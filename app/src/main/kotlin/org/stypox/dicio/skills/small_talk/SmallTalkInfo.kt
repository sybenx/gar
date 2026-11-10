package org.stypox.dicio.skills.small_talk

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiPeople
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object SmallTalkInfo : SkillInfo("small_talk") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_small_talk)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_small_talk)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.EmojiPeople)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.SmallTalk[ctx.sentencesLanguage] ?: return null
        return SmallTalkSkill(SmallTalkInfo, data)
    }
}
