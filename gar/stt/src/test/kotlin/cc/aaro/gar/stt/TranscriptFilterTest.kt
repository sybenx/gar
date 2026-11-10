package cc.aaro.gar.stt

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TranscriptFilterTest : FunSpec({
    test("a command passes, whatever Whisper thinks of the audio") {
        TranscriptFilter.accept("set a timer for five minutes", 0.1f, 1_500) shouldBe true
        TranscriptFilter.accept("set a timer for five minutes", 0.9f, 300) shouldBe true
    }

    test("empty text never passes") {
        TranscriptFilter.accept("", 0f, 2_000) shouldBe false
        TranscriptFilter.accept("   ", 0f, 2_000) shouldBe false
    }

    test("a stock phrase on little speech with a high no-speech probability is dropped") {
        for (phrase in TranscriptFilter.SILENCE_PHRASES) {
            TranscriptFilter.accept(phrase, 0.8f, 400) shouldBe false
        }
        TranscriptFilter.accept(" thank you ", 0.8f, 400) shouldBe false
    }

    test("a stock phrase passes when one of the three conditions fails") {
        TranscriptFilter.accept("thank you", 0.6f, 400) shouldBe true // not above 0.6
        TranscriptFilter.accept("thank you", 0.8f, 600) shouldBe true // enough speech
        TranscriptFilter.accept("thank you very much", 0.8f, 400) shouldBe true // not stock
        TranscriptFilter.accept("bye", 0.2f, 300) shouldBe true // Whisper heard speech
    }

    val names = listOf("aaron", "mom", "sarah lee")

    test("a command with a prompt name passes") {
        TranscriptFilter.accept("send a text to aaron", 0.1f, 1_200, 0.3f, names) shouldBe true
        TranscriptFilter.accept("call mom", 0.9f, 300, 0.2f, names) shouldBe true
    }

    test("a name said on its own passes when Whisper is sure of it") {
        TranscriptFilter.accept("aaron", 0.05f, 400, 0.85f, names) shouldBe true
        TranscriptFilter.accept("sarah lee", 0.05f, 500, 0.8f, names) shouldBe true
        // without a prompt the guard doesn't apply
        TranscriptFilter.accept("aaron", 0.05f, 400, 0.2f) shouldBe true
    }

    test("prompt text Whisper wasn't sure of is dropped") {
        TranscriptFilter.accept("aaron", 0.05f, 400, 0.3f, names) shouldBe false
        TranscriptFilter.accept("sarah lee", 0.1f, 900, 0.49f, names) shouldBe false
        TranscriptFilter.accept("aaron", 0.05f, 400, Float.NaN, names) shouldBe false
    }

    test("prompt text on what Whisper calls no speech is dropped, however sure") {
        TranscriptFilter.accept("mom", 0.8f, 1_500, 0.95f, names) shouldBe false
    }

    test("a list of prompt names is dropped, however sure") {
        // Whisper copying " Aaron, Mom, Sarah Lee." into unclear audio
        TranscriptFilter.accept("aaron mom sarah lee", 0.1f, 2_000, 0.95f, names) shouldBe false
        TranscriptFilter.accept("aaron aaron", 0.1f, 2_000, 0.95f, names) shouldBe false
    }

    test("a short list of prompt names is dropped unless Whisper is very sure") {
        TranscriptFilter.accept("aaron mom", 0.1f, 2_000, 0.7f, names) shouldBe false
        // harmless: no contact is called that
        TranscriptFilter.accept("aaron mom", 0.1f, 2_000, 0.9f, names) shouldBe true
    }

    test("a full name made of separate prompt names passes when said clearly") {
        // "Sarah Lee" didn't fit; "Sarah" and "Lee" (of Lee Wong) did
        val separate = listOf("aaron", "sarah", "lee")
        TranscriptFilter.accept("sarah lee", 0.05f, 600, 0.9f, separate) shouldBe true
        TranscriptFilter.accept("sarah lee", 0.05f, 600, 0.6f, separate) shouldBe false
    }

    test("the silence phrases still apply with a prompt") {
        TranscriptFilter.accept("thank you", 0.8f, 400, 0.9f, names) shouldBe false
        TranscriptFilter.accept("", 0f, 2_000, 0.9f, names) shouldBe false
    }

    test("the rejection says why a transcript is dropped, and nothing for one that passes") {
        TranscriptFilter.rejection("call mom", 0.1f, 1_500, 0.9f, names) shouldBe null
        TranscriptFilter.rejection(" ", 0f, 2_000) shouldBe TranscriptFilter.REJECT_EMPTY
        TranscriptFilter.rejection("thank you", 0.8f, 400) shouldBe
            TranscriptFilter.REJECT_SILENCE_PHRASE
        TranscriptFilter.rejection("aaron mom sarah lee", 0.1f, 2_000, 0.95f, names) shouldBe
            TranscriptFilter.REJECT_PROMPT_NAMES
    }

    test("the score is the confidence within 0 and 1") {
        TranscriptFilter.score(0.83f) shouldBe 0.83f
        TranscriptFilter.score(1.2f) shouldBe 1f
        TranscriptFilter.score(-0.1f) shouldBe 0f
        TranscriptFilter.score(Float.NaN) shouldBe 0f
    }
})
