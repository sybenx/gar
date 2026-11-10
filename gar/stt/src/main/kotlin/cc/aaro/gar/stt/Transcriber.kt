package cc.aaro.gar.stt

/**
 * Turns a clip into text. Implemented by `WhisperEngine`; the input device sees only this, so its
 * tests can use a fake.
 */
interface Transcriber {
    /**
     * Transcribes the first [samples] floats of [pcm] (16 kHz mono, in [-1, 1]) with an encoder
     * context of [audioCtx] positions (use [AudioCtx.forSamples]) and at most [maxTokens] tokens.
     * [names] (contact names, most important first) go into the initial prompt, as many as fit
     * in [NamePrompt.MAX_TOKENS]; [Transcript.promptNames] says which did.
     *
     * Throws [kotlinx.coroutines.CancellationException] when the calling coroutine is cancelled
     * (the native call is aborted), and [java.io.IOException] when Whisper fails.
     */
    suspend fun transcribe(
        pcm: FloatArray,
        samples: Int,
        audioCtx: Int,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        names: List<String> = emptyList(),
    ): Transcript

    /**
     * Gets the prompt for [names] ready, so that the next [transcribe] with them doesn't wait
     * for it; called while the user talks. Optional: [transcribe] does it when needed.
     */
    suspend fun prepare(names: List<String>) {}

    companion object {
        /** Enough for any command; a guard against decoding loops. */
        const val DEFAULT_MAX_TOKENS = 64
    }
}
