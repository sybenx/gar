package org.stypox.dicio.skills.song

import android.content.Context
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.R
import org.stypox.dicio.gar.conversation.GarInputMode // GAR
import org.stypox.dicio.gar.conversation.GarSpeechMode // GAR
import org.stypox.dicio.gar.conversation.GarTurnElement // GAR
import org.stypox.dicio.sentences.Sentences.Song
import java.time.Instant

/**
 * "What song is this?", in tiers (gar/DESIGN.md, "Step 4: song"):
 * 1. something plays on the phone: its media session's title and artist (offline, needs the
 *    notification access Dicio's notification skill already asks for, else skipped);
 * 2. a service is set up: record 10 s and send them to AudD or ACRCloud with the user's token,
 *    or queue the clip when offline (the skill's only network access, opted in by the token);
 *    the card has a Stop button, and nothing is spoken while GAR records;
 * 3. Audile is installed: hand off to it, and say the song when Audile's result notification
 *    comes (needs notification access too);
 * otherwise ask once whether to install Audile, or say that GAR needs it and where to get it.
 */
class SongSkill(correspondingSkillInfo: SkillInfo, data: StandardRecognizerData<Song>) :
    StandardRecognizerSkill<Song>(correspondingSkillInfo, data) {

    // In English, only an input that names a song and points at what plays (see [SongWords]):
    // Dicio scores a sentence with a word left out or swapped above its thresholds, so "what is
    // this" matched "what is this song", "who is this" "who is singing this" and "what's the next
    // song" "what's the song", also with GAR's model off.
    override fun score(ctx: SkillContext, input: String): Pair<Score, Song> {
        val (score, sentence) = super.score(ctx, input)
        val takes = !isEnglish(ctx) || SongWords.asksForSong(input)
        return Pair(if (takes) score else AlwaysWorstScore, sentence)
    }

    private fun isEnglish(ctx: SkillContext): Boolean =
        ctx.sentencesLanguage.substringBefore('-').substringBefore('_') == "en"

    override suspend fun generateOutput(ctx: SkillContext, inputData: Song): SkillOutput {
        val context = ctx.android
        if (songRunner.isActive) {
            return alreadyListening(context)
        }
        val settings = context.songSettingsDataStore.data.first()
        // GAR: the late answers are said only if this turn was spoken, whatever comes in meanwhile
        val mode = GarTurnElement.current()?.mode
        val plan = SongLogic.plan(
            nowPlaying = SongDevice.nowPlaying(context),
            provider = SongProviderConfig.from(settings),
            audilePackage = AndroidAudileLauncher.installedAudile(context),
        )
        return when (plan) {
            is SongPlan.Answer -> SongPlayingOutput(plan.track)
            is SongPlan.Record -> record(ctx, plan.provider, settings.networkNoticeSpoken, mode)
            is SongPlan.OpenAudile -> openAudile(ctx, plan.packageName, settings, mode)
            is SongPlan.NothingAvailable -> audileMissing(ctx, plan.nowPlaying, settings)
        }
    }

    /**
     * Nothing can recognize the song: GAR asks whether to install Audile until the user says no
     * ([AudileInstallPrompt]), else it says that it needs Audile and where to get it.
     */
    private fun audileMissing(
        ctx: SkillContext,
        nowPlaying: NowPlaying,
        settings: SkillSettingsSong,
    ): SkillOutput {
        val context = ctx.android
        val host = AndroidAudileInstallHost(context)
        if (!AudileInstallPrompt.asks(
                declined = settings.audileInstallDeclined,
                locked = host.isDeviceLocked(),
                english = isEnglish(ctx),
            )
        ) {
            return AudileMissingOutput(nowPlaying)
        }
        val rankers = EntryPointAccessors
            .fromApplication(context.applicationContext, SongEntryPoint::class.java)
            .skillHandler().skillRanker
        return AudileInstallAskOutput(correspondingSkillInfo, nowPlaying, host, rankers)
    }

    private fun record(
        ctx: SkillContext,
        provider: SongProviderConfig,
        noticeSpoken: Boolean,
        mode: GarInputMode?, // GAR
    ): SkillOutput {
        val context = ctx.android
        val run = SongRecognitionRun(
            provider = provider,
            recorder = AndroidSongRecorder(context),
            recognizer = SongRecognitionClient.forContext(context),
            queue = SongClipQueue.forContext(context),
            isOnline = { SongDevice.isOnline(context) },
            onQueued = { SongQueueWorker.schedule(context) },
            onError = { Log.e(TAG, "Song recognition failed", it) },
            canNotify = { SongNotifications.canShow(context) },
        )
        val end = SongRunEnd(
            noticeSpoken = noticeSpoken,
            markNoticeSpoken = { markNoticeSpoken(context) },
            noticeText = {
                context.getString(R.string.skill_song_sent_recording, provider.displayName)
            },
            answerText = { SongMessages.outcome(it, provider.kind).resolve(context) },
            speak = { text -> // GAR: in this turn's mode
                withContext(Dispatchers.Main) {
                    GarSpeechMode.speak(ctx.speechOutputDevice, mode, text)
                }
            },
        )
        if (!songRunner.start(run, end)) {
            return alreadyListening(context)
        }
        return SongListeningOutput(run) { songRunner.stop(run) }
    }

    /**
     * Tier 3: hands off to Audile, then waits for its result notification and says the song, so
     * that it works without looking (see [AudileResults]).
     */
    private fun openAudile(
        ctx: SkillContext,
        packageName: String,
        settings: SkillSettingsSong,
        mode: GarInputMode?, // GAR
    ): SkillOutput {
        val context = ctx.android
        val handOff = Instant.now()
        val result = AudileHandOff.run(packageName, AndroidAudileLauncher(context))
        val text = context.getString(SongMessages.audile(result))
        val speak = SongMessages.speaksAudile(result)
        if (!AudileWaits.waitsFor(result)) {
            return SongMessageOutput(text, speak)
        }

        val hints = AudileWaits.hints(
            result = result,
            hearsResults = SongDevice.hasListenerAccess(context),
            foregroundHeard = settings.audileForegroundHeard,
        )
        val state = MutableStateFlow(AudileWaits.initial(hints))
        if (state.value is AudileCardState.Waiting) {
            audileWaiter.start {
                val posted = try {
                    AudileResults.await(handOff, AudileWaits.TIMEOUT)
                } catch (e: CancellationException) {
                    state.value = AudileCardState.Ended(hints) // a newer hand-off dropped it
                    throw e
                }
                if (posted == null) {
                    state.value = AudileCardState.Ended(hints)
                    return@start
                }
                if (posted.foreground && !settings.audileForegroundHeard) {
                    markForegroundHeard(context)
                }
                state.value = AudileCardState.Found(posted.result)
                val track = posted.result.track
                val answer = SongMessages.thatIs(track.title, track.artist.ifEmpty { null })
                withContext(Dispatchers.Main) { // GAR: in this turn's mode
                    GarSpeechMode.speak(ctx.speechOutputDevice, mode, answer.resolve(context))
                }
            }
        }
        return SongAudileOutput(text, speak, packageName, state)
    }

    /** Not said: GAR's voice would end up in the recording, and the card shows it. */
    private fun alreadyListening(context: Context) =
        SongMessageOutput(context.getString(R.string.skill_song_already_listening), speak = false)

    private suspend fun markNoticeSpoken(context: Context) {
        try {
            context.songSettingsDataStore.updateData {
                it.toBuilder().setNetworkNoticeSpoken(true).build()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't save the network notice", e)
        }
    }

    private suspend fun markForegroundHeard(context: Context) {
        try {
            context.songSettingsDataStore.updateData {
                it.toBuilder().setAudileForegroundHeard(true).build()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't save that Audile's foreground results are on", e)
        }
    }

    companion object {
        private const val TAG = "SongSkill"

        /** The tier-2 run, one at a time, in its own scope: it outlives the evaluation. */
        private val songRunner = SongRunner(
            CoroutineScope(
                SupervisorJob() + Dispatchers.Default +
                    CoroutineExceptionHandler { _, e -> Log.e(TAG, "Song recognition failed", e) }
            )
        )

        /** Waits for Audile's answer: outlives the evaluation, like [songRunner]. */
        private val audileWaiter = AudileWaiter(
            CoroutineScope(
                SupervisorJob() + Dispatchers.Default +
                    CoroutineExceptionHandler { _, e -> Log.e(TAG, "Waiting for Audile failed", e) }
            )
        )
    }
}
