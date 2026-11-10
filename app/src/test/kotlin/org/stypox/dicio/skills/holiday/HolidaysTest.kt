package org.stypox.dicio.skills.holiday

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.dicio.skill.skill.AlwaysWorstScore
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarSlotRewrites
import org.stypox.dicio.sentences.Sentences
import java.time.LocalDate
import java.time.ZoneOffset

/** Tuesday, October 27, 2026. */
private val TODAY: LocalDate = LocalDate.of(2026, 10, 27)

private fun holiday(name: String) = Holidays.find(name).shouldNotBeNull()

private fun skill() = HolidaySkill(
    HolidayInfo, Sentences.Holiday["en"]!!, now = { TODAY.atTime(12, 0).atZone(ZoneOffset.UTC) },
)

private fun request(input: String): HolidayRequest? =
    skill().score(FakeSkillContext().forInput(input), input).second

class HolidaysTest : StringSpec({
    "easter by the computus" {
        for ((year, date) in listOf(
            2000 to "2000-04-23", 2024 to "2024-03-31", 2025 to "2025-04-20", 2026 to "2026-04-05",
            2027 to "2027-03-28", 2038 to "2038-04-25", 2285 to "2285-03-22",
        )) {
            withClue(year) { Holidays.easter(year).toString() shouldBe date }
        }
    }

    "holidays on a weekday of their month" {
        for ((name, date) in listOf(
            "thanksgiving" to "2026-11-26", "memorial day" to "2026-05-25",
            "labor day" to "2026-09-07", "mother's day" to "2026-05-10",
            "father's day" to "2026-06-21", "election day" to "2026-11-03",
            "martin luther king day" to "2026-01-19", "black friday" to "2026-11-27",
            "good friday" to "2026-04-03", "mardi gras" to "2026-02-17",
        )) {
            withClue(name) { holiday(name).dateIn(2026).toString() shouldBe date }
        }
    }

    "names as people say them" {
        holiday("Christmas").name shouldBe "Christmas"
        holiday("xmas").name shouldBe "Christmas"
        holiday("the fourth of july").name shouldBe "Independence Day"
        holiday("Mothers Day").name shouldBe "Mother's Day"
        holiday("st. patrick's day").name shouldBe "St. Patrick's Day"
        holiday("new year's eve").name shouldBe "New Year's Eve"
        Holidays.find("friday").shouldBeNull()
        Holidays.find("the fourth").shouldBeNull()
        Holidays.find("hanukkah").shouldBeNull()
    }

    "the next one from today on, or the year said" {
        Holidays.date(holiday("christmas"), TODAY) shouldBe LocalDate.of(2026, 12, 25)
        Holidays.date(holiday("easter"), TODAY) shouldBe LocalDate.of(2027, 3, 28)
        Holidays.date(holiday("halloween"), LocalDate.of(2026, 10, 31)) shouldBe
            LocalDate.of(2026, 10, 31)
        val (easter, year) = Holidays.findWithYear("easter next year", TODAY).shouldNotBeNull()
        Holidays.date(easter, TODAY, year) shouldBe LocalDate.of(2027, 3, 28)
        val (halloween, thisYear) = Holidays.findWithYear("halloween this year", TODAY).shouldNotBeNull()
        Holidays.date(halloween, TODAY, thisYear) shouldBe LocalDate.of(2026, 10, 31)
    }

    "the model's date_query day is the holiday's date, which the date resolver reads" {
        GarSlotRewrites.holidayDay("christmas", TODAY) shouldBe "december 25 2026"
        GarSlotRewrites.holidayDay("easter", TODAY) shouldBe "march 28 2027"
        GarSlotRewrites.holidayDay("thanksgiving next year", TODAY) shouldBe "november 25 2027"
        GarSlotRewrites.holidayDay("friday", TODAY).shouldBeNull()
    }

    "countdowns to holidays, days and personal days" {
        request("how many days until christmas") shouldBe
            HolidayRequest.Countdown("Christmas", LocalDate.of(2026, 12, 25))
        request("how long until halloween") shouldBe
            HolidayRequest.Countdown("Halloween", LocalDate.of(2026, 10, 31))
        request("how many sleeps till christmas") shouldBe
            HolidayRequest.Countdown("Christmas", LocalDate.of(2026, 12, 25))
        request("how many days until friday") shouldBe
            HolidayRequest.Countdown(null, LocalDate.of(2026, 10, 30))
        request("how many days until my birthday") shouldBe HolidayRequest.Unknown(mine = true)
        request("how many days until mom's birthday") shouldBe HolidayRequest.Unknown(mine = false)
        request("how many days until our anniversary") shouldBe
            HolidayRequest.Unknown(mine = true, anniversary = true)
        HolidayOutput(request("how many days until christmas")!!, TODAY).days shouldBe 59L
    }

    "the year's end, not a day a year from today" {
        val newYear = HolidayRequest.Countdown("New Year's Day", LocalDate.of(2027, 1, 1))
        for (input in listOf(
            "how many days until next year", "how many days are left in the year",
            "how many days left in the year", "how many days left this year",
        )) {
            withClue(input) { request(input) shouldBe newYear }
        }
        request("how many days until the end of the year") shouldBe
            HolidayRequest.Countdown(null, LocalDate.of(2026, 12, 31))
        // a span is no day: the model gets it
        for (input in listOf(
            "how many days left in the month", "how many days until next week",
            "how many days until next month",
        )) {
            withClue(input) {
                skill().score(FakeSkillContext().forInput(input), input).first shouldBe AlwaysWorstScore
            }
        }
        // a holiday next year still is
        request("how many days until easter next year") shouldBe
            HolidayRequest.Countdown("Easter", LocalDate.of(2027, 3, 28))
    }

    "tax day is the filing deadline: after a weekend and DC's Emancipation Day" {
        for ((year, date) in listOf(
            2022 to "2022-04-18", 2024 to "2024-04-15", 2027 to "2027-04-15", 2028 to "2028-04-18",
            2029 to "2029-04-17", 2033 to "2033-04-18",
        )) {
            withClue(year) { holiday("tax day").dateIn(year).toString() shouldBe date }
        }
    }

    "when a holiday is" {
        request("when is thanksgiving") shouldBe
            HolidayRequest.When("Thanksgiving", LocalDate.of(2026, 11, 26))
        request("when's easter") shouldBe HolidayRequest.When("Easter", LocalDate.of(2027, 3, 28))
        request("what day is christmas") shouldBe
            HolidayRequest.When("Christmas", LocalDate.of(2026, 12, 25))
        request("what's the date of easter") shouldBe
            HolidayRequest.When("Easter", LocalDate.of(2027, 3, 28))
        request("when is my birthday") shouldBe HolidayRequest.Unknown(mine = true)
        request("when is my anniversary") shouldBe
            HolidayRequest.Unknown(mine = true, anniversary = true)
    }

    "other days and questions are left to the other skills" {
        for (input in listOf(
            "when is my next alarm", "how long until the pasta is done",
            "how long until my phone is charged", "what day is the fourth of july",
            "when is the next bus", "when is hanukkah", "what day is it",
        )) {
            withClue(input) {
                skill().score(FakeSkillContext().forInput(input), input).first shouldBe AlwaysWorstScore
            }
        }
    }
})
