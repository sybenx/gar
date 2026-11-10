package org.stypox.dicio.skills.dictionary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.stypox.dicio.gar.model.GarModelDownloader
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * The dictionary's data: Open English WordNet 2025 (CC BY 4.0, https://en-word.net), downloaded
 * once from its GitHub release (9.6 MB, checked by size and SHA-256 like GAR's models) and kept
 * unpacked in [dir] (about 25 MB: the index, data and exception files of the four parts of
 * speech). After that every lookup is offline.
 *
 * @param download fetches a url into a file and checks its size and SHA-256, see
 * [GarModelDownloader.download]
 */
class DictionaryPack(
    val dir: File,
    private val download: suspend (url: String, target: File, size: Long, sha256: String) -> Unit,
) {
    /** Whether the files are all there (the marker is written last). */
    val isInstalled: Boolean get() = File(dir, READY_MARKER).isFile

    /**
     * Downloads and unpacks the dictionary, unless it is there already.
     * @throws IOException without a connection, on a bad download or a full disk
     */
    suspend fun install() = withContext(Dispatchers.IO) {
        if (isInstalled) {
            return@withContext
        }
        val parent = dir.absoluteFile.parentFile ?: throw IOException("No parent for $dir")
        parent.mkdirs()
        val zip = File(parent, ZIP_NAME)
        download(URL, zip, SIZE, SHA256)
        val unpacking = File(parent, dir.name + ".unpacking")
        unpacking.deleteRecursively()
        unpacking.mkdirs()
        unpack(zip, unpacking)
        File(unpacking, READY_MARKER).writeText(VERSION)
        dir.deleteRecursively()
        if (!unpacking.renameTo(dir)) {
            throw IOException("Cannot rename $unpacking to $dir")
        }
        zip.delete()
    }

    companion object {
        const val VERSION = "english-wordnet-2025"
        const val URL = "https://github.com/globalwordnet/english-wordnet/releases/download/" +
            "2025-edition/english-wordnet-2025.zip"
        const val SIZE = 9_618_697L
        const val SHA256 = "73355e48f8117a24ca9ebc23ed75b35434e6cd21cc9dd3984e80aff5a5f63636"

        private const val ZIP_NAME = "english-wordnet-2025.zip"
        private const val READY_MARKER = "ready"

        /** The files a lookup reads; the zip also has index.sense (6.5 MB), which isn't needed. */
        val FILES: Set<String> = PartOfSpeech.entries.flatMap { pos ->
            listOf("index.${pos.fileName}", "data.${pos.fileName}", "${pos.fileName}.exc")
        }.toSet()

        /** The app's pack, in its files directory, downloaded with OkHttp. */
        fun forFilesDir(filesDir: File, httpClient: OkHttpClient = OkHttpClient()): DictionaryPack {
            val downloader = GarModelDownloader(httpClient)
            return DictionaryPack(File(filesDir, "gar/dictionary/$VERSION")) { url, target, size, sha ->
                downloader.download(url, target, size, sha) { _, _ -> }
            }
        }

        /** Copies the entries named in [FILES] (in any folder of the zip) into [target]. */
        fun unpack(zip: File, target: File) {
            ZipFile(zip).use { file ->
                val found = mutableSetOf<String>()
                for (entry in file.entries()) {
                    val name = entry.name.substringAfterLast('/')
                    if (entry.isDirectory || name !in FILES) {
                        continue
                    }
                    file.getInputStream(entry).use { input ->
                        File(target, name).outputStream().use { input.copyTo(it) }
                    }
                    found.add(name)
                }
                if (found != FILES) {
                    throw IOException("The dictionary lacks ${FILES - found}")
                }
            }
        }
    }
}
