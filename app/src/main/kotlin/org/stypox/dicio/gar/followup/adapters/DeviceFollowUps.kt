package org.stypox.dicio.gar.followup.adapters

import cc.aaro.gar.router.followup.FollowUpForm
import org.stypox.dicio.gar.followup.GarAnswered
import org.stypox.dicio.gar.followup.GarFacts
import org.stypox.dicio.gar.followup.GarFollowUpAdapter
import org.stypox.dicio.gar.followup.GarFollowUpContext
import org.stypox.dicio.gar.followup.GarFollowUpReach
import org.stypox.dicio.gar.followup.GarRememberedTurn
import org.stypox.dicio.gar.followup.GarRewrite
import org.stypox.dicio.gar.followup.GarTurn
import org.stypox.dicio.gar.followup.GarTurnKind
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.connectivity.ConnectivityLogic
import org.stypox.dicio.skills.connectivity.Radio
import org.stypox.dicio.skills.connectivity.RadioState
import org.stypox.dicio.skills.device_settings.DeviceAction
import org.stypox.dicio.skills.device_settings.DeviceSetting
import org.stypox.dicio.skills.device_settings.DeviceSettingCommand
import org.stypox.dicio.skills.device_settings.DeviceSettingTool
import org.stypox.dicio.skills.device_settings.SaidDeviceSetting
import org.stypox.dicio.skills.flashlight.FlashlightOutput
import org.stypox.dicio.skills.volume.SpokenVolume

/**
 * The phone's settings: the flashlight, Wi-Fi and Bluetooth, Do Not Disturb, the device settings
 * and the volume. Their turns are [GarTurnKind.REVERSIBLE]: "turn on the flashlight", "what time
 * is it", "turn it back off" still turns the flashlight off. The fact is the setting as the
 * sentences say it ([GarFacts.setting]), the rewrite that sentence again: "turn off the
 * flashlight", "turn the volume up", "set the brightness to 50 percent".
 */
object DeviceFollowUps : GarFollowUpAdapter {
    const val FLASHLIGHT = "flashlight"
    const val WIFI = "wifi"
    const val BLUETOOTH = "bluetooth"
    const val DO_NOT_DISTURB = "do not disturb"

    const val ON = "on"
    const val OFF = "off"
    const val UP = "up"
    const val DOWN = "down"
    const val SET = "set"
    const val STATUS = "status"

    /** The [GarTurn.slots] keys. */
    const val SETTING = "setting"
    const val ACTION = "action"
    const val STREAM = "stream"

    override val skillIds = setOf("flashlight", "connectivity", "do_not_disturb", "device_settings", "volume")

    // like the target tools: the flashlight, volume, ringer, brightness and Do Not Disturb run
    // on the lock screen; the airplane rule stays in its skill
    override val onLockScreen: Boolean = true

    /** What a turn did: the setting, as the sentences name it, and the action. */
    private data class Change(val setting: String, val action: String, val stream: String? = null)

    override fun remember(answered: GarAnswered): GarTurn? {
        if (answered.output is FlashlightOutput.Error) {
            return null
        }
        val change = fromCall(answered) ?: fromInput(answered.inputData)
        return GarTurn(
            skillId = answered.skillId,
            action = change?.action ?: "",
            slots = listOfNotNull(
                change?.let { SETTING to it.setting },
                change?.let { ACTION to it.action },
                change?.stream?.let { STREAM to it },
            ).toMap(),
            facts = GarFacts(setting = change?.setting),
            command = answered.input,
            kind = if (change == null) GarTurnKind.ANSWER else GarTurnKind.REVERSIBLE,
        )
    }

    override fun rewrite(form: FollowUpForm, turn: GarRememberedTurn, context: GarFollowUpContext): GarRewrite? {
        val setting = turn.turn.facts.setting ?: return null
        val stream = turn.turn.slots[STREAM]
        val text = when (form) {
            is FollowUpForm.Switch -> switchCommand(setting, form.on)
            is FollowUpForm.Adjust -> if (form.kind == null) adjustCommand(setting, form.up, stream) else null
            is FollowUpForm.SetTo -> setCommand(setting, form.value, stream)
            else -> null
        } ?: return null
        return GarRewrite.Command(text)
    }

    /** "turn off the flashlight", "mute the volume"; null for a setting with no switch. */
    fun switchCommand(setting: String, on: Boolean): String? {
        val state = if (on) "on" else "off"
        return when (setting) {
            FLASHLIGHT -> "turn $state the flashlight"
            WIFI, BLUETOOTH, DO_NOT_DISTURB -> "turn $state $setting"
            GarFollowUpReach.VOLUME -> if (on) "unmute the volume" else "mute the volume"
            DeviceSetting.RINGER.slotValue -> "turn $state the ringer"
            else -> DeviceSetting.entries
                .firstOrNull { it.slotValue == setting && it !in UNSWITCHED }
                ?.let { "turn $state ${it.slotValue}" }
        }
    }

    /** "turn the volume up", "turn down the brightness"; null for a setting with no level. */
    fun adjustCommand(setting: String, up: Boolean, stream: String? = null): String? = when (setting) {
        GarFollowUpReach.VOLUME -> "turn the ${stream?.let { "$it " } ?: ""}volume ${if (up) "up" else "down"}"
        GarFollowUpReach.BRIGHTNESS -> if (up) "turn up the brightness" else "turn down the brightness"
        else -> null
    }

    /** "set the brightness to 50 percent", "set the volume to 7"; null for a setting with no level. */
    fun setCommand(setting: String, value: String, stream: String? = null): String? = when (setting) {
        GarFollowUpReach.VOLUME -> "set the ${stream?.let { "$it " } ?: ""}volume to $value"
        GarFollowUpReach.BRIGHTNESS ->
            if (NUMBER.matches(value)) "set the brightness to $value percent" else "set the brightness to $value"
        DeviceSetting.SCREEN_TIMEOUT.slotValue -> "set the screen timeout to $value"
        else -> null
    }

    private val NUMBER = Regex("[0-9]+")

    /** Settings whose sentences have no "turn on" or "turn off" of their own. */
    private val UNSWITCHED = setOf(
        DeviceSetting.BRIGHTNESS, DeviceSetting.SCREEN_TIMEOUT, DeviceSetting.RINGER,
        DeviceSetting.WIFI, DeviceSetting.BLUETOOTH,
    )

    /** The change of a model's call (GarTurnElement.call), after its confirmation if any. */
    private fun fromCall(answered: GarAnswered): Change? {
        val call = answered.call ?: return null
        return when (call.tool.name) {
            "flashlight" -> (call.values["state"] as? String)?.let { Change(FLASHLIGHT, it) }
            "do_not_disturb" -> when (call.values["action"]) {
                "enable" -> Change(DO_NOT_DISTURB, ON)
                "disable" -> Change(DO_NOT_DISTURB, OFF)
                "check" -> Change(DO_NOT_DISTURB, STATUS)
                else -> null
            }
            "volume" -> {
                // the default stream needs no name ("turn the volume up")
                val stream = (call.values["stream"] as? String)?.takeIf { it != "music" }
                when (call.values["action"]) {
                    "up" -> Change(GarFollowUpReach.VOLUME, UP, stream)
                    "down" -> Change(GarFollowUpReach.VOLUME, DOWN, stream)
                    "mute" -> Change(GarFollowUpReach.VOLUME, OFF, stream)
                    "unmute" -> Change(GarFollowUpReach.VOLUME, ON, stream)
                    else -> null
                }
            }
            "volume_set" -> Change(GarFollowUpReach.VOLUME, SET)
            DeviceSettingTool.NAME -> {
                val setting = DeviceSetting.fromSlot(call.values[DeviceSettingTool.SETTING])
                val action = DeviceAction.fromSlot(call.values[DeviceSettingTool.ACTION])
                if (setting == null || action == null) null else deviceChange(setting, action)
            }
            else -> null
        }
    }

    /** The change of a sentence match. */
    private fun fromInput(inputData: Any?): Change? = when (inputData) {
        Sentences.Flashlight.TurnOn -> Change(FLASHLIGHT, ON)
        Sentences.Flashlight.TurnOff -> Change(FLASHLIGHT, OFF)
        is Sentences.Connectivity -> ConnectivityLogic.radioAndState(inputData).let { (radio, state) ->
            val setting = if (radio == Radio.WIFI) WIFI else BLUETOOTH
            when (state) {
                RadioState.ON -> Change(setting, ON)
                RadioState.OFF -> Change(setting, OFF)
                RadioState.SETTINGS -> Change(setting, STATUS)
            }
        }
        Sentences.DoNotDisturb.TurnOn -> Change(DO_NOT_DISTURB, ON)
        Sentences.DoNotDisturb.TurnOff -> Change(DO_NOT_DISTURB, OFF)
        Sentences.DoNotDisturb.Query -> Change(DO_NOT_DISTURB, STATUS)
        is SpokenVolume -> {
            val action = if (inputData.question) STATUS else when (inputData.sentence) {
                Sentences.Volume.VolumeUp, is Sentences.Volume.VolumeUpTimes -> UP
                Sentences.Volume.VolumeDown, is Sentences.Volume.VolumeDownTimes -> DOWN
                is Sentences.Volume.VolumeSet, Sentences.Volume.Max -> SET
                Sentences.Volume.Mute -> OFF
                Sentences.Volume.Unmute -> ON
            }
            Change(GarFollowUpReach.VOLUME, action, inputData.stream?.slotValue)
        }
        is DeviceSettingCommand -> deviceChange(inputData.setting, inputData.action)
        is SaidDeviceSetting -> deviceChange(inputData.setting, inputData.action)
        else -> null
    }

    private fun deviceChange(setting: DeviceSetting, action: DeviceAction): Change {
        val name = when (setting) {
            DeviceSetting.WIFI -> WIFI
            DeviceSetting.BLUETOOTH -> BLUETOOTH
            else -> setting.slotValue
        }
        return Change(
            name,
            when (action) {
                DeviceAction.ON -> ON
                DeviceAction.OFF -> OFF
                DeviceAction.UP -> UP
                DeviceAction.DOWN -> DOWN
                DeviceAction.SET -> SET
                DeviceAction.TOGGLE, DeviceAction.SHOW, DeviceAction.STATUS -> STATUS
            },
        )
    }
}
