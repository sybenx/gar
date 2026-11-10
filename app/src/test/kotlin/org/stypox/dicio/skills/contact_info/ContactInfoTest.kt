package org.stypox.dicio.skills.contact_info

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.ask.GarPickOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.calls.CallEntry
import org.stypox.dicio.skills.calls.CallKind
import java.time.Instant

private val MOM = ContactName(1, "Mom")
private val DAD = ContactName(2, "Dad")
private val SARAH_LEE = ContactName(3, "Sarah Lee")
private val SARAH_CONNOR = ContactName(4, "Sarah Connor")
private val STRANGERS = listOf(
    ContactName(5, "Gabi (Plumber)"), ContactName(6, "Myrtle Seller 27"), ContactName(7, "Katerina Book Club"),
)

/** Contacts and a call log in memory, with the editor's new contacts. */
class FakeContactBook(
    private val contacts: List<ContactName> = listOf(MOM, DAD, SARAH_LEE, SARAH_CONNOR) + STRANGERS,
    private val values: Map<Pair<Long, ContactField>, List<ContactValue>> = mapOf(
        (1L to ContactField.NUMBER) to listOf(ContactValue("(801) 555-1234", "Mobile")),
        (2L to ContactField.EMAIL) to listOf(ContactValue("dad@example.com", "Home")),
        (3L to ContactField.ADDRESS) to listOf(ContactValue("12 Main St\nSpringfield", "Home")),
    ),
    private val owner: Pair<String, List<ContactValue>>? = Pair("Aaron", listOf(ContactValue("+1 435 555 0167", null))),
    override val allowed: Boolean = true,
    override val callLogAllowed: Boolean = true,
    private val call: CallEntry? = null,
) : ContactBook {
    val opened = mutableListOf<NewContact>()

    override fun names() = contacts
    override fun values(contact: ContactName, field: ContactField) = values[contact.id to field].orEmpty()
    override suspend fun own(field: ContactField) = owner
    override fun lastCall() = call
    override fun openEditor(contact: NewContact): Boolean {
        opened += contact
        return true
    }
}

private fun skill(book: ContactBook, locked: Boolean = false) =
    ContactInfoSkill(ContactInfoInfo, Sentences.ContactInfo["en"]!!, book = { book }, isLocked = { locked })

class ContactInfoTest : StringSpec({
    "contact requests, whole" {
        val expected = listOf(
            "what's mom's number" to ContactRequest.Info("mom", ContactField.NUMBER),
            "what's dad's email" to ContactRequest.Info("dad", ContactField.EMAIL),
            "what's sarah's address" to ContactRequest.Info("sarah", ContactField.ADDRESS),
            "what's moms number" to ContactRequest.Info("moms", ContactField.NUMBER),
            "what is james number" to ContactRequest.Info("james", ContactField.NUMBER),
            "what's my number" to ContactRequest.Info("", ContactField.NUMBER),
            "where does grandma live" to ContactRequest.Info("grandma", ContactField.ADDRESS),
            "when is mom's birthday" to ContactRequest.Info("mom", ContactField.BIRTHDAY),
            "what's the phone number for pizza hut" to ContactRequest.Info("pizza hut", ContactField.NUMBER),
            "what's my wife's number" to ContactRequest.Info("my wife", ContactField.NUMBER),
            "add a new contact" to ContactRequest.Add("", "", lastCall = false),
            "create a contact for joe" to ContactRequest.Add("joe", "", lastCall = false),
            "save this number as joe" to ContactRequest.Add("joe", "", lastCall = true),
            "save five five five one two one two as joe" to ContactRequest.Add("joe", "5551212", lastCall = false),
            "add joe to my contacts" to ContactRequest.Add("joe", "", lastCall = false),
        )
        for ((input, request) in expected) {
            withClue(input) { ContactInfoWords.request(input) shouldBe request }
        }
        for (input in listOf(
            "what's the wifi password", "what's the tracking number", "what's your number",
            "what is the number one song", "what's the time", "add milk to my list",
            "what's my locker number", "what's my car's address",
        )) {
            withClue(input) { ContactInfoWords.request(input) shouldBe null }
        }
    }

    "contacts match by whole words, never strangers" {
        val all = listOf(MOM, DAD, SARAH_LEE, SARAH_CONNOR) + STRANGERS
        ContactInfoSkill.find(all, "mom") shouldBe listOf(MOM)
        ContactInfoSkill.find(all, "moms") shouldBe listOf(MOM)
        ContactInfoSkill.find(all, "sarah").toSet() shouldBe setOf(SARAH_LEE, SARAH_CONNOR)
        ContactInfoSkill.find(all, "myself") shouldBe listOf()
        ContactInfoSkill.find(all, "erin") shouldBe listOf()
    }

    "a contact's details, the owner's, several to pick from, none" {
        val ctx = FakeSkillContext()
        skill(FakeContactBook()).generateOutput(ctx, ContactRequest.Info("mom", ContactField.NUMBER))
            .shouldBeInstanceOf<ContactInfoOutput.Found>().values.single().value shouldBe "(801) 555-1234"
        skill(FakeContactBook()).generateOutput(ctx, ContactRequest.Info("", ContactField.NUMBER))
            .shouldBeInstanceOf<ContactInfoOutput.Found>().let {
                it.own shouldBe true
                it.name shouldBe "Aaron"
            }
        val pick = skill(FakeContactBook()).generateOutput(ctx, ContactRequest.Info("sarah", ContactField.ADDRESS))
            .shouldBeInstanceOf<GarPickOutput<ContactName>>()
        pick.pick("sarah lee") shouldBe SARAH_LEE
        pick.choose(ctx, SARAH_LEE).shouldBeInstanceOf<ContactInfoOutput.Found>().values.size shouldBe 1
        skill(FakeContactBook()).generateOutput(ctx, ContactRequest.Info("erin", ContactField.NUMBER))
            .shouldBeInstanceOf<ContactInfoOutput.NotFound>()
        skill(FakeContactBook()).generateOutput(ctx, ContactRequest.Info("dad", ContactField.NUMBER))
            .shouldBeInstanceOf<ContactInfoOutput.Found>().values shouldBe listOf()
        skill(FakeContactBook(owner = null)).generateOutput(ctx, ContactRequest.Info("", ContactField.EMAIL))
            .shouldBe(ContactInfoOutput.OwnUnknown)
    }

    "new contacts open in the editor, filled in, never saved by GAR" {
        val ctx = FakeSkillContext()
        val book = FakeContactBook(call = CallEntry("+18015550100", null, CallKind.INCOMING, Instant.EPOCH))
        skill(book).generateOutput(ctx, ContactRequest.Add("joe", "", lastCall = true))
            .shouldBeInstanceOf<ContactInfoOutput.EditorOpened>()
        skill(book).generateOutput(ctx, ContactRequest.Add("", "", lastCall = false))
        book.opened shouldBe listOf(NewContact("Joe", "+18015550100"), NewContact("", ""))

        skill(FakeContactBook(call = CallEntry("123", "Mom", CallKind.INCOMING, Instant.EPOCH)))
            .generateOutput(ctx, ContactRequest.Add("joe", "", lastCall = true))
            .shouldBeInstanceOf<ContactInfoOutput.AlreadySaved>().name shouldBe "Mom"
    }

    "locked or without permissions" {
        val ctx = FakeSkillContext()
        skill(FakeContactBook(), locked = true).generateOutput(ctx, ContactRequest.Info("mom", ContactField.NUMBER))
            .shouldBeInstanceOf<GarLockedOutput>()
        skill(FakeContactBook(allowed = false)).generateOutput(ctx, ContactRequest.Info("mom", ContactField.NUMBER))
            .shouldBeInstanceOf<MissingPermissionsSkillOutput>()
        skill(FakeContactBook(callLogAllowed = false)).generateOutput(ctx, ContactRequest.Add("joe", "", lastCall = true))
            .shouldBeInstanceOf<MissingPermissionsSkillOutput>()
    }
})
