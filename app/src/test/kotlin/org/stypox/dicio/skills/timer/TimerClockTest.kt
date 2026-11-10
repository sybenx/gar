package org.stypox.dicio.skills.timer

import android.app.Notification
import android.provider.AlarmClock
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.timer.GarOldTimers
import org.stypox.dicio.gar.timer.GarStartedTimers
import org.stypox.dicio.gar.timer.GarTimerClock
import org.stypox.dicio.gar.timer.GarTimerStore
import org.stypox.dicio.sentences.Sentences.Timer
import org.stypox.dicio.skills.alarm.AlarmIntent
import org.stypox.dicio.skills.alarm.ClockApp
import org.stypox.dicio.skills.alarm.FiringAlarmCheck
import org.stypox.dicio.skills.alarm.FiringAlarms
import org.stypox.dicio.skills.alarm.FiringTimers
import org.stypox.dicio.skills.alarm.NextAlarm
import org.stypox.dicio.skills.stopwatch.ClockReading
import java.time.Duration
import org.dicio.numbers.unit.Duration as DicioDuration

private const val MINUTE = 60_000L

/** The clock app: records the intents it gets; [sound] is whether something plays as an alarm. */
private class FakeClockApp(var installed: Boolean = true, var sound: Boolean = false) : ClockApp {
    val started = mutableListOf<AlarmIntent>()

    override fun canHandle(intent: AlarmIntent): Boolean = installed

    override fun start(intent: AlarmIntent): Boolean {
        if (!installed) return false
        started += intent
        return true
    }

    override fun nextAlarm(): NextAlarm? = null

    override fun isAlarmRinging(): Boolean = sound
}

/** A phone with a clock app, its notifications ([firing]) and the timers GAR started. */
private class Phone(var firing: FiringAlarmCheck = FiringAlarmCheck.None) {
    /** Whether Android lets GAR start the clock app, and whether the lock screen is up. */
    var foreground = true
    var locked = false

    var elapsed = 1_000_000L
    var wall = 1_700_000_000_000L
    val saved = mutableListOf<String>()
    val store = object : GarTimerStore {
        override fun load(): String? = saved.lastOrNull()
        override fun save(encoded: String) {
            saved += encoded
        }
    }
    val clockReading = GarTimerClock { ClockReading(elapsed, wall, 1) }
    val clock = FakeClockApp()
    val timers = GarStartedTimers(store, clockReading)
    val pauses = mutableListOf<Long>()

    /** Called at each pause of the actions: the phone may change meanwhile. */
    var onPause: () -> Unit = {}

    val actions = TimerActions(
        clock = clock,
        timers = timers,
        firingTimer = { firing },
        pause = {
            pauses += it
            onPause()
        },
        durationOf = { said -> if ("ten minute" in said) 10 * MINUTE else null },
        canStartActivities = { foreground },
        isLocked = { locked },
    )

    fun advance(millis: Long) {
        elapsed += millis
        wall += millis
    }
}

private fun SkillOutput.answer(): TimerAnswer = shouldBeInstanceOf<TimerAnswerOutput>().answer

private fun minutes(n: Long) = DicioDuration(Duration.ofMinutes(n))

/**
 * Timers are the clock app's (gar/DESIGN.md, "Timers"): the intents GAR sends, its answers, how
 * it stops a ringing timer, and what it remembers of the timers it started.
 */
class TimerClockTest : StringSpec({
    "the intents" {
        TimerIntents.set(600, "Pasta") shouldBe AlarmIntent(
            AlarmClock.ACTION_SET_TIMER,
            mapOf(
                AlarmClock.EXTRA_LENGTH to 600,
                AlarmClock.EXTRA_SKIP_UI to true,
                AlarmClock.EXTRA_MESSAGE to "Pasta",
            ),
        )
        TimerIntents.set(5, null).extras shouldBe
            mapOf(AlarmClock.EXTRA_LENGTH to 5, AlarmClock.EXTRA_SKIP_UI to true)
        TimerIntents.set(5, " ").extras.containsKey(AlarmClock.EXTRA_MESSAGE).shouldBeFalse()
        TimerIntents.dismiss() shouldBe AlarmIntent(AlarmClock.ACTION_DISMISS_TIMER)
        TimerIntents.show() shouldBe AlarmIntent(AlarmClock.ACTION_SHOW_TIMERS)
    }

    "setting a timer starts one in the clock app, and GAR remembers it" {
        val phone = Phone()
        phone.actions.generateOutput(Timer.Set(minutes(10), null)).answer() shouldBe
            TimerAnswer.Started(null, 10 * MINUTE)
        phone.actions.generateOutput(Timer.Set(minutes(8), "the pasta ")).answer() shouldBe
            TimerAnswer.Started("pasta", 8 * MINUTE)
        phone.clock.started shouldBe listOf(TimerIntents.set(600, null), TimerIntents.set(480, "Pasta"))
        phone.timers.current().map { Pair(it.name, it.durationMillis) } shouldBe
            listOf(Pair(null, 10 * MINUTE), Pair("pasta", 8 * MINUTE))
        // a new process reads them back
        GarStartedTimers(phone.store, phone.clockReading).current() shouldBe phone.timers.current()
    }

    "a duration said without one asks for it" {
        Phone().actions.generateOutput(Timer.Set(null, null))
            .shouldBeInstanceOf<TimerOutput.SetAskDuration>()
    }

    "set: rounding, the 24 hour limit and no clock app" {
        val phone = Phone()
        phone.actions.set(Duration.ofMillis(200), null).answer() shouldBe TimerAnswer.Started(null, 1000)
        phone.clock.started.single().extras[AlarmClock.EXTRA_LENGTH] shouldBe 1
        phone.actions.set(Duration.ofHours(25), null).answer() shouldBe TimerAnswer.TooLong
        phone.actions.set(Duration.ofHours(24), null).answer() shouldBe
            TimerAnswer.Started(null, 24 * 60 * MINUTE)
        phone.clock.started.size shouldBe 2

        val none = Phone().apply { clock.installed = false }
        none.actions.set(Duration.ofMinutes(5), null).answer() shouldBe TimerAnswer.NoClockApp
        none.timers.current().shouldBeEmpty()
    }

    "stop dismisses the clock app's ringing timer, and says so once it is quiet" {
        val phone = Phone(firing = FiringAlarmCheck.Firing)
        phone.actions.set(Duration.ofMinutes(1), "pasta")
        phone.advance(2 * MINUTE)
        phone.onPause = { phone.firing = FiringAlarmCheck.None }
        phone.actions.run(TimerCommand.StopRinging(null)).answer() shouldBe TimerAnswer.Stopped
        phone.clock.started.last() shouldBe TimerIntents.dismiss()
        // the ended timer is forgotten
        phone.timers.current().shouldBeEmpty()
    }

    "a timer that goes on ringing, and nothing ringing" {
        val stuck = Phone(firing = FiringAlarmCheck.Firing)
        stuck.actions.stop().answer() shouldBe TimerAnswer.StillRinging
        stuck.pauses.sum() shouldBe TimerActions.WAIT_MILLIS

        val quiet = Phone(firing = FiringAlarmCheck.None)
        quiet.actions.stop().answer() shouldBe TimerAnswer.NothingRinging
        quiet.clock.started.shouldBeEmpty()
    }

    "without notification access, GAR goes by the sound" {
        val phone = Phone(firing = FiringAlarmCheck.NoAccess)
        phone.actions.stop().answer() shouldBe TimerAnswer.NothingRinging
        phone.clock.sound = true
        phone.onPause = { phone.clock.sound = false }
        phone.actions.stop().answer() shouldBe TimerAnswer.Stopped
        phone.clock.started shouldBe listOf(TimerIntents.dismiss())
        // an alarm that rings goes on ringing: the dismiss only stops timers
        phone.clock.sound = true
        phone.onPause = {}
        phone.actions.stop().answer() shouldBe TimerAnswer.StillRinging
    }

    "cancel stops a ringing timer, else opens the clock app's timers" {
        val ringing = Phone(firing = FiringAlarmCheck.Firing)
        ringing.onPause = { ringing.firing = FiringAlarmCheck.None }
        ringing.actions.generateOutput(Timer.Cancel(null)).answer() shouldBe TimerAnswer.Stopped
        ringing.clock.started shouldBe listOf(TimerIntents.dismiss())

        val running = Phone()
        running.actions.set(Duration.ofMinutes(10), "pasta")
        running.actions.generateOutput(Timer.Cancel("pasta")).answer() shouldBe TimerAnswer.CantChange
        running.clock.started.last() shouldBe TimerIntents.show()

        // a timer that still counts, named while another rings: not the ringing one
        running.firing = FiringAlarmCheck.Firing
        running.actions.generateOutput(Timer.Cancel("the pasta")).answer() shouldBe TimerAnswer.CantChange
        running.clock.started.last() shouldBe TimerIntents.show()
    }

    "what the clock app has no intent for opens its timers" {
        for (command in listOf(
            TimerCommand.Pause(null), TimerCommand.Resume("pasta"), TimerCommand.AddTime(MINUTE, null),
            TimerCommand.AddTime(-MINUTE, "pasta"), TimerCommand.Cancel(null), TimerCommand.CancelAll,
        )) {
            val phone = Phone()
            phone.actions.run(command).answer() shouldBe TimerAnswer.CantChange
            phone.clock.started shouldBe listOf(TimerIntents.show())
        }
        val phone = Phone()
        phone.actions.run(TimerCommand.Show).answer() shouldBe TimerAnswer.Shown
        phone.clock.started shouldBe listOf(TimerIntents.show())
        phone.clock.installed = false
        phone.actions.run(TimerCommand.Show).answer() shouldBe TimerAnswer.NoClockApp
    }

    "how long is left: an estimate from the timers GAR started" {
        val phone = Phone()
        phone.actions.generateOutput(Timer.Query(null)).answer() shouldBe TimerAnswer.CantCheck
        phone.clock.started shouldBe listOf(TimerIntents.show())

        phone.actions.set(Duration.ofMinutes(10), null)
        phone.actions.set(Duration.ofMinutes(5), "pasta")
        phone.advance(MINUTE)
        // without a name, the one that ends first
        phone.actions.generateOutput(Timer.Query(null)).answer() shouldBe
            TimerAnswer.Left("pasta", 4 * MINUTE)
        phone.actions.generateOutput(Timer.Query("the ten minute")).answer() shouldBe
            TimerAnswer.Left(null, 9 * MINUTE)
        phone.actions.generateOutput(Timer.Query("eggs")).answer() shouldBe TimerAnswer.CantCheck
        phone.advance(5 * MINUTE)
        phone.actions.generateOutput(Timer.Query("pasta")).answer() shouldBe TimerAnswer.Ended("pasta")
        // "my" names nothing
        phone.actions.generateOutput(Timer.Query("my")).answer() shouldBe
            TimerAnswer.Left(null, 4 * MINUTE)
    }

    "estimates are said to the minute, short ones to the second" {
        TimerAnswers.roundLeft(42_000) shouldBe 42_000
        TimerAnswers.roundLeft(4 * MINUTE + 29_000) shouldBe 4 * MINUTE
        TimerAnswers.roundLeft(4 * MINUTE + 31_000) shouldBe 5 * MINUTE
    }

    "started timers are forgotten an hour after their end, and survive broken state" {
        val phone = Phone()
        phone.actions.set(Duration.ofMinutes(1), "tea")
        phone.actions.set(Duration.ofMinutes(90), "bread")
        phone.advance(30 * MINUTE)
        phone.timers.ended().map { it.name } shouldBe listOf("tea")
        phone.advance(31 * MINUTE)
        phone.timers.current().map { it.name } shouldBe listOf("bread")
        phone.timers.ended().shouldBeEmpty()

        GarStartedTimers.decode("{broken").shouldBeEmpty()
        GarStartedTimers.decode(null).shouldBeEmpty()
    }

    "on a locked phone the clock app's timers open behind the lock screen" {
        val phone = Phone().apply { locked = true }
        phone.actions.run(TimerCommand.Show).answer() shouldBe TimerAnswer.Locked(TimerAnswer.Shown)
        phone.actions.run(TimerCommand.Pause(null)).answer() shouldBe
            TimerAnswer.Locked(TimerAnswer.CantChange)
        phone.actions.generateOutput(Timer.Query("eggs")).answer() shouldBe
            TimerAnswer.Locked(TimerAnswer.CantCheck)
        phone.clock.started shouldBe List(3) { TimerIntents.show() }
        // setting one needs no screen
        phone.actions.set(Duration.ofMinutes(5), null).answer() shouldBe
            TimerAnswer.Started(null, 5 * MINUTE)
    }

    "from the background Android would drop the start, so GAR doesn't try" {
        val phone = Phone(firing = FiringAlarmCheck.Firing).apply { foreground = false }
        phone.actions.set(Duration.ofMinutes(5), "pasta").answer() shouldBe TimerAnswer.Background
        phone.timers.current().shouldBeEmpty()
        phone.actions.stop().answer() shouldBe TimerAnswer.Background
        phone.actions.run(TimerCommand.Show).answer() shouldBe TimerAnswer.Background
        phone.clock.started.shouldBeEmpty()
        // nothing ringing says so, background or not
        Phone().apply { foreground = false }.actions.stop().answer() shouldBe
            TimerAnswer.NothingRinging
    }

    "the old engine's timers that the update stops" {
        GarOldTimers.lostTimers(null) shouldBe null
        GarOldTimers.lostTimers("{broken") shouldBe null
        GarOldTimers.lostTimers("{\"timers\":[],\"nextId\":3}") shouldBe null
        GarOldTimers.lostTimers("{\"nextId\":3}") shouldBe null
        GarOldTimers.lostTimers(
            "{\"timers\":[{\"id\":1,\"name\":\"pasta\",\"durationMillis\":600000," +
                "\"run\":{\"type\":\"running\",\"end\":{\"elapsed\":1,\"wall\":2}}}," +
                "{\"id\":2,\"durationMillis\":60000," +
                "\"run\":{\"type\":\"paused\",\"remainingMillis\":5000}}],\"nextId\":3}"
        ) shouldBe listOf("pasta", null)
    }

    "the clock app's ringing timer, by its notifications" {
        val clock = "com.android.deskclock"
        fun timer(
            category: String? = null,
            flags: Int = Notification.FLAG_FOREGROUND_SERVICE,
            fullScreen: Boolean = true,
            packageName: String = clock,
        ) = FiringTimers.isFiringTimer(packageName, clock, category, flags, fullScreen)
        fun alarm(
            category: String? = null,
            flags: Int = Notification.FLAG_FOREGROUND_SERVICE,
            fullScreen: Boolean = true,
        ) = FiringAlarms.isFiringAlarm(clock, clock, category, flags, fullScreen)

        // DeskClock's ringing timer: no category, its foreground service and a full-screen intent
        timer().shouldBeTrue()
        timer(fullScreen = false).shouldBeTrue()
        timer(flags = 0).shouldBeTrue()
        alarm().shouldBeFalse()
        // its ringing alarm is no timer
        timer(category = Notification.CATEGORY_ALARM).shouldBeFalse()
        alarm(category = Notification.CATEGORY_ALARM).shouldBeTrue()
        // Google Clock's ringing timer has the alarm category; its sort key says EXPIRED
        val googleKey = "0 0|↺11|EXPIRED|▶17:51:00.610|Σ0:00:15"
        FiringTimers.isFiringTimer(clock, clock, Notification.CATEGORY_ALARM,
            Notification.FLAG_FOREGROUND_SERVICE, true, googleKey).shouldBeTrue()
        FiringAlarms.isFiringAlarm(clock, clock, Notification.CATEGORY_ALARM,
            Notification.FLAG_FOREGROUND_SERVICE, true, googleKey).shouldBeFalse()
        // its running timers: the alarm category, neither of the others
        timer(Notification.CATEGORY_ALARM, Notification.FLAG_ONGOING_EVENT, false).shouldBeFalse()
        // its alarms to come, snoozed and missed
        timer(Notification.CATEGORY_EVENT, 0, false).shouldBeFalse()
        // a plain notification of it, and another app's timer
        timer(flags = Notification.FLAG_ONGOING_EVENT, fullScreen = false).shouldBeFalse()
        timer(packageName = "com.example.timer").shouldBeFalse()
    }
})
