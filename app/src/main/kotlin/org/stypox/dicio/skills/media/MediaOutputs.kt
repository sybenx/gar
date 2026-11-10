package org.stypox.dicio.skills.media

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarValueFormatter
import org.stypox.dicio.gar.media.ControlOutcome
import org.stypox.dicio.gar.media.GarSleepTimer
import org.stypox.dicio.gar.media.MediaCommand
import org.stypox.dicio.gar.media.MediaPlayRequest
import org.stypox.dicio.gar.media.PlayOutcome
import org.stypox.dicio.gar.media.RepeatMode
import org.stypox.dicio.gar.ui.GarChip
import org.stypox.dicio.gar.ui.GarChipStrings
import org.stypox.dicio.gar.ui.GarChipTable
import org.stypox.dicio.gar.ui.GarSuggestions
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.skills.song.openListenerSettings
import org.stypox.dicio.util.getString
import java.time.Duration
import java.time.LocalTime
import kotlin.math.abs
import org.dicio.numbers.unit.Duration as DicioDuration

/**
 * A text of the media skills as a string resource and its arguments, so that tests can check it.
 * A [Duration] argument reads as a duration ("thirty seconds"), a [LocalTime] as a time of day.
 */
data class MediaText(@StringRes val id: Int, val args: List<Any> = listOf()) {
    fun resolve(ctx: SkillContext, speech: Boolean): String =
        ctx.getString(id, *args.map { format(ctx, it, speech) }.toTypedArray())

    private fun format(ctx: SkillContext, arg: Any, speech: Boolean): Any = when (arg) {
        is Duration -> ctx.parserFormatter?.niceDuration(DicioDuration(arg))?.speech(true)?.get()
            ?: arg.toString()
        is LocalTime -> GarValueFormatter.forContext(ctx).format(arg, speech) ?: arg.toString()
        else -> arg
    }
}

/** A button on a media card. */
enum class MediaButton { ALLOW_ACCESS, CANCEL_SLEEP_TIMER }

/**
 * An answer of the media skills: [headline], said unless [speak] is false, and on the card
 * maybe a [detail] and a [button].
 */
data class GarMediaOutput(
    val headline: MediaText,
    val speak: Boolean = true,
    val detail: MediaText? = null,
    val button: MediaButton? = null,
) : SkillOutput, GarSuggestions {
    override fun getSpeechOutput(ctx: SkillContext): String =
        if (speak) headline.resolve(ctx, speech = true) else ""

    /** Something started playing (true), was paused (false), or neither is known (null). */
    val playing: Boolean?
        get() = when (headline.id) {
            R.string.skill_media_play_anything_on, R.string.skill_media_play_playing_on -> true
            R.string.skill_media_paused -> false
            else -> null
        }

    override fun suggestions(ctx: SkillContext): List<GarChip> =
        if (button != null) listOf() else GarChipTable.media(GarChipStrings.of(ctx), playing)

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        var cancelled by remember { mutableStateOf(false) }
        Column {
            Headline(text = headline.resolve(ctx, speech = false))
            detail?.takeIf { !cancelled }?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Body(text = it.resolve(ctx, speech = false))
            }
            if (cancelled) {
                Spacer(modifier = Modifier.height(8.dp))
                Body(text = stringResource(R.string.skill_media_sleep_cancelled))
            }
            when (button.takeIf { !cancelled }) {
                MediaButton.ALLOW_ACCESS -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { openListenerSettings(context) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(text = stringResource(R.string.skill_media_allow_access))
                    }
                }
                MediaButton.CANCEL_SLEEP_TIMER -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            GarSleepTimer.get(context).cancel()
                            cancelled = true
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                }
                null -> {}
            }
        }
    }
}

/** "For how long?" about a sleep timer; [answer] takes the duration. */
class MediaSleepAskOutput(private val answer: Skill<*>) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_media_sleep_ask)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(reopenMicrophone = true, nextSkills = listOf(answer))

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Headline(text = getSpeechOutput(ctx))
    }
}

/** The answers of the media skills, as [GarMediaOutput]s. */
object MediaAnswers {
    fun play(outcome: PlayOutcome): GarMediaOutput = when (outcome) {
        is PlayOutcome.Playing -> GarMediaOutput(
            if (outcome.request.said.isEmpty()) {
                MediaText(R.string.skill_media_play_anything_on, listOf(outcome.app))
            } else {
                MediaText(R.string.skill_media_play_playing_on, listOf(spoken(outcome.request), outcome.app))
            }
        )
        is PlayOutcome.AskedApp -> GarMediaOutput(
            if (outcome.request.said.isEmpty()) {
                MediaText(R.string.skill_media_play_asked_anything, listOf(outcome.app))
            } else {
                MediaText(R.string.skill_media_play_asked, listOf(spoken(outcome.request), outcome.app))
            }
        )
        is PlayOutcome.Choosing -> GarMediaOutput(
            MediaText(R.string.skill_media_play_choose),
            detail = MediaText(R.string.skill_media_play_choose_hint),
        )
        is PlayOutcome.NotInstalled ->
            GarMediaOutput(MediaText(R.string.skill_media_play_not_installed, listOf(capitalized(outcome.app))))
        is PlayOutcome.CantPlayByName -> GarMediaOutput(
            MediaText(R.string.skill_media_play_cant, listOf(outcome.app)),
            detail = if (outcome.noAccess) {
                MediaText(R.string.skill_media_play_cant_access, listOf(outcome.app))
            } else {
                null
            },
            button = if (outcome.noAccess) MediaButton.ALLOW_ACCESS else null,
        )
        PlayOutcome.NoPlayer -> GarMediaOutput(
            MediaText(R.string.skill_media_play_no_player),
            detail = MediaText(R.string.skill_media_play_no_player_hint),
        )
        is PlayOutcome.Failed -> GarMediaOutput(
            outcome.app?.let { MediaText(R.string.skill_media_play_failed, listOf(it)) }
                ?: MediaText(R.string.skill_media_play_failed_no_app)
        )
    }

    /** The model's media_play call for something that isn't music to play ("a game"). */
    fun cantPlay(): GarMediaOutput = GarMediaOutput(MediaText(R.string.skill_media_play_not_media))

    fun control(outcome: ControlOutcome): GarMediaOutput = when (outcome) {
        ControlOutcome.NoAccess -> GarMediaOutput(
            MediaText(R.string.skill_media_controls_no_access),
            detail = MediaText(R.string.skill_media_controls_no_access_detail),
            button = MediaButton.ALLOW_ACCESS,
        )
        ControlOutcome.NothingPlaying -> GarMediaOutput(MediaText(R.string.skill_media_controls_nothing_playing))
        is ControlOutcome.Failed ->
            GarMediaOutput(MediaText(R.string.skill_media_controls_failed, listOf(outcome.app)))
        is ControlOutcome.Done -> done(outcome.command)
        is ControlOutcome.Toggled -> GarMediaOutput(
            MediaText(
                if (outcome.command is MediaCommand.Shuffle) R.string.skill_media_shuffle_toggled
                else R.string.skill_media_repeat_toggled,
                listOf(outcome.app),
            ),
            detail = MediaText(R.string.skill_media_controls_toggle_detail, listOf(outcome.app)),
        )
        is ControlOutcome.Already -> GarMediaOutput(
            MediaText(
                if ((outcome.command as? MediaCommand.Rate)?.like == false) {
                    R.string.skill_media_already_disliked
                } else {
                    R.string.skill_media_already_liked
                }
            )
        )
        is ControlOutcome.OutOfTrack -> GarMediaOutput(MediaText(R.string.skill_media_seek_past_end))
        is ControlOutcome.Unsupported -> GarMediaOutput(
            MediaText(unsupported(outcome.command), listOf(outcome.app))
        )
    }

    private fun done(command: MediaCommand): GarMediaOutput = when (command) {
        is MediaCommand.Shuffle -> GarMediaOutput(
            MediaText(if (command.on) R.string.skill_media_shuffle_on else R.string.skill_media_shuffle_off)
        )
        is MediaCommand.Repeat -> GarMediaOutput(
            MediaText(
                when (command.mode) {
                    RepeatMode.ONE -> R.string.skill_media_repeat_one
                    RepeatMode.ALL -> R.string.skill_media_repeat_all
                    RepeatMode.OFF -> R.string.skill_media_repeat_off
                }
            )
        )
        is MediaCommand.Seek -> GarMediaOutput(
            MediaText(
                if (command.millis >= 0) R.string.skill_media_seek_forward else R.string.skill_media_seek_back,
                listOf(Duration.ofMillis(abs(command.millis))),
            )
        )
        MediaCommand.Restart -> GarMediaOutput(MediaText(R.string.skill_media_restart))
        is MediaCommand.Rate -> GarMediaOutput(
            MediaText(if (command.like) R.string.skill_media_like else R.string.skill_media_dislike)
        )
        // "stop" pauses silently, as Google does; the card says what happened
        MediaCommand.Pause -> GarMediaOutput(MediaText(R.string.skill_media_paused), speak = false)
    }

    @StringRes
    private fun unsupported(command: MediaCommand): Int = when (command) {
        is MediaCommand.Shuffle -> R.string.skill_media_shuffle_unsupported
        is MediaCommand.Repeat -> R.string.skill_media_repeat_unsupported
        is MediaCommand.Seek -> R.string.skill_media_seek_unsupported
        MediaCommand.Restart -> R.string.skill_media_restart_unsupported
        is MediaCommand.Rate -> if (command.like) R.string.skill_media_like_unsupported
            else R.string.skill_media_dislike_unsupported
        MediaCommand.Pause -> R.string.skill_media_controls_nothing_playing
    }

    /** A sleep timer of [duration] was set; it ends at [endsAt] by the wall clock. */
    fun sleepSet(duration: Duration, endsAt: LocalTime): GarMediaOutput = GarMediaOutput(
        MediaText(R.string.skill_media_sleep_set, listOf(duration)),
        detail = MediaText(R.string.skill_media_sleep_until, listOf(endsAt)),
        button = MediaButton.CANCEL_SLEEP_TIMER,
    )

    fun sleepLeft(millis: Long): GarMediaOutput = GarMediaOutput(
        MediaText(R.string.skill_media_sleep_left, listOf(rounded(millis))),
        button = MediaButton.CANCEL_SLEEP_TIMER,
    )

    fun sleepCancelled(): GarMediaOutput = GarMediaOutput(MediaText(R.string.skill_media_sleep_cancelled))

    fun sleepNone(): GarMediaOutput = GarMediaOutput(MediaText(R.string.skill_media_sleep_none))

    fun sleepTooLong(): GarMediaOutput = GarMediaOutput(MediaText(R.string.skill_media_sleep_too_long))

    /**
     * What to play as the answer says it: "my workout playlist" is "your workout playlist".
     * English, like the play sentences.
     */
    fun spoken(request: MediaPlayRequest): String =
        request.said.split(" ").joinToString(" ") { if (it == "my") "your" else it }

    /** The time left as people say it: whole minutes, rounded up, from a minute on. */
    fun rounded(millis: Long): Duration =
        if (millis >= 60_000) {
            Duration.ofMinutes((millis + 59_999) / 60_000)
        } else {
            Duration.ofSeconds(((millis + 999) / 1000).coerceAtLeast(1))
        }

    private fun capitalized(name: String): String =
        name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
