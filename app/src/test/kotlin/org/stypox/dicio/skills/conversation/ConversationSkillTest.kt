package org.stypox.dicio.skills.conversation

import androidx.compose.runtime.Composable
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.allEnglishSkills
import org.stypox.dicio.gar.conversation.GarContinuedListening
import org.stypox.dicio.gar.conversation.GarEndsConversation
import org.stypox.dicio.gar.conversation.GarFollowUps
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Conversation
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import org.stypox.dicio.skills.alarm.AlarmInfo
import org.stypox.dicio.skills.alarm.AlarmOutput
import org.stypox.dicio.skills.alarm.FiringAlarmCheck
import org.stypox.dicio.skills.media.MediaInfo
import org.stypox.dicio.skills.stopwatch.StopwatchInfo
import org.stypox.dicio.skills.timer.TimerInfo

private val STOP_INPUTS = listOf(
    "stop", "Stop.", "stop it", "stop that", "stop talking", "stop speaking", "OK stop",
    "okay, stop please", "can you stop", "could you stop talking please", "stop now", "shut up",
    "be quiet", "quiet", "keep quiet please", "silence", "hush", "enough", "that's enough",
    "cancel", "cancel that", "cancel it", "never mind", "Nevermind.", "no never mind",
    "sorry, never mind", "forget it", "forget about it", "that's all", "that's it", "thats all",
    "that is all", "Thanks, that's it.", "thank you that's all", "that's all thanks",
    "ok thanks that's it", "that'll be all", "that will be all", "I'm done", "we're done",
    "all done", "just stop", "uh stop", "alright, stop",
    // no thanks, to "What can I do for you?"
    "no thanks", "no thank you", "nope", "nothing", "nothing, thanks", "I'm good", "no, I'm good",
    "not now", "not right now", "nah",
)

private val REPEAT_INPUTS = listOf(
    "repeat that", "Repeat that, please.", "repeat it", "repeat it again", "repeat yourself",
    "say that again", "say it again", "can you say that again", "could you repeat that",
    "what did you say", "what did you just say", "Sorry, what did you say?", "what was that",
    "come again", "pardon", "pardon me", "I didn't hear you", "i did not catch that",
    "I didn't get that", "I missed that", "say again", "say that one more time",
    "what was that again", "can you repeat", "could you repeat please", "repeat what you said",
    "repeat what you just said", "I couldn't hear you", "i could not hear that", "I can't hear you",
    "i cannot hear you",
)

/** Stop phrases said with a word twice or more fillers: the skill's, without a whole sentence. */
private val LOOSE_STOP_INPUTS = listOf("stop stop", "cancel cancel", "ok please stop", "no no stop")

/** Near the phrases, but other commands (or nothing at all). */
private val OTHER_INPUTS = listOf(
    "stop the music", "stop the timer", "stop listening", "stop the stopwatch", "cancel my alarm",
    "cancel the pasta timer", "stop the alarm", "repeat", "repeat this song", "turn on repeat",
    "say that again in french", "say hello", "what did you say about the weather", "thanks",
    "thank you", "no", "ok", "please", "what", "i'm done with work", "that's all folks",
    "forget my password", "quiet mode", "be quiet for an hour", "never mind the weather",
    "one more time", "repeat please", "i can't hear it", "i can't hear the music",
    "nothing else matters", "not", "no thanks i want pizza",
)

private val PHRASES = listOf(STOP_INPUTS to Conversation.Stop, REPEAT_INPUTS to Conversation.Repeat)

private val data = Sentences.Conversation["en"]!!

private fun skill(
    ringing: Boolean = false,
    stopped: MutableList<SkillContext> = mutableListOf(),
    alarmSounds: Boolean = false,
    firing: FiringAlarmCheck = FiringAlarmCheck.NoAccess,
) = ConversationSkill(
    ConversationInfo, data, stopSpeaking = { stopped.add(it) }, isRinging = { ringing },
    alarmSounds = { alarmSounds }, firingAlarm = { firing },
)

private fun ConversationSkill.scoreOf(input: String) = score(FakeSkillContext().forInput(input), input)

/** The skills of a new install, ranked like the evaluator (GarRankingTest's list). */
private val ranker = SkillRanker(
    GarOnlineSkills.forRanking(allEnglishSkills(), UserSettingsSerializer.defaultValue.enabledSkillsMap),
    MockSkill(Specificity.LOW, 0.0f),
)

private fun winner(input: String): Pair<String?, Any?> {
    val best = ranker.getBest(FakeSkillContext().forInput(input), input)
    return Pair(best?.skill?.correspondingSkillInfo?.id, best?.inputData)
}

/** An output with the given [plan]. */
private class PlanOutput(private val plan: InteractionPlan) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = "Which day in December?"
    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan = plan
    @Composable override fun GraphicalOutput(ctx: SkillContext) {}
}

private suspend fun answer(
    input: Conversation,
    previous: SkillOutput?,
    stopped: MutableList<SkillContext> = mutableListOf(),
): SkillOutput {
    val ctx = FakeSkillContext()
    ctx.previousOutput = previous
    return skill(stopped = stopped).generateOutput(ctx, ConversationMatch(input))
}

class ConversationSkillTest : StringSpec({
    "the stop phrases" {
        for (input in STOP_INPUTS) {
            withClue(input) {
                ConversationWords.isStop(input).shouldBeTrue()
                ConversationWords.isRepeat(input).shouldBeFalse()
            }
        }
    }

    "the repeat phrases" {
        for (input in REPEAT_INPUTS) {
            withClue(input) {
                ConversationWords.isRepeat(input).shouldBeTrue()
                ConversationWords.isStop(input).shouldBeFalse()
            }
        }
    }

    "a word said twice and several fillers are dropped" {
        for (input in LOOSE_STOP_INPUTS) {
            withClue(input) { ConversationWords.isStop(input).shouldBeTrue() }
        }
    }

    "other inputs are neither" {
        for (input in OTHER_INPUTS) {
            withClue(input) {
                ConversationWords.isStop(input).shouldBeFalse()
                ConversationWords.isRepeat(input).shouldBeFalse()
            }
        }
    }

    "the words that stop a ringing timer or alarm are stop phrases of the skill" {
        for (phrase in GarRinging.STOP_PHRASES) {
            withClue(phrase) { ConversationWords.isStop(phrase).shouldBeTrue() }
        }
        for (input in listOf(
            "stop", "OK stop", "stop it please", "can you stop", "shut up", "be quiet",
            "silence", "enough", "that's enough", "Stop!", "just stop", "ok please stop",
            "stop stop", "uh, stop",
        )) {
            withClue(input) { GarRinging.isStopPhrase(input).shouldBeTrue() }
        }
        for (input in listOf("cancel", "cancel it", "ok cancel that")) {
            withClue(input) { GarRinging.isCancelWhileRinging(input).shouldBeTrue() }
        }
        GarRinging.isCancelWhileRinging("cancel my alarm").shouldBeFalse()
        // these stay with GAR's conversation
        for (input in listOf(
            "never mind", "cancel", "that's all", "stop talking", "repeat that", "stop the music",
        )) {
            withClue(input) { GarRinging.isStopPhrase(input).shouldBeFalse() }
        }
    }

    "the skill takes its phrases, each with its sentence and exactly, and nothing else" {
        val skill = skill()
        for ((inputs, sentence) in PHRASES) {
            for (input in inputs) {
                withClue(input) {
                    val (score, data) = skill.scoreOf(input)
                    data.sentence shouldBe sentence
                    // a whole sentence, so that it wins over an answer that takes any words
                    score.scoreIn01Range() shouldBeGreaterThanOrEqual GarFollowUps.COMMAND_SCORE
                }
            }
        }
        for (input in OTHER_INPUTS) {
            withClue(input) { skill.scoreOf(input).first shouldBeSameInstanceAs AlwaysWorstScore }
        }
    }

    "while a timer or an alarm rings, the words that stop it are left to its skill" {
        val ringing = skill(ringing = true)
        // "cancel" too: the clock app's ringing alarm takes it, like a ringing timer
        for (input in listOf(
            "stop", "OK stop", "stop it", "shut up", "be quiet please", "enough", "cancel",
            "cancel it", "cancel that",
        )) {
            withClue(input) { ringing.scoreOf(input).first shouldBeSameInstanceAs AlwaysWorstScore }
        }
        for (input in listOf("never mind", "that's all", "stop talking", "repeat that")) {
            withClue(input) { ringing.scoreOf(input).first shouldNotBe AlwaysWorstScore }
        }
    }

    "stop: GAR's speech stops, nothing is said and the interaction ends" {
        val stopped = mutableListOf<SkillContext>()
        val previous = FakeOutput("the answer")
        val output = answer(Conversation.Stop, previous, stopped).shouldBeInstanceOf<ConversationStopOutput>()
        stopped.size shouldBe 1
        output.getSpeechOutput(FakeSkillContext()) shouldBe ""
        output.getInteractionPlan(FakeSkillContext()) shouldBe InteractionPlan.FinishInteraction
        output.before shouldBeSameInstanceAs previous
        skill().keepsQuestion(ConversationMatch(Conversation.Stop)).shouldBeFalse()
    }

    "stop to an alarm that GAR can't see says where to stop it, instead of a silent OK" {
        // no notification access: nothing is registered as ringing, but an alarm sounds
        val sounding = skill(alarmSounds = true)
        for (input in listOf("stop", "shut up", "ok stop")) {
            withClue(input) {
                val (_, match) = sounding.scoreOf(input)
                sounding.generateOutput(FakeSkillContext(), match) shouldBe
                    AlarmOutput.Ringing(needsAccess = true)
            }
        }
        // what doesn't silence anything stays GAR's own
        for (input in listOf("never mind", "stop talking", "cancel")) {
            withClue(input) {
                val (_, match) = sounding.scoreOf(input)
                sounding.generateOutput(FakeSkillContext(), match)
                    .shouldBeInstanceOf<ConversationStopOutput>()
            }
        }
        // nothing sounds: a silent OK
        skill().let { it.generateOutput(FakeSkillContext(), it.scoreOf("stop").second) }
            .shouldBeInstanceOf<ConversationStopOutput>()
    }

    "repeat: the last answer is said again, and a question listens again" {
        val ctx = FakeSkillContext()
        skill().keepsQuestion(ConversationMatch(Conversation.Repeat)).shouldBeTrue()

        // FakeOutput says "done" and ends the interaction
        answer(Conversation.Repeat, FakeOutput(null)).shouldBeInstanceOf<ConversationRepeatOutput>().let {
            it.getSpeechOutput(ctx) shouldBe "done"
            it.getInteractionPlan(ctx) shouldBe InteractionPlan.Continue(reopenMicrophone = false)
        }

        // a question: said again, and the microphone opens for the answer (its batch stays)
        val question = PlanOutput(InteractionPlan.StartSubInteraction(true, listOf()))
        val repeated = answer(Conversation.Repeat, question).shouldBeInstanceOf<ConversationRepeatOutput>()
        repeated.getSpeechOutput(ctx) shouldBe "Which day in December?"
        repeated.getInteractionPlan(ctx) shouldBe InteractionPlan.Continue(reopenMicrophone = true)
        // and again
        answer(Conversation.Repeat, repeated).shouldBeInstanceOf<ConversationRepeatOutput>().let {
            it.getSpeechOutput(ctx) shouldBe "Which day in December?"
            it.getInteractionPlan(ctx) shouldBe InteractionPlan.Continue(reopenMicrophone = true)
        }

        // after "stop", the answer it stopped; the question it ended doesn't listen again
        answer(Conversation.Repeat, ConversationStopOutput(question))
            .shouldBeInstanceOf<ConversationRepeatOutput>().let {
                it.getSpeechOutput(ctx) shouldBe "Which day in December?"
                it.getInteractionPlan(ctx) shouldBe InteractionPlan.Continue(reopenMicrophone = false)
            }

        // nothing said yet: the conversation stays as it is
        for (previous in listOf(null, ConversationStopOutput(null))) {
            answer(Conversation.Repeat, previous).shouldBeInstanceOf<ConversationNothingToRepeatOutput>()
                .getInteractionPlan(ctx) shouldBe InteractionPlan.Continue(reopenMicrophone = false)
        }
    }

    "the phrases win over every other skill" {
        for ((inputs, sentence) in PHRASES + listOf(LOOSE_STOP_INPUTS to Conversation.Stop)) {
            for (input in inputs) {
                withClue(input) {
                    val (skill, match) = winner(input)
                    skill shouldBe ConversationInfo.id
                    match.shouldBeInstanceOf<ConversationMatch>().sentence shouldBe sentence
                }
            }
        }
    }

    "other skills keep their commands" {
        for ((input, skill) in listOf(
            "stop the timer" to TimerInfo.id,
            "cancel the pasta timer" to TimerInfo.id,
            "stop listening" to "listening",
            "stop the stopwatch" to StopwatchInfo.id,
            "cancel my alarm" to AlarmInfo.id,
            "pause the music" to MediaInfo.id,
        )) {
            withClue(input) { winner(input).first shouldBe skill }
        }
        val taken = OTHER_INPUTS.associateWith { winner(it).first }.filterValues { it == ConversationInfo.id }
        taken shouldBe mapOf()
    }

    "the timer and the alarm register what rings, and a failing provider rings nothing" {
        try {
            GarRinging.isRinging().shouldBeFalse()
            var timer = false
            GarRinging.register("test_timer") { timer }
            GarRinging.register("test_alarm") { throw IllegalStateException("no clock app") }
            GarRinging.isRinging().shouldBeFalse()
            timer = true
            GarRinging.ringing() shouldBe listOf("test_timer")
            GarRinging.register("test_alarm") { true }
            GarRinging.ringing() shouldBe listOf("test_alarm", "test_timer")
            GarRinging.unregister("test_timer")
            GarRinging.unregister("test_alarm")
            GarRinging.ringing().shouldBeEmpty()
        } finally {
            GarRinging.unregister("test_timer")
            GarRinging.unregister("test_alarm")
        }
    }

    "a bare thanks ends a continued conversation silently, and is small talk's otherwise" {
        val continued = ConversationSkill(
            ConversationInfo, data, stopSpeaking = {}, isRinging = { false },
            alarmSounds = { false }, firingAlarm = { FiringAlarmCheck.NoAccess },
            continued = { true },
        )
        for (input in listOf("thanks", "Thank you.", "thank you very much", "ok thanks", "cheers")) {
            withClue(input) {
                val (score, match) = continued.scoreOf(input)
                score shouldBeSameInstanceAs AlwaysBestScore
                match.sentence shouldBe Conversation.Stop
                val output = continued.generateOutput(FakeSkillContext(), match)
                output.shouldBeInstanceOf<ConversationStopOutput>()
                output.getSpeechOutput(FakeSkillContext()) shouldBe ""
                // a conversation's end: no continued-listening window after it
                (output is GarEndsConversation).shouldBeTrue()
                // outside a window it is small talk's
                skill().scoreOf(input).first shouldBeSameInstanceAs AlwaysWorstScore
            }
        }
        for (input in listOf("thanks for the weather", "thank you what about tomorrow", "you")) {
            withClue(input) {
                continued.scoreOf(input).first shouldNotBe AlwaysBestScore
            }
        }
        // the stop phrases stay the same in a window
        continued.scoreOf("that's all").second.sentence shouldBe Conversation.Stop

        // the whole ranker: small talk's "You're welcome!" outside a window, the silent stop in one
        winner("thank you").first shouldBe "small_talk"
        GarContinuedListening.matchingContinued = true
        try {
            winner("thank you").first shouldBe ConversationInfo.id
            winner("what time is it").first shouldBe "current_time"
        } finally {
            GarContinuedListening.matchingContinued = false
        }
    }

    "the skill has no tool, so the model's prompt stays as it is" {
        val tools = cc.aaro.gar.router.tools.ToolCatalog.load(
            java.io.File("src/main/gar/tools.yml").readText(),
            java.io.File("src/main/sentences/skill_definitions.yml").readText(),
        ).tools
        tools.map { it.skillId }.shouldContainAll(listOf("timer", "alarm"))
        tools.filter { it.skillId == ConversationInfo.id }.shouldBeEmpty()
    }
})
