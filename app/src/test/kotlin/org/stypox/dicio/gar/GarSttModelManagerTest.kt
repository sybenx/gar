@file:OptIn(ExperimentalCoroutinesApi::class)

package org.stypox.dicio.gar

import cc.aaro.gar.stt.Transcriber
import cc.aaro.gar.stt.Transcript
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.stypox.dicio.gar.model.GarDownloadException
import org.stypox.dicio.gar.model.GarEngineHolder
import org.stypox.dicio.gar.model.GarModelDownloader
import org.stypox.dicio.gar.model.ModelCrashedException
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.gar.settings.GarSettingsSerializer
import org.stypox.dicio.gar.settings.GarSttModel
import org.stypox.dicio.gar.stt.GarSttModelManager
import org.stypox.dicio.gar.stt.GarSttOffer
import org.stypox.dicio.gar.stt.GarSttOfferStore
import org.stypox.dicio.gar.stt.GarSttState
import org.stypox.dicio.gar.stt.LoadedStt
import org.stypox.dicio.gar.stt.SttDownload
import org.stypox.dicio.gar.stt.SttLoadException
import org.stypox.dicio.gar.stt.SttModelCatalog
import org.stypox.dicio.gar.stt.SttSelection
import org.stypox.dicio.gar.stt.SttTransfer
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.stt_popup.garPopupUtterances
import org.stypox.dicio.settings.datastore.InputDevice
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext

private class FakeTranscriber(val file: File) : Transcriber {
    override suspend fun transcribe(
        pcm: FloatArray,
        samples: Int,
        audioCtx: Int,
        maxTokens: Int,
        names: List<String>,
    ) = Transcript(file.name, 0f, 1f, 1, audioCtx, 0L, 0L)
}

/** A manager on a temporary folder, with downloads and loads that only touch that folder. */
private class SttFixture(scope: CoroutineScope) {
    val dir: File = Files.createTempDirectory("gar-stt").toFile().apply { deleteOnExit() }
    val modelsDir = File(dir, "gar-stt")
    val marker = File(dir, "gar-stt-loading")
    val settings = GarSettingsModule.newDataStoreForPreviews()

    val fetched = mutableListOf<String>()
    /** Files whose download fails, by name. */
    val failing = mutableSetOf<String>()
    /** Set to make the next download wait until it completes. */
    var gate: CompletableDeferred<Unit>? = null
    val loads = mutableListOf<String>()
    val closed = mutableListOf<String>()
    var failLoading = false
    val promptNames = MutableStateFlow(listOf("Aaron"))
    var nameRefreshes = 0

    val manager = GarSttModelManager(
        scope = scope,
        settings = settings,
        dir = modelsDir,
        loadMarker = marker,
        fetch = { download: SttDownload, target: File, progress: (Long, Long) -> Unit ->
            fetched.add(download.fileName)
            progress(0, download.sizeBytes)
            gate?.await()
            if (download.fileName in failing) {
                throw GarDownloadException("Checksum mismatch", damaged = true)
            }
            target.parentFile?.mkdirs()
            target.writeText(download.fileName)
            progress(download.sizeBytes, download.sizeBytes)
        },
        loadEngine = { file ->
            loads.add(file.name)
            if (failLoading) throw IOException("bad model")
            LoadedStt(FakeTranscriber(file)) { closed.add(file.name) }
        },
        io = EmptyCoroutineContext,
        promptNames = promptNames,
        refreshPromptNames = { nameRefreshes++ },
    )

    fun file(download: SttDownload) = File(modelsDir, download.fileName)

    /** What the loaded model is: the fake transcriber answers with its file name. */
    suspend fun transcribe(): String =
        manager.use { it.transcribe(FloatArray(16), 16, 128) }.text
}

/**
 * The manager's scope: cancelled with the test like backgroundScope, but on a dispatcher of its
 * own, because advanceUntilIdle skips work in backgroundScope.
 */
private fun TestScope.managerScope() =
    CoroutineScope(StandardTestDispatcher(testScheduler) + backgroundScope.coroutineContext[Job]!!)

private fun TestScope.installed(fixture: SttFixture) {
    fixture.manager.download()
    advanceUntilIdle()
    fixture.manager.installed.value shouldBe true
}

private val BASE = SttModelCatalog.BASE_EN
private val TINY = SttModelCatalog.TINY_EN
private val VAD = SttModelCatalog.SILERO_VAD

class GarSttModelManagerTest : StringSpec({
    "the catalog pins FUTO's files and the VAD by size and sha256" {
        SttModelCatalog.models.map { it.setting } shouldContainExactly
            listOf(GarSttModel.GAR_STT_MODEL_BASE_EN, GarSttModel.GAR_STT_MODEL_TINY_EN)
        BASE.sizeBytes shouldBe 81_781_811L
        TINY.sizeBytes shouldBe 43_550_795L
        VAD.sizeBytes shouldBe 885_098L
        for (file in SttModelCatalog.models + VAD) {
            file.sha256.matches(Regex("[0-9a-f]{64}")) shouldBe true
            file.url.startsWith("https://") shouldBe true
            file.url.endsWith(".bin") shouldBe true
        }
        for (model in SttModelCatalog.models) {
            model.url.startsWith("https://dl.voiceinput.futo.org/VoiceInput/") shouldBe true
        }
        (SttModelCatalog.models + VAD).map { it.fileName }.toSet().size shouldBe 3
        VAD.setting shouldBe null
        SttModelCatalog.forSetting(GarSttModel.UNRECOGNIZED) shouldBe BASE
        SttModelCatalog.downloadSize(GarSttModel.GAR_STT_MODEL_TINY_EN) shouldBe
            TINY.sizeBytes + VAD.sizeBytes
        // base.en is the default (proto3's 0)
        GarSettingsSerializer.defaultValue.sttModel shouldBe GarSttModel.GAR_STT_MODEL_BASE_EN
        GarSettingsSerializer.defaultValue.sttKeepRecordings shouldBe false
    }

    "a download fetches the selected model and the VAD, then counts as installed" {
        runTest {
            val fixture = SttFixture(managerScope())
            fixture.manager.installed.value shouldBe false
            fixture.manager.state.first() shouldBe GarSttState.NotDownloaded

            fixture.gate = CompletableDeferred()
            fixture.manager.download()
            runCurrent()
            fixture.manager.transfer.value shouldBe
                SttTransfer.Running(0, BASE.sizeBytes + VAD.sizeBytes)
            fixture.manager.state.first().shouldBeInstanceOf<GarSttState.Downloading>()
                .model shouldBe GarSttModel.GAR_STT_MODEL_BASE_EN
            // a second tap doesn't start a second download
            fixture.manager.download()
            fixture.gate!!.complete(Unit)
            advanceUntilIdle()

            fixture.fetched shouldContainExactly listOf(BASE.fileName, VAD.fileName)
            fixture.manager.installed.value shouldBe true
            fixture.manager.transfer.value shouldBe SttTransfer.Idle
            fixture.manager.vadFile shouldBe fixture.file(VAD)
            fixture.manager.state.first() shouldBe GarSttState.Ready
            fixture.transcribe() shouldBe BASE.fileName

            // nothing is missing: nothing is fetched again
            fixture.manager.download()
            advanceUntilIdle()
            fixture.fetched.size shouldBe 2
        }
    }

    "a switch downloads the new model, selects it, unloads and deletes the old one" {
        runTest {
            val fixture = SttFixture(managerScope())
            installed(fixture)
            fixture.transcribe() shouldBe BASE.fileName

            fixture.gate = CompletableDeferred()
            fixture.manager.switchTo(GarSttModel.GAR_STT_MODEL_TINY_EN)
            runCurrent()
            // the old model keeps working while the new one downloads
            fixture.manager.installed.value shouldBe true
            fixture.settings.data.first().sttModel shouldBe GarSttModel.GAR_STT_MODEL_BASE_EN
            fixture.manager.transferModel.value shouldBe GarSttModel.GAR_STT_MODEL_TINY_EN
            fixture.manager.transfer.value shouldBe SttTransfer.Running(0, TINY.sizeBytes)
            fixture.gate!!.complete(Unit)
            advanceUntilIdle()

            fixture.fetched shouldContainExactly
                listOf(BASE.fileName, VAD.fileName, TINY.fileName)
            fixture.settings.data.first().sttModel shouldBe GarSttModel.GAR_STT_MODEL_TINY_EN
            fixture.file(BASE).exists() shouldBe false
            fixture.file(TINY).exists() shouldBe true
            fixture.file(VAD).exists() shouldBe true
            fixture.closed shouldContainExactly listOf(BASE.fileName)
            fixture.manager.installed.value shouldBe true
            fixture.manager.transferModel.value shouldBe null
            fixture.transcribe() shouldBe TINY.fileName
        }
    }

    "a failed switch keeps the old model and its file" {
        runTest {
            val fixture = SttFixture(managerScope())
            installed(fixture)
            fixture.failing.add(TINY.fileName)

            fixture.manager.switchTo(GarSttModel.GAR_STT_MODEL_TINY_EN)
            advanceUntilIdle()

            fixture.manager.transfer.value.shouldBeInstanceOf<SttTransfer.Failed>()
            fixture.manager.state.first() shouldBe
                GarSttState.DownloadFailed(GarSttModel.GAR_STT_MODEL_TINY_EN, damaged = true)
            fixture.settings.data.first().sttModel shouldBe GarSttModel.GAR_STT_MODEL_BASE_EN
            fixture.file(BASE).exists() shouldBe true
            fixture.file(TINY).exists() shouldBe false
            fixture.manager.installed.value shouldBe true
            fixture.transcribe() shouldBe BASE.fileName

            // trying again works
            fixture.failing.clear()
            fixture.manager.switchTo(GarSttModel.GAR_STT_MODEL_TINY_EN)
            advanceUntilIdle()
            fixture.settings.data.first().sttModel shouldBe GarSttModel.GAR_STT_MODEL_TINY_EN
            fixture.file(BASE).exists() shouldBe false
        }
    }

    "switching to another model replaces a running download" {
        runTest {
            val fixture = SttFixture(managerScope())
            fixture.gate = CompletableDeferred()
            fixture.manager.download()
            runCurrent()
            fixture.manager.switchTo(GarSttModel.GAR_STT_MODEL_TINY_EN)
            runCurrent()
            fixture.gate!!.complete(Unit)
            advanceUntilIdle()

            fixture.fetched shouldContainExactly
                listOf(BASE.fileName, TINY.fileName, VAD.fileName)
            fixture.settings.data.first().sttModel shouldBe GarSttModel.GAR_STT_MODEL_TINY_EN
            fixture.file(BASE).exists() shouldBe false
            fixture.manager.installed.value shouldBe true
        }
    }

    "a cancelled download keeps its partial files and reports nothing" {
        runTest {
            val fixture = SttFixture(managerScope())
            fixture.gate = CompletableDeferred()
            fixture.manager.download()
            runCurrent()
            fixture.manager.cancelDownload()
            advanceUntilIdle()
            fixture.manager.transfer.value shouldBe SttTransfer.Idle
            fixture.manager.transferModel.value shouldBe null
            fixture.manager.installed.value shouldBe false
            fixture.manager.state.first() shouldBe GarSttState.NotDownloaded
        }
    }

    "delete removes every speech file and unloads the model" {
        runTest {
            val fixture = SttFixture(managerScope())
            installed(fixture)
            fixture.transcribe()
            // a partial download of the other model goes too
            GarModelDownloader.partFile(fixture.file(TINY)).writeText("part")
            fixture.manager.partialDownloadSize(GarSttModel.GAR_STT_MODEL_TINY_EN) shouldBe 4L
            fixture.manager.fileSize(GarSttModel.GAR_STT_MODEL_BASE_EN) shouldBe
                (BASE.fileName.length + VAD.fileName.length).toLong()

            fixture.manager.delete()
            advanceUntilIdle()

            fixture.modelsDir.listFiles()!!.toList().shouldBeEmpty()
            fixture.closed shouldContainExactly listOf(BASE.fileName)
            fixture.manager.installed.value shouldBe false
            fixture.manager.fileSize(GarSttModel.GAR_STT_MODEL_BASE_EN) shouldBe null
            shouldThrow<SttLoadException> { fixture.transcribe() }
        }
    }

    "a failed load is not repeated until a retry" {
        runTest {
            val fixture = SttFixture(managerScope())
            installed(fixture)
            fixture.failLoading = true

            shouldThrow<SttLoadException> { fixture.transcribe() }
            fixture.loads shouldContainExactly listOf(BASE.fileName)
            fixture.manager.state.first() shouldBe GarSttState.LoadFailed(crashed = false)
            // the guard's marker is gone: the load ended, it didn't stop the app
            fixture.marker.exists() shouldBe false

            shouldThrow<SttLoadException> { fixture.transcribe() }
            fixture.manager.preload()
            advanceUntilIdle()
            fixture.loads.size shouldBe 1

            fixture.failLoading = false
            fixture.manager.retryLoad()
            fixture.transcribe() shouldBe BASE.fileName
            fixture.loads.size shouldBe 2
        }
    }

    "a model whose loads stopped the app is not loaded until a retry" {
        runTest {
            val fixture = SttFixture(managerScope())
            installed(fixture)
            // two loads of this file never finished (whisper.cpp aborted the process)
            val id = SttSelection(fixture.file(BASE)).id
            fixture.marker.writeText("2\n$id")

            val e = shouldThrow<SttLoadException> { fixture.transcribe() }
            // kotlinx.coroutines' stack trace recovery may wrap the exception in a copy of itself
            generateSequence<Throwable>(e) { it.cause }
                .any { it is ModelCrashedException } shouldBe true
            fixture.loads.shouldBeEmpty()
            fixture.manager.state.first() shouldBe GarSttState.LoadFailed(crashed = true)

            fixture.manager.retryLoad()
            fixture.transcribe() shouldBe BASE.fileName
            fixture.marker.exists() shouldBe false
        }
    }

    "each capture start refreshes the contact names, which the manager passes on" {
        runTest {
            val fixture = SttFixture(managerScope())
            installed(fixture)
            fixture.manager.promptNames.value shouldBe listOf("Aaron")
            fixture.nameRefreshes shouldBe 0
            fixture.manager.acquire()
            fixture.nameRefreshes shouldBe 1
            fixture.manager.release()
            fixture.manager.preload()
            fixture.nameRefreshes shouldBe 1
            fixture.promptNames.value = listOf("Aaron", "Mom")
            fixture.manager.promptNames.value shouldBe listOf("Aaron", "Mom")
        }
    }

    "errors of the block pass through, they are not load errors" {
        runTest {
            val fixture = SttFixture(managerScope())
            installed(fixture)
            val e = shouldThrow<IOException> {
                fixture.manager.use { throw IOException("whisper failed") }
            }
            (e is SttLoadException) shouldBe false
            e.message shouldBe "whisper failed"
            // the model stays loaded and usable
            fixture.transcribe() shouldBe BASE.fileName
            fixture.loads.size shouldBe 1
        }
    }

    "the model is unloaded some time after the last release" {
        runTest {
            val fixture = SttFixture(managerScope())
            installed(fixture)
            fixture.manager.acquire()
            advanceUntilIdle()
            fixture.loads shouldContainExactly listOf(BASE.fileName)
            fixture.manager.state.first() shouldBe GarSttState.Loaded

            fixture.manager.release()
            advanceUntilIdle()
            fixture.closed shouldContainExactly listOf(BASE.fileName)
            fixture.manager.state.first() shouldBe GarSttState.Ready
        }
    }

    "the settings state puts a download before the files and the engine" {
        val file = File("/nowhere/ggml-base.en-acft-q8_0.bin")
        val selection = SttSelection(file, 1L, 1L)
        val other = SttSelection(file, 2L, 2L)
        val tiny = GarSttModel.GAR_STT_MODEL_TINY_EN
        val notLoaded = GarEngineHolder.State.NotLoaded

        GarSttModelManager.sttState(selection, SttTransfer.Running(5, 10), tiny, notLoaded) shouldBe
            GarSttState.Downloading(tiny, 0.5f)
        GarSttModelManager.sttState(null, SttTransfer.Running(5, 0), tiny, notLoaded) shouldBe
            GarSttState.Downloading(tiny, null)
        GarSttModelManager.sttState(
            selection, SttTransfer.Failed(IOException("offline")), tiny, notLoaded,
        ) shouldBe GarSttState.DownloadFailed(tiny, damaged = false)
        GarSttModelManager.sttState(null, SttTransfer.Idle, null, notLoaded) shouldBe
            GarSttState.NotDownloaded
        GarSttModelManager.sttState(selection, SttTransfer.Idle, null, notLoaded) shouldBe
            GarSttState.Ready
        GarSttModelManager.sttState(
            selection, SttTransfer.Idle, null, GarEngineHolder.State.Loading(selection),
        ) shouldBe GarSttState.Loading
        GarSttModelManager.sttState(
            selection, SttTransfer.Idle, null, GarEngineHolder.State.Loaded(selection),
        ) shouldBe GarSttState.Loaded
        // the engine of a replaced file doesn't count
        GarSttModelManager.sttState(
            selection, SttTransfer.Idle, null, GarEngineHolder.State.Loaded(other),
        ) shouldBe GarSttState.Ready
        GarSttModelManager.sttState(
            selection, SttTransfer.Idle, null,
            GarEngineHolder.State.Failed(selection, SttLoadException("bad")),
        ) shouldBe GarSttState.LoadFailed(crashed = false)
    }

    "Whisper is loaded ahead of time when chosen, or unset in English" {
        GarSttModelManager.whisperWanted(InputDevice.INPUT_DEVICE_WHISPER, "de") shouldBe true
        GarSttModelManager.whisperWanted(InputDevice.INPUT_DEVICE_UNSET, "en") shouldBe true
        GarSttModelManager.whisperWanted(InputDevice.INPUT_DEVICE_UNSET, "de") shouldBe false
        GarSttModelManager.whisperWanted(InputDevice.INPUT_DEVICE_VOSK, "en") shouldBe false
        GarSttModelManager.whisperWanted(InputDevice.INPUT_DEVICE_EXTERNAL_POPUP, "en") shouldBe
            false
        GarSttModelManager.whisperWanted(InputDevice.INPUT_DEVICE_NOTHING, "en") shouldBe false
    }

    "the speech model offer shows once, until dismissed or downloaded" {
        GarSttOffer.shows(hidden = false, installed = false, wanted = true) shouldBe true
        GarSttOffer.shows(hidden = true, installed = false, wanted = true) shouldBe false
        GarSttOffer.shows(hidden = false, installed = true, wanted = true) shouldBe false
        // Vosk chosen, or not English
        GarSttOffer.shows(hidden = false, installed = false, wanted = false) shouldBe false
        // nothing until the settings are read, so that the card doesn't flash
        GarSttOffer.shows(hidden = null, installed = false, wanted = true) shouldBe false
        GarSttOffer.shows(hidden = false, installed = false, wanted = null) shouldBe false

        GarSttOffer.hidesForGood(hidden = false, installed = true) shouldBe true
        GarSttOffer.hidesForGood(hidden = true, installed = true) shouldBe false
        GarSttOffer.hidesForGood(hidden = null, installed = true) shouldBe false
        GarSttOffer.hidesForGood(hidden = false, installed = false) shouldBe false

        GarSettingsSerializer.defaultValue.sttOfferHidden shouldBe false
        val dataStore = GarSettingsModule.newDataStoreForPreviews()
        val store = GarSttOfferStore(dataStore)
        store.hidden.first() shouldBe false
        store.hide()
        store.hide() // again: nothing changes
        store.hidden.first() shouldBe true
        dataStore.data.first().llmEnabled shouldBe true
    }

    "the STT popup gives other apps Whisper's written text" {
        garPopupUtterances(
            InputEvent.Final(listOf(Pair("set a timer for five minutes", 0.9f)),
                written = "Set a timer for 5 minutes.")
        ) shouldBe listOf(Pair("Set a timer for 5 minutes.", 0.9f))
        // Vosk: no written text
        val vosk = listOf(Pair("hello", 0.8f), Pair("yellow", 0.1f))
        garPopupUtterances(InputEvent.Final(vosk)) shouldBe vosk
        garPopupUtterances(InputEvent.Final(vosk, written = " ")) shouldBe vosk
    }
})
