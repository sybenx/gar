package org.stypox.dicio.skills.math

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.FloatScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.skills.calculator.CalculatorOutput
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/**
 * Spoken arithmetic that Dicio's calculator sentence doesn't take or gets wrong: a bare
 * expression ("seven plus nine", which went to the model and became a timer), percentages
 * ("what's fifteen percent of eighty", "a twenty percent tip on eighty five"), squares and roots,
 * and calculations with polite words ("what is 8 times 7 please"). The whole input must be the
 * expression ([SpokenMath]), so nothing with other words is taken. An input the calculator's own
 * sentence computes the same way ("what is two plus two") stays the calculator's. Answers like
 * the calculator does: the result spoken, the expression on the card.
 */
class MathSkill(correspondingSkillInfo: SkillInfo) :
    Skill<MathExpression?>(correspondingSkillInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, MathExpression?> {
        val expression = SpokenMath.parse(input, ctx.parserFormatter, ctx.locale)
        val takes = expression != null &&
            !(expression.calculatorLead && expression.plainOperators && !expression.extraWords)
        return Pair(if (takes) FloatScore(1.0f) else AlwaysWorstScore, expression)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: MathExpression?): SkillOutput {
        val value = inputData?.value ?: return CalculatorOutput(null, "", "")
        // rounded and spoken like the calculator's results
        val result = DecimalFormat("#.##", DecimalFormatSymbols(ctx.locale)).format(value)
        return CalculatorOutput(
            result = result,
            spokenResult = ctx.parserFormatter?.niceNumber(value)?.get() ?: result,
            inputInterpretation = inputData.interpretation,
        )
    }
}
