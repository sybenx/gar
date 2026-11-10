package org.stypox.dicio.gar.conversation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.dicio.skill.context.SpeechOutputDevice
import org.stypox.dicio.io.speech.InstantSpeechDevice
import java.util.Locale

/**
 * Android's text to speech as `AndroidTtsSpeechDevice` drives it, behind
 * `SpeechOutputDeviceWrapper` (which tells [GarContinuedListening] about new speech): the engine
 * reports not speaking right after `speak`, until its `onDone` ([done]) comes.
 */
private class FakeTts : SpeechOutputDevice {
    private val utterances = GarUtteranceTracker()
    private val queued = ArrayDeque<String>()
    private var lastId = 0
    val said = mutableListOf<String>()

    override fun speak(speechOutput: String) {
        GarContinuedListening.onSpeak()
        lastId += 1
        utterances.queued("dicio_$lastId")
        queued.addLast("dicio_$lastId")
        said.add(speechOutput)
    }

    /** The engine's onDone for the oldest utterance. */
    fun done() {
        utterances.finished(queued.removeFirst())
    }

    override fun stopSpeaking() {
        utterances.stopped()
        queued.clear()
    }

    override val isSpeaking: Boolean get() = false

    override fun runWhenFinishedSpeaking(runnable: Runnable) {
        utterances.runWhenFinished(false, runnable)
    }

    override fun cleanup() {}
}

/** The toast and snackbar devices: nothing to hear, the runnable runs at once. */
private class FakeToast : InstantSpeechDevice() {
    val said = mutableListOf<String>()
    override fun speak(speechOutput: String) {
        GarContinuedListening.onSpeak()
        said.add(speechOutput)
    }
    override fun stopSpeaking() {}
    override fun cleanup() {}
}

private fun facts(mode: GarInputMode, spoke: Boolean, skillId: String = "weather") = GarWindowFacts(
    settingOn = true,
    spokenInput = mode == GarInputMode.SPOKEN,
    planReopens = false,
    spoke = spoke,
    conversationEnds = false,
    skillId = skillId,
    onScreen = true,
    lockedAtStart = false,
    lockedNow = false,
    ringing = false,
    call = false,
    speakerMusic = false,
    whisper = true,
    sttListening = false,
)

/**
 * What the evaluator does once a skill answered (SkillEvaluatorImpl.evaluateMatchingSkill): says
 * [speech] in the turn's [mode] through [GarSpeechMode], then schedules the window. Returns the
 * sequence numbers of the windows that opened.
 */
private class Evaluator(private val device: SpeechOutputDevice) {
    val opened = mutableListOf<Long>()

    fun answer(mode: GarInputMode, speech: String, skillId: String = "weather") {
        val spoke = GarSpeechMode.speak(device, mode, speech)
        GarContinuedListening.afterAnswer(
            device,
            facts = { facts(mode, spoke, skillId) },
            open = { opened.add(it) },
        )
    }
}

class GarContinuedSchedulingTest : StringSpec({
    "no window while the answer is still being said, one at its onDone" {
        val tts = FakeTts()
        val evaluator = Evaluator(tts)
        evaluator.answer(GarInputMode.SPOKEN, "It's sunny and 21 degrees.")
        tts.said shouldBe listOf("It's sunny and 21 degrees.")
        tts.isSpeaking.shouldBeFalse() // as TextToSpeech says right after speak
        evaluator.opened.shouldBeEmpty()
        tts.done()
        evaluator.opened.size shouldBe 1
    }

    "speech after the answer (the song's late result) makes the scheduled window stale" {
        val tts = FakeTts()
        val evaluator = Evaluator(tts)
        evaluator.answer(GarInputMode.SPOKEN, "It's sunny.")
        tts.speak("That's Dancing Queen by ABBA.")
        tts.done()
        tts.done()
        evaluator.opened.shouldBeEmpty()
    }

    "late speech during an open window closes it" {
        val tts = FakeTts()
        val evaluator = Evaluator(tts)
        evaluator.answer(GarInputMode.SPOKEN, "The flashlight is on.")
        tts.done()
        val window = evaluator.opened.single()
        var closed = 0
        GarContinuedListening.opened(window) { closed++ }.shouldBeTrue()
        tts.speak("Your pasta timer is done.")
        closed shouldBe 1
    }

    "a newer input before the answer ends: no window for the older answer" {
        val tts = FakeTts()
        val evaluator = Evaluator(tts)
        evaluator.answer(GarInputMode.SPOKEN, "It's 5 PM.")
        GarContinuedListening.onInput()
        tts.done()
        evaluator.opened.shouldBeEmpty()
    }

    "stopping the speech drops the window" {
        val tts = FakeTts()
        val evaluator = Evaluator(tts)
        evaluator.answer(GarInputMode.SPOKEN, "It's sunny.")
        tts.stopSpeaking()
        tts.speak("Something else.")
        tts.done()
        evaluator.opened.shouldBeEmpty()
    }

    "a typed turn says nothing and opens no window" {
        GarSpeechMode.speakTypedAnswers = false
        val tts = FakeTts()
        val evaluator = Evaluator(tts)
        evaluator.answer(GarInputMode.TYPED, "It's sunny.")
        tts.said.shouldBeEmpty()
        evaluator.opened.shouldBeEmpty()
    }

    "a typed turn said aloud with the setting still opens no window" {
        GarSpeechMode.speakTypedAnswers = true
        try {
            val tts = FakeTts()
            val evaluator = Evaluator(tts)
            evaluator.answer(GarInputMode.TYPED, "It's sunny.")
            tts.said shouldBe listOf("It's sunny.")
            tts.done()
            evaluator.opened.shouldBeEmpty()
        } finally {
            GarSpeechMode.speakTypedAnswers = false
        }
    }

    "a German spoken weather turn opens one" {
        val tts = FakeTts()
        val evaluator = Evaluator(tts)
        GarContinuedListening.endsConversation(Locale.GERMANY, "wie wird das Wetter morgen")
            .shouldBeFalse()
        evaluator.answer(GarInputMode.SPOKEN, "Morgen wird es sonnig, 18 Grad.")
        tts.done()
        evaluator.opened.size shouldBe 1
    }

    "after a call there is no window" {
        val tts = FakeTts()
        val evaluator = Evaluator(tts)
        evaluator.answer(GarInputMode.SPOKEN, "Calling Erin.", skillId = "telephone")
        tts.done()
        evaluator.opened.shouldBeEmpty()
    }

    "a device with nothing to hear opens the window at once" {
        val toast = FakeToast()
        val evaluator = Evaluator(toast)
        evaluator.answer(GarInputMode.SPOKEN, "It's sunny.")
        toast.said shouldBe listOf("It's sunny.")
        evaluator.opened.size shouldBe 1
    }
})
