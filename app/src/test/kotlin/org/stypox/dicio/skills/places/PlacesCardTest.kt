package org.stypox.dicio.skills.places

import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarCancelledOutput
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.offer.GarOfferAnswerSkill
import org.stypox.dicio.gar.offer.GarOfferOutput
import org.stypox.dicio.gar.settings.GarPlaces
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.gar.settings.PlaceEditor
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.navigation.NavigationInfo

private class CardSaved(val places: MutableMap<SavedPlaceKind, SavedPlace> = mutableMapOf()) : SavedPlaces {
    override suspend fun get(kind: SavedPlaceKind) = places[kind]
    override suspend fun set(kind: SavedPlaceKind, place: SavedPlace) {
        places[kind] = place
    }
    override suspend fun forget(kind: SavedPlaceKind) = places.remove(kind) != null
}

private class FakeCard(
    val access: Boolean = true,
    val work: List<String> = listOf("1 Infinite Loop, Cupertino"),
) : CardPlaces {
    var reads = 0
    override fun hasAccess() = access
    override suspend fun addresses(kind: SavedPlaceKind): List<String> {
        reads++
        return if (kind == SavedPlaceKind.WORK) work else listOf()
    }
}

private class CardMaps : MapLauncher {
    val calls = mutableListOf<String>()
    override fun apps() = listOf(OSMAND_APP.copy(text = false))
    override fun defaultApp(): String? = null
    override fun route(app: MapApp, point: GeoPoint, label: String, fix: GeoPoint?): MapResult {
        calls += "route ${MapLinks.coordinates(point)}"
        return MapResult.ROUTE
    }
    override fun routeText(app: MapApp, address: String, near: GeoPoint?): MapResult? = null
    override fun search(query: String, app: MapApp?): MapResult {
        calls += "search $query"
        return MapResult.SHOWN
    }
    override fun show(point: GeoPoint, label: String, app: MapApp?) = MapResult.SHOWN
}

private class FixedLocation(val result: DeviceFix?, val access: LocationAccess = LocationAccess.PRECISE) : DeviceLocation {
    override fun access() = access
    override suspend fun requestAccess() = access
    override fun isEnabled() = true
    override suspend fun current() = result
    override suspend fun placeName(point: GeoPoint): String? = null
}

private fun cardDeps(
    saved: SavedPlaces = CardSaved(),
    card: CardPlaces = FakeCard(),
    maps: MapLauncher = CardMaps(),
    locked: () -> Boolean = { false },
) = PlacesDeps(
    saved = { saved },
    location = { FixedLocation(null) },
    maps = { maps },
    pages = { object : PlacePages {
        override fun openAppInfo() = true
        override fun openLocationSettings() = true
    } },
    isLocked = { locked() },
    yesNo = { Sentences.UtilYesNo["en"] },
    card = { card },
    prefs = { FakePrefs() },
    lookup = { FakeLookup() },
    points = { MemoryPointStore() },
    label = { _, kind -> kind.name },
)

class PlacesCardTest : StringSpec({
    "postal rows: home and work types, labels that clearly say so" {
        for ((type, label, kind) in listOf(
            Triple(StructuredPostal.TYPE_HOME, null, SavedPlaceKind.HOME),
            Triple(StructuredPostal.TYPE_WORK, null, SavedPlaceKind.WORK),
            Triple(StructuredPostal.TYPE_OTHER, "Home", SavedPlaceKind.HOME),
            Triple(StructuredPostal.TYPE_CUSTOM, "my house", SavedPlaceKind.HOME),
            Triple(StructuredPostal.TYPE_CUSTOM, "Office", SavedPlaceKind.WORK),
            Triple(StructuredPostal.TYPE_CUSTOM, "Work (new)", SavedPlaceKind.WORK),
            Triple(StructuredPostal.TYPE_CUSTOM, "job", SavedPlaceKind.WORK),
        )) {
            withClue("$type $label") { CardAddresses.kindOf(type, label) shouldBe kind }
        }
        for ((type, label) in listOf(
            StructuredPostal.TYPE_OTHER to null,
            StructuredPostal.TYPE_OTHER to "Cabin",
            StructuredPostal.TYPE_CUSTOM to "Home office",
            StructuredPostal.TYPE_CUSTOM to "homework club",
            StructuredPostal.TYPE_CUSTOM to "",
            // a "work" label on a home row is still home: the type wins
        )) {
            withClue("$type $label") { CardAddresses.kindOf(type, label).shouldBeNull() }
        }
        CardAddresses.kindOf(StructuredPostal.TYPE_HOME, "Work") shouldBe SavedPlaceKind.HOME
    }

    "several addresses: the primary first, each once, on one line" {
        val rows = listOf(
            PostalRow(StructuredPostal.TYPE_WORK, null, "5 Old Office Rd\nProvo, UT"),
            PostalRow(StructuredPostal.TYPE_HOME, null, "12 Main St"),
            PostalRow(StructuredPostal.TYPE_CUSTOM, "Office", "1 Infinite Loop\n Cupertino, CA ", primary = true),
            PostalRow(StructuredPostal.TYPE_WORK, null, "5 old office rd,\nprovo,  UT"),
            PostalRow(StructuredPostal.TYPE_WORK, null, "  \n "),
        )
        CardAddresses.pick(rows, SavedPlaceKind.WORK) shouldBe
            listOf("1 Infinite Loop, Cupertino, CA", "5 Old Office Rd, Provo, UT")
        CardAddresses.pick(rows, SavedPlaceKind.HOME) shouldBe listOf("12 Main St")
        CardAddresses.pick(listOf(), SavedPlaceKind.HOME) shouldBe listOf()
    }

    "the fallback: saved, then the card, then the question; locked and no access read no card" {
        var reads = 0
        val card = suspend { reads++; listOf("1 Infinite Loop") }
        val saved = SavedPlace("12 Main St", null)
        SavedPlaceSource.decide(saved, locked = false, contactsAccess = true, card) shouldBe
            SavedPlaceSource.Saved(saved)
        SavedPlaceSource.decide(null, locked = false, contactsAccess = true, card) shouldBe
            SavedPlaceSource.Card(listOf("1 Infinite Loop"))
        SavedPlaceSource.decide(null, locked = false, contactsAccess = true) { listOf() } shouldBe
            SavedPlaceSource.Ask
        reads shouldBe 1
        SavedPlaceSource.decide(null, locked = true, contactsAccess = true, card) shouldBe SavedPlaceSource.Locked
        SavedPlaceSource.decide(null, locked = false, contactsAccess = false, card) shouldBe SavedPlaceSource.Ask
        reads shouldBe 1
    }

    "take me to work offers the card's address; yes saves and goes, no asks" {
        val ctx = FakeSkillContext()
        val saved = CardSaved()
        val maps = CardMaps()
        val flow = PlacesFlow(NavigationInfo, cardDeps(saved = saved, maps = maps))
        val offer = flow.navigateTo(ctx, SavedPlaceKind.WORK).shouldBeInstanceOf<GarOfferOutput>()
        saved.places[SavedPlaceKind.WORK].shouldBeNull()

        val answer = GarOfferAnswerSkill(offer, Sentences.UtilYesNo["en"]!!)
        val no = answer.generateOutput(ctx, false).shouldBeInstanceOf<GarFollowUpOutput>()
        no.output.shouldBeInstanceOf<SavedPlaceAskOutput>().purpose shouldBe SavedPlacePurpose.NAVIGATE
        saved.places[SavedPlaceKind.WORK].shouldBeNull()

        val yes = answer.generateOutput(ctx, true).shouldBeInstanceOf<GarFollowUpOutput>()
        yes.output.shouldBeInstanceOf<SavedPlaceOutput>().then.shouldBeInstanceOf<NavigateSavedOutput>()
        saved.places[SavedPlaceKind.WORK] shouldBe SavedPlace("1 Infinite Loop, Cupertino", null)
        maps.calls shouldBe listOf("search 1 Infinite Loop, Cupertino")

        // once saved, the card isn't asked again
        val card = FakeCard()
        PlacesFlow(NavigationInfo, cardDeps(saved = saved, card = card)).navigateTo(ctx, SavedPlaceKind.WORK)
            .shouldBeInstanceOf<NavigateSavedOutput>()
        card.reads shouldBe 0
    }

    "several card addresses: which one, and none asks" {
        val ctx = FakeSkillContext()
        val saved = CardSaved()
        val maps = CardMaps()
        val card = FakeCard(work = listOf("1 Infinite Loop, Cupertino", "4 Oak Ave"))
        val pick = PlacesFlow(NavigationInfo, cardDeps(saved = saved, card = card, maps = maps))
            .navigateTo(ctx, SavedPlaceKind.WORK).shouldBeInstanceOf<org.stypox.dicio.gar.ask.GarPickOutput<String?>>()
        pick.options shouldBe listOf("1 Infinite Loop, Cupertino", "4 Oak Ave", null)
        pick.pick("none") shouldBe null
        pick.pick("the second one") shouldBe "4 Oak Ave"
        pick.choose(ctx, null).shouldBeInstanceOf<SavedPlaceAskOutput>()
        saved.places[SavedPlaceKind.WORK].shouldBeNull()
        pick.choose(ctx, "4 Oak Ave").shouldBeInstanceOf<SavedPlaceOutput>()
        saved.places[SavedPlaceKind.WORK] shouldBe SavedPlace("4 Oak Ave", null)
        maps.calls shouldBe listOf("search 4 Oak Ave")
    }

    "a pasted map link or coordinates in the setting saves the point" {
        val store = GarSettingsSavedPlaces(GarSettingsModule.newDataStoreForPreviews())
        val editor = PlaceEditor(store)
        editor.saveTyped(SavedPlaceKind.HOME, "40.71278, -74.00597")
        store.get(SavedPlaceKind.HOME) shouldBe SavedPlace(null, GeoPoint(40.71278, -74.00597))
        editor.saveTyped(SavedPlaceKind.WORK, "https://magicearth.com/?show_on_map&lat=37.33&lon=-122.03&name=Office")
        store.get(SavedPlaceKind.WORK) shouldBe SavedPlace("Office", GeoPoint(37.33, -122.03))
        // a looked-up point is read back as such
        store.set(SavedPlaceKind.WORK, SavedPlace("1 Infinite Loop", GeoPoint(37.33, -122.03), lookedUp = true))
        store.get(SavedPlaceKind.WORK) shouldBe SavedPlace("1 Infinite Loop", GeoPoint(37.33, -122.03), lookedUp = true)
    }

    "no card address, no contacts access or a locked phone: no offer" {
        val ctx = FakeSkillContext()
        PlacesFlow(NavigationInfo, cardDeps()).navigateTo(ctx, SavedPlaceKind.HOME)
            .shouldBeInstanceOf<SavedPlaceAskOutput>()
        val noAccess = FakeCard(access = false)
        PlacesFlow(NavigationInfo, cardDeps(card = noAccess)).navigateTo(ctx, SavedPlaceKind.WORK)
            .shouldBeInstanceOf<SavedPlaceAskOutput>()
        noAccess.reads shouldBe 0
        val lockedCard = FakeCard()
        PlacesFlow(NavigationInfo, cardDeps(card = lockedCard, locked = { true }))
            .navigateTo(ctx, SavedPlaceKind.WORK).shouldBeInstanceOf<GarLockedOutput>()
        lockedCard.reads shouldBe 0
    }

    "a yes after the phone locked saves nothing" {
        val ctx = FakeSkillContext()
        val saved = CardSaved()
        var locked = false
        val offer = PlacesFlow(NavigationInfo, cardDeps(saved = saved, locked = { locked }))
            .navigateTo(ctx, SavedPlaceKind.WORK).shouldBeInstanceOf<GarOfferOutput>()
        locked = true
        offer.onYes(ctx).shouldBeInstanceOf<GarLockedOutput>()
        saved.places[SavedPlaceKind.WORK].shouldBeNull()
    }

    "an offer without onNo still says OK to a no" {
        val offer = GarOfferOutput(
            info = NavigationInfo, speech = { "?" }, yesLabel = 0, noLabel = 0, yesNoData = null,
        ) { GarCancelledOutput() }
        GarOfferAnswerSkill(offer, Sentences.UtilYesNo["en"]!!).generateOutput(FakeSkillContext(), false)
            .shouldBeInstanceOf<GarCancelledOutput>()
    }

    "the setting writes the same store the voice flow reads" {
        val store = GarSettingsSavedPlaces(GarSettingsModule.newDataStoreForPreviews())
        val editor = PlaceEditor(store)
        editor.saveTyped(SavedPlaceKind.WORK, "  ").shouldBeNull()
        store.get(SavedPlaceKind.WORK).shouldBeNull()

        editor.saveTyped(SavedPlaceKind.WORK, "Springfield Library\n 1 Main St ")
        store.get(SavedPlaceKind.WORK) shouldBe SavedPlace("Springfield Library, 1 Main St", null)

        val fix = DeviceFix(GeoPoint(40.5, -111.9), 10f, true)
        editor.saveHere(SavedPlaceKind.HOME, FixedLocation(fix)).shouldBeInstanceOf<LocationResult.Found>()
        store.get(SavedPlaceKind.HOME) shouldBe SavedPlace(null, GeoPoint(40.5, -111.9))
        // an approximate fix can be 2 km off: returned for the dialog to explain, not saved
        val rough = DeviceFix(GeoPoint(40.6, -111.8), 2000f, false)
        editor.saveHere(SavedPlaceKind.HOME, FixedLocation(rough)) shouldBe LocationResult.Found(rough, null)
        store.get(SavedPlaceKind.HOME) shouldBe SavedPlace(null, GeoPoint(40.5, -111.9))
        editor.saveHere(SavedPlaceKind.HOME, FixedLocation(null)) shouldBe LocationResult.NoFix
        editor.saveHere(SavedPlaceKind.HOME, FixedLocation(fix, LocationAccess.NONE)) shouldBe LocationResult.NoAccess
        store.get(SavedPlaceKind.HOME) shouldBe SavedPlace(null, GeoPoint(40.5, -111.9))

        editor.saveCard(SavedPlaceKind.WORK, "1 Infinite Loop, Cupertino")
        store.get(SavedPlaceKind.WORK) shouldBe SavedPlace("1 Infinite Loop, Cupertino", null)

        // voice reads what the setting wrote
        val maps = CardMaps()
        PlacesFlow(NavigationInfo, cardDeps(saved = store, maps = maps)).navigateTo(FakeSkillContext(), SavedPlaceKind.HOME)
        maps.calls shouldBe listOf("route 40.5,-111.9")

        editor.clear(SavedPlaceKind.WORK)
        store.get(SavedPlaceKind.WORK).shouldBeNull()
    }

    "the rows' summaries" {
        val string = { id: Int, args: Array<out Any> -> "$id${args.toList()}" }
        GarPlaces.summary(SavedPlace("12 Main St", null), locked = false, string) shouldBe "12 Main St"
        GarPlaces.summary(SavedPlace("12 Main St", null), locked = true, string) shouldBe
            "${org.stypox.dicio.R.string.gar_places_locked}[]"
        GarPlaces.summary(SavedPlace("12 Main St", null), locked = null, string) shouldBe
            "${org.stypox.dicio.R.string.gar_places_locked}[]"
        GarPlaces.summary(null, locked = false, string) shouldBe "${org.stypox.dicio.R.string.gar_places_not_set}[]"
        GarPlaces.summary(SavedPlace(null, GeoPoint(40.712776, -74.005974)), locked = false, string) shouldBe
            "${org.stypox.dicio.R.string.gar_places_point}[40.71278, -74.00597]"
        GarPlaces.typed(" a\n\n b   c ") shouldBe "a, b c"
    }
})
