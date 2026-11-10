package org.stypox.dicio.skills.places

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.gar.offer.GarOfferOutput
import org.stypox.dicio.util.getString

/** Why a lookup gave no point, said before the map search that replaces the route. */
enum class LookupProblem { NOT_FOUND, FAILED }

/**
 * How a trip ended in the maps app: the [result], whether it was a [searched] text instead of a
 * point, whether the point was [lookedUp] online (the card then credits OpenStreetMap), a lookup
 * [problem], and whether "Tap Route." is said this once ([sayTapRoute]).
 */
data class RouteDone(
    val result: MapResult,
    val searched: Boolean = false,
    val lookedUp: Boolean = false,
    val problem: LookupProblem? = null,
    val sayTapRoute: Boolean = false,
)

/**
 * GAR (gar:places). Getting from a saved place or a contact's address to the maps app's route
 * planning: the "Navigate with" app ([MapAppChoice], GAR's own "Which maps app?" when none is
 * chosen yet or for "Ask every time"),
 * then a point straight to its route intent, or a text address in this order: a point looked up
 * before, the app's own text route (OsmAnd, Magic Earth), "Find addresses online"
 * ([AddressLookup]; asked once, never on a locked phone), and last the app's search.
 */
class PlaceRoutes(private val flow: PlacesFlow) {
    private val deps get() = flow.deps

    /**
     * [go] with the app to use, after "Which maps app?" ([MapAppPickOutput]) when none is chosen
     * yet or the setting says to ask; [none] without one.
     */
    suspend fun withApp(
        ctx: SkillContext,
        none: () -> SkillOutput,
        go: suspend (SkillContext, MapApp) -> SkillOutput,
    ): SkillOutput {
        val maps = deps.maps(ctx)
        return when (val choice = MapAppChoice.choose(deps.prefs(ctx).app(), maps.apps(), maps.defaultApp())) {
            is NavigateWith.App -> go(ctx, choice.app)
            is NavigateWith.Ask -> MapAppPickOutput(
                info = flow.info,
                apps = choice.apps,
                remember = choice.remember,
                save = { c, app -> deps.prefs(c).setApp(app.pkg) },
                onPick = go,
            )
            NavigateWith.NoApp -> none()
        }
    }

    /**
     * The maps app's search for [query] ("coffee", "the airport") in the "Navigate with" app,
     * asked for as in [withApp]; [done] makes the answer from what happened.
     */
    suspend fun search(ctx: SkillContext, query: String, done: (MapResult) -> SkillOutput): SkillOutput =
        withApp(ctx, none = { done(MapResult.NO_APP) }) { c, app -> done(deps.maps(c).search(query, app)) }

    /**
     * To [point] when there is one, else to [address] (see the class), named [label] on a pin.
     * [keep] stores a point found online for [address] (with the saved place); [finish] makes the
     * answer. [locked]: the lookup question isn't asked.
     */
    suspend fun go(
        ctx: SkillContext,
        app: MapApp,
        point: GeoPoint?,
        address: String?,
        label: String,
        locked: Boolean,
        keep: suspend (SkillContext, GeoPoint) -> Unit,
        finish: (RouteDone) -> SkillOutput,
    ): SkillOutput {
        val maps = deps.maps(ctx)
        if (point != null) {
            return finish(tapRoute(ctx, routePoint(ctx, app, point, label)))
        }
        val text = address?.takeIf { it.isNotBlank() } ?: return finish(RouteDone(MapResult.NO_APP))
        deps.points(ctx).get(text)?.point?.let {
            return finish(tapRoute(ctx, routePoint(ctx, app, it, label).copy(lookedUp = true)))
        }
        maps.routeText(app, text, near(ctx))?.let { return finish(RouteDone(it)) }

        val prefs = deps.prefs(ctx)
        if (prefs.lookup()) {
            return lookUp(ctx, app, text, label, keep, finish)
        }
        if (locked || prefs.lookupAsked()) {
            return finish(search(ctx, app, text))
        }
        // asked once, whatever the answer (or none)
        prefs.setLookupAsked()
        return GarOfferOutput(
            info = flow.info,
            speech = { it.getString(R.string.skill_places_lookup_offer) },
            details = { listOf(it.getString(R.string.skill_places_lookup_offer_detail)) },
            yesLabel = R.string.skill_places_lookup_yes,
            noLabel = R.string.skill_places_lookup_no,
            yesNoData = deps.yesNo(ctx),
            onNo = { c -> finish(search(c, app, text)) },
        ) { c ->
            deps.prefs(c).setLookup(true)
            lookUp(c, app, text, label, keep, finish)
        }
    }

    private suspend fun lookUp(
        ctx: SkillContext,
        app: MapApp,
        text: String,
        label: String,
        keep: suspend (SkillContext, GeoPoint) -> Unit,
        finish: (RouteDone) -> SkillOutput,
    ): SkillOutput = when (val found = deps.lookup(ctx).find(text)) {
        is LookupResult.Found -> {
            keep(ctx, found.point)
            finish(tapRoute(ctx, routePoint(ctx, app, found.point, label).copy(lookedUp = true)))
        }
        LookupResult.NotFound -> finish(search(ctx, app, text).copy(problem = LookupProblem.NOT_FOUND))
        LookupResult.Failed -> finish(search(ctx, app, text).copy(problem = LookupProblem.FAILED))
        LookupResult.Paused -> finish(search(ctx, app, text))
    }

    private fun routePoint(ctx: SkillContext, app: MapApp, point: GeoPoint, label: String): RouteDone {
        // CoMaps draws its route from this point: only a precise fix of the last 2 minutes
        val fix = if (app.point == RouteSupport.ROUTE_FROM_FIX) {
            deps.location(ctx).lastKnown(FIX_MAX_AGE_MILLIS)?.takeIf { it.precise }?.point
        } else {
            null
        }
        return RouteDone(deps.maps(ctx).route(app, point, label, fix))
    }

    private fun search(ctx: SkillContext, app: MapApp, text: String): RouteDone =
        RouteDone(deps.maps(ctx).search(text, app), searched = true)

    /** Where OsmAnd's text route searches around: the last fix of the day, else home. */
    private suspend fun near(ctx: SkillContext): GeoPoint? =
        deps.location(ctx).lastKnown(NEAR_MAX_AGE_MILLIS)?.point
            ?: deps.saved(ctx).get(SavedPlaceKind.HOME)?.point

    /** "Tap Route." is said after the first pin only; the card always shows it. */
    private suspend fun tapRoute(ctx: SkillContext, done: RouteDone): RouteDone {
        if (done.result != MapResult.SHOWN || done.searched) {
            return done
        }
        val prefs = deps.prefs(ctx)
        if (prefs.tapRouteSaid()) {
            return done
        }
        prefs.setTapRouteSaid()
        return done.copy(sayTapRoute = true)
    }

    companion object {
        const val FIX_MAX_AGE_MILLIS = 2 * 60_000L
        const val NEAR_MAX_AGE_MILLIS = 24 * 60 * 60_000L
    }
}
