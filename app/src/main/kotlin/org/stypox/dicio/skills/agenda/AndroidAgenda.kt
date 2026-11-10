package org.stypox.dicio.skills.agenda

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import org.stypox.dicio.skills.calendar.CalendarStore
import org.stypox.dicio.skills.calendar.CalendarTime
import org.stypox.dicio.skills.calendar.calendarRemindersDataStore
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The calendar provider through `CalendarContract`, as Etar, Proton Calendar, KashCal and DAVx5's
 * calendars all keep their events there. Instances are read from `Instances` (so repeating events
 * show each time), changes go to the `Events` row as an app (not a sync adapter): the provider
 * marks the row dirty and the calendar's sync adapter sends the change or the deletion (a deleted
 * row stays with `DELETED = 1` until it has synced). GAR's reminders are the events the calendar
 * skill made and tracks (`calendarRemindersDataStore`, see `CalendarReminderNotifier.track`).
 */
class AndroidAgenda(private val context: Context) : Agenda {
    private val resolver = context.contentResolver
    private val zone: ZoneId get() = ZoneId.systemDefault()

    override val allowed: Boolean
        get() = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private suspend fun trackedIds(): Set<Long> =
        context.calendarRemindersDataStore.data.first().remindersList.mapTo(HashSet()) { it.eventId }

    override suspend fun events(from: LocalDateTime, to: LocalDateTime): List<AgendaEvent> {
        val reminders = trackedIds()
        val uri = Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, CalendarTime.providerMillis(from, false, zone))
            ContentUris.appendId(it, CalendarTime.providerMillis(to, false, zone))
            it.build()
        }
        val events = mutableListOf<AgendaEvent>()
        resolver.query(
            uri,
            arrayOf(
                Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
                Instances.EVENT_LOCATION, Instances.CALENDAR_DISPLAY_NAME,
                Instances.CALENDAR_ACCESS_LEVEL, Instances.RRULE, Instances.RDATE,
                Instances.ORIGINAL_ID, Instances.ORIGINAL_SYNC_ID,
            ),
            "${Instances.VISIBLE} = 1 AND (${Instances.STATUS} IS NULL OR " +
                "${Instances.STATUS} != ${Events.STATUS_CANCELED})",
            null,
            "${Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.isNull(2) || cursor.isNull(3) || cursor.getLong(0) in reminders) {
                    continue
                }
                val allDay = !cursor.isNull(4) && cursor.getInt(4) == 1
                events += AgendaEvent(
                    id = cursor.getLong(0),
                    title = cursor.text(1),
                    begin = CalendarTime.fromProviderMillis(cursor.getLong(2), allDay, zone),
                    end = CalendarTime.fromProviderMillis(cursor.getLong(3), allDay, zone),
                    allDay = allDay,
                    location = cursor.text(5),
                    calendarName = cursor.text(6).orEmpty(),
                    writable = !cursor.isNull(7) && cursor.getInt(7) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                    repeats = isOfSeries(cursor.text(8), cursor.text(9), cursor.text(10), cursor.text(11)),
                )
            }
        }
        return events
    }

    override suspend fun reminders(from: LocalDateTime): List<AgendaEvent> {
        val ids = trackedIds()
        if (ids.isEmpty()) {
            return listOf()
        }
        val fromMillis = CalendarTime.providerMillis(from, false, zone)
        return eventRows("${Events._ID} IN (${ids.joinToString(",")}) AND ${Events.DELETED} = 0")
            .filter { CalendarTime.providerMillis(it.begin, it.allDay, zone) >= fromMillis }
            .sortedBy { it.begin }
    }

    override suspend fun move(event: AgendaEvent, begin: LocalDateTime, end: LocalDateTime): AgendaEvent? {
        val values = ContentValues().apply {
            put(Events.DTSTART, CalendarTime.providerMillis(begin, event.allDay, zone))
            put(Events.DTEND, CalendarTime.providerMillis(end, event.allDay, zone))
        }
        val changed = resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, event.id), values, null, null)
        if (changed < 1) {
            return null
        }
        // read back what the provider has now
        return eventRows("${Events._ID} = ${event.id} AND ${Events.DELETED} = 0").firstOrNull()
            ?.let { event.copy(begin = it.begin, end = it.end) }
    }

    override suspend fun delete(event: AgendaEvent): Boolean {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, event.id), null, null)
        val gone = eventRows("${Events._ID} = ${event.id} AND ${Events.DELETED} = 0").isEmpty()
        if (gone && event.id in trackedIds()) {
            context.calendarRemindersDataStore.updateData { reminders ->
                reminders.toBuilder().clearReminders()
                    .addAllReminders(reminders.remindersList.filter { it.eventId != event.id })
                    .build()
            }
        }
        return gone
    }

    override fun open(event: AgendaEvent): Boolean =
        CalendarStore.start(context, CalendarStore.viewEventIntent(event.id))

    /** `Events` rows matching [selection] (only ids GAR built, no user text in it). */
    private fun eventRows(selection: String): List<AgendaEvent> {
        val rows = mutableListOf<AgendaEvent>()
        resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_LOCATION),
            selection,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.isNull(2)) {
                    continue
                }
                val allDay = !cursor.isNull(4) && cursor.getInt(4) == 1
                val begin = CalendarTime.fromProviderMillis(cursor.getLong(2), allDay, zone)
                rows += AgendaEvent(
                    id = cursor.getLong(0),
                    title = cursor.text(1),
                    begin = begin,
                    end = if (cursor.isNull(3)) begin else CalendarTime.fromProviderMillis(cursor.getLong(3), allDay, zone),
                    allDay = allDay,
                    location = cursor.text(5),
                )
            }
        }
        return rows
    }

    companion object {
        /**
         * Whether an event row is one of a series: it repeats (an RRULE or RDATE), or it is one
         * time of a series moved or changed on its own (an exception, with ORIGINAL_ID or, from
         * DAVx5 before the provider links it, only ORIGINAL_SYNC_ID). Deleting an exception's row
         * as an app brings the series' time back, so GAR opens these in the calendar app too.
         */
        fun isOfSeries(rrule: String?, rdate: String?, originalId: String?, originalSyncId: String?): Boolean =
            listOf(rrule, rdate, originalId, originalSyncId).any { !it.isNullOrBlank() }
    }

    private fun Cursor.text(index: Int): String? =
        if (isNull(index)) null else getString(index)?.takeIf { it.isNotBlank() }
}
