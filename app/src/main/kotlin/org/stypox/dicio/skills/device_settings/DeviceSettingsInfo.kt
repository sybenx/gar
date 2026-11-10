package org.stypox.dicio.skills.device_settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object DeviceSettingsInfo : SkillInfo("device_settings") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_device_settings)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_device_settings)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Settings)

    // No neededPermissions on purpose: "Modify system settings" and Do Not Disturb access are
    // special accesses that Dicio's permission check can't ask for. The skill asks for the first
    // with a question card and opens the page of the second, only when a command needs it.

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.DeviceSettings[ctx.sentencesLanguage] ?: return null
        return DeviceSettingsSkill(DeviceSettingsInfo, data)
    }
}
