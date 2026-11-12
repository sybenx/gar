package org.stypox.dicio.skills.song

import org.stypox.dicio.gar.ui.garble.GarblePrimaryButton // GAR
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.util.getString
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/** A text of the song skill as a string resource and its arguments, so that tests can check it. */
data class SongText(@StringRes val id: Int, val args: List<Any> = listOf()) {
    fun resolve(context: Context): String = context.getString(id, *args.toTypedArray())
}

/** The texts of the song skill's answers. */
object SongMessages {
    /**
     * The text for [failure] of [provider].
     * @param final the clip is gone (a notification after giving up), so "try again" makes no sense
     */
    fun failure(
        failure: RecognitionResult.Failure,
        provider: SongProviderKind,
        final: Boolean = false,
    ): SongText {
        val name = provider.displayName
        return when (failure) {
            RecognitionResult.BadToken -> SongText(
                if (provider == SongProviderKind.ACRCLOUD) R.string.skill_song_error_bad_key
                else R.string.skill_song_error_bad_token,
                listOf(name),
            )
            RecognitionResult.BadHost -> SongText(R.string.skill_song_error_bad_host, listOf(name))
            RecognitionResult.BadSettings ->
                SongText(R.string.skill_song_error_bad_settings, listOf(name))
            RecognitionResult.LimitReached -> SongText(R.string.skill_song_error_limit, listOf(name))
            RecognitionResult.BadRecording ->
                SongText(R.string.skill_song_error_bad_recording, listOf(name))
            RecognitionResult.NoConnection ->
                SongText(R.string.skill_song_error_no_connection, listOf(name))
            is RecognitionResult.HttpError ->
                SongText(R.string.skill_song_error_http, listOf(name, failure.code))
            RecognitionResult.ServiceDown, is RecognitionResult.ServiceError -> SongText(
                if (final) R.string.skill_song_error_service_final
                else R.string.skill_song_error_service,
                listOf(name),
            )
        }
    }

    fun thatIs(title: String, artist: String?): SongText = if (artist == null) {
        SongText(R.string.skill_song_that_is_no_artist, listOf(title))
    } else {
        SongText(R.string.skill_song_that_is, listOf(title, artist))
    }

    @StringRes
    fun micFailure(reason: RecordingResult.Failure): Int = when (reason) {
        RecordingResult.Failure.NO_PERMISSION -> R.string.skill_song_no_mic_permission
        RecordingResult.Failure.MIC_UNAVAILABLE -> R.string.skill_song_mic_unavailable
        RecordingResult.Failure.MIC_SILENCED -> R.string.skill_song_mic_silenced
    }

    /**
     * A clip that couldn't be sent now: [whenOffline] without a network, else [whenUnreachable],
     * which names the service first.
     */
    private fun queued(
        offline: Boolean,
        provider: SongProviderKind,
        @StringRes whenOffline: Int,
        @StringRes whenUnreachable: Int,
        vararg args: Any,
    ): SongText = if (offline) {
        SongText(whenOffline, args.toList())
    } else {
        SongText(whenUnreachable, listOf(provider.displayName) + args)
    }

    fun outcome(outcome: SongOutcome, provider: SongProviderKind): SongText = when (outcome) {
        is SongOutcome.Found -> thatIs(outcome.track.title, outcome.track.artist)
        SongOutcome.NotFound -> SongText(R.string.skill_song_not_recognized)
        is SongOutcome.Queued -> queued(
            outcome.offline, provider,
            R.string.skill_song_offline_queued, R.string.skill_song_unreachable_queued,
        )
        is SongOutcome.QueueFull -> queued(
            outcome.offline, provider,
            R.string.skill_song_queue_full, R.string.skill_song_unreachable_queue_full,
            outcome.waiting,
        )
        is SongOutcome.QueueFailed -> queued(
            outcome.offline, provider,
            R.string.skill_song_queue_failed, R.string.skill_song_unreachable_queue_failed,
        )
        is SongOutcome.NoNotifications -> queued(
            outcome.offline, provider,
            R.string.skill_song_offline_no_notifications,
            R.string.skill_song_unreachable_no_notifications,
        )
        SongOutcome.Silence -> SongText(R.string.skill_song_silence)
        is SongOutcome.MicFailure -> SongText(micFailure(outcome.reason))
        is SongOutcome.Failed -> failure(outcome.failure, provider)
        is SongOutcome.Stopped -> SongText(
            if (outcome.whileSending) R.string.skill_song_stopped_sending
            else R.string.skill_song_stopped
        )
        SongOutcome.Error -> SongText(R.string.skill_song_error)
    }

    /** Why a queued clip was dropped without being sent. */
    @StringRes
    fun dropped(reason: SongDropReason): Int = when (reason) {
        SongDropReason.NO_SERVICE -> R.string.skill_song_notification_no_service
        SongDropReason.SERVICE_CHANGED -> R.string.skill_song_notification_service_changed
        SongDropReason.EXPIRED -> R.string.skill_song_notification_expired
    }

    /** Nothing can recognize the song, and GAR doesn't ask about Audile: it needs Audile. */
    @StringRes
    fun nothingAvailable(nowPlaying: NowPlaying): Int = when (nowPlaying) {
        NowPlaying.NoAccess -> R.string.skill_song_no_listener_access
        NowPlaying.Untitled -> R.string.skill_song_untitled_playing
        else -> R.string.skill_song_nothing_playing
    }

    /** What the question about installing Audile adds on its card about the phone's player. */
    @StringRes
    fun installHint(nowPlaying: NowPlaying): Int? = when (nowPlaying) {
        NowPlaying.NoAccess -> R.string.skill_song_install_hint_listener
        NowPlaying.Untitled -> R.string.skill_song_install_hint_untitled
        else -> null
    }

    /** The answer after a yes to installing Audile. */
    fun install(result: AudileInstallResult): SongText = when (result) {
        is AudileInstallResult.Opened -> when (result.target.source) {
            AudileSource.OBTAINIUM -> SongText(R.string.skill_song_install_obtainium)
            AudileSource.FDROID -> SongText(
                R.string.skill_song_install_store,
                listOf(result.target.handler?.label ?: "F-Droid"),
            )
            AudileSource.GITHUB -> SongText(R.string.skill_song_install_github)
        }
        AudileInstallResult.BlockedInBackground -> SongText(R.string.skill_song_install_background)
        AudileInstallResult.Failed -> SongText(R.string.skill_song_install_failed)
    }

    /** The answer after handing off to Audile. */
    @StringRes
    fun audile(result: AudileResult): Int = when (result) {
        AudileResult.LISTENING_IN_BACKGROUND -> R.string.skill_song_audile_listening
        AudileResult.OPENED -> R.string.skill_song_opening_audile
        AudileResult.OPENED_LOCKED -> R.string.skill_song_opening_audile_locked
        AudileResult.OPENED_APP -> R.string.skill_song_opening_audile_app
        AudileResult.BLOCKED_IN_BACKGROUND -> R.string.skill_song_audile_background
        AudileResult.FAILED -> R.string.skill_song_audile_failed
    }

    /**
     * Whether GAR says the answer after handing off to Audile, or only shows it: Audile records
     * at once after the background shortcut and the RECOGNIZE intent, and GAR's voice would end
     * up in its recording. On a locked phone Audile's screen waits behind the lock screen, so
     * "Unlock your phone to see it." is said.
     */
    fun speaksAudile(result: AudileResult): Boolean =
        result != AudileResult.LISTENING_IN_BACKGROUND && result != AudileResult.OPENED

    /** "2014-11-10" in the locale's medium form, or as given ("1985"). */
    fun releaseDate(raw: String, locale: Locale): String = try {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(LocalDate.parse(raw))
    } catch (e: DateTimeParseException) {
        raw
    }
}

/** A plain answer. [speak] false shows it without saying it (Audile is listening). */
class SongMessageOutput(private val text: String, private val speak: Boolean = true) :
    HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = if (speak) text else ""

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Headline(text = text)
    }
}

/** An answer as a [SongText], resolved when it is said, so that tests can check which one. */
class SongTextOutput(val text: SongText) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = text.resolve(ctx.android)
}

/**
 * Nothing can recognize the song, and GAR doesn't ask about Audile (the user said no, the phone
 * is locked, or the language isn't English): says that GAR needs Audile and where to get it,
 * without a question. The card adds the recognition services and a button that opens Audile's
 * install page.
 */
class AudileMissingOutput(val nowPlaying: NowPlaying) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(SongMessages.nothingAvailable(nowPlaying))

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        Column {
            Headline(text = getSpeechOutput(ctx))
            Spacer(modifier = Modifier.height(8.dp))
            Body(text = stringResource(R.string.skill_song_install_services))
            Spacer(modifier = Modifier.height(8.dp))
            GarbleSecondaryButton( // GAR
                onClick = { openAudileInstall(context) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.skill_song_install_yes))
            }
        }
    }
}

/**
 * "I can recognize songs with Audile, a free and open source app. Want to install it?", asked
 * when nothing else can recognize the song, until the user says no (see [AudileInstallPrompt]).
 * The card adds where Audile comes from and what GAR does with it, and No thanks / Install Audile
 * buttons, which send their label as input like the online skills' question, so that both go
 * through [AudileInstallAnswerSkill]. Any other input leaves the question unanswered: GAR
 * remembers nothing and asks again next time.
 *
 * @param skillInfo the song skill's, for the answer skill
 * @param rankers Dicio's current ranker, which it builds again when the skills' switches change;
 * null in tests
 */
class AudileInstallAskOutput(
    private val skillInfo: SkillInfo,
    val nowPlaying: NowPlaying,
    private val host: AudileInstallHost,
    private val rankers: StateFlow<SkillRanker>?,
) : SkillOutput {

    /**
     * Dicio's ranker when GAR asked, which gets [AudileInstallAnswerSkill]: in a ranker built
     * later the buttons would send their label as a new command.
     */
    val askedRanker: SkillRanker? = rankers?.value

    /** Set once the user answered, to disable the buttons. */
    var answered by mutableStateOf(false)

    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_song_install_ask)

    /** The skills that wait for the answer; [yesLabel] and [noLabel] are the buttons' labels. */
    fun nextSkills(yesLabel: String, noLabel: String): List<Skill<*>> = listOf(
        AudileInstallAnswerSkill(skillInfo, host, yesLabel, noLabel) { answered = true },
    )

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = nextSkills(
                ctx.getString(R.string.skill_song_install_yes),
                ctx.getString(R.string.skill_song_install_no),
            ),
        )

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        val skillEvaluator = remember {
            EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
                .skillEvaluator()
        }
        // the buttons only make sense while this card is the latest answer, and its answer skill
        // still waits in the ranker
        val log by skillEvaluator.state.collectAsState()
        val ranker by remember { rankers ?: MutableStateFlow<SkillRanker?>(null) }.collectAsState()
        // after the model's confirmation the song skill's output is wrapped
        val latest = log.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer
        val isLatest = latest === this || (latest as? GarFollowUpOutput)?.output === this
        val enabled = isLatest && !answered && ranker === askedRanker

        val yes = stringResource(R.string.skill_song_install_yes)
        val no = stringResource(R.string.skill_song_install_no)
        val send = { label: String ->
            answered = true
            skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(label, 1.0f))))
        }

        Column {
            Headline(text = stringResource(R.string.skill_song_install_ask))
            Spacer(modifier = Modifier.height(8.dp))
            Body(text = stringResource(R.string.skill_song_install_detail))
            SongMessages.installHint(nowPlaying)?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Body(text = stringResource(it))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GarbleSecondaryButton(onClick = { send(no) }, enabled = enabled) { // GAR
                    Text(text = no)
                }
                GarblePrimaryButton(onClick = { send(yes) }, enabled = enabled) { // GAR
                    Text(text = yes)
                }
            }
        }
    }
}

/** Tier 1: the track the phone plays. */
class SongPlayingOutput(val track: PlayingTrack) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        SongMessages.thatIs(track.title, track.artist).resolve(ctx.android)

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = track.title)
            track.artist?.let { Subtitle(text = it) }
            track.album?.let { Body(text = it) }
            track.appName?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Body(text = ctx.getString(R.string.skill_song_playing_in, it))
            }
        }
    }
}

/**
 * Tier 2: says nothing at first, so that GAR's voice isn't recorded, and shows the recording's
 * progress ("Listening...", 10 s) and where it goes, with a Stop button that drops the recording
 * (nothing is sent while listening). The card then shows the result, which [SongSkill] speaks
 * once the run ends.
 */
class SongListeningOutput(
    private val run: SongRecognitionRun,
    private val onStop: () -> Unit,
) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ""

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val state by run.state.collectAsState()
        val providerName = run.provider.displayName
        when (val s = state) {
            is SongCardState.Listening -> Column {
                Headline(text = stringResource(R.string.skill_song_listening))
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Body(text = stringResource(R.string.skill_song_sends_recording, providerName))
                StopButton(onStop)
            }
            SongCardState.Recognizing -> Column {
                Headline(text = stringResource(R.string.skill_song_recognizing))
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Body(text = stringResource(R.string.skill_song_sends_recording, providerName))
                StopButton(onStop)
            }
            is SongCardState.Done -> when (val outcome = s.outcome) {
                is SongOutcome.Found -> SongTrackCard(outcome.track, ctx.locale)
                is SongOutcome.NoNotifications -> Column {
                    Headline(text = SongMessages.outcome(outcome, run.provider.kind)
                        .resolve(LocalContext.current))
                    Spacer(modifier = Modifier.height(8.dp))
                    val allow = rememberAllowSongNotifications {}
                    GarbleSecondaryButton( // GAR
                        onClick = allow,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(stringResource(R.string.skill_song_allow_notifications))
                    }
                }
                else -> Headline(
                    text = SongMessages.outcome(outcome, run.provider.kind)
                        .resolve(LocalContext.current)
                )
            }
        }
    }
}

/**
 * Tier 3: the hand-off to Audile ([text], said if [speak]), then the song once Audile's result
 * notification comes ([SongSkill] says it), or how to let GAR hear it.
 */
class SongAudileOutput(
    private val text: String,
    private val speak: Boolean,
    private val packageName: String,
    private val state: StateFlow<AudileCardState>,
) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = if (speak) text else ""

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val current by state.collectAsState()
        val context = LocalContext.current
        when (val s = current) {
            is AudileCardState.Waiting -> Column {
                Headline(text = text)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                AudileHintsCard(s.hints, packageName)
            }
            is AudileCardState.Found -> Column {
                SongTrackCard(s.result.track, ctx.locale)
                Spacer(modifier = Modifier.height(8.dp))
                GarbleSecondaryButton( // GAR
                    onClick = { openAudileTrack(context, s.result, packageName) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.skill_song_open_in_audile))
                }
            }
            is AudileCardState.Ended -> Column {
                Headline(text = text)
                AudileHintsCard(s.hints, packageName)
            }
        }
    }
}

@Composable
private fun ColumnScope.AudileHintsCard(hints: AudileHints, packageName: String) {
    val context = LocalContext.current
    if (hints.needsListener) {
        Spacer(modifier = Modifier.height(8.dp))
        Body(text = stringResource(R.string.skill_song_audile_needs_listener))
        Spacer(modifier = Modifier.height(8.dp))
        GarbleSecondaryButton( // GAR
            onClick = { openListenerSettings(context) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.skill_song_allow_access))
        }
    }
    if (hints.foregroundHint) {
        Spacer(modifier = Modifier.height(8.dp))
        Body(text = stringResource(R.string.skill_song_audile_foreground_hint))
        Spacer(modifier = Modifier.height(8.dp))
        GarbleSecondaryButton( // GAR
            onClick = { openAudileForegroundSettings(context, packageName) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.skill_song_turn_on))
        }
    }
}

@Composable
private fun ColumnScope.StopButton(onStop: () -> Unit) {
    Spacer(modifier = Modifier.height(8.dp))
    GarbleSecondaryButton(onClick = onStop, modifier = Modifier.align(Alignment.CenterHorizontally)) { // GAR
        Text(stringResource(R.string.skill_song_stop))
    }
}

/** A recognized song: title, artist, album, release date and links to open it. */
@Composable
fun SongTrackCard(track: SongTrack, locale: Locale) {
    val context = LocalContext.current
    Column {
        Headline(text = track.title)
        Subtitle(text = track.artist)
        track.album?.let { Body(text = it) }
        track.releaseDate?.let {
            Body(text = stringResource(R.string.skill_song_released, SongMessages.releaseDate(it, locale)))
        }
        if (track.spotifyUrl != null || track.youtubeUrl != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                track.spotifyUrl?.let { url ->
                    GarbleSecondaryButton(onClick = { openLink(context, url) }) { // GAR
                        Text(stringResource(R.string.skill_song_open_spotify))
                    }
                }
                track.youtubeUrl?.let { url ->
                    GarbleSecondaryButton(onClick = { openLink(context, url) }) { // GAR
                        Text(stringResource(R.string.skill_song_open_youtube))
                    }
                }
            }
        }
    }
}

/** Opens [url] in the app that handles it (Spotify, YouTube), or the browser. */
private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Log.w("SongTrackCard", "Nothing opens $url", e)
    }
}
