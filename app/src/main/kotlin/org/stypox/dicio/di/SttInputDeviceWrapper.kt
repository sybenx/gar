package org.stypox.dicio.di

import android.content.Context
import android.os.Trace // GAR
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.datastore.core.DataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted // GAR
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn // GAR
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex // GAR
import kotlinx.coroutines.sync.withLock // GAR
import okhttp3.OkHttpClient
import org.stypox.dicio.R
import org.stypox.dicio.gar.settings.GarSettings // GAR
import org.stypox.dicio.gar.stt.GarSttDeviceResolver // GAR
import org.stypox.dicio.gar.stt.GarWhisperInputDevice // GAR
import org.stypox.dicio.gar.stt.SttModels // GAR
import org.stypox.dicio.gar.stt.WhisperState // GAR
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.input.VoiceActivity // GAR
import org.stypox.dicio.io.input.external_popup.ExternalPopupInputDevice
import org.stypox.dicio.io.input.vosk.VoskInputDevice
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_EXTERNAL_POPUP
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_NOTHING
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_UNSET
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_VOSK
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_WHISPER // GAR
import org.stypox.dicio.settings.datastore.InputDevice.UNRECOGNIZED
import org.stypox.dicio.settings.datastore.SttPlaySound
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.util.distinctUntilChangedBlockingFirst
import org.stypox.dicio.util.toStateFlowDistinctBlockingFirst


interface SttInputDeviceWrapper {
    val uiState: StateFlow<SttState?>

    fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean

    fun stopListening()

    fun onClick(eventListener: (InputEvent) -> Unit)

    fun reinitializeToReleaseResources()

    /** GAR: the current device's [SttInputDevice.voiceActivity]; null when it reports none. */
    val voiceActivity: StateFlow<VoiceActivity?> get() = NO_VOICE_ACTIVITY

    /** GAR: see [SttInputDevice.finishListening]. */
    fun finishListening() = stopListening()

    /**
     * GAR: opens a continued-listening window for [eventListener] (gar/DESIGN.md, "Continued
     * conversation"), with no earcons: speech must start within [windowMillis], and the capture
     * ends after [maxSpeechMillis] of it. False when the device has no such windows (only Whisper
     * has) or isn't idle.
     */
    fun tryListenBriefly(
        eventListener: (InputEvent) -> Unit,
        windowMillis: Long,
        maxSpeechMillis: Long,
    ): Boolean = false

    /** GAR: closes a continued-listening window that still listens; it sends None. */
    fun stopBriefListening() {}

    /** GAR: whether the device has continued-listening windows (Whisper). */
    val hasBriefListening: Boolean get() = false

    /** GAR: when the open window ends (`System.nanoTime` in milliseconds), null when none is. */
    val briefWindowEndsAt: StateFlow<Long?> get() = NO_BRIEF_WINDOW

    companion object {
        private val NO_VOICE_ACTIVITY: StateFlow<VoiceActivity?> = MutableStateFlow(null) // GAR
        private val NO_BRIEF_WINDOW: StateFlow<Long?> = MutableStateFlow(null) // GAR
    }
}

class SttInputDeviceWrapperImpl(
    @param:ApplicationContext private val appContext: Context,
    dataStore: DataStore<UserSettings>,
    private val localeManager: LocaleManager,
    private val okHttpClient: OkHttpClient,
    private val activityForResultManager: ActivityForResultManager,
    // GAR: the Whisper device, and which device UNSET means (design 4.2)
    private val sttModels: SttModels,
    private val sttDeviceResolver: GarSttDeviceResolver,
    garSettings: DataStore<GarSettings>,
) : SttInputDeviceWrapper {
    private val scope = CoroutineScope(Dispatchers.Default)

    private var inputDeviceSetting: InputDevice
    // GAR: the device inputDeviceSetting resolved to when sttInputDevice was built
    private var resolvedInputDevice: InputDevice
    private val changeInputDeviceMutex = Mutex() // GAR: settings, files and reinitialize race
    private val keepSttRecordings: StateFlow<Boolean> = garSettings.data // GAR
        .map { it.sttKeepRecordings }
        .stateIn(scope, SharingStarted.Eagerly, false)
    private var sttPlaySoundSetting: SttPlaySound
    private val silencesBeforeStop: StateFlow<Int>
    private var sttInputDevice: SttInputDevice?

    // null means that the user has not enabled any STT input device
    private val _uiState: MutableStateFlow<SttState?> = MutableStateFlow(null)
    override val uiState: StateFlow<SttState?> = _uiState
    private var uiStateJob: Job? = null

    // GAR: the current device's voice activity, null while it reports none
    private val _voiceActivity: MutableStateFlow<VoiceActivity?> = MutableStateFlow(null)
    override val voiceActivity: StateFlow<VoiceActivity?> = _voiceActivity
    private var voiceActivityJob: Job? = null

    // GAR: the end of Whisper's continued-listening window, null while none is open
    private val _briefWindowEndsAt: MutableStateFlow<Long?> = MutableStateFlow(null)
    override val briefWindowEndsAt: StateFlow<Long?> = _briefWindowEndsAt


    init {
        Trace.beginSection("Gar:SttInputDeviceWrapper.init") // GAR: gar/DESIGN.md, "Frame rate"
        // Run blocking, because the data store is always available right away since LocaleManager
        // also initializes in a blocking way from the same data store.
        val (firstSettings, nextSettingsFlow) = dataStore.data
            .map { Pair(it.inputDevice, it.sttPlaySound) }
            .distinctUntilChangedBlockingFirst()

        inputDeviceSetting = firstSettings.first
        sttPlaySoundSetting = firstSettings.second
        silencesBeforeStop = dataStore.data.map(SttInputDevice::getSttSilenceDurationOrDefault)
            .toStateFlowDistinctBlockingFirst(scope)
        resolvedInputDevice = sttDeviceResolver.resolve(inputDeviceSetting) // GAR
        sttInputDevice = buildInputDevice(resolvedInputDevice)
        scope.launch {
            restartUiStateJob()
        }

        // GAR: UNSET switches between Vosk and Whisper when the locale or the Whisper files change
        scope.launch {
            sttDeviceResolver.changes.collect {
                if (sttDeviceResolver.resolve(inputDeviceSetting) != resolvedInputDevice) {
                    changeInputDeviceTo(inputDeviceSetting)
                }
            }
        }

        scope.launch {
            nextSettingsFlow.collect { (inputDevice, sttPlaySound) ->
                sttPlaySoundSetting = sttPlaySound
                if (inputDeviceSetting != inputDevice) {
                    changeInputDeviceTo(inputDevice)
                }
            }
        }
        Trace.endSection() // GAR
    }

    private suspend fun changeInputDeviceTo(setting: InputDevice) = changeInputDeviceMutex.withLock { // GAR: mutex
        val prevSttInputDevice = sttInputDevice
        inputDeviceSetting = setting
        resolvedInputDevice = sttDeviceResolver.resolve(setting) // GAR
        sttInputDevice = buildInputDevice(resolvedInputDevice)
        prevSttInputDevice?.destroy()
        restartUiStateJob()
    }

    // GAR: takes the resolved device (GarSttDeviceResolver), never UNSET
    private fun buildInputDevice(setting: InputDevice): SttInputDevice? {
        return when (setting) {
            UNRECOGNIZED,
            INPUT_DEVICE_UNSET,
            INPUT_DEVICE_VOSK -> VoskInputDevice(appContext, okHttpClient, localeManager, silencesBeforeStop)
            INPUT_DEVICE_WHISPER -> GarWhisperInputDevice.create( // GAR
                appContext, localeManager.locale, sttModels, silencesBeforeStop, keepSttRecordings
            )
            INPUT_DEVICE_EXTERNAL_POPUP ->
                ExternalPopupInputDevice(appContext, activityForResultManager, localeManager)
            INPUT_DEVICE_NOTHING -> null
        }
    }

    private suspend fun restartUiStateJob() {
        uiStateJob?.cancel()
        val newSttInputDevice = sttInputDevice
        restartVoiceActivityJob(newSttInputDevice) // GAR
        if (newSttInputDevice == null) {
            uiStateJob = null
            _uiState.emit(null)
        } else {
            uiStateJob = scope.launch {
                newSttInputDevice.uiState.collect {
                    _uiState.emit(it)
                    // GAR: no earcon for a continued-listening window
                    if (it == SttState.Listening && !isBriefWindow(newSttInputDevice)) {
                        playSound(R.raw.listening_sound)
                    }
                }
            }
        }
    }

    // GAR: forwards the device's voice activity, and the end of Whisper's window
    private fun restartVoiceActivityJob(device: SttInputDevice?) {
        voiceActivityJob?.cancel()
        _voiceActivity.value = null
        _briefWindowEndsAt.value = null
        val flow = device?.voiceActivity ?: return
        voiceActivityJob = scope.launch {
            launch {
                (device as? GarWhisperInputDevice)?.state?.collect { state ->
                    _briefWindowEndsAt.value =
                        (state as? WhisperState.Listening)?.takeIf { it.brief }?.endsAt
                }
            }
            flow.collect { _voiceActivity.value = it }
        }
    }

    // GAR: whether [device] listens for a continued-listening window right now
    private fun isBriefWindow(device: SttInputDevice): Boolean =
        ((device as? GarWhisperInputDevice)?.state?.value as? WhisperState.Listening)?.brief == true

    private fun playSound(resid: Int) {
        val attributes = AudioAttributes.Builder()
            .setUsage(
                when (sttPlaySoundSetting) {
                    SttPlaySound.UNRECOGNIZED,
                    SttPlaySound.STT_PLAY_SOUND_UNSET,
                    SttPlaySound.STT_PLAY_SOUND_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                    SttPlaySound.STT_PLAY_SOUND_ALARM -> AudioAttributes.USAGE_ALARM
                    SttPlaySound.STT_PLAY_SOUND_MEDIA -> AudioAttributes.USAGE_MEDIA
                    SttPlaySound.STT_PLAY_SOUND_NONE -> return // do not play any sound
                }
            )
            .build()
        val mediaPlayer = MediaPlayer.create(appContext, resid, attributes, 0)
        mediaPlayer.setVolume(0.75f, 0.75f)
        mediaPlayer.start()
    }

    private fun wrapEventListener(eventListener: (InputEvent) -> Unit): (InputEvent) -> Unit = {
        if (it is InputEvent.None) {
            scope.launch {
                playSound(R.raw.listening_no_input_sound)
            }
        }
        eventListener(it)
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        return sttInputDevice?.tryLoad(if (thenStartListeningEventListener != null) {
            wrapEventListener(thenStartListeningEventListener)
        } else { null }) ?: false
    }

    override fun stopListening() {
        sttInputDevice?.stopListening()
    }

    override fun finishListening() { // GAR
        sttInputDevice?.finishListening()
    }

    // GAR: continued listening, Whisper only; None from the window gets no earcon either
    override fun tryListenBriefly(
        eventListener: (InputEvent) -> Unit,
        windowMillis: Long,
        maxSpeechMillis: Long,
    ): Boolean = (sttInputDevice as? GarWhisperInputDevice)
        ?.tryListenBriefly(eventListener, windowMillis, maxSpeechMillis) ?: false

    override fun stopBriefListening() { // GAR
        (sttInputDevice as? GarWhisperInputDevice)?.stopBriefListening()
    }

    override val hasBriefListening: Boolean // GAR
        get() = sttInputDevice is GarWhisperInputDevice

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        sttInputDevice?.onClick(wrapEventListener(eventListener))
    }

    override fun reinitializeToReleaseResources() {
        scope.launch { changeInputDeviceTo(inputDeviceSetting) }
        sttModels.unloadIfIdle() // GAR: frees Whisper unless a capture holds it
    }
}

@Module
@InstallIn(SingletonComponent::class)
class SttInputDeviceWrapperModule {
    @Provides
    @Singleton
    fun provideInputDeviceWrapper(
        @ApplicationContext appContext: Context,
        dataStore: DataStore<UserSettings>,
        localeManager: LocaleManager,
        okHttpClient: OkHttpClient,
        activityForResultManager: ActivityForResultManager,
        sttModels: SttModels, // GAR
        sttDeviceResolver: GarSttDeviceResolver, // GAR
        garSettings: DataStore<GarSettings>, // GAR
    ): SttInputDeviceWrapper {
        return SttInputDeviceWrapperImpl(
            appContext, dataStore, localeManager, okHttpClient, activityForResultManager,
            sttModels, sttDeviceResolver, garSettings, // GAR
        )
    }
}
