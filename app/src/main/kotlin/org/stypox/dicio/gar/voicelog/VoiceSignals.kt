package org.stypox.dicio.gar.voicelog

import kotlin.math.max
import kotlin.math.min

/**
 * The failure signals a turn gives about the turn before it (gar/DESIGN.md, "Voice log"): the
 * user retried or rephrased it soon after, said no, cancel, stop or "that's wrong" when that was
 * no command, cancelled a confirmation, or edited its transcript. Pure, for tests.
 */
object VoiceSignals {
    /** A repeat or rephrase counts only when the next turn starts this soon after the reply. */
    const val REPEAT_WINDOW_MILLIS = 20_000L

    /** Character similarity (see [similarity]) from which two inputs are a near-repeat. */
    const val REPEAT_SIMILARITY = 0.75

    /** Share of the shorter input's words in the other from which it is a rephrase. */
    const val REPHRASE_OVERLAP = 0.6

    const val REPEAT = "repeat"
    const val REPHRASE = "rephrase"
    const val SAID_NO = "said_no"
    const val SAID_CANCEL = "said_cancel"
    const val SAID_STOP = "said_stop"
    const val SAID_WRONG = "said_wrong"
    const val I_SAID = "i_said"
    const val CANCELLED_CONFIRMATION = "cancelled_confirmation"
    const val EDITED_TRANSCRIPT = "edited_transcript"
    const val NOT_UNDERSTOOD = "not_understood"
    const val ASKED_TO_REPEAT = "asked_to_repeat"
    const val ERROR = "error"

    const val OUTCOME_ANSWERED = "answered"
    const val PATH_FOLLOW_UP_ANSWER = "follow_up_answer"
    const val PATH_MODEL = "model"
    const val PATH_FALLBACK = "fallback"
    const val CHECK_REJECT = "reject"

    /** The skills that take a bare "stop" or "cancel" ("Stop: who takes it"). */
    private val STOP_SKILLS = setOf("timer_control", "alarm", "media_controls")
    private val TROUBLED_MODEL_OUTCOMES = setOf("dropped", "no_match", "failed")
    private val TROUBLED_SUSPECT = setOf(NOT_UNDERSTOOD, ASKED_TO_REPEAT, ERROR)

    private val FILLERS = setOf("oh", "uh", "um", "hmm", "ok", "okay", "hey", "please", "sorry")

    private val NO = setOf("no", "nope", "nah", "no no", "no no no", "no way")
    private val CANCEL = setOf(
        "cancel", "cancel that", "cancel it", "cancel this", "no cancel", "never mind",
        "nevermind", "forget it", "forget that", "don't",
    )
    private val STOP = setOf("stop", "stop it", "stop that", "no stop")
    private val WRONG = setOf(
        "wrong", "that's wrong", "thats wrong", "that is wrong", "that's not right",
        "that is not right", "not right", "not that", "not that one", "that's not it",
        "that's not what i said", "not what i said", "that's not what i meant",
        "you misheard", "you misheard me", "wrong one",
    )

    /** Words that rephrases share and that say nothing about the request. */
    private val STOP_WORDS = setOf(
        "a", "an", "the", "to", "for", "of", "on", "in", "at", "my", "me", "i", "is", "it",
        "please", "can", "could", "would", "you", "what", "what's", "whats", "and",
    )

    /**
     * What the signals read of a turn, once it is answered: its [input], the [skill] that answered,
     * the [path] that won, GarMatchCheck's [check], the answer's [card] class, whether it showed
     * [trouble] (see [troubled]) and the names Whisper's prompt held ([promptNames]).
     */
    data class Turn(
        val input: String?,
        val skill: String? = null,
        val path: String? = null,
        val check: String? = null,
        val card: String? = null,
        val trouble: Boolean = false,
        val promptNames: List<String> = listOf(),
    )

    /**
     * The reasons [next] gives to mark [previous] suspect, empty when it gives none. Both are
     * answered: a "stop" that stopped a ringing timer or the music, or a "no" that answered GAR's
     * question, was a command, and saying the same command again is a retry only when the first
     * one went wrong ([retried]).
     *
     * @param gapMillis from [previous]'s reply to [next]'s start
     * @param editedFrom the text of the user bubble that [next] was typed from, when the user
     * tapped one to edit it
     */
    fun reasons(previous: Turn, next: Turn, gapMillis: Long, editedFrom: String?): List<String> {
        val reasons = ArrayList<String>()
        val nextWords = words(next.input ?: "")
        val previousInput = previous.input

        if (editedFrom != null && previousInput != null && sameText(editedFrom, previousInput)) {
            reasons.add(EDITED_TRANSCRIPT)
        }

        when (val negative = negative(nextWords)) {
            null -> {}
            SAID_NO, SAID_CANCEL, SAID_STOP -> when {
                negative != SAID_STOP && isConfirmation(previous.card) ->
                    reasons.add(CANCELLED_CONFIRMATION)
                tookNegative(next) -> {}
                else -> reasons.add(negative)
            }
            else -> reasons.add(negative)
        }

        if (previousInput != null && gapMillis in 0..REPEAT_WINDOW_MILLIS && reasons.isEmpty()) {
            val previousWords = words(previousInput)
            if (previousWords.isNotEmpty() && nextWords.isNotEmpty()) {
                if (nearRepeat(previousWords, nextWords)) {
                    if (retried(previous, next, previousWords, nextWords)) {
                        reasons.add(REPEAT)
                    }
                } else if (overlap(previousWords, nextWords) >= REPHRASE_OVERLAP) {
                    reasons.add(REPHRASE)
                }
            }
        }
        return reasons
    }

    /**
     * Whether a turn went wrong or was unsure, as far as its record shows: not answered (an
     * error, the permissions card), routed by the model or the fallback, its match rejected,
     * the model's call dropped or none, a question or a list to pick from (a contact not found,
     * several found), or GAR not understanding it.
     */
    fun troubled(
        outcome: String?,
        path: String?,
        check: String?,
        modelOutcome: String?,
        card: String?,
        suspect: Collection<String>,
    ): Boolean =
        outcome != OUTCOME_ANSWERED ||
            path == PATH_MODEL || path == PATH_FALLBACK || check == CHECK_REJECT ||
            modelOutcome in TROUBLED_MODEL_OUTCOMES ||
            isQuestion(card) ||
            suspect.any { it in TROUBLED_SUSPECT }

    /**
     * Whether [next] took its stop, cancel or no as a command: a ringing timer or alarm or the
     * music stopped (`timer_control`, `alarm`, `media_controls`), or GAR's pending question
     * answered (the `follow_up_answer` path).
     */
    private fun tookNegative(next: Turn): Boolean =
        !next.trouble && (next.path == PATH_FOLLOW_UP_ANSWER || next.skill in STOP_SKILLS)

    /**
     * Whether a near-repeat ([previousWords] then [nextWords]) retried [previous]: it showed
     * trouble or another skill answered the retry; the same words again only when the retry went
     * wrong too ("volume up", "next" twice are two commands); other words only when a name of the
     * prompt came in for one that isn't ("call erin" then "call aaron", not "call mom" then "call
     * dad").
     */
    private fun retried(
        previous: Turn,
        next: Turn,
        previousWords: List<String>,
        nextWords: List<String>,
    ): Boolean {
        if (previous.trouble || previous.skill != next.skill) {
            return true
        }
        if (previousWords == nextWords) {
            return next.trouble
        }
        val names = (previous.promptNames + next.promptNames).flatMap(::words).toSet()
        val added = nextWords.filter { it !in previousWords }
        val dropped = previousWords.filter { it !in nextWords }
        return added.any { it in names } && dropped.none { it in names }
    }

    /** A card that asked something or offered a list to pick from (`TelephoneAskOutput`, ...). */
    fun isQuestion(card: String?): Boolean =
        card != null && (card.contains("Ask") || card.contains("Pick"))

    /** A card that asked yes or no before acting (`GarConfirmOutput` and the like). */
    fun isConfirmation(card: String?): Boolean = card != null && card.contains("Confirm")

    /**
     * Which "no" [words] are, if they are only that: [SAID_NO], [SAID_CANCEL], [SAID_STOP],
     * [SAID_WRONG] or [I_SAID] ("I said call Aaron", "no I said Aaron").
     */
    fun negative(words: List<String>): String? {
        if (words.isEmpty()) {
            return null
        }
        val start = if (words[0] == "no" && words.size > 2) 1 else 0
        if (words.size - start >= 3 && words[start] == "i" && words[start + 1] == "said") {
            return I_SAID
        }
        val text = words.joinToString(" ")
        // "no that's wrong", "no cancel": the leading no only adds to it
        val withoutNo = if (words[0] == "no" && words.size > 1) {
            words.drop(1).joinToString(" ")
        } else {
            text
        }
        return when {
            text in NO -> SAID_NO
            text in WRONG || withoutNo in WRONG -> SAID_WRONG
            text in CANCEL || withoutNo in CANCEL -> SAID_CANCEL
            text in STOP || withoutNo in STOP -> SAID_STOP
            else -> null
        }
    }

    /**
     * Lowercase words without punctuation (apostrophes kept), without leading and trailing
     * fillers ("oh", "uh", "please") and with a word said twice in a row once.
     */
    fun words(text: String): List<String> {
        val raw = text.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^a-z0-9' ]"), " ")
            .split(' ')
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }
        val deduped = raw.filterIndexed { i, w -> i == 0 || raw[i - 1] != w || w == "no" }
        val first = deduped.indexOfFirst { it !in FILLERS }
        if (first < 0) {
            return emptyList()
        }
        val last = deduped.indexOfLast { it !in FILLERS }
        return deduped.subList(first, last + 1)
    }

    private fun sameText(a: String, b: String): Boolean = words(a) == words(b)

    /**
     * Whether [b] says [a] again, maybe misheard the other way: a [similarity] of at least
     * [REPEAT_SIMILARITY], or one word changed, added or dropped in a command of two words or
     * more ("call erin" and "call aaron", the retry of a misheard name, are only 0.7 similar).
     */
    fun nearRepeat(a: List<String>, b: List<String>): Boolean =
        similarity(a.joinToString(" "), b.joinToString(" ")) >= REPEAT_SIMILARITY ||
            (a.size >= 2 && b.size >= 2 && levenshtein(a, b) <= 1)

    /**
     * 1 minus the Levenshtein distance over the longer length, on characters: 1 for the same
     * text, 0.86 for "set a timer for five minutes" against "...ten minutes".
     */
    fun similarity(a: String, b: String): Double {
        if (a == b) {
            return 1.0
        }
        val longest = max(a.length, b.length)
        if (longest == 0) {
            return 1.0
        }
        return 1.0 - levenshtein(a.toList(), b.toList()).toDouble() / longest
    }

    /**
     * The share of the shorter input's content words (without [STOP_WORDS]) found in the other:
     * "call my mom" and "call mom please" share all of "call mom". 0 when either has fewer than
     * two content words, since one shared word ("timer") says little.
     */
    fun overlap(a: List<String>, b: List<String>): Double {
        val setA = a.filter { it !in STOP_WORDS }.toSet()
        val setB = b.filter { it !in STOP_WORDS }.toSet()
        val smaller = min(setA.size, setB.size)
        if (smaller < 2) {
            return 0.0
        }
        return setA.intersect(setB).size.toDouble() / smaller
    }

    private fun <T> levenshtein(a: List<T>, b: List<T>): Int {
        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)
        for (i in 1..a.size) {
            current[0] = i
            for (j in 1..b.size) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.size]
    }
}
