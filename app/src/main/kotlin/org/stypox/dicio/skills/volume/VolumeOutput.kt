package org.stypox.dicio.skills.volume

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

class VolumeOutput(val result: VolumeResult?) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = when (result) {
        null -> ctx.getString(R.string.skill_volume_unavailable)
        VolumeResult.Fixed -> ctx.getString(R.string.skill_volume_fixed)
        is VolumeResult.NotAllowed -> ctx.getString(R.string.skill_volume_not_allowed)
        is VolumeResult.Level -> if (result.muted) {
            ctx.getString(
                when (result.stream) {
                    VolumeStream.MUSIC -> R.string.skill_volume_muted_music
                    VolumeStream.RING -> R.string.skill_volume_muted_ring
                    VolumeStream.ALARM -> R.string.skill_volume_muted_alarm
                    VolumeStream.NOTIFICATION -> R.string.skill_volume_muted_notification
                }
            )
        } else {
            ctx.getString(
                when (result.stream) {
                    VolumeStream.MUSIC -> R.string.skill_volume_level_music
                    VolumeStream.RING -> R.string.skill_volume_level_ring
                    VolumeStream.ALARM -> R.string.skill_volume_level_alarm
                    VolumeStream.NOTIFICATION -> R.string.skill_volume_level_notification
                },
                result.percent,
            )
        }
    }
}
