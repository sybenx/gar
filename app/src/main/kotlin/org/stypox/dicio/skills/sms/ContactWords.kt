package org.stypox.dicio.skills.sms

import cc.aaro.gar.router.resolve.NameMatcher
import org.stypox.dicio.gar.call.isPositionWord
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.skills.conversation.ConversationWords

/**
 * How texts, calls and emails read a contact as said, Google style. English, like GAR's other
 * answers:
 * - "myself", "me", "my own number" are the phone's owner ([ownRest]);
 * - "no, to Aaron" in reply to "Which one?" or "Who?" corrects the name ([correction]), and
 *   "not the first one" asks again ([rejectsOnly]);
 * - a contact matches only by whole words ([matches]): "erin" is not "Katerina".
 */
object ContactWords {
    /**
     * What follows the owner in [spoken]: "" when [spoken] is the owner ("myself", "me", "my
     * own number"), the message after "me" or "myself" ("myself buy milk" -> "buy milk"), or null
     * when [spoken] names someone else ("mom", "my wife", "my phone company").
     */
    fun ownRest(spoken: String): String? {
        val words = spoken.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val normalized = words.map(::normalizeAnswer)
        if (normalized in OWN_PHRASES) {
            return ""
        }
        return OWN_PRONOUNS.firstOrNull { normalized.take(it.size) == it }
            ?.let { words.drop(it.size).joinToString(" ") }
    }

    /** Whether [spoken] is the owner and nothing else: "myself", "me", "my phone". */
    fun isOwn(spoken: String): Boolean = ownRest(spoken) == ""

    /**
     * The new name in an answer that corrects a name GAR heard ("no, to Aaron" -> "Aaron", "no I
     * meant mom" -> "mom", "not him, dad" -> "dad", "no, not the first one, the second one" ->
     * "second one"), or null when [input] doesn't start with a negation, names no one after it
     * ("no thanks", "no don't", "not the first one") or is one of the conversation's stop and
     * repeat phrases ("no, I'm good", "no, forget it", "not now", "no, what did you say").
     *
     * @param names the contacts just offered, so that "not sarah lee sarah connor" leaves out the
     * whole name that isn't wanted
     */
    fun correction(input: String, names: List<String> = listOf()): String? = read(input, names)?.name

    /**
     * Whether [input] only says who it isn't ("not the first one", "no, not him", "not mom"):
     * then GAR asks "Which one?" again instead of picking or looking anyone up.
     */
    fun rejectsOnly(input: String, names: List<String> = listOf()): Boolean =
        read(input, names)?.let { it.name == null && it.rejected } == true

    /** "no, to Aaron" -> "Aaron"; any other [input] as it is. */
    fun corrected(input: String): String = correction(input) ?: input

    /**
     * Whether the contact [name] matches [spoken] as a name: [NameMatcher] finds it, or they
     * share a whole word ([NameMatcher.sharesWord]). Dicio's contact distance counts letters
     * anywhere, so it also finds "erin" in "Katerina Book Club" and "myself" in "Myrtle Seller".
     */
    fun matches(spoken: String, name: String): Boolean =
        NameMatcher.similarity(spoken, name) >= NameMatcher.MIN_SCORE ||
            NameMatcher.sharesWord(spoken, name)

    /** A correction: the new name, if any, and whether "not ..." left someone out. */
    private class Read(val name: String?, val rejected: Boolean)

    private fun read(input: String, names: List<String>): Read? {
        val words = input.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val normalized = words.map(::normalizeAnswer)
        if (normalized.firstOrNull() !in NEGATIONS ||
            ConversationWords.isStop(input) || ConversationWords.isRepeat(input)
        ) {
            return null
        }
        // "not the first one", "not him", "not mom" says who it isn't: left out with the "not"
        val not = normalized.indexOf("not")
        val rejectedEnd = if (not < 0) -1 else rejectedEnd(words, normalized, not + 1, names)
        val kept = words.indices.filter { not < 0 || it !in not until rejectedEnd }
        val start = kept.firstOrNull { normalized[it].isNotEmpty() && normalized[it] !in CORRECTION_WORDS }
        val name = start?.let { kept.filter { i -> i >= it } }
            ?.joinToString(" ") { words[it] }
            ?.trim { it in PUNCTUATION }
            ?.ifEmpty { null }
        return Read(name, rejectedEnd > not + 1)
    }

    /**
     * Where the words after a "not" at [from] stop saying who it isn't: a position ("the first
     * one"), the words up to a comma ("him, dad"), a pronoun ("him dad"), the longest name of
     * [names] that leaves a word after it ("sarah lee sarah connor"), else one word ("mom dad").
     */
    private fun rejectedEnd(
        words: List<String>,
        normalized: List<String>,
        from: Int,
        names: List<String>,
    ): Int {
        var i = from
        if (normalized.getOrNull(i) == "the") {
            ++i
        }
        val word = normalized.getOrNull(i) ?: return words.size
        if (isPositionWord(word) || word in PRONOUNS) {
            ++i
            if (normalized.getOrNull(i) == "one" && words[i - 1].last() !in PUNCTUATION) {
                ++i
            }
            return i
        }
        (from until words.size - 1).firstOrNull { words[it].last() in PUNCTUATION }
            ?.let { return it + 1 }
        val listed = names.map(::normalizeAnswer).toSet()
        return (words.size - 1 downTo i + 1)
            .firstOrNull { normalized.subList(i, it).joinToString(" ") in listed }
            ?: (i + 1)
    }

    private val WHITESPACE = Regex("\\s+")
    private const val PUNCTUATION = ",.;:!?"

    /** The owner as a whole recipient. */
    private val OWN_PHRASES = setOf(
        "me", "myself", "my self", "my number", "my own number", "my phone", "my own phone",
        "my phone number", "my own phone number", "my cell", "my cell phone",
    ).map { it.split(" ") }

    /** The owner before a message: "text myself buy milk". */
    private val OWN_PRONOUNS = listOf("my self", "myself", "me").map { it.split(" ") }

    private val NEGATIONS = setOf("no", "nope", "nah", "not", "wrong")

    /** Who "not" leaves out without a name: "not him, dad". */
    private val PRONOUNS = setOf("him", "her", "them", "that", "this", "those", "these")

    /** What may come between the negation and the new name, and what is no name on its own. */
    private val CORRECTION_WORDS = NEGATIONS + setOf(
        "him", "her", "them", "that", "this", "one", "person", "contact", "the", "other",
        "i", "meant", "mean", "said", "say", "wanted", "want", "it's", "its", "it", "is", "was",
        "to", "send", "text", "message", "call", "email", "a", "an",
        "sorry", "actually", "wait", "oh", "uh", "um", "ok", "okay", "please", "instead",
        "don't", "dont", "do", "never", "mind", "nevermind", "thanks", "thank", "you", "nothing",
        "nobody", "none", "cancel", "stop", "of", "those", "these", "neither", "either",
    )
}
