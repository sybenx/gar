package org.stypox.dicio.skills.timer

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.util.getString
import java.util.Locale

/**
 * What a timer command did, for its answer (GAR: timers are the clock app's, gar/DESIGN.md
 * "Timers"). Plain data, so tests can check it without Android; [TimerAnswers.text] says it.
 * Times are in milliseconds.
 */
sealed interface TimerAnswer {
    /** A clock app timer of [durationMillis] called [name] started. */
    data class Started(val name: String?, val durationMillis: Long) : TimerAnswer

    /** `AlarmClock.EXTRA_LENGTH` goes up to a day. */
    data object TooLong : TimerAnswer
    data object NoClockApp : TimerAnswer

    /** The clock app's ringing timers stopped. */
    data object Stopped : TimerAnswer

    /** Something still rings after the dismiss: maybe not a timer. */
    data object StillRinging : TimerAnswer
    data object NothingRinging : TimerAnswer

    /** The clock app's timers are open ("show my timers"). */
    data object Shown : TimerAnswer

    /** The clock app's timers are open, since no intent pauses, resumes, cancels or adds time. */
    data object CantChange : TimerAnswer

    /** The clock app's timers are open, since GAR knows nothing about the timer asked for. */
    data object CantCheck : TimerAnswer

    /** [opened] ([Shown], [CantChange] or [CantCheck]) on a locked phone: Clock is behind the lock. */
    data class Locked(val opened: TimerAnswer) : TimerAnswer

    /** Android would block the clock app's start: GAR is neither on screen nor the assistant. */
    data object Background : TimerAnswer

    /** By GAR's reckoning, a timer it started has [remainingMillis] left. */
    data class Left(val name: String?, val remainingMillis: Long) : TimerAnswer

    /** By GAR's reckoning, a timer it started has ended. */
    data class Ended(val name: String?) : TimerAnswer
}

/** The words of [TimerAnswer]s: short to say. */
object TimerAnswers {

    fun text(ctx: SkillContext, answer: TimerAnswer): String = when (answer) {
        is TimerAnswer.Started -> {
            val duration = duration(ctx, answer.durationMillis).replaceFirstChar {
                it.titlecase(Locale.ENGLISH)
            }
            if (answer.name == null) {
                ctx.getString(R.string.skill_timer_started, duration)
            } else {
                ctx.getString(
                    R.string.skill_timer_started_name,
                    answer.name.replaceFirstChar { it.titlecase(Locale.ENGLISH) },
                    duration.lowercase(Locale.ENGLISH),
                )
            }
        }
        TimerAnswer.TooLong -> ctx.getString(R.string.skill_timer_too_long)
        TimerAnswer.NoClockApp -> ctx.getString(R.string.skill_alarm_no_clock_app)
        TimerAnswer.Stopped -> ctx.getString(R.string.skill_timer_control_stopped)
        TimerAnswer.StillRinging -> ctx.getString(R.string.skill_timer_still_ringing)
        TimerAnswer.NothingRinging -> ctx.getString(R.string.skill_timer_control_nothing_ringing)
        TimerAnswer.Shown -> ctx.getString(R.string.skill_timer_shown)
        TimerAnswer.CantChange -> ctx.getString(R.string.skill_timer_cant_change)
        TimerAnswer.CantCheck -> ctx.getString(R.string.skill_timer_cant_check)
        is TimerAnswer.Locked -> ctx.getString(
            when (answer.opened) {
                TimerAnswer.Shown -> R.string.skill_timer_shown_locked
                TimerAnswer.CantCheck -> R.string.skill_timer_cant_check_locked
                else -> R.string.skill_timer_cant_change_locked
            }
        )
        TimerAnswer.Background -> ctx.getString(R.string.skill_timer_background)
        is TimerAnswer.Left -> if (answer.name == null) {
            ctx.getString(R.string.skill_timer_left, duration(ctx, roundLeft(answer.remainingMillis)))
        } else {
            ctx.getString(
                R.string.skill_timer_left_name,
                answer.name, duration(ctx, roundLeft(answer.remainingMillis)),
            )
        }
        is TimerAnswer.Ended -> if (answer.name == null) {
            ctx.getString(R.string.skill_timer_ended)
        } else {
            ctx.getString(R.string.skill_timer_ended_name, answer.name)
        }
    }

    /** A duration as dicio-numbers says it ("four minutes thirty two seconds"), to the second. */
    fun duration(ctx: SkillContext, millis: Long): String {
        val seconds = ((millis + 500) / 1000).coerceAtLeast(1)
        return getFormattedDuration(ctx.parserFormatter!!, seconds * 1000, true)
    }

    /**
     * An estimate, said as one: to the second under a minute, else to the minute ("about four
     * minutes", not "four minutes thirty two seconds").
     */
    fun roundLeft(millis: Long): Long = if (millis < MINUTE) {
        millis
    } else {
        (millis + MINUTE / 2) / MINUTE * MINUTE
    }

    private const val MINUTE = 60_000L
}
