package org.stypox.dicio.gar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import org.stypox.dicio.R
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.gar.settings.GarSettingsSerializer
import org.stypox.dicio.gar.settings.GarYou
import org.stypox.dicio.gar.settings.You
import org.stypox.dicio.gar.settings.YouChoice
import org.stypox.dicio.gar.settings.YouContact
import org.stypox.dicio.gar.stt.GarSttOwnNameStore
import org.stypox.dicio.skills.sms.AndroidOwnContact
import org.stypox.dicio.skills.sms.OwnCard
import java.io.File
import java.util.Locale

private fun settings(build: GarSettings.Builder.() -> Unit = {}): GarSettings =
    GarSettingsSerializer.defaultValue.toBuilder().apply(build).build()

/** The real English strings of `strings_gar_you.xml`, formatted like Android does. */
private fun youString(name: String, vararg args: Any): String {
    val path = "src/main/res/values/strings_gar_you.xml"
    val file = sequenceOf(File(path), File("app", path)).first { it.isFile }
    val template = Regex("<string name=\"$name\">(.*?)</string>")
        .find(file.readText())!!.groupValues[1]
        .replace("\\'", "'")
    return String.format(Locale.ROOT, template, *args)
}

/** The "You" setting's summary, as GarYouSetting writes it, with the real strings. */
private fun summary(you: You): String = GarYou.summary(you) { id, args ->
    val name = R.string::class.java.fields.first { it.getInt(null) == id }.name
    youString(name, *args)
}

private fun card(name: String, vararg numbers: String, emails: List<String> = listOf()) =
    OwnCard(name, numbers.toList(), emails)

class GarYouTest : StringSpec({
    "the summary says who GAR thinks you are and where it came from" {
        val contact = settings { setOwnContactName("Alex Herbert").setSttOwnName("Alex") }
        val alex = card("Alex Herbert", "435-555-0100")
        summary(GarYou.who(contact, alex, "Ron Herbert")) shouldBe "Alex Herbert, 435-555-0100"
        val emailOnly = card("Alex Herbert", emails = listOf("a@example.com"))
        summary(GarYou.who(contact, emailOnly, null)) shouldBe "Alex Herbert, no number"
        summary(GarYou.who(settings(), null, "Alex Herbert")) shouldBe "Alex Herbert (your card in Contacts)"
        summary(GarYou.who(settings { setSttOwnName("Alex") }, null, null)) shouldBe "Alex (name only)"
        summary(GarYou.who(settings(), null, null)) shouldBe "Not set"
        summary(GarYou.who(settings(), null, " ")) shouldBe "Not set"
    }

    "a typed name wins over the profile, as in the name hint" {
        GarYou.who(settings { setSttOwnName("Alex") }, null, "Ron Herbert") shouldBe You.NameOnly("Alex")
    }

    "a contact GAR can't find or can't read says so" {
        val contact = settings { setOwnContactName("Alex Herbert").setSttOwnName("Alex") }
        summary(GarYou.who(contact, null, null)) shouldBe "Alex Herbert (not in Contacts any more)"
        summary(GarYou.who(contact, null, null, access = false)) shouldBe
            "Alex Herbert (allow contacts access)"
    }

    "the summary says when texts go to the profile, as cards() picks the number" {
        // OwnContact.cards: the own contact, then the profile; texts take the first with a number
        val profile = card("Ron Herbert", "435-555-0199")
        val contact = settings { setOwnContactName("Alex Herbert").setSttOwnName("Alex") }
        val noNumber = card("Alex Herbert", emails = listOf("a@example.com"))
        fun textsGoTo(own: OwnCard?) = listOfNotNull(own, profile).first { it.numbers.isNotEmpty() }

        textsGoTo(noNumber) shouldBe profile
        summary(GarYou.who(contact, noNumber, "Ron Herbert", profile)) shouldBe
            "Alex Herbert, no number. Texts go to your card in Contacts"
        textsGoTo(null) shouldBe profile
        summary(GarYou.who(contact, null, "Ron Herbert", profile)) shouldBe
            "Alex Herbert (not in Contacts any more). Texts go to your card in Contacts"
        // a typed name has no card: cards() is the profile alone
        summary(GarYou.who(settings { setSttOwnName("Alex") }, null, "Ron Herbert", profile)) shouldBe
            "Alex (name only). Texts go to your card in Contacts"
        // the contact's own number wins, so nothing to add
        val alex = card("Alex Herbert", "435-555-0100")
        textsGoTo(alex) shouldBe alex
        summary(GarYou.who(contact, alex, "Ron Herbert", profile)) shouldBe "Alex Herbert, 435-555-0100"
        // a profile without a number takes no texts
        val emailProfile = card("Ron Herbert", emails = listOf("r@example.com"))
        summary(GarYou.who(settings { setSttOwnName("Alex") }, null, "Ron Herbert", emailProfile)) shouldBe
            "Alex (name only)"
    }

    "over the lock screen the dialog doesn't open and the summary has no number" {
        GarYou.canOpen(null) shouldBe false
        GarYou.canOpen(true) shouldBe false
        GarYou.canOpen(false) shouldBe true
        val contact = settings { setOwnContactName("Alex Herbert").setSttOwnName("Alex") }
        val profile = card("Ron Herbert", "435-555-0199")
        // the view model reads no card while locked; even given one, no number shows
        val locked = GarYou.who(contact, card("Alex Herbert", "435-555-0100"), "Ron Herbert", profile, locked = true)
        summary(locked) shouldBe "Alex Herbert"
        summary(GarYou.who(settings { setSttOwnName("Alex") }, null, null, profile, locked = true)) shouldBe
            "Alex (name only)"
    }

    "Clear is offered only for what GAR keeps" {
        GarYou.canClear(You.Contact("Alex Herbert", null)) shouldBe true
        GarYou.canClear(You.NameOnly("Alex")) shouldBe true
        // after Clear the profile would still show, and NotSet has nothing to clear
        GarYou.canClear(You.Profile("Alex Herbert")) shouldBe false
        GarYou.canClear(You.NotSet) shouldBe false
        GarYou.canClear(null) shouldBe false
    }

    "picking a contact sets it and its first name for the hint" {
        val picked = GarYou.apply(settings { setSttOwnName("Ron") }, YouChoice.Contact("Alex Herbert (work)"))
        picked.ownContactName shouldBe "Alex Herbert (work)"
        picked.sttOwnName shouldBe "Alex"
        picked.sttOwnNameAsked shouldBe true
        // a contact saved as "Me" names nobody: the typed name stays for the hint
        val me = GarYou.apply(settings { setSttOwnName("Ron").setSttOwnNameAsked(true) }, YouChoice.Contact("Me"))
        me.ownContactName shouldBe "Me"
        me.sttOwnName shouldBe "Ron"
        me.sttOwnNameAsked shouldBe true
        // and without one the home screen's card may still ask
        val asked = GarYou.apply(settings(), YouChoice.Contact("Me"))
        asked.sttOwnName shouldBe ""
        asked.sttOwnNameAsked shouldBe false
    }

    "Me then a typed name keeps both: text myself and the hint" {
        GarYou.namesSomeone("Me") shouldBe false
        GarYou.namesSomeone("Alex Herbert") shouldBe true
        val me = GarYou.apply(settings(), YouChoice.Contact("Me"))
        val both = GarYou.apply(me, YouChoice.Name("Alex"))
        both.ownContactName shouldBe "Me"
        both.sttOwnName shouldBe "Alex"
        both.sttOwnNameAsked shouldBe true
        val you = GarYou.who(both, card("Me", "435-555-0100"), null)
        you shouldBe You.Contact("Me", "435-555-0100", firstName = "Alex")
        summary(you) shouldBe "Me (Alex), 435-555-0100"
        // a contact that names someone shows no typed name beside it
        GarYou.who(settings { setOwnContactName("Alex Herbert").setSttOwnName("Ron") }, null, null)
            .let { (it as You.Contact).firstName } shouldBe null
    }

    "a typed name clears a contact that names someone, and a name without letters changes nothing" {
        val before = settings { setOwnContactName("Alex Herbert").setSttOwnName("Alex") }
        val typed = GarYou.apply(before, YouChoice.Name("  Ron   Jr "))
        typed.ownContactName shouldBe ""
        typed.sttOwnName shouldBe "Ron Jr"
        typed.sttOwnNameAsked shouldBe true
        GarYou.apply(before, YouChoice.Name("42")) shouldBe before
    }

    "Clear forgets both, so the home screen's card may ask again" {
        val cleared = GarYou.apply(
            settings { setOwnContactName("Alex Herbert").setSttOwnName("Alex").setSttOwnNameAsked(true) },
            YouChoice.Clear,
        )
        cleared.ownContactName shouldBe ""
        cleared.sttOwnName shouldBe ""
        cleared.sttOwnNameAsked shouldBe false
        cleared.llmEnabled shouldBe true
    }

    "the home screen's card sets You like the setting does" {
        val dataStore = GarSettingsModule.newDataStoreForPreviews(settings { setOwnContactName("Me") })
        val store = GarSttOwnNameStore(dataStore)
        store.save("Alex")
        dataStore.data.first() shouldBe GarYou.apply(settings { setOwnContactName("Me") }, YouChoice.Name("Alex"))
        // "Me" stays for "text myself"
        dataStore.data.first().ownContactName shouldBe "Me"
        store.chooseContact("Alex Herbert")
        dataStore.data.first().ownContactName shouldBe "Alex Herbert"
        dataStore.data.first().sttOwnName shouldBe "Alex"
    }

    "Which contact is you? sets You like the setting does" {
        val dataStore = GarSettingsModule.newDataStoreForPreviews(settings { setSttOwnName("Ron") })
        // remember doesn't read the contacts
        AndroidOwnContact({ error("no contacts in tests") }, dataStore).remember("Alex Herbert")
        dataStore.data.first().ownContactName shouldBe "Alex Herbert"
        dataStore.data.first().sttOwnName shouldBe "Alex"
        dataStore.data.first().sttOwnNameAsked shouldBe true
    }

    "the dialog's search finds names and numbers" {
        val contacts = listOf(
            YouContact("Alex Herbert", "435-555-0100"),
            YouContact("Mom", "+1 555 0123"),
            YouContact("Erin", null),
        )
        GarYou.search(contacts, "") shouldBe contacts
        GarYou.search(contacts, " ale ") shouldBe listOf(contacts[0])
        GarYou.search(contacts, "435 555") shouldBe listOf(contacts[0])
        GarYou.search(contacts, "5550123") shouldBe listOf(contacts[1])
        GarYou.search(contacts, "erin") shouldBe listOf(contacts[2])
        GarYou.search(contacts, "zed").shouldBeEmpty()
    }

    "the two old settings are gone" {
        R.string::class.java.fields.map { it.name }.filter {
            it.startsWith("gar_pref_own_contact") || it.startsWith("gar_stt_own_name_summary") ||
                it == "gar_stt_own_name" || it == "gar_stt_own_name_none"
        }.shouldBeEmpty()
    }
})
