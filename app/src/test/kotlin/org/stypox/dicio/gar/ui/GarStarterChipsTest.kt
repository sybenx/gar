package org.stypox.dicio.gar.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.voicelog.GarVoiceStart
import org.stypox.dicio.gar.voicelog.VoiceFollowUp
import org.stypox.dicio.gar.voicelog.VoiceRecord

private const val DAY = 24L * 60 * 60 * 1000
private const val NOW = 1_800_000_000_000L

private fun turn(
    input: String,
    ago: Long = DAY,
    skill: String = "current_time",
    path: String? = "sentence",
    outcome: String? = "answered",
    start: String = "mic",
    suspect: Boolean = false,
    followUp: Boolean = false,
) = GarStarterChips.Turn(
    input = input,
    wallMillis = NOW - ago,
    start = start,
    outcome = outcome,
    skill = skill,
    path = path,
    suspect = suspect,
    followUp = followUp,
)

private val DEFAULTS = listOf(
    GarChip("What's the weather") to "weather",
    GarChip("Set a timer for 5 minutes") to "timer",
    GarChip("What time is it") to "current_time",
    GarChip("What's my battery") to "battery",
)

class GarStarterChipsTest : StringSpec({
    "only answered, unsuspected sentence matches of the read-only skills teach a starter" {
        val ok = turn("what time is it")
        GarStarterChips.allowed(ok, NOW).shouldBeTrue()
        GarStarterChips.allowed(ok.copy(path = "model"), NOW).shouldBeFalse()
        GarStarterChips.allowed(ok.copy(path = "follow_up_answer"), NOW).shouldBeFalse()
        GarStarterChips.allowed(ok.copy(path = null), NOW).shouldBeFalse()
        GarStarterChips.allowed(ok.copy(followUp = true), NOW).shouldBeFalse()
        GarStarterChips.allowed(ok.copy(suspect = true), NOW).shouldBeFalse()
        GarStarterChips.allowed(ok.copy(outcome = "not_understood"), NOW).shouldBeFalse()
        GarStarterChips.allowed(ok.copy(start = GarVoiceStart.BUTTON.key), NOW).shouldBeFalse()
        GarStarterChips.allowed(ok.copy(skill = "telephone"), NOW).shouldBeFalse()
        GarStarterChips.allowed(ok.copy(skill = "sms"), NOW).shouldBeFalse()
        GarStarterChips.allowed(ok.copy(skill = "units"), NOW).shouldBeTrue()
        GarStarterChips.allowed(ok.copy(start = "typed"), NOW).shouldBeTrue()
    }

    "2 to 6 words, within the last 30 days" {
        GarStarterChips.allowed(turn("time"), NOW).shouldBeFalse()
        GarStarterChips.allowed(turn("what is the time right now please tell"), NOW).shouldBeFalse()
        GarStarterChips.allowed(turn("what time is it", ago = 31 * DAY), NOW).shouldBeFalse()
        GarStarterChips.allowed(turn("what time is it", ago = 29 * DAY), NOW).shouldBeTrue()
    }

    "a timer only with a duration" {
        GarStarterChips.allowed(turn("set a timer", skill = "timer"), NOW).shouldBeFalse()
        GarStarterChips.allowed(turn("set a timer for ten minutes", skill = "timer"), NOW).shouldBeTrue()
        GarStarterChips.allowed(turn("timer 30 seconds", skill = "timer"), NOW).shouldBeTrue()
    }

    "twice at least; by count, then the newest; the newest raw input is kept" {
        val turns = listOf(
            turn("What's the time?", ago = 5 * DAY),
            turn("what's the time", ago = 2 * DAY),
            turn("battery level", ago = 3 * DAY, skill = "battery"),
            turn("Battery level", ago = 1 * DAY, skill = "battery"),
            turn("battery level", ago = 4 * DAY, skill = "battery"),
            turn("what is the date", ago = DAY, skill = "date"), // once: not learned
        )
        GarStarterChips.rank(turns, NOW) shouldBe listOf(
            GarChip(label = "Battery level", input = "Battery level"),
            GarChip(label = "What's the time", input = "what's the time"),
        )
    }

    "model-routed turns never count, however often" {
        val turns = List(5) { turn("weather in logan", skill = "weather", path = "model") }
        GarStarterChips.rank(turns, NOW).shouldBeEmpty()
    }

    "the defaults fill up to four, without one of a skill the user already has" {
        val turns = listOf(
            turn("set a timer for 10 minutes", skill = "timer"),
            turn("Set a timer for 10 minutes", ago = DAY / 2, skill = "timer"),
        )
        GarStarterChips.rank(turns, NOW, DEFAULTS) shouldBe listOf(
            GarChip("Set a timer for 10 minutes", "Set a timer for 10 minutes"),
            GarChip("What's the weather"),
            GarChip("What time is it"),
            GarChip("What's my battery"),
        )
        GarStarterChips.rank(listOf(), NOW, DEFAULTS) shouldBe DEFAULTS.map { it.first }
    }

    "one chip per skill: a cut-off request counts for the whole one, in a default's wording" {
        val turns = listOf(
            turn("what time is it", ago = 3 * DAY),
            turn("What time is it?", ago = 2 * DAY),
            turn("what time is", ago = DAY),
            turn("what time is", ago = DAY / 2),
            turn("whats my battery", skill = "battery"),
            turn("Whats my battery", ago = DAY / 2, skill = "battery"),
        )
        // without defaults: the newest raw words of the longest group
        GarStarterChips.rank(turns, NOW) shouldBe listOf(
            GarChip("What time is it?", "What time is it?"),
            GarChip("Whats my battery", "Whats my battery"),
        )
        // STT's "whats" is the default's "what's"; neither default shows twice
        GarStarterChips.rank(turns, NOW, DEFAULTS) shouldBe listOf(
            GarChip("What time is it"),
            GarChip("What's my battery"),
            GarChip("What's the weather"),
            GarChip("Set a timer for 5 minutes"),
        )
    }

    "a cut-off word is cut off too, and a cut-off of another skill's request is dropped" {
        GarStarterChips.isCutOff("what time is i", "what time is it").shouldBeTrue()
        GarStarterChips.isCutOff("whats my batter", "whats my battery").shouldBeTrue()
        GarStarterChips.isCutOff("set a timer for 1 minute", "set a timer for 10 minutes")
            .shouldBeFalse()
        GarStarterChips.isCutOff("what time is it", "what time is it").shouldBeFalse()
        val turns = listOf(
            turn("what time is it"), turn("what time is it"),
            turn("what time is i"), turn("what time is i"), turn("what time is i"),
            // STT cut the word, and the calculator answered it
            turn("whats my batter", skill = "calculator"),
            turn("whats my batter", skill = "calculator"),
        )
        // nothing whole to compare it with
        GarStarterChips.rank(turns, NOW) shouldBe listOf(
            GarChip("What time is it", "what time is it"),
            GarChip("Whats my batter", "whats my batter"),
        )
        // the battery default is what it was cut from
        GarStarterChips.rank(turns, NOW, DEFAULTS) shouldBe listOf(
            GarChip("What time is it"),
            GarChip("What's the weather"),
            GarChip("Set a timer for 5 minutes"),
            GarChip("What's my battery"),
        )
    }

    "a cut-off request alone takes a default's wording" {
        val turns = listOf(turn("what time is"), turn("what time is", ago = DAY / 2))
        GarStarterChips.rank(turns, NOW, DEFAULTS).first() shouldBe GarChip("What time is it")
    }

    "at most four learned ones" {
        val turns = listOf("weather", "date", "battery", "calculator", "units")
            .flatMap { skill -> List(2) { turn("ask the $skill", skill = skill) } }
        GarStarterChips.rank(turns, NOW, DEFAULTS).size shouldBe 4
    }

    "a voice-log line written by VoiceRecord reads back" {
        val record = VoiceRecord("id", 1, wallStart = NOW - DAY, startMono = 0L, start = GarVoiceStart.TYPED)
        record.input = "What's the weather tomorrow"
        record.path = "sentence"
        record.answer = VoiceRecord.Answer(outcome = "answered", skill = "weather")
        val parsed = GarStarterChips.parse(record.toJson().toString()).shouldNotBeNull()
        parsed shouldBe GarStarterChips.Turn(
            input = "What's the weather tomorrow",
            wallMillis = NOW - DAY,
            start = "typed",
            outcome = "answered",
            skill = "weather",
            path = "sentence",
            suspect = false,
            followUp = false,
        )

        record.suspect.add("repeat")
        record.followUp = VoiceFollowUp(
            form = "switch_off_back", turnSkill = null, turnAgeMs = null, rewrite = null,
            outcome = VoiceFollowUp.OUTCOME_DIRECT,
        )
        val marked = GarStarterChips.parse(record.toJson().toString()).shouldNotBeNull()
        marked.suspect.shouldBeTrue()
        marked.followUp.shouldBeTrue()
    }

    "lines without input, or broken, are skipped" {
        val record = VoiceRecord("id", 1, wallStart = NOW, startMono = 0L, start = GarVoiceStart.MIC_BUTTON)
        GarStarterChips.parse(record.toJson().toString()).shouldBeNull()
        GarStarterChips.parse("{\"input\": \"what time").shouldBeNull()
        GarStarterChips.parse("").shouldBeNull()
    }
})
