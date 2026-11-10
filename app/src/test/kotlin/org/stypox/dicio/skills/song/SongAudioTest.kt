package org.stypox.dicio.skills.song

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SongAudioTest : StringSpec({
    "the WAV header describes 16-bit mono PCM" {
        val pcm = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE)
        val wav = SongWav.encode(pcm, 16_000)
        wav.size shouldBe SongWav.HEADER_BYTES + pcm.size * 2

        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(wav, offset, 4, Charsets.US_ASCII)
        tag(0) shouldBe "RIFF"
        b.getInt(4) shouldBe wav.size - 8
        tag(8) shouldBe "WAVE"
        tag(12) shouldBe "fmt "
        b.getInt(16) shouldBe 16 // fmt chunk size
        b.getShort(20) shouldBe 1.toShort() // PCM
        b.getShort(22) shouldBe 1.toShort() // mono
        b.getInt(24) shouldBe 16_000 // sample rate
        b.getInt(28) shouldBe 32_000 // byte rate
        b.getShort(32) shouldBe 2.toShort() // block align
        b.getShort(34) shouldBe 16.toShort() // bits per sample
        tag(36) shouldBe "data"
        b.getInt(40) shouldBe pcm.size * 2

        // the samples, little-endian
        (0 until pcm.size).map { b.getShort(44 + 2 * it) } shouldBe pcm.toList()
        wav.copyOfRange(48, 50).toList() shouldBe listOf(0xFF.toByte(), 0xFF.toByte()) // -1
        wav.copyOfRange(50, 52).toList() shouldBe listOf(0xFF.toByte(), 0x7F.toByte()) // max
        wav.copyOfRange(52, 54).toList() shouldBe listOf(0x00.toByte(), 0x80.toByte()) // min
    }

    "a clip fits both services' limits" {
        SongAudio.CLIP_MILLIS shouldBeLessThan 15_000L // ACRCloud: under 15 s
        for (rate in listOf(16_000, 44_100)) {
            val bytes = SongWav.sizeOf((SongAudio.CLIP_MILLIS / 1000).toInt(), rate)
            bytes shouldBeLessThan 5_000_000 // ACRCloud: under 5 MB (AudD: 10 MB)
            SongWav.encode(ShortArray(rate * 10), rate).size shouldBe bytes
        }
        SongWav.sizeOf(10, 16_000) shouldBe 320_044
    }

    "digital silence isn't sent, quiet sound is" {
        SongAudio.isSilent(ShortArray(16_000)) shouldBe true
        SongAudio.isSilent(ShortArray(16_000) { if (it % 2 == 0) 5 else -5 }) shouldBe true
        SongAudio.isSilent(ShortArray(16_000) { if (it == 8_000) 40 else 0 }) shouldBe false
        SongAudio.isSilent(ShortArray(16_000) { (it % 200 - 100).toShort() }) shouldBe false
    }
})
