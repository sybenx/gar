package org.stypox.dicio.gar.sheet

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.Keep
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.datastore.core.DataStore
import cc.aaro.gar.assist.GarAssist
import cc.aaro.gar.assist.GarSessionHost
import cc.aaro.gar.assist.GarSessionUi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.dicio.skill.skill.Permission
import org.stypox.dicio.MainActivity
import org.stypox.dicio.R
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.di.SpeechOutputDeviceWrapper
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.gar.conversation.GarBargeIn
import org.stypox.dicio.gar.model.GarModelManager
import org.stypox.dicio.gar.perf.GarJank
import org.stypox.dicio.gar.perf.GarStartupCache
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.stt.GarMicSilenced
import org.stypox.dicio.gar.ui.GarChip
import org.stypox.dicio.gar.ui.GarChips
import org.stypox.dicio.gar.ui.GarMicState
import org.stypox.dicio.gar.voicelog.GarVoiceLog
import org.stypox.dicio.gar.voicelog.GarVoiceStart
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.ui.home.InteractionLog

/**
 * The assistant sheet in the session's window (gar/DESIGN.md, "Assistant entry points"). Loaded
 * by name from `:gar:assist` ([GarAssist.SHEET_UI_CLASS]), hence [Keep]. One controller per show.
 */
@Keep
class GarSheetUi : GarSessionUi {
    private val controllers = HashMap<GarSessionHost, GarSheetController>()

    override fun prewarm(context: Context) {
        // the theme and GAR's settings for the first frame, read on the GarStartup thread
        GarStartupCache.start(context)
        // the locale: LocaleManager reads the language once when built, better here than on main
        EntryPointAccessors.fromApplication(context, GarSheetEntryPoint::class.java)
            .localeManager()
    }

    override fun wantsSheet(context: Context, source: Int): Boolean =
        !GarSheet.fullAppWanted(context)

    override fun createView(host: GarSessionHost): View =
        GarSheetController(host).also { controllers[host] = it }.createView()

    override fun onShow(host: GarSessionHost) {
        controllers[host]?.onShow()
    }

    override fun onHide(host: GarSessionHost) {
        controllers.remove(host)?.onHide()
    }

    override fun onBackPressed(host: GarSessionHost): Boolean {
        val controller = controllers[host] ?: return false
        controller.dismiss()
        return true
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GarSheetEntryPoint {
    fun skillEvaluator(): SkillEvaluator
    fun sttInputDevice(): SttInputDeviceWrapper
    fun speechOutputDevice(): SpeechOutputDeviceWrapper
    fun skillContext(): SkillContextInternal
    fun userSettings(): DataStore<UserSettings>
    fun garSettings(): DataStore<GarSettings>
    fun modelManager(): GarModelManager
    fun localeManager(): LocaleManager
}

/** The app's singletons the sheet uses; building them the first time builds every skill. */
internal class GarSheetDeps(entryPoint: GarSheetEntryPoint) {
    val skillEvaluator = entryPoint.skillEvaluator()
    val sttInputDevice = entryPoint.sttInputDevice()
    val speechOutputDevice = entryPoint.speechOutputDevice()
    val skillContext = entryPoint.skillContext()
    val garSettings = entryPoint.garSettings()
    val modelManager = entryPoint.modelManager()
    val localeManager = entryPoint.localeManager()
}

/**
 * One show of the sheet: its window, owners, the singletons once fetched, and the teardown that
 * every way out runs. Main thread.
 */
internal class GarSheetController(val host: GarSessionHost) {
    private val appContext: Context = host.context.applicationContext
    private val entryPoint =
        EntryPointAccessors.fromApplication(appContext, GarSheetEntryPoint::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val owners = GarSheetOwners()
    private var view: ComposeView? = null

    /** A DataStore singleton is cheap to get: nothing is read until collected. */
    val userSettings: DataStore<UserSettings> = entryPoint.userSettings()

    // read by the composition
    val deps = mutableStateOf<GarSheetDeps?>(null)
    val backProgress = mutableFloatStateOf(0f)
    val closing = mutableStateOf(false)
    /** The last moment the sheet was busy or touched (elapsedRealtime ms), for the idle timer. */
    val lastActiveAt = mutableLongStateOf(SystemClock.elapsedRealtime())
    /** A screen reader explores by touch (TalkBack): the sheet doesn't go by itself. */
    val touchExploration = mutableStateOf(false)
    /** Starter chips take taps only once the finger that opened the sheet has lifted. */
    val startersEnabled = mutableStateOf(false)
    /** Answered turns from before this show, see [GarSheetState.baseline]. */
    private var baseline = 0

    private val back = GarSheetBack(host.window, backProgress, ::dismiss)
    private val dismissFromOutside = { dismiss() }
    private var jank: AutoCloseable? = null
    private var model: AutoCloseable? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var requester: (suspend (List<Permission>) -> Boolean)? = null
    private var previousRequester: (suspend (List<Permission>) -> Boolean)? = null
    private var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var touchExplorationListener:
        AccessibilityManager.TouchExplorationStateChangeListener? = null
    private var leavingAnswerAt = 0L
    private var keepScreenOn = false
    private var shown = false
    private var tornDown = false

    /** For a skill's activity result launcher (the song skill): the full app does it instead. */
    val activityResultOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
                openGar()
            }
        }
    }

    fun createView(): View {
        val window = host.window
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // the session's theme asks for translucent bars, which draw a grey scrim over the
        // sheet's bottom edge and the status bar; the sheet draws behind clear bars instead
        @Suppress("DEPRECATION")
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION") // ignored from API 35, where the bar is transparent anyway
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        @Suppress("DEPRECATION") // the IME's insets reach the sheet either way on API 30+
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setTitle(host.context.getString(R.string.gar_sheet_title))

        return ComposeView(host.context).also { composeView ->
            owners.attach(composeView)
            composeView.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owners)
            )
            composeView.setContent { GarSheetRoot(this) }
            // TalkBack's swipes and focus moves send the sheet no touches: they reset the idle
            // timer here instead (Compose's view asks its parent, this one, to send the events)
            composeView.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onRequestSendAccessibilityEvent(
                    host: ViewGroup,
                    child: View,
                    event: AccessibilityEvent,
                ): Boolean {
                    when (event.eventType) {
                        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> touched()
                    }
                    return super.onRequestSendAccessibilityEvent(host, child, event)
                }
            }
            view = composeView
        }
    }

    fun onShow() {
        if (tornDown) {
            return
        }
        GarSheet.isShown = true
        GarSheet.dismissShown = dismissFromOutside
        owners.resume()
        if (shown) {
            // shown again while up (the assist gesture again, the wake word): a new turn
            deps.value?.let { startTurn(it) }
            return
        }
        shown = true
        if (host.source == GarAssist.SOURCE_HOTWORD) {
            // the baseline now, not at a cold fetch: the wake word's turn may be answered by
            // then. WakeService built the evaluator already and its capture only just started,
            // so no answer has landed yet and its pending question is this sheet's
            baseline = GarSheetState.baseline(
                entryPoint.skillEvaluator().state.value, countPending = false
            )
        }
        val warm = depsFetchedInProcess || MainActivity.isCreated > 0
        jank = GarJank.track(host.window, "sheet")
        back.register()
        GarMicSilenced.handler = micSilenced
        registerScreenOff()
        watchFocus()
        followTouchExploration()
        val view = view ?: return
        afterFirstFrame(view) { elapsed ->
            Log.i(TIMING_TAG, "first_frame_ms=$elapsed source=${sourceName()} cold=${!warm}")
            if (!warm) {
                // the singletons are built after the shell's first frame, never before it
                Looper.myQueue().addIdleHandler {
                    fetchDeps()
                    false
                }
                // the shell's spinner keeps a slow main thread from ever going idle (seen on
                // the emulator under load), so the fetch has a deadline too; it runs once
                handler.postDelayed(::fetchDeps, DEPS_IDLE_DEADLINE_MILLIS)
            }
        }
        if (warm) {
            fetchDeps()
        }
    }

    /** Any hide: teardown, then the composition goes with the lifecycle. */
    fun onHide() {
        teardown()
        owners.destroy()
        view = null
    }

    /** Every way out but a hide by the system: the sheet slides away, then the session hides. */
    fun dismiss() {
        if (closing.value) {
            return
        }
        teardown()
        closing.value = true
        handler.postDelayed({ host.dismiss() }, CLOSE_FALLBACK_MILLIS)
    }

    /** The slide-out ended (or there was none). */
    fun closed() {
        host.dismiss()
    }

    // ---- the turn ----

    private fun fetchDeps() {
        if (tornDown || deps.value != null) {
            return
        }
        val fetched = GarSheetDeps(entryPoint)
        depsFetchedInProcess = true
        if (host.source != GarAssist.SOURCE_HOTWORD) {
            val log = fetched.skillEvaluator.state.value
            if (host.locked) {
                // over the lock screen: no follow-up may act on what was said while unlocked
                // (for the wake word GarSheet.beforeHotwordCapture did it before its capture)
                fetched.skillEvaluator.forgetConversation()
            }
            baseline = GarSheetState.baseline(log)
        }
        installRequester(fetched)
        GarBargeIn.start(fetched.sttInputDevice, fetched.speechOutputDevice)
        view?.let { model = fetched.modelManager.acquireAfterFirstFrame(it) }
        deps.value = fetched
        startTurn(fetched)
    }

    private fun startTurn(deps: GarSheetDeps) {
        if (host.source == GarAssist.SOURCE_HOTWORD) {
            return // WakeService started the capture before showing the sheet
        }
        // as MainActivity's assist intent: the system may show a session twice in a row
        val now = SystemClock.elapsedRealtime()
        if (now < nextListenAllowedAt) {
            Log.w(TAG, "Ignoring a duplicate show")
            return
        }
        nextListenAllowedAt = now + LISTEN_BACKOFF_MILLIS
        GarVoiceLog.listen(GarVoiceStart.ASSIST) {
            deps.sttInputDevice.tryLoad(deps.skillEvaluator::processInputEvent)
        }
    }

    /** The sheet's turns in [log]; keeps the baseline that [GarSheetState.select] settles on. */
    fun select(log: InteractionLog): GarSheetTurns =
        GarSheetState.select(log, baseline).also { baseline = it.baseline }

    private fun installRequester(deps: GarSheetDeps) {
        val evaluator = deps.skillEvaluator
        val own: suspend (List<Permission>) -> Boolean = { permissions ->
            GarPermissionHandOff.requester(appContext, host, permissions)
        }
        previousRequester = evaluator.permissionRequester
        requester = own
        evaluator.permissionRequester = own
    }

    // ---- what the sheet's buttons do ----

    fun onMic(state: GarMicState) {
        val deps = deps.value ?: return
        touched()
        when (state) {
            GarMicState.Speaking -> deps.speechOutputDevice.stopSpeaking()
            is GarMicState.Unavailable -> openGar()
            is GarMicState.Listening -> if (state.followUpEndsAt != null) {
                deps.sttInputDevice.stopBriefListening()
            } else {
                deps.sttInputDevice.onClick(deps.skillEvaluator::processInputEvent)
            }
            // Dicio's click: stops loading, cancels the transcription
            GarMicState.Starting,
            GarMicState.Transcribing ->
                deps.sttInputDevice.onClick(deps.skillEvaluator::processInputEvent)
            // a new request; one being answered still lands in the log
            GarMicState.Idle,
            is GarMicState.Thinking ->
                GarVoiceLog.listen(GarVoiceStart.MIC_BUTTON) {
                    deps.sttInputDevice.onClick(deps.skillEvaluator::processInputEvent)
                }
        }
    }

    fun onChip(chip: GarChip) {
        val deps = deps.value ?: return
        touched()
        GarChips.send(deps.skillEvaluator, chip)
    }

    /** The text field got focus: listening stops, so typing and talking don't mix. */
    fun onStartTyping() {
        val deps = deps.value ?: return
        touched()
        when (deps.sttInputDevice.uiState.value) {
            SttState.Listening, is SttState.Loading -> deps.sttInputDevice.stopListening()
            else -> {}
        }
        if (deps.sttInputDevice.briefWindowEndsAt.value != null) {
            deps.sttInputDevice.stopBriefListening()
        }
    }

    fun onEdit(text: String) {
        touched()
        GarVoiceLog.editing(text)
    }

    fun onSend(text: String) {
        val deps = deps.value ?: return
        touched()
        GarVoiceLog.hint(GarVoiceStart.TYPED)
        deps.skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(text, 1.0f))))
    }

    fun stopSpeaking() {
        deps.value?.speechOutputDevice?.stopSpeaking()
    }

    // the capture stayed silenced: another app records at the top, and only an activity makes
    // GAR the top app (gar/DESIGN.md, "Assistant sheet"), so MainActivity listens instead
    private val micSilenced: () -> Boolean = {
        if (tornDown) {
            false
        } else {
            handler.post {
                if (!tornDown) {
                    host.openApp(
                        Intent(Intent.ACTION_ASSIST)
                            .setClassName(host.context.packageName, GarAssist.MAIN_ACTIVITY_CLASS)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                    )
                }
            }
            true
        }
    }

    /** "Open GAR", and the hand-offs that need the full app. */
    fun openGar() {
        host.openApp(
            Intent(Intent.ACTION_MAIN)
                .setClassName(host.context.packageName, GarAssist.MAIN_ACTIVITY_CLASS)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
        )
    }

    fun touched() {
        lastActiveAt.longValue = SystemClock.elapsedRealtime()
    }

    // a finger is down on the sheet (the one that opened it may still be, from the corner swipe)
    private var pointerDown = false

    /** Starters stay disabled while the finger that may have opened the sheet is down. */
    fun enableStartersIfNoPointer() {
        if (!pointerDown) {
            startersEnabled.value = true
        }
    }

    /** What the sheet's composables call. */
    val actions: GarSheetActions by lazy {
        GarSheetActions(
            onDismiss = ::dismiss,
            onClosed = ::closed,
            onMic = ::onMic,
            onOpenApp = ::openGar,
            onChip = ::onChip,
            onEdit = ::onEdit,
            onStartTyping = ::onStartTyping,
            onSend = ::onSend,
            onStopSpeaking = ::stopSpeaking,
            onTouch = ::touched,
            onPointerDown = { pointerDown = true },
            onPointerUp = {
                pointerDown = false
                startersEnabled.value = true
            },
        )
    }

    // ---- window ----

    /** FLAG_KEEP_SCREEN_ON as [GarSheetState.idleDecision] says. */
    fun keepScreenOn(on: Boolean) {
        if (tornDown || on == keepScreenOn) {
            return
        }
        keepScreenOn = on
        if (on) {
            host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * The base timeouts lengthened as the user's accessibility settings ask. With touch
     * exploration on there is no auto-dismiss at all ([touchExploration]).
     */
    fun timeouts(): GarSheetTimeouts {
        val manager = appContext.getSystemService(AccessibilityManager::class.java)
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return GarSheetTimeouts()
        }
        val flags = AccessibilityManager.FLAG_CONTENT_TEXT or
            AccessibilityManager.FLAG_CONTENT_CONTROLS
        return GarSheetTimeouts(
            answeredMillis = manager.getRecommendedTimeoutMillis(
                GarSheetTimeouts.ANSWERED_MILLIS.toInt(), flags
            ).toLong(),
            emptyMillis = manager.getRecommendedTimeoutMillis(
                GarSheetTimeouts.EMPTY_MILLIS.toInt(), flags
            ).toLong(),
            unavailableMillis = manager.getRecommendedTimeoutMillis(
                GarSheetTimeouts.UNAVAILABLE_MILLIS.toInt(), flags
            ).toLong(),
        )
    }

    /** The latest answer came from [skillId]; if it opens another app, the sheet should go. */
    fun onLatestAnswer(skillId: String?) {
        if (skillId == null || skillId !in GarSheet.LEAVES_GAR) {
            leavingAnswerAt = 0L
            return
        }
        leavingAnswerAt = SystemClock.elapsedRealtime()
        handler.postDelayed({
            if (view?.hasWindowFocus() == false) {
                dismiss()
            }
        }, LEAVING_CHECK_MILLIS)
    }

    // an app came up some other way than through GarLeavingContext (the safety net)
    private fun watchFocus() {
        val view = view ?: return
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus && leavingAnswerAt != 0L &&
                SystemClock.elapsedRealtime() - leavingAnswerAt < LEAVING_WINDOW_MILLIS
            ) {
                dismiss()
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        focusListener = listener
    }

    // TalkBack may be turned on or off while the sheet is up
    private fun followTouchExploration() {
        val manager = appContext.getSystemService(AccessibilityManager::class.java) ?: return
        touchExploration.value = manager.isTouchExplorationEnabled
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            touchExploration.value = enabled
            touched() // a new idle timer when it goes off
        }
        manager.addTouchExplorationStateChangeListener(listener)
        touchExplorationListener = listener
    }

    // the screen turning off dismisses the sheet, but only once it has been on during this show
    // (a sheet shown on a dark screen must not go away at once)
    private fun registerScreenOff() {
        var seenOn = appContext.getSystemService(PowerManager::class.java)?.isInteractive == true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> seenOn = true
                    Intent.ACTION_SCREEN_OFF -> if (seenOn) dismiss()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // system broadcasts, so they arrive even though the receiver isn't exported
        ContextCompat.registerReceiver(
            appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiver = receiver
    }

    /**
     * Calls [then] with the milliseconds from now to [view]'s first frame on screen: its next
     * draw (after composition and layout), then the following Choreographer frame.
     */
    private fun afterFirstFrame(view: View, then: (Long) -> Unit) {
        val start = SystemClock.elapsedRealtime()
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                Choreographer.getInstance().postFrameCallback {
                    if (!tornDown) {
                        then(SystemClock.elapsedRealtime() - start)
                    }
                }
                return true
            }
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
    }

    // ---- the one teardown: dismiss, home, shade, recents, screen off, a skill leaving ----

    private fun teardown() {
        if (tornDown) {
            return
        }
        tornDown = true
        if (GarSheet.dismissShown === dismissFromOutside) {
            // only this show's flags: a newer session may be up already
            GarSheet.dismissShown = null
            GarSheet.isShown = false
        }
        handler.removeCallbacksAndMessages(null)
        if (GarMicSilenced.handler === micSilenced) {
            GarMicSilenced.handler = null
        }
        back.unregister()
        screenReceiver?.let {
            try {
                appContext.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Screen receiver was not registered", e)
            }
        }
        screenReceiver = null
        focusListener?.let { listener ->
            view?.viewTreeObserver?.takeIf { it.isAlive }
                ?.removeOnWindowFocusChangeListener(listener)
        }
        focusListener = null
        touchExplorationListener?.let { listener ->
            appContext.getSystemService(AccessibilityManager::class.java)
                ?.removeTouchExplorationStateChangeListener(listener)
        }
        touchExplorationListener = null

        val deps = deps.value
        if (deps != null) {
            deps.speechOutputDevice.stopSpeaking()
            stopCapture(deps.sttInputDevice)
            val evaluator = deps.skillEvaluator
            val previous = previousRequester
            if (previous != null && evaluator.permissionRequester === requester) {
                evaluator.permissionRequester = previous // unless the home screen set its own
            }
        } else if (host.source == GarAssist.SOURCE_HOTWORD) {
            // WakeService's capture: its STT singleton exists already
            stopCapture(entryPoint.sttInputDevice())
        }
        requester = null
        previousRequester = null

        jank?.close()
        jank = null
        model?.close()
        model = null
        if (keepScreenOn) {
            host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepScreenOn = false
        }
    }

    private fun stopCapture(stt: SttInputDeviceWrapper) {
        when (val state = stt.uiState.value) {
            SttState.Listening -> stt.stopListening()
            is SttState.Loading -> if (state.thenStartListening) stt.stopListening()
            else -> {}
        }
        if (stt.briefWindowEndsAt.value != null) {
            stt.stopBriefListening()
        }
    }

    private fun sourceName(): String = when (host.source) {
        GarAssist.SOURCE_HOTWORD -> "hotword"
        GarAssist.SOURCE_TILE -> "tile"
        else -> "assist"
    }

    companion object {
        private const val TAG = "GarSheetUi"
        /** The `GarSheet: first_frame_ms=` line that gar/tools/jank.py reads. */
        private const val TIMING_TAG = "GarSheet"
        private const val LISTEN_BACKOFF_MILLIS = 100L
        /** The latest the singletons are fetched after the first frame without an idle moment. */
        private const val DEPS_IDLE_DEADLINE_MILLIS = 300L
        /** The slide-out takes about 250 ms; the session hides after this at the latest. */
        private const val CLOSE_FALLBACK_MILLIS = 400L
        private const val LEAVING_CHECK_MILLIS = 500L
        private const val LEAVING_WINDOW_MILLIS = 10_000L

        // whether a show in this process fetched the singletons (main thread)
        private var depsFetchedInProcess = false
        private var nextListenAllowedAt = 0L
    }
}
