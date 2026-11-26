package com.wasimaster.wmkeyboard.core.location

import java.net.URLDecoder

/** Where in some text a place was written, and how. */
data class ParsedLocation(
    val point: GeoPoint,
    /** A name the source carried with it ("Banani Lake" in a Google place link), or null. */
    val label: String?,
    val kind: Kind,
    /** The span in the scanned text, half-open, for highlighting and for de-duplicating. */
    val start: Int,
    val end: Int,
    /** The text as written, which is what a chip pastes back. */
    val text: String,
) {
    enum class Kind { LINK, GEO_URI, DEGREES, COORDINATES, PLUS_CODE }
}

/**
 * Finds the places people send each other (#350): map links from the apps
 * that make them, `geo:` URIs, coordinates in decimal or in degrees, and full
 * Plus codes. Pure and offline, so a shortened link (`maps.app.goo.gl/…`) is
 * not followed: it names no place until a server is asked, and the keyboard
 * does not ask.
 *
 * Leans towards missing a place rather than inventing one: a decimal pair
 * needs three decimals on each side and must land on the globe, so "1.5, 2.5"
 * in a recipe stays a recipe.
 */
object LocationParser {

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 88 KB across this file's patterns.
    private val URL by lazy { Regex("""(?:https?://|geo:)[^\s<>"'`]+""", RegexOption.IGNORE_CASE) }

    private const val NUM = """-?\d{1,3}(?:\.\d+)?"""

    /** Google's exact pin inside a place link's data blob. */
    private val GOOGLE_PIN by lazy { Regex("""!3d($NUM)!4d($NUM)""") }

    /** A query parameter carrying "lat,lon". */
    private val PAIR_PARAM by lazy {
        Regex(
            """[?&#](?:q|query|ll|sll|daddr|destination|center|coordinate|coordinates|pin|point|loc)=\s*($NUM)\s*,\s*($NUM)""",
            RegexOption.IGNORE_CASE,
        )
    }
    private val MLAT by lazy { Regex("""[?&#]mlat=($NUM)""", RegexOption.IGNORE_CASE) }
    private val MLON by lazy { Regex("""[?&#]mlon=($NUM)""", RegexOption.IGNORE_CASE) }

    /** Google's viewport, `/@lat,lon,15z`: where the map was looking, a fair answer when there is no pin. */
    private val AT_VIEWPORT by lazy { Regex("""/@($NUM),($NUM)""") }

    /** OpenStreetMap and OsmAnd's `#map=zoom/lat/lon`. */
    private val MAP_FRAGMENT by lazy { Regex("""#map=\d+(?:\.\d+)?/($NUM)/($NUM)""") }

    /** Bing's `cp=lat~lon`. */
    private val BING by lazy { Regex("""[?&]cp=($NUM)~($NUM)""", RegexOption.IGNORE_CASE) }

    private val GOOGLE_PLACE_NAME by lazy { Regex("""/maps/place/([^/@?#]+)""") }

    /** WM Keyboard's own card: `…/place/#lat,lon;n=name`. */
    private val CARD by lazy { Regex("""#($NUM),($NUM)(?:;n=([^;]*))?""") }

    private val GEO_URI by lazy {
        Regex(
            """geo:($NUM),($NUM)(?:[^?\s]*)(?:\?q=([^\s]*))?""",
            RegexOption.IGNORE_CASE,
        )
    }
    private val GEO_QUERY by lazy { Regex("""^($NUM)\s*,\s*($NUM)(?:\(([^)]*)\)?)?""") }

    private val DEGREES by lazy {
        Regex(
            """(\d{1,2})\s*°\s*(\d{1,2})\s*['′’]\s*(\d{1,2}(?:\.\d+)?)\s*(?:"|″|”|'')?\s*([NSns])[\s,;]+""" +
                """(\d{1,3})\s*°\s*(\d{1,2})\s*['′’]\s*(\d{1,2}(?:\.\d+)?)\s*(?:"|″|”|'')?\s*([EWew])""",
        )
    }

    /** "23.7806, 90.4193", or with hemisphere letters, "23.7806 N, 90.4193 E". */
    private val DECIMAL_PAIR by lazy {
        Regex(
            """(?<![\w.\-])(-?\d{1,2}\.\d{3,})\s*°?\s*([NSns])?\s*[,;\s]\s*(-?\d{1,3}\.\d{3,})\s*°?\s*([EWew])?(?![\w.])""",
        )
    }

    private val PLUS_CODE by lazy {
        Regex(
            """(?<![\w+])[23456789CFGHJMPQRVWXcfghjmpqrvwx]{8}\+[23456789CFGHJMPQRVWXcfghjmpqrvwx]{2,7}(?!\w)""",
        )
    }

    /** Every place in [text], in the order they appear, never overlapping. */
    fun findAll(text: String): List<ParsedLocation> {
        val found = ArrayList<ParsedLocation>()
        val claimed = ArrayList<IntRange>()

        fun claim(location: ParsedLocation?) {
            if (location == null || !location.point.isValid) return
            if (claimed.any { it.first < location.end && location.start <= it.last }) return
            claimed += location.start until location.end
            found += location
        }

        for (match in URL.findAll(text)) {
            val raw = trimTail(match.value)
            val start = match.range.first
            val parsed = parseUrl(raw)
            if (parsed == null) {
                // Any other link is spoken for too: numbers inside a URL that
                // is not a map are an id, not a place.
                claimed += start until start + raw.length
                continue
            }
            val (point, label, kind) = parsed
            claim(ParsedLocation(point, label, kind, start, start + raw.length, raw))
        }
        for (match in DEGREES.findAll(text)) {
            val (d1, m1, s1, h1, d2, m2, s2, h2) = match.destructured
            val lat = dms(d1, m1, s1, h1) ?: continue
            val lon = dms(d2, m2, s2, h2) ?: continue
            claim(ParsedLocation(GeoPoint(lat, lon), null, ParsedLocation.Kind.DEGREES, match.range.first, match.range.last + 1, match.value))
        }
        for (match in PLUS_CODE.findAll(text)) {
            val point = PlusCode.decode(match.value) ?: continue
            claim(ParsedLocation(point, null, ParsedLocation.Kind.PLUS_CODE, match.range.first, match.range.last + 1, match.value))
        }
        for (match in DECIMAL_PAIR.findAll(text)) {
            val (latText, latHemi, lonText, lonHemi) = match.destructured
            var lat = latText.toDoubleOrNull() ?: continue
            var lon = lonText.toDoubleOrNull() ?: continue
            if (latHemi.equals("S", ignoreCase = true)) lat = -kotlin.math.abs(lat)
            if (lonHemi.equals("W", ignoreCase = true)) lon = -kotlin.math.abs(lon)
            claim(ParsedLocation(GeoPoint(lat, lon), null, ParsedLocation.Kind.COORDINATES, match.range.first, match.range.last + 1, match.value))
        }
        return found.sortedBy { it.start }
    }

    fun first(text: String): ParsedLocation? = findAll(text).firstOrNull()

    /** A single map link or `geo:` URI, or null when it names no place this can read. */
    fun parseUrl(url: String): Triple<GeoPoint, String?, ParsedLocation.Kind>? {
        if (url.startsWith("geo:", ignoreCase = true)) return parseGeo(url)?.let { Triple(it.first, it.second, ParsedLocation.Kind.GEO_URI) }
        if (url.startsWith(LocationFormatter.CARD_BASE, ignoreCase = true)) {
            // Matched before decoding: the name is percent-encoded and may hold a comma.
            val match = CARD.find(url) ?: return null
            val point = match.pair() ?: return null
            val label = match.groupValues[3].takeIf { it.isNotEmpty() }?.let(::decode)?.trim()?.ifEmpty { null }
            return Triple(point, label, ParsedLocation.Kind.LINK)
        }
        val decoded = decode(url)
        if (!isMapLink(decoded)) return null
        val point = GOOGLE_PIN.find(decoded)?.pair()
            ?: PAIR_PARAM.find(decoded)?.pair()
            ?: mlatMlon(decoded)
            ?: MAP_FRAGMENT.find(decoded)?.pair()
            ?: BING.find(decoded)?.pair()
            ?: AT_VIEWPORT.find(decoded)?.pair()
            ?: return null
        val label = GOOGLE_PLACE_NAME.find(decoded)?.groupValues?.get(1)
            ?.replace('+', ' ')?.trim()?.takeIf { it.isNotEmpty() && !it.first().isDigit() && it.first() != '-' }
        return Triple(point, label, ParsedLocation.Kind.LINK)
    }

    private fun parseGeo(uri: String): Pair<GeoPoint, String?>? {
        val match = GEO_URI.find(uri) ?: return null
        val (latText, lonText, query) = match.destructured
        val lat = latText.toDoubleOrNull() ?: return null
        val lon = lonText.toDoubleOrNull() ?: return null
        val q = decode(query)
        val fromQuery = GEO_QUERY.find(q)
        // geo:0,0?q=lat,lon(label) is how Android apps share a pin.
        if (fromQuery != null) {
            val (qLat, qLon, label) = fromQuery.destructured
            val point = GeoPoint(qLat.toDouble(), qLon.toDouble())
            if (lat == 0.0 && lon == 0.0 || point.isValid) return point to label.trim().ifEmpty { null }
        }
        if (lat == 0.0 && lon == 0.0) return null
        return GeoPoint(lat, lon) to q.trim().takeIf { it.isNotEmpty() && !it.first().isDigit() }
    }

    private fun isMapLink(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        val path = url.substringAfter("://").substringAfter('/', "")
        return host.startsWith("maps.") || host.contains("openstreetmap.") || host == "osm.org" ||
            host.endsWith("osmand.net") || host.endsWith("waze.com") || host.endsWith("bing.com") && path.startsWith("maps") ||
            (host.contains("google.") && (path.startsWith("maps") || host.startsWith("maps."))) ||
            host.endsWith("apple.com") && host.startsWith("maps")
    }

    private fun mlatMlon(url: String): GeoPoint? {
        val lat = MLAT.find(url)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val lon = MLON.find(url)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return GeoPoint(lat, lon)
    }

    private fun MatchResult.pair(): GeoPoint? {
        val lat = groupValues[1].toDoubleOrNull() ?: return null
        val lon = groupValues[2].toDoubleOrNull() ?: return null
        return GeoPoint(lat, lon).takeIf { it.isValid }
    }

    private fun dms(degrees: String, minutes: String, seconds: String, hemisphere: String): Double? {
        val d = degrees.toIntOrNull() ?: return null
        val m = minutes.toIntOrNull()?.takeIf { it < 60 } ?: return null
        val s = seconds.toDoubleOrNull()?.takeIf { it < 60 } ?: return null
        val value = d + m / 60.0 + s / 3600.0
        return if (hemisphere.equals("S", true) || hemisphere.equals("W", true)) -value else value
    }

    private fun decode(text: String): String =
        runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text).replace("%2B", "+")

    /**
     * Drops the sentence punctuation a link match swallows. A closing bracket
     * goes only when nothing in the link opened it, so `geo:…(Home)` keeps its
     * label's bracket and "(see geo:1,2)" loses the sentence's.
     */
    private fun trimTail(raw: String): String {
        var link = raw.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"', '”', '’')
        while (link.endsWith(')') || link.endsWith(']')) {
            val close = link.last()
            val open = if (close == ')') '(' else '['
            if (link.count { it == open } >= link.count { it == close }) break
            link = link.dropLast(1).trimEnd('.', ',', ';', ':', '!', '?')
        }
        return link
    }
}
