package org.stypox.dicio.skills.timer

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Timer

// GAR: the timers are the clock app's (AlarmClock intents, gar/DESIGN.md "Timers"), so they ring
// with GAR closed. Dicio's in-process countdown (SetTimer) is gone; TimerActions does the work.
class TimerSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Timer>,
    private val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
    // GAR: the clock app's timers, only looked up when the skill runs
    private val actions: (SkillContext) -> TimerActions = TimerActions::forContext,
) : StandardRecognizerSkill<Timer>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Timer): SkillOutput {
        return actions(ctx).generateOutput(inputData) // GAR
    }
}
