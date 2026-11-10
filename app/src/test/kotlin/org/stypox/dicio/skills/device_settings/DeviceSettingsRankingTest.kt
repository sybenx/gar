package org.stypox.dicio.skills.device_settings

import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.GarMatchCheck
import org.stypox.dicio.gar.garRankingWinner
import java.io.File

private val catalogTools by lazy {
    fun source(path: String) = sequenceOf(File(path), File("app", path)).first { it.isFile }.readText()
    ToolCatalog.load(
        source("src/main/gar/tools.yml"),
        source("src/main/sentences/skill_definitions.yml"),
    ).tools
}

/** A level as the skill reads it, so that a capture's spaces or a leading "for" don't matter. */
private fun cleaned(input: Any?): Any? = when (input) {
    is SaidDeviceSetting -> input.copy(level = input.level?.let { DeviceSettingWords.cleanLevel(it) })
    else -> input
}

private fun said(setting: DeviceSetting, action: DeviceAction, level: String? = null) =
    SaidDeviceSetting(setting, action, level)

private fun command(setting: DeviceSetting, action: DeviceAction, level: DeviceLevel? = null) =
    DeviceSettingCommand(setting, action, level)

/**
 * gar:settings (gar/PARITY.md, batch 3): every English skill of the app ranked like the
 * evaluator does. The device settings sentences take their commands, Wi-Fi and Bluetooth on, off
 * and settings stay the connectivity skill's, and inputs that only share words with a setting go
 * elsewhere.
 */
class DeviceSettingsRankingTest : StringSpec({
    "the device settings sentences take their commands" {
        for ((input, expected) in listOf(
            "turn up the brightness" to command(DeviceSetting.BRIGHTNESS, DeviceAction.UP),
            "make the screen brighter" to command(DeviceSetting.BRIGHTNESS, DeviceAction.UP),
            "the screen is too dark" to command(DeviceSetting.BRIGHTNESS, DeviceAction.UP),
            "dim the screen" to command(DeviceSetting.BRIGHTNESS, DeviceAction.DOWN),
            "lower the brightness" to command(DeviceSetting.BRIGHTNESS, DeviceAction.DOWN),
            // a vague amount is a step, not a level to ask about
            "lower the brightness a bit" to command(DeviceSetting.BRIGHTNESS, DeviceAction.DOWN),
            "turn up the brightness a little" to command(DeviceSetting.BRIGHTNESS, DeviceAction.UP),
            "make the brightness automatic" to said(DeviceSetting.AUTO_BRIGHTNESS, DeviceAction.ON),
            "set the brightness to fifty percent" to
                said(DeviceSetting.BRIGHTNESS, DeviceAction.SET, "fifty percent"),
            "brightness to seventy" to said(DeviceSetting.BRIGHTNESS, DeviceAction.SET, "seventy"),
            "max brightness" to command(DeviceSetting.BRIGHTNESS, DeviceAction.SET, DeviceLevel.Max),
            "turn the brightness all the way down" to
                command(DeviceSetting.BRIGHTNESS, DeviceAction.SET, DeviceLevel.Min),
            "turn on auto brightness" to said(DeviceSetting.AUTO_BRIGHTNESS, DeviceAction.ON),
            "turn off adaptive brightness" to said(DeviceSetting.AUTO_BRIGHTNESS, DeviceAction.OFF),
            "put my phone on vibrate" to command(DeviceSetting.VIBRATE, DeviceAction.ON),
            "vibrate mode" to command(DeviceSetting.VIBRATE, DeviceAction.ON),
            "put my phone on silent" to command(DeviceSetting.RINGER, DeviceAction.OFF),
            "silence my phone" to command(DeviceSetting.RINGER, DeviceAction.OFF),
            "silence the phone" to command(DeviceSetting.RINGER, DeviceAction.OFF),
            "mute my phone" to command(DeviceSetting.RINGER, DeviceAction.OFF),
            "turn on silent mode" to command(DeviceSetting.RINGER, DeviceAction.OFF),
            "turn the ringer back on" to command(DeviceSetting.RINGER, DeviceAction.ON),
            "take my phone off silent" to command(DeviceSetting.RINGER, DeviceAction.ON),
            "turn off vibrate" to command(DeviceSetting.RINGER, DeviceAction.ON),
            "turn on airplane mode" to said(DeviceSetting.AIRPLANE_MODE, DeviceAction.ON),
            "turn off airplane mode" to said(DeviceSetting.AIRPLANE_MODE, DeviceAction.OFF),
            "put my phone in airplane mode" to said(DeviceSetting.AIRPLANE_MODE, DeviceAction.ON),
            "turn on flight mode" to said(DeviceSetting.AIRPLANE_MODE, DeviceAction.ON),
            "turn on my hotspot" to said(DeviceSetting.HOTSPOT, DeviceAction.ON),
            "turn on the wifi hotspot" to said(DeviceSetting.HOTSPOT, DeviceAction.ON),
            "turn off location" to said(DeviceSetting.LOCATION, DeviceAction.OFF),
            "turn on gps" to said(DeviceSetting.LOCATION, DeviceAction.ON),
            "turn on battery saver" to said(DeviceSetting.BATTERY_SAVER, DeviceAction.ON),
            "turn on dark mode" to said(DeviceSetting.DARK_MODE, DeviceAction.ON),
            "turn on light mode" to said(DeviceSetting.DARK_MODE, DeviceAction.OFF),
            "turn on the light theme" to said(DeviceSetting.DARK_MODE, DeviceAction.OFF),
            "turn off mobile data" to said(DeviceSetting.MOBILE_DATA, DeviceAction.OFF),
            "turn on nfc" to said(DeviceSetting.NFC, DeviceAction.ON),
            "turn off auto rotate" to said(DeviceSetting.AUTO_ROTATE, DeviceAction.OFF),
            "lock the screen rotation" to said(DeviceSetting.AUTO_ROTATE, DeviceAction.OFF),
            "is wifi on" to said(DeviceSetting.WIFI, DeviceAction.STATUS),
            "is bluetooth on" to said(DeviceSetting.BLUETOOTH, DeviceAction.STATUS),
            "is my phone on silent" to said(DeviceSetting.RINGER, DeviceAction.STATUS),
            "is my phone on vibrate" to said(DeviceSetting.VIBRATE, DeviceAction.STATUS),
            "what's my brightness" to said(DeviceSetting.BRIGHTNESS, DeviceAction.STATUS),
            "how bright is my screen" to said(DeviceSetting.BRIGHTNESS, DeviceAction.STATUS),
            "is my phone on mute" to said(DeviceSetting.RINGER, DeviceAction.STATUS),
            "when does my screen turn off" to said(DeviceSetting.SCREEN_TIMEOUT, DeviceAction.STATUS),
            "is airplane mode on" to said(DeviceSetting.AIRPLANE_MODE, DeviceAction.STATUS),
            "is location on" to said(DeviceSetting.LOCATION, DeviceAction.STATUS),
            "is auto rotate on" to said(DeviceSetting.AUTO_ROTATE, DeviceAction.STATUS),
            "what's my screen timeout" to said(DeviceSetting.SCREEN_TIMEOUT, DeviceAction.STATUS),
            "set the screen timeout to two minutes" to
                said(DeviceSetting.SCREEN_TIMEOUT, DeviceAction.SET, "two minutes"),
            "keep the screen on for ten minutes" to
                said(DeviceSetting.SCREEN_TIMEOUT, DeviceAction.SET, "ten minutes"),
            "open battery settings" to OpenSettingsPage(SettingsPage.BATTERY),
            "open the display settings" to OpenSettingsPage(SettingsPage.DISPLAY_PAGE),
            "show me the sound settings" to OpenSettingsPage(SettingsPage.SOUND_PAGE),
            "open developer options" to OpenSettingsPage(SettingsPage.DEVELOPER_OPTIONS),
            "go to storage settings" to OpenSettingsPage(SettingsPage.STORAGE),
            "date and time settings" to OpenSettingsPage(SettingsPage.DATE_TIME),
            "open notification settings" to OpenSettingsPage(SettingsPage.NOTIFICATIONS),
            "open the hotspot settings" to OpenSettingsPage(SettingsPage.HOTSPOT),
            // a level without "to" (found in review: these went to the model and the volume)
            "brightness fifty percent" to
                said(DeviceSetting.BRIGHTNESS, DeviceAction.SET, "fifty percent"),
            "brightness 50" to said(DeviceSetting.BRIGHTNESS, DeviceAction.SET, "50"),
            "screen brightness settings" to OpenSettingsPage(SettingsPage.DISPLAY_PAGE),
            // short ringer commands both ways
            "ringer off" to command(DeviceSetting.RINGER, DeviceAction.OFF),
            "silent mode off" to command(DeviceSetting.RINGER, DeviceAction.ON),
            "vibrate off" to command(DeviceSetting.RINGER, DeviceAction.ON),
            "is my phone muted" to said(DeviceSetting.RINGER, DeviceAction.STATUS),
            "is my phone silenced" to said(DeviceSetting.RINGER, DeviceAction.STATUS),
            // headphones are Bluetooth's audio devices
            "are my earbuds connected" to HeadphonesStatus,
            "is my headset connected" to HeadphonesStatus,
            "are my headphones connected" to HeadphonesStatus,
            // Android's night light, not the torch
            "turn on the night light" to OpenSettingsPage(SettingsPage.NIGHT_LIGHT, switch = true),
            "turn on night light" to OpenSettingsPage(SettingsPage.NIGHT_LIGHT, switch = true),
            "turn off night light" to OpenSettingsPage(SettingsPage.NIGHT_LIGHT, switch = true),
            "turn on the blue light filter" to OpenSettingsPage(SettingsPage.NIGHT_LIGHT, switch = true),
            // polite commands are commands
            "can you turn on airplane mode" to said(DeviceSetting.AIRPLANE_MODE, DeviceAction.ON),
            "will you turn on airplane mode" to said(DeviceSetting.AIRPLANE_MODE, DeviceAction.ON),
        )) {
            withClue(input) {
                val (skill, data) = garRankingWinner(input)
                Pair(skill, cleaned(data)) shouldBe Pair(DeviceSettingsInfo.id, expected)
            }
        }
    }

    "Wi-Fi and Bluetooth on, off and settings stay with the connectivity sentences" {
        for (input in listOf(
            "turn on wifi", "turn off the wi fi", "open the wifi settings", "turn on bluetooth",
            "turn bluetooth off", "open bluetooth settings",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe "connectivity" }
        }
    }

    "other skills keep their commands" {
        for ((input, skill) in listOf(
            "turn on the flashlight" to "flashlight", "turn up the volume" to "volume",
            "mute the music" to "volume", "turn up the ringer volume" to "volume",
            "turn on do not disturb" to "do_not_disturb", "open spotify" to "open",
            "what's my battery" to "battery", "set a timer for five minutes" to "timer",
            "what time is it" to "current_time",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe skill }
        }
    }

    "inputs that only share words with a setting go elsewhere" {
        for (input in listOf(
            "turn on the lights", "dim the lights", "make the lights brighter",
            "turn on location sharing", "share my location with mom", "turn on wifi calling",
            "turn on wireless charging", "turn on data saver", "what's the brightness of the sun",
            "how bright is the sun", "turn on the tv", "open the camera", "open spotify",
            "show me the weather", "turn off screen time", "set the thermostat to seventy",
            "my screen is cracked", "set an alarm for seven", "is the oven on",
            "what's my location", "silence the alarm", "mute the timer", "show me the settings",
            "open settings", "turn up the heat",
            // where the phone is, not its location settings
            "show my location", "show me my location", "open my location",
            // the keyboard's vibration, not vibrate mode
            "turn on keyboard vibration", "turn on haptic vibration",
            // "time or" is how STT hears "timer"
            "set the time or for forty seconds",
        )) {
            withClue(input) { garRankingWinner(input).first shouldNotBe DeviceSettingsInfo.id }
        }
    }

    "a status needs a question" {
        // "is ... ringer off" with its first word left out isn't a question
        garRankingWinner("ringer off").second shouldNotBe said(DeviceSetting.RINGER, DeviceAction.STATUS)
    }

    "a question about a command switches nothing" {
        // did, is, are: the setting's status
        for ((input, expected) in listOf(
            "did i turn on airplane mode" to said(DeviceSetting.AIRPLANE_MODE, DeviceAction.STATUS),
            "did i put my phone on vibrate" to said(DeviceSetting.VIBRATE, DeviceAction.STATUS),
            "did i turn up the brightness" to said(DeviceSetting.BRIGHTNESS, DeviceAction.STATUS),
        )) {
            withClue(input) {
                val (skill, data) = garRankingWinner(input)
                Pair(skill, cleaned(data)) shouldBe Pair(DeviceSettingsInfo.id, expected)
            }
        }
        // should, can i, how: advice, not a command (found in review: these switched)
        for (input in listOf(
            "should i turn on battery saver", "should i turn off mobile data",
            "should i put my phone on silent", "can i turn on airplane mode",
            "should i turn up the brightness", "should i set the screen timeout to 30 seconds",
            "how do i turn on airplane mode", "what happens if i turn on airplane mode",
        )) {
            withClue(input) { garRankingWinner(input).first shouldNotBe DeviceSettingsInfo.id }
        }
    }

    "the flashlight's match of the night light goes to the model" {
        val state = GarMatchCheck.State(catalogTools, listOf(), ENGLISH_PARSER_FORMATTER)
        GarMatchCheck.rejects(state, "flashlight", null, 0.9f, "turn on the night light") shouldBe true
        GarMatchCheck.rejects(state, "flashlight", null, 0.9f, "turn on the blue light") shouldBe true
        GarMatchCheck.rejects(state, "flashlight", null, 0.9f, "turn on the flash light") shouldBe false
    }

    "GAR's match check keeps the device settings matches and still checks connectivity's" {
        val state = GarMatchCheck.State(catalogTools, listOf(), ENGLISH_PARSER_FORMATTER)
        for (input in listOf(
            "turn up the brightness", "put my phone on vibrate", "mute my phone",
            "make my phone ring again", "turn on airplane mode", "is wifi on",
            "what's my screen timeout", "open battery settings", "open developer options",
            "open about phone", "open accessibility", "keep the screen on for ten minutes",
            // "screen" is a sentence keyword, not the tool's ("my screen is cracked")
            "when does the screen turn off", "the screen is too dark", "turn on the night light",
            "brightness fifty percent", "ringer off", "are my earbuds connected",
        )) {
            withClue(input) {
                val (skill, data) = garRankingWinner(input)
                skill shouldBe DeviceSettingsInfo.id
                GarMatchCheck.rejects(state, DeviceSettingsInfo.id, data, 0.9f, input) shouldBe false
            }
        }
        // the connectivity sentences keep their check without tools of their own
        GarMatchCheck.keywordsFor(catalogTools, "connectivity").shouldNotBeNull() shouldContainAll
            listOf("wifi", "bluetooth", "headphone")
        GarMatchCheck.rejects(state, "connectivity", null, 0.9f, "turn on wifi calling") shouldBe true
        GarMatchCheck.rejects(state, "connectivity", null, 1.0f, "turn on wifi") shouldBe false
        GarMatchCheck.rejects(state, "connectivity", null, 0.9f, "switch off the music") shouldBe true
    }
})
