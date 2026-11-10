package org.stypox.dicio.skills.math

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Functions
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R

/** Spoken arithmetic, English only: its words are read in Kotlin, not by sentences. */
object MathInfo : SkillInfo("math") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_math)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_math)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Functions)

    override fun build(ctx: SkillContext): Skill<*>? {
        if (ctx.sentencesLanguage.substringBefore('-').substringBefore('_') != "en" ||
            ctx.parserFormatter == null
        ) {
            return null
        }
        return MathSkill(MathInfo)
    }
}
