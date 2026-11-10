package org.stypox.dicio.skills.device_settings

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.getSystemService
import cc.aaro.gar.assist.GarAssist
import org.stypox.dicio.skills.connectivity.ActivityIntent
import org.stypox.dicio.skills.connectivity.BluetoothState
import org.stypox.dicio.skills.connectivity.ConnectivityResult
import org.stypox.dicio.skills.connectivity.ConnectivitySkill
import org.stypox.dicio.skills.connectivity.Radio
import org.stypox.dicio.skills.connectivity.RadioState
import org.stypox.dicio.skills.do_not_disturb.DoNotDisturbIntents
import org.stypox.dicio.skills.volume.AndroidVolumeControl
import org.stypox.dicio.skills.volume.VolumeControl
import java.time.Duration

/** The phone's ringer mode, as [AudioManager.getRingerMode] says it. */
enum class RingerMode { NORMAL, VIBRATE, SILENT }

/** @param enabled null when Android didn't say (no Wi-Fi state access) */
data class WifiStatus(val enabled: Boolean?, val connected: Boolean)

/** @param devices the names of the Bluetooth audio devices that are connected now */
data class BluetoothStatus(val state: BluetoothState, val devices: List<String>)

/**
 * What the device settings skill does on the device, so that its decisions can be tested.
 * Reads return null when Android doesn't say; writes of Android's system settings throw
 * [SecurityException] without the "Modify system settings" access.
 */
interface DeviceSettingsControl {
    /** Whether the phone is locked (with a PIN, pattern, ...). */
    fun isLocked(): Boolean

    /** Whether GAR may change system settings: brightness, screen timeout, auto-rotate. */
    fun canWriteSettings(): Boolean

    /** Opens the page where the user allows GAR to modify system settings. */
    fun openWriteSettingsAccess(): Boolean

    /** `Settings.System.SCREEN_BRIGHTNESS`, 0 to 255. */
    fun brightness(): Int?
    fun setBrightness(value: Int)
    fun isAutoBrightness(): Boolean?
    fun setAutoBrightness(on: Boolean)
    fun isAutoRotate(): Boolean?
    fun setAutoRotate(on: Boolean)
    fun screenTimeout(): Duration?
    fun setScreenTimeout(timeout: Duration)

    fun ringerMode(): RingerMode?

    /**
     * @throws SecurityException without Do Not Disturb access when the phone goes into or out of
     * silent mode
     */
    fun setRingerMode(mode: RingerMode)

    /** Whether GAR may change Do Not Disturb, which silent mode needs. */
    fun hasDndAccess(): Boolean

    /** Opens the page where the user allows GAR to change Do Not Disturb. */
    fun openDndAccess(): Boolean

    /** The volume skill's control, for the ring volume. */
    fun volume(): VolumeControl?

    /**
     * Whether a setting that Android switches itself is on: airplane mode, mobile data, NFC,
     * location, battery saver, dark theme. Null when Android doesn't say (the hotspot never).
     */
    fun isOn(setting: DeviceSetting): Boolean?

    /** False when the phone has no such hardware (NFC). */
    fun hasHardware(setting: DeviceSetting): Boolean

    fun wifiStatus(): WifiStatus
    fun bluetoothStatus(): BluetoothStatus

    /** Opens the system's controls of Wi-Fi or Bluetooth, as the connectivity skill does. */
    fun connectivity(radio: Radio, state: RadioState): ConnectivityResult

    /** Whether GAR is the active digital assistant, which may run Settings' voice activities. */
    fun isActiveAssistant(): Boolean

    /**
     * Asks Settings to switch airplane mode or battery saver through its voice activity, which
     * only the active assistant can start; whether it worked is read from [isOn] afterwards.
     */
    suspend fun switchWithVoiceActivity(setting: DeviceSetting, on: Boolean)

    /** Starts the first of [intents] that can be started. @return false if none could */
    fun open(intents: List<ActivityIntent>): Boolean
}

/** Settings' voice activities: the same path as Do Not Disturb off ([DoNotDisturbIntents]). */
object DeviceSettingsIntents {
    fun voiceSwitch(setting: DeviceSetting, on: Boolean): ActivityIntent? = when (setting) {
        DeviceSetting.AIRPLANE_MODE -> ActivityIntent(
            Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE,
            mapOf(Settings.EXTRA_AIRPLANE_MODE_ENABLED to on),
        )
        DeviceSetting.BATTERY_SAVER -> ActivityIntent(
            Settings.ACTION_VOICE_CONTROL_BATTERY_SAVER_MODE,
            mapOf(Settings.EXTRA_BATTERY_SAVER_MODE_ENABLED to on),
        )
        else -> null
    }

    /** GAR's own "Modify system settings" switch. */
    fun writeSettingsAccess(packageName: String) =
        ActivityIntent(Settings.ACTION_MANAGE_WRITE_SETTINGS, data = "package:$packageName")
}

class AndroidDeviceSettingsControl(private val context: Context) : DeviceSettingsControl {
    private val resolver get() = context.contentResolver
    private val audioManager get() = context.getSystemService<AudioManager>()

    override fun isLocked(): Boolean =
        context.getSystemService<KeyguardManager>()?.isDeviceLocked == true

    override fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    override fun openWriteSettingsAccess(): Boolean =
        DeviceSettingsIntents.writeSettingsAccess(context.packageName).start(context)

    private fun systemInt(name: String): Int? = try {
        Settings.System.getInt(resolver, name)
    } catch (e: Settings.SettingNotFoundException) {
        null
    }

    private fun putSystemInt(name: String, value: Int) {
        Settings.System.putInt(resolver, name, value)
    }

    override fun brightness(): Int? = systemInt(Settings.System.SCREEN_BRIGHTNESS)

    override fun setBrightness(value: Int) = putSystemInt(Settings.System.SCREEN_BRIGHTNESS, value)

    override fun isAutoBrightness(): Boolean? = systemInt(Settings.System.SCREEN_BRIGHTNESS_MODE)
        ?.let { it == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }

    override fun setAutoBrightness(on: Boolean) = putSystemInt(
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        if (on) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } else {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        },
    )

    override fun isAutoRotate(): Boolean? =
        systemInt(Settings.System.ACCELEROMETER_ROTATION)?.let { it != 0 }

    override fun setAutoRotate(on: Boolean) =
        putSystemInt(Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)

    override fun screenTimeout(): Duration? =
        systemInt(Settings.System.SCREEN_OFF_TIMEOUT)?.let { Duration.ofMillis(it.toLong()) }

    override fun setScreenTimeout(timeout: Duration) =
        putSystemInt(Settings.System.SCREEN_OFF_TIMEOUT, timeout.toMillis().toInt())

    override fun ringerMode(): RingerMode? = when (audioManager?.ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
        AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
        else -> null
    }

    override fun setRingerMode(mode: RingerMode) {
        audioManager?.ringerMode = when (mode) {
            RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
        }
    }

    override fun hasDndAccess(): Boolean =
        context.getSystemService<NotificationManager>()?.isNotificationPolicyAccessGranted == true

    override fun openDndAccess(): Boolean = DoNotDisturbIntents.ACCESS_SETTINGS.start(context)

    override fun volume(): VolumeControl? = audioManager?.let { AndroidVolumeControl(it) }

    override fun isOn(setting: DeviceSetting): Boolean? = try {
        when (setting) {
            DeviceSetting.AIRPLANE_MODE ->
                Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            DeviceSetting.BATTERY_SAVER -> context.getSystemService<PowerManager>()?.isPowerSaveMode
            DeviceSetting.LOCATION -> context.getSystemService<LocationManager>()?.isLocationEnabled
            DeviceSetting.NFC -> NfcAdapter.getDefaultAdapter(context)?.isEnabled
            // ACCESS_NETWORK_STATE is enough
            DeviceSetting.MOBILE_DATA -> context.getSystemService<TelephonyManager>()?.isDataEnabled
            // the application's configuration follows the system's dark theme
            DeviceSetting.DARK_MODE -> (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> null
        }
    } catch (e: RuntimeException) {
        // a SecurityException, or UnsupportedOperationException on phones without telephony
        Log.w(TAG, "Can't read whether $setting is on", e)
        null
    }

    override fun hasHardware(setting: DeviceSetting): Boolean = when (setting) {
        DeviceSetting.NFC -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
        else -> true
    }

    override fun wifiStatus(): WifiStatus {
        val enabled = try {
            context.applicationContext.getSystemService<WifiManager>()?.isWifiEnabled
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't read the Wi-Fi state", e)
            null
        }
        val connected = try {
            val manager = context.getSystemService<ConnectivityManager>()
            manager?.getNetworkCapabilities(manager.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't read the network", e)
            false
        }
        return WifiStatus(enabled ?: if (connected) true else null, connected)
    }

    override fun bluetoothStatus(): BluetoothStatus {
        val state = ConnectivitySkill.bluetoothState(context)
        // no permission needed: what plays where is the audio framework's
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .filter { it.type in BLUETOOTH_AUDIO_TYPES }
            .mapNotNull { it.productName?.toString()?.trim()?.takeIf { name -> name.isNotEmpty() } }
            .distinct()
        return BluetoothStatus(state, devices)
    }

    override fun connectivity(radio: Radio, state: RadioState): ConnectivityResult =
        ConnectivitySkill.execute(context, radio, state)

    override fun isActiveAssistant(): Boolean = try {
        GarAssist.isActiveAssistant(context)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Can't tell whether GAR is the assistant", e)
        false
    }

    override suspend fun switchWithVoiceActivity(setting: DeviceSetting, on: Boolean) {
        // the activity's own answer doesn't matter, only the setting afterwards
        DeviceSettingsIntents.voiceSwitch(setting, on)?.startAsVoiceActivity()
    }

    override fun open(intents: List<ActivityIntent>): Boolean =
        ActivityIntent.startFirst(context, intents)

    companion object {
        private const val TAG = "DeviceSettingsControl"

        private val BLUETOOTH_AUDIO_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
    }
}
