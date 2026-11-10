package org.stypox.dicio.gar

import cc.aaro.gar.router.SpokenForm
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.stypox.dicio.skills.notes.NoteRequest
import org.stypox.dicio.skills.notes.NoteWords

/**
 * Inputs that two of PARITY batches 5 and 6's skills could both take, ranked with every English
 * skill of a new install (no facts remembered: NotesRankingTest has those). Each line is a clash
 * resolved when the four batches were merged (gar/DESIGN.md, "Batches 5 and 6: who takes what").
 */
class GarBatch56RankingTest : StringSpec({
    "what is X: knowledge, the dictionary, the calculator, translation and the weather" {
        for ((input, skill) in listOf(
            "what is serendipity" to "knowledge", "what is a cat" to "knowledge",
            "what's the capital of france" to "knowledge", "who is taylor swift" to "knowledge",
            "how tall is the eiffel tower" to "knowledge", "when was abraham lincoln born" to "knowledge",
            "what does serendipity mean" to "dictionary",
            "what is the meaning of serendipity" to "dictionary",
            "define serendipity" to "dictionary", "synonym for happy" to "dictionary",
            "how do you spell necessary" to "spelling",
            "what is 5 plus 5" to "calculator", "what is hello in spanish" to "translation",
            "what time is it in tokyo" to "world_time",
            "what's the temperature" to "weather", "what is the temperature outside" to "weather",
            "what's the weather in paris" to "weather",
            "20 euros in dollars" to "currency", "20 pounds in kilograms" to "units",
            "what's mom's number" to "contact_info", "what's my address" to "contact_info",
            "what's my home address" to "places",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe skill }
        }
        // questions about the user or the phone are nobody's Wikipedia question
        for (input in listOf("who am i", "what is my name", "what's my locker number", "what's up")) {
            withClue(input) { garRankingWinner(input).first shouldNotBe "knowledge" }
        }
    }

    "where is: navigation, places, the facts and the weather" {
        for ((input, skill) in listOf(
            "where is paris" to "navigation", "where is the nearest gas station" to "navigation",
            "get me directions to grandma's house" to "navigation",
            "take me home" to "navigation", "directions to work" to "navigation",
            "where am i" to "places", "gas stations nearby" to "places",
            "where do i live" to "places", "forget my work address" to "places",
            "where did i park" to "facts", "where is my car" to "facts",
            "forget where i parked" to "facts",
            "what's the weather nearby" to "weather",
            "share my location with mom" to "share_location",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe skill }
        }
    }

    "remember, notes and reminders" {
        for ((input, skill) in listOf(
            "remember that i parked on level 3" to "facts",
            "remind me to buy milk" to "calendar", "remind me to take a note" to "calendar",
            "take a note call the dentist" to "notes", "make a note to call mom" to "notes",
            "note to self buy eggs" to "notes",
            "what are my reminders" to "reminders", "delete the reminder about the milk" to "reminders",
            "what's on my calendar tomorrow" to "calendar", "what's my next event" to "agenda",
            "clear my calendar tomorrow" to "agenda", "clear my shopping list" to "lists",
            "clear my notifications" to "clear_notifications",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe skill }
        }
        // "remember to ..." is a reminder for the model, never a fact or a note
        garRankingWinner("remember to buy milk").first shouldBe null
        NoteWords.request("make a note to call mom") shouldBe NoteRequest.Take("call mom")
    }

    "take a: the camera, the screenshot and notes" {
        for ((input, skill) in listOf(
            "take a picture" to "camera", "take a selfie" to "camera",
            "take a photo in 5 seconds" to "camera", "take a screenshot" to "screenshot",
            "take a note" to "notes",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe skill }
        }
    }

    "do you know: the facts only when a fact answers it (none here)" {
        for ((input, skill) in listOf(
            "do you know the time" to "current_time",
            "do you know if it's going to rain" to "weather",
            "do you know a joke" to "Joke",
            "do you know what time it is in japan" to "world_time",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe skill }
        }
        garRankingWinner("do you know what time it is").first shouldBe "current_time"
    }

    "Whisper's written web addresses reach the website skill" {
        for (written in listOf("Go to reddit.com.", "Visit wikipedia.org", "Go to aaro.cc")) {
            val input = SpokenForm.of(written).spoken
            withClue(input) { garRankingWinner(input).first shouldBe "website" }
        }
    }

    "calendar words in other requests stay out of the calendar" {
        for (input in listOf("delete all my voicemails", "switch my call to speaker", "clear all notifications")) {
            withClue(input) { garRankingWinner(input).first shouldNotBe "agenda" }
        }
    }
})
