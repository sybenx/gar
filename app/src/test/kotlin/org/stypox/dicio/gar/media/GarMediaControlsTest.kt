package org.stypox.dicio.gar.media

import android.media.Rating
import android.media.session.PlaybackState
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.media.GarMediaControls.CustomKind

private fun perform(command: MediaCommand, vararg sessions: MediaSessionView): Pair<ControlOutcome, FakeSessions> {
    val fake = FakeSessions(sessions.toList())
    return Pair(GarMediaControls.perform(command, fake), fake)
}

private val SHUFFLE_AND_REPEAT = GarMediaSessions.ACTION_SET_SHUFFLE_MODE or GarMediaSessions.ACTION_SET_REPEAT_MODE

class GarMediaControlsTest : StringSpec({
    "the command goes to the session that plays, else to the first" {
        val paused = session("a", state = PlaybackState.STATE_PAUSED)
        val playing = session("b")
        GarMediaControls.target(listOf(paused, playing)) shouldBe playing
        GarMediaControls.target(listOf(paused, session("c", state = PlaybackState.STATE_STOPPED))) shouldBe paused
        GarMediaControls.target(listOf()).shouldBeNull()
    }

    "without notification access or a session there is nothing to control" {
        GarMediaControls.perform(MediaCommand.Restart, FakeSessions(null)) shouldBe ControlOutcome.NoAccess
        perform(MediaCommand.Restart).first shouldBe ControlOutcome.NothingPlaying
    }

    "shuffle and repeat use androidx.media's set-mode actions when the session has them" {
        val player = session(SPOTIFY, actions = SHUFFLE_AND_REPEAT, appName = "Spotify")
        for ((command, sent) in listOf(
            MediaCommand.Shuffle(true) to SessionCommand.CustomAction(
                GarMediaSessions.CUSTOM_SET_SHUFFLE_MODE, mapOf(GarMediaSessions.ARGUMENT_SHUFFLE_MODE to 1)),
            MediaCommand.Shuffle(false) to SessionCommand.CustomAction(
                GarMediaSessions.CUSTOM_SET_SHUFFLE_MODE, mapOf(GarMediaSessions.ARGUMENT_SHUFFLE_MODE to 0)),
            MediaCommand.Repeat(RepeatMode.ONE) to SessionCommand.CustomAction(
                GarMediaSessions.CUSTOM_SET_REPEAT_MODE, mapOf(GarMediaSessions.ARGUMENT_REPEAT_MODE to 1)),
            MediaCommand.Repeat(RepeatMode.ALL) to SessionCommand.CustomAction(
                GarMediaSessions.CUSTOM_SET_REPEAT_MODE, mapOf(GarMediaSessions.ARGUMENT_REPEAT_MODE to 2)),
            MediaCommand.Repeat(RepeatMode.OFF) to SessionCommand.CustomAction(
                GarMediaSessions.CUSTOM_SET_REPEAT_MODE, mapOf(GarMediaSessions.ARGUMENT_REPEAT_MODE to 0)),
        )) {
            withClue(command) {
                val (outcome, fake) = perform(command, player)
                outcome shouldBe ControlOutcome.Done(command, "Spotify")
                fake.sent shouldContainExactly listOf(Pair(SPOTIFY, sent))
            }
        }
    }

    "a session with only a shuffle or repeat button gets it pressed, and GAR says it can't tell the state" {
        val player = session(
            SPOTIFY, appName = "Spotify",
            customActions = listOf(
                MediaCustomAction("com.spotify.music.ACTION_TOGGLE_SHUFFLE", "Shuffle"),
                MediaCustomAction("repeat", "Repeat"),
            ),
        )
        val (shuffled, fake) = perform(MediaCommand.Shuffle(false), player)
        shuffled shouldBe ControlOutcome.Toggled(MediaCommand.Shuffle(false), "Spotify")
        fake.sent shouldContainExactly listOf(
            Pair(SPOTIFY, SessionCommand.CustomAction("com.spotify.music.ACTION_TOGGLE_SHUFFLE")))
        perform(MediaCommand.Repeat(RepeatMode.ALL), player).first shouldBe
            ControlOutcome.Toggled(MediaCommand.Repeat(RepeatMode.ALL), "Spotify")
    }

    "a session without anything for a command says so, by the app's name or package" {
        val (outcome, fake) = perform(MediaCommand.Shuffle(true), session("org.example.player"))
        outcome shouldBe ControlOutcome.Unsupported(MediaCommand.Shuffle(true), "org.example.player")
        fake.sent.shouldBeEmpty()
        perform(MediaCommand.Seek(30_000), session("a", appName = "A")).first shouldBe
            ControlOutcome.Unsupported(MediaCommand.Seek(30_000), "A")
        perform(MediaCommand.Restart, session("a", appName = "A")).first shouldBe
            ControlOutcome.Unsupported(MediaCommand.Restart, "A")
        perform(MediaCommand.Rate(true), session("a", appName = "A")).first shouldBe
            ControlOutcome.Unsupported(MediaCommand.Rate(true), "A")
    }

    "seeking moves from where the track is now, within the track" {
        // at 10 s when the state was read at 90 s, 10 s ago, playing at normal speed: now at 20 s
        fun player(state: Int = PlaybackState.STATE_PLAYING) = session(
            "a", state = state, actions = PlaybackState.ACTION_SEEK_TO, positionMs = 10_000,
            updateTimeElapsed = 90_000, durationMs = 200_000, appName = "A",
        )
        val (forward, fake) = perform(MediaCommand.Seek(30_000), player())
        forward shouldBe ControlOutcome.Done(MediaCommand.Seek(30_000), "A")
        fake.sent shouldContainExactly listOf(Pair("a", SessionCommand.SeekTo(50_000)))
        // paused, it stays at 10 s
        perform(MediaCommand.Seek(-5_000), player(PlaybackState.STATE_PAUSED)).second.sent shouldContainExactly
            listOf(Pair("a", SessionCommand.SeekTo(5_000)))
        // back past the start: the start
        perform(MediaCommand.Seek(-60_000), player()).second.sent shouldContainExactly
            listOf(Pair("a", SessionCommand.SeekTo(0)))
        // forward past the end: nothing
        val (past, pastFake) = perform(MediaCommand.Seek(600_000), player())
        past shouldBe ControlOutcome.OutOfTrack(MediaCommand.Seek(600_000), "A")
        pastFake.sent.shouldBeEmpty()
        // without a known length, forward as far as asked
        perform(MediaCommand.Seek(600_000), player().copy(durationMs = null)).second.sent shouldContainExactly
            listOf(Pair("a", SessionCommand.SeekTo(620_000)))
    }

    "restart goes to the start" {
        perform(MediaCommand.Restart, session("a", actions = PlaybackState.ACTION_SEEK_TO, positionMs = 50_000))
            .second.sent shouldContainExactly listOf(Pair("a", SessionCommand.SeekTo(0)))
    }

    "a like uses the session's rating, else its like button" {
        val thumbs = session("a", actions = PlaybackState.ACTION_SET_RATING, ratingType = Rating.RATING_THUMB_UP_DOWN)
        perform(MediaCommand.Rate(true), thumbs).second.sent shouldContainExactly
            listOf(Pair("a", SessionCommand.SetRating(Rating.RATING_THUMB_UP_DOWN, true)))
        perform(MediaCommand.Rate(false), thumbs).second.sent shouldContainExactly
            listOf(Pair("a", SessionCommand.SetRating(Rating.RATING_THUMB_UP_DOWN, false)))

        val heart = session("a", actions = PlaybackState.ACTION_SET_RATING, ratingType = Rating.RATING_HEART)
        perform(MediaCommand.Rate(true), heart).second.sent shouldContainExactly
            listOf(Pair("a", SessionCommand.SetRating(Rating.RATING_HEART, true)))
        // a heart has no thumbs down
        perform(MediaCommand.Rate(false), heart).first shouldBe
            ControlOutcome.Unsupported(MediaCommand.Rate(false), "a")

        val buttons = session(
            "a", appName = "A",
            customActions = listOf(
                MediaCustomAction("start_radio", "Start radio"),
                MediaCustomAction("ADD_TO_COLLECTION", "Add to Liked Songs"),
            ),
        )
        val (liked, fake) = perform(MediaCommand.Rate(true), buttons)
        liked shouldBe ControlOutcome.Done(MediaCommand.Rate(true), "A")
        fake.sent shouldContainExactly listOf(Pair("a", SessionCommand.CustomAction("ADD_TO_COLLECTION")))
    }

    "a like button that undoes the like means the track is liked already" {
        val liked = session(
            "a", appName = "A",
            customActions = listOf(MediaCustomAction("REMOVE_FROM_COLLECTION", "Remove from Liked Songs")),
        )
        val (outcome, fake) = perform(MediaCommand.Rate(true), liked)
        outcome shouldBe ControlOutcome.Already(MediaCommand.Rate(true), "A")
        fake.sent.shouldBeEmpty()
        perform(MediaCommand.Rate(false), session("a", customActions = listOf(MediaCustomAction("x", "Remove dislike"))))
            .first shouldBe ControlOutcome.Already(MediaCommand.Rate(false), "a")
    }

    "the custom actions are known by their ids and names" {
        for ((action, kind) in listOf(
            MediaCustomAction("com.app.ACTION_THUMBS_UP", "") to CustomKind.LIKE,
            MediaCustomAction("thumbsUp", "") to CustomKind.LIKE,
            MediaCustomAction("x", "Like") to CustomKind.LIKE,
            MediaCustomAction("x", "Add to Liked Songs") to CustomKind.LIKE,
            MediaCustomAction("x", "Favorite") to CustomKind.LIKE,
            MediaCustomAction("x", "Add to favourites") to CustomKind.LIKE,
            MediaCustomAction("x", "Unlike") to CustomKind.UNLIKE,
            MediaCustomAction("x", "Liked") to CustomKind.UNLIKE,
            MediaCustomAction("x", "Remove from favorites") to CustomKind.UNLIKE,
            MediaCustomAction("com.app.THUMBS_DOWN", "") to CustomKind.DISLIKE,
            MediaCustomAction("x", "Dislike") to CustomKind.DISLIKE,
            MediaCustomAction("x", "Remove dislike") to CustomKind.UNDISLIKE,
            MediaCustomAction("toggleShuffle", "") to CustomKind.SHUFFLE,
            MediaCustomAction("x", "Shuffle on") to CustomKind.SHUFFLE,
            MediaCustomAction("REPEAT_MODE", "Repeat all") to CustomKind.REPEAT,
            MediaCustomAction("x", "Loop") to CustomKind.REPEAT,
        )) {
            withClue(action) { GarMediaControls.kindOf(action) shouldBe kind }
        }
        for (action in listOf(
            MediaCustomAction("start_radio", "Start radio"),
            MediaCustomAction("x", "Add to playlist"),
            MediaCustomAction("x", "Playback speed"),
            MediaCustomAction("x", "Sleep timer"),
            MediaCustomAction("skip_30", "Skip 30 seconds"),
            MediaCustomAction("x", "Clicker"),
        )) {
            withClue(action) { GarMediaControls.kindOf(action).shouldBeNull() }
        }
    }

    "pause stops every player that plays" {
        val (outcome, fake) = perform(
            MediaCommand.Pause,
            session("a", appName = "A"),
            session("b", state = PlaybackState.STATE_PAUSED),
            session("c", state = PlaybackState.STATE_BUFFERING),
        )
        outcome shouldBe ControlOutcome.Done(MediaCommand.Pause, "A")
        fake.sent shouldContainExactly listOf(Pair("a", SessionCommand.Pause), Pair("c", SessionCommand.Pause))
        perform(MediaCommand.Pause, session("b", state = PlaybackState.STATE_PAUSED)).first shouldBe
            ControlOutcome.NothingPlaying
    }

    "a session that refuses a command failed" {
        val fake = FakeSessions(listOf(session("a", actions = PlaybackState.ACTION_SEEK_TO, appName = "A")))
        fake.accepts = false
        GarMediaControls.perform(MediaCommand.Restart, fake) shouldBe ControlOutcome.Failed(MediaCommand.Restart, "A")
    }
})
