package org.stypox.dicio.gar.model

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** @param damaged the file was corrupted on the way (wrong checksum or size), not just cut off */
open class GarDownloadException(message: String, val damaged: Boolean = false) :
    IOException(message)

/**
 * Downloads model files: `.part` file, resume with `Range`, HTTP status check, then size and
 * sha256 verification before the file gets its final name. Only GAR's model downloads use this.
 */
class GarModelDownloader(private val httpClient: OkHttpClient) {

    /**
     * Downloads [url] into [target]. A `.part` file left by an earlier attempt is resumed; if the
     * server answers the resume with another part of the file, the `.part` file is deleted and the
     * whole file downloaded once more from the start. Cancelling the calling coroutine keeps the
     * `.part` file so the next attempt can resume.
     *
     * @param onProgress called with the bytes on disk so far and [expectedSize]
     * @throws GarDownloadException on HTTP errors, unexpected sizes and checksum mismatches
     */
    suspend fun download(
        url: String,
        target: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = partFile(target)
        var offset = if (part.exists()) part.length() else 0L
        if (offset > expectedSize) {
            part.delete()
            offset = 0L
        }
        onProgress(offset, expectedSize)

        if (offset < expectedSize) {
            try {
                try {
                    fetch(url, part, offset, expectedSize, onProgress)
                } catch (e: WrongRangeException) {
                    if (offset == 0L) {
                        throw e
                    }
                    // a server that answers ranges wrongly would fail every resume: start over,
                    // once (without a Range header the whole file comes from the start)
                    part.delete()
                    onProgress(0L, expectedSize)
                    fetch(url, part, 0L, expectedSize, onProgress)
                }
            } catch (e: IOException) {
                // a cancelled coroutine cancels the call, which makes reads fail: report that as
                // a cancellation, not as an error
                coroutineContext.ensureActive()
                throw e
            }
        }

        val length = part.length()
        if (length != expectedSize) {
            if (length > expectedSize) {
                part.delete()
            }
            throw GarDownloadException("Download incomplete: $length of $expectedSize bytes")
        }
        val actualSha256 = sha256(part)
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            part.delete()
            throw GarDownloadException(
                "Checksum mismatch: expected $expectedSha256, got $actualSha256", damaged = true
            )
        }

        target.delete()
        if (!part.renameTo(target)) {
            throw IOException("Cannot rename $part to $target")
        }
    }

    private suspend fun fetch(
        url: String,
        part: File,
        offset: Long,
        expectedSize: Long,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit,
    ) = coroutineScope {
        val request = Request.Builder()
            .url(url)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        val call = httpClient.newCall(request)
        // the reads below block their thread: cancel the call as soon as this coroutine is
        // cancelled, so a stalled connection doesn't keep writing to the .part file until the read
        // timeout
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }

        try {
            call.execute().use { response ->
                val start = when (response.code) {
                    HTTP_PARTIAL_CONTENT -> {
                        val rangeStart = parseContentRangeStart(response.header("Content-Range"))
                        if (rangeStart != offset) {
                            throw WrongRangeException(rangeStart, offset)
                        }
                        offset
                    }
                    // the server sent the whole file (no Range, or Range not supported)
                    HTTP_OK -> 0L
                    else -> throw GarDownloadException("HTTP ${response.code} for $url")
                }

                var current = start
                FileOutputStream(part, start > 0).use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) {
                                break
                            }
                            coroutineContext.ensureActive() // don't write after a cancel
                            if (current + read > expectedSize) {
                                throw GarDownloadException(
                                    "Server sent more than $expectedSize bytes", damaged = true
                                )
                            }
                            output.write(buffer, 0, read)
                            current += read
                            onProgress(current, expectedSize)
                        }
                    }
                }
            }
        } finally {
            canceller.cancel()
        }
    }

    /** The server answered a request with another part of the file than the one asked for. */
    private class WrongRangeException(rangeStart: Long?, offset: Long) :
        GarDownloadException("Server resumed at $rangeStart instead of $offset")

    companion object {
        private const val CHUNK_SIZE = 256 * 1024
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206

        fun partFile(target: File): File = File(target.parentFile, target.name + ".part")

        /** @return the start offset in a `bytes START-END/TOTAL` header, or null */
        fun parseContentRangeStart(contentRange: String?): Long? {
            return contentRange
                ?.let { Regex("""^\s*bytes\s+(\d+)-""").find(it) }
                ?.groupValues?.get(1)
                ?.toLongOrNull()
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
