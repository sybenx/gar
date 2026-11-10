package org.stypox.dicio.gar

import androidx.compose.runtime.Composable
import cc.aaro.gar.router.resolve.AmbiguityReason
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.ResolvedDate
import cc.aaro.gar.router.resolve.ResolvedDateTime
import cc.aaro.gar.router.resolve.ResolvedTime
import cc.aaro.gar.router.resolve.SlotResolvers
import cc.aaro.gar.router.tools.ConfirmText
import cc.aaro.gar.router.tools.SentenceMapping
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.eval.scoreAndWrapResult
import org.stypox.dicio.gar.call.GarAnswerSkill
import org.stypox.dicio.gar.call.GarAskSlotOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarCallRunner
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.call.DefaultGarSlotResolver
import org.stypox.dicio.gar.call.GarAnswer
import org.stypox.dicio.gar.call.GarCancelledOutput
import org.stypox.dicio.gar.call.GarConfirmOutput
import org.stypox.dicio.gar.call.GarConfirmSkill
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarQuestion
import org.stypox.dicio.gar.call.GarSlotResolver
import org.stypox.dicio.gar.call.GarRoutedSkill
import org.stypox.dicio.gar.call.GarSlotRewrites
import org.stypox.dicio.gar.call.GarToolSkill
import org.stypox.dicio.gar.call.spokenPhoneNumber
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.calculator.CalculatorOutput
import org.stypox.dicio.skills.calculator.CalculatorSkill
import org.stypox.dicio.skills.telephone.ConfirmCallOutput
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.telephone.TelephoneSkill
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

private fun routedSkill(
    target: Skill<*>,
    confirm: Boolean = false,
    confirmAllTools: Boolean = false,
    resolver: GarSlotResolver = fakeResolver,
) = GarRoutedSkill(
    targetInfo = target.correspondingSkillInfo,
    target = target,
    confirm = confirm,
    confirmAllTools = confirmAllTools,
    resolverFactory = { resolver },
    formatterFactory = { englishFormatter() },
)

private val CALCULATE_TOOL = ToolSpec(
    name = "calculate",
    skillId = "calculator",
    sentence = SentenceMapping.Fixed("calculate", listOf("calculation")),
    description = "Calculate the result of a math expression.",
    confirm = false,
    slots = listOf(SlotSpec("calculation", SlotKind.TEXT, true, "", captureType = "string")),
    examples = listOf(),
)

private val CALL_CONTACT_TOOL = ToolSpec(
    name = "call_contact",
    skillId = "telephone",
    sentence = SentenceMapping.Fixed("dial", listOf("who")),
    description = "Phone call a contact.",
    confirm = false,
    slots = listOf(SlotSpec("who", SlotKind.CONTACT, true, "", captureType = "string")),
    examples = listOf(),
)

private val OPEN_APP_TOOL = ToolSpec(
    name = "open_app",
    skillId = "open",
    sentence = SentenceMapping.Fixed("query", listOf("what")),
    description = "Open an app.",
    confirm = false,
    slots = listOf(SlotSpec("what", SlotKind.APP, true, "", captureType = "string")),
    examples = listOf(),
)

/** The number Dicio's calculator computed, as it shows it. */
private fun CalculatorOutput.result(): String? = CalculatorOutput::class.java
    .getDeclaredField("result").apply { isAccessible = true }.get(this) as String?

/** The number the telephone skill's confirmation would dial. */
private fun ConfirmCallOutput.number(): String = ConfirmCallOutput::class.java
    .getDeclaredField("number").apply { isAccessible = true }.get(this) as String

private fun telephoneSkill() = TelephoneSkill(
    TelephoneInfo,
    Sentences.Telephone["en"]!!,
    Sentences.UtilYesNo["en"]!!,
)

/** The skills GAR put on the stack, and whether they replace GAR's previous batch. */
private fun followUps(output: SkillOutput): Pair<List<Skill<*>>, Boolean> {
    return when (val plan = output.getInteractionPlan(FakeSkillContext())) {
        is InteractionPlan.StartSubInteraction -> {
            plan.reopenMicrophone.shouldBeTrue()
            Pair(plan.nextSkills, false)
        }
        is InteractionPlan.ReplaceSubInteraction -> {
            plan.reopenMicrophone.shouldBeTrue()
            Pair(plan.nextSkills, true)
        }
        else -> throw AssertionError("Unexpected plan $plan")
    }
}

private suspend fun answer(skill: Skill<*>, input: String): SkillOutput {
    val ctx = FakeSkillContext().forInput(input)
    val result = skill.scoreAndWrapResult(ctx, input)
    result.score shouldBeSameInstanceAs AlwaysBestScore
    return result.generateOutput(ctx)
}

/** The target's output, run from one of GAR's follow-up skills. */
private fun SkillOutput.fromFollowUp(): FakeOutput =
    shouldBeInstanceOf<GarFollowUpOutput>().output.shouldBeInstanceOf<FakeOutput>()

/** Changes the stack of batches like SkillEvaluator does after an output. */
private fun SkillRanker.apply(plan: InteractionPlan) {
    when (plan) {
        InteractionPlan.FinishInteraction -> removeAllBatches()
        is InteractionPlan.FinishSubInteraction -> removeTopBatch()
        is InteractionPlan.Continue -> {}
        is InteractionPlan.StartSubInteraction -> addBatchToTop(plan.nextSkills)
        is InteractionPlan.ReplaceSubInteraction -> {
            removeTopBatch()
            addBatchToTop(plan.nextSkills)
        }
    }
}

/** Evaluates [input] against the stack like SkillEvaluator, and applies the output's plan. */
private suspend fun SkillRanker.evaluate(input: String): SkillOutput? {
    val ctx = FakeSkillContext().forInput(input)
    val output = getBest(ctx, input)?.generateOutput(ctx) ?: return null
    apply(output.getInteractionPlan(ctx))
    return output
}

/** An LLM-only tool with one free-text slot, like calling a contact. */
private val CALL_TOOL = ToolSpec(
    name = "call",
    skillId = "call",
    sentence = null,
    description = "Call someone.",
    confirm = true,
    slots = listOf(SlotSpec("who", SlotKind.CONTACT, true, "Who")),
    examples = listOf(),
)

/** An output with the given [plan]. */
private class PlanOutput(private val plan: InteractionPlan) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = "done"
    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan = plan
    @Composable override fun GraphicalOutput(ctx: SkillContext) {}
}

/** A target that asks its own follow-up question, like the telephone skill's contact chooser. */
private class AskingTargetSkill(private val plan: InteractionPlan) :
    RecordingSkill(FakeSkillInfo("call")), GarToolSkill {
    override fun inputFromSlots(slots: Map<String, Any?>): Any? = slots["who"]

    override suspend fun generateOutput(ctx: SkillContext, inputData: Any?): SkillOutput {
        inputs.add(inputData)
        return PlanOutput(plan)
    }
}

class GarCallFlowTest : StringSpec({
    "a fully resolved call runs the target with its generated sentence data" {
        val target = RecordingSkill(FakeSkillInfo("timer"))
        val output = routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(TIMER_SET_TOOL, mapOf("duration" to "ten minutes", "name" to "pasta"), ""),
        )

        output.shouldBeInstanceOf<FakeOutput>()
        val input = target.inputs.single().shouldBeInstanceOf<Sentences.Timer.Set>()
        input.duration?.toJavaDuration() shouldBe Duration.ofMinutes(10)
        input.name shouldBe "pasta"
    }

    "an enum slot selects a capture-less sentence" {
        val target = RecordingSkill(FakeSkillInfo("flashlight"))
        routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(FLASHLIGHT_TOOL, mapOf("state" to "off"), ""),
        )
        target.inputs.single() shouldBeSameInstanceAs Sentences.Flashlight.TurnOff
    }

    "a missing required slot is asked for, and the answer continues the call" {
        val target = RecordingSkill(FakeSkillInfo("timer"))
        val ask = routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(TIMER_SET_TOOL, mapOf("name" to "pasta"), ""),
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        ask.slot.name shouldBe "duration"
        ask.question shouldBe GarQuestion.Value(null)
        target.inputs.shouldBeEmpty()

        val (skills, replaces) = followUps(ask)
        replaces.shouldBeFalse()
        skills shouldHaveSize 2
        val answerSkill = skills[0].shouldBeInstanceOf<GarAnswerSkill>()
        val cancelSkill = skills[1].shouldBeInstanceOf<GarCancelSkill>()
        // GAR's skills show the target skill
        answerSkill.correspondingSkillInfo shouldBeSameInstanceAs target.correspondingSkillInfo

        val ctx = FakeSkillContext()
        answerSkill.score(ctx, "ten minutes").first shouldBeSameInstanceAs AlwaysBestScore
        answerSkill.score(ctx, "banana").first shouldBeSameInstanceAs AlwaysWorstScore
        answerSkill.score(ctx, "never mind").first shouldBeSameInstanceAs AlwaysWorstScore
        cancelSkill.score(ctx, "never mind").first shouldBeSameInstanceAs AlwaysBestScore
        cancelSkill.score(ctx, "ten minutes").first shouldBeSameInstanceAs AlwaysWorstScore

        answer(answerSkill, "ten minutes").fromFollowUp()
        val input = target.inputs.single().shouldBeInstanceOf<Sentences.Timer.Set>()
        input.duration?.toJavaDuration() shouldBe Duration.ofMinutes(10)
        input.name shouldBe "pasta"
    }

    "an unresolvable slot is asked for again" {
        val target = RecordingSkill(FakeSkillInfo("timer"))
        val ask = routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(TIMER_SET_TOOL, mapOf("duration" to "a little while"), ""),
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        ask.question shouldBe GarQuestion.Value("a little while")
    }

    "cancel phrases end the call" {
        val target = RecordingSkill(FakeSkillInfo("timer"))
        val ask = routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(TIMER_SET_TOOL, mapOf(), ""),
        )
        val cancelSkill = followUps(ask).first[1]
        for (phrase in listOf("cancel", "Never mind.", "stop", "forget it", "OK cancel")) {
            answer(cancelSkill, phrase).shouldBeInstanceOf<GarCancelledOutput>()
        }
        target.inputs.shouldBeEmpty()
    }

    "am or pm is asked, and picked by word, position or a full answer" {
        val picks = mapOf(
            "pm" to SEVEN_PM,
            "P.M." to SEVEN_PM,
            "in the evening" to SEVEN_PM,
            "tonight" to SEVEN_PM,
            "am" to SEVEN_AM,
            "morning" to SEVEN_AM,
            "the first one" to SEVEN_AM,
            "second" to SEVEN_PM,
            "the last one" to SEVEN_PM,
            "seven pm" to SEVEN_PM,
            "7:00 AM" to SEVEN_AM,
        )
        for ((input, expected) in picks) {
            val target = RecordingToolSkill(FakeSkillInfo("alarm"))
            val ask = routedSkill(target).generateOutput(
                FakeSkillContext(),
                GarCall(ALARM_TOOL, mapOf("time" to "seven", "label" to "gym"), ""),
            ).shouldBeInstanceOf<GarAskSlotOutput>()
            ask.question.shouldBeInstanceOf<GarQuestion.Pick>()

            val answerSkill = followUps(ask).first[0]
            answerSkill.score(FakeSkillContext(), "banana").first shouldBeSameInstanceAs
                    AlwaysWorstScore
            answerSkill.score(FakeSkillContext(), "the third one").first shouldBeSameInstanceAs
                    AlwaysWorstScore

            answer(answerSkill, input).fromFollowUp()
            // LLM-only skills get their input from GarToolSkill.inputFromSlots
            target.inputs.single() shouldBe mapOf("time" to expected, "label" to "gym")
        }
    }

    "an answer that is itself ambiguous leads to a pick" {
        val target = RecordingToolSkill(FakeSkillInfo("alarm"))
        val ask = routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(ALARM_TOOL, mapOf("label" to "gym"), ""),
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        ask.question shouldBe GarQuestion.Value(null)

        val pick = answer(followUps(ask).first[0], "seven").shouldBeInstanceOf<GarAskSlotOutput>()
        pick.question.shouldBeInstanceOf<GarQuestion.Pick>()
        val (skills, replaces) = followUps(pick)
        replaces.shouldBeTrue()

        answer(skills[0], "pm").fromFollowUp()
        target.inputs.single() shouldBe mapOf("time" to SEVEN_PM, "label" to "gym")
    }

    "the next question after an answer replaces the previous one" {
        val target = RecordingToolSkill(FakeSkillInfo("alarm"))
        val ask = routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(ALARM_TOOL, mapOf(), ""),
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        ask.slot.name shouldBe "time"

        val next = answer(followUps(ask).first[0], "seven pm").shouldBeInstanceOf<GarAskSlotOutput>()
        next.slot.name shouldBe "label"
        val (skills, replaces) = followUps(next)
        replaces.shouldBeTrue()

        answer(skills[0], "gym").fromFollowUp()
        target.inputs.single() shouldBe mapOf("time" to SEVEN_PM, "label" to "gym")
    }

    "enum questions accept the value or its position" {
        val target = RecordingSkill(FakeSkillInfo("flashlight"))
        val ask = routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(FLASHLIGHT_TOOL, mapOf(), ""),
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        val answerSkill = followUps(ask).first[0]
        answerSkill.score(FakeSkillContext(), "sideways").first shouldBeSameInstanceAs
                AlwaysWorstScore

        answer(answerSkill, "the second one")
        target.inputs.single() shouldBeSameInstanceAs Sentences.Flashlight.TurnOff
    }

    "with confirmation on, a complete call is confirmed first" {
        val target = RecordingSkill(FakeSkillInfo("timer"))
        val confirm = routedSkill(target, confirm = true).generateOutput(
            FakeSkillContext(),
            GarCall(TIMER_SET_TOOL, mapOf("duration" to "ten minutes", "name" to "pasta"), ""),
        ).shouldBeInstanceOf<GarConfirmOutput>()
        target.inputs.shouldBeEmpty()

        confirm.runner.summary(confirm.call, speech = false) shouldBe
                "Start a countdown timer: ten minutes, pasta?"
    }

    "tools that don't need confirmation run right away" {
        val target = RecordingSkill(FakeSkillInfo("flashlight"))
        routedSkill(target, confirm = true).generateOutput(
            FakeSkillContext(),
            GarCall(FLASHLIGHT_TOOL, mapOf("state" to "on"), ""),
        ).shouldBeInstanceOf<FakeOutput>()
        target.inputs.single() shouldBeSameInstanceAs Sentences.Flashlight.TurnOn
    }

    "the confirm skill understands yes, no and the button labels" {
        val target = RecordingSkill(FakeSkillInfo("timer"))
        val runner: GarCallRunner = routedSkill(target, confirm = true)
            .newRunner(FakeSkillContext())
        val call = GarCall(
            TIMER_SET_TOOL,
            mapOf(),
            "",
            values = mapOf("duration" to Duration.ofMinutes(10), "name" to null),
        )
        var answered = 0
        val skill = GarConfirmSkill(
            runner = runner,
            call = call,
            data = Sentences.UtilYesNo["en"]!!,
            yesLabel = "Go ahead",
            noLabel = "Dismiss",
            onAnswered = { answered += 1 },
        )

        fun score(input: String) = skill.score(FakeSkillContext().forInput(input), input)

        score("yes").let {
            it.first.scoreIn01Range() shouldBeGreaterThan 0.85f
            it.second.shouldBeTrue()
        }
        score("no").let {
            it.first.scoreIn01Range() shouldBeGreaterThan 0.85f
            it.second.shouldBeFalse()
        }
        score("Go ahead") shouldBe Pair(AlwaysBestScore, true)
        score("dismiss") shouldBe Pair(AlwaysBestScore, false)
        score("what is the weather in rome").first.scoreIn01Range() shouldBeLessThan 0.7f

        skill.generateOutput(FakeSkillContext(), false).shouldBeInstanceOf<GarCancelledOutput>()
        target.inputs.shouldBeEmpty()

        skill.generateOutput(FakeSkillContext(), true).fromFollowUp()
        target.inputs.single().shouldBeInstanceOf<Sentences.Timer.Set>()
            .duration?.toJavaDuration() shouldBe Duration.ofMinutes(10)
        answered shouldBe 2
    }

    "after an answer, the target's own follow-up replaces GAR's skills on the stack" {
        // the target's chooser never matches here, like a name that isn't in the list
        val chooser = MockSkill(Specificity.HIGH, 0.0f)
        val target = AskingTargetSkill(
            InteractionPlan.StartSubInteraction(reopenMicrophone = true, nextSkills = listOf(chooser))
        )
        val ranker = SkillRanker(listOf(), MockSkill(Specificity.LOW, 0.0f))

        val ask = routedSkill(target).generateOutput(FakeSkillContext(), GarCall(CALL_TOOL, mapOf(), ""))
        ranker.apply(ask.shouldBeInstanceOf<GarAskSlotOutput>().getInteractionPlan(FakeSkillContext()))

        ranker.evaluate("sarah").shouldBeInstanceOf<GarFollowUpOutput>()
        target.inputs shouldBe listOf("sarah")
        ranker.hasAnyBatches().shouldBeTrue()
        // GAR's free-text answer skill is gone: it would accept this as another name
        ranker.evaluate("what time is it").shouldBeNull()
        target.inputs shouldBe listOf("sarah")
    }

    "after a confirmation, the target's own follow-up replaces GAR's skills on the stack" {
        val chooser = MockSkill(Specificity.HIGH, 0.0f)
        val target = AskingTargetSkill(
            InteractionPlan.StartSubInteraction(reopenMicrophone = true, nextSkills = listOf(chooser))
        )
        val ranker = SkillRanker(listOf(), MockSkill(Specificity.LOW, 0.0f))

        val confirm = routedSkill(target, confirm = true)
            .generateOutput(FakeSkillContext(), GarCall(CALL_TOOL, mapOf("who" to "sarah"), ""))
        // what GarConfirmOutput.getInteractionPlan does, without Android resources for the labels
        val confirmSkills = confirm.shouldBeInstanceOf<GarConfirmOutput>().nextSkills("Yes", "No")
        ranker.apply(InteractionPlan.StartSubInteraction(true, confirmSkills))

        ranker.evaluate("yes").shouldBeInstanceOf<GarFollowUpOutput>()
        target.inputs shouldBe listOf("sarah")
        // only the target's batch is left: "no" doesn't reach GAR's confirm skill anymore
        ranker.evaluate("no").shouldBeNull()
        ranker.hasAnyBatches().shouldBeTrue()
    }

    "plans of targets run from a follow-up drop GAR's batch" {
        val skills = listOf(MockSkill(Specificity.HIGH, 0.0f))
        fun mapped(plan: InteractionPlan) =
            GarFollowUpOutput(PlanOutput(plan)).getInteractionPlan(FakeSkillContext())

        mapped(InteractionPlan.StartSubInteraction(true, skills)) shouldBe
                InteractionPlan.ReplaceSubInteraction(true, skills)
        mapped(InteractionPlan.Continue(false)) shouldBe InteractionPlan.FinishSubInteraction(false)
        mapped(InteractionPlan.FinishInteraction) shouldBe InteractionPlan.FinishInteraction
        mapped(InteractionPlan.ReplaceSubInteraction(true, skills)) shouldBe
                InteractionPlan.ReplaceSubInteraction(true, skills)
    }

    "confirmation texts are filled in with the values as shown" {
        val target = RecordingSkill(FakeSkillInfo("timer"))
        val tool = TIMER_SET_TOOL.copy(
            confirmText = ConfirmText.Template("Set the {name} timer for {duration}?")
        )
        val runner = routedSkill(target, confirm = true).newRunner(FakeSkillContext())

        val named = GarCall(tool, mapOf(), "", mapOf("duration" to Duration.ofMinutes(10), "name" to "pasta"))
        runner.summary(named, speech = false) shouldBe "Set the pasta timer for ten minutes?"
        // a left-out optional slot leaves no double space
        val unnamed = named.copy(values = mapOf("duration" to Duration.ofMinutes(10), "name" to null))
        runner.summary(unnamed, speech = true) shouldBe "Set the timer for ten minutes?"

        val flashlight = FLASHLIGHT_TOOL.copy(
            confirmText = ConfirmText.ByValue(
                "state", mapOf("on" to "Turn on the flashlight?", "off" to "Turn off the flashlight?")
            )
        )
        runner.summary(GarCall(flashlight, mapOf(), "", mapOf("state" to "off")), speech = true) shouldBe
                "Turn off the flashlight?"

        GarCallRunner.fillTemplate("Cancel the {name} timer?") { null } shouldBe "Cancel the timer?"
        GarCallRunner.fillTemplate("Open {what}.") { "Spotify" } shouldBe "Open Spotify."
    }

    "the generic word timer is not a timer name" {
        for ((raw, name) in listOf(
            "timer" to "", "my timer" to "", "the timer" to "", "the pasta timer" to "pasta",
            "pasta" to "pasta", "tea timer" to "tea", "egg countdown" to "egg",
            "kitchen alarm" to "kitchen", "called pasta" to "pasta",
        )) {
            withClue(raw) { GarSlotRewrites.timerName(raw) shouldBe name }
        }

        val target = RecordingSkill(FakeSkillInfo("timer"))
        routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(TIMER_SET_TOOL, mapOf("duration" to "ten minutes", "name" to "my timer"), ""),
        )
        target.inputs.single().shouldBeInstanceOf<Sentences.Timer.Set>().name.shouldBeNull()
    }

    "calculations in prefix form are rewritten so that Dicio's calculator gets them right" {
        val cases = listOf(
            "twelve times seven" to "84",
            "divide one hundred by four" to "25",
            "multiply six by six" to "36",
            "square root of sixty four" to "8",
            "the square root of eighty one" to "9",
            "fifteen percent of eighty" to "12",
            "subtract three from ten" to "7",
            "add five and eight" to "13",
            "add two to three" to "5",
            "Divide 10 by 2" to "5",
            "take away five from ten" to "5",
            "multiply six and seven" to "42",
            // an "and" inside a number doesn't separate the operands
            "multiply one hundred and five by two" to "210",
            "add one hundred and five to ten" to "115",
        )
        for ((calculation, expected) in cases) {
            val target = CalculatorSkill(
                CalculatorInfo,
                Sentences.Calculator["en"]!!,
                Sentences.CalculatorOperators["en"]!!,
            )
            val output = routedSkill(target).generateOutput(
                FakeSkillContext(),
                GarCall(CALCULATE_TOOL, mapOf("calculation" to calculation), ""),
            )
            withClue(calculation) {
                output.shouldBeInstanceOf<CalculatorOutput>().result() shouldBe expected
            }
        }

        // also when the calculation is the answer to GAR's question
        val target = CalculatorSkill(
            CalculatorInfo,
            Sentences.Calculator["en"]!!,
            Sentences.CalculatorOperators["en"]!!,
        )
        val ask = routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(CALCULATE_TOOL, mapOf(), ""),
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        answer(followUps(ask).first[0], "divide ten by four")
            .shouldBeInstanceOf<GarFollowUpOutput>().output
            .shouldBeInstanceOf<CalculatorOutput>().result() shouldBe "2.5"
    }

    "a spelled-out phone number is dialed with the telephone skill's confirmation" {
        spokenPhoneNumber("five five five one two one two") shouldBe "5551212"
        spokenPhoneNumber("nine one one") shouldBe "911"
        spokenPhoneNumber("plus four four seven 700") shouldBe "+447700"
        spokenPhoneNumber("oh one two three") shouldBe "0123"
        spokenPhoneNumber("mom").shouldBeNull()
        spokenPhoneNumber("john five").shouldBeNull()
        spokenPhoneNumber("one two").shouldBeNull()

        val target = TelephoneSkill(
            TelephoneInfo,
            Sentences.Telephone["en"]!!,
            Sentences.UtilYesNo["en"]!!,
        )
        // the telephone skill itself would look the digits up as a contact name (and needs a
        // ContentResolver, which FakeSkillContext doesn't have)
        routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(CALL_CONTACT_TOOL, mapOf("who" to "five five five one two one two"), ""),
        ).shouldBeInstanceOf<ConfirmCallOutput>()
    }

    "with confirmAllTools even tools without confirmation are confirmed" {
        val target = RecordingSkill(FakeSkillInfo("flashlight"))
        routedSkill(target, confirm = true, confirmAllTools = true).generateOutput(
            FakeSkillContext(),
            GarCall(FLASHLIGHT_TOOL, mapOf("state" to "on"), ""),
        ).shouldBeInstanceOf<GarConfirmOutput>()
        target.inputs.shouldBeEmpty()

        // the setting still turns all confirmations off
        routedSkill(target, confirm = false, confirmAllTools = true).generateOutput(
            FakeSkillContext(),
            GarCall(FLASHLIGHT_TOOL, mapOf("state" to "on"), ""),
        ).shouldBeInstanceOf<FakeOutput>()
    }

    "app names resolve to the label of an installed app" {
        val resolver = DefaultGarSlotResolver(
            SlotResolvers(ENGLISH_PARSER_FORMATTER, "en"),
            readAppLabels = { listOf("Spotify", "YouTube", "Camera", "Camera Pro", "Clock") },
        )
        val app = SlotSpec("what", SlotKind.APP, true, "")
        resolver.resolve(app, "spot if i") shouldBe Resolution.Resolved("Spotify")
        resolver.resolve(app, "you tube") shouldBe Resolution.Resolved("YouTube")
        resolver.resolve(app, "the weather channel").shouldBeInstanceOf<Resolution.Unresolvable>()
        resolver.resolve(app, "camera").let {
            if (it is Resolution.Ambiguous) {
                it.reason shouldBe AmbiguityReason.SEVERAL_MATCHES
                it.options.first() shouldBe "Camera"
            } else {
                it shouldBe Resolution.Resolved("Camera")
            }
        }

        // no launcher apps known: the name stays as said
        DefaultGarSlotResolver(SlotResolvers(ENGLISH_PARSER_FORMATTER, "en"))
            .resolve(app, "spot if i") shouldBe Resolution.Resolved("spot if i")
    }

    "a call that needs no follow-up returns the target's output as is" {
        val target = RecordingSkill(FakeSkillInfo("flashlight"))
        routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(FLASHLIGHT_TOOL, mapOf("state" to "on"), ""),
        ).shouldBeInstanceOf<FakeOutput>()
    }

    "a skill without sentences that can't take slots fails loudly" {
        // GarCatalogTest checks that the catalog's LLM-only tools have skills that can
        val e = shouldThrow<IllegalStateException> {
            routedSkill(RecordingSkill(FakeSkillInfo("alarm"))).generateOutput(
                FakeSkillContext(),
                GarCall(ALARM_TOOL, mapOf("time" to "seven pm", "label" to "gym"), ""),
            )
        }
        e.message shouldBe "alarm_set has no sentence, so skill alarm must implement GarToolSkill"
    }

    "a number answered to the contact question is dialed from the follow-up" {
        val ask = routedSkill(telephoneSkill()).generateOutput(
            FakeSkillContext(),
            GarCall(CALL_CONTACT_TOOL, mapOf(), ""),
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        ask.slot.name shouldBe "who"

        answer(followUps(ask).first[0], "nine one one")
            .shouldBeInstanceOf<GarFollowUpOutput>().output
            .shouldBeInstanceOf<ConfirmCallOutput>().number() shouldBe "911"
    }

    "a typed international number keeps its plus" {
        spokenPhoneNumber("+44 7700 900123") shouldBe "+447700900123"
        spokenPhoneNumber(" +1 (555) 010-0123") shouldBe "+15550100123"
        spokenPhoneNumber("+ plus four four seven 700").shouldBeNull()
        routedSkill(telephoneSkill()).generateOutput(
            FakeSkillContext(),
            GarCall(CALL_CONTACT_TOOL, mapOf("who" to "+44 7700 900123"), ""),
        ).shouldBeInstanceOf<ConfirmCallOutput>().number() shouldBe "+447700900123"
    }

    "a month without its day asks which day, and the answer is a day of that month" {
        // Sunday 2026-10-25 at noon
        val now = LocalDateTime.of(2026, 10, 25, 12, 0)
        val resolver = DefaultGarSlotResolver(
            SlotResolvers(ENGLISH_PARSER_FORMATTER, "en") { now.atZone(ZoneOffset.UTC) }
        )
        val remind = ToolSpec(
            name = "remind",
            skillId = "remind",
            sentence = null,
            description = "Remind.",
            confirm = false,
            slots = listOf(SlotSpec("when", SlotKind.DATETIME, true, "When")),
            examples = listOf(),
        )
        val day = remind.copy(name = "day", slots = listOf(SlotSpec("day", SlotKind.DATE, false, "Day")))
        suspend fun ask(tool: ToolSpec, raw: String) =
            routedSkill(RecordingToolSkill(FakeSkillInfo("remind")), resolver = resolver).generateOutput(
                FakeSkillContext(),
                GarCall(tool, mapOf(tool.slots[0].name to raw), ""),
            ).shouldBeInstanceOf<GarAskSlotOutput>()
        fun GarAskSlotOutput.interpret(input: String) = runner.interpret(input, call.tool, slot, question)
        fun december(day: Int, hour: Int = 12, timeSpoken: Boolean = false) = ResolvedDateTime(
            LocalDateTime.of(2026, 12, day, hour, 0), dateSpoken = true, timeSpoken = timeSpoken,
        )

        val ask = ask(remind, "december")
        ask.question.shouldBeInstanceOf<GarQuestion.Pick>().reason shouldBe AmbiguityReason.DAY_OF_MONTH
        for ((input, expected) in listOf(
            "the fifth" to december(5),
            "fifth" to december(5),
            "5" to december(5),
            "the 5th" to december(5),
            "five" to december(5),
            "the twenty first" to december(21),
            "december twelfth" to december(12),
            // the answer's own time
            "the fifth at nine pm" to december(5, 21, timeSpoken = true),
        )) {
            withClue(input) { ask.interpret(input) shouldBe GarAnswer.Value(expected) }
        }
        // am or pm is still asked about, on the day said
        ask.interpret("the fifth at seven").shouldBeInstanceOf<GarAnswer.NeedsPick>().let {
            it.reason shouldBe AmbiguityReason.AM_OR_PM
            it.options shouldBe listOf(december(5, 7, true), december(5, 19, true))
        }
        // another month asks again, a complete new answer is taken, other words aren't answers
        ask.interpret("january").shouldBeInstanceOf<GarAnswer.NeedsPick>().reason shouldBe
            AmbiguityReason.DAY_OF_MONTH
        ask.interpret("tomorrow") shouldBe GarAnswer.Value(
            ResolvedDateTime(LocalDateTime.of(2026, 10, 26, 12, 0), dateSpoken = true, timeSpoken = false)
        )
        ask.interpret("banana").shouldBeNull()

        // the time said with the month stays, also when it was am or pm
        ask(remind, "december at nine am").interpret("the fifth") shouldBe GarAnswer.Value(december(5, 9, true))
        ask(remind, "in december at seven").interpret("the fifth").shouldBeInstanceOf<GarAnswer.NeedsPick>()
            .options shouldBe listOf(december(5, 7, true), december(5, 19, true))
        // a month of another year
        ask(remind, "december 2027").interpret("the fifth") shouldBe GarAnswer.Value(
            ResolvedDateTime(LocalDateTime.of(2027, 12, 5, 12, 0), dateSpoken = true, timeSpoken = false)
        )
        // a date slot
        ask(day, "next month").interpret("the third") shouldBe
            GarAnswer.Value(ResolvedDate(LocalDate.of(2026, 11, 3)))

        // this month, asked on the 25th: a passed day or time answers nothing (the question
        // stays), it is never taken in November
        val october = ask(remind, "october")
        october.question.shouldBeInstanceOf<GarQuestion.Pick>().reason shouldBe AmbiguityReason.DAY_OF_MONTH
        october.interpret("the fifth").shouldBeNull()
        october.interpret("the twenty fifth at nine am").shouldBeNull()
        october.interpret("the thirtieth") shouldBe GarAnswer.Value(
            ResolvedDateTime(LocalDateTime.of(2026, 10, 30, 12, 0), dateSpoken = true, timeSpoken = false)
        )
        october.interpret("the twenty fifth at nine pm") shouldBe GarAnswer.Value(
            ResolvedDateTime(LocalDateTime.of(2026, 10, 25, 21, 0), dateSpoken = true, timeSpoken = true)
        )
        val octoberDay = ask(day, "in october")
        octoberDay.interpret("the fifth").shouldBeNull()
        octoberDay.interpret("the thirtieth") shouldBe GarAnswer.Value(ResolvedDate(LocalDate.of(2026, 10, 30)))
    }

    "an optional slot that doesn't resolve is asked about, not left out" {
        val tool = ALARM_TOOL.copy(
            name = "remind",
            slots = listOf(SlotSpec("time", SlotKind.TIME, false, "When")),
        )
        val ask = routedSkill(RecordingToolSkill(FakeSkillInfo("alarm"))).generateOutput(
            FakeSkillContext(),
            GarCall(tool, mapOf("time" to "whenever"), ""),
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        ask.slot.name shouldBe "time"
        ask.question shouldBe GarQuestion.Value("whenever")
    }

    "a required slot that rewrites to nothing is asked for" {
        val tool = TIMER_SET_TOOL.copy(
            slots = listOf(
                TIMER_SET_TOOL.slots[0],
                SlotSpec("name", SlotKind.TEXT, true, "Name", captureType = "string"),
            )
        )
        val ask = routedSkill(RecordingSkill(FakeSkillInfo("timer"))).generateOutput(
            FakeSkillContext(),
            GarCall(tool, mapOf("duration" to "ten minutes", "name" to "my timer"), ""),
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        ask.slot.name shouldBe "name"
        ask.question shouldBe GarQuestion.Value(null)
    }

    "raw slots the tool doesn't have are ignored, and values given already win" {
        val target = RecordingSkill(FakeSkillInfo("timer"))
        routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(
                TIMER_SET_TOOL,
                mapOf("duration" to "banana", "extra" to "x"),
                "",
                values = mapOf("duration" to Duration.ofMinutes(10)),
            ),
        ).shouldBeInstanceOf<FakeOutput>()
        val input = target.inputs.single().shouldBeInstanceOf<Sentences.Timer.Set>()
        input.duration?.toJavaDuration() shouldBe Duration.ofMinutes(10)
        input.name.shouldBeNull()
    }

    "an empty answer, or one of only punctuation, answers nothing" {
        // a free-text question, which would take any other answer
        val ask = routedSkill(RecordingToolSkill(FakeSkillInfo("call"))).generateOutput(
            FakeSkillContext(),
            GarCall(CALL_TOOL, mapOf(), ""),
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        val answerSkill = followUps(ask).first[0]
        for (input in listOf("", "...", " ?! ")) {
            withClue(input) {
                answerSkill.score(FakeSkillContext(), input).first shouldBeSameInstanceAs AlwaysWorstScore
            }
        }
        answerSkill.score(FakeSkillContext(), "sarah").first shouldBeSameInstanceAs AlwaysBestScore
    }

    "an app name that matches several apps is picked by name or position, not by a new name" {
        val severalMatches = mapOf(
            "camera" to listOf("Camera", "Camera Pro"),
            "last fm" to listOf("Last.fm", "Last.fm Scrobbler"),
            "first bank" to listOf("First Bank", "First Bank Business"),
        )
        val apps = GarSlotResolver { slot, raw ->
            val options = severalMatches[raw]
            if (slot.kind == SlotKind.APP && options != null) {
                Resolution.Ambiguous(options, AmbiguityReason.SEVERAL_MATCHES)
            } else {
                fakeResolver.resolve(slot, raw)
            }
        }
        // the app said, then each answer and the app it picks
        val picks = mapOf(
            "camera" to mapOf(
                "camera pro" to "Camera Pro", "Camera Pro!" to "Camera Pro",
                "the second one" to "Camera Pro", "camera" to "Camera", "first" to "Camera",
                // any text is a valid app name: it doesn't pick, it asks again
                "spotify" to null, "the third one" to null,
            ),
            // a name with a position word in it picks by the name
            "last fm" to mapOf("last fm" to "Last.fm", "the last one" to "Last.fm Scrobbler"),
            "first bank" to mapOf(
                "first bank business" to "First Bank Business", "first bank" to "First Bank",
                "the first one" to "First Bank",
            ),
        )
        for ((said, answers) in picks) {
            for ((input, expected) in answers) {
                val target = RecordingSkill(FakeSkillInfo("open"))
                val ask = routedSkill(target, resolver = apps).generateOutput(
                    FakeSkillContext(),
                    GarCall(OPEN_APP_TOOL, mapOf("what" to said), ""),
                ).shouldBeInstanceOf<GarAskSlotOutput>()
                ask.question shouldBe
                    GarQuestion.Pick(severalMatches.getValue(said), AmbiguityReason.SEVERAL_MATCHES)

                val answerSkill = followUps(ask).first[0]
                withClue("$said: $input") {
                    if (expected == null) {
                        answerSkill.score(FakeSkillContext(), input).first shouldBeSameInstanceAs
                            AlwaysWorstScore
                    } else {
                        answer(answerSkill, input).fromFollowUp()
                        target.inputs.single() shouldBe Sentences.Open.Query(expected)
                    }
                }
            }
        }
    }

    "a time is said as the card shows it: eleven forty five a.m., never quarter to twelve" {
        val formatter = englishFormatter()
        val quarterTo = ResolvedTime(LocalTime.of(11, 45))
        formatter.format(quarterTo, speech = false) shouldBe "11:45 AM"
        formatter.format(quarterTo, speech = true) shouldBe "eleven forty five a.m."
        formatter.format(ResolvedTime(LocalTime.of(14, 30)), speech = true) shouldBe "two thirty p.m."
        formatter.format(ResolvedTime(LocalTime.of(6, 15)), speech = true) shouldBe "six fifteen a.m."
        formatter.format(ResolvedTime(LocalTime.of(23, 45)), speech = true) shouldBe "eleven forty five p.m."
        formatter.format(ResolvedTime(LocalTime.of(6, 5)), speech = true) shouldBe "six oh five a.m."
    }

    "an am/pm question is also answered with the time as GAR says it" {
        // neither a position nor am/pm alone, and fakeResolver doesn't know it: only the spoken
        // form of the option matches
        englishFormatter().format(SEVEN_AM, speech = true) shouldBe "seven a.m."
        val target = RecordingToolSkill(FakeSkillInfo("alarm"))
        val ask = routedSkill(target).generateOutput(
            FakeSkillContext(),
            GarCall(ALARM_TOOL, mapOf("time" to "seven", "label" to "gym"), ""),
        )
        answer(followUps(ask).first[0], "Seven a.m.").fromFollowUp()
        target.inputs.single() shouldBe mapOf("time" to SEVEN_AM, "label" to "gym")

        // am/pm needs options with an hour
        val runner = routedSkill(target).newRunner(FakeSkillContext())
        runner.interpret(
            "pm", ALARM_TOOL, ALARM_TOOL.slots[0],
            GarQuestion.Pick(listOf("a", "b"), AmbiguityReason.AM_OR_PM),
        ).shouldBeNull()
    }

    "every option of an enum question can be picked by position" {
        // the stopwatch's six actions, numbered one to six on the card
        val actions = listOf("start", "stop", "resume", "reset", "lap", "elapsed")
        val tool = ToolSpec(
            name = "stopwatch",
            skillId = "stopwatch",
            sentence = null,
            description = "Control the stopwatch.",
            confirm = false,
            slots = listOf(SlotSpec("action", SlotKind.ENUM, true, "", enumValues = actions)),
            examples = listOf(),
        )
        for ((input, expected) in listOf(
            "the sixth one" to "elapsed", "six" to "elapsed", "number 6" to "elapsed",
            "last" to "elapsed", "the second to last" to "lap", "fifth" to "lap",
        )) {
            val target = RecordingToolSkill(FakeSkillInfo("stopwatch"))
            val ask = routedSkill(target).generateOutput(FakeSkillContext(), GarCall(tool, mapOf(), ""))
            withClue(input) {
                answer(followUps(ask).first[0], input).fromFollowUp()
                target.inputs.single() shouldBe mapOf("action" to expected)
            }
        }
    }

    "a confirmation by value without a text for the value says the description" {
        val runner = routedSkill(RecordingSkill(FakeSkillInfo("flashlight")), confirm = true)
            .newRunner(FakeSkillContext())
        val flashlight = FLASHLIGHT_TOOL.copy(
            confirmText = ConfirmText.ByValue("state", mapOf("on" to "Turn on the flashlight?"))
        )
        runner.summary(GarCall(flashlight, mapOf(), "", mapOf("state" to null)), speech = true) shouldBe
            "Turn the flashlight on or off?"
    }

    "values are put into confirmation texts literally" {
        // not as a replacement string, where $1 and backslashes have a meaning
        GarCallRunner.fillTemplate("Text {to}?") { "\$1 mom" } shouldBe "Text \$1 mom?"
        GarCallRunner.fillTemplate("Text {to}: {message}") { if (it == "to") "a\\b" else "$0" } shouldBe
            "Text a\\b: $0"
    }
})
