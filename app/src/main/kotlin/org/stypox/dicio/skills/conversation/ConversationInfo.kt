package org.stypox.dicio.skills.conversation

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

/**
 * "Stop", "never mind", "repeat that" (GAR): commands about the conversation itself. English
 * sentences only and no tool, so the model's prompt stays as it is (gar/DESIGN.md,
 * "Conversation").
 */
object ConversationInfo : SkillInfo("conversation") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_conversation)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_conversation)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Forum)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Conversation[ctx.sentencesLanguage] ?: return null
        return ConversationSkill(ConversationInfo, data)
    }
}
