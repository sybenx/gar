package cc.aaro.gar.stt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException

/**
 * On-device [Transcriber] backed by whisper.cpp (JNI). One model per instance, meant for FUTO's
 * ACFT English models (see [AudioCtx] for why only those).
 *
 * All native calls run on one thread owned by the engine, in the order they were made, as in
 * `LlamaEngine`. Each [transcribe] is one greedy `whisper_full` call without timestamps, fallback
 * or context from earlier calls (whisper-design.md 3.2).
 *
 * No `android.*` APIs are used here, so the engine also runs on a desktop JVM with a host build
 * of the native library (see `gar/stt/host-test/`).
 */
class WhisperEngine private constructor(
    private val executor: ExecutorService,
    private val handle: Long,
    private val abortFlag: ByteBuffer,
    /** whisper.cpp version and commit. */
    val whisperVersion: String,
    /** The CPU features whisper.cpp uses, for logs (expect NEON, DOTPROD and FP16_VA on arm64). */
    val systemInfo: String,
) : Transcriber {

    private val closed = AtomicBoolean(false)
    private val freed = CompletableDeferred<Unit>()
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    /** Guards [running] and writes to [abortFlag], see [Call.cancel]. */
    private val abortLock = Any()
    private var running: Call? = null

    // only used on the engine thread
    private var released = false
    private var promptKey: List<String> = emptyList()
    private var prompt: Prompt = NO_PROMPT

    /**
     * Throws [CancellationException] when the calling coroutine is cancelled or [cancel] is called,
     * [WhisperException] when Whisper fails.
     */
    override suspend fun transcribe(
        pcm: FloatArray,
        samples: Int,
        audioCtx: Int,
        maxTokens: Int,
        names: List<String>,
    ): Transcript {
        require(samples in 0..pcm.size) { "samples $samples outside 0..${pcm.size}" }
        require(audioCtx in 1..AudioCtx.MAX_AUDIO_CTX) { "audioCtx $audioCtx outside 1..${AudioCtx.MAX_AUDIO_CTX}" }
        require(maxTokens > 0) { "maxTokens must be positive: $maxTokens" }
        return submit { call ->
            if (call.cancelled) {
                throw CancellationException("Transcription was cancelled")
            }
            val stats = LongArray(WhisperNative.STATS)
            val scores = FloatArray(WhisperNative.SCORES)
            val prompt = promptFor(names)
            val text = WhisperNative.transcribe(
                handle, pcm, samples, audioCtx, maxTokens, prompt.tokens, stats, scores,
            )
            when (val status = stats[WhisperNative.STAT_STATUS].toInt()) {
                WhisperNative.STATUS_OK -> {}
                WhisperNative.STATUS_CANCELLED -> throw CancellationException("Transcription was cancelled")
                else -> throw WhisperException("Transcription failed ($status): ${WhisperNative.lastError()}")
            }
            Transcript(
                text = String(text ?: ByteArray(0), Charsets.UTF_8).trim(),
                noSpeechProb = scores[WhisperNative.SCORE_NO_SPEECH],
                confidence = scores[WhisperNative.SCORE_CONFIDENCE],
                tokens = stats[WhisperNative.STAT_TOKENS].toInt(),
                audioCtx = audioCtx,
                encodeMicros = stats[WhisperNative.STAT_ENCODE_US],
                totalMicros = stats[WhisperNative.STAT_TOTAL_US],
                promptNames = prompt.names,
                promptTokens = prompt.tokens?.size ?: 0,
            )
        }
    }

    /** Fits and tokenizes the prompt for [names] now, so that [transcribe] finds it ready. */
    override suspend fun prepare(names: List<String>) {
        submit { promptFor(names) }
    }

    /**
     * Whisper's tokens for [text], as the initial prompt gets them ([NamePrompt]).
     * @throws WhisperException if tokenizing fails
     */
    suspend fun tokenize(text: String): IntArray = submit { tokenizeNow(text) }

    /** Engine thread only. */
    private fun tokenizeNow(text: String): IntArray =
        WhisperNative.tokenize(handle, text.toByteArray())
            ?: throw WhisperException("Can't tokenize: ${WhisperNative.lastError()}")

    /**
     * The initial prompt for [names], fitted and tokenized once per list (the caller keeps
     * passing the same list until the contacts change, and [prepare]s it while the user talks);
     * a new model is a new engine, so a new cache. Without a prompt when tokenizing fails.
     * Engine thread only.
     */
    private fun promptFor(names: List<String>): Prompt {
        if (names.isEmpty()) {
            return NO_PROMPT
        }
        if (names != promptKey) {
            prompt = try {
                var fitted = NamePrompt.fit(names, NamePrompt.MAX_TOKENS) { tokenizeNow(it).size }
                var tokens = tokenizeNow(NamePrompt.text(fitted))
                // fit's count is short when a name ends in punctuation ("Jr.,")
                while (tokens.size > NamePrompt.MAX_TOKENS && fitted.isNotEmpty()) {
                    fitted = fitted.dropLast(1)
                    tokens = tokenizeNow(NamePrompt.text(fitted))
                }
                if (fitted.isEmpty()) NO_PROMPT else Prompt(fitted, tokens)
            } catch (e: WhisperException) {
                NO_PROMPT
            }
            promptKey = names
        }
        return prompt
    }

    private class Prompt(val names: List<String>, val tokens: IntArray?)

    /**
     * Aborts running and queued [transcribe] calls; they throw [CancellationException]. Calls made
     * afterwards run normally. Cancelling the coroutine of a call also aborts it. Any thread.
     */
    fun cancel() {
        calls.forEach { it.cancel() }
    }

    /** Aborts running calls and frees the model once the engine thread is done with it. */
    suspend fun close() {
        startClose()
        freed.await()
    }

    private fun startClose() {
        if (closed.compareAndSet(false, true)) {
            cancel()
            try {
                executor.execute { release() }
            } catch (e: RejectedExecutionException) {
                // never happens: only startClose shuts the executor down
            }
            executor.shutdown()
        }
    }

    /** Runs [task] on the engine thread. */
    private suspend fun <T> submit(task: (Call) -> T): T {
        check(!closed.get()) { "WhisperEngine is closed" }
        val call = Call()
        calls += call
        try {
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                try {
                    executor.execute {
                        synchronized(abortLock) {
                            abortFlag.put(0, 0)
                            running = call
                        }
                        val result = try {
                            runCatching {
                                check(!released) { "WhisperEngine is closed" }
                                task(call)
                            }
                        } finally {
                            synchronized(abortLock) {
                                running = null
                            }
                        }
                        cont.resumeWith(result)
                    }
                } catch (e: RejectedExecutionException) {
                    throw IllegalStateException("WhisperEngine is closed", e)
                }
            }
        } finally {
            calls -= call
        }
    }

    private inner class Call {
        @Volatile
        var cancelled = false

        fun cancel() {
            // A call that has not started sees `cancelled` when it starts; a running one is
            // aborted through the flag. The lock keeps a cancel() that races with the end of
            // this call from setting the flag after the next call has cleared it.
            synchronized(abortLock) {
                cancelled = true
                if (running === this) {
                    abortFlag.put(0, 1)
                }
            }
        }
    }

    /** Engine thread only. */
    private fun release() {
        if (!released) {
            released = true
            WhisperNative.free(handle)
        }
        freed.complete(Unit)
    }

    companion object {
        private val NO_PROMPT = Prompt(emptyList(), null)

        /**
         * The X4 and three A720 cores of the Pixel 9a's Tensor G4, as `LlamaConfig.threads`; more
         * would land on the slow A520s.
         */
        const val DEFAULT_THREADS = 4

        /**
         * Loads [model] (whisper.cpp ggml file) on the engine's own thread, into RAM (no mmap).
         * Cancelling the caller doesn't stop whisper.cpp's load, but the engine is then freed.
         * @throws WhisperException if the file can't be loaded
         */
        suspend fun load(model: File, threads: Int = DEFAULT_THREADS): WhisperEngine {
            require(threads > 0) { "threads must be positive: $threads" }
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "gar-whisper").apply { isDaemon = true }
            }
            val abortFlag = ByteBuffer.allocateDirect(1)

            return suspendCancellableCoroutine { cont ->
                executor.execute {
                    val engine = try {
                        open(executor, abortFlag, model, threads)
                    } catch (e: Throwable) {
                        executor.shutdown()
                        cont.resumeWithException(e)
                        return@execute
                    }
                    // if the caller was cancelled in the meantime, nobody gets the engine
                    cont.resume(engine) { _, _, _ -> engine.startClose() }
                }
            }
        }

        /** Engine thread only. */
        private fun open(executor: ExecutorService, abortFlag: ByteBuffer, model: File, threads: Int): WhisperEngine {
            if (!model.isFile) {
                throw WhisperException("Model file not found: $model")
            }
            try {
                WhisperNative.ensureLoaded()
            } catch (e: UnsatisfiedLinkError) {
                throw WhisperException("Can't load the whisper.cpp library", e)
            }
            val handle = WhisperNative.load(model.absolutePath.toByteArray(), threads, abortFlag)
            if (handle == 0L) {
                throw WhisperException("Can't load ${model.name}: ${WhisperNative.lastError()}")
            }
            return WhisperEngine(
                executor = executor,
                handle = handle,
                abortFlag = abortFlag,
                whisperVersion = WhisperNative.version(),
                systemInfo = WhisperNative.systemInfo(),
            )
        }
    }
}

/** whisper.cpp failed: the model can't be loaded, or a call on it failed. */
class WhisperException(message: String, cause: Throwable? = null) : IOException(message, cause)
