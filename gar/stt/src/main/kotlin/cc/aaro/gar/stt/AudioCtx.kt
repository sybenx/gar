package cc.aaro.gar.stt

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * How many encoder positions Whisper gets for a clip (`whisper_full_params.audio_ctx`).
 *
 * One position is 20 ms of audio (two mel frames, [SAMPLES_PER_POSITION] samples at 16 kHz).
 * whisper.cpp encodes only the first `audio_ctx` positions and drops the rest, so the value must
 * cover the whole clip. FUTO's ACFT models were fine-tuned for a context that fits the clip, and
 * evaluated with `min(1500, round(50 * seconds) + 8)` (futo-org/whisper-acft, MIT); GAR adds a
 * floor, because very short commands are rare in the fine-tuning data and extra context only
 * costs encoder time.
 *
 * Only ACFT models may get a small context: stock Whisper falls apart below [MAX_AUDIO_CTX].
 */
object AudioCtx {
    /** The floor, 2.56 s. To tune on recordings (64, 96, 128, 192). */
    const val MIN_AUDIO_CTX = 128

    /** Whisper's full 30 s window. */
    const val MAX_AUDIO_CTX = 1500

    /** Positions added on top of the clip, as in FUTO's evaluation (ADD_AUDIO_CTX). */
    const val EXTRA_AUDIO_CTX = 8

    const val SAMPLE_RATE = 16_000
    const val SAMPLES_PER_POSITION = 320

    /**
     * The context for a clip of [samples] samples at 16 kHz, between [min] and [MAX_AUDIO_CTX].
     * Rounds half to even, like Python's `round` in FUTO's notebooks.
     */
    fun forSamples(samples: Int, min: Int = MIN_AUDIO_CTX): Int {
        require(samples >= 0) { "samples must not be negative: $samples" }
        val positions = round(samples / SAMPLES_PER_POSITION.toDouble()).toInt() + EXTRA_AUDIO_CTX
        return min(MAX_AUDIO_CTX, max(min, positions))
    }
}
