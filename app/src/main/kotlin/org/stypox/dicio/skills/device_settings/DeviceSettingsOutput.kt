package org.stypox.dicio.skills.device_settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.call.GarCancelledOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.connectivity.ConnectivityOutput
import org.stypox.dicio.skills.volume.VolumeOutput
import org.stypox.dicio.util.RecognizeYesNoSkill
import org.stypox.dicio.util.getString
import java.time.Duration
import org.dicio.numbers.unit.Duration as DicioDuration

/**
 * What a device settings command did: a short line to speak ("Brightness at 40 percent.",
 * "Here's airplane mode."), and on the card the details (adaptive brightness, why GAR opened a
 * page instead of switching it).
 */
class DeviceSettingsOutput(val result: DeviceSettingResult) : SkillOutput {

    override fun getSpeechOutput(ctx: SkillContext): String = when (val r = result) {
        is DeviceSettingResult.Brightness -> ctx.getString(
            if (r.changed) R.string.gar_settings_brightness_set else R.string.gar_settings_brightness,
            r.percent,
        )
        is DeviceSettingResult.Switch -> ctx.getString(
            when (r.change) {
                SwitchChange.TURNED ->
                    if (r.on) R.string.gar_settings_turned_on else R.string.gar_settings_turned_off
                SwitchChange.ALREADY ->
                    if (r.on) R.string.gar_settings_already_on else R.string.gar_settings_already_off
                SwitchChange.STATE ->
                    if (r.on) R.string.gar_settings_is_on else R.string.gar_settings_is_off
            },
            ctx.getString(
                if (r.change == SwitchChange.TURNED) nameOf(r.setting) else titleOf(r.setting)
            ),
        )
        is DeviceSettingResult.Timeout -> ctx.getString(
            if (r.changed) R.string.gar_settings_timeout_set else R.string.gar_settings_timeout,
            duration(ctx, r.timeout),
        )
        is DeviceSettingResult.Ringer -> ctx.getString(ringerMessage(r.mode, r.change))
        is DeviceSettingResult.RingerUnchanged -> ctx.getString(R.string.gar_settings_ringer_unchanged)
        is DeviceSettingResult.Wifi -> ctx.getString(
            when {
                r.status.connected -> R.string.gar_settings_wifi_connected
                r.status.enabled == true -> R.string.gar_settings_wifi_on
                r.status.enabled == false -> R.string.gar_settings_wifi_off
                else -> R.string.gar_settings_wifi_not_connected
            }
        )
        is DeviceSettingResult.Bluetooth -> when {
            !r.status.state.present -> ctx.getString(R.string.skill_connectivity_no_bluetooth)
            r.status.devices.isNotEmpty() -> ctx.getString(
                R.string.gar_settings_bluetooth_connected,
                r.status.devices.joinToString(ctx.getString(R.string.gar_options_separator)),
            )
            // "Bluetooth is on." would sound like a yes to "are my earbuds connected"
            r.headphones && r.status.state.enabled == true ->
                ctx.getString(R.string.gar_settings_headphones_none)
            r.status.state.enabled == true -> ctx.getString(R.string.gar_settings_bluetooth_on)
            r.status.state.enabled == false -> ctx.getString(R.string.gar_settings_bluetooth_off)
            else -> ctx.getString(
                R.string.gar_settings_unknown,
                ctx.getString(R.string.gar_settings_name_bluetooth),
            )
        }
        is DeviceSettingResult.Connectivity -> ConnectivityOutput(r.result).getSpeechOutput(ctx)
        is DeviceSettingResult.Volume -> VolumeOutput(r.result).getSpeechOutput(ctx)
        is DeviceSettingResult.SettingPage ->
            ctx.getString(R.string.gar_settings_here, ctx.getString(nameOf(r.setting)))
        is DeviceSettingResult.Page -> if (r.switch) {
            ctx.getString(R.string.gar_settings_here, ctx.getString(switchNameOf(r.page)))
        } else {
            ctx.getString(R.string.gar_settings_opening, ctx.getString(r.page.label))
        }
        is DeviceSettingResult.NeedsDndAccess -> ctx.getString(
            if (r.toSilent) {
                R.string.gar_settings_dnd_access_silent
            } else {
                R.string.gar_settings_dnd_access_ring
            }
        )
        is DeviceSettingResult.NoHardware ->
            ctx.getString(R.string.gar_settings_no_hardware, ctx.getString(nameOf(r.setting)))
        is DeviceSettingResult.Unknown -> ctx.getString(
            if (r.setting.takesLevel) R.string.gar_settings_cant_read else R.string.gar_settings_unknown,
            ctx.getString(nameOf(r.setting)),
        )
        DeviceSettingResult.AccessOpened -> ctx.getString(R.string.gar_settings_access_opened)
        DeviceSettingResult.Locked -> ctx.getString(R.string.gar_unlock_first)
        DeviceSettingResult.OpenFailed -> ctx.getString(R.string.skill_connectivity_open_failed)
        // asked by DeviceSettingsAccessOutput instead
        is DeviceSettingResult.NeedsWriteAccess,
        DeviceSettingResult.Failed -> ctx.getString(R.string.gar_settings_failed)
    }

    /** What the card adds below the spoken line, or null. */
    private fun detail(ctx: SkillContext): String? = when (val r = result) {
        is DeviceSettingResult.Brightness ->
            if (r.auto) ctx.getString(R.string.gar_settings_brightness_auto) else null
        is DeviceSettingResult.SettingPage -> if (r.systemOnly) {
            ctx.getString(R.string.gar_settings_system_only, ctx.getString(nameOf(r.setting)))
        } else {
            null
        }
        is DeviceSettingResult.RingerUnchanged -> ctx.getString(ringerMessage(r.mode, SwitchChange.STATE))
        is DeviceSettingResult.Page -> if (r.switch) {
            ctx.getString(R.string.gar_settings_system_only, ctx.getString(switchNameOf(r.page)))
        } else {
            null
        }
        // GAR only sees Bluetooth's headphones, not a wired or USB-C headset
        is DeviceSettingResult.Bluetooth ->
            if (r.headphones) ctx.getString(R.string.gar_settings_headphones_bluetooth_only) else null
        else -> null
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val detail = detail(ctx)
        if (detail == null) {
            Headline(text = getSpeechOutput(ctx))
        } else {
            Column {
                Headline(text = getSpeechOutput(ctx))
                Spacer(modifier = Modifier.height(4.dp))
                Subtitle(text = detail)
            }
        }
    }

    companion object {
        /** A setting's name inside a sentence: "Turned on auto-rotate." */
        @StringRes
        fun nameOf(setting: DeviceSetting): Int = when (setting) {
            DeviceSetting.WIFI -> R.string.gar_settings_name_wifi
            DeviceSetting.BLUETOOTH -> R.string.gar_settings_name_bluetooth
            DeviceSetting.AIRPLANE_MODE -> R.string.gar_settings_name_airplane_mode
            DeviceSetting.MOBILE_DATA -> R.string.gar_settings_name_mobile_data
            DeviceSetting.HOTSPOT -> R.string.gar_settings_name_hotspot
            DeviceSetting.NFC -> R.string.gar_settings_name_nfc
            DeviceSetting.LOCATION -> R.string.gar_settings_name_location
            DeviceSetting.BATTERY_SAVER -> R.string.gar_settings_name_battery_saver
            DeviceSetting.DARK_MODE -> R.string.gar_settings_name_dark_mode
            DeviceSetting.BRIGHTNESS -> R.string.gar_settings_name_brightness
            DeviceSetting.AUTO_BRIGHTNESS -> R.string.gar_settings_name_auto_brightness
            DeviceSetting.AUTO_ROTATE -> R.string.gar_settings_name_auto_rotate
            DeviceSetting.SCREEN_TIMEOUT -> R.string.gar_settings_name_screen_timeout
            DeviceSetting.RINGER -> R.string.gar_settings_name_ringer
            DeviceSetting.VIBRATE -> R.string.gar_settings_name_vibrate
        }

        /** What a page switches, inside a sentence: "Here's night light." */
        @StringRes
        fun switchNameOf(page: SettingsPage): Int =
            if (page == SettingsPage.NIGHT_LIGHT) R.string.gar_settings_name_night_light else page.label

        /** A setting's name at the start of a sentence: "Airplane mode is on." */
        @StringRes
        fun titleOf(setting: DeviceSetting): Int = when (setting) {
            DeviceSetting.WIFI -> R.string.gar_settings_title_wifi
            DeviceSetting.BLUETOOTH -> R.string.gar_settings_title_bluetooth
            DeviceSetting.AIRPLANE_MODE -> R.string.gar_settings_title_airplane_mode
            DeviceSetting.MOBILE_DATA -> R.string.gar_settings_title_mobile_data
            DeviceSetting.HOTSPOT -> R.string.gar_settings_title_hotspot
            DeviceSetting.NFC -> R.string.gar_settings_title_nfc
            DeviceSetting.LOCATION -> R.string.gar_settings_title_location
            DeviceSetting.BATTERY_SAVER -> R.string.gar_settings_title_battery_saver
            DeviceSetting.DARK_MODE -> R.string.gar_settings_title_dark_mode
            DeviceSetting.BRIGHTNESS -> R.string.gar_settings_title_brightness
            DeviceSetting.AUTO_BRIGHTNESS -> R.string.gar_settings_title_auto_brightness
            DeviceSetting.AUTO_ROTATE -> R.string.gar_settings_title_auto_rotate
            DeviceSetting.SCREEN_TIMEOUT -> R.string.gar_settings_title_screen_timeout
            DeviceSetting.RINGER -> R.string.gar_settings_title_ringer
            DeviceSetting.VIBRATE -> R.string.gar_settings_title_vibrate
        }

        @StringRes
        fun ringerMessage(mode: RingerMode, change: SwitchChange): Int = when (change) {
            SwitchChange.TURNED -> when (mode) {
                RingerMode.NORMAL -> R.string.gar_settings_ringer_turned_normal
                RingerMode.VIBRATE -> R.string.gar_settings_ringer_vibrate
                RingerMode.SILENT -> R.string.gar_settings_ringer_silent
            }
            SwitchChange.ALREADY -> when (mode) {
                RingerMode.NORMAL -> R.string.gar_settings_ringer_already_normal
                RingerMode.VIBRATE -> R.string.gar_settings_ringer_already_vibrate
                RingerMode.SILENT -> R.string.gar_settings_ringer_already_silent
            }
            SwitchChange.STATE -> when (mode) {
                RingerMode.NORMAL -> R.string.gar_settings_ringer_normal
                RingerMode.VIBRATE -> R.string.gar_settings_ringer_vibrate
                RingerMode.SILENT -> R.string.gar_settings_ringer_silent
            }
        }

        /** "thirty seconds", "two minutes". */
        fun duration(ctx: SkillContext, duration: Duration): String =
            ctx.parserFormatter?.niceDuration(DicioDuration(duration))?.speech(true)?.get()
                ?: ctx.android.resources.getQuantityString(
                    R.plurals.gar_settings_seconds,
                    duration.seconds.toInt(),
                    duration.seconds.toInt(),
                )

        /**
         * The output for [result]: GAR asks before opening the "Modify system settings" page,
         * and a locked phone gets GAR's usual answer.
         */
        fun of(
            result: DeviceSettingResult,
            control: DeviceSettingsControl,
            yesNoData: StandardRecognizerData<Sentences.UtilYesNo>?,
        ): SkillOutput = when {
            result is DeviceSettingResult.Locked -> GarLockedOutput()
            result is DeviceSettingResult.NeedsWriteAccess && yesNoData != null ->
                DeviceSettingsAccessOutput(result.setting, control, yesNoData)
            // no yes/no sentences (not English): open the page, as for Do Not Disturb access
            result is DeviceSettingResult.NeedsWriteAccess ->
                DeviceSettingsOutput(DeviceSettingsAccessAnswerSkill.openAccess(control))
            else -> DeviceSettingsOutput(result)
        }
    }
}

/**
 * "Changing the brightness needs permission to modify system settings. Open that page?", with
 * what the access is and Not now / Open settings buttons, like the online skills' question
 * (`GarOnlineAskOutput`). The user answers by voice (yes/no) or with the buttons, which send their
 * label as input, so both go through [DeviceSettingsAccessAnswerSkill]. Android grants the access
 * on its own page, so GAR can only open it: the user asks again once it is on.
 */
class DeviceSettingsAccessOutput(
    val setting: DeviceSetting,
    private val control: DeviceSettingsControl,
    private val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
) : SkillOutput {

    /** Set once the user answered, to disable the buttons. */
    var answered by mutableStateOf(false)

    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.gar_settings_access_question, ctx.getString(accessReason(setting)))

    /** The skills that wait for the answer; [yesLabel] and [noLabel] are the buttons' labels. */
    fun nextSkills(yesLabel: String, noLabel: String): List<Skill<*>> = listOf(
        DeviceSettingsAccessAnswerSkill(control, yesNoData, yesLabel, noLabel) { answered = true },
        GarCancelSkill(DeviceSettingsInfo) { answered = true },
    )

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = nextSkills(
                ctx.getString(R.string.gar_settings_access_open),
                ctx.getString(R.string.gar_settings_access_not_now),
            ),
        )

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        val skillEvaluator = remember {
            EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
                .skillEvaluator()
        }
        // the buttons only make sense while this card is the latest answer
        val log by skillEvaluator.state.collectAsState()
        val isLatest = log.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer === this
        val enabled = isLatest && !answered

        val yes = stringResource(R.string.gar_settings_access_open)
        val no = stringResource(R.string.gar_settings_access_not_now)
        val send = { label: String ->
            answered = true
            skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(label, 1.0f))))
        }

        Column {
            Headline(text = getSpeechOutput(ctx))
            Spacer(modifier = Modifier.height(8.dp))
            Body(text = stringResource(R.string.gar_settings_access_detail))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = { send(no) }, enabled = enabled) {
                    Text(text = no)
                }
                Button(onClick = { send(yes) }, enabled = enabled) {
                    Text(text = yes)
                }
            }
        }
    }

    companion object {
        /** What needs the access: "Changing the brightness". */
        @StringRes
        fun accessReason(setting: DeviceSetting): Int = when (setting) {
            DeviceSetting.SCREEN_TIMEOUT -> R.string.gar_settings_access_timeout
            DeviceSetting.AUTO_ROTATE -> R.string.gar_settings_access_auto_rotate
            DeviceSetting.AUTO_BRIGHTNESS -> R.string.gar_settings_access_auto_brightness
            else -> R.string.gar_settings_access_brightness
        }
    }
}

/**
 * The answer to a [DeviceSettingsAccessOutput]: yes (or the [yesLabel] button) opens GAR's
 * "Modify system settings" page, no (or the [noLabel] button) leaves it ("OK."). Voice yes/no
 * through Dicio's `util_yes_no` sentences, like GAR's confirmation card.
 */
class DeviceSettingsAccessAnswerSkill(
    private val control: DeviceSettingsControl,
    data: StandardRecognizerData<Sentences.UtilYesNo>,
    private val yesLabel: String,
    private val noLabel: String,
    private val onAnswered: () -> Unit = {},
) : RecognizeYesNoSkill(DeviceSettingsInfo, data) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Boolean> =
        when (normalizeAnswer(input)) {
            normalizeAnswer(yesLabel) -> Pair(AlwaysBestScore, true)
            normalizeAnswer(noLabel) -> Pair(AlwaysBestScore, false)
            else -> super.score(ctx, input)
        }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Boolean): SkillOutput {
        onAnswered()
        return if (inputData) DeviceSettingsOutput(openAccess(control)) else GarCancelledOutput()
    }

    companion object {
        /** Opens the access page; a locked phone gets "Unlock your phone first." */
        fun openAccess(control: DeviceSettingsControl): DeviceSettingResult = when {
            control.isLocked() -> DeviceSettingResult.Locked
            control.openWriteSettingsAccess() -> DeviceSettingResult.AccessOpened
            else -> DeviceSettingResult.OpenFailed
        }
    }
}
