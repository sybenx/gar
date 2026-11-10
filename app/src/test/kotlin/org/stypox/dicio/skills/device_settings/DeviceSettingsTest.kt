package org.stypox.dicio.skills.device_settings

import android.media.AudioManager
import cc.aaro.gar.router.resolve.ResolvedNumber
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.stypox.dicio.skills.connectivity.ActivityIntent
import org.stypox.dicio.skills.connectivity.ConnectivityLogic
import org.stypox.dicio.skills.connectivity.Radio
import org.stypox.dicio.skills.connectivity.RadioState
import org.stypox.dicio.skills.volume.FakeVolumeControl
import org.stypox.dicio.skills.volume.VolumeResult
import org.stypox.dicio.skills.volume.VolumeStream
import java.time.Duration

private fun percent(value: Double) = ResolvedNumber(value, percent = true, isInteger = true)
private fun plain(value: Double) = ResolvedNumber(value, percent = false, isInteger = true)

private suspend fun run(
    control: FakeDeviceSettingsControl,
    setting: DeviceSetting,
    action: DeviceAction,
    level: DeviceLevel? = null,
) = DeviceSettingsLogic(control).run(DeviceSettingCommand(setting, action, level))

class DeviceSettingsTest : StringSpec({
    "the brightness slider's curve is SettingsLib's: half way is a twelfth of the range" {
        BrightnessCurve.settingOf(0) shouldBe 1
        BrightnessCurve.settingOf(50) shouldBe 22
        BrightnessCurve.settingOf(100) shouldBe 255
        BrightnessCurve.percentOf(1) shouldBe 0
        BrightnessCurve.percentOf(22) shouldBe 50
        BrightnessCurve.percentOf(255) shouldBe 100
        // off (0) reads as the darkest setting, out of range values are clamped
        BrightnessCurve.percentOf(0) shouldBe 0
        BrightnessCurve.settingOf(150) shouldBe 255
        // a position survives the round trip within two points, except at the dark end, where
        // one step of the stored value is several points of the slider; higher is never darker
        for (position in 20..100) {
            withClue(position) {
                BrightnessCurve.percentOf(BrightnessCurve.settingOf(position)) shouldBeInRange
                    (position - 2)..(position + 2)
            }
        }
        for (position in 1..100) {
            withClue(position) {
                (BrightnessCurve.settingOf(position) >= BrightnessCurve.settingOf(position - 1)) shouldBe true
            }
        }
        // ten points up from the darkest setting is a step up
        BrightnessCurve.settingOf(BrightnessCurve.percentOf(1) + 10) shouldBe 2
    }

    "brightness up and down move ten points of the slider, or the amount said" {
        val control = FakeDeviceSettingsControl(brightness = 22) // 50 percent
        run(control, DeviceSetting.BRIGHTNESS, DeviceAction.UP) shouldBe
            DeviceSettingResult.Brightness(60, auto = false, changed = true)
        control.brightness shouldBe BrightnessCurve.settingOf(60)
        run(control, DeviceSetting.BRIGHTNESS, DeviceAction.DOWN, DeviceLevel.Amount(percent(30.0)))
        control.brightness shouldBe BrightnessCurve.settingOf(30)
        // not below the darkest setting, which isn't off
        run(control, DeviceSetting.BRIGHTNESS, DeviceAction.DOWN, DeviceLevel.Amount(percent(90.0)))
        control.brightness shouldBe BrightnessCurve.MIN_SETTING
    }

    "brightness set reads levels like the volume: up to ten out of ten, max and min" {
        val control = FakeDeviceSettingsControl(autoBrightness = true)
        run(control, DeviceSetting.BRIGHTNESS, DeviceAction.SET, DeviceLevel.Amount(percent(50.0))) shouldBe
            DeviceSettingResult.Brightness(50, auto = true, changed = true)
        run(control, DeviceSetting.BRIGHTNESS, DeviceAction.SET, DeviceLevel.Amount(plain(7.0)))
        BrightnessCurve.percentOf(control.brightness!!) shouldBe 70
        run(control, DeviceSetting.BRIGHTNESS, DeviceAction.SET, DeviceLevel.Max)
        control.brightness shouldBe 255
        run(control, DeviceSetting.BRIGHTNESS, DeviceAction.SET, DeviceLevel.Min)
        control.brightness shouldBe 1
        // adaptive brightness stays as it was, as with the slider
        control.autoBrightness shouldBe true
    }

    "the brightness question reads the slider's position, also without the access" {
        val control = FakeDeviceSettingsControl(writeAccess = false, brightness = 22, autoBrightness = true)
        run(control, DeviceSetting.BRIGHTNESS, DeviceAction.STATUS) shouldBe
            DeviceSettingResult.Brightness(50, auto = true, changed = false)
    }

    "without the Modify system settings access GAR asks for it, or to unlock first" {
        val control = FakeDeviceSettingsControl(writeAccess = false)
        run(control, DeviceSetting.BRIGHTNESS, DeviceAction.UP) shouldBe
            DeviceSettingResult.NeedsWriteAccess(DeviceSetting.BRIGHTNESS)
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.SET, DeviceLevel.Time(Duration.ofMinutes(2))) shouldBe
            DeviceSettingResult.NeedsWriteAccess(DeviceSetting.SCREEN_TIMEOUT)
        run(control, DeviceSetting.AUTO_ROTATE, DeviceAction.OFF) shouldBe
            DeviceSettingResult.NeedsWriteAccess(DeviceSetting.AUTO_ROTATE)
        control.brightness shouldBe 22
        control.autoRotate shouldBe true
        control.locked = true
        run(control, DeviceSetting.BRIGHTNESS, DeviceAction.UP) shouldBe DeviceSettingResult.Locked
    }

    "auto-rotate and adaptive brightness switch, or say they already are" {
        val control = FakeDeviceSettingsControl(autoRotate = true, autoBrightness = false)
        run(control, DeviceSetting.AUTO_ROTATE, DeviceAction.ON) shouldBe
            DeviceSettingResult.Switch(DeviceSetting.AUTO_ROTATE, true, SwitchChange.ALREADY)
        run(control, DeviceSetting.AUTO_ROTATE, DeviceAction.TOGGLE) shouldBe
            DeviceSettingResult.Switch(DeviceSetting.AUTO_ROTATE, false, SwitchChange.TURNED)
        control.autoRotate shouldBe false
        run(control, DeviceSetting.AUTO_BRIGHTNESS, DeviceAction.SET) shouldBe
            DeviceSettingResult.Switch(DeviceSetting.AUTO_BRIGHTNESS, true, SwitchChange.TURNED)
        run(control, DeviceSetting.AUTO_BRIGHTNESS, DeviceAction.STATUS) shouldBe
            DeviceSettingResult.Switch(DeviceSetting.AUTO_BRIGHTNESS, true, SwitchChange.STATE)
        // on a locked phone too: like the brightness, nothing private
        control.locked = true
        run(control, DeviceSetting.AUTO_ROTATE, DeviceAction.ON) shouldBe
            DeviceSettingResult.Switch(DeviceSetting.AUTO_ROTATE, true, SwitchChange.TURNED)
    }

    "the screen timeout: set within Settings' range, longer and shorter by its choices" {
        val control = FakeDeviceSettingsControl(timeout = Duration.ofSeconds(30))
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.STATUS) shouldBe
            DeviceSettingResult.Timeout(Duration.ofSeconds(30), changed = false)
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.SET, DeviceLevel.Time(Duration.ofMinutes(2))) shouldBe
            DeviceSettingResult.Timeout(Duration.ofMinutes(2), changed = true)
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.UP)
        control.timeout shouldBe Duration.ofMinutes(5)
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.DOWN)
        control.timeout shouldBe Duration.ofMinutes(2)
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.SET, DeviceLevel.Time(Duration.ofHours(2)))
        control.timeout shouldBe DeviceSettingsLogic.MAX_TIMEOUT
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.SET, DeviceLevel.Time(Duration.ofSeconds(3)))
        control.timeout shouldBe DeviceSettingsLogic.MIN_TIMEOUT
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.SET, DeviceLevel.Max)
        control.timeout shouldBe Duration.ofMinutes(30)
    }

    "on a locked phone the screen timeout only gets shorter" {
        // a longer one would keep the phone unlocked longer once the owner unlocks it
        val control = FakeDeviceSettingsControl(timeout = Duration.ofMinutes(1), locked = true)
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.UP) shouldBe DeviceSettingResult.Locked
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.SET, DeviceLevel.Time(Duration.ofMinutes(10))) shouldBe
            DeviceSettingResult.Locked
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.SET, DeviceLevel.Max) shouldBe
            DeviceSettingResult.Locked
        control.timeout shouldBe Duration.ofMinutes(1)
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.STATUS) shouldBe
            DeviceSettingResult.Timeout(Duration.ofMinutes(1), changed = false)
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.DOWN) shouldBe
            DeviceSettingResult.Timeout(Duration.ofSeconds(30), changed = true)
        run(control, DeviceSetting.SCREEN_TIMEOUT, DeviceAction.SET, DeviceLevel.Min) shouldBe
            DeviceSettingResult.Timeout(Duration.ofSeconds(15), changed = true)
    }

    "the ringer: vibrate and ring without access, silent with Do Not Disturb access" {
        val control = FakeDeviceSettingsControl(dndAccess = false)
        run(control, DeviceSetting.VIBRATE, DeviceAction.ON) shouldBe
            DeviceSettingResult.Ringer(RingerMode.VIBRATE, SwitchChange.TURNED)
        run(control, DeviceSetting.VIBRATE, DeviceAction.ON) shouldBe
            DeviceSettingResult.Ringer(RingerMode.VIBRATE, SwitchChange.ALREADY)
        run(control, DeviceSetting.RINGER, DeviceAction.STATUS) shouldBe
            DeviceSettingResult.Ringer(RingerMode.VIBRATE, SwitchChange.STATE)
        // silent needs the access the Do Not Disturb skill asks for: its page opens
        run(control, DeviceSetting.RINGER, DeviceAction.OFF) shouldBe
            DeviceSettingResult.NeedsDndAccess(toSilent = true)
        control.openedDndAccess shouldBe true
        control.ringer shouldBe RingerMode.VIBRATE
        run(control, DeviceSetting.VIBRATE, DeviceAction.OFF) shouldBe
            DeviceSettingResult.Ringer(RingerMode.NORMAL, SwitchChange.TURNED)

        val allowed = FakeDeviceSettingsControl(dndAccess = true)
        run(allowed, DeviceSetting.RINGER, DeviceAction.OFF) shouldBe
            DeviceSettingResult.Ringer(RingerMode.SILENT, SwitchChange.TURNED)
        run(allowed, DeviceSetting.RINGER, DeviceAction.TOGGLE) shouldBe
            DeviceSettingResult.Ringer(RingerMode.NORMAL, SwitchChange.TURNED)
    }

    "leaving silent mode needs the access too, and a ringer the system keeps silent says so" {
        val silent = FakeDeviceSettingsControl(ringer = RingerMode.SILENT, dndAccess = false)
        run(silent, DeviceSetting.RINGER, DeviceAction.ON) shouldBe
            DeviceSettingResult.NeedsDndAccess(toSilent = false)
        silent.locked = true
        run(silent, DeviceSetting.VIBRATE, DeviceAction.ON) shouldBe DeviceSettingResult.Locked

        val stuck = FakeDeviceSettingsControl(ringer = RingerMode.SILENT, ringerStuck = true)
        run(stuck, DeviceSetting.RINGER, DeviceAction.ON) shouldBe
            DeviceSettingResult.RingerUnchanged(RingerMode.SILENT)
    }

    "the ringer's level is the ring volume, through the volume skill" {
        val volume = FakeVolumeControl(index = 10)
        val control = FakeDeviceSettingsControl(volume = volume)
        run(control, DeviceSetting.RINGER, DeviceAction.UP) shouldBe
            DeviceSettingResult.Volume(VolumeResult.Level(VolumeStream.RING, 44, muted = false))
        volume.streams shouldContainExactly listOf(AudioManager.STREAM_RING)
        run(control, DeviceSetting.RINGER, DeviceAction.SET, DeviceLevel.Amount(percent(80.0))) shouldBe
            DeviceSettingResult.Volume(VolumeResult.Level(VolumeStream.RING, 80, muted = false))
    }

    "airplane mode and battery saver go through Settings' voice activities as the assistant" {
        val control = FakeDeviceSettingsControl(assistant = true)
        run(control, DeviceSetting.AIRPLANE_MODE, DeviceAction.ON) shouldBe
            DeviceSettingResult.Switch(DeviceSetting.AIRPLANE_MODE, true, SwitchChange.TURNED)
        run(control, DeviceSetting.BATTERY_SAVER, DeviceAction.TOGGLE) shouldBe
            DeviceSettingResult.Switch(DeviceSetting.BATTERY_SAVER, true, SwitchChange.TURNED)
        control.voiceSwitches shouldContainExactly listOf(
            DeviceSetting.AIRPLANE_MODE to true, DeviceSetting.BATTERY_SAVER to true,
        )
        control.opened.shouldBeEmpty()
        run(control, DeviceSetting.AIRPLANE_MODE, DeviceAction.ON) shouldBe
            DeviceSettingResult.Switch(DeviceSetting.AIRPLANE_MODE, true, SwitchChange.ALREADY)
    }

    "otherwise, or when the voice activity changed nothing, the setting's page opens" {
        val notAssistant = FakeDeviceSettingsControl()
        run(notAssistant, DeviceSetting.AIRPLANE_MODE, DeviceAction.ON) shouldBe
            DeviceSettingResult.SettingPage(DeviceSetting.AIRPLANE_MODE, systemOnly = true)
        notAssistant.opened shouldContainExactly listOf(SettingsPage.forSetting(DeviceSetting.AIRPLANE_MODE))
        notAssistant.voiceSwitches.shouldBeEmpty()

        val failing = FakeDeviceSettingsControl(assistant = true, voiceWorks = false)
        run(failing, DeviceSetting.BATTERY_SAVER, DeviceAction.ON) shouldBe
            DeviceSettingResult.SettingPage(DeviceSetting.BATTERY_SAVER, systemOnly = true)
        failing.voiceSwitches shouldContainExactly listOf(DeviceSetting.BATTERY_SAVER to true)

        // never on a locked phone: airplane mode would cut a lost phone off
        val locked = FakeDeviceSettingsControl(assistant = true, locked = true)
        run(locked, DeviceSetting.AIRPLANE_MODE, DeviceAction.ON) shouldBe DeviceSettingResult.Locked
        locked.voiceSwitches.shouldBeEmpty()
    }

    "what only Android switches opens its page, says it is already so, or answers its state" {
        val control = FakeDeviceSettingsControl()
        for (setting in listOf(DeviceSetting.LOCATION, DeviceSetting.NFC, DeviceSetting.MOBILE_DATA)) {
            withClue(setting) {
                run(control, setting, DeviceAction.ON) shouldBe
                    DeviceSettingResult.Switch(setting, true, SwitchChange.ALREADY)
                run(control, setting, DeviceAction.OFF) shouldBe
                    DeviceSettingResult.SettingPage(setting, systemOnly = true)
                run(control, setting, DeviceAction.STATUS) shouldBe
                    DeviceSettingResult.Switch(setting, true, SwitchChange.STATE)
            }
        }
        run(control, DeviceSetting.DARK_MODE, DeviceAction.ON) shouldBe
            DeviceSettingResult.SettingPage(DeviceSetting.DARK_MODE, systemOnly = true)
        run(control, DeviceSetting.DARK_MODE, DeviceAction.SHOW) shouldBe
            DeviceSettingResult.SettingPage(DeviceSetting.DARK_MODE, systemOnly = false)
        // Android never says whether the hotspot is on: its page answers
        control.switches[DeviceSetting.HOTSPOT] = null
        run(control, DeviceSetting.HOTSPOT, DeviceAction.STATUS) shouldBe
            DeviceSettingResult.SettingPage(DeviceSetting.HOTSPOT, systemOnly = false)
        run(control, DeviceSetting.HOTSPOT, DeviceAction.TOGGLE) shouldBe
            DeviceSettingResult.SettingPage(DeviceSetting.HOTSPOT, systemOnly = false)
        // questions work on a locked phone, pages don't
        control.locked = true
        run(control, DeviceSetting.LOCATION, DeviceAction.STATUS) shouldBe
            DeviceSettingResult.Switch(DeviceSetting.LOCATION, true, SwitchChange.STATE)
        run(control, DeviceSetting.LOCATION, DeviceAction.OFF) shouldBe DeviceSettingResult.Locked
    }

    "a phone without NFC says so" {
        val control = FakeDeviceSettingsControl(nfcHardware = false)
        run(control, DeviceSetting.NFC, DeviceAction.ON) shouldBe DeviceSettingResult.NoHardware(DeviceSetting.NFC)
        run(control, DeviceSetting.NFC, DeviceAction.STATUS) shouldBe DeviceSettingResult.NoHardware(DeviceSetting.NFC)
    }

    "Wi-Fi and Bluetooth open the connectivity skill's controls; status is answered" {
        val control = FakeDeviceSettingsControl()
        run(control, DeviceSetting.WIFI, DeviceAction.ON)
        run(control, DeviceSetting.WIFI, DeviceAction.TOGGLE)
        run(control, DeviceSetting.BLUETOOTH, DeviceAction.OFF)
        run(control, DeviceSetting.BLUETOOTH, DeviceAction.SHOW)
        control.radios shouldContainExactly listOf(
            Radio.WIFI to RadioState.ON, Radio.WIFI to RadioState.OFF,
            Radio.BLUETOOTH to RadioState.OFF, Radio.BLUETOOTH to RadioState.SETTINGS,
        )
        run(control, DeviceSetting.WIFI, DeviceAction.STATUS) shouldBe
            DeviceSettingResult.Wifi(WifiStatus(enabled = true, connected = true))
        run(control, DeviceSetting.BLUETOOTH, DeviceAction.STATUS) shouldBe
            DeviceSettingResult.Bluetooth(control.bluetooth)
        // "are my earbuds connected"
        DeviceSettingsLogic(control).headphones() shouldBe
            DeviceSettingResult.Bluetooth(control.bluetooth, headphones = true)
        control.locked = true
        run(control, DeviceSetting.WIFI, DeviceAction.OFF) shouldBe DeviceSettingResult.Locked
        run(control, DeviceSetting.WIFI, DeviceAction.STATUS) shouldBe
            DeviceSettingResult.Wifi(WifiStatus(enabled = true, connected = true))
    }

    "pages: opened unlocked, a page that doesn't open says so" {
        val control = FakeDeviceSettingsControl()
        DeviceSettingsLogic(control).openPage(SettingsPage.BATTERY) shouldBe
            DeviceSettingResult.Page(SettingsPage.BATTERY)
        control.opened shouldContainExactly listOf(SettingsPage.BATTERY.intents)
        DeviceSettingsLogic(FakeDeviceSettingsControl(locked = true)).openPage(SettingsPage.BATTERY) shouldBe
            DeviceSettingResult.Locked
        DeviceSettingsLogic(FakeDeviceSettingsControl(pagesOpen = false)).openPage(SettingsPage.STORAGE) shouldBe
            DeviceSettingResult.OpenFailed
        run(control, DeviceSetting.BRIGHTNESS, DeviceAction.SHOW) shouldBe
            DeviceSettingResult.Page(SettingsPage.DISPLAY_PAGE)
        // "turn on night light": its page, as what only Android switches
        DeviceSettingsLogic(control).openPage(SettingsPage.NIGHT_LIGHT, switch = true) shouldBe
            DeviceSettingResult.Page(SettingsPage.NIGHT_LIGHT, switch = true)
    }

    "page names, with what people put around them" {
        for ((said, page) in listOf(
            "battery" to SettingsPage.BATTERY, "the battery" to SettingsPage.BATTERY,
            "my phone's storage" to SettingsPage.STORAGE, "about phone" to SettingsPage.ABOUT_PHONE,
            "developer options" to SettingsPage.DEVELOPER_OPTIONS,
            "battery saver" to SettingsPage.BATTERY_SAVER, "wi fi" to SettingsPage.WIFI,
            "Do Not Disturb" to SettingsPage.DO_NOT_DISTURB, "date and time" to SettingsPage.DATE_TIME,
            "the display settings" to SettingsPage.DISPLAY_PAGE, "lock screen" to SettingsPage.SECURITY,
        )) {
            withClue(said) { SettingsPage.find(said) shouldBe page }
        }
        for (said in listOf("spotify", "screen time", "camera", "the", "", "settings", "phone")) {
            withClue(said) { SettingsPage.find(said) shouldBe null }
        }
        // each name names one page
        val names = SettingsPage.entries.flatMap { it.names }
        names.toSet().size shouldBe names.size
    }

    "open_app's app names: a page with settings after it, or a name that works alone" {
        for ((said, name) in listOf(
            "battery settings" to "battery settings", "the battery settings" to "battery settings",
            "battery usage settings" to "battery settings",
            "developer options" to "developer options", "developer settings" to "developer options",
            "the display settings" to "display settings", "screen settings" to "display settings",
            "airplane mode" to "airplane mode", "my phone's storage settings" to "storage settings",
        )) {
            withClue(said) { SettingsPage.appNameFor(said) shouldBe name }
        }
        for (said in listOf("storage", "battery", "spotify", "settings", "spotify settings", "camera")) {
            withClue(said) { SettingsPage.appNameFor(said) shouldBe null }
        }
        // each page's app name leads back to it
        for (page in SettingsPage.entries) {
            withClue(page) {
                SettingsPage.fromAppName(page.appName) shouldBe page
                SettingsPage.appNameFor(page.appName) shouldBe page.appName
            }
        }
    }

    "the voice activities and the access page" {
        DeviceSettingsIntents.voiceSwitch(DeviceSetting.AIRPLANE_MODE, true) shouldBe ActivityIntent(
            "android.settings.VOICE_CONTROL_AIRPLANE_MODE", mapOf("airplane_mode_enabled" to true),
        )
        DeviceSettingsIntents.voiceSwitch(DeviceSetting.BATTERY_SAVER, false) shouldBe ActivityIntent(
            "android.settings.VOICE_CONTROL_BATTERY_SAVER_MODE",
            mapOf("android.settings.extra.battery_saver_mode_enabled" to false),
        )
        DeviceSettingsIntents.voiceSwitch(DeviceSetting.LOCATION, true) shouldBe null
        DeviceSettingsIntents.writeSettingsAccess("cc.aaro.gar") shouldBe ActivityIntent(
            "android.settings.action.MANAGE_WRITE_SETTINGS", data = "package:cc.aaro.gar",
        )
        // Wi-Fi's page is the connectivity skill's
        SettingsPage.forSetting(DeviceSetting.WIFI).first() shouldBe ConnectivityLogic.INTERNET_PANEL
        // every setting has a page to fall back on
        for (setting in DeviceSetting.entries) {
            withClue(setting) { SettingsPage.forSetting(setting).isEmpty() shouldBe false }
        }
    }
})
