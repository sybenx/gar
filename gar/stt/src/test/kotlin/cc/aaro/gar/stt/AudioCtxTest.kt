package cc.aaro.gar.stt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AudioCtxTest : FunSpec({
    test("short clips get the floor") {
        AudioCtx.forSamples(0) shouldBe AudioCtx.MIN_AUDIO_CTX
        AudioCtx.forSamples(16_000) shouldBe AudioCtx.MIN_AUDIO_CTX // 1 s: 50 + 8
    }

    test("longer clips get 50 positions a second plus 8") {
        AudioCtx.forSamples(3 * 16_000) shouldBe 158
        AudioCtx.forSamples(11 * 16_000) shouldBe 558 // the jfk sample
    }

    test("rounds half to even, like Python") {
        AudioCtx.forSamples(160 * 1001, min = 0) shouldBe 500 + 8 // 500.5 -> 500
        AudioCtx.forSamples(160 * 1003, min = 0) shouldBe 502 + 8 // 501.5 -> 502
        AudioCtx.forSamples(160 * 1001 + 1, min = 0) shouldBe 501 + 8
    }

    test("a lower floor is honoured") {
        AudioCtx.forSamples(16_000, min = 32) shouldBe 58
    }

    test("never above Whisper's 30 s window") {
        AudioCtx.forSamples(28 * 16_000) shouldBe 1408
        AudioCtx.forSamples(30 * 16_000) shouldBe AudioCtx.MAX_AUDIO_CTX
        AudioCtx.forSamples(60 * 16_000) shouldBe AudioCtx.MAX_AUDIO_CTX
    }

    test("negative sample counts are a bug") {
        shouldThrow<IllegalArgumentException> { AudioCtx.forSamples(-1) }
    }
})
