package cc.aaro.gar.router

import cc.aaro.gar.router.resolve.EnglishTime
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.dicio.numbers.ParserFormatter
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import java.util.Locale
import org.dicio.numbers.unit.Number as DicioNumber

private fun spoken(written: String) = SpokenForm.of(written).spoken

/** Unit tests run in the module directory; be lenient about the repository root. */
private fun repoFile(path: String): File =
    sequenceOf(File("../../$path"), File(path)).firstOrNull { it.isFile }
        ?: throw AssertionError("Cannot find $path from ${File("").absolutePath}")

/** The `text` of every case in an eval file (both the flow and the block style). */
private fun caseTexts(path: String): List<String> =
    ((Load(LoadSettings.builder().build()).loadFromString(repoFile(path).readText()) as Map<*, *>)["cases"]
        as List<*>).map { (it as Map<*, *>)["text"] as String }

private val english = ParserFormatter(Locale.ENGLISH)

class SpokenFormTest : StringSpec({
    "the golden table" {
        mapOf(
            "Set a timer for 5 minutes." to "set a timer for five minutes",
            "Wake me up at 7:30 a.m." to "wake me up at seven thirty am",
            "Set the volume to 50%." to "set the volume to fifty percent",
            "What's 8 x 7?" to "what's eight times seven",
            "Call 555-1234." to "call five five five one two three four",
            "Turn on Wi-Fi." to "turn on wi fi",
            "[BLANK_AUDIO]" to "",
        ).forEach { (written, expected) -> withClue(written) { spoken(written) shouldBe expected } }
    }

    "cleanup: markers, a dialogue dash, ellipses and apostrophes" {
        mapOf(
            "[BLANK_AUDIO]" to "",
            "(upbeat music)" to "",
            "*sighs* Okay." to "Okay.",
            "♪ la la la ♪" to "",
            "♪ Thank you" to "Thank you",
            "- Hello there." to "Hello there.",
            "Wait... what?" to "Wait what?",
            "Wait… what?" to "Wait what?",
            "What’s up?" to "What's up?",
            "  Set  a timer.  " to "Set a timer.",
            // a minus sign and parentheses with digits stay
            "-5 degrees" to "-5 degrees",
            "Call (435) 555-1234." to "Call (435) 555-1234.",
            "(5 + 3) x 2" to "(5 + 3) x 2",
            "5 * 3 * 2" to "5 * 3 * 2",
        ).forEach { (written, expected) -> withClue(written) { SpokenForm.of(written).written shouldBe expected } }
    }

    "symbols" {
        mapOf(
            "15%" to "fifteen percent",
            "What's 15% of 80?" to "what's fifteen percent of eighty",
            "2.5 %" to "two point five percent",
            "$20" to "twenty dollars",
            "$1" to "one dollar",
            "$4.50" to "four dollars and fifty cents",
            "$0.99" to "ninety nine cents",
            "$1,000" to "one thousand dollars",
            "$2 million" to "two million dollars",
            "$1.5 million" to "one point five million dollars",
            "$5k" to "five thousand dollars",
            "It costs $5." to "it costs five dollars",
            "€3.20" to "three euros and twenty cents",
            "£1.01" to "one pound and one penny",
            "72°F" to "seventy two degrees fahrenheit",
            "-5°C" to "minus five degrees celsius",
            "90°" to "ninety degrees",
            "5 + 3" to "five plus three",
            "5+3" to "five plus three",
            "10 - 4" to "ten minus four",
            "8 × 7" to "eight times seven",
            "8x7" to "eight times seven",
            "6 * 6" to "six times six",
            "20 / 5" to "twenty divided by five",
            "20 ÷ 5" to "twenty divided by five",
            "2^10" to "two to the power of ten",
            "5 + 3 = 8" to "five plus three equals eight",
            "Tom & Jerry" to "tom and jerry",
            "Call room #5" to "call room number five",
            "24/7" to "twenty four seven",
        ).forEach { (written, expected) -> withClue(written) { spoken(written) shouldBe expected } }
    }

    "abbreviations" {
        mapOf(
            "7 a.m." to "seven am",
            "7 A.M." to "seven am",
            "7 p.m. tomorrow" to "seven pm tomorrow",
            "7 AM" to "seven am",
            "OK." to "okay",
            "O.K., thanks" to "okay thanks",
            "ok" to "okay",
            "Okay." to "okay",
            "Mr. Smith" to "mister smith",
            "Mrs. Smith" to "missus smith",
            "Dr. Jones" to "doctor jones",
            "Dr Jones" to "doctor jones",
            // not inside words, nor a lowercase "dr" without its period
            "Drive to the dry cleaner" to "drive to the dry cleaner",
            "take the dr out" to "take the dr out",
            "I am here" to "i am here",
        ).forEach { (written, expected) -> withClue(written) { spoken(written) shouldBe expected } }
    }

    "times" {
        mapOf(
            "7:30" to "seven thirty",
            "7:05" to "seven oh five",
            "7:00" to "seven o'clock",
            "7:00 a.m." to "seven am",
            "7:00 PM" to "seven pm",
            "7:15pm" to "seven fifteen pm",
            "7.30 pm" to "seven thirty pm",
            "Wake me up at 7.30." to "wake me up at seven thirty",
            "remind me until 19.45" to "remind me until nineteen forty five",
            "At 6.05 tomorrow" to "at six oh five tomorrow",
            "Set an alarm for 8.15 tomorrow." to "set an alarm for eight fifteen tomorrow",
            "730 pm" to "seven thirty pm",
            "1130pm" to "eleven thirty pm",
            "7pm" to "seven pm",
            "12:00" to "twelve o'clock",
            "12 noon" to "twelve noon",
            "19:00" to "nineteen hundred",
            "19:45" to "nineteen forty five",
            "0:00" to "midnight",
            "0:15" to "twelve fifteen am",
            // not times
            "7.30" to "seven point three zero",
            "set the volume to 7.30" to "set the volume to seven point three zero",
            "at 7.75" to "at seven point seven five",
            "at 7.305" to "at seven point three zero five",
            "set a timer for 1.30" to "set a timer for one point three zero",
            "25:00" to "twenty five zero zero",
            "13 pm" to "thirteen pm",
        ).forEach { (written, expected) -> withClue(written) { spoken(written) shouldBe expected } }
    }

    "numbers" {
        mapOf(
            "5" to "five",
            "21" to "twenty one",
            "105" to "one hundred and five",
            "1,000" to "one thousand",
            "10,000 steps" to "ten thousand steps",
            "1,234,567" to "one million two hundred and thirty four thousand five hundred and sixty seven",
            "21st" to "twenty first",
            "the 2nd and the 3rd" to "the second and the third",
            "42nd Street" to "forty second street",
            "1.5 hours" to "one and a half hours",
            "2.5 minutes" to "two and a half minutes",
            "3.5" to "three point five",
            "0.25" to "zero point two five",
            "In 1999." to "in nineteen ninety nine",
            "in 2026" to "in twenty twenty six",
            "2005" to "two thousand five",
            "2026 steps" to "two thousand twenty six steps",
            "1500 calories" to "fifteen hundred calories",
            "the 90s" to "the nineties",
            "the 1990s" to "the nineteen nineties",
            "the '80s" to "the eighties",
            "10000" to "ten thousand",
            "4K" to "four k",
            "MP3" to "mp three",
            "COVID-19" to "covid nineteen",
            "twenty-one" to "twenty one",
            "3.5.6" to "three point five six",
        ).forEach { (written, expected) -> withClue(written) { spoken(written) shouldBe expected } }
    }

    "web addresses are said with dot and slash, as Vosk hears them" {
        mapOf(
            "Go to reddit.com." to "go to reddit dot com",
            "Open news.ycombinator.com" to "open news dot ycombinator dot com",
            "Visit aaro.cc" to "visit aaro dot cc",
            "Go to Wikipedia.org/wiki/Cat." to "go to wikipedia dot org slash wiki slash cat",
            "Open my-site.co.uk" to "open my dash site dot co dot uk",
        ).forEach { (written, expected) -> withClue(written) { spoken(written) shouldBe expected } }
        for (written in listOf("Bring snacks, e.g. chips.", "Plug in the 3.5mm jack.", "Email bob@example.com", "The U.S. Open")) {
            withClue(written) { spoken(written).split(" ").contains("dot") shouldBe false }
        }
    }

    "phone-like numbers go digit by digit, as spokenPhoneNumber reads them" {
        mapOf(
            "Call 555-1234." to "call five five five one two three four",
            "Call 1-800-555-1234" to "call one eight zero zero five five five one two three four",
            "(435) 555-1234" to "four three five five five five one two three four",
            "555.123.4567" to "five five five one two three four five six seven",
            "+44 7700 900123" to "plus four four seven seven zero zero nine zero zero one two three",
            "Call +1 (555) 010-0123." to "call plus one five five five zero one zero zero one two three",
            "Dial 911" to "dial nine one one",
            "the 911 operator" to "the nine one one operator",
            "Call 112." to "call one one two",
            "Call 1234" to "call one two three four",
            // not after other words
            "Buy 112 apples" to "buy one hundred and twelve apples",
            "Dial 007" to "dial zero zero seven",
            "12345" to "one two three four five",
            "90210" to "nine zero two one zero",
            // short dashed groups are no phone numbers
            "9-11" to "nine eleven",
            "2-3 minutes" to "two three minutes",
        ).forEach { (written, expected) -> withClue(written) { spoken(written) shouldBe expected } }
    }

    "whole sentences" {
        mapOf(
            "Thank you." to "thank you",
            "Set a timer for 1 hour and 30 minutes." to "set a timer for one hour and thirty minutes",
            "Remind me at 5 p.m. to call Mom." to "remind me at five pm to call mom",
            "Set an alarm for 6:30 every weekday." to "set an alarm for six thirty every weekday",
            "Text John, \"I'm on my way.\"" to "text john i'm on my way",
            "What's the date on the 28th?" to "what's the date on the twenty eighth",
            "Set the brightness to 80%." to "set the brightness to eighty percent",
            "Open YouTube." to "open youtube",
            "Send an SMS to 555-1212 saying call me back." to
                "send an sms to five five five one two one two saying call me back",
            "It's $4.50, OK?" to "it's four dollars and fifty cents okay",
        ).forEach { (written, expected) -> withClue(written) { spoken(written) shouldBe expected } }
    }

    "the spoken form normalizes to itself and has no digits left" {
        val inputs = listOf(
            "Set a timer for 5 minutes.", "7:30 a.m.", "15%", "$4.50", "21st", "8 x 7", "555-1234",
            "Wi-Fi", "OK", "[BLANK_AUDIO]", "+44 7700 900123", "(435) 555-1234", "1.5 hours", "2^10",
            "-5°C", "the '90s", "12345678901234567890", "1,000,000,000,000,000,000", "$99999999999999999",
            "0.000001", "3.5.6", "1:100", "12:345", "5,6", "#", "%", "$", "+", "a+5", "x", "07:30",
        )
        for (input in inputs) {
            withClue(input) {
                val spoken = spoken(input)
                Utterances.normalize(spoken) shouldBe spoken
                spoken.any { it.isDigit() } shouldBe false
            }
        }
    }

    "the eval cases and the ranking test inputs come out unchanged" {
        val texts = caseTexts("gar/eval/cases.yml") + caseTexts("gar/eval/holdout.yml")
        texts shouldHaveAtLeastSize 500
        // the lowercase utterances among GarRankingTest's string literals
        val ranking = Regex("\"([a-z][a-z' ]* [a-z' ]*)\"")
            .findAll(repoFile("app/src/test/kotlin/org/stypox/dicio/gar/GarRankingTest.kt").readText())
            .map { it.groupValues[1] }.toList()
        ranking shouldHaveAtLeastSize 50
        for (text in texts + ranking) {
            withClue(text) {
                val result = SpokenForm.of(text)
                result.spoken shouldBe Utterances.normalize(text)
                result.written shouldBe text
            }
        }
    }

    "dicio-numbers reads the numbers back" {
        // 911 is said digit by digit
        for (n in (0L..10_000L).filter { it != 911L }) {
            withClue(n) {
                val value = english.extractNumber(spoken("$n")).parseFirst() as DicioNumber?
                value.shouldNotBeNull()
                value.equals(n) shouldBe true
            }
        }
        for (n in 1L..31L) {
            val suffix = when {
                n % 10 == 1L && n != 11L -> "st"
                n % 10 == 2L && n != 12L -> "nd"
                n % 10 == 3L && n != 13L -> "rd"
                else -> "th"
            }
            withClue("$n$suffix") {
                val value = english.extractNumber(spoken("the $n$suffix")).preferOrdinal(true).parseFirst()
                    as DicioNumber?
                value.shouldNotBeNull()
                value.equals(n) shouldBe true
                value.isOrdinal shouldBe true
            }
        }
    }

    "EnglishTime reads the times back" {
        for (hour in 0..23) {
            for (minute in 0..59) {
                val mm = minute.toString().padStart(2, '0')
                val cases = mutableListOf("at $hour:$mm" to null as EnglishTime.Meridiem?)
                if (hour in 1..12) {
                    cases += "at $hour:$mm p.m." to EnglishTime.Meridiem.PM
                    cases += "at $hour:$mm AM" to EnglishTime.Meridiem.AM
                }
                for ((written, meridiem) in cases) {
                    withClue(written) {
                        val extraction = EnglishTime.extract(Utterances.words(spoken(written)))
                        val clock = extraction.clock.shouldNotBeNull()
                        extraction.meridiem shouldBe (meridiem ?: if (hour == 0 && minute != 0) {
                            EnglishTime.Meridiem.AM
                        } else {
                            null
                        })
                        val time = EnglishTime.toLocalTime(clock, extraction.meridiem, extraction.partOfDay)
                            ?: EnglishTime.amAndPm(clock).first()
                        val expectedHour = when (meridiem) {
                            EnglishTime.Meridiem.AM -> hour % 12
                            EnglishTime.Meridiem.PM -> hour % 12 + 12
                            null -> hour
                        }
                        // without am/pm, "seven thirty" is 7:30 or 19:30
                        (time.hour % 12) shouldBe (expectedHour % 12)
                        time.minute shouldBe minute
                        if (meridiem != null || hour == 0 || hour >= 12) time.hour shouldBe expectedHour
                    }
                }
            }
        }
    }
})
