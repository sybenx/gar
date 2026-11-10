package org.stypox.dicio.gar.perf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class GarFrameStatsTest : StringSpec({
    "percentiles by nearest rank" {
        val values = DoubleArray(100) { (it + 1).toDouble() } // 1..100, shuffled below
        values.shuffle(kotlin.random.Random(7))
        GarFrameStats.percentiles(values, 0.5, 0.9, 0.99, 1.0, 0.0).toList() shouldBe
            listOf(50.0, 90.0, 99.0, 100.0, 1.0)
    }

    "percentiles of few values and of none" {
        GarFrameStats.percentiles(doubleArrayOf(16.0), 0.5, 0.99).toList() shouldBe
            listOf(16.0, 16.0)
        GarFrameStats.percentiles(doubleArrayOf(4.0, 8.0, 30.0), 0.5, 0.9).toList() shouldBe
            listOf(8.0, 30.0)
        GarFrameStats.percentiles(doubleArrayOf(), 0.5).single().isNaN() shouldBe true
    }

    "a summary counts janky frames and keeps the slowest" {
        val frames = (1..10).map { i ->
            frame(start = i * 10L, ms = if (i == 10) 120.0 else 8.0, janky = i == 10)
        }
        val summary = GarFrameStats.summarize(frames).shouldNotBeNull()
        summary.frames shouldBe 10
        summary.janky shouldBe 1
        summary.p50Ms shouldBe 8.0
        summary.p90Ms shouldBe 8.0
        summary.p99Ms shouldBe 120.0
        summary.maxMs shouldBe 120.0
        summary.refreshHz shouldBe 120
        summary.surface shouldBe "home"
        GarFrameStats.summarize(emptyList()).shouldBeNull()
    }

    "janky means over twice the deadline, or the budget when the deadline is unknown" {
        val ms = 1_000_000L
        GarFrameStats.isJanky(20 * ms, 8 * ms, 120) shouldBe true
        GarFrameStats.isJanky(16 * ms, 8 * ms, 120) shouldBe false
        // a 600 ms frame at 60 Hz is janky whatever JankStats says
        GarFrameStats.isJanky(600 * ms, 0, 60) shouldBe true
        GarFrameStats.isJanky(30 * ms, 0, 60) shouldBe false
        GarFrameStats.isJanky(34 * ms, -5 * ms, 0) shouldBe true
    }

    "two windows' frames name both surfaces, the highest refresh rate counts" {
        val summary = GarFrameStats.summarize(listOf(
            frame(1, 8.0, surface = "home", hz = 60),
            frame(2, 8.0, surface = "sheet", hz = 120),
            frame(3, 8.0, surface = "home", hz = 60),
        )).shouldNotBeNull()
        summary.surface shouldBe "home+sheet"
        summary.refreshHz shouldBe 120
    }

    "the ring keeps the newest frames and finds a time span" {
        val ring = GarFrameStats.Ring(4)
        (1..6).forEach { ring.add(frame(start = it.toLong(), ms = it.toDouble())) }
        ring.between(0, 100).map { it.startNanos } shouldBe listOf(3L, 4L, 5L, 6L)
        ring.between(4, 5).map { it.startNanos } shouldBe listOf(4L, 5L)
        ring.between(7, 9) shouldBe emptyList()
    }

    "the ring's last frames of one surface" {
        val ring = GarFrameStats.Ring(8)
        ring.add(frame(1, 1.0, surface = "home"))
        ring.add(frame(2, 2.0, surface = "sheet"))
        ring.add(frame(3, 3.0, surface = "home"))
        ring.add(frame(4, 4.0, surface = "home"))
        ring.last("home", 2).map { it.startNanos } shouldBe listOf(3L, 4L)
        ring.last("sheet", 5).map { it.startNanos } shouldBe listOf(2L)
        ring.last("other", 5) shouldBe emptyList()
    }

    "the frame budget" {
        GarFrameStats.budgetNanos(120) shouldBe 8_333_333L
        GarFrameStats.budgetNanos(60) shouldBe 16_666_666L
        GarFrameStats.budgetNanos(0) shouldBe 16_666_666L // unknown: 60 Hz
    }

    "the logcat line jank.py reads" {
        val summary = GarFrameSummary("home", 120, 3, 8.04, 11.96, 33.3, 81.25, 120)
        GarJank.line("window", summary) shouldBe "GarJank: window surface=home frames=120 " +
            "janky=3 p50_ms=8.0 p90_ms=12.0 p99_ms=33.3 max_ms=81.3 hz=120"
    }
})

private fun frame(
    start: Long,
    ms: Double,
    janky: Boolean = false,
    surface: String = "home",
    hz: Int = 120,
) = GarFrameStats.Frame(
    startNanos = start,
    durationNanos = (ms * 1e6).toLong(),
    janky = janky,
    surface = surface,
    refreshHz = hz,
)
