package org.stypox.dicio.skills.weather

import cc.aaro.gar.router.Utterances
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** What a weather request asks about (gar:weather). */
enum class WeatherTopic {
    /** "what's the weather", "weather tomorrow", "is it sunny", "do I need a jacket" */
    GENERAL,

    /** "what's the temperature", "how cold is it", "is it hot outside" */
    TEMPERATURE,

    /** "how hot will it get", "what's the high today" */
    HIGH,

    /** "how cold will it get tonight", "what's the low" */
    LOW,

    /** "will it rain today", "any rain this weekend" */
    RAIN,

    /** "is it going to snow on friday" */
    SNOW,

    /** "do I need an umbrella" */
    UMBRELLA,

    /** "how windy is it" */
    WIND,

    /** "when will it stop raining": hourly */
    STOPS,

    /** "when will it start raining", "when is it going to rain": hourly */
    STARTS,
}

/** Rain or snow, for [WeatherTopic.STOPS] and [WeatherTopic.STARTS]. */
enum class Precipitation { RAIN, SNOW }

/** The time a weather request is about. */
sealed interface WeatherWhen {
    /** Now: current conditions. */
    data object Now : WeatherWhen

    /** One day ("today", "tomorrow", "friday", "on the 5th"). */
    data class Day(val date: LocalDate) : WeatherWhen

    /** The night of [date] ("tonight", "tomorrow night"): its low. */
    data class Night(val date: LocalDate) : WeatherWhen

    /** Saturday and Sunday from [saturday] ("this weekend"). */
    data class Weekend(val saturday: LocalDate) : WeatherWhen

    /** The next seven days from [from] ("this week", "next week"). */
    data class Week(val from: LocalDate) : WeatherWhen

    /** The next hours ("when will it stop raining"). */
    data object Hours : WeatherWhen
}

/**
 * A weather request as GAR reads it from the whole input (English): the topic, the time and the
 * place as said ("salt lake city"), or null for the home place. The weather sentences' capture
 * only says that some words are there; GAR reads the day and the place itself, so that the model's
 * slotless `weather` call and a sentence match give the same request.
 *
 * @param utterance the input, for the card
 */
data class WeatherRequest(
    val topic: WeatherTopic,
    val time: WeatherWhen,
    val place: String?,
    val precipitation: Precipitation = Precipitation.RAIN,
    val utterance: String = "",
) {
    companion object {
        /** Other languages: Dicio's sentence, current conditions in the captured place. */
        fun current(place: String?) = WeatherRequest(
            WeatherTopic.GENERAL, WeatherWhen.Now, place?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}

/**
 * Reads English weather requests (gar:weather). [parse] is null for inputs that aren't one, which
 * is the weather skill's score guard (a sentence matched with a word swapped never runs it) and
 * the check of the model's `weather` calls: "set the thermostat to 70", "play cold by coldplay"
 * and "it's raining men" name a weather word but ask nothing about the weather.
 */
object WeatherWords {
    /** Words that only ever mean the weather in a request. */
    private val WEATHER_NOUNS = setOf(
        "weather", "forecast", "forecasts", "humidity", "temperature", "temp", "temperatures",
    )

    /** Words that mean the weather when the request is about "it", outside, a day or a place. */
    private val RAIN_WORDS = setOf(
        "rain", "raining", "rainy", "rains", "rained", "drizzle", "drizzling", "showers",
        "storm", "storms", "stormy", "storming", "thunderstorm", "thunderstorms", "thunder",
        "pouring", "downpour",
    )
    private val SNOW_WORDS = setOf(
        "snow", "snowing", "snowy", "snows", "snowfall", "sleet", "sleeting", "hail", "hailing",
        "blizzard", "flurries",
    )
    private val HOT_WORDS = setOf("hot", "warm", "warmer", "hotter", "heat")
    private val COLD_WORDS = setOf(
        "cold", "colder", "cool", "cooler", "chilly", "freezing", "freeze", "frost", "frosty",
    )
    private val WIND_WORDS = setOf("wind", "windy", "winds", "breezy", "gusty", "gusts")
    private val SKY_WORDS = setOf(
        "sunny", "cloudy", "overcast", "foggy", "fog", "humid", "clear", "nice", "degrees",
        "outside",
    )

    /** "do I need an umbrella", "should I bring a jacket": only with one of [NEED_WORDS]. */
    private val UMBRELLA_WORDS = setOf("umbrella", "raincoat")
    private val CLOTHES_WORDS = setOf("jacket", "coat", "sweater", "hoodie", "shorts", "gloves")
    private val NEED_WORDS = setOf(
        "need", "bring", "take", "pack", "grab", "wear", "carry", "want",
    )

    /** Words a request opens with (after [FILLERS]). */
    private val OPENERS = setOf(
        "what", "what's", "whats", "how", "how's", "hows", "will", "is", "isn't", "are",
        "does", "do", "should", "when", "when's", "whens", "any", "tell", "give", "show",
        "check", "get", "weather", "forecast", "temperature", "today's", "tomorrow's",
        "tonight's", "is", "it'll", "gonna", "going", "chance", "chances", "need", "rain",
        "snow", "hows", "current",
    )

    /** Words before the request: "hey", "ok", "can you", "please". */
    private val FILLERS = setOf(
        "hey", "ok", "okay", "so", "um", "uh", "and", "please", "now", "well", "alright", "gar",
        "google",
    )

    /** "can you", "could you", "would you", "will you": a polite request follows. */
    private val POLITE = setOf("can", "could", "would", "will")

    /** Words that lead into a request: "i want to know the weather". */
    private val LEADS = listOf(
        listOf("i", "want", "to", "know"), listOf("i", "wanna", "know"),
        listOf("i", "wonder", "if"), listOf("i", "wonder", "whether"),
        listOf("do", "you", "know", "if"), listOf("do", "you", "know", "whether"), listOf("do", "you", "know"),
        listOf("let", "me", "know", "if"),
    )

    /** Commands of other skills that name a weather word: "set the temperature to 70". */
    private val OTHER_COMMANDS = setOf(
        "play", "put", "listen", "queue", "set", "turn", "switch", "change", "adjust", "make",
        "raise", "lower", "increase", "decrease", "open", "launch", "start", "call", "text",
        "send", "search", "look", "find", "navigate", "take", "remind", "add", "read", "sing",
        "translate", "define", "spell", "convert", "heat", "cool", "warm", "record", "write",
    )

    /** After "temperature": the temperature of something else ("of the oven"). */
    private val NOT_WEATHER_AFTER = setOf("of", "on", "for")

    private val PLACE_PREPOSITIONS = setOf("in", "for", "at", "near", "around", "over")

    /** Places that mean "here": no place. */
    private val HERE = setOf(
        "here", "outside", "out", "my area", "my location", "where i am", "where i live",
        "home", "my city", "town", "my town", "this area", "the area", "my house",
    )

    /** Words after "in" that make it a time, not a place: "in the morning", "in an hour". */
    private val TIME_AFTER_IN = setOf(
        "the morning", "the afternoon", "the evening", "the night", "the next", "an hour",
        "a bit", "a few", "a while", "a minute", "a couple", "the coming", "the week",
        "the weekend", "the meantime", "two", "three", "four", "five", "six", "ten", "half",
        "one", "an", "a",
    )

    private val TIME_WORDS = setOf(
        "today", "today's", "tonight", "tonight's", "tomorrow", "tomorrow's", "now", "right",
        "currently", "weekend", "week", "morning", "afternoon", "evening", "night", "this",
        "next", "later", "on", "the", "day", "after", "monday", "tuesday", "wednesday",
        "thursday", "friday", "saturday", "sunday", "mondays", "please", "like", "going", "be",
        "outside", "out", "there", "at", "moment", "soon", "hour", "hours",
    )

    private val DAY_NAMES = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    /** Words that stop a place: what follows is the time or filler. */
    private val PLACE_END = TIME_WORDS - setOf("the", "at", "day", "after", "be", "like") +
        setOf("right", "please", "for", "in", "and", "with", "going", "will", "be", "gonna")

    /**
     * The weather request in [input], or null when it isn't one.
     *
     * @param today the phone's date, to read days with
     * @param resolveDay GAR's date resolver for days that [parse] doesn't read itself ("on the
     * fifth", "november tenth"): the date, or null
     */
    fun parse(
        input: String,
        today: LocalDate,
        resolveDay: (String) -> LocalDate? = { null },
    ): WeatherRequest? {
        val all = Utterances.words(Utterances.normalize(input))
        val words = request(all) ?: return null
        val topic = topicOf(words) ?: return null
        val precipitation = if (words.any { it in SNOW_WORDS }) Precipitation.SNOW else Precipitation.RAIN
        val time = timeOf(words, today, resolveDay)
            // "is it raining": right now
            ?: if ((topic == WeatherTopic.RAIN || topic == WeatherTopic.SNOW) &&
                words.firstOrNull() in setOf("is", "isn't") &&
                words.any { it == "raining" || it == "snowing" || it == "pouring" }
            ) WeatherWhen.Now else defaultTime(topic, today)
        val place = placeOf(words)
        return WeatherRequest(topic, time, place, precipitation, input)
    }

    /** Whether [input] is a weather request (English). */
    fun asksForWeather(input: String): Boolean = parse(input, LocalDate.of(2000, 1, 1)) != null

    /**
     * The words of the request without leading fillers and polite openers, or null when the input
     * isn't a request (a statement like "it's raining men", another skill's command).
     */
    private fun request(all: List<String>): List<String>? {
        var words = all.dropWhile { it in FILLERS }
        // "can you tell me the weather", "could you please check the forecast"
        if (words.size >= 2 && words[0] in POLITE && words[1] == "you") {
            words = words.drop(2).dropWhile { it in FILLERS }
        }
        // "i want to know the weather", "i wonder if it'll rain"
        var asked = false
        for (lead in LEADS) {
            if (words.take(lead.size) == lead) {
                words = words.drop(lead.size)
                asked = lead.last() == "if" || lead.last() == "whether"
                break
            }
        }
        val first = words.firstOrNull() ?: return null
        if (first in OTHER_COMMANDS) {
            return null
        }
        // after "do you know if", "it's going to rain" is a question, not a statement
        val opens = first in OPENERS || first in DAY_NAMES || first in TIME_WORDS ||
            asked && first in setOf("it", "it's", "its", "there") ||
            // "denver weather", "salt lake city forecast tomorrow"
            words.any { it == "weather" || it == "forecast" } && first !in setOf("it", "it's", "its", "i", "i'm", "the")
        return words.takeIf { opens }
    }

    private fun topicOf(words: List<String>): WeatherTopic? {
        val said = words.toSet()
        val aboutIt = "it" in said || "it's" in said || "it'll" in said || "outside" in said ||
            "out" in said || "there" in said || timeWordIn(words) || placeOf(words) != null ||
            "chance" in said || "chances" in said || "any" in said || "the" in said
        val hasWeatherNoun = words.withIndex().any { (i, word) ->
            word in WEATHER_NOUNS && !(word.startsWith("temp") && words.getOrNull(i + 1) in NOT_WEATHER_AFTER)
        }
        val needs = said.any { it in NEED_WORDS }
        val rain = said.any { it in RAIN_WORDS }
        val snow = said.any { it in SNOW_WORDS }
        val ask = words.firstOrNull()
        val how = ask == "how" || ask == "how's" || ask == "hows"
        val future = "will" in said || "get" in said || "gonna" in said || "going" in said ||
            "it'll" in said || "be" in said || timeWordIn(words.filter { it != "now" && it != "right" })

        return when {
            said.any { it in UMBRELLA_WORDS } -> if (needs || hasWeatherNoun) WeatherTopic.UMBRELLA else null
            (rain || snow) && (ask == "when" || ask == "when's" || ask == "whens") && aboutIt ->
                if ("stop" in said || "end" in said || "over" in said || "done" in said ||
                    "stops" in said || "ends" in said
                ) WeatherTopic.STOPS else WeatherTopic.STARTS
            (rain || snow) && ("stop" in said || "end" in said) && aboutIt -> WeatherTopic.STOPS
            snow && aboutIt -> WeatherTopic.SNOW
            rain && aboutIt -> WeatherTopic.RAIN
            how && said.any { it in HOT_WORDS } && ("it" in said || "outside" in said) ->
                if (future) WeatherTopic.HIGH else WeatherTopic.TEMPERATURE
            how && said.any { it in COLD_WORDS } && ("it" in said || "outside" in said) ->
                if (future) WeatherTopic.LOW else WeatherTopic.TEMPERATURE
            "high" in said && (hasWeatherNoun || "the" in said || "today's" in said) &&
                ask != "how" -> WeatherTopic.HIGH
            "low" in said && (hasWeatherNoun || "the" in said || "tonight's" in said) &&
                ask != "how" -> WeatherTopic.LOW
            words.withIndex().any { (i, word) ->
                word.startsWith("temp") && word in WEATHER_NOUNS &&
                    words.getOrNull(i + 1) !in NOT_WEATHER_AFTER
            } -> WeatherTopic.TEMPERATURE
            "degrees" in said && aboutIt -> WeatherTopic.TEMPERATURE
            said.any { it in WIND_WORDS } && aboutIt -> WeatherTopic.WIND
            said.any { it in CLOTHES_WORDS } && needs -> WeatherTopic.GENERAL
            hasWeatherNoun -> WeatherTopic.GENERAL
            (said.any { it in HOT_WORDS || it in COLD_WORDS }) && ("it" in said || "outside" in said || "out" in said) ->
                WeatherTopic.TEMPERATURE
            // "is it sunny", "how is it outside", "what's it like outside"
            said.any { it in SKY_WORDS } && ("it" in said || "outside" in said) &&
                (said.any { it != "outside" && it in SKY_WORDS } || how || "like" in said) ->
                WeatherTopic.GENERAL
            else -> null
        }
    }

    private fun timeWordIn(words: List<String>): Boolean = words.any {
        it in DAY_NAMES || it in setOf(
            "today", "today's", "tonight", "tonight's", "tomorrow", "tomorrow's", "weekend",
            "week", "morning", "afternoon", "evening", "later",
        )
    }

    private fun defaultTime(topic: WeatherTopic, today: LocalDate): WeatherWhen = when (topic) {
        WeatherTopic.GENERAL, WeatherTopic.TEMPERATURE, WeatherTopic.WIND -> WeatherWhen.Now
        WeatherTopic.STOPS, WeatherTopic.STARTS -> WeatherWhen.Hours
        WeatherTopic.LOW -> WeatherWhen.Night(today)
        else -> WeatherWhen.Day(today)
    }

    private fun timeOf(
        words: List<String>,
        today: LocalDate,
        resolveDay: (String) -> LocalDate?,
    ): WeatherWhen? {
        val text = words.joinToString(" ")
        fun has(phrase: String) = " $text ".contains(" $phrase ")
        val tomorrow = today.plusDays(1)
        return when {
            has("day after tomorrow") -> WeatherWhen.Day(today.plusDays(2))
            has("tomorrow night") || has("tomorrow evening") -> WeatherWhen.Night(tomorrow)
            has("tomorrow") || has("tomorrow's") -> WeatherWhen.Day(tomorrow)
            has("tonight") || has("tonight's") || has("this evening") || has("overnight") ->
                WeatherWhen.Night(today)
            has("weekend") -> WeatherWhen.Weekend(
                if (today.dayOfWeek == DayOfWeek.SUNDAY && !has("next weekend")) {
                    today.minusDays(1)
                } else {
                    today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).let {
                        if (today.dayOfWeek == DayOfWeek.SATURDAY && !has("next weekend")) today else it
                    }
                },
            )
            has("next week") -> WeatherWhen.Week(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)))
            has("this week") || has("the week") || has("week") || has("next few days") ||
                has("coming days") || has("seven day") || has("7 day") || has("ten day") ||
                has("10 day") || has("extended") -> WeatherWhen.Week(today)
            has("right now") || has("now") || has("currently") || has("at the moment") ->
                WeatherWhen.Now
            has("today") || has("today's") || has("this morning") || has("this afternoon") ||
                has("later") || (has("morning") || has("afternoon")) && words.none { it in DAY_NAMES } ->
                WeatherWhen.Day(today)
            else -> words.withIndex().firstNotNullOfOrNull { (i, word) ->
                DAY_NAMES[word]?.let { day ->
                    // "next friday" said on a monday is this friday for most people, as for
                    // GAR's other days; said on a friday, it is a week away
                    val next = words.getOrNull(i - 1) == "next"
                    val date = if (today.dayOfWeek == day && !next) {
                        today
                    } else {
                        today.with(TemporalAdjusters.next(day))
                    }
                    if (words.getOrNull(i + 1) == "night") WeatherWhen.Night(date) else WeatherWhen.Day(date)
                }
            } ?: dayPhrase(words)?.let(resolveDay)?.let { WeatherWhen.Day(it) }
        }
    }

    /** "on the fifth", "for november tenth": the words after "on" or "for", up to a place. */
    private fun dayPhrase(words: List<String>): String? {
        val start = words.indexOfFirst { it == "on" || it == "for" }
        if (start < 0) return null
        val phrase = words.drop(start + 1).takeWhile { it !in PLACE_PREPOSITIONS }
        return phrase.joinToString(" ").takeIf { phrase.isNotEmpty() }
    }

    /**
     * The place said: the words after "in", "for", "at" or "near" up to the time ("weather in salt
     * lake city tomorrow"), or the words before "weather" or "forecast" ("denver weather"). Null
     * for no place, "here", "outside" and times ("in the morning", "in an hour").
     */
    fun placeOf(words: List<String>): String? {
        for ((i, word) in words.withIndex()) {
            if (word !in PLACE_PREPOSITIONS) continue
            val rest = words.drop(i + 1)
            val restText = rest.joinToString(" ")
            if (TIME_AFTER_IN.any { restText == it || restText.startsWith("$it ") }) continue
            val place = rest.takeWhile { it !in PLACE_END }.let { if (it.firstOrNull() == "the") it.drop(1) else it }
            if (place.isEmpty()) continue
            val text = place.joinToString(" ")
            if (text in HERE || text.toIntOrNull() != null || place.all { it in TIME_WORDS || it in DAY_NAMES }) continue
            // "for the weekend", "for friday" are times
            if (place.first() in DAY_NAMES || place.first() in TIME_WORDS) continue
            return text
        }
        // "denver weather", "what's the salt lake city forecast"
        val noun = words.indexOfFirst { it == "weather" || it == "forecast" }
        if (noun > 0) {
            val before = words.take(noun).takeLastWhile {
                it !in OPENERS && it !in FILLERS && it !in TIME_WORDS && it !in DAY_NAMES &&
                    it !in setOf("the", "my", "our", "a", "your", "local", "current", "is", "s", "like")
            }
            if (before.isNotEmpty()) {
                val text = before.joinToString(" ")
                if (text !in HERE) return text
            }
        }
        return null
    }
}

/**
 * Answers to "Which city are you in? You can also say: use my location." (English): a city
 * ("logan", "i'm in salt lake city", "denver colorado") or the location ("use my location",
 * "my location", "where i am"). Anything else is no answer, so that a new command goes to its own
 * skill: a question, another skill's command, or more than [MAX_CITY_WORDS] words.
 */
object WeatherPlaceAnswers {
    private const val MAX_CITY_WORDS = 5

    private val LOCATION_ANSWERS = setOf(
        "use my location", "my location", "use location", "use the location", "location",
        "use my current location", "my current location", "current location", "where i am",
        "use where i am", "here", "right here", "use gps", "gps", "find me", "use my phone's location",
        "use the phone's location", "yes use my location", "ok use my location",
    )

    private val LEADS = listOf(
        "i'm in", "i am in", "im in", "i live in", "we're in", "we are in", "i'm near",
        "i am near", "i'm from", "it's", "its", "it is", "my city is", "the city is", "in",
        "near", "the city of", "city of",
    )

    private val NOT_A_CITY = setOf(
        "what", "what's", "whats", "how", "who", "why", "when", "where", "is", "are", "do", "does",
        "can", "could", "will", "would", "should", "turn", "set", "play", "call", "text", "send",
        "open", "start", "stop", "cancel", "no", "yes", "nope", "never", "nevermind", "not",
        "don't", "dont", "i", "skip", "later",
    )

    /** Whether [input] asks for the phone's location. */
    fun isLocation(input: String): Boolean =
        Utterances.normalize(input).trimEnd() in LOCATION_ANSWERS

    /** The city in [input], or null when it isn't one. */
    fun city(input: String): String? {
        val normalized = Utterances.normalize(input)
        if (normalized.isEmpty() || isLocation(input)) return null
        var text = normalized
        for (lead in LEADS) {
            if (text.startsWith("$lead ")) {
                text = text.removePrefix("$lead ")
                break
            }
        }
        text = text.removeSuffix(" please")
        val words = Utterances.words(text)
        if (words.isEmpty() || words.size > MAX_CITY_WORDS || words[0] in NOT_A_CITY) return null
        if (words.any { it in setOf("weather", "forecast", "timer", "alarm", "music", "song") }) return null
        return text
    }
}
