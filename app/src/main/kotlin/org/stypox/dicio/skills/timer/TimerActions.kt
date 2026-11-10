package org.stypox.dicio.skills.timer

import android.app.KeyguardManager
import android.provider.AlarmClock
import kotlinx.coroutines.delay
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.timer.GarStartedTimers
import org.stypox.dicio.gar.timer.GarTimerNames
import org.stypox.dicio.sentences.Sentences.Timer
import org.stypox.dicio.skills.alarm.AlarmIntent
import org.stypox.dicio.skills.alarm.AndroidClockApp
import org.stypox.dicio.skills.alarm.ClockApp
import org.stypox.dicio.skills.alarm.FiringAlarmCheck
import org.stypox.dicio.skills.alarm.FiringTimers
import org.stypox.dicio.skills.song.AndroidAudileLauncher
import java.time.Duration
import java.util.Locale

/** The `AlarmClock` timer intents GAR sends to the clock app, as plain data ([AlarmIntent]). */
object TimerIntents {
    /**
     * Starts a clock app timer of [seconds] called [name] (none if null), without showing the
     * clock app.
     */
    fun set(seconds: Int, name: String?): AlarmIntent {
        val extras = linkedMapOf<String, Any>(
            AlarmClock.EXTRA_LENGTH to seconds,
            AlarmClock.EXTRA_SKIP_UI to true,
        )
        if (!name.isNullOrBlank()) {
            extras[AlarmClock.EXTRA_MESSAGE] = name
        }
        return AlarmIntent(AlarmClock.ACTION_SET_TIMER, extras)
    }

    /** Stops the clock app's ringing timers; AOSP DeskClock leaves the others alone. */
    fun dismiss(): AlarmIntent = AlarmIntent(AlarmClock.ACTION_DISMISS_TIMER)

    /** Opens the clock app's timers. */
    fun show(): AlarmIntent = AlarmIntent(AlarmClock.ACTION_SHOW_TIMERS)
}

/**
 * GAR's timers are the clock app's (gar/DESIGN.md, "Timers"): Dicio's timer sentences, the
 * model's `timer_set`, `timer_cancel` and `timer_query` calls (through Dicio's sentence data,
 * unchanged) and GAR's timer controls end here. Setting a timer starts one in the clock app;
 * stopping a ringing one dismisses it. `AlarmClock` has no intent to pause, resume, cancel, add
 * time to or read a timer, so those open the clock app's timers and say so. "How long is left"
 * is answered from the timers GAR started ([GarStartedTimers]), as an estimate.
 *
 * @param firingTimer whether the clock app's timer rings, see [FiringTimers]
 * @param pause waits while checking that a dismissed timer went quiet
 * @param durationOf the duration in a name as said ("the ten minute timer")
 * @param canStartActivities whether Android lets GAR start the clock app: from the background it
 * drops the start without an error, so GAR doesn't try (the song skill's check)
 * @param isLocked whether the lock screen is up: the clock app's timers open behind it
 */
class TimerActions(
    private val clock: ClockApp,
    private val timers: GarStartedTimers,
    private val firingTimer: () -> FiringAlarmCheck,
    private val pause: suspend (Long) -> Unit,
    private val durationOf: (String) -> Long?,
    private val canStartActivities: () -> Boolean = { true },
    private val isLocked: () -> Boolean = { false },
) {
    /** Dicio's timer sentences, and the model's calls through them. */
    suspend fun generateOutput(inputData: Timer): SkillOutput = when (inputData) {
        is Timer.Set -> if (inputData.duration == null) {
            TimerOutput.SetAskDuration { set(it, inputData.name) }
        } else {
            set(inputData.duration.toJavaDuration(), inputData.name)
        }
        is Timer.Query -> query(GarTimerNames.said(inputData.name))
        is Timer.Cancel -> cancel(GarTimerNames.said(inputData.name))
    }

    /** GAR's timer controls. */
    suspend fun run(command: TimerCommand): SkillOutput = when (command) {
        is TimerCommand.StopRinging -> stop()
        TimerCommand.Show -> open(TimerAnswer.Shown)
        is TimerCommand.Pause, is TimerCommand.Resume, is TimerCommand.AddTime,
        is TimerCommand.Cancel, TimerCommand.CancelAll -> open(TimerAnswer.CantChange)
    }

    /** Starts a clock app timer; a duration under a second is a second. */
    fun set(duration: Duration, name: String?): SkillOutput {
        val seconds = ((duration.toMillis() + 500) / 1000).coerceAtLeast(1)
        if (seconds > MAX_SECONDS) {
            return TimerAnswerOutput(TimerAnswer.TooLong)
        }
        val label = GarTimerNames.said(name)?.let { GarTimerNames.clean(it) }
            ?.takeIf { it.isNotEmpty() }
        val intent = TimerIntents.set(
            seconds.toInt(), label?.replaceFirstChar { it.titlecase(Locale.ENGLISH) },
        )
        if (!canStartActivities()) {
            return TimerAnswerOutput(TimerAnswer.Background)
        }
        if (!clock.start(intent)) {
            return TimerAnswerOutput(TimerAnswer.NoClockApp)
        }
        timers.add(seconds * 1000, label)
        return TimerAnswerOutput(TimerAnswer.Started(label, seconds * 1000))
    }

    /**
     * "Cancel the timer", "stop the timer": a ringing timer stops; a running one can't be
     * cancelled by intent, so the clock app's timers open. So does a [name] that is a timer GAR
     * started that still counts: dismissing would stop the timer that rings instead.
     */
    suspend fun cancel(name: String?): SkillOutput {
        val now = timers.clock.now()
        val counting = name?.let { GarTimerNames.find(timers.current(), it, durationOf) }
            ?.takeIf { it.remainingMillis(now) > 0 }
        return if (counting == null && isRinging()) stop() else open(TimerAnswer.CantChange)
    }

    /**
     * Dismisses the clock app's ringing timers, and says so once they went quiet. Without
     * notification access GAR goes by the sound: the dismiss only stops ringing timers, so a
     * ringing alarm comes to no harm, and the sound tells whether it worked.
     */
    suspend fun stop(): SkillOutput {
        if (!isRinging()) {
            return TimerAnswerOutput(TimerAnswer.NothingRinging)
        }
        if (!canStartActivities()) {
            return TimerAnswerOutput(TimerAnswer.Background)
        }
        if (!clock.start(TimerIntents.dismiss())) {
            return TimerAnswerOutput(TimerAnswer.NoClockApp)
        }
        timers.forgetEnded()
        val stopped = waitFor { !isRinging() }
        return TimerAnswerOutput(if (stopped) TimerAnswer.Stopped else TimerAnswer.StillRinging)
    }

    /**
     * How long the timer named [name] has left, or without a name the one GAR started that ends
     * first; an estimate, since it may have been changed in the clock app. A timer GAR didn't
     * start opens the clock app's timers.
     */
    fun query(name: String?): SkillOutput {
        val now = timers.clock.now()
        val all = timers.current()
        val timer = if (name == null) {
            all.filter { it.remainingMillis(now) > 0 }.minByOrNull { it.remainingMillis(now) }
                ?: all.lastOrNull()
        } else {
            GarTimerNames.find(all, name, durationOf)
        } ?: return open(TimerAnswer.CantCheck)
        val left = timer.remainingMillis(now)
        return TimerAnswerOutput(
            if (left > 0) TimerAnswer.Left(timer.name, left) else TimerAnswer.Ended(timer.name)
        )
    }

    /**
     * Opens the clock app's timers, and answers [answer]; on a locked phone they open behind the
     * lock screen and show once it is unlocked, so GAR says to unlock it.
     */
    fun open(answer: TimerAnswer): SkillOutput = TimerAnswerOutput(
        when {
            !canStartActivities() -> TimerAnswer.Background
            !clock.start(TimerIntents.show()) -> TimerAnswer.NoClockApp
            isLocked() -> TimerAnswer.Locked(answer)
            else -> answer
        }
    )

    private fun isRinging(): Boolean = when (firingTimer()) {
        FiringAlarmCheck.Firing -> true
        FiringAlarmCheck.None -> false
        FiringAlarmCheck.NoAccess -> clock.isAlarmRinging()
    }

    /** Waits until [done], for at most [WAIT_MILLIS]; whether it got done. */
    private suspend fun waitFor(done: () -> Boolean): Boolean {
        repeat((WAIT_MILLIS / WAIT_STEP_MILLIS).toInt()) {
            if (done()) {
                return true
            }
            pause(WAIT_STEP_MILLIS)
        }
        return done()
    }

    companion object {
        /** `AlarmClock.EXTRA_LENGTH` is 1 to 86400 seconds. */
        const val MAX_SECONDS = 24 * 60 * 60L

        /** How long GAR waits for a dismissed timer to go quiet, and how often it looks. */
        const val WAIT_MILLIS = 2000L
        const val WAIT_STEP_MILLIS = 100L

        /** The duration said in a timer's name ("ten minute"), to find a timer without a name. */
        fun durationOf(ctx: SkillContext): (String) -> Long? = { said ->
            ctx.parserFormatter?.extractDuration(said)?.parseFirst()?.toJavaDuration()?.toMillis()
        }

        /** The actions on the phone. */
        fun forContext(ctx: SkillContext) = TimerActions(
            clock = AndroidClockApp(ctx.android),
            timers = GarStartedTimers.get(ctx.android),
            firingTimer = FiringTimers::current,
            pause = { delay(it) },
            durationOf = durationOf(ctx),
            canStartActivities = { AndroidAudileLauncher(ctx.android).canStartActivities() },
            isLocked = {
                ctx.android.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            },
        )
    }
}
