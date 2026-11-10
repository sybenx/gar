package org.stypox.dicio.skills.online_skills

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

/**
 * "Turn on all online skills" (GAR): switches all of Dicio's online skills on or off at once,
 * without GAR's question about each one. English only.
 */
object OnlineSkillsInfo : SkillInfo("online_skills") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_online_skills)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_online_skills)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Public)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.OnlineSkills[ctx.sentencesLanguage] ?: return null
        return OnlineSkillsSkill(OnlineSkillsInfo, data)
    }
}
