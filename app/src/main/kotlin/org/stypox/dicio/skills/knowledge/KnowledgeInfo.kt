package org.stypox.dicio.skills.knowledge

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R

/** General knowledge from Wikipedia, English only: its questions are read in Kotlin. */
object KnowledgeInfo : SkillInfo("knowledge") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_knowledge)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_knowledge)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.AutoMirrored.Filled.MenuBook)

    override fun build(ctx: SkillContext): Skill<*>? {
        if (ctx.sentencesLanguage.substringBefore('-').substringBefore('_') != "en") {
            return null
        }
        return KnowledgeSkill(KnowledgeInfo)
    }
}
