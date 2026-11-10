package org.stypox.dicio.skills.world_time

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.ZoneId
import java.time.ZonedDateTime

/** Tuesday, October 27, 2026, 10 am in Denver (MDT, UTC-6; the UK is on GMT since the 25th). */
private val NOW: ZonedDateTime = ZonedDateTime.of(2026, 10, 27, 10, 0, 0, 0, ZoneId.of("America/Denver"))

private fun place(text: String): Place = WorldPlaces.find(text).shouldNotBeNull()

class WorldTimeTest : StringSpec({
    "places are found by their zone's city, country, state or time zone name" {
        for ((text, zone) in listOf(
            "tokyo" to "Asia/Tokyo", "Tokyo" to "Asia/Tokyo", "new york" to "America/New_York",
            "New York City" to "America/New_York", "london" to "Europe/London",
            "buenos aires" to "America/Argentina/Buenos_Aires", "St. John's" to "America/St_Johns",
            "japan" to "Asia/Tokyo", "the uk" to "Europe/London", "england" to "Europe/London",
            "california" to "America/Los_Angeles", "texas" to "America/Chicago",
            "hawai'i" to "Pacific/Honolulu", "arizona" to "America/Phoenix",
            "washington" to "America/New_York", "washington state" to "America/Los_Angeles",
            "pacific time" to "America/Los_Angeles", "eastern" to "America/New_York",
            "utc" to "UTC", "beijing" to "Asia/Shanghai", "mumbai" to "Asia/Kolkata",
            "san francisco" to "America/Los_Angeles", "australia" to "Australia/Sydney",
            // what people call them
            "la" to "America/Los_Angeles", "L.A." to "America/Los_Angeles",
            "vegas" to "America/Los_Angeles", "philly" to "America/New_York",
            "maui" to "Pacific/Honolulu", "kauai" to "Pacific/Honolulu", "bali" to "Asia/Makassar",
            "cabo" to "America/Mazatlan", "jersey" to "America/New_York",
            "jersey city" to "America/New_York",
            // the likelier place than the IANA id's
            "san juan" to "America/Puerto_Rico", "samoa" to "Pacific/Apia",
            "american samoa" to "Pacific/Pago_Pago",
        )) {
            withClue(text) { place(text).zone shouldBe ZoneId.of(zone) }
        }
        place("japan").name shouldBe "Japan"
        place("australia").name shouldBe "Sydney, Australia"
        place("maui").name shouldBe "Maui"
    }

    "a city with its state takes the state's zone, with its country the city's" {
        place("portland maine") shouldBe Place("Portland, Maine", ZoneId.of("America/New_York"))
        place("paris texas") shouldBe Place("Paris, Texas", ZoneId.of("America/Chicago"))
        place("perth australia") shouldBe Place("Perth, Australia", ZoneId.of("Australia/Perth"))
        place("paris france") shouldBe Place("Paris, France", ZoneId.of("Europe/Paris"))
        // "washington" after a city is the state, and Georgia the country for its own cities
        place("seattle washington") shouldBe
            Place("Seattle, Washington", ZoneId.of("America/Los_Angeles"))
        place("tbilisi georgia") shouldBe Place("Tbilisi, Georgia", ZoneId.of("Asia/Tbilisi"))
        place("athens georgia") shouldBe Place("Athens, Georgia", ZoneId.of("America/New_York"))
        place("vancouver canada").name shouldBe "Vancouver, Canada"
    }

    "words that aren't places are none" {
        for (text in listOf("the morning", "kalamazoo", "north", "act", "my house", "", "the")) {
            withClue(text) { WorldPlaces.find(text).shouldBeNull() }
        }
    }

    "captured places lose the fillers around them" {
        WorldTimePhrases.timeIn("tokyo right now")?.zone shouldBe ZoneId.of("Asia/Tokyo")
        WorldTimePhrases.timeIn("london local time")?.zone shouldBe ZoneId.of("Europe/London")
        WorldTimePhrases.timeIn("the morning").shouldBeNull()
        // time zone names that end in a filler
        for ((text, zone) in listOf(
            "eastern standard time" to "America/New_York",
            "pacific standard time" to "America/Los_Angeles",
            "central daylight time" to "America/Chicago", "greenwich mean time" to "UTC",
            "coordinated universal time" to "UTC", "universal time" to "UTC",
            "central european time" to "Europe/Paris", "japan standard time" to "Asia/Tokyo",
            "atlantic time" to "America/Halifax", "atlantic standard time" to "America/Halifax",
        )) {
            withClue(text) { WorldTimePhrases.timeIn(text)?.zone shouldBe ZoneId.of(zone) }
        }
        WorldTimePhrases.difference("eastern standard time")?.place?.zone shouldBe
            ZoneId.of("America/New_York")
        // not Atlantic City's
        WorldPlaces.find("atlantic city").shouldBeNull()
    }

    "a place GAR doesn't know is named, unless it may be no place" {
        WorldTimePhrases.unknownPlace("moab") shouldBe "Moab"
        WorldTimePhrases.unknownPlace("yellowstone right now") shouldBe "Yellowstone"
        WorldTimePhrases.unknownPlace("the bronx") shouldBe "the Bronx"
        for (capture in listOf(
            "military time", "24 hour format", "my time zone", "my area", "here", "two hours",
            "the morning", "an hour", "a few minutes", "seconds", "the next five minutes",
            "the middle of the night tonight",
        )) {
            withClue(capture) { WorldTimePhrases.unknownPlace(capture).shouldBeNull() }
        }
    }

    "a time difference is with the phone's zone or between two places" {
        WorldTimePhrases.difference("london") shouldBe
            WorldTimeRequest.Difference(place("london"), null)
        WorldTimePhrases.difference("here and tokyo") shouldBe
            WorldTimeRequest.Difference(place("tokyo"), null)
        WorldTimePhrases.difference("tokyo and me") shouldBe
            WorldTimeRequest.Difference(place("tokyo"), null)
        WorldTimePhrases.difference("between new york and london") shouldBe
            WorldTimeRequest.Difference(place("london"), place("new york"))
        WorldTimePhrases.difference("new york and the moon").shouldBeNull()
    }

    "the model's current_time calls are read for a place after in" {
        WorldTimePhrases.fromUtterance("hey what's the time over in tokyo") shouldBe
            WorldTimeRequest.TimeIn(place("tokyo"))
        WorldTimePhrases.fromUtterance("what time is it in the morning in paris") shouldBe
            WorldTimeRequest.TimeIn(place("paris"))
        WorldTimePhrases.fromUtterance("how far ahead is london") shouldBe
            WorldTimeRequest.Difference(place("london"), null)
        WorldTimePhrases.fromUtterance("what's the time difference between new york and london") shouldBe
            WorldTimeRequest.Difference(place("london"), place("new york"))
        WorldTimePhrases.fromUtterance("what time is it").shouldBeNull()
        // the longest run of words that is a place: Central European Time, not US Central
        WorldTimePhrases.fromUtterance("what time is it in central european time") shouldBe
            WorldTimeRequest.TimeIn(place("central european time"))
        WorldTimePhrases.fromUtterance("what time is it in the morning").shouldBeNull()
        WorldTimePhrases.fromUtterance("is it late").shouldBeNull()
    }

    "the time there, and whether it is another day" {
        val tokyo = WorldTimeOutput(WorldTimeRequest.TimeIn(place("tokyo")), NOW)
        tokyo.placeTime.toLocalDateTime().toString() shouldBe "2026-10-28T01:00"
        tokyo.dayOffset shouldBe 1L
        val london = WorldTimeOutput(WorldTimeRequest.TimeIn(place("london")), NOW)
        london.placeTime.hour shouldBe 16
        london.dayOffset shouldBe 0L
        val utcEvening = ZonedDateTime.of(2026, 10, 27, 1, 0, 0, 0, ZoneId.of("UTC"))
        WorldTimeOutput(WorldTimeRequest.TimeIn(place("los angeles")), utcEvening).dayOffset shouldBe -1L
    }

    "the difference is in minutes, ahead positive" {
        fun minutes(there: String, from: String? = null) = WorldTimeOutput(
            WorldTimeRequest.Difference(place(there), from?.let { place(it) }), NOW,
        ).offsetMinutes
        minutes("london") shouldBe 6 * 60
        // the US changes its clocks a week after the UK
        minutes("london", "new york") shouldBe 4 * 60
        minutes("india") shouldBe 11 * 60 + 30
        minutes("hawaii") shouldBe -4 * 60
        minutes("denver") shouldBe 0
        WorldTimeOutput(WorldTimeRequest.Difference(place("tokyo"), null), NOW)
            .request.shouldBeInstanceOf<WorldTimeRequest.Difference>().from.shouldBeNull()
    }
})
