package org.stypox.dicio.gar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import org.stypox.dicio.gar.assist.GarAssistantCardStore
import org.stypox.dicio.gar.assist.GarAssistantPrompt
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.gar.settings.GarSettingsSerializer

class GarAssistantCardTest : StringSpec({
    "the card shows until GAR is the assistant or the user dismissed it" {
        GarAssistantPrompt.shows(hidden = false, isAssistant = false) shouldBe true
        GarAssistantPrompt.shows(hidden = true, isAssistant = false) shouldBe false
        GarAssistantPrompt.shows(hidden = false, isAssistant = true) shouldBe false
        // nothing until both are known, so that the card doesn't flash
        GarAssistantPrompt.shows(hidden = null, isAssistant = false) shouldBe false
        GarAssistantPrompt.shows(hidden = false, isAssistant = null) shouldBe false
    }

    "becoming the assistant hides the card for good, once" {
        GarAssistantPrompt.hidesForGood(hidden = false, isAssistant = true) shouldBe true
        GarAssistantPrompt.hidesForGood(hidden = true, isAssistant = true) shouldBe false
        GarAssistantPrompt.hidesForGood(hidden = false, isAssistant = false) shouldBe false
        GarAssistantPrompt.hidesForGood(hidden = null, isAssistant = true) shouldBe false
        GarAssistantPrompt.hidesForGood(hidden = false, isAssistant = null) shouldBe false
    }

    "the button opens Assist and voice input, else the default apps, else Settings" {
        // the role isn't requestable, so no RoleManager request intent
        GarAssistantPrompt.SETTINGS_ACTIONS shouldContainExactly listOf(
            "android.settings.VOICE_INPUT_SETTINGS",
            "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
            "android.settings.SETTINGS",
        )
        val tried = mutableListOf<String>()
        val opened = GarAssistantPrompt.openFirst {
            tried.add(it)
            it == "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"
        }
        opened shouldBe true
        tried shouldContainExactly listOf(
            "android.settings.VOICE_INPUT_SETTINGS",
            "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
        )
        GarAssistantPrompt.openFirst { false } shouldBe false
    }

    "a new install shows the card, and hiding it is saved" {
        GarSettingsSerializer.defaultValue.assistantCardHidden shouldBe false
        val dataStore = GarSettingsModule.newDataStoreForPreviews()
        val store = GarAssistantCardStore(dataStore)
        store.hidden.first() shouldBe false
        store.hide()
        store.hidden.first() shouldBe true
        store.hide() // again: nothing changes
        dataStore.data.first().assistantCardHidden shouldBe true
        // the other settings stay
        dataStore.data.first().llmEnabled shouldBe true
    }
})
