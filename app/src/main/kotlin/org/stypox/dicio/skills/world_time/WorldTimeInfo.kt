package org.stypox.dicio.skills.world_time

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object WorldTimeInfo : SkillInfo("world_time") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_world_time)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_world_time)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Public)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.WorldTime[ctx.sentencesLanguage] ?: return null
        return WorldTimeSkill(WorldTimeInfo, data)
    }
}
