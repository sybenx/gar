package org.stypox.dicio.skills.weather

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.GZIPOutputStream

/** Monday, November 2, 2026, 9 am in Logan, Utah (MST). */
private val ZONE: ZoneId = ZoneId.of("America/Denver")
private val NOW: ZonedDateTime = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZONE)
private val TODAY: LocalDate = NOW.toLocalDate()

private fun parse(input: String): WeatherRequest? =
    WeatherWords.parse(input, TODAY) { if (it == "the fifth") LocalDate.of(2026, 11, 5) else null }

private fun request(input: String): WeatherRequest = parse(input).shouldNotBeNull()

/** Open-Meteo's answer for Logan: rain this afternoon until 4 pm, snow on Thursday. */
private val FORECAST_JSON = """
{"latitude":41.74,"longitude":-111.83,"utc_offset_seconds":-25200,"timezone":"America/Denver",
 "current":{"time":"2026-11-02T09:00","interval":900,"temperature_2m":8.4,"weather_code":61,
   "wind_speed_10m":19.3,"is_day":1},
 "hourly":{"time":["2026-11-02T09:00","2026-11-02T10:00","2026-11-02T11:00","2026-11-02T12:00",
   "2026-11-02T13:00","2026-11-02T14:00","2026-11-02T15:00","2026-11-02T16:00","2026-11-02T17:00"],
   "temperature_2m":[8.4,9.0,10.1,11.0,11.5,12.0,11.2,10.0,9.0],
   "precipitation_probability":[80,85,80,70,65,55,40,20,10],
   "weather_code":[61,61,63,61,61,80,3,2,1]},
 "daily":{"time":["2026-11-02","2026-11-03","2026-11-04","2026-11-05","2026-11-06","2026-11-07",
   "2026-11-08","2026-11-09"],
   "weather_code":[61,0,2,73,3,1,80,0],
   "temperature_2m_max":[12.2,12.6,10.0,1.5,4.0,6.0,8.0,9.0],
   "temperature_2m_min":[2.0,-0.4,1.0,-4.0,-3.0,-1.0,2.0,0.5],
   "precipitation_probability_max":[85,5,30,90,10,0,60,null],
   "snowfall_sum":[0.0,0.0,0.0,6.3,0.0,0.0,0.0,0.0],
   "wind_speed_10m_max":[25.0,10.0,12.0,30.0,15.0,8.0,20.0,9.0]}}
""".trimIndent()

private const val GEOCODING_JSON = """
{"results":[
 {"id":7281838,"name":"Logan City","latitude":-27.63917,"longitude":153.10944,"country_code":"AU",
  "admin1":"Queensland","country":"Australia"},
 {"id":5777544,"name":"Logan","latitude":41.73549,"longitude":-111.83439,"country_code":"US",
  "admin1":"Utah","country":"United States"},
 {"id":4516989,"name":"Logan","latitude":39.54007,"longitude":-82.4071,"country_code":"US",
  "admin1":"Ohio","country":"United States"}]}
"""

private val FORECAST = OpenMeteo.parseForecast(FORECAST_JSON, "Logan")

/** Open-Meteo without the network: the URLs asked, and the fixtures above. */
private class FakeHttp(private val fail: Boolean = false) : WeatherHttp {
    val urls = mutableListOf<String>()

    override fun get(url: String): String {
        urls.add(url)
        if (fail) throw IOException("offline")
        return when {
            url.startsWith(OpenMeteo.GEOCODING_URL) ->
                if (url.contains("name=logan&")) GEOCODING_JSON else "{}"
            url.startsWith(OpenMeteo.FORECAST_URL) -> FORECAST_JSON
            else -> throw IOException("unexpected $url")
        }
    }
}

private class FakePlaces(
    var home: String = "",
    var useLocation: Boolean = false,
    var permission: Boolean = false,
    var here: WeatherPlace? = null,
) : WeatherPlaces {
    override val countryCode: String = "US"
    override suspend fun homeCity() = home
    override suspend fun setHomeCity(name: String) { home = name }
    override suspend fun useLocation() = useLocation
    override suspend fun setUseLocation(use: Boolean) { useLocation = use }
    override fun hasLocationPermission() = permission
    override suspend fun location() = here
}

/** Breezy Weather's Gadgetbridge JSON for Logan (Kelvin, km/h), updated [updated]. */
private fun breezyJson(updated: Instant, location: String = "Logan"): String = """
{"timestamp":${updated.epochSecond},"location":"$location","currentTemp":285,"currentConditionCode":800,
 "currentCondition":"Clear","todayMaxTemp":288,"todayMinTemp":272,"windSpeed":12.5,
 "precipProbability":10,"latitude":41.74,"longitude":-111.83,"isCurrentLocation":1,
 "forecasts":[{"minTemp":270,"maxTemp":280,"conditionCode":600,"precipProbability":80},
              {"minTemp":268,"maxTemp":276,"conditionCode":802,"precipProbability":20}],
 "hourly":[{"timestamp":${updated.epochSecond},"temp":285,"conditionCode":800,"precipProbability":0}]}
"""

class WeatherTest : StringSpec({
    "Google's weather requests are read with their topic, day and place" {
        for ((input, expected) in listOf(
            "what's the weather" to Triple(WeatherTopic.GENERAL, WeatherWhen.Now, null),
            "weather tomorrow" to Triple(WeatherTopic.GENERAL, WeatherWhen.Day(TODAY.plusDays(1)), null),
            "what's the weather this weekend" to
                Triple(WeatherTopic.GENERAL, WeatherWhen.Weekend(LocalDate.of(2026, 11, 7)), null),
            "will it rain today" to Triple(WeatherTopic.RAIN, WeatherWhen.Day(TODAY), null),
            "do i need an umbrella" to Triple(WeatherTopic.UMBRELLA, WeatherWhen.Day(TODAY), null),
            "how hot will it get" to Triple(WeatherTopic.HIGH, WeatherWhen.Day(TODAY), null),
            "is it going to snow on friday" to
                Triple(WeatherTopic.SNOW, WeatherWhen.Day(LocalDate.of(2026, 11, 6)), null),
            "weather in denver" to Triple(WeatherTopic.GENERAL, WeatherWhen.Now, "denver"),
            "what's the temperature" to Triple(WeatherTopic.TEMPERATURE, WeatherWhen.Now, null),
            "when will it stop raining" to Triple(WeatherTopic.STOPS, WeatherWhen.Hours, null),
            "what's the weather like in salt lake city tomorrow" to
                Triple(WeatherTopic.GENERAL, WeatherWhen.Day(TODAY.plusDays(1)), "salt lake city"),
            "denver weather" to Triple(WeatherTopic.GENERAL, WeatherWhen.Now, "denver"),
            "how cold will it get tonight" to Triple(WeatherTopic.LOW, WeatherWhen.Night(TODAY), null),
            "how cold is it outside" to Triple(WeatherTopic.TEMPERATURE, WeatherWhen.Now, null),
            "is it windy" to Triple(WeatherTopic.WIND, WeatherWhen.Now, null),
            "what's the forecast for this week" to Triple(WeatherTopic.GENERAL, WeatherWhen.Week(TODAY), null),
            "is it raining" to Triple(WeatherTopic.RAIN, WeatherWhen.Now, null),
            "when is it going to rain" to Triple(WeatherTopic.STARTS, WeatherWhen.Hours, null),
            "what's the weather on the fifth" to
                Triple(WeatherTopic.GENERAL, WeatherWhen.Day(LocalDate.of(2026, 11, 5)), null),
            "any snow this weekend in park city" to
                Triple(WeatherTopic.SNOW, WeatherWhen.Weekend(LocalDate.of(2026, 11, 7)), "park city"),
            "can you tell me the weather in paris france" to
                Triple(WeatherTopic.GENERAL, WeatherWhen.Now, "paris france"),
            "is it cold in tokyo right now" to Triple(WeatherTopic.TEMPERATURE, WeatherWhen.Now, "tokyo"),
            "what's the high today" to Triple(WeatherTopic.HIGH, WeatherWhen.Day(TODAY), null),
            "how is it outside" to Triple(WeatherTopic.GENERAL, WeatherWhen.Now, null),
            "do i need a jacket" to Triple(WeatherTopic.GENERAL, WeatherWhen.Now, null),
            "what's the weather in the morning" to Triple(WeatherTopic.GENERAL, WeatherWhen.Day(TODAY), null),
        )) {
            withClue(input) {
                val request = request(input)
                Triple(request.topic, request.time, request.place) shouldBe expected
            }
        }
        request("is it going to snow on friday").precipitation shouldBe Precipitation.SNOW
        request("when will it stop snowing").precipitation shouldBe Precipitation.SNOW
    }

    "near negatives and other skills' commands aren't weather requests" {
        for (input in listOf(
            "set the thermostat to 70", "play cold by coldplay", "it's raining men",
            "set the temperature to 70", "turn on the heat", "play purple rain",
            "what's the temperature of the sun", "is the coffee hot", "how hot is the sun",
            "look up the weather", "when is the umbrella academy coming out",
            "is snow white a disney movie", "how many degrees in a circle", "it's cold",
            "what's the time in denver", "what will the traffic be tomorrow", "hello",
        )) {
            withClue(input) { parse(input).shouldBeNull() }
        }
    }

    "answers to which city are a city or the location" {
        WeatherPlaceAnswers.city("logan") shouldBe "logan"
        WeatherPlaceAnswers.city("i'm in salt lake city") shouldBe "salt lake city"
        WeatherPlaceAnswers.city("Denver, Colorado.") shouldBe "denver colorado"
        WeatherPlaceAnswers.city("use my location").shouldBeNull()
        WeatherPlaceAnswers.isLocation("Use my location") shouldBe true
        WeatherPlaceAnswers.isLocation("where i am") shouldBe true
        for (input in listOf("what time is it", "set a timer for ten minutes", "no", "never mind",
            "what's the weather in paris", "turn on the flashlight please")) {
            withClue(input) { WeatherPlaceAnswers.city(input).shouldBeNull() }
        }
    }

    "Open-Meteo's forecast is read at the place's local time" {
        FORECAST.now shouldBe LocalDateTime.of(2026, 11, 2, 9, 0)
        FORECAST.current.shouldNotBeNull().condition shouldBe WeatherCondition.RAIN
        FORECAST.days shouldHaveSize 8
        FORECAST.day(LocalDate.of(2026, 11, 5)).shouldNotBeNull().snowfall shouldBe 6.3
        FORECAST.days.last().precipitationChance.shouldBeNull()
        FORECAST.nextHours shouldHaveSize 9
    }

    "places are looked up by name only, the phone's country first, with a region after it" {
        val http = FakeHttp()
        val meteo = OpenMeteo(http)
        meteo.findPlace("logan", "US").shouldNotBeNull().region shouldBe "Utah"
        meteo.findPlace("logan", "AU").shouldNotBeNull().name shouldBe "Logan City"
        meteo.findPlace("logan ohio", "US").shouldNotBeNull().region shouldBe "Ohio"
        meteo.findPlace("nowhere at all", "US").shouldBeNull()
        http.urls.first() shouldBe
            "https://geocoding-api.open-meteo.com/v1/search?name=logan&count=10&language=en&format=json"
    }

    "the forecast request sends coordinates rounded to about 1 km, and nothing else about the user" {
        val http = FakeHttp()
        OpenMeteo(http).forecast(WeatherPlace("Logan", 41.735491, -111.834392))
        val url = http.urls.single()
        url shouldContain "latitude=41.74&longitude=-111.83&"
        url shouldNotContain "41.735"
        url shouldNotContain "Logan"
    }

    "replies: now, days, highs and lows" {
        WeatherAnswer.reply(request("what's the weather"), FORECAST)
            .shouldBeInstanceOf<WeatherReply.Current>().today.shouldNotBeNull().high shouldBe 12.2
        WeatherAnswer.reply(request("weather tomorrow"), FORECAST)
            .shouldBeInstanceOf<WeatherReply.Day>().label shouldBe DayLabel.Tomorrow
        WeatherAnswer.reply(request("what's the weather this weekend"), FORECAST)
            .shouldBeInstanceOf<WeatherReply.Days>().days.map { it.date } shouldBe
            listOf(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8))
        WeatherAnswer.reply(request("how hot will it get"), FORECAST)
            .shouldBeInstanceOf<WeatherReply.High>().day.high shouldBe 12.2
        WeatherAnswer.reply(request("how cold will it get tonight"), FORECAST)
            .shouldBeInstanceOf<WeatherReply.Low>().label shouldBe DayLabel.Tonight
        WeatherAnswer.reply(request("what's the temperature"), FORECAST)
            .shouldBeInstanceOf<WeatherReply.Temperature>().now.temperature shouldBe 8.4
        WeatherAnswer.reply(request("what's the forecast for this week"), FORECAST)
            .shouldBeInstanceOf<WeatherReply.Days>().let {
                it.days shouldHaveSize 7
                it.label shouldBe DayLabel.Week
            }
        // next week starts on Monday and says so; the forecast has only its first days
        WeatherAnswer.reply(request("what's the weather next week"), FORECAST)
            .shouldBeInstanceOf<WeatherReply.Days>().label shouldBe DayLabel.NextWeek
        // past the forecast
        WeatherAnswer.reply(
            WeatherRequest(WeatherTopic.GENERAL, WeatherWhen.Day(TODAY.plusDays(20)), null), FORECAST,
        ) shouldBe WeatherReply.NoForecast(8)
    }

    "replies: rain, snow and the umbrella say yes, maybe or no" {
        fun chance(input: String) = WeatherAnswer.reply(request(input), FORECAST)
            .shouldBeInstanceOf<WeatherReply.Chance>().answer
        chance("will it rain today") shouldBe WeatherChance.YES
        chance("will it rain tomorrow") shouldBe WeatherChance.NO
        chance("will it rain on wednesday") shouldBe WeatherChance.MAYBE
        chance("is it going to snow on thursday") shouldBe WeatherChance.YES
        chance("is it going to snow today") shouldBe WeatherChance.NO
        // the wettest day of the weekend
        WeatherAnswer.reply(request("will it rain this weekend"), FORECAST)
            .shouldBeInstanceOf<WeatherReply.Chance>().let {
                it.day.date shouldBe LocalDate.of(2026, 11, 8)
                it.label shouldBe DayLabel.Weekend
                it.answer shouldBe WeatherChance.YES
            }
        WeatherAnswer.reply(request("do i need an umbrella"), FORECAST)
            .shouldBeInstanceOf<WeatherReply.Umbrella>().answer shouldBe WeatherChance.YES
        WeatherAnswer.reply(request("do i need an umbrella tomorrow"), FORECAST)
            .shouldBeInstanceOf<WeatherReply.Umbrella>().answer shouldBe WeatherChance.NO
        WeatherAnswer.reply(request("is it raining"), FORECAST) shouldBe
            WeatherReply.FallingNow(Precipitation.RAIN, falling = true)
    }

    "replies: when the rain stops and starts, from the hours" {
        WeatherAnswer.reply(request("when will it stop raining"), FORECAST) shouldBe
            WeatherReply.Stops(Precipitation.RAIN, falling = true, at = LocalDateTime.of(2026, 11, 2, 15, 0))
        WeatherAnswer.reply(request("when is it going to rain"), FORECAST) shouldBe
            WeatherReply.Starts(Precipitation.RAIN, falling = true, at = null)
        WeatherAnswer.reply(request("when will it stop snowing"), FORECAST) shouldBe
            WeatherReply.Stops(Precipitation.SNOW, falling = false, at = null)
    }

    "Breezy Weather's data answers while it is fresh and has the place" {
        val updated = NOW.toInstant().minusSeconds(30 * 60)
        val locations = BreezyWeatherData.parse(breezyJson(updated), updated, ZONE)
        val logan = locations.single()
        logan.current.shouldNotBeNull().temperature shouldBe (285 - 273.15)
        logan.day(TODAY.plusDays(1)).shouldNotBeNull().condition shouldBe WeatherCondition.SNOW

        fun usable(input: String, at: ZonedDateTime = NOW) =
            BreezyWeatherData.usable(request(input), locations, at.toInstant(), ZONE)
        usable("what's the weather").shouldNotBeNull().source shouldBe WeatherSource.BREEZY_WEATHER
        usable("weather in logan").shouldNotBeNull()
        usable("weather tomorrow").shouldNotBeNull()
        // another place, a day Breezy didn't send, data too old for now but fine for tomorrow
        usable("weather in denver").shouldBeNull()
        usable("weather on friday").shouldBeNull()
        usable("what's the weather", NOW.plusHours(3)).shouldBeNull()
        usable("weather tomorrow", NOW.plusHours(3)).shouldNotBeNull()
        usable("weather tomorrow", NOW.plusHours(9)).shouldBeNull()
        BreezyWeatherData.usable(request("what's the weather"), null, NOW.toInstant(), ZONE).shouldBeNull()
    }

    "Breezy Weather's compressed broadcast is read, and capped" {
        val json = "[" + breezyJson(NOW.toInstant()) + "]"
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(json.toByteArray()) }
        }.toByteArray()
        BreezyWeatherReceiver.gunzip(bytes) shouldBe json
        val huge = java.io.ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(ByteArray(5_000_000)) }
        }.toByteArray()
        shouldThrow<IllegalArgumentException> { BreezyWeatherReceiver.gunzip(huge) }
    }

    "the lookup: Breezy first, then the place said, the home city, the location, or a question" {
        // Breezy answers offline: no request at all
        val updated = NOW.toInstant().minusSeconds(600)
        val offlineHttp = FakeHttp(fail = true)
        val withBreezy = WeatherLookup(OpenMeteo(offlineHttp), FakePlaces(), {
            BreezyWeatherData.parse(breezyJson(updated), updated, ZONE)
        }) { NOW }
        withBreezy.offline(request("what's the weather")).shouldNotBeNull()
        offlineHttp.urls.shouldBeEmpty()

        val places = FakePlaces()
        val lookup = WeatherLookup(OpenMeteo(FakeHttp()), places, { null }) { NOW }
        lookup.offline(request("what's the weather")).shouldBeNull()
        lookup.online(request("what's the weather")) shouldBe WeatherResult.AskPlace(AskReason.FIRST)
        lookup.online(request("weather in logan")).shouldBeInstanceOf<WeatherResult.Answer>()
            .forecast.placeName shouldBe "Logan"
        lookup.online(request("weather in atlantis")) shouldBe WeatherResult.PlaceNotFound("atlantis")

        // the answer to the question is remembered as the home city
        lookup.answerCity(request("what's the weather"), "logan").shouldBeInstanceOf<WeatherResult.Answer>()
        places.home shouldBe "Logan, Utah"
        lookup.answerCity(request("what's the weather"), "atlantis") shouldBe
            WeatherResult.AskPlace(AskReason.NOT_FOUND)

        // "use my location": the home city goes, the location is used once allowed
        lookup.useLocation(request("what's the weather")) shouldBe WeatherResult.AskPlace(AskReason.NO_PERMISSION)
        places.home shouldBe ""
        places.useLocation shouldBe true
        places.permission = true
        lookup.online(request("what's the weather")) shouldBe WeatherResult.AskPlace(AskReason.NO_LOCATION)
        places.here = WeatherPlace("", 41.7355, -111.8344)
        lookup.online(request("what's the weather")).shouldBeInstanceOf<WeatherResult.Answer>()
            .nearYou shouldBe true

        // no internet
        WeatherLookup(OpenMeteo(FakeHttp(fail = true)), FakePlaces(home = "Logan"), { null }) { NOW }
            .online(request("what's the weather")) shouldBe WeatherResult.NoConnection
    }

    "conditions from both sources" {
        WeatherCondition.fromWmo(0) shouldBe WeatherCondition.CLEAR
        WeatherCondition.fromWmo(75) shouldBe WeatherCondition.SNOW
        WeatherCondition.fromWmo(95) shouldBe WeatherCondition.THUNDERSTORM
        WeatherCondition.fromOwm(616) shouldBe WeatherCondition.SLEET
        WeatherCondition.fromOwm(211) shouldBe WeatherCondition.THUNDERSTORM
        WeatherCondition.fromOwm(803) shouldBe WeatherCondition.CLOUDY
        WeatherCondition.fromOwm(3200) shouldBe WeatherCondition.UNKNOWN
    }
})
