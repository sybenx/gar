package org.stypox.dicio.gar.assist

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import cc.aaro.gar.assist.GarAssist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.stypox.dicio.R
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.ui.home.MessageCard
import org.stypox.dicio.ui.theme.AppTheme
import javax.inject.Inject
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import org.stypox.dicio.gar.ui.garble.GarbleTextButton // GAR

/**
 * The home screen's "Make GAR your assistant" card (gar/DESIGN.md, "Assistant entry points"),
 * shown until the user dismisses it or GAR is the digital assistant once. Apps can't ask for the
 * assistant role (it isn't requestable, see `gar/assist/README.md`), so the card opens the system
 * page where the user picks the digital assistant app; GAR never changes the role itself.
 */
object GarAssistantPrompt {
    /**
     * The system pages that lead to the picker, best first: Settings' "Assist & voice input"
     * (ManageAssist), whose first row is "Default digital assistant app"; the default apps list,
     * where "Digital assistant app" is one row; Settings itself.
     */
    val SETTINGS_ACTIONS = listOf(
        Settings.ACTION_VOICE_INPUT_SETTINGS,
        Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        Settings.ACTION_SETTINGS,
    )

    /**
     * Whether the card shows. [hidden] (`assistant_card_hidden`) and [isAssistant] are null until
     * they are read, so that the card doesn't flash.
     */
    fun shows(hidden: Boolean?, isAssistant: Boolean?): Boolean =
        hidden == false && isAssistant == false

    /** Whether to hide the card for good: GAR is the assistant now. */
    fun hidesForGood(hidden: Boolean?, isAssistant: Boolean?): Boolean =
        hidden == false && isAssistant == true

    /**
     * Starts the first of [actions] that works.
     * @param start starts an action, false if nothing handles it
     * @return false if none worked
     */
    fun openFirst(actions: List<String> = SETTINGS_ACTIONS, start: (String) -> Boolean): Boolean =
        actions.any(start)

    /**
     * Whether GAR is the device's digital assistant: its VoiceInteractionService is the active
     * one, or it holds the assistant role (also when the system took only its ASSIST activity,
     * e.g. on low-RAM devices: the user picked GAR either way).
     */
    fun isAssistant(context: Context): Boolean {
        val active = try {
            GarAssist.isActiveAssistant(context)
        } catch (e: RuntimeException) {
            false
        }
        if (active) {
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false // Android 9 has no roles
        }
        return try {
            context.getSystemService(RoleManager::class.java)
                ?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } catch (e: RuntimeException) {
            false
        }
    }

    /** Opens the page where the user picks the digital assistant app, see [SETTINGS_ACTIONS]. */
    fun openPicker(context: Context): Boolean = openFirst { action ->
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Nothing opens $action", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to open $action", e)
            false
        }
    }

    private const val TAG = "GarAssistantPrompt"
}

/** The card's setting in [GarSettings], apart from the view model so that tests can run it. */
class GarAssistantCardStore(private val dataStore: DataStore<GarSettings>) {
    val hidden: Flow<Boolean> = dataStore.data.map { it.assistantCardHidden }

    suspend fun hide() {
        dataStore.updateData {
            if (it.assistantCardHidden) it else it.toBuilder().setAssistantCardHidden(true).build()
        }
    }
}

@HiltViewModel
class GarAssistantCardViewModel @Inject constructor(
    dataStore: DataStore<GarSettings>,
) : ViewModel() {
    private val store = GarAssistantCardStore(dataStore)

    /** Null until the settings are read. */
    val hidden: StateFlow<Boolean?> = store.hidden
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun hide() {
        viewModelScope.launch { store.hide() }
    }
}

/**
 * Asks once to make GAR the digital assistant, on the home screen (one GAR line in Dicio's
 * `InteractionList`). Shows nothing once dismissed, while GAR is the assistant, and in previews.
 */
@Composable
fun GarAssistantCard() {
    if (LocalInspectionMode.current) {
        return // previews have no Hilt
    }
    val viewModel: GarAssistantCardViewModel = hiltViewModel()
    val hidden by viewModel.hidden.collectAsState()
    val context = LocalContext.current
    var isAssistant by remember { mutableStateOf<Boolean?>(null) }
    // checked again when the user comes back from the system's settings
    LifecycleResumeEffect(null) {
        isAssistant = GarAssistantPrompt.isAssistant(context)
        onPauseOrDispose {}
    }
    LaunchedEffect(hidden, isAssistant) {
        if (GarAssistantPrompt.hidesForGood(hidden, isAssistant)) {
            viewModel.hide()
        }
    }

    if (GarAssistantPrompt.shows(hidden, isAssistant)) {
        GarAssistantCardImpl(
            onOpenSettings = { GarAssistantPrompt.openPicker(context) },
            onDismiss = viewModel::hide,
        )
    }
}

/** The card itself, in the style of Dicio's wake word card. */
@Composable
fun GarAssistantCardImpl(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    MessageCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.gar_assistant_card_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.gar_assistant_card_text),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.gar_assistant_card_how),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GarbleTextButton(onClick = onDismiss) { // GAR
                Text(text = stringResource(R.string.gar_assistant_card_dismiss))
            }
            GarbleSecondaryButton(onClick = onOpenSettings) { // GAR
                Text(text = stringResource(R.string.gar_assistant_card_open))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Preview
@Composable
private fun GarAssistantCardPreview() {
    AppTheme {
        GarAssistantCardImpl(onOpenSettings = {}, onDismiss = {})
    }
}
