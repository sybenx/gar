package org.stypox.dicio.skills.places

/** GAR (gar:places). Fakes the route tests share: no Android, no network. */

val OSMAND_APP = MapApp("net.osmand.plus", "OsmAnd", MapAppKind.OSMAND, RouteSupport.ROUTE, text = true)
val ORGANIC_APP = MapApp("app.organicmaps", "Organic Maps", MapAppKind.ORGANIC_MAPS, RouteSupport.ROUTE, text = false)
val COMAPS_APP = MapApp("app.comaps.fdroid", "CoMaps", MapAppKind.COMAPS, RouteSupport.ROUTE_FROM_FIX, text = false)
val MAGIC_APP = MapApp("com.generalmagic.magicearth", "Magic Earth", MapAppKind.MAGIC_EARTH, RouteSupport.PIN, text = false)
val VELA_APP = MapApp("app.vela", "Vela", MapAppKind.VELA, RouteSupport.ROUTE, text = true)
val WEB_APP = MapApp("us.spotco.maps", "Maps", MapAppKind.GMAPS_WEB, RouteSupport.ROUTE, text = true)

/**
 * Records what it was asked as "route <lat,lon> <label> <pkg>", "text <address> <pkg>",
 * "search <query> <pkg or ->", "show <lat,lon>"; routes as the app's [RouteSupport] says.
 */
class RecordingMaps(
    val installed: List<MapApp> = listOf(COMAPS_APP),
    val default: String? = null,
) : MapLauncher {
    val calls = mutableListOf<String>()

    override fun apps() = installed
    override fun defaultApp() = default

    override fun route(app: MapApp, point: GeoPoint, label: String, fix: GeoPoint?): MapResult {
        calls += "route ${MapLinks.coordinates(point)} $label ${app.pkg}" + (fix?.let { " from ${MapLinks.coordinates(it)}" } ?: "")
        return when {
            app.point == RouteSupport.ROUTE -> MapResult.ROUTE
            app.point == RouteSupport.ROUTE_FROM_FIX && fix != null -> MapResult.ROUTE_DRAWN
            else -> MapResult.SHOWN
        }
    }

    override fun routeText(app: MapApp, address: String, near: GeoPoint?): MapResult? {
        if (!app.text) return null
        calls += "text $address ${app.pkg}"
        return MapResult.ROUTE
    }

    override fun search(query: String, app: MapApp?): MapResult {
        calls += "search $query ${app?.pkg ?: "-"}"
        return MapResult.SHOWN
    }

    override fun show(point: GeoPoint, label: String, app: MapApp?): MapResult {
        calls += "show ${MapLinks.coordinates(point)}"
        return MapResult.SHOWN
    }
}

class FakePrefs(
    var app: String = MapAppChoice.NOT_CHOSEN,
    var lookup: Boolean = false,
    var asked: Boolean = true,
    var tapRoute: Boolean = false,
) : NavigationPrefs {
    override suspend fun app() = app
    override suspend fun setApp(pkg: String) {
        app = pkg
    }
    override suspend fun lookup() = lookup
    override suspend fun lookupAsked() = asked
    override suspend fun setLookup(on: Boolean) {
        lookup = on
        asked = true
    }
    override suspend fun setLookupAsked() {
        asked = true
    }
    override suspend fun tapRouteSaid() = tapRoute
    override suspend fun setTapRouteSaid() {
        tapRoute = true
    }
}

class MemoryPointStore : AddressPointStore {
    val points = mutableMapOf<String, CachedPoint>()
    var paused = 0L

    override suspend fun get(address: String) = points[AddressPointStore.key(address)]
    override suspend fun put(address: String, point: GeoPoint?, at: Long) {
        points[AddressPointStore.key(address)] = CachedPoint(point, at)
    }
    override suspend fun pausedUntil() = paused
    override suspend fun pause(until: Long) {
        paused = until
    }
}

/** An [AddressLookup] that answers [answer] and counts its calls. */
class FakeLookup(var answer: LookupResult = LookupResult.NotFound) : AddressLookup {
    val asked = mutableListOf<String>()
    override suspend fun find(address: String): LookupResult {
        asked += address
        return answer
    }
}
