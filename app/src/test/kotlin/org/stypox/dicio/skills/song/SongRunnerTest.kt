package org.stypox.dicio.skills.song

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import org.stypox.dicio.R

private const val NOW = 1_761_500_000_000L
private val AUDD = SongProviderConfig.Audd("token")
private val TRACK = SongTrack("Everybody Wants To Rule The World", "Tears For Fears")

/** What a [SongRunEnd] said and saved. */
private class Speech(noticeSpoken: Boolean) {
    val spoken = mutableListOf<String>()
    var marks = 0
    val end = SongRunEnd(
        noticeSpoken = noticeSpoken,
        markNoticeSpoken = { marks++ },
        noticeText = { "I sent it." },
        answerText = { it.toString() },
        speak = { spoken.add(it) },
    )
}

private fun run(recorder: SongRecorder, recognizer: SongRecognizer = FakeRecognizer(RecognitionResult.Match(TRACK))) =
    SongRecognitionRun(AUDD, recorder, recognizer, SongClipQueue(tempQueueDir()), { true }, {}, { NOW })

class SongRunnerTest : StringSpec({
    "the first answer that reached the service starts with the network notice, which is saved" {
        val speech = Speech(noticeSpoken = false)
        speech.end.finish(SongOutcome.NotFound)
        speech.spoken shouldBe listOf("I sent it. NotFound")
        speech.marks shouldBe 1
    }

    "no notice once it was said, or when nothing reached the service" {
        val said = Speech(noticeSpoken = true)
        said.end.finish(SongOutcome.Found(TRACK))
        said.spoken shouldBe listOf(SongOutcome.Found(TRACK).toString())
        said.marks shouldBe 0

        for (outcome in listOf(
            SongOutcome.Queued(offline = true), SongOutcome.Queued(offline = false),
            SongOutcome.NoNotifications(offline = true), SongOutcome.Silence,
            SongOutcome.Failed(RecognitionResult.NoConnection),
            SongOutcome.MicFailure(RecordingResult.Failure.NO_PERMISSION),
        )) {
            val speech = Speech(noticeSpoken = false)
            speech.end.finish(outcome)
            speech.spoken shouldBe listOf(outcome.toString())
            speech.marks shouldBe 0
        }
    }

    "after Stop nothing is said" {
        for (whileSending in listOf(false, true)) {
            val speech = Speech(noticeSpoken = false)
            speech.end.finish(SongOutcome.Stopped(whileSending))
            speech.spoken.shouldBeEmpty()
            speech.marks shouldBe 0
        }
    }

    "one run at a time, and the answer is said when it ends" {
        val speech = Speech(noticeSpoken = true)
        val recording = CompletableDeferred<Unit>()
        val first = run({ _, _ -> recording.await(); FakeRecorder.music() })
        val second = run(FakeRecorder())
        lateinit var runner: SongRunner
        coroutineScope {
            runner = SongRunner(this)
            runner.start(first, speech.end) shouldBe true
            runner.isActive shouldBe true
            runner.start(second, speech.end) shouldBe false
            speech.spoken.shouldBeEmpty()
            recording.complete(Unit)
        }
        speech.spoken shouldBe listOf(SongOutcome.Found(TRACK).toString())
        runner.isActive shouldBe false
        second.state.value shouldBe SongCardState.Listening(0f) // never ran
    }

    "Stop cancels its own run only, and then a new one can start" {
        val speech = Speech(noticeSpoken = false)
        val started = CompletableDeferred<Unit>()
        val first = run({ _, _ -> started.complete(Unit); kotlinx.coroutines.awaitCancellation() })
        val other = run(FakeRecorder())
        lateinit var runner: SongRunner
        coroutineScope {
            runner = SongRunner(this)
            runner.start(first, speech.end) shouldBe true
            started.await()
            runner.stop(other) // a card of an earlier run
            runner.isActive shouldBe true
            runner.stop(first)
        }
        runner.isActive shouldBe false
        first.state.value shouldBe SongCardState.Done(SongOutcome.Stopped(whileSending = false))
        speech.spoken.shouldBeEmpty()

        coroutineScope {
            runner = SongRunner(this)
            runner.start(other, speech.end) shouldBe true
        }
        speech.spoken shouldBe listOf("I sent it. " + SongOutcome.Found(TRACK))
    }
})

class SongMessagesTest : StringSpec({
    "failures name the service, and ACRCloud's key and secret" {
        val audd = SongProviderKind.AUDD
        val acr = SongProviderKind.ACRCLOUD
        SongMessages.failure(RecognitionResult.BadToken, audd) shouldBe
            SongText(R.string.skill_song_error_bad_token, listOf("AudD"))
        SongMessages.failure(RecognitionResult.BadToken, acr) shouldBe
            SongText(R.string.skill_song_error_bad_key, listOf("ACRCloud"))
        SongMessages.failure(RecognitionResult.BadHost, acr) shouldBe
            SongText(R.string.skill_song_error_bad_host, listOf("ACRCloud"))
        SongMessages.failure(RecognitionResult.BadSettings, acr) shouldBe
            SongText(R.string.skill_song_error_bad_settings, listOf("ACRCloud"))
        SongMessages.failure(RecognitionResult.LimitReached, audd) shouldBe
            SongText(R.string.skill_song_error_limit, listOf("AudD"))
        SongMessages.failure(RecognitionResult.BadRecording, audd) shouldBe
            SongText(R.string.skill_song_error_bad_recording, listOf("AudD"))
        SongMessages.failure(RecognitionResult.NoConnection, audd) shouldBe
            SongText(R.string.skill_song_error_no_connection, listOf("AudD"))
        SongMessages.failure(RecognitionResult.HttpError(503), audd) shouldBe
            SongText(R.string.skill_song_error_http, listOf("AudD", 503))
        for (failure in listOf(RecognitionResult.ServiceDown, RecognitionResult.ServiceError(19, "x"))) {
            SongMessages.failure(failure, audd) shouldBe
                SongText(R.string.skill_song_error_service, listOf("AudD"))
            // a give-up notification: the clip is gone, nothing to try again
            SongMessages.failure(failure, audd, final = true) shouldBe
                SongText(R.string.skill_song_error_service_final, listOf("AudD"))
        }
    }

    "every outcome has its text" {
        val audd = SongProviderKind.AUDD
        mapOf(
            SongOutcome.Found(TRACK) to SongText(
                R.string.skill_song_that_is, listOf(TRACK.title, TRACK.artist),
            ),
            SongOutcome.NotFound to SongText(R.string.skill_song_not_recognized),
            SongOutcome.Queued(true) to SongText(R.string.skill_song_offline_queued),
            SongOutcome.Queued(false) to SongText(R.string.skill_song_unreachable_queued, listOf("AudD")),
            SongOutcome.QueueFull(3, true) to SongText(R.string.skill_song_queue_full, listOf(3)),
            SongOutcome.QueueFull(3, false) to
                SongText(R.string.skill_song_unreachable_queue_full, listOf("AudD", 3)),
            SongOutcome.QueueFailed(true) to SongText(R.string.skill_song_queue_failed),
            SongOutcome.QueueFailed(false) to
                SongText(R.string.skill_song_unreachable_queue_failed, listOf("AudD")),
            SongOutcome.NoNotifications(true) to
                SongText(R.string.skill_song_offline_no_notifications),
            SongOutcome.NoNotifications(false) to
                SongText(R.string.skill_song_unreachable_no_notifications, listOf("AudD")),
            SongOutcome.Silence to SongText(R.string.skill_song_silence),
            SongOutcome.MicFailure(RecordingResult.Failure.MIC_SILENCED) to
                SongText(R.string.skill_song_mic_silenced),
            SongOutcome.Failed(RecognitionResult.LimitReached) to
                SongText(R.string.skill_song_error_limit, listOf("AudD")),
            SongOutcome.Stopped(false) to SongText(R.string.skill_song_stopped),
            SongOutcome.Stopped(true) to SongText(R.string.skill_song_stopped_sending),
            SongOutcome.Error to SongText(R.string.skill_song_error),
        ).forEach { (outcome, text) -> SongMessages.outcome(outcome, audd) shouldBe text }
        SongMessages.thatIs("track01", null) shouldBe
            SongText(R.string.skill_song_that_is_no_artist, listOf("track01"))
    }

    "the other answers" {
        SongMessages.micFailure(RecordingResult.Failure.NO_PERMISSION) shouldBe
            R.string.skill_song_no_mic_permission
        SongMessages.micFailure(RecordingResult.Failure.MIC_UNAVAILABLE) shouldBe
            R.string.skill_song_mic_unavailable
        SongMessages.nothingAvailable(NowPlaying.NoAccess) shouldBe R.string.skill_song_no_listener_access
        SongMessages.nothingAvailable(NowPlaying.Untitled) shouldBe R.string.skill_song_untitled_playing
        SongMessages.nothingAvailable(NowPlaying.NothingPlaying) shouldBe
            R.string.skill_song_nothing_playing
        SongMessages.dropped(SongDropReason.NO_SERVICE) shouldBe
            R.string.skill_song_notification_no_service
        SongMessages.dropped(SongDropReason.SERVICE_CHANGED) shouldBe
            R.string.skill_song_notification_service_changed
        SongMessages.dropped(SongDropReason.EXPIRED) shouldBe R.string.skill_song_notification_expired
        SongMessages.audile(AudileResult.OPENED) shouldBe R.string.skill_song_opening_audile
        SongMessages.audile(AudileResult.BLOCKED_IN_BACKGROUND) shouldBe
            R.string.skill_song_audile_background
    }

    "GAR is quiet while Audile records" {
        AudileResult.entries.associateWith { SongMessages.speaksAudile(it) } shouldBe mapOf(
            AudileResult.LISTENING_IN_BACKGROUND to false, // the background shortcut records
            AudileResult.OPENED to false, // RECOGNIZE records at once
            AudileResult.OPENED_LOCKED to true, // its screen waits behind the lock screen
            AudileResult.OPENED_APP to true, // the user taps Audile's button
            AudileResult.BLOCKED_IN_BACKGROUND to true,
            AudileResult.FAILED to true,
        )
    }
})
