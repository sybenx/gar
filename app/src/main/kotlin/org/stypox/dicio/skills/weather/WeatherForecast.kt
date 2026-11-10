package org.stypox.dicio.skills.weather

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** The sky in a few words (gar:weather), from Open-Meteo's WMO codes or Breezy Weather's. */
enum class WeatherCondition {
    CLEAR, MOSTLY_CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, HEAVY_RAIN, FREEZING_RAIN,
    SHOWERS, SNOW, SNOW_SHOWERS, SLEET, THUNDERSTORM, WINDY, UNKNOWN;

    val isRain: Boolean get() = this in setOf(DRIZZLE, RAIN, HEAVY_RAIN, FREEZING_RAIN, SHOWERS, THUNDERSTORM)
    val isSnow: Boolean get() = this in setOf(SNOW, SNOW_SHOWERS, SLEET)

    companion object {
        /** WMO weather interpretation codes, as Open-Meteo gives them. */
        fun fromWmo(code: Int?): WeatherCondition = when (code) {
            0 -> CLEAR
            1 -> MOSTLY_CLEAR
            2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55 -> DRIZZLE
            56, 57, 66, 67 -> FREEZING_RAIN
            61, 63 -> RAIN
            65 -> HEAVY_RAIN
            71, 73, 75, 77 -> SNOW
            80, 81, 82 -> SHOWERS
            85, 86 -> SNOW_SHOWERS
            95, 96, 99 -> THUNDERSTORM
            else -> UNKNOWN
        }

        /**
         * OpenWeatherMap's condition codes, which Breezy Weather's Gadgetbridge data uses
         * (`GadgetbridgeService.getWeatherCode`: 800-804 by cloud cover, 500 rain, 600 snow, 611
         * hail, 616 sleet, 210 and 211 thunder, 741 fog, 721 haze, 771 wind, 3200 unknown).
         */
        fun fromOwm(code: Int?): WeatherCondition = when (code) {
            null -> UNKNOWN
            in 200..299 -> THUNDERSTORM
            in 300..399 -> DRIZZLE
            in 502..504 -> HEAVY_RAIN
            511 -> FREEZING_RAIN
            in 500..501 -> RAIN
            in 520..531 -> SHOWERS
            in 611..616 -> SLEET
            in 620..622 -> SNOW_SHOWERS
            in 600..602 -> SNOW
            771, 781 -> WINDY
            in 700..799 -> FOG
            800 -> CLEAR
            801 -> MOSTLY_CLEAR
            802 -> PARTLY_CLOUDY
            803, 804 -> CLOUDY
            else -> UNKNOWN
        }
    }
}

/** Current conditions. Temperatures in Celsius, wind in km/h. */
data class WeatherNow(
    val temperature: Double,
    val condition: WeatherCondition,
    val wind: Double? = null,
    val isDay: Boolean = true,
)

/** One day's forecast. */
data class WeatherDay(
    val date: LocalDate,
    val high: Double?,
    val low: Double?,
    val condition: WeatherCondition,
    /** The highest chance of rain or snow that day, in percent. */
    val precipitationChance: Int? = null,
    /** Snowfall in cm, when the source says (Open-Meteo). */
    val snowfall: Double? = null,
    val wind: Double? = null,
)

/** One hour's forecast, at the place's local time. */
data class WeatherHour(
    val time: LocalDateTime,
    val temperature: Double?,
    val condition: WeatherCondition,
    val precipitationChance: Int? = null,
)

/** Where a forecast came from: shown on the card (Open-Meteo asks for attribution). */
enum class WeatherSource { OPEN_METEO, BREEZY_WEATHER }

/**
 * A forecast for one place: current conditions, days from today on and the next hours.
 *
 * @param now the time at the place when the forecast was read (for "today" and the next hours)
 * @param updated when the source computed it, if known
 */
data class WeatherForecast(
    val placeName: String,
    val now: LocalDateTime,
    val current: WeatherNow?,
    val days: List<WeatherDay>,
    val hours: List<WeatherHour>,
    val source: WeatherSource,
    val updated: Instant? = null,
) {
    val today: LocalDate get() = now.toLocalDate()

    fun day(date: LocalDate): WeatherDay? = days.firstOrNull { it.date == date }

    /** The hours from the current one on. */
    val nextHours: List<WeatherHour>
        get() = hours.filter { !it.time.isBefore(now.withMinute(0).withSecond(0).withNano(0)) }
}
