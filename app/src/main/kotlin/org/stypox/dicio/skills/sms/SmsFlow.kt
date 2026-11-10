package org.stypox.dicio.skills.sms

import android.app.KeyguardManager
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.normalizeAnswer

/**
 * The way from a command to a sent text: find the recipient, ask for what is missing, then show
 * the card ([SmsCardOutput]), which reads the text back and asks whether to send it. The user
 * answers by voice (send, cancel, change the message or the recipient, read it again) or with the
 * card's buttons. The skill (Dicio's sentences and GAR's `sms_send` tool alike) and every
 * follow-up answer go through here.
 *
 * @param findRecipient looks a spoken name up in the contacts and, when asked to, splits off a
 * message said right after it (see [SmsRecipients.findInContacts]); returns the choice and that
 * message
 * @param senderFor sends, or opens the messages app when GAR can't send
 * @param isDeviceLocked whether the phone is locked (with a PIN, pattern, ...): then the skill
 * doesn't run, like GAR's `sms_send` tool (`on_lock_screen: false`), since Dicio's sentences also
 * match on the lock screen (wake word) and the skill shows contacts and can send
 * @param sendTimeoutMs how long a spoken "send it" waits for the network's report
 * @param ownContact who "myself" is ("text myself", see [OwnContact])
 */
class SmsFlow(
    private val findRecipient: (SkillContext, String, Boolean) -> Pair<RecipientChoice, String?> =
        { ctx, spoken, splitMessage ->
            SmsRecipients.findInContacts(ctx.android.contentResolver, spoken, splitMessage)
        },
    val senderFor: (SkillContext) -> SmsSender = { AndroidSmsSender(it.android) },
    private val isDeviceLocked: (SkillContext) -> Boolean = { ctx ->
        ctx.android.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    },
    val sendTimeoutMs: Long = SEND_TIMEOUT_MS,
    private val ownContact: (SkillContext) -> OwnContact = { OwnContact.of(it) },
) {
    /** @param who the recipient as said, [what] the message as said; either may be missing */
    suspend fun start(ctx: SkillContext, who: String?, what: String?): SkillOutput =
        if (isDeviceLocked(ctx)) {
            GarLockedOutput() // "Unlock your phone first."
        } else {
            withSpokenRecipient(ctx, who, SmsMessage.assemble(what), nested = false)
        }

    /**
     * @param nested false for the output that answers the user's command, true for outputs that
     * answer one of this skill's follow-up questions (they replace its batch of skills)
     */
    suspend fun withSpokenRecipient(
        ctx: SkillContext,
        spoken: String?,
        message: String?,
        nested: Boolean,
    ): SkillOutput {
        // "no, to Aaron" in reply to "Who?" is Aaron
        val name = spoken?.let(ContactWords::corrected)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return SmsAskOutput(this, SmsQuestion.RECIPIENT, null, message, null, nested)

        val (choice, rest) = find(ctx, name, message == null)
        return withChoice(ctx, choice, name, message ?: rest?.let(SmsMessage::assemble), nested)
    }

    /**
     * [findRecipient], except that "myself" ("text myself buy milk") is the owner's card with a
     * number, or [RecipientChoice.UnknownOwner] when GAR knows none.
     */
    private suspend fun find(
        ctx: SkillContext,
        name: String,
        splitMessage: Boolean,
    ): Pair<RecipientChoice, String?> {
        val rest = ContactWords.ownRest(name)
        if (rest == null || (rest.isNotEmpty() && !splitMessage)) {
            return findRecipient(ctx, name, splitMessage)
        }
        val choice = ownContact(ctx).cards().firstOrNull { it.numbers.isNotEmpty() }
            ?.let { SmsRecipients.single(SmsContact(it.name, SmsNumbers.distinct(it.numbers))) }
            ?: RecipientChoice.UnknownOwner
        return Pair(choice, rest.takeIf { it.isNotEmpty() })
    }

    private fun withChoice(
        ctx: SkillContext,
        choice: RecipientChoice,
        name: String,
        message: String?,
        nested: Boolean,
    ): SkillOutput = when (choice) {
        is RecipientChoice.One -> withRecipient(choice.recipient, message, nested)
        is RecipientChoice.Several -> SmsChooseOutput(this, choice.contacts, message, nested)
        RecipientChoice.NotFound ->
            SmsAskOutput(this, SmsQuestion.RECIPIENT, null, message, name, nested)
        RecipientChoice.UnknownOwner -> OwnContactAskOutput(
            info = SmsInfo,
            own = ownContact(ctx),
            then = { c, owner -> withSpokenRecipient(c, owner, message, nested = true) },
            cancelled = ::SmsNotSendingOutput,
            needs = OwnNeed.NUMBER,
            nested = nested,
        )
    }

    fun withRecipient(recipient: SmsRecipient, message: String?, nested: Boolean): SkillOutput =
        if (message == null) {
            SmsAskOutput(this, SmsQuestion.MESSAGE, recipient, null, null, nested)
        } else {
            SmsCardOutput(this, SmsDraft(recipient, message), nested)
        }

    /**
     * The user wants a different message for [draft]: it is replaced, and the new message (as
     * said, if the user said it right away) is read back on a new card, or asked for.
     */
    fun changeMessage(draft: SmsDraft, said: String?): SkillOutput {
        draft.replace()
        val message = SmsMessage.assemble(said)
        return if (message == null) {
            SmsAskOutput(this, SmsQuestion.NEW_MESSAGE, draft.recipient, null, null, nested = true)
        } else {
            SmsCardOutput(this, SmsDraft(draft.recipient, message), nested = true)
        }
    }

    /**
     * "send it to dad instead": the text of [draft] goes to [spoken] instead, read back on a new
     * card (or after asking which one). If [spoken] is the recipient the text already has ("yes,
     * send it to mom"), that is a yes, and the text is sent.
     */
    suspend fun changeRecipient(ctx: SkillContext, draft: SmsDraft, spoken: String): SkillOutput {
        val sameName = normalizeAnswer(spoken) == normalizeAnswer(draft.recipient.name)
        val choice = if (sameName) null else find(ctx, spoken, false).first
        val sameNumber = (choice as? RecipientChoice.One)?.recipient?.number
            ?.let { SmsNumbers.same(it, draft.recipient.number) } == true
        if (sameName || sameNumber) {
            return SmsSendOutput(draft.sendAndWait(senderFor(ctx), sendTimeoutMs))
        }

        draft.replace()
        return withChoice(ctx, choice!!, spoken, draft.message, nested = true)
    }

    companion object {
        /**
         * A spoken "send it" waits this long for the network's report, then says the text is
         * still sending.
         */
        const val SEND_TIMEOUT_MS = 10_000L

        fun plan(nested: Boolean, nextSkills: List<Skill<*>>): InteractionPlan =
            if (nested) {
                InteractionPlan.ReplaceSubInteraction(reopenMicrophone = true, nextSkills)
            } else {
                InteractionPlan.StartSubInteraction(reopenMicrophone = true, nextSkills)
            }
    }
}
