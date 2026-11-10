package org.stypox.dicio.skills.calls

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Permission
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.telephone.ConfirmCallOutput
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.telephone.TelephoneSkill
import org.stypox.dicio.util.PERMISSION_CALL_PHONE
import org.stypox.dicio.util.PERMISSION_READ_CONTACTS
import java.time.ZoneOffset

private fun skill(
    history: CallHistory,
    locked: Boolean = false,
    granted: (Permission) -> Boolean = { true },
) = CallLogSkill(
    CallLogInfo,
    Sentences.CallLog["en"]!!,
    TelephoneSkill(TelephoneInfo, Sentences.Telephone["en"]!!, Sentences.UtilYesNo["en"]!!),
    history = { history },
    isLocked = { locked },
    now = { CALLS_NOW.atZone(ZoneOffset.UTC) },
    granted = { _, permission -> granted(permission) },
)

class CallLogSkillTest : StringSpec({
    "who called me: the newest call that came in, with the latest calls" {
        val output = skill(FakeCallHistory(SOME_CALLS))
            .generateOutput(FakeSkillContext(), CallLogRequest.LastCaller)
            .shouldBeInstanceOf<CallLogOutput.LastCaller>()
        output.call shouldBe SOME_CALLS[1]
        output.recent shouldBe SOME_CALLS
        skill(FakeCallHistory(listOf(callAt(1, CallKind.OUTGOING, "Dad"))))
            .generateOutput(FakeSkillContext(), CallLogRequest.LastCaller)
            .shouldBeInstanceOf<CallLogOutput.LastCaller>().call shouldBe null
    }

    "did I miss any calls: the missed calls of the last day and the new ones" {
        val calls = listOf(
            callAt(10, CallKind.MISSED, "Mom"),
            callAt(60 * 30, CallKind.MISSED, "Ann"),
            callAt(60 * 40, CallKind.MISSED, "Zoe", isNew = true),
        )
        val output = skill(FakeCallHistory(calls))
            .generateOutput(FakeSkillContext(), CallLogRequest.Missed)
            .shouldBeInstanceOf<CallLogOutput.Missed>()
        output.missed.map { it.name } shouldBe listOf("Mom", "Zoe")
        output.summary.count shouldBe 2
    }

    "call backs go through the telephone skill's confirmation" {
        skill(FakeCallHistory(SOME_CALLS))
            .generateOutput(FakeSkillContext(), CallLogRequest.Call(CallTarget.LastIncoming))
            .shouldBeInstanceOf<ConfirmCallOutput>()
    }

    "the call log stays closed on a locked phone" {
        val history = FakeCallHistory(SOME_CALLS)
        for (request in listOf(
            CallLogRequest.LastCaller, CallLogRequest.Missed, CallLogRequest.Call(CallTarget.Last),
        )) {
            withClue(request) {
                skill(history, locked = true).generateOutput(FakeSkillContext(), request)
                    .shouldBeInstanceOf<GarLockedOutput>()
            }
        }
        history.reads shouldBe 0
    }

    "without the permission Dicio's card asks for it" {
        skill(FakeCallHistory(SOME_CALLS, allowed = false))
            .generateOutput(FakeSkillContext(), CallLogRequest.Missed)
            .shouldBeInstanceOf<MissingPermissionsSkillOutput>()
    }

    "the questions need only the call log; calling back asks for the telephone's permissions" {
        CallLogInfo.neededPermissions shouldBe listOf(PERMISSION_READ_CALL_LOG)
        val noCalls = skill(FakeCallHistory(SOME_CALLS)) { it != PERMISSION_CALL_PHONE }
        noCalls.generateOutput(FakeSkillContext(), CallLogRequest.LastCaller)
            .shouldBeInstanceOf<CallLogOutput.LastCaller>()
        noCalls.generateOutput(FakeSkillContext(), CallLogRequest.Call(CallTarget.LastIncoming))
            .shouldBeInstanceOf<MissingPermissionsSkillOutput>()
        // a name the call log doesn't have goes to the contacts, which need their permission
        val noContacts = skill(FakeCallHistory(SOME_CALLS)) { it != PERMISSION_READ_CONTACTS }
        noContacts
            .generateOutput(FakeSkillContext(), CallLogRequest.Call(CallTarget.Named("grandma", cameIn = true)))
            .shouldBeInstanceOf<MissingPermissionsSkillOutput>()
        noContacts.generateOutput(FakeSkillContext(), CallLogRequest.Call(CallTarget.LastIncoming))
            .shouldBeInstanceOf<ConfirmCallOutput>()
    }

    "did mom call me: the newest call from her, or none" {
        val calls = listOf(
            callAt(10, CallKind.OUTGOING, "Mom"),
            callAt(60, CallKind.MISSED, "Mom"),
            callAt(120, CallKind.INCOMING, "Dad"),
        )
        skill(FakeCallHistory(calls)).generateOutput(FakeSkillContext(), CallLogRequest.From("mom"))
            .shouldBeInstanceOf<CallLogOutput.From>().call shouldBe calls[1]
        skill(FakeCallHistory(calls)).generateOutput(FakeSkillContext(), CallLogRequest.From("grandma"))
            .shouldBeInstanceOf<CallLogOutput.From>().call shouldBe null
        skill(FakeCallHistory(calls), locked = true)
            .generateOutput(FakeSkillContext(), CallLogRequest.From("mom"))
            .shouldBeInstanceOf<GarLockedOutput>()
    }

    "the words of each request" {
        val expected = mapOf(
            "who called me" to CallLogRequest.LastCaller,
            "Who called me?" to CallLogRequest.LastCaller,
            "who's been calling me" to CallLogRequest.LastCaller,
            "who just called" to CallLogRequest.LastCaller,
            "who called me a few minutes ago" to CallLogRequest.LastCaller,
            "what was the last call" to CallLogRequest.LastCaller,
            "did i miss any calls" to CallLogRequest.Missed,
            "Did I miss any calls today?" to CallLogRequest.Missed,
            "do i have missed calls" to CallLogRequest.Missed,
            "has anybody tried to call me" to CallLogRequest.Missed,
            "any calls for me" to CallLogRequest.Missed,
            "who did i miss" to CallLogRequest.Missed,
            "redial" to CallLogRequest.Call(CallTarget.LastOutgoing),
            "redial please" to CallLogRequest.Call(CallTarget.LastOutgoing),
            "call back" to CallLogRequest.Call(CallTarget.LastIncoming),
            "phone them back" to CallLogRequest.Call(CallTarget.LastIncoming),
            "return the call" to CallLogRequest.Call(CallTarget.LastIncoming),
            "return call" to CallLogRequest.Call(CallTarget.LastIncoming),
            "return my last missed call" to CallLogRequest.Call(CallTarget.LastIncoming),
            "return mom's call" to CallLogRequest.Call(CallTarget.Named("moms", cameIn = true)),
            "call the last number" to CallLogRequest.Call(CallTarget.Last),
            "call mom back" to CallLogRequest.Call(CallTarget.Named("mom", cameIn = true)),
            "did mom call me" to CallLogRequest.From("mom"),
            "did mom call" to CallLogRequest.From("mom"),
            "has mom called today" to CallLogRequest.From("mom"),
            "did dad try to call me" to CallLogRequest.From("dad"),
            "did my sister call me back" to CallLogRequest.From("sister"),
            "did i miss a call from mom" to CallLogRequest.From("mom"),
            "any calls from john smith" to CallLogRequest.From("john smith"),
            "missed call from mom" to CallLogRequest.From("mom"),
            "when did mom last call" to CallLogRequest.From("mom"),
        )
        for ((input, request) in expected) {
            withClue(input) { CallLogWords.request(input) shouldBe request }
        }
        for (input in listOf(
            "call mom", "who called the plumber", "call back in five minutes", "what did i miss",
            "did i miss the bus", "who is calling the shots", "return the book", "missed call",
            "call me back", "did anyone feed the cat", "did you call mom", "did he call",
            "did mom call in five minutes",
        )) {
            withClue(input) { CallLogWords.request(input) shouldBe null }
        }
    }

    "a match only counts for the request its sentence is" {
        val skill = skill(FakeCallHistory(SOME_CALLS))
        for (input in listOf("who called the plumber", "call back in five minutes", "call mom")) {
            withClue(input) {
                skill.score(FakeSkillContext().forInput(input), input).first shouldBe AlwaysWorstScore
            }
        }
        val input = "did i miss any calls"
        skill.score(FakeSkillContext().forInput(input), input).second shouldBe CallLogRequest.Missed
    }
})
