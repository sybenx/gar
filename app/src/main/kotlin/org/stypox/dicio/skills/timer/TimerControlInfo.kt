package org.stypox.dicio.skills.timer

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.gar.timer.GarStartedTimers
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.alarm.FiringAlarmCheck
import org.stypox.dicio.skills.alarm.FiringTimers

/**
 * GAR's controls of the clock app's timers, which Dicio's timer sentences set: stop a ringing
 * timer; pause, resume, add or take away time, list them, cancel them open the clock app's
 * timers (gar/DESIGN.md, "Timers").
 */
object TimerControlInfo : SkillInfo("timer_control") {
    /** The key of the clock app's ringing timer in [GarRinging]. */
    const val RINGING_ID = "timer"

    override fun name(context: Context) =
        context.getString(R.string.skill_name_timer_control)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_timer_control)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Timer)

    // English only, like GAR's stop words
    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.TimerControl[ctx.sentencesLanguage] ?: return null
        // while the clock app's timer rings, GAR's conversation leaves "stop" to this skill, and
        // GAR's questions what stops it
        GarRinging.register(RINGING_ID) { FiringTimers.current() == FiringAlarmCheck.Firing }
        GarRinging.registerCommands(RINGING_ID) { skillContext, input ->
            val names = GarStartedTimers.peek()?.ended().orEmpty().map { it.name }
            val durationOf = TimerControlSkill.wholeDurationOf(skillContext)
            TimerWords.whileRinging(input, ringing = true, names, durationOf = durationOf) != null
        }
        return TimerControlSkill(TimerControlInfo, data, actions = TimerActions::forContext)
    }
}
