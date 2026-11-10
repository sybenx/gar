package org.stypox.dicio.gar.followup.adapters

import cc.aaro.gar.router.followup.ControlVerb
import cc.aaro.gar.router.followup.CorrectionKind
import cc.aaro.gar.router.followup.FollowUpForm
import org.stypox.dicio.gar.followup.GarAlarmFact
import org.stypox.dicio.gar.followup.GarAnswered
import org.stypox.dicio.gar.followup.GarFacts
import org.stypox.dicio.gar.followup.GarFollowUpAdapter
import org.stypox.dicio.gar.followup.GarFollowUpContext
import org.stypox.dicio.gar.followup.GarRememberedTurn
import org.stypox.dicio.gar.followup.GarRewrite
import org.stypox.dicio.gar.followup.GarRewriteText
import org.stypox.dicio.gar.followup.GarTimerFact
import org.stypox.dicio.gar.followup.GarTurn
import org.stypox.dicio.gar.followup.GarTurnKind
import org.stypox.dicio.skills.alarm.AlarmOutput
import org.stypox.dicio.skills.timer.TimerAnswer
import org.stypox.dicio.skills.timer.TimerAnswerOutput
import org.stypox.dicio.skills.timer.TimerCommand
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

/**
 * Alarms, timers and the stopwatch. Their turns are [GarTurnKind.MODIFIABLE]: "cancel it" and
 * "turn it off" reach only the newest turn within a minute, "change it to 8" the newest turn.
 * An alarm keeps its days ("change my 7 am alarm to 8 am on weekdays"); the clock app's change
 * sets the new alarm without a label, as the full command does. An unnamed timer is named by its
 * length ("cancel the 8 minute timer"), a control of an unnamed one by "the timer". "Make it 10"
 * within [CHANGE_LENGTH_MILLIS] of setting a timer adds or takes off the difference; later the
 * time that already ran would make it wrong, so GAR asks what to change instead.
 */
object ClockFollowUps : GarFollowUpAdapter {
    const val ALARM = "alarm"
    const val TIMER = "timer"
    const val TIMER_CONTROL = "timer_control"
    const val STOPWATCH = "stopwatch"

    /** [GarTurn.action]s. */
    const val SET = "set"
    const val CHANGE = "change"
    const val QUERY = "query"
    const val OTHER = "other"

    override val skillIds = setOf(ALARM, TIMER, TIMER_CONTROL, STOPWATCH)

    /** "Make it 10" changes a timer's length only this soon after it was set. */
    const val CHANGE_LENGTH_MILLIS = 15_000L

    /** The longest length "make it 90" may give a timer. */
    private val MAX_LENGTH: Duration = Duration.ofHours(24)

    override val onLockScreen: Boolean = true

    override fun remember(answered: GarAnswered): GarTurn? {
        val output = answered.output
        return when (answered.skillId) {
            ALARM -> when (output) {
                is AlarmOutput.Set -> alarmTurn(answered, SET, GarAlarmFact(output.time.time, output.time.repeatDays, output.label))
                is AlarmOutput.Changed -> alarmTurn(answered, CHANGE, GarAlarmFact(output.to.time, output.to.repeatDays, null))
                else -> GarTurn(answered.skillId, OTHER, mapOf(), GarFacts(), answered.input, GarTurnKind.ANSWER)
            }
            TIMER, TIMER_CONTROL -> when (val answer = (output as? TimerAnswerOutput)?.answer) {
                is TimerAnswer.Started -> timerTurn(answered, SET, GarTimerFact(answer.name, Duration.ofMillis(answer.durationMillis)))
                is TimerAnswer.Left -> timerTurn(answered, QUERY, GarTimerFact(answer.name, Duration.ZERO))
                // "pause the timer", or the rewrite of "make it 10": "the timer" stays the referent
                else -> when (val command = answered.inputData) {
                    is TimerCommand.Pause, is TimerCommand.Resume, is TimerCommand.AddTime, is TimerCommand.Cancel ->
                        timerTurn(answered, OTHER, GarTimerFact(commandName(command), Duration.ZERO))
                    else -> GarTurn(answered.skillId, OTHER, mapOf(), GarFacts(), answered.input, GarTurnKind.ANSWER)
                }
            }
            STOPWATCH -> GarTurn(
                answered.skillId, OTHER, mapOf(), GarFacts(setting = STOPWATCH), answered.input, GarTurnKind.MODIFIABLE,
            )
            else -> null
        }
    }

    override fun rewrite(form: FollowUpForm, turn: GarRememberedTurn, context: GarFollowUpContext): GarRewrite? {
        val facts = turn.turn.facts
        val text = when {
            facts.alarm != null -> alarm(form, facts.alarm)
            facts.timer != null -> timer(form, facts.timer, turn.turn.action, turn.ageMillis)
            facts.setting == STOPWATCH -> stopwatch(form)
            else -> null
        } ?: return null
        return GarRewrite.Command(text)
    }

    private fun alarm(form: FollowUpForm, alarm: GarAlarmFact): String? {
        val from = spokenTime(alarm.time)
        return when (form) {
            is FollowUpForm.Remove -> "cancel my $from alarm"
            is FollowUpForm.Switch -> if (form.on) null else "cancel my $from alarm"
            is FollowUpForm.SetTo -> newTime(form.value, alarm)?.let { "change my $from alarm to $it" }
            is FollowUpForm.Correction -> if (form.kind == CorrectionKind.OTHER) {
                null
            } else {
                form.value?.let { newTime(it, alarm) }?.let { "change my $from alarm to $it" }
            }
            else -> null
        }
    }

    private fun timer(form: FollowUpForm, timer: GarTimerFact, action: String, ageMillis: Long): String? {
        val name = timer.name?.let { GarRewriteText.words(it) }
        // a timer by its name, else by its length ("the 8 minute timer"), else the only one
        val which = when {
            name != null -> "the $name timer"
            !timer.length.isZero -> "the ${lengthWords(timer.length)} timer"
            else -> "the timer"
        }
        return when (form) {
            is FollowUpForm.Remove -> "cancel $which"
            is FollowUpForm.Switch -> if (form.on) null else "cancel $which"
            is FollowUpForm.AddMore -> {
                val target = if (name != null) "the $name timer" else "the timer"
                if (form.less) "take ${form.amount} off $target" else "add ${form.amount} to $target"
            }
            is FollowUpForm.Control -> when (form.verb) {
                ControlVerb.PAUSE -> "pause $which"
                ControlVerb.RESUME -> "resume $which"
                // the clock app has no reset or restart of a timer
                ControlVerb.RESET, ControlVerb.RESTART -> null
            }
            // "how much time is left on the timer", "and the pasta timer?"
            is FollowUpForm.WhatAbout -> if (action == QUERY && form.rest.split(" ").last() == TIMER) {
                val rest = form.rest.removePrefix("the ")
                "how much time is left on the $rest"
            } else {
                null
            }
            // "make it 10" right after "set a timer for 5 minutes": the clock app can't set a
            // timer's length, so the difference is added or taken off
            is FollowUpForm.SetTo -> if (action == SET && ageMillis <= CHANGE_LENGTH_MILLIS) {
                newLength(form.value, timer.length)
            } else {
                null
            }?.let { to ->
                val target = if (name != null) "the $name timer" else "the timer"
                val difference = to.minus(timer.length)
                when {
                    difference.isZero -> null
                    difference.isNegative -> "take ${amountWords(difference.negated())} off $target"
                    else -> "add ${amountWords(difference)} to $target"
                }
            }
            else -> null
        }
    }

    /**
     * The length [value] says ("10", "10 minutes", "1 hour"); a bare number counts in minutes, and
     * only after a timer of minutes ("make it 90" after a 1 hour timer says no unit GAR can trust).
     * Null when [value] is no length or over a day.
     */
    fun newLength(value: String, length: Duration): Duration? {
        if (length.isZero) {
            return null
        }
        val match = Regex("^([0-9]{1,3})(?: (second|minute|hour)s?)?$").find(value.trim().lowercase()) ?: return null
        val count = match.groupValues[1].toLong()
        val unit = match.groupValues[2].ifEmpty {
            lengthWords(length).substringAfter(" ").takeIf { it == "minute" } ?: return null
        }
        return when (unit) {
            "hour" -> Duration.ofHours(count)
            "minute" -> Duration.ofMinutes(count)
            else -> Duration.ofSeconds(count)
        }.takeIf { !it.isZero && it <= MAX_LENGTH }
    }

    /** "5 minutes", "1 hour", "90 seconds": an amount of time the timer sentences add or take off. */
    fun amountWords(amount: Duration): String {
        val words = lengthWords(amount)
        return if (words.startsWith("1 ")) words else words + "s"
    }

    private fun stopwatch(form: FollowUpForm): String? = when (form) {
        is FollowUpForm.Control -> when (form.verb) {
            ControlVerb.PAUSE -> "stop the stopwatch"
            ControlVerb.RESUME -> "resume the stopwatch"
            ControlVerb.RESET -> "reset the stopwatch"
            ControlVerb.RESTART -> null
        }
        is FollowUpForm.Switch -> if (form.on) "start the stopwatch" else "stop the stopwatch"
        is FollowUpForm.Remove -> "reset the stopwatch"
        else -> null
    }

    private fun alarmTurn(answered: GarAnswered, action: String, alarm: GarAlarmFact) = GarTurn(
        skillId = answered.skillId,
        action = action,
        slots = mapOf("time" to spokenTime(alarm.time)),
        facts = GarFacts(alarm = alarm, time = alarm.time),
        command = answered.input,
        kind = GarTurnKind.MODIFIABLE,
    )

    private fun timerTurn(answered: GarAnswered, action: String, timer: GarTimerFact) = GarTurn(
        skillId = answered.skillId,
        action = action,
        slots = listOfNotNull(
            timer.name?.let { "name" to it },
            ("duration" to lengthWords(timer.length)).takeIf { !timer.length.isZero },
        ).toMap(),
        facts = GarFacts(timer = timer),
        command = answered.input,
        kind = GarTurnKind.MODIFIABLE,
    )

    /** The timer a timer control named, as said. */
    private fun commandName(inputData: Any?): String? = when (inputData) {
        is TimerCommand.Pause -> inputData.name
        is TimerCommand.Resume -> inputData.name
        is TimerCommand.AddTime -> inputData.name
        is TimerCommand.Cancel -> inputData.name
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** "7 am", "7:30 pm", "12 pm". */
    fun spokenTime(time: LocalTime): String {
        val hour = if (time.hour % 12 == 0) 12 else time.hour % 12
        val minutes = if (time.minute == 0) "" else ":" + time.minute.toString().padStart(2, '0')
        return "$hour$minutes ${if (time.hour < 12) "am" else "pm"}"
    }

    /**
     * The new time of [alarm] as said, in the same half of the day when it says none ("8" after
     * a 7 am alarm is 8 am), with the alarm's days; null when [value] isn't a time.
     */
    fun newTime(value: String, alarm: GarAlarmFact): String? {
        val words = value.trim().lowercase().split(" ").filter { it.isNotEmpty() }
        if (words.none { word -> word.any { it.isDigit() } || word in TIME_WORDS }) {
            return null
        }
        val bare = Regex("[0-9]{1,2}(:[0-9]{2})?").matches(value.trim())
        val time = if (bare) "${value.trim()} ${if (alarm.time.hour < 12) "am" else "pm"}" else value.trim()
        return if (alarm.days.isEmpty()) time else withDays(time, alarm.days)
    }

    /** [time] repeating on [days], as GAR's time resolver reads it. */
    fun withDays(time: String, days: Set<DayOfWeek>): String = when (days) {
        DayOfWeek.entries.toSet() -> "every day at $time"
        WEEKDAYS -> "$time on weekdays"
        WEEKEND -> "every weekend at $time"
        else -> {
            val names = days.sorted().map { it.name.lowercase() }
            val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(" ") + " and " + names.last()
            "every $list at $time"
        }
    }

    /** "8 minute", "1 hour", "90 second": how the timer sentences name a timer by its length. */
    fun lengthWords(length: Duration): String {
        val seconds = length.seconds
        return when {
            seconds % 3600 == 0L -> "${seconds / 3600} hour"
            seconds % 60 == 0L -> "${seconds / 60} minute"
            else -> "$seconds second"
        }
    }

    private val WEEKDAYS = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    private val TIME_WORDS = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "noon", "midnight", "am", "pm", "oclock", "half", "quarter",
    )
}
