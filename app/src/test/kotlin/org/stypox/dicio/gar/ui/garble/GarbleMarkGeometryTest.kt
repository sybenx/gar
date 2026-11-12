package org.stypox.dicio.gar.ui.garble

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.ui.GarMicState

class GarbleMarkGeometryTest : StringSpec({
    fun pose(
        state: GarbleMarkState,
        t: Long = 0,
        level: Float = 0f,
        bands: FloatArray? = null,
        speech: Float? = null,
        reduced: Boolean = false,
    ) = FloatArray(15).also {
        GarbleMarkGeometry.pose(state, t, level, bands, speech, reduced, it)
    }

    fun FloatArray.shouldMatch(expected: FloatArray) {
        size shouldBe expected.size
        for (i in indices) {
            this[i] shouldBe (expected[i] plusOrMinus 0.01f)
        }
    }

    "spec poses" {
        pose(GarbleMarkState.Logo).shouldMatch(
            floatArrayOf(54f, -28f, -16f, 84f, 16f, 18f, 116f, -5f, -4f, 146f, 0f, 0f, 92f, 0f, 0f)
        )
        // dots, each breathing a little on its own phase
        val dots = pose(GarbleMarkState.Listening)
        dots[0] shouldBe GarbleMarkGeometry.DOT
        for (i in 0 until 5) {
            (dots[i * 3] in GarbleMarkGeometry.DOT..GarbleMarkGeometry.DOT + GarbleMarkGeometry.BREATH_UNITS) shouldBe true
            dots[i * 3 + 1] shouldBe 0f
            dots[i * 3 + 2] shouldBe 0f
        }
        pose(GarbleMarkState.Thinking).shouldMatch(
            floatArrayOf(36f, 0f, -18f, 60f, 0f, -9f, 84f, 0f, 0f, 60f, 0f, 9f, 36f, 0f, 18f)
        )
        pose(GarbleMarkState.Speaking, speech = 1f).shouldMatch(
            floatArrayOf(48f, 0f, 0f, 98f, 0f, 0f, 148f, 0f, 0f, 98f, 0f, 0f, 48f, 0f, 0f)
        )
        pose(GarbleMarkState.Understood).shouldMatch(GarbleMarkGeometry.UPRIGHT)
    }

    "hearing follows the level and eases back to dots" {
        pose(GarbleMarkState.Hearing, level = 0f).shouldMatch(GarbleMarkGeometry.DOTS)
        // full level with mid bands is the spec's base scramble
        pose(GarbleMarkState.Hearing, level = 1f, bands = FloatArray(5) { 0.5f })
            .shouldMatch(GarbleMarkGeometry.HEARING)
        val half = pose(GarbleMarkState.Hearing, level = GarbleMarkGeometry.HEARING_FULL_LEVEL / 2)
        for (i in 0 until 5) {
            (half[i * 3] > GarbleMarkGeometry.DOT) shouldBe true
            (half[i * 3] < GarbleMarkGeometry.HEARING[i * 3]) shouldBe true
        }
        // one band per bar: a loud band makes only its bar taller
        val loud = pose(GarbleMarkState.Hearing, level = 1f, bands = floatArrayOf(0f, 0f, 1f, 0f, 0f))
        loud[2 * 3] shouldBe (46f * 1.3f plusOrMinus 0.01f)
        loud[0] shouldBe (62f * 0.7f plusOrMinus 0.01f)
    }

    "thinking turns, listening breathes, speaking pulses" {
        val quarter = (GarbleMarkGeometry.THINKING_PERIOD_MILLIS / 4).toLong()
        val turned = pose(GarbleMarkState.Thinking, t = quarter)
        turned[2 * 3] shouldBe (60f plusOrMinus 0.01f) // the crest moved off the middle bar
        turned[2] shouldBe (0f plusOrMinus 0.01f) // and the line is level
        val breath = pose(GarbleMarkState.Listening, t = (GarbleMarkGeometry.BREATH_PERIOD_MILLIS / 2).toLong())
        breath[0] shouldBe (GarbleMarkGeometry.DOT + GarbleMarkGeometry.BREATH_UNITS plusOrMinus 0.01f)
        val quiet = pose(GarbleMarkState.Speaking, speech = 0f)
        quiet[2 * 3] shouldBe (148f * GarbleMarkGeometry.SPEAKING_MIN_SHARE plusOrMinus 0.01f)
        for (t in 0L until 3000L step 37) {
            val p = pose(GarbleMarkState.Speaking, t = t)
            for (i in 0 until 5) {
                val h = p[i * 3]
                val base = GarbleMarkGeometry.UPRIGHT[i * 3]
                (h >= base * GarbleMarkGeometry.SPEAKING_MIN_SHARE - 0.01f && h <= base + 0.01f) shouldBe true
            }
        }
    }

    "reduced motion keeps heights only" {
        for (state in GarbleMarkState.entries - GarbleMarkState.Logo) {
            val p = pose(state, t = 400, level = 1f, reduced = true)
            for (i in 0 until 5) {
                p[i * 3 + 1] shouldBe 0f
                p[i * 3 + 2] shouldBe 0f
            }
        }
        val hearing = pose(GarbleMarkState.Hearing, level = 1f, bands = FloatArray(5) { 0.5f }, reduced = true)
        for (i in 0 until 5) {
            hearing[i * 3] shouldBe (GarbleMarkGeometry.HEARING[i * 3] plusOrMinus 0.01f)
        }
        pose(GarbleMarkState.Logo, reduced = true).shouldMatch(GarbleMarkGeometry.LOGO)
    }

    "bars are laid out symmetric and fit the box" {
        GarbleMarkGeometry.barCenterX(2) shouldBe 0f
        GarbleMarkGeometry.barCenterX(0) shouldBe -GarbleMarkGeometry.barCenterX(4)
        val width = GarbleMarkGeometry.barCenterX(4) - GarbleMarkGeometry.barCenterX(0) +
            GarbleMarkGeometry.BAR_WIDTH
        width shouldBe (148f plusOrMinus 0.01f)
        (width <= GarbleMarkGeometry.BOX) shouldBe true
        (GarbleMarkGeometry.UPRIGHT[2 * 3] <= GarbleMarkGeometry.BOX) shouldBe true
    }

    "transitions interpolate between poses" {
        val out = FloatArray(15)
        GarbleMarkGeometry.lerp(GarbleMarkGeometry.DOTS, GarbleMarkGeometry.UPRIGHT, 0f, out)
        out.shouldMatch(GarbleMarkGeometry.DOTS)
        GarbleMarkGeometry.lerp(GarbleMarkGeometry.DOTS, GarbleMarkGeometry.UPRIGHT, 1f, out)
        out.shouldMatch(GarbleMarkGeometry.UPRIGHT)
        GarbleMarkGeometry.lerp(GarbleMarkGeometry.HEARING, GarbleMarkGeometry.UPRIGHT, 0.5f, out)
        out[1] shouldBe (-14f plusOrMinus 0.01f)
        // a spring's undershoot never makes a bar vanish
        GarbleMarkGeometry.lerp(GarbleMarkGeometry.UPRIGHT, GarbleMarkGeometry.DOTS, 1.3f, out)
        (out[0] > 0f) shouldBe true
    }

    "mic state to mark state" {
        val listening = GarMicState.Listening(followUpEndsAt = null)
        garbleMarkStateFor(listening, voice = false) shouldBe GarbleMarkState.Listening
        garbleMarkStateFor(listening, voice = true) shouldBe GarbleMarkState.Hearing
        garbleMarkStateFor(GarMicState.Starting, voice = false) shouldBe GarbleMarkState.Listening
        garbleMarkStateFor(GarMicState.Transcribing, voice = false) shouldBe GarbleMarkState.Thinking
        garbleMarkStateFor(GarMicState.Thinking(0, null, model = true, loadingModel = false), false) shouldBe
            GarbleMarkState.Thinking
        garbleMarkStateFor(GarMicState.Speaking, voice = false) shouldBe GarbleMarkState.Speaking
        garbleMarkStateFor(GarMicState.Idle, voice = false) shouldBe GarbleMarkState.Logo
        garbleMarkStateFor(GarMicState.Idle, voice = false, answered = true) shouldBe
            GarbleMarkState.Understood
        GarbleMarkGeometry.animates(GarbleMarkState.Logo) shouldBe false
        GarbleMarkGeometry.animates(GarbleMarkState.Hearing) shouldBe true
    }

    "reduced motion poses don't move with time" {
        val a = FloatArray(15)
        val b = FloatArray(15)
        for (state in listOf(
            GarbleMarkState.Listening, GarbleMarkState.Thinking, GarbleMarkState.Speaking,
        )) {
            GarbleMarkGeometry.pose(state, 0L, 0.3f, null, null, reducedMotion = true, out = a)
            GarbleMarkGeometry.pose(state, 437L, 0.3f, null, null, reducedMotion = true, out = b)
            a.shouldMatch(b)
        }
        GarbleMarkGeometry.pose(GarbleMarkState.Listening, 900L, 0f, null, null, true, a)
        a.shouldMatch(GarbleMarkGeometry.DOTS)
        GarbleMarkGeometry.pose(GarbleMarkState.Speaking, 900L, 0f, null, null, true, a)
        a.shouldMatch(GarbleMarkGeometry.UPRIGHT)
        // the clock only runs where real data drives the mark
        GarbleMarkGeometry.ticks(GarbleMarkState.Listening, true, false) shouldBe false
        GarbleMarkGeometry.ticks(GarbleMarkState.Thinking, true, false) shouldBe false
        GarbleMarkGeometry.ticks(GarbleMarkState.Speaking, true, false) shouldBe false
        GarbleMarkGeometry.ticks(GarbleMarkState.Speaking, true, true) shouldBe true
        GarbleMarkGeometry.ticks(GarbleMarkState.Hearing, true, false) shouldBe true
        GarbleMarkGeometry.ticks(GarbleMarkState.Listening, false, false) shouldBe true
    }

    "card shadows map CSS blur to a shadow layer radius" {
        // sigma = blur / 2, radius = (sigma - 0.5) / 0.57735
        GarbleDepth.shadowLayerRadius(16f) shouldBe ((8f - 0.5f) / 0.57735f plusOrMinus 0.01f)
        GarbleDepth.shadowLayerRadius(0f) shouldBe 0.5f
    }
})
