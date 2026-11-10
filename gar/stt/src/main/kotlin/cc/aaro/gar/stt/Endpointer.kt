package cc.aaro.gar.stt

import kotlin.math.max
import kotlin.math.min

/**
 * Decides, one frame of speech probabilities at a time, when a spoken command starts and ends,
 * and which samples of the capture to give Whisper. Pure logic: a [SpeechDetector] scores each
 * frame, the capture loop passes the score to [accept] and acts on the [Decision].
 *
 * - Speech starts when [Config.startFrames] of the last [Config.startWindow] frames score at
 *   least [Config.startThreshold] (3 of 4, about 100 ms).
 * - Once started, a frame scoring at least [Config.endThreshold] still counts as speech (Silero's
 *   rule: the start threshold minus 0.15). Speech ends after [Config.hangMillis] of frames below
 *   it.
 * - A stretch of speech shorter than [Config.minSpeechMillis] in total (a click, a cough, the
 *   earcon) is forgotten when it ends, and waiting goes on.
 * - With no speech for the first [Config.noSpeechMillis] of the capture: [Decision.Discard].
 * - At [Config.maxMillis] of capture, what was heard is transcribed.
 * - The clip runs from [Config.preRollMillis] before speech started to [Config.postRollMillis]
 *   after the last speech frame, within what was captured.
 *
 * Feed frames of exactly [Config.frameSamples] samples; frame `i` covers the capture's samples
 * `[i * frameSamples, (i + 1) * frameSamples)`. Not thread-safe: the capture thread only.
 */
class Endpointer(val config: Config = Config()) {

    data class Config(
        /** Samples per frame: [SpeechDetector.frameSamples], 512 (32 ms) for Silero. */
        val frameSamples: Int = 512,
        val sampleRate: Int = AudioCtx.SAMPLE_RATE,
        val startThreshold: Float = 0.5f,
        val startFrames: Int = 3,
        val startWindow: Int = 4,
        val endThreshold: Float = 0.35f,
        /** Silence after speech that ends it. To tune between 600 and 1000. */
        val hangMillis: Int = 800,
        /**
         * No speech this long from the start: nothing was said. The app passes Dicio's silence
         * setting x 3 s to [reset] (`GarWhisperInputDevice.NO_SPEECH_MILLIS_PER_STEP`).
         */
        val noSpeechMillis: Int = 4_000,
        /** Less speech than this in total is not a command; Whisper never runs on it. */
        val minSpeechMillis: Int = 250,
        /** The capture cap. Whisper's window is 30 s and audio_ctx must cover the clip. */
        val maxMillis: Int = 28_000,
        val preRollMillis: Int = 300,
        val postRollMillis: Int = 200,
    ) {
        init {
            require(frameSamples > 0) { "frameSamples must be positive" }
            require(startFrames in 1..startWindow) { "startFrames must be in 1..startWindow" }
            require(endThreshold <= startThreshold) { "endThreshold above startThreshold" }
        }

        /** The capture buffer needed for [maxMillis], in whole frames. */
        val maxSamples: Int
            get() = ceilFrames(samples(maxMillis)) * frameSamples

        internal fun samples(millis: Int): Int = (millis.toLong() * sampleRate / 1000).toInt()

        internal fun ceilFrames(samples: Int): Int = (samples + frameSamples - 1) / frameSamples
    }

    /** What the capture loop does after a frame. */
    sealed interface Decision {
        /** Keep listening. */
        data object Continue : Decision

        /** Stop capturing and transcribe [clip]. */
        data class Transcribe(val clip: Clip, val reason: Reason) : Decision

        /** Stop capturing; nothing worth transcribing was said. */
        data class Discard(val reason: Reason) : Decision
    }

    enum class Reason {
        /** Silence for the hang time after speech. */
        END_OF_SPEECH,

        /** The capture reached [Config.maxMillis]. */
        CAP,

        /** Asked to stop by [finish] (a tap, the end of the audio source). */
        FINISHED,

        /** No speech before [Config.noSpeechMillis]. */
        NO_SPEECH,

        /** Speech was heard, but less than [Config.minSpeechMillis]. */
        TOO_LITTLE,
    }

    /**
     * The samples `[start, end)` of the capture to transcribe; [speechSamples] counts the frames
     * that held speech, for the hallucination guard and the log.
     */
    data class Clip(val start: Int, val end: Int, val speechSamples: Int) {
        val samples: Int get() = end - start
    }

    private val hangFrames = config.ceilFrames(config.samples(config.hangMillis))
    private val maxFrames = config.ceilFrames(config.samples(config.maxMillis))
    private val minSpeechSamples = config.samples(config.minSpeechMillis)
    private val preRollSamples = config.samples(config.preRollMillis)
    private val postRollSamples = config.samples(config.postRollMillis)
    private var noSpeechFrames = config.ceilFrames(config.samples(config.noSpeechMillis))

    /** Whether each of the last [Config.startWindow] frames reached the start threshold. */
    private val window = BooleanArray(config.startWindow)
    private var frames = 0
    private var started = false
    private var startFrame = 0
    private var lastSpeechFrame = -1
    private var speechFrames = 0
    private var silentFrames = 0
    private var done = false

    /** Whether the last frame was speech, for the level meter. */
    var speaking: Boolean = false
        private set

    /** Frames accepted since [reset]. */
    val frameCount: Int get() = frames

    /** Samples accepted since [reset]. */
    val capturedSamples: Int get() = frames * config.frameSamples

    /** Speech heard in the current stretch, in samples. */
    val speechSamples: Int get() = speechFrames * config.frameSamples

    /**
     * Starts over for a new capture; [noSpeechMillis] replaces the config's timeout when not
     * null (Dicio's setting can change between captures).
     */
    fun reset(noSpeechMillis: Int? = null) {
        noSpeechFrames = config.ceilFrames(config.samples(noSpeechMillis ?: config.noSpeechMillis))
        window.fill(false)
        frames = 0
        started = false
        startFrame = 0
        lastSpeechFrame = -1
        speechFrames = 0
        silentFrames = 0
        speaking = false
        done = false
    }

    /** Takes the next frame's speech [probability] and says what to do. */
    fun accept(probability: Float): Decision {
        check(!done) { "The capture already ended; call reset() first" }
        val frame = frames++
        window[frame % window.size] = probability >= config.startThreshold

        if (!started) {
            if (window.count { it } >= config.startFrames) {
                started = true
                // speech began at the oldest frame of the window that reached the threshold
                val oldest = max(0, frame - window.size + 1)
                startFrame = (oldest..frame).first { window[it % window.size] }
                speechFrames = (startFrame..frame).count { window[it % window.size] }
                lastSpeechFrame = frame
                silentFrames = 0
            }
        } else if (probability >= config.endThreshold) {
            speechFrames++
            lastSpeechFrame = frame
            silentFrames = 0
        } else {
            silentFrames++
        }
        speaking = started && lastSpeechFrame == frame

        if (started && silentFrames >= hangFrames) {
            if (speechSamples >= minSpeechSamples) {
                return end(Decision.Transcribe(clip(), Reason.END_OF_SPEECH))
            }
            forgetSpeech() // a blip: keep waiting
        }
        if (frames >= maxFrames) {
            return end(finishDecision(Reason.CAP))
        }
        if (!started && frames >= noSpeechFrames) {
            return end(Decision.Discard(Reason.NO_SPEECH))
        }
        return Decision.Continue
    }

    /**
     * Ends the capture now (a tap while listening, the end of the audio): transcribes the speech
     * heard so far, or discards the capture when there was too little.
     */
    fun finish(): Decision = end(finishDecision(Reason.FINISHED))

    private fun finishDecision(reason: Reason): Decision = when {
        !started -> Decision.Discard(Reason.NO_SPEECH)
        speechSamples < minSpeechSamples -> Decision.Discard(Reason.TOO_LITTLE)
        else -> Decision.Transcribe(clip(), reason)
    }

    private fun clip(): Clip {
        val captured = capturedSamples
        val start = max(0, startFrame * config.frameSamples - preRollSamples)
        val end = min(captured, (lastSpeechFrame + 1) * config.frameSamples + postRollSamples)
        return Clip(start, end, speechSamples)
    }

    private fun forgetSpeech() {
        started = false
        speechFrames = 0
        lastSpeechFrame = -1
        silentFrames = 0
        speaking = false
        window.fill(false)
    }

    private fun end(decision: Decision): Decision {
        done = true
        speaking = false
        return decision
    }
}
