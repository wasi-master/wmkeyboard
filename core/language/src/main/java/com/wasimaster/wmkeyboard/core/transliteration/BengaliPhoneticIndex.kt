package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Reverse-phonetic lookup from romanized Bengali to dictionary words.
 *
 * Banglish spelling is loose — আছি gets typed as "asi", "achi" or "achhi".
 * Instead of trying to predict every spelling, both the romanized input and
 * each Bengali dictionary word are folded to a lenient canonical key where
 * confusable sounds collapse (স/শ/ষ/ছ/চ → s, ত/ট → t, ব/ভ → b, aspiration
 * dropped, inherent vowels dropped). Words sharing a key are phonetic
 * siblings; the highest-frequency sibling is offered as the primary
 * suggestion, which is what makes "ami valo asi" become আমি ভালো আছি.
 *
 * Frequency alone is too blunt to order those siblings, because the key
 * throws away two things the typist actually wrote down:
 *
 *  1. **Aspiration.** থাকে and তাকে share the key "tk", and তাকে is the more
 *     common word — so "thake" used to come back as তাকে, silently replacing
 *     the word the h was there to ask for. The [aspiration] mask records, per
 *     key position, whether that consonant was written aspirated, and siblings
 *     that contradict what was typed are ranked down. It is a ranking signal
 *     rather than a filter, and deliberately lopsided: typing the h and not
 *     getting it ([ASPIRATION_MISMATCH_TYPED]) is a worse error than leaving it
 *     out and getting it anyway ([ASPIRATION_MISMATCH_WORD]), since casual
 *     romanization drops an h far more readily than it invents one. Only the
 *     ক/খ, গ/ঘ, ত/থ, ট/ঠ, দ/ধ and ড/ঢ pairs count: "ch" for চ and "ph" for ফ
 *     are ordinary English-influenced spellings, not claims about aspiration.
 *  2. **A final ও.** [dropTrailingO] folds ভালো and ভাল together so both are
 *     reachable from "valo", but it also folds কথা together with কথাও — and ও
 *     is a whole word's worth of meaning ("also"). [endsWithO] keeps the two
 *     apart again, so "kotha" answers কথা and only "kothao" answers কথাও.
 */
class BengaliPhoneticIndex(private val table: PhoneticTable) : PhoneticIndex {

    /** An index built on the device over [entries], `word to frequency`. */
    constructor(entries: List<Pair<String, Int>>) : this(table(entries))

    // ## Layout
    //
    // A [PhoneticTable], as every phonetic language's index is (#716): each
    // word filed under its key with its aspiration pattern as the row's detail
    // and [FLAG_ENDS_WITH_O] as its flag, and every word once in String order
    // for [sortedWords] and [completions]. The downloaded list's table is built
    // by the data repo and mapped, so the 451k words of the largest tier cost
    // no heap; one built on the device over the bundled list or an import is
    // the same bytes in an array. Within a key rows are commonest first, then
    // in the order they were filed, which is the order the old per-key lists
    // were in, so ties still break the same way.

    override val isEmpty: Boolean get() = table.isEmpty

    override val maxFrequency: Int get() = table.maxFrequency

    /** The table's words, commonest first among the matches of a search. */
    override val sortedWords: SortedWords get() = table.words

    /** Dictionary words phonetically matching the romanized [input], best first. */
    override fun lookup(input: String): List<String> {
        val typedFinalO = input.isNotEmpty() && input.last().lowercaseChar() in "ow"
        return ranked(foldRomanFull(input), typedFinalO)
    }

    /**
     * [lookup], then the words reached by reading a typed `o` between two
     * consonants as ো rather than as the inherent vowel.
     *
     * The fold drops that `o` so that "kori" meets করি, whose inherent vowel is
     * not written. But a ো in the middle of a word is written, so the same rule
     * left every such word out of reach of the `o` that spells it: "vodor" could
     * not find ভোঁদড় (issue #516, fourth on desktop Avro's list), "dokan" not
     * দোকান, "vot" not ভোট. Avro's regular expression lets each `o` be either,
     * and so does this: every way of keeping some of those vowels, the plain
     * fold's words first. Capped at [LOOSE_VARIANTS] keys; four medial vowels
     * already make sixteen.
     */
    override fun lookupLoose(input: String): List<String> {
        val typedFinalO = input.isNotEmpty() && input.last().lowercaseChar() in "ow"
        val raw = foldRomanRaw(input)
        val out = LinkedHashSet<String>()
        for (folded in inherentVariants(raw, LOOSE_VARIANTS)) out.addAll(ranked(folded, typedFinalO))
        return out.toList()
    }

    /** The rows under [folded]'s key, best first, or empty. */
    private fun ranked(folded: Folded, typedFinalO: Boolean): List<String> {
        val bucket = table.rows(folded.key)
        // One sibling is the overwhelmingly common case; skip the comparator.
        if (bucket.size <= 1) return bucket.map { it.word }
        return bucket
            .sortedByDescending { it.frequency.toLong() * SCALE / handicap(it, folded.aspiration, typedFinalO) }
            .map { it.word }
    }

    /**
     * What to divide [row]'s frequency by for disagreeing with what was
     * actually typed. 1 means it agrees on every count and its frequency
     * stands as it is.
     */
    private fun handicap(row: PhoneticRow, typed: String, typedFinalO: Boolean): Long {
        var divisor = 1L
        if ((row.flags and FLAG_ENDS_WITH_O != 0) != typedFinalO) divisor *= FINAL_O_MISMATCH
        // A row's aspiration pattern is as long as its key, and the key is the
        // typed one — that is how the row was found — so [typed], built in
        // lockstep with it, is exactly as long.
        for (i in typed.indices) {
            val typedAspirated = typed[i] == ASPIRATED
            val wordAspirated = row.detail[i] == ASPIRATED
            if (typedAspirated == wordAspirated) continue
            divisor *= if (typedAspirated) ASPIRATION_MISMATCH_TYPED else ASPIRATION_MISMATCH_WORD
        }
        return divisor
    }

    /**
     * Dictionary frequency of a Bengali [word], 0 when unknown. A word listed
     * twice (the bundled list and an imported one) answers with its higher
     * frequency.
     */
    override fun frequencyOf(word: String): Int = table.frequencyOf(word)

    /**
     * The table's words are in word order, so the words with [prefix] are one
     * contiguous run of them: binary-search the run's start and keep the
     * commonest [limit] while walking it. A one-letter prefix over a big
     * downloaded list is a run of tens of thousands, which is still a linear
     * pass on the suggestion thread, not a sort.
     */
    override fun completions(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val words = table.words
        var low = 0
        var high = words.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareWord(words, mid, prefix) < 0) low = mid + 1 else high = mid
        }
        // Min-heap on frequency: the head is the weakest word kept so far.
        val best = java.util.PriorityQueue<Int>(limit + 1, compareByDescending { words.rank(it) })
        var i = low
        while (i < words.size && startsWith(words, i, prefix)) {
            best.add(i)
            if (best.size > limit) best.poll()
            i++
        }
        val out = ArrayList<String>(best.size)
        while (best.isNotEmpty()) out.add(words.word(best.poll()))
        return out.asReversed()
    }

    /** Word [i] of [words] against [word], in [String.compareTo] order. */
    private fun compareWord(words: SortedWords, i: Int, word: String): Int {
        val length = words.length(i)
        for (k in 0 until minOf(length, word.length)) {
            val diff = words.charAt(i, k) - word[k]
            if (diff != 0) return diff
        }
        return length - word.length
    }

    /** Whether word [i] of [words] begins with [prefix]. */
    private fun startsWith(words: SortedWords, i: Int, prefix: String): Boolean {
        if (words.length(i) < prefix.length) return false
        for (k in prefix.indices) if (words.charAt(i, k) != prefix[k]) return false
        return true
    }

    override fun matchStrength(input: String): Int {
        val folded = foldRomanFull(input)
        val bucket = table.rows(folded.key)
        if (bucket.isEmpty()) return 0
        val typedFinalO = input.isNotEmpty() && input.last().lowercaseChar() in "ow"
        return bucket.maxOf { it.frequency / handicap(it, folded.aspiration, typedFinalO) }.toInt()
    }

    companion object {

        /** [PhoneticTable] fold id of this index's tables. */
        const val FOLD = 3

        /**
         * Version of the fold and of how words are filed. Bumped whenever either
         * changes, which makes every prebuilt table of the old one read as
         * absent (the data repo publishes the new one under a new name).
         *
         * Bumped as well when the bundled list (`app/dictionaries-src/bn.txt`)
         * changes: the published Bangla table has that list's ranking baked
         * in ([withBundledRanking]), and `BengaliPhoneticIndexTest` fails until
         * the two agree again.
         *
         * 2: filed over `bn_full_v2`, which has corpus counts, with the bundled
         * frequencies added to them rather than standing in for them.
         */
        const val REVISION = 2

        /** The word ends in ও or ো; see [endsWithO]. */
        private const val FLAG_ENDS_WITH_O = 1

        /**
         * Files [entries] by the Bengali fold. [spelling] gives the Bangla a
         * word is folded from, for a language written in its own letters of
         * the script ([AssamesePhoneticIndex]); the row keeps the word as the
         * list spells it. A word that folds to nothing is left out altogether.
         */
        fun table(
            entries: List<Pair<String, Int>>,
            spelling: ((String) -> String)? = null,
        ): PhoneticTable {
            val builder = PhoneticTableBuilder(FOLD, REVISION)
            file(builder, entries, spelling)
            return builder.build()
        }

        /** Files [entries] into [builder]; see [table]. */
        fun file(builder: PhoneticTableBuilder, entries: List<Pair<String, Int>>, spelling: ((String) -> String)? = null) {
            for ((word, frequency) in entries) {
                val bn = spelling?.invoke(word) ?: word
                val folded = foldBengaliFull(bn)
                if (folded.key.isEmpty()) continue
                // A row's detail is at most 255 positions. No word of a list
                // folds that long; one that did keeps its frequency unfiled.
                if (folded.aspiration.length > MAX_DETAIL) {
                    builder.addWord(word, frequency)
                    continue
                }
                builder.add(folded.key, word, frequency, folded.aspiration, if (endsWithO(bn)) FLAG_ENDS_WITH_O else 0)
            }
        }

        private const val MAX_DETAIL = 255

        /** Marks a key position whose consonant was written aspirated. */
        private const val ASPIRATED = 'h'
        private const val PLAIN = '.'

        /**
         * Disagreements divide a sibling's frequency rather than outranking
         * it outright, so the penalty is a handicap and not a veto. Typing
         * "tik" for ঠিক leaves an h out, which is ordinary — ঠিক should still
         * win on being the far commoner word. Typing "thake" and getting তাকে
         * is a different matter: that h was a deliberate request, so the
         * handicap has to be big enough to overturn তাকে being ~3x commoner.
         */
        private const val ASPIRATION_MISMATCH_TYPED = 20

        /** Left the h out and the word has one — ordinary casual spelling. */
        private const val ASPIRATION_MISMATCH_WORD = 2

        private const val FINAL_O_MISMATCH = 4

        /** Keeps the divided frequencies in integer arithmetic. */
        private const val SCALE = 1024L

        /** Most fold keys [lookupLoose] tries for one input. */
        private const val LOOSE_VARIANTS = 16

        /**
         * Bengali consonants whose aspiration a typist genuinely signals with
         * an h. ছ and ফ are absent on purpose: "ch" is how চ gets written by
         * anyone used to English, and "ph" is the ordinary spelling of ফ, so
         * neither h says anything about aspiration. ঝ and ভ likewise, since
         * "jh"/"bh" compete with the plain "j"/"v" spellings of the same
         * sounds.
         */
        private val aspiratedLetters = setOf('খ', 'ঘ', 'ঠ', 'ঢ', 'থ', 'ধ')

        /** Roman consonants where a following h is a real aspiration mark. */
        private const val ASPIRABLE_ROMAN = "kgtd"

        private val consonantClasses = mapOf(
            'ক' to 'k', 'খ' to 'k', 'গ' to 'g', 'ঘ' to 'g', 'ঙ' to 'q',
            'চ' to 's', 'ছ' to 's', 'জ' to 'j', 'ঝ' to 'j', 'ঞ' to 'q',
            'ট' to 't', 'ঠ' to 't', 'ড' to 'd', 'ঢ' to 'd', 'ণ' to 'n',
            'ত' to 't', 'থ' to 't', 'দ' to 'd', 'ধ' to 'd', 'ন' to 'n',
            'প' to 'p', 'ফ' to 'p', 'ব' to 'b', 'ভ' to 'b', 'ম' to 'm',
            'য' to 'j', 'র' to 'r', 'ল' to 'l', 'শ' to 's', 'ষ' to 's',
            'স' to 's', 'হ' to 'h',
            // খণ্ড-ত is a ত, and nobody spells it with a key of its own when
            // typing fast: "hotat" has to be able to reach হঠাৎ.
            'ৎ' to 't',
            // Nukta letters as precomposed code points (ড় ঢ় য়); decomposed
            // input is normalized before lookup.
            'ড়' to 'r', 'ঢ়' to 'r', 'য়' to 'y',
            'ং' to 'q', 'ঃ' to 'h',
        )

        private val vowelClasses = mapOf(
            'অ' to 'o', 'আ' to 'a', 'া' to 'a',
            'ই' to 'i', 'ঈ' to 'i', 'ি' to 'i', 'ী' to 'i',
            'উ' to 'u', 'ঊ' to 'u', 'ু' to 'u', 'ূ' to 'u',
            'এ' to 'e', 'ে' to 'e', 'ঐ' to 'i', 'ৈ' to 'i',
            'ও' to 'o', 'ো' to 'o', 'ঔ' to 'u', 'ৌ' to 'u',
            'ঋ' to 'r', 'ৃ' to 'r',
        )

        /**
         * A canonical key plus, character for character, which of its
         * positions were written aspirated. The two strings are built in
         * lockstep and edited in lockstep, so position `i` of [aspiration]
         * always describes position `i` of [key] — that alignment is what
         * lets the roman and Bengali sides be compared at all.
         */
        class Folded(val key: String, val aspiration: String)

        /**
         * [word] with its decomposed nukta pairs as precomposed code points,
         * and the corrupt U+0985 U+09BE pair (seen in scraped word lists) as
         * U+0986. NFC leaves the nukta pairs alone, since U+09DC, U+09DD and
         * U+09DF are composition exclusions, so the downloaded list and the
         * bundled one can spell the same word two ways.
         */
        private fun precomposed(word: String): String = word
            .replace("\u09A1\u09BC", "\u09DC")
            .replace("\u09A2\u09BC", "\u09DD")
            .replace("\u09AF\u09BC", "\u09DF")
            .replace("\u0985\u09BE", "\u0986")

        /**
         * [listed] with [bundled]'s ranking put back over it, the bundled
         * words in the bundled list's spelling (Avro's precomposed nukta).
         *
         * A flat list (`bn_full`, where all 451k words say 1) has nothing of
         * its own to rank by: the bundled frequencies come back as they are.
         * Without them siblings tie, and a tie falls to trie order, ছ before স.
         * That also restores the common words a download from before flat lists
         * were taken whole is missing, since it kept the first lines of an
         * alphabetical list.
         *
         * A ranked list (`bn_full_v2`, counted over the Leipzig corpora) leads
         * with its own counts, and each bundled word adds [BUNDLED_WEIGHT]
         * times its bundled frequency to them. The bundled list is not a
         * ranking to impose (it has থেকেও three times above থেকে), but it is
         * the list of chat Bangla, so it lifts the words a chat writes and news
         * barely does: আছি over আসি for "asi", করছি for "korsi". Measured on a
         * held-out Leipzig corpus, weight 4 commits the right word as often as
         * the counts alone and keeps every such chat case.
         */
        fun withBundledRanking(
            listed: List<Pair<String, Int>>,
            bundled: () -> List<Pair<String, Int>>,
        ): List<Pair<String, Int>> {
            if (listed.isEmpty()) return listed
            val first = listed[0].second
            val flat = listed.all { it.second == first }
            val ranked = bundled()
            if (ranked.isEmpty()) return listed
            val known = ranked.mapTo(HashSet(ranked.size * 2)) { precomposed(it.first) }
            val rest = listed.filter { precomposed(it.first) !in known }
            return (if (flat) ranked else withListedCounts(ranked, listed)) + rest
        }

        /** [bundled]'s words at [listed]'s count plus their own; see [withBundledRanking]. */
        private fun withListedCounts(
            bundled: List<Pair<String, Int>>,
            listed: List<Pair<String, Int>>,
        ): List<Pair<String, Int>> {
            val counts = HashMap<String, Int>(listed.size * 2)
            for ((word, frequency) in listed) counts.merge(precomposed(word), frequency, ::maxOf)
            // A bundled word the list lacks starts where the list's unseen words do.
            val floor = listed.minOf { it.second }
            return bundled.map { (word, frequency) ->
                word to (counts[precomposed(word)] ?: floor) + BUNDLED_WEIGHT * frequency
            }
        }

        /** How much a bundled word's frequency counts against a corpus count; see [withBundledRanking]. */
        private const val BUNDLED_WEIGHT = 4

        /** Folds a Bengali word to its canonical phonetic key. */
        fun foldBengali(word: String): String = foldBengaliFull(word).key

        /** Whether a Bengali word ends in the standalone ও or its ো sign. */
        fun endsWithO(word: String): Boolean =
            word.isNotEmpty() && (word.last() == 'ও' || word.last() == 'ো')

        /** Folds a Bengali word, keeping its aspiration pattern. */
        fun foldBengaliFull(word: String): Folded {
            // Normalize decomposed nukta pairs to precomposed code points,
            // and the corrupt U+0985 U+09BE pair (seen in scraped word lists)
            // to U+0986: the pair folds to "oa" and would hijack keys like
            // "oasi"/"wasi".
            val normalized = precomposed(word)
            val out = StringBuilder()
            val marks = StringBuilder()
            for (ch in normalized) {
                val c = consonantClasses[ch]
                val v = vowelClasses[ch]
                when {
                    c != null -> {
                        out.append(c)
                        marks.append(if (ch in aspiratedLetters) ASPIRATED else PLAIN)
                    }
                    v != null -> {
                        out.append(v)
                        marks.append(PLAIN)
                    }
                    // hasant, chandrabindu and anything unmapped fold away
                }
            }
            return dropTrailingO(Folded(out.toString(), marks.toString()))
        }

        /** Folds romanized Bengali typing to the same canonical key. */
        fun foldRoman(input: String): String = foldRomanFull(input).key

        /** Folds romanized typing, keeping the aspiration the typist wrote. */
        fun foldRomanFull(input: String): Folded = dropTrailingO(collapseInherent(foldRomanRaw(input)))

        /**
         * Every key [raw] folds to when each `o` between two consonants may be
         * the inherent vowel (dropped, as [foldRomanFull] always does) or a
         * written ো (kept), the all-dropped one first, at most [cap] of them.
         */
        internal fun inherentVariants(raw: Folded, cap: Int): List<Folded> {
            val key = raw.key
            val medial = key.indices.filter { i ->
                key[i] == 'o' && i > 0 && key[i - 1] !in "aeiou" && i < key.length - 1 && key[i + 1] !in "aeiou"
            }
            val out = ArrayList<Folded>()
            val seen = HashSet<String>()
            for (kept in 0..medial.size) {
                for (subset in subsets(medial, kept)) {
                    val folded = dropTrailingO(collapseInherent(raw, subset.toSet()))
                    if (seen.add(folded.key)) out += folded
                    if (out.size >= cap) return out
                }
            }
            return out
        }

        /** Every [size]-element subset of [items], in order. */
        private fun subsets(items: List<Int>, size: Int): List<List<Int>> {
            if (size == 0) return listOf(emptyList())
            if (size > items.size) return emptyList()
            val out = ArrayList<List<Int>>()
            for (i in 0..items.size - size) {
                for (rest in subsets(items.subList(i + 1, items.size), size - 1)) out += listOf(items[i]) + rest
            }
            return out
        }

        /** [input] folded letter for letter, before the inherent vowels and a final o come off. */
        private fun foldRomanRaw(input: String): Folded {
            val lower = input.lowercase()
            val out = StringBuilder()
            val marks = StringBuilder()
            var i = 0
            while (i < lower.length) {
                val ch = lower[i]
                val next = lower.getOrNull(i + 1)
                when {
                    // Aspiration folds: kh→k, gh→g, ch→s, th→t, dh→d, ph→p, bh→b, sh→s
                    next == 'h' && ch in "kgctdpbs" -> {
                        out.append(if (ch == 'c') 's' else ch)
                        marks.append(if (ch in ASPIRABLE_ROMAN) ASPIRATED else PLAIN)
                        i += 2
                        // chh → s as well
                        if (ch == 'c' && lower.getOrNull(i) == 'h') i++
                        continue
                    }
                    ch == 'c' -> out.append('s').also { marks.append(PLAIN) }
                    ch == 'v' -> out.append('b').also { marks.append(PLAIN) }
                    ch == 'w' -> out.append('o').also { marks.append(PLAIN) }
                    ch == 'z' -> out.append('j').also { marks.append(PLAIN) }
                    ch == 'f' -> out.append('p').also { marks.append(PLAIN) }
                    // "qq" is ঁ, which the Bengali side folds away; a lone
                    // q is ক, the same as on the Avro layout.
                    ch == 'q' && next == 'q' -> i++
                    ch == 'q' -> out.append('k').also { marks.append(PLAIN) }
                    ch == 'x' -> {
                        out.append('k').append('s')
                        marks.append(PLAIN).append(PLAIN)
                    }
                    ch in "aeiou" -> {
                        // Doubled vowels lengthen: ee→i (ঈ), oo→u (উ)
                        if (next == ch) {
                            i++
                            out.append(when (ch) { 'e' -> 'i'; 'o' -> 'u'; else -> ch })
                        } else {
                            out.append(ch)
                        }
                        marks.append(PLAIN)
                    }
                    ch.isLetter() -> out.append(ch).also { marks.append(PLAIN) }
                    // punctuation and digits fold away
                }
                i++
            }
            return Folded(out.toString(), marks.toString())
        }

        /**
         * Romanized inherent vowels: "kori" carries its vowels explicitly, but
         * the Bengali fold of করি is "kri" (inherent o is invisible). Dropping
         * medial "o" between consonants from the roman side aligns the two.
         */
        private fun collapseInherent(folded: Folded, keep: Set<Int> = emptySet()): Folded {
            val key = folded.key
            val out = StringBuilder()
            val marks = StringBuilder()
            for ((index, ch) in key.withIndex()) {
                val prevIsConsonant = index > 0 && key[index - 1] !in "aeiou"
                val nextIsConsonant = index < key.length - 1 && key[index + 1] !in "aeiou"
                if (ch == 'o' && prevIsConsonant && nextIsConsonant && index !in keep) continue
                out.append(ch)
                marks.append(folded.aspiration[index])
            }
            return Folded(out.toString(), marks.toString())
        }

        /** Word-final inherent/o-kar ambiguity: ভালো vs ভাল both end "balo". */
        private fun dropTrailingO(folded: Folded): Folded =
            if (folded.key.length > 1 && folded.key.endsWith('o')) {
                Folded(folded.key.dropLast(1), folded.aspiration.dropLast(1))
            } else {
                folded
            }
    }
}
