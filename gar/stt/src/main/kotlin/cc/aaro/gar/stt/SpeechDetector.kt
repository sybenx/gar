package cc.aaro.gar.stt

/**
 * Tells speech from silence one frame at a time, for end-pointing. The implementation is
 * `SileroVad` (whisper.cpp's streaming VAD); tests script the probabilities.
 *
 * Not thread-safe: one capture thread feeds it. Call [reset] at the start of each capture, since
 * the detector keeps state between frames. [close] frees native memory; the default does nothing.
 */
interface SpeechDetector : AutoCloseable {
    /** The samples (16 kHz mono) [probability] expects per call: 512 (32 ms) for Silero. */
    val frameSamples: Int

    /**
     * How likely [frame] holds speech, from 0 to 1. [frame] has exactly [frameSamples] floats in
     * [-1, 1]; zero-pad a partial one.
     */
    fun probability(frame: FloatArray): Float

    /** Forgets the previous frames, before a new capture. */
    fun reset()

    override fun close() {}
}
