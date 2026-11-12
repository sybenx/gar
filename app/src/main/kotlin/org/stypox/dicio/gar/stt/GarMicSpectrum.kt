package org.stypox.dicio.gar.stt

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 5 coarse speech bands of a 16 kHz mono frame, each 0 (-60 dBFS) to 1 (-10 dBFS) like the level:
 * a Hann window and a radix-2 FFT of the frame (zero padded to a power of two, at most
 * [MAX_FFT]). Not thread safe: one instance per capture thread. Only reads the frame.
 */
class GarMicSpectrum(private val sampleRate: Int = 16_000) {
    private var n = 0
    private var re = FloatArray(0)
    private var im = FloatArray(0)
    private var window = FloatArray(0)
    private var windowCount = 0
    private var cosTable = FloatArray(0)
    private var sinTable = FloatArray(0)

    /** Writes the bands of the first [samples] of [frame] into [out] (size [BANDS]). */
    fun bands(frame: FloatArray, samples: Int, out: FloatArray) {
        require(out.size == BANDS)
        val count = samples.coerceIn(0, minOf(frame.size, MAX_FFT))
        if (count < 2) {
            out.fill(0f)
            return
        }
        prepare(Integer.highestOneBit(count - 1) shl 1)
        if (windowCount != count) {
            for (i in 0 until count) {
                window[i] = (0.5 - 0.5 * cos(2 * PI * i / (count - 1))).toFloat()
            }
            windowCount = count
        }
        for (i in 0 until n) {
            re[i] = if (i < count) frame[i] * window[i] else 0f
            im[i] = 0f
        }
        fft()
        val binHz = sampleRate.toFloat() / n
        for (b in 0 until BANDS) {
            val lo = (EDGES_HZ[b] / binHz).toInt().coerceAtLeast(1)
            val hi = (EDGES_HZ[b + 1] / binHz).toInt().coerceAtMost(n / 2)
            var power = 0.0
            for (k in lo until hi) {
                power += re[k] * re[k] + im[k] * im[k]
            }
            // a sine of amplitude A peaks at A*count/4 under Hann (coherent gain 0.5) and spreads
            // 1.5x its peak power (equivalent noise bandwidth): back to the band's RMS
            val amplitude = 4.0 * sqrt(power / 1.5) / count
            val rms = amplitude / sqrt(2.0)
            out[b] = if (rms <= 0.0) {
                0f
            } else {
                ((20 * log10(rms) - MIN_DB) / (MAX_DB - MIN_DB)).toFloat().coerceIn(0f, 1f)
            }
        }
    }

    private fun prepare(size: Int) {
        if (size == n) {
            return
        }
        n = size
        re = FloatArray(n)
        im = FloatArray(n)
        window = FloatArray(n)
        windowCount = 0
        cosTable = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
        sinTable = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    }

    /** In place, iterative Cooley-Tukey. */
    private fun fft() {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val c = cosTable[k * step]
                    val s = -sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * c - im[b] * s
                    val xi = re[b] * s + im[b] * c
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val BANDS = 5
        const val MAX_FFT = 1024

        /** Band edges: low voice, first formant, second formant, presence, sibilance. */
        val EDGES_HZ = floatArrayOf(80f, 300f, 700f, 1_500f, 3_000f, 6_000f)

        private const val MIN_DB = -60.0
        private const val MAX_DB = -10.0
    }
}
