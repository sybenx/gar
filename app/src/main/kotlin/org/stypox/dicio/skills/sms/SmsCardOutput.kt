package org.stypox.dicio.skills.sms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import org.stypox.dicio.gar.call.showsOutput
import org.stypox.dicio.gar.call.GarYesNo
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

/**
 * The confirmation card: recipient, number and message, with Send, Edit and Cancel buttons. Its
 * speech reads the text back ("Your message to Mom says: I'm late. Ready to send it?") and the
 * microphone opens again, so that a text can be sent without looking: "yes" or "send it" sends
 * (see [SmsCardAnswerSkill]). The buttons work as well. This card is also the confirmation on
 * GAR's path, which is why the `sms_send` tool has `confirm: false`.
 */
class SmsCardOutput(
    private val flow: SmsFlow,
    val draft: SmsDraft,
    private val nested: Boolean,
) : SkillOutput {

    override fun getSpeechOutput(ctx: SkillContext): String = readBack(ctx, draft)

    /** The skills that understand voice answers to the card. */
    fun nextSkills(): List<Skill<*>> = listOf(SmsCardAnswerSkill(flow, draft))

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        SmsFlow.plan(nested, nextSkills())

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        val skillEvaluator = remember {
            EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
                .skillEvaluator()
        }
        // the buttons work while this card's conversation is the latest one, so that "read it
        // again" keeps them, but a new command disables them
        val log by skillEvaluator.state.collectAsState()
        val current = log.interactions.lastOrNull()?.questionsAnswers?.any {
            // GAR wraps outputs run from its own follow-ups (e.g. after its confirmation)
            it.answer.showsOutput(this) // GAR
        } == true
        val sender = remember { flow.senderFor(ctx) }
        org.stypox.dicio.gar.sms.GarSmsConfirmCard( // GAR: the Garble confirm card
            draft = draft,
            enabled = current,
            onSend = { draft.send(sender) },
            onEdit = { draft.edit(sender) },
            onDiscard = { draft.cancel() },
        )
    }

    companion object {
        /**
         * What the card says about the text, or null while it waits for an answer and once the
         * message was changed (the new card below it tells the rest).
         */
        fun statusText(state: SmsState): Int? = when (state) {
            SmsState.PENDING -> null
            SmsState.SENDING -> R.string.skill_sms_sending
            SmsState.SENT -> R.string.skill_sms_sent
            SmsState.FAILED -> R.string.skill_sms_failed
            SmsState.NO_REPORT -> R.string.skill_sms_no_report
            SmsState.OPENED_APP -> R.string.skill_sms_opened_app
            SmsState.NO_SMS_APP -> R.string.skill_sms_no_sms_app
            SmsState.CANCELLED -> R.string.skill_sms_not_sending
            SmsState.REPLACED -> null
        }

        /** "Your message to Mom says: I'm late. Ready to send it?" */
        fun readBack(ctx: SkillContext, draft: SmsDraft): String = ctx.getString(
            R.string.skill_sms_read_back,
            spokenName(draft.recipient),
            spokenMessage(draft.message),
        )

        /**
         * The recipient as the read-back says it: the contact's name, never the number, or a
         * number that is not a contact digit by digit (speech reads "5550100" as five million...).
         */
        fun spokenName(recipient: SmsRecipient): String =
            if (recipient.isContact) {
                recipient.name
            } else {
                recipient.number.filter { it in '0'..'9' }.toList().joinToString(" ")
            }

        /** The message exactly as it will be sent, ended like a sentence for the read-back. */
        fun spokenMessage(message: String): String {
            val text = message.trim()
            return if (text.lastOrNull() in SENTENCE_ENDS) text else "$text."
        }

        private val SENTENCE_ENDS = setOf('.', '!', '?', '…')
    }
}

/** A voice answer to [SmsCardOutput], see [SmsCardAnswers.classify]. */
sealed interface SmsCardAnswer {
    data object Send : SmsCardAnswer
    data object Cancel : SmsCardAnswer
    data object ReadAgain : SmsCardAnswer

    /** Opens the messages app with the text filled in, like the card's Edit button. */
    data object OpenApp : SmsCardAnswer

    /** @param message the new message as said ("change it to ..."), or null to ask for it */
    data class Change(val message: String?) : SmsCardAnswer

    /** "send it to dad instead", "yes, send it to mom": the recipient as said. */
    data class Recipient(val spoken: String) : SmsCardAnswer
}

/**
 * Short voice answers to [SmsCardOutput]'s "Ready to send it?", English only like GAR's other
 * answers. The card's skill stays on top of the stack while the text waits, so it is checked
 * before every other skill: only answers made of [ANSWER_WORDS] count ("yes", "no, you send it",
 * "don't send it", "read it again"), plus a new message ("change it to ...") or recipient ("send
 * it to dad instead"). A new command that merely contains "send", "not" or "change" ("send a text
 * to dad saying hi", "send a text", "change the volume") goes to its own skill, which ends the
 * text's conversation unsent; input that no skill understands leaves the text waiting.
 */
object SmsCardAnswers {
    private val SEND_WORDS = setOf("send", "sent")
    private val YES_WORDS = GarYesNo.YES_WORDS + setOf("correct", "exactly")

    /**
     * Mild no's, which only a following explicit go-ahead overrides: "no, you send it", "no, go
     * ahead", but not "no, it's fine", a polite no.
     */
    private val NO_WORDS = GarYesNo.NO_WORDS

    /** Negations that always keep the text from being sent: "don't send it", "do not send". */
    private val NEGATIONS = GarYesNo.NEGATIONS + setOf("isn't", "isnt")
    private val CHANGE_WORDS = setOf("change", "edit", "fix", "wrong", "different")
    private val NOT_WORDS = setOf("not", "isn't", "isnt")
    private val RIGHT_WORDS = setOf("right", "correct", "good")
    private val GO_ON = setOf("ahead", "for")
    private val LETS = setOf("let's", "lets")
    private val READ_WORDS = setOf("read", "repeat", "again", "what", "what's", "whats", "pardon")

    /** Questions about the text, which never send it: "is that right?", "did you send it?" */
    private val QUESTIONS = setOf("is", "did", "does")

    // no "a": "send a text" or "send a message" starts a new text, it doesn't send this one
    private val ANSWER_WORDS = SEND_WORDS + YES_WORDS + NO_WORDS + NEGATIONS + CHANGE_WORDS +
            READ_WORDS + QUESTIONS + setOf(
        "go", "open", "messages", "app", "in", "it", "it's", "its", "that", "that's", "thats",
        "this", "the", "text", "message", "sms", "i", "want", "to", "let", "let's", "lets",
        "me", "and", "then", "first", "my", "you", "can", "could", "would", "will", "do", "for",
        "say", "says", "back", "mind", "about", "please", "just", "now", "so", "oh", "uh", "um",
        "hmm", "well", "actually", "thanks", "thank", "him", "her", "them", "out", "off", "away",
        "all", "ahead", "sounds", "looks",
    )

    /** What [input] answers, or null if it is not an answer to the card. */
    fun classify(input: String): SmsCardAnswer? {
        newMessage(input)?.let { return SmsCardAnswer.Change(it) }
        newRecipient(input)?.let { return SmsCardAnswer.Recipient(it) }
        val words = words(input) ?: return null
        val negations = words.indices.filter { isNegation(words, it) }
        fun negatedBefore(index: Int) = negations.any { it < index }

        val change = words.indexOfFirst { it in CHANGE_WORDS }
        if (change >= 0) {
            // "no, change it" changes it, "don't change it" leaves it unsent
            return if (negatedBefore(change)) SmsCardAnswer.Cancel else SmsCardAnswer.Change(null)
        }
        // "that's not right", but "not right now" is a no
        if (words.indices.any {
                words[it] in NOT_WORDS && words.getOrNull(it + 1) in RIGHT_WORDS &&
                        words.getOrNull(it + 2) != "now"
            }) {
            return SmsCardAnswer.Change(null)
        }
        val open = words.indexOf("open")
        if (open >= 0) {
            return if (negatedBefore(open)) SmsCardAnswer.Cancel else SmsCardAnswer.OpenApp
        }
        if ("messages" in words) {
            return null // "read my messages" is about the inbox, not this text
        }
        // "read it again", "what does it say", "is that right?", "did you send it?"; before
        // sending, so that "read it again and send it" doesn't send unheard
        if (words[0] in QUESTIONS || words.any { it in READ_WORDS }) {
            return if (negations.isEmpty()) SmsCardAnswer.ReadAgain else SmsCardAnswer.Cancel
        }
        if (negations.isNotEmpty()) {
            return SmsCardAnswer.Cancel // "don't send it", "do not send", "no don't", "stop"
        }

        // an explicit go-ahead: "send it", "do it", "go ahead", "go for it", "let's go", but
        // not "go away", nor "go" alone, which may be a misheard "no"
        val isGoAhead = { index: Int ->
            words[index] in SEND_WORDS ||
                    (words[index] == "do" && words.getOrNull(index + 1) == "it") ||
                    (words[index] == "go" && (words.getOrNull(index + 1) in GO_ON ||
                            words.getOrNull(index - 1) in LETS))
        }
        // after a no, only a later go-ahead sends: "no, you send it", while "no, it's fine", "no
        // go" and "yes... no" decline
        return when (GarYesNo.answer(words, { words[it] in YES_WORDS }, isGoAhead)) {
            true -> SmsCardAnswer.Send
            false -> SmsCardAnswer.Cancel
            null -> null
        }
    }

    /** "never" is a negation, but not in "never mind", which is a mild no. */
    private fun isNegation(words: List<String>, index: Int): Boolean =
        GarYesNo.isNegation(words, index, NEGATIONS)

    /** The words of [input], or null if it is not a short answer to the card. */
    private fun words(input: String): List<String>? =
        normalizeAnswer(input.replace('’', '\'')).split(" ").filter { it.isNotEmpty() }
            .takeIf { words -> words.isNotEmpty() && words.all { it in ANSWER_WORDS } }

    /**
     * The new message in "change it to see you soon", "make it say I'm late", "say hi instead",
     * as said (with "instead" left out), or null if [input] doesn't give one.
     */
    fun newMessage(input: String): String? {
        val text = input.replace('’', '\'')
        val said = NEW_MESSAGE_PATTERNS.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
            ?: return null
        val message = said.replace(TRAILING_INSTEAD, "").trim().trim('"', '“', '”').trim()
        return message.takeIf { normalizeAnswer(it) !in NOT_A_MESSAGE }
    }

    // a separator between words: anything but letters, digits and apostrophes
    private const val SEP = "(?:[^\\p{L}\\p{N}']+)"

    // words said before the request itself: "no, change it to ...", "can you make it say ..."
    private const val LEAD = "(?:(?:no|nope|nah|oh|actually|wait|um|uh|well|please|ok|okay|" +
            "hmm|so|yes|yeah|can|could|would|you|just|i|want|to|let|me)$SEP)*"
    private const val TARGET = "(?:it|that|this|(?:the|my)$SEP(?:text${SEP}message|message|text))"

    private val NEW_MESSAGE_PATTERNS = listOf(
        "(?:change|edit|fix|switch)$SEP$TARGET${SEP}to$SEP(?:(?:say|read)$SEP)?(.+)",
        "(?:change|edit|fix)$SEP$TARGET${SEP}so${SEP}it$SEP(?:says|reads)$SEP(.+)",
        "(?:make|have)$SEP$TARGET$SEP(?:say|read)$SEP(.+)",
        "$TARGET${SEP}should$SEP(?:say|read)$SEP(.+)",
        "replace$SEP$TARGET${SEP}with$SEP(.+)",
        "instead$SEP(?:say|write)$SEP(.+)",
        "(?:say|write)$SEP(.+?)${SEP}instead",
    ).map { Regex("^$SEP?$LEAD$it$SEP?$", RegexOption.IGNORE_CASE) }

    private val TRAILING_INSTEAD = Regex("${SEP}instead$SEP?$", RegexOption.IGNORE_CASE)

    /** What is left of "change it to say" and the like, which is no message. */
    private val NOT_A_MESSAGE = setOf("", "say", "read", "instead")

    /**
     * The recipient in "send it to dad instead", "yes, send it to mom", "text that to sarah lee",
     * as said, or null if [input] doesn't name one. "send it to him" is a plain yes.
     */
    fun newRecipient(input: String): String? {
        val said = NEW_RECIPIENT.find(input.replace('’', '\''))?.groupValues?.get(1) ?: return null
        return said.trim().takeIf { normalizeAnswer(it) !in NOT_A_RECIPIENT }
    }

    private val NEW_RECIPIENT = Regex(
        "^$SEP?$LEAD(?:send|text)$SEP$TARGET${SEP}to$SEP(.+?)(?:$SEP(?:instead|please|now))*$SEP?$",
        RegexOption.IGNORE_CASE,
    )

    private val NOT_A_RECIPIENT = setOf("", "him", "her", "them", "instead", "please", "now")
}

/**
 * Voice answers to [SmsCardOutput] while its text waits, see [SmsCardAnswers]. Matches nothing
 * once the text was sent, cancelled or changed, so later inputs go to other skills.
 */
class SmsCardAnswerSkill(
    private val flow: SmsFlow,
    private val draft: SmsDraft,
) : Skill<SmsCardAnswer?>(SmsInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, SmsCardAnswer?> {
        val answer = if (draft.isPending) SmsCardAnswers.classify(input) else null
        return Pair(if (answer == null) AlwaysWorstScore else AlwaysBestScore, answer)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: SmsCardAnswer?): SkillOutput {
        return when (inputData) {
            SmsCardAnswer.Send ->
                SmsSendOutput(draft.sendAndWait(flow.senderFor(ctx), flow.sendTimeoutMs))
            SmsCardAnswer.ReadAgain -> SmsReadBackOutput(draft)
            SmsCardAnswer.OpenApp -> {
                draft.edit(flow.senderFor(ctx))
                SmsStatusOutput(draft.state)
            }
            is SmsCardAnswer.Change -> flow.changeMessage(draft, inputData.message)
            is SmsCardAnswer.Recipient -> flow.changeRecipient(ctx, draft, inputData.spoken)
            // AlwaysWorstScore is never chosen, so null can't happen
            null, SmsCardAnswer.Cancel -> {
                draft.cancel()
                SmsNotSendingOutput()
            }
        }
    }
}

/** The read-back again. The card's skill stays on the stack and the microphone opens again. */
class SmsReadBackOutput(private val draft: SmsDraft) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = SmsCardOutput.readBack(ctx, draft)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.Continue(reopenMicrophone = true)
}

/** What happened after a spoken "send it": "Sent.", "The text didn't go through.", ... */
class SmsSendOutput(val state: SmsState) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(speech(state))

    companion object {
        fun speech(state: SmsState): Int = when (state) {
            SmsState.SENT -> R.string.skill_sms_sent
            SmsState.SENDING -> R.string.skill_sms_still_sending
            SmsState.FAILED -> R.string.skill_sms_not_through
            SmsState.NO_REPORT -> R.string.skill_sms_no_report
            // GAR may not send texts (SEND_SMS revoked, phone locked) or sending didn't start
            SmsState.OPENED_APP -> R.string.skill_sms_opened_app_instead
            SmsState.NO_SMS_APP -> R.string.skill_sms_no_sms_app_instead
            // sendAndWait never leaves the text waiting, nor cancels or replaces it
            SmsState.PENDING, SmsState.CANCELLED, SmsState.REPLACED -> R.string.skill_sms_not_sending
        }
    }
}

/** What happened to the text after "open it in messages": "Opened in your messages app." */
class SmsStatusOutput(private val state: SmsState) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        SmsCardOutput.statusText(state)?.let { ctx.getString(it) } ?: ""
}
