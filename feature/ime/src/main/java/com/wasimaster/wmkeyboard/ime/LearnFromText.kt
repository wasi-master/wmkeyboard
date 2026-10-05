package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.prediction.TextWordScan
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.prediction.WordContext
import com.wasimaster.wmkeyboard.core.prediction.WordKey
import com.wasimaster.wmkeyboard.core.settings.LearnFromTextSort

/** One word the Learn from text panel offers to add (#174). */
data class LearnRow(
    val key: String,
    /** The spelling the text used; see [TextWordScan.Word.spelling]. */
    val spelling: String,
    /** The user's own spelling from Edit, or null to add [spelling] as found. */
    val edited: String? = null,
    /** How many times the word appears in the scanned text. */
    val count: Int,
    /** Sightings already collected while typing (the waiting room), 0 for none. */
    val seen: Int = 0,
    val checked: Boolean = true,
    /** The first occurrence, relative to the start of the scanned text. */
    val start: Int,
    val length: Int,
    val caseEvidence: Boolean,
) {
    /** What Add puts in the personal dictionary. */
    val finalSpelling: String get() = edited ?: spelling
}

/**
 * The Learn from text panel's state; null on [KeyboardUiState] while it is closed.
 *
 * The scan itself (every word and pair in the text) stays in the service: the
 * panel only draws rows, and a text of thousands of words would otherwise be
 * compared on every state emission.
 */
data class LearnFromTextUi(
    val scanning: Boolean = true,
    val rows: List<LearnRow> = emptyList(),
    /** Where offset 0 of the scanned text sits in the field, or null when unknown. */
    val origin: Int? = null,
    /** Only part of the field could be read. */
    val partial: Boolean = false,
    /** Found more unknown words than the panel lists. */
    val capped: Boolean = false,
    /** A password or no-learning field: nothing was read. */
    val blocked: Boolean = false,
    /** The key of the row whose spelling is being edited, or null. */
    val editing: String? = null,
    val editText: String = "",
    /** What the last Add did, shown in the header until the next change. */
    val result: LearnResult? = null,
) {
    val checkedCount: Int get() = rows.count { it.checked }

    /** The spelling being typed would be accepted. */
    val editValid: Boolean
        get() {
            val text = editText.trim()
            return text.length in 2..UserLexicon.MAX_WORD_LENGTH && WordContext.isLearnableWord(text)
        }
}

/** Counts for the result line after an Add. */
data class LearnResult(val words: Int, val pairs: Int)

/** The word pairs, triples and skip-grams an Add should teach, along with occurrence counts. */
data class LearnPlan(
    val pairCounts: Map<Pair<String, String>, Int> = emptyMap(),
    val tripleCounts: Map<Triple<String, String, String>, Int> = emptyMap(),
    val skipCounts: Map<Pair<String, String>, Int> = emptyMap(),
    /** The 2-skip bigrams, the word three back and the word (#195). */
    val skip2Counts: Map<Pair<String, String>, Int> = emptyMap(),
) {
    val pairs: Set<Pair<String, String>> get() = pairCounts.keys
    val triples: Set<Triple<String, String, String>> get() = tripleCounts.keys
    val skips: Set<Pair<String, String>> get() = skipCounts.keys
    val skips2: Set<Pair<String, String>> get() = skip2Counts.keys

    val size: Int get() = pairCounts.size + tripleCounts.size + skipCounts.size + skip2Counts.size
}

/** The panel's rules, apart from the service so they are unit-tested. */
object LearnFromText {

    /** Rows past this are not listed; the header says the list was cut. */
    const val MAX_ROWS = 2_000

    /**
     * The words in [scan] worth offering: long enough to learn, not blacklisted,
     * and unknown to every source [isKnown] asks (dictionaries, the personal
     * dictionary, contacts, apps, Android's dictionary).
     */
    fun rowsFor(
        scan: TextWordScan.Result,
        isKnown: (String) -> Boolean,
        blacklist: Set<String>,
        sightings: (String) -> Int,
    ): List<LearnRow> = scan.words
        .asSequence()
        .filter { it.key.length in 2..UserLexicon.MAX_WORD_LENGTH }
        .filter { it.key !in blacklist }
        .filter { !isKnown(it.key) }
        .map { word ->
            LearnRow(
                key = word.key,
                spelling = word.spelling,
                count = word.count,
                seen = sightings(word.key),
                start = word.firstStart,
                length = word.firstLength,
                caseEvidence = word.caseEvidence,
            )
        }
        .toList()

    /** [rows] in the order [sort] names; ties go to the order in the text. */
    fun sorted(rows: List<LearnRow>, sort: LearnFromTextSort): List<LearnRow> = when (sort) {
        LearnFromTextSort.MOST_FREQUENT -> rows.sortedWith(compareByDescending<LearnRow> { it.count }.thenBy { it.start })
        LearnFromTextSort.TEXT_ORDER -> rows.sortedBy { it.start }
        LearnFromTextSort.ALPHABETICAL -> rows.sortedWith(compareBy<LearnRow> { it.finalSpelling.lowercase() }.thenBy { it.start })
    }

    /**
     * What to teach from [scan] once the words have been added.
     *
     * [renames] maps a row's key to the key of the spelling the user edited it
     * to, so "teh cat" teaches "the cat". Every end of every pair must then be
     * a word the keyboard knows ([isKnown], asked after the add) and not on the
     * blacklist: the same gate typing uses, so a word the user left unchecked
     * never comes back as a next-word suggestion.
     */
    fun plan(
        scan: TextWordScan.Result,
        renames: Map<String, String>,
        isKnown: (String) -> Boolean,
        blacklist: Set<String>,
    ): LearnPlan {
        val verdicts = HashMap<String, Boolean>()
        fun map(key: String) = renames[key] ?: key
        fun ok(key: String) = verdicts.getOrPut(key) { key !in blacklist && isKnown(key) }

        val pairs = LinkedHashMap<Pair<String, String>, Int>()
        for ((pairKey, count) in scan.pairCounts) {
            val (a, b) = pairKey
            val x = map(a)
            val y = map(b)
            if (ok(x) && ok(y)) {
                val pair = x to y
                pairs[pair] = (pairs[pair] ?: 0) + count
            }
        }

        val triples = LinkedHashMap<Triple<String, String, String>, Int>()
        for ((tripleKey, count) in scan.tripleCounts) {
            val (a, b, c) = tripleKey
            val x = map(a)
            val y = map(b)
            val z = map(c)
            if (ok(x) && ok(y) && ok(z)) {
                val triple = Triple(x, y, z)
                triples[triple] = (triples[triple] ?: 0) + count
            }
        }

        val skips = LinkedHashMap<Pair<String, String>, Int>()
        for ((skipKey, count) in scan.skipCounts) {
            val (a, b) = skipKey
            val x = map(a)
            val y = map(b)
            if (ok(x) && ok(y)) {
                val skip = x to y
                skips[skip] = (skips[skip] ?: 0) + count
            }
        }

        val skips2 = LinkedHashMap<Pair<String, String>, Int>()
        for ((skip2Key, count) in scan.skip2Counts) {
            val (a, b) = skip2Key
            val x = map(a)
            val y = map(b)
            if (ok(x) && ok(y)) {
                val skip2 = x to y
                skips2[skip2] = (skips2[skip2] ?: 0) + count
            }
        }

        return LearnPlan(pairs, triples, skips, skips2)
    }

    /** The key an edited row is renamed to, for [plan]. */
    fun renamesOf(rows: List<LearnRow>): Map<String, String> =
        rows.mapNotNull { row -> row.edited?.let { row.key to WordKey.of(it.trim()) } }.toMap()
}
