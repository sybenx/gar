package org.stypox.dicio.skills.date

import cc.aaro.gar.router.resolve.ResolvedDate
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarAskSlotOutput
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarQuestion
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.sentences.Sentences
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** Sunday, October 25, 2026, at noon. */
private val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 25, 12, 0)
private val TODAY: LocalDate = NOW.toLocalDate()

private fun dateSkill() = DateSkill(
    DateInfo,
    Sentences.Date["en"],
    now = { NOW.atZone(ZoneOffset.UTC) },
    formatterFactory = { englishFormatter() },
)

/** What the date skill answers for [input], as the evaluator would run it. */
private suspend fun answer(input: String): SkillOutput {
    val skill = dateSkill()
    val ctx = FakeSkillContext().forInput(input)
    val (_, inputData) = skill.score(ctx, input)
    return skill.generateOutput(FakeSkillContext(), inputData)
}

class DateSkillTest : StringSpec({
    "the sentences capture the day as said, or none for today" {
        val cases = mapOf(
            "what's the date" to null,
            "what is the date today" to null,
            "what's today's date" to null,
            "what day is it" to null,
            "what day is it today" to null,
            "what day of the week is it" to null,
            "tell me the date" to null,
            "what's the date tomorrow" to "tomorrow",
            "what is the date on friday" to "on friday",
            "what day is tomorrow" to "tomorrow",
            "what day is it tomorrow" to "tomorrow",
            "what day will it be in three days" to "in three days",
            "what day was yesterday" to "yesterday",
            "what will the date be in three days" to "in three days",
            // no sentence for it, but close enough
            "what was the date yesterday" to "yesterday",
            // made of day words, so GAR asks which day
            "what's the date this weekend" to "this weekend",
        )
        for ((input, day) in cases) {
            val (score, inputData) = dateSkill().score(FakeSkillContext().forInput(input), input)
            withClue(input) {
                // above the ranker's first-round threshold for high specificity skills
                (score.scoreIn01Range() > 0.85f) shouldBe true
                inputData shouldBe DateInput.Said(day)
            }
        }
    }

    "a capture that isn't a day doesn't match, so other questions don't become dates" {
        for (input in listOf(
            "what is the capital of france", "what's the weather", "what is the date of the election",
            "what's the time", "what day is christmas",
            // day words, but not only day words, and not a day GAR can read: the model decides
            "what's the date of the next full moon", "what's the date of the first moon landing",
            "what is the date of easter next year", "what day is the meeting next week",
            "what was the score last night", "what year did the war end",
        )) {
            val (score, _) = dateSkill().score(FakeSkillContext().forInput(input), input)
            withClue(input) { score shouldBe AlwaysWorstScore }
        }
        DateSkill.mentionsDay("on friday") shouldBe true
        DateSkill.mentionsDay("the 3rd") shouldBe true
        DateSkill.mentionsDay("in two weeks") shouldBe true
        DateSkill.mentionsDay("capital of france") shouldBe false

        DateSkill.onlyDayWords("this weekend") shouldBe true
        DateSkill.onlyDayWords("on the thirty second") shouldBe true
        DateSkill.onlyDayWords("the 3rd") shouldBe true
        DateSkill.onlyDayWords("of the next full moon") shouldBe false
        DateSkill.onlyDayWords("the score last night") shouldBe false
    }

    "the current time question stays with the current time skill" {
        val input = "what time is it"
        val (date, _) = dateSkill().score(FakeSkillContext().forInput(input), input)
        val (time, _) = Sentences.CurrentTime["en"]!!.score(FakeSkillContext().forInput(input), input)
        time.isBetterThan(date) shouldBe true
    }

    "a said day is resolved with a fixed now" {
        val cases = mapOf(
            "what's the date" to TODAY,
            "what day is it today" to TODAY,
            "what's the date tomorrow" to LocalDate.of(2026, 10, 26),
            "what is the date on friday" to LocalDate.of(2026, 10, 30),
            "what day will it be in three days" to LocalDate.of(2026, 10, 28),
            "what will the date be in three days" to LocalDate.of(2026, 10, 28),
            "what day was it three days ago" to LocalDate.of(2026, 10, 22),
            "what day was yesterday" to LocalDate.of(2026, 10, 24),
            "what day is the day after tomorrow" to LocalDate.of(2026, 10, 27),
            "what date is next sunday" to LocalDate.of(2026, 11, 1),
            "what day is the thirty first" to LocalDate.of(2026, 10, 31),
        )
        for ((input, expected) in cases) {
            withClue(input) {
                answer(input).shouldBeInstanceOf<DateOutput>().date shouldBe expected
            }
        }
    }

    "a day that can't be understood gets GAR's question, and the answer is used" {
        // "this weekend" is its saturday; which weekend "next weekend" is, GAR asks
        val ask = answer("what's the date next weekend").shouldBeInstanceOf<GarAskSlotOutput>()
        ask.question shouldBe GarQuestion.Value("next weekend")
        ask.call.tool shouldBe DateSkill.DATE_QUERY_TOOL

        val plan = ask.getInteractionPlan(FakeSkillContext())
            .shouldBeInstanceOf<InteractionPlan.StartSubInteraction>()
        @Suppress("UNCHECKED_CAST")
        val answerSkill = plan.nextSkills[0] as Skill<Any?>
        val ctx = FakeSkillContext()
        answerSkill.score(ctx, "banana").first shouldBe AlwaysWorstScore
        val (_, answer) = answerSkill.score(ctx, "friday")
        val output = answerSkill.generateOutput(ctx, answer).shouldBeInstanceOf<GarFollowUpOutput>()
        output.output.shouldBeInstanceOf<DateOutput>().date shouldBe LocalDate.of(2026, 10, 30)
    }

    "GAR's resolved slots become the same input" {
        val skill = dateSkill()
        skill.inputFromSlots(mapOf("day" to ResolvedDate(LocalDate.of(2026, 12, 25)))) shouldBe
                DateInput.Day(LocalDate.of(2026, 12, 25))
        skill.inputFromSlots(mapOf("day" to null)) shouldBe DateInput.Day(null)
        skill.inputFromSlots(mapOf()) shouldBe DateInput.Day(null)

        skill.generateOutput(FakeSkillContext(), DateInput.Day(null))
            .shouldBeInstanceOf<DateOutput>().date shouldBe TODAY
        skill.generateOutput(FakeSkillContext(), DateInput.Day(LocalDate.of(2026, 12, 25)))
            .shouldBeInstanceOf<DateOutput>().date shouldBe LocalDate.of(2026, 12, 25)
    }

    "without sentences the skill is only reachable through GAR" {
        val skill = DateSkill(DateInfo, null, now = { NOW.atZone(ZoneOffset.UTC) })
        skill.score(FakeSkillContext(), "what's the date").first shouldBe AlwaysWorstScore
    }

    "the answer's wording follows the day" {
        DateRelation.of(TODAY, TODAY) shouldBe DateRelation.TODAY
        DateRelation.of(TODAY.plusDays(1), TODAY) shouldBe DateRelation.TOMORROW
        DateRelation.of(TODAY.minusDays(1), TODAY) shouldBe DateRelation.YESTERDAY
        DateRelation.of(TODAY.plusDays(2), TODAY) shouldBe DateRelation.FUTURE
        DateRelation.of(TODAY.plusYears(1), TODAY) shouldBe DateRelation.FUTURE
        DateRelation.of(TODAY.minusDays(9), TODAY) shouldBe DateRelation.PAST
    }

    "dates are written in the locale's full form" {
        formatFullDate(TODAY, Locale.US) shouldBe "Sunday, October 25, 2026"
        formatFullDate(TODAY, Locale.ITALY) shouldBe "domenica 25 ottobre 2026"
    }
})
