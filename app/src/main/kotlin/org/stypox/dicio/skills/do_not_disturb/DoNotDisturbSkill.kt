package org.stypox.dicio.skills.do_not_disturb

import android.app.NotificationManager
import androidx.core.content.getSystemService
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.DoNotDisturb

class DoNotDisturbSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<DoNotDisturb>,
    private val controlFactory: (SkillContext) -> DoNotDisturbControl? = { ctx ->
        ctx.android.getSystemService<NotificationManager>()
            ?.let { AndroidDoNotDisturbControl(ctx.android, it) }
    },
) : StandardRecognizerSkill<DoNotDisturb>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: DoNotDisturb): SkillOutput {
        val control = controlFactory(ctx)
            ?: return DoNotDisturbOutput(DoNotDisturbResult.FAILED)
        return DoNotDisturbOutput(
            when (inputData) {
                DoNotDisturb.TurnOn -> DoNotDisturbLogic.turnOn(control)
                DoNotDisturb.TurnOff -> DoNotDisturbLogic.turnOff(control)
                DoNotDisturb.Query -> DoNotDisturbLogic.query(control)
            }
        )
    }
}
