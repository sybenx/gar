package org.stypox.dicio.skills.weather

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.R
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.online.GarOnlineAnswerSkill
import org.stypox.dicio.gar.online.GarOnlineGateSkill
import org.stypox.dicio.gar.online.GarOnlineIsOffOutput
import org.stypox.dicio.gar.online.GarOnlineSettingsImpl
import org.stypox.dicio.gar.online.GarOnlineSkill
import org.stypox.dicio.gar.online.GarOnlineStaysOffOutput
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.UserSettingsModule
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Weather apps in memory: what is installed, the setting, and which apps were opened. */
class FakeWeatherApps(
    var apps: List<WeatherApp> = listOf(),
    var value: String = WeatherApps.NOT_CHOSEN,
    var opens: Boolean = true,
) : WeatherAppAccess {
    val opened = mutableListOf<String>()

    override fun installed(): List<WeatherApp> = apps
    override suspend fun setting(): String = value
    override suspend fun setSetting(value: String) {
        this.value = value
    }

    override fun open(app: WeatherApp): Boolean {
        opened.add(app.pkg)
        return opens
    }
}

private val BREEZY = WeatherApp("org.breezyweather", "Breezy Weather")
private val CLIMA = WeatherApp("co.prestosole.clima", "Clima")
private val OSS = WeatherApp("com.akylas.weather", "OSS Weather")
private const val OWN = "cc.aaro.gar"

private val NOW: ZonedDateTime = ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, ZoneId.of("America/Denver"))
private val UNITS = WeatherUnits(ResolvedTemperatureUnit.FAHRENHEIT, imperial = true)
private val NO_NETWORK = WeatherHttp { throw IOException("Open-Meteo timed out") }

private object NoPlace : WeatherPlaces {
    override val countryCode = "US"
    override suspend fun homeCity() = ""
    override suspend fun setHomeCity(name: String) {}
    override suspend fun useLocation() = false
    override suspend fun setUseLocation(use: Boolean) {}
    override fun hasLocationPermission() = false
    override suspend fun location(): WeatherPlace? = null
}

private fun skill(apps: FakeWeatherApps, locked: Boolean = false) = GarWeatherSkill(
    WeatherInfo,
    Sentences.Weather["en"]!!,
    lookupFor = { WeatherLookup(OpenMeteo(NO_NETWORK), NoPlace, { null }) { NOW } },
    unitsFor = { UNITS },
    today = { NOW.toLocalDate() },
    isLocked = { locked },
    appsFor = { apps },
)

/** A request with a place, so the lookup goes to Open-Meteo, which fails. */
private val IN_DENVER = WeatherRequest(WeatherTopic.GENERAL, WeatherWhen.Now, "denver")

/** The weather strings by resource id, formatted like Android does. */
private val templates: Map<String, String> by lazy {
    val path = "src/main/res/values/strings_gar_weather.xml"
    val text = sequenceOf(File(path), File("app", path)).first { it.isFile }.readText()
    Regex("<string name=\"([a-z0-9_]+)\">(.*?)</string>").findAll(text)
        .associate { Pair(it.groupValues[1], it.groupValues[2].replace("\\'", "'")) }
}
private val names: Map<Int, String> by lazy {
    R.string::class.java.fields.associate { Pair(it.getInt(null), it.name) }
}

private fun say(id: Int, vararg args: Any): String =
    String.format(Locale.ROOT, templates[names[id]] ?: throw AssertionError("no string $id"), *args)

private suspend fun offSettings() = GarOnlineSettingsImpl(
    UserSettingsModule.newDataStoreForPreviews(), GarSettingsModule.newDataStoreForPreviews(),
).also { it.setEnabled(listOf("weather"), false) }

/**
 * gar:weather: the user's weather app when Open-Meteo isn't answering or the weather skill is off,
 * found among the launchable apps, picked once and remembered.
 */
class WeatherAppsTest : StringSpec({
    "weather apps: the known ones, and \"weather\" in the package or the name" {
        for ((pkg, label) in listOf(
            "org.breezyweather" to "Breezy Weather", "org.breezyweather.debug" to "Breezy Weather",
            "co.prestosole.clima" to "Clima", "com.davidtakac.bura" to "Bura",
            "wangdaye.com.geometricweather" to "Geometric Weather", "com.ominous.quickweather" to "QuickWeather",
            "com.example.forecast" to "Simple Weather", "com.example.my_weather" to "Sky",
        )) {
            withClue(pkg) { WeatherApps.isWeatherApp(pkg, label, OWN) shouldBe true }
        }
        for ((pkg, label) in listOf(
            "net.osmand.plus" to "OsmAnd", "app.comaps.fdroid" to "CoMaps",
            "com.example.weatherly" to "Weatherly", OWN to "Garble weather", "$OWN.debug" to "Weather",
        )) {
            withClue(pkg) { WeatherApps.isWeatherApp(pkg, label, OWN) shouldBe false }
        }
    }

    "lists start with the known apps, Breezy Weather first, then by name" {
        val other = WeatherApp("com.example.weather", "Another Weather")
        WeatherApps.ordered(listOf(other, CLIMA, BREEZY, OSS, BREEZY)) shouldBe
            listOf(BREEZY, CLIMA, OSS, other)
    }

    "no app, one app, several: nothing, that app, or GAR asks once" {
        WeatherApps.choose(WeatherApps.NOT_CHOSEN, listOf()) shouldBe WeatherAppChoice.NoApp
        WeatherApps.choose(WeatherApps.NOT_CHOSEN, listOf(CLIMA)) shouldBe WeatherAppChoice.App(CLIMA)
        WeatherApps.choose(WeatherApps.NOT_CHOSEN, listOf(CLIMA, BREEZY)) shouldBe
            WeatherAppChoice.Ask(listOf(BREEZY, CLIMA))
        // the pick, while installed
        WeatherApps.choose(CLIMA.pkg, listOf(BREEZY, CLIMA)) shouldBe WeatherAppChoice.App(CLIMA)
        WeatherApps.choose("gone.weather", listOf(BREEZY, CLIMA)) shouldBe
            WeatherAppChoice.Ask(listOf(BREEZY, CLIMA))
        WeatherApps.choose("gone.weather", listOf(CLIMA)) shouldBe WeatherAppChoice.App(CLIMA)
        // "Don't open an app"
        WeatherApps.choose(WeatherApps.NONE, listOf(BREEZY)) shouldBe WeatherAppChoice.NoApp
    }

    "answers to \"Which weather app?\"" {
        val apps = listOf(BREEZY, CLIMA, OSS)
        for ((input, app) in listOf(
            "Breezy Weather" to BREEZY, "breezy" to BREEZY, "use breezy please" to BREEZY,
            "clima" to CLIMA, "the second one" to CLIMA, "oss weather" to OSS, "OSS" to OSS,
        )) {
            withClue(input) { WeatherApps.pick(input, apps) shouldBe app }
        }
        for (input in listOf("cancel", "never mind", "", "what's the weather in paris", "weather")) {
            withClue(input) { WeatherApps.pick(input, apps).shouldBeNull() }
        }
    }

    "a bare \"weather\" names no app, even with one \"... Weather\" installed" {
        for (input in listOf("weather", "the weather", "weather app")) {
            withClue(input) { WeatherApps.pick(input, listOf(BREEZY, CLIMA)).shouldBeNull() }
        }
        WeatherApps.pick("breezy weather", listOf(BREEZY, CLIMA)) shouldBe BREEZY
    }

    "a tapped row picks that app, also when two apps share a name" {
        val woheller = WeatherApp("org.woheller69.weather", "Weather")
        val oem = WeatherApp("com.oem.weather", "Weather")
        val ask = WeatherAppFallback.output(
            FakeWeatherApps(listOf(woheller, oem)), WeatherAppReason.UNREACHABLE, locked = false,
        ).shouldBeInstanceOf<WeatherAppPickOutput>()
        ask.pick("Weather") shouldBe ask.apps.first()
        ask.pick("the second one") shouldBe ask.apps[1]
    }

    "Open-Meteo not answering: the only app is opened" {
        val apps = FakeWeatherApps(listOf(BREEZY))
        val out = skill(apps).generateOutput(FakeSkillContext(), IN_DENVER)
            .shouldBeInstanceOf<WeatherAppOpenedOutput>()
        out.app shouldBe BREEZY
        out.reason shouldBe WeatherAppReason.UNREACHABLE
        apps.opened shouldBe listOf(BREEZY.pkg)
    }

    "Open-Meteo not answering, no app: the message, which doesn't blame the internet" {
        for (apps in listOf(
            FakeWeatherApps(), FakeWeatherApps(listOf(BREEZY), value = WeatherApps.NONE),
            FakeWeatherApps(listOf(BREEZY), opens = false),
        )) {
            skill(apps).generateOutput(FakeSkillContext(), IN_DENVER).shouldBeInstanceOf<WeatherMessageOutput>()
        }
        say(R.string.gar_weather_no_connection) shouldBe "Open-Meteo isn't answering right now. Try again in a bit."
    }

    "several apps: GAR asks once, and the pick is saved with Always on" {
        val apps = FakeWeatherApps(listOf(CLIMA, BREEZY))
        val ask = skill(apps).generateOutput(FakeSkillContext(), IN_DENVER)
            .shouldBeInstanceOf<WeatherAppPickOutput>()
        ask.apps shouldBe listOf(BREEZY, CLIMA)
        ask.remember shouldBe true
        apps.opened shouldBe listOf()
        val done = ask.choose(CLIMA).shouldBeInstanceOf<WeatherAppOpenedOutput>()
        done.saved shouldBe true
        done.reason.shouldBeNull()
        apps.value shouldBe CLIMA.pkg
        apps.opened shouldBe listOf(CLIMA.pkg)
        // next time, no question
        skill(apps).generateOutput(FakeSkillContext(), IN_DENVER)
            .shouldBeInstanceOf<WeatherAppOpenedOutput>().app shouldBe CLIMA
    }

    "the pick isn't saved with Always off, nor on a locked phone" {
        val apps = FakeWeatherApps(listOf(CLIMA, BREEZY))
        val off = WeatherAppFallback.output(apps, WeatherAppReason.UNREACHABLE, locked = false)
            .shouldBeInstanceOf<WeatherAppPickOutput>()
        off.always = false
        off.choose(BREEZY).shouldBeInstanceOf<WeatherAppOpenedOutput>().saved shouldBe false
        apps.value shouldBe WeatherApps.NOT_CHOSEN

        val locked = skill(apps, locked = true).generateOutput(FakeSkillContext(), IN_DENVER)
            .shouldBeInstanceOf<WeatherAppPickOutput>()
        locked.remember shouldBe false
        locked.choose(BREEZY).shouldBeInstanceOf<WeatherAppOpenedOutput>().saved shouldBe false
        apps.value shouldBe WeatherApps.NOT_CHOSEN
        apps.opened shouldBe listOf(BREEZY.pkg, BREEZY.pkg)
    }

    "an app that won't start after the pick: said plainly" {
        val apps = FakeWeatherApps(listOf(CLIMA, BREEZY), opens = false)
        WeatherAppFallback.output(apps, WeatherAppReason.UNREACHABLE, locked = false)
            .shouldBeInstanceOf<WeatherAppPickOutput>()
            .choose(CLIMA).shouldBeInstanceOf<WeatherMessageOutput>()
    }

    "weather skill off: the app is opened instead of \"it's off\", and without an app nothing changes" {
        val settings = offSettings()
        val apps = FakeWeatherApps(listOf(BREEZY))
        val gate = GarOnlineGateSkill(GarOnlineSkill.WEATHER, skill(apps), isDeviceLocked = { false }) { settings }
        gate.generateOutput(FakeSkillContext(), IN_DENVER).shouldBeInstanceOf<WeatherAppOpenedOutput>()
            .reason shouldBe WeatherAppReason.SKILL_OFF
        apps.opened shouldBe listOf(BREEZY.pkg)

        val none = GarOnlineGateSkill(
            GarOnlineSkill.WEATHER, skill(FakeWeatherApps()), isDeviceLocked = { false },
        ) { settings }
        none.generateOutput(FakeSkillContext(), IN_DENVER).shouldBeInstanceOf<GarOnlineIsOffOutput>()
    }

    "\"keep it off\": the skill stays off and the app is opened" {
        val settings = GarOnlineSettingsImpl(
            UserSettingsModule.newDataStoreForPreviews(), GarSettingsModule.newDataStoreForPreviews(),
        )
        val apps = FakeWeatherApps(listOf(BREEZY, CLIMA))
        val gate = GarOnlineGateSkill(GarOnlineSkill.WEATHER, skill(apps), isDeviceLocked = { false }) { settings }
        val answer = GarOnlineAnswerSkill(gate, IN_DENVER, settings, "Turn it on", "Keep it off")
        // several apps: the question replaces GAR's online question
        answer.generateOutput(FakeSkillContext(), false).shouldBeInstanceOf<GarFollowUpOutput>()
            .output.shouldBeInstanceOf<WeatherAppPickOutput>().reason shouldBe WeatherAppReason.STAYS_OFF
        settings.wasAsked("weather") shouldBe true

        val noApp = GarOnlineGateSkill(
            GarOnlineSkill.WEATHER, skill(FakeWeatherApps()), isDeviceLocked = { false },
        ) { settings }
        GarOnlineAnswerSkill(noApp, IN_DENVER, settings, "Turn it on", "Keep it off")
            .generateOutput(FakeSkillContext(), false).shouldBeInstanceOf<GarOnlineStaysOffOutput>()
    }

    "the copy" {
        val app = "Breezy Weather"
        say(WeatherAppFallback.openedText(WeatherAppReason.UNREACHABLE), app) shouldBe
            "Open-Meteo isn't answering, so I opened Breezy Weather."
        say(WeatherAppFallback.openedText(WeatherAppReason.SKILL_OFF), app) shouldBe
            "The weather skill is off, so I opened Breezy Weather."
        say(WeatherAppFallback.openedText(WeatherAppReason.STAYS_OFF), app) shouldBe
            "OK, the weather skill stays off, so I opened Breezy Weather."
        say(WeatherAppFallback.openedText(null), app) shouldBe "Opening Breezy Weather."
        say(WeatherAppFallback.leadText(WeatherAppReason.UNREACHABLE)) + " " +
            say(R.string.gar_weather_which_app, "Breezy Weather or Clima") shouldBe
            "Open-Meteo isn't answering. Which weather app? Breezy Weather or Clima?"
        say(R.string.gar_weather_open_app, app) shouldBe "Open Breezy Weather"
        for ((name, text) in templates.filterKeys { it.startsWith("gar_weather") }) {
            withClue(name) {
                text shouldNotContain "—"
                text shouldNotContain "internet connection"
            }
        }
    }
})
