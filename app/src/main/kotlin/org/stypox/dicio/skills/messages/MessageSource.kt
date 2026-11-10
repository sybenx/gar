package org.stypox.dicio.skills.messages

/** What happened to a reply handed to [MessageSource.reply]. */
enum class ReplyResult {
    /** Handed to the app through its notification's reply button; the app sends it. */
    SENT,

    /** The conversation's notification is gone (read or dismissed), so there's no button. */
    GONE,

    /** The notification has no reply button GAR can use. */
    NO_REPLY,

    /** The app refused the reply (its button was cancelled). */
    FAILED,

    /** GAR can't see the notifications right now (no access, or the listener isn't connected). */
    NO_ACCESS,
}

/**
 * The messaging apps' notifications, seen through Dicio's notification listener
 * (`NotificationMessageSource`), or a fake in tests.
 */
interface MessageSource {
    /** The conversations newest first, or null when GAR can't see the notifications. */
    fun conversations(): List<Conversation>?

    /**
     * Replies [text] to [conversation] through its notification's reply button, looked up again
     * now: the notification may be gone or changed since it was read.
     */
    fun reply(conversation: Conversation, text: String): ReplyResult

    /** Opens the app of [packageName]; false if it can't be opened. */
    fun openApp(packageName: String): Boolean
}
