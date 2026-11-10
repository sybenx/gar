package org.stypox.dicio.gar.stt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.gar.settings.GarSettingsSerializer
import org.stypox.dicio.settings.datastore.InputDevice

private val READ_NO_OWNER = PromptNamesState(read = true, owner = null)

/** [GarSttOwnNameAsk.shows], by default with all that makes the card ask. */
private fun shows(
    asked: Boolean? = false,
    whisperInUse: Boolean? = true,
    state: PromptNamesState? = READ_NO_OWNER,
    locked: Boolean? = false,
) = GarSttOwnNameAsk.shows(asked, whisperInUse, state, locked)

class GarSttOwnNameTest : StringSpec({
    "GAR asks once, with Whisper in use, the contacts read and no name from any source" {
        shows() shouldBe true
        // asked before (answered or skipped)
        shows(asked = true) shouldBe false
        // Vosk or another input
        shows(whisperInUse = false) shouldBe false
        // the hint off or no READ_CONTACTS
        shows(state = PromptNamesState(false, null)) shouldBe false
        // a name from the own contact, a typed name or the profile
        for (source in OwnerSource.entries) {
            shows(state = PromptNamesState(read = true, owner = PromptOwner("Alex", source))) shouldBe false
        }
        // nothing until all is known, so that the card doesn't flash
        shows(asked = null) shouldBe false
        shows(whisperInUse = null) shouldBe false
        shows(state = null) shouldBe false
        shows(locked = null) shouldBe false
    }

    "over the lock screen GAR doesn't ask, so nobody can see contacts or change the own contact" {
        shows(locked = true) shouldBe false
    }

    "Whisper is in use when it runs and is installed" {
        GarSttOwnNameAsk.whisperInUse(InputDevice.INPUT_DEVICE_WHISPER, installed = true) shouldBe true
        GarSttOwnNameAsk.whisperInUse(InputDevice.INPUT_DEVICE_WHISPER, installed = false) shouldBe false
        GarSttOwnNameAsk.whisperInUse(InputDevice.INPUT_DEVICE_VOSK, installed = true) shouldBe false
    }

    "the typed name is trimmed, and a name without letters isn't saved" {
        GarSttOwnNameAsk.typed("  Alex ") shouldBe "Alex"
        GarSttOwnNameAsk.typed("Mary   Jane") shouldBe "Mary Jane"
        GarSttOwnNameAsk.typed("") shouldBe null
        GarSttOwnNameAsk.typed("   ") shouldBe null
        GarSttOwnNameAsk.typed("42") shouldBe null
        GarSttOwnNameAsk.typed("A") shouldBe null
    }

    "a typed name that is a contact's offers that contact as the user's own" {
        val names = { listOf("Alex Herbert", "Mom", "Eric") }
        GarSttOwnNameAsk.contactOffer("Alex", names) shouldBe listOf("Alex Herbert")
        GarSttOwnNameAsk.contactOffer("alex herbert", names) shouldBe listOf("Alex Herbert")
        // two contacts share it: both to choose from
        GarSttOwnNameAsk.contactOffer("Alex", { listOf("Alex Herbert", "Alex Smith", "Mom") }) shouldBe
            listOf("Alex Herbert", "Alex Smith")
        // only close to a contact, or no contact: nothing offered
        GarSttOwnNameAsk.contactOffer("Erin", names) shouldBe emptyList()
        GarSttOwnNameAsk.contactOffer("Zed", names) shouldBe emptyList()
        GarSttOwnNameAsk.contactOffer("Alex", { emptyList() }) shouldBe emptyList()
    }

    "a new install asks; saving or skipping is remembered, and the other settings stay" {
        GarSettingsSerializer.defaultValue.sttOwnNameAsked shouldBe false
        GarSettingsSerializer.defaultValue.sttOwnName shouldBe ""
        val dataStore = GarSettingsModule.newDataStoreForPreviews()
        val store = GarSttOwnNameStore(dataStore)
        store.asked.first() shouldBe false
        store.skip()
        store.asked.first() shouldBe true
        dataStore.data.first().sttOwnName shouldBe ""
        store.save("Alex")
        store.asked.first() shouldBe true
        dataStore.data.first().sttOwnName shouldBe "Alex"
        dataStore.data.first().llmEnabled shouldBe true
    }

    "the contact offer shows up to two numbers, so same-named contacts differ" {
        OwnContactOffer("Alex Herbert", listOf("435-555-0100")).numbersText shouldBe "435-555-0100"
        OwnContactOffer("Alex", listOf("435-555-0100", "+1 435 555 0199", "555 0123")).numbersText shouldBe
            "435-555-0100, +1 435 555 0199"
        OwnContactOffer("Alex", listOf()).numbersText shouldBe ""
    }
})
