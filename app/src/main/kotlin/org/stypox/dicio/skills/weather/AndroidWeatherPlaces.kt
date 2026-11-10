package org.stypox.dicio.skills.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.dicio.skill.skill.Permission
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.skills.weather.WeatherInfo.weatherDataStore
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The approximate location, which Dicio asks for before "use my location" runs (as the weather
 * skill's [WeatherLocationInfo.neededPermissions]): only for that answer, never for every weather
 * request. Coarse only: a forecast needs no more, and GAR rounds it to about 1 km anyway.
 */
val PERMISSION_COARSE_LOCATION = Permission.NormalPermission(
    name = R.string.gar_weather_perm_location,
    id = Manifest.permission.ACCESS_COARSE_LOCATION,
)

/**
 * [WeatherPlaces] on the phone: the home city is the weather skill's "Default city" setting
 * (Dicio's, shown in Settings > Skills > Weather), "use my location" is GAR's setting, and the
 * location comes from Android's LocationManager (no Google services on GrapheneOS): a fix from
 * the last [RECENT_MILLIS], else a new one within [FIX_TIMEOUT_MILLIS].
 */
class AndroidWeatherPlaces(private val context: Context) : WeatherPlaces {

    override val countryCode: String? = Locale.getDefault().country.takeIf { it.isNotEmpty() }

    private val garSettings
        get() = EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
            .garSettings()

    override suspend fun homeCity(): String = context.weatherDataStore.data.first().defaultCity

    override suspend fun setHomeCity(name: String) {
        context.weatherDataStore.updateData { it.toBuilder().setDefaultCity(name).build() }
    }

    override suspend fun useLocation(): Boolean = garSettings.data.first().weatherUseLocation

    override suspend fun setUseLocation(use: Boolean) {
        garSettings.updateData { it.toBuilder().setWeatherUseLocation(use).build() }
    }

    override fun hasLocationPermission(): Boolean = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
    ).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission") // hasLocationPermission
    override suspend fun location(): WeatherPlace? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        if (!manager.isLocationEnabled) return null
        val providers = providers(manager)

        val recent = providers.mapNotNull { lastKnown(manager, it) }
            .filter { ageMillis(it) <= RECENT_MILLIS }
            .maxByOrNull { it.elapsedRealtimeNanos }
        val location = recent ?: withTimeoutOrNull(FIX_TIMEOUT_MILLIS) {
            providers.firstNotNullOfOrNull { currentLocation(manager, it) }
        } ?: providers.mapNotNull { lastKnown(manager, it) }
            .filter { ageMillis(it) <= OLD_MILLIS }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?: return null
        return WeatherPlace(name = "", latitude = location.latitude, longitude = location.longitude)
    }

    /** The enabled providers, the cheapest approximate ones first. */
    private fun providers(manager: LocationManager): List<String> {
        val enabled = manager.getProviders(true)
        val order = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
        return order.filter { it in enabled }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager, provider: String): Location? = try {
        manager.getLastKnownLocation(provider)
    } catch (_: SecurityException) {
        null // the GPS provider needs the precise location before Android 12
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun ageMillis(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(manager: LocationManager, provider: String): Location? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val cancel = android.os.CancellationSignal()
                        continuation.invokeOnCancellation { cancel.cancel() }
                        manager.getCurrentLocation(provider, cancel, context.mainExecutor) {
                            if (continuation.isActive) continuation.resume(it)
                        }
                    } else {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                if (continuation.isActive) continuation.resume(location)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                            override fun onProviderEnabled(p: String) {}
                            override fun onProviderDisabled(p: String) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                        @Suppress("DEPRECATION") // getCurrentLocation is API 30
                        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    }
                } catch (_: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                } catch (_: IllegalArgumentException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    companion object {
        const val RECENT_MILLIS = 30 * 60 * 1000L
        const val OLD_MILLIS = 6 * 60 * 60 * 1000L
        const val FIX_TIMEOUT_MILLIS = 15_000L
    }
}
