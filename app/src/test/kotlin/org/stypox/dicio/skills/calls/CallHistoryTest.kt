package org.stypox.dicio.skills.calls

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** CALLS_NOW in Denver: Tuesday 2026-10-27 at noon. */
private val NOW = CALLS_NOW.atZone(ZoneId.of("America/Denver"))

class CallHistoryTest : StringSpec({
    "the last call that came in, the last one made, the last one" {
        CallHistoryQueries.lastIncoming(SOME_CALLS) shouldBe SOME_CALLS[1]
        CallHistoryQueries.lastOutgoing(SOME_CALLS) shouldBe SOME_CALLS[0]
        CallHistoryQueries.last(SOME_CALLS) shouldBe SOME_CALLS[0]
        val declined = callAt(1, CallKind.DECLINED, "Spam")
        CallHistoryQueries.lastIncoming(listOf(declined) + SOME_CALLS) shouldBe declined
        CallHistoryQueries.lastIncoming(listOf()) shouldBe null
    }

    "missed calls are the new ones and those of the last day" {
        val calls = listOf(
            callAt(10, CallKind.MISSED, "Mom"),
            callAt(20, CallKind.INCOMING, "Dad"),
            callAt(60 * 23, CallKind.MISSED, "Bob"),
            callAt(60 * 25, CallKind.MISSED, "Ann"),
            callAt(60 * 50, CallKind.MISSED, "Zoe", isNew = true),
            callAt(60 * 26, CallKind.DECLINED, "Spam", isNew = true),
        )
        CallHistoryQueries.missed(calls, CALLS_NOW).map { it.name } shouldBe
            listOf("Mom", "Bob", "Zoe")
    }

    "names match on whole words, without case or accents" {
        val calls = listOf(
            callAt(1, CallKind.INCOMING, "Zoë-Anne Müller"),
            callAt(2, CallKind.MISSED, "Mom ❤"),
            callAt(3, CallKind.INCOMING, "John Smith"),
            callAt(4, CallKind.INCOMING, "Johnny"),
            callAt(5, CallKind.INCOMING, null),
        )
        CallHistoryQueries.named(calls, "zoe", cameIn = true) shouldBe calls[0]
        CallHistoryQueries.named(calls, "anne muller", cameIn = true) shouldBe calls[0]
        CallHistoryQueries.named(calls, "mom", cameIn = true) shouldBe calls[1]
        CallHistoryQueries.named(calls, "moms", cameIn = true) shouldBe calls[1]
        CallHistoryQueries.named(calls, "john", cameIn = true) shouldBe calls[2]
        CallHistoryQueries.named(calls, "johnny", cameIn = true) shouldBe calls[3]
        CallHistoryQueries.named(calls, "jon", cameIn = true) shouldBe null
        CallHistoryQueries.named(calls, "mom", cameIn = false) shouldBe null
        CallHistoryQueries.named(calls, "", cameIn = true) shouldBe null
    }

    "the last digits of a number" {
        CallHistoryQueries.lastDigits("+1 (801) 555-0123") shouldBe "0123"
        CallHistoryQueries.lastDigits("911") shouldBe null
        CallerText.spacedDigits("0123") shouldBe "0 1 2 3"
    }

    "when a call was" {
        // noon in Denver; the calls are at Denver's times
        CallTime.of(callAt(5, CallKind.MISSED), NOW) shouldBe CallTime.Today(LocalTime.of(11, 55))
        CallTime.of(callAt(60 * 13, CallKind.MISSED), NOW) shouldBe
            CallTime.Yesterday(LocalTime.of(23, 0))
        CallTime.of(callAt(60 * 24 * 3, CallKind.MISSED), NOW) shouldBe
            CallTime.Weekday(DayOfWeek.SATURDAY, LocalTime.of(12, 0))
        CallTime.of(callAt(60 * 24 * 10, CallKind.MISSED), NOW) shouldBe
            CallTime.OnDate(LocalDate.of(2026, 10, 17))
        // a call after "now" (the clock went back) is today
        CallTime.of(callAt(-30, CallKind.MISSED), NOW) shouldBe CallTime.Today(LocalTime.of(12, 30))
    }

    "missed calls by caller, newest first" {
        val summary = MissedSummary.of(listOf(
            callAt(1, CallKind.MISSED, "Mom"),
            callAt(2, CallKind.MISSED, null, "8015550123"),
            callAt(3, CallKind.MISSED, "mom"),
            callAt(4, CallKind.MISSED, hidden = true),
            callAt(5, CallKind.MISSED, null, "801-555-0123"),
            callAt(6, CallKind.MISSED, hidden = true),
        ))
        summary.count shouldBe 6
        summary.callers.map { (call, count) -> (call.name ?: call.number) to count } shouldBe
            listOf("Mom" to 2, "8015550123" to 2, "" to 2)
        MissedSummary.of(listOf()) shouldBe MissedSummary(0, listOf())
    }
})
