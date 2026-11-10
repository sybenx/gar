package org.stypox.dicio.skills.messages

import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.Resolution

/**
 * Finds the conversations a request names: a person, a group or an app, matched loosely like
 * contacts and apps ([NameMatcher]: "jon" is John, "whats app" is WhatsApp). The names are the
 * ones the notifications show, which messaging apps take from the phone's contacts.
 */
object MessageFinder {
    /**
     * The conversations of [request] among [conversations] (newest first, as they stay). A person
     * in a group keeps only that person's messages of the group ("what did dad say" reads dad's
     * part of the family chat). "mom on signal" is mom's messages on Signal.
     */
    fun find(conversations: List<Conversation>, request: ReadRequest): List<Conversation> {
        val source = request.source ?: return latest(conversations, request)
        val (person, app) = splitApp(source, conversations)
        val found = when {
            app != null -> byPerson(byApp(conversations, app) ?: listOf(), person)
            request.via == Via.ON -> byApp(conversations, source) ?: byPerson(conversations, source)
            else -> byPerson(conversations, source).ifEmpty { byApp(conversations, source) ?: listOf() }
        }
        return latest(found, request)
    }

    /** The newest message alone for "read my last text", everything otherwise. */
    private fun latest(conversations: List<Conversation>, request: ReadRequest): List<Conversation> =
        if (request.latestOnly) {
            conversations.take(1).map { it.copy(messages = it.messages.takeLast(1)) }
        } else {
            conversations
        }

    /**
     * [source] split into a person and an app at its last "on" or "in" ("mom on signal"), when
     * the words after it name an app with a conversation; the whole of it as the person
     * otherwise, and no app.
     */
    private fun splitApp(source: String, conversations: List<Conversation>): Pair<String, String?> {
        val words = source.trim().split(WHITESPACE)
        val at = words.indexOfLast { it.lowercase() == "on" || it.lowercase() == "in" }
        if (at <= 0 || at == words.size - 1) {
            return Pair(source, null)
        }
        val app = words.subList(at + 1, words.size).joinToString(" ")
        return if (byApp(conversations, app) == null) {
            Pair(source, null)
        } else {
            Pair(words.subList(0, at).joinToString(" "), app)
        }
    }

    /** The conversations of the app [spoken] names, or null if it names none of their apps. */
    private fun byApp(conversations: List<Conversation>, spoken: String): List<Conversation>? {
        val apps = conversations.map { it.app }.distinct()
        val matched = when (val match = NameMatcher.match(spoken, apps, { it }, maxOptions(apps))) {
            is Resolution.Resolved -> listOf(match.value)
            is Resolution.Ambiguous -> match.options
            is Resolution.Unresolvable -> return null
        }
        return conversations.filter { it.app in matched }
    }

    /** A name to match: the conversation's own, or one of its senders'. */
    private data class Name(val conversation: Conversation, val name: String, val isSender: Boolean)

    /**
     * The conversations of the person or group [spoken] names: all of a conversation that its
     * name matches, and only the matching sender's messages of a group.
     */
    private fun byPerson(conversations: List<Conversation>, spoken: String): List<Conversation> {
        val names = conversations.flatMap { conversation ->
            listOf(Name(conversation, conversation.name, false)) +
                conversation.messages.mapNotNull { it.sender }.distinct()
                    .filter { it != conversation.name }
                    .map { Name(conversation, it, true) }
        }
        val matched = when (val match = NameMatcher.match(spoken, names, Name::name, maxOptions(names))) {
            is Resolution.Resolved -> listOf(match.value)
            is Resolution.Ambiguous -> match.options
            is Resolution.Unresolvable -> return listOf()
        }
        return conversations.mapNotNull { conversation ->
            val ofThis = matched.filter { it.conversation === conversation }
            when {
                ofThis.isEmpty() -> null
                ofThis.any { !it.isSender } -> conversation
                else -> {
                    val senders = ofThis.map { it.name }.toSet()
                    conversation.copy(messages = conversation.messages.filter { it.sender in senders })
                }
            }
        }
    }

    /** Every close match counts: two chats with "Mom" (on Signal and in Messages) are both read. */
    private fun maxOptions(candidates: List<*>): Int = maxOf(2, candidates.size)

    private val WHITESPACE = Regex("\\s+")
}
