package cc.aaro.gar.router.resolve

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * GAR's own English time-of-day parsing, run before dicio-numbers (gar/DESIGN.md, "Resolvers").
 * dicio-numbers reads "ten thirty" as the year 1030 and "seven fifteen" as July 15, never infers
 * am/pm and maps "night" to 3 am, so spoken clock times are parsed here into hour and minute,
 * and only the remaining date words go to dicio-numbers.
 *
 * Input is a list of normalized words: lowercase, as Vosk emits them and as Whisper's text comes out
 * of [cc.aaro.gar.router.SpokenForm] ("seven thirty am", "seven oh five", "nineteen hundred").
 * Typed digit forms ("7", "7:15", "19:30", "7pm") are accepted too.
 */
internal object EnglishTime {

    enum class Meridiem { AM, PM }

    /** Parts of day; [hour] is used when no clock time is said ("tomorrow morning"). */
    enum class PartOfDay(val hour: Int) {
        MORNING(9), AFTERNOON(15), EVENING(19), NIGHT(21)
    }

    /**
     * A clock time as said. [hour] is 0..23; when [absolute] is false it is a 12-hour value
     * (1..12) still waiting for am/pm.
     */
    data class ClockTime(val hour: Int, val minute: Int, val absolute: Boolean)

    /** The time-related parts found in a phrase, and the words left for the date parser. */
    data class Extraction(
        val clock: ClockTime?,
        val meridiem: Meridiem?,
        val partOfDay: PartOfDay?,
        val rest: List<String>,
    )

    class TimeException(message: String) : Exception(message)

    /**
     * Finds at most one clock time and at most one am/pm or part-of-day cue in [words].
     * @throws TimeException for two clock times or contradicting cues
     */
    fun extract(words: List<String>): Extraction {
        val rest = words.toMutableList()
        var meridiem: Meridiem? = null
        var partOfDay: PartOfDay? = null

        // cues first, so that "a m" is not read as the article "a"
        var i = 0
        while (i < rest.size) {
            val cue = cueAt(rest, i)
            if (cue == null) {
                i++
                continue
            }
            val (length, cueMeridiem, cuePart) = cue
            if (cueMeridiem != null) {
                if (meridiem != null && meridiem != cueMeridiem) throw TimeException("both am and pm")
                meridiem = cueMeridiem
            }
            if (cuePart != null) {
                if (partOfDay != null && partOfDay != cuePart) throw TimeException("two parts of day")
                partOfDay = cuePart
            }
            repeat(length) { rest.removeAt(i) }
        }

        var clock: ClockTime? = null
        i = 0
        while (i < rest.size) {
            val match = clockAt(rest, i)
            if (match == null) {
                i++
                continue
            }
            if (clock != null) throw TimeException("two times")
            clock = match.first.time
            match.first.meridiem?.let {
                if (meridiem != null && meridiem != it) throw TimeException("both am and pm")
                meridiem = it
            }
            repeat(match.second - i) { rest.removeAt(i) }
        }
        return Extraction(clock, meridiem, partOfDay, rest)
    }

    /**
     * Applies am/pm or the part of day to [clock].
     * @return the time, or null when it stays ambiguous (12-hour value 1..11 without any cue)
     * @throws TimeException for contradictions like "nineteen hundred am"
     */
    fun toLocalTime(clock: ClockTime, meridiem: Meridiem?, partOfDay: PartOfDay?): LocalTime? {
        if (meridiem == Meridiem.AM && partOfDay in setOf(PartOfDay.AFTERNOON, PartOfDay.EVENING) ||
            meridiem == Meridiem.PM && partOfDay == PartOfDay.MORNING) {
            throw TimeException("am/pm contradicts the part of day")
        }
        if (clock.absolute) {
            if ((meridiem == Meridiem.AM || partOfDay == PartOfDay.MORNING) && clock.hour >= 13) {
                throw TimeException("am after 12:59")
            }
            return LocalTime.of(clock.hour, clock.minute)
        }
        val h = clock.hour // 1..12
        val hour = when {
            meridiem == Meridiem.AM || partOfDay == PartOfDay.MORNING -> if (h == 12) 0 else h
            meridiem == Meridiem.PM || partOfDay == PartOfDay.AFTERNOON ||
                partOfDay == PartOfDay.EVENING -> if (h == 12) 12 else h + 12
            partOfDay == PartOfDay.NIGHT -> when (h) {
                12 -> 0
                in 1..4 -> h // "two at night"
                else -> h + 12
            }
            h == 12 -> 12 // "twelve thirty" is just after noon
            else -> return null
        }
        return LocalTime.of(hour, clock.minute)
    }

    /** Both readings of an ambiguous 12-hour [clock], am first. */
    fun amAndPm(clock: ClockTime): List<LocalTime> =
        listOf(
            LocalTime.of(clock.hour % 12, clock.minute),
            LocalTime.of(clock.hour % 12 + 12, clock.minute),
        )

    // ---------- recurrence ----------

    /** Repeating days found in a phrase, the part of day they imply, and the remaining words. */
    data class Recurrence(val days: Set<DayOfWeek>, val partOfDay: PartOfDay?, val rest: List<String>)

    /**
     * Extracts "every day", "daily", "every weekday", "weekdays", "weekends", "every monday and
     * wednesday", "on mondays", "every morning" and similar.
     * @throws TimeException for a single weekday without "every" or a plural (a one-off day)
     */
    fun extractRecurrence(words: List<String>): Recurrence {
        val rest = mutableListOf<String>()
        val days = mutableSetOf<DayOfWeek>()
        var partOfDay: PartOfDay? = null
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val next = words.getOrNull(i + 1)
            when {
                w == "daily" || w == "everyday" -> { days += ALL_DAYS; i++ }
                w == "every" || w == "each" -> {
                    i++
                    var found = false
                    while (i < words.size) {
                        val word = words[i]
                        val after = words.getOrNull(i + 1)
                        when {
                            word == "day" || word == "single" && after == "day" -> {
                                days += ALL_DAYS
                                i += if (word == "single") 2 else 1
                            }
                            word == "weekday" || word == "workday" -> { days += WORK_DAYS; i++ }
                            (word == "week" || word == "work") && after == "day" -> {
                                days += WORK_DAYS
                                i += 2
                            }
                            word == "weekend" -> { days += WEEKEND; i++ }
                            word == "morning" || word == "afternoon" || word == "evening" ||
                                word == "night" -> {
                                days += ALL_DAYS
                                partOfDay = cueAt(listOf(word), 0)?.third
                                i++
                            }
                            dayOfWeek(word) != null -> { days += dayOfWeek(word)!!; i++ }
                            (word == "and" || word == "or") && found -> { i++; continue }
                            else -> break
                        }
                        found = true
                    }
                    if (!found) throw TimeException("'every' without days")
                }
                w == "weekdays" || w == "workdays" || w == "weekday" && next == null -> {
                    days += WORK_DAYS
                    i++
                }
                (w == "week" || w == "work") && next == "days" -> { days += WORK_DAYS; i += 2 }
                w == "weekends" || w == "weekend" -> { days += WEEKEND; i++ }
                pluralDayOfWeek(w) != null -> {
                    days += pluralDayOfWeek(w)!!
                    i++
                    // "mondays and wednesdays", "mondays and wednesday"
                    while (i + 1 < words.size && words[i] == "and" &&
                        (pluralDayOfWeek(words[i + 1]) ?: dayOfWeek(words[i + 1])) != null) {
                        days += (pluralDayOfWeek(words[i + 1]) ?: dayOfWeek(words[i + 1]))!!
                        i += 2
                    }
                }
                dayOfWeek(w) != null && (next == "to" || next == "through" || next == "thru") &&
                    i + 2 < words.size && dayOfWeek(words[i + 2]) != null -> {
                    // "monday to friday", "from monday to friday"
                    if (rest.lastOrNull() == "from") rest.removeAt(rest.size - 1)
                    val from = dayOfWeek(w)!!
                    val to = dayOfWeek(words[i + 2])!!
                    var d = from
                    while (true) {
                        days += d
                        if (d == to) break
                        d = d.plus(1)
                    }
                    i += 3
                }
                dayOfWeek(w) != null -> throw TimeException("a single day is not a repetition")
                else -> { rest += w; i++ }
            }
        }
        // "on weekdays", "on mondays": drop the "on" left behind
        if (days.isNotEmpty()) {
            rest.removeAll { it == "on" }
        }
        return Recurrence(days, partOfDay, rest)
    }

    fun dayOfWeek(word: String): DayOfWeek? = DAY_NAMES[word]

    private fun pluralDayOfWeek(word: String): DayOfWeek? =
        if (word.endsWith("s")) DAY_NAMES[word.dropLast(1)] else null

    // ---------- clock times ----------

    private data class ClockMatch(val time: ClockTime, val meridiem: Meridiem?)

    /** The longest clock time starting at [i], with the index after it. */
    private fun clockAt(words: List<String>, i: Int): Pair<ClockMatch, Int>? {
        // a number right before a duration unit or after "the" is not a clock time, nor one after
        // a month ("december twelve": a day) or the last word of a number ("twenty twenty eight")
        val candidates = listOfNotNull(relativeAt(words, i), specialAt(words, i), hourMinuteAt(words, i))
        val best = candidates.maxByOrNull { it.second } ?: return null
        val prev = words.getOrNull(i - 1)
        val next = words.getOrNull(best.second)
        if (prev == "the" || prev in MONTHS || prev in TENS && UNITS[words[i]] in 1..9 ||
            next != null && (next in UNIT_WORDS || next in ORDINALS || next == "of")) {
            return null
        }
        return best
    }

    /** "noon", "midday", "midnight", optionally after "twelve". */
    private fun specialAt(words: List<String>, i: Int): Pair<ClockMatch, Int>? {
        val start = if (words.getOrNull(i) == "twelve" || words.getOrNull(i) == "12") i + 1 else i
        return when (words.getOrNull(start)) {
            "noon", "midday" -> Pair(ClockMatch(ClockTime(12, 0, true), null), start + 1)
            "midnight" -> Pair(ClockMatch(ClockTime(0, 0, true), null), start + 1)
            else -> null
        }
    }

    /** "half past six", "a quarter to eight", "ten past nine", "twenty minutes to five". */
    private fun relativeAt(words: List<String>, i: Int): Pair<ClockMatch, Int>? {
        var j = i
        if (words[j] == "a") j++
        val minutes: Int
        var bareCount = false
        when (words.getOrNull(j)) {
            "half" -> { minutes = 30; j++ }
            "quarter" -> { minutes = 15; j++ }
            else -> {
                if (j != i) return null // "a" only before half/quarter
                val (value, end) = smallNumberAt(words, j) ?: return null
                if (value !in 1..59) return null
                minutes = value
                j = end
                if (words.getOrNull(j) in setOf("minute", "minutes", "min", "mins")) j++ else bareCount = true
            }
        }
        val after = when (words.getOrNull(j)) {
            "past", "after" -> true
            "to", "till", "til", "before", "of" -> false
            else -> return null
        }
        if (words[j - 1] == "half" && !after) return null // "half to" is not a time
        // "seven to eight" is a range: before the hour, a count without "minutes" is a multiple
        // of five ("five to seven", "twenty five to eight")
        if (bareCount && !after && minutes % 5 != 0) return null
        j++
        // the hour: a 12-hour number, noon or midnight
        val special = specialAt(words, j)
        val hour: Int
        val absolute: Boolean
        if (special != null) {
            hour = special.first.time.hour
            absolute = true
            j = special.second
        } else {
            val (value, end) = smallNumberAt(words, j) ?: return null
            if (value !in 1..12) return null
            hour = value
            absolute = false
            j = end
            if (words.getOrNull(j) == "o'clock" || words.getOrNull(j) == "oclock") j++
        }
        val time = if (after) {
            ClockTime(hour, minutes, absolute)
        } else if (absolute) {
            val t = LocalTime.of(hour, 0).minusMinutes(minutes.toLong())
            ClockTime(t.hour, t.minute, true)
        } else {
            ClockTime(if (hour == 1) 12 else hour - 1, 60 - minutes, false)
        }
        return Pair(ClockMatch(time, null), j)
    }

    /** "seven", "seven fifteen", "nine oh five", "seven o'clock", "nineteen hundred", "7:15pm". */
    private fun hourMinuteAt(words: List<String>, i: Int): Pair<ClockMatch, Int>? {
        DIGIT_TIME.matchEntire(words[i])?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            val meridiem = when (m.groupValues[3]) {
                "am" -> Meridiem.AM
                "pm" -> Meridiem.PM
                else -> null
            }
            if (hour > 23 || minute > 59 || meridiem != null && hour !in 1..12) return null
            return Pair(ClockMatch(ClockTime(hour, minute, hour == 0 || hour > 12), meridiem), i + 1)
        }

        val (hour, afterHour) = spokenHourAt(words, i) ?: return null
        var j = afterHour
        var minute = 0
        val next = words.getOrNull(j)
        val military = next == "hundred"
        when {
            next == "o'clock" || next == "oclock" -> j++
            next == "o" && words.getOrNull(j + 1) == "clock" -> j += 2
            military -> {
                j++
                if (words.getOrNull(j) == "hours") j++
            }
            (next == "oh" || next == "o" || next == "zero") &&
                UNITS[words.getOrNull(j + 1)] in 1..9 -> {
                minute = UNITS[words[j + 1]]!!
                j += 2
            }
            next != null && next.all { it.isDigit() } && next.length == 2 -> {
                minute = next.toInt()
                if (minute > 59) return null
                j++
            }
            else -> {
                val m = smallNumberAt(words, j)
                if (m != null && m.first in 10..59) {
                    minute = m.first
                    j = m.second
                }
            }
        }
        // spoken 24-hour times only after "at" or as "nineteen hundred": "twenty twenty" is a year
        if (hour > 12 && !military && words.getOrNull(i - 1) != "at") return null
        if (hour == 0 && !military) return null
        // "seven hundred" is military time, so 07:00 and not ambiguous
        return Pair(ClockMatch(ClockTime(hour, minute, military || hour > 12), null), j)
    }

    /** An hour said as words: 0..23 (the caller restricts 0 and 13..23). */
    private fun spokenHourAt(words: List<String>, i: Int): Pair<Int, Int>? {
        val (value, end) = smallNumberAt(words, i) ?: return null
        if (value > 23) {
            // "twenty five" etc.: only the tens word could be an hour, and it is not one
            return null
        }
        // smallNumberAt reads "twenty one" as 21 and "twenty" as 20
        return Pair(value, end)
    }

    /** A number 0..59 said as words ("forty five") or 1-2 digits, and the index after it. */
    internal fun smallNumberAt(words: List<String>, i: Int): Pair<Int, Int>? {
        val w = words.getOrNull(i) ?: return null
        if (w.length <= 2 && w.all { it.isDigit() }) {
            return Pair(w.toInt(), i + 1)
        }
        TENS[w]?.let { tens ->
            val unit = UNITS[words.getOrNull(i + 1)]
            return if (unit != null && unit in 1..9) Pair(tens + unit, i + 2) else Pair(tens, i + 1)
        }
        TEENS[w]?.let { return Pair(it, i + 1) }
        UNITS[w]?.let { if (w != "oh" && w != "zero") return Pair(it, i + 1) }
        return null
    }

    // ---------- cues ----------

    /** @return (words used, meridiem, part of day) of a cue at [i], or null */
    private fun cueAt(words: List<String>, i: Int): Triple<Int, Meridiem?, PartOfDay?>? {
        val w = words[i]
        val next = words.getOrNull(i + 1)
        val afterNext = words.getOrNull(i + 2)
        return when {
            w == "am" -> Triple(1, Meridiem.AM, null)
            w == "pm" -> Triple(1, Meridiem.PM, null)
            w == "a" && next == "m" -> Triple(2, Meridiem.AM, null)
            w == "p" && next == "m" -> Triple(2, Meridiem.PM, null)
            w == "in" && next == "the" && afterNext != null && PARTS[afterNext] != null ->
                Triple(3, null, PARTS[afterNext])
            (w == "this" || w == "at") && next != null && PARTS[next] != null ->
                Triple(2, null, PARTS[next])
            w == "tonight" -> Triple(1, null, PartOfDay.NIGHT)
            PARTS[w] != null -> Triple(1, null, PARTS[w])
            else -> null
        }
    }

    private val PARTS = mapOf(
        "morning" to PartOfDay.MORNING,
        "afternoon" to PartOfDay.AFTERNOON,
        "evening" to PartOfDay.EVENING,
        "night" to PartOfDay.NIGHT,
    )

    private val DIGIT_TIME = Regex("""(\d{1,2})(?::(\d{2}))?(am|pm)?""")

    private val UNITS = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50,
    )

    private val MONTHS = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december",
    )

    val UNIT_WORDS = setOf(
        "second", "seconds", "sec", "secs", "minute", "minutes", "min", "mins", "hour", "hours",
        "hr", "hrs", "day", "days", "week", "weeks", "month", "months", "year", "years",
    )

    private val ORDINAL_VALUES = listOf(
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth", "twentieth",
    ).withIndex().associate { (i, word) -> word to i + 1 } + ("thirtieth" to 30)

    val ORDINALS = ORDINAL_VALUES.keys

    private val ORDINAL_DIGITS = Regex("""(\d{1,2})(st|nd|rd|th)""")

    /** The day of the month in [words] ("the twenty first", "the 5th"), or null. */
    fun dayOfMonth(words: List<String>): Int? {
        words.forEachIndexed { i, w ->
            ORDINAL_DIGITS.matchEntire(w)?.let { return it.groupValues[1].toInt() }
            val value = ORDINAL_VALUES[w] ?: return@forEachIndexed
            return value + when (words.getOrNull(i - 1)) {
                "twenty" -> 20
                "thirty" -> 30
                else -> 0
            }
        }
        return null
    }

    private val DAY_NAMES = DayOfWeek.entries.associateBy { it.name.lowercase() }
    val ALL_DAYS = DayOfWeek.entries.toSet()
    val WORK_DAYS = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )
    val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
}
