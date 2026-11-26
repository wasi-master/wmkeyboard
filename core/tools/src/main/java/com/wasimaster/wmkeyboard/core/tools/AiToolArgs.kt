package com.wasimaster.wmkeyboard.core.tools

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Dates and times as a model writes them for the calendar tools (#698), read
 * into the instants the calendar provider stores.
 *
 * Forgiving about form, strict about meaning: `2026-10-17`, `2026-10-17 17:00`
 * and `2026-10-17T17:00:00Z` all read, but `next Friday` does not, since the
 * model has today's date in the tool's description and can work the day out
 * itself far better than a parser can guess at English.
 */
object AiCalendarArgs {

    /** One date, with a time of day or without one. */
    data class Moment(val year: Int, val month: Int, val day: Int, val hour: Int? = null, val minute: Int = 0) {
        val hasTime: Boolean get() = hour != null
    }

    /**
     * An event's span as the provider stores it. An all-day event runs from
     * one UTC midnight to the next, whatever the phone's zone, which is how
     * CalendarContract defines one.
     */
    data class Span(val begin: Long, val end: Long, val allDay: Boolean)

    /**
     * [text] as a date and, if it has one, a time. A zone or an offset on the
     * end is ignored: the tool tells the model to write local time, and a
     * model that adds `Z` out of habit still means the user's five o'clock.
     */
    fun parse(text: String): Moment? {
        val match = MOMENT.matchEntire(text.trim()) ?: return null
        val (y, mo, d, h, mi) = match.destructured
        val month = mo.toInt()
        val day = d.toInt()
        if (month !in 1..12 || day !in 1..31) return null
        val hour = h.takeIf { it.isNotEmpty() }?.toInt()
        val minute = mi.takeIf { it.isNotEmpty() }?.toInt() ?: 0
        if (hour != null && (hour !in 0..23 || minute !in 0..59)) return null
        return Moment(y.toInt(), month, day, hour, minute)
    }

    /**
     * The span an event from [start] to [end] covers. A date with no time is
     * an all-day event; an end left out, or one not after the start, makes it
     * an hour long (or one day). Null when [start] does not read.
     */
    fun span(start: String, end: String?, zone: TimeZone = TimeZone.getDefault()): Span? {
        val from = parse(start) ?: return null
        val to = end?.let(::parse)
        if (!from.hasTime) {
            val begin = millis(from, UTC)
            // An end date is the last day the event covers, and the provider
            // wants the midnight after it.
            val last = to?.let { millis(it.copy(hour = null, minute = 0), UTC) + DAY_MS }
            return Span(begin, last?.takeIf { it > begin } ?: (begin + DAY_MS), allDay = true)
        }
        val begin = millis(from, zone)
        val until = to?.let { if (it.hasTime) millis(it, zone) else null }
        return Span(begin, until?.takeIf { it > begin } ?: (begin + HOUR_MS), allDay = false)
    }

    /** The local day [moment] names, from its midnight to the next. */
    fun dayRange(moment: Moment, zone: TimeZone = TimeZone.getDefault()): LongRange {
        val start = millis(moment.copy(hour = null, minute = 0), zone)
        return start until start + DAY_MS
    }

    /** [moment] as epoch milliseconds in [zone]; midnight when it has no time. */
    fun millis(moment: Moment, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        clear()
        set(moment.year, moment.month - 1, moment.day, moment.hour ?: 0, moment.minute, 0)
    }.timeInMillis

    /** Today in [zone], as the date sentence the dated tools end with. */
    fun today(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = now }
        val date = String.format(
            Locale.ROOT, "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH),
        )
        val weekday = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH)
        return "Today is $weekday $date, and the user's time zone is ${zone.id}."
    }

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private const val HOUR_MS = 60L * 60L * 1000L
    const val DAY_MS = 24L * HOUR_MS

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 33 KB across this file's patterns.
    private val MOMENT by lazy {
        Regex(
            """(\d{4})-(\d{1,2})-(\d{1,2})(?:[T ](\d{1,2}):(\d{2})(?::\d{2}(?:\.\d+)?)?)?\s*(?:Z|[+-]\d{2}:?\d{2})?""",
            RegexOption.IGNORE_CASE,
        )
    }
}

/**
 * Times, lengths and days as a model writes them for the clock tools (#698).
 */
object AiClockArgs {

    /**
     * A time of day as hour and minute: `07:30`, `7:30 pm`, `6pm`, `18.45`.
     * Null for anything that is not one.
     */
    fun time(text: String): Pair<Int, Int>? {
        val match = TIME.matchEntire(text.trim()) ?: return null
        val (h, m, meridiem) = match.destructured
        var hour = h.toInt()
        val minute = m.takeIf { it.isNotEmpty() }?.toInt() ?: 0
        when (meridiem.lowercase(Locale.ROOT).replace(".", "")) {
            "am" -> if (hour == 12) hour = 0 else if (hour > 12) return null
            "pm" -> if (hour in 1..11) hour += 12 else if (hour > 12) return null
            else -> if (m.isEmpty()) return null
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    /**
     * A length of time in milliseconds: `90s`, `25m`, `1h30m`, `1 hour 20
     * minutes`, `1:30:00`, or a bare number of minutes. Null for nothing, a
     * zero, or more than a day, which no kitchen timer means.
     */
    fun duration(text: String): Long? {
        val trimmed = text.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return null
        val ms = when {
            COLONS.matches(trimmed) -> {
                val parts = trimmed.split(':').map { it.toLong() }
                val seconds = if (parts.size == 3) parts[0] * 3600 + parts[1] * 60 + parts[2] else parts[0] * 60 + parts[1]
                seconds * 1000
            }
            BARE.matches(trimmed) -> (trimmed.toDouble() * 60_000).toLong()
            else -> {
                var total = 0.0
                var rest = trimmed
                for (match in PART.findAll(trimmed)) {
                    val amount = match.groupValues[1].toDouble()
                    total += amount * when (match.groupValues[2].first()) {
                        'h' -> 3_600_000.0
                        'm' -> 60_000.0
                        else -> 1_000.0
                    }
                    rest = rest.replaceFirst(match.value, "")
                }
                // Something left over means words we did not read, and a
                // timer for part of what was asked is worse than none.
                if (total == 0.0 || rest.replace(FILLER, "").isNotEmpty()) return null
                total.toLong()
            }
        }
        return ms.takeIf { it in 1_000..MAX_MS }
    }

    /**
     * Repeat days as the clock stores them, Monday in bit 0 to Sunday in bit
     * 6 ([com.wasimaster.wmkeyboard.core.clock.Weekdays]). 0 for "once", null
     * for a word that is not a day.
     */
    fun days(text: String?): Int? {
        val trimmed = text?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (trimmed.isEmpty() || trimmed == "once" || trimmed == "none") return 0
        when (trimmed.replace("-", " ").replace("_", " ")) {
            "every day", "everyday", "daily", "all", "all days" -> return ALL
            "weekdays", "workdays", "week days" -> return WORKDAYS
            "weekends", "weekend" -> return WEEKEND
        }
        var bits = 0
        for (token in trimmed.split(',', ' ', '/', ';').map { it.trim() }.filter { it.isNotEmpty() && it != "and" }) {
            val index = DAY_NAMES.indexOfFirst { names -> names.any { token == it || (token.length >= 3 && it.startsWith(token)) } }
            if (index < 0) return null
            bits = bits or (1 shl index)
        }
        return bits
    }

    private const val MAX_MS = 24L * 60L * 60L * 1000L
    private const val ALL = 0b111_1111
    private const val WORKDAYS = 0b001_1111
    private const val WEEKEND = 0b110_0000

    private val TIME by lazy { Regex("""(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?""", RegexOption.IGNORE_CASE) }
    private val COLONS by lazy { Regex("""\d{1,2}:\d{2}(?::\d{2})?""") }
    private val BARE by lazy { Regex("""\d+(?:\.\d+)?""") }
    private val PART by lazy { Regex("""(\d+(?:\.\d+)?)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)""") }
    private val FILLER by lazy { Regex("""[\s,]|and""") }

    /** Monday first, as the clock's bits are. */
    private val DAY_NAMES = listOf(
        listOf("monday", "mon"),
        listOf("tuesday", "tue", "tues"),
        listOf("wednesday", "wed"),
        listOf("thursday", "thu", "thur", "thurs"),
        listOf("friday", "fri"),
        listOf("saturday", "sat"),
        listOf("sunday", "sun"),
    )
}
