package org.stypox.dicio.skills.song

import cc.aaro.gar.router.KeywordFilter
import cc.aaro.gar.router.Utterances
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Whether an input asks which song plays, see [SongSkill.score] and `SongCallFilter`. */
object SongWords {
    /** The song_identify tool's keywords in tools.yml (GarCatalogTest compares them). */
    val KEYWORDS = listOf(
        "song", "music", "track", "tune", "playing", "listen", "sing", "sang", "singer", "shazam",
    )

    /**
     * Other questions about a song or about something else that plays, and media commands: lyrics
     * are the lyrics skill's; Dicio's matcher took "what's the next song", "what movie is
     * playing", "what's the music volume", "what's the track record", "who wrote this song" and
     * "who sang that song yesterday" with a word swapped or added (and the last song isn't the
     * one that plays now); the model's `media_control` commands name the music too ("skip this
     * song", "keep playing", checked by `SongCallFilter`). No sentence has one of these words.
     */
    private val OTHER_THINGS = listOf(
        "lyric", "next", "volume", "movie", "film", "show", "game", "record", "about", "wrote",
        "written", "writer", "produced", "producer", "genre", "tempo", "year", "yesterday", "last",
        "skip", "pause", "resume", "stop", "previous", "repeat", "replay", "shuffle", "mute",
        "louder", "quieter", "continue", "keep", "turn",
    )

    /**
     * The words that point at the sound around the phone; every sentence has one. Without one the
     * input asks about a song in general: "what is music" matched "what is the music" with "the"
     * left out, and "who sings hello" "who sings this" with "hello" for "this".
     */
    private val POINTERS = listOf(
        "this", "that", "it", "the", "playing", "on", "now", "singing", "listening", "shazam",
    )

    fun asksForSong(input: String): Boolean {
        val normalized = Utterances.normalize(input)
        return KeywordFilter.containsAny(normalized, KEYWORDS) &&
            KeywordFilter.containsAny(normalized, POINTERS) &&
            !KeywordFilter.containsAny(normalized, OTHER_THINGS)
    }
}

/** A track the phone plays, from its media session. */
data class PlayingTrack(
    val title: String,
    val artist: String?,
    val album: String?,
    /** The app that plays it, e.g. "Spotify". */
    val appName: String?,
)

/** One media session, as [SongLogic.nowPlaying] needs it. */
data class MediaSessionInfo(
    val packageName: String,
    val playing: Boolean,
    val title: String?,
    val artist: String?,
    val album: String?,
)

/** What the phone plays (tier 1). */
sealed interface NowPlaying {
    /** GAR has no notification access, so it can't see the media sessions: tier 1 is skipped. */
    data object NoAccess : NowPlaying
    data object NothingPlaying : NowPlaying

    /** Something plays, but its app doesn't say what. */
    data object Untitled : NowPlaying
    data class Track(val track: PlayingTrack) : NowPlaying
}

/** Which tier answers "what song is this". */
sealed interface SongPlan {
    /** Tier 1: the phone plays it, answer from the media session (offline). */
    data class Answer(val track: PlayingTrack) : SongPlan

    /** Tier 2: record and send to the user's service. */
    data class Record(val provider: SongProviderConfig) : SongPlan

    /** Tier 3: no service set up, Audile recognizes it. */
    data class OpenAudile(val packageName: String) : SongPlan

    /** Nothing can answer: say how to set something up. */
    data class NothingAvailable(val nowPlaying: NowPlaying) : SongPlan
}

object SongLogic {
    fun plan(nowPlaying: NowPlaying, provider: SongProviderConfig?, audilePackage: String?): SongPlan =
        when {
            nowPlaying is NowPlaying.Track -> SongPlan.Answer(nowPlaying.track)
            provider != null -> SongPlan.Record(provider)
            audilePackage != null -> SongPlan.OpenAudile(audilePackage)
            else -> SongPlan.NothingAvailable(nowPlaying)
        }

    /**
     * What plays, from the sessions in the system's priority order (the most recently active
     * first): the first playing session with a title, ignoring GAR's own. A playing session
     * without a title is [NowPlaying.Untitled], so that another tier recognizes the sound.
     */
    fun nowPlaying(
        sessions: List<MediaSessionInfo>,
        ownPackage: String,
        appName: (String) -> String?,
    ): NowPlaying {
        val playing = sessions.filter { it.playing && it.packageName != ownPackage }
        if (playing.isEmpty()) {
            return NowPlaying.NothingPlaying
        }
        val session = playing.firstOrNull { !it.title.isNullOrBlank() }
            ?: return NowPlaying.Untitled
        return NowPlaying.Track(
            PlayingTrack(
                title = session.title!!.trim(),
                artist = session.artist?.trim()?.takeIf { it.isNotEmpty() },
                album = session.album?.trim()?.takeIf { it.isNotEmpty() },
                appName = appName(session.packageName),
            )
        )
    }
}

/** What a tier-2 recognition ended with. */
sealed interface SongOutcome {
    data class Found(val track: SongTrack) : SongOutcome
    data object NotFound : SongOutcome

    /**
     * The clip waits for the network, a notification will tell.
     * @param offline no network; else the service couldn't be reached
     */
    data class Queued(val offline: Boolean) : SongOutcome

    /** The clip can't wait: the queue is full. */
    data class QueueFull(val waiting: Int, val offline: Boolean) : SongOutcome

    /** The clip can't wait: it couldn't be saved. */
    data class QueueFailed(val offline: Boolean) : SongOutcome

    /** The clip isn't kept: GAR can't show notifications, so nobody would learn the result. */
    data class NoNotifications(val offline: Boolean) : SongOutcome

    /** Nothing but silence was recorded, so it wasn't sent. */
    data object Silence : SongOutcome
    data class MicFailure(val reason: RecordingResult.Failure) : SongOutcome
    data class Failed(val failure: RecognitionResult.Failure) : SongOutcome

    /**
     * The user tapped Stop.
     * @param whileSending the recording was being sent, so part of it may have arrived
     */
    data class Stopped(val whileSending: Boolean) : SongOutcome

    /** A bug or an unexpected exception: logged, and the card doesn't hang. */
    data object Error : SongOutcome
}

/** Whether the recording reached the service, which the first such answer says aloud. */
val SongOutcome.sentToService: Boolean
    get() = when (this) {
        is SongOutcome.Found, SongOutcome.NotFound -> true
        is SongOutcome.Failed -> failure != RecognitionResult.NoConnection &&
            failure != RecognitionResult.BadHost
        else -> false
    }

/** The tier-2 card while it runs. */
sealed interface SongCardState {
    data class Listening(val progress: Float) : SongCardState
    data object Recognizing : SongCardState
    data class Done(val outcome: SongOutcome) : SongCardState
}

/**
 * Tier 2: records [SongAudio.CLIP_MILLIS], then sends the clip to [provider], or queues it when
 * there is no network (checked before sending) or the service can't be reached (an I/O failure),
 * but only if GAR can show the notification that tells the result ([canNotify]).
 */
class SongRecognitionRun(
    val provider: SongProviderConfig,
    private val recorder: SongRecorder,
    private val recognizer: SongRecognizer,
    private val queue: SongClipQueue,
    private val isOnline: () -> Boolean,
    /** Called after a clip was queued: schedules [SongQueueWorker]. */
    private val onQueued: () -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onError: (Exception) -> Unit = {},
    private val canNotify: () -> Boolean = { true },
) {
    private val _state = MutableStateFlow<SongCardState>(SongCardState.Listening(0f))
    val state: StateFlow<SongCardState> = _state

    suspend fun run(): SongOutcome {
        val outcome = try {
            recognize()
        } catch (e: CancellationException) {
            // Stop: whatever was recorded is dropped with the coroutine
            _state.value = SongCardState.Done(
                SongOutcome.Stopped(whileSending = _state.value == SongCardState.Recognizing)
            )
            throw e
        } catch (e: Exception) {
            onError(e)
            SongOutcome.Error
        }
        _state.value = SongCardState.Done(outcome)
        return outcome
    }

    private suspend fun recognize(): SongOutcome {
        val recording = when (
            val result = recorder.record(SongAudio.CLIP_MILLIS) {
                _state.value = SongCardState.Listening(it.coerceIn(0f, 1f))
            }
        ) {
            is RecordingResult.Recorded -> result
            is RecordingResult.Failure -> return SongOutcome.MicFailure(result)
        }
        if (SongAudio.isSilent(recording.pcm)) {
            return SongOutcome.Silence
        }
        val wav = SongWav.encode(recording.pcm, recording.sampleRate)

        if (!isOnline()) {
            return enqueue(wav, offline = true)
        }
        _state.value = SongCardState.Recognizing
        return when (val result = recognizer.recognize(provider, wav)) {
            is RecognitionResult.Match -> SongOutcome.Found(result.track)
            RecognitionResult.NoMatch -> SongOutcome.NotFound
            // online, but DNS, TLS or a timeout: the service may be down, or the network a portal
            RecognitionResult.NoConnection -> enqueue(wav, offline = false)
            is RecognitionResult.Failure -> SongOutcome.Failed(result)
        }
    }

    private fun enqueue(wav: ByteArray, offline: Boolean): SongOutcome {
        if (!canNotify()) {
            return SongOutcome.NoNotifications(offline)
        }
        return when (queue.add(wav, nowMillis(), provider.kind)) {
            is QueueAddResult.Added -> {
                onQueued()
                SongOutcome.Queued(offline)
            }
            QueueAddResult.Full -> SongOutcome.QueueFull(queue.size(nowMillis()), offline)
            QueueAddResult.WriteFailed -> SongOutcome.QueueFailed(offline)
        }
    }
}

/**
 * Says how a tier-2 run ended, after it ended (nothing is spoken while GAR listens): the answer,
 * starting with [noticeText] the first time a recording reached the service. Nothing after Stop,
 * which the user tapped.
 */
class SongRunEnd(
    /** Whether the network notice was spoken before (`network_notice_spoken`). */
    private val noticeSpoken: Boolean,
    private val markNoticeSpoken: suspend () -> Unit,
    private val noticeText: () -> String,
    private val answerText: (SongOutcome) -> String,
    private val speak: suspend (String) -> Unit,
) {
    suspend fun finish(outcome: SongOutcome) {
        if (outcome is SongOutcome.Stopped) {
            return
        }
        val notice = !noticeSpoken && outcome.sentToService
        if (notice) {
            markNoticeSpoken()
        }
        val answer = answerText(outcome)
        speak(if (notice) noticeText() + " " + answer else answer)
    }
}

/** The tier-2 run, one at a time: two would record each other. */
class SongRunner(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var current: SongRecognitionRun? = null

    /** Whether a run is recording or sending: then the skill neither speaks nor starts another. */
    val isActive: Boolean
        @Synchronized get() = job?.isActive == true

    /** @return false if a run is still active */
    @Synchronized
    fun start(run: SongRecognitionRun, end: SongRunEnd): Boolean {
        if (isActive) {
            return false
        }
        current = run
        job = scope.launch {
            end.finish(run.run())
        }
        return true
    }

    /** Stops [run] if it is the active one: nothing more is recorded or sent. */
    @Synchronized
    fun stop(run: SongRecognitionRun) {
        if (current === run) {
            job?.cancel()
        }
    }
}
