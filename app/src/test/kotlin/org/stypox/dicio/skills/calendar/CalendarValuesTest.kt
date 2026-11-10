package org.stypox.dicio.skills.calendar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

private val DENVER: ZoneId = ZoneId.of("America/Denver")
private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
private fun utc(dateTime: LocalDateTime) = dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()

private fun calendar(
    id: Long,
    name: String,
    access: Int = 700,
    visible: Boolean = true,
    primary: Boolean = false,
) = CalendarEntry(id, name, "aaron@example.com", access, visible, primary)

// Aaron's phone: DAVx5 CalDAV calendars, one read-only
private val PHONE = listOf(
    calendar(1, "Federal Holidays", primary = true),
    calendar(2, "work", primary = true),
    calendar(3, "Work Shifts (RotaBot)", access = 200, primary = true),
    calendar(4, "Personal", primary = true),
    calendar(5, "important", primary = true),
)

class CalendarValuesTest : StringSpec({
    "a timed event row" {
        CalendarValues.event(4, "Dentist", EventTimes(at(26, 15), at(26, 16), false), DENVER, false) shouldBe
                mapOf(
                    "calendar_id" to 4L,
                    "title" to "Dentist",
                    "dtstart" to utc(at(26, 21)),
                    "dtend" to utc(at(26, 22)),
                    "eventTimezone" to "America/Denver",
                    "allDay" to 0,
                )
    }

    "an all-day event row is in UTC" {
        CalendarValues.event(4, "Trip", EventTimes(at(26, 0), at(29, 0), true), DENVER, false) shouldBe
                mapOf(
                    "calendar_id" to 4L,
                    "title" to "Trip",
                    "dtstart" to utc(at(26, 0)),
                    "dtend" to utc(at(29, 0)),
                    "eventTimezone" to "UTC",
                    "allDay" to 1,
                )
    }

    "a reminder doesn't make the user busy, and alerts at its start" {
        val values = CalendarValues.event(4, "Call mom", EventTimes(at(25, 17), at(25, 17, 15), false), DENVER, true)
        values["availability"] shouldBe 1 // AVAILABILITY_FREE
        values["hasAlarm"] shouldBe 1
        CalendarValues.alertAtStart(42) shouldBe
                mapOf("event_id" to 42L, "method" to 1 /* METHOD_ALERT */, "minutes" to 0)
    }

    "insert intent extras use local times, also for all-day events" {
        CalendarValues.insertExtras("Trip", EventTimes(at(26, 0), at(27, 0), true), DENVER) shouldBe
                mapOf(
                    "title" to "Trip",
                    "beginTime" to utc(at(26, 6)),
                    "endTime" to utc(at(27, 6)),
                    "allDay" to true,
                )
    }

    "a time the clock shows twice is stored at its offset" {
        // the night the clock goes back in Denver: the second 1:10 am, MST (UTC-7)
        val twice = LocalDateTime.of(2026, 11, 1, 1, 10)
        val times = EventTimes(twice, twice.plusMinutes(15), false, ZoneOffset.ofHours(-7))
        val values = CalendarValues.event(4, "Call mom", times, DENVER, true)
        values["dtstart"] shouldBe utc(LocalDateTime.of(2026, 11, 1, 8, 10))
        values["dtend"] shouldBe utc(LocalDateTime.of(2026, 11, 1, 8, 25))
        val extras = CalendarValues.insertExtras("Call mom", times, DENVER)
        extras["beginTime"] shouldBe utc(LocalDateTime.of(2026, 11, 1, 8, 10))
        extras["endTime"] shouldBe utc(LocalDateTime.of(2026, 11, 1, 8, 25))
    }

    "the default calendar is the writable one named like Personal" {
        CalendarChoice.writable(PHONE).map { it.id } shouldBe listOf(1L, 2L, 4L, 5L)
        CalendarChoice.choose(PHONE, CalendarChoice.AUTOMATIC)?.name shouldBe "Personal"
    }

    "the chosen calendar wins while it's writable" {
        CalendarChoice.choose(PHONE, 2)?.name shouldBe "work"
        // read-only, hidden or deleted: back to the default
        CalendarChoice.choose(PHONE, 3)?.name shouldBe "Personal"
        CalendarChoice.choose(PHONE.map { if (it.id == 2L) it.copy(visible = false) else it }, 2)
            ?.name shouldBe "Personal"
        CalendarChoice.choose(PHONE, 99)?.name shouldBe "Personal"
    }

    "without a personal calendar: primary first, holidays last" {
        CalendarChoice.default(
            listOf(calendar(1, "US Holidays", primary = true), calendar(2, "Team"), calendar(3, "Me", primary = true))
        )?.name shouldBe "Me"
        CalendarChoice.default(listOf(calendar(1, "Birthdays"), calendar(2, "Team")))?.name shouldBe "Team"
        CalendarChoice.default(listOf(calendar(1, "Holidays"), calendar(2, "Read only", access = 200)))
            ?.name shouldBe "Holidays"
        // the first of equal ones
        CalendarChoice.default(listOf(calendar(7, "B"), calendar(3, "A")))?.id shouldBe 7L
    }

    "no writable calendar, no choice" {
        CalendarChoice.choose(listOf(calendar(3, "Shifts", access = 200)), CalendarChoice.AUTOMATIC).shouldBeNull()
        CalendarChoice.choose(listOf(), CalendarChoice.AUTOMATIC).shouldBeNull()
        // contributor access is enough
        CalendarChoice.choose(listOf(calendar(3, "Shared", access = 500)), 0)?.id shouldBe 3L
    }
})

class HandlersTest : StringSpec({
    val stock = Handler("com.android.calendar", "com.android.calendar.EventInfoActivity")
    val etar = Handler("ws.xsoh.etar", "com.android.calendar.EventInfoActivity")
    val proton = Handler("me.proton.android.calendar", "me.proton.android.calendar.MainActivity")
    val resolver = Handler("android", "com.android.internal.app.ResolverActivity")

    "the stock calendar never gets the intent" {
        Handlers.pick(stock, listOf(stock)) shouldBe Launch.None
        Handlers.pick(null, listOf()) shouldBe Launch.None
        Handlers.pick(stock, listOf(stock, etar)) shouldBe Launch.Explicit(etar)
        Handlers.pick(resolver, listOf(stock, etar, proton)) shouldBe Launch.Chooser(listOf(stock))
    }

    "a default other than the stock calendar is kept" {
        Handlers.pick(etar, listOf(stock, etar, proton)) shouldBe Launch.AsIs
        Handlers.pick(resolver, listOf(etar, proton)) shouldBe Launch.Chooser(listOf())
        Handlers.pick(null, listOf(proton)) shouldBe Launch.Explicit(proton)
    }

    "GAR notifies only when no calendar app gets the reminders" {
        val own = "cc.aaro.gar"
        // GrapheneOS without a calendar app
        Handlers.garNotifies(listOf("com.android.calendar", "com.android.providers.calendar", own), own) shouldBe true
        Handlers.garNotifies(listOf(own), own) shouldBe true
        // Etar notifies itself
        Handlers.garNotifies(listOf("com.android.calendar", "ws.xsoh.etar", own), own) shouldBe false
    }
})
