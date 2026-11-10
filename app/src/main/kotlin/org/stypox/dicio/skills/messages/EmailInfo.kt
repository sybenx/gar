package org.stypox.dicio.skills.messages

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.util.PERMISSION_READ_CONTACTS

object EmailInfo : SkillInfo("email") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_email)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_email)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Email)

    // the contacts' email addresses
    override val neededPermissions: List<Permission>
        = listOf(PERMISSION_READ_CONTACTS)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Email[ctx.sentencesLanguage] ?: return null
        return EmailSkill(EmailInfo, data, EmailFlow())
    }
}
