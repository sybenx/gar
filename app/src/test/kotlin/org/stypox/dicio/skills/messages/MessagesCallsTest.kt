package org.stypox.dicio.skills.messages

import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.FakeSkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.skills.sms.SmsInfo
import java.io.File

private fun sourceFile(path: String): String =
    sequenceOf(File(path), File("app", path)).first { it.isFile }.readText()

private val tools = ToolCatalog.load(
    sourceFile("src/main/gar/tools.yml"),
    sourceFile("src/main/sentences/skill_definitions.yml"),
).tools.associateBy { it.name }

/** A call of the model, its slots as said (contacts and texts stay as said). */
private fun call(tool: String, utterance: String, vararg slots: Pair<String, String>) = GarCall(
    tool = tools.getValue(tool),
    rawSlots = slots.toMap(),
    utterance = utterance,
    values = slots.toMap(),
)

private val MOM = chat("Mom", "Are you coming?", time = 2_000)
private val DAD = chat("Dad", "On my way", app = "WhatsApp", time = 1_000)

/**
 * gar:messages: the model's read_notifications and sms_send calls that the messages and email
 * skills answer, resolved in Kotlin with no tool change ([MessagesCalls]).
 */
class MessagesCallsTest : StringSpec({
    val source = FakeSource(listOf(MOM, DAD))
    val mailer = object : Mailer {
        val composed = mutableListOf<EmailDraft>()
        override fun compose(draft: EmailDraft): Boolean = composed.add(draft)
    }
    val contacts = listOf(EmailContact("Boss", listOf("boss@example.com")))

    /** As GarFallback routes the call (every skill on), then runs it. */
    suspend fun output(call: GarCall): SkillOutput? {
        val info = MessagesCalls.rerouteInfo(call.tool, call.rawSlots, call.utterance) { true }
            ?: return null
        return MessagesCalls.output(
            FakeSkillContext(),
            call,
            info,
            messages = { messagesFlow(source) },
            email = { EmailFlow(findContacts = { contacts }, mailerFor = { mailer }, isDeviceLocked = { false }) },
        )
    }

    "read_notifications about messages reads them by person or app" {
        output(call("read_notifications", "did mom text me")).shouldBeInstanceOf<MessagesReadOutput>()
            .let { read -> read.conversations.map { it.name } shouldContainExactly listOf("Mom") }
        output(call("read_notifications", "read my texts on whatsapp")).shouldBeInstanceOf<MessagesReadOutput>()
            .let { read -> read.conversations.map { it.name } shouldContainExactly listOf("Dad") }
        output(call("read_notifications", "do i have any messages")).shouldBeInstanceOf<MessagesReadOutput>()
            .conversations.size shouldBe 2
        output(call("read_notifications", "any messages from grandma")).shouldBeInstanceOf<MessagesNoneOutput>()
        // about the notifications: Dicio's notify skill
        output(call("read_notifications", "read my notifications")).shouldBeNull()
        output(call("read_notifications", "what are my alerts")).shouldBeNull()
    }

    "sms_send that says email opens the mail app" {
        output(call("sms_send", "send an email to my boss saying i'm sick", "to" to "my boss", "message" to "i'm sick"))
            .shouldBeInstanceOf<EmailOpenedOutput>()
        mailer.composed.single() shouldBe
            EmailDraft(EmailRecipient("Boss", "boss@example.com"), "I'm sick", "I'm sick")
        // the model's message made of the command's words is no text: GAR asks for it
        output(call("sms_send", "send an email to my boss", "to" to "my boss", "message" to "send an email to my boss"))
            .shouldBeInstanceOf<EmailAskOutput>().question shouldBe EmailQuestion.MESSAGE
        output(call("sms_send", "send an email to my boss", "to" to "my boss", "message" to "email"))
            .shouldBeInstanceOf<EmailAskOutput>().question shouldBe EmailQuestion.MESSAGE
        // email in the message itself: a text, left to the SMS skill
        output(call("sms_send", "tell mom i'll check my email later", "to" to "mom", "message" to "i'll check my email later"))
            .shouldBeNull()
    }

    "a call goes to the messages or email skill only when it is turned on" {
        val email = call("sms_send", "send an email to my boss", "to" to "my boss")
        MessagesCalls.rerouteInfo(email.tool, email.rawSlots, email.utterance) { true } shouldBe EmailInfo
        MessagesCalls.rerouteInfo(email.tool, email.rawSlots, email.utterance) { it != EmailInfo.id }
            .shouldBeNull()
        val reply = call("sms_send", "respond to mom with on my way", "to" to "mom", "message" to "on my way")
        MessagesCalls.rerouteInfo(reply.tool, reply.rawSlots, reply.utterance) { true } shouldBe MessagesInfo
        MessagesCalls.rerouteInfo(reply.tool, reply.rawSlots, reply.utterance) { it != MessagesInfo.id }
            .shouldBeNull()
        val read = call("read_notifications", "did mom text me")
        MessagesCalls.rerouteInfo(read.tool, read.rawSlots, read.utterance) { true } shouldBe MessagesInfo
        // a call under its own skill's info is left to that skill
        MessagesCalls.output(FakeSkillContext(), email, SmsInfo).shouldBeNull()
    }

    "sms_send that replies goes through the notification's reply button" {
        output(call("sms_send", "respond to mom with on my way", "to" to "mom", "message" to "on my way"))
            .shouldBeInstanceOf<ReplyCardOutput>().draft.let {
                Pair(it.conversation.name, it.message) shouldBe Pair("Mom", "On my way")
            }
        output(call("sms_send", "text mom saying hi", "to" to "mom", "message" to "hi")).shouldBeNull()
    }

    "other tools are left to their skills" {
        output(call("current_time", "what time is it")).shouldBeNull()
    }
})
