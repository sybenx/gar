package org.stypox.dicio.skills.date

import androidx.annotation.StringRes
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How a day relates to today, which picks the answer's wording. */
enum class DateRelation(@StringRes val answer: Int) {
    TODAY(R.string.skill_date_today),
    TOMORROW(R.string.skill_date_tomorrow),
    YESTERDAY(R.string.skill_date_yesterday),
    FUTURE(R.string.skill_date_future),
    PAST(R.string.skill_date_past);

    companion object {
        fun of(date: LocalDate, today: LocalDate): DateRelation =
            when (val days = ChronoUnit.DAYS.between(today, date)) {
                0L -> TODAY
                1L -> TOMORROW
                -1L -> YESTERDAY
                else -> if (days > 0) FUTURE else PAST
            }
    }
}

/** The weekday and date in the locale's long form, e.g. "Sunday, October 25, 2026". */
fun formatFullDate(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)

/** "Today is Sunday, October 25, 2026.", "Tomorrow is ...", "That's ...". */
class DateOutput(
    val date: LocalDate,
    private val today: LocalDate,
) : HeadlineSpeechSkillOutput {
    val relation: DateRelation get() = DateRelation.of(date, today)

    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(relation.answer, formatFullDate(date, ctx.locale))
}
