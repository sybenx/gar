package org.stypox.dicio.skills.song

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

private const val T0 = 1_761_500_000_000L
private val AUDD = SongProviderConfig.Audd("token")
private val TRACK = SongTrack("Everybody Wants To Rule The World", "Tears For Fears")

fun tempQueueDir(): File = Files.createTempDirectory("song_queue").toFile().also { it.deleteOnExit() }

/** Answers with [results] in order, the last one again when they run out. */
class FakeRecognizer(vararg results: RecognitionResult) : SongRecognizer {
    private val results = results.toMutableList()
    val sent = mutableListOf<Pair<SongProviderConfig, String>>()
    val wavs = mutableListOf<ByteArray>()

    override suspend fun recognize(config: SongProviderConfig, wav: ByteArray): RecognitionResult {
        sent.add(Pair(config, String(wav)))
        wavs.add(wav)
        return if (results.size > 1) results.removeAt(0) else results.first()
    }
}

/** Records the notifications; [queue], if given, must no longer hold the clip when it is told. */
class FakeNotifier(private val queue: SongClipQueue? = null) : SongQueueNotifier {
    val events = mutableListOf<String>()
    private fun add(clip: QueuedClip, event: String) {
        if (queue != null) {
            queue.list(clip.createdAtMillis).map { it.id }.contains(clip.id) shouldBe false
        }
        events.add(event)
    }
    override fun found(clip: QueuedClip, track: SongTrack) {
        add(clip, "found ${clip.createdAtMillis} ${track.title}")
    }
    override fun notFound(clip: QueuedClip) {
        add(clip, "not found ${clip.createdAtMillis}")
    }
    override fun failed(clip: QueuedClip, provider: SongProviderConfig, failure: RecognitionResult.Failure) {
        add(clip, "failed ${clip.createdAtMillis} ${provider.displayName} $failure")
    }
    override fun dropped(clip: QueuedClip, reason: SongDropReason) {
        add(clip, "dropped ${clip.createdAtMillis} $reason")
    }
}

private val AUDD_KIND = SongProviderKind.AUDD

private fun SongClipQueue.add(wav: ByteArray, nowMillis: Long): QueuedClip? =
    (add(wav, nowMillis, AUDD_KIND) as? QueueAddResult.Added)?.clip

class SongQueueTest : StringSpec({
    "clips wait oldest first and survive a new queue object" {
        val dir = tempQueueDir()
        val queue = SongClipQueue(dir)
        queue.add("second".toByteArray(), T0 + 2000).shouldNotBeNull()
        queue.add("first".toByteArray(), T0 + 1000).shouldNotBeNull()
        queue.add("third".toByteArray(), T0 + 2000).shouldNotBeNull() // same millisecond

        val clips = SongClipQueue(dir).list(T0 + 3000)
        clips.map { it.createdAtMillis } shouldBe listOf(T0 + 1000, T0 + 2000, T0 + 2000)
        clips.map { String(queue.read(it)!!) } shouldBe listOf("first", "second", "third")
        clips.map { it.attempts } shouldBe listOf(0, 0, 0)
    }

    "the queue is capped" {
        val queue = SongClipQueue(tempQueueDir(), maxClips = 3)
        repeat(3) { queue.add("clip $it".toByteArray(), T0 + it).shouldNotBeNull() }
        queue.add("one too many".toByteArray(), T0 + 10).shouldBeNull()
        queue.size(T0 + 10) shouldBe 3
        queue.remove(queue.list(T0 + 10).first())
        queue.add("fits again".toByteArray(), T0 + 11).shouldNotBeNull()
    }

    "half-written and stray files are cleaned up, old clips hidden until they are removed" {
        val dir = tempQueueDir()
        val queue = SongClipQueue(dir, maxAgeMillis = 1000)
        queue.add("old".toByteArray(), T0).shouldNotBeNull()
        queue.add("new".toByteArray(), T0 + 1500).shouldNotBeNull()
        File(dir, "123-0.wav").writeText("no metadata")
        File(dir, "124-0.json").writeText("""{"createdAtMillis":124}""") // no recording
        File(dir, "125-0.wav.tmp").writeText("a crash while writing")
        File(dir, "126-0.wav").writeText("broken metadata")
        File(dir, "126-0.json").writeText("not json")

        queue.list(T0 + 2000).map { String(queue.read(it)!!) } shouldBe listOf("new")
        queue.size(T0 + 2000) shouldBe 1
        // the expired one waits for removeExpired, which tells who removed it
        queue.nextExpiryMillis() shouldBe T0 + 1000
        queue.removeExpired(T0 + 2000).map { it.createdAtMillis } shouldBe listOf(T0)
        dir.list()!!.sorted() shouldBe listOf("${T0 + 1500}-0.json", "${T0 + 1500}-0.wav")
        queue.nextExpiryMillis() shouldBe T0 + 2500
        queue.removeExpired(T0 + 2000).shouldBeEmpty()
    }

    "a clip remembers its service; clips from before that go to the current one" {
        val dir = tempQueueDir()
        val queue = SongClipQueue(dir)
        queue.add("x".toByteArray(), T0, SongProviderKind.ACRCLOUD)
        File(dir, "${T0 + 1}-0.wav").writeText("y")
        File(dir, "${T0 + 1}-0.json").writeText("""{"createdAtMillis":${T0 + 1},"attempts":1}""")
        SongClipQueue(dir).list(T0 + 2).map { it.provider } shouldBe
            listOf(SongProviderKind.ACRCLOUD, null)
        // an attempt keeps it
        queue.recordAttempt(queue.list(T0 + 2).first())
        SongClipQueue(dir).list(T0 + 2).first().provider shouldBe SongProviderKind.ACRCLOUD
    }

    "a full queue and a failed write are told apart" {
        val queue = SongClipQueue(tempQueueDir(), maxClips = 1)
        queue.add("a".toByteArray(), T0, AUDD_KIND).shouldBeInstanceOf<QueueAddResult.Added>()
        queue.add("b".toByteArray(), T0, AUDD_KIND) shouldBe QueueAddResult.Full
        // a file where the directory should be: nothing can be written
        val notADir = File(tempQueueDir(), "file").apply { writeText("x") }
        SongClipQueue(notADir).add("c".toByteArray(), T0, AUDD_KIND) shouldBe
            QueueAddResult.WriteFailed
    }

    "attempts are counted in the clip's metadata, and clear empties the queue" {
        val dir = tempQueueDir()
        val queue = SongClipQueue(dir)
        val clip = queue.add("x".toByteArray(), T0).shouldNotBeNull()
        queue.recordAttempt(queue.recordAttempt(clip)).attempts shouldBe 2
        SongClipQueue(dir).list(T0).single().attempts shouldBe 2
        queue.clear()
        queue.list(T0).shouldBeEmpty()
    }

    "the processor sends oldest first and notifies each result" {
        val queue = SongClipQueue(tempQueueDir())
        queue.add("a".toByteArray(), T0)
        queue.add("b".toByteArray(), T0 + 1)
        val recognizer = FakeRecognizer(RecognitionResult.Match(TRACK), RecognitionResult.NoMatch)
        val notifier = FakeNotifier(queue) // each clip is deleted before it is told
        SongQueueProcessor(queue, { AUDD }, recognizer, notifier) { T0 + 5 }.processAll() shouldBe
            SongQueueProcessor.Outcome.DONE
        recognizer.sent shouldBe listOf(Pair(AUDD, "a"), Pair(AUDD, "b"))
        notifier.events shouldBe listOf("found $T0 ${TRACK.title}", "not found ${T0 + 1}")
        queue.list(T0 + 5).shouldBeEmpty()
    }

    "a transient failure keeps the clip and the rest for a retry, then gives up" {
        val queue = SongClipQueue(tempQueueDir())
        queue.add("a".toByteArray(), T0)
        queue.add("b".toByteArray(), T0 + 1)
        val recognizer = FakeRecognizer(RecognitionResult.NoConnection)
        val notifier = FakeNotifier()
        val processor = SongQueueProcessor(queue, { AUDD }, recognizer, notifier) { T0 + 5 }

        repeat(SongQueueProcessor.MAX_ATTEMPTS - 1) {
            processor.processAll() shouldBe SongQueueProcessor.Outcome.RETRY
        }
        recognizer.sent.map { it.second } shouldBe listOf("a", "a") // "b" waits
        notifier.events.shouldBeEmpty()
        queue.list(T0 + 5).map { it.attempts } shouldBe listOf(2, 0)

        // the third failure drops "a" with a notification; "b" fails its first try
        processor.processAll() shouldBe SongQueueProcessor.Outcome.RETRY
        notifier.events shouldBe listOf("failed $T0 AudD NoConnection")
        queue.list(T0 + 5).map { it.attempts } shouldBe listOf(1)
    }

    "the service coming back clears the queue" {
        val queue = SongClipQueue(tempQueueDir())
        queue.add("a".toByteArray(), T0)
        val recognizer = FakeRecognizer(
            RecognitionResult.HttpError(503), RecognitionResult.Match(TRACK),
        )
        val notifier = FakeNotifier()
        val processor = SongQueueProcessor(queue, { AUDD }, recognizer, notifier) { T0 + 5 }
        processor.processAll() shouldBe SongQueueProcessor.Outcome.RETRY
        processor.processAll() shouldBe SongQueueProcessor.Outcome.DONE
        notifier.events shouldBe listOf("found $T0 ${TRACK.title}")
    }

    "a lasting failure drops the clip at once, with a notification" {
        val queue = SongClipQueue(tempQueueDir())
        queue.add("a".toByteArray(), T0)
        queue.add("b".toByteArray(), T0 + 1)
        val notifier = FakeNotifier()
        SongQueueProcessor(
            queue, { AUDD }, FakeRecognizer(RecognitionResult.BadToken), notifier,
        ) { T0 + 5 }.processAll() shouldBe SongQueueProcessor.Outcome.DONE
        notifier.events shouldBe listOf(
            "failed $T0 AudD BadToken", "failed ${T0 + 1} AudD BadToken",
        )
        queue.list(T0 + 5).shouldBeEmpty()
    }

    "without a service the clips are dropped, without sending anything" {
        val queue = SongClipQueue(tempQueueDir())
        queue.add("a".toByteArray(), T0)
        val recognizer = FakeRecognizer(RecognitionResult.NoMatch)
        val notifier = FakeNotifier()
        SongQueueProcessor(queue, { null }, recognizer, notifier) { T0 + 5 }.processAll() shouldBe
            SongQueueProcessor.Outcome.DONE
        recognizer.sent.shouldBeEmpty()
        notifier.events shouldBe listOf("dropped $T0 NO_SERVICE")
        queue.list(T0 + 5).shouldBeEmpty()
    }

    "a clip recorded for another service is dropped, not sent to the new one" {
        val queue = SongClipQueue(tempQueueDir())
        queue.add("for acrcloud".toByteArray(), T0, SongProviderKind.ACRCLOUD)
        queue.add("for audd".toByteArray(), T0 + 1, SongProviderKind.AUDD)
        val recognizer = FakeRecognizer(RecognitionResult.NoMatch)
        val notifier = FakeNotifier(queue)
        SongQueueProcessor(queue, { AUDD }, recognizer, notifier) { T0 + 5 }.processAll() shouldBe
            SongQueueProcessor.Outcome.DONE
        recognizer.sent.map { it.second } shouldBe listOf("for audd")
        notifier.events shouldBe listOf("dropped $T0 SERVICE_CHANGED", "not found ${T0 + 1}")
    }

    "expired clips are dropped with a notification before anything is sent" {
        val queue = SongClipQueue(tempQueueDir(), maxAgeMillis = 1000)
        queue.add("old".toByteArray(), T0)
        queue.add("new".toByteArray(), T0 + 1500)
        val recognizer = FakeRecognizer(RecognitionResult.NoMatch)
        val notifier = FakeNotifier()
        SongQueueProcessor(queue, { AUDD }, recognizer, notifier) { T0 + 2000 }.processAll() shouldBe
            SongQueueProcessor.Outcome.DONE
        recognizer.sent.map { it.second } shouldBe listOf("new")
        notifier.events shouldBe listOf("dropped $T0 EXPIRED", "not found ${T0 + 1500}")
    }

    "with the skill off or no notifications nothing is sent, and the clips are deleted" {
        val queue = SongClipQueue(tempQueueDir())
        queue.add("a".toByteArray(), T0)
        val recognizer = FakeRecognizer(RecognitionResult.NoMatch)
        val notifier = FakeNotifier()
        SongQueueProcessor(
            queue, { AUDD }, recognizer, notifier, canRun = { false }, nowMillis = { T0 + 5 },
        ).processAll() shouldBe SongQueueProcessor.Outcome.DONE
        recognizer.sent.shouldBeEmpty()
        notifier.events.shouldBeEmpty()
        queue.list(T0 + 5).shouldBeEmpty()
    }

    "a recognizer that throws drops the clip instead of failing every run" {
        val queue = SongClipQueue(tempQueueDir())
        queue.add("a".toByteArray(), T0)
        queue.add("b".toByteArray(), T0 + 1)
        val errors = mutableListOf<Exception>()
        val notifier = FakeNotifier(queue)
        var calls = 0
        SongQueueProcessor(
            queue, { AUDD },
            { _, _ -> if (calls++ == 0) throw IllegalArgumentException("bad url") else RecognitionResult.NoMatch },
            notifier, onError = { errors.add(it) }, nowMillis = { T0 + 5 },
        ).processAll() shouldBe SongQueueProcessor.Outcome.DONE
        errors.single().message shouldBe "bad url"
        notifier.events shouldBe listOf(
            "failed $T0 AudD ${RecognitionResult.ServiceError(null, "bad url")}",
            "not found ${T0 + 1}",
        )
        queue.list(T0 + 5).shouldBeEmpty()
    }

    "an empty queue is done" {
        SongQueueProcessor(
            SongClipQueue(File(tempQueueDir(), "missing")), { AUDD },
            FakeRecognizer(RecognitionResult.NoMatch), FakeNotifier(),
        ).processAll() shouldBe SongQueueProcessor.Outcome.DONE
    }
})
