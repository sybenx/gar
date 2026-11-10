package org.stypox.dicio.skills.date

import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.ResolvedDate
import cc.aaro.gar.router.resolve.SlotResolvers
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.gar.call.DefaultGarSlotResolver
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarCallRunner
import org.stypox.dicio.gar.call.GarToolSkill
import org.stypox.dicio.gar.call.GarValueFormatter
import org.stypox.dicio.sentences.Sentences
import java.time.LocalDate
import java.time.ZonedDateTime

/** What the date skill is asked about. */
sealed interface DateInput {
    /** Matched by a sentence: the day as the user said it, or null for today. */
    data class Said(val day: String?) : DateInput

    /** A resolved day, or null for today. Both the sentences and GAR end here. */
    data class Day(val date: LocalDate?) : DateInput
}

/**
 * Tells the date and weekday of today or of another day ("tomorrow", "on friday", "in three
 * days"). The day a sentence captures is said text: it is resolved with GAR's date resolver
 * (dicio-numbers misreads spoken dates), through GAR's call runner, so that a day it can't
 * understand gets GAR's question. A capture that neither resolves nor is made of day words
 * doesn't match at all ([score]). The model's `date_query` calls arrive already resolved
 * ([inputFromSlots]).
 *
 * @param data null for languages without sentences: then only GAR reaches the skill
 */
class DateSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Sentences.Date>?,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val formatterFactory: (SkillContext) -> GarValueFormatter = GarValueFormatter::forContext,
) : Skill<DateInput>(correspondingSkillInfo, data?.specificity ?: Specificity.HIGH), GarToolSkill {

    override fun score(ctx: SkillContext, input: String): Pair<Score, DateInput> {
        if (data == null) {
            return Pair(AlwaysWorstScore, DateInput.Day(null))
        }
        val (score, match) = data.score(ctx, input)
        val day = when (match) {
            is Sentences.Date.Query -> match.day?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (day != null && !asksAboutDay(ctx, day)) {
            // the capture takes any words, so "what is the capital of france" would otherwise
            // match "what is the date <day>" with one word missing, and "what's the date of the
            // next full moon" would get GAR's "What day?": leave them to the model
            return Pair(AlwaysWorstScore, DateInput.Said(null))
        }
        return Pair(score, DateInput.Said(day))
    }

    /**
     * Whether [day], captured by a date sentence, names a day: it resolves, or it is made of day
     * words only ("this weekend"), so that GAR's question about it makes sense.
     */
    private fun asksAboutDay(ctx: SkillContext, day: String): Boolean =
        mentionsDay(day) && (onlyDayWords(day) || resolvers(ctx).date(day) is Resolution.Resolved)

    override suspend fun generateOutput(ctx: SkillContext, inputData: DateInput): SkillOutput {
        val today = now().toLocalDate()
        return when (inputData) {
            is DateInput.Day -> DateOutput(inputData.date ?: today, today)
            is DateInput.Said -> if (inputData.day == null) {
                DateOutput(today, today)
            } else {
                // resolves the day, asks when it can't, and comes back through inputFromSlots
                newRunner(ctx).next(
                    ctx,
                    GarCall(DATE_QUERY_TOOL, mapOf(DAY_SLOT to inputData.day), inputData.day),
                    nested = false,
                )
            }
        }
    }

    override fun inputFromSlots(slots: Map<String, Any?>): DateInput =
        DateInput.Day((slots[DAY_SLOT] as? ResolvedDate)?.date)

    private fun newRunner(ctx: SkillContext) = GarCallRunner(
        targetInfo = correspondingSkillInfo,
        target = this,
        confirm = false,
        resolver = DefaultGarSlotResolver(resolvers(ctx)),
        formatter = formatterFactory(ctx),
        yesNoData = null,
        now = { now().toLocalDateTime() },
    )

    private fun resolvers(ctx: SkillContext) =
        SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage, now)

    companion object {
        const val DAY_SLOT = "day"

        private val DAY_WORDS = setOf(
            "today", "tomorrow", "yesterday", "day", "days", "week", "weeks", "weekend",
            "month", "months", "year", "years", "next", "last", "ago", "after", "before",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "january", "february", "march", "april", "may", "june", "july", "august",
            "september", "october", "november", "december",
            "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
            "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
            "seventeenth", "eighteenth", "nineteenth", "twentieth", "thirtieth",
        )
        /** Words that may come with day words in a day: "on the", "in three", "this". */
        private val DAY_PHRASE_WORDS = setOf(
            "the", "this", "that", "on", "in", "at", "of", "a", "an", "and", "from", "now",
            "coming", "following", "previous", "weekday",
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty", "thirty",
        )
        private val ORDINAL_DIGITS = Regex("""\d{1,2}(st|nd|rd|th)""")
        private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

        private fun words(text: String) =
            text.lowercase().split(WORD_SEPARATOR).filter { it.isNotEmpty() }

        /**
         * Whether [text] has a word that can name a day (English): a cheap check that keeps the
         * capture of the date sentences from swallowing other questions. Whether the day can be
         * resolved is up to GAR's resolver, which asks when it can't.
         */
        fun mentionsDay(text: String): Boolean =
            words(text).any { it in DAY_WORDS || ORDINAL_DIGITS.matches(it) }

        /**
         * Whether [text] is made only of words that can make up a day ("this weekend", "the
         * thirty second"), even if GAR's resolver can't read it: "the next full moon" isn't.
         */
        fun onlyDayWords(text: String): Boolean =
            words(text).all {
                it in DAY_WORDS || it in DAY_PHRASE_WORDS || ORDINAL_DIGITS.matches(it) ||
                    it.all(Char::isDigit)
            }

        /**
         * The `date_query` tool of `tools.yml`, for running the sentences' calls through GAR's
         * runner (a unit test keeps the two in sync). Never shown to the model.
         */
        val DATE_QUERY_TOOL = ToolSpec(
            name = "date_query",
            skillId = "date",
            sentence = null,
            description = "Tell the date.",
            confirm = false,
            slots = listOf(SlotSpec(DAY_SLOT, SlotKind.DATE, required = false, description = "")),
            examples = listOf(),
            onLockScreen = true,
        )
    }
}
