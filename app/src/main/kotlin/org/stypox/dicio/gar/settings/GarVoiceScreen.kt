package org.stypox.dicio.gar.settings

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.BreakfastDining
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.SpeakerPhone
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.serialization.Serializable
import org.stypox.dicio.BuildConfig
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarKeepScreenOn
import org.stypox.dicio.gar.assist.GarAssistantPrompt
import org.stypox.dicio.gar.conversation.GarContinuedListening
import org.stypox.dicio.gar.stt.GarSttPromptNames
import org.stypox.dicio.gar.stt.GarSttState
import org.stypox.dicio.gar.stt.SttModelCatalog
import org.stypox.dicio.gar.stt.rememberResolvedInputDevice
import org.stypox.dicio.gar.ui.garble.GarbleTextButton
import org.stypox.dicio.gar.voicelog.GarVoiceLog
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.settings.MainSettingsViewModel
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.Language
import org.stypox.dicio.settings.datastore.SpeechOutputDevice
import org.stypox.dicio.settings.datastore.SttPlaySound
import org.stypox.dicio.settings.datastore.WakeDevice
import org.stypox.dicio.settings.languageSetting
import org.stypox.dicio.settings.ui.BooleanSetting
import org.stypox.dicio.settings.ui.IntSetting
import org.stypox.dicio.settings.ui.ListSetting
import org.stypox.dicio.settings.ui.SettingsCategoryTitle
import org.stypox.dicio.settings.ui.SettingsItem

/** Navigation route of [GarVoiceScreen] (kept here so Dicio's Routes.kt stays unchanged). */
@Serializable
object GarVoiceSettings

/** The "Assistant and voice" row in Dicio's main settings. */
@Composable
fun GarVoiceItem(onClick: () -> Unit) {
    SettingsItem(
        title = stringResource(R.string.gar_voice_title),
        icon = Icons.Default.Mic,
        description = stringResource(R.string.gar_voice_summary),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * GAR. "Assistant and voice": the default assistant app, the language, how Garble listens (input
 * method, the Whisper model, the wake word, sounds and silence) and how it answers, then the
 * voice log. Dicio's "Input and output methods" values live here, saved through Dicio's own
 * [MainSettingsViewModel], so no value moved; the rows are GAR's own builders below with plain
 * copy, and Dicio's factories in Definitions.kt stay for upstream.
 */
@Composable
fun GarVoiceScreen(
    navigationIcon: @Composable () -> Unit,
    mainViewModel: MainSettingsViewModel = hiltViewModel(),
    viewModel: GarSettingsViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(stringResource(R.string.gar_voice_title)) },
                navigationIcon = navigationIcon
            )
        }
    ) {
        GarVoiceScreen(
            mainViewModel = mainViewModel,
            viewModel = viewModel,
            modifier = Modifier.padding(it),
        )
    }
}

@Composable
private fun GarVoiceScreen(
    mainViewModel: MainSettingsViewModel,
    viewModel: GarSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val main by mainViewModel.settingsState.collectAsState()
    val settings by viewModel.settingsState.collectAsState()
    val formatSize = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    // UNSET shows the device it resolves to, Vosk or Whisper
    val inputDevice = rememberResolvedInputDevice(main.inputDevice)
    val isHeyDicio by mainViewModel.isHeyDicio.collectAsState(true)
    val importWakeWord = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) {
            mainViewModel.addOwwUserWakeFile(it)
        }
    }

    LazyColumn(modifier) {
        /* ASSISTANT */
        item { SettingsCategoryTitle(stringResource(R.string.gar_voice_assistant_title), topPadding = 4.dp) }
        item { DefaultAssistantItem() }
        item {
            languageSetting().Render(
                when (val language = main.language) {
                    Language.UNRECOGNIZED -> Language.LANGUAGE_SYSTEM
                    else -> language
                },
                mainViewModel::setLanguage,
            )
        }
        // the assistant sheet or the full app (gar/DESIGN.md, "Assistant entry points")
        item {
            BooleanSetting(
                title = stringResource(R.string.gar_pref_assist_full_screen),
                icon = Icons.Default.Fullscreen,
                descriptionOn = stringResource(R.string.gar_pref_assist_full_screen_on),
                descriptionOff = stringResource(R.string.gar_pref_assist_full_screen_off),
            ).Render(settings.assistFullScreen, viewModel::setAssistFullScreen)
        }
        item {
            BooleanSetting(
                title = stringResource(R.string.gar_pref_keep_screen_on),
                icon = Icons.Default.LightMode,
                descriptionOn = stringResource(R.string.gar_pref_keep_screen_on_on),
                descriptionOff = stringResource(R.string.gar_pref_keep_screen_on_off),
            ).Render(GarKeepScreenOn.settingOn(settings), viewModel::setKeepScreenOn)
        }

        /* SPEECH RECOGNITION */
        item { SettingsCategoryTitle(stringResource(R.string.gar_stt_settings_title)) }
        item { inputDeviceSetting().Render(inputDevice, mainViewModel::setInputDevice) }
        // the Whisper model right under the input method that uses it
        item {
            SttModelRows(
                viewModel,
                settings,
                formatSize,
                whisperChosen = inputDevice == InputDevice.INPUT_DEVICE_WHISPER,
            )
        }

        /* WAKE WORD */
        item { SettingsCategoryTitle(stringResource(R.string.gar_voice_wake_title)) }
        val wakeDevice = when (val device = main.wakeDevice) {
            WakeDevice.UNRECOGNIZED,
            WakeDevice.WAKE_DEVICE_UNSET -> WakeDevice.WAKE_DEVICE_OWW
            else -> device
        }
        item {
            wakeDeviceSetting(GarSettingsSummaries.wakeWord(wakeDevice, isHeyDicio))
                .Render(wakeDevice, mainViewModel::setWakeDevice)
        }
        if (wakeDevice == WakeDevice.WAKE_DEVICE_OWW) {
            item {
                if (isHeyDicio) {
                    // the wake word is "Hey Dicio", so there is no custom model at the moment
                    SettingsItem(
                        modifier = Modifier.clickable { importWakeWord.launch(arrayOf("*/*")) },
                        title = stringResource(R.string.gar_voice_wake_import),
                        icon = Icons.Default.UploadFile,
                        description = stringResource(R.string.gar_voice_wake_import_summary),
                    )
                } else {
                    SettingsItem(
                        modifier = Modifier.clickable { mainViewModel.removeOwwUserWakeFile() },
                        title = stringResource(R.string.gar_voice_wake_delete),
                        icon = Icons.Default.DeleteSweep,
                        description = stringResource(R.string.gar_voice_wake_delete_summary),
                    )
                }
            }
        }

        /* LISTENING */
        item { SettingsCategoryTitle(stringResource(R.string.gar_voice_listening_title)) }
        item {
            sttPlaySoundSetting().Render(
                when (val sttPlaySound = main.sttPlaySound) {
                    SttPlaySound.UNRECOGNIZED,
                    SttPlaySound.STT_PLAY_SOUND_UNSET -> SttPlaySound.STT_PLAY_SOUND_NOTIFICATION
                    else -> sttPlaySound
                },
                mainViewModel::setSttPlaySound
            )
        }
        // only Whisper and Vosk wait for speech; the popup and the text box don't listen
        if (inputDevice == InputDevice.INPUT_DEVICE_WHISPER || inputDevice == InputDevice.INPUT_DEVICE_VOSK) {
            item {
                waitForSpeechSetting(whisper = inputDevice == InputDevice.INPUT_DEVICE_WHISPER).Render(
                    SttInputDevice.getSttSilenceDurationOrDefault(main),
                    mainViewModel::setSttSilenceDuration,
                )
            }
        }
        // continued listening (gar/DESIGN.md, "Continued conversation")
        item {
            BooleanSetting(
                title = stringResource(R.string.gar_pref_continued_listening),
                icon = Icons.Default.Hearing,
                descriptionOn = stringResource(R.string.gar_pref_continued_listening_summary),
                descriptionOff = stringResource(R.string.gar_pref_continued_listening_summary),
            ).Render(GarContinuedListening.settingOn(settings), viewModel::setContinuedListening)
        }

        /* SPOKEN ANSWERS */
        item { SettingsCategoryTitle(stringResource(R.string.gar_voice_spoken_title)) }
        item {
            speechOutputSetting().Render(
                when (val speechOutputDevice = main.speechOutputDevice) {
                    SpeechOutputDevice.UNRECOGNIZED,
                    SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_UNSET ->
                        SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_ANDROID_TTS
                    else -> speechOutputDevice
                },
                mainViewModel::setSpeechOutputDevice,
            )
        }
        item {
            BooleanSetting(
                title = stringResource(R.string.gar_pref_speak_typed_answers),
                icon = Icons.Default.Keyboard,
                descriptionOn = stringResource(R.string.gar_pref_speak_typed_answers_on),
                descriptionOff = stringResource(R.string.gar_pref_speak_typed_answers_off),
            ).Render(settings.speakTypedAnswers, viewModel::setSpeakTypedAnswers)
        }
        // Dicio's "Directly send result of speech to text popup" (auto_finish_stt_popup) is not
        // shown: nothing reads the value, in GAR or in Dicio

        /* DIAGNOSTICS */
        item { SettingsCategoryTitle(stringResource(R.string.gar_voice_diagnostics_title)) }
        item { VoiceLogSettings(viewModel, settings) }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * "Default assistant app": whether Garble is Android's digital assistant, checked again when the
 * user comes back from the system's page; a tap opens that page, as the home screen's card does.
 */
@Composable
private fun DefaultAssistantItem() {
    val context = LocalContext.current
    var isAssistant by remember { mutableStateOf<Boolean?>(null) }
    LifecycleResumeEffect(null) {
        isAssistant = GarAssistantPrompt.isAssistant(context)
        onPauseOrDispose {}
    }
    SettingsItem(
        title = stringResource(R.string.gar_voice_default_assistant),
        icon = Icons.Default.Assistant,
        description = when (isAssistant) {
            true -> stringResource(R.string.gar_voice_default_assistant_yes)
            false -> stringResource(R.string.gar_voice_default_assistant_no)
            null -> null
        },
        modifier = Modifier.clickable { GarAssistantPrompt.openPicker(context) },
    )
}

/**
 * Dicio's wake word method as On or Off, with a description that matches the value ([summary]):
 * what it listens for while on, that it doesn't listen while off.
 */
@Composable
private fun wakeDeviceSetting(summary: WakeWordSummary) = ListSetting(
    title = stringResource(R.string.gar_voice_wake_method),
    icon = Icons.Default.SettingsVoice,
    description = stringResource(
        when (summary) {
            WakeWordSummary.HEY_DICIO -> R.string.gar_voice_wake_method_summary_hey_dicio
            WakeWordSummary.CUSTOM -> R.string.gar_voice_wake_method_summary_custom
            WakeWordSummary.OFF -> R.string.gar_voice_wake_method_summary_off
        }
    ),
    possibleValues = listOf(
        ListSetting.Value(
            value = WakeDevice.WAKE_DEVICE_OWW,
            name = stringResource(R.string.gar_voice_wake_on),
        ),
        ListSetting.Value(
            value = WakeDevice.WAKE_DEVICE_NOTHING,
            name = stringResource(R.string.gar_voice_wake_off),
        ),
    ),
)

/** Dicio's input method (same values as its `inputDevice()`), named for what each one is. */
@Composable
private fun inputDeviceSetting() = ListSetting(
    title = stringResource(R.string.gar_voice_input),
    icon = Icons.Default.Mic,
    possibleValues = listOf(
        ListSetting.Value(
            value = InputDevice.INPUT_DEVICE_WHISPER,
            name = stringResource(R.string.pref_input_method_whisper),
            description = stringResource(R.string.pref_input_method_whisper_summary),
            icon = Icons.Default.GraphicEq,
        ),
        ListSetting.Value(
            value = InputDevice.INPUT_DEVICE_VOSK,
            name = stringResource(R.string.gar_voice_input_vosk),
            description = stringResource(R.string.gar_voice_input_vosk_summary),
            icon = Icons.Default.Mic,
        ),
        ListSetting.Value(
            value = InputDevice.INPUT_DEVICE_EXTERNAL_POPUP,
            name = stringResource(R.string.gar_voice_input_external),
            description = stringResource(R.string.gar_voice_input_external_summary),
            icon = Icons.Default.PictureInPictureAlt,
        ),
        ListSetting.Value(
            value = InputDevice.INPUT_DEVICE_NOTHING,
            name = stringResource(R.string.gar_voice_input_text),
            icon = Icons.Default.KeyboardAlt,
        ),
    ),
)

/** Dicio's listening sound (same values as its `sttPlaySound()`), as the volume it plays at. */
@Composable
private fun sttPlaySoundSetting() = ListSetting(
    title = stringResource(R.string.gar_voice_sound),
    icon = Icons.Default.Campaign,
    possibleValues = listOf(
        ListSetting.Value(
            value = SttPlaySound.STT_PLAY_SOUND_NOTIFICATION,
            name = stringResource(R.string.gar_voice_sound_notification),
            icon = Icons.Default.Notifications,
        ),
        ListSetting.Value(
            value = SttPlaySound.STT_PLAY_SOUND_ALARM,
            name = stringResource(R.string.gar_voice_sound_alarm),
            icon = Icons.Default.Alarm,
        ),
        ListSetting.Value(
            value = SttPlaySound.STT_PLAY_SOUND_MEDIA,
            name = stringResource(R.string.gar_voice_sound_media),
            icon = Icons.Default.MusicNote,
        ),
        ListSetting.Value(
            value = SttPlaySound.STT_PLAY_SOUND_NONE,
            name = stringResource(R.string.gar_voice_sound_none),
        ),
    ),
)

/** Dicio's speech output method (same values as its `speechOutputDevice()`), as what answers do. */
@Composable
private fun speechOutputSetting() = ListSetting(
    title = stringResource(R.string.gar_voice_answers),
    icon = Icons.Default.SpeakerPhone,
    possibleValues = listOf(
        ListSetting.Value(
            value = SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_ANDROID_TTS,
            name = stringResource(R.string.gar_voice_answers_spoken),
            description = stringResource(R.string.gar_voice_answers_spoken_summary),
            icon = Icons.Default.SpeakerPhone,
        ),
        ListSetting.Value(
            value = SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_TOAST,
            name = stringResource(R.string.gar_voice_answers_popup),
            icon = Icons.Default.BreakfastDining,
        ),
        ListSetting.Value(
            value = SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_SNACKBAR,
            name = stringResource(R.string.gar_voice_answers_bottom),
            icon = Icons.Default.Minimize,
        ),
        ListSetting.Value(
            value = SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_NOTHING,
            name = stringResource(R.string.gar_voice_answers_silent),
            description = stringResource(R.string.gar_voice_answers_silent_summary),
        ),
    ),
)

/**
 * Dicio's `stt_silence_duration` (1 to 7), as what it does: Whisper waits that many 3-second
 * steps for speech to start, Vosk that many of the silences it reports.
 */
@Composable
private fun waitForSpeechSetting(whisper: Boolean): IntSetting {
    val resources = LocalContext.current.resources
    return IntSetting(
        title = stringResource(R.string.gar_voice_wait),
        icon = Icons.Default.HourglassEmpty,
        description = { steps ->
            if (whisper) {
                val seconds = GarSettingsSummaries.whisperWaitSeconds(steps)
                resources.getQuantityString(R.plurals.gar_voice_wait_whisper, seconds, seconds)
            } else {
                resources.getQuantityString(R.plurals.gar_voice_wait_vosk, steps, steps)
            }
        },
        minimum = 1,
        maximum = 7,
    )
}

/**
 * The Whisper rows under "Input method": the model (one on disk; switching downloads the new one
 * first), its status, download and deletion, "Privacy" and "Help recognize contact names". Shown
 * while Whisper is the input method, and otherwise only while a model or a partial download is on
 * the phone, so it can still be deleted.
 */
@Composable
private fun SttModelRows(
    viewModel: GarSettingsViewModel,
    settings: GarSettings,
    formatSize: (Long) -> String,
    whisperChosen: Boolean,
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
    val state = sttState
    if (!whisperChosen && fileSize == null && partialSize == null && state !is GarSttState.Downloading) {
        return
    }

    Column {
        sttModelSetting(formatSize).Render(shown, viewModel::switchSttModel)

        SettingsItem(
            title = stringResource(R.string.gar_stt_status),
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
                    if (partialSize == null) R.string.gar_stt_download
                    else R.string.gar_stt_download_resume
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
    }
}

/**
 * "Voice log" (on by default in debug builds only), "Share voice log", "Clear voice log" (see
 * GarVoiceLog) and the debug-only "Keep recordings".
 */
@Composable
private fun VoiceLogSettings(viewModel: GarSettingsViewModel, settings: GarSettings) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Column {
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

        if (BuildConfig.DEBUG) {
            BooleanSetting(
                title = stringResource(R.string.gar_stt_keep_recordings),
                icon = Icons.Default.RecordVoiceOver,
                descriptionOn = stringResource(R.string.gar_stt_keep_recordings_summary),
                descriptionOff = stringResource(R.string.gar_stt_keep_recordings_summary),
            ).Render(settings.sttKeepRecordings, viewModel::setSttKeepRecordings)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.gar_voice_log_clear_question)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clearVoiceLog()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.gar_voice_log_clear))
                }
            },
            dismissButton = {
                GarbleTextButton(onClick = { confirmClear = false }) {
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
