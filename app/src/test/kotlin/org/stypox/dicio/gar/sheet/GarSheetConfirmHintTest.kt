package org.stypox.dicio.gar.sheet

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.ui.GarMicState
import org.stypox.dicio.gar.ui.garble.GarbleMarkState
import org.stypox.dicio.skills.sms.SmsCardOutput
import org.stypox.dicio.skills.sms.SmsDraft
import org.stypox.dicio.skills.sms.SmsFlow
import org.stypox.dicio.skills.sms.SmsRecipient

class GarSheetConfirmHintTest : StringSpec({
    val sms = GarSheetConfirm.Sms(
        SmsCardOutput(SmsFlow(), SmsDraft(SmsRecipient("Mom", "555 0100"), "hi"), nested = false)
    )
    val states = listOf(
        GarMicState.Idle, GarMicState.Starting, GarMicState.Listening(null),
        GarMicState.Listening(5_000L), GarMicState.Speaking, GarMicState.Transcribing,
    )

    "the SMS hint shows in every mic state, also after typed input with the mic closed" {
        states.forEach { garSheetShowsConfirmHint(sms, it) shouldBe true }
    }

    "a yes/no hint only shows while the mic listens" {
        garSheetShowsConfirmHint(GarSheetConfirm.YesNo, GarMicState.Idle) shouldBe false
        garSheetShowsConfirmHint(GarSheetConfirm.YesNo, GarMicState.Speaking) shouldBe false
        garSheetShowsConfirmHint(GarSheetConfirm.YesNo, GarMicState.Starting) shouldBe true
        garSheetShowsConfirmHint(GarSheetConfirm.YesNo, GarMicState.Listening(5_000L)) shouldBe true
    }

    "the hint's mark follows the open mic, else it is the logo" {
        garSheetConfirmHintMark(GarMicState.Listening(5_000L), voice = false) shouldBe
            GarbleMarkState.Listening
        garSheetConfirmHintMark(GarMicState.Listening(5_000L), voice = true) shouldBe
            GarbleMarkState.Hearing
        garSheetConfirmHintMark(GarMicState.Starting, voice = false) shouldBe GarbleMarkState.Listening
        garSheetConfirmHintMark(GarMicState.Idle, voice = false) shouldBe GarbleMarkState.Logo
        garSheetConfirmHintMark(GarMicState.Speaking, voice = true) shouldBe GarbleMarkState.Logo
        garSheetConfirmHintMark(GarMicState.Transcribing, voice = false) shouldBe GarbleMarkState.Logo
    }
})
