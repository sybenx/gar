package org.stypox.dicio.gar

import cc.aaro.gar.router.CallVerdict
import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.resolve.ResolvedDate
import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarEverydayCalls
import org.stypox.dicio.gar.call.GarRoutedSkill
import org.stypox.dicio.gar.call.GarSlotRewrites
import org.stypox.dicio.gar.call.garCallFilter
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.calculator.CalculatorSkill
import org.stypox.dicio.skills.date.DateInfo
import org.stypox.dicio.skills.date.DateSkill
import org.stypox.dicio.skills.holiday.HolidayOutput
import org.stypox.dicio.skills.holiday.HolidayRequest
import org.stypox.dicio.skills.units.UnitsOutput
import org.stypox.dicio.skills.world_time.WorldTimeOutput
import org.stypox.dicio.skills.world_time.WorldTimeRequest
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

private val CATALOG = ToolCatalog.load(
    File("src/main/gar/tools.yml").readText(),
    File("src/main/sentences/skill_definitions.yml").readText(),
)

private fun tool(name: String) = CATALOG.byName(name) ?: throw AssertionError("no tool $name")

private val NOW: ZonedDateTime = ZonedDateTime.of(2026, 10, 27, 10, 0, 0, 0, ZoneId.of("America/Denver"))

private fun output(call: GarCall) = GarEverydayCalls.output(FakeSkillContext(), call) { NOW }

/** The model's call of [target], run like GarFallback runs it (no confirmation). */
private fun routed(info: SkillInfo, target: Skill<*>) =
    GarRoutedSkill(info, target, confirm = false, formatterFactory = { englishFormatter() })

/** gar:answers: the model's calls of current_time, calculate and date_query, read in Kotlin. */
class GarEverydayCallsTest : StringSpec({
    "a current_time call that names a place tells the time there" {
        output(GarCall(tool("current_time"), mapOf(), "hey what's the time over in tokyo"))
            .shouldBeInstanceOf<WorldTimeOutput>().request
            .shouldBeInstanceOf<WorldTimeRequest.TimeIn>().place.zone shouldBe ZoneId.of("Asia/Tokyo")
        output(GarCall(tool("current_time"), mapOf(), "how far ahead is london"))
            .shouldBeInstanceOf<WorldTimeOutput>().offsetMinutes shouldBe 360
        output(GarCall(tool("current_time"), mapOf(), "what time is it")).shouldBeNull()
        output(GarCall(tool("current_time"), mapOf(), "is it late")).shouldBeNull()
    }

    "a calculate call with units converts them" {
        output(GarCall(tool("calculate"), mapOf("calculation" to "five miles in kilometers"), ""))
            .shouldBeInstanceOf<UnitsOutput>().conversion.to.singular shouldBe "kilometer"
        // the slot alone isn't a conversion, the utterance is
        output(GarCall(tool("calculate"), mapOf("calculation" to "a liter"), "how many cups in a liter"))
            .shouldBeInstanceOf<UnitsOutput>().conversion.from.singular shouldBe "liter"
        output(GarCall(tool("calculate"), mapOf("calculation" to "twelve times seven"), ""))
            .shouldBeNull()
        output(GarCall(tool("calculate"), mapOf("calculation" to "add two to three"), "add two to three"))
            .shouldBeNull()
    }

    "a date_query call for a holiday names it, and counts the days when asked" {
        val christmas = mapOf("day" to ResolvedDate(LocalDate.of(2026, 12, 25)))
        output(GarCall(tool("date_query"), mapOf("day" to "christmas"), "is christmas soon", christmas))
            .shouldBeInstanceOf<HolidayOutput>().request shouldBe
            HolidayRequest.When("Christmas", LocalDate.of(2026, 12, 25))
        output(GarCall(
            tool("date_query"), mapOf("day" to "christmas"), "how many days until christmas", christmas,
        )).shouldBeInstanceOf<HolidayOutput>().days shouldBe 59L
        val friday = mapOf("day" to ResolvedDate(LocalDate.of(2026, 10, 30)))
        output(GarCall(tool("date_query"), mapOf("day" to "friday"), "how long until friday", friday))
            .shouldBeInstanceOf<HolidayOutput>().request shouldBe
            HolidayRequest.Countdown(null, LocalDate.of(2026, 10, 30))
        output(GarCall(tool("date_query"), mapOf("day" to "friday"), "what's the date on friday", friday))
            .shouldBeNull()
        output(GarCall(tool("date_query"), mapOf(), "what's the date", mapOf("day" to null)))
            .shouldBeNull()
    }

    "countdown questions" {
        GarEverydayCalls.asksHowLong("how many days until christmas") shouldBe true
        GarEverydayCalls.asksHowLong("how long till my birthday") shouldBe true
        GarEverydayCalls.asksHowLong("how many weeks left before thanksgiving") shouldBe true
        GarEverydayCalls.asksHowLong("what's the date on friday") shouldBe false
        GarEverydayCalls.asksHowLong("how long is a day") shouldBe false
    }

    "the model's date_query for a holiday passes the slot check and runs the date skill's call" {
        // (without a date keyword, "when is christmas", Guard B drops it; the holiday sentences
        // take that one before the model)
        val call = RouterOutput.Call(
            tool("date_query"), mapOf("day" to "christmas"), listOf(),
            Utterances.normalize("what day is christmas on"),
        )
        garCallFilter(FakeSkillContext()).check(call) shouldBe CallVerdict.Keep
        val target = DateSkill(DateInfo, Sentences.Date["en"])
        val output = routed(DateInfo, target).generateOutput(
            FakeSkillContext(),
            GarCall(tool("date_query"), mapOf("day" to "christmas"), "what day is christmas on"),
        )
        val date = output.shouldBeInstanceOf<HolidayOutput>().request
            .shouldBeInstanceOf<HolidayRequest.When>().date
        Pair(date.monthValue, date.dayOfMonth) shouldBe Pair(12, 25)
    }

    "a calculation said as a square or a tip is rewritten for the calculator" {
        GarSlotRewrites.calculation("five squared") shouldBe "five to the power of 2"
        GarSlotRewrites.calculation("two cubed") shouldBe "two to the power of 3"
        GarSlotRewrites.calculation("a twenty percent tip on eighty") shouldBe
            "twenty times eighty divided by 100"
        val target = CalculatorSkill(
            CalculatorInfo, Sentences.Calculator["en"]!!, Sentences.CalculatorOperators["en"]!!,
        )
        // GarCallFlowTest pins the calculator's results; here only that a conversion goes elsewhere
        routed(CalculatorInfo, target).generateOutput(
            FakeSkillContext(),
            GarCall(tool("calculate"), mapOf("calculation" to "convert 70 fahrenheit to celsius"), ""),
        ).shouldBeInstanceOf<UnitsOutput>()
    }
})
