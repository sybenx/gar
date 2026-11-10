package org.stypox.dicio.skills

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.garRankingWinner

/** The skill each input goes to, ranked like the evaluator with the skills of a new install. */
private fun winners(inputs: List<String>): Map<String, String?> =
    inputs.associateWith { garRankingWinner(it).first }

private val NEW_SKILLS = setOf(
    "battery", "world_time", "units", "math", "random", "spelling", "holiday", "small_talk",
)

/**
 * gar:answers (gar/PARITY.md, batch 2). The everyday answers take their own inputs, which went to
 * the model or got a wrong answer before ("seven plus nine" became a timer, "what time is it in
 * tokyo" the local time), and nothing that belongs to another skill.
 */
class EverydayAnswersRankingTest : StringSpec({
    "the new skills take their inputs" {
        val expected = mapOf(
            "battery" to listOf(
                "what's my battery", "what is my battery level", "how much battery do i have left",
                "is my phone charging", "is it charging", "how long until my phone is charged",
                "how long until it's fully charged", "when will my battery be full",
                "what percent is my phone at", "how's my battery", "is my phone charged",
                "is my phone fully charged", "is it charged", "is my battery charged", "battery",
                "how long will my battery last",
            ),
            "world_time" to listOf(
                "what time is it in tokyo", "what's the time in london",
                "what time is it in new york right now", "what's the time difference with london",
                "what's the time difference between new york and london",
                "how many hours ahead is tokyo", "what time is it in paris france",
                "what's the local time in sydney", "do you know what time it is in japan",
                "what time is it in vegas", "what time is it in la", "what time is it in philly",
                "what time is it in maui", "what time is it in eastern standard time",
                "what's the time in greenwich mean time",
                // a place GAR doesn't know: it says so, instead of the local time
                "what time is it in moab",
            ),
            "units" to listOf(
                "how many cups in a liter", "convert seventy fahrenheit to celsius",
                "five miles in kilometers", "how many ounces in a pound",
                "how many feet are in a mile", "what's 5 miles in km", "how many grams is 5 ounces",
                "how many tablespoons in a cup", "convert 100 kilograms to pounds",
            ),
            "math" to listOf(
                "seven plus nine", "twelve times eight", "what's fifteen percent of eighty",
                "the square root of sixty four", "what is the square root of sixty four",
                "five squared", "what's a twenty percent tip on eighty five dollars",
                "how much is twelve times seven", "what is 8 times 7 please", "100 divided by 4",
                "what's eighty plus fifteen percent", "100 plus 20 percent",
            ),
            "random" to listOf(
                "flip a coin", "heads or tails", "toss a coin", "roll a die", "roll two dice",
                "roll a d twenty", "pick a number between one and ten", "pick a random number",
                "give me a random number between 1 and 100",
            ),
            "spelling" to listOf(
                "how do you spell necessary", "spell rhythm", "how is accommodate spelled",
                "what's the spelling of separate", "spell out necessary",
            ),
            "holiday" to listOf(
                "how many days until christmas", "when is thanksgiving",
                "how many days until my birthday", "how many days until friday",
                "what's the date of easter", "what is the date of thanksgiving",
                "when is mother's day", "how long until halloween", "what day is christmas",
                "how many days until next year", "how many days are left in the year",
                "how many days until our anniversary",
            ),
            "small_talk" to listOf(
                "hello", "hi there", "good morning", "thank you", "thanks a lot", "who are you",
                "what's your name", "how are you", "what can you do", "help", "bye", "good night",
                "ok thank you", "okay thanks", "alright thanks", "perfect thanks", "thank you google",
                "thanks google", "goodnight",
            ),
        )
        for ((skill, inputs) in expected) {
            withClue(skill) { winners(inputs).filterValues { it != skill } shouldBe mapOf() }
        }
    }

    "existing commands stay with their skills" {
        for ((input, skill) in listOf(
            "what time is it" to "current_time", "what is two plus two" to "calculator",
            "what is seven plus nine" to "calculator", "calculate 12 divided by 4" to "calculator",
            "set a timer for five minutes" to "timer", "remind me to call mom" to "calendar",
            "send a text to mom" to "sms", "whats the date" to "date",
            "what's the date of the fourth of july" to "date", "what day is tomorrow" to "date",
            // gar:settings opens the battery page itself now
            "open battery settings" to "device_settings", "when is my next alarm" to "alarm",
            "what song is this" to "song", "turn on the flashlight" to "flashlight",
            "call mike" to "telephone", "stop" to "conversation", "never mind" to "conversation",
            "what month is it" to "current_time", "turn up the volume" to "volume",
            "no thanks" to "conversation", "nothing" to "conversation", "nope" to "conversation",
            "not now" to "conversation", "say again" to "conversation",
            "can you repeat" to "conversation", "i can't hear you" to "conversation",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe skill }
        }
    }

    "the new skills take nothing else" {
        val inputs = listOf(
            "turn on battery saver", "is my watch charging", "turn on wireless charging",
            "what time is it in the morning", "what time does the pharmacy close",
            "remind me to call mom in an hour", "translate cat to french", "send this to mom",
            "set a timer for five minutes", "call five five five plus one", "seven eleven",
            "roll down the window", "flip the screen", "what's my phone number", "pick up the phone",
            "how do you spell my name", "turn on spell check", "when is my dentist appointment",
            "how long until the pasta is done", "when is the next bus", "who are you calling",
            "help me find my phone", "thank you set a timer", "hello world", "what is it",
            "show me", "what's the weather in tokyo", "how far is tokyo", "play some music",
            "what's on my calendar tomorrow", "how much time is left on my timer",
            // Dicio's matcher left out "spell": "can you spell .word."
            "can you dial the dentist", "can you open firefox", "can you call mom",
            // not places, nor days
            "what time is it in military time", "what time is it in my time zone",
            "what time is it in two hours", "what's the time in 24 hour format",
            "what time is it in here", "how many days left in the month",
            "how many days until next week", "spell out the address",
        )
        winners(inputs).filterValues { it in NEW_SKILLS } shouldBe mapOf()
    }
})
