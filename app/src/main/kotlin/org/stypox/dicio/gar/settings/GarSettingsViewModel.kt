package org.stypox.dicio.gar.settings

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.dicio.gar.GarKeepScreenOn
import org.stypox.dicio.gar.model.GarModelManager
import org.stypox.dicio.gar.model.GarModelState
import org.stypox.dicio.gar.model.ModelCatalog
import org.stypox.dicio.gar.stt.GarSttModelManager
import org.stypox.dicio.gar.stt.GarSttState
import org.stypox.dicio.gar.voicelog.GarVoiceLog
import org.stypox.dicio.skills.sms.AndroidOwnContact
import org.stypox.dicio.util.toStateFlowDistinctBlockingFirst
import javax.inject.Inject

@HiltViewModel
class GarSettingsViewModel @Inject constructor(
    application: Application,
    private val dataStore: DataStore<GarSettings>,
    private val modelManager: GarModelManager,
    private val sttModels: GarSttModelManager,
) : AndroidViewModel(application) {
    // run blocking like Dicio's settings screens, so the screen starts with the real values
    val settingsState: StateFlow<GarSettings> = dataStore.data
        .toStateFlowDistinctBlockingFirst(viewModelScope)

    val modelState: StateFlow<GarModelState?> = modelManager.modelState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val filesChanged: StateFlow<Int> = modelManager.filesChanged

    val sttState: StateFlow<GarSttState?> = sttModels.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sttFilesChanged: StateFlow<Int> = sttModels.filesChanged

    /** The speech model a running or failed download is for, see [GarSttModelManager.switchTo]. */
    val sttTransferModel: StateFlow<GarSttModel?> = sttModels.transferModel

    private fun updateData(transform: (GarSettings.Builder) -> Unit) {
        viewModelScope.launch {
            dataStore.updateData {
                it.toBuilder()
                    .apply(transform)
                    .build()
            }
        }
    }

    fun setLlmEnabled(value: Boolean) =
        updateData { it.setLlmEnabled(value) }
    fun setModel(value: GarModel) =
        updateData { it.setModel(value) }
    fun setConfirmLlmActions(value: Boolean) =
        updateData { it.setConfirmLlmActions(value) }
    fun acceptFunctionGemmaTerms() =
        updateData { it.setFunctionGemmaTermsAccepted(true) }
    fun setKeepScreenOn(value: Boolean) =
        updateData { it.setScreenMaySleep(!value) }
    fun setSttKeepRecordings(value: Boolean) =
        updateData { it.setSttKeepRecordings(value) }
    fun setVoiceLog(value: Boolean) =
        updateData { it.setVoiceLogOff(!value) }
    fun setContinuedListening(value: Boolean) =
        updateData { it.setContinuedListeningOff(!value) }
    fun setSpeakTypedAnswers(value: Boolean) =
        updateData { it.setSpeakTypedAnswers(value) }
    fun setAssistFullScreen(value: Boolean) =
        updateData { it.setAssistFullScreen(value) }

    /** Zips the voice log and opens the share sheet. */
    fun shareVoiceLog() = GarVoiceLog.share(getApplication<Application>())

    /** Deletes the voice log and its recordings. */
    fun clearVoiceLog() = GarVoiceLog.clear(getApplication<Application>())
    fun setSttContactNames(value: Boolean) = updateData {
        it.setSttContactNames(
            if (value) GarSttContactNames.GAR_STT_CONTACT_NAMES_ON
            else GarSttContactNames.GAR_STT_CONTACT_NAMES_OFF
        )
    }

    private val ownContact = AndroidOwnContact(application, dataStore)

    private val youChecks = MutableStateFlow(0)

    /**
     * Whether the phone is locked, as in [org.stypox.dicio.gar.stt.GarSttOwnNameViewModel.locked]:
     * a wake word opens GAR over the lock screen, and "You" lists the contacts. Read again at
     * every unlock and at [checkYou]; null until read.
     */
    val locked: StateFlow<Boolean?> =
        combine(GarKeepScreenOn.unlocks(application), youChecks) { _, _ ->
            application.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Who GAR thinks the user is, for the "You" setting's summary: read again when the settings
     * change, at unlocks and at [checkYou] (contacts access granted, the screen shown again);
     * null until read. No card is read while [locked].
     */
    val you: StateFlow<You?> = combine(dataStore.data, locked, youChecks) { settings, locked, _ ->
        val hidden = locked != false
        val access = ContextCompat.checkSelfPermission(application, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val read = access && !hidden
        GarYou.who(
            settings = settings,
            card = settings.ownContactName.takeIf { read && it.isNotEmpty() }?.let(ownContact::card),
            profileName = ownContact.profileName(),
            profile = if (read) ownContact.profile() else null,
            access = access,
            locked = hidden,
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _youContacts = MutableStateFlow<List<YouContact>?>(null)

    /** The contacts the "You" dialog lists, null until [loadYouContacts] read them. */
    val youContacts: StateFlow<List<YouContact>?> = _youContacts

    init {
        // the contacts are not left for the lock screen
        viewModelScope.launch {
            locked.collect { if (it != false) _youContacts.value = null }
        }
    }

    fun checkYou() {
        youChecks.value += 1
    }

    /** Reads the contacts for the "You" dialog (empty without `READ_CONTACTS`), not when locked. */
    fun loadYouContacts() {
        viewModelScope.launch {
            val contacts = withContext(Dispatchers.IO) { ownContact.youContacts() }
            if (locked.value == false) {
                _youContacts.value = contacts
            }
        }
    }

    /** Sets who the user is, as the home screen's card and "Which contact is you?" do. */
    fun setYou(choice: YouChoice) {
        viewModelScope.launch { GarYou.set(dataStore, choice) }
    }

    /** Downloads [model] if needed, then makes it the speech model (one model on disk). */
    fun switchSttModel(model: GarSttModel) = sttModels.switchTo(model)

    fun cancelSttDownload() = sttModels.cancelDownload()

    fun deleteStt() = sttModels.delete()

    fun retrySttLoad() = sttModels.retryLoadInBackground()

    fun sttFileSize(model: GarSttModel): Long? = sttModels.fileSize(model)

    fun sttPartialDownloadSize(model: GarSttModel): Long? = sttModels.partialDownloadSize(model)

    fun download(model: GarModel) {
        ModelCatalog.forSetting(model)?.let { modelManager.download(it) }
    }

    fun cancelDownload() = modelManager.cancelDownload()

    fun delete(model: GarModel) = modelManager.delete(model)

    fun importCustom(uri: Uri, family: GarModelFamily) = modelManager.importCustom(uri, family)

    fun fileSize(model: GarModel): Long? = modelManager.fileSize(model)

    fun partialDownloadSize(model: GarModel): Long? = modelManager.partialDownloadSize(model)

    fun retryLoad() = modelManager.retryLoad()

    /** The license texts GAR ships for its native code, by name. */
    fun licenses(): List<Pair<String, String>> = LICENSES.mapNotNull { (name, asset) ->
        try {
            Pair(name, getApplication<Application>().assets.open(asset).bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val LICENSES = listOf(
            "llama.cpp" to "gar-licenses/llama.cpp.txt",
            "whisper.cpp" to "gar-licenses/whisper.cpp.txt",
            "OpenAI Whisper" to "gar-licenses/openai-whisper.txt",
            "Whisper ACFT (FUTO)" to "gar-licenses/whisper-acft.txt",
            "Silero VAD" to "gar-licenses/silero-vad.txt",
        )
    }
}
