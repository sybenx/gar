package org.stypox.dicio.gar.call

import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.resolve.ResolvedDate
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.skills.holiday.HolidayOutput
import org.stypox.dicio.skills.holiday.HolidayRequest
import org.stypox.dicio.skills.holiday.Holidays
import org.stypox.dicio.skills.units.UnitConversion
import org.stypox.dicio.skills.units.UnitsOutput
import org.stypox.dicio.skills.world_time.WorldTimeOutput
import org.stypox.dicio.skills.world_time.WorldTimePhrases
import java.time.ZonedDateTime

/**
 * gar:answers. What the model's calls of existing tools answer when the target skill can't, read
 * in Kotlin from the call and the utterance, so that the prompt stays as it is (gar/PARITY.md,
 * batch 2; English, like everything GAR routes):
 * - `current_time` naming a place ("what's the time over in tokyo") or a time difference: the
 *   world clock's answer instead of the local time;
 * - `calculate` with a unit conversion ("five miles in kilometers"): the unit converter's answer;
 * - `date_query` for a holiday (its name resolves through [GarSlotRewrites]) or asking how many
 *   days until a day: the holiday skill's answer ("Christmas is in 59 days.").
 * Null leaves the call to its skill.
 */
object GarEverydayCalls {
    fun output(
        ctx: SkillContext,
        call: GarCall,
        now: () -> ZonedDateTime = { ZonedDateTime.now() },
    ): SkillOutput? = when (call.tool.skillId) {
        CURRENT_TIME_SKILL ->
            WorldTimePhrases.fromUtterance(call.utterance)?.let { WorldTimeOutput(it, now()) }
        CALCULATOR_SKILL -> listOfNotNull(call.rawSlots[CALCULATION_SLOT], call.utterance)
            .firstNotNullOfOrNull { UnitConversion.parse(it, ctx.parserFormatter) }
            ?.let { UnitsOutput(it) }
        DATE_SKILL -> dateOutput(call, now())
        else -> null
    }

    private fun dateOutput(call: GarCall, now: ZonedDateTime): SkillOutput? {
        val date = (call.values[DAY_SLOT] as? ResolvedDate)?.date ?: return null
        val holiday = call.rawSlots[DAY_SLOT]?.let { Holidays.findWithYear(it, now.toLocalDate()) }
        val request = when {
            asksHowLong(call.utterance) -> HolidayRequest.Countdown(holiday?.first?.name, date)
            holiday != null -> HolidayRequest.When(holiday.first.name, date)
            else -> return null
        }
        return HolidayOutput(request, now.toLocalDate())
    }

    /** "how many days until", "how long until", "how many weeks before". */
    fun asksHowLong(utterance: String): Boolean {
        val words = Utterances.words(Utterances.normalize(utterance))
        return words.windowed(2).any { (first, second) ->
            first == "how" && (second == "long" || second == "many" || second == "soon")
        } && words.any { it in COUNTDOWN_WORDS }
    }

    private val COUNTDOWN_WORDS = setOf("until", "till", "til", "before", "to", "left")

    private const val CURRENT_TIME_SKILL = "current_time"
    private const val CALCULATOR_SKILL = "calculator"
    private const val CALCULATION_SLOT = "calculation"
    private const val DATE_SKILL = "date"
    private const val DAY_SLOT = "day"
}
