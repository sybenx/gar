package org.stypox.dicio.gar.stt

import java.util.Locale

/**
 * What Whisper writes for silence, music or a room's noise: subtitle credits, "Thank you.", "♪"
 * (gar/DESIGN.md, "Continued conversation"). Only continued-listening windows treat these as
 * silence, since nobody asked anything there; a capture the user started keeps them, "thank you"
 * is small talk then. The lists grow from the voice log.
 */
object GarWhisperHallucinations {
    /** Whole inputs that are silence, by language. */
    private val EXACT = mapOf(
        "en" to setOf(
            "thank you", "thanks for watching", "thank you for watching", "you", "bye", "music",
            "blank audio",
        ),
    )

    /** Starts of inputs that are silence (subtitle credits and sign-offs), by language. */
    private val PREFIXES = mapOf(
        "de" to listOf("untertitel", "vielen dank fürs zuschauen"),
        "fr" to listOf("sous titres", "merci d avoir regardé"),
        "es" to listOf("subtítulos", "gracias por ver"),
        "it" to listOf("sottotitoli"),
        "pt" to listOf("legendas"),
        "nl" to listOf("ondertiteling"),
    )

    /** Bracketed tags: "[BLANK_AUDIO]", "(music)", "*laughs*". */
    private val TAGS = Regex("""\[[^\]]*\]|\([^)]*\)|\*[^*]*\*""")

    /**
     * Whether [text], heard in a continued-listening window in [locale], is silence: one of the
     * language's phrases, or only tags and symbols.
     */
    fun isSilence(locale: Locale, text: String): Boolean {
        if (words(locale, TAGS.replace(text, " ")).isEmpty()) {
            return true
        }
        val said = words(locale, text)
        val language = locale.language
        return said in EXACT[language].orEmpty() ||
            PREFIXES[language].orEmpty().any { said == it || said.startsWith("$it ") }
    }

    /**
     * [text] lowercase, apostrophes and dashes as spaces, without other punctuation and symbols,
     * one space between words.
     */
    internal fun words(locale: Locale, text: String): String = buildString {
        for (c in text.lowercase(locale)) {
            append(if (Character.isLetterOrDigit(c)) c else ' ')
        }
    }.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
