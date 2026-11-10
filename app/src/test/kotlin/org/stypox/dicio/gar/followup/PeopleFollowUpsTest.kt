package org.stypox.dicio.gar.followup

import cc.aaro.gar.router.followup.CorrectionKind
import cc.aaro.gar.router.followup.FollowUpForm
import cc.aaro.gar.router.followup.PersonVerb
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.gar.followup.adapters.PeopleFollowUps
import org.stypox.dicio.gar.shouldBeTakenBy
import org.stypox.dicio.skills.contact_info.ContactField
import org.stypox.dicio.skills.contact_info.ContactInfoOutput
import org.stypox.dicio.skills.contact_info.ContactValue

/** A hand-made turn of [skillId] about [person], with [ownText] if the user sent one. */
private fun peopleTurn(skillId: String, person: GarPerson?, ownText: String? = null, ageMillis: Long = 1_000L) =
    GarRememberedTurn(
        GarTurn(skillId, skillId, mapOf(), GarFacts(person = person, ownText = ownText), "", GarTurnKind.ANSWER),
        PeopleFollowUps,
        ageMillis,
    )

private fun erin(unique: Boolean = true) = GarPerson("Erin", "555 0100", GarPersonSource.CONTACT, unique)

private fun person(verb: PersonVerb, vararg turns: GarRememberedTurn, locked: Boolean = false) =
    GarEntityCommands.person(FollowUpForm.AboutPerson(verb, back = false), FakeFollowUpContext(FakeView(turns.toList())), locked)

class PeopleFollowUpsTest : StringSpec({
    "what's erin's number remembers a unique contact" {
        resetMemory()
        val found = ContactInfoOutput.Found("Erin", ContactField.NUMBER, listOf(ContactValue("555 0100", null)), own = false)
        val turn = rememberedBy(PeopleFollowUps, answered("contact_info", "what's erin's number", null, found))
        turn.turn.facts.person shouldBe GarPerson("Erin", "555 0100", GarPersonSource.CONTACT, unique = true)
        // the owner's own number is nobody to call
        val own = ContactInfoOutput.Found("Me", ContactField.NUMBER, listOf(), own = true)
        PeopleFollowUps.remember(answered("contact_info", "what's my number", null, own))!!.facts.person.shouldBeNull()
    }

    "call her, text her and her number name the person" {
        val turn = peopleTurn("contact_info", erin())
        person(PersonVerb.CALL, turn).outcome shouldBe GarFollowUpOutcome.Rewritten("call erin")
        shouldBeTakenBy("call erin", "telephone")
        person(PersonVerb.TEXT, turn).outcome shouldBe GarFollowUpOutcome.Rewritten("send a text to erin")
        shouldBeTakenBy("send a text to erin", "sms")
        person(PersonVerb.NUMBER, peopleTurn("telephone", erin())).outcome shouldBe
            GarFollowUpOutcome.Rewritten("what's erin's number")
        shouldBeTakenBy("what's erin's number", "contact_info")
    }

    "two Erins, a name that splits a sentence, or nobody: GAR asks" {
        person(PersonVerb.CALL, peopleTurn("contact_info", erin(unique = false))).outcome shouldBe
            GarFollowUpOutcome.Ask(GarFollowUpQuestion.WHO_CALL, "call {answer}")
        val benAndJerry = GarPerson("Ben and Jerry", "555 0111", GarPersonSource.CONTACT, unique = true)
        person(PersonVerb.CALL, peopleTurn("contact_info", benAndJerry)).outcome
            .shouldBeInstanceOf<GarFollowUpOutcome.Ask>()
        person(PersonVerb.TEXT).outcome shouldBe GarFollowUpOutcome.Ask(GarFollowUpQuestion.WHO_TEXT, "send a text to {answer}")
        person(PersonVerb.NUMBER).outcome shouldBe GarFollowUpOutcome.Ask(GarFollowUpQuestion.WHOSE_NUMBER, "what's {answer}'s number")
        // more than two minutes ago
        person(PersonVerb.CALL, peopleTurn("contact_info", erin(), ageMillis = 3 * 60_000L)).outcome
            .shouldBeInstanceOf<GarFollowUpOutcome.Ask>()
    }

    "persons stay behind the lock" {
        person(PersonVerb.CALL, peopleTurn("contact_info", erin()), locked = true).outcome shouldBe
            GarFollowUpOutcome.Locked
    }

    "I said Erin corrects a call" {
        val call = peopleTurn("telephone", GarPerson("Aaron", "555 0102", GarPersonSource.CONTACT, true))
        rewriteOf(PeopleFollowUps, FollowUpForm.Correction("erin", CorrectionKind.SAID), call).command shouldBe "call erin"
        rewriteOf(PeopleFollowUps, FollowUpForm.Correction("ben and jerry", CorrectionKind.SAID), call).shouldBeNull()
    }

    "send it to mom too: only the user's own text, asked again when it doesn't fit a sentence" {
        val sent = peopleTurn("sms", GarPerson("Aaron", "555 0102", GarPersonSource.CONTACT, true), ownText = "I'm late")
        val rewrite = rewriteOf(PeopleFollowUps, FollowUpForm.SendTo("mom", too = true), sent).command
        rewrite shouldBe "send a text to mom saying i'm late"
        shouldBeTakenBy(rewrite!!, "sms")
        rewriteOf(PeopleFollowUps, FollowUpForm.Correction("erin", CorrectionKind.SAID), sent).command shouldBe
            "send a text to erin saying i'm late"
        // a body that would split the sentence
        // a text that would split the sentence: the sms skill asks for the message again
        val split = peopleTurn("sms", null, ownText = "meet me at 5 to talk")
        rewriteOf(PeopleFollowUps, FollowUpForm.SendTo("mom", too = false), split).command shouldBe "send a text to mom"
        // a text that didn't go is no own text
        val unsent = peopleTurn("sms", null, ownText = null)
        rewriteOf(PeopleFollowUps, FollowUpForm.SendTo("mom", too = false), unsent).shouldBeNull()
    }

    "an incoming message is not forwarded" {
        val read = GarRememberedTurn(
            BareFollowUps.remember(answered("messages", "read my messages", null, FakeOutput(null))),
            BareFollowUps,
            1_000L,
        )
        val match = GarFollowUpReach.resolve(
            FollowUpForm.SendTo("mom", too = false), FakeFollowUpContext(FakeView(listOf(read))), false, false,
        )
        match.outcome shouldBe GarFollowUpOutcome.Deferred("reach")
    }
})
