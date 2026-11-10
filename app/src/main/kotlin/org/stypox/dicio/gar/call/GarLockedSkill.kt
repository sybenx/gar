package org.stypox.dicio.gar.call

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

/**
 * What GAR hands to the evaluator instead of [GarRoutedSkill] when the model called a tool that
 * may not run on a locked phone ([cc.aaro.gar.router.tools.ToolSpec.onLockScreen]) while the phone
 * is locked. Like [GarRoutedSkill], it is never put in a skill batch.
 */
class GarLockedSkill(targetInfo: SkillInfo) : Skill<Unit>(targetInfo, Specificity.LOW) {
    override fun score(ctx: SkillContext, input: String): Pair<Score, Unit> {
        throw UnsupportedOperationException("GarLockedSkill is only created by GarFallback")
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Unit): SkillOutput =
        GarLockedOutput()
}

/** "Unlock your phone first." */
class GarLockedOutput : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_unlock_first)
}
