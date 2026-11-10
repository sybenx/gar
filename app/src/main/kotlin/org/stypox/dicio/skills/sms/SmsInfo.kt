package org.stypox.dicio.skills.sms

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sms
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.util.PERMISSION_READ_CONTACTS

object SmsInfo : SkillInfo("sms") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_sms)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_sms)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Sms)

    // SEND_SMS too, so that Dicio asks for it before the first text and a spoken yes can send
    // without the screen (see PERMISSION_SEND_SMS)
    override val neededPermissions: List<Permission>
        = listOf(PERMISSION_READ_CONTACTS, PERMISSION_SEND_SMS)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Sms[ctx.sentencesLanguage] ?: return null
        return SmsSkill(SmsInfo, data, SmsFlow())
    }
}
