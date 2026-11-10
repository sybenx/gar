package org.stypox.dicio.gar.ui

import android.Manifest
import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.datastore.core.DataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.shreyaspatil.permissionflow.compose.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.eval.SkillHandler
import org.stypox.dicio.gar.model.GarModelManager
import org.stypox.dicio.gar.model.GarModelState
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.home.InteractionLog
import java.util.Collections
import java.util.WeakHashMap

/**
 * What GAR's microphone is doing, for the home screen and the assistant sheet (gar/DESIGN.md,
 * "Home and chips"). All times in this file are SystemClock.elapsedRealtime() milliseconds.
 */
sealed interface GarMicState {
    data object Idle : GarMicState

    /** [followUpEndsAt]: end of a continued-listening window, null otherwise. */
    data class Listening(val followUpEndsAt: Long?) : GarMicState

    /** SttState.Loading(thenStartListening = true) */
    data object Starting : GarMicState

    /** Also SttState.WaitingForResult */
    data object Transcribing : GarMicState

    /**
     * A command is being answered: since [since]; [skill] once a skill was chosen, [model] while
     * the model routes it, [loadingModel] while that route waits for the model to load.
     */
    data class Thinking(
        val since: Long,
        val skill: SkillInfo?,
        val model: Boolean,
        val loadingModel: Boolean,
    ) : GarMicState

    data object Speaking : GarMicState

    /** The microphone can't listen: [stt] says why (null when speech input is off). */
    data class Unavailable(val stt: SttState?) : GarMicState
}

/** A short name for logs and frame tags (`GarJank.state(window, "mic", ...)`). */
val GarMicState.tag: String
    get() = when (this) {
        GarMicState.Idle -> "idle"
        is GarMicState.Listening -> if (followUpEndsAt != null) "follow_up" else "listening"
        GarMicState.Starting -> "starting"
        GarMicState.Transcribing -> "transcribing"
        is GarMicState.Thinking -> "thinking"
        GarMicState.Speaking -> "speaking"
        is GarMicState.Unavailable -> "unavailable"
    }

/** Pure. Precedence: Listening > Starting > Transcribing > Thinking > Speaking > Idle/Unavailable. */
fun garMicState(
    stt: SttState?,
    briefEndsAt: Long?,       // elapsedRealtime ms; see garBriefEndsAtElapsed
    log: InteractionLog,
    pendingSince: Long?,
    now: Long,
    routing: GarThinking.Routing?,
    modelLoading: Boolean,
    speaking: Boolean,
): GarMicState {
    when (stt) {
        SttState.Listening -> return GarMicState.Listening(briefEndsAt)
        is SttState.Loading -> if (stt.thenStartListening) return GarMicState.Starting
        SttState.Transcribing, SttState.WaitingForResult -> return GarMicState.Transcribing
        else -> {}
    }

    val pending = log.pendingQuestion
    if (pending != null || routing != null) {
        val since = pendingSince ?: routing?.startedAt ?: now
        val old = pending != null && now - since >= GarThinking.SHOW_AFTER_MILLIS
        if (old || routing != null) {
            return GarMicState.Thinking(
                since = since,
                skill = pending?.skillBeingEvaluated,
                model = routing != null,
                loadingModel = routing != null && modelLoading,
            )
        }
    }

    if (speaking) {
        return GarMicState.Speaking
    }
    return when (stt) {
        SttState.Loaded, SttState.NotLoaded, is SttState.Loading -> GarMicState.Idle
        else -> GarMicState.Unavailable(stt)
    }
}

/** Converts SttInputDeviceWrapper.briefWindowEndsAt (System.nanoTime ms) to elapsedRealtime ms. */
fun garBriefEndsAtElapsed(nanoMs: Long?): Long? =
    garBriefEndsAtElapsed(nanoMs, System.nanoTime() / 1_000_000, garElapsedNow())

/** [garBriefEndsAtElapsed] with both clocks read already, for tests. */
internal fun garBriefEndsAtElapsed(nanoMs: Long?, nanoNow: Long, elapsedNow: Long): Long? =
    nanoMs?.let { it - nanoNow + elapsedNow }

/** SystemClock.elapsedRealtime(); System.nanoTime in JVM tests, where Android's stubs throw. */
internal fun garElapsedNow(): Long = try {
    SystemClock.elapsedRealtime()
} catch (e: RuntimeException) {
    System.nanoTime() / 1_000_000
}

/** Whether the model is routing a command right now (GarFallbackImpl.tryRoute). */
object GarThinking {
    data class Routing(val startedAt: Long)

    private val _routing = MutableStateFlow<Routing?>(null)
    val routing: StateFlow<Routing?> = _routing

    fun routingStarted() {
        _routing.value = Routing(garElapsedNow())
    }

    fun routingEnded() {
        _routing.value = null
    }

    /** A pending question shows as Thinking once this old (quick answers show none). */
    const val SHOW_AFTER_MILLIS = 300L

    /** From this on the label counts the seconds ("Thinking... 3 s"). */
    const val ELAPSED_AFTER_MILLIS = 1_500L
}

/** True while GAR's TTS has queued or playing utterances; fed by GarUtteranceTracker. */
object GarSpeaking {
    private val lock = Any()
    // the trackers with queued utterances; weak, so that a dropped speech device can't keep it
    private val speakingSources: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    /** [source] (a GarUtteranceTracker) has utterances queued ([pending]) or none. */
    internal fun update(source: Any, pending: Boolean) {
        synchronized(lock) {
            if (pending) {
                speakingSources.add(source)
            } else {
                speakingSources.remove(source)
            }
            _speaking.value = speakingSources.isNotEmpty()
        }
    }
}

/**
 * The microphone's state from the app's singletons (entry points); previews get Idle. Mirrors
 * SttFab: without the microphone permission the input device counts as NoMicrophonePermission.
 */
@Composable
fun rememberGarMicState(): State<GarMicState> {
    if (LocalInspectionMode.current) {
        return remember { mutableStateOf(GarMicState.Idle) } // previews have no Hilt
    }
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, GarUiEntryPoint::class.java)
    }
    val stt = entryPoint.sttInputDevice().uiState.collectAsState()
    val briefWindow = entryPoint.sttInputDevice().briefWindowEndsAt.collectAsState()
    val log = entryPoint.skillEvaluator().state.collectAsState()
    val routing = GarThinking.routing.collectAsState()
    val speaking = GarSpeaking.speaking.collectAsState()
    val modelLoading = remember(entryPoint) {
        entryPoint.modelManager().modelState
            .map { it == GarModelState.Loading }
            .distinctUntilChanged()
    }.collectAsState(initial = false)
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    // when the pending question appeared, and a clock that ticks once it is old enough to show
    var pendingSince by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(garElapsedNow()) }
    val hasPending = log.value.pendingQuestion != null
    LaunchedEffect(hasPending) {
        now = garElapsedNow()
        pendingSince = if (hasPending) now else null
        if (hasPending) {
            delay(GarThinking.SHOW_AFTER_MILLIS)
            now = garElapsedNow()
        }
    }

    return remember {
        derivedStateOf {
            val sttState = stt.value
            val noPermission = !micPermission.value.isGranted &&
                sttState != null && sttState != SttState.NotAvailable
            garMicState(
                stt = if (noPermission) SttState.NoMicrophonePermission else sttState,
                briefEndsAt = garBriefEndsAtElapsed(briefWindow.value),
                log = log.value,
                pendingSince = pendingSince,
                now = now,
                routing = routing.value,
                modelLoading = modelLoading.value,
                speaking = speaking.value,
            )
        }
    }
}

/** ANIMATOR_DURATION_SCALE == 0 or !ValueAnimator.areAnimatorsEnabled(); follows changes. */
@Composable
fun rememberGarReducedMotion(): Boolean {
    if (LocalInspectionMode.current) {
        return false
    }
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(garAnimationsOff(context.contentResolver)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = garAnimationsOff(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer
        )
        reduced = garAnimationsOff(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun garAnimationsOff(resolver: android.content.ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
        !ValueAnimator.areAnimatorsEnabled()

/**
 * The singletons the home screen's and the sheet's GAR composables read without a view model:
 * the mic state, the chips and the starter chips' re-check.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GarUiEntryPoint {
    fun sttInputDevice(): SttInputDeviceWrapper

    fun skillEvaluator(): SkillEvaluator

    fun modelManager(): GarModelManager

    fun skillHandler(): SkillHandler

    fun skillContext(): SkillContextInternal

    fun garSettings(): DataStore<GarSettings>
}
