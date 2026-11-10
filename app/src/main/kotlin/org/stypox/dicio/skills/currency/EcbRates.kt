package org.stypox.dicio.skills.currency

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.stypox.dicio.gar.online.GarHttp
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The European Central Bank's euro reference rates of [date]: how much of each currency one euro
 * buys ([perEuro], euros themselves not included).
 */
data class EcbRates(val date: LocalDate, val perEuro: Map<String, Double>) {
    /** How much of [to] one [from] buys, or null if the ECB has no rate for either. */
    fun rate(from: String, to: String): Double? {
        val fromPerEuro = if (from == EURO) 1.0 else perEuro[from] ?: return null
        val toPerEuro = if (to == EURO) 1.0 else perEuro[to] ?: return null
        return toPerEuro / fromPerEuro
    }

    fun has(code: String): Boolean = code == EURO || code in perEuro

    companion object {
        const val EURO = "EUR"

        private val DATE = Regex("time=['\"](\\d{4}-\\d{2}-\\d{2})['\"]")
        private val RATE = Regex("currency=['\"]([A-Z]{3})['\"]\\s+rate=['\"]([0-9.]+)['\"]")

        /** The rates in the ECB's daily XML (eurofxref-daily.xml), or null if it has none. */
        fun parse(xml: String): EcbRates? {
            val date = DATE.find(xml)?.groupValues?.get(1)?.let {
                try {
                    LocalDate.parse(it)
                } catch (e: DateTimeParseException) {
                    null
                }
            } ?: return null
            val rates = RATE.findAll(xml).mapNotNull { match ->
                match.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 }
                    ?.let { Pair(match.groupValues[1], it) }
            }.toMap()
            return if (rates.isEmpty()) null else EcbRates(date, rates)
        }
    }
}

/** Rates as the currency skill got them. */
sealed interface RatesResult {
    /** Fetched now, or fetched less than a day ago. */
    data class Fresh(val rates: EcbRates) : RatesResult

    /** The last rates kept, since new ones couldn't be fetched: the answer names their date. */
    data class Cached(val rates: EcbRates) : RatesResult

    /** Nothing fetched and nothing kept. */
    data object Unavailable : RatesResult
}

/**
 * The ECB's daily reference rates (https://www.ecb.europa.eu, about 30 currencies, published on
 * working days around 16:00 CET), fetched at most once a day into [cacheFile]. Only the file is
 * fetched: the amounts and currencies asked about never leave the phone. Without a connection the
 * kept rates answer, with their date.
 */
class EcbRateSource(
    private val cacheFile: File,
    private val http: GarHttp = GarHttp.default,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun rates(): RatesResult = withContext(Dispatchers.IO) {
        val cached = cacheFile.takeIf { it.isFile }?.let { file ->
            try {
                EcbRates.parse(file.readText())?.let { Pair(it, Instant.ofEpochMilli(file.lastModified())) }
            } catch (e: IOException) {
                null
            }
        }
        if (cached != null && Duration.between(cached.second, now()) < MAX_AGE &&
            !cached.second.isAfter(now())
        ) {
            return@withContext RatesResult.Fresh(cached.first)
        }
        val fetched = try {
            http.get(URL)?.let { xml -> EcbRates.parse(xml)?.let { Pair(xml, it) } }
        } catch (e: IOException) {
            null
        }
        if (fetched != null) {
            try {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(fetched.first)
                cacheFile.setLastModified(now().toEpochMilli())
            } catch (e: IOException) {
                // the answer doesn't need the cache
            }
            RatesResult.Fresh(fetched.second)
        } else if (cached != null) {
            RatesResult.Cached(cached.first)
        } else {
            RatesResult.Unavailable
        }
    }

    companion object {
        const val URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
        val MAX_AGE: Duration = Duration.ofDays(1)

        /** The app's rates, kept in its files directory. */
        fun forFilesDir(filesDir: File): EcbRateSource =
            EcbRateSource(File(filesDir, "gar/currency/eurofxref-daily.xml"))
    }
}
