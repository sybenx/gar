package org.stypox.dicio.skills.song

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * A recording waiting for the network, see [SongClipQueue].
 * @param provider the service the card named when it was recorded (null in clips queued before
 * GAR stored it: they go to the current service)
 */
data class QueuedClip(
    val id: String,
    val createdAtMillis: Long,
    val attempts: Int,
    val provider: SongProviderKind? = null,
)

@Serializable
private data class QueuedClipMeta(
    val createdAtMillis: Long,
    val attempts: Int = 0,
    val provider: String? = null,
)

/** What [SongClipQueue.add] did. */
sealed interface QueueAddResult {
    data class Added(val clip: QueuedClip) : QueueAddResult

    /** [SongClipQueue.maxClips] are already waiting. */
    data object Full : QueueAddResult

    /** The files couldn't be written (storage full, ...). */
    data object WriteFailed : QueueAddResult
}

/**
 * Recordings made offline, kept in app storage until [SongQueueProcessor] sends them: `<id>.wav`
 * with `<id>.json` (when it was recorded, for which service, how often sending failed). Oldest
 * first, at most [maxClips]; half-written files are dropped when the queue is read, clips older
 * than [maxAgeMillis] by [removeExpired] (which the worker and [SongQueueExpiryWorker] call, so
 * that they notify). The service's credentials are read from the settings when a clip is sent.
 * All access goes through one lock, since the skill and the workers share the files.
 */
class SongClipQueue(
    private val dir: File,
    val maxClips: Int = MAX_CLIPS,
    val maxAgeMillis: Long = MAX_AGE_MILLIS,
) {
    fun add(wav: ByteArray, nowMillis: Long, provider: SongProviderKind): QueueAddResult =
        synchronized(LOCK) {
            if (list(nowMillis).size >= maxClips) {
                return QueueAddResult.Full
            }
            try {
                dir.mkdirs()
                var n = 0
                var id: String
                do {
                    id = "$nowMillis-${n++}"
                } while (wavFile(id).exists() || metaFile(id).exists())

                // the wav first: a clip only counts once its metadata exists
                val tmp = File(dir, "$id.wav.tmp")
                tmp.writeBytes(wav)
                if (!tmp.renameTo(wavFile(id))) {
                    tmp.delete()
                    return QueueAddResult.WriteFailed
                }
                writeMeta(id, QueuedClipMeta(nowMillis, 0, provider.name))
                QueueAddResult.Added(QueuedClip(id, nowMillis, 0, provider))
            } catch (e: IOException) { // the outcome says it; nothing else to log
                QueueAddResult.WriteFailed
            }
        }

    /**
     * The waiting clips that haven't expired, oldest first; drops incomplete ones on the way.
     * Expired clips stay on disk until [removeExpired].
     */
    fun list(nowMillis: Long): List<QueuedClip> = all().filter { !isExpired(it, nowMillis) }

    fun size(nowMillis: Long): Int = list(nowMillis).size

    /** Deletes the clips older than [maxAgeMillis]. @return them, to tell the user */
    fun removeExpired(nowMillis: Long): List<QueuedClip> = synchronized(LOCK) {
        all().filter { isExpired(it, nowMillis) }.onEach { deleteFiles(it.id) }
    }

    /** When the oldest waiting clip expires, or null when nothing waits. */
    fun nextExpiryMillis(): Long? = all().minOfOrNull { it.createdAtMillis + maxAgeMillis }

    /** The clip's recording, or null if it is gone. */
    fun read(clip: QueuedClip): ByteArray? = synchronized(LOCK) {
        try {
            wavFile(clip.id).takeIf { it.isFile }?.readBytes()
        } catch (e: IOException) {
            null
        }
    }

    /** Counts a failed attempt to send [clip]. @return the updated clip */
    fun recordAttempt(clip: QueuedClip): QueuedClip = synchronized(LOCK) {
        val updated = clip.copy(attempts = clip.attempts + 1)
        if (metaFile(clip.id).isFile) {
            writeMeta(
                clip.id,
                QueuedClipMeta(updated.createdAtMillis, updated.attempts, updated.provider?.name),
            )
        }
        updated
    }

    fun remove(clip: QueuedClip) = synchronized(LOCK) {
        deleteFiles(clip.id)
    }

    fun clear() = synchronized(LOCK) {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun isExpired(clip: QueuedClip, nowMillis: Long) =
        nowMillis - clip.createdAtMillis > maxAgeMillis

    /** Every complete clip, expired or not, oldest first; deletes incomplete ones. */
    private fun all(): List<QueuedClip> = synchronized(LOCK) {
        val files = dir.listFiles() ?: return listOf()
        val clips = mutableListOf<QueuedClip>()
        val ids = files.mapNotNull { file ->
            when {
                file.name.endsWith(".json") -> file.name.removeSuffix(".json")
                file.name.endsWith(".wav") -> file.name.removeSuffix(".wav")
                else -> {
                    file.delete() // a .tmp left by a crash
                    null
                }
            }
        }.toSet()

        for (id in ids) {
            val meta = readMeta(id)
            if (meta == null || !wavFile(id).isFile) {
                deleteFiles(id)
                continue
            }
            val provider = meta.provider?.let { name ->
                SongProviderKind.entries.firstOrNull { it.name == name }
            }
            clips.add(QueuedClip(id, meta.createdAtMillis, meta.attempts, provider))
        }
        return clips.sortedWith(compareBy({ it.createdAtMillis }, { it.id }))
    }

    private fun wavFile(id: String) = File(dir, "$id.wav")
    private fun metaFile(id: String) = File(dir, "$id.json")

    private fun deleteFiles(id: String) {
        // the metadata first, so a half-deleted clip is incomplete and dropped later
        metaFile(id).delete()
        wavFile(id).delete()
    }

    private fun readMeta(id: String): QueuedClipMeta? = try {
        metaFile(id).takeIf { it.isFile }
            ?.readText()
            ?.let { JSON.decodeFromString(QueuedClipMeta.serializer(), it) }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) { // a broken file
        null
    }

    private fun writeMeta(id: String, meta: QueuedClipMeta) {
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(JSON.encodeToString(QueuedClipMeta.serializer(), meta))
        if (!tmp.renameTo(metaFile(id))) {
            tmp.delete()
            throw IOException("Can't write the metadata of $id")
        }
    }

    companion object {
        private val LOCK = Any()
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Ten clips of 10 s are at most 8.8 MB (44.1 kHz), usually 3.2 MB. */
        const val MAX_CLIPS = 10

        /** A song asked about a week ago isn't worth sending anymore. */
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

        fun forContext(context: Context) =
            SongClipQueue(File(context.applicationContext.filesDir, "song_queue"))
    }
}

/** Why a queued clip was dropped without being sent. */
enum class SongDropReason {
    /** No service is set up anymore. */
    NO_SERVICE,

    /** The user picked another service after the card named the first one. */
    SERVICE_CHANGED,

    /** It waited [SongClipQueue.MAX_AGE_MILLIS] for the network. */
    EXPIRED,
}

/** What happened to queued clips, shown as notifications. */
interface SongQueueNotifier {
    fun found(clip: QueuedClip, track: SongTrack)
    fun notFound(clip: QueuedClip)

    /** The service answered with an error, or failed [SongQueueProcessor.MAX_ATTEMPTS] times. */
    fun failed(clip: QueuedClip, provider: SongProviderConfig, failure: RecognitionResult.Failure)

    /** The clip was dropped without sending it. */
    fun dropped(clip: QueuedClip, reason: SongDropReason)
}

/**
 * Sends the queued clips, oldest first. A clip leaves the queue with a notification when the
 * service answers (a match or none), when it fails for good (a bad token, the limit, a bad
 * recording) or after [MAX_ATTEMPTS] transient failures (no connection, the service down), and
 * without being sent when it expired or its service is gone or another one. After a transient
 * failure the rest waits for the next run, see [SongQueueWorker].
 *
 * Nothing is sent, and the clips are deleted silently, while the song skill is off or GAR can't
 * show notifications ([canRun] false): nobody would learn the result.
 */
class SongQueueProcessor(
    private val queue: SongClipQueue,
    private val provider: suspend () -> SongProviderConfig?,
    private val recognizer: SongRecognizer,
    private val notifier: SongQueueNotifier,
    private val canRun: suspend () -> Boolean = { true },
    private val onError: (Exception) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { DONE, RETRY }

    suspend fun processAll(): Outcome {
        if (!canRun()) {
            queue.clear()
            return Outcome.DONE
        }
        queue.removeExpired(nowMillis()).forEach { notifier.dropped(it, SongDropReason.EXPIRED) }

        val done = mutableSetOf<String>()
        while (true) {
            // read again each time: the skill may queue a clip meanwhile
            val clip = queue.list(nowMillis()).firstOrNull { it.id !in done } ?: return Outcome.DONE
            if (!process(clip)) {
                return Outcome.RETRY
            }
            done.add(clip.id) // in case its files can't be deleted
        }
    }

    /** @return false when [clip] stays queued for another try */
    private suspend fun process(clip: QueuedClip): Boolean {
        val config = provider()
        if (config == null || (clip.provider != null && clip.provider != config.kind)) {
            // nothing will ever send this recording to the service its card named
            queue.remove(clip)
            notifier.dropped(
                clip,
                if (config == null) SongDropReason.NO_SERVICE else SongDropReason.SERVICE_CHANGED,
            )
            return true
        }
        val wav = queue.read(clip)
        if (wav == null) {
            queue.remove(clip)
            return true
        }

        val result = try {
            recognizer.recognize(config, wav)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a bug: drop the clip instead of failing the work and leaving the queue stuck
            onError(e)
            RecognitionResult.ServiceError(null, e.message)
        }
        if (result is RecognitionResult.Failure && result.isTransient) {
            val updated = queue.recordAttempt(clip)
            if (updated.attempts < MAX_ATTEMPTS) {
                return false
            }
            queue.remove(updated)
            notifier.failed(updated, config, result)
            return true
        }

        // removed before notifying: if GAR dies in between, the notification is lost, but the
        // clip isn't sent (and billed) a second time
        queue.remove(clip)
        when (result) {
            is RecognitionResult.Match -> notifier.found(clip, result.track)
            RecognitionResult.NoMatch -> notifier.notFound(clip)
            is RecognitionResult.Failure -> notifier.failed(clip, config, result)
        }
        return true
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
