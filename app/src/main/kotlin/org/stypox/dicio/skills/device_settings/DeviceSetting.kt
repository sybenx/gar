package org.stypox.dicio.skills.device_settings

import cc.aaro.gar.router.resolve.ResolvedNumber
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import java.time.Duration

/**
 * A setting of the `device_setting` tool (`tools.yml`); [slotValue] is its value in the tool's
 * `setting` enum. Wi-Fi and Bluetooth are here for their status questions even when the catalog
 * gives them their own tools again (the merge can be undone, see gar/DESIGN.md).
 */
enum class DeviceSetting(val slotValue: String) {
    WIFI("wifi"),
    BLUETOOTH("bluetooth"),
    AIRPLANE_MODE("airplane mode"),
    MOBILE_DATA("mobile data"),
    HOTSPOT("hotspot"),
    NFC("nfc"),
    LOCATION("location"),
    BATTERY_SAVER("battery saver"),
    DARK_MODE("dark mode"),
    BRIGHTNESS("brightness"),
    AUTO_BRIGHTNESS("auto brightness"),
    AUTO_ROTATE("auto rotate"),
    SCREEN_TIMEOUT("screen timeout"),
    RINGER("ringer"),
    VIBRATE("vibrate");

    /** Whether a command can come with a level: "brightness to fifty", "timeout to two minutes". */
    val takesLevel: Boolean get() = this == BRIGHTNESS || this == SCREEN_TIMEOUT || this == RINGER

    companion object {
        fun fromSlot(value: Any?): DeviceSetting? = entries.firstOrNull { it.slotValue == value }
    }
}

/** What to do with a [DeviceSetting], the `action` enum of the `device_setting` tool. */
enum class DeviceAction(val slotValue: String) {
    ON("on"),
    OFF("off"),
    TOGGLE("toggle"),
    UP("up"),
    DOWN("down"),
    SET("set"),
    SHOW("show"),
    STATUS("status");

    companion object {
        fun fromSlot(value: Any?): DeviceAction? = entries.firstOrNull { it.slotValue == value }
    }
}

/** A resolved level of a command. */
sealed interface DeviceLevel {
    /**
     * A brightness or ring volume: a percentage, or a plain number (up to ten it is out of ten,
     * above ten a percentage, a fraction is a share, as for the volume).
     */
    data class Amount(val number: ResolvedNumber) : DeviceLevel

    /** A screen timeout. */
    data class Time(val duration: Duration) : DeviceLevel

    /** "max", "full", "all the way up". */
    data object Max : DeviceLevel

    /** "min", "lowest", "all the way down". */
    data object Min : DeviceLevel
}

/** What [DeviceSettingsSkill.generateOutput] takes. */
sealed interface DeviceSettingsInput

/** A command whose level, if any, is resolved. */
data class DeviceSettingCommand(
    val setting: DeviceSetting,
    val action: DeviceAction,
    val level: DeviceLevel? = null,
) : DeviceSettingsInput

/**
 * A command with its level as said (the model's call, or a sentence's capture), which GAR's slot
 * resolution turns into a [DeviceSettingCommand], asking when it can't read a level it needs.
 */
data class SaidDeviceSetting(
    val setting: DeviceSetting,
    val action: DeviceAction,
    val level: String?,
) : DeviceSettingsInput

/**
 * "Open the battery settings". [switch]: the user asked to switch what the page has, which only
 * Android may do ("turn on night light": "Here's night light.").
 */
data class OpenSettingsPage(val page: SettingsPage, val switch: Boolean = false) : DeviceSettingsInput

/** "Are my earbuds connected": Bluetooth's status, said about headphones. */
data object HeadphonesStatus : DeviceSettingsInput

/** A sentence match the skill doesn't take (its score is the worst). */
data object NotADeviceSetting : DeviceSettingsInput

/**
 * The slots of the `device_setting` tool of `tools.yml` (LLM-only) and the specs GAR resolves a
 * level with. The tool's level is text: what it means depends on the setting (a number for the
 * brightness, a duration for the screen timeout), and the model often fills an optional slot with
 * leftover words, which must not drop the call ("turn on wifi" -> `level: "wifi"`).
 */
object DeviceSettingTool {
    const val NAME = "device_setting"
    const val SETTING = "setting"
    const val ACTION = "action"
    const val LEVEL = "level"

    private fun spec(levelKind: SlotKind) = ToolSpec(
        name = NAME,
        skillId = DeviceSettingsInfo.id,
        sentence = null,
        description = "Change a phone setting.",
        confirm = false,
        slots = listOf(
            SlotSpec(SETTING, SlotKind.ENUM, true, "", DeviceSetting.entries.map { it.slotValue }),
            SlotSpec(ACTION, SlotKind.ENUM, true, "", DeviceAction.entries.map { it.slotValue }),
            SlotSpec(LEVEL, levelKind, true, ""),
        ),
        examples = listOf(),
        onLockScreen = true,
    )

    /** A brightness or a ring volume: GAR asks "What level?" for one it can't read. */
    val NUMBER_SPEC = spec(SlotKind.NUMBER)

    /** A screen timeout: GAR asks "How long?". */
    val DURATION_SPEC = spec(SlotKind.DURATION)

    fun levelSpec(setting: DeviceSetting): ToolSpec =
        if (setting == DeviceSetting.SCREEN_TIMEOUT) DURATION_SPEC else NUMBER_SPEC

    /**
     * The input for resolved slot values: the tool's call (level as said, a String) or GAR's
     * resolution of a level with [NUMBER_SPEC] or [DURATION_SPEC].
     */
    fun inputFromSlots(slots: Map<String, Any?>): DeviceSettingsInput {
        val setting = DeviceSetting.fromSlot(slots[SETTING]) ?: return NotADeviceSetting
        val action = DeviceAction.fromSlot(slots[ACTION]) ?: return NotADeviceSetting
        return when (val level = slots[LEVEL]) {
            is ResolvedNumber -> DeviceSettingCommand(setting, action, DeviceLevel.Amount(level))
            is Duration -> DeviceSettingCommand(setting, action, DeviceLevel.Time(level))
            is String -> SaidDeviceSetting(setting, action, level)
            else -> SaidDeviceSetting(setting, action, null)
        }
    }
}
