package org.stypox.dicio.skills.messages

import org.stypox.dicio.gar.ui.garble.GarblePrimaryButton // GAR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarYesNo
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.skills.sms.SmsCardAnswer
import org.stypox.dicio.skills.sms.SmsCardAnswers
import org.stypox.dicio.skills.sms.SmsCardOutput
import org.stypox.dicio.skills.sms.SmsFlow
import org.stypox.dicio.skills.sms.SmsInfo
import org.stypox.dicio.util.getString
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR

enum class ReplyState {
    /** Waiting for the user to say or tap send. */
    PENDING,

    /** Handed to the app through the notification's reply button. */
    SENT,
    GONE,
    NO_REPLY,
    FAILED,
    NO_ACCESS,
    CANCELLED,

    /** The user changed the reply or the chat: a new draft replaces this one. */
    REPLACED,

    /** The app was opened instead ("open it in signal"); nothing was sent. */
    OPENED_APP,
    NO_APP,
}

/**
 * A reply waiting for the user's decision on its card. Only [send] sends: the card's Send button
 * and a spoken yes after the read-back ([ReplyCardAnswerSkill]), once, also when both come at the
 * same time (taps run on the main thread, voice answers on the evaluator's), like GAR's texts.
 */
class ReplyDraft(val conversation: Conversation, val message: String) {
    var state by mutableStateOf(ReplyState.PENDING)
        private set

    val isPending: Boolean get() = state == ReplyState.PENDING

    @Synchronized
    fun send(source: MessageSource) {
        if (state != ReplyState.PENDING) {
            return
        }
        state = when (source.reply(conversation, message)) {
            ReplyResult.SENT -> ReplyState.SENT
            ReplyResult.GONE -> ReplyState.GONE
            ReplyResult.NO_REPLY -> ReplyState.NO_REPLY
            ReplyResult.FAILED -> ReplyState.FAILED
            ReplyResult.NO_ACCESS -> ReplyState.NO_ACCESS
        }
    }

    /** Opens the chat's app instead; never sends. */
    @Synchronized
    fun openApp(source: MessageSource) {
        if (state == ReplyState.PENDING) {
            state = if (source.openApp(conversation.packageName)) ReplyState.OPENED_APP else ReplyState.NO_APP
        }
    }

    @Synchronized
    fun cancel() {
        if (state == ReplyState.PENDING) {
            state = ReplyState.CANCELLED
        }
    }

    @Synchronized
    fun replace() {
        if (state == ReplyState.PENDING) {
            state = ReplyState.REPLACED
        }
    }
}

/**
 * The reply's card: the chat, its last message and the reply, with Cancel and Send. Its speech
 * reads the reply back ("Your reply to Mom on Signal says: OK. Ready to send it?") with the
 * microphone open, and the voice answers are the text card's ([SmsCardAnswers]): yes or "send
 * it" sends, no cancels, "change it to ..." takes a new reply, "read it again" repeats.
 */
class ReplyCardOutput(
    private val flow: MessagesFlow,
    val draft: ReplyDraft,
    private val nested: Boolean,
) : SkillOutput {

    override fun getSpeechOutput(ctx: SkillContext): String = readBack(ctx.say(), draft)

    fun nextSkills(): List<Skill<*>> = listOf(ReplyCardAnswerSkill(flow, draft))

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        SmsFlow.plan(nested, nextSkills())

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        val skillEvaluator = remember {
            EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
                .skillEvaluator()
        }
        // the buttons work while this card's conversation is the latest one (see SmsCardOutput)
        val log by skillEvaluator.state.collectAsState()
        val current = log.interactions.lastOrNull()?.questionsAnswers?.any {
            it.answer === this || (it.answer as? GarFollowUpOutput)?.output === this
        } == true
        val source = remember { flow.sourceFor(ctx) }
        val conversation = draft.conversation

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Headline(text = stringResource(R.string.skill_messages_reply_title, conversation.name))
            Body(text = conversation.app)
            conversation.messages.lastOrNull()?.let { last ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (conversation.isGroup) "${conversation.senderOf(last)}: ${last.text}" else last.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = draft.message,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(12.dp),
                )
            }

            statusText(draft.state)?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(it, conversation.app),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GarbleSecondaryButton(onClick = { draft.cancel() }, enabled = current && draft.isPending) { // GAR
                    Text(text = stringResource(R.string.skill_messages_cancel))
                }
                GarblePrimaryButton(onClick = { draft.send(source) }, enabled = current && draft.isPending) { // GAR
                    Text(text = stringResource(R.string.skill_messages_send))
                }
            }
        }
    }

    companion object {
        /** "Your reply to Mom on Signal says: OK. Ready to send it?" */
        fun readBack(say: Say, draft: ReplyDraft): String = say(
            R.string.skill_messages_read_back,
            listOf(
                draft.conversation.app,
                draft.conversation.name,
                SmsCardOutput.spokenMessage(draft.message),
            ),
        )

        /**
         * What the card says about the reply (formatted with the app's name), or null while it
         * waits and once it was replaced (the new card tells the rest).
         */
        fun statusText(state: ReplyState): Int? = when (state) {
            ReplyState.PENDING, ReplyState.REPLACED, ReplyState.GONE -> null
            ReplyState.SENT -> R.string.skill_messages_sent
            ReplyState.NO_REPLY -> R.string.skill_messages_no_reply_button
            ReplyState.FAILED -> R.string.skill_messages_reply_failed
            ReplyState.NO_ACCESS -> R.string.skill_messages_no_access
            ReplyState.CANCELLED -> R.string.skill_messages_not_sending
            ReplyState.OPENED_APP -> R.string.skill_messages_opened_app
            ReplyState.NO_APP -> R.string.skill_messages_no_app
        }

        /** What GAR says once the reply left the card, by its [ReplyState]. */
        fun speech(say: Say, draft: ReplyDraft): String {
            val conversation = draft.conversation
            return when (draft.state) {
                ReplyState.GONE -> say(R.string.skill_messages_reply_gone, listOf(conversation.name))
                ReplyState.NO_REPLY ->
                    say(R.string.skill_messages_no_reply_button, listOf(conversation.app))
                ReplyState.FAILED -> say(R.string.skill_messages_reply_failed, listOf(conversation.app))
                ReplyState.SENT -> say(R.string.skill_messages_sent, listOf())
                ReplyState.NO_ACCESS -> say(R.string.skill_messages_no_access, listOf())
                ReplyState.OPENED_APP -> say(R.string.skill_messages_opened_app, listOf(conversation.app))
                ReplyState.NO_APP -> say(R.string.skill_messages_no_app, listOf(conversation.app))
                ReplyState.PENDING, ReplyState.CANCELLED, ReplyState.REPLACED ->
                    say(R.string.skill_messages_not_sending, listOf())
            }
        }
    }
}

/**
 * Voice answers to [ReplyCardOutput] while its reply waits: the text card's answers
 * ([SmsCardAnswers.classify]), so a reply is confirmed like a text. Matches nothing once the
 * reply was sent, cancelled or changed.
 */
class ReplyCardAnswerSkill(
    private val flow: MessagesFlow,
    private val draft: ReplyDraft,
) : Skill<SmsCardAnswer?>(MessagesInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, SmsCardAnswer?> {
        val answer = if (draft.isPending) SmsCardAnswers.classify(input) else null
        return Pair(if (answer == null) AlwaysWorstScore else AlwaysBestScore, answer)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: SmsCardAnswer?): SkillOutput {
        return when (inputData) {
            SmsCardAnswer.Send -> {
                draft.send(flow.sourceFor(ctx))
                ReplySentOutput(draft)
            }
            SmsCardAnswer.ReadAgain -> ReplyReadBackOutput(draft)
            SmsCardAnswer.OpenApp -> {
                draft.openApp(flow.sourceFor(ctx))
                ReplySentOutput(draft)
            }
            is SmsCardAnswer.Change -> flow.changeMessage(draft, inputData.message)
            is SmsCardAnswer.Recipient -> flow.changeRecipient(ctx, draft, inputData.spoken)
            // AlwaysWorstScore is never chosen, so null can't happen
            null, SmsCardAnswer.Cancel -> {
                draft.cancel()
                ReplySentOutput(draft)
            }
        }
    }
}

/** The read-back again; the card's skill stays on the stack and the microphone opens again. */
class ReplyReadBackOutput(private val draft: ReplyDraft) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ReplyCardOutput.readBack(ctx.say(), draft)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.Continue(reopenMicrophone = true)
}

/** What happened to the reply: "Sent.", "OK, not sending.", or why it couldn't be sent. */
class ReplySentOutput(val draft: ReplyDraft) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ReplyCardOutput.speech(ctx.say(), draft)
}

/** "What's your reply?", and any answer (except "cancel") is the reply. */
class ReplyAskOutput(
    private val flow: MessagesFlow,
    val conversation: Conversation,
    private val nested: Boolean,
) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_messages_ask_reply_text)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        SmsFlow.plan(nested, listOf(ReplyAnswerSkill(flow, conversation)))

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            Spacer(modifier = Modifier.height(4.dp))
            ConversationBox(conversation)
        }
    }
}

/** Follow-up skill for [ReplyAskOutput]: any words are the reply, "cancel" stops. */
class ReplyAnswerSkill(
    private val flow: MessagesFlow,
    private val conversation: Conversation,
) : Skill<String>(MessagesInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, String> {
        val trimmed = input.trim()
        return Pair(if (trimmed.isEmpty()) AlwaysWorstScore else AlwaysBestScore, trimmed)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput {
        if (isCancelPhrase(inputData)) {
            return MessagesNotSendingOutput()
        }
        return flow.replyTo(conversation, inputData, nested = true)
    }
}

/** "OK, not sending." */
class MessagesNotSendingOutput : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_messages_not_sending)
}

/** "There's no message to reply to." */
class MessagesNothingToReplyOutput : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_messages_nothing_to_reply)
}

/**
 * The chat's notification has no reply button GAR can use: said plainly, with a button that
 * opens the app.
 */
class MessagesNoReplyOutput(val conversation: Conversation) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_messages_no_reply_button, conversation.app)

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        Column {
            Headline(text = getSpeechOutput(ctx))
            Spacer(modifier = Modifier.height(8.dp))
            GarbleSecondaryButton( // GAR
                onClick = { NotificationMessageSource(context).openApp(conversation.packageName) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(text = stringResource(R.string.skill_messages_open_app, conversation.app))
            }
        }
    }
}

/**
 * "There's no message from Mom to reply to. Want to send a text instead?" A yes starts the sms
 * skill's flow with the name and the reply ([TextInsteadSkill]).
 */
class MessagesTextInsteadOutput(
    private val flow: MessagesFlow,
    val name: String,
    val said: String?,
    private val nested: Boolean,
) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_messages_text_instead, name)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        SmsFlow.plan(nested, listOf(TextInsteadSkill(flow, name, said)))
}

/**
 * A yes or no to "Want to send a text instead?". Its info is the sms skill's, so that Dicio asks
 * for the sms skill's permissions (contacts, sending texts) before the text starts.
 */
class TextInsteadSkill(
    private val flow: MessagesFlow,
    private val name: String,
    private val said: String?,
) : Skill<Boolean?>(SmsInfo, Specificity.HIGH) {
    override fun score(ctx: SkillContext, input: String): Pair<Score, Boolean?> {
        val words = normalizeAnswer(input).split(" ").filter { it.isNotEmpty() }
        val answer = if (words.isEmpty() || !words.all { it in ANSWER_WORDS }) {
            null
        } else if (words.indices.any { GarYesNo.isNegation(words, it) }) {
            false
        } else {
            GarYesNo.answer(words, { words[it] in GarYesNo.YES_WORDS }, { words[it] in SEND_WORDS })
        }
        return Pair(if (answer == null) AlwaysWorstScore else AlwaysBestScore, answer)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Boolean?): SkillOutput =
        if (inputData == true) {
            flow.textFlow().withSpokenRecipient(ctx, name, MessageWords.replyMessage(said), nested = true)
        } else {
            MessagesNotSendingOutput()
        }

    companion object {
        private val SEND_WORDS = setOf("send", "text")
        private val ANSWER_WORDS = GarYesNo.YES_WORDS + GarYesNo.NO_WORDS + GarYesNo.NEGATIONS +
            SEND_WORDS + setOf("please", "a", "it", "do", "that", "just", "thanks", "mind", "never")
    }
}
