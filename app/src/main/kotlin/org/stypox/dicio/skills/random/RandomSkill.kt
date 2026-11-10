package org.stypox.dicio.skills.random

import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences.Random
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

/**
 * Flip a coin, roll dice ("roll a d twenty", "roll two dice"), pick a number ("between one and
 * ten"; one to ten when no range is said). Each kind of sentence only counts when the input names
 * what it is about: a coin (or heads and tails), dice ([RandomPhrases.dice]: "roll down the
 * window" isn't), or a number to pick at random ("what's my phone number" isn't).
 */
class RandomSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Random>,
    private val random: kotlin.random.Random = SecureRandom().asKotlinRandom(),
) : Skill<RandomRequest?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, RandomRequest?> {
        val (score, match) = data.score(ctx, input)
        val words = Utterances.words(Utterances.normalize(input)).toSet()
        val request = when (match) {
            is Random.Coin -> RandomRequest.Coin.takeIf {
                "coin" in words || ("heads" in words && "tails" in words)
            }
            is Random.Dice -> match.dice?.let { RandomPhrases.dice(it, ctx.parserFormatter) }
            is Random.Number -> when {
                "number" !in words || words.none { it in PICK_WORDS } -> null
                match.low == null || match.high == null -> RandomPhrases.DEFAULT_RANGE
                else -> RandomPhrases.range(match.low, match.high, ctx.parserFormatter)
            }
        }
        return Pair(if (request == null) AlwaysWorstScore else score, request)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: RandomRequest?): SkillOutput =
        when (inputData) {
            RandomRequest.Coin -> CoinOutput(heads = random.nextBoolean())
            is RandomRequest.Dice -> DiceOutput(
                inputData.sides,
                List(inputData.count) { random.nextInt(1, inputData.sides + 1) },
            )
            is RandomRequest.Pick ->
                NumberOutput(inputData, random.nextLong(inputData.low, inputData.high + 1))
            null -> throw IllegalStateException("a random match without a request ran")
        }

    companion object {
        /** A number sentence needs one of them: "pick a number", "a random number". */
        private val PICK_WORDS = setOf(
            "random", "pick", "choose", "generate", "think", "between", "from",
        )
    }
}
