package org.stypox.dicio.skills.random

import cc.aaro.gar.router.Utterances
import org.dicio.numbers.ParserFormatter
import org.dicio.numbers.unit.Number

/** A coin to flip, dice to roll or a number to pick. */
sealed interface RandomRequest {
    data object Coin : RandomRequest

    /** [count] dice with [sides] sides each. */
    data class Dice(val count: Int, val sides: Int) : RandomRequest

    /** A whole number from [low] to [high], both included. */
    data class Pick(val low: Long, val high: Long) : RandomRequest
}

/** Reads the dice and the range out of what the sentences capture (English). */
object RandomPhrases {
    const val DEFAULT_SIDES = 6
    const val MAX_DICE = 20
    const val MAX_SIDES = 1000
    val DEFAULT_RANGE = RandomRequest.Pick(1, 10)

    /**
     * The dice in "roll <capture>": "a die", "two dice", "a pair of dice", "a d twenty", "d20",
     * "2d6", "a twenty sided die", "three six-sided dice". Null if it names no dice ("roll down
     * the window", "roll the credits") or too many.
     */
    fun dice(capture: String, parserFormatter: ParserFormatter?): RandomRequest.Dice? {
        var words = words(capture)
        while (words.isNotEmpty() && words.last() in TRAILING_WORDS) {
            words = words.dropLast(1)
        }
        // "for me"
        if (words.takeLast(2) == listOf("for", "me")) {
            words = words.dropLast(2)
        }
        val count: List<String>
        val sides: List<String>
        val dieIndex = words.indexOfLast { it in DIE_WORDS }
        if (dieIndex >= 0 && dieIndex == words.size - 1) {
            val beforeDie = words.subList(0, dieIndex)
            val sidedIndex = beforeDie.indexOfLast { it == "sided" || it == "side" }
            if (sidedIndex >= 0) {
                if (sidedIndex != beforeDie.size - 1) {
                    return null
                }
                val split = splitCountAndSides(beforeDie.subList(0, sidedIndex), parserFormatter)
                    ?: return null
                count = split.first
                sides = split.second
            } else {
                count = beforeDie
                sides = listOf()
            }
        } else {
            val dIndex = words.indexOfFirst { it in D_WORDS }
            if (dIndex < 0 || dIndex == words.size - 1) {
                return null
            }
            count = words.subList(0, dIndex)
            sides = words.subList(dIndex + 1, words.size)
        }
        val diceCount = count(count, parserFormatter) ?: return null
        val diceSides = if (sides.isEmpty()) {
            DEFAULT_SIDES.toLong()
        } else {
            integer(sides, parserFormatter) ?: return null
        }
        if (diceCount !in 1..MAX_DICE || diceSides !in 2..MAX_SIDES) {
            return null
        }
        return RandomRequest.Dice(diceCount.toInt(), diceSides.toInt())
    }

    /** A range "between [low] and [high]" in either order, or null if they aren't numbers. */
    fun range(low: String, high: String, parserFormatter: ParserFormatter?): RandomRequest.Pick? {
        val a = integer(words(low), parserFormatter) ?: return null
        val b = integer(words(high), parserFormatter) ?: return null
        val range = RandomRequest.Pick(minOf(a, b), maxOf(a, b))
        return range.takeIf { it.high - it.low in 0..MAX_RANGE }
    }

    /**
     * "twenty" in "a twenty sided": the words before "sided" are the count, if any, and then the
     * sides; the count is a single word ("a", "two", "3"), the sides may be more ("one hundred").
     */
    private fun splitCountAndSides(
        words: List<String>,
        parserFormatter: ParserFormatter?,
    ): Pair<List<String>, List<String>>? {
        if (words.isEmpty()) {
            return null
        }
        val first = words.subList(0, 1)
        if (words.size >= 2 && (words[0] in ONE || integer(first, parserFormatter) != null)) {
            val rest = words.drop(1)
            if (integer(rest, parserFormatter) != null) {
                return Pair(first, rest)
            }
        }
        return Pair(listOf(), words)
    }

    /** How many dice: nothing, "a", "the" and "one" are one, "a pair of" two. */
    private fun count(words: List<String>, parserFormatter: ParserFormatter?): Long? = when {
        words.isEmpty() || words.size == 1 && words[0] in ONE -> 1
        words == listOf("a", "pair", "of") || words == listOf("pair", "of") -> 2
        else -> integer(words, parserFormatter)
    }

    /** The whole number [words] say as a whole, or null. */
    fun integer(words: List<String>, parserFormatter: ParserFormatter?): Long? {
        val text = words.joinToString(" ")
        if (text.isEmpty()) {
            return null
        }
        text.toLongOrNull()?.let { return it }
        val parts = parserFormatter?.extractNumber(text)?.parseMixedWithText() ?: return null
        val numbers = parts.filterIsInstance<Number>()
        if (numbers.size != 1 || parts.any { it is String && it.isNotBlank() }) {
            return null
        }
        return numbers[0].takeIf { !it.isDecimal }?.integerValue()
    }

    /** Words, with "d20" and "2d6" split into "d 20" and "2 d 6". */
    private fun words(text: String): List<String> =
        Utterances.words(Utterances.normalize(text)).flatMap { word ->
            DICE_NOTATION.matchEntire(word)?.let { match ->
                val count = match.groupValues[1].takeIf { it.isNotEmpty() }
                listOfNotNull(count, "d", match.groupValues[2])
            } ?: listOf(word)
        }

    private val DICE_NOTATION = Regex("([0-9]*)d([0-9]+)")
    private val DIE_WORDS = setOf("die", "dice", "dices")
    /** "a d twenty" (STT may write the letter as a word). */
    private val D_WORDS = setOf("d", "dee")
    private val ONE = setOf("a", "an", "the", "one", "my")
    private val TRAILING_WORDS = setOf("please", "now", "again")
    private const val MAX_RANGE = 1_000_000_000_000L
}
