package cc.aaro.gar.router

import java.util.Locale

/**
 * Utterance normalization shared by [LlmRouter], the grammars (slot values are word spans of the
 * normalized utterance) and the validation of the model's output.
 */
object Utterances {
    /**
     * Lowercases [text], turns punctuation and symbols into spaces and collapses whitespace.
     * Kept: letters, digits and combining marks; apostrophes between two letters or digits
     * ("what's", "o'clock"; typographic apostrophes become `'`); `.`, `,` and `:` between two digits
     * ("7:15", "3.5"), and a `+` that starts a word before a digit (the `+` of a typed
     * international phone number, "+44 7700 900123"), which only typed input contains: speech
     * arrives as lowercase words, from Vosk or through [SpokenForm] ("plus four four ..."). The `+`
     * stays at the start of its word, so a span of the result normalizes to itself.
     */
    fun normalize(text: String): String {
        val cps = text.lowercase(Locale.ROOT).codePoints().toArray()
        val out = StringBuilder(cps.size)
        var pendingSpace = false
        for (i in cps.indices) {
            val c = cps[i]
            val prev = if (i > 0) cps[i - 1] else -1
            val next = if (i < cps.size - 1) cps[i + 1] else -1
            val keep = when {
                // before isWordChar: U+02BC is a letter, but only kept where the others are; and
                // not as a neighbour, since it comes out as a `'`, which isn't one
                c in APOSTROPHES -> isWordChar(prev) && prev !in APOSTROPHES &&
                    isWordChar(next) && next !in APOSTROPHES
                isWordChar(c) -> true
                c == '.'.code || c == ','.code || c == ':'.code ->
                    prev >= 0 && next >= 0 && Character.isDigit(prev) && Character.isDigit(next)
                // not after a word character: "5+3" stays two numbers
                c == '+'.code -> !isWordChar(prev) && next >= 0 && Character.isDigit(next)
                else -> false
            }
            if (keep) {
                if (pendingSpace && out.isNotEmpty()) {
                    out.append(' ')
                }
                pendingSpace = false
                if (c in APOSTROPHES) out.append('\'') else out.appendCodePoint(c)
            } else {
                pendingSpace = true
            }
        }
        return out.toString()
    }

    /** The words of an already normalized utterance; empty for an empty utterance. */
    fun words(normalized: String): List<String> =
        if (normalized.isEmpty()) listOf() else normalized.split(' ')

    /** Whether [value] (normalized) consists of consecutive words of [utteranceWords]. */
    fun isSpan(value: String, utteranceWords: List<String>): Boolean {
        val valueWords = words(value)
        if (valueWords.isEmpty() || valueWords.size > utteranceWords.size) {
            return false
        }
        return (0..utteranceWords.size - valueWords.size).any { start ->
            valueWords.indices.all { utteranceWords[start + it] == valueWords[it] }
        }
    }

    private fun isWordChar(c: Int): Boolean = c >= 0 && (
        Character.isLetterOrDigit(c) || when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK,
            Character.ENCLOSING_MARK -> true
            else -> false
        })

    private val APOSTROPHES = setOf('\''.code, 0x2019, 0x2018, 0x02BC)
}
