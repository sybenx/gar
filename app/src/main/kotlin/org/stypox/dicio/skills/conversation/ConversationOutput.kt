package org.stypox.dicio.skills.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.gar.conversation.GarEndsConversation
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

/**
 * "Stop", "never mind": GAR says nothing, and its card says "OK". Ends the interaction, so a
 * pending question is gone and the microphone stays off, also after a continued-listening turn
 * (GarEndsConversation).
 *
 * @param before the answer before this one, which "repeat that" says again
 */
class ConversationStopOutput(val before: SkillOutput?) : SkillOutput, GarEndsConversation {
    override fun getSpeechOutput(ctx: SkillContext): String = ""

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Headline(text = stringResource(R.string.skill_conversation_stopped))
    }
}

/**
 * "Repeat that": GAR's last answer said again, as [said]. The pending batches stay
 * ([ConversationSkill.keepsQuestion]), and the microphone opens again if it did after that
 * answer, so a question is asked again and can be answered.
 */
class ConversationRepeatOutput(
    val said: String,
    private val reopenMicrophone: Boolean,
) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = said

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.Continue(reopenMicrophone)

    companion object {
        /**
         * The output for "repeat that" after [previous], the last answer in the log: what it said
         * (what the answer before a "stop" said), or that there is nothing to repeat.
         */
        fun of(ctx: SkillContext, previous: SkillOutput?): SkillOutput {
            val said = lastSaid(ctx, previous)?.takeIf { it.isNotBlank() }
                ?: return ConversationNothingToRepeatOutput()
            // the question asked again listens again, if it did; a "stop" before ended it
            val reopen = try {
                previous?.getInteractionPlan(ctx)?.reopenMicrophone == true
            } catch (e: Exception) {
                false
            }
            return ConversationRepeatOutput(said, reopen)
        }

        private fun lastSaid(ctx: SkillContext, output: SkillOutput?): String? = when (output) {
            null -> null
            is ConversationStopOutput -> lastSaid(ctx, output.before)
            else -> output.getSpeechOutput(ctx)
        }
    }
}

/** "Repeat that" before GAR said anything. Changes nothing in the conversation. */
class ConversationNothingToRepeatOutput : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_conversation_nothing_to_repeat)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.Continue(reopenMicrophone = false)
}
