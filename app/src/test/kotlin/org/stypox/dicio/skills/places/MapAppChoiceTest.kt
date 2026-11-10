package org.stypox.dicio.skills.places

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.FakeSkillContext
import io.kotest.assertions.withClue
import org.stypox.dicio.skills.navigation.NavigationInfo
import org.stypox.dicio.skills.navigation.NavigationOutput

private val ALL = listOf(COMAPS_APP, MAGIC_APP, ORGANIC_APP, OSMAND_APP, WEB_APP)

private fun routeDeps(maps: MapLauncher, prefs: NavigationPrefs, fix: DeviceFix? = null, locked: Boolean = false) =
    PlacesDeps(
        saved = { object : SavedPlaces {
            override suspend fun get(kind: SavedPlaceKind) = SavedPlace("Home", GeoPoint(40.5, -111.9))
            override suspend fun set(kind: SavedPlaceKind, place: SavedPlace) {}
            override suspend fun forget(kind: SavedPlaceKind) = false
        } },
        location = { object : DeviceLocation {
            override fun access() = LocationAccess.PRECISE
            override suspend fun requestAccess() = LocationAccess.PRECISE
            override fun isEnabled() = true
            override suspend fun current() = fix
            override suspend fun placeName(point: GeoPoint): String? = null
            override fun lastKnown(maxAgeMillis: Long) = fix
        } },
        maps = { maps },
        isLocked = { locked },
        yesNo = { null },
        prefs = { prefs },
        lookup = { FakeLookup() },
        points = { MemoryPointStore() },
        label = { _, kind -> kind.name.lowercase() },
    )

class MapAppChoiceTest : StringSpec({
    "not chosen yet: Android's default, else the only app, else GAR asks and remembers" {
        MapAppChoice.choose("", ALL, null) shouldBe
            NavigateWith.Ask(listOf(ORGANIC_APP, OSMAND_APP, COMAPS_APP, MAGIC_APP, WEB_APP), remember = true)
        // Android's default is a choice the user made
        MapAppChoice.choose("", ALL, COMAPS_APP.pkg) shouldBe NavigateWith.App(COMAPS_APP)
        MapAppChoice.androidDefault(ALL, COMAPS_APP.pkg) shouldBe COMAPS_APP
        MapAppChoice.androidDefault(ALL, "org.gone.maps") shouldBe null
        // nothing to choose between
        MapAppChoice.choose("", listOf(MAGIC_APP), null) shouldBe NavigateWith.App(MAGIC_APP)
        MapAppChoice.choose("", listOf(WEB_APP), null) shouldBe NavigateWith.App(WEB_APP)
        MapAppChoice.choose("", listOf(), null) shouldBe NavigateWith.NoApp
    }

    "a stored Automatic is not chosen yet, so GAR asks once" {
        // "Automatic" was stored as "", what an untouched setting holds
        MapAppChoice.NOT_CHOSEN shouldBe ""
        MapAppChoice.choose("", listOf(OSMAND_APP, COMAPS_APP), null)
            .shouldBeInstanceOf<NavigateWith.Ask>().remember shouldBe true
    }

    "a chosen app, an uninstalled one, and ask every time" {
        MapAppChoice.choose(MAGIC_APP.pkg, ALL, OSMAND_APP.pkg) shouldBe NavigateWith.App(MAGIC_APP)
        // uninstalled since: asked again, or Android's default when one is set
        MapAppChoice.choose("org.gone.maps", ALL, null).shouldBeInstanceOf<NavigateWith.Ask>().remember shouldBe true
        MapAppChoice.choose("org.gone.maps", ALL, OSMAND_APP.pkg) shouldBe NavigateWith.App(OSMAND_APP)
        // ask every time asks even with an Android default, and saves nothing
        MapAppChoice.choose("ask", ALL, OSMAND_APP.pkg) shouldBe
            NavigateWith.Ask(listOf(ORGANIC_APP, OSMAND_APP, COMAPS_APP, MAGIC_APP, WEB_APP), remember = false)
        MapAppChoice.choose("ask", listOf(COMAPS_APP), null) shouldBe NavigateWith.App(COMAPS_APP)
    }

    "package names to kinds; us.spotco.maps is not Organic Maps" {
        MapAppKind.of("net.osmand.plus") shouldBe MapAppKind.OSMAND
        MapAppKind.of("net.osmand") shouldBe MapAppKind.OSMAND
        MapAppKind.of("app.organicmaps") shouldBe MapAppKind.ORGANIC_MAPS
        MapAppKind.of("app.comaps.fdroid") shouldBe MapAppKind.COMAPS
        MapAppKind.of("com.generalmagic.magicearth") shouldBe MapAppKind.MAGIC_EARTH
        MapAppKind.of("us.spotco.maps") shouldBe MapAppKind.GMAPS_WEB
        MapAppKind.of("app.vela") shouldBe MapAppKind.VELA
        MapAppKind.of("app.vela.debug") shouldBe MapAppKind.VELA
        MapAppKind.of("app.velasomething") shouldBe MapAppKind.OTHER
        MapAppKind.of("org.example.maps") shouldBe MapAppKind.OTHER
    }

    "each app's route links" {
        val point = GeoPoint(40.712776, -74.005974)
        val fix = GeoPoint(40.5, -111.9)
        MapLinks.organicMapsNavigate(point) shouldBe "geo:40.71278,-74.00597"
        MapLinks.apiRoute("cm", fix, point, "My location", "Home") shouldBe
            "cm://route?sll=40.5,-111.9&saddr=My%20location&dll=40.71278,-74.00597&daddr=Home&type=vehicle"
        MapLinks.apiRoute("om", fix, point, "", "") shouldBe
            "om://route?sll=40.5,-111.9&saddr=-&dll=40.71278,-74.00597&daddr=-&type=vehicle"
        MapLinks.osmAndSearchRoute("12 Main St, Springfield", fix) shouldBe
            "osmand.api://navigate_search?dest_search_query=12%20Main%20St%2C%20Springfield" +
            "&search_lat=40.5&search_lon=-111.9&profile=car&show_search_results=false"
        MapLinks.magicEarthDaddr("CH1 6BJ United Kingdom") shouldBe "magicearth://?daddr=CH1%206BJ%20United%20Kingdom"
        // Vela: google.navigation: opens its route chooser on the point or on the text's first hit
        MapLinks.osmAndRoute(point) shouldBe "google.navigation:q=40.71278,-74.00597"
        MapLinks.velaTextRoute("12 Main St, Springfield") shouldBe "google.navigation:q=12%20Main%20St%2C%20Springfield"
        MapLinks.webDirections("40.5,-111.9") shouldBe
            "https://www.google.com/maps/dir/?api=1&destination=40.5%2C-111.9&travelmode=driving"
    }

    "a saved point goes to the chosen app's route, never a search" {
        val ctx = FakeSkillContext()
        val home = SavedPlace(null, GeoPoint(1.0, 2.0))
        for ((app, expected) in listOf(
            OSMAND_APP to MapResult.ROUTE, ORGANIC_APP to MapResult.ROUTE, MAGIC_APP to MapResult.SHOWN,
        )) {
            val maps = RecordingMaps(listOf(app))
            PlacesFlow(NavigationInfo, routeDeps(maps, FakePrefs())).navigate(ctx, SavedPlaceKind.HOME, home)
                .shouldBeInstanceOf<NavigateSavedOutput>().result shouldBe expected
            maps.calls shouldBe listOf("route 1,2 home ${app.pkg}")
        }
    }

    "CoMaps draws the route only from a fresh precise fix" {
        val ctx = FakeSkillContext()
        val home = SavedPlace(null, GeoPoint(1.0, 2.0))
        val precise = DeviceFix(GeoPoint(3.0, 4.0), 5f, true)
        val maps = RecordingMaps(listOf(COMAPS_APP))
        PlacesFlow(NavigationInfo, routeDeps(maps, FakePrefs(), fix = precise)).navigate(ctx, SavedPlaceKind.HOME, home)
            .shouldBeInstanceOf<NavigateSavedOutput>().result shouldBe MapResult.ROUTE_DRAWN
        maps.calls shouldBe listOf("route 1,2 home app.comaps.fdroid from 3,4")

        val rough = RecordingMaps(listOf(COMAPS_APP))
        val prefs = FakePrefs()
        val pin = PlacesFlow(NavigationInfo, routeDeps(rough, prefs, fix = precise.copy(precise = false)))
            .navigate(ctx, SavedPlaceKind.HOME, home).shouldBeInstanceOf<NavigateSavedOutput>()
        pin.result shouldBe MapResult.SHOWN
        rough.calls shouldBe listOf("route 1,2 home app.comaps.fdroid")
        // "Tap Route." is said the first time only
        pin.done.sayTapRoute shouldBe true
        PlacesFlow(NavigationInfo, routeDeps(rough, prefs)).navigate(ctx, SavedPlaceKind.HOME, home)
            .shouldBeInstanceOf<NavigateSavedOutput>().done.sayTapRoute shouldBe false
    }

    "ask every time asks which app, the pick gets its own route intent, nothing is saved" {
        val ctx = FakeSkillContext()
        val maps = RecordingMaps(listOf(COMAPS_APP, OSMAND_APP))
        val prefs = FakePrefs(app = "ask")
        val pick = PlacesFlow(NavigationInfo, routeDeps(maps, prefs))
            .navigate(ctx, SavedPlaceKind.WORK, SavedPlace(null, GeoPoint(1.0, 2.0)))
            .shouldBeInstanceOf<MapAppPickOutput>()
        pick.apps shouldBe listOf(OSMAND_APP, COMAPS_APP)
        pick.remember shouldBe false
        pick.pick("osmand") shouldBe OSMAND_APP
        pick.pick("the second one") shouldBe COMAPS_APP
        maps.calls shouldBe listOf()
        pick.choose(ctx, OSMAND_APP).shouldBeInstanceOf<NavigateSavedOutput>().result shouldBe MapResult.ROUTE
        maps.calls shouldBe listOf("route 1,2 work net.osmand.plus")
        prefs.app shouldBe "ask"
    }

    "the first time GAR asks, Always saves the pick, Just once doesn't" {
        val ctx = FakeSkillContext()
        val home = SavedPlace(null, GeoPoint(1.0, 2.0))
        val maps = RecordingMaps(listOf(COMAPS_APP, VELA_APP, OSMAND_APP))
        val prefs = FakePrefs()
        val first = PlacesFlow(NavigationInfo, routeDeps(maps, prefs)).navigate(ctx, SavedPlaceKind.HOME, home)
            .shouldBeInstanceOf<MapAppPickOutput>()
        first.remember shouldBe true
        first.always shouldBe true
        first.apps.map { it.label } shouldBe listOf("OsmAnd", "Vela", "CoMaps")
        // Always turned off: this once
        first.always = false
        first.choose(ctx, VELA_APP).shouldBeInstanceOf<NavigateSavedOutput>().result shouldBe MapResult.ROUTE
        maps.calls shouldBe listOf("route 1,2 home app.vela")
        prefs.app shouldBe ""
        // asked again; a spoken answer with Always left on saves, and says where to change it
        val second = PlacesFlow(NavigationInfo, routeDeps(maps, prefs)).navigate(ctx, SavedPlaceKind.HOME, home)
            .shouldBeInstanceOf<MapAppPickOutput>()
        val saved = second.choose(ctx, second.pick("vela")!!).shouldBeInstanceOf<MapAppSavedOutput>()
        saved.output.shouldBeInstanceOf<NavigateSavedOutput>()
        prefs.app shouldBe "app.vela"
        // then never again
        PlacesFlow(NavigationInfo, routeDeps(maps, prefs)).navigate(ctx, SavedPlaceKind.HOME, home)
            .shouldBeInstanceOf<NavigateSavedOutput>()
        maps.calls.last() shouldBe "route 1,2 home app.vela"
        // and once Vela is gone, GAR asks again
        val gone = RecordingMaps(listOf(COMAPS_APP, OSMAND_APP))
        PlacesFlow(NavigationInfo, routeDeps(gone, prefs)).navigate(ctx, SavedPlaceKind.HOME, home)
            .shouldBeInstanceOf<MapAppPickOutput>().remember shouldBe true
    }

    "a tap with Always on saves it" {
        val ctx = FakeSkillContext()
        val prefs = FakePrefs()
        val pick = PlacesFlow(NavigationInfo, routeDeps(RecordingMaps(listOf(COMAPS_APP, OSMAND_APP)), prefs))
            .navigate(ctx, SavedPlaceKind.HOME, SavedPlace(null, GeoPoint(1.0, 2.0)))
            .shouldBeInstanceOf<MapAppPickOutput>()
        pick.pick(COMAPS_APP.label) shouldBe COMAPS_APP
        pick.choose(ctx, COMAPS_APP).shouldBeInstanceOf<MapAppSavedOutput>()
        prefs.app shouldBe COMAPS_APP.pkg
    }

    "a spoken answer after turning Always off saves nothing, so no Saved note" {
        val ctx = FakeSkillContext()
        val prefs = FakePrefs()
        val pick = PlacesFlow(NavigationInfo, routeDeps(RecordingMaps(listOf(COMAPS_APP, OSMAND_APP)), prefs))
            .navigate(ctx, SavedPlaceKind.HOME, SavedPlace(null, GeoPoint(1.0, 2.0)))
            .shouldBeInstanceOf<MapAppPickOutput>()
        pick.always = false
        pick.choose(ctx, pick.pick("comaps")!!).shouldBeInstanceOf<NavigateSavedOutput>()
        prefs.app shouldBe ""
    }

    "maps apps by spoken name" {
        val pick = MapAppPickOutput(NavigationInfo, listOf(OSMAND_APP, VELA_APP, COMAPS_APP, MAGIC_APP, ORGANIC_APP),
            remember = true, save = { _, _ -> }, onPick = { _, _ -> error("not picked") })
        for ((said, app) in listOf(
            "OsmAnd" to OSMAND_APP, "osm and" to OSMAND_APP, "os mand" to OSMAND_APP,
            "Vela" to VELA_APP, "vella" to VELA_APP, "use vela" to VELA_APP,
            "CoMaps" to COMAPS_APP, "co maps" to COMAPS_APP, "with comaps please" to COMAPS_APP,
            "Magic Earth" to MAGIC_APP, "magic earth" to MAGIC_APP,
            "organic maps" to ORGANIC_APP, "the third one" to COMAPS_APP,
        )) {
            withClue(said) { pick.pick(said) shouldBe app }
        }
        for (said in listOf("cancel", "", "google", "maps")) {
            withClue(said) { pick.pick(said) shouldBe null }
        }
    }

    "a search and nearby ask too, then go to the picked app" {
        val ctx = FakeSkillContext()
        val maps = RecordingMaps(listOf(OSMAND_APP, VELA_APP))
        val prefs = FakePrefs()
        val nearby = NavigationPlaces.output(ctx, NavigationInfo, "coffee near me", routeDeps(maps, prefs))
            .shouldBeInstanceOf<MapAppPickOutput>()
        nearby.choose(ctx, VELA_APP).shouldBeInstanceOf<MapAppSavedOutput>()
            .output.shouldBeInstanceOf<NearbyOutput>().result shouldBe MapResult.SHOWN
        maps.calls shouldBe listOf("search coffee app.vela")
        NavigationPlaces.search(ctx, NavigationInfo, "the airport", routeDeps(maps, prefs))
            .shouldBeInstanceOf<NavigationOutput>()
        maps.calls.last() shouldBe "search the airport app.vela"
        // Android's default without asking
        val withDefault = RecordingMaps(listOf(OSMAND_APP, VELA_APP), default = OSMAND_APP.pkg)
        NavigationPlaces.search(ctx, NavigationInfo, "the airport", routeDeps(withDefault, FakePrefs()))
            .shouldBeInstanceOf<NavigationOutput>()
        withDefault.calls shouldBe listOf("search the airport net.osmand.plus")
    }

    "Vela routes to a contact's text address itself" {
        val ctx = FakeSkillContext()
        val maps = RecordingMaps(listOf(VELA_APP))
        PlacesFlow(NavigationInfo, routeDeps(maps, FakePrefs()))
            .navigate(ctx, SavedPlaceKind.WORK, SavedPlace("1 Infinite Loop", null))
            .shouldBeInstanceOf<NavigateSavedOutput>().result shouldBe MapResult.ROUTE
        maps.calls shouldBe listOf("text 1 Infinite Loop app.vela")
    }

    "over the lock screen GAR still asks (the card lists apps only), the route card stays locked" {
        val ctx = FakeSkillContext()
        val pick = PlacesFlow(NavigationInfo, routeDeps(RecordingMaps(listOf(OSMAND_APP, VELA_APP)), FakePrefs(), locked = true))
            .navigate(ctx, SavedPlaceKind.HOME, SavedPlace("12 Main St", GeoPoint(1.0, 2.0)))
            .shouldBeInstanceOf<MapAppPickOutput>()
        pick.apps shouldBe listOf(OSMAND_APP, VELA_APP)
        pick.choose(ctx, OSMAND_APP).shouldBeInstanceOf<MapAppSavedOutput>()
            .output.shouldBeInstanceOf<NavigateSavedOutput>().locked shouldBe true
    }

    "a locked phone still starts the route, and the card knows it is locked" {
        val maps = RecordingMaps(listOf(OSMAND_APP))
        val output = PlacesFlow(NavigationInfo, routeDeps(maps, FakePrefs(), locked = true))
            .navigate(FakeSkillContext(), SavedPlaceKind.HOME, SavedPlace("12 Main St", GeoPoint(1.0, 2.0)))
            .shouldBeInstanceOf<NavigateSavedOutput>()
        output.locked shouldBe true
        output.result shouldBe MapResult.ROUTE
    }

    "no maps app" {
        PlacesFlow(NavigationInfo, routeDeps(RecordingMaps(listOf()), FakePrefs()))
            .navigate(FakeSkillContext(), SavedPlaceKind.HOME, SavedPlace(null, GeoPoint(1.0, 2.0)))
            .shouldBeInstanceOf<NavigateSavedOutput>().result shouldBe MapResult.NO_APP
    }
})
