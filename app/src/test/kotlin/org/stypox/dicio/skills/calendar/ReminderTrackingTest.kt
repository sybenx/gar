package org.stypox.dicio.skills.calendar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private const val NOW = 1_800_000_000_000L
private const val MINUTE = 60_000L
private const val DAY = 24 * 60 * MINUTE
private const val SCHEDULED = 0
private const val FIRED = 1

private fun alert(id: Long, eventId: Long, alarmTime: Long, state: Int = SCHEDULED) =
    AlertRow(id, eventId, alarmTime, alarmTime, "Event $eventId", state)

class ReminderTrackingTest : StringSpec({
    "only GAR's own due and scheduled alerts fire" {
        val tracked = listOf(Tracked(10, NOW), Tracked(11, NOW - MINUTE), Tracked(12, NOW + MINUTE))
        val alerts = listOf(
            alert(1, 10, NOW),                    // GAR's, due now
            alert(2, 11, NOW - MINUTE),           // GAR's, a minute late
            alert(3, 12, NOW + MINUTE),           // GAR's, not yet
            alert(4, 20, NOW),                    // another app's event
            alert(5, 10, NOW - DAY, FIRED),       // already fired
        )
        ReminderTracking.toFire(alerts, tracked, NOW).map { it.id } shouldBe listOf(1L, 2L)
        ReminderTracking.toFire(alerts, listOf(), NOW) shouldBe listOf()
    }

    "fired and old reminders are forgotten" {
        val tracked = listOf(
            Tracked(10, NOW),
            Tracked(11, NOW - 8 * DAY), // never fired (moved or deleted), too old
            Tracked(12, NOW - 6 * DAY),
            Tracked(13, NOW + DAY),
        )
        ReminderTracking.prune(tracked, fired = listOf(10L), NOW).map { it.eventId } shouldBe
                listOf(12L, 13L)
    }

    "adding a reminder replaces one with the same event and prunes" {
        val tracked = listOf(Tracked(10, NOW + DAY), Tracked(11, NOW - 30 * DAY))
        ReminderTracking.add(tracked, Tracked(10, NOW + 2 * DAY), NOW) shouldBe
                listOf(Tracked(10, NOW + 2 * DAY))
        ReminderTracking.add(listOf(), Tracked(5, NOW), NOW) shouldBe listOf(Tracked(5, NOW))
    }
})
