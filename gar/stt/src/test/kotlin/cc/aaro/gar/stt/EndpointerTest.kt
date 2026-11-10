package cc.aaro.gar.stt

import cc.aaro.gar.stt.Endpointer.Clip
import cc.aaro.gar.stt.Endpointer.Decision
import cc.aaro.gar.stt.Endpointer.Reason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val FRAME = 512
private const val SPEECH = 0.9f
private const val SILENCE = 0.05f

/** Frames of [p], [count] times. */
private fun run(p: Float, count: Int) = List(count) { p }

/**
 * Feeds [probabilities] until a decision other than Continue, and returns it with the index of the
 * frame that caused it; Continue and -1 when all frames were fed.
 */
private fun Endpointer.feed(probabilities: List<Float>): Pair<Decision, Int> {
    probabilities.forEachIndexed { i, p ->
        val decision = accept(p)
        if (decision != Decision.Continue) {
            return decision to i
        }
    }
    return Decision.Continue to -1
}

private fun newEndpointer() = Endpointer().also { it.reset() }

class EndpointerTest : FunSpec({
    test("the default config: 32 ms frames, a 28 s buffer") {
        val config = Endpointer.Config()
        config.frameSamples shouldBe FRAME
        config.maxSamples shouldBe 448_000
    }

    test("speech starts with 3 of the last 4 frames, not 2") {
        val endpointer = newEndpointer()
        val alternating = List(40) { if (it % 2 == 0) SPEECH else SILENCE }
        endpointer.feed(alternating) shouldBe (Decision.Continue to -1)
        endpointer.speaking shouldBe false

        val (decision, _) = endpointer.feed(listOf(SPEECH, SPEECH, SILENCE, SPEECH))
        decision shouldBe Decision.Continue
        endpointer.speaking shouldBe true
    }

    test("speech then the 800 ms hang gives the clip with its margins") {
        val endpointer = newEndpointer()
        val frames = run(SILENCE, 10) + run(SPEECH, 20) + run(SILENCE, 40)
        val (decision, at) = endpointer.feed(frames)
        // 25 silent frames (800 ms) after the last speech frame, 29
        at shouldBe 29 + 25
        decision shouldBe Decision.Transcribe(
            Clip(start = 10 * FRAME - 4_800, end = 30 * FRAME + 3_200, speechSamples = 20 * FRAME),
            Reason.END_OF_SPEECH,
        )
    }

    test("a pause shorter than the hang doesn't end the command") {
        val endpointer = newEndpointer()
        val frames = run(SPEECH, 10) + run(SILENCE, 24) + run(SPEECH, 10) + run(SILENCE, 30)
        val (decision, at) = endpointer.feed(frames)
        at shouldBe 10 + 24 + 10 - 1 + 25
        decision shouldBe Decision.Transcribe(
            Clip(start = 0, end = 44 * FRAME + 3_200, speechSamples = 20 * FRAME),
            Reason.END_OF_SPEECH,
        )
    }

    test("once started, frames above 0.35 still count as speech") {
        val endpointer = newEndpointer()
        val frames = run(SPEECH, 5) + run(0.4f, 10) + run(0.3f, 25)
        val (decision, at) = endpointer.feed(frames)
        at shouldBe 39
        (decision as Decision.Transcribe).clip.speechSamples shouldBe 15 * FRAME
    }

    test("0.4 alone never starts speech") {
        val endpointer = newEndpointer()
        val (decision, _) = endpointer.feed(run(0.4f, 200))
        decision shouldBe Decision.Discard(Reason.NO_SPEECH)
    }

    test("no speech before the timeout gives Discard") {
        val endpointer = newEndpointer()
        val (decision, at) = endpointer.feed(run(SILENCE, 200))
        at shouldBe 125 - 1 // 4 s is 125 frames
        decision shouldBe Decision.Discard(Reason.NO_SPEECH)
    }

    test("the timeout follows Dicio's silence setting") {
        val endpointer = Endpointer()
        endpointer.reset(noSpeechMillis = 2_000)
        val (_, at) = endpointer.feed(run(SILENCE, 200))
        at shouldBe 63 - 1 // 62.5 frames, rounded up
        endpointer.reset(noSpeechMillis = 14_000)
        endpointer.feed(run(SILENCE, 500)).second shouldBe 438 - 1
    }

    test("a blip under 250 ms is forgotten and the timeout still applies") {
        val endpointer = newEndpointer()
        val frames = run(SILENCE, 10) + run(SPEECH, 4) + run(SILENCE, 200)
        val (decision, at) = endpointer.feed(frames)
        decision shouldBe Decision.Discard(Reason.NO_SPEECH)
        at shouldBe 124
    }

    test("a blip is forgotten and the command after it is clipped on its own") {
        val endpointer = newEndpointer()
        val frames = run(SILENCE, 10) + run(SPEECH, 4) + run(SILENCE, 36) + run(SPEECH, 20) +
            run(SILENCE, 30)
        val (decision, _) = endpointer.feed(frames)
        decision shouldBe Decision.Transcribe(
            Clip(start = 50 * FRAME - 4_800, end = 70 * FRAME + 3_200, speechSamples = 20 * FRAME),
            Reason.END_OF_SPEECH,
        )
    }

    test("speech that started before the timeout isn't cut by it") {
        val endpointer = newEndpointer()
        val frames = run(SILENCE, 120) + run(SPEECH, 30) + run(SILENCE, 30)
        val (decision, _) = endpointer.feed(frames)
        (decision as Decision.Transcribe).reason shouldBe Reason.END_OF_SPEECH
    }

    test("8 frames (256 ms) are enough speech, 7 are not") {
        newEndpointer().feed(run(SILENCE, 10) + run(SPEECH, 8) + run(SILENCE, 30)).first
            .shouldBeTranscribe()
        val (decision, _) = newEndpointer()
            .feed(run(SILENCE, 10) + run(SPEECH, 7) + run(SILENCE, 200))
        decision shouldBe Decision.Discard(Reason.NO_SPEECH)
    }

    test("the 28 s cap transcribes what there is") {
        val endpointer = newEndpointer()
        val (decision, at) = endpointer.feed(run(SPEECH, 1_000))
        at shouldBe 875 - 1
        decision shouldBe Decision.Transcribe(
            Clip(start = 0, end = 448_000, speechSamples = 448_000),
            Reason.CAP,
        )
    }

    test("the cap during a blip discards") {
        val endpointer = Endpointer(Endpointer.Config(maxMillis = 1_000))
        endpointer.reset()
        val (decision, _) = endpointer.feed(run(SILENCE, 25) + run(SPEECH, 10))
        decision shouldBe Decision.Discard(Reason.TOO_LITTLE)
    }

    test("finish transcribes the speech heard so far") {
        val endpointer = newEndpointer()
        endpointer.feed(run(SILENCE, 20) + run(SPEECH, 15) + run(SILENCE, 3))
        endpointer.finish() shouldBe Decision.Transcribe(
            Clip(start = 20 * FRAME - 4_800, end = 38 * FRAME, speechSamples = 15 * FRAME),
            Reason.FINISHED,
        )
    }

    test("finish with too little or no speech discards") {
        val little = newEndpointer()
        little.feed(run(SILENCE, 20) + run(SPEECH, 5))
        little.finish() shouldBe Decision.Discard(Reason.TOO_LITTLE)

        val none = newEndpointer()
        none.feed(run(SILENCE, 20))
        none.finish() shouldBe Decision.Discard(Reason.NO_SPEECH)
    }

    test("speaking follows the frames once speech started") {
        val endpointer = newEndpointer()
        endpointer.feed(run(SPEECH, 2))
        endpointer.speaking shouldBe false
        endpointer.feed(run(SPEECH, 1))
        endpointer.speaking shouldBe true
        endpointer.feed(run(SILENCE, 1))
        endpointer.speaking shouldBe false
        endpointer.feed(run(SPEECH, 1))
        endpointer.speaking shouldBe true
    }

    test("frames after the end need a reset") {
        val endpointer = newEndpointer()
        endpointer.feed(run(SILENCE, 200))
        shouldThrow<IllegalStateException> { endpointer.accept(SPEECH) }
        endpointer.reset()
        endpointer.accept(SPEECH) shouldBe Decision.Continue
        endpointer.capturedSamples shouldBe FRAME
    }

    test("other frame sizes scale the timings") {
        val endpointer = Endpointer(Endpointer.Config(frameSamples = 256))
        endpointer.reset()
        val frames = run(SPEECH, 20) + run(SILENCE, 60)
        val (decision, at) = endpointer.feed(frames)
        at shouldBe 19 + 50 // 800 ms is 50 frames of 16 ms
        (decision as Decision.Transcribe).clip shouldBe
            Clip(start = 0, end = 20 * 256 + 3_200, speechSamples = 20 * 256)
    }
})

private fun Decision.shouldBeTranscribe() {
    (this is Decision.Transcribe) shouldBe true
}
