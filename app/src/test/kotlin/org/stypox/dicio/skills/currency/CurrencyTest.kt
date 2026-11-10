package org.stypox.dicio.skills.currency

import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.RecordingSkill
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.garRankingWinner
import org.stypox.dicio.gar.online.GarHttp
import org.stypox.dicio.gar.online.GarOnlineAskOutput
import org.stypox.dicio.gar.online.GarOnlineGateSkill
import org.stypox.dicio.gar.online.GarOnlineSettingsImpl
import org.stypox.dicio.gar.online.GarOnlineSkill
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.settings.datastore.UserSettingsModule
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

private val XML = """
    <?xml version="1.0" encoding="UTF-8"?>
    <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01">
      <Cube>
        <Cube time='2026-10-30'>
          <Cube currency='USD' rate='1.1172'/>
          <Cube currency='JPY' rate='175.68'/>
          <Cube currency='GBP' rate='0.83380'/>
          <Cube currency='MXN' rate='21.1240'/>
        </Cube>
      </Cube>
    </gesmes:Envelope>
""".trimIndent()

private fun parse(input: String) = CurrencyRequests.parse(input, ENGLISH_PARSER_FORMATTER, "USD")

/** An ECB that answers [XML], or fails while [offline], counting its fetches. */
private class FakeEcb : GarHttp {
    var fetches = 0
    var offline = false

    override suspend fun get(url: String): String? {
        url shouldBe EcbRateSource.URL
        fetches++
        if (offline) {
            throw IOException("offline")
        }
        return XML
    }
}

private val CATALOG = ToolCatalog.load(
    File("src/main/gar/tools.yml").readText(),
    File("src/main/sentences/skill_definitions.yml").readText(),
)

class CurrencyTest : StringSpec({
    "the request forms" {
        for ((input, request) in listOf(
            "how much is 20 euros in dollars" to CurrencyRequest(20.0, "EUR", "USD"),
            "how much is twenty euros in dollars" to CurrencyRequest(20.0, "EUR", "USD"),
            "100 yen to usd" to CurrencyRequest(100.0, "JPY", "USD"),
            "convert 50 pounds to euros" to CurrencyRequest(50.0, "GBP", "EUR"),
            "\$20 in pesos" to CurrencyRequest(20.0, "USD", "MXN"),
            "what's 100 canadian dollars in us dollars" to CurrencyRequest(100.0, "CAD", "USD"),
            "how many dollars is 20 euros" to CurrencyRequest(20.0, "EUR", "USD"),
            "how many yen in a dollar" to CurrencyRequest(1.0, "USD", "JPY"),
            "how many pesos do i get for 100 dollars" to CurrencyRequest(100.0, "USD", "MXN"),
            "how much is a dollar worth in yen" to CurrencyRequest(1.0, "USD", "JPY"),
            "how much are 1,500 kroner in euros" to CurrencyRequest(1500.0, "NOK", "EUR"),
            "exchange rate for pesos" to CurrencyRequest(1.0, "MXN", "USD", rate = true),
            "what's the euro to dollar exchange rate" to CurrencyRequest(1.0, "EUR", "USD", rate = true),
            "dollar exchange rate" to CurrencyRequest(1.0, "USD", "EUR", rate = true),
        )) {
            withClue(input) { parse(input) shouldBe request }
        }
    }

    "other words make it no currency request" {
        for (input in listOf(
            "20 pounds in kilograms", "how much is 20 pounds", "who won the game", "is this real",
            "try 20 dollars in euros", "20 euros in euros", "what is the exchange rate of my bank",
            "send 20 dollars to mom", "how many dollars do i have", "set a timer for 20 minutes",
        )) {
            withClue(input) { parse(input).shouldBeNull() }
        }
    }

    "ranked with every skill: money goes to the currency gate, units stay" {
        for (input in listOf("how much is 20 euros in dollars", "100 yen to usd", "exchange rate for pesos")) {
            withClue(input) { garRankingWinner(input).first shouldBe CurrencyInfo.id }
        }
        garRankingWinner("20 pounds in kilograms").first shouldBe "units"
        garRankingWinner("convert 5 miles to kilometers").first shouldBe "units"
    }

    "the ECB's rates, through the euro" {
        val rates = EcbRates.parse(XML)!!
        rates.date shouldBe LocalDate.of(2026, 10, 30)
        rates.rate("EUR", "USD")!! shouldBe (1.1172 plusOrMinus 1e-9)
        rates.rate("USD", "MXN")!! shouldBe (21.1240 / 1.1172 plusOrMinus 1e-9)
        rates.rate("USD", "BGN").shouldBeNull()
        EcbRates.parse("<html>maintenance</html>").shouldBeNull()
    }

    "fetched at most once a day, the kept rates offline with their date" {
        val cache = File(Files.createTempDirectory("gar-ecb").toFile(), "rates.xml")
        val ecb = FakeEcb()
        var now = Instant.parse("2026-10-31T10:00:00Z")
        val source = EcbRateSource(cache, ecb) { now }
        source.rates().shouldBeInstanceOf<RatesResult.Fresh>()
        source.rates().shouldBeInstanceOf<RatesResult.Fresh>()
        ecb.fetches shouldBe 1
        now += Duration.ofHours(25)
        ecb.offline = true
        source.rates().shouldBeInstanceOf<RatesResult.Cached>().rates.date shouldBe
            LocalDate.of(2026, 10, 30)
        ecb.fetches shouldBe 2
        EcbRateSource(File(cache.parentFile, "none.xml"), ecb) { now }.rates() shouldBe
            RatesResult.Unavailable
    }

    "the answers" {
        val rates = EcbRates.parse(XML)!!
        CurrencySkill.answer(CurrencyRequest(20.0, "EUR", "USD"), RatesResult.Fresh(rates))
            .shouldBeInstanceOf<CurrencyOutput.Converted>().result shouldBe (22.344 plusOrMinus 1e-9)
        CurrencySkill.answer(CurrencyRequest(20.0, "EUR", "USD"), RatesResult.Cached(rates))
            .shouldBeInstanceOf<CurrencyOutput.Converted>().cached shouldBe true
        CurrencySkill.answer(CurrencyRequest(1.0, "BGN", "USD"), RatesResult.Fresh(rates)) shouldBe
            CurrencyOutput.NoRate("BGN")
        CurrencySkill.answer(CurrencyRequest(1.0, "EUR", "USD"), RatesResult.Unavailable) shouldBe
            CurrencyOutput.Unavailable
    }

    "amounts as said" {
        CurrencyFormat.amount(22.344, "USD", Locale.US) shouldBe "22.34 US dollars"
        CurrencyFormat.amount(20.0, "EUR", Locale.US) shouldBe "20 euros"
        CurrencyFormat.amount(1.0, "EUR", Locale.US) shouldBe "1 euro"
        CurrencyFormat.amount(15724.6, "JPY", Locale.US) shouldBe "15,725 Japanese yen"
        CurrencyFormat.amount(22.3, "USD", Locale.US) shouldBe "22.30 US dollars"
        CurrencyFormat.amount(1.1172, "USD", Locale.US, rate = true) shouldBe "1.1172 US dollars"
        // a rate reads as one of the stronger currency
        CurrencyFormat.rateSides("MXN", "USD", 0.0529) shouldBe
            Pair(Pair("USD", 1.0), Pair("MXN", 1.0 / 0.0529))
    }

    "the model's calculate calls about money reach the currency gate" {
        val settings = GarOnlineSettingsImpl(
            UserSettingsModule.newDataStoreForPreviews(), GarSettingsModule.newDataStoreForPreviews(),
        )
        val currency = RecordingSkill(CurrencyInfo)
        val gate = {
            GarOnlineGateSkill(GarOnlineSkill.CURRENCY, currency, isDeviceLocked = { false }) { settings }
        }
        val calculate = CATALOG.byName("calculate")!!
        val ctx = FakeSkillContext()
        // off: GAR asks once, naming the ECB
        CurrencyCalls.output(
            ctx, GarCall(calculate, mapOf("calculation" to "20 euros in dollars"), "how much is 20 euros in dollars"),
            gate,
        ).shouldBeInstanceOf<GarOnlineAskOutput>().online shouldBe GarOnlineSkill.CURRENCY
        // on: the skill runs with the request
        settings.setEnabled(listOf(CurrencyInfo.id), true)
        CurrencyCalls.output(
            ctx, GarCall(calculate, mapOf("calculation" to "100 yen to usd"), "100 yen to usd"), gate,
        )
        currency.inputs.single() shouldBe CurrencyRequest(100.0, "JPY", "USD")
        // not money: the calculator's and the unit converter's
        CurrencyCalls.output(ctx, GarCall(calculate, mapOf("calculation" to "5 miles in km"), "5 miles in km"), gate)
            .shouldBeNull()
        CurrencyCalls.output(ctx, GarCall(CATALOG.byName("current_time")!!, mapOf(), "20 euros in dollars"), gate)
            .shouldBeNull()
    }
})
