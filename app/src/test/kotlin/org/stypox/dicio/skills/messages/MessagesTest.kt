package org.stypox.dicio.skills.messages

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.InteractionPlan
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.skills.sms.SmsAskOutput
import org.stypox.dicio.skills.sms.SmsCardOutput
import org.stypox.dicio.skills.sms.SmsContact
import org.stypox.dicio.skills.sms.SmsFlow
import org.stypox.dicio.skills.sms.SmsRecipients

private fun notification(
    key: String,
    packageName: String = "org.thoughtcrime.securesms",
    title: String? = null,
    conversationTitle: String? = null,
    isGroupConversation: Boolean = false,
    styleMessages: List<StyleMessage>? = null,
    text: String? = null,
    isMessage: Boolean = true,
    group: String? = null,
    isGroupSummary: Boolean = false,
    isOngoing: Boolean = false,
    postTime: Long = 100,
    canReply: Boolean = true,
) = MessageNotification(
    key, packageName, group, isGroupSummary, isOngoing, isMessage, title, conversationTitle,
    isGroupConversation, styleMessages, text, postTime, canReply,
)

private val APPS = mapOf(
    "org.thoughtcrime.securesms" to "Signal",
    "com.whatsapp" to "WhatsApp",
    "com.google.android.apps.messaging" to "Messages",
)

private fun conversations(vararg notifications: MessageNotification) =
    Conversations.of(notifications.toList(), { APPS[it] ?: it }, ownPackage = "org.stypox.dicio")

private val MOM = chat("Mom", "Are you coming?", "It's getting late", time = 3_000)
private val DAD = chat("Dad", "On my way", app = "WhatsApp", time = 2_000)
private val FAMILY = group("Family", "Sarah Lee" to "Dinner at six", "Dad" to "I'll bring dessert", time = 1_500)
private val BANK = chat("First Bank", "Your code is 1234", app = "Messages", time = 1_000, canReply = false)
private val ALL = listOf(MOM, DAD, FAMILY, BANK)

private fun find(source: String?, via: Via = Via.FROM, latestOnly: Boolean = false) =
    MessageFinder.find(ALL, ReadRequest(source, via, latestOnly))

class MessagesTest : StringSpec({
    "conversations come from messaging notifications, without the user's own messages" {
        val found = conversations(
            notification(
                "mom", title = "Mom", postTime = 300,
                styleMessages = listOf(
                    StyleMessage("Mom", false, "Are you coming?", 100),
                    StyleMessage(null, true, "In a bit", 200),
                    StyleMessage("Mom", false, "It's late", 300),
                ),
            ),
            notification(
                "family", packageName = "com.whatsapp", conversationTitle = "Family",
                isGroupConversation = true, postTime = 200,
                styleMessages = listOf(
                    StyleMessage("Dad", false, "Dinner at six", 150),
                    StyleMessage("Sarah", false, " ", 160),
                ),
            ),
            // no messaging style: the title and the text
            notification(
                "bank", packageName = "com.google.android.apps.messaging", title = "First Bank",
                text = "Your code is 1234", postTime = 50, canReply = false,
            ),
            // not messages: a download, an ongoing call, GAR's own, an empty one
            notification("download", packageName = "com.android.providers.downloads", isMessage = false, text = "Done"),
            notification("call", isOngoing = true, title = "Mom", text = "Ongoing call"),
            notification("gar", packageName = "org.stypox.dicio", title = "GAR", text = "Timer"),
            notification("empty", title = "Signal", text = null),
        )
        found.map { it.key } shouldContainExactly listOf("mom", "family", "bank")
        found[0] shouldBe Conversation(
            key = "mom", packageName = "org.thoughtcrime.securesms", app = "Signal", name = "Mom",
            isGroup = false,
            messages = listOf(ChatMessage(null, "Are you coming?", 100), ChatMessage(null, "It's late", 300)),
            time = 300, canReply = true,
        )
        found[1].isGroup shouldBe true
        found[1].name shouldBe "Family"
        found[1].messages shouldContainExactly listOf(ChatMessage("Dad", "Dinner at six", 150))
        found[2].name shouldBe "First Bank"
        found[2].canReply shouldBe false
    }

    "a group summary counts only when its group shows no chats of its own" {
        val chatAndSummary = conversations(
            notification("summary", packageName = "com.whatsapp", group = "chats", isGroupSummary = true, title = "WhatsApp", text = "2 messages from 2 chats"),
            notification("dad", packageName = "com.whatsapp", group = "chats", title = "Dad", text = "On my way"),
        )
        chatAndSummary.map { it.key } shouldContainExactly listOf("dad")
        val summaryAlone = conversations(
            notification("summary", packageName = "com.whatsapp", group = "chats", isGroupSummary = true, title = "Dad", text = "On my way"),
        )
        summaryAlone.map { it.key } shouldContainExactly listOf("summary")
    }

    "a chat's name: its title, the person who writes, or the notification's title" {
        conversations(notification("a", styleMessages = listOf(StyleMessage("Jane Doe", false, "hi", 1))))
            .single().name shouldBe "Jane Doe"
        conversations(notification("b", title = "Mom", text = "hi")).single().name shouldBe "Mom"
        // several people writing make a group, also without the app saying so
        val group = conversations(
            notification("c", title = "Book club", styleMessages = listOf(
                StyleMessage("Ann", false, "hi", 1), StyleMessage("Bob", false, "hey", 2),
            )),
        ).single()
        group.isGroup shouldBe true
        group.name shouldBe "Book club"
    }

    "conversations are found by person, group, sender or app" {
        find(null) shouldBe ALL
        find("mom").map { it.name } shouldContainExactly listOf("Mom")
        find("MOM").map { it.name } shouldContainExactly listOf("Mom")
        find("frist bank").map { it.name } shouldContainExactly listOf("First Bank")
        find("the family").map { it.name } shouldContainExactly listOf("Family")
        find("first bank").map { it.name } shouldContainExactly listOf("First Bank")
        // a person who wrote in a group: their chat, and their part of the group
        find("dad").map { Pair(it.name, it.messages.map(ChatMessage::text)) } shouldContainExactly listOf(
            Pair("Dad", listOf("On my way")),
            Pair("Family", listOf("I'll bring dessert")),
        )
        find("sarah").single().messages.single().sender shouldBe "Sarah Lee"
        // apps: "on signal", and "from whatsapp" when no person has that name
        find("signal", Via.ON).map { it.name } shouldContainExactly listOf("Mom", "Family")
        find("whats app", Via.ON).map { it.name } shouldContainExactly listOf("Dad")
        find("whatsapp").map { it.name } shouldContainExactly listOf("Dad")
        find("mom on signal").map { it.name } shouldContainExactly listOf("Mom")
        find("mom on whatsapp").shouldBeEmpty()
        find("grandma").shouldBeEmpty()
        find("telegram", Via.ON).shouldBeEmpty()
    }

    "the last text is the newest message alone" {
        find(null, latestOnly = true) shouldContainExactly listOf(MOM.copy(messages = MOM.messages.takeLast(1)))
        find("dad", latestOnly = true).single().messages.map { it.text } shouldContainExactly listOf("On my way")
    }

    "the sentences' matches count only when they are about messages" {
        MessageWords.readRequest("read my messages", null) shouldBe ReadRequest(null)
        MessageWords.readRequest("read my messages from mom", "mom") shouldBe ReadRequest("mom")
        MessageWords.readRequest("any new messages on signal", "signal") shouldBe ReadRequest("signal", Via.ON)
        MessageWords.readRequest("what's in the family chat", "the family chat").shouldBeNull()
        MessageWords.readRequest("any new texts in the family chat", "the family chat") shouldBe
            ReadRequest("family", Via.ON)
        MessageWords.readRequest("what did dad say", "dad") shouldBe ReadRequest("dad")
        MessageWords.readRequest("read mom's messages", "mom") shouldBe ReadRequest("mom")
        MessageWords.readRequest("read my last text", null) shouldBe ReadRequest(null, latestOnly = true)
        MessageWords.readRequest("read my last messages", null) shouldBe ReadRequest(null)
        MessageWords.readRequest("read me the messages", "the") shouldBe ReadRequest(null)
        MessageWords.readRequest("who texted me", null, namesOnly = true) shouldBe
            ReadRequest(null, namesOnly = true)
        // "again" is no one's name: the message read last, or the messages as usual
        MessageWords.readRequest("read the message again", "again") shouldBe ReadRequest(null, again = true)
        MessageWords.readRequest("read my messages again", "again") shouldBe ReadRequest(null)
        MessageWords.readRequest("read messages from mom again", "mom again") shouldBe ReadRequest("mom")

        for ((input, source) in listOf(
            // a word swapped by Dicio's matcher, or not about chat messages
            "read my notifications" to null, "read my email" to null, "did mom call me" to "mom",
            "any new emails from sarah" to "sarah", "did i get any calls" to null,
            // no one to look up
            "what did you say" to "you", "what did he say" to "he", "did someone text me" to "someone",
            "any messages in the last hour" to "the last hour",
        )) {
            withClue(input) { MessageWords.readRequest(input, source).shouldBeNull() }
        }
    }

    "a reply match needs a reply word, and her or it means the message read last" {
        MessageWords.isReply("reply to mom saying hi", "mom", "hi") shouldBe true
        MessageWords.isReply("text mom back", "mom", null) shouldBe true
        MessageWords.isReply("reply ok", null, "ok") shouldBe true
        MessageWords.isReply("text mom", "mom", null) shouldBe false
        MessageWords.isReply("reply to this email", "this email", null) shouldBe false

        for (who in listOf(null, "her", "him", "them", "it", "that", "this message", "the last one", "the message")) {
            withClue(who) { MessageWords.meansLastRead(who) shouldBe true }
        }
        MessageWords.meansLastRead("mom") shouldBe false
        MessageWords.meansLastRead("her mom") shouldBe false
        MessageWords.afterLastRead("her i'm on my way") shouldBe "i'm on my way"
        MessageWords.afterLastRead("mom i'm on my way").shouldBeNull()
        MessageWords.afterLastRead("her").shouldBeNull()
    }

    "replies after reading a message" {
        for ((input, reply) in listOf(
            "reply ok" to "OK",
            "reply saying i'm on my way" to "I'm on my way",
            "reply with sounds good" to "Sounds good",
            "respond back see you soon" to "See you soon",
            "reply to her saying i'll call later" to "I'll call later",
            "reply to that message no thanks" to "No thanks",
            "write back sounds good" to "Sounds good",
            "tell her i'm on my way" to "I'm on my way",
            "yes tell him that i'm running late" to "I'm running late",
            "text them back saying be there soon" to "Be there soon",
            "say i love you" to "I love you",
            "ok say yes" to "Yes",
            "let her know i'm almost there" to "I'm almost there",
            "Reply: OK!" to "OK!",
        )) {
            withClue(input) { MessageWords.replyText(input) shouldBe reply }
        }
        for (input in listOf(
            "reply", "reply to her", "reply to mom", "reply to mom saying hi", "say that again",
            "say it again please", "say again", "say it", "what did she say", "yes", "tell me more",
            "reply saying",
        )) {
            withClue(input) { MessageWords.replyText(input).shouldBeNull() }
        }
    }

    "the answers after reading" {
        for ((input, answer) in listOf(
            "reply ok" to ReadAnswer.Reply("OK"),
            "tell her i'm on my way" to ReadAnswer.Reply("I'm on my way"),
            "reply" to ReadAnswer.Reply(null),
            "yes reply" to ReadAnswer.Reply(null),
            "i want to reply to her" to ReadAnswer.Reply(null),
            "don't reply" to ReadAnswer.No,
            "yes" to ReadAnswer.Yes,
            "sure" to ReadAnswer.Yes,
            "yes please" to ReadAnswer.Yes,
            "no" to ReadAnswer.No,
            "nope" to ReadAnswer.No,
            "not now" to ReadAnswer.No,
            "next" to ReadAnswer.Next,
            "skip it" to ReadAnswer.Next,
            "read the next one" to ReadAnswer.Next,
            "keep going" to ReadAnswer.Next,
            "what else" to ReadAnswer.Next,
        )) {
            withClue(input) { ReadAnswers.classify(input) shouldBe answer }
        }
        // new commands, and what the conversation skill takes
        for (input in listOf(
            "call mom", "ok thanks", "cancel", "never mind", "stop", "what time is it",
            "read my messages from dad", "reply to mom saying hi", "repeat that", "say that again",
        )) {
            withClue(input) { ReadAnswers.classify(input).shouldBeNull() }
        }
    }

    "the model's read_notifications calls about messages, and its sms_send calls" {
        MessageWords.fromReadCall("read my notifications").shouldBeNull()
        MessageWords.fromReadCall("what do my notifications say").shouldBeNull()
        MessageWords.fromReadCall("read my messages") shouldBe ReadRequest(null)
        MessageWords.fromReadCall("read my last text message") shouldBe ReadRequest(null, latestOnly = true)
        MessageWords.fromReadCall("did mom text me") shouldBe ReadRequest("mom")
        MessageWords.fromReadCall("what did sarah lee write") shouldBe ReadRequest("sarah lee")
        MessageWords.fromReadCall("read the notifications from my messages on signal please") shouldBe
            ReadRequest("signal", Via.ON)
        MessageWords.fromReadCall("show me texts from dad") shouldBe ReadRequest("dad")
        MessageWords.fromReadCall("any messages in the last hour") shouldBe ReadRequest(null)
        MessageWords.fromReadCall("read my emails").shouldBeNull()

        MessagesCalls.asksForEmail("send an email to my boss", "my boss", null) shouldBe true
        MessagesCalls.asksForEmail("send an e-mail to sarah", "sarah", null) shouldBe true
        MessagesCalls.asksForEmail("send a text to my boss", "my boss", null) shouldBe false
        // the email word has to be the command's, not the message's
        MessagesCalls.asksForEmail("tell mom i'll check my email later", "mom", "i'll check my email later") shouldBe
            false
        MessagesCalls.asksForEmail("send an email to my boss saying i'm sick", "my boss", "i'm sick") shouldBe true
        // the model's message is often the whole command, or a part of it
        MessagesCalls.asksForEmail("send an email to my boss", "my boss", "send an email to my boss") shouldBe true
        MessagesCalls.asksForEmail("send an email to my boss", "my boss", "an email") shouldBe true
        MessageWords.emailMessage("my boss", "an email").shouldBeNull()
        MessageWords.emailMessage("my boss", "i'm sick") shouldBe "i'm sick"
        MessageWords.isReplyCommand("respond to mom with on my way") shouldBe true
        MessageWords.isReplyCommand("ok reply to dad") shouldBe true
        MessageWords.isReplyCommand("text mom back saying hi") shouldBe true
        MessageWords.isReplyCommand("text mom saying i'll be back soon") shouldBe false
        MessageWords.isReplyCommand("tell mom i'm on my way") shouldBe false
    }

    "messages are read with the names first, a conversation at a time" {
        MessagesSpeech.names(englishSay, listOf("Mom")) shouldBe "Mom"
        MessagesSpeech.names(englishSay, listOf("Mom", "Dad")) shouldBe "Mom and Dad"
        MessagesSpeech.names(englishSay, listOf("Mom", "Dad", "Family")) shouldBe "Mom, Dad and Family"
        MessagesSpeech.names(englishSay, (1..7).map { "P$it" }) shouldBe "P1, P2, P3, P4 and 3 more"

        MessagesSpeech.step(englishSay, ALL, 0, 0, intro = true) shouldBe
            "You have messages from Mom, Dad, Family and First Bank. " +
            "Mom said: Are you coming? It's getting late. Want to reply?"
        MessagesSpeech.step(englishSay, ALL, 2, 2, intro = false) shouldBe
            "In Family, Sarah Lee said: Dinner at six. Dad said: I'll bring dessert. Want to reply?"
        // the bank's notification has no reply button: nothing to ask at the end
        MessagesSpeech.step(englishSay, ALL, 3, 3, intro = false) shouldBe
            "First Bank said: Your code is 1234."
        val noReply = listOf(BANK, BANK.copy(key = "b2", name = "Dentist"))
        MessagesSpeech.step(englishSay, noReply, 0, 0, intro = false) shouldBe
            "First Bank said: Your code is 1234. Want to hear more?"

        // only the newest three messages are spoken
        val many = chat("Mom", "one", "two", "three", "four")
        MessagesSpeech.conversation(englishSay, many) shouldBe "Mom said: two. three. four."
    }

    "a step reads up to the first conversation that can be replied to" {
        val noReply = BANK
        MessagesFlow.stepEnd(listOf(MOM, DAD), 0) shouldBe 0
        MessagesFlow.stepEnd(listOf(noReply, noReply, MOM, DAD), 0) shouldBe 2
        MessagesFlow.stepEnd(listOf(noReply, noReply, noReply, noReply, MOM), 0) shouldBe 2
        MessagesFlow.stepEnd(listOf(noReply, noReply), 1) shouldBe 1
    }

    "reading, then yes, no and next" {
        val source = FakeSource(ALL)
        val flow = messagesFlow(source)
        val first = flow.read(FakeSkillContext(), ReadRequest(null)).shouldBeInstanceOf<MessagesReadOutput>()
        Pair(first.start, first.end) shouldBe Pair(0, 0)
        first.question shouldBe ReadQuestion.REPLY
        first.getInteractionPlan(FakeSkillContext()).reopenMicrophone shouldBe true

        val ranker = rankerAfter(first)
        // no: the next conversation
        val second = ranker.evaluate("no").shouldBeInstanceOf<MessagesReadOutput>()
        second.conversations[second.end].name shouldBe "Dad"
        ranker.evaluate("skip").shouldBeInstanceOf<MessagesReadOutput>().end shouldBe 2
        // the bank last: no question, and "next" says that was all
        val last = ranker.evaluate("next").shouldBeInstanceOf<MessagesReadOutput>()
        last.question shouldBe ReadQuestion.NONE
        last.getInteractionPlan(FakeSkillContext()).reopenMicrophone shouldBe false
        ranker.evaluate("yes").shouldBeNull()
        ranker.evaluate("next").shouldBeInstanceOf<MessagesDoneOutput>().noMore shouldBe true
        source.replies.shouldBeEmpty()
    }

    "a reply after reading: read back, then sent on a spoken yes" {
        val source = FakeSource(ALL)
        val flow = messagesFlow(source)
        val read = flow.read(FakeSkillContext(), ReadRequest("mom"))
        val ranker = rankerAfter(read)

        val card = ranker.evaluate("reply ok").shouldBeInstanceOf<ReplyCardOutput>()
        card.draft.message shouldBe "OK"
        ReplyCardOutput.readBack(englishSay, card.draft) shouldBe
            "Your reply to Mom on Signal says: OK. Ready to send it?"
        card.getInteractionPlan(FakeSkillContext()).reopenMicrophone shouldBe true

        ranker.evaluate("read it again").shouldBeInstanceOf<ReplyReadBackOutput>()
        source.replies.shouldBeEmpty()
        val sent = ranker.evaluate("yes send it").shouldBeInstanceOf<ReplySentOutput>()
        sent.draft.state shouldBe ReplyState.SENT
        ReplyCardOutput.speech(englishSay, sent.draft) shouldBe "Sent."
        source.replies shouldContainExactly listOf(Pair("Signal|Mom", "OK"))
        // sent once
        ranker.evaluate("yes").shouldBeNull()
    }

    "yes to 'Want to reply?' asks for the reply" {
        val source = FakeSource(ALL)
        val ranker = rankerAfter(messagesFlow(source).read(FakeSkillContext(), ReadRequest("dad", latestOnly = true)))
        ranker.evaluate("yes").shouldBeInstanceOf<ReplyAskOutput>().conversation.name shouldBe "Dad"
        val card = ranker.evaluate("see you soon").shouldBeInstanceOf<ReplyCardOutput>()
        ReplyCardOutput.readBack(englishSay, card.draft) shouldBe
            "Your reply to Dad on WhatsApp says: See you soon. Ready to send it?"
        ranker.evaluate("change it to see you at six").shouldBeInstanceOf<ReplyCardOutput>()
            .draft.message shouldBe "See you at six"
        card.draft.state shouldBe ReplyState.REPLACED
        ranker.evaluate("no").shouldBeInstanceOf<ReplySentOutput>().draft.state shouldBe ReplyState.CANCELLED
        source.replies.shouldBeEmpty()
    }

    "a reply says plainly when the notification is gone or has no reply button" {
        for ((result, speech) in listOf(
            ReplyResult.GONE to "The message from Mom is gone from your notifications, so I can't reply to it anymore.",
            ReplyResult.NO_REPLY to "This Signal notification has no reply button, so I can't reply to it from here.",
            ReplyResult.FAILED to "Signal didn't take the reply.",
            ReplyResult.NO_ACCESS to "I can't see your notifications right now. Try again in a moment.",
        )) {
            val source = FakeSource(ALL, result = result)
            val card = messagesFlow(source).reply(FakeSkillContext(), "mom", "on my way", nested = false)
                .shouldBeInstanceOf<ReplyCardOutput>()
            val output = rankerAfter(card).evaluate("yes").shouldBeInstanceOf<ReplySentOutput>()
            withClue(result) { ReplyCardOutput.speech(englishSay, output.draft) shouldBe speech }
        }

        // known before asking: no card for a notification without a reply button
        val bank = messagesFlow(FakeSource(ALL)).reply(FakeSkillContext(), "first bank", "thanks", nested = false)
            .shouldBeInstanceOf<MessagesNoReplyOutput>()
        bank.conversation.name shouldBe "First Bank"
        messagesFlow(FakeSource(listOf())).reply(FakeSkillContext(), null, "ok", nested = false)
            .shouldBeInstanceOf<MessagesNothingToReplyOutput>()
        messagesFlow(FakeSource(null)).reply(FakeSkillContext(), "mom", "ok", nested = false)
            .shouldBeInstanceOf<MessagesNoAccessOutput>()
    }

    "replies by name, to the message read last, or split from the name" {
        val source = FakeSource(ALL)
        val memory = ReadMemory()
        val flow = messagesFlow(source, memory = memory)
        flow.reply(FakeSkillContext(), "dad", "ok", nested = false)
            .shouldBeInstanceOf<ReplyCardOutput>().draft.conversation.name shouldBe "Dad"
        // dad's own chat before the group he wrote in
        flow.reply(FakeSkillContext(), "dad", null, nested = false)
            .shouldBeInstanceOf<ReplyAskOutput>().conversation.name shouldBe "Dad"
        flow.reply(FakeSkillContext(), "mom i'm on my way", null, nested = false)
            .shouldBeInstanceOf<ReplyCardOutput>().draft.let {
                Pair(it.conversation.name, it.message) shouldBe Pair("Mom", "I'm on my way")
            }

        // "reply ok" goes to the message read last, here dad's
        flow.read(FakeSkillContext(), ReadRequest("whatsapp", Via.ON))
        flow.reply(FakeSkillContext(), null, "ok", nested = false)
            .shouldBeInstanceOf<ReplyCardOutput>().draft.conversation.name shouldBe "Dad"
        flow.reply(FakeSkillContext(), "her i'm late", null, nested = false)
            .shouldBeInstanceOf<ReplyCardOutput>().draft.let {
                Pair(it.conversation.name, it.message) shouldBe Pair("Dad", "I'm late")
            }
        // nothing read yet: the newest chat with a reply button
        messagesFlow(FakeSource(listOf(BANK, DAD))).reply(FakeSkillContext(), "it", "ok", nested = false)
            .shouldBeInstanceOf<ReplyCardOutput>().draft.conversation.name shouldBe "Dad"
    }

    "the message read last is forgotten after ten minutes" {
        var now = 0L
        val memory = ReadMemory { now }
        memory.remember(DAD)
        memory.lastRead() shouldBe DAD
        now = ReadMemory.FRESH_MS + 1
        memory.lastRead().shouldBeNull()
    }

    "no message from that person: GAR offers a text instead" {
        val texts = SmsFlow(
            findRecipient = { _, spoken, split ->
                SmsRecipients.find(
                    spoken = spoken, splitMessage = split,
                    search = { listOf(SmsContact("Grandma", listOf("555 0199"))) },
                    name = { it.name }, distance = { -10 }, numbers = { it.numbers },
                )
            },
            isDeviceLocked = { false },
        )
        val flow = MessagesFlow(sourceFor = { FakeSource(ALL) }, isDeviceLocked = { false }, memory = ReadMemory(), textFlow = { texts })
        val offer = flow.reply(FakeSkillContext(), "grandma", "happy birthday", nested = false)
            .shouldBeInstanceOf<MessagesTextInsteadOutput>()
        offer.name shouldBe "grandma"
        val card = rankerAfter(offer).evaluate("yes").shouldBeInstanceOf<SmsCardOutput>()
        Pair(card.draft.recipient.name, card.draft.message) shouldBe Pair("Grandma", "Happy birthday")

        val withoutText = flow.reply(FakeSkillContext(), "grandma", null, nested = false)
        rankerAfter(withoutText).evaluate("sure").shouldBeInstanceOf<SmsAskOutput>()
        rankerAfter(withoutText).evaluate("no thanks").shouldBeInstanceOf<MessagesNotSendingOutput>()
        rankerAfter(withoutText).evaluate("call grandma").shouldBeNull()
    }

    "who texted me: the names, then the messages on a yes" {
        val flow = messagesFlow(FakeSource(ALL))
        val who = flow.read(FakeSkillContext(), ReadRequest(null, namesOnly = true))
            .shouldBeInstanceOf<MessagesWhoOutput>()
        who.getInteractionPlan(FakeSkillContext()).reopenMicrophone shouldBe true
        val ranker = rankerAfter(who)
        ranker.evaluate("reply ok").shouldBeNull()
        ranker.evaluate("yes").shouldBeInstanceOf<MessagesReadOutput>().let {
            Pair(it.start, it.end) shouldBe Pair(0, 0)
        }
    }

    "read the message again: the conversation read last, while its notification shows" {
        val memory = ReadMemory()
        val flow = messagesFlow(FakeSource(ALL), memory = memory)
        memory.remember(DAD)
        flow.read(FakeSkillContext(), ReadRequest(null, again = true))
            .shouldBeInstanceOf<MessagesReadOutput>().conversations shouldContainExactly listOf(DAD)
        // nothing read before: the messages as usual
        messagesFlow(FakeSource(ALL)).read(FakeSkillContext(), ReadRequest(null, again = true))
            .shouldBeInstanceOf<MessagesReadOutput>().conversations shouldContainExactly ALL
    }

    "a reply goes only to the chat the user confirmed" {
        // the app posted the chat again under another key
        Conversations.replyTarget(listOf(MOM.copy(key = "k2"), DAD), MOM)?.key shouldBe "k2"
        // the app now shows another chat under the same key: not that one
        val swapped = DAD.copy(key = MOM.key, packageName = MOM.packageName, app = MOM.app)
        Conversations.replyTarget(listOf(swapped), MOM).shouldBeNull()
        Conversations.replyTarget(listOf(swapped, MOM.copy(key = "k3")), MOM)?.key shouldBe "k3"
        Conversations.replyTarget(listOf(MOM), MOM) shouldBe MOM
    }

    "nothing to read, no access, or a locked phone" {
        val flow = messagesFlow(FakeSource(ALL))
        flow.read(FakeSkillContext(), ReadRequest("grandma")).shouldBeInstanceOf<MessagesNoneOutput>()
            .request shouldBe ReadRequest("grandma")
        messagesFlow(FakeSource(listOf())).read(FakeSkillContext(), ReadRequest(null))
            .shouldBeInstanceOf<MessagesNoneOutput>()
        messagesFlow(FakeSource(null)).read(FakeSkillContext(), ReadRequest(null))
            .shouldBeInstanceOf<MessagesNoAccessOutput>()
        messagesFlow(FakeSource(ALL), locked = true).read(FakeSkillContext(), ReadRequest(null))
            .shouldBeInstanceOf<GarLockedOutput>()
        messagesFlow(FakeSource(ALL), locked = true).reply(FakeSkillContext(), "mom", "hi", nested = false)
            .shouldBeInstanceOf<GarLockedOutput>()
    }

    "outputs from a follow-up replace its batch" {
        val flow = messagesFlow(FakeSource(ALL))
        flow.readStep(ALL, 1, intro = false, nested = true).getInteractionPlan(FakeSkillContext())
            .shouldBeInstanceOf<InteractionPlan.ReplaceSubInteraction>()
        flow.readStep(ALL, 1, intro = false, nested = false).getInteractionPlan(FakeSkillContext())
            .shouldBeInstanceOf<InteractionPlan.StartSubInteraction>()
        flow.replyTo(MOM, null, nested = true).getInteractionPlan(FakeSkillContext())
            .shouldBeInstanceOf<InteractionPlan.ReplaceSubInteraction>().reopenMicrophone shouldBe true
    }

    "the reply card's status lines" {
        ReplyCardOutput.statusText(ReplyState.PENDING).shouldBeNull()
        ReplyCardOutput.statusText(ReplyState.SENT).shouldNotBeNull()
    }
})
