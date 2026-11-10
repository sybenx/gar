package org.stypox.dicio.gar.media

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.PlaybackState
import android.provider.MediaStore
import android.util.Log
import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.Resolution

/**
 * What the words of a play request say it is, for `MediaStore.EXTRA_MEDIA_FOCUS`. Android defines
 * no podcast focus: a podcast goes as an unstructured search, with its words kept.
 */
enum class MediaFocus { ANY, ARTIST, ALBUM, SONG, PLAYLIST, PODCAST }

/**
 * Something to play by name.
 *
 * @param said what to play as the user said it ("my workout playlist"), for the answer
 * @param query `SearchManager.QUERY`: [said] without the words that only gave the focus
 * ("workout playlist"); empty for "anything" ("play spotify", "play some music on spotify")
 * @param app the app to play it in: as said after "on" in a sentence, or the launcher label GAR
 * resolved for the model's call; null for the default player
 * @param whole the request read without an app, for when [app] names no app ("play dancing on
 * my own"); null when [app] wasn't split off the words
 */
data class MediaPlayRequest(
    val said: String,
    val query: String,
    val focus: MediaFocus = MediaFocus.ANY,
    val artist: String? = null,
    val album: String? = null,
    val title: String? = null,
    val playlist: String? = null,
    val app: String? = null,
    val whole: MediaPlayRequest? = null,
)

/** An app the launcher shows. */
data class LauncherApp(val label: String, val packageName: String)

/** The players on the phone, besides their media sessions ([MediaSessions]). */
interface MediaPlayers {
    /** The apps whose activity takes `INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` (not GAR). */
    fun searchHandlers(): List<String>

    /** The handler that Android starts without asking (the user tapped "Always"), if any. */
    fun defaultHandler(): String?

    fun launcherApps(): List<LauncherApp>

    fun label(packageName: String): String?

    /**
     * Starts the play-from-search activity of [packageName], or lets Android ask which app when
     * it is null; false if it couldn't start.
     */
    fun startSearch(packageName: String?, request: MediaPlayRequest): Boolean
}

/** How GAR gets a player to play a request. */
sealed interface PlayRoute {
    /** Through the player's media session (`TransportControls.playFromSearch`). */
    data class Session(val packageName: String) : PlayRoute

    /** Through the player's play-from-search activity. */
    data class Intent(val packageName: String) : PlayRoute

    /** Several players and no default: Android asks which one. */
    data object Chooser : PlayRoute

    /**
     * The app has neither a play-from-search activity nor a session that plays by name;
     * [noAccess]: GAR couldn't see its session.
     */
    data class CantPlayByName(val packageName: String, val noAccess: Boolean) : PlayRoute

    data object NoPlayer : PlayRoute
}

/** What came of a play request. */
sealed interface PlayOutcome {
    /** [app] was asked to play [request] and took it (or its activity started). */
    data class Playing(val request: MediaPlayRequest, val app: String) : PlayOutcome

    /** [app]'s session was asked, but it didn't start playing, and it has no activity for it. */
    data class AskedApp(val request: MediaPlayRequest, val app: String) : PlayOutcome

    /** Android asks which app plays [request]. */
    data class Choosing(val request: MediaPlayRequest) : PlayOutcome

    /** [app], a player GAR knows by name, isn't installed. */
    data class NotInstalled(val app: String) : PlayOutcome

    data class CantPlayByName(val app: String, val noAccess: Boolean) : PlayOutcome

    data object NoPlayer : PlayOutcome

    /** The player's activity didn't start. */
    data class Failed(val app: String?) : PlayOutcome
}

/**
 * Play by name (gar/PARITY.md, batch 4): "play taylor swift on spotify". The app named, else
 * Android's default player for play-from-search, else the player that plays or played last,
 * else the only player, else Android asks. A player whose media session plays from search gets
 * the request there, without its screen; when that session doesn't start playing, or the app has
 * no session, its `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` activity does.
 *
 * @param pause waits between the checks of whether a session started playing
 */
class GarMediaPlay(
    private val sessions: MediaSessions,
    private val players: MediaPlayers,
    private val pause: suspend (Long) -> Unit,
) {
    suspend fun play(request: MediaPlayRequest): PlayOutcome {
        var wanted = request
        var app: LauncherApp? = null
        val launcher by lazy { players.launcherApps() }
        if (request.app != null) {
            app = resolveApp(request.app, launcher)
            if (app == null) {
                // "play dancing on my own": "my own" is no app, but "spotify" is a player
                if (isKnownPlayer(request.app)) {
                    return PlayOutcome.NotInstalled(request.app)
                }
                wanted = request.whole ?: request.copy(app = null)
            }
        }

        val all = sessions.sessions()
        val handlers = players.searchHandlers()
        if (app == null && wanted.query.isNotEmpty()) {
            // "play spotify": play anything in that player
            val named = launcher.firstOrNull { NameMatcher.similarity(wanted.query, it.label) >= SAME_NAME }
            if (named != null && (named.packageName in handlers || all.orEmpty().any {
                    it.packageName == named.packageName &&
                        it.supports(PlaybackState.ACTION_PLAY_FROM_SEARCH)
                })
            ) {
                app = named
                wanted = MediaPlayRequest(said = "", query = "")
            }
        }

        return when (val route = route(app?.packageName, all, handlers, players.defaultHandler())) {
            is PlayRoute.Session -> {
                val before = all.orEmpty().firstOrNull { it.packageName == route.packageName }
                val name = labelOf(route.packageName, app, all)
                if (sessions.send(
                        route.packageName,
                        SessionCommand.PlayFromSearch(wanted.query, extras(wanted)),
                    ) && awaitStart(route.packageName, before)
                ) {
                    PlayOutcome.Playing(wanted, name)
                } else if (route.packageName in handlers) {
                    // the session didn't take it: the activity does
                    startActivity(route.packageName, wanted, name)
                } else {
                    PlayOutcome.AskedApp(wanted, name)
                }
            }
            is PlayRoute.Intent ->
                startActivity(route.packageName, wanted, labelOf(route.packageName, app, all))
            PlayRoute.Chooser -> if (players.startSearch(null, wanted)) {
                PlayOutcome.Choosing(wanted)
            } else {
                PlayOutcome.Failed(null)
            }
            is PlayRoute.CantPlayByName ->
                PlayOutcome.CantPlayByName(labelOf(route.packageName, app, all), route.noAccess)
            PlayRoute.NoPlayer -> PlayOutcome.NoPlayer
        }
    }

    private fun startActivity(packageName: String, request: MediaPlayRequest, name: String) =
        if (players.startSearch(packageName, request)) {
            PlayOutcome.Playing(request, name)
        } else {
            PlayOutcome.Failed(name)
        }

    private fun labelOf(packageName: String, app: LauncherApp?, all: List<MediaSessionView>?) =
        app?.takeIf { it.packageName == packageName }?.label
            ?: all.orEmpty().firstOrNull { it.packageName == packageName }?.appName
            ?: players.label(packageName)
            ?: packageName

    /** Whether the session of [packageName] starts playing within [START_WAIT_MILLIS]. */
    private suspend fun awaitStart(packageName: String, before: MediaSessionView?): Boolean {
        var waited = 0L
        while (waited < START_WAIT_MILLIS) {
            pause(START_CHECK_MILLIS)
            waited += START_CHECK_MILLIS
            val after = sessions.sessions()?.firstOrNull { it.packageName == packageName }
            if (started(before, after)) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "GarMediaPlay"

        /** A query this close to an app's label names that app ("play spotify"). */
        private const val SAME_NAME = 0.95

        const val START_WAIT_MILLIS = 3000L
        const val START_CHECK_MILLIS = 250L

        /** `EXTRA_MEDIA_FOCUS` of a search for anything, or for words of any kind. */
        const val FOCUS_ANY = "vnd.android.cursor.item/*"

        /**
         * Players GAR knows by name, so that "play jazz on spotify" without Spotify says that it
         * isn't installed, while "play dancing on my own" plays the song. Lowercase, as the
         * normalized input has them.
         */
        val KNOWN_PLAYERS = setOf(
            "spotify", "youtube", "youtube music", "yt music", "pandora", "deezer", "tidal",
            "soundcloud", "sound cloud", "apple music", "amazon music", "audible", "pocket casts",
            "pocketcasts", "antennapod", "antenna pod", "podcast addict", "google podcasts",
            "castbox", "newpipe", "new pipe", "vlc", "plex", "plexamp", "jellyfin", "tunein",
            "tune in", "iheartradio", "iheart radio", "iheart", "siriusxm", "sirius xm",
            "audiomack", "napster", "qobuz", "bandcamp", "poweramp", "musicolet", "auxio",
            "symfonium", "retro music", "vinyl", "oto music", "gramophone", "metro", "foobar",
            "foobar2000", "librivox", "libby", "blackplayer", "black player", "nextcloud music",
            "funkwhale", "navidrome", "subsonic", "ultrasonic", "tempo", "innertune",
            "vimusic", "vi music", "harmony music", "metrolist", "simpmusic", "spotube",
        )

        fun isKnownPlayer(said: String): Boolean {
            val name = said.lowercase().split(Regex("\\s+"))
                .filter { it.isNotEmpty() && it !in setOf("the", "my", "app", "application") }
                .joinToString(" ")
            return name in KNOWN_PLAYERS
        }

        /** The launcher app [said] names: the closest label, also for STT splits ("spot if i"). */
        fun resolveApp(said: String, apps: List<LauncherApp>): LauncherApp? =
            when (val resolution = NameMatcher.match(said, apps, { it.label })) {
                is Resolution.Resolved -> resolution.value
                // the best of several close labels, as NameMatcher ranks them
                is Resolution.Ambiguous -> resolution.options.firstOrNull()
                is Resolution.Unresolvable -> null
            }

        /** See [GarMediaPlay]: which player gets the request, and how. */
        fun route(
            app: String?,
            sessions: List<MediaSessionView>?,
            handlers: List<String>,
            defaultHandler: String?,
        ): PlayRoute {
            val bySession = sessions.orEmpty()
                .filter { it.supports(PlaybackState.ACTION_PLAY_FROM_SEARCH) }
                .map { it.packageName }
            fun routeTo(packageName: String): PlayRoute? = when (packageName) {
                in bySession -> PlayRoute.Session(packageName)
                in handlers -> PlayRoute.Intent(packageName)
                else -> null
            }

            if (app != null) {
                return routeTo(app) ?: PlayRoute.CantPlayByName(app, noAccess = sessions == null)
            }
            defaultHandler?.let(::routeTo)?.let { return it }
            sessions.orEmpty().firstNotNullOfOrNull { routeTo(it.packageName) }?.let { return it }
            return when (handlers.size) {
                0 -> PlayRoute.NoPlayer
                1 -> PlayRoute.Intent(handlers[0])
                else -> PlayRoute.Chooser
            }
        }

        /**
         * Whether a session asked to play started: it plays (or buffers) now and didn't before,
         * or plays another title, or went back to near the start of the same one.
         */
        fun started(before: MediaSessionView?, after: MediaSessionView?): Boolean {
            if (after == null || !after.isStarting) {
                return false
            }
            if (before == null || !before.isStarting) {
                return true
            }
            return after.title != before.title ||
                after.positionMs + RESTART_MARGIN_MILLIS < before.positionAt(after.updateTimeElapsed)
        }

        private const val RESTART_MARGIN_MILLIS = 2000L

        /**
         * The play-from-search extras of [request]: the focus, `SearchManager.QUERY` and the
         * focus's own fields, as "Play music based on a search query" in Android's common
         * intents describes them.
         */
        // the playlist constants are deprecated with MediaStore's own playlists (Android 12),
        // not as play-from-search fields, which players still read
        @Suppress("DEPRECATION")
        fun extras(request: MediaPlayRequest): Map<String, String> {
            val focus = when (request.focus) {
                MediaFocus.ARTIST -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
                MediaFocus.ALBUM -> MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
                MediaFocus.SONG -> MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
                MediaFocus.PLAYLIST -> MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE
                MediaFocus.ANY, MediaFocus.PODCAST -> FOCUS_ANY
            }
            return listOfNotNull(
                MediaStore.EXTRA_MEDIA_FOCUS to focus,
                SearchManager.QUERY to request.query,
                request.artist?.let { MediaStore.EXTRA_MEDIA_ARTIST to it },
                request.album?.let { MediaStore.EXTRA_MEDIA_ALBUM to it },
                request.title?.let { MediaStore.EXTRA_MEDIA_TITLE to it },
                request.playlist?.let { MediaStore.EXTRA_MEDIA_PLAYLIST to it },
            ).toMap()
        }
    }
}

/** The players on the phone, through the package manager and their activities. */
class AndroidMediaPlayers(private val context: Context) : MediaPlayers {
    private val packageManager get() = context.packageManager

    private fun searchIntent() = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)

    override fun searchHandlers(): List<String> = try {
        @SuppressLint("QueryPermissionsNeeded") // GAR holds QUERY_ALL_PACKAGES
        val handlers = packageManager.queryIntentActivities(
            searchIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        handlers.map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()
    } catch (e: RuntimeException) {
        Log.w(TAG, "Can't list the players", e)
        listOf()
    }

    override fun defaultHandler(): String? = try {
        // the system's chooser when the user picked no default, which isn't among the handlers
        packageManager.resolveActivity(searchIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it in searchHandlers() }
    } catch (e: RuntimeException) {
        null
    }

    override fun launcherApps(): List<LauncherApp> = try {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        @SuppressLint("QueryPermissionsNeeded") // GAR holds QUERY_ALL_PACKAGES
        val activities = packageManager.queryIntentActivities(intent, 0)
        activities.map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { LauncherApp(packageManager.getApplicationLabel(it).toString(), it.packageName) }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Can't list the launcher apps", e)
        listOf()
    }

    override fun label(packageName: String): String? = GarMediaSessions.appName(context, packageName)

    override fun startSearch(packageName: String?, request: MediaPlayRequest): Boolean {
        val intent = searchIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        packageName?.let { intent.setPackage(it) }
        GarMediaPlay.extras(request).forEach { (key, value) -> intent.putExtra(key, value) }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No play-from-search activity in $packageName", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't start the play-from-search activity of $packageName", e)
            false
        }
    }

    private companion object {
        const val TAG = "AndroidMediaPlayers"
    }
}
