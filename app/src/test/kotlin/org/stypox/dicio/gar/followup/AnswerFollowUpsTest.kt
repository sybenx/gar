package org.stypox.dicio.gar.followup

import cc.aaro.gar.router.followup.CorrectionKind
import cc.aaro.gar.router.followup.FollowUpForm
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.gar.TEST_TODAY
import org.stypox.dicio.gar.followup.adapters.AnswerFollowUps
import org.stypox.dicio.gar.followup.adapters.RepeatFollowUps
import org.stypox.dicio.gar.shouldBeTakenBy
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.joke.JokeInfo
import org.stypox.dicio.skills.random.RandomRequest
import org.stypox.dicio.skills.weather.WeatherRequest
import org.stypox.dicio.skills.weather.WeatherTopic
import org.stypox.dicio.skills.weather.WeatherWhen
import org.stypox.dicio.skills.world_time.Place
import org.stypox.dicio.skills.world_time.WorldTimeAnswer
import org.stypox.dicio.skills.world_time.WorldTimeRequest
import java.time.LocalDate
import java.time.ZoneId

private fun weather(time: WeatherWhen, place: String?, topic: WeatherTopic = WeatherTopic.GENERAL): GarRememberedTurn {
    AnswerFollowUps.today = { TEST_TODAY }
    val request = WeatherRequest(topic, time, place)
    return rememberedBy(AnswerFollowUps, answered("weather", "the weather", request, FakeOutput(request)))
}

private fun whatAbout(rest: String, preposition: String? = null, bareAnd: Boolean = false) =
    FollowUpForm.WhatAbout(rest, preposition, bareAnd)

class AnswerFollowUpsTest : StringSpec({
    afterTest { AnswerFollowUps.today = { LocalDate.now() } }

    "the weather: what about tomorrow, and in paris, how about boston" {
        val now = weather(WeatherWhen.Now, "denver")
        now.turn.kind shouldBe GarTurnKind.ANSWER
        now.turn.facts.place?.name shouldBe "denver"
        val tomorrow = rewriteOf(AnswerFollowUps, whatAbout("tomorrow"), now).command
        tomorrow shouldBe "what's the weather tomorrow in denver"
        shouldBeTakenBy(tomorrow!!, "weather", mapOf("place" to "denver"))

        val thenTomorrow = weather(WeatherWhen.Day(TEST_TODAY.plusDays(1)), "denver")
        val paris = rewriteOf(AnswerFollowUps, whatAbout("paris", "in", bareAnd = true), thenTomorrow).command
        paris shouldBe "what's the weather tomorrow in paris"
        shouldBeTakenBy(paris!!, "weather", mapOf("place" to "paris"))
        rewriteOf(AnswerFollowUps, whatAbout("the weekend", bareAnd = true), thenTomorrow).command shouldBe
            "what's the weather this weekend in denver"
        // a bare name after "how about" is no place the weather sentences read
        rewriteOf(AnswerFollowUps, whatAbout("boston"), now).shouldBeNull()
    }

    "the weather keeps what it was asked about" {
        val rain = weather(WeatherWhen.Day(TEST_TODAY), "austin", WeatherTopic.RAIN)
        rewriteOf(AnswerFollowUps, whatAbout("tomorrow"), rain).command shouldBe "will it rain tomorrow in austin"
        shouldBeTakenBy("will it rain tomorrow in austin", "weather")
    }

    "the weather: the next day and the day before count from the day asked (Sunday the 25th)" {
        val tomorrow = weather(WeatherWhen.Day(TEST_TODAY.plusDays(1)), "denver")
        for (rest in listOf(
            "the next day", "next day", "the day after", "the day after that", "the following day",
        )) {
            rewriteOf(AnswerFollowUps, whatAbout(rest), tomorrow).command shouldBe
                "what's the weather on tuesday in denver"
        }
        rewriteOf(AnswerFollowUps, whatAbout("the day after", bareAnd = true), tomorrow).command shouldBe
            "what's the weather on tuesday in denver"
        rewriteOf(AnswerFollowUps, whatAbout("the next day", "for"), tomorrow).command shouldBe
            "what's the weather on tuesday in denver"
        shouldBeTakenBy("what's the weather on tuesday in denver", "weather", mapOf("place" to "denver"))
        rewriteOf(AnswerFollowUps, whatAbout("the day before"), tomorrow).command shouldBe
            "what's the weather today in denver"
        // the day after tomorrow names its own day
        rewriteOf(AnswerFollowUps, whatAbout("the day after tomorrow"), tomorrow).command shouldBe
            "what's the weather on tuesday in denver"

        // from now (today), from a night, keeping the topic; nothing before today
        val now = weather(WeatherWhen.Now, null)
        rewriteOf(AnswerFollowUps, whatAbout("the next day"), now).command shouldBe "what's the weather tomorrow"
        rewriteOf(AnswerFollowUps, whatAbout("the day before"), now).shouldBeNull()
        val rain = weather(WeatherWhen.Day(TEST_TODAY.plusDays(2)), null, WeatherTopic.RAIN)
        rewriteOf(AnswerFollowUps, whatAbout("the day after that"), rain).command shouldBe
            "will it rain on wednesday"
        val tonight = weather(WeatherWhen.Night(TEST_TODAY), null, WeatherTopic.LOW)
        rewriteOf(AnswerFollowUps, whatAbout("the next day"), tonight).command shouldBe "what's the low tomorrow"
        // a week has no next day, and a week away is no day the sentences name
        rewriteOf(AnswerFollowUps, whatAbout("the next day"), weather(WeatherWhen.Week(TEST_TODAY), null))
            .shouldBeNull()
        rewriteOf(AnswerFollowUps, whatAbout("the next day"), weather(WeatherWhen.Day(TEST_TODAY.plusDays(6)), null))
            .shouldBeNull()
    }

    "the weather: next week and this weekend after a day" {
        val tomorrow = weather(WeatherWhen.Day(TEST_TODAY.plusDays(1)), "denver")
        rewriteOf(AnswerFollowUps, whatAbout("next week"), tomorrow).command shouldBe
            "what's the weather next week in denver"
        shouldBeTakenBy("what's the weather next week in denver", "weather", mapOf("place" to "denver"))
        rewriteOf(AnswerFollowUps, whatAbout("this week"), tomorrow).command shouldBe
            "what's the weather this week in denver"
        rewriteOf(AnswerFollowUps, whatAbout("this weekend"), tomorrow).command shouldBe
            "what's the weather this weekend in denver"
        rewriteOf(AnswerFollowUps, whatAbout("next weekend"), tomorrow).command shouldBe
            "what's the weather next weekend in denver"
        // next week, remembered, is asked again as next week
        val nextWeek = weather(WeatherWhen.Week(TEST_TODAY.plusDays(1)), null)
        nextWeek.turn.slots[AnswerFollowUps.WHEN] shouldBe "next week"
    }

    "no, boston corrects the place" {
        val austin = weather(WeatherWhen.Now, "austin")
        val boston = rewriteOf(AnswerFollowUps, FollowUpForm.Correction("boston", CorrectionKind.NO), austin).command
        boston shouldBe "what's the weather in boston"
        shouldBeTakenBy(boston!!, "weather", mapOf("place" to "boston"))
        rewriteOf(AnswerFollowUps, FollowUpForm.Correction("boston", CorrectionKind.MEANT), austin).command shouldBe
            "what's the weather in boston"
        rewriteOf(AnswerFollowUps, FollowUpForm.Correction(null, CorrectionKind.OTHER), austin).shouldBeNull()
        // "no wait", "no that's wrong" are about the conversation, not a place
        for (value in listOf("wait", "thats wrong", "sorry")) {
            rewriteOf(AnswerFollowUps, FollowUpForm.Correction(value, CorrectionKind.NO), austin).shouldBeNull()
        }
    }

    "a place that would split the sentence isn't written into it" {
        val odd = weather(WeatherWhen.Now, "starbucks, 5th and pine")
        rewriteOf(AnswerFollowUps, whatAbout("tomorrow"), odd).shouldBeNull()
    }

    "the time somewhere: and in london" {
        val tokyo = WorldTimeAnswer.Known(WorldTimeRequest.TimeIn(Place("tokyo", ZoneId.of("Asia/Tokyo"))))
        val turn = rememberedBy(AnswerFollowUps, answered("world_time", "what time is it in tokyo", tokyo, FakeOutput(null)))
        turn.turn.facts.place?.name shouldBe "tokyo"
        val london = rewriteOf(AnswerFollowUps, whatAbout("london", "in", bareAnd = true), turn).command
        london shouldBe "what time is it in london"
        shouldBeTakenBy(london!!, "world_time")
        // after the time here too
        val here = rememberedBy(
            AnswerFollowUps,
            answered("current_time", "what time is it", Sentences.CurrentTime.Query, FakeOutput(null)),
        )
        rewriteOf(AnswerFollowUps, whatAbout("london", "in", bareAnd = true), here).command shouldBe
            "what time is it in london"
        rewriteOf(AnswerFollowUps, whatAbout("tomorrow"), here).shouldBeNull()
    }

    "and easter after a holiday" {
        val turn = rememberedBy(AnswerFollowUps, answered("holiday", "what day is christmas", null, FakeOutput(null)))
        rewriteOf(AnswerFollowUps, whatAbout("easter", bareAnd = true), turn).command shouldBe "when is easter"
        shouldBeTakenBy("when is easter", "holiday")
        // a day counted from the last one is no holiday
        rewriteOf(AnswerFollowUps, whatAbout("the day after", bareAnd = true), turn).shouldBeNull()
    }

    "another one: the same joke request, the same dice" {
        val joke = rememberedBy(RepeatFollowUps, answered(JokeInfo.id, "tell me a joke", null, FakeOutput(null)))
        rewriteOf(RepeatFollowUps, FollowUpForm.Another(null), joke).command shouldBe "tell me a joke"
        rewriteOf(RepeatFollowUps, FollowUpForm.Another("joke"), joke).command shouldBe "tell me a joke"
        shouldBeTakenBy("tell me a joke", JokeInfo.id)

        val dice = rememberedBy(
            RepeatFollowUps,
            answered("random", "roll 2 dice", RandomRequest.Dice(2, 6), FakeOutput(null)),
        )
        rewriteOf(RepeatFollowUps, FollowUpForm.Another(null), dice).command shouldBe "roll 2d6"
        shouldBeTakenBy("roll 2d6", "random")
        rewriteOf(RepeatFollowUps, FollowUpForm.Another("joke"), dice).shouldBeNull()

        val coin = rememberedBy(RepeatFollowUps, answered("random", "flip a coin", RandomRequest.Coin, FakeOutput(null)))
        rewriteOf(RepeatFollowUps, FollowUpForm.Another(null), coin).command shouldBe "flip a coin"
        shouldBeTakenBy("flip a coin", "random")
        val pick = rememberedBy(
            RepeatFollowUps,
            answered("random", "pick a number between 1 and 100", RandomRequest.Pick(1, 100), FakeOutput(null)),
        )
        rewriteOf(RepeatFollowUps, FollowUpForm.Another(null), pick).command shouldBe
            "pick a random number between 1 and 100"
        shouldBeTakenBy("pick a random number between 1 and 100", "random")
        // only "another"
        rewriteOf(RepeatFollowUps, whatAbout("tomorrow"), coin).shouldBeNull()
    }
})
