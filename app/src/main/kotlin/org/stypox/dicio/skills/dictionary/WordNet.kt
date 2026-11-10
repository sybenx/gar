package org.stypox.dicio.skills.dictionary

import java.io.File
import java.io.RandomAccessFile

/** WordNet's parts of speech and the names of their files ("index.noun", "noun.exc"). */
enum class PartOfSpeech(val fileName: String) {
    NOUN("noun"), VERB("verb"), ADJECTIVE("adj"), ADVERB("adv"),
}

/**
 * One meaning of a word: its synset's [words] (the word itself among them), the [definition] and
 * the [examples] of its gloss. For an adjective, [similar] has the first word of each synset
 * WordNet calls similar ("tired": "exhausted", "weary"), since its own synset often has no other
 * word; [similarOffsets] are where those synsets are.
 */
data class WordSense(
    val words: List<String>,
    val definition: String,
    val examples: List<String>,
    val similarOffsets: List<Long> = listOf(),
    val similar: List<String> = listOf(),
)

/**
 * A word as one part of speech, with its meanings in WordNet's order (most frequent first) and
 * [tagged], how often its meanings were seen in WordNet's tagged texts (which part of speech
 * comes first: "run" is more often a verb).
 */
data class DictionaryEntry(
    val lemma: String,
    val partOfSpeech: PartOfSpeech,
    val tagged: Int,
    val senses: List<WordSense>,
)

/**
 * Looks words up in a WordNet database in WordNet's own format (WNDB: `index.*`, `data.*`,
 * `*.exc`), as Open English WordNet releases it, without loading it: the index files are sorted,
 * so a lookup is a binary search, and the index gives each meaning's byte offset in the data file.
 * Inflected forms ("ran", "geese", "happier") find their base form through the exception lists
 * and WordNet's suffix rules (Morphy).
 */
class WordNet(private val dir: File) {

    /** Every part of speech [word] has, the most frequent first; empty if WordNet lacks it. */
    fun lookup(word: String): List<DictionaryEntry> {
        val key = keyOf(word)
        return PartOfSpeech.entries.mapNotNull { pos ->
            baseForms(key, pos).firstNotNullOfOrNull { base -> entry(base, pos) }
        }.sortedWith(
            // the word as said before a base form ("tired" the adjective before "tire"), then
            // the most frequent; Open English WordNet's tag counts are all 0, so then the most
            // meanings
            compareByDescending<DictionaryEntry> { it.lemma == key }
                .thenByDescending { it.tagged }.thenByDescending { it.senses.size }
        )
    }

    private fun entry(key: String, pos: PartOfSpeech): DictionaryEntry? {
        val line = findLine(File(dir, "index.${pos.fileName}"), key) ?: return null
        val tokens = line.trim().split(' ')
        // lemma pos synset_cnt p_cnt [ptr_symbol...] sense_cnt tagsense_cnt synset_offset...
        val synsetCount = tokens.getOrNull(2)?.toIntOrNull() ?: return null
        val pointerCount = tokens.getOrNull(3)?.toIntOrNull() ?: return null
        val tagged = tokens.getOrNull(5 + pointerCount)?.toIntOrNull() ?: 0
        val offsets = tokens.takeLast(synsetCount).mapNotNull { it.toLongOrNull() }
        val data = File(dir, "data.${pos.fileName}")
        val senses = RandomAccessFile(data, "r").use { file ->
            offsets.mapNotNull { offset -> readLine(file, offset)?.let(::parseSense) }
                .mapIndexed { index, sense ->
                    if (index < SIMILAR_SENSES && sense.similarOffsets.isNotEmpty()) {
                        sense.copy(similar = sense.similarOffsets.mapNotNull { offset ->
                            readLine(file, offset)?.let(::parseSense)?.words?.firstOrNull()
                        })
                    } else {
                        sense
                    }
                }
        }
        return if (senses.isEmpty()) null else DictionaryEntry(key, pos, tagged, senses)
    }

    /** [key] itself, its forms in the exception list, then what the suffix rules make of it. */
    private fun baseForms(key: String, pos: PartOfSpeech): List<String> {
        val exceptions = findLine(File(dir, "${pos.fileName}.exc"), key)
            ?.trim()?.split(' ')?.drop(1).orEmpty()
        val detached = SUFFIXES[pos].orEmpty().mapNotNull { (suffix, ending) ->
            if (key.length > suffix.length && key.endsWith(suffix)) {
                key.dropLast(suffix.length) + ending
            } else {
                null
            }
        }
        return (listOf(key) + exceptions + detached).distinct()
    }

    companion object {
        /** "Ice Cream" -> "ice_cream", as WordNet's files spell lemmas. */
        fun keyOf(word: String): String =
            word.trim().lowercase().replace(Regex("\\s+"), "_")

        /** WordNet's detachment rules (Morphy), in its order: suffix and replacement. */
        private val SUFFIXES = mapOf(
            PartOfSpeech.NOUN to listOf(
                "s" to "", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh",
                "men" to "man", "ies" to "y",
            ),
            PartOfSpeech.VERB to listOf(
                "s" to "", "ies" to "y", "es" to "e", "es" to "", "ed" to "e", "ed" to "",
                "ing" to "e", "ing" to "",
            ),
            PartOfSpeech.ADJECTIVE to listOf("er" to "", "est" to "", "er" to "e", "est" to "e"),
        )

        /**
         * A data line: `offset lex_filenum ss_type w_cnt word lex_id [word lex_id...] p_cnt
         * [pointers...] [frames...] | gloss`, with w_cnt in hexadecimal.
         */
        fun parseSense(line: String): WordSense? {
            val bar = line.indexOf(" | ")
            if (bar < 0) {
                return null
            }
            val tokens = line.substring(0, bar).split(' ')
            val wordCount = tokens.getOrNull(3)?.toIntOrNull(HEX) ?: return null
            val words = (0 until wordCount).mapNotNull { tokens.getOrNull(4 + 2 * it) }
                .map { it.replace(ADJECTIVE_MARKER, "").replace('_', ' ') }
            // p_cnt, then each pointer: symbol, synset offset, part of speech, source/target
            val pointerStart = 4 + 2 * wordCount
            val pointerCount = tokens.getOrNull(pointerStart)?.toIntOrNull() ?: 0
            val similarOffsets = (0 until pointerCount).mapNotNull { index ->
                val at = pointerStart + 1 + 4 * index
                if (tokens.getOrNull(at) == SIMILAR_TO) tokens.getOrNull(at + 1)?.toLongOrNull() else null
            }
            val gloss = line.substring(bar + 3).trim()
            val examples = EXAMPLE.findAll(gloss).map { it.groupValues[1].trim() }.toList()
            val definition = gloss.substringBefore("; \"").substringBefore("\"").trim()
                .trimEnd(';').trim()
            return if (definition.isEmpty()) {
                null
            } else {
                WordSense(words, definition, examples, similarOffsets)
            }
        }

        private const val HEX = 16
        private const val SIMILAR_TO = "&"

        /** The meanings whose similar words a lookup reads (the synonyms come from these). */
        private const val SIMILAR_SENSES = 4
        private val ADJECTIVE_MARKER = Regex("\\((a|p|ip)\\)$")
        private val EXAMPLE = Regex("\"([^\"]+)\"")

        /**
         * The line of the sorted [file] whose first word is [key], by binary search over the
         * file's bytes; null if there is none or the file is missing. Lines are compared byte by
         * byte (the files are sorted that way); the license lines at the start begin with spaces,
         * so they sort before every word.
         */
        fun findLine(file: File, key: String): String? {
            if (!file.isFile) {
                return null
            }
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            RandomAccessFile(file, "r").use { raf ->
                var lo = 0L
                var hi = raf.length()
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    val start = nextLineStart(raf, mid)
                    if (start >= hi) {
                        hi = mid
                        continue
                    }
                    val line = readBytes(raf, start) ?: return null
                    val comparison = compareFirstWord(line, keyBytes)
                    when {
                        comparison == 0 -> return String(line, Charsets.UTF_8)
                        comparison < 0 -> lo = start + line.size + 1
                        else -> hi = mid
                    }
                }
            }
            return null
        }

        /** The line that starts at [offset] (a synset's offset in a data file). */
        fun readLine(file: RandomAccessFile, offset: Long): String? =
            readBytes(file, offset)?.let { String(it, Charsets.UTF_8) }

        /** The first line start at or after [position]. */
        private fun nextLineStart(raf: RandomAccessFile, position: Long): Long {
            if (position == 0L) {
                return 0L
            }
            var at = position - 1
            val buffer = ByteArray(CHUNK)
            while (at < raf.length()) {
                raf.seek(at)
                val read = raf.read(buffer)
                if (read <= 0) {
                    break
                }
                for (i in 0 until read) {
                    if (buffer[i] == NEWLINE) {
                        return at + i + 1
                    }
                }
                at += read
            }
            return raf.length()
        }

        /** The bytes from [start] to the end of its line, or null at the end of the file. */
        private fun readBytes(raf: RandomAccessFile, start: Long): ByteArray? {
            if (start >= raf.length()) {
                return null
            }
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(CHUNK)
            var at = start
            while (true) {
                raf.seek(at)
                val read = raf.read(buffer)
                if (read <= 0) {
                    break
                }
                val newline = (0 until read).firstOrNull { buffer[it] == NEWLINE }
                if (newline != null) {
                    out.write(buffer, 0, newline)
                    break
                }
                out.write(buffer, 0, read)
                at += read
            }
            return out.toByteArray()
        }

        /** [line]'s first word against [key], bytes unsigned; a shorter word sorts first. */
        private fun compareFirstWord(line: ByteArray, key: ByteArray): Int {
            val end = line.indexOf(SPACE).let { if (it < 0) line.size else it }
            for (i in 0 until minOf(end, key.size)) {
                val a = line[i].toInt() and 0xff
                val b = key[i].toInt() and 0xff
                if (a != b) {
                    return a - b
                }
            }
            return end - key.size
        }

        private const val CHUNK = 4096
        private const val NEWLINE = '\n'.code.toByte()
        private const val SPACE = ' '.code.toByte()
    }
}
