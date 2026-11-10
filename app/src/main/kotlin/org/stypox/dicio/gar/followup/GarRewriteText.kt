package org.stypox.dicio.gar.followup

import cc.aaro.gar.router.Utterances

/**
 * Remembered values in a rewrite's text (gar/DESIGN.md, "Follow-ups"). A rewrite is matched by
 * Dicio's sentences again, so a value only goes into it when no sentence could read part of it as
 * another slot: "send a text to Ben and Jerry saying hi" splits at "and", "meet me at 5 to talk"
 * at "to". A refused value goes to [GarRewrite.Direct] or a question, never into text.
 */
object GarRewriteText {
    /** Words that start or join another slot in GAR's English sentences. */
    val SLOT_WORDS = setOf(
        "to", "and", "saying", "that", "at", "in", "on", "for", "by", "with", "from", "then",
    )

    /**
     * Whether [value] may go into a rewrite's text: no comma, no digit inside a name ("5th",
     * "b2"), none of [SLOT_WORDS], and something left once normalized.
     */
    fun safe(value: String?): Boolean {
        if (value == null || ',' in value) {
            return false
        }
        val words = Utterances.normalize(value).split(" ").filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.none { word ->
            word in SLOT_WORDS || (word.any { it.isDigit() } && word.any { it.isLetter() })
        }
    }

    /** [value] as words for a rewrite ("Erin Smith" -> "erin smith"), or null when not [safe]. */
    fun words(value: String?): String? =
        value?.takeIf(::safe)?.let { Utterances.normalize(it) }
}
