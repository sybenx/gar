package org.stypox.dicio.gar

import cc.aaro.gar.router.resolve.ResolvedDate
import cc.aaro.gar.router.resolve.ResolvedDateTime
import cc.aaro.gar.router.resolve.ResolvedNumber
import cc.aaro.gar.router.resolve.ResolvedTime
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.stypox.dicio.gar.call.GarSentenceBinder
import org.stypox.dicio.gar.call.GarValueFormatter
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.call.parseAmPm
import org.stypox.dicio.gar.call.parseOptionIndex
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import org.dicio.numbers.unit.Duration as DicioDuration

class GarAnswersTest : StringSpec({
    "option positions" {
        parseOptionIndex("the first one", 3) shouldBe 0
        parseOptionIndex("Second.", 3) shouldBe 1
        parseOptionIndex("number three", 3) shouldBe 2
        parseOptionIndex("two", 3) shouldBe 1
        parseOptionIndex("the last one", 3) shouldBe 2
        parseOptionIndex("fourth", 3) shouldBe null
        parseOptionIndex("one more minute", 3) shouldBe null
        parseOptionIndex("pasta", 3) shouldBe null
        parseOptionIndex("1st", 3) shouldBe 0
        parseOptionIndex("the 2nd one", 3) shouldBe 1
        parseOptionIndex("uh number two please", 3) shouldBe 1
        parseOptionIndex("last", 0) shouldBe null
    }

    "positions counted from the last option" {
        parseOptionIndex("the second to last", 3) shouldBe 1
        parseOptionIndex("second to last one", 5) shouldBe 3
        parseOptionIndex("next to last", 3) shouldBe 1
        parseOptionIndex("the one before last", 3) shouldBe 1
        parseOptionIndex("third to last", 3) shouldBe 0
        parseOptionIndex("fourth to last", 3) shouldBe null
        parseOptionIndex("the last one", 1) shouldBe 0
        // five options, so that a wrong reading can't pick the same one by chance
        parseOptionIndex("second from last", 5) shouldBe 3
        parseOptionIndex("third from the last", 5) shouldBe 2
        parseOptionIndex("the second one from last", 5) shouldBe 3
        parseOptionIndex("second from the end", 5) shouldBe 3
        parseOptionIndex("the second last", 5) shouldBe 3
        parseOptionIndex("the last but one", 5) shouldBe 3
        parseOptionIndex("last but two", 5) shouldBe 2
        parseOptionIndex("two before last", 5) shouldBe 2
        parseOptionIndex("the very last one", 5) shouldBe 4
        // numbers in a form this doesn't understand pick nothing, rather than the last option
        parseOptionIndex("two to last", 5) shouldBe null
        parseOptionIndex("two last", 5) shouldBe null
        parseOptionIndex("second before last", 5) shouldBe null
    }

    "positions past five, which a question shows with all its options" {
        parseOptionIndex("six", 6) shouldBe 5
        parseOptionIndex("sixth", 6) shouldBe 5
        parseOptionIndex("the 6th one", 6) shouldBe 5
        parseOptionIndex("number ten", 10) shouldBe 9
        parseOptionIndex("seven", 6) shouldBe null
    }

    "typographic apostrophes inside a word become apostrophes, as in the router's utterances" {
        normalizeAnswer("It’s 7:00 AM!") shouldBe "it's 7 00 am"
        normalizeAnswer("It's  7:00 AM!") shouldBe "it's 7 00 am"
        normalizeAnswer("it‘s") shouldBe "it's"
        normalizeAnswer("itʼs") shouldBe "it's"
        normalizeAnswer("the 90’s") shouldBe "the 90's"
        // quotes around words aren't apostrophes
        normalizeAnswer("‘Cancel’") shouldBe "cancel"
        // unlike the router, punctuation between digits splits them (the router keeps "7:00"):
        // answers are only compared with options normalized the same way
        normalizeAnswer("7:00") shouldBe "7 00"
    }

    "am and pm" {
        parseAmPm("a.m.") shouldBe false
        parseAmPm("in the morning") shouldBe false
        parseAmPm("PM please") shouldBe true
        parseAmPm("tonight") shouldBe true
        parseAmPm("seven") shouldBe null
    }

    "cancel phrases" {
        isCancelPhrase("Cancel!") shouldBe true
        isCancelPhrase("never mind") shouldBe true
        isCancelPhrase("okay forget it") shouldBe true
        isCancelPhrase("stop the timer") shouldBe false
        isCancelPhrase("no") shouldBe false
        isCancelPhrase("Stop it!") shouldBe true
        isCancelPhrase("forget about it please") shouldBe true
        isCancelPhrase("cancel the timer") shouldBe false
    }

    "values are formatted readably, times with am/pm" {
        val formatter = englishFormatter()
        formatter.format(Duration.ofMinutes(90), speech = false) shouldBe "one hour thirty minutes"
        formatter.format(ResolvedNumber(50.0, percent = true, isInteger = true), false) shouldBe "50%"
        formatter.format(SEVEN_AM, speech = false) shouldBe "7:00 AM"
        formatter.format(SEVEN_PM, speech = false) shouldBe "7:00 PM"
        formatter.format(
            ResolvedTime(LocalTime.of(6, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            speech = false,
        ) shouldBe "6:30 AM every Monday, Friday"
        formatter.format(ResolvedDate(TEST_TODAY.plusDays(1)), false) shouldBe "tomorrow"
        formatter.format(
            ResolvedDateTime(TEST_TODAY.plusDays(1).atTime(19, 0), dateSpoken = true, timeSpoken = true),
            speech = false,
        ) shouldBe "tomorrow at 7:00 PM"
        formatter.format(
            ResolvedDateTime(TEST_TODAY.atTime(19, 0), dateSpoken = false, timeSpoken = true),
            speech = false,
        ) shouldBe "7:00 PM"
        formatter.format(null, false) shouldBe null
    }

    "dates a month or more away are never today or tomorrow" {
        // dicio-numbers' niceDate only looks at the days part of the period to now
        val formatter = englishFormatter()
        formatter.format(ResolvedDate(TEST_TODAY.plusMonths(1)), false) shouldBe
            "wednesday, november twenty-fifth, twenty twenty six"
        formatter.format(ResolvedDate(TEST_TODAY.plusMonths(1).plusDays(1)), false) shouldBe
            "thursday, november twenty-sixth, twenty twenty six"
        formatter.format(ResolvedDate(TEST_TODAY.plusYears(1)), false) shouldBe
            "monday, october twenty-fifth, twenty twenty seven"
        // yesterday falls in the same check: the full date
        formatter.format(ResolvedDate(TEST_TODAY.minusDays(1)), false) shouldBe
            "saturday, october twenty-fourth, twenty twenty six"
        // other days this year leave the year out
        val inTwoDays = formatter.format(ResolvedDate(TEST_TODAY.plusDays(2)), false)!!
        inTwoDays shouldStartWith "tuesday"
        inTwoDays shouldNotContain "twenty twenty"
        inTwoDays shouldNotBe "tomorrow"
    }

    "every kind of value is formatted, also those that skills pass" {
        val formatter = englishFormatter()
        formatter.format(ResolvedNumber(50.0, percent = true, isInteger = true), speech = true) shouldBe
            "fifty percent"
        // dicio-numbers shows fractions as such
        formatter.format(ResolvedNumber(2.5, percent = false, isInteger = false), speech = false) shouldBe
            "2 1/2"
        formatter.format(
            ResolvedDateTime(TEST_TODAY.plusDays(1).atTime(19, 0), dateSpoken = false, timeSpoken = true),
            speech = false,
        ) shouldBe "tomorrow at 7:00 PM" // another day: the date is shown even if not said
        formatter.format(
            ResolvedDateTime(TEST_TODAY.plusDays(1).atTime(19, 0), dateSpoken = true, timeSpoken = false),
            speech = false,
        ) shouldBe "tomorrow"
        formatter.format(
            ResolvedTime(LocalTime.of(6, 30), setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)),
            speech = false,
        ) shouldBe "6:30 AM every Monday, Friday"
        formatter.format(DicioDuration(Duration.ofMinutes(10)), false) shouldBe "ten minutes"
        formatter.format(LocalTime.of(19, 0), false) shouldBe "7:00 PM"
        formatter.format(TEST_TODAY.atTime(19, 0), false) shouldBe "today at 7:00 PM"
        formatter.format(TEST_TODAY.plusDays(1), false) shouldBe "tomorrow"
        formatter.format(5, false) shouldBe "5"
    }

    "without dicio-numbers, values fall back to their plain forms" {
        // only for languages dicio-numbers doesn't have, which GAR doesn't route
        val formatter = GarValueFormatter(
            parserFormatter = null,
            locale = Locale.ENGLISH,
            words = GarValueFormatter.Words(
                today = "today",
                tomorrow = "tomorrow",
                dateAtTime = "%1\$s at %2\$s",
                every = "every %1\$s",
                percent = "%1\$s percent",
            ),
            today = { TEST_TODAY },
        )
        formatter.format(Duration.ofMinutes(10), false) shouldBe "PT10M"
        formatter.format(LocalTime.of(19, 0), false) shouldBe "19:00"
        formatter.format(ResolvedNumber(2.5, percent = false, isInteger = false), false) shouldBe "2.5"
        formatter.format(ResolvedNumber(5.0, percent = false, isInteger = true), false) shouldBe "5"
        formatter.format(ResolvedNumber(5.0, percent = true, isInteger = true), true) shouldBe
            "5 percent"
        formatter.format(ResolvedDate(TEST_TODAY.plusDays(3)), false) shouldBe "2026-10-28"
        formatter.format(ResolvedDate(TEST_TODAY), false) shouldBe "today"
    }

    "a time-only value bound to a date_time capture is its next occurrence" {
        val now = LocalDateTime.of(2026, 10, 25, 12, 0)
        GarSentenceBinder.toCaptureValue(SEVEN_PM, "date_time", now) shouldBe
                LocalDateTime.of(2026, 10, 25, 19, 0)
        GarSentenceBinder.toCaptureValue(SEVEN_AM, "date_time", now) shouldBe
                LocalDateTime.of(2026, 10, 26, 7, 0)
    }
})
