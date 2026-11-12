package org.stypox.dicio.gar.ui.garble

import org.stypox.dicio.gar.ui.GarMicState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the mark shows. */
enum class GarbleMarkState {
    /** The static brand (also "resolving"). */
    Logo,

    /** Mic open, no voice yet: 5 breathing dots. */
    Listening,

    /** Voice coming through: a scramble driven by the level and the 5 bands. */
    Hearing,

    /** The model fallback (and any wait): a turning wave. */
    Thinking,

    /** Upright and symmetric, pulsing with speech. */
    Speaking,

    /** Upright and symmetric, still: answered, not speaking. */
    Understood,
}

/**
 * The mark's geometry, pure (unit tested). A pose is 5 bars x (height, rotation degrees,
 * translateY) in units where a bar is [BAR_WIDTH] = 20 wide, packed in a FloatArray of 15:
 * `[h0, r0, y0, h1, r1, y1, ...]`. Bars are [GAP] apart, rounded by w/2, centered vertically on
 * the mark's center plus translateY (positive is down), rotated about their own center.
 */
object GarbleMarkGeometry {
    const val BARS = GarbleColors.MARK_BARS
    const val BAR_WIDTH = 20f
    const val GAP = 12f // 0.6 w

    /** The square, in units, the mark is fitted into (the widest pose is 148 wide). */
    const val BOX = 168f

    /** A dot: a bar as tall as it is wide. */
    const val DOT = BAR_WIDTH

    val LOGO = floatArrayOf(54f, -28f, -16f, 84f, 16f, 18f, 116f, -5f, -4f, 146f, 0f, 0f, 92f, 0f, 0f)
    val DOTS = floatArrayOf(DOT, 0f, 0f, DOT, 0f, 0f, DOT, 0f, 0f, DOT, 0f, 0f, DOT, 0f, 0f)
    val HEARING = floatArrayOf(
        62f, -28f, -20f, 100f, 18f, 22f, 46f, -12f, -30f, 124f, 22f, 10f, 74f, -16f, 24f,
    )
    val THINKING = floatArrayOf(36f, 0f, -18f, 60f, 0f, -9f, 84f, 0f, 0f, 60f, 0f, 9f, 36f, 0f, 18f)
    val UPRIGHT = floatArrayOf(48f, 0f, 0f, 98f, 0f, 0f, 148f, 0f, 0f, 98f, 0f, 0f, 48f, 0f, 0f)

    const val BREATH_PERIOD_MILLIS = 1_600f
    const val BREATH_UNITS = 6f
    const val THINKING_PERIOD_MILLIS = 1_200f
    const val SPEAKING_PERIOD_MILLIS = 700f
    const val SPEAKING_ENVELOPE_MILLIS = 2_300f

    /** Speaking at its quietest is this share of the upright heights. */
    const val SPEAKING_MIN_SHARE = 0.82f

    /** Hearing reaches its full scramble at this mic level. */
    const val HEARING_FULL_LEVEL = 0.55f

    /** Horizontal center of bar [i], relative to the mark's center, in units. */
    fun barCenterX(i: Int): Float = (i - (BARS - 1) / 2f) * (BAR_WIDTH + GAP)

    /**
     * The target pose of [state] at [timeMillis] (since the state began) into [out]. [level] and
     * [bands] are the mic (Hearing), [speech] the TTS amplitude or null for a synthetic pulse
     * (Speaking). [reducedMotion]: no tilt, no vertical offset (Logo stays) and nothing driven by
     * time: Listening is still dots, Thinking the still wave, Speaking upright unless a real
     * amplitude drives it. Hearing still follows the level.
     */
    fun pose(
        state: GarbleMarkState,
        timeMillis: Long,
        level: Float,
        bands: FloatArray?,
        speech: Float?,
        reducedMotion: Boolean,
        out: FloatArray,
    ) {
        val t = timeMillis.toFloat()
        when {
            reducedMotion && state == GarbleMarkState.Listening -> DOTS.copyInto(out)
            reducedMotion && state == GarbleMarkState.Thinking -> THINKING.copyInto(out)
            reducedMotion && state == GarbleMarkState.Speaking && speech == null ->
                UPRIGHT.copyInto(out)
            else -> movingPose(state, t, level, bands, speech, out)
        }
        if (reducedMotion && state != GarbleMarkState.Logo) {
            for (i in 0 until BARS) {
                out[i * 3 + 1] = 0f
                out[i * 3 + 2] = 0f
            }
        }
    }

    private fun movingPose(
        state: GarbleMarkState,
        t: Float,
        level: Float,
        bands: FloatArray?,
        speech: Float?,
        out: FloatArray,
    ) {
        when (state) {
            GarbleMarkState.Logo -> LOGO.copyInto(out)
            GarbleMarkState.Understood -> UPRIGHT.copyInto(out)
            GarbleMarkState.Listening -> for (i in 0 until BARS) {
                val phase = 2f * PI.toFloat() * t / BREATH_PERIOD_MILLIS - i * 0.5f
                set(out, i, DOT + BREATH_UNITS * (0.5f - 0.5f * cos(phase)), 0f, 0f)
            }
            GarbleMarkState.Hearing -> {
                val amount = smoothstep((level / HEARING_FULL_LEVEL).coerceIn(0f, 1f))
                for (i in 0 until BARS) {
                    val band = bands?.getOrNull(i)?.coerceIn(0f, 1f) ?: 0.5f
                    val target = HEARING[i * 3] * (0.7f + 0.6f * band)
                    val h = (DOT + (target - DOT) * amount).coerceAtLeast(DOT)
                    set(out, i, h, HEARING[i * 3 + 1] * amount, HEARING[i * 3 + 2] * amount)
                }
            }
            GarbleMarkState.Thinking -> {
                val phase = 2f * PI.toFloat() * t / THINKING_PERIOD_MILLIS
                for (i in 0 until BARS) {
                    val offset = i - (BARS - 1) / 2
                    set(
                        out, i,
                        60f + 24f * cos(phase + offset * PI.toFloat() / 2f),
                        0f,
                        9f * offset * cos(phase),
                    )
                }
            }
            GarbleMarkState.Speaking -> for (i in 0 until BARS) {
                val pulse = speech?.coerceIn(0f, 1f) ?: syntheticSpeech(t, i)
                val share = SPEAKING_MIN_SHARE + (1f - SPEAKING_MIN_SHARE) * pulse
                set(out, i, UPRIGHT[i * 3] * share, 0f, 0f)
            }
        }
    }

    /** 0..1: a syllable-rate wobble per bar under a slower phrase envelope. */
    fun syntheticSpeech(t: Float, i: Int): Float {
        val fast = 0.5f + 0.5f * sin(2f * PI.toFloat() * t / SPEAKING_PERIOD_MILLIS + i * 1.1f)
        val slow = 0.6f + 0.4f * sin(2f * PI.toFloat() * t / SPEAKING_ENVELOPE_MILLIS)
        return (fast * slow).coerceIn(0f, 1f)
    }

    /** [from] to [to] by [fraction] (may overshoot, springs do) into [out]. */
    fun lerp(from: FloatArray, to: FloatArray, fraction: Float, out: FloatArray) {
        for (i in out.indices) {
            out[i] = from[i] + (to[i] - from[i]) * fraction
        }
        for (i in 0 until BARS) {
            out[i * 3] = out[i * 3].coerceAtLeast(DOT * 0.5f)
        }
    }

    private fun set(out: FloatArray, i: Int, height: Float, rotation: Float, translateY: Float) {
        out[i * 3] = height
        out[i * 3 + 1] = rotation
        out[i * 3 + 2] = translateY
    }

    private fun smoothstep(x: Float): Float = x * x * (3f - 2f * x)

    /** True if [state] moves over time (needs a frame clock). */
    fun animates(state: GarbleMarkState): Boolean =
        state != GarbleMarkState.Logo && state != GarbleMarkState.Understood

    /**
     * True if [state] needs the frame clock: it moves, and under [reducedMotion] only where real
     * data drives it (Hearing's level, Speaking with a real amplitude, [hasSpeech]).
     */
    fun ticks(state: GarbleMarkState, reducedMotion: Boolean, hasSpeech: Boolean): Boolean = when {
        !animates(state) -> false
        !reducedMotion -> true
        state == GarbleMarkState.Hearing -> true
        state == GarbleMarkState.Speaking -> hasSpeech
        else -> false
    }

    /** The clock advances at most this often (about 60 fps; the mic updates at about 31 Hz). */
    const val MIN_TICK_MILLIS = 16L
}

/**
 * The mark for the app's mic state. [voice] is the endpointer hearing speech (GarMicLevel's
 * Frame.voice or VoiceActivity.speaking), [answered] an answer on screen. Transcribing and a slow
 * skill count as a wait (Thinking); Idle is the Logo, or Understood once answered.
 */
fun garbleMarkStateFor(micState: GarMicState, voice: Boolean, answered: Boolean = false): GarbleMarkState =
    when (micState) {
        is GarMicState.Listening, GarMicState.Starting ->
            if (voice) GarbleMarkState.Hearing else GarbleMarkState.Listening
        GarMicState.Transcribing, is GarMicState.Thinking -> GarbleMarkState.Thinking
        GarMicState.Speaking -> GarbleMarkState.Speaking
        GarMicState.Idle, is GarMicState.Unavailable ->
            if (answered) GarbleMarkState.Understood else GarbleMarkState.Logo
    }
