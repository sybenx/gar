package org.stypox.dicio.skills.places

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import org.stypox.dicio.R
import java.net.URLEncoder
import java.util.Locale

/**
 * What a maps intent did: started route planning ([ROUTE]), drew a route the user starts with
 * Start ([ROUTE_DRAWN], CoMaps' `cm://route`), showed a pin or a search ([SHOWN]), or found no
 * maps app.
 */
enum class MapResult { ROUTE, ROUTE_DRAWN, SHOWN, NO_APP }

/**
 * GAR (gar:places). The maps intents, written for the apps on Aaron's phone (checked against
 * their public sources at their 2026-11-02 HEAD; links in gar/DESIGN.md "Places, camera and
 * screen"):
 * - OsmAnd (`net.osmand.plus`, `net.osmand`): `google.navigation:q=<lat>,<lon>` opens route
 *   planning from the current position (`IntentHelper.parseNavigationIntent`); a text address
 *   routes with `osmand.api://navigate_search` (`ExternalApiHelper`, an offline search near a
 *   point, no permission needed); `google.navigation:q=<text>` is refused.
 * - Organic Maps (`app.organicmaps`): `android.intent.action.NAVIGATE` with `geo:<lat>,<lon>`
 *   prepares a route from My Position (`GoogleAssistantIntentHandler`, 2026 releases). Its
 *   handler reads a query parameter first, which throws on an opaque `geo:` Uri and drops to a
 *   pin, so the Uri is built hierarchically. Older versions: `om://route` as CoMaps.
 * - CoMaps (`app.comaps.fdroid`): no phone intent routes from My Position (its NAVIGATE filter is
 *   Android Auto's). `cm://route?sll=..&saddr=..&dll=..&daddr=..&type=vehicle` draws a route from
 *   a given start, so GAR sends it only with a precise fix at most 2 minutes old; Start then
 *   offers to reroute from the current position. Otherwise a pin.
 * - Magic Earth (`com.generalmagic.magicearth`, closed): `magicearth://?drive_to&lat=&lon=` and
 *   `magicearth://?daddr=<text>` from its URL scheme page (now gone). Android matches only the
 *   `magicearth` scheme, not `drive_to`, so they stay off until seen working on the phone
 *   ([MapLinks.MAGIC_EARTH_ROUTES_VERIFIED]); until then Magic Earth gets a pin.
 * - Vela (`app.vela`, GPL-3.0): `google.navigation:q=<lat>,<lon>` opens its route chooser on the
 *   point from where the user is, and `google.navigation:q=<text>` searches the text and opens the
 *   chooser on the first hit (`MapLinkParser.parseNavigation`, `MapViewModel.openDirectionsLink`).
 * - Divested's "Maps" (`us.spotco.maps`, Google Maps on the web): a google.com/maps/dir link.
 * Every intent names its app (`setPackage`), so Android's picker never shows: GAR asks itself
 * ([MapAppPickOutput]); only [search] and [show] without an app are left to Android.
 */
interface MapLauncher {
    /** The installed maps apps (the ones that open `geo:`), what each does probed, not assumed. */
    fun apps(): List<MapApp>

    /** The package that opens `geo:` links without asking, or null when Android would ask. */
    fun defaultApp(): String?

    /**
     * Route planning to [point] in [app], or the pin labelled [label] where it can't.
     * @param fix a precise fix at most 2 minutes old, for [RouteSupport.ROUTE_FROM_FIX] apps; null
     * gives them a pin
     */
    fun route(app: MapApp, point: GeoPoint, label: String, fix: GeoPoint?): MapResult

    /**
     * Route planning to a text [address] where [app] does that itself ([MapApp.text]); null when
     * it can't. [near] is where OsmAnd searches around.
     */
    fun routeText(app: MapApp, address: String, near: GeoPoint?): MapResult?

    /** The maps app's search for [query] ("coffee", an address), in [app] when given. */
    fun search(query: String, app: MapApp? = null): MapResult

    /** A pin at [point] labelled [label], in [app] when given. */
    fun show(point: GeoPoint, label: String, app: MapApp? = null): MapResult

    companion object {
        fun of(context: Context): MapLauncher = AndroidMapLauncher(context)
    }
}

object MapLinks {
    const val OSMAND = "net.osmand.plus"
    const val OSMAND_FREE = "net.osmand"
    const val MAGIC_EARTH = "com.generalmagic.magicearth"
    const val VELA = "app.vela"

    /**
     * Whether Magic Earth's `drive_to` and `daddr` links were seen routing on the Pixel. Its
     * resolving `magicearth:` proves only the scheme, so until then it gets a pin and "Tap Route."
     */
    const val MAGIC_EARTH_ROUTES_VERIFIED = false

    /** `geo:<lat>,<lon>?q=<lat>,<lon>(<label>)`: a labelled pin, which every maps app takes. */
    fun pin(point: GeoPoint, label: String): String {
        val at = coordinates(point)
        return "geo:$at?q=$at(${encode(label.replace(Regex("[()]"), ""))})"
    }

    /**
     * `geo:0,0?q=<query>`: the maps app's search, as Dicio's navigation skill sends it but
     * encoded, so "AT&T store" isn't cut at the `&` and "#" isn't taken for a fragment.
     */
    fun search(query: String): String = "geo:0,0?q=${encode(query)}"

    /**
     * [text] for a `geo:` query: percent-encoded, with `%20` for a space (not every maps app reads
     * `+` as one). Plain JVM, unlike `Uri.encode`, so tests can call it.
     */
    fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** OsmAnd's and Vela's route planning (`google.navigation:q=<lat>,<lon>`). */
    fun osmAndRoute(point: GeoPoint): String = "google.navigation:q=${coordinates(point)}"

    /** Magic Earth's drive-to link, from its URL scheme page. */
    fun magicEarthRoute(point: GeoPoint): String =
        "magicearth://?drive_to&lat=${format(point.latitude)}&lon=${format(point.longitude)}"

    /** Organic Maps' NAVIGATE data, `geo:<lat>,<lon>` (built hierarchically on Android). */
    fun organicMapsNavigate(point: GeoPoint): String = "geo:${coordinates(point)}"

    /**
     * Organic Maps' and CoMaps' API route (`cm://route`, `om://route`): all five keys in this
     * order or the link is refused (`mwm_url.cpp`), names never empty.
     */
    fun apiRoute(scheme: String, fix: GeoPoint, point: GeoPoint, from: String, to: String): String =
        "$scheme://route?sll=${coordinates(fix)}&saddr=${encode(from.ifBlank { "-" })}" +
            "&dll=${coordinates(point)}&daddr=${encode(to.ifBlank { "-" })}&type=vehicle"

    /** OsmAnd's offline search for [text] around [near], then a car route to the first result. */
    fun osmAndSearchRoute(text: String, near: GeoPoint): String =
        "osmand.api://navigate_search?dest_search_query=${encode(text)}" +
            "&search_lat=${format(near.latitude)}&search_lon=${format(near.longitude)}" +
            "&profile=car&show_search_results=false"

    /** Vela's route to a text address: its search, then the route chooser on the first hit. */
    fun velaTextRoute(text: String): String = "google.navigation:q=${encode(text)}"

    /** Magic Earth's route to a text address. */
    fun magicEarthDaddr(text: String): String = "magicearth://?daddr=${encode(text)}"

    /** Google Maps' directions on the web, for Divested's "Maps". */
    fun webDirections(destination: String): String =
        "https://www.google.com/maps/dir/?api=1&destination=${encode(destination)}&travelmode=driving"

    /**
     * A link anyone can open, for a text: OpenStreetMap's map with a marker, which CoMaps and
     * OsmAnd open too (`https://www.openstreetmap.org/?mlat=..&mlon=..#map=17/..`).
     */
    fun osmLink(point: GeoPoint): String {
        val lat = format(point.latitude)
        val lon = format(point.longitude)
        return "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon"
    }

    fun coordinates(point: GeoPoint) = "${format(point.latitude)},${format(point.longitude)}"

    /** Five decimals, about a metre, with a dot whatever the locale. */
    fun format(degrees: Double): String =
        String.format(Locale.ROOT, "%.5f", degrees).trimEnd('0').trimEnd('.')
}

class AndroidMapLauncher(private val context: Context) : MapLauncher {
    private val packages: PackageManager get() = context.packageManager

    override fun apps(): List<MapApp> {
        val own = context.packageName
        return packages.queryIntentActivities(view(MapLinks.search("")), 0)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != own && it.packageName != "android" }
            .distinctBy { it.packageName }
            .map { info ->
                val pkg = info.packageName
                val kind = MapAppKind.of(pkg)
                val label = try {
                    packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    pkg
                }
                MapApp(pkg, label, kind, pointSupport(pkg, kind), routesText(pkg, kind))
            }
    }

    private fun pointSupport(pkg: String, kind: MapAppKind): RouteSupport {
        val probe = GeoPoint(1.0, 1.0)
        return when (kind) {
            MapAppKind.OSMAND -> if (takes(view(MapLinks.osmAndRoute(probe)).setPackage(pkg))) {
                RouteSupport.ROUTE
            } else {
                RouteSupport.PIN
            }
            MapAppKind.ORGANIC_MAPS -> when {
                takes(organicMapsNavigate(probe).setPackage(pkg)) -> RouteSupport.ROUTE
                takes(view(MapLinks.apiRoute("om", probe, probe, "-", "-")).setPackage(pkg)) ->
                    RouteSupport.ROUTE_FROM_FIX
                else -> RouteSupport.PIN
            }
            MapAppKind.COMAPS ->
                if (takes(view(MapLinks.apiRoute("cm", probe, probe, "-", "-")).setPackage(pkg))) {
                    RouteSupport.ROUTE_FROM_FIX
                } else {
                    RouteSupport.PIN
                }
            MapAppKind.MAGIC_EARTH -> if (MapLinks.MAGIC_EARTH_ROUTES_VERIFIED &&
                takes(view(MapLinks.magicEarthRoute(probe)).setPackage(pkg))
            ) {
                RouteSupport.ROUTE
            } else {
                RouteSupport.PIN
            }
            MapAppKind.VELA -> if (takes(view(MapLinks.osmAndRoute(probe)).setPackage(pkg))) {
                RouteSupport.ROUTE
            } else {
                RouteSupport.PIN
            }
            MapAppKind.GMAPS_WEB -> RouteSupport.ROUTE
            MapAppKind.OTHER -> RouteSupport.PIN
        }
    }

    private fun routesText(pkg: String, kind: MapAppKind): Boolean = when (kind) {
        MapAppKind.OSMAND -> takes(view(MapLinks.osmAndSearchRoute("x", GeoPoint(1.0, 1.0))).setPackage(pkg))
        MapAppKind.MAGIC_EARTH -> MapLinks.MAGIC_EARTH_ROUTES_VERIFIED &&
            takes(view(MapLinks.magicEarthDaddr("x")).setPackage(pkg))
        MapAppKind.VELA -> takes(view(MapLinks.velaTextRoute("x")).setPackage(pkg))
        MapAppKind.GMAPS_WEB -> true
        else -> false
    }

    override fun defaultApp(): String? {
        val resolved = packages.resolveActivity(view(MapLinks.search("")), PackageManager.MATCH_DEFAULT_ONLY)
            ?: return null
        val pkg = resolved.activityInfo?.packageName ?: return null
        return pkg.takeIf { it != "android" }
    }

    override fun route(app: MapApp, point: GeoPoint, label: String, fix: GeoPoint?): MapResult {
        val started = when (app.kind) {
            MapAppKind.OSMAND -> app.point == RouteSupport.ROUTE &&
                start(view(MapLinks.osmAndRoute(point)).setPackage(app.pkg))
            MapAppKind.ORGANIC_MAPS -> when {
                app.point == RouteSupport.ROUTE -> start(organicMapsNavigate(point).setPackage(app.pkg))
                app.point == RouteSupport.ROUTE_FROM_FIX && fix != null ->
                    return drawn(apiRoute("om", fix, point, label), app)
                else -> false
            }
            MapAppKind.COMAPS -> if (app.point == RouteSupport.ROUTE_FROM_FIX && fix != null) {
                return drawn(apiRoute("cm", fix, point, label), app)
            } else {
                false
            }
            MapAppKind.MAGIC_EARTH -> app.point == RouteSupport.ROUTE &&
                start(view(MapLinks.magicEarthRoute(point)).setPackage(app.pkg))
            MapAppKind.VELA -> app.point == RouteSupport.ROUTE &&
                start(view(MapLinks.osmAndRoute(point)).setPackage(app.pkg))
            MapAppKind.GMAPS_WEB -> start(view(MapLinks.webDirections(MapLinks.coordinates(point))).setPackage(app.pkg))
            MapAppKind.OTHER -> false
        }
        return if (started) MapResult.ROUTE else show(point, label, app)
    }

    private fun drawn(uri: String, app: MapApp): MapResult =
        if (start(view(uri).setPackage(app.pkg))) MapResult.ROUTE_DRAWN else MapResult.NO_APP

    private fun apiRoute(scheme: String, fix: GeoPoint, point: GeoPoint, label: String) =
        MapLinks.apiRoute(scheme, fix, point, context.getString(R.string.skill_places_my_location), label)

    override fun routeText(app: MapApp, address: String, near: GeoPoint?): MapResult? {
        if (!app.text) {
            return null
        }
        val uri = when (app.kind) {
            MapAppKind.OSMAND -> MapLinks.osmAndSearchRoute(address, near ?: return null)
            MapAppKind.MAGIC_EARTH -> MapLinks.magicEarthDaddr(address)
            MapAppKind.VELA -> MapLinks.velaTextRoute(address)
            MapAppKind.GMAPS_WEB -> MapLinks.webDirections(address)
            else -> return null
        }
        return if (start(view(uri).setPackage(app.pkg))) MapResult.ROUTE else null
    }

    override fun search(query: String, app: MapApp?): MapResult =
        if (start(view(MapLinks.search(query)).setPackage(app?.pkg))) MapResult.SHOWN else MapResult.NO_APP

    override fun show(point: GeoPoint, label: String, app: MapApp?): MapResult =
        if (start(view(MapLinks.pin(point, label)).setPackage(app?.pkg))) MapResult.SHOWN else MapResult.NO_APP

    /**
     * Organic Maps' NAVIGATE intent: the `geo:` Uri built hierarchically, so that its handler's
     * `getQueryParameter` returns null instead of throwing (and dropping to a pin).
     */
    private fun organicMapsNavigate(point: GeoPoint) = Intent(
        ACTION_NAVIGATE,
        Uri.Builder().scheme("geo").encodedPath(MapLinks.organicMapsNavigate(point).removePrefix("geo:")).build(),
    )

    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    private fun takes(intent: Intent): Boolean = packages.queryIntentActivities(intent, 0).isNotEmpty()

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No app takes ${intent.data}", e)
        false
    }

    companion object {
        private const val TAG = "AndroidMapLauncher"
        private const val ACTION_NAVIGATE = "android.intent.action.NAVIGATE"
    }
}
