package org.stypox.dicio.gar.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import org.stypox.dicio.R
import org.stypox.dicio.gar.ui.garble.GarbleTextButton
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.skills.places.GarSettingsSavedPlaces
import org.stypox.dicio.skills.places.MapLinks
import org.stypox.dicio.skills.places.PlaceLinks
import org.stypox.dicio.skills.places.SavedPlace
import org.stypox.dicio.skills.places.SavedPlaceKind
import org.stypox.dicio.skills.places.SharedPlace
import org.stypox.dicio.util.BaseActivity

/**
 * GAR (gar:places). The share target "Save as Home or Work": a place shared as text from a maps
 * app (CoMaps, Organic Maps, OsmAnd, Magic Earth) or any map link ([PlaceLinks.parse]) is saved
 * as Home or Work with its point, through the same store as the voice flow
 * ([GarSettingsSavedPlaces]). A small dialog; never shown over the lock screen.
 */
@AndroidEntryPoint
class GarPlaceShareActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val shared = PlaceLinks.parse(text)
        val store = GarSettingsSavedPlaces(
            EntryPointAccessors.fromApplication(applicationContext, GarEntryPoint::class.java).garSettings()
        )

        composeSetContent {
            var saved by remember { mutableStateOf<Map<SavedPlaceKind, SavedPlace?>?>(null) }
            LaunchedEffect(Unit) {
                saved = SavedPlaceKind.entries.associateWith { store.get(it) }
            }
            val scope = rememberCoroutineScope()
            SharePlaceDialog(
                shared = shared,
                nameOnly = PlaceLinks.namesWithoutPoint(text),
                saved = saved.orEmpty(),
                onSave = { kind ->
                    scope.launch {
                        PlaceEditor(store).saveShared(kind, shared!!)
                        val name = getString(if (kind == SavedPlaceKind.HOME) R.string.gar_places_home else R.string.gar_places_work)
                        Toast.makeText(this@GarPlaceShareActivity, getString(R.string.gar_places_share_saved, name), Toast.LENGTH_SHORT)
                            .show()
                        finish()
                    }
                },
                onDismiss = { finish() },
            )
        }
    }
}

@Composable
private fun SharePlaceDialog(
    shared: SharedPlace?,
    nameOnly: Boolean,
    saved: Map<SavedPlaceKind, SavedPlace?>,
    onSave: (SavedPlaceKind) -> Unit,
    onDismiss: () -> Unit,
) {
    if (shared == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            text = {
                Text(stringResource(if (nameOnly) R.string.gar_places_share_name_only else R.string.gar_places_share_none))
            },
            confirmButton = {
                GarbleTextButton(onClick = onDismiss) { Text(stringResource(R.string.gar_places_share_close)) }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gar_places_share_title)) },
        text = {
            Column {
                shared.name?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Text(
                    "${MapLinks.format(shared.point.latitude)}, ${MapLinks.format(shared.point.longitude)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                for (kind in SavedPlaceKind.entries) {
                    Spacer(modifier = Modifier.height(12.dp))
                    GarbleSecondaryButton( // GAR
                        onClick = { onSave(kind) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(
                                if (kind == SavedPlaceKind.HOME) R.string.gar_places_share_home else R.string.gar_places_share_work
                            )
                        )
                    }
                    saved[kind]?.let { place ->
                        val now = place.address ?: place.point?.let {
                            stringResource(R.string.gar_places_point, MapLinks.format(it.latitude), MapLinks.format(it.longitude))
                        }
                        now?.let {
                            Text(
                                stringResource(R.string.gar_places_share_now, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            GarbleTextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
