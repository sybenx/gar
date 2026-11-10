package org.stypox.dicio.skills.dictionary

import cc.aaro.gar.router.Utterances

/** A word to define ("define serendipity") or to find other words for ("synonym for happy"). */
data class DictionaryRequest(val word: String, val synonyms: Boolean)

/**
 * The dictionary's English requests, read in Kotlin (no Dicio sentences, like the math skill), so
 * that only a whole request of these forms reaches it:
 * - definitions: "define X", "(what is) the definition|meaning of X", "what does X mean";
 * - synonyms: "(what is) a synonym for|of X", "synonyms for X", "(what's) another word for X".
 *
 * "the word" before X is dropped; X is one to three words of letters, and no pronoun ("what does
 * that mean" asks about something else). "How do you spell X" stays the spelling skill's.
 */
object DictionaryRequests {
    fun parse(input: String): DictionaryRequest? {
        val text = stripTrailing(stripLeads(Utterances.words(Utterances.normalize(input))))
            .joinToString(" ")
        for ((pattern, synonyms) in PATTERNS) {
            val word = pattern.matchEntire(text)?.groupValues?.get(1) ?: continue
            return wordOf(word)?.let { DictionaryRequest(it, synonyms) }
        }
        return null
    }

    /** [said] without "the word", if it is a word to look up. */
    private fun wordOf(said: String): String? {
        val words = Utterances.words(said).let {
            if (it.take(2) == listOf("the", "word")) it.drop(2) else it
        }
        if (words.isEmpty() || words.size > MAX_WORDS || words.any { it in NOT_WORDS } ||
            words.any { word -> !word.all { it.isLetter() || it == '\'' || it == '-' } }
        ) {
            return null
        }
        return words.joinToString(" ")
    }

    private fun stripLeads(words: List<String>): List<String> {
        var rest = words
        while (true) {
            val lead = LEADS.firstOrNull { lead ->
                rest.size > lead.size && rest.subList(0, lead.size) == lead
            } ?: return rest
            rest = rest.drop(lead.size)
        }
    }

    private fun stripTrailing(words: List<String>): List<String> =
        if (words.size > 1 && words.last() == "please") words.dropLast(1) else words

    private const val MAX_WORDS = 3

    private const val WHAT_IS = "(?:what is |what's |whats |what s |what are )?"
    private const val WORD = "(.+)"

    private val PATTERNS = listOf(
        Regex("define $WORD") to false,
        Regex("$WHAT_IS(?:the |a )?(?:definition|meaning|meanings|definitions) of $WORD") to false,
        Regex("what does $WORD mean") to false,
        Regex("$WHAT_IS(?:a |some |the |any )?synonyms? (?:for|of) $WORD") to true,
        Regex("$WHAT_IS(?:another|other|a different|different|a) words? for $WORD") to true,
    )

    private val LEADS = listOf(
        listOf("hey"), listOf("ok"), listOf("okay"), listOf("so"), listOf("please"),
        listOf("can", "you"), listOf("could", "you"), listOf("would", "you"),
        listOf("tell", "me"), listOf("give", "me"), listOf("do", "you", "know"),
        listOf("i", "want", "to", "know"), listOf("look", "up"),
    )

    /** Pronouns and the like: "what does that mean", "define it", "the meaning of life". */
    private val NOT_WORDS = setOf(
        "it", "that", "this", "these", "those", "he", "she", "they", "you", "i", "me", "we", "him",
        "her", "them", "us", "my", "your", "his", "their", "our", "life",
    )
}
