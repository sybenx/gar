package org.stypox.dicio.gar.offer

import androidx.annotation.StringRes
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
import dagger.hilt.android.EntryPointAccessors
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.call.GarCancelledOutput
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.util.RecognizeYesNoSkill
import org.stypox.dicio.util.getString

/**
 * GAR (gar:places, gar:screen). An answer that ends in an offer: "Open it in the map?",
 * "Taking screenshots needs GAR's screenshot service. Open Accessibility settings?". A yes by voice
 * or with the [yesLabel] button runs [onYes]; a no or the [noLabel] button says "OK.", like the
 * device settings' access card (`DeviceSettingsAccessOutput`). The buttons send their label as
 * input, so both ways go through [GarOfferAnswerSkill].
 *
 * @param speech what is said and shown as the headline, ending in the question
 * @param details what only the card shows, under the headline
 * @param yesLabel the yes button's label, e.g. "Open settings"
 * @param noLabel the no button's label, e.g. "Not now"
 * @param nested true when this answers one of the skill's own follow-ups (it replaces their batch)
 * @param onNo what a no does instead of "OK." (gar:places: the card's address declined asks for one)
 */
class GarOfferOutput(
    val info: SkillInfo,
    private val speech: (SkillContext) -> String,
    private val details: (SkillContext) -> List<String> = { listOf() },
    @StringRes val yesLabel: Int,
    @StringRes val noLabel: Int,
    private val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>?,
    private val nested: Boolean = false,
    val onNo: (suspend (SkillContext) -> SkillOutput)? = null,
    val onYes: suspend (SkillContext) -> SkillOutput,
) : SkillOutput {

    /** Set once the user answered, to disable the buttons. */
    var answered by mutableStateOf(false)

    override fun getSpeechOutput(ctx: SkillContext): String = speech(ctx)

    fun nextSkills(): List<Skill<*>> = listOfNotNull(
        yesNoData?.let { GarOfferAnswerSkill(this, it) },
        GarCancelSkill(info) { answered = true },
    )

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan = if (nested) {
        InteractionPlan.ReplaceSubInteraction(reopenMicrophone = true, nextSkills = nextSkills())
    } else {
        InteractionPlan.StartSubInteraction(reopenMicrophone = true, nextSkills = nextSkills())
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
        val enabled = isLatest && !answered && yesNoData != null
        val yes = stringResource(yesLabel)
        val no = stringResource(noLabel)
        val send = { label: String ->
            answered = true
            skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(label, 1.0f))))
        }

        Column {
            Headline(text = speech(ctx))
            for (detail in details(ctx)) {
                Spacer(modifier = Modifier.height(8.dp))
                Body(text = detail)
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
}

/** The answer to a [GarOfferOutput]: Dicio's yes/no sentences, or a button's label. */
class GarOfferAnswerSkill(
    private val offer: GarOfferOutput,
    data: StandardRecognizerData<Sentences.UtilYesNo>,
) : RecognizeYesNoSkill(offer.info, data) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Boolean> =
        when (normalizeAnswer(input)) {
            normalizeAnswer(ctx.getString(offer.yesLabel)) -> Pair(AlwaysBestScore, true)
            normalizeAnswer(ctx.getString(offer.noLabel)) -> Pair(AlwaysBestScore, false)
            else -> super.score(ctx, input)
        }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Boolean): SkillOutput {
        offer.answered = true
        return when {
            inputData -> GarFollowUpOutput(offer.onYes(ctx))
            else -> offer.onNo?.let { GarFollowUpOutput(it(ctx)) } ?: GarCancelledOutput()
        }
    }
}
