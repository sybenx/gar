package org.stypox.dicio.skills.song

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.stypox.dicio.MainActivity
import org.stypox.dicio.R
import java.util.Date
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Whether the queue may send at all: the song skill is enabled and GAR can show the
 * notification with the result. Otherwise [SongQueueProcessor] deletes the clips unsent.
 */
private suspend fun songQueueMayRun(context: Context): Boolean {
    val userSettings = EntryPointAccessors
        .fromApplication(context.applicationContext, SongEntryPoint::class.java)
        .userSettings()
    val enabled = userSettings.data.first().enabledSkillsMap.getOrDefault(SongInfo.id, true)
    return enabled && SongNotifications.canShow(context)
}

/**
 * Sends the recordings made offline once a network is up (WorkManager's CONNECTED constraint),
 * see [SongQueueProcessor]. One unique work: a new clip replaces the waiting work (so it doesn't
 * wait out an earlier clip's backoff), or runs after the running one. A transient failure retries
 * with exponential backoff from 30 s; each clip gives up after
 * [SongQueueProcessor.MAX_ATTEMPTS] failures.
 */
class SongQueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val queue = SongClipQueue.forContext(context)
        val processor = SongQueueProcessor(
            queue = queue,
            provider = { SongProviderConfig.from(context.songSettingsDataStore.data.first()) },
            recognizer = SongRecognitionClient.forContext(context),
            notifier = SongNotifications(context),
            canRun = { songQueueMayRun(context) },
            onError = { Log.e(TAG, "Can't send a queued recording", it) },
        )
        val outcome = try {
            processor.processAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a bug: don't fail the unique work, which would fail the clips queued after it
            Log.e(TAG, "The song queue failed", e)
            SongQueueProcessor.Outcome.DONE
        }
        SongQueueExpiryWorker.schedule(context, queue)
        return when (outcome) {
            SongQueueProcessor.Outcome.DONE -> Result.success()
            SongQueueProcessor.Outcome.RETRY -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "SongQueueWorker"
        private const val WORK_NAME = "gar_song_queue"

        /** After a clip was queued, from a background thread (it asks WorkManager the state). */
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            val running = try {
                workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                    .any { it.state == WorkInfo.State.RUNNING }
            } catch (e: ExecutionException) {
                false
            } catch (e: InterruptedException) {
                false
            }
            val request = OneTimeWorkRequestBuilder<SongQueueWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // a running work may have listed the queue for the last time already: run once more
            // after it; a waiting one is replaced, which resets its backoff
            workManager.enqueueUniqueWork(
                WORK_NAME,
                if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                request,
            )
            SongQueueExpiryWorker.schedule(context, SongClipQueue.forContext(context))
        }

        /** Deletes the waiting clips and stops sending them, e.g. when the service is turned off. */
        fun discardQueue(context: Context) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            workManager.cancelUniqueWork(WORK_NAME)
            workManager.cancelUniqueWork(SongQueueExpiryWorker.WORK_NAME)
            SongClipQueue.forContext(context).clear()
        }
    }
}

/**
 * Deletes clips that waited [SongClipQueue.MAX_AGE_MILLIS] for the network, with a notification,
 * also while GAR has no network at all (no constraints): [SongQueueWorker] only runs online.
 * Scheduled for the oldest clip's expiry; when it ran, again for the next one.
 */
class SongQueueExpiryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val queue = SongClipQueue.forContext(context)
        try {
            if (songQueueMayRun(context)) {
                val notifier = SongNotifications(context)
                queue.removeExpired(System.currentTimeMillis())
                    .forEach { notifier.dropped(it, SongDropReason.EXPIRED) }
            } else {
                queue.clear()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Can't expire the song queue", e)
        }
        schedule(context, queue, fromExpiryWork = true)
        return Result.success()
    }

    companion object {
        private const val TAG = "SongQueueExpiryWorker"
        const val WORK_NAME = "gar_song_queue_expiry"

        /**
         * Runs the expiry when the oldest waiting clip expires. A waiting schedule is kept: it is
         * for an older clip, or for one that is gone, and then schedules the next one when it
         * runs. [fromExpiryWork]: the expiry work itself, which the next one follows.
         */
        fun schedule(context: Context, queue: SongClipQueue, fromExpiryWork: Boolean = false) {
            val expiry = queue.nextExpiryMillis() ?: return
            val delay = (expiry - System.currentTimeMillis()).coerceAtLeast(0) + MARGIN_MILLIS
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                if (fromExpiryWork) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SongQueueExpiryWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build(),
            )
        }

        /** So that the clip is expired when the work runs. */
        private const val MARGIN_MILLIS = 60_000L
    }
}

/** The results of queued recordings, on their own channel. */
class SongNotifications(private val context: Context) : SongQueueNotifier {

    override fun found(clip: QueuedClip, track: SongTrack) {
        val text = SongMessages.thatIs(track.title, track.artist).resolve(context)
        val builder = builder(clip, track.title, text)
        track.spotifyUrl?.let {
            builder.addAction(0, context.getString(R.string.skill_song_open_spotify), viewIntent(it))
        }
        track.youtubeUrl?.let {
            builder.addAction(0, context.getString(R.string.skill_song_open_youtube), viewIntent(it))
        }
        notify(clip, builder)
    }

    override fun notFound(clip: QueuedClip) {
        notify(clip, builder(
            clip,
            context.getString(R.string.skill_song_notification_not_found),
            context.getString(R.string.skill_song_not_recognized),
        ))
    }

    override fun failed(
        clip: QueuedClip,
        provider: SongProviderConfig,
        failure: RecognitionResult.Failure,
    ) {
        // final: the clip is gone, so there is nothing to try again
        val reason = SongMessages.failure(failure, provider.kind, final = true).resolve(context)
        notify(clip, builder(clip, context.getString(R.string.skill_song_notification_not_found), reason))
    }

    override fun dropped(clip: QueuedClip, reason: SongDropReason) {
        notify(clip, builder(
            clip,
            context.getString(R.string.skill_song_notification_not_found),
            context.getString(SongMessages.dropped(reason)),
        ))
    }

    private fun builder(clip: QueuedClip, title: String, text: String): NotificationCompat.Builder {
        val recordedAt = DateFormat.getTimeFormat(context).format(Date(clip.createdAtMillis))
        val body = context.getString(R.string.skill_song_notification_recorded_at, text, recordedAt)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hearing_white)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setWhen(clip.createdAtMillis)
            .setShowWhen(true)
            .setContentIntent(PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
    }

    private fun viewIntent(url: String): PendingIntent = PendingIntent.getActivity(
        context,
        url.hashCode(),
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(clip: QueuedClip, builder: NotificationCompat.Builder) {
        if (!canShow(context)) {
            return
        }
        createChannel(context)
        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_TAG, clip.id.hashCode(), builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't show the song notification", e)
        }
    }

    companion object {
        private const val TAG = "SongNotifications"
        const val CHANNEL_ID = "gar_song"
        private const val NOTIFICATION_TAG = "gar_song"

        private fun hasPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun channelBlocked(context: Context): Boolean =
            NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_ID)
                ?.importance == NotificationManager.IMPORTANCE_NONE

        /**
         * Whether GAR can show the song notifications: the permission (Android 13+, which GAR
         * otherwise only asks for with the wake word), GAR's notifications on and the song
         * channel not blocked. Without it, an offline recording isn't kept.
         */
        fun canShow(context: Context): Boolean = hasPermission(context) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            !channelBlocked(context)

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.skill_song_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            channel.description = context.getString(R.string.skill_song_channel_description)
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        /** The system page for GAR's notifications, or for the song channel if only it is off. */
        fun openSettings(context: Context) {
            val intent = if (hasPermission(context) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                channelBlocked(context)
            ) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            }
            try {
                context.startActivity(
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No notification settings", e)
            }
        }
    }
}

/**
 * A tap that lets GAR show the song notifications: Android's permission dialog where it can still
 * ask (Android 13+), else the system's notification settings.
 * @param onDone called after the dialog, to read the new state
 */
@Composable
fun rememberAllowSongNotifications(onDone: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (SongNotifications.canShow(context)) {
            SongNotifications.createChannel(context)
        } else {
            // denied now or before (then Android doesn't ask again), or turned off in settings
            SongNotifications.openSettings(context)
        }
        onDone()
    }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SongNotifications.openSettings(context)
        }
    }
}
