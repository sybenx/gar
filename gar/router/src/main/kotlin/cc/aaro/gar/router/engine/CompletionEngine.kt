package cc.aaro.gar.router.engine

/**
 * A loaded language model that completes text. Implementations: `LlamaEngine` in `:gar:llm`
 * (on device, JNI) and `LlamaServerEngine` in this module's eval source set (llama-server over
 * HTTP, for the eval and tests on a dev machine).
 *
 * Implementations keep the KV cache of the last [CompletionRequest.prefix] and only evaluate
 * [CompletionRequest.suffix] when the prefix is unchanged. Calls are serialized by the
 * implementation; callers may call from any coroutine.
 */
interface CompletionEngine {
    suspend fun complete(request: CompletionRequest): Completion

    /** Evaluates and caches [prefix] ahead of time, e.g. while the user is still speaking. */
    suspend fun warmUp(prefix: String)

    /** Frees the model. The engine can't be used afterwards. */
    suspend fun close()
}

data class CompletionRequest(
    /** Static part of the prompt (system/developer turn with the tool declarations). */
    val prefix: String,
    /** Per-request part (user turn and the start of the assistant turn). */
    val suffix: String,
    /** GBNF grammar constraining the output, or null for unconstrained generation. */
    val grammar: String?,
    val maxTokens: Int,
    /** Generation stops after any of these strings is produced (it is included in the text). */
    val stopStrings: List<String>,
)

data class Completion(
    /** Generated text, special tokens rendered as text (e.g. `<tool_call>`). */
    val text: String,
    val promptTokens: Int,
    /** Prompt tokens that were reused from the cache instead of being evaluated. */
    val cachedTokens: Int,
    val generatedTokens: Int,
    val prefillMillis: Long,
    val generateMillis: Long,
    val stopReason: StopReason,
)

enum class StopReason { END_OF_GENERATION, STOP_STRING, MAX_TOKENS, CANCELLED }
