package cc.aaro.gar.llm

import cc.aaro.gar.router.engine.Completion
import cc.aaro.gar.router.engine.CompletionEngine
import cc.aaro.gar.router.engine.CompletionRequest
import cc.aaro.gar.router.engine.StopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException

/**
 * On-device [CompletionEngine] backed by llama.cpp (JNI). One model per instance.
 *
 * All native calls run on one thread owned by the engine, in the order they were made. The KV
 * cache keeps the last prefix, so only the suffix is evaluated while the prefix stays the same.
 * Generation is greedy, with grammars enforced by rejection sampling. With a prefix cache dir, a
 * newly evaluated prefix is also saved to a state file there, so that a later [load] of the same
 * model restores it instead of evaluating it again.
 *
 * Prompts: special tokens in the text (e.g. `<|im_start|>`) are parsed as such. The prefix is
 * tokenized with the BOS token the model wants (so it should not contain `<bos>` text, as
 * llama-server's /apply-template output doesn't), the suffix without it, so the prefix must end
 * where the full prompt's tokens split anyway, e.g. right before a special token. A suffix must
 * not be empty. [Completion.text] ends after the stop string that ended it and never contains the
 * end-of-generation token; [Completion.generatedTokens] counts that token too.
 *
 * No `android.*` APIs are used here, so the engine also runs on a desktop JVM with a host build
 * of the native library (see `gar/llm/host-test/`).
 */
class LlamaEngine private constructor(
    private val executor: ExecutorService,
    private val handle: Long,
    private val abortFlag: ByteBuffer,
    private val modelFile: File,
    private val config: LlamaConfig,
    private val prefixCacheDir: File?,
    /** llama.cpp version and commit. */
    val llamaVersion: String,
    /** The CPU features llama.cpp uses, for logs. */
    val systemInfo: String,
) : CompletionEngine {

    private val closed = AtomicBoolean(false)
    private val freed = CompletableDeferred<Unit>()
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    /** Guards [running] and writes to [abortFlag], see [Call.cancel]. */
    private val abortLock = Any()
    private var running: Call? = null

    // only used on the engine thread
    private var released = false
    private var currentPrefix: String? = null
    private var cacheFilePrefix: String? = null
    private var cacheFile: File? = null
    /** The priority the engine thread runs at now, see [background]; engine thread only. */
    private var appliedBackground: Boolean? = null

    /**
     * Whether calls from now on run at a background priority ([LlamaConfig.threadPriority]):
     * while loading and warming up, so that the work a user waits for (matching a command on
     * other threads) gets the CPU first. llama.cpp starts its worker threads from the engine
     * thread for each evaluation, so they run at its priority too.
     */
    @Volatile
    var background: Boolean = false

    /** Engine thread only: applies [background] (or [force]) through [LlamaConfig.threadPriority]. */
    private fun applyPriority(force: Boolean = background) {
        val priority = config.threadPriority ?: return
        if (appliedBackground != force) {
            appliedBackground = force
            try {
                priority.apply(force)
            } catch (e: Exception) {
                // a priority is a hint: the call runs anyway
            }
        }
    }

    override suspend fun complete(request: CompletionRequest): Completion = submit { call ->
        if (call.cancelled) {
            return@submit cancelledCompletion(0, 0)
        }
        val prefix = evalPrefix(request.prefix)
        if (prefix.status == LlamaNative.STATUS_CANCELLED) {
            return@submit cancelledCompletion(prefix.tokens, prefix.micros)
        }

        val stats = LongArray(LlamaNative.COMPLETE_STATS)
        val text = LlamaNative.complete(
            handle,
            request.suffix.toByteArray(),
            request.grammar?.toByteArray(),
            request.maxTokens,
            request.stopStrings.map { it.toByteArray() }.toTypedArray(),
            stats,
        )
        if (text == null) {
            currentPrefix = null // the native side may have dropped it
            throw LlamaException("Completion failed: ${LlamaNative.lastError()}")
        }

        Completion(
            text = String(text, Charsets.UTF_8),
            promptTokens = prefix.tokens + stats[LlamaNative.COMPLETE_SUFFIX_TOKENS].toInt(),
            cachedTokens = prefix.reused,
            generatedTokens = stats[LlamaNative.COMPLETE_GENERATED].toInt(),
            prefillMillis = (prefix.micros + stats[LlamaNative.COMPLETE_PREFILL_MICROS]) / 1000,
            generateMillis = stats[LlamaNative.COMPLETE_GENERATE_MICROS] / 1000,
            stopReason = when (stats[LlamaNative.COMPLETE_STOP_REASON]) {
                LlamaNative.STOP_END_OF_GENERATION -> StopReason.END_OF_GENERATION
                LlamaNative.STOP_STOP_STRING -> StopReason.STOP_STRING
                LlamaNative.STOP_MAX_TOKENS -> StopReason.MAX_TOKENS
                else -> StopReason.CANCELLED
            },
        )
    }

    override suspend fun warmUp(prefix: String) {
        submit { call ->
            if (!call.cancelled) {
                evalPrefix(prefix)
            }
        }
    }

    /**
     * Counts the tokens of [text] for this model. With [addSpecial], a BOS token is counted if the
     * model adds one (as for prefixes).
     */
    suspend fun countTokens(text: String, addSpecial: Boolean = false): Int = submit {
        val count = LlamaNative.tokenCount(handle, text.toByteArray(), addSpecial)
        if (count < 0) {
            throw LlamaException("Can't tokenize: ${LlamaNative.lastError()}")
        }
        count
    }

    /**
     * Aborts running and queued [complete] and [warmUp] calls. [complete] then returns with
     * [StopReason.CANCELLED] and the text generated so far. Calls made afterwards run normally.
     * Cancelling the coroutine of a call also aborts it.
     */
    fun cancel() {
        calls.forEach { it.cancel() }
    }

    override suspend fun close() {
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
        check(!closed.get()) { "LlamaEngine is closed" }
        val call = Call()
        calls += call
        try {
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                try {
                    executor.execute {
                        applyPriority()
                        synchronized(abortLock) {
                            abortFlag.put(0, 0)
                            running = call
                        }
                        val result = try {
                            runCatching {
                                check(!released) { "LlamaEngine is closed" }
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
                    throw IllegalStateException("LlamaEngine is closed", e)
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

    private class PrefixResult(val status: Int, val tokens: Int, val reused: Int, val micros: Long)

    /** Engine thread only. Makes [prefix] the cached prefix, from the KV cache, a state file or by evaluating it. */
    private fun evalPrefix(prefix: String): PrefixResult {
        currentPrefix = null
        val file = prefixCacheFile(prefix)
        val stats = LongArray(LlamaNative.PREFIX_STATS)
        val status = LlamaNative.evalPrefix(handle, prefix.toByteArray(), file?.path?.toByteArray(), stats)
        if (status < 0) {
            throw LlamaException("Can't evaluate the prompt prefix: ${LlamaNative.lastError()}")
        }
        val result = PrefixResult(
            status = status,
            tokens = stats[LlamaNative.PREFIX_TOKENS].toInt(),
            reused = stats[LlamaNative.PREFIX_REUSED].toInt(),
            micros = stats[LlamaNative.PREFIX_MICROS],
        )

        if (status == LlamaNative.STATUS_OK) {
            currentPrefix = prefix
            if (file != null && stats[LlamaNative.PREFIX_RESTORED] != 0L) {
                file.setLastModified(System.currentTimeMillis())
            } else if (file != null && result.reused < result.tokens) {
                // after the current call, so that writing the file doesn't delay its result
                try {
                    executor.execute { savePrefix(prefix, file) }
                } catch (e: RejectedExecutionException) {
                    // closing
                }
            }
        }
        return result
    }

    /** Engine thread only. The state file is optional, so this never throws. */
    private fun savePrefix(prefix: String, file: File) {
        if (released || prefix != currentPrefix) {
            return // another prefix was evaluated in the meantime
        }
        applyPriority(force = true) // nobody waits for the file; the next call sets its own
        val tmp = File(file.path + TMP_SUFFIX)
        try {
            // fsync before the rename: after a power loss, a file with the final name must not
            // have zeroed or stale KV pages, which would restore silently and skew every result
            if (LlamaNative.savePrefix(handle, tmp.path.toByteArray()) && sync(tmp) &&
                tmp.renameTo(file)
            ) {
                prefixCacheDir?.listFiles { f -> f.name.endsWith(CACHE_FILE_SUFFIX) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_PREFIX_CACHE_FILES)
                    ?.forEach { it.delete() }
            } else {
                tmp.delete()
            }
        } catch (e: Exception) {
            // an exception would also end the engine thread, and the executor would start another
            tmp.delete()
        }
    }

    private fun sync(file: File): Boolean = try {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Engine thread only. The state file for [prefix]: its name is a hash of everything the saved
     * state depends on.
     */
    private fun prefixCacheFile(prefix: String): File? {
        val dir = prefixCacheDir ?: return null
        if (prefix != cacheFilePrefix) {
            val digest = MessageDigest.getInstance("SHA-256")
            val parts = listOf(
                CACHE_FORMAT,
                llamaVersion,
                // CPU features of the build: other kernels give slightly different KV values
                systemInfo,
                modelFile.absolutePath,
                modelFile.length(),
                modelFile.lastModified(),
                config.contextSize,
                config.batchSize,
                config.repackWeights,
                prefix,
            )
            for (part in parts) {
                digest.update(part.toString().toByteArray())
                digest.update(0)
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            cacheFile = File(dir, hash + CACHE_FILE_SUFFIX)
            cacheFilePrefix = prefix
        }
        return cacheFile
    }

    /** Engine thread only. */
    private fun release() {
        if (!released) {
            released = true
            currentPrefix = null
            LlamaNative.free(handle)
        }
        freed.complete(Unit)
    }

    companion object {
        /** State files are about 112 KiB per prefix token for Qwen3-0.6B, so keep few. */
        private const val MAX_PREFIX_CACHE_FILES = 2
        private const val CACHE_FILE_SUFFIX = ".kvstate"
        private const val TMP_SUFFIX = ".tmp"
        /** Change when the state file contents change in a way llama.cpp can't detect. */
        private const val CACHE_FORMAT = "gar-llama-prefix-1"
        /** Grammar matching in llama.cpp is recursive, so give the engine thread a large stack. */
        private const val THREAD_STACK_SIZE = 8L shl 20

        private fun cancelledCompletion(promptTokens: Int, prefillMicros: Long) = Completion(
            text = "",
            promptTokens = promptTokens,
            cachedTokens = 0,
            generatedTokens = 0,
            prefillMillis = prefillMicros / 1000,
            generateMillis = 0,
            stopReason = StopReason.CANCELLED,
        )

        /**
         * Loads [modelFile] (GGUF) on the engine's own thread.
         * @param prefixCacheDir where to keep KV state files of evaluated prefixes, so the next
         * load can skip the prefill; null disables this
         * @param background the engine's first [LlamaEngine.background], which the load itself
         * runs at too
         * @throws LlamaException if the file can't be loaded
         */
        suspend fun load(
            modelFile: File,
            config: LlamaConfig = LlamaConfig(),
            prefixCacheDir: File? = null,
            background: Boolean = false,
        ): LlamaEngine {
            require(config.contextSize > 0 && config.threads > 0 && config.batchSize > 0) {
                "Invalid $config"
            }
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(null, runnable, "gar-llama", THREAD_STACK_SIZE).apply { isDaemon = true }
            }
            val abortFlag = ByteBuffer.allocateDirect(1)

            return suspendCancellableCoroutine { cont ->
                // aborts the native load through llama.cpp's progress callback
                cont.invokeOnCancellation { abortFlag.put(0, 1) }
                executor.execute {
                    try {
                        config.threadPriority?.apply(background)
                    } catch (e: Exception) {
                        // a priority is a hint
                    }
                    val engine = try {
                        open(executor, abortFlag, modelFile, config, prefixCacheDir).also {
                            it.background = background
                            it.appliedBackground = background.takeIf { config.threadPriority != null }
                        }
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
        private fun open(
            executor: ExecutorService,
            abortFlag: ByteBuffer,
            modelFile: File,
            config: LlamaConfig,
            prefixCacheDir: File?,
        ): LlamaEngine {
            if (!modelFile.isFile) {
                throw LlamaException("Model file not found: $modelFile")
            }
            try {
                LlamaNative.ensureLoaded()
            } catch (e: UnsatisfiedLinkError) {
                throw LlamaException("Can't load the llama.cpp library", e)
            }
            prefixCacheDir?.mkdirs()
            // left behind when the process died while writing one (each can be 100+ MB)
            prefixCacheDir?.listFiles { f -> f.name.endsWith(CACHE_FILE_SUFFIX + TMP_SUFFIX) }
                ?.forEach { it.delete() }

            val handle = LlamaNative.load(
                modelFile.absolutePath.toByteArray(),
                config.contextSize,
                config.threads,
                config.batchSize,
                config.repackWeights,
                abortFlag,
            )
            if (handle == 0L) {
                if (abortFlag.get(0) != 0.toByte()) {
                    throw CancellationException("Loading ${modelFile.name} was cancelled")
                }
                throw LlamaException("Can't load ${modelFile.name}: ${LlamaNative.lastError()}")
            }
            return LlamaEngine(
                executor = executor,
                handle = handle,
                abortFlag = abortFlag,
                modelFile = modelFile,
                config = config,
                prefixCacheDir = prefixCacheDir,
                llamaVersion = LlamaNative.version(),
                systemInfo = LlamaNative.systemInfo(),
            )
        }
    }
}

data class LlamaConfig(
    /**
     * Context size in tokens. The KV cache for all of it is allocated (and cleared, so it is
     * resident) at load time: at f16 that is 112 KiB per token for Qwen3-0.6B (448 MiB at 4096)
     * and 18 KiB for FunctionGemma 270M. Size it for the prompts actually used.
     */
    val contextSize: Int = 4096,
    val threads: Int = 4,
    /** Tokens per llama_decode call while evaluating prompts. */
    val batchSize: Int = 512,
    /**
     * Let llama.cpp repack weights into faster layouts at load time. Faster, but the repacked
     * weights live in anonymous memory next to the mmapped file instead of being reclaimable.
     */
    val repackWeights: Boolean = true,
    /**
     * Sets the engine thread's scheduling priority (see [LlamaEngine.background]); null leaves
     * it alone. The engine uses no `android.*` APIs, so Android passes one in.
     */
    val threadPriority: ThreadPriority? = null,
)

/** Sets the calling thread's priority: low while [background], else the normal one. */
fun interface ThreadPriority {
    fun apply(background: Boolean)
}

class LlamaException(message: String, cause: Throwable? = null) : Exception(message, cause)
