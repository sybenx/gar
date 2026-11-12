package org.stypox.dicio.skills.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarYesNo
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.ui.GarChip
import org.stypox.dicio.gar.ui.GarChipStrings
import org.stypox.dicio.gar.ui.GarChipTable
import org.stypox.dicio.gar.ui.GarSuggestions
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.skills.sms.SmsCardOutput
import org.stypox.dicio.skills.sms.SmsFlow
import org.stypox.dicio.skills.song.openListenerSettings
import org.stypox.dicio.util.getString

/** A string resource formatted with its arguments: `ctx.getString`, or the strings in tests. */
typealias Say = (Int, List<Any?>) -> String

/** [Say] through the context's resources. */
fun SkillContext.say(): Say = { id, args -> getString(id, *args.toTypedArray()) }

/** What GAR asks after reading, see [MessagesSpeech.questionAfter]. */
enum class ReadQuestion(val text: Int?) {
    /** The conversation read last has a reply button. */
    REPLY(R.string.skill_messages_ask_reply),

    /** More conversations wait, and the one read last can't be replied to. */
    MORE(R.string.skill_messages_ask_more),

    /** "Who texted me": the names only, so far. */
    READ(R.string.skill_messages_ask_read),

    /** Nothing more to read or do. */
    NONE(null),
}

/** What GAR says when reading, English sentences put together from the strings. */
object MessagesSpeech {
    /** Only a conversation's newest messages are spoken; the card shows them all. */
    const val MAX_SPOKEN_MESSAGES = 3

    /** At most this many names are said, the rest are counted. */
    const val MAX_NAMES = 5

    /** "Mom", "Mom and Dad", "Mom, Dad and Family", "Mom, Dad, Sarah, Family and 3 more". */
    fun names(say: Say, names: List<String>): String {
        val distinct = names.distinct()
        val said = if (distinct.size > MAX_NAMES) {
            distinct.take(MAX_NAMES - 1) +
                say(R.string.skill_messages_names_more, listOf(distinct.size - MAX_NAMES + 1))
        } else {
            distinct
        }
        return if (said.size <= 1) {
            said.joinToString("")
        } else {
            said.dropLast(1).joinToString(say(R.string.skill_messages_names_separator, listOf())) +
                say(R.string.skill_messages_names_and, listOf()) + said.last()
        }
    }

    /**
     * "Mom said: Are you coming? It's late.", or for a group "In Family, Dad said: On my way.
     * Mom said: OK."
     */
    fun conversation(say: Say, conversation: Conversation): String {
        val spoken = conversation.messages.takeLast(MAX_SPOKEN_MESSAGES)
        if (!conversation.isGroup) {
            return said(say, conversation.name, spoken)
        }
        val runs = mutableListOf<Pair<String, MutableList<ChatMessage>>>()
        for (message in spoken) {
            val sender = conversation.senderOf(message)
            if (runs.lastOrNull()?.first == sender) {
                runs.last().second.add(message)
            } else {
                runs.add(Pair(sender, mutableListOf(message)))
            }
        }
        return say(
            R.string.skill_messages_in_group,
            listOf(conversation.name, runs.joinToString(" ") { (sender, messages) -> said(say, sender, messages) }),
        )
    }

    private fun said(say: Say, who: String, messages: List<ChatMessage>): String = say(
        R.string.skill_messages_said,
        listOf(who, messages.joinToString(" ") { SmsCardOutput.spokenMessage(it.text) }),
    )

    /** What GAR asks after reading [conversations] up to [end]. */
    fun questionAfter(conversations: List<Conversation>, end: Int): ReadQuestion = when {
        conversations[end].canReply -> ReadQuestion.REPLY
        end < conversations.size - 1 -> ReadQuestion.MORE
        else -> ReadQuestion.NONE
    }

    /** A step of [MessagesReadOutput], with the names first when [intro], and its question. */
    fun step(
        say: Say,
        conversations: List<Conversation>,
        start: Int,
        end: Int,
        intro: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        if (intro) {
            parts.add(say(R.string.skill_messages_from, listOf(names(say, conversations.map { it.name }))))
        }
        for (i in start..end) {
            parts.add(conversation(say, conversations[i]))
        }
        questionAfter(conversations, end).text?.let { parts.add(say(it, listOf())) }
        return parts.joinToString(" ")
    }
}

/**
 * Messages read aloud: [conversations] from [start] to [end], see [MessagesFlow.readStep]. The
 * microphone opens again when GAR asks something ([MessagesSpeech.questionAfter]); the answers
 * ([MessagesReadAnswerSkill]) reply, go on, or end the reading. A reply ("reply ok", "tell her
 * i'm on my way") is taken without the question too, also later on.
 */
class MessagesReadOutput(
    private val flow: MessagesFlow,
    val conversations: List<Conversation>,
    val start: Int,
    val end: Int,
    private val intro: Boolean,
    private val nested: Boolean,
) : SkillOutput, GarSuggestions {
    val question: ReadQuestion get() = MessagesSpeech.questionAfter(conversations, end)

    /** The one sender this step read, for the Reply and Call chips; null for a group or several. */
    val chipSender: Conversation?
        get() = conversations.getOrNull(start)
            ?.takeIf { start == end && !it.isGroup && it.name.isNotBlank() }

    override fun suggestions(ctx: SkillContext): List<GarChip> {
        val sender = chipSender
        return GarChipTable.messagesRead(
            GarChipStrings.of(ctx), sender?.name, canReply = sender?.canReply == true,
        )
    }

    override fun getSpeechOutput(ctx: SkillContext): String =
        MessagesSpeech.step(ctx.say(), conversations, start, end, intro)

    fun nextSkills(): List<Skill<*>> =
        listOf(MessagesReadAnswerSkill(flow, conversations, end, question))

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan {
        val ask = question != ReadQuestion.NONE
        return if (nested) {
            InteractionPlan.ReplaceSubInteraction(reopenMicrophone = ask, nextSkills())
        } else {
            InteractionPlan.StartSubInteraction(reopenMicrophone = ask, nextSkills())
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in start..end) {
                ConversationBox(conversations[i])
            }
            question.text?.let { Body(text = stringResource(it)) }
        }
    }
}

/** A conversation on a card: its name and app, then every message (with its sender in a group). */
@Composable
fun ConversationBox(conversation: Conversation) {
    Column {
        Text(
            text = buildAnnotatedString {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(conversation.name)
                }
                append(" · ")
                withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(conversation.app)
                }
            },
            style = MaterialTheme.typography.titleMedium,
        )
        for (message in conversation.messages) {
            Text(
                text = if (conversation.isGroup) {
                    "${conversation.senderOf(message)}: ${message.text}"
                } else {
                    message.text
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * "Who texted me?": "You have messages from Mom and Dad. Want me to read them?", with the
 * microphone open.
 */
class MessagesWhoOutput(
    private val flow: MessagesFlow,
    val conversations: List<Conversation>,
    private val nested: Boolean,
) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String {
        val say = ctx.say()
        return say(R.string.skill_messages_from, listOf(MessagesSpeech.names(say, conversations.map { it.name }))) +
            " " + say(R.string.skill_messages_ask_read, listOf())
    }

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        SmsFlow.plan(nested, listOf(MessagesReadAnswerSkill(flow, conversations, -1, ReadQuestion.READ)))

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            Spacer(modifier = Modifier.height(4.dp))
            Body(text = conversations.joinToString("\n") { "${it.name} · ${it.app}" })
        }
    }
}

/** An answer to [MessagesReadOutput] or [MessagesWhoOutput], see [ReadAnswers.classify]. */
sealed interface ReadAnswer {
    /** @param text the reply as it will be sent, or null to ask for it ("reply", "yes reply") */
    data class Reply(val text: String?) : ReadAnswer
    data object Next : ReadAnswer
    data object Yes : ReadAnswer
    data object No : ReadAnswer
}

/**
 * Short spoken answers after GAR read messages, English only like GAR's other answers: a reply
 * ([MessageWords.replyText]), "next" or "skip", or a yes or no ([GarYesNo]) made only of known
 * words, so that a new command ("call mom", "ok thanks") goes to its own skill and ends the
 * reading. Cancel phrases are left to the conversation skill.
 */
object ReadAnswers {
    private val REPLY_VERBS = setOf("reply", "respond", "answer")
    private val NEXT_WORDS = setOf("next", "skip", "continue", "more", "else", "going", "on", "rest")
    private val ANSWER_WORDS = GarYesNo.YES_WORDS + GarYesNo.NO_WORDS + GarYesNo.NEGATIONS +
        REPLY_VERBS + NEXT_WORDS + setOf(
            "i", "want", "to", "it", "the", "one", "read", "message", "messages", "please", "just",
            "now", "so", "oh", "uh", "um", "well", "actually", "can", "you", "do", "go", "keep",
            "what", "anything", "and", "then", "her", "him", "them", "that", "this", "me", "let",
            "lets", "let's", "hear", "back", "don't", "dont", "mind", "never",
        )

    /** What [input] answers, or null if it is not an answer. */
    fun classify(input: String): ReadAnswer? {
        if (isCancelPhrase(input)) {
            return null
        }
        MessageWords.replyText(input)?.let { return ReadAnswer.Reply(it) }
        val words = normalizeAnswer(input.replace('’', '\'')).split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty() || !words.all { it in ANSWER_WORDS }) {
            return null
        }
        val negated = words.indices.any { GarYesNo.isNegation(words, it) }
        if (words.any { it in REPLY_VERBS }) {
            // "no, reply", "yes reply", but "don't reply"
            val yes = GarYesNo.answer(words, { words[it] in GarYesNo.YES_WORDS }, { words[it] in REPLY_VERBS })
            return if (negated || yes == false) ReadAnswer.No else ReadAnswer.Reply(null)
        }
        if (words.any { it in NEXT_WORDS }) {
            return if (negated) ReadAnswer.No else ReadAnswer.Next
        }
        if (negated) {
            return ReadAnswer.No
        }
        return when (GarYesNo.answer(words, { words[it] in GarYesNo.YES_WORDS }, { false })) {
            true -> ReadAnswer.Yes
            false -> ReadAnswer.No
            null -> null
        }
    }
}

/**
 * The answers after GAR read [conversations] up to [end] (-1: only their names) and asked
 * [question]. A reply goes to the conversation read last; "next" reads on; yes and no answer the
 * question, and only when there is one.
 */
class MessagesReadAnswerSkill(
    private val flow: MessagesFlow,
    private val conversations: List<Conversation>,
    private val end: Int,
    private val question: ReadQuestion,
) : Skill<ReadAnswer?>(MessagesInfo, Specificity.HIGH) {
    private val current: Conversation? get() = conversations.getOrNull(end)
    private val hasMore: Boolean get() = end + 1 < conversations.size

    override fun score(ctx: SkillContext, input: String): Pair<Score, ReadAnswer?> {
        val answer = ReadAnswers.classify(input)?.takeIf(::applies)
        return Pair(if (answer == null) AlwaysWorstScore else AlwaysBestScore, answer)
    }

    private fun applies(answer: ReadAnswer): Boolean = when (answer) {
        is ReadAnswer.Reply -> current != null
        ReadAnswer.Next -> true
        ReadAnswer.Yes, ReadAnswer.No -> question != ReadQuestion.NONE
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: ReadAnswer?): SkillOutput =
        when (inputData) {
            is ReadAnswer.Reply -> flow.replyTo(current!!, inputData.text, nested = true)
            ReadAnswer.Yes -> when (question) {
                ReadQuestion.REPLY -> flow.replyTo(current!!, null, nested = true)
                ReadQuestion.MORE, ReadQuestion.READ -> readOn()
                ReadQuestion.NONE -> MessagesDoneOutput(noMore = true)
            }
            ReadAnswer.Next -> if (hasMore) readOn() else MessagesDoneOutput(noMore = true)
            // AlwaysWorstScore is never chosen, so null can't happen
            ReadAnswer.No, null ->
                if (question == ReadQuestion.REPLY && hasMore) readOn() else MessagesDoneOutput(noMore = false)
        }

    private fun readOn(): SkillOutput =
        flow.readStep(conversations, end + 1, intro = false, nested = true)
}

/** "OK." after a no, or "That's all your messages." after "next" at the end. */
class MessagesDoneOutput(val noMore: Boolean) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(
        if (noMore) R.string.skill_messages_no_more else R.string.skill_messages_done
    )
}

/** "You have no new messages (from mom, on signal)." */
class MessagesNoneOutput(val request: ReadRequest) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = when {
        request.source == null -> ctx.getString(R.string.skill_messages_none)
        request.via == Via.ON -> ctx.getString(R.string.skill_messages_none_on, request.source)
        else -> ctx.getString(R.string.skill_messages_none_from, request.source)
    }
}

/**
 * GAR can't see the notifications although it has notification access (Dicio checks that before
 * the skill runs): the listener isn't connected yet. The card offers the access page anyway.
 */
class MessagesNoAccessOutput : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_messages_no_access)

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        Column {
            Headline(text = getSpeechOutput(ctx))
            Spacer(modifier = Modifier.height(8.dp))
            Body(text = stringResource(R.string.skill_messages_needs_listener))
            Spacer(modifier = Modifier.height(8.dp))
            GarbleSecondaryButton( // GAR
                onClick = { openListenerSettings(context) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(text = stringResource(R.string.skill_messages_allow_access))
            }
        }
    }
}
