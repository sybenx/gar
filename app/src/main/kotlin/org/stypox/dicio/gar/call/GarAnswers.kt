package org.stypox.dicio.gar.call

// English-only matching of short follow-up answers ("the second one", "pm", "never mind").
// GAR only routes English input for now (see GarFallbackImpl).

/**
 * Lowercase, punctuation (except apostrophes) removed, whitespace collapsed. Typographic
 * apostrophes between letters, digits or marks count as apostrophes, as in the router's
 * `Utterances.normalize`; unlike there, punctuation between digits splits them ("7:00" is
 * "7 00"), which is fine as long as both sides of a comparison are normalized here.
 */
fun normalizeAnswer(input: String): String {
    return input.lowercase()
        .replace(TYPOGRAPHIC_APOSTROPHE, "'")
        .replace(Regex("[^\\p{L}\\p{N}' ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private val TYPOGRAPHIC_APOSTROPHE =
    Regex("(?<=[\\p{L}\\p{N}\\p{M}])[\u2018\u2019\u02BC](?=[\\p{L}\\p{N}\\p{M}])")

private val FILLER_WORDS = setOf("please", "ok", "okay", "oh", "just", "uh", "um")

/** The words of [input] after [normalizeAnswer], without fillers like "please" or "okay". */
private fun answerWords(input: String): List<String> =
    normalizeAnswer(input).split(" ").filter { it.isNotEmpty() && it !in FILLER_WORDS }

/**
 * The yes/no core of GAR's spoken answers to a yes/no question ("Ready to send it?", "Turn it
 * on?"), shared by the cards that ask one (`SmsCardAnswers`, `GarOnlineAnswers`). Each card keeps
 * its own check of which words make an answer, its extra words and its go-ahead verbs.
 */
object GarYesNo {
    val YES_WORDS = setOf(
        "yes", "yeah", "yep", "yup", "yea", "ya", "sure", "ok", "okay", "alright", "right",
        "perfect", "good", "great", "fine", "absolutely", "certainly", "affirmative", "proceed",
        "continue",
    )

    /**
     * Mild no's, which only a later explicit go-ahead overrides: "no wait, turn it on", "no, you
     * send it", but not "no, it's fine", a polite no.
     */
    val NO_WORDS = setOf("no", "nope", "nah", "nevermind")

    /** Negations that always say no: "don't", "do not", "never", "cancel", "stop". */
    val NEGATIONS = setOf(
        "don't", "dont", "not", "never", "cancel", "stop", "forget", "quit", "negative",
    )

    /** Whether [words] has a negation of [negations] at [index]: "never", but not "never mind". */
    fun isNegation(words: List<String>, index: Int, negations: Set<String> = NEGATIONS): Boolean =
        words[index] in negations &&
            !(words[index] == "never" && words.getOrNull(index + 1) == "mind")

    /**
     * The answer of [words], which the card checked for negations already: without a no, a yes or
     * a go-ahead is a yes; after a no ([NO_WORDS], "never mind" or [isNo]), only a later go-ahead
     * is a yes ("no wait, turn it on"), while "no, it's fine" and "yes... no" are a no. Null when
     * [words] say neither.
     *
     * @param isYes whether the word at an index is a yes
     * @param isGoAhead whether an explicit go-ahead starts at an index ("send it", "turn it on")
     * @param isNo whether the word at an index is a no besides [NO_WORDS] and "never mind"
     */
    fun answer(
        words: List<String>,
        isYes: (Int) -> Boolean,
        isGoAhead: (Int) -> Boolean,
        isNo: (Int) -> Boolean = { false },
    ): Boolean? {
        val goAhead = words.indices.lastOrNull(isGoAhead)
        val yes = words.indices.lastOrNull(isYes)
        val no = words.indices.lastOrNull {
            words[it] in NO_WORDS || (words[it] == "never" && words.getOrNull(it + 1) == "mind") ||
                isNo(it)
        }
        return when {
            no == null -> if (goAhead != null || yes != null) true else null
            goAhead != null && goAhead > no -> true
            else -> false
        }
    }
}

private val CANCEL_PHRASES = setOf(
    "cancel", "cancel it", "cancel that", "never mind", "nevermind", "stop", "stop it",
    "forget it", "forget about it",
)

fun isCancelPhrase(input: String): Boolean {
    return answerWords(input).joinToString(" ") in CANCEL_PHRASES
}

/** Whether [word] says a position in a list: "first", "2nd", "two", "last". */
fun isPositionWord(word: String): Boolean =
    word == "last" || ORDINALS.any { word in it } || CARDINALS.any { word in it }

// up to ten: questions show every option with its number (the stopwatch has six actions)
private val ORDINALS = listOf(
    setOf("first", "1st"),
    setOf("second", "2nd"),
    setOf("third", "3rd"),
    setOf("fourth", "4th"),
    setOf("fifth", "5th"),
    setOf("sixth", "6th"),
    setOf("seventh", "7th"),
    setOf("eighth", "8th"),
    setOf("ninth", "9th"),
    setOf("tenth", "10th"),
)
private val CARDINALS = listOf(
    setOf("one", "1"),
    setOf("two", "2"),
    setOf("three", "3"),
    setOf("four", "4"),
    setOf("five", "5"),
    setOf("six", "6"),
    setOf("seven", "7"),
    setOf("eight", "8"),
    setOf("nine", "9"),
    setOf("ten", "10"),
)

/**
 * @return the zero-based index of the option picked by position ("first", "the second one",
 * "number three", "last", "second to last", "second from the end"), or null if [input] doesn't
 * pick by position or the index is out of range
 */
fun parseOptionIndex(input: String, optionCount: Int): Int? {
    val said = answerWords(input).filter { it != "the" }
    // "from the end" counts like "from the last"; "not the first one, the second one" doesn't
    // pick the position after "not"
    val words = said.mapIndexed { i, word ->
        if (word == "end" && said.getOrNull(i - 1) == "from") "last" else word
    }.filterIndexed { i, _ -> said.getOrNull(i - 1) != "not" }
    val index = if ("last" in words) {
        optionsAfter(words)?.let { optionCount - 1 - it }
    } else {
        // the first position said, not the lowest
        words.firstNotNullOfOrNull { word -> ORDINALS.indexOfFirst { word in it }.takeIf { it >= 0 } }
            // a bare number ("two", "number two") also picks by position
            ?: words.filter { it != "number" }
                .singleOrNull()
                ?.let(::cardinal)
                ?.let { it - 1 }
    }
    return index?.takeIf { it in 0 until optionCount }
}

/**
 * How many options come after the one [words] pick from the end: 0 for "last" and "the very
 * last", 1 for "second to last", "second from last", "second last", "next to last", "one before
 * last" and "last but one", 2 for "two before last". Null for a number this doesn't understand
 * ("two to last", "two last"), so that nothing is picked.
 */
private fun optionsAfter(words: List<String>): Int? {
    val last = words.indexOf("last")
    if (words.getOrNull(last + 1) == "but") {
        return cardinal(words.getOrNull(last + 2))
    }
    return when (val before = words.getOrNull(last - 1)) {
        // "two before last" counts, "second before last" is unclear
        "before" -> words.getOrNull(last - 2).let { count ->
            cardinal(count) ?: if (ordinal(count) == null) 1 else null
        }
        "to", "from" -> when (val position = words.getOrNull(last - 2)) {
            "next" -> 1
            // "the second one from last"
            "one" -> ordinal(words.getOrNull(last - 3))
            else -> ordinal(position)
        }
        // "second last"; "next last" or "two last" is no position this understands, and other
        // words ("the very last") don't change it
        else -> ordinal(before) ?: if (before == "next" || cardinal(before) != null) null else 0
    }
}

/** 0 for "first" or "1st", 1 for "second" and so on, null for any other word. */
private fun ordinal(word: String?): Int? =
    ORDINALS.indexOfFirst { word in it }.takeIf { it >= 0 }

/** 1 for "one" or "1", 2 for "two" and so on, null for any other word. */
private fun cardinal(word: String?): Int? =
    CARDINALS.indexOfFirst { word in it }.takeIf { it >= 0 }?.let { it + 1 }

private val AM_PHRASES = setOf("am", "a m", "morning", "in the morning", "this morning")
private val PM_PHRASES = setOf(
    "pm", "p m", "afternoon", "in the afternoon", "this afternoon", "evening", "in the evening",
    "this evening", "night", "at night", "tonight",
)

/** @return false for "am"/"morning", true for "pm"/"evening"/"tonight", null otherwise */
fun parseAmPm(input: String): Boolean? {
    val phrase = answerWords(input).joinToString(" ")
    return when (phrase) {
        in AM_PHRASES -> false
        in PM_PHRASES -> true
        else -> null
    }
}
