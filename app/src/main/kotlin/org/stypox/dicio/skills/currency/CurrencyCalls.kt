package org.stypox.dicio.skills.currency

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.online.GarOnlineGateSkill
import org.stypox.dicio.gar.online.GarOnlineSkill

/**
 * gar:knowledge. The model's `calculate` calls about money ("how much is 20 euros in dollars" ->
 * `calculate(calculation: "20 euros in dollars")`): the currency converter's answer, through
 * GAR's ask-once gate like a direct request, so the prompt stays as it is (gar/PARITY.md,
 * "Keeping the model's prompt small", point 2). Null leaves the call to the calculator (and to
 * the unit converter before it).
 */
object CurrencyCalls {
    suspend fun output(
        ctx: SkillContext,
        call: GarCall,
        gate: () -> GarOnlineGateSkill = {
            GarOnlineGateSkill(GarOnlineSkill.CURRENCY, CurrencyInfo.newSkill())
        },
    ): SkillOutput? {
        if (call.tool.skillId != CALCULATOR_SKILL) {
            return null
        }
        val request = listOfNotNull(call.rawSlots[CALCULATION_SLOT], call.utterance)
            .firstNotNullOfOrNull { CurrencyRequests.parse(it, ctx.parserFormatter) }
            ?: return null
        // asks once while the skill is off, runs the skill when it is on
        return gate().generateOutput(ctx, request)
    }

    private const val CALCULATOR_SKILL = "calculator"
    private const val CALCULATION_SLOT = "calculation"
}
