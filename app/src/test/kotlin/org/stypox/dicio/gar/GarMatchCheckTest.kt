package org.stypox.dicio.gar

import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.alarm.AlarmSentenceMatch
import org.stypox.dicio.skills.date.DateInput
import java.io.File
import java.time.Duration
import org.dicio.numbers.unit.Duration as DicioDuration

private val catalogTools = ToolCatalog.load(
    File("src/main/gar/tools.yml").readText(),
    File("src/main/sentences/skill_definitions.yml").readText(),
).tools

private val state = GarMatchCheck.State(catalogTools, listOf(), ENGLISH_PARSER_FORMATTER)

/** Whether the check rejects a match of [skillId] with [data] and [score] for [input]. */
private fun rejects(
    skillId: String,
    data: Any?,
    score: Float,
    input: String,
    state: GarMatchCheck.State = org.stypox.dicio.gar.state,
) = GarMatchCheck.rejects(state, skillId, data, score, input)

/**
 * [GarMatchCheck]'s branches one by one, with the real catalog's keywords; [GarRankingTest] checks
 * the same rules on the matches Dicio's ranker really makes.
 */
class GarMatchCheckTest : StringSpec({
    "a skill is checked when all of its tools have keywords, or it has sentence keywords" {
        val tool = FLASHLIGHT_TOOL.copy(skillId = "x", keywords = listOf("thing"))
        GarMatchCheck.keywordsFor(listOf(tool), "x") shouldBe listOf("thing")
        // one tool without keywords turns the check off: its requests don't always name them
        GarMatchCheck.keywordsFor(listOf(tool, tool.copy(name = "other", keywords = listOf())), "x")
            .shouldBeNull()
        GarMatchCheck.keywordsFor(listOf(tool), "no_tools").shouldBeNull()

        for (skill in listOf(
            "open", "telephone", "navigation", "volume", "do_not_disturb", "calendar", "timer",
            "calculator",
        )) {
            withClue(skill) { GarMatchCheck.keywordsFor(catalogTools, skill).shouldBeNull() }
        }
        GarMatchCheck.keywordsFor(catalogTools, "alarm").shouldNotBeNull() shouldContainAll
            listOf("alarm", "clock")
        GarMatchCheck.keywordsFor(catalogTools, "listening").shouldNotBeNull() shouldContain "mike"
    }

    "the media skill's position words count only in an exact match" {
        val positions = listOf("next", "following", "previous", "last", "rewind")
        val inexact = GarMatchCheck.keywordsFor(catalogTools, "media", exact = false)
            .shouldNotBeNull()
        inexact shouldContainAll listOf("song", "one")
        for (word in positions) {
            inexact shouldNotContain word
        }
        GarMatchCheck.keywordsFor(catalogTools, "media").shouldNotBeNull() shouldContainAll
            positions
    }

    "a score of 0.999 is exact, 0.998 is not" {
        val next = Sentences.Media.Next
        rejects("media", next, 0.999f, "go to the next one") shouldBe false
        // "one" is the media skill's noun
        rejects("media", next, 0.998f, "go to the next one") shouldBe false
        rejects("media", next, 0.999f, "go to the next page") shouldBe false
        rejects("media", next, 0.998f, "go to the next page") shouldBe true
    }

    "words naming another thing count only in a match that isn't exact" {
        val on = Sentences.Flashlight.TurnOn
        rejects("flashlight", on, 1.0f, "turn on the light mode") shouldBe false
        rejects("flashlight", on, 0.9f, "turn on the light mode") shouldBe true
        rejects("flashlight", on, 0.9f, "turn on the light") shouldBe false
        // only the skill's own other things
        val notifications = Sentences.Notify.Notifications
        rejects("notify", notifications, 0.9f, "read my notification mode") shouldBe false
    }

    "an alarm cancel that isn't exact needs a cancel verb" {
        val cancelAll = AlarmSentenceMatch(Sentences.Alarm.CancelAll)
        rejects("alarm", cancelAll, 0.9f, "all my alarms") shouldBe true
        rejects("alarm", cancelAll, 0.9f, "clear all my alarms") shouldBe false
        rejects("alarm", cancelAll, 1.0f, "all my alarms") shouldBe false
        // other alarm sentences don't need one
        rejects("alarm", AlarmSentenceMatch(Sentences.Alarm.Show), 0.9f, "my alarms") shouldBe false
    }

    "an alarm's time and label are captures, so their words don't count" {
        val set = AlarmSentenceMatch(Sentences.Alarm.Set(time = "seven", label = "wake up"))
        // "remind me at to" is left
        rejects("alarm", set, 0.9f, "remind me at seven to wake up") shouldBe true
        rejects("alarm", set, 0.9f, "set an alarm at seven to wake up") shouldBe false
        val cancel = AlarmSentenceMatch(Sentences.Alarm.Cancel(time = "the alarm"))
        rejects("alarm", cancel, 0.9f, "cancel the alarm") shouldBe true
    }

    "a capture is removed as normalized, and only where the input has it" {
        val friday = DateInput.Said("Friday!")
        rejects("date", friday, 0.9f, "what will the weather be on friday") shouldBe true
        rejects("date", friday, 0.9f, "what is the date on friday") shouldBe false
        // "monday" isn't in the input: nothing is removed, and "friday" counts
        val monday = DateInput.Said("monday")
        rejects("date", monday, 0.9f, "what will the weather be on friday") shouldBe false
        // a capture with no words removes nothing
        rejects("date", DateInput.Said(" ? "), 0.9f, "what day is it") shouldBe false
    }

    "a new timer needs the timer's words, or a duration and a set word" {
        rejects("timer", Sentences.Timer.Set(null, null), 0.9f, "start a time") shouldBe true
        rejects("timer", Sentences.Timer.Set(null, null), 0.9f, "start a timer") shouldBe false
        val fiveMinutes = Sentences.Timer.Set(DicioDuration(Duration.ofMinutes(5)), null)
        // STT drops the r of "timer"
        rejects("timer", fiveMinutes, 0.9f, "set a time for five minutes") shouldBe false
        rejects("timer", fiveMinutes, 0.9f, "set a five minute alarm") shouldBe false
        rejects("timer", fiveMinutes, 0.9f, "set five minutes") shouldBe true
        // the timer's name doesn't count, exact or not
        rejects("timer", Sentences.Timer.Set(null, "beep"), 1.0f, "start the beep") shouldBe true
    }

    "timer cancels and questions name a running timer, or ask while one runs" {
        val unnamed = Sentences.Timer.Query("  ")
        rejects("timer", unnamed, 0.9f, "how long is left") shouldBe true
        rejects("timer", unnamed, 0.9f, "how long is left", state.withTimers(null)) shouldBe false
        rejects("timer", Sentences.Timer.Query(null), 0.9f, "how long is left",
            state.withTimers("pasta")) shouldBe false

        val pasta = state.withTimers(" pasta ")
        fun cancel(name: String?) = Sentences.Timer.Cancel(name)
        rejects("timer", cancel(null), 0.9f, "stop the music", pasta) shouldBe true
        rejects("timer", cancel("music"), 0.9f, "stop the music", pasta) shouldBe true
        rejects("timer", cancel("Pasta"), 0.9f, "stop the pasta", pasta) shouldBe false
        // a part of the name, or the name and more
        val sauce = state.withTimers("pasta sauce")
        rejects("timer", cancel("pasta"), 0.9f, "stop the pasta", sauce) shouldBe false
        rejects("timer", cancel("the pasta one"), 0.9f, "stop the pasta one", pasta) shouldBe false
        // blank names never match
        val blank = state.withTimers(" ", null)
        rejects("timer", cancel("pasta"), 0.9f, "stop the pasta", blank) shouldBe true
        rejects("timer", cancel(" "), 0.9f, "stop the", pasta) shouldBe true
        rejects("timer", Sentences.Timer.Query("pasta"), 0.9f, "how long is left on the pasta",
            pasta) shouldBe false
        rejects("timer", Sentences.Timer.Query("eggs"), 0.9f, "how long is left on the eggs",
            pasta) shouldBe true
    }

    "anything else matched as the timer is kept" {
        rejects("timer", null, 0.9f, "stop the music") shouldBe false
    }

    "a calculation needs two numbers, or one alone: the calculator says a single number back" {
        fun calculation(text: String?, state: GarMatchCheck.State = org.stypox.dicio.gar.state) =
            rejects("calculator", Sentences.Calculator.Calculate(text), 0.9f, "what is $text",
                state)

        calculation(null) shouldBe true
        calculation("the capital of france") shouldBe true
        calculation("two plus two") shouldBe false
        // the number parser divides: this is one number, 3
        calculation("12 divided by 4") shouldBe false
        calculation("12") shouldBe false
        // "what's the weather on the 5th" was answered 5
        calculation("the weather on the 5th") shouldBe true
        // the calculator has no unary operations: the model's call gets GAR's rewrite instead
        calculation("the square root of sixty four") shouldBe true
        // without a number parser nothing is checked
        calculation("the capital of france", GarMatchCheck.State(catalogTools)) shouldBe false
        rejects("calculator", null, 0.9f, "what is the capital of france") shouldBe false
    }

    "a calculation needs operators between its numbers and nothing after the last one" {
        fun calculation(text: String, state: GarMatchCheck.State = org.stypox.dicio.gar.state) =
            rejects("calculator", Sentences.Calculator.Calculate(text), 0.9f, "what is $text",
                state)

        // "score" is twenty and "of" means times: 20 x 49, with "ers game" left over
        calculation("the score of the 49ers game") shouldBe true
        calculation("eight times seven please") shouldBe true
        // words between two numbers that aren't an operator: the calculator would add them
        calculation("two and three") shouldBe true
        calculation("2 x 3") shouldBe true
        // operators, and "plus" and "minus", which the number parser takes into the next number
        for (text in listOf(
            "seven plus nine", "two times three", "2 to the power of 8", "10 minus 4",
            "nine minus three plus one", "15 percent of 80", "5 plus 3?",
        )) {
            withClue(text) { calculation(text) shouldBe false }
        }
        // without the operator sentences only the words after the last number count
        val noOperators = GarMatchCheck.State(
            catalogTools, listOf(), ENGLISH_PARSER_FORMATTER, calculatorOperators = null,
        )
        calculation("two and three", noOperators) shouldBe false
        calculation("the score of the 49ers game", noOperators) shouldBe true
    }

    "skills without keywords are never rejected" {
        rejects("open", Sentences.Open.Query("tv"), 0.8f, "open the tv") shouldBe false
        rejects("volume", null, 0.8f, "turn up the brightness") shouldBe false
    }
})

private fun GarMatchCheck.State.withTimers(vararg names: String?) =
    GarMatchCheck.State(tools, names.toList(), parserFormatter)
