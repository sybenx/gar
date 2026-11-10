package org.stypox.dicio.skills.stopwatch

import kotlin.math.abs

/** What the user asked the stopwatch to do; [slotValue] is the value of the `stopwatch` tool's enum. */
enum class StopwatchAction(val slotValue: String) {
    START("start"),
    STOP("stop"),
    RESUME("resume"),
    RESET("reset"),
    LAP("lap"),
    ELAPSED("elapsed");

    companion object {
        fun fromSlotValue(value: String?): StopwatchAction? = entries.firstOrNull { it.slotValue == value }
    }
}

/**
 * The clocks the stopwatch reads at one moment. [elapsedRealtime] keeps counting in deep sleep but
 * restarts from zero at every boot, so [wallMillis] and [bootCount] are kept too.
 *
 * @param bootCount `Settings.Global.BOOT_COUNT`, or null if unknown
 */
data class ClockReading(
    val elapsedRealtime: Long,
    val wallMillis: Long,
    val bootCount: Int?,
)

/** What an action did, for the answer. Times are the stopwatch's total in milliseconds. */
sealed interface StopwatchEvent {
    data object Started : StopwatchEvent
    data class Resumed(val millis: Long) : StopwatchEvent
    data class AlreadyRunning(val millis: Long) : StopwatchEvent
    data class Stopped(val millis: Long) : StopwatchEvent

    /** Stop, lap or elapsed while not running; [millis] is 0 if it was never started. */
    data class NotRunning(val millis: Long) : StopwatchEvent
    data object Reset : StopwatchEvent
    data object AlreadyReset : StopwatchEvent

    /** @param number 1 for the first lap */
    data class Lap(val number: Int, val lapMillis: Long, val totalMillis: Long) : StopwatchEvent
    data class Elapsed(val millis: Long, val running: Boolean) : StopwatchEvent
}

/**
 * The stopwatch: time accumulated by earlier runs, the current run if it is running, and the laps.
 * Immutable; every action returns the new state. Only plain numbers are kept, so the state can be
 * saved and survives process death (see [encode]), and a reboot (see [runMillis]).
 *
 * @param accumulatedMillis the time of the runs before the current one
 * @param run the current run, or null while stopped
 * @param lapTotals the stopwatch's total at each lap, in order
 */
data class StopwatchState(
    val accumulatedMillis: Long = 0,
    val run: Run? = null,
    val lapTotals: List<Long> = listOf(),
) {
    /** When the current run started, by each clock in [ClockReading]. */
    data class Run(
        val startElapsed: Long,
        val startWall: Long,
        val bootCount: Int?,
    )

    val isRunning: Boolean get() = run != null

    /** Never started, or reset since. */
    val isReset: Boolean get() = run == null && accumulatedMillis == 0L && lapTotals.isEmpty()

    /** The stopwatch's total at [now]. */
    fun elapsedMillis(now: ClockReading): Long =
        accumulatedMillis + (run?.let { runMillis(it, now) } ?: 0L)

    /** The duration of each lap, from the previous lap (or the start) to its own. */
    fun lapDurations(): List<Long> =
        lapTotals.mapIndexed { i, total -> total - (lapTotals.getOrNull(i - 1) ?: 0L) }

    /** Applies [action] at [now]. */
    fun perform(action: StopwatchAction, now: ClockReading): Pair<StopwatchState, StopwatchEvent> {
        val elapsed = elapsedMillis(now)
        return when (action) {
            StopwatchAction.START, StopwatchAction.RESUME -> when {
                // "start" on a stopped stopwatch continues it: starting over would lose its time,
                // and "reset" does that on request
                isRunning -> Pair(this, StopwatchEvent.AlreadyRunning(elapsed))
                elapsed == 0L -> Pair(startRun(now), StopwatchEvent.Started)
                else -> Pair(startRun(now), StopwatchEvent.Resumed(elapsed))
            }

            StopwatchAction.STOP -> if (isRunning) {
                Pair(copy(accumulatedMillis = elapsed, run = null), StopwatchEvent.Stopped(elapsed))
            } else {
                Pair(this, StopwatchEvent.NotRunning(elapsed))
            }

            StopwatchAction.RESET -> if (isReset) {
                Pair(this, StopwatchEvent.AlreadyReset)
            } else {
                Pair(StopwatchState(), StopwatchEvent.Reset)
            }

            StopwatchAction.LAP -> if (isRunning) {
                val lapMillis = elapsed - (lapTotals.lastOrNull() ?: 0L)
                Pair(
                    copy(lapTotals = lapTotals + elapsed),
                    StopwatchEvent.Lap(lapTotals.size + 1, lapMillis, elapsed),
                )
            } else {
                Pair(this, StopwatchEvent.NotRunning(elapsed))
            }

            StopwatchAction.ELAPSED -> if (isRunning || elapsed > 0) {
                Pair(this, StopwatchEvent.Elapsed(elapsed, isRunning))
            } else {
                Pair(this, StopwatchEvent.NotRunning(0))
            }
        }
    }

    /**
     * The same stopwatch with its run measured from this boot's clocks, for a state read back
     * after a reboot. Unchanged otherwise.
     */
    fun restored(now: ClockReading): StopwatchState {
        val run = run ?: return this
        if (isSameBoot(run, now)) {
            return this
        }
        val millis = runMillis(run, now)
        return copy(run = Run(now.elapsedRealtime - millis, now.wallMillis - millis, now.bootCount))
    }

    private fun startRun(now: ClockReading) =
        copy(run = Run(now.elapsedRealtime, now.wallMillis, now.bootCount))

    /** Encodes the state in one line (see [decode]). */
    fun encode(): String = listOf(
        FORMAT_VERSION,
        accumulatedMillis,
        run?.startElapsed ?: "",
        run?.startWall ?: "",
        run?.bootCount ?: "",
        lapTotals.joinToString(","),
    ).joinToString(";")

    companion object {
        private const val FORMAT_VERSION = "1"

        /**
         * How far the boot time implied by elapsedRealtime and the wall clock may move before the
         * device counts as rebooted, when the boot count is unknown. Covers network time updates.
         */
        const val REBOOT_TOLERANCE_MILLIS = 10_000L

        /** The state [encode]d into [text], or null if [text] isn't one. */
        fun decode(text: String?): StopwatchState? {
            val parts = text?.split(";") ?: return null
            if (parts.size != 6 || parts[0] != FORMAT_VERSION) {
                return null
            }
            val accumulated = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val run = if (parts[2].isEmpty()) {
                null
            } else {
                Run(
                    startElapsed = parts[2].toLongOrNull() ?: return null,
                    startWall = parts[3].toLongOrNull() ?: return null,
                    bootCount = parts[4].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return null },
                )
            }
            val laps = if (parts[5].isEmpty()) {
                listOf()
            } else {
                parts[5].split(",").map { it.toLongOrNull() ?: return null }
            }
            return StopwatchState(accumulated, run, laps)
        }

        /**
         * Whether [now] is in the same boot as the start of [run]. The boot count tells for sure;
         * without it, a reboot shows as elapsedRealtime going back, or as a jump of the boot time
         * that elapsedRealtime and the wall clock imply.
         */
        fun isSameBoot(run: Run, now: ClockReading): Boolean {
            if (run.bootCount != null && now.bootCount != null) {
                return run.bootCount == now.bootCount
            }
            if (now.elapsedRealtime < run.startElapsed) {
                return false
            }
            val bootAtStart = run.startWall - run.startElapsed
            val bootNow = now.wallMillis - now.elapsedRealtime
            return abs(bootNow - bootAtStart) <= REBOOT_TOLERANCE_MILLIS
        }

        /**
         * How long [run] has been running at [now]: by elapsedRealtime in the same boot (immune to
         * wall clock changes), by the wall clock across a reboot.
         */
        fun runMillis(run: Run, now: ClockReading): Long {
            val millis = if (isSameBoot(run, now)) {
                now.elapsedRealtime - run.startElapsed
            } else {
                now.wallMillis - run.startWall
            }
            return millis.coerceAtLeast(0)
        }
    }
}

/**
 * The stopwatch's display: "0:05.3", "12:34.5", "1:02:03.4", with tenths of a second rounded down
 * (like a stopwatch app, it never shows a time not yet reached).
 */
fun formatStopwatch(millis: Long, decimalSeparator: Char = '.'): String {
    val clamped = millis.coerceAtLeast(0)
    val tenths = (clamped / 100) % 10
    val totalSeconds = clamped / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(java.util.Locale.ROOT, "%d:%02d:%02d%c%d", hours, minutes, seconds, decimalSeparator, tenths)
    } else {
        String.format(java.util.Locale.ROOT, "%d:%02d%c%d", minutes, seconds, decimalSeparator, tenths)
    }
}
