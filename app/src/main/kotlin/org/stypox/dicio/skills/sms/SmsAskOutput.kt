package org.stypox.dicio.skills.sms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.skills.conversation.ConversationWords
import org.stypox.dicio.util.getString

enum class SmsQuestion {
    RECIPIENT,
    MESSAGE,

    /** The user wants to change the message that was read back. */
    NEW_MESSAGE,
}

/**
 * Asks who to text or what to write, and takes any answer (except "cancel") as the reply.
 *
 * @param recipient the chosen recipient, when asking for the message
 * @param message the message, if the user already said it
 * @param notFound the name that matched no contact, when asking for the recipient again
 */
class SmsAskOutput(
    private val flow: SmsFlow,
    val question: SmsQuestion,
    val recipient: SmsRecipient?,
    val message: String?,
    val notFound: String?,
    private val nested: Boolean,
) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = when (question) {
        SmsQuestion.RECIPIENT -> if (notFound == null) {
            ctx.getString(R.string.skill_sms_ask_recipient)
        } else {
            ctx.getString(R.string.skill_sms_unknown_contact, notFound)
        }
        SmsQuestion.MESSAGE -> ctx.getString(R.string.skill_sms_ask_message)
        SmsQuestion.NEW_MESSAGE -> ctx.getString(R.string.skill_sms_ask_new_message)
    }

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        SmsFlow.plan(nested, listOf(SmsAnswerSkill(flow, question, recipient, message)))

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            if (recipient != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Body(text = recipientLine(recipient))
            }
        }
    }
}

/** "Sarah (+1 555 0100)", or just the number for a number that is not a contact. */
fun recipientLine(recipient: SmsRecipient): String =
    if (recipient.isContact) "${recipient.name} (${recipient.number})" else recipient.number

/** Follow-up skill for [SmsAskOutput]: any non-empty answer continues, "cancel" stops. */
class SmsAnswerSkill(
    private val flow: SmsFlow,
    private val question: SmsQuestion,
    private val recipient: SmsRecipient?,
    private val message: String?,
) : Skill<String>(SmsInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, String> {
        val trimmed = input.trim()
        return Pair(if (trimmed.isEmpty()) AlwaysWorstScore else AlwaysBestScore, trimmed)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput {
        // "no, I'm good" or "not now" to "Who should I ...?" stops, as the conversation skill would
        if (isCancelPhrase(inputData) ||
            (question == SmsQuestion.RECIPIENT && ConversationWords.isStop(inputData))
        ) {
            return SmsNotSendingOutput()
        }
        return when (question) {
            SmsQuestion.RECIPIENT ->
                flow.withSpokenRecipient(ctx, recipientAnswer(inputData), message, nested = true)
            SmsQuestion.MESSAGE, SmsQuestion.NEW_MESSAGE -> {
                val text = SmsMessage.assemble(inputData)
                if (text == null) {
                    SmsAskOutput(flow, question, recipient, null, null, nested = true)
                } else {
                    flow.withRecipient(recipient!!, text, nested = true)
                }
            }
        }
    }

    companion object {
        private val LEADING_WORDS = setOf("to", "text", "message", "send", "it", "a")

        /** "to mom", "text mom" -> "mom": the words that repeat the command are not the name. */
        fun recipientAnswer(input: String): String {
            val words = input.trim().split(Regex("\\s+"))
            return words.dropWhile { it.lowercase() in LEADING_WORDS }
                .ifEmpty { words }
                .joinToString(" ")
        }
    }
}

/** "OK, not sending." Ends the interaction. */
class SmsNotSendingOutput : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_sms_not_sending)
}
