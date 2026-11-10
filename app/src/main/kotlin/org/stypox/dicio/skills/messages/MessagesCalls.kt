package org.stypox.dicio.skills.messages

import cc.aaro.gar.router.tools.ToolSpec
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.skills.sms.SmsMessage
import org.stypox.dicio.skills.sms.SmsSkill

/**
 * gar:messages. The model's calls of existing tools that the messages and email skills answer,
 * read in Kotlin from the call and its utterance so that the prompt stays as it is (gar/PARITY.md,
 * "Keeping the model's prompt small"; English, like everything GAR routes):
 * - read_notifications about messages ("did mom text me", "read my texts from signal"): the
 *   messages, filtered by person or app ([MessageWords.fromReadCall]); about the notifications
 *   ("read my notifications"), Dicio's notify skill as before;
 * - sms_send that says email ("send an email to my boss"): the email flow, which opens the mail
 *   app, instead of a text the user would decline;
 * - sms_send that replies ("respond to mom with on my way"): the reply through the notification's
 *   reply button, or the offer to text instead when there is no message from that person.
 *
 * `GarFallback` decides with [rerouteInfo] before the call runs, so that Dicio asks for the
 * permissions of the skill that answers (not SEND_SMS for an email) and shows its icon, and only
 * when that skill is turned on; [output] then follows that decision.
 */
object MessagesCalls {
    /**
     * The skill that answers the model's call of [tool] with [rawSlots] for [utterance] instead
     * of the tool's own, or null to leave the call to it: [EmailInfo] or [MessagesInfo], when
     * [isEnabled] says that skill is turned on.
     */
    fun rerouteInfo(
        tool: ToolSpec,
        rawSlots: Map<String, String>,
        utterance: String,
        isEnabled: (String) -> Boolean,
    ): SkillInfo? {
        val info = when (tool.skillId) {
            NOTIFY_SKILL -> MessagesInfo.takeIf { MessageWords.fromReadCall(utterance) != null }
            SMS_SKILL -> {
                val to = rawSlots[SmsSkill.SLOT_TO]?.takeIf { it.isNotBlank() }
                when {
                    asksForEmail(utterance, to, rawSlots[SmsSkill.SLOT_MESSAGE]) -> EmailInfo
                    MessageWords.isReplyCommand(utterance) -> MessagesInfo
                    else -> null
                }
            }
            else -> null
        }
        return info?.takeIf { isEnabled(it.id) }
    }

    /**
     * The answer to [call] when [targetInfo], the skill it runs under ([rerouteInfo]), is the
     * messages or the email skill; null leaves the call to its skill.
     */
    suspend fun output(
        ctx: SkillContext,
        call: GarCall,
        targetInfo: SkillInfo,
        messages: () -> MessagesFlow = { MessagesFlow() },
        email: () -> EmailFlow = { EmailFlow() },
    ): SkillOutput? = when {
        targetInfo.id == EmailInfo.id && call.tool.skillId == SMS_SKILL -> {
            val to = slot(call, SmsSkill.SLOT_TO)
            val message = MessageWords.emailMessage(to, slot(call, SmsSkill.SLOT_MESSAGE))
            email().start(ctx, to, null, message)
        }
        targetInfo.id != MessagesInfo.id -> null
        call.tool.skillId == NOTIFY_SKILL ->
            MessageWords.fromReadCall(call.utterance)?.let { messages().read(ctx, it) }
        call.tool.skillId == SMS_SKILL -> {
            val to = slot(call, SmsSkill.SLOT_TO)
            // the model fills the message with words of the command too (see SmsMessage.fromModel)
            val message = SmsMessage.fromModel(to, slot(call, SmsSkill.SLOT_MESSAGE))
            messages().reply(ctx, to, message, nested = false)
        }
        else -> null
    }

    /**
     * Whether an sms_send call for [utterance] asks for an email: the command's own words say
     * email or mail, outside the recipient [to] and the message ("tell mom i'll check my email
     * later" is a text).
     */
    fun asksForEmail(utterance: String, to: String?, message: String?): Boolean =
        MessageWords.saysEmail(utterance, listOf(to, MessageWords.emailMessage(to, message)))

    private fun slot(call: GarCall, name: String): String? =
        (call.values[name] as? String ?: call.rawSlots[name])?.takeIf { it.isNotBlank() }

    private const val NOTIFY_SKILL = "notify"
    private const val SMS_SKILL = "sms"
}
