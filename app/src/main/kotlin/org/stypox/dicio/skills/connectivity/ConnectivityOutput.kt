package org.stypox.dicio.skills.connectivity

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

class ConnectivityOutput(val result: ConnectivityResult) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(
        when (result) {
            ConnectivityResult.WIFI_ON_PANEL -> R.string.skill_connectivity_wifi_on_panel
            ConnectivityResult.WIFI_OFF_PANEL -> R.string.skill_connectivity_wifi_off_panel
            ConnectivityResult.WIFI_PANEL -> R.string.skill_connectivity_wifi_panel
            ConnectivityResult.WIFI_SETTINGS -> R.string.skill_connectivity_wifi_settings
            ConnectivityResult.BLUETOOTH_ASKED -> R.string.skill_connectivity_bluetooth_asked
            ConnectivityResult.BLUETOOTH_ALREADY_ON ->
                R.string.skill_connectivity_bluetooth_already_on
            ConnectivityResult.BLUETOOTH_ALREADY_OFF ->
                R.string.skill_connectivity_bluetooth_already_off
            ConnectivityResult.BLUETOOTH_ON_SETTINGS ->
                R.string.skill_connectivity_bluetooth_on_settings
            ConnectivityResult.BLUETOOTH_OFF_SETTINGS ->
                R.string.skill_connectivity_bluetooth_off_settings
            ConnectivityResult.BLUETOOTH_SETTINGS -> R.string.skill_connectivity_bluetooth_settings
            ConnectivityResult.NO_BLUETOOTH -> R.string.skill_connectivity_no_bluetooth
            ConnectivityResult.OPEN_FAILED -> R.string.skill_connectivity_open_failed
        }
    )
}
