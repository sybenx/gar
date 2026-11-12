package org.stypox.dicio.skills.alarm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.aaro.gar.router.resolve.ResolvedTime
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarValueFormatter
import org.stypox.dicio.gar.ui.GarChip
import org.stypox.dicio.gar.ui.GarChipStrings
import org.stypox.dicio.gar.ui.GarChipTable
import org.stypox.dicio.gar.ui.GarSuggestions
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.skills.song.openListenerSettings
import org.stypox.dicio.util.getString
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

/** The day an alarm rings, relative to today, as the alarm skill says it. */
sealed interface AlarmDay {
    data object Today : AlarmDay
    data object Tomorrow : AlarmDay
    /** Within the next week: "on Monday". */
    data class Weekday(val day: DayOfWeek) : AlarmDay
    data class Date(val date: LocalDate) : AlarmDay

    companion object {
        fun of(date: LocalDate, today: LocalDate): AlarmDay =
            when (val days = ChronoUnit.DAYS.between(today, date)) {
                0L -> Today
                1L -> Tomorrow
                else -> if (days in 2..6) Weekday(date.dayOfWeek) else Date(date)
            }
    }
}

sealed interface AlarmOutput : SkillOutput {

    /** Outputs whose card is just their text. */
    abstract class Text : AlarmOutput {
        abstract fun text(ctx: SkillContext, speech: Boolean): String

        override fun getSpeechOutput(ctx: SkillContext): String = text(ctx, speech = true)

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Headline(text = text(ctx, speech = false))
        }
    }

    /**
     * The alarm was handed to the clock app.
     * @param rings when a one-off alarm rings next (null for repeating alarms)
     */
    class Set(
        val time: ResolvedTime,
        val label: String?,
        val rings: LocalDateTime?,
        private val today: LocalDate,
        private val formatter: GarValueFormatter,
    ) : Text(), GarSuggestions {
        override fun suggestions(ctx: SkillContext): List<GarChip> =
            GarChipTable.alarmSet(GarChipStrings.of(ctx), time.time)

        override fun text(ctx: SkillContext, speech: Boolean): String {
            val whenText = if (rings == null) {
                formatter.format(time, speech) ?: ""
            } else {
                dayAndTime(ctx, formatter, rings, today, speech)
            }
            return if (label == null) {
                ctx.getString(R.string.skill_alarm_set, whenText)
            } else {
                ctx.getString(
                    R.string.skill_alarm_set_label,
                    whenText,
                    label.replaceFirstChar { it.uppercaseChar() },
                )
            }
        }
    }

    /**
     * When the next alarm rings.
     * @param openFailed the user asked to open the alarms, but no app could show them
     * @param open shows a button that opens the clock app's alarms, if not null
     */
    class Show(
        val next: NextAlarm?,
        val opened: Boolean,
        val openFailed: Boolean,
        private val today: LocalDate,
        private val formatter: GarValueFormatter,
        val open: (() -> Unit)?,
    ) : AlarmOutput, GarSuggestions {
        override fun suggestions(ctx: SkillContext): List<GarChip> =
            GarChipTable.alarmShow(GarChipStrings.of(ctx))

        private fun text(ctx: SkillContext, speech: Boolean): String {
            val nextText = when {
                next == null -> ctx.getString(R.string.skill_alarm_none)
                next.setBy == null -> ctx.getString(
                    R.string.skill_alarm_next,
                    dayAndTime(ctx, formatter, next.time, today, speech),
                )
                else -> ctx.getString(
                    R.string.skill_alarm_next_set_by,
                    dayAndTime(ctx, formatter, next.time, today, speech),
                    next.setBy,
                )
            }
            return if (openFailed) {
                nextText + " " + ctx.getString(R.string.skill_alarm_no_clock_app)
            } else {
                nextText
            }
        }

        override fun getSpeechOutput(ctx: SkillContext): String = text(ctx, speech = true)

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = text(ctx, speech = false))
                if (open != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    GarbleSecondaryButton(onClick = open) { // GAR
                        Text(text = stringResource(R.string.skill_alarm_open_alarms))
                    }
                }
            }
        }
    }

    /**
     * The clock app was asked to dismiss [target].
     * @param next the alarm that will be dismissed, when it is known to be the next alarm and to
     * ring within 24 hours, so the clock app can dismiss it
     */
    class Dismissing(
        val target: CancelTarget,
        val next: NextAlarm?,
        private val today: LocalDate,
        private val formatter: GarValueFormatter,
    ) : Text() {
        override fun text(ctx: SkillContext, speech: Boolean): String {
            if (next != null) {
                return ctx.getString(
                    R.string.skill_alarm_dismissing_known,
                    dayAndTime(ctx, formatter, next.time, today, speech),
                )
            }
            val opening = when (target) {
                is CancelTarget.At -> ctx.getString(
                    R.string.skill_alarm_dismissing_at,
                    formatter.format(target.time, speech),
                )
                CancelTarget.Next -> ctx.getString(R.string.skill_alarm_dismissing_next)
                CancelTarget.All -> ctx.getString(R.string.skill_alarm_dismissing_all)
            }
            // AOSP DeskClock only dismisses alarms within 24 hours, without saying so
            return opening + " " + ctx.getString(R.string.skill_alarm_dismiss_limit)
        }
    }

    /**
     * The clock app's [next] alarm, and so every alarm it could dismiss, rings more than 24 hours
     * from now, which AOSP DeskClock refuses silently, so the alarms were opened instead (if
     * [opened]).
     */
    class TooFarAhead(
        val next: NextAlarm,
        val opened: Boolean,
        private val today: LocalDate,
        private val formatter: GarValueFormatter,
    ) : Text() {
        override fun text(ctx: SkillContext, speech: Boolean): String {
            val text = ctx.getString(
                R.string.skill_alarm_too_far_ahead,
                dayAndTime(ctx, formatter, next.time, today, speech),
            )
            return if (opened) text + " " + ctx.getString(R.string.skill_alarm_opening_alarms) else text
        }
    }

    /**
     * The next alarm was set by another app ([NextAlarm.setBy]), so the clock app can't dismiss
     * it; nothing was sent.
     */
    class OtherApp(
        val next: NextAlarm,
        private val today: LocalDate,
        private val formatter: GarValueFormatter,
    ) : Text() {
        override fun text(ctx: SkillContext, speech: Boolean): String = ctx.getString(
            R.string.skill_alarm_other_app,
            dayAndTime(ctx, formatter, next.time, today, speech),
            next.setBy,
        )
    }

    /**
     * Something rings that GAR can't tell to be the clock app's alarm (it may be a timer), so
     * dismissing "the next alarm" could skip an alarm to come; nothing was sent.
     * @param needsAccess GAR has no notification access, with which it could tell
     * ([FiringAlarms]); the card offers it
     */
    data class Ringing(val needsAccess: Boolean) : AlarmOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_alarm_ringing)

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            val context = LocalContext.current
            Column {
                Headline(text = ctx.getString(R.string.skill_alarm_ringing))
                if (needsAccess) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Body(text = stringResource(R.string.skill_alarm_needs_listener))
                    Spacer(modifier = Modifier.height(8.dp))
                    GarbleSecondaryButton( // GAR
                        onClick = { openListenerSettings(context) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(text = stringResource(R.string.skill_alarm_allow_access))
                    }
                }
            }
        }
    }

    /** The clock app was asked to dismiss its ringing alarm, and it stopped ringing. */
    data object Stopped : Text() {
        override fun text(ctx: SkillContext, speech: Boolean): String =
            ctx.getString(R.string.skill_alarm_stopped)
    }

    /**
     * The clock app was asked to snooze its ringing alarm, and it stopped ringing.
     * @param until when it rings again, if the clock app's next alarm told it
     * @param ownLength the clock app snoozed for another length than the user asked for
     */
    class Snoozed(
        val until: LocalTime?,
        val ownLength: Boolean,
        private val formatter: GarValueFormatter,
    ) : Text() {
        override fun text(ctx: SkillContext, speech: Boolean): String {
            val text = if (until == null) {
                ctx.getString(R.string.skill_alarm_snoozed)
            } else {
                ctx.getString(R.string.skill_alarm_snoozed_until, formatter.format(until, speech))
            }
            // the details only on the card
            return if (ownLength && !speech) {
                text + " " + ctx.getString(R.string.skill_alarm_snooze_length)
            } else {
                text
            }
        }
    }

    /**
     * The clock app was asked to stop or [snooze] the alarm, but it still rings: AOSP DeskClock
     * asks which one when several ring, or what rings isn't the clock app's.
     */
    data class StillRinging(val snooze: Boolean) : Text() {
        override fun text(ctx: SkillContext, speech: Boolean): String = ctx.getString(
            if (snooze) R.string.skill_alarm_still_ringing_snooze else R.string.skill_alarm_still_ringing
        )
    }

    /** "Stop" or "snooze" while no alarm rings; nothing was sent. */
    data object NotRinging : Text() {
        override fun text(ctx: SkillContext, speech: Boolean): String =
            ctx.getString(R.string.skill_alarm_not_ringing)
    }

    /**
     * The new alarm was set and the clock app was asked to dismiss the old one.
     * @param rings when a one-off new alarm rings (null for a repeating one)
     * @param oldKnown the old alarm is the clock app's next alarm, so it rings within 24 hours
     * and the clock app can dismiss it
     * @param oldDismissed false if the clock app couldn't be asked to dismiss the old alarm
     */
    class Changed(
        val to: ResolvedTime,
        val rings: LocalDateTime?,
        val oldKnown: Boolean,
        val oldDismissed: Boolean,
        private val today: LocalDate,
        private val formatter: GarValueFormatter,
    ) : Text() {
        override fun text(ctx: SkillContext, speech: Boolean): String {
            val whenText = if (rings == null) {
                formatter.format(to, speech) ?: ""
            } else {
                dayAndTime(ctx, formatter, rings, today, speech)
            }
            if (!oldDismissed) {
                return ctx.getString(R.string.skill_alarm_changed_old_kept, whenText)
            }
            val text = ctx.getString(R.string.skill_alarm_changed, whenText)
            // what the clock app's dismissal leaves, only on the card
            return when {
                speech -> text
                oldKnown -> text + " " + ctx.getString(R.string.skill_alarm_change_repeats)
                else -> text + " " + ctx.getString(R.string.skill_alarm_change_limit)
            }
        }
    }

    /** The alarm to change is already at the new time; nothing was sent. */
    class AlreadyAt(val time: LocalTime, private val formatter: GarValueFormatter) : Text() {
        override fun text(ctx: SkillContext, speech: Boolean): String =
            ctx.getString(R.string.skill_alarm_already_at, formatter.format(time, speech))
    }

    data object NoAlarms : Text() {
        override fun text(ctx: SkillContext, speech: Boolean): String =
            ctx.getString(R.string.skill_alarm_none)
    }

    data object NoClockApp : Text() {
        override fun text(ctx: SkillContext, speech: Boolean): String =
            ctx.getString(R.string.skill_alarm_no_clock_app)
    }
}

/** "tomorrow at seven AM", "on Monday at 7:00 AM", ... */
private fun dayAndTime(
    ctx: SkillContext,
    formatter: GarValueFormatter,
    time: LocalDateTime,
    today: LocalDate,
    speech: Boolean,
): String {
    val day = when (val day = AlarmDay.of(time.toLocalDate(), today)) {
        AlarmDay.Today -> ctx.getString(R.string.skill_alarm_today)
        AlarmDay.Tomorrow -> ctx.getString(R.string.skill_alarm_tomorrow)
        is AlarmDay.Weekday -> ctx.getString(
            R.string.skill_alarm_on_day,
            day.day.getDisplayName(TextStyle.FULL, ctx.locale),
        )
        is AlarmDay.Date -> ctx.getString(R.string.skill_alarm_on_day, formatter.format(day.date, speech))
    }
    return ctx.getString(R.string.skill_alarm_day_at_time, day, formatter.format(time.toLocalTime(), speech))
}
