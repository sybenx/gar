package org.stypox.dicio.skills.device_settings

import org.stypox.dicio.skills.connectivity.ConnectivityResult
import org.stypox.dicio.skills.connectivity.Radio
import org.stypox.dicio.skills.connectivity.RadioState
import org.stypox.dicio.skills.volume.VolumeAction
import org.stypox.dicio.skills.volume.VolumeCommand
import org.stypox.dicio.skills.volume.VolumeLogic
import org.stypox.dicio.skills.volume.VolumeResult
import org.stypox.dicio.skills.volume.VolumeStream
import java.time.Duration

/** How a command left a setting that is on or off. */
enum class SwitchChange {
    /** GAR switched it. */
    TURNED,

    /** It already was as asked. */
    ALREADY,

    /** The user asked whether it is on. */
    STATE,
}

/** What happened, see [DeviceSettingsLogic]; each becomes a [DeviceSettingsOutput]. */
sealed interface DeviceSettingResult {
    /** The brightness on the slider's scale; [changed] false for a question. */
    data class Brightness(val percent: Int, val auto: Boolean, val changed: Boolean) :
        DeviceSettingResult

    data class Switch(val setting: DeviceSetting, val on: Boolean, val change: SwitchChange) :
        DeviceSettingResult

    /** [changed] false for a question. */
    data class Timeout(val timeout: Duration, val changed: Boolean) : DeviceSettingResult

    data class Ringer(val mode: RingerMode, val change: SwitchChange) : DeviceSettingResult

    /** The ringer stayed at [mode] (Do Not Disturb can keep the phone silent). */
    data class RingerUnchanged(val mode: RingerMode) : DeviceSettingResult

    data class Wifi(val status: WifiStatus) : DeviceSettingResult

    /** [headphones]: the user asked about headphones ("are my earbuds connected"). */
    data class Bluetooth(val status: BluetoothStatus, val headphones: Boolean = false) :
        DeviceSettingResult

    /** What the connectivity skill did (Wi-Fi and Bluetooth on, off and settings). */
    data class Connectivity(val result: ConnectivityResult) : DeviceSettingResult

    /** The ring volume, from the volume skill. */
    data class Volume(val result: VolumeResult) : DeviceSettingResult

    /**
     * The page of [setting] opened: "Here's airplane mode." [systemOnly] when the user asked to
     * switch it, which Android only lets the user do.
     */
    data class SettingPage(val setting: DeviceSetting, val systemOnly: Boolean) : DeviceSettingResult

    /**
     * "Opening display settings." [switch] when the user asked to switch what the page has, which
     * Android only lets the user do: "Here's night light."
     */
    data class Page(val page: SettingsPage, val switch: Boolean = false) : DeviceSettingResult

    /** GAR may not modify system settings: ask whether to open the page that allows it. */
    data class NeedsWriteAccess(val setting: DeviceSetting) : DeviceSettingResult

    /** The user said yes to [NeedsWriteAccess]'s question: its page opened. */
    data object AccessOpened : DeviceSettingResult

    /** Silent mode needs Do Not Disturb access: its page was opened. */
    data class NeedsDndAccess(val toSilent: Boolean) : DeviceSettingResult

    /** "This phone has no NFC." */
    data class NoHardware(val setting: DeviceSetting) : DeviceSettingResult

    /** Android doesn't say whether [setting] is on. */
    data class Unknown(val setting: DeviceSetting) : DeviceSettingResult

    /** Opening a page or switching a radio needs an unlocked phone. */
    data object Locked : DeviceSettingResult

    data object OpenFailed : DeviceSettingResult

    data object Failed : DeviceSettingResult
}

/**
 * What a device settings command does (gar/DESIGN.md, "Device settings"). GAR changes what an
 * app may change with the "Modify system settings" access (brightness, adaptive brightness,
 * auto-rotate, screen timeout) and the ringer mode (silent with Do Not Disturb access). Airplane
 * mode and battery saver go through Settings' voice activities while GAR is the active assistant.
 * Everything else that only Android may switch opens its page or panel, with a short line.
 * Questions work on a locked phone; pages, radios, access pages and a longer screen timeout need
 * it unlocked.
 */
class DeviceSettingsLogic(private val control: DeviceSettingsControl) {

    suspend fun run(command: DeviceSettingCommand): DeviceSettingResult {
        val setting = command.setting
        return when (setting) {
            DeviceSetting.WIFI, DeviceSetting.BLUETOOTH -> radio(setting, command.action)
            DeviceSetting.AIRPLANE_MODE, DeviceSetting.BATTERY_SAVER,
            DeviceSetting.MOBILE_DATA, DeviceSetting.HOTSPOT, DeviceSetting.NFC,
            DeviceSetting.LOCATION, DeviceSetting.DARK_MODE ->
                systemSwitch(setting, switchAction(command))
            DeviceSetting.AUTO_BRIGHTNESS, DeviceSetting.AUTO_ROTATE ->
                writableSwitch(setting, switchAction(command))
            DeviceSetting.BRIGHTNESS -> brightness(command)
            DeviceSetting.SCREEN_TIMEOUT -> timeout(command)
            DeviceSetting.RINGER, DeviceSetting.VIBRATE -> ringer(command)
        }
    }

    fun openPage(page: SettingsPage, switch: Boolean = false): DeviceSettingResult = when {
        control.isLocked() -> DeviceSettingResult.Locked
        control.open(page.intents) -> DeviceSettingResult.Page(page, switch)
        else -> DeviceSettingResult.OpenFailed
    }

    /** "Are my earbuds connected": Bluetooth's status, with the audio devices it plays on. */
    fun headphones(): DeviceSettingResult =
        DeviceSettingResult.Bluetooth(control.bluetoothStatus(), headphones = true)

    /**
     * The action for a setting that is on or off: "set airplane mode" turns it on, "turn up dark
     * mode" shows it.
     */
    private fun switchAction(command: DeviceSettingCommand): DeviceAction = when (command.action) {
        DeviceAction.SET -> DeviceAction.ON
        DeviceAction.UP, DeviceAction.DOWN -> DeviceAction.SHOW
        else -> command.action
    }

    /** On, off, or the other way round for a toggle; null to show or ask. */
    private fun wanted(action: DeviceAction, isOn: Boolean?): Boolean? = when (action) {
        DeviceAction.ON -> true
        DeviceAction.OFF -> false
        DeviceAction.TOGGLE -> isOn?.not()
        else -> null
    }

    private fun showPage(setting: DeviceSetting, systemOnly: Boolean): DeviceSettingResult = when {
        control.isLocked() -> DeviceSettingResult.Locked
        !control.open(SettingsPage.forSetting(setting)) -> DeviceSettingResult.OpenFailed
        systemOnly || setting in NAMED_PAGES -> DeviceSettingResult.SettingPage(setting, systemOnly)
        else -> DeviceSettingResult.Page(SettingsPage.pageOf(setting))
    }

    private fun radio(setting: DeviceSetting, action: DeviceAction): DeviceSettingResult {
        val radio = if (setting == DeviceSetting.WIFI) Radio.WIFI else Radio.BLUETOOTH
        if (action == DeviceAction.STATUS) {
            return if (radio == Radio.WIFI) {
                DeviceSettingResult.Wifi(control.wifiStatus())
            } else {
                DeviceSettingResult.Bluetooth(control.bluetoothStatus())
            }
        }
        val isOn = when (radio) {
            Radio.WIFI -> control.wifiStatus().enabled
            Radio.BLUETOOTH -> control.bluetoothStatus().state.enabled
        }
        val state = when (wanted(action, isOn)) {
            true -> RadioState.ON
            false -> RadioState.OFF
            null -> RadioState.SETTINGS
        }
        if (control.isLocked()) {
            return DeviceSettingResult.Locked
        }
        return DeviceSettingResult.Connectivity(control.connectivity(radio, state))
    }

    /**
     * Settings only Android may switch; airplane mode and battery saver through Settings' voice
     * activities.
     */
    private suspend fun systemSwitch(
        setting: DeviceSetting,
        action: DeviceAction,
    ): DeviceSettingResult {
        if (!control.hasHardware(setting)) {
            return DeviceSettingResult.NoHardware(setting)
        }
        val isOn = control.isOn(setting)
        if (action == DeviceAction.STATUS) {
            return if (isOn != null) {
                DeviceSettingResult.Switch(setting, isOn, SwitchChange.STATE)
            } else {
                showPage(setting, systemOnly = false) // the hotspot: its page says
            }
        }
        val on = wanted(action, isOn) ?: return showPage(setting, systemOnly = false)
        if (isOn == on) {
            return DeviceSettingResult.Switch(setting, on, SwitchChange.ALREADY)
        }
        if (control.isLocked()) {
            return DeviceSettingResult.Locked
        }
        if (setting in VOICE_SWITCHES && control.isActiveAssistant()) {
            control.switchWithVoiceActivity(setting, on)
            if (control.isOn(setting) == on) {
                return DeviceSettingResult.Switch(setting, on, SwitchChange.TURNED)
            }
        }
        return showPage(setting, systemOnly = true)
    }

    /** Adaptive brightness and auto-rotate, with the "Modify system settings" access. */
    private fun writableSwitch(setting: DeviceSetting, action: DeviceAction): DeviceSettingResult {
        val rotate = setting == DeviceSetting.AUTO_ROTATE
        val read = { if (rotate) control.isAutoRotate() else control.isAutoBrightness() }
        val isOn = read()
        if (action == DeviceAction.STATUS) {
            return isOn?.let { DeviceSettingResult.Switch(setting, it, SwitchChange.STATE) }
                ?: DeviceSettingResult.Unknown(setting)
        }
        val on = wanted(action, isOn) ?: return showPage(setting, systemOnly = false)
        if (isOn == on) {
            return DeviceSettingResult.Switch(setting, on, SwitchChange.ALREADY)
        }
        return write(setting) {
            if (rotate) control.setAutoRotate(on) else control.setAutoBrightness(on)
            if (read() == on) {
                DeviceSettingResult.Switch(setting, on, SwitchChange.TURNED)
            } else {
                DeviceSettingResult.Failed
            }
        }
    }

    /** Runs [change] with the "Modify system settings" access, or asks for it. */
    private fun write(setting: DeviceSetting, change: () -> DeviceSettingResult): DeviceSettingResult {
        val needsAccess = {
            if (control.isLocked()) {
                DeviceSettingResult.Locked
            } else {
                DeviceSettingResult.NeedsWriteAccess(setting)
            }
        }
        if (!control.canWriteSettings()) {
            return needsAccess()
        }
        return try {
            change()
        } catch (e: SecurityException) {
            needsAccess()
        } catch (e: IllegalArgumentException) {
            DeviceSettingResult.Failed // a value the setting's validator refused
        }
    }

    private fun brightness(command: DeviceSettingCommand): DeviceSettingResult {
        val current = control.brightness()
            ?: return DeviceSettingResult.Unknown(DeviceSetting.BRIGHTNESS)
        val percent = BrightnessCurve.percentOf(current)
        val auto = control.isAutoBrightness() == true
        val target = when (command.action) {
            DeviceAction.STATUS ->
                return DeviceSettingResult.Brightness(percent, auto, changed = false)
            DeviceAction.UP -> percent + step(command.level)
            DeviceAction.DOWN -> percent - step(command.level)
            DeviceAction.SET -> when (val level = command.level) {
                is DeviceLevel.Amount -> levelPercent(level)
                DeviceLevel.Max -> 100
                DeviceLevel.Min -> 0
                // GAR asks for a level it can't read before this runs
                is DeviceLevel.Time, null -> return DeviceSettingResult.Failed
            }
            // "turn off the brightness" has no clear meaning: the brightness slider is on the page
            else -> return showPage(DeviceSetting.BRIGHTNESS, systemOnly = false)
        }.coerceIn(0, 100)
        return write(DeviceSetting.BRIGHTNESS) {
            control.setBrightness(BrightnessCurve.settingOf(target))
            val now = control.brightness() ?: return@write DeviceSettingResult.Failed
            DeviceSettingResult.Brightness(BrightnessCurve.percentOf(now), auto, changed = true)
        }
    }

    /** How far up or down: [BRIGHTNESS_STEP] points of the slider, or the amount said. */
    private fun step(level: DeviceLevel?): Int = when (level) {
        is DeviceLevel.Amount -> levelPercent(level).coerceAtLeast(1)
        DeviceLevel.Max -> 100
        else -> BRIGHTNESS_STEP
    }

    /** A level read like the volume's: up to ten out of ten, above ten a percentage. */
    private fun levelPercent(level: DeviceLevel.Amount): Int =
        VolumeLogic.levelIndex(level.number, 0, 100)

    private fun timeout(command: DeviceSettingCommand): DeviceSettingResult {
        val current = control.screenTimeout()
            ?: return DeviceSettingResult.Unknown(DeviceSetting.SCREEN_TIMEOUT)
        val target = when (command.action) {
            DeviceAction.STATUS -> return DeviceSettingResult.Timeout(current, changed = false)
            DeviceAction.UP -> TIMEOUTS.firstOrNull { it > current } ?: TIMEOUTS.last()
            DeviceAction.DOWN -> TIMEOUTS.lastOrNull { it < current } ?: TIMEOUTS.first()
            DeviceAction.SET -> when (val level = command.level) {
                is DeviceLevel.Time -> level.duration
                DeviceLevel.Max -> TIMEOUTS.last()
                DeviceLevel.Min -> TIMEOUTS.first()
                is DeviceLevel.Amount, null -> return DeviceSettingResult.Failed
            }
            else -> return showPage(DeviceSetting.SCREEN_TIMEOUT, systemOnly = false)
        }.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)
        // a longer timeout keeps an unlocked phone on and unlocked longer: not from the lock
        // screen, where anyone holding the phone could ask (shorter is fine)
        if (target > current && control.isLocked()) {
            return DeviceSettingResult.Locked
        }
        return write(DeviceSetting.SCREEN_TIMEOUT) {
            control.setScreenTimeout(target)
            val now = control.screenTimeout() ?: return@write DeviceSettingResult.Failed
            DeviceSettingResult.Timeout(now, changed = true)
        }
    }

    private fun ringer(command: DeviceSettingCommand): DeviceSettingResult {
        val action = command.action
        if (command.setting == DeviceSetting.RINGER &&
            action in setOf(DeviceAction.UP, DeviceAction.DOWN, DeviceAction.SET)
        ) {
            return ringVolume(command)
        }
        val current = control.ringerMode() ?: return DeviceSettingResult.Failed
        val target = when (command.setting) {
            DeviceSetting.VIBRATE -> when (action) {
                DeviceAction.ON -> RingerMode.VIBRATE
                DeviceAction.OFF -> RingerMode.NORMAL
                DeviceAction.TOGGLE ->
                    if (current == RingerMode.VIBRATE) RingerMode.NORMAL else RingerMode.VIBRATE
                DeviceAction.STATUS -> return DeviceSettingResult.Ringer(current, SwitchChange.STATE)
                else -> return showPage(command.setting, systemOnly = false)
            }
            else -> when (action) {
                DeviceAction.ON -> RingerMode.NORMAL
                DeviceAction.OFF -> RingerMode.SILENT
                DeviceAction.TOGGLE ->
                    if (current == RingerMode.NORMAL) RingerMode.SILENT else RingerMode.NORMAL
                DeviceAction.STATUS -> return DeviceSettingResult.Ringer(current, SwitchChange.STATE)
                else -> return showPage(command.setting, systemOnly = false)
            }
        }
        if (current == target) {
            return DeviceSettingResult.Ringer(target, SwitchChange.ALREADY)
        }
        // going into or out of silent mode switches Do Not Disturb, which needs its access
        val needsDndAccess = (current == RingerMode.SILENT) != (target == RingerMode.SILENT)
        val noAccess = {
            when {
                control.isLocked() -> DeviceSettingResult.Locked
                control.openDndAccess() -> DeviceSettingResult.NeedsDndAccess(target == RingerMode.SILENT)
                else -> DeviceSettingResult.OpenFailed
            }
        }
        if (needsDndAccess && !control.hasDndAccess()) {
            return noAccess()
        }
        try {
            control.setRingerMode(target)
        } catch (e: SecurityException) {
            return noAccess()
        }
        val now = control.ringerMode() ?: return DeviceSettingResult.Failed
        return if (now == target) {
            DeviceSettingResult.Ringer(now, SwitchChange.TURNED)
        } else {
            DeviceSettingResult.RingerUnchanged(now)
        }
    }

    /** "Turn up the ringer": the volume skill's logic on the ring stream. */
    private fun ringVolume(command: DeviceSettingCommand): DeviceSettingResult {
        val volume = control.volume() ?: return DeviceSettingResult.Failed
        val number = (command.level as? DeviceLevel.Amount)?.number
        val volumeCommand = when (command.action) {
            DeviceAction.UP -> VolumeCommand(VolumeAction.UP, number, null, VolumeStream.RING)
            DeviceAction.DOWN -> VolumeCommand(VolumeAction.DOWN, number, null, VolumeStream.RING)
            else -> when (command.level) {
                DeviceLevel.Max -> VolumeCommand(VolumeAction.MAX, null, null, VolumeStream.RING)
                DeviceLevel.Min -> VolumeCommand(VolumeAction.MUTE, null, null, VolumeStream.RING)
                else -> VolumeCommand(null, null, number, VolumeStream.RING)
            }
        }
        return DeviceSettingResult.Volume(VolumeLogic.apply(volume, volumeCommand))
    }

    companion object {
        /** Points of the brightness slider that "turn up the brightness" moves. */
        const val BRIGHTNESS_STEP = 10

        /** Settings' screen timeout choices, for "longer" and "shorter". */
        val TIMEOUTS: List<Duration> = listOf(
            Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(1),
            Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10),
            Duration.ofMinutes(30),
        )

        /** Shorter than this, the phone is hard to use. */
        val MIN_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** Settings' longest choice; a longer one keeps the screen on for hours. */
        val MAX_TIMEOUT: Duration = Duration.ofMinutes(30)

        /** Only these voice activities exist in Settings (besides Do Not Disturb's). */
        private val VOICE_SWITCHES = setOf(DeviceSetting.AIRPLANE_MODE, DeviceSetting.BATTERY_SAVER)

        /** Settings whose page GAR names by the setting: "Here's airplane mode." */
        private val NAMED_PAGES = setOf(
            DeviceSetting.AIRPLANE_MODE, DeviceSetting.MOBILE_DATA, DeviceSetting.HOTSPOT,
            DeviceSetting.NFC, DeviceSetting.LOCATION, DeviceSetting.BATTERY_SAVER,
            DeviceSetting.DARK_MODE,
        )
    }
}
