package org.stypox.dicio.gar.stt

import kotlinx.coroutines.Job
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.util.Progress

/**
 * The internal state of [GarWhisperInputDevice], shown to the UI as an [SttState] (design 4.3).
 * The resting states follow the locale and [SttModels]; [Listening] and [Transcribing] hold the
 * capture that is running.
 */
sealed interface WhisperState {
    /** No locale yet. */
    data object NotInitialized : WhisperState

    /** The models are English only. */
    data object NotAvailable : WhisperState

    data object NotDownloaded : WhisperState

    /** [totalBytes] covers the model and the VAD file. */
    data class Downloading(val currentBytes: Long, val totalBytes: Long) : WhisperState

    data class ErrorDownloading(val throwable: Throwable) : WhisperState

    /**
     * The files are on disk. The model may or may not be loaded: the mic opens at once either way
     * and the model loads while the user talks, so the UI shows a plain, loaded mic.
     */
    data object Ready : WhisperState

    /** The last load failed; a tap retries it and starts listening. */
    data class ErrorLoading(val throwable: Throwable) : WhisperState

    /**
     * Capturing; the end of speech, a tap or the cap moves on to [Transcribing]. [brief] for a
     * continued-listening window, which ends at [endsAt] (`System.nanoTime` in milliseconds)
     * unless speech starts.
     */
    class Listening internal constructor(
        internal val session: CaptureSession,
        val brief: Boolean = false,
        val endsAt: Long = 0,
    ) : WhisperState

    /** Whisper is turning the clip into text; a tap cancels. */
    class Transcribing internal constructor(internal val session: CaptureSession) : WhisperState

    fun toUiState(): SttState = when (this) {
        NotInitialized -> SttState.NotInitialized
        NotAvailable -> SttState.NotAvailable
        NotDownloaded -> SttState.NotDownloaded
        is Downloading -> SttState.Downloading(Progress(0, 1, currentBytes, totalBytes))
        is ErrorDownloading -> SttState.ErrorDownloading(throwable)
        Ready -> SttState.Loaded
        is ErrorLoading -> SttState.ErrorLoading(throwable)
        is Listening -> SttState.Listening
        is Transcribing -> SttState.Transcribing
    }
}

/**
 * One run from opening the mic to sending Final, None or Error to [listener]. [ended] is guarded
 * by the device's lock; the flags are read by the capture loop between frames.
 */
internal class CaptureSession(val listener: (InputEvent) -> Unit) {
    /** Set by a tap or SttService: stop capturing and transcribe what was heard. */
    @Volatile
    var finishRequested = false

    /** Whether the event was sent; nothing is sent after it. */
    var ended = false

    /** Captures, then transcribes; cancelled by a tap while transcribing or by stopListening. */
    var job: Job? = null

    /** The voice log's turn for this capture, null while the log is off. */
    var voice: org.stypox.dicio.gar.voicelog.VoiceTurn? = null

    /** Set for a continued-listening window. */
    var brief: BriefWindow? = null

    /** The window's speech reached [BriefWindow.maxSpeechMillis]: capturing ended there. */
    @Volatile
    var speechCapped = false
}

/**
 * A continued-listening window's limits: speech must start within [windowMillis], ends after
 * [maxSpeechMillis], and less than [minSpeechMillis] of it is silence.
 */
internal data class BriefWindow(
    val windowMillis: Long,
    val maxSpeechMillis: Long,
    val minSpeechMillis: Int,
    val endsAt: Long,
)
