package org.stypox.dicio.gar.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import org.stypox.dicio.skills.notify.NotifyHandler

/** A custom action a media session offers on its controls (a like button, a shuffle switch). */
data class MediaCustomAction(val action: String, val name: String)

/**
 * A media session as GAR sees it, read once from its controller, so that the decisions in
 * [GarMediaControls] and [GarMediaPlay] are plain functions.
 *
 * @param appName the label of [packageName], null if unknown
 * @param state a `PlaybackState.STATE_*`, `STATE_NONE` without a playback state
 * @param actions the session's `PlaybackState.getActions()`, with the androidx.media bits
 * ([GarMediaSessions.ACTION_SET_REPEAT_MODE], [GarMediaSessions.ACTION_SET_SHUFFLE_MODE])
 * @param positionMs the position at [updateTimeElapsed] (elapsedRealtime)
 * @param durationMs the track's length from its metadata, null if unknown
 * @param ratingType a `Rating.RATING_*`, what [MediaController.getRatingType] says
 */
data class MediaSessionView(
    val packageName: String,
    val appName: String? = null,
    val state: Int = PlaybackState.STATE_NONE,
    val actions: Long = 0,
    val positionMs: Long = 0,
    val updateTimeElapsed: Long = 0,
    val speed: Float = 1.0f,
    val durationMs: Long? = null,
    val title: String? = null,
    val ratingType: Int = Rating.RATING_NONE,
    val customActions: List<MediaCustomAction> = listOf(),
) {
    val isPlaying: Boolean get() = state == PlaybackState.STATE_PLAYING

    /** Playing, or about to: what a player that was asked to play shows. */
    val isStarting: Boolean get() = state in STARTING_STATES

    fun supports(action: Long): Boolean = (actions and action) != 0L

    /** The position at [nowElapsed], moving on while it plays. */
    fun positionAt(nowElapsed: Long): Long {
        if (!isPlaying || updateTimeElapsed <= 0) {
            return positionMs
        }
        return positionMs + ((nowElapsed - updateTimeElapsed) * speed).toLong()
    }

    private companion object {
        val STARTING_STATES = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
        )
    }
}

/** What GAR asks a media session to do. */
sealed interface SessionCommand {
    /** `TransportControls.sendCustomAction`, also how androidx.media sets shuffle and repeat. */
    data class CustomAction(val action: String, val args: Map<String, Int> = mapOf()) : SessionCommand

    data class SeekTo(val positionMs: Long) : SessionCommand

    /** @param ratingType `Rating.RATING_HEART` or `Rating.RATING_THUMB_UP_DOWN` */
    data class SetRating(val ratingType: Int, val positive: Boolean) : SessionCommand

    data object Pause : SessionCommand

    /** @param extras the play-from-search extras, see [GarMediaPlay.extras] */
    data class PlayFromSearch(val query: String, val extras: Map<String, String>) : SessionCommand
}

/** The phone's media sessions, through GAR's notification listener. */
interface MediaSessions {
    /**
     * The sessions in Android's priority order (the one the media keys reach first), without
     * GAR's own, or null when GAR has no notification access.
     */
    fun sessions(): List<MediaSessionView>?

    /** Sends [command] to the session of [packageName]; false if it is gone or refused it. */
    fun send(packageName: String, command: SessionCommand): Boolean

    /** elapsedRealtime, for [MediaSessionView.positionAt]. */
    fun nowElapsed(): Long
}

/**
 * The phone's media sessions, seen through Dicio's notification listener ([NotifyHandler]), the
 * access the song skill's tier 1 and the alarm's ringing check already use. Without notification
 * access Android doesn't list the sessions to GAR at all.
 */
class AndroidMediaSessions(private val context: Context) : MediaSessions {

    override fun sessions(): List<MediaSessionView>? =
        GarMediaSessions.controllers(context)?.map { GarMediaSessions.view(context, it) }

    override fun send(packageName: String, command: SessionCommand): Boolean {
        val controller = GarMediaSessions.controllers(context)
            ?.firstOrNull { it.packageName == packageName }
            ?: return false
        return try {
            val controls = controller.transportControls
            when (command) {
                is SessionCommand.CustomAction -> controls.sendCustomAction(
                    command.action,
                    Bundle().apply { command.args.forEach { (key, value) -> putInt(key, value) } },
                )
                is SessionCommand.SeekTo -> controls.seekTo(command.positionMs)
                is SessionCommand.SetRating -> controls.setRating(
                    if (command.ratingType == Rating.RATING_HEART) {
                        Rating.newHeartRating(command.positive)
                    } else {
                        Rating.newThumbRating(command.positive)
                    }
                )
                SessionCommand.Pause -> controls.pause()
                is SessionCommand.PlayFromSearch -> controls.playFromSearch(
                    command.query,
                    Bundle().apply { command.extras.forEach { (key, value) -> putString(key, value) } },
                )
            }
            true
        } catch (e: RuntimeException) {
            // a session that went away in the meantime (DeadObjectException is a RemoteException,
            // which the framework wraps), or a rating the session doesn't take
            Log.w(TAG, "Can't send $command to $packageName", e)
            false
        }
    }

    override fun nowElapsed(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "AndroidMediaSessions"
    }
}

object GarMediaSessions {
    private const val TAG = "GarMediaSessions"

    /**
     * androidx.media's `PlaybackStateCompat` actions, which its sessions (and media3's, for
     * platform controllers) put into the platform state next to the framework's own.
     */
    const val ACTION_SET_REPEAT_MODE = 1L shl 18
    const val ACTION_SET_SHUFFLE_MODE = 1L shl 21

    /**
     * The custom actions through which androidx.media's `MediaControllerCompat` sets shuffle and
     * repeat on a session (`MediaSessionCompat.ACTION_SET_*`), which compat and media3 sessions
     * turn into `onSetShuffleMode` and `onSetRepeatMode`.
     */
    const val CUSTOM_SET_SHUFFLE_MODE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE"
    const val CUSTOM_SET_REPEAT_MODE = "android.support.v4.media.session.action.SET_REPEAT_MODE"
    const val ARGUMENT_SHUFFLE_MODE = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE"
    const val ARGUMENT_REPEAT_MODE = "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE"

    /** `PlaybackStateCompat.SHUFFLE_MODE_*` and `REPEAT_MODE_*`. */
    const val SHUFFLE_MODE_NONE = 0
    const val SHUFFLE_MODE_ALL = 1
    const val REPEAT_MODE_NONE = 0
    const val REPEAT_MODE_ONE = 1
    const val REPEAT_MODE_ALL = 2

    /** Dicio's notification listener, which unlocks the media sessions. */
    fun listenerComponent(context: Context) = ComponentName(context, NotifyHandler::class.java)

    /**
     * The controllers of the active sessions (not GAR's), or null without notification access.
     * Never throws.
     */
    fun controllers(context: Context): List<MediaController>? {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        return try {
            manager.getActiveSessions(listenerComponent(context))
                .filter { it.packageName != context.packageName }
        } catch (e: SecurityException) {
            // no notification access, or it was just taken away
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't list the media sessions", e)
            null
        }
    }

    /**
     * Whether a media session other than GAR's plays now, for skills' `score`: answers at once,
     * never throws, and is false without notification access (the listener isn't running) and
     * in JVM tests.
     */
    fun isPlaying(): Boolean {
        val listener = NotifyHandler.Instance ?: return false
        return try {
            controllers(listener)?.any {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } == true
        } catch (e: RuntimeException) {
            false
        }
    }

    fun view(context: Context, controller: MediaController): MediaSessionView {
        val state = controller.playbackState
        val metadata = controller.metadata
        return MediaSessionView(
            packageName = controller.packageName,
            appName = appName(context, controller.packageName),
            state = state?.state ?: PlaybackState.STATE_NONE,
            actions = state?.actions ?: 0L,
            positionMs = state?.position ?: 0L,
            updateTimeElapsed = state?.lastPositionUpdateTime ?: 0L,
            speed = state?.playbackSpeed ?: 1.0f,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString(),
            ratingType = controller.ratingType,
            customActions = state?.customActions.orEmpty().map {
                MediaCustomAction(it.action, it.name?.toString() ?: "")
            },
        )
    }

    fun appName(context: Context, packageName: String): String? = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        null
    }

    /**
     * Pauses what plays: every playing session with notification access, else the media key,
     * which Android sends to the session that last played (as Dicio's media skill does).
     */
    fun pauseEverything(context: Context) {
        val sessions = AndroidMediaSessions(context)
        val playing = sessions.sessions()
        if (playing == null) {
            val audioManager = context.getSystemService(AudioManager::class.java) ?: return
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            return
        }
        for (session in playing.filter { it.isStarting }) {
            sessions.send(session.packageName, SessionCommand.Pause)
        }
    }
}
