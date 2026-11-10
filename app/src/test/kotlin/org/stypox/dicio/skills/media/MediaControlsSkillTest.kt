package org.stypox.dicio.skills.media

import android.media.session.PlaybackState
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.stypox.dicio.R
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.media.FakeSessions
import org.stypox.dicio.gar.media.GarMediaSessions
import org.stypox.dicio.gar.media.GarSleepTimer
import org.stypox.dicio.gar.media.MediaCommand
import org.stypox.dicio.gar.media.RepeatMode
import org.stypox.dicio.gar.media.SessionCommand
import org.stypox.dicio.gar.media.session
import org.stypox.dicio.gar.timer.GarTimerClock
import org.stypox.dicio.gar.timer.GarTimerScheduler
import org.stypox.dicio.gar.timer.GarTimerStore
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.stopwatch.ClockReading
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** A sleep timer in memory, at a fixed moment. */
private fun memorySleepTimer(onPause: () -> Unit = {}): GarSleepTimer {
    var saved: String? = null
    return GarSleepTimer(
        store = object : GarTimerStore {
            override fun load(): String? = saved
            override fun save(encoded: String) {
                saved = encoded
            }
        },
        clock = GarTimerClock { ClockReading(1_000_000, 1_700_000_000_000, 1) },
        scheduler = object : GarTimerScheduler {
            override fun schedule(elapsedRealtime: Long) {}
            override fun cancel() {}
        },
        pauseMedia = onPause,
    )
}

private class Rig(
    var playing: Boolean = false,
    var ringing: Boolean = false,
    val sessions: FakeSessions = FakeSessions(),
    val sleepTimer: GarSleepTimer = memorySleepTimer(),
) {
    var stoppedSpeaking = 0

    val skill = MediaControlsSkill(
        MediaControlsInfo,
        Sentences.MediaControls["en"]!!,
        sessions = { sessions },
        sleepTimer = { sleepTimer },
        isPlaying = { playing },
        isRinging = { ringing },
        stopSpeaking = { stoppedSpeaking++ },
        now = { ZonedDateTime.of(2026, 10, 27, 22, 0, 0, 0, ZoneOffset.UTC) },
    )

    fun score(input: String): Pair<Score, MediaControlRequest?> =
        skill.score(FakeSkillContext().forInput(input), input)

    suspend fun run(input: String) = skill.generateOutput(FakeSkillContext(), score(input).second)
}

class MediaControlsSkillTest : StringSpec({
    "the skill's phrases score as its sentences do, and only them" {
        val rig = Rig()
        for (input in listOf(
            "shuffle", "turn off shuffle", "repeat this song", "turn on repeat", "turn repeat off",
            "skip ahead thirty seconds", "go back ten seconds", "like this song", "thumbs down this song",
        )) {
            withClue(input) { rig.score(input).first.scoreIn01Range() shouldBeGreaterThan 0.85f }
        }
        for (input in listOf("shuffle the cards", "repeat that", "stop the music", "skip this song", "play music")) {
            withClue(input) { rig.score(input).first shouldBeSameInstanceAs AlwaysWorstScore }
        }
    }

    "what Dicio's own sentences also take scores best" {
        val rig = Rig()
        for (input in listOf(
            "restart the song", "set a sleep timer for an hour", "cancel the sleep timer",
            "how long is left on the sleep timer", "set a sleep timer",
        )) {
            withClue(input) { rig.score(input).first shouldBeSameInstanceAs AlwaysBestScore }
        }
    }

    "stop pauses what plays, while nothing rings" {
        val rig = Rig()
        rig.score("stop").first shouldBeSameInstanceAs AlwaysWorstScore
        rig.playing = true
        rig.score("stop") shouldBe Pair(AlwaysBestScore, MediaControlRequest.Control(MediaCommand.Pause))
        rig.score("pause it").first shouldBeSameInstanceAs AlwaysBestScore
        rig.ringing = true
        rig.score("stop").first shouldBeSameInstanceAs AlwaysWorstScore
    }

    "repeat alone and i like this count only while something plays" {
        val rig = Rig()
        for (input in listOf("repeat", "i like this", "thumbs up")) {
            withClue(input) { rig.score(input).first shouldBeSameInstanceAs AlwaysWorstScore }
        }
        rig.playing = true
        rig.score("repeat").second shouldBe MediaControlRequest.Control(MediaCommand.Repeat(RepeatMode.ONE))
        rig.score("i like this").second shouldBe MediaControlRequest.Control(MediaCommand.Rate(true))
    }

    "stop pauses every player that plays, stops GAR's speech and says nothing" {
        val rig = Rig(
            playing = true,
            sessions = FakeSessions(listOf(session("a", appName = "A"), session("b", state = PlaybackState.STATE_PAUSED))),
        )
        val output = rig.run("stop")
        output shouldBe GarMediaOutput(MediaText(R.string.skill_media_paused), speak = false)
        rig.stoppedSpeaking shouldBe 1
        rig.sessions.sent shouldContainExactly listOf(Pair("a", SessionCommand.Pause))
    }

    "the controls answer what they did" {
        val rig = Rig(sessions = FakeSessions(listOf(session(
            "a", appName = "A",
            actions = GarMediaSessions.ACTION_SET_SHUFFLE_MODE or PlaybackState.ACTION_SEEK_TO,
            positionMs = 60_000,
        ))))
        rig.run("turn on shuffle") shouldBe GarMediaOutput(MediaText(R.string.skill_media_shuffle_on))
        rig.run("go back ten seconds") shouldBe
            GarMediaOutput(MediaText(R.string.skill_media_seek_back, listOf(Duration.ofSeconds(10))))
        rig.run("turn on repeat") shouldBe
            GarMediaOutput(MediaText(R.string.skill_media_repeat_unsupported, listOf("A")))
        rig.stoppedSpeaking shouldBe 0
    }

    "without notification access the answer offers it" {
        val rig = Rig(sessions = FakeSessions(null))
        rig.run("shuffle") shouldBe GarMediaOutput(
            MediaText(R.string.skill_media_controls_no_access),
            detail = MediaText(R.string.skill_media_controls_no_access_detail),
            button = MediaButton.ALLOW_ACCESS,
        )
    }

    "a sleep timer says how long and when the music stops, and can be cancelled" {
        val rig = Rig()
        rig.run("stop the music in thirty minutes") shouldBe GarMediaOutput(
            MediaText(R.string.skill_media_sleep_set, listOf(Duration.ofMinutes(30))),
            detail = MediaText(R.string.skill_media_sleep_until, listOf(LocalTime.of(22, 30))),
            button = MediaButton.CANCEL_SLEEP_TIMER,
        )
        rig.run("how long is left on the sleep timer") shouldBe GarMediaOutput(
            MediaText(R.string.skill_media_sleep_left, listOf(Duration.ofMinutes(30))),
            button = MediaButton.CANCEL_SLEEP_TIMER,
        )
        rig.run("cancel the sleep timer") shouldBe MediaAnswers.sleepCancelled()
        rig.run("cancel the sleep timer") shouldBe MediaAnswers.sleepNone()
        rig.run("when will the music stop") shouldBe MediaAnswers.sleepNone()
        rig.sleepTimer.remainingMillis().shouldBeNull()
    }

    "a sleep timer longer than a day isn't set" {
        val rig = Rig()
        rig.run("sleep timer for two days") shouldBe MediaAnswers.sleepTooLong()
        rig.sleepTimer.remainingMillis().shouldBeNull()
    }

    "a sleep timer without a time asks how long, and the answer sets it" {
        val rig = Rig()
        val question = rig.run("set a sleep timer").shouldBeInstanceOf<MediaSleepAskOutput>()
        val plan = question.getInteractionPlan(FakeSkillContext())
            .shouldBeInstanceOf<InteractionPlan.StartSubInteraction>()
        plan.reopenMicrophone shouldBe true
        @Suppress("UNCHECKED_CAST")
        val answer = plan.nextSkills.single() as Skill<Long?>
        val ctx = FakeSkillContext()
        answer.score(ctx.forInput("call mom"), "call mom").first shouldBeSameInstanceAs AlwaysWorstScore
        val (score, millis) = answer.score(ctx.forInput("for forty five minutes"), "for forty five minutes")
        score shouldBeSameInstanceAs AlwaysBestScore
        answer.generateOutput(ctx, millis).shouldBeInstanceOf<GarMediaOutput>().headline shouldBe
            MediaText(R.string.skill_media_sleep_set, listOf(Duration.ofMinutes(45)))
        rig.sleepTimer.remainingMillis() shouldBe 2_700_000L
    }

    "the time left is said in whole minutes, rounded up" {
        MediaAnswers.rounded(1_740_001) shouldBe Duration.ofMinutes(30)
        MediaAnswers.rounded(60_000) shouldBe Duration.ofMinutes(1)
        MediaAnswers.rounded(59_001) shouldBe Duration.ofSeconds(60)
        MediaAnswers.rounded(100) shouldBe Duration.ofSeconds(1)
    }
})
