package org.stypox.dicio.skills.holiday

import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.SlotResolvers
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences.Holiday as HolidaySentence
import java.time.LocalDate
import java.time.ZonedDateTime

/** What a question about a holiday or a day asks. */
sealed interface HolidayRequest {
    /** "How many days until Christmas?": [name] is null for a plain day ("until friday"). */
    data class Countdown(val name: String?, val date: LocalDate) : HolidayRequest

    /** "When is Thanksgiving?" */
    data class When(val name: String, val date: LocalDate) : HolidayRequest

    /**
     * "How many days until my birthday?": a personal day GAR doesn't know; [mine]: "my" or
     * "our"; [anniversary]: an anniversary rather than a birthday.
     */
    data class Unknown(val mine: Boolean, val anniversary: Boolean = false) : HolidayRequest
}

/**
 * Countdowns and holidays, offline ([Holidays]): "how many days until christmas", "when is
 * thanksgiving", "how many days until friday" (any day GAR's date resolver reads), "how many days
 * until my birthday" (it says it doesn't know). A captured day that is no holiday, no personal day
 * and (for countdowns) no day at all doesn't match: "how long until my phone is charged", "when
 * is my next alarm". Questions about the date of a plain day ("what day is the fourth of july")
 * stay with the date skill. The model's `date_query` calls get holiday names through
 * `GarSlotRewrites` and countdowns through `GarEverydayCalls`.
 */
class HolidaySkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<HolidaySentence>,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : Skill<HolidayRequest?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, HolidayRequest?> {
        val (score, match) = data.score(ctx, input)
        val today = now().toLocalDate()
        // Dicio's matcher leaves a word out, so each kind of question needs its own words
        val words = Utterances.words(Utterances.normalize(input))
        val request = when (match) {
            is HolidaySentence.Until -> match.day?.takeIf { words.any { it in COUNTDOWN_WORDS } }
                ?.let { countdown(ctx, it, today) }
            is HolidaySentence.When -> match.day?.takeIf { words.any { it in WHEN_WORDS } }
                ?.let { whenIs(it, today) }
            // "what day is the fourth of july" is the date skill's
            is HolidaySentence.DateOf -> match.day
                ?.takeIf { words.any { it in DATE_WORDS } && !isPlainDate(ctx, it) }
                ?.let { whenIs(it, today) }
        }
        return Pair(if (request == null) AlwaysWorstScore else score, request)
    }

    private fun countdown(ctx: SkillContext, day: String, today: LocalDate): HolidayRequest? {
        Holidays.findWithYear(day, today)?.let { (holiday, year) ->
            return HolidayRequest.Countdown(holiday.name, Holidays.date(holiday, today, year))
        }
        personal(day)?.let { return it }
        yearEnd(day, today)?.let { return it }
        // "next week", "in the month": a span, which the date resolver reads as the day that far
        // from today; the model gets it
        val words = Utterances.words(Utterances.normalize(day))
        if (words.all { it in SPAN_WORDS || it in SPAN_FILLERS }) {
            return null
        }
        val resolved = resolvers(ctx).date(day) as? Resolution.Resolved ?: return null
        return HolidayRequest.Countdown(null, resolved.value.date)
    }

    private fun whenIs(day: String, today: LocalDate): HolidayRequest? {
        Holidays.findWithYear(day, today)?.let { (holiday, year) ->
            return HolidayRequest.When(holiday.name, Holidays.date(holiday, today, year))
        }
        return personal(day)
    }

    private fun isPlainDate(ctx: SkillContext, day: String): Boolean =
        resolvers(ctx).date(day) !is Resolution.Unresolvable

    private fun resolvers(ctx: SkillContext) =
        SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage, now)

    override suspend fun generateOutput(ctx: SkillContext, inputData: HolidayRequest?): SkillOutput =
        HolidayOutput(
            inputData ?: throw IllegalStateException("a holiday match without a day ran"),
            now().toLocalDate(),
        )

    companion object {
        /** A birthday or an anniversary, which only the user knows. */
        fun personal(day: String): HolidayRequest.Unknown? {
            val words = Utterances.words(Utterances.normalize(day))
            if (words.none { it in PERSONAL_DAYS }) {
                return null
            }
            return HolidayRequest.Unknown(
                mine = words.any { it in MINE },
                anniversary = words.none { it in BIRTHDAYS },
            )
        }

        /**
         * The year's end as a countdown asks for it: "next year", "the year" ("how many days are
         * left in the year") is New Year's Day, "the end of the year" December 31; else null.
         */
        fun yearEnd(day: String, today: LocalDate): HolidayRequest.Countdown? {
            val words = Utterances.words(Utterances.normalize(day))
            return when {
                YEAR !in words -> null
                "end" in words && words.all { it in END_OF_YEAR_WORDS } ->
                    HolidayRequest.Countdown(null, LocalDate.of(today.year, 12, 31))
                words.all { it in NEW_YEAR_WORDS } -> {
                    val newYear = Holidays.ALL.first()
                    HolidayRequest.Countdown(newYear.name, newYear.dateIn(today.year + 1))
                }
                else -> null
            }
        }

        private const val YEAR = "year"
        private val NEW_YEAR_WORDS = setOf("in", "the", "this", "next", "new", "rest", "of", YEAR)
        private val END_OF_YEAR_WORDS = setOf("end", "of", "the", "this", YEAR)

        /** Spans of time, and the words around them, see [countdown]. */
        private val SPAN_WORDS = setOf("week", "weeks", "month", "months", YEAR, "years")
        private val SPAN_FILLERS =
            setOf("in", "the", "this", "next", "rest", "of", "end", "a", "one")

        private val COUNTDOWN_WORDS =
            setOf("days", "day", "weeks", "sleeps", "long", "countdown", "count")
        private val WHEN_WORDS = setOf("when", "whens", "when's")
        private val DATE_WORDS = setOf("day", "date")
        private val BIRTHDAYS = setOf("birthday", "birthdays", "bday")
        private val PERSONAL_DAYS = BIRTHDAYS + setOf("anniversary", "anniversaries")
        private val MINE = setOf("my", "our")
    }
}
