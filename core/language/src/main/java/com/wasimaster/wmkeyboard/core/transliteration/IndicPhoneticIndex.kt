package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Reverse-phonetic lookup for the [IndicPhonetic] languages: [HindiPhoneticIndex]'s
 * fold, reached through Devanagari.
 *
 * Every word of the list is spelled in Devanagari ([IndicScript.toDevanagari]:
 * the blocks are parallel, so that is a shift and a handful of script-specific
 * signs) and filed by the Hindi fold, which already forgets everything a
 * romanized spelling of any of these languages leaves out — aspiration, dental
 * against retroflex, vowel length, the inherent vowel, conjuncts — and keeps
 * as detail what it can rank by. A lookup answers in the list's own script,
 * because the fold remembers which word each Devanagari spelling came from.
 *
 * What a language adds ([IndicProfiles]):
 *
 *  - [romanPrep], its romanization's habits rewritten into the spelling the
 *    Hindi fold expects. Tamil writes no voiced stops, so "ganga" has to ask
 *    for the k that கங்கா folds to; "zh" is the Tamil and Malayalam ழ / ഴ,
 *    which the fold files under l.
 *  - [devanagariPrep], the same done to the list's words, where a distinction
 *    has to go from both sides (Malayalam's voicing).
 *  - [rerankBy], the rules' own reading, for a list with no frequencies: the
 *    fold's ties are then broken by how close each candidate is to what the
 *    rules spell — a better prior than none when the list ranks every word the
 *    same and is full of tokens that only share a key with the real word.
 */
class IndicPhoneticIndex(
    table: PhoneticTable,
    /**
     * A word's plain Devanagari ([IndicScript.toDevanagari]), without the
     * language's prep: what tells two spellings of one word from two words.
     * Malayalam's prep folds voicing, which would make അപു and അബു one word.
     */
    private val spellingOf: (String) -> String,
    private val romanPrep: (String) -> String = { it },
    private val rerankBy: ((String) -> String)? = null,
    /** See [IndicProfiles.typedSchwa]. */
    typedSchwa: Boolean = false,
) : PhoneticIndex {

    /** An index built on the device over [entries], `word to frequency`. */
    constructor(
        entries: List<Pair<String, Int>>,
        script: IndicScript,
        romanPrep: (String) -> String = { it },
        devanagariPrep: (String) -> String = { it },
        rerankBy: ((String) -> String)? = null,
    ) : this(table(entries, script, devanagariPrep), script::toDevanagari, romanPrep, rerankBy)

    // Rows keep the word as the list spells it and are filed by its
    // Devanagari, so the Hindi index answers in the list's own script.
    private val inner = HindiPhoneticIndex(table, typedSchwa)

    /** Every word at the same count: no frequency to rank by. */
    private val flat: Boolean = !table.isEmpty && table.maxFrequency == table.minFrequency

    override val isEmpty: Boolean get() = inner.isEmpty

    override fun lookup(input: String): List<String> {
        // Two spellings of one word can meet in Devanagari (a precomposed and
        // a decomposed nukta letter, a chillu and its consonant with a
        // virama): the better-ranked one, the commoner, answers for both.
        val found = inner.lookup(romanPrep(input)).let { all ->
            if (all.size < 2) all else all.distinctBy(spellingOf)
        }
        val rules = rerankBy
        if (!flat || rules == null || found.size < 2) return found
        val reference = rules(input)
        val head = found.take(RERANK_DEPTH).withIndex()
            .sortedWith(compareBy({ editDistance(it.value, reference) }, { it.index }))
            .map { it.value }
        return head + found.drop(RERANK_DEPTH)
    }

    override fun frequencyOf(word: String): Int = inner.frequencyOf(word)

    override fun matchStrength(input: String): Int = inner.matchStrength(romanPrep(input))

    override val maxFrequency: Int get() = inner.maxFrequency

    companion object {

        /**
         * Files [entries], at most the [boundedEntries] budget of them, by the
         * Hindi fold over each word's Devanagari spelling.
         */
        fun table(
            entries: List<Pair<String, Int>>,
            script: IndicScript,
            devanagariPrep: (String) -> String = { it },
        ): PhoneticTable = HindiPhoneticIndex.table(boundedEntries(entries)) { devanagariPrep(script.toDevanagari(it)) }

        /** How many of the fold's candidates the re-rank reorders. */
        private const val RERANK_DEPTH = 30

        private fun editDistance(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in a.indices) {
                val current = IntArray(b.length + 1)
                current[0] = i + 1
                for (j in b.indices) {
                    val cost = if (a[i] == b[j]) 0 else 1
                    current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + cost)
                }
                previous = current
            }
            return previous[b.length]
        }
    }
}

/**
 * At most [max] of [entries], the ones a lookup is likeliest to want.
 *
 * Some of the downloadable lists are enormous — Tamil's has 1.9 million words,
 * Arabic's 2.5 million — and a phonetic index costs a few hundred bytes a word
 * on the heap, which for those is hundreds of megabytes in a keyboard. Past the
 * budget the commonest words are kept; a list with no frequencies (every word
 * at the same count, as Tamil's and Urdu's are) keeps its shortest words
 * instead, the nearest thing to a frequency a flat list has — and never an
 * alphabetical slice, which is what taking the first [max] would be, since
 * flat lists are stored in alphabetical order.
 */
internal fun boundedEntries(entries: List<Pair<String, Int>>, max: Int = PHONETIC_INDEX_BUDGET): List<Pair<String, Int>> {
    if (entries.size <= max) return entries
    val flat = entries.all { it.second == entries[0].second }
    val ranked = if (flat) entries.sortedBy { it.first.length } else entries.sortedByDescending { it.second }
    return ranked.subList(0, max)
}

/** How many words a phonetic index files at most; see [boundedEntries]. */
internal const val PHONETIC_INDEX_BUDGET = 300_000
