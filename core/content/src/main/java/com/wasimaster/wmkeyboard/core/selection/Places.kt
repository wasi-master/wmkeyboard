package com.wasimaster.wmkeyboard.core.selection

import java.net.URLEncoder
import java.util.Locale

data class LatLng(val lat: Double, val lng: Double)

sealed class Place {
    data class Coordinates(val point: LatLng) : Place()
    data class Address(val text: String) : Place()
}

/**
 * Whether a selection names a place a map can find.
 *
 * Coordinates are parsed exactly and range-checked. An address is a guess:
 * one line with a house number and a street word in it, or a postcode after
 * a comma. The guess is tuned to miss rather than to fire on a phone number,
 * a date or "I have 3 books", because a Map chip on those reads as broken.
 */
object Places {

    const val MAX_ADDRESS_LENGTH = 120

    private const val NUMBER = """[-+]?\d{1,3}(?:\.\d+)?"""
    private const val LAT_LABEL = """(?:lat(?:itude)?[:\s]*)?"""
    private const val LNG_LABEL = """(?:l(?:ng|on|ong)(?:gitude)?[:\s]*)?"""

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 78 KB across this file's patterns.
    private val DECIMAL by lazy {
        Regex(
            """\(?\s*$LAT_LABEL($NUMBER)\s*°?\s*([nsew])?\s*[,;\s]\s*$LNG_LABEL($NUMBER)\s*°?\s*([nsew])?\s*\)?""",
            RegexOption.IGNORE_CASE,
        )
    }
    private const val DMS_ONE = """(\d{1,3})°\s*(\d{1,2})['′]\s*(?:(\d{1,2}(?:\.\d+)?)["″])?\s*([nsew])"""
    private val DMS by lazy { Regex("""$DMS_ONE\s*[,;\s]\s*$DMS_ONE""", RegexOption.IGNORE_CASE) }

    private val STRONG = setOf(
        "road", "street", "avenue", "lane", "sector", "block", "house", "plot", "flat", "floor",
        "gali", "goli", "mor", "bazar", "bazaar", "nagar", "para", "colony", "boulevard", "drive",
        "highway", "square", "market", "tower", "plaza", "apartment", "suite", "unit", "building",
        "complex", "mansion", "villa", "residence", "terrace", "crescent", "thana", "upazila",
        "district", "zilla", "union", "p.o.", "post office",
        "রোড", "সড়ক", "রাস্তা", "গলি", "মোড়", "বাজার", "নগর", "পাড়া", "সেক্টর", "ব্লক", "বাড়ি", "বাসা",
        "প্লট", "ফ্ল্যাট", "তলা", "টাওয়ার", "মার্কেট", "থানা", "জেলা", "উপজেলা", "ইউনিয়ন", "ডাকঘর",
        "লেন", "এভিনিউ", "কলোনি", "মহল্লা",
    )
    private val WEAK_STREET by lazy { Regex("""\b\d+[a-z]?\s+(?:[^\s,]+\s+){1,3}(?:rd|st|ave|ln|blvd|dr|hwy|sq|ct|pl)\b""") }
    private val WEAK_UNIT by lazy { Regex("""\b(?:apt|bldg)\.?\s*#?\d""") }
    private val POSTCODE by lazy { Regex(""",.*\b(?:\d{4,6}|[a-z]{1,2}\d[a-z\d]?\s?\d[a-z]{2})\b""") }
    private val LONG_DIGITS by lazy { Regex("""\d{7,}""") }
    private val CLOCK by lazy { Regex("""\b\d{1,2}(?::\d{2})?\s*(?:am|pm)\b""") }
    private val WORD by lazy { Regex("""[\p{L}\p{M}.]+""") }

    fun parseCoordinates(text: String): LatLng? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 64) return null
        DMS.matchEntire(t)?.let { m ->
            val a = dms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            val b = dms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
            return pair(a, b, m.groupValues[4], m.groupValues[8])
        }
        val m = DECIMAL.matchEntire(t) ?: return null
        val first = m.groupValues[1].toDoubleOrNull() ?: return null
        val second = m.groupValues[3].toDoubleOrNull() ?: return null
        val letters = m.groupValues[2].isNotEmpty() || m.groupValues[4].isNotEmpty()
        if (!letters && !t.contains('°')) {
            // Two prices or two scores look exactly like this; real
            // coordinates come with the decimals a GPS writes.
            val d1 = decimals(m.groupValues[1])
            val d2 = decimals(m.groupValues[3])
            if (d1 < 2 || d2 < 2 || maxOf(d1, d2) < 3) return null
        }
        return pair(signed(first, m.groupValues[2]), signed(second, m.groupValues[4]), m.groupValues[2], m.groupValues[4])
    }

    fun looksLikeAddress(text: String, isDate: (String) -> Boolean = { false }): Boolean {
        val t = text.trim()
        if (t.length !in 8..MAX_ADDRESS_LENGTH || t.contains('\n')) return false
        val ascii = DigitScripts.toAsciiDigits(t) ?: t
        if (ascii.none { it.isDigit() }) return false
        if (ascii.split(Regex("""[\s,]+""")).count { it.isNotEmpty() } < 3) return false
        if (LONG_DIGITS.containsMatchIn(ascii) || SelectionMacros.detect(ascii) != SelectionKind.TEXT) return false
        // The keyword table is stored with nukta letters decomposed; read the
        // text the same way so a precomposed word still counts.
        val lower = ascii.lowercase(Locale.ROOT)
            .replace("\u09DF", "\u09AF\u09BC").replace("\u09DC", "\u09A1\u09BC").replace("\u09DD", "\u09A2\u09BC")
        if (CLOCK.containsMatchIn(lower) || isDate(ascii)) return false
        val words = WORD.findAll(lower).map { it.value }.toSet()
        if (words.any { it in STRONG } || STRONG.any { it.contains(' ') && lower.contains(it) }) return true
        if (WEAK_STREET.containsMatchIn(lower) || WEAK_UNIT.containsMatchIn(lower)) return true
        return POSTCODE.containsMatchIn(lower)
    }

    /** Coordinates first, then the address guess. */
    fun detect(text: String, isDate: (String) -> Boolean = { false }): Place? {
        parseCoordinates(text)?.let { return Place.Coordinates(it) }
        return if (looksLikeAddress(text, isDate)) Place.Address(text.trim()) else null
    }

    fun geoUri(place: Place): String = when (place) {
        is Place.Coordinates -> {
            val lat = number(place.point.lat)
            val lng = number(place.point.lng)
            "geo:$lat,$lng?q=$lat,$lng"
        }
        is Place.Address -> "geo:0,0?q=" + URLEncoder.encode(place.text, "UTF-8")
    }

    private fun number(value: Double): String =
        "%.6f".format(Locale.US, value).trimEnd('0').trimEnd('.')

    private fun decimals(number: String): Int {
        val dot = number.indexOf('.')
        return if (dot < 0) 0 else number.length - dot - 1
    }

    private fun signed(value: Double, letter: String): Double =
        if (letter.equals("s", true) || letter.equals("w", true)) -abs(value) else value

    private fun abs(v: Double) = if (v < 0) -v else v

    private fun dms(deg: String, min: String, sec: String, letter: String): Double {
        val value = deg.toDouble() + min.toDouble() / 60.0 + (sec.toDoubleOrNull() ?: 0.0) / 3600.0
        return signed(value, letter)
    }

    /** Which number is the latitude: the one marked N/S when marked, else the first. */
    private fun pair(a: Double, b: Double, la: String, lb: String): LatLng? {
        val aIsLng = la.equals("e", true) || la.equals("w", true)
        val bIsLat = lb.equals("n", true) || lb.equals("s", true)
        val (lat, lng) = if (aIsLng || bIsLat) b to a else a to b
        if (abs(lat) > 90.0 || abs(lng) > 180.0) return null
        return LatLng(lat, lng)
    }
}
