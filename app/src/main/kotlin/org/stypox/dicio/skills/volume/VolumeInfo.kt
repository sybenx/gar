package org.stypox.dicio.skills.volume

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object VolumeInfo : SkillInfo("volume") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_volume)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_volume)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.AutoMirrored.Filled.VolumeUp)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Volume[ctx.sentencesLanguage] ?: return null
        return VolumeSkill(VolumeInfo, data)
    }
}
