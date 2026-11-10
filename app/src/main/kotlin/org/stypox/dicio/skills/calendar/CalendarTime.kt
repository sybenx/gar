package org.stypox.dicio.skills.calendar

import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.ResolvedDateTime
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * The span of an event to add. All-day events go from midnight to midnight of the following
 * day ([end] is exclusive), as the calendar provider stores them.
 *
 * @param offset the UTC offset meant when the clock shows [start] twice (the hour it goes back,
 * see [ResolvedDateTime.offset]), else null
 */
data class EventTimes(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val offset: ZoneOffset? = null,
)

/** Time math for the calendar skill, kept free of Android so that it can be unit tested. */
object CalendarTime {
    val DEFAULT_DURATION: Duration = Duration.ofHours(1)
    val REMINDER_DURATION: Duration = Duration.ofMinutes(15)

    /** The Julian day of 1970-01-01, as in `android.text.format.Time.EPOCH_JULIAN_DAY`. */
    private const val EPOCH_JULIAN_DAY = 2440588L
    private const val UTC = "UTC"

    /**
     * The span of an event starting at [start]. When only a date was said, it's an all-day event
     * lasting until [end] (exclusive, at midnight) or as many days as [duration] covers, at least
     * one. Otherwise it ends at [end] if that is after the start, else after [duration] (one hour
     * by default).
     */
    fun eventTimes(start: ResolvedDateTime, duration: Duration?, end: LocalDateTime?): EventTimes {
        if (!start.timeSpoken) {
            val first = start.dateTime.toLocalDate().atStartOfDay()
            val last = end?.toLocalDate()?.atStartOfDay()?.takeIf { it.isAfter(first) }
                ?: first.plusDays(duration?.let(::wholeDays) ?: 1)
            return EventTimes(first, last, allDay = true)
        }

        val begin = start.dateTime.truncatedTo(ChronoUnit.MINUTES)
        val finish = end?.takeIf { it.isAfter(begin) } ?: begin.plus(duration ?: DEFAULT_DURATION)
        return EventTimes(begin, finish, allDay = false, offset = start.offset)
    }

    /** A reminder is a short event at the time it goes off ([offset]: see [EventTimes]). */
    fun reminderTimes(at: LocalDateTime, offset: ZoneOffset? = null): EventTimes {
        val begin = at.truncatedTo(ChronoUnit.MINUTES)
        return EventTimes(begin, begin.plus(REMINDER_DURATION), allDay = false, offset = offset)
    }

    /** Days an all-day event lasting [duration] spans: "two days" is 2, "a day and a half" is 2. */
    private fun wholeDays(duration: Duration): Long =
        ((duration.toMinutes() + MINUTES_PER_DAY - 1) / MINUTES_PER_DAY).coerceAtLeast(1)

    private const val MINUTES_PER_DAY = 24 * 60L

    /**
     * When an event starting at [start] ends, given what the user said after "until" or "to".
     * A time without a date is on the start's day (or the next day, "from ten pm to one am"), and
     * an am/pm question is answered by taking the first option after the start. For an all-day
     * start ("from monday to wednesday") the end day is included, so the result is midnight after
     * it.
     *
     * @return null if [end] can't be an end of this event
     */
    fun endAfter(start: ResolvedDateTime, end: Resolution<ResolvedDateTime>): LocalDateTime? {
        val options = when (end) {
            is Resolution.Resolved -> listOf(end.value)
            is Resolution.Ambiguous -> end.options
            is Resolution.Unresolvable -> return null
        }

        if (!start.timeSpoken) {
            val first = start.dateTime.toLocalDate()
            return options.singleOrNull()
                ?.takeIf { !it.timeSpoken && !it.dateTime.toLocalDate().isBefore(first) }
                ?.let { it.dateTime.toLocalDate().plusDays(1).atStartOfDay() }
        }

        val begin = start.dateTime.truncatedTo(ChronoUnit.MINUTES)
        return options.mapNotNull { option ->
            when {
                !option.timeSpoken -> null
                option.dateSpoken -> option.dateTime
                else -> begin.toLocalDate().atTime(option.dateTime.toLocalTime())
                    .let { if (it.isAfter(begin)) it else it.plusDays(1) }
            }?.truncatedTo(ChronoUnit.MINUTES)
        }.filter { it.isAfter(begin) }.minOrNull()
    }

    /**
     * Answers an am/pm question about a start time with the end the user also said: "from three
     * to five pm" starts at 3 pm, the latest option before the end.
     *
     * @return null if [end] doesn't tell (it's unclear itself, or no option is before it)
     */
    fun startBefore(options: List<ResolvedDateTime>, end: Resolution<ResolvedDateTime>): ResolvedDateTime? {
        val until = (end as? Resolution.Resolved)?.value?.takeIf { it.timeSpoken } ?: return null
        return options.filter { option ->
            if (until.dateSpoken) {
                option.dateTime.isBefore(until.dateTime)
            } else {
                option.dateTime.toLocalTime().isBefore(until.dateTime.toLocalTime())
            }
        }.maxByOrNull { it.dateTime }
    }

    /**
     * `DTSTART`/`DTEND` millis: all-day events are stored at midnight UTC. [offset] picks the
     * moment when the clock shows [dateTime] twice (see [EventTimes]).
     */
    fun providerMillis(
        dateTime: LocalDateTime,
        allDay: Boolean,
        zone: ZoneId,
        offset: ZoneOffset? = null,
    ): Long =
        if (allDay) {
            dateTime.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        } else {
            ZonedDateTime.ofLocal(dateTime, zone, offset).toInstant().toEpochMilli()
        }

    /** `EVENT_TIMEZONE`: the provider requires UTC for all-day events. */
    fun providerTimezone(allDay: Boolean, zone: ZoneId): String = if (allDay) UTC else zone.id

    /** The local date and time of provider millis ([providerMillis] reversed). */
    fun fromProviderMillis(millis: Long, allDay: Boolean, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), if (allDay) ZoneOffset.UTC else zone)

    /** The Julian day the calendar provider's `instances/whenbyday` queries take. */
    fun julianDay(date: LocalDate): Int = (date.toEpochDay() + EPOCH_JULIAN_DAY).toInt()
}
