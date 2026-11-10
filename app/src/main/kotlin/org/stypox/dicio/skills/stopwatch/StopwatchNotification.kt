package org.stypox.dicio.skills.stopwatch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.stypox.dicio.MainActivity
import org.stypox.dicio.R

/**
 * What the stopwatch notification shows, or null for no notification (a reset stopwatch).
 *
 * @param chronometerBase the wall clock time the notification's chronometer counts from
 * @param lastLap the number and duration of the last lap, if any
 */
data class StopwatchNotificationContent(
    val running: Boolean,
    val elapsedMillis: Long,
    val chronometerBase: Long,
    val lastLap: Pair<Int, Long>?,
) {
    companion object {
        fun of(state: StopwatchState, now: ClockReading): StopwatchNotificationContent? {
            if (state.isReset) {
                return null
            }
            val elapsed = state.elapsedMillis(now)
            return StopwatchNotificationContent(
                running = state.isRunning,
                elapsedMillis = elapsed,
                chronometerBase = now.wallMillis - elapsed,
                lastLap = state.lapDurations().lastOrNull()?.let { Pair(state.lapTotals.size, it) },
            )
        }
    }
}

/**
 * The stopwatch's notification: while running, an ongoing one with a chronometer and Stop/Reset
 * buttons; while stopped, a dismissible one with the time and Resume/Reset buttons; none once
 * reset. Skipped silently when notifications aren't allowed.
 */
class StopwatchNotification(private val context: Context) {

    fun update(state: StopwatchState, now: ClockReading) {
        val manager = NotificationManagerCompat.from(context)
        val content = StopwatchNotificationContent.of(state, now)
        if (content == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!canNotify(manager)) {
            return
        }

        createChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stopwatch_white)
            .setContentTitle(context.getString(R.string.skill_name_stopwatch))
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setContentIntent(openAppIntent())

        val lapText = content.lastLap?.let { (number, millis) ->
            context.getString(R.string.skill_stopwatch_notification_lap, number, formatStopwatch(millis))
        }
        if (content.running) {
            builder.setOngoing(true)
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(content.chronometerBase)
                .setContentText(lapText ?: context.getString(R.string.skill_stopwatch_running))
                .addAction(action(ACTION_STOP, R.string.skill_stopwatch_button_stop))
                .addAction(action(ACTION_RESET, R.string.skill_stopwatch_button_reset))
        } else {
            builder.setOngoing(false)
                .setShowWhen(false)
                .setContentText(context.getString(
                    R.string.skill_stopwatch_notification_stopped,
                    formatStopwatch(content.elapsedMillis),
                ))
                .addAction(action(ACTION_RESUME, R.string.skill_stopwatch_button_resume))
                .addAction(action(ACTION_RESET, R.string.skill_stopwatch_button_reset))
        }

        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // the permission was revoked in the meantime
            Log.w(TAG, "Can't show the stopwatch notification", e)
        }
    }

    private fun canNotify(manager: NotificationManagerCompat): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.skill_name_stopwatch),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = context.getString(R.string.skill_stopwatch_channel_description)
        channel.setShowBadge(false)
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun action(action: String, label: Int) = NotificationCompat.Action(
        0,
        context.getString(label),
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, StopwatchReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    private fun openAppIntent() = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "StopwatchNotification"
        const val CHANNEL_ID = "gar_stopwatch"
        const val NOTIFICATION_ID = 74_100_917

        const val ACTION_STOP = "org.stypox.dicio.skills.stopwatch.STOP"
        const val ACTION_RESUME = "org.stypox.dicio.skills.stopwatch.RESUME"
        const val ACTION_RESET = "org.stypox.dicio.skills.stopwatch.RESET"

        /** The stopwatch action for a notification button's intent action, if it is one. */
        fun actionFor(intentAction: String?): StopwatchAction? = when (intentAction) {
            ACTION_STOP -> StopwatchAction.STOP
            ACTION_RESUME -> StopwatchAction.RESUME
            ACTION_RESET -> StopwatchAction.RESET
            else -> null
        }
    }
}

/** The notification's buttons. Not exported: only the app's own pending intents reach it. */
class StopwatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = StopwatchNotification.actionFor(intent.action) ?: return
        StopwatchController.get(context).perform(action)
    }
}

/** Shows the notification of a running or stopped stopwatch again after a reboot. */
class StopwatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            StopwatchController.get(context).refresh()
        }
    }
}
