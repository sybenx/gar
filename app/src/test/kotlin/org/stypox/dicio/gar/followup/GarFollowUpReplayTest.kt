package org.stypox.dicio.gar.followup

import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.tools.ToolCatalog
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.GarFallbackImpl
import org.stypox.dicio.gar.GarRouterSource
import org.stypox.dicio.gar.TEST_TODAY
import org.stypox.dicio.gar.allEnglishSkills
import org.stypox.dicio.gar.conversation.GarTurnElement
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.gar.garMatchUtterances
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.gar.tools.GarToolsProvider
import org.stypox.dicio.gar.tools.OfferedTool
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import org.stypox.dicio.skills.alarm.AlarmOutput
import org.stypox.dicio.skills.alarm.AlarmSentenceMatch
import org.stypox.dicio.skills.calls.PhoneCallState
import org.stypox.dicio.skills.contact_info.ContactField
import org.stypox.dicio.skills.contact_info.ContactInfoOutput
import org.stypox.dicio.skills.conversation.ConversationInfo
import org.stypox.dicio.skills.joke.JokeInfo
import org.stypox.dicio.skills.telephone.ConfirmCallOutput
import org.stypox.dicio.skills.telephone.ConfirmedCallOutput
import org.stypox.dicio.skills.random.RandomRequest
import org.stypox.dicio.skills.weather.WeatherRequest
import org.stypox.dicio.skills.weather.WeatherWhen
import cc.aaro.gar.router.resolve.ResolvedTime
import java.io.File
import java.time.LocalTime

private val catalogTools = ToolCatalog.load(
    File("src/main/gar/tools.yml").readText(),
    File("src/main/sentences/skill_definitions.yml").readText(),
).tools

/** A model that calls no tool, and records what it got. */
internal class SilentRouter : GarRouterSource {
    val routed = mutableListOf<String>()

    override fun isModelAvailable(settings: GarSettings) = true

    override suspend fun route(settings: GarSettings, utterance: String, tools: List<ToolSpec>) =
        RouterOutput.NoMatch.also { routed.add(utterance) }
}

/**
 * The evaluator's matching as Aaron's phone does it, with the follow-up skill first and the
 * follow-ups' memory bracketing each turn like SkillEvaluator: outputs are given by the test
 * ([say]'s `output`), since the real skills would reach the phone.
 */
internal class FollowUpReplayPhone(
    var locked: Boolean = false,
    var media: Boolean = false,
    var ringing: Boolean = false,
    /** Replaces a real skill, such as the sms skill with contacts in memory. */
    swap: (Skill<*>) -> Skill<*> = { it },
) {
    val skills: List<Skill<*>> = GarOnlineSkills.forRanking(
        listOf<Skill<*>>(
            GarFollowUpSkill(
                GarFollowUpInfo,
                isLocked = { locked },
                mediaPlaying = { media },
                callState = { PhoneCallState.IDLE },
                ringingTakes = { _, _ -> ringing },
                now = { FOLLOW_UP_NOW },
            ),
        ) + allEnglishSkills().map(swap),
        UserSettingsSerializer.defaultValue.enabledSkillsMap,
    )
    val ranker = SkillRanker(skills, MockSkill(Specificity.LOW, 0.0f))
    val router = SilentRouter()
    private val fallback = GarFallbackImpl(
        settings = GarSettingsModule.newDataStoreForPreviews(),
        toolsProvider = object : GarToolsProvider {
            override suspend fun offeredTools(ctx: SkillContext) = listOf<OfferedTool>()
            override fun allTools() = catalogTools
        },
        routerSource = router,
        logger = { _, _ -> },
    )

    /** The evaluator's match for [input] in [element]'s turn, with nothing remembered of it yet. */
    suspend fun match(input: String, element: GarTurnElement = turnElement(locked = locked)): Pair<String, SkillWithResult<*>> =
        withContext(element) { garMatchUtterances(FakeSkillContext(), ranker, fallback, listOf(input)) }

    /**
     * Says [input]: its match, the answer [output] makes of it (the follow-up skill's own answers
     * are real), whose follow-up skills then listen, and the memory of the turn.
     */
    suspend fun say(
        input: String,
        continued: Boolean = false,
        output: suspend (SkillWithResult<*>) -> SkillOutput = { FakeOutput(it.inputData) },
    ): Turn {
        val element = turnElement(locked = locked, continued = continued)
        val (chosen, match) = match(input, element)
        val token = GarConversationMemory.begin(element, continuesStep = ranker.hasAnyBatches())
        val answer = if (match.skill is GarFollowUpStep) match.generateOutput(FakeSkillContext()) else output(match)
        val plan = show(answer)
        GarConversationMemory.finish(
            token,
            GarAnswered(match.skill.correspondingSkillInfo.id, chosen, match.inputData, element.call, answer, plan),
            locked,
        )
        return Turn(chosen, match, answer)
    }

    private fun show(output: SkillOutput): InteractionPlan {
        val plan = when (output) {
            is GarFollowUpConfirmOutput -> InteractionPlan.StartSubInteraction(
                true, output.nextSkills(Sentences.UtilYesNo["en"], "Yes", "No"),
            )
            else -> output.getInteractionPlan(FakeSkillContext())
        }
        when (plan) {
            InteractionPlan.FinishInteraction -> ranker.removeAllBatches()
            is InteractionPlan.FinishSubInteraction -> ranker.removeTopBatch()
            is InteractionPlan.Continue -> {}
            is InteractionPlan.StartSubInteraction -> ranker.addBatchToTop(plan.nextSkills)
            is InteractionPlan.ReplaceSubInteraction -> {
                ranker.removeTopBatch()
                ranker.addBatchToTop(plan.nextSkills)
            }
        }
        return plan
    }

    class Turn(val input: String, val match: SkillWithResult<*>, val output: SkillOutput) {
        val skill: String get() = match.skill.correspondingSkillInfo.id
        val inputData: Any? get() = match.inputData
    }
}

private fun alarmSet(hour: Int): (SkillWithResult<*>) -> SkillOutput = {
    AlarmOutput.Set(ResolvedTime(LocalTime.of(hour, 0)), null, null, TEST_TODAY, englishFormatter())
}

private fun alarmSentence(turn: FollowUpReplayPhone.Turn) =
    turn.inputData.shouldBeInstanceOf<AlarmSentenceMatch>().sentence

class GarFollowUpReplayTest : StringSpec({
    afterTest { org.stypox.dicio.gar.followup.adapters.AnswerFollowUps.today = { java.time.LocalDate.now() } }

    "the field case: turn it back off after the flashlight, and wifi, dark mode, do not disturb" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("turn on the flashlight").skill shouldBe "flashlight"
        phone.say("turn it back off").let {
            it.input shouldBe "turn it back off"
            it.skill shouldBe "flashlight"
            it.inputData shouldBe Sentences.Flashlight.TurnOff
        }
        phone.say("turn on wifi").skill shouldBe "connectivity"
        phone.say("turn it off").inputData shouldBe Sentences.Connectivity.WifiOff
        phone.say("turn on do not disturb").skill shouldBe "do_not_disturb"
        phone.say("turn it off").inputData shouldBe Sentences.DoNotDisturb.TurnOff
        phone.say("turn on dark mode").skill shouldBe "device_settings"
        phone.say("turn it off").skill shouldBe "device_settings"
        // the rewrites never reached the model
        phone.router.routed shouldBe listOf()
    }

    "the weather, then what about tomorrow" {
        resetMemory()
        org.stypox.dicio.gar.followup.adapters.AnswerFollowUps.today = { java.time.LocalDate.now() }
        val phone = FollowUpReplayPhone()
        phone.say("what's the weather in denver").skill shouldBe "weather"
        phone.say("what about tomorrow").let {
            it.skill shouldBe "weather"
            val request = it.inputData.shouldBeInstanceOf<WeatherRequest>()
            request.place shouldBe "denver"
            request.time.shouldBeInstanceOf<WeatherWhen.Day>()
        }
    }

    "an alarm: change it, cancel it within a minute, the conversation after" {
        val clock = resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("set an alarm for 7 am", output = alarmSet(7)).skill shouldBe "alarm"
        alarmSentence(phone.say("change it to 8")).shouldBeInstanceOf<Sentences.Alarm.Change>()
        resetMemory(clock)
        phone.say("set an alarm for 7 am", output = alarmSet(7))
        alarmSentence(phone.say("cancel it")).shouldBeInstanceOf<Sentences.Alarm.Cancel>()
        phone.say("set an alarm for 7 am", output = alarmSet(7))
        clock.advance(61_000L)
        phone.say("cancel it").skill shouldBe ConversationInfo.id
        phone.say("set an alarm for 7 pm", output = alarmSet(19))
        alarmSentence(phone.say("no, 8 pm")).shouldBeInstanceOf<Sentences.Alarm.Change>()
    }

    "the alarm isn't the newest turn: stop, or what to turn off" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("set an alarm for 7 am", output = alarmSet(7))
        phone.say("what time is it")
        phone.say("cancel it").skill shouldBe ConversationInfo.id
        resetMemory()
        phone.say("set an alarm for 7 am", output = alarmSet(7))
        phone.say("tell me a joke").skill shouldBe JokeInfo.id
        phone.say("turn it off").output.shouldBeInstanceOf<GarFollowUpAskOutput>().question shouldBe
            GarFollowUpQuestion.TURN_WHAT_OFF
        phone.say("the flashlight").inputData shouldBe Sentences.Flashlight.TurnOff
        // the question is gone: a new follow-up
        phone.say("turn it on").inputData shouldBe Sentences.Flashlight.TurnOn
    }

    "call her after what's erin's number" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("what's erin's number") {
            ContactInfoOutput.Found("Erin", ContactField.NUMBER, listOf(), own = false)
        }.skill shouldBe "contact_info"
        phone.say("call her").let {
            it.input shouldBe "call her"
            it.skill shouldBe "telephone"
        }
    }

    "an unanswered Call Mom? isn't the person of call him back" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        val yesNo = Sentences.UtilYesNo["en"]!!
        phone.say("call mom") { ConfirmCallOutput("Mom", "555 0100", yesNo) }.skill shouldBe "telephone"
        // the microphone timed out; later, from the default batch
        phone.say("call him back") { ConfirmCallOutput("Erin", "555 0101", yesNo) }.skill shouldBe "call_log"
        phone.say("yes") { ConfirmedCallOutput("555 0101") }.skill shouldBe "telephone"
        GarConversationMemory.view(false).newest!!.turn.facts.person!!.name shouldBe "Erin"
        phone.say("text her").let {
            it.skill shouldBe "sms"
            it.input shouldBe "text her"
        }
        GarFollowUpSkill.scored?.second?.outcome shouldBe GarFollowUpOutcome.Rewritten("send a text to erin")
    }

    "a message read out isn't forwarded" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("read my messages").skill shouldBe "messages"
        val (_, match) = phone.match("send it to mom")
        (match.skill is GarFollowUpStep) shouldBe false
        // the follow-up skill read the form and left it: incoming bodies are no facts
        GarFollowUpSkill.scored?.second?.outcome shouldBe GarFollowUpOutcome.Deferred("reach")
    }

    "another one" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("tell me a joke")
        phone.say("another one").skill shouldBe JokeInfo.id
        phone.say("roll 2 dice").inputData shouldBe RandomRequest.Dice(2, 6)
        phone.say("again").inputData shouldBe RandomRequest.Dice(2, 6)
    }

    "while something rings, six minutes later, on the lock screen" {
        val clock = resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("turn on the flashlight")
        phone.ringing = true
        phone.match("turn it off").second.skill shouldNotBe phone.skills.first()
        phone.ringing = false
        phone.locked = true
        phone.say("turn it off").output.shouldBeInstanceOf<GarFollowUpAskOutput>()
        phone.locked = false
        phone.ranker.removeAllBatches()
        phone.say("turn on the flashlight")
        clock.advance(6 * 60_000L)
        phone.say("turn it off").output.shouldBeInstanceOf<GarFollowUpAskOutput>()
    }

    "a continued turn: cancel it and delete it ask first" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("set an alarm for 7 am", output = alarmSet(7))
        phone.say("cancel it", continued = true).output.shouldBeInstanceOf<GarFollowUpConfirmOutput>()
        phone.say("no").output.shouldBeInstanceOf<org.stypox.dicio.gar.call.GarCancelledOutput>()
        resetMemory()
        phone.say("set an alarm for 7 am", output = alarmSet(7))
        phone.say("delete it", continued = true).output.shouldBeInstanceOf<GarFollowUpConfirmOutput>()
        alarmSentence(phone.say("yes")).shouldBeInstanceOf<Sentences.Alarm.Cancel>()
    }
})
