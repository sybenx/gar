package cc.aaro.gar.stt

import java.nio.ByteBuffer

/**
 * JNI functions of `libgar_whisper` (`src/main/cpp/gar_whisper.cpp`). Not thread-safe: [WhisperEngine]
 * calls the Whisper functions from its own thread, [SileroVad] the VAD functions from the capture
 * thread. Text is passed as UTF-8 bytes. The constants must match the ones in the C++ file.
 */
internal object WhisperNative {
    const val STATUS_OK = 0
    const val STATUS_CANCELLED = 1
    // other statuses: whisper_full's negative error codes (-5 audio_ctx too large, -6 encode
    // failed, -7 to -9 decode failed)

    // transcribe stats
    const val STAT_STATUS = 0
    const val STAT_TOTAL_US = 1
    const val STAT_ENCODE_US = 2
    const val STAT_TOKENS = 3
    const val STATS = 4

    // transcribe scores
    const val SCORE_NO_SPEECH = 0
    const val SCORE_CONFIDENCE = 1
    const val SCORES = 2

    /** Samples per [vadProbability] call: Silero's 32 ms window at 16 kHz. */
    const val VAD_FRAME_SAMPLES = 512

    /**
     * System property with the absolute path of a host build of the library (see
     * `gar/stt/host-test/`). If unset, the library is loaded from the APK.
     */
    const val LIBRARY_PATH_PROPERTY = "cc.aaro.gar.stt.libraryPath"

    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            val path = System.getProperty(LIBRARY_PATH_PROPERTY)
            if (path.isNullOrEmpty()) {
                System.loadLibrary("gar_whisper")
            } else {
                System.load(path)
            }
            loaded = true
        }
    }

    /** whisper.cpp version and commit. */
    @JvmStatic external fun version(): String

    /** whisper.cpp's summary of the CPU features it uses. */
    @JvmStatic external fun systemInfo(): String

    /** Why the last call on this thread failed. */
    @JvmStatic external fun lastError(): String

    /**
     * @param abortFlag direct buffer whose first byte, when not 0, aborts [transcribe]
     * @return a handle, or 0 if loading failed
     */
    @JvmStatic external fun load(path: ByteArray, threads: Int, abortFlag: ByteBuffer): Long

    @JvmStatic external fun free(handle: Long)

    /** @return the tokens of [text], or null on errors */
    @JvmStatic external fun tokenize(handle: Long, text: ByteArray): IntArray?

    /**
     * One `whisper_full` call on the first [n] floats of [pcm].
     * @param prompt tokens to condition the decoder on, or null
     * @param stats receives [STAT_STATUS], [STAT_TOTAL_US], [STAT_ENCODE_US], [STAT_TOKENS]
     * @param scores receives [SCORE_NO_SPEECH], [SCORE_CONFIDENCE]
     * @return segment 0's text (empty when cancelled), or null on errors
     */
    @JvmStatic external fun transcribe(
        handle: Long,
        pcm: FloatArray,
        n: Int,
        audioCtx: Int,
        maxTokens: Int,
        prompt: IntArray?,
        stats: LongArray,
        scores: FloatArray,
    ): ByteArray?

    /** @return a VAD handle, or 0 if loading failed */
    @JvmStatic external fun vadLoad(path: ByteArray, threads: Int): Long

    /**
     * The speech probability of [frame] ([VAD_FRAME_SAMPLES] floats), continuing from the frames
     * before it. -1 on errors.
     */
    @JvmStatic external fun vadProbability(vad: Long, frame: FloatArray): Float

    @JvmStatic external fun vadReset(vad: Long)

    @JvmStatic external fun vadFree(vad: Long)
}
