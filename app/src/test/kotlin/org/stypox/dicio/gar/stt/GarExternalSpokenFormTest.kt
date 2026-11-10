package org.stypox.dicio.gar.stt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.input.InputEvent
import java.util.Locale

class GarExternalSpokenFormTest : StringSpec({
    "English results become GAR's spoken form, with the first one's written text" {
        garExternalFinal(
            listOf("Set a timer for 5 minutes.", "[BLANK_AUDIO]", "Call 555-1234."),
            listOf(0.9f, 0.5f, 0.2f),
            Locale.ENGLISH,
        ) shouldBe InputEvent.Final(
            listOf(
                "set a timer for five minutes" to 0.9f,
                "call five five five one two three four" to 0.2f,
            ),
            "Set a timer for 5 minutes.",
            spoken = true,
        )
    }

    "the written text is the first kept result's" {
        garExternalFinal(listOf("?", "Call mom."), listOf(0.9f, 0.4f), Locale.US) shouldBe
            InputEvent.Final(listOf("call mom" to 0.4f), "Call mom.", spoken = true)
    }

    "English results without words are None" {
        garExternalFinal(listOf("[BLANK_AUDIO]", "(music)"), listOf(1f, 1f), Locale.US) shouldBe
            InputEvent.None
    }

    "other languages are left as they are" {
        garExternalFinal(listOf("Stell einen Timer auf 5 Minuten"), listOf(1f), Locale.GERMAN) shouldBe
            InputEvent.Final(listOf("Stell einen Timer auf 5 Minuten" to 1f), spoken = true)
    }
})
