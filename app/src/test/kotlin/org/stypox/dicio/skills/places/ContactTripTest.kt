package org.stypox.dicio.skills.places

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.ask.GarPickOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.offer.GarOfferAnswerSkill
import org.stypox.dicio.gar.offer.GarOfferOutput
import org.stypox.dicio.skills.contact_info.ContactBook
import org.stypox.dicio.skills.contact_info.ContactField
import org.stypox.dicio.skills.contact_info.ContactName
import org.stypox.dicio.skills.contact_info.ContactValue
import org.stypox.dicio.skills.contact_info.FakeContactBook
import org.stypox.dicio.skills.navigation.NavigationInfo
import org.stypox.dicio.skills.navigation.NavigationOutput
import org.stypox.dicio.skills.navigation.NavigationSkill
import org.stypox.dicio.sentences.Sentences

private val MOM = ContactName(1, "Mom")
private val SARAH_LEE = ContactName(3, "Sarah Lee")
private val SARAH_CONNOR = ContactName(4, "Sarah Connor")
private val DAVE = ContactName(8, "Dave Smith")
private val CONTACTS = listOf(MOM, ContactName(2, "Dad"), SARAH_LEE, SARAH_CONNOR, DAVE)

private fun book(allowed: Boolean = true) = FakeContactBook(
    contacts = CONTACTS,
    values = mapOf(
        (3L to ContactField.ADDRESS) to listOf(ContactValue("12 Main St\nSpringfield", "Home")),
        (8L to ContactField.ADDRESS) to listOf(
            ContactValue("4 Oak Ave", "Work"), ContactValue("9 Elm Rd", "Home"), ContactValue("4  oak ave", "Other"),
        ),
    ),
    allowed = allowed,
)

private fun contactDeps(book: ContactBook, maps: MapLauncher, locked: Boolean = false) = PlacesDeps(
    maps = { maps },
    location = { object : DeviceLocation {
        override fun access() = LocationAccess.PRECISE
        override suspend fun requestAccess() = LocationAccess.PRECISE
        override fun isEnabled() = true
        override suspend fun current(): DeviceFix? = null
        override suspend fun placeName(point: GeoPoint): String? = null
        override fun lastKnown(maxAgeMillis: Long) = DeviceFix(GeoPoint(40.5, -111.9), 30f, true)
    } },
    isLocked = { locked },
    yesNo = { null },
    prefs = { FakePrefs() },
    lookup = { FakeLookup() },
    points = { MemoryPointStore() },
    contacts = { book },
    label = { _, kind -> kind.name },
)

class ContactTripTest : StringSpec({
    "people in navigation requests" {
        for ((where, target) in listOf(
            "sarah's" to PersonTarget("sarah", SavedPlaceKind.HOME, true),
            "sarahs house" to PersonTarget("sarah", SavedPlaceKind.HOME, true, sure = true),
            "sarah's place" to PersonTarget("sarah", SavedPlaceKind.HOME, true, sure = true),
            "Sarah’s house." to PersonTarget("sarah", SavedPlaceKind.HOME, true, sure = true),
            "grandma's house" to PersonTarget("grandma", SavedPlaceKind.HOME, true, sure = true),
            "sarah's work" to PersonTarget("sarah", SavedPlaceKind.WORK, true, sure = true),
            "sarah lee's" to PersonTarget("sarah lee", SavedPlaceKind.HOME, true),
            "james' house" to PersonTarget("james", SavedPlaceKind.HOME, true, sure = true),
            "my mom's house" to PersonTarget("mom", SavedPlaceKind.HOME, true, sure = true),
            "my mom's" to PersonTarget("mom", SavedPlaceKind.HOME, true, sure = true),
            "my sister's work" to PersonTarget("sister", SavedPlaceKind.WORK, true, sure = true),
            "my dad's place" to PersonTarget("dad", SavedPlaceKind.HOME, true, sure = true),
            // maybe a shop: not sure
            "mcdonald's" to PersonTarget("mcdonald", SavedPlaceKind.HOME, true),
            "trader joe's" to PersonTarget("trader joe", SavedPlaceKind.HOME, true),
            "sarah" to PersonTarget("sarah", null, false),
            "mom" to PersonTarget("mom", null, false),
        )) {
            withClue(where) { PersonWords.person(where) shouldBe target }
        }
        for (where in listOf(
            "the airport", "airport", "the train station", "train station", "central park", "my home", "home",
            "my house", "my car", "my mom",
            "home depot", "42nd street", "a coffee shop", "the nearest gas station", "the long way round to the beach",
            "",
        )) {
            withClue(where) { PersonWords.person(where).shouldBeNull() }
        }
    }

    "the decision: locked, no access, no match, several, no address, one, several addresses" {
        val find = { said: String, how: NameMatch -> ContactTrip.find(CONTACTS, said, how) }
        val values = { c: ContactName -> book().values(c, ContactField.ADDRESS) }
        val sarahs = PersonTarget("sarah", SavedPlaceKind.HOME, true)
        ContactTrip.decide(sarahs.copy(sure = true), locked = true, access = true, find, values) shouldBe
            ContactTrip.Locked
        // a bare "sarah's" may be a shop: searches
        ContactTrip.decide(sarahs, locked = true, access = true, find, values) shouldBe ContactTrip.Search
        ContactTrip.decide(PersonTarget("boston", null, false), locked = true, access = true, find, values) shouldBe
            ContactTrip.Search
        ContactTrip.decide(sarahs, locked = false, access = false, find, values) shouldBe ContactTrip.Search
        ContactTrip.decide(PersonTarget("zelda", SavedPlaceKind.HOME, true), false, true, find, values) shouldBe
            ContactTrip.Search
        ContactTrip.decide(sarahs, false, true, find, values) shouldBe ContactTrip.Which(listOf(SARAH_LEE, SARAH_CONNOR))
        ContactTrip.decide(PersonTarget("mom", null, false), false, true, find, values) shouldBe ContactTrip.NoAddress(MOM)
        ContactTrip.decide(PersonTarget("sarah lee", SavedPlaceKind.HOME, true), false, true, find, values) shouldBe
            ContactTrip.Go(SARAH_LEE, "12 Main St, Springfield")
        val daves = ContactTrip.WhichAddress(DAVE, listOf(ContactValue("9 Elm Rd", "Home"), ContactValue("4 Oak Ave", "Work")))
        ContactTrip.decide(PersonTarget("dave", SavedPlaceKind.HOME, true, sure = true), false, true, find, values) shouldBe
            daves
        // a bare "dave's": Dave or a shop? asks first
        ContactTrip.decide(PersonTarget("dave", SavedPlaceKind.HOME, true), false, true, find, values) shouldBe
            ContactTrip.Check(DAVE, daves)
        // "dave's work": work first
        ContactTrip.ordered(values(DAVE), SavedPlaceKind.WORK).map { it.value } shouldBe listOf("4 Oak Ave", "9 Elm Rd")
        // a bare name needs a close match; a possessive takes "daves" as "dave" too
        ContactTrip.find(CONTACTS, "zelda", NameMatch.CLOSE) shouldBe listOf()
        ContactTrip.find(CONTACTS, "daves", NameMatch.WORDS) shouldBe listOf(DAVE)
    }

    "shops are no contacts: mcdonald's, trader joe's, smith's search; wendy's asks" {
        val joe = ContactName(20, "Joe Smith")
        val john = ContactName(21, "John Smith")
        val wendy = ContactName(22, "Wendy Jones")
        val contacts = CONTACTS + listOf(joe, john, wendy)
        val find = { said: String, how: NameMatch -> ContactTrip.find(contacts, said, how) }
        val values = { _: ContactName -> listOf(ContactValue("1 Pine St", "Home")) }
        for (shop in listOf("mcdonald's", "trader joe's", "smith's", "wendy's")) {
            val target = PersonWords.person(shop)!!
            withClue("$shop locked") { ContactTrip.decide(target, true, true, find, values) shouldBe ContactTrip.Search }
        }
        for (shop in listOf("mcdonald's", "trader joe's", "smith's")) {
            withClue(shop) {
                ContactTrip.decide(PersonWords.person(shop)!!, false, true, find, values) shouldBe ContactTrip.Search
            }
        }
        ContactTrip.decide(PersonWords.person("wendy's")!!, false, true, find, values) shouldBe
            ContactTrip.Check(wendy, ContactTrip.Go(wendy, "1 Pine St"))
        // said as a person's place, it goes
        ContactTrip.decide(PersonWords.person("wendy's house")!!, false, true, find, values) shouldBe
            ContactTrip.Go(wendy, "1 Pine St")
    }

    "take me to sarah lee's goes to her address; the airport stays a search" {
        val ctx = FakeSkillContext()
        val maps = RecordingMaps(listOf(OSMAND_APP))
        val output = NavigationPlaces.output(ctx, NavigationInfo, "sarah lee's", contactDeps(book(), maps))
            .shouldBeInstanceOf<ContactRouteOutput>()
        output.name shouldBe "Sarah Lee"
        output.done.result shouldBe MapResult.ROUTE
        // OsmAnd routes the text itself, offline, around the last fix
        maps.calls shouldBe listOf("text 12 Main St, Springfield net.osmand.plus")

        NavigationPlaces.output(ctx, NavigationInfo, "the airport", contactDeps(book(), maps)).shouldBeNull()
        NavigationPlaces.output(ctx, NavigationInfo, "zelda's", contactDeps(book(), maps)).shouldBeNull()
        NavigationPlaces.output(ctx, NavigationInfo, "sarah's", contactDeps(book(allowed = false), maps)).shouldBeNull()
    }

    "which sarah, which address, and no address offers a search" {
        val ctx = FakeSkillContext()
        val maps = RecordingMaps(listOf(OSMAND_APP))
        val which = NavigationPlaces.output(ctx, NavigationInfo, "sarah's", contactDeps(book(), maps))
            .shouldBeInstanceOf<GarPickOutput<ContactName>>()
        which.options shouldBe listOf(SARAH_LEE, SARAH_CONNOR)
        which.choose(ctx, SARAH_LEE).shouldBeInstanceOf<ContactRouteOutput>()

        val address = NavigationPlaces.output(ctx, NavigationInfo, "dave's place", contactDeps(book(), maps))
            .shouldBeInstanceOf<GarPickOutput<ContactValue>>()
        address.pick("home") shouldBe ContactValue("9 Elm Rd", "Home")
        address.choose(ctx, ContactValue("4 Oak Ave", "Work")).shouldBeInstanceOf<ContactRouteOutput>().address shouldBe "4 Oak Ave"

        val offer = NavigationPlaces.output(ctx, NavigationInfo, "mom's", contactDeps(book(), maps))
            .shouldBeInstanceOf<GarOfferOutput>()
        offer.onYes(ctx).shouldBeInstanceOf<NavigationOutput>()
        maps.calls.last() shouldBe "search mom's net.osmand.plus"
    }

    "after which address or an offer's yes, the maps app picker still takes taps" {
        val ctx = FakeSkillContext()
        val maps = RecordingMaps(listOf(OSMAND_APP, VELA_APP))
        // "Which address?": the log holds the picker wrapped in a GarFollowUpOutput
        val address = NavigationPlaces.output(ctx, NavigationInfo, "dave's place", contactDeps(book(), maps))
            .shouldBeInstanceOf<GarPickOutput<ContactValue>>()
        @Suppress("UNCHECKED_CAST")
        val pickSkill = address.getInteractionPlan(ctx).shouldBeInstanceOf<InteractionPlan.StartSubInteraction>()
            .nextSkills.first() as Skill<ContactValue?>
        val afterPick = pickSkill.generateOutput(ctx, ContactValue("4 Oak Ave", "Work"))
            .shouldBeInstanceOf<GarFollowUpOutput>()
        afterPick.output.shouldBeInstanceOf<MapAppPickOutput>().enabled(afterPick) shouldBe true

        // an offer's yes ("Show it on the map?", "Search the map for mom's?")
        val offer = NavigationPlaces.output(ctx, NavigationInfo, "mom's", contactDeps(book(), maps))
            .shouldBeInstanceOf<GarOfferOutput>()
        val afterYes = GarOfferAnswerSkill(offer, Sentences.UtilYesNo["en"]!!).generateOutput(ctx, true)
            .shouldBeInstanceOf<GarFollowUpOutput>()
        val picker = afterYes.output.shouldBeInstanceOf<MapAppPickOutput>()
        picker.enabled(afterYes) shouldBe true
        picker.enabled(offer) shouldBe false
        // answered: no more taps
        picker.choose(ctx, VELA_APP)
        picker.enabled(afterYes) shouldBe false
        maps.calls shouldBe listOf("search mom's app.vela")
    }

    "locked: sarah's house says unlock, a bare name or sarah's searches, no contacts read" {
        val ctx = FakeSkillContext()
        val maps = RecordingMaps(listOf(OSMAND_APP))
        NavigationPlaces.output(ctx, NavigationInfo, "sarah's house", contactDeps(book(), maps, locked = true))
            .shouldBeInstanceOf<GarLockedOutput>()
        NavigationPlaces.output(ctx, NavigationInfo, "sarah", contactDeps(book(), maps, locked = true)).shouldBeNull()
        NavigationPlaces.output(ctx, NavigationInfo, "mcdonald's", contactDeps(book(), maps, locked = true)).shouldBeNull()
        NavigationPlaces.output(ctx, NavigationInfo, "sarah's", contactDeps(book(), maps, locked = true)).shouldBeNull()
        maps.calls shouldBe listOf()
    }

    "wendy's asks before going to Wendy, and a no searches" {
        val ctx = FakeSkillContext()
        val maps = RecordingMaps(listOf(OSMAND_APP))
        val wendy = ContactName(22, "Wendy")
        val wendys = FakeContactBook(
            contacts = listOf(wendy),
            values = mapOf((22L to ContactField.ADDRESS) to listOf(ContactValue("1 Pine St", "Home"))),
        )
        val check = NavigationPlaces.output(ctx, NavigationInfo, "wendy's", contactDeps(wendys, maps))
            .shouldBeInstanceOf<GarOfferOutput>()
        maps.calls shouldBe listOf()
        check.onNo!!(ctx).shouldBeInstanceOf<NavigationOutput>()
        maps.calls shouldBe listOf("search wendy's net.osmand.plus")
        check.onYes(ctx).shouldBeInstanceOf<ContactRouteOutput>().name shouldBe "Wendy"
    }

    "where is mom shows no route to mom's" {
        val ctx = FakeSkillContext()
        val maps = RecordingMaps(listOf(OSMAND_APP))
        val moms = FakeContactBook(
            contacts = listOf(MOM),
            values = mapOf((1L to ContactField.ADDRESS) to listOf(ContactValue("5 Birch Ln", "Home"))),
        )
        NavigationPlaces.output(ctx, NavigationInfo, "mom", contactDeps(moms, maps), people = false).shouldBeNull()
        maps.calls shouldBe listOf()
        NavigationPlaces.output(ctx, NavigationInfo, "mom", contactDeps(moms, maps))
            .shouldBeInstanceOf<ContactRouteOutput>()

        val navigation = NavigationSkill(NavigationInfo, Sentences.Navigation["en"]!!)
        for (input in listOf("where is mom", "where's sarah")) {
            withClue(input) {
                navigation.score(FakeSkillContext().forInput(input), input).second
                    .shouldBeInstanceOf<Sentences.Navigation.WhereIs>()
            }
        }
        navigation.score(FakeSkillContext().forInput("take me to mom"), "take me to mom").second
            .shouldBeInstanceOf<Sentences.Navigation.Query>()
    }

    "the navigation skill hands contacts to GAR after home, websites and nearby" {
        NavigationPlaces.target("sarah's") shouldBe NavigationPlaces.Target.Person(PersonTarget("sarah", SavedPlaceKind.HOME, true))
        NavigationPlaces.target("home") shouldBe NavigationPlaces.Target.Saved(SavedPlaceKind.HOME)
        NavigationPlaces.target("coffee near me") shouldBe NavigationPlaces.Target.Nearby("coffee")
        NavigationPlaces.target("the airport").shouldBeNull()
    }
})
