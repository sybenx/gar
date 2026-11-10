package org.stypox.dicio.skills.navigation

import cc.aaro.gar.router.CallVerdict
import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.GarMatchCheck
import org.stypox.dicio.gar.call.garCallFilter
import org.stypox.dicio.gar.garRankingWinner
import org.stypox.dicio.sentences.Sentences
import java.io.File

private val NAVIGATE by lazy {
    fun source(path: String) = sequenceOf(File(path), File("app", path)).first { it.isFile }.readText()
    ToolCatalog.load(
        source("src/main/gar/tools.yml"),
        source("src/main/sentences/skill_definitions.yml"),
    ).byName("navigate")!!
}

private fun navigateCall(utterance: String, where: String) =
    RouterOutput.Call(NAVIGATE, mapOf("where" to where), listOf(), Utterances.normalize(utterance))

/**
 * "where's my battery" (Vosk's hearing of "what's my battery") went to Dicio's "where is .where."
 * sentence: "Navigating to my battery". The phone's parts and settings are no place.
 */
class NavigationWordsTest : StringSpec({
    val phoneQuestions = listOf(
        "where's my battery", "where is my battery", "where s my battery", "wheres my battery",
        "Where's my battery?", "where is my battery level", "where's the battery",
        "where is my phone's battery", "where is the phone s battery", "where is my phone",
        "where's my phone charger", "where is my cell phone", "where is my wifi",
        "where's the wi-fi password", "where is my signal", "where is the bluetooth",
        "where is my battery at", "where's my location", "where is my alarm",
    )
    val places = listOf(
        "where is the nearest gas station", "where's the closest pharmacy",
        "where is the nearest phone store", "where is the charging station", "where is battery park",
        "where's my car", "where is grandma's house", "where is the closest wifi cafe",
        "navigate to the battery", "take me to the phone store", "directions to my office",
    )

    "where is a part or a setting of the phone" {
        for (input in phoneQuestions) {
            withClue(input) { NavigationWords.asksAboutPhone(input) shouldBe true }
        }
    }

    "places, other leads and other questions are not" {
        for (input in places + listOf("what's my battery", "where is", "where", "", "battery")) {
            withClue(input) { NavigationWords.asksAboutPhone(input) shouldBe false }
        }
    }

    "the navigation sentence leaves the phone" {
        val navigation = NavigationSkill(NavigationInfo, Sentences.Navigation["en"]!!)
        for (input in phoneQuestions) {
            withClue(input) {
                navigation.score(FakeSkillContext().forInput(input), input).first shouldBe
                    AlwaysWorstScore
            }
        }
        val (score, query) = navigation.score(
            FakeSkillContext().forInput("where is the nearest gas station"),
            "where is the nearest gas station",
        )
        score shouldNotBe AlwaysWorstScore
        query.shouldBeInstanceOf<Sentences.Navigation.WhereIs>().where?.trim() shouldBe
            "the nearest gas station"
    }

    "the battery skill takes where's my battery, navigation keeps places" {
        for (input in listOf(
            "where's my battery", "where is my battery", "where is my battery level",
            "where's the battery percentage",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe "battery" }
        }
        for (input in listOf("where is my wifi", "where's my phone", "where is my signal")) {
            withClue(input) { garRankingWinner(input).first shouldNotBe NavigationInfo.id }
        }
        for (input in listOf(
            "where is the nearest gas station", "where's the closest pharmacy",
            "where is the nearest phone store", "where is battery park",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe NavigationInfo.id }
        }
    }

    "the model's navigate calls are kept unless narration or a question about when" {
        for (input in listOf(
            "take me to the train station", "directions to central park", "how do i get to the airport",
            "navigate home", "drive to grandma's house", "find me a route to the nearest gas station",
            "get me to forty second street", "show me the way to the library", "let's go to the beach",
            "route me to the closest pharmacy", "where is the nearest pharmacy", "take me home",
            "directions to costco", "can you take me to work", "hey navigate to the airport please",
            "i need directions to the mall", "how far is denver", "i want to go to the zoo",
            // no list of phrasings catches these; the model's call stands
            "what's the fastest way home", "what's the best route home", "i want to go home",
            "i need to get home", "which way is the airport", "how long does it take to get to the airport",
            "how long will it take to drive to work", "can i get to the airport by bus",
            "open maps to costco", "i'm heading to the airport", "nearest gas station",
            "im trying to get to the airport", "i'm trying to get directions to costco",
            "i'm trying to find the way to the airport", "we're trying to get to the stadium, take us there",
        )) {
            withClue(input) { NavigationWords.refusesDirections(input) shouldBe false }
        }
        for (input in listOf(
            "You're trying to get to the first half of your business.",
            "you are going to the store", "she's on her way to the office", "they're driving to denver",
            "when should i go to bed", "why do i go to work", "what time should i get to the airport",
            "should i go to the store", "",
        )) {
            withClue(input) { NavigationWords.refusesDirections(input) shouldBe true }
        }
    }

    "the how-line without its how" {
        for (input in listOf("when should i go to bed", "do i go to the gym today", "should i get to bed")) {
            withClue(input) { NavigationWords.skipsHow(input) shouldBe true }
        }
        for (input in listOf(
            "how do i get to the airport", "so how can i get home", "where can i get gas",
            "can i get directions to the mall", "take me home", "where is the nearest pharmacy",
        )) {
            withClue(input) { NavigationWords.skipsHow(input) shouldBe false }
        }
    }

    "when should I go to bed is no navigation; real requests still are" {
        for (input in listOf(
            "when should i go to bed", "When should I go to bed?", "what time should i go to bed",
            "should i go to bed", "when should i go to sleep", "how do i get to sleep",
            "where is the bathroom",
        )) {
            withClue(input) { garRankingWinner(input).first shouldNotBe NavigationInfo.id }
        }
        for (input in listOf(
            "how do i get to the airport", "take me home", "directions to costco",
            "where is the nearest pharmacy",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe NavigationInfo.id }
        }
    }

    "GarMatchCheck rejects a navigation match that skipped the how" {
        val state = GarMatchCheck.State(listOf())
        val match = Sentences.Navigation.Query("bed")
        GarMatchCheck.rejects(state, NavigationInfo.id, match, 0.8f, "when should i go to bed") shouldBe true
        GarMatchCheck.rejects(state, NavigationInfo.id, match, 0.8f, "do i go to the gym today") shouldBe true
        GarMatchCheck.rejects(state, NavigationInfo.id, match, 0.9f, "how do i get to the airport") shouldBe false
        GarMatchCheck.rejects(state, NavigationInfo.id, match, 1f, "take me home") shouldBe false
    }

    "the model's navigate calls need a request for directions to a place" {
        val filter = garCallFilter(FakeSkillContext())
        for ((utterance, where) in listOf(
            "You're trying to get to the first half of your business." to "the first half of your business",
            "you are going to the store" to "the store",
            "i'm trying to get to sleep" to "sleep",
            "when should i go to bed" to "bed",
            "take me to bed" to "bed",
        )) {
            withClue(utterance) {
                filter.check(navigateCall(utterance, where)).shouldBeInstanceOf<CallVerdict.Drop>()
                    .reason shouldContain "navigate"
            }
        }
        for ((utterance, where) in listOf(
            "directions to costco" to "costco",
            "let's go to the beach" to "the beach",
            "navigate home" to "home",
            "show me the way to the library" to "the library",
            // no sentence takes these, so the model's call is their only route
            "what's the fastest way home" to "home",
            "what's the best route home" to "home",
            "i want to go home" to "home",
            "i need to get home" to "home",
            "which way is the airport" to "the airport",
            "how long does it take to get to the airport" to "the airport",
            "how long will it take to drive to work" to "work",
            "can i get to the airport by bus" to "the airport",
            "open maps to costco" to "costco",
            "nearest gas station" to "nearest gas station",
            "i'm heading to the airport" to "the airport",
            "i'm trying to get to the airport" to "the airport",
            "i'm trying to get directions to costco" to "costco",
        )) {
            withClue(utterance) { filter.check(navigateCall(utterance, where)) shouldBe CallVerdict.Keep }
        }
    }

    "the model's navigate calls for the phone are dropped, places kept" {
        val filter = garCallFilter(FakeSkillContext())
        for ((utterance, where) in listOf(
            "where is my wifi" to "my wifi",
            "where's my battery" to "my battery",
            "where is my phone" to "my phone",
        )) {
            withClue(utterance) {
                filter.check(navigateCall(utterance, where)).shouldBeInstanceOf<CallVerdict.Drop>()
                    .reason shouldContain "navigate"
            }
        }
        for ((utterance, where) in listOf(
            "take me to the train station" to "the train station",
            "where is the nearest gas station" to "the nearest gas station",
            "how do i get to the airport" to "the airport",
        )) {
            withClue(utterance) { filter.check(navigateCall(utterance, where)) shouldBe CallVerdict.Keep }
        }
    }
})
