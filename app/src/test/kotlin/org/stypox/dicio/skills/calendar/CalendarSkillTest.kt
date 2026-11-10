package org.stypox.dicio.skills.calendar

import cc.aaro.gar.router.resolve.AmbiguityReason
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.ResolvedDate
import cc.aaro.gar.router.resolve.ResolvedDateTime
import cc.aaro.gar.router.resolve.ResolvedTime
import cc.aaro.gar.router.resolve.SlotResolvers
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarAnswer
import org.stypox.dicio.gar.call.GarAskSlotOutput
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.GarSlotRewrites
import org.stypox.dicio.gar.call.DefaultGarSlotResolver
import org.stypox.dicio.gar.call.GarQuestion
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Calendar
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

// Sunday noon
private val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 25, 12, 0)

private fun skill(locked: Boolean = false) = CalendarSkill(
    CalendarInfo,
    Sentences.Calendar["en"]!!,
    Sentences.UtilYesNo["en"]!!,
    now = { NOW.atZone(ZoneOffset.UTC) },
    formatterFactory = { englishFormatter() },
    isDeviceLocked = { locked },
)

/** The skill's score and the sentence it matched, captures trimmed (they keep their spaces). */
private fun match(input: String): Pair<Float, Calendar> {
    val (score, data) = skill().score(FakeSkillContext().forInput(input), input)
    val trimmed = when (val sentence = data as Calendar) {
        is Calendar.Create -> Calendar.Create(
            sentence.title?.trim(), sentence.begin?.trim(), sentence.end?.trim(), sentence.duration?.trim(),
        )
        is Calendar.Query -> Calendar.Query(sentence.`when`?.trim())
        is Calendar.Reminder -> Calendar.Reminder(sentence.what?.trim(), sentence.`when`?.trim())
    }
    return Pair(score.scoreIn01Range(), trimmed)
}

class CalendarSkillTest : StringSpec({
    "the fast path's sentences" {
        val cases = mapOf(
            "add dentist tomorrow at three pm to my calendar" to
                    Calendar.Create("dentist tomorrow at three pm", null, null, null),
            "put lunch with sam on my calendar friday at noon" to
                    Calendar.Create("lunch with sam", "friday at noon", null, null),
            "schedule a meeting with john on friday at two" to
                    Calendar.Create("a meeting with john on friday at two", null, null, null),
            "add an event called team lunch tomorrow at noon" to
                    Calendar.Create("team lunch tomorrow at noon", null, null, null),
            "block two hours for gym tomorrow at five" to
                    Calendar.Create("gym tomorrow at five", null, null, "two hours"),
            "what's on my calendar today" to Calendar.Query("today"),
            "what's on my calendar" to Calendar.Query(null),
            "what events do i have tomorrow" to Calendar.Query("tomorrow"),
            "am i busy on friday" to Calendar.Query("friday"),
            "do i have any meetings tomorrow" to Calendar.Query("tomorrow"),
            "remind me to call mom at five pm" to Calendar.Reminder("call mom at five pm", null),
            "remind me in ten minutes to check the oven" to
                    Calendar.Reminder("check the oven", "in ten minutes"),
            "set a reminder to buy milk" to Calendar.Reminder("buy milk", null),
            "set a reminder for tomorrow morning to take out the trash" to
                    Calendar.Reminder("take out the trash", "tomorrow morning"),
            "set a reminder in ten minutes to check the oven" to
                    Calendar.Reminder("check the oven", "in ten minutes"),
            "set a reminder to call mom about the party" to
                    Calendar.Reminder("call mom about the party", null),
            "remind me to call mom about the party" to
                    Calendar.Reminder("call mom about the party", null),
        )
        for ((input, expected) in cases) {
            withClue(input) {
                val (score, data) = match(input)
                data shouldBe expected
                score shouldBeGreaterThan 0.85f
            }
        }
    }

    "other skills' commands don't look like calendar commands" {
        for (input in listOf(
            "add five and eight", "set a timer for ten minutes", "what time is it", "call mom",
            "what's the weather like tomorrow", "open the calendar app",
            "set a timer for ten minutes to check the pasta", "set an alarm for seven am",
            "set the volume to fifty percent", "make a call to mom",
        )) {
            withClue(input) { match(input).first shouldBeLessThan 0.7f }
        }
    }

    "a reminder without a time asks when" {
        val ask = skill().generateOutput(FakeSkillContext(), Calendar.Reminder("call mom", null))
            .shouldBeInstanceOf<GarAskSlotOutput>()
        ask.slot.name shouldBe "when"
        ask.question shouldBe GarQuestion.Value(null)
    }

    "an unclear am or pm is asked" {
        val ask = skill().generateOutput(FakeSkillContext(), Calendar.Reminder("call mom at five", null))
            .shouldBeInstanceOf<GarAskSlotOutput>()
        ask.slot.name shouldBe "when"
        ask.question.shouldBeInstanceOf<GarQuestion.Pick>().reason shouldBe AmbiguityReason.AM_OR_PM
    }

    "a reminder with a day but no time asks what time, on both paths" {
        val fast = skill().generateOutput(FakeSkillContext(), Calendar.Reminder("call mom tomorrow", null))
            .shouldBeInstanceOf<GarAskSlotOutput>()
        fast.slot.name shouldBe "time"

        val tomorrow = ResolvedDateTime(LocalDate.of(2026, 10, 26).atTime(12, 0), dateSpoken = true, timeSpoken = false)
        val model = skill().generateOutput(
            FakeSkillContext(),
            skill().inputFromSlots(mapOf("what" to "call mom", "when" to tomorrow)),
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        model.slot.name shouldBe "time"
        model.call.values["what"] shouldBe "Call mom"
    }

    "a reminder at a part of day asks what time, and the part of day tells am from pm" {
        for ((input, answer, expected) in listOf(
            Triple("take my pills tonight", "nine", LocalTime.of(21, 0)),
            Triple("take my pills tonight", "eleven thirty", LocalTime.of(23, 30)),
            Triple("call the doctor tomorrow morning", "seven", LocalTime.of(7, 0)),
            Triple("call the doctor tomorrow morning", "seven pm", LocalTime.of(19, 0)),
            Triple("water the plants this evening", "six", LocalTime.of(18, 0)),
        )) {
            withClue(input) {
                val ask = skill().generateOutput(FakeSkillContext(), Calendar.Reminder(input, null))
                    .shouldBeInstanceOf<GarAskSlotOutput>()
                ask.slot.name shouldBe "time"
                ask.question shouldBe GarQuestion.Value(null)
                ask.runner.interpret(answer, ask.call.tool, ask.slot, ask.question) shouldBe
                    GarAnswer.Value(ResolvedTime(expected))
            }
        }

        // "twelve" after "tonight" is midnight; noon and pm stay noon
        val tonightAsk = skill().generateOutput(FakeSkillContext(), Calendar.Reminder("take my pills tonight", null))
            .shouldBeInstanceOf<GarAskSlotOutput>()
        for ((answer, expected) in listOf(
            "twelve" to LocalTime.MIDNIGHT, "twelve thirty" to LocalTime.of(0, 30),
            "noon" to LocalTime.NOON, "twelve pm" to LocalTime.NOON, "twelve noon" to LocalTime.NOON,
        )) {
            withClue(answer) {
                tonightAsk.runner.interpret(answer, tonightAsk.call.tool, tonightAsk.slot, tonightAsk.question) shouldBe
                    GarAnswer.Value(ResolvedTime(expected))
            }
        }
        // a time after midnight is the next day's ("tonight", "one" was refused as past before):
        // it reaches the calendar provider, which the fake context doesn't have
        val (_, oneAm) = (tonightAsk.getInteractionPlan(FakeSkillContext()) as InteractionPlan.StartSubInteraction)
            .nextSkills[0].score(FakeSkillContext(), "one")
        shouldThrow<NotImplementedError> {
            @Suppress("UNCHECKED_CAST")
            ((tonightAsk.getInteractionPlan(FakeSkillContext()) as InteractionPlan.StartSubInteraction)
                .nextSkills[0] as Skill<Any?>).generateOutput(FakeSkillContext(), oneAm)
        }

        // where the asked time falls: NOW is Sunday noon
        val r = SlotResolvers(ENGLISH_PARSER_FORMATTER, "en") { NOW.atZone(ZoneOffset.UTC) }
        fun said(raw: String, resolvers: SlotResolvers = r) =
            resolvers.dateTime(raw).shouldBeInstanceOf<Resolution.Resolved<ResolvedDateTime>>().value
        val today = NOW.toLocalDate()
        for ((partOfDay, time, expected) in listOf(
            Triple("tonight", LocalTime.of(21, 0), today.atTime(21, 0)),
            Triple("tonight", LocalTime.of(1, 0), today.plusDays(1).atTime(1, 0)),
            Triple("tonight", LocalTime.MIDNIGHT, today.plusDays(1).atStartOfDay()),
            Triple("this afternoon", LocalTime.MIDNIGHT, today.plusDays(1).atStartOfDay()),
            Triple("this evening", LocalTime.of(5, 0), today.plusDays(1).atTime(5, 0)), // after midnight
            Triple("tomorrow morning", LocalTime.of(7, 0), today.plusDays(1).atTime(7, 0)),
            Triple("tomorrow morning", LocalTime.MIDNIGHT, today.plusDays(1).atStartOfDay()),
            Triple("tomorrow night", LocalTime.of(1, 0), today.plusDays(2).atTime(1, 0)),
            Triple("tomorrow night", LocalTime.of(23, 0), today.plusDays(1).atTime(23, 0)),
        )) {
            withClue("$partOfDay $time") {
                reminderAfterPartOfDay(said(partOfDay), time, NOW) shouldBe expected
            }
        }
        // "tonight" said at 11:30 pm: the resolver's 9 pm has passed, but the user means tonight
        val late = NOW.withHour(23).withMinute(30)
        val lateTonight = said("tonight", SlotResolvers(ENGLISH_PARSER_FORMATTER, "en") { late.atZone(ZoneOffset.UTC) })
        reminderAfterPartOfDay(lateTonight, LocalTime.of(23, 45), late) shouldBe today.atTime(23, 45)
        reminderAfterPartOfDay(lateTonight, LocalTime.of(1, 0), late) shouldBe today.plusDays(1).atTime(1, 0)
        reminderAfterPartOfDay(lateTonight, LocalTime.of(21, 0), late) shouldBe today.plusDays(1).atTime(21, 0)

        // the model's calls too
        val tonight = ResolvedDateTime(
            NOW.withHour(21), dateSpoken = false, timeSpoken = true, approximateTime = true,
        )
        val model = skill().generateOutput(
            FakeSkillContext(),
            skill().inputFromSlots(mapOf("what" to "take my pills", "when" to tonight)),
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        model.slot.name shouldBe "time"
        model.call.values["day"] shouldBe ResolvedDate(NOW.toLocalDate())
    }

    "a month without its day asks which day, for reminders and events" {
        for (sentence in listOf(
            Calendar.Reminder("call mom", "in december"),
            Calendar.Create("vacation", "in january", null, null),
            Calendar.Create("vacation next month", null, null, null),
        )) {
            withClue(sentence) {
                val ask = skill().generateOutput(FakeSkillContext(), sentence).shouldBeInstanceOf<GarAskSlotOutput>()
                ask.slot.name shouldBe "when"
                ask.question.shouldBeInstanceOf<GarQuestion.Pick>().reason shouldBe AmbiguityReason.DAY_OF_MONTH
            }
        }
        // the calendar shows one day: a month alone is asked about too
        val query = skill().generateOutput(FakeSkillContext(), Calendar.Query("in december"))
            .shouldBeInstanceOf<GarAskSlotOutput>()
        query.slot.name shouldBe "day"
        query.question.shouldBeInstanceOf<GarQuestion.Pick>().reason shouldBe AmbiguityReason.DAY_OF_MONTH
        query.runner.interpret("the fifth", query.call.tool, query.slot, query.question) shouldBe
            GarAnswer.Value(ResolvedDate(LocalDate.of(2026, 12, 5)))
    }

    "a part of day reads the events of its day, without a question" {
        val resolver = DefaultGarSlotResolver(SlotResolvers(ENGLISH_PARSER_FORMATTER, "en") { NOW.atZone(ZoneOffset.UTC) })
        val day = CalendarSkill.QUERY_TOOL.slots.single()
        for ((said, expected) in listOf(
            "tonight" to null,
            "this afternoon" to null,
            "tomorrow morning" to LocalDate.of(2026, 10, 26),
            "friday night" to LocalDate.of(2026, 10, 30),
            "tomorrow at three pm" to LocalDate.of(2026, 10, 26),
        )) {
            withClue(said) {
                val raw = GarSlotRewrites.rewrite(CalendarSkill.QUERY_TOOL, day, said)
                if (expected == null) {
                    raw shouldBe "" // no day: today's events
                } else {
                    resolver.resolve(day, raw) shouldBe Resolution.Resolved(ResolvedDate(expected))
                }
                // reading the calendar, which isn't there in these tests, means nothing was asked
                shouldThrow<NotImplementedError> {
                    skill().generateOutput(FakeSkillContext(), Calendar.Query(said))
                }
            }
        }
    }

    "a reminder in the past is refused" {
        skill().generateOutput(FakeSkillContext(), Calendar.Reminder("call mom", "at nine am today")) shouldBe
                CalendarOutput.PastTime
    }

    "in the hour the clock goes back, a reminder's time has passed as a moment" {
        // the first 1:50 am (MDT, UTC-6) of the night the clock goes back in Denver
        val firstTime = ZonedDateTime.ofLocal(
            LocalDateTime.of(2026, 11, 1, 1, 50), ZoneId.of("America/Denver"), ZoneOffset.ofHours(-6),
        )
        val skill = CalendarSkill(
            CalendarInfo,
            Sentences.Calendar["en"]!!,
            Sentences.UtilYesNo["en"]!!,
            now = { firstTime },
            formatterFactory = { englishFormatter() },
            isDeviceLocked = { false },
        )
        // in twenty minutes is the second 1:10 am (MST), which is later: adding it reaches the
        // calendar provider, which the fake context doesn't have
        shouldThrow<NotImplementedError> {
            skill.generateOutput(FakeSkillContext(), Calendar.Reminder("call mom", "in twenty minutes"))
        }
        // the first 1:00 am has passed
        skill.generateOutput(FakeSkillContext(), Calendar.Reminder("call mom", "at one am today")) shouldBe
                CalendarOutput.PastTime
    }

    "an event without a title or a time asks for it" {
        skill().generateOutput(FakeSkillContext(), Calendar.Create("the office party", null, null, null))
            .shouldBeInstanceOf<GarAskSlotOutput>().slot.name shouldBe "when"
        skill().generateOutput(FakeSkillContext(), Calendar.Create("tomorrow at three pm", null, null, null))
            .shouldBeInstanceOf<GarAskSlotOutput>().slot.name shouldBe "title"
    }

    "Dicio's sentences don't read or add events on a locked phone" {
        for (sentence in listOf(
            Calendar.Query("today"),
            Calendar.Create("dentist tomorrow at three pm", null, null, null),
            Calendar.Reminder("call mom", "in ten minutes"),
        )) {
            withClue(sentence) {
                skill(locked = true).generateOutput(FakeSkillContext(), sentence)
                    .shouldBeInstanceOf<GarLockedOutput>()
            }
        }
    }

    "the confirmation card knows it's the latest answer, also after one of GAR's questions" {
        val card = FakeOutput(null)
        isAnswer(card, card) shouldBe true
        // "remind me to call mom", "When?", "at five pm": the card comes through GAR's runner
        isAnswer(GarFollowUpOutput(card), card) shouldBe true
        isAnswer(FakeOutput(null), card) shouldBe false
        isAnswer(null, card) shouldBe false
    }

    "the model's calls build the same typed requests" {
        val tomorrow3pm = ResolvedDateTime(LocalDateTime.of(2026, 10, 26, 15, 0), dateSpoken = true, timeSpoken = true)
        skill().inputFromSlots(mapOf("title" to "dentist", "when" to tomorrow3pm, "duration" to null)) shouldBe
                CalendarRequest.AddEvent("Dentist", tomorrow3pm, null, null, confirm = false)
        skill().inputFromSlots(mapOf("day" to null)) shouldBe CalendarRequest.Query(null)
    }
})
