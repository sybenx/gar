package org.stypox.dicio.skills.agenda

import org.stypox.dicio.skills.notes.NotesText

/** What the user asked of the calendar beyond adding and reading a day. Refs as said. */
sealed interface AgendaRequest {
    /**
     * "What's my next event", "when is my dentist appointment": [about] "" for any event; [where]
     * for "where is my meeting with john", which is answered with the place.
     */
    data class Next(val about: String, val where: Boolean = false) : AgendaRequest

    /**
     * "Move my dentist appointment to 3pm": [ref] names the event ("my dentist appointment"),
     * [from] its time when said ("move my meeting from 3 to 4"), [to] the new time.
     */
    data class Move(val ref: String, val from: String, val to: String) : AgendaRequest

    /** "Cancel my meeting with john"; [all] for "clear my calendar tomorrow". */
    data class Cancel(val ref: String, val all: Boolean) : AgendaRequest
}

/** What the user asked of GAR's reminders. */
sealed interface ReminderRequest {
    /** "What are my reminders"; [day] "today" or "tomorrow" when said, else "". */
    data class List(val day: String) : ReminderRequest

    /** "Cancel the reminder about the dentist": [about] "" for "cancel the reminder". */
    data class Cancel(val about: String) : ReminderRequest

    /** "Delete all reminders." */
    data object CancelAll : ReminderRequest
}

/** Reads calendar requests from the whole input. English. */
object AgendaWords {
    fun request(input: String): AgendaRequest? {
        val text = NotesText.apostrophesOff(NotesText.of(input))
        if (text.isEmpty() || text.split(" ").any { it in NOT_EVENTS }) {
            return null
        }
        val where = text.startsWith("where")
        NEXT.firstNotNullOfOrNull { it.matchEntire(text) }?.let { match ->
            return AgendaRequest.Next(keyword(match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()), where)
        }
        WHEN_IS.matchEntire(text)?.let { match ->
            val about = keyword(match.groupValues[1].ifEmpty { match.groupValues[2] })
            return AgendaRequest.Next(about, where)
        }
        FIND.firstNotNullOfOrNull { it.matchEntire(text) }?.let { match ->
            // "do i have a dentist appointment", "is there any haircut appointment", "check for
            // a haircut appointment": the next one by name. A day said is the calendar skill's
            // ("do i have a meeting tomorrow"), and so is a bare kind ("do i have any meetings")
            val about = keyword(match.groupValues[1])
            return if (about.isEmpty() || saysWhen(about)) null else AgendaRequest.Next(about)
        }
        CLEAR_DAY.firstNotNullOfOrNull { it.matchEntire(text) }?.let { match ->
            val day = match.groupValues[1]
            // "delete all my voicemails" says no day: not the calendar's
            return if (saysWhen(day)) AgendaRequest.Cancel(day, all = true) else null
        }
        MOVE.matchEntire(text)?.let { match ->
            val verb = match.groupValues[1]
            var ref = match.groupValues[2]
            var from = ""
            FROM.matchEntire(ref)?.let { ref = it.groupValues[1]; from = it.groupValues[2] }
            val to = match.groupValues[3]
            val calendarVerb = verb in CALENDAR_VERBS
            // "switch my call to speaker" moves nothing in time
            if (to.startsWith("by ") || !isEventRef(ref, loose = calendarVerb) || (!calendarVerb && !saysWhen(to))) {
                return null
            }
            return AgendaRequest.Move(ref, from, to)
        }
        CANCEL.matchEntire(text)?.let { match ->
            val ref = match.groupValues[1]
            return if (isEventRef(ref, loose = false)) AgendaRequest.Cancel(ref, all = false) else null
        }
        return null
    }

    /**
     * What to look for in the titles, from the words said about an event: "next haircut" is
     * "haircut" ("when's my next haircut appointment", 2026-11-05), "a dentist" is "dentist".
     * The skill matches each word against the titles by word or prefix.
     */
    fun keyword(about: String): String =
        about.split(" ").filter { it.isNotEmpty() && it !in KEYWORD_FILLERS }.joinToString(" ")

    /**
     * Whether [text] says a day or a time ("friday", "the 5th", "3pm", "three thirty", "next
     * week", "later"), by its words: the skill reads the actual date with Dicio's parser.
     */
    fun saysWhen(text: String): Boolean = text.split(" ").any { word ->
        word in WHEN_WORDS || word.any { it.isDigit() } || ORDINAL.matches(word)
    }

    /**
     * Whether [ref] names an event: it says event, meeting, appointment and the like, "with
     * someone" or "on my calendar"; with [loose] (after "reschedule", "postpone") any words do.
     */
    fun isEventRef(ref: String, loose: Boolean): Boolean {
        val words = ref.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty() || words.all { it in REF_FILLERS }) {
            return false
        }
        return loose || words.any { it in EVENT_NOUNS } || "with" in words ||
            ref.contains(CALENDAR_PLACE)
    }

    /** Things that are no event: "cancel my reminder", "delete the alarm", "move the note". */
    val NOT_EVENTS = setOf(
        "reminder", "reminders", "alarm", "alarms", "timer", "timers", "note", "notes", "list",
        "text", "message", "messages", "email", "emails", "order", "subscription", "download",
        "file", "files", "photo", "photos", "picture", "song", "app", "volume", "brightness",
        "notification", "notifications", "contact", "contacts", "voicemail", "voicemails",
        "downloads", "apps",
    )

    /** Words that say a day or a time, see [saysWhen]. */
    private val WHEN_WORDS = setOf(
        "today", "tonight", "tomorrow", "yesterday", "monday", "tuesday", "wednesday", "thursday",
        "friday", "saturday", "sunday", "weekend", "week", "month", "year", "january", "february",
        "march", "april", "may", "june", "july", "august", "september", "october", "november",
        "december", "morning", "afternoon", "evening", "night", "noon", "midnight", "am", "pm",
        "oclock", "clock", "later", "earlier", "hour", "hours", "minutes", "day", "days",
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirty", "fifteen", "forty", "half", "quarter",
    )

    /** "5th", "twentieth", "first". */
    private val ORDINAL = Regex(
        "\\d+(?:st|nd|rd|th)|first|second|third|" +
            "\\w*(?:fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|teenth|tieth)"
    )

    /** What names an event in a request ("my dentist appointment"). */
    val EVENT_NOUNS = setOf(
        "event", "events", "appointment", "appointments", "meeting", "meetings", "reservation",
        "booking", "class", "lesson", "session", "party", "dinner", "lunch", "breakfast",
        "brunch", "interview", "plans", "practice", "checkup", "rehearsal", "conference",
        "standup", "sync", "call", "visit",
    )

    /** Words around an event's name that are no part of it, see [keyword]. */
    private val KEYWORD_FILLERS = setOf(
        "my", "the", "our", "a", "an", "any", "some", "this", "that", "next", "upcoming",
        "coming", "first", "soonest", "nearest", "scheduled",
    )

    private val REF_FILLERS = setOf("my", "the", "our", "a", "an", "this", "that", "it", "next", "upcoming")

    /** After these any event name works: they only ever change plans. */
    private val CALENDAR_VERBS = setOf("reschedule", "postpone", "push back", "bring forward", "put off")

    private val CALENDAR_PLACE = Regex("(?:on|from|in) (?:my|the) (?:calendar|agenda|schedule)")

    private const val NOUN =
        "(?:event|meeting|appointment|thing|calendar event|appointment|thing on my calendar|" +
            "event on my calendar|meeting on my calendar|appointment on my calendar)"

    private val NEXT = listOf(
        Regex(
            "(?:whats|what is|what s|when is|whens|when s|where is|wheres|where s|what time is)" +
                "(?: my| the) next $NOUN(?: (?:with|about|for) (.+?))?(?: today| tomorrow)?"
        ),
        Regex(
            "(?:whats|what is|what s) (?:next|coming up next|up next|coming up)" +
                "(?: on my (?:calendar|agenda|schedule))?(?: today)?"
        ),
        Regex("what do i have (?:next|coming up)(?: on my (?:calendar|agenda|schedule))?(?: today)?"),
        Regex("when do i have (?:my |a |the )?next $NOUN(?: (?:with|about|for) (.+?))?"),
    )

    /** "When is my dentist appointment", "when's my meeting with john". */
    private val WHEN_IS = Regex(
        "(?:when is|whens|when s|what time is|where is|wheres|where s)(?: my| the) " +
            "(?:(.+?) (?:appointment|meeting|event|reservation|class|lesson|party|dinner|lunch|" +
            "interview|checkup|call)|(?:appointment|meeting|event|call|lunch|dinner) with (.+))"
    )

    private const val KIND =
        "(?:appointments?|meetings?|events?|reservations?|class|classes|lessons?|party|dinner|" +
            "lunch|interview|checkup|call|session)"

    private const val ON_CALENDAR =
        "(?: coming up| scheduled| soon| (?:on|in) (?:my|the)(?: next)? (?:calendar|agenda|schedule))?"

    private val FIND = listOf(
        Regex("(?:do|will) i have (.+?) $KIND$ON_CALENDAR"),
        Regex("(?:is|are) there (.+?) $KIND$ON_CALENDAR"),
        Regex("(?:check|look|search) for (.+?) $KIND$ON_CALENDAR"),
        Regex(
            "(?:check|look|search)(?: (?:on|in))? (?:my|the) (?:calendar|agenda|schedule) for " +
                "(.+?)(?: $KIND)?"
        ),
    )

    private val CLEAR_DAY = listOf(
        Regex(
            "(?:clear|empty)(?: out)? (?:my|the) (?:calendar|agenda|schedule) (?:for |on )?(.+)"
        ),
        Regex(
            "(?:cancel|delete|remove|clear) (?:all|everything)(?: of)?(?: my| the)?" +
                "(?: events| meetings| appointments)?(?: on my calendar)? (?:for |on )?" +
                "(today|tomorrow|tonight|(?:this |next )?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|.+)"
        ),
    )

    private val MOVE = Regex(
        "(move|reschedule|change|push|shift|postpone|bump|delay|bring forward|push back|put off|switch)" +
            "(?: back| forward)? (.+?) (?:to|until|till|for) (.+)"
    )

    /** "my meeting from three": the time it is at now. */
    private val FROM = Regex("(.+?) from (.+)")

    private val CANCEL = Regex(
        "(?:cancel|delete|remove|clear|drop|scrap|call off|get rid of|take off)(?: my| the)? (.+?)" +
            "(?: from my calendar| off my calendar)?"
    )
}

/** Reads reminder requests from the whole input. English. */
object ReminderWords {
    fun request(input: String): ReminderRequest? {
        val text = NotesText.apostrophesOff(NotesText.of(input))
        if (text.isEmpty()) {
            return null
        }
        LIST.firstNotNullOfOrNull { it.matchEntire(text) }?.let { match ->
            return ReminderRequest.List(match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty())
        }
        if (CANCEL_ALL.any { it.matches(text) }) {
            return ReminderRequest.CancelAll
        }
        CANCEL.matchEntire(text)?.let { match ->
            val about = match.groupValues[2].ifEmpty { match.groupValues[1] }
            return ReminderRequest.Cancel(about.removePrefix("my ").removePrefix("the "))
        }
        DONT_REMIND.matchEntire(text)?.let { return ReminderRequest.Cancel(it.groupValues[1]) }
        return null
    }

    private const val DAY = "(?: (?:for |on )?(today|tomorrow|tonight))?"

    private val LIST = listOf(
        Regex(
            "(?:what are|whats|what s|what is|show|read|list|tell me|check|open)(?: me)?(?: all)?(?: of)?" +
                "(?: my| the)? (?:upcoming |current |active |next )?reminders?$DAY"
        ),
        Regex("(?:do i have|have i got|are there|is there)(?: any| a)? reminders?$DAY"),
        Regex("any reminders$DAY"),
        Regex("what reminders do i have$DAY"),
        Regex("(?:what|when) (?:did i ask you|have i asked you|did i tell you) to remind me(?: about)?"),
    )

    private val CANCEL_ALL = listOf(
        Regex(
            "(?:delete|cancel|clear|remove|erase|get rid of|turn off|dismiss)(?: all| every)(?: of)?" +
                "(?: my| the)? reminders?"
        ),
        Regex("(?:delete|cancel|clear|remove|erase|get rid of|turn off)(?: my| the)? reminders"),
        Regex("clear (?:out )?my reminders"),
    )

    private val CANCEL = Regex(
        "(?:cancel|delete|remove|clear|erase|dismiss|get rid of|turn off|stop)(?: my| the| that| this)?" +
            "(?: (.+?))? reminder(?: (?:about|to|for|that|of|at|on) (.+))?"
    )

    private val DONT_REMIND = Regex("(?:dont|do not|don t) remind me (?:to |about |of )?(.+)")
}
