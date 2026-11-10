package org.stypox.dicio.gar

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.stypox.dicio.gar.model.GarDownloadException
import org.stypox.dicio.gar.model.GarModelDownloader
import org.stypox.dicio.gar.model.GarModelManager
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

private const val URL = "https://example.invalid/model.gguf"

/** Serves [content] without network, honoring `Range` unless [ignoreRange]. */
private class FakeServer(
    val content: ByteArray,
    val ignoreRange: Boolean = false,
    val status: Int? = null,
    /** Serve only this many bytes of the body, like a dropped connection. */
    val truncateAt: Int? = null,
    /** The Content-Range header of a 206 for a range from the given start, null for none. */
    val contentRange: (start: Int) -> String? = { "bytes $it-${content.size - 1}/${content.size}" },
    /** Answer a request without `Range` with a 206 too, like a broken server. */
    val partialWithoutRange: Boolean = false,
) : Interceptor {
    val rangeHeaders = mutableListOf<String?>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val range = request.header("Range")
        rangeHeaders.add(range)
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .message("fake")

        if (status != null) {
            return builder.code(status).body("nope".toResponseBody()).build()
        }

        val start = range?.takeIf { !ignoreRange }
            ?.let { Regex("""bytes=(\d+)-""").find(it)!!.groupValues[1].toInt() }
        var body = if (start == null) content else content.copyOfRange(start, content.size)
        truncateAt?.let { body = body.copyOf(minOf(it, body.size)) }
        val mediaType = "application/octet-stream".toMediaType()
        return if (start == null && !partialWithoutRange) {
            builder.code(200).body(body.toResponseBody(mediaType)).build()
        } else {
            builder.code(206)
                .apply { contentRange(start ?: 0)?.let { header("Content-Range", it) } }
                .body(body.toResponseBody(mediaType))
                .build()
        }
    }
}

private fun sha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private class DownloadFixture(val server: FakeServer) {
    val dir: File = Files.createTempDirectory("gar-download").toFile().apply { deleteOnExit() }
    val target = File(dir, "model.gguf")
    val part = GarModelDownloader.partFile(target)
    val progress = mutableListOf<Pair<Long, Long>>()
    val downloader = GarModelDownloader(OkHttpClient.Builder().addInterceptor(server).build())

    suspend fun download(
        sha256: String = sha256(server.content),
        expectedSize: Long = server.content.size.toLong(),
    ) = downloader.download(
        url = URL,
        target = target,
        expectedSize = expectedSize,
        expectedSha256 = sha256,
    ) { current, total -> progress.add(Pair(current, total)) }
}

private val CONTENT = Random(42).nextBytes(1_000_000)

class GarModelDownloaderTest : StringSpec({
    "downloads, verifies and renames" {
        val fixture = DownloadFixture(FakeServer(CONTENT))
        fixture.download()

        fixture.target.readBytes() shouldBe CONTENT
        fixture.part.exists().shouldBeFalse()
        fixture.server.rangeHeaders shouldBe listOf(null)
        fixture.progress.last() shouldBe Pair(CONTENT.size.toLong(), CONTENT.size.toLong())
    }

    "a checksum mismatch deletes the download" {
        val fixture = DownloadFixture(FakeServer(CONTENT))
        val e = shouldThrow<GarDownloadException> { fixture.download(sha256 = "0".repeat(64)) }
        e.message shouldContain "Checksum mismatch"
        e.damaged.shouldBeTrue()
        fixture.target.exists().shouldBeFalse()
        fixture.part.exists().shouldBeFalse()
    }

    "resumes a partial download from its size" {
        val fixture = DownloadFixture(FakeServer(CONTENT))
        fixture.part.writeBytes(CONTENT.copyOf(300_000))
        fixture.download()

        fixture.server.rangeHeaders shouldBe listOf("bytes=300000-")
        fixture.target.readBytes() shouldBe CONTENT
        fixture.progress.first() shouldBe Pair(300_000L, CONTENT.size.toLong())
    }

    "starts over when the server ignores the range" {
        val fixture = DownloadFixture(FakeServer(CONTENT, ignoreRange = true))
        fixture.part.writeBytes(CONTENT.copyOf(300_000))
        fixture.download()

        fixture.server.rangeHeaders shouldBe listOf("bytes=300000-")
        fixture.target.readBytes() shouldBe CONTENT
    }

    "a dropped connection keeps the partial file for the next attempt" {
        val dropping = DownloadFixture(FakeServer(CONTENT, truncateAt = 400_000))
        val e = shouldThrow<GarDownloadException> { dropping.download() }
        e.message shouldContain "incomplete"
        e.damaged.shouldBeFalse() // just cut off
        dropping.part.length() shouldBe 400_000L
        dropping.target.exists().shouldBeFalse()

        // same files, a server that sends everything
        val resumed = DownloadFixture(FakeServer(CONTENT))
        dropping.part.copyTo(resumed.part)
        resumed.download()
        resumed.server.rangeHeaders shouldBe listOf("bytes=400000-")
        resumed.target.readBytes() shouldBe CONTENT
    }

    "a complete partial file is only verified" {
        val fixture = DownloadFixture(FakeServer(CONTENT))
        fixture.part.writeBytes(CONTENT)
        fixture.download()
        fixture.server.rangeHeaders.shouldBeEmpty()
        fixture.target.readBytes() shouldBe CONTENT
    }

    "HTTP errors fail without touching the partial file" {
        val fixture = DownloadFixture(FakeServer(CONTENT, status = 404))
        fixture.part.writeBytes(CONTENT.copyOf(10))
        val e = shouldThrow<GarDownloadException> { fixture.download() }
        e.message shouldContain "404"
        e.damaged.shouldBeFalse()
        fixture.part.length() shouldBe 10L
    }

    "Content-Range parsing" {
        GarModelDownloader.parseContentRangeStart("bytes 100-199/200") shouldBe 100L
        GarModelDownloader.parseContentRangeStart("bytes */200") shouldBe null
        GarModelDownloader.parseContentRangeStart(null) shouldBe null
        GarModelDownloader.parseContentRangeStart("  bytes 5-9/10") shouldBe 5L
        GarModelDownloader.parseContentRangeStart("bytes 5-9/*") shouldBe 5L
        GarModelDownloader.parseContentRangeStart("bytes=5-9") shouldBe null
    }

    "a partial file bigger than the model is downloaded again from the start" {
        val fixture = DownloadFixture(FakeServer(CONTENT))
        fixture.part.writeBytes(CONTENT + ByteArray(10))
        fixture.download()
        fixture.server.rangeHeaders shouldBe listOf(null)
        fixture.target.readBytes() shouldBe CONTENT
    }

    "a resume answered with another part of the file starts over from the start, once" {
        // the body starts at 300000, the header says 0: every resume would fail
        val wrongStart = DownloadFixture(
            FakeServer(CONTENT, contentRange = { "bytes 0-${CONTENT.size - 1}/${CONTENT.size}" })
        )
        wrongStart.part.writeBytes(CONTENT.copyOf(300_000))
        wrongStart.download()
        wrongStart.server.rangeHeaders shouldBe listOf("bytes=300000-", null)
        wrongStart.target.readBytes() shouldBe CONTENT
        wrongStart.part.exists().shouldBeFalse()
        wrongStart.progress.first() shouldBe Pair(300_000L, CONTENT.size.toLong())
        wrongStart.progress[1] shouldBe Pair(0L, CONTENT.size.toLong())

        val noRange = DownloadFixture(FakeServer(CONTENT, contentRange = { null }))
        noRange.part.writeBytes(CONTENT.copyOf(300_000))
        noRange.download()
        noRange.server.rangeHeaders shouldBe listOf("bytes=300000-", null)
        noRange.target.readBytes() shouldBe CONTENT
    }

    "a server that answers every request with another part of the file fails after one restart" {
        fun brokenServer() = FakeServer(
            CONTENT,
            contentRange = { "bytes 5-${CONTENT.size - 1}/${CONTENT.size}" },
            partialWithoutRange = true,
        )
        val resumed = DownloadFixture(brokenServer())
        resumed.part.writeBytes(CONTENT.copyOf(300_000))
        val e = shouldThrow<GarDownloadException> { resumed.download() }
        e.message shouldBe "Server resumed at 5 instead of 0"
        e.damaged.shouldBeFalse()
        resumed.server.rangeHeaders shouldBe listOf("bytes=300000-", null)
        // the partial file was dropped for the restart, which wrote nothing
        resumed.part.exists().shouldBeFalse()
        resumed.target.exists().shouldBeFalse()

        // without a partial file there is nothing to restart
        val fresh = DownloadFixture(brokenServer())
        shouldThrow<GarDownloadException> { fresh.download() }.message shouldBe
            "Server resumed at 5 instead of 0"
        fresh.server.rangeHeaders shouldBe listOf(null)
    }

    "a body longer than the model is a damaged download" {
        val fixture = DownloadFixture(FakeServer(CONTENT))
        val e = shouldThrow<GarDownloadException> {
            fixture.download(expectedSize = CONTENT.size - 10L)
        }
        e.message shouldContain "more than"
        e.damaged.shouldBeTrue()
        fixture.target.exists().shouldBeFalse()
    }

    "checksums are compared ignoring case" {
        val fixture = DownloadFixture(FakeServer(CONTENT))
        fixture.download(sha256 = sha256(CONTENT).uppercase())
        fixture.target.readBytes() shouldBe CONTENT
    }

    "imported files must start with the GGUF magic" {
        GarModelManager.isGguf("GGUF\u0003\u0000".toByteArray()) shouldBe true
        GarModelManager.isGguf("GGML".toByteArray()) shouldBe false
        GarModelManager.isGguf("GG".toByteArray()) shouldBe false
        GarModelManager.readHeader(ByteArrayInputStream("GGUFxyz".toByteArray()), 4)
            .decodeToString() shouldBe "GGUF"
        GarModelManager.readHeader(ByteArrayInputStream("GG".toByteArray()), 4)
            .decodeToString() shouldBe "GG"
    }

    "cancelling a stalled download stops it right away, without writing more" {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            // sends the headers and 1000 bytes of a much bigger body, then stalls
            val serverThread = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".toByteArray())
                            write(CONTENT.copyOf(1000))
                            flush()
                        }
                        Thread.sleep(60_000)
                    }
                } catch (_: InterruptedException) {
                }
            }

            val dir = Files.createTempDirectory("gar-download").toFile().apply { deleteOnExit() }
            val target = File(dir, "model.gguf")
            val part = GarModelDownloader.partFile(target)
            // a read timeout far longer than the test: only cancelling the call can unblock it
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

            coroutineScope {
                val job = launch(Dispatchers.Default) {
                    GarModelDownloader(client).download(
                        url = "http://127.0.0.1:${server.localPort}/model.gguf",
                        target = target,
                        expectedSize = 1_000_000,
                        expectedSha256 = "0".repeat(64),
                    ) { _, _ -> }
                }
                withTimeout(20_000) {
                    while (part.length() < 1000) {
                        delay(20)
                    }
                }

                val start = System.nanoTime()
                job.cancelAndJoin()
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) shouldBeLessThan 5_000L
            }
            part.length() shouldBe 1000L
            target.exists().shouldBeFalse()
            serverThread.interrupt()
        }
    }
})
