package org.stypox.dicio.skills.messages

import android.app.KeyguardManager
import cc.aaro.gar.router.resolve.NameMatcher
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.skills.sms.SmsFlow
import org.stypox.dicio.skills.sms.SmsRecipients

/**
 * The conversation GAR read last, so that "reply ok" or "tell her i'm on my way" a little later
 * replies to it. Shared by the skill and the model's calls (`MessagesCalls`).
 */
class ReadMemory(private val now: () -> Long = System::currentTimeMillis) {
    private var last: Conversation? = null
    private var at = 0L

    @Synchronized
    fun remember(conversation: Conversation) {
        last = conversation
        at = now()
    }

    /** The conversation read last, if GAR read it within [FRESH_MS]. */
    @Synchronized
    fun lastRead(): Conversation? = last?.takeIf { now() - at <= FRESH_MS }

    companion object {
        /** After this long, "reply ok" is no longer about the message GAR read. */
        const val FRESH_MS = 10 * 60_000L

        val shared = ReadMemory()
    }
}

/**
 * The way from a command to messages read aloud or a reply sent, shared by the skill's sentences,
 * its follow-up answers and the model's calls (`MessagesCalls`):
 * - reading: one conversation at a time, newest first, asking "Want to reply?" after each one
 *   whose notification has a reply button, like Google Assistant ([MessagesReadOutput]);
 * - replying: the reply is read back on a card and sent through the notification's reply button
 *   on a spoken yes or a tap on Send ([ReplyCardOutput]), like GAR's texts.
 *
 * @param sourceFor the notifications, see [MessageSource]
 * @param isDeviceLocked whether the phone is locked: messages are private, so the skill doesn't
 * run then (Dicio's sentences also match on the lock screen)
 * @param textFlow the sms skill's flow, for "Want to send a text instead?"
 */
class MessagesFlow(
    val sourceFor: (SkillContext) -> MessageSource = { NotificationMessageSource(it.android) },
    private val isDeviceLocked: (SkillContext) -> Boolean = { ctx ->
        ctx.android.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    },
    val memory: ReadMemory = ReadMemory.shared,
    val textFlow: () -> SmsFlow = { SmsFlow() },
) {
    /** Reads what [request] asks for, see [MessagesReadOutput]. */
    fun read(ctx: SkillContext, request: ReadRequest): SkillOutput {
        if (isDeviceLocked(ctx)) {
            return GarLockedOutput() // "Unlock your phone first."
        }
        val all = sourceFor(ctx).conversations() ?: return MessagesNoAccessOutput()
        // "read the message again": the one GAR read last, while its notification shows
        if (request.again) {
            memory.lastRead()?.let { Conversations.replyTarget(all, it) }
                ?.let { return readStep(listOf(it), 0, intro = false, nested = false) }
        }
        val found = MessageFinder.find(all, request)
        return when {
            found.isEmpty() -> MessagesNoneOutput(request)
            request.namesOnly -> MessagesWhoOutput(this, found, nested = false)
            else -> readStep(found, 0, intro = found.size > 1, nested = false)
        }
    }

    /**
     * Reads [conversations] from [start]: up to [MAX_PER_STEP] of them, ending with the first
     * that can be replied to, so that "Want to reply?" is about one conversation.
     */
    fun readStep(
        conversations: List<Conversation>,
        start: Int,
        intro: Boolean,
        nested: Boolean,
    ): MessagesReadOutput {
        val end = stepEnd(conversations, start)
        memory.remember(conversations[end])
        return MessagesReadOutput(this, conversations, start, end, intro, nested)
    }

    /**
     * A reply to the conversation [who] names, or to the one GAR read last when [who] is null or
     * "her", "it" and the like ([MessageWords.meansLastRead]).
     *
     * @param said the reply as said, or null to ask for it
     */
    fun reply(ctx: SkillContext, who: String?, said: String?, nested: Boolean): SkillOutput {
        if (isDeviceLocked(ctx)) {
            return GarLockedOutput()
        }
        val all = sourceFor(ctx).conversations() ?: return MessagesNoAccessOutput()
        val afterLastRead = who?.takeIf { said == null }?.let(MessageWords::afterLastRead)
        if (MessageWords.meansLastRead(who) || afterLastRead != null) {
            val target = lastRead(all) ?: return MessagesNothingToReplyOutput()
            return replyTo(target, said ?: afterLastRead, nested)
        }

        // "reply to mom i'm on my way": the name and the message in one capture
        val split = if (said == null) SmsRecipients.splitMessage(who!!, all, Conversation::name) else null
        val name = split?.first ?: who!!
        val target = named(all, name)
            ?: return MessagesTextInsteadOutput(this, name, said ?: split?.second, nested)
        return replyTo(target, said ?: split?.second, nested)
    }

    /** The reply card for [conversation], or "What's your reply?" when [said] has no words. */
    fun replyTo(conversation: Conversation, said: String?, nested: Boolean): SkillOutput {
        if (!conversation.canReply) {
            return MessagesNoReplyOutput(conversation)
        }
        memory.remember(conversation)
        val message = MessageWords.replyMessage(said)
            ?: return ReplyAskOutput(this, conversation, nested)
        return ReplyCardOutput(this, ReplyDraft(conversation, message), nested)
    }

    /** "change it to ...": [draft] is replaced by one with the new message, or GAR asks for it. */
    fun changeMessage(draft: ReplyDraft, said: String?): SkillOutput {
        draft.replace()
        return replyTo(draft.conversation, said, nested = true)
    }

    /**
     * "send it to dad instead": the reply goes to [spoken]'s conversation, read back again. The
     * same conversation ("yes, send it to mom") is a yes.
     */
    fun changeRecipient(ctx: SkillContext, draft: ReplyDraft, spoken: String): SkillOutput {
        if (normalizeAnswer(spoken) == normalizeAnswer(draft.conversation.name)) {
            draft.send(sourceFor(ctx))
            return ReplySentOutput(draft)
        }
        draft.replace()
        return reply(ctx, spoken, draft.message, nested = true)
    }

    /**
     * The conversation GAR read last as the notifications show it now (it may have new messages,
     * or be gone, which the reply then says), or the newest one with a reply button.
     */
    private fun lastRead(all: List<Conversation>): Conversation? {
        val last = memory.lastRead()
        if (last != null) {
            // never another chat that the app now shows under the same notification
            return Conversations.replyTarget(all, last) ?: last
        }
        return all.firstOrNull { it.canReply } ?: all.firstOrNull()
    }

    /**
     * The conversation [name] names: its own chat before a group where that person wrote, one
     * with a reply button first, the newest first.
     */
    private fun named(all: List<Conversation>, name: String): Conversation? {
        val found = MessageFinder.find(all, ReadRequest(name))
        return found.sortedWith(
            compareByDescending<Conversation> { NameMatcher.similarity(name, it.name) >= NameMatcher.MIN_SCORE }
                .thenByDescending { it.canReply }
        ).firstOrNull()
    }

    companion object {
        /** At most this many conversations are read before GAR asks whether to go on. */
        const val MAX_PER_STEP = 3

        /** The last conversation a step from [start] reads, see [readStep]. */
        fun stepEnd(conversations: List<Conversation>, start: Int): Int {
            var end = start
            while (end < conversations.size - 1 && end - start < MAX_PER_STEP - 1 &&
                !conversations[end].canReply
            ) {
                ++end
            }
            return end
        }
    }
}
