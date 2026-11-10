package org.stypox.dicio.skills.calendar

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration

// no need to use Hilt injection here, let DataStore take care of handling the singletons itself

/** The calendar skill's settings (the target calendar). */
internal val Context.calendarSettingsDataStore by dataStore(
    fileName = "skill_settings_calendar.pb",
    serializer = SkillSettingsCalendarSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { SkillSettingsCalendarSerializer.defaultValue },
)

/** The reminders GAR created, read by [CalendarReminderReceiver]. */
internal val Context.calendarRemindersDataStore by dataStore(
    fileName = "skill_calendar_reminders.pb",
    serializer = CalendarRemindersSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { CalendarRemindersSerializer.defaultValue },
)

object SkillSettingsCalendarSerializer : Serializer<SkillSettingsCalendar> {
    override val defaultValue: SkillSettingsCalendar = SkillSettingsCalendar.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): SkillSettingsCalendar {
        try {
            return SkillSettingsCalendar.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto", exception)
        }
    }

    override suspend fun writeTo(t: SkillSettingsCalendar, output: OutputStream) {
        t.writeTo(output)
    }
}

object CalendarRemindersSerializer : Serializer<CalendarReminders> {
    override val defaultValue: CalendarReminders = CalendarReminders.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): CalendarReminders {
        try {
            return CalendarReminders.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto", exception)
        }
    }

    override suspend fun writeTo(t: CalendarReminders, output: OutputStream) {
        t.writeTo(output)
    }
}

/** A reminder event GAR created, see [ReminderTracking]. */
data class Tracked(val eventId: Long, val alarmTimeMillis: Long)

/** An alert row of the calendar provider (`CalendarAlerts`, joined with its event). */
data class AlertRow(
    val id: Long,
    val eventId: Long,
    val alarmTime: Long,
    val begin: Long,
    val title: String?,
    val state: Int,
)

/**
 * Which reminders GAR notifies for: only events it created itself, whose alert is still
 * scheduled and due. Pure, so that it can be unit tested; [CalendarReminderReceiver] does the I/O.
 */
object ReminderTracking {
    /** Reminders that never fired (the event was moved or deleted) are forgotten after this. */
    val KEEP_AFTER_ALARM: Duration = Duration.ofDays(7)

    /** `CalendarContract.CalendarAlerts.STATE_SCHEDULED` */
    const val STATE_SCHEDULED = 0

    fun toFire(alerts: List<AlertRow>, tracked: Collection<Tracked>, nowMillis: Long): List<AlertRow> {
        val ids = tracked.mapTo(HashSet()) { it.eventId }
        return alerts.filter {
            it.eventId in ids && it.state == STATE_SCHEDULED && it.alarmTime <= nowMillis
        }
    }

    /** [tracked] without the reminders that [fired] and the ones too old to fire anymore. */
    fun prune(tracked: List<Tracked>, fired: Collection<Long>, nowMillis: Long): List<Tracked> =
        tracked.filter {
            it.eventId !in fired && it.alarmTimeMillis + KEEP_AFTER_ALARM.toMillis() >= nowMillis
        }

    /** [tracked] plus a new reminder, pruning old ones on the way. */
    fun add(tracked: List<Tracked>, reminder: Tracked, nowMillis: Long): List<Tracked> =
        prune(tracked.filter { it.eventId != reminder.eventId }, listOf(), nowMillis) + reminder
}
