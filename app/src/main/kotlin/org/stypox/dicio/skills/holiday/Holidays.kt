package org.stypox.dicio.skills.holiday

import cc.aaro.gar.router.Utterances
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters

/** A holiday: its name as GAR says it, and its date in a given year. */
class Holiday(val name: String, val aliases: List<String>, val dateIn: (year: Int) -> LocalDate) {
    override fun toString(): String = name
}

/**
 * Common US holidays, offline (English): fixed dates, nth weekdays of a month (Thanksgiving is
 * the fourth Thursday of November), Easter's dates by the Gregorian computus, and Tax Day's
 * filing deadline. Lunar holidays (Hanukkah, Ramadan, Lunar New Year, Diwali) aren't here.
 */
object Holidays {
    /** The holiday [text] names ("christmas", "the fourth of july", "mother's day"), or null. */
    fun find(text: String): Holiday? = byName[key(text)]

    /**
     * The holiday a day phrase names and the year it means, if said: "christmas", "easter next
     * year", "thanksgiving this year". [year] is null for its next one from today on.
     */
    fun findWithYear(text: String, today: LocalDate): Pair<Holiday, Int?>? {
        val words = key(text).split(' ')
        val (holidayWords, year) = when (words.takeLast(2)) {
            listOf("this", "year") -> Pair(words.dropLast(2), today.year)
            listOf("next", "year") -> Pair(words.dropLast(2), today.year + 1)
            listOf("last", "year") -> Pair(words.dropLast(2), today.year - 1)
            else -> Pair(words, null)
        }
        val holiday = byName[holidayWords.joinToString(" ")] ?: return null
        return Pair(holiday, year)
    }

    /** The day of [holiday] in [year], or its next one from [today] on (today included). */
    fun date(holiday: Holiday, today: LocalDate, year: Int? = null): LocalDate {
        if (year != null) {
            return holiday.dateIn(year)
        }
        val thisYear = holiday.dateIn(today.year)
        return if (thisYear.isBefore(today)) holiday.dateIn(today.year + 1) else thisYear
    }

    /** Easter Sunday of [year] (Gregorian, the anonymous algorithm). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /** The [n]th [day] of [month] in [year] ("the fourth Thursday of November"). */
    fun nth(year: Int, month: Month, n: Int, day: DayOfWeek): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day))

    /** The last [day] of [month] in [year] ("the last Monday of May"). */
    fun last(year: Int, month: Month, day: DayOfWeek): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(day))

    /** Lowercase words without apostrophes and dots, and without a leading "the". */
    private fun key(text: String): String =
        Utterances.normalize(text.replace(APOSTROPHE_OR_DOT, ""))
            .removePrefix("the ").removePrefix("this ").removePrefix("next ")

    private val APOSTROPHE_OR_DOT = Regex("['’‘ʼ.]")

    private fun fixed(month: Month, day: Int): (Int) -> LocalDate =
        { year -> LocalDate.of(year, month, day) }

    private fun thanksgiving(year: Int) = nth(year, Month.NOVEMBER, 4, DayOfWeek.THURSDAY)

    /**
     * The US tax filing deadline of [year]: April 15, or the next business day when that is a
     * weekend or Washington, D.C.'s Emancipation Day (April 16, on the Friday before or the
     * Monday after when it falls on a weekend). April 18 in 2028, for example.
     */
    fun taxDay(year: Int): LocalDate {
        val emancipationDay = LocalDate.of(year, Month.APRIL, 16).let {
            when (it.dayOfWeek) {
                DayOfWeek.SATURDAY -> it.minusDays(1)
                DayOfWeek.SUNDAY -> it.plusDays(1)
                else -> it
            }
        }
        var day = LocalDate.of(year, Month.APRIL, 15)
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY ||
            day == emancipationDay
        ) {
            day = day.plusDays(1)
        }
        return day
    }

    private fun h(name: String, vararg aliases: String, dateIn: (Int) -> LocalDate) =
        Holiday(name, aliases.toList(), dateIn)

    val ALL: List<Holiday> = listOf(
        h("New Year's Day", "new years", "new year", "new years day", "new year day",
            dateIn = fixed(Month.JANUARY, 1)),
        h("Martin Luther King Jr. Day", "martin luther king day", "martin luther king jr day",
            "martin luther king junior day", "mlk day", "m l k day", "mlk",
            dateIn = { nth(it, Month.JANUARY, 3, DayOfWeek.MONDAY) }),
        h("Groundhog Day", "groundhogs day", dateIn = fixed(Month.FEBRUARY, 2)),
        h("Valentine's Day", "valentines day", "valentines", "valentine day", "valentine",
            "st valentines day", "saint valentines day", dateIn = fixed(Month.FEBRUARY, 14)),
        h("Presidents' Day", "presidents day", "president day", "presidents",
            "washingtons birthday", dateIn = { nth(it, Month.FEBRUARY, 3, DayOfWeek.MONDAY) }),
        h("Mardi Gras", "fat tuesday", "shrove tuesday", "pancake day",
            dateIn = { easter(it).minusDays(47) }),
        h("Ash Wednesday", dateIn = { easter(it).minusDays(46) }),
        h("St. Patrick's Day", "st patricks day", "saint patricks day", "st patrick day",
            "saint patrick day", "st paddys day", "saint paddys day", "paddys day",
            "st patricks", "saint patricks", dateIn = fixed(Month.MARCH, 17)),
        h("April Fools' Day", "april fools day", "april fools", "april fool day", "april fool",
            dateIn = fixed(Month.APRIL, 1)),
        h("Palm Sunday", dateIn = { easter(it).minusDays(7) }),
        h("Good Friday", dateIn = { easter(it).minusDays(2) }),
        h("Easter", "easter sunday", "easter day", dateIn = ::easter),
        h("Easter Monday", dateIn = { easter(it).plusDays(1) }),
        h("Tax Day", dateIn = ::taxDay),
        h("Earth Day", dateIn = fixed(Month.APRIL, 22)),
        h("Cinco de Mayo", dateIn = fixed(Month.MAY, 5)),
        h("Mother's Day", "mothers day", "mother day", "moms day", "mom day",
            dateIn = { nth(it, Month.MAY, 2, DayOfWeek.SUNDAY) }),
        h("Memorial Day", dateIn = { last(it, Month.MAY, DayOfWeek.MONDAY) }),
        h("Flag Day", dateIn = fixed(Month.JUNE, 14)),
        h("Father's Day", "fathers day", "father day", "dads day", "dad day",
            dateIn = { nth(it, Month.JUNE, 3, DayOfWeek.SUNDAY) }),
        h("Juneteenth", "juneteenth day", dateIn = fixed(Month.JUNE, 19)),
        h("Independence Day", "fourth of july", "4th of july", "july fourth", "july 4th",
            "july 4", "july the fourth", dateIn = fixed(Month.JULY, 4)),
        h("Labor Day", "labour day", dateIn = { nth(it, Month.SEPTEMBER, 1, DayOfWeek.MONDAY) }),
        h("Columbus Day", dateIn = { nth(it, Month.OCTOBER, 2, DayOfWeek.MONDAY) }),
        h("Indigenous Peoples' Day", "indigenous peoples day", "indigenous people day",
            dateIn = { nth(it, Month.OCTOBER, 2, DayOfWeek.MONDAY) }),
        h("Halloween", "all hallows eve", "halloween day", dateIn = fixed(Month.OCTOBER, 31)),
        h("Election Day", dateIn = {
            // the Tuesday after the first Monday of November
            nth(it, Month.NOVEMBER, 1, DayOfWeek.MONDAY).plusDays(1)
        }),
        h("Veterans Day", "veterans", "veteran day", "veteran's day", "armistice day",
            dateIn = fixed(Month.NOVEMBER, 11)),
        h("Thanksgiving", "thanksgiving day", dateIn = ::thanksgiving),
        h("Black Friday", dateIn = { thanksgiving(it).plusDays(1) }),
        h("Cyber Monday", dateIn = { thanksgiving(it).plusDays(4) }),
        h("Christmas Eve", "christmas eve day", "xmas eve", dateIn = fixed(Month.DECEMBER, 24)),
        h("Christmas", "christmas day", "xmas", "x mas", dateIn = fixed(Month.DECEMBER, 25)),
        h("Boxing Day", dateIn = fixed(Month.DECEMBER, 26)),
        h("Kwanzaa", dateIn = fixed(Month.DECEMBER, 26)),
        h("New Year's Eve", "new years eve", "new year eve", "new years eve day",
            dateIn = fixed(Month.DECEMBER, 31)),
    )

    private val byName: Map<String, Holiday> = buildMap {
        for (holiday in ALL) {
            for (name in holiday.aliases + holiday.name) {
                put(key(name), holiday)
            }
        }
    }
}
