package org.stypox.dicio.gar.stt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.sin

class GarMicSpectrumTest : StringSpec({
    fun sine(hz: Float, amplitude: Float, n: Int = 512) =
        FloatArray(n) { (amplitude * sin(2 * PI * hz * it / 16_000)).toFloat() }

    "silence is zero" {
        val out = FloatArray(5) { 1f }
        GarMicSpectrum().bands(FloatArray(512), 512, out)
        out.toList() shouldBe List(5) { 0f }
    }

    "a tone lands in its band at about its level" {
        val spectrum = GarMicSpectrum()
        val out = FloatArray(5)
        val centers = floatArrayOf(180f, 500f, 1_000f, 2_200f, 4_400f)
        for ((band, hz) in centers.withIndex()) {
            spectrum.bands(sine(hz, 0.3f), 512, out)
            out.indices.maxBy { out[it] } shouldBe band
            // 0.3 amplitude is 0.212 RMS, -13.5 dBFS: (60 - 13.5) / 50
            out[band] shouldBe (0.93f plusOrMinus 0.04f)
        }
    }

    "short and odd frames are padded" {
        val out = FloatArray(5)
        GarMicSpectrum().bands(sine(1_000f, 0.3f, 400), 400, out)
        out.indices.maxBy { out[it] } shouldBe 2
        GarMicSpectrum().bands(FloatArray(1), 1, out)
        out.toList() shouldBe List(5) { 0f }
    }

    "the level flow publishes and clears" {
        GarMicLevel.publish(0.5f, sine(1_000f, 0.3f), 512, voice = true)
        val frame = GarMicLevel.frame.value
        frame.rms shouldBe 0.5f
        frame.voice shouldBe true
        (frame.bands[2] > 0.5f) shouldBe true // 0.6 of the way up after one frame
        GarMicLevel.clear()
        GarMicLevel.frame.value shouldBe GarMicLevel.Frame.SILENT
    }
})
