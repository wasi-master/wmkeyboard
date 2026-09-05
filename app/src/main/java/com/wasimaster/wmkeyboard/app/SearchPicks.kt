package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.core.content.edit
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What one person picked out of settings search, and what that is worth the
 * next time they search.
 *
 * Two words match a query equally well and one of them is the setting this
 * person changes every week. No wording fixes that, and there is no telemetry
 * to learn it from everyone; so the search learns it from them. Each result
 * opened is one pick: which row, when, and what was typed to find it. A pick
 * then lifts the row a little in every later search, and a lot in a search
 * that starts the same way the earlier one did.
 *
 * The lift is capped so that the words still decide. A row called exactly
 * what you typed cannot be pushed under a row you merely picked before; a row
 * whose name only starts with your word can. See [pointsFor].
 *
 * Pure Kotlin so the ranking tests can run it. [SearchPicks] is the shim that
 * keeps it on disk.
 */
@Serializable
internal class SearchHistory(
    private val picks: MutableMap<String, Pick> = LinkedHashMap(),
) : SearchBoost {

    @Serializable
    internal class Pick(
        var count: Int,
        var lastMillis: Long,
        /** The last few normalized queries that led here, newest last. */
        val queries: MutableList<String> = mutableListOf(),
    )

    val isEmpty: Boolean get() = picks.isEmpty()

    /** [key] was opened from a search for [query], now. */
    fun record(query: String, key: String, nowMillis: Long) {
        val normalized = normalizeForSearch(query)
        val pick = picks.getOrPut(key) { Pick(0, nowMillis) }
        pick.count++
        pick.lastMillis = nowMillis
        if (normalized.isNotEmpty()) {
            pick.queries.remove(normalized)
            pick.queries += normalized
            while (pick.queries.size > QUERIES_PER_PICK) pick.queries.removeAt(0)
        }
        if (picks.size > MAX_PICKS) {
            val oldest = picks.minByOrNull { it.value.lastMillis }?.key
            if (oldest != null) picks.remove(oldest)
        }
    }

    /** The keys opened most recently, newest first. */
    fun recent(limit: Int): List<String> =
        picks.entries.sortedByDescending { it.value.lastMillis }.take(limit).map { it.key }

    fun clear() = picks.clear()

    /** The clock the decay is measured against; the shim sets it to now. */
    @kotlinx.serialization.Transient
    var nowMillis: () -> Long = System::currentTimeMillis

    /**
     * Points for [key] given [normalizedQuery].
     *
     * A pick made from a query that begins the way this one does (or that this
     * one begins) is what the person is after again: it starts high and grows
     * slowly with repetition. Any other pick lifts the row a little, for the
     * setting someone keeps coming back to by different words. Both fade with
     * a two-month half-life, so what mattered in the spring does not decide
     * the autumn.
     */
    override fun pointsFor(key: String, normalizedQuery: String): Int {
        val pick = picks[key] ?: return 0
        val ageDays = (nowMillis() - pick.lastMillis).coerceAtLeast(0) / DAY_MILLIS.toDouble()
        val recency = HALF.pow(ageDays / HALF_LIFE_DAYS)
        val repeats = ln(1.0 + pick.count)
        val linked = normalizedQuery.isNotEmpty() && pick.queries.any {
            it.startsWith(normalizedQuery) || normalizedQuery.startsWith(it)
        }
        val raw = if (linked) LINKED_BASE + LINKED_PER_REPEAT * repeats else ANY_PER_REPEAT * repeats
        val cap = if (linked) LINKED_CAP else ANY_CAP
        return min(cap.toDouble(), raw * recency).toInt()
    }

    fun encode(): String = JSON.encodeToString(this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** A history read back from [encode]; empty when the text is not one. */
        fun decode(text: String?): SearchHistory {
            if (text.isNullOrBlank()) return SearchHistory()
            return try {
                JSON.decodeFromString<SearchHistory>(text)
            } catch (_: SerializationException) {
                SearchHistory()
            } catch (_: IllegalArgumentException) {
                SearchHistory()
            }
        }

        /** How many rows are remembered. Past this the least recent goes. */
        const val MAX_PICKS = 80

        private const val QUERIES_PER_PICK = 6
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val HALF = 0.5
        private const val HALF_LIFE_DAYS = 60.0

        /** A pick from the same query: worth a title-word tier on its own. */
        private const val LINKED_BASE = 10.0
        private const val LINKED_PER_REPEAT = 6.0

        /**
         * Under the gap between a title that starts with the word (80) and one
         * that is the word (100): history reorders the near-misses, never the
         * exact hit.
         */
        private const val LINKED_CAP = 30

        private const val ANY_PER_REPEAT = 3.0
        private const val ANY_CAP = 8
    }
}

/**
 * [SearchHistory] on disk, in its own `SharedPreferences` file for the same
 * reasons as [EggPrefs]: the IME never reads it, it has no place in a settings
 * export, and `KeyboardSettings` has no room. One JSON string; the whole
 * history is a few kilobytes at most.
 */
internal class SearchPicks(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The history as last read or written. Mutate through [record] and [clear]. */
    var history: SearchHistory = SearchHistory.decode(prefs.getString(KEY_HISTORY, null))
        private set

    fun record(query: String, key: String) {
        history.record(query, key, System.currentTimeMillis())
        save()
    }

    fun clear() {
        history.clear()
        save()
    }

    private fun save() = prefs.edit { putString(KEY_HISTORY, history.encode()) }

    private companion object {
        const val FILE_NAME = "settings_search_picks"
        const val KEY_HISTORY = "history"
    }
}
