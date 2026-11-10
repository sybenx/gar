package org.stypox.dicio.skills.places

import android.util.Log
import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.Resolution
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.gar.ask.GarPickOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.offer.GarOfferOutput
import org.stypox.dicio.skills.contact_info.ContactField
import org.stypox.dicio.skills.contact_info.ContactInfoSkill
import org.stypox.dicio.skills.contact_info.ContactName
import org.stypox.dicio.skills.contact_info.ContactSay
import org.stypox.dicio.skills.contact_info.ContactValue
import org.stypox.dicio.skills.navigation.NavigationSkill
import org.stypox.dicio.util.getString

/**
 * A person a navigation request may mean: [name] as said, the address they [wants] ("Sarah's
 * work"; null for none said) and whether it was [possessive] ("Sarah's", "Sarah's house") or a
 * bare name ("directions to Sarah"). [sure] when the words say it is a person's place ("Sarah's
 * house", "Sarah's work", "my mom's"): a bare "Wendy's" or "Trader Joe's" may be a shop.
 */
data class PersonTarget(
    val name: String,
    val wants: SavedPlaceKind?,
    val possessive: Boolean,
    val sure: Boolean = false,
)

/** GAR (gar:places). English words for "take me to Sarah's" and its kin. */
object PersonWords {
    /**
     * "sarah's", "sarahs house", "sarah's place" -> (sarah, HOME, true); "sarah's work" ->
     * (sarah, WORK, true); "my mom's house", "my mom's" -> (mom, HOME, true); "sarah" -> (sarah,
     * null, false); null with "the", "a", "my" before no possessive (home and work are
     * [PlaceWords]'), a number, a word places have ("park", "station") or more than 3 name words.
     * Only a bare "sarah's" is not [PersonTarget.sure].
     */
    fun person(where: String): PersonTarget? {
        val said = where.lowercase().replace('’', '\'').replace(SPACES, " ")
            .trim().trimEnd('.', '!', '?', ',').trim()
        // "my mom's house" is mom's; "my car" stays a search
        val mine = said.startsWith("my ")
        val text = if (mine) said.removePrefix("my ").trim() else said
        if (text.isEmpty() || text.any { it.isDigit() } || text.substringBefore(' ') in LEADS) {
            return null
        }
        HOME_OF.matchEntire(text)?.let { match ->
            return target(nameOf(match.groupValues[1], match.groupValues[2]), SavedPlaceKind.HOME, true, true)
        }
        WORK_OF.matchEntire(text)?.let { match ->
            return target(match.groupValues[1], SavedPlaceKind.WORK, true, true)
        }
        POSSESSIVE.matchEntire(text)?.let { match ->
            return target(match.groupValues[1], SavedPlaceKind.HOME, true, mine)
        }
        return if (mine) null else target(text, null, false, false)
    }

    /** "sarahs house" is Sarah's: the s goes when no apostrophe said it. */
    private fun nameOf(name: String, apostrophe: String): String =
        if (apostrophe.isEmpty() && name.endsWith("s") && name.length > 3 && ' ' !in name) name.dropLast(1) else name

    private fun target(name: String, wants: SavedPlaceKind?, possessive: Boolean, sure: Boolean): PersonTarget? {
        val words = name.trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > MAX_WORDS || words.any { it in LEADS || it in PLACE_WORDS } ||
            words.any { word -> word.any { !it.isLetter() && it != '-' && it != '\'' && it != '.' } }
        ) {
            return null
        }
        return PersonTarget(words.joinToString(" "), wants, possessive, sure)
    }

    private const val MAX_WORDS = 3
    private val SPACES = Regex("\\s+")
    // "james' house": the apostrophe alone, so the s stays James'
    private val HOME_OF = Regex("(.+?)('s|')?\\s+(house|home|place|apartment|flat|condo)")
    private val WORK_OF = Regex("(.+?)(?:'s|')\\s+(?:work|office|job|workplace)")
    private val POSSESSIVE = Regex("(.+?)(?:'s|')")
    private val LEADS = setOf(
        "the", "a", "an", "my", "our", "your", "his", "her", "their", "this", "that", "some", "any",
        "nearest", "closest", "near", "here", "there", "home", "work",
    )
    private val PLACE_WORDS = setOf(
        "street", "st", "avenue", "ave", "road", "rd", "boulevard", "blvd", "lane", "drive", "way",
        "park", "station", "airport", "mall", "store", "shop", "market", "center", "centre", "library",
        "museum", "school", "university", "college", "hospital", "clinic", "beach", "restaurant", "cafe",
        "coffee", "bar", "pub", "hotel", "church", "temple", "downtown", "city", "town", "lake",
        "mountain", "river", "bridge", "zoo", "stadium", "gym", "pharmacy", "bank", "office", "depot",
        "square", "plaza", "court", "hall", "theater", "theatre", "cinema", "gas", "parking",
    )
}

/** What "take me to Sarah's" does, see [ContactTrip.decide]. */
sealed interface ContactTrip {
    /** A locked phone and a sure possessive: "Unlock your phone first.", contacts not read. */
    data object Locked : ContactTrip

    /**
     * No contacts access, no such contact, or anything but a sure possessive on a locked phone:
     * the map search.
     */
    data object Search : ContactTrip

    /**
     * A bare one-word possessive that one contact matches, "Wendy's": the shop or Wendy? Asks
     * before [then].
     */
    data class Check(val contact: ContactName, val then: ContactTrip) : ContactTrip

    data class Which(val contacts: List<ContactName>) : ContactTrip
    data class NoAddress(val contact: ContactName) : ContactTrip
    data class WhichAddress(val contact: ContactName, val addresses: List<ContactValue>) : ContactTrip
    data class Go(val contact: ContactName, val address: String) : ContactTrip

    companion object {
        /**
         * Pure, tested. Bare names ([PersonTarget.possessive] false) need [find]'s close match,
         * so "directions to boston" doesn't go to Bob Stone. Sure possessives ("Sarah's house")
         * also take whole-word matches ([ContactInfoSkill.find]). A bare possessive may be a shop
         * ("McDonald's", "Trader Joe's"): one word matches first names only and asks first
         * ([Check]), more words need the close match.
         */
        fun decide(
            target: PersonTarget,
            locked: Boolean,
            access: Boolean,
            find: (String, NameMatch) -> List<ContactName>,
            addresses: (ContactName) -> List<ContactValue>,
        ): ContactTrip {
            if (locked) {
                // a bare name or "wendy's" may be no contact at all: "take me to boston" still searches
                return if (target.possessive && target.sure) Locked else Search
            }
            if (!access) {
                return Search
            }
            val oneWord = ' ' !in target.name
            val how = when {
                !target.possessive -> NameMatch.CLOSE
                target.sure -> NameMatch.WORDS
                oneWord -> NameMatch.FIRST_NAME
                else -> NameMatch.CLOSE
            }
            val contacts = find(target.name, how)
            return when (contacts.size) {
                0 -> Search
                1 -> forContact(contacts[0], addresses(contacts[0]), target.wants).let { trip ->
                    if (how == NameMatch.FIRST_NAME && trip !is NoAddress) Check(contacts[0], trip) else trip
                }
                else -> Which(contacts)
            }
        }

        /** [contact]'s trip once picked: no address, one, or "Which address?". */
        fun forContact(contact: ContactName, values: List<ContactValue>, wants: SavedPlaceKind?): ContactTrip {
            val ordered = ordered(values, wants)
            return when (ordered.size) {
                0 -> NoAddress(contact)
                1 -> Go(contact, ordered[0].value)
                else -> WhichAddress(contact, ordered)
            }
        }

        /**
         * Home first (work first for "Sarah's work"), then the rest as stored, each on one line
         * ([CardAddresses.oneLine]) and once.
         */
        fun ordered(values: List<ContactValue>, wants: SavedPlaceKind?): List<ContactValue> {
            val first = wants ?: SavedPlaceKind.HOME
            return values
                .mapNotNull { v -> CardAddresses.oneLine(v.value)?.let { ContactValue(it, v.label) } }
                .distinctBy { it.value.lowercase().replace(Regex("\\s+"), " ") }
                .sortedBy { if (CardAddresses.labelKind(it.label) == first) 0 else 1 }
        }

        /** The contacts [spoken] names, matched [how]. */
        fun find(contacts: List<ContactName>, spoken: String, how: NameMatch): List<ContactName> {
            val name: (ContactName) -> String = when (how) {
                NameMatch.WORDS -> return ContactInfoSkill.find(contacts, spoken)
                NameMatch.CLOSE -> ContactName::name
                // "wendy" is Wendy Jones, "smith" is not John Smith
                NameMatch.FIRST_NAME -> { c -> c.name.trim().substringBefore(' ') }
            }
            return when (val match = NameMatcher.match(spoken, contacts, name)) {
                is Resolution.Resolved -> listOf(match.value)
                is Resolution.Ambiguous -> match.options.take(ContactInfoSkill.MAX_CONTACTS)
                else -> listOf()
            }
        }
    }
}

/** How [ContactTrip.find] matches a spoken name. */
enum class NameMatch {
    /** NameMatcher's close match on the whole name. */
    CLOSE,

    /** NameMatcher's close match on the first name only. */
    FIRST_NAME,

    /** Contact info's: the close match, then whole words, "sarahs" as "sarah". */
    WORDS,
}

/**
 * GAR (gar:places). "Take me to Sarah's": the contact's postal address through [PlaceRoutes],
 * "Which one?" for several Sarahs, "Which address?" (home first) for several addresses, and
 * "Sarah has no address in your contacts. Search the map for Sarah's?" for none. Null when the
 * navigation skill should search for the words as today.
 */
class ContactTrips(private val flow: PlacesFlow) {
    private val deps get() = flow.deps

    suspend fun output(ctx: SkillContext, where: String, target: PersonTarget): SkillOutput? {
        val book = deps.contacts(ctx)
        val trip = try {
            ContactTrip.decide(
                target, deps.isLocked(ctx), book.allowed,
                find = { said, how -> ContactTrip.find(book.names(), said, how) },
                addresses = { book.values(it, ContactField.ADDRESS) },
            )
        } catch (e: RuntimeException) {
            // SecurityException when access was taken back, or a provider failing
            Log.w(TAG, "Could not read the contacts", e)
            ContactTrip.Search
        }
        return when (trip) {
            ContactTrip.Locked -> GarLockedOutput()
            ContactTrip.Search -> null
            is ContactTrip.Which -> GarPickOutput(
                info = flow.info,
                options = trip.contacts,
                question = { c ->
                    c.getString(R.string.gar_contacts_which, ContactSay.joinedOr(c, trip.contacts.map { it.name }))
                },
                shown = { _, contact -> contact.name },
                nameOf = { it.name },
                onPick = { c, contact ->
                    answer(c, where, ContactTrip.forContact(
                        contact, book.values(contact, ContactField.ADDRESS), target.wants,
                    ))
                },
            )
            else -> answer(ctx, where, trip)
        }
    }

    private suspend fun answer(ctx: SkillContext, where: String, trip: ContactTrip): SkillOutput = when (trip) {
        is ContactTrip.Go -> go(ctx, trip.contact, trip.address)
        // "Wendy's": the contact or the shop
        is ContactTrip.Check -> GarOfferOutput(
            info = flow.info,
            speech = { it.getString(R.string.skill_places_contact_check, trip.contact.name) },
            details = { listOf(it.getString(R.string.skill_places_contact_check_detail, where)) },
            yesLabel = R.string.skill_places_contact_go,
            noLabel = R.string.skill_places_contact_search,
            yesNoData = deps.yesNo(ctx),
            onNo = { c -> search(c, where) },
        ) { c -> answer(c, where, trip.then) }
        is ContactTrip.NoAddress -> GarOfferOutput(
            info = flow.info,
            speech = { it.getString(R.string.skill_places_contact_no_address, trip.contact.name, where) },
            yesLabel = R.string.skill_places_contact_search,
            noLabel = R.string.skill_places_card_no,
            yesNoData = deps.yesNo(ctx),
        ) { c -> search(c, where) }
        is ContactTrip.WhichAddress -> GarPickOutput(
            info = flow.info,
            options = trip.addresses,
            question = { c ->
                c.getString(R.string.skill_places_which_address, ContactSay.joinedOr(c, trip.addresses.map { shown(it) }))
            },
            shown = { _, value -> shown(value) },
            nameOf = { listOfNotNull(it.label, it.value).joinToString(" ") },
            onPick = { c, value -> go(c, trip.contact, value.value) },
        )
        // decide gives these only before a contact is picked
        else -> search(ctx, where)
    }

    /** "Home, 12 Main St". */
    private fun shown(value: ContactValue): String =
        listOfNotNull(value.label?.takeIf { it.isNotBlank() }, value.value).joinToString(", ")

    private suspend fun go(ctx: SkillContext, contact: ContactName, address: String): SkillOutput {
        val routes = PlaceRoutes(flow)
        return routes.withApp(ctx, none = { ContactRouteOutput(contact.name, address, RouteDone(MapResult.NO_APP)) }) { c, app ->
            routes.go(
                c, app, point = null, address = address, label = contact.name, locked = deps.isLocked(c),
                // NominatimLookup keeps contacts' points in its own cache, keyed by the address
                keep = { _, _ -> },
            ) { done -> ContactRouteOutput(contact.name, address, done) }
        }
    }

    /** Today's search for the words as said, as Dicio's navigation skill sends it. */
    private suspend fun search(ctx: SkillContext, where: String): SkillOutput =
        NavigationPlaces.search(ctx, flow.info, NavigationSkill.cleanPlace(ctx.parserFormatter, where), deps)

    private companion object {
        const val TAG = "ContactTrips"
    }
}
