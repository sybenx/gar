package cc.aaro.gar.router.resolve

import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec

/**
 * English rewrites of number, date and time slots that the model (or a sentence capture) fills
 * with words the resolvers can't read, although they mean a value: "half" as a volume level,
 * "next" as the alarm to cancel, "date" as the day of "what's the date", "tonight" as the day of
 * the calendar's events. Applied before resolving by GAR's call runner (through the app's
 * `GarSlotRewrites`) and by [SlotPlausibilityFilter], so both judge the same strings. A rewrite to
 * "" means the slot was left out.
 */
object TypedSlotRewrites {
    fun rewrite(tool: ToolSpec, slot: SlotSpec, raw: String): String = when {
        tool.skillId == VOLUME_SKILL && slot.name == VOLUME_LEVEL_SLOT -> volumeLevel(raw)
        tool.name == ALARM_SET_TOOL && slot.name == ALARM_TIME_SLOT -> alarmToSet(raw)
        tool.name == ALARM_CANCEL_TOOL && slot.name == ALARM_TIME_SLOT -> alarmToCancel(raw)
        tool.name == CALENDAR_QUERY_TOOL && slot.kind == SlotKind.DATE -> day(dayOfTimeOfDay(raw))
        slot.kind == SlotKind.DATE -> day(raw)
        else -> raw
    }

    /** The level "half" stands for, as GAR resolves it. */
    const val HALF_LEVEL = "fifty percent"

    /** The level "max" stands for, as GAR resolves it. */
    const val MAX_LEVEL = "one hundred percent"

    /** "halfway", "the middle": half the range, which the number resolver can't read. */
    fun isHalfLevel(raw: String): Boolean = words(raw).joinToString(" ") in HALF_WORDS

    /** "max", "full volume": the top of the range. */
    fun isMaxLevel(raw: String): Boolean = words(raw).joinToString(" ") in MAX_WORDS

    /**
     * A volume level as the volume sentences read it: "half" (which the number resolver reads as
     * 0.5, a level of 0.5 out of ten) is fifty percent, "max" one hundred percent, and the volume
     * itself isn't part of the level ("volume thirty percent"). Words around no level ("the" of
     * "set the volume") are no level, so GAR asks for one.
     */
    fun volumeLevel(raw: String): String {
        val words = words(raw)
        val level = words.filter { it !in VOLUME_NOUNS }
        // "to max", "at the max": the words the number resolver skips before a level
        val bareLevel = level.dropWhile { it in LEVEL_PREPOSITIONS }.joinToString(" ")
        return when {
            isMaxLevel(raw) -> MAX_LEVEL
            level.all { it in NO_LEVEL_WORDS } -> ""
            isHalfLevel(bareLevel) -> HALF_LEVEL
            isMaxLevel(bareLevel) -> MAX_LEVEL
            level.size < words.size -> level.joinToString(" ")
            else -> raw
        }
    }

    /**
     * No time for the alarm to set when it is only the next day ("tomorrow", "tomorrow morning",
     * "tonight", [isNextDay]): GAR asks "What time?" instead of saying it didn't understand.
     */
    fun alarmToSet(raw: String): String = if (isNextDay(raw)) "" else raw

    /**
     * "tomorrow", "tomorrow morning", "tonight": the alarm's day but not its time. Only today and
     * tomorrow: an alarm rings at the next occurrence of its time, so "friday" can't be kept.
     */
    fun isNextDay(raw: String): Boolean {
        val words = words(raw)
        return words.isNotEmpty() && words.all { it in NEXT_DAY_WORDS } &&
            words.any { it in DAY_WORDS }
    }

    /**
     * No time for the alarm to cancel when it is only "next", the day of the next alarm and the
     * like: "skip my next alarm" and "turn off tomorrow's alarm" cancel the next alarm, as
     * without a time. Any other word keeps the time as said.
     */
    fun alarmToCancel(raw: String): String =
        if (words(raw).all { it in NEXT_ALARM_WORDS }) "" else raw

    /**
     * The day asked about. Only words that name no day ("date" in "what's the date", "the week"
     * in "what day of the week is it") mean today, i.e. no day; "today's date" is today; a number
     * of days without "in" ("three days") is that many days from today.
     */
    fun day(raw: String): String {
        val words = words(raw).map { if (it == "today's" || it == "todays") "today" else it }
        val meaningful = words.filter { it !in DAYLESS_WORDS }
        return when {
            // before the day-less words, which include "a" and the units ("a week")
            words.size >= 2 && words.last() in DAY_UNITS &&
                words.dropLast(1).all { it in COUNT_WORDS || it.all(Char::isDigit) } ->
                "in " + words.joinToString(" ")
            meaningful.isEmpty() -> ""
            meaningful == listOf("today") -> "today"
            else -> raw
        }
    }

    /**
     * The day of a time of day, for the calendar's events: "tomorrow morning" is tomorrow,
     * "friday at three" friday, "tonight", "this afternoon" and "at seven" today (""). A bare
     * number ("seven", which may be a day of the month) and words without a time stay as said.
     */
    fun dayOfTimeOfDay(raw: String): String {
        val words = words(raw).map { if (it == "tonight's" || it == "tonights") "tonight" else it }
        val extraction = try {
            EnglishTime.extract(words)
        } catch (e: EnglishTime.TimeException) {
            return raw
        }
        // "friday at", "in the": the words the time was said with go too
        val day = extraction.rest.dropLastWhile { it in TIME_FILLERS }
        val cue = extraction.partOfDay != null || extraction.meridiem != null
        val clock = extraction.clock?.let { it.absolute || "at" in words || day.isNotEmpty() } == true
        return if (cue || clock) day.joinToString(" ") else raw
    }

    private fun words(raw: String) = Utterances.words(Utterances.normalize(raw))

    private const val CALENDAR_QUERY_TOOL = "calendar_query"
    private const val VOLUME_SKILL = "volume"
    private const val VOLUME_LEVEL_SLOT = "level"
    private const val ALARM_SET_TOOL = "alarm_set"
    private const val ALARM_CANCEL_TOOL = "alarm_cancel"
    private const val ALARM_TIME_SLOT = "time"

    private val HALF_WORDS = setOf("half", "a half", "halfway", "half way", "the middle")
    private val MAX_WORDS = setOf(
        "max", "maximum", "the max", "the maximum", "full", "full volume", "the top", "top",
    )
    private val VOLUME_NOUNS = setOf("volume", "sound", "level")
    private val LEVEL_PREPOSITIONS = setOf("to", "at")
    private val NO_LEVEL_WORDS = LEVEL_PREPOSITIONS + setOf("the", "my", "a", "its")

    /** Words left around a time of day that say nothing about the day: "at", "in the". */
    private val TIME_FILLERS = setOf("at", "in", "the", "on", "for", "around", "about", "by", "of")
    private val DAY_WORDS = setOf(
        "today", "today's", "tomorrow", "tomorrow's", "tonight", "tonight's",
    )
    private val NEXT_DAY_WORDS = DAY_WORDS + setOf(
        "in", "the", "this", "for", "morning", "afternoon", "evening", "night",
    )
    private val NEXT_ALARM_WORDS = DAY_WORDS + setOf(
        "next", "upcoming", "first", "my", "the", "one", "alarm", "alarms", "for",
    )
    private val DAYLESS_WORDS = setOf(
        "date", "day", "the", "of", "week", "month", "year", "it", "is", "this", "current", "a",
        "now", "right", "currently",
    )
    private val DAY_UNITS = setOf("day", "days", "week", "weeks", "month", "months", "year", "years")
    private val COUNT_WORDS = setOf(
        "a", "an", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fourty", "fifty", "sixty", "seventy", "eighty",
        "ninety", "hundred",
    )
}
