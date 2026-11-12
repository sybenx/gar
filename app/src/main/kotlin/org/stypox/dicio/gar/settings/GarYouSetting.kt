package org.stypox.dicio.gar.settings

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.stypox.dicio.R
import org.stypox.dicio.gar.stt.GarSttOwnNameAsk
import org.stypox.dicio.settings.ui.SettingsItem
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import org.stypox.dicio.gar.ui.garble.LocalGarbleColors

/**
 * "You": who GAR thinks the user is and where that came from ([GarYou.who]); a tap opens
 * [YouDialog], which picks a contact, takes just a name, or clears both. Over the lock screen
 * the summary has no number and the dialog doesn't open ([GarYou.canOpen]).
 */
@Composable
fun GarYouSetting(viewModel: GarSettingsViewModel) {
    val you by viewModel.you.collectAsState()
    val locked by viewModel.locked.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    // a contact may have been renamed, access granted or the phone locked while in the background
    LifecycleResumeEffect(null) {
        viewModel.checkYou()
        onPauseOrDispose {}
    }
    val resources = LocalContext.current.resources

    SettingsItem(
        title = stringResource(R.string.gar_you),
        icon = Icons.Default.Person,
        description = you?.let { GarYou.summary(it) { id, args -> resources.getString(id, *args) } },
        modifier = Modifier.clickable { open = GarYou.canOpen(locked) },
    )

    // the phone locked with the dialog open: it closes
    LaunchedEffect(locked) {
        if (!GarYou.canOpen(locked)) {
            open = false
        }
    }
    if (open && GarYou.canOpen(locked)) {
        YouDialog(viewModel, you, onDismiss = { open = false })
    }
}

/**
 * Everything "You" sets, in place: a search field over the contacts (each with its first
 * number or address, the current one marked), "Use just a name" with a text field, and Clear
 * when there is something of GAR's to clear. A contact like "Me" asks for the first name next.
 * Without `READ_CONTACTS`, the name field and a button that asks for it, or opens App info
 * once Android stopped asking.
 */
@Composable
private fun YouDialog(viewModel: GarSettingsViewModel, you: You?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun readable() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(readable()) }
    // access may have been allowed in App info
    LifecycleResumeEffect(null) {
        granted = readable()
        onPauseOrDispose {}
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            viewModel.checkYou()
            // after two "Don't allow"s Android answers at once without asking
            val activity = context as? Activity
            if (!it && activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)
            ) {
                openAppInfo(context)
            }
        }
    LaunchedEffect(granted) {
        if (granted) {
            viewModel.loadYouContacts()
        }
    }
    val contacts by viewModel.youContacts.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var nameMode by rememberSaveable { mutableStateOf(you is You.NameOnly) }
    // a contact like "Me" was just picked: ask for the first name that it doesn't give
    var askFirstName by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable {
        mutableStateOf((you as? You.NameOnly)?.name ?: (you as? You.Contact)?.firstName.orEmpty())
    }

    fun choose(choice: YouChoice) {
        viewModel.setYou(choice)
        if (choice is YouChoice.Contact && !GarYou.namesSomeone(choice.name)) {
            nameMode = true
            askFirstName = true
        } else {
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gar_you)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        dismissButton = {
            if (GarYou.canClear(you) && !askFirstName) {
                TextButton(onClick = { choose(YouChoice.Clear) }) {
                    Text(stringResource(R.string.gar_you_clear))
                }
            }
        },
        text = {
            Column {
                if (you is You.Profile && !askFirstName) {
                    Text(stringResource(R.string.gar_you_from_profile))
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!granted) {
                    Text(stringResource(R.string.gar_you_no_access))
                    TextButton(
                        onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
                    ) {
                        Text(stringResource(R.string.gar_you_grant))
                    }
                } else if (!nameMode) {
                    Text(stringResource(R.string.gar_you_dialog_text))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.gar_you_search)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val found = contacts?.let { GarYou.search(it, query) }
                    if (found != null && found.isEmpty()) {
                        Text(
                            text = stringResource(R.string.gar_you_no_contacts),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    val current = (you as? You.Contact)?.name
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(found.orEmpty()) { contact ->
                            YouContactRow(contact, selected = contact.name == current) {
                                choose(YouChoice.Contact(contact.name))
                            }
                        }
                    }
                    TextButton(onClick = { nameMode = true }) {
                        Text(stringResource(R.string.gar_you_name_only_button))
                    }
                } else if (askFirstName) {
                    Text(stringResource(R.string.gar_you_first_name))
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (!granted || nameMode) {
                    val canSave = GarSttOwnNameAsk.typed(name) != null
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.gar_stt_own_name_hint)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            if (canSave) choose(YouChoice.Name(name))
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (granted) Arrangement.SpaceBetween else Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (granted) {
                            TextButton(
                                onClick = {
                                    nameMode = false
                                    askFirstName = false
                                },
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Text(stringResource(R.string.gar_you_pick_contact))
                            }
                        }
                        GarbleSecondaryButton( // GAR
                            onClick = { choose(YouChoice.Name(name)) },
                            enabled = canSave,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.gar_stt_own_name_save))
                        }
                    }
                }
            }
        },
    )
}

/** GAR's App info page, where contacts access is allowed after Android stopped asking. */
private fun openAppInfo(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        )
    } catch (e: ActivityNotFoundException) {
        Log.w("GarYouSetting", "Can't open App info", e)
    }
}

/**
 * A contact's name, with its first number, its first address or "No number or email" below;
 * the current one ([selected]) in bold, in the text color (the accent stays off labels).
 */
@Composable
private fun YouContactRow(contact: YouContact, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = contact.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) LocalGarbleColors.current.text else Color.Unspecified,
            fontWeight = if (selected) FontWeight.Bold else null,
        )
        Text(
            text = contact.number ?: contact.email ?: stringResource(R.string.gar_you_no_number),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
