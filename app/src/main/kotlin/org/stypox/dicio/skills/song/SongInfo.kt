package org.stypox.dicio.skills.song

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.ui.ListSetting
import org.stypox.dicio.settings.ui.SettingsItem
import org.stypox.dicio.settings.ui.StringSetting

/**
 * "What song is this?" (GAR Step 4). Enabled by default: tier 1 is offline, and the network tier
 * only runs once the user picks a service and adds a token in these settings, which say that it
 * sends a recording over the internet. Turning the service off (or to another one) deletes the
 * recordings waiting for the network.
 */
object SongInfo : SkillInfo("song") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_song)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_song)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.MusicNote)

    // No neededPermissions: notification access only enables tier 1 (without it, tier 1 is
    // skipped), and the microphone is Dicio's own permission, checked before recording.

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Song[ctx.sentencesLanguage] ?: return null
        return SongSkill(SongInfo, data)
    }

    override val renderSettings: @Composable () -> Unit get() = @Composable {
        SongSettings()
    }
}

@Composable
private fun SongSettings() {
    val context = LocalContext.current
    val dataStore = context.songSettingsDataStore
    val data by dataStore.data.collectAsState(SkillSettingsSongSerializer.defaultValue)
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableIntStateOf(0) }
    fun update(change: SkillSettingsSong.Builder.() -> Unit) {
        scope.launch {
            var before: SkillSettingsSong? = null
            val after = dataStore.updateData {
                before = it
                it.toBuilder().apply(change).build()
            }
            if (before?.let { SongProviderConfig.discardsQueue(it, after) } == true) {
                // the card named a service that is gone now: its recordings aren't sent
                withContext(Dispatchers.IO) { SongQueueWorker.discardQueue(context) }
                waiting = 0
            }
        }
    }

    Column {
        SettingsItem(
            title = stringResource(R.string.pref_song_network_title),
            icon = Icons.Default.Public,
            description = stringResource(R.string.pref_song_network_summary),
        )

        // tier 1
        var listenerAccess by remember { mutableStateOf(SongDevice.hasListenerAccess(context)) }
        var askedForListener by rememberSaveable { mutableStateOf(false) }
        LifecycleResumeEffect(null) {
            listenerAccess = SongDevice.hasListenerAccess(context)
            onPauseOrDispose {}
        }
        SettingsItem(
            title = stringResource(R.string.pref_song_listener_title),
            icon = Icons.Default.Notifications,
            description = stringResource(
                when {
                    listenerAccess -> R.string.pref_song_listener_allowed
                    // back from the system page without access: the switch may be restricted
                    askedForListener -> R.string.pref_song_listener_restricted
                    else -> R.string.pref_song_listener_missing
                }
            ),
            modifier = Modifier.clickable {
                askedForListener = true
                openListenerSettings(context)
            },
        )

        // tier 3: GAR says Audile's answers if it can read Audile's result notifications
        var installedAudile by remember {
            mutableStateOf(AndroidAudileLauncher.installedAudile(context))
        }
        LifecycleResumeEffect(null) {
            installedAudile = AndroidAudileLauncher.installedAudile(context)
            onPauseOrDispose {}
        }
        val audile = installedAudile
        if (audile != null) {
            SettingsItem(
                title = stringResource(R.string.pref_song_audile_title),
                icon = Icons.Default.RecordVoiceOver,
                description = stringResource(R.string.pref_song_audile_summary),
                modifier = Modifier.clickable { openAudileForegroundSettings(context, audile) },
            )
        } else {
            // also after the user said no to GAR's question about installing it
            SettingsItem(
                title = stringResource(R.string.pref_song_audile_install_title),
                icon = Icons.Default.Download,
                description = stringResource(R.string.pref_song_audile_install_summary),
                modifier = Modifier.clickable { openAudileInstall(context) },
            )
        }

        // tier 2
        val sends = { name: String -> context.getString(R.string.pref_song_provider_sends, name) }
        ListSetting(
            title = stringResource(R.string.pref_song_provider),
            icon = Icons.Default.MusicNote,
            possibleValues = listOf(
                ListSetting.Value(
                    SongProvider.SONG_PROVIDER_NONE,
                    stringResource(R.string.pref_song_provider_none),
                    stringResource(R.string.pref_song_provider_none_description),
                ),
                ListSetting.Value(SongProvider.SONG_PROVIDER_AUDD, "AudD", sends("AudD")),
                ListSetting.Value(SongProvider.SONG_PROVIDER_ACRCLOUD, "ACRCloud", sends("ACRCloud")),
            ),
        ).Render(
            value = when (val provider = data.provider) {
                SongProvider.UNRECOGNIZED -> SongProvider.SONG_PROVIDER_NONE
                else -> provider
            },
            onValueChange = { update { provider = it } },
        )

        when (data.provider) {
            SongProvider.SONG_PROVIDER_AUDD -> SecretSetting(
                title = stringResource(R.string.pref_song_audd_token),
                emptyDescription = stringResource(R.string.pref_song_audd_token_empty),
                value = data.auddApiToken,
                onValueChange = { update { auddApiToken = it.trim() } },
            )
            SongProvider.SONG_PROVIDER_ACRCLOUD -> {
                StringSetting(
                    title = stringResource(R.string.pref_song_acrcloud_host),
                    icon = Icons.Default.Dns,
                    descriptionWhenEmpty = stringResource(R.string.pref_song_acrcloud_host_empty),
                ).Render(
                    value = data.acrcloudHost,
                    onValueChange = {
                        val host = SongProviderConfig.normalizeHost(it)
                        if (host.isEmpty() || SongProviderConfig.isValidHost(host)) {
                            update { acrcloudHost = host }
                        } else {
                            Toast.makeText(
                                context, R.string.pref_song_acrcloud_host_invalid, Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
                SecretSetting(
                    title = stringResource(R.string.pref_song_acrcloud_key),
                    emptyDescription = stringResource(R.string.pref_song_secret_empty),
                    value = data.acrcloudAccessKey,
                    onValueChange = { update { acrcloudAccessKey = it.trim() } },
                )
                SecretSetting(
                    title = stringResource(R.string.pref_song_acrcloud_secret),
                    emptyDescription = stringResource(R.string.pref_song_secret_empty),
                    value = data.acrcloudAccessSecret,
                    onValueChange = { update { acrcloudAccessSecret = it.trim() } },
                )
            }
            else -> {}
        }

        // the result of a recording made offline comes as a notification
        if (data.provider == SongProvider.SONG_PROVIDER_AUDD ||
            data.provider == SongProvider.SONG_PROVIDER_ACRCLOUD
        ) {
            var canNotify by remember { mutableStateOf(SongNotifications.canShow(context)) }
            LifecycleResumeEffect(null) {
                canNotify = SongNotifications.canShow(context)
                onPauseOrDispose {}
            }
            val allow = rememberAllowSongNotifications { canNotify = SongNotifications.canShow(context) }
            SettingsItem(
                title = stringResource(R.string.pref_song_notifications_title),
                icon = Icons.Default.NotificationsActive,
                description = stringResource(
                    if (canNotify) R.string.pref_song_notifications_on
                    else R.string.pref_song_notifications_off
                ),
                modifier = Modifier.clickable {
                    if (canNotify) SongNotifications.openSettings(context) else allow()
                },
            )
        }

        // recordings waiting for the network
        LifecycleResumeEffect(null) {
            val job = scope.launch {
                waiting = withContext(Dispatchers.IO) {
                    SongClipQueue.forContext(context).size(System.currentTimeMillis())
                }
            }
            onPauseOrDispose { job.cancel() }
        }
        var confirmDelete by rememberSaveable { mutableStateOf(false) }
        if (waiting > 0) {
            SettingsItem(
                title = stringResource(R.string.pref_song_queue_title),
                icon = Icons.Default.Delete,
                description = stringResource(R.string.pref_song_queue_summary, waiting),
                modifier = Modifier.clickable { confirmDelete = true },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.pref_song_queue_delete_title)) },
                text = { Text(stringResource(R.string.pref_song_queue_delete_text, waiting)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            withContext(Dispatchers.IO) { SongQueueWorker.discardQueue(context) }
                            waiting = 0
                        }
                    }) {
                        Text(stringResource(R.string.pref_song_queue_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }
}

/**
 * A text setting that never shows its value: "Set" or [emptyDescription]. Its dialog doesn't show
 * the stored value either, hides what is typed (unless the user shows it) and asks the keyboard
 * for a password field, so that the keyboard doesn't learn the token.
 */
@Composable
private fun SecretSetting(
    title: String,
    emptyDescription: String,
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector = Icons.Default.Key,
) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    SettingsItem(
        title = title,
        icon = icon,
        description = if (value.isEmpty()) emptyDescription
            else stringResource(R.string.pref_song_secret_set),
        modifier = Modifier.clickable { dialogOpen = true },
    )
    if (dialogOpen) {
        SecretDialog(
            title = title,
            isSet = value.isNotEmpty(),
            onSave = onValueChange,
            onDismiss = { dialogOpen = false },
        )
    }
}

/** Empty means unchanged; Clear removes the stored value. */
@Composable
private fun SecretDialog(
    title: String,
    isSet: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // remember, not rememberSaveable: the secret doesn't go into the saved instance state
    var value by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = {
                    Text(stringResource(
                        if (isSet) R.string.pref_song_secret_keep else R.string.pref_song_secret_enter
                    ))
                },
                visualTransformation = if (visible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (visible) R.string.pref_song_secret_hide
                                else R.string.pref_song_secret_show
                            ),
                        )
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (value.isNotBlank()) {
                    onSave(value.trim())
                }
                onDismiss()
            }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            Row {
                if (isSet) {
                    TextButton(onClick = {
                        onSave("")
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.pref_song_secret_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

/** Notification access for GAR's listener, which tier 1 and Audile's spoken answers need. */
fun openListenerSettings(context: Context) {
    val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            SongDevice.listenerComponent(context).flattenToString(),
        )
    } else {
        null
    }
    for (intent in listOfNotNull(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // try the list of all listeners
        }
    }
}
