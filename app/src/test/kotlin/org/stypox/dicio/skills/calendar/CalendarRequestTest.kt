package org.stypox.dicio.skills.calendar

import cc.aaro.gar.router.resolve.ResolvedDate
import cc.aaro.gar.router.resolve.ResolvedDateTime
import cc.aaro.gar.router.resolve.ResolvedTime
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 25, 12, 0)
private val TOMORROW_3PM = ResolvedDateTime(LocalDateTime.of(2026, 10, 26, 15, 0), dateSpoken = true, timeSpoken = true)

class CalendarRequestTest : StringSpec({
    "calendar_add from the model's resolved slots" {
        CalendarRequest.fromSlots(
            mapOf("title" to "a dentist appointment", "when" to TOMORROW_3PM, "duration" to null),
            confirm = false,
            now = NOW,
        ) shouldBe CalendarRequest.AddEvent("Dentist appointment", TOMORROW_3PM, null, null, confirm = false)

        CalendarRequest.fromSlots(
            mapOf("title" to "gym", "when" to TOMORROW_3PM, "duration" to Duration.ofHours(2)),
            confirm = false,
            now = NOW,
        ) shouldBe CalendarRequest.AddEvent("Gym", TOMORROW_3PM, Duration.ofHours(2), null, confirm = false)
    }

    "the fast path's end is placed after the start" {
        val request = CalendarRequest.fromSlots(
            mapOf("title" to "yoga", "when" to TOMORROW_3PM, "duration" to null, "end" to "five"),
            confirm = true,
            end = { start -> start.dateTime.plusHours(2) },
            now = NOW,
        ) as CalendarRequest.AddEvent
        request.end shouldBe LocalDateTime.of(2026, 10, 26, 17, 0)
        request.confirm shouldBe true
    }

    "a date or a bare time becomes a start" {
        val friday = LocalDate.of(2026, 10, 30)
        CalendarRequest.dateTimeOf(ResolvedDate(friday), NOW) shouldBe
                ResolvedDateTime(friday.atStartOfDay(), dateSpoken = true, timeSpoken = false)
        // before noon: tomorrow; after noon: today
        CalendarRequest.dateTimeOf(ResolvedTime(LocalTime.of(9, 0)), NOW) shouldBe
                ResolvedDateTime(LocalDateTime.of(2026, 10, 26, 9, 0), dateSpoken = false, timeSpoken = true)
        CalendarRequest.dateTimeOf(ResolvedTime(LocalTime.of(17, 0)), NOW) shouldBe
                ResolvedDateTime(LocalDateTime.of(2026, 10, 25, 17, 0), dateSpoken = false, timeSpoken = true)
    }

    "reminder_add, also with the time asked for later" {
        CalendarRequest.fromSlots(mapOf("what" to "to call mom", "when" to TOMORROW_3PM), false, now = NOW) shouldBe
                CalendarRequest.AddReminder("Call mom", TOMORROW_3PM, confirm = false)

        CalendarRequest.fromSlots(
            mapOf(
                "what" to "Call mom",
                "day" to ResolvedDate(LocalDate.of(2026, 10, 26)),
                "time" to ResolvedTime(LocalTime.of(15, 0)),
            ),
            confirm = true,
            now = NOW,
        ) shouldBe CalendarRequest.AddReminder("Call mom", TOMORROW_3PM, confirm = true)

        shouldThrow<IllegalArgumentException> {
            CalendarRequest.fromSlots(mapOf("what" to "call mom", "when" to null), false, now = NOW)
        }
    }

    "calendar_query, today when no day was said" {
        CalendarRequest.fromSlots(mapOf("day" to ResolvedDate(LocalDate.of(2026, 10, 30))), false, now = NOW) shouldBe
                CalendarRequest.Query(LocalDate.of(2026, 10, 30))
        CalendarRequest.fromSlots(mapOf("day" to null), false, now = NOW) shouldBe CalendarRequest.Query(null)
        CalendarRequest.fromSlots(mapOf(), false, now = NOW) shouldBe CalendarRequest.Query(null)
    }

    "titles and reminders lose the words around them" {
        CalendarRequest.cleanTitle(" an event called team lunch ") shouldBe "Event called team lunch"
        CalendarRequest.cleanTitle("called team lunch") shouldBe "Team lunch"
        CalendarRequest.cleanTitle("the office party") shouldBe "Office party"
        CalendarRequest.cleanTitle("the") shouldBe "The"
        // the eval's "schedule a meeting with the team on monday at ten"
        CalendarRequest.cleanTitle("schedule a meeting with the team") shouldBe "Meeting with the team"
        CalendarRequest.cleanTitle("book club") shouldBe "Book club"
        CalendarRequest.cleanWhat("that i need to buy milk") shouldBe "Buy milk"
        CalendarRequest.cleanWhat("about the meeting") shouldBe "The meeting"
    }
})
