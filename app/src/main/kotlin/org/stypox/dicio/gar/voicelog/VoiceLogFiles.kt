package org.stypox.dicio.gar.voicelog

import cc.aaro.gar.stt.WavWriter
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The voice log's files in GAR's private storage (`files/gar-voice-log/`): the JSONL log, rotated
 * at [maxBytes] into `voice-log.1.jsonl` .. `voice-log.<keepFiles - 1>.jsonl` (the higher, the
 * older), and the WAVs of the turns in `wav/`: the last [keepRecent] turns' and every suspect
 * turn's (`<id>.suspect.wav`), at most [keepSuspect] of those and [maxSuspectBytes] in all.
 *
 * Not thread-safe: [GarVoiceLog] calls it from its one background thread only.
 */
class VoiceLogFiles(
    val dir: File,
    private val maxBytes: Long = MAX_BYTES,
    private val keepFiles: Int = KEEP_FILES,
    private val keepRecent: Int = KEEP_RECENT_WAVS,
    private val keepSuspect: Int = KEEP_SUSPECT_WAVS,
    private val maxSuspectBytes: Long = MAX_SUSPECT_WAV_BYTES,
) {
    val current: File get() = File(dir, LOG_NAME)
    val wavDir: File get() = File(dir, WAV_DIR)

    /**
     * Appends [line] (one JSON object, no newline), rotating first if it wouldn't fit. A line
     * left without its newline (a full disk, a crash) is ended first, so that only it is lost.
     */
    fun append(line: String) {
        dir.mkdirs()
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val file = current
        if (file.exists() && file.length() + bytes.size > maxBytes) {
            rotate()
        }
        file.appendBytes(if (endsMidLine(file)) "\n".toByteArray() + bytes else bytes)
    }

    private fun endsMidLine(file: File): Boolean {
        val length = file.length()
        if (length == 0L) {
            return false
        }
        return RandomAccessFile(file, "r").use {
            it.seek(length - 1)
            it.read() != '\n'.code
        }
    }

    private fun rotate() {
        rotated(keepFiles - 1).delete()
        for (i in keepFiles - 2 downTo 1) {
            val from = rotated(i)
            if (from.exists()) {
                from.renameTo(rotated(i + 1))
            }
        }
        if (keepFiles > 1) {
            current.renameTo(rotated(1))
        } else {
            current.delete()
        }
    }

    private fun rotated(index: Int): File = File(dir, "voice-log.$index.jsonl")

    /** The log files, oldest first. */
    fun logFiles(): List<File> =
        (keepFiles - 1 downTo 1).map(::rotated).filter { it.exists() } +
            listOf(current).filter { it.exists() }

    /** Writes [pcm] as `wav/<name>` and drops the WAVs that are no longer kept. */
    fun saveWav(name: String, pcm: FloatArray) {
        val wavs = wavDir
        wavs.mkdirs()
        File(wavs, name).outputStream().buffered().use { WavWriter.write(it, pcm) }
        prune()
    }

    /**
     * Renames `wav/<name>` to its suspect name, so that it is kept past the last [keepRecent];
     * returns the new name, or [name] when there is no such file (no recording, or not yet
     * written: then [saveWav] is never called after this on the same thread).
     */
    fun markSuspect(name: String): String {
        if (name.endsWith(SUSPECT_SUFFIX)) {
            return name
        }
        val newName = name.removeSuffix(".wav") + SUSPECT_SUFFIX
        val from = File(wavDir, name)
        return if (from.exists() && from.renameTo(File(wavDir, newName))) newName else name
    }

    private fun prune() {
        val all = wavDir.listFiles { file -> file.name.endsWith(".wav") } ?: return
        val (suspect, recent) = all.partition { it.name.endsWith(SUSPECT_SUFFIX) }
        // names start with the turn's start time, so they sort by age
        recent.sortedByDescending { it.name }.drop(keepRecent).forEach { it.delete() }
        // the newest suspect ones, while they fit in both the count and the bytes
        var kept = 0
        var bytes = 0L
        var full = false
        for (file in suspect.sortedByDescending { it.name }) {
            val size = file.length()
            if (!full && kept < keepSuspect && bytes + size <= maxSuspectBytes) {
                kept += 1
                bytes += size
            } else {
                full = true
                file.delete()
            }
        }
    }

    /** A zip of the log files and the WAVs, at [target]. */
    fun zipTo(target: File) {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            for (file in logFiles()) {
                zip.putNextEntry(ZipEntry("gar-voice-log/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            wavDir.listFiles { file -> file.name.endsWith(".wav") }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    zip.putNextEntry(ZipEntry("gar-voice-log/$WAV_DIR/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    /** Deletes the logs and the recordings. */
    fun clear() {
        if (dir.exists() && !dir.deleteRecursively()) {
            throw IOException("Could not delete $dir")
        }
    }

    companion object {
        const val DIR_NAME = "gar-voice-log"
        const val LOG_NAME = "voice-log.jsonl"
        const val WAV_DIR = "wav"
        const val SUSPECT_SUFFIX = ".suspect.wav"

        const val MAX_BYTES = 2L * 1024 * 1024
        const val KEEP_FILES = 5
        const val KEEP_RECENT_WAVS = 20
        const val KEEP_SUSPECT_WAVS = 200

        /** 30 MB: about 980 s of 16 kHz 16-bit audio, 35 clips at the 28 s capture cap. */
        const val MAX_SUSPECT_WAV_BYTES = 30L * 1024 * 1024
    }
}
