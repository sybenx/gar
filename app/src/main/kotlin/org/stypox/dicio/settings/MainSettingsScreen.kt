package org.stypox.dicio.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.stypox.dicio.R
import org.stypox.dicio.gar.settings.GarAppearanceItem // GAR
import org.stypox.dicio.gar.settings.GarAboutItem // GAR
import org.stypox.dicio.gar.settings.GarSettingsItem // GAR
import org.stypox.dicio.gar.settings.GarVoiceItem // GAR
import org.stypox.dicio.gar.settings.GarYouPlacesItem // GAR
import org.stypox.dicio.settings.datastore.UserSettingsModule.Companion.newDataStoreForPreviews
import org.stypox.dicio.settings.ui.SettingsItem
import org.stypox.dicio.ui.theme.AppTheme


@Composable
fun MainSettingsScreen(
    navigationIcon: @Composable () -> Unit,
    navigateToSkillSettings: () -> Unit,
    navigateToGarSettings: () -> Unit = {}, // GAR
    navigateToGarAppearance: () -> Unit = {}, // GAR
    navigateToGarYouPlaces: () -> Unit = {}, // GAR
    navigateToGarVoice: () -> Unit = {}, // GAR
    navigateToAbout: () -> Unit = {}, // GAR
    viewModel: MainSettingsViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = navigationIcon
            )
        }
    ) {
        MainSettingsScreen(
            navigateToSkillSettings = navigateToSkillSettings,
            navigateToGarSettings = navigateToGarSettings, // GAR
            navigateToGarAppearance = navigateToGarAppearance, // GAR
            navigateToGarYouPlaces = navigateToGarYouPlaces, // GAR
            navigateToGarVoice = navigateToGarVoice, // GAR
            navigateToAbout = navigateToAbout, // GAR
            viewModel = viewModel,
            modifier = Modifier.padding(it),
        )
    }
}

@Composable
private fun MainSettingsScreen(
    navigateToSkillSettings: () -> Unit,
    navigateToGarSettings: () -> Unit = {}, // GAR
    navigateToGarAppearance: () -> Unit = {}, // GAR
    navigateToGarYouPlaces: () -> Unit = {}, // GAR
    navigateToGarVoice: () -> Unit = {}, // GAR
    navigateToAbout: () -> Unit = {}, // GAR
    viewModel: MainSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    // GAR: Garble's order with no headers: You and places, Assistant and voice, Skills,
    // Appearance, Language model, About. Dicio's "General" header, Language and the whole
    // "Input and output methods" section moved to GarVoiceScreen (same factories, same values);
    // the theme and Material You rows moved to Appearance
    LazyColumn(modifier) {
        item { GarYouPlacesItem(onClick = navigateToGarYouPlaces) } // GAR
        item { GarVoiceItem(onClick = navigateToGarVoice) } // GAR
        item {
            SettingsItem(
                title = stringResource(R.string.pref_skills_title),
                icon = Icons.Default.Extension,
                description = stringResource(R.string.gar_skills_summary), // GAR
                modifier = Modifier
                    .clickable(onClick = navigateToSkillSettings)
                    .testTag("skill_settings_item")
            )
        }
        item { GarAppearanceItem(onClick = navigateToGarAppearance) } // GAR
        item { GarSettingsItem(onClick = navigateToGarSettings) } // GAR
        item { GarAboutItem(onClick = navigateToAbout) } // GAR

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Preview
@Composable
private fun MainSettingsScreenPreview() {
    AppTheme {
        Surface(
            color = MaterialTheme.colorScheme.background
        ) {
            MainSettingsScreen(
                navigateToSkillSettings = {},
                viewModel = MainSettingsViewModel(
                    application = Application(),
                    wakeDeviceWrapper = null,
                    dataStore = newDataStoreForPreviews(),
                ),
            )
        }
    }
}

@Preview
@Composable
private fun MainSettingsScreenWithTopBarPreview() {
    AppTheme {
        Surface(
            color = MaterialTheme.colorScheme.background
        ) {
            MainSettingsScreen(
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                navigateToSkillSettings = {},
                viewModel = MainSettingsViewModel(
                    application = Application(),
                    wakeDeviceWrapper = null,
                    dataStore = newDataStoreForPreviews()
                )
            )
        }
    }
}
