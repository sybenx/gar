package org.stypox.dicio.skills.stopwatch

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.skill.skill.AlwaysWorstScore
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.sentences.Sentences

/** A clock the test moves by hand; [reboot] restarts elapsedRealtime like a real boot does. */
private class FakeClock(
    var elapsed: Long = 1_000_000,
    var wall: Long = 1_761_400_000_000,
    var boot: Int? = 7,
) : StopwatchClock {
    override fun now() = ClockReading(elapsed, wall, boot)

    fun advance(millis: Long) {
        elapsed += millis
        wall += millis
    }

    /** The device was off for [offMillis] and has been up for [upMillis] since. */
    fun reboot(offMillis: Long, upMillis: Long) {
        wall += offMillis + upMillis
        elapsed = upMillis
        boot = boot?.plus(1)
    }
}

private class MemoryStore(var saved: String? = null) : StopwatchStore {
    override fun load(): String? = saved
    override fun save(encoded: String) {
        saved = encoded
    }
}

private fun at(elapsed: Long, wall: Long = 1_761_400_000_000 + elapsed, boot: Int? = 7) =
    ClockReading(elapsed, wall, boot)

class StopwatchTest : StringSpec({
    "start, stop, resume and elapsed add up the runs" {
        var state = StopwatchState()
        state.isReset shouldBe true

        state.perform(StopwatchAction.START, at(1_000)).let { (s, event) ->
            event shouldBe StopwatchEvent.Started
            state = s
        }
        state.isRunning shouldBe true
        state.perform(StopwatchAction.ELAPSED, at(6_000)).second shouldBe
                StopwatchEvent.Elapsed(5_000, running = true)

        state.perform(StopwatchAction.STOP, at(11_000)).let { (s, event) ->
            event shouldBe StopwatchEvent.Stopped(10_000)
            state = s
        }
        state.isRunning shouldBe false
        // stopped: time doesn't move
        state.elapsedMillis(at(99_000)) shouldBe 10_000
        state.perform(StopwatchAction.ELAPSED, at(99_000)).second shouldBe
                StopwatchEvent.Elapsed(10_000, running = false)

        state.perform(StopwatchAction.RESUME, at(100_000)).let { (s, event) ->
            event shouldBe StopwatchEvent.Resumed(10_000)
            state = s
        }
        state.elapsedMillis(at(102_500)) shouldBe 12_500
    }

    "start on a stopped stopwatch continues it, on a running one changes nothing" {
        val (stopped, _) = StopwatchState().perform(StopwatchAction.START, at(0)).first
            .perform(StopwatchAction.STOP, at(3_000))
        val (resumed, event) = stopped.perform(StopwatchAction.START, at(10_000))
        event shouldBe StopwatchEvent.Resumed(3_000)
        resumed.elapsedMillis(at(11_000)) shouldBe 4_000

        val (same, again) = resumed.perform(StopwatchAction.START, at(12_000))
        again shouldBe StopwatchEvent.AlreadyRunning(5_000)
        same shouldBe resumed
        resumed.perform(StopwatchAction.RESUME, at(12_000)).second shouldBe
                StopwatchEvent.AlreadyRunning(5_000)
    }

    "resume of a reset stopwatch starts it" {
        StopwatchState().perform(StopwatchAction.RESUME, at(0)).second shouldBe StopwatchEvent.Started
    }

    "stop, lap and elapsed of a stopwatch that isn't running say so" {
        val reset = StopwatchState()
        for (action in listOf(StopwatchAction.STOP, StopwatchAction.LAP, StopwatchAction.ELAPSED)) {
            val (state, event) = reset.perform(action, at(5_000))
            event shouldBe StopwatchEvent.NotRunning(0)
            state shouldBe reset
        }

        val stopped = StopwatchState(accumulatedMillis = 4_200)
        stopped.perform(StopwatchAction.STOP, at(5_000)).second shouldBe StopwatchEvent.NotRunning(4_200)
        stopped.perform(StopwatchAction.LAP, at(5_000)).second shouldBe StopwatchEvent.NotRunning(4_200)
    }

    "reset stops and clears everything, twice is a no-op" {
        val running = StopwatchState().perform(StopwatchAction.START, at(0)).first
            .perform(StopwatchAction.LAP, at(2_000)).first
        val (reset, event) = running.perform(StopwatchAction.RESET, at(3_000))
        event shouldBe StopwatchEvent.Reset
        reset shouldBe StopwatchState()
        reset.isReset shouldBe true
        reset.perform(StopwatchAction.RESET, at(4_000)).second shouldBe StopwatchEvent.AlreadyReset
    }

    "laps record the split since the previous lap and the total" {
        var state = StopwatchState().perform(StopwatchAction.START, at(0)).first
        val events = mutableListOf<StopwatchEvent>()
        for (t in listOf(23_400L, 50_000L, 51_000L)) {
            val (s, e) = state.perform(StopwatchAction.LAP, at(t))
            state = s
            events += e
        }
        events shouldBe listOf(
            StopwatchEvent.Lap(1, 23_400, 23_400),
            StopwatchEvent.Lap(2, 26_600, 50_000),
            StopwatchEvent.Lap(3, 1_000, 51_000),
        )
        state.lapTotals shouldBe listOf(23_400L, 50_000L, 51_000L)
        state.lapDurations() shouldBe listOf(23_400L, 26_600L, 1_000L)

        // laps continue from the total after a pause
        state = state.perform(StopwatchAction.STOP, at(60_000)).first
            .perform(StopwatchAction.RESUME, at(100_000)).first
        state.perform(StopwatchAction.LAP, at(101_000)).second shouldBe
                StopwatchEvent.Lap(4, 10_000, 61_000)
    }

    "the state survives process death through the store" {
        val clock = FakeClock()
        val store = MemoryStore()
        val first = StopwatchController(store, clock)
        first.perform(StopwatchAction.START)
        clock.advance(4_000)
        first.perform(StopwatchAction.LAP)
        clock.advance(6_000)

        // a new process: a new controller reading the same store
        clock.advance(30_000)
        val second = StopwatchController(store, clock)
        second.state.value.isRunning shouldBe true
        second.state.value.lapTotals shouldBe listOf(4_000L)
        second.perform(StopwatchAction.STOP) shouldBe StopwatchEvent.Stopped(40_000)

        clock.advance(60_000)
        val third = StopwatchController(store, clock)
        third.state.value.elapsedMillis(clock.now()) shouldBe 40_000
        third.perform(StopwatchAction.ELAPSED) shouldBe StopwatchEvent.Elapsed(40_000, running = false)
    }

    "after a reboot the running time is taken from the wall clock" {
        val clock = FakeClock()
        val store = MemoryStore()
        StopwatchController(store, clock).perform(StopwatchAction.START)
        clock.advance(10_000)

        // off for a minute, then up for 2 s: elapsedRealtime is now smaller than at the start
        clock.reboot(offMillis = 60_000, upMillis = 2_000)
        val afterReboot = StopwatchController(store, clock)
        afterReboot.state.value.elapsedMillis(clock.now()) shouldBe 72_000

        // the run was moved to this boot's clocks, so a later wall clock change doesn't matter
        afterReboot.state.value.run.shouldNotBeNull().bootCount shouldBe clock.boot
        StopwatchState.decode(store.saved) shouldBe afterReboot.state.value
        clock.elapsed += 1_000
        clock.wall -= 3_600_000
        afterReboot.perform(StopwatchAction.STOP) shouldBe StopwatchEvent.Stopped(73_000)
    }

    "a reboot is detected without the boot count too" {
        val run = StopwatchState.Run(startElapsed = 500_000, startWall = 1_000_000_000, bootCount = null)
        // same boot: by elapsedRealtime, the wall clock only moved a few seconds (network time)
        val sameBoot = ClockReading(510_000, 1_000_010_000 + 5_000, null)
        StopwatchState.isSameBoot(run, sameBoot) shouldBe true
        StopwatchState.runMillis(run, sameBoot) shouldBe 10_000

        // elapsedRealtime went back: rebooted
        val rebootedEarly = ClockReading(20_000, 1_000_300_000, null)
        StopwatchState.isSameBoot(run, rebootedEarly) shouldBe false
        StopwatchState.runMillis(run, rebootedEarly) shouldBe 300_000

        // up longer than before, but the implied boot time moved by far more than clock drift
        val rebootedLate = ClockReading(900_000, 1_002_000_000, null)
        StopwatchState.isSameBoot(run, rebootedLate) shouldBe false
        StopwatchState.runMillis(run, rebootedLate) shouldBe 2_000_000

        // never negative, e.g. when the wall clock was set back across a reboot
        StopwatchState.runMillis(run, ClockReading(1_000, 900_000_000, null)) shouldBe 0
    }

    "a stopped stopwatch isn't affected by a reboot" {
        val stopped = StopwatchState(accumulatedMillis = 12_345, lapTotals = listOf(1_000))
        stopped.restored(at(5, wall = 9_999_999_999_999, boot = 99)) shouldBe stopped
    }

    "encoding round trips, and broken or old data is ignored" {
        val states = listOf(
            StopwatchState(),
            StopwatchState(accumulatedMillis = 1_234),
            StopwatchState(5, StopwatchState.Run(-20, 1_761_400_000_000, null), listOf(1, 2, 3)),
            StopwatchState(0, StopwatchState.Run(1_000, 2_000, 42), listOf()),
        )
        for (state in states) {
            StopwatchState.decode(state.encode()) shouldBe state
        }
        for (bad in listOf(null, "", "garbage", "2;0;;;;", "1;x;;;;", "1;0;1;;;", "1;0;;;;1,a", "1;-5;;;;")) {
            StopwatchState.decode(bad).shouldBeNull()
        }
    }

    "a broken saved state starts a fresh stopwatch" {
        val controller = StopwatchController(MemoryStore("not a state"), FakeClock())
        controller.state.value shouldBe StopwatchState()
    }

    "only changes are saved and reported" {
        val store = MemoryStore()
        val changes = mutableListOf<StopwatchState>()
        val controller = StopwatchController(store, FakeClock()) { state, _ -> changes += state }
        controller.perform(StopwatchAction.ELAPSED)
        controller.perform(StopwatchAction.RESET)
        store.saved.shouldBeNull()
        changes shouldBe listOf()

        controller.perform(StopwatchAction.START)
        changes.size shouldBe 1
        StopwatchState.decode(store.saved) shouldBe controller.state.value
    }

    "the display shows minutes, seconds and tenths, and hours when needed" {
        formatStopwatch(0) shouldBe "0:00.0"
        formatStopwatch(5_390) shouldBe "0:05.3"
        formatStopwatch(59_999) shouldBe "0:59.9"
        formatStopwatch(754_500) shouldBe "12:34.5"
        formatStopwatch(3_723_400) shouldBe "1:02:03.4"
        formatStopwatch(36_000_000 + 59_000) shouldBe "10:00:59.0"
        formatStopwatch(-50) shouldBe "0:00.0"
        formatStopwatch(1_500, ',') shouldBe "0:01,5"
    }

    "the notification counts from the start and is gone once reset" {
        StopwatchNotificationContent.of(StopwatchState(), at(0)).shouldBeNull()

        val running = StopwatchState().perform(StopwatchAction.START, at(1_000)).first
            .perform(StopwatchAction.LAP, at(3_000)).first
        val now = at(11_000)
        StopwatchNotificationContent.of(running, now) shouldBe StopwatchNotificationContent(
            running = true,
            elapsedMillis = 10_000,
            chronometerBase = now.wallMillis - 10_000,
            lastLap = Pair(1, 2_000L),
        )

        val stopped = running.perform(StopwatchAction.STOP, now).first
        StopwatchNotificationContent.of(stopped, at(50_000)).shouldNotBeNull().let {
            it.running shouldBe false
            it.elapsedMillis shouldBe 10_000
        }
    }

    "notification buttons map to actions" {
        StopwatchNotification.actionFor(StopwatchNotification.ACTION_STOP) shouldBe StopwatchAction.STOP
        StopwatchNotification.actionFor(StopwatchNotification.ACTION_RESUME) shouldBe StopwatchAction.RESUME
        StopwatchNotification.actionFor(StopwatchNotification.ACTION_RESET) shouldBe StopwatchAction.RESET
        StopwatchNotification.actionFor("android.intent.action.BOOT_COMPLETED").shouldBeNull()
        StopwatchNotification.actionFor(null).shouldBeNull()
    }

    "the tool's resolved action becomes the skill's input" {
        val skill = StopwatchSkill(StopwatchInfo, null) { throw AssertionError("not needed") }
        for (action in StopwatchAction.entries) {
            skill.inputFromSlots(mapOf("action" to action.slotValue)) shouldBe action
        }
        shouldThrow<IllegalArgumentException> { skill.inputFromSlots(mapOf("action" to "explode")) }
        shouldThrow<IllegalArgumentException> { skill.inputFromSlots(mapOf()) }
    }

    "without sentences in the language, Dicio's matcher never picks the stopwatch" {
        val skill = StopwatchSkill(StopwatchInfo, null) { throw AssertionError("not needed") }
        skill.score(FakeSkillContext(), "start the stopwatch").first shouldBeSameInstanceAs
                AlwaysWorstScore
    }

    "every English sentence maps to its action" {
        val skill = StopwatchSkill(StopwatchInfo, Sentences.Stopwatch["en"]!!) {
            throw AssertionError("not needed")
        }
        val inputs = mapOf(
            "start the stopwatch" to StopwatchAction.START,
            "stop the stopwatch" to StopwatchAction.STOP,
            "resume the stopwatch" to StopwatchAction.RESUME,
            "reset the stopwatch" to StopwatchAction.RESET,
            "mark a lap" to StopwatchAction.LAP,
            "how long has the stopwatch been running" to StopwatchAction.ELAPSED,
        )
        for ((input, action) in inputs) {
            withClue(input) {
                val (score, matched) = skill.score(FakeSkillContext().forInput(input), input)
                matched shouldBe action
                (score.scoreIn01Range() > 0.85f) shouldBe true
            }
        }
    }
})
