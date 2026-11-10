package org.stypox.dicio.skills.places

/** The maps apps GAR knows the intents of ([MapLinks]); anything else gets `geo:` pins. */
enum class MapAppKind {
    OSMAND, ORGANIC_MAPS, COMAPS, MAGIC_EARTH,

    /** Vela (`app.vela`): Google Maps' data in its own app; takes `google.navigation:`. */
    VELA,

    /** Divested Computing's "Maps" (`us.spotco.maps`): Google Maps on the web in a WebView, online only. */
    GMAPS_WEB,
    OTHER;

    companion object {
        fun of(pkg: String): MapAppKind = when {
            pkg == "net.osmand" || pkg.startsWith("net.osmand.") -> OSMAND
            pkg == "app.organicmaps" || pkg.startsWith("app.organicmaps.") -> ORGANIC_MAPS
            pkg == "app.comaps" || pkg.startsWith("app.comaps.") -> COMAPS
            pkg == MapLinks.MAGIC_EARTH -> MAGIC_EARTH
            pkg == MapLinks.VELA || pkg.startsWith("${MapLinks.VELA}.") -> VELA
            pkg == "us.spotco.maps" -> GMAPS_WEB
            else -> OTHER
        }
    }
}

/**
 * How far an app gets with a point: route planning from where the user is ([ROUTE]), a route
 * drawn from GAR's own recent fix that the app offers to start from the current position
 * ([ROUTE_FROM_FIX], CoMaps' `cm://route`), or a pin the user taps Route on ([PIN]).
 */
enum class RouteSupport { ROUTE, ROUTE_FROM_FIX, PIN }

/**
 * An installed maps app: its [pkg], [label] ("OsmAnd"), [kind], what it does with a point, and
 * whether it routes to a text address by itself ([text]: OsmAnd's offline `navigate_search`,
 * Magic Earth's `daddr`, Vela's `google.navigation:q=<text>`).
 */
data class MapApp(
    val pkg: String,
    val label: String,
    val kind: MapAppKind,
    val point: RouteSupport,
    val text: Boolean,
)

/** Which app "take me home" goes to, see [MapAppChoice.choose]. */
sealed interface NavigateWith {
    data class App(val app: MapApp) : NavigateWith

    /**
     * GAR asks which ([MapAppPickOutput]), so each app still gets its own route intent: the first
     * time, with "Always use this app" ([remember]), or every time when the setting says so.
     */
    data class Ask(val apps: List<MapApp>, val remember: Boolean) : NavigateWith

    data object NoApp : NavigateWith
}

/**
 * GAR (gar:places). The "Navigate with" setting's decision, tested on the JVM. GAR never picks a
 * maps app for the user: the app the user picked, else Android's default `geo:` app when one is
 * set (the user chose that too), else the only one installed, else GAR asks.
 */
object MapAppChoice {
    /** Nothing picked yet. "Automatic" was stored as "" too, so it now asks once. */
    const val NOT_CHOSEN = ""
    const val ASK = "ask"

    /**
     * @param setting `GarSettings.navigation_app`: [NOT_CHOSEN], [ASK] or a package; a package
     * that is no longer installed counts as not chosen
     * @param androidDefault the package that opens `geo:` links without asking, or null
     */
    fun choose(setting: String, installed: List<MapApp>, androidDefault: String?): NavigateWith {
        if (installed.isEmpty()) {
            return NavigateWith.NoApp
        }
        val ordered = ordered(installed)
        if (setting == ASK) {
            return if (ordered.size == 1) NavigateWith.App(ordered[0]) else NavigateWith.Ask(ordered, remember = false)
        }
        installed.firstOrNull { it.pkg == setting && setting != NOT_CHOSEN }?.let { return NavigateWith.App(it) }
        androidDefault(installed, androidDefault)?.let { return NavigateWith.App(it) }
        // nothing to choose between; a second app installed later asks
        return if (ordered.size == 1) NavigateWith.App(ordered[0]) else NavigateWith.Ask(ordered, remember = true)
    }

    /** Android's default `geo:` app among [installed], or null: the setting shows it. */
    fun androidDefault(installed: List<MapApp>, androidDefault: String?): MapApp? =
        installed.firstOrNull { it.pkg == androidDefault }

    /** The apps in lists: the ones that start a route first, Google Maps on the web last, then by name. */
    fun ordered(installed: List<MapApp>): List<MapApp> = installed.sortedWith(
        compareBy<MapApp>({ it.kind == MapAppKind.GMAPS_WEB }, { it.point.ordinal }, { it.label.lowercase() })
    )

    /**
     * The words an answer to "Which maps app?" is matched with: the label, and how Vosk and
     * Whisper write the names ("osm and", "co maps", "vella").
     */
    fun spokenNames(app: MapApp): List<String> = listOf(app.label) + when (app.kind) {
        MapAppKind.OSMAND -> listOf("osmand", "osm and")
        MapAppKind.COMAPS -> listOf("comaps", "co maps")
        MapAppKind.VELA -> listOf("vela", "vella")
        MapAppKind.MAGIC_EARTH -> listOf("magic earth")
        MapAppKind.ORGANIC_MAPS -> listOf("organic maps")
        else -> listOf()
    }
}
