package org.stypox.dicio.skills.sms

/**
 * Turns the message part of a command, as transcribed, into the text to send. English only, like
 * the sms sentences. Speech recognition writes everything lowercase and the model or the sentence
 * capture may keep the words that introduce the message, so:
 * - leading connectors are dropped ("saying i'm late", "that i'm late" -> "I'm late");
 * - the first letter and the pronoun "I" are capitalized;
 * - everything else is kept as said or typed.
 */
object SmsMessage {

    /** @return the text to send, or null if [raw] has no words besides the connectors */
    fun assemble(raw: String?): String? {
        var words = raw?.trim()?.split(WHITESPACE)?.filter { it.isNotEmpty() } ?: return null
        val lowercase = words.map { it.lowercase() }
        LEADING_CONNECTORS.firstOrNull { connector ->
            lowercase.size >= connector.size && lowercase.subList(0, connector.size) == connector
        }?.let { words = words.drop(it.size) }

        if (words.isEmpty()) {
            return null
        }
        return words.joinToString(" ") { word ->
            if (word == "i" || word.startsWith("i'")) word.replaceFirstChar { 'I' } else word
        }.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * The message slot of GAR's `sms_send` tool, or null if it is not a message. The model often
     * fills the optional slot with words of the command ("send a text to my wife" -> "send a text
     * to my wife", "write a message to alex" -> "message"). Both slots are word spans of the same
     * utterance, so a message that contains the recipient's words, or only command words, is
     * part of the command: the skill then asks for the message instead.
     */
    fun fromModel(to: String?, message: String?): String? {
        val words = words(message)
        if (words.isEmpty() || words.all { it in COMMAND_WORDS }) {
            return null
        }
        val toWords = words(to)
        val repeatsRecipient = toWords.isNotEmpty() &&
                (0..words.size - toWords.size).any { words.subList(it, it + toWords.size) == toWords }
        return if (repeatsRecipient) null else message
    }

    private fun words(text: String?): List<String> =
        text?.lowercase()?.split(WHITESPACE)?.filter { it.isNotEmpty() } ?: listOf()

    private val COMMAND_WORDS = setOf(
        "send", "write", "text", "texts", "message", "messages", "sms", "a", "an", "the", "to",
    )

    private val WHITESPACE = Regex("\\s+")

    // longest first, so that "that says" wins over "that"
    private val LEADING_CONNECTORS = listOf(
        "and tell him", "and tell her", "and tell them",
        "that says", "which says", "that reads", "to say", "and say", "telling him",
        "telling her", "telling them", "saying", "that",
    ).map { it.split(" ") }.sortedByDescending { it.size }
}
