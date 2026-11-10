package org.stypox.dicio.gar.ui

import androidx.annotation.StringRes
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.gar.followup.adapters.ClockFollowUps
import org.stypox.dicio.gar.voicelog.GarVoiceLog
import org.stypox.dicio.gar.voicelog.GarVoiceStart
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.sentences.Sentences.Media
import org.stypox.dicio.skills.media.MediaOutput
import org.stypox.dicio.skills.timer.TimerAnswer
import org.stypox.dicio.skills.timer.TimerAnswerOutput
import org.stypox.dicio.skills.timer.TimerOutput
import org.stypox.dicio.skills.weather.WeatherOutput
import java.time.LocalTime

/** A chip under an answer: [label] is shown, [input] is sent to the evaluator as typed text. */
data class GarChip(val label: String, val input: String = label)

/** Implemented by GAR outputs that offer chips. */
interface GarSuggestions {
    fun suggestions(ctx: SkillContext): List<GarChip>
}

/**
 * The chips under the latest answer (gar/DESIGN.md, "Home and chips"). Each chip's input must be
 * matched by the skill it is meant for; the inputs are English, so chips are offered only when
 * the sentences' language is the one `gar_chips_language` names.
 */
object GarChips {
    const val MAX_CHIPS = 4

    /** Taps on a chip row are ignored this long after its chips appear or change. */
    const val TAP_GUARD_MILLIS = 300L

    /** Chips for [output]: its own (GarSuggestions) or GAR's table for Dicio's outputs. Max 4. */
    fun forOutput(output: SkillOutput, ctx: SkillContext): List<GarChip> {
        if (!languageMatches(ctx)) {
            return listOf()
        }
        val chips = (output as? GarSuggestions)?.suggestions(ctx)
            ?: dicioChips(output, GarChipStrings.of(ctx))
        return chips.take(MAX_CHIPS)
    }

    /** Sends [chip] like a card button (voice-log start "button"). */
    fun send(evaluator: SkillEvaluator, chip: GarChip) {
        GarVoiceLog.hint(GarVoiceStart.BUTTON)
        evaluator.processInputEvent(InputEvent.Final(listOf(Pair(chip.input, 1.0f))))
    }

    /** Whether the chips' inputs are in the language the sentences are matched in. */
    fun languageMatches(ctx: SkillContext): Boolean =
        ctx.android.getString(R.string.gar_chips_language) == ctx.sentencesLanguage

    /** GAR's table for Dicio's outputs, which don't implement [GarSuggestions]. */
    internal fun dicioChips(output: SkillOutput, strings: GarChipStrings): List<GarChip> =
        when (output) {
            is TimerOutput.Set -> GarChipTable.timerSet(strings)
            // GAR's timers are the clock app's: a started one answers with TimerAnswerOutput,
            // and the clock app's API can't cancel one (TimerAnswer.CantChange), so no Cancel
            is TimerAnswerOutput -> when (output.answer) {
                is TimerAnswer.Started -> GarChipTable.timerStarted(strings)
                else -> listOf()
            }
            is WeatherOutput.Success -> GarChipTable.weather(strings, tomorrow = true, weekend = true)
            is MediaOutput -> when (output.performedAction) {
                Media.Play, Media.Next, Media.Previous -> GarChipTable.media(strings, playing = true)
                else -> listOf()
            }
            else -> listOf()
        }
}

/** Resolves the chips' strings: Android's resources in the app, fakes in tests. */
fun interface GarChipStrings {
    fun get(@StringRes id: Int, vararg args: Any): String

    companion object {
        fun of(ctx: SkillContext): GarChipStrings =
            GarChipStrings { id, args -> ctx.android.getString(id, *args) }
    }
}

/** The chips of each kind of answer (spec 3.2); the outputs pick the row that fits. */
object GarChipTable {
    private fun GarChipStrings.chip(@StringRes label: Int, @StringRes input: Int, vararg args: Any) =
        GarChip(label = get(label), input = get(input, *args))

    /** A timer was set. */
    fun timerSet(s: GarChipStrings) = listOf(
        s.chip(R.string.gar_chip_cancel_timer, R.string.gar_chip_cancel_timer_input),
        s.chip(R.string.gar_chip_show_timers, R.string.gar_chip_show_timers_input),
    )

    /** A timer was started in the clock app, which GAR can show but not cancel. */
    fun timerStarted(s: GarChipStrings) = listOf(
        s.chip(R.string.gar_chip_show_timers, R.string.gar_chip_show_timers_input),
    )

    /**
     * An alarm was set at [time]. "Cancel it" names the alarm ("cancel my 7 am alarm"): a bare
     * "cancel it" reaches it only as a follow-up, within a minute, but the chip stays longer.
     */
    fun alarmSet(s: GarChipStrings, time: LocalTime) = listOf(
        s.chip(R.string.gar_chip_show_alarms, R.string.gar_chip_show_alarms_input),
        s.chip(
            R.string.gar_chip_cancel_it, R.string.gar_chip_cancel_alarm_input,
            ClockFollowUps.spokenTime(time),
        ),
    )

    /** The alarms were shown. */
    fun alarmShow(s: GarChipStrings) = listOf(
        s.chip(R.string.gar_chip_set_alarm, R.string.gar_chip_set_alarm_input),
    )

    /** A forecast: tomorrow's and the weekend's, each unless the answer already was about it. */
    fun weather(s: GarChipStrings, tomorrow: Boolean, weekend: Boolean) = listOfNotNull(
        s.chip(R.string.gar_chip_tomorrow, R.string.gar_chip_tomorrow_input).takeIf { tomorrow },
        s.chip(R.string.gar_chip_weekend, R.string.gar_chip_weekend_input).takeIf { weekend },
    )

    /** Media started playing ([playing]) or was paused; null: neither is known. */
    fun media(s: GarChipStrings, playing: Boolean?) = when (playing) {
        true -> listOf(
            s.chip(R.string.gar_chip_pause, R.string.gar_chip_pause_input),
            s.chip(R.string.gar_chip_next, R.string.gar_chip_next_input),
        )
        false -> listOf(s.chip(R.string.gar_chip_resume, R.string.gar_chip_resume_input))
        null -> listOf()
    }

    /** About one call from [name]; none without a name. */
    fun callBack(s: GarChipStrings, name: String?) = listOfNotNull(
        name?.let { s.chip(R.string.gar_chip_call_back, R.string.gar_chip_call_back_input, it) },
    )

    /** Messages from one sender [name] were read; Reply only when the app takes replies. */
    fun messagesRead(s: GarChipStrings, name: String?, canReply: Boolean) = listOfNotNull(
        s.chip(R.string.gar_chip_reply, R.string.gar_chip_reply_input).takeIf { name != null && canReply },
        name?.let { s.chip(R.string.gar_chip_call, R.string.gar_chip_call_input, it) },
    )
}
