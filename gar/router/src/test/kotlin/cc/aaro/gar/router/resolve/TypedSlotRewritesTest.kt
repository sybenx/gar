package cc.aaro.gar.router.resolve

import cc.aaro.gar.router.format.testTool
import cc.aaro.gar.router.format.text
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.dicio.numbers.ParserFormatter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

private val NOW = LocalDateTime.of(2026, 10, 25, 14, 30, 15).atZone(ZoneOffset.UTC)
private val resolvers = SlotResolvers(ParserFormatter(Locale.ENGLISH), "en") { NOW }

private fun slot(name: String, kind: SlotKind) = SlotSpec(name, kind, false, "")

class TypedSlotRewritesTest : StringSpec({
    "volume levels are read like the volume sentences read them" {
        for ((raw, level) in listOf(
            "half" to "fifty percent",
            "the middle" to "fifty percent",
            "max" to "one hundred percent",
            "full volume" to "one hundred percent",
            "the maximum" to "one hundred percent",
            "volume thirty percent" to "thirty percent",
            // no level at all: GAR asks for one ("set the volume")
            "the volume" to "",
            "the" to "",
            "my volume" to "",
            "volume to" to "",
            "volume" to "",
            "seven" to "seven",
            "fifty percent" to "fifty percent",
            "to fifty percent" to "to fifty percent",
            "max volume" to "one hundred percent",
            "halfway" to "fifty percent",
            "level five" to "five",
            "sound level ten percent" to "ten percent",
            "full blast" to "full blast",
            // "to" and "at", which the number resolver skips, don't hide max and half
            "to max" to "one hundred percent",
            "to the max" to "one hundred percent",
            "at max" to "one hundred percent",
            "to the maximum" to "one hundred percent",
            "volume to max" to "one hundred percent",
            "to half" to "fifty percent",
        )) {
            withClue(raw) { TypedSlotRewrites.volumeLevel(raw) shouldBe level }
        }
        for (raw in listOf("half", "half way", "the middle")) {
            withClue(raw) { TypedSlotRewrites.isHalfLevel(raw) shouldBe true }
        }
        for (raw in listOf("top", "the top", "full", "Max")) {
            withClue(raw) { TypedSlotRewrites.isMaxLevel(raw) shouldBe true }
        }
        TypedSlotRewrites.isMaxLevel("full blast") shouldBe false
        TypedSlotRewrites.isHalfLevel("half an hour") shouldBe false
        // "half" alone resolves as 0.5, a level of 0.5 out of ten
        resolvers.number("half") shouldBe Resolution.Resolved(ResolvedNumber(0.5, false, false))
    }

    "only the next alarm words mean no time to cancel" {
        TypedSlotRewrites.alarmToCancel("next") shouldBe ""
        TypedSlotRewrites.alarmToCancel("my next alarm") shouldBe ""
        TypedSlotRewrites.alarmToCancel("Upcoming") shouldBe ""
        TypedSlotRewrites.alarmToCancel("next monday at seven") shouldBe "next monday at seven"
        TypedSlotRewrites.alarmToCancel("seven am") shouldBe "seven am"
        // the day of the next alarm ("turn off tomorrow's alarm")
        TypedSlotRewrites.alarmToCancel("tomorrow's alarm") shouldBe ""
        TypedSlotRewrites.alarmToCancel("the alarm for tomorrow") shouldBe ""
        TypedSlotRewrites.alarmToCancel("tonight") shouldBe ""
        TypedSlotRewrites.alarmToCancel("tomorrow at seven") shouldBe "tomorrow at seven"
        TypedSlotRewrites.alarmToCancel("friday's alarm") shouldBe "friday's alarm"
        for (raw in listOf("", "my alarm", "the alarm", "alarm", "first", "next one", "tonight's alarm")) {
            withClue(raw) { TypedSlotRewrites.alarmToCancel(raw) shouldBe "" }
        }
        for (raw in listOf("all", "the seven am one")) {
            withClue(raw) { TypedSlotRewrites.alarmToCancel(raw) shouldBe raw }
        }
    }

    "the next day alone is no time for a new alarm: GAR asks for it" {
        for (raw in listOf(
            "tomorrow", "tomorrow morning", "tonight", "in the morning tomorrow", "for tomorrow", "today",
            "tomorrow's", "for tonight",
        )) {
            withClue(raw) { TypedSlotRewrites.alarmToSet(raw) shouldBe "" }
        }
        for (raw in listOf(
            "seven tomorrow", "friday", "this morning", "morning", "in twenty minutes", "tomorrow at", "the morning",
            "in the morning", "tomorrow early",
        )) {
            withClue(raw) { TypedSlotRewrites.alarmToSet(raw) shouldBe raw }
        }
    }

    "days: words that name no day mean today, the others stay as said" {
        for ((raw, day) in listOf(
            "date" to "",
            "the date" to "",
            "day" to "",
            "the week" to "",
            "day of the month" to "",
            "today's date" to "today",
            "today" to "today",
            "right now" to "",
            "three days" to "in three days",
            "two weeks" to "in two weeks",
            "a week" to "in a week",
            "a month" to "in a month",
            "a year" to "in a year",
            "one day" to "in one day",
            "one week" to "in one week",
            "twenty one days" to "in twenty one days",
            "fifteen days" to "in fifteen days",
            "fourteen days" to "in fourteen days",
            "forty days" to "in forty days",
            "3 days" to "in 3 days",
            "this week" to "",
            "the date today" to "today",
            "today's" to "today",
            "in three days" to "in three days",
            "three days ago" to "three days ago",
            "the day after tomorrow" to "the day after tomorrow",
            "friday" to "friday",
            "france" to "france",
        )) {
            withClue(raw) { TypedSlotRewrites.day(raw) shouldBe day }
        }
        (resolvers.date("in three days") as Resolution.Resolved).value.date shouldBe
            LocalDate.of(2026, 10, 28)
        (resolvers.date("in fifteen days") as Resolution.Resolved).value.date shouldBe
            LocalDate.of(2026, 11, 9)
        (resolvers.date("in a week") as Resolution.Resolved).value.date shouldBe
            LocalDate.of(2026, 11, 1)
        (resolvers.date("today") as Resolution.Resolved).value.date shouldBe LocalDate.of(2026, 10, 25)
    }

    "rewrite picks the rule by tool and slot" {
        val volumeSet = testTool("volume_set", slot("level", SlotKind.NUMBER)).copy(skillId = "volume")
        val alarmCancel = testTool("alarm_cancel", slot("time", SlotKind.TIME)).copy(skillId = "alarm")
        val alarmSet = testTool("alarm_set", slot("time", SlotKind.TIME)).copy(skillId = "alarm")
        val dateQuery = testTool("date_query", slot("day", SlotKind.DATE)).copy(skillId = "date")
        val note = testTool("note", text("a"))
        TypedSlotRewrites.rewrite(volumeSet, volumeSet.slots[0], "max") shouldBe "one hundred percent"
        TypedSlotRewrites.rewrite(alarmCancel, alarmCancel.slots[0], "next") shouldBe ""
        // a new alarm needs a time: "next" stays, and doesn't resolve
        TypedSlotRewrites.rewrite(alarmSet, alarmSet.slots[0], "next") shouldBe "next"
        TypedSlotRewrites.rewrite(alarmSet, alarmSet.slots[0], "tomorrow morning") shouldBe ""
        TypedSlotRewrites.rewrite(alarmCancel, alarmCancel.slots[0], "tomorrow morning") shouldBe
            "tomorrow morning"
        TypedSlotRewrites.rewrite(dateQuery, dateQuery.slots[0], "date") shouldBe ""
        TypedSlotRewrites.rewrite(note, note.slots[0], "date") shouldBe "date"
        // the day of a time of day, only for the calendar's events
        val calendarQuery = testTool("calendar_query", slot("day", SlotKind.DATE)).copy(skillId = "calendar")
        TypedSlotRewrites.rewrite(calendarQuery, calendarQuery.slots[0], "tomorrow morning") shouldBe "tomorrow"
        TypedSlotRewrites.rewrite(calendarQuery, calendarQuery.slots[0], "tonight") shouldBe ""
        TypedSlotRewrites.rewrite(calendarQuery, calendarQuery.slots[0], "the date") shouldBe ""
        TypedSlotRewrites.rewrite(dateQuery, dateQuery.slots[0], "tomorrow morning") shouldBe "tomorrow morning"
        TypedSlotRewrites.rewrite(volumeSet, volumeSet.slots[0], "the") shouldBe ""
    }

    "the day of a time of day, for the calendar's events" {
        for ((raw, day) in listOf(
            "tomorrow morning" to "tomorrow",
            "tomorrow night" to "tomorrow",
            "friday night" to "friday",
            "friday at three" to "friday",
            "tomorrow at 3pm" to "tomorrow",
            "the day after tomorrow in the evening" to "the day after tomorrow",
            "on friday at noon" to "on friday",
            // today: no day
            "tonight" to "",
            "tonight's" to "",
            "this afternoon" to "",
            "this morning" to "",
            "in the evening" to "",
            "at seven" to "",
            "seven pm" to "",
            "noon" to "",
            // no time of day: as said
            "tomorrow" to "tomorrow",
            "friday" to "friday",
            "the fifth" to "the fifth",
            "december twelve" to "december twelve",
            "in two days" to "in two days",
            "seven" to "seven", // the seventh, or seven o'clock: GAR asks
            "france" to "france",
            "seven am and seven pm" to "seven am and seven pm", // not one time
        )) {
            withClue(raw) { TypedSlotRewrites.dayOfTimeOfDay(raw) shouldBe day }
        }
    }
})
