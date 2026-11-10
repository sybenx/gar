package org.stypox.dicio.skills.media

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.SlotResolvers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.R
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.gar.media.AndroidMediaSessions
import org.stypox.dicio.gar.media.GarMediaControls
import org.stypox.dicio.gar.media.GarMediaSessions
import org.stypox.dicio.gar.media.GarSleepTimer
import org.stypox.dicio.gar.media.MediaCommand
import org.stypox.dicio.gar.media.MediaSessions
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Media
import org.stypox.dicio.sentences.Sentences.MediaControls
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

object MediaControlsInfo : SkillInfo("media_controls") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_media_controls)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_media_controls)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Shuffle)

    // no permissions: the media sessions need notification access, a special access that the
    // answers offer to open (as the song skill's do); the sleep timer's exact alarm is GAR's
    // USE_EXACT_ALARM, which the timers already hold

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.MediaControls[ctx.sentencesLanguage] ?: return null
        return MediaControlsSkill(MediaControlsInfo, data)
    }
}

/**
 * Media controls beyond Dicio's media skill (gar/PARITY.md, batch 4): shuffle on or off, repeat
 * one, all or off, seek forward or back, restart the track, like or dislike, and "stop" while
 * something plays; Dicio's play, pause, next and previous in words its sentences miss ("keep
 * playing", "go back a song", "play something else"); and the media sleep timer ("stop the music
 * in thirty minutes"). English sentences only, no tool. The controls go to the media session of the app that plays
 * ([GarMediaControls]); the sleep timer is [GarSleepTimer].
 *
 * Dicio's matcher takes a sentence with a word left out, so a match only counts when the input
 * is one of [MediaControlWords]' phrases as a whole. Phrases that Dicio's own sentences also take
 * ("restart the song" resumes in Dicio's media skill, "set a sleep timer for an hour" is a timer
 * called sleep, "cancel the sleep timer" cancels it) score [AlwaysBestScore], and so does "stop"
 * while something plays and nothing rings, which GAR's conversation would take ("Stop: who
 * takes it" in gar/DESIGN.md: after a ringing timer and the clock app's alarm, before GAR's
 * speech).
 *
 * @param sessions the phone's media sessions
 * @param sleepTimer the app's sleep timer
 * @param isPlaying whether something plays now (needs notification access), see
 * [GarMediaSessions.isPlaying]; answers at once
 * @param isRinging whether a timer or an alarm rings ([GarRinging])
 * @param stopSpeaking stops what GAR says, for a typed "stop" (listening stopped a spoken one)
 * @param now the wall clock, for when the sleep timer ends
 * @param pressKey sends Dicio's media key for play, pause, next or previous, see [pressMediaKey]
 */
class MediaControlsSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<MediaControls>,
    private val sessions: (SkillContext) -> MediaSessions = { AndroidMediaSessions(it.android) },
    private val sleepTimer: (SkillContext) -> GarSleepTimer = { GarSleepTimer.get(it.android) },
    private val isPlaying: () -> Boolean = GarMediaSessions::isPlaying,
    private val isRinging: () -> Boolean = GarRinging::isRinging,
    private val stopSpeaking: suspend (SkillContext) -> Unit = { ctx ->
        withContext(Dispatchers.Main) { ctx.speechOutputDevice.stopSpeaking() }
    },
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val pressKey: (SkillContext, Media) -> SkillOutput = ::pressMediaKey,
) : Skill<MediaControlRequest?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, MediaControlRequest?> {
        if (MediaControlWords.isBareStop(input)) {
            // only checked for these words: it asks the media sessions
            val takes = !isRinging() && isPlaying()
            return Pair(
                if (takes) AlwaysBestScore else AlwaysWorstScore,
                MediaControlRequest.Control(MediaCommand.Pause),
            )
        }
        val match = MediaControlWords.parse(input) { durationOf(ctx, it) }
        if (match == null || (match.whilePlaying && !isPlaying())) {
            return Pair(AlwaysWorstScore, null)
        }
        val (score, _) = data.score(ctx, input)
        return Pair(if (takesFromDicio(match.request)) AlwaysBestScore else score, match.request)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: MediaControlRequest?): SkillOutput {
        return when (val request = inputData
            ?: throw IllegalArgumentException("AlwaysWorstScore still triggered generateOutput")) {
            is MediaControlRequest.Control -> {
                if (request.command == MediaCommand.Pause) {
                    stopSpeaking(ctx)
                }
                MediaAnswers.control(GarMediaControls.perform(request.command, sessions(ctx)))
            }
            is MediaControlRequest.Key -> pressKey(ctx, request.action)
            is MediaControlRequest.SleepSet -> request.millis?.let { setSleepTimer(ctx, it) }
                ?: MediaSleepAskOutput(SleepDurationSkill(correspondingSkillInfo) { duration ->
                    durationOf(ctx, duration)
                })
            MediaControlRequest.SleepCancel -> if (sleepTimer(ctx).cancel()) {
                MediaAnswers.sleepCancelled()
            } else {
                MediaAnswers.sleepNone()
            }
            MediaControlRequest.SleepQuery -> sleepTimer(ctx).remainingMillis()
                ?.let { MediaAnswers.sleepLeft(it) }
                ?: MediaAnswers.sleepNone()
        }
    }

    /** Starts a sleep timer of [millis], replacing the one that ran. */
    fun setSleepTimer(ctx: SkillContext, millis: Long): SkillOutput {
        if (millis > MAX_SLEEP_MILLIS) {
            return MediaAnswers.sleepTooLong()
        }
        sleepTimer(ctx).set(millis)
        val endsAt: LocalTime = now().plus(Duration.ofMillis(millis)).toLocalTime()
        return MediaAnswers.sleepSet(Duration.ofMillis(millis), endsAt)
    }

    /**
     * The answer to "For how long?": a duration, which sets the sleep timer. Anything else isn't
     * an answer, and goes to the other skills.
     */
    private inner class SleepDurationSkill(
        info: SkillInfo,
        private val durationOf: (String) -> Long?,
    ) : Skill<Long?>(info, Specificity.HIGH) {
        override fun score(ctx: SkillContext, input: String): Pair<Score, Long?> {
            val millis = MediaControlWords.sleepAnswer(input, durationOf)
            return Pair(if (millis == null) AlwaysWorstScore else AlwaysBestScore, millis)
        }

        override suspend fun generateOutput(ctx: SkillContext, inputData: Long?): SkillOutput =
            setSleepTimer(
                ctx,
                inputData ?: throw IllegalArgumentException("AlwaysWorstScore still triggered generateOutput"),
            )
    }

    companion object {
        /** A sleep timer runs for up to a day. */
        const val MAX_SLEEP_MILLIS = 24 * 60 * 60 * 1000L

        /**
         * The requests whose words Dicio's own sentences also take: "restart the song" (the media
         * skill's play), "set a sleep timer for an hour" and "cancel the sleep timer" (a timer
         * called sleep).
         */
        fun takesFromDicio(request: MediaControlRequest): Boolean =
            request !is MediaControlRequest.Control || request.command == MediaCommand.Restart

        /**
         * Sends the media key of [action] and answers like Dicio's media skill, which does the
         * same for its own sentences: the player that has the media buttons takes it.
         */
        fun pressMediaKey(ctx: SkillContext, action: Media): SkillOutput {
            val audioManager = ctx.android.getSystemService(AudioManager::class.java)
                ?: return MediaOutput(performedAction = null)
            val key = when (action) {
                is Media.Play -> KeyEvent.KEYCODE_MEDIA_PLAY
                is Media.Pause -> KeyEvent.KEYCODE_MEDIA_PAUSE
                is Media.Previous -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                is Media.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
            }
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            return MediaOutput(performedAction = action)
        }

        /** [raw] as a duration in milliseconds, read by GAR's resolvers; null if it is none. */
        fun durationOf(ctx: SkillContext, raw: String): Long? =
            when (val resolution = SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage).duration(raw)) {
                is Resolution.Resolved -> resolution.value.toMillis()
                else -> null
            }
    }
}
