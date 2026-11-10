package cc.aaro.gar.llm

import java.nio.ByteBuffer

/**
 * JNI functions of `libgar_llama` (`src/main/cpp/gar_llama.cpp`). Not thread-safe: [LlamaEngine]
 * calls all of them from its own thread. Text is passed as UTF-8 bytes. The constants must match
 * the ones in the C++ file.
 */
internal object LlamaNative {
    const val STATUS_OK = 0
    const val STATUS_CANCELLED = 1

    // evalPrefix stats
    const val PREFIX_TOKENS = 0
    const val PREFIX_REUSED = 1
    const val PREFIX_RESTORED = 2
    const val PREFIX_MICROS = 3
    const val PREFIX_STATS = 4

    // complete stats
    const val COMPLETE_STOP_REASON = 0
    const val COMPLETE_SUFFIX_TOKENS = 1
    const val COMPLETE_GENERATED = 2
    const val COMPLETE_PREFILL_MICROS = 3
    const val COMPLETE_GENERATE_MICROS = 4
    const val COMPLETE_STATS = 5

    // stop reasons
    const val STOP_END_OF_GENERATION = 0L
    const val STOP_STOP_STRING = 1L
    const val STOP_MAX_TOKENS = 2L
    const val STOP_CANCELLED = 3L

    /**
     * System property with the absolute path of a host build of the library (see
     * `gar/llm/host-test/`). If unset, the library is loaded from the APK.
     */
    const val LIBRARY_PATH_PROPERTY = "cc.aaro.gar.llm.libraryPath"

    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            val path = System.getProperty(LIBRARY_PATH_PROPERTY)
            if (path.isNullOrEmpty()) {
                System.loadLibrary("gar_llama")
            } else {
                System.load(path)
            }
            loaded = true
        }
    }

    /** llama.cpp version and commit. */
    @JvmStatic external fun version(): String

    /** llama.cpp's summary of the CPU features it uses. */
    @JvmStatic external fun systemInfo(): String

    /** Why the last call on this thread failed. */
    @JvmStatic external fun lastError(): String

    /**
     * @param abortFlag direct buffer whose first byte, when not 0, aborts loading and decoding
     * @return a handle, or 0 if loading failed
     */
    @JvmStatic external fun load(
        path: ByteArray,
        contextSize: Int,
        threads: Int,
        batchSize: Int,
        repackWeights: Boolean,
        abortFlag: ByteBuffer,
    ): Long

    @JvmStatic external fun free(handle: Long)

    /** @return the number of tokens, or -1 on errors */
    @JvmStatic external fun tokenCount(handle: Long, text: ByteArray, addSpecial: Boolean): Int

    /** @return [STATUS_OK], [STATUS_CANCELLED] or a negative value on errors */
    @JvmStatic external fun evalPrefix(
        handle: Long,
        text: ByteArray,
        cachePath: ByteArray?,
        stats: LongArray,
    ): Int

    @JvmStatic external fun savePrefix(handle: Long, path: ByteArray): Boolean

    /** @return the generated text, or null on errors */
    @JvmStatic external fun complete(
        handle: Long,
        suffix: ByteArray,
        grammar: ByteArray?,
        maxTokens: Int,
        stopStrings: Array<ByteArray>,
        stats: LongArray,
    ): ByteArray?
}
