package org.stypox.dicio.gar.stt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.settings.GarSttContactNames

private fun sources(
    owner: List<String> = emptyList(),
    starred: List<String> = emptyList(),
    recent: List<String> = emptyList(),
) = ContactPromptNames.Sources(owner, starred, recent)

class ContactPromptNamesTest : StringSpec({
    "first names of the owner, starred and recent callers, then their full names" {
        ContactPromptNames.names(
            sources(
                // the remembered own contact, the typed name and the profile
                owner = listOf("Alex Herbert", "alex", "Alex Herbert"),
                starred = listOf("Mom", "Sarah Lee"),
                recent = listOf("Erin Smith", "Sarah Lee"),
            )
        ) shouldBe listOf("Alex", "Mom", "Sarah", "Erin", "Alex Herbert", "Sarah Lee", "Erin Smith")
    }

    "the owner is first even when starred or a recent caller" {
        ContactPromptNames.names(
            sources(owner = listOf("", "Alex"), starred = listOf("Mom", "Alex Herbert"), recent = listOf("Alex"))
        ) shouldBe listOf("Alex", "Mom", "Alex Herbert")
    }

    "no owner known, nobody starred, no call log: no names, never other contacts" {
        ContactPromptNames.names(sources(owner = listOf(""), recent = listOf("Bob Jones"))) shouldBe
            listOf("Bob", "Bob Jones")
        ContactPromptNames.names(sources()) shouldBe emptyList()
    }

    "the owner's name comes from the own contact, then the typed name, then the profile" {
        val all = listOf(
            OwnerSource.SETTING to "Alex Herbert",
            OwnerSource.ASKED to "Ron",
            OwnerSource.PROFILE to "Ronnie Herbert (me)",
        )
        ContactPromptNames.owner(all) shouldBe PromptOwner("Alex", OwnerSource.SETTING)
        ContactPromptNames.owner(all.drop(1)) shouldBe PromptOwner("Ron", OwnerSource.ASKED)
        ContactPromptNames.owner(all.drop(2)) shouldBe PromptOwner("Ronnie", OwnerSource.PROFILE)
        // the order the settings are read in, the one "You" shows them in
        OwnerSource.entries shouldBe listOf(OwnerSource.SETTING, OwnerSource.ASKED, OwnerSource.PROFILE)
        // the phone that heard "Call Alexa": no own contact, no profile, the name typed
        ContactPromptNames.owner(listOf(OwnerSource.SETTING to "", OwnerSource.ASKED to " alex ")) shouldBe
            PromptOwner("alex", OwnerSource.ASKED)
        // a number saved as the name gives none
        ContactPromptNames.owner(listOf(OwnerSource.PROFILE to "+1 555 0100", OwnerSource.ASKED to "")) shouldBe null
        ContactPromptNames.owner(emptyList()) shouldBe null
    }

    "an own contact saved as Me or My number is no name: GAR asks, and it takes no prompt slot" {
        ContactPromptNames.owner(listOf(OwnerSource.SETTING to "Me", OwnerSource.ASKED to "")) shouldBe null
        ContactPromptNames.owner(listOf(OwnerSource.SETTING to "Me", OwnerSource.ASKED to "Alex")) shouldBe
            PromptOwner("Alex", OwnerSource.ASKED)
        ContactPromptNames.owner(listOf(OwnerSource.SETTING to "My Number", OwnerSource.PROFILE to "home")) shouldBe
            null
        ContactPromptNames.names(sources(owner = listOf("Me", "", "Alex"), starred = listOf("Mom"))) shouldBe
            listOf("Alex", "Mom")
    }

    "picking a contact for You keeps its cleaned first name, and none for a placeholder" {
        ContactPromptNames.ownerFirstName("Alex Herbert") shouldBe "Alex"
        ContactPromptNames.ownerFirstName("Alex Herbert (work)") shouldBe "Alex"
        ContactPromptNames.ownerFirstName("Me") shouldBe null
        ContactPromptNames.ownerFirstName("+1 555 0100") shouldBe null
    }

    "whether the owner went into a prompt" {
        ContactPromptNames.hasOwner(listOf("Alex", "Mom"), "alex") shouldBe true
        ContactPromptNames.hasOwner(listOf("Alexa", "Mom"), "Alex") shouldBe false
        ContactPromptNames.hasOwner(emptyList(), "Alex") shouldBe false
        SttBenchLog.ownerInPrompt(listOf("Alex"), true, PromptOwner("Alex", OwnerSource.ASKED)) shouldBe
            "in (asked)"
        SttBenchLog.ownerInPrompt(listOf("Mom"), true, PromptOwner("Alex", OwnerSource.PROFILE)) shouldBe
            "out (profile)"
        SttBenchLog.ownerInPrompt(listOf("Mom"), true, null) shouldBe "unknown"
        SttBenchLog.ownerInPrompt(listOf("Alex"), false, null) shouldBe "n/a"
    }

    "a long full name gives only its first name" {
        ContactPromptNames.names(sources(starred = listOf("Maria de los Angeles Garcia", "Christopher Montgomery-Smith"))) shouldBe
            listOf("Maria", "Christopher")
    }

    "names are cleaned of brackets, emoji, digits and symbols" {
        ContactPromptNames.clean("Mom (work)") shouldBe "Mom"
        ContactPromptNames.clean("Sarah Lee 🙂") shouldBe "Sarah Lee"
        ContactPromptNames.clean("Dr. Who [old]") shouldBe "Dr Who"
        ContactPromptNames.clean("O'Brien") shouldBe "O'Brien"
        ContactPromptNames.clean("Mary-Jane  Watson") shouldBe "Mary-Jane Watson"
        ContactPromptNames.clean("José") shouldBe "José"
        ContactPromptNames.clean("Plumber 2") shouldBe "Plumber"
        ContactPromptNames.clean("+1 555 0100") shouldBe null
        ContactPromptNames.clean("J") shouldBe null
        ContactPromptNames.clean("  ") shouldBe null
    }

    "a number saved as a caller's name is left out" {
        ContactPromptNames.names(sources(recent = listOf("+1 555 0100", "Erin"))) shouldBe listOf("Erin")
    }

    "at most MAX_CANDIDATES names" {
        // 200 different first names
        val starred = (0 until 200).map { "${'A' + it % 26}${'a' + it / 26}on Smith" }
        ContactPromptNames.names(sources(starred = starred)) shouldHaveSize ContactPromptNames.MAX_CANDIDATES
    }

    "the setting's default is one constant" {
        GarSttPromptNames.contactNamesOn(GarSttContactNames.GAR_STT_CONTACT_NAMES_DEFAULT) shouldBe
            GarSttPromptNames.ON_BY_DEFAULT
        GarSttPromptNames.contactNamesOn(GarSttContactNames.GAR_STT_CONTACT_NAMES_ON) shouldBe true
        GarSttPromptNames.contactNamesOn(GarSttContactNames.GAR_STT_CONTACT_NAMES_OFF) shouldBe false
    }
})
