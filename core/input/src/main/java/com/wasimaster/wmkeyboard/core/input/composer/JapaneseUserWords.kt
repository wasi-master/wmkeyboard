package com.wasimaster.wmkeyboard.core.input.composer

import java.util.TreeMap

/**
 * The user's own Japanese words, each under the kana reading it was registered
 * with: a Gboard personal dictionary's `よろ → よろしくお願いします`, a Mozc or
 * Google Japanese Input user dictionary, any list imported for Japanese that
 * carries readings (#1254).
 *
 * The built-in Japanese composer already gets these merged into its table
 * ([ConversionWordLists], #633). Mozc ranks from its own dictionary and never
 * sees that table, so it is handed them here instead, and `MozcComposer` puts
 * them among its candidates: a word whose reading is exactly what was typed
 * first, as a dictionary the user filled in themselves should be, and words
 * whose reading carries on from it a little further down, as completions.
 *
 * Readings are hiragana, as [ConversionWordLists] writes them.
 */
class JapaneseUserWords private constructor(private val byReading: TreeMap<String, List<String>>) {

    val isEmpty: Boolean get() = byReading.isEmpty()

    /** How many words there are, across every reading. */
    val size: Int get() = byReading.values.sumOf { it.size }

    /** The words registered under exactly [reading], in the order they were listed. */
    fun exact(reading: String): List<String> = byReading[reading].orEmpty()

    /**
     * Words whose reading starts with [prefix] and goes on past it, shortest
     * reading first, at most [limit]. Nothing for a prefix of one kana: every
     * word starting with "よ" is not a suggestion, it is noise.
     */
    fun completions(prefix: String, limit: Int): List<String> {
        if (prefix.length < MIN_PREFIX || limit <= 0) return emptyList()
        val found = ArrayList<Pair<String, String>>()
        for ((reading, words) in byReading.subMap(prefix, false, prefix + Char.MAX_VALUE, false)) {
            for (word in words) found += reading to word
            if (found.size >= SCAN_LIMIT) break
        }
        return found.sortedBy { it.first.length }.map { it.second }.distinct().take(limit)
    }

    companion object {
        val EMPTY = JapaneseUserWords(TreeMap())

        /** A prefix shorter than this offers no completions. */
        const val MIN_PREFIX = 2

        /** Far past any hand-made dictionary; a list beyond it is a corpus, which the table already has. */
        const val MAX_WORDS = 50_000

        /** How many continuations [completions] looks at before ranking them. */
        private const val SCAN_LIMIT = 64

        /** [entries] as (reading, word) pairs, the first spelling of a repeat kept, capped at [MAX_WORDS]. */
        fun of(entries: List<Pair<String, String>>): JapaneseUserWords {
            val map = TreeMap<String, MutableList<String>>()
            var count = 0
            for ((reading, word) in entries) {
                if (count >= MAX_WORDS) break
                if (reading.isEmpty() || word.isEmpty()) continue
                val words = map.getOrPut(reading) { ArrayList() }
                if (word in words) continue
                words += word
                count++
            }
            return if (map.isEmpty()) EMPTY else JapaneseUserWords(map.mapValuesTo(TreeMap()) { it.value.toList() })
        }
    }
}
