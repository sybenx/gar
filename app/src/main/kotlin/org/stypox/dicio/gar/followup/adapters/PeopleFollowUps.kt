package org.stypox.dicio.gar.followup.adapters

import cc.aaro.gar.router.followup.CorrectionKind
import cc.aaro.gar.router.followup.FollowUpForm
import org.stypox.dicio.gar.ask.GarPickOutput
import org.stypox.dicio.gar.followup.GarAnswered
import org.stypox.dicio.gar.followup.GarConversationMemory
import org.stypox.dicio.gar.followup.GarFacts
import org.stypox.dicio.gar.followup.GarFollowUpAdapter
import org.stypox.dicio.gar.followup.GarFollowUpContext
import org.stypox.dicio.gar.followup.GarPerson
import org.stypox.dicio.gar.followup.GarPersonSource
import org.stypox.dicio.gar.followup.GarRememberedTurn
import org.stypox.dicio.gar.followup.GarRewrite
import org.stypox.dicio.gar.followup.GarRewriteText
import org.stypox.dicio.gar.followup.GarTurn
import org.stypox.dicio.gar.followup.GarTurnKind
import org.stypox.dicio.skills.contact_info.ContactField
import org.stypox.dicio.skills.contact_info.ContactInfoOutput
import org.stypox.dicio.skills.sms.SmsCardOutput
import org.stypox.dicio.skills.sms.SmsChooseOutput
import org.stypox.dicio.skills.sms.SmsSendOutput
import org.stypox.dicio.skills.sms.SmsState
import org.stypox.dicio.skills.telephone.ConfirmCallOutput
import org.stypox.dicio.skills.telephone.ConfirmedCallOutput
import org.stypox.dicio.skills.telephone.TelephoneOutput

/**
 * Calls, contacts and texts. A turn remembers the person it was about ("call her" after "what's
 * erin's number") and the text the user sent ("send it to mom too"); never a message someone else
 * wrote. The person is [GarPerson.unique] only when the lookup chose the contact without asking
 * "Which Erin?" and the name is [GarRewriteText.safe]. Not on the lock screen, like the tools.
 */
object PeopleFollowUps : GarFollowUpAdapter {
    const val TELEPHONE = "telephone"
    const val CONTACT_INFO = "contact_info"
    const val SMS = "sms"

    /** [GarTurn.slots] keys. */
    const val WHO = "who"
    const val MESSAGE = "message"

    override val skillIds = setOf(TELEPHONE, CONTACT_INFO, SMS)

    override fun remember(answered: GarAnswered): GarTurn? {
        val asked = GarConversationMemory.askedBy(answered.skillId)
        return when (val output = answered.output) {
            is ConfirmedCallOutput -> {
                // "no" to "Call Erin?": nothing happened
                val number = output.number ?: return null
                // the "Call Erin?" of this number, whichever skill asked it (call_log's call back
                // asks too, and the telephone's yes answers)
                val questions = GarConversationMemory.askedByAny()
                val name = questions.filterIsInstance<ConfirmCallOutput>().lastOrNull { it.number == number }?.name
                    ?: number
                val chooser = questions.any { it is TelephoneOutput }
                turn(answered, person(name, number, GarPersonSource.CONTACT, chooser || name == number))
            }
            is ContactInfoOutput.Found -> if (output.own) {
                turn(answered, null)
            } else {
                val number = output.values.firstOrNull()?.value?.takeIf { output.field == ContactField.NUMBER }
                turn(answered, person(output.name, number, GarPersonSource.CONTACT, asked.any { it is GarPickOutput<*> }))
            }
            is SmsSendOutput -> {
                val draft = asked.filterIsInstance<SmsCardOutput>().lastOrNull()?.draft
                    ?: return turn(answered, null)
                val recipient = draft.recipient
                val person = person(
                    recipient.name,
                    recipient.number,
                    if (recipient.isContact) GarPersonSource.CONTACT else GarPersonSource.CALL_LOG,
                    asked.any { it is SmsChooseOutput } || !recipient.isContact,
                )
                // only what the user wrote, and only once it went
                val sent = output.state in SENT_STATES
                turn(answered, person, ownText = draft.message.takeIf { sent })
            }
            else -> turn(answered, null)
        }
    }

    override fun rewrite(form: FollowUpForm, turn: GarRememberedTurn, context: GarFollowUpContext): GarRewrite? {
        val text = when (form) {
            // "I said Erin" right after "call aaron", or after a text to Aaron
            is FollowUpForm.Correction -> if (form.kind == CorrectionKind.OTHER) {
                null
            } else {
                form.value?.let { GarRewriteText.words(it) }?.let { who ->
                    when (turn.turn.skillId) {
                        TELEPHONE -> "call $who"
                        SMS -> textTo(who, turn.turn.facts.ownText)
                        else -> null
                    }
                }
            }
            // "send it to mom too": only the user's own text goes on
            is FollowUpForm.SendTo -> GarRewriteText.words(form.who)?.let { who ->
                if (turn.turn.skillId == SMS) textTo(who, turn.turn.facts.ownText) else null
            }
            else -> null
        } ?: return null
        return GarRewrite.Command(text)
    }

    /**
     * A new text of [message] to [who]; the sms card reads it back before anything is sent. A
     * message that can't go into text ("on my way to work") isn't dropped: the sms skill asks
     * "What's the message?" instead.
     */
    private fun textTo(who: String, message: String?): String? = message?.let {
        GarRewriteText.words(it)?.let { words -> "send a text to $who saying $words" } ?: "send a text to $who"
    }

    private fun person(name: String, number: String?, source: GarPersonSource, asked: Boolean) =
        GarPerson(name, number, source, unique = !asked && GarRewriteText.safe(name))

    private fun turn(answered: GarAnswered, person: GarPerson?, ownText: String? = null) = GarTurn(
        skillId = answered.skillId,
        action = answered.skillId,
        slots = listOfNotNull(
            person?.let { WHO to it.name },
            ownText?.let { MESSAGE to it },
        ).toMap(),
        facts = GarFacts(person = person, ownText = ownText),
        command = answered.input,
        kind = GarTurnKind.ANSWER,
    )

    private val SENT_STATES = setOf(SmsState.SENT, SmsState.SENDING, SmsState.NO_REPORT)
}
