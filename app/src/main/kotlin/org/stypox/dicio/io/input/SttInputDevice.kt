package org.stypox.dicio.io.input

import kotlinx.coroutines.flow.StateFlow
import org.stypox.dicio.settings.datastore.UserSettings

interface SttInputDevice {
    val uiState: StateFlow<SttState>

    fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean

    fun stopListening()

    /**
     * GAR: the microphone level and whether speech is heard, while listening; null when the device
     * doesn't report it (Vosk, external popup).
     */
    val voiceActivity: StateFlow<VoiceActivity>? get() = null

    /**
     * GAR: stops listening and recognizes what was heard so far, whereas [stopListening] discards
     * it and sends [InputEvent.None]. Devices that recognize while listening just stop.
     */
    fun finishListening() = stopListening()

    fun onClick(eventListener: (InputEvent) -> Unit)

    suspend fun destroy()

    companion object {
        const val DEFAULT_STT_SILENCE_DURATION = 2
        fun getSttSilenceDurationOrDefault(settings: UserSettings): Int {
            // unfortunately there is no way to tell protobuf to use "2" as the default value
            return settings.sttSilenceDuration.takeIf { it > 0 } ?: DEFAULT_STT_SILENCE_DURATION
        }
    }
}

/**
 * GAR: what the microphone hears while listening.
 *
 * @param level the input level from 0 (silence, -60 dBFS or less) to 1 (-10 dBFS or more), smoothed
 * @param speaking whether speech is heard right now
 */
data class VoiceActivity(
    val level: Float,
    val speaking: Boolean,
)
