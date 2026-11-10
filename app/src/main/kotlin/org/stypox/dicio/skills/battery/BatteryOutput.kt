package org.stypox.dicio.skills.battery

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.util.getString
import java.time.Duration
import org.dicio.numbers.unit.Duration as DicioDuration

/** The battery level and charging state, spoken short, with the percentage large on the card. */
class BatteryOutput(
    val question: BatteryQuestion,
    val status: BatteryStatus,
) : SkillOutput {
    val answer: BatteryAnswer get() = BatteryAnswer.of(question, status)

    override fun getSpeechOutput(ctx: SkillContext): String {
        val percent = status.percent ?: 0
        return when (answer) {
            BatteryAnswer.LEVEL -> ctx.getString(R.string.skill_battery_level, percent)
            BatteryAnswer.LEVEL_CHARGING ->
                ctx.getString(R.string.skill_battery_level_charging, percent)
            BatteryAnswer.FULL -> ctx.getString(R.string.skill_battery_full)
            BatteryAnswer.CHARGING -> ctx.getString(R.string.skill_battery_charging, percent)
            BatteryAnswer.PLUGGED_NOT_CHARGING ->
                ctx.getString(R.string.skill_battery_plugged_not_charging, percent)
            BatteryAnswer.NOT_CHARGING -> ctx.getString(R.string.skill_battery_not_charging, percent)
            BatteryAnswer.TIME_TO_FULL -> ctx.getString(
                R.string.skill_battery_time_to_full, duration(ctx, status.timeToFull), percent,
            )
            BatteryAnswer.CHARGING_NO_ESTIMATE ->
                ctx.getString(R.string.skill_battery_charging_no_estimate, percent)
            BatteryAnswer.UNKNOWN -> ctx.getString(R.string.skill_battery_unknown)
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val percent = status.percent
        if (percent == null) {
            Headline(text = getSpeechOutput(ctx))
            return
        }
        Column {
            Headline(text = ctx.getString(R.string.skill_battery_percent, percent))
            Subtitle(text = when {
                status.full -> ctx.getString(R.string.skill_battery_state_full)
                status.charging && status.timeToFull != null -> ctx.getString(
                    R.string.skill_battery_state_full_in, duration(ctx, status.timeToFull),
                )
                status.charging -> ctx.getString(R.string.skill_battery_state_charging)
                status.plugged -> ctx.getString(R.string.skill_battery_state_plugged)
                else -> ctx.getString(R.string.skill_battery_state_not_charging)
            })
        }
    }

    /** "one hour twenty minutes", rounded up to the minute. */
    private fun duration(ctx: SkillContext, duration: Duration?): String {
        val minutes = Duration.ofMinutes(((duration?.seconds ?: 0) + 59) / 60)
        val count = minutes.toMinutes().toInt()
        val resources = ctx.android.resources
        return ctx.parserFormatter?.niceDuration(DicioDuration(minutes))?.speech(true)?.get()
            ?: resources.getQuantityString(R.plurals.skill_battery_minutes, count, count)
    }
}
