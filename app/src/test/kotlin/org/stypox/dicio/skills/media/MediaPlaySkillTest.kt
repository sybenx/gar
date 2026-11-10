package org.stypox.dicio.skills.media

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.skill.skill.AlwaysWorstScore
import org.stypox.dicio.R
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.media.FakePlayers
import org.stypox.dicio.gar.media.FakeSessions
import org.stypox.dicio.gar.media.GarMediaPlay
import org.stypox.dicio.gar.media.LauncherApp
import org.stypox.dicio.gar.media.MediaPlayRequest
import org.stypox.dicio.gar.media.PlayOutcome
import org.stypox.dicio.gar.media.SPOTIFY
import org.stypox.dicio.sentences.Sentences

private class PlayRig(val players: FakePlayers = FakePlayers(
    handlers = listOf(SPOTIFY), apps = listOf(LauncherApp("Spotify", SPOTIFY)),
)) {
    val skill = MediaPlaySkill(MediaPlayInfo, Sentences.MediaPlay["en"]!!) {
        GarMediaPlay(FakeSessions(), players) {}
    }

    fun score(input: String) = skill.score(FakeSkillContext().forInput(input), input)
}

class MediaPlaySkillTest : StringSpec({
    "play requests score as the play sentences do; other inputs not at all" {
        val rig = PlayRig()
        for (input in listOf(
            "play taylor swift on spotify", "play bohemian rhapsody", "put on my workout playlist",
            "listen to the daily", "i want to listen to some jazz", "start my discover weekly playlist",
        )) {
            rig.score(input).first.scoreIn01Range() shouldBeGreaterThan 0.7f
        }
        for (input in listOf("play music", "play the next song", "play a game", "start the stopwatch")) {
            rig.score(input).first shouldBeSameInstanceAs AlwaysWorstScore
        }
    }

    "the model's call builds the same request as the sentences" {
        val rig = PlayRig()
        rig.skill.inputFromSlots(mapOf("query" to "taylor swift", "app" to "Spotify")) shouldBe
            MediaPlayRequest(said = "taylor swift", query = "taylor swift", app = "Spotify")
        rig.skill.inputFromSlots(mapOf("query" to "my workout playlist", "app" to null)) shouldBe
            MediaPlayWords.parse("play my workout playlist")
    }

    "it says what plays where, as said, with my as your" {
        val rig = PlayRig()
        val output = rig.skill.generateOutput(FakeSkillContext(), rig.score("put on my workout playlist on spotify").second)
        output shouldBe GarMediaOutput(MediaText(
            R.string.skill_media_play_playing_on, listOf("your workout playlist", "Spotify"),
        ))
        rig.players.started.map { it.first } shouldContainExactly listOf(SPOTIFY)
    }

    "the answers of the outcomes" {
        val taylor = MediaPlayRequest(said = "taylor swift", query = "taylor swift")
        MediaAnswers.play(PlayOutcome.Playing(MediaPlayRequest(said = "", query = ""), "Spotify")) shouldBe
            GarMediaOutput(MediaText(R.string.skill_media_play_anything_on, listOf("Spotify")))
        MediaAnswers.play(PlayOutcome.AskedApp(taylor, "Spotify")) shouldBe
            GarMediaOutput(MediaText(R.string.skill_media_play_asked, listOf("taylor swift", "Spotify")))
        MediaAnswers.play(PlayOutcome.NotInstalled("youtube music")) shouldBe
            GarMediaOutput(MediaText(R.string.skill_media_play_not_installed, listOf("Youtube music")))
        MediaAnswers.play(PlayOutcome.CantPlayByName("AntennaPod", noAccess = true)) shouldBe GarMediaOutput(
            MediaText(R.string.skill_media_play_cant, listOf("AntennaPod")),
            detail = MediaText(R.string.skill_media_play_cant_access, listOf("AntennaPod")),
            button = MediaButton.ALLOW_ACCESS,
        )
        MediaAnswers.play(PlayOutcome.NoPlayer) shouldBe GarMediaOutput(
            MediaText(R.string.skill_media_play_no_player),
            detail = MediaText(R.string.skill_media_play_no_player_hint),
        )
        MediaAnswers.play(PlayOutcome.Choosing(taylor)).detail shouldBe
            MediaText(R.string.skill_media_play_choose_hint)
    }
})
