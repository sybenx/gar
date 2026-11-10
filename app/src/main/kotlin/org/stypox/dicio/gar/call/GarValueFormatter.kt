package org.stypox.dicio.gar.call

import cc.aaro.gar.router.resolve.ResolvedDate
import cc.aaro.gar.router.resolve.ResolvedDateTime
import cc.aaro.gar.router.resolve.ResolvedNumber
import cc.aaro.gar.router.resolve.ResolvedTime
import org.dicio.numbers.ParserFormatter
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.util.getString
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Period
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.dicio.numbers.unit.Duration as DicioDuration

/**
 * Formats resolved slot values (see [GarSlotResolver]) for confirmation cards, questions and
 * speech. Times always show am/pm, so 06:30 and 18:30 never read the same. Relative day words
 * are computed here because dicio-numbers' niceDate can say "today" for a date a month away.
 */
class GarValueFormatter(
    private val parserFormatter: ParserFormatter?,
    private val locale: Locale,
    private val words: Words,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    /** Localized words; [dateAtTime], [every] and [percent] are format strings. */
    data class Words(
        val today: String,
        val tomorrow: String,
        /** `%1$s` = date, `%2$s` = time */
        val dateAtTime: String,
        /** `%1$s` = list of weekdays */
        val every: String,
        /** `%1$s` = number */
        val percent: String,
    )

    /** @return a readable form of [value], or null for a null value */
    fun format(value: Any?, speech: Boolean): String? {
        return when (value) {
            null -> null
            is String -> value
            is java.time.Duration -> duration(value)
            is DicioDuration -> duration(value.toJavaDuration())
            is ResolvedNumber -> number(value, speech)
            is ResolvedTime -> time(value.time, speech) + repeat(value.repeatDays)
            is ResolvedDate -> date(value.date)
            is ResolvedDateTime -> dateTime(value.dateTime, value.dateSpoken, value.timeSpoken, speech)
            is LocalTime -> time(value, speech)
            is LocalDate -> date(value)
            is LocalDateTime -> dateTime(value, dateSpoken = true, timeSpoken = true, speech)
            else -> value.toString()
        }
    }

    private fun duration(duration: java.time.Duration): String {
        // the spoken form ("ten minutes") is also the clearest written form, "10:00" could be
        // mistaken for a time
        return parserFormatter?.niceDuration(DicioDuration(duration))?.speech(true)?.get()
            ?: duration.toString()
    }

    private fun number(number: ResolvedNumber, speech: Boolean): String {
        val formatted = if (number.isInteger) {
            parserFormatter?.niceNumber(number.value)?.speech(speech)?.get()
                ?: number.value.toLong().toString()
        } else {
            parserFormatter?.niceNumber(number.value)?.speech(speech)?.get()
                ?: number.value.toString()
        }
        return if (!number.percent) {
            formatted
        } else if (speech) {
            String.format(locale, words.percent, formatted)
        } else {
            "$formatted%"
        }
    }

    private fun time(time: LocalTime, speech: Boolean): String {
        if (speech) {
            clockTime(time)?.let { return it }
        }
        return parserFormatter?.niceTime(time)
            ?.speech(speech)
            ?.use24Hour(false)
            ?.showAmPm(true)
            ?.get()
            ?: time.toString()
    }

    /**
     * English times as the card shows them, "11:45 AM" said "eleven forty five a.m.": Dicio says
     * "quarter to twelve a.m." for 11:45 AM, with the a.m. of the hour before, and "half past" or
     * "quarter past" for :30 and :15, which a confirmation is hard to check against. Null for
     * other times and languages, which keep Dicio's words.
     */
    private fun clockTime(time: LocalTime): String? {
        val formatter = parserFormatter ?: return null
        if (locale.language != "en" || time.minute !in CLOCK_STYLE_MINUTES) {
            return null
        }
        val hour = (time.hour + 11) % 12 + 1
        val hourWords = formatter.pronounceNumber(hour.toDouble()).get()
        val minuteWords = formatter.pronounceNumber(time.minute.toDouble()).get()
        return "$hourWords $minuteWords " + if (time.hour >= 12) "p.m." else "a.m."
    }

    private fun date(date: LocalDate): String {
        val now = today()
        return when (ChronoUnit.DAYS.between(now, date)) {
            0L -> words.today
            1L -> words.tomorrow
            else -> {
                // niceDate only looks at the days part of a Period: avoid passing `now` when that
                // would make it say yesterday/today/tomorrow for a date months away
                val safeNow = now.takeIf { Period.between(date, now).days !in -1..1 }
                parserFormatter?.niceDate(date)?.now(safeNow)?.get() ?: date.toString()
            }
        }
    }

    private fun dateTime(
        dateTime: LocalDateTime,
        dateSpoken: Boolean,
        timeSpoken: Boolean,
        speech: Boolean,
    ): String {
        val showDate = dateSpoken || dateTime.toLocalDate() != today()
        return when {
            !timeSpoken -> date(dateTime.toLocalDate())
            !showDate -> time(dateTime.toLocalTime(), speech)
            else -> String.format(
                locale,
                words.dateAtTime,
                date(dateTime.toLocalDate()),
                time(dateTime.toLocalTime(), speech),
            )
        }
    }

    private fun repeat(days: Set<DayOfWeek>): String {
        if (days.isEmpty()) {
            return ""
        }
        val names = days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.FULL, locale) }
        return " " + String.format(locale, words.every, names)
    }

    companion object {
        /** The minutes Dicio says as "quarter past", "half past" and "quarter to". */
        private val CLOCK_STYLE_MINUTES = setOf(15, 30, 45)

        fun forContext(ctx: SkillContext): GarValueFormatter = GarValueFormatter(
            parserFormatter = ctx.parserFormatter,
            locale = ctx.locale,
            words = Words(
                today = ctx.getString(R.string.gar_today),
                tomorrow = ctx.getString(R.string.gar_tomorrow),
                dateAtTime = ctx.getString(R.string.gar_date_at_time),
                every = ctx.getString(R.string.gar_every),
                percent = ctx.getString(R.string.gar_percent),
            ),
        )
    }
}
