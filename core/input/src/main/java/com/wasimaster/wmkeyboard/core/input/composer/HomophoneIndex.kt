package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Homophone search (AnySoftKeyboard's `HOMOPHONE_SEARCH`): the characters
 * that share a reading with one character, read backwards out of a
 * conversion table that only maps readings to words.
 *
 * Built once per table from its single-character rows, so a lookup is two
 * map reads. Pure: the device builds it from the pinyin, Jyutping and kana
 * packs and from RIME's `.dict.yaml` sources, the tests from plain entries.
 *
 * A character with several readings (行 is `xing` and `hang`) lists its
 * homophones reading by reading, its most usual reading first: the one under
 * which it ranks highest among that reading's characters.
 */
class HomophoneIndex private constructor(
    /** Each character's readings, most usual first. */
    private val readingsOf: Map<String, List<String>>,
    /** Each reading's single characters, commonest first. */
    private val charsOf: Map<String, List<String>>,
) {

    /** One row of a table: a word, the reading that spells it, and how common it is (higher is commoner). */
    data class Entry(val reading: String, val word: String, val weight: Double)

    val isEmpty: Boolean get() = charsOf.isEmpty()

    /** [char]'s readings, most usual first; empty when the table does not know it. */
    fun readings(char: String): List<String> = readingsOf[char].orEmpty()

    /**
     * The characters that sound like [char], [char] itself left out, each
     * once, up to [limit]: every character of its most usual reading first,
     * then of the next. [fallbackReading] is asked when the table does not
     * know [char] at all: a kana is its own reading, so `か` lists 化, 下, 火.
     */
    fun homophones(char: String, limit: Int = DEFAULT_LIMIT, fallbackReading: String? = null): List<String> {
        val readings = readingsOf[char] ?: listOfNotNull(fallbackReading)
        val out = LinkedHashSet<String>()
        for (reading in readings) {
            for (other in charsOf[reading].orEmpty()) {
                if (other != char) out.add(other)
                if (out.size >= limit) return out.toList()
            }
        }
        return out.toList()
    }

    companion object {
        /** The most a strip is offered: a scrolling row and a grid behind it. */
        const val DEFAULT_LIMIT = 60

        val EMPTY = HomophoneIndex(emptyMap(), emptyMap())

        /** Whether [text] is one character, as Java counts code points. */
        fun isOneCharacter(text: String): Boolean =
            text.isNotEmpty() && text.codePointCount(0, text.length) == 1

        /**
         * Whether [text] is one character a homophone search can be about: a
         * Han ideograph (Chinese, kanji) or a kana.
         */
        fun isSearchable(text: String): Boolean {
            if (!isOneCharacter(text)) return false
            return when (Character.UnicodeScript.of(text.codePointAt(0))) {
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                -> true
                else -> false
            }
        }

        /**
         * The index of [entries]' single-character words. Several entries for
         * one reading and word (two sources, or a list beside a pack) count
         * once, at their best weight.
         */
        fun build(entries: Sequence<Entry>): HomophoneIndex {
            val best = HashMap<String, HashMap<String, Double>>()
            for (entry in entries) {
                if (entry.reading.isEmpty() || !isOneCharacter(entry.word)) continue
                best.getOrPut(entry.reading) { HashMap() }.merge(entry.word, entry.weight, ::maxOf)
            }
            if (best.isEmpty()) return EMPTY
            val charsOf = HashMap<String, List<String>>(best.size)
            // Where each character ranks under each of its readings: its most
            // usual reading is the one it leads the most.
            val ranks = HashMap<String, MutableList<Pair<String, Int>>>()
            for ((reading, words) in best) {
                val ordered = words.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                    .map { it.key }
                charsOf[reading] = ordered
                ordered.forEachIndexed { rank, word -> ranks.getOrPut(word) { ArrayList(2) }.add(reading to rank) }
            }
            val readingsOf = HashMap<String, List<String>>(ranks.size)
            for ((word, at) in ranks) {
                readingsOf[word] = at.sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first }).map { it.first }
            }
            return HomophoneIndex(readingsOf, charsOf)
        }
    }
}
