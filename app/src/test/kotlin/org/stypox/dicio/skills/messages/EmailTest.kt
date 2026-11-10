package org.stypox.dicio.skills.messages

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.skills.sms.FakeOwnContact
import org.stypox.dicio.skills.sms.OwnCard
import org.stypox.dicio.skills.sms.OwnContactAskOutput
import org.stypox.dicio.skills.sms.OwnContactRememberedOutput

private val SARAH_CONNOR = EmailContact("Sarah Connor", listOf("sarah@sky.net"))
private val SARAH_LEE = EmailContact("Sarah Lee", listOf("slee@example.com", "sarah.lee@work.example"))
private val BOSS = EmailContact("Boss", listOf("boss@example.com"))
private val MOM = EmailContact("Mom", listOf("mom@example.com"))
private val NO_EMAIL = EmailContact("Grandma", listOf())
private val CONTACTS = listOf(SARAH_CONNOR, SARAH_LEE, BOSS, MOM, NO_EMAIL)

private class FakeMailer(var hasApp: Boolean = true) : Mailer {
    val composed = mutableListOf<EmailDraft>()

    override fun compose(draft: EmailDraft): Boolean {
        composed.add(draft)
        return hasApp
    }
}

private fun emailFlow(mailer: Mailer = FakeMailer(), locked: Boolean = false) =
    EmailFlow(findContacts = { CONTACTS }, mailerFor = { mailer }, isDeviceLocked = { locked })

class EmailTest : StringSpec({
    "the recipient is a contact with an email address" {
        EmailRecipients.choose("mom", CONTACTS) shouldBe
            EmailChoice.One(EmailRecipient("Mom", "mom@example.com"))
        EmailRecipients.choose("my boss", CONTACTS) shouldBe
            EmailChoice.One(EmailRecipient("Boss", "boss@example.com"))
        EmailRecipients.choose("sarah connor", CONTACTS) shouldBe
            EmailChoice.One(EmailRecipient("Sarah Connor", "sarah@sky.net"))
        EmailRecipients.choose("sarah", CONTACTS) shouldBe EmailChoice.Several(listOf(SARAH_CONNOR, SARAH_LEE))
        EmailRecipients.choose("sarah lee", CONTACTS) shouldBe EmailChoice.Several(listOf(SARAH_LEE))
        EmailRecipients.choose("someone@example.org", CONTACTS) shouldBe
            EmailChoice.One(EmailRecipient("someone@example.org", "someone@example.org"))
        EmailRecipients.choose("grandma", listOf(NO_EMAIL)) shouldBe EmailChoice.NotFound
        EmailRecipients.choose("", CONTACTS) shouldBe EmailChoice.NotFound
    }

    "which one: a position, a name or an address" {
        val sarahs = listOf(SARAH_CONNOR, SARAH_LEE)
        EmailRecipients.pick("the second one", sarahs, ENGLISH_PARSER_FORMATTER) shouldBe
            EmailPick.Picked(EmailRecipient("Sarah Lee", "slee@example.com"))
        EmailRecipients.pick("three", sarahs, ENGLISH_PARSER_FORMATTER) shouldBe
            EmailPick.Picked(EmailRecipient("Sarah Lee", "sarah.lee@work.example"))
        EmailRecipients.pick("connor", sarahs, ENGLISH_PARSER_FORMATTER) shouldBe
            EmailPick.Picked(EmailRecipient("Sarah Connor", "sarah@sky.net"))
        EmailRecipients.pick("sarah lee", sarahs, ENGLISH_PARSER_FORMATTER) shouldBe
            EmailPick.Narrowed(SARAH_LEE)
        EmailRecipients.pick("sarah.lee@work.example", sarahs, ENGLISH_PARSER_FORMATTER) shouldBe
            EmailPick.Picked(EmailRecipient("Sarah Lee", "sarah.lee@work.example"))
        EmailRecipients.pick("the weather", sarahs, ENGLISH_PARSER_FORMATTER).shouldBeNull()
    }

    "the subject: as said after about, or the text's first words" {
        EmailDraft.subjectFor("the meeting", "I'll be late") shouldBe "The meeting"
        EmailDraft.subjectFor(null, "The report is done") shouldBe "The report is done"
        EmailDraft.subjectFor(null, "The report is done. I sent it to Bob.") shouldBe "The report is done"
        EmailDraft.subjectFor(null, "Can we move the meeting to friday because I have a dentist appointment") shouldBe
            "Can we move the meeting to friday because…"
        EmailDraft.subjectFor(null, "Are you coming?") shouldBe "Are you coming?"
    }

    "the mail app gets a mailto address with the subject and the text" {
        val draft = EmailDraft(EmailRecipient("Sarah", "sarah+work@example.com"), "Report", "It's done")
        val intent = EmailIntent.compose(draft)
        intent.action shouldBe "android.intent.action.SENDTO"
        intent.data shouldBe "mailto:sarah+work@example.com"
        intent.extras shouldBe mapOf(
            "android.intent.extra.SUBJECT" to "Report",
            "android.intent.extra.TEXT" to "It's done",
        )
        EmailIntent.mailto("josé o'neil@example.com") shouldBe "mailto:jos%C3%A9%20o%27neil@example.com"
    }

    "email sarah saying the report is done: the mail app opens filled in" {
        val mailer = FakeMailer()
        val output = emailFlow(mailer).start(FakeSkillContext(), "sarah connor", null, "saying the report is done")
            .shouldBeInstanceOf<EmailOpenedOutput>()
        output.opened shouldBe true
        mailer.composed shouldContainExactly listOf(
            EmailDraft(EmailRecipient("Sarah Connor", "sarah@sky.net"), "The report is done", "The report is done")
        )

        // the name and the text in one capture
        emailFlow(mailer).start(FakeSkillContext(), "my boss i'll be late today", null, null)
            .shouldBeInstanceOf<EmailOpenedOutput>().draft shouldBe
            EmailDraft(EmailRecipient("Boss", "boss@example.com"), "I'll be late today", "I'll be late today")

        emailFlow(FakeMailer(hasApp = false)).start(FakeSkillContext(), "mom", "dinner", "see you at six")
            .shouldBeInstanceOf<EmailOpenedOutput>().let {
                it.opened shouldBe false
                it.draft.subject shouldBe "Dinner"
            }
    }

    "what is missing is asked for, by voice" {
        val mailer = FakeMailer()
        val flow = emailFlow(mailer)
        val who = flow.start(FakeSkillContext(), null, null, null).shouldBeInstanceOf<EmailAskOutput>()
        who.question shouldBe EmailQuestion.RECIPIENT
        val which = rankerAfter(who).evaluate("to sarah").shouldBeInstanceOf<EmailChooseOutput>()
        which.contacts shouldContainExactly listOf(SARAH_CONNOR, SARAH_LEE)
        val ranker = rankerAfter(which)
        val address = ranker.evaluate("sarah lee").shouldBeInstanceOf<EmailChooseOutput>()
        address.contacts shouldContainExactly listOf(SARAH_LEE)
        val text = ranker.evaluate("the second one").shouldBeInstanceOf<EmailAskOutput>()
        text.question shouldBe EmailQuestion.MESSAGE
        text.recipient shouldBe EmailRecipient("Sarah Lee", "sarah.lee@work.example")
        mailer.composed.shouldBeEmpty()
        ranker.evaluate("the slides are attached").shouldBeInstanceOf<EmailOpenedOutput>()
        mailer.composed.single().body shouldBe "The slides are attached"

        val unknown = flow.start(FakeSkillContext(), "grandma", null, "hi").shouldBeInstanceOf<EmailAskOutput>()
        unknown.notFound shouldBe "grandma"
        rankerAfter(unknown).evaluate("cancel").shouldBeInstanceOf<EmailCancelledOutput>()
    }

    "a contact matches by whole words, and no, to Aaron looks Aaron up instead" {
        // seen on Aaron's phone: "erin" found "Katerina Book Club" by its letters
        val katerina = EmailContact("Katerina Book Club", listOf("club@example.com"))
        EmailRecipients.choose("erin", listOf(katerina, MOM)) shouldBe EmailChoice.NotFound
        EmailRecipients.pick("erin", listOf(katerina, MOM), ENGLISH_PARSER_FORMATTER).shouldBeNull()
        EmailRecipients.choose("katerina", listOf(katerina, MOM)) shouldBe
            EmailChoice.One(EmailRecipient("Katerina Book Club", "club@example.com"))

        val sarahs = listOf(SARAH_CONNOR, SARAH_LEE)
        EmailChooserAnswer.of("no I meant mom", sarahs, ENGLISH_PARSER_FORMATTER) shouldBe
            EmailChooserAnswer.Correction("mom")
        EmailChooserAnswer.of("no, the second one", sarahs, ENGLISH_PARSER_FORMATTER) shouldBe
            EmailChooserAnswer.Pick(EmailPick.Picked(EmailRecipient("Sarah Lee", "slee@example.com")))
        EmailChooserAnswer.of("never mind", sarahs, ENGLISH_PARSER_FORMATTER) shouldBe EmailChooserAnswer.Cancel
        EmailChooserAnswer.of("no not the first one the second one", sarahs, ENGLISH_PARSER_FORMATTER) shouldBe
            EmailChooserAnswer.Pick(EmailPick.Picked(EmailRecipient("Sarah Lee", "slee@example.com")))
        EmailChooserAnswer.of("not the first one", sarahs, ENGLISH_PARSER_FORMATTER) shouldBe
            EmailChooserAnswer.Again
        EmailChooserAnswer.of("not mom dad", listOf(MOM, BOSS), ENGLISH_PARSER_FORMATTER) shouldBe
            EmailChooserAnswer.Correction("dad")
        EmailChooserAnswer.of("no, I'm good", sarahs, ENGLISH_PARSER_FORMATTER).shouldBeNull()

        val mailer = FakeMailer()
        val which = emailFlow(mailer).start(FakeSkillContext(), "sarah", null, "hi")
            .shouldBeInstanceOf<EmailChooseOutput>()
        rankerAfter(which).evaluate("no I meant mom").shouldBeInstanceOf<EmailOpenedOutput>()
            .draft.recipient shouldBe EmailRecipient("Mom", "mom@example.com")
        rankerAfter(which).evaluate("no to erin").shouldBeInstanceOf<EmailAskOutput>().notFound shouldBe "erin"
        // and in reply to "Who should I email?"
        val who = emailFlow(mailer).start(FakeSkillContext(), null, null, "hi")
            .shouldBeInstanceOf<EmailAskOutput>()
        rankerAfter(who).evaluate("no, to the boss").shouldBeInstanceOf<EmailOpenedOutput>()
            .draft.recipient shouldBe EmailRecipient("Boss", "boss@example.com")
    }

    "email myself: the owner's address, or which contact is you" {
        val mailer = FakeMailer()
        fun flow(own: FakeOwnContact) = EmailFlow(
            findContacts = { CONTACTS },
            mailerFor = { mailer },
            isDeviceLocked = { false },
            ownContact = { own },
        )
        val profile = OwnCard("Aaron", listOf("+14355550167"), listOf("aaron@example.com"))
        flow(FakeOwnContact(profile = profile)).start(FakeSkillContext(), "myself", null, "the slides")
            .shouldBeInstanceOf<EmailOpenedOutput>().draft.recipient shouldBe
            EmailRecipient("Aaron", "aaron@example.com")
        // "email me the slides" captures it all as the recipient
        flow(FakeOwnContact(profile = profile)).start(FakeSkillContext(), "me the slides", null, null)
            .shouldBeInstanceOf<EmailOpenedOutput>().draft.body shouldBe "The slides"
        flow(FakeOwnContact(profile = profile)).start(FakeSkillContext(), "me", null, null)
            .shouldBeInstanceOf<EmailAskOutput>().recipient shouldBe EmailRecipient("Aaron", "aaron@example.com")

        // a card without an address doesn't make an email: GAR asks which contact is you
        val own = FakeOwnContact(
            contacts = CONTACTS.map { OwnCard(it.name, listOf(), it.addresses) },
            profile = OwnCard("Aaron", listOf("+14355550167"), listOf()),
        )
        val ask = flow(own).start(FakeSkillContext(), "myself", null, "the slides")
            .shouldBeInstanceOf<OwnContactAskOutput>()
        val remembered = rankerAfter(ask).evaluate("i'm the boss").shouldBeInstanceOf<OwnContactRememberedOutput>()
        remembered.name shouldBe "Boss"
        own.remembered shouldContainExactly listOf("Boss")
        remembered.then.shouldBeInstanceOf<GarFollowUpOutput>().output.shouldBeInstanceOf<EmailOpenedOutput>()
            .draft.recipient shouldBe EmailRecipient("Boss", "boss@example.com")
        rankerAfter(ask).evaluate("cancel").shouldBeInstanceOf<EmailCancelledOutput>()
    }

    "a locked phone opens nothing" {
        val mailer = FakeMailer()
        emailFlow(mailer, locked = true).start(FakeSkillContext(), "mom", null, "hi")
            .shouldBeInstanceOf<GarLockedOutput>()
        mailer.composed.shouldBeEmpty()
    }

    "an email match says email outside the captures" {
        for ((input, captures) in listOf(
            "send an email to sarah" to listOf("sarah"),
            "email mom saying hi" to listOf("mom", "hi"),
            "send an e-mail to the boss" to listOf("the boss"),
            "write a mail to dad" to listOf("dad"),
        )) {
            withClue(input) { MessageWords.saysEmail(input, captures) shouldBe true }
        }
        MessageWords.saysEmail("send a text to mom", listOf("mom")) shouldBe false
        // the email in a capture doesn't count
        MessageWords.saysEmail("text mom about the email", listOf("mom about the email")) shouldBe false
    }
})
