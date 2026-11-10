package org.stypox.dicio.skills.world_time

/** What a question about the time somewhere else asks. */
sealed interface WorldTimeRequest {
    /** "What time is it in Tokyo?" */
    data class TimeIn(val place: Place) : WorldTimeRequest

    /**
     * "What's the time difference with London?": how far [place]'s clock is from [from]'s, or
     * from the phone's when [from] is null.
     */
    data class Difference(val place: Place, val from: Place?) : WorldTimeRequest
}

/** Reads the places out of what the user said (English), with [WorldPlaces]. */
object WorldTimePhrases {
    /**
     * The place in a captured "time in <place>" phrase: "tokyo", "tokyo right now", "paris
     * france", or null when it isn't a place GAR knows.
     */
    fun timeIn(capture: String): Place? = find(words(capture))

    /**
     * The name of the place in a captured "time in <place>" phrase that GAR doesn't know ("moab"
     * -> "Moab", "the bronx" -> "the Bronx"), for "I don't know the time in Moab." instead of the
     * local time; null when the capture may well be no place: more than three words, a number or
     * a duration ("in two hours"), a time format ("in military time", "in 24 hour format"), the
     * phone's own place ("in my time zone", "here") or a part of the day ("in the morning").
     */
    fun unknownPlace(capture: String): String? {
        val words = withoutFillers(words(capture)).split(' ').filter { it.isNotEmpty() }
        if (words.none { it != "the" } || words.size > MAX_UNKNOWN_WORDS || isHere(words) ||
            words.any { word -> word in NOT_PLACE_WORDS || !word.all { it.isLetter() } }
        ) {
            return null
        }
        return words.joinToString(" ") { word ->
            if (word == "the") word else word.replaceFirstChar { it.titlecase() }
        }
    }

    /**
     * The places of a captured "time difference with <place>" phrase: "london" (from here),
     * "here and tokyo", "new york and london" (London compared with New York). Null when a place
     * isn't known.
     */
    fun difference(capture: String): WorldTimeRequest.Difference? {
        val words = words(capture).dropWhile { it == "between" }
        find(words)?.let { return WorldTimeRequest.Difference(it, null) }
        for ((i, word) in words.withIndex()) {
            if (word != "and") {
                continue
            }
            val first = words.subList(0, i)
            val second = words.subList(i + 1, words.size)
            val firstHere = isHere(first)
            val secondHere = isHere(second)
            val firstPlace = if (firstHere) null else find(first)
            val secondPlace = if (secondHere) null else find(second)
            val difference = when {
                firstHere && secondPlace != null -> WorldTimeRequest.Difference(secondPlace, null)
                secondHere && firstPlace != null -> WorldTimeRequest.Difference(firstPlace, null)
                firstPlace != null && secondPlace != null ->
                    WorldTimeRequest.Difference(secondPlace, firstPlace)
                else -> null
            }
            difference?.let { return it }
        }
        return null
    }

    /**
     * For the model's `current_time` calls: the place the utterance asks about ("what's the time
     * over in tokyo"), with a time difference when it asks for one ("how far ahead is london"),
     * or null when it names no place GAR knows (then it is the local time, as before).
     */
    fun fromUtterance(utterance: String): WorldTimeRequest? {
        val words = words(utterance)
        if (words.any { it in DIFFERENCE_WORDS }) {
            val places = placesAfter(words, DIFFERENCE_PLACE_LEADS)
            return when (places.size) {
                0 -> null
                1 -> WorldTimeRequest.Difference(places[0], null)
                else -> WorldTimeRequest.Difference(places[1], places[0])
            }
        }
        return placesAfter(words, setOf("in")).firstOrNull()?.let { WorldTimeRequest.TimeIn(it) }
    }

    /**
     * The places named right after one of [leads], in order, each the longest run of words that
     * is a place.
     */
    private fun placesAfter(words: List<String>, leads: Set<String>): List<Place> {
        val places = mutableListOf<Place>()
        var i = 0
        while (i < words.size) {
            if (words[i] !in leads) {
                i += 1
                continue
            }
            val rest = words.subList(i + 1, words.size)
            val match = (rest.size downTo 1).firstNotNullOfOrNull { length ->
                find(rest.subList(0, length))?.let { Pair(it, length) }
            }
            if (match == null) {
                i += 1
            } else {
                if (places.none { it.zone == match.first.zone && it.name == match.first.name }) {
                    places.add(match.first)
                }
                i += 1 + match.second
            }
        }
        return places
    }

    private fun words(text: String): List<String> =
        WorldPlaces.normalize(text).split(' ').filter { it.isNotEmpty() }

    /**
     * The place [words] name: as they are, else with the fillers after them taken off one by one
     * ("tokyo right now", "london local time"). As they are first: "eastern standard time" is a
     * name that ends in a filler.
     */
    private fun find(words: List<String>): Place? =
        withFillersOff(words).firstNotNullOfOrNull { WorldPlaces.find(it.joinToString(" ")) }

    /** [words], then without one more of the fillers after them each time. */
    private fun withFillersOff(words: List<String>): List<List<String>> {
        val forms = mutableListOf(words)
        while (true) {
            val last = forms.last()
            val filler = TRAILING_FILLERS.firstOrNull {
                last.size > it.size && last.takeLast(it.size) == it
            } ?: return forms
            forms.add(last.dropLast(filler.size))
        }
    }

    /** [words] without the fillers around a place: "tokyo right now", "the time in tokyo". */
    private fun withoutFillers(words: List<String>): String =
        withFillersOff(words).last().joinToString(" ")

    private fun isHere(words: List<String>): Boolean =
        words.joinToString(" ") in HERE || withoutFillers(words) in HERE

    /** Words of a question about a time difference rather than about the time. */
    private val DIFFERENCE_WORDS = setOf("difference", "ahead", "behind")

    /** Words before a place in a question about a time difference. */
    private val DIFFERENCE_PLACE_LEADS =
        setOf("with", "between", "and", "to", "from", "in", "is", "of", "than", "for")

    /** What the phone's own place is called. */
    private val HERE = setOf(
        "here", "me", "us", "my time", "my place", "my location", "my time zone", "where i am",
        "where we are", "local time", "my local time", "home",
    )

    private val TRAILING_FILLERS = listOf(
        "right now", "now", "currently", "at the moment", "at this moment", "today", "please",
        "time", "local", "for me", "then",
    ).map { it.split(' ') }

    /** At most this many words of a place GAR doesn't know, see [unknownPlace]. */
    private const val MAX_UNKNOWN_WORDS = 3

    /** Words of what follows "what time is it in" that isn't a place, see [unknownPlace]. */
    private val NOT_PLACE_WORDS = setOf(
        // the phone's own place, and words around a time
        "my", "your", "our", "this", "that", "here", "there", "a", "an", "it",
        // time formats and durations
        "military", "format", "hour", "hours", "minute", "minutes", "second", "seconds", "day",
        "days", "week", "weeks", "month", "months", "year", "years", "zone", "half", "quarter",
        "few", "couple",
        // parts of the day and days
        "morning", "afternoon", "evening", "night", "tonight", "noon", "midnight", "today",
        "tomorrow", "yesterday", "am", "pm",
        // numbers
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "fifteen", "twenty", "thirty", "forty", "fifty", "hundred",
    )
}
