package org.stypox.dicio.ui.nav

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf // GAR
import androidx.compose.runtime.saveable.rememberSaveable // GAR
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.stypox.dicio.R
import org.stypox.dicio.gar.settings.GarAppearance // GAR
import org.stypox.dicio.gar.settings.GarAppearanceScreen // GAR
import org.stypox.dicio.gar.settings.GarModelSettings // GAR
import org.stypox.dicio.gar.settings.GarSettingsScreen // GAR
import org.stypox.dicio.gar.settings.GarVoiceScreen // GAR
import org.stypox.dicio.gar.settings.GarVoiceSettings // GAR
import org.stypox.dicio.gar.settings.GarYouPlacesScreen // GAR
import org.stypox.dicio.gar.settings.GarYouPlacesSettings // GAR
import org.stypox.dicio.gar.ui.GarAppHandOffs // GAR
import org.stypox.dicio.gar.ui.GarImePadded // GAR
import org.stypox.dicio.io.input.stt_popup.SttPopupActivity
import org.stypox.dicio.settings.MainSettingsScreen
import org.stypox.dicio.settings.SkillSettingsScreen
import org.stypox.dicio.ui.about.AboutScreen
import org.stypox.dicio.ui.home.HomeScreen

@Composable
fun Navigation() {
    val navController = rememberNavController()
    val backIcon = @Composable {
        IconButton(
            onClick = { navController.navigateUp() }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
    }

    // GAR: the sheet's hand-offs, answered whatever screen is showing; typing goes back home
    val garTypeRequested = rememberSaveable { mutableStateOf(false) }
    GarAppHandOffs(onType = {
        navController.popBackStack<Home>(inclusive = false)
        garTypeRequested.value = true
    })

    NavHost(navController = navController, startDestination = Home) {
        composable<Home> {
            val context = LocalContext.current
            ScreenWithDrawer(
                onSettingsClick = { navController.navigate(MainSettings) },
                onAboutClick = { navController.navigate(About) },
                onSpeechToTextPopupClick = {
                    val intent = Intent(context, SttPopupActivity::class.java)
                    context.startActivity(intent)
                },
            ) {
                HomeScreen(
                    it,
                    typeRequested = garTypeRequested, // GAR
                    onSettingsClick = { navController.navigate(MainSettings) }, // GAR
                )
            }
        }

        composable<MainSettings> {
            GarImePadded { // GAR
                MainSettingsScreen(
                    navigationIcon = backIcon,
                    navigateToSkillSettings = { navController.navigate(SkillSettings) },
                    navigateToGarSettings = { navController.navigate(GarModelSettings) }, // GAR
                    navigateToGarAppearance = { navController.navigate(GarAppearance) }, // GAR
                    navigateToGarYouPlaces = { navController.navigate(GarYouPlacesSettings) }, // GAR
                    navigateToGarVoice = { navController.navigate(GarVoiceSettings) }, // GAR
                    navigateToAbout = { navController.navigate(About) }, // GAR
                )
            }
        }

        composable<SkillSettings> {
            GarImePadded { SkillSettingsScreen(navigationIcon = backIcon) } // GAR
        }

        composable<GarModelSettings> { // GAR
            GarImePadded { GarSettingsScreen(navigationIcon = backIcon) }
        }

        composable<GarYouPlacesSettings> { // GAR
            GarImePadded { GarYouPlacesScreen(navigationIcon = backIcon) }
        }

        composable<GarVoiceSettings> { // GAR
            GarImePadded { GarVoiceScreen(navigationIcon = backIcon) }
        }

        composable<GarAppearance> { // GAR
            GarImePadded { GarAppearanceScreen(navigationIcon = backIcon) }
        }

        composable<About> {
            GarImePadded { AboutScreen(navigationIcon = backIcon) } // GAR
        }
    }
}

@Composable
fun ScreenWithDrawer(
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSpeechToTextPopupClick: () -> Unit,
    screen: @Composable (navigationIcon: @Composable () -> Unit) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                onSettingsClick = onSettingsClick,
                onAboutClick = onAboutClick,
                onSpeechToTextPopupClick = onSpeechToTextPopupClick,
                closeDrawer = {
                    scope.launch {
                        drawerState.close()
                    }
                }
            )
        },
    ) {
        screen {
            AppBarDrawerIcon(
                onDrawerClick = {
                    scope.launch {
                        drawerState.apply {
                            if (isClosed) open() else close()
                        }
                    }
                },
                isClosed = drawerState.isClosed,
            )
        }
    }
}
