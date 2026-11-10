package org.stypox.dicio.skills.song

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What recording a clip gave. */
sealed interface RecordingResult {
    class Recorded(val pcm: ShortArray, val sampleRate: Int) : RecordingResult

    enum class Failure : RecordingResult {
        NO_PERMISSION,

        /** The microphone couldn't be opened, or another app holds it. */
        MIC_UNAVAILABLE,

        /** Android gave the app silence, e.g. it isn't in the foreground (see the DESIGN). */
        MIC_SILENCED,
    }
}

/** Records a clip of the sound around the phone. */
fun interface SongRecorder {
    /** @param onProgress called with 0..1 while recording */
    suspend fun record(millis: Long, onProgress: (Float) -> Unit): RecordingResult
}

/**
 * Records with [AudioRecord] right after the command: Dicio's speech recognition has stopped its
 * own AudioRecord before the evaluator gets the input, and nothing plays while GAR listens. Uses
 * the VOICE_RECOGNITION source (see [SOURCE]) and 16 kHz mono where the device records it, else
 * 44.1 kHz (the rate every device supports).
 */
class AndroidSongRecorder(private val context: Context) : SongRecorder {

    override suspend fun record(millis: Long, onProgress: (Float) -> Unit): RecordingResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return RecordingResult.Failure.NO_PERMISSION
        }
        return withContext(Dispatchers.IO) { recordGranted(millis, onProgress) }
    }

    @SuppressLint("MissingPermission") // checked in record()
    private suspend fun recordGranted(millis: Long, onProgress: (Float) -> Unit): RecordingResult {
        val sampleRate = SAMPLE_RATES.firstOrNull { rate ->
            AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING) > 0
        } ?: return RecordingResult.Failure.MIC_UNAVAILABLE
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)

        val record = try {
            AudioRecord(SOURCE, sampleRate, CHANNEL, ENCODING, maxOf(minBuffer, sampleRate))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Can't create the AudioRecord", e)
            return RecordingResult.Failure.MIC_UNAVAILABLE
        } catch (e: SecurityException) {
            return RecordingResult.Failure.NO_PERMISSION
        }

        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return RecordingResult.Failure.MIC_UNAVAILABLE
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return RecordingResult.Failure.MIC_UNAVAILABLE
            }

            val pcm = ShortArray((sampleRate * millis / 1000).toInt())
            val chunk = sampleRate / 10 // 100 ms
            var read = 0
            var silencedChecked = false
            while (read < pcm.size) {
                currentCoroutineContext().ensureActive()
                val n = record.read(pcm, read, minOf(chunk, pcm.size - read))
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read failed: $n")
                    return RecordingResult.Failure.MIC_UNAVAILABLE
                }
                read += n
                onProgress(read.toFloat() / pcm.size)

                // the system silences a client that may not record now; ask once it has started
                if (!silencedChecked && read >= sampleRate / 2) {
                    silencedChecked = true
                    if (isSilenced(record)) {
                        return RecordingResult.Failure.MIC_SILENCED
                    }
                }
            }
            if (isSilenced(record)) {
                return RecordingResult.Failure.MIC_SILENCED
            }
            return RecordingResult.Recorded(pcm, sampleRate)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Recording failed", e)
            return RecordingResult.Failure.MIC_UNAVAILABLE
        } finally {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                // never started
            }
            record.release()
        }
    }

    private fun isSilenced(record: AudioRecord): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            record.activeRecordingConfiguration?.isClientSilenced == true

    companion object {
        private const val TAG = "AndroidSongRecorder"
        private val SAMPLE_RATES = listOf(16_000, 44_100)

        /**
         * The CDD has no noise suppression or gain control on it, which fingerprinting wants, and
         * it is the source of Dicio's own capture (Vosk, the wake word service, which keeps
         * listening meanwhile): a second capture from the same source in the same app is shared
         * by the audio HAL, where another source (UNPROCESSED) could fail or silence one of them.
         */
        private const val SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
