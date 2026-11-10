package org.stypox.dicio.skills.calendar

import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.time.ZoneId

/** A row of the calendar provider's calendars table. */
data class CalendarEntry(
    val id: Long,
    val name: String,
    val accountName: String,
    val accessLevel: Int,
    val visible: Boolean,
    val isPrimary: Boolean,
) {
    /** Visible calendars the user can add events to (contributor access or more). */
    val writable: Boolean get() = visible && accessLevel >= Calendars.CAL_ACCESS_CONTRIBUTOR
}

/** Which calendar new events and reminders go to. */
object CalendarChoice {
    /** The setting's value for "pick one automatically". */
    const val AUTOMATIC = 0L

    private val PERSONAL_NAMES = setOf("personal", "private", "home", "main", "my calendar")
    private val GENERIC_WORDS = listOf("holiday", "birthday", "contacts", "week number")

    fun writable(calendars: List<CalendarEntry>): List<CalendarEntry> = calendars.filter { it.writable }

    /**
     * The calendar chosen in the settings ([savedId]) while it's still writable, otherwise
     * [default]; null if no calendar can take new events.
     */
    fun choose(calendars: List<CalendarEntry>, savedId: Long): CalendarEntry? {
        val writable = writable(calendars)
        return writable.firstOrNull { savedId != AUTOMATIC && it.id == savedId } ?: default(writable)
    }

    /**
     * The first writable calendar, preferring one named like "Personal" and then the account's
     * primary calendar, and avoiding subscriptions like holidays and birthdays.
     */
    fun default(calendars: List<CalendarEntry>): CalendarEntry? =
        writable(calendars).minByOrNull(::rank) // minByOrNull keeps the first of equal ranks

    private fun rank(calendar: CalendarEntry): Int {
        val name = calendar.name.trim().lowercase()
        val personal = name in PERSONAL_NAMES || "personal" in name
        val generic = GENERIC_WORDS.any { it in name }
        return when {
            personal && calendar.isPrimary -> 0
            personal -> 1
            calendar.isPrimary && !generic -> 2
            !generic -> 3
            else -> 4
        }
    }
}

/**
 * The column values and intent extras the calendar skill writes, as plain maps so that they can
 * be unit tested ([CalendarStore] copies them into `ContentValues` and intents).
 */
object CalendarValues {
    /** An `Events` row in [calendarId]; reminders don't make the user look busy. */
    fun event(
        calendarId: Long,
        title: String,
        times: EventTimes,
        zone: ZoneId,
        reminder: Boolean,
    ): Map<String, Any> {
        val values = linkedMapOf<String, Any>(
            Events.CALENDAR_ID to calendarId,
            Events.TITLE to title,
            Events.DTSTART to CalendarTime.providerMillis(times.start, times.allDay, zone, times.offset),
            Events.DTEND to CalendarTime.providerMillis(times.end, times.allDay, zone, times.offset),
            Events.EVENT_TIMEZONE to CalendarTime.providerTimezone(times.allDay, zone),
            Events.ALL_DAY to if (times.allDay) 1 else 0,
        )
        if (reminder) {
            values[Events.AVAILABILITY] = Events.AVAILABILITY_FREE
            values[Events.HAS_ALARM] = 1
        }
        return values
    }

    /** The `Reminders` row that makes the provider alert when the reminder event starts. */
    fun alertAtStart(eventId: Long): Map<String, Any> = linkedMapOf(
        Reminders.EVENT_ID to eventId,
        Reminders.METHOD to Reminders.METHOD_ALERT,
        Reminders.MINUTES to 0,
    )

    /**
     * Extras for `ACTION_INSERT` in a calendar app, used when no calendar is writable. Apps read
     * the times as local times, also for all-day events.
     */
    fun insertExtras(title: String, times: EventTimes, zone: ZoneId): Map<String, Any> = linkedMapOf(
        Events.TITLE to title,
        CalendarContract.EXTRA_EVENT_BEGIN_TIME to
            CalendarTime.providerMillis(times.start, false, zone, times.offset),
        CalendarContract.EXTRA_EVENT_END_TIME to
            CalendarTime.providerMillis(times.end, false, zone, times.offset),
        CalendarContract.EXTRA_EVENT_ALL_DAY to times.allDay,
    )
}

/** An activity or receiver that handles an intent. */
data class Handler(val packageName: String, val className: String)

/** How to start an intent so that it doesn't reach the stock calendar app. */
sealed interface Launch {
    /** The system's default handler is fine: start the intent as it is. */
    data object AsIs : Launch

    data class Explicit(val handler: Handler) : Launch

    /** Several apps can handle it: let the user pick, without [excluded]. */
    data class Chooser(val excluded: List<Handler>) : Launch

    /** Nothing but the stock calendar handles it. */
    data object None : Launch
}

object Handlers {
    /**
     * GrapheneOS' stock calendar: its event editor class is missing (ACTION_INSERT crashes), so
     * it never gets the skill's intents.
     */
    const val STOCK_CALENDAR = "com.android.calendar"
    const val CALENDAR_PROVIDER = "com.android.providers.calendar"

    /**
     * @param defaultHandler what the system would start ([android.content.pm.PackageManager.resolveActivity]),
     * which is the system's chooser when there's no default
     * @param handlers every activity that handles the intent
     */
    fun pick(defaultHandler: Handler?, handlers: List<Handler>): Launch {
        val usable = handlers.filter { it.packageName != STOCK_CALENDAR }
        return when {
            usable.isEmpty() -> Launch.None
            defaultHandler != null && defaultHandler in usable -> Launch.AsIs
            usable.size == 1 -> Launch.Explicit(usable[0])
            else -> Launch.Chooser(handlers.filter { it.packageName == STOCK_CALENDAR })
        }
    }

    /**
     * Whether GAR has to post reminder notifications itself: when nothing but GAR, the stock
     * calendar (whose alert code GrapheneOS removed) and the calendar provider receives
     * `EVENT_REMINDER`. A real calendar app would notify too, so GAR stays quiet then.
     */
    fun garNotifies(reminderReceivers: Collection<String>, ownPackage: String): Boolean =
        reminderReceivers.all { it == ownPackage || it == STOCK_CALENDAR || it == CALENDAR_PROVIDER }
}
