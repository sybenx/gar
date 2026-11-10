package org.stypox.dicio.gar.perf

import android.os.Process
import android.os.SystemClock
import android.os.Trace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gives matching a command's sentences the CPU before background work (gar/DESIGN.md, "Frame
 * rate", "Routing speed"). A sentence match takes 30-90 ms of CPU once the app is warm, but on
 * the phone it took 2-19 s while the model loaded and warmed up and the starter chips read the
 * voice log: the ranker's thread waited for a core. So:
 * - [ranking] runs Dicio's ranker at [MATCH_PRIORITY] (the thread's own priority comes back
 *   after), inside the trace section "Gar:rank", and the voice log gets how long it took;
 * - the model's load and warm-up run at a lower priority (`LlamaEngine.background`) and wait
 *   for a running match before they start ([awaitNoMatch]).
 */
object GarMatchPriority {
    /** Above Dispatchers.Default's normal priority, below the UI thread's. */
    const val MATCH_PRIORITY = Process.THREAD_PRIORITY_DISPLAY

    /**
     * The model's engine thread while loading and warming up: lower than normal, but not
     * [Process.THREAD_PRIORITY_BACKGROUND], which would also move it to the little cores.
     */
    const val MODEL_LOAD_PRIORITY =
        Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE

    /** How long a model load waits for a running match at most. */
    const val MATCH_WAIT_MILLIS = 2_000L

    private val matching = MutableStateFlow(0)

    /** Whether the model is loading or warming up now, for the voice log. */
    @Volatile
    var modelLoading = false

    /**
     * How long one [ranking] took: wall and thread CPU time in ms (CPU null where it can't be
     * read), and whether the model was loading meanwhile. Wall far above CPU means the thread
     * waited for a core.
     */
    data class Timing(val wallMillis: Long, val cpuMillis: Long?, val modelLoading: Boolean)

    /** Runs [block] (Dicio's ranker, which doesn't suspend) as described above. */
    inline fun <T> ranking(onDone: (Timing) -> Unit, block: () -> T): T {
        val wall = System.nanoTime()
        val cpu = threadCpuMillis()
        val loading = modelLoading
        val previous = raise()
        try {
            return block()
        } finally {
            restore(previous)
            val cpuEnd = threadCpuMillis()
            onDone(
                Timing(
                    (System.nanoTime() - wall) / 1_000_000,
                    if (cpu != null && cpuEnd != null) cpuEnd - cpu else null,
                    loading || modelLoading,
                )
            )
        }
    }

    /** This thread's CPU time, or null where it can't be read (JVM tests). */
    fun threadCpuMillis(): Long? = try {
        SystemClock.currentThreadTimeMillis()
    } catch (t: Throwable) {
        null
    }

    /**
     * Marks a match as running, raises this thread and opens the trace section "Gar:rank";
     * returns the thread's priority before, if it was read.
     */
    fun raise(): Int? {
        matching.update { it + 1 }
        try {
            Trace.beginSection("Gar:rank")
        } catch (t: Throwable) {
            // no tracing in JVM tests
        }
        return try {
            val tid = Process.myTid()
            Process.getThreadPriority(tid).also { Process.setThreadPriority(tid, MATCH_PRIORITY) }
        } catch (t: Throwable) {
            null // a priority is a hint (and JVM tests have no android.os.Process)
        }
    }

    /** Ends [raise]: this thread gets [previous] back. */
    fun restore(previous: Int?) {
        try {
            Trace.endSection()
        } catch (t: Throwable) {
            // no tracing in JVM tests
        }
        try {
            if (previous != null) {
                Process.setThreadPriority(Process.myTid(), previous)
            }
        } catch (t: Throwable) {
            // a priority is a hint
        } finally {
            matching.update { it - 1 }
        }
    }

    /** Waits until no match runs, [maxMillis] at most. */
    suspend fun awaitNoMatch(maxMillis: Long = MATCH_WAIT_MILLIS) {
        withTimeoutOrNull(maxMillis) { matching.first { it <= 0 } }
    }

    /** Sets the calling thread's priority for the model's engine (`LlamaConfig.threadPriority`). */
    fun applyModelPriority(background: Boolean) {
        Process.setThreadPriority(
            if (background) MODEL_LOAD_PRIORITY else Process.THREAD_PRIORITY_DEFAULT
        )
    }
}
