package org.stypox.dicio.skills.agenda

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.calendar.PERMISSION_READ_CALENDAR
import org.stypox.dicio.skills.calendar.PERMISSION_WRITE_CALENDAR

/** "What's my next event", "move my dentist appointment to 3pm", "cancel my meeting with john". */
object AgendaInfo : SkillInfo("agenda") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_agenda)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_agenda)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.EditCalendar)

    // the calendar skill's permissions, asked for plainly by Dicio before the skill runs
    override val neededPermissions: List<Permission> =
        listOf(PERMISSION_READ_CALENDAR, PERMISSION_WRITE_CALENDAR)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Agenda[ctx.sentencesLanguage] ?: return null
        val yesNoData = Sentences.UtilYesNo[ctx.sentencesLanguage] ?: return null
        // events are found by dates and times resolved with dicio-numbers
        if (ctx.parserFormatter == null) return null
        return AgendaSkill(AgendaInfo, data, yesNoData)
    }
}

/** "What are my reminders", "cancel the reminder about the dentist", "delete all reminders". */
object RemindersInfo : SkillInfo("reminders") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_reminders)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_reminders)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.NotificationsActive)

    override val neededPermissions: List<Permission> =
        listOf(PERMISSION_READ_CALENDAR, PERMISSION_WRITE_CALENDAR)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Reminders[ctx.sentencesLanguage] ?: return null
        val yesNoData = Sentences.UtilYesNo[ctx.sentencesLanguage] ?: return null
        if (ctx.parserFormatter == null) return null
        return RemindersSkill(RemindersInfo, data, yesNoData)
    }
}
