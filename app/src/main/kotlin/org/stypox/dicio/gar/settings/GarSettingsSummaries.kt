package org.stypox.dicio.gar.settings

import org.stypox.dicio.gar.stt.GarWhisperInputDevice
import org.stypox.dicio.settings.datastore.WakeDevice
import org.stypox.dicio.skills.places.MapAppChoice
import org.stypox.dicio.skills.weather.WeatherApp
import org.stypox.dicio.skills.weather.WeatherAppChoice
import org.stypox.dicio.skills.weather.WeatherApps

/** What the "Weather app" row says for a value, see [GarSettingsSummaries.weatherApp]. */
sealed interface WeatherAppSummary {
    /** The installed apps aren't read yet: no summary. */
    data object Loading : WeatherAppSummary

    /** "Don't open an app". */
    data object Off : WeatherAppSummary

    /** "No weather app found". */
    data object NoneFound : WeatherAppSummary

    /** Several apps, none picked: "Not chosen yet. Garble asks the first time." */
    data object NotChosen : WeatherAppSummary

    data class App(val label: String) : WeatherAppSummary
}

/** What the "Navigate with" row says for a value, see [GarSettingsSummaries.navigateWith]. */
sealed interface NavigateWithSummary {
    data object Loading : NavigateWithSummary
    data object Ask : NavigateWithSummary
    data object NoneFound : NavigateWithSummary
    data object NotChosen : NavigateWithSummary
    data class App(val label: String) : NavigateWithSummary

    /** Nothing picked, so Android's default maps app: "%s (Android default)". */
    data class AndroidDefault(val label: String) : NavigateWithSummary
}

/** What the "Wake word detection" row says under its value, see [GarSettingsSummaries.wakeWord]. */
enum class WakeWordSummary {
    /** "On", "Listens in the background for \"Hey Dicio\"". */
    HEY_DICIO,

    /** "On", "Listens in the background for your custom wake word". */
    CUSTOM,

    /** "Off", "Garble doesn't listen in the background". */
    OFF,
}

/**
 * GAR. The summaries of GAR's settings rows for each value, apart from Compose so that tests can
 * check them. A row says what GAR does with the value, not only the value.
 */
object GarSettingsSummaries {
    /**
     * @param setting `GarSettings.weather_app`
     * @param installed the weather apps, null while they are read
     */
    fun weatherApp(setting: String, installed: List<WeatherApp>?): WeatherAppSummary = when {
        setting == WeatherApps.NONE -> WeatherAppSummary.Off
        installed == null -> WeatherAppSummary.Loading
        installed.isEmpty() -> WeatherAppSummary.NoneFound
        else -> when (val choice = WeatherApps.choose(setting, installed)) {
            // the only app counts as chosen, as GAR then opens it without asking
            is WeatherAppChoice.App -> WeatherAppSummary.App(choice.app.label)
            else -> WeatherAppSummary.NotChosen
        }
    }

    /**
     * Whether the row adds when the app opens: only when one opens or GAR will ask for one, not
     * for "Don't open an app" (no app and no card button) or with no weather app installed.
     */
    fun weatherAppShowsDetail(summary: WeatherAppSummary): Boolean =
        summary is WeatherAppSummary.App || summary is WeatherAppSummary.NotChosen

    /**
     * @param setting `GarSettings.navigation_app`
     * @param apps the maps apps and Android's default one, null while they are read
     */
    fun navigateWith(setting: String, apps: MapAppsState?): NavigateWithSummary {
        if (setting == MapAppChoice.ASK) {
            return NavigateWithSummary.Ask
        }
        if (apps == null) {
            return NavigateWithSummary.Loading
        }
        if (apps.apps.isEmpty()) {
            return NavigateWithSummary.NoneFound
        }
        // an app uninstalled since counts as not chosen, as in MapAppChoice.choose
        apps.apps.firstOrNull { it.pkg == setting && setting != MapAppChoice.NOT_CHOSEN }
            ?.let { return NavigateWithSummary.App(it.label) }
        apps.androidDefault?.let { return NavigateWithSummary.AndroidDefault(it.label) }
        // the only app counts as chosen, as GAR then opens it without asking
        return apps.apps.singleOrNull()?.let { NavigateWithSummary.App(it.label) }
            ?: NavigateWithSummary.NotChosen
    }

    /**
     * @param device Dicio's `wake_device`; UNSET is OpenWakeWord, as Dicio's wake service has it
     * @param isHeyDicio whether no custom wake word model was imported
     */
    fun wakeWord(device: WakeDevice, isHeyDicio: Boolean): WakeWordSummary = when {
        device == WakeDevice.WAKE_DEVICE_NOTHING -> WakeWordSummary.OFF
        isHeyDicio -> WakeWordSummary.HEY_DICIO
        else -> WakeWordSummary.CUSTOM
    }

    /**
     * How long Whisper waits for speech to start at [steps] of "Wait for speech", in whole
     * seconds; Vosk counts its own silent intervals instead, which have no fixed length.
     */
    fun whisperWaitSeconds(steps: Int): Int =
        steps * GarWhisperInputDevice.NO_SPEECH_MILLIS_PER_STEP / 1000
}
