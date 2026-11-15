package com.wasimaster.wmkeyboard.core.prediction

/**
 * Post-commit revision: after "their going" lands in the field, the word
 * that follows sometimes proves the word before it wrong — "they're" was
 * meant. This advisor checks the just-committed pair against a small closed
 * set of classic English confusables and, when the bigram evidence for a
 * sibling clearly dominates, names the replacement.
 *
 * Chip-only by contract: the caller offers the fix, the user applies it.
 * Nothing here ever rewrites text on its own — revising already-committed
 * prose without asking is the one autocorrect sin users never forgive.
 * Deliberately conservative: a sibling must beat the typed word's own
 * evidence by [DOMINANCE] in the same source and clear that source's
 * minimum, so an unusual-but-intended pair ("their going concern") with any
 * real support survives untouched.
 */
class RevisionAdvisor(
    private val userLexicon: UserLexicon,
    private val seedBigrams: SeedBigrams,
    /** Read live like the reranker's: downloads swap the pack at runtime. */
    private val ngramPack: () -> NgramPack = { NgramPack.EMPTY },
) {

    /**
     * The word [prev] should have been, given that [follower] came next —
     * or null (the common case): not a confusable, or no sibling dominates.
     */
    fun advise(prev: String?, follower: String?): String? {
        val p = prev?.lowercase() ?: return null
        val f = follower?.lowercase() ?: return null
        if (WordContext.isSentinel(p) || WordContext.isSentinel(f)) return null
        if (f.isEmpty() || !f.all(WordContext::isWordChar)) return null
        val siblings = CONFUSABLES[p] ?: return null

        var best: String? = null
        var bestRatio = 0.0
        for (alt in siblings) {
            if (alt == p) continue
            val ratio = dominanceRatio(p, alt, f)
            if (ratio > bestRatio) {
                bestRatio = ratio
                best = alt
            }
        }
        return if (bestRatio >= DOMINANCE) best else null
    }

    /**
     * Whether the word before says the user meant [fixed] where they typed
     * [typed] — both real words, so only context can tell "form" from "from".
     *
     * The forward twin of [advise]'s test: bigram(prev → fixed) has to beat
     * bigram(prev → typed) by [TAUGHT_DOMINANCE] within a single source and
     * clear that source's minimum. A lower bar than [DOMINANCE], because the
     * caller only asks once the user has already fixed this exact pair by hand
     * [CorrectionMemory.APPLY_KNOWN_AT] times; the context is a second witness,
     * not the whole case. False at a sentence start: no context, no verdict.
     */
    fun precedes(prev: String?, typed: String, fixed: String): Boolean {
        val p = prev?.lowercase() ?: return false
        if (p.isEmpty() || WordContext.isSentinel(p)) return false
        val t = typed.lowercase()
        val f = fixed.lowercase().substringBefore(' ')
        if (t.isEmpty() || f.isEmpty()) return false
        val pack = ngramPack()
        var best = 0.0
        fun consider(fixedCount: Int, typedCount: Int, minCount: Int) {
            if (fixedCount < minCount) return
            val ratio = (fixedCount + 1).toDouble() / (typedCount + 1)
            if (ratio > best) best = ratio
        }
        consider(userLexicon.bigramCount(p, f), userLexicon.bigramCount(p, t), MIN_USER_COUNT)
        consider(pack.bigramCount(p, f), pack.bigramCount(p, t), MIN_CORPUS_COUNT)
        consider(seedBigrams.count(p, f), seedBigrams.count(p, t), MIN_CORPUS_COUNT)
        return best >= TAUGHT_DOMINANCE
    }

    /**
     * The strongest per-source case for [alt] over [prev] before [follower].
     * Sources are never mixed: user counts and corpus counts live on
     * different scales, and a ratio across them means nothing. A source only
     * testifies at all when the winning count clears its own minimum.
     */
    private fun dominanceRatio(prev: String, alt: String, follower: String): Double {
        val pack = ngramPack()
        var best = 0.0
        fun consider(altCount: Int, prevCount: Int, minCount: Int) {
            if (altCount < minCount) return
            val ratio = (altCount + 1).toDouble() / (prevCount + 1)
            if (ratio > best) best = ratio
        }
        consider(
            userLexicon.bigramCount(alt, follower),
            userLexicon.bigramCount(prev, follower),
            MIN_USER_COUNT,
        )
        consider(
            pack.bigramCount(alt, follower),
            pack.bigramCount(prev, follower),
            MIN_CORPUS_COUNT,
        )
        consider(
            seedBigrams.count(alt, follower),
            seedBigrams.count(prev, follower),
            MIN_CORPUS_COUNT,
        )
        return best
    }

    companion object {
        /** A sibling must out-evidence the typed word by this factor
         * (add-one smoothed) within a single source before the chip shows. */
        const val DOMINANCE = 8.0

        /**
         * What the context must say for a taught fix of a real word to fire
         * ([precedes]); the user's own fixes already carry most of the case.
         */
        const val TAUGHT_DOMINANCE = 3.0

        /** Personal pairs are small counts; three uses is a real habit. */
        const val MIN_USER_COUNT = 3

        /** Corpus/seed counts are population-scale; below this is noise. */
        const val MIN_CORPUS_COUNT = 25

        /**
         * The classic English confusable sets, keyed by every member. Closed
         * and deliberately short: each entry is a pair the follower word can
         * actually disambiguate ("they're going" vs "their car"), not merely
         * a common misspelling — those are autocorrect's job at typing time.
         */
        private val SETS: List<List<String>> = listOf(
            listOf("their", "there", "they're"),
            listOf("your", "you're"),
            listOf("its", "it's"),
            listOf("whose", "who's"),
            listOf("were", "we're", "where"),
            listOf("then", "than"),
            listOf("lose", "loose"),
            listOf("affect", "effect"),
        )

        private val CONFUSABLES: Map<String, List<String>> = buildMap {
            for (set in SETS) for (word in set) put(word, set)
        }
    }
}
