package org.stypox.dicio.skills.places

import androidx.datastore.core.DataStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.online.GarHttp
import org.stypox.dicio.gar.online.GarHttpStatusException
import org.stypox.dicio.gar.settings.GarAddressPoint
import org.stypox.dicio.gar.settings.GarSettings
import java.io.IOException
import java.util.Locale

/** What [AddressLookup.find] found for an address. */
sealed interface LookupResult {
    data class Found(val point: GeoPoint) : LookupResult

    /** Nominatim knows no such address (or didn't, less than 30 days ago). */
    data object NotFound : LookupResult

    /** No connection, a timeout, a server error or an answer GAR can't read. */
    data object Failed : LookupResult

    /** Nominatim answered 403, 429 or 509 less than a day ago: no lookups until then. */
    data object Paused : LookupResult
}

/** Finds the point of a text address, for a route ("Find addresses online"). */
fun interface AddressLookup {
    suspend fun find(address: String): LookupResult

    companion object {
        fun of(ctx: SkillContext): AddressLookup = NominatimLookup(
            GarHttp.default,
            System::currentTimeMillis,
            AddressPointStore.of(ctx),
            language = ctx.locale.language.ifEmpty { "en" },
        )
    }
}

/**
 * At most one request per [gapMillis] through [pass], process-wide when shared
 * ([NOMINATIM]): a request waits for the rest of the gap after the one before it started.
 */
class RateGate(
    private val gapMillis: Long,
    private val clock: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) {
    private val mutex = Mutex()
    private var last: Long? = null

    suspend fun <T> pass(request: suspend () -> T): T = mutex.withLock {
        last?.let { previous ->
            val wait = previous + gapMillis - clock()
            if (wait > 0) {
                sleep(wait)
            }
        }
        last = clock()
        request()
    }

    companion object {
        /** Nominatim's usage policy: an absolute maximum of 1 request per second. */
        val NOMINATIM = RateGate(1000L, System::currentTimeMillis) { delay(it) }
    }
}

/**
 * GAR (gar:places). One address to a point through OpenStreetMap's Nominatim, as its usage
 * policy asks (operations.osmfoundation.org/policies/nominatim): GAR names itself in the
 * User-Agent ([GarHttp.USER_AGENT]), sends at most one request a second ([RateGate.NOMINATIM]),
 * one address per command and never in the background or for a list, keeps what it found
 * ([AddressPointStore]: a point for good, "not found" for 30 days) and stops for a day after a
 * 403, 429 or 509, which is also how the OSMF can switch GAR off without an update. No
 * autocomplete, no reverse lookups. Plain JVM: the HTTP client, clock and gate are injected.
 */
class NominatimLookup(
    private val http: GarHttp,
    private val clock: () -> Long,
    private val store: AddressPointStore,
    private val gate: RateGate = RateGate.NOMINATIM,
    private val language: String = "en",
) : AddressLookup {

    override suspend fun find(address: String): LookupResult {
        val text = address.trim()
        if (text.isEmpty()) {
            return LookupResult.NotFound
        }
        val now = clock()
        store.get(text)?.let { cached ->
            if (cached.point != null) {
                return LookupResult.Found(cached.point)
            }
            if (now - cached.at < NOT_FOUND_MILLIS) {
                return LookupResult.NotFound
            }
        }
        if (store.pausedUntil() > now) {
            return LookupResult.Paused
        }
        return gate.pass {
            try {
                val body = http.get(url(text, language)) ?: return@pass LookupResult.Failed
                val point = parse(body)
                store.put(text, point, clock())
                if (point == null) LookupResult.NotFound else LookupResult.Found(point)
            } catch (e: GarHttpStatusException) {
                if (e.code in BLOCKED) {
                    store.pause(clock() + PAUSE_MILLIS)
                    LookupResult.Paused
                } else {
                    LookupResult.Failed
                }
            } catch (e: IOException) {
                LookupResult.Failed
            } catch (e: IllegalArgumentException) {
                // not JSON, or not the shape Nominatim answers with
                LookupResult.Failed
            }
        }
    }

    companion object {
        const val NOT_FOUND_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val PAUSE_MILLIS = 24L * 60 * 60 * 1000
        private val BLOCKED = setOf(403, 429, 509)

        fun url(address: String, language: String): String =
            "https://nominatim.openstreetmap.org/search?q=${MapLinks.encode(address)}" +
                "&format=jsonv2&limit=1&accept-language=${MapLinks.encode(language)}"

        /**
         * The first result's point (`[{"lat": "40.7", "lon": "-74.0", ...}]`, strings), or null
         * for no result.
         * @throws IllegalArgumentException for anything else
         */
        fun parse(body: String): GeoPoint? {
            val results = Json.parseToJsonElement(body) as? JsonArray
                ?: throw IllegalArgumentException("Not a list")
            val first = results.firstOrNull() as? JsonObject ?: return null
            val lat = (first["lat"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            val lon = (first["lon"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            require(lat != null && lon != null) { "No lat and lon" }
            return GeoPoint(lat, lon).takeIf(PlaceLinks::valid)
        }
    }
}

/** A looked-up address: its [point], or null when Nominatim found none, and when ([at], epoch ms). */
data class CachedPoint(val point: GeoPoint?, val at: Long)

/** What [NominatimLookup] found, kept in `GarSettings.address_points`, and its pause. */
interface AddressPointStore {
    suspend fun get(address: String): CachedPoint?

    /** [point] null: not found. Keeps the newest [MAX_ENTRIES]. */
    suspend fun put(address: String, point: GeoPoint?, at: Long)

    suspend fun pausedUntil(): Long
    suspend fun pause(until: Long)

    companion object {
        const val MAX_ENTRIES = 100

        /** The cache key: lowercase, one space, no punctuation at the ends. */
        fun key(address: String): String = address.lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim { it.isWhitespace() || it in ".,;:!?" }

        fun of(ctx: SkillContext): AddressPointStore = GarSettingsAddressPoints(
            EntryPointAccessors.fromApplication(ctx.android.applicationContext, GarEntryPoint::class.java)
                .garSettings()
        )
    }
}

class GarSettingsAddressPoints(private val settings: DataStore<GarSettings>) : AddressPointStore {
    override suspend fun get(address: String): CachedPoint? {
        val key = AddressPointStore.key(address)
        val stored = settings.data.first().addressPointsList.lastOrNull { it.key == key } ?: return null
        return CachedPoint(if (stored.found) GeoPoint(stored.latitude, stored.longitude) else null, stored.at)
    }

    override suspend fun put(address: String, point: GeoPoint?, at: Long) {
        val key = AddressPointStore.key(address)
        val entry = GarAddressPoint.newBuilder()
            .setKey(key)
            .setFound(point != null)
            .setLatitude(point?.latitude ?: 0.0)
            .setLongitude(point?.longitude ?: 0.0)
            .setAt(at)
            .build()
        settings.updateData { data ->
            val kept = (data.addressPointsList.filter { it.key != key } + entry)
                .sortedBy { it.at }
                .takeLast(AddressPointStore.MAX_ENTRIES)
            data.toBuilder().clearAddressPoints().addAllAddressPoints(kept).build()
        }
    }

    override suspend fun pausedUntil(): Long = settings.data.first().addressLookupPausedUntil

    override suspend fun pause(until: Long) {
        settings.updateData { it.toBuilder().setAddressLookupPausedUntil(until).build() }
    }
}

/** The "Navigate with" and "Find addresses online" settings, and what GAR said once. */
interface NavigationPrefs {
    /** `GarSettings.navigation_app`, see [MapAppChoice.choose]. */
    suspend fun app(): String

    /** Saves the app picked with "Always use this app". */
    suspend fun setApp(pkg: String)
    suspend fun lookup(): Boolean
    suspend fun lookupAsked(): Boolean

    /** Turns "Find addresses online" on or off; either way GAR has asked. */
    suspend fun setLookup(on: Boolean)
    suspend fun setLookupAsked()
    suspend fun tapRouteSaid(): Boolean
    suspend fun setTapRouteSaid()

    companion object {
        fun of(ctx: SkillContext): NavigationPrefs = GarSettingsNavigationPrefs(
            EntryPointAccessors.fromApplication(ctx.android.applicationContext, GarEntryPoint::class.java)
                .garSettings()
        )
    }
}

class GarSettingsNavigationPrefs(private val settings: DataStore<GarSettings>) : NavigationPrefs {
    override suspend fun app(): String = settings.data.first().navigationApp

    override suspend fun setApp(pkg: String) {
        settings.updateData { it.toBuilder().setNavigationApp(pkg).build() }
    }

    override suspend fun lookup(): Boolean = settings.data.first().addressLookup
    override suspend fun lookupAsked(): Boolean = settings.data.first().addressLookupAsked

    override suspend fun setLookup(on: Boolean) {
        settings.updateData { it.toBuilder().setAddressLookup(on).setAddressLookupAsked(true).build() }
    }

    override suspend fun setLookupAsked() {
        settings.updateData { it.toBuilder().setAddressLookupAsked(true).build() }
    }

    override suspend fun tapRouteSaid(): Boolean = settings.data.first().tapRouteSaid

    override suspend fun setTapRouteSaid() {
        settings.updateData { it.toBuilder().setTapRouteSaid(true).build() }
    }
}
