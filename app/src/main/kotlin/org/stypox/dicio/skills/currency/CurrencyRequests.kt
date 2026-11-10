package org.stypox.dicio.skills.currency

import cc.aaro.gar.router.Utterances
import org.dicio.numbers.ParserFormatter
import org.stypox.dicio.skills.units.UnitConversion
import java.util.Currency
import java.util.Locale

/**
 * A currency request: [amount] of [from] in [to] ("20 euros in dollars"), or, with [rate], the
 * exchange rate between them ("exchange rate for pesos": [to] is the phone's own currency).
 * Codes are ISO 4217 ("USD").
 */
data class CurrencyRequest(
    val amount: Double,
    val from: String,
    val to: String,
    val rate: Boolean = false,
)

/** A currency's English name, singular and plural ("US dollar", "US dollars"). */
data class CurrencyName(val singular: String, val plural: String) {
    fun of(amount: Double): String = if (amount == 1.0) singular else plural
}

/**
 * English currency requests, read in Kotlin like the unit converter's (the model's `calculate`
 * calls about money come here too, `CurrencyCalls`):
 * - "how much is 20 euros in dollars", "convert 50 pounds to euros", "100 yen to usd", "$20 in
 *   pesos";
 * - "how many dollars is 20 euros", "how many yen in a dollar", "how many pesos for 100 dollars";
 * - "exchange rate for pesos", "what's the euro to dollar exchange rate", "dollar to yen rate".
 *
 * Every word must belong to one of these forms (leads, a number, currency names, connectors), so
 * "who won the game", "is this real" or "20 pounds in kilograms" (the unit converter's) are no
 * request. Names are the currencies of the European Central Bank's daily reference rates, which
 * is what the converter knows.
 */
object CurrencyRequests {
    /**
     * The request [input] makes, or null. [home] is the currency of "exchange rate for pesos"
     * without a second one (the phone's, US dollars when unknown).
     */
    fun parse(
        input: String,
        parserFormatter: ParserFormatter?,
        home: String = homeCurrency(Locale.getDefault()),
    ): CurrencyRequest? {
        val words = stripTrailing(stripLeads(Utterances.words(Utterances.normalize(withSymbols(input)))))
        if (words.isEmpty()) {
            return null
        }
        val mentions = mentions(words)
        if (mentions.isEmpty() || mentions.size > 2) {
            return null
        }
        return if (words.any { it in RATE_WORDS }) {
            rate(words, mentions, home)
        } else {
            conversion(words, mentions, parserFormatter)
        }
    }

    /** "how much is 20 euros in dollars", "how many dollars is 20 euros". */
    private fun conversion(
        words: List<String>,
        mentions: List<Mention>,
        parserFormatter: ParserFormatter?,
    ): CurrencyRequest? {
        if (mentions.size != 2) {
            return null
        }
        val (first, second) = mentions
        val before = words.subList(0, first.start)
        val between = words.subList(first.end, second.start)
        val after = words.subList(second.end, words.size)
        if (after.isNotEmpty() || first.code == second.code) {
            return null
        }
        // "how many dollars is 20 euros", "how many yen in a dollar"
        if (before == listOf("how", "many") || before == listOf("how", "much")) {
            val connectors = between.takeWhile { it in HOW_MANY_CONNECTORS }
            val amount = UnitConversion.parseAmount(between.drop(connectors.size), parserFormatter)
                ?: return null
            return if (connectors.isEmpty()) null else CurrencyRequest(amount, second.code, first.code)
        }
        // "20 euros in dollars", "how much is 20 euros in dollars", "a dollar worth in yen"
        val to = if (between.firstOrNull() == "worth") between.drop(1) else between
        if (to.size != 1 || to[0] !in TO_WORDS) {
            return null
        }
        val amountWords = before.dropWhile { it in CONVERSION_LEADS }
        if (amountWords.isEmpty() && before.isNotEmpty() && before.last() !in CONVERSION_LEADS) {
            return null
        }
        val amount = UnitConversion.parseAmount(amountWords, parserFormatter) ?: return null
        return CurrencyRequest(amount, first.code, second.code)
    }

    /** "exchange rate for pesos", "euro to dollar exchange rate". */
    private fun rate(words: List<String>, mentions: List<Mention>, home: String): CurrencyRequest? {
        val others = words.filterIndexed { index, _ -> mentions.none { index in it.start until it.end } }
        if (others.any { it !in RATE_FILLERS }) {
            return null
        }
        val from = mentions[0].code
        val to = mentions.getOrNull(1)?.code ?: if (from == home) OTHER_HOME[home] ?: "EUR" else home
        return if (from == to) null else CurrencyRequest(1.0, from, to, rate = true)
    }

    /** A currency named by words [start] until [end] of the input. */
    private data class Mention(val start: Int, val end: Int, val code: String)

    /** The currencies [words] name, longest names first, left to right. */
    private fun mentions(words: List<String>): List<Mention> {
        val mentions = mutableListOf<Mention>()
        var i = 0
        while (i < words.size) {
            val match = (MAX_NAME_WORDS downTo 1).firstNotNullOfOrNull { length ->
                if (i + length > words.size) {
                    null
                } else {
                    NAMES[words.subList(i, i + length).joinToString(" ")]?.let { Pair(length, it) }
                }
            }
            if (match == null) {
                i++
            } else {
                mentions.add(Mention(i, i + match.first, match.second))
                i += match.first
            }
        }
        return mentions
    }

    /** "$20" -> "20 dollars", "€5" -> "5 euros": typed symbols that normalizing would drop. */
    fun withSymbols(input: String): String = SYMBOL.replace(input) { match ->
        "${match.groupValues[2]} ${SYMBOLS.getValue(match.groupValues[1])}"
    }

    private fun stripLeads(words: List<String>): List<String> {
        var rest = words
        while (true) {
            val lead = LEADS.firstOrNull { lead ->
                rest.size > lead.size && rest.subList(0, lead.size) == lead
            } ?: return rest
            rest = rest.drop(lead.size)
        }
    }

    private fun stripTrailing(words: List<String>): List<String> =
        words.dropLastWhile { it in TRAILING }

    /** The currency of [locale]'s country, US dollars when it has none or isn't known. */
    fun homeCurrency(locale: Locale): String = try {
        Currency.getInstance(locale)?.currencyCode ?: "USD"
    } catch (e: IllegalArgumentException) {
        "USD"
    }

    /** The English name of [code] ("US dollars"), or the code itself. */
    fun nameOf(code: String): CurrencyName = DISPLAY[code] ?: CurrencyName(code, code)

    private val SYMBOL = Regex("([$€£¥₹₩₪])\\s?(\\d[\\d,]*(?:\\.\\d+)?)")
    private val SYMBOLS = mapOf(
        "$" to "dollars", "€" to "euros", "£" to "pounds", "¥" to "yen", "₹" to "rupees",
        "₩" to "won", "₪" to "shekels",
    )

    private val LEADS = listOf(
        listOf("hey"), listOf("ok"), listOf("okay"), listOf("so"), listOf("please"),
        listOf("can", "you"), listOf("could", "you"), listOf("would", "you"),
        listOf("tell", "me"), listOf("do", "you", "know"), listOf("i", "want", "to", "know"),
    )
    private val TRAILING = setOf("please", "today", "now", "right", "currently", "exactly")

    /** Before the amount of "how much is 20 euros in dollars". */
    private val CONVERSION_LEADS = setOf(
        "how", "much", "is", "are", "what", "what's", "whats", "s", "convert", "change",
        "calculate", "would", "be", "worth",
    )
    private val TO_WORDS = setOf("in", "into", "to", "as")

    /** Between the currencies of "how many dollars is 20 euros", "how many yen in a dollar". */
    private val HOW_MANY_CONNECTORS = setOf(
        "is", "are", "in", "for", "to", "make", "makes", "equals", "equal", "would", "will", "i",
        "you", "do", "does", "can", "get", "there", "there's",
    )

    private val RATE_WORDS = setOf("rate", "rates")
    private val RATE_FILLERS = setOf(
        "what", "what's", "whats", "is", "are", "s", "the", "exchange", "rate", "rates", "for",
        "of", "from", "to", "and", "against", "in", "into", "between", "current", "today's",
        "todays", "how", "much", "a", "one", "conversion", "currency", "at",
    )

    /** "dollar exchange rate" for someone whose currency is the dollar: against the euro. */
    private val OTHER_HOME = mapOf("EUR" to "USD")

    private val DISPLAY = mapOf(
        "USD" to CurrencyName("US dollar", "US dollars"),
        "EUR" to CurrencyName("euro", "euros"),
        "GBP" to CurrencyName("British pound", "British pounds"),
        "JPY" to CurrencyName("Japanese yen", "Japanese yen"),
        "CNY" to CurrencyName("Chinese yuan", "Chinese yuan"),
        "INR" to CurrencyName("Indian rupee", "Indian rupees"),
        "IDR" to CurrencyName("Indonesian rupiah", "Indonesian rupiah"),
        "KRW" to CurrencyName("South Korean won", "South Korean won"),
        "MXN" to CurrencyName("Mexican peso", "Mexican pesos"),
        "PHP" to CurrencyName("Philippine peso", "Philippine pesos"),
        "CAD" to CurrencyName("Canadian dollar", "Canadian dollars"),
        "AUD" to CurrencyName("Australian dollar", "Australian dollars"),
        "NZD" to CurrencyName("New Zealand dollar", "New Zealand dollars"),
        "HKD" to CurrencyName("Hong Kong dollar", "Hong Kong dollars"),
        "SGD" to CurrencyName("Singapore dollar", "Singapore dollars"),
        "CHF" to CurrencyName("Swiss franc", "Swiss francs"),
        "SEK" to CurrencyName("Swedish krona", "Swedish kronor"),
        "NOK" to CurrencyName("Norwegian krone", "Norwegian kroner"),
        "DKK" to CurrencyName("Danish krone", "Danish kroner"),
        "ISK" to CurrencyName("Icelandic krona", "Icelandic kronur"),
        "PLN" to CurrencyName("Polish zloty", "Polish zlotys"),
        "HUF" to CurrencyName("Hungarian forint", "Hungarian forints"),
        "CZK" to CurrencyName("Czech koruna", "Czech korunas"),
        "RON" to CurrencyName("Romanian leu", "Romanian lei"),
        "BGN" to CurrencyName("Bulgarian lev", "Bulgarian leva"),
        "TRY" to CurrencyName("Turkish lira", "Turkish liras"),
        "BRL" to CurrencyName("Brazilian real", "Brazilian reais"),
        "ZAR" to CurrencyName("South African rand", "South African rand"),
        "ILS" to CurrencyName("Israeli shekel", "Israeli shekels"),
        "MYR" to CurrencyName("Malaysian ringgit", "Malaysian ringgit"),
        "THB" to CurrencyName("Thai baht", "Thai baht"),
    )

    /** What people call each currency, lowercase words; the codes themselves are added below. */
    private val SPOKEN = mapOf(
        "USD" to listOf(
            "dollar", "dollars", "buck", "bucks", "us dollar", "us dollars", "u s dollars",
            "american dollar", "american dollars", "united states dollar", "united states dollars",
        ),
        "EUR" to listOf("euro", "euros"),
        "GBP" to listOf(
            "pound", "pounds", "quid", "sterling", "pound sterling", "pounds sterling",
            "british pound", "british pounds", "uk pound", "uk pounds",
        ),
        "JPY" to listOf("yen", "japanese yen"),
        "CNY" to listOf("yuan", "chinese yuan", "renminbi", "rmb"),
        "INR" to listOf("rupee", "rupees", "indian rupee", "indian rupees"),
        "IDR" to listOf("rupiah", "indonesian rupiah"),
        "KRW" to listOf("won", "korean won", "south korean won"),
        "MXN" to listOf("peso", "pesos", "mexican peso", "mexican pesos"),
        "PHP" to listOf("philippine peso", "philippine pesos", "filipino peso", "filipino pesos"),
        "CAD" to listOf("canadian dollar", "canadian dollars"),
        "AUD" to listOf("australian dollar", "australian dollars", "aussie dollar", "aussie dollars"),
        "NZD" to listOf("new zealand dollar", "new zealand dollars", "kiwi dollar", "kiwi dollars"),
        "HKD" to listOf("hong kong dollar", "hong kong dollars"),
        "SGD" to listOf("singapore dollar", "singapore dollars", "singaporean dollars"),
        "CHF" to listOf("franc", "francs", "swiss franc", "swiss francs"),
        "SEK" to listOf("krona", "kronor", "swedish krona", "swedish kronor", "swedish crowns"),
        // a bare krone is the Norwegian one; the Danish one is named
        "NOK" to listOf("krone", "kroner", "norwegian krone", "norwegian kroner", "norwegian crowns"),
        "DKK" to listOf("danish krone", "danish kroner", "danish crowns"),
        "ISK" to listOf("icelandic krona", "icelandic kronur", "icelandic kronas"),
        "PLN" to listOf("zloty", "zlotys", "zlotych", "polish zloty", "polish zlotys"),
        "HUF" to listOf("forint", "forints", "hungarian forint", "hungarian forints"),
        "CZK" to listOf("koruna", "korunas", "czech koruna", "czech korunas", "czech crowns"),
        "RON" to listOf("leu", "lei", "romanian leu", "romanian lei"),
        "BGN" to listOf("lev", "leva", "bulgarian lev", "bulgarian leva"),
        "TRY" to listOf("lira", "liras", "turkish lira", "turkish liras"),
        "BRL" to listOf("real", "reais", "reals", "brazilian real", "brazilian reais"),
        "ZAR" to listOf("rand", "rands", "south african rand", "south african rands"),
        "ILS" to listOf("shekel", "shekels", "israeli shekel", "israeli shekels", "new shekels"),
        "MYR" to listOf("ringgit", "ringgits", "malaysian ringgit"),
        "THB" to listOf("baht", "thai baht"),
    )

    /** Codes that are English words: "try" is no Turkish lira. */
    private val CODE_WORDS = setOf("TRY")

    /** Every name and code (lowercase), and its currency. */
    private val NAMES: Map<String, String> = buildMap {
        for ((code, names) in SPOKEN) {
            if (code !in CODE_WORDS) {
                put(code.lowercase(), code)
            }
            for (name in names) {
                put(name, code)
            }
        }
    }

    private val MAX_NAME_WORDS = NAMES.keys.maxOf { it.split(' ').size }
}
