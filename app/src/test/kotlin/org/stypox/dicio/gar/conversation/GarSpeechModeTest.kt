package org.stypox.dicio.gar.conversation

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.speech.InstantSpeechDevice
import java.io.File

private class RecordingDevice : InstantSpeechDevice() {
    val said = mutableListOf<String>()
    override fun speak(speechOutput: String) {
        said.add(speechOutput)
    }
    override fun stopSpeaking() {}
    override fun cleanup() {}
}

private fun spoken(text: String) = InputEvent.Final(listOf(text to 0.9f), spoken = true)
private fun typed(text: String) = InputEvent.Final(listOf(text to 1.0f))

/**
 * A skill that answers at once and says its result later, like the song skill: it captures its
 * turn's mode in generateOutput, and the late speech waits for [result].
 */
private suspend fun lateSpeech(device: RecordingDevice, result: CompletableDeferred<String>) {
    val mode = GarTurnElement.current()?.mode
    GarSpeechMode.speak(device, mode, result.await())
}

class GarSpeechModeTest : StringSpec({
    "spoken input is said, typed input and buttons are shown only" {
        GarSpeechMode.speakTypedAnswers = false
        val device = RecordingDevice()
        GarSpeechMode.speak(device, GarInputMode.SPOKEN, "It's 5 PM.").shouldBeTrue()
        GarSpeechMode.speak(device, GarInputMode.TYPED, "It's 6 PM.").shouldBeFalse()
        GarSpeechMode.speak(device, null, "No turn: as Dicio did.").shouldBeTrue()
        GarSpeechMode.speak(device, GarInputMode.SPOKEN, " ").shouldBeFalse()
        device.said shouldBe listOf("It's 5 PM.", "No turn: as Dicio did.")
    }

    "the turn's mode comes from the input: buttons and typing are typed" {
        GarTurnElement.of(spoken("what time is it"), continued = false, locked = false).mode shouldBe
            GarInputMode.SPOKEN
        GarTurnElement.of(typed("what time is it"), continued = false, locked = false).mode shouldBe
            GarInputMode.TYPED
        // a card's button sends its label like this
        GarTurnElement.of(InputEvent.Final(listOf("Yes" to 1.0f)), false, false).mode shouldBe
            GarInputMode.TYPED
        val continued = GarTurnElement.of(spoken("and tomorrow"), continued = true, locked = true)
        continued.continued.shouldBeTrue()
        continued.lockedAtStart.shouldBeTrue()
    }

    "a capture that started while GAR spoke marks its own turn only" {
        GarTurnElement.markCaptureStart(wasSpeaking = true)
        GarTurnElement.of(typed("typed meanwhile"), false, false).startedWhileSpeaking
            .shouldBeFalse()
        GarTurnElement.of(spoken("cancel it"), false, false).startedWhileSpeaking.shouldBeTrue()
        GarTurnElement.of(spoken("cancel it"), false, false).startedWhileSpeaking.shouldBeFalse()
        GarTurnElement.markCaptureStart(wasSpeaking = false)
        GarTurnElement.of(spoken("cancel it"), false, false).startedWhileSpeaking.shouldBeFalse()
    }

    "Speak answers to typed requests says them too" {
        GarSpeechMode.speakTypedAnswers = true
        try {
            val device = RecordingDevice()
            GarSpeechMode.speak(device, GarInputMode.TYPED, "It's 6 PM.").shouldBeTrue()
            device.said shouldBe listOf("It's 6 PM.")
        } finally {
            GarSpeechMode.speakTypedAnswers = false
        }
    }

    "late speech follows its own turn, not the one after it" {
        GarSpeechMode.speakTypedAnswers = false
        val device = RecordingDevice()
        coroutineScope {
            // spoken turn N records a song; typed turn N+1 comes before the result
            val songN = CompletableDeferred<String>()
            val turnN = GarTurnElement.of(spoken("what song is this"), false, false)
            val late = async(turnN) { lateSpeech(device, songN) }
            withContext(GarTurnElement.of(typed("what time is it"), false, false)) {
                GarSpeechMode.speak(device, GarTurnElement.current()?.mode, "It's 5 PM.")
            }
            songN.complete("That's Dancing Queen by ABBA.")
            late.await()
        }
        device.said shouldBe listOf("That's Dancing Queen by ABBA.")

        val other = RecordingDevice()
        coroutineScope {
            // typed turn N records; spoken turn N+1 is said, N's result is not
            val songN = CompletableDeferred<String>()
            val turnN = GarTurnElement.of(typed("what song is this"), false, false)
            val late = async(turnN) { lateSpeech(other, songN) }
            withContext(GarTurnElement.of(spoken("what time is it"), false, false)) {
                GarSpeechMode.speak(other, GarTurnElement.current()?.mode, "It's 5 PM.")
            }
            songN.complete("That's Dancing Queen by ABBA.")
            late.await()
        }
        other.said shouldBe listOf("It's 5 PM.")
    }

    "no skill speaks except through GarSpeechMode" {
        val sources = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        (sources.size > 100).shouldBeTrue() // the sources are found
        val direct = Regex("""\b(speechOutputDevice|SpeechOutputDevice\(\))\s*\.\s*speak\(""")
        val allowed = setOf("GarSpeechMode.kt")
        val offenders = sources.filter { it.name !in allowed }.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> direct.containsMatchIn(line) }
                .map { (i, line) -> "${file.path}:${i + 1}: ${line.trim()}" }
        }
        withClue(offenders.joinToString("\n")) { offenders.shouldBeEmpty() }
    }
})
