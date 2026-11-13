package org.stypox.dicio.skills.weather

import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.CallVerdict
import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeTypeOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.GarMatchCheck
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarCallRunner
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.WeatherCallFilter
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.gar.fakeResolver
import org.stypox.dicio.gar.garRankingWinner
import org.stypox.dicio.gar.online.GarOnlineAskOutput
import org.stypox.dicio.gar.online.GarOnlineGateSkill
import org.stypox.dicio.gar.online.GarOnlineSettingsImpl
import org.stypox.dicio.gar.online.GarOnlineSkill
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.gar.tools.GarToolsProviderImpl
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.UserSettingsModule
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private val ZONE: ZoneId = ZoneId.of("America/Denver")
private val NOW: ZonedDateTime = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZONE)

private val CATALOG = ToolCatalog.load(
    File("src/main/gar/tools.yml").readText(),
    File("src/main/sentences/skill_definitions.yml").readText(),
)
private val WEATHER_TOOL = CATALOG.byName("weather")!!

private val UNITS = WeatherUnits(ResolvedTemperatureUnit.FAHRENHEIT, imperial = true)

/** Breezy Weather's data for Logan, updated ten minutes ago; or no data. */
private fun breezy(fresh: Boolean): List<WeatherForecast>? {
    if (!fresh) return null
    val updated: Instant = NOW.toInstant().minusSeconds(600)
    val json = """{"timestamp":${updated.epochSecond},"location":"Logan","currentTemp":285,
        "currentConditionCode":800,"todayMaxTemp":288,"todayMinTemp":272,"precipProbability":10,
        "forecasts":[{"minTemp":270,"maxTemp":280,"conditionCode":600,"precipProbability":80}]}"""
    return BreezyWeatherData.parse(json, updated, ZONE)
}

/** No network in tests: every request fails. */
private val NO_NETWORK = WeatherHttp { throw IOException("no network in tests") }

private object NoPlaces : WeatherPlaces {
    override val countryCode = "US"
    override suspend fun homeCity() = ""
    override suspend fun setHomeCity(name: String) {}
    override suspend fun useLocation() = false
    override suspend fun setUseLocation(use: Boolean) {}
    override fun hasLocationPermission() = false
    override suspend fun location(): WeatherPlace? = null
}

private fun skill(fresh: Boolean) = GarWeatherSkill(
    WeatherInfo,
    Sentences.Weather["en"]!!,
    lookupFor = { WeatherLookup(OpenMeteo(NO_NETWORK), NoPlaces, { breezy(fresh) }) { NOW } },
    unitsFor = { UNITS },
    today = { NOW.toLocalDate() },
    isLocked = { false },
    appsFor = { FakeWeatherApps() },
)

private fun gate(fresh: Boolean) = GarOnlineGateSkill(
    GarOnlineSkill.WEATHER, skill(fresh), isDeviceLocked = { false },
) {
    GarOnlineSettingsImpl(
        UserSettingsModule.newDataStoreForPreviews(), GarSettingsModule.newDataStoreForPreviews(),
    )
}

private fun runner(target: GarOnlineGateSkill) = GarCallRunner(
    targetInfo = WeatherInfo,
    target = target,
    confirm = true,
    resolver = fakeResolver,
    formatter = englishFormatter(),
    yesNoData = null,
)

private fun call(utterance: String) = GarCall(WEATHER_TOOL, mapOf(), utterance)

/**
 * gar:weather (gar/PARITY.md, batch 5): the weather skill's guard, the model's slotless call
 * through the online gate, Breezy Weather answering without the question, and where Dicio's
 * ranker sends weather requests and their near negatives.
 */
class WeatherSkillTest : StringSpec({
    "the skill keeps only weather requests from Dicio's matches" {
        val skill = skill(fresh = false)
        val ctx = FakeSkillContext()
        for (input in listOf("play cold by coldplay", "it's raining men", "set the thermostat to 70")) {
            withClue(input) { skill.score(ctx.forInput(input), input).first shouldBe AlwaysWorstScore }
        }
        skill.score(ctx.forInput("will it rain tomorrow"), "will it rain tomorrow").second
            .shouldNotBeNull().topic shouldBe WeatherTopic.RAIN
    }

    "the model's slotless call reads the day and the place from the utterance" {
        val request = skill(fresh = false).inputFromCall(FakeSkillContext(), call("weather in denver tomorrow"))
            .shouldBeInstanceOf<WeatherRequest>()
        request.place shouldBe "denver"
        request.time shouldBe WeatherWhen.Day(NOW.toLocalDate().plusDays(1))
    }

    "the weather tool is offered while the skill is off, behind its gate" {
        val offered = GarToolsProviderImpl.offeredTools(
            CATALOG, listOf(), FakeSkillContext(), gatedSkills = listOf(WeatherInfo),
        )
        offered.single().tool.name shouldBe "weather"
        offered.single().skill.shouldBeTypeOf<GarOnlineGateSkill>().online shouldBe GarOnlineSkill.WEATHER
        // on, it is the skill itself
        GarToolsProviderImpl.offeredTools(CATALOG, listOf(WeatherInfo), FakeSkillContext())
            .single { it.tool.name == "weather" }.skill.shouldBeTypeOf<GarWeatherSkill>()
    }

    "the model's call lands on the ask-once question, unless Breezy Weather has the answer" {
        runner(gate(fresh = false)).run(FakeSkillContext(), call("will it rain today"), false)
            .shouldBeInstanceOf<GarOnlineAskOutput>()
        val answer = runner(gate(fresh = true)).run(FakeSkillContext(), call("what's the weather"), false)
            .shouldBeInstanceOf<WeatherAnswerOutput>()
        answer.result.forecast.source shouldBe WeatherSource.BREEZY_WEATHER
        answer.result.reply.shouldBeInstanceOf<WeatherReply.Current>()
    }

    "the app drops the model's weather calls that aren't weather requests" {
        for ((utterance, keep) in listOf(
            "will it rain today" to true, "do i need an umbrella" to true,
            "play cold by coldplay" to false, "it's raining men" to false,
            "set the temperature to seventy" to false,
        )) {
            withClue(utterance) {
                val verdict = WeatherCallFilter.check(RouterOutput.Call(WEATHER_TOOL, mapOf(), listOf(), utterance))
                (verdict == CallVerdict.Keep) shouldBe keep
            }
        }
    }

    "units: Fahrenheit and miles per hour, or Celsius and km/h" {
        UNITS.degrees(12.2) shouldBe 54
        UNITS.speed(19.3) shouldBe 12
        WeatherUnits(ResolvedTemperatureUnit.CELSIUS, imperial = false).degrees(12.2) shouldBe 12
    }

    "Dicio's ranker sends weather requests to the weather's gate, and not the near negatives" {
        val tools = CATALOG.tools
        for (input in listOf(
            "what's the weather", "what's the weather tomorrow", "weather this weekend",
            "will it rain today", "do i need an umbrella", "how hot will it get",
            "is it going to snow on friday", "weather in denver", "what's the temperature",
            "when will it stop raining", "what will the weather be tomorrow",
            "what will the weather be on friday", "what's the forecast on the 5th",
            "is it raining", "how is it outside",
            // 2026-11-02: 3.4 s to the first word, a sentence match all the same
            "what's the weather like for this week", "What's the weather like for this week?",
        )) {
            withClue(input) {
                val (skill, data) = garRankingWinner(input)
                skill shouldBe "weather"
                data.shouldBeInstanceOf<WeatherRequest>()
                // the match check keeps it: it names the weather tool's keywords
                GarMatchCheck.rejects(
                    GarMatchCheck.State(tools, parserFormatter = ENGLISH_PARSER_FORMATTER),
                    "weather", data, 0.9f, input,
                ) shouldBe false
            }
        }
        for (input in listOf(
            "set the thermostat to 70", "play cold by coldplay", "it's raining men",
            "look up the weather", "what will the traffic be tomorrow",
        )) {
            withClue(input) { garRankingWinner(input).first shouldNotBe "weather" }
        }
    }

    "the city answer and the location answer don't take new commands" {
        WeatherPlaceAnswers.city("what's the weather in paris").shouldBeNull()
        WeatherPlaceAnswers.city("salt lake city") shouldBe "salt lake city"
    }

    "locked, GAR doesn't ask for a city or save the answer" {
        val places = RecordingPlaces()
        val lookup = WeatherLookup(OpenMeteo(NO_NETWORK), places, { null }) { NOW }
        val locked = GarWeatherSkill(
            WeatherInfo, Sentences.Weather["en"]!!, lookupFor = { lookup }, unitsFor = { UNITS },
            today = { NOW.toLocalDate() }, isLocked = { true },
        )
        val request = WeatherRequest(WeatherTopic.GENERAL, WeatherWhen.Now, null)
        locked.generateOutput(FakeSkillContext(), request).shouldBeInstanceOf<GarLockedOutput>()
        WeatherCityAnswerSkill(request, lookup, UNITS, isLocked = { true })
            .generateOutput(FakeSkillContext(), "salt lake city")
            .shouldBeInstanceOf<GarFollowUpOutput>().output.shouldBeInstanceOf<GarLockedOutput>()
        WeatherLocationAnswerSkill(request, lookup, UNITS, isLocked = { true })
            .generateOutput(FakeSkillContext(), Unit)
            .shouldBeInstanceOf<GarFollowUpOutput>().output.shouldBeInstanceOf<GarLockedOutput>()
        places.changed shouldBe false
        // unlocked, the question is asked
        GarWeatherSkill(
            WeatherInfo, Sentences.Weather["en"]!!, lookupFor = { lookup }, unitsFor = { UNITS },
            today = { NOW.toLocalDate() }, isLocked = { false },
        ).generateOutput(FakeSkillContext(), request).shouldBeInstanceOf<WeatherPlaceQuestionOutput>()
    }

    "a city answer while Open-Meteo isn't answering opens the user's weather app" {
        val apps = FakeWeatherApps(listOf(WeatherApp("org.breezyweather", "Breezy Weather")))
        val ctx = FakeSkillContext()
        val question = GarWeatherSkill(
            WeatherInfo, Sentences.Weather["en"]!!,
            lookupFor = { WeatherLookup(OpenMeteo(NO_NETWORK), NoPlaces, { null }) { NOW } },
            unitsFor = { UNITS }, today = { NOW.toLocalDate() }, isLocked = { false }, appsFor = { apps },
        ).generateOutput(ctx, WeatherRequest(WeatherTopic.GENERAL, WeatherWhen.Now, null))
            .shouldBeInstanceOf<WeatherPlaceQuestionOutput>()
        val next = question.getInteractionPlan(ctx).shouldBeInstanceOf<InteractionPlan.StartSubInteraction>()
            .nextSkills
        val city = next.filterIsInstance<WeatherCityAnswerSkill>().single()
        val (score, answer) = city.score(ctx, "salt lake city")
        score shouldNotBe AlwaysWorstScore
        city.generateOutput(ctx, answer).shouldBeInstanceOf<GarFollowUpOutput>()
            .output.shouldBeInstanceOf<WeatherAppOpenedOutput>().reason shouldBe WeatherAppReason.UNREACHABLE
        apps.opened shouldBe listOf("org.breezyweather")
    }
})

/** No saved place, and whether anything was saved. */
private class RecordingPlaces : WeatherPlaces {
    var changed = false
    override val countryCode = "US"
    override suspend fun homeCity() = ""
    override suspend fun setHomeCity(name: String) { changed = true }
    override suspend fun useLocation() = false
    override suspend fun setUseLocation(use: Boolean) { changed = true }
    override fun hasLocationPermission() = false
    override suspend fun location(): WeatherPlace? = null
}
