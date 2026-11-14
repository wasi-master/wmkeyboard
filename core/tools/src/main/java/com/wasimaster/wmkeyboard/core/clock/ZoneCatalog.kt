package com.wasimaster.wmkeyboard.core.clock

import java.text.Normalizer
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * One zone the user can pick: its tz id, the city the system names for it in
 * the user's language, its country or region, and its current offset.
 *
 * An [extra] entry is one of [EXTRA_CITIES]: a city that shares another's zone
 * (Beijing in Shanghai's), with coordinates of its own.
 */
data class ZoneEntry(
    val id: String,
    val city: String,
    val region: String,
    val offsetMinutes: Int,
    val extra: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    /** Unique across the catalog, where [id] is not: Beijing and Shanghai share one. */
    val key: String get() = if (extra) "$id#$city" else id

    /**
     * The world clock card this entry adds. An extra city keeps its own name
     * as the card's label, since the zone alone would read "Shanghai".
     */
    fun toWorldZone(): WorldZone =
        if (extra) WorldZone(id, label = city, latitude = latitude, longitude = longitude) else WorldZone(id)
}

/**
 * The world clock's list of places, built from the tz database the phone
 * already carries: about 420 cities, offline, in the user's own language when
 * Android has a name for it in that language.
 *
 * Names come through [ZoneNames] so the search rules are tested on the JVM,
 * where `android.icu` is a stub.
 */
class ZoneCatalog(private val names: ZoneNames, now: Long = System.currentTimeMillis()) {

    private val zones: List<ZoneEntry> = TimeZone.getAvailableIDs()
        .asSequence()
        .filter(::isPlace)
        // "Asia/Calcutta" and "Asia/Kolkata" are one place. ICU's canonical id
        // is often the older spelling, so it only groups them; the id kept is
        // the one the tz database's own zone table uses today.
        .groupBy { names.canonical(it) }
        .values
        .asSequence()
        .map { ids -> ids.firstOrNull { ZoneCoordinates.of(it) != null } ?: ids.first() }
        .map { id ->
            val tz = TimeZone.getTimeZone(id)
            ZoneEntry(
                id = id,
                city = names.city(id) ?: fallbackCity(id),
                region = names.region(id).orEmpty(),
                offsetMinutes = tz.getOffset(now) / 60_000,
            )
        }
        .distinctBy { it.id }
        .toList()

    /**
     * [EXTRA_CITIES], each under the id its zone has in [zones]: the list may
     * name a zone by a spelling this phone groups under another. A city whose
     * zone the phone does not have is left out.
     */
    private val extras: List<Pair<ZoneEntry, String>> = run {
        val byCanonical = zones.associateBy { names.canonical(it.id) }
        val byId = zones.associateBy { it.id }
        EXTRA_CITIES.mapNotNull { c ->
            val zone = byId[c.zoneId] ?: byCanonical[names.canonical(c.zoneId)] ?: return@mapNotNull null
            zone.copy(
                city = c.name,
                region = c.region?.let(names::country) ?: zone.region,
                extra = true,
                latitude = c.latitude,
                longitude = c.longitude,
            ) to c.also
        }
    }

    val entries: List<ZoneEntry> = (zones + extras.map { it.first })
        .sortedWith(compareBy({ it.offsetMinutes }, { it.city }))

    private val keys: Map<String, String> = run {
        val also = extras.associate { (e, words) -> e.key to words }
        entries.associate { e ->
            // An extra city is not found by its zone's name: "shanghai" lists
            // Shanghai, not every Chinese city after it.
            val zoneWords = if (e.extra) "" else e.id.replace('_', ' ').replace('/', ' ')
            e.key to fold("${e.city} ${also[e.key].orEmpty()} ${e.region} $zoneWords ${abbreviations(e.id)}")
        }
    }

    /**
     * Places matching [query], best first.
     *
     * Every word of the query has to start a word of the place ("new yo" finds
     * New York, "york" does too, "ork" does not). An offset ("+5:30", "utc-3",
     * "gmt 8") finds every place on that offset, and an abbreviation ("ist",
     * "pst") finds the zones that use it.
     */
    fun search(query: String, limit: Int = MAX_RESULTS): List<ZoneEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries.take(limit)
        parseOffset(q)?.let { off -> return entries.filter { it.offsetMinutes == off }.take(limit) }
        val words = fold(q).split(' ').filter { it.isNotEmpty() }
        return entries
            .mapNotNull { e ->
                val key = keys[e.key] ?: return@mapNotNull null
                val tokens = key.split(' ')
                if (!words.all { w -> tokens.any { it.startsWith(w) } }) return@mapNotNull null
                val cityKey = fold(e.city)
                val rank = when {
                    cityKey == fold(q) -> 0
                    cityKey.startsWith(fold(q)) -> 1
                    tokens.firstOrNull()?.startsWith(words.first()) == true -> 2
                    else -> 3
                }
                rank to e
            }
            .sortedWith(compareBy({ it.first }, { it.second.city.length }))
            .map { it.second }
            .take(limit)
    }

    fun byId(id: String): ZoneEntry? = zones.firstOrNull { it.id == id }

    companion object {
        const val MAX_RESULTS = 60

        /**
         * A real place, not a legacy alias: "Asia/Dhaka" yes; "UTC",
         * "Etc/GMT+6", "SystemV/…", "US/Pacific" and the bare three-letter
         * ids no.
         */
        fun isPlace(id: String): Boolean {
            if ('/' !in id) return false
            val area = id.substringBefore('/')
            return area in AREAS
        }

        private val AREAS = setOf(
            "Africa", "America", "Antarctica", "Arctic", "Asia", "Atlantic",
            "Australia", "Europe", "Indian", "Pacific",
        )

        /** "America/Argentina/Buenos_Aires" -> "Buenos Aires". */
        fun fallbackCity(id: String): String = id.substringAfterLast('/').replace('_', ' ')

        /**
         * "+5:30", "-3", "utc+8", "GMT -04:00" as minutes east of UTC, or null
         * when the text is not an offset.
         */
        fun parseOffset(text: String): Int? {
            val m = OFFSET.matchEntire(text.trim()) ?: return null
            val sign = if (m.groupValues[1] == "-") -1 else 1
            val hours = m.groupValues[2].toIntOrNull() ?: return null
            val minutes = m.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
            if (hours > 14 || minutes > 59) return null
            return sign * (hours * 60 + minutes)
        }

        private val OFFSET = Regex("""(?i)(?:utc|gmt)?\s*([+-])\s*(\d{1,2})(?::?(\d{2}))?""")

        /** Folds case and accents, so "sao" finds São Paulo and "zur" Zürich. */
        fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(MARKS, "")
                .lowercase(Locale.ROOT)
                .replace(NON_WORD, " ")
                .trim()

        private val MARKS = Regex("\\p{Mn}+")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        private fun abbreviations(id: String): String {
            val tz = TimeZone.getTimeZone(id)
            val std = tz.getDisplayName(false, TimeZone.SHORT, Locale.US)
            val dst = if (tz.useDaylightTime()) tz.getDisplayName(true, TimeZone.SHORT, Locale.US) else ""
            // "GMT+06:00" is not an abbreviation anybody types; the offset search covers it.
            return listOf(std, dst).filterNot { it.startsWith("GMT") || it.isEmpty() }.joinToString(" ")
        }
    }
}

/** The city and region names for a zone, in the user's language. */
interface ZoneNames {
    fun city(id: String): String?
    fun region(id: String): String?

    /** The name of the ISO region [code]: "IL" -> "Israel". */
    fun country(code: String): String? = null

    /** The current id for a zone that has been renamed; the id itself otherwise. */
    fun canonical(id: String): String = id
}

/** Offset text for a number of minutes east of UTC: "UTC+5:30", "UTC−3", "UTC". */
fun utcOffsetLabel(minutes: Int): String {
    if (minutes == 0) return "UTC"
    val sign = if (minutes > 0) "+" else "−"
    val h = abs(minutes) / 60
    val m = abs(minutes) % 60
    return if (m == 0) "UTC$sign$h" else "UTC$sign$h:${m.toString().padStart(2, '0')}"
}
