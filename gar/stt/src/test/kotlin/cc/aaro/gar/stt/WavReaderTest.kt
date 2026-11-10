package cc.aaro.gar.stt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** A WAV file with the given fmt fields, an optional extra chunk and [data] as the data chunk. */
private fun wav(
    format: Int = 1,
    channels: Int = 1,
    rate: Int = 16_000,
    bits: Int = 16,
    extraChunk: Boolean = false,
    data: ByteArray,
): ByteArray {
    val extra = if (extraChunk) 8 + 5 + 1 else 0 // odd size, padded
    val buffer = ByteBuffer.allocate(44 + extra + data.size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + extra + data.size).put("WAVE".toByteArray())
    buffer.put("fmt ".toByteArray()).putInt(16)
    buffer.putShort(format.toShort()).putShort(channels.toShort()).putInt(rate)
    buffer.putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort())
    buffer.putShort(bits.toShort())
    if (extraChunk) {
        buffer.put("LIST".toByteArray()).putInt(5).put(ByteArray(6))
    }
    buffer.put("data".toByteArray()).putInt(data.size).put(data)
    return buffer.array()
}

private fun pcm16(vararg samples: Int): ByteArray {
    val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { buffer.putShort(it.toShort()) }
    return buffer.array()
}

private fun float32(vararg samples: Float): ByteArray {
    val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { buffer.putFloat(it) }
    return buffer.array()
}

private fun read(bytes: ByteArray) = WavReader.read(ByteArrayInputStream(bytes))

class WavReaderTest : FunSpec({
    test("reads 16-bit PCM as floats") {
        read(wav(data = pcm16(0, 16_384, -32_768, 32_767))).toList() shouldBe
            listOf(0f, 0.5f, -1f, 32_767 / 32_768f)
    }

    test("reads 32-bit float") {
        read(wav(format = 3, bits = 32, data = float32(0.25f, -0.75f))).toList() shouldBe
            listOf(0.25f, -0.75f)
    }

    test("skips other chunks, odd sizes padded") {
        read(wav(extraChunk = true, data = pcm16(100, -100))).size shouldBe 2
    }

    test("rejects what it can't feed Whisper") {
        shouldThrow<IOException> { read(wav(channels = 2, data = pcm16(1, 2))) }
        shouldThrow<IOException> { read(wav(rate = 44_100, data = pcm16(1))) }
        shouldThrow<IOException> { read(wav(bits = 8, data = ByteArray(4))) }
        shouldThrow<IOException> { read("RIFX....".toByteArray()) }
        shouldThrow<IOException> { read(wav(data = pcm16(1, 2)).copyOf(30)) }
    }

    test("what WavWriter writes reads back") {
        val pcm = FloatArray(1_000) { ((it % 50) - 25) / 25f }
        val bytes = WavWriter.toBytes(pcm, from = 100, to = 900)
        bytes.size shouldBe 44 + 800 * 2
        val back = read(bytes)
        back.size shouldBe 800
        back.indices.all { abs(back[it] - pcm[it + 100]) < 1e-4f } shouldBe true
    }

    test("the writer clamps out-of-range samples") {
        read(WavWriter.toBytes(floatArrayOf(2f, -2f))).toList() shouldBe
            listOf(32_767 / 32_768f, -32_767 / 32_768f)
    }
})
