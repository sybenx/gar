package org.stypox.dicio.skills.calendar

import cc.aaro.gar.router.resolve.ResolvedDate
import cc.aaro.gar.router.resolve.ResolvedDateTime
import cc.aaro.gar.router.resolve.ResolvedTime
import org.stypox.dicio.gar.call.GarCallRunner
import org.stypox.dicio.gar.call.GarSentenceBinder
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * The calendar skill's typed input. Both paths end here: Dicio's sentences through
 * [CalendarSkill.fromSentence] (the slot strings resolved and asked for by GAR's
 * [org.stypox.dicio.gar.call.GarCallRunner]), and the model's tool calls through
 * [CalendarSkill.inputFromSlots] with values GAR already resolved.
 */
sealed interface CalendarRequest {
    /** The events of [day], or of today when null. */
    data class Query(val day: LocalDate?) : CalendarRequest

    /**
     * @param end an explicit end ("until five"), already placed after [start] by
     * [CalendarTime.endAfter]
     * @param confirm whether the skill asks before adding (false when GAR already asked, or the
     * user turned GAR's confirmation off)
     */
    data class AddEvent(
        val title: String,
        val start: ResolvedDateTime,
        val duration: Duration?,
        val end: LocalDateTime?,
        val confirm: Boolean,
    ) : CalendarRequest

    /** @param at without [ResolvedDateTime.timeSpoken] the skill asks "What time?" first */
    data class AddReminder(
        val what: String,
        val at: ResolvedDateTime,
        val confirm: Boolean,
    ) : CalendarRequest

    companion object {
        // the slot names of the tools in tools.yml (and of CalendarSkill's own fast path tools)
        const val TITLE = "title"
        const val WHAT = "what"
        const val WHEN = "when"
        const val DURATION = "duration"
        const val END = "end"
        const val DAY = "day"
        const val TIME = "time"

        /**
         * Builds the request from resolved slot values (see
         * [org.stypox.dicio.gar.call.GarSlotResolver] for their types). The tool is told apart by
         * its slots: `title` (calendar_add), `what` (reminder_add), else calendar_query.
         *
         * @param end the explicit end, computed from the fast path's `end` slot
         * @param now the reference for a bare time of day (next occurrence)
         */
        fun fromSlots(
            slots: Map<String, Any?>,
            confirm: Boolean,
            end: (ResolvedDateTime) -> LocalDateTime? = { null },
            now: LocalDateTime = LocalDateTime.now(),
        ): CalendarRequest {
            val title = slots[TITLE] as? String
            val what = slots[WHAT] as? String
            return when {
                title != null -> {
                    val start = dateTimeOf(slots[WHEN], now)
                        ?: throw IllegalArgumentException("calendar_add without a start: $slots")
                    AddEvent(
                        title = cleanTitle(title),
                        start = start,
                        duration = slots[DURATION] as? Duration,
                        end = end(start),
                        confirm = confirm,
                    )
                }
                what != null -> AddReminder(
                    what = cleanWhat(what),
                    at = dateTimeOf(slots[WHEN], now)
                        ?: combine(slots[DAY], slots[TIME])
                        ?: throw IllegalArgumentException("reminder_add without a time: $slots"),
                    confirm = confirm,
                )
                else -> Query(GarCallRunner.dateOf(slots[DAY] ?: slots[WHEN]))
            }
        }

        /** A start as [ResolvedDateTime], whichever of GAR's date/time types it was given in. */
        fun dateTimeOf(value: Any?, now: LocalDateTime): ResolvedDateTime? = when (value) {
            is ResolvedDateTime -> value
            is ResolvedDate -> ResolvedDateTime(value.date.atStartOfDay(), dateSpoken = true, timeSpoken = false)
            // repeat days are dropped: events and reminders are added once
            is ResolvedTime -> ResolvedDateTime(
                GarSentenceBinder.nextOccurrence(value.time, now), dateSpoken = false, timeSpoken = true,
            )
            is LocalDateTime -> ResolvedDateTime(value, dateSpoken = true, timeSpoken = true)
            is LocalDate -> ResolvedDateTime(value.atStartOfDay(), dateSpoken = true, timeSpoken = false)
            else -> null
        }

        /** A day ("tomorrow") and the time asked for afterwards ("five pm"). */
        private fun combine(day: Any?, time: Any?): ResolvedDateTime? {
            val date = GarCallRunner.dateOf(day) ?: return null
            val localTime = when (time) {
                is ResolvedTime -> time.time
                is LocalTime -> time
                is ResolvedDateTime -> time.dateTime.toLocalTime()
                else -> return null
            }
            return ResolvedDateTime(date.atTime(localTime), dateSpoken = true, timeSpoken = true)
        }

        // the model sometimes starts the title span with the command ("schedule a meeting ...");
        // not "book" or "set", which start real titles ("book club")
        private val TITLE_LEADING_WORDS = setOf(
            "a", "an", "the", "my", "new", "called", "named", "titled", "for", "about",
            "schedule", "add", "put", "create",
        )
        private val WHAT_LEADING_WORDS = setOf("to", "about", "of", "that", "i", "need", "should")
        private val SPACES = Regex("\\s+")

        /**
         * The event title as said, without the words before it ("a", "called", ...) and with a
         * capital first letter: "a dentist appointment" -> "Dentist appointment".
         */
        fun cleanTitle(raw: String): String = capitalize(dropLeading(raw, TITLE_LEADING_WORDS))

        /**
         * What to be reminded about, as said: "to call mom" -> "Call mom", "that i need to buy
         * milk" -> "Buy milk".
         */
        fun cleanWhat(raw: String): String = capitalize(dropLeading(raw, WHAT_LEADING_WORDS))

        private fun dropLeading(raw: String, leading: Set<String>): String {
            val words = raw.trim().split(SPACES).filter { it.isNotEmpty() }
            val kept = words.dropWhile { it.lowercase() in leading }
            // never drop everything: "remind me about the" keeps what was said
            return (kept.ifEmpty { words }).joinToString(" ")
        }

        private fun capitalize(text: String): String =
            text.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}
