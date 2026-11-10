package org.stypox.dicio.skills.song

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

private const val KEY = "com.mrsep.musicrecognizer.track_metadata."
private val HAND_OFF: Instant = Instant.parse("2026-10-26T18:00:00Z")

private fun extras(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.associate { (key, value) -> KEY + key to value }
    return { map[it] }
}

private fun posted(sampleTime: Instant?, postTime: Instant = HAND_OFF.plusSeconds(20)) = AudilePosted(
    result = AudileTrack(SongTrack("Song", "Artist"), id = "1", sampleTime = sampleTime),
    postTime = postTime,
    foreground = false,
)

class SongAudileResultTest : StringSpec({
    "an Audile result notification is read from its track extras" {
        AudileNotifications.parse(
            "com.mrsep.musicrecognizer",
            extras(
                "id" to "4f1c",
                "title" to "Bohemian Rhapsody",
                "artist" to "Queen",
                "album" to "A Night at the Opera",
                "release_date" to "1975-10-31",
                "sample_timestamp" to "2026-10-26T18:00:03.250Z",
                "isrc" to "GBUM71029604",
            ),
        ) shouldBe AudileTrack(
            track = SongTrack(
                title = "Bohemian Rhapsody",
                artist = "Queen",
                album = "A Night at the Opera",
                releaseDate = "1975-10-31",
            ),
            id = "4f1c",
            sampleTime = Instant.parse("2026-10-26T18:00:03.250Z"),
        )
    }

    "the debug build's results count, other apps' don't" {
        val song = extras("title" to "Song", "artist" to "Artist")
        AudileNotifications.parse("com.mrsep.musicrecognizer.debug", song)?.track shouldBe
            SongTrack("Song", "Artist")
        AudileNotifications.parse("org.example.scrobbler", song) shouldBe null
    }

    "a notification without a title isn't a result" {
        // e.g. "No matches found", or Audile's ongoing "Tap to recognize"
        AudileNotifications.parse("com.mrsep.musicrecognizer", extras("artist" to "Queen")) shouldBe
            null
        AudileNotifications.parse("com.mrsep.musicrecognizer", extras("title" to "  ")) shouldBe null
    }

    "missing or broken optional extras are left out" {
        AudileNotifications.parse(
            "com.mrsep.musicrecognizer",
            extras("title" to "Song", "sample_timestamp" to "yesterday"),
        ) shouldBe AudileTrack(SongTrack("Song", ""), id = null, sampleTime = null)
    }

    "only a result recorded after the hand-off answers it" {
        AudileNotifications.answers(posted(HAND_OFF.plusSeconds(2)), HAND_OFF) shouldBe true
        // clocks may differ a little
        AudileNotifications.answers(posted(HAND_OFF.minusSeconds(3)), HAND_OFF) shouldBe true
        // an older result whose notification Audile updated
        AudileNotifications.answers(posted(HAND_OFF.minusSeconds(60)), HAND_OFF) shouldBe false
    }

    "without a recording time, the time it was posted counts" {
        AudileNotifications.answers(posted(null, HAND_OFF.plusSeconds(15)), HAND_OFF) shouldBe true
        AudileNotifications.answers(posted(null, HAND_OFF.minusSeconds(600)), HAND_OFF) shouldBe
            false
    }

    "the track link opens Audile's result screen" {
        AudileNotifications.trackUri("4f1c") shouldBe "app://mrsep.musicrecognizer.com/track/4f1c"
        AudileNotifications.trackUri("a b/c") shouldBe "app://mrsep.musicrecognizer.com/track/a%20b%2Fc"
    }

    "GAR waits only when Audile recognizes" {
        AudileWaits.waitsFor(AudileResult.LISTENING_IN_BACKGROUND) shouldBe true
        AudileWaits.waitsFor(AudileResult.OPENED) shouldBe true
        AudileWaits.waitsFor(AudileResult.OPENED_LOCKED) shouldBe true
        AudileWaits.waitsFor(AudileResult.OPENED_APP) shouldBe true
        AudileWaits.waitsFor(AudileResult.BLOCKED_IN_BACKGROUND) shouldBe false
        AudileWaits.waitsFor(AudileResult.FAILED) shouldBe false
    }

    "results on Audile's screen need its foreground notifications, until one was heard" {
        AudileWaits.hints(AudileResult.OPENED, hearsResults = true, foregroundHeard = false) shouldBe
            AudileHints(needsListener = false, foregroundHint = true)
        AudileWaits.hints(AudileResult.OPENED, hearsResults = true, foregroundHeard = true) shouldBe
            AudileHints(needsListener = false, foregroundHint = false)
        // the background shortcut's results go to a channel that is on
        AudileWaits.hints(
            AudileResult.LISTENING_IN_BACKGROUND, hearsResults = true, foregroundHeard = false,
        ) shouldBe AudileHints(needsListener = false, foregroundHint = false)
    }

    "without notification access GAR asks for it and doesn't wait" {
        val hints = AudileWaits.hints(AudileResult.OPENED, hearsResults = false, foregroundHeard = false)
        hints shouldBe AudileHints(needsListener = true, foregroundHint = false)
        AudileWaits.initial(hints) shouldBe AudileCardState.Ended(hints)
        val listening = AudileHints(needsListener = false, foregroundHint = false)
        AudileWaits.initial(listening) shouldBe AudileCardState.Waiting(listening)
    }

    "the wait skips older results and returns the hand-off's" {
        val handOff = Instant.now()
        AudileResults.post(posted(handOff.minusSeconds(120), handOff.minusSeconds(100)))
        val fresh = posted(handOff.plusSeconds(1), handOff.plusSeconds(8))
        coroutineScope {
            val answer = async { AudileResults.await(handOff, Duration.ofSeconds(5)) }
            delay(50)
            AudileResults.post(fresh)
            answer.await() shouldBe fresh
        }
    }

    "a result posted before the wait started still counts" {
        val handOff = Instant.now()
        val early = posted(handOff.plusSeconds(1))
        AudileResults.post(early)
        AudileResults.await(handOff, Duration.ofSeconds(5)) shouldBe early
    }

    "no answer within the timeout ends the wait" {
        val handOff = Instant.now().plusSeconds(3600)
        AudileResults.await(handOff, Duration.ofMillis(100)) shouldBe null
    }
})
