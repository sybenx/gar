package org.stypox.dicio.gar.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.stypox.dicio.BuildConfig
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarKeepScreenOn
import org.stypox.dicio.gar.conversation.GarContinuedListening
import org.stypox.dicio.gar.model.GarModelState
import org.stypox.dicio.gar.model.ModelCatalog
import org.stypox.dicio.gar.stt.GarSttPromptNames
import org.stypox.dicio.gar.stt.GarSttState
import org.stypox.dicio.gar.stt.SttModelCatalog
import org.stypox.dicio.gar.voicelog.GarVoiceLog
import org.stypox.dicio.settings.ui.BooleanSetting
import org.stypox.dicio.settings.ui.ListSetting
import org.stypox.dicio.settings.ui.SettingsCategoryTitle
import org.stypox.dicio.settings.ui.SettingsItem
import kotlinx.serialization.Serializable

/** Navigation route of [GarSettingsScreen] (kept here so Dicio's Routes.kt stays unchanged). */
@Serializable
object GarModelSettings

/** The entry in Dicio's main settings that opens [GarSettingsScreen]. */
@Composable
fun GarSettingsItem(onClick: () -> Unit) {
    SettingsItem(
        title = stringResource(R.string.gar_pref_title),
        icon = Icons.Default.Psychology,
        description = stringResource(R.string.gar_pref_summary),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun GarSettingsScreen(
    navigationIcon: @Composable () -> Unit,
    viewModel: GarSettingsViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(stringResource(R.string.gar_pref_title)) },
                navigationIcon = navigationIcon
            )
        }
    ) {
        GarSettingsScreen(
            viewModel = viewModel,
            modifier = Modifier.padding(it),
        )
    }
}

@Composable
private fun GarSettingsScreen(
    viewModel: GarSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val settings by viewModel.settingsState.collectAsState()
    val modelState by viewModel.modelState.collectAsState()

    val model = when (val model = settings.model) {
        GarModel.UNRECOGNIZED -> GarModel.GAR_MODEL_QWEN3_0_6B
        else -> model
    }
    val downloadable = ModelCatalog.forSetting(model)
    // only re-checked when the state or the files change
    val filesChanged by viewModel.filesChanged.collectAsState()
    val fileSize = remember(modelState, model, filesChanged) { viewModel.fileSize(model) }
    val partialSize =
        remember(modelState, model, filesChanged) { viewModel.partialDownloadSize(model) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val formatSize = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }

    var pendingImport by rememberSaveable { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) {
            pendingImport = it
        }
    }

    LazyColumn(modifier) {
        // who the user is: "text myself", "call me" and the first name in the name hint
        item {
            GarYouSetting(viewModel)
        }

        // where "take me home" and "take me to work" go (gar:places)
        item {
            GarPlacesSetting()
        }

        // which maps app routes there, and looking addresses up online (gar:places)
        item {
            GarNavigationSetting()
        }

        item {
            SttSettings(viewModel, settings, formatSize)
        }

        // the assistant sheet or the full app (gar/DESIGN.md, "Assistant entry points")
        item {
            SettingsCategoryTitle(stringResource(R.string.gar_pref_assistant_title))
        }

        item {
            BooleanSetting(
                title = stringResource(R.string.gar_pref_assist_full_screen),
                icon = Icons.Default.Fullscreen,
                descriptionOn = stringResource(R.string.gar_pref_assist_full_screen_on),
                descriptionOff = stringResource(R.string.gar_pref_assist_full_screen_off),
            ).Render(settings.assistFullScreen, viewModel::setAssistFullScreen)
        }

        item {
            SettingsCategoryTitle(stringResource(R.string.gar_pref_screen_title))
        }

        item {
            BooleanSetting(
                title = stringResource(R.string.gar_pref_keep_screen_on),
                icon = Icons.Default.LightMode,
                descriptionOn = stringResource(R.string.gar_pref_keep_screen_on_on),
                descriptionOff = stringResource(R.string.gar_pref_keep_screen_on_off),
            ).Render(GarKeepScreenOn.settingOn(settings), viewModel::setKeepScreenOn)
        }

        // continued listening and speaking vs showing (gar/DESIGN.md, "Continued conversation")
        item {
            SettingsCategoryTitle(stringResource(R.string.gar_pref_conversation_title))
        }

        item {
            BooleanSetting(
                title = stringResource(R.string.gar_pref_continued_listening),
                icon = Icons.Default.Hearing,
                descriptionOn = stringResource(R.string.gar_pref_continued_listening_summary),
                descriptionOff = stringResource(R.string.gar_pref_continued_listening_summary),
            ).Render(GarContinuedListening.settingOn(settings), viewModel::setContinuedListening)
        }

        item {
            BooleanSetting(
                title = stringResource(R.string.gar_pref_speak_typed_answers),
                icon = Icons.Default.Keyboard,
                descriptionOn = stringResource(R.string.gar_pref_speak_typed_answers_on),
                descriptionOff = stringResource(R.string.gar_pref_speak_typed_answers_off),
            ).Render(settings.speakTypedAnswers, viewModel::setSpeakTypedAnswers)
        }

        item {
            SettingsCategoryTitle(stringResource(R.string.gar_stt_settings_llm_title))
        }

        item {
            BooleanSetting(
                title = stringResource(R.string.gar_pref_llm_enabled),
                icon = Icons.Default.Psychology,
                descriptionOn = stringResource(R.string.gar_pref_llm_enabled_on),
                descriptionOff = stringResource(R.string.gar_pref_llm_enabled_off),
            ).Render(settings.llmEnabled, viewModel::setLlmEnabled)
        }

        item {
            modelSetting(settings, formatSize).Render(model, viewModel::setModel)
        }

        item {
            val retry = (modelState as? GarModelState.Error)?.canRetry == true
            SettingsItem(
                title = stringResource(R.string.gar_pref_status),
                icon = Icons.Default.Info,
                description = modelState?.let { statusText(it, model) },
                modifier = if (retry) Modifier.clickable { viewModel.retryLoad() } else Modifier,
            )
        }

        if (downloadable != null) {
            val termsUrl = downloadable.termsUrl
            val termsOk = termsUrl == null || settings.functionGemmaTermsAccepted
            if (termsUrl != null) {
                item {
                    SettingsItem(
                        title = stringResource(R.string.gar_pref_terms),
                        icon = Icons.Default.Gavel,
                        description = stringResource(
                            if (settings.functionGemmaTermsAccepted) R.string.gar_pref_terms_accepted
                            else R.string.gar_pref_terms_summary
                        ),
                        modifier = Modifier.clickable { uriHandler.openUri(termsUrl) },
                        content = if (settings.functionGemmaTermsAccepted) {
                            null
                        } else {
                            {
                                TextButton(onClick = viewModel::acceptFunctionGemmaTerms) {
                                    Text(stringResource(R.string.gar_pref_terms_accept))
                                }
                            }
                        },
                    )
                }
                downloadable.usePolicyUrl?.let { policyUrl ->
                    item {
                        SettingsItem(
                            title = stringResource(R.string.gar_pref_prohibited_use),
                            icon = Icons.Default.Policy,
                            description = stringResource(R.string.gar_pref_prohibited_use_summary),
                            modifier = Modifier.clickable { uriHandler.openUri(policyUrl) },
                        )
                    }
                }
            }

            if (modelState is GarModelState.Downloading) {
                item {
                    SettingsItem(
                        title = stringResource(R.string.gar_pref_download_cancel),
                        icon = Icons.Default.Close,
                        description = stringResource(R.string.gar_pref_download_cancel_summary),
                        modifier = Modifier.clickable { viewModel.cancelDownload() },
                    )
                }
            } else if (fileSize == null) {
                // the download needs the terms accepted first, if the model has any
                if (termsOk) {
                    item {
                        SettingsItem(
                            title = stringResource(
                                if (partialSize == null) R.string.gar_pref_download
                                else R.string.gar_pref_download_resume
                            ),
                            icon = Icons.Default.Download,
                            description = if (partialSize == null) {
                                stringResource(
                                    R.string.gar_pref_download_summary,
                                    formatSize(downloadable.sizeBytes),
                                )
                            } else {
                                stringResource(
                                    R.string.gar_pref_download_resume_summary,
                                    formatSize(partialSize),
                                    formatSize(downloadable.sizeBytes),
                                )
                            },
                            modifier = Modifier.clickable { viewModel.download(model) },
                        )
                    }
                }
                if (partialSize != null) {
                    item {
                        DeleteItem(
                            partialSize,
                            formatSize,
                            title = stringResource(R.string.gar_pref_delete_partial),
                        ) { viewModel.delete(model) }
                    }
                }
            } else {
                item { DeleteItem(fileSize, formatSize) { viewModel.delete(model) } }
            }
        } else {
            item {
                SettingsItem(
                    title = stringResource(R.string.gar_pref_import),
                    icon = Icons.Default.UploadFile,
                    description = stringResource(R.string.gar_pref_import_summary),
                    modifier = Modifier.clickable { importLauncher.launch(arrayOf("*/*")) },
                )
            }
            if (fileSize != null) {
                item { DeleteItem(fileSize, formatSize) { viewModel.delete(model) } }
            }
        }

        item {
            BooleanSetting(
                title = stringResource(R.string.gar_pref_confirm),
                icon = Icons.Default.TaskAlt,
                descriptionOn = stringResource(R.string.gar_pref_confirm_on),
                descriptionOff = stringResource(R.string.gar_pref_confirm_off),
            ).Render(settings.confirmLlmActions, viewModel::setConfirmLlmActions)
        }

        item {
            SettingsItem(
                title = stringResource(R.string.gar_pref_licenses),
                icon = Icons.Default.Description,
                description = stringResource(R.string.gar_pref_licenses_summary),
                modifier = Modifier.clickable { showLicenses = true },
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showLicenses) {
        val licenses = remember { viewModel.licenses() }
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.gar_pref_licenses)) },
            text = {
                Text(
                    text = licenses.joinToString("\n\n") { (name, text) -> "$name\n\n$text" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
        )
    }

    pendingImport?.let { uri ->
        familySetting().ChooserDialog(
            value = settings.customModelFamily,
            onValueChange = { family -> viewModel.importCustom(uri, family) },
            onDismissRequest = { pendingImport = null },
        )
    }
}

@Composable
private fun DeleteItem(
    fileSize: Long,
    formatSize: (Long) -> String,
    title: String = stringResource(R.string.gar_pref_delete),
    onClick: () -> Unit,
) {
    SettingsItem(
        title = title,
        icon = Icons.Default.Delete,
        description = stringResource(R.string.gar_pref_delete_summary, formatSize(fileSize)),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun modelSetting(settings: GarSettings, formatSize: (Long) -> String) = ListSetting(
    title = stringResource(R.string.gar_pref_model),
    icon = Icons.Default.Memory,
    possibleValues = listOf(
        ListSetting.Value(
            value = GarModel.GAR_MODEL_QWEN3_0_6B,
            name = stringResource(R.string.gar_pref_model_qwen_name),
            description = stringResource(
                R.string.gar_pref_model_size_license,
                formatSize(ModelCatalog.QWEN3_0_6B.sizeBytes),
                ModelCatalog.QWEN3_0_6B.licenseName,
            ),
        ),
        ListSetting.Value(
            value = GarModel.GAR_MODEL_FUNCTION_GEMMA_270M,
            name = stringResource(R.string.gar_pref_model_function_gemma_name),
            description = stringResource(
                R.string.gar_pref_model_size_license,
                formatSize(ModelCatalog.FUNCTION_GEMMA_270M.sizeBytes),
                ModelCatalog.FUNCTION_GEMMA_270M.licenseName,
            ),
        ),
        ListSetting.Value(
            value = GarModel.GAR_MODEL_CUSTOM,
            name = stringResource(R.string.gar_pref_model_custom_name),
            description = settings.customModelName.takeIf { it.isNotEmpty() }
                ?: stringResource(R.string.gar_pref_model_custom_summary),
        ),
    ),
)

@Composable
private fun familySetting() = ListSetting(
    title = stringResource(R.string.gar_pref_import_family),
    possibleValues = listOf(
        ListSetting.Value(
            value = GarModelFamily.GAR_MODEL_FAMILY_HERMES,
            name = stringResource(R.string.gar_pref_import_family_hermes),
        ),
        ListSetting.Value(
            value = GarModelFamily.GAR_MODEL_FAMILY_FUNCTION_GEMMA,
            name = stringResource(R.string.gar_pref_import_family_function_gemma),
        ),
    ),
)

@Composable
private fun statusText(state: GarModelState, model: GarModel): String = when (state) {
    GarModelState.NotDownloaded -> stringResource(
        if (model == GarModel.GAR_MODEL_CUSTOM) R.string.gar_status_no_custom_model
        else R.string.gar_status_not_downloaded
    )
    is GarModelState.Downloading -> state.fraction
        ?.let { stringResource(R.string.gar_status_downloading, (it * 100).toInt()) }
        ?: stringResource(R.string.gar_status_downloading_unknown)
    GarModelState.Importing -> stringResource(R.string.gar_status_importing)
    GarModelState.Ready -> stringResource(R.string.gar_status_ready)
    GarModelState.Loading -> stringResource(R.string.gar_status_loading)
    GarModelState.Loaded -> stringResource(R.string.gar_status_loaded)
    is GarModelState.Error -> stringResource(R.string.gar_status_error, state.message)
}

/**
 * The "Speech recognition" section: the Whisper model (one on disk; switching downloads the new
 * one first), its download, deletion and status, "Help recognise contact names",
 * the voice log and the debug-only "Keep recordings" switch.
 */
@Composable
private fun SttSettings(
    viewModel: GarSettingsViewModel,
    settings: GarSettings,
    formatSize: (Long) -> String,
) {
    val sttState by viewModel.sttState.collectAsState()
    val transferModel by viewModel.sttTransferModel.collectAsState()
    val filesChanged by viewModel.sttFilesChanged.collectAsState()

    val selected = when (val model = settings.sttModel) {
        GarSttModel.UNRECOGNIZED -> GarSttModel.GAR_STT_MODEL_BASE_EN
        else -> model
    }
    // a switch shows the model it downloads, which becomes the selected one when done
    val shown = transferModel ?: selected
    val fileSize = remember(sttState, shown, filesChanged) { viewModel.sttFileSize(shown) }
    val partialSize =
        remember(sttState, shown, filesChanged) { viewModel.sttPartialDownloadSize(shown) }

    Column {
        SettingsCategoryTitle(stringResource(R.string.gar_stt_settings_title), topPadding = 8.dp)

        sttModelSetting(formatSize).Render(shown, viewModel::switchSttModel)

        val state = sttState
        SettingsItem(
            title = stringResource(R.string.gar_pref_status),
            icon = Icons.Default.Info,
            description = state?.let { sttStatusText(it) },
            modifier = when (state) {
                is GarSttState.LoadFailed -> Modifier.clickable { viewModel.retrySttLoad() }
                is GarSttState.DownloadFailed ->
                    Modifier.clickable { viewModel.switchSttModel(state.model) }
                else -> Modifier
            },
        )

        if (state is GarSttState.Downloading) {
            SettingsItem(
                title = stringResource(R.string.gar_pref_download_cancel),
                icon = Icons.Default.Close,
                description = stringResource(R.string.gar_pref_download_cancel_summary),
                modifier = Modifier.clickable { viewModel.cancelSttDownload() },
            )
        } else if (fileSize == null) {
            SettingsItem(
                title = stringResource(
                    if (partialSize == null) R.string.gar_pref_download
                    else R.string.gar_pref_download_resume
                ),
                icon = Icons.Default.Download,
                description = if (partialSize == null) {
                    stringResource(
                        R.string.gar_stt_download_summary,
                        formatSize(SttModelCatalog.downloadSize(shown)),
                    )
                } else {
                    stringResource(
                        R.string.gar_pref_download_resume_summary,
                        formatSize(partialSize),
                        formatSize(SttModelCatalog.downloadSize(shown)),
                    )
                },
                modifier = Modifier.clickable { viewModel.switchSttModel(shown) },
            )
            if (partialSize != null) {
                DeleteItem(
                    partialSize,
                    formatSize,
                    title = stringResource(R.string.gar_pref_delete_partial),
                ) { viewModel.deleteStt() }
            }
        } else {
            DeleteItem(
                fileSize,
                formatSize,
                title = stringResource(R.string.gar_stt_delete),
            ) { viewModel.deleteStt() }
        }

        SettingsItem(
            title = stringResource(R.string.gar_stt_settings_privacy_title),
            icon = Icons.Default.Lock,
            description = stringResource(R.string.gar_stt_settings_privacy),
        )

        BooleanSetting(
            title = stringResource(R.string.gar_stt_contact_names),
            icon = Icons.Default.Person,
            descriptionOn = stringResource(R.string.gar_stt_contact_names_summary),
            descriptionOff = stringResource(R.string.gar_stt_contact_names_summary),
        ).Render(
            GarSttPromptNames.contactNamesOn(settings.sttContactNames),
            viewModel::setSttContactNames,
        )

        VoiceLogSettings(viewModel, settings)

        if (BuildConfig.DEBUG) {
            BooleanSetting(
                title = stringResource(R.string.gar_stt_keep_recordings),
                icon = Icons.Default.RecordVoiceOver,
                descriptionOn = stringResource(R.string.gar_stt_keep_recordings_summary),
                descriptionOff = stringResource(R.string.gar_stt_keep_recordings_summary),
            ).Render(settings.sttKeepRecordings, viewModel::setSttKeepRecordings)
        }
    }
}

/** "Voice log" (on by default in debug builds only), "Share voice log" and "Clear voice log", see GarVoiceLog. */
@Composable
private fun VoiceLogSettings(viewModel: GarSettingsViewModel, settings: GarSettings) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    BooleanSetting(
        title = stringResource(R.string.gar_voice_log_title),
        icon = Icons.Default.History,
        descriptionOn = stringResource(R.string.gar_voice_log_summary),
        descriptionOff = stringResource(R.string.gar_voice_log_summary),
    ).Render(GarVoiceLog.settingOn(settings), viewModel::setVoiceLog)

    SettingsItem(
        title = stringResource(R.string.gar_voice_log_share),
        icon = Icons.Default.Share,
        description = stringResource(R.string.gar_voice_log_share_summary),
        modifier = Modifier.clickable { viewModel.shareVoiceLog() },
    )

    SettingsItem(
        title = stringResource(R.string.gar_voice_log_clear),
        icon = Icons.Default.DeleteSweep,
        description = stringResource(R.string.gar_voice_log_clear_summary),
        modifier = Modifier.clickable { confirmClear = true },
    )

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearVoiceLog()
                }) {
                    Text(stringResource(R.string.gar_voice_log_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            text = { Text(stringResource(R.string.gar_voice_log_clear_summary)) },
        )
    }
}

@Composable
private fun sttModelSetting(formatSize: (Long) -> String) = ListSetting(
    title = stringResource(R.string.gar_stt_model),
    icon = Icons.Default.Mic,
    possibleValues = listOf(
        ListSetting.Value(
            value = GarSttModel.GAR_STT_MODEL_BASE_EN,
            name = stringResource(R.string.gar_stt_model_base_en),
            description = stringResource(
                R.string.gar_stt_model_base_en_summary,
                formatSize(SttModelCatalog.BASE_EN.sizeBytes),
                SttModelCatalog.BASE_EN.licenseName,
            ),
        ),
        ListSetting.Value(
            value = GarSttModel.GAR_STT_MODEL_TINY_EN,
            name = stringResource(R.string.gar_stt_model_tiny_en),
            description = stringResource(
                R.string.gar_stt_model_tiny_en_summary,
                formatSize(SttModelCatalog.TINY_EN.sizeBytes),
                SttModelCatalog.TINY_EN.licenseName,
            ),
        ),
    ),
)

@Composable
private fun sttStatusText(state: GarSttState): String = when (state) {
    GarSttState.NotDownloaded -> stringResource(R.string.gar_status_not_downloaded)
    is GarSttState.Downloading -> {
        val percent = ((state.fraction ?: 0f) * 100).toInt()
        stringResource(
            R.string.gar_stt_status_downloading_other,
            SttModelCatalog.forSetting(state.model).displayName,
            percent,
        )
    }
    is GarSttState.DownloadFailed -> stringResource(
        if (state.damaged) R.string.gar_stt_status_download_damaged
        else R.string.gar_stt_status_download_failed
    )
    GarSttState.Ready -> stringResource(R.string.gar_status_ready)
    GarSttState.Loading -> stringResource(R.string.gar_status_loading)
    GarSttState.Loaded -> stringResource(R.string.gar_status_loaded)
    is GarSttState.LoadFailed -> stringResource(
        if (state.crashed) R.string.gar_error_load_crashed else R.string.gar_error_load_failed
    )
}
