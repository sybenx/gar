package org.stypox.dicio.gar.followup

import cc.aaro.gar.router.followup.FollowUpForm
import cc.aaro.gar.router.followup.FollowUpForms
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.InteractionPlan
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.TEST_TODAY
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.io.graphical.ErrorSkillOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.alarm.AlarmOutput
import org.stypox.dicio.skills.conversation.ConversationRepeatOutput
import org.stypox.dicio.skills.conversation.ConversationWords
import org.stypox.dicio.skills.device_settings.DeviceAction
import org.stypox.dicio.skills.device_settings.DeviceSetting
import org.stypox.dicio.skills.device_settings.DeviceSettingTool
import org.stypox.dicio.skills.flashlight.FlashlightOutput
import org.stypox.dicio.skills.joke.JokeInfo
import org.stypox.dicio.skills.telephone.ConfirmCallOutput
import org.stypox.dicio.skills.telephone.ConfirmedCallOutput
import org.stypox.dicio.skills.timer.TimerAnswer
import org.stypox.dicio.skills.timer.TimerAnswerOutput
import cc.aaro.gar.router.resolve.ResolvedTime
import java.time.LocalTime

private const val SECOND = 1_000L
private const val MINUTE = 60_000L

fun flashlightOn() = answered("flashlight", "turn on the flashlight", Sentences.Flashlight.TurnOn, FlashlightOutput.Success(true))

fun alarmAt(hour: Int) = answered(
    "alarm", "set an alarm for $hour", null,
    AlarmOutput.Set(ResolvedTime(LocalTime.of(hour, 0)), null, null, TEST_TODAY, englishFormatter()),
)

fun weatherNow() = answered("weather", "what's the weather", null, FakeOutput(null))
fun joke() = answered(JokeInfo.id, "tell me a joke", null, FakeOutput(null))
fun timeNow() = answered("current_time", "what time is it", Sentences.CurrentTime.Query, FakeOutput(null))

/** What GAR makes of [input] now, as the follow-up skill reads it. */
fun reach(
    input: String,
    locked: Boolean = false,
    continued: Boolean = false,
    whileSpeaking: Boolean = false,
    mediaPlaying: Boolean = false,
): GarFollowUpMatch = GarFollowUpReach.resolve(
    checkNotNull(FollowUpForms.parse(input)) { input },
    FakeFollowUpContext(GarConversationMemory.view(locked), mediaPlaying, continued),
    locked,
    whileSpeaking,
)

class GarConversationMemoryTest : StringSpec({
    "turns are remembered newest first, three of them, for five minutes" {
        val clock = resetMemory()
        remember(flashlightOn())
        clock.advance(SECOND)
        remember(alarmAt(7))
        clock.advance(SECOND)
        remember(weatherNow())
        clock.advance(SECOND)
        remember(joke())
        GarConversationMemory.view(false).turns.map { it.turn.skillId } shouldContainExactly
            listOf(JokeInfo.id, "weather", "alarm")
        GarConversationMemory.view(false).newest!!.ageMillis shouldBe 0
        clock.advance(GarConversationMemory.MEMORY_MILLIS)
        GarConversationMemory.view(false).turns.map { it.turn.skillId } shouldContainExactly listOf(JokeInfo.id)
        clock.advance(SECOND)
        GarConversationMemory.view(false).turns.shouldBeEmpty()
    }

    "questions, errors, locked answers, the conversation and the follow-up skill's own answers leave it" {
        resetMemory()
        remember(flashlightOn())
        val ignored = listOf(
            answered("alarm", "set an alarm", null, FakeOutput(null), InteractionPlan.StartSubInteraction(true, listOf())),
            answered("weather", "weather", null, ErrorSkillOutput(Exception(), true)),
            answered("weather", "weather", null, GarLockedOutput()),
            answered("conversation", "repeat that", null, FakeOutput(null)),
            answered("small_talk", "how are you", null, FakeOutput(null)),
            answered("text", "blah", null, FakeOutput(null)),
            answered(GarFollowUpInfo.id, "who sings it", null, GarFollowUpSayOutput("That's ABBA.")),
        )
        for (turn in ignored) {
            remember(turn)
        }
        remember(null) // an error or a permission card
        GarConversationMemory.view(false).turns.map { it.turn.skillId } shouldContainExactly listOf("flashlight")
    }

    "a skill without an adapter leaves a bare turn; an adapter's null leaves nothing" {
        resetMemory()
        remember(answered("battery", "how much battery", null, FakeOutput(null)))
        GarConversationMemory.view(false).newest!!.adapter shouldBe BareFollowUps
        remember(answered("telephone", "no", null, ConfirmedCallOutput(null)))
        GarConversationMemory.view(false).turns.map { it.turn.skillId } shouldContainExactly listOf("battery")
    }

    "a call remembers the contact of its question" {
        resetMemory()
        remember(answered(
            "telephone", "call erin", null,
            ConfirmCallOutput("Erin", "555 0100", Sentences.UtilYesNo["en"]!!),
            InteractionPlan.ReplaceSubInteraction(true, listOf()),
        ))
        remember(answered("telephone", "yes", true, ConfirmedCallOutput("555 0100"), InteractionPlan.FinishSubInteraction(false)))
        GarConversationMemory.view(false).newest!!.turn.facts.person shouldBe
            GarPerson("Erin", "555 0100", GarPersonSource.CONTACT, unique = true)
    }

    "a confirmed model call takes its slots from the call, not from yes" {
        resetMemory()
        val element = turnElement()
        element.call = GarCall(
            tool = DeviceSettingTool.NUMBER_SPEC,
            rawSlots = mapOf(),
            utterance = "make the screen dark",
            values = mapOf(
                DeviceSettingTool.SETTING to DeviceSetting.DARK_MODE.slotValue,
                DeviceSettingTool.ACTION to DeviceAction.ON.slotValue,
            ),
        )
        remember(
            answered(
                "device_settings", "yes", true, GarFollowUpOutput(FakeOutput(null)),
                InteractionPlan.FinishSubInteraction(false), call = element.call,
            ),
            element,
        )
        val turn = GarConversationMemory.view(false).newest!!.turn
        turn.facts.setting shouldBe "dark mode"
        turn.kind shouldBe GarTurnKind.REVERSIBLE
        reach("turn it off").outcome shouldBe GarFollowUpOutcome.Rewritten("turn off dark mode")
    }

    "turns answered unlocked are hidden while locked; forget and the switch clear them" {
        resetMemory()
        remember(flashlightOn())
        GarConversationMemory.view(true).turns.shouldBeEmpty()
        remember(timeNow(), turnElement(locked = true))
        GarConversationMemory.view(true).turns.map { it.turn.skillId } shouldContainExactly listOf("current_time")
        GarConversationMemory.forget()
        GarConversationMemory.view(false).turns.shouldBeEmpty()

        remember(flashlightOn())
        GarConversationMemory.enabled = false
        GarConversationMemory.view(false).turns.shouldBeEmpty()
        remember(flashlightOn())
        GarConversationMemory.view(false).turns.shouldBeEmpty()
        GarConversationMemory.enabled = true
    }

    "reach: cancel it reaches the newest clock within a minute, over the conversation" {
        val clock = resetMemory()
        remember(alarmAt(7))
        clock.advance(30 * SECOND)
        reach("cancel it").let {
            it.outcome shouldBe GarFollowUpOutcome.Rewritten("cancel my 7 am alarm")
            it.alwaysBest shouldBe true
        }
        reach("delete it").outcome shouldBe GarFollowUpOutcome.Rewritten("cancel my 7 am alarm")
        reach("turn it off").outcome shouldBe GarFollowUpOutcome.Rewritten("cancel my 7 am alarm")
        // a barge-in: not over the conversation's stop
        reach("cancel it", whileSpeaking = true).alwaysBest shouldBe false
        // continued: cancel it, delete it and turn it off ask first, over the conversation's stop
        for (input in listOf("cancel it", "delete it", "turn it off")) {
            withClue(input) {
                reach(input, continued = true).let {
                    it.outcome.shouldBeInstanceOf<GarFollowUpOutcome.Confirm>().question shouldBe "Cancel your 7 am alarm?"
                    it.alwaysBest shouldBe true
                }
            }
        }
        clock.advance(31 * SECOND)
        reach("cancel it").outcome shouldBe GarFollowUpOutcome.Deferred("reach")
        // a change still reaches it for five minutes
        reach("change it to 8").outcome shouldBe GarFollowUpOutcome.Rewritten("change my 7 am alarm to 8 am")
    }

    "reach: cancel it needs the clock to be the latest answer, repeat that aside" {
        resetMemory()
        remember(alarmAt(7))
        // small talk is not remembered, but GAR answered it after the alarm
        remember(answered("small_talk", "how are you", null, FakeOutput(null)))
        reach("cancel it").outcome shouldBe GarFollowUpOutcome.Deferred("reach")
        reach("turn it off").outcome shouldBe GarFollowUpOutcome.Ask(GarFollowUpQuestion.TURN_WHAT_OFF, "turn off {answer}")
        resetMemory()
        remember(alarmAt(7))
        remember(answered("text", "what's the meaning of life", null, FakeOutput(null)))
        reach("cancel it").outcome shouldBe GarFollowUpOutcome.Deferred("reach")
        resetMemory()
        remember(alarmAt(7))
        remember(null) // an error
        reach("cancel it").outcome shouldBe GarFollowUpOutcome.Deferred("reach")
        resetMemory()
        remember(alarmAt(7))
        remember(answered("conversation", "repeat that", null, ConversationRepeatOutput.of(FakeSkillContext(), null)))
        reach("cancel it").outcome shouldBe GarFollowUpOutcome.Rewritten("cancel my 7 am alarm")
    }

    "reach: turn it off doesn't walk past the music or a clock" {
        resetMemory()
        remember(flashlightOn())
        remember(answered("media_play", "play my road trip playlist", null, FakeOutput(null)))
        reach("turn it off", mediaPlaying = true).outcome shouldBe GarFollowUpOutcome.Rewritten(GarFollowUpReach.PAUSE_MUSIC)
        reach("turn it off").outcome shouldBe GarFollowUpOutcome.Ask(GarFollowUpQuestion.TURN_WHAT_OFF, "turn off {answer}")
        val clock = resetMemory()
        remember(flashlightOn())
        remember(alarmAt(7))
        clock.advance(2 * MINUTE)
        reach("turn it off").outcome shouldBe GarFollowUpOutcome.Ask(GarFollowUpQuestion.TURN_WHAT_OFF, "turn off {answer}")
        // a remembered setting still wins over the music
        resetMemory()
        remember(flashlightOn())
        reach("turn it off", mediaPlaying = true).outcome shouldBe GarFollowUpOutcome.Rewritten("turn off the flashlight")
        resetMemory()
        reach("turn it off", mediaPlaying = true).outcome shouldBe GarFollowUpOutcome.Rewritten(GarFollowUpReach.PAUSE_MUSIC)
        reach("turn it on", mediaPlaying = true).outcome shouldBe GarFollowUpOutcome.Ask(GarFollowUpQuestion.TURN_WHAT_ON, "turn on {answer}")
    }

    "reach: pausing a timer isn't confirmed in a continued turn" {
        resetMemory()
        remember(answered("timer", "set a pasta timer", null, TimerAnswerOutput(TimerAnswer.Started("pasta", 5 * MINUTE))))
        reach("pause it", continued = true).outcome shouldBe GarFollowUpOutcome.Rewritten("pause the pasta timer")
        reach("make it 10", continued = true).outcome.shouldBeInstanceOf<GarFollowUpOutcome.Confirm>()
    }

    "a call remembers the contact it called, not a question left unanswered" {
        resetMemory()
        // "call mom": "Call Mom?", and the microphone timed out
        remember(answered(
            "telephone", "call mom", null,
            ConfirmCallOutput("Mom", "555 0100", Sentences.UtilYesNo["en"]!!),
            InteractionPlan.ReplaceSubInteraction(true, listOf()),
        ))
        // "call him back", from the default batch: call_log asks "Call Erin?", and yes
        remember(
            answered(
                "call_log", "call him back", null,
                ConfirmCallOutput("Erin", "555 0101", Sentences.UtilYesNo["en"]!!),
                InteractionPlan.ReplaceSubInteraction(true, listOf()),
            ),
            continuesStep = false,
        )
        remember(answered("telephone", "yes", true, ConfirmedCallOutput("555 0101"), InteractionPlan.FinishSubInteraction(false)))
        GarConversationMemory.view(false).newest!!.turn.facts.person shouldBe
            GarPerson("Erin", "555 0101", GarPersonSource.CONTACT, unique = true)
        // even with the stale question still there, only the called number's name is taken
        resetMemory()
        remember(answered(
            "telephone", "call mom", null,
            ConfirmCallOutput("Mom", "555 0100", Sentences.UtilYesNo["en"]!!),
            InteractionPlan.ReplaceSubInteraction(true, listOf()),
        ))
        remember(answered("telephone", "yes", true, ConfirmedCallOutput("555 0101"), InteractionPlan.FinishSubInteraction(false)))
        GarConversationMemory.view(false).newest!!.turn.facts.person shouldBe
            GarPerson("555 0101", "555 0101", GarPersonSource.CONTACT, unique = false)
    }

    "reach: an alarm that isn't the newest turn is out of reach" {
        resetMemory()
        remember(alarmAt(7))
        remember(weatherNow())
        reach("cancel it").outcome shouldBe GarFollowUpOutcome.Deferred("reach")
        resetMemory()
        remember(alarmAt(7))
        remember(joke())
        reach("turn it off").outcome shouldBe GarFollowUpOutcome.Ask(GarFollowUpQuestion.TURN_WHAT_OFF, "turn off {answer}")
    }

    "reach: settings are walked back to" {
        val clock = resetMemory()
        remember(flashlightOn())
        remember(timeNow())
        reach("turn it off").outcome shouldBe GarFollowUpOutcome.Rewritten("turn off the flashlight")
        reach("turn it back off").outcome shouldBe GarFollowUpOutcome.Rewritten("turn off the flashlight")
        clock.advance(6 * MINUTE)
        reach("turn it off").outcome shouldBe GarFollowUpOutcome.Ask(GarFollowUpQuestion.TURN_WHAT_OFF, "turn off {answer}")
    }

    "reach: adjustments" {
        resetMemory()
        reach("louder").outcome shouldBe GarFollowUpOutcome.Rewritten("turn the volume up")
        reach("turn it up").outcome shouldBe GarFollowUpOutcome.Ask(GarFollowUpQuestion.TURN_WHAT_UP, "turn up {answer}")
        reach("turn it up", mediaPlaying = true).outcome shouldBe GarFollowUpOutcome.Rewritten("turn the volume up")
    }

    "reach: corrections within a minute, another one only after a repeatable answer" {
        val clock = resetMemory()
        remember(alarmAt(19))
        reach("no, 8 pm").outcome shouldBe GarFollowUpOutcome.Rewritten("change my 7 pm alarm to 8 pm")
        clock.advance(61 * SECOND)
        reach("no, 8 pm").outcome shouldBe GarFollowUpOutcome.Deferred("reach")
        reach("another one").outcome shouldBe GarFollowUpOutcome.Deferred("reach")
        remember(joke())
        reach("another one").outcome shouldBe GarFollowUpOutcome.Rewritten("tell me a joke")
        reach("another one", mediaPlaying = true).outcome shouldBe GarFollowUpOutcome.Deferred("media")
    }

    "reach: the lock" {
        resetMemory()
        remember(flashlightOn(), turnElement(locked = true))
        reach("turn it off", locked = true).outcome shouldBe GarFollowUpOutcome.Rewritten("turn off the flashlight")
        reach("call her", locked = true).outcome shouldBe GarFollowUpOutcome.Locked
    }

    "a person is reached for two minutes" {
        val clock = resetMemory()
        remember(answered(
            "contact_info", "what's erin's number", null,
            org.stypox.dicio.skills.contact_info.ContactInfoOutput.Found(
                "Erin", org.stypox.dicio.skills.contact_info.ContactField.NUMBER, listOf(), own = false,
            ),
        ))
        remember(weatherNow())
        reach("call her").outcome shouldBe GarFollowUpOutcome.Rewritten("call erin")
        reach("call her back").outcome shouldBe GarFollowUpOutcome.Deferred("back")
        clock.advance(2 * MINUTE + SECOND)
        reach("call her").outcome.shouldBeInstanceOf<GarFollowUpOutcome.Ask>()
    }

    "the parser's declines are the conversation's" {
        for (decline in listOf("no thanks", "no thank you", "no not now", "never mind", "that's all", "nope")) {
            withClue(decline) {
                ConversationWords.isStop(decline) shouldBe true
                FollowUpForms.parse(decline).shouldBeNull()
            }
        }
        FollowUpForms.parse("cancel it") shouldBe FollowUpForm.Remove(delete = false)
    }
})
