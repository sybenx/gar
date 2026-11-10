package org.stypox.dicio.skills.places

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarEntryPoint
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.round

/** How much of the phone's location GAR may read (Android 12+ lets the user give approximate only). */
enum class LocationAccess { NONE, APPROXIMATE, PRECISE }

/**
 * Where the phone is.
 *
 * @param accuracyMeters the radius Android is 68% sure of, if it said one
 * @param precise false when GAR only has approximate access (a point within about 2 km)
 */
data class DeviceFix(val point: GeoPoint, val accuracyMeters: Float?, val precise: Boolean)

/** What the places skills read of the phone's location, so their decisions can be tested. */
interface DeviceLocation {
    fun access(): LocationAccess

    /** Asks for location access with Android's own dialog (precise or approximate, the user's pick). */
    suspend fun requestAccess(): LocationAccess

    /** Whether location is on in the phone's settings. */
    fun isEnabled(): Boolean

    /** A fresh fix, or null when none came in time. */
    suspend fun current(): DeviceFix?

    /**
     * The name of the place at [point] ("12 Main Street, Springfield") from Android's Geocoder,
     * or null when the phone has none (GrapheneOS ships no Geocoder backend) or it found none.
     */
    suspend fun placeName(point: GeoPoint): String?

    /**
     * GAR (gar:places). Android's newest last-known fix no older than [maxAgeMillis], without
     * asking for access or a new fix (no wait, no dialog); null when there is none.
     */
    fun lastKnown(maxAgeMillis: Long): DeviceFix? = null

    companion object {
        fun of(ctx: SkillContext): DeviceLocation = AndroidDeviceLocation(
            ctx.android,
            requester = { permissions ->
                EntryPointAccessors.fromApplication(ctx.android.applicationContext, GarEntryPoint::class.java)
                    .skillEvaluator().permissionRequester(permissions)
            },
        )
    }
}

/** The result of [LocationLookup.locate]. */
sealed interface LocationResult {
    /** @param name the place's name, when asked for and the phone could name it */
    data class Found(val fix: DeviceFix, val name: String?) : LocationResult

    /** The user didn't allow location access. */
    data object NoAccess : LocationResult

    /** Location is off in the phone's settings. */
    data object Off : LocationResult

    /** No fix came in time (indoors, no network location). */
    data object NoFix : LocationResult
}

/**
 * GAR (gar:places). Finds where the phone is for "where am I", "share my location" and "use my
 * current location": asks for access when GAR has none, then a fresh fix, then, for [locate]'s
 * `withName`, the place's name. The Geocoder only gets the point rounded to about 1 km
 * ([forNaming]): a backend may send it to a server, and a street's name needs no more.
 */
object LocationLookup {
    suspend fun locate(location: DeviceLocation, withName: Boolean): LocationResult {
        var access = location.access()
        if (access == LocationAccess.NONE) {
            access = location.requestAccess()
        }
        if (access == LocationAccess.NONE) {
            return LocationResult.NoAccess
        }
        if (!location.isEnabled()) {
            return LocationResult.Off
        }
        val fix = location.current() ?: return LocationResult.NoFix
        val name = if (withName) location.placeName(forNaming(fix.point)) else null
        return LocationResult.Found(fix, name)
    }

    /** [point] rounded to two decimals: about 1.1 km north to south, less east to west. */
    fun forNaming(point: GeoPoint): GeoPoint =
        GeoPoint(round(point.latitude * 100) / 100, round(point.longitude * 100) / 100)
}

val PERMISSION_LOCATION_APPROXIMATE = Permission.NormalPermission(
    name = R.string.perm_gar_location_approximate,
    id = Manifest.permission.ACCESS_COARSE_LOCATION,
)
val PERMISSION_LOCATION_PRECISE = Permission.NormalPermission(
    name = R.string.perm_gar_location_precise,
    id = Manifest.permission.ACCESS_FINE_LOCATION,
)

/**
 * [DeviceLocation] on Android's [LocationManager] (no Google Play services: GrapheneOS has GPS and,
 * when the user turned it on, its own network location). Access is asked through Dicio's
 * permission requester, both permissions at once as Android 12+ wants, so its dialog offers
 * precise and approximate; any of the two is enough.
 *
 * @param requester Dicio's permission requester (`SkillEvaluator.permissionRequester`)
 */
class AndroidDeviceLocation(
    private val context: Context,
    private val requester: suspend (List<Permission>) -> Boolean,
) : DeviceLocation {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)

    override fun access(): LocationAccess = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationAccess.PRECISE
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAccess.APPROXIMATE
        else -> LocationAccess.NONE
    }

    override suspend fun requestAccess(): LocationAccess {
        try {
            withContext(Dispatchers.Main) {
                requester(listOf(PERMISSION_LOCATION_APPROXIMATE, PERMISSION_LOCATION_PRECISE))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not ask for location access", e)
        }
        return access()
    }

    override fun isEnabled(): Boolean = manager?.isLocationEnabled == true

    @SuppressLint("MissingPermission") // checked by access()
    override suspend fun current(): DeviceFix? {
        val manager = manager ?: return null
        val access = access()
        if (access == LocationAccess.NONE) {
            return null
        }
        val precise = access == LocationAccess.PRECISE
        val providers = providers(manager, precise)

        val lastKnown = providers.mapNotNull { lastKnown(manager, it) }
        lastKnown.filter { ageMillis(it) <= RECENT_MILLIS }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
            ?.let { return fixOf(it, precise) }

        // the first fresh fix of any provider: the network's comes in seconds, GPS may need longer
        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MILLIS) {
            channelFlow {
                for (provider in providers) {
                    launch { single(manager, provider)?.let { send(it) } }
                }
            }.firstOrNull()
        }
        return (fresh ?: lastKnown.filter { ageMillis(it) <= STALE_MILLIS }.maxByOrNull { it.time })
            ?.let { fixOf(it, precise) }
    }

    override suspend fun placeName(point: GeoPoint): String? {
        if (!Geocoder.isPresent()) {
            return null
        }
        return withTimeoutOrNull(GEOCODER_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) {
                try {
                    addresses(Geocoder(context, Locale.getDefault()), point)?.firstOrNull()?.let(::nameOf)
                } catch (e: Exception) {
                    Log.w(TAG, "The Geocoder failed", e)
                    null
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // checked by access()
    override fun lastKnown(maxAgeMillis: Long): DeviceFix? {
        val manager = manager ?: return null
        val access = access()
        if (access == LocationAccess.NONE) {
            return null
        }
        return providers(manager, access == LocationAccess.PRECISE)
            .mapNotNull { lastKnown(manager, it) }
            .filter { ageMillis(it) <= maxAgeMillis }
            .maxByOrNull { it.time }
            ?.let { fixOf(it, access == LocationAccess.PRECISE) }
    }

    private suspend fun addresses(geocoder: Geocoder, point: GeoPoint): List<Address>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(point.latitude, point.longitude, 1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            Log.w(TAG, "The Geocoder failed: $errorMessage")
                            if (cont.isActive) cont.resume(null)
                        }
                    })
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(point.latitude, point.longitude, 1)
        }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager, provider: String): Location? = try {
        manager.getLastKnownLocation(provider)
    } catch (e: Exception) {
        Log.w(TAG, "No last location from $provider", e)
        null
    }

    /** One fix from [provider], or null if it has none. */
    @SuppressLint("MissingPermission")
    private suspend fun single(manager: LocationManager, provider: String): Location? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }
        } else {
            callbackFlow {
                // every method implemented: before Android 11 they have no default bodies
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        trySend(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        close()
                    }
                }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                awaitClose { manager.removeUpdates(listener) }
            }.firstOrNull()
        }
    } catch (e: Exception) {
        // a provider that went away, or access taken back meanwhile
        Log.w(TAG, "No fix from $provider", e)
        null
    }

    private fun fixOf(location: Location, precise: Boolean) = DeviceFix(
        GeoPoint(location.latitude, location.longitude),
        if (location.hasAccuracy()) location.accuracy else null,
        precise,
    )

    companion object {
        private const val TAG = "AndroidDeviceLocation"
        private const val RECENT_MILLIS = 2 * 60_000L
        private const val STALE_MILLIS = 15 * 60_000L
        private const val FIX_TIMEOUT_MILLIS = 20_000L
        private const val GEOCODER_TIMEOUT_MILLIS = 5_000L

        /**
         * The providers to ask, the best first: fused when the phone has one (Android 12+),
         * GPS with precise access, then the network's, each only while it is on.
         */
        fun providers(manager: LocationManager, precise: Boolean): List<String> {
            val all = manager.allProviders
            return buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(LocationManager.FUSED_PROVIDER)
                }
                if (precise) {
                    add(LocationManager.GPS_PROVIDER)
                }
                add(LocationManager.NETWORK_PROVIDER)
            }.filter { it in all && isOn(manager, it) }
        }

        private fun isOn(manager: LocationManager, provider: String): Boolean = try {
            manager.isProviderEnabled(provider)
        } catch (e: Exception) {
            false
        }

        private fun ageMillis(location: Location): Long =
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

        /** "12 Main Street, Springfield", or Android's first address line. */
        fun nameOf(address: Address): String? {
            val street = listOfNotNull(address.subThoroughfare, address.thoroughfare)
                .joinToString(" ").takeIf { it.isNotBlank() }
            val parts = listOfNotNull(street ?: address.featureName, address.locality)
                .filter { it.isNotBlank() }.distinct()
            return parts.joinToString(", ").takeIf { it.isNotBlank() }
                ?: address.getAddressLine(0)?.takeIf { it.isNotBlank() }
        }
    }
}
