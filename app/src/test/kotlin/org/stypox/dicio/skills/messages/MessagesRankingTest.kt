package org.stypox.dicio.skills.messages

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.garRankingWinner

/** The skill each input goes to, ranked like the evaluator with the skills of a new install. */
private fun winners(inputs: List<String>): Map<String, String?> =
    inputs.associateWith { garRankingWinner(it).first }

/**
 * gar:messages (gar/PARITY.md, batch 4). The messages and email skills take their requests,
 * which went to the notify skill, the sms skill or the model before ("read my messages" read every
 * notification, "send an email to my boss" became a text), and nothing of the other skills.
 */
class MessagesRankingTest : StringSpec({
    "the new skills take their inputs" {
        val expected = mapOf(
            "messages" to listOf(
                "read my messages", "read my new messages", "read my texts", "read my text messages",
                "read me my messages", "check my messages", "read my messages from mom",
                "read my texts from dad", "read the messages from sarah lee", "read mom's messages",
                "read my last text", "read my latest message from mom",
                "any new messages", "any new messages on signal", "any messages from mom",
                "do i have any new messages", "do i have any messages from dad",
                "did i get any texts", "are there any new messages on whatsapp",
                "what are my new messages", "what's the latest message from mom",
                "what did dad say", "what did mom text me", "what does mom's message say",
                "did mom text me", "has dad messaged me", "did sarah reply", "has john texted me back",
                "who texted me", "who just messaged me", "who sent me a message",
                "reply to mom saying i'm on my way", "reply to mom with sounds good",
                "respond to dad saying see you soon", "reply to mom", "reply ok",
                "reply saying i'm on my way", "reply with thanks", "text mom back saying ok",
                "message dad back", "reply to her saying i'll call later", "reply",
            ),
            "email" to listOf(
                "email sarah saying the report is done", "email sarah that the report is done",
                "send an email to my boss", "send an email to sarah saying i'll be late",
                "send an email to john about the meeting", "email mom", "send an email",
                "write an email to dad saying happy birthday", "compose an email to sarah",
                "email the team about the meeting saying it moved to friday",
                "email sarah about lunch",
            ),
        )
        for ((skill, inputs) in expected) {
            withClue(skill) { winners(inputs).filterValues { it != skill } shouldBe mapOf() }
        }
    }

    "the reply and email captures" {
        garRankingWinner("reply to mom saying i'm on my way").second shouldBe
            MessagesCommand.Reply("mom", "i'm on my way")
        garRankingWinner("reply ok").second shouldBe MessagesCommand.Reply(null, "ok")
        garRankingWinner("read my messages from mom").second shouldBe
            MessagesCommand.Read(ReadRequest("mom"))
        garRankingWinner("any new messages on signal").second shouldBe
            MessagesCommand.Read(ReadRequest("signal", Via.ON))
    }

    "existing commands stay with their skills" {
        for ((input, skill) in listOf(
            "read my notifications" to "notify", "read the notifications" to "notify",
            "text mom" to "sms", "send a text to mom" to "sms", "text mom saying hi" to "sms",
            "send a message to john saying see you at five" to "sms", "message dad" to "sms",
            "call mom" to "telephone", "what did you say" to "conversation",
            "say that again" to "conversation", "never mind" to "conversation",
            "what time is it" to "current_time", "set a timer for five minutes" to "timer",
            "open messages" to "open", "open signal" to "open",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe skill }
        }
    }

    "the new skills take nothing else" {
        val inputs = listOf(
            "read my email", "check my email", "any new emails", "did mom call me", "who called me",
            "did i miss any calls", "what did he say", "did someone text me", "read the news",
            "send a picture to dad", "send this to mom", "what's the weather", "read me a story",
            "turn on read receipts", "play my voicemail", "read my notification settings",
            "send mom i'm on my way", "tell sarah i'll be late", "text me back later",
            // the eval's current_time and stopwatch cases
            "what does the clock say", "what does the stopwatch say",
        )
        winners(inputs).filterValues { it == "messages" || it == "email" } shouldBe mapOf()
    }
})
