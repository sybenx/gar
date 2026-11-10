package org.stypox.dicio.gar.perf

import kotlin.math.ceil

/**
 * The frames of a time span, as the voice log and logcat show them (gar/DESIGN.md, "Frame rate").
 *
 * @param surface the window the frames were drawn in ("home", "sheet"); several joined with "+"
 * @param janky frames longer than twice their deadline ([isJanky]), from the same durations as
 *  the percentiles
 * @param refreshHz the display's refresh rate while the frames were drawn (the highest seen)
 */
data class GarFrameSummary(
    val surface: String,
    val frames: Int,
    val janky: Int,
    val p50Ms: Double,
    val p90Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
    val refreshHz: Int,
)

/** Pure frame statistics for [GarJank], kept apart so they can be unit-tested. */
object GarFrameStats {
    /**
     * The [shares] (0..1) percentiles of [values] by nearest rank: the smallest value with at
     * least that share of the values at or below it. Empty input gives NaN for each share.
     */
    fun percentiles(values: DoubleArray, vararg shares: Double): DoubleArray {
        if (values.isEmpty()) {
            return DoubleArray(shares.size) { Double.NaN }
        }
        val sorted = values.sortedArray()
        return DoubleArray(shares.size) { i ->
            // the margin keeps a product like 0.9 * 10 from rounding up past its rank
            val rank = ceil(shares[i].coerceIn(0.0, 1.0) * sorted.size - 1e-9).toInt()
            sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
        }
    }

    /** The summary of [frames], or null if there are none. */
    fun summarize(frames: List<Frame>): GarFrameSummary? {
        if (frames.isEmpty()) {
            return null
        }
        val durations = DoubleArray(frames.size) { frames[it].durationNanos / 1e6 }
        val (p50, p90, p99) = percentiles(durations, 0.5, 0.9, 0.99)
        return GarFrameSummary(
            surface = frames.map { it.surface }.distinct().joinToString("+"),
            frames = frames.size,
            janky = frames.count { it.janky },
            p50Ms = p50,
            p90Ms = p90,
            p99Ms = p99,
            maxMs = durations.max(),
            refreshHz = frames.maxOf { it.refreshHz },
        )
    }

    /** The frame budget at [refreshHz] in nanoseconds (60 Hz when unknown). */
    fun budgetNanos(refreshHz: Int): Long = 1_000_000_000L / (if (refreshHz > 0) refreshHz else 60)

    /**
     * Whether a frame of [durationNanos] is janky: longer than twice its deadline, which is
     * [deadlineNanos] when known (positive) and the frame budget at [refreshHz] otherwise.
     */
    fun isJanky(durationNanos: Long, deadlineNanos: Long, refreshHz: Int): Boolean =
        durationNanos > 2 * (if (deadlineNanos > 0) deadlineNanos else budgetNanos(refreshHz))

    /** One frame: when it started (System.nanoTime) and how long it took. */
    data class Frame(
        val startNanos: Long,
        val durationNanos: Long,
        val janky: Boolean,
        val surface: String,
        val refreshHz: Int,
    )

    /**
     * The last [capacity] frames of every tracked window, oldest overwritten first. Thread safe:
     * JankStats reports from its own thread, the voice log reads from another.
     */
    class Ring(private val capacity: Int) {
        private val frames = arrayOfNulls<Frame>(capacity)
        private var next = 0
        private var size = 0

        @Synchronized
        fun add(frame: Frame) {
            frames[next] = frame
            next = (next + 1) % capacity
            if (size < capacity) {
                size += 1
            }
        }

        /** The frames that started in [fromNanos, toNanos], oldest first. */
        @Synchronized
        fun between(fromNanos: Long, toNanos: Long): List<Frame> {
            val out = ArrayList<Frame>()
            val first = (next - size + capacity) % capacity
            for (i in 0 until size) {
                val frame = frames[(first + i) % capacity] ?: continue
                if (frame.startNanos in fromNanos..toNanos) {
                    out.add(frame)
                }
            }
            return out
        }

        /** The newest [count] frames of [surface], oldest first. */
        @Synchronized
        fun last(surface: String, count: Int): List<Frame> {
            val out = ArrayList<Frame>()
            for (i in 1..size) {
                if (out.size >= count) {
                    break
                }
                val frame = frames[(next - i + capacity) % capacity] ?: continue
                if (frame.surface == surface) {
                    out.add(frame)
                }
            }
            out.reverse()
            return out
        }
    }
}
