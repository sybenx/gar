package org.stypox.dicio.skills.song

import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.stypox.dicio.R
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

private const val NOW = 1_761_500_000_000L
private val AUDD = SongProviderConfig.Audd("token")
private val TRACK = SongTrack("Everybody Wants To Rule The World", "Tears For Fears")
private const val OWN = "cc.aaro.gar"

private fun session(
    packageName: String = "com.spotify.music",
    playing: Boolean = true,
    title: String? = "Song",
    artist: String? = "Artist",
    album: String? = null,
) = MediaSessionInfo(packageName, playing, title, artist, album)

private val APP_NAMES = mapOf(
    "com.spotify.music" to "Spotify",
    "org.videolan.vlc" to "VLC",
    "app.revanced.android.youtube" to "YouTube",
)

private fun nowPlaying(vararg sessions: MediaSessionInfo) =
    SongLogic.nowPlaying(sessions.toList(), OWN) { APP_NAMES[it] }

/** A microphone that hears music (or silence), and reports its progress. */
class FakeRecorder(private val result: RecordingResult = music()) : SongRecorder {
    val progress = mutableListOf<Float>()
    var calls = 0

    override suspend fun record(millis: Long, onProgress: (Float) -> Unit): RecordingResult {
        calls++
        onProgress(0.5f)
        onProgress(1f)
        progress.addAll(listOf(0.5f, 1f))
        return result
    }

    companion object {
        fun music() = RecordingResult.Recorded(ShortArray(1600) { ((it * 37) % 2000 - 1000).toShort() }, 16_000)
        fun silence() = RecordingResult.Recorded(ShortArray(1600), 16_000)
    }
}

private class Setup(
    recorder: SongRecorder = FakeRecorder(),
    val recognizer: SongRecognizer = FakeRecognizer(RecognitionResult.Match(TRACK)),
    online: Boolean = true,
    maxClips: Int = 10,
    canNotify: Boolean = true,
    val queue: SongClipQueue = SongClipQueue(tempQueueDir(), maxClips = maxClips),
) {
    var scheduled = 0
    val run = SongRecognitionRun(
        provider = AUDD,
        recorder = recorder,
        recognizer = recognizer,
        queue = queue,
        isOnline = { online },
        onQueued = { scheduled++ },
        nowMillis = { NOW },
        canNotify = { canNotify },
    )
    val fake: FakeRecognizer get() = recognizer as FakeRecognizer
}

class SongPlanTest : StringSpec({
    "tier 1: a playing session with a title answers" {
        val playing = nowPlaying(session(title = "Warriors", artist = "Imagine Dragons", album = "Smoke + Mirrors"))
        val track = PlayingTrack("Warriors", "Imagine Dragons", "Smoke + Mirrors", "Spotify")
        playing shouldBe NowPlaying.Track(track)
        // even with a service and Audile: the phone's own player knows best, and offline
        SongLogic.plan(playing, AUDD, AudileIntents.PACKAGE) shouldBe SongPlan.Answer(track)
    }

    "tier 1 picks the first playing session, never a paused one or GAR's own" {
        nowPlaying(
            session(packageName = OWN, title = "GAR's voice"),
            session(packageName = "org.videolan.vlc", playing = false, title = "Paused"),
            session(packageName = "app.revanced.android.youtube", title = "A video", artist = "A channel"),
            session(title = "Later"),
        ) shouldBe NowPlaying.Track(PlayingTrack("A video", "A channel", null, "YouTube"))
        // no artist (an untagged file): the title alone
        nowPlaying(session(packageName = "org.videolan.vlc", title = " track01.mp3 ", artist = " ")) shouldBe
            NowPlaying.Track(PlayingTrack("track01.mp3", null, null, "VLC"))
        // a session without a title doesn't hide one that has it
        nowPlaying(session(title = null), session(packageName = "org.videolan.vlc", title = "Named")) shouldBe
            NowPlaying.Track(PlayingTrack("Named", "Artist", null, "VLC"))
    }

    "nothing playing, or playing without a title, goes on to the other tiers" {
        nowPlaying() shouldBe NowPlaying.NothingPlaying
        nowPlaying(session(playing = false)) shouldBe NowPlaying.NothingPlaying
        nowPlaying(session(title = null), session(title = "  ")) shouldBe NowPlaying.Untitled

        for (np in listOf(NowPlaying.NothingPlaying, NowPlaying.Untitled, NowPlaying.NoAccess)) {
            SongLogic.plan(np, AUDD, AudileIntents.PACKAGE) shouldBe SongPlan.Record(AUDD)
            SongLogic.plan(np, null, AudileIntents.DEBUG_PACKAGE) shouldBe
                SongPlan.OpenAudile(AudileIntents.DEBUG_PACKAGE)
            SongLogic.plan(np, null, null) shouldBe SongPlan.NothingAvailable(np)
        }
    }

    "tier 2 online: listens, then recognizes" {
        val recorder = FakeRecorder()
        val setup = Setup(recorder = recorder)
        setup.run.state.value shouldBe SongCardState.Listening(0f)
        setup.run.run() shouldBe SongOutcome.Found(TRACK)
        setup.run.state.value shouldBe SongCardState.Done(SongOutcome.Found(TRACK))
        recorder.calls shouldBe 1
        // a WAV of what was recorded went to the service
        setup.fake.sent.single().first shouldBe AUDD
        val wav = setup.fake.wavs.single()
        wav.size shouldBe SongWav.HEADER_BYTES + 1600 * 2
        String(wav, 0, 4, Charsets.US_ASCII) shouldBe "RIFF"
        setup.queue.list(NOW).shouldBeEmpty()
        setup.scheduled shouldBe 0
    }

    "the card follows the recording's progress, then waits for the service" {
        lateinit var run: SongRecognitionRun
        val seen = mutableListOf<SongCardState>()
        val recorder = SongRecorder { _, onProgress ->
            onProgress(0.25f)
            seen.add(run.state.value)
            onProgress(1f)
            seen.add(run.state.value)
            FakeRecorder.music()
        }
        val recognizer = SongRecognizer { _, _ ->
            seen.add(run.state.value)
            RecognitionResult.NoMatch
        }
        run = SongRecognitionRun(AUDD, recorder, recognizer, SongClipQueue(tempQueueDir()), { true }, {}, { NOW })
        run.run() shouldBe SongOutcome.NotFound
        seen shouldBe listOf(
            SongCardState.Listening(0.25f), SongCardState.Listening(1f), SongCardState.Recognizing,
        )
    }

    "tier 2 answers: no match and errors" {
        Setup(recognizer = FakeRecognizer(RecognitionResult.NoMatch)).run.run() shouldBe
            SongOutcome.NotFound
        Setup(recognizer = FakeRecognizer(RecognitionResult.BadToken)).run.run() shouldBe
            SongOutcome.Failed(RecognitionResult.BadToken)
        Setup(recognizer = FakeRecognizer(RecognitionResult.LimitReached)).run.run() shouldBe
            SongOutcome.Failed(RecognitionResult.LimitReached)
        Setup(recognizer = FakeRecognizer(RecognitionResult.HttpError(500))).run.run() shouldBe
            SongOutcome.Failed(RecognitionResult.HttpError(500))
    }

    "tier 2 offline: the clip is queued and the worker scheduled, nothing is sent" {
        val setup = Setup(online = false)
        setup.run.run() shouldBe SongOutcome.Queued(offline = true)
        setup.fake.sent.shouldBeEmpty()
        setup.queue.list(NOW).size shouldBe 1
        setup.scheduled shouldBe 1
        String(setup.queue.read(setup.queue.list(NOW).single())!!, 0, 4, Charsets.US_ASCII) shouldBe "RIFF"
    }

    "tier 2: a connection failure while online queues the clip too, and says the service" {
        val setup = Setup(recognizer = FakeRecognizer(RecognitionResult.NoConnection))
        setup.run.run() shouldBe SongOutcome.Queued(offline = false)
        setup.fake.sent.size shouldBe 1
        setup.queue.list(NOW).single().provider shouldBe SongProviderKind.AUDD
        setup.scheduled shouldBe 1
        SongMessages.outcome(SongOutcome.Queued(offline = false), SongProviderKind.AUDD) shouldBe
            SongText(R.string.skill_song_unreachable_queued, listOf("AudD"))
    }

    "tier 2 offline with a full queue says so" {
        val setup = Setup(online = false, maxClips = 1)
        setup.run.run() shouldBe SongOutcome.Queued(offline = true)
        val second = Setup(online = false, maxClips = 1)
        second.queue.add("waiting".toByteArray(), NOW, SongProviderKind.AUDD)
        second.run.run() shouldBe SongOutcome.QueueFull(1, offline = true)
        second.scheduled shouldBe 0
    }

    "tier 2 offline: a recording that can't be saved isn't called a full queue" {
        val notADir = java.io.File(tempQueueDir(), "file").apply { writeText("x") }
        val setup = Setup(online = false, queue = SongClipQueue(notADir))
        setup.run.run() shouldBe SongOutcome.QueueFailed(offline = true)
        setup.scheduled shouldBe 0
    }

    "tier 2 offline without notifications: nothing is kept, so nothing is promised" {
        val setup = Setup(online = false, canNotify = false)
        setup.run.run() shouldBe SongOutcome.NoNotifications(offline = true)
        setup.queue.list(NOW).shouldBeEmpty()
        setup.scheduled shouldBe 0
        val unreachable = Setup(
            recognizer = FakeRecognizer(RecognitionResult.NoConnection), canNotify = false,
        )
        unreachable.run.run() shouldBe SongOutcome.NoNotifications(offline = false)
        unreachable.queue.list(NOW).shouldBeEmpty()
        // online, the answer doesn't need a notification
        Setup(canNotify = false).run.run() shouldBe SongOutcome.Found(TRACK)
    }

    "Stop while listening drops the recording; nothing is sent or queued" {
        val started = CompletableDeferred<Unit>()
        val recorder = SongRecorder { _, onProgress ->
            onProgress(0.3f)
            started.complete(Unit)
            awaitCancellation()
        }
        val setup = Setup(recorder = recorder)
        coroutineScope {
            val job = launch { setup.run.run() }
            started.await()
            job.cancelAndJoin()
        }
        setup.run.state.value shouldBe SongCardState.Done(SongOutcome.Stopped(whileSending = false))
        setup.fake.sent.shouldBeEmpty()
        setup.queue.list(NOW).shouldBeEmpty()
    }

    "Stop while sending says that part of it may have gone" {
        val sending = CompletableDeferred<Unit>()
        val setup = Setup(recognizer = SongRecognizer { _, _ ->
            sending.complete(Unit)
            awaitCancellation()
        })
        coroutineScope {
            val job = launch { setup.run.run() }
            sending.await()
            job.cancelAndJoin()
        }
        setup.run.state.value shouldBe SongCardState.Done(SongOutcome.Stopped(whileSending = true))
        setup.queue.list(NOW).shouldBeEmpty()
    }

    "tier 2 never sends silence or a failed recording" {
        val silent = Setup(recorder = FakeRecorder(FakeRecorder.silence()))
        silent.run.run() shouldBe SongOutcome.Silence
        silent.fake.sent.shouldBeEmpty()

        for (failure in RecordingResult.Failure.entries) {
            val setup = Setup(recorder = FakeRecorder(failure))
            setup.run.run() shouldBe SongOutcome.MicFailure(failure)
            setup.fake.sent.shouldBeEmpty()
            setup.queue.list(NOW).shouldBeEmpty()
        }
    }

    "a recorder that throws ends the card instead of leaving it listening" {
        val setup = Setup(recorder = SongRecorder { _, _ -> throw IllegalStateException("boom") })
        setup.run.run() shouldBe SongOutcome.Error
        setup.run.state.value shouldBe SongCardState.Done(SongOutcome.Error)
    }

    "only answers that reached the service say the first time that it used the network" {
        SongOutcome.Found(TRACK).sentToService shouldBe true
        SongOutcome.NotFound.sentToService shouldBe true
        SongOutcome.Failed(RecognitionResult.BadToken).sentToService shouldBe true
        SongOutcome.Failed(RecognitionResult.NoConnection).sentToService shouldBe false
        SongOutcome.Failed(RecognitionResult.BadHost).sentToService shouldBe false
        SongOutcome.Queued(offline = true).sentToService shouldBe false
        SongOutcome.Stopped(whileSending = false).sentToService shouldBe false
        SongOutcome.Silence.sentToService shouldBe false
        SongOutcome.MicFailure(RecordingResult.Failure.MIC_SILENCED).sentToService shouldBe false
    }
})
