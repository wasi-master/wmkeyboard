package com.wasimaster.wmkeyboard.ime.calendar

import com.wasimaster.wmkeyboard.core.tools.EventWeatherRules
import com.wasimaster.wmkeyboard.core.tools.GeoPlace
import com.wasimaster.wmkeyboard.core.tools.HourlyForecast
import com.wasimaster.wmkeyboard.core.tools.WeatherClient
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the weather on calendar events (#1212) may do right now, read from
 * the settings and the network each time the Calendar panel asks.
 */
internal data class EventWeatherConfig(
    /** The Calendar tool's "Weather on events" setting. */
    val enabled: Boolean = false,
    /** The Weather tool's own place, which stands in for an event with no place of its own. */
    val home: ForecastPlace? = null,
    val fahrenheit: Boolean = false,
    /** The Weather tool's refresh interval: a forecast older than this is fetched again. */
    val staleAfterMs: Long = 15 * 60_000L,
    /** A network is up. */
    val online: Boolean = true,
    /** Data saving is in force, so nothing is fetched without a tap. */
    val dataSaving: Boolean = false,
) {
    /** Whether the cards may fetch by themselves: online, and data saving not holding it back. */
    val mayFetchUnasked: Boolean get() = online && !dataSaving
}

/** A place to forecast for, and whether the event named it or the user's weather location stands in. */
data class ForecastPlace(val name: String, val latitude: Float, val longitude: Float, val fromEvent: Boolean = false)

/** The weather beside one event card: the hour it is for, and where. */
data class EventWeatherChip(
    val weatherCode: Int,
    val isDay: Boolean,
    val temperatureC: Double,
    val precipProbabilityPercent: Int,
    val place: ForecastPlace,
    /** The moment forecast for: the event's start, now for one under way, midday for an all-day one. */
    val focusMillis: Long,
    val fahrenheit: Boolean,
) {
    val emoji: String get() = WeatherClient.emoji(weatherCode, isDay)
    val temperature: Int get() = WeatherClient.toDisplay(temperatureC, fahrenheit)
}

/**
 * The hourly forecast the Weather panel shows for one event, opened from the
 * weather on its card. Owned by [CalendarController]; the Weather panel only
 * draws it.
 */
data class EventForecastUi(
    val title: String,
    val place: ForecastPlace,
    val day: EventDate,
    /** The local midnights around [day], for the hours to show. */
    val dayStart: Long,
    val dayEnd: Long,
    val focusMillis: Long,
    val fahrenheit: Boolean,
    val hour24: Boolean,
    val forecast: HourlyForecast? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/**
 * The places and forecasts the event cards have looked up, kept for the life
 * of the keyboard process. One forecast covers every event at one place for
 * [WeatherClient.FORECAST_DAYS] days, so a week of meetings in one town is a
 * single request; it is fetched again only once it is older than the Weather
 * tool's refresh interval.
 *
 * A place that searched to nothing is remembered as nothing, so it is not
 * asked about again; one whose search failed (no network) is not remembered,
 * so it is tried the next time.
 */
internal class EventWeatherSource(private val now: () -> Long = System::currentTimeMillis) {

    private val lock = Mutex()

    /** The place each event text searched to; a null value is "found nothing". */
    private val places = object : LinkedHashMap<String, GeoPlace?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GeoPlace?>?) = size > MAX_PLACES
    }

    private val forecasts = object : LinkedHashMap<String, HourlyForecast>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HourlyForecast>?) = size > MAX_FORECASTS
    }

    /** What a search for an event's place came to. */
    sealed interface PlaceHit {
        data class Found(val place: GeoPlace) : PlaceHit

        /** The text names no place a search finds: the user's weather location may stand in. */
        data object NotFound : PlaceHit

        /** Not searched yet (no network, or not allowed to): nothing may stand in for it. */
        data object Unknown : PlaceHit
    }

    /** Where [location] is; searched only when it has not been already and [mayFetch]. */
    suspend fun place(location: String, mayFetch: Boolean): PlaceHit = lock.withLock {
        val key = location.trim().lowercase(Locale.ROOT)
        if (places.containsKey(key)) return@withLock places[key]?.let { PlaceHit.Found(it) } ?: PlaceHit.NotFound
        val queries = EventWeatherRules.placeQueries(location)
        if (queries.isEmpty()) {
            places[key] = null
            return@withLock PlaceHit.NotFound
        }
        if (!mayFetch) return@withLock PlaceHit.Unknown
        for (q in queries) {
            val found = withContext(Dispatchers.IO) { runCatching { WeatherClient.geocode(q, count = 1) } }
                .getOrElse { return@withLock PlaceHit.Unknown }
                .firstOrNull()
            if (found != null) {
                places[key] = found
                return@withLock PlaceHit.Found(found)
            }
        }
        places[key] = null
        PlaceHit.NotFound
    }

    /**
     * The forecast for [latitude]/[longitude]: the kept one while it is
     * younger than [staleAfterMs], else a fresh one when [mayFetch]. A fetch
     * that fails, or one that may not run, falls back on a kept forecast up to
     * a day old, which still says roughly what the day holds.
     */
    suspend fun forecast(latitude: Float, longitude: Float, staleAfterMs: Long, mayFetch: Boolean): HourlyForecast? =
        lock.withLock {
            val key = String.format(Locale.ROOT, "%.2f,%.2f", latitude, longitude)
            val kept = forecasts[key]
            val age = kept?.let { now() - it.fetchedAtMillis }
            if (kept != null && age != null && age in 0 until staleAfterMs) return@withLock kept
            val fresh = if (mayFetch) {
                withContext(Dispatchers.IO) { runCatching { WeatherClient.fetchHourly(latitude, longitude) }.getOrNull() }
            } else {
                null
            }
            if (fresh != null) {
                forecasts[key] = fresh
                return@withLock fresh
            }
            kept?.takeIf { age != null && age in 0 until KEEP_STALE_MS }
        }

    private companion object {
        const val MAX_PLACES = 64
        const val MAX_FORECASTS = 8
        const val KEEP_STALE_MS = 24 * 3_600_000L
    }
}
