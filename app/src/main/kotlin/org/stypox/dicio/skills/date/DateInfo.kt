package org.stypox.dicio.skills.date

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Today
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object DateInfo : SkillInfo("date") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_date)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_date)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Today)

    // also built without sentences in this language, for GAR's date_query tool
    override fun build(ctx: SkillContext): Skill<*> =
        DateSkill(DateInfo, Sentences.Date[ctx.sentencesLanguage])
}
