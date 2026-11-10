package org.stypox.dicio.skills.units

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import java.util.Locale

private fun convert(text: String): Conversion? = UnitConversion.parse(text, ENGLISH_PARSER_FORMATTER)

class UnitConversionTest : StringSpec({
    "conversions as people say them" {
        for ((text, expected) in listOf(
            "how many cups in a liter" to 4.22675,
            "convert seventy fahrenheit to celsius" to 21.1111,
            "five miles in kilometers" to 8.04672,
            "what's 5 miles in km" to 8.04672,
            "how many ounces in a pound" to 16.0,
            "how many ounces in a cup" to 8.0,
            "how many tablespoons in a cup" to 16.0,
            "how many teaspoons are in a tablespoon" to 3.0,
            "how many feet are there in a mile" to 5280.0,
            "how many megabytes in a gigabyte" to 1000.0,
            "how many seconds in a day" to 86400.0,
            "how many days in a year" to 365.0,
            "100 km/h in miles per hour" to 62.1371,
            "convert 2 acres to square feet" to 87120.0,
            "minus forty celsius to fahrenheit" to -40.0,
            "seventy degrees in celsius" to 21.1111,
            "twenty degrees in fahrenheit" to 68.0,
            "one and a half cups in milliliters" to 354.882,
            "half a gallon in liters" to 1.89271,
            "how many grams is 5 ounces" to 141.748,
            "how much is a gallon in liters" to 3.78541,
            "300 kelvin to celsius" to 26.85,
        )) {
            withClue(text) {
                convert(text).shouldNotBeNull().result shouldBe (expected plusOrMinus 0.001)
            }
        }
    }

    "the amount and both units" {
        convert("how many cups in a liter") shouldBe Conversion(
            1.0,
            UnitConversion.UNITS.first { it.singular == "liter" },
            UnitConversion.UNITS.first { it.singular == "cup" },
        )
    }

    "anything else is no conversion" {
        for (text in listOf(
            "five miles in kilograms", "remind me to call mom", "how many days until christmas",
            "convert five dollars to euros", "how many cups in a liter of water",
            "what is seven plus nine", "send a text to mom", "five in kilometers",
            "set a timer for five minutes", "how many people live in tokyo", "translate cat to french",
        )) {
            withClue(text) { convert(text).shouldBeNull() }
        }
    }

    "results are rounded as people say them" {
        for ((value, text) in listOf(
            4.22675 to "4.23", 21.1111 to "21.11", 1609.344 to "1,609.3", 0.0295735 to "0.0296",
            16.0 to "16", -40.0 to "-40", 5280.0 to "5,280", 0.5 to "0.5", 0.0 to "0",
        )) {
            withClue(value) { UnitsOutput.formatValue(value, Locale.US) shouldBe text }
        }
        val liter = UnitConversion.UNITS.first { it.singular == "liter" }
        UnitsOutput.quantity(1.0, liter, Locale.US) shouldBe "1 liter"
        UnitsOutput.quantity(1.00001, liter, Locale.US) shouldBe "1 liter"
        UnitsOutput.quantity(4.22675, liter, Locale.US) shouldBe "4.23 liters"
    }
})
