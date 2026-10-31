package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.SpellingMap
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.PhoneticCorrection
import com.wasimaster.wmkeyboard.core.prediction.WordSource
import com.wasimaster.wmkeyboard.core.transliteration.PhoneticIndex
import kotlin.math.ln

/**
 * Swiping a phonetic layout, where the keys are Latin and the text is not.
 *
 * Avro is the case. Its grid is QWERTY and its output is Bengali, so a stroke
 * over it draws a *romanization* — "amake", "bhalo" — and nothing in the Bengali
 * word list can be matched against that shape. The rest of the decoder's world
 * has the layout's characters and the dictionary's characters be the same
 * alphabet; here they are two alphabets with a translation between them, and
 * this is that translation.
 *
 * The romanized side is decoded exactly like any other language: a trie of
 * spellings, walked by the same beam, scored the same way. Only the last step
 * differs — a decoded spelling is resolved to the Bengali it stands for, and it
 * is the Bengali that is offered and committed.
 *
 * Two sources of spellings, in that order of trust:
 *
 *  - **The curated maps** that ship with the app: the loanword list and the
 *    romanized-Bengali list behind [SpellingMap], about fourteen thousand
 *    spellings of the kind people actually type, including the vowel-dropping
 *    chat forms ("tmr", "amk") that no phonetic rule reaches.
 *  - **The downloaded romanized word list**, when the user has one. It is
 *    consulted as a trie in place rather than read into memory, and a spelling
 *    found only there is resolved through [PhoneticIndex] — the same
 *    lenient fold that turns "valo asi" into ভালো আছি while typing.
 *
 * Avro is the worked example throughout, but nothing here is Bengali: any
 * phonetic scheme with a spelling map and a [PhoneticIndex] builds one ([of]).
 *
 * A spelling with no Bengali behind it is dropped rather than committed as
 * Latin: on a phonetic layout, Latin output is never what the user asked for.
 */
class RomanizedIndex private constructor(
    /**
     * The curated spellings, packed: they never change once built, and as a
     * node-per-letter [com.wasimaster.wmkeyboard.core.prediction.Trie] the
     * Bengali set alone held 4 MB of heap for as long as the keyboard ran.
     */
    private val curated: WordSource,
    private val downloaded: WordSource,
    private val resolveSpelling: (String) -> List<String>,
    /** Code points the romanized side is written in — the coverage gate's input. */
    val alphabet: Set<Int>,
) {

    val isEmpty: Boolean get() = alphabet.isEmpty()

    /**
     * The tries a stroke is decoded against. The curated list leads: it is a
     * record of how people write these words, where the downloaded list is a
     * record of which words exist.
     */
    fun walkSources(): List<FuzzyBeamSearch.WalkSource> = buildList {
        for (walker in curated.walkers()) {
            add(FuzzyBeamSearch.WalkSource(walker, CURATED_BONUS, FuzzyBeamSearch.Tier.DICTIONARY))
        }
        for (walker in downloaded.walkers()) {
            add(FuzzyBeamSearch.WalkSource(walker, 0.0, FuzzyBeamSearch.Tier.DICTIONARY))
        }
    }

    /** The words [spelling] stands for, best first. */
    fun readingsOf(spelling: String): List<String> = resolveSpelling(spelling)

    /** Whether either list holds [spelling] as it stands: nobody mistyped it. */
    fun spells(spelling: String): Boolean = curated.contains(spelling) || downloaded.contains(spelling)

    /**
     * How much of a record there is of [spelling] being written, on the
     * downloaded list's scale. A curated spelling only has its native word's
     * frequency to go by, scaled down to that ([PhoneticCorrection.NATIVE_PER_SPELLING]).
     */
    fun spellingEvidence(spelling: String): Int = maxOf(
        downloaded.frequencyOf(spelling),
        curated.frequencyOf(spelling) / PhoneticCorrection.NATIVE_PER_SPELLING,
    )

    /**
     * Turns decoded spellings into the words they stand for.
     *
     * One spelling can mean several things — "ki" is both কি and কী — so each
     * is offered, the alternates a little behind the spelling's own score. Two
     * spellings can also mean the same thing, which is the common case once the
     * curated and downloaded lists both have an opinion; those merge, keeping
     * whichever route scored better.
     */
    fun resolve(decoded: List<GlideBeam.Candidate>): List<GlideBeam.Candidate> {
        val best = LinkedHashMap<String, GlideBeam.Candidate>()
        for (candidate in decoded) {
            val forms = resolveSpelling(candidate.word)
            for ((index, form) in forms.withIndex()) {
                val score = candidate.score - index * ALTERNATE_STEP
                val existing = best[form]
                if (existing == null || score > existing.score) {
                    best[form] = GlideBeam.Candidate(
                        form, score, candidate.shapeCost, candidate.tier,
                    )
                }
            }
        }
        return best.values.sortedWith(
            compareByDescending<GlideBeam.Candidate> { it.score }.thenBy { it.word }
        )
    }

    companion object {

        /** No romanization available — the layout is not phonetic, or its language
         * is not loaded. Decoding falls back to the ordinary word sources. */
        val EMPTY = RomanizedIndex(PackedTrie.EMPTY, PackedTrie.EMPTY, { emptyList() }, emptySet())

        /**
         * Builds a phonetic language's romanization from what the IME has loaded.
         *
         * [nativeFrequency] scores a spelling by the native word behind it, so
         * the romanized trie inherits the real language model rather than
         * inventing one — a spelling of a common word outranks a spelling of a
         * rare one, which is the whole reason a swipe over Avro can be decided
         * at all. [downloadedRomanized] is the user's romanized word list if
         * they have one and [PackedTrie.EMPTY] if they do not.
         */
        fun of(
            spellings: SpellingMap,
            phonetic: PhoneticIndex,
            downloadedRomanized: WordSource = PackedTrie.EMPTY,
            nativeFrequency: (String) -> Int,
        ): RomanizedIndex {
            val curated = ArrayList<Pair<String, Int>>()
            val alphabet = HashSet<Int>()
            for (spelling in spellings.spellings) {
                if (spelling.length < MIN_SPELLING || !spelling.all { it.isLetter() }) continue
                val forms = spellings.lookup(spelling)
                if (forms.isEmpty()) continue
                val frequency = forms.maxOf(nativeFrequency).coerceAtLeast(1)
                curated += spelling to frequency
                for (ch in spelling) alphabet.add(ch.lowercaseChar().code)
            }
            if (alphabet.isEmpty()) return EMPTY
            // Curated first: where the hand-written map and the lenient fold
            // disagree about a spelling, the hand-written one is the record of
            // what somebody meant by it.
            val resolve = { spelling: String ->
                spellings.lookup(spelling).ifEmpty { phonetic.lookup(spelling) }
            }
            return RomanizedIndex(PackedTrie.of(curated), downloadedRomanized, resolve, alphabet)
        }

        /**
         * A romanization whose spellings each stand for exactly one word,
         * which [convert] gives back: Khipro, where a key sequence has one
         * meaning. [personal] is walked ahead of [spellings], as the curated
         * map is for Avro: the user's own words are the likelier ones.
         */
        fun deterministic(
            spellings: WordSource,
            personal: WordSource,
            /** Every character a spelling may hold: the keys a stroke can pass through. */
            keys: String,
            convert: (String) -> String,
        ): RomanizedIndex {
            val alphabet = keys.mapTo(HashSet()) { it.code }
            val resolve = { spelling: String -> listOf(convert(spelling)).filter { it.isNotEmpty() } }
            return RomanizedIndex(personal, spellings, resolve, alphabet)
        }

        /** How far behind its spelling's own score a second reading starts. */
        private const val ALTERNATE_STEP = 0.35

        /** Weight the curated spellings walk at, over the downloaded list's. */
        private val CURATED_BONUS = ln(3.0)

        /** One- and two-letter romanizations are tapped, not swiped. */
        private const val MIN_SPELLING = 3
    }
}
