package org.stypox.dicio.skills.weather

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.datastore.core.DataStore
import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.Resolution
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.call.parseOptionIndex
import org.stypox.dicio.gar.settings.GarSettings

/** An installed weather app: its [pkg] and [label] ("Breezy Weather"). */
data class WeatherApp(val pkg: String, val label: String)

/** Which weather app GAR opens, see [WeatherApps.choose]. */
sealed interface WeatherAppChoice {
    data class App(val app: WeatherApp) : WeatherAppChoice

    /** Several apps and none picked yet: GAR asks once ([WeatherAppPickOutput]). */
    data class Ask(val apps: List<WeatherApp>) : WeatherAppChoice

    /** No weather app installed, or the user said not to open one. */
    data object NoApp : WeatherAppChoice
}

/**
 * GAR (gar:weather). The user's own weather app, opened when Open-Meteo isn't answering or the
 * weather skill is off, and from the forecast card. Android has no weather intent, so GAR only
 * launches the app, which it finds among the launchable apps by a list of known weather apps
 * ([KNOWN]) and, for the rest, "weather" in the package or the name. Android's app categories
 * have none for weather. Plain JVM, so tests can call it.
 */
object WeatherApps {
    /** `GarSettings.weather_app` before the user picked: the only app, else GAR asks once. */
    const val NOT_CHOSEN = ""

    /** "Don't open an app": no app when Open-Meteo fails and no button on the card. */
    const val NONE = "none"

    /** Weather apps whose package doesn't say "weather", or that come first in lists. */
    val KNOWN: List<String> = listOf(
        "org.breezyweather", // Breezy Weather, which also shares its forecasts with GAR
        "wangdaye.com.geometricweather", // Geometric Weather
        "co.prestosole.clima", // Clima
        "com.davidtakac.bura", // Bura
        "com.ominous.quickweather", // QuickWeather
        "com.pranshulgg.weather_master_app", // WeatherMaster
        "com.yoshi.rain", // Rain
        "com.akylas.weather", // OSS Weather
        "org.woheller69.weather", // woheller69's Weather
        "cz.martykan.forecastie", // Forecastie
        "com.google.android.apps.weather", // Google's weather, not on GrapheneOS
    )

    /** Whether the launchable app [pkg] named [label] is a weather app (never GAR, [own]). */
    fun isWeatherApp(pkg: String, label: String, own: String): Boolean {
        if (pkg == own || pkg.startsWith("$own.")) {
            return false
        }
        if (KNOWN.any { pkg == it || pkg.startsWith("$it.") }) {
            return true
        }
        val parts = pkg.lowercase().split('.', '_')
        return parts.any { it == "weather" || it.endsWith("weather") } ||
            label.lowercase().split(Regex("[^\\p{L}]+")).any { it == "weather" }
    }

    /** The apps in lists: the known ones in [KNOWN]'s order (Breezy Weather first), then by name. */
    fun ordered(installed: List<WeatherApp>): List<WeatherApp> = installed
        .distinctBy { it.pkg }
        .sortedWith(compareBy({ knownIndex(it.pkg) }, { it.label.lowercase() }))

    private fun knownIndex(pkg: String): Int =
        KNOWN.indexOfFirst { pkg == it || pkg.startsWith("$it.") }.let { if (it < 0) KNOWN.size else it }

    /**
     * @param setting `GarSettings.weather_app`: [NOT_CHOSEN], [NONE] or a package; a package that
     * is no longer installed counts as not chosen
     */
    fun choose(setting: String, installed: List<WeatherApp>): WeatherAppChoice {
        if (setting == NONE || installed.isEmpty()) {
            return WeatherAppChoice.NoApp
        }
        installed.firstOrNull { it.pkg == setting }?.let { return WeatherAppChoice.App(it) }
        val ordered = ordered(installed)
        // nothing to choose between; a second app installed later asks
        return if (ordered.size == 1) WeatherAppChoice.App(ordered[0]) else WeatherAppChoice.Ask(ordered)
    }

    /** The words an answer to "Which weather app?" is matched with: "Breezy Weather", "breezy". */
    fun spokenNames(app: WeatherApp): List<String> {
        val short = app.label.split(" ").filter { !it.equals("weather", ignoreCase = true) }
            .joinToString(" ").trim()
        return listOfNotNull(app.label, short.takeIf { it.isNotEmpty() && it != app.label })
    }

    /** The app [input] names ("Breezy", "breezy weather", "the second one"), or null. */
    fun pick(input: String, apps: List<WeatherApp>): WeatherApp? {
        if (isCancelPhrase(input) || normalizeAnswer(input).isEmpty()) {
            return null
        }
        apps.firstOrNull { normalizeAnswer(it.label) == normalizeAnswer(input) }?.let { return it }
        parseOptionIndex(input, apps.size)?.let { return apps[it] }
        val words = normalizeAnswer(input).split(" ").filter { it !in LEADS }.ifEmpty { return null }
        val names = apps.flatMap { app -> spokenNames(app).map { Pair(app, it) } }
        return when (val match = NameMatcher.match(words.joinToString(" "), names, { it.second })) {
            is Resolution.Resolved -> match.value.first
            // several spellings of one app are still that app
            is Resolution.Ambiguous -> match.options.map { it.first }.distinct().singleOrNull()
            else -> null
        }
    }

    /**
     * Words around a name: "use breezy", "open clima please". "weather" too, so a bare "weather"
     * (the user repeating the request) names no app, even with only one "... Weather" installed.
     */
    private val LEADS = setOf("use", "with", "in", "open", "it", "please", "just", "the", "app", "weather")
}

/** The installed weather apps, the "Weather app" setting, and opening an app. */
interface WeatherAppAccess {
    fun installed(): List<WeatherApp>

    suspend fun setting(): String

    suspend fun setSetting(value: String)

    /** Launches [app]; false when it can't be started. */
    fun open(app: WeatherApp): Boolean

    companion object {
        /**
         * Opens apps through the skill's context, which takes the sheet away first and, over the
         * lock screen, asks to unlock before the app comes up (`GarLeavingContext`).
         */
        fun of(ctx: SkillContext): WeatherAppAccess = of(ctx.android)

        fun of(context: Context): WeatherAppAccess = AndroidWeatherApps(
            context,
            EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
                .garSettings(),
        )
    }
}

class AndroidWeatherApps(
    private val context: Context,
    private val settings: DataStore<GarSettings>,
) : WeatherAppAccess {

    @SuppressLint("QueryPermissionsNeeded") // GAR can see all apps (QUERY_ALL_PACKAGES)
    override fun installed(): List<WeatherApp> {
        val packages = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packages.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo }
            .distinctBy { it.packageName }
            .mapNotNull { info ->
                val label = try {
                    packages.getApplicationLabel(packages.getApplicationInfo(info.packageName, 0)).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    return@mapNotNull null
                }
                WeatherApp(info.packageName, label)
                    .takeIf { WeatherApps.isWeatherApp(it.pkg, it.label, context.packageName) }
            }
    }

    override suspend fun setting(): String = settings.data.first().weatherApp

    override suspend fun setSetting(value: String) {
        settings.updateData { it.toBuilder().setWeatherApp(value).build() }
    }

    override fun open(app: WeatherApp): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.pkg) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Can't open ${app.pkg}", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to open ${app.pkg}", e)
            false
        }
    }

    private companion object {
        const val TAG = "GarWeather"
    }
}
