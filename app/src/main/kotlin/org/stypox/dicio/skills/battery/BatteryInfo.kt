package org.stypox.dicio.skills.battery

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object BatteryInfo : SkillInfo("battery") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_battery)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_battery)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.BatteryFull)

    // no permissions: the battery state is a sticky broadcast and BatteryManager's properties

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Battery[ctx.sentencesLanguage] ?: return null
        return BatterySkill(BatteryInfo, data)
    }
}
