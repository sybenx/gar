package org.stypox.dicio.skills.contact_info

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.util.PERMISSION_READ_CONTACTS

/** "What's mom's number", "add a new contact", "save this number as joe". */
object ContactInfoInfo : SkillInfo("contact_info") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_contact_info)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_contact_info)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.ContactPhone)

    // the contacts, as the telephone and sms skills ask for them; "save this number" checks the
    // call log itself and asks for it with the call history skill's name
    override val neededPermissions: List<Permission> = listOf(PERMISSION_READ_CONTACTS)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.ContactInfo[ctx.sentencesLanguage] ?: return null
        return ContactInfoSkill(ContactInfoInfo, data)
    }
}
