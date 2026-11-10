package org.stypox.dicio.skills.timer

import androidx.compose.runtime.Composable
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.io.graphical.Headline

/**
 * A timer answer (GAR): says [answer] and shows it. The clock app shows the timers themselves,
 * since GAR's timers are the clock app's (gar/DESIGN.md, "Timers").
 */
class TimerAnswerOutput(val answer: TimerAnswer) : SkillOutput {

    override fun getSpeechOutput(ctx: SkillContext): String = TimerAnswers.text(ctx, answer)

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Headline(text = getSpeechOutput(ctx))
    }
}
