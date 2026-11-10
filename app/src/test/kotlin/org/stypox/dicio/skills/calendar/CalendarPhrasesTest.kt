package org.stypox.dicio.skills.calendar

import cc.aaro.gar.router.resolve.SlotResolvers
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import java.time.LocalDateTime
import java.time.ZoneOffset

// Sunday noon
private val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 25, 12, 0)
private val phrases = CalendarPhrases(SlotResolvers(ENGLISH_PARSER_FORMATTER, "en") { NOW.atZone(ZoneOffset.UTC) })

class CalendarPhrasesTest : StringSpec({
    "the time and duration at the end of a title are split off" {
        val cases = listOf(
            "dentist tomorrow at three" to
                    mapOf("title" to "dentist", "when" to "tomorrow at three"),
            "dentist appointment tomorrow at three pm for an hour" to
                    mapOf("title" to "dentist appointment", "when" to "tomorrow at three pm", "duration" to "for an hour"),
            "dentist for an hour tomorrow at three" to
                    mapOf("title" to "dentist", "when" to "tomorrow at three", "duration" to "for an hour"),
            "a meeting with john on friday at two" to
                    mapOf("title" to "a meeting with john", "when" to "on friday at two"),
            "team lunch in two hours" to mapOf("title" to "team lunch", "when" to "in two hours"),
            "vacation next monday for three days" to
                    mapOf("title" to "vacation", "when" to "next monday", "duration" to "for three days"),
            "dentist tomorrow from three to five" to
                    mapOf("title" to "dentist", "when" to "tomorrow at three", "end" to "five"),
            "yoga from six to seven pm" to mapOf("title" to "yoga", "when" to "six", "end" to "seven pm"),
            // nothing that looks like a time: it's asked for
            "the office party" to mapOf("title" to "the office party"),
            "look at the stars" to mapOf("title" to "look at the stars"),
            // only a time: the title is asked for
            "tomorrow at three" to mapOf("when" to "tomorrow at three"),
        )
        for ((title, expected) in cases) {
            withClue(title) { phrases.createSlots(title, null, null, null) shouldBe expected }
        }
    }

    "what's said after \"to my calendar\" is the start, with its end or duration" {
        phrases.createSlots("lunch with sam", "friday at noon", null, null) shouldBe
                mapOf("title" to "lunch with sam", "when" to "friday at noon")
        phrases.createSlots("lunch with sam", "on friday from noon to one", null, null) shouldBe
                mapOf("title" to "lunch with sam", "when" to "on friday at noon", "end" to "one")
        phrases.createSlots("gym", "tomorrow at six for two hours", null, null) shouldBe
                mapOf("title" to "gym", "when" to "tomorrow at six", "duration" to "for two hours")
        phrases.createSlots("gym tomorrow at six", "for two hours", null, null) shouldBe
                mapOf("title" to "gym", "when" to "tomorrow at six", "duration" to "for two hours")
    }

    "the sentence's own captures win" {
        // "block two hours for gym tomorrow at five"
        phrases.createSlots("gym tomorrow at five", null, null, "two hours") shouldBe
                mapOf("title" to "gym", "when" to "tomorrow at five", "duration" to "two hours")
    }

    "reminders: the time at the end of what to do" {
        val cases = listOf(
            "call mom at five pm" to mapOf("what" to "call mom", "when" to "at five pm"),
            "call mom" to mapOf("what" to "call mom"),
            "look at the stars at nine" to mapOf("what" to "look at the stars", "when" to "at nine"),
            "call the doctor tomorrow morning" to mapOf("what" to "call the doctor", "when" to "tomorrow morning"),
            "pay rent on the first" to mapOf("what" to "pay rent", "when" to "on the first"),
            "take my pills in the morning" to mapOf("what" to "take my pills", "when" to "in the morning"),
            "check the oven in twenty minutes" to mapOf("what" to "check the oven", "when" to "in twenty minutes"),
            // a number alone is not a time ("chapter seven"), nor "in the dryer"
            "read chapter seven" to mapOf("what" to "read chapter seven"),
            "put the laundry in the dryer" to mapOf("what" to "put the laundry in the dryer"),
            // "remind me tomorrow at five": what is asked for
            "tomorrow at five" to mapOf("when" to "tomorrow at five"),
        )
        for ((what, expected) in cases) {
            withClue(what) { phrases.reminderSlots(what, null) shouldBe expected }
        }
        phrases.reminderSlots("take the pizza out", "in ten minutes") shouldBe
                mapOf("what" to "take the pizza out", "when" to "in ten minutes")
    }

    "query: the day, if any" {
        phrases.querySlots("tomorrow") shouldBe mapOf("day" to "tomorrow")
        phrases.querySlots(" ") shouldBe mapOf()
        phrases.querySlots(null) shouldBe mapOf()
    }

    "ranges need a start and an end that are times" {
        phrases.splitRange("meeting from marketing to sales", requireStart = true).shouldBeNull()
        phrases.splitRange("between two and four", requireStart = true) shouldBe Triple("", "two", "four")
        phrases.splitRange("noon to one", requireStart = false) shouldBe Triple("", "noon", "one")
        phrases.splitRange("noon to one", requireStart = true).shouldBeNull()
    }
})
