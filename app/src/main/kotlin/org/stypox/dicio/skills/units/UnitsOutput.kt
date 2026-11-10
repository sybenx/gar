package org.stypox.dicio.skills.units

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.util.getString
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.floor
import kotlin.math.log10

/** "5 miles is 8.05 kilometers.", with the result large on the card. */
class UnitsOutput(val conversion: Conversion) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(
            R.string.skill_units_result,
            quantity(conversion.amount, conversion.from, ctx.locale),
            quantity(conversion.result, conversion.to, ctx.locale),
        )

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Subtitle(text = quantity(conversion.amount, conversion.from, ctx.locale) + " =")
            Headline(text = quantity(conversion.result, conversion.to, ctx.locale))
        }
    }

    companion object {
        /** "8.05 kilometers", "1 liter". */
        fun quantity(value: Double, unit: MeasureUnit, locale: Locale): String {
            val number = formatValue(value, locale)
            val name = if (number == formatValue(1.0, locale)) unit.singular else unit.plural
            return "$number $name"
        }

        /**
         * Rounded like people say measurements: two decimals from 1 on ("4.23"), one from 100 on
         * ("1,609.3"), three significant digits below 1 ("0.0296").
         */
        fun formatValue(value: Double, locale: Locale): String {
            val magnitude = value.absoluteValue
            val pattern = when {
                magnitude == 0.0 || magnitude >= 100 -> "#,##0.#"
                magnitude >= 1 -> "#,##0.##"
                else -> {
                    val decimals = (2 - floor(log10(magnitude)).toInt()).coerceIn(1, 12)
                    "0." + "#".repeat(decimals)
                }
            }
            return DecimalFormat(pattern, DecimalFormatSymbols(locale)).format(value)
        }
    }
}
