package org.stypox.dicio.skills.calendar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalDateTime

private val DAY: LocalDate = LocalDate.of(2026, 10, 26)
private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
private fun event(id: Long, begin: LocalDateTime, end: LocalDateTime, allDay: Boolean = false) =
    CalendarEvent(id, "Event $id", begin, end, allDay, null)

class CalendarEventTest : StringSpec({
    "a day lists all-day events first, then by start" {
        val lunch = event(1, at(26, 12), at(26, 13))
        val holiday = event(2, at(26, 0), at(27, 0), allDay = true)
        val dentist = event(3, at(26, 9), at(26, 10))
        val conference = event(4, at(25, 9), at(28, 17)) // runs through the whole day
        CalendarEvent.sortedForDay(listOf(lunch, holiday, dentist, conference), DAY).map { it.id } shouldBe
                listOf(4L, 2L, 3L, 1L)
    }

    "events that started the day before" {
        val flight = event(1, at(25, 22), at(26, 6))
        flight.endsOn(DAY) shouldBe true
        flight.coversWholeDay(DAY) shouldBe false
        event(2, at(25, 22), at(27, 0)).coversWholeDay(DAY) shouldBe true
        event(3, at(26, 9), at(26, 10)).endsOn(DAY) shouldBe false
    }

    "days are said relative to today" {
        DayKind.of(DAY, DAY) shouldBe DayKind.TODAY
        DayKind.of(DAY.plusDays(1), DAY) shouldBe DayKind.TOMORROW
        DayKind.of(DAY.minusDays(1), DAY) shouldBe DayKind.YESTERDAY
        DayKind.of(DAY.plusDays(6), DAY) shouldBe DayKind.THIS_WEEK
        DayKind.of(DAY.plusDays(7), DAY) shouldBe DayKind.OTHER
        DayKind.of(DAY.minusDays(3), DAY) shouldBe DayKind.OTHER
    }
})
