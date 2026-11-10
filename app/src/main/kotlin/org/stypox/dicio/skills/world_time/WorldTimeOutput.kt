package org.stypox.dicio.skills.world_time

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.skills.date.formatFullDate
import org.stypox.dicio.util.getString
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

/**
 * The time in another place, or how far its clock is from the phone's (or another place's).
 * Spoken short ("It's 3:12 AM tomorrow in Tokyo.", "London is 7 hours ahead of you."); the card
 * has both clocks and the date there.
 *
 * @param now the current time in the phone's time zone
 */
class WorldTimeOutput(
    val request: WorldTimeRequest,
    private val now: ZonedDateTime,
) : SkillOutput {
    val place: Place get() = when (request) {
        is WorldTimeRequest.TimeIn -> request.place
        is WorldTimeRequest.Difference -> request.place
    }

    /** The time at [place] now. */
    val placeTime: ZonedDateTime get() = now.withZoneSameInstant(place.zone)

    /** -1, 0 or 1 when the date at [place] is yesterday, today or tomorrow here. */
    val dayOffset: Long get() = ChronoUnit.DAYS.between(now.toLocalDate(), placeTime.toLocalDate())

    /** The time at the place [placeTime] is compared with: the other place or the phone. */
    val fromTime: ZonedDateTime get() = when (request) {
        is WorldTimeRequest.Difference ->
            request.from?.let { now.withZoneSameInstant(it.zone) } ?: now
        is WorldTimeRequest.TimeIn -> now
    }

    /** How many minutes [place]'s clock is ahead of [fromTime]'s (negative: behind). */
    val offsetMinutes: Int get() =
        (placeTime.offset.totalSeconds - fromTime.offset.totalSeconds) / 60

    override fun getSpeechOutput(ctx: SkillContext): String = when (request) {
        is WorldTimeRequest.TimeIn -> ctx.getString(
            when {
                dayOffset > 0 -> R.string.skill_world_time_in_tomorrow
                dayOffset < 0 -> R.string.skill_world_time_in_yesterday
                else -> R.string.skill_world_time_in
            },
            formatTime(ctx, placeTime), place.name,
        )
        is WorldTimeRequest.Difference -> difference(ctx, request.from)
    }

    private fun difference(ctx: SkillContext, from: Place?): String {
        val minutes = offsetMinutes
        if (minutes == 0) {
            return if (from == null) {
                ctx.getString(R.string.skill_world_time_same, place.name)
            } else {
                ctx.getString(R.string.skill_world_time_same_as, place.name, from.name)
            }
        }
        val amount = formatOffset(ctx, minutes.absoluteValue)
        return when {
            from == null && minutes > 0 ->
                ctx.getString(R.string.skill_world_time_ahead, place.name, amount)
            from == null -> ctx.getString(R.string.skill_world_time_behind, place.name, amount)
            minutes > 0 ->
                ctx.getString(R.string.skill_world_time_ahead_of, place.name, amount, from.name)
            else -> ctx.getString(R.string.skill_world_time_behind_of, place.name, amount, from.name)
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            when (request) {
                is WorldTimeRequest.TimeIn -> {
                    Headline(text = formatTime(ctx, placeTime))
                    Subtitle(text = place.name)
                    Body(text = formatFullDate(placeTime.toLocalDate(), ctx.locale))
                }
                is WorldTimeRequest.Difference -> {
                    Headline(text = getSpeechOutput(ctx))
                    Body(text = ctx.getString(
                        R.string.skill_world_time_card_at, formatTime(ctx, placeTime), place.name,
                    ))
                    val from = request.from
                    Body(text = if (from == null) {
                        ctx.getString(R.string.skill_world_time_card_here, formatTime(ctx, fromTime))
                    } else {
                        ctx.getString(
                            R.string.skill_world_time_card_at, formatTime(ctx, fromTime), from.name,
                        )
                    })
                }
            }
        }
    }

    companion object {
        fun formatTime(ctx: SkillContext, time: ZonedDateTime): String =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(ctx.locale).format(time)

        /** "7 hours", "5 and a half hours", "5 hours and 45 minutes", "30 minutes". */
        fun formatOffset(ctx: SkillContext, minutes: Int): String {
            val resources = ctx.android.resources
            val hours = minutes / 60
            val rest = minutes % 60
            fun hoursText() =
                resources.getQuantityString(R.plurals.skill_world_time_hours, hours, hours)
            fun minutesText() =
                resources.getQuantityString(R.plurals.skill_world_time_minutes, rest, rest)
            return when {
                rest == 0 -> hoursText()
                hours == 0 -> minutesText()
                rest == 30 -> ctx.getString(R.string.skill_world_time_hours_and_a_half, hours)
                else -> ctx.getString(
                    R.string.skill_world_time_hours_and_minutes, hoursText(), minutesText(),
                )
            }
        }
    }
}

/** "I don't know the time in Moab.": a place GAR's offline list doesn't have. */
class WorldTimeUnknownOutput(val name: String) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_world_time_unknown_place, name)
}
