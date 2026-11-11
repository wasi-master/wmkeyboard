package com.wasimaster.wmkeyboard.core.tools

import java.util.Calendar
import java.util.TimeZone

/**
 * The pure half of the weather on calendar events (#1212): which words of an
 * event's place to look up, and which hour of the event to forecast.
 *
 * Open-Meteo's place search matches place names, not addresses, so a whole
 * "Café Nero, 12 Oxford Street, London W1, UK" finds nothing. The parts of
 * it, with the house numbers and postcodes taken out, are what gets searched,
 * the town-shaped ones first.
 */
object EventWeatherRules {

    /** At most this many searches for one event's place. */
    const val MAX_QUERIES = 3

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    /** Places that are not places: a call link, a video app, "online". */
    private val NOT_A_PLACE = Regex(
        "(?i)(https?://|www\\.|\\bzoom\\b|\\bmeet\\.|google meet|\\bteams\\b|\\bwebex\\b|\\bskype\\b|" +
            "\\bdiscord\\b|\\bonline\\b|\\bvirtual\\b|\\bphone call\\b|\\bconference call\\b)",
    )

    /**
     * What to search for, in order, to find where [location] is; empty when it
     * names no place worth searching for, and the user's own weather location
     * stands in.
     *
     * One part is searched as it is. With more, the part before the last comes
     * first (in "Venue, Street, Town, Country" that is the town, and in "Town,
     * Country" the town again), then the parts before it, then the last.
     */
    fun placeQueries(location: String?): List<String> {
        val raw = location?.trim().orEmpty()
        if (raw.isEmpty() || NOT_A_PLACE.containsMatchIn(raw)) return emptyList()
        val parts = raw.split(',', ';', '\n', '|').map(::clean)
        val ordered = if (parts.size == 1) {
            parts
        } else {
            val n = parts.size
            (n - 2 downTo 0).map { parts[it] } + parts[n - 1]
        }
        return ordered.filter(::searchable).distinctBy { it.lowercase() }.take(MAX_QUERIES)
    }

    /** One part of an address without its numbers: "12 Oxford Street" is "Oxford Street", "W1A 1AA" is nothing. */
    private fun clean(part: String): String =
        part.trim().split(Regex("\\s+"))
            .filter { word -> word.none { it.isDigit() } }
            .joinToString(" ")
            .trim(' ', '-', '.', '#', '(', ')')

    /** A part that names a room in a building rather than a place on the map. */
    private val INDOORS = Regex("(?i)\\b(room|office|floor|desk|suite|hall|lobby|home|building)\\b")

    /** Long enough to mean something, not a state or postcode code like "IL", and not "Room 4". */
    private fun searchable(part: String): Boolean =
        part.length >= 3 && !(part.length <= 3 && part.all { it.isUpperCase() }) && !INDOORS.containsMatchIn(part)

    /**
     * The moment of an event to forecast, or null when there is none to give:
     * the event is over, or it lies beyond the [WeatherClient.FORECAST_DAYS]
     * the forecast covers.
     *
     * A timed event is forecast at its start, or now when it has already
     * started. An all-day event, whose ends are UTC midnights naming dates, is
     * forecast at midday of its first day still to come.
     */
    fun targetMillis(
        begin: Long,
        end: Long,
        allDay: Boolean,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
        forecastDays: Int = WeatherClient.FORECAST_DAYS,
    ): Long? {
        val today = midnight(now, zone)
        val horizon = today + forecastDays * DAY_MS
        val target = if (allDay) {
            val first = dateMidnight(begin, zone)
            val afterLast = dateMidnight(end, zone).coerceAtLeast(first + DAY_MS)
            if (afterLast <= today) return null
            maxOf(first, today) + 12 * HOUR_MS
        } else {
            val over = if (end > begin) end else begin + HOUR_MS
            if (over <= now) return null
            maxOf(begin, now)
        }
        return target.takeIf { it < horizon }
    }

    /** The local midnight starting the day [millis] falls on. */
    fun midnight(millis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** The local midnight of the date a UTC midnight names, as an all-day event stores it. */
    private fun dateMidnight(utcMidnight: Long, zone: TimeZone): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
        return Calendar.getInstance(zone).apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }
}
