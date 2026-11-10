package org.stypox.dicio.skills.places

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarCancelledOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.garRankingWinner
import org.stypox.dicio.gar.offer.GarOfferOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.navigation.NavigationInfo
import org.stypox.dicio.skills.navigation.NavigationSkill

private class FakeSavedPlaces(val places: MutableMap<SavedPlaceKind, SavedPlace> = mutableMapOf()) : SavedPlaces {
    override suspend fun get(kind: SavedPlaceKind) = places[kind]
    override suspend fun set(kind: SavedPlaceKind, place: SavedPlace) {
        places[kind] = place
    }
    override suspend fun forget(kind: SavedPlaceKind) = places.remove(kind) != null
}

private class FakeLocation(
    var access: LocationAccess = LocationAccess.PRECISE,
    val grantOnRequest: LocationAccess = LocationAccess.NONE,
    val enabled: Boolean = true,
    val fix: DeviceFix? = DeviceFix(GeoPoint(40.712776, -74.005974), 12f, true),
    val name: String? = null,
) : DeviceLocation {
    var requested = 0
    var namedAt: GeoPoint? = null
    override fun access() = access
    override suspend fun requestAccess(): LocationAccess {
        requested++
        access = grantOnRequest
        return access
    }
    override fun isEnabled() = enabled
    override suspend fun current() = fix
    override suspend fun placeName(point: GeoPoint): String? {
        namedAt = point
        return name
    }
}

private class FakeMaps(val routes: Boolean = false) : MapLauncher {
    val calls = mutableListOf<String>()
    override fun apps() = listOf(if (routes) OSMAND_APP else MAGIC_APP)
    override fun defaultApp(): String? = null
    override fun route(app: MapApp, point: GeoPoint, label: String, fix: GeoPoint?): MapResult {
        calls += "route ${MapLinks.coordinates(point)} $label"
        return if (routes) MapResult.ROUTE else MapResult.SHOWN
    }
    override fun routeText(app: MapApp, address: String, near: GeoPoint?): MapResult? = null
    override fun search(query: String, app: MapApp?): MapResult {
        calls += "search $query"
        return MapResult.SHOWN
    }
    override fun show(point: GeoPoint, label: String, app: MapApp?): MapResult {
        calls += "show ${MapLinks.coordinates(point)}"
        return MapResult.SHOWN
    }
}

private fun deps(
    saved: SavedPlaces = FakeSavedPlaces(),
    location: DeviceLocation = FakeLocation(),
    maps: MapLauncher = FakeMaps(),
    locked: Boolean = false,
) = PlacesDeps(
    saved = { saved },
    location = { location },
    maps = { maps },
    pages = { object : PlacePages {
        override fun openAppInfo() = true
        override fun openLocationSettings() = true
    } },
    isLocked = { locked },
    yesNo = { null },
    card = { object : CardPlaces {
        override fun hasAccess() = false
        override suspend fun addresses(kind: SavedPlaceKind) = listOf<String>()
    } },
    prefs = { FakePrefs() },
    lookup = { FakeLookup() },
    points = { MemoryPointStore() },
    label = { _, kind -> kind.name },
)

class PlacesTest : StringSpec({
    "home and work as people say them" {
        for ((said, kind) in listOf(
            "home" to SavedPlaceKind.HOME, "my home" to SavedPlaceKind.HOME,
            "back home" to SavedPlaceKind.HOME, "my house" to SavedPlaceKind.HOME,
            "work" to SavedPlaceKind.WORK, "the office" to SavedPlaceKind.WORK,
            "my job" to SavedPlaceKind.WORK,
        )) {
            withClue(said) { PlaceWords.savedPlace(said) shouldBe kind }
        }
        for (said in listOf("home depot", "work boots", "the train station", "", null)) {
            withClue(said) { PlaceWords.savedPlace(said).shouldBeNull() }
        }
    }

    "nearby searches lose their near me" {
        for ((said, thing) in listOf(
            "coffee near me" to "coffee", "the nearest pharmacy" to "pharmacy",
            "is there a gas station around here" to "gas station",
            "closest gas station" to "gas station", "a nearby atm" to "atm",
            "pizza places nearby" to "pizza places",
        )) {
            withClue(said) { PlaceWords.nearbyThing(said) shouldBe thing }
        }
        for (said in listOf("the weather nearby", "what's open nearby", "train station", "my phone nearby")) {
            withClue(said) { PlaceWords.nearbyThing(said).shouldBeNull() }
        }
    }

    "how do I get and get me lead to a place only with to, home or work" {
        for ((input, where) in listOf(
            "how do i get rid of ants" to "rid of ants", "get me a coffee" to "a coffee",
            "drive me crazy" to "crazy",
            // 2026-11-02: questions about when or whether, and things no map has
            "when should i go to bed" to "bed", "why do i go to work" to "work",
            "what time should i get to the airport" to "the airport",
            "should i go to the store" to "the store", "take me to bed" to "bed",
            "how do i get to sleep" to "sleep", "where is the bathroom" to "the bathroom",
            "how do i go to the bathroom" to "the bathroom", "get me to bed early" to "bed early",
        )) {
            withClue(input) { PlaceWords.isNoPlace(input, where) shouldBe true }
        }
        for ((input, where) in listOf(
            "how do i get home" to "home", "how do i get to the station" to "station",
            "get me home" to "home", "drive me to the airport" to "airport",
            "take me home" to "home", "navigate to work" to "work",
            "how do i get to the airport" to "the airport", "directions to costco" to "costco",
            "where is the nearest pharmacy" to "the nearest pharmacy",
            "where is the nearest restroom" to "the nearest restroom",
            "take me to bed bath and beyond" to "bed bath and beyond",
        )) {
            withClue(input) { PlaceWords.isNoPlace(input, where) shouldBe false }
        }
    }

    "answers to what's your address" {
        for (said in listOf("here", "use my current location", "where I am", "right here", "this place")) {
            withClue(said) { PlaceAnswers.isHere(said) shouldBe true }
        }
        for (said in listOf("12 main street", "it's 4 elm road", "the corner of 5th and main")) {
            withClue(said) { PlaceAnswers.isHere(said) shouldBe false }
        }
        PlaceAnswers.address("it's 12 Main Street.") shouldBe "12 Main Street"
        PlaceAnswers.address("my address is 4 Elm Road") shouldBe "4 Elm Road"
        PlaceAnswers.address("I live at 9 Oak Lane") shouldBe "9 Oak Lane"
        PlaceAnswers.address("  ").shouldBeNull()
    }

    "map links" {
        val point = GeoPoint(40.712776, -74.005974)
        MapLinks.coordinates(point) shouldBe "40.71278,-74.00597"
        MapLinks.pin(point, "Home") shouldBe "geo:40.71278,-74.00597?q=40.71278,-74.00597(Home)"
        MapLinks.osmAndRoute(point) shouldBe "google.navigation:q=40.71278,-74.00597"
        MapLinks.magicEarthRoute(point) shouldBe "magicearth://?drive_to&lat=40.71278&lon=-74.00597"
        MapLinks.osmLink(point) shouldBe
            "https://www.openstreetmap.org/?mlat=40.71278&mlon=-74.00597#map=17/40.71278/-74.00597"
        MapLinks.search("coffee") shouldBe "geo:0,0?q=coffee"
        // a & or # in the query is no new parameter or fragment
        MapLinks.search("AT&T store") shouldBe "geo:0,0?q=AT%26T%20store"
        MapLinks.search("Barnes & Noble #2") shouldBe "geo:0,0?q=Barnes%20%26%20Noble%20%232"
        MapLinks.pin(point, "You are (here)") shouldBe "geo:40.71278,-74.00597?q=40.71278,-74.00597(You%20are%20here)"
        MapLinks.format(10.0) shouldBe "10"
    }

    "finding the phone asks for access once, and names it with the point rounded to 1 km" {
        val none = FakeLocation(access = LocationAccess.NONE)
        LocationLookup.locate(none, withName = true) shouldBe LocationResult.NoAccess
        none.requested shouldBe 1

        val approximate = FakeLocation(access = LocationAccess.NONE, grantOnRequest = LocationAccess.APPROXIMATE)
        LocationLookup.locate(approximate, withName = false).shouldBeInstanceOf<LocationResult.Found>()

        LocationLookup.locate(FakeLocation(enabled = false), withName = true) shouldBe LocationResult.Off
        LocationLookup.locate(FakeLocation(fix = null), withName = true) shouldBe LocationResult.NoFix

        val named = FakeLocation(name = "12 Main Street, Springfield")
        val found = LocationLookup.locate(named, withName = true).shouldBeInstanceOf<LocationResult.Found>()
        found.name shouldBe "12 Main Street, Springfield"
        named.namedAt shouldBe GeoPoint(40.71, -74.01)
        named.requested shouldBe 0
    }

    "going home: there when saved, asked once when not" {
        val ctx = FakeSkillContext()
        val maps = FakeMaps(routes = true)
        val saved = FakeSavedPlaces(mutableMapOf(SavedPlaceKind.HOME to SavedPlace(null, GeoPoint(1.0, 2.0))))
        val flow = PlacesFlow(NavigationInfo, deps(saved = saved, maps = maps))
        flow.navigateTo(ctx, SavedPlaceKind.HOME).shouldBeInstanceOf<NavigateSavedOutput>().result shouldBe MapResult.ROUTE
        maps.calls shouldBe listOf("route 1,2 HOME")

        val ask = flow.navigateTo(ctx, SavedPlaceKind.WORK).shouldBeInstanceOf<SavedPlaceAskOutput>()
        ask.purpose shouldBe SavedPlacePurpose.NAVIGATE

        // an address is saved and searched for in the maps app
        val answer = flow.save(ctx, SavedPlaceKind.WORK, "it's 1 Infinite Loop", SavedPlacePurpose.NAVIGATE, nested = true)
            .shouldBeInstanceOf<SavedPlaceOutput>()
        answer.then.shouldBeInstanceOf<NavigateSavedOutput>()
        saved.places[SavedPlaceKind.WORK] shouldBe SavedPlace("1 Infinite Loop", null)
        maps.calls.last() shouldBe "search 1 Infinite Loop"

        // a spoken house number is kept as digits, which offline maps search finds
        flow.save(ctx, SavedPlaceKind.HOME, "one hundred twenty three main street", SavedPlacePurpose.NAVIGATE, nested = true)
            .shouldBeInstanceOf<SavedPlaceOutput>()
        saved.places[SavedPlaceKind.HOME] shouldBe SavedPlace("123 main street", null)
        maps.calls.last() shouldBe "search 123 main street"
    }

    "here saves where the phone is, without going there" {
        val ctx = FakeSkillContext()
        val saved = FakeSavedPlaces()
        val maps = FakeMaps()
        val flow = PlacesFlow(NavigationInfo, deps(saved = saved, maps = maps))
        val output = flow.save(ctx, SavedPlaceKind.HOME, "use my current location", SavedPlacePurpose.NAVIGATE, nested = true)
            .shouldBeInstanceOf<SavedPlaceOutput>()
        output.then.shouldBeNull()
        saved.places[SavedPlaceKind.HOME] shouldBe SavedPlace(null, GeoPoint(40.712776, -74.005974))
        maps.calls shouldBe listOf()
    }

    "with only approximate access, here saves nothing and offers GAR's app info" {
        val ctx = FakeSkillContext()
        val saved = FakeSavedPlaces()
        val rough = FakeLocation(LocationAccess.APPROXIMATE, fix = DeviceFix(GeoPoint(40.7, -74.0), 2000f, false))
        PlacesFlow(NavigationInfo, deps(saved = saved, location = rough))
            .save(ctx, SavedPlaceKind.HOME, "here", SavedPlacePurpose.SAVE, nested = false)
            .shouldBeInstanceOf<GarOfferOutput>()
        saved.places[SavedPlaceKind.HOME].shouldBeNull()
    }

    "without access, here offers GAR's app info; cancel and a locked phone stop" {
        val ctx = FakeSkillContext()
        val flow = PlacesFlow(NavigationInfo, deps(location = FakeLocation(access = LocationAccess.NONE)))
        flow.save(ctx, SavedPlaceKind.HOME, "here", SavedPlacePurpose.SAVE, nested = false)
            .shouldBeInstanceOf<GarOfferOutput>()
        flow.save(ctx, SavedPlaceKind.HOME, "never mind", SavedPlacePurpose.SAVE, nested = false)
            .shouldBeInstanceOf<GarCancelledOutput>()
        PlacesFlow(NavigationInfo, deps(locked = true))
            .save(ctx, SavedPlaceKind.HOME, "12 main street", SavedPlacePurpose.SAVE, nested = false)
            .shouldBeInstanceOf<GarLockedOutput>()
    }

    "the navigation skill hands home, work, nearby and websites to GAR" {
        NavigationPlaces.target("home") shouldBe NavigationPlaces.Target.Saved(SavedPlaceKind.HOME)
        NavigationPlaces.target("my work") shouldBe NavigationPlaces.Target.Saved(SavedPlaceKind.WORK)
        NavigationPlaces.target("coffee near me") shouldBe NavigationPlaces.Target.Nearby("coffee")
        NavigationPlaces.target("the nearest pharmacy") shouldBe NavigationPlaces.Target.Nearby("pharmacy")
        NavigationPlaces.target("reddit dot com").shouldBeInstanceOf<NavigationPlaces.Target.Website>()
        NavigationPlaces.target("the train station").shouldBeNull()
        NavigationPlaces.target("home depot").shouldBeNull()

        val maps = FakeMaps()
        NavigationPlaces.output(FakeSkillContext(), NavigationInfo, "coffee near me", deps(maps = maps))
            .shouldBeInstanceOf<NearbyOutput>().thing shouldBe "coffee"
        maps.calls shouldBe listOf("search coffee")
    }

    "the navigation sentences drop how do I get rid of ants" {
        val navigation = NavigationSkill(NavigationInfo, Sentences.Navigation["en"]!!)
        for (input in listOf("how do i get rid of ants", "get me a coffee", "drive me crazy")) {
            withClue(input) {
                navigation.score(FakeSkillContext().forInput(input), input).first shouldBe AlwaysWorstScore
            }
        }
        for (input in listOf("how do i get home", "how do i get to the airport", "get me home", "take me home")) {
            withClue(input) {
                navigation.score(FakeSkillContext().forInput(input), input).first shouldNotBe AlwaysWorstScore
            }
        }
    }

    "share sentences need the location named" {
        ShareLocationWords.shares("share my location with mom", "mom") shouldBe true
        ShareLocationWords.shares("tell dad where i am", "dad") shouldBe true
        ShareLocationWords.shares("text mom my location", "mom my location") shouldBe false
        ShareLocationWords.shares("text mom hello", "mom") shouldBe false
    }

    "places rank to their skills" {
        for ((input, skill) in listOf(
            "where am i" to PlacesInfo.id,
            "what's my current location" to PlacesInfo.id,
            "find coffee near me" to PlacesInfo.id,
            "gas stations nearby" to PlacesInfo.id,
            "is there a pharmacy near me" to PlacesInfo.id,
            "set my home address to 12 main street" to PlacesInfo.id,
            "set this place as work" to PlacesInfo.id,
            "what's my home address" to PlacesInfo.id,
            "forget my work address" to PlacesInfo.id,
            "where do i live" to PlacesInfo.id,
            "share my location with mom" to ShareLocationInfo.id,
            "send dad my location" to ShareLocationInfo.id,
            "let mom know where i am" to ShareLocationInfo.id,
            "take me home" to NavigationInfo.id,
            "directions to work" to NavigationInfo.id,
            "how do i get home" to NavigationInfo.id,
            "where is the nearest gas station" to NavigationInfo.id,
            "take me to the train station" to NavigationInfo.id,
            "text mom" to "sms",
            "text mom i'm late" to "sms",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe skill }
        }
        for (input in listOf("how do i get rid of ants", "what's the weather nearby", "drive me crazy")) {
            withClue(input) {
                garRankingWinner(input).first shouldNotBe NavigationInfo.id
                garRankingWinner(input).first shouldNotBe PlacesInfo.id
            }
        }
    }
})
