package org.stypox.dicio.gar.stt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The live mic for Garble's mark: the smoothed level and 5 coarse bands of every captured frame
 * (32 ms), zero whenever speech input isn't listening. Fed by GarWhisperInputDevice's capture
 * loop; Vosk and the external popup expose no audio, so it stays [Frame.SILENT] with them and the
 * mark shows breathing dots.
 *
 * Read [frame]'s value in the DRAW phase (GarbleMark does), never collect it into composition:
 * it changes 30 times a second.
 */
object GarMicLevel {
    /**
     * One frame. Not a data class: every publish is a different instance, the arrays are not
     * compared. Frames are reused from a small ring (see [publish]): read one within a draw, don't
     * keep it.
     */
    class Frame(
        rms: Float,
        /** [GarMicSpectrum.BANDS] values, low to high, smoothed like [rms]. Don't modify. */
        val bands: FloatArray,
        voice: Boolean,
    ) {
        /** 0 (-60 dBFS) to 1 (-10 dBFS), rises fast and falls slowly. */
        var rms: Float = rms
            internal set

        /** The endpointer hears speech (Hearing rather than Listening dots). */
        var voice: Boolean = voice
            internal set

        companion object {
            val SILENT = Frame(0f, FloatArray(GarMicSpectrum.BANDS), false)
        }
    }

    private val _frame = MutableStateFlow(Frame.SILENT)
    val frame: StateFlow<Frame> = _frame

    private val _speech = MutableStateFlow<Float?>(null)

    /**
     * TTS output amplitude 0..1 while speaking, if an engine reports it; null means synthesize
     * the speaking pulse (nothing feeds this yet, see [publishSpeech]).
     */
    val speech: StateFlow<Float?> = _speech

    private val spectrum = GarMicSpectrum()
    private val raw = FloatArray(GarMicSpectrum.BANDS)
    private val smoothed = FloatArray(GarMicSpectrum.BANDS)

    // publish() runs on the capture thread every 32 ms: frames come from a ring instead of two
    // allocations per call. The UI reads a frame within one draw, and a slot is rewritten only
    // RING publishes (about 100 ms) later, so it never sees one half written. Each publish is a
    // different instance than the last, so the StateFlow still emits.
    private val ring = Array(RING) { Frame(0f, FloatArray(GarMicSpectrum.BANDS), false) }
    private var next = 0

    /**
     * From the capture thread: [level] is the device's smoothed level, [pcm]'s first [samples]
     * the frame it came from (only read).
     */
    @Synchronized
    fun publish(level: Float, pcm: FloatArray, samples: Int, voice: Boolean) {
        spectrum.bands(pcm, samples, raw)
        val frame = ring[next]
        next = (next + 1) % RING
        for (i in smoothed.indices) {
            smoothed[i] = smooth(smoothed[i], raw[i])
            frame.bands[i] = smoothed[i]
        }
        frame.rms = level.coerceIn(0f, 1f)
        frame.voice = voice
        _frame.value = frame
    }

    /** The mic stopped, paused or was silenced by the system. */
    @Synchronized
    fun clear() {
        smoothed.fill(0f)
        _frame.value = Frame.SILENT
    }

    /** For a TTS amplitude source (e.g. UtteranceProgressListener.onAudioAvailable); null ends it. */
    fun publishSpeech(level: Float?) {
        _speech.value = level?.coerceIn(0f, 1f)
    }

    private const val RING = 3

    /** Like GarWhisperInputDevice.smoothLevel: rises fast, falls slowly. */
    internal fun smooth(previous: Float, current: Float): Float =
        previous + (current - previous) * (if (current > previous) 0.6f else 0.2f)
}
