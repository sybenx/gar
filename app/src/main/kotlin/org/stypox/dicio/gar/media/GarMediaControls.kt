package org.stypox.dicio.gar.media

import android.media.Rating
import android.media.session.PlaybackState

/** What the user asked of the playing media, beyond Dicio's play, pause, next and previous. */
sealed interface MediaCommand {
    data class Shuffle(val on: Boolean) : MediaCommand

    data class Repeat(val mode: RepeatMode) : MediaCommand

    /** Moves the position by [millis]: forward when positive, back when negative. */
    data class Seek(val millis: Long) : MediaCommand

    /** Starts the track over. */
    data object Restart : MediaCommand

    /** Thumbs up ([like]) or down. */
    data class Rate(val like: Boolean) : MediaCommand

    /** "Stop" while something plays: pauses it, so that "play" or "resume" continue. */
    data object Pause : MediaCommand
}

enum class RepeatMode { ONE, ALL, OFF }

/** What came of a [MediaCommand]. */
sealed interface ControlOutcome {
    /** GAR has no notification access, so it can't see the sessions. */
    data object NoAccess : ControlOutcome

    data object NothingPlaying : ControlOutcome

    /** [app] (the session's label, or its package) did it. */
    data class Done(val command: MediaCommand, val app: String) : ControlOutcome

    /**
     * [app] has only a button that switches shuffle or repeat, and GAR pressed it: it can't tell
     * the state that came of it.
     */
    data class Toggled(val command: MediaCommand, val app: String) : ControlOutcome

    /** The track is already liked (or disliked): GAR pressed nothing. */
    data class Already(val command: MediaCommand, val app: String) : ControlOutcome

    /** Seeking that far would go past the end of the track (or before its start). */
    data class OutOfTrack(val command: MediaCommand, val app: String) : ControlOutcome

    /** [app] offers nothing for [command]. */
    data class Unsupported(val command: MediaCommand, val app: String) : ControlOutcome

    /** The session didn't take the command (it went away, or refused it). */
    data class Failed(val command: MediaCommand, val app: String) : ControlOutcome
}

/**
 * The controls beyond play, pause, next and previous (gar/PARITY.md, batch 4): shuffle, repeat,
 * seek, restart, like, through the media session of the app that plays. Standard actions first
 * (androidx.media's set-shuffle and set-repeat custom actions, seekTo, setRating), then the
 * session's own custom actions (a like or a shuffle button), recognized by their names.
 */
object GarMediaControls {

    /** What GAR does for [command] on [session], before it sends anything. */
    sealed interface Plan {
        data class Send(val command: SessionCommand, val toggles: Boolean = false) : Plan
        data class Outcome(val outcome: (String) -> ControlOutcome) : Plan
    }

    /**
     * The session a command is for: the first that plays, else the one Android ranks first (a
     * paused player still takes shuffle or a like).
     */
    fun target(sessions: List<MediaSessionView>): MediaSessionView? =
        sessions.firstOrNull { it.isPlaying } ?: sessions.firstOrNull()

    fun perform(command: MediaCommand, sessions: MediaSessions): ControlOutcome {
        val all = sessions.sessions() ?: return ControlOutcome.NoAccess
        val session = target(all) ?: return ControlOutcome.NothingPlaying
        val app = session.appName ?: session.packageName
        if (command == MediaCommand.Pause) {
            // every player that plays, as the media key would stop the one that last played
            val playing = all.filter { it.isStarting }
            if (playing.isEmpty()) {
                return ControlOutcome.NothingPlaying
            }
            val sent = playing.map { sessions.send(it.packageName, SessionCommand.Pause) }
            return if (sent.any { it }) ControlOutcome.Done(command, app)
            else ControlOutcome.Failed(command, app)
        }
        return when (val plan = plan(command, session, sessions.nowElapsed())) {
            is Plan.Outcome -> plan.outcome(app)
            is Plan.Send -> when {
                !sessions.send(session.packageName, plan.command) ->
                    ControlOutcome.Failed(command, app)
                plan.toggles -> ControlOutcome.Toggled(command, app)
                else -> ControlOutcome.Done(command, app)
            }
        }
    }

    /** How [command] is done on [session] at [nowElapsed] (not for [MediaCommand.Pause]). */
    fun plan(command: MediaCommand, session: MediaSessionView, nowElapsed: Long): Plan {
        val unsupported = Plan.Outcome { ControlOutcome.Unsupported(command, it) }
        return when (command) {
            is MediaCommand.Shuffle -> when {
                session.supports(GarMediaSessions.ACTION_SET_SHUFFLE_MODE) -> Plan.Send(
                    SessionCommand.CustomAction(
                        GarMediaSessions.CUSTOM_SET_SHUFFLE_MODE,
                        mapOf(
                            GarMediaSessions.ARGUMENT_SHUFFLE_MODE to if (command.on) {
                                GarMediaSessions.SHUFFLE_MODE_ALL
                            } else {
                                GarMediaSessions.SHUFFLE_MODE_NONE
                            }
                        ),
                    )
                )
                else -> customPlan(session, CustomKind.SHUFFLE, toggles = true) ?: unsupported
            }

            is MediaCommand.Repeat -> when {
                session.supports(GarMediaSessions.ACTION_SET_REPEAT_MODE) -> Plan.Send(
                    SessionCommand.CustomAction(
                        GarMediaSessions.CUSTOM_SET_REPEAT_MODE,
                        mapOf(
                            GarMediaSessions.ARGUMENT_REPEAT_MODE to when (command.mode) {
                                RepeatMode.ONE -> GarMediaSessions.REPEAT_MODE_ONE
                                RepeatMode.ALL -> GarMediaSessions.REPEAT_MODE_ALL
                                RepeatMode.OFF -> GarMediaSessions.REPEAT_MODE_NONE
                            }
                        ),
                    )
                )
                else -> customPlan(session, CustomKind.REPEAT, toggles = true) ?: unsupported
            }

            is MediaCommand.Seek -> {
                if (!session.supports(PlaybackState.ACTION_SEEK_TO)) {
                    return unsupported
                }
                val target = session.positionAt(nowElapsed) + command.millis
                val duration = session.durationMs
                when {
                    target < 0 && command.millis < 0 ->
                        // "go back a minute" ten seconds in: the start, as players do
                        Plan.Send(SessionCommand.SeekTo(0))
                    duration != null && target >= duration ->
                        Plan.Outcome { ControlOutcome.OutOfTrack(command, it) }
                    else -> Plan.Send(SessionCommand.SeekTo(target.coerceAtLeast(0)))
                }
            }

            MediaCommand.Restart -> if (session.supports(PlaybackState.ACTION_SEEK_TO)) {
                Plan.Send(SessionCommand.SeekTo(0))
            } else {
                unsupported
            }

            is MediaCommand.Rate -> ratePlan(command, session) ?: unsupported

            MediaCommand.Pause -> Plan.Send(SessionCommand.Pause)
        }
    }

    /**
     * A like through the session's rating (a heart or thumbs), else through a like or dislike
     * button among its custom actions; a button that undoes the like means it is liked already.
     */
    private fun ratePlan(command: MediaCommand.Rate, session: MediaSessionView): Plan? {
        val rates = session.supports(PlaybackState.ACTION_SET_RATING)
        if (rates && session.ratingType == Rating.RATING_THUMB_UP_DOWN) {
            return Plan.Send(SessionCommand.SetRating(Rating.RATING_THUMB_UP_DOWN, command.like))
        }
        if (rates && session.ratingType == Rating.RATING_HEART && command.like) {
            return Plan.Send(SessionCommand.SetRating(Rating.RATING_HEART, true))
        }
        val (doKind, undoKind) = if (command.like) {
            Pair(CustomKind.LIKE, CustomKind.UNLIKE)
        } else {
            Pair(CustomKind.DISLIKE, CustomKind.UNDISLIKE)
        }
        customPlan(session, doKind, toggles = false)?.let { return it }
        if (session.customActions.any { kindOf(it) == undoKind }) {
            return Plan.Outcome { ControlOutcome.Already(command, it) }
        }
        return null
    }

    private fun customPlan(session: MediaSessionView, kind: CustomKind, toggles: Boolean): Plan? =
        session.customActions.firstOrNull { kindOf(it) == kind }
            ?.let { Plan.Send(SessionCommand.CustomAction(it.action), toggles) }

    /** What a session's custom action does, by its id and its (shown) name. */
    enum class CustomKind { SHUFFLE, REPEAT, LIKE, UNLIKE, DISLIKE, UNDISLIKE }

    /**
     * The kind of [action], from the words of its id ("com.app.ACTION_THUMBS_UP", "toggleShuffle")
     * and its name ("Add to Liked Songs", "Remove from favorites"), or null for other buttons
     * ("Start radio", "Add to playlist"). Undoing comes first: "Dislike" and "Unlike" contain
     * "like".
     */
    fun kindOf(action: MediaCustomAction): CustomKind? {
        val text = " " + words(action.action + " " + action.name).joinToString(" ") + " "
        fun has(vararg phrases: String) = phrases.any { text.contains(" $it ") }
        return when {
            has("shuffle", "shuffled", "shuffling") -> CustomKind.SHUFFLE
            has("repeat", "repeating", "loop", "looping") -> CustomKind.REPEAT
            has("remove dislike", "undislike", "undo dislike", "remove thumbs down",
                "remove thumb down", "undo thumbs down") -> CustomKind.UNDISLIKE
            has("dislike", "disliked", "thumbs down", "thumb down", "thumbsdown", "thumbdown") ->
                CustomKind.DISLIKE
            // before "liked" below, which names a liked track's undo button
            has("add to liked", "save to liked", "add to favorites", "add to favourites") ->
                CustomKind.LIKE
            has("unlike", "remove like", "undo like", "remove from liked", "remove from favorites",
                "remove from favourites", "remove from favorite", "remove from favourite",
                "remove from library", "remove from collection", "remove from your library",
                "unfavorite", "unfavourite", "unheart", "unlove", "remove thumbs up",
                "remove thumb up", "undo thumbs up", "liked") -> CustomKind.UNLIKE
            has("playlist") -> null
            has("like", "love", "heart", "favorite", "favourite", "favorites", "favourites",
                "thumbs up", "thumb up", "thumbsup", "thumbup", "add to library",
                "add to collection", "add to your library", "save to library",
                "save to your library") -> CustomKind.LIKE
            else -> null
        }
    }

    /** Lowercase words of an id or a name: separators and camel case split ("thumbsUp"). */
    private fun words(text: String): List<String> =
        text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.isNotEmpty() }
}
