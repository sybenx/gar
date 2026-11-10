package org.stypox.dicio.gar.call

import androidx.compose.runtime.Composable
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillOutput

/**
 * The target skill's output when it runs from one of GAR's follow-up skills (an answer or a
 * confirmation). GAR's batch of skills is on top of the stack then, but the target's plan is meant
 * for the stack without it, as if the target had matched directly. So GAR's batch is dropped
 * first: a sub-interaction the target starts replaces GAR's batch, and continuing removes it.
 * Otherwise e.g. a free-text answer skill would stay below the target's own follow-ups and
 * swallow every later input that they don't match.
 */
class GarFollowUpOutput(val output: SkillOutput) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = output.getSpeechOutput(ctx)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        when (val plan = output.getInteractionPlan(ctx)) {
            is InteractionPlan.StartSubInteraction ->
                InteractionPlan.ReplaceSubInteraction(plan.reopenMicrophone, plan.nextSkills)
            is InteractionPlan.Continue ->
                InteractionPlan.FinishSubInteraction(plan.reopenMicrophone)
            // FinishInteraction clears everything anyway, FinishSubInteraction and
            // ReplaceSubInteraction already remove the top batch, which is GAR's
            else -> plan
        }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        output.GraphicalOutput(ctx)
    }
}
