package org.stypox.dicio.skills.song

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.stypox.dicio.skills.notify.NotifyHandler

/** The phone's side of the song skill: media sessions and the network. */
object SongDevice {
    private const val TAG = "SongDevice"

    /** Dicio's notification listener, which also unlocks the media sessions. */
    fun listenerComponent(context: Context) = ComponentName(context, NotifyHandler::class.java)

    fun hasListenerAccess(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(listenerComponent(context)) == true
    } catch (e: RuntimeException) {
        false
    }

    /** Tier 1: what the phone plays, or [NowPlaying.NoAccess] without notification access. */
    fun nowPlaying(context: Context): NowPlaying {
        if (!hasListenerAccess(context)) {
            return NowPlaying.NoAccess
        }
        val manager = context.getSystemService(MediaSessionManager::class.java)
            ?: return NowPlaying.NothingPlaying
        val controllers = try {
            manager.getActiveSessions(listenerComponent(context))
        } catch (e: SecurityException) {
            // the listener was disabled in the meantime
            Log.w(TAG, "No access to the media sessions", e)
            return NowPlaying.NoAccess
        }

        val sessions = controllers.map { controller ->
            val metadata = controller.metadata
            fun text(vararg keys: String) = keys.firstNotNullOfOrNull { key ->
                metadata?.getText(key)?.toString()?.takeIf { it.isNotBlank() }
            }
            MediaSessionInfo(
                packageName = controller.packageName,
                playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
                title = text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                artist = text(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                ),
                album = text(MediaMetadata.METADATA_KEY_ALBUM),
            )
        }
        return SongLogic.nowPlaying(sessions, context.packageName) { appName(context, it) }
    }

    private fun appName(context: Context, packageName: String): String? = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        null
    }

    /**
     * Whether a network with internet is up. GrapheneOS's Network permission toggle makes the
     * network look down to GAR, so a revoked permission reads as offline too.
     */
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false)
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
