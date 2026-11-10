@file:OptIn(ExperimentalCoroutinesApi::class)

package org.stypox.dicio.gar.stt

import cc.aaro.gar.router.SpokenText
import cc.aaro.gar.stt.SpeechDetector
import cc.aaro.gar.stt.Transcriber
import cc.aaro.gar.stt.Transcript
import cc.aaro.gar.stt.WavReader
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.util.Progress
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext

private const val FRAME = 512
private const val FRAME_MILLIS = 32L
private const val SPEECH_AMPLITUDE = 0.1f

/** What the fake mic plays: per frame, silence (false) or speech (true), then [after]. */
private data class Script(
    val frames: List<Boolean>,
    val after: After = After.SILENCE,
    val failStart: Boolean = false,
    val silenced: Boolean = false,
    /** The system silences the mic for the first this many frames: they read as zeros. */
    val silencedFrames: Int = 0,
) {
    enum class After { SILENCE, SPEECH, END }
}

private fun script(
    silence: Int = 10,
    speech: Int = 20,
    after: Script.After = Script.After.SILENCE,
) = Script(List(silence) { false } + List(speech) { true }, after)

/** Plays a [Script] at real-time pace in virtual time; speech frames hold [SPEECH_AMPLITUDE]. */
private class FakePcmSource(private val script: Script) : PcmSource {
    var started = false
    var stopped = false
    private var position = 0

    override fun start() {
        if (script.failStart) throw IOException("no mic")
        started = true
    }

    override suspend fun read(frame: FloatArray): Int {
        check(started && !stopped) { "read outside start/stop" }
        val speech = if (position < script.frames.size) {
            script.frames[position]
        } else when (script.after) {
            Script.After.SILENCE -> false
            Script.After.SPEECH -> true
            Script.After.END -> return -1
        }
        val silencedNow = position < script.silencedFrames
        position++
        delay(FRAME_MILLIS)
        frame.fill(if (speech && !silencedNow) SPEECH_AMPLITUDE else 0f)
        return frame.size
    }

    override fun isSilenced() = script.silenced || position < script.silencedFrames

    override fun stop() {
        stopped = true
    }
}

/** Speech wherever the fake mic put [SPEECH_AMPLITUDE]. */
private class FakeSpeechDetector : SpeechDetector {
    var resets = 0
    var closed = false
    override val frameSamples = FRAME
    override fun probability(frame: FloatArray): Float {
        check(!closed)
        return if (frame[0] > SPEECH_AMPLITUDE / 2) 0.9f else 0.05f
    }
    override fun reset() {
        resets++
    }
    override fun close() {
        closed = true
    }
}

private class FakeTranscriber : Transcriber {
    var transcript = transcript("Set a timer for 5 minutes.")
    var failure: Throwable? = null
    var millis = 300L
    var calls = 0
    var cancelled = false
    var lastPcm = FloatArray(0)
    var lastAudioCtx = 0
    var lastNames: List<String>? = null
    val prepared = mutableListOf<List<String>>()

    override suspend fun prepare(names: List<String>) {
        prepared += names
    }

    override suspend fun transcribe(
        pcm: FloatArray,
        samples: Int,
        audioCtx: Int,
        maxTokens: Int,
        names: List<String>,
    ): Transcript {
        calls++
        lastPcm = pcm.copyOf(samples)
        lastAudioCtx = audioCtx
        lastNames = names
        try {
            delay(millis)
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        }
        failure?.let { throw it }
        // as if every name fitted, two tokens each
        return transcript.copy(audioCtx = audioCtx, promptNames = names, promptTokens = 2 * names.size)
    }
}

private fun transcript(text: String, noSpeechProb: Float = 0.02f, confidence: Float = 0.9f) =
    Transcript(text, noSpeechProb, confidence, tokens = 8, audioCtx = 0, encodeMicros = 80_000,
        totalMicros = 150_000)

private class FakeSttModels(installed: Boolean = true) : SttModels {
    override val installed = MutableStateFlow(installed)
    override val transfer = MutableStateFlow<SttTransfer>(SttTransfer.Idle)
    override val loadGeneration = MutableStateFlow(0)
    override val vadFile = File("vad.bin")
    override val promptNames = MutableStateFlow<List<String>>(emptyList())
    val transcriber = FakeTranscriber()
    var loadMillis = 0L
    var loadFailure: SttLoadException? = null
    var downloads = 0
    var acquires = 0
    var releases = 0
    var preloads = 0
    var retries = 0

    override fun download() {
        downloads++
    }
    override fun acquire() {
        acquires++
    }
    override fun release() {
        releases++
    }
    override fun preload() {
        preloads++
    }
    override suspend fun retryLoad() {
        retries++
        loadGeneration.value++
    }
    override suspend fun <R> use(block: suspend (Transcriber) -> R): R {
        delay(loadMillis)
        loadFailure?.let { throw it }
        return block(transcriber)
    }
}

/** Like SpokenForm for the simple texts here: no brackets, no punctuation, lowercase. */
private fun fakeSpokenForm(raw: String): SpokenText {
    val written = raw.replace(Regex("\\[[^]]*]"), "").trim()
    val spoken = written.lowercase()
        .replace("5", "five")
        .replace(Regex("[^a-z' ]"), "")
        .trim()
    return SpokenText(spoken = spoken, written = written)
}

private val MESSAGES = SttErrorMessages(
    microphone = "mic failed", transcribing = "stt failed", silenced = "mic busy"
)

private class Fixture(
    scope: TestScope,
    locale: Locale? = Locale.US,
    installed: Boolean = true,
    keepRecordings: Boolean = false,
    silenceSetting: Int = 2,
    nanoTime: () -> Long = System::nanoTime,
) {
    val locale = MutableStateFlow(locale)
    val models = FakeSttModels(installed)
    val detector = FakeSpeechDetector()
    var detectorsBuilt = 0
    var script = script()
    val sources = mutableListOf<FakePcmSource>()
    val infoLogs = mutableListOf<String>()
    val errorLogs = mutableListOf<String>()
    val recordingsDir: File = Files.createTempDirectory("gar-stt-test").toFile()
    /** What the UI says when the mic stays silenced: whether it took the turn over. */
    var handsOff = false
    var handOffs = 0

    /** Each event with the UI state the device showed when the listener ran. */
    val events = mutableListOf<Pair<InputEvent, SttState>>()
    lateinit var device: GarWhisperInputDevice
    val listener: (InputEvent) -> Unit = { events.add(it to device.uiState.value) }

    init {
        device = GarWhisperInputDevice(
            locale = this.locale,
            models = models,
            silencesBeforeStop = MutableStateFlow(silenceSetting),
            keepRecordings = MutableStateFlow(keepRecordings),
            recordingsDir = recordingsDir,
            pcmSources = { FakePcmSource(script).also { sources.add(it) } },
            speechDetectors = {
                detectorsBuilt++
                detector
            },
            spokenForm = ::fakeSpokenForm,
            messages = MESSAGES,
            // the test scheduler's time, cancelled with the background scope, but foreground work
            // so that advanceUntilIdle runs it
            coroutineContext = StandardTestDispatcher(scope.testScheduler) +
                scope.backgroundScope.coroutineContext[Job]!!,
            captureContext = EmptyCoroutineContext,
            ioContext = EmptyCoroutineContext,
            logInfo = { infoLogs.add(it) },
            logError = { message, _ -> errorLogs.add(message) },
            nanoTime = nanoTime,
            onMicSilenced = {
                handOffs++
                handsOff
            },
        )
    }

    val ui: SttState get() = device.uiState.value
}

/** Speech of 20 frames after 10 of silence ends at frame 54, then 300 ms of Whisper. */
private const val END_OF_SPEECH_MILLIS = 55 * FRAME_MILLIS

class GarWhisperInputDeviceTest : StringSpec({
    "the resting states follow the locale and the model files" {
        runTest {
            Fixture(this, locale = null).ui shouldBe SttState.NotInitialized
            Fixture(this, locale = Locale.GERMANY).ui shouldBe SttState.NotAvailable
            Fixture(this, installed = false).ui shouldBe SttState.NotDownloaded
            Fixture(this).ui shouldBe SttState.Loaded

            val fixture = Fixture(this)
            fixture.locale.value = Locale.ITALY
            runCurrent()
            fixture.ui shouldBe SttState.NotAvailable
            fixture.device.tryLoad(fixture.listener) shouldBe false
            fixture.device.onClick(fixture.listener)
            fixture.models.acquires shouldBe 0
        }
    }

    "a tap downloads, progress shows, a failure retries on a tap" {
        runTest {
            val fixture = Fixture(this, installed = false)
            fixture.device.tryLoad(fixture.listener) shouldBe false
            fixture.device.tryLoad(null) shouldBe false
            fixture.device.onClick(fixture.listener)
            fixture.models.downloads shouldBe 1

            fixture.models.transfer.value = SttTransfer.Running(10, 100)
            runCurrent()
            fixture.ui shouldBe SttState.Downloading(Progress(0, 1, 10, 100))
            fixture.device.onClick(fixture.listener)
            fixture.device.tryLoad(fixture.listener) shouldBe false
            fixture.models.downloads shouldBe 1

            val failure = IOException("network")
            fixture.models.transfer.value = SttTransfer.Failed(failure)
            runCurrent()
            fixture.ui shouldBe SttState.ErrorDownloading(failure)
            fixture.device.tryLoad(fixture.listener) shouldBe false
            fixture.device.onClick(fixture.listener)
            fixture.models.downloads shouldBe 2

            fixture.models.transfer.value = SttTransfer.Idle
            fixture.models.installed.value = true
            runCurrent()
            fixture.ui shouldBe SttState.Loaded
            fixture.events shouldHaveSize 0
        }
    }

    "tryLoad without a listener preloads and doesn't listen" {
        runTest {
            val fixture = Fixture(this)
            fixture.device.tryLoad(null) shouldBe true
            fixture.models.preloads shouldBe 1
            advanceUntilIdle()
            fixture.ui shouldBe SttState.Loaded
            fixture.sources shouldHaveSize 0
        }
    }

    "a command: listening, transcribing, then Final with the spoken and written text" {
        runTest {
            val fixture = Fixture(this)
            fixture.device.onClick(fixture.listener)
            fixture.ui shouldBe SttState.Listening
            runCurrent()
            fixture.models.acquires shouldBe 1

            advanceTimeBy(20 * FRAME_MILLIS)
            runCurrent()
            fixture.ui shouldBe SttState.Listening
            fixture.device.voiceActivity.value.speaking shouldBe true
            (fixture.device.voiceActivity.value.level > 0.5f) shouldBe true

            advanceTimeBy(END_OF_SPEECH_MILLIS - 20 * FRAME_MILLIS + 100)
            runCurrent()
            fixture.ui shouldBe SttState.Transcribing
            fixture.sources.single().stopped shouldBe true // the mic is free before Whisper runs
            fixture.device.voiceActivity.value.speaking shouldBe false

            advanceUntilIdle()
            fixture.events shouldBe listOf(
                InputEvent.Final(
                    listOf("set a timer for five minutes" to 0.9f),
                    "Set a timer for 5 minutes.",
                    spoken = true,
                ) to SttState.Loaded,
            )
            fixture.models.releases shouldBe 1
            fixture.detector.resets shouldBe 1
            fixture.infoLogs.single() shouldContain "audio_ctx 128"
        }
    }

    "Whisper gets the clip from 300 ms before the speech to 200 ms after it" {
        runTest {
            val fixture = Fixture(this)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            val pcm = fixture.models.transcriber.lastPcm
            // speech starts at frame 10, the clip 4800 samples earlier; it ends 3200 after frame 29
            pcm.size shouldBe 30 * FRAME + 3_200 - (10 * FRAME - 4_800)
            pcm[4_799] shouldBe 0f
            pcm[4_800] shouldBe SPEECH_AMPLITUDE
            pcm[4_800 + 20 * FRAME - 1] shouldBe SPEECH_AMPLITUDE
            pcm[4_800 + 20 * FRAME] shouldBe 0f
            fixture.models.transcriber.lastAudioCtx shouldBe 128
        }
    }

    "tryLoad is false while listening and while transcribing" {
        runTest {
            val fixture = Fixture(this)
            fixture.device.tryLoad(fixture.listener) shouldBe true
            fixture.ui shouldBe SttState.Listening
            fixture.device.tryLoad(fixture.listener) shouldBe false
            fixture.device.tryLoad(null) shouldBe false

            advanceTimeBy(END_OF_SPEECH_MILLIS + 100)
            runCurrent()
            fixture.ui shouldBe SttState.Transcribing
            fixture.device.tryLoad(fixture.listener) shouldBe false
            fixture.device.tryLoad(null) shouldBe false

            advanceUntilIdle()
            fixture.events shouldHaveSize 1
            fixture.sources shouldHaveSize 1
        }
    }

    "stopListening while listening sends None at once and frees the mic" {
        runTest {
            val fixture = Fixture(this)
            fixture.device.onClick(fixture.listener)
            advanceTimeBy(500)
            runCurrent()
            fixture.device.stopListening()
            fixture.events shouldBe listOf(InputEvent.None to SttState.Loaded)

            advanceUntilIdle()
            fixture.events shouldHaveSize 1
            fixture.sources.single().stopped shouldBe true
            fixture.models.transcriber.calls shouldBe 0
            fixture.models.acquires shouldBe 1
            fixture.models.releases shouldBe 1
        }
    }

    "a tap while listening transcribes what was heard" {
        runTest {
            val fixture = Fixture(this)
            fixture.script = script(silence = 5, speech = 0, after = Script.After.SPEECH)
            fixture.device.onClick(fixture.listener)
            advanceTimeBy(30 * FRAME_MILLIS)
            runCurrent()
            fixture.device.onClick(fixture.listener)
            fixture.ui shouldBe SttState.Transcribing

            advanceUntilIdle()
            fixture.events.single().first.shouldBeInstanceOf<InputEvent.Final>()
            fixture.events.single().second shouldBe SttState.Loaded
            fixture.sources.single().stopped shouldBe true
        }
    }

    "finishListening with too little speech sends None without Whisper" {
        runTest {
            val fixture = Fixture(this)
            fixture.script = script(silence = 5, speech = 4)
            fixture.device.tryLoad(fixture.listener)
            advanceTimeBy(10 * FRAME_MILLIS)
            runCurrent()
            fixture.device.finishListening()
            fixture.ui shouldBe SttState.Transcribing
            advanceUntilIdle()
            fixture.events shouldBe listOf(InputEvent.None to SttState.Loaded)
            fixture.models.transcriber.calls shouldBe 0
        }
    }

    "no speech before the timeout gives None; the timeout is Dicio's setting x 3 s" {
        runTest {
            val fixture = Fixture(this, silenceSetting = 1)
            fixture.script = script(silence = 0, speech = 0)
            fixture.device.onClick(fixture.listener)
            advanceTimeBy(2_900)
            runCurrent()
            fixture.ui shouldBe SttState.Listening
            advanceTimeBy(200)
            runCurrent()
            fixture.events shouldBe listOf(InputEvent.None to SttState.Loaded)
            fixture.models.transcriber.calls shouldBe 0
            fixture.models.releases shouldBe 1
        }
    }

    "the end of a debug WAV finishes the capture" {
        runTest {
            val fixture = Fixture(this)
            fixture.script = script(silence = 5, speech = 20, after = Script.After.END)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.events.single().first.shouldBeInstanceOf<InputEvent.Final>()
        }
    }

    "a tap while transcribing cancels Whisper and sends None" {
        runTest {
            val fixture = Fixture(this)
            fixture.models.transcriber.millis = 5_000
            fixture.device.onClick(fixture.listener)
            advanceTimeBy(END_OF_SPEECH_MILLIS + 100)
            runCurrent()
            fixture.ui shouldBe SttState.Transcribing

            fixture.device.onClick(fixture.listener)
            fixture.events shouldBe listOf(InputEvent.None to SttState.Loaded)
            advanceUntilIdle()
            fixture.models.transcriber.cancelled shouldBe true
            fixture.events shouldHaveSize 1
            fixture.models.releases shouldBe 1
        }
    }

    "stopListening while transcribing cancels too" {
        runTest {
            val fixture = Fixture(this)
            fixture.models.transcriber.millis = 5_000
            fixture.device.onClick(fixture.listener)
            advanceTimeBy(END_OF_SPEECH_MILLIS + 100)
            runCurrent()
            fixture.device.stopListening()
            fixture.device.finishListening() // nothing left to finish
            advanceUntilIdle()
            fixture.events shouldBe listOf(InputEvent.None to SttState.Loaded)
            fixture.models.transcriber.cancelled shouldBe true
        }
    }

    "a slow load keeps Transcribing until the model is there" {
        runTest {
            val fixture = Fixture(this)
            fixture.models.loadMillis = 3_000
            fixture.device.onClick(fixture.listener)
            advanceTimeBy(END_OF_SPEECH_MILLIS + 2_000)
            runCurrent()
            fixture.ui shouldBe SttState.Transcribing
            fixture.events shouldHaveSize 0
            advanceUntilIdle()
            fixture.events.single().first.shouldBeInstanceOf<InputEvent.Final>()
        }
    }

    "a failed load shows ErrorLoading; a tap retries the load and listens" {
        runTest {
            val fixture = Fixture(this)
            val failure = SttLoadException("damaged model")
            fixture.models.loadFailure = failure
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.events shouldBe listOf(InputEvent.Error(failure) to SttState.ErrorLoading(failure))
            fixture.ui shouldBe SttState.ErrorLoading(failure)

            fixture.models.loadFailure = null
            fixture.device.onClick(fixture.listener)
            fixture.ui shouldBe SttState.Listening
            advanceUntilIdle()
            fixture.models.retries shouldBe 1
            fixture.events shouldHaveSize 2
            fixture.events[1].first.shouldBeInstanceOf<InputEvent.Final>()
            fixture.models.acquires shouldBe 2
            fixture.models.releases shouldBe 2
        }
    }

    "in ErrorLoading, tryLoad without a listener only preloads; with one it retries and listens" {
        runTest {
            val fixture = Fixture(this)
            val failure = SttLoadException("damaged model")
            fixture.models.loadFailure = failure
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.models.loadFailure = null

            // e.g. MainActivity re-created: the failure and the crash guard stay
            fixture.device.tryLoad(null) shouldBe true
            advanceUntilIdle()
            fixture.ui shouldBe SttState.ErrorLoading(failure)
            fixture.models.retries shouldBe 0
            fixture.models.preloads shouldBe 1

            fixture.device.tryLoad(fixture.listener) shouldBe true
            fixture.ui shouldBe SttState.Listening
            advanceUntilIdle()
            fixture.models.retries shouldBe 1
            fixture.events.last().first.shouldBeInstanceOf<InputEvent.Final>()
        }
    }

    "a load error goes away when the model files change or Settings retry" {
        runTest {
            val fixture = Fixture(this)
            val failure = SttLoadException("damaged model")
            fixture.models.loadFailure = failure
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.ui shouldBe SttState.ErrorLoading(failure)

            // another model selected: installed stays true, the generation moves on
            fixture.models.loadGeneration.value++
            advanceUntilIdle()
            fixture.ui shouldBe SttState.Loaded
        }
    }

    "a Whisper failure is an Error and the mic stays ready" {
        runTest {
            val fixture = Fixture(this)
            val failure = IOException("whisper_full failed: -6")
            fixture.models.transcriber.failure = failure
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            val (event, state) = fixture.events.single()
            state shouldBe SttState.Loaded
            val error = (event as InputEvent.Error).throwable
            error.message shouldBe "stt failed"
            error.cause shouldBe failure
        }
    }

    "a mic that can't start or is silenced is an Error" {
        runTest {
            val fixture = Fixture(this)
            fixture.script = script().copy(failStart = true)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            val (event, state) = fixture.events.single()
            state shouldBe SttState.Loaded
            (event as InputEvent.Error).throwable.message shouldBe "mic failed"
            fixture.sources.single().stopped shouldBe true
            fixture.models.releases shouldBe 1

            // silenced for good: the capture waits about 2 s within the turn, then says so
            // without "error" wording when no UI takes it over
            fixture.script = script().copy(silenced = true)
            fixture.device.onClick(fixture.listener)
            advanceTimeBy(17 * FRAME_MILLIS)
            runCurrent()
            fixture.ui shouldBe SttState.Listening
            fixture.events shouldHaveSize 1
            advanceTimeBy(GarWhisperInputDevice.SILENCED_WAIT_MILLIS - 200L)
            runCurrent()
            fixture.ui shouldBe SttState.Listening
            advanceTimeBy(400)
            runCurrent()
            fixture.events shouldHaveSize 2
            (fixture.events[1].first as InputEvent.Error).throwable.message shouldBe "mic busy"
            fixture.handOffs shouldBe 1
            fixture.sources.last().stopped shouldBe true
        }
    }

    "a mic silenced for good is handed off to the UI without an error" {
        runTest {
            val fixture = Fixture(this)
            fixture.handsOff = true
            fixture.script = script().copy(silenced = true)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.events.single() shouldBe (InputEvent.None to SttState.Loaded)
            fixture.handOffs shouldBe 1
            fixture.errorLogs shouldHaveSize 0
            fixture.models.releases shouldBe 1
        }
    }

    "a mic silenced for a moment comes back within the turn and the command is heard" {
        runTest {
            val fixture = Fixture(this)
            // 1.6 s silenced (the competing capture ended), then 20 frames of speech
            fixture.script = Script(List(60) { false } + List(20) { true })
                .copy(silencedFrames = 50)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            val (event, _) = fixture.events.single()
            (event as InputEvent.Final).utterances.first().first shouldBe "set a timer for five minutes"
            fixture.handOffs shouldBe 0
            fixture.sources.single().stopped shouldBe true
            // the VAD and the no-speech timeout started over when the mic came back
            (fixture.detector.resets >= 2) shouldBe true
        }
    }

    "made-up text on near silence is dropped, the same text on speech is not" {
        runTest {
            val fixture = Fixture(this)
            fixture.script = script(speech = 10) // 320 ms
            fixture.models.transcriber.transcript = transcript("Thank you.", noSpeechProb = 0.9f)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.events.single() shouldBe (InputEvent.None to SttState.Loaded)
            fixture.infoLogs.single() shouldContain "dropped"

            fixture.models.transcriber.transcript = transcript("Thank you.", noSpeechProb = 0.1f)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.events[1].first shouldBe
                InputEvent.Final(listOf("thank you" to 0.9f), "Thank you.", spoken = true)
        }
    }

    "empty text and [BLANK_AUDIO] give None" {
        runTest {
            val fixture = Fixture(this)
            fixture.models.transcriber.transcript = transcript("[BLANK_AUDIO]")
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.models.transcriber.transcript = transcript("")
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.events shouldBe List(2) { InputEvent.None to SttState.Loaded }
        }
    }

    "the contact names go into the prompt, and a command with a name passes" {
        runTest {
            val fixture = Fixture(this)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.models.transcriber.lastNames shouldBe emptyList()
            fixture.models.transcriber.prepared shouldBe emptyList()
            fixture.infoLogs.single() shouldContain "prompt 0 tokens"

            fixture.models.promptNames.value = listOf("Aaron", "Mom", "Sarah Lee")
            // unsure of the words, but they are more than names
            fixture.models.transcriber.transcript =
                transcript("Send a text to Aaron.", confidence = 0.3f)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.models.transcriber.lastNames shouldBe listOf("Aaron", "Mom", "Sarah Lee")
            // fitted while the user talked, not after
            fixture.models.transcriber.prepared shouldBe listOf(listOf("Aaron", "Mom", "Sarah Lee"))
            fixture.infoLogs.last() shouldContain "prompt 6 tokens"
            fixture.events.last() shouldBe (InputEvent.Final(
                listOf("send a text to aaron" to 0.3f),
                "Send a text to Aaron.",
                spoken = true,
            ) to SttState.Loaded)
        }
    }

    "names that arrive during capture are fitted before the clip is transcribed" {
        runTest {
            val fixture = Fixture(this)
            fixture.device.onClick(fixture.listener)
            runCurrent()
            fixture.models.promptNames.value = listOf("Aaron", "Erin")
            advanceUntilIdle()
            fixture.models.transcriber.prepared shouldBe listOf(listOf("Aaron", "Erin"))
            fixture.models.transcriber.lastNames shouldBe listOf("Aaron", "Erin")
        }
    }

    "prompt names Whisper copied into unclear audio give None, a name said clearly passes" {
        runTest {
            val fixture = Fixture(this)
            fixture.models.promptNames.value = listOf("Aaron", "Mom", "Sarah Lee")
            // the whole prompt back, however sure
            fixture.models.transcriber.transcript =
                transcript("Aaron, Mom, Sarah Lee.", confidence = 0.95f)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            // one name Whisper wasn't sure of
            fixture.models.transcriber.transcript = transcript("Sarah Lee.", confidence = 0.35f)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            // the answer to "Who do you want to text?"
            fixture.models.transcriber.transcript = transcript("Aaron.", confidence = 0.9f)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()

            fixture.events shouldBe listOf(
                InputEvent.None to SttState.Loaded,
                InputEvent.None to SttState.Loaded,
                InputEvent.Final(listOf("aaron" to 0.9f), "Aaron.", spoken = true) to SttState.Loaded,
            )
            fixture.infoLogs[0] shouldContain "dropped"
            fixture.infoLogs[1] shouldContain "dropped"
        }
    }

    "the listener can start the next capture right away" {
        runTest {
            val fixture = Fixture(this)
            var restarted: Boolean? = null
            fixture.device.onClick { event ->
                fixture.listener(event)
                restarted = fixture.device.tryLoad(fixture.listener)
            }
            advanceTimeBy(END_OF_SPEECH_MILLIS + 400)
            runCurrent()
            restarted shouldBe true
            fixture.ui shouldBe SttState.Listening
            advanceUntilIdle()
            fixture.events shouldHaveSize 2
            fixture.sources shouldHaveSize 2
            fixture.sources.all { it.stopped } shouldBe true
            fixture.detectorsBuilt shouldBe 1
            fixture.detector.resets shouldBe 2
            fixture.models.acquires shouldBe 2
            fixture.models.releases shouldBe 2
        }
    }

    "destroy while busy sends None and frees the VAD" {
        runTest {
            val fixture = Fixture(this)
            fixture.device.onClick(fixture.listener)
            advanceTimeBy(500)
            runCurrent()
            fixture.device.destroy()
            fixture.events shouldBe listOf(InputEvent.None to SttState.Loaded)
            fixture.detector.closed shouldBe true
            fixture.sources.single().stopped shouldBe true
            fixture.models.releases shouldBe 1
        }
    }

    "keep recordings saves the clip and its text, the last 50 only" {
        runTest {
            val fixture = Fixture(this, keepRecordings = true)
            repeat(50) {
                File(fixture.recordingsDir, "20000101-000000-%03d.wav".format(it)).writeText("")
                File(fixture.recordingsDir, "20000101-000000-%03d.txt".format(it)).writeText("")
            }
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()

            val wavs = fixture.recordingsDir.listFiles { f -> f.name.endsWith(".wav") }!!
            wavs shouldHaveSize 50
            File(fixture.recordingsDir, "20000101-000000-000.wav").exists() shouldBe false
            File(fixture.recordingsDir, "20000101-000000-000.txt").exists() shouldBe false
            val newest = wavs.maxBy { it.name }
            newest.inputStream().use { WavReader.read(it) }.size shouldBe
                fixture.models.transcriber.lastPcm.size
            File(newest.path.removeSuffix(".wav") + ".txt").readText() shouldContain
                "raw: Set a timer for 5 minutes."
            fixture.recordingsDir.deleteRecursively()
        }
    }

    "nothing is kept unless asked" {
        runTest {
            val fixture = Fixture(this)
            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.recordingsDir.listFiles()!!.size shouldBe 0
            fixture.recordingsDir.deleteRecursively()
        }
    }

    "a window that took 2 s to start listens only until the end the arc shows" {
        runTest {
            // the first reading sets endsAt; the capture starts 2 s later
            var calls = 0
            val fixture = Fixture(this, silenceSetting = 1, nanoTime = {
                if (calls++ == 0) 0L else 2_000_000_000L
            })
            fixture.script = script(silence = 0, speech = 0)
            fixture.device.tryListenBriefly(fixture.listener, 7_000, 6_000) shouldBe true
            runCurrent()
            fixture.device.state.value.shouldBeInstanceOf<WhisperState.Listening>().endsAt shouldBe 7_000L
            advanceTimeBy(4_900)
            runCurrent()
            fixture.events shouldHaveSize 0
            advanceTimeBy(200)
            runCurrent()
            fixture.events shouldBe listOf(InputEvent.None to SttState.Loaded)
        }
    }

    "a continued-listening window waits 7 s for speech, shows as brief, and only when idle" {
        runTest {
            val fixture = Fixture(this, silenceSetting = 1) // 3 s for a capture the user started
            fixture.script = script(silence = 0, speech = 0)
            fixture.device.tryListenBriefly(fixture.listener, 7_000, 6_000) shouldBe true
            runCurrent()
            val listening = fixture.device.state.value.shouldBeInstanceOf<WhisperState.Listening>()
            listening.brief shouldBe true
            (listening.endsAt > 0) shouldBe true
            fixture.ui shouldBe SttState.Listening
            // a second window or a tap's capture doesn't start over it
            fixture.device.tryListenBriefly(fixture.listener, 7_000, 6_000) shouldBe false

            advanceTimeBy(6_900)
            runCurrent()
            fixture.events shouldHaveSize 0
            advanceTimeBy(200)
            runCurrent()
            fixture.events shouldBe listOf(InputEvent.None to SttState.Loaded)
            fixture.models.transcriber.calls shouldBe 0

            // a capture the user started is not brief
            fixture.device.onClick(fixture.listener)
            runCurrent()
            fixture.device.state.value.shouldBeInstanceOf<WhisperState.Listening>().brief shouldBe
                false
            fixture.device.stopListening()

            val notReady = Fixture(this, installed = false)
            notReady.device.tryListenBriefly(notReady.listener, 7_000, 6_000) shouldBe false
        }
    }

    "a window's short sound is silence, a capture the user started transcribes it" {
        runTest {
            // 8 frames of speech: 256 ms, over the endpointer's 250 ms, under the window's 300
            val fixture = Fixture(this)
            fixture.script = script(silence = 10, speech = 8)
            fixture.device.tryListenBriefly(fixture.listener, 7_000, 6_000) shouldBe true
            advanceUntilIdle()
            fixture.events shouldBe listOf(InputEvent.None to SttState.Loaded)
            fixture.models.transcriber.calls shouldBe 0

            fixture.device.onClick(fixture.listener)
            advanceUntilIdle()
            fixture.models.transcriber.calls shouldBe 1
            fixture.events.last().first.shouldBeInstanceOf<InputEvent.Final>().spoken shouldBe true
        }
    }

    "a window's speech ends after 6 s and is transcribed" {
        runTest {
            val fixture = Fixture(this)
            fixture.script = script(silence = 10, speech = 0, after = Script.After.SPEECH)
            fixture.device.tryListenBriefly(fixture.listener, 7_000, 6_000) shouldBe true
            advanceTimeBy(10 * FRAME_MILLIS + 5_900)
            runCurrent()
            fixture.ui shouldBe SttState.Listening
            advanceTimeBy(300)
            runCurrent()
            fixture.ui shouldBe SttState.Transcribing
            advanceUntilIdle()
            fixture.events.single().first.shouldBeInstanceOf<InputEvent.Final>()
            // 6 s of speech and the pre-roll, not the 28 s cap
            val clipMillis = fixture.models.transcriber.lastPcm.size / 16
            (clipMillis in 6_000..6_600) shouldBe true
        }
    }

    "closing a window sends None; it leaves other captures and a window's transcription alone" {
        runTest {
            val fixture = Fixture(this)
            fixture.script = script(silence = 0, speech = 0)
            fixture.device.tryListenBriefly(fixture.listener, 7_000, 6_000) shouldBe true
            runCurrent()
            fixture.device.stopBriefListening()
            fixture.events shouldBe listOf(InputEvent.None to SttState.Loaded)
            advanceUntilIdle()

            fixture.device.onClick(fixture.listener)
            runCurrent()
            fixture.device.stopBriefListening()
            fixture.ui shouldBe SttState.Listening
            fixture.device.stopListening()
            advanceUntilIdle()

            fixture.script = script()
            fixture.models.transcriber.millis = 5_000
            fixture.device.tryListenBriefly(fixture.listener, 7_000, 6_000) shouldBe true
            advanceTimeBy(END_OF_SPEECH_MILLIS + 100)
            runCurrent()
            fixture.ui shouldBe SttState.Transcribing
            fixture.device.stopBriefListening()
            advanceUntilIdle()
            fixture.events.last().first.shouldBeInstanceOf<InputEvent.Final>()
        }
    }

    "the level maps -60 to -10 dBFS onto 0 to 1" {
        GarWhisperInputDevice.levelOf(FloatArray(FRAME), FRAME) shouldBe 0f
        (GarWhisperInputDevice.levelOf(FloatArray(FRAME) { 0.001f }, FRAME) < 0.001f) shouldBe true
        GarWhisperInputDevice.levelOf(FloatArray(FRAME) { 0.5f }, FRAME) shouldBe 1f
        val mid = GarWhisperInputDevice.levelOf(FloatArray(FRAME) { 0.01f }, FRAME) // -40 dB
        (mid in 0.39f..0.41f) shouldBe true
        GarWhisperInputDevice.smoothLevel(0f, 1f) shouldBe 0.6f
        GarWhisperInputDevice.smoothLevel(1f, 0f) shouldBe 0.8f
    }
})
