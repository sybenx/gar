package org.stypox.dicio.skills.song

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** A song Audile recognized, from its result notification. */
data class AudileTrack(
    val track: SongTrack,
    /** Audile's id of the track, which opens its result screen (see [AudileNotifications]). */
    val id: String?,
    /** When Audile started recording, if the notification says. */
    val sampleTime: Instant?,
)

/** An Audile result notification as Dicio's notification listener got it. */
data class AudilePosted(
    val result: AudileTrack,
    val postTime: Instant,
    /** It came from [AudileNotifications.FOREGROUND_CHANNEL]: the user switched that on. */
    val foreground: Boolean,
)

/**
 * Audile's result notifications (ResultNotificationHelper in Audile's source, see
 * `~/src/gar-notes/recon/audile.md`): every success notification carries the track in extras
 * that Audile added "for scrobbling". A notification listener sees the background and queued
 * results (IMPORTANCE_HIGH channels), but a result shown on Audile's own screen goes to
 * [FOREGROUND_CHANNEL], which is off (IMPORTANCE_NONE) until the user switches it on: Android
 * doesn't pass notifications of a channel that is off to listeners.
 */
object AudileNotifications {
    const val FOREGROUND_CHANNEL = "com.mrsep.musicrecognizer.foreground_result"
    private const val KEY = "com.mrsep.musicrecognizer.track_metadata."

    /** A result recorded this much before the hand-off still counts (clock skew). */
    private val SLACK: Duration = Duration.ofSeconds(5)

    /**
     * @param extra the notification's string extra for a key
     * @return the track in a notification of [packageName], or null if it isn't an Audile result
     */
    fun parse(packageName: String, extra: (String) -> String?): AudileTrack? {
        if (packageName !in AudileIntents.PACKAGES) {
            return null
        }
        fun text(name: String) = extra(KEY + name)?.trim()?.takeIf { it.isNotEmpty() }
        val title = text("title") ?: return null
        return AudileTrack(
            track = SongTrack(
                title = title,
                artist = text("artist") ?: "",
                album = text("album"),
                releaseDate = text("release_date"),
            ),
            id = text("id"),
            sampleTime = text("sample_timestamp")?.let {
                try {
                    Instant.parse(it)
                } catch (e: DateTimeParseException) {
                    null
                }
            },
        )
    }

    /**
     * Whether [posted] answers a hand-off at [handOff]: Audile recorded it after the hand-off, or,
     * if the notification doesn't say when it recorded, posted it after. Older results come back
     * when Audile updates their notifications.
     */
    fun answers(posted: AudilePosted, handOff: Instant): Boolean =
        !(posted.result.sampleTime ?: posted.postTime).isBefore(handOff.minus(SLACK))

    /** Opens Audile on the result screen of the track with [id] (a deep link of MainActivity). */
    fun trackUri(id: String): String = "app://mrsep.musicrecognizer.com/track/" +
        URLEncoder.encode(id, "UTF-8").replace("+", "%20")
}

/**
 * Audile's latest result, from Dicio's notification listener
 * ([org.stypox.dicio.skills.notify.NotifyHandler]). Only the latest is kept: [await] filters it by
 * time, so a result posted between the hand-off and [await] isn't missed.
 */
object AudileResults {
    private const val TAG = "AudileResults"
    private val latest = MutableStateFlow<AudilePosted?>(null)

    /** Called for every notification the listener is told about. Never throws. */
    fun posted(sbn: StatusBarNotification) {
        try {
            val extras = sbn.notification?.extras ?: return
            val result = AudileNotifications.parse(sbn.packageName) { extras.getString(it) }
                ?: return
            latest.value = AudilePosted(
                result = result,
                postTime = Instant.ofEpochMilli(sbn.postTime),
                foreground = sbn.notification.channelId == AudileNotifications.FOREGROUND_CHANNEL,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't read a notification", e)
        }
    }

    /** For tests: what the listener would post. */
    internal fun post(posted: AudilePosted) {
        latest.value = posted
    }

    /** Audile's answer to a hand-off at [handOff], or null if none comes within [timeout]. */
    suspend fun await(handOff: Instant, timeout: Duration): AudilePosted? =
        withTimeoutOrNull(timeout.toMillis()) {
            latest.filterNotNull().first { AudileNotifications.answers(it, handOff) }
        }
}

/**
 * What the card of a hand-off to Audile points out.
 * @param needsListener GAR has no notification access, so it can't hear Audile's answer
 * @param foregroundHint the answer goes to Audile's screen, whose notifications are off until
 * switched on
 */
data class AudileHints(val needsListener: Boolean, val foregroundHint: Boolean)

/** The card of a hand-off to Audile. */
sealed interface AudileCardState {
    data class Waiting(val hints: AudileHints) : AudileCardState

    data class Found(val result: AudileTrack) : AudileCardState

    /** No answer came: Audile shows it, or found nothing, or GAR can't hear it. */
    data class Ended(val hints: AudileHints) : AudileCardState
}

object AudileWaits {
    /** Audile records for up to about 30 s and may retry; the user may have to tap or unlock. */
    val TIMEOUT: Duration = Duration.ofMinutes(2)

    /** Whether GAR waits for Audile's answer after [result]: Audile is recognizing, or soon. */
    fun waitsFor(result: AudileResult): Boolean = when (result) {
        AudileResult.LISTENING_IN_BACKGROUND,
        AudileResult.OPENED,
        AudileResult.OPENED_LOCKED,
        AudileResult.OPENED_APP -> true
        AudileResult.BLOCKED_IN_BACKGROUND, AudileResult.FAILED -> false
    }

    /**
     * Whether the answer after [result] goes to Audile's screen, so to its foreground channel.
     * The background shortcut's answer goes there too while Audile's floating button is on, which
     * GAR can't know.
     */
    fun onAudileScreen(result: AudileResult): Boolean =
        result != AudileResult.LISTENING_IN_BACKGROUND

    /**
     * The hint about Audile's foreground notifications shows until GAR heard a result from them
     * once ([foregroundHeard]), and only when the answer goes to Audile's screen.
     */
    fun hints(result: AudileResult, hearsResults: Boolean, foregroundHeard: Boolean) = AudileHints(
        needsListener = !hearsResults,
        foregroundHint = hearsResults && !foregroundHeard && onAudileScreen(result),
    )

    /** The card at first: GAR waits only if it can hear the answer. */
    fun initial(hints: AudileHints): AudileCardState =
        if (hints.needsListener) AudileCardState.Ended(hints) else AudileCardState.Waiting(hints)
}

/** Waits for one hand-off's answer at a time: a new hand-off drops the previous wait. */
class AudileWaiter(private val scope: CoroutineScope) {
    private var job: Job? = null

    @Synchronized
    fun start(wait: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { wait() }
    }
}

/** Opens Audile's settings page of its notifications for results found on its screen. */
fun openAudileForegroundSettings(context: Context, packageName: String) {
    val channel = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, AudileNotifications.FOREGROUND_CHANNEL)
    val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    startFirst(context, channel, app)
}

/** Opens Audile on the result screen of [result], or Audile itself without an id. */
fun openAudileTrack(context: Context, result: AudileTrack, packageName: String) {
    val track = result.id?.let {
        Intent(Intent.ACTION_VIEW, Uri.parse(AudileNotifications.trackUri(it))).setPackage(packageName)
    }
    val app = context.packageManager.getLaunchIntentForPackage(packageName)
    startFirst(context, *listOfNotNull(track, app).toTypedArray())
}

private fun startFirst(context: Context, vararg intents: Intent) {
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            Log.w("AudileSettings", "Can't open $intent", e)
        }
    }
}
