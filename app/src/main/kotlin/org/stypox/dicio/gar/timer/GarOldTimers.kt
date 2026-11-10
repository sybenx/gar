package org.stypox.dicio.gar.timer

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.stypox.dicio.R
import org.stypox.dicio.util.checkPermissions
import java.io.File

/**
 * Drops the timers of GAR's own timer engine, which the clock app's timers replaced
 * (gar/DESIGN.md, "Timers"): on the first start after the update, their alarm, notifications,
 * notification channels and saved state (`shared_prefs/gar_timers.xml`) go. A timer that was
 * still there (counting, paused or ringing) is lost, so one notification on Dicio's channel tells
 * the user to set it again. The engine's classes are gone, so the alarm's intent is rebuilt from
 * their names, and the state is read as plain JSON.
 */
object GarOldTimers {
    private const val TAG = "GarOldTimers"
    private const val PREFERENCES_NAME = "gar_timers"
    private const val STATE_KEY = "state"
    private const val LOST_NOTIFICATION_ID = 74_100_921
    private const val ALARM_RECEIVER = "org.stypox.dicio.gar.timer.GarTimerAlarmReceiver"
    private const val ACTION_TIMER_ENDS = "org.stypox.dicio.gar.timer.TIMER_ENDS"
    private const val NOTIFICATION_TAG = "gar_timer"
    private const val RING_NOTIFICATION_ID = 74_100_920
    private val CHANNELS = listOf("gar_timers", "gar_timer_ringing", "gar_timer_ringing_aloud")

    /** Cheap when there is nothing to drop: one file check. Never throws. */
    fun drop(context: Context) {
        try {
            val saved = File(context.applicationInfo.dataDir, "shared_prefs/$PREFERENCES_NAME.xml")
            if (!saved.exists()) {
                return
            }
            val lost = lostTimers(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .getString(STATE_KEY, null)
            )
            cancelAlarm(context)
            cancelNotifications(context)
            context.deleteSharedPreferences(PREFERENCES_NAME)
            if (lost != null) {
                notifyLost(context, lost)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't drop the old timers", e)
        }
    }

    /**
     * The names of the timers in the old engine's saved state [text] (null for a timer without a
     * name), or null if it had none or can't be read. Every saved timer was counting, paused or
     * ringing: cancelled ones were removed.
     */
    fun lostTimers(text: String?): List<String?>? {
        if (text.isNullOrEmpty()) {
            return null
        }
        return try {
            val timers = (Json.parseToJsonElement(text) as? JsonObject)?.get("timers") as? JsonArray
            timers?.map { timer ->
                ((timer as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
                    ?.trim()?.takeIf { it.isNotEmpty() }
            }?.takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            // also kotlinx.serialization's SerializationException
            null
        }
    }

    /** "Your pasta timer was stopped": one plain notification, if GAR may post one. */
    private fun notifyLost(context: Context, lost: List<String?>) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            !checkPermissions(context, android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            return
        }
        val name = lost.singleOrNull()
        val text = when {
            lost.size > 1 -> context.getString(R.string.gar_old_timers_lost_many, lost.size)
            name != null -> context.getString(R.string.gar_old_timers_lost_name, name)
            else -> context.getString(R.string.gar_old_timers_lost)
        }
        val notification = NotificationCompat.Builder(
            context, context.getString(R.string.error_report_channel_id)
        )
            .setSmallIcon(R.drawable.ic_gar_timer_white)
            .setContentTitle(context.getString(R.string.gar_old_timers_lost_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(LOST_NOTIFICATION_ID, notification)
    }

    private fun cancelAlarm(context: Context) {
        val intent = Intent(ACTION_TIMER_ENDS)
            .setComponent(ComponentName(context.packageName, ALARM_RECEIVER))
        val operation = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(operation)
        operation.cancel()
    }

    private fun cancelNotifications(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications
            .filter { it.tag == NOTIFICATION_TAG || it.id == RING_NOTIFICATION_ID }
            .forEach { manager.cancel(it.tag, it.id) }
        CHANNELS.forEach { manager.deleteNotificationChannel(it) }
    }
}
