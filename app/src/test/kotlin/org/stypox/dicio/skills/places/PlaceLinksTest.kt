package org.stypox.dicio.skills.places

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun SharedPlace?.shouldBeAt(lat: Double, lon: Double, name: String?, within: Double = 1e-6) {
    val place = this.shouldNotBeNull()
    place.point.latitude shouldBe (lat plusOrMinus within)
    place.point.longitude shouldBe (lon plusOrMinus within)
    place.name shouldBe name
}

class PlaceLinksTest : StringSpec({
    "ge0 codes, with Organic Maps' own test vectors" {
        // libs/ge0/ge0_tests/parser_tests.cpp
        val (point, name) = Ge0.decode("B4srhdHVVt/Name").shouldNotBeNull()
        point.latitude shouldBe (64.5234 plusOrMinus 1e-4)
        point.longitude shouldBe (12.1234 plusOrMinus 1e-4)
        name shouldBe "Name"
        Ge0.decode("BaF6F6F6F6/Name")!!.first.latitude shouldBe (-20.0 plusOrMinus 1e-4)
        Ge0.decode("BaF6F6F6F6/Name")!!.first.longitude shouldBe (20.0 plusOrMinus 1e-4)
        Ge0.decode("Byqqqqqqqq/Name")!!.first.latitude shouldBe (45.0 plusOrMinus 1e-4)
        Ge0.decode("Byqqqqqqqq/Name")!!.first.longitude shouldBe (0.0 plusOrMinus 1e-4)
        Ge0.decode("Byqqqqqqqq")!!.second.shouldBeNull()
        // too short, a bad character, no slash before the name
        Ge0.decode("Byqqqqqqq").shouldBeNull()
        Ge0.decode("Byqqqq*qqq").shouldBeNull()
        Ge0.decode("ByqqqqqqqqName").shouldBeNull()
        Ge0.name("Eiffel_Tower") shouldBe "Eiffel Tower"
        Ge0.name("a%20b_c") shouldBe "a_b c"
    }

    "Organic Maps' share text: name, address, coordinates, a short link" {
        // libs/map/map_tests/share_tests.cpp
        PlaceLinks.parse(
            "Eiffel Tower\nChamp de Mars, 5 Av. Anatole France\n48.858093, 2.294694\nhttps://omaps.app/w4CNuoc9QN/Eiffel_Tower"
        ).shouldBeAt(48.858093, 2.294694, "Eiffel Tower", within = 1e-4)
        // my position: no name, and "I am here on Organic Maps" isn't one
        PlaceLinks.parse("I am here on Organic Maps\n5 Av. Anatole France\n48.858093, 2.294694\nhttps://omaps.app/04CNuoc9QN")
            .shouldBeAt(48.858093, 2.294694, null, within = 1e-4)
        // the coming clear format, and the om:// and ge0:// schemes
        PlaceLinks.parse("https://omaps.app/48.858093,2.294694/Eiffel_Tower?z=16").shouldBeAt(48.858093, 2.294694, "Eiffel Tower")
        PlaceLinks.parse("ge0://B4srhdHVVt/Name").shouldBeAt(64.5234, 12.1234, "Name", within = 1e-4)
        PlaceLinks.parse("om://map?ll=54.32123,12.34562&n=Point%20Name").shouldBeAt(54.32123, 12.34562, "Point Name")
    }

    "CoMaps' share sentence takes the geo: point and its label" {
        PlaceLinks.parse(
            "Follow this link https://comaps.at/w4CNuoc9QN/Eiffel_Tower or paste these coordinates " +
                "geo:48.8580930,2.2946940?z=16.0&q=48.8580930,2.2946940(Eiffel%20Tower) into your maps app to see this location."
        ).shouldBeAt(48.858093, 2.294694, "Eiffel Tower")
        PlaceLinks.parse("https://comaps.at/w4CNuoc9QN/Eiffel_Tower").shouldBeAt(48.858093, 2.294694, "Eiffel Tower", within = 1e-4)
        PlaceLinks.parse("cm://route?sll=1.5,2.5&saddr=A&dll=40.5,-111.9&daddr=Work&type=vehicle")
            .shouldBeAt(40.5, -111.9, "Work")
    }

    "OsmAnd's share text" {
        PlaceLinks.parse(
            "Springfield Library\n1 Main St, Springfield\n" +
                "Location: geo:40.712776,-74.005974?z=17&q=40.712776,-74.005974(Springfield+Library)\n" +
                "https://osmand.net/map?pin=40.712776,-74.005974#17/40.712776/-74.005974"
        ).shouldBeAt(40.712776, -74.005974, "Springfield Library")
        PlaceLinks.parse("https://osmand.net/map?pin=40.712776,-74.005974#17/40.712776/-74.005974")
            .shouldBeAt(40.712776, -74.005974, null)
        PlaceLinks.parse("https://osmand.net/map#17/40.5/-111.9").shouldBeAt(40.5, -111.9, null)
    }

    "Magic Earth, OpenStreetMap and Google links" {
        PlaceLinks.parse("https://magicearth.com/?show_on_map&lat=48.85837&lon=2.294481&name=Eiffel+Tower&str=Av&city=Paris")
            .shouldBeAt(48.85837, 2.294481, "Eiffel Tower")
        PlaceLinks.parse("https://www.openstreetmap.org/?mlat=40.71278&mlon=-74.00597#map=17/40.71278/-74.00597")
            .shouldBeAt(40.71278, -74.00597, null)
        PlaceLinks.parse("https://www.openstreetmap.org/#map=15/51.5/-0.12").shouldBeAt(51.5, -0.12, null)
        PlaceLinks.parse("https://www.google.com/maps/@40.7,-74.0,15z").shouldBeAt(40.7, -74.0, null)
        PlaceLinks.parse("https://www.google.com/maps/dir/?api=1&destination=40.7%2C-74.0").shouldBeAt(40.7, -74.0, null)
        PlaceLinks.parse("https://maps.google.com/?q=40.7,-74.0").shouldBeAt(40.7, -74.0, null)
        PlaceLinks.parse("geo:40.5,-111.9").shouldBeAt(40.5, -111.9, null)
    }

    "Vela's Google links: a named query has the point, a cid link only names the place" {
        PlaceLinks.parse("https://www.google.com/maps/search/?api=1&query=Connect%20Transit%2041.73201%2C-111.83445")
            .shouldBeAt(41.73201, -111.83445, "Connect Transit")
        PlaceLinks.parse("https://www.google.com/maps/search/?api=1&query=Cafe%20No.%205%2040.5%2C-111.9")
            .shouldBeAt(40.5, -111.9, "Cafe No. 5")
        PlaceLinks.parse("https://www.google.com/maps?cid=12345678901234567890") shouldBe null
        PlaceLinks.namesWithoutPoint("https://www.google.com/maps?cid=12345678901234567890") shouldBe true
        PlaceLinks.namesWithoutPoint("https://maps.app.goo.gl/AbCdEf") shouldBe true
        PlaceLinks.namesWithoutPoint("https://www.google.com/maps/@40.7,-74.0,15z") shouldBe false
        PlaceLinks.namesWithoutPoint("hello") shouldBe false
    }

    "pasted coordinates" {
        PlaceLinks.parse("40.71278, -74.00597").shouldBeAt(40.71278, -74.00597, null)
        PlaceLinks.parse("40.7128° N, 74.0060° W").shouldBeAt(40.7128, -74.006, null)
        PlaceLinks.parse("33.8688 S 151.2093 E").shouldBeAt(-33.8688, 151.2093, null)
    }

    "no point: addresses, short links that need the network, 0,0 and off the globe" {
        for (text in listOf(
            "12 Main St, Springfield", "1 Infinite Loop, Cupertino, CA 95014", "https://maps.app.goo.gl/abc123",
            "https://osm.org/go/0EEQjE--", "geo:0,0?q=coffee", "geo:0,0", "95.1, 10.2", "", "Apt 4.5, 12 Elm",
            "om://search?query=coffee",
        )) {
            withClue(text) { PlaceLinks.parse(text).shouldBeNull() }
        }
    }
})
