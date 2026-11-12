package com.wasimaster.wmkeyboard.core.prediction

import java.io.InputStream

/**
 * Bundled next-word pairs so prediction works from the first keystroke.
 * The user's own bigrams ([UserLexicon]) always rank ahead of these; the
 * seed list only fills the strip until enough personal history exists.
 *
 * Format: one `previous next frequency` triple per line, `#` comments and
 * malformed lines skipped. `previous` may be [SENTENCE_START_TOKEN], which
 * stands for the sentence-start sentinel: those pairs are the first word of a
 * message, and they are what fills the strip before the user has typed enough
 * for their own openers to be known (#119).
 */
class SeedBigrams private constructor(
    private val map: Map<String, List<Pair<String, Int>>>,
    /**
     * Every `previous → follower` pair between two real words, in file order
     * and both sides as keys.
     *
     * Read by the evaluation tests, which used to re-parse the asset four
     * times over — four copies of this loop that had to be kept in step with
     * the format by hand, and were not. The sentence openers are left out on
     * purpose: their `previous` is the sentinel rather than a word, so nothing
     * that measures what a context term is worth has a context to measure.
     */
    val wordPairs: List<Pair<String, String>>,
) {

    /** Continuations of [previous], best first. */
    fun nextWords(previous: String): List<String> =
        map[previous]?.map { it.first }.orEmpty()

    /** Whether the bundled pairs know [next] as a follower of [previous].
     * Lists are short (a handful of seeds per word), so the scan is cheap. */
    fun follows(previous: String, next: String): Boolean =
        map[previous]?.any { it.first == next } == true

    /** The bundled count of the pair, 0 when unknown. The list carried these
     * all along; the context reranker is what finally reads them. */
    fun count(previous: String, next: String): Int =
        map[previous]?.firstOrNull { it.first == next }?.second ?: 0

    /**
     * Every pair as three parallel arrays (previous, next, count), the shape
     * the tap decoder takes them across JNI in. Built once.
     */
    internal val columns: Triple<Array<String>, Array<String>, IntArray> by lazy {
        val rows = map.flatMap { (previous, next) -> next.map { Triple(previous, it.first, it.second) } }
        Triple(
            Array(rows.size) { rows[it].first },
            Array(rows.size) { rows[it].second },
            IntArray(rows.size) { rows[it].third },
        )
    }

    companion object {
        val EMPTY = SeedBigrams(emptyMap(), emptyList())

        /**
         * How the file spells [WordContext.SENTENCE_START].
         *
         * The sentinel itself is U+0001, and writing a bare control character
         * into a text asset is asking for an editor or a diff tool to eat it —
         * the sentinel is built rather than typed in `WordContext` for exactly
         * that reason. `<s>` is the usual language-model spelling of the same
         * idea and survives any tool; it can never collide with a word,
         * because `<` and `>` are not word characters.
         */
        const val SENTENCE_START_TOKEN = "<s>"

        fun load(stream: InputStream): SeedBigrams {
            val pairs = HashMap<String, ArrayList<Pair<String, Int>>>()
            val words = ArrayList<Pair<String, String>>()
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size < 2) continue
                    val frequency = parts.getOrNull(2)?.toIntOrNull() ?: 1
                    val token = parts[0].lowercase()
                    val previous =
                        if (token == SENTENCE_START_TOKEN) WordContext.SENTENCE_START else token
                    pairs.getOrPut(previous) { ArrayList() }
                        .add(parts[1] to frequency)
                    if (previous != WordContext.SENTENCE_START) {
                        words.add(previous to parts[1].lowercase())
                    }
                }
            }
            return SeedBigrams(
                pairs.mapValues { (_, list) -> list.sortedByDescending { it.second } },
                words,
            )
        }
    }
}
