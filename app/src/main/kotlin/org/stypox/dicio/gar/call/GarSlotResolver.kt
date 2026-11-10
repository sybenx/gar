package org.stypox.dicio.gar.call

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.SlotResolvers
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import org.dicio.skill.context.SkillContext

/**
 * Turns one raw slot string into a value, by slot kind. Resolved values are:
 * [java.time.Duration] (duration), [cc.aaro.gar.router.resolve.ResolvedDateTime] (datetime),
 * [cc.aaro.gar.router.resolve.ResolvedDate] (date), [cc.aaro.gar.router.resolve.ResolvedTime]
 * (time), [cc.aaro.gar.router.resolve.ResolvedNumber] (number) and [String] (text, contact, app
 * and enum, where enum values are the declared spelling).
 *
 * App names are matched against the launcher labels ([NameMatcher]), so the confirmation and the
 * open skill get the label of the app that will open ("spot if i" -> "Spotify"), and close
 * matches become a question. Contact names stay as said: the telephone skill matches them itself
 * and always asks before calling.
 */
fun interface GarSlotResolver {
    fun resolve(slot: SlotSpec, raw: String): Resolution<Any?>
}

/**
 * [GarSlotResolver] on top of `:gar:router`'s [SlotResolvers].
 *
 * @param readAppLabels the labels of the launcher apps, read when an app slot is first resolved;
 * if there are none (or reading them failed) app names stay as said
 * @param settingsPage gar:settings: the name of the page of Android's Settings that an app slot
 * names ("battery settings"), or null; `SettingsPageCalls` opens that page instead of an app
 * @param website gar:places: the web address that an app slot names ("reddit dot com" ->
 * "reddit.com"), or null; the open skill opens it in the browser (`WebsiteSkill.outputFor`)
 */
class DefaultGarSlotResolver(
    private val resolvers: SlotResolvers,
    readAppLabels: () -> List<String> = { listOf() },
    private val settingsPage: (String) -> String? = { null },
    private val website: (String) -> String? = { null },
) : GarSlotResolver {
    private val appLabels by lazy(readAppLabels)

    override fun resolve(slot: SlotSpec, raw: String): Resolution<Any?> {
        return when (slot.kind) {
            SlotKind.DURATION -> resolvers.duration(raw)
            SlotKind.DATETIME -> resolvers.dateTime(raw)
            SlotKind.DATE -> resolvers.date(raw)
            SlotKind.TIME -> resolvers.time(raw)
            SlotKind.NUMBER -> resolvers.number(raw)
            SlotKind.ENUM -> resolveEnum(slot, raw)
            SlotKind.APP -> (settingsPage(raw) ?: website(raw))?.let { Resolution.Resolved(it) }
                ?: resolveName(raw, appLabels)
            SlotKind.TEXT, SlotKind.CONTACT -> resolveText(raw)
        }
    }

    companion object {
        private const val TAG = "GarSlotResolver"

        fun forContext(ctx: SkillContext): GarSlotResolver = DefaultGarSlotResolver(
            SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage),
            readAppLabels = { launcherLabels(ctx.android.packageManager) },
            settingsPage = org.stypox.dicio.skills.device_settings.SettingsPage::appNameFor,
            website = org.stypox.dicio.skills.website.WebAddress::displayFor, // gar:places
        )

        /** The labels of the apps the launcher shows, each once, like the open skill sees them. */
        fun launcherLabels(packageManager: PackageManager): List<String> = try {
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            @SuppressLint("QueryPermissionsNeeded") // the open skill needs all apps too
            val activities = packageManager.queryIntentActivities(intent, 0)
            activities.map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .map { packageManager.getApplicationLabel(it).toString() }
                .distinct()
        } catch (e: Exception) {
            Log.w(TAG, "Can't list the launcher apps", e)
            listOf()
        }

        /** [raw] matched against [candidates], or [raw] itself when there are no candidates. */
        fun resolveName(raw: String, candidates: List<String>): Resolution<String> {
            if (candidates.isEmpty()) {
                return resolveText(raw)
            }
            return NameMatcher.match(raw, candidates, { it })
        }

        fun resolveText(raw: String): Resolution<String> {
            val text = raw.trim()
            return if (text.isEmpty()) {
                Resolution.Unresolvable("empty")
            } else {
                Resolution.Resolved(text)
            }
        }

        fun resolveEnum(slot: SlotSpec, raw: String): Resolution<String> {
            val normalized = normalizeAnswer(raw)
            return slot.enumValues.firstOrNull { normalizeAnswer(it) == normalized }
                ?.let { Resolution.Resolved(it) }
                ?: Resolution.Unresolvable("not one of ${slot.enumValues}")
        }
    }
}
