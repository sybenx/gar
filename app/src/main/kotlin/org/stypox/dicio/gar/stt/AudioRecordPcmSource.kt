package org.stypox.dicio.gar.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import cc.aaro.gar.stt.AudioCtx
import java.io.IOException
import kotlin.math.max

/**
 * The microphone, as [PcmSource]: `VOICE_RECOGNITION` like Vosk, the wake word and the song
 * skill (the audio HAL shares one source within the app), 16 kHz mono float, or 16-bit PCM where
 * float can't be opened. Raises the capture thread to audio priority at [start].
 */
class AudioRecordPcmSource : PcmSource {
    private var record: AudioRecord? = null
    private var shorts: ShortArray? = null

    override fun start() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val opened = open(AudioFormat.ENCODING_PCM_FLOAT)
            ?: open(AudioFormat.ENCODING_PCM_16BIT)?.also { shorts = ShortArray(0) }
            ?: throw IOException("Could not open the microphone")
        record = opened
        try {
            opened.startRecording()
        } catch (e: IllegalStateException) {
            throw IOException("Could not start recording", e)
        }
        if (opened.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IOException("The microphone did not start recording")
        }
    }

    @SuppressLint("MissingPermission") // the UI asks for it before any capture
    private fun open(encoding: Int): AudioRecord? {
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, encoding)
        if (minBytes <= 0) {
            return null
        }
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val bufferBytes = max(minBytes, SAMPLE_RATE * bytesPerSample / 2) // half a second
        val record = try {
            AudioRecord(SOURCE, SAMPLE_RATE, CHANNEL, encoding, bufferBytes)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "AudioRecord rejected encoding $encoding", e)
            return null
        } catch (e: SecurityException) {
            throw IOException("No permission to record audio", e)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record
    }

    override suspend fun read(frame: FloatArray): Int {
        val record = record ?: throw IOException("Not recording")
        val read = shorts.let { buffer ->
            if (buffer == null) {
                record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
            } else {
                val pcm = if (buffer.size >= frame.size) buffer else ShortArray(frame.size)
                    .also { shorts = it }
                val n = record.read(pcm, 0, frame.size, AudioRecord.READ_BLOCKING)
                for (i in 0 until n) {
                    frame[i] = pcm[i] / 32768f
                }
                n
            }
        }
        if (read < 0) {
            throw IOException("Could not read from the microphone: $read")
        }
        return read
    }

    override fun isSilenced(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            record?.activeRecordingConfiguration?.isClientSilenced == true

    override fun stop() {
        val record = record ?: return
        this.record = null
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            // never started
        }
        record.release()
    }

    companion object {
        private const val TAG = "GarStt"
        private const val SAMPLE_RATE = AudioCtx.SAMPLE_RATE
        private const val SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    }
}
