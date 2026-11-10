package org.stypox.dicio.skills.sms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import org.stypox.dicio.util.getString

/**
 * Asks which of several contacts (or which number of one contact) to text. The numbers are listed
 * with a position each; the user answers with the position or a contact's name, like for the
 * telephone skill.
 */
class SmsChooseOutput(
    private val flow: SmsFlow,
    val contacts: List<SmsContact>,
    val message: String?,
    private val nested: Boolean,
) : SkillOutput {
    private val entries = SmsRecipients.entries(contacts)

    override fun getSpeechOutput(ctx: SkillContext): String = if (contacts.size == 1) {
        ctx.getString(R.string.skill_sms_which_number, contacts[0].name)
    } else {
        ctx.getString(
            R.string.skill_sms_which_contact,
            contacts.joinToString(ctx.getString(R.string.skill_sms_contacts_separator)) { it.name },
        )
    }

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        SmsFlow.plan(nested, listOf(SmsChooserSkill(flow, contacts, message)))

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(
                text = if (contacts.size == 1) {
                    getSpeechOutput(ctx)
                } else {
                    ctx.getString(R.string.skill_sms_which_contact_title)
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Body(
                text = entries.withIndex().joinToString("\n") { (i, entry) ->
                    if (contacts.size == 1) {
                        "${i + 1}. ${entry.number}"
                    } else {
                        "${i + 1}. ${entry.name}, ${entry.number}"
                    }
                }
            )
        }
    }
}

/** An answer to [SmsChooseOutput]. */
sealed interface SmsChooserAnswer {
    data object Cancel : SmsChooserAnswer
    data class Pick(val pick: RecipientPick) : SmsChooserAnswer

    /** "not the first one", "no, not him": who it isn't, so "Which one?" is asked again. */
    data object Again : SmsChooserAnswer

    /** "no, to Aaron": someone who isn't offered, to look up instead. */
    data class Correction(val name: String) : SmsChooserAnswer

    companion object {
        /**
         * [input] as an answer to "Which one?": cancel, a pick ([SmsRecipients.pick]), or a
         * correction ([ContactWords.correction]) that picks among [contacts] ("no, the second one",
         * "no, Sarah Lee") or else names someone to look up, or "not the first one" alone, which
         * asks again. Null if it says none of these.
         */
        fun of(
            input: String,
            contacts: List<SmsContact>,
            parserFormatter: ParserFormatter?,
        ): SmsChooserAnswer? {
            if (isCancelPhrase(input)) {
                return Cancel
            }
            val names = contacts.map { it.name }
            if (ContactWords.rejectsOnly(input, names)) {
                return Again
            }
            val correction = ContactWords.correction(input, names)
                ?: return SmsRecipients.pick(input, contacts, parserFormatter)?.let(::Pick)
            // a position or a whole name of the list after a no picks from it; any other name
            // is looked up among all the contacts, since the list was wrong
            return SmsRecipients.pick(correction, contacts, parserFormatter, looseNames = false)
                ?.let(::Pick)
                ?: Correction(correction)
        }
    }
}

/** Follow-up skill for [SmsChooseOutput], see [SmsChooserAnswer.of]. */
class SmsChooserSkill(
    private val flow: SmsFlow,
    private val contacts: List<SmsContact>,
    private val message: String?,
) : Skill<SmsChooserAnswer?>(SmsInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, SmsChooserAnswer?> {
        val answer = SmsChooserAnswer.of(input, contacts, ctx.parserFormatter)
        return Pair(if (answer == null) AlwaysWorstScore else AlwaysBestScore, answer)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: SmsChooserAnswer?): SkillOutput {
        return when (val answer = inputData) {
            // AlwaysWorstScore is never chosen, so null can't happen
            SmsChooserAnswer.Again -> SmsChooseOutput(flow, contacts, message, nested = true)
            null, SmsChooserAnswer.Cancel -> SmsNotSendingOutput()
            is SmsChooserAnswer.Pick -> when (val pick = answer.pick) {
                is RecipientPick.Picked -> flow.withRecipient(pick.recipient, message, nested = true)
                is RecipientPick.Narrowed ->
                    SmsChooseOutput(flow, listOf(pick.contact), message, nested = true)
            }
            is SmsChooserAnswer.Correction ->
                flow.withSpokenRecipient(ctx, answer.name, message, nested = true)
        }
    }
}
