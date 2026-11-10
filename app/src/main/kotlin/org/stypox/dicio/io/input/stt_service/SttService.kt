package org.stypox.dicio.io.input.stt_service

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope // GAR
import kotlinx.coroutines.Dispatchers // GAR
import kotlinx.coroutines.Job // GAR
import kotlinx.coroutines.SupervisorJob // GAR
import kotlinx.coroutines.cancel // GAR
import kotlinx.coroutines.flow.filterNotNull // GAR
import kotlinx.coroutines.launch // GAR
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttState // GAR
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean // GAR
import javax.inject.Inject


// TODO this class is really simple at the moment, but many more things could be implemented, e.g.:
//  - allowing an SttInputDevice to download/support multiple languages
//  - handling more EXTRAs, e.g. EXTRA_LANGUAGE, EXTRA_LANGUAGE_PREFERENCE,
//  EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, EXTRA_LANGUAGE_MODEL, LANGUAGE_MODEL_FREE_FORM,
//  LANGUAGE_MODEL_WEB_SEARCH, EXTRA_SEGMENTED_SESSION,
//  EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
//  EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
//  EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, EXTRA_AUDIO_SOURCE, EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,
//  EXTRA_AUDIO_SOURCE_ENCODING, EXTRA_AUDIO_SOURCE_SAMPLING_RATE, EXTRA_BIASING_STRINGS,
//  EXTRA_ENABLE_BIASING_DEVICE_CONTEXT
//  - if the SttInputDevice is already busy (e.g. another service is using it, or another part of
//  Dicio is using it), that needs to be reported with ERROR_BUSY
@AndroidEntryPoint
class SttService : RecognitionService() {

    @Inject
    lateinit var sttInputDevice: SttInputDeviceWrapper

    @Inject
    lateinit var localeManager: LocaleManager

    // GAR: reports Whisper's listening progress (ready, speech, level, end) to the caller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val wantedLanguageExtra = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        // "und" is "Undetermined", see https://www.loc.gov/standards/iso639-2/php/code_list.php
        if (wantedLanguageExtra != null && wantedLanguageExtra != "und") {
            val appLanguage = localeManager.locale.value.language
            val wantedLanguage = Locale.forLanguageTag(wantedLanguageExtra).language
            if (appLanguage != wantedLanguage) {
                Log.e(TAG, "Unsupported language: app=$appLanguage wanted=$wantedLanguageExtra")
                // From the javadoc of ERROR_LANGUAGE_UNAVAILABLE: Requested language is supported,
                // but not available currently (e.g. not downloaded yet).
                logRemoteExceptions { listener.error(ERROR_LANGUAGE_UNAVAILABLE) }
                return
            }
        }

        var beginningOfSpeech = true
        // GAR: whether endOfSpeech() is still to be sent; set from the device's thread and main
        val endOfSpeech = AtomicBoolean(true)
        val willStartListening = sttInputDevice.tryLoad { inputEvent ->
            progressJob?.cancel() // GAR
            when (inputEvent) {
                is InputEvent.Error -> {
                    logRemoteExceptions { listener.error(SpeechRecognizer.ERROR_SERVER) }
                }

                is InputEvent.Final -> {
                    if (beginningOfSpeech) {
                        logRemoteExceptions { listener.beginningOfSpeech() }
                        beginningOfSpeech = false
                    }

                    val results = Bundle()
                    results.putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        // GAR: other apps get Whisper's written text, not GAR's spoken form
                        inputEvent.written?.let { arrayListOf(it) }
                            ?: ArrayList(inputEvent.utterances.map { it.first })
                    )
                    results.putFloatArray(
                        SpeechRecognizer.CONFIDENCE_SCORES,
                        inputEvent.utterances.map { it.second }.toFloatArray()
                    )

                    logRemoteExceptions { listener.results(results) }
                    if (endOfSpeech.getAndSet(false)) { // GAR: once
                        logRemoteExceptions { listener.endOfSpeech() }
                    }
                }

                InputEvent.None -> {
                    logRemoteExceptions { listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
                    if (endOfSpeech.getAndSet(false)) { // GAR: once
                        logRemoteExceptions { listener.endOfSpeech() }
                    }
                }

                is InputEvent.Partial -> {
                    if (beginningOfSpeech) {
                        logRemoteExceptions { listener.beginningOfSpeech() }
                        beginningOfSpeech = false
                    }

                    val partResult = Bundle()
                    partResult.putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(inputEvent.utterance)
                    )

                    logRemoteExceptions { listener.partialResults(partResult) }
                }
            }
        }

        if (!willStartListening) {
            Log.w(TAG, "Could not start STT recognizer")
            logRemoteExceptions { listener.error(ERROR_LANGUAGE_UNAVAILABLE) }
            return // GAR
        }

        // GAR: readyForSpeech at capture start, beginningOfSpeech at the first speech,
        // rmsChanged from the level and endOfSpeech once transcribing (Whisper; Vosk sends only
        // readyForSpeech and its partial results)
        progressJob?.cancel()
        progressJob = scope.launch {
            launch {
                var ready = true
                sttInputDevice.uiState.collect { state ->
                    if (state == SttState.Listening && ready) {
                        ready = false
                        logRemoteExceptions { listener.readyForSpeech(Bundle()) }
                    } else if (state == SttState.Transcribing && endOfSpeech.getAndSet(false)) {
                        logRemoteExceptions { listener.endOfSpeech() }
                    }
                }
            }
            sttInputDevice.voiceActivity.filterNotNull().collect { activity ->
                if (activity.speaking && beginningOfSpeech) {
                    beginningOfSpeech = false
                    logRemoteExceptions { listener.beginningOfSpeech() }
                }
                logRemoteExceptions { listener.rmsChanged(-2f + 12f * activity.level) }
            }
        }
    }

    override fun onCancel(listener: Callback) {
        progressJob?.cancel() // GAR
        sttInputDevice.stopListening()
    }

    override fun onStopListening(listener: Callback) {
        // GAR: "stop listening" asks for the result of what was said (Whisper transcribes it);
        // cancelling discards it
        sttInputDevice.finishListening()
    }

    override fun onDestroy() { // GAR
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        val TAG = SttService::class.simpleName

        /**
         * From the javadoc of [SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE]: Requested language is
         * supported, but not available currently (e.g. not downloaded yet).
         */
        val ERROR_LANGUAGE_UNAVAILABLE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        } else {
            SpeechRecognizer.ERROR_SERVER
        }

        fun logRemoteExceptions(f: () -> Unit) {
            try {
                return f()
            } catch (e: RemoteException) {
                Log.e(TAG, "Remote exception", e)
            }
        }
    }
}
