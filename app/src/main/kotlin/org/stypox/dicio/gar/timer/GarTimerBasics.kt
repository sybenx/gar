package org.stypox.dicio.gar.timer

import kotlinx.serialization.Serializable
import org.stypox.dicio.skills.stopwatch.ClockReading
import org.stypox.dicio.skills.stopwatch.StopwatchState
import kotlin.math.abs

/**
 * A moment by both of Android's clocks: [elapsed] (elapsedRealtime, which wall clock changes don't
 * move, but which restarts at every boot) in boot [boot], and [wall] (the wall clock, which
 * survives a reboot). Used by the timers GAR started in the clock app ([GarStartedTimers]) and by
 * the media sleep timer.
 */
@Serializable
data class Moment(val elapsed: Long, val wall: Long, val boot: Int? = null) {

    /**
     * Milliseconds from [now] to this moment, negative once it has passed: by elapsedRealtime in
     * the same boot, by the wall clock across a reboot.
     */
    fun millisFrom(now: ClockReading): Long =
        if (isSameBoot(now)) elapsed - now.elapsedRealtime else wall - now.wallMillis

    /**
     * Whether [now] is in the boot of this moment. The boot count tells for sure; without it, a
     * reboot shows as a jump of the boot time that elapsedRealtime and the wall clock imply.
     */
    fun isSameBoot(now: ClockReading): Boolean {
        if (boot != null && now.bootCount != null) {
            return boot == now.bootCount
        }
        val bootThen = wall - elapsed
        val bootNow = now.wallMillis - now.elapsedRealtime
        return abs(bootNow - bootThen) <= StopwatchState.REBOOT_TOLERANCE_MILLIS
    }

    companion object {
        /** The moment [millis] after [now] (before it, if negative). */
        fun after(now: ClockReading, millis: Long) =
            Moment(now.elapsedRealtime + millis, now.wallMillis + millis, now.bootCount)
    }
}

/** Where a small piece of state is kept between processes (SharedPreferences on the phone). */
interface GarTimerStore {
    fun load(): String?
    fun save(encoded: String)
}

/** Wakes the app at a time (an alarm on the phone). */
interface GarTimerScheduler {
    /** Wakes the app at [elapsedRealtime] (`SystemClock.elapsedRealtime`), instead of before. */
    fun schedule(elapsedRealtime: Long)

    /** Nothing to wake the app for. */
    fun cancel()
}

fun interface GarTimerClock {
    fun now(): ClockReading
}
