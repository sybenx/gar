package cc.aaro.gar.stt

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the WAV files GAR feeds Whisper in tests and debug builds: 16 kHz mono, 16-bit PCM or
 * 32-bit float, as `say --data-format=LEI16@16000` and [WavWriter] make them. Anything else
 * throws an [IOException]: resampling is not this class's job.
 */
object WavReader {
    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    /** Reads the whole file into floats in [-1, 1]. Does not close [input]. */
    fun read(input: InputStream, sampleRate: Int = AudioCtx.SAMPLE_RATE): FloatArray {
        val data = DataInputStream(input)
        try {
            if (data.fourCc() != "RIFF") throw IOException("Not a RIFF file")
            data.int32()
            if (data.fourCc() != "WAVE") throw IOException("Not a WAVE file")

            var format = -1
            var bitsPerSample = 0
            while (true) {
                val id = data.fourCc()
                val size = data.int32().toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        if (size < 16) throw IOException("fmt chunk too short: $size")
                        format = data.int16()
                        val channels = data.int16()
                        val rate = data.int32()
                        data.int32() // byte rate
                        data.int16() // block align
                        bitsPerSample = data.int16()
                        var rest = size - 16
                        if (format == FORMAT_EXTENSIBLE && size >= 40) {
                            data.int16() // extension size
                            data.int16() // valid bits
                            data.int32() // channel mask
                            format = data.int16() // first two bytes of the sub-format GUID
                            rest -= 10
                        }
                        data.skipFully(rest + (size and 1))
                        if (channels != 1) throw IOException("Not mono: $channels channels")
                        if (rate != sampleRate) throw IOException("Not $sampleRate Hz: $rate Hz")
                    }
                    "data" -> {
                        if (format < 0) throw IOException("data chunk before fmt chunk")
                        return decode(data, size, format, bitsPerSample)
                    }
                    else -> data.skipFully(size + (size and 1))
                }
            }
        } catch (e: EOFException) {
            throw IOException("Truncated WAV file", e)
        }
    }

    private fun decode(data: DataInputStream, size: Long, format: Int, bits: Int): FloatArray {
        val bytesPerSample = when {
            format == FORMAT_PCM && bits == 16 -> 2
            format == FORMAT_FLOAT && bits == 32 -> 4
            else -> throw IOException("Unsupported WAV format $format with $bits bits")
        }
        // some writers leave the size at 0 or 0xFFFFFFFF when streaming: read to the end then
        val bytes = if (size == 0L || size == 0xFFFFFFFFL) {
            data.readBytes()
        } else {
            if (size > Int.MAX_VALUE) throw IOException("WAV data too large: $size bytes")
            ByteArray(size.toInt()).also { data.readFully(it) }
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / bytesPerSample) {
            if (bytesPerSample == 2) buffer.getShort() / 32768f else buffer.getFloat()
        }
    }

    private fun DataInputStream.fourCc(): String {
        val bytes = ByteArray(4)
        readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun DataInputStream.int16(): Int =
        (readUnsignedByte() or (readUnsignedByte() shl 8))

    private fun DataInputStream.int32(): Int =
        readUnsignedByte() or (readUnsignedByte() shl 8) or
            (readUnsignedByte() shl 16) or (readUnsignedByte() shl 24)

    private fun DataInputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                read().takeIf { it >= 0 } ?: throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }
}

/** Writes 16 kHz mono 16-bit PCM WAV files, which [WavReader] reads back. */
object WavWriter {
    /** Writes the samples `[from, to)` of [pcm], clamped to [-1, 1]. Does not close [output]. */
    fun write(
        output: OutputStream,
        pcm: FloatArray,
        from: Int = 0,
        to: Int = pcm.size,
        sampleRate: Int = AudioCtx.SAMPLE_RATE,
    ) {
        require(from in 0..to && to <= pcm.size) { "Bad range [$from, $to) of ${pcm.size}" }
        val dataBytes = (to - from) * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        output.write(header.array())

        val data = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from until to) {
            val sample = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
            data.putShort(sample.toShort())
        }
        output.write(data.array())
    }

    /** [write] into a byte array. */
    fun toBytes(pcm: FloatArray, from: Int = 0, to: Int = pcm.size): ByteArray =
        ByteArrayOutputStream().also { write(it, pcm, from, to) }.toByteArray()
}
