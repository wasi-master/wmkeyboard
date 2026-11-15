package com.wasimaster.wmkeyboard.core.clock

import com.wasimaster.wmkeyboard.core.tools.GeoPlace
import com.wasimaster.wmkeyboard.core.tools.WeatherClient
import java.util.TimeZone

/**
 * The world clock's search past its own list: a town the tz database has no
 * zone for (Sylhet, Leeds, Kraków) is looked up with the same Open-Meteo
 * geocoder the Weather tool uses, which answers with the town's zone. Only
 * what was typed is sent, and only when the user asks for it.
 *
 * Every hit becomes an "extra" [ZoneEntry]: the town keeps its own name on
 * its card, the way Beijing does in Shanghai's zone.
 */
object OnlinePlaces {

    /** Blocking; call on an IO dispatcher. Throws when the search cannot be reached. */
    fun search(query: String, now: Long = System.currentTimeMillis()): List<ZoneEntry> {
        if (query.isBlank()) return emptyList()
        return entries(WeatherClient.geocode(query.trim(), count = MAX_RESULTS), now)
    }

    /** The hits that name a zone this phone knows, one per town and zone. */
    fun entries(places: List<GeoPlace>, now: Long): List<ZoneEntry> =
        places.mapNotNull { entry(it, now) }.distinctBy { it.key }

    fun entry(place: GeoPlace, now: Long): ZoneEntry? {
        val id = place.timezone?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val tz = TimeZone.getTimeZone(id)
        // An id the phone's tz data lacks comes back as plain GMT.
        if (tz.id != id && tz.id == "GMT") return null
        return ZoneEntry(
            id = id,
            city = place.name,
            region = place.region,
            offsetMinutes = tz.getOffset(now) / 60_000,
            extra = true,
            latitude = place.latitude.toDouble(),
            longitude = place.longitude.toDouble(),
        )
    }

    private const val MAX_RESULTS = 10
}
