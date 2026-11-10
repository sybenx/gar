package org.stypox.dicio.skills.units

import cc.aaro.gar.router.Utterances
import org.dicio.numbers.ParserFormatter
import org.dicio.numbers.unit.Number

/** What a unit measures; only units of the same dimension convert into each other. */
enum class Dimension { LENGTH, MASS, VOLUME, TEMPERATURE, SPEED, AREA, DATA, TIME }

/**
 * A unit and how it converts: a value `v` of it is `v * factor + offset` of its dimension's base
 * unit (meter, kilogram, liter, kelvin, meter per second, square meter, byte, second). Names are
 * English, like the sentences that use them.
 */
class MeasureUnit(
    val singular: String,
    val plural: String,
    val dimension: Dimension,
    val factor: Double,
    val offset: Double = 0.0,
    /** What people call it besides [singular] and [plural]: "km", "feet", "mph". */
    val aliases: List<String> = listOf(),
) {
    fun toBase(value: Double): Double = value * factor + offset
    fun fromBase(base: Double): Double = (base - offset) / factor
    fun name(value: Double): String = if (value == 1.0) singular else plural
    override fun toString(): String = singular
}

/** "5 miles in kilometers": [amount] of [from], converted to [to]. */
data class Conversion(val amount: Double, val from: MeasureUnit, val to: MeasureUnit) {
    val result: Double get() = to.fromBase(from.toBase(amount))
}

/**
 * Offline unit conversion (English): the units, and how the words of a request become a
 * [Conversion]. Volumes are US customary ("cup", "gallon"); "imperial gallon" and "imperial pint"
 * are the British ones. Data units are decimal ("kilobyte" = 1000 bytes), the binary ones are
 * "kibibyte" and so on. A month is a twelfth of a 365-day year.
 */
object UnitConversion {
    /**
     * The conversion of [amount] (a number and a unit: "seventy fahrenheit", "a liter", "five
     * miles", "one and a half cups") into [target] (a unit: "celsius", "cups"), or null if either
     * isn't one or they measure different things. "Ounce" is a weight or a volume and "degrees" a
     * temperature in the other scale, whichever fits the other unit.
     */
    fun request(amount: String, target: String, parserFormatter: ParserFormatter?): Conversion? {
        val amountWords = words(amount)
        val targetWords = words(target).dropWhile { it in LEADING_WORDS }
            .let { dropTrailing(it) }
        val targets = unitsNamed(targetWords) ?: return null
        val (numberWords, sources) = trailingUnit(amountWords) ?: return null
        val value = parseAmount(numberWords, parserFormatter) ?: return null
        for (to in targets) {
            for (from in sources) {
                if (from.dimension == to.dimension && from !== to) {
                    return Conversion(value, from, to)
                }
            }
        }
        // "seventy degrees in celsius": the other scale
        if (sources.isEmpty() && targets.any { it.dimension == Dimension.TEMPERATURE }) {
            val to = targets.first { it.dimension == Dimension.TEMPERATURE }
            val from = if (to === FAHRENHEIT) CELSIUS else FAHRENHEIT
            return Conversion(value, from, to)
        }
        return null
    }

    /**
     * A whole request in one string, as the model's `calculate` calls have it: "five miles in
     * kilometers", "convert 70 fahrenheit to celsius", "how many cups in a liter", "how many
     * grams is 5 ounces". Null if it isn't a conversion.
     */
    fun parse(text: String, parserFormatter: ParserFormatter?): Conversion? {
        val normalized = words(text).joinToString(" ")
        HOW_MANY.matchEntire(normalized)
            ?.let { request(it.groupValues[2], it.groupValues[1], parserFormatter) }
            ?.let { return it }
        val match = AMOUNT_TO_TARGET.matchEntire(normalized) ?: return null
        return request(match.groupValues[1], match.groupValues[2], parserFormatter)
    }

    /**
     * The unit that ends [words] (longest name first) and the words before it, or null if none
     * does. The unit list is empty for a bare "degrees" (a temperature, scale unknown).
     */
    private fun trailingUnit(words: List<String>): Pair<List<String>, List<MeasureUnit>>? {
        val trimmed = dropTrailing(words)
        for (start in trimmed.indices) {
            val name = trimmed.subList(start, trimmed.size).joinToString(" ")
            val units = byName[name] ?: continue
            return Pair(trimmed.subList(0, start), units)
        }
        val last = trimmed.lastOrNull()
        if (last == "degrees" || last == "degree") {
            return Pair(trimmed.dropLast(1), listOf())
        }
        return null
    }

    /** The units [words] name as a whole, or null. */
    private fun unitsNamed(words: List<String>): List<MeasureUnit>? =
        byName[words.joinToString(" ")]

    private fun dropTrailing(words: List<String>): List<String> =
        words.dropLastWhile { it in TRAILING_WORDS }

    /**
     * The number [words] say: "seventy", "1.5", "a" (1), "half a", "one and a half", "two and a
     * quarter", "minus ten"; nothing at all is 1 ("how many cups in liter").
     */
    fun parseAmount(words: List<String>, parserFormatter: ParserFormatter?): Double? {
        var rest = words
        // "a quarter of a cup", "half of a liter"
        while (rest.isNotEmpty() && rest.last() in setOf("of", "a", "an") && rest.size > 1) {
            rest = rest.dropLast(1)
        }
        if (rest.isEmpty() || rest == listOf("a") || rest == listOf("an") || rest == listOf("one")) {
            return 1.0
        }
        FRACTIONS[rest.joinToString(" ")]?.let { return it }
        for ((suffix, fraction) in AND_FRACTIONS) {
            if (rest.size > suffix.size && rest.takeLast(suffix.size) == suffix) {
                val whole = parseNumber(rest.dropLast(suffix.size), parserFormatter) ?: return null
                return whole + fraction
            }
        }
        return parseNumber(rest, parserFormatter)
    }

    /** One number made of all of [words], with dicio-numbers (or plain digits without it). */
    private fun parseNumber(words: List<String>, parserFormatter: ParserFormatter?): Double? {
        val text = words.joinToString(" ")
        // "5,280", typed
        text.replace(",", "").toDoubleOrNull()?.let { return it }
        val parts = parserFormatter?.extractNumber(text)?.parseMixedWithText() ?: return null
        val numbers = parts.filterIsInstance<Number>()
        if (numbers.size != 1 || parts.any { it is String && it.isNotBlank() }) {
            return null
        }
        val number = numbers[0]
        return if (number.isDecimal) number.decimalValue() else number.integerValue().toDouble()
    }

    private fun words(text: String): List<String> =
        Utterances.words(Utterances.normalize(text.replace("/", " per ")))

    /** Words that may come before a target unit: "convert it to the metric cups". */
    private val LEADING_WORDS = setOf("a", "an", "the", "into", "in", "to", "how", "many", "much")

    /** Words that may follow a unit: "five miles please", "a cup exactly". */
    private val TRAILING_WORDS = setOf("please", "exactly", "approximately", "roughly", "about")

    private val FRACTIONS = mapOf(
        "half" to 0.5, "a half" to 0.5, "half a" to 0.5, "half an" to 0.5, "quarter" to 0.25,
        "a quarter" to 0.25, "a third" to 1.0 / 3, "third" to 1.0 / 3, "three quarters" to 0.75,
        "two thirds" to 2.0 / 3,
    )
    private val AND_FRACTIONS = listOf(
        listOf("and", "a", "half") to 0.5, listOf("and", "a", "quarter") to 0.25,
        listOf("and", "three", "quarters") to 0.75, listOf("and", "a", "third") to 1.0 / 3,
    )

    private val HOW_MANY = Regex(
        "(?:.* )?how (?:many|much) (.+?) (?:are |is )?(?:there )?" +
            "(?:in|is|are|make|makes|equals?) (.+)"
    )
    private val AMOUNT_TO_TARGET =
        Regex("(?:convert |what is |what's |whats |how much is )?(.+?) (?:in|into|to|as) (.+)")

    private fun unit(
        singular: String,
        plural: String,
        dimension: Dimension,
        factor: Double,
        vararg aliases: String,
        offset: Double = 0.0,
    ) = MeasureUnit(singular, plural, dimension, factor, offset, aliases.toList())

    // temperatures, by their kelvin
    private val CELSIUS = unit("degree Celsius", "degrees Celsius", Dimension.TEMPERATURE, 1.0,
        "celsius", "centigrade", "degree celsius", "degrees celsius", "degrees centigrade",
        offset = 273.15)
    private val FAHRENHEIT = unit("degree Fahrenheit", "degrees Fahrenheit",
        Dimension.TEMPERATURE, 5.0 / 9, "fahrenheit", "degree fahrenheit", "degrees fahrenheit",
        offset = 459.67 * 5 / 9)

    private const val YEAR = 365.0 * 24 * 3600

    val UNITS: List<MeasureUnit> = listOf(
        unit("millimeter", "millimeters", Dimension.LENGTH, 0.001, "millimetre", "millimetres",
            "mm"),
        unit("centimeter", "centimeters", Dimension.LENGTH, 0.01, "centimetre", "centimetres", "cm"),
        unit("meter", "meters", Dimension.LENGTH, 1.0, "metre", "metres"),
        unit("kilometer", "kilometers", Dimension.LENGTH, 1000.0, "kilometre", "kilometres", "km",
            "kms", "klicks"),
        unit("inch", "inches", Dimension.LENGTH, 0.0254),
        unit("foot", "feet", Dimension.LENGTH, 0.3048, "ft", "foots"),
        unit("yard", "yards", Dimension.LENGTH, 0.9144, "yd", "yds"),
        unit("mile", "miles", Dimension.LENGTH, 1609.344, "mi"),
        unit("nautical mile", "nautical miles", Dimension.LENGTH, 1852.0),

        unit("milligram", "milligrams", Dimension.MASS, 1e-6, "milligramme", "milligrammes", "mg"),
        unit("gram", "grams", Dimension.MASS, 0.001, "gramme", "grammes"),
        unit("kilogram", "kilograms", Dimension.MASS, 1.0, "kilogramme", "kilogrammes", "kilo",
            "kilos", "kg", "kgs"),
        unit("metric ton", "metric tons", Dimension.MASS, 1000.0, "tonne", "tonnes"),
        unit("ounce", "ounces", Dimension.MASS, 0.028349523125, "oz"),
        unit("pound", "pounds", Dimension.MASS, 0.45359237, "lb", "lbs"),
        unit("stone", "stone", Dimension.MASS, 6.35029318, "stones"),
        unit("ton", "tons", Dimension.MASS, 907.18474, "short ton", "short tons"),

        unit("milliliter", "milliliters", Dimension.VOLUME, 0.001, "millilitre", "millilitres",
            "ml", "mls", "cc", "cubic centimeter", "cubic centimeters"),
        unit("liter", "liters", Dimension.VOLUME, 1.0, "litre", "litres"),
        unit("cubic meter", "cubic meters", Dimension.VOLUME, 1000.0, "cubic metre",
            "cubic metres"),
        unit("teaspoon", "teaspoons", Dimension.VOLUME, 0.00492892159375, "tsp"),
        unit("tablespoon", "tablespoons", Dimension.VOLUME, 0.01478676478125, "tbsp"),
        unit("fluid ounce", "fluid ounces", Dimension.VOLUME, 0.0295735295625, "fl oz",
            "ounce", "ounces", "oz"),
        unit("cup", "cups", Dimension.VOLUME, 0.2365882365),
        unit("pint", "pints", Dimension.VOLUME, 0.473176473),
        unit("quart", "quarts", Dimension.VOLUME, 0.946352946),
        unit("gallon", "gallons", Dimension.VOLUME, 3.785411784),
        unit("imperial pint", "imperial pints", Dimension.VOLUME, 0.56826125),
        unit("imperial gallon", "imperial gallons", Dimension.VOLUME, 4.54609),

        CELSIUS,
        FAHRENHEIT,
        unit("kelvin", "kelvins", Dimension.TEMPERATURE, 1.0, "degree kelvin", "degrees kelvin"),

        unit("meter per second", "meters per second", Dimension.SPEED, 1.0,
            "metres per second", "meter a second", "meters a second"),
        unit("kilometer per hour", "kilometers per hour", Dimension.SPEED, 1 / 3.6,
            "kilometres per hour", "kilometers an hour", "kilometres an hour", "km per h",
            "km per hour", "kmh", "kph"),
        unit("mile per hour", "miles per hour", Dimension.SPEED, 0.44704, "miles an hour", "mph"),
        unit("knot", "knots", Dimension.SPEED, 1852 / 3600.0),
        unit("foot per second", "feet per second", Dimension.SPEED, 0.3048),

        unit("square centimeter", "square centimeters", Dimension.AREA, 1e-4,
            "square centimetre", "square centimetres"),
        unit("square meter", "square meters", Dimension.AREA, 1.0, "square metre",
            "square metres"),
        unit("square kilometer", "square kilometers", Dimension.AREA, 1e6, "square kilometre",
            "square kilometres"),
        unit("square inch", "square inches", Dimension.AREA, 0.00064516),
        unit("square foot", "square feet", Dimension.AREA, 0.09290304, "sq ft"),
        unit("square yard", "square yards", Dimension.AREA, 0.83612736),
        unit("square mile", "square miles", Dimension.AREA, 2589988.110336),
        unit("acre", "acres", Dimension.AREA, 4046.8564224),
        unit("hectare", "hectares", Dimension.AREA, 10000.0),

        unit("bit", "bits", Dimension.DATA, 0.125),
        unit("byte", "bytes", Dimension.DATA, 1.0),
        unit("kilobit", "kilobits", Dimension.DATA, 125.0),
        unit("megabit", "megabits", Dimension.DATA, 125e3),
        unit("gigabit", "gigabits", Dimension.DATA, 125e6),
        unit("kilobyte", "kilobytes", Dimension.DATA, 1e3, "kb"),
        unit("megabyte", "megabytes", Dimension.DATA, 1e6, "mb", "meg", "megs"),
        unit("gigabyte", "gigabytes", Dimension.DATA, 1e9, "gb", "gig", "gigs"),
        unit("terabyte", "terabytes", Dimension.DATA, 1e12, "tb"),
        unit("petabyte", "petabytes", Dimension.DATA, 1e15),
        unit("kibibyte", "kibibytes", Dimension.DATA, 1024.0),
        unit("mebibyte", "mebibytes", Dimension.DATA, 1048576.0),
        unit("gibibyte", "gibibytes", Dimension.DATA, 1073741824.0),
        unit("tebibyte", "tebibytes", Dimension.DATA, 1099511627776.0),

        unit("millisecond", "milliseconds", Dimension.TIME, 0.001, "ms"),
        unit("second", "seconds", Dimension.TIME, 1.0, "sec", "secs"),
        unit("minute", "minutes", Dimension.TIME, 60.0, "min", "mins"),
        unit("hour", "hours", Dimension.TIME, 3600.0, "hr", "hrs"),
        unit("day", "days", Dimension.TIME, 86400.0),
        unit("week", "weeks", Dimension.TIME, 604800.0),
        unit("month", "months", Dimension.TIME, YEAR / 12),
        unit("year", "years", Dimension.TIME, YEAR),
        unit("decade", "decades", Dimension.TIME, YEAR * 10),
        unit("century", "centuries", Dimension.TIME, YEAR * 100),
    )

    /** Every name of every unit (lowercase words) and the units it names ("ounce": two). */
    private val byName: Map<String, List<MeasureUnit>> = buildMap<String, MutableList<MeasureUnit>> {
        for (unit in UNITS) {
            for (name in listOf(unit.singular, unit.plural) + unit.aliases) {
                val key = words(name).joinToString(" ")
                val units = getOrPut(key) { mutableListOf() }
                if (unit !in units) {
                    units.add(unit)
                }
            }
        }
    }
}
