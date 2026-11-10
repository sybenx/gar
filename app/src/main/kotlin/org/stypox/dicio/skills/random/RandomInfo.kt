package org.stypox.dicio.skills.random

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object RandomInfo : SkillInfo("random") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_random)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_random)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Casino)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Random[ctx.sentencesLanguage] ?: return null
        return RandomSkill(RandomInfo, data)
    }
}
