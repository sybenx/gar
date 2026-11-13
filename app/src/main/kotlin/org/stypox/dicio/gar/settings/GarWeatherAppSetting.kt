package org.stypox.dicio.gar.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.stypox.dicio.R
import org.stypox.dicio.gar.ui.garble.GarbleChoice
import org.stypox.dicio.gar.ui.garble.GarbleTextButton
import org.stypox.dicio.settings.ui.SettingsItem
import org.stypox.dicio.skills.weather.AndroidWeatherApps
import org.stypox.dicio.skills.weather.WeatherApp
import org.stypox.dicio.skills.weather.WeatherAppChoice
import org.stypox.dicio.skills.weather.WeatherApps
import javax.inject.Inject

/**
 * GAR (gar:weather). "Weather app": `GarSettings.weather_app`, the installed weather apps read
 * once when the page opens. Like "Navigate with" ([GarNavigationSetting]). Shown in the weather
 * skill's settings (Settings, Skills, Weather), next to Breezy Weather.
 */
@HiltViewModel
class GarWeatherAppViewModel @Inject constructor(
    private val application: Application,
    private val dataStore: DataStore<GarSettings>,
) : AndroidViewModel(application) {
    private val _apps = MutableStateFlow<List<WeatherApp>?>(null)
    val apps: StateFlow<List<WeatherApp>?> = _apps

    val setting: StateFlow<String?> = dataStore.data
        .map { it.weatherApp }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            _apps.value = WeatherApps.ordered(AndroidWeatherApps(application, dataStore).installed())
        }
    }

    fun setApp(value: String) {
        viewModelScope.launch {
            dataStore.updateData { it.toBuilder().setWeatherApp(value).build() }
        }
    }
}

@Composable
fun GarWeatherAppSetting(viewModel: GarWeatherAppViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsState()
    val setting by viewModel.setting.collectAsState()
    var picking by rememberSaveable { mutableStateOf(false) }
    val current = setting ?: return
    val installed = apps

    val summary = GarSettingsSummaries.weatherApp(current, installed)
    val text = when (summary) {
        WeatherAppSummary.Loading -> null
        WeatherAppSummary.Off -> stringResource(R.string.gar_weather_app_off)
        WeatherAppSummary.NoneFound -> stringResource(R.string.gar_weather_app_none_found)
        WeatherAppSummary.NotChosen -> stringResource(R.string.gar_weather_app_not_chosen)
        is WeatherAppSummary.App -> summary.label
    }
    val detail = if (GarSettingsSummaries.weatherAppShowsDetail(summary)) {
        stringResource(R.string.gar_weather_app_detail)
    } else {
        null
    }
    SettingsItem(
        title = stringResource(R.string.gar_weather_app),
        // no icon: it sits in the Weather skill card, whose other rows have none
        description = listOfNotNull(text, detail).joinToString("\n").ifEmpty { null },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = installed != null) { picking = true },
    )

    if (picking && installed != null) {
        WeatherAppDialog(installed, current, onPick = {
            viewModel.setApp(it)
            picking = false
        }, onDismiss = { picking = false })
    }
}

@Composable
private fun WeatherAppDialog(
    apps: List<WeatherApp>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val chosen = (WeatherApps.choose(selected, apps) as? WeatherAppChoice.App)?.app?.pkg
        ?: selected.takeIf { it == WeatherApps.NONE }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gar_weather_app)) },
        confirmButton = {},
        dismissButton = {
            GarbleTextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (apps.isEmpty()) {
                    Text(
                        stringResource(R.string.gar_weather_app_none_found),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                for (app in apps) {
                    GarbleChoice(app.label, chosen == app.pkg, onClick = { onPick(app.pkg) })
                }
                GarbleChoice(
                    stringResource(R.string.gar_weather_app_off),
                    chosen == WeatherApps.NONE,
                    onClick = { onPick(WeatherApps.NONE) },
                )
            }
        },
    )
}
