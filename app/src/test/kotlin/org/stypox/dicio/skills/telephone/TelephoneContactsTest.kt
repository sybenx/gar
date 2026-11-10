package org.stypox.dicio.skills.telephone

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.FakeSkillInfo
import org.stypox.dicio.gar.RecordingSkill
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.sms.FakeOwnContact
import org.stypox.dicio.skills.sms.OwnCard
import org.stypox.dicio.skills.sms.OwnContactAskOutput

private val YES_NO = Sentences.UtilYesNo["en"]!!

private fun telephone() = TelephoneSkill(TelephoneInfo, Sentences.Telephone["en"]!!, YES_NO)

private fun ConfirmCallOutput.field(name: String): String = ConfirmCallOutput::class.java
    .getDeclaredField(name).apply { isAccessible = true }.get(this) as String

private fun SkillOutput.nextSkills(): List<Skill<*>> =
    when (val plan = getInteractionPlan(FakeSkillContext())) {
        is InteractionPlan.StartSubInteraction -> plan.nextSkills
        is InteractionPlan.ReplaceSubInteraction -> plan.nextSkills
        else -> throw AssertionError("Unexpected plan $plan")
    }

/** GAR's lines in the telephone skill: whole words, no strangers offered, corrections, myself. */
class TelephoneContactsTest : StringSpec({
    "which one: a name matches by whole words, not by its letters" {
        val chooser = ContactChooserName(
            listOf(Pair("Katerina Book Club", "555 0132"), Pair("Mom", "555 0100")),
            YES_NO,
        )
        // seen on Aaron's phone: "no to erin" picked "Katerina Book Club"
        for (input in listOf("erin", "no to erin")) {
            withClue(input) {
                chooser.score(FakeSkillContext().forInput(input), input).first shouldBeSameInstanceAs
                    AlwaysWorstScore
            }
        }
        chooser.score(FakeSkillContext().forInput("katerina"), "katerina") shouldBe
            Pair(AlwaysBestScore, Pair("Katerina Book Club", "555 0132"))
    }

    "no, to Aaron in reply to which one looks Aaron up, a position after a no picks here" {
        val contacts = listOf(
            Pair("Sarah Connor", listOf("555 0101", "555 0102")),
            Pair("Sarah Lee", listOf("555 0103")),
        )
        val correction = TelephoneOutput(contacts, YES_NO, telephone()).nextSkills()
            .filterIsInstance<TelephoneCorrectionSkill>().single()
        fun score(input: String) = correction.score(FakeSkillContext().forInput(input), input)

        score("no to erin") shouldBe Pair(AlwaysBestScore, TelephoneCorrection.LookUp("erin"))
        score("no I meant mom") shouldBe Pair(AlwaysBestScore, TelephoneCorrection.LookUp("mom"))
        score("not her, dad") shouldBe Pair(AlwaysBestScore, TelephoneCorrection.LookUp("dad"))
        // Dicio's ContactChooserIndex would read "first": the position after "not" isn't wanted
        score("no not the first one the second one") shouldBe
            Pair(AlwaysBestScore, TelephoneCorrection.Pick("Sarah Connor", "555 0102"))
        score("no, the third one") shouldBe Pair(AlwaysBestScore, TelephoneCorrection.Pick("Sarah Lee", "555 0103"))
        score("not the first one") shouldBe Pair(AlwaysBestScore, TelephoneCorrection.Again)
        // a position alone is Dicio's, the conversation's stop phrases are left to it, and
        // anything else isn't a correction
        for (input in listOf(
            "the second one", "no", "no thanks", "two", "sarah lee", "cancel",
            "not now", "no forget it", "no, I'm good", "no, what did you say",
        )) {
            withClue(input) { score(input).first shouldBeSameInstanceAs AlwaysWorstScore }
        }
        correction.generateOutput(FakeSkillContext(), TelephoneCorrection.Pick("Sarah Lee", "555 0103"))
            .shouldBeInstanceOf<ConfirmCallOutput>().field("number") shouldBe "555 0103"
        correction.generateOutput(FakeSkillContext(), TelephoneCorrection.Again)
            .shouldBeInstanceOf<GarFollowUpOutput>().output.shouldBeInstanceOf<TelephoneOutput>()

        // a position alone still picks through Dicio's ContactChooserIndex
        val ranker = SkillRanker(listOf(), RecordingSkill(FakeSkillInfo("fallback")))
        ranker.addBatchToTop(TelephoneOutput(contacts, YES_NO, telephone()).nextSkills())
        val input = "the second one"
        val best = ranker.getBest(FakeSkillContext().forInput(input), input).shouldNotBeNull()
        best.skill.shouldBeInstanceOf<ContactChooserIndex>()
        best.generateOutput(FakeSkillContext()).shouldBeInstanceOf<ConfirmCallOutput>()
            .field("number") shouldBe "555 0102"

        // Dicio's own list, without the telephone skill to look up with
        TelephoneOutput(contacts, YES_NO).nextSkills().none { it is TelephoneCorrectionSkill } shouldBe true
    }

    "the contacts found: whole words, one is called, several are offered, none is said" {
        // seen on Aaron's phone: five strangers offered for "myself", "erin" found Katerina
        val strangers = listOf(
            Triple("Gabi (Plumber, Handyman, Locksmith)", -9, listOf("555 0130")),
            Triple("Myrtle Seller 27", -7, listOf("555 0131")),
            Triple("Katerina Book Club", -7, listOf("555 0132")),
            Triple("Joe's Work", -5, listOf("555 0133")),
            Triple("Melody Feed Store", -6, listOf("555 0134")),
        )
        fun choose(who: String, candidates: List<Triple<String, Int, List<String>>>, language: String = "en") =
            TelephoneContacts.choose(
                FakeSkillContext(sentencesLanguage = language), telephone(), who,
                candidates.sortedBy { it.second }, { it.first }, { it.second }, { it.third },
            )

        for (who in listOf("myself", "erin", "no to erin")) {
            withClue(who) { choose(who, strangers).shouldBeInstanceOf<TelephoneAskOutput>().notFound shouldBe who }
        }
        // a close single match is called, a spelling Vosk always writes too
        choose("gabby", strangers).shouldBeInstanceOf<ConfirmCallOutput>().field("name") shouldBe
            "Gabi (Plumber, Handyman, Locksmith)"
        choose("steven", listOf(Triple("Stephen", -8, listOf("555 0170")))).shouldBeInstanceOf<ConfirmCallOutput>()
            .field("number") shouldBe "555 0170"
        // several real matches are offered, with the correction skill
        val sarahs = listOf(Triple("Sarah Connor", -10, listOf("555 0101")), Triple("Sarah Lee", -10, listOf("555 0103")))
        choose("sarah", sarahs).shouldBeInstanceOf<TelephoneOutput>().nextSkills()
            .any { it is TelephoneCorrectionSkill } shouldBe true
        // other languages keep Dicio's "No contact found" and list, without GAR's English answers
        choose("erin", strangers, "it").shouldBeInstanceOf<TelephoneOutput>()
        choose("sarah", sarahs, "it").shouldBeInstanceOf<TelephoneOutput>().nextSkills()
            .none { it is TelephoneCorrectionSkill } shouldBe true
    }

    "a number said with the command is dialed as is, like the answer to who" {
        TelephoneContacts.directOutput(FakeSkillContext(), telephone(), "five five five one two one two") {
            error("no owner needed")
        }.shouldBeInstanceOf<ConfirmCallOutput>().field("number") shouldBe "5551212"
        TelephoneContacts.directOutput(FakeSkillContext(), telephone(), "mom") { error("no owner needed") }
            .shouldBeNull()
        TelephoneContacts.spokenName(FakeSkillContext(), "no, to mom") shouldBe "mom"
        TelephoneContacts.spokenName(FakeSkillContext(sentencesLanguage = "it"), "no, a mamma") shouldBe "no, a mamma"
    }

    "a name no contact has is said, and asked again" {
        val ask = TelephoneAskOutput(telephone(), "erin")
        ask.notFound shouldBe "erin"
        val plan = ask.getInteractionPlan(FakeSkillContext())
            .shouldBeInstanceOf<InteractionPlan.ReplaceSubInteraction>()
        plan.reopenMicrophone shouldBe true
        val answer = plan.nextSkills.single().shouldBeInstanceOf<TelephoneAnswerSkill>()
        answer.generateOutput(FakeSkillContext(), "cancel").shouldBeInstanceOf<ConfirmedCallOutput>()
        // a number spelled out digit by digit is dialed as is
        answer.generateOutput(FakeSkillContext(), "five five five one two one two")
            .shouldBeInstanceOf<ConfirmCallOutput>().field("number") shouldBe "5551212"

        for ((input, name) in listOf(
            "mom" to "mom",
            "call mom" to "mom",
            "to dad" to "dad",
            "no, to mom" to "mom",
            "Sarah Lee" to "Sarah Lee",
            "call" to "call",
        )) {
            withClue(input) { TelephoneAnswerSkill.recipientAnswer(input) shouldBe name }
        }
    }

    "call myself: the owner's number, or which contact is you" {
        val ctx = FakeSkillContext()
        // one number stored in two ways is one number
        val one = OwnCard("Aaron", listOf("435-555-0167", "+14355550167"), listOf())
        TelephoneContacts.ownOutput(ctx, telephone(), "myself") { FakeOwnContact(profile = one) }
            .shouldBeInstanceOf<ConfirmCallOutput>().let {
                it.field("name") shouldBe "Aaron"
                it.field("number") shouldBe "+14355550167"
            }
        val two = OwnCard("Aaron", listOf("435-555-0167", "+1 435 555 0168"), listOf())
        TelephoneContacts.ownOutput(ctx, telephone(), "me") { FakeOwnContact(profile = two) }
            .shouldBeInstanceOf<TelephoneOutput>()

        val ask = TelephoneContacts.ownOutput(ctx, telephone(), "my own number") { FakeOwnContact() }
            .shouldBeInstanceOf<OwnContactAskOutput>()
        ask.info shouldBe TelephoneInfo
        ask.answer(ctx, "cancel").shouldBeInstanceOf<ConfirmedCallOutput>()

        // anyone else, and other languages, are looked up as before
        TelephoneContacts.ownOutput(ctx, telephone(), "mom") { error("no owner needed") }.shouldBeNull()
        TelephoneContacts.ownOutput(FakeSkillContext(sentencesLanguage = "it"), telephone(), "me") {
            error("no owner needed")
        }.shouldBeNull()
    }
})
