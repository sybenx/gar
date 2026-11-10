package org.stypox.dicio.skills.weather

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** How a day is said: "Today", "Tomorrow", "Friday", "November 9". */
sealed interface DayLabel {
    data object Today : DayLabel
    data object Tonight : DayLabel
    data object Tomorrow : DayLabel
    data object TomorrowNight : DayLabel
    data class Weekday(val day: DayOfWeek, val night: Boolean = false) : DayLabel
    data class Date(val date: LocalDate) : DayLabel
    data object Weekend : DayLabel
    data object Week : DayLabel

    /** The seven days from next Monday ("next week"). */
    data object NextWeek : DayLabel

    companion object {
        fun of(date: LocalDate, today: LocalDate, night: Boolean = false): DayLabel {
            val days = ChronoUnit.DAYS.between(today, date)
            return when {
                days == 0L -> if (night) Tonight else Today
                days == 1L -> if (night) TomorrowNight else Tomorrow
                days in 2..6 -> Weekday(date.dayOfWeek, night)
                else -> Date(date)
            }
        }
    }
}

/** Yes, maybe or no, for "will it rain", "will it snow" and "do I need an umbrella". */
enum class WeatherChance { YES, MAYBE, NO }

/**
 * What GAR says about a forecast (gar:weather): a short spoken answer in Google's style
 * ("Tomorrow in Logan: 54 and sunny, low of 31."), which [WeatherOutput.Answer] words with GAR's
 * strings; the card adds a few days or hours. Temperatures are in Celsius here.
 */
sealed interface WeatherReply {
    /** "Right now in Logan: 54 and sunny, high of 60." */
    data class Current(val now: WeatherNow, val today: WeatherDay?) : WeatherReply

    /** "Tomorrow in Logan: 54 and sunny, low of 31." */
    data class Day(val day: WeatherDay, val label: DayLabel) : WeatherReply

    /** "Tonight in Logan: clear, low of 31." */
    data class Night(val day: WeatherDay, val label: DayLabel) : WeatherReply

    /** "This weekend in Logan: Saturday 54 and sunny, Sunday 48 with rain." */
    data class Days(val days: List<WeatherDay>, val label: DayLabel) : WeatherReply

    /** "It's 54 in Logan right now." */
    data class Temperature(val now: WeatherNow) : WeatherReply

    /** "It'll get up to 60 today in Logan." */
    data class High(val day: WeatherDay, val label: DayLabel) : WeatherReply

    /** "It'll get down to 31 tonight in Logan." */
    data class Low(val day: WeatherDay, val label: DayLabel) : WeatherReply

    /** "Yes, rain is likely tomorrow in Logan: 70 percent chance." */
    data class Chance(
        val day: WeatherDay,
        val label: DayLabel,
        val precipitation: Precipitation,
        val answer: WeatherChance,
    ) : WeatherReply

    /** "Yes, it's raining in Logan right now.", "No, it isn't snowing in Logan right now." */
    data class FallingNow(val precipitation: Precipitation, val falling: Boolean) : WeatherReply

    /** "Yes, take an umbrella: 70 percent chance of rain today in Logan." */
    data class Umbrella(val day: WeatherDay, val label: DayLabel, val answer: WeatherChance) : WeatherReply

    /** "Wind in Logan: 12 miles per hour right now." */
    data class Wind(val now: WeatherNow) : WeatherReply

    /**
     * When the rain or snow stops: around [at], or not in the next hours ([at] null and
     * [falling]), or it isn't falling now ([falling] false).
     */
    data class Stops(val precipitation: Precipitation, val falling: Boolean, val at: LocalDateTime?) : WeatherReply

    /** When it starts: around [at], it already is ([falling]), or not in the next hours. */
    data class Starts(val precipitation: Precipitation, val falling: Boolean, val at: LocalDateTime?) : WeatherReply

    /** The day asked is past or beyond the forecast: "I only have the forecast for the next 7 days." */
    data class NoForecast(val days: Int) : WeatherReply
}

/** Picks the [WeatherReply] for a request and a forecast. */
object WeatherAnswer {
    /** At least this chance (percent) is a yes, at least [MAYBE_CHANCE] a maybe. */
    const val YES_CHANCE = 50
    const val MAYBE_CHANCE = 25

    fun reply(request: WeatherRequest, forecast: WeatherForecast): WeatherReply {
        val today = forecast.today
        val noForecast = WeatherReply.NoForecast(forecast.days.count { !it.date.isBefore(today) })
        val time = request.time

        // the day asked, or today for "now" and the next hours
        val date = when (time) {
            is WeatherWhen.Day -> time.date
            is WeatherWhen.Night -> time.date
            is WeatherWhen.Weekend -> time.saturday
            is WeatherWhen.Week -> time.from
            WeatherWhen.Now, WeatherWhen.Hours -> today
        }
        val night = time is WeatherWhen.Night
        val label = DayLabel.of(date, today, night)
        val day = forecast.day(date)

        return when (request.topic) {
            WeatherTopic.STOPS -> stops(request.precipitation, forecast)
            WeatherTopic.STARTS -> starts(request.precipitation, forecast)
            WeatherTopic.TEMPERATURE -> when {
                time == WeatherWhen.Now || time == WeatherWhen.Hours ->
                    forecast.current?.let { WeatherReply.Temperature(it) }
                night -> day?.let { WeatherReply.Low(it, label) }
                else -> day?.let { WeatherReply.High(it, label) }
            } ?: noForecast
            WeatherTopic.HIGH -> day?.let { WeatherReply.High(it, label) } ?: noForecast
            WeatherTopic.LOW -> day?.let { WeatherReply.Low(it, label) } ?: noForecast
            WeatherTopic.RAIN, WeatherTopic.SNOW -> {
                val precipitation =
                    if (request.topic == WeatherTopic.SNOW) Precipitation.SNOW else Precipitation.RAIN
                if (time == WeatherWhen.Now) {
                    return WeatherReply.FallingNow(precipitation, fallingNow(precipitation, forecast))
                }
                days(time, forecast)?.let { days ->
                    // "will it rain this weekend": the wettest day
                    val wettest = days.maxWithOrNull(compareBy(
                        { WeatherChance.entries.size - chanceOf(precipitation, it).ordinal },
                        { it.precipitationChance ?: 0 },
                    )) ?: return@let null
                    val dayLabel = if (days.size > 1) labelOf(time, today) else label
                    WeatherReply.Chance(wettest, dayLabel, precipitation, chanceOf(precipitation, wettest))
                } ?: noForecast
            }
            WeatherTopic.UMBRELLA -> day?.let {
                WeatherReply.Umbrella(it, label, chanceOf(Precipitation.RAIN, it))
            } ?: noForecast
            WeatherTopic.WIND -> if (time == WeatherWhen.Now) {
                forecast.current?.let { WeatherReply.Wind(it) } ?: noForecast
            } else {
                day?.let { WeatherReply.Day(it, label) } ?: noForecast
            }
            WeatherTopic.GENERAL -> when (time) {
                WeatherWhen.Now, WeatherWhen.Hours -> forecast.current
                    ?.let { WeatherReply.Current(it, forecast.day(today)) }
                    ?: forecast.day(today)?.let { WeatherReply.Day(it, DayLabel.Today) }
                    ?: noForecast
                is WeatherWhen.Day -> if (time.date == today && forecast.current != null) {
                    WeatherReply.Current(forecast.current, day)
                } else {
                    day?.let { WeatherReply.Day(it, label) } ?: noForecast
                }
                is WeatherWhen.Night -> day?.let { WeatherReply.Night(it, label) } ?: noForecast
                is WeatherWhen.Weekend, is WeatherWhen.Week ->
                    days(time, forecast)?.let { WeatherReply.Days(it, labelOf(time, today)) } ?: noForecast
            }
        }
    }

    private fun labelOf(time: WeatherWhen, today: LocalDate): DayLabel = when (time) {
        is WeatherWhen.Weekend -> DayLabel.Weekend
        // "next week" starts on a later Monday; "this week" on the phone's today
        is WeatherWhen.Week ->
            if (time.from.isAfter(today) && time.from.dayOfWeek == DayOfWeek.MONDAY) DayLabel.NextWeek
            else DayLabel.Week
        is WeatherWhen.Day -> DayLabel.of(time.date, today)
        is WeatherWhen.Night -> DayLabel.of(time.date, today, night = true)
        WeatherWhen.Now, WeatherWhen.Hours -> DayLabel.Today
    }

    /** The days a request covers, or null when one of them isn't in the forecast. */
    private fun days(time: WeatherWhen, forecast: WeatherForecast): List<WeatherDay>? {
        val dates = when (time) {
            is WeatherWhen.Weekend -> listOf(time.saturday, time.saturday.plusDays(1))
                .filter { !it.isBefore(forecast.today) }
            is WeatherWhen.Week -> (0L until 7L).map { time.from.plusDays(it) }
                .filter { forecast.day(it) != null }
                .ifEmpty { return null }
            is WeatherWhen.Day -> listOf(time.date)
            is WeatherWhen.Night -> listOf(time.date)
            WeatherWhen.Now, WeatherWhen.Hours -> listOf(forecast.today)
        }
        return dates.map { forecast.day(it) ?: return null }
    }

    /** Whether rain or snow is likely on [day]. */
    fun chanceOf(precipitation: Precipitation, day: WeatherDay): WeatherChance {
        val chance = day.precipitationChance
        val kindMatches = when (precipitation) {
            Precipitation.RAIN -> !day.condition.isSnow || day.condition == WeatherCondition.SLEET
            // snowfall said (Open-Meteo), or a snowy sky (Breezy only gives the condition)
            Precipitation.SNOW -> day.condition.isSnow || (day.snowfall ?: 0.0) >= 0.1
        }
        if (!kindMatches) {
            return WeatherChance.NO
        }
        val wetSky = when (precipitation) {
            Precipitation.RAIN -> day.condition.isRain
            Precipitation.SNOW -> day.condition.isSnow
        }
        return when {
            chance == null -> if (wetSky) WeatherChance.YES else WeatherChance.NO
            chance >= YES_CHANCE -> WeatherChance.YES
            chance >= MAYBE_CHANCE -> WeatherChance.MAYBE
            wetSky && precipitation == Precipitation.SNOW -> WeatherChance.MAYBE
            else -> WeatherChance.NO
        }
    }

    /** Whether rain or snow is likely in [hour]: a wet sky with some chance, or a high chance. */
    fun isWet(precipitation: Precipitation, hour: WeatherHour): Boolean {
        val sky = when (precipitation) {
            Precipitation.RAIN -> hour.condition.isRain
            Precipitation.SNOW -> hour.condition.isSnow
        }
        val chance = hour.precipitationChance ?: return sky
        return sky && chance >= MAYBE_CHANCE || chance >= YES_CHANCE &&
            (precipitation == Precipitation.RAIN || sky)
    }

    /** Whether rain or snow falls now: the current sky, or the current hour's forecast. */
    private fun fallingNow(precipitation: Precipitation, forecast: WeatherForecast): Boolean =
        forecast.current?.condition?.let {
            if (precipitation == Precipitation.SNOW) it.isSnow else it.isRain
        } == true || forecast.nextHours.firstOrNull()?.let { isWet(precipitation, it) } == true

    private fun stops(precipitation: Precipitation, forecast: WeatherForecast): WeatherReply {
        val hours = forecast.nextHours
        if (!fallingNow(precipitation, forecast)) {
            return WeatherReply.Stops(precipitation, falling = false, at = null)
        }
        // the first hour where it is no longer likely, as for when it starts
        val dry = hours.drop(1).firstOrNull { !isWet(precipitation, it) }
        return WeatherReply.Stops(precipitation, falling = true, at = dry?.time)
    }

    private fun starts(precipitation: Precipitation, forecast: WeatherForecast): WeatherReply {
        val hours = forecast.nextHours
        if (fallingNow(precipitation, forecast)) {
            return WeatherReply.Starts(precipitation, falling = true, at = null)
        }
        val wet = hours.firstOrNull { isWet(precipitation, it) }
        return WeatherReply.Starts(precipitation, falling = false, at = wet?.time)
    }
}
