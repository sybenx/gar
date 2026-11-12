package org.stypox.dicio.skills.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.dicio.numbers.ParserFormatter
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
import org.stypox.dicio.skills.sms.ContactWords
import org.stypox.dicio.skills.sms.SmsFlow
import org.stypox.dicio.skills.sms.SmsMessage
import org.stypox.dicio.util.getString

enum class EmailQuestion {
    RECIPIENT,
    MESSAGE,
}

/** "Name <address>", or the address alone when it is no contact. */
fun emailRecipientLine(recipient: EmailRecipient): String =
    if (recipient.isContact) "${recipient.name} <${recipient.address}>" else recipient.address

/**
 * Asks whom to email or what the email says, and takes any answer (except "cancel") as the reply.
 *
 * @param recipient the chosen recipient, when asking for the text
 * @param notFound the name that matched no contact with an email address, when asking again
 */
class EmailAskOutput(
    private val flow: EmailFlow,
    val question: EmailQuestion,
    val recipient: EmailRecipient?,
    val subject: String?,
    val message: String?,
    val notFound: String?,
    private val nested: Boolean,
) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = when (question) {
        EmailQuestion.RECIPIENT -> if (notFound == null) {
            ctx.getString(R.string.skill_email_ask_recipient)
        } else {
            ctx.getString(R.string.skill_email_unknown_contact, notFound)
        }
        EmailQuestion.MESSAGE -> ctx.getString(R.string.skill_email_ask_message)
    }

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        SmsFlow.plan(nested, listOf(EmailAnswerSkill(flow, question, recipient, subject, message)))

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            if (recipient != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Body(text = stringResource(R.string.skill_email_to, emailRecipientLine(recipient)))
            }
        }
    }
}

/** Follow-up skill for [EmailAskOutput]: any non-empty answer continues, "cancel" stops. */
class EmailAnswerSkill(
    private val flow: EmailFlow,
    private val question: EmailQuestion,
    private val recipient: EmailRecipient?,
    private val subject: String?,
    private val message: String?,
) : Skill<String>(EmailInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, String> {
        val trimmed = input.trim()
        return Pair(if (trimmed.isEmpty()) AlwaysWorstScore else AlwaysBestScore, trimmed)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput {
        // "no, I'm good" or "not now" to "Who should I ...?" stops, as the conversation skill would
        if (isCancelPhrase(inputData) ||
            (question == EmailQuestion.RECIPIENT && ConversationWords.isStop(inputData))
        ) {
            return EmailCancelledOutput()
        }
        return when (question) {
            EmailQuestion.RECIPIENT ->
                flow.withSpokenRecipient(ctx, recipientAnswer(inputData), subject, message, nested = true)
            EmailQuestion.MESSAGE -> {
                val text = SmsMessage.assemble(inputData)
                if (text == null) {
                    EmailAskOutput(flow, question, recipient, subject, null, null, nested = true)
                } else {
                    flow.withRecipient(ctx, recipient!!, subject, text, nested = true)
                }
            }
        }
    }

    companion object {
        private val LEADING_WORDS = setOf("to", "email", "mail", "send", "it", "an", "a")

        /** "to sarah", "email sarah" -> "sarah": the words that repeat the command are not the name. */
        fun recipientAnswer(input: String): String {
            val words = input.trim().split(Regex("\\s+"))
            return words.dropWhile { it.lowercase() in LEADING_WORDS }
                .ifEmpty { words }
                .joinToString(" ")
        }
    }
}

/**
 * Asks which of several contacts (or which address of one contact) to email; the user answers
 * with the position or a contact's name, like for a text.
 */
class EmailChooseOutput(
    private val flow: EmailFlow,
    val contacts: List<EmailContact>,
    val subject: String?,
    val message: String?,
    private val nested: Boolean,
) : SkillOutput {
    private val entries = EmailRecipients.entries(contacts)

    override fun getSpeechOutput(ctx: SkillContext): String = if (contacts.size == 1) {
        ctx.getString(R.string.skill_email_which_address, contacts[0].name)
    } else {
        ctx.getString(
            R.string.skill_email_which_contact,
            contacts.joinToString(ctx.getString(R.string.skill_email_contacts_separator)) { it.name },
        )
    }

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        SmsFlow.plan(nested, listOf(EmailChooserSkill(flow, contacts, subject, message)))

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(
                text = if (contacts.size == 1) {
                    getSpeechOutput(ctx)
                } else {
                    ctx.getString(R.string.skill_email_which_contact_title)
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Body(
                text = entries.withIndex().joinToString("\n") { (i, entry) ->
                    if (contacts.size == 1) {
                        "${i + 1}. ${entry.address}"
                    } else {
                        "${i + 1}. ${entry.name}, ${entry.address}"
                    }
                }
            )
        }
    }
}

/** An answer to [EmailChooseOutput]. */
sealed interface EmailChooserAnswer {
    data object Cancel : EmailChooserAnswer
    data class Pick(val pick: EmailPick) : EmailChooserAnswer

    /** "not the first one", "no, not him": who it isn't, so "Which one?" is asked again. */
    data object Again : EmailChooserAnswer

    /** "no, to Aaron": someone who isn't offered, to look up instead. */
    data class Correction(val name: String) : EmailChooserAnswer

    companion object {
        /**
         * [input] as an answer to "Which one?", like a text's (`SmsChooserAnswer.of`): cancel, a
         * pick ([EmailRecipients.pick]), or a correction that picks among [contacts] or else
         * names someone to look up, or "not the first one" alone, which asks again. Null if it
         * says none of these.
         */
        fun of(
            input: String,
            contacts: List<EmailContact>,
            parserFormatter: ParserFormatter?,
        ): EmailChooserAnswer? {
            if (isCancelPhrase(input)) {
                return Cancel
            }
            val names = contacts.map { it.name }
            if (ContactWords.rejectsOnly(input, names)) {
                return Again
            }
            val correction = ContactWords.correction(input, names)
                ?: return EmailRecipients.pick(input, contacts, parserFormatter)?.let(::Pick)
            // a position or a whole name of the list after a no picks from it; any other name
            // is looked up among all the contacts, since the list was wrong
            return EmailRecipients.pick(correction, contacts, parserFormatter, looseNames = false)
                ?.let(::Pick)
                ?: Correction(correction)
        }
    }
}

/** Follow-up skill for [EmailChooseOutput], see [EmailChooserAnswer.of]. */
class EmailChooserSkill(
    private val flow: EmailFlow,
    private val contacts: List<EmailContact>,
    private val subject: String?,
    private val message: String?,
) : Skill<EmailChooserAnswer?>(EmailInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, EmailChooserAnswer?> {
        val answer = EmailChooserAnswer.of(input, contacts, ctx.parserFormatter)
        return Pair(if (answer == null) AlwaysWorstScore else AlwaysBestScore, answer)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: EmailChooserAnswer?): SkillOutput {
        return when (val answer = inputData) {
            // AlwaysWorstScore is never chosen, so null can't happen
            EmailChooserAnswer.Again -> EmailChooseOutput(flow, contacts, subject, message, nested = true)
            null, EmailChooserAnswer.Cancel -> EmailCancelledOutput()
            is EmailChooserAnswer.Pick -> when (val pick = answer.pick) {
                is EmailPick.Picked -> flow.withRecipient(ctx, pick.recipient, subject, message, nested = true)
                is EmailPick.Narrowed ->
                    EmailChooseOutput(flow, listOf(pick.contact), subject, message, nested = true)
            }
            is EmailChooserAnswer.Correction ->
                flow.withSpokenRecipient(ctx, answer.name, subject, message, nested = true)
        }
    }
}

/**
 * The mail app was opened with the email ("Your email to Sarah is ready. Press send in your mail
 * app."), or no mail app could show it. The card shows the email and opens it again.
 */
class EmailOpenedOutput(
    private val flow: EmailFlow,
    val draft: EmailDraft,
    val opened: Boolean,
) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = if (opened) {
        ctx.getString(R.string.skill_email_opened, draft.recipient.name)
    } else {
        ctx.getString(R.string.skill_email_no_app)
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            Spacer(modifier = Modifier.height(8.dp))
            Body(text = stringResource(R.string.skill_email_to, emailRecipientLine(draft.recipient)))
            Body(text = stringResource(R.string.skill_email_subject, draft.subject))
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = draft.body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(12.dp),
                )
            }
            if (opened) {
                Spacer(modifier = Modifier.height(8.dp))
                GarbleSecondaryButton( // GAR
                    onClick = { flow.mailerFor(ctx).compose(draft) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(text = stringResource(R.string.skill_email_open_again))
                }
            }
        }
    }
}

/** "OK." after "cancel" while GAR asked about an email. */
class EmailCancelledOutput : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_email_not_sending)
}
