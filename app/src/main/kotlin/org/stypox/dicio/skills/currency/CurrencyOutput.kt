package org.stypox.dicio.skills.currency

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.util.getString
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

sealed interface CurrencyOutput : SkillOutput {

    /**
     * "20 euros is 22.34 US dollars.", "One US dollar is 18.52 Mexican pesos." for a rate, and
     * "With the rates of October 30, ..." when the rates are the kept ones.
     *
     * @param result [request]'s amount converted
     * @param rate how much of the request's target one of its source buys
     * @param date the ECB rates' date
     * @param cached whether new rates couldn't be fetched
     */
    data class Converted(
        val request: CurrencyRequest,
        val result: Double,
        val rate: Double,
        val date: LocalDate,
        val cached: Boolean,
    ) : CurrencyOutput {
        override fun getSpeechOutput(ctx: SkillContext): String {
            val day = DateTimeFormatter.ofPattern("MMMM d", ctx.locale).format(date)
            return if (request.rate) {
                val (one, other) = CurrencyFormat.rateSides(request.from, request.to, rate)
                ctx.getString(
                    if (cached) R.string.skill_currency_rate_cached else R.string.skill_currency_rate,
                    CurrencyRequests.nameOf(one.first).singular,
                    CurrencyFormat.amount(other.second, other.first, ctx.locale, rate = true),
                    day,
                )
            } else {
                ctx.getString(
                    if (cached) R.string.skill_currency_converted_cached
                    else R.string.skill_currency_converted,
                    CurrencyFormat.amount(request.amount, request.from, ctx.locale),
                    CurrencyFormat.amount(result, request.to, ctx.locale),
                    day,
                )
            }
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            val (one, other) = CurrencyFormat.rateSides(request.from, request.to, rate)
            Column {
                if (!request.rate) {
                    Subtitle(text = CurrencyFormat.amount(request.amount, request.from, ctx.locale) + " =")
                }
                Text(
                    text = if (request.rate) {
                        "1 ${one.first} = ${CurrencyFormat.number(other.second, ctx.locale, 4)} ${other.first}"
                    } else {
                        CurrencyFormat.amount(result, request.to, ctx.locale)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Body(
                    text = ctx.getString(
                        R.string.skill_currency_source,
                        "1 ${request.from} = ${CurrencyFormat.number(rate, ctx.locale, 4)} ${request.to}",
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(ctx.locale)
                            .format(date),
                    )
                )
            }
        }
    }

    /** "The European Central Bank has no rate for Bulgarian leva." */
    data class NoRate(val code: String) : CurrencyOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_currency_no_rate, CurrencyRequests.nameOf(code).plural)
    }

    /** No connection, and no rates kept. */
    data object Unavailable : CurrencyOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_currency_unavailable)
    }
}

/** How the currency skill writes amounts, English names, the locale's digits. */
object CurrencyFormat {
    /** "22.34 US dollars", "1 euro", "1,000 Japanese yen". */
    fun amount(value: Double, code: String, locale: Locale, rate: Boolean = false): String {
        val digits = if (rate) {
            if (value < RATE_PRECISE_BELOW) 4 else 2
        } else {
            fractionDigits(code)
        }
        val rounded = number(value, locale, digits)
        val name = CurrencyRequests.nameOf(code)
        return "$rounded ${if (rounded == number(1.0, locale, 0)) name.singular else name.plural}"
    }

    /** [value] with at most [maxDigits] decimals, and none for a whole number. */
    fun number(value: Double, locale: Locale, maxDigits: Int): String {
        val format = NumberFormat.getNumberInstance(locale)
        format.maximumFractionDigits = maxDigits
        val whole = Math.round(value * POWERS[maxDigits]) % POWERS[maxDigits] == 0L
        format.minimumFractionDigits = if (whole) 0 else minOf(maxDigits, 2)
        return format.format(value)
    }

    /**
     * Which way a rate reads better: one of the stronger currency in the weaker one ("One US
     * dollar is 18.52 Mexican pesos", not "is 0.054 US dollars"). Returns (code, 1) and (code,
     * amount).
     */
    fun rateSides(from: String, to: String, rate: Double): Pair<Pair<String, Double>, Pair<String, Double>> =
        if (rate >= 1.0) {
            Pair(Pair(from, 1.0), Pair(to, rate))
        } else {
            Pair(Pair(to, 1.0), Pair(from, 1.0 / rate))
        }

    /** The currency's usual decimals: 2 for dollars, 0 for yen. */
    private fun fractionDigits(code: String): Int = try {
        Currency.getInstance(code).defaultFractionDigits.takeIf { it >= 0 } ?: 2
    } catch (e: IllegalArgumentException) {
        2
    }

    private const val RATE_PRECISE_BELOW = 10.0
    private val POWERS = longArrayOf(1, 10, 100, 1000, 10000)
}
