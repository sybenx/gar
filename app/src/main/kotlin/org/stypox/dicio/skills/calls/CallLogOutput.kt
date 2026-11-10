package org.stypox.dicio.skills.calls

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.gar.ui.GarChip
import org.stypox.dicio.gar.ui.GarChipStrings
import org.stypox.dicio.gar.ui.GarChipTable
import org.stypox.dicio.gar.ui.GarSuggestions
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.util.getString
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

/** When a call was, as GAR says it. */
sealed interface CallTime {
    /** "At 3:12 PM." */
    data class Today(val time: LocalTime) : CallTime

    /** "Yesterday at 3:12 PM." */
    data class Yesterday(val time: LocalTime) : CallTime

    /** "On Monday at 3:12 PM.", within the last week. */
    data class Weekday(val day: DayOfWeek, val time: LocalTime) : CallTime

    /** "On October 12.", longer ago. */
    data class OnDate(val date: LocalDate) : CallTime

    companion object {
        fun of(call: CallEntry, now: ZonedDateTime): CallTime {
            val at = call.time.atZone(now.zone)
            val time = at.toLocalTime().truncatedTo(ChronoUnit.MINUTES)
            return when (ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate())) {
                0L -> Today(time)
                1L -> Yesterday(time)
                in 2L..6L -> Weekday(at.dayOfWeek, time)
                // a clock that went back: the call's time on its day
                in Long.MIN_VALUE..-1L -> Today(time)
                else -> OnDate(at.toLocalDate())
            }
        }
    }
}

/**
 * The missed calls by caller, newest first: each caller once ("Mom", "a number ending in 1 2 3
 * 4"), with how many calls they made.
 */
data class MissedSummary(val count: Int, val callers: List<Pair<CallEntry, Int>>) {
    companion object {
        fun of(missed: List<CallEntry>): MissedSummary {
            val byCaller = missed.groupBy { call ->
                when {
                    call.name != null -> "name:" + CallHistoryQueries.nameWords(call.name)
                    call.hidden -> "hidden"
                    else -> "number:" + call.number.filter { it in '0'..'9' }
                }
            }
            // groupBy keeps the order of the first call of each caller, the newest
            return MissedSummary(missed.size, byCaller.values.map { calls -> calls.first() to calls.size })
        }
    }
}

/** How GAR names callers and times, spoken and on its cards. */
object CallerText {
    /** "Mom", "a number ending in 1 2 3 4", "a hidden number": short for speech. */
    fun spoken(ctx: SkillContext, call: CallEntry): String {
        call.name?.let { return it }
        if (call.hidden) {
            return ctx.getString(R.string.skill_call_log_hidden_number)
        }
        return CallHistoryQueries.lastDigits(call.number)
            ?.let { ctx.getString(R.string.skill_call_log_number_ending, spacedDigits(it)) }
            ?: shownNumber(call)
    }

    /** "Mom", "(801) 555-1234", "Hidden number": the card shows the whole number. */
    fun shown(ctx: SkillContext, call: CallEntry): String =
        call.name ?: if (call.hidden) {
            ctx.getString(R.string.skill_call_log_hidden_number_card)
        } else {
            shownNumber(call)
        }

    /** For the telephone skill's "Should I call ...?" of a number without a name. */
    fun confirmName(ctx: SkillContext, call: CallEntry): String =
        CallHistoryQueries.lastDigits(call.number)
            ?.let { ctx.getString(R.string.skill_call_log_the_number_ending, spacedDigits(it)) }
            ?: shownNumber(call)

    /** "1 2 3 4": TTS reads the digits one by one, not "one thousand two hundred ...". */
    fun spacedDigits(digits: String): String = digits.toList().joinToString(" ")

    private fun shownNumber(call: CallEntry): String = call.formattedNumber ?: call.number

    fun time(ctx: SkillContext, time: CallTime): String {
        val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(ctx.locale)
        return when (time) {
            is CallTime.Today -> ctx.getString(R.string.skill_call_log_today, clock.format(time.time))
            is CallTime.Yesterday ->
                ctx.getString(R.string.skill_call_log_yesterday, clock.format(time.time))
            is CallTime.Weekday -> ctx.getString(
                R.string.skill_call_log_weekday,
                time.day.getDisplayName(TextStyle.FULL, ctx.locale),
                clock.format(time.time),
            )
            is CallTime.OnDate -> ctx.getString(
                R.string.skill_call_log_date,
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(ctx.locale)
                    .format(time.date),
            )
        }
    }

    /** "Mom", "Mom and Dad", "Mom, Dad and Bob". */
    fun joined(ctx: SkillContext, items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> ctx.getString(
            R.string.skill_call_log_and, items.dropLast(1).joinToString(", "), items.last(),
        )
    }

    fun sentence(ctx: SkillContext, text: String): String =
        text.replaceFirstChar { it.titlecase(ctx.locale) }
}

sealed interface CallLogOutput : SkillOutput, GarSuggestions {

    /**
     * The name of the one caller this answer is about, for the "Call back" chip: null when the
     * caller has no name (or hid the number) or the answer is about several callers. The chip
     * says the name, so that "call back" can't pick a newer incoming call instead.
     */
    val callBackName: String?
        get() {
            val call = when (val output = this) {
                is LastCaller -> output.call
                is From -> output.call
                is Missed -> output.summary.callers.singleOrNull()?.first
                else -> null
            }
            return call?.takeIf { !it.hidden }?.name?.takeIf { it.isNotBlank() }
        }

    override fun suggestions(ctx: SkillContext): List<GarChip> =
        GarChipTable.callBack(GarChipStrings.of(ctx), callBackName)

    /**
     * "Who called me": the newest call that came in, and the latest calls on the card.
     * @param call null when no call came in
     */
    class LastCaller(
        val call: CallEntry?,
        val recent: List<CallEntry>,
        val now: ZonedDateTime,
    ) : CallLogOutput {
        override fun getSpeechOutput(ctx: SkillContext): String {
            val call = call ?: return ctx.getString(R.string.skill_call_log_no_incoming)
            val caller = CallerText.spoken(ctx, call)
            val time = CallerText.time(ctx, CallTime.of(call, now))
            return CallerText.sentence(ctx, when (call.kind) {
                CallKind.MISSED -> ctx.getString(R.string.skill_call_log_missed_one, caller, time)
                CallKind.DECLINED -> ctx.getString(R.string.skill_call_log_declined, caller, time)
                else -> ctx.getString(R.string.skill_call_log_called, caller, time)
            })
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                RecentCalls(ctx, recent.take(CARD_CALLS), now)
            }
        }
    }

    /** "Did I miss any calls": who, how many and, for one call, when. */
    class Missed(val missed: List<CallEntry>, val now: ZonedDateTime) : CallLogOutput {
        val summary = MissedSummary.of(missed)

        override fun getSpeechOutput(ctx: SkillContext): String {
            val resources = ctx.android.resources
            val callers = summary.callers
            val (first, firstCount) = callers.firstOrNull()
                ?: return ctx.getString(R.string.skill_call_log_no_missed)
            val time = CallerText.time(ctx, CallTime.of(first, now))
            return when {
                summary.count == 1 -> ctx.getString(
                    R.string.skill_call_log_missed_one, CallerText.spoken(ctx, first), time,
                )
                callers.size == 1 -> resources.getQuantityString(
                    R.plurals.skill_call_log_missed_from, firstCount, firstCount,
                    CallerText.spoken(ctx, first), time,
                )
                else -> {
                    val names = callers.take(SPOKEN_CALLERS).map { CallerText.spoken(ctx, it.first) }
                    val others = callers.size - names.size
                    val list = if (others == 0) names else names + resources.getQuantityString(
                        R.plurals.skill_call_log_others, others, others,
                    )
                    resources.getQuantityString(
                        R.plurals.skill_call_log_missed_many, summary.count, summary.count,
                        CallerText.joined(ctx, list),
                    )
                }
            }
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                RecentCalls(ctx, missed.take(CARD_CALLS), now)
            }
        }
    }

    /**
     * "Did mom call me": the newest call from the person named, as [said]; [call] is null when
     * the call log has none from a contact of that name.
     */
    class From(val said: String, val call: CallEntry?, val now: ZonedDateTime) : CallLogOutput {
        override fun getSpeechOutput(ctx: SkillContext): String {
            val call = call ?: return ctx.getString(R.string.skill_call_log_none_from, name(said))
            val caller = CallerText.spoken(ctx, call)
            val time = CallerText.time(ctx, CallTime.of(call, now))
            return CallerText.sentence(ctx, when (call.kind) {
                CallKind.MISSED -> ctx.getString(R.string.skill_call_log_missed_one, caller, time)
                CallKind.DECLINED -> ctx.getString(R.string.skill_call_log_declined, caller, time)
                else -> ctx.getString(R.string.skill_call_log_called, caller, time)
            })
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                RecentCalls(ctx, listOfNotNull(call), now)
            }
        }

        /** "mom" as a name: "Mom". */
        private fun name(said: String): String =
            said.split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
    }

    /** "Call back" with nothing in the call log to call. */
    data class NothingToCall(val target: CallTarget) : CallLogOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(
            when (target) {
                CallTarget.LastOutgoing -> R.string.skill_call_log_nothing_outgoing
                CallTarget.Last -> R.string.skill_call_log_nothing_at_all
                else -> R.string.skill_call_log_nothing_incoming
            }
        )
    }

    /** "Call back" a caller who hid the number. */
    data object HiddenNumber : CallLogOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_call_log_hidden_call_back)
    }

    companion object {
        /** Calls on a card. */
        const val CARD_CALLS = 5

        /** Callers named in speech; more are "and 2 others". */
        const val SPOKEN_CALLERS = 3
    }
}

/** The calls under a call log answer: who, and what happened when. */
@Composable
private fun RecentCalls(ctx: SkillContext, calls: List<CallEntry>, now: ZonedDateTime) {
    for (call in calls) {
        Spacer(modifier = Modifier.height(8.dp))
        Subtitle(text = CallerText.shown(ctx, call))
        Body(text = ctx.getString(
            R.string.skill_call_log_row,
            ctx.getString(when (call.kind) {
                CallKind.INCOMING -> R.string.skill_call_log_kind_incoming
                CallKind.MISSED -> R.string.skill_call_log_kind_missed
                CallKind.DECLINED -> R.string.skill_call_log_kind_declined
                CallKind.OUTGOING -> R.string.skill_call_log_kind_outgoing
            }),
            CallerText.time(ctx, CallTime.of(call, now)),
        ))
    }
}
