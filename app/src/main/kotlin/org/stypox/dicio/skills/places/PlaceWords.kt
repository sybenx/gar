package org.stypox.dicio.skills.places

import cc.aaro.gar.router.Utterances

/** The places GAR remembers for the user: "take me home", "directions to work". */
enum class SavedPlaceKind { HOME, WORK }

/**
 * GAR (gar:places). English words about places: home and work ([savedPlace]), nearby searches
 * ([nearbyThing]) and the "how do I get ..." and "get me ..." lines of the navigation sentences
 * that took no place ([isNoPlace]).
 */
object PlaceWords {
    /**
     * The saved place that [where] names: "home", "my house", "back home", "work", "the office",
     * "my job". Only when those words are all of it: "home depot" and "work boots" are places to
     * search for.
     */
    fun savedPlace(where: String?): SavedPlaceKind? {
        val words = Utterances.words(Utterances.normalize(where ?: return null))
            .filter { it !in FILLERS }
        if (words.isEmpty()) {
            return null
        }
        val joined = words.joinToString(" ")
        return when (joined) {
            in HOME_NAMES -> SavedPlaceKind.HOME
            in WORK_NAMES -> SavedPlaceKind.WORK
            else -> null
        }
    }

    /**
     * What a nearby search looks for: "coffee near me" -> "coffee", "the nearest pharmacy" ->
     * "pharmacy", "is there a gas station around here" -> "gas station", or null when [where]
     * doesn't ask for something nearby, or asks about something no map has ("the weather
     * nearby") or is a question or a command ("what's open nearby"). The maps app searches the
     * rest around the user, which offline search does better without "near me".
     */
    fun nearbyThing(where: String?): String? {
        var words = Utterances.words(Utterances.normalize(where ?: return null))
        var nearby = false
        for (marker in TRAILING) {
            if (words.size > marker.size && words.takeLast(marker.size) == marker) {
                words = words.dropLast(marker.size)
                nearby = true
                break
            }
        }
        for (marker in LEADING) {
            if (words.size > marker.size && words.take(marker.size) == marker) {
                words = words.drop(marker.size)
                nearby = true
                break
            }
        }
        return if (nearby) thing(words) else null
    }

    /**
     * What to search the map for in [what], a nearby sentence's capture ("a coffee shop" ->
     * "coffee shop"), or null when it is no thing on a map ([nearbyThing]).
     */
    fun searchable(what: String?): String? {
        return thing(Utterances.words(Utterances.normalize(what ?: return null)))
    }

    /**
     * Whether [input] says near the user at all. Dicio's matcher scores a nearby sentence with
     * its "near me" left out ("get me directions to grandma's house" as "get me .what. near me"),
     * and that is a trip, not a search around the user (navigation's).
     */
    fun saysNearby(input: String): Boolean =
        Utterances.words(Utterances.normalize(input)).any { it in NEARBY_WORDS }

    private val NEARBY_WORDS = setOf(
        "near", "nearby", "nearest", "closest", "close", "around", "area", "here",
    )

    private fun thing(said: List<String>): String? {
        var words = said
        for (lead in LEADS) {
            if (words.size > lead.size && words.take(lead.size) == lead) {
                words = words.drop(lead.size)
                break
            }
        }
        words = words.dropWhile { it in ARTICLES }
        if (words.isEmpty() || words.first() in NOT_A_THING || words.any { it in NO_PLACE }) {
            return null
        }
        return words.joinToString(" ")
    }

    /**
     * Whether a navigation match is no place: "how do I get ...", "get me ..." and "drive me ..."
     * only lead to a place with "to" ("how do I get to the station") or to home or work ("how do
     * I get home"); "how do I get rid of ants", "get me a coffee" and "drive me crazy" are other
     * requests. A question about when, why or whether ("when should I go to bed", "what time
     * should I get to the airport", "should I go to the store") asks for no directions, and
     * [where] naming no place on a map ("bed", "sleep", "the bathroom") is none either.
     */
    fun isNoPlace(input: String, where: String?): Boolean {
        val words = Utterances.words(Utterances.normalize(input))
        if (asksNoDirections(words) || namesNoPlace(where)) {
            return true
        }
        val lead = when {
            words.firstOrNull() == "how" -> words.indexOfFirst { it == "get" || it == "go" }
            words.firstOrNull() in LEAD_VERBS -> 0
            else -> return false
        }
        if (lead < 0) {
            return false
        }
        // "get me directions to grandma's house", "get me the fastest route to the airport"
        if (words.drop(lead + 1).any { it in ROUTE_WORDS }) {
            return false
        }
        return words.getOrNull(lead + 1) != "to" && words.getOrNull(lead + 2) != "to" &&
            savedPlace(where) == null
    }

    /**
     * Whether [words] open with a question that asks for no directions: when, why, whether
     * ("should i ..."), "what time". "How" and "where" questions do ask for them.
     */
    fun asksNoDirections(words: List<String>): Boolean {
        val first = words.dropWhile { it in QUESTION_FILLERS }
        return first.firstOrNull() in NO_DIRECTIONS_OPENERS ||
            first.take(2) == listOf("what", "time")
    }

    /**
     * Whether [where] names only things no maps app finds: "bed", "sleep", "the bathroom". "The
     * nearest restroom" is a search ("nearest" is no such thing).
     */
    fun namesNoPlace(where: String?): Boolean {
        val words = Utterances.words(Utterances.normalize(where ?: return false))
            .filter { it !in FILLERS && it != "a" && it != "an" && it !in WHEN_WORDS }
        return words.isNotEmpty() && words.all { it in NOT_PLACES }
    }

    private val QUESTION_FILLERS = setOf("so", "and", "but", "hey", "ok", "okay", "um", "uh", "well")

    private val NO_DIRECTIONS_OPENERS = setOf(
        "when", "when's", "whens", "why", "why's", "whys", "should", "shall", "shouldn't",
    )

    /** Words said after a non-place: "go to bed early", "go to sleep now". */
    private val WHEN_WORDS = setOf(
        "early", "earlier", "late", "later", "tonight", "today", "tomorrow", "soon", "again",
        "first", "too",
    )

    /** Things people go to that are no place on a map. */
    private val NOT_PLACES = setOf(
        "bed", "sleep", "sleeping", "bathroom", "toilet", "loo", "potty", "restroom", "nap",
    )

    private val LEAD_VERBS = setOf("get", "drive", "walk")

    private val ROUTE_WORDS = setOf("directions", "direction", "route", "way")

    private val FILLERS = setOf(
        "my", "the", "our", "back", "to", "towards", "toward", "please", "now", "right", "me",
        "go", "get", "way", "fastest", "quickest", "route", "directions", "direction", "best",
    )

    private val HOME_NAMES = setOf(
        "home", "house", "my house", "place", "apartment", "flat", "home address",
    )
    private val WORK_NAMES = setOf(
        "work", "office", "job", "workplace", "work place", "work address",
    )

    private val TRAILING = listOf(
        listOf("near", "me"), listOf("nearby"), listOf("near", "by"), listOf("close", "by"),
        listOf("around", "me"), listOf("around", "here"), listOf("near", "here"),
        listOf("close", "to", "me"), listOf("close", "to", "here"), listOf("near", "my", "location"),
        listOf("in", "the", "area"), listOf("in", "my", "area"), listOf("around", "the", "corner"),
        listOf("close", "to", "where", "i", "am"), listOf("near", "where", "i", "am"),
    ).sortedByDescending { it.size }

    private val LEADING = listOf(
        listOf("the", "nearest"), listOf("nearest"), listOf("the", "closest"), listOf("closest"),
        listOf("a", "nearby"), listOf("nearby"), listOf("the", "nearby"), listOf("any", "nearby"),
    ).sortedByDescending { it.size }

    private val LEADS = listOf(
        listOf("is", "there", "a"), listOf("is", "there", "an"), listOf("is", "there", "any"),
        listOf("are", "there", "any"), listOf("are", "there"), listOf("is", "there"),
        listOf("find", "me"), listOf("find"), listOf("show", "me"), listOf("search", "for"),
        listOf("look", "for"), listOf("looking", "for"), listOf("i", "need"),
        listOf("where", "is"), listOf("where's"), listOf("where", "s"), listOf("wheres"),
    ).sortedByDescending { it.size }

    private val ARTICLES = setOf("a", "an", "the", "some", "any", "good", "nearest", "closest")

    /** Questions and commands, not things on a map: "what's open nearby", "turn on wifi". */
    private val NOT_A_THING = setOf(
        "what", "what's", "whats", "how", "when", "why", "who", "who's", "which", "is", "are",
        "do", "does", "can", "could", "will", "set", "turn", "play", "call", "text", "send",
        "tell", "open", "start", "stop", "it", "this", "that", "me", "i", "you", "my",
    )

    /** Nothing a maps app can search for. */
    private val NO_PLACE = setOf(
        "weather", "forecast", "temperature", "rain", "time", "news", "traffic", "earthquake",
        "earthquakes", "events", "concerts",
    )
}
