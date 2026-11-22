package com.wasimaster.wmkeyboard.core.selection

import com.wasimaster.wmkeyboard.core.tools.CalendarSystems
import com.wasimaster.wmkeyboard.core.tools.DateSuggest
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A selection read as a moment: a date, a time, or both.
 *
 * [startMillis] is the instant in [zoneId], the zone the text was read in:
 * the one it named, else the device's. Without a time the hit is an all-day
 * event ending a day later; with one it ends an hour later, which is what a
 * calendar app wants handed to it. [ambiguousOrder] is set when `04/05` had
 * to be settled by the locale rather than by the numbers.
 */
data class DateTimeHit(
    val startMillis: Long,
    val endMillis: Long,
    val hasDate: Boolean,
    val hasTime: Boolean,
    val zoneId: String,
    val zoneNamed: Boolean,
    val ambiguousOrder: Boolean,
) {
    val allDay: Boolean get() = !hasTime
}

/**
 * Reads dates and times the way people write them in a message, without
 * `java.time` (minSdk 24): numeric dates in either order, month names in
 * English and Bengali, the forward phrases `DateSuggest` already knows (in
 * the locale's language and the text's script as well as English), and
 * clock times with or without am/pm. Everything is anchored to a caller's
 * `now`, zone and locale, so a test can pin every answer.
 */
object DateTimes {

    const val MAX_LENGTH = 64
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    private val WS = Regex("""\s+""")

    private const val TIME = """(\d{1,2})(?:([:.])(\d{2})(?::(\d{2}))?)?\s*(am|pm|a\.m\.|p\.m\.)?"""
    private const val BN_TIME = """(সকাল|ভোর|দুপুর|বিকাল|বিকেল|সন্ধ্যা|সন্ধ্যে|রাত|রাত্রি)?\s*(\d{1,2})(?::(\d{2}))?\s*টা(?:য়|র)?"""
    private const val SEP_BEFORE = """(?:^|(?<=\d)t|[\s,]+(?:at\s+)?|\s*@\s*)"""
    private const val SEP_AFTER = """(?:$|[\s,]+(?:on\s+)?)"""
    private val TIME_END = Regex("$SEP_BEFORE$TIME$")
    private val TIME_START = Regex("^$TIME$SEP_AFTER")
    private val BN_TIME_END = Regex("$SEP_BEFORE$BN_TIME$")
    private val BN_TIME_START = Regex("^$BN_TIME$SEP_AFTER")

    private val ZONE_UTC = Regex("""(?:^|\s)(utc|gmt|z)(?:\s*([+-]\d{1,2}(?::?\d{2})?))?$""")
    private val ZONE_OFFSET = Regex("""\s*([+-]\d{2}:?\d{2})$""")
    private val ZONE_ABBREV = Regex("""\s([a-z]{2,5})$""")
    private val OFFSET = Regex("""([+-])(\d{1,2})(?::?(\d{2}))?""")

    private val MONTHS: Map<String, Int> = buildMap {
        fun month(index: Int, vararg names: String) = names.forEach { put(it, index) }
        month(1, "january", "jan", "জানুয়ারি", "জানুয়ারী")
        month(2, "february", "feb", "ফেব্রুয়ারি", "ফেব্রুয়ারী")
        month(3, "march", "mar", "মার্চ")
        month(4, "april", "apr", "এপ্রিল")
        month(5, "may", "মে")
        month(6, "june", "jun", "জুন")
        month(7, "july", "jul", "জুলাই")
        month(8, "august", "aug", "আগস্ট", "অগাস্ট")
        month(9, "september", "sep", "sept", "সেপ্টেম্বর")
        month(10, "october", "oct", "অক্টোবর")
        month(11, "november", "nov", "নভেম্বর")
        month(12, "december", "dec", "ডিসেম্বর")
    }
    private val MONTH_NAMES = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    private val ISO = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
    private val NUMERIC = Regex("""(\d{1,2})([/.\-])(\d{1,2})\2(\d{4}|\d{2})""")
    private val DAY_MONTH = Regex("""(\d{1,2})(?:st|nd|rd|th|ই|শে|লা|রা|ঠা)?\s*(?:of\s+)?($MONTH_NAMES)(?:,?\s+(\d{4}))?""")
    private val MONTH_DAY = Regex("""($MONTH_NAMES)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?""")
    private val TODAY = setOf("today", "tonight", "আজ", "আজকে")
    /** The moment itself (#970): "now" in a message is a time like any other. */
    private val NOW = setOf("now", "right now", "time now", "the time now", "এখন", "এখনই")
    /** `Mon 14 Sep`: the weekday is decoration on a date that names its day. */
    private val WEEKDAY_PREFIX = Regex(
        """^(?:sun|sunday|mon|monday|tue|tues|tuesday|wed|wednesday|thu|thur|thurs|thursday|fri|friday|sat|saturday)[,\s]+(?=\d)""",
    )

    private class Time(val hour: Int, val minute: Int, val second: Int)
    private class Day(val year: Int, val month: Int, val day: Int, val ambiguous: Boolean)

    /** A zone abbreviation, with the id it usually means and the ones it means elsewhere. */
    private class Abbrev(val id: String, val byRegion: Map<String, String> = emptyMap())

    private val ABBREVS: Map<String, Abbrev> = mapOf(
        "bdt" to Abbrev("Asia/Dhaka"),
        "bst" to Abbrev("Asia/Dhaka", mapOf("Europe/" to "Europe/London")),
        "ist" to Abbrev("Asia/Kolkata"),
        "pkt" to Abbrev("Asia/Karachi"),
        "npt" to Abbrev("Asia/Kathmandu"),
        "gst" to Abbrev("Asia/Dubai"),
        "ast" to Abbrev("Asia/Riyadh", mapOf("America/" to "America/Halifax")),
        "irst" to Abbrev("Asia/Tehran"),
        "trt" to Abbrev("Europe/Istanbul"),
        "msk" to Abbrev("Europe/Moscow"),
        "cet" to Abbrev("Europe/Berlin"),
        "cest" to Abbrev("Europe/Berlin"),
        "eet" to Abbrev("Europe/Athens"),
        "eest" to Abbrev("Europe/Athens"),
        "wet" to Abbrev("Europe/Lisbon"),
        "west" to Abbrev("Europe/Lisbon"),
        "eat" to Abbrev("Africa/Nairobi"),
        "wat" to Abbrev("Africa/Lagos"),
        "sast" to Abbrev("Africa/Johannesburg"),
        "ict" to Abbrev("Asia/Bangkok"),
        "wib" to Abbrev("Asia/Jakarta"),
        "sgt" to Abbrev("Asia/Singapore"),
        "myt" to Abbrev("Asia/Kuala_Lumpur"),
        "hkt" to Abbrev("Asia/Hong_Kong"),
        "cst" to Abbrev("America/Chicago", mapOf("Asia/" to "Asia/Shanghai")),
        "cdt" to Abbrev("America/Chicago"),
        "jst" to Abbrev("Asia/Tokyo"),
        "kst" to Abbrev("Asia/Seoul"),
        "aest" to Abbrev("Australia/Sydney"),
        "aedt" to Abbrev("Australia/Sydney"),
        "nzst" to Abbrev("Pacific/Auckland"),
        "nzdt" to Abbrev("Pacific/Auckland"),
        "est" to Abbrev("America/New_York"),
        "edt" to Abbrev("America/New_York"),
        "mst" to Abbrev("America/Denver"),
        "mdt" to Abbrev("America/Denver"),
        "pst" to Abbrev("America/Los_Angeles"),
        "pdt" to Abbrev("America/Los_Angeles"),
        "akst" to Abbrev("America/Anchorage"),
        "akdt" to Abbrev("America/Anchorage"),
        "hst" to Abbrev("Pacific/Honolulu"),
    )

    fun parse(text: String, nowMillis: Long, zone: TimeZone, locale: Locale): DateTimeHit? {
        var t = text.trim()
        if (t.isEmpty() || t.length > MAX_LENGTH || t.contains('\n')) return null
        t = normalise(t)
        var interpretation = zone
        var zoneNamed = false
        zoneTail(t, zone)?.let { (rest, found) ->
            t = rest
            interpretation = found
            zoneNamed = true
        }
        if (t.isEmpty()) return null
        if (t in NOW) {
            return DateTimeHit(
                startMillis = nowMillis,
                endMillis = nowMillis + HOUR_MS,
                hasDate = false,
                hasTime = true,
                zoneId = interpretation.id,
                zoneNamed = zoneNamed,
                ambiguousOrder = false,
            )
        }

        val found = TIME_SHAPES.firstNotNullOfOrNull { shape ->
            val match = shape.regex.find(t) ?: return@firstNotNullOfOrNull null
            val parsed = (if (shape.bengali) bengaliTime(match) else time(match)) ?: return@firstNotNullOfOrNull null
            parsed to (if (shape.atEnd) t.substring(0, match.range.first) else t.substring(match.range.last + 1))
        }
        val time: Time? = found?.first
        if (found != null) t = found.second.trim(' ', ',')
        t = t.removePrefix("at ").removePrefix("on ").trim()

        val today = Calendar.getInstance(interpretation).apply { timeInMillis = nowMillis }
        val day: Day? = if (t.isEmpty()) null else day(t, today, locale) ?: return null
        if (day == null && time == null) return null

        val calendar = Calendar.getInstance(interpretation)
        calendar.clear()
        calendar.set(
            day?.year ?: today.get(Calendar.YEAR),
            (day?.month ?: (today.get(Calendar.MONTH) + 1)) - 1,
            day?.day ?: today.get(Calendar.DAY_OF_MONTH),
            time?.hour ?: 0,
            time?.minute ?: 0,
            time?.second ?: 0,
        )
        val start = calendar.timeInMillis
        return DateTimeHit(
            startMillis = start,
            endMillis = start + if (time != null) HOUR_MS else DAY_MS,
            hasDate = day != null,
            hasTime = time != null,
            zoneId = interpretation.id,
            zoneNamed = zoneNamed,
            ambiguousOrder = day?.ambiguous == true,
        )
    }

    /** Whether the locale writes the day before the month (`14/09`), the default when unsure. */
    fun dayFirst(locale: Locale): Boolean {
        val pattern = (DateFormat.getDateInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern()
            ?: return true
        val d = pattern.indexOf('d')
        val m = pattern.indexOf('M')
        return d < 0 || m < 0 || d < m
    }

    /** The zone [token] names, or null when it is not one. */
    fun zoneFor(token: String, deviceZone: TimeZone): TimeZone? {
        val t = token.trim().lowercase(Locale.ROOT)
        if (t == "utc" || t == "gmt" || t == "z") return TimeZone.getTimeZone("UTC")
        if (t.startsWith("utc") || t.startsWith("gmt")) return offsetZone(t.substring(3))
        if (t.startsWith("+") || t.startsWith("-")) return offsetZone(t)
        val abbrev = ABBREVS[t] ?: return null
        val id = abbrev.byRegion.entries.firstOrNull { deviceZone.id.startsWith(it.key) }?.value ?: abbrev.id
        return TimeZone.getTimeZone(id)
    }

    /** `IST (Kolkata)`, `EDT (New York)`, `GMT+6`, `UTC`. */
    fun zoneLabel(zoneId: String, millis: Long): String {
        if (zoneId == "UTC" || zoneId == "GMT" || zoneId == "Etc/UTC") return "UTC"
        val zone = TimeZone.getTimeZone(zoneId)
        var short = zone.getDisplayName(zone.inDaylightTime(Date(millis)), TimeZone.SHORT, Locale.US)
        Regex("""GMT([+-])(\d{2}):(\d{2})""").matchEntire(short)?.let { m ->
            val hours = m.groupValues[2].trimStart('0').ifEmpty { "0" }
            short = "GMT" + m.groupValues[1] + hours + if (m.groupValues[3] == "00") "" else ":" + m.groupValues[3]
        }
        val city = zoneId.substringAfterLast('/', "").replace('_', ' ')
        return if (city.isEmpty() || city.equals(short, ignoreCase = true) || !zoneId.contains('/')) short else "$short ($city)"
    }

    /**
     * [millis] as a clock time in [zoneId]. The date comes along when the
     * selection had one, or when the day in [zoneId] is not the day it was in
     * [sourceZoneId]: 02:00 in Dhaka is 20:00 the evening before in UTC.
     */
    fun renderInZone(
        millis: Long,
        zoneId: String,
        sourceZoneId: String,
        hasDate: Boolean,
        hour24: Boolean,
        nowMillis: Long,
    ): String {
        val zone = TimeZone.getTimeZone(zoneId)
        val target = Calendar.getInstance(zone).apply { timeInMillis = millis }
        val source = Calendar.getInstance(TimeZone.getTimeZone(sourceZoneId)).apply { timeInMillis = millis }
        val sameDay = target.get(Calendar.YEAR) == source.get(Calendar.YEAR) &&
            target.get(Calendar.DAY_OF_YEAR) == source.get(Calendar.DAY_OF_YEAR)
        val time = if (hour24) {
            "%02d:%02d".format(Locale.ROOT, target.get(Calendar.HOUR_OF_DAY), target.get(Calendar.MINUTE))
        } else {
            val hour = target.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            val half = if (target.get(Calendar.AM_PM) == Calendar.AM) "am" else "pm"
            "%d:%02d %s".format(Locale.ROOT, hour, target.get(Calendar.MINUTE), half)
        }
        val prefix = if (hasDate || !sameDay) {
            val thisYear = Calendar.getInstance(zone).apply { timeInMillis = nowMillis }.get(Calendar.YEAR)
            val pattern = if (target.get(Calendar.YEAR) == thisYear) "EEE d MMM" else "EEE d MMM yyyy"
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(Date(millis)) + ", "
        } else {
            ""
        }
        return prefix + time + " " + zoneLabel(zoneId, millis)
    }

    /** Where a clock time may sit in the text, and which grammar reads it. Tried in this order. */
    private class TimeShape(val regex: Regex, val atEnd: Boolean, val bengali: Boolean)

    private val TIME_SHAPES = listOf(
        TimeShape(TIME_END, atEnd = true, bengali = false),
        TimeShape(BN_TIME_END, atEnd = true, bengali = true),
        TimeShape(TIME_START, atEnd = false, bengali = false),
        TimeShape(BN_TIME_START, atEnd = false, bengali = true),
    )

    private fun normalise(text: String): String =
        decomposeNukta(DigitScripts.toAsciiDigits(text) ?: text)
            .lowercase(Locale.ROOT)
            .replace(WS, " ")

    /**
     * Precomposed nukta letters written as base plus nukta, which is how this
     * file's own Bengali month names are stored.
     */
    private fun decomposeNukta(text: String): String =
        text.replace("\u09DF", "\u09AF\u09BC").replace("\u09DC", "\u09A1\u09BC").replace("\u09DD", "\u09A2\u09BC")

    private fun zoneTail(text: String, deviceZone: TimeZone): Pair<String, TimeZone>? {
        ZONE_UTC.find(text)?.let { m ->
            val zone = zoneFor(m.groupValues[1] + m.groupValues[2], deviceZone) ?: return null
            return text.substring(0, m.range.first).trim() to zone
        }
        ZONE_OFFSET.find(text)?.let { m ->
            val zone = zoneFor(m.groupValues[1], deviceZone) ?: return null
            return text.substring(0, m.range.first).trim() to zone
        }
        ZONE_ABBREV.find(text)?.let { m ->
            val zone = zoneFor(m.groupValues[1], deviceZone) ?: return null
            return text.substring(0, m.range.first).trim() to zone
        }
        return null
    }

    private fun offsetZone(spec: String): TimeZone? {
        val m = OFFSET.matchEntire(spec.trim()) ?: return null
        val hours = m.groupValues[2].toInt()
        val minutes = m.groupValues[3].toIntOrNull() ?: 0
        if (hours > 14 || minutes > 59) return null
        return TimeZone.getTimeZone("GMT%s%02d:%02d".format(Locale.ROOT, m.groupValues[1], hours, minutes))
    }

    private fun time(match: MatchResult): Time? {
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val separator = match.groupValues[2]
        val minute = match.groupValues[3].toIntOrNull() ?: 0
        val second = match.groupValues[4].toIntOrNull() ?: 0
        val half = match.groupValues[5].replace(".", "")
        if (minute > 59 || second > 59) return null
        if (half.isEmpty()) {
            // A bare number is a count, not a clock, and a dotted one is a
            // decimal; only the colon form stands on its own.
            if (separator != ":") return null
            return if (hour in 0..23) Time(hour, minute, second) else null
        }
        if (hour !in 1..12) return null
        val hour24 = when {
            half == "am" -> if (hour == 12) 0 else hour
            else -> if (hour == 12) 12 else hour + 12
        }
        return Time(hour24, minute, second)
    }

    private fun bengaliTime(match: MatchResult): Time? {
        val period = match.groupValues[1]
        val hour = match.groupValues[2].toIntOrNull() ?: return null
        val minute = match.groupValues[3].toIntOrNull() ?: 0
        if (hour !in 1..12 || minute > 59) return null
        val hour24 = when (period) {
            "সকাল", "ভোর" -> if (hour == 12) 0 else hour
            "দুপুর" -> if (hour == 12) 12 else hour + 12
            "বিকাল", "বিকেল", "সন্ধ্যা", "সন্ধ্যে" -> if (hour == 12) 12 else hour + 12
            "রাত", "রাত্রি" -> if (hour in 6..11) hour + 12 else if (hour == 12) 0 else hour
            else -> hour
        }
        return Time(hour24, minute, 0)
    }

    private fun day(text: String, today: Calendar, locale: Locale): Day? {
        WEEKDAY_PREFIX.find(text)?.let { m -> return day(text.substring(m.range.last + 1), today, locale) }
        if (text in TODAY) {
            return Day(today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH), false)
        }
        ISO.matchEntire(text)?.let { m ->
            return valid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), false)
        }
        NUMERIC.matchEntire(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[3].toInt()
            val year = m.groupValues[4].let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
            return when {
                a > 12 -> valid(year, b, a, false)
                b > 12 -> valid(year, a, b, false)
                a == b -> valid(year, a, b, false)
                dayFirst(locale) -> valid(year, b, a, true)
                else -> valid(year, a, b, true)
            }
        }
        DAY_MONTH.matchEntire(text)?.let { m ->
            return named(m.groupValues[1].toInt(), MONTHS.getValue(m.groupValues[2]), m.groupValues[3], today)
        }
        MONTH_DAY.matchEntire(text)?.let { m ->
            return named(m.groupValues[2].toInt(), MONTHS.getValue(m.groupValues[1]), m.groupValues[3], today)
        }
        val todayJdn = CalendarSystems.gregorianToJdn(
            today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH),
        )
        // The locale's own words too ("morgen", "nächsten Freitag"), and those
        // of the script the text is in: a Bengali selection on an English phone.
        val hit = DateSuggest.find(text, todayJdn, listOf(locale.toLanguageTag())) ?: return null
        if (hit.start != 0) return null
        val date = CalendarSystems.jdnToGregorian(hit.jdn)
        return Day(date.year, date.month, date.day, false)
    }

    /** A day and month with an optional year: this year while still ahead, else next. */
    private fun named(day: Int, month: Int, yearText: String, today: Calendar): Day? {
        yearText.toIntOrNull()?.let { return valid(it, month, day, false) }
        val thisYear = today.get(Calendar.YEAR)
        val todayJdn = CalendarSystems.gregorianToJdn(thisYear, today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH))
        val year = if (day in 1..CalendarSystems.gregorianMonthLength(thisYear, month) &&
            CalendarSystems.gregorianToJdn(thisYear, month, day) >= todayJdn
        ) {
            thisYear
        } else {
            thisYear + 1
        }
        return valid(year, month, day, false)
    }

    private fun valid(year: Int, month: Int, day: Int, ambiguous: Boolean): Day? {
        if (year !in 1900..2100 || month !in 1..12) return null
        if (day !in 1..CalendarSystems.gregorianMonthLength(year, month)) return null
        return Day(year, month, day, ambiguous)
    }
}
