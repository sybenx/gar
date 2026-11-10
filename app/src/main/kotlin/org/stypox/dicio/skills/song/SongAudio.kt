package org.stypox.dicio.skills.song

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The recording format: 16-bit mono PCM in a WAV file. No encoder is needed and the size stays
 * well below the services' limits (AudD 10 MB, ACRCloud under 5 MB and 15 s): 10 s at 16 kHz is
 * 320 KB, at 44.1 kHz (the fallback rate) 882 KB. Both services fingerprint far below 16 kHz.
 */
object SongWav {
    const val HEADER_BYTES = 44
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1

    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = pcm.size * 2
        val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // size of the fmt chunk
        buffer.putShort(1) // PCM
        buffer.putShort(CHANNELS.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // block align
        buffer.putShort(BITS_PER_SAMPLE.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        for (sample in pcm) {
            buffer.putShort(sample)
        }
        return buffer.array()
    }

    /** The bytes a clip of [seconds] takes at [sampleRate]. */
    fun sizeOf(seconds: Int, sampleRate: Int): Int = HEADER_BYTES + seconds * sampleRate * 2
}

object SongAudio {
    /** How long GAR listens. ACRCloud prefers clips under 15 s, AudD takes up to 25 s. */
    const val CLIP_MILLIS = 10_000L

    /**
     * Peaks below this are digital silence: a silenced or muted microphone gives zeros, a quiet
     * room still has some noise above it.
     */
    const val SILENCE_PEAK = 32

    /** Whether [pcm] is (nearly) all zeros, e.g. because the system silenced the microphone. */
    fun isSilent(pcm: ShortArray): Boolean = pcm.none { abs(it.toInt()) >= SILENCE_PEAK }
}
