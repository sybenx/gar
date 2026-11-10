package org.stypox.dicio.skills.units

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object UnitsInfo : SkillInfo("units") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_units)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_units)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Straighten)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Units[ctx.sentencesLanguage] ?: return null
        return UnitsSkill(UnitsInfo, data)
    }
}
