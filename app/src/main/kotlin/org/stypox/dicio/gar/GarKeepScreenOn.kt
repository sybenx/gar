package org.stypox.dicio.gar

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.home.InteractionLog

/**
 * "Keep the screen on while GAR is open": while a GAR screen is in the foreground its window has
 * [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON], so the phone doesn't fall asleep while the user
 * reads an answer or waits for one. The flag only acts while the window is visible and is cleared
 * once the activity stops, so the phone sleeps as usual otherwise. No wake lock is held.
 *
 * Over the lock screen (the wake word turned the screen on) the flag is on only while the user
 * talks to GAR: while speech is loading, listening or being transcribed, while a question is being
 * answered, and for [LOCKED_WINDOW_MILLIS] after that. So the answer can be read hands-free, and a
 * wake word heard in a pocket still lets the screen sleep once listening times out. Unlocking with
 * GAR open turns it on for good.
 */
object GarKeepScreenOn {
    /** The switch, on unless the user turned it off. */
    fun settingOn(settings: GarSettings): Boolean = !settings.screenMaySleep

    /** How long the flag stays on over the lock screen after the user last talked to GAR. */
    const val LOCKED_WINDOW_MILLIS = 30_000L

    /**
     * Whether the window of a screen in [state] should keep the screen on; [interacting] is whether
     * the user talks to GAR or did within [LOCKED_WINDOW_MILLIS], which only counts when locked.
     */
    fun shouldKeepScreenOn(
        state: Lifecycle.State,
        settingOn: Boolean,
        keyguardLocked: Boolean,
        interacting: Boolean,
    ): Boolean = settingOn &&
        (!keyguardLocked || interacting) &&
        state.isAtLeast(Lifecycle.State.STARTED)

    /** Whether speech input or the evaluator is busy: loading, listening, transcribing, answering. */
    fun busy(stt: SttState?, log: InteractionLog): Boolean =
        log.pendingQuestion != null || when (stt) {
            is SttState.Loading,
            SttState.Listening,
            SttState.Transcribing,
            SttState.WaitingForResult -> true
            else -> false
        }

    /**
     * Sets or clears the flag on [activity]'s window whenever its lifecycle, the setting, the lock
     * state or the interaction with GAR changes, until the activity is destroyed. Call it from `onCreate`.
     */
    fun follow(activity: ComponentActivity) {
        val entryPoint = EntryPointAccessors
            .fromApplication(activity.applicationContext, GarEntryPoint::class.java)
        val keyguard = activity.getSystemService(KeyguardManager::class.java)

        activity.lifecycleScope.launch {
            combine(
                activity.lifecycle.currentStateFlow,
                entryPoint.garSettings().data.map(::settingOn).distinctUntilChanged(),
                interacting(entryPoint.sttInputDevice().uiState, entryPoint.skillEvaluator().state),
                // the lock state is read again at every change, and at every unlock
                unlocks(activity),
            ) { state, settingOn, interacting, _ ->
                shouldKeepScreenOn(state, settingOn, keyguard?.isKeyguardLocked == true, interacting)
            }
                .distinctUntilChanged()
                .collect { keepOn ->
                    if (keepOn) {
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
        }
    }

    /**
     * True while [busy], and for [LOCKED_WINDOW_MILLIS] after it stops being busy or after the
     * interaction log last changed (an answer came). The first value carries no window, so that
     * opening GAR over the lock screen without talking to it doesn't hold the screen on.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun interacting(stt: Flow<SttState?>, log: Flow<InteractionLog>): Flow<Boolean> {
        var first = true
        return combine(stt, log) { sttState, interactionLog ->
            Pair(busy(sttState, interactionLog), interactionLog)
        }
            .distinctUntilChanged()
            .transformLatest { (busy, _) ->
                val wasFirst = first
                first = false
                if (busy) {
                    emit(true)
                } else if (!wasFirst) {
                    emit(true)
                    delay(LOCKED_WINDOW_MILLIS)
                    emit(false)
                } else {
                    emit(false)
                }
            }
            .distinctUntilChanged()
    }

    /** Emits once at the start and then whenever the user unlocks the phone. */
    fun unlocks(context: Context): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        // a system broadcast, so it arrives even though the receiver isn't exported
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        trySend(Unit)
        awaitClose { context.unregisterReceiver(receiver) }
    }
}
