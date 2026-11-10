package org.stypox.dicio.skills.device_settings

import cc.aaro.gar.router.KeywordFilter
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.SlotResolvers
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.ToolCatalog
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.DefaultGarSlotResolver
import org.stypox.dicio.gar.call.GarAnswer
import org.stypox.dicio.gar.call.GarAskSlotOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarCancelledOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.connectivity.Radio
import org.stypox.dicio.skills.connectivity.RadioState
import java.io.File
import java.time.Duration

private val CATALOG by lazy {
    fun source(path: String) = sequenceOf(File(path), File("app", path)).first { it.isFile }.readText()
    ToolCatalog.load(source("src/main/gar/tools.yml"), source("src/main/sentences/skill_definitions.yml"))
}
private val TOOL: ToolSpec by lazy { CATALOG.byName(DeviceSettingTool.NAME)!! }

private val resolver = DefaultGarSlotResolver(SlotResolvers(ENGLISH_PARSER_FORMATTER, "en"))

private fun skill(control: FakeDeviceSettingsControl) = DeviceSettingsSkill(
    DeviceSettingsInfo,
    Sentences.DeviceSettings["en"]!!,
    resolverFactory = { resolver },
    formatterFactory = { englishFormatter() },
    controlFactory = { control },
)

/** The skill's output for the model's call with these slots, as GAR's call runner gives them. */
private suspend fun call(control: FakeDeviceSettingsControl, vararg slots: Pair<String, String>): SkillOutput {
    val skill = skill(control)
    return skill.generateOutput(FakeSkillContext(), skill.inputFromSlots(slots.toMap()))
}

private fun SkillOutput.result() = shouldBeInstanceOf<DeviceSettingsOutput>().result

/** gar:settings: the model's device_setting calls and the skill's questions. */
class DeviceSettingsSkillTest : StringSpec({
    "the tool's slots are the skill's settings and actions, its level is text and optional" {
        val setting = TOOL.slots.single { it.name == DeviceSettingTool.SETTING }
        val action = TOOL.slots.single { it.name == DeviceSettingTool.ACTION }
        val level = TOOL.slots.single { it.name == DeviceSettingTool.LEVEL }
        // all of them while the tool has Wi-Fi and Bluetooth; see gar/DESIGN.md for the undo
        for (value in setting.enumValues) {
            withClue(value) { DeviceSetting.fromSlot(value).shouldNotBeNull() }
        }
        action.enumValues shouldContainExactly DeviceAction.entries.map { it.slotValue }
        level.kind shouldBe SlotKind.TEXT
        level.required shouldBe false
        TOOL.sentence shouldBe null
        TOOL.skillId shouldBe DeviceSettingsInfo.id
        // a request that names a setting as the tool does passes its keywords
        for (value in setting.enumValues) {
            withClue(value) { KeywordFilter.containsAny(value, TOOL.keywords) shouldBe true }
        }
    }

    "the model's calls run the setting, with a level read for the setting" {
        val control = FakeDeviceSettingsControl()
        call(control, "setting" to "brightness", "action" to "set", "level" to "fifty percent")
            .result() shouldBe DeviceSettingResult.Brightness(50, auto = false, changed = true)
        call(control, "setting" to "brightness", "action" to "set", "level" to "to the max")
            .result() shouldBe DeviceSettingResult.Brightness(100, auto = false, changed = true)
        call(control, "setting" to "screen timeout", "action" to "set", "level" to "two minutes")
            .result() shouldBe DeviceSettingResult.Timeout(Duration.ofMinutes(2), changed = true)
        call(control, "setting" to "auto rotate", "action" to "off")
            .result() shouldBe DeviceSettingResult.Switch(DeviceSetting.AUTO_ROTATE, false, SwitchChange.TURNED)
    }

    "leftover words in the level are no level" {
        val control = FakeDeviceSettingsControl(brightness = 22)
        // "turn up the brightness" -> level "up": one step
        call(control, "setting" to "brightness", "action" to "up", "level" to "up")
            .result() shouldBe DeviceSettingResult.Brightness(60, auto = false, changed = true)
        // "turn on wifi" -> level "wifi": the connectivity skill's controls
        call(control, "setting" to "wifi", "action" to "on", "level" to "wifi")
        control.radios shouldContainExactly listOf(Radio.WIFI to RadioState.ON)
    }

    "a set command without a level GAR can read asks for one" {
        val control = FakeDeviceSettingsControl()
        val missing = call(control, "setting" to "brightness", "action" to "set")
            .shouldBeInstanceOf<GarAskSlotOutput>()
        missing.slot.kind shouldBe SlotKind.NUMBER
        val unclear = call(control, "setting" to "screen timeout", "action" to "set", "level" to "banana")
            .shouldBeInstanceOf<GarAskSlotOutput>()
        unclear.slot.kind shouldBe SlotKind.DURATION
        // the answer runs the command
        val answer = unclear.runner.interpret("five minutes", unclear.call.tool, unclear.slot, unclear.question)
            .shouldBeInstanceOf<GarAnswer.Value>()
        control.timeout shouldBe Duration.ofSeconds(30)
        unclear.runner.answered(FakeSkillContext(), unclear.call, unclear.slot, answer)
        control.timeout shouldBe Duration.ofMinutes(5)
    }

    "without the access GAR asks with a button, and opens the page on yes" {
        val control = FakeDeviceSettingsControl(writeAccess = false)
        val ask = call(control, "setting" to "brightness", "action" to "up")
            .shouldBeInstanceOf<DeviceSettingsAccessOutput>()
        ask.setting shouldBe DeviceSetting.BRIGHTNESS
        val (answerSkill, cancel) = ask.nextSkills("Open settings", "Not now")
        val ctx = FakeSkillContext()
        for ((input, yes) in listOf(
            "Open settings" to true, "Not now" to false, "yes" to true, "no" to false,
        )) {
            withClue(input) {
                answerSkill.score(ctx.forInput(input), input).second shouldBe yes
            }
        }
        cancel.correspondingSkillInfo shouldBe DeviceSettingsInfo
        (answerSkill as DeviceSettingsAccessAnswerSkill).generateOutput(ctx, false)
            .shouldBeInstanceOf<GarCancelledOutput>()
        control.openedWriteAccess shouldBe false
        answerSkill.generateOutput(ctx, true).result() shouldBe DeviceSettingResult.AccessOpened
        control.openedWriteAccess shouldBe true
        ask.answered shouldBe true
    }

    "a locked phone gets GAR's usual answer" {
        val control = FakeDeviceSettingsControl(locked = true)
        call(control, "setting" to "airplane mode", "action" to "on").shouldBeInstanceOf<GarLockedOutput>()
        DeviceSettingsAccessAnswerSkill.openAccess(control) shouldBe DeviceSettingResult.Locked
        // a question still works
        call(control, "setting" to "airplane mode", "action" to "status")
            .result() shouldBe DeviceSettingResult.Switch(DeviceSetting.AIRPLANE_MODE, false, SwitchChange.STATE)
    }

    "open_app calls for a settings page open that page, apps stay the open skill's" {
        val openApp = CATALOG.byName("open_app")!!
        fun openCall(what: String) = GarCall(openApp, mapOf("what" to what), "", mapOf("what" to what))
        SettingsPageCalls.pageOf(openCall("battery settings")) shouldBe SettingsPage.BATTERY
        SettingsPageCalls.pageOf(openCall("developer options")) shouldBe SettingsPage.DEVELOPER_OPTIONS
        SettingsPageCalls.pageOf(openCall("Spotify")) shouldBe null
        SettingsPageCalls.pageOf(openCall("Settings")) shouldBe null
        SettingsPageCalls.pageOf(GarCall(TOOL, mapOf(), "", mapOf("setting" to "battery saver"))) shouldBe null

        val control = FakeDeviceSettingsControl()
        SettingsPageCalls.output(FakeSkillContext(), openCall("battery settings")) { control }!!
            .result() shouldBe DeviceSettingResult.Page(SettingsPage.BATTERY)
        control.opened shouldContainExactly listOf(SettingsPage.BATTERY.intents)
    }

    "the app resolver reads a settings page before the launcher's apps" {
        val apps = DefaultGarSlotResolver(
            SlotResolvers(ENGLISH_PARSER_FORMATTER, "en"),
            readAppLabels = { listOf("Spotify", "Settings", "SoundCloud", "Phone", "Storage Analyzer") },
            settingsPage = SettingsPage::appNameFor,
        )
        val slot = CATALOG.byName("open_app")!!.slots.single()
        for ((said, resolved) in listOf(
            "battery settings" to "battery settings", "the display settings" to "display settings",
            "developer options" to "developer options", "settings" to "Settings",
            "spot if i" to "Spotify", "sound cloud" to "SoundCloud", "phone" to "Phone",
            // a page's name alone is an app's: "Storage Analyzer", or a question
            "storage analyzer" to "Storage Analyzer",
        )) {
            withClue(said) { apps.resolve(slot, said) shouldBe Resolution.Resolved(resolved) }
        }
    }
})
