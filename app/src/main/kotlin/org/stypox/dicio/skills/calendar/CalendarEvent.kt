package org.stypox.dicio.skills.calendar

import java.time.LocalDate
import java.time.LocalDateTime

/** An event instance read from the calendar provider, with local times. */
data class CalendarEvent(
    val id: Long,
    val title: String?,
    val begin: LocalDateTime,
    /** Exclusive; midnight after the last day for all-day events. */
    val end: LocalDateTime,
    val allDay: Boolean,
    val location: String?,
) {
    /** Whether the event takes all of [day]: all-day events, or ones running through it. */
    fun coversWholeDay(day: LocalDate): Boolean =
        allDay || (!begin.isAfter(day.atStartOfDay()) && !end.isBefore(day.plusDays(1).atStartOfDay()))

    /** Whether the event started on an earlier day and ends during [day]. */
    fun endsOn(day: LocalDate): Boolean =
        !allDay && begin.isBefore(day.atStartOfDay()) && end.isBefore(day.plusDays(1).atStartOfDay())

    companion object {
        /** All-day events first, then by start, as calendar apps list a day. */
        fun sortedForDay(events: List<CalendarEvent>, day: LocalDate): List<CalendarEvent> =
            events.sortedWith(
                compareByDescending<CalendarEvent> { it.coversWholeDay(day) }
                    .thenBy { it.begin }
                    .thenBy { it.title.orEmpty() }
            )
    }
}
