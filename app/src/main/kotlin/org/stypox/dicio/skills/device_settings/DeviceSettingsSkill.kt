package org.stypox.dicio.skills.device_settings

import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.ResolvedNumber
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.gar.call.DefaultGarSlotResolver
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarCallRunner
import org.stypox.dicio.gar.call.GarSlotResolver
import org.stypox.dicio.gar.call.GarToolSkill
import org.stypox.dicio.gar.call.GarValueFormatter
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.DeviceSettings
import java.time.Duration

/**
 * Device settings (gar/PARITY.md, batch 3): brightness, adaptive brightness, auto-rotate, screen
 * timeout, ringer mode, the status of settings, and the pages of what only Android may switch
 * (airplane mode, mobile data, hotspot, NFC, location, battery saver, dark theme) or that the
 * user wants to open ("open battery settings"). Its sentences are English only and take an input
 * only when its words name the setting ([DeviceSettingWords]); the model reaches the skill
 * through the LLM-only `device_setting` tool, which also takes Wi-Fi and Bluetooth (the
 * connectivity skill's controls).
 *
 * A level stays as said ([SaidDeviceSetting]) until GAR's slot resolution reads it, asking when it
 * can't, as the volume skill does for its sentences.
 */
class DeviceSettingsSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<DeviceSettings>,
    private val resolverFactory: (SkillContext) -> GarSlotResolver = DefaultGarSlotResolver::forContext,
    private val formatterFactory: (SkillContext) -> GarValueFormatter = GarValueFormatter::forContext,
    private val controlFactory: (SkillContext) -> DeviceSettingsControl = { ctx ->
        AndroidDeviceSettingsControl(ctx.android)
    },
) : Skill<DeviceSettingsInput>(correspondingSkillInfo, data.specificity), GarToolSkill {

    override fun score(ctx: SkillContext, input: String): Pair<Score, DeviceSettingsInput> {
        val (score, sentence) = data.score(ctx, input)
        val match = DeviceSettingWords.inputOf(sentence, input)
        return if (match == null) Pair(AlwaysWorstScore, NotADeviceSetting) else Pair(score, match)
    }

    override fun inputFromSlots(slots: Map<String, Any?>): DeviceSettingsInput =
        DeviceSettingTool.inputFromSlots(slots)

    override suspend fun generateOutput(ctx: SkillContext, inputData: DeviceSettingsInput): SkillOutput {
        val control = controlFactory(ctx)
        val yesNo = Sentences.UtilYesNo[ctx.sentencesLanguage]
        val logic = DeviceSettingsLogic(control)
        return when (inputData) {
            is DeviceSettingCommand -> DeviceSettingsOutput.of(logic.run(inputData), control, yesNo)
            is OpenSettingsPage -> DeviceSettingsOutput.of(
                logic.openPage(inputData.page, inputData.switch), control, yesNo,
            )
            HeadphonesStatus -> DeviceSettingsOutput.of(logic.headphones(), control, yesNo)
            is SaidDeviceSetting -> said(ctx, inputData)
                ?: DeviceSettingsOutput.of(logic.run(commandOf(ctx, inputData)), control, yesNo)
            NotADeviceSetting -> DeviceSettingsOutput(DeviceSettingResult.Failed)
        }
    }

    /**
     * GAR's question for a level a set command needs and can't read ("set the brightness to
     * banana": "What level?"), or null to run the command right away.
     */
    private suspend fun said(ctx: SkillContext, said: SaidDeviceSetting): SkillOutput? {
        if (!needsLevel(said)) {
            return null
        }
        val level = said.level?.let { DeviceSettingWords.cleanLevel(it) }
        val readable = level != null &&
            (levelWord(said, level) != null || resolve(ctx, said, level) != null)
        if (readable) {
            return null
        }
        // resolves like the model's call of a tool with a typed level, then runs this skill again
        // with the DeviceSettingCommand from inputFromSlots
        val spec = DeviceSettingTool.levelSpec(said.setting)
        return GarCallRunner(
            targetInfo = correspondingSkillInfo,
            target = this,
            confirm = false,
            resolver = resolverFactory(ctx),
            formatter = formatterFactory(ctx),
            yesNoData = Sentences.UtilYesNo[ctx.sentencesLanguage],
        ).next(
            ctx,
            GarCall(
                tool = spec,
                rawSlots = listOfNotNull(level?.let { DeviceSettingTool.LEVEL to it }).toMap(),
                utterance = "",
                values = mapOf(
                    DeviceSettingTool.SETTING to said.setting.slotValue,
                    DeviceSettingTool.ACTION to said.action.slotValue,
                ),
            ),
            nested = false,
        )
    }

    /** Only a set command of a setting with a level needs one; up and down take a step. */
    private fun needsLevel(said: SaidDeviceSetting): Boolean =
        said.setting.takesLevel && said.action == DeviceAction.SET

    /** The command for [said], with its level when GAR can read it. */
    private fun commandOf(ctx: SkillContext, said: SaidDeviceSetting): DeviceSettingCommand {
        val level = said.level?.let { DeviceSettingWords.cleanLevel(it) }
            ?.takeIf { said.setting.takesLevel && said.action in LEVEL_ACTIONS }
            ?.let { levelWord(said, it) ?: resolve(ctx, said, it) }
        return DeviceSettingCommand(said.setting, said.action, level)
    }

    /** "max", "the lowest", or "half" for a setting whose level is an amount. */
    private fun levelWord(said: SaidDeviceSetting, level: String): DeviceLevel? =
        DeviceSettingWords.levelWord(level)
            ?.takeUnless { it is DeviceLevel.Amount && said.setting == DeviceSetting.SCREEN_TIMEOUT }

    /** [level] read for [said]'s setting (a number, or a duration for the timeout), or null. */
    private fun resolve(ctx: SkillContext, said: SaidDeviceSetting, level: String): DeviceLevel? {
        val spec = DeviceSettingTool.levelSpec(said.setting)
        val slot = spec.slots.first { it.name == DeviceSettingTool.LEVEL }
        val resolved = resolverFactory(ctx).resolve(slot, level) as? Resolution.Resolved<*>
            ?: return null
        return when (val value = resolved.value) {
            is ResolvedNumber -> DeviceLevel.Amount(value)
            is Duration -> DeviceLevel.Time(value)
            else -> null
        }
    }

    companion object {
        private val LEVEL_ACTIONS = setOf(DeviceAction.UP, DeviceAction.DOWN, DeviceAction.SET)
    }
}
