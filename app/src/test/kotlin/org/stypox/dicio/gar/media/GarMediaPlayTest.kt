package org.stypox.dicio.gar.media

import android.media.session.PlaybackState
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private val TAYLOR = MediaPlayRequest(said = "taylor swift", query = "taylor swift", app = "spotify")

private val APPS = listOf(
    LauncherApp("Spotify", SPOTIFY),
    LauncherApp("YouTube Music", YT_MUSIC),
    LauncherApp("YouTube", "com.google.android.youtube"),
    LauncherApp("AntennaPod", ANTENNAPOD),
    LauncherApp("Signal", "org.thoughtcrime.securesms"),
)

private val PLAYS_BY_SESSION = PlaybackState.ACTION_PLAY_FROM_SEARCH

/** A player that starts playing what it is asked to play, under a new title. */
private val STARTS: FakeSessions.(String, SessionCommand) -> Unit = { packageName, command ->
    if (command is SessionCommand.PlayFromSearch) {
        list = list.orEmpty().map {
            if (it.packageName == packageName) it.copy(state = PlaybackState.STATE_PLAYING, title = command.query)
            else it
        }
    }
}

private suspend fun play(
    request: MediaPlayRequest,
    sessions: FakeSessions = FakeSessions(),
    players: FakePlayers = FakePlayers(apps = APPS),
): PlayOutcome = GarMediaPlay(sessions, players) {}.play(request)

class GarMediaPlayTest : StringSpec({
    "the app named plays it, through its session or its activity" {
        val bySession = listOf(session(SPOTIFY, actions = PLAYS_BY_SESSION))
        GarMediaPlay.route(SPOTIFY, bySession, listOf(SPOTIFY), null) shouldBe PlayRoute.Session(SPOTIFY)
        GarMediaPlay.route(SPOTIFY, listOf(session(SPOTIFY)), listOf(SPOTIFY), null) shouldBe PlayRoute.Intent(SPOTIFY)
        GarMediaPlay.route(SPOTIFY, null, listOf(SPOTIFY), YT_MUSIC) shouldBe PlayRoute.Intent(SPOTIFY)
        GarMediaPlay.route(ANTENNAPOD, listOf(), listOf(SPOTIFY), null) shouldBe
            PlayRoute.CantPlayByName(ANTENNAPOD, noAccess = false)
        GarMediaPlay.route(ANTENNAPOD, null, listOf(SPOTIFY), null) shouldBe
            PlayRoute.CantPlayByName(ANTENNAPOD, noAccess = true)
    }

    "without an app: the default player, the one that plays or played last, the only one, or Android asks" {
        val handlers = listOf(SPOTIFY, YT_MUSIC)
        GarMediaPlay.route(null, listOf(session(SPOTIFY)), handlers, YT_MUSIC) shouldBe PlayRoute.Intent(YT_MUSIC)
        GarMediaPlay.route(null, listOf(session(ANTENNAPOD), session(SPOTIFY)), handlers, null) shouldBe
            PlayRoute.Intent(SPOTIFY)
        GarMediaPlay.route(null, listOf(session(ANTENNAPOD, actions = PLAYS_BY_SESSION)), handlers, null) shouldBe
            PlayRoute.Session(ANTENNAPOD)
        GarMediaPlay.route(null, null, listOf(SPOTIFY), null) shouldBe PlayRoute.Intent(SPOTIFY)
        GarMediaPlay.route(null, listOf(), handlers, null) shouldBe PlayRoute.Chooser
        GarMediaPlay.route(null, listOf(session(ANTENNAPOD)), listOf(), null) shouldBe PlayRoute.NoPlayer
    }

    "the extras say the focus and its fields" {
        GarMediaPlay.extras(MediaPlayRequest(said = "lofi", query = "lofi")) shouldBe mapOf(
            "android.intent.extra.focus" to "vnd.android.cursor.item/*", "query" to "lofi",
        )
        GarMediaPlay.extras(
            MediaPlayRequest(said = "", query = "the beatles", focus = MediaFocus.ARTIST, artist = "the beatles")
        ) shouldBe mapOf(
            "android.intent.extra.focus" to "vnd.android.cursor.item/artist", "query" to "the beatles",
            "android.intent.extra.artist" to "the beatles",
        )
        GarMediaPlay.extras(
            MediaPlayRequest(said = "", query = "thriller", focus = MediaFocus.ALBUM, album = "thriller")
        )["android.intent.extra.focus"] shouldBe "vnd.android.cursor.item/album"
        GarMediaPlay.extras(
            MediaPlayRequest(said = "", query = "x", focus = MediaFocus.SONG, title = "x", artist = "y")
        ) shouldBe mapOf(
            "android.intent.extra.focus" to "vnd.android.cursor.item/audio", "query" to "x",
            "android.intent.extra.artist" to "y", "android.intent.extra.title" to "x",
        )
        GarMediaPlay.extras(
            MediaPlayRequest(said = "", query = "workout playlist", focus = MediaFocus.PLAYLIST, playlist = "workout")
        ) shouldBe mapOf(
            "android.intent.extra.focus" to "vnd.android.cursor.item/playlist", "query" to "workout playlist",
            "android.intent.extra.playlist" to "workout",
        )
        // Android has no podcast focus
        GarMediaPlay.extras(MediaPlayRequest(said = "", query = "the daily", focus = MediaFocus.PODCAST))[
            "android.intent.extra.focus"] shouldBe "vnd.android.cursor.item/*"
    }

    "a session started when it plays now, plays another title, or went back near the start" {
        val paused = session("a", state = PlaybackState.STATE_PAUSED, title = "old")
        val playing = session("a", title = "old", positionMs = 60_000, updateTimeElapsed = 1_000)
        GarMediaPlay.started(paused, playing.copy(updateTimeElapsed = 2_000)).shouldBeTrue()
        GarMediaPlay.started(null, session("a", state = PlaybackState.STATE_BUFFERING)).shouldBeTrue()
        GarMediaPlay.started(playing, playing.copy(title = "new", updateTimeElapsed = 2_000)).shouldBeTrue()
        GarMediaPlay.started(playing, playing.copy(positionMs = 0, updateTimeElapsed = 2_000)).shouldBeTrue()
        // the same title, a second further on: it ignored the request
        GarMediaPlay.started(playing, playing.copy(positionMs = 61_000, updateTimeElapsed = 2_000)).shouldBeFalse()
        GarMediaPlay.started(paused, paused).shouldBeFalse()
        GarMediaPlay.started(paused, null).shouldBeFalse()
    }

    "a named app with a session that plays from search gets the request there, without its screen" {
        val sessions = FakeSessions(
            listOf(session(SPOTIFY, state = PlaybackState.STATE_PAUSED, actions = PLAYS_BY_SESSION)),
            onSend = STARTS,
        )
        val players = FakePlayers(handlers = listOf(SPOTIFY), apps = APPS)
        play(TAYLOR, sessions, players) shouldBe PlayOutcome.Playing(TAYLOR, "Spotify")
        sessions.sent shouldContainExactly listOf(Pair(
            SPOTIFY,
            SessionCommand.PlayFromSearch(
                "taylor swift",
                mapOf("android.intent.extra.focus" to "vnd.android.cursor.item/*", "query" to "taylor swift"),
            ),
        ))
        players.started.shouldBeEmpty()
    }

    "a session that doesn't start playing hands the request to the app's activity" {
        val sessions = FakeSessions(listOf(session(SPOTIFY, state = PlaybackState.STATE_PAUSED, actions = PLAYS_BY_SESSION)))
        val players = FakePlayers(handlers = listOf(SPOTIFY), apps = APPS)
        play(TAYLOR, sessions, players) shouldBe PlayOutcome.Playing(TAYLOR, "Spotify")
        players.started shouldContainExactly listOf(Pair(SPOTIFY, TAYLOR))

        // no activity: GAR says it asked
        val noActivity = FakePlayers(apps = APPS)
        play(TAYLOR, FakeSessions(listOf(session(SPOTIFY, state = PlaybackState.STATE_PAUSED,
            actions = PLAYS_BY_SESSION))), noActivity) shouldBe PlayOutcome.AskedApp(TAYLOR, "Spotify")
    }

    "the app's name is matched like GAR's other app names" {
        val players = FakePlayers(handlers = listOf(SPOTIFY, YT_MUSIC), apps = APPS)
        val request = TAYLOR.copy(app = "spot if i")
        play(request, players = players) shouldBe PlayOutcome.Playing(request, "Spotify")
        players.started shouldContainExactly listOf(Pair(SPOTIFY, request))
        GarMediaPlay.resolveApp("you tube", APPS)?.label shouldBe "YouTube"
        GarMediaPlay.resolveApp("youtube music", APPS)?.label shouldBe "YouTube Music"
    }

    "a player GAR knows that isn't installed says so; other words after on are part of the title" {
        val players = FakePlayers(handlers = listOf(YT_MUSIC), apps = APPS.filter { it.packageName != SPOTIFY })
        play(TAYLOR, players = players) shouldBe PlayOutcome.NotInstalled("spotify")
        players.started.shouldBeEmpty()

        val whole = MediaPlayRequest(said = "dancing on my own", query = "dancing on my own")
        val song = MediaPlayRequest(said = "dancing", query = "dancing", app = "own", whole = whole)
        play(song, players = players) shouldBe PlayOutcome.Playing(whole, "YouTube Music")
        players.started shouldContainExactly listOf(Pair(YT_MUSIC, whole))
    }

    "a player's name alone plays anything in it" {
        val players = FakePlayers(handlers = listOf(SPOTIFY, YT_MUSIC), apps = APPS)
        val anything = MediaPlayRequest(said = "", query = "")
        play(MediaPlayRequest(said = "spotify", query = "spotify"), players = players) shouldBe
            PlayOutcome.Playing(anything, "Spotify")
        players.started shouldContainExactly listOf(Pair(SPOTIFY, anything))
        // an app that plays nothing is a title ("signal")
        val signal = MediaPlayRequest(said = "signal", query = "signal")
        play(signal, players = FakePlayers(handlers = listOf(SPOTIFY), apps = APPS)) shouldBe
            PlayOutcome.Playing(signal, "Spotify")
    }

    "several players and no default let Android ask; none can't play" {
        val lofi = MediaPlayRequest(said = "lofi", query = "lofi")
        val players = FakePlayers(handlers = listOf(SPOTIFY, YT_MUSIC), apps = APPS)
        play(lofi, players = players) shouldBe PlayOutcome.Choosing(lofi)
        players.started shouldContainExactly listOf(Pair(null, lofi))
        play(lofi, players = FakePlayers(apps = APPS)) shouldBe PlayOutcome.NoPlayer
    }

    "an app that can't play by name says so, and whether notification access might help" {
        val podcasts = MediaPlayRequest(said = "the daily", query = "the daily", app = "antennapod")
        play(podcasts, FakeSessions(null)) shouldBe PlayOutcome.CantPlayByName("AntennaPod", noAccess = true)
        play(podcasts, FakeSessions(listOf(session(ANTENNAPOD)))) shouldBe
            PlayOutcome.CantPlayByName("AntennaPod", noAccess = false)
    }

    "an activity that doesn't start failed" {
        val players = FakePlayers(handlers = listOf(SPOTIFY), apps = APPS, starts = false)
        play(TAYLOR, players = players) shouldBe PlayOutcome.Failed("Spotify")
    }

    "only known players are known" {
        for (name in listOf("spotify", "YouTube Music", "the spotify app", "pocket casts")) {
            withClue(name) { GarMediaPlay.isKnownPlayer(name).shouldBeTrue() }
        }
        for (name in listOf("my own", "the radio", "repeat", "spot")) {
            withClue(name) { GarMediaPlay.isKnownPlayer(name).shouldBeFalse() }
        }
    }
})
