package org.stypox.dicio.skills.weather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Breezy Weather (org.breezyweather) on the phone shares its forecasts with apps the user picks:
 * Breezy's Settings > Widgets & Live Wallpaper > Data sharing > "Send Gadgetbridge data" lists
 * the apps with a receiver for [ACTION] (GAR among them) and, after each refresh, sends an
 * explicit broadcast to each one picked (`RefreshHelper.broadcastDataIfNecessary`, checked in
 * Breezy's source, v6.2.2): `WeatherJson` (the first location, Gadgetbridge's WeatherSpec JSON)
 * and `WeatherGz` (all locations, a gzipped JSON array of the same). Temperatures are in Kelvin,
 * wind in km/h, `timestamp` is when Breezy's forecast was updated (seconds), `forecasts` start
 * tomorrow and `hourly` has the next hours. GAR keeps the last broadcast in a file and answers
 * from it offline while it is fresh ([BreezyWeatherData.usable]); nothing goes to the internet.
 *
 * The receiver is exported with no permission, as Breezy needs, and Android can't say who sent a
 * broadcast: any app on the phone could send one. GAR drops broadcasts while Breezy isn't
 * installed, and unzips and saves off the main thread.
 *
 * Breezy also has a content provider behind a dangerous permission (`org.breezyweather
 * .READ_PROVIDER`), still marked as being tested by its developers; GAR uses the broadcast,
 * whose format Gadgetbridge has kept for years.
 */
class BreezyWeatherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || !breezyInstalled(context)) return
        val pending = goAsync()
        thread(name = TAG) {
            try {
                val json = intent.getByteArrayExtra(EXTRA_GZ)?.let(::gunzip)
                    ?: intent.getStringExtra(EXTRA_JSON)
                if (json != null) {
                    BreezyWeatherStore(context).save(json, Instant.now())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't keep Breezy Weather's data", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun breezyInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val ACTION = "nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER"
        const val EXTRA_JSON = "WeatherJson"
        const val EXTRA_GZ = "WeatherGz"
        const val PACKAGE = "org.breezyweather"
        private const val TAG = "BreezyWeatherReceiver"

        /**
         * A broadcast's compressed data, unzipped to at most [MAX_BYTES]. Binder keeps the
         * zipped extra under about 1 MB; the JSON unzips to several times that, so the cap is
         * on the unzipped size, against a broadcast that unzips without end.
         */
        fun gunzip(bytes: ByteArray): String =
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                // not readNBytes, which needs API 33
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    require(out.size() <= MAX_BYTES) { "Breezy Weather's data is too large" }
                }
                out.toString("UTF-8")
            }

        private const val MAX_BYTES = 4_000_000
    }
}

/** Where GAR keeps Breezy Weather's last broadcast: the JSON and when it came. */
class BreezyWeatherStore(context: Context) {
    private val dir = File(context.filesDir, "gar-weather")
    private val file = File(dir, "breezy.json")

    fun save(json: String, received: Instant) {
        dir.mkdirs()
        val tmp = File(dir, "breezy.json.tmp")
        tmp.writeText(json)
        tmp.setLastModified(received.toEpochMilli())
        tmp.renameTo(file)
    }

    /** The last data, or null when Breezy never sent any (or it can't be read). */
    fun read(zone: ZoneId = ZoneId.systemDefault()): List<WeatherForecast>? = try {
        if (file.isFile) {
            BreezyWeatherData.parse(file.readText(), Instant.ofEpochMilli(file.lastModified()), zone)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

/** Reading Breezy Weather's Gadgetbridge data, and when it is fresh enough to answer from. */
object BreezyWeatherData {
    /** Current conditions and the next hours: Breezy refreshes every hour or so by default. */
    val CURRENT_MAX_AGE: Duration = Duration.ofHours(2)

    /** Today's and later days' highs, lows and chances. */
    val DAYS_MAX_AGE: Duration = Duration.ofHours(8)

    private const val KELVIN = 273.15

    /**
     * Breezy's locations in [json] (one object, or an array of them), each as a forecast at the
     * phone's [zone] (the data has no time zone): days from the day it was updated on, the hours
     * it sent. [received] stands in for a missing update time.
     */
    fun parse(json: String, received: Instant, zone: ZoneId): List<WeatherForecast> {
        val objects = when (val root = Json.parseToJsonElement(json)) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> listOf()
        }
        return objects.map { location(it, received, zone) }
    }

    private fun location(o: JsonObject, received: Instant, zone: ZoneId): WeatherForecast {
        val updated = o.int("timestamp")?.let { Instant.ofEpochSecond(it.toLong()) } ?: received
        val updatedAt = LocalDateTime.ofInstant(updated, zone)
        val today = updatedAt.toLocalDate()
        val currentCondition = WeatherCondition.fromOwm(o.int("currentConditionCode"))
        val current = o.double("currentTemp")?.let {
            WeatherNow(it - KELVIN, currentCondition, o.double("windSpeed"))
        }
        val todayForecast = WeatherDay(
            date = today,
            high = o.double("todayMaxTemp")?.minus(KELVIN),
            low = o.double("todayMinTemp")?.minus(KELVIN),
            condition = currentCondition,
            precipitationChance = o.int("precipProbability"),
            wind = o.double("windSpeed"),
        )
        val later = (o["forecasts"] as? JsonArray).orEmpty().mapIndexedNotNull { i, element ->
            val day = element as? JsonObject ?: return@mapIndexedNotNull null
            WeatherDay(
                date = today.plusDays(i + 1L),
                high = day.double("maxTemp")?.minus(KELVIN),
                low = day.double("minTemp")?.minus(KELVIN),
                condition = WeatherCondition.fromOwm(day.int("conditionCode")),
                precipitationChance = day.int("precipProbability"),
                wind = day.double("windSpeed"),
            )
        }
        val hours = (o["hourly"] as? JsonArray).orEmpty().mapNotNull { element ->
            val hour = element as? JsonObject ?: return@mapNotNull null
            val at = hour.int("timestamp") ?: return@mapNotNull null
            WeatherHour(
                time = LocalDateTime.ofInstant(Instant.ofEpochSecond(at.toLong()), zone),
                temperature = hour.double("temp")?.minus(KELVIN),
                condition = WeatherCondition.fromOwm(hour.int("conditionCode")),
                precipitationChance = hour.int("precipProbability"),
            )
        }
        return WeatherForecast(
            placeName = (o["location"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: "",
            now = updatedAt,
            current = current,
            days = listOf(todayForecast) + later,
            hours = hours,
            source = WeatherSource.BREEZY_WEATHER,
            updated = updated,
        )
    }

    /**
     * The forecast among Breezy's [locations] that answers [request] at [now], or null to ask
     * Open-Meteo: the place said must be one of Breezy's locations (no place: Breezy's first, the
     * user's own), and the data fresh enough for what is asked ([CURRENT_MAX_AGE] for now and
     * the next hours, [DAYS_MAX_AGE] for days). The forecast comes back with [now] as its time.
     */
    fun usable(
        request: WeatherRequest,
        locations: List<WeatherForecast>?,
        now: Instant,
        zone: ZoneId,
    ): WeatherForecast? {
        val location = locations?.let { all ->
            val place = request.place
            if (place == null) all.firstOrNull() else all.firstOrNull { namesPlace(it.placeName, place) }
        } ?: return null
        val age = Duration.between(location.updated ?: return null, now)
        if (age.isNegative) return null
        val maxAge = when (request.time) {
            WeatherWhen.Now, WeatherWhen.Hours -> CURRENT_MAX_AGE
            else -> DAYS_MAX_AGE
        }
        if (age > maxAge) return null
        val nowAtPlace = LocalDateTime.ofInstant(now, zone)
        val answered = location.copy(now = nowAtPlace)
        // the days asked must be in the data
        val needed = when (val time = request.time) {
            is WeatherWhen.Day -> listOf(time.date)
            is WeatherWhen.Night -> listOf(time.date)
            is WeatherWhen.Weekend -> listOf(time.saturday, time.saturday.plusDays(1))
            is WeatherWhen.Week -> listOf(time.from)
            WeatherWhen.Now -> listOf()
            WeatherWhen.Hours -> listOf()
        }
        if (needed.any { answered.day(it) == null }) return null
        if (request.time == WeatherWhen.Now && answered.current == null) return null
        if (request.time == WeatherWhen.Hours && answered.nextHours.isEmpty()) return null
        return answered
    }

    /** "Logan" names "Logan, Utah"; "salt lake city" names "Salt Lake City". */
    fun namesPlace(placeName: String, said: String): Boolean {
        fun words(s: String) = s.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
        val name = words(placeName)
        val asked = words(said)
        return asked.isNotEmpty() && name.size >= asked.size &&
            (0..name.size - asked.size).any { start -> asked.indices.all { name[start + it] == asked[it] } }
    }

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
}
