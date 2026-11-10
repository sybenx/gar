package org.stypox.dicio.gar.followup

import android.app.KeyguardManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.aaro.gar.router.followup.FollowUpForm
import cc.aaro.gar.router.followup.FollowUpForms
import dagger.hilt.android.EntryPointAccessors
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.FloatScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.GarFallbackImpl
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.call.GarCancelledOutput
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.conversation.GarEndsConversation
import org.stypox.dicio.gar.conversation.GarOpenAnswer
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.gar.conversation.GarTurnElement
import org.stypox.dicio.gar.media.GarMediaSessions
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.calls.AndroidPhoneCalls
import org.stypox.dicio.skills.calls.PhoneCallState
import org.stypox.dicio.util.getString
import java.time.LocalDateTime

/**
 * "Follow-ups" (gar/DESIGN.md, "Follow-ups"): "turn it off", "what about tomorrow", "call her",
 * "no, 8 pm". English only; no sentences and no tool, so the model's prompt stays as it is. First
 * in Dicio's list of skills.
 */
object GarFollowUpInfo : SkillInfo("follow_up") {
    override fun name(context: Context) = context.getString(R.string.skill_name_follow_up)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_follow_up)

    @Composable
    override fun icon() = rememberVectorPainter(Icons.Default.History)

    override fun build(ctx: SkillContext): Skill<*>? =
        if (ctx.sentencesLanguage == GarFallbackImpl.ENGLISH) GarFollowUpSkill(GarFollowUpInfo) else null
}

/** What GAR asks when a follow-up fits no remembered turn. */
enum class GarFollowUpQuestion(@StringRes val text: Int) {
    TURN_WHAT_OFF(R.string.gar_follow_up_turn_what_off),
    TURN_WHAT_ON(R.string.gar_follow_up_turn_what_on),
    TURN_WHAT_UP(R.string.gar_follow_up_turn_what_up),
    TURN_WHAT_DOWN(R.string.gar_follow_up_turn_what_down),
    CHANGE_WHAT(R.string.gar_follow_up_change_what),
    ADD_TIME_TO_WHAT(R.string.gar_follow_up_add_time_to_what),
    WHO_CALL(R.string.gar_follow_up_who_call),
    WHO_TEXT(R.string.gar_follow_up_who_text),
    WHO_EMAIL(R.string.gar_follow_up_who_email),
    WHOSE_NUMBER(R.string.gar_follow_up_whose_number),
    WHOSE_ADDRESS(R.string.gar_follow_up_whose_address),
    WHERE_TO(R.string.gar_follow_up_where_to),
}

/** What GAR does with a follow-up; [logName] is the voice log's `outcome`. */
sealed interface GarFollowUpOutcome {
    val logName: String

    /** The full command, matched again by `garMatchUtterances`. */
    data class Rewritten(val text: String) : GarFollowUpOutcome {
        override val logName: String get() = "rewritten"
    }

    /** The target's own match, returned as is by `garMatchUtterances`. */
    data class Direct(val text: String, val match: SkillWithResult<*>) : GarFollowUpOutcome {
        override val logName: String get() = "direct"
    }

    data class Say(val speech: String) : GarFollowUpOutcome {
        override val logName: String get() = "said"
    }

    /** "Turn what off?": the answer fills [template]'s `{answer}`. */
    data class Ask(val question: GarFollowUpQuestion, val template: String) : GarFollowUpOutcome {
        override val logName: String get() = "asked"
    }

    /** A continued turn's destructive or outbound follow-up: [rewrite] runs on yes. */
    data class Confirm(val question: String, val rewrite: GarRewrite) : GarFollowUpOutcome {
        override val logName: String get() = "confirm"
    }

    data object Locked : GarFollowUpOutcome {
        override val logName: String get() = "locked"
    }

    /** No to [Confirm]: nothing runs. */
    data object Declined : GarFollowUpOutcome {
        override val logName: String get() = "declined"
    }

    /** Not GAR's to answer: the follow-up skill scores worst; [to] says why. */
    data class Deferred(val to: String) : GarFollowUpOutcome {
        override val logName: String get() = "deferred"
    }

    companion object {
        /** [rewrite] as an outcome, asked about first when [confirm]. */
        fun of(rewrite: GarRewrite, confirm: Boolean): GarFollowUpOutcome = when (rewrite) {
            is GarRewrite.Say -> Say(rewrite.speech)
            is GarRewrite.Command -> if (confirm) Confirm(question(rewrite.text), rewrite) else Rewritten(rewrite.text)
            is GarRewrite.Direct ->
                if (confirm) Confirm(question(rewrite.text), rewrite) else Direct(rewrite.text, rewrite.match)
        }

        /** "cancel my 7 am alarm" -> "Cancel your 7 am alarm?" */
        fun question(command: String): String =
            command.replace(Regex("\\bmy\\b"), "your").replaceFirstChar { it.uppercaseChar() } + "?"
    }
}

/**
 * A follow-up as read by [GarFollowUpSkill] and its answer and confirmation skills: the [form]
 * said, what GAR does with it, the remembered [turn] it is about (for the voice log), and whether
 * it wins over the conversation's "cancel it" ([alwaysBest]).
 */
data class GarFollowUpMatch(
    val form: FollowUpForm,
    val outcome: GarFollowUpOutcome,
    val turn: GarRememberedTurn?,
    val alwaysBest: Boolean = false,
)

/** The follow-up skill and its answer and confirmation skills, whose matches `garMatchUtterances` runs. */
interface GarFollowUpStep

/** The follow-up skill's own outputs, which the memory never remembers. */
interface GarFollowUpOwnOutput : SkillOutput

/**
 * Reads a follow-up form ([FollowUpForms]) and asks the remembered turns' adapters to rewrite it
 * ([GarFollowUpReach]). It scores [SCORE], below a whole sentence of any later skill and below
 * any later [AlwaysBestScore], so a skill that reads the words itself ("call her back", "play it
 * again", a ringing timer's "turn it off", the conversation's "cancel it") keeps them; only
 * "cancel it" right after a clock was set scores the best ([GarFollowUpMatch.alwaysBest]). It
 * scores worst while something rings, during a phone call, while a rewrite is matched and when the
 * form is someone else's ([GarFollowUpOutcome.Deferred]).
 */
class GarFollowUpSkill(
    correspondingSkillInfo: SkillInfo,
    private val view: (locked: Boolean) -> GarMemoryView = GarConversationMemory::view,
    private val isLocked: (SkillContext) -> Boolean = { isDeviceLocked(it) },
    private val mediaPlaying: () -> Boolean = GarMediaSessions::isPlaying,
    private val callState: (SkillContext) -> PhoneCallState = { phoneCallState(it) },
    private val ringingTakes: (SkillContext, String) -> Boolean = GarRinging::takes,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    /** The default batch's skills by id, for [GarRewrite.Direct] (places, phase 2). */
    private val skills: (String) -> Skill<*>? = { null },
) : Skill<GarFollowUpMatch?>(correspondingSkillInfo, Specificity.HIGH), GarFollowUpStep {

    override fun score(ctx: SkillContext, input: String): Pair<Score, GarFollowUpMatch?> {
        if (rewritePass) {
            return Pair(AlwaysWorstScore, null)
        }
        val form = FollowUpForms.parse(input) ?: return Pair(AlwaysWorstScore, null)
        val element = turn
        val match = when {
            // a ringing timer or alarm takes "turn it off" and "snooze it"
            ringingTakes(ctx, input) -> GarFollowUpMatch(form, GarFollowUpOutcome.Deferred("ringing"), null)
            callState(ctx) in CALL_STATES -> GarFollowUpMatch(form, GarFollowUpOutcome.Deferred("call"), null)
            else -> {
                val locked = isLocked(ctx)
                GarFollowUpReach.resolve(
                    form,
                    FollowUpContext(now(), mediaPlaying(), element?.continued == true, view(locked), skills),
                    locked,
                    startedWhileSpeaking = element?.startedWhileSpeaking == true,
                )
            }
        }
        if (scored == null) {
            scored = Pair(input, match) // the first alternative is scored first
        }
        val score = when {
            match.outcome is GarFollowUpOutcome.Deferred -> AlwaysWorstScore
            match.alwaysBest -> AlwaysBestScore
            else -> FloatScore(SCORE)
        }
        return Pair(score, match)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: GarFollowUpMatch?): SkillOutput =
        outputOf(correspondingSkillInfo, checkNotNull(inputData) { "a worst score is never chosen" })

    private class FollowUpContext(
        override val now: LocalDateTime,
        override val mediaPlaying: Boolean,
        override val continued: Boolean,
        override val memory: GarMemoryView,
        private val skills: (String) -> Skill<*>?,
    ) : GarFollowUpContext {
        override fun skill(id: String): Skill<*>? = skills(id)
    }

    companion object {
        /** Below a whole sentence ([org.stypox.dicio.gar.conversation.GarFollowUps.COMMAND_SCORE]). */
        const val SCORE = 0.94f

        private val CALL_STATES = setOf(PhoneCallState.RINGING, PhoneCallState.WAITING, PhoneCallState.IN_CALL)

        /**
         * Set while `garMatchUtterances` matches a rewrite (under the evaluator's match lock): the
         * follow-up, answer and confirmation skills score worst then.
         */
        @Volatile
        var rewritePass: Boolean = false

        /**
         * The turn being matched, set by `garMatchUtterances` under the match lock, since
         * [score] can't suspend to read it from the coroutine.
         */
        @Volatile
        var turn: GarTurnElement? = null

        /**
         * The first input this skill scored since `garMatchUtterances` reset it, and its match,
         * for the voice log's deferrals.
         */
        @Volatile
        var scored: Pair<String, GarFollowUpMatch>? = null

        /** The output of a follow-up [match] that `garMatchUtterances` doesn't match again. */
        fun outputOf(info: SkillInfo, match: GarFollowUpMatch): SkillOutput = when (val outcome = match.outcome) {
            is GarFollowUpOutcome.Say -> GarFollowUpSayOutput(outcome.speech)
            is GarFollowUpOutcome.Ask -> GarFollowUpAskOutput(info, outcome.question, outcome.template, match.form)
            is GarFollowUpOutcome.Confirm -> GarFollowUpConfirmOutput(info, outcome.question, outcome.rewrite, match)
            GarFollowUpOutcome.Locked -> GarFollowUpLockedOutput()
            GarFollowUpOutcome.Declined -> GarCancelledOutput()
            is GarFollowUpOutcome.Rewritten, is GarFollowUpOutcome.Direct, is GarFollowUpOutcome.Deferred ->
                throw IllegalStateException("garMatchUtterances matches ${outcome.logName} follow-ups again")
        }

        private fun isDeviceLocked(ctx: SkillContext): Boolean = try {
            ctx.android.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        } catch (e: Exception) {
            false
        }

        private fun phoneCallState(ctx: SkillContext): PhoneCallState = try {
            AndroidPhoneCalls(ctx.android).state()
        } catch (e: Exception) {
            PhoneCallState.UNKNOWN
        }
    }
}

/** "That's ABBA." */
class GarFollowUpSayOutput(val speech: String) : HeadlineSpeechSkillOutput, GarFollowUpOwnOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = speech
}

/** "Unlock your phone first.", for a follow-up about something that stays behind the lock. */
class GarFollowUpLockedOutput : HeadlineSpeechSkillOutput, GarFollowUpOwnOutput, GarEndsConversation {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_unlock_first)
}

/**
 * "Turn what off?" Its answer ("the flashlight") fills the template ("turn off {answer}") and is
 * matched like a new command; "cancel" drops the question.
 */
class GarFollowUpAskOutput(
    private val info: SkillInfo,
    val question: GarFollowUpQuestion,
    val template: String,
    private val form: FollowUpForm,
) : HeadlineSpeechSkillOutput, GarFollowUpOwnOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(question.text)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = listOf(GarFollowUpAnswerSkill(info, template, form), GarCancelSkill(info)),
        )
}

/**
 * The answer to a [GarFollowUpAskOutput]: any words, which give way to a whole sentence of
 * Dicio's default batch ("call mom" in reply to "Who should I text?" is a call).
 */
class GarFollowUpAnswerSkill(
    info: SkillInfo,
    val template: String,
    private val form: FollowUpForm,
) : Skill<GarFollowUpMatch?>(info, Specificity.HIGH), GarFollowUpStep, GarOpenAnswer {

    override fun score(ctx: SkillContext, input: String): Pair<Score, GarFollowUpMatch?> {
        val answer = normalizeAnswer(input)
        if (GarFollowUpSkill.rewritePass || answer.isEmpty() || isCancelPhrase(input) ||
            GarRinging.takes(ctx, input)
        ) {
            return Pair(AlwaysWorstScore, null)
        }
        val command = template.replace(ANSWER, answer)
        return Pair(AlwaysBestScore, GarFollowUpMatch(form, GarFollowUpOutcome.Rewritten(command), null))
    }

    override fun yieldsTo(command: SkillWithResult<*>): Boolean = true

    override suspend fun generateOutput(ctx: SkillContext, inputData: GarFollowUpMatch?): SkillOutput =
        throw IllegalStateException("garMatchUtterances matches the answer's rewrite instead")

    companion object {
        const val ANSWER = "{answer}"
    }
}

/**
 * "Cancel your 7 am alarm?", asked before a destructive or outbound follow-up of a continued
 * turn runs. The user answers by voice (yes/no) or with the card's buttons.
 */
class GarFollowUpConfirmOutput(
    private val info: SkillInfo,
    val question: String,
    val rewrite: GarRewrite,
    private val match: GarFollowUpMatch,
) : GarFollowUpOwnOutput {

    /** Set once the user answered, to disable the buttons. */
    var answered by mutableStateOf(false)

    override fun getSpeechOutput(ctx: SkillContext): String = question

    /** The skills that wait for the answer; [yesLabel] and [noLabel] are the buttons' labels. */
    fun nextSkills(
        data: StandardRecognizerData<Sentences.UtilYesNo>?,
        yesLabel: String,
        noLabel: String,
    ): List<Skill<*>> = listOfNotNull(
        data?.let {
            GarFollowUpConfirmSkill(info, it, rewrite, match, yesLabel, noLabel) { answered = true }
        },
        GarCancelSkill(info) { answered = true },
    )

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan = InteractionPlan.StartSubInteraction(
        reopenMicrophone = true,
        nextSkills = nextSkills(
            Sentences.UtilYesNo[ctx.sentencesLanguage],
            ctx.getString(R.string.gar_yes),
            ctx.getString(R.string.gar_no),
        ),
    )

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        val skillEvaluator = remember {
            EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
                .skillEvaluator()
        }
        // the buttons only make sense while this card is the latest answer
        val log by skillEvaluator.state.collectAsState()
        val isLatest = log.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer === this
        val enabled = isLatest && !answered

        val yes = stringResource(R.string.gar_yes)
        val no = stringResource(R.string.gar_no)
        val send = { label: String ->
            answered = true
            skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(label, 1.0f))))
        }

        Column {
            Headline(text = question)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = { send(no) }, enabled = enabled) {
                    Text(text = no)
                }
                Button(onClick = { send(yes) }, enabled = enabled) {
                    Text(text = yes)
                }
            }
        }
    }
}

/**
 * The yes or no to a [GarFollowUpConfirmOutput], through Dicio's `util_yes_no` sentences and the
 * buttons' labels: yes runs [rewrite] like any follow-up ([GarFollowUpOutcome.of]), no says "OK.".
 */
class GarFollowUpConfirmSkill(
    info: SkillInfo,
    private val data: StandardRecognizerData<Sentences.UtilYesNo>,
    val rewrite: GarRewrite,
    private val match: GarFollowUpMatch,
    private val yesLabel: String,
    private val noLabel: String,
    private val onAnswered: () -> Unit = {},
) : Skill<GarFollowUpMatch?>(info, data.specificity), GarFollowUpStep {

    override fun score(ctx: SkillContext, input: String): Pair<Score, GarFollowUpMatch?> {
        if (GarFollowUpSkill.rewritePass) {
            return Pair(AlwaysWorstScore, null)
        }
        val (score, yes) = when (normalizeAnswer(input)) {
            normalizeAnswer(yesLabel) -> Pair(AlwaysBestScore, true)
            normalizeAnswer(noLabel) -> Pair(AlwaysBestScore, false)
            else -> data.score(ctx, input).let { (score, sentence) -> Pair(score, sentence is Sentences.UtilYesNo.Yes) }
        }
        val outcome = if (yes) GarFollowUpOutcome.of(rewrite, confirm = false) else GarFollowUpOutcome.Declined
        return Pair(score, match.copy(outcome = outcome, alwaysBest = false))
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: GarFollowUpMatch?): SkillOutput {
        onAnswered()
        return GarFollowUpSkill.outputOf(correspondingSkillInfo, checkNotNull(inputData))
    }
}
