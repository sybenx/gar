package org.stypox.dicio.skills.alarm

import cc.aaro.gar.router.resolve.ResolvedTime
import cc.aaro.gar.router.resolve.TypedSlotRewrites
import cc.aaro.gar.router.tools.ConfirmText
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import org.stypox.dicio.sentences.Sentences
import java.time.Duration
import java.time.LocalTime

/** The input of [AlarmSkill]: a sentence match still to resolve, or an [AlarmInput]. */
sealed interface AlarmRequest

/** A match of the alarm sentences, with the captures still as said (Dicio's fast path). */
data class AlarmSentenceMatch(val sentence: Sentences.Alarm) : AlarmRequest

/**
 * What the alarm skill does, with every value resolved. Sentence matches (after resolving and
 * asking, see [AlarmSkill]) and GAR's tool calls (through [AlarmTools.inputFromSlots]) both end
 * here.
 */
sealed interface AlarmInput : AlarmRequest {
    /** An alarm at [time], repeating on [ResolvedTime.repeatDays] if any. */
    data class Set(val time: ResolvedTime, val label: String?) : AlarmInput

    /** Tell when the next alarm rings; with [open] also open the clock app's list of alarms. */
    data class Show(val open: Boolean) : AlarmInput

    /** Dismiss alarms in the clock app. */
    data class Cancel(val target: CancelTarget) : AlarmInput

    /** Stop the clock app's ringing alarm. */
    data object Stop : AlarmInput

    /** Snooze the clock app's ringing alarm, for [duration] if the user said one. */
    data class Snooze(val duration: Duration?) : AlarmInput

    /**
     * Move the alarm at [from] to [to]: the clock app has no intent to change an alarm, so set
     * the new one and dismiss the old one.
     */
    data class Change(val from: LocalTime, val to: ResolvedTime) : AlarmInput
}

/** Which alarms to dismiss, see `AlarmClock.EXTRA_ALARM_SEARCH_MODE`. */
sealed interface CancelTarget {
    /** The alarm(s) at this time of day. */
    data class At(val time: LocalTime) : CancelTarget

    /** The alarm that rings next. */
    data object Next : CancelTarget

    data object All : CancelTarget
}

/**
 * The alarm tools of `app/src/main/gar/tools.yml` (all LLM-only, since their time slots are
 * resolved by GAR, see gar/DESIGN.md "Step 3 skills"), and the conversion of their slots.
 */
object AlarmTools {
    const val SET = "alarm_set"
    const val LIST = "alarm_list"
    const val CANCEL = "alarm_cancel"
    const val TIME = "time"
    const val LABEL = "label"

    /**
     * The same slots as `alarm_set` in tools.yml. The fast path runs sentence matches through
     * GAR's [org.stypox.dicio.gar.call.GarCallRunner] with this tool, so a missing, unclear or
     * am/pm-ambiguous time is asked for just like after a model call.
     */
    val SET_TOOL = ToolSpec(
        name = SET,
        skillId = AlarmInfo.id,
        sentence = null,
        description = "Set an alarm.",
        confirm = false,
        slots = listOf(
            SlotSpec(TIME, SlotKind.TIME, required = true, description = ""),
            SlotSpec(LABEL, SlotKind.TEXT, required = false, description = ""),
        ),
        examples = listOf(),
    )

    /** The same slots as `alarm_cancel` in tools.yml, see [SET_TOOL]. */
    val CANCEL_TOOL = ToolSpec(
        name = CANCEL,
        skillId = AlarmInfo.id,
        sentence = null,
        description = "Cancel an alarm.",
        confirm = false,
        slots = listOf(SlotSpec(TIME, SlotKind.TIME, required = false, description = "")),
        examples = listOf(),
    )

    // Tools of the sentences only, never offered to the model (tools.yml doesn't have them): they
    // run the sentence matches that need a yes through GAR's runner, which asks am or pm and
    // shows GAR's confirmation card. Their confirmation texts are English, like tools.yml's.
    const val CHANGE = "alarm_change"
    const val CANCEL_ALL = "alarm_cancel_all"
    const val FROM = "from"
    const val TO = "to"
    const val WHICH = "which"
    const val ALL = "all"

    /** Change an alarm: [FROM] is the old time, [TO] the new one. */
    val CHANGE_TOOL = ToolSpec(
        name = CHANGE,
        skillId = AlarmInfo.id,
        sentence = null,
        description = "Change an alarm.",
        confirm = true,
        slots = listOf(
            SlotSpec(FROM, SlotKind.TIME, required = true, description = ""),
            SlotSpec(TO, SlotKind.TIME, required = true, description = ""),
        ),
        examples = listOf(),
        confirmText = ConfirmText.Template("Change your {$FROM} alarm to {$TO}?"),
        onLockScreen = true,
    )

    /**
     * Dismiss all alarms. Its only slot, [WHICH], is always [ALL] (the call comes with it), so
     * that [inputFromSlots] can tell this tool apart.
     */
    val CANCEL_ALL_TOOL = ToolSpec(
        name = CANCEL_ALL,
        skillId = AlarmInfo.id,
        sentence = null,
        description = "Dismiss all alarms.",
        confirm = true,
        slots = listOf(
            SlotSpec(WHICH, SlotKind.ENUM, required = true, description = "", enumValues = listOf(ALL)),
        ),
        examples = listOf(),
        confirmText = ConfirmText.Template("Dismiss all your alarms?"),
        onLockScreen = true,
    )

    /**
     * Builds the input for a call of one of the alarm tools. [org.stypox.dicio.gar.call.GarToolSkill]
     * only passes the slots, so the tool is told apart by its slot names: GAR passes every slot
     * of the called tool, with null for an optional slot the user left out. `alarm_set` is the
     * only tool with a label, `alarm_cancel` the only other one with a time, `alarm_list` has
     * none (AlarmToolsTest checks this against tools.yml). Of the sentences' own tools,
     * [CHANGE_TOOL] has [TO] and [CANCEL_ALL_TOOL] has [WHICH].
     *
     * @param slots resolved values: a [ResolvedTime] for [TIME], [FROM] and [TO], a string for
     * [LABEL] and [WHICH]
     */
    fun inputFromSlots(slots: Map<String, Any?>): AlarmInput {
        val time = slots[TIME] as ResolvedTime?
        return when {
            LABEL in slots -> AlarmInput.Set(
                time = checkNotNull(time) { "$SET without a time" },
                label = (slots[LABEL] as String?)?.let(::cleanLabel),
            )
            TO in slots -> AlarmInput.Change(
                from = checkNotNull(slots[FROM] as ResolvedTime?) { "$CHANGE without a time" }.time,
                to = checkNotNull(slots[TO] as ResolvedTime?) { "$CHANGE without a new time" },
            )
            WHICH in slots -> AlarmInput.Cancel(CancelTarget.All)
            TIME in slots -> AlarmInput.Cancel(
                time?.let { CancelTarget.At(it.time) } ?: CancelTarget.Next
            )
            else -> AlarmInput.Show(open = false)
        }
    }

    /**
     * The old alarm of a change as said, for [TypedSlotRewrites.alarmToCancel]: "my wake up
     * alarm" is the next alarm, like "my alarm".
     */
    fun changeFrom(raw: String): String {
        val words = raw.trim().split(WHITESPACE)
            .filter { it.isNotEmpty() && it.lowercase() !in WAKE_UP_WORDS }
        return TypedSlotRewrites.alarmToCancel(words.joinToString(" "))
    }

    private val WAKE_UP_WORDS = setOf("wake", "up", "wakeup", "waking")

    /**
     * The label without the generic words the model and the sentences tend to keep around it:
     * "the gym alarm" -> "gym", "called work" -> "work", "my alarm" -> null. The model often
     * repeats the time as the label ("quarter past five tomorrow"), which is no label either.
     */
    fun cleanLabel(raw: String): String? {
        val words = raw.trim().split(WHITESPACE)
            .filter { it.isNotEmpty() && it.lowercase() !in GENERIC_WORDS }
            .dropWhile { it.lowercase() in LEADING_WORDS }
        if (words.all { it.lowercase() in TIME_WORDS || DIGITS.matches(it) }) {
            return null
        }
        return words.joinToString(" ")
    }

    private val WHITESPACE = Regex("\\s+")
    private val DIGITS = Regex("[0-9:]+(am|pm)?", RegexOption.IGNORE_CASE)
    private val GENERIC_WORDS = setOf("alarm", "alarms")
    private val LEADING_WORDS = setOf(
        "a", "an", "the", "my", "for", "called", "named", "labeled", "labelled", "with", "label",
        "name", "saying", "that", "says",
    )

    /** English words a spoken time of day or repetition is made of. */
    private val TIME_WORDS = setOf(
        "zero", "oh", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fifty", "hundred", "o'clock", "oclock", "o",
        "clock", "am", "pm", "a", "p", "m", "quarter", "half", "past", "to", "till", "after",
        "before", "at", "for", "in", "on", "by", "around", "about", "the", "this", "and", "next",
        "today", "tomorrow", "tonight", "morning", "afternoon", "evening", "night", "noon",
        "midnight", "every", "each", "day", "days", "daily", "weekday", "weekdays", "weekend",
        "weekends", "minute", "minutes", "hour", "hours", "monday", "tuesday", "wednesday",
        "thursday", "friday", "saturday", "sunday", "mondays", "tuesdays", "wednesdays",
        "thursdays", "fridays", "saturdays", "sundays",
    )
}
