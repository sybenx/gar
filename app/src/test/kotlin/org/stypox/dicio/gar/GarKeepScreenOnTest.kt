package org.stypox.dicio.gar

import androidx.lifecycle.Lifecycle
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.settings.GarSettingsSerializer
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.PendingQuestion
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class GarKeepScreenOnTest : StringSpec({
    "the screen stays on by default, for new installs and for settings files from before" {
        GarKeepScreenOn.settingOn(GarSettingsSerializer.defaultValue) shouldBe true
        GarKeepScreenOn.settingOn(GarSettings.getDefaultInstance()) shouldBe true
        val empty = GarSettingsSerializer.readFrom(ByteArrayInputStream(byteArrayOf()))
        GarKeepScreenOn.settingOn(empty) shouldBe true
    }

    "turning the switch off is written and read back" {
        val off = GarSettingsSerializer.defaultValue.toBuilder().setScreenMaySleep(true).build()
        val bytes = ByteArrayOutputStream().also { GarSettingsSerializer.writeTo(off, it) }
        val read = GarSettingsSerializer.readFrom(ByteArrayInputStream(bytes.toByteArray()))
        GarKeepScreenOn.settingOn(read) shouldBe false
    }

    "the flag is set only while the screen is visible and the setting is on" {
        for (state in Lifecycle.State.entries) {
            val visible = state == Lifecycle.State.STARTED || state == Lifecycle.State.RESUMED
            val keep = { settingOn: Boolean, locked: Boolean, interacting: Boolean ->
                GarKeepScreenOn.shouldKeepScreenOn(state, settingOn, locked, interacting)
            }
            withClue(state) {
                keep(true, false, false) shouldBe visible
                keep(true, false, true) shouldBe visible
                // over the lock screen, e.g. after the wake word turned the screen on: only while
                // the user talks to GAR or did within the window
                keep(true, true, true) shouldBe visible
                keep(true, true, false) shouldBe false
                keep(false, false, false) shouldBe false
                keep(false, false, true) shouldBe false
                keep(false, true, true) shouldBe false
            }
        }
    }

    "loading, listening, transcribing and answering count as talking to GAR" {
        val idle = InteractionLog(interactions = listOf(), pendingQuestion = null)
        val answering = idle.copy(pendingQuestion = PendingQuestion("call mom", false, null))
        GarKeepScreenOn.busy(SttState.Listening, idle) shouldBe true
        GarKeepScreenOn.busy(SttState.Transcribing, idle) shouldBe true
        GarKeepScreenOn.busy(SttState.WaitingForResult, idle) shouldBe true
        GarKeepScreenOn.busy(SttState.Loading(thenStartListening = true), idle) shouldBe true
        GarKeepScreenOn.busy(SttState.Loaded, answering) shouldBe true
        GarKeepScreenOn.busy(SttState.Loaded, idle) shouldBe false
        GarKeepScreenOn.busy(null, idle) shouldBe false
        GarKeepScreenOn.busy(SttState.NotLoaded, idle) shouldBe false
    }
})
