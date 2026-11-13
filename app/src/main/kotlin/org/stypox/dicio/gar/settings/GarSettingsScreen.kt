package org.stypox.dicio.gar.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import org.stypox.dicio.R
import org.stypox.dicio.gar.model.GarModelState
import org.stypox.dicio.gar.model.ModelCatalog
import org.stypox.dicio.gar.ui.garble.GarbleTextButton
import org.stypox.dicio.settings.ui.BooleanSetting
import org.stypox.dicio.settings.ui.ListSetting
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
        // GAR's other settings moved: You and places to GarYouPlacesScreen, speech recognition,
        // the assistant, screen and conversation rows to GarVoiceScreen, Weather app to the
        // weather skill's settings; this page holds the language model only
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
                                if (partialSize == null) R.string.gar_pref_download_model
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
                item {
                    DeleteItem(fileSize, formatSize, canDownloadAgain = false) { viewModel.delete(model) }
                }
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
                GarbleTextButton(onClick = { showLicenses = false }) {
                    Text(stringResource(R.string.gar_close))
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
internal fun DeleteItem(
    fileSize: Long,
    formatSize: (Long) -> String,
    title: String = stringResource(R.string.gar_pref_delete),
    canDownloadAgain: Boolean = true,
    onClick: () -> Unit,
) {
    // A tap used to delete at once; a model is tens to hundreds of MB to get back, so ask first
    var confirm by rememberSaveable { mutableStateOf(false) }
    val size = formatSize(fileSize)
    SettingsItem(
        title = title,
        icon = Icons.Default.Delete,
        description = stringResource(R.string.gar_pref_delete_summary, size),
        modifier = Modifier.clickable { confirm = true },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.gar_delete_confirm_title, title)) },
            text = {
                Text(
                    stringResource(
                        if (canDownloadAgain) R.string.gar_delete_confirm_text
                        else R.string.gar_delete_confirm_text_import,
                        size,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onClick()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.gar_delete_confirm))
                }
            },
            dismissButton = {
                GarbleTextButton(onClick = { confirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
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
