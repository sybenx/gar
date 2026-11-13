package org.stypox.dicio.gar.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.serialization.Serializable
import org.stypox.dicio.R
import org.stypox.dicio.settings.ui.SettingsCategoryTitle
import org.stypox.dicio.settings.ui.SettingsItem

/** Navigation route of [GarYouPlacesScreen] (kept here so Dicio's Routes.kt stays unchanged). */
@Serializable
object GarYouPlacesSettings

/** The "You and places" row in Dicio's main settings. */
@Composable
fun GarYouPlacesItem(onClick: () -> Unit) {
    SettingsItem(
        title = stringResource(R.string.gar_you_places_title),
        icon = Icons.Default.Person,
        description = stringResource(R.string.gar_you_places_summary),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** The "About" row at the end of Dicio's main settings, which opens Dicio's About screen. */
@Composable
fun GarAboutItem(onClick: () -> Unit) {
    SettingsItem(
        title = stringResource(R.string.about),
        icon = Icons.Default.Info,
        description = stringResource(R.string.gar_about_summary),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * GAR. "You and places": who the user is ("text myself", "call me", the first name in Whisper's
 * hint), home and work ("take me home"), and getting there: the maps app and looking addresses
 * up online (gar:places).
 */
@Composable
fun GarYouPlacesScreen(
    navigationIcon: @Composable () -> Unit,
    viewModel: GarSettingsViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(stringResource(R.string.gar_you_places_title)) },
                navigationIcon = navigationIcon
            )
        }
    ) {
        LazyColumn(Modifier.padding(it)) {
            item { SettingsCategoryTitle(stringResource(R.string.gar_you_section_title), topPadding = 4.dp) }
            item { GarYouSetting(viewModel) }
            // its own "Home and work" header
            item { GarPlacesSetting() }
            // its own "Getting around" header
            item { GarNavigationSetting() }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}
