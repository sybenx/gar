package org.stypox.dicio.skills.media

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.media.MediaCommand
import org.stypox.dicio.gar.media.MediaFocus
import org.stypox.dicio.gar.media.MediaPlayRequest
import org.stypox.dicio.gar.media.RepeatMode
import org.stypox.dicio.sentences.Sentences.Media

private val durationOf: (String) -> Long? = { MediaControlsSkill.durationOf(FakeSkillContext(), it) }

private fun control(input: String): MediaControlMatch? = MediaControlWords.parse(input, durationOf)

private fun command(command: MediaCommand) = MediaControlRequest.Control(command)

class MediaWordsTest : StringSpec({
    "play requests name what to play, the app after on, and the focus the words give" {
        for ((input, expected) in listOf(
            "play taylor swift on spotify" to MediaPlayRequest(
                said = "taylor swift", query = "taylor swift", app = "spotify",
                whole = MediaPlayRequest(said = "taylor swift on spotify", query = "taylor swift on spotify"),
            ),
            "Play bohemian rhapsody." to MediaPlayRequest(said = "bohemian rhapsody", query = "bohemian rhapsody"),
            "can you play lofi on youtube music please" to MediaPlayRequest(
                said = "lofi", query = "lofi", app = "youtube music",
                whole = MediaPlayRequest(said = "lofi on youtube music", query = "lofi on youtube music"),
            ),
            "put on my workout playlist" to MediaPlayRequest(
                said = "my workout playlist", query = "workout playlist",
                focus = MediaFocus.PLAYLIST, playlist = "workout",
            ),
            "play the latest episode of the daily" to MediaPlayRequest(
                said = "the latest episode of the daily", query = "the latest episode of the daily",
                focus = MediaFocus.PODCAST,
            ),
            "i want to listen to some jazz" to MediaPlayRequest(said = "some jazz", query = "jazz"),
            "play me some jazz" to MediaPlayRequest(said = "some jazz", query = "jazz"),
            "play music by the beatles" to MediaPlayRequest(
                said = "music by the beatles", query = "the beatles",
                focus = MediaFocus.ARTIST, artist = "the beatles",
            ),
            "play the band queen" to MediaPlayRequest(
                said = "the band queen", query = "queen", focus = MediaFocus.ARTIST, artist = "queen",
            ),
            "play the album thriller by michael jackson" to MediaPlayRequest(
                said = "the album thriller by michael jackson", query = "thriller michael jackson",
                focus = MediaFocus.ALBUM, album = "thriller", artist = "michael jackson",
            ),
            "play the song dancing queen by abba" to MediaPlayRequest(
                said = "the song dancing queen by abba", query = "dancing queen abba",
                focus = MediaFocus.SONG, title = "dancing queen", artist = "abba",
            ),
            // "by" without a focus word stays as said: "stand by me" is a title
            "play stand by me" to MediaPlayRequest(said = "stand by me", query = "stand by me"),
            "start my discover weekly playlist" to MediaPlayRequest(
                said = "my discover weekly playlist", query = "discover weekly playlist",
                focus = MediaFocus.PLAYLIST, playlist = "discover weekly",
            ),
            "queue up some lo fi beats" to MediaPlayRequest(said = "some lo fi beats", query = "lo fi beats"),
            // music in an app: anything there
            "play some music on spotify" to MediaPlayRequest(
                said = "", query = "", app = "spotify",
                whole = MediaPlayRequest(said = "some music on spotify", query = "music on spotify"),
            ),
            "play something on the spotify app" to MediaPlayRequest(
                said = "", query = "", app = "spotify",
                whole = MediaPlayRequest(said = "something on the spotify app", query = "something on the spotify app"),
            ),
            // "on repeat" is no app
            "play bohemian rhapsody on repeat" to MediaPlayRequest(
                said = "bohemian rhapsody on repeat", query = "bohemian rhapsody on repeat",
            ),
        )) {
            withClue(input) { MediaPlayWords.parse(input) shouldBe expected }
        }
    }

    "a song with on in its title keeps the whole title for when the words name no app" {
        val request = MediaPlayWords.parse("play dancing on my own").shouldNotBeNull()
        request.app shouldBe "own" // "the" and "my" before an app's name are dropped
        request.whole shouldBe MediaPlayRequest(said = "dancing on my own", query = "dancing on my own")
    }

    "inputs that name nothing to play, or something else, aren't play requests" {
        for (input in listOf(
            "play", "play music", "play some music", "play a song", "play the song", "play it",
            "play it again", "play this song again", "play something", "play the next song",
            "play the next", "play the previous one", "play the last song", "play the last song again",
            "play next track", "play the last voicemail", "play my messages", "play a game",
            "let's play a game", "play with the dog", "play back the recording", "listen to this",
            "listen to me", "start the stopwatch", "start the video call", "start a timer",
            "put on", "put on some", "what's playing", "who sings bohemian rhapsody",
            "pause the music", "stop the music", "open spotify", "resume playback", "keep playing",
            "play on spotify",
        )) {
            withClue(input) { MediaPlayWords.parse(input).shouldBeNull() }
        }
    }

    "the model's call keeps its query, without the verb or an app it left in" {
        MediaPlayWords.fromSlots("dancing queen by abba", null) shouldBe
            MediaPlayRequest(said = "dancing queen by abba", query = "dancing queen by abba")
        MediaPlayWords.fromSlots("put on dancing queen", null) shouldBe
            MediaPlayRequest(said = "dancing queen", query = "dancing queen")
        MediaPlayWords.fromSlots("taylor swift", "Spotify") shouldBe
            MediaPlayRequest(said = "taylor swift", query = "taylor swift", app = "Spotify")
        MediaPlayWords.fromSlots("taylor swift on spotify", null)?.app shouldBe "spotify"
        // the app the model gave wins over words that look like one
        MediaPlayWords.fromSlots("dancing on my own", "Spotify") shouldBe MediaPlayRequest(
            said = "dancing on my own", query = "dancing on my own", app = "Spotify",
        )
        MediaPlayWords.fromSlots("my workout playlist", null)?.focus shouldBe MediaFocus.PLAYLIST
        // "some music": anything
        MediaPlayWords.fromSlots("some music", null) shouldBe MediaPlayRequest(said = "", query = "")
        MediaPlayWords.fromSlots("music", "YouTube Music") shouldBe
            MediaPlayRequest(said = "", query = "", app = "YouTube Music")
    }

    "the model's call for a skip or for something that isn't media plays nothing" {
        for (query in listOf(
            "last song", "the next song", "a game", "my voicemail", "with the dog", "something else",
        )) {
            withClue(query) { MediaPlayWords.fromSlots(query, null).shouldBeNull() }
        }
    }

    "something else is a skip, not a title" {
        for (input in listOf(
            "play something else", "play something different", "play another song",
            "play a different one", "play some other song",
        )) {
            withClue(input) { MediaPlayWords.parse(input).shouldBeNull() }
        }
        for (input in listOf(
            "play nothing else matters", "play somebody else", "play another brick in the wall",
            "play another one bites the dust", "play a different world",
        )) {
            withClue(input) { MediaPlayWords.parse(input).shouldNotBeNull() }
        }
    }

    "the model's app counts only when the words say it after on, in or with" {
        MediaPlayWords.saysApp("play taylor swift on spotify", "spotify") shouldBe true
        MediaPlayWords.saysApp("listen to my chill playlist on you tube music", "you tube music") shouldBe
            true
        MediaPlayWords.saysApp("play it on the spotify app", "spotify") shouldBe true
        MediaPlayWords.saysApp("put on the new kendrick lamar album", "album") shouldBe false
        MediaPlayWords.saysApp("i'd like to hear hotel california by the eagles", "the eagles") shouldBe
            false
        MediaPlayWords.saysApp("play the radio lab podcast", "podcast") shouldBe false
    }

    "the control phrases" {
        for ((inputs, request) in listOf(
            listOf(
                "shuffle", "Shuffle.", "turn on shuffle", "turn shuffle on", "shuffle on",
                "enable shuffle", "shuffle the music", "shuffle my playlist", "shuffle it",
                "ok can you shuffle the songs please", "put it on shuffle", "play in random order",
                "shuffle all songs", "start shuffling",
            ) to command(MediaCommand.Shuffle(true)),
            listOf(
                "turn off shuffle", "turn shuffle off", "shuffle off", "disable shuffle",
                "stop shuffling", "don't shuffle", "no shuffle", "no more shuffling", "unshuffle",
                "play in order", "switch off shuffle mode",
            ) to command(MediaCommand.Shuffle(false)),
            listOf(
                "repeat this song", "repeat the song", "loop this track", "repeat this",
                "repeat one", "put this song on repeat", "put it on repeat",
                "play this song on repeat", "keep repeating this song", "loop it",
            ) to command(MediaCommand.Repeat(RepeatMode.ONE)),
            listOf(
                "repeat all", "repeat the playlist", "loop the album", "repeat everything",
                "turn on repeat", "turn repeat on", "repeat on", "enable repeat",
                "put the playlist on repeat", "repeat the whole album", "turn on repeat mode",
            ) to command(MediaCommand.Repeat(RepeatMode.ALL)),
            listOf(
                "turn off repeat", "turn repeat off", "repeat off", "stop repeating",
                "don't repeat this song", "no more repeat", "disable repeat", "stop looping",
                "turn off loop",
            ) to command(MediaCommand.Repeat(RepeatMode.OFF)),
            listOf(
                "restart the song", "restart this track", "restart song", "restart it",
                "start the song over", "start it over", "start this song from the beginning",
                "play it from the beginning", "play the song again", "play it again",
                "play this song one more time", "go back to the beginning", "back to the start",
                "from the top", "rewind the song", "replay this song", "take it from the top",
                "go back to the start of the song",
            ) to command(MediaCommand.Restart),
            listOf(
                "like this song", "i like this song", "i love this track", "thumbs up this song",
                "give it a thumbs up", "give this song a thumbs up", "heart this song",
                "favorite this song", "add this song to my liked songs", "save this song",
                "add it to my favorites",
            ) to command(MediaCommand.Rate(true)),
            listOf(
                "dislike this song", "i don't like this song", "i hate this song",
                "thumbs down this song", "give it a thumbs down",
            ) to command(MediaCommand.Rate(false)),
            listOf(
                "cancel the sleep timer", "stop the sleep timer", "turn off the sleep timer",
                "delete my sleep timer", "turn the sleep timer off", "no sleep timer",
            ) to MediaControlRequest.SleepCancel,
            listOf(
                "how long is left on the sleep timer", "how much time is left on my sleep timer",
                "when will the music stop", "how long until the music stops",
                "is there a sleep timer", "is the sleep timer on", "check the sleep timer",
                "when does the sleep timer end", "do i have a sleep timer set",
            ) to MediaControlRequest.SleepQuery,
            listOf("set a sleep timer", "sleep timer", "start a sleep timer", "turn on the sleep timer")
                to MediaControlRequest.SleepSet(null),
        )) {
            for (input in inputs) {
                withClue(input) { control(input) shouldBe MediaControlMatch(request) }
            }
        }
    }

    "seek and sleep timer phrases read their duration" {
        for ((input, request) in listOf(
            "skip ahead thirty seconds" to command(MediaCommand.Seek(30_000)),
            "fast forward two minutes" to command(MediaCommand.Seek(120_000)),
            "skip forward 30 seconds" to command(MediaCommand.Seek(30_000)),
            "jump ahead a minute and a half" to command(MediaCommand.Seek(90_000)),
            "skip thirty seconds" to command(MediaCommand.Seek(30_000)),
            "go back ten seconds" to command(MediaCommand.Seek(-10_000)),
            "rewind thirty seconds" to command(MediaCommand.Seek(-30_000)),
            "skip back fifteen seconds" to command(MediaCommand.Seek(-15_000)),
            "go thirty seconds back" to command(MediaCommand.Seek(-30_000)),
            "back up a minute" to command(MediaCommand.Seek(-60_000)),
            "replay the last ten seconds" to command(MediaCommand.Seek(-10_000)),
            "stop the music in thirty minutes" to MediaControlRequest.SleepSet(1_800_000),
            "sleep timer for an hour" to MediaControlRequest.SleepSet(3_600_000),
            "set a sleep timer for 45 minutes" to MediaControlRequest.SleepSet(2_700_000),
            "pause the podcast in twenty minutes" to MediaControlRequest.SleepSet(1_200_000),
            "stop playing in half an hour" to MediaControlRequest.SleepSet(1_800_000),
            "turn off the music after an hour and a half" to MediaControlRequest.SleepSet(5_400_000),
            "turn the music off in ten minutes" to MediaControlRequest.SleepSet(600_000),
            "set a thirty minute sleep timer" to MediaControlRequest.SleepSet(1_800_000),
            "change the sleep timer to an hour" to MediaControlRequest.SleepSet(3_600_000),
        )) {
            withClue(input) { control(input) shouldBe MediaControlMatch(request) }
        }
    }

    "words that only mean the music while it plays say so" {
        for ((input, request) in listOf(
            "repeat" to command(MediaCommand.Repeat(RepeatMode.ONE)),
            "loop" to command(MediaCommand.Repeat(RepeatMode.ONE)),
            "on repeat" to command(MediaCommand.Repeat(RepeatMode.ONE)),
            "i like this" to command(MediaCommand.Rate(true)),
            "i love that" to command(MediaCommand.Rate(true)),
            "thumbs up" to command(MediaCommand.Rate(true)),
            "thumbs down" to command(MediaCommand.Rate(false)),
            "i don't like this" to command(MediaCommand.Rate(false)),
        )) {
            withClue(input) { control(input) shouldBe MediaControlMatch(request, whilePlaying = true) }
        }
    }

    "Dicio's play, pause, next and previous in words its sentences miss" {
        for ((input, action) in listOf(
            "resume" to Media.Play, "resume playback" to Media.Play, "keep playing" to Media.Play,
            "resume playing my podcast" to Media.Play, "continue the podcast" to Media.Play,
            "unpause" to Media.Play, "hold the music for a second" to Media.Pause,
            "paws the music" to Media.Pause, "next one please" to Media.Next,
            "skip it" to Media.Next, "play something else" to Media.Next,
            "play another song" to Media.Next, "go back a song" to Media.Previous,
            "previous one" to Media.Previous,
        )) {
            withClue(input) { control(input) shouldBe MediaControlMatch(MediaControlRequest.Key(action)) }
        }
    }

    "near misses aren't control phrases" {
        for (input in listOf(
            "shuffle the cards", "repeat that", "repeat it", "repeat after me", "say that again",
            "stop the music", "pause the music", "skip this song", "skip ahead", "fast forward",
            "rewind", "go back", "go back to the previous song", "skip the song",
            "set a timer for ten minutes", "cancel the timer", "stop in thirty minutes",
            "remind me to go to sleep in an hour", "i like pizza", "i love you", "like",
            "restart", "restart my phone", "replay the last message", "save the date",
            "how long is left on the timer", "what's playing", "play taylor swift",
            "don't stop the music", "stop the music in the morning", "skip a song",
            "jump to the next track", "go back to the main menu", "play it",
        )) {
            withClue(input) { control(input).shouldBeNull() }
        }
    }

    "stop words pause what plays, only as a whole" {
        for (input in listOf("stop", "Stop.", "ok stop", "stop it", "pause", "pause it", "be quiet", "shut up")) {
            withClue(input) { MediaControlWords.isBareStop(input).shouldBeTrue() }
        }
        for (input in listOf("stop the music", "stop the timer", "never mind", "stop talking", "pause the timer")) {
            withClue(input) { MediaControlWords.isBareStop(input).shouldBeFalse() }
        }
    }

    "the answer to how long a sleep timer runs is a duration" {
        MediaControlWords.sleepAnswer("thirty minutes", durationOf) shouldBe 1_800_000
        MediaControlWords.sleepAnswer("for an hour", durationOf) shouldBe 3_600_000
        MediaControlWords.sleepAnswer("make it twenty minutes please", durationOf) shouldBe 1_200_000
        MediaControlWords.sleepAnswer("call mom", durationOf).shouldBeNull()
        MediaControlWords.sleepAnswer("for", durationOf).shouldBeNull()
        MediaControlWords.sleepAnswer("tomorrow", durationOf).shouldBeNull()
    }
})
