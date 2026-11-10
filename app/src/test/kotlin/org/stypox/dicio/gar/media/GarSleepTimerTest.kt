package org.stypox.dicio.gar.media

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.timer.GarTimerClock
import org.stypox.dicio.gar.timer.GarTimerScheduler
import org.stypox.dicio.gar.timer.GarTimerStore
import org.stypox.dicio.skills.stopwatch.ClockReading

/** A sleep timer on a clock, a store and an alarm the test controls. */
private class SleepTimerRig {
    var elapsed = 1_000_000L
    var wall = 1_700_000_000_000L
    var boot: Int? = 7
    var saved: String? = null
    val alarms = mutableListOf<Long?>() // null: cancelled
    var pauses = 0

    fun timer() = GarSleepTimer(
        store = object : GarTimerStore {
            override fun load(): String? = saved
            override fun save(encoded: String) {
                saved = encoded
            }
        },
        clock = GarTimerClock { ClockReading(elapsed, wall, boot) },
        scheduler = object : GarTimerScheduler {
            override fun schedule(elapsedRealtime: Long) {
                alarms += elapsedRealtime
            }

            override fun cancel() {
                alarms += null
            }
        },
        pauseMedia = { pauses++ },
    )

    fun pass(millis: Long) {
        elapsed += millis
        wall += millis
    }
}

class GarSleepTimerTest : StringSpec({
    "a sleep timer ends after its time, with an exact alarm at its end" {
        val rig = SleepTimerRig()
        val timer = rig.timer()
        timer.remainingMillis().shouldBeNull()
        timer.set(30 * 60_000L).elapsed shouldBe 1_000_000L + 1_800_000L
        rig.alarms shouldContainExactly listOf(2_800_000L)
        rig.pass(60_000)
        timer.remainingMillis() shouldBe 1_740_000L
    }

    "its alarm pauses the media and ends it; an early alarm sets it again" {
        val rig = SleepTimerRig()
        val timer = rig.timer()
        timer.set(600_000)
        rig.pass(300_000)
        timer.onAlarm()
        rig.pauses shouldBe 0
        rig.alarms shouldContainExactly listOf(1_600_000L, 1_600_000L)
        rig.pass(300_000)
        timer.onAlarm()
        rig.pauses shouldBe 1
        timer.remainingMillis().shouldBeNull()
        // a second alarm finds nothing
        timer.onAlarm()
        rig.pauses shouldBe 1
    }

    "cancel ends it without pausing, and says whether one ran" {
        val rig = SleepTimerRig()
        val timer = rig.timer()
        timer.cancel().shouldBeFalse()
        timer.set(600_000)
        timer.cancel().shouldBeTrue()
        rig.alarms.last().shouldBeNull()
        timer.remainingMillis().shouldBeNull()
        rig.pauses shouldBe 0
        timer.cancel().shouldBeFalse()
    }

    "a new sleep timer replaces the one that ran" {
        val rig = SleepTimerRig()
        val timer = rig.timer()
        timer.set(600_000)
        timer.set(3_600_000)
        timer.remainingMillis() shouldBe 3_600_000L
        rig.alarms.last() shouldBe 4_600_000L
    }

    "a new process knows it from the store" {
        val rig = SleepTimerRig()
        rig.timer().set(600_000)
        rig.pass(100_000)
        rig.timer().remainingMillis() shouldBe 500_000L
    }

    "a reboot ends it: its alarm and the music are gone" {
        val rig = SleepTimerRig()
        val timer = rig.timer()
        timer.set(3_600_000)
        rig.boot = 8
        rig.elapsed = 10_000
        timer.remainingMillis().shouldBeNull()
        timer.onAlarm()
        rig.pauses shouldBe 0
    }

    "refresh sets the alarm again, ends a timer that just ended, and drops a stale one" {
        val rig = SleepTimerRig()
        rig.timer().set(600_000)
        val reloaded = rig.timer()
        reloaded.refresh()
        rig.alarms.last() shouldBe 1_600_000L

        // its alarm was lost (a force stop) a moment ago: pause now
        rig.pass(610_000)
        reloaded.refresh()
        rig.pauses shouldBe 1
        reloaded.remainingMillis().shouldBeNull()

        // lost an hour ago: what plays now isn't what it was for
        val stale = SleepTimerRig()
        stale.timer().set(600_000)
        stale.pass(3_600_000)
        stale.timer().refresh()
        stale.pauses shouldBe 0
        stale.timer().remainingMillis().shouldBeNull()
    }

    "a broken store is no timer" {
        GarSleepTimer.decode("{broken").shouldBeNull()
        GarSleepTimer.decode("").shouldBeNull()
        GarSleepTimer.decode(null).shouldBeNull()
        val rig = SleepTimerRig()
        rig.timer().set(60_000)
        GarSleepTimer.decode(rig.saved).shouldNotBeNull().boot shouldBe 7
    }
})
