package org.stypox.dicio.gar.media

import android.media.session.PlaybackState

/** The phone's media sessions as a test sets them; records what GAR sends. */
class FakeSessions(
    var list: List<MediaSessionView>? = listOf(),
    private val now: Long = 100_000,
    /** Changes [list] after a command, as a player would. */
    private val onSend: FakeSessions.(String, SessionCommand) -> Unit = { _, _ -> },
) : MediaSessions {
    val sent = mutableListOf<Pair<String, SessionCommand>>()
    var accepts = true

    override fun sessions(): List<MediaSessionView>? = list

    override fun send(packageName: String, command: SessionCommand): Boolean {
        sent += Pair(packageName, command)
        val there = list.orEmpty().any { it.packageName == packageName }
        if (there && accepts) {
            onSend(packageName, command)
        }
        return there && accepts
    }

    override fun nowElapsed(): Long = now
}

/** The players on the phone as a test sets them; records the play-from-search activities started. */
class FakePlayers(
    var handlers: List<String> = listOf(),
    var default: String? = null,
    var apps: List<LauncherApp> = listOf(),
    var starts: Boolean = true,
) : MediaPlayers {
    val started = mutableListOf<Pair<String?, MediaPlayRequest>>()

    override fun searchHandlers(): List<String> = handlers
    override fun defaultHandler(): String? = default
    override fun launcherApps(): List<LauncherApp> = apps
    override fun label(packageName: String): String? =
        apps.firstOrNull { it.packageName == packageName }?.label

    override fun startSearch(packageName: String?, request: MediaPlayRequest): Boolean {
        started += Pair(packageName, request)
        return starts
    }
}

fun session(
    packageName: String,
    state: Int = PlaybackState.STATE_PLAYING,
    actions: Long = 0,
    appName: String? = null,
    positionMs: Long = 0,
    updateTimeElapsed: Long = 0,
    durationMs: Long? = null,
    title: String? = null,
    ratingType: Int = 0,
    customActions: List<MediaCustomAction> = listOf(),
) = MediaSessionView(
    packageName = packageName,
    appName = appName,
    state = state,
    actions = actions,
    positionMs = positionMs,
    updateTimeElapsed = updateTimeElapsed,
    durationMs = durationMs,
    title = title,
    ratingType = ratingType,
    customActions = customActions,
)

const val SPOTIFY = "com.spotify.music"
const val YT_MUSIC = "com.google.android.apps.youtube.music"
const val ANTENNAPOD = "de.danoeh.antennapod"
