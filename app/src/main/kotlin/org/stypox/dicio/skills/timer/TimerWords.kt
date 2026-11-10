package org.stypox.dicio.skills.timer

import cc.aaro.gar.router.KeywordFilter
import cc.aaro.gar.router.Utterances
import org.stypox.dicio.gar.GarMatchCheck
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.gar.conversation.conversationWords
import org.stypox.dicio.gar.timer.GarTimerNames
import org.stypox.dicio.gar.timer.StartedTimer

/** Which inputs GAR's timer controls take (English, like their sentences). */
object TimerWords {
    /**
     * The timer's own words (with their plurals and "timer's"). "Time" isn't one, as in
     * `GarMatchCheck`: "pause the time" isn't about a timer.
     */
    private val TIMER_WORDS = listOf("timer", "countdown")

    /** One of them stops ringing timers. */
    private val STOP_WORDS = setOf(
        "stop", "dismiss", "cancel", "silence", "mute", "quiet", "enough", "ok", "okay", "off",
        "hush", "shush", "shut", "done", "end",
    )

    /** "Add a minute" for ringing timers, which only the clock app's own buttons can do. */
    private val SNOOZE_WORDS = setOf("snooze")

    /** What a snooze without a duration adds. */
    const val ONE_MINUTE_MILLIS = 60_000L

    /**
     * Words that may come with a snooze's duration, as in the alarm's snooze sentences: "snooze
     * for five minutes", "snooze again for another ten minutes".
     */
    private val SNOOZE_DURATION_WORDS = setOf("for", "another", "again", "more")

    /**
     * Words that may come with [STOP_WORDS] and [SNOOZE_WORDS]: "turn it off", "stop the
     * timer", "ok thanks", "stop the ringing please". Not "all": "cancel all timers" cancels the
     * running ones too.
     */
    private val RINGING_FILLERS = setOf(
        "it", "that", "this", "the", "my", "timer", "timers", "alarm", "sound", "ringing", "ring",
        "beeping", "beep", "noise", "buzzing", "please", "now", "turn", "switch", "up", "be", "keep",
        "thats", "is", "thanks", "thank", "you", "alright", "google", "hey", "dicio", "already",
        "oh", "just", "so", "im",
    )

    /** Among [RINGING_FILLERS]: the clock app's alarm, see [whileRinging]. */
    private const val ALARM = "alarm"

    /** Among [RINGING_FILLERS]: what names a timer and not the clock app's alarm. */
    private val TIMER_FILLERS = setOf("timer", "timers")

    /**
     * What [input] tells the clock app's ringing timers, or null if none rings ([ringing]) or it
     * tells them nothing. Every stop phrase of GAR's conversation ([GarRinging.isStopPhrase])
     * stops them, and so does an input made of [STOP_WORDS] and [RINGING_FILLERS] ("ok",
     * "dismiss", "cancel", "turn it off", "stop the timer", "ok thanks"), also with the name of a
     * timer GAR started that has ended ("stop the pasta timer"; the clock app's dismiss stops
     * every ringing timer anyway). "Snooze" is time added, for a minute or the duration said ("snooze for five
     * minutes"). Not "never mind", which stays with GAR's conversation, nor a command about
     * anything else.
     *
     * A ringing timer is often called an alarm ("stop the alarm"), but while the clock app's
     * alarm rings too ([alarmRinging]), an input that says "alarm" and no timer's word or name is
     * left to the alarm skill: "stop" stops the timers first, "stop the alarm" the alarm.
     *
     * @param ringing whether the clock app's timer rings (`FiringTimers`)
     * @param names the names of the timers GAR started that have ended, null for an unnamed one
     * @param durationOf the duration that a text is and nothing more ("for five more minutes"),
     * in milliseconds, or null
     */
    fun whileRinging(
        input: String,
        ringing: Boolean,
        names: List<String?> = listOf(),
        alarmRinging: () -> Boolean = { false },
        durationOf: (String) -> Long? = { null },
    ): TimerCommand? {
        if (!ringing) {
            return null
        }
        if (GarRinging.isStopPhrase(input)) {
            return TimerCommand.StopRinging(null)
        }

        val words = conversationWords(input)
        val nameWords = names.flatMap { name ->
            Utterances.words(Utterances.normalize(name ?: ""))
        }.toSet()
        // the leading "ok" that conversationWords drops still stops them ("ok thanks")
        val allWords = normalizeAnswer(input).replace("'", "").split(" ")
        val snooze = words.any { it in SNOOZE_WORDS }
        val other = words.filter {
            it !in STOP_WORDS && it !in SNOOZE_WORDS && it !in RINGING_FILLERS &&
                it !in nameWords && !(snooze && it in SNOOZE_DURATION_WORDS)
        }
        // "snooze for five minutes": the other words must be the duration
        val snoozeMillis = other.takeIf { snooze && it.isNotEmpty() }
            ?.let { durationOf(it.joinToString(" ")) }
            ?.takeIf { it > 0 }
        if (words.isEmpty() || (other.isNotEmpty() && snoozeMillis == null)) {
            return null
        }
        if (ALARM in words && words.none { it in TIMER_FILLERS || it in nameWords } &&
            alarmRinging()
        ) {
            return null
        }
        val name = words.filter { it in nameWords }.joinToString(" ").takeIf { it.isNotEmpty() }
        return when {
            snooze ->
                TimerCommand.AddTime(snoozeMillis ?: ONE_MINUTE_MILLIS, name)
            allWords.any { it in STOP_WORDS } -> TimerCommand.StopRinging(name)
            else -> null
        }
    }

    /**
     * Whether [input], which matched [command]'s sentence, is about a timer: it names the timer
     * outside the name that the sentence captured, or that name is one of [timers]' ("pause the
     * pasta" while a pasta timer runs). "Add two minutes" (a duration without a name) counts while
     * there is a timer GAR started. Dicio's matcher also scores a sentence with a word left out above its
     * thresholds: without this, "pause the music" would be a timer called "music" and "show my
     * alarms" would list the timers.
     */
    fun isAboutTimer(input: String, command: TimerCommand, timers: List<StartedTimer>): Boolean {
        val name = nameOf(command)
        val words = Utterances.words(Utterances.normalize(input))
        val frame = GarMatchCheck.withoutCaptures(words, listOf(name)).joinToString(" ")
        if (KeywordFilter.containsAny(frame, TIMER_WORDS)) {
            return true
        }
        return when {
            name != null -> GarTimerNames.namesOneOf(timers, name)
            command is TimerCommand.AddTime -> timers.isNotEmpty()
            else -> false
        }
    }

    /**
     * [command] for every timer when [input] says "timers" without a name: "pause the timers" is
     * "pause all timers", not a question which one.
     */
    fun allWhenPlural(input: String, command: TimerCommand): TimerCommand {
        if (Utterances.words(Utterances.normalize(input)).none { it == "timers" }) {
            return command
        }
        return when (command) {
            is TimerCommand.Pause -> if (command.name == null) command.copy(name = ALL) else command
            is TimerCommand.Resume -> if (command.name == null) command.copy(name = ALL) else command
            is TimerCommand.AddTime -> if (command.name == null) command.copy(name = ALL) else command
            is TimerCommand.Cancel -> if (command.name == null) command.copy(name = ALL) else command
            else -> command
        }
    }

    /**
     * Whether [input] says that it is about every timer: "all", "every", "each", "both" or the
     * plural. "Delete the timer" matches the sentence of "delete all timers" with a word left out,
     * and must not cancel every timer without a question.
     */
    fun saysAll(input: String): Boolean =
        Utterances.words(Utterances.normalize(input)).any { it in ALL_SAID }

    private val ALL_SAID = setOf("all", "every", "each", "both", "timers")

    /** A name that means every timer (`GarTimerNames.meansAll`). */
    private const val ALL = "all"

    private fun nameOf(command: TimerCommand): String? = when (command) {
        is TimerCommand.Pause -> command.name
        is TimerCommand.Resume -> command.name
        is TimerCommand.AddTime -> command.name
        is TimerCommand.StopRinging -> command.name
        is TimerCommand.Cancel -> command.name
        TimerCommand.Show, TimerCommand.CancelAll -> null
    }
}
