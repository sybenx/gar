package org.stypox.dicio.skills.device_settings

import cc.aaro.gar.router.CallVerdict
import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.resolve.ResolvedNumber
import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.call.GarCall
import java.io.File

private val tools by lazy {
    fun source(path: String) = sequenceOf(File(path), File("app", path)).first { it.isFile }.readText()
    ToolCatalog.load(
        source("src/main/gar/tools.yml"),
        source("src/main/sentences/skill_definitions.yml"),
    ).tools.associateBy { it.name }
}

/** The model's call as the router hands it on, slots as the model said them. */
private fun routed(tool: String, utterance: String, vararg slots: Pair<String, String>) =
    RouterOutput.Call(tools.getValue(tool), slots.toMap(), listOf(), Utterances.normalize(utterance))

/** The model's call once GAR resolved its slots (enum values stay as they are). */
private fun call(tool: String, utterance: String, vararg values: Pair<String, Any?>) = GarCall(
    tool = tools.getValue(tool),
    rawSlots = values.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap(),
    utterance = Utterances.normalize(utterance),
    values = values.toMap(),
)

private fun setting(setting: String, action: String) = arrayOf(
    DeviceSettingTool.SETTING to setting, DeviceSettingTool.ACTION to action,
)

/**
 * gar:settings: the model's device_setting and volume calls checked against what the user said
 * ([DeviceSettingCalls]), with the wrong calls Qwen3-0.6B made in the 2026-10-27 eval.
 */
class DeviceSettingCallsTest : StringSpec({
    "a device_setting call for another setting than the words name is dropped" {
        for ((utterance, slots) in listOf(
            "is my phone on silent" to setting("battery saver", "on"),
            "is my headset connected" to setting("battery saver", "on"),
            "i want to pair my headphones" to setting("battery saver", "on"),
            "switch my phone to silent" to setting("battery saver", "off"),
            "turn on location sharing" to setting("location", "on"),
            "turn on wifi calling" to setting("wifi", "on"),
        )) {
            withClue(utterance) {
                DeviceSettingCalls.filter.check(routed("device_setting", utterance, *slots))
                    .shouldBeInstanceOf<CallVerdict.Drop>()
            }
        }
        for ((utterance, slots) in listOf(
            "get me out of airplane mode" to setting("airplane mode", "off"),
            "connect my earbuds" to setting("bluetooth", "on"),
            "put my phone on vibrate" to setting("ringer", "on"),
            "turn on auto brightness" to setting("brightness", "on"),
            "get me back online" to setting("wifi", "on"),
            "it's too bright" to setting("brightness", "down"),
        )) {
            withClue(utterance) {
                DeviceSettingCalls.filter.check(routed("device_setting", utterance, *slots)) shouldBe
                    CallVerdict.Keep
            }
        }
    }

    "a volume call about another thing's brightness is dropped" {
        DeviceSettingCalls.filter.check(routed("volume", "how bright is the sun", "action" to "up"))
            .shouldBeInstanceOf<CallVerdict.Drop>()
        DeviceSettingCalls.filter.check(routed("volume", "turn up the brightness", "action" to "up")) shouldBe
            CallVerdict.Keep
        DeviceSettingCalls.filter.check(routed("volume", "turn up the volume", "action" to "up")) shouldBe
            CallVerdict.Keep
    }

    "a question runs as the setting's status" {
        for (utterance in listOf(
            "what does airplane mode do", "is battery saver on", "did i turn on airplane mode",
            "should i turn on battery saver", "can i turn off location",
        )) {
            withClue(utterance) {
                val checked = DeviceSettingCalls.checked(
                    call("device_setting", utterance, *setting("airplane mode", "on")),
                )
                checked.values[DeviceSettingTool.ACTION] shouldBe "status"
                DeviceSettingCalls.needsConfirm(checked) shouldBe false
            }
        }
        val command = call("device_setting", "turn on airplane mode", *setting("airplane mode", "on"))
        DeviceSettingCalls.checked(command) shouldBe command
        // GAR's own calls (a level asked for) have no words to check
        val own = GarCall(DeviceSettingTool.NUMBER_SPEC, mapOf(), "", mapOf(*setting("brightness", "set")))
        DeviceSettingCalls.checked(own) shouldBe own
        DeviceSettingCalls.needsConfirm(own) shouldBe false
    }

    "a switch that the words don't name asks first" {
        for ((utterance, slots) in listOf(
            "show me the settings" to setting("battery saver", "on"),
            "get me off the grid" to setting("airplane mode", "on"),
            "make my phone quiet" to setting("ringer", "off"),
            "stop the screen from turning" to setting("auto rotate", "off"),
        )) {
            withClue(utterance) {
                DeviceSettingCalls.needsConfirm(call("device_setting", utterance, *slots)) shouldBe true
            }
        }
        for ((utterance, slots) in listOf(
            "turn on airplane mode" to setting("airplane mode", "on"),
            "put my phone on vibrate" to setting("vibrate", "on"),
            // only opens a page, or changes a level
            "get me back online" to setting("mobile data", "on"),
            "make it brighter" to setting("brightness", "up"),
        )) {
            withClue(utterance) {
                DeviceSettingCalls.needsConfirm(call("device_setting", utterance, *slots)) shouldBe false
            }
        }
    }

    "a volume call about the brightness is the brightness, as the words say" {
        val fifty = ResolvedNumber(50.0, percent = true, isInteger = true)
        for ((pair, expected) in listOf(
            call("volume", "turn up the brightness", "action" to "up") to
                DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.UP),
            call("volume", "make the screen brighter", "action" to "up") to
                DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.UP),
            call("volume", "lower the brightness a bit", "action" to "down") to
                DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.DOWN),
            // the words decide, not the model's volume action
            call("volume", "it's too bright dim the screen", "action" to "up") to
                DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.DOWN),
            call("volume", "what's my brightness at", "action" to "up") to
                DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.STATUS),
            call("volume", "turn on auto brightness", "action" to "up") to
                DeviceSettingCommand(DeviceSetting.AUTO_BRIGHTNESS, DeviceAction.ON),
            call("volume_set", "set the brightness to fifty percent", "level" to fifty) to
                DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.SET, DeviceLevel.Amount(fifty)),
        )) {
            withClue(pair.utterance) { DeviceSettingCalls.brightnessCommand(pair) shouldBe expected }
        }
        DeviceSettingCalls.brightnessCommand(call("volume", "turn up the volume", "action" to "up"))
            .shouldBeNull()
        DeviceSettingCalls.brightnessCommand(
            call("device_setting", "turn up the brightness", *setting("brightness", "up")),
        ).shouldBeNull()
    }
})
