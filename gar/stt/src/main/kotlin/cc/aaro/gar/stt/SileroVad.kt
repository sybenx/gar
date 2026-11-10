package cc.aaro.gar.stt

import java.io.File
import java.io.IOException

/**
 * Silero VAD (v6.2.0, ggml conversion from ggml-org/whisper-vad) through whisper.cpp's streaming
 * VAD API. Loads in milliseconds, so the input device creates it at capture start on the capture
 * thread, outside the Whisper engine's load.
 *
 * Not thread-safe: one thread feeds it, from construction to [close].
 *
 * @throws IOException if [model] can't be loaded
 */
class SileroVad(model: File) : SpeechDetector {
    private var handle: Long

    init {
        if (!model.isFile) {
            throw IOException("VAD model not found: $model")
        }
        try {
            WhisperNative.ensureLoaded()
        } catch (e: UnsatisfiedLinkError) {
            throw IOException("Can't load the whisper.cpp library", e)
        }
        handle = WhisperNative.vadLoad(model.absolutePath.toByteArray(), THREADS)
        if (handle == 0L) {
            throw IOException("Can't load ${model.name}: ${WhisperNative.lastError()}")
        }
        // the LSTM state buffer is not cleared on allocation
        WhisperNative.vadReset(handle)
    }

    override val frameSamples: Int = WhisperNative.VAD_FRAME_SAMPLES

    /** @throws IOException if whisper.cpp fails */
    override fun probability(frame: FloatArray): Float {
        check(handle != 0L) { "SileroVad is closed" }
        require(frame.size == frameSamples) { "A frame has $frameSamples samples, not ${frame.size}" }
        val p = WhisperNative.vadProbability(handle, frame)
        if (p < 0f) {
            throw IOException("VAD failed: ${WhisperNative.lastError()}")
        }
        return p
    }

    override fun reset() {
        check(handle != 0L) { "SileroVad is closed" }
        WhisperNative.vadReset(handle)
    }

    override fun close() {
        if (handle != 0L) {
            WhisperNative.vadFree(handle)
            handle = 0L
        }
    }

    private companion object {
        /** Under 1 ms per frame on one core; more threads only add overhead. */
        const val THREADS = 1
    }
}
