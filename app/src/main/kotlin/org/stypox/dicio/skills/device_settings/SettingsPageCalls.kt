package org.stypox.dicio.skills.device_settings

import cc.aaro.gar.router.tools.SlotKind
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.open.OpenInfo

/**
 * The model's `open_app` calls for a page of Android's Settings, which no launcher app is: GAR's
 * app resolver reads a page's name before the launcher's apps ([SettingsPage.appNameFor],
 * "battery settings", "developer options"), and a call that resolved to one opens that page
 * instead of the Settings app's start page. No new tool, no prompt change (gar/PARITY.md,
 * "Keeping the model's prompt small").
 */
object SettingsPageCalls {
    /** The page an `open_app` call resolved to, or null for an app. */
    fun pageOf(call: GarCall): SettingsPage? {
        if (call.tool.skillId != OpenInfo.id) {
            return null
        }
        return call.tool.slots.filter { it.kind == SlotKind.APP }
            .firstNotNullOfOrNull { call.values[it.name] as? String }
            ?.let { SettingsPage.fromAppName(it) }
    }

    /** The output of an `open_app` call for a settings page, or null to run the open skill. */
    fun output(
        ctx: SkillContext,
        call: GarCall,
        controlFactory: (SkillContext) -> DeviceSettingsControl = { AndroidDeviceSettingsControl(it.android) },
    ): SkillOutput? {
        val page = pageOf(call) ?: return null
        val control = controlFactory(ctx)
        return DeviceSettingsOutput.of(
            DeviceSettingsLogic(control).openPage(page),
            control,
            Sentences.UtilYesNo[ctx.sentencesLanguage],
        )
    }
}
