package cc.aaro.gar.stt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.random.Random

/**
 * Runs [WhisperEngine] and [SileroVad] on the JVM with a host build of the JNI library. Skipped
 * unless GAR_STT_HOST_JNI_LIB (from host-test/build-host-jni.sh), GAR_STT_TEST_MODEL
 * (ggml-base.en-acft-q8_0.bin) and GAR_STT_TEST_VAD (ggml-silero-v6.2.0.bin) are set.
 *
 * The WAVs in resources/wav are 16 kHz mono PCM16, made with macOS `say -v Samantha -o` and
 * `afconvert -f WAVE -d LEI16@16000 -c 1`. The host script also converts whisper.cpp's JFK sample
 * next to the library; that check is skipped without it.
 */
class WhisperEngineHostTest : FunSpec({
    val library = System.getenv("GAR_STT_HOST_JNI_LIB").orEmpty()
    val modelPath = System.getenv("GAR_STT_TEST_MODEL").orEmpty()
    val vadPath = System.getenv("GAR_STT_TEST_VAD").orEmpty()
    val enabled = library.isNotEmpty() && modelPath.isNotEmpty()
    val vadEnabled = library.isNotEmpty() && vadPath.isNotEmpty()
    val jfk = if (library.isEmpty()) null else File(File(library).parentFile, "jfk.wav").takeIf { it.isFile }

    lateinit var engine: WhisperEngine

    suspend fun transcribe(pcm: FloatArray, audioCtx: Int = AudioCtx.forSamples(pcm.size)): Transcript {
        val t = engine.transcribe(pcm, pcm.size, audioCtx)
        log("%.2f s, ctx %d: %s (no_speech %.3f, confidence %.3f, %d tokens, encode %d ms, total %d ms)".format(
            pcm.size / 16000.0, t.audioCtx, t.text.quoted(), t.noSpeechProb, t.confidence, t.tokens,
            t.encodeMicros / 1000, t.totalMicros / 1000,
        ))
        return t
    }

    beforeSpec {
        if (library.isNotEmpty()) {
            System.setProperty(WhisperNative.LIBRARY_PATH_PROPERTY, library)
        }
        if (enabled) {
            val start = System.nanoTime()
            engine = WhisperEngine.load(File(modelPath))
            log("load ${(System.nanoTime() - start) / 1_000_000} ms, whisper.cpp ${engine.whisperVersion}, ${engine.systemInfo}")
        }
    }

    afterSpec {
        if (enabled) {
            engine.close()
        }
    }

    test("transcribes the say commands").config(enabled = enabled) {
        // as Whisper writes them; plain() drops case and punctuation ("7.30." is "7 30")
        val expected = mapOf(
            "timer" to "set a timer for 5 minutes",
            "alarm" to "wake me up at 7 30",
            "call" to "call aaron",
            "weather" to "what's the weather like tomorrow",
        )
        for ((name, words) in expected) {
            val t = transcribe(readWav("wav/$name.wav"))
            plain(t.text) shouldBe words
            t.audioCtx shouldBe AudioCtx.MIN_AUDIO_CTX
            t.tokens shouldBeGreaterThan 0
            t.confidence shouldBeGreaterThan 0.4f
            t.noSpeechProb shouldBeLessThan 0.2f
            t.encodeMicros shouldBeGreaterThan 0L
            t.totalMicros shouldBeGreaterThan t.encodeMicros
        }
    }

    test("JFK sample").config(enabled = enabled && jfk != null) {
        val t = transcribe(readWav(jfk!!))
        t.audioCtx shouldBe 558
        plain(t.text) shouldContain "ask not what your country can do for you"
    }

    test("the clip-sized context and the full one give the same text").config(enabled = enabled) {
        for (name in listOf("timer", "call")) {
            val pcm = readWav("wav/$name.wav")
            val small = transcribe(pcm)
            val full = transcribe(pcm, AudioCtx.MAX_AUDIO_CTX)
            plain(full.text) shouldBe plain(small.text)
        }
    }

    test("the same clip gives the same result whatever ran before").config(enabled = enabled) {
        // with whisper.cpp's flash attention, a small audio_ctx read stale cross K/V left by
        // earlier calls; "Call Aaron." came out as "call your phone" after a 1500 context run
        val call = readWav("wav/call.wav")
        val first = transcribe(call)
        transcribe(longClip(readWav("wav/weather.wav")), AudioCtx.MAX_AUDIO_CTX)
        transcribe(readWav("wav/timer.wav"), 192)
        val again = transcribe(call)
        again.text shouldBe first.text
        again.confidence shouldBe first.confidence
        again.noSpeechProb shouldBe first.noSpeechProb
    }

    test("silence, noise and too short clips give no words").config(enabled = enabled) {
        transcribe(FloatArray(100)).text shouldBe ""
        // Whisper writes "...", "(silence)" or "[BLANK_AUDIO]" on these; the device filters them
        val silence = transcribe(FloatArray(2 * 16000))
        words(silence.text) shouldBe ""
        val random = Random(7)
        val noise = transcribe(FloatArray(2 * 16000) { (random.nextFloat() - 0.5f) * 0.01f })
        words(noise.text) shouldBe ""
        noise.noSpeechProb shouldBeGreaterThan 0.5f
        // an empty clip doesn't reuse the previous call's audio
        transcribe(readWav("wav/call.wav"))
        transcribe(FloatArray(0)).text shouldBe ""
    }

    test("cancel stops a full-context run at the end of the encoder, and the next call works").config(enabled = enabled) {
        val pcm = longClip(readWav("wav/weather.wav"))
        val reference = transcribe(pcm, AudioCtx.MAX_AUDIO_CTX)
        coroutineScope {
            val run = async { runCatching { engine.transcribe(pcm, pcm.size, AudioCtx.MAX_AUDIO_CTX) } }
            delay(100)
            val start = System.nanoTime()
            engine.cancel()
            val result = run.await()
            val millis = (System.nanoTime() - start) / 1_000_000
            log("cancel took $millis ms, the uncancelled run ${reference.totalMicros / 1000} ms")
            (result.exceptionOrNull() is CancellationException) shouldBe true
            // At this pin whisper.cpp checks the abort callback after the encoder and between
            // decoder steps, not inside the encoder graph, so the encode finishes first.
            millis shouldBeLessThan reference.encodeMicros / 1000 + 300
        }
        plain(transcribe(readWav("wav/call.wav")).text) shouldBe "call aaron"
    }

    test("cancelling the coroutine aborts the native call").config(enabled = enabled) {
        val pcm = longClip(readWav("wav/weather.wav"))
        coroutineScope {
            val run = async { engine.transcribe(pcm, pcm.size, AudioCtx.MAX_AUDIO_CTX) }
            delay(100)
            val start = System.nanoTime()
            run.cancel()
            run.join()
            // the caller is released at once, while the engine thread finishes the encoder
            ((System.nanoTime() - start) / 1_000_000) shouldBeLessThan 100L
            // queued behind the aborted call
            plain(transcribe(readWav("wav/call.wav")).text) shouldBe "call aaron"
        }
    }

    test("invalid arguments and files are rejected").config(enabled = enabled) {
        shouldThrow<IllegalArgumentException> { engine.transcribe(FloatArray(16000), 16000, AudioCtx.MAX_AUDIO_CTX + 1) }
        shouldThrow<IllegalArgumentException> { engine.transcribe(FloatArray(10), 11, 128) }
        shouldThrow<WhisperException> { WhisperEngine.load(File("/nonexistent/model.bin")) }
        val junk = Files.createTempFile("gar-whisper", ".bin").toFile()
        try {
            junk.writeBytes(ByteArray(4096) { it.toByte() })
            shouldThrow<WhisperException> { WhisperEngine.load(junk) }
        } finally {
            junk.delete()
        }
    }

    test("tokenizes text").config(enabled = enabled) {
        val tokens = engine.tokenize(" Aaron")
        tokens.size shouldBeGreaterThan 0
    }

    test("a prompt of names keeps the command and fits the budget").config(enabled = enabled) {
        val names = listOf("Aaron", "Erin", "Mom", "Sarah Lee")
        val call = readWav("wav/call.wav")
        engine.prepare(names) // as the input device does while the user talks
        val t = engine.transcribe(call, call.size, AudioCtx.MIN_AUDIO_CTX, names = names)
        log("prompt ${t.promptTokens} tokens ${t.promptNames}: ${t.text.quoted()} (confidence %.3f)".format(t.confidence))
        plain(t.text) shouldBe "call aaron"
        t.promptNames shouldBe names
        t.promptTokens shouldBe engine.tokenize(NamePrompt.text(names)).size
        // more names than fit: cut to the budget
        val many = (1..60).map { "Contact$it" }
        val timer = readWav("wav/timer.wav")
        val cut = engine.transcribe(timer, timer.size, AudioCtx.MIN_AUDIO_CTX, names = many)
        (cut.promptTokens in 1..NamePrompt.MAX_TOKENS) shouldBe true
        cut.promptNames shouldBe many.take(cut.promptNames.size)
        // a prompt without digits can make Whisper write numbers as words (whisper-cli does)
        plain(cut.text) shouldBeIn listOf("set a timer for 5 minutes", "set a timer for five minutes")
        // without names, no prompt
        engine.transcribe(FloatArray(16000), 16000, AudioCtx.MIN_AUDIO_CTX).promptTokens shouldBe 0
    }

    test("names a prompt leaks into silence don't pass the filter").config(enabled = enabled) {
        val names = listOf("Aaron", "Erin", "Mom", "Sarah Lee")
        val silence = engine.transcribe(FloatArray(2 * 16000), 2 * 16000, AudioCtx.MIN_AUDIO_CTX, names = names)
        log("silence with a prompt: ${silence.text.quoted()} (no_speech %.3f, confidence %.3f)".format(
            silence.noSpeechProb, silence.confidence))
        val spoken = words(silence.text)
        val spokenNames = names.map { plain(it) }
        // at whisper-cli's settings it writes "you" here, which the silence phrases catch when
        // no_speech is high; only names are this guard's business
        val passes = TranscriptFilter.accept(spoken, silence.noSpeechProb, 0, silence.confidence, spokenNames)
        (passes && NamePrompt.onlyNames(spoken, spokenNames)) shouldBe false
    }

    test("Silero is low in silence and high in speech").config(enabled = vadEnabled) {
        SileroVad(File(vadPath)).use { vad ->
            vad.frameSamples shouldBe 512
            val silence = frameProbabilities(vad, FloatArray(16000))
            log("VAD silence max %.3f".format(silence.max()))
            silence.max() shouldBeLessThan 0.2f

            vad.reset()
            val speech = frameProbabilities(vad, readWav("wav/weather.wav"))
            log("VAD speech: " + speech.joinToString(" ") { "%.2f".format(it) })
            speech.count { it >= 0.5f } shouldBeGreaterThan speech.size / 2

            vad.reset()
            frameProbabilities(vad, FloatArray(16000)).max() shouldBeLessThan 0.2f

            shouldThrow<IllegalArgumentException> { vad.probability(FloatArray(100)) }
        }
        shouldThrow<java.io.IOException> { SileroVad(File("/nonexistent/vad.bin")) }
    }
}) {
    private companion object {
        fun readWav(resource: String): FloatArray {
            val stream = WhisperEngineHostTest::class.java.classLoader.getResourceAsStream(resource)
                ?: error("missing resource $resource")
            return parseWav(stream.use { it.readBytes() })
        }

        fun readWav(file: File): FloatArray = parseWav(file.readBytes())

        /** 16 kHz mono PCM16 WAV to floats; skips chunks other than fmt and data (afconvert adds FLLR). */
        fun parseWav(bytes: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = buf.getInt(pos + 4)
                if (id == "fmt ") {
                    require(buf.getShort(pos + 8).toInt() == 1) { "not PCM" }
                    require(buf.getShort(pos + 10).toInt() == 1) { "not mono" }
                    require(buf.getInt(pos + 12) == 16000) { "not 16 kHz" }
                    require(buf.getShort(pos + 22).toInt() == 16) { "not 16 bit" }
                } else if (id == "data") {
                    val n = minOf(size, bytes.size - pos - 8) / 2
                    return FloatArray(n) { buf.getShort(pos + 8 + 2 * it) / 32768f }
                }
                pos += 8 + size + (size and 1)
            }
            error("no data chunk")
        }

        /** The clip at the start of 28 s, the capture cap; the rest silent. */
        fun longClip(pcm: FloatArray): FloatArray = pcm.copyOf(28 * 16000)

        fun frameProbabilities(vad: SpeechDetector, pcm: FloatArray): List<Float> =
            (0 until pcm.size step vad.frameSamples).map { start ->
                // the last frame is zero-padded
                val frame = FloatArray(vad.frameSamples)
                pcm.copyInto(frame, 0, start, minOf(start + vad.frameSamples, pcm.size))
                vad.probability(frame)
            }

        /** Lowercase words, without punctuation other than apostrophes. */
        fun plain(text: String): String =
            text.lowercase().replace(Regex("[^a-z0-9'\\s]"), " ").trim().split(Regex("\\s+")).joinToString(" ")

        /** [plain] without Whisper's non-speech markers ("[BLANK_AUDIO]", "(silence)"). */
        fun words(text: String): String = plain(text.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " "))

        fun String.quoted() = "\"" + replace("\n", "\\n") + "\""

        fun log(message: String) = println("GAR-HOST-TEST $message")
    }
}
