package cc.aaro.gar.router

import org.dicio.numbers.ParserFormatter
import java.time.LocalDate
import java.util.Locale

/**
 * A recognizer's text twice: [spoken], the form GAR matches and routes (lowercase words, numbers as
 * words, normalized by [Utterances.normalize]), and [written], the recognizer's own text without
 * its noise markers ("[BLANK_AUDIO]", "(music)") but with its capitals, digits and punctuation.
 */
data class SpokenText(val spoken: String, val written: String)

/**
 * Turns the written text of a recognizer that punctuates and writes digits and symbols (Whisper:
 * "Set a timer for 5 minutes.", "7:30 a.m.", "15%", "$20", "555-1234") into the spoken form Vosk
 * emits and the rest of GAR assumes: Dicio's sentences, the resolvers and guards, and the eval cases
 * ("set a timer for five minutes", "seven thirty am", "fifteen percent", "twenty dollars", "five
 * five five one two three four"). English only. Typed input and buttons don't go through it.
 *
 * Numbers are words from dicio-numbers, so its parser and [cc.aaro.gar.router.resolve.EnglishTime]
 * read them back; phone-like numbers (a leading `+` or `0`, an area code in parentheses, dashed
 * digit groups, five or more digits, a number after "call" or "dial", 911) come out digit by digit,
 * as people say them and `spokenPhoneNumber` reads them. The result has no digits left and
 * normalizes to itself.
 */
object SpokenForm {

    fun of(written: String): SpokenText {
        val clean = cleanup(written)
        return SpokenText(spoken(clean), clean)
    }

    // ---------- the written text ----------

    /** Drops noise markers, a dialogue dash and ellipses; unifies apostrophes. */
    private fun cleanup(text: String): String {
        var s = text
        s = MARKERS.replace(s, " ")
        s = PARENTHESES.replace(s, " ")
        s = s.replace("♪", " ").replace("♫", " ")
        s = ELLIPSIS.replace(s, " ")
        s = s.replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'')
        s = SPACES.replace(s, " ").trim()
        s = LEADING_DASH.replace(s, "")
        return s
    }

    // ---------- the spoken form ----------

    private fun spoken(clean: String): String {
        var s = clean
        s = s.replace(TWENTY_FOUR_SEVEN, " twenty four seven ")
        s = abbreviations(s)
        s = webAddresses(s)
        s = times(s)
        s = currency(s)
        s = symbols(s)
        s = phoneNumbers(s)
        s = ORDINAL.replace(s) { m -> " " + ordinal(m.groupValues[1]) + " " }
        s = DECADE.replace(s) { m -> " " + plural(cardinal(m.groupValues[1])) + " " }
        s = HALF_UNIT.replace(s) { m -> " " + cardinal(m.groupValues[1]) + " and a half " }
        s = numbers(s)
        s = DASHES.replace(s, " ")
        return Utterances.normalize(s)
    }

    private fun abbreviations(text: String): String {
        var s = MERIDIEM_DOTS.replace(text) { m -> m.groupValues[1].lowercase(Locale.ROOT) + "m" }
        s = OKAY.replace(s, " okay ")
        s = TITLE.replace(s) { m -> " " + TITLES.getValue(m.groupValues[1].lowercase(Locale.ROOT)) + " " }
        return s
    }

    /**
     * "reddit.com" -> "reddit dot com", "news.ycombinator.com/item" -> "news dot ycombinator dot
     * com slash item", "my-site.org" -> "my dash site dot org": a web address as Vosk hears it,
     * which GAR's website parser reads. Only when the last label is a top level domain people say
     * ([TOP_LEVEL_DOMAINS]) or two letters and every label has a letter, so "e.g.", "U.S.", "3.5"
     * and "3.5mm" stay; not in an email address. Digits in a label become words later ("web3.io":
     * "web three dot io").
     */
    private fun webAddresses(text: String): String = WEB_ADDRESS.replace(text) { m ->
        val labels = m.groupValues[1].split('.')
        val tld = labels.last().lowercase(Locale.ROOT)
        if (tld !in TOP_LEVEL_DOMAINS && tld.length != 2 || labels.any { label -> label.none { it.isLetter() } }) {
            return@replace m.value
        }
        val path = m.groupValues[2].split('/').filter { it.isNotEmpty() }
        " " + labels.joinToString(" dot ") { it.replace("-", " dash ") } +
            path.joinToString("") { " slash " + it.replace("-", " dash ") } + " "
    }

    /**
     * "7:30" -> "seven thirty", "7:05 pm", "730 pm", "7.30 pm", "7 pm", "19:00" -> "nineteen hundred",
     * and "at 7.30" (Whisper writes "Wake me up at 7.30.", and "set an alarm for 8.15" with names in
     * its prompt; a bare "for 1.30" stays a number, as in "a timer for 1.30 hours").
     */
    private fun times(text: String): String {
        var s = COLON_TIME.replace(text) { m ->
            clockTime(m.groupValues[1].toInt(), m.groupValues[2].toInt(), meridiem(m.groupValues[3]))
                ?: m.value
        }
        s = MERIDIEM_TIME.replace(s) { m ->
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            clockTime(m.groupValues[1].toInt(), minute, meridiem(m.groupValues[3])) ?: m.value
        }
        s = DOT_TIME.replace(s) { m ->
            clockTime(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null) ?: m.value
        }
        return s
    }

    private fun meridiem(text: String): String? = text.lowercase(Locale.ROOT).ifEmpty { null }

    /** The words of a clock time, or null if [hour] and [minute] are none. */
    private fun clockTime(hour: Int, minute: Int, meridiem: String?): String? {
        if (minute > 59 || hour > 23 || meridiem != null && hour !in 1..12) return null
        val minutes = when (minute) {
            0 -> ""
            in 1..9 -> " oh " + cardinal(minute.toLong())
            else -> " " + cardinal(minute.toLong())
        }
        val words = when {
            meridiem != null -> cardinal(hour.toLong()) + minutes + " " + meridiem
            hour == 0 && minute == 0 -> "midnight"
            hour == 0 -> "twelve$minutes am"
            minute == 0 && hour <= 12 -> cardinal(hour.toLong()) + " o'clock"
            minute == 0 -> cardinal(hour.toLong()) + " hundred"
            else -> cardinal(hour.toLong()) + minutes
        }
        return " $words "
    }

    /** "$20" -> "twenty dollars", "$4.50" -> "four dollars and fifty cents", "$2 million". */
    private fun currency(text: String): String = CURRENCY.replace(text) { m ->
        val (one, many, cent, cents) = CURRENCIES.getValue(m.groupValues[1])
        val whole = m.groupValues[2].replace(",", "").trimStart('0').ifEmpty { "0" }
        val decimals = m.groupValues[3]
        val scale = m.groupValues[4]
        if (scale.isNotEmpty()) {
            val scaleWord = SCALES[scale.lowercase(Locale.ROOT)] ?: scale.lowercase(Locale.ROOT)
            val amount = if (decimals.isEmpty()) cardinal(whole) else decimal(whole, decimals)
            return@replace " $amount $scaleWord $many "
        }
        val hundredths = if (decimals.isEmpty()) 0 else decimals.padEnd(2, '0').toInt()
        val parts = mutableListOf<String>()
        if (whole != "0" || hundredths == 0) {
            parts += cardinal(whole) + " " + if (whole == "1") one else many
        }
        if (hundredths != 0) {
            parts += cardinal(hundredths.toLong()) + " " + if (hundredths == 1) cent else cents
        }
        " " + parts.joinToString(" and ") + " "
    }

    /** Percent, degrees, arithmetic between numbers, a minus sign, "&" and "#5". */
    private fun symbols(text: String): String {
        var s = PERCENT.replace(text, " percent ")
        s = DEGREES.replace(s) { m ->
            when (m.groupValues[1].uppercase(Locale.ROOT)) {
                "F" -> " degrees fahrenheit "
                "C" -> " degrees celsius "
                else -> " degrees "
            }
        }
        s = PLUS.replace(s, " plus ")
        s = MINUS.replace(s, " minus ")
        s = TIMES.replace(s, " times ")
        s = DIVIDED.replace(s, " divided by ")
        s = POWER.replace(s, " to the power of ")
        s = s.replace("=", " equals ")
        s = NEGATIVE.replace(s, " minus ")
        s = s.replace("&", " and ")
        s = NUMBER_SIGN.replace(s, " number ")
        return s
    }

    private fun phoneNumbers(text: String): String {
        var s = INTERNATIONAL.replace(text) { m -> " plus " + digits(m.value) + " " }
        s = AREA_CODE.replace(s) { m -> " " + digits(m.value) + " " }
        s = DASHED_DIGITS.replace(s) { m ->
            if (m.value.count { it.isDigit() } >= MIN_DASHED_DIGITS) " " + digits(m.value) + " " else m.value
        }
        s = DIALED.replace(s) { m -> " " + digits(m.value) + " " }
        for (pattern in DIGIT_RUNS) {
            s = pattern.replace(s) { m -> " " + digits(m.value) + " " }
        }
        return s
    }

    /** Integers, decimals and years; what the rules before left. */
    private fun numbers(text: String): String = NUMBER.replace(text) { m ->
        val whole = m.groupValues[1]
        val decimals = m.groupValues[2]
        val words = when {
            decimals.isNotEmpty() -> decimal(whole, decimals)
            whole.length == 4 && whole.toInt() in YEARS && !beforeUnit(text, m.range.last + 1) ->
                pf.niceYear(LocalDate.of(whole.toInt(), 1, 1)).get()
            else -> cardinal(whole)
        }
        " $words "
    }

    private fun beforeUnit(text: String, end: Int): Boolean =
        NEXT_WORD.find(text.substring(end))?.groupValues?.get(1)?.lowercase(Locale.ROOT) in UNITS

    // ---------- helpers ----------

    private val pf by lazy { ParserFormatter(Locale.ENGLISH) }

    private fun cardinal(n: Long): String = pf.pronounceNumber(n.toDouble()).get()

    /** [number], digits maybe with thousands separators, as words; digit by digit if very long. */
    private fun cardinal(number: String): String {
        val plain = number.replace(",", "")
        return if (plain.length > MAX_CARDINAL_DIGITS) digits(plain) else cardinal(plain.toLong())
    }

    private fun ordinal(number: String): String {
        val plain = number.replace(",", "")
        return if (plain.length > MAX_CARDINAL_DIGITS) {
            digits(plain)
        } else {
            pf.pronounceNumber(plain.toDouble()).ordinal(true).get()
        }
    }

    private fun decimal(whole: String, decimals: String): String =
        cardinal(whole) + " point " + digits(decimals)

    /** Each digit of [text] as a word; other characters are dropped. */
    private fun digits(text: String): String =
        text.filter { it in '0'..'9' }.map { DIGIT_WORDS[it - '0'] }.joinToString(" ")

    /** "ninety" -> "nineties", "nineteen ninety" -> "nineteen nineties", "two thousand" -> "two thousands". */
    private fun plural(words: String): String =
        if (words.endsWith("y")) words.dropLast(1) + "ies" else words + "s"

    private val DIGIT_WORDS =
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")

    private data class Currency(val one: String, val many: String, val cent: String, val cents: String)

    private val CURRENCIES = mapOf(
        "$" to Currency("dollar", "dollars", "cent", "cents"),
        "€" to Currency("euro", "euros", "cent", "cents"),
        "£" to Currency("pound", "pounds", "penny", "pence"),
    )

    private val SCALES = mapOf("k" to "thousand", "m" to "million", "b" to "billion", "bn" to "billion")

    private val TITLES = mapOf("mr" to "mister", "mrs" to "missus", "dr" to "doctor")

    /** 1100 to 2099, said as years unless a unit follows ("2026" against "2026 steps"). */
    private val YEARS = 1100..2099

    private val UNITS = setOf(
        "second", "seconds", "sec", "secs", "minute", "minutes", "min", "mins", "hour", "hours", "hr",
        "hrs", "day", "days", "week", "weeks", "month", "months", "year", "years", "times", "steps",
        "calories", "kcal", "people", "points", "words", "pages", "items", "meters", "metres", "miles",
        "feet", "foot", "yards", "kilometers", "kilometres", "km", "kilograms", "kg", "grams", "g",
        "pounds", "lbs", "ounces", "oz", "liters", "litres", "ml", "dollars", "euros", "bucks",
        "percent", "degrees",
    )

    /** Beyond this, dicio-numbers' doubles lose digits: say them one by one. */
    private const val MAX_CARDINAL_DIGITS = 15

    /** "555-1234" yes, "9-11" or "2-3" no. */
    private const val MIN_DASHED_DIGITS = 5

    // cleanup
    /** "[BLANK_AUDIO]", "*sighs*" (not the "*" of "5 * 3"), "♪ la la ♪". */
    private val MARKERS = Regex("""\[[^\]]*]|\*[A-Za-z][^*\d]*\*|♪[^♪]*♪|♫[^♫]*♫""")

    /** "(music)", but not "(435) 555-1234" or "(5 + 3) x 2": nothing with a digit. */
    private val PARENTHESES = Regex("""\([^)\d]*\)""")
    private val ELLIPSIS = Regex("""…|\.{2,}""")
    private val SPACES = Regex("""\s+""")

    /** A dialogue dash ("- Hello."), not a minus sign ("-5 degrees"). */
    private val LEADING_DASH = Regex("""^-+(?!\d)\s*""")

    // abbreviations
    private const val TWENTY_FOUR_SEVEN = "24/7"
    private val MERIDIEM_DOTS = Regex("""(?i)\b([ap])\.\s?m\.?(?![a-z])""")
    private val OKAY = Regex("""(?i)\bo\.?k\b\.?""")
    /** "Mr." and "Mr", but "mr" and "dr" only with their period. */
    private val TITLE = Regex("""\b(Mr|Mrs|Dr|MR|MRS|DR|mr(?=\.)|mrs(?=\.)|dr(?=\.))\b\.?""")

    // web addresses
    /**
     * Top level domains people say, besides the two-letter country ones; the website skill's
     * parser takes the same ones.
     */
    val TOP_LEVEL_DOMAINS = setOf(
        "com", "org", "net", "edu", "gov", "mil", "int", "info", "biz", "io", "dev", "app",
        "ai", "me", "tv", "co", "xyz", "online", "site", "tech", "store", "shop", "blog",
        "news", "wiki", "page", "cloud", "social", "live", "life", "world", "earth", "lol",
        "ninja", "cc", "gg", "fm", "ly", "to", "is", "eu", "asia", "museum", "travel", "pro",
        "name", "mobi", "zone", "link", "club", "space", "website", "email", "today", "art",
    )

    /** Labels joined by dots, the last all letters, then maybe a path; not after "@" or a dot. */
    private val WEB_ADDRESS = Regex(
        """(?i)(?<![\w@.-])((?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,})((?:/[\w-]+)*/?)(?![\w@-]|\.\w)"""
    )

    // times
    private const val MERIDIEM = """(?:\s*([AaPp][Mm])(?![A-Za-z]))"""
    private val COLON_TIME = Regex("""(?<![\d:.,])(\d{1,2}):(\d{2})(?![\d:])$MERIDIEM?""")

    /** "7 pm", "7pm", "7.30 pm", "730pm": only before am/pm. */
    private val MERIDIEM_TIME = Regex("""(?<![\d:.,])(\d{1,2})(?:\.?(\d{2}))?$MERIDIEM""")

    /** "at 7.30", "until 19.45", "alarm for 8.15": a dotted time only after a word that takes a time, else a decimal. */
    private val DOT_TIME = Regex(
        """(?<=\b(?:[Aa]t|[Bb]y|[Uu]ntil|[Tt]ill?|[Bb]efore|[Aa]fter|[Ff]rom|[Aa]round|[Aa]larm for) )(\d{1,2})\.(\d{2})(?!\d|\.\d)"""
    )

    // currency, symbols
    private val CURRENCY = Regex(
        """([$€£])\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?(?!\d|\.\d)""" +
            """(?:\s?(thousand|million|billion|trillion|[kKmMbB]n?)\b)?""",
    )
    private val PERCENT = Regex("""(?<=\d)\s?%""")
    private val DEGREES = Regex("""(?<=\d)\s?[°º]\s?([FfCc](?![A-Za-z]))?""")
    private val PLUS = Regex("""(?<=\d)\s*\+\s*(?=\d)""")
    private val MINUS = Regex("""(?<=\d)\s+[-–−]\s+(?=\d)|(?<=\d)\s*−\s*(?=\d)""")
    private val TIMES = Regex("""(?<=\d)\s*[x×*X]\s*(?=\d)""")
    private val DIVIDED = Regex("""(?<=\d)\s*[/÷]\s*(?=\d)""")
    private val POWER = Regex("""(?<=\d)\s*\^\s*(?=\d)""")
    private val NEGATIVE = Regex("""(?<![\w.,)])[-–−](?=\d)""")
    private val NUMBER_SIGN = Regex("""#(?=\s?\d)""")

    // phone numbers
    private val INTERNATIONAL = Regex("""(?<![\w+])\+\d(?:[\d().]|[\s-](?=[\d(]))*\d""")
    /** "(435) 555-1234", "(435) 555 1234", "555.123.4567". */
    private val AREA_CODE = Regex(
        """\(\d{3}\)\s?\d{3}[-.\s]?\d{4}(?!\d)|(?<![\d.])\d{3}\.\d{3}\.\d{4}(?![\d.])""",
    )
    private val DASHED_DIGITS = Regex("""(?<![\d.,-])\d+(?:-\d+)+(?![\d-])""")

    /** Three digits and more right after "call", "dial", "phone" or "ring" ("call 112"), and 911. */
    private val DIALED = Regex(
        """(?i)(?<=\b(?:call|dial|phone|ring)\s)\d{3,}(?!\d|[.,:]\d)|(?<![\d.,])911(?!\d|[.,:]\d)""",
    )

    /**
     * A leading zero ("007", "0161"), or five digits and more without separators, unless they end
     * in "000" ("12345" and "90210", but "10000" is ten thousand).
     */
    private val DIGIT_RUNS = listOf(
        Regex("""(?<![\d.,])0\d+(?!\d|[.,]\d)"""),
        Regex("""(?<![\d.,])(?!\d+000(?!\d))\d{5,}(?!\d|[.,]\d)"""),
    )

    // numbers
    private val ORDINAL = Regex("""(?<![\d.,])(\d{1,3}(?:,\d{3})+|\d+)(?:st|nd|rd|th|ST|ND|RD|TH)\b""")
    private val DECADE = Regex("""(?<![\d.,])'?((?:1[1-9]|20)?\d0)'?s\b""")
    private val HALF_UNIT = Regex(
        """(?<![\d.,])([1-9]\d*)\.5""" +
            """(?=\s+(?:hours?|hrs?|minutes?|mins?|seconds?|secs?|days?|weeks?|months?|years?)\b)""",
    )
    private val NUMBER = Regex("""(?<!\d)(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(?!\d)""")
    private val NEXT_WORD = Regex("""^\s*([A-Za-z]+)""")
    /** Hyphens, en and em dashes. */
    private val DASHES = Regex("""[-\u2013\u2014]""")
}
