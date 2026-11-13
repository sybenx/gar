package org.stypox.dicio

import android.Manifest
import android.app.KeyguardManager // GAR
import android.content.Intent
import android.content.Intent.ACTION_ASSIST
import android.content.Intent.ACTION_VOICE_COMMAND
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.ReportDrawn // GAR
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding // GAR
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect // GAR
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics // GAR
import androidx.compose.ui.semantics.testTagsAsResourceId // GAR
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.shreyaspatil.permissionFlow.PermissionFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.gar.perf.GarJank // GAR
import org.stypox.dicio.gar.ui.GarInsets // GAR
import org.stypox.dicio.gar.perf.garTrace // GAR
import org.stypox.dicio.gar.perf.garTraceAsyncBegin // GAR
import org.stypox.dicio.gar.perf.garTraceAsyncEnd // GAR
import org.stypox.dicio.io.wake.WakeService
import org.stypox.dicio.io.wake.WakeState.Loaded
import org.stypox.dicio.io.wake.WakeState.Loading
import org.stypox.dicio.io.wake.WakeState.NotLoaded
import org.stypox.dicio.ui.home.wakeWordPermissions
import org.stypox.dicio.ui.nav.Navigation
import org.stypox.dicio.util.BaseActivity
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BaseActivity() {

    @Inject
    lateinit var skillEvaluator: SkillEvaluator
    @Inject
    lateinit var sttInputDevice: SttInputDeviceWrapper
    @Inject
    lateinit var wakeDevice: WakeDeviceWrapper

    private var sttPermissionJob: Job? = null
    private var wakeServiceJob: Job? = null

    private var nextAssistAllowed = Instant.MIN

    // GAR: see gar/DESIGN.md, "Frame rate"
    private var jankTracking: AutoCloseable? = null
    private var firstCompositionDone = false

    /**
     * Automatically loads the LLM and the STT when the [ACTION_ASSIST] intent is received. Applies
     * a backoff of [INTENT_BACKOFF_MILLIS], since during testing Android would send the assist
     * intent to the app twice in a row.
     */
    private fun onAssistIntentReceived() {
        val now = Instant.now()
        if (nextAssistAllowed < now) {
            nextAssistAllowed = now.plusMillis(INTENT_BACKOFF_MILLIS)
            Log.d(TAG, "Received assist intent")
            // GAR: over the lock screen, show none of the answers given while it was unlocked,
            // and remember none of their turns (the follow-ups' memory)
            if (getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) {
                skillEvaluator.forgetInteractions()
            }
            // GAR: the voice log's start
            org.stypox.dicio.gar.voicelog.GarVoiceLog.listen(
                org.stypox.dicio.gar.voicelog.GarVoiceStart.ASSIST
            ) {
                sttInputDevice.tryLoad(skillEvaluator::processInputEvent)
            }
        } else {
            Log.w(TAG, "Ignoring duplicate assist intent")
        }
    }

    private fun handleWakeWordTurnOnScreen(intent: Intent?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
            intent?.action == ACTION_WAKE_WORD
        ) {
            // Dicio was started anew based on a wake word,
            // turn on the screen to let the user see what is happening
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // GAR: over the lock screen, show none of the answers given while it was unlocked
            if (getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) {
                skillEvaluator.forgetInteractions()
            }
        }

        // the wake word triggered notification is not needed anymore
        WakeService.cancelTriggeredNotification(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        handleWakeWordTurnOnScreen(intent)
        if (isAssistIntent(intent)) {
            onAssistIntentReceived()
        }
    }

    override fun onStart() {
        isInForeground += 1
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        isInForeground -= 1

        // once the activity is swiped away from the lock screen (or put in the background in any
        // other way), we don't want to show it on the lock screen anymore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        garTrace("Gar:MainActivity.super.onCreate") { // GAR: locale, Hilt injection
            super.onCreate(savedInstanceState)
        }
        isCreated += 1

        handleWakeWordTurnOnScreen(intent)
        if (isAssistIntent(intent)) {
            onAssistIntentReceived()
        } else if (intent.action != ACTION_WAKE_WORD) {
            // load the input device, without starting to listen
            sttInputDevice.tryLoad(null)
        }

        WakeService.start(this)
        wakeServiceJob?.cancel()
        wakeServiceJob = lifecycleScope.launch {
            wakeDevice.state
                .map { it == NotLoaded || it == Loading || it == Loaded }
                .combine(
                    PermissionFlow.getInstance().getMultiplePermissionState(*wakeWordPermissions)
                ) { wakeState, permGranted ->
                    wakeState && permGranted.allGranted
                }
                // avoid restarting the service if the state changes but the resulting value
                // in the flow remains true (which happens when the user stops the WakeService from
                // the notification, which releases resources and makes the WakeDevice go from
                // Loaded to NotLoaded)
                .distinctUntilChanged()
                .filter { it }
                .collect { WakeService.start(this@MainActivity) }
        }

        sttPermissionJob?.cancel()
        sttPermissionJob = lifecycleScope.launch {
            // if the STT failed to load because of the missing permission, this will try again
            PermissionFlow.getInstance().getPermissionState(Manifest.permission.RECORD_AUDIO)
                .drop(1)
                .filter { it.isGranted }
                .collect { sttInputDevice.tryLoad(null) }
        }

        val firstComposition = garTraceAsyncBegin(FIRST_COMPOSITION_SECTION) // GAR
        composeSetContent {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    // GAR: testTags as resource ids, for the baseline profile's UiAutomator,
                    // in every build but the shipped release
                    .then(
                        if (BuildConfig.BUILD_TYPE != "release") {
                            Modifier.semantics { testTagsAsResourceId = true }
                        } else {
                            Modifier
                        }
                    ),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(
                    // GAR: the bars and cutout only; each screen pads for the keyboard itself
                    // (GarInsets.kt), so the keyboard's animation re-lays out only what moves
                    modifier = Modifier.windowInsetsPadding(GarInsets.rootInsets())
                ) {
                    Navigation()
                }
            }
            // GAR: the end of the first composition, for traces
            SideEffect {
                if (!firstCompositionDone) {
                    firstCompositionDone = true
                    garTraceAsyncEnd(FIRST_COMPOSITION_SECTION, firstComposition)
                }
            }
            // GAR: `Fully drawn` for `am start -W` once the first frame is drawn, which is after
            // the first composition and the first measure
            ReportDrawn()
        }
        jankTracking = GarJank.track(window, "home") // GAR: frame times, see GarJank
    }

    override fun onDestroy() {
        jankTracking?.close() // GAR
        jankTracking = null
        // the wake word service remains active in the background,
        // so we need to release resources that it does not need manually
        sttInputDevice.reinitializeToReleaseResources()
        isCreated -= 1
        super.onDestroy()
    }

    companion object {
        private const val INTENT_BACKOFF_MILLIS = 100L
        private const val FIRST_COMPOSITION_SECTION = "Gar:MainActivity.firstComposition" // GAR
        private val TAG = MainActivity::class.simpleName
        const val ACTION_WAKE_WORD = "org.stypox.dicio.MainActivity.ACTION_WAKE_WORD"

        var isInForeground: Int = 0
            private set
        var isCreated: Int = 0
            private set

        private fun isAssistIntent(intent: Intent?): Boolean {
            return when (intent?.action) {
                ACTION_ASSIST, ACTION_VOICE_COMMAND -> true
                else -> false
            }
        }
    }
}
