package org.stypox.dicio.skills.places

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.skills.navigation.NavigationOutput
import org.stypox.dicio.skills.website.WebAddress
import org.stypox.dicio.skills.website.WebsiteSkill

/**
 * GAR (gar:places). What Dicio's navigation skill does with a destination GAR knows more about,
 * for its sentences and the model's `navigate` calls alike (no new tool, gar/PARITY.md "Keeping
 * the model's prompt small"): home and work go to the saved place ([PlacesFlow.navigateTo]),
 * "coffee near me" searches "coffee" around the user, "reddit dot com" is a website, and
 * "Sarah's" or "Sarah" is a contact's address when the contacts have one ([ContactTrips]), but
 * not for "where is Sarah" (a route to her home is not what that asks).
 * Everything else is the maps app's search ([search]), in the "Navigate with" app.
 */
object NavigationPlaces {
    sealed interface Target {
        data class Saved(val kind: SavedPlaceKind) : Target
        data class Nearby(val thing: String) : Target
        data class Website(val address: WebAddress) : Target
        data class Person(val person: PersonTarget) : Target
    }

    /** What [where] is to GAR, or null for a place to search for as said; no person unless [people]. */
    fun target(where: String, people: Boolean = true): Target? =
        PlaceWords.savedPlace(where)?.let { Target.Saved(it) }
            ?: WebAddress.parse(where)?.let { Target.Website(it) }
            ?: PlaceWords.nearbyThing(where)?.let { Target.Nearby(it) }
            ?: PersonWords.person(where)?.takeIf { people }?.let { Target.Person(it) }

    /**
     * The output for [where], or null to let the navigation skill search for it.
     * @param people false for "where is .where.": no route to a contact's address
     */
    suspend fun output(
        ctx: SkillContext,
        info: SkillInfo,
        where: String,
        deps: PlacesDeps = PlacesDeps(),
        people: Boolean = true,
    ): SkillOutput? = when (val target = target(where, people)) {
        null -> null
        is Target.Saved -> PlacesFlow(info, deps).navigateTo(ctx, target.kind)
        is Target.Nearby -> PlaceRoutes(PlacesFlow(info, deps)).search(ctx, target.thing) { NearbyOutput(target.thing, it) }
        is Target.Website -> WebsiteSkill.outputFor(ctx, where)
        is Target.Person -> ContactTrips(PlacesFlow(info, deps)).output(ctx, where, target.person)
    }

    /**
     * Dicio's search for [place] (already cleaned up), in the "Navigate with" app: GAR asks which
     * maps app the first time, as for a route, instead of Android's chooser.
     */
    suspend fun search(ctx: SkillContext, info: SkillInfo, place: String, deps: PlacesDeps = PlacesDeps()): SkillOutput =
        PlaceRoutes(PlacesFlow(info, deps)).search(ctx, place) { result ->
            if (result == MapResult.NO_APP) PlacesProblemOutput(PlacesProblem.NO_MAPS_APP) else NavigationOutput(place)
        }
}
