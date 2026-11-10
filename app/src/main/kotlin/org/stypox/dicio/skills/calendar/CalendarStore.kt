package org.stypox.dicio.skills.calendar

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract.CalendarAlerts
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId

/**
 * The calendar skill's access to the calendar provider and to calendar apps. The values it
 * writes come from [CalendarValues], the choices it makes from [CalendarChoice] and [Handlers].
 */
object CalendarStore {
    private const val TAG = "CalendarStore"

    /** Every calendar, in provider order. */
    fun calendars(resolver: ContentResolver): List<CalendarEntry> {
        val calendars = mutableListOf<CalendarEntry>()
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(
                Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME,
                Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE, Calendars.IS_PRIMARY,
            ),
            null,
            null,
            "${Calendars._ID} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                calendars += CalendarEntry(
                    id = cursor.getLong(0),
                    name = cursor.getNonBlankStringOrNull(1) ?: "",
                    accountName = cursor.getNonBlankStringOrNull(2) ?: "",
                    accessLevel = if (cursor.isNull(3)) 0 else cursor.getInt(3),
                    visible = !cursor.isNull(4) && cursor.getInt(4) == 1,
                    isPrimary = !cursor.isNull(5) && cursor.getInt(5) == 1,
                )
            }
        }
        return calendars
    }

    /** @return the new event's id */
    fun insertEvent(resolver: ContentResolver, values: Map<String, Any>): Long {
        val uri = resolver.insert(Events.CONTENT_URI, contentValues(values))
            ?: throw IllegalStateException("The calendar provider didn't add the event")
        return ContentUris.parseId(uri)
    }

    fun insertReminder(resolver: ContentResolver, values: Map<String, Any>) {
        resolver.insert(Reminders.CONTENT_URI, contentValues(values))
            ?: throw IllegalStateException("The calendar provider didn't add the reminder")
    }

    fun deleteEvent(resolver: ContentResolver, eventId: Long) {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
    }

    /**
     * The instances of events on [day] in visible calendars, not cancelled. Queried by Julian
     * day, so that the provider places all-day events (stored in UTC) on the right day.
     */
    fun eventsOn(resolver: ContentResolver, day: LocalDate, zone: ZoneId): List<CalendarEvent> {
        val julianDay = CalendarTime.julianDay(day).toString()
        val events = mutableListOf<CalendarEvent>()
        resolver.query(
            Instances.CONTENT_BY_DAY_URI.buildUpon()
                .appendPath(julianDay)
                .appendPath(julianDay)
                .build(),
            arrayOf(
                Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END,
                Instances.ALL_DAY, Instances.EVENT_LOCATION,
            ),
            "${Instances.VISIBLE} = 1 AND (${Instances.STATUS} IS NULL OR " +
                    "${Instances.STATUS} != ${Events.STATUS_CANCELED})",
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val allDay = !cursor.isNull(4) && cursor.getInt(4) == 1
                if (cursor.isNull(2) || cursor.isNull(3)) {
                    continue
                }
                events += CalendarEvent(
                    id = cursor.getLong(0),
                    title = cursor.getNonBlankStringOrNull(1),
                    begin = CalendarTime.fromProviderMillis(cursor.getLong(2), allDay, zone),
                    end = CalendarTime.fromProviderMillis(cursor.getLong(3), allDay, zone),
                    allDay = allDay,
                    location = cursor.getNonBlankStringOrNull(5),
                )
            }
        }
        return CalendarEvent.sortedForDay(events, day)
    }

    /** Scheduled alerts that are due, of any event (see [ReminderTracking.toFire]). */
    fun dueAlerts(resolver: ContentResolver, nowMillis: Long): List<AlertRow> {
        val alerts = mutableListOf<AlertRow>()
        resolver.query(
            CalendarAlerts.CONTENT_URI,
            arrayOf(
                CalendarAlerts._ID, CalendarAlerts.EVENT_ID, CalendarAlerts.ALARM_TIME,
                CalendarAlerts.BEGIN, CalendarAlerts.TITLE, CalendarAlerts.STATE,
            ),
            "${CalendarAlerts.STATE} = ? AND ${CalendarAlerts.ALARM_TIME} <= ?",
            arrayOf(CalendarAlerts.STATE_SCHEDULED.toString(), nowMillis.toString()),
            "${CalendarAlerts.ALARM_TIME} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                alerts += AlertRow(
                    id = cursor.getLong(0),
                    eventId = cursor.getLong(1),
                    alarmTime = cursor.getLong(2),
                    begin = cursor.getLong(3),
                    title = cursor.getNonBlankStringOrNull(4),
                    state = cursor.getInt(5),
                )
            }
        }
        return alerts
    }

    /** Marks an alert as fired, so that the provider doesn't schedule it again. */
    fun markFired(resolver: ContentResolver, alertId: Long, nowMillis: Long) {
        resolver.update(
            ContentUris.withAppendedId(CalendarAlerts.CONTENT_URI, alertId),
            ContentValues().apply {
                put(CalendarAlerts.STATE, CalendarAlerts.STATE_FIRED)
                put(CalendarAlerts.RECEIVED_TIME, nowMillis)
                put(CalendarAlerts.NOTIFY_TIME, nowMillis)
            },
            null,
            null,
        )
    }

    fun viewEventIntent(eventId: Long): Intent =
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(Events.CONTENT_URI, eventId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun insertIntent(extras: Map<String, Any>): Intent =
        Intent(Intent.ACTION_INSERT, Events.CONTENT_URI)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply {
                for ((key, value) in extras) {
                    when (value) {
                        is Long -> putExtra(key, value)
                        is Int -> putExtra(key, value)
                        is Boolean -> putExtra(key, value)
                        else -> putExtra(key, value.toString())
                    }
                }
            }

    /** How [intent] would start without reaching the stock calendar app, see [Handlers.pick]. */
    @SuppressLint("QueryPermissionsNeeded") // GAR can see all apps (QUERY_ALL_PACKAGES)
    fun launchFor(packageManager: PackageManager, intent: Intent): Launch {
        val handlers = packageManager.queryIntentActivities(intent, 0)
            .map { Handler(it.activityInfo.packageName, it.activityInfo.name) }
        val default = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.let { Handler(it.packageName, it.name) }
        return Handlers.pick(default, handlers)
    }

    /** The intent to start for [launch], or null for [Launch.None]. */
    fun intentFor(launch: Launch, intent: Intent): Intent? = when (launch) {
        Launch.AsIs -> intent
        is Launch.Explicit -> Intent(intent).setComponent(
            ComponentName(launch.handler.packageName, launch.handler.className)
        )
        is Launch.Chooser -> Intent.createChooser(intent, null)
            .putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                launch.excluded.map { ComponentName(it.packageName, it.className) }.toTypedArray(),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Launch.None -> null
    }

    /** Starts [intent] in a calendar app other than the stock one; false if there is none. */
    fun start(context: Context, intent: Intent): Boolean {
        val target = intentFor(launchFor(context.packageManager, intent), intent) ?: return false
        return try {
            context.startActivity(target)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Could not open a calendar app", e)
            false
        }
    }

    private fun contentValues(values: Map<String, Any>) = ContentValues().apply {
        for ((key, value) in values) {
            when (value) {
                is Long -> put(key, value)
                is Int -> put(key, value)
                is Boolean -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    private fun Cursor.getNonBlankStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)?.takeIf { it.isNotBlank() }
}
