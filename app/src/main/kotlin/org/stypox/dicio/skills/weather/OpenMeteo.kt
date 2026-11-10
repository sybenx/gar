package org.stypox.dicio.skills.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToLong

/** GETs a page (gar:weather); tests use a fake, so that no test touches the network. */
fun interface WeatherHttp {
    /** The body of [url], or throws [IOException]. */
    @Throws(IOException::class)
    fun get(url: String): String

    companion object {
        /** HttpURLConnection with timeouts, so that a dead network fails within seconds. */
        val DEFAULT = WeatherHttp { url ->
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) {
                    throw IOException("HTTP ${connection.responseCode} from ${connection.url.host}")
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

        private const val TIMEOUT_MILLIS = 8000
    }
}

/** A place found by name, or the phone's location. Coordinates are rounded before they are sent. */
data class WeatherPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val region: String? = null,
    val countryCode: String? = null,
)

/**
 * Open-Meteo (open-meteo.com): open source, no API key, free for non-commercial use with
 * attribution (CC BY 4.0), so GAR names it on the card. GAR's online source for the weather
 * (gar:weather), in place of Dicio's OpenWeatherMap with a shared key and IPinfo:
 * - the geocoding API gets the place's name as said ("salt lake city"), nothing else;
 * - the forecast API gets coordinates rounded to two decimals (about 1 km) and the variables
 *   GAR shows, in Celsius and km/h (GAR converts).
 */
class OpenMeteo(private val http: WeatherHttp = WeatherHttp.DEFAULT) {

    /**
     * The place named [name], preferring one in [countryCode] (the phone's country: "logan" is
     * Logan, Utah for an American, not Logan City in Australia, which has more people). "logan
     * utah" and "paris france" are searched as "logan" and "paris", then picked by region or
     * country. Null when nothing matches.
     */
    @Throws(IOException::class)
    fun findPlace(name: String, countryCode: String?): WeatherPlace? {
        val words = name.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        // the whole name first, then without its last words as a region or a country
        for (keep in words.size downTo 1) {
            val search = words.take(keep).joinToString(" ")
            val qualifier = words.drop(keep).joinToString(" ")
            val results = search(search)
            if (results.isEmpty()) continue
            val picked = if (qualifier.isEmpty()) {
                results.firstOrNull { it.countryCode.equals(countryCode, ignoreCase = true) }
                    ?: results.first()
            } else {
                results.firstOrNull { it.matches(qualifier) } ?: continue
            }
            return picked
        }
        return null
    }

    private fun WeatherPlace.matches(qualifier: String): Boolean {
        val q = qualifier.lowercase(Locale.ROOT)
        val country = countryCode?.let { Locale("", it).getDisplayCountry(Locale.ENGLISH) }
        return listOfNotNull(region, country, countryCode).any { it.lowercase(Locale.ROOT) == q } ||
            (q in US_NAMES && countryCode == "US") ||
            (q in UK_NAMES && countryCode == "GB") ||
            US_STATE_CODES[q]?.equals(region, ignoreCase = true) == true
    }

    private fun search(name: String): List<WeatherPlace> {
        val url = "$GEOCODING_URL?name=${encode(name)}&count=10&language=en&format=json"
        return parsePlaces(http.get(url))
    }

    /** The forecast at [place]: current conditions, [DAYS] days and [HOURS] hours. */
    @Throws(IOException::class)
    fun forecast(place: WeatherPlace): WeatherForecast {
        val url = "$FORECAST_URL?latitude=${rounded(place.latitude)}" +
            "&longitude=${rounded(place.longitude)}" +
            "&current=temperature_2m,weather_code,wind_speed_10m,is_day" +
            "&hourly=temperature_2m,precipitation_probability,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
            "precipitation_probability_max,snowfall_sum,wind_speed_10m_max" +
            "&timezone=auto&forecast_days=$DAYS&forecast_hours=$HOURS"
        return parseForecast(http.get(url), place.name)
    }

    companion object {
        const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val DAYS = 8
        const val HOURS = 24

        private val US_NAMES = setOf("usa", "us", "america", "united states")
        private val UK_NAMES = setOf("uk", "england", "britain", "scotland", "wales")
        private val US_STATE_CODES = mapOf(
            "ut" to "Utah", "co" to "Colorado", "ca" to "California", "ny" to "New York",
            "tx" to "Texas", "wa" to "Washington", "id" to "Idaho", "az" to "Arizona",
            "nv" to "Nevada", "or" to "Oregon", "wy" to "Wyoming", "nm" to "New Mexico",
            "fl" to "Florida", "il" to "Illinois", "ma" to "Massachusetts", "ga" to "Georgia",
        )

        /** Two decimals: about 1 km, enough for a forecast and no more precise than needed. */
        fun rounded(coordinate: Double): String {
            val value = (coordinate * 100).roundToLong() / 100.0
            return String.format(Locale.ROOT, "%.2f", value)
        }

        private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

        fun parsePlaces(body: String): List<WeatherPlace> {
            val results = (Json.parseToJsonElement(body) as? JsonObject)?.get("results") as? JsonArray
                ?: return listOf()
            return results.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                WeatherPlace(
                    name = o.string("name") ?: return@mapNotNull null,
                    latitude = o.double("latitude") ?: return@mapNotNull null,
                    longitude = o.double("longitude") ?: return@mapNotNull null,
                    region = o.string("admin1"),
                    countryCode = o.string("country_code"),
                )
            }
        }

        /** Open-Meteo's forecast JSON, with local times at the place (`timezone=auto`). */
        fun parseForecast(body: String, placeName: String): WeatherForecast {
            val root = Json.parseToJsonElement(body) as? JsonObject
                ?: throw IOException("Open-Meteo sent no forecast")
            val currentObject = root["current"] as? JsonObject
            val now = currentObject?.string("time")?.let(::parseTime)
                ?: throw IOException("Open-Meteo sent no current time")
            val current = currentObject.double("temperature_2m")?.let { temperature ->
                WeatherNow(
                    temperature = temperature,
                    condition = WeatherCondition.fromWmo(currentObject.int("weather_code")),
                    wind = currentObject.double("wind_speed_10m"),
                    isDay = currentObject.int("is_day")?.let { it != 0 }
                        ?: currentObject.bool("is_day") ?: true,
                )
            }

            val daily = root["daily"] as? JsonObject
            val days = daily?.array("time")?.mapIndexedNotNull { i, time ->
                val date = (time as? JsonPrimitive)?.content?.let(LocalDate::parse)
                    ?: return@mapIndexedNotNull null
                WeatherDay(
                    date = date,
                    high = daily.doubleAt("temperature_2m_max", i),
                    low = daily.doubleAt("temperature_2m_min", i),
                    condition = WeatherCondition.fromWmo(daily.doubleAt("weather_code", i)?.toInt()),
                    precipitationChance = daily.doubleAt("precipitation_probability_max", i)?.toInt(),
                    snowfall = daily.doubleAt("snowfall_sum", i),
                    wind = daily.doubleAt("wind_speed_10m_max", i),
                )
            }.orEmpty()

            val hourly = root["hourly"] as? JsonObject
            val hours = hourly?.array("time")?.mapIndexedNotNull { i, time ->
                val at = (time as? JsonPrimitive)?.content?.let(::parseTime)
                    ?: return@mapIndexedNotNull null
                WeatherHour(
                    time = at,
                    temperature = hourly.doubleAt("temperature_2m", i),
                    condition = WeatherCondition.fromWmo(hourly.doubleAt("weather_code", i)?.toInt()),
                    precipitationChance = hourly.doubleAt("precipitation_probability", i)?.toInt(),
                )
            }.orEmpty()

            return WeatherForecast(
                placeName = placeName,
                now = now,
                current = current,
                days = days,
                hours = hours,
                source = WeatherSource.OPEN_METEO,
            )
        }

        private fun parseTime(text: String): LocalDateTime? = try {
            LocalDateTime.parse(text)
        } catch (_: Exception) {
            null
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

        private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

        private fun JsonObject.doubleAt(key: String, index: Int): Double? {
            val element: JsonElement = array(key)?.getOrNull(index) ?: return null
            return if (element is JsonNull) null else (element as? JsonPrimitive)?.doubleOrNull
        }
    }
}
