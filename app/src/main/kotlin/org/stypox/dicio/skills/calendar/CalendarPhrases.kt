package org.stypox.dicio.skills.calendar

import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.SlotResolvers
import org.stypox.dicio.sentences.Sentences.Calendar

/**
 * Splits the fast path's string captures into slot strings. Dicio's sentences capture free text
 * (typed captures misparse spoken times), so "add dentist tomorrow at three for an hour to my
 * calendar" captures "dentist tomorrow at three for an hour" as the title: the time and the
 * duration at its end are found here, by trying the longest phrase that starts with a time word
 * and that [SlotResolvers] understand. Nothing is resolved for good here: GAR's slot runner does
 * that, and asks about what's unclear.
 */
class CalendarPhrases(private val resolvers: SlotResolvers) {

    /** Raw slots for `calendar_add`: title, when, duration and end (absent ones left out). */
    fun createSlots(title: String?, begin: String?, end: String?, duration: String?): Map<String, String> {
        val slots = mutableMapOf<String, String>()
        var rest = title?.trim().orEmpty()
        var start: String? = null

        // what was said after "to my calendar": "friday from noon to one", "at three for an hour"
        begin?.trim()?.takeIf { it.isNotEmpty() }?.let { said ->
            if (isDuration(said)) {
                slots[CalendarRequest.DURATION] = said
                return@let
            }
            val (beforeDuration, span) = splitTrailingDuration(said)
            span?.let { slots[CalendarRequest.DURATION] = it }
            val range = splitRange(beforeDuration, requireStart = false)
            if (range == null) {
                start = beforeDuration
            } else {
                slots[CalendarRequest.END] = range.third
                start = joinDayAndTime(range.first, range.second)
            }
        }

        // "schedule tomorrow at three": no title, it's asked for
        if (start == null && rest.split(SPACES)[0] in TIME_CUES && isDateOrTime(rest)) {
            start = rest
            rest = ""
        }

        // the time and the duration said within the title, in either order
        repeat(2) {
            if (CalendarRequest.DURATION !in slots) {
                val (before, span) = splitTrailingDuration(rest)
                if (span != null) {
                    rest = before
                    slots[CalendarRequest.DURATION] = span
                }
            }
            if (start == null) {
                val range = splitRange(rest, requireStart = true)
                if (range != null && range.first.isNotEmpty()) {
                    // "dentist tomorrow from three to five": the day is still in the title
                    val (before, day) = splitTrailingTime(range.first)
                    rest = before
                    start = joinDayAndTime(day.orEmpty(), range.second)
                    slots[CalendarRequest.END] = range.third
                } else {
                    val (before, time) = splitTrailingTime(rest)
                    if (time != null) {
                        rest = before
                        start = time
                    }
                }
            }
        }

        rest.takeIf { it.isNotBlank() }?.let { slots[CalendarRequest.TITLE] = it }
        start?.let { slots[CalendarRequest.WHEN] = it }
        // the sentence's own captures win over what was split off the title
        end?.trim()?.takeIf { it.isNotEmpty() }?.let { slots[CalendarRequest.END] = it }
        duration?.trim()?.takeIf { it.isNotEmpty() }?.let { slots[CalendarRequest.DURATION] = it }
        return slots
    }

    /** Raw slots for `reminder_add`: what and when (absent when not said). */
    fun reminderSlots(what: String?, `when`: String?): Map<String, String> {
        val slots = mutableMapOf<String, String>()
        var rest = what?.trim().orEmpty()
        var time = `when`?.trim()?.takeIf { it.isNotEmpty() }
        if (time == null && rest.split(SPACES)[0] in TIME_CUES && isDateOrTime(rest)) {
            // "remind me tomorrow at five": what is asked for
            time = rest
            rest = ""
        } else if (time == null) {
            val (before, span) = splitTrailingTime(rest)
            rest = before
            time = span
        }
        rest.takeIf { it.isNotBlank() }?.let { slots[CalendarRequest.WHAT] = it }
        time?.let { slots[CalendarRequest.WHEN] = it }
        return slots
    }

    /** Raw slots for `calendar_query`: the day, if one was said. */
    fun querySlots(`when`: String?): Map<String, String> =
        `when`?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { mapOf(CalendarRequest.DAY to it) }
            ?: mapOf()

    /**
     * "call mom tomorrow at five" -> ("call mom", "tomorrow at five"): the longest phrase at the
     * end of [text] that starts with a time word and is a date or a time (an unclear am/pm
     * counts), keeping at least one word before it. ("call mom", null) if there is none.
     */
    fun splitTrailingTime(text: String): Pair<String, String?> = splitTrailing(text) { words ->
        words[0] in TIME_CUES && isDateOrTime(words.joinToString(" "))
    }

    /** "dentist for two hours" -> ("dentist", "for two hours"). */
    fun splitTrailingDuration(text: String): Pair<String, String?> = splitTrailing(text) { words ->
        words[0] in DURATION_CUES &&
                resolvers.duration(words.drop(1).joinToString(" ")) is Resolution.Resolved
    }

    /**
     * "lunch from noon to one" -> ("lunch", "noon", "one"). With [requireStart] false the range
     * may also start without "from": "friday noon to one" -> ("", "friday noon", "one"), as in
     * what is said after "to my calendar".
     */
    fun splitRange(text: String, requireStart: Boolean): Triple<String, String, String>? {
        val words = words(text)
        for (from in words.indices) {
            val marked = words[from] in RANGE_STARTS
            if (!marked && (requireStart || from > 0)) continue
            val first = if (marked) from + 1 else from
            for (to in first + 1 until words.size - 1) {
                if (words[to] !in RANGE_ENDS) continue
                val start = words.subList(first, to).joinToString(" ")
                val end = words.subList(to + 1, words.size).joinToString(" ")
                if (isDateOrTime(start) && isDateOrTime(end)) {
                    return Triple(words.subList(0, from).joinToString(" "), start, end)
                }
            }
        }
        return null
    }

    private fun isDateOrTime(text: String) = resolvers.dateTime(text) !is Resolution.Unresolvable

    /** "for an hour" (but not "in an hour", which is a start) */
    private fun isDuration(text: String): Boolean {
        val words = words(text)
        return words.size > 1 && words[0] in DURATION_CUES &&
                resolvers.duration(words.drop(1).joinToString(" ")) is Resolution.Resolved
    }

    private fun joinDayAndTime(day: String, time: String) =
        if (day.isBlank()) time else "$day at $time"

    private fun splitTrailing(text: String, matches: (List<String>) -> Boolean): Pair<String, String?> {
        val words = words(text)
        for (start in 1 until words.size) {
            val tail = words.subList(start, words.size)
            if (matches(tail)) {
                val head = words.subList(0, start).dropLastWhile { it in TRAILING_FILLERS }
                if (head.isNotEmpty()) {
                    return Pair(head.joinToString(" "), tail.joinToString(" "))
                }
            }
        }
        return Pair(text.trim(), null)
    }

    private fun words(text: String) = text.trim().split(SPACES).filter { it.isNotEmpty() }

    companion object {
        private val SPACES = Regex("\\s+")
        private val NOT_LETTERS = Regex("[^\\p{L}]+")

        private val REMINDER_CUES = setOf("remind", "reminder")
        private val CREATE_CUES = setOf(
            "calendar", "agenda", "schedule", "event", "appointment", "meeting", "block",
        )
        private val QUERY_CUES = setOf(
            "calendar", "agenda", "schedule", "timetable", "event", "events", "appointment",
            "appointments", "meeting", "meetings", "plan", "plans", "planned", "busy", "free",
            "occupied",
        )

        /**
         * Whether [input] has a word that the matched [sentence] can't do without. Sentences with
         * free-text captures match many commands of other skills almost as well.
         */
        fun hasCue(sentence: Calendar, input: String): Boolean {
            val cues = when (sentence) {
                is Calendar.Create -> CREATE_CUES
                is Calendar.Query -> QUERY_CUES
                is Calendar.Reminder -> REMINDER_CUES
            }
            return input.lowercase().split(NOT_LETTERS).any { it in cues }
        }

        /** Words a time phrase at the end of a title can start with. */
        val TIME_CUES = setOf(
            "at", "on", "in", "by", "this", "next", "today", "tomorrow", "tonight", "noon",
            "midnight", "the", "after", "monday", "tuesday", "wednesday", "thursday", "friday",
            "saturday", "sunday", "january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december",
        )
        val DURATION_CUES = setOf("for", "lasting")
        val RANGE_STARTS = setOf("from", "between")
        val RANGE_ENDS = setOf("to", "until", "till", "and")

        /** Left between a title and the time split off it: "call mom and" in "... and at five". */
        private val TRAILING_FILLERS = setOf("and")
    }
}
