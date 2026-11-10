package org.stypox.dicio.skills.knowledge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.stypox.dicio.gar.online.GarHttp
import java.net.URLEncoder

/**
 * A page summary of Wikipedia's REST API (`/api/rest_v1/page/summary/{title}`).
 *
 * @param disambiguation whether the page lists several meanings ("Mercury")
 * @param description the short description ("American singer-songwriter (born 1989)")
 * @param extract the plain text of the page's first paragraph
 * @param url the page, for the card's link
 */
data class WikiSummary(
    val title: String,
    val description: String?,
    val extract: String,
    val url: String,
    val disambiguation: Boolean,
)

/**
 * English Wikipedia, as the knowledge skill uses it: what is sent is only the subject's words, as
 * a title and, when no page has that title, as a search.
 */
class Wikipedia(private val http: GarHttp = GarHttp.default) {

    /**
     * The summary of the page about [subject] ("mount everest"): the page with that title (its
     * redirects followed), or else the first search result; null if there is none.
     * @throws java.io.IOException without a connection
     */
    suspend fun find(subject: String): WikiSummary? {
        summary(titleOf(subject))?.takeIf { !it.disambiguation }?.let { return it }
        val searched = search(subject)
        if (searched != null) {
            summary(searched)?.let { return it }
        }
        // a disambiguation page when nothing else matched ("mercury" is one)
        return summary(titleOf(subject))
    }

    /** The summary of the page [title] (underscores for spaces), null if there is none. */
    suspend fun summary(title: String): WikiSummary? =
        http.get("$SUMMARY_URL${encode(title)}?redirect=true")?.let { parseSummary(it) }

    /** The key of the first page that a full text search for [query] finds, or null. */
    suspend fun search(query: String): String? =
        http.get("$SEARCH_URL${encode(query)}&limit=1")?.let { parseSearch(it) }

    companion object {
        const val SUMMARY_URL = "https://en.wikipedia.org/api/rest_v1/page/summary/"
        const val SEARCH_URL = "https://en.wikipedia.org/w/rest.php/v1/search/page?q="

        private val json = Json { ignoreUnknownKeys = true }

        /** "mount everest" -> "Mount_everest": titles start with a capital, the rest redirect. */
        fun titleOf(subject: String): String =
            subject.trim().replace(' ', '_').replaceFirstChar { it.uppercaseChar() }

        /** Percent-encoding for a path segment or a query value ("%20", not "+"). */
        fun encode(text: String): String =
            URLEncoder.encode(text, Charsets.UTF_8.name()).replace("+", "%20")

        /** The summary in [body], or null for a page without text. */
        fun parseSummary(body: String): WikiSummary? {
            val page = json.parseToJsonElement(body).jsonObject
            val extract = page.string("extract")?.trim().orEmpty()
            val title = page.string("title") ?: return null
            if (extract.isEmpty()) {
                return null
            }
            val url = page["content_urls"]?.jsonObject?.get("mobile")?.jsonObject?.string("page")
                ?: "https://en.m.wikipedia.org/wiki/${encode(title.replace(' ', '_'))}"
            return WikiSummary(
                title = title,
                description = page.string("description"),
                extract = extract,
                url = url,
                disambiguation = page.string("type") == "disambiguation",
            )
        }

        /** The first page's key in a search result, or null. */
        fun parseSearch(body: String): String? =
            json.parseToJsonElement(body).jsonObject["pages"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.string("key")

        private fun JsonObject.string(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }
}

/** What GAR says from a [WikiSummary] for a [KnowledgeQuestion], English. */
object WikiAnswers {
    /**
     * The sentences to speak: for a summary the first one, and the second too when the first is
     * short; for a measurement or a date the first sentence that has one (the summary's start when
     * none has). Null for [KnowledgeKind.BORN], [KnowledgeKind.DIED] and [KnowledgeKind.AGE]
     * when the description has the years ([lifeYears] answers those).
     */
    fun spokenText(summary: WikiSummary, question: KnowledgeQuestion): String {
        val sentences = sentences(summary.extract)
        val found = when (question.kind) {
            KnowledgeKind.MEASURE -> sentences.firstOrNull { MEASUREMENT.containsMatchIn(it) }
            KnowledgeKind.WHEN -> sentences.firstOrNull { YEAR.containsMatchIn(it) }
            else -> null
        }
        val first = sentences.firstOrNull() ?: summary.extract
        val text = when {
            found == null -> {
                val second = sentences.getOrNull(1)
                if (second != null && first.length < SHORT_SENTENCE &&
                    first.length + second.length < MAX_SPOKEN
                ) "$first $second" else first
            }
            found == first -> first
            // "Its height was ... 8,848.86 m." says "its": the first sentence names the subject
            first.length + found.length < MAX_SPOKEN * 2 -> "$first $found"
            else -> found
        }
        return spokenUnits(text)
    }

    /** Birth and death years from a description like "(born 1989)" or "(1879-1955)". */
    fun lifeYears(description: String?): Pair<Int, Int?>? {
        description ?: return null
        BORN.find(description)?.let { return Pair(it.groupValues[1].toInt(), null) }
        LIFESPAN.find(description)?.let {
            return Pair(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        return null
    }

    /**
     * [text] split into sentences at ". " (also "! " and "? ") before a capital letter or a digit,
     * but not after an initial ("J. R. R.") or a common abbreviation ("St.", "U.S.", "Dr.").
     */
    fun sentences(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length - 2) {
            val c = text[i]
            if ((c == '.' || c == '!' || c == '?') && text[i + 1] == ' ' &&
                (text[i + 2].isUpperCase() || text[i + 2].isDigit()) &&
                !(c == '.' && endsWithAbbreviation(text, start, i))
            ) {
                result.add(text.substring(start, i + 1).trim())
                start = i + 2
            }
            i++
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { result.add(it) }
        return result
    }

    private fun endsWithAbbreviation(text: String, start: Int, period: Int): Boolean {
        val word = text.substring(start, period).substringAfterLast(' ')
        // an initial is a capital ("J. R. R. Tolkien"); "330 m." ends a sentence
        return (word.length == 1 && word[0].isUpperCase()) || word.contains('.') ||
            word.lowercase() in ABBREVIATIONS
    }

    /** "8,848.86 m" -> "8,848.86 meters", so that the speech says the unit. */
    fun spokenUnits(text: String): String = UNIT.replace(text) { match ->
        val unit = SPOKEN_UNITS.getValue(match.groupValues[2])
        "${match.groupValues[1]} $unit"
    }

    private const val SHORT_SENTENCE = 90
    private const val MAX_SPOKEN = 220

    private val ABBREVIATIONS = setOf(
        "st", "dr", "mr", "mrs", "ms", "jr", "sr", "vs", "etc", "inc", "ltd", "co", "mt", "ft",
        "no", "approx", "ca", "lit", "gen", "col", "lt", "sgt", "gov", "sen", "rep", "prof",
    )

    private val SPOKEN_UNITS = mapOf(
        "m" to "meters", "km" to "kilometers", "cm" to "centimeters", "mm" to "millimeters",
        "ft" to "feet", "mi" to "miles", "in" to "inches", "kg" to "kilograms", "lb" to "pounds",
        "t" to "tonnes", "km2" to "square kilometers", "sq mi" to "square miles",
        "mph" to "miles per hour", "km/h" to "kilometers per hour",
    )
    // Wikipedia puts a no-break space between a number and its unit
    private val UNIT = Regex(
        "(\\d[\\d,.]*)[\\s\u00A0]?(km2|sq mi|km/h|mph|km|cm|mm|kg|lb|ft|mi|m|t)(?![\\p{L}\\d])"
    )

    /** A number with a unit of size, distance, weight or speed: what "how tall" asks for. */
    private val MEASUREMENT = Regex(
        "\\d[\\d,.]*[\\s\u00A0]?(m|km|cm|ft|mi|kg|lb|t|mph|km/h|meters?|metres?|kilometers?|" +
            "kilometres?|feet|foot|miles?|inches|kilograms?|pounds?|tonnes?|tons?|square)\\b"
    )
    private val YEAR = Regex("\\b(1\\d{3}|20\\d{2})\\b")
    private val BORN = Regex("\\(born (\\d{3,4})\\)")
    // Wikipedia writes the span with an en dash (U+2013)
    private val LIFESPAN = Regex("\\((?:c\\. )?(\\d{3,4})\\s?[\u2013-]\\s?(\\d{3,4})\\)")
}
