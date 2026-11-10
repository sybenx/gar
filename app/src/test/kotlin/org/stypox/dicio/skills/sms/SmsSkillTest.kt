package org.stypox.dicio.skills.sms

import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.FakeSkillInfo
import org.stypox.dicio.gar.RecordingSkill
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarCallRunner
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.gar.fakeResolver
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.util.StringUtils
import java.io.File
import java.util.Locale

private fun sourceFile(path: String): String =
    sequenceOf(File(path), File("app", path)).first { it.isFile }.readText()

/** Contacts in memory, searched like the phone's contacts by [SmsRecipients.findInContacts]. */
private class FakeContacts(vararg contacts: SmsContact) {
    val contacts = contacts.toList()
    val numberLookups = mutableListOf<String>()

    fun find(spoken: String, splitMessage: Boolean): Pair<RecipientChoice, String?> =
        SmsRecipients.find(
            spoken = spoken,
            splitMessage = splitMessage,
            // like Contact.getFilteredSortedContacts: contacts that share some letters, by distance
            search = { name ->
                contacts.map { Pair(it, StringUtils.contactStringDistance(it.name, name)) }
                    .filter { it.second < 0 }
                    .sortedBy { it.second }
            },
            name = { it.first.name },
            distance = { it.second },
            numbers = { numberLookups.add(it.first.name); it.first.numbers },
        )
}

/** @param report what the network says about each text right away, or null to wait */
private class FakeSender(
    var allowed: Boolean = true,
    var throwOnSend: Boolean = false,
    var hasApp: Boolean = true,
    var report: SendResult? = null,
) : SmsSender {
    val sent = mutableListOf<Pair<String, String>>()
    val opened = mutableListOf<Pair<String, String>>()
    var onResult: ((SendResult) -> Unit)? = null

    override fun canSendDirectly(): Boolean = allowed

    override fun send(number: String, message: String, onResult: (SendResult) -> Unit) {
        if (throwOnSend) {
            throw IllegalStateException("no service")
        }
        sent.add(Pair(number, message))
        this.onResult = onResult
        report?.let(onResult)
    }

    override fun openSmsApp(number: String, message: String): Boolean {
        opened.add(Pair(number, message))
        return hasApp
    }
}

private val MOM = SmsContact("Mom", listOf("+1 555 0100"))
private val DAD = SmsContact("Dad", listOf("+1 555 0111"))
private val JOHN = SmsContact("John", listOf("555 0120"))
private val SARAH_CONNOR = SmsContact("Sarah Connor", listOf("555 0101", "555 0102"))
private val SARAH_LEE = SmsContact("Sarah Lee", listOf("555 0103"))
private val NO_NUMBER = SmsContact("Grandma", listOf())

private fun contacts() = FakeContacts(MOM, DAD, JOHN, SARAH_CONNOR, SARAH_LEE, NO_NUMBER)

private fun flow(
    contacts: FakeContacts = contacts(),
    sender: SmsSender = FakeSender(),
    locked: Boolean = false,
    own: OwnContact = FakeOwnContact(),
) = SmsFlow(
    findRecipient = { _, spoken, split -> contacts.find(spoken, split) },
    senderFor = { sender },
    isDeviceLocked = { locked },
    ownContact = { own },
)

/** Stand-ins for the five contacts a phone offered for "send a text to myself", none the owner. */
private val STRANGERS = listOf(
    SmsContact("Gabi (Plumber, Handyman, Locksmith)", listOf("555 0130")),
    SmsContact("Myrtle Seller 27", listOf("555 0131")),
    SmsContact("Katerina Book Club", listOf("555 0132")),
    SmsContact("Joe's Work", listOf("555 0133")),
    SmsContact("Melody Feed Store", listOf("555 0134")),
)

private fun SkillOutput.nextSkills(): List<Skill<*>> =
    when (val plan = getInteractionPlan(FakeSkillContext())) {
        is InteractionPlan.StartSubInteraction -> plan.nextSkills
        is InteractionPlan.ReplaceSubInteraction -> plan.nextSkills
        else -> throw AssertionError("Unexpected plan $plan")
    }

/** Changes the stack of batches like SkillEvaluator does after an output. */
private fun SkillRanker.apply(plan: InteractionPlan) {
    when (plan) {
        InteractionPlan.FinishInteraction -> removeAllBatches()
        is InteractionPlan.FinishSubInteraction -> removeTopBatch()
        is InteractionPlan.Continue -> {}
        is InteractionPlan.StartSubInteraction -> addBatchToTop(plan.nextSkills)
        is InteractionPlan.ReplaceSubInteraction -> {
            removeTopBatch()
            addBatchToTop(plan.nextSkills)
        }
    }
}

/** A stack of batches with [output]'s follow-up skills on top and nothing below. */
private fun rankerAfter(output: SkillOutput): SkillRanker =
    SkillRanker(listOf(), RecordingSkill(FakeSkillInfo("fallback")))
        .also { it.apply(output.getInteractionPlan(FakeSkillContext())) }

/** Evaluates [input] against the stack like SkillEvaluator, and applies the output's plan. */
private suspend fun SkillRanker.evaluate(input: String): SkillOutput? {
    val ctx = FakeSkillContext().forInput(input)
    val output = getBest(ctx, input)?.generateOutput(ctx) ?: return null
    apply(output.getInteractionPlan(ctx))
    return output
}

/** Answers [output]'s question with [input] through its only follow-up skill. */
private suspend fun answer(output: SkillOutput, input: String): SkillOutput =
    rankerAfter(output).evaluate(input) ?: throw AssertionError("'$input' was not understood")

class SmsSkillTest : StringSpec({
    "messages are cleaned up as speech recognition writes them" {
        SmsMessage.assemble("i'm on my way") shouldBe "I'm on my way"
        SmsMessage.assemble("see you when i get there") shouldBe "See you when I get there"
        SmsMessage.assemble("saying i'll be late") shouldBe "I'll be late"
        SmsMessage.assemble("that the meeting is canceled") shouldBe "The meeting is canceled"
        SmsMessage.assemble("that says see you at five") shouldBe "See you at five"
        SmsMessage.assemble("  and tell her   happy birthday ") shouldBe "Happy birthday"
        SmsMessage.assemble("it's fine, isn't it?") shouldBe "It's fine, isn't it?"
        SmsMessage.assemble("Typed With Care.") shouldBe "Typed With Care."
        SmsMessage.assemble("saying").shouldBeNull()
        SmsMessage.assemble("   ").shouldBeNull()
        SmsMessage.assemble(null).shouldBeNull()
    }

    "a message the model made up from the command words is dropped" {
        SmsMessage.fromModel("my wife", "send a text to my wife").shouldBeNull()
        SmsMessage.fromModel("dentist", "text message to the dentist").shouldBeNull()
        SmsMessage.fromModel("alex", "message").shouldBeNull()
        SmsMessage.fromModel("message", "text message").shouldBeNull()
        SmsMessage.fromModel("dad", null).shouldBeNull()
        SmsMessage.fromModel("mom", "i'm on my way") shouldBe "i'm on my way"
        SmsMessage.fromModel("john", "send the report to sarah") shouldBe "send the report to sarah"
        SmsMessage.fromModel(null, "hi") shouldBe "hi"
    }

    "phone numbers are texted as is, spelled out or typed" {
        SmsRecipients.phoneNumber("five five five one two one two") shouldBe "5551212"
        SmsRecipients.phoneNumber("plus four four seven seven") shouldBe "+4477"
        SmsRecipients.phoneNumber("+1 (555) 010-0123") shouldBe "+1 (555) 010-0123"
        SmsRecipients.phoneNumber("mom").shouldBeNull()
        SmsRecipients.phoneNumber("one two").shouldBeNull()

        val contacts = contacts()
        contacts.find("five five five one two one two", false).first shouldBe
                RecipientChoice.One(SmsRecipient("5551212", "5551212"))
        contacts.numberLookups.shouldBeEmpty()
        SmsRecipient("5551212", "5551212").isContact shouldBe false
    }

    "a contact with one number is chosen directly, also when misheard a little" {
        val contacts = contacts()
        contacts.find("mom", false).first shouldBe RecipientChoice.One(SmsRecipient("Mom", "+1 555 0100"))
        contacts.find("jon", false).first shouldBe RecipientChoice.One(SmsRecipient("John", "555 0120"))
        // only the matched contact's numbers are read
        contacts.numberLookups shouldContainExactly listOf("Mom", "John")
    }

    "several matching contacts or numbers are offered to choose from" {
        contacts().find("sarah", false).first shouldBe
                RecipientChoice.Several(listOf(SARAH_CONNOR, SARAH_LEE))
        contacts().find("sarah connor", false).first shouldBe
                RecipientChoice.Several(listOf(SARAH_CONNOR))
    }

    "one number stored in different ways is offered once, in its international form" {
        // seen on Aaron's phone: "1. 435-555-0167 2. +14355550167"
        val aaron = SmsContact("Aaron", listOf("435-555-0167", "+14355550167"))
        FakeContacts(aaron, MOM).find("aaron", false).first shouldBe
                RecipientChoice.One(SmsRecipient("Aaron", "+14355550167"))
        SmsNumbers.distinct(aaron.numbers) shouldContainExactly listOf("+14355550167")

        for ((a, b) in listOf(
            "435-555-0167" to "+14355550167",
            "+1 435 555 0167" to "(435) 555-0167",
            "435.555.0167" to "1-435-555-0167",
            "1 435 555 0167" to "+1-435-555-0167",
            "07700 900123" to "+44 7700 900123",
            "+44 7700 900123" to "0044 7700 900123",
            "06 1234 5678" to "+39 06 1234 5678",
            "435 555 0167" to "+353 435 555 0167",
        )) {
            withClue("$a, $b") {
                SmsNumbers.same(a, b) shouldBe true
                SmsNumbers.same(b, a) shouldBe true
            }
        }
        for ((a, b) in listOf(
            "+1 435 555 0167" to "+1 435 555 0168",
            "435-555-0167" to "435-555-0168",
            "+1 435 555 0167" to "+44 435 555 0167",
            "212 555 0100" to "555 0100", // an area code is no country code
            "+1 435 555 0167" to "+1234 435 555 0167",
            "+44 5050" to "5050 5050",
            "112" to "+1 112",
        )) {
            withClue("$a, $b") {
                SmsNumbers.same(a, b) shouldBe false
                SmsNumbers.same(b, a) shouldBe false
            }
        }

        // the complete form is kept as stored, in the order the numbers first appear
        SmsNumbers.distinct(listOf("435 555 0167", "555 0100", "1 (435) 555-0167", "+1 435-555-0167")) shouldContainExactly
                listOf("+1 435-555-0167", "555 0100")
        SmsNumbers.distinct(listOf("435 555 0167", "1 (435) 555-0167")) shouldContainExactly
                listOf("1 (435) 555-0167")
        // a national form doesn't chain two different international numbers into one
        SmsNumbers.distinct(listOf("435 555 0167", "+1 435 555 0167", "+44 435 555 0167")) shouldContainExactly
                listOf("+1 435 555 0167", "+44 435 555 0167")
        // really different numbers are still asked about
        val twoNumbers = SmsContact("Aaron", listOf("435-555-0167", "+1 435 555 0168", "+14355550167"))
        FakeContacts(twoNumbers).find("aaron", false).first shouldBe
                RecipientChoice.Several(listOf(SmsContact("Aaron", listOf("+14355550167", "+1 435 555 0168"))))
    }

    "contacts without numbers are skipped, and nothing found is reported" {
        FakeContacts(NO_NUMBER).find("grandma", false).first shouldBe RecipientChoice.NotFound
        FakeContacts().find("mom", false).first shouldBe RecipientChoice.NotFound
        FakeContacts(MOM).find("   ", false).first shouldBe RecipientChoice.NotFound
    }

    "without a name match, the telephone skill's choice by distance is used, by whole words" {
        // made-up distances: lower is better, as Dicio's contactStringDistance
        val near = Pair("Samantha", -30)
        val far = Pair("Samuel", -20)
        val choose = { spoken: String, candidates: List<Pair<String, Int>> ->
            SmsRecipients.choose(spoken, candidates, { it.first }, { it.second }) { listOf("1") }
        }
        // a clear best match with one number is taken directly
        choose("sam", listOf(near, far)) shouldBe RecipientChoice.One(SmsRecipient("Samantha", "1"))
        // a close second makes it a choice
        choose("sam", listOf(near, Pair("Samuel", -29))) shouldBe RecipientChoice.Several(
            listOf(SmsContact("Samantha", listOf("1")), SmsContact("Samuel", listOf("1")))
        )
        // at most MAX_CONTACTS
        val many = (1..9).map { Pair("Sammy $it", -10) }
        (choose("sam", many) as RecipientChoice.Several).contacts.size shouldBe SmsRecipients.MAX_CONTACTS
        // a contact that only has the letters is no match, however close its distance
        choose("qqq", listOf(near, far)) shouldBe RecipientChoice.NotFound
        choose("erin", listOf(Pair("Katerina Book Club", -30))) shouldBe RecipientChoice.NotFound
    }

    "no contact is offered that doesn't match: I couldn't find it, who should I text?" {
        // seen on Aaron's phone: these five were offered for "myself", and "erin" picked Katerina
        val strangers = FakeContacts(*STRANGERS.toTypedArray())
        for (spoken in listOf("erin", "myself", "aaron", "no to erin", "my brother")) {
            withClue(spoken) { strangers.find(spoken, false).first shouldBe RecipientChoice.NotFound }
        }
        strangers.numberLookups.shouldBeEmpty()
        flow(strangers).start(FakeSkillContext(), "erin", "hi").shouldBeInstanceOf<SmsAskOutput>().let {
            it.question shouldBe SmsQuestion.RECIPIENT
            it.notFound shouldBe "erin"
            it.message shouldBe "Hi"
        }

        // whole words still match, and so do small mishearings and splits
        strangers.find("joe", false).first shouldBe RecipientChoice.One(SmsRecipient("Joe's Work", "555 0133"))
        strangers.find("katerina", false).first shouldBe
                RecipientChoice.One(SmsRecipient("Katerina Book Club", "555 0132"))
        strangers.find("melody feed", false).first shouldBe
                RecipientChoice.One(SmsRecipient("Melody Feed Store", "555 0134"))
        val saraLee = SmsContact("Sara-Lee", listOf("555 0135"))
        FakeContacts(saraLee, JOHN).find("sara lee", false).first shouldBe
                RecipientChoice.One(SmsRecipient("Sara-Lee", "555 0135"))
        FakeContacts(saraLee, JOHN).find("jon", false).first shouldBe
                RecipientChoice.One(SmsRecipient("John", "555 0120"))
        // several real matches are still asked about
        contacts().find("sarah", false).first shouldBe RecipientChoice.Several(listOf(SARAH_CONNOR, SARAH_LEE))
    }

    "contact words: the owner, a correction and whole-word matches" {
        for (spoken in listOf(
            "myself", "me", "Me.", "my self", "my own number", "my number", "my phone", "my cell",
        )) {
            withClue(spoken) { ContactWords.ownRest(spoken) shouldBe "" }
        }
        ContactWords.ownRest("myself buy milk") shouldBe "buy milk"
        ContactWords.ownRest("me i'm running late") shouldBe "i'm running late"
        for (spoken in listOf("mom", "my wife", "my phone company", "melissa", "myrtle seller", "", "text me")) {
            withClue(spoken) { ContactWords.ownRest(spoken).shouldBeNull() }
        }
        ContactWords.isOwn("myself") shouldBe true
        ContactWords.isOwn("myself buy milk") shouldBe false

        for ((input, name) in listOf(
            "no to erin" to "erin",
            "No, to Aaron." to "Aaron",
            "no I meant mom" to "mom",
            "not him, dad" to "dad",
            "nope, Sarah Lee" to "Sarah Lee",
            "no no no send a text aaron" to "aaron",
            "no, the second one" to "second one",
            "no wait, call my wife" to "my wife",
            "no, myself" to "myself",
            // who it isn't is left out: a position, up to a comma, a pronoun, else one word
            "no, not the first one, the second one" to "second one",
            "no not the first one the second one" to "second one",
            "no the second one not the first" to "second one",
            "not him dad" to "dad",
            "not mom dad" to "dad",
        )) {
            withClue(input) { ContactWords.correction(input) shouldBe name }
        }
        // a whole name of the list offered, as Vosk writes it without commas
        val sarahs = listOf("Sarah Lee", "Sarah Connor")
        ContactWords.correction("not sarah lee sarah connor", sarahs) shouldBe "sarah connor"
        for (input in listOf(
            "no", "nope", "no thanks", "no thank you", "no don't", "no, that one", "mom", "Noah",
            "the second one", "never mind", "nobody", "",
            // the conversation's stop and repeat phrases, and no one named
            "no forget it", "no, forget it", "not now", "no I'm good", "no, that's it",
            "no, none of them", "no, what did you say", "not the first one", "no, not him",
        )) {
            withClue(input) { ContactWords.correction(input).shouldBeNull() }
        }
        for (input in listOf("not the first one", "no, not him", "not mom", "no not the last one")) {
            withClue(input) { ContactWords.rejectsOnly(input) shouldBe true }
        }
        for (input in listOf("no thanks", "not now", "no", "no, the second one", "not mom dad")) {
            withClue(input) { ContactWords.rejectsOnly(input) shouldBe false }
        }
        ContactWords.corrected("mom") shouldBe "mom"

        ContactWords.matches("jon", "John") shouldBe true
        ContactWords.matches("sara lee", "Sara-Lee") shouldBe true
        ContactWords.matches("de shawn", "DeShawn") shouldBe true
        ContactWords.matches("sam", "Samantha Jones") shouldBe true
        ContactWords.matches("erin", "Katerina Book Club") shouldBe false
        ContactWords.matches("myself", "Myrtle Seller 27") shouldBe false
    }

    "text myself: the owner's card" {
        val card = OwnCard("Alex Herbert", listOf("435-555-0167", "+14355550167"), listOf())
        for (who in listOf("myself", "me", "my own number", "my phone")) {
            withClue(who) {
                flow(own = FakeOwnContact(profile = card)).start(FakeSkillContext(), who, "buy milk")
                    .shouldBeInstanceOf<SmsCardOutput>().draft.let {
                        it.recipient shouldBe SmsRecipient("Alex Herbert", "+14355550167")
                        it.message shouldBe "Buy milk"
                    }
            }
        }
        // "text myself buy milk" captures it all as the recipient
        flow(own = FakeOwnContact(profile = card)).start(FakeSkillContext(), "myself buy milk", null)
            .shouldBeInstanceOf<SmsCardOutput>().draft.message shouldBe "Buy milk"
        // without the message, it is asked for
        flow(own = FakeOwnContact(profile = card)).start(FakeSkillContext(), "me", null)
            .shouldBeInstanceOf<SmsAskOutput>().let {
                it.question shouldBe SmsQuestion.MESSAGE
                it.recipient shouldBe SmsRecipient("Alex Herbert", "+14355550167")
            }
        // two really different numbers: which one
        val twoNumbers = OwnCard("Aaron", listOf("435-555-0167", "+1 435 555 0168"), listOf())
        flow(own = FakeOwnContact(profile = twoNumbers)).start(FakeSkillContext(), "myself", "hi")
            .shouldBeInstanceOf<SmsChooseOutput>().contacts shouldBe
                listOf(SmsContact("Aaron", listOf("435-555-0167", "+1 435 555 0168")))
        // "send it to me instead" on the read-back
        val toMom = flow(own = FakeOwnContact(profile = card)).start(FakeSkillContext(), "mom", "hi")
            .shouldBeInstanceOf<SmsCardOutput>()
        answer(toMom, "send it to me instead").shouldBeInstanceOf<SmsCardOutput>().draft.recipient shouldBe
                SmsRecipient("Alex Herbert", "+14355550167")
    }

    "who myself is: asked once, remembered, with how to change it" {
        // seen on Aaron's phone: "send a text to myself" offered five strangers
        val aaron = SmsContact("Aaron", listOf("435-555-0167", "+14355550167"))
        val contacts = FakeContacts(*(STRANGERS + listOf(aaron, MOM)).toTypedArray())
        val own = FakeOwnContact.of(*contacts.contacts.toTypedArray())
        val ask = flow(contacts, own = own).start(FakeSkillContext(), "myself", null)
            .shouldBeInstanceOf<OwnContactAskOutput>()
        ask.options.shouldBeEmpty()
        ask.notFound.shouldBeNull()
        contacts.numberLookups.shouldBeEmpty()
        // a profile with only an email address doesn't make a text
        flow(contacts, own = FakeOwnContact(profile = OwnCard("Aaron", listOf(), listOf("a@example.com"))))
            .start(FakeSkillContext(), "myself", "hi").shouldBeInstanceOf<OwnContactAskOutput>()

        // as Vosk heard the answer: no contact is called Erin, and none is offered
        val again = answer(ask, "erin").shouldBeInstanceOf<OwnContactAskOutput>()
        again.notFound shouldBe "erin"
        again.options.shouldBeEmpty()
        own.remembered.shouldBeEmpty()

        val remembered = answer(again, "it's aaron").shouldBeInstanceOf<OwnContactRememberedOutput>()
        remembered.name shouldBe "Aaron"
        own.remembered shouldContainExactly listOf("Aaron")
        remembered.then.shouldBeInstanceOf<GarFollowUpOutput>().output.shouldBeInstanceOf<SmsAskOutput>().let {
            it.question shouldBe SmsQuestion.MESSAGE
            it.recipient shouldBe SmsRecipient("Aaron", "+14355550167")
        }
        // the question's batch is replaced by the next question
        remembered.getInteractionPlan(FakeSkillContext())
            .shouldBeInstanceOf<InteractionPlan.ReplaceSubInteraction>()

        // from then on, myself is Aaron without asking
        flow(contacts, own = own).start(FakeSkillContext(), "myself", "hi")
            .shouldBeInstanceOf<SmsCardOutput>().draft.recipient shouldBe SmsRecipient("Aaron", "+14355550167")

        // the message said with "myself" is kept through the question
        val withMessage = flow(contacts, own = FakeOwnContact.of(*contacts.contacts.toTypedArray()))
            .start(FakeSkillContext(), "myself buy milk", null).shouldBeInstanceOf<OwnContactAskOutput>()
        answer(withMessage, "aaron").shouldBeInstanceOf<OwnContactRememberedOutput>()
            .then.shouldBeInstanceOf<GarFollowUpOutput>().output.shouldBeInstanceOf<SmsCardOutput>()
            .draft.message shouldBe "Buy milk"

        answer(ask, "cancel").shouldBeInstanceOf<SmsNotSendingOutput>()
    }

    "who myself is: several matches are asked about, by name or position" {
        val herbert = SmsContact("Alex Herbert", listOf("555 0150"))
        val smith = SmsContact("Alex Smith", listOf("555 0151"))
        val contacts = FakeContacts(herbert, smith, MOM)
        val own = FakeOwnContact.of(herbert, smith, MOM)
        val ask = flow(contacts, own = own).start(FakeSkillContext(), "me", "hi")
            .shouldBeInstanceOf<OwnContactAskOutput>()
        val which = answer(ask, "alex").shouldBeInstanceOf<OwnContactAskOutput>()
        which.options shouldContainExactly listOf("Alex Herbert", "Alex Smith")
        answer(which, "the second one").shouldBeInstanceOf<OwnContactRememberedOutput>()
            .name shouldBe "Alex Smith"
        answer(which, "herbert").shouldBeInstanceOf<OwnContactRememberedOutput>().let {
            it.name shouldBe "Alex Herbert"
            it.then.shouldBeInstanceOf<GarFollowUpOutput>().output.shouldBeInstanceOf<SmsCardOutput>()
                .draft.recipient shouldBe SmsRecipient("Alex Herbert", "555 0150")
        }
        // a last name alone, after "I'm"
        val names = { contacts.contacts.map { it.name } }
        OwnContactAnswers.find(OwnContactAnswers.said("I'm Smith"), listOf(), names) shouldBe
                OwnAnswer.One("Alex Smith", sure = true)
        // a first name alone that two contacts share, or a word of one
        OwnContactAnswers.find("alex", listOf(), names) shouldBe
                OwnAnswer.Several(listOf("Alex Herbert", "Alex Smith"))
        OwnContactAnswers.find("ale", listOf(), names) shouldBe
                OwnAnswer.Several(listOf("Alex Herbert", "Alex Smith"))
        OwnContactAnswers.find("erin", listOf(), names) shouldBe OwnAnswer.NotFound
        // only close to a name: not for certain
        OwnContactAnswers.find("erin", listOf(), { listOf("Eric", "Mom") }) shouldBe
                OwnAnswer.One("Eric", sure = false)
        OwnContactAnswers.said("my name is Alex Herbert.") shouldBe "Alex Herbert"
        OwnContactAnswers.said("no, Aaron") shouldBe "Aaron"
    }

    "who myself is: a name only close to a contact, or a contact without a number, isn't remembered" {
        // Vosk writes "aaron" as "erin": that must not make Eric the owner for good
        for (eric in listOf("Eric", "Eric Smith")) {
            val contact = SmsContact(eric, listOf("555 0160"))
            val contacts = FakeContacts(contact, MOM)
            val own = FakeOwnContact.of(contact, MOM)
            val ask = flow(contacts, own = own).start(FakeSkillContext(), "myself", "hi")
                .shouldBeInstanceOf<OwnContactAskOutput>()
            withClue(eric) {
                // the text goes on to Eric this once, and is read back with his name
                answer(ask, "erin").shouldBeInstanceOf<GarFollowUpOutput>().output
                    .shouldBeInstanceOf<SmsCardOutput>().draft.recipient shouldBe SmsRecipient(eric, "555 0160")
                own.remembered.shouldBeEmpty()
            }
        }

        // his own contact has only an email address, a work contact shares his name
        val aaron = OwnCard("Aaron", listOf(), listOf("a@example.com"))
        val work = OwnCard("Aaron's Work", listOf("555 0140"), listOf())
        val own = FakeOwnContact(listOf(aaron, work))
        val ask = flow(FakeContacts(SmsContact("Aaron's Work", listOf("555 0140"))), own = own)
            .start(FakeSkillContext(), "myself", "hi").shouldBeInstanceOf<OwnContactAskOutput>()
        val again = answer(ask, "aaron").shouldBeInstanceOf<OwnContactAskOutput>()
        again.lacking shouldBe "Aaron"
        own.remembered.shouldBeEmpty()
        // a contact with a number is still taken
        answer(again, "aaron's work").shouldBeInstanceOf<OwnContactRememberedOutput>().name shouldBe
                "Aaron's Work"
    }

    "a spelling Vosk writes the same way every time still finds the contact" {
        val gabi = STRANGERS[0]
        FakeContacts(*STRANGERS.toTypedArray()).find("gabby", false).first shouldBe
                RecipientChoice.One(SmsRecipient(gabi.name, "555 0130"))
        val stephen = SmsContact("Stephen", listOf("555 0170"))
        FakeContacts(stephen, MOM).find("steven", false).first shouldBe
                RecipientChoice.One(SmsRecipient("Stephen", "555 0170"))
    }

    "no, to Aaron in reply to which one looks Aaron up instead" {
        val pf = ENGLISH_PARSER_FORMATTER
        // seen on Aaron's phone: "no to erin" picked "Katerina Book Club" from the list
        SmsChooserAnswer.of("no to erin", STRANGERS, pf) shouldBe SmsChooserAnswer.Correction("erin")
        SmsChooserAnswer.of("erin", STRANGERS, pf).shouldBeNull()
        SmsChooserAnswer.of("katerina", STRANGERS, pf) shouldBe
                SmsChooserAnswer.Pick(RecipientPick.Picked(SmsRecipient("Katerina Book Club", "555 0132")))
        // a position or a whole name of the list after a no still picks from it
        SmsChooserAnswer.of("no, the second one", STRANGERS, pf) shouldBe
                SmsChooserAnswer.Pick(RecipientPick.Picked(SmsRecipient("Myrtle Seller 27", "555 0131")))
        SmsChooserAnswer.of("no, joe's work", STRANGERS, pf) shouldBe
                SmsChooserAnswer.Pick(RecipientPick.Picked(SmsRecipient("Joe's Work", "555 0133")))
        SmsChooserAnswer.of("cancel", STRANGERS, pf) shouldBe SmsChooserAnswer.Cancel
        // the position after "not" is the one not wanted, and "not" alone asks again
        SmsChooserAnswer.of("no not the first one the second one", STRANGERS, pf) shouldBe
                SmsChooserAnswer.Pick(RecipientPick.Picked(SmsRecipient("Myrtle Seller 27", "555 0131")))
        SmsChooserAnswer.of("not the first one", STRANGERS, pf) shouldBe SmsChooserAnswer.Again
        val moms = listOf(MOM, SmsContact("Mom Work", listOf("555 0141")))
        SmsChooserAnswer.of("not mom dad", moms, pf) shouldBe SmsChooserAnswer.Correction("dad")
        // the conversation's stop phrases are left to it
        for (input in listOf("no forget it", "no, I'm good", "not now", "no, what did you say")) {
            withClue(input) { SmsChooserAnswer.of(input, STRANGERS, pf).shouldBeNull() }
        }
        // after a no, a name that only partly matches the list is looked up: the list was wrong
        val work = listOf(SmsContact("Aaron's Work", listOf("555 0140")))
        SmsChooserAnswer.of("no, to aaron", work, pf) shouldBe SmsChooserAnswer.Correction("aaron")
        SmsChooserAnswer.of("aaron", work, pf) shouldBe
                SmsChooserAnswer.Pick(RecipientPick.Picked(SmsRecipient("Aaron's Work", "555 0140")))

        // through the flow, the new name is looked up among all the contacts, with the message
        val choose = flow().start(FakeSkillContext(), "sarah", "hi").shouldBeInstanceOf<SmsChooseOutput>()
        answer(choose, "no I meant mom").shouldBeInstanceOf<SmsCardOutput>().draft.let {
            it.recipient shouldBe SmsRecipient("Mom", "+1 555 0100")
            it.message shouldBe "Hi"
        }
        answer(choose, "not her, dad").shouldBeInstanceOf<SmsCardOutput>().draft.recipient shouldBe
                SmsRecipient("Dad", "+1 555 0111")
        answer(choose, "no to erin").shouldBeInstanceOf<SmsAskOutput>().let {
            it.question shouldBe SmsQuestion.RECIPIENT
            it.notFound shouldBe "erin"
            it.message shouldBe "Hi"
        }
        val card = OwnCard("Aaron", listOf("+14355550167"), listOf())
        val withOwner = flow(own = FakeOwnContact(profile = card)).start(FakeSkillContext(), "sarah", "hi")
            .shouldBeInstanceOf<SmsChooseOutput>()
        answer(withOwner, "no, to myself").shouldBeInstanceOf<SmsCardOutput>().draft.recipient shouldBe
                SmsRecipient("Aaron", "+14355550167")

        // "not the first one" asks which one again
        answer(choose, "not the first one").shouldBeInstanceOf<SmsChooseOutput>().contacts shouldBe
                choose.contacts

        // and in reply to "Who should I text?", where a stop phrase stops
        val who = flow().start(FakeSkillContext(), null, "hi").shouldBeInstanceOf<SmsAskOutput>()
        answer(who, "no, to mom").shouldBeInstanceOf<SmsCardOutput>().draft.recipient shouldBe
                SmsRecipient("Mom", "+1 555 0100")
        for (input in listOf("no I'm good", "not now", "no forget it")) {
            withClue(input) { answer(who, input).shouldBeInstanceOf<SmsNotSendingOutput>() }
        }
    }

    "a message said right after the name is split off" {
        val contacts = contacts()
        contacts.find("mom i'm on my way", true) shouldBe
                Pair(RecipientChoice.One(SmsRecipient("Mom", "+1 555 0100")), "i'm on my way")
        contacts.find("five five five one two one two call me back", true) shouldBe
                Pair(RecipientChoice.One(SmsRecipient("5551212", "5551212")), "call me back")
        // a full name is not split, and nothing is split when the message is known
        contacts.find("sarah lee", true) shouldBe
                Pair(RecipientChoice.One(SmsRecipient("Sarah Lee", "555 0103")), null)
        contacts.find("mom i'm on my way", false).second.shouldBeNull()
        SmsRecipients.splitMessage("qqq zzz", contacts.contacts, SmsContact::name).shouldBeNull()
        // filler words alone are no name
        SmsRecipients.splitMessage("the dentist", listOf("Theo"), { it }).shouldBeNull()
        // the best-matching name wins, not the longest loose match ("mom i" ~ "Mom")
        contacts.find("mom i am running late", true) shouldBe
                Pair(RecipientChoice.One(SmsRecipient("Mom", "+1 555 0100")), "i am running late")
        SmsRecipients.splitMessage("sarah lee see you soon", contacts.contacts, SmsContact::name) shouldBe
                Pair("sarah lee", "see you soon")
    }

    "a contact is picked by position or by name" {
        val choice = listOf(SARAH_CONNOR, SARAH_LEE)
        val pick = { input: String -> SmsRecipients.pick(input, choice, ENGLISH_PARSER_FORMATTER) }
        pick("the second one") shouldBe RecipientPick.Picked(SmsRecipient("Sarah Connor", "555 0102"))
        pick("three") shouldBe RecipientPick.Picked(SmsRecipient("Sarah Lee", "555 0103"))
        pick("last") shouldBe RecipientPick.Picked(SmsRecipient("Sarah Lee", "555 0103"))
        pick("sarah lee") shouldBe RecipientPick.Picked(SmsRecipient("Sarah Lee", "555 0103"))
        // a contact with several numbers is narrowed down
        pick("connor") shouldBe RecipientPick.Narrowed(SARAH_CONNOR)
        pick("pizza").shouldBeNull()
        pick("number nine").shouldBeNull()

        // a name with a position word in it picks by the name
        val banks = listOf(SmsContact("First Bank", listOf("1")), SmsContact("First Bank Business", listOf("2")))
        SmsRecipients.pick("first bank business", banks, ENGLISH_PARSER_FORMATTER) shouldBe
                RecipientPick.Picked(SmsRecipient("First Bank Business", "2"))
        SmsRecipients.pick("the second one", banks, ENGLISH_PARSER_FORMATTER) shouldBe
                RecipientPick.Picked(SmsRecipient("First Bank Business", "2"))
        SmsRecipients.pick("first", banks, ENGLISH_PARSER_FORMATTER) shouldBe
                RecipientPick.Picked(SmsRecipient("First Bank", "1"))

        // positions past five go through Dicio's number parser
        val many = (1..7).map { SmsContact("Person $it", listOf("$it")) }
        SmsRecipients.pick("number seven", many, ENGLISH_PARSER_FORMATTER) shouldBe
                RecipientPick.Picked(SmsRecipient("Person 7", "7"))
    }

    "the messages app is opened with ACTION_SENDTO smsto: and sms_body" {
        SmsAppIntent.compose("+1 (555) 010-0123", "I'm late", "com.google.android.apps.messaging") shouldBe
                SmsAppIntent(
                    action = "android.intent.action.SENDTO",
                    scheme = "smsto",
                    recipient = "+15550100123",
                    extras = mapOf("sms_body" to "I'm late"),
                    packageName = "com.google.android.apps.messaging",
                )
        SmsAppIntent.compose("555 0100", "Hi", null).packageName.shouldBeNull()
        SmsAppIntent.dialable("555.0100") shouldBe "5550100"
        SmsAppIntent.dialable("*#06#") shouldBe "*#06#"
    }

    "a tap on Send sends once, with the permission" {
        val sender = FakeSender()
        val draft = SmsDraft(SmsRecipient("Mom", "+1 555 0100"), "Hi")
        draft.send(sender)
        sender.sent shouldContainExactly listOf(Pair("+1 555 0100", "Hi"))
        draft.state shouldBe SmsState.SENDING

        sender.onResult!!(SendResult.SENT)
        draft.state shouldBe SmsState.SENT
        draft.send(sender)
        draft.edit(sender)
        sender.sent.size shouldBe 1
        sender.opened.shouldBeEmpty()

        val failed = SmsDraft(SmsRecipient("Mom", "1"), "Hi")
        failed.send(sender)
        sender.onResult!!(SendResult.FAILED)
        failed.state shouldBe SmsState.FAILED
        // Edit retries by hand in the messages app, Send doesn't send twice
        failed.send(sender)
        sender.sent.size shouldBe 2
        failed.edit(sender)
        failed.state shouldBe SmsState.OPENED_APP
    }

    "without the permission, or when sending fails to start, Send opens the messages app" {
        val noPermission = FakeSender(allowed = false)
        SmsDraft(SmsRecipient("Mom", "1"), "Hi").apply {
            send(noPermission)
            state shouldBe SmsState.OPENED_APP
        }
        noPermission.sent.shouldBeEmpty()
        noPermission.opened shouldContainExactly listOf(Pair("1", "Hi"))

        val broken = FakeSender(throwOnSend = true)
        SmsDraft(SmsRecipient("Mom", "1"), "Hi", logError = { _, _ -> }).apply {
            send(broken)
            state shouldBe SmsState.OPENED_APP
        }
        broken.opened shouldContainExactly listOf(Pair("1", "Hi"))

        val noApp = FakeSender(allowed = false, hasApp = false)
        SmsDraft(SmsRecipient("Mom", "1"), "Hi").apply {
            send(noApp)
            state shouldBe SmsState.NO_SMS_APP
        }
    }

    "a cancelled or changed text can't be sent or edited" {
        val sender = FakeSender()
        val draft = SmsDraft(SmsRecipient("Mom", "1"), "Hi")
        draft.cancel()
        draft.state shouldBe SmsState.CANCELLED
        draft.send(sender)
        draft.edit(sender)
        sender.sent.shouldBeEmpty()
        sender.opened.shouldBeEmpty()

        val changed = SmsDraft(SmsRecipient("Mom", "1"), "Hi")
        changed.replace()
        changed.state shouldBe SmsState.REPLACED
        changed.send(sender)
        changed.edit(sender)
        changed.cancel()
        changed.state shouldBe SmsState.REPLACED
        sender.sent.shouldBeEmpty()
        sender.opened.shouldBeEmpty()
    }

    "voice answers to the read-back are understood" {
        val expected = listOf(
            SmsCardAnswer.Send to listOf(
                "yes", "yeah", "yep", "sure", "ok", "okay", "correct", "right", "go ahead", "do it",
                "send", "send it", "please send it", "yes send it", "Yes, send it!", "no you send it",
                "No, you send it.", "no go ahead", "sounds good", "that's right", "perfect",
                "go for it", "let's go", "send the text", "yes please", "just do it",
                "never mind send it",
            ),
            SmsCardAnswer.Cancel to listOf(
                "no", "nope", "nah", "cancel", "cancel that", "never mind", "forget it", "stop",
                "don't send it", "do not send", "no don't", "no don't send it", "Don't send it.",
                "don't change it", "no thanks", "yes no", "no don't do it",
            ),
            SmsCardAnswer.Change(null) to listOf(
                "change it", "change the message", "edit", "edit it", "that's wrong",
                "no change it", "edit and send it", "let me edit it first", "that's not right",
                "change it to say",
            ),
            SmsCardAnswer.ReadAgain to listOf(
                "read it again", "what does it say", "repeat that", "what", "say that again",
                "is that right", "read it back to me",
            ),
            SmsCardAnswer.OpenApp to listOf("open it in messages", "open the messages app"),
        )
        for ((answer, inputs) in expected) {
            for (input in inputs) {
                withClue(input) { SmsCardAnswers.classify(input) shouldBe answer }
            }
        }
    }

    "a polite no or a question about the text never sends it" {
        for ((answer, inputs) in listOf(
            // after a no, only an explicit go-ahead sends ("no, you send it")
            SmsCardAnswer.Cancel to listOf(
                "no it's fine", "No, that's okay.", "nah it's good", "no go", "no yes",
                "no, that's right", "nope perfect", "not right now", "not now",
            ),
            SmsCardAnswer.Send to listOf(
                "no you do it", "no, just send it", "no, ok, send it", "could you send it",
                "will you send it", "send it to him",
            ),
            SmsCardAnswer.ReadAgain to listOf(
                "did you send it", "did it send", "is it sent", "could you read it again",
            ),
        )) {
            for (input in inputs) {
                withClue(input) { SmsCardAnswers.classify(input) shouldBe answer }
            }
        }
    }

    "send it to someone else reads it back for them, the same recipient sends it" {
        for ((input, recipient) in listOf(
            "send it to dad instead" to "dad",
            "yes, send it to mom" to "mom",
            "No, text it to Sarah Lee." to "Sarah Lee",
            "send the message to dad please" to "dad",
            "send it to someone else" to "someone else",
        )) {
            withClue(input) {
                SmsCardAnswers.classify(input) shouldBe SmsCardAnswer.Recipient(recipient)
            }
        }

        // the recipient the text already has: a yes
        val sender = FakeSender(report = SendResult.SENT)
        val card = flow(sender = sender).start(FakeSkillContext(), "mom", "hi")
            .shouldBeInstanceOf<SmsCardOutput>()
        answer(card, "yes, send it to mom").shouldBeInstanceOf<SmsSendOutput>()
            .state shouldBe SmsState.SENT
        sender.sent shouldContainExactly listOf(Pair("+1 555 0100", "Hi"))

        // another recipient: the same message on a new card, read back and asked again
        val toDad = flow(sender = sender).start(FakeSkillContext(), "mom", "hi")
            .shouldBeInstanceOf<SmsCardOutput>()
        val ranker = rankerAfter(toDad)
        val dad = ranker.evaluate("no, send it to dad instead").shouldBeInstanceOf<SmsCardOutput>()
        dad.draft shouldNotBe toDad.draft
        dad.draft.recipient shouldBe SmsRecipient("Dad", "+1 555 0111")
        dad.draft.message shouldBe "Hi"
        dad.getInteractionPlan(FakeSkillContext()).shouldBeInstanceOf<InteractionPlan.ReplaceSubInteraction>()
        toDad.draft.state shouldBe SmsState.REPLACED
        sender.sent.size shouldBe 1
        ranker.evaluate("yes").shouldBeInstanceOf<SmsSendOutput>()
        sender.sent shouldContainExactly listOf(Pair("+1 555 0100", "Hi"), Pair("+1 555 0111", "Hi"))

        // several matches or none: asked, with the message kept
        val toSarah = flow().start(FakeSkillContext(), "mom", "hi").shouldBeInstanceOf<SmsCardOutput>()
        answer(toSarah, "send it to sarah").shouldBeInstanceOf<SmsChooseOutput>().let {
            it.contacts shouldBe listOf(SARAH_CONNOR, SARAH_LEE)
            it.message shouldBe "Hi"
        }
        val toNobody = flow(FakeContacts(MOM)).start(FakeSkillContext(), "mom", "hi")
            .shouldBeInstanceOf<SmsCardOutput>()
        answer(toNobody, "send it to qqq").shouldBeInstanceOf<SmsAskOutput>().let {
            it.question shouldBe SmsQuestion.RECIPIENT
            it.notFound shouldBe "qqq"
            it.message shouldBe "Hi"
        }
    }

    "a new message said with the change is taken as said" {
        for ((input, message) in listOf(
            "change it to see you soon" to "see you soon",
            "No, change it to: see you at 5!" to "see you at 5!",
            "change the message to say i'm running late" to "i'm running late",
            "can you change it to see you soon instead" to "see you soon",
            "make it say I'll call you back" to "I'll call you back",
            "say call me later instead" to "call me later",
            "no say call me later instead" to "call me later",
            "it should say happy birthday" to "happy birthday",
            "replace it with \"on my way\"" to "on my way",
        )) {
            withClue(input) {
                SmsCardAnswers.classify(input) shouldBe SmsCardAnswer.Change(message)
            }
        }
    }

    "the card leaves anything else to other skills, and the text keeps waiting" {
        // only short answers are taken: the card's skill is checked before every other skill
        for (input in listOf(
            "send a text to dad saying hi", "send dad a text", "change the volume",
            "change the volume to fifty percent", "don't forget to set a timer",
            "what's not on my calendar", "fix my alarm", "never gonna give you up", "open spotify",
            "what time is it", "stop the music", "turn it off", "go away", "say hi to mom",
            "please", "it", "", "turn on the flashlight", "set a timer for five minutes",
            "call mom", "text mom", "read my messages", "what messages",
            // a new text, not this one
            "send a text", "send a message", "send a text message",
            // "go" alone may be a misheard "no"
            "go",
        )) {
            withClue(input) { SmsCardAnswers.classify(input).shouldBeNull() }

            val sender = FakeSender()
            val card = flow(sender = sender).start(FakeSkillContext(), "mom", "hi")
                .shouldBeInstanceOf<SmsCardOutput>()
            withClue(input) {
                rankerAfter(card).evaluate(input).shouldBeNull()
                card.draft.state shouldBe SmsState.PENDING
                sender.sent.shouldBeEmpty()
                sender.opened.shouldBeEmpty()
            }
        }
    }

    "the read-back says the name and the message as it will be sent" {
        // the real string, formatted like Android does
        val template = Regex("<string name=\"skill_sms_read_back\">(.*?)</string>")
            .find(sourceFile("src/main/res/values/strings_gar_sms.xml"))!!.groupValues[1]
            .replace("\\'", "'")
        fun readBack(card: SmsCardOutput) = String.format(
            Locale.ROOT,
            template,
            SmsCardOutput.spokenName(card.draft.recipient),
            SmsCardOutput.spokenMessage(card.draft.message),
        )

        val aaron = FakeContacts(SmsContact("Aaron", listOf("435-555-0167", "+14355550167")))
        val card = flow(aaron).start(FakeSkillContext(), "aaron", "hello")
            .shouldBeInstanceOf<SmsCardOutput>()
        card.draft.recipient shouldBe SmsRecipient("Aaron", "+14355550167")
        readBack(card) shouldBe "Your message to Aaron says: Hello. Ready to send it?"

        flow().start(FakeSkillContext(), "mom", "are you there?")
            .shouldBeInstanceOf<SmsCardOutput>().let(::readBack) shouldBe
                "Your message to Mom says: Are you there? Ready to send it?"
        // a number that is no contact is read digit by digit
        flow().start(FakeSkillContext(), "five five five one two one two", "call me back")
            .shouldBeInstanceOf<SmsCardOutput>().let(::readBack) shouldBe
                "Your message to 5 5 5 1 2 1 2 says: Call me back. Ready to send it?"

        // the card asks with the microphone open, and "read it again" keeps it waiting
        card.getInteractionPlan(FakeSkillContext()).reopenMicrophone shouldBe true
        val ranker = rankerAfter(card)
        ranker.evaluate("what does it say").shouldBeInstanceOf<SmsReadBackOutput>()
            .getInteractionPlan(FakeSkillContext()) shouldBe InteractionPlan.Continue(true)
        card.draft.state shouldBe SmsState.PENDING
    }

    "a spoken yes sends the text and says how it went" {
        for (input in listOf("yes", "send it", "no you send it", "sounds good", "go ahead")) {
            val sender = FakeSender(report = SendResult.SENT)
            val card = flow(sender = sender).start(FakeSkillContext(), "mom", "i'm on my way")
                .shouldBeInstanceOf<SmsCardOutput>()
            val ranker = rankerAfter(card)
            withClue(input) {
                val output = ranker.evaluate(input).shouldBeInstanceOf<SmsSendOutput>()
                output.state shouldBe SmsState.SENT
                SmsSendOutput.speech(output.state) shouldBe R.string.skill_sms_sent
                output.getInteractionPlan(FakeSkillContext()) shouldBe InteractionPlan.FinishInteraction
                sender.sent shouldContainExactly listOf(Pair("+1 555 0100", "I'm on my way"))
                card.draft.state shouldBe SmsState.SENT
                // the card's skill is done: a second yes doesn't send again
                rankerAfter(card).evaluate("yes").shouldBeNull()
                sender.sent.size shouldBe 1
            }
        }

        // after a read-back, a yes still sends
        val sender = FakeSender(report = SendResult.SENT)
        val card = flow(sender = sender).start(FakeSkillContext(), "mom", "hi")
            .shouldBeInstanceOf<SmsCardOutput>()
        val ranker = rankerAfter(card)
        ranker.evaluate("read it again").shouldBeInstanceOf<SmsReadBackOutput>()
        ranker.evaluate("yes").shouldBeInstanceOf<SmsSendOutput>().state shouldBe SmsState.SENT
        sender.sent shouldContainExactly listOf(Pair("+1 555 0100", "Hi"))
    }

    "the network's answer is said, or that the text is still sending" {
        val failed = FakeSender(report = SendResult.FAILED)
        val card = flow(sender = failed).start(FakeSkillContext(), "mom", "hi")
            .shouldBeInstanceOf<SmsCardOutput>()
        answer(card, "yes").shouldBeInstanceOf<SmsSendOutput>().state shouldBe SmsState.FAILED
        SmsSendOutput.speech(SmsState.FAILED) shouldBe R.string.skill_sms_not_through

        // no report in time: "It's still sending.", and the card shows the result later
        val slow = FakeSender()
        val slowCard = SmsFlow(
            findRecipient = { _, spoken, split -> contacts().find(spoken, split) },
            senderFor = { slow },
            isDeviceLocked = { false },
            sendTimeoutMs = 10,
        ).start(FakeSkillContext(), "mom", "hi").shouldBeInstanceOf<SmsCardOutput>()
        answer(slowCard, "yes").shouldBeInstanceOf<SmsSendOutput>().state shouldBe SmsState.SENDING
        SmsSendOutput.speech(SmsState.SENDING) shouldBe R.string.skill_sms_still_sending
        slow.onResult!!(SendResult.SENT)
        slowCard.draft.state shouldBe SmsState.SENT
    }

    "without SEND_SMS, or when sending fails to start, a spoken yes opens the messages app" {
        for (sender in listOf(FakeSender(allowed = false), FakeSender(throwOnSend = true))) {
            // android.util.Log, which the draft logs the failure to, doesn't run in unit tests
            val draft = SmsDraft(SmsRecipient("Mom", "+1 555 0100"), "Hi", logError = { _, _ -> })
            val output = answer(SmsCardOutput(flow(sender = sender), draft, nested = false), "yes")
                .shouldBeInstanceOf<SmsSendOutput>()
            output.state shouldBe SmsState.OPENED_APP
            SmsSendOutput.speech(output.state) shouldBe R.string.skill_sms_opened_app_instead
            sender.sent.shouldBeEmpty()
            sender.opened shouldContainExactly listOf(Pair("+1 555 0100", "Hi"))
        }

        val noApp = FakeSender(allowed = false, hasApp = false)
        val card = flow(sender = noApp).start(FakeSkillContext(), "mom", "hi")
            .shouldBeInstanceOf<SmsCardOutput>()
        answer(card, "yes").shouldBeInstanceOf<SmsSendOutput>().state shouldBe SmsState.NO_SMS_APP
        SmsSendOutput.speech(SmsState.NO_SMS_APP) shouldBe R.string.skill_sms_no_sms_app_instead
    }

    "no and cancel phrases cancel the text, open it in messages opens the app" {
        for (input in listOf("no", "cancel", "never mind", "don't send it", "stop", "no don't")) {
            val sender = FakeSender()
            val card = flow(sender = sender).start(FakeSkillContext(), "mom", "hi")
                .shouldBeInstanceOf<SmsCardOutput>()
            withClue(input) {
                answer(card, input).shouldBeInstanceOf<SmsNotSendingOutput>()
                card.draft.state shouldBe SmsState.CANCELLED
                sender.sent.shouldBeEmpty()
            }
        }

        val sender = FakeSender()
        val card = flow(sender = sender).start(FakeSkillContext(), "mom", "hi")
            .shouldBeInstanceOf<SmsCardOutput>()
        val ranker = rankerAfter(card)
        ranker.evaluate("open it in messages").shouldBeInstanceOf<SmsStatusOutput>()
        card.draft.state shouldBe SmsState.OPENED_APP
        sender.opened shouldContainExactly listOf(Pair("+1 555 0100", "Hi"))
        sender.sent.shouldBeEmpty()

        // once the card is done, its skill doesn't take answers anymore
        ranker.evaluate("yes").shouldBeNull()
        for (skill in card.nextSkills()) {
            skill.score(FakeSkillContext().forInput("yes"), "yes").first shouldBeSameInstanceAs
                    AlwaysWorstScore
        }
    }

    "change it asks for the new message and reads it back again" {
        val sender = FakeSender(report = SendResult.SENT)
        val card = flow(sender = sender).start(FakeSkillContext(), "mom", "hello")
            .shouldBeInstanceOf<SmsCardOutput>()
        val ranker = rankerAfter(card)

        val ask = ranker.evaluate("change it").shouldBeInstanceOf<SmsAskOutput>()
        ask.question shouldBe SmsQuestion.NEW_MESSAGE
        ask.recipient shouldBe SmsRecipient("Mom", "+1 555 0100")
        ask.getInteractionPlan(FakeSkillContext()).shouldBeInstanceOf<InteractionPlan.ReplaceSubInteraction>()
            .reopenMicrophone shouldBe true
        card.draft.state shouldBe SmsState.REPLACED

        val changed = ranker.evaluate("see you soon").shouldBeInstanceOf<SmsCardOutput>()
        changed.draft.recipient shouldBe SmsRecipient("Mom", "+1 555 0100")
        changed.draft.message shouldBe "See you soon"
        changed.getInteractionPlan(FakeSkillContext()).shouldBeInstanceOf<InteractionPlan.ReplaceSubInteraction>()

        ranker.evaluate("yes").shouldBeInstanceOf<SmsSendOutput>().state shouldBe SmsState.SENT
        sender.sent shouldContainExactly listOf(Pair("+1 555 0100", "See you soon"))
        card.draft.state shouldBe SmsState.REPLACED

        // the new message said right away is read back on a new card directly
        val direct = flow(sender = sender).start(FakeSkillContext(), "mom", "hello")
            .shouldBeInstanceOf<SmsCardOutput>()
        answer(direct, "change it to see you soon").shouldBeInstanceOf<SmsCardOutput>()
            .draft.message shouldBe "See you soon"
        direct.draft.state shouldBe SmsState.REPLACED

        // cancelling instead of saying the new message sends nothing
        val cancelled = flow(sender = sender).start(FakeSkillContext(), "mom", "hello")
            .shouldBeInstanceOf<SmsCardOutput>()
        val cancelRanker = rankerAfter(cancelled)
        cancelRanker.evaluate("edit").shouldBeInstanceOf<SmsAskOutput>()
        cancelRanker.evaluate("never mind").shouldBeInstanceOf<SmsNotSendingOutput>()
        sender.sent.size shouldBe 1
    }

    "on a locked phone the skill doesn't run" {
        val contacts = contacts()
        val sender = FakeSender()
        flow(contacts, sender, locked = true).start(FakeSkillContext(), "mom", "i'm on my way")
            .shouldBeInstanceOf<GarLockedOutput>()
        SmsSkill(SmsInfo, Sentences.Sms["en"]!!, flow(contacts, sender, locked = true))
            .generateOutput(FakeSkillContext(), Sentences.Sms.Send("mom", "hi"))
            .shouldBeInstanceOf<GarLockedOutput>()
        contacts.numberLookups.shouldBeEmpty()
        sender.sent.shouldBeEmpty()
        sender.opened.shouldBeEmpty()
    }

    "a missing recipient or message is asked for" {
        val noRecipient = flow().start(FakeSkillContext(), null, "i'm late")
            .shouldBeInstanceOf<SmsAskOutput>()
        noRecipient.question shouldBe SmsQuestion.RECIPIENT
        noRecipient.getInteractionPlan(FakeSkillContext())
            .shouldBeInstanceOf<InteractionPlan.StartSubInteraction>()

        val noMessage = answer(noRecipient, "to dad")
        noMessage.shouldBeInstanceOf<SmsCardOutput>().draft.let {
            it.recipient shouldBe SmsRecipient("Dad", "+1 555 0111")
            it.message shouldBe "I'm late"
        }
        // follow-up outputs replace the skill's previous batch
        noMessage.getInteractionPlan(FakeSkillContext())
            .shouldBeInstanceOf<InteractionPlan.ReplaceSubInteraction>()

        val askMessage = flow().start(FakeSkillContext(), "dad", null).shouldBeInstanceOf<SmsAskOutput>()
        askMessage.question shouldBe SmsQuestion.MESSAGE
        askMessage.recipient shouldBe SmsRecipient("Dad", "+1 555 0111")
        answer(askMessage, "saying").shouldBeInstanceOf<SmsAskOutput>().question shouldBe
                SmsQuestion.MESSAGE
        answer(askMessage, "dinner is ready").shouldBeInstanceOf<SmsCardOutput>().draft.message shouldBe
                "Dinner is ready"
        answer(askMessage, "cancel").shouldBeInstanceOf<SmsNotSendingOutput>()
    }

    "an unknown name is asked again, several matches are chosen from" {
        flow(FakeContacts(MOM)).start(FakeSkillContext(), "qqq", "hi")
            .shouldBeInstanceOf<SmsAskOutput>().notFound shouldBe "qqq"

        val choose = flow().start(FakeSkillContext(), "sarah", "hi")
            .shouldBeInstanceOf<SmsChooseOutput>()
        choose.contacts shouldBe listOf(SARAH_CONNOR, SARAH_LEE)
        answer(choose, "sarah lee").shouldBeInstanceOf<SmsCardOutput>().draft.recipient shouldBe
                SmsRecipient("Sarah Lee", "555 0103")

        // naming a contact with several numbers asks which number
        val numbers = answer(choose, "sarah connor").shouldBeInstanceOf<SmsChooseOutput>()
        numbers.contacts shouldBe listOf(SARAH_CONNOR)
        answer(numbers, "the second one").shouldBeInstanceOf<SmsCardOutput>().draft.recipient shouldBe
                SmsRecipient("Sarah Connor", "555 0102")

        // without a message, the choice is followed by the question
        val noMessage = flow().start(FakeSkillContext(), "sarah", null)
            .shouldBeInstanceOf<SmsChooseOutput>()
        answer(noMessage, "two").shouldBeInstanceOf<SmsAskOutput>().question shouldBe SmsQuestion.MESSAGE
        answer(noMessage, "cancel").shouldBeInstanceOf<SmsNotSendingOutput>()
        rankerAfter(noMessage).evaluate("pizza").shouldBeNull()
    }

    "Dicio's sentences capture the recipient and the message" {
        val data = Sentences.Sms["en"]!!
        fun capture(input: String): Pair<String?, String?> {
            val send = data.score(FakeSkillContext().forInput(input), input).second
                .shouldBeInstanceOf<Sentences.Sms.Send>()
            return Pair(send.who?.trim(), send.what?.trim())
        }
        capture("send a text to mom saying i'm on my way") shouldBe Pair("mom", "i'm on my way")
        capture("text dad that says dinner is ready") shouldBe Pair("dad", "dinner is ready")
        capture("message sarah lee") shouldBe Pair("sarah lee", null)
        capture("send a message") shouldBe Pair(null, null)

        // "text mom i'm on my way" captures it all as the recipient: the skill splits it
        val skill = SmsSkill(SmsInfo, data, flow())
        val (who, what) = capture("text mom i'm on my way")
        skill.generateOutput(FakeSkillContext(), Sentences.Sms.Send(who, what))
            .shouldBeInstanceOf<SmsCardOutput>().draft.let {
                it.recipient shouldBe SmsRecipient("Mom", "+1 555 0100")
                it.message shouldBe "I'm on my way"
            }
    }

    "Dicio's fast path takes common phrasings, so they never reach the model" {
        // the sms skill has low specificity: the ranker takes it above 0.9 (LOW_THRESHOLD_3)
        val data = Sentences.Sms["en"]!!
        fun score(input: String) = data.score(FakeSkillContext().forInput(input), input)
            .first.scoreIn01Range()
        for (input in listOf(
            "text mom i'm on my way", "text dad", "message sarah lee",
            "send a message to john saying see you at five", "send a text to mom",
            "text dad that says dinner is ready", "send a text",
        )) {
            withClue(input) { (score(input) > 0.9f) shouldBe true }
        }
        for (input in listOf("read my last text message", "what's the weather")) {
            withClue(input) { (score(input) > 0.9f) shouldBe false }
        }
    }

    "GAR's sms_send tool reaches the same card and voice confirmation, without GAR's own" {
        val tool = ToolCatalog.load(
            sourceFile("src/main/gar/tools.yml"),
            sourceFile("src/main/sentences/skill_definitions.yml"),
        ).tools.single { it.name == "sms_send" }
        tool.confirm shouldBe false
        tool.onLockScreen shouldBe false
        tool.sentence.shouldBeNull()

        val sender = FakeSender(report = SendResult.SENT)
        val skill = SmsSkill(SmsInfo, Sentences.Sms["en"]!!, flow(sender = sender))
        skill.inputFromSlots(mapOf("to" to "mom", "message" to "i'm on my way")) shouldBe
                Sentences.Sms.Send("mom", "i'm on my way")

        val runner = GarCallRunner(
            targetInfo = SmsInfo,
            target = skill,
            confirm = true,
            resolver = fakeResolver,
            formatter = englishFormatter(),
            yesNoData = Sentences.UtilYesNo["en"]!!,
        )
        val call = GarCall(tool, mapOf("to" to "mom", "message" to "i'm on my way"), "")
        runner.next(FakeSkillContext(), call, nested = false)
            .shouldBeInstanceOf<SmsCardOutput>().draft.message shouldBe "I'm on my way"

        // the model left out the message: the skill asks for it
        runner.next(FakeSkillContext(), call.copy(rawSlots = mapOf("to" to "dad")), nested = false)
            .shouldBeInstanceOf<SmsAskOutput>().question shouldBe SmsQuestion.MESSAGE
        // the model filled the message with the command itself (seen in the eval)
        runner.next(
            FakeSkillContext(),
            call.copy(rawSlots = mapOf("to" to "dad", "message" to "send a picture to dad")),
            nested = false,
        ).shouldBeInstanceOf<SmsAskOutput>().question shouldBe SmsQuestion.MESSAGE
        // a number spelled out digit by digit
        runner.next(
            FakeSkillContext(),
            call.copy(rawSlots = mapOf("to" to "five five five one two one two", "message" to "hi")),
            nested = false,
        ).shouldBeInstanceOf<SmsCardOutput>().draft.recipient shouldBe SmsRecipient("5551212", "5551212")
        sender.sent.shouldBeEmpty()

        // the card reads the text back, and a spoken yes sends it
        val card = runner.next(FakeSkillContext(), call, nested = false).shouldBeInstanceOf<SmsCardOutput>()
        answer(card, "yes, send it").shouldBeInstanceOf<SmsSendOutput>()
        sender.sent shouldContainExactly listOf(Pair("+1 555 0100", "I'm on my way"))
    }

    "SEND_SMS is requested with READ_CONTACTS before the skill runs, so a voice user can send" {
        SmsInfo.neededPermissions.map { (it as org.dicio.skill.skill.Permission.NormalPermission).id } shouldBe
                listOf("android.permission.READ_CONTACTS", "android.permission.SEND_SMS")
        PERMISSION_SEND_SMS.id shouldBe "android.permission.SEND_SMS"
    }
})
