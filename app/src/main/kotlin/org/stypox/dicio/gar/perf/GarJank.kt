package org.stypox.dicio.gar.perf

import android.util.Log
import android.view.Display
import android.view.Window
import androidx.metrics.performance.FrameData
import androidx.metrics.performance.FrameDataApi24
import androidx.metrics.performance.FrameDataApi31
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import java.util.Locale
import kotlin.math.roundToInt
import org.stypox.dicio.BuildConfig

/**
 * Frame times of GAR's own windows with JankStats (gar/DESIGN.md, "Frame rate"): the home screen
 * and the assistant sheet. Keeps the last [RING_FRAMES] frames for the voice log ([summary]) and
 * writes a `GarJank:` line to logcat when tracking stops and, in debug builds or with the tag's
 * DEBUG log level, every [LOG_EVERY_FRAMES] frames, plus a debug line for each frame over three
 * frame budgets with the states it was drawn in ([state]). A frame is janky when it took over
 * twice its deadline ([GarFrameStats.isJanky]).
 *
 * JankStats starts once the window exists, so it never sees a stall before the first frame: the
 * startup is measured with `am start -W` and a trace (gar/tools/jank.py), not here.
 */
object GarJank {
    private const val TAG = "GarJank"
    const val RING_FRAMES = 4096
    const val LOG_EVERY_FRAMES = 120
    /** A frame this many budgets long gets its own debug line. */
    private const val SLOW_FRAME_BUDGETS = 3

    private val ring = GarFrameStats.Ring(RING_FRAMES)

    /**
     * Starts JankStats on [window], whose frames are counted as [surface] ("home" or "sheet").
     * Main thread; close the result (main thread) when the window goes away. Never throws: if
     * JankStats can't start, the result does nothing.
     */
    fun track(window: Window, surface: String): AutoCloseable = try {
        Tracker(window, surface)
    } catch (t: Throwable) {
        Log.w(TAG, "Could not track the frames of $surface", t)
        AutoCloseable {}
    }

    /**
     * Tags the next frames of [window] with [key] = [value] (e.g. "mic" = "listening"), or removes
     * the tag when [value] is null. Main thread; does nothing while the window isn't tracked.
     */
    fun state(window: Window, key: String, value: String?) {
        val decor = window.peekDecorView() ?: return
        val state = PerformanceMetricsState.getHolderForHierarchy(decor).state ?: return
        if (value == null) {
            state.removeState(key)
        } else {
            state.putState(key, value)
        }
    }

    /**
     * The frames that started in [fromNanos, toNanos] (System.nanoTime) in any tracked window,
     * or null if there were none (or they are no longer kept). Any thread.
     */
    fun summary(fromNanos: Long, toNanos: Long): GarFrameSummary? =
        GarFrameStats.summarize(ring.between(fromNanos, toNanos))

    /** The `GarJank:` logcat line that gar/tools/jank.py reads. */
    internal fun line(what: String, summary: GarFrameSummary): String = String.format(
        Locale.ROOT,
        "GarJank: %s surface=%s frames=%d janky=%d p50_ms=%.1f p90_ms=%.1f p99_ms=%.1f " +
            "max_ms=%.1f hz=%d",
        what, summary.surface, summary.frames, summary.janky, summary.p50Ms, summary.p90Ms,
        summary.p99Ms, summary.maxMs, summary.refreshHz,
    )

    private class Tracker(window: Window, private val surface: String) :
        AutoCloseable, JankStats.OnFrameListener {

        // JankStats needs the decor view; this creates it if setContent hasn't yet
        private val decor = window.decorView
        @Volatile
        private var display: Display? = null
        private var sinceLog = 0 // guarded by this
        private var closed = false // guarded by this
        private val jankStats: JankStats = JankStats.createAndTrack(window, this)
        // `adb shell setprop log.tag.GarJank DEBUG` turns them on in other builds
        private val logWindows = BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG)

        init {
            // the display is known once the window is attached; posted runnables wait for that
            decor.post { display = decor.display }
        }

        override fun onFrame(volatileFrameData: FrameData) {
            // JankStats' own thread; the data is reused for the next frame, copy what we need
            val hz = display?.refreshRate?.roundToInt() ?: 0
            val duration = when (volatileFrameData) {
                is FrameDataApi31 -> volatileFrameData.frameDurationTotalNanos
                is FrameDataApi24 -> volatileFrameData.frameDurationCpuNanos
                else -> volatileFrameData.frameDurationUiNanos
            }
            // janky by the same duration as the percentiles, not JankStats' isJank (which uses
            // another duration); API 31+ knows the frame's deadline through its overrun
            val deadline = if (volatileFrameData is FrameDataApi31) {
                duration - volatileFrameData.frameOverrunNanos
            } else {
                0L
            }
            ring.add(
                GarFrameStats.Frame(
                    startNanos = volatileFrameData.frameStartNanos,
                    durationNanos = duration,
                    janky = GarFrameStats.isJanky(duration, deadline, hz),
                    surface = surface,
                    refreshHz = hz,
                )
            )
            if (duration > SLOW_FRAME_BUDGETS * GarFrameStats.budgetNanos(hz)) {
                Log.d(TAG, String.format(
                    Locale.ROOT, "GarJank: slow surface=%s ms=%.1f states=%s", surface,
                    duration / 1e6,
                    volatileFrameData.states.joinToString(",") { "${it.key}:${it.value}" },
                ))
            }
            val logNow = synchronized(this) {
                if (closed) {
                    return
                }
                sinceLog += 1
                (sinceLog >= LOG_EVERY_FRAMES).also { if (it) sinceLog = 0 }
            }
            // a window line about every second at 120 Hz is for debugging only
            if (logNow && logWindows) {
                logLast(LOG_EVERY_FRAMES, "window")
            }
        }

        override fun close() {
            val count = synchronized(this) {
                if (closed) {
                    return
                }
                closed = true
                sinceLog.also { sinceLog = 0 }
            }
            try {
                jankStats.isTrackingEnabled = false
            } catch (t: Throwable) {
                Log.w(TAG, "Could not stop tracking $surface", t)
            }
            if (count > 0) {
                logLast(count, "stop")
            }
        }

        private fun logLast(count: Int, what: String) {
            GarFrameStats.summarize(ring.last(surface, count))?.let { Log.i(TAG, line(what, it)) }
        }
    }
}
