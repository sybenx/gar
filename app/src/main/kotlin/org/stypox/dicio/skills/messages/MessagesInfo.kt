package org.stypox.dicio.skills.messages

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.util.PERMISSION_NOTIFICATION_LISTENER

object MessagesInfo : SkillInfo("messages") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_messages)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_messages)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Forum)

    // the messages and their reply buttons are in the notifications: Dicio asks for the access
    // (with a button to its settings page) before the skill first runs
    override val neededPermissions: List<Permission>
        = listOf(PERMISSION_NOTIFICATION_LISTENER)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Messages[ctx.sentencesLanguage] ?: return null
        return MessagesSkill(MessagesInfo, data, MessagesFlow())
    }
}
