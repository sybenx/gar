package org.stypox.dicio.skills.holiday

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object HolidayInfo : SkillInfo("holiday") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_holiday)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_holiday)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Celebration)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Holiday[ctx.sentencesLanguage] ?: return null
        return HolidaySkill(HolidayInfo, data)
    }
}
