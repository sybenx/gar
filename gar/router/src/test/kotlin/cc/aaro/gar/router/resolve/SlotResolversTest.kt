package cc.aaro.gar.router.resolve

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.numbers.ParserFormatter
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

// Sunday 2026-10-25 14:30:15, the reference time of the Step 1 STT run
private val NOW = LocalDateTime.of(2026, 10, 25, 14, 30, 15)
private val english = ParserFormatter(Locale.ENGLISH)
private val italian = ParserFormatter(Locale.ITALIAN)

/** The resolvers at [now] in UTC, which has no daylight saving changes. */
private fun en(now: LocalDateTime = NOW) = SlotResolvers(english, "en") { now.atZone(ZoneOffset.UTC) }

private val ZONED_NOW = NOW.atZone(ZoneOffset.UTC)

private fun at(month: Int, day: Int, hour: Int, minute: Int, year: Int = 2026) =
    LocalDateTime.of(year, month, day, hour, minute)

private fun <T> Resolution<T>.resolved(raw: String): T {
    withClue(raw) { shouldBeInstanceOf<Resolution.Resolved<T>>() }
    return (this as Resolution.Resolved<T>).value
}

private fun <T> Resolution<T>.ambiguous(raw: String): List<T> {
    withClue(raw) { shouldBeInstanceOf<Resolution.Ambiguous<T>>() }
    return (this as Resolution.Ambiguous<T>).options
}

private fun Resolution<*>.unresolvable(raw: String) {
    withClue(raw) { shouldBeInstanceOf<Resolution.Unresolvable>() }
}

class SlotResolversTest : StringSpec({
    "durations dicio-numbers already reads" {
        val r = en()
        mapOf(
            "ten minutes" to Duration.ofMinutes(10),
            "ninety seconds" to Duration.ofSeconds(90),
            "forty five seconds" to Duration.ofSeconds(45),
            "three hours" to Duration.ofHours(3),
            "twenty five minutes" to Duration.ofMinutes(25),
            "one hour and thirty minutes" to Duration.ofMinutes(90),
            "half an hour" to Duration.ofMinutes(30),
            "for five minutes" to Duration.ofMinutes(5),
            "Ten Minutes." to Duration.ofMinutes(10),
            "2 hours" to Duration.ofHours(2),
        ).forEach { (raw, expected) -> withClue(raw) { r.duration(raw).resolved(raw) shouldBe expected } }
    }

    "durations with halves and quarters" {
        val r = en()
        mapOf(
            "an hour and a half" to Duration.ofMinutes(90),
            "a minute and a half" to Duration.ofSeconds(90),
            "two and a half minutes" to Duration.ofSeconds(150),
            "one and a half hours" to Duration.ofMinutes(90),
            "two hours and a half" to Duration.ofMinutes(150),
            "an hour and a quarter" to Duration.ofMinutes(75),
            "a half hour" to Duration.ofMinutes(30),
            "a quarter of an hour" to Duration.ofMinutes(15),
            "quarter hour" to Duration.ofMinutes(15),
            "three quarters of an hour" to Duration.ofMinutes(45),
            "a minute" to Duration.ofMinutes(1),
            "an hour" to Duration.ofHours(1),
            "a day and a half" to Duration.ofHours(36),
            // the model sometimes leaves the article out of the span
            "hour and a half" to Duration.ofMinutes(90),
            "minute and a quarter" to Duration.ofSeconds(75),
        ).forEach { (raw, expected) -> withClue(raw) { r.duration(raw).resolved(raw) shouldBe expected } }
    }

    "durations said the way people ask for more time or read a clock" {
        val r = en()
        mapOf(
            "five more minutes" to Duration.ofMinutes(5),
            "give me five more minutes".removePrefix("give me ") to Duration.ofMinutes(5),
            "another ten minutes" to Duration.ofMinutes(10),
            "an extra minute" to Duration.ofMinutes(1),
            // a number after the unit counts the next smaller one
            "one minute thirty" to Duration.ofSeconds(90),
            "a minute thirty" to Duration.ofSeconds(90),
            "two minutes fifteen" to Duration.ofSeconds(135),
            "an hour fifteen" to Duration.ofMinutes(75),
            "two hours forty five" to Duration.ofMinutes(165),
            "one minute thirty seconds" to Duration.ofSeconds(90),
        ).forEach { (raw, expected) -> withClue(raw) { r.duration(raw).resolved(raw) shouldBe expected } }
        listOf("one minute sixty", "ten seconds five", "a few minutes", "five minutes more or less")
            .forEach { r.duration(it).unresolvable(it) }
    }

    "durations that are not durations, or only partly" {
        val r = en()
        listOf(
            "ten", "pasta", "", "zero minutes", "ten minutes and some", "set a timer",
            "seven fifteen", "tomorrow",
        ).forEach { r.duration(it).unresolvable(it) }
    }

    "spoken clock times without am or pm are ambiguous, never guessed" {
        val r = en()
        mapOf(
            // 07:15 today is past, so the am reading moves to tomorrow
            "seven fifteen" to listOf(at(10, 26, 7, 15), at(10, 25, 19, 15)),
            // "ten/eleven/twelve thirty" are years in dicio-numbers
            "ten thirty" to listOf(at(10, 26, 10, 30), at(10, 25, 22, 30)),
            "eleven thirty" to listOf(at(10, 26, 11, 30), at(10, 25, 23, 30)),
            "at five forty five" to listOf(at(10, 26, 5, 45), at(10, 25, 17, 45)),
            "half past six" to listOf(at(10, 26, 6, 30), at(10, 25, 18, 30)),
            "a quarter to eight" to listOf(at(10, 26, 7, 45), at(10, 25, 19, 45)),
            "ten past nine" to listOf(at(10, 26, 9, 10), at(10, 25, 21, 10)),
            "nine oh five" to listOf(at(10, 26, 9, 5), at(10, 25, 21, 5)),
            "nine o five" to listOf(at(10, 26, 9, 5), at(10, 25, 21, 5)),
            "seven o'clock" to listOf(at(10, 26, 7, 0), at(10, 25, 19, 0)),
            "tomorrow at seven" to listOf(at(10, 26, 7, 0), at(10, 26, 19, 0)),
            "sunday at seven" to listOf(at(11, 1, 7, 0), at(10, 25, 19, 0)),
            "seven" to listOf(at(10, 26, 7, 0), at(10, 25, 19, 0)),
        ).forEach { (raw, expected) ->
            val options = r.dateTime(raw).ambiguous(raw)
            withClue(raw) {
                options.map { it.dateTime } shouldBe expected
                options.all { it.timeSpoken } shouldBe true
            }
        }
        (r.dateTime("seven fifteen") as Resolution.Ambiguous).reason shouldBe AmbiguityReason.AM_OR_PM
    }

    "clock times with am, pm or a part of day" {
        val r = en()
        mapOf(
            "seven pm" to at(10, 25, 19, 0),
            "at seven p m" to at(10, 25, 19, 0),
            "seven fifteen am" to at(10, 26, 7, 15),
            "eleven thirty at night" to at(10, 25, 23, 30),
            "twelve fifteen pm" to at(10, 26, 12, 15),
            "six thirty in the morning" to at(10, 26, 6, 30),
            "half past six in the evening" to at(10, 25, 18, 30),
            "a quarter to eight pm" to at(10, 25, 19, 45),
            "three in the afternoon" to at(10, 25, 15, 0),
            "tonight at eight" to at(10, 25, 20, 0),
            "two at night" to at(10, 26, 2, 0),
            "noon" to at(10, 26, 12, 0),
            "midnight" to at(10, 26, 0, 0),
            "twelve thirty" to at(10, 26, 12, 30),
            "at eighteen thirty" to at(10, 25, 18, 30),
            "nineteen hundred" to at(10, 25, 19, 0),
            "a quarter to midnight" to at(10, 25, 23, 45),
            "7:15pm" to at(10, 25, 19, 15),
            "19:30" to at(10, 25, 19, 30),
            "tomorrow at seven pm" to at(10, 26, 19, 0),
            "tomorrow morning at seven" to at(10, 26, 7, 0),
            "eight tomorrow morning" to at(10, 26, 8, 0),
            "saturday at six thirty pm" to at(10, 31, 18, 30),
            "march fifth at two pm" to at(3, 5, 14, 0, year = 2027),
            "on the fifth at three pm" to at(11, 5, 15, 0),
            "at three pm on the fifth" to at(11, 5, 15, 0),
            // today is Sunday the 25th, 14:30: a passed time on today's weekday or day of the month
            // means the next occurrence, unless the user said "today"
            "sunday at nine am" to at(11, 1, 9, 0),
            "sunday at seven pm" to at(10, 25, 19, 0),
            "the twenty fifth at nine am" to at(11, 25, 9, 0),
            "october twenty fifth at nine am" to at(10, 25, 9, 0, year = 2027),
            "today at nine am" to at(10, 25, 9, 0),
        ).forEach { (raw, expected) ->
            val value = r.dateTime(raw).resolved(raw)
            withClue(raw) {
                value.dateTime shouldBe expected
                value.timeSpoken shouldBe true
            }
        }
    }

    "parts of day without a clock time" {
        val r = en()
        r.dateTime("tomorrow morning").resolved("") shouldBe ResolvedDateTime(
            at(10, 26, 9, 0), dateSpoken = true, timeSpoken = true, approximateTime = true,
        )
        r.dateTime("this evening").resolved("") shouldBe ResolvedDateTime(
            at(10, 25, 19, 0), dateSpoken = false, timeSpoken = true, approximateTime = true,
        )
        r.dateTime("tonight").resolved("").dateTime shouldBe at(10, 25, 21, 0)
        r.dateTime("tonight").resolved("").approximateTime shouldBe true
        r.dateTime("in the morning").resolved("").dateTime shouldBe at(10, 26, 9, 0)
        // a clock time is no approximation, with or without the part of day
        r.dateTime("tonight at nine").resolved("").approximateTime shouldBe false
        r.dateTime("tomorrow at seven pm").resolved("").approximateTime shouldBe false
    }

    "the weekend is its saturday, or today during the weekend" {
        val wednesday = LocalDateTime.of(2026, 10, 28, 10, 0)
        val saturday = LocalDate.of(2026, 10, 31)
        for (raw in listOf("this weekend", "the weekend", "weekend", "this coming weekend", "on the weekend")) {
            withClue(raw) {
                en(wednesday).date(raw).resolved(raw) shouldBe ResolvedDate(saturday)
                en(saturday.atTime(10, 0)).date(raw).resolved(raw) shouldBe ResolvedDate(saturday)
                // NOW is a Sunday
                en().date(raw).resolved(raw) shouldBe ResolvedDate(NOW.toLocalDate())
            }
        }
        en(wednesday).dateTime("this weekend").resolved("") shouldBe ResolvedDateTime(
            saturday.atTime(10, 0), dateSpoken = true, timeSpoken = false,
        )
        en(wednesday).dateTime("this weekend at ten am").resolved("").dateTime shouldBe
            saturday.atTime(10, 0)
        // which weekend "next weekend" is depends on who says it: GAR asks
        en().date("next weekend").unresolvable("")
    }

    "relative offsets are added to now, across midnight too" {
        val r = en()
        r.dateTime("in two hours").resolved("") shouldBe
            ResolvedDateTime(NOW.plusHours(2), dateSpoken = true, timeSpoken = true)
        r.dateTime("two hours from now").resolved("").dateTime shouldBe NOW.plusHours(2)
        r.dateTime("in an hour and a half").resolved("").dateTime shouldBe NOW.plusMinutes(90)
        r.dateTime("in thirty hours").resolved("").dateTime shouldBe NOW.plusHours(30)
        r.dateTime("twenty minutes later").resolved("").dateTime shouldBe NOW.plusMinutes(20)
        r.dateTime("in three days").resolved("") shouldBe
            ResolvedDateTime(NOW.plusDays(3), dateSpoken = true, timeSpoken = false)
        r.dateTime("a week from today").resolved("").dateTime shouldBe NOW.plusWeeks(1)

        val lateNight = LocalDateTime.of(2026, 10, 25, 23, 55)
        en(lateNight).dateTime("in ten minutes").resolved("").dateTime shouldBe
            LocalDateTime.of(2026, 10, 26, 0, 5)
        en(lateNight).dateTime("in two hours").resolved("").dateTime shouldBe
            LocalDateTime.of(2026, 10, 26, 1, 55)
    }

    "dates roll forward to the next occurrence" {
        val r = en()
        val nowTime = LocalTime.of(14, 30)
        mapOf(
            // bare weekdays are in the current Monday-Sunday week in dicio-numbers
            "tuesday" to LocalDate.of(2026, 10, 27),
            "friday" to LocalDate.of(2026, 10, 30),
            "this tuesday" to LocalDate.of(2026, 10, 27),
            "on monday" to LocalDate.of(2026, 10, 26),
            "next tuesday" to LocalDate.of(2026, 10, 27),
            "tomorrow" to LocalDate.of(2026, 10, 26),
            "the fifth" to LocalDate.of(2026, 11, 5),
            "the thirtieth" to LocalDate.of(2026, 10, 30),
            "march fifth" to LocalDate.of(2027, 3, 5),
            "december first" to LocalDate.of(2026, 12, 1),
            "friday the thirteenth" to LocalDate.of(2026, 11, 13),
            "in two weeks" to LocalDate.of(2026, 11, 8),
        ).forEach { (raw, expected) ->
            val value = r.dateTime(raw).resolved(raw)
            withClue(raw) {
                value.dateTime.toLocalDate() shouldBe expected
                value.dateSpoken shouldBe true
                value.timeSpoken shouldBe false
                if (!raw.startsWith("in ")) value.dateTime.toLocalTime() shouldBe nowTime
            }
        }
    }

    "day of month rolls over months that are too short" {
        val r = en(LocalDateTime.of(2026, 11, 5, 10, 0))
        r.date("the thirty first").resolved("").date shouldBe LocalDate.of(2026, 12, 31)
    }

    "date and time slots reject partial parses and garbage" {
        val r = en()
        listOf(
            "", "pasta", "seven fifteen at him", "tomorrow at nine to pay rent", "twenty two eight",
            "seven pasta", "every weekday at seven", "seven am in the evening", "am", "twenty",
            "seven and eight", "twenty past", "half past", "quarter to",
        ).forEach { r.dateTime(it).unresolvable(it) }
        // cut-off phrases are rejected by the parser itself, not by a caught exception
        (r.dateTime("twenty past") as Resolution.Unresolvable).reason shouldNotContain "could not parse"
    }

    "time slots with repetition" {
        val r = en()
        val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
        val all = java.time.DayOfWeek.entries.toSet()
        mapOf(
            "seven am every weekday" to ResolvedTime(LocalTime.of(7, 0), weekdays),
            "every weekday at seven am" to ResolvedTime(LocalTime.of(7, 0), weekdays),
            "seven am on weekdays" to ResolvedTime(LocalTime.of(7, 0), weekdays),
            "every monday and wednesday at six am" to ResolvedTime(LocalTime.of(6, 0), setOf(MONDAY, WEDNESDAY)),
            "every monday wednesday and friday at six am" to
                ResolvedTime(LocalTime.of(6, 0), setOf(MONDAY, WEDNESDAY, FRIDAY)),
            "mondays and wednesdays at nine pm" to ResolvedTime(LocalTime.of(21, 0), setOf(MONDAY, WEDNESDAY)),
            "on mondays at seven am" to ResolvedTime(LocalTime.of(7, 0), setOf(MONDAY)),
            "daily at nine pm" to ResolvedTime(LocalTime.of(21, 0), all),
            "every day at seven pm" to ResolvedTime(LocalTime.of(19, 0), all),
            "weekends at eight thirty am" to ResolvedTime(LocalTime.of(8, 30), setOf(SATURDAY, SUNDAY)),
            "every weekend at nine am" to ResolvedTime(LocalTime.of(9, 0), setOf(SATURDAY, SUNDAY)),
            "every morning at seven" to ResolvedTime(LocalTime.of(7, 0), all),
            "monday to friday at six am" to ResolvedTime(LocalTime.of(6, 0), weekdays),
        ).forEach { (raw, expected) -> withClue(raw) { r.time(raw).resolved(raw) shouldBe expected } }
    }

    "time slots without repetition" {
        val r = en()
        mapOf(
            "six thirty in the morning" to LocalTime.of(6, 30),
            "eleven thirty at night" to LocalTime.of(23, 30),
            "twelve fifteen pm" to LocalTime.of(12, 15),
            "noon" to LocalTime.of(12, 0),
            "midnight" to LocalTime.of(0, 0),
            "7pm" to LocalTime.of(19, 0),
            "19:30" to LocalTime.of(19, 30),
            "twelve o'clock" to LocalTime.of(12, 0),
            "nineteen hundred" to LocalTime.of(19, 0),
            "ten minutes to five pm" to LocalTime.of(16, 50),
            "twenty past seven am" to LocalTime.of(7, 20),
            "tomorrow at seven am" to LocalTime.of(7, 0),
            "tonight at eight" to LocalTime.of(20, 0),
            "for seven a m" to LocalTime.of(7, 0),
        ).forEach { (raw, expected) ->
            withClue(raw) { r.time(raw).resolved(raw) shouldBe ResolvedTime(expected) }
        }
        r.time("half past six").ambiguous("") shouldBe
            listOf(ResolvedTime(LocalTime.of(6, 30)), ResolvedTime(LocalTime.of(18, 30)))
        r.time("ten thirty").ambiguous("") shouldBe
            listOf(ResolvedTime(LocalTime.of(10, 30)), ResolvedTime(LocalTime.of(22, 30)))
        r.time("seven every weekday").ambiguous("").map { it.time } shouldBe
            listOf(LocalTime.of(7, 0), LocalTime.of(19, 0))
        listOf("pasta", "seven pasta", "monday at seven am", "tomorrow", "every", "ten minutes")
            .forEach { r.time(it).unresolvable(it) }
    }

    "time slots said from now are the time of day then, rounded to the minute" {
        // NOW is 14:30:15
        mapOf(
            "in ten minutes" to LocalTime.of(14, 40),
            "in twenty minutes" to LocalTime.of(14, 50),
            "twenty minutes from now" to LocalTime.of(14, 50),
            "in an hour and a half" to LocalTime.of(16, 0),
            "in two hours" to LocalTime.of(16, 30),
        ).forEach { (raw, expected) ->
            withClue(raw) { en().time(raw).resolved(raw) shouldBe ResolvedTime(expected) }
        }
        en(LocalDateTime.of(2026, 10, 25, 23, 55, 40)).time("in ten minutes").resolved("") shouldBe
            ResolvedTime(LocalTime.of(0, 6))
        // an alarm rings within a day
        listOf("in three days", "in twenty four hours", "in thirty hours").forEach {
            en().time(it).unresolvable(it)
        }
        // never the minute that has begun, which the clock app would set for tomorrow
        en(LocalDateTime.of(2026, 10, 25, 14, 30, 0)).time("in ten seconds").resolved("") shouldBe
            ResolvedTime(LocalTime.of(14, 31))
        en().time("in ten seconds").resolved("") shouldBe ResolvedTime(LocalTime.of(14, 31))
    }

    "a duration from now is a duration, not a time of day" {
        val r = en()
        r.duration("twenty minutes from now").resolved("") shouldBe Duration.ofMinutes(20)
        r.duration("ten minutes later").resolved("") shouldBe Duration.ofMinutes(10)
        r.duration("an hour from now").resolved("") shouldBe Duration.ofHours(1)
        listOf("from now", "later", "a bit later").forEach { r.duration(it).unresolvable(it) }
        // as a time of day these stay relative to now
        r.time("ten minutes later").resolved("") shouldBe ResolvedTime(LocalTime.of(14, 40))
    }

    "date slots" {
        val r = en()
        r.date("friday").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 10, 30))
        r.date("march third").resolved("") shouldBe ResolvedDate(LocalDate.of(2027, 3, 3))
        r.date("in two weeks").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 11, 8))
        r.date("tomorrow at seven pm").unresolvable("")
        r.date("seven fifteen").unresolvable("")
        r.date("pasta").unresolvable("")
    }

    "numbers and percentages" {
        val r = en()
        r.number("fifty percent").resolved("") shouldBe ResolvedNumber(50.0, percent = true, isInteger = true)
        r.number("to fifty percent").resolved("") shouldBe ResolvedNumber(50.0, percent = true, isInteger = true)
        r.number("fifty per cent").resolved("") shouldBe ResolvedNumber(50.0, percent = true, isInteger = true)
        r.number("seven").resolved("") shouldBe ResolvedNumber(7.0, percent = false, isInteger = true)
        r.number("two point five").resolved("") shouldBe ResolvedNumber(2.5, percent = false, isInteger = false)
        r.number("a hundred and twenty").resolved("") shouldBe ResolvedNumber(120.0, percent = false, isInteger = true)
        r.number("minus three").resolved("") shouldBe ResolvedNumber(-3.0, percent = false, isInteger = true)
        r.number("42").resolved("") shouldBe ResolvedNumber(42.0, percent = false, isInteger = true)
        listOf("pasta", "", "seven pasta", "percent").forEach { r.number(it).unresolvable(it) }
    }

    "unsupported languages are unresolvable" {
        listOf(SlotResolvers(null, "de") { ZONED_NOW }, SlotResolvers(english, "de") { ZONED_NOW }).forEach { r ->
            r.duration("ten minutes").unresolvable("de")
            r.dateTime("tomorrow").unresolvable("de")
            r.time("seven pm").unresolvable("de")
            r.date("friday").unresolvable("de")
            r.number("seven").unresolvable("de")
        }
        // regional variants of supported languages work
        SlotResolvers(english, "en-US") { ZONED_NOW }.duration("ten minutes").resolved("") shouldBe
            Duration.ofMinutes(10)
    }

    "bare weekdays and dates at the turn of the year" {
        // dicio-numbers puts bare weekdays in the current Monday-Sunday week, which can be last year's
        val sunday = en(LocalDateTime.of(2027, 1, 3, 10, 0))
        for ((raw, expected) in listOf(
            "tuesday" to LocalDate.of(2027, 1, 5),
            "monday" to LocalDate.of(2027, 1, 4),
            "friday" to LocalDate.of(2027, 1, 8),
            "sunday" to LocalDate.of(2027, 1, 3),
        )) {
            withClue(raw) { sunday.date(raw).resolved(raw) shouldBe ResolvedDate(expected) }
        }
        sunday.dateTime("tuesday at nine am").resolved("").dateTime shouldBe at(1, 5, 9, 0, year = 2027)
        en(LocalDateTime.of(2027, 1, 1, 10, 0)).date("thursday").resolved("") shouldBe
            ResolvedDate(LocalDate.of(2027, 1, 7))
        en(LocalDateTime.of(2026, 12, 28, 10, 0)).date("sunday").resolved("") shouldBe
            ResolvedDate(LocalDate.of(2027, 1, 3))

        val newYearsEve = en(LocalDateTime.of(2026, 12, 31, 10, 0))
        for ((raw, expected) in listOf(
            "monday" to LocalDate.of(2027, 1, 4),
            "tomorrow" to LocalDate.of(2027, 1, 1),
            "january first" to LocalDate.of(2027, 1, 1),
            "the first" to LocalDate.of(2027, 1, 1),
        )) {
            withClue(raw) { newYearsEve.date(raw).resolved(raw) shouldBe ResolvedDate(expected) }
        }
        en(LocalDateTime.of(2026, 12, 26, 10, 0)).date("friday").resolved("") shouldBe
            ResolvedDate(LocalDate.of(2027, 1, 1))
        en(LocalDateTime.of(2026, 12, 20, 10, 0)).date("the fifth").resolved("") shouldBe
            ResolvedDate(LocalDate.of(2027, 1, 5))
        en(LocalDateTime.of(2027, 1, 2, 10, 0)).date("this weekend").resolved("") shouldBe
            ResolvedDate(LocalDate.of(2027, 1, 2))
        // a year that was said stays as said, also in the past
        en().date("march fifth 2020").resolved("") shouldBe ResolvedDate(LocalDate.of(2020, 3, 5))
        en().date("march fifth 2028").resolved("") shouldBe ResolvedDate(LocalDate.of(2028, 3, 5))

        val beforeMidnight = en(LocalDateTime.of(2026, 12, 31, 23, 50))
        beforeMidnight.dateTime("in twenty minutes").resolved("").dateTime shouldBe at(1, 1, 0, 10, year = 2027)
        beforeMidnight.dateTime("midnight").resolved("").dateTime shouldBe at(1, 1, 0, 0, year = 2027)
        beforeMidnight.dateTime("seven fifteen").ambiguous("").map { it.dateTime } shouldBe
            listOf(at(1, 1, 7, 15, year = 2027), at(1, 1, 19, 15, year = 2027))
    }

    "leap days and short months" {
        en().date("february twenty ninth").resolved("") shouldBe ResolvedDate(LocalDate.of(2028, 2, 29))
        en(LocalDateTime.of(2028, 2, 10, 10, 0)).date("the twenty ninth").resolved("") shouldBe
            ResolvedDate(LocalDate.of(2028, 2, 29))
        en(LocalDateTime.of(2027, 2, 10, 10, 0)).date("the twenty ninth").resolved("") shouldBe
            ResolvedDate(LocalDate.of(2027, 3, 29))
        // passed on a leap day: the next leap year
        en(LocalDateTime.of(2028, 2, 29, 10, 0)).dateTime("february twenty ninth at nine am").resolved("")
            .dateTime shouldBe at(2, 29, 9, 0, year = 2032)
        en(LocalDateTime.of(2026, 1, 31, 10, 0)).date("the thirtieth").resolved("") shouldBe
            ResolvedDate(LocalDate.of(2026, 3, 30))
        listOf("february thirtieth", "april thirty first", "june thirty first", "the thirty second", "the 45th")
            .forEach { en().date(it).unresolvable(it) }
    }

    "a day said as a number after the month, and a spoken year" {
        for ((raw, expected) in listOf(
            "december twelve" to LocalDate.of(2026, 12, 12),
            "march five" to LocalDate.of(2027, 3, 5),
            "march 5" to LocalDate.of(2027, 3, 5),
            "march fifth twenty twenty eight" to LocalDate.of(2028, 3, 5),
            // already understood before
            "december first" to LocalDate.of(2026, 12, 1),
            "on the fifth of november" to LocalDate.of(2026, 11, 5),
            "november the fifth" to LocalDate.of(2026, 11, 5),
        )) {
            withClue(raw) { en().date(raw).resolved(raw) shouldBe ResolvedDate(expected) }
        }
        en().dateTime("december twelve at nine am").resolved("").dateTime shouldBe at(12, 12, 9, 0)
    }

    "the small hours of a night that was said, and a day's midnight, are after that day" {
        val r = en()
        for ((raw, expected) in listOf(
            "tomorrow night at two" to at(10, 27, 2, 0),
            "friday night at one am" to at(10, 31, 1, 0),
            "saturday night at two am" to at(11, 1, 2, 0),
            "tomorrow night at midnight" to at(10, 27, 0, 0),
            // today is Sunday
            "sunday night at one" to at(10, 26, 1, 0),
            // unchanged: late hours of that night, and nights without a day
            "tomorrow night at eleven" to at(10, 26, 23, 0),
            "tonight at midnight" to at(10, 26, 0, 0),
            "two at night" to at(10, 26, 2, 0),
            // a day's midnight is its end, like the night's, not the one that has passed or is
            // a week away
            "tomorrow at midnight" to at(10, 27, 0, 0),
            "midnight on friday" to at(10, 31, 0, 0),
            "today at midnight" to at(10, 26, 0, 0),
            "sunday at midnight" to at(10, 26, 0, 0),
            "tomorrow at two am" to at(10, 26, 2, 0),
        )) {
            withClue(raw) { r.dateTime(raw).resolved(raw).dateTime shouldBe expected }
        }
        en(LocalDateTime.of(2026, 10, 25, 22, 0)).dateTime("tonight at eleven").resolved("").dateTime shouldBe
            at(10, 25, 23, 0)
    }

    "a part of day that has passed is tomorrow's, with no day said" {
        // CalendarSkill relies on dateSpoken = false to keep a late "tonight" on today
        en(LocalDateTime.of(2026, 10, 25, 22, 0)).dateTime("tonight").resolved("") shouldBe
            ResolvedDateTime(at(10, 26, 21, 0), dateSpoken = false, timeSpoken = true, approximateTime = true)
        en().dateTime("this morning").resolved("") shouldBe
            ResolvedDateTime(at(10, 26, 9, 0), dateSpoken = false, timeSpoken = true, approximateTime = true)
        en().dateTime("saturday night").resolved("") shouldBe
            ResolvedDateTime(at(10, 31, 21, 0), dateSpoken = true, timeSpoken = true, approximateTime = true)
        en().dateTime("tomorrow evening").resolved("") shouldBe
            ResolvedDateTime(at(10, 26, 19, 0), dateSpoken = true, timeSpoken = true, approximateTime = true)
        // "today" is kept as said, so a passed 7 am is one of the choices
        en().dateTime("today at seven").ambiguous("").map { it.dateTime } shouldBe
            listOf(at(10, 25, 7, 0), at(10, 25, 19, 0))
    }

    "ranges are no clock times; to, of, after and before" {
        val r = en()
        listOf("seven to eight", "two to three", "twelve to one", "from five to seven", "nine to five")
            .forEach { r.dateTime(it).unresolvable(it) }
        r.dateTime("five to seven").ambiguous("").map { it.dateTime } shouldBe
            listOf(at(10, 26, 6, 55), at(10, 25, 18, 55))
        r.dateTime("three minutes to eight").ambiguous("").map { it.dateTime } shouldBe
            listOf(at(10, 26, 7, 57), at(10, 25, 19, 57))
        mapOf(
            "quarter of eight" to listOf(LocalTime.of(7, 45), LocalTime.of(19, 45)),
            "five after six" to listOf(LocalTime.of(6, 5), LocalTime.of(18, 5)),
            "ten before seven" to listOf(LocalTime.of(6, 50), LocalTime.of(18, 50)),
            "twenty five to seven" to listOf(LocalTime.of(6, 35), LocalTime.of(18, 35)),
        ).forEach { (raw, expected) -> withClue(raw) { r.time(raw).ambiguous(raw).map { it.time } shouldBe expected } }
        r.time("twenty five past seven pm").resolved("") shouldBe ResolvedTime(LocalTime.of(19, 25))
        // "half to" is no time, with or without the article
        listOf("half to eight", "a half to eight").forEach { r.time(it).unresolvable(it) }
    }

    "twelve, noon, midnight and 24-hour times" {
        val r = en()
        mapOf(
            "twelve am" to LocalTime.of(0, 0),
            "twelve thirty am" to LocalTime.of(0, 30),
            "12am" to LocalTime.of(0, 0),
            "half past twelve" to LocalTime.of(12, 30),
            // twelve is noon, so the hour before one needs no question
            "a quarter to one" to LocalTime.of(12, 45),
            "quarter past noon" to LocalTime.of(12, 15),
            "ten to midnight" to LocalTime.of(23, 50),
            "seven hundred" to LocalTime.of(7, 0),
            "twenty three hundred hours" to LocalTime.of(23, 0),
            "at twenty" to LocalTime.of(20, 0),
            "0:30" to LocalTime.of(0, 30),
        ).forEach { (raw, expected) -> withClue(raw) { r.time(raw).resolved(raw) shouldBe ResolvedTime(expected) } }
        // a year without "at"; a leading zero doesn't make a 24-hour time
        listOf("nineteen thirty", "zero seven hundred").forEach { r.time(it).unresolvable(it) }
        r.time("07:00").ambiguous("").map { it.time } shouldBe listOf(LocalTime.of(7, 0), LocalTime.of(19, 0))
        r.dateTime("noon tomorrow").resolved("").dateTime shouldBe at(10, 26, 12, 0)
        r.dateTime("tomorrow at noon").resolved("").dateTime shouldBe at(10, 26, 12, 0)
        // contradictions
        listOf("nineteen hundred in the morning", "at eighteen thirty in the morning", "seven pm in the morning")
            .forEach { r.dateTime(it).unresolvable(it) }
        (r.dateTime("at seven am and seven pm") as Resolution.Unresolvable).reason shouldContain "both am and pm"
        (r.dateTime("seven fifteen and eight") as Resolution.Unresolvable).reason shouldContain "two times"
    }

    "repetitions: more spellings, and what isn't one" {
        val r = en()
        val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
        val all = java.time.DayOfWeek.entries.toSet()
        mapOf(
            "monday through friday at six am" to ResolvedTime(LocalTime.of(6, 0), weekdays),
            "six am from monday to friday" to ResolvedTime(LocalTime.of(6, 0), weekdays),
            "from monday through friday at six am" to ResolvedTime(LocalTime.of(6, 0), weekdays),
            "friday to monday at nine am" to ResolvedTime(LocalTime.of(9, 0), setOf(FRIDAY, SATURDAY, SUNDAY, MONDAY)),
            "everyday at six am" to ResolvedTime(LocalTime.of(6, 0), all),
            "each day at six am" to ResolvedTime(LocalTime.of(6, 0), all),
            "every single day at six am" to ResolvedTime(LocalTime.of(6, 0), all),
            "every week day at six am" to ResolvedTime(LocalTime.of(6, 0), weekdays),
            "on weekends at nine am" to ResolvedTime(LocalTime.of(9, 0), setOf(SATURDAY, SUNDAY)),
            "mondays wednesdays and fridays at seven am" to
                ResolvedTime(LocalTime.of(7, 0), setOf(MONDAY, WEDNESDAY, FRIDAY)),
        ).forEach { (raw, expected) -> withClue(raw) { r.time(raw).resolved(raw) shouldBe expected } }
        r.time("every day at seven").ambiguous("") shouldBe
            listOf(ResolvedTime(LocalTime.of(7, 0), all), ResolvedTime(LocalTime.of(19, 0), all))
        listOf(
            "every other monday at seven am", "every two days at seven am", "monday and wednesday at seven am",
            "seven am on sunday", "every morning at seven pm", "every evening", "weekday mornings at seven",
        ).forEach { r.time(it).unresolvable(it) }
        (r.time("monday and wednesday at seven am") as Resolution.Unresolvable).reason shouldContain
            "a single day is not a repetition"
    }

    "more durations, and what isn't one" {
        val r = en()
        mapOf(
            "an hour and twenty minutes" to Duration.ofMinutes(80),
            "two minutes ten seconds" to Duration.ofSeconds(130),
            "an hour fifteen minutes" to Duration.ofMinutes(75),
            "a week" to Duration.ofHours(168),
            "a week and a half" to Duration.ofHours(252),
            "1 hour 30 minutes" to Duration.ofMinutes(90),
            "10 mins" to Duration.ofMinutes(10),
            "2 hrs" to Duration.ofHours(2),
            "five minutes and a half" to Duration.ofSeconds(330),
            "two and a quarter hours" to Duration.ofMinutes(135),
            "one hundred and twenty seconds" to Duration.ofMinutes(2),
            "a couple of minutes" to Duration.ofMinutes(2),
            "two hours and a half from now" to Duration.ofMinutes(150),
            // the unit said again after the fraction is the same unit
            "an hour and a half hour" to Duration.ofMinutes(90),
            "hour and a half hour" to Duration.ofMinutes(90),
            "two hours and a half hours" to Duration.ofMinutes(150),
            // repeats are summed
            "ten minutes ten minutes" to Duration.ofMinutes(20),
            // dicio-numbers' average month
            "a month" to Duration.ofHours(730).plusMinutes(29).plusSeconds(6),
        ).forEach { (raw, expected) -> withClue(raw) { r.duration(raw).resolved(raw) shouldBe expected } }
        // ranges are never guessed
        listOf(
            "a minute or two", "five or ten minutes", "for an hour or two", "half a second", "1:30",
            "three quarter hour", "a couple",
        ).forEach { r.duration(it).unresolvable(it) }
        (r.duration("minus five minutes") as Resolution.Unresolvable).reason shouldContain "not a positive duration"
    }

    "numbers through dicio-numbers" {
        val r = en()
        mapOf(
            "a dozen" to ResolvedNumber(12.0, percent = false, isInteger = true),
            "number seven" to ResolvedNumber(7.0, percent = false, isInteger = true),
            "zero" to ResolvedNumber(0.0, percent = false, isInteger = true),
            "point five" to ResolvedNumber(0.5, percent = false, isInteger = false),
            "two percent" to ResolvedNumber(2.0, percent = true, isInteger = true),
            "fifty five percent" to ResolvedNumber(55.0, percent = true, isInteger = true),
            // normalization drops the sign
            "50%" to ResolvedNumber(50.0, percent = false, isInteger = true),
            // dicio-numbers' year reading
            "ten and twenty" to ResolvedNumber(1020.0, percent = false, isInteger = true),
            "ten twenty" to ResolvedNumber(1020.0, percent = false, isInteger = true),
            "nineteen thirty" to ResolvedNumber(1930.0, percent = false, isInteger = true),
        ).forEach { (raw, expected) -> withClue(raw) { r.number(raw).resolved(raw) shouldBe expected } }
        listOf("ten to twenty", "ten or eleven", "one and a half", "level five", "five out of ten", "minus")
            .forEach { r.number(it).unresolvable(it) }
    }

    "offsets with a clock time, refusals, and past days kept as said" {
        val r = en()
        r.dateTime("in two weeks at nine am").resolved("").dateTime shouldBe at(11, 8, 9, 0)
        r.dateTime("two weeks from now at noon").resolved("").dateTime shouldBe at(11, 8, 12, 0)
        r.dateTime("a week from today at nine am").resolved("").dateTime shouldBe at(11, 1, 9, 0)
        r.dateTime("in three days at seven").ambiguous("").map { it.dateTime } shouldBe
            listOf(at(10, 28, 7, 0), at(10, 28, 19, 0))
        listOf(
            "after five", "in five", "later", "after lunch", "a week from friday", "the fifth of next month",
            "tomorrow friday",
        ).forEach { r.dateTime(it).unresolvable(it) }
        r.dateTime("yesterday at five pm").resolved("").dateTime shouldBe at(10, 24, 17, 0)
        for ((raw, expected) in listOf(
            "last friday" to LocalDate.of(2026, 10, 23),
            "three days ago" to LocalDate.of(2026, 10, 22),
            "the day before yesterday" to LocalDate.of(2026, 10, 23),
            "the day after tomorrow" to LocalDate.of(2026, 10, 27),
        )) {
            withClue(raw) { r.date(raw).resolved(raw) shouldBe ResolvedDate(expected) }
        }
    }

    "this, next and coming weekdays as dicio-numbers reads them" {
        val saturday = en(LocalDateTime.of(2026, 10, 31, 10, 0))
        for (raw in listOf("friday", "this friday", "next friday", "this coming friday")) {
            withClue(raw) { saturday.date(raw).resolved(raw) shouldBe ResolvedDate(LocalDate.of(2026, 11, 6)) }
        }
        val monday = en(LocalDateTime.of(2026, 10, 26, 10, 0))
        monday.date("next monday").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 11, 2))
        monday.date("this monday").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 10, 26))
        monday.date("next tuesday").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 10, 27))
        en().date("next sunday").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 11, 1))
        en().date("sunday").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 10, 25))
        // Sunday afternoon: this weekend's 9 am has passed, and "coming" asks for the next one too
        en().dateTime("this weekend at nine am").resolved("").dateTime shouldBe at(10, 31, 9, 0)
        en().dateTime("this coming weekend at nine am").resolved("").dateTime shouldBe at(10, 31, 9, 0)
        en().dateTime("this coming sunday at nine am").resolved("").dateTime shouldBe at(11, 1, 9, 0)
        // "next" stays as dicio-numbers reads it
        en().dateTime("next sunday at nine am").resolved("").dateTime shouldBe at(11, 1, 9, 0)
    }

    "months and years from now are calendar months and years, with no time said" {
        val r = en()
        r.dateTime("in a month").resolved("") shouldBe
            ResolvedDateTime(NOW.plusMonths(1), dateSpoken = true, timeSpoken = false)
        r.dateTime("in a year").resolved("") shouldBe
            ResolvedDateTime(NOW.plusYears(1), dateSpoken = true, timeSpoken = false)
        r.dateTime("in two months").resolved("").dateTime shouldBe NOW.plusMonths(2)
        r.dateTime("a month from now").resolved("").dateTime shouldBe NOW.plusMonths(1)
        r.dateTime("in a week").resolved("") shouldBe
            ResolvedDateTime(NOW.plusWeeks(1), dateSpoken = true, timeSpoken = false)
        // from the end of January: the end of February, not March
        en(LocalDateTime.of(2027, 1, 31, 10, 0)).dateTime("in a month").resolved("").dateTime shouldBe
            LocalDateTime.of(2027, 2, 28, 10, 0)
        r.date("in a month").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 11, 25))
        r.dateTime("in a month at nine am").resolved("").dateTime shouldBe at(11, 25, 9, 0)
        r.time("in a month").unresolvable("")
    }

    "an exception while resolving is unresolvable, and language codes" {
        val broken = SlotResolvers(english, "en") { throw IllegalStateException("clock broke") }
        broken.dateTime("tomorrow") shouldBe Resolution.Unresolvable("could not parse: clock broke")
        broken.time("seven pm") shouldBe Resolution.Unresolvable("could not parse: clock broke")
        broken.date("friday") shouldBe Resolution.Unresolvable("could not parse: clock broke")
        // these don't read the clock
        broken.duration("ten minutes").resolved("") shouldBe Duration.ofMinutes(10)
        broken.number("seven").resolved("").value shouldBe 7.0

        for (language in listOf("EN", "en_GB", "it-IT")) {
            withClue(language) { SlotResolvers(english, language) { ZONED_NOW }.supportsLanguage shouldBe true }
        }
        for (language in listOf("", "eng")) {
            withClue(language) { SlotResolvers(english, language) { ZONED_NOW }.supportsLanguage shouldBe false }
        }
    }

    "in N minutes across a daylight saving change is that much real time" {
        val denver = ZoneId.of("America/Denver")
        fun inDenver(now: ZonedDateTime) = SlotResolvers(english, "en") { now }
        // the clock goes back at 2 am on 2026-11-01, so 1:50 am comes twice
        val fallBack = LocalDateTime.of(2026, 11, 1, 1, 50)
        val firstTime = ZonedDateTime.ofLocal(fallBack, denver, ZoneOffset.ofHours(-6))
        val secondTime = ZonedDateTime.ofLocal(fallBack, denver, ZoneOffset.ofHours(-7))
        for (now in listOf(firstTime, secondTime)) {
            val then = inDenver(now).dateTime("in twenty minutes").resolved("")
            withClue(now) { Duration.between(now, then.atZone(denver)).toMinutes() shouldBe 20L }
        }
        // twenty minutes after the first 1:50 am is the second 1:10 am, which the offset tells
        inDenver(firstTime).dateTime("in twenty minutes").resolved("") shouldBe ResolvedDateTime(
            LocalDateTime.of(2026, 11, 1, 1, 10), dateSpoken = true, timeSpoken = true,
            offset = ZoneOffset.ofHours(-7),
        )
        inDenver(secondTime).dateTime("in twenty minutes").resolved("") shouldBe ResolvedDateTime(
            LocalDateTime.of(2026, 11, 1, 2, 10), dateSpoken = true, timeSpoken = true,
        )
        // an alarm's time of day then
        inDenver(firstTime).time("in twenty minutes").resolved("") shouldBe ResolvedTime(LocalTime.of(1, 10))
        inDenver(secondTime).time("in twenty minutes").resolved("") shouldBe ResolvedTime(LocalTime.of(2, 10))
        inDenver(firstTime).time("in ten seconds").resolved("") shouldBe ResolvedTime(LocalTime.of(1, 51))

        // the clock goes forward at 2 am on 2027-03-14
        val springForward = ZonedDateTime.of(LocalDateTime.of(2027, 3, 14, 1, 30), denver)
        val later = inDenver(springForward).dateTime("in two hours").resolved("")
        later shouldBe ResolvedDateTime(LocalDateTime.of(2027, 3, 14, 4, 30), dateSpoken = true, timeSpoken = true)
        Duration.between(springForward, later.atZone(denver)).toMinutes() shouldBe 120L
        // days are calendar days, at the same time of day, as intended
        val friday = ZonedDateTime.of(LocalDateTime.of(2026, 10, 30, 9, 0), denver)
        inDenver(friday).dateTime("in three days").resolved("").dateTime shouldBe LocalDateTime.of(2026, 11, 2, 9, 0)
        inDenver(friday).date("in three days").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 11, 2))
        // a time without an offset is the earlier of the two, as the calendar provider reads it
        ResolvedDateTime(fallBack, dateSpoken = true, timeSpoken = true).atZone(denver) shouldBe firstTime
    }

    "a month without its day is every day of it: GAR asks which day" {
        val r = en()
        val december = r.dateTime("december")
        december.shouldBeInstanceOf<Resolution.Ambiguous<ResolvedDateTime>>().reason shouldBe
            AmbiguityReason.DAY_OF_MONTH
        val options = december.ambiguous("december")
        options.map { it.dateTime.toLocalDate() } shouldBe (1..31).map { LocalDate.of(2026, 12, it) }
        options.all { it.dateSpoken && !it.timeSpoken } shouldBe true
        r.date("december").ambiguous("december") shouldBe (1..31).map { ResolvedDate(LocalDate.of(2026, 12, it)) }

        // dicio-numbers read these as the 1st or as today's day of the month
        for ((raw, month) in listOf(
            "in december" to YearMonth.of(2026, 12),
            "on december" to YearMonth.of(2026, 12),
            "for december" to YearMonth.of(2026, 12),
            "next month" to YearMonth.of(2026, 11),
            "december 2027" to YearMonth.of(2027, 12),
            "december twenty twenty eight" to YearMonth.of(2028, 12),
            // rolls forward like a date
            "january" to YearMonth.of(2027, 1),
        )) {
            withClue(raw) {
                r.date(raw).ambiguous(raw).map { YearMonth.from(it.date) }.distinct() shouldBe listOf(month)
            }
        }
        // this month: from today on, also after "in" or "on" (to dicio-numbers a year from now)
        for (raw in listOf("october", "in october", "on october")) {
            withClue(raw) {
                r.date(raw).ambiguous(raw) shouldBe (25..31).map { ResolvedDate(LocalDate.of(2026, 10, it)) }
            }
        }
        // "in january" said in December is next January
        en(LocalDateTime.of(2026, 12, 10, 9, 0)).date("in january").ambiguous("")
            .map { YearMonth.from(it.date) }.distinct() shouldBe listOf(YearMonth.of(2027, 1))
        // on the 15th, the other reference day is the 14th
        en(LocalDateTime.of(2026, 10, 15, 9, 0)).date("next month").ambiguous("") shouldBe
            (1..30).map { ResolvedDate(LocalDate.of(2026, 11, it)) }
        // only today is left: today
        en(LocalDateTime.of(2026, 10, 31, 9, 0)).date("october").resolved("") shouldBe
            ResolvedDate(LocalDate.of(2026, 10, 31))

        // with a time of day: each day at that time, and both readings when am or pm is unclear
        val nineAm = r.dateTime("december at nine am").ambiguous("")
        nineAm.size shouldBe 31
        nineAm.first() shouldBe ResolvedDateTime(at(12, 1, 9, 0), dateSpoken = true, timeSpoken = true)
        r.dateTime("in december at seven").ambiguous("").take(2).map { it.dateTime } shouldBe
            listOf(at(12, 1, 7, 0), at(12, 1, 19, 0))
        // the end of each day is the next day's start, which a picked day can't tell apart
        r.dateTime("december at midnight").unresolvable("")
        // as a day, a month with a time is a time
        r.date("december at nine am").unresolvable("")

        // a day said is one day, also the first
        for ((raw, day) in listOf(
            "december twelve" to LocalDate.of(2026, 12, 12),
            "december first" to LocalDate.of(2026, 12, 1),
            "december one" to LocalDate.of(2026, 12, 1),
            "the first of december" to LocalDate.of(2026, 12, 1),
            "december 1" to LocalDate.of(2026, 12, 1),
            "the fifth of december 2026" to LocalDate.of(2026, 12, 5),
            "december the fifth 2026" to LocalDate.of(2026, 12, 5),
            // a cardinal after "the": dicio-numbers reads it as an hour on the 1st
            "the 12 of december" to LocalDate.of(2026, 12, 12),
            "the twelve of december" to LocalDate.of(2026, 12, 12),
            "on the 12 of december" to LocalDate.of(2026, 12, 12),
            "the twenty one of december" to LocalDate.of(2026, 12, 21),
            "the thirty one of december" to LocalDate.of(2026, 12, 31),
            "the 5 of december 2026" to LocalDate.of(2026, 12, 5),
            "the 5 of january" to LocalDate.of(2027, 1, 5),
        )) {
            withClue(raw) { r.date(raw).resolved(raw) shouldBe ResolvedDate(day) }
        }
        // offsets of months are days: a month from today, a month ago
        r.date("in a month").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 11, 25))
        r.date("a month from now").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 11, 25))
        r.date("one month ago").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 9, 25))
        r.date("1 month ago").resolved("") shouldBe ResolvedDate(LocalDate.of(2026, 9, 25))
        // an alarm's time never takes a month
        r.time("seven am in december").unresolvable("")
    }

    "a day's midnight is the end of that day, as the user confirmed" {
        // gar/DESIGN.md, "Resolvers": "tomorrow at midnight" is the night after tomorrow
        val r = en()
        r.dateTime("tomorrow at midnight").resolved("").dateTime shouldBe at(10, 27, 0, 0)
        r.dateTime("friday at midnight").resolved("").dateTime shouldBe at(10, 31, 0, 0)
        r.dateTime("december twelve at midnight").resolved("").dateTime shouldBe at(12, 13, 0, 0)
        // without a day, the next midnight
        r.dateTime("midnight").resolved("").dateTime shouldBe at(10, 26, 0, 0)
    }

    "italian time slots" {
        val r = SlotResolvers(italian, "it") { ZONED_NOW }
        // a passed time of day moves to tomorrow, with no am/pm question
        r.dateTime("alle sette").resolved("").dateTime shouldBe at(10, 26, 7, 0)
        r.time("alle sette").resolved("") shouldBe ResolvedTime(LocalTime.of(7, 0))
        // unlike English, a day in a time slot is not dropped
        listOf("domani alle sette", "oggi alle sette").forEach { r.time(it).unresolvable(it) }
        en().time("tomorrow at seven am").resolved("") shouldBe ResolvedTime(LocalTime.of(7, 0))
    }

    "italian uses dicio-numbers with the whole-slot check" {
        val r = SlotResolvers(italian, "it") { ZONED_NOW }
        r.duration("dieci minuti").resolved("") shouldBe Duration.ofMinutes(10)
        r.number("cinquanta percento").resolved("") shouldBe ResolvedNumber(50.0, percent = true, isInteger = true)
        val tomorrow = r.dateTime("domani").resolved("")
        tomorrow.dateTime.toLocalDate() shouldBe LocalDate.of(2026, 10, 26)
        tomorrow.dateSpoken shouldBe true
        tomorrow.timeSpoken shouldBe false
        r.duration("pasta").unresolvable("")
    }
})
