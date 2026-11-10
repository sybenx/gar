package org.stypox.dicio.gar.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.Resolution
import dagger.hilt.android.EntryPointAccessors
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.call.GarCancelledOutput
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.call.parseOptionIndex
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.util.RecognizeYesNoSkill
import org.stypox.dicio.util.getString

/**
 * gar:notes. A question GAR asks before it changes something the user can't easily get back
 * (moving or deleting an event, cancelling several reminders, clearing a list): [question] is
 * spoken and shown with [details] below it, answered by voice (Dicio's `util_yes_no`) or with the
 * Yes and No buttons, which send their label as input like GAR's confirmation card. Yes runs
 * [onYes]; no and "cancel" say "OK.".
 */
class GarYesNoOutput(
    private val info: SkillInfo,
    private val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
    private val question: (SkillContext) -> String,
    private val details: (SkillContext) -> List<String> = { listOf() },
    private val onYes: suspend (SkillContext) -> SkillOutput,
) : SkillOutput {

    /** Set once the user answered, to disable the buttons. */
    var answered by mutableStateOf(false)
        private set

    override fun getSpeechOutput(ctx: SkillContext): String = question(ctx)

    /** The skills that wait for the answer; [yesLabel] and [noLabel] are the buttons' labels. */
    fun nextSkills(yesLabel: String, noLabel: String): List<Skill<*>> = listOf(
        AnswerSkill(this, yesLabel, noLabel),
        GarCancelSkill(info) { answered = true },
    )

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = nextSkills(ctx.getString(R.string.gar_yes), ctx.getString(R.string.gar_no)),
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
        val latest = log.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer
        val enabled = isLatest(latest, this) && !answered

        val yes = stringResource(R.string.gar_yes)
        val no = stringResource(R.string.gar_no)
        val send = { label: String ->
            answered = true
            skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(label, 1.0f))))
        }

        Column {
            Headline(text = question(ctx))
            for (line in details(ctx)) {
                Body(text = line)
            }
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

    /** Voice yes/no, plus the exact button labels. */
    private class AnswerSkill(
        private val output: GarYesNoOutput,
        private val yesLabel: String,
        private val noLabel: String,
    ) : RecognizeYesNoSkill(output.info, output.yesNoData) {

        override fun score(ctx: SkillContext, input: String): Pair<Score, Boolean> =
            when (normalizeAnswer(input)) {
                normalizeAnswer(yesLabel) -> Pair(AlwaysBestScore, true)
                normalizeAnswer(noLabel) -> Pair(AlwaysBestScore, false)
                else -> super.score(ctx, input)
            }

        override suspend fun generateOutput(ctx: SkillContext, inputData: Boolean): SkillOutput {
            output.answered = true
            return if (inputData) {
                // the answer ends the question: a sub-interaction the result starts replaces it
                GarFollowUpOutput(output.onYes(ctx))
            } else {
                GarCancelledOutput()
            }
        }
    }

    /** Runs the yes branch directly, for tests. */
    suspend fun answerYes(ctx: SkillContext): SkillOutput {
        answered = true
        return onYes(ctx)
    }
}

/**
 * gar:notes. "Which one?" among a few [options] (events, reminders, contacts): spoken with the
 * options' [spoken] names, shown as a numbered list, answered by position ("the second one",
 * "last") or by saying one of the names ([NameMatcher]). "Cancel" says "OK."; anything else is no
 * answer and goes on to Dicio's default batch.
 */
class GarPickOutput<T>(
    private val info: SkillInfo,
    val options: List<T>,
    private val question: (SkillContext) -> String,
    private val shown: (SkillContext, T) -> String,
    private val spoken: (SkillContext, T) -> String = shown,
    /** The words a name answer is compared with, e.g. an event's title. */
    private val nameOf: (T) -> String,
    private val onPick: suspend (SkillContext, T) -> SkillOutput,
) : SkillOutput {

    override fun getSpeechOutput(ctx: SkillContext): String = question(ctx)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = listOf(PickSkill(this), GarCancelSkill(info)),
        )

    /** The option [input] picks, or null when it picks none. */
    fun pick(input: String): T? {
        if (isCancelPhrase(input) || normalizeAnswer(input).isEmpty()) {
            return null
        }
        parseOptionIndex(input, options.size)?.let { return options[it] }
        return when (val match = NameMatcher.match(input, options, nameOf)) {
            is Resolution.Resolved -> match.value
            else -> null
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = question(ctx))
            options.forEachIndexed { i, option ->
                Body(text = "${i + 1}. ${shown(ctx, option)}")
            }
        }
    }

    /** "The first one", "number two" or a name. */
    private class PickSkill<T>(private val output: GarPickOutput<T>) :
        Skill<T?>(output.info, Specificity.HIGH) {

        override fun score(ctx: SkillContext, input: String): Pair<Score, T?> {
            val picked = output.pick(input)
            return Pair(if (picked == null) AlwaysWorstScore else AlwaysBestScore, picked)
        }

        override suspend fun generateOutput(ctx: SkillContext, inputData: T?): SkillOutput =
            // AlwaysWorstScore is never chosen, so inputData isn't null here
            GarFollowUpOutput(output.onPick(ctx, inputData!!))
    }

    /** Runs the pick of [option] directly, for tests. */
    suspend fun choose(ctx: SkillContext, option: T): SkillOutput = onPick(ctx, option)

    /** The spoken name of [option], e.g. for a question that lists them. */
    fun spokenName(ctx: SkillContext, option: T): String = spoken(ctx, option)
}

/**
 * Whether [logged], an answer in the interaction log, is [output]. After a GAR question the log
 * holds the output wrapped in a [GarFollowUpOutput].
 */
internal fun isLatest(logged: SkillOutput?, output: SkillOutput): Boolean =
    logged === output || (logged as? GarFollowUpOutput)?.output === output
