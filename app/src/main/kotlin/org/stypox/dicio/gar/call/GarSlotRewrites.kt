package org.stypox.dicio.gar.call

import cc.aaro.gar.router.resolve.TypedSlotRewrites
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import org.stypox.dicio.skills.holiday.Holidays
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Rewrites raw slot strings (from the model or from an answer to a GAR question) into what the
 * target skill understands, before they are resolved. English only, like the rest of GAR's
 * answer handling (see [normalizeAnswer]). Number, date and time slots get the router's
 * [TypedSlotRewrites], which the router's slot filter applies too ([garCallFilter]), so it judges
 * the same strings.
 */
object GarSlotRewrites {
    fun rewrite(tool: ToolSpec, slot: SlotSpec, raw: String): String = when {
        tool.skillId == TIMER_SKILL && slot.name == TIMER_NAME_SLOT -> timerName(raw)
        tool.skillId == CALCULATOR_SKILL && slot.name == CALCULATION_SLOT -> calculation(raw)
        // gar:answers
        tool.skillId == DATE_SKILL && slot.kind == SlotKind.DATE ->
            holidayDay(raw, LocalDate.now()) ?: TypedSlotRewrites.rewrite(tool, slot, raw)
        else -> TypedSlotRewrites.rewrite(tool, slot, raw)
    }

    /**
     * gar:answers. A holiday's name as the date it is next ("christmas" -> "december 25 2026",
     * "easter next year" -> "april 4 2027"), which GAR's date resolver reads, or null for any
     * other day. Applied to `date_query`'s day, also by the slot check, which would otherwise drop
     * the call ("christmas" is no day to the resolver).
     */
    fun holidayDay(raw: String, today: LocalDate): String? {
        val (holiday, year) = Holidays.findWithYear(raw, today) ?: return null
        val date = Holidays.date(holiday, today, year)
        val month = date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "${month.lowercase(Locale.ENGLISH)} ${date.dayOfMonth} ${date.year}"
    }

    /**
     * The label of a timer without the generic words around it: "the pasta timer" -> "pasta",
     * "my timer" -> "" (no name). The model often puts "timer" in the name slot, but the timer
     * skill takes any name as a filter, so "timer" would only match a timer called "timer" (or the
     * closest named one).
     */
    fun timerName(raw: String): String {
        val words = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            .filter { it.lowercase() !in TIMER_GENERIC_WORDS }
        return words.dropWhile { it.lowercase() in TIMER_LEADING_WORDS }.joinToString(" ")
    }

    /**
     * Prefix forms of operations into the infix form the calculator skill evaluates: it reads the
     * operators between numbers left to right and has no unary operators, so e.g. "divide one
     * hundred by four" would otherwise multiply.
     */
    fun calculation(raw: String): String {
        val text = raw.trim().replace(WHITESPACE, " ")
        for ((regex, replacement) in CALCULATION_REWRITES) {
            val match = regex.matchEntire(text) ?: continue
            return replacement(match.groupValues)
        }
        return raw
    }

    private const val TIMER_SKILL = "timer"
    private const val TIMER_NAME_SLOT = "name"
    private const val CALCULATOR_SKILL = "calculator"
    private const val CALCULATION_SLOT = "calculation"
    private const val DATE_SKILL = "date"

    private val WHITESPACE = Regex("\\s+")
    private val TIMER_GENERIC_WORDS = setOf(
        "timer", "timers", "countdown", "countdowns", "alarm", "alarms", "clock",
    )
    private val TIMER_LEADING_WORDS = setOf(
        "a", "an", "the", "my", "our", "your", "this", "that", "for", "called", "named",
    )

    private fun op(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    // the first one that matches the whole calculation wins; "and" is also a word of numbers
    // ("one hundred and five"), so it separates the operands only when no other word does
    private val CALCULATION_REWRITES: List<Pair<Regex, (List<String>) -> String>> = listOf(
        op("(?:the )?square root of (.+)") to { g -> "${g[1]} to the power of 0.5" },
        op("(.+?) percent of (.+)") to { g -> "${g[1]} times ${g[2]} divided by 100" },
        // gar:answers
        op("(?:an? |the )?(.+?) percent tip (?:on|for|of) (.+)") to
            { g -> "${g[1]} times ${g[2]} divided by 100" },
        op("(.+?) squared") to { g -> "${g[1]} to the power of 2" },
        op("(.+?) cubed") to { g -> "${g[1]} to the power of 3" },
        op("divide (.+?) by (.+)") to { g -> "${g[1]} divided by ${g[2]}" },
        op("multiply (.+?) (?:by|times|with) (.+)") to { g -> "${g[1]} times ${g[2]}" },
        op("multiply (.+?) and (.+)") to { g -> "${g[1]} times ${g[2]}" },
        op("(?:subtract|take away|take) (.+?) from (.+)") to { g -> "${g[2]} minus ${g[1]}" },
        op("(?:add|sum) (.+?) (?:to|plus|with) (.+)") to { g -> "${g[1]} plus ${g[2]}" },
        op("(?:add|sum) (.+?) and (.+)") to { g -> "${g[1]} plus ${g[2]}" },
    )
}

private val DIGIT_WORDS = mapOf(
    "zero" to '0', "oh" to '0', "o" to '0', "one" to '1', "two" to '2', "three" to '3',
    "four" to '4', "five" to '5', "six" to '6', "seven" to '7', "eight" to '8', "nine" to '9',
)

/**
 * A phone number spelled out digit by digit, as Vosk transcribes it ("five five five one two one
 * two" -> "5551212", "plus four four ..." -> "+44..."), or typed ("+44 7700 900123"), or null if
 * [text] is anything else.
 */
fun spokenPhoneNumber(text: String): String? {
    val words = normalizeAnswer(text).split(" ").filter { it.isNotEmpty() }
    // normalizeAnswer drops the "+" of a typed number
    val number = StringBuilder(if (text.trimStart().startsWith('+')) "+" else "")
    for ((i, word) in words.withIndex()) {
        when {
            i == 0 && word == "plus" && number.isEmpty() -> number.append('+')
            word.all { it in '0'..'9' } -> number.append(word)
            else -> number.append(DIGIT_WORDS[word] ?: return null)
        }
    }
    return number.toString().takeIf { n -> n.count { it in '0'..'9' } >= MIN_PHONE_DIGITS }
}

/** Short numbers like 112 or 911 count too. */
private const val MIN_PHONE_DIGITS = 3
