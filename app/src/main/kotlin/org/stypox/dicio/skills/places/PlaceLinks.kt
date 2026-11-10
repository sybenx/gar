package org.stypox.dicio.skills.places

import java.net.URLDecoder
import kotlin.math.abs

/** A point found in shared or pasted text, with the place's name when the text had one. */
data class SharedPlace(val point: GeoPoint, val name: String?)

/**
 * GAR (gar:places). The map point in what a maps app shares ("Share" on a place) or the user
 * pastes into the "Home and work" setting, as each app writes it (checked in their sources,
 * 2026-11-02, see gar/DESIGN.md "Places, camera and screen"):
 * - OsmAnd: `Name\nAddress\nLocation: geo:<lat>,<lon>?z=<z>&q=<lat>,<lon>(<label>)\nhttps://osmand.net/map?pin=<lat>,<lon>#<z>/<lat>/<lon>`
 * - Organic Maps: `Name\nAddress\n48.858093, 2.294694\nhttps://omaps.app/w4CNuoc9QN/Eiffel_Tower`
 *   (a ge0 short link, [Ge0]); older versions CoMaps' sentence
 * - CoMaps: `Follow this link https://comaps.at/<ge0>/<Name> or paste these coordinates
 *   geo:<lat>,<lon>?z=<z>&q=<lat>,<lon>(<Name>) into your maps app to see this location.`
 * - Magic Earth: `https://magicearth.com/?show_on_map&lat=<lat>&lon=<lon>&name=...`
 * - openstreetmap.org (`?mlat=&mlon=`, `#map=<z>/<lat>/<lon>`), Google Maps links with the point
 *   in them, and a bare "40.71278, -74.00597" (or "40.7128° N, 74.0060° W").
 * Short links that need the network (`maps.app.goo.gl`, `osm.org/go/`) give no point. Plain JVM.
 */
object PlaceLinks {
    /**
     * Whether [text] is a map link that only names a place ("https://www.google.com/maps?cid=..",
     * `maps.app.goo.gl`): no point in it, and finding one needs Google. Vela shares a cid link
     * for a business; its place Share menu also has a geo: pin and coordinates.
     */
    fun namesWithoutPoint(text: String): Boolean = parse(text) == null && NAME_ONLY.containsMatchIn(text)

    /** The first point in [text], tried in the order above, or null. */
    fun parse(text: String): SharedPlace? {
        val found = geo(text) ?: ge0(text) ?: osmAnd(text) ?: openStreetMap(text) ?: magicEarth(text)
            ?: google(text) ?: bare(text) ?: return null
        return SharedPlace(found.point, found.name ?: firstLineName(text))
    }

    /** `geo:<lat>,<lon>`, or the `q=<lat>,<lon>(<label>)` point of `geo:0,0?q=...`. */
    private fun geo(text: String): SharedPlace? {
        for (match in GEO.findAll(text)) {
            val query = match.groupValues[3]
            val q = Q_POINT.find(query)
            val label = q?.groupValues?.get(3)?.let(::decode)?.takeIf { it.isNotBlank() }
            q?.let { point(it.groupValues[1], it.groupValues[2]) }?.let { return SharedPlace(it, label) }
            point(match.groupValues[1], match.groupValues[2])?.let { return SharedPlace(it, label) }
        }
        return null
    }

    /** Organic Maps' and CoMaps' links: a ge0 code, clear `<lat>,<lon>`, or the API's `ll=`/`dll=`. */
    private fun ge0(text: String): SharedPlace? {
        for (match in GE0_LINK.findAll(text)) {
            val rest = match.groupValues[1]
            CLEAR.find(rest)?.takeIf { it.range.first == 0 }?.let { clear ->
                point(clear.groupValues[1], clear.groupValues[2])?.let {
                    return SharedPlace(it, clear.groupValues[3].takeIf { n -> n.isNotEmpty() }?.let(Ge0::name))
                }
            }
            val word = rest.takeWhile { it.isLetterOrDigit() }.lowercase()
            if (word in API_WORDS) {
                val param = Regex("[?&]d?ll=(-?[0-9.]+),(-?[0-9.]+)").find(rest) ?: continue
                val name = Regex("[?&](?:n|daddr)=([^&\\s]+)").find(rest)?.groupValues?.get(1)?.let(::decode)
                point(param.groupValues[1], param.groupValues[2])?.let { return SharedPlace(it, name) }
                continue
            }
            Ge0.decode(rest)?.let { (point, name) -> if (valid(point)) return SharedPlace(point, name) }
        }
        return null
    }

    /** `osmand.net/map?pin=<lat>,<lon>` or its `#<z>/<lat>/<lon>`. */
    private fun osmAnd(text: String): SharedPlace? {
        for (match in OSMAND_LINK.findAll(text)) {
            val link = match.value
            Regex("[?&]pin=(-?[0-9.]+),(-?[0-9.]+)").find(link)?.let { pin ->
                point(pin.groupValues[1], pin.groupValues[2])?.let { return SharedPlace(it, null) }
            }
            Regex("#[0-9.]+/(-?[0-9.]+)/(-?[0-9.]+)").find(link)?.let { hash ->
                point(hash.groupValues[1], hash.groupValues[2])?.let { return SharedPlace(it, null) }
            }
        }
        return null
    }

    /** openstreetmap.org and osm.org: the marker `?mlat=&mlon=`, else the view `#map=<z>/<lat>/<lon>`. */
    private fun openStreetMap(text: String): SharedPlace? {
        for (match in OSM_LINK.findAll(text)) {
            val link = match.value
            val lat = param(link, "mlat")
            val lon = param(link, "mlon")
            if (lat != null && lon != null) {
                point(lat, lon)?.let { return SharedPlace(it, null) }
            }
            Regex("#map=[0-9.]+/(-?[0-9.]+)/(-?[0-9.]+)").find(link)?.let { hash ->
                point(hash.groupValues[1], hash.groupValues[2])?.let { return SharedPlace(it, null) }
            }
        }
        return null
    }

    /** `magicearth.com/?show_on_map&lat=<lat>&lon=<lon>&name=<name>`. */
    private fun magicEarth(text: String): SharedPlace? {
        for (match in MAGIC_EARTH_LINK.findAll(text)) {
            val link = match.value
            val lat = param(link, "lat") ?: continue
            val lon = param(link, "lon") ?: continue
            point(lat, lon)?.let { return SharedPlace(it, param(link, "name")?.let(::decode)) }
        }
        return null
    }

    /**
     * Google Maps links with the point in them: `@lat,lon`, `q=`, `destination=`, `/place/lat,lon`,
     * and Vela's `search/?api=1&query=<name> <lat>,<lon>` (the name kept).
     */
    private fun google(text: String): SharedPlace? {
        for (match in GOOGLE_LINK.findAll(text)) {
            val link = decode(match.value)
            GOOGLE_NAMED_QUERY.find(link)?.let { found ->
                point(found.groupValues[2], found.groupValues[3])?.let {
                    return SharedPlace(it, found.groupValues[1].trim().takeIf { name -> name.length <= MAX_NAME })
                }
            }
            for (pattern in GOOGLE_POINTS) {
                pattern.find(link)?.let { found ->
                    point(found.groupValues[1], found.groupValues[2])?.let { return SharedPlace(it, null) }
                }
            }
        }
        return null
    }

    /** "40.71278, -74.00597" or "40.7128° N, 74.0060° W": both with decimals, so "12 Main St, 5" isn't one. */
    private fun bare(text: String): SharedPlace? {
        for (match in BARE.findAll(text)) {
            var lat = match.groupValues[1].toDoubleOrNull() ?: continue
            var lon = match.groupValues[3].toDoubleOrNull() ?: continue
            if (match.groupValues[2].equals("S", ignoreCase = true)) lat = -abs(lat)
            if (match.groupValues[4].equals("W", ignoreCase = true)) lon = -abs(lon)
            GeoPoint(lat, lon).takeIf(::valid)?.let { return SharedPlace(it, null) }
        }
        return null
    }

    /**
     * The place's name from OsmAnd's and Organic Maps' first line, unless that line is the
     * coordinates, a link, a sentence of the app ("I am here on Organic Maps") or too long.
     */
    private fun firstLineName(text: String): String? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return line.takeIf {
            it.length <= MAX_NAME && !it.contains("://") && !it.contains("geo:") &&
                BARE.find(it) == null && APP_NAMES.none { app -> it.contains(app, ignoreCase = true) }
        }
    }

    /** A query parameter's raw value in [link], or null. */
    private fun param(link: String, name: String): String? =
        Regex("[?&#]$name=([^&#\\s]*)").find(link)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    private fun point(lat: String, lon: String): GeoPoint? {
        val point = GeoPoint(lat.toDoubleOrNull() ?: return null, lon.toDoubleOrNull() ?: return null)
        return point.takeIf(::valid)
    }

    /** On the globe and not 0,0 (`geo:0,0?q=` means "no point"). */
    fun valid(point: GeoPoint): Boolean = abs(point.latitude) <= 90 && abs(point.longitude) <= 180 &&
        !(point.latitude == 0.0 && point.longitude == 0.0)

    /** A label as the apps encode it: `%20` (CoMaps) or `+` (OsmAnd's URLEncoder) for a space. */
    private fun decode(text: String): String = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (e: IllegalArgumentException) {
        text
    }

    private const val MAX_NAME = 80
    private val GEO = Regex("geo:(-?[0-9.]+),(-?[0-9.]+)(?:;[^?\\s]*)?(\\?[^\\s]*)?", RegexOption.IGNORE_CASE)
    private val Q_POINT = Regex("[?&]q=(-?[0-9.]+),(-?[0-9.]+)(?:\\(([^)]*)\\))?")
    private val GE0_LINK = Regex(
        "(?:https?://(?:omaps\\.app|ge0\\.me|comaps\\.at)/|om://|ge0://|comaps://|cm://)([^\\s]+)",
        RegexOption.IGNORE_CASE,
    )
    private val CLEAR = Regex("(-?[0-9]{1,2}\\.[0-9]+),(-?[0-9]{1,3}\\.[0-9]+)(?:/([^?\\s]*))?")
    private val API_WORDS = setOf("map", "route", "search", "crosshair", "menu", "settings", "oauth2")
    private val OSMAND_LINK = Regex("https?://(?:www\\.)?osmand\\.net/(?:go|map)[^\\s]*", RegexOption.IGNORE_CASE)
    private val OSM_LINK = Regex("https?://(?:www\\.)?(?:openstreetmap\\.org|osm\\.org)/[^\\s]*", RegexOption.IGNORE_CASE)
    private val MAGIC_EARTH_LINK = Regex("https?://(?:www\\.)?magicearth\\.com/[^\\s]*", RegexOption.IGNORE_CASE)
    private val GOOGLE_LINK = Regex(
        "https?://(?:www\\.|maps\\.)?google\\.[a-z.]+/maps[^\\s]*|https?://maps\\.google\\.[a-z.]+/[^\\s]*",
        RegexOption.IGNORE_CASE,
    )
    /** Vela's place query: a name, a space, then the point ("Connect Transit 41.73,-111.83"). */
    private val GOOGLE_NAMED_QUERY = Regex("[?&]query=([^&]*?\\S)[ +](-?[0-9]{1,2}\\.[0-9]+),\\s*(-?[0-9]{1,3}\\.[0-9]+)(?![0-9])")
    private val GOOGLE_POINTS = listOf(
        Regex("[?&](?:destination|q|query|ll|daddr)=(-?[0-9.]+),\\s*(-?[0-9.]+)"),
        Regex("/place/(-?[0-9.]+),(-?[0-9.]+)"),
        Regex("@(-?[0-9.]+),(-?[0-9.]+)"),
    )
    private val BARE = Regex(
        "(-?[0-9]{1,2}\\.[0-9]+)\\s*°?\\s*([NS])?\\s*[,;]?\\s+(-?[0-9]{1,3}\\.[0-9]+)\\s*°?\\s*([EW])?(?![0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val NAME_ONLY = Regex(
        "https?://(?:www\\.|maps\\.)?google\\.[a-z.]+/maps[^\\s]*[?&]cid=|https?://maps\\.app\\.goo\\.gl/|https?://goo\\.gl/maps/",
        RegexOption.IGNORE_CASE,
    )
    private val APP_NAMES = listOf("Organic Maps", "CoMaps", "OsmAnd", "Magic Earth", "Follow this link")
}

/**
 * GAR (gar:places). Organic Maps' and CoMaps' ge0 short codes (`omaps.app/w4CNuoc9QN/Eiffel_Tower`),
 * as their `libs/ge0/parser.cpp` reads them: a zoom character, then 9 base64url characters each
 * holding 3 bits of latitude and 3 of longitude (most significant first, 30 bits each), then an
 * optional `/Name`.
 */
object Ge0 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val BITS = 30
    private const val CODE_LENGTH = 10
    private const val MAX_NAME = 256

    /** The point and name of a code without its prefix (`B4srhdHVVt/Name`), or null. */
    fun decode(code: String): Pair<GeoPoint, String?>? {
        if (code.length < CODE_LENGTH) {
            return null
        }
        if (ALPHABET.indexOf(code[0]) < 0) {
            return null
        }
        var lat = 0L
        var lon = 0L
        var shift = BITS - 3
        for (i in 1 until CODE_LENGTH) {
            val a = ALPHABET.indexOf(code[i])
            if (a < 0) {
                return null
            }
            val lat1 = ((a shr 5) and 1 shl 2) or ((a shr 3) and 1 shl 1) or ((a shr 1) and 1)
            val lon1 = ((a shr 4) and 1 shl 2) or ((a shr 2) and 1 shl 1) or (a and 1)
            lat = lat or (lat1.toLong() shl shift)
            lon = lon or (lon1.toLong() shl shift)
            shift -= 3
        }
        // the middle of the last cell
        val middle = 1L shl (3 * (BITS / 3 - (CODE_LENGTH - 1)) - 1)
        lat += middle
        lon += middle
        val max = (1L shl BITS) - 1
        val latitude = (lat.toDouble() / max * 180 - 90).coerceIn(-90.0, 90.0)
        val longitude = (lon.toDouble() / (max + 1.0) * 360 - 180).coerceIn(-180.0, 180.0)

        val rest = code.substring(CODE_LENGTH).substringBefore('?').substringBefore('#')
        val name = when {
            rest.isEmpty() -> null
            rest[0] != '/' -> return null
            else -> name(rest.substring(1).take(MAX_NAME)).takeIf { it.isNotBlank() }
        }
        return Pair(GeoPoint(latitude, longitude), name)
    }

    /** A ge0 name: URL-decoded, then `_` and space swapped (`DecodeName`). */
    fun name(raw: String): String {
        val decoded = try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            raw
        }
        return decoded.map { if (it == '_') ' ' else if (it == ' ') '_' else it }.joinToString("")
    }
}
