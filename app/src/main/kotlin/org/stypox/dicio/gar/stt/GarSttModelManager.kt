package org.stypox.dicio.gar.stt

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.datastore.core.DataStore
import cc.aaro.gar.router.SpokenForm
import cc.aaro.gar.stt.Transcriber
import cc.aaro.gar.stt.WhisperEngine
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.stypox.dicio.MainActivity
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.gar.GarFallbackImpl
import org.stypox.dicio.gar.model.GarDownloadException
import org.stypox.dicio.gar.model.GarEngineHolder
import org.stypox.dicio.gar.model.GarLoadGuard
import org.stypox.dicio.gar.model.GarModelDownloader
import org.stypox.dicio.gar.model.GarNativeLoadLock
import org.stypox.dicio.gar.model.ModelCrashedException
import org.stypox.dicio.gar.model.PreviousLoadFailedException
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.settings.GarSttModel
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.util.toStateFlowDistinctBlockingFirst
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/**
 * A speech model file on disk. Its size and modification time are part of it, so a replaced file
 * is a different selection (for [GarEngineHolder] and [GarLoadGuard]).
 */
data class SttSelection(
    val file: File,
    val length: Long = file.length(),
    val lastModified: Long = file.lastModified(),
) {
    /** Identifies the file contents for [GarLoadGuard]. */
    val id: String get() = "${file.absolutePath}|$length|$lastModified"
}

/** A loaded speech model and how to free it. */
class LoadedStt(val transcriber: Transcriber, val close: suspend () -> Unit)

/** What GAR's settings show about speech recognition. */
sealed interface GarSttState {
    data object NotDownloaded : GarSttState

    /**
     * @param model the model being downloaded, which may not be the selected one yet (a switch)
     * @param fraction 0..1, or null if the size is unknown
     */
    data class Downloading(val model: GarSttModel, val fraction: Float?) : GarSttState

    /** @param damaged the file arrived corrupted (wrong checksum or size), not just cut off */
    data class DownloadFailed(val model: GarSttModel, val damaged: Boolean) : GarSttState
    data object Ready : GarSttState
    data object Loading : GarSttState
    data object Loaded : GarSttState

    /** @param crashed loading stopped the app ([GarLoadGuard]); a tap retries either way */
    data class LoadFailed(val crashed: Boolean) : GarSttState
}

/**
 * Owns the speech model files (download, switch, delete) and the loaded Whisper model, for the
 * Whisper input device ([SttModels]) and GAR's settings.
 *
 * Files live in `filesDir/gar-stt/`: the selected model and the Silero VAD file. Only one model is
 * kept on disk: switching downloads and verifies the new model first, then selects it, unloads the
 * old one and deletes its file, so a failed switch leaves speech recognition as it was.
 *
 * The model is loaded while Dicio's [MainActivity] is started (if Whisper is the input device, so
 * loading overlaps with the user starting to talk), and by the device at each capture start
 * ([acquire]). It is unloaded [GRACE_MILLIS] after the last release ([SHORT_GRACE_MILLIS] if the
 * process may be frozen meanwhile) or right away on memory pressure. The activity and memory
 * callbacks are registered when Hilt creates this singleton, which happens while MainActivity is
 * injected (it needs the input device wrapper, which needs this).
 */
@Singleton
class GarSttModelManager internal constructor(
    private val scope: CoroutineScope,
    private val settings: DataStore<GarSettings>,
    private val dir: File,
    loadMarker: File,
    /** Downloads and verifies a file into the given target (see [GarModelDownloader.download]). */
    private val fetch: suspend (SttDownload, File, (current: Long, total: Long) -> Unit) -> Unit,
    private val loadEngine: suspend (File) -> LoadedStt,
    /** Whether Whisper is the input device to load ahead of time, for [onUiStarted]. */
    private val wanted: Flow<Boolean> = flowOf(true),
    keepLonger: () -> Boolean = { true },
    private val io: CoroutineContext = Dispatchers.IO,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    /** Shared with the language model, see [GarNativeLoadLock]. */
    private val loadLock: GarNativeLoadLock = GarNativeLoadLock(),
    // GAR contact names: the prompt's names, and what refreshes them at each capture
    override val promptNames: StateFlow<List<String>> = MutableStateFlow(emptyList()),
    private val refreshPromptNames: () -> Unit = {},
) : SttModels {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: DataStore<GarSettings>,
        userSettings: DataStore<UserSettings>,
        localeManager: LocaleManager,
        httpClient: OkHttpClient,
        promptNames: GarSttPromptNames,
    ) : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        settings = settings,
        dir = File(context.filesDir, MODELS_DIR),
        loadMarker = File(context.filesDir, LOAD_MARKER_FILE),
        fetch = downloadWith(GarModelDownloader(httpClient)),
        loadEngine = ::loadWhisper,
        wanted = combine(userSettings.data, localeManager.sentencesLanguage) { user, language ->
            whisperWanted(user.inputDevice, language)
        },
        keepLonger = ::isProcessKeptRunning,
        log = { message, t -> Log.w(TAG, message, t) },
        loadLock = GarNativeLoadLock.shared,
        promptNames = promptNames.names,
        refreshPromptNames = promptNames::refreshIfStale,
    ) {
        (context.applicationContext as? Application)?.let { app ->
            val activityCallbacks = MainActivityCallbacks()
            app.registerActivityLifecycleCallbacks(activityCallbacks)
            app.registerComponentCallbacks(MemoryCallbacks())
            // in case Hilt created this after MainActivity had already started
            Handler(Looper.getMainLooper()).post { activityCallbacks.acquireIfStarted() }
        }
    }

    private val settingsState: StateFlow<GarSettings> =
        settings.data.toStateFlowDistinctBlockingFirst(scope)

    private val loadGuard = GarLoadGuard(loadMarker)

    private val holder = GarEngineHolder<SttSelection, LoadedStt>(
        scope = scope,
        graceMillis = GRACE_MILLIS,
        load = ::loadSelection,
        close = { it.close() },
        onCloseError = { log("Could not close the speech model", it) },
        shortGraceMillis = SHORT_GRACE_MILLIS,
        keepLonger = keepLonger,
    )

    // files only change through this class, which bumps this to re-check them
    private val filesVersion = MutableStateFlow(0)

    /** Changes whenever speech files (also partial downloads) were written or deleted. */
    val filesChanged: StateFlow<Int> = filesVersion

    // before _installed, which reads it
    override val vadFile: File = fileFor(SttModelCatalog.SILERO_VAD)

    private val _loadGeneration = MutableStateFlow(0)
    override val loadGeneration: StateFlow<Int> = _loadGeneration

    private val _installed = MutableStateFlow(selectionFor(settingsState.value) != null)
    override val installed: StateFlow<Boolean> = _installed

    private val _transfer = MutableStateFlow<SttTransfer>(SttTransfer.Idle)
    override val transfer: StateFlow<SttTransfer> = _transfer

    // the model the running or failed download is for (it becomes the selected one when done)
    private val _transferModel = MutableStateFlow<GarSttModel?>(null)
    val transferModel: StateFlow<GarSttModel?> = _transferModel
    private var downloadJob: Job? = null // only touched under downloadLock
    private val downloadLock = Any()

    /** What GAR's settings show, see [sttState]. */
    val state: Flow<GarSttState> = combine(
        settingsState, _transfer, _transferModel, holder.state, filesVersion,
    ) { currentSettings, transfer, transferModel, engine, _ ->
        sttState(selectionFor(currentSettings), transfer, transferModel, engine)
    }

    init {
        scope.launch {
            // follow the files and the setting: free a model that is gone or replaced, load the
            // new one while someone holds it
            combine(settingsState, filesVersion) { s, _ -> selectionFor(s) }
                .distinctUntilChanged()
                .collect { selection ->
                    _installed.value = selection != null
                    _loadGeneration.update { it + 1 }
                    if (selection == null) {
                        holder.unload()
                    } else if (holder.isAcquired) {
                        holder.preload(selection)
                    }
                }
        }
    }

    fun fileFor(download: SttDownload): File = File(dir, download.fileName)

    /** The selected model as it is on disk, or null while it or the VAD file is missing. */
    fun selectionFor(settings: GarSettings): SttSelection? {
        val model = fileFor(SttModelCatalog.forSetting(settings.sttModel))
        return if (model.isFile && vadFile.isFile) SttSelection(model) else null
    }

    /** Bytes on disk of [model] and the VAD file, or null if the model isn't there. */
    fun fileSize(model: GarSttModel): Long? =
        fileFor(SttModelCatalog.forSetting(model)).takeIf { it.isFile }
            ?.let { it.length() + (vadFile.takeIf { vad -> vad.isFile }?.length() ?: 0L) }

    /** Bytes of unfinished downloads of [model] and the VAD file, or null if there are none. */
    fun partialDownloadSize(model: GarSttModel): Long? =
        listOf(SttModelCatalog.forSetting(model), SttModelCatalog.SILERO_VAD)
            .map { GarModelDownloader.partFile(fileFor(it)) }
            .filter { it.isFile }
            .takeIf { it.isNotEmpty() }
            ?.sumOf { it.length() }

    override fun download() = startDownload(settingsState.value.sttModel, replaceRunning = false)

    /**
     * Makes [model] the speech model: downloads it (and the VAD file) if needed, then selects it
     * and deletes the other model. Replaces a running download of another model; a failed one
     * keeps the selected model and its file.
     */
    fun switchTo(model: GarSttModel) = startDownload(model, replaceRunning = true)

    private fun startDownload(target: GarSttModel, replaceRunning: Boolean) {
        synchronized(downloadLock) {
            val previous = downloadJob
            if (previous?.isActive == true) {
                if (!replaceRunning || _transferModel.value == target) {
                    return
                }
                previous.cancel()
            }
            downloadJob = scope.launch {
                // a cancelled download may still be closing its .part file, never write it twice
                previous?.join()
                if (downloadMissing(target)) {
                    select(target)
                }
            }
        }
    }

    /** @return whether the files of [target] are all on disk now */
    private suspend fun downloadMissing(target: GarSttModel): Boolean {
        val missing = listOf(SttModelCatalog.forSetting(target), SttModelCatalog.SILERO_VAD)
            .filter { !fileFor(it).isFile }
        if (missing.isEmpty()) {
            return true
        }

        val total = missing.sumOf { it.sizeBytes }
        _transferModel.value = target
        _transfer.value = SttTransfer.Running(0, total)
        var done = 0L
        var lastPermille = -1L
        try {
            for (file in missing) {
                fetch(file, fileFor(file)) { current, _ ->
                    // reads are a few KB each: only publish visible progress
                    val permille = (done + current) * 1000 / total
                    if (permille != lastPermille) {
                        lastPermille = permille
                        _transfer.value = SttTransfer.Running(done + current, total)
                    }
                }
                done += file.sizeBytes
                filesVersion.update { it + 1 }
            }
            _transfer.value = SttTransfer.Idle
            _transferModel.value = null
            return true
        } catch (e: CancellationException) {
            _transfer.value = SttTransfer.Idle
            _transferModel.value = null
            throw e
        } catch (t: Throwable) {
            log("Could not download the speech model", t)
            _transfer.value = SttTransfer.Failed(t)
            return false
        } finally {
            filesVersion.update { it + 1 }
        }
    }

    /** Selects [target], whose files are on disk, and deletes the other models' files. */
    private suspend fun select(target: GarSttModel) {
        if (settingsState.value.sttModel != target) {
            settings.updateData { it.toBuilder().setSttModel(target).build() }
        }
        val others = SttModelCatalog.models
            .filter { it.setting != target }
            .map { fileFor(it) }
            .flatMap { listOf(it, GarModelDownloader.partFile(it)) }
            .filter { it.exists() }
        if (others.isNotEmpty()) {
            // waits for a transcription with the old model to finish
            holder.unload()
            withContext(io) { others.forEach { it.delete() } }
        }
        filesVersion.update { it + 1 }
    }

    /** Stops the download; partial files are kept, so the next download resumes. */
    fun cancelDownload() {
        synchronized(downloadLock) {
            // the job is kept, so that the next download waits for it to finish
            downloadJob?.cancel()
        }
        _transfer.value = SttTransfer.Idle
        _transferModel.value = null
    }

    /** Deletes every speech file (models, VAD, partial downloads); Whisper needs a download then. */
    fun delete() {
        val running = synchronized(downloadLock) { downloadJob?.also { it.cancel() } }
        scope.launch {
            running?.join()
            holder.unload()
            withContext(io) {
                (SttModelCatalog.models + SttModelCatalog.SILERO_VAD)
                    .map { fileFor(it) }
                    .forEach {
                        it.delete()
                        GarModelDownloader.partFile(it).delete()
                    }
            }
            _transfer.value = SttTransfer.Idle
            _transferModel.value = null
            filesVersion.update { it + 1 }
        }
    }

    override fun acquire() {
        holder.acquire()
        preload()
        refreshPromptNames()
    }

    override fun release() = holder.release()

    override fun preload() {
        val selection = selectionFor(settingsState.value) ?: return
        // held for the grace time, so a load nobody uses doesn't stay in memory
        holder.acquire()
        try {
            holder.preload(selection)
        } finally {
            holder.release()
        }
    }

    override suspend fun retryLoad() {
        withContext(io) { loadGuard.clear() }
        holder.retry()
        _loadGeneration.update { it + 1 }
    }

    /** [retryLoad] from GAR's settings, then loads again if someone holds the model. */
    fun retryLoadInBackground() {
        scope.launch {
            retryLoad()
            if (holder.isAcquired) {
                preload()
            }
        }
    }

    override suspend fun <R> use(block: suspend (Transcriber) -> R): R {
        val selection = selectionFor(settingsState.value)
            ?: throw SttLoadException("The speech model is not downloaded")
        holder.acquire()
        try {
            return holder.use(selection) { loaded ->
                try {
                    block(loaded.transcriber)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    throw BlockException(t)
                }
            }
        } catch (e: BlockException) {
            throw e.cause
        } catch (e: CancellationException) {
            throw e
        } catch (e: SttLoadException) {
            throw e
        } catch (e: PreviousLoadFailedException) {
            // the earlier load's own exception, which says why (e.g. it stopped the app)
            throw e.cause as? SttLoadException
                ?: SttLoadException("The speech model could not be loaded", e.cause)
        } catch (t: Throwable) {
            throw SttLoadException("The speech model could not be loaded", t)
        } finally {
            holder.release()
        }
    }

    /** Unloads the model now, unless someone holds it (memory pressure, releasing resources). */
    override fun unloadIfIdle() {
        scope.launch { holder.unloadIfIdle() }
    }

    private suspend fun loadSelection(selection: SttSelection): LoadedStt = withContext(io) {
        // one native load at a time, so the guard blames the model that was loading
        loadLock.withSpeechLoad { loadGuarded(selection) }
    }

    private suspend fun loadGuarded(selection: SttSelection): LoadedStt {
        // throws if this file stopped the app while loading before; outside the try below, so
        // that the guard keeps counting
        try {
            loadGuard.begin(selection.id)
        } catch (e: ModelCrashedException) {
            throw SttLoadException("Loading this speech model stopped the app", e)
        }
        return try {
            loadEngine(selection.file)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("Could not load ${selection.file}", t)
            throw t as? SttLoadException ?: SttLoadException("Could not load ${selection.file.name}", t)
        } finally {
            // not reached when whisper.cpp aborts the process or the system kills it
            loadGuard.end()
        }
    }

    /** The UI was opened: keep the model, and load it now if Whisper is the input device. */
    private fun onUiStarted() {
        holder.acquire()
        scope.launch {
            if (wanted.first()) {
                selectionFor(settingsState.value)?.let { holder.preload(it) }
            }
        }
    }

    private class BlockException(override val cause: Throwable) : Exception(cause)

    /**
     * Holds the model while at least one MainActivity is started, like GarModelManager's (there
     * can be two, e.g. one in the assistant's task and one in the launcher's). Main thread only.
     */
    private inner class MainActivityCallbacks : Application.ActivityLifecycleCallbacks {
        private val started = HashSet<Activity>()
        // stopped for a configuration change: their recreated instance takes over their place
        private var recreating = 0
        private var holding = false

        fun acquireIfStarted() {
            if (MainActivity.isInForeground > 0 && !holding) {
                holding = true
                onUiStarted()
            }
        }

        override fun onActivityStarted(activity: Activity) {
            if (activity !is MainActivity) {
                return
            }
            if (recreating > 0) {
                recreating -= 1
            }
            started.add(activity)
            if (!holding) {
                holding = true
                onUiStarted()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity !is MainActivity) {
                return
            }
            if (!started.remove(activity) && started.isNotEmpty()) {
                return // started before these callbacks existed, others still hold
            }
            if (activity.isChangingConfigurations) {
                recreating += 1
            } else if (started.isEmpty() && recreating == 0 && holding) {
                holding = false
                release()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private inner class MemoryCallbacks : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION") // still delivered, and the threshold we want
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                unloadIfIdle()
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = unloadIfIdle()
    }

    companion object {
        private const val TAG = "GarStt"
        const val MODELS_DIR = "gar-stt"
        private const val LOAD_MARKER_FILE = "gar-stt-loading"
        const val GRACE_MILLIS = 30_000L
        /** Shorter than the ~10 s after which Android freezes a cached process. */
        const val SHORT_GRACE_MILLIS = 6_000L
        /** X4 plus three A720 cores on the Pixel 9a, like the language model. */
        private const val THREADS = 4

        /**
         * Whether Whisper is (or will be) the input device, so loading it ahead of time is
         * useful: chosen, or the default (unset) in English, where the device resolver picks
         * Whisper once its files exist.
         */
        fun whisperWanted(inputDevice: InputDevice, sentencesLanguage: String): Boolean =
            when (inputDevice) {
                InputDevice.INPUT_DEVICE_WHISPER -> true
                InputDevice.INPUT_DEVICE_UNSET,
                InputDevice.UNRECOGNIZED -> sentencesLanguage == GarFallbackImpl.ENGLISH
                else -> false
            }

        /**
         * What GAR's settings show: a running or failed download first (also of a model being
         * switched to), then whether the selected model is on disk and loaded.
         */
        fun sttState(
            selection: SttSelection?,
            transfer: SttTransfer,
            transferModel: GarSttModel?,
            engine: GarEngineHolder.State<SttSelection>,
        ): GarSttState {
            if (transferModel != null) {
                when (transfer) {
                    is SttTransfer.Running -> return GarSttState.Downloading(
                        transferModel,
                        transfer.totalBytes.takeIf { it > 0 }
                            ?.let { transfer.currentBytes.toFloat() / it },
                    )
                    is SttTransfer.Failed -> return GarSttState.DownloadFailed(
                        transferModel, (transfer.throwable as? GarDownloadException)?.damaged == true,
                    )
                    SttTransfer.Idle -> {}
                }
            }
            if (selection == null) {
                return GarSttState.NotDownloaded
            }
            return when (engine) {
                is GarEngineHolder.State.Loading ->
                    if (engine.key == selection) GarSttState.Loading else GarSttState.Ready
                is GarEngineHolder.State.Loaded ->
                    if (engine.key == selection) GarSttState.Loaded else GarSttState.Ready
                is GarEngineHolder.State.Failed ->
                    if (engine.key == selection) {
                        // the whole chain: coroutines' stack trace recovery may add a copy
                        GarSttState.LoadFailed(
                            generateSequence(engine.throwable) { it.cause }
                                .any { it is ModelCrashedException }
                        )
                    } else {
                        GarSttState.Ready
                    }
                GarEngineHolder.State.NotLoaded -> GarSttState.Ready
            }
        }

        private fun downloadWith(
            downloader: GarModelDownloader,
        ): suspend (SttDownload, File, (Long, Long) -> Unit) -> Unit = { file, target, progress ->
            downloader.download(file.url, target, file.sizeBytes, file.sha256, progress)
        }

        private suspend fun loadWhisper(file: File): LoadedStt {
            val engine = try {
                WhisperEngine.load(file, THREADS)
            } catch (e: IOException) {
                throw SttLoadException("Could not load ${file.name}: ${e.message}", e)
            }
            Log.i(TAG, "Loaded ${file.name} with whisper.cpp ${engine.whisperVersion}: " +
                engine.systemInfo)
            // the first SpokenForm call loads dicio-numbers' English data: not on the first
            // command's path
            SpokenForm.of("1")
            return LoadedStt(engine) { engine.close() }
        }

        /**
         * Whether the process stays unfrozen after the UI is gone (the assistant service is bound
         * or the wake word service runs), so a timer in it keeps running.
         */
        private fun isProcessKeptRunning(): Boolean {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            return info.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        }
    }
}
