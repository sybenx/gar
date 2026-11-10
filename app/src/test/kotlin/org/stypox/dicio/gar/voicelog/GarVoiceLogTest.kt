package org.stypox.dicio.gar.voicelog

import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.tools.SentenceMapping
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import cc.aaro.gar.stt.Endpointer
import cc.aaro.gar.stt.Transcript
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.stypox.dicio.gar.perf.GarFrameSummary
import org.stypox.dicio.gar.stt.GarWhisperInputDevice
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.zip.ZipFile

class GarVoiceLogTest : StringSpec({
    "a spoken turn is one JSON line with every stage, and logcat gets no words" {
        val h = Harness()
        h.turns.hint(GarVoiceStart.MIC_BUTTON)
        val turn = h.speak("set a timer for five minutes", raw = "Set a timer for 5 minutes.")
        turn.scored("timer", 0.97f)
        turn.scored("alarm", 0.41f)
        turn.scored("timer", 0.5f) // a lower score again keeps the best
        turn.scored("calculator", 0.3f)
        turn.scored("weather", 0.1f)
        turn.matchChecked("timer", "accept")
        h.advance(10)
        turn.routedBy("sentence")
        h.advance(30)
        h.turns.answered(turn, "timer", "Timer set for 5 minutes", "TimerOutput", false, null)
        h.advance(400)
        h.turns.firstWord()
        h.lines().shouldBeEmpty() // held for the next turn's signals
        h.endHold()

        val line = h.lines().single()
        line.str("start") shouldBe "mic"
        line.str("id") shouldBe turn.id
        line.obj("stt").str("engine") shouldBe "whisper"
        line.obj("stt").str("model") shouldBe "ggml-base.en-acft-q8_0.bin"
        line["locked"]!!.jsonPrimitive.boolean shouldBe false
        val capture = line.obj("capture")
        capture.str("end") shouldBe "vad_silence"
        capture.long("speech_start_ms") shouldBe 400
        capture.long("speech_ms") shouldBe 1500
        capture.long("clip_ms") shouldBe 2187
        capture.long("audio_ms") shouldBe 2500
        capture.long("audio_ctx") shouldBe 128
        capture["peak_dbfs"]!!.jsonPrimitive.double shouldBe -20.0
        capture["mean_dbfs"]!!.jsonPrimitive.double shouldBe -20.0
        val prompt = line.obj("prompt")
        prompt["on"]!!.jsonPrimitive.boolean shouldBe true
        prompt.long("tokens") shouldBe 5
        prompt["names"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Aaron", "Mom")
        prompt["owner_included"]!!.jsonPrimitive.boolean shouldBe true
        prompt.str("owner_source") shouldBe "setting"
        val transcript = line.obj("transcript")
        transcript.str("raw") shouldBe "Set a timer for 5 minutes."
        transcript["confidence"]!!.jsonPrimitive.double shouldBe 0.77
        transcript.long("decode_ms") shouldBe 216
        transcript.str("filter") shouldBe "accept"
        line.str("spoken") shouldBe "set a timer for five minutes"
        line.str("input") shouldBe "set a timer for five minutes"
        line.str("wav") shouldBe "${turn.id}.wav"
        File(h.files.wavDir, "${turn.id}.wav").exists() shouldBe true
        val match = line.obj("match")
        match["candidates"]!!.jsonArray.map { it.jsonObject.str("skill") } shouldBe
            listOf("timer", "alarm", "calculator")
        match["candidates"]!!.jsonArray[0].jsonObject["score"]!!.jsonPrimitive.double shouldBe 0.97
        match.str("check") shouldBe "accept"
        match.str("path") shouldBe "sentence"
        val answer = line.obj("answer")
        answer.str("outcome") shouldBe "answered"
        answer.str("skill") shouldBe "timer"
        answer.str("speech") shouldBe "Timer set for 5 minutes"
        answer.str("card") shouldBe "TimerOutput"
        val latency = line.obj("latency")
        latency.long("to_text_ms") shouldBe 200
        latency.long("to_route_ms") shouldBe 210
        latency.long("to_first_word_ms") shouldBe 640
        line["suspect"].shouldBeNull()

        val summary = h.logs.single()
        summary shouldContain "start=mic"
        summary shouldContain "route=sentence"
        summary shouldContain "answer=answered:timer"
        summary shouldNotContain "five"
        summary shouldNotContain "Aaron"
        summary shouldNotContain "Timer set"
    }

    "the model's call, its rewrite and its filter are recorded" {
        val h = Harness()
        val turn = h.turns.input(VoiceInput.Final(listOf("pause the music"))).shouldNotBeNull()
        val call = RouterOutput.Call(
            MEDIA_TOOL, mapOf("action" to "pause"), listOf(), "pause the music"
        )
        turn.rewritten("media_play{query=pause the music}", "media_control{action=pause}")
        turn.modelOutput(call, 412)
        turn.routedBy("model")
        h.turns.answered(turn, "media", "", "MediaOutput", false, null)
        h.endHold()
        val model = h.lines().single().obj("model")
        model.str("outcome") shouldBe "call"
        model.str("tool") shouldBe "media_control"
        model.obj("args").str("action") shouldBe "pause"
        model.str("rewrite") shouldBe "media_play{query=pause the music} -> media_control{action=pause}"
        model.str("filter") shouldBe "keep"
        model.long("ms") shouldBe 412

        val dropped = Harness()
        val other = dropped.turns.input(VoiceInput.Final(listOf("track my package"))).shouldNotBeNull()
        other.modelOutput(RouterOutput.Dropped(call, "doesn't ask about what plays now"), 300)
        other.routedBy("fallback")
        dropped.turns.answered(other, "text", "Could you repeat?", "TextFallbackOutput", false,
            VoiceSignals.ASKED_TO_REPEAT)
        dropped.endHold()
        val line = dropped.lines().single()
        line.obj("model").str("filter") shouldBe "drop"
        line.obj("model").str("filter_reason") shouldBe "doesn't ask about what plays now"
        line["suspect"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("asked_to_repeat")
    }

    "a typed turn and a button's turn start as such, with latencies from the input" {
        val h = Harness()
        h.turns.hint(GarVoiceStart.TYPED)
        val typed = h.turns.input(VoiceInput.Final(listOf("what time is it"))).shouldNotBeNull()
        h.advance(5)
        typed.routedBy("sentence")
        h.turns.answered(typed, "current_time", "It's 5 PM", "CurrentTimeOutput", false, null)
        h.advance(60_000) // the hold ends with no new turn
        val button = h.turns.input(VoiceInput.Final(listOf("Yes"))).shouldNotBeNull()
        h.turns.answered(button, "gar_confirm", "", "GarConfirmOutput", false, null)
        h.endHold()
        val lines = h.lines()
        lines.map { it.str("start") } shouldBe listOf("typed", "button")
        lines[0].obj("latency").long("to_route_ms") shouldBe 5
        lines[0]["stt"].shouldBeNull()
    }

    "a turn records the frames drawn from its start to its answer" {
        val asked = ArrayList<Pair<Long, Long>>()
        val h = Harness(frames = { from, to ->
            asked.add(from to to)
            GarFrameSummary("sheet", 42, 2, 8.333, 9.1, 40.0, 51.256, 120)
        })
        h.turns.hint(GarVoiceStart.TYPED)
        val typed = h.turns.input(VoiceInput.Final(listOf("what time is it"))).shouldNotBeNull()
        h.advance(250)
        h.turns.answered(typed, "current_time", "It's 5 PM", "CurrentTimeOutput", false, null)
        h.endHold()
        asked shouldBe listOf(1_000L to 1_250L)
        val frames = h.lines().single().obj("frames")
        frames.str("surface") shouldBe "sheet"
        frames.long("n") shouldBe 42
        frames.long("janky") shouldBe 2
        frames["p50_ms"]!!.jsonPrimitive.double shouldBe 8.33
        frames["max_ms"]!!.jsonPrimitive.double shouldBe 51.26
        frames.long("hz") shouldBe 120
    }

    "a turn without frames has no frames field, and a failing source drops only it" {
        val h = Harness(frames = { _, _ -> throw IllegalStateException("no frames") })
        h.turns.hint(GarVoiceStart.TYPED)
        val typed = h.turns.input(VoiceInput.Final(listOf("what time is it"))).shouldNotBeNull()
        h.turns.answered(typed, "current_time", "It's 5 PM", "CurrentTimeOutput", false, null)
        h.endHold()
        h.warnings.single() shouldStartWith "Could not read the frame times"
        h.warnings.clear()
        h.lines().single()["frames"].shouldBeNull()
    }

    "a capture without input is written at once and keeps the held turn" {
        val h = Harness()
        val first = h.spokenAnswer("call erin", "telephone")
        h.advance(2_000)
        h.turns.hint(GarVoiceStart.MIC_BUTTON)
        val silent = h.turns.captureStarted("whisper").shouldNotBeNull()
        silent.captureEnded("no_speech", 64_000, null, null)
        h.turns.input(VoiceInput.None).shouldBeNull()
        h.lines().map { it.obj("answer").str("outcome") } shouldBe listOf("no_input")
        h.lines()[0].obj("capture").str("end") shouldBe "no_speech"
        h.advance(3_000)
        h.spokenAnswer("call aaron", "telephone")
        // the retry still marks the first command, the silent one in between doesn't count
        val firstLine = h.lines().single { it.str("id") == first.id }
        firstLine["suspect"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("repeat")
    }

    "a repeat or rephrase within 20 s marks the previous turn, its WAV is kept as suspect" {
        val h = Harness()
        val first = h.spokenAnswer("call erin", "telephone")
        h.advance(5_000)
        val second = h.spokenAnswer("call aaron", "telephone")
        val line = h.lines().single()
        line.str("id") shouldBe first.id
        line["suspect"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("repeat")
        line.str("suspect_by") shouldBe second.id
        line.str("wav") shouldBe "${first.id}.suspect.wav"
        File(h.files.wavDir, "${first.id}.suspect.wav").exists() shouldBe true

        // more than 20 s later the same words are a new command, not a retry
        h.advance(25_000)
        h.spokenAnswer("call aaron", "telephone")
        h.lines()[1]["suspect"].shouldBeNull()
    }

    "no, cancel, stop, that's wrong, I said and an edit mark the previous turn" {
        for ((input, previousCard, reason) in listOf(
            Triple("no", "TimerOutput", "said_no"),
            Triple("no", "GarConfirmOutput", "cancelled_confirmation"),
            Triple("cancel", "GarConfirmOutput", "cancelled_confirmation"),
            Triple("stop", "WeatherOutput", "said_stop"),
            Triple("uh that's wrong", "WeatherOutput", "said_wrong"),
            Triple("no I said call aaron", "TelephoneOutput", "i_said"),
        )) {
            val h = Harness()
            h.spokenAnswer("what's the weather", "weather", card = previousCard)
            h.advance(40_000) // after the repeat window, within the hold
            h.spokenAnswer(input, "conversation")
            h.lines().single()["suspect"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                listOf(reason)
        }

        val h = Harness()
        h.spokenAnswer("text mom i'm late", "sms")
        h.turns.editing("text mom i'm late")
        h.turns.hint(GarVoiceStart.TYPED)
        val edited = h.turns.input(VoiceInput.Final(listOf("text mom I'm running late")))
        edited.shouldNotBeNull()
        h.lines().shouldBeEmpty() // judged once the edit is answered
        h.turns.answered(edited, "sms", "Send it?", "SmsOutput", false, null)
        h.lines().single()["suspect"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
            listOf("edited_transcript")
    }

    "the signals' similarity measure" {
        VoiceSignals.similarity("set a timer for five minutes", "set a timer for ten minutes") shouldBe
            (1.0 - 4.0 / 28)
        VoiceSignals.nearRepeat(listOf("call", "erin"), listOf("call", "aaron")) shouldBe true
        VoiceSignals.nearRepeat(listOf("what", "time", "is", "it"),
            listOf("set", "an", "alarm")) shouldBe false
        VoiceSignals.overlap(listOf("call", "my", "mom"), listOf("please", "call", "mom")) shouldBe 1.0
        VoiceSignals.overlap(listOf("timer"), listOf("timer")) shouldBe 0.0
        VoiceSignals.reasons(
            VoiceSignals.Turn("call my mom"), VoiceSignals.Turn("please call mom now"), 3_000, null
        ) shouldBe listOf(VoiceSignals.REPHRASE)
        VoiceSignals.reasons(
            VoiceSignals.Turn("what time is it"), VoiceSignals.Turn("set an alarm"), 3_000, null
        ).shouldBeEmpty()
        VoiceSignals.negative(VoiceSignals.words("No thanks.")).shouldBeNull()
        VoiceSignals.negative(VoiceSignals.words("Nope!")) shouldBe VoiceSignals.SAID_NO
        VoiceSignals.words("Oh, stop stop please") shouldBe listOf("stop")
    }

    "GAR not understanding marks the turn itself" {
        val h = Harness()
        val turn = h.turns.input(VoiceInput.Final(listOf("blorp"))).shouldNotBeNull()
        h.turns.answered(turn, "text", "I don't understand", "TextFallbackOutput", false,
            VoiceSignals.NOT_UNDERSTOOD)
        h.endHold()
        h.lines().single()["suspect"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
            listOf("not_understood")
    }

    "errors, the permission card and STT errors are recorded" {
        val h = Harness()
        val turn = h.turns.input(VoiceInput.Final(listOf("call mom"))).shouldNotBeNull()
        h.turns.answered(turn, "telephone", "", "MissingPermissionsSkillOutput", true, null)
        h.endHold()
        val failing = h.turns.input(VoiceInput.Final(listOf("weather"))).shouldNotBeNull()
        h.turns.failed(failing, IllegalStateException("no network"))
        h.endHold()
        h.turns.hint(GarVoiceStart.HOTWORD)
        val capture = h.turns.captureStarted("whisper").shouldNotBeNull()
        capture.captureFailed(java.io.IOException("mic busy"))
        h.turns.input(VoiceInput.Error(java.io.IOException("I couldn't use the microphone.")))
        val lines = h.lines()
        lines[0].obj("answer")["permission_card"]!!.jsonPrimitive.boolean shouldBe true
        lines[0].obj("answer").str("outcome") shouldBe "missing_permissions"
        lines[1].obj("answer").str("error_class") shouldBe "java.lang.IllegalStateException"
        lines[1]["suspect"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("error")
        lines[2].str("start") shouldBe "hotword"
        lines[2].obj("answer").str("outcome") shouldBe "stt_error"
        lines[2].obj("capture").str("end") shouldBe "error"
        lines[2].obj("capture").str("error") shouldContain "mic busy"
    }

    "a button tapped while listening is its own turn, and old hints expire" {
        val h = Harness()
        h.turns.hint(GarVoiceStart.ASSIST)
        val capture = h.turns.captureStarted("whisper").shouldNotBeNull()
        h.turns.hint(GarVoiceStart.MIC_BUTTON) // the tap that ends the capture
        val button = h.turns.input(VoiceInput.Final(listOf("No"))).shouldNotBeNull()
        (button === capture) shouldBe false
        button.record.start shouldBe GarVoiceStart.BUTTON
        capture.record.start shouldBe GarVoiceStart.ASSIST

        val late = Harness()
        late.turns.hint(GarVoiceStart.HOTWORD)
        late.advance(VoiceTurns.HINT_MILLIS + 1)
        late.turns.captureStarted("whisper").shouldBeNull()
    }

    "another app's dictation has no turn, and a button after it gets its own" {
        val h = Harness()
        // SttService or the speech popup: a capture without a hint
        h.turns.captureStarted("whisper").shouldBeNull()
        val button = h.turns.input(VoiceInput.Final(listOf("Yes"))).shouldNotBeNull()
        h.turns.answered(button, "gar_confirm", "", "GarConfirmOutput", false, null)
        h.endHold()
        val line = h.lines().single()
        line.str("start") shouldBe "button"
        line["transcript"].shouldBeNull()
        line["wav"].shouldBeNull()
        h.files.wavDir.list().orEmpty().toList().shouldBeEmpty()
    }

    "Clear drops the turns in flight and a recording that comes after it" {
        val h = Harness()
        h.turns.hint(GarVoiceStart.MIC_BUTTON)
        val routing = h.speak("what's the weather") // the model takes seconds
        h.turns.hint(GarVoiceStart.HOTWORD)
        val transcribing = h.turns.captureStarted("whisper").shouldNotBeNull()
        h.turns.clear()
        transcribing.transcribed(transcript("Hi."), "hi", "Hi.", null, FloatArray(160))
        h.turns.answered(routing, "weather", "Sunny", "WeatherOutput", false, null)
        h.endHold()
        h.lines().shouldBeEmpty()
        h.files.wavDir.exists() shouldBe false

        // after the log went off and on again too
        val again = Harness()
        val before = again.turns.input(VoiceInput.Final(listOf("hello"))).shouldNotBeNull()
        again.turns.enabled = false
        again.turns.enabled = true
        again.turns.answered(before, "small_talk", "Hi", "SmallTalkOutput", false, null)
        again.endHold()
        again.lines().shouldBeEmpty()
    }

    "commands said again, and stop or no that a skill took, are not retries" {
        fun reasonsFor(
            first: String, firstSkill: String, second: String, secondSkill: String,
            secondPath: String = "sentence",
        ): List<String> {
            val h = Harness()
            h.spokenAnswer(first, firstSkill)
            h.advance(4_000)
            h.spokenAnswer(second, secondSkill, path = secondPath)
            h.endHold()
            return h.lines()[0]["suspect"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf()
        }
        reasonsFor("volume up", "volume", "volume up", "volume").shouldBeEmpty()
        reasonsFor("next", "media_controls", "next", "media_controls").shouldBeEmpty()
        reasonsFor("play music", "media_play", "stop", "media_controls").shouldBeEmpty()
        reasonsFor("set a timer for one minute", "timer", "stop", "timer_control").shouldBeEmpty()
        reasonsFor("text dad", "sms", "no", "sms", secondPath = "follow_up_answer").shouldBeEmpty()
        // Mom is a prompt name, Dad isn't: a new call, not the retry of a misheard name
        reasonsFor("call mom", "telephone", "call dad", "telephone").shouldBeEmpty()
        reasonsFor("set a timer for five minutes", "timer", "set a timer for ten minutes", "timer")
            .shouldBeEmpty()
        // the retries stay: a misheard name, a stop GAR's conversation took, another skill
        reasonsFor("call erin", "telephone", "call aaron", "telephone") shouldBe listOf("repeat")
        reasonsFor("what's the weather", "weather", "stop", "conversation") shouldBe
            listOf("said_stop")
        reasonsFor("play music", "weather", "play music", "media_play") shouldBe listOf("repeat")

        // the same words after a turn that went wrong
        val h = Harness()
        h.turns.hint(GarVoiceStart.MIC_BUTTON)
        val first = h.speak("volume up")
        first.routedBy("fallback")
        h.turns.answered(first, "text", "Could you repeat?", "TextFallbackOutput", false,
            VoiceSignals.ASKED_TO_REPEAT)
        h.advance(3_000)
        h.spokenAnswer("volume up", "volume")
        h.lines()[0]["suspect"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
            listOf("asked_to_repeat", "repeat")
    }

    "nothing is recorded while the log is off, and turning it off drops the held turn" {
        val h = Harness()
        h.turns.enabled = false
        h.turns.hint(GarVoiceStart.MIC_BUTTON)
        h.turns.captureStarted("whisper").shouldBeNull()
        h.turns.input(VoiceInput.Final(listOf("hello"))).shouldBeNull()
        h.lines().shouldBeEmpty()

        h.turns.enabled = true
        h.spokenAnswer("hello", "small_talk")
        h.turns.enabled = false
        h.endHold()
        h.lines().shouldBeEmpty()
    }

    "the hooks never throw, whatever fails underneath" {
        val blocked = Files.createTempFile("gar-voice-log", "").toFile() // a file, not a folder
        val warnings = ArrayList<String>()
        var failClock = false
        val turns = VoiceTurns(
            files = VoiceLogFiles(File(blocked, "log")),
            io = Executor { it.run() },
            schedule = { _, task -> task.run() },
            log = { throw IllegalStateException("logcat") },
            warnLog = { message, _ -> warnings.add(message) },
            monoClock = { if (failClock) throw IllegalStateException("clock") else 0L },
            isLocked = { throw SecurityException("keyguard") },
            sttModel = { throw IllegalStateException("settings") },
            promptInfoSource = { throw IllegalStateException("contacts") },
        ).apply { enabled = true }
        turns.hint(GarVoiceStart.MIC_BUTTON)
        val turn = turns.captureStarted("whisper").shouldNotBeNull()
        turn.record.locked.shouldBeNull()
        turn.speechStarted(100)
        turn.transcribed(transcript("Hi."), "hi", "Hi.", null, FloatArray(10))
        turn.update { throw IllegalStateException("block") }
        turns.input(VoiceInput.Final(listOf("hi")))
        turns.answered(turn, "small_talk", "Hello", "SmallTalkOutput", false, null)
        failClock = true
        turns.hint(GarVoiceStart.MIC_BUTTON)
        turns.firstWord()
        turns.input(VoiceInput.Final(listOf("hi again")))
        turns.flush()
        var zipped: File? = File("not called")
        turns.zipTo(File(blocked, "x.zip")) { zipped = it }
        zipped.shouldBeNull()
        turns.clear()
        warnings shouldHaveSize 1 // once per process
    }

    "the log rotates at its size and keeps its number of files" {
        val dir = Files.createTempDirectory("gar-voice-log").toFile()
        val files = VoiceLogFiles(dir, maxBytes = 100, keepFiles = 3)
        for (i in 1..10) {
            files.append("{\"n\":$i,\"pad\":\"${"x".repeat(30)}\"}") // 47 bytes with the newline
        }
        files.logFiles().map { it.name } shouldBe
            listOf("voice-log.2.jsonl", "voice-log.1.jsonl", "voice-log.jsonl")
        files.logFiles()
            .flatMap { it.readLines() }
            .map { Json.parseToJsonElement(it).jsonObject.long("n") } shouldBe
            listOf(5L, 6L, 7L, 8L, 9L, 10L)
        files.current.length() shouldBe 95 // lines 9 and 10 (one digit more)
    }

    "a line cut short loses only itself" {
        val dir = Files.createTempDirectory("gar-voice-log").toFile()
        val files = VoiceLogFiles(dir)
        files.append("{\"n\":1}")
        files.current.appendText("{\"n\":2,\"cut") // the disk filled up
        files.append("{\"n\":3}")
        files.current.readLines() shouldBe listOf("{\"n\":1}", "{\"n\":2,\"cut", "{\"n\":3}")
    }

    "suspect WAVs are kept within their bytes too" {
        val dir = Files.createTempDirectory("gar-voice-log").toFile()
        // each WAV of 1600 samples is 44 + 3200 bytes: room for two
        val files = VoiceLogFiles(dir, keepRecent = 1, keepSuspect = 10, maxSuspectBytes = 7_000)
        for (i in 1..4) {
            files.saveWav("2026110$i.wav", FloatArray(1_600))
            files.markSuspect("2026110$i.wav")
        }
        files.saveWav("20261105.wav", FloatArray(1_600)) // prunes
        files.wavDir.list()!!.sorted() shouldContainExactly
            listOf("20261103.suspect.wav", "20261104.suspect.wav", "20261105.wav")
    }

    "the last WAVs and the suspect ones are kept, and clearing deletes all" {
        val dir = Files.createTempDirectory("gar-voice-log").toFile()
        val files = VoiceLogFiles(dir, keepRecent = 2, keepSuspect = 1)
        for (i in 1..3) {
            files.saveWav("2026110$i.wav", FloatArray(160))
        }
        files.markSuspect("20261103.wav") shouldBe "20261103.suspect.wav"
        files.markSuspect("missing.wav") shouldBe "missing.wav"
        files.saveWav("20261104.wav", FloatArray(160))
        files.saveWav("20261105.wav", FloatArray(160))
        files.wavDir.list()!!.sorted() shouldContainExactly
            listOf("20261103.suspect.wav", "20261104.wav", "20261105.wav")
        files.append("{}")

        val zip = File(dir.parentFile, "${dir.name}.zip")
        files.zipTo(zip)
        ZipFile(zip).use { z -> z.entries().toList().map { it.name } } shouldContainExactly listOf(
            "gar-voice-log/voice-log.jsonl",
            "gar-voice-log/wav/20261103.suspect.wav",
            "gar-voice-log/wav/20261104.wav",
            "gar-voice-log/wav/20261105.wav",
        )
        files.clear()
        dir.exists() shouldBe false
    }

    "JSON numbers stay valid when Whisper gives NaN" {
        val record = VoiceRecord("id", 1, 0, 0, GarVoiceStart.OTHER)
        record.transcript = VoiceRecord.Transcript("", Float.NaN, 0.5f, 0, 0, 0, "drop", "empty")
        val json = record.toJson().toString()
        json shouldContain "\"confidence\":null"
        Json.parseToJsonElement(json).jsonObject.obj("transcript").str("filter_reason") shouldBe "empty"
    }

    "the Whisper device's end-point reasons" {
        val clip = Endpointer.Clip(0, 100, 50)
        GarWhisperInputDevice.endReason(
            Endpointer.Decision.Transcribe(clip, Endpointer.Reason.END_OF_SPEECH), false
        ) shouldBe "vad_silence"
        GarWhisperInputDevice.endReason(
            Endpointer.Decision.Transcribe(clip, Endpointer.Reason.CAP), false
        ) shouldBe "max_length"
        GarWhisperInputDevice.endReason(
            Endpointer.Decision.Transcribe(clip, Endpointer.Reason.FINISHED), true
        ) shouldBe "user_tap"
        GarWhisperInputDevice.endReason(
            Endpointer.Decision.Discard(Endpointer.Reason.NO_SPEECH), false
        ) shouldBe "no_speech"
    }

    "a follow-up is written with its outcome, and asking or no match mark the turn" {
        val h = Harness()
        h.turns.hint(GarVoiceStart.MIC_BUTTON)
        val turn = h.speak("turn it back off")
        turn.followUp(VoiceFollowUp(
            "switch_off_back", "flashlight", 42_000, "turn off the flashlight", "rewritten",
        ))
        turn.routedBy("sentence")
        h.turns.answered(turn, "flashlight", "Flashlight off.", "FlashlightOutput", false, null,
            spoken = true)
        h.advance(30_000) // no repeat
        h.turns.hint(GarVoiceStart.MIC_BUTTON)
        val asked = h.speak("turn it on")
        asked.followUp(VoiceFollowUp("switch_on", null, null, null, "asked"))
        h.turns.answered(asked, "follow_up", "Turn what on?", "GarFollowUpAskOutput", false, null)
        h.advance(30_000)
        h.turns.hint(GarVoiceStart.MIC_BUTTON)
        val deferred = h.speak("play it again")
        deferred.followUp(VoiceFollowUp(
            "about_song_play_again", "song", 5_000, null, "deferred", deferredTo = "media",
        ))
        deferred.followUp(VoiceFollowUp(
            "about_song_play_again", "song", 5_000, null, "deferred", deferredTo = "media",
        )) // twice is once
        h.turns.answered(deferred, "media_controls", "", "MediaOutput", false, null)
        h.endHold()

        val lines = h.lines()
        val first = lines[0]
        val followUp = first.obj("follow_up")
        followUp.str("form") shouldBe "switch_off_back"
        followUp.str("turn_skill") shouldBe "flashlight"
        followUp.long("turn_age_ms") shouldBe 42_000
        followUp.str("rewrite") shouldBe "turn off the flashlight"
        followUp.str("outcome") shouldBe "rewritten"
        followUp["deferred_to"].shouldBeNull()
        first.obj("answer")["spoken"]!!.jsonPrimitive.boolean shouldBe true
        first["suspect"].shouldBeNull()

        lines[1].obj("follow_up").str("outcome") shouldBe "asked"
        lines[1].obj("follow_up")["turn_skill"].shouldBeNull()
        lines[1]["suspect"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
            listOf("follow_up_asked")
        lines[2].obj("follow_up").str("deferred_to") shouldBe "media"
        lines[2]["suspect"].shouldBeNull()
    }

    "nothing taking a rewrite marks the turn itself" {
        val h = Harness()
        val turn = h.turns.input(VoiceInput.Final(listOf("turn it off"))).shouldNotBeNull()
        val rewrite = "turn of the flash" // a typo no skill reads
        turn.followUp(VoiceFollowUp("switch_off", "flashlight", 1_000, rewrite, "rewritten"))
        turn.followUp(VoiceFollowUp("switch_off", "flashlight", 1_000, rewrite, "unmatched"))
        h.turns.answered(turn, "text", "Could you repeat?", "TextFallbackOutput", false, null)
        h.endHold()
        val line = h.lines().single()
        line.obj("follow_up").str("outcome") shouldBe "unmatched"
        line["suspect"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
            listOf("follow_up_unmatched")
    }

    "a correction or a cancel right after a rewrite marks it, so does a declined confirmation" {
        for ((next, nextFollowUp) in listOf(
            "no eight pm" to VoiceFollowUp("correction_no", "alarm", 3_000, null, "rewritten"),
            "cancel" to null,
            "that's wrong" to null,
        )) {
            val h = Harness()
            h.turns.hint(GarVoiceStart.MIC_BUTTON)
            val first = h.speak("change it to seven")
            first.followUp(VoiceFollowUp(
                "set_to", "alarm", 20_000, "change my 6 am alarm to 7 am", "rewritten",
            ))
            h.turns.answered(first, "alarm", "Alarm changed.", "AlarmOutput", false, null)
            h.advance(30_000)
            h.turns.hint(GarVoiceStart.MIC_BUTTON)
            val second = h.speak(next)
            nextFollowUp?.let { second.followUp(it) }
            h.turns.answered(second, "alarm", "OK", "AlarmOutput", false, null)
            withClue(next) {
                val suspect = h.lines().first()["suspect"]!!.jsonArray
                suspect.map { it.jsonPrimitive.content } shouldContain "follow_up_corrected"
            }
        }

        val h = Harness()
        h.turns.hint(GarVoiceStart.MIC_BUTTON)
        val confirm = h.speak("delete it")
        confirm.followUp(VoiceFollowUp(
            "remove_delete", "alarm", 20_000, "cancel my 7 am alarm", "confirm",
        ))
        h.turns.answered(confirm, "follow_up", "Cancel your 7 AM alarm?",
            "GarFollowUpConfirmOutput", false, null)
        h.advance(3_000)
        h.turns.hint(GarVoiceStart.FOLLOW_UP)
        val no = h.speak("no")
        no.routedBy("follow_up_answer")
        h.turns.answered(no, "follow_up", "OK.", "GarCancelOutput", false, null)
        h.lines().single()["suspect"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContain
            "follow_up_confirm_declined"
    }

    "a continued turn starts as such, and windows without a turn count in the next one" {
        val h = Harness()
        repeat(2) {
            h.turns.hint(GarVoiceStart.CONTINUED)
            val silent = h.turns.captureStarted("whisper").shouldNotBeNull()
            silent.captureEnded("no_speech", 112_000, null, null)
            h.turns.windowSilent(hallucination = false)
        }
        h.turns.hint(GarVoiceStart.CONTINUED)
        val noise = h.turns.captureStarted("whisper").shouldNotBeNull()
        noise.transcribed(transcript("Thank you."), "thank you", "Thank you.", null, null)
        h.turns.windowSilent(hallucination = true)
        h.lines().shouldBeEmpty() // no turn for them

        h.turns.hint(GarVoiceStart.CONTINUED)
        val turn = h.speak("what about tomorrow")
        h.turns.answered(turn, "weather", "Tomorrow, sunny.", "WeatherOutput", false, null)
        h.endHold()
        val line = h.lines().single()
        line.str("start") shouldBe "continued"
        line.long("continued_silent") shouldBe 2
        line.long("continued_hallucination") shouldBe 1

        // counted once
        h.turns.hint(GarVoiceStart.MIC_BUTTON)
        h.spokenAnswer("what time is it", "current_time")
        h.endHold()
        h.lines()[1]["continued_silent"].shouldBeNull()
    }

    "a typed turn's answer is shown only, and waits for no first word" {
        val h = Harness()
        h.turns.hint(GarVoiceStart.TYPED)
        val typed = h.turns.input(VoiceInput.Final(listOf("what time is it"))).shouldNotBeNull()
        h.turns.answered(typed, "current_time", "It's 5 PM", "CurrentTimeOutput", false, null,
            spoken = false)
        h.advance(300)
        h.turns.firstWord() // another answer's speech, not this one's
        h.endHold()
        val line = h.lines().single()
        line.obj("answer")["spoken"]!!.jsonPrimitive.boolean shouldBe false
        line.obj("answer").str("speech") shouldBe "It's 5 PM"
        (line["latency"] as? JsonObject)?.get("to_first_word_ms").shouldBeNull()
    }

    "the evaluator's turn is the capture's" {
        val h = Harness()
        h.turns.hint(GarVoiceStart.FOLLOW_UP)
        val capture = h.turns.captureStarted("whisper").shouldNotBeNull()
        capture.transcribed(transcript("Hi."), "hi", "Hi.", null, null)
        h.turns.input(VoiceInput.Final(listOf("hi"))) shouldBeSameInstanceAs capture
    }
})

private val MEDIA_TOOL = ToolSpec(
    name = "media_control",
    skillId = "media",
    sentence = SentenceMapping.Fixed("control", listOf("action")),
    description = "Control what plays.",
    confirm = false,
    slots = listOf(SlotSpec("action", SlotKind.TEXT, true, "", captureType = "string")),
    examples = listOf(),
)

private fun transcript(text: String) = Transcript(
    text = text,
    noSpeechProb = 0.02f,
    confidence = 0.77f,
    tokens = 4,
    audioCtx = 128,
    encodeMicros = 40_000,
    totalMicros = 216_000,
    promptNames = listOf("Aaron", "Mom"),
    promptTokens = 5,
)

private class Harness(
    frames: (fromMono: Long, toMono: Long) -> GarFrameSummary? = { _, _ -> null },
) {
    val dir: File = Files.createTempDirectory("gar-voice-log").toFile()
    var mono = 1_000L
    var wall = 1_762_000_000_000L
    val scheduled = ArrayList<Runnable>()
    val logs = ArrayList<String>()
    val warnings = ArrayList<String>()
    val files = VoiceLogFiles(dir)
    val turns = VoiceTurns(
        files = files,
        io = Executor { it.run() },
        schedule = { _, task -> scheduled.add(task) },
        log = { logs.add(it) },
        warnLog = { message, t -> warnings.add("$message: $t") },
        wallClock = { wall },
        monoClock = { mono },
        isLocked = { false },
        sttModel = { "ggml-base.en-acft-q8_0.bin" },
        promptInfoSource = { VoicePromptInfo(on = true, owner = "Aaron", ownerSource = "setting") },
        frames = frames,
    ).apply { enabled = true }

    fun advance(millis: Long) {
        mono += millis
        wall += millis
    }

    /** Runs the end of the holds that are due (all of them: the test clock doesn't wait). */
    fun endHold() {
        val tasks = ArrayList(scheduled)
        scheduled.clear()
        tasks.forEach { it.run() }
    }

    /**
     * A Whisper capture of [spoken] through the evaluator's input: 400 ms to speech, 2.5 s of
     * capture with 1.5 s of speech, 200 ms of decoding.
     */
    fun speak(spoken: String, raw: String = spoken): VoiceTurn {
        val turn = turns.captureStarted("whisper")!!
        advance(400)
        turn.speechStarted(6_400)
        advance(2_100)
        turn.captureEnded("vad_silence", 40_000, 35_000, 24_000)
        advance(200)
        turn.transcribed(transcript(raw), spoken, raw, null, FloatArray(16_000) { 0.1f })
        turns.input(VoiceInput.Final(listOf(spoken))) shouldBeSameInstanceAs turn
        return turn
    }

    fun spokenAnswer(
        spoken: String,
        skill: String,
        card: String = "Output",
        path: String = "sentence",
    ): VoiceTurn {
        turns.hint(GarVoiceStart.MIC_BUTTON)
        val turn = speak(spoken)
        turn.routedBy(path)
        turns.answered(turn, skill, "OK", card, false, null)
        return turn
    }

    /** The lines written so far; also checks that nothing failed on the way. */
    fun lines(): List<JsonObject> {
        warnings.shouldBeEmpty()
        return files.logFiles()
            .flatMap { it.readLines() }
            .map { Json.parseToJsonElement(it).jsonObject }
    }
}

private fun JsonObject.str(key: String): String = (this[key] as JsonPrimitive).content
private fun JsonObject.long(key: String): Long = this[key]!!.jsonPrimitive.long
private fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject
