package org.stypox.dicio.skills.places

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.offer.GarOfferOutput
import org.stypox.dicio.gar.online.GarHttp
import org.stypox.dicio.gar.online.GarHttpStatusException
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.skills.navigation.NavigationInfo
import java.io.IOException

private const val FOUND = """[{"place_id":1,"lat":"40.7127281","lon":"-74.0060152","display_name":"New York"}]"""
private const val DAY = 24L * 60 * 60 * 1000

/** A clock that only [sleep] moves, and the requests made at each time. */
private class FakeTime(var now: Long = 1_000_000L) {
    val sleeps = mutableListOf<Long>()
    fun gate() = RateGate(1000L, { now }) { sleeps += it; now += it }
}

private class FakeHttp(var answer: () -> String? = { FOUND }) : GarHttp {
    val urls = mutableListOf<String>()
    override suspend fun get(url: String): String? {
        urls += url
        return answer()
    }
}

private class PlaceStore(var place: SavedPlace?) : SavedPlaces {
    override suspend fun get(kind: SavedPlaceKind) = place
    override suspend fun set(kind: SavedPlaceKind, place: SavedPlace) {
        this.place = place
    }
    override suspend fun forget(kind: SavedPlaceKind) = false
}

private fun lookupDeps(
    saved: SavedPlaces,
    maps: MapLauncher,
    prefs: NavigationPrefs,
    lookup: AddressLookup,
    points: AddressPointStore = MemoryPointStore(),
    locked: Boolean = false,
) = PlacesDeps(
    saved = { saved },
    location = { object : DeviceLocation {
        override fun access() = LocationAccess.NONE
        override suspend fun requestAccess() = LocationAccess.NONE
        override fun isEnabled() = true
        override suspend fun current(): DeviceFix? = null
        override suspend fun placeName(point: GeoPoint): String? = null
    } },
    maps = { maps },
    isLocked = { locked },
    yesNo = { null },
    prefs = { prefs },
    lookup = { lookup },
    points = { points },
    label = { _, kind -> kind.name },
)

class AddressPointsTest : StringSpec({
    "the request names the address once, encoded, with the language" {
        NominatimLookup.url("12 Main St & 5th, Springfield", "en") shouldBe
            "https://nominatim.openstreetmap.org/search?q=12%20Main%20St%20%26%205th%2C%20Springfield" +
            "&format=jsonv2&limit=1&accept-language=en"
        NominatimLookup.parse(FOUND) shouldBe GeoPoint(40.7127281, -74.0060152)
        NominatimLookup.parse("[]").shouldBeNull()
        AddressPointStore.key("  12 Main  St,\nSpringfield. ") shouldBe "12 main st, springfield"
    }

    "found once, then from the cache: the same address is never asked twice" {
        val time = FakeTime()
        val http = FakeHttp()
        val store = MemoryPointStore()
        val lookup = NominatimLookup(http, { time.now }, store, time.gate())
        lookup.find("New York") shouldBe LookupResult.Found(GeoPoint(40.7127281, -74.0060152))
        lookup.find("new york.") shouldBe LookupResult.Found(GeoPoint(40.7127281, -74.0060152))
        http.urls shouldHaveSize 1
        store.get("New York")!!.point shouldBe GeoPoint(40.7127281, -74.0060152)
    }

    "at most one request a second, process-wide through the gate" {
        val time = FakeTime()
        val http = FakeHttp()
        val gate = time.gate()
        val lookup = NominatimLookup(http, { time.now }, MemoryPointStore(), gate)
        lookup.find("a")
        time.now += 300
        lookup.find("b")
        time.sleeps shouldBe listOf(700L)
        time.now += 5000
        NominatimLookup(http, { time.now }, MemoryPointStore(), gate).find("c")
        time.sleeps shouldBe listOf(700L)
        http.urls shouldHaveSize 3
    }

    "not found is kept 30 days" {
        val time = FakeTime()
        val http = FakeHttp { "[]" }
        val lookup = NominatimLookup(http, { time.now }, MemoryPointStore(), time.gate())
        lookup.find("Nowhere 1") shouldBe LookupResult.NotFound
        time.now += 29 * DAY
        lookup.find("Nowhere 1") shouldBe LookupResult.NotFound
        http.urls shouldHaveSize 1
        time.now += 2 * DAY
        lookup.find("Nowhere 1") shouldBe LookupResult.NotFound
        http.urls shouldHaveSize 2
    }

    "403, 429 and 509 pause lookups for a day; other errors and bad answers just fail" {
        for (code in listOf(403, 429, 509)) {
            val time = FakeTime()
            val http = FakeHttp { throw GarHttpStatusException(code, "u") }
            val store = MemoryPointStore()
            val lookup = NominatimLookup(http, { time.now }, store, time.gate())
            lookup.find("a") shouldBe LookupResult.Paused
            store.paused shouldBe time.now + DAY
            http.answer = { FOUND }
            time.now += DAY - 1
            lookup.find("b") shouldBe LookupResult.Paused
            http.urls shouldHaveSize 1
            time.now += 2
            lookup.find("b").shouldBeInstanceOf<LookupResult.Found>()
        }
        val time = FakeTime()
        for (answer in listOf<() -> String?>(
            { throw GarHttpStatusException(500, "u") }, { throw IOException("offline") }, { null },
            { "<html>" }, { "{}" }, { """[{"lat":"x"}]""" },
        )) {
            val store = MemoryPointStore()
            NominatimLookup(FakeHttp(answer), { time.now }, store, time.gate()).find("a") shouldBe LookupResult.Failed
            store.paused shouldBe 0L
            store.points shouldBe mapOf()
        }
    }

    "the settings cache keeps the newest 100" {
        val store = GarSettingsAddressPoints(GarSettingsModule.newDataStoreForPreviews())
        for (i in 1..105) {
            store.put("$i Main St", if (i % 2 == 0) GeoPoint(1.0, i.toDouble()) else null, i.toLong())
        }
        store.get("1 Main St").shouldBeNull()
        store.get("5 Main St").shouldBeNull()
        store.get("6 main st") shouldBe CachedPoint(GeoPoint(1.0, 6.0), 6L)
        store.get("105 Main St") shouldBe CachedPoint(null, 105L)
        store.pause(42L)
        store.pausedUntil() shouldBe 42L
    }

    "a saved address: asked once; yes looks it up, keeps the point with the place and routes" {
        val ctx = FakeSkillContext()
        val place = SavedPlace("1 Infinite Loop", null)
        val saved = PlaceStore(place)
        val maps = RecordingMaps(listOf(ORGANIC_APP))
        val prefs = FakePrefs(asked = false)
        val lookup = FakeLookup(LookupResult.Found(GeoPoint(37.33, -122.03)))
        val flow = PlacesFlow(NavigationInfo, lookupDeps(saved, maps, prefs, lookup))
        val offer = flow.navigate(ctx, SavedPlaceKind.WORK, place).shouldBeInstanceOf<GarOfferOutput>()
        prefs.asked shouldBe true
        lookup.asked shouldBe listOf()

        val done = offer.onYes(ctx).shouldBeInstanceOf<NavigateSavedOutput>()
        prefs.lookup shouldBe true
        done.result shouldBe MapResult.ROUTE
        done.done.lookedUp shouldBe true
        lookup.asked shouldBe listOf("1 Infinite Loop")
        maps.calls shouldBe listOf("route 37.33,-122.03 WORK app.organicmaps")
        saved.place shouldBe SavedPlace("1 Infinite Loop", GeoPoint(37.33, -122.03), lookedUp = true)

        // next time the point is there: no lookup
        flow.navigate(ctx, SavedPlaceKind.WORK, saved.place!!)
        lookup.asked shouldHaveSize 1
    }

    "no to the question searches, and isn't asked again; a locked phone isn't asked" {
        val ctx = FakeSkillContext()
        val place = SavedPlace("1 Infinite Loop", null)
        val maps = RecordingMaps(listOf(ORGANIC_APP))
        val prefs = FakePrefs(asked = false)
        val lookup = FakeLookup()
        val flow = PlacesFlow(NavigationInfo, lookupDeps(PlaceStore(place), maps, prefs, lookup))
        val offer = flow.navigate(ctx, SavedPlaceKind.WORK, place).shouldBeInstanceOf<GarOfferOutput>()
        offer.onNo!!(ctx).shouldBeInstanceOf<NavigateSavedOutput>().done.searched shouldBe true
        maps.calls shouldBe listOf("search 1 Infinite Loop app.organicmaps")
        prefs.lookup shouldBe false
        flow.navigate(ctx, SavedPlaceKind.WORK, place).shouldBeInstanceOf<NavigateSavedOutput>()
        lookup.asked shouldBe listOf()

        val lockedPrefs = FakePrefs(asked = false)
        val locked = PlacesFlow(NavigationInfo, lookupDeps(PlaceStore(place), maps, lockedPrefs, lookup, locked = true))
            .navigate(ctx, SavedPlaceKind.WORK, place).shouldBeInstanceOf<NavigateSavedOutput>()
        locked.locked shouldBe true
        locked.done.searched shouldBe true
        lockedPrefs.asked shouldBe false
    }

    "with the setting on: not found and no connection search instead, with why" {
        val ctx = FakeSkillContext()
        val place = SavedPlace("Nowhere 1", null)
        for ((answer, problem) in listOf(
            LookupResult.NotFound to LookupProblem.NOT_FOUND, LookupResult.Failed to LookupProblem.FAILED,
            LookupResult.Paused to null,
        )) {
            val maps = RecordingMaps(listOf(COMAPS_APP))
            val done = PlacesFlow(NavigationInfo, lookupDeps(PlaceStore(place), maps, FakePrefs(lookup = true), FakeLookup(answer)))
                .navigate(ctx, SavedPlaceKind.HOME, place).shouldBeInstanceOf<NavigateSavedOutput>().done
            done.problem shouldBe problem
            done.searched shouldBe true
            maps.calls shouldBe listOf("search Nowhere 1 app.comaps.fdroid")
        }
    }

    "an app that routes text needs no lookup; a cached point needs neither" {
        val ctx = FakeSkillContext()
        val place = SavedPlace("12 Main St", null)
        val lookup = FakeLookup()
        val text = RecordingMaps(listOf(MAGIC_APP.copy(text = true)))
        PlacesFlow(NavigationInfo, lookupDeps(PlaceStore(place), text, FakePrefs(lookup = true), lookup))
            .navigate(ctx, SavedPlaceKind.HOME, place).shouldBeInstanceOf<NavigateSavedOutput>().result shouldBe MapResult.ROUTE
        text.calls shouldBe listOf("text 12 Main St com.generalmagic.magicearth")

        val points = MemoryPointStore().apply { put("12 main st", GeoPoint(1.0, 2.0), 5L) }
        val maps = RecordingMaps(listOf(OSMAND_APP))
        PlacesFlow(NavigationInfo, lookupDeps(PlaceStore(place), maps, FakePrefs(), lookup, points))
            .navigate(ctx, SavedPlaceKind.HOME, place).shouldBeInstanceOf<NavigateSavedOutput>().done.lookedUp shouldBe true
        maps.calls shouldBe listOf("route 1,2 HOME net.osmand.plus")
        lookup.asked shouldBe listOf()
    }
})
