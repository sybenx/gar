package org.stypox.dicio.skills.weather

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.ui.GarChip
import org.stypox.dicio.gar.ui.GarChipStrings
import org.stypox.dicio.gar.ui.GarChipTable
import org.stypox.dicio.gar.ui.GarSuggestions
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.util.getString
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlin.math.roundToInt

/** The user's units: Dicio's weather settings (system default: Fahrenheit for a US locale). */
data class WeatherUnits(val temperature: ResolvedTemperatureUnit, val imperial: Boolean) {
    fun degrees(celsius: Double): Int = temperature.convert(celsius).roundToInt()

    /** km/h in mph or km/h. */
    fun speed(kmh: Double): Int = (if (imperial) kmh / KM_PER_MILE else kmh).roundToInt()

    companion object {
        private const val KM_PER_MILE = 1.609344

        fun from(prefs: SkillSettingsWeather) = WeatherUnits(
            ResolvedTemperatureUnit.from(prefs),
            ResolvedLengthUnit.from(prefs) == ResolvedLengthUnit.IMPERIAL,
        )
    }
}

/**
 * A forecast's answer (gar:weather): spoken short ("Tomorrow in Logan: 54 and sunny, low of
 * 31."), with a few days or the next hours on the card and where the data came from.
 */
class WeatherAnswerOutput(
    val result: WeatherResult.Answer,
    private val units: WeatherUnits,
) : SkillOutput, GarSuggestions {
    private val forecast get() = result.forecast

    /** The day the reply is about, null for now, the next hours, or no forecast. */
    val replyLabel: DayLabel?
        get() = when (val reply = result.reply) {
            is WeatherReply.Day -> reply.label
            is WeatherReply.Night -> reply.label
            is WeatherReply.Days -> reply.label
            is WeatherReply.High -> reply.label
            is WeatherReply.Low -> reply.label
            is WeatherReply.Chance -> reply.label
            is WeatherReply.Umbrella -> reply.label
            else -> null
        }

    /** Tomorrow's and the weekend's forecast, each unless this answer was about it already. */
    override fun suggestions(ctx: SkillContext): List<GarChip> {
        if (result.reply is WeatherReply.NoForecast) {
            return listOf()
        }
        val label = replyLabel
        return GarChipTable.weather(
            GarChipStrings.of(ctx),
            tomorrow = label != DayLabel.Tomorrow && label != DayLabel.TomorrowNight,
            weekend = label != DayLabel.Weekend,
        )
    }

    override fun getSpeechOutput(ctx: SkillContext): String = speech(ctx)

    private fun where(ctx: SkillContext): String {
        val name = forecast.placeName.substringBefore(',').trim()
        return if (result.nearYou || name.isEmpty()) {
            ctx.getString(R.string.gar_weather_near_you)
        } else {
            ctx.getString(R.string.gar_weather_in_place, name)
        }
    }

    private fun deg(celsius: Double?): String = celsius?.let { units.degrees(it).toString() } ?: "?"

    private fun tempAndSky(ctx: SkillContext, celsius: Double?, condition: WeatherCondition, isDay: Boolean = true): String {
        val sky = sky(ctx, condition, isDay) ?: return deg(celsius)
        return ctx.getString(R.string.gar_weather_temp_and_sky, deg(celsius), sky)
    }

    private fun speech(ctx: SkillContext): String {
        val where = where(ctx)
        return when (val reply = result.reply) {
            is WeatherReply.Current -> {
                val now = tempAndSky(ctx, reply.now.temperature, reply.now.condition, reply.now.isDay)
                val high = reply.today?.high
                if (high != null && high > reply.now.temperature) {
                    ctx.getString(R.string.gar_weather_now_high, where, now, deg(high))
                } else {
                    ctx.getString(R.string.gar_weather_now, where, now)
                }
            }
            is WeatherReply.Day -> {
                val day = tempAndSky(ctx, reply.day.high, reply.day.condition)
                if (reply.day.low != null) {
                    ctx.getString(R.string.gar_weather_day, label(ctx, reply.label), where, day, deg(reply.day.low))
                } else {
                    ctx.getString(R.string.gar_weather_day_no_low, label(ctx, reply.label), where, day)
                }
            }
            is WeatherReply.Night -> {
                val sky = sky(ctx, reply.day.condition, isDay = false)
                if (sky != null) {
                    ctx.getString(R.string.gar_weather_night, label(ctx, reply.label), where, sky, deg(reply.day.low))
                } else {
                    ctx.getString(R.string.gar_weather_night_no_sky, label(ctx, reply.label), where, deg(reply.day.low))
                }
            }
            is WeatherReply.Days -> if (reply.label == DayLabel.Week || reply.label == DayLabel.NextWeek) {
                week(ctx, reply.days, where, reply.label)
            } else {
                val items = reply.days.joinToString(", ") {
                    ctx.getString(
                        R.string.gar_weather_days_item, weekday(ctx, it.date),
                        tempAndSky(ctx, it.high, it.condition),
                    )
                }
                ctx.getString(R.string.gar_weather_days, label(ctx, reply.label), where, items)
            }
            is WeatherReply.Temperature ->
                ctx.getString(R.string.gar_weather_temperature, deg(reply.now.temperature), where)
            is WeatherReply.High ->
                ctx.getString(R.string.gar_weather_high, where, inline(ctx, reply.label), deg(reply.day.high))
            is WeatherReply.Low ->
                ctx.getString(R.string.gar_weather_low, where, inline(ctx, reply.label), deg(reply.day.low))
            is WeatherReply.Chance -> {
                val noun = noun(ctx, reply.precipitation)
                val chance = reply.day.precipitationChance
                val day = inline(ctx, reply.label)
                when {
                    reply.answer == WeatherChance.YES && chance != null ->
                        ctx.getString(R.string.gar_weather_yes_chance, noun, where, day, chance)
                    reply.answer == WeatherChance.YES ->
                        ctx.getString(R.string.gar_weather_yes, noun, where, day)
                    reply.answer == WeatherChance.MAYBE && chance != null ->
                        ctx.getString(R.string.gar_weather_maybe, noun, where, day, chance)
                    else -> ctx.getString(R.string.gar_weather_no, noun, where, day)
                }
            }
            is WeatherReply.Umbrella -> {
                val chance = reply.day.precipitationChance
                val day = inline(ctx, reply.label)
                when {
                    reply.answer == WeatherChance.YES && chance != null ->
                        ctx.getString(R.string.gar_weather_umbrella_yes_chance, where, day, chance)
                    reply.answer == WeatherChance.YES ->
                        ctx.getString(R.string.gar_weather_umbrella_yes, where, day)
                    reply.answer == WeatherChance.MAYBE && chance != null ->
                        ctx.getString(R.string.gar_weather_umbrella_maybe, where, day, chance)
                    else -> ctx.getString(R.string.gar_weather_umbrella_no, where, day)
                }
            }
            is WeatherReply.Wind -> ctx.getString(
                R.string.gar_weather_wind, where, ctx.getString(
                    if (units.imperial) R.string.gar_weather_speed_mph else R.string.gar_weather_speed_kmh,
                    units.speed(reply.now.wind ?: 0.0),
                ),
            )
            is WeatherReply.FallingNow -> ctx.getString(
                when (reply.precipitation) {
                    Precipitation.RAIN ->
                        if (reply.falling) R.string.gar_weather_raining else R.string.gar_weather_not_raining
                    Precipitation.SNOW ->
                        if (reply.falling) R.string.gar_weather_snowing else R.string.gar_weather_not_snowing
                },
                where,
            )
            is WeatherReply.Stops -> {
                val snow = reply.precipitation == Precipitation.SNOW
                val at = reply.at
                when {
                    !reply.falling -> ctx.getString(
                        if (snow) R.string.gar_weather_stops_dry_snow else R.string.gar_weather_stops_dry_rain,
                        where,
                    )
                    at != null -> ctx.getString(
                        if (snow) R.string.gar_weather_stops_at_snow else R.string.gar_weather_stops_at_rain,
                        where, time(ctx, at, forecast.today),
                    )
                    else -> ctx.getString(
                        if (snow) R.string.gar_weather_stops_later_snow else R.string.gar_weather_stops_later_rain,
                        where,
                    )
                }
            }
            is WeatherReply.Starts -> {
                val snow = reply.precipitation == Precipitation.SNOW
                val at = reply.at
                when {
                    reply.falling -> ctx.getString(
                        if (snow) R.string.gar_weather_snowing else R.string.gar_weather_raining, where,
                    )
                    at != null -> ctx.getString(
                        if (snow) R.string.gar_weather_starts_at_snow else R.string.gar_weather_starts_at_rain,
                        where, time(ctx, at, forecast.today),
                    )
                    else -> ctx.getString(
                        if (snow) R.string.gar_weather_starts_none_snow else R.string.gar_weather_starts_none_rain,
                        where,
                    )
                }
            }
            is WeatherReply.NoForecast -> ctx.getString(R.string.gar_weather_no_forecast, reply.days)
        }
    }

    private fun week(ctx: SkillContext, days: List<WeatherDay>, where: String, weekLabel: DayLabel): String {
        val highs = days.mapNotNull { it.high }
        val low = deg(highs.minOrNull())
        val high = deg(highs.maxOrNull())
        val wet = days.filter {
            WeatherAnswer.chanceOf(Precipitation.RAIN, it) == WeatherChance.YES ||
                WeatherAnswer.chanceOf(Precipitation.SNOW, it) == WeatherChance.YES
        }.map { weekday(ctx, it.date) }
        val label = label(ctx, weekLabel)
        return if (wet.isEmpty()) {
            ctx.getString(R.string.gar_weather_week, label, where, low, high)
        } else {
            val list = if (wet.size == 1) {
                wet[0]
            } else {
                ctx.getString(R.string.gar_weather_and, wet.dropLast(1).joinToString(", "), wet.last())
            }
            ctx.getString(
                R.string.gar_weather_week_wet, label, where, low, high,
                ctx.getString(R.string.gar_weather_inline_on, list),
            )
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = speech(ctx))
            Spacer(modifier = Modifier.height(8.dp))
            for (row in cardRows(ctx)) {
                Body(text = row)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Subtitle(text = source(ctx))
        }
    }

    /** A few days from the one asked, or the next hours for "now" and rain stopping or starting. */
    private fun cardRows(ctx: SkillContext): List<String> {
        val hourly = when (result.reply) {
            is WeatherReply.Current, is WeatherReply.Temperature, is WeatherReply.Wind,
            is WeatherReply.FallingNow, is WeatherReply.Stops, is WeatherReply.Starts -> true
            else -> false
        }
        if (hourly && forecast.nextHours.isNotEmpty()) {
            return forecast.nextHours.take(CARD_HOURS).map { hour ->
                val row = ctx.getString(
                    R.string.gar_weather_card_hour, time(ctx, hour.time, forecast.today),
                    cardDegrees(ctx, hour.temperature), capitalized(sky(ctx, hour.condition, isDay = true)),
                )
                withChance(ctx, row, hour.precipitationChance)
            }
        }
        val first = when (val reply = result.reply) {
            is WeatherReply.Day -> reply.day.date
            is WeatherReply.Night -> reply.day.date
            is WeatherReply.Days -> reply.days.firstOrNull()?.date
            is WeatherReply.High -> reply.day.date
            is WeatherReply.Low -> reply.day.date
            is WeatherReply.Chance -> reply.day.date
            is WeatherReply.Umbrella -> reply.day.date
            else -> null
        } ?: forecast.today
        val count = maxOf(CARD_DAYS, (result.reply as? WeatherReply.Days)?.days?.size ?: 0)
        return forecast.days.filter { !it.date.isBefore(first) }
            .take(count)
            .map { day ->
                val row = ctx.getString(
                    R.string.gar_weather_card_day, shortDay(ctx, day.date), cardDegrees(ctx, day.high),
                    cardDegrees(ctx, day.low), capitalized(sky(ctx, day.condition, isDay = true)),
                )
                withChance(ctx, row, day.precipitationChance)
            }
    }

    private fun withChance(ctx: SkillContext, row: String, chance: Int?): String =
        if (chance != null && chance >= WeatherAnswer.MAYBE_CHANCE) {
            ctx.getString(R.string.gar_weather_card_chance, row, chance)
        } else {
            row
        }

    private fun cardDegrees(ctx: SkillContext, celsius: Double?): String =
        celsius?.let { ctx.getString(R.string.gar_weather_card_degrees, units.degrees(it)) } ?: "-"

    private fun source(ctx: SkillContext): String = when (forecast.source) {
        WeatherSource.OPEN_METEO -> ctx.getString(R.string.gar_weather_card_open_meteo)
        WeatherSource.BREEZY_WEATHER -> ctx.getString(
            R.string.gar_weather_card_breezy,
            forecast.updated?.let {
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(ctx.locale)
                    .format(it.atZone(ZoneId.systemDefault()))
            } ?: "",
        )
    }

    private fun shortDay(ctx: SkillContext, date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, ctx.locale)

    private fun capitalized(text: String?): String =
        text?.replaceFirstChar { it.titlecase(java.util.Locale.ROOT) } ?: ""

    companion object {
        const val CARD_DAYS = 5
        const val CARD_HOURS = 8

        /** "Tomorrow", "Friday", "November 9": an answer's first words. */
        fun label(ctx: SkillContext, label: DayLabel): String = when (label) {
            DayLabel.Today -> ctx.getString(R.string.gar_weather_label_today)
            DayLabel.Tonight -> ctx.getString(R.string.gar_weather_label_tonight)
            DayLabel.Tomorrow -> ctx.getString(R.string.gar_weather_label_tomorrow)
            DayLabel.TomorrowNight -> ctx.getString(R.string.gar_weather_label_tomorrow_night)
            is DayLabel.Weekday -> {
                val day = label.day.getDisplayName(TextStyle.FULL, ctx.locale)
                if (label.night) ctx.getString(R.string.gar_weather_label_night, day) else day
            }
            is DayLabel.Date -> dateText(ctx, label.date)
            DayLabel.Weekend -> ctx.getString(R.string.gar_weather_label_weekend)
            DayLabel.Week -> ctx.getString(R.string.gar_weather_label_week)
            DayLabel.NextWeek -> ctx.getString(R.string.gar_weather_label_next_week)
        }

        /** "today", "on Friday", "this weekend": inside an answer. */
        fun inline(ctx: SkillContext, label: DayLabel): String = when (label) {
            DayLabel.Today -> ctx.getString(R.string.gar_weather_inline_today)
            DayLabel.Tonight -> ctx.getString(R.string.gar_weather_inline_tonight)
            DayLabel.Tomorrow -> ctx.getString(R.string.gar_weather_inline_tomorrow)
            DayLabel.TomorrowNight -> ctx.getString(R.string.gar_weather_inline_tomorrow_night)
            is DayLabel.Weekday -> {
                val day = label.day.getDisplayName(TextStyle.FULL, ctx.locale)
                ctx.getString(if (label.night) R.string.gar_weather_inline_night else R.string.gar_weather_inline_on, day)
            }
            is DayLabel.Date -> ctx.getString(R.string.gar_weather_inline_on, dateText(ctx, label.date))
            DayLabel.Weekend -> ctx.getString(R.string.gar_weather_inline_weekend)
            DayLabel.Week -> ctx.getString(R.string.gar_weather_inline_week)
            DayLabel.NextWeek -> ctx.getString(R.string.gar_weather_inline_next_week)
        }

        private fun dateText(ctx: SkillContext, date: LocalDate): String =
            DateTimeFormatter.ofPattern("MMMM d", ctx.locale).format(date)

        fun weekday(ctx: SkillContext, date: LocalDate): String =
            date.dayOfWeek.getDisplayName(TextStyle.FULL, ctx.locale)

        fun noun(ctx: SkillContext, precipitation: Precipitation): String = ctx.getString(
            if (precipitation == Precipitation.SNOW) R.string.gar_weather_noun_snow else R.string.gar_weather_noun_rain,
        )

        /** "3 PM", "15:00", "4 AM tomorrow". */
        fun time(ctx: SkillContext, time: LocalDateTime, today: LocalDate): String {
            val pattern = if (DateFormat.is24HourFormat(ctx.android)) "HH:mm" else "h a"
            val text = DateTimeFormatter.ofPattern(pattern, ctx.locale).format(time)
            return if (time.toLocalDate().isAfter(today)) {
                ctx.getString(R.string.gar_weather_time_tomorrow, text)
            } else {
                text
            }
        }

        /** "sunny", "partly cloudy", or null for no condition. */
        fun sky(ctx: SkillContext, condition: WeatherCondition, isDay: Boolean): String? = when (condition) {
            WeatherCondition.CLEAR ->
                if (isDay) R.string.gar_weather_sky_sunny else R.string.gar_weather_sky_clear
            WeatherCondition.MOSTLY_CLEAR ->
                if (isDay) R.string.gar_weather_sky_mostly_sunny else R.string.gar_weather_sky_mostly_clear
            WeatherCondition.PARTLY_CLOUDY -> R.string.gar_weather_sky_partly_cloudy
            WeatherCondition.CLOUDY -> R.string.gar_weather_sky_cloudy
            WeatherCondition.FOG -> R.string.gar_weather_sky_foggy
            WeatherCondition.DRIZZLE -> R.string.gar_weather_sky_drizzly
            WeatherCondition.RAIN, WeatherCondition.HEAVY_RAIN -> R.string.gar_weather_sky_rainy
            WeatherCondition.FREEZING_RAIN -> R.string.gar_weather_sky_icy
            WeatherCondition.SHOWERS -> R.string.gar_weather_sky_showery
            WeatherCondition.SNOW, WeatherCondition.SNOW_SHOWERS -> R.string.gar_weather_sky_snowy
            WeatherCondition.SLEET -> R.string.gar_weather_sky_sleety
            WeatherCondition.THUNDERSTORM -> R.string.gar_weather_sky_stormy
            WeatherCondition.WINDY -> R.string.gar_weather_sky_windy
            WeatherCondition.UNKNOWN -> null
        }?.let { ctx.getString(it) }
    }
}

/** "I couldn't find Moab.", "I couldn't reach Open-Meteo. ..." */
class WeatherMessageOutput(private val message: (SkillContext) -> String) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = message(ctx)
}

/**
 * "Which city are you in? You can also say: use my location." GAR asks once, when the user named
 * no place and neither a home city nor "use my location" is set. The answer is a city
 * ([WeatherCityAnswerSkill], remembered as the weather skill's default city) or "use my location"
 * ([WeatherLocationAnswerSkill], for which Dicio asks for the approximate location first); the
 * card has a button for the location. Either answers [request] right away.
 */
class WeatherPlaceQuestionOutput(
    val request: WeatherRequest,
    val reason: AskReason,
    private val lookup: WeatherLookup,
    private val units: WeatherUnits,
) : SkillOutput {

    /** Set once the user answered, to disable the button. */
    var answered by mutableStateOf(false)

    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(
        when (reason) {
            AskReason.FIRST -> R.string.gar_weather_ask_place
            AskReason.NO_PERMISSION -> R.string.gar_weather_ask_place_no_permission
            AskReason.NO_LOCATION -> R.string.gar_weather_ask_place_no_location
            AskReason.NOT_FOUND -> R.string.gar_weather_ask_place_not_found
        },
    )

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = listOf(
                WeatherLocationAnswerSkill(request, lookup, units, onAnswered = { answered = true }),
                WeatherCityAnswerSkill(request, lookup, units, onAnswered = { answered = true }),
                GarCancelSkill(WeatherInfo) { answered = true },
            ),
        )

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        val skillEvaluator = remember {
            EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
                .skillEvaluator()
        }
        // the button only makes sense while this card is the latest answer
        val log by skillEvaluator.state.collectAsState()
        val isLatest = log.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer === this
        val useLocation = ctx.getString(R.string.gar_weather_use_location)

        Column {
            Headline(text = getSpeechOutput(ctx))
            Spacer(modifier = Modifier.height(8.dp))
            Body(text = ctx.getString(R.string.gar_weather_ask_place_detail))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GarbleSecondaryButton( // GAR
                    onClick = {
                        answered = true
                        skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(useLocation, 1.0f))))
                    },
                    enabled = isLatest && !answered,
                ) {
                    Text(text = useLocation)
                }
            }
        }
    }
}

/** What [WeatherResult] the weather skill shows. */
fun weatherOutput(
    result: WeatherResult,
    request: WeatherRequest,
    lookup: WeatherLookup,
    units: WeatherUnits,
): SkillOutput = when (result) {
    is WeatherResult.Answer -> WeatherAnswerOutput(result, units)
    is WeatherResult.AskPlace -> WeatherPlaceQuestionOutput(request, result.reason, lookup, units)
    is WeatherResult.PlaceNotFound ->
        WeatherMessageOutput { it.getString(R.string.gar_weather_not_found, result.name) }
    WeatherResult.NoConnection ->
        WeatherMessageOutput { it.getString(R.string.gar_weather_no_connection) }
}
