package org.stypox.dicio.skills.calendar

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.stypox.dicio.R
import java.util.Date

/**
 * Gets the calendar provider's `EVENT_REMINDER` broadcast when an alert is due, and posts a
 * notification for the reminders GAR created, but only when no calendar app would (see
 * [Handlers.garNotifies]): on GrapheneOS the stock calendar registers for the broadcast but its
 * alert code was removed, so without a real calendar app reminders would never show.
 */
class CalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CalendarContract.ACTION_EVENT_REMINDER) {
            return
        }
        val result = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CalendarReminderNotifier.notifyDue(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Could not post calendar reminders", e)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        private const val TAG = "CalendarReminderReceiver"
    }
}

object CalendarReminderNotifier {
    private const val CHANNEL_ID = "gar_calendar_reminders"

    /** Notifies for GAR's due reminders and marks their alerts as fired. */
    suspend fun notifyDue(context: Context) {
        if (!garNotifies(context)) {
            return
        }
        val store = context.calendarRemindersDataStore
        val tracked = store.data.first().remindersList.map { Tracked(it.eventId, it.alarmTimeMillis) }
        if (tracked.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        val resolver = context.contentResolver
        val due = ReminderTracking.toFire(CalendarStore.dueAlerts(resolver, now), tracked, now)
        for (alert in due) {
            post(context, alert)
            CalendarStore.markFired(resolver, alert.id, now)
        }

        val fired = due.map { it.eventId }
        store.updateData { reminders ->
            val kept = ReminderTracking.prune(
                reminders.remindersList.map { Tracked(it.eventId, it.alarmTimeMillis) }, fired, now,
            )
            reminders.toBuilder().clearReminders().addAllReminders(kept.map(::toProto)).build()
        }
    }

    /** Remembers a reminder GAR created, so that [notifyDue] notifies for it. */
    suspend fun track(context: Context, reminder: Tracked) {
        context.calendarRemindersDataStore.updateData { reminders ->
            val kept = ReminderTracking.add(
                reminders.remindersList.map { Tracked(it.eventId, it.alarmTimeMillis) },
                reminder,
                System.currentTimeMillis(),
            )
            reminders.toBuilder().clearReminders().addAllReminders(kept.map(::toProto)).build()
        }
    }

    /** Whether GAR posts reminder notifications itself on this device. */
    fun garNotifies(context: Context): Boolean =
        Handlers.garNotifies(reminderReceivers(context.packageManager), context.packageName)

    /** Whether GAR may post notifications at all. */
    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()

    @SuppressLint("QueryPermissionsNeeded") // GAR can see all apps (QUERY_ALL_PACKAGES)
    private fun reminderReceivers(packageManager: PackageManager): List<String> {
        val intent = Intent(
            CalendarContract.ACTION_EVENT_REMINDER,
            Uri.parse("content://${CalendarContract.AUTHORITY}/0"),
        )
            // like the broadcast itself: an app that was never opened (or force stopped) doesn't
            // get it, so it doesn't notify either
            .addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES)
        return packageManager.queryBroadcastReceivers(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
    }

    @SuppressLint("MissingPermission") // checked by canNotify()
    private fun post(context: Context, alert: AlertRow) {
        if (!canNotify(context)) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.skill_calendar_reminder_channel_name))
                .setDescription(context.getString(R.string.skill_calendar_reminder_channel_description))
                .build()
        )

        val open = CalendarStore.viewEventIntent(alert.eventId).let { view ->
            CalendarStore.intentFor(CalendarStore.launchFor(context.packageManager, view), view)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gar_calendar_event_white)
            .setContentTitle(alert.title ?: context.getString(R.string.skill_calendar_reminder_no_title))
            .setContentText(DateFormat.getTimeFormat(context).format(Date(alert.begin)))
            .setWhen(alert.begin)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply {
                if (open != null) {
                    setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            alert.eventId.toInt(),
                            open,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    )
                }
            }
            .build()
        manager.notify(NOTIFICATION_TAG, alert.eventId.toInt(), notification)
    }

    private const val NOTIFICATION_TAG = "gar_calendar_reminder"

    private fun toProto(tracked: Tracked): TrackedReminder = TrackedReminder.newBuilder()
        .setEventId(tracked.eventId)
        .setAlarmTimeMillis(tracked.alarmTimeMillis)
        .build()
}
