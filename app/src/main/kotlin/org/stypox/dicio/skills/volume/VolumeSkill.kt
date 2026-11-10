package org.stypox.dicio.skills.volume

import android.media.AudioManager
import androidx.core.content.getSystemService
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.gar.call.DefaultGarSlotResolver
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarCallRunner
import org.stypox.dicio.gar.call.GarSlotResolver
import org.stypox.dicio.gar.call.GarToolSkill
import org.stypox.dicio.gar.call.GarValueFormatter
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Volume

/**
 * Volume up/down (by steps), to a level, mute, unmute and max. Based on the volume sentences of
 * DicioTeam/dicio-android#384, as a skill of its own so that Dicio's media skill stays as upstream.
 *
 * Dicio's matches keep their numbers as said ([SpokenVolume]) and go through GAR's slot
 * resolution, which parses spoken numbers and percentages and asks when it can't; the model's
 * calls of the LLM-only `volume` and `volume_set` tools arrive resolved. Both end in a
 * [VolumeCommand].
 */
class VolumeSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Volume>,
    private val resolverFactory: (SkillContext) -> GarSlotResolver = DefaultGarSlotResolver::forContext,
    private val formatterFactory: (SkillContext) -> GarValueFormatter = GarValueFormatter::forContext,
    private val controlFactory: (SkillContext) -> VolumeControl? = { ctx ->
        ctx.android.getSystemService<AudioManager>()?.let { AndroidVolumeControl(it) }
    },
) : Skill<VolumeInput>(correspondingSkillInfo, data.specificity), GarToolSkill {

    override fun score(ctx: SkillContext, input: String): Pair<Score, VolumeInput> {
        val (score, sentence) = data.score(ctx, input)
        val spoken = SpokenVolume(
            sentence, VolumeStream.namedIn(input), VolumeTool.asksAboutVolume(input),
        )
        return Pair(if (VolumeTool.mentionsVolume(input)) score else AlwaysWorstScore, spoken)
    }

    override fun inputFromSlots(slots: Map<String, Any?>): VolumeInput =
        VolumeTool.commandFromSlots(slots)

    override suspend fun generateOutput(ctx: SkillContext, inputData: VolumeInput): SkillOutput {
        return when (inputData) {
            is VolumeCommand -> {
                val control = controlFactory(ctx) ?: return VolumeOutput(null)
                VolumeOutput(VolumeLogic.apply(control, inputData))
            }
            // resolves the captures like a call of the volume tool, asking about numbers it can't
            // read and for the level "set the volume" leaves out, then runs this skill again with
            // the VolumeCommand from inputFromSlots
            is SpokenVolume -> GarCallRunner(
                targetInfo = correspondingSkillInfo,
                target = this,
                confirm = false,
                resolver = resolverFactory(ctx),
                formatter = formatterFactory(ctx),
                yesNoData = Sentences.UtilYesNo[ctx.sentencesLanguage],
            ).next(
                ctx,
                GarCall(VolumeTool.specOf(inputData), VolumeTool.rawSlotsOf(inputData), ""),
                nested = false,
            )
        }
    }
}
