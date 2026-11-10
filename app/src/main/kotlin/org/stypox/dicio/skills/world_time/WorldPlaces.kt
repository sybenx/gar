package org.stypox.dicio.skills.world_time

import cc.aaro.gar.router.Utterances
import java.time.ZoneId

/** A place people ask the time of: its name as GAR says it, and its time zone. */
data class Place(val name: String, val zone: ZoneId)

/**
 * Offline place names to time zones (English): the city of every IANA zone id ("tokyo",
 * "new york", "buenos aires"), plus [PLACES]: countries, US states, Canadian provinces, big
 * cities without a zone of their own, and time zone names ("pacific time", "utc"). A country or
 * state with several zones takes the one where most people live, and its name says which
 * ("Sydney, Australia").
 */
object WorldPlaces {
    /**
     * The place [text] names as a whole (after [normalize]), or null. Also a city with its state
     * or country ("portland maine", "paris texas", "perth australia"): a US state or Canadian
     * province decides the zone, since a city's name alone may be another city's ("Portland,
     * Maine" isn't Portland, Oregon); a country only names the city. "Washington" after a city is
     * the state ("Seattle, Washington"), and Georgia the country for its own cities ("Tbilisi,
     * Georgia") but the state for all others ("Athens, Georgia").
     */
    fun find(text: String): Place? {
        val key = normalize(text)
        if (key.isEmpty()) {
            return null
        }
        findExact(key)?.let { return it }
        val words = key.split(' ')
        for (split in 1 until words.size) {
            val city = findExact(words.subList(0, split).joinToString(" ")) ?: continue
            val region = words.subList(split, words.size).joinToString(" ")
            val state = findRegion(region)
            if (state != null) {
                val zone = if (STATES_THAT_ARE_COUNTRIES[normalize(state.name)] == city.zone.id) {
                    city.zone
                } else {
                    state.zone
                }
                return Place("${city.name}, ${state.name.removeSuffix(" State")}", zone)
            }
            val country = findExact(region) ?: continue
            return Place("${city.name}, ${country.name.substringAfterLast(", ")}", city.zone)
        }
        return null
    }

    /** The US state or Canadian province [key] names ("washington" is the state here). */
    private fun findRegion(key: String): Place? =
        regionsByName[key] ?: regionsByName[key.removeSuffix(" state").removeSuffix(" province")]

    private fun findExact(key: String): Place? =
        byName[key]
            ?: byName[key.removePrefix("the ")]
            ?: CITY_OR_STATE.firstNotNullOfOrNull { suffix ->
                key.removeSuffix(suffix).takeIf { it != key }?.let { byName[it] }
            }

    /**
     * Lowercase words without apostrophes, dots or other symbols: "St. John's" -> "st johns",
     * "Hawai'i" -> "hawaii", "Port-au-Prince" -> "port au prince".
     */
    fun normalize(text: String): String =
        Utterances.normalize(text.replace(APOSTROPHE_OR_DOT, "")).replace('_', ' ')

    private val APOSTROPHE_OR_DOT = Regex("['\u2019\u2018\u02BC.]")

    /** "new york city", "washington state", "ontario province": the place without them. */
    private val CITY_OR_STATE = listOf(" city", " state", " province")

    /** Areas of the IANA ids whose last part is a place ("Asia/Tokyo"; not "Etc/GMT+5"). */
    private val ZONE_AREAS = setOf(
        "Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic", "Australia", "Europe",
        "Indian", "Pacific",
    )

    /** Last parts of IANA ids that aren't place names people say ("Australia/North"). */
    private val NOT_PLACES = setOf("north", "south", "east", "west", "yancowinna")

    private val byName: Map<String, Place> by lazy {
        val names = HashMap<String, Place>()
        for (id in ZoneId.getAvailableZoneIds().sorted()) {
            val parts = id.split('/')
            if (parts.size < 2 || parts[0] !in ZONE_AREAS) {
                continue
            }
            val city = parts.last()
            val key = normalize(city)
            // "Australia/ACT", "Australia/NSW": abbreviations, not names
            if (key.length <= 3 || key in NOT_PLACES) {
                continue
            }
            names.putIfAbsent(key, Place(city.replace('_', ' '), ZoneId.of(id)))
        }
        for ((name, zone, aliases) in TIME_ZONES + REGIONS + COUNTRIES_AND_CITIES) {
            // an older time zone database may not know a renamed zone ("Europe/Kyiv")
            val place = Place(name, runCatching { ZoneId.of(zone) }.getOrNull() ?: continue)
            for (alias in aliases + name) {
                names[normalize(alias)] = place
            }
        }
        names
    }

    /** [REGIONS] by name, which decide the zone of a city said with them. */
    private val regionsByName: Map<String, Place> by lazy {
        val names = HashMap<String, Place>()
        for ((name, zone, aliases) in REGIONS) {
            val place = Place(name, ZoneId.of(zone))
            for (alias in aliases + name) {
                names[normalize(alias)] = place
            }
        }
        names["washington"] = names.getValue("washington state")
        names
    }

    /** US states that are also countries, by the country's zone: "Tbilisi, Georgia". */
    private val STATES_THAT_ARE_COUNTRIES = mapOf("georgia" to "Asia/Tbilisi")

    private data class Entry(val name: String, val zone: String, val aliases: List<String>)

    private fun p(name: String, zone: String, vararg aliases: String) =
        Entry(name, zone, aliases.toList())

    private const val NEW_YORK = "America/New_York"
    private const val CHICAGO = "America/Chicago"
    private const val DENVER = "America/Denver"
    private const val LOS_ANGELES = "America/Los_Angeles"

    private val TIME_ZONES = listOf(
        p("Eastern Time", NEW_YORK, "eastern", "eastern standard time", "eastern daylight time",
            "est", "edt", "east coast"),
        p("Central Time", CHICAGO, "central", "central standard time", "central daylight time",
            "cst", "cdt"),
        p("Mountain Time", DENVER, "mountain", "mountain standard time", "mountain daylight time",
            "mst", "mdt"),
        p("Pacific Time", LOS_ANGELES, "pacific", "pacific standard time",
            "pacific daylight time", "pst", "pdt", "west coast"),
        p("Alaska Time", "America/Anchorage", "akst", "akdt"),
        p("Hawaii Time", "Pacific/Honolulu", "hst"),
        p("Atlantic Time", "America/Halifax", "atlantic standard time", "atlantic daylight time",
            "ast", "adt"),
        p("UTC", "UTC", "gmt", "greenwich", "greenwich mean time", "coordinated universal time",
            "universal time", "zulu", "zulu time"),
        p("Central European Time", "Europe/Paris", "cet", "cest"),
        p("Japan Standard Time", "Asia/Tokyo", "jst"),
    )

    /** US states and Canadian provinces (several zones: the one where most people live). */
    private val REGIONS = listOf(
        p("Alabama", CHICAGO), p("Alaska", "America/Anchorage"), p("Arizona", "America/Phoenix"),
        p("Arkansas", CHICAGO), p("California", LOS_ANGELES), p("Colorado", DENVER),
        p("Connecticut", NEW_YORK), p("Delaware", NEW_YORK), p("Florida", NEW_YORK),
        p("Georgia", NEW_YORK), p("Hawaii", "Pacific/Honolulu"), p("Idaho", "America/Boise"),
        p("Illinois", CHICAGO), p("Indiana", "America/Indiana/Indianapolis"), p("Iowa", CHICAGO),
        p("Kansas", CHICAGO), p("Kentucky", NEW_YORK), p("Louisiana", CHICAGO),
        p("Maine", NEW_YORK), p("Maryland", NEW_YORK), p("Massachusetts", NEW_YORK),
        p("Michigan", "America/Detroit"), p("Minnesota", CHICAGO), p("Mississippi", CHICAGO),
        p("Missouri", CHICAGO), p("Montana", DENVER), p("Nebraska", CHICAGO),
        p("Nevada", LOS_ANGELES), p("New Hampshire", NEW_YORK), p("New Jersey", NEW_YORK, "jersey"),
        p("New Mexico", DENVER), p("North Carolina", NEW_YORK), p("North Dakota", CHICAGO),
        p("Ohio", NEW_YORK), p("Oklahoma", CHICAGO), p("Oregon", LOS_ANGELES),
        p("Pennsylvania", NEW_YORK), p("Rhode Island", NEW_YORK), p("South Carolina", NEW_YORK),
        p("South Dakota", CHICAGO), p("Tennessee", CHICAGO), p("Texas", CHICAGO),
        p("Utah", DENVER), p("Vermont", NEW_YORK), p("Virginia", NEW_YORK),
        p("Washington State", LOS_ANGELES), p("West Virginia", NEW_YORK),
        p("Wisconsin", CHICAGO), p("Wyoming", DENVER), p("Puerto Rico", "America/Puerto_Rico"),
        p("Alberta", "America/Edmonton"), p("British Columbia", "America/Vancouver"),
        p("Ontario", "America/Toronto"), p("Quebec", "America/Toronto"),
        p("Manitoba", "America/Winnipeg"), p("Nova Scotia", "America/Halifax"),
        p("Newfoundland", "America/St_Johns"),
    )

    /** Countries, and cities without a zone of their own. */
    private val COUNTRIES_AND_CITIES = listOf(
        // "washington" alone is the capital, as people mostly mean when they ask its time
        p("Washington, D.C.", NEW_YORK, "washington", "washington dc", "washington d c", "dc",
            "d c"),

        // US cities without a zone of their own, and what people call them
        p("New York", NEW_YORK, "new york city", "nyc", "manhattan", "brooklyn"),
        p("Los Angeles", LOS_ANGELES, "la", "l a"),
        p("San Francisco", LOS_ANGELES, "sf", "san fran"), p("Seattle", LOS_ANGELES),
        p("San Diego", LOS_ANGELES), p("San Jose", LOS_ANGELES),
        p("Las Vegas", LOS_ANGELES, "vegas"), p("Portland", LOS_ANGELES),
        p("Sacramento", LOS_ANGELES), p("Boston", NEW_YORK),
        p("Philadelphia", NEW_YORK, "philly"), p("Miami", NEW_YORK), p("Atlanta", NEW_YORK),
        p("Orlando", NEW_YORK), p("Charlotte", NEW_YORK), p("Pittsburgh", NEW_YORK),
        p("Baltimore", NEW_YORK), p("Cleveland", NEW_YORK), p("Columbus", NEW_YORK),
        p("Tampa", NEW_YORK), p("Dallas", CHICAGO), p("Houston", CHICAGO),
        p("Austin", CHICAGO), p("San Antonio", CHICAGO), p("New Orleans", CHICAGO),
        p("Nashville", CHICAGO), p("Memphis", CHICAGO), p("Minneapolis", CHICAGO),
        p("St. Louis", CHICAGO, "saint louis"), p("Kansas City", CHICAGO),
        p("Milwaukee", CHICAGO), p("Salt Lake City", DENVER, "salt lake"),
        p("Albuquerque", DENVER), p("Honolulu", "Pacific/Honolulu"),
        p("Maui", "Pacific/Honolulu"), p("Kauai", "Pacific/Honolulu"),
        p("Oahu", "Pacific/Honolulu"),
        // Puerto Rico's capital, not the Argentine city of the IANA id
        p("San Juan, Puerto Rico", "America/Puerto_Rico", "san juan"),

        // countries (several zones: the capital's, or the biggest city's, named)
        p("Afghanistan", "Asia/Kabul"), p("Argentina", "America/Argentina/Buenos_Aires"),
        p("Sydney, Australia", "Australia/Sydney", "australia"),
        p("Austria", "Europe/Vienna"), p("Bangladesh", "Asia/Dhaka"),
        p("Belgium", "Europe/Brussels"), p("Sao Paulo, Brazil", "America/Sao_Paulo", "brazil"),
        p("Toronto, Canada", "America/Toronto", "canada"), p("Chile", "America/Santiago"),
        p("China", "Asia/Shanghai"), p("Colombia", "America/Bogota"),
        p("Costa Rica", "America/Costa_Rica"), p("Croatia", "Europe/Zagreb"),
        p("Cuba", "America/Havana"), p("Czech Republic", "Europe/Prague", "czechia"),
        p("Denmark", "Europe/Copenhagen"), p("Dominican Republic", "America/Santo_Domingo"),
        p("Ecuador", "America/Guayaquil"), p("Egypt", "Africa/Cairo"),
        p("Ethiopia", "Africa/Addis_Ababa"), p("Finland", "Europe/Helsinki"),
        p("France", "Europe/Paris"), p("Germany", "Europe/Berlin"),
        p("Ghana", "Africa/Accra"), p("Greece", "Europe/Athens"),
        p("Guatemala", "America/Guatemala"), p("Hungary", "Europe/Budapest"),
        p("Iceland", "Atlantic/Reykjavik"), p("India", "Asia/Kolkata"),
        p("Jakarta, Indonesia", "Asia/Jakarta", "indonesia"), p("Iran", "Asia/Tehran"),
        p("Iraq", "Asia/Baghdad"), p("Ireland", "Europe/Dublin"),
        p("Israel", "Asia/Jerusalem"), p("Italy", "Europe/Rome"),
        p("Jamaica", "America/Jamaica"), p("Japan", "Asia/Tokyo"), p("Jordan", "Asia/Amman"),
        p("Kenya", "Africa/Nairobi"), p("Lebanon", "Asia/Beirut"),
        p("Malaysia", "Asia/Kuala_Lumpur"), p("Mexico", "America/Mexico_City"),
        p("Morocco", "Africa/Casablanca"), p("Nepal", "Asia/Kathmandu"),
        p("Netherlands", "Europe/Amsterdam", "holland", "the netherlands"),
        p("New Zealand", "Pacific/Auckland"), p("Nigeria", "Africa/Lagos"),
        p("North Korea", "Asia/Pyongyang"), p("Norway", "Europe/Oslo"),
        p("Pakistan", "Asia/Karachi"), p("Panama", "America/Panama"), p("Peru", "America/Lima"),
        p("Philippines", "Asia/Manila", "the philippines"), p("Poland", "Europe/Warsaw"),
        p("Portugal", "Europe/Lisbon"), p("Qatar", "Asia/Qatar"), p("Romania", "Europe/Bucharest"),
        p("Moscow, Russia", "Europe/Moscow", "russia"),
        // the country, not the Pacific/Samoa link, which is American Samoa (a day apart)
        p("Samoa", "Pacific/Apia"), p("American Samoa", "Pacific/Pago_Pago"),
        p("Saudi Arabia", "Asia/Riyadh"),
        p("Scotland", "Europe/London"), p("South Africa", "Africa/Johannesburg"),
        p("South Korea", "Asia/Seoul", "korea"), p("Spain", "Europe/Madrid"),
        p("Sri Lanka", "Asia/Colombo"), p("Sweden", "Europe/Stockholm"),
        p("Switzerland", "Europe/Zurich"), p("Taiwan", "Asia/Taipei"),
        p("Thailand", "Asia/Bangkok"), p("Turkey", "Europe/Istanbul", "turkiye"),
        p("Ukraine", "Europe/Kiev"),
        p("United Arab Emirates", "Asia/Dubai", "uae", "the uae", "emirates",
            "the united arab emirates"),
        p("United Kingdom", "Europe/London", "uk", "the uk", "england", "britain",
            "great britain", "the united kingdom"),
        p("Venezuela", "America/Caracas"), p("Vietnam", "Asia/Ho_Chi_Minh"),
        p("Wales", "Europe/London"),

        // big cities that share another city's zone
        p("Beijing", "Asia/Shanghai"), p("Shenzhen", "Asia/Shanghai"),
        p("Guangzhou", "Asia/Shanghai"), p("Mumbai", "Asia/Kolkata", "bombay"),
        p("Delhi", "Asia/Kolkata", "new delhi"), p("Bangalore", "Asia/Kolkata", "bengaluru"),
        p("Chennai", "Asia/Kolkata"), p("Hyderabad", "Asia/Kolkata"),
        p("Kolkata", "Asia/Kolkata", "calcutta"), p("Osaka", "Asia/Tokyo"),
        p("Kyoto", "Asia/Tokyo"), p("Hanoi", "Asia/Bangkok"),
        p("Ho Chi Minh City", "Asia/Ho_Chi_Minh", "saigon"), p("Abu Dhabi", "Asia/Dubai"),
        p("Tel Aviv", "Asia/Jerusalem"), p("Mecca", "Asia/Riyadh"),
        p("Cape Town", "Africa/Johannesburg"), p("Rio de Janeiro", "America/Sao_Paulo", "rio"),
        p("Barcelona", "Europe/Madrid"), p("Munich", "Europe/Berlin"),
        p("Frankfurt", "Europe/Berlin"), p("Hamburg", "Europe/Berlin"),
        p("Milan", "Europe/Rome"), p("Venice", "Europe/Rome"), p("Florence", "Europe/Rome"),
        p("Naples", "Europe/Rome"), p("Geneva", "Europe/Zurich"),
        p("Manchester", "Europe/London"), p("Edinburgh", "Europe/London"),
        p("Liverpool", "Europe/London"), p("St. Petersburg", "Europe/Moscow",
            "saint petersburg"),
        p("Montreal", "America/Toronto"), p("Ottawa", "America/Toronto"),
        p("Calgary", "America/Edmonton"), p("Kyiv", "Europe/Kiev", "kiev"),
        p("Mexico City", "America/Mexico_City"), p("Brisbane", "Australia/Brisbane"),
        p("Bali", "Asia/Makassar"),
        // Baja California Sur: UTC-7 all year
        p("Cabo San Lucas", "America/Mazatlan", "cabo", "los cabos"),
        p("Canberra", "Australia/Sydney"), p("Wellington", "Pacific/Auckland"),
    )
}
