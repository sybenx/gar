package org.stypox.dicio.gar

import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.resolve.AmbiguityReason
import cc.aaro.gar.router.resolve.SlotResolvers
import cc.aaro.gar.router.tools.ToolCatalog
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.FloatScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.gar.call.DefaultGarSlotResolver
import org.stypox.dicio.gar.call.GarAnswerSkill
import org.stypox.dicio.gar.call.GarAskSlotOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.call.GarOpenAnswerSkill
import org.stypox.dicio.gar.call.GarQuestion
import org.stypox.dicio.gar.call.GarRoutedSkill
import org.stypox.dicio.gar.conversation.GarConversationSkill
import org.stypox.dicio.gar.conversation.GarFollowUps
import org.stypox.dicio.gar.conversation.GarOpenAnswer
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.gar.tools.GarToolsProvider
import org.stypox.dicio.gar.tools.OfferedTool
import org.stypox.dicio.sentences.Sentences.Conversation
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.conversation.ConversationInfo
import org.stypox.dicio.skills.conversation.ConversationMatch
import org.stypox.dicio.skills.current_time.CurrentTimeInfo
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.timer.TimerWords
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

private val catalog = ToolCatalog.load(
    File("src/main/gar/tools.yml").readText(),
    File("src/main/sentences/skill_definitions.yml").readText(),
)

private fun tool(name: String): ToolSpec = catalog.tools.single { it.name == name }

/** Sunday 2026-10-25 at noon, so that "december" is a month without its day. */
private val resolver = DefaultGarSlotResolver(
    SlotResolvers(ENGLISH_PARSER_FORMATTER, "en") {
        LocalDateTime.of(2026, 10, 25, 12, 0).atZone(ZoneOffset.UTC)
    }
)

/** A model that calls no tool (as for the score of a game) and records what it got. */
private class AbstainingRouter : GarRouterSource {
    val routed = mutableListOf<String>()

    override fun isModelAvailable(settings: GarSettings) = true

    override suspend fun route(settings: GarSettings, utterance: String, tools: List<ToolSpec>) =
        RouterOutput.NoMatch.also { routed.add(utterance) }
}

/**
 * The evaluator's matching step on the skills of a new install, with GAR's match check of the
 * real catalog and a model that calls no tool.
 */
private class Dialog(val fallbackSkill: MockSkill = MockSkill(Specificity.LOW, 0.0f)) {
    val ranker = SkillRanker(
        GarOnlineSkills.forRanking(allEnglishSkills(), UserSettingsSerializer.defaultValue.enabledSkillsMap),
        fallbackSkill,
    )
    val router = AbstainingRouter()
    private val fallback = GarFallbackImpl(
        settings = GarSettingsModule.newDataStoreForPreviews(),
        toolsProvider = object : GarToolsProvider {
            override suspend fun offeredTools(ctx: SkillContext) = listOf(
                OfferedTool(TIMER_SET_TOOL, FakeSkillInfo("timer"), RecordingSkill(FakeSkillInfo("timer")))
            )
            override fun allTools() = catalog.tools
        },
        routerSource = router,
        logger = { _, _ -> },
    )

    /** GAR asks about a slot of [toolName]'s call with [raw], and listens for the answer. */
    suspend fun ask(toolName: String, raw: Map<String, String>): GarAskSlotOutput {
        val tool = tool(toolName)
        val target = RecordingToolSkill(FakeSkillInfo(tool.skillId))
        val ask = GarRoutedSkill(
            targetInfo = target.correspondingSkillInfo,
            target = target,
            confirm = false,
            resolverFactory = { resolver },
            formatterFactory = { englishFormatter() },
        ).generateOutput(FakeSkillContext(), GarCall(tool, raw, "")).shouldBeInstanceOf<GarAskSlotOutput>()
        val plan = ask.getInteractionPlan(FakeSkillContext())
        ranker.addBatchToTop(plan.shouldBeInstanceOf<InteractionPlan.StartSubInteraction>().nextSkills)
        return ask
    }

    suspend fun match(input: String): SkillWithResult<*> =
        garMatchUtterances(FakeSkillContext(), ranker, fallback, listOf(input)).second
}

/** For each input, whether a new conversation asked by [ask] takes it as the answer. */
private suspend fun answers(ask: suspend Dialog.() -> Unit, inputs: List<String>) {
    for (input in inputs) {
        withClue(input) {
            val conversation = Dialog().apply { ask() }
            conversation.match(input).skill.shouldBeInstanceOf<GarAnswerSkill>()
            conversation.ranker.hasAnyBatches().shouldBeTrue()
            conversation.router.routed.shouldBeEmpty()
        }
    }
}

/** For each input and skill id, whether that skill takes it, and whether the question stays. */
private suspend fun commands(
    ask: suspend Dialog.() -> Unit,
    expected: List<Pair<String, String>>,
    questionStays: Boolean = false,
) {
    for ((input, skillId) in expected) {
        withClue(input) {
            val conversation = Dialog().apply { ask() }
            conversation.match(input).skill.correspondingSkillInfo.id shouldBe skillId
            conversation.ranker.hasAnyBatches() shouldBe questionStays
        }
    }
}

private val whichDay: suspend Dialog.() -> Unit = {
    ask("reminder_add", mapOf("what" to "pay rent", "when" to "december")).question
        .shouldBeInstanceOf<GarQuestion.Pick>().reason shouldBe AmbiguityReason.DAY_OF_MONTH
}
private val whom: suspend Dialog.() -> Unit = {
    ask("call_contact", mapOf()).slot.name shouldBe "who"
}
private val where: suspend Dialog.() -> Unit = {
    ask("navigate", mapOf()).slot.name shouldBe "where"
}
private val remindWhat: suspend Dialog.() -> Unit = {
    ask("reminder_add", mapOf("when" to "tomorrow at five pm")).slot.name shouldBe "what"
}

/** A pending answer that takes anything, and whether it yields to a command. */
private class OpenAnswerSkill(private val yields: Boolean) :
    Skill<String>(FakeSkillInfo("answer"), Specificity.HIGH), GarOpenAnswer {
    override fun score(ctx: SkillContext, input: String): Pair<Score, String> =
        Pair(AlwaysBestScore, "answer")

    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput =
        FakeOutput(inputData)

    override fun yieldsTo(command: SkillWithResult<*>): Boolean = yields
}

/** A command of the default batch that scores [score], and keeps a question if [keeps]. */
private class CommandSkill(private val score: Float, private val keeps: Boolean = false) :
    Skill<String>(FakeSkillInfo("command"), Specificity.HIGH), GarConversationSkill {
    var scored = 0

    override fun score(ctx: SkillContext, input: String): Pair<Score, String> {
        scored += 1
        return Pair(FloatScore(score), "command")
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput =
        FakeOutput(inputData)

    override fun keepsQuestion(inputData: Any?): Boolean = keeps
}

private fun rankerWith(command: CommandSkill, answer: Skill<*>) =
    SkillRanker(listOf(command), MockSkill(Specificity.LOW, 0.0f)).apply { addBatchToTop(listOf(answer)) }

class GarConversationTest : StringSpec({
    "a new command lets go of a pending question, but its answers are taken" {
        // the emulator: "Which day in December?", then "what is the score of the 49ers game".
        // The calculator matched it, GAR's check rejected that (it would have said 980), and the
        // model called no tool: the text fallback asked to repeat and the question stayed, so
        // the next commands went to it. A rejected match is a new command too.
        val conversation = Dialog().apply { whichDay() }
        val score = conversation.match("what is the score of the 49ers game")
        score.skill shouldBeSameInstanceAs conversation.fallbackSkill
        conversation.router.routed shouldBe listOf("what is the score of the 49ers game")
        conversation.ranker.hasAnyBatches().shouldBeFalse()

        answers(whichDay, listOf(
            "the fifth", "fifth", "the 5th", "5", "december twelfth", "the fifth at nine pm",
        ))
        // a command that matches is a new command, as upstream
        commands(whichDay, listOf(
            "what time is it" to CurrentTimeInfo.id, "what is 5 plus 3" to CalculatorInfo.id,
        ))
        // what no skill matched may be a misheard answer: the model gets it, the question stays
        Dialog().apply { whichDay() }.let {
            it.match("purple monkey dishwasher").skill shouldBeSameInstanceAs it.fallbackSkill
            it.router.routed shouldBe listOf("purple monkey dishwasher")
            it.ranker.hasAnyBatches().shouldBeTrue()
        }
    }

    "the conversation's commands while GAR asks" {
        commands(
            whichDay,
            listOf("repeat that" to ConversationInfo.id, "what did you say" to ConversationInfo.id),
            questionStays = true,
        )
        commands(whichDay, listOf("be quiet" to ConversationInfo.id, "that's all" to ConversationInfo.id))
        // GAR's own cancel comes first, and says "OK."
        Dialog().apply { whichDay() }.match("never mind").skill.shouldBeInstanceOf<GarCancelSkill>()
    }

    "an answer that takes any words gives way to a command when it names a thing" {
        Dialog().apply { whom() }.ranker.let { ranker ->
            (ranker.getBest(FakeSkillContext().forInput("mom"), "mom")?.skill)
                .shouldBeInstanceOf<GarOpenAnswerSkill>()
        }
        answers(whom, listOf("mom", "Alex Herbert", "the pizza place", "dad's cell"))
        commands(whom, listOf(
            "call mom" to TelephoneInfo.id,
            "what time is it" to CurrentTimeInfo.id,
            "be quiet" to ConversationInfo.id,
        ))
        commands(whom, listOf("repeat that" to ConversationInfo.id), questionStays = true)
        // a command for the model: the question lets go, and the model gets it
        Dialog().apply { whom() }.let {
            it.match("what is the score of the 49ers game").skill shouldBeSameInstanceAs it.fallbackSkill
            it.router.routed shouldBe listOf("what is the score of the 49ers game")
            it.ranker.hasAnyBatches().shouldBeFalse()
        }

        answers(where, listOf("the airport", "home", "the nearest gas station"))
        commands(where, listOf("what time is it" to CurrentTimeInfo.id))
    }

    "a reminder's text takes commands as said, except the conversation's" {
        answers(remindWhat, listOf("call mom", "what time is it", "take out the trash", "set a timer"))
        commands(remindWhat, listOf("be quiet" to ConversationInfo.id, "that's all" to ConversationInfo.id))
        commands(remindWhat, listOf("repeat that" to ConversationInfo.id), questionStays = true)
        Dialog().apply { remindWhat() }.match("never mind").skill.shouldBeInstanceOf<GarCancelSkill>()
    }

    "while a timer or an alarm rings, GAR's questions leave the words that stop it" {
        try {
            GarRinging.register("test_ringing") { true }
            val conversation = Dialog().apply { remindWhat() }
            val ranker = conversation.ranker
            for (input in listOf("stop", "shut up", "be quiet")) {
                withClue(input) {
                    val best = ranker.getBest(FakeSkillContext().forInput(input), input)
                    (best?.skill is GarAnswerSkill || best?.skill is GarCancelSkill).shouldBeFalse()
                }
            }
            // "never mind" isn't for what rings
            ranker.getBest(FakeSkillContext().forInput("never mind"), "never mind")?.skill
                .shouldBeInstanceOf<GarCancelSkill>()

            // nor what the skill of what rings takes: a ringing timer's "turn it off" is no
            // reminder text, while "call mom" still is
            GarRinging.registerCommands("test_ringing") { _, input ->
                TimerWords.whileRinging(input, ringing = true, names = listOf("pasta")) != null
            }
            for (input in listOf("turn it off", "dismiss", "snooze", "ok thanks")) {
                withClue(input) {
                    val best = Dialog().apply { remindWhat() }.ranker
                        .getBest(FakeSkillContext().forInput(input), input)
                    (best?.skill is GarAnswerSkill).shouldBeFalse()
                }
            }
            answers(remindWhat, listOf("call mom"))
        } finally {
            GarRinging.unregister("test_ringing")
        }
        GarCancelSkill(FakeSkillInfo("any")).score(FakeSkillContext(), "stop").first shouldBeSameInstanceAs
            AlwaysBestScore
    }

    "only an open answer gives way, to a whole sentence it yields to" {
        val ctx = FakeSkillContext()
        fun best(ranker: SkillRanker, accept: Boolean = true) = ranker.getBest(ctx, "input") { accept }

        // an answer that doesn't take any words: the default batch isn't even scored
        val notOpen = CommandSkill(1.0f)
        val closed = rankerWith(notOpen, FakeAnswer)
        best(closed)?.skill shouldBeSameInstanceAs FakeAnswer
        notOpen.scored shouldBe 0
        closed.hasAnyBatches().shouldBeTrue()

        // a whole sentence wins and drops the question
        val exact = CommandSkill(1.0f)
        val dropped = rankerWith(exact, OpenAnswerSkill(yields = true))
        best(dropped)?.skill shouldBeSameInstanceAs exact
        dropped.hasAnyBatches().shouldBeFalse()

        // a word left out (or added), or not one the answer yields to: the answer
        best(rankerWith(CommandSkill(0.9f), OpenAnswerSkill(yields = true)))?.skill
            .shouldBeInstanceOf<OpenAnswerSkill>()
        best(rankerWith(CommandSkill(1.0f), OpenAnswerSkill(yields = false)))?.skill
            .shouldBeInstanceOf<OpenAnswerSkill>()

        // a command the match check rejects is no match: the question stays for the other
        // alternatives (garMatchUtterances then lets it go)
        val rejected = rankerWith(CommandSkill(1.0f), OpenAnswerSkill(yields = true))
        best(rejected, accept = false).shouldBeNull()
        rejected.hasAnyBatches().shouldBeTrue()

        // "repeat that" keeps the question, also over an open answer
        for (answer in listOf(OpenAnswerSkill(yields = true), MockSkill(Specificity.HIGH, 0.0f))) {
            val keeping = rankerWith(CommandSkill(1.0f, keeps = true), answer)
            best(keeping)?.skill.shouldBeInstanceOf<CommandSkill>()
            keeping.hasAnyBatches().shouldBeTrue()
        }

        GarFollowUps.keepsBatches(SkillWithResult(CommandSkill(1.0f, keeps = true), AlwaysBestScore, "x"))
            .shouldBeTrue()
        GarFollowUps.keepsBatches(SkillWithResult(FakeAnswer, AlwaysBestScore, null)).shouldBeFalse()
    }

    "the conversation skill keeps a question only for repeat" {
        val skill = ConversationInfo.build(FakeSkillContext()).shouldBeInstanceOf<GarConversationSkill>()
        skill.keepsQuestion(ConversationMatch(Conversation.Repeat)).shouldBeTrue()
        skill.keepsQuestion(ConversationMatch(Conversation.Stop)).shouldBeFalse()
    }

    "GAR's cancel answer still takes its phrases when nothing rings" {
        val cancel = GarCancelSkill(FakeSkillInfo("any"))
        for (input in listOf("stop", "never mind", "cancel")) {
            withClue(input) {
                cancel.score(FakeSkillContext(), input).first shouldBeSameInstanceAs AlwaysBestScore
            }
        }
        cancel.score(FakeSkillContext(), "be quiet").first shouldBeSameInstanceAs AlwaysWorstScore
    }
})

/** A pending answer that takes everything, but isn't open. */
private object FakeAnswer : Skill<Nothing?>(FakeSkillInfo("answer"), Specificity.HIGH) {
    override fun score(ctx: SkillContext, input: String): Pair<Score, Nothing?> =
        Pair(AlwaysBestScore, null)

    override suspend fun generateOutput(ctx: SkillContext, inputData: Nothing?): SkillOutput =
        FakeOutput(inputData)
}
