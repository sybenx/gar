package org.stypox.dicio.skills.places

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarCancelledOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.gar.offer.GarOfferOutput
import org.stypox.dicio.gar.ask.GarPickOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.contact_info.AndroidContactBook
import org.stypox.dicio.skills.contact_info.ContactBook
import org.stypox.dicio.skills.contact_info.ContactSay
import org.stypox.dicio.skills.sms.OwnContact
import org.stypox.dicio.util.getString
import org.stypox.dicio.skills.navigation.NavigationSkill

/** The settings pages the places skills offer to open. */
interface PlacePages {
    /** GAR's App info page, where location access is allowed after a "don't allow". */
    fun openAppInfo(): Boolean

    /** Android's location page, to turn location on. */
    fun openLocationSettings(): Boolean
}

class AndroidPlacePages(private val context: Context) : PlacePages {
    override fun openAppInfo(): Boolean = start(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    )

    override fun openLocationSettings(): Boolean = start(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("AndroidPlacePages", "Can't open ${intent.action}", e)
        false
    }
}

/**
 * What the places skills use of the phone, so their decisions can be tested with fakes.
 */
class PlacesDeps(
    val saved: (SkillContext) -> SavedPlaces = SavedPlaces::of,
    val location: (SkillContext) -> DeviceLocation = DeviceLocation::of,
    val maps: (SkillContext) -> MapLauncher = { MapLauncher.of(it.android) },
    val pages: (SkillContext) -> PlacePages = { AndroidPlacePages(it.android) },
    val isLocked: (SkillContext) -> Boolean = { ctx ->
        ctx.android.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    },
    val yesNo: (SkillContext) -> org.dicio.skill.standard.StandardRecognizerData<Sentences.UtilYesNo>? =
        { Sentences.UtilYesNo[it.sentencesLanguage] },
    val card: (SkillContext) -> CardPlaces = CardPlaces::of,
    // GAR: "Navigate with", "Find addresses online", the lookup and its cache, the contacts for
    // "take me to Sarah's", and what a saved place is called on a pin ("home")
    val prefs: (SkillContext) -> NavigationPrefs = NavigationPrefs::of,
    val lookup: (SkillContext) -> AddressLookup = AddressLookup::of,
    val points: (SkillContext) -> AddressPointStore = AddressPointStore::of,
    val contacts: (SkillContext) -> ContactBook = { ctx -> AndroidContactBook(ctx.android) { OwnContact.of(ctx) } },
    val label: (SkillContext, SavedPlaceKind) -> String = { ctx, kind -> ctx.getString(kind.label) },
)

/** What a saved place was asked for: to go there now, or only to save it. */
enum class SavedPlacePurpose { NAVIGATE, SAVE }

/**
 * GAR (gar:places). The steps the places skills share: finding the phone ([locate]), what to say
 * when that fails, saving home or work ([save]), and going there ([navigate]).
 */
class PlacesFlow(val info: SkillInfo, val deps: PlacesDeps = PlacesDeps()) {

    /**
     * Where the phone is, then [found] with it; or what to say when GAR has no access ("Open App
     * info?"), location is off ("Open location settings?") or no fix came.
     */
    suspend fun locate(
        ctx: SkillContext,
        withName: Boolean,
        nested: Boolean,
        found: suspend (LocationResult.Found) -> SkillOutput,
    ): SkillOutput = when (val result = LocationLookup.locate(deps.location(ctx), withName)) {
        is LocationResult.Found -> found(result)
        LocationResult.NoAccess -> offerPage(ctx, PlacesProblem.NO_ACCESS, nested) {
            deps.pages(it).openAppInfo()
        }
        LocationResult.Off -> offerPage(ctx, PlacesProblem.LOCATION_OFF, nested) {
            deps.pages(it).openLocationSettings()
        }
        LocationResult.NoFix -> PlacesProblemOutput(PlacesProblem.NO_FIX)
    }

    private fun offerPage(
        ctx: SkillContext,
        problem: PlacesProblem,
        nested: Boolean,
        open: (SkillContext) -> Boolean,
    ): SkillOutput = GarOfferOutput(
        info = info,
        speech = { it.getString(problem.speech) },
        details = { c -> listOfNotNull(problem.detail?.let { c.getString(it) }) },
        yesLabel = R.string.skill_places_open_settings,
        noLabel = R.string.skill_places_not_now,
        yesNoData = deps.yesNo(ctx),
        nested = nested,
    ) { c ->
        when {
            deps.isLocked(c) -> GarLockedOutput()
            open(c) -> PlacesProblemOutput(PlacesProblem.PAGE_OPENED)
            else -> PlacesProblemOutput(PlacesProblem.PAGE_FAILED)
        }
    }

    /**
     * Goes to [kind] with the "Navigate with" app ([PlaceRoutes]): route planning for a point,
     * else the address's point found before or online, the app's own text route, or its search.
     * On a locked phone the route starts but the card shows no address.
     */
    suspend fun navigate(ctx: SkillContext, kind: SavedPlaceKind, place: SavedPlace): SkillOutput {
        val locked = deps.isLocked(ctx)
        val routes = PlaceRoutes(this)
        // an address saved before GAR wrote its numbers as digits gets them now
        val address = place.address?.let { NavigationSkill.cleanPlace(ctx.parserFormatter, it) }
        return routes.withApp(ctx, none = { NavigateSavedOutput(kind, place, RouteDone(MapResult.NO_APP), locked) }) { c, app ->
            routes.go(
                c, app, place.point, address, deps.label(c, kind), locked,
                keep = { k, point -> keepLookedUp(k, kind, place, point) },
            ) { done -> NavigateSavedOutput(kind, place, done, locked) }
        }
    }

    /** A point found online for [place]'s address is kept with it, unless the place changed meanwhile. */
    private suspend fun keepLookedUp(ctx: SkillContext, kind: SavedPlaceKind, place: SavedPlace, point: GeoPoint) {
        val saved = deps.saved(ctx)
        val now = saved.get(kind)
        if (now != null && now.point == null && now.address == place.address) {
            saved.set(kind, SavedPlace(place.address, point, lookedUp = true))
        }
    }

    /**
     * "Take me home": there; or, with none saved, "Use the home address on your contact card,
     * ...?" when the "You" card has one ([SavedPlaceSource.decide]), else "What's your home
     * address?". A locked phone with none saved gets "Unlock your phone first.": the card isn't
     * read and no address is said there.
     */
    suspend fun navigateTo(ctx: SkillContext, kind: SavedPlaceKind): SkillOutput {
        val saved = deps.saved(ctx).get(kind)
        val source = SavedPlaceSource.decide(
            saved = saved,
            locked = saved == null && deps.isLocked(ctx),
            contactsAccess = saved == null && deps.card(ctx).hasAccess(),
            cardAddresses = { deps.card(ctx).addresses(kind) },
        )
        return when (source) {
            is SavedPlaceSource.Saved -> navigate(ctx, kind, source.place)
            is SavedPlaceSource.Card -> if (source.addresses.size == 1) {
                offerCard(ctx, kind, source.addresses[0])
            } else {
                pickCard(ctx, kind, source.addresses)
            }
            SavedPlaceSource.Ask -> SavedPlaceAskOutput(this, kind, SavedPlacePurpose.NAVIGATE, nested = false)
            SavedPlaceSource.Locked -> GarLockedOutput()
        }
    }

    /**
     * "Use the work address on your contact card, 12 Main Street?": a yes saves it and goes there,
     * a no asks "What's your work address?" as if the card had none.
     */
    fun offerCard(ctx: SkillContext, kind: SavedPlaceKind, address: String): GarOfferOutput = GarOfferOutput(
        info = info,
        speech = { it.getString(R.string.skill_places_card_offer, it.getString(kind.label), address) },
        details = { listOf(it.getString(R.string.skill_places_card_offer_detail)) },
        yesLabel = R.string.skill_places_card_use,
        noLabel = R.string.skill_places_card_no,
        yesNoData = deps.yesNo(ctx),
        onNo = { SavedPlaceAskOutput(this, kind, SavedPlacePurpose.NAVIGATE, nested = true) },
    ) { c -> useCard(c, kind, address) }

    /**
     * GAR: "Which is your work address? 1 Infinite Loop, Cupertino, or 4 Oak Ave", when the "You"
     * card has several; picking one saves it and goes, "none" asks "What's your work address?".
     */
    fun pickCard(ctx: SkillContext, kind: SavedPlaceKind, addresses: List<String>): SkillOutput =
        GarPickOutput<String?>(
            info = info,
            options = addresses + null,
            question = { c ->
                c.getString(R.string.skill_places_which_own_address, c.getString(kind.label), ContactSay.joinedOr(c, addresses))
            },
            shown = { c, address -> address ?: c.getString(R.string.skill_places_none_of_these) },
            nameOf = { it ?: NONE_ANSWER },
        ) { c, address ->
            if (address == null) SavedPlaceAskOutput(this, kind, SavedPlacePurpose.NAVIGATE, nested = true) else useCard(c, kind, address)
        }

    private suspend fun useCard(ctx: SkillContext, kind: SavedPlaceKind, address: String): SkillOutput {
        if (deps.isLocked(ctx)) {
            return GarLockedOutput()
        }
        val place = SavedPlace(address, null)
        deps.saved(ctx).set(kind, place)
        return SavedPlaceOutput(kind, place, then = navigate(ctx, kind, place))
    }

    /**
     * The answer to "What's your home address?" or a "set my home address to ...": "here" (or
     * "use my current location") saves where the phone is (only a precise fix), anything else
     * the address as said.
     * For [SavedPlacePurpose.NAVIGATE] an address is gone to right away; a point is not, since the
     * user is there.
     */
    suspend fun save(
        ctx: SkillContext,
        kind: SavedPlaceKind,
        said: String,
        purpose: SavedPlacePurpose,
        nested: Boolean,
    ): SkillOutput {
        if (deps.isLocked(ctx)) {
            return GarLockedOutput()
        }
        if (isCancelPhrase(said)) {
            return GarCancelledOutput()
        }
        if (PlaceAnswers.isHere(said)) {
            return locate(ctx, withName = false, nested = nested) { found ->
                if (!found.fix.precise) {
                    // GAR: an approximate fix can be 2 km off, too far for every later "take me home"
                    offerPage(ctx, PlacesProblem.APPROXIMATE, nested) { deps.pages(it).openAppInfo() }
                } else {
                    val place = SavedPlace(null, found.fix.point)
                    deps.saved(ctx).set(kind, place)
                    SavedPlaceOutput(kind, place, then = null)
                }
            }
        }
        // "one twenty three main street" is kept as "123 main street", which maps apps find
        val address = PlaceAnswers.address(said)?.let { NavigationSkill.cleanPlace(ctx.parserFormatter, it) }
            ?.takeIf { it.isNotEmpty() }
            ?: return SavedPlaceAskOutput(this, kind, purpose, nested = true)
        val place = SavedPlace(address, null)
        deps.saved(ctx).set(kind, place)
        return SavedPlaceOutput(
            kind, place,
            then = if (purpose == SavedPlacePurpose.NAVIGATE) navigate(ctx, kind, place) else null,
        )
    }
}

/** The words "None of these" is picked by ("no", "none"), see [PlacesFlow.pickCard]. */
private const val NONE_ANSWER = "no none"

/** English answers to "What's your home address?". */
object PlaceAnswers {
    /** "here", "use my current location", "where I am now", "this place". */
    fun isHere(said: String): Boolean {
        val words = org.stypox.dicio.gar.call.normalizeAnswer(said).split(" ")
            .filter { it.isNotEmpty() && it !in HERE_FILLERS }
        return words.isNotEmpty() && words.joinToString(" ") in HERE
    }

    /**
     * The address in an answer, without its lead: "it's 12 main street" -> "12 main street", or
     * null when nothing is left.
     */
    fun address(said: String): String? {
        var text = said.trim().trimEnd('.', '!', '?').trim()
        LEADS.find(text)?.let { text = text.substring(it.range.last + 1) }
        return text.trim().takeIf { it.isNotEmpty() }
    }

    private val HERE_FILLERS = setOf(
        "use", "my", "the", "set", "it", "to", "as", "save", "please", "just", "right", "now",
        "it's", "its", "is", "i'm", "im", "am", "at", "this", "that", "okay", "ok",
    )
    private val HERE = setOf(
        "here", "current location", "location", "where i", "where i am", "place", "spot",
        "current position", "position", "where we are", "where i'm", "where im",
    )
    private val LEADS = Regex(
        "^(it's|it is|its|my address is|the address is|address is|my (home|work) address is|" +
            "i live at|i live on|i work at|i work on|at|on|set it to|change it to)\\s+",
        RegexOption.IGNORE_CASE,
    )
}
