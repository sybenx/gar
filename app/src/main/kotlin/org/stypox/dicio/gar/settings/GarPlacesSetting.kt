package org.stypox.dicio.gar.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import org.stypox.dicio.gar.ui.garble.GarbleTextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.dicio.skill.skill.Permission
import org.stypox.dicio.R
import org.stypox.dicio.settings.ui.SettingsCategoryTitle
import org.stypox.dicio.settings.ui.SettingsItem
import org.stypox.dicio.skills.places.AndroidPlacePages
import org.stypox.dicio.skills.places.GeoPoint
import org.stypox.dicio.skills.places.LocationResult
import org.stypox.dicio.skills.places.SavedPlace
import org.stypox.dicio.skills.places.SavedPlaceKind
import org.stypox.dicio.ui.theme.AppTheme

/** Why "Use where I am now" saved nothing, and the page that may fix it. */
enum class PlaceEditError(val message: Int, val action: Int? = null) {
    NO_ACCESS(R.string.gar_places_no_access, R.string.gar_places_open_app_info),
    APPROXIMATE(R.string.gar_places_approximate, R.string.gar_places_open_app_info),
    LOCATION_OFF(R.string.gar_places_location_off, R.string.gar_places_open_location),
    NO_FIX(R.string.gar_places_no_fix),
}

private val SavedPlaceKind.title: Int
    get() = if (this == SavedPlaceKind.HOME) R.string.gar_places_home else R.string.gar_places_work

private val SavedPlaceKind.clear: Int
    get() = if (this == SavedPlaceKind.HOME) R.string.gar_places_clear_home else R.string.gar_places_clear_work

private val SavedPlaceKind.clearAsk: Int
    get() = if (this == SavedPlaceKind.HOME) R.string.gar_places_clear_ask_home else R.string.gar_places_clear_ask_work

/**
 * GAR (gar:places). "Home and work": a row each with what is saved ([GarPlaces.summary]); a tap
 * opens [PlaceEditorDialog]. Over the lock screen the rows say nothing of the places and don't
 * open ([GarYou.canOpen]), as for "You".
 */
@Composable
fun GarPlacesSetting(viewModel: GarPlacesViewModel = hiltViewModel()) {
    val places by viewModel.places.collectAsState()
    val locked by viewModel.locked.collectAsState()
    var editing by rememberSaveable { mutableStateOf<SavedPlaceKind?>(null) }
    LifecycleResumeEffect(null) {
        viewModel.check()
        onPauseOrDispose {}
    }

    GarPlacesRows(places, locked) { if (GarYou.canOpen(locked)) editing = it }

    LaunchedEffect(locked) {
        if (!GarYou.canOpen(locked)) {
            editing = null
        }
    }
    val kind = editing
    if (kind != null && GarYou.canOpen(locked)) {
        PlaceEditorDialog(viewModel, kind, places?.get(kind), onDismiss = { editing = null })
    }
}

@Composable
private fun GarPlacesRows(
    places: Map<SavedPlaceKind, SavedPlace?>?,
    locked: Boolean?,
    onEdit: (SavedPlaceKind) -> Unit,
) {
    val resources = LocalContext.current.resources
    Column {
        SettingsCategoryTitle(stringResource(R.string.gar_places_title))
        for (kind in SavedPlaceKind.entries) {
            SettingsItem(
                title = stringResource(kind.title),
                icon = if (kind == SavedPlaceKind.HOME) Icons.Default.Home else Icons.Default.Work,
                description = places?.let {
                    GarPlaces.summary(it[kind], locked) { id, args -> resources.getString(id, *args) }
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(enabled = GarYou.canOpen(locked)) { onEdit(kind) },
            )
        }
    }
}

/**
 * The editor of one place: what to type or paste, "Use where I am now" (Android's location
 * dialog first, as by voice), "From my contact" when the "You" card has such an address (a list
 * to pick from when it has several), and "Clear home" when something is saved, asked once more
 * since it can't be undone. Save and Cancel are the dialog's own buttons.
 */
@Composable
private fun PlaceEditorDialog(
    viewModel: GarPlacesViewModel,
    kind: SavedPlaceKind,
    place: SavedPlace?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<PlaceEditError?>(null) }
    var cardAddresses by remember { mutableStateOf<List<String>>(listOf()) }
    LaunchedEffect(kind) {
        cardAddresses = viewModel.cardAddresses(kind)
    }

    // Android's location dialog, awaited like Dicio's permission requester
    val waiting = remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        waiting.value?.complete(it.values.any { granted -> granted })
        waiting.value = null
    }
    val requester: suspend (List<Permission>) -> Boolean = { permissions ->
        val result = CompletableDeferred<Boolean>()
        waiting.value = result
        launcher.launch(
            permissions.filterIsInstance<Permission.NormalPermission>().map { it.id }.toTypedArray()
                .ifEmpty { arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION) }
        )
        result.await()
    }

    PlaceEditorContent(
        kind = kind,
        place = place,
        cardAddresses = cardAddresses,
        locating = locating,
        error = error,
        onSaveTyped = { text ->
            scope.launch {
                if (viewModel.saveTyped(kind, text) != null) onDismiss()
            }
        },
        onHere = {
            locating = true
            error = null
            scope.launch {
                val result = viewModel.saveHere(kind, requester)
                locating = false
                error = when (result) {
                    is LocationResult.Found ->
                        if (result.fix.precise) null.also { onDismiss() } else PlaceEditError.APPROXIMATE
                    LocationResult.NoAccess -> PlaceEditError.NO_ACCESS
                    LocationResult.Off -> PlaceEditError.LOCATION_OFF
                    LocationResult.NoFix -> PlaceEditError.NO_FIX
                }
            }
        },
        onCard = { address ->
            scope.launch {
                viewModel.saveCard(kind, address)
                onDismiss()
            }
        },
        onErrorAction = {
            val pages = AndroidPlacePages(context)
            when (error) {
                PlaceEditError.NO_ACCESS, PlaceEditError.APPROXIMATE -> pages.openAppInfo()
                PlaceEditError.LOCATION_OFF -> pages.openLocationSettings()
                else -> {}
            }
        },
        onClear = {
            scope.launch {
                viewModel.clear(kind)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun PlaceEditorContent(
    kind: SavedPlaceKind,
    place: SavedPlace?,
    cardAddresses: List<String>,
    locating: Boolean,
    error: PlaceEditError?,
    onSaveTyped: (String) -> Unit,
    onHere: () -> Unit,
    onCard: (String) -> Unit,
    onErrorAction: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(kind) { mutableStateOf(place?.address.orEmpty()) }
    var picking by rememberSaveable(kind) { mutableStateOf(false) }
    var askingClear by rememberSaveable(kind) { mutableStateOf(false) }
    val canSave = GarPlaces.typed(text) != null

    if (askingClear) {
        AlertDialog(
            onDismissRequest = { askingClear = false },
            text = { Text(stringResource(kind.clearAsk)) },
            confirmButton = {
                TextButton(
                    onClick = { askingClear = false; onClear() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.gar_places_clear))
                }
            },
            dismissButton = {
                GarbleTextButton(onClick = { askingClear = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(kind.title)) },
        confirmButton = {
            TextButton(onClick = { onSaveTyped(text) }, enabled = canSave && !picking) {
                Text(stringResource(R.string.gar_places_save))
            }
        },
        dismissButton = {
            GarbleTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (picking) {
                    Text(stringResource(R.string.gar_places_pick_card))
                    Spacer(modifier = Modifier.height(8.dp))
                    for (address in cardAddresses) {
                        Text(
                            text = address,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onCard(address) }
                                .padding(vertical = 12.dp),
                        )
                    }
                    GarbleTextButton(onClick = { picking = false }) {
                        Text(stringResource(R.string.gar_places_back))
                    }
                    return@Column
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.gar_places_field)) },
                    supportingText = { Text(stringResource(R.string.gar_places_field_hint)) },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) onSaveTyped(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                EditorButton(
                    icon = { Icon(Icons.Default.MyLocation, contentDescription = null, Modifier.size(18.dp)) },
                    text = stringResource(if (locating) R.string.gar_places_locating else R.string.gar_places_here),
                    enabled = !locating,
                    onClick = onHere,
                )
                if (cardAddresses.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    EditorButton(
                        icon = { Icon(Icons.Default.ContactPage, contentDescription = null, Modifier.size(18.dp)) },
                        text = stringResource(R.string.gar_places_from_card),
                        enabled = !locating,
                        onClick = {
                            if (cardAddresses.size == 1) onCard(cardAddresses[0]) else picking = true
                        },
                    )
                    if (cardAddresses.size == 1) {
                        Text(
                            text = cardAddresses[0],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                }
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(error.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    error.action?.let {
                        GarbleTextButton(onClick = onErrorAction) {
                            Text(stringResource(it))
                        }
                    }
                }
                if (place != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { askingClear = true },
                        enabled = !locating,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(kind.clear))
                    }
                }
            }
        },
    )
}

@Composable
private fun EditorButton(icon: @Composable () -> Unit, text: String, enabled: Boolean, onClick: () -> Unit) {
    GarbleSecondaryButton( // GAR
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(modifier = Modifier.width(8.dp))
            Text(text)
        }
    }
}

@Preview
@Composable
private fun GarPlacesRowsPreview() {
    AppTheme {
        GarPlacesRows(
            places = mapOf(
                SavedPlaceKind.HOME to SavedPlace("123 Main Street, Springfield", null),
                SavedPlaceKind.WORK to SavedPlace(null, GeoPoint(40.712776, -74.005974)),
            ),
            locked = false,
            onEdit = {},
        )
    }
}

@Preview
@Composable
private fun GarPlacesRowsLockedPreview() {
    AppTheme {
        GarPlacesRows(places = mapOf(), locked = true, onEdit = {})
    }
}

@Preview
@Composable
private fun PlaceEditorPreview() {
    AppTheme {
        PlaceEditorContent(
            kind = SavedPlaceKind.WORK,
            place = SavedPlace("Infinatum Coffee", null),
            cardAddresses = listOf("1 Infinite Loop, Cupertino, CA 95014"),
            locating = false,
            error = PlaceEditError.NO_FIX,
            onSaveTyped = {}, onHere = {}, onCard = {}, onErrorAction = {}, onClear = {}, onDismiss = {},
        )
    }
}
