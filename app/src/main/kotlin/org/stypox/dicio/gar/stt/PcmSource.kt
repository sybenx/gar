package org.stypox.dicio.gar.stt

/**
 * Where [GarWhisperInputDevice] gets its audio: 16 kHz mono floats in [-1, 1]. The microphone
 * ([AudioRecordPcmSource]) or, in debug builds, a WAV file ([DebugWavPcmSource]).
 *
 * All calls come from the device's capture thread, in order: [start], [read] until the capture
 * ends, [stop] (also after a failed [start]).
 */
interface PcmSource {
    /** Opens the source; throws an [java.io.IOException] when it can't (no mic). */
    fun start()

    /**
     * Fills [frame] with the next samples, waiting for them (32 ms for 512 samples from the mic).
     * Returns how many were written, which can be less than `frame.size` at the end of a file,
     * or -1 once the audio has ended (never for the mic). Throws an [java.io.IOException] when
     * the source fails.
     */
    suspend fun read(frame: FloatArray): Int

    /** Whether the system silences this capture (another app holds the mic). */
    fun isSilenced(): Boolean = false

    /** Stops and frees the source. */
    fun stop()
}
