package cc.aaro.gar.router.resolve

import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.tools.SlotKind

/**
 * The words that make a typed slot value (number, duration, time, date, datetime) worth asking
 * about when GAR can't resolve it: "later", "after lunch", "a few minutes", "this evening" are
 * vague but say when, while "timer", "to buy milk" or "france" say nothing of the kind, so a call
 * with them is a misroute. Used by Guard A ([SlotPlausibilityFilter]); English, like GAR's slot
 * resolution.
 *
 * Words are matched whole, also with a plural or possessive ending ("minutes", "tomorrow's",
 * "mondays"). Function words that go with any noun ("next", "this", "in", "at") don't count, nor
 * do "time", "clock" and "date" (the names of the tools' subjects: "turn off my alarm" ->
 * `alarm_set(time: "alarm")`), nor a part of day in a greeting ("good morning").
 */
object TypedValueWords {

    /** Whether [raw] has a word that can be (part of) a value of [kind]. */
    fun mentionsValue(kind: SlotKind, raw: String): Boolean {
        val words = Utterances.words(Utterances.normalize(raw))
        val vocabulary = when (kind) {
            SlotKind.NUMBER -> NUMBER_VOCABULARY
            SlotKind.DURATION -> DURATION_VOCABULARY
            SlotKind.TIME, SlotKind.DATE, SlotKind.DATETIME -> TEMPORAL_VOCABULARY
            else -> return false
        }
        return words.withIndex().any { (i, word) ->
            val stem = stem(word)
            (word.any { it.isDigit() } || stem in vocabulary) &&
                !(stem in PARTS_OF_DAY && words.getOrNull(i - 1) == "good")
        }
    }

    /** [word] without a plural or possessive ending, if it has one. */
    private fun stem(word: String): String = when {
        word in ALL_WORDS -> word
        word.endsWith("'s") -> word.dropLast(2)
        word.endsWith("s'") -> word.dropLast(2)
        word.endsWith("s") -> word.dropLast(1)
        else -> word
    }

    /** Not "one", which is mostly the pronoun ("the next one"); "one" alone is a bare number. */
    private val NUMBERS = setOf(
        "zero", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fourty", "fifty", "sixty", "seventy", "eighty",
        "ninety", "hundred", "thousand", "half", "quarter", "dozen",
    )

    /** Levels said without a number ("set the volume to the minimum"). */
    private val LEVELS = setOf("percent", "percentage", "max", "maximum", "min", "minimum")

    private val UNITS = setOf(
        "second", "sec", "minute", "min", "hour", "hr", "day", "week", "fortnight", "month", "year",
    )

    /** Amounts of time without a number ("a few minutes", "a while", "a bit"). */
    private val AMOUNTS = setOf("few", "couple", "several", "while", "bit", "moment")

    private val PARTS_OF_DAY = setOf(
        "morning", "afternoon", "evening", "night", "tonight", "noon", "midday", "midnight",
        "dawn", "dusk", "sunrise", "sunset", "bedtime", "overnight",
    )

    private val MEALS = setOf(
        "breakfast", "brunch", "lunch", "lunchtime", "dinner", "dinnertime", "supper",
    )

    private val CLOCK = setOf("o'clock", "oclock", "am", "pm")

    private val RELATIVE = setOf(
        "today", "tomorrow", "yesterday", "later", "soon", "now", "early", "earlier", "weekend",
        "weekday",
    )

    private val WEEKDAYS = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    )

    /** Not "may", which is mostly the verb. */
    private val MONTHS = setOf(
        "january", "february", "march", "april", "june", "july", "august", "september",
        "october", "november", "december",
    )

    private val ORDINALS = EnglishTime.ORDINALS

    private val NUMBER_VOCABULARY = NUMBERS + LEVELS
    private val DURATION_VOCABULARY = NUMBERS + UNITS + AMOUNTS
    private val TEMPORAL_VOCABULARY = NUMBERS + UNITS + AMOUNTS + PARTS_OF_DAY + MEALS + CLOCK +
        RELATIVE + WEEKDAYS + MONTHS + ORDINALS
    private val ALL_WORDS = NUMBER_VOCABULARY + TEMPORAL_VOCABULARY
}
