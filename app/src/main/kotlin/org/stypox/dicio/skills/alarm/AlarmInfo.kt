package org.stypox.dicio.skills.alarm

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.sentences.Sentences

/** Needed to send `AlarmClock` intents to the clock app (a normal permission). */
val PERMISSION_SET_ALARM = Permission.NormalPermission(
    name = R.string.perm_set_alarm,
    id = "com.android.alarm.permission.SET_ALARM",
)

object AlarmInfo : SkillInfo("alarm") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_alarm)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_alarm)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Alarm)

    override val neededPermissions: List<Permission>
            = listOf(PERMISSION_SET_ALARM)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Alarm[ctx.sentencesLanguage] ?: return null
        // times are resolved with dicio-numbers
        if (ctx.parserFormatter == null) return null
        // while the clock app's alarm rings, GAR's conversation leaves "stop" to this skill, and
        // GAR's questions its stop and snooze commands
        GarRinging.register(id) { FiringAlarms.current() == FiringAlarmCheck.Firing }
        GarRinging.registerCommands(id) { _, input -> AlarmSkill.isRingingCommand(input) }
        return AlarmSkill(AlarmInfo, data)
    }
}
