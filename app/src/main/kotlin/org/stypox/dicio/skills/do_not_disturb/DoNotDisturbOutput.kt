package org.stypox.dicio.skills.do_not_disturb

import androidx.annotation.StringRes
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

class DoNotDisturbOutput(val result: DoNotDisturbResult) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(messageFor(result))

    companion object {
        /**
         * A change never sounds like a state: the model sometimes calls "enable" for "is do not
         * disturb on", and "Do Not Disturb is on" would hide that GAR just turned it on.
         */
        @StringRes
        fun messageFor(result: DoNotDisturbResult): Int = when (result) {
            DoNotDisturbResult.TURNED_ON -> R.string.skill_dnd_turned_on
            DoNotDisturbResult.TURNED_OFF -> R.string.skill_dnd_turned_off
            DoNotDisturbResult.IS_ON -> R.string.skill_dnd_on
            DoNotDisturbResult.IS_OFF -> R.string.skill_dnd_off
            DoNotDisturbResult.ALREADY_ON -> R.string.skill_dnd_already_on
            DoNotDisturbResult.ALREADY_OFF -> R.string.skill_dnd_already_off
            DoNotDisturbResult.OFF_IN_SETTINGS -> R.string.skill_dnd_off_in_settings
            DoNotDisturbResult.NO_ACCESS -> R.string.skill_dnd_no_access
            DoNotDisturbResult.FAILED -> R.string.skill_dnd_failed
        }
    }
}
