package cc.aaro.gar.router.resolve

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Result of turning one raw slot string into a value. Resolvers never guess. */
sealed interface Resolution<out T> {
    data class Resolved<T>(val value: T) : Resolution<T>

    /** Several readings are plausible; ask the user to pick. */
    data class Ambiguous<T>(val options: List<T>, val reason: AmbiguityReason) : Resolution<T>

    data class Unresolvable(val reason: String) : Resolution<Nothing>
}

enum class AmbiguityReason {
    AM_OR_PM,
    SEVERAL_MATCHES,

    /**
     * A month without its day ("december", "next month"): the options are the days of that month,
     * from today on when it is this month, each with the time of day said, if any. GAR asks which
     * day ("Which day in December?").
     */
    DAY_OF_MONTH,
}

/**
 * A resolved point in time and which parts the user actually said.
 *
 * @param approximateTime the time of day is the hour of a part of day ("tonight" = 9 pm,
 * "tomorrow morning" = 9 am, see [EnglishTime.PartOfDay]), not a clock time the user said
 * @param offset the UTC offset of [dateTime] when the clock shows that time twice (the hour it
 * goes back) and the resolver knows which of the two is meant ("in twenty minutes"), else null
 */
data class ResolvedDateTime(
    val dateTime: LocalDateTime,
    val dateSpoken: Boolean,
    val timeSpoken: Boolean,
    val approximateTime: Boolean = false,
    val offset: ZoneOffset? = null,
) {
    /** The moment meant, in [zone]: [dateTime], at [offset] when the clock shows it twice. */
    fun atZone(zone: ZoneId): ZonedDateTime = ZonedDateTime.ofLocal(dateTime, zone, offset)
}

/** For time-only slots (alarms): a time of day, optionally repeating on some weekdays. */
data class ResolvedTime(
    val time: LocalTime,
    val repeatDays: Set<DayOfWeek> = setOf(),
)

data class ResolvedDate(val date: LocalDate)
