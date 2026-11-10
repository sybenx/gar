package cc.aaro.gar.router

import cc.aaro.gar.router.format.testTool
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

private fun matches(utterance: String, vararg keywords: String) =
    KeywordFilter.containsAny(Utterances.normalize(utterance), keywords.map(Utterances::normalize))

class KeywordFilterTest : StringSpec({
    "a keyword matches a whole word, also with a common ending" {
        for ((utterance, keyword) in listOf(
            "what alarms do i have" to "alarm",
            "set an alarm" to "alarm",
            "i don't want to be disturbed" to "disturb",
            "it's too loud" to "loud",
            "make it louder" to "loud",
            "what's today's date" to "today",
            "it is muted" to "mute",
            "how many hours" to "hour",
            "dates" to "date",
            "turn off the lights" to "light",
            "the light keeps flashing" to "flash",
            "it flashes" to "flash",
            "make it the loudest" to "loud",
            // a quirk: "see" is a flashlight keyword
            "plant the seed" to "see",
        )) {
            withClue("$utterance / $keyword") { matches(utterance, keyword) shouldBe true }
        }
    }

    "a keyword doesn't match inside other words or before other endings" {
        for ((utterance, keyword) in listOf(
            "set a timer" to "time",
            "see you later" to "late",
            "what's the update" to "date",
            "sometime soon" to "time",
            "the volumetric flask" to "volume",
            "mute" to "unmute",
            // endings only extend the keyword, never the utterance's word
            "set two alarm" to "alarms",
            // limits keywords must be chosen around: no doubled consonants, no dropped "e"
            "the stopwatch stopped" to "stop",
            "i'm waking" to "wake",
            "writing a text" to "write",
        )) {
            withClue("$utterance / $keyword") { matches(utterance, keyword) shouldBe false }
        }
        KeywordFilter.containsAny("what time is it", listOf("")) shouldBe false
    }

    "a phrase matches consecutive words, each with the same endings" {
        matches("switch off the wi fi", "wi fi") shouldBe true
        matches("turn on do not disturb", "do not disturb") shouldBe true
        matches("do not disturb me", "do not disturbed") shouldBe false
        matches("wi wi fi", "wi fi") shouldBe true // the match starts at the second word
        matches("wi and fi", "wi fi") shouldBe false
        matches("fi wi", "wi fi") shouldBe false
        matches("wi", "wi fi") shouldBe false
        matches("", "time") shouldBe false
    }

    "any of several keywords is enough, and keywords are normalized like utterances" {
        matches("what's the time", "clock", "time") shouldBe true
        matches("What's the TIME?", "clock", "o'clock", "Time") shouldBe true
        matches("it's five o'clock", "o’clock") shouldBe true
        matches("good morning", "clock", "time", "hour") shouldBe false
    }

    "calls of tools without keywords are kept, others need a keyword in the utterance" {
        val plain = testTool("plain")
        val timed = testTool("current_time").copy(keywords = listOf("time", "clock"))
        KeywordFilter.check(RouterOutput.Call(plain, mapOf(), listOf(), "good morning")) shouldBe
            CallVerdict.Keep
        KeywordFilter.check(RouterOutput.Call(timed, mapOf(), listOf(), "what time is it")) shouldBe
            CallVerdict.Keep
        val dropped = KeywordFilter.check(RouterOutput.Call(timed, mapOf(), listOf(), "good morning"))
        dropped.shouldBeInstanceOf<CallVerdict.Drop>().reason shouldContain "current_time"
        // check() takes the keywords as normalized by the catalog loader
        val raw = testTool("current_time").copy(keywords = listOf("Time"))
        KeywordFilter.check(RouterOutput.Call(raw, mapOf(), listOf(), "what time is it"))
            .shouldBeInstanceOf<CallVerdict.Drop>()
    }
})
