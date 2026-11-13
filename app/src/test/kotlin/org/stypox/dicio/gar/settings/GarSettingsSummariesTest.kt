package org.stypox.dicio.gar.settings

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.settings.datastore.WakeDevice
import org.stypox.dicio.skills.places.COMAPS_APP
import org.stypox.dicio.skills.places.MapAppChoice
import org.stypox.dicio.skills.places.ORGANIC_APP
import org.stypox.dicio.skills.places.OSMAND_APP
import org.stypox.dicio.skills.weather.WeatherApp
import org.stypox.dicio.skills.weather.WeatherApps
import java.io.File

private val BREEZY = WeatherApp("org.breezyweather", "Breezy Weather")
private val BURA = WeatherApp("com.davidtakac.bura", "Bura")

private fun string(file: String, name: String): String {
    val path = "src/main/res/values/$file"
    val text = sequenceOf(File(path), File("app", path)).first { it.isFile }.readText()
    return Regex("<string name=\"$name\"[^>]*>(.*?)</string>").find(text)!!.groupValues[1]
}

class GarSettingsSummariesTest : StringSpec({
    "weather app: the value, and the detail only when an app opens or Garble will ask" {
        val cases = listOf(
            Triple(WeatherApps.NONE, listOf(BREEZY), WeatherAppSummary.Off),
            Triple(WeatherApps.NONE, null, WeatherAppSummary.Off),
            Triple(WeatherApps.NOT_CHOSEN, null, WeatherAppSummary.Loading),
            Triple(WeatherApps.NOT_CHOSEN, listOf(), WeatherAppSummary.NoneFound),
            Triple(BURA.pkg, listOf(), WeatherAppSummary.NoneFound),
            // the only app opens without asking, so it counts as chosen
            Triple(WeatherApps.NOT_CHOSEN, listOf(BREEZY), WeatherAppSummary.App("Breezy Weather")),
            Triple(WeatherApps.NOT_CHOSEN, listOf(BREEZY, BURA), WeatherAppSummary.NotChosen),
            Triple(BURA.pkg, listOf(BREEZY, BURA), WeatherAppSummary.App("Bura")),
            // uninstalled since: not chosen again
            Triple("gone.weather", listOf(BREEZY, BURA), WeatherAppSummary.NotChosen),
        )
        for ((setting, installed, summary) in cases) {
            GarSettingsSummaries.weatherApp(setting, installed) shouldBe summary
        }
        GarSettingsSummaries.weatherAppShowsDetail(WeatherAppSummary.App("Bura")) shouldBe true
        GarSettingsSummaries.weatherAppShowsDetail(WeatherAppSummary.NotChosen) shouldBe true
        GarSettingsSummaries.weatherAppShowsDetail(WeatherAppSummary.Off) shouldBe false
        GarSettingsSummaries.weatherAppShowsDetail(WeatherAppSummary.NoneFound) shouldBe false
        GarSettingsSummaries.weatherAppShowsDetail(WeatherAppSummary.Loading) shouldBe false
    }

    "navigate with: the picked app, Android's default, the only app, else not chosen" {
        val two = MapAppsState(listOf(OSMAND_APP, ORGANIC_APP), androidDefault = null)
        GarSettingsSummaries.navigateWith(MapAppChoice.ASK, null) shouldBe NavigateWithSummary.Ask
        GarSettingsSummaries.navigateWith(MapAppChoice.NOT_CHOSEN, null) shouldBe NavigateWithSummary.Loading
        GarSettingsSummaries.navigateWith(MapAppChoice.NOT_CHOSEN, MapAppsState(listOf(), null)) shouldBe
            NavigateWithSummary.NoneFound
        GarSettingsSummaries.navigateWith(ORGANIC_APP.pkg, two) shouldBe NavigateWithSummary.App("Organic Maps")
        GarSettingsSummaries.navigateWith(MapAppChoice.NOT_CHOSEN, two) shouldBe NavigateWithSummary.NotChosen
        GarSettingsSummaries.navigateWith("gone.maps", two) shouldBe NavigateWithSummary.NotChosen
        GarSettingsSummaries.navigateWith(MapAppChoice.NOT_CHOSEN, two.copy(androidDefault = OSMAND_APP)) shouldBe
            NavigateWithSummary.AndroidDefault("OsmAnd")
        // a picked app wins over Android's default
        GarSettingsSummaries.navigateWith(COMAPS_APP.pkg, MapAppsState(listOf(OSMAND_APP, COMAPS_APP), OSMAND_APP)) shouldBe
            NavigateWithSummary.App("CoMaps")
        // the only app opens without asking, as MapAppChoice.choose does
        GarSettingsSummaries.navigateWith(MapAppChoice.NOT_CHOSEN, MapAppsState(listOf(OSMAND_APP), null)) shouldBe
            NavigateWithSummary.App("OsmAnd")
    }

    "wake word: the description matches the value" {
        GarSettingsSummaries.wakeWord(WakeDevice.WAKE_DEVICE_OWW, isHeyDicio = true) shouldBe WakeWordSummary.HEY_DICIO
        GarSettingsSummaries.wakeWord(WakeDevice.WAKE_DEVICE_OWW, isHeyDicio = false) shouldBe WakeWordSummary.CUSTOM
        GarSettingsSummaries.wakeWord(WakeDevice.WAKE_DEVICE_UNSET, isHeyDicio = true) shouldBe WakeWordSummary.HEY_DICIO
        // off says so, whichever model is on the phone
        GarSettingsSummaries.wakeWord(WakeDevice.WAKE_DEVICE_NOTHING, isHeyDicio = true) shouldBe WakeWordSummary.OFF
        GarSettingsSummaries.wakeWord(WakeDevice.WAKE_DEVICE_NOTHING, isHeyDicio = false) shouldBe WakeWordSummary.OFF
        string("strings_gar_settings_pages.xml", "gar_voice_wake_on") shouldBe "On"
        string("strings_gar_settings_pages.xml", "gar_voice_wake_off") shouldBe "Off"
        string("strings_gar_settings_pages.xml", "gar_voice_wake_method_summary_off") shouldBe
            "Garble doesn\\'t listen in the background"
    }

    "wait for speech: Whisper waits three seconds a step" {
        GarSettingsSummaries.whisperWaitSeconds(1) shouldBe 3
        GarSettingsSummaries.whisperWaitSeconds(2) shouldBe 6
        GarSettingsSummaries.whisperWaitSeconds(7) shouldBe 21
    }

    "settings copy: no dash between a value and its description, and pages named as they are" {
        string("strings_gar_settings_pages.xml", "gar_settings_description_with_value") shouldBe "%2\$s\\n%1\$s"
        string("strings_gar_weather.xml", "gar_weather_app_saved") shouldBe
            "Saved. Change it in Settings, Skills, Weather, Weather app."
        string("strings_gar_places.xml", "skill_places_app_saved") shouldBe
            "Saved. Change it in Settings, You and places, Navigate with."
        string("strings_gar_sms.xml", "skill_own_contact_remembered") shouldBe
            "OK, you\\'re %1\$s. You can change that in Settings, You and places."
    }

    "GAR's settings strings have no em dashes" {
        val dir = sequenceOf(File("src/main/res/values"), File("app/src/main/res/values")).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.startsWith("strings_gar") }!!
        for (file in files) {
            Regex("<string name=\"(\\w+)\"[^>]*>(.*?)</string>").findAll(file.readText())
                .filter { '—' in it.groupValues[2] }
                .map { "${file.name}: ${it.groupValues[1]}" }
                .toList() shouldBe listOf()
        }
    }
})
