package org.stypox.dicio.skills.calls

import android.media.AudioManager
import android.telephony.TelephonyManager
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.CallControl

/**
 * The phone's calls: [states] is what [state] says, one after the other (the last one stays),
 * [ends] whether Android ends the call.
 */
class FakePhoneCalls(
    vararg states: PhoneCallState,
    private val ends: Boolean = true,
    private val refuses: Boolean = false,
) : PhoneCalls {
    private val states = states.toMutableList()
    val actions = mutableListOf<String>()

    override fun state(): PhoneCallState =
        if (states.size > 1) states.removeAt(0) else states.first()

    override fun answer() {
        if (refuses) throw SecurityException("ANSWER_PHONE_CALLS")
        actions.add("answer")
    }

    override fun end(): Boolean {
        if (refuses) throw SecurityException("ANSWER_PHONE_CALLS")
        actions.add("end")
        return ends
    }

    override fun showCallScreen() {
        actions.add("show")
    }
}

/** The waits of the skill, instead of waiting. */
private class Pauses {
    var total = 0L
    val pause: suspend (Long) -> Unit = { total += it }
}

private fun skill(calls: PhoneCalls, pauses: Pauses = Pauses()) = CallControlSkill(
    CallControlInfo, Sentences.CallControl["en"]!!, calls = { calls }, pause = pauses.pause,
)

private suspend fun run(command: CallControl, calls: PhoneCalls, pauses: Pauses = Pauses()): SkillOutput =
    skill(calls, pauses).generateOutput(FakeSkillContext(), command)

class CallControlSkillTest : StringSpec({
    "the call state from Telephony's state and the audio mode" {
        PhoneCallState.of(TelephonyManager.CALL_STATE_IDLE, AudioManager.MODE_NORMAL) shouldBe
            PhoneCallState.IDLE
        PhoneCallState.of(TelephonyManager.CALL_STATE_RINGING, AudioManager.MODE_RINGTONE) shouldBe
            PhoneCallState.RINGING
        // a waiting call: another call is on
        PhoneCallState.of(TelephonyManager.CALL_STATE_RINGING, AudioManager.MODE_IN_CALL) shouldBe
            PhoneCallState.WAITING
        PhoneCallState.of(TelephonyManager.CALL_STATE_RINGING, AudioManager.MODE_IN_COMMUNICATION) shouldBe
            PhoneCallState.WAITING
        PhoneCallState.of(TelephonyManager.CALL_STATE_OFFHOOK, AudioManager.MODE_IN_CALL) shouldBe
            PhoneCallState.IN_CALL
    }

    "answer answers the ringing call, then shows the call screen" {
        val calls = FakePhoneCalls(
            PhoneCallState.RINGING, PhoneCallState.RINGING, PhoneCallState.IN_CALL,
        )
        run(CallControl.Answer, calls) shouldBe CallControlOutput.Answered
        calls.actions shouldBe listOf("answer", "show")
    }

    "answer says so when the call still rings after two seconds" {
        val calls = FakePhoneCalls(PhoneCallState.RINGING)
        val pauses = Pauses()
        run(CallControl.Answer, calls, pauses).shouldBeInstanceOf<CallControlOutput.StillRinging>()
        calls.actions shouldBe listOf("answer")
        pauses.total shouldBe CallControlSkill.WAIT_MILLIS
    }

    "answer takes a waiting call too" {
        val calls = FakePhoneCalls(PhoneCallState.WAITING, PhoneCallState.IN_CALL)
        run(CallControl.Answer, calls) shouldBe CallControlOutput.Answered
        calls.actions shouldBe listOf("answer", "show")
    }

    "answer and decline with nothing ringing do nothing" {
        for (state in listOf(PhoneCallState.IDLE, PhoneCallState.IN_CALL)) {
            for (command in listOf(CallControl.Answer, CallControl.Decline)) {
                val calls = FakePhoneCalls(state)
                withClue("$command $state") {
                    run(command, calls) shouldBe CallControlOutput.NothingRinging
                    calls.actions shouldBe listOf()
                }
            }
        }
    }

    "decline declines the ringing call" {
        val calls = FakePhoneCalls(PhoneCallState.RINGING)
        run(CallControl.Decline, calls) shouldBe CallControlOutput.Declined
        calls.actions shouldBe listOf("end")
    }

    "decline leaves a waiting call alone, since endCall would end the other call" {
        val calls = FakePhoneCalls(PhoneCallState.WAITING)
        run(CallControl.Decline, calls).shouldBeInstanceOf<CallControlOutput.CantDeclineWaiting>()
        calls.actions shouldBe listOf()
    }

    "hang up ends the call, or declines a ringing one" {
        for (state in listOf(PhoneCallState.IN_CALL, PhoneCallState.WAITING)) {
            val calls = FakePhoneCalls(state)
            withClue(state) {
                run(CallControl.HangUp, calls) shouldBe CallControlOutput.Ended
                calls.actions shouldBe listOf("end")
            }
        }
        run(CallControl.HangUp, FakePhoneCalls(PhoneCallState.RINGING)) shouldBe
            CallControlOutput.Declined
        run(CallControl.HangUp, FakePhoneCalls(PhoneCallState.IDLE)) shouldBe
            CallControlOutput.NotInCall
    }

    "a call Android won't end (an emergency call, Signal's) goes to the call screen" {
        run(CallControl.HangUp, FakePhoneCalls(PhoneCallState.IN_CALL, ends = false))
            .shouldBeInstanceOf<CallControlOutput.CantEnd>()
    }

    "the speaker can't be switched, so GAR offers the call screen" {
        for (command in listOf(CallControl.SpeakerOn, CallControl.SpeakerOff)) {
            val calls = FakePhoneCalls(PhoneCallState.IN_CALL)
            run(command, calls).shouldBeInstanceOf<CallControlOutput.Speaker>()
            calls.actions shouldBe listOf()
            run(command, FakePhoneCalls(PhoneCallState.IDLE)) shouldBe CallControlOutput.NotInCall
        }
    }

    "without the phone permissions Dicio's card asks for them" {
        run(CallControl.HangUp, FakePhoneCalls(PhoneCallState.UNKNOWN))
            .shouldBeInstanceOf<MissingPermissionsSkillOutput>()
        run(CallControl.Answer, FakePhoneCalls(PhoneCallState.RINGING, refuses = true))
            .shouldBeInstanceOf<MissingPermissionsSkillOutput>()
    }

    "a match only counts for one of the commands, whole" {
        val skill = skill(FakePhoneCalls(PhoneCallState.IDLE))
        for ((input, command) in listOf(
            "answer the call" to CallControl.Answer,
            "please hang up the phone" to CallControl.HangUp,
            "uh decline it" to CallControl.Decline,
            "put it on speaker now" to CallControl.SpeakerOn,
            "switch back to the earpiece" to CallControl.SpeakerOff,
        )) {
            withClue(input) {
                CallControlWords.command(input) shouldBe command
                skill.score(FakeSkillContext().forInput(input), input).second shouldBe command
            }
        }
        for (input in listOf("pick up milk", "end the meeting", "answer the question", "speaker volume")) {
            withClue(input) {
                CallControlWords.command(input) shouldBe null
                skill.score(FakeSkillContext().forInput(input), input).first shouldBe AlwaysWorstScore
            }
        }
    }
})
