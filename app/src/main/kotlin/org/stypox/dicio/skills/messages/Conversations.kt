package org.stypox.dicio.skills.messages

/** One message of a conversation, as its notification shows it. */
data class ChatMessage(
    /** Who wrote it, or null when the notification doesn't say (then it is the conversation's) */
    val sender: String?,
    val text: String,
    val time: Long,
)

/**
 * A conversation as a messaging app's notification shows it (gar/PARITY.md, batch 4: "Read
 * messages by person or app").
 *
 * @param key the notification's key, to find it again for a reply
 * @param app the app's name ("Signal")
 * @param name the group's name, or the person's in a conversation with one person
 * @param messages the messages the notification shows, oldest first, never the user's own
 * @param time when the newest message came
 * @param canReply whether the notification has a reply button GAR can use ([MessageSource.reply])
 */
data class Conversation(
    val key: String,
    val packageName: String,
    val app: String,
    val name: String,
    val isGroup: Boolean,
    val messages: List<ChatMessage>,
    val time: Long,
    val canReply: Boolean,
) {
    /** Who wrote [message]: its sender, or the conversation's name. */
    fun senderOf(message: ChatMessage): String = message.sender ?: name
}

/**
 * What GAR reads off a notification, free of Android types so that [Conversations.of] can be unit
 * tested (`NotificationMessageSource` fills it in).
 *
 * @param group the app's group of notifications, see [isGroupSummary]
 * @param isMessage the app says it is a message ([android.app.Notification.CATEGORY_MESSAGE]) or
 * it has the messaging style
 * @param title the notification's title, often the sender
 * @param styleMessages the messaging style's messages, oldest first, or null without that style
 * @param text the notification's (big) text, for notifications without the messaging style
 */
data class MessageNotification(
    val key: String,
    val packageName: String,
    val group: String?,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean,
    val isMessage: Boolean,
    val title: String?,
    val conversationTitle: String?,
    val isGroupConversation: Boolean,
    val styleMessages: List<StyleMessage>?,
    val text: String?,
    val postTime: Long,
    val canReply: Boolean,
)

/** A message of the messaging style. [fromUser]: the user wrote it (the app's history). */
data class StyleMessage(
    val sender: String?,
    val fromUser: Boolean,
    val text: String?,
    val time: Long,
)

object Conversations {
    /**
     * The conversations among [notifications], newest first. Only messages count (see
     * [MessageNotification.isMessage]), not ongoing ones nor GAR's own. A group summary is left
     * out when its group has other notifications, which show the same messages one chat each.
     *
     * @param appName the app's name for a package name
     * @param ownPackage GAR's package
     */
    fun of(
        notifications: List<MessageNotification>,
        appName: (String) -> String,
        ownPackage: String,
    ): List<Conversation> {
        val messages = notifications.filter {
            it.isMessage && !it.isOngoing && it.packageName != ownPackage
        }
        val grouped = messages.filter { !it.isGroupSummary && it.group != null }
            .map { Pair(it.packageName, it.group) }
            .toSet()
        return messages
            .filter { !it.isGroupSummary || Pair(it.packageName, it.group) !in grouped }
            .mapNotNull { conversation(it, appName) }
            .sortedByDescending { it.time }
    }

    /**
     * The conversation among [current] to send a reply to [confirmed] through: the same chat
     * (app and name) under the same notification, else under another one (an app that posts a
     * chat again under another id), or null when the chat is gone. Never the chat that an app now
     * shows under [confirmed]'s notification instead (apps without the messaging style often
     * post every chat under one id): the user confirmed the reply for this chat.
     */
    fun replyTarget(current: List<Conversation>, confirmed: Conversation): Conversation? {
        val sameChat = { it: Conversation ->
            it.packageName == confirmed.packageName && it.name == confirmed.name
        }
        return current.firstOrNull { it.key == confirmed.key && sameChat(it) }
            ?: current.firstOrNull(sameChat)
    }

    private fun conversation(
        notification: MessageNotification,
        appName: (String) -> String,
    ): Conversation? {
        val app = appName(notification.packageName)
        val messages = notification.styleMessages
            ?.filter { !it.fromUser && !it.text.isNullOrBlank() }
            ?.map { ChatMessage(it.sender?.trim()?.takeIf(String::isNotEmpty), it.text!!.trim(), it.time) }
            ?: listOfNotNull(
                notification.text?.trim()?.takeIf(String::isNotEmpty)
                    ?.let { ChatMessage(null, it, notification.postTime) }
            )
        if (messages.isEmpty()) {
            return null
        }

        val senders = messages.mapNotNull { it.sender }.distinct()
        val isGroup = notification.isGroupConversation || senders.size > 1
        val name = listOfNotNull(
            notification.conversationTitle,
            // with one person, the messaging style's sender is that person
            senders.lastOrNull()?.takeIf { !isGroup },
            notification.title,
        ).map(String::trim).firstOrNull(String::isNotEmpty) ?: app
        return Conversation(
            key = notification.key,
            packageName = notification.packageName,
            app = app,
            name = name,
            isGroup = isGroup,
            // in a conversation with one person, every message is that person's
            messages = if (isGroup) messages else messages.map { it.copy(sender = null) },
            time = maxOf(messages.maxOf { it.time }, notification.postTime.takeIf { it > 0 } ?: 0),
            canReply = notification.canReply,
        )
    }
}
