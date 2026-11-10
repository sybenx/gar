package org.stypox.dicio.skills.knowledge

import cc.aaro.gar.router.Utterances
import org.stypox.dicio.cldr.CldrLanguages

/** What a general knowledge question asks about its subject. */
enum class KnowledgeKind {
    /** "who is ...", "what is ...", "tell me about ...": the summary's first sentences. */
    SUMMARY,

    /** "how tall is ...": a sentence of the summary with a measurement ([attribute]). */
    MEASURE,

    /** "when was ... born". */
    BORN,

    /** "when did ... die". */
    DIED,

    /** "how old is ...". */
    AGE,

    /** "when was ... built": a sentence of the summary with a year ([attribute]). */
    WHEN,
}

/**
 * A question for Wikipedia: [subject] as said ("mount everest", without a leading article),
 * [kind] and, for [KnowledgeKind.MEASURE] and [KnowledgeKind.WHEN], the word asked about ("tall",
 * "built").
 */
data class KnowledgeQuestion(
    val subject: String,
    val kind: KnowledgeKind,
    val attribute: String? = null,
)

/**
 * English general knowledge questions, read in Kotlin (no Dicio sentences, like the math skill):
 * the whole input must be one of the forms below, with a subject that is no phone command or
 * another skill's question. Dicio's matcher would accept "what is .x." for "what's the weather",
 * "what is the time" or "what's my battery", and a gate that took them would ask about Wikipedia
 * for a question the phone answers itself (gar/DESIGN.md, "Online skills").
 *
 * Forms, after polite leads ("hey", "can you tell me", "do you know"):
 * - "who is|was|are|were|'s X", "what is|was|are|were|'s X", "tell me (more|something)? about
 *   X", "what do you know about X";
 * - "how tall|high|big|... is|was|are X" (how old: [KnowledgeKind.AGE]);
 * - "when was|were X born", "when did X die", "when was|were X built|founded|invented|...",
 *   "when did X start|end|begin|happen".
 */
object KnowledgeQuestions {
    fun parse(input: String): KnowledgeQuestion? {
        val words = stripLeads(Utterances.words(Utterances.normalize(input)))
            .let { dropTrailing(it) }
        if (words.isEmpty()) {
            return null
        }
        val question = when (words[0]) {
            "who", "what" -> whoOrWhat(words)
            "who's", "whos", "what's", "whats" -> summary(words.drop(1))
            "tell" -> tellMeAbout(words)
            "how" -> howAdjective(words)
            "when" -> whenWas(words)
            else -> null
        } ?: return null
        return question.takeIf { acceptsSubject(it.subject) }
    }

    private fun whoOrWhat(words: List<String>): KnowledgeQuestion? {
        val second = words.getOrNull(1) ?: return null
        return when {
            second in BE -> summary(words.drop(2))
            // "what do you know about X"
            words[0] == "what" && words.take(5) == listOf("what", "do", "you", "know", "about") ->
                summary(words.drop(5))
            else -> null
        }
    }

    /** "tell me about X", "tell me more about X", "tell me something about X". */
    private fun tellMeAbout(words: List<String>): KnowledgeQuestion? {
        if (words.getOrNull(1) != "me") {
            return null
        }
        val rest = words.drop(2).dropWhile { it in TELL_FILLERS }
        return if (rest.firstOrNull() == "about") summary(rest.drop(1)) else null
    }

    /** "how tall is mount everest", "how old is taylor swift". */
    private fun howAdjective(words: List<String>): KnowledgeQuestion? {
        val adjective = words.getOrNull(1) ?: return null
        if (adjective !in MEASURES || words.getOrNull(2) !in BE) {
            return null
        }
        val subject = subjectOf(words.drop(3)) ?: return null
        return if (adjective == "old") {
            KnowledgeQuestion(subject, KnowledgeKind.AGE)
        } else {
            KnowledgeQuestion(subject, KnowledgeKind.MEASURE, adjective)
        }
    }

    /** "when was X born", "when did X die", "when was X built", "when did X end". */
    private fun whenWas(said: List<String>): KnowledgeQuestion? {
        // "when did X pass away"
        val words = if (said.takeLast(2) == listOf("pass", "away")) said.dropLast(1) else said
        val verb = words.getOrNull(1) ?: return null
        val last = words.last()
        val middle = words.subList(minOf(2, words.size), words.size - 1)
        val subject = subjectOf(middle) ?: return null
        return when {
            verb in setOf("was", "were") && last == "born" ->
                KnowledgeQuestion(subject, KnowledgeKind.BORN)
            verb == "did" && last in setOf("die", "pass") ->
                KnowledgeQuestion(subject, KnowledgeKind.DIED)
            verb in setOf("was", "were", "is") && last in WHEN_PARTICIPLES ->
                KnowledgeQuestion(subject, KnowledgeKind.WHEN, last)
            verb == "did" && last in WHEN_VERBS ->
                KnowledgeQuestion(subject, KnowledgeKind.WHEN, last)
            else -> null
        }
    }

    private fun summary(rest: List<String>): KnowledgeQuestion? =
        subjectOf(rest)?.let { KnowledgeQuestion(it, KnowledgeKind.SUMMARY) }

    /** The subject words without a leading article, or null if none are left. */
    private fun subjectOf(words: List<String>): String? {
        val subject = words.dropWhile { it in ARTICLES }
        return if (subject.isEmpty()) null else subject.joinToString(" ")
    }

    private fun stripLeads(words: List<String>): List<String> {
        var rest = words
        while (true) {
            // "tell me who is X" keeps its question word; "tell me about X" is a form of its own
            if (rest.size > 3 && rest[0] == "tell" && rest[1] == "me" && rest[2] in QUESTION_WORDS) {
                rest = rest.drop(2)
                continue
            }
            val lead = LEADS.firstOrNull { lead ->
                rest.size > lead.size && rest.subList(0, lead.size) == lead
            } ?: return rest
            rest = rest.drop(lead.size)
        }
    }

    private fun dropTrailing(words: List<String>): List<String> {
        var rest = words
        while (true) {
            val tail = TRAILING.firstOrNull { tail ->
                rest.size > tail.size && rest.subList(rest.size - tail.size, rest.size) == tail
            } ?: return rest
            rest = rest.dropLast(tail.size)
        }
    }

    /**
     * Whether [subject] is something to look up: at most [MAX_SUBJECT_WORDS] words, none of them
     * about the user or the phone (pronouns, the time and date, the weather, alarms, messages, the
     * battery, ...), no arithmetic and not only numbers ("what is 5 plus 5", "what is five") and no
     * word that another skill answers (meaning, definition and synonym are the dictionary's,
     * exchange rates the currency converter's).
     */
    fun acceptsSubject(subject: String): Boolean {
        val words = Utterances.words(subject)
        return words.isNotEmpty() && words.size <= MAX_SUBJECT_WORDS &&
            subject !in SMALL_TALK_SUBJECTS && words[0] !in PREPOSITIONS &&
            !endsInLanguage(words) &&
            words.none { word ->
                word in NOT_SUBJECT_WORDS || word.removeSuffix("'s") in NOT_SUBJECT_WORDS ||
                    word.endsWith("s") && word.dropLast(1) in NOT_SUBJECT_WORDS
            } &&
            // "world war two" and "the 16th president" are things; "five", "5 5" and "5 miles in
            // km" (the unit converter's) start with a number
            !isNumber(words[0])
    }

    private fun isNumber(word: String): Boolean =
        word in NUMBER_WORDS || word.all { it.isDigit() || it in ".," }

    /** "hello in spanish": "what is hello in spanish" asks for a translation. */
    private fun endsInLanguage(words: List<String>): Boolean {
        val index = words.lastIndexOf("in")
        if (index < 0 || index == words.lastIndex) {
            return false
        }
        val name = words.subList(index + 1, words.size).joinToString(" ")
        return CldrLanguages["en"].orEmpty().any { it.translationNormalized.equals(name, ignoreCase = true) }
    }

    private const val MAX_SUBJECT_WORDS = 8

    private val BE = setOf("is", "was", "are", "were", "s")
    private val ARTICLES = setOf("a", "an", "the")
    private val QUESTION_WORDS = setOf("who", "what", "when", "how")
    private val TELL_FILLERS = setOf("more", "something", "a", "bit", "little", "all")

    private val MEASURES = setOf(
        // not "far": "how far is tokyo" asks for the way from here, which no summary has
        "tall", "high", "big", "large", "long", "deep", "wide", "heavy", "old", "fast",
    )
    private val WHEN_PARTICIPLES = setOf(
        "built", "founded", "invented", "made", "created", "discovered", "written", "released",
        "established", "formed", "signed", "published", "completed", "opened",
    )
    private val WHEN_VERBS = setOf("start", "end", "begin", "happen", "open", "close")

    private val LEADS = listOf(
        listOf("hey"), listOf("ok"), listOf("okay"), listOf("so"), listOf("um"), listOf("uh"),
        listOf("well"), listOf("please"), listOf("quick", "question"),
        listOf("can", "you", "tell", "me"), listOf("could", "you", "tell", "me"),
        listOf("would", "you", "tell", "me"), listOf("do", "you", "know"),
        listOf("i", "want", "to", "know"), listOf("i", "wanna", "know"), listOf("i", "wonder"),
    )

    private val TRAILING = listOf(
        listOf("please"), listOf("for", "me"), listOf("right", "now"), listOf("again"),
        listOf("anyway"), listOf("exactly"),
    )

    /**
     * Words of a subject that make the question one for the phone or another skill. Plural forms
     * with an "s" are found by the check too ("alarms", "messages").
     */
    private val NOT_SUBJECT_WORDS = setOf(
        // the user, the assistant and what is around them
        "i", "me", "my", "mine", "myself", "you", "your", "yours", "yourself", "we", "us", "our",
        "it", "its", "this", "that", "these", "those", "there", "here", "he", "she", "him", "her",
        "his", "they", "them", "their", "someone", "somebody", "something", "everyone",
        "everything", "anything", "nothing",
        // time, date, weather
        "time", "date", "day", "today", "tonight", "tomorrow", "yesterday", "now", "weekend",
        "week", "month", "clock", "hour", "minute", "weather", "forecast", "temperature", "rain",
        "snow", "wind", "humidity", "sunrise", "sunset", "outside",
        // the phone and GAR's commands
        "battery", "charge", "alarm", "timer", "stopwatch", "reminder", "calendar", "schedule",
        "event", "appointment", "meeting", "agenda", "notification", "message", "text", "email",
        "mail", "call", "caller", "calling", "called", "missed", "voicemail", "volume",
        "brightness", "wifi", "bluetooth", "hotspot", "flashlight", "torch", "ringer", "song",
        "music", "playing", "podcast", "playlist", "album", "track", "phone", "screen", "app",
        "setting", "settings", "password", "code", "pin", "location", "traffic", "number",
        "address", "birthday", "anniversary", "nearby", "nearest", "closest",
        "score", "news", "headline", "headlines", "left", "remaining", "wake", "singing", "sings",
        "sang", "singer", "holiday", "next",
        // other skills' questions
        "meaning", "definition", "synonym", "word", "spelling", "spelled", "exchange", "rate", "price",
        "cost", "costs", "worth", "translation",
        // arithmetic
        "plus", "minus", "times", "divided", "multiplied", "percent", "percentage", "squared",
        "cubed", "root", "sum", "equals", "tip",
    )

    /** Small talk, whole (no leading article): "what's up", "what's new", "what's the plan". */
    private val SMALL_TALK_SUBJECTS = setOf(
        "up", "new", "going on", "happening", "wrong", "next", "plan", "good", "cooking",
        "matter", "deal", "story", "point", "open", "open now",
    )

    /**
     * A subject that starts with one of these is no thing: "what's on tv", "what's in the family
     * chat" (the messages), "what's for dinner".
     */
    private val PREPOSITIONS = setOf(
        "in", "on", "at", "for", "with", "from", "to", "about", "into", "near", "around", "after",
        "before", "behind", "inside", "outside", "up", "down", "like",
    )

    /** "what is five": a number alone is the calculator's. */
    private val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        "hundred", "thousand", "million", "billion", "half", "quarter",
    )
}
