package org.stypox.dicio.skills.agenda

import cc.aaro.gar.router.resolve.AmbiguityReason
import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.ResolvedDateTime
import cc.aaro.gar.router.resolve.SlotResolvers
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * An event instance as GAR changes it: [id] is the event's row (`Events._ID`), [begin] and [end]
 * local times (all-day events from midnight to the next midnight), [writable] when its calendar
 * lets the user change it (contributor access or more), [repeats] when it is one of a series (a
 * repeating event, or one time of a series changed on its own: [AndroidAgenda.isOfSeries]).
 */
data class AgendaEvent(
    val id: Long,
    val title: String?,
    val begin: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean = false,
    val calendarName: String = "",
    val writable: Boolean = true,
    val repeats: Boolean = false,
    val location: String? = null,
)

/** The calendar provider as the agenda and reminders skills use it ([AndroidAgenda]). */
interface Agenda {
    /** Whether GAR may read and write the calendar (`READ_CALENDAR`, `WRITE_CALENDAR`). */
    val allowed: Boolean

    /**
     * The event instances that overlap [from] to [to] in visible calendars, not cancelled and not
     * GAR's reminders, by start.
     */
    suspend fun events(from: LocalDateTime, to: LocalDateTime): List<AgendaEvent>

    /** GAR's reminders (events it made with an alert) that start at [from] or later, by start. */
    suspend fun reminders(from: LocalDateTime): List<AgendaEvent>

    /** Moves [event]; the event as the provider has it afterwards, or null if that failed. */
    suspend fun move(event: AgendaEvent, begin: LocalDateTime, end: LocalDateTime): AgendaEvent?

    /** Deletes [event] (a reminder too, which GAR then forgets); whether it is gone. */
    suspend fun delete(event: AgendaEvent): Boolean

    /** Opens [event] in a calendar app; false if none can. */
    fun open(event: AgendaEvent): Boolean
}

/**
 * Which events a request means ("tomorrow's 9am event", "my meeting with john", "the dentist"):
 * a day and a time of day when said ([SlotResolvers]), and the words of the title. English.
 *
 * @param times the times of day said, two when it could be am or pm ("nine"); empty for none
 * @param words the title's words: each must be a whole word of the event's title. A kind of
 * event ("lunch", "call", "class") is one of them: "cancel my lunch" never means the dentist.
 * @param without the title's words without those kinds, when there are other words too, for
 * [loosened]: "lunch with john" may be the event "John".
 */
data class EventQuery(
    val day: LocalDate?,
    val times: List<LocalTime>,
    val words: List<String>,
    val without: List<String>? = null,
) {
    /** The query again without the kinds of event, when only they could have kept a match out. */
    fun loosened(): EventQuery? = without?.let { copy(words = it, without = null) }

    /** Only "my meeting", "the event": the next event is meant. */
    val nothingSaid: Boolean get() = day == null && times.isEmpty() && words.isEmpty()

    fun matches(event: AgendaEvent): Boolean =
        (day == null || event.begin.toLocalDate() == day ||
            (event.allDay && !day.isBefore(event.begin.toLocalDate()) && day.isBefore(event.end.toLocalDate()))) &&
            (times.isEmpty() || (!event.allDay && event.begin.toLocalTime().withSecond(0).withNano(0) in times)) &&
            words.all { word -> NameMatcher.sharesWord(word, event.title.orEmpty()) }

    companion object {
        /**
         * Reads [ref] ("tomorrows 9am event", "my meeting with john"): the longest run of words
         * that [resolvers] reads as a date or time is the when, the rest without fillers and
         * plain event nouns ("event", "meeting") the title's words.
         */
        fun parse(ref: String, resolvers: SlotResolvers): EventQuery {
            val words = ref.split(" ").filter { it.isNotEmpty() }.map { POSSESSIVE_DAYS[it] ?: it }
            var day: LocalDate? = null
            var times: List<LocalTime> = listOf()
            var whenRange: IntRange? = null
            loop@ for (length in words.size downTo 1) {
                for (start in 0..words.size - length) {
                    val span = words.subList(start, start + length)
                    if (span.all { it in TITLE_FILLERS || it in AgendaWords.EVENT_NOUNS }) {
                        continue
                    }
                    val read = readWhen(span.joinToString(" "), resolvers) ?: continue
                    day = read.first
                    times = read.second
                    whenRange = start until start + length
                    break@loop
                }
            }
            val title = words.filterIndexed { i, word ->
                (whenRange == null || i !in whenRange) && word !in TITLE_FILLERS && word !in GENERIC_NOUNS
            }
            val without = title.filter { it !in AgendaWords.EVENT_NOUNS }
            return EventQuery(day, times, title, without.takeIf { it.isNotEmpty() && it.size < title.size })
        }

        /** The day and times [raw] says, or null when it says no date or time. */
        fun readWhen(raw: String, resolvers: SlotResolvers): Pair<LocalDate?, List<LocalTime>>? {
            val options = when (val resolution = resolvers.dateTime(raw)) {
                is Resolution.Resolved -> listOf(resolution.value)
                is Resolution.Ambiguous ->
                    if (resolution.reason == AmbiguityReason.AM_OR_PM) resolution.options else return null
                is Resolution.Unresolvable -> return null
            }
            val first = options[0]
            val day = if (first.dateSpoken) first.dateTime.toLocalDate() else null
            // "tomorrow morning" names a part of the day, not the event's start
            val times = options.filter { it.timeSpoken && !it.approximateTime }
                .map { it.dateTime.toLocalTime().withSecond(0).withNano(0) }
            return Pair(day, times)
        }

        /** "tomorrows" (from "tomorrow's") is "tomorrow". */
        private val POSSESSIVE_DAYS = mapOf(
            "tomorrows" to "tomorrow", "todays" to "today", "tonights" to "tonight",
            "mondays" to "monday", "tuesdays" to "tuesday", "wednesdays" to "wednesday",
            "thursdays" to "thursday", "fridays" to "friday", "saturdays" to "saturday",
            "sundays" to "sunday", "yesterdays" to "yesterday",
        )

        /** Nouns that say only "an event"; the other [AgendaWords.EVENT_NOUNS] say which kind. */
        private val GENERIC_NOUNS = setOf(
            "event", "events", "appointment", "appointments", "meeting", "meetings", "reservation",
            "booking", "session", "plans", "thing",
        )

        private val TITLE_FILLERS = setOf(
            "my", "the", "our", "a", "an", "this", "that", "next", "upcoming", "with", "at", "on",
            "for", "in", "about", "to", "of", "from", "calendar", "agenda", "schedule", "s", "all",
            "everything", "it",
        )
    }
}

/** Where a moved event goes, see [MovePlan.of]. */
sealed interface MovePlan {
    data class To(val begin: LocalDateTime, val end: LocalDateTime) : MovePlan

    /** The new time isn't understood: "move it to whenever". */
    data object Unclear : MovePlan

    /** A time of day for an all-day event: only its day can change by voice. */
    data object AllDayTime : MovePlan

    companion object {
        /**
         * The new start of [event] for [to] ("3pm", "friday", "friday at ten"), keeping its
         * length: a time alone keeps the day, a day alone keeps the time. A time that could be
         * am or pm is the one nearer to the event's own time ("3" for a 2 PM meeting is 3 PM).
         */
        fun of(event: AgendaEvent, to: String, resolvers: SlotResolvers): MovePlan {
            val options: List<ResolvedDateTime> = when (val resolution = resolvers.dateTime(to)) {
                is Resolution.Resolved -> listOf(resolution.value)
                is Resolution.Ambiguous ->
                    if (resolution.reason == AmbiguityReason.AM_OR_PM) resolution.options else return Unclear
                is Resolution.Unresolvable -> return Unclear
            }
            val said = options.minByOrNull { option ->
                val minutes = abs(
                    option.dateTime.toLocalTime().toSecondOfDay() - event.begin.toLocalTime().toSecondOfDay()
                ) / 60
                minOf(minutes, 24 * 60 - minutes)
            }!!
            if (event.allDay) {
                if (said.timeSpoken) return AllDayTime
                val shift = ChronoUnit.DAYS.between(
                    event.begin.toLocalDate(), said.dateTime.toLocalDate(),
                )
                return To(event.begin.plusDays(shift), event.end.plusDays(shift))
            }
            val day = if (said.dateSpoken) said.dateTime.toLocalDate() else event.begin.toLocalDate()
            val time = if (said.timeSpoken) said.dateTime.toLocalTime() else event.begin.toLocalTime()
            val begin = day.atTime(time)
            val length = Duration.between(event.begin, event.end)
            return To(begin, begin.plus(length))
        }
    }
}
