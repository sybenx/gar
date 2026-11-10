package org.stypox.dicio.skills.messages

import cc.aaro.gar.router.Utterances
import org.stypox.dicio.gar.GarMatchCheck
import org.stypox.dicio.skills.sms.SmsMessage

/** How the user named whose messages to read. */
enum class Via {
    /** "from mom", "mom's messages", "what did dad say": a person first, then an app. */
    FROM,

    /** "on signal", "in the family chat": an app first, then a person or group. */
    ON,
}

/**
 * What to read.
 *
 * @param source the person, group or app as said, or null for every conversation
 * @param latestOnly "read my last text": only the newest message
 * @param namesOnly "who texted me": only who wrote, then GAR offers to read them
 * @param again "read the message again": the conversation GAR read last, if it still shows
 */
data class ReadRequest(
    val source: String?,
    val via: Via = Via.FROM,
    val latestOnly: Boolean = false,
    val namesOnly: Boolean = false,
    val again: Boolean = false,
)

/**
 * English words of the messages skill (like everything GAR adds, English only). Dicio's matcher
 * accepts a sentence with a word left out or swapped, and captures take any words, so the skill
 * keeps a match only when these checks say it is about messages ([readRequest], [isReply]); and
 * the model's calls of existing tools are read here too ([fromReadCall], [emailMessage],
 * [isReplyCommand]).
 */
object MessageWords {
    /** Words that make an input about messages, said outside the captures. */
    private val MESSAGE_WORDS = setOf(
        "message", "messages", "text", "texts", "texted", "texting", "messaged", "messaging",
        "chat", "chats", "say", "said", "send", "sent", "write", "wrote", "written", "reply",
        "replied", "respond", "responded", "back",
    )

    /** The nouns: an input with one of them asks about messages ("read my texts"). */
    private val MESSAGE_NOUNS = setOf("message", "messages", "text", "texts", "chat", "chats")
    private val SINGULAR_NOUNS = setOf("message", "text", "chat")
    private val LATEST_WORDS = setOf("last", "latest", "newest", "recent")

    /** No one whose messages GAR could look up: "what did you say", "did someone text me". */
    private val NOT_A_SOURCE = setOf(
        "i", "me", "my", "you", "your", "he", "she", "it", "they", "we", "him", "her", "them", "us",
        "that", "this", "these", "those", "someone", "somebody", "anyone", "anybody", "everyone",
        "everybody", "there", "who", "what", "one",
    )

    /** Other things than chat messages: emails, notifications, calls and voicemail. */
    private val OTHER_THINGS = setOf(
        "email", "emails", "mail", "gmail", "notification", "notifications", "call", "calls",
        "voicemail", "voicemails", "inbox",
    )

    // "again" isn't a name: "read the message again", "read messages from mom again"
    private val FILLERS = setOf(
        "the", "my", "a", "an", "all", "of", "please", "app", "now", "today", "yet", "again",
    )
    private val PREPOSITIONS_ON = setOf("on", "in")

    /** A source of more words is not a name or an app. */
    private const val MAX_SOURCE_WORDS = 5

    /**
     * What a match of the read or who sentences asks to read, or null if [input] isn't about
     * messages (see [MessageWords]).
     *
     * @param source the sentence's capture, as said
     * @param namesOnly a match of the who sentence
     */
    fun readRequest(input: String, source: String?, namesOnly: Boolean = false): ReadRequest? {
        val words = words(input)
        if (words.any { it in OTHER_THINGS }) {
            return null
        }
        val frame = GarMatchCheck.withoutCaptures(words, listOf(source))
        if (frame.none { it in MESSAGE_WORDS }) {
            return null
        }
        // "what does the clock say" asks the clock: in the present tense only "what does mom's
        // message say" is about a message
        if ("does" in frame && frame.none { it in MESSAGE_NOUNS }) {
            return null
        }
        val name = source?.let { if (isNoSource(it)) null else (cleanSource(it) ?: return null) }
        val singular = frame.any { it in SINGULAR_NOUNS }
        return ReadRequest(
            source = name,
            via = if (name != null && wordBefore(words, source) in PREPOSITIONS_ON) Via.ON else Via.FROM,
            latestOnly = frame.any { it in LATEST_WORDS } && singular,
            namesOnly = namesOnly,
            again = name == null && singular && "again" in words,
        )
    }

    /**
     * [source] without fillers ("the family chat" is "family") and a trailing "'s", or null when
     * it names no one: a pronoun ("what did you say"), a time ("in the last hour") or too many
     * words. A source of fillers only ("read me the messages") is no source at all, see
     * [isNoSource].
     */
    private fun cleanSource(source: String): String? {
        val words = words(source).map { it.removeSuffix("'s") }
        val kept = words.dropWhile { it in FILLERS }.dropLastWhile { it in FILLERS || it in GROUP_WORDS }
        if (kept.isEmpty() || kept.size > MAX_SOURCE_WORDS ||
            kept.any { it in NOT_A_SOURCE || it in TIME_WORDS }
        ) {
            return null
        }
        return kept.joinToString(" ")
    }

    private val GROUP_WORDS = setOf("group", "chat", "conversation", "thread")
    private val TIME_WORDS = setOf(
        "hour", "hours", "minute", "minutes", "day", "days", "today", "yesterday", "week", "morning",
        "afternoon", "evening", "night", "tonight", "while", "last", "past",
    )

    /** Whether [source] holds only fillers ("the"), so that the request reads everything. */
    private fun isNoSource(source: String): Boolean =
        words(source).all { it in FILLERS }

    /** The normalized word right before [capture] in [words], or null. */
    private fun wordBefore(words: List<String>, capture: String?): String? {
        val captureWords = words(capture ?: return null)
        if (captureWords.isEmpty()) {
            return null
        }
        val start = (0..words.size - captureWords.size).firstOrNull { start ->
            captureWords.indices.all { words[start + it] == captureWords[it] }
        } ?: return null
        return words.getOrNull(start - 1)
    }

    /**
     * Whether a match of the reply sentences is a reply: it says reply, respond or back outside
     * the captures ("text mom" matched "text .who. back" with a word left out), and [who] names
     * no email or notification.
     */
    fun isReply(input: String, who: String?, what: String?): Boolean {
        val frame = GarMatchCheck.withoutCaptures(words(input), listOf(who, what))
        return frame.any { it in REPLY_WORDS } && who?.let(::words)?.let { whoWords ->
            whoWords.none { it in OTHER_THINGS } && whoWords.firstOrNull() !in NOT_A_TARGET
        } != false
    }

    private val REPLY_WORDS = setOf("reply", "respond", "back")

    /** "text me back later" is no reply to anyone ("reply to mom i will" still is). */
    private val NOT_A_TARGET = setOf("me", "i", "us", "myself", "you")

    /**
     * Whether a reply's [who] means the message GAR read last: no name, or "her", "him", "them",
     * "it", "that", "this message", "the last one".
     */
    fun meansLastRead(who: String?): Boolean {
        val words = words(who ?: return true).filter { it !in TARGET_FILLERS }
        return words.isEmpty() || words.all { it in LAST_READ_WORDS }
    }

    /**
     * The message in a reply's [who] that starts with the message GAR read last, as captured from
     * "reply to her i'm on my way" (no word between the two), or null if [who] doesn't start so.
     */
    fun afterLastRead(who: String): String? {
        val words = who.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val first = words.firstOrNull()?.let(::words)?.singleOrNull() ?: return null
        if (words.size < 2 || first !in setOf("her", "him", "them", "it", "that", "this")) {
            return null
        }
        return words.drop(1).joinToString(" ")
    }

    private val WHITESPACE = Regex("\\s+")
    private val TARGET_FILLERS = setOf("the", "a", "my", "last", "latest", "new", "newest")
    private val LAST_READ_WORDS = setOf(
        "her", "him", "them", "it", "that", "this", "one", "message", "text", "chat",
    )

    // a separator between words: anything but letters, digits and apostrophes
    private const val SEP = "(?:[^\\p{L}\\p{N}']+)"

    // words said before the reply itself: "yes, tell her ...", "ok reply ..."
    private const val LEAD = "(?:(?:yes|yeah|yep|ok|okay|sure|please|um|uh|so|and|then|just|oh|" +
            "well|alright|can|could|you|i|want|to|would|like|i'd)$SEP)*"
    private const val PRONOUN = "(?:him|her|them)"
    // the longer ones first: "that message" before "that"
    private const val THAT_MESSAGE = "(?:(?:that|this|the)$SEP(?:message|text|one)|$PRONOUN|it|that|this)"
    private const val CONNECTOR = "(?:saying|with|that|and${SEP}say|and${SEP}tell$SEP$PRONOUN|by${SEP}saying)"

    private val REPLY_PATTERNS = listOf(
        // "reply ok", "reply to her saying i'm on my way", "write back sounds good"; never "reply to
        // mom", which names someone else than the message GAR read (the reply sentences take it)
        "(?:reply|respond|(?:write|text|message)${SEP}back)(?:${SEP}back)?" +
            "(?:${SEP}to$SEP$THAT_MESSAGE)?(?:$SEP$CONNECTOR)?$SEP(?!to$SEP)(.+)",
        // "tell her i'm on my way", "text him back saying ok"
        "(?:tell|text|message)$SEP$PRONOUN(?:${SEP}back)?(?:$SEP(?:that|saying))?$SEP(.+)",
        // "say i'm on my way", but not "say that again" (the conversation's repeat)
        "(?:say|write)(?:${SEP}back)?$SEP" +
            "(?!(?:(?:it|that)$SEP)?(?:again|one${SEP}more${SEP}time)(?:${SEP}please)?$SEP?$)" +
            "(?!(?:it|that)(?:${SEP}please)?$SEP?$)(.+)",
        // "let her know i'm running late"
        "let$SEP$PRONOUN${SEP}know(?:${SEP}that)?$SEP(.+)",
    ).map { Regex("^$SEP?$LEAD$it$SEP?$", RegexOption.IGNORE_CASE) }

    /**
     * The reply in an answer to a message GAR read ("reply ok", "tell her i'm on my way", "say
     * sounds good"), as it will be sent ([SmsMessage.assemble]), or null if [input] gives none.
     */
    fun replyText(input: String): String? {
        val text = input.replace('’', '\'')
        val said = REPLY_PATTERNS.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
            ?: return null
        return replyMessage(said)
    }

    /**
     * A reply as it will be sent: [SmsMessage.assemble] (connectors dropped, capitalized), and
     * "ok" as "OK". Null without words.
     */
    fun replyMessage(said: String?): String? {
        val message = SmsMessage.assemble(said) ?: return null
        return if (message.equals("ok", ignoreCase = true)) "OK" else message
    }

    /**
     * What a read_notifications call of the model reads, from its [utterance]: a request about
     * messages ("did mom text me", "read my messages from signal"), or null for one about the
     * notifications ("read my notifications"), which Dicio's notify skill reads as before.
     */
    fun fromReadCall(utterance: String): ReadRequest? {
        val words = words(utterance)
        if (words.none { it in MESSAGE_WORDS } || words.any { it in OTHER_THINGS - NOTIFICATIONS }) {
            return null
        }
        if (words.any { it in NOTIFICATIONS } && words.none { it in MESSAGE_NOUNS }) {
            return null
        }
        val source = sourceAfterPreposition(words) ?: sourceOfQuestion(words)
        val name = source?.second?.let { if (isNoSource(it)) null else cleanSource(it) }
        return ReadRequest(
            source = name,
            via = if (name != null && source.first in PREPOSITIONS_ON) Via.ON else Via.FROM,
            latestOnly = words.any { it in LATEST_WORDS } && words.any { it in SINGULAR_NOUNS },
        )
    }

    private val NOTIFICATIONS = setOf("notification", "notifications")

    /** "messages from mom", "any texts on signal": the preposition and the words after it. */
    private fun sourceAfterPreposition(words: List<String>): Pair<String, String>? {
        val at = words.indexOfLast { it in SOURCE_PREPOSITIONS }
        if (at < 0 || at == words.size - 1) {
            return null
        }
        return Pair(words[at], words.subList(at + 1, words.size).joinToString(" "))
    }

    private val SOURCE_PREPOSITIONS = setOf("from", "on", "in", "by")

    /** "what did dad say", "did mom text me": the words between the question's verbs. */
    private fun sourceOfQuestion(words: List<String>): Pair<String, String>? {
        val start = words.indexOfFirst { it == "did" || it == "does" || it == "has" }
        if (start < 0) {
            return null
        }
        val end = words.withIndex().indexOfFirst { (i, word) -> i > start && word in MESSAGE_WORDS }
        if (end <= start + 1) {
            return null
        }
        return Pair(words[start], words.subList(start + 1, end).joinToString(" "))
    }

    /**
     * The text of an email from the model's sms_send [message] slot, or null when it holds no
     * text: words of the command ([SmsMessage.fromModel]), or only "email" and command words
     * ("send an email to my boss" -> "an email").
     */
    fun emailMessage(to: String?, message: String?): String? =
        SmsMessage.fromModel(to, message)?.takeUnless { text ->
            words(text).all { it in EMAIL_WORDS || it in EMAIL_COMMAND_WORDS }
        }

    private val EMAIL_COMMAND_WORDS = setOf(
        "send", "write", "compose", "draft", "start", "new", "a", "an", "the", "to", "e", "my",
    )

    /** Whether [input] says email outside [captures], as the email sentences do. */
    fun saysEmail(input: String, captures: List<String?>): Boolean {
        val frame = GarMatchCheck.withoutCaptures(words(input), captures)
        return frame.any { it in EMAIL_WORDS } ||
            frame.windowed(2).any { (first, second) -> first == "e" && second == "mail" }
    }

    private val EMAIL_WORDS = setOf("email", "emails", "mail", "gmail")

    /**
     * Whether an sms_send call's [utterance] replies ("respond to mom with on my way", "write back
     * to dad"): it starts with reply or respond, or says back after write, text or message.
     */
    fun isReplyCommand(utterance: String): Boolean {
        val words = words(utterance).dropWhile { it in COMMAND_FILLERS }
        return words.firstOrNull() in setOf("reply", "respond") ||
            words.windowed(2).any { (first, second) -> first in BACK_VERBS && second == "back" } ||
            words.windowed(3).any { (first, _, third) -> first in BACK_VERBS && third == "back" }
    }

    private val COMMAND_FILLERS = setOf("ok", "okay", "please", "can", "could", "you", "hey", "and", "just")
    private val BACK_VERBS = setOf("write", "text", "message")

    private fun words(text: String): List<String> = Utterances.words(Utterances.normalize(text))
}
