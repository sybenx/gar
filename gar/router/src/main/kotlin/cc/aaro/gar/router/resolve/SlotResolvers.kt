package cc.aaro.gar.router.resolve

import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.resolve.EnglishTime.PartOfDay
import cc.aaro.gar.router.resolve.EnglishTime.TimeException
import org.dicio.numbers.ParserFormatter
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import org.dicio.numbers.unit.Duration as DicioDuration
import org.dicio.numbers.unit.Number as DicioNumber

/**
 * Deterministic resolvers for raw slot strings, built on dicio-numbers plus GAR's English fixes
 * (gar/DESIGN.md, "Resolvers"). [parserFormatter] is null for languages dicio-numbers doesn't
 * support; then every number/date/time/duration slot is [Resolution.Unresolvable].
 *
 * English: spoken clock times are parsed by GAR ([EnglishTime]) because dicio-numbers misreads
 * them ("ten thirty" = the year 1030, "seven fifteen" = July 15); the remaining date words go to
 * dicio-numbers and are rolled forward to their next occurrence. Durations get the "and a half"
 * forms rewritten before dicio-numbers sees them. Relative offsets ("in two hours", "two hours
 * from now") are `now + duration`, since dicio-numbers drops the date of hour/minute offsets.
 * A month said without its day ("december", "next month") is no day: it is
 * [AmbiguityReason.DAY_OF_MONTH], and GAR asks which day.
 * Every resolver rejects partial parses: the whole slot must be understood, apart from filler
 * words like "at", "on" or "the".
 *
 * Italian gets dicio-numbers alone with the same whole-slot check (no am/pm questions, no
 * recurrence). Other languages are [Resolution.Unresolvable].
 *
 * @param now supplies the reference time and its time zone (fixed in tests). Offsets shorter than
 * a day ("in twenty minutes") are that much real time, also across a daylight saving change;
 * days, months and years are calendar ones ("in three days" is at the same time of day).
 */
class SlotResolvers(
    private val parserFormatter: ParserFormatter?,
    private val language: String,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private val lang = language.lowercase().substringBefore('-').substringBefore('_')

    /** Whether number, date, time and duration slots can resolve at all in this language. */
    val supportsLanguage: Boolean get() = parserFormatter != null && lang in SUPPORTED_LANGUAGES

    fun duration(raw: String): Resolution<Duration> = withParser { pf ->
        val words = words(raw)
        val duration = parseDuration(pf, words)
            ?: return@withParser Resolution.Unresolvable("not a duration: '$raw'")
        if (duration.isZero || duration.isNegative) {
            Resolution.Unresolvable("not a positive duration: '$raw'")
        } else {
            Resolution.Resolved(duration)
        }
    }

    /** A point in time, future preferred: "tomorrow at seven pm", "next tuesday", "in two hours". */
    fun dateTime(raw: String): Resolution<ResolvedDateTime> = withParser { pf ->
        val zonedNow = now()
        val now = zonedNow.toLocalDateTime()
        val today = now.toLocalDate()
        val words = words(raw)
        if (lang == "it") return@withParser italianDateTime(pf, words, now)

        relativeOffset(pf, words, zonedNow)?.let { return@withParser Resolution.Resolved(it) }

        val extraction = try {
            EnglishTime.extract(words)
        } catch (e: TimeException) {
            return@withParser Resolution.Unresolvable("${e.message}: '$raw'")
        }
        val rest = cardinalDayAsOrdinal(extraction.rest)
        val dateWords = rest.filter { it !in FILLERS }
        // "december", "next month": every day of that month, see AmbiguityReason.DAY_OF_MONTH
        val month = if (dateWords.isEmpty()) null else monthWithoutDay(pf, rest, today)
        val date = if (dateWords.isEmpty() || month != null) {
            null
        } else {
            parseDate(pf, rest, today)
                ?: return@withParser Resolution.Unresolvable("not a date and time: '$raw'")
        }

        val times = try {
            timesOf(extraction)
        } catch (e: TimeException) {
            return@withParser Resolution.Unresolvable("${e.message}: '$raw'")
        }
        if (date == null && month == null && times == null) {
            return@withParser Resolution.Unresolvable("no date or time in '$raw'")
        }
        // "sunday at nine am" said on Sunday afternoon: the rolled-forward date is today, but the
        // time has passed, so the next occurrence is a week later (a month or year for "the
        // twenty fifth" or "october twenty fifth"). Explicit "today" or "next ..." stay as said;
        // "this coming ..." asks for the next one, like without "coming".
        val laterDate by lazy {
            if (rest.any { it in RELATIVE_WORDS && it != "coming" }) {
                null
            } else {
                parseDate(pf, rest, today.plusDays(1))
            }
        }

        /**
         * [day] at [time], either of them null when not said; [later] is the day to take instead
         * of [day] when that time has passed on it.
         */
        fun at(day: LocalDate?, time: LocalTime?, later: () -> LocalDate?): ResolvedDateTime {
            val dateTime = when {
                time == null -> day!!.atTime(now.toLocalTime().truncatedTo(ChronoUnit.MINUTES))
                day == null -> today(now, time).let { if (it.isAfter(now)) it else it.plusDays(1) }
                else -> {
                    // "friday night at two": the small hours after that night, on the next day;
                    // "friday at midnight": the end of friday, like "tonight at midnight"
                    val smallHours = extraction.partOfDay == PartOfDay.NIGHT && time.hour < 5
                    val endOfDay = time == LocalTime.MIDNIGHT && "midnight" in words
                    val days = if (smallHours || endOfDay) 1L else 0L
                    day.plusDays(days).atTime(time).let {
                        if (it.isAfter(now)) it else later()?.plusDays(days)?.atTime(time) ?: it
                    }
                }
            }
            return ResolvedDateTime(
                dateTime,
                dateSpoken = day != null,
                timeSpoken = time != null,
                approximateTime = time != null && extraction.clock == null,
            )
        }

        if (month != null) {
            // the days to pick from; a passed time today stays today's, it is only one of them
            val days = daysOf(month, today)
            val byDay = days.flatMap { day -> (times ?: listOf(null)).map { day to at(day, it) { null } } }
            if (byDay.any { (day, option) -> option.dateTime.toLocalDate() != day }) {
                // "december at midnight": each day's end is the next day's start, so a day picked
                // by its date would be the wrong one; GAR asks for the whole date instead
                return@withParser Resolution.Unresolvable("a month without its day: '$raw'")
            }
            val options = byDay.map { it.second }
            return@withParser when {
                days.size > 1 -> Resolution.Ambiguous(options, AmbiguityReason.DAY_OF_MONTH)
                options.size > 1 -> Resolution.Ambiguous(options, AmbiguityReason.AM_OR_PM)
                else -> Resolution.Resolved(options[0])
            }
        }
        val options = (times ?: listOf(null)).map { at(date, it) { laterDate } }
        if (options.size == 1) {
            Resolution.Resolved(options[0])
        } else {
            Resolution.Ambiguous(options, AmbiguityReason.AM_OR_PM)
        }
    }

    /** A time of day with optional repetition: "half past six", "seven am every weekday". */
    fun time(raw: String): Resolution<ResolvedTime> = withParser { pf ->
        val zonedNow = now()
        val now = zonedNow.toLocalDateTime()
        val words = words(raw)
        if (lang == "it") {
            return@withParser when (val r = italianDateTime(pf, words, now)) {
                is Resolution.Resolved -> if (r.value.timeSpoken && !r.value.dateSpoken) {
                    Resolution.Resolved(ResolvedTime(r.value.dateTime.toLocalTime()))
                } else {
                    Resolution.Unresolvable("not a time of day: '$raw'")
                }
                else -> Resolution.Unresolvable("not a time of day: '$raw'")
            }
        }

        // "in twenty minutes": the time of day it will be then (an alarm in twenty minutes);
        // within a day on the clock, since the clock app rings at the next time it shows that
        offsetFrom(pf, words, zonedNow)?.let { (then, subDay) ->
            val withinADay = subDay && then.toLocalDateTime().isBefore(now.plusDays(1))
            return@withParser if (!withinADay) {
                Resolution.Unresolvable("a day or more from now: '$raw'")
            } else {
                Resolution.Resolved(ResolvedTime(roundedAlarmTime(then, zonedNow)))
            }
        }

        try {
            val recurrence = EnglishTime.extractRecurrence(words)
            // "today" and "tomorrow" don't change when an alarm rings next ("tonight" means pm)
            val extraction = EnglishTime.extract(
                recurrence.rest.filter { it != "today" && it != "tomorrow" }
            )
            if (extraction.clock == null) {
                return@withParser Resolution.Unresolvable("no time of day in '$raw'")
            }
            if (extraction.rest.any { it !in FILLERS }) {
                return@withParser Resolution.Unresolvable("not only a time of day: '$raw'")
            }
            val partOfDay = extraction.partOfDay ?: recurrence.partOfDay
            val times = timesOf(extraction.copy(partOfDay = partOfDay))!!
            val options = times.map { ResolvedTime(it!!, recurrence.days) }
            if (options.size == 1) {
                Resolution.Resolved(options[0])
            } else {
                Resolution.Ambiguous(options, AmbiguityReason.AM_OR_PM)
            }
        } catch (e: TimeException) {
            Resolution.Unresolvable("${e.message}: '$raw'")
        }
    }

    /**
     * A calendar date, next occurrence: "friday", "the fifth", "march third". A month without its
     * day ("december") is [AmbiguityReason.DAY_OF_MONTH] with its days as options.
     */
    fun date(raw: String): Resolution<ResolvedDate> {
        return when (val r = dateTime(raw)) {
            is Resolution.Resolved -> if (r.value.timeSpoken) {
                Resolution.Unresolvable("a time, not a date: '$raw'")
            } else {
                Resolution.Resolved(ResolvedDate(r.value.dateTime.toLocalDate()))
            }
            is Resolution.Ambiguous ->
                if (r.reason == AmbiguityReason.DAY_OF_MONTH && r.options.none { it.timeSpoken }) {
                    Resolution.Ambiguous(
                        r.options.map { ResolvedDate(it.dateTime.toLocalDate()) },
                        AmbiguityReason.DAY_OF_MONTH,
                    )
                } else {
                    Resolution.Unresolvable("a time, not a date: '$raw'")
                }
            is Resolution.Unresolvable -> r
        }
    }

    /** A number, including percentages ("fifty percent" = 50.0 with [percent] = true). */
    fun number(raw: String): Resolution<ResolvedNumber> = withParser { pf ->
        var words = words(raw)
        val percentWords =
            if (lang == "it") listOf("percento", "percentuale") else listOf("percent", "percentage")
        var percent = false
        if (words.lastOrNull() in percentWords) {
            percent = true
            words = words.dropLast(1)
        } else if (words.size >= 2 && words.takeLast(2) == listOf("per", "cent")) {
            percent = true
            words = words.dropLast(2)
        }
        val numbers = onlyObjects<DicioNumber>(
            pf.extractNumber(words.joinToString(" ")).parseMixedWithText(),
            if (lang == "it") ITALIAN_FILLERS else NUMBER_FILLERS,
        )
        val number = numbers?.singleOrNull()
            ?: return@withParser Resolution.Unresolvable("not one number: '$raw'")
        val value = if (number.isInteger) number.integerValue().toDouble() else number.decimalValue()
        Resolution.Resolved(ResolvedNumber(value, percent, number.isInteger))
    }

    // ---------- shared helpers ----------

    private inline fun <T> withParser(block: (ParserFormatter) -> Resolution<T>): Resolution<T> {
        val pf = parserFormatter
        if (pf == null || !supportsLanguage) {
            return Resolution.Unresolvable("numbers, dates and times aren't supported in '$language'")
        }
        return try {
            block(pf)
        } catch (e: RuntimeException) {
            // dicio-numbers has thrown on odd input before; never let that escape
            Resolution.Unresolvable("could not parse: ${e.message}")
        }
    }

    private fun words(raw: String) = Utterances.words(Utterances.normalize(raw))

    /** The options for the time of day, or null when none was said. */
    private fun timesOf(extraction: EnglishTime.Extraction): List<LocalTime?>? {
        val clock = extraction.clock
        if (clock == null) {
            if (extraction.meridiem != null) throw TimeException("am/pm without a time")
            return extraction.partOfDay?.let { listOf(LocalTime.of(it.hour, 0)) }
        }
        val time = EnglishTime.toLocalTime(clock, extraction.meridiem, extraction.partOfDay)
        return if (time != null) listOf(time) else EnglishTime.amAndPm(clock)
    }

    private fun today(now: LocalDateTime, time: LocalTime) = now.toLocalDate().atTime(time)

    /**
     * Keeps the objects of a dicio-numbers `parseMixedWithText` result, or returns null if any
     * text between them is more than [fillers].
     */
    private inline fun <reified T> onlyObjects(mixed: List<Any>, fillers: Set<String>): List<T>? {
        val objects = mutableListOf<T>()
        for (item in mixed) {
            when (item) {
                is T -> objects += item
                is String -> if (Utterances.words(Utterances.normalize(item)).any { it !in fillers }) {
                    return null
                }
                else -> return null
            }
        }
        return objects
    }

    // ---------- durations ----------

    /** A month and a year are dicio-numbers' average lengths (a timer for "a month"). */
    private fun parseDuration(pf: ParserFormatter, allWords: List<String>): Duration? =
        parseDicioDuration(pf, allWords)?.toJavaDuration()

    /** The duration with its months and years kept apart, so they can be added as calendar units. */
    private fun parseDicioDuration(pf: ParserFormatter, allWords: List<String>): DicioDuration? {
        val words = if (lang == "en") fromNowRemoved(allWords.filter { it !in MORE_WORDS }) else allWords
        if (words.isEmpty()) return null
        val text = if (lang == "en") rewriteFractions(pf, words) else words.joinToString(" ")
        val durations = onlyObjects<DicioDuration>(
            pf.extractDuration(text).parseMixedWithText(),
            if (lang == "it") ITALIAN_FILLERS else DURATION_FILLERS,
        ) ?: return null
        // "one hour and thirty minutes" can come back as two parts
        return durations.reduceOrNull { sum, d -> sum.plus(d) }
    }

    /**
     * "twenty minutes from now", "ten minutes later": as long as without the words, which
     * [relativeOffset] reads as a time of day; a timer for "twenty minutes from now" is a timer
     * for twenty minutes, not a time of day in a duration slot (which Guard A would drop).
     */
    private fun fromNowRemoved(words: List<String>): List<String> = when {
        words.size > 2 && words.takeLast(2) == listOf("from", "now") -> words.dropLast(2)
        words.size > 1 && words.last() == "later" -> words.dropLast(1)
        else -> words
    }

    /**
     * Rewrites what dicio-numbers gets wrong into what it gets right: "an hour and a half" and
     * "one and a half hours" -> "1 hour 30 minutes", "half an hour" -> "30 minutes", "a quarter of
     * an hour" -> "15 minutes", "a minute" -> "1 minute".
     */
    private fun rewriteFractions(pf: ParserFormatter, words: List<String>): String {
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            // "half an hour", "a half hour", "half a minute"
            val half = matchFraction(words, i, "half", 0.5)
                ?: matchFraction(words, i, "quarter", 0.25)
                ?: matchThreeQuarters(words, i)
            if (half != null) {
                out += half.first
                i = half.second
                continue
            }

            // "hour and a half" without the article (the model sometimes drops it from the span);
            // "two hours and a half" is taken by the number branch below before reaching here
            val bareUnit = unitOf(words[i])
            val bareFraction = FRACTIONS[words.getOrNull(i + 3)]
            if (bareUnit != null && bareFraction != null && words.getOrNull(i + 1) == "and" &&
                words.getOrNull(i + 2) == "a") {
                out += "1 ${bareUnit.word}"
                out += fractionOf(bareUnit, bareFraction) ?: return words.joinToString(" ")
                i += 4
                if (words.getOrNull(i)?.let { unitOf(it) } == bareUnit) i++ // "... a half hour"
                continue
            }

            val numberEnd = numberRunEnd(words, i)
            if (numberEnd > i) {
                val value = numberValue(pf, words.subList(i, numberEnd))
                val rest = words.subList(numberEnd, words.size)
                val fraction = when {
                    // "two and a half hours"
                    rest.size >= 4 && rest[0] == "and" && rest[1] == "a" && unitOf(rest[3]) != null ->
                        FRACTIONS[rest[2]]?.let { Triple(it, unitOf(rest[3])!!, 4) }
                    // "an hour and a half", also with the unit said again: "an hour and a half hour"
                    rest.size >= 4 && unitOf(rest[0]) != null && rest[1] == "and" && rest[2] == "a" -> {
                        val unit = unitOf(rest[0])!!
                        val repeated = if (rest.getOrNull(4)?.let { unitOf(it) } == unit) 1 else 0
                        FRACTIONS[rest[3]]?.let { Triple(it, unit, 4 + repeated) }
                    }
                    else -> null
                }
                if (value != null && fraction != null) {
                    out += "${format(value)} ${fraction.second.word}"
                    out += fractionOf(fraction.second, fraction.first) ?: return words.joinToString(" ")
                    i = numberEnd + fraction.third
                    continue
                }
                // "one minute thirty", "an hour fifteen": the number after the unit counts the
                // next smaller unit; only at the end, so "two minutes ten seconds" stays as said
                val unit = rest.firstOrNull()?.let { unitOf(it) }
                val smaller = unit?.let { SMALLER_UNITS[it] }
                val countEnd = if (smaller != null) numberRunEnd(rest, 1) else 1
                if (value != null && smaller != null && countEnd > 1 && countEnd == rest.size) {
                    val count = numberValue(pf, rest.subList(1, countEnd))
                    if (count != null && count >= 1 && count < 60 && count == Math.floor(count) &&
                        rest[1] != "a" && rest[1] != "an") {
                        out += "${format(value)} ${unit.word}"
                        out += "${format(count)} ${smaller.word}"
                        i = words.size
                        continue
                    }
                }
                if (value != null && (words[i] == "a" || words[i] == "an") && numberEnd == i + 1 &&
                    rest.isNotEmpty() && unitOf(rest[0]) != null) {
                    out += "1" // "a minute": dicio-numbers needs a number
                    i = numberEnd
                    continue
                }
                out += words.subList(i, numberEnd)
                i = numberEnd
                continue
            }
            out += words[i]
            i++
        }
        return out.joinToString(" ")
    }

    /** "half an hour", "a half hour", "half hour", "quarter of an hour", "a quarter hour". */
    private fun matchFraction(
        words: List<String>,
        i: Int,
        fraction: String,
        value: Double,
    ): Pair<String, Int>? {
        var j = i
        if (words.getOrNull(j) == "a") j++
        if (words.getOrNull(j) != fraction) return null
        j++
        if (words.getOrNull(j) == "of") j++
        if (words.getOrNull(j) == "a" || words.getOrNull(j) == "an") j++
        val unit = words.getOrNull(j)?.let { unitOf(it) } ?: return null
        return fractionOf(unit, value)?.let { Pair(it, j + 1) }
    }

    /** "three quarters of an hour". */
    private fun matchThreeQuarters(words: List<String>, i: Int): Pair<String, Int>? {
        if (words.getOrNull(i) != "three" || words.getOrNull(i + 1) != "quarters") return null
        var j = i + 2
        if (words.getOrNull(j) == "of") j++
        if (words.getOrNull(j) == "a" || words.getOrNull(j) == "an") j++
        val unit = words.getOrNull(j)?.let { unitOf(it) } ?: return null
        return fractionOf(unit, 0.75)?.let { Pair(it, j + 1) }
    }

    private fun numberRunEnd(words: List<String>, start: Int): Int {
        var i = start
        while (i < words.size && (words[i] in NUMBER_WORDS || words[i].all { it.isDigit() })) i++
        return i
    }

    private fun numberValue(pf: ParserFormatter, words: List<String>): Double? {
        if (words == listOf("a") || words == listOf("an")) return 1.0
        val numbers = onlyObjects<DicioNumber>(
            pf.extractNumber(words.joinToString(" ")).parseMixedWithText(), setOf()
        ) ?: return null
        val n = numbers.singleOrNull() ?: return null
        return if (n.isInteger) n.integerValue().toDouble() else n.decimalValue()
    }

    private enum class Unit(val word: String, val seconds: Long) {
        SECOND("seconds", 1), MINUTE("minutes", 60), HOUR("hours", 3600), DAY("days", 86400),
        WEEK("weeks", 604800),
    }

    /** The unit a bare number after [Unit] counts: "one minute thirty" (seconds). */
    private val SMALLER_UNITS = mapOf(Unit.MINUTE to Unit.SECOND, Unit.HOUR to Unit.MINUTE)

    private fun unitOf(word: String): Unit? = when (word) {
        "second", "seconds", "sec", "secs" -> Unit.SECOND
        "minute", "minutes", "min", "mins" -> Unit.MINUTE
        "hour", "hours", "hr", "hrs" -> Unit.HOUR
        "day", "days" -> Unit.DAY
        "week", "weeks" -> Unit.WEEK
        else -> null
    }

    /** [fraction] of one [unit], said in smaller units ("30 minutes"); null below one second. */
    private fun fractionOf(unit: Unit, fraction: Double): String? {
        var seconds = Math.round(unit.seconds * fraction)
        if (seconds < 1) return null
        val parts = mutableListOf<String>()
        for (u in Unit.entries.reversed()) {
            if (u.seconds < unit.seconds && seconds >= u.seconds && u != Unit.WEEK) {
                parts += "${seconds / u.seconds} ${u.word}"
                seconds %= u.seconds
            }
        }
        return parts.joinToString(" ").ifEmpty { null }
    }

    private fun format(value: Double) =
        if (value == Math.floor(value)) value.toLong().toString() else value.toString()

    // ---------- dates ----------

    /**
     * "in two hours", "in three days", "two hours from now", "a week from today". Years, months
     * and days are calendar ones ("in a month" on January 31st is February 28th, "in three days"
     * is at the time of day it is now) and say no time of day; hours, minutes and seconds are
     * that much real time, also across a daylight saving change ("in twenty minutes" at the first
     * 1:50 am of the night the clock goes back is the second 1:10 am).
     */
    private fun relativeOffset(
        pf: ParserFormatter,
        words: List<String>,
        now: ZonedDateTime,
    ): ResolvedDateTime? {
        val (then, subDay) = offsetFrom(pf, words, now) ?: return null
        val local = then.toLocalDateTime()
        val twice = then.zone.rules.getValidOffsets(local).size > 1
        return ResolvedDateTime(
            local, dateSpoken = true, timeSpoken = subDay, offset = then.offset.takeIf { twice },
        )
    }

    /**
     * The moment [words] say relative to [now] ([relativeOffset]), and whether the offset has a
     * part shorter than a day (so a time of day was said), or null if they say no offset.
     */
    private fun offsetFrom(
        pf: ParserFormatter,
        words: List<String>,
        now: ZonedDateTime,
    ): Pair<ZonedDateTime, Boolean>? {
        val inner = when {
            words.size >= 2 && (words[0] == "in" || words[0] == "after") -> words.drop(1)
            words.size >= 3 && words[words.size - 2] == "from" &&
                (words.last() == "now" || words.last() == "today") -> words.dropLast(2)
            words.size >= 2 && words.last() == "later" -> words.dropLast(1)
            else -> return null
        }
        val duration = parseDicioDuration(pf, inner) ?: return null
        if (duration.toJavaDuration().let { it.isZero || it.isNegative }) return null
        val subDay = duration.nanos % NANOS_PER_DAY != 0L
        // plusNanos is on the time line, the others on the calendar
        val then = now.plusYears(duration.years).plusMonths(duration.months)
            .plusDays(duration.days).plusNanos(duration.nanos)
        return Pair(then, subDay)
    }

    /**
     * The month [words] name when they say no day of it: "december", "in december", "next
     * month", "december 2027". dicio-numbers reads these as the first of the month or as today's
     * day of that month, a day nobody said. A month name rolls forward like a date, also after
     * "in" or "on" (said in October, "january" is next January and "in october" this October);
     * with "next" or a year it stays as read. A counted month ("one month ago") is an offset, so
     * a day.
     */
    private fun monthWithoutDay(pf: ParserFormatter, words: List<String>, today: LocalDate): YearMonth? {
        // dicio-numbers reads "in october" said in October as a year from now
        val said = words.filterIndexed { i, word ->
            !(word in MONTH_PREPOSITIONS && words.getOrNull(i + 1) in MONTHS)
        }
        val content = said.filter { it !in FILLERS }
        val counted = content.indices.any { i ->
            content[i] == "month" && content.getOrNull(i - 1)?.let(::isCount) == true
        }
        if (content.none { it in MONTHS || it == "month" } || counted ||
            EnglishTime.dayOfMonth(content) != null || content.any { EnglishTime.dayOfWeek(it) != null } ||
            offsetFrom(pf, content, today.atStartOfDay(ZoneOffset.UTC)) != null) {
            return null
        }
        val text = said.joinToString(" ")
        fun dateFrom(reference: LocalDate) = onlyObjects<LocalDateTime>(
            pf.extractDateTime(text).now(reference.atTime(12, 0)).parseMixedWithText(), DATE_FILLERS,
        )?.singleOrNull()?.toLocalDate()
        val date = dateFrom(today) ?: return null
        // a day taken from the reference moves with it; the first of the month stands for no day
        // unless the first was said ("december one")
        val other = dateFrom(today.withDayOfMonth(if (today.dayOfMonth == 15) 14 else 15))
        val dayFromReference = other != null && other.dayOfMonth != date.dayOfMonth
        val firstForNoDay = date.dayOfMonth == 1 && content.none { it == "one" || it == "1" }
        if (!dayFromReference && !firstForNoDay) {
            return null
        }
        val month = YearMonth.from(date)
        val asSaid = content.any { it in RELATIVE_WORDS } || date.year != today.year
        return if (!asSaid && month.isBefore(YearMonth.from(today))) month.plusYears(1) else month
    }

    /** "one", "a", "2": a count of what follows. */
    private fun isCount(word: String) = word in NUMBER_WORDS || word.all { it.isDigit() }

    /**
     * [words] with a day of the month said as a cardinal between "the" and "of <month>" written
     * as an ordinal: "the twelve of december" is "the 12th of december". dicio-numbers reads a
     * cardinal up to 24 there as an hour on the 1st ("the 12 of december" is noon on December
     * 1st). Both dicio-numbers and [EnglishTime.dayOfMonth] take "th" after any day ("21th").
     */
    private fun cardinalDayAsOrdinal(words: List<String>): List<String> {
        for (i in words.indices) {
            if (words[i] != "the") continue
            val (day, end) = EnglishTime.smallNumberAt(words, i + 1) ?: continue
            if (day in 1..31 && words.getOrNull(end) == "of" && words.getOrNull(end + 1) in MONTHS) {
                return words.subList(0, i + 1) + "${day}th" + words.subList(end, words.size)
            }
        }
        return words
    }

    /** The days of [month], from [today] on when it is this month. */
    private fun daysOf(month: YearMonth, today: LocalDate): List<LocalDate> {
        val first = if (month == YearMonth.from(today)) today else month.atDay(1)
        return generateSequence(first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(month.atEndOfMonth()) }
            .toList()
    }

    /**
     * Resolves the date words left after the time was taken out. Requires a date word (so a
     * leftover bare number is not read as a day of the month), lets dicio-numbers parse, then
     * rolls bare weekdays, days of the month and month names forward to the next occurrence.
     */
    private fun parseDate(pf: ParserFormatter, words: List<String>, today: LocalDate): LocalDate? {
        val content = words.filter { it !in FILLERS }
        // "this weekend", "the weekend": its Saturday, or today during the weekend
        if (content.filter { it != "coming" } == listOf("weekend")) {
            return if (today.dayOfWeek == DayOfWeek.SUNDAY) {
                today
            } else {
                today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            }
        }
        if (content.none { isDateWord(it) }) return null

        // "in three days", "two weeks from today"
        offsetFrom(pf, content, today.atStartOfDay(ZoneOffset.UTC))?.let { (then, subDay) ->
            return if (subDay) null else then.toLocalDate()
        }

        val text = words.joinToString(" ")
        val parsed = onlyObjects<LocalDateTime>(
            pf.extractDateTime(text).now(today.atTime(12, 0)).parseMixedWithText(), DATE_FILLERS,
        )?.singleOrNull() ?: return null
        var date = parsed.toLocalDate()

        val relative = content.any { it in RELATIVE_WORDS }
        val weekday = content.firstNotNullOfOrNull { EnglishTime.dayOfWeek(it) }
        val month = content.any { it in MONTHS }
        // dicio-numbers clamps impossible days ("the thirty first" in November is the 30th)
        val day = EnglishTime.dayOfMonth(content)
        if (day != null && day !in 1..31) return null

        // another year is kept when it can have been said, with a month or a day; a bare weekday is
        // in last year in early January (dicio-numbers reads it in the current Monday-Sunday week)
        if (relative || date.year != today.year && (month || day != null)) {
            return date.takeIf { day == null || it.dayOfMonth == day }
        }
        if (month) {
            // the next year with this month and day, on the weekday if one was said
            val d = day ?: date.dayOfMonth
            return (date.year..date.year + 8).asSequence()
                .filter { d <= YearMonth.of(it, date.monthValue).lengthOfMonth() }
                .map { LocalDate.of(it, date.monthValue, d) }
                .firstOrNull { !it.isBefore(today) && (weekday == null || it.dayOfWeek == weekday) }
        }
        if (day != null) {
            // the next month with this day ("friday the thirteenth": also on that weekday)
            var ym = YearMonth.from(date)
            repeat(48) {
                if (day <= ym.lengthOfMonth()) {
                    val candidate = ym.atDay(day)
                    val onWeekday = weekday == null || candidate.dayOfWeek == weekday
                    if (!candidate.isBefore(today) && onWeekday) {
                        return candidate
                    }
                }
                ym = ym.plusMonths(1)
            }
            return null
        }
        if (weekday != null) {
            while (date.isBefore(today)) date = date.plusWeeks(1)
            return date.takeIf { it.dayOfWeek == weekday }
        }
        return date
    }

    private fun isDateWord(word: String) = word in DATE_WORDS || word in MONTHS ||
        EnglishTime.dayOfWeek(word) != null || word in EnglishTime.ORDINALS ||
        ORDINAL_DIGITS.matches(word)

    // ---------- Italian ----------

    /**
     * dicio-numbers alone, whole slot or nothing. Whether the time of day was said is found by
     * parsing with two `now`s one nanosecond apart: a time taken from `now` moves with it.
     */
    private fun italianDateTime(
        pf: ParserFormatter,
        words: List<String>,
        now: LocalDateTime,
    ): Resolution<ResolvedDateTime> {
        val text = words.joinToString(" ")
        fun parse(at: LocalDateTime) = onlyObjects<LocalDateTime>(
            pf.extractDateTime(text).now(at).parseMixedWithText(), ITALIAN_FILLERS,
        )?.singleOrNull()
        val result = parse(now) ?: return Resolution.Unresolvable("not a date and time: '$text'")
        val shifted = parse(now.plusNanos(1))
        val timeSpoken = shifted != null && shifted.toLocalTime() == result.toLocalTime()
        val dateSpoken = result.toLocalDate() != now.toLocalDate() || "oggi" in words
        val dateTime =
            if (timeSpoken && !dateSpoken && !result.isAfter(now)) result.plusDays(1) else result
        return Resolution.Resolved(ResolvedDateTime(dateTime, dateSpoken, timeSpoken))
    }

    private companion object {
        val SUPPORTED_LANGUAGES = setOf("en", "it")

        /** Words that may be left over around a date or time. */
        val FILLERS = setOf("at", "on", "for", "by", "around", "about", "the", "this", "of")
        val DATE_FILLERS = FILLERS + setOf("in", "and")
        val DURATION_FILLERS = setOf("for", "in", "about", "around", "just", "only", "like", "and", "a")

        /** "five more minutes", "another ten minutes": the duration is what counts. */
        val MORE_WORDS = setOf("more", "another", "extra")
        val NUMBER_FILLERS = setOf("to", "at", "by", "of", "about", "around", "the", "number", "and")
        val ITALIAN_FILLERS = setOf(
            "a", "alle", "all'", "il", "lo", "la", "le", "per", "di", "verso", "e", "tra", "fra",
        )

        val FRACTIONS = mapOf("half" to 0.5, "quarter" to 0.25)
        val NANOS_PER_DAY = Duration.ofDays(1).toNanos()

        val NUMBER_WORDS = setOf(
            "a", "an", "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
            "eighty", "ninety", "hundred", "thousand",
        )

        val MONTHS = setOf(
            "january", "february", "march", "april", "may", "june", "july", "august", "september",
            "october", "november", "december",
        )
        /** "in october", "on october": the month name alone, see `monthWithoutDay`. */
        val MONTH_PREPOSITIONS = setOf("in", "on")
        val DATE_WORDS = setOf(
            "today", "tomorrow", "yesterday", "day", "days", "week", "weeks", "month", "months",
            "year", "years",
        )
        /** Words after which dicio-numbers' reading is kept as is (no rolling forward). */
        val RELATIVE_WORDS = setOf(
            "today", "tomorrow", "yesterday", "next", "last", "ago", "after", "before", "from",
            "in", "coming", "following", "previous", "day", "days", "week", "weeks",
        )
        val ORDINAL_DIGITS = Regex("""\d{1,2}(st|nd|rd|th)""")
    }
}

data class ResolvedNumber(val value: Double, val percent: Boolean, val isInteger: Boolean)

/**
 * The minute an alarm said relative to [now] rings at ("in twenty minutes"): [at] rounded to the
 * nearest minute, but never the minute that has begun ("in ten seconds"), which the clock app
 * would set for tomorrow. Both are moments, so this holds across a daylight saving change too.
 */
fun roundedAlarmTime(at: ZonedDateTime, now: ZonedDateTime): LocalTime {
    val rounded = at.plusSeconds(30).truncatedTo(ChronoUnit.MINUTES)
    return (if (rounded.isAfter(now)) rounded else rounded.plusMinutes(1)).toLocalTime()
}
