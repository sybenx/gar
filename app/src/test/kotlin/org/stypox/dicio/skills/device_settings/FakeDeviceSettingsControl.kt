package org.stypox.dicio.skills.device_settings

import org.stypox.dicio.skills.connectivity.ActivityIntent
import org.stypox.dicio.skills.connectivity.BluetoothState
import org.stypox.dicio.skills.connectivity.ConnectivityResult
import org.stypox.dicio.skills.connectivity.Radio
import org.stypox.dicio.skills.connectivity.RadioState
import org.stypox.dicio.skills.volume.VolumeControl
import java.time.Duration

/**
 * The phone as the device settings skill sees it. Writes of system settings throw without
 * [writeAccess], as Settings.System does; silent mode without [dndAccess] throws as AudioManager
 * does for an app without Do Not Disturb access.
 */
class FakeDeviceSettingsControl(
    var locked: Boolean = false,
    var writeAccess: Boolean = true,
    var dndAccess: Boolean = true,
    var brightness: Int? = 22,
    var autoBrightness: Boolean? = false,
    var autoRotate: Boolean? = true,
    var timeout: Duration? = Duration.ofSeconds(30),
    var ringer: RingerMode? = RingerMode.NORMAL,
    val switches: MutableMap<DeviceSetting, Boolean?> = mutableMapOf(
        DeviceSetting.AIRPLANE_MODE to false,
        DeviceSetting.BATTERY_SAVER to false,
        DeviceSetting.LOCATION to true,
        DeviceSetting.NFC to true,
        DeviceSetting.MOBILE_DATA to true,
        DeviceSetting.DARK_MODE to false,
    ),
    var nfcHardware: Boolean = true,
    var wifi: WifiStatus = WifiStatus(enabled = true, connected = true),
    var bluetooth: BluetoothStatus =
        BluetoothStatus(BluetoothState(present = true, enabled = true), listOf("Pixel Buds")),
    var assistant: Boolean = false,
    /** Whether Settings' voice activity really switches the setting. */
    private val voiceWorks: Boolean = true,
    private val pagesOpen: Boolean = true,
    /** A ringer change that the system ignores (Do Not Disturb keeps the phone silent). */
    private val ringerStuck: Boolean = false,
    private val volume: VolumeControl? = null,
) : DeviceSettingsControl {
    val opened = mutableListOf<List<ActivityIntent>>()
    val radios = mutableListOf<Pair<Radio, RadioState>>()
    val voiceSwitches = mutableListOf<Pair<DeviceSetting, Boolean>>()
    var openedWriteAccess = false
    var openedDndAccess = false

    override fun isLocked() = locked
    override fun canWriteSettings() = writeAccess

    override fun openWriteSettingsAccess(): Boolean {
        openedWriteAccess = true
        return pagesOpen
    }

    private fun checkWrite() {
        if (!writeAccess) throw SecurityException("WRITE_SETTINGS")
    }

    override fun brightness() = brightness
    override fun setBrightness(value: Int) {
        checkWrite()
        brightness = value
    }

    override fun isAutoBrightness() = autoBrightness
    override fun setAutoBrightness(on: Boolean) {
        checkWrite()
        autoBrightness = on
    }

    override fun isAutoRotate() = autoRotate
    override fun setAutoRotate(on: Boolean) {
        checkWrite()
        autoRotate = on
    }

    override fun screenTimeout() = timeout
    override fun setScreenTimeout(timeout: Duration) {
        checkWrite()
        this.timeout = timeout
    }

    override fun ringerMode() = ringer
    override fun setRingerMode(mode: RingerMode) {
        if (!dndAccess && (ringer == RingerMode.SILENT) != (mode == RingerMode.SILENT)) {
            throw SecurityException("Not allowed to change Do Not Disturb state")
        }
        if (!ringerStuck) ringer = mode
    }

    override fun hasDndAccess() = dndAccess

    override fun openDndAccess(): Boolean {
        openedDndAccess = true
        return pagesOpen
    }

    override fun volume() = volume

    override fun isOn(setting: DeviceSetting) = switches[setting]
    override fun hasHardware(setting: DeviceSetting) = setting != DeviceSetting.NFC || nfcHardware
    override fun wifiStatus() = wifi
    override fun bluetoothStatus() = bluetooth

    override fun connectivity(radio: Radio, state: RadioState): ConnectivityResult {
        radios += Pair(radio, state)
        return ConnectivityResult.WIFI_PANEL
    }

    override fun isActiveAssistant() = assistant

    override suspend fun switchWithVoiceActivity(setting: DeviceSetting, on: Boolean) {
        voiceSwitches += Pair(setting, on)
        if (voiceWorks) switches[setting] = on
    }

    override fun open(intents: List<ActivityIntent>): Boolean {
        opened += intents
        return pagesOpen
    }
}
