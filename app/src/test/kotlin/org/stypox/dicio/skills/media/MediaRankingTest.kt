package org.stypox.dicio.skills.media

import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.GarMatchCheck
import org.stypox.dicio.gar.allEnglishSkills
import org.stypox.dicio.gar.media.MediaCommand
import org.stypox.dicio.gar.media.RepeatMode
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Media
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import java.io.File

/**
 * Every English skill of a new install, ranked like the evaluator; [replace] swaps the media
 * controls for one that sees something playing (or ringing).
 */
private fun ranker(replace: MediaControlsSkill? = null): SkillRanker {
    val skills: List<Skill<*>> = allEnglishSkills().map {
        if (replace != null && it.correspondingSkillInfo.id == MediaControlsInfo.id) replace else it
    }
    return SkillRanker(
        GarOnlineSkills.forRanking(skills, UserSettingsSerializer.defaultValue.enabledSkillsMap),
        MockSkill(Specificity.LOW, 0.0f),
    )
}

private val defaultRanker = ranker()

private fun winner(input: String, ranker: SkillRanker = defaultRanker): Pair<String?, Any?> {
    val best = ranker.getBest(FakeSkillContext().forInput(input), input)
    return Pair(best?.skill?.correspondingSkillInfo?.id, best?.inputData)
}

private val catalogTools = ToolCatalog.load(
    File("src/main/gar/tools.yml").readText(),
    File("src/main/sentences/skill_definitions.yml").readText(),
).tools

private fun controls(playing: Boolean, ringing: Boolean = false) = MediaControlsSkill(
    MediaControlsInfo,
    Sentences.MediaControls["en"]!!,
    isPlaying = { playing },
    isRinging = { ringing },
)

class MediaRankingTest : StringSpec({
    "play requests go to play by name, and GAR's match check keeps them" {
        val state = GarMatchCheck.State(catalogTools, listOf(), ENGLISH_PARSER_FORMATTER)
        for (input in listOf(
            "play taylor swift on spotify", "play taylor swift", "put on my workout playlist",
            "play the latest episode of the daily", "play lofi on youtube music",
            "i want to listen to some jazz", "start my discover weekly playlist",
            "play bohemian rhapsody", "play dancing queen by abba", "listen to the daily podcast",
            "can you play some music by the beatles", "queue up some lo fi beats",
            "play the album thriller", "play something relaxing on youtube",
            "play stand by me", "stream the joe rogan podcast",
        )) {
            withClue(input) {
                val best = defaultRanker.getBest(FakeSkillContext().forInput(input), input)
                best?.skill?.correspondingSkillInfo?.id shouldBe MediaPlayInfo.id
                GarMatchCheck.rejects(state, best!!, input) shouldBe false
            }
        }
    }

    "the controls and the sleep timer go to the media controls" {
        for ((input, request) in listOf(
            "shuffle" to MediaControlRequest.Control(MediaCommand.Shuffle(true)),
            "turn on shuffle" to MediaControlRequest.Control(MediaCommand.Shuffle(true)),
            "turn off shuffle" to MediaControlRequest.Control(MediaCommand.Shuffle(false)),
            "repeat this song" to MediaControlRequest.Control(MediaCommand.Repeat(RepeatMode.ONE)),
            "turn on repeat" to MediaControlRequest.Control(MediaCommand.Repeat(RepeatMode.ALL)),
            "turn off repeat" to MediaControlRequest.Control(MediaCommand.Repeat(RepeatMode.OFF)),
            "skip ahead thirty seconds" to MediaControlRequest.Control(MediaCommand.Seek(30_000)),
            "fast forward two minutes" to MediaControlRequest.Control(MediaCommand.Seek(120_000)),
            "go back ten seconds" to MediaControlRequest.Control(MediaCommand.Seek(-10_000)),
            "rewind thirty seconds" to MediaControlRequest.Control(MediaCommand.Seek(-30_000)),
            "restart the song" to MediaControlRequest.Control(MediaCommand.Restart),
            "restart this track" to MediaControlRequest.Control(MediaCommand.Restart),
            "start the song over" to MediaControlRequest.Control(MediaCommand.Restart),
            "play the song again" to MediaControlRequest.Control(MediaCommand.Restart),
            "like this song" to MediaControlRequest.Control(MediaCommand.Rate(true)),
            "thumbs up this song" to MediaControlRequest.Control(MediaCommand.Rate(true)),
            "i don't like this song" to MediaControlRequest.Control(MediaCommand.Rate(false)),
            "stop the music in thirty minutes" to MediaControlRequest.SleepSet(1_800_000),
            "pause the podcast in twenty minutes" to MediaControlRequest.SleepSet(1_200_000),
            "sleep timer for an hour" to MediaControlRequest.SleepSet(3_600_000),
            "set a sleep timer for 45 minutes" to MediaControlRequest.SleepSet(2_700_000),
            "set a sleep timer" to MediaControlRequest.SleepSet(null),
            "cancel the sleep timer" to MediaControlRequest.SleepCancel,
            // Dicio's media keys in words its sentences miss (they went to the model)
            "resume playback" to MediaControlRequest.Key(Media.Play),
            "keep playing" to MediaControlRequest.Key(Media.Play),
            "continue the podcast" to MediaControlRequest.Key(Media.Play),
            "next one please" to MediaControlRequest.Key(Media.Next),
            "go back a song" to MediaControlRequest.Key(Media.Previous),
            "hold the music for a second" to MediaControlRequest.Key(Media.Pause),
            "play something else" to MediaControlRequest.Key(Media.Next),
            "stop the sleep timer" to MediaControlRequest.SleepCancel,
            "how long is left on the sleep timer" to MediaControlRequest.SleepQuery,
            "when will the music stop" to MediaControlRequest.SleepQuery,
        )) {
            withClue(input) { winner(input) shouldBe Pair(MediaControlsInfo.id, request) }
        }
    }

    "Dicio's media sentences and other skills keep theirs" {
        for ((input, skill) in listOf(
            "play music" to "media", "play the music" to "media", "play the next song" to "media",
            "play the previous track" to "media", "pause the music" to "media",
            "stop the music" to "media", "skip this song" to "media", "next" to "media",
            "rewind" to "media", "resume the music" to "media", "play the last song" to "media",
            "set a timer for ten minutes" to "timer", "cancel the timer" to "timer",
            "set a pasta timer for ten minutes" to "timer", "what song is this" to "song",
            "open spotify" to "open", "stop" to "conversation", "repeat that" to "conversation",
            "never mind" to "conversation", "start the stopwatch" to "stopwatch",
        )) {
            withClue(input) { winner(input).first shouldBe skill }
        }
        for (input in listOf(
            "repeat", "i like this", "thumbs up", "listen to this", "play a game",
            "play the last voicemail", "what's playing", "who sings bohemian rhapsody",
            "shuffle the cards", "remind me to go to sleep in an hour", "restart my phone",
        )) {
            withClue(input) {
                winner(input).first shouldNotBe MediaPlayInfo.id
                winner(input).first shouldNotBe MediaControlsInfo.id
            }
        }
    }

    "while something plays, stop pauses it and repeat repeats the song" {
        val playing = ranker(controls(playing = true))
        winner("stop", playing) shouldBe
            Pair(MediaControlsInfo.id, MediaControlRequest.Control(MediaCommand.Pause))
        winner("ok stop", playing).first shouldBe MediaControlsInfo.id
        winner("pause", playing).first shouldBe MediaControlsInfo.id
        winner("repeat", playing) shouldBe
            Pair(MediaControlsInfo.id, MediaControlRequest.Control(MediaCommand.Repeat(RepeatMode.ONE)))
        winner("i like this", playing).first shouldBe MediaControlsInfo.id
        // still GAR's conversation's
        winner("never mind", playing).first shouldBe "conversation"
        winner("repeat that", playing).first shouldBe "conversation"
        winner("stop talking", playing).first shouldBe "conversation"
    }

    "while something rings, stop is what rings" {
        winner("stop", ranker(controls(playing = true, ringing = true))).first shouldNotBe MediaControlsInfo.id
    }
})
