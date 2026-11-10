package org.stypox.dicio.skills.holiday

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.skills.date.formatFullDate
import org.stypox.dicio.util.getString
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

/**
 * "Christmas is in 59 days.", "Thanksgiving is on Thursday, November 26, 2026.", "That's in 3
 * days, on Friday, October 30, 2026.", "I don't know your birthday yet."
 */
class HolidayOutput(
    val request: HolidayRequest,
    private val today: LocalDate,
) : SkillOutput {
    /** Days from today to the day asked about (negative: it has passed), or null for none. */
    val days: Long? get() = when (request) {
        is HolidayRequest.Countdown -> ChronoUnit.DAYS.between(today, request.date)
        is HolidayRequest.When -> ChronoUnit.DAYS.between(today, request.date)
        is HolidayRequest.Unknown -> null
    }

    override fun getSpeechOutput(ctx: SkillContext): String {
        val days = days
        return when (request) {
            is HolidayRequest.Unknown -> ctx.getString(
                when {
                    !request.mine -> R.string.skill_holiday_unknown
                    request.anniversary -> R.string.skill_holiday_anniversary_unknown
                    else -> R.string.skill_holiday_birthday_unknown
                }
            )
            is HolidayRequest.When -> {
                val date = formatFullDate(request.date, ctx.locale)
                when {
                    days == 0L -> ctx.getString(R.string.skill_holiday_today, request.name)
                    days!! < 0 -> ctx.getString(R.string.skill_holiday_was_on, request.name, date)
                    else -> ctx.getString(R.string.skill_holiday_is_on, request.name, date)
                }
            }
            is HolidayRequest.Countdown -> countdown(ctx, request, days!!)
        }
    }

    private fun countdown(ctx: SkillContext, request: HolidayRequest.Countdown, days: Long): String {
        val resources = ctx.android.resources
        val count = days.absoluteValue.toInt()
        val name = request.name
        if (name == null) {
            val date = formatFullDate(request.date, ctx.locale)
            return when {
                days == 0L -> ctx.getString(R.string.skill_holiday_date_today)
                days == 1L -> ctx.getString(R.string.skill_holiday_date_tomorrow, date)
                days > 0 -> resources.getQuantityString(
                    R.plurals.skill_holiday_date_in_days, count, count, date,
                )
                else -> resources.getQuantityString(
                    R.plurals.skill_holiday_date_days_ago, count, count, date,
                )
            }
        }
        return when {
            days == 0L -> ctx.getString(R.string.skill_holiday_today, name)
            days == 1L -> ctx.getString(R.string.skill_holiday_tomorrow, name)
            days > 0 ->
                resources.getQuantityString(R.plurals.skill_holiday_in_days, count, name, count)
            else ->
                resources.getQuantityString(R.plurals.skill_holiday_days_ago, count, name, count)
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            val date = when (request) {
                is HolidayRequest.Countdown -> request.date.takeIf { request.name != null }
                is HolidayRequest.When -> null
                is HolidayRequest.Unknown -> null
            }
            if (date != null) {
                Subtitle(text = formatFullDate(date, ctx.locale))
            }
        }
    }
}
