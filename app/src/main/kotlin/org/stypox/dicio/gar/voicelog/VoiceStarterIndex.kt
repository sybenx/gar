package org.stypox.dicio.gar.voicelog

import java.io.File
import java.io.IOException

/**
 * The few voice-log turns that may teach a starter chip, one short JSON line each, in
 * `files/gar-voice-log/starters.jsonl` (gar/DESIGN.md, "Home and chips"), so that the starters
 * never parse the whole log (up to 10 MB) when GAR opens, which is when the user starts talking.
 *
 * [add] gets each line as the log writes it; [select] turns a log line into its index line, or
 * null for a turn that can never teach a starter. Without an index, [add] does nothing (the log
 * has the line) and the scheduled [buildIfMissing] builds it from the log files once. Lines past [maxLines] go, oldest first. Clear deletes
 * the folder and the index with it.
 *
 * Not thread-safe: [GarVoiceLog] calls it from its one background thread only; [read] may run
 * on any thread (a line being written is skipped by its reader).
 */
class VoiceStarterIndex(
    private val files: VoiceLogFiles,
    private val select: (String) -> String?,
    private val maxLines: Int = MAX_LINES,
) {
    val file: File get() = File(files.dir, NAME)

    /** The log just wrote [line]. */
    fun add(line: String) {
        if (!file.exists()) {
            return // the log has the line; the scheduled build reads it, away from a turn
        }
        val indexLine = select(line) ?: return
        file.appendText(indexLine + "\n")
        if (file.length() > maxLines.toLong() * PRUNE_BYTES_PER_LINE) {
            prune()
        }
    }

    /** Builds the index from the log files if there is none (an update from before it). */
    fun buildIfMissing() {
        if (file.exists()) {
            return
        }
        val lines = files.logFiles().flatMap { log ->
            try {
                log.useLines { all -> all.mapNotNull(select).toList() }
            } catch (e: IOException) {
                listOf()
            }
        }
        write(lines.takeLast(maxLines))
    }

    private fun prune() {
        val lines = try {
            file.readLines().filter { it.isNotBlank() }
        } catch (e: IOException) {
            return
        }
        if (lines.size > maxLines) {
            write(lines.takeLast(maxLines))
        }
    }

    private fun write(lines: List<String>) {
        files.dir.mkdirs()
        val tmp = File(files.dir, "$NAME.tmp")
        tmp.writeText(lines.joinToString("") { it + "\n" })
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not write $file")
        }
    }

    companion object {
        const val NAME = "starters.jsonl"
        /** About a year of starters at a few a day; a short line is about 150 bytes. */
        const val MAX_LINES = 1000
        /** The index is pruned once it is this many bytes per [MAX_LINES] line. */
        private const val PRUNE_BYTES_PER_LINE = 300L

        /** The index's lines in [dir] (the voice log's folder), or null when there is none. */
        fun read(dir: File): List<String>? = try {
            File(dir, NAME).takeIf { it.isFile }?.readLines()
        } catch (e: IOException) {
            null
        }
    }
}
