package org.stypox.dicio.gar.stt

import android.os.SystemClock
import cc.aaro.gar.stt.AudioCtx
import cc.aaro.gar.stt.WavReader
import kotlinx.coroutines.delay
import java.io.File

/**
 * Debug builds only: plays `filesDir/gar-stt-test/next.wav` (16 kHz mono, 16-bit PCM or float)
 * into the Whisper device instead of the microphone, at real-time pace, then [TAIL_SECONDS] of
 * silence so the end-pointer sees the speech end. The file is deleted when the capture starts, so
 * the next capture uses the mic again. For the emulator checks of design section 12:
 *
 * ```
 * adb exec-in run-as cc.aaro.gar.gar sh -c \
 *   'mkdir -p files/gar-stt-test && cat > files/gar-stt-test/next.wav' < cmd.wav
 * ```
 */
class DebugWavPcmSource(
    private val file: File,
    private val nanoTime: () -> Long = SystemClock::elapsedRealtimeNanos,
) : PcmSource {
    private var pcm = FloatArray(0)
    private var position = 0
    private var total = 0
    private var startNanos = 0L

    override fun start() {
        try {
            pcm = file.inputStream().buffered().use { WavReader.read(it) }
        } finally {
            file.delete()
        }
        total = pcm.size + TAIL_SECONDS * AudioCtx.SAMPLE_RATE
        position = 0
        startNanos = nanoTime()
    }

    override suspend fun read(frame: FloatArray): Int {
        if (position >= total) {
            return -1
        }
        val n = minOf(frame.size, total - position)
        for (i in 0 until n) {
            val at = position + i
            frame[i] = if (at < pcm.size) pcm[at] else 0f
        }
        position += n

        // real-time pace, without drift: wait until these samples would have been recorded
        val dueNanos = startNanos + position * 1_000_000_000L / AudioCtx.SAMPLE_RATE
        val waitMillis = (dueNanos - nanoTime()) / 1_000_000L
        if (waitMillis > 0) {
            delay(waitMillis)
        }
        return n
    }

    override fun stop() {
        pcm = FloatArray(0)
    }

    companion object {
        const val TAIL_SECONDS = 3

        fun nextFile(filesDir: File) = File(filesDir, "gar-stt-test/next.wav")

        /** The source for the waiting file, or null when there is none. */
        fun takeNext(filesDir: File): DebugWavPcmSource? =
            nextFile(filesDir).takeIf { it.isFile }?.let { DebugWavPcmSource(it) }
    }
}
