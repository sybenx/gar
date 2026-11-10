package org.stypox.dicio.skills.calls

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.telephone.ConfirmCallOutput
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.telephone.TelephoneSkill
import java.time.Instant

/** The call log GAR reads, as a list. */
class FakeCallHistory(
    private val calls: List<CallEntry>,
    override val allowed: Boolean = true,
) : CallHistory {
    var reads = 0
        private set

    override fun recent(): List<CallEntry> {
        reads += 1
        return if (allowed) calls else listOf()
    }
}

/** 2026-10-27 18:00 UTC, a Tuesday. */
val CALLS_NOW: Instant = Instant.parse("2026-10-27T18:00:00Z")

fun callAt(
    minutesAgo: Long,
    kind: CallKind,
    name: String? = null,
    number: String = "8015550100",
    hidden: Boolean = false,
    isNew: Boolean = false,
) = CallEntry(
    number = if (hidden) "" else number,
    name = name,
    kind = kind,
    time = CALLS_NOW.minusSeconds(minutesAgo * 60),
    hidden = hidden,
    isNew = isNew,
)

/** Newest first, as the call log gives them. */
val SOME_CALLS = listOf(
    callAt(5, CallKind.OUTGOING, "Dad", "8015550101"),
    callAt(30, CallKind.MISSED, "Mom", "8015550199"), // Mom's work phone
    callAt(90, CallKind.INCOMING, null, "4355550123"),
    callAt(600, CallKind.OUTGOING, "Mom", "8015550100"),
    callAt(2000, CallKind.INCOMING, "John Smith", "8015550102"),
)

private fun telephone() = TelephoneSkill(
    TelephoneInfo, Sentences.Telephone["en"]!!, Sentences.UtilYesNo["en"]!!,
)

private fun ConfirmCallOutput.field(name: String): String = ConfirmCallOutput::class.java
    .getDeclaredField(name).apply { isAccessible = true }.get(this) as String

/** What the telephone skill's contact lookup was asked, instead of the contacts. */
private class ContactLookups {
    val names = mutableListOf<String>()
    val lookUp: suspend (String) -> SkillOutput = { name -> names.add(name); FakeOutput(name) }
}

private suspend fun output(
    target: CallTarget,
    history: CallHistory = FakeCallHistory(SOME_CALLS),
    locked: Boolean = false,
    lookups: ContactLookups = ContactLookups(),
    ctx: SkillContext = FakeSkillContext(),
): SkillOutput = CallBack.output(ctx, target, telephone(), history, locked, lookups.lookUp)

class CallBackTest : StringSpec({
    "what follows the verb points at a call of the call log" {
        val incoming = CallTarget.LastIncoming
        val outgoing = CallTarget.LastOutgoing
        val expected = mapOf(
            "back" to incoming,
            "them back" to incoming,
            "her back" to incoming,
            "it back" to incoming,
            "back the last number" to incoming,
            "back the last caller" to incoming,
            "the last caller" to incoming,
            "back my last missed call" to incoming,
            "whoever just called" to incoming,
            "whoever just called me back" to incoming,
            "the person who called me" to incoming,
            "the person who called me earlier" to incoming,
            "whoever rang me a minute ago" to incoming,
            "the last person who called" to incoming,
            "the number that just called" to incoming,
            "last caller" to incoming,
            "my missed call" to incoming,
            "that number back" to incoming,
            "redial" to outgoing,
            "re dial" to outgoing,
            "redial it" to outgoing,
            "redial the last number" to outgoing,
            "again" to outgoing,
            "them again" to outgoing,
            "the last number again" to outgoing,
            "that number again" to outgoing,
            "the last number i dialed" to outgoing,
            "the last person i called" to outgoing,
            "the last number" to CallTarget.Last,
            "my last call" to CallTarget.Last,
            "the most recent number" to CallTarget.Last,
            "that number" to CallTarget.Last,
            "last number" to CallTarget.Last,
            "mom back" to CallTarget.Named("mom", cameIn = true),
            "back mom" to CallTarget.Named("mom", cameIn = true),
            "Mom back" to CallTarget.Named("mom", cameIn = true),
            "john smith back" to CallTarget.Named("john smith", cameIn = true),
            "mom again" to CallTarget.Named("mom", cameIn = false),
            "redial mom" to CallTarget.Named("mom", cameIn = false),
            "back five five five one two one two" to
                CallTarget.Named("five five five one two one two", cameIn = true),
        )
        for ((who, target) in expected) {
            withClue(who) { CallBack.parse(who) shouldBe target }
        }
    }

    "the model's who values of the eval's call backs point at the call log" {
        // gar/eval/cases.yml, gar:calls: call_contact's accepted values
        for (who in listOf(
            "the last caller", "last caller", "back", "my missed call",
            "whoever rang me a minute ago", "whoever rang me", "back the person who called earlier",
            "the person who called earlier",
        )) {
            withClue(who) { CallBack.parse(who) shouldBe CallTarget.LastIncoming }
        }
        CallBack.parse("my last number") shouldBe CallTarget.Last
    }

    "contacts, numbers and times stay with the telephone skill" {
        for (who in listOf(
            "mom", "john smith", "five five five one two one two", "the dentist", "", "  ",
            "back in five minutes", "back later", "back tomorrow morning", "me back", "them",
            "redial the dentist tomorrow", "mom back at five",
        )) {
            withClue(who) { CallBack.parse(who) shouldBe null }
        }
    }

    "call back calls the newest call that came in, with the telephone skill's confirmation" {
        val confirm = output(CallTarget.LastIncoming).shouldBeInstanceOf<ConfirmCallOutput>()
        confirm.field("name") shouldBe "Mom"
        // the number Mom called from, not the one in her contact
        confirm.field("number") shouldBe "8015550199"
    }

    "redial calls the newest call the user made" {
        val confirm = output(CallTarget.LastOutgoing).shouldBeInstanceOf<ConfirmCallOutput>()
        confirm.field("name") shouldBe "Dad"
        confirm.field("number") shouldBe "8015550101"
    }

    "the last number is the newest call either way" {
        output(CallTarget.Last).shouldBeInstanceOf<ConfirmCallOutput>()
            .field("number") shouldBe "8015550101"
    }

    "call mom back calls the number she last called from" {
        output(CallTarget.Named("mom", cameIn = true)).shouldBeInstanceOf<ConfirmCallOutput>()
            .field("number") shouldBe "8015550199"
        output(CallTarget.Named("john", cameIn = true)).shouldBeInstanceOf<ConfirmCallOutput>()
            .field("number") shouldBe "8015550102"
        // "return moms call": the apostrophe is gone
        output(CallTarget.Named("moms", cameIn = true)).shouldBeInstanceOf<ConfirmCallOutput>()
            .field("number") shouldBe "8015550199"
        // "call mom again": the number the user dialed
        output(CallTarget.Named("mom", cameIn = false)).shouldBeInstanceOf<ConfirmCallOutput>()
            .field("number") shouldBe "8015550100"
    }

    "a name the call log doesn't have goes to the contacts" {
        val lookups = ContactLookups()
        output(CallTarget.Named("grandma", cameIn = true), lookups = lookups)
            .shouldBeInstanceOf<FakeOutput>()
        // Dad never called: "call dad back" calls him as "call dad" would
        output(CallTarget.Named("dad", cameIn = true), lookups = lookups)
            .shouldBeInstanceOf<FakeOutput>()
        lookups.names shouldBe listOf("grandma", "dad")
    }

    "a spoken number is called as said" {
        val history = FakeCallHistory(SOME_CALLS)
        output(CallTarget.Named("five five five one two one two", cameIn = true), history)
            .shouldBeInstanceOf<ConfirmCallOutput>().field("number") shouldBe "5551212"
        history.reads shouldBe 0
    }

    "a locked phone keeps the call log closed" {
        val history = FakeCallHistory(SOME_CALLS)
        for (target in listOf(CallTarget.LastIncoming, CallTarget.LastOutgoing, CallTarget.Last)) {
            withClue(target) {
                output(target, history, locked = true).shouldBeInstanceOf<GarLockedOutput>()
            }
        }
        // a name is looked up in the contacts, as the telephone skill always did
        val lookups = ContactLookups()
        output(CallTarget.Named("mom", cameIn = true), history, locked = true, lookups = lookups)
            .shouldBeInstanceOf<FakeOutput>()
        lookups.names shouldBe listOf("mom")
        history.reads shouldBe 0
    }

    "without the permission Dicio's card asks for it" {
        val history = FakeCallHistory(SOME_CALLS, allowed = false)
        output(CallTarget.LastIncoming, history).shouldBeInstanceOf<MissingPermissionsSkillOutput>()
        val lookups = ContactLookups()
        output(CallTarget.Named("mom", cameIn = true), history, lookups = lookups)
            .shouldBeInstanceOf<FakeOutput>()
        lookups.names shouldBe listOf("mom")
    }

    "nothing to call, and a hidden number" {
        output(CallTarget.LastIncoming, FakeCallHistory(listOf())) shouldBe
            CallLogOutput.NothingToCall(CallTarget.LastIncoming)
        output(CallTarget.LastOutgoing, FakeCallHistory(listOf(callAt(1, CallKind.MISSED, "Mom")))) shouldBe
            CallLogOutput.NothingToCall(CallTarget.LastOutgoing)
        output(
            CallTarget.LastIncoming,
            FakeCallHistory(listOf(callAt(1, CallKind.MISSED, hidden = true)) + SOME_CALLS),
        ) shouldBeSameInstanceAs CallLogOutput.HiddenNumber
    }
})
