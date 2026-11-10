package org.stypox.dicio.skills.do_not_disturb

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object DoNotDisturbInfo : SkillInfo("do_not_disturb") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_do_not_disturb)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_do_not_disturb)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.DoNotDisturbOn)

    // No neededPermissions on purpose: policy access is not in the
    // enabled_notification_policy_access_packages secure setting on Android 15+, so Dicio's
    // SecurePermission check would never pass. The skill checks
    // NotificationManager.isNotificationPolicyAccessGranted itself and opens the access page.

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.DoNotDisturb[ctx.sentencesLanguage] ?: return null
        return DoNotDisturbSkill(DoNotDisturbInfo, data)
    }
}
