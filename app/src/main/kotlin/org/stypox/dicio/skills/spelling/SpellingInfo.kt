package org.stypox.dicio.skills.spelling

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object SpellingInfo : SkillInfo("spelling") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_spelling)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_spelling)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Spellcheck)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Spelling[ctx.sentencesLanguage] ?: return null
        return SpellingSkill(SpellingInfo, data)
    }
}
