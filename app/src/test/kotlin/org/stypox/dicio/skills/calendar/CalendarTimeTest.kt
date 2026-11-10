package org.stypox.dicio.skills.calendar

import cc.aaro.gar.router.resolve.AmbiguityReason
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.ResolvedDateTime
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
private fun timed(dateTime: LocalDateTime, dateSpoken: Boolean = true) =
    ResolvedDateTime(dateTime, dateSpoken = dateSpoken, timeSpoken = true)
private fun dateOnly(day: Int) =
    ResolvedDateTime(at(day, 14, 37), dateSpoken = true, timeSpoken = false)

private val DENVER: ZoneId = ZoneId.of("America/Denver")

class CalendarTimeTest : StringSpec({
    "a timed event lasts an hour by default, seconds dropped" {
        CalendarTime.eventTimes(timed(at(26, 15, 0).withSecond(42)), null, null) shouldBe
                EventTimes(at(26, 15), at(26, 16), allDay = false)
    }

    "a duration or a later end sets the end" {
        CalendarTime.eventTimes(timed(at(26, 15)), Duration.ofMinutes(90), null) shouldBe
                EventTimes(at(26, 15), at(26, 16, 30), allDay = false)
        CalendarTime.eventTimes(timed(at(26, 15)), Duration.ofMinutes(90), at(26, 17)) shouldBe
                EventTimes(at(26, 15), at(26, 17), allDay = false)
        // an end before the start is ignored
        CalendarTime.eventTimes(timed(at(26, 15)), null, at(26, 14)) shouldBe
                EventTimes(at(26, 15), at(26, 16), allDay = false)
    }

    "only a date makes an all-day event, as long as the duration says" {
        CalendarTime.eventTimes(dateOnly(30), null, null) shouldBe
                EventTimes(at(30, 0), at(31, 0), allDay = true)
        CalendarTime.eventTimes(dateOnly(26), Duration.ofDays(3), null) shouldBe
                EventTimes(at(26, 0), at(29, 0), allDay = true)
        CalendarTime.eventTimes(dateOnly(26), Duration.ofHours(36), null) shouldBe
                EventTimes(at(26, 0), at(28, 0), allDay = true)
        // "for two hours" without a time still takes the day
        CalendarTime.eventTimes(dateOnly(26), Duration.ofHours(2), null) shouldBe
                EventTimes(at(26, 0), at(27, 0), allDay = true)
        CalendarTime.eventTimes(dateOnly(26), null, at(29, 0)) shouldBe
                EventTimes(at(26, 0), at(29, 0), allDay = true)
    }

    "a reminder is a quarter of an hour long" {
        CalendarTime.reminderTimes(at(25, 17, 0).withSecond(30)) shouldBe
                EventTimes(at(25, 17), at(25, 17, 15), allDay = false)
    }

    "an end time without a date is on the start's day, or the next one" {
        val start = timed(at(26, 15))
        val fivePm = timed(at(25, 17), dateSpoken = false) // resolved relative to now
        CalendarTime.endAfter(start, Resolution.Resolved(fivePm)) shouldBe at(26, 17)

        val late = timed(at(26, 22))
        val oneAm = timed(at(26, 1), dateSpoken = false)
        CalendarTime.endAfter(late, Resolution.Resolved(oneAm)) shouldBe at(27, 1)
    }

    "an unclear end takes the first option after the start" {
        val start = timed(at(26, 15))
        val five = Resolution.Ambiguous(
            listOf(timed(at(26, 5), dateSpoken = false), timed(at(26, 17), dateSpoken = false)),
            AmbiguityReason.AM_OR_PM,
        )
        CalendarTime.endAfter(start, five) shouldBe at(26, 17)
        CalendarTime.endAfter(start, Resolution.Unresolvable("no")).shouldBeNull()
    }

    "the end day of an all-day event is included" {
        CalendarTime.endAfter(dateOnly(26), Resolution.Resolved(dateOnly(28))) shouldBe at(29, 0)
        // a time can't end an all-day event, and neither can an earlier day
        CalendarTime.endAfter(dateOnly(26), Resolution.Resolved(timed(at(26, 17)))).shouldBeNull()
        CalendarTime.endAfter(dateOnly(26), Resolution.Resolved(dateOnly(25))).shouldBeNull()
    }

    "the end answers whether the start is am or pm" {
        val three = listOf(timed(at(26, 3), dateSpoken = false), timed(at(26, 15), dateSpoken = false))
        val fivePm = Resolution.Resolved(timed(at(26, 17), dateSpoken = false))
        CalendarTime.startBefore(three, fivePm) shouldBe three[1]
        val fiveAm = Resolution.Resolved(timed(at(26, 5), dateSpoken = false))
        CalendarTime.startBefore(three, fiveAm) shouldBe three[0]
        val twoAm = Resolution.Resolved(timed(at(26, 2), dateSpoken = false))
        CalendarTime.startBefore(three, twoAm).shouldBeNull()
        CalendarTime.startBefore(three, Resolution.Unresolvable("no")).shouldBeNull()
    }

    "provider times: all-day events at midnight UTC, others in the zone" {
        CalendarTime.providerMillis(at(26, 0), allDay = true, DENVER) shouldBe
                at(26, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        CalendarTime.providerTimezone(allDay = true, DENVER) shouldBe "UTC"
        // 3 pm in Denver (MDT, UTC-6) is 9 pm UTC
        CalendarTime.providerMillis(at(26, 15), allDay = false, DENVER) shouldBe
                at(26, 21).toInstant(ZoneOffset.UTC).toEpochMilli()
        CalendarTime.providerTimezone(allDay = false, DENVER) shouldBe "America/Denver"

        for (allDay in listOf(true, false)) {
            val millis = CalendarTime.providerMillis(at(26, if (allDay) 0 else 15), allDay, DENVER)
            CalendarTime.fromProviderMillis(millis, allDay, DENVER) shouldBe at(26, if (allDay) 0 else 15)
        }
    }

    "a time the clock shows twice: the offset picks the moment" {
        // the night the clock goes back in Denver, 1:10 am is MDT (UTC-6), then MST (UTC-7)
        val twice = LocalDateTime.of(2026, 11, 1, 1, 10)
        val mst = ZoneOffset.ofHours(-7)
        CalendarTime.providerMillis(twice, allDay = false, DENVER, mst) shouldBe
                LocalDateTime.of(2026, 11, 1, 8, 10).toInstant(ZoneOffset.UTC).toEpochMilli()
        // without one the earlier, as the provider reads a local time
        CalendarTime.providerMillis(twice, allDay = false, DENVER) shouldBe
                LocalDateTime.of(2026, 11, 1, 7, 10).toInstant(ZoneOffset.UTC).toEpochMilli()

        // events and reminders carry it to the provider
        val second = ResolvedDateTime(twice, dateSpoken = true, timeSpoken = true, offset = mst)
        CalendarTime.eventTimes(second, null, null) shouldBe
                EventTimes(twice, twice.plusHours(1), allDay = false, offset = mst)
        CalendarTime.reminderTimes(twice, mst) shouldBe
                EventTimes(twice, twice.plusMinutes(15), allDay = false, offset = mst)
    }

    "julian days as the provider counts them" {
        CalendarTime.julianDay(LocalDate.of(1970, 1, 1)) shouldBe 2440588
        CalendarTime.julianDay(LocalDate.of(2026, 10, 25)) shouldBe 2440588 + 20751
    }
})
