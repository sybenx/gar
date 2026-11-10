package org.stypox.dicio.gar.model

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import androidx.datastore.core.DataStore
import cc.aaro.gar.llm.LlamaConfig
import cc.aaro.gar.llm.LlamaEngine
import cc.aaro.gar.llm.ThreadPriority
import cc.aaro.gar.router.LlmRouter
import cc.aaro.gar.router.MediaControlRewrite
import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.engine.CompletionEngine
import cc.aaro.gar.router.format.FunctionGemmaFormat
import cc.aaro.gar.router.format.HermesFormat
import cc.aaro.gar.router.format.ModelFamily
import cc.aaro.gar.router.tools.ToolSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.stypox.dicio.MainActivity
import org.stypox.dicio.R
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.gar.GarFallbackImpl
import org.stypox.dicio.gar.GarRouterSource
import org.stypox.dicio.gar.call.garCallFilter
import org.stypox.dicio.gar.perf.GarMatchPriority
import org.stypox.dicio.gar.perf.garTraceSuspending
import org.stypox.dicio.gar.voicelog.GarVoiceLog
import org.stypox.dicio.gar.settings.GarModel
import org.stypox.dicio.gar.settings.GarModelFamily
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.tools.GarToolsProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A model file and how to prompt it. The file's size and modification time are part of it, so a
 * replaced file (imported or downloaded again) is a different selection.
 */
data class ModelSelection(
    val file: File,
    val family: ModelFamily,
    val length: Long = file.length(),
    val lastModified: Long = file.lastModified(),
) {
    /** Identifies the file contents for [GarLoadGuard]. */
    val id: String get() = "${file.absolutePath}|$length|$lastModified"
}

class LoadedModel(val engine: CompletionEngine, val router: LlmRouter)

/**
 * Whether [selection] can route, with the engine in [state] and [guardRefuses] telling whether
 * [GarLoadGuard] refuses to load it: not after its load failed (until the user retries).
 */
internal fun canRoute(
    state: GarEngineHolder.State<ModelSelection>,
    selection: ModelSelection,
    guardRefuses: () -> Boolean,
): Boolean = when (state) {
    // the guard's marker also counts the load that runs now
    is GarEngineHolder.State.Loaded -> state.key == selection || !guardRefuses()
    is GarEngineHolder.State.Loading -> state.key == selection || !guardRefuses()
    is GarEngineHolder.State.Failed -> state.key != selection && !guardRefuses()
    GarEngineHolder.State.NotLoaded -> !guardRefuses()
}

/**
 * Loads an engine whose context fits the prompt, with one load when [remembered] holds the size
 * the prompt needed last time. The size is only known by counting the prompt's tokens with a
 * loaded engine, and each load takes over a second on a phone: loading at the smallest size, then
 * again at the counted one, loaded the model twice at every start.
 *
 * @param load loads an engine with a context of the given size
 * @param contextNeeded the context the prompt needs, counted with the loaded engine; null if it
 * can't be counted, then the engine is kept as loaded
 * @param remember keeps a counted size that differs from [remembered] for the next load
 */
internal suspend fun <E> loadWithContextFor(
    remembered: Int?,
    load: suspend (contextSize: Int) -> E,
    contextNeeded: suspend (E) -> Int?,
    close: suspend (E) -> Unit,
    remember: (Int) -> Unit,
): E {
    val contextSize = maxOf(remembered ?: 0, GarModelManager.MIN_CONTEXT_SIZE)
    val engine = load(contextSize)
    val needed = try {
        contextNeeded(engine)
    } catch (t: Throwable) {
        close(engine)
        throw t
    } ?: return engine
    if (needed != remembered) {
        remember(needed)
    }
    if (needed <= contextSize) {
        // also after the prompt got shorter: the next load uses the smaller size
        return engine
    }
    close(engine)
    return load(needed)
}

/**
 * The context size the last loaded model needed ([loadWithContextFor]), in [file]. Only one model
 * is kept: switching models is rare and costs one more load.
 */
internal class GarContextSizes(private val file: File) {
    /** The size remembered for [model] (its file and prompt family), or null. */
    fun read(model: String): Int? = try {
        val lines = file.readLines()
        lines.getOrNull(1)?.toIntOrNull()?.takeIf { lines[0] == model && it > 0 }
    } catch (e: IOException) {
        null // nothing remembered yet
    }

    fun write(model: String, contextSize: Int) {
        try {
            file.writeText("$model\n$contextSize\n")
        } catch (e: IOException) {
            // the next load counts again
        }
    }
}

/** What the settings screen shows about the selected model. */
sealed interface GarModelState {
    data object NotDownloaded : GarModelState
    /** @param fraction 0..1, or null if the size is unknown */
    data class Downloading(val fraction: Float?) : GarModelState
    data object Importing : GarModelState
    data object Ready : GarModelState
    data object Loading : GarModelState
    data object Loaded : GarModelState
    /** @param canRetry loading failed, [GarModelManager.retryLoad] tries again */
    data class Error(val message: String, val canRetry: Boolean = false) : GarModelState
}

sealed interface GarTransferState {
    data object Idle : GarTransferState
    data class Running(val model: GarModel, val currentBytes: Long, val totalBytes: Long) :
        GarTransferState
    data class Failed(val model: GarModel, val message: String) : GarTransferState
}

/**
 * Owns GAR's model files (download, import, delete) and the loaded model.
 *
 * The model is loaded while Dicio's [MainActivity] is started (configuration changes don't count),
 * so loading overlaps with listening, and unloaded [GRACE_MILLIS] after it stops (only
 * [SHORT_GRACE_MILLIS] if the process may be frozen meanwhile) or right away on memory pressure. Without the activity (wake word), [route] loads the model on demand. The
 * activity callbacks are registered when Hilt creates this singleton, which happens while
 * MainActivity is injected (it needs the SkillEvaluator, which needs GarFallback, which needs
 * this), so before its first onStart. That keeps upstream files untouched.
 */
@Singleton
class GarModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: DataStore<GarSettings>,
    httpClient: OkHttpClient,
    private val toolsProvider: GarToolsProvider,
    private val skillContext: SkillContextInternal,
) : GarRouterSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modelsDir = File(context.filesDir, MODELS_DIR)
    private val prefixCacheDir = File(context.cacheDir, PREFIX_CACHE_DIR)
    private val downloader = GarModelDownloader(httpClient)

    private val contextSizes = GarContextSizes(File(context.cacheDir, CONTEXT_SIZE_FILE))

    private val holder = GarEngineHolder<ModelSelection, LoadedModel>(
        scope = scope,
        graceMillis = GRACE_MILLIS,
        load = ::loadModel,
        close = { it.engine.close() },
        onCloseError = { Log.w(TAG, "Could not close the model", it) },
        shortGraceMillis = SHORT_GRACE_MILLIS,
        keepLonger = ::isProcessKeptRunning,
    )
    private val loadGuard = GarLoadGuard(File(context.filesDir, LOAD_MARKER_FILE))

    private val _downloadState = MutableStateFlow<GarTransferState>(GarTransferState.Idle)
    val downloadState: StateFlow<GarTransferState> = _downloadState
    private var downloadJob: Job? = null

    private val _importState = MutableStateFlow<GarTransferState>(GarTransferState.Idle)
    val importState: StateFlow<GarTransferState> = _importState
    private var importJob: Job? = null

    // model files only change through this class, which bumps this to re-check them
    private val filesVersion = MutableStateFlow(0)
    /** Changes whenever model files (also partial downloads) were written or deleted. */
    val filesChanged: StateFlow<Int> = filesVersion

    val modelState: Flow<GarModelState> = combine(
        settings.data,
        _downloadState,
        _importState,
        holder.state,
        filesVersion,
    ) { currentSettings, download, import, engine, _ ->
        modelState(currentSettings, download, import, engine)
    }

    init {
        (context.applicationContext as? Application)?.let { app ->
            val activityCallbacks = MainActivityCallbacks()
            app.registerActivityLifecycleCallbacks(activityCallbacks)
            app.registerComponentCallbacks(MemoryCallbacks())
            // in case Hilt created this after MainActivity had already started
            Handler(Looper.getMainLooper()).post { activityCallbacks.acquireIfStarted() }
        }

        scope.launch {
            // follow settings changes: load the newly selected model while the UI is open, or
            // free memory when the model is turned off
            combine(settings.data, filesVersion) { s, _ -> Pair(s.llmEnabled, selectionFor(s)) }
                .distinctUntilChanged()
                .collect { (enabled, selection) ->
                    if (!enabled || selection == null) {
                        holder.unload()
                    } else {
                        preloadIfUseful()
                    }
                }
        }
    }

    /** Keeps the model loaded (loading it if possible) until [release]. */
    fun acquire() {
        holder.acquire()
        scope.launch { preloadIfUseful() }
    }

    /**
     * Like [acquire], but only once [view] has drawn its first frame and the main thread is idle
     * after it (or [IDLE_DEADLINE_MILLIS] after that frame at the latest), so that loading
     * doesn't compete with a screen's first frames (gar/DESIGN.md, "Frame rate"). Main thread. Every call is its own hold: close the returned handle exactly
     * once (not [release]). Closing it before it acquired cancels it, closing it after ends the
     * hold, and closing it again does nothing.
     */
    fun acquireAfterFirstFrame(view: View): AutoCloseable =
        AfterFirstFrame(view).also { it.start() }

    /** Ends an [acquire]. */
    fun release() {
        holder.release()
    }

    /**
     * Waits for [view]'s next draw, then for the next frame (so that one is on screen) and for the
     * main thread to be idle, then acquires, unless [close] came first.
     */
    private inner class AfterFirstFrame(val view: View) :
        ViewTreeObserver.OnPreDrawListener, AutoCloseable {
        private var observer: ViewTreeObserver? = null
        private var acquired = false // guarded by this
        private var closed = false // guarded by this

        fun start() {
            observer = view.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            view.invalidate() // a view already on screen may not draw again by itself
        }

        override fun onPreDraw(): Boolean {
            removeListener()
            Choreographer.getInstance().postFrameCallback {
                Looper.myQueue().addIdleHandler {
                    fire()
                    false
                }
                // a main thread that never goes idle (the sheet's level meter while listening,
                // a phone under memory pressure) must not keep the model from loading: the
                // 2026-11-05 sheet turn waited 17 s for a load that started late
                view.postDelayed(::fire, IDLE_DEADLINE_MILLIS)
            }
            return true
        }

        // the idle handler or the deadline, whichever comes first
        private fun fire() {
            val due = synchronized(this) {
                (!closed && !acquired).also { if (it) acquired = true }
            }
            if (due) {
                acquire()
            }
        }

        override fun close() {
            val wasAcquired = synchronized(this) {
                if (closed) {
                    return
                }
                closed = true
                acquired
            }
            if (wasAcquired) {
                release()
            } else {
                // still waiting: it will never acquire, and needs no draw anymore
                view.post { removeListener() }
            }
        }

        private fun removeListener() {
            // the observer may have been replaced when the view was attached
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            view.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        }
    }

    override fun isModelAvailable(settings: GarSettings): Boolean = selectionFor(settings) != null

    override fun canRoute(settings: GarSettings): Boolean {
        val selection = selectionFor(settings) ?: return false
        return canRoute(holder.state.value, selection) { loadGuard.refuses(selection.id) }
    }

    override fun isLoaded(settings: GarSettings): Boolean {
        val selection = selectionFor(settings) ?: return false
        return holder.state.value == GarEngineHolder.State.Loaded(selection)
    }

    override suspend fun route(
        settings: GarSettings,
        utterance: String,
        tools: List<ToolSpec>,
    ): RouterOutput {
        val selection = selectionFor(settings)
            ?: return RouterOutput.Failed("No model file for ${settings.model}")
        holder.acquire()
        try {
            // a load is waited for LOAD_WAIT_MILLIS at most: past that the command gets the text
            // fallback rather than seconds of silence, and the load goes on in the holder's
            // scope for the next command (gar/DESIGN.md, "Model lifecycle and settings")
            if (holder.state.value != GarEngineHolder.State.Loaded(selection)) {
                val loading = holder.preload(selection)
                if (withTimeoutOrNull(LOAD_WAIT_MILLIS) { loading.join() } == null) {
                    return RouterOutput.Failed("The model is still loading")
                }
            }
            return holder.use(selection) {
                // evaluate the tool declarations first if they changed or the warm-up at load time
                // was skipped, so the router's timeout only covers this utterance (a timeout in
                // the middle of the prefix would throw the evaluated part away)
                it.router.warmUp(tools)
                it.router.route(utterance, tools)
            }
        } finally {
            holder.release()
        }
    }

    /**
     * Loads the selected model again after it failed or stopped the app while loading
     * ([GarLoadGuard]), e.g. when the user taps the error in the settings.
     */
    fun retryLoad() {
        scope.launch {
            withContext(Dispatchers.IO) { loadGuard.clear() }
            holder.retry()
            preloadIfUseful()
        }
    }

    fun fileFor(setting: GarModel): File =
        File(modelsDir, ModelCatalog.forSetting(setting)?.fileName ?: CUSTOM_MODEL_FILE)

    /** The selected model file and family, or null if the file isn't on disk. */
    fun selectionFor(settings: GarSettings): ModelSelection? {
        val file = if (settings.model == GarModel.GAR_MODEL_CUSTOM) {
            fileFor(GarModel.GAR_MODEL_CUSTOM)
        } else {
            File(modelsDir, (ModelCatalog.forSetting(settings.model) ?: ModelCatalog.QWEN3_0_6B).fileName)
        }
        return if (file.isFile) ModelSelection(file, settings.selectedModelFamily()) else null
    }

    /** Starts downloading [model], unless a download is already running. */
    fun download(model: DownloadableModel) {
        val previous = downloadJob
        if (previous?.isActive == true) {
            return
        }
        downloadJob = scope.launch {
            // a cancelled download may still be closing its .part file, never write it twice
            previous?.join()
            if (model.termsUrl != null && !settings.data.first().functionGemmaTermsAccepted) {
                Log.w(TAG, "Not downloading ${model.displayName}: terms not accepted")
                return@launch
            }

            _downloadState.value = GarTransferState.Running(model.setting, 0, model.sizeBytes)
            var lastPermille = -1L
            try {
                downloader.download(
                    url = model.url,
                    target = File(modelsDir, model.fileName),
                    expectedSize = model.sizeBytes,
                    expectedSha256 = model.sha256,
                ) { current, total ->
                    // reads are a few KB each: only publish visible progress
                    val permille = if (total > 0) current * 1000 / total else current
                    if (permille != lastPermille) {
                        lastPermille = permille
                        _downloadState.value =
                            GarTransferState.Running(model.setting, current, total)
                    }
                }
                _downloadState.value = GarTransferState.Idle
            } catch (e: CancellationException) {
                _downloadState.value = GarTransferState.Idle
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Could not download ${model.displayName}", t)
                _downloadState.value = GarTransferState.Failed(
                    model.setting,
                    context.getString(
                        if ((t as? GarDownloadException)?.damaged == true) {
                            R.string.gar_error_download_damaged
                        } else {
                            R.string.gar_error_download_failed
                        }
                    ),
                )
            } finally {
                filesVersion.update { it + 1 }
            }
        }
    }

    /** Stops the download; the partial file is kept, so the next download resumes. */
    fun cancelDownload() {
        // the job is kept, so that the next download waits for it to finish
        downloadJob?.cancel()
        _downloadState.value = GarTransferState.Idle
    }

    /** Deletes the file of [setting] (and any partial download of it). */
    fun delete(setting: GarModel) {
        scope.launch {
            holder.unload()
            withContext(Dispatchers.IO) {
                val file = fileFor(setting)
                file.delete()
                GarModelDownloader.partFile(file).delete()
            }
            if (_downloadState.value.let { it is GarTransferState.Failed && it.model == setting }) {
                _downloadState.value = GarTransferState.Idle
            }
            filesVersion.update { it + 1 }
        }
    }

    /**
     * Copies a GGUF file picked by the user into GAR's model folder and selects it. Files that
     * don't start with the GGUF magic are rejected.
     */
    fun importCustom(uri: Uri, family: GarModelFamily) {
        if (importJob?.isActive == true) {
            return // two imports would write the same .part file
        }
        importJob = scope.launch {
            val model = GarModel.GAR_MODEL_CUSTOM
            _importState.value = GarTransferState.Running(model, 0, 0)
            try {
                val displayName = withContext(Dispatchers.IO) {
                    holder.unload()
                    copyGguf(uri, fileFor(model))
                    // the new file has the same path as the old one: drop an engine that may have
                    // been loaded from the old file during the copy
                    holder.unload()
                    queryDisplayName(uri)
                }
                settings.updateData {
                    it.toBuilder()
                        .setModel(model)
                        .setCustomModelFamily(family)
                        .setCustomModelName(displayName ?: "")
                        .build()
                }
                _importState.value = GarTransferState.Idle
            } catch (e: CancellationException) {
                _importState.value = GarTransferState.Idle
                throw e
            } catch (e: NotGgufException) {
                _importState.value =
                    GarTransferState.Failed(model, context.getString(R.string.gar_import_not_gguf))
            } catch (e: ImportRejectedException) {
                Log.w(TAG, "Model not imported", e)
                _importState.value = GarTransferState.Failed(model, context.getString(e.problem.message))
            } catch (t: Throwable) {
                Log.e(TAG, "Could not import model", t)
                _importState.value =
                    GarTransferState.Failed(model, context.getString(R.string.gar_error_import_failed))
            } finally {
                filesVersion.update { it + 1 }
            }
        }
    }

    private fun copyGguf(uri: Uri, target: File) {
        target.parentFile?.mkdirs()
        val totalMemory = totalMemory()
        // the old file is replaced, so its space counts as free
        val freeSpace = modelsDir.usableSpace + target.length()
        querySize(uri)?.let { size ->
            importProblem(size, totalMemory, freeSpace)?.let { throw ImportRejectedException(it) }
        }

        val part = GarModelDownloader.partFile(target)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open $uri")
        try {
            input.use { stream ->
                val header = readHeader(stream, GGUF_MAGIC.size)
                if (!isGguf(header)) {
                    throw NotGgufException()
                }
                part.outputStream().use { output ->
                    output.write(header)
                    stream.copyTo(output)
                }
            }
            // the provider may not have said how big the file is
            importProblem(part.length(), totalMemory, Long.MAX_VALUE)
                ?.let { throw ImportRejectedException(it) }
            target.delete()
            if (!part.renameTo(target)) {
                throw IOException("Cannot rename $part to $target")
            }
        } finally {
            part.delete()
        }
    }

    private fun querySize(uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
            ?.takeIf { it > 0 }
    } catch (e: Exception) {
        null
    }

    private fun totalMemory(): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return info.totalMem
    }

    /**
     * Whether the process stays unfrozen after the UI is gone, so a timer in it keeps running:
     * true while the assistant service is bound or the wake word service runs, false once the
     * process is cached (Android freezes cached processes after about 10 seconds).
     */
    private fun isProcessKeptRunning(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** Size of the model file of [setting] on disk, or null if there is none. */
    fun fileSize(setting: GarModel): Long? = fileFor(setting).takeIf { it.isFile }?.length()

    /** Size of an unfinished download of [setting], or null if there is none. */
    fun partialDownloadSize(setting: GarModel): Long? =
        GarModelDownloader.partFile(fileFor(setting)).takeIf { it.isFile }?.length()

    private suspend fun preloadIfUseful() {
        if (!holder.isAcquired || skillContext.sentencesLanguage != GarFallbackImpl.ENGLISH) {
            return
        }
        val currentSettings = settings.data.first()
        if (!currentSettings.llmEnabled) {
            return
        }
        selectionFor(currentSettings)?.let { holder.preload(it) }
    }

    private suspend fun loadModel(selection: ModelSelection): LoadedModel =
        garTraceSuspending("Gar:loadModel") { loadModelTraced(selection) }

    private suspend fun loadModelTraced(selection: ModelSelection): LoadedModel =
        withContext(Dispatchers.IO) {
            // at startup Dicio may still be building its skills: wait for them, so that the
            // warm-up evaluates the real prompt and the first command doesn't pay for it. Before
            // the load lock, which speech recognition may be waiting for.
            val tools = toolsProvider.awaitOfferedTools(skillContext, SKILLS_READY_TIMEOUT_MILLIS)
                .map { it.tool }
            // a command being matched gets the CPU first (gar/DESIGN.md, "Routing speed")
            GarMatchPriority.awaitNoMatch()
            // one native load at a time, so the guard blames the model that was loading
            GarNativeLoadLock.shared.withLanguageModelLoad {
                // throws if this file stopped the app while loading before
                loadGuard.begin(selection.id)
                GarMatchPriority.modelLoading = true
                try {
                    garTraceSuspending("Gar:loadAndWarmUp") { loadAndWarmUp(selection, tools) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not load ${selection.file}", t)
                    throw t
                } finally {
                    GarMatchPriority.modelLoading = false
                    // not reached when llama.cpp aborts the process or the system kills it
                    loadGuard.end()
                }
            }
        }

    private suspend fun loadAndWarmUp(selection: ModelSelection, tools: List<ToolSpec>): LoadedModel {
        prefixCacheDir.mkdirs()
        val format = when (selection.family) {
            ModelFamily.HERMES -> HermesFormat()
            ModelFamily.FUNCTION_GEMMA -> FunctionGemmaFormat()
        }
        // sized for every tool: the offered ones may grow after loading (they are empty while
        // Dicio still builds its skills at startup), and the prefix must always fit
        val sizingTools = toolsProvider.allTools().ifEmpty { tools }
        val engine = loadEngine(
            selection, sizingTools.takeIf { it.isNotEmpty() }?.let(format::staticPrefix)
        )
        val router = LlmRouter(
            engine, format, maxTokens = ROUTER_MAX_TOKENS, filter = garCallFilter(skillContext),
            // the router's default rewrite, recorded in the voice log when it changes a call
            rewrite = GarVoiceLog.watchRewrite(MediaControlRewrite),
        )
        try {
            // evaluate the tool declarations now, so the first command only pays for itself
            if (tools.isNotEmpty()) {
                GarMatchPriority.awaitNoMatch()
                router.warmUp(tools)
            }
        } catch (e: CancellationException) {
            engine.close()
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Could not warm up the model", t)
        }
        // loaded and warmed up at a low priority: commands for the model run at the normal one
        engine.background = false
        return LoadedModel(engine, router)
    }

    /**
     * Loads the engine with a context just big enough for [prefix] (the tool declarations) plus
     * an utterance and the answer: the KV cache for the whole context is allocated and cleared
     * at load time, 112 KiB per token for Qwen3-0.6B. Counting the prefix's tokens needs a loaded
     * engine, so the size this model needed last time is loaded right away
     * ([loadWithContextFor]); only a new model or a longer prefix loads twice.
     */
    private suspend fun loadEngine(selection: ModelSelection, prefix: String?): LlamaEngine {
        val model = "${selection.id}|${selection.family}"
        return loadWithContextFor(
            remembered = contextSizes.read(model),
            load = { contextSize ->
                Log.i(TAG, "Loading ${selection.file.name} with a context of $contextSize tokens")
                LlamaEngine.load(
                    selection.file,
                    LlamaConfig(
                        contextSize = contextSize,
                        threadPriority = ThreadPriority(GarMatchPriority::applyModelPriority),
                    ),
                    prefixCacheDir,
                    background = true,
                )
            },
            contextNeeded = { engine ->
                try {
                    prefix?.let { contextSizeFor(engine.countTokens(it, addSpecial = true)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not count the prompt's tokens", t)
                    null // routing will report the problem
                }
            },
            close = { it.close() },
            remember = { contextSizes.write(model, it) },
        )
    }

    private fun modelState(
        currentSettings: GarSettings,
        download: GarTransferState,
        import: GarTransferState,
        engine: GarEngineHolder.State<ModelSelection>,
    ): GarModelState {
        val model = currentSettings.model
        if (import is GarTransferState.Running && model == import.model) {
            return GarModelState.Importing
        } else if (import is GarTransferState.Failed && model == import.model) {
            return GarModelState.Error(import.message)
        } else if (download is GarTransferState.Running && download.model == model) {
            return GarModelState.Downloading(
                download.totalBytes.takeIf { it > 0 }
                    ?.let { download.currentBytes.toFloat() / it }
            )
        } else if (download is GarTransferState.Failed && download.model == model) {
            return GarModelState.Error(download.message)
        }

        val selection = selectionFor(currentSettings) ?: return GarModelState.NotDownloaded
        return when (engine) {
            is GarEngineHolder.State.Loading ->
                if (engine.key == selection) GarModelState.Loading else GarModelState.Ready
            is GarEngineHolder.State.Loaded ->
                if (engine.key == selection) GarModelState.Loaded else GarModelState.Ready
            is GarEngineHolder.State.Failed ->
                if (engine.key == selection) {
                    GarModelState.Error(
                        context.getString(
                            if (engine.throwable is ModelCrashedException) {
                                R.string.gar_error_load_crashed
                            } else {
                                R.string.gar_error_load_failed
                            }
                        ),
                        canRetry = true,
                    )
                } else {
                    GarModelState.Ready
                }
            GarEngineHolder.State.NotLoaded -> GarModelState.Ready
        }
    }

    /**
     * Holds the model while at least one MainActivity is started. There can be two, e.g. one in
     * the assistant's task and one in the launcher's. Only touched on the main thread.
     */
    private inner class MainActivityCallbacks : Application.ActivityLifecycleCallbacks {
        private val started = HashSet<Activity>()
        // stopped for a configuration change: their recreated instance takes over their place
        private var recreating = 0
        private var holding = false

        fun acquireIfStarted() {
            // Hilt created this singleton after a MainActivity had already started
            if (MainActivity.isInForeground > 0 && !holding) {
                holding = true
                acquire()
            }
        }

        override fun onActivityStarted(activity: Activity) {
            // the assist intent and the launcher both start MainActivity, so this also starts
            // loading as soon as the assistant is invoked
            if (activity !is MainActivity) {
                return
            }
            if (recreating > 0) {
                recreating -= 1
            }
            started.add(activity)
            if (!holding) {
                holding = true
                acquire()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity !is MainActivity) {
                return
            }
            if (!started.remove(activity) && started.isNotEmpty()) {
                // started before these callbacks existed (see acquireIfStarted), others still hold
                return
            }
            if (activity.isChangingConfigurations) {
                // a recreated activity (rotation, theme, locale) keeps the model
                recreating += 1
            } else if (started.isEmpty() && recreating == 0 && holding) {
                holding = false
                release()
            }
        }

        override fun onActivityResumed(activity: Activity) {
            // an assist intent reaching the visible activity only resumes it: retry a load that
            // was skipped (does nothing when the model is already loaded or failed to load)
            if (activity is MainActivity && holding) {
                scope.launch { preloadIfUseful() }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private inner class MemoryCallbacks : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION") // still delivered, and the threshold we want
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                scope.launch { holder.unloadIfIdle() }
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
            scope.launch { holder.unloadIfIdle() }
        }
    }

    private class NotGgufException : IOException("Not a GGUF file")

    enum class ImportProblem(val message: Int) {
        TOO_LARGE(R.string.gar_import_too_large),
        NO_SPACE(R.string.gar_import_no_space),
    }

    private class ImportRejectedException(val problem: ImportProblem) :
        IOException("Import rejected: $problem")

    companion object {
        private const val TAG = "GarModelManager"
        const val MODELS_DIR = "gar-models"
        const val PREFIX_CACHE_DIR = "gar-prefix"
        const val CUSTOM_MODEL_FILE = "custom.gguf"
        const val GRACE_MILLIS = 30_000L
        /** Shorter than the ~10 s after which Android freezes a cached process. */
        const val SHORT_GRACE_MILLIS = 6_000L
        private const val LOAD_MARKER_FILE = "gar-loading"
        private const val CONTEXT_SIZE_FILE = "gar-context-size"

        const val ROUTER_MAX_TOKENS = 96
        /** Tokens of an utterance with the chat template around it (typed text can be long). */
        const val SUFFIX_TOKEN_BUDGET = 256
        /**
         * Enough for the 12 tools before Step 3 (a prefix of about 1000 tokens with Hermes, 770
         * with FunctionGemma); the 26 tools of Step 3 take about 2270 with Hermes, so their
         * context is sized by [contextSizeFor].
         */
        const val MIN_CONTEXT_SIZE = 2048

        /** How long loading waits for Dicio's skills before warming up with what is offered. */
        const val SKILLS_READY_TIMEOUT_MILLIS = 30_000L

        /**
         * How long a command waits for the model to load before the text fallback. A calm load
         * takes about 1.3 s plus the warm-up; one under memory pressure took 17 s on 2026-11-05.
         */
        const val LOAD_WAIT_MILLIS = 8_000L

        /**
         * The latest [acquireAfterFirstFrame] acquires after the first frame without an idle
         * main thread, as the sheet's own deadline for its singletons.
         */
        private const val IDLE_DEADLINE_MILLIS = 300L
        private const val CONTEXT_STEP = 256

        /** Imported models may use at most this fraction of the phone's RAM. */
        private const val MAX_MODEL_MEMORY_FRACTION = 4
        private const val FREE_SPACE_MARGIN = 64L shl 20

        /** The context a prefix of [prefixTokens] tokens needs, see [loadEngine]. */
        fun contextSizeFor(prefixTokens: Int): Int {
            val needed = prefixTokens + SUFFIX_TOKEN_BUDGET + ROUTER_MAX_TOKENS
            val rounded = (needed + CONTEXT_STEP - 1) / CONTEXT_STEP * CONTEXT_STEP
            return maxOf(MIN_CONTEXT_SIZE, rounded)
        }

        /**
         * Why a model file of [size] bytes can't be imported, or null if it can: the weights end
         * up in memory, and the system kills the app (and the assistant with it) when they take
         * too much of it.
         */
        fun importProblem(size: Long, totalMemory: Long, freeSpace: Long): ImportProblem? = when {
            totalMemory > 0 && size > totalMemory / MAX_MODEL_MEMORY_FRACTION -> ImportProblem.TOO_LARGE
            size > freeSpace - FREE_SPACE_MARGIN -> ImportProblem.NO_SPACE
            else -> null
        }

        private val GGUF_MAGIC = "GGUF".toByteArray(Charsets.US_ASCII)

        fun isGguf(header: ByteArray): Boolean =
            header.size >= GGUF_MAGIC.size && GGUF_MAGIC.indices.all { header[it] == GGUF_MAGIC[it] }

        /** Reads up to [count] bytes, fewer only at the end of the stream. */
        fun readHeader(input: InputStream, count: Int): ByteArray {
            val buffer = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(buffer, read, count - read)
                if (n == -1) {
                    break
                }
                read += n
            }
            return buffer.copyOf(read)
        }
    }
}
