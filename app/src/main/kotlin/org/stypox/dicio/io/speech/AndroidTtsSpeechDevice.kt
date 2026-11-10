package org.stypox.dicio.io.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import org.dicio.skill.context.SpeechOutputDevice
import org.stypox.dicio.R
import org.stypox.dicio.gar.conversation.GarUtteranceTracker // GAR
import java.util.Locale

class AndroidTtsSpeechDevice(private var context: Context, locale: Locale) : SpeechOutputDevice {
    private var textToSpeech: TextToSpeech? = null
    private var initializedCorrectly = false
    // GAR: the queued utterances and what waits for them (replaces Dicio's runnablesWhenFinished):
    // runs after the last one is done, failed or stopped, never while one is still queued
    private val utterances = GarUtteranceTracker()
    private var lastUtteranceId = 0

    init {
        textToSpeech = TextToSpeech(context) { status: Int ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.run {
                    val errorCode = setLanguage(locale)
                    if (errorCode >= 0) { // errors are -1 or -2
                        initializedCorrectly = true
                        setOnUtteranceProgressListener(object :
                            UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) {
                                // GAR: the voice log's first spoken word
                                org.stypox.dicio.gar.voicelog.GarVoiceLog.firstWord()
                            }
                            override fun onDone(utteranceId: String) {
                                // GAR: runs once the last enqueued utterance is finished
                                utterances.finished(utteranceId)
                            }

                            @Suppress("OVERRIDE_DEPRECATION")
                            @Deprecated("")
                            override fun onError(utteranceId: String) {
                                utterances.finished(utteranceId) // GAR: a question still listens
                            }

                            override fun onStop(utteranceId: String, interrupted: Boolean) {
                                utterances.finished(utteranceId) // GAR
                            }
                        })
                    } else {
                        Log.e(TAG, "Unsupported language: $errorCode")
                        handleInitializationError(R.string.android_tts_unsupported_language)
                    }
                }
            } else {
                Log.e(TAG, "TTS error: $status")
                handleInitializationError(R.string.android_tts_error)
            }
        }
    }

    override fun speak(speechOutput: String) {
        if (initializedCorrectly) {
            lastUtteranceId += 1
            utterances.queued("dicio_$lastUtteranceId") // GAR
            val result = textToSpeech?.speak(
                speechOutput, TextToSpeech.QUEUE_ADD, null,
                "dicio_$lastUtteranceId"
            )
            if (result != TextToSpeech.SUCCESS) {
                utterances.finished("dicio_$lastUtteranceId") // GAR: never queued
            }
        } else {
            Toast.makeText(context, speechOutput, Toast.LENGTH_LONG).show()
        }
    }

    override fun stopSpeaking() {
        utterances.stopped() // GAR: before stop(), whose onStop must not run what waited
        textToSpeech?.stop()
    }

    override val isSpeaking: Boolean
        get() = textToSpeech?.isSpeaking == true || utterances.pending // GAR: or one is queued

    override fun runWhenFinishedSpeaking(runnable: Runnable) {
        utterances.runWhenFinished(isSpeaking, runnable) // GAR: also while one is queued
    }

    override fun cleanup() {
        utterances.stopped() // GAR: a shut down engine finishes none of them (GarSpeaking)
        textToSpeech?.apply {
            shutdown()
            textToSpeech = null
        }
    }

    private fun handleInitializationError(@StringRes errorString: Int) {
        Toast.makeText(context, errorString, Toast.LENGTH_SHORT).show()
        cleanup()
    }

    companion object {
        val TAG: String = AndroidTtsSpeechDevice::class.simpleName!!
    }
}
