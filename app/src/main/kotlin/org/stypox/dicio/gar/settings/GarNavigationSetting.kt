package org.stypox.dicio.gar.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
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
import org.stypox.dicio.settings.ui.BooleanSetting
import org.stypox.dicio.settings.ui.SettingsCategoryTitle
import org.stypox.dicio.settings.ui.SettingsItem
import org.stypox.dicio.skills.places.AndroidMapLauncher
import org.stypox.dicio.skills.places.MapApp
import org.stypox.dicio.skills.places.MapAppChoice
import org.stypox.dicio.skills.places.doesText
import javax.inject.Inject

/** The installed maps apps and Android's default one, for "Navigate with". */
data class MapAppsState(val apps: List<MapApp>, val androidDefault: MapApp?)

/**
 * GAR (gar:places). "Navigate with" and "Find addresses online": `GarSettings.navigation_app`
 * and `address_lookup`, the installed maps apps read once when the page opens.
 */
@HiltViewModel
class GarNavigationViewModel @Inject constructor(
    private val application: Application,
    private val dataStore: DataStore<GarSettings>,
) : AndroidViewModel(application) {
    private val _apps = MutableStateFlow<MapAppsState?>(null)
    val apps: StateFlow<MapAppsState?> = _apps

    val setting: StateFlow<Pair<String, Boolean>?> = dataStore.data
        .map { Pair(it.navigationApp, it.addressLookup) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val launcher = AndroidMapLauncher(application)
            val installed = MapAppChoice.ordered(launcher.apps())
            _apps.value = MapAppsState(installed, MapAppChoice.androidDefault(installed, launcher.defaultApp()))
        }
    }

    fun setApp(value: String) {
        viewModelScope.launch {
            dataStore.updateData { it.toBuilder().setNavigationApp(value).build() }
        }
    }

    /** Turning it on here counts as GAR's question answered. */
    fun setLookup(on: Boolean) {
        viewModelScope.launch {
            dataStore.updateData { it.toBuilder().setAddressLookup(on).setAddressLookupAsked(true).build() }
        }
    }
}

@Composable
fun GarNavigationSetting(viewModel: GarNavigationViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsState()
    val setting by viewModel.setting.collectAsState()
    var picking by rememberSaveable { mutableStateOf(false) }
    val current = setting ?: return
    val state = apps

    // nothing chosen (or an app uninstalled since): Android's default when set, else GAR asks
    val summary = when (val value = GarSettingsSummaries.navigateWith(current.first, state)) {
        NavigateWithSummary.Loading -> null
        NavigateWithSummary.Ask -> stringResource(R.string.gar_navigate_with_ask)
        NavigateWithSummary.NoneFound -> stringResource(R.string.gar_navigate_with_none)
        NavigateWithSummary.NotChosen -> stringResource(R.string.gar_navigate_with_not_chosen)
        is NavigateWithSummary.App -> value.label
        is NavigateWithSummary.AndroidDefault ->
            stringResource(R.string.gar_navigate_with_android_default, value.label)
    }
    Column {
        SettingsCategoryTitle(stringResource(R.string.gar_getting_around_title))
        SettingsItem(
            title = stringResource(R.string.gar_navigate_with),
            icon = Icons.Default.Directions,
            description = summary,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(enabled = state != null) { picking = true },
        )
        BooleanSetting(
            title = stringResource(R.string.gar_address_lookup),
            icon = Icons.Default.TravelExplore,
            descriptionOn = stringResource(R.string.gar_address_lookup_summary),
            descriptionOff = stringResource(R.string.gar_address_lookup_summary),
        ).Render(current.second, viewModel::setLookup)
    }

    if (picking && state != null) {
        NavigateWithDialog(state, current.first, onPick = {
            viewModel.setApp(it)
            picking = false
        }, onDismiss = { picking = false })
    }
}

@Composable
private fun NavigateWithDialog(
    state: MapAppsState,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val chosen = selected.takeIf { it == MapAppChoice.ASK || state.apps.any { app -> app.pkg == it } }
        ?: state.androidDefault?.pkg
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gar_navigate_with)) },
        confirmButton = {},
        dismissButton = {
            GarbleTextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (app in state.apps) {
                    val title = if (app == state.androidDefault) {
                        stringResource(R.string.gar_navigate_with_android_default, app.label)
                    } else {
                        app.label
                    }
                    GarbleChoice(title, chosen == app.pkg, onClick = { onPick(app.pkg) }, detail = stringResource(app.doesText()))
                }
                GarbleChoice(
                    stringResource(R.string.gar_navigate_with_ask),
                    chosen == MapAppChoice.ASK,
                    onClick = { onPick(MapAppChoice.ASK) },
                )
            }
        },
    )
}
