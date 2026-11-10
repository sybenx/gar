package org.stypox.dicio.gar.followup

import cc.aaro.gar.router.followup.FollowUpForms
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.followup.adapters.AnswerFollowUps
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.current_time.CurrentTimeInfo
import org.stypox.dicio.skills.random.RandomRequest
import org.stypox.dicio.skills.sms.FakeOwnContact
import org.stypox.dicio.skills.sms.SendResult
import org.stypox.dicio.skills.sms.SmsCardOutput
import org.stypox.dicio.skills.sms.SmsContact
import org.stypox.dicio.skills.sms.SmsFlow
import org.stypox.dicio.skills.sms.SmsInfo
import org.stypox.dicio.skills.sms.SmsRecipients
import org.stypox.dicio.skills.sms.SmsSendOutput
import org.stypox.dicio.skills.sms.SmsSender
import org.stypox.dicio.skills.sms.SmsSkill
import org.stypox.dicio.skills.sms.SmsState
import org.stypox.dicio.skills.timer.TimerAnswer
import org.stypox.dicio.skills.volume.SpokenVolume
import org.stypox.dicio.skills.timer.TimerAnswerOutput
import org.stypox.dicio.skills.weather.GarWeatherSkill
import org.stypox.dicio.skills.weather.WeatherInfo
import org.stypox.dicio.skills.weather.WeatherRequest
import org.stypox.dicio.skills.weather.WeatherWhen
import org.stypox.dicio.util.StringUtils

/** Texts that never leave the test. Each one goes through at once. */
private class SentTexts : SmsSender {
    val sent = mutableListOf<Pair<String, String>>()

    override fun canSendDirectly(): Boolean = true

    override fun send(number: String, message: String, onResult: (SendResult) -> Unit) {
        sent.add(Pair(number, message))
        onResult(SendResult.SENT)
    }

    override fun openSmsApp(number: String, message: String): Boolean = true
}

private val MOM = SmsContact("Mom", listOf("+1 555 0100"))
private val ERIN = SmsContact("Erin", listOf("+1 555 0101"))

/** The sms skill with Mom and Erin in memory instead of the contacts provider. */
private fun smsWith(sender: SmsSender, contacts: List<SmsContact> = listOf(MOM, ERIN)): SmsSkill {
    val flow = SmsFlow(
        findRecipient = { _, spoken, split ->
            SmsRecipients.find(
                spoken = spoken,
                splitMessage = split,
                search = { name ->
                    contacts.map { Pair(it, StringUtils.contactStringDistance(it.name, name)) }
                        .filter { it.second < 0 }
                        .sortedBy { it.second }
                },
                name = { it.first.name },
                distance = { it.second },
                numbers = { it.first.numbers },
            )
        },
        senderFor = { sender },
        isDeviceLocked = { false },
        ownContact = { FakeOwnContact.of(*contacts.toTypedArray()) },
    )
    return SmsSkill(SmsInfo, Sentences.Sms["en"]!!, flow)
}

/** What the follow-up skill made of the last input it scored. */
private fun lastOutcome(): GarFollowUpOutcome? = GarFollowUpSkill.scored?.second?.outcome

/**
 * Conversations with follow-ups, replayed through the evaluator's matching and the follow-ups'
 * memory the way SkillEvaluator brackets each turn: what was said, one turn after the other.
 */
class GarFollowUpChatReplayTest : StringSpec({
    afterTest { AnswerFollowUps.today = { java.time.LocalDate.now() } }

    "turn on the flashlight, then turn it back off" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("turn on the flashlight").inputData shouldBe Sentences.Flashlight.TurnOn
        phone.say("turn it back off").let {
            it.input shouldBe "turn it back off"
            it.skill shouldBe "flashlight"
            it.inputData shouldBe Sentences.Flashlight.TurnOff
        }
        lastOutcome() shouldBe GarFollowUpOutcome.Rewritten("turn off the flashlight")
        // again, thanks, no: the same follow-up
        phone.say("turn it on again").inputData shouldBe Sentences.Flashlight.TurnOn
        phone.say("turn it off again").inputData shouldBe Sentences.Flashlight.TurnOff
        phone.say("Turn it back on. Thank you.").inputData shouldBe Sentences.Flashlight.TurnOn
        phone.say("no, turn it back off").inputData shouldBe Sentences.Flashlight.TurnOff
        phone.router.routed.shouldBeEmpty()
    }

    "set a timer for 5 minutes, then make it 10" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("set a timer for 5 minutes") {
            TimerAnswerOutput(TimerAnswer.Started(null, 5 * 60_000L))
        }.skill shouldBe "timer"
        phone.say("make it 10").let {
            it.input shouldBe "make it 10"
            it.skill shouldBe "timer_control"
        }
        lastOutcome() shouldBe GarFollowUpOutcome.Rewritten("add 5 minutes to the timer")
        // the timer is still what "it" is
        phone.say("cancel it").skill shouldBe "timer"
        lastOutcome() shouldBe GarFollowUpOutcome.Rewritten("cancel the timer")
        phone.router.routed.shouldBeEmpty()
    }

    "weather tomorrow, then what about friday, then and in denver" {
        resetMemory()
        AnswerFollowUps.today = { FOLLOW_UP_NOW.toLocalDate() }
        // the weather skill reads days on the same date as the memory
        val phone = FollowUpReplayPhone(swap = {
            if (it.correspondingSkillInfo == WeatherInfo) {
                GarWeatherSkill(WeatherInfo, Sentences.Weather["en"]!!, today = { FOLLOW_UP_NOW.toLocalDate() })
            } else {
                it
            }
        })
        phone.say("weather tomorrow").let {
            it.skill shouldBe "weather"
            it.inputData.shouldBeInstanceOf<WeatherRequest>().place shouldBe null
        }
        phone.say("what about friday").let {
            it.skill shouldBe "weather"
            it.inputData.shouldBeInstanceOf<WeatherRequest>().time.shouldBeInstanceOf<WeatherWhen.Day>()
        }
        lastOutcome() shouldBe GarFollowUpOutcome.Rewritten("what's the weather on friday")
        phone.say("and in denver").let {
            it.skill shouldBe "weather"
            it.inputData.shouldBeInstanceOf<WeatherRequest>().place shouldBe "denver"
        }
        // the day of the reply chain stays
        lastOutcome() shouldBe GarFollowUpOutcome.Rewritten("what's the weather on friday in denver")
        phone.router.routed.shouldBeEmpty()
    }

    "what's the weather like tomorrow, then what about the next day, then and the day after" {
        resetMemory()
        AnswerFollowUps.today = { FOLLOW_UP_NOW.toLocalDate() }
        val phone = FollowUpReplayPhone(swap = {
            if (it.correspondingSkillInfo == WeatherInfo) {
                GarWeatherSkill(WeatherInfo, Sentences.Weather["en"]!!, today = { FOLLOW_UP_NOW.toLocalDate() })
            } else {
                it
            }
        })
        phone.say("what's the weather like tomorrow").skill shouldBe "weather"
        // Sunday the 25th: tomorrow is Monday, the next day Tuesday
        phone.say("what about the next day?").let {
            it.skill shouldBe "weather"
            it.inputData.shouldBeInstanceOf<WeatherRequest>().time shouldBe
                WeatherWhen.Day(FOLLOW_UP_NOW.toLocalDate().plusDays(2))
        }
        lastOutcome() shouldBe GarFollowUpOutcome.Rewritten("what's the weather on tuesday")
        phone.say("and the day after").let {
            it.skill shouldBe "weather"
            it.inputData.shouldBeInstanceOf<WeatherRequest>().time shouldBe
                WeatherWhen.Day(FOLLOW_UP_NOW.toLocalDate().plusDays(3))
        }
        lastOutcome() shouldBe GarFollowUpOutcome.Rewritten("what's the weather on wednesday")
        phone.router.routed.shouldBeEmpty()
    }

    "text mom saying hi, then call her" {
        resetMemory()
        val sender = SentTexts()
        val sms = smsWith(sender)
        val phone = FollowUpReplayPhone(swap = { if (it.correspondingSkillInfo == SmsInfo) sms else it })
        val real: suspend (org.stypox.dicio.eval.SkillWithResult<*>) -> org.dicio.skill.skill.SkillOutput =
            { it.generateOutput(FakeSkillContext()) }
        phone.say("text mom saying hi", output = real).let {
            it.skill shouldBe "sms"
            it.output.shouldBeInstanceOf<SmsCardOutput>().draft.recipient.name shouldBe "Mom"
        }
        phone.say("yes", output = real).output.shouldBeInstanceOf<SmsSendOutput>().state shouldBe SmsState.SENT
        sender.sent shouldContainExactly listOf(Pair("+1 555 0100", "Hi"))
        phone.say("call her").let {
            it.input shouldBe "call her"
            it.skill shouldBe "telephone"
        }
        lastOutcome() shouldBe GarFollowUpOutcome.Rewritten("call mom")
    }

    "what's the volume, then turn it up" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        val asked = phone.say("what's the volume")
        asked.skill shouldBe "volume"
        asked.inputData.shouldBeInstanceOf<SpokenVolume>().question shouldBe true
        phone.say("turn it up").let {
            it.input shouldBe "turn it up"
            it.skill shouldBe "volume"
        }
        lastOutcome() shouldBe GarFollowUpOutcome.Rewritten("turn the volume up")
    }

    "flip a coin, then another one" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("flip a coin").inputData shouldBe RandomRequest.Coin
        phone.say("another one").let {
            it.skill shouldBe "random"
            it.inputData shouldBe RandomRequest.Coin
        }
        lastOutcome() shouldBe GarFollowUpOutcome.Rewritten("flip a coin")
    }

    "an unrelated command after a follow-up isn't rewritten" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("turn on the flashlight")
        phone.say("turn it off", continued = true).inputData shouldBe Sentences.Flashlight.TurnOff
        // the next command in the window is its own
        phone.say("what time is it", continued = true).skill shouldBe CurrentTimeInfo.id
        phone.say("set an alarm for 7 am").skill shouldBe "alarm"
        // a question asked by a follow-up gives way to a whole command
        resetMemory()
        phone.say("turn it off").output.shouldBeInstanceOf<GarFollowUpAskOutput>()
        phone.say("what time is it").skill shouldBe CurrentTimeInfo.id
        phone.say("flip a coin").inputData shouldBe RandomRequest.Coin
        phone.router.routed.shouldBeEmpty()
    }

    "turn it off with nothing remembered asks what" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("turn it off").let {
            it.skill shouldBe GarFollowUpInfo.id
            it.output.shouldBeInstanceOf<GarFollowUpAskOutput>().question shouldBe GarFollowUpQuestion.TURN_WHAT_OFF
        }
        phone.say("the flashlight").inputData shouldBe Sentences.Flashlight.TurnOff
        phone.router.routed.shouldBeEmpty()
    }

    "bluetooth, then music, then turn it off pauses the music" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("turn on bluetooth").inputData shouldBe Sentences.Connectivity.BluetoothOn
        phone.say("play my road trip playlist").skill shouldBe "media_play"
        phone.media = true
        phone.say("turn it off").skill shouldBe "media"
        lastOutcome() shouldBe GarFollowUpOutcome.Rewritten(GarFollowUpReach.PAUSE_MUSIC)
        phone.router.routed.shouldBeEmpty()
    }

    "bluetooth, then the brightness, then turn it off asks what" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("turn on bluetooth").inputData shouldBe Sentences.Connectivity.BluetoothOn
        phone.say("set the brightness to 50 percent").skill shouldBe "device_settings"
        phone.say("turn it off").output.shouldBeInstanceOf<GarFollowUpAskOutput>().question shouldBe
            GarFollowUpQuestion.TURN_WHAT_OFF
    }

    "set an alarm for 7, then a continued cancel it or turn it off asks first" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        val alarm: suspend (org.stypox.dicio.eval.SkillWithResult<*>) -> org.dicio.skill.skill.SkillOutput = {
            org.stypox.dicio.skills.alarm.AlarmOutput.Set(
                cc.aaro.gar.router.resolve.ResolvedTime(java.time.LocalTime.of(7, 0)), null, null,
                org.stypox.dicio.gar.TEST_TODAY, org.stypox.dicio.gar.englishFormatter(),
            )
        }
        for (input in listOf("cancel it", "turn it off")) {
            phone.say("set an alarm for 7 am", output = alarm).skill shouldBe "alarm"
            phone.say(input, continued = true).output.shouldBeInstanceOf<GarFollowUpConfirmOutput>().question shouldBe
                "Cancel your 7 am alarm?"
            phone.say("yes", continued = true).inputData
                .shouldBeInstanceOf<org.stypox.dicio.skills.alarm.AlarmSentenceMatch>().sentence
                .shouldBeInstanceOf<Sentences.Alarm.Cancel>()
        }
        // small talk after the alarm: cancel it is the conversation's
        phone.say("set an alarm for 7 am", output = alarm)
        phone.say("how are you").skill shouldBe "small_talk"
        phone.say("cancel it").skill shouldBe org.stypox.dicio.skills.conversation.ConversationInfo.id
        phone.router.routed.shouldBeEmpty()
    }

    "no wait after the weather or a text is no correction" {
        resetMemory()
        val phone = FollowUpReplayPhone()
        phone.say("what's the weather in austin").skill shouldBe "weather"
        FollowUpForms.parse("no wait").shouldBeNull()
        FollowUpForms.parse("no that's wrong").shouldBeNull()
        FollowUpForms.parse("no sorry").shouldBeNull()
        (phone.match("no wait").second.skill is GarFollowUpStep) shouldBe false
    }
})
