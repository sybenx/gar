package org.stypox.dicio.skills.math

import org.dicio.numbers.ParserFormatter
import org.dicio.numbers.unit.Number
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A spoken arithmetic expression that GAR understood as a whole (English): "seven plus nine",
 * "twelve times eight", "what's fifteen percent of eighty", "the square root of sixty four",
 * "five squared", "a twenty percent tip on eighty five".
 *
 * @param value the result, or null when it has none (division by zero)
 * @param interpretation the expression in symbols, for the card: "15% of 80 ="
 * @param plainOperators only +, -, x, ÷ and powers, which Dicio's calculator computes too
 * @param calculatorLead it starts like the calculator's sentence ("what is", "calculate")
 * @param extraWords it had polite or filler words around it ("please", "equals"), which the
 * calculator would not ignore
 */
data class MathExpression(
    val value: Double?,
    val interpretation: String,
    val plainOperators: Boolean,
    val calculatorLead: Boolean,
    val extraWords: Boolean,
)

/**
 * Reads spoken arithmetic: numbers (with dicio-numbers) between operator words, with the usual
 * precedence (powers, then x and ÷, then + and -). The whole input must be the expression, apart
 * from a lead ("what's", "how much is", "calculate") and a few fillers ("please", "equals"), and
 * it needs an operator, so that no command with a number in it ("set a timer for five minutes",
 * "call five five five") and no number alone is taken.
 */
object SpokenMath {
    /** [plain]: Dicio's calculator computes it too. */
    private enum class Op(val symbol: String, val plain: Boolean) {
        PLUS("+", true), MINUS("-", true), TIMES("x", true), DIVIDE("÷", true), POWER("^", true),
        PERCENT_OF("% of", false), SQUARED("²", false), CUBED("³", false), PERCENT("%", false),
        SQUARE_ROOT("√", false), CUBE_ROOT("∛", false),
    }

    private sealed interface Token {
        data class Num(val value: Double) : Token
        data class Operator(val op: Op) : Token
    }

    /** The expression [input] says, or null if it isn't only arithmetic. */
    fun parse(input: String, parserFormatter: ParserFormatter?, locale: Locale): MathExpression? {
        parserFormatter ?: return null
        var words = words(input)
        var calculatorLead = false
        var extraWords = false

        // fillers before the lead: "hey", "ok so", "please"
        while (words.isNotEmpty() && words[0] in LEADING_FILLERS) {
            words = words.drop(1)
            extraWords = true
        }
        val lead = LEADS.firstOrNull { lead -> words.take(lead.size) == lead }
        if (lead != null) {
            words = words.drop(lead.size)
            calculatorLead = lead in CALCULATOR_LEADS
        }
        while (words.isNotEmpty() && words.last() in TRAILING_FILLERS) {
            words = words.dropLast(1)
            extraWords = true
        }

        val tokens = tokenize(words, parserFormatter) ?: return null
        val parser = Parser(tokens, locale)
        val result = parser.expression() ?: return null
        // a number alone ("minus five", "fifty percent") is no calculation
        if (!parser.done || parser.operations == 0) {
            return null
        }
        val ops = tokens.filterIsInstance<Token.Operator>().map { it.op }
        return MathExpression(
            value = result.first.takeIf { it.isFinite() },
            interpretation = result.second + " =",
            plainOperators = ops.all { it.plain },
            calculatorLead = calculatorLead,
            extraWords = extraWords,
        )
    }

    /**
     * Operators and numbers; every run of words between two operators must be one number as a
     * whole ("one hundred and five", "a hundred", "3.5").
     */
    private fun tokenize(words: List<String>, parserFormatter: ParserFormatter): List<Token>? {
        val tokens = mutableListOf<Token>()
        var numberStart = 0
        var i = 0
        fun flushNumber(end: Int): Boolean {
            if (end > numberStart) {
                val value = number(words.subList(numberStart, end), parserFormatter) ?: return false
                tokens.add(Token.Num(value))
            }
            return true
        }
        while (i < words.size) {
            val rest = words.subList(i, words.size)
            val match = OPERATORS.firstOrNull { (phrase, _) -> rest.take(phrase.size) == phrase }
            if (match == null) {
                i += 1
                continue
            }
            if (!flushNumber(i)) {
                return null
            }
            tokens.add(Token.Operator(match.second))
            i += match.first.size
            numberStart = i
        }
        if (!flushNumber(words.size)) {
            return null
        }
        return tokens
    }

    /** The one number [words] are, also after "a" or "the" ("a twenty percent tip"). */
    private fun number(words: List<String>, parserFormatter: ParserFormatter): Double? {
        val withoutUnit =
            if (words.size > 1 && words.last() in CURRENCY_WORDS) words.dropLast(1) else words
        return wholeNumber(withoutUnit, parserFormatter)
            ?: withoutUnit.takeIf { it.size > 1 && it[0] in ARTICLES }
                ?.let { wholeNumber(it.drop(1), parserFormatter) }
    }

    private fun wholeNumber(words: List<String>, parserFormatter: ParserFormatter): Double? {
        val text = words.joinToString(" ")
        text.toDoubleOrNull()?.let { return it }
        val parts = parserFormatter.extractNumber(text).parseMixedWithText()
        val numbers = parts.filterIsInstance<Number>()
        if (numbers.size != 1 || parts.any { it is String && it.isNotBlank() }) {
            return null
        }
        val number = numbers[0]
        return if (number.isDecimal) number.decimalValue() else number.integerValue().toDouble()
    }

    /** Recursive descent over the tokens; each rule gives the value and its symbols. */
    private class Parser(private val tokens: List<Token>, locale: Locale) {
        private var position = 0
        private val format = DecimalFormat("#.##", DecimalFormatSymbols(locale))
        val done: Boolean get() = position == tokens.size

        /** How many operations were read: binary ones, powers and roots, not a sign. */
        var operations = 0
            private set

        private fun peek(): Op? = (tokens.getOrNull(position) as? Token.Operator)?.op

        private fun accept(vararg ops: Op): Op? {
            val op = peek()?.takeIf { it in ops } ?: return null
            position += 1
            return op
        }

        fun expression(): Pair<Double, String>? {
            var start = position
            var (value, text) = term() ?: return null
            // only percents so far ("10% + 5%"): they add up as they are
            var percents = isBarePercent(start)
            while (true) {
                val op = accept(Op.PLUS, Op.MINUS) ?: return Pair(value, text)
                start = position
                val (right, rightText) = term() ?: return null
                operations += 1
                val rightPercent = isBarePercent(start)
                // "100 + 20%" is 120 and "50 - 10%" 45, as on a phone's calculator: a percent
                // after plus or minus is of the value before it (tax, a discount)
                val change = if (rightPercent && !percents) value * right else right
                value = if (op == Op.PLUS) value + change else value - change
                percents = percents && rightPercent
                text = "$text ${op.symbol} $rightText"
            }
        }

        /** Whether the tokens read since [start] are a number and a percent, "20%". */
        private fun isBarePercent(start: Int): Boolean =
            position - start == 2 && tokens[start] is Token.Num &&
                (tokens[start + 1] as? Token.Operator)?.op == Op.PERCENT

        private fun term(): Pair<Double, String>? {
            var (value, text) = power() ?: return null
            while (true) {
                val op = accept(Op.TIMES, Op.DIVIDE, Op.PERCENT_OF) ?: return Pair(value, text)
                val (right, rightText) = power() ?: return null
                operations += 1
                value = when (op) {
                    Op.TIMES -> value * right
                    Op.DIVIDE -> value / right
                    else -> value / 100 * right
                }
                // "15% of 80", "12 x 8"
                text = if (op == Op.PERCENT_OF) {
                    "$text${op.symbol} $rightText"
                } else {
                    "$text ${op.symbol} $rightText"
                }
            }
        }

        private fun power(): Pair<Double, String>? {
            val (base, baseText) = unary() ?: return null
            accept(Op.POWER) ?: return Pair(base, baseText)
            val (exponent, exponentText) = power() ?: return null
            operations += 1
            return Pair(base.pow(exponent), "$baseText ^ $exponentText")
        }

        private fun unary(): Pair<Double, String>? {
            when (accept(Op.MINUS, Op.SQUARE_ROOT, Op.CUBE_ROOT)) {
                Op.MINUS -> return unary()?.let { (value, text) -> Pair(-value, "-$text") }
                Op.SQUARE_ROOT -> return unary()?.let { (value, text) ->
                    operations += 1
                    Pair(sqrt(value), "√$text")
                }
                Op.CUBE_ROOT -> return unary()?.let { (value, text) ->
                    operations += 1
                    Pair(cbrt(value), "∛$text")
                }
                else -> {}
            }
            val number = tokens.getOrNull(position) as? Token.Num ?: return null
            position += 1
            var value = number.value
            var text = format.format(value)
            while (true) {
                when (accept(Op.SQUARED, Op.CUBED, Op.PERCENT)) {
                    Op.SQUARED -> {
                        operations += 1
                        value *= value
                        text += "²"
                    }
                    Op.CUBED -> {
                        operations += 1
                        value *= value * value
                        text += "³"
                    }
                    Op.PERCENT -> {
                        // "fifteen percent of eighty" is PERCENT_OF; a percent alone is a fraction,
                        // which counts as an operation only next to another one
                        value /= 100
                        text += "%"
                    }
                    else -> return Pair(value, text)
                }
            }
        }
    }

    private fun words(input: String): List<String> {
        val spaced = input.lowercase(Locale.ROOT)
            .replace(DIGIT_GROUPS, "")
            .replace(Regex("(?<=[0-9])\\s*-\\s*(?=[0-9])"), " minus ")
            .replace(Regex("(?<![0-9a-z])-(?=[0-9])"), " minus ")
            .replace(Regex("(?<=[0-9])\\s*x\\s*(?=[0-9])"), " times ")
            .replace("+", " plus ").replace("*", " times ").replace("×", " times ")
            .replace("÷", " divided by ").replace("/", " divided by ")
            .replace("^", " to the power of ")
            .replace("%", " percent ").replace("=", " equals ").replace("√", " square root of ")
            .replace(Regex("(?<![0-9])\\.|\\.(?![0-9])"), " ")
            .replace(Regex("[?!,;:\"()]"), " ")
            .replace("-", " ")
            .replace(Regex("['\u2019]"), " ")
        return spaced.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private val DIGIT_GROUPS = Regex("(?<=[0-9]),(?=[0-9]{3})")

    private fun phrase(text: String) = text.split(' ')

    /** Longest first, so that "percent of" wins over "percent". */
    private val OPERATORS: List<Pair<List<String>, Op>> = listOf(
        "plus" to Op.PLUS, "minus" to Op.MINUS, "take away" to Op.MINUS,
        "times" to Op.TIMES, "multiplied by" to Op.TIMES, "x" to Op.TIMES,
        "divided by" to Op.DIVIDE, "over" to Op.DIVIDE,
        "to the power of" to Op.POWER, "raised to the power of" to Op.POWER,
        "raised to" to Op.POWER, "to the power" to Op.POWER,
        "percent of" to Op.PERCENT_OF, "per cent of" to Op.PERCENT_OF,
        "percent tip on" to Op.PERCENT_OF, "percent tip for" to Op.PERCENT_OF,
        "percent tip of" to Op.PERCENT_OF, "percent" to Op.PERCENT, "per cent" to Op.PERCENT,
        "squared" to Op.SQUARED, "cubed" to Op.CUBED,
        "the square root of" to Op.SQUARE_ROOT, "square root of" to Op.SQUARE_ROOT,
        "the cube root of" to Op.CUBE_ROOT, "cube root of" to Op.CUBE_ROOT,
    ).map { (text, op) -> Pair(phrase(text), op) }.sortedByDescending { it.first.size }

    /** Leads of the calculator's sentence ("calculate|evaluate|what is|whats|find the value of"). */
    private val CALCULATOR_LEADS = listOf(
        "calculate", "evaluate", "what is", "whats", "what s", "find the value of",
    ).map(::phrase)

    private val LEADS: List<List<String>> = (CALCULATOR_LEADS + listOf(
        "how much is", "how much s", "what does", "what do", "what would", "what will",
        "compute", "solve", "tell me", "can you calculate", "please calculate",
    ).map(::phrase)).sortedByDescending { it.size }

    private val LEADING_FILLERS = setOf("hey", "ok", "okay", "so", "please", "um", "uh")

    private val TRAILING_FILLERS =
        setOf("equals", "equal", "make", "makes", "be", "is", "please", "again")

    private val ARTICLES = setOf("a", "an", "the")
    private val CURRENCY_WORDS = setOf("dollars", "dollar", "bucks", "euros", "euro", "pounds")
}
