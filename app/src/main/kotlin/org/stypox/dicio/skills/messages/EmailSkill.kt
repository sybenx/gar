package org.stypox.dicio.skills.messages

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences.Email

/**
 * gar:messages (gar/PARITY.md, batch 4): "email sarah saying the report is done" opens the mail
 * app with Sarah's address, a subject and the text filled in, and the user presses send there;
 * GAR never sends an email itself. English only, no tool: the model's sms_send calls that say
 * email reach the same flow (`MessagesCalls`), since "send an email to my boss" used to become a
 * text.
 *
 * A match only counts when the input says email or mail outside the captures: with a word
 * swapped, "send a text to mom" matched "send an email to .who.".
 */
class EmailSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Email>,
    private val flow: EmailFlow,
) : Skill<Email.Send?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Email.Send?> {
        val (score, match) = data.score(ctx, input)
        val send = when (match) {
            is Email.Send -> match.takeIf {
                MessageWords.saysEmail(input, listOf(it.who, it.subject, it.what))
            }
        }?.let { send ->
            // "send an email" also matches "send .who. email" with "an" as the name
            if (send.who?.trim()?.lowercase() in NOT_A_NAME) send.copy(who = null) else send
        }
        return Pair(if (send == null) AlwaysWorstScore else score, send)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Email.Send?): SkillOutput {
        val send = inputData ?: throw IllegalStateException("an email match without email ran")
        return flow.start(ctx, send.who, send.subject, send.what)
    }

    companion object {
        private val NOT_A_NAME = setOf("a", "an", "new", "the", "an new", "a new")
    }
}
