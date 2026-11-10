package org.stypox.dicio.skills.currency

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.FloatScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity

/**
 * Currency conversion (gar/PARITY.md, batch 5): "how much is 20 euros in dollars", "100 yen to
 * usd", "exchange rate for pesos". An online skill under GAR's ask-once rule
 * (`GarOnlineSkill.CURRENCY`) that only downloads the ECB's daily rates ([EcbRateSource]); the
 * conversion happens on the phone, and without a connection the kept rates answer with their
 * date. The model's `calculate` calls about money get the same answer (`CurrencyCalls`).
 *
 * English only, read in Kotlin ([CurrencyRequests]): every word of the input must be part of a
 * request, so a near-miss never runs it.
 *
 * @param rates the rate source, built on first use (the app's files directory)
 */
class CurrencySkill(
    correspondingSkillInfo: SkillInfo,
    private val rates: (SkillContext) -> EcbRateSource,
) : Skill<CurrencyRequest?>(correspondingSkillInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, CurrencyRequest?> {
        val request = CurrencyRequests.parse(input, ctx.parserFormatter)
        return Pair(if (request == null) AlwaysWorstScore else FloatScore(1.0f), request)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: CurrencyRequest?): SkillOutput {
        val request = inputData
            ?: throw IllegalStateException("a currency match without a request ran")
        return answer(request, rates(ctx).rates())
    }

    companion object {
        /** The answer to [request] with [result]. */
        fun answer(request: CurrencyRequest, result: RatesResult): CurrencyOutput {
            val (rates, cached) = when (result) {
                is RatesResult.Fresh -> Pair(result.rates, false)
                is RatesResult.Cached -> Pair(result.rates, true)
                RatesResult.Unavailable -> return CurrencyOutput.Unavailable
            }
            val missing = listOf(request.from, request.to).firstOrNull { !rates.has(it) }
            if (missing != null) {
                return CurrencyOutput.NoRate(missing)
            }
            val rate = rates.rate(request.from, request.to)
                ?: return CurrencyOutput.NoRate(request.from)
            return CurrencyOutput.Converted(request, request.amount * rate, rate, rates.date, cached)
        }
    }
}
