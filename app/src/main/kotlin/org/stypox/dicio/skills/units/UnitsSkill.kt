package org.stypox.dicio.skills.units

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences.Units

/**
 * Unit conversion, offline ([UnitConversion]): "how many cups in a liter", "convert seventy
 * fahrenheit to celsius", "five miles in kilometers". The sentences capture the amount and the
 * target unit; a match whose captures aren't a conversion between units of the same kind doesn't
 * match at all (the captures take any words: "remind me to call mom" fits ".amount. to
 * .target."). The model's `calculate` calls that ask for a conversion get the same answer
 * (`GarEverydayCalls`).
 */
class UnitsSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Units>,
) : Skill<Conversion?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Conversion?> {
        val (score, match) = data.score(ctx, input)
        val (amount, target) = when (match) {
            is Units.Convert -> Pair(match.amount, match.target)
            is Units.HowMany -> Pair(match.amount, match.target)
        }
        val conversion = if (amount == null || target == null) {
            null
        } else {
            UnitConversion.request(amount, target, ctx.parserFormatter)
        }
        return Pair(if (conversion == null) AlwaysWorstScore else score, conversion)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Conversion?): SkillOutput =
        UnitsOutput(inputData ?: throw IllegalStateException("a units match without units ran"))
}
