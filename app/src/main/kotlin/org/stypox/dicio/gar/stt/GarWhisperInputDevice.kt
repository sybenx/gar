package org.stypox.dicio.gar.stt

import android.content.Context
import android.util.Log
import cc.aaro.gar.router.SpokenForm
import cc.aaro.gar.router.SpokenText
import cc.aaro.gar.stt.AudioCtx
import cc.aaro.gar.stt.Endpointer
import cc.aaro.gar.stt.SileroVad
import cc.aaro.gar.stt.SpeechDetector
import cc.aaro.gar.stt.TranscriptFilter
import cc.aaro.gar.stt.WavWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.dicio.BuildConfig
import org.stypox.dicio.R
import org.stypox.dicio.gar.conversation.GarContinuedListening
import org.stypox.dicio.gar.voicelog.GarVoiceLog
import org.stypox.dicio.gar.voicelog.VoiceTurn
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.input.VoiceActivity
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.math.log10
import kotlin.math.sqrt

/** The user-facing messages of the device's errors, from `strings_gar_stt_input.xml`. */
data class SttErrorMessages(
    val microphone: String,
    val transcribing: String,
    /** The mic stayed silenced by another app's capture: no "error" wording. */
    val silenced: String = microphone,
)

/**
 * GAR's speech input: Whisper through whisper.cpp, end-pointed by Silero VAD (design section 4).
 *
 * A capture opens the mic at once and asks [models] to load Whisper meanwhile. Each frame goes
 * through the [SpeechDetector] and the [Endpointer] on a dedicated capture thread; the level and
 * whether speech is heard go to [voiceActivity]. When speech ends (or on a tap, or at the 28 s
 * cap) the mic is released, the clip is transcribed, turned into GAR's spoken form, checked by
 * [TranscriptFilter] and sent as [InputEvent.Final] with Whisper's written text alongside.
 *
 * The state is always set before the listener runs, since the listener may start a new capture.
 * Build it with [create]; the constructor is for tests.
 */
class GarWhisperInputDevice(
    locale: StateFlow<Locale?>,
    private val models: SttModels,
    private val silencesBeforeStop: StateFlow<Int>,
    private val keepRecordings: StateFlow<Boolean>,
    private val recordingsDir: File,
    private val pcmSources: () -> PcmSource,
    private val speechDetectors: (File) -> SpeechDetector,
    private val spokenForm: (String) -> SpokenText,
    private val messages: SttErrorMessages,
    coroutineContext: CoroutineContext,
    private val captureContext: CoroutineContext,
    private val ioContext: CoroutineContext,
    private val logInfo: (String) -> Unit,
    private val logError: (String, Throwable?) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Opens the voice log's turn for a capture, null while the log is off. */
    private val voiceLog: () -> VoiceTurn? = { null },
    /**
     * The mic stayed silenced for [SILENCED_WAIT_MILLIS]: true when the UI took the turn over
     * (the sheet opens MainActivity listening, [GarMicSilenced]), on the capture thread.
     */
    private val onMicSilenced: () -> Boolean = { false },
) : SttInputDevice {

    private val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))

    /** Guards the fields below and every state change. */
    private val lock = Any()
    private var currentLocale: Locale? = locale.value
    private var installed: Boolean = models.installed.value
    private var transfer: SttTransfer = models.transfer.value
    private var loadError: Throwable? = null
    /** The [SttModels.loadGeneration] [loadError] happened in; it counts only in that one. */
    private var loadErrorGeneration = 0
    private var busy: WhisperState? = null // Listening or Transcribing
    /** The last capture's job: the next one waits for it to let go of the mic, VAD and buffer. */
    private var lastJob: Job? = null

    private val _state = MutableStateFlow(restingState())
    val state: StateFlow<WhisperState> = _state
    private val _uiState = MutableStateFlow(_state.value.toUiState())
    override val uiState: StateFlow<SttState> = _uiState

    private val _voiceActivity = MutableStateFlow(SILENT)
    override val voiceActivity: StateFlow<VoiceActivity> = _voiceActivity

    // used only on the capture thread, one capture at a time (see lastJob)
    private var speechDetector: SpeechDetector? = null
    private var endpointer: Endpointer? = null
    private var buffer = FloatArray(0)

    // used only by transcribe, one at a time
    private var spokenNamesCache: Pair<List<String>, List<String>>? = null

    init {
        scope.launch {
            // loadGeneration only to refresh: restingState reads its current value
            combine(locale, models.installed, models.transfer, models.loadGeneration) {
                newLocale, newInstalled, newTransfer, _ -> Triple(newLocale, newInstalled, newTransfer)
            }
                .collect { (newLocale, newInstalled, newTransfer) ->
                    synchronized(lock) {
                        currentLocale = newLocale
                        installed = newInstalled
                        transfer = newTransfer
                        refresh()
                    }
                }
        }
    }

    private fun restingState(): WhisperState {
        val locale = currentLocale
        val transfer = transfer
        return when {
            locale == null -> WhisperState.NotInitialized
            locale.language != ENGLISH -> WhisperState.NotAvailable
            installed -> loadError
                ?.takeIf { loadErrorGeneration == models.loadGeneration.value }
                ?.let { WhisperState.ErrorLoading(it) }
                ?: WhisperState.Ready
            transfer is SttTransfer.Running ->
                WhisperState.Downloading(transfer.currentBytes, transfer.totalBytes)
            transfer is SttTransfer.Failed -> WhisperState.ErrorDownloading(transfer.throwable)
            else -> WhisperState.NotDownloaded
        }
    }

    /** Publishes the current state. Call with [lock] held. */
    private fun refresh() {
        val state = busy ?: restingState()
        _state.value = state
        _uiState.value = state.toUiState()
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        synchronized(lock) {
            return when (_state.value) {
                WhisperState.Ready -> {
                    if (thenStartListeningEventListener == null) {
                        models.preload()
                    } else {
                        startCapture(thenStartListeningEventListener, retryLoad = false)
                    }
                    true
                }
                is WhisperState.ErrorLoading -> {
                    if (thenStartListeningEventListener == null) {
                        // only a tap or Settings forget the failure: activity re-creation calls
                        // this, and must not clear the crash guard (design 4.3)
                        models.preload()
                    } else {
                        startCapture(thenStartListeningEventListener, retryLoad = true)
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        val toCancel: CaptureSession
        synchronized(lock) {
            when (val s = _state.value) {
                WhisperState.NotInitialized,
                WhisperState.NotAvailable,
                is WhisperState.Downloading -> return
                WhisperState.NotDownloaded,
                is WhisperState.ErrorDownloading -> {
                    models.download()
                    return
                }
                WhisperState.Ready -> {
                    startCapture(eventListener, retryLoad = false)
                    return
                }
                is WhisperState.ErrorLoading -> {
                    startCapture(eventListener, retryLoad = true)
                    return
                }
                is WhisperState.Listening -> {
                    finish(s.session)
                    return
                }
                is WhisperState.Transcribing -> toCancel = s.session
            }
        }
        cancel(toCancel)
    }

    /**
     * Opens a continued-listening window for [listener] (gar/DESIGN.md, "Continued
     * conversation"): like a tap, but speech must start within [windowMillis], the capture ends
     * after [maxSpeechMillis] of speech, and less than [minSpeechMillis] of it is silence (None).
     * Only when the device is ready and idle; false otherwise.
     */
    fun tryListenBriefly(
        listener: (InputEvent) -> Unit,
        windowMillis: Long,
        maxSpeechMillis: Long,
        minSpeechMillis: Int = GarContinuedListening.MIN_SPEECH_MILLIS,
    ): Boolean {
        synchronized(lock) {
            if (_state.value != WhisperState.Ready) {
                return false
            }
            val endsAt = nanoTime() / 1_000_000 + windowMillis
            startCapture(
                listener,
                retryLoad = false,
                brief = BriefWindow(windowMillis, maxSpeechMillis, minSpeechMillis, endsAt),
            )
            return true
        }
    }

    /**
     * Closes a continued-listening window that still listens, sending None; nothing for other
     * captures, or once the window's speech is being transcribed.
     */
    fun stopBriefListening() {
        val session = synchronized(lock) {
            (busy as? WhisperState.Listening)?.session?.takeIf { it.brief != null }
        } ?: return
        cancel(session)
    }

    /** Discards the capture or the transcription that is running, and sends None. */
    override fun stopListening() {
        val session = synchronized(lock) { busy?.session() } ?: return
        cancel(session)
    }

    /** Stops capturing and transcribes what was heard; nothing while transcribing already. */
    override fun finishListening() {
        synchronized(lock) {
            val s = _state.value
            if (s is WhisperState.Listening) {
                finish(s.session)
            }
        }
    }

    override suspend fun destroy() {
        val session = synchronized(lock) { busy?.session() }
        if (session != null) {
            cancel(session)
        }
        val job = synchronized(lock) { lastJob }
        job?.join()
        scope.cancel()
        speechDetector?.close()
        speechDetector = null
        (captureContext as? Closeable)?.close()
    }

    /**
     * Starts a capture for [listener], a continued-listening window with [brief]. Call with
     * [lock] held, in Ready or ErrorLoading.
     */
    private fun startCapture(
        listener: (InputEvent) -> Unit,
        retryLoad: Boolean,
        brief: BriefWindow? = null,
    ) {
        if (retryLoad) {
            loadError = null
        }
        val session = CaptureSession(listener)
        session.voice = voiceLog()
        session.brief = brief
        busy = WhisperState.Listening(session, brief != null, brief?.endsAt ?: 0)
        refresh()

        val previous = lastJob
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            runSession(session, retryLoad)
        }
        session.job = job
        lastJob = job
        job.start()
    }

    /** Moves [session] from Listening to Transcribing. Call with [lock] held. */
    private fun finish(session: CaptureSession) {
        session.finishRequested = true
        toTranscribing(session)
    }

    private fun toTranscribing(session: CaptureSession) {
        val s = busy
        if (s is WhisperState.Listening && s.session === session && !session.ended) {
            busy = WhisperState.Transcribing(session)
            refresh()
        }
    }

    private fun cancel(session: CaptureSession) {
        if (!session.ended) {
            session.voice?.cancelled()
        }
        session.job?.cancel()
        end(session, InputEvent.None)
    }

    /**
     * Ends [session]: back to the resting state (ErrorLoading when [failedLoad] is set), then the
     * event. Does nothing if the session already ended.
     */
    private fun end(session: CaptureSession, event: InputEvent, failedLoad: Throwable? = null) {
        synchronized(lock) {
            if (session.ended) {
                return
            }
            session.ended = true
            if (busy?.session() === session) {
                busy = null
            }
            if (failedLoad != null) {
                loadError = failedLoad
                loadErrorGeneration = models.loadGeneration.value
            }
            refresh()
        }
        session.listener(event)
    }

    private suspend fun runSession(session: CaptureSession, retryLoad: Boolean) {
        if (retryLoad) {
            models.retryLoad()
        }
        models.acquire() // the model loads while the user talks
        val preparing = scope.launch { preparePrompt() }
        try {
            val decision = try {
                withContext(captureContext) { capture(session) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MicrophoneSilencedException) {
                // still silenced after the wait: a GAR activity on top gets the mic (the sheet
                // opens MainActivity listening); else say so without "error" wording
                if (onMicSilenced()) {
                    logInfo("The microphone stayed silenced, handed off")
                    session.voice?.captureEnded(END_MIC_SILENCED_HANDOFF, null, null, null)
                    end(session, InputEvent.None)
                } else {
                    logError("The microphone stayed silenced", e)
                    session.voice?.captureFailed(e)
                    end(session, InputEvent.Error(IOException(messages.silenced, e)))
                }
                return
            } catch (e: SpeechDetectorException) {
                logError("Could not load the VAD", e.cause)
                session.voice?.captureFailed(e.cause ?: e)
                end(session, InputEvent.Error(IOException(messages.transcribing, e.cause)))
                return
            } catch (e: Exception) {
                logError("Capture failed", e)
                session.voice?.captureFailed(e)
                end(session, InputEvent.Error(IOException(messages.microphone, e)))
                return
            } finally {
                preparing.cancel()
            }
            val clip = (decision as? Endpointer.Decision.Transcribe)?.clip
            session.voice?.captureEnded(
                if (session.speechCapped) "max_length"
                else endReason(decision, session.finishRequested),
                endpointer?.capturedSamples, clip?.samples, clip?.speechSamples,
            )
            val brief = session.brief
            when {
                clip == null -> end(session, InputEvent.None)
                // a window's short noise: silence, Whisper never runs on it
                brief != null && clip.speechSamples <
                    brief.minSpeechMillis.toLong() * AudioCtx.SAMPLE_RATE / 1000 ->
                    end(session, InputEvent.None)
                else -> transcribe(session, clip)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("Speech recognition failed", e)
            session.voice?.captureFailed(e)
            end(session, InputEvent.Error(IOException(messages.transcribing, e)))
        } finally {
            models.release()
        }
    }

    /**
     * Records until the endpointer decides, a tap finishes or the audio ends, on the capture
     * thread. The mic is released before this returns.
     */
    private suspend fun capture(session: CaptureSession): Endpointer.Decision {
        val detector = speechDetector ?: try {
            speechDetectors(models.vadFile)
        } catch (e: Exception) {
            throw SpeechDetectorException(e)
        }.also { speechDetector = it }
        detector.reset()
        val endpointer = endpointer?.takeIf { it.config.frameSamples == detector.frameSamples }
            ?: Endpointer(Endpointer.Config(frameSamples = detector.frameSamples))
                .also { endpointer = it }
        val brief = session.brief
        // a window's budget is what is left until the endsAt the arc shows, not windowMillis from
        // now: starting the capture took time (gar/DESIGN.md, "Continued conversation")
        val noSpeechMillis = {
            brief?.let { (it.endsAt - nanoTime() / 1_000_000).coerceAtLeast(0).toInt() }
                ?: (silencesBeforeStop.value * NO_SPEECH_MILLIS_PER_STEP)
        }
        endpointer.reset(noSpeechMillis = noSpeechMillis())
        val maxSpeechSamples = brief?.let { it.maxSpeechMillis * AudioCtx.SAMPLE_RATE / 1000 }
        var speechStart = 0
        if (buffer.size < endpointer.config.maxSamples) {
            buffer = FloatArray(endpointer.config.maxSamples)
        }
        val frame = FloatArray(detector.frameSamples)

        val source = pcmSources()
        var level = 0f
        var heardSpeech = false
        var frames = 0
        // frames read since the system silenced the mic, -1 while it isn't (see silencedFrames)
        var silencedFor = -1
        val maxSilencedFrames = silencedFrames(detector.frameSamples)
        try {
            source.start()
            while (true) {
                currentCoroutineContext().ensureActive()
                if (session.finishRequested) {
                    return endpointer.finish()
                }
                val read = source.read(frame)
                if (read < 0) {
                    return endpointer.finish()
                } else if (read == 0) {
                    continue
                } else if (read < frame.size) {
                    frame.fill(0f, read, frame.size)
                }

                frames++
                if (silencedFor >= 0) {
                    // the audio policy un-silences the same record when GAR wins the mic again;
                    // meanwhile it reads zeros, which the endpointer must not count
                    silencedFor++
                    if (frames % SILENCED_POLL_FRAMES != 0) {
                        continue
                    }
                    if (source.isSilenced()) {
                        if (silencedFor >= maxSilencedFrames) {
                            throw MicrophoneSilencedException()
                        }
                        continue
                    }
                    session.voice?.stage(STAGE_MIC_UNSILENCED)
                    logInfo("The microphone came back after ${silencedFor * 1000L * detector
                        .frameSamples / AudioCtx.SAMPLE_RATE} ms")
                    silencedFor = -1
                    if (!heardSpeech) {
                        // the no-speech timeout counts from now, not from the silent part
                        detector.reset()
                        endpointer.reset(noSpeechMillis = noSpeechMillis())
                    }
                    continue
                }

                frame.copyInto(buffer, endpointer.capturedSamples)
                val decision = endpointer.accept(detector.probability(frame))
                if (!heardSpeech && endpointer.speaking) {
                    heardSpeech = true
                    speechStart = endpointer.capturedSamples
                    session.voice?.speechStarted(endpointer.capturedSamples)
                }
                level = smoothLevel(level, levelOf(frame, read))
                _voiceActivity.value = VoiceActivity(level, endpointer.speaking)

                if (frames % SILENCED_CHECK_FRAMES == 0 && source.isSilenced()) {
                    // GAR: another app's capture won the mic (the sheet isn't the top app, see
                    // gar/DESIGN.md "Assistant sheet"); wait for it within the turn
                    session.voice?.stage(STAGE_MIC_SILENCED)
                    logInfo("The microphone is silenced by the system, waiting")
                    silencedFor = 0
                    _voiceActivity.value = SILENT
                    continue
                }
                if (decision != Endpointer.Decision.Continue) {
                    return decision
                }
                if (maxSpeechSamples != null && heardSpeech &&
                    endpointer.capturedSamples - speechStart >= maxSpeechSamples
                ) {
                    session.speechCapped = true // a window's follow-up is short
                    return endpointer.finish()
                }
            }
        } finally {
            _voiceActivity.value = SILENT
            try {
                source.stop()
            } catch (e: Exception) {
                logError("Could not stop the audio source", e)
            }
        }
    }

    private suspend fun transcribe(session: CaptureSession, clip: Endpointer.Clip) {
        synchronized(lock) {
            if (session.ended) {
                return
            }
            toTranscribing(session)
        }
        val endOfSpeech = nanoTime()
        val samples = clip.samples
        if (clip.start > 0) {
            buffer.copyInto(buffer, 0, clip.start, clip.end)
        }
        val audioCtx = AudioCtx.forSamples(samples)
        val names = models.promptNames.value

        val transcript = try {
            models.use { it.transcribe(buffer, samples, audioCtx, names = names) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SttLoadException) {
            logError("Could not load the speech model", e)
            session.voice?.captureFailed(e)
            end(session, InputEvent.Error(e), failedLoad = e)
            return
        } catch (e: Exception) {
            logError("Whisper failed", e)
            session.voice?.captureFailed(e)
            end(session, InputEvent.Error(IOException(messages.transcribing, e)))
            return
        }

        val text = spokenForm(transcript.text)
        val speechMillis = (clip.speechSamples * 1000L / AudioCtx.SAMPLE_RATE).toInt()
        val rejection = TranscriptFilter.rejection(
            text.spoken,
            transcript.noSpeechProb,
            speechMillis,
            transcript.confidence,
            spokenNames(transcript.promptNames),
        )
        val accepted = rejection == null
        // one copy for both the recordings and the voice log, which only read it
        val clipCopy = if (keepRecordings.value || session.voice != null) {
            buffer.copyOf(samples)
        } else {
            null
        }
        val recording = clipCopy?.takeIf { keepRecordings.value }
        session.voice?.transcribed(transcript, text.spoken, text.written, rejection, clipCopy)

        end(session, if (accepted) {
            InputEvent.Final(
                listOf(text.spoken to TranscriptFilter.score(transcript.confidence)),
                text.written,
                spoken = true,
            )
        } else {
            InputEvent.None
        })

        logInfo(String.format(
            Locale.ROOT,
            "clip %.2f s, speech %.2f s, audio_ctx %d, encode %d ms, total %d ms, tokens %d, " +
                "no_speech %.2f, confidence %.2f, prompt %d tokens, end to final %d ms%s",
            samples / AudioCtx.SAMPLE_RATE.toFloat(),
            clip.speechSamples / AudioCtx.SAMPLE_RATE.toFloat(),
            transcript.audioCtx,
            transcript.encodeMicros / 1000,
            transcript.totalMicros / 1000,
            transcript.tokens,
            transcript.noSpeechProb,
            transcript.confidence,
            transcript.promptTokens,
            (nanoTime() - endOfSpeech) / 1_000_000,
            if (accepted) "" else ", dropped",
        ))

        if (recording != null) {
            scope.launch(ioContext) {
                try {
                    saveRecording(recording, transcript.text, text.spoken, accepted)
                } catch (e: IOException) {
                    logError("Could not keep the recording", e)
                }
            }
        }
    }

    /**
     * Fits the prompt for the contact names on the engine while the user talks (the names may
     * arrive during capture), so that transcribing doesn't wait for it. A load error is left to
     * [transcribe] to report.
     */
    private suspend fun preparePrompt() {
        models.promptNames.collectLatest { names ->
            if (names.isNotEmpty()) {
                try {
                    models.use { it.prepare(names) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // transcribe fits it, or reports why the model can't be used
                }
            }
        }
    }

    /**
     * The prompt's [names] in spoken form, for [TranscriptFilter]; kept while the names stay
     * the same. Only one transcription runs at a time.
     */
    private fun spokenNames(names: List<String>): List<String> {
        val cached = spokenNamesCache
        if (cached != null && cached.first == names) {
            return cached.second
        }
        return names.map { spokenForm(it).spoken }.also { spokenNamesCache = names to it }
    }

    /** Debug builds: the clip as WAV plus its text, keeping the last [KEPT_RECORDINGS]. */
    private fun saveRecording(pcm: FloatArray, raw: String, spoken: String, accepted: Boolean) {
        recordingsDir.mkdirs()
        val name = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        File(recordingsDir, "$name.wav").outputStream().buffered().use { WavWriter.write(it, pcm) }
        File(recordingsDir, "$name.txt").writeText(
            "raw: $raw\nspoken: $spoken\n" + if (accepted) "" else "dropped\n"
        )
        recordingsDir.listFiles { file -> file.name.endsWith(".wav") }
            ?.sortedByDescending { it.name }
            ?.drop(KEPT_RECORDINGS)
            ?.forEach { wav ->
                wav.delete()
                File(recordingsDir, wav.name.removeSuffix(".wav") + ".txt").delete()
            }
    }

    companion object {
        private const val TAG = "GarStt"
        private const val ENGLISH = "en"

        /**
         * Dicio's `stt_silence_duration` (1 to 7, default 2) times this is the no-speech timeout:
         * how long the mic waits for speech to start before giving up, from when the mic opens
         * (the earcon plays as it does). 3 s makes the default 6 s, like Google Assistant (about 5
         * to 8 s); with 2 s the default gave up after 4 s, and two starts on 2026-11-02 ended
         * no_speech.
         */
        const val NO_SPEECH_MILLIS_PER_STEP = 3_000

        /** About half a second of 32 ms frames, as SongRecorder checks. */
        private const val SILENCED_CHECK_FRAMES = 16

        /** While silenced, the mic is checked about every 128 ms (4 frames of 32 ms). */
        private const val SILENCED_POLL_FRAMES = 4

        /**
         * How long a silenced mic is waited for within the turn before [onMicSilenced]: another
         * app's short capture ends by then, a lasting one needs GAR on top.
         */
        const val SILENCED_WAIT_MILLIS = 2_000

        const val STAGE_MIC_SILENCED = "mic_silenced"
        const val STAGE_MIC_UNSILENCED = "mic_unsilenced"
        const val END_MIC_SILENCED_HANDOFF = "mic_silenced_handoff"

        /** [SILENCED_WAIT_MILLIS] in frames of [frameSamples]. */
        internal fun silencedFrames(frameSamples: Int): Int =
            (SILENCED_WAIT_MILLIS.toLong() * AudioCtx.SAMPLE_RATE / 1000 / frameSamples).toInt()

        const val KEPT_RECORDINGS = 50

        private const val MIN_DB = -60.0
        private const val MAX_DB = -10.0

        private val SILENT = VoiceActivity(0f, false)

        /** Why capturing stopped, as the voice log says it. */
        internal fun endReason(decision: Endpointer.Decision, finishRequested: Boolean): String {
            val reason = when (decision) {
                is Endpointer.Decision.Transcribe -> decision.reason
                is Endpointer.Decision.Discard -> decision.reason
                Endpointer.Decision.Continue -> return "unknown"
            }
            return when (reason) {
                Endpointer.Reason.END_OF_SPEECH -> "vad_silence"
                Endpointer.Reason.CAP -> "max_length"
                // a tap, or SttService's finish; else the audio source ran out (debug WAVs)
                Endpointer.Reason.FINISHED -> if (finishRequested) "user_tap" else "audio_end"
                Endpointer.Reason.NO_SPEECH -> "no_speech"
                Endpointer.Reason.TOO_LITTLE -> "too_little"
            }
        }

        /** The first [samples] of [frame] as a level from 0 (-60 dBFS) to 1 (-10 dBFS). */
        internal fun levelOf(frame: FloatArray, samples: Int): Float {
            var sum = 0.0
            for (i in 0 until samples) {
                sum += frame[i] * frame[i]
            }
            val rms = sqrt(sum / samples)
            if (rms <= 0.0) {
                return 0f
            }
            val db = 20 * log10(rms)
            return ((db - MIN_DB) / (MAX_DB - MIN_DB)).toFloat().coerceIn(0f, 1f)
        }

        /** Rises fast, falls slowly, so the ring doesn't flicker between syllables. */
        internal fun smoothLevel(previous: Float, current: Float): Float =
            previous + (current - previous) * (if (current > previous) 0.6f else 0.2f)

        fun create(
            appContext: Context,
            locale: StateFlow<Locale>,
            models: SttModels,
            silencesBeforeStop: StateFlow<Int>,
            keepRecordings: StateFlow<Boolean>,
        ): GarWhisperInputDevice {
            val filesDir = appContext.filesDir
            val captureDispatcher = Executors
                .newSingleThreadExecutor { Thread(it, "GarSttCapture") }
                .asCoroutineDispatcher()
            return GarWhisperInputDevice(
                locale = locale,
                models = models,
                silencesBeforeStop = silencesBeforeStop,
                keepRecordings = if (BuildConfig.DEBUG) keepRecordings else MutableStateFlow(false),
                recordingsDir = File(filesDir, "gar-stt-recordings"),
                pcmSources = {
                    (if (BuildConfig.DEBUG) DebugWavPcmSource.takeNext(filesDir) else null)
                        ?: AudioRecordPcmSource()
                },
                speechDetectors = { SileroVad(it) },
                spokenForm = SpokenForm::of,
                voiceLog = GarVoiceLog::captureStarted,
                messages = SttErrorMessages(
                    microphone = appContext.getString(R.string.gar_stt_error_microphone),
                    transcribing = appContext.getString(R.string.gar_stt_error_transcribing),
                    silenced = appContext.getString(R.string.gar_stt_error_microphone_busy),
                ),
                onMicSilenced = GarMicSilenced::handOff,
                coroutineContext = Dispatchers.Default,
                captureContext = captureDispatcher,
                ioContext = Dispatchers.IO,
                logInfo = { Log.i(TAG, it) },
                logError = { message, t -> Log.e(TAG, message, t) },
            )
        }
    }
}

/** The system kept the mic silenced for [GarWhisperInputDevice.SILENCED_WAIT_MILLIS]. */
private class MicrophoneSilencedException :
    IOException("The microphone is silenced by the system")

/** The VAD could not be loaded: not a microphone problem. */
private class SpeechDetectorException(cause: Exception) : Exception(cause)

private fun WhisperState.session(): CaptureSession? = when (this) {
    is WhisperState.Listening -> session
    is WhisperState.Transcribing -> session
    else -> null
}
