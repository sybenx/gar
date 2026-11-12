package org.stypox.dicio.gar.call

import org.stypox.dicio.gar.ui.garble.GarblePrimaryButton // GAR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import dagger.hilt.android.EntryPointAccessors
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.util.RecognizeYesNoSkill
import org.stypox.dicio.util.getString
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR

/**
 * Asks "<tool description>: <slot values>?" before running a call the model made. The user
 * answers by voice (yes/no) or with the card's buttons, which send their label as input to the
 * [org.stypox.dicio.eval.SkillEvaluator], so both go through [GarConfirmSkill].
 *
 * @param nested see [GarCallRunner.next]
 */
class GarConfirmOutput(
    val runner: GarCallRunner,
    val call: GarCall,
    private val nested: Boolean,
) : SkillOutput {

    /** Set once the user answered, to disable the buttons. */
    var answered by mutableStateOf(false)

    override fun getSpeechOutput(ctx: SkillContext): String = runner.summary(call, speech = true)

    /** The skills that wait for the answer; [yesLabel] and [noLabel] are the buttons' labels. */
    fun nextSkills(yesLabel: String, noLabel: String): List<Skill<*>> = listOf(
        GarConfirmSkill(
            runner = runner,
            call = call,
            data = checkNotNull(runner.yesNoData) { "No yes/no sentences" },
            yesLabel = yesLabel,
            noLabel = noLabel,
            onAnswered = { answered = true },
        ),
        GarCancelSkill(runner.targetInfo) { answered = true },
    )

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan {
        val skills = nextSkills(ctx.getString(R.string.gar_yes), ctx.getString(R.string.gar_no))
        return if (nested) {
            InteractionPlan.ReplaceSubInteraction(reopenMicrophone = true, nextSkills = skills)
        } else {
            InteractionPlan.StartSubInteraction(reopenMicrophone = true, nextSkills = skills)
        }
    }

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
            Headline(text = runner.summary(call, speech = false))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GarbleSecondaryButton(onClick = { send(no) }, enabled = enabled) { // GAR
                    Text(text = no)
                }
                GarblePrimaryButton(onClick = { send(yes) }, enabled = enabled) { // GAR
                    Text(text = yes)
                }
            }
        }
    }
}

/**
 * Follow-up skill for a [GarConfirmOutput]: voice yes/no through Dicio's `util_yes_no` sentences,
 * plus the exact button labels. On yes it runs the target skill and returns its output.
 */
class GarConfirmSkill(
    private val runner: GarCallRunner,
    private val call: GarCall,
    data: StandardRecognizerData<Sentences.UtilYesNo>,
    private val yesLabel: String,
    private val noLabel: String,
    private val onAnswered: () -> Unit = {},
) : RecognizeYesNoSkill(runner.targetInfo, data) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Boolean> {
        val normalized = normalizeAnswer(input)
        return when (normalized) {
            normalizeAnswer(yesLabel) -> Pair(AlwaysBestScore, true)
            normalizeAnswer(noLabel) -> Pair(AlwaysBestScore, false)
            else -> super.score(ctx, input)
        }
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Boolean): SkillOutput {
        onAnswered()
        return if (inputData) {
            runner.run(ctx, call, fromFollowUp = true)
        } else {
            GarCancelledOutput()
        }
    }
}
