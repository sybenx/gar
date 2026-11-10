package org.stypox.dicio.skills.device_settings

import cc.aaro.gar.router.CallFilter
import cc.aaro.gar.router.CallVerdict
import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.resolve.ResolvedNumber
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.device_settings.DeviceSettingWords.Opening

/**
 * gar:settings. The model's device_setting calls checked against what the user said, and its
 * volume calls about the brightness, in Kotlin so that the prompt stays as it is (gar/PARITY.md,
 * "Keeping the model's prompt small"; English, like everything GAR routes). Qwen3-0.6B's
 * device_setting slots aren't reliable (gar/eval/README.md, 2026-10-27): questions got "on"
 * ("what does airplane mode do" -> airplane mode on), other requests another setting ("is my
 * headset connected" -> battery saver on), and brightness commands went to volume or volume_set.
 *
 * - [filter] drops a device_setting call whose words name another setting ([namedSetting];
 *   the ringer and vibrate count as one, so do the brightness and adaptive brightness) or another
 *   thing ("turn on location sharing"), and a volume call about another thing's brightness ("how
 *   bright is the sun").
 * - [checked] runs a device_setting call for a question as the setting's status; [needsConfirm]
 *   asks first ("Turn on airplane mode?") before a call switches a setting the words don't name,
 *   even when the user turned GAR's confirmations off: GAR can't tell what to switch then.
 * - [output] runs a volume or volume_set call whose words are about the brightness as the
 *   brightness, with the action the words say.
 */
object DeviceSettingCalls {
    /** The app's check of the model's calls, see [DeviceSettingCalls]. */
    val filter = CallFilter { call ->
        val reason = when (call.tool.name) {
            DeviceSettingTool.NAME -> mismatch(
                call.utterance, DeviceSetting.fromSlot(call.rawSlots[DeviceSettingTool.SETTING]),
            )
            VOLUME, VOLUME_SET -> if (DeviceSettingWords.saysBrightness(call.utterance) &&
                DeviceSettingWords.namesOtherThing(call.utterance, DeviceSetting.BRIGHTNESS)
            ) {
                "'${call.utterance}' is about another thing's brightness"
            } else {
                null
            }
            else -> null
        }
        if (reason == null) CallVerdict.Keep else CallVerdict.Drop(reason)
    }

    /** Why the words of [utterance] don't fit a call for [setting], or null if they do. */
    private fun mismatch(utterance: String, setting: DeviceSetting?): String? {
        setting ?: return null // GAR asks which setting
        val named = DeviceSettingWords.namedSetting(utterance)
        return when {
            named != null && !sameSetting(named, setting) ->
                "'$utterance' names ${named.slotValue}, not ${setting.slotValue}"
            DeviceSettingWords.namesOtherThing(utterance, setting) ->
                "'$utterance' names another thing than ${setting.slotValue}"
            else -> null
        }
    }

    private fun sameSetting(a: DeviceSetting, b: DeviceSetting): Boolean =
        groupOf(a) == groupOf(b)

    private fun groupOf(setting: DeviceSetting): DeviceSetting = when (setting) {
        DeviceSetting.VIBRATE -> DeviceSetting.RINGER
        DeviceSetting.AUTO_BRIGHTNESS -> DeviceSetting.BRIGHTNESS
        else -> setting
    }

    /** Whether [call] is the model's device_setting call (not GAR's own, which has no words). */
    private fun isModelCall(call: GarCall): Boolean =
        call.tool.name == DeviceSettingTool.NAME && call.utterance.isNotBlank()

    /**
     * [call] with its action as the words ask: a question ("is my phone on silent", "what does
     * airplane mode do") is the setting's status, whatever action the model chose.
     */
    fun checked(call: GarCall): GarCall {
        if (!isModelCall(call) || DeviceSettingWords.openingOf(call.utterance) == Opening.COMMAND) {
            return call
        }
        val action = DeviceAction.fromSlot(call.values[DeviceSettingTool.ACTION])
        if (action == null || action == DeviceAction.STATUS || action == DeviceAction.SHOW) {
            return call
        }
        return call.copy(
            values = call.values + (DeviceSettingTool.ACTION to DeviceAction.STATUS.slotValue),
        )
    }

    /**
     * Whether [call] switches one of [CONFIRMED] that its words don't name ("get me off the grid"
     * -> airplane mode on): then GAR asks with the tool's question before running it.
     */
    fun needsConfirm(call: GarCall): Boolean {
        if (!isModelCall(call)) {
            return false
        }
        val setting = DeviceSetting.fromSlot(call.values[DeviceSettingTool.SETTING]) ?: return false
        val action = DeviceAction.fromSlot(call.values[DeviceSettingTool.ACTION]) ?: return false
        if (setting !in CONFIRMED || action !in SWITCH_ACTIONS) {
            return false
        }
        val named = DeviceSettingWords.namedSetting(call.utterance)
        return named == null || !sameSetting(named, setting)
    }

    /** What a model's call can switch without asking only when the words name it. */
    private val CONFIRMED = setOf(
        DeviceSetting.AIRPLANE_MODE, DeviceSetting.BATTERY_SAVER, DeviceSetting.RINGER,
        DeviceSetting.VIBRATE, DeviceSetting.AUTO_ROTATE, DeviceSetting.AUTO_BRIGHTNESS,
    )

    private val SWITCH_ACTIONS = setOf(DeviceAction.ON, DeviceAction.OFF, DeviceAction.TOGGLE)

    /**
     * The brightness for a volume or volume_set call of the model whose words are about it ("make
     * the screen brighter" -> volume(up)), or null to run the volume. [filter] already dropped
     * the calls about another thing's brightness.
     */
    suspend fun output(
        ctx: SkillContext,
        call: GarCall,
        controlFactory: (SkillContext) -> DeviceSettingsControl = { AndroidDeviceSettingsControl(it.android) },
    ): SkillOutput? {
        val command = brightnessCommand(call) ?: return null
        val control = controlFactory(ctx)
        return DeviceSettingsOutput.of(
            DeviceSettingsLogic(control).run(command),
            control,
            Sentences.UtilYesNo[ctx.sentencesLanguage],
        )
    }

    /**
     * The brightness command for a volume or volume_set [call] about the brightness, or null. The
     * words decide, not the model's volume action: a question is the status, adaptive
     * brightness turns on or off, a level is set, and "lower", "dim" or "up" step.
     */
    fun brightnessCommand(call: GarCall): DeviceSettingCommand? {
        if (call.tool.name != VOLUME && call.tool.name != VOLUME_SET) {
            return null
        }
        val utterance = call.utterance
        if (utterance.isBlank() || !DeviceSettingWords.saysBrightness(utterance)) {
            return null
        }
        val question = DeviceSettingWords.openingOf(utterance) != Opening.COMMAND
        if (DeviceSettingWords.settingIn(utterance) == DeviceSetting.AUTO_BRIGHTNESS) {
            val action = when {
                question -> DeviceAction.STATUS
                DeviceSettingWords.saysOff(utterance) -> DeviceAction.OFF
                else -> DeviceAction.ON
            }
            return DeviceSettingCommand(DeviceSetting.AUTO_BRIGHTNESS, action)
        }
        val level = call.values[LEVEL] as? ResolvedNumber
        return when {
            question -> DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.STATUS)
            level != null && call.tool.name == VOLUME_SET -> DeviceSettingCommand(
                DeviceSetting.BRIGHTNESS, DeviceAction.SET, DeviceLevel.Amount(level),
            )
            DeviceSettingWords.saysDown(utterance) ->
                DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.DOWN)
            DeviceSettingWords.saysUp(utterance) ->
                DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.UP)
            else -> DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.STATUS)
        }
    }

    /** The volume tools of `tools.yml` and volume_set's level slot. */
    private const val VOLUME = "volume"
    private const val VOLUME_SET = "volume_set"
    private const val LEVEL = "level"
}
