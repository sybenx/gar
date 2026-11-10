package org.stypox.dicio.gar.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.stypox.dicio.di.SpeechOutputDeviceWrapper
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.io.input.SttState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one process-wide barge-in collector: on each transition into SttState.Listening it calls
 * GarTurnElement.markCaptureStart(speech.isSpeaking) and then speech.stopSpeaking(), exactly once.
 * Idempotent: the first call starts it in a process scope (Dispatchers.Main.immediate), later calls do nothing.
 */
object GarBargeIn {
    private val collector = GarBargeInCollector()
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    fun start(stt: SttInputDeviceWrapper, speech: SpeechOutputDeviceWrapper) {
        collector.start(
            scope = scope,
            states = stt.uiState,
            isSpeaking = { speech.isSpeaking },
            stopSpeaking = speech::stopSpeaking,
        )
    }
}

/**
 * [GarBargeIn]'s logic, apart for tests: [start] collects once, however often it is called (the
 * home screen's view model and the sheet both call it, and they used to overwrite each other's
 * mark).
 */
internal class GarBargeInCollector(
    private val markCaptureStart: (Boolean) -> Unit = { GarTurnElement.markCaptureStart(it) },
) {
    private val started = AtomicBoolean(false)

    /** Starts collecting [states] in [scope]; false (and nothing started) when already running. */
    fun start(
        scope: CoroutineScope,
        states: Flow<SttState?>,
        isSpeaking: () -> Boolean,
        stopSpeaking: () -> Unit,
    ): Boolean {
        if (!started.compareAndSet(false, true)) {
            return false
        }
        scope.launch {
            states
                .distinctUntilChanged()
                .filter { it == SttState.Listening }
                .collect {
                    // the turn knows whether the user talked over GAR (a barge-in)
                    markCaptureStart(isSpeaking())
                    stopSpeaking()
                }
        }
        return true
    }
}
