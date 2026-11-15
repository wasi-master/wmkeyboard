// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.prediction.eval

import com.wasimaster.wmkeyboard.core.prediction.CandidateReranker
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.NgramReranker
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.SeedBigrams
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import java.io.File
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counterweight to `RerankGainTest`: what the context reranker COSTS.
 *
 * Gain is measured on cases where the population prior happens to be right,
 * which is the easy half of the question and the only half the existing
 * harnesses ask. `RerankGainTest`'s random-context corpus cannot ask the other
 * half — a random previous word means the seed term almost never fires, so a
 * "0/1000 lists changed" reading is the reranker staying silent, not the
 * reranker being safe.
 *
 * This corpus is built to make it speak and be wrong. Each case types a real
 * dictionary word **correctly**, choosing one that sits an edit away from a
 * common follower of the preceding word. "hello" primes "world"; the user
 * types "words". Nothing is misspelled, so the right answer is unambiguous and
 * any drop in top1 is the prior burying a word the user actually typed.
 *
 * The number this prints is the one to weigh a cap change against. A reranker
 * cap is a trade, and a trade needs both sides measured.
 */
class RerankHarmTest {

    private companion object {
        const val CASES = 600
        const val SEED = 11L

        /** Neighbours rarer than this are not plausible intended words. */
        const val MIN_NEIGHBOUR_FREQ = 200
    }

    private fun realEntries(): List<Pair<String, Int>> {
        val candidates = listOf(
            File("dictionaries-src/en.txt"),
            File("app/dictionaries-src/en.txt"),
        )
        return candidates.firstOrNull { it.exists() }?.inputStream()
            ?.use { DictionaryLoader.loadEntries(it) }
            ?: error("en.txt not found")
    }

    private fun seedFile(): File = listOf(
        File("src/main/assets/dictionaries/en_bigrams.txt"),
        File("app/src/main/assets/dictionaries/en_bigrams.txt"),
    ).firstOrNull { it.exists() } ?: error("en_bigrams.txt not found")

    @Test
    fun seedPriorDoesNotBuryACorrectlyTypedNeighbour() {
        val entries = realEntries()
        // Which neighbours are plausible is a property of the exam, so it is
        // read from the pinned list when one is set (#837); see EvalSampling.
        val freq = com.wasimaster.wmkeyboard.core.gesture.eval.EvalSampling.entries(entries).toMap()
        val seeds = seedFile().inputStream().use { SeedBigrams.load(it) }

        // (prev, follower) the seed list knows, grouped so a candidate
        // neighbour can be checked against every follower of the same prev —
        // a neighbour that is ITSELF a follower is not a harm case, it is an
        // agreement case.
        val followers = HashMap<String, MutableSet<String>>()
        for ((prev, next) in seeds.wordPairs) {
            followers.getOrPut(prev) { HashSet() }.add(next)
        }

        val random = Random(SEED)
        val prevs = followers.keys.sorted()

        class Case(val previous: String, val typed: String, val primed: String)

        val cases = ArrayList<Case>(CASES)
        var attempts = 0
        while (cases.size < CASES && attempts < CASES * 400) {
            attempts++
            val prev = prevs[random.nextInt(prevs.size)]
            val known = followers[prev] ?: continue
            val primed = known.elementAt(random.nextInt(known.size))
            if (primed.length < 4 || !primed.all { it in 'a'..'z' }) continue
            // A real word one edit from the primed follower, common enough to
            // be a plausible thing to type, and not itself primed by `prev`.
            val neighbour = editNeighbours(primed)
                .filter { it !in known && (freq[it] ?: 0) >= MIN_NEIGHBOUR_FREQ }
                .minByOrNull { random.nextInt(1000) } ?: continue
            cases.add(Case(prev, neighbour, primed))
        }
        check(cases.size == CASES) { "only built ${cases.size} harm cases" }

        val dictionary = PackedTrie.of(entries)
        val lexicon = UserLexicon(null)
        val engine = SuggestionEngine(
            dictionary, BengaliPhoneticIndex(emptyList()), lexicon, seedBigrams = seeds,
        )

        fun top1(rerank: Boolean): Double {
            engine.reranker = if (rerank) {
                NgramReranker(lexicon, seeds, dictionaryFrequency = { dictionary.frequencyOf(it) })
            } else {
                CandidateReranker.NONE
            }
            var hits = 0
            for (case in cases) {
                val out = engine.suggest(
                    case.typed, previousWord = case.previous, allowRerank = rerank,
                )
                if (out.firstOrNull()?.equals(case.typed, ignoreCase = true) == true) hits++
            }
            return hits / cases.size.toDouble()
        }

        val base = top1(rerank = false)
        val reranked = top1(rerank = true)
        fun pct(v: Double) = String.format(Locale.ROOT, "%.4f", v)
        println(
            "rerank harm (correctly-typed neighbour of a primed word): " +
                "${pct(base)} -> ${pct(reranked)} (${pct(reranked - base)}), n=${cases.size}"
        )

        // The gate, and the accepted price of raising NgramReranker.CAP_SEED:
        // the prior costs -2.2pt here to buy +6.7pt on the matching
        // RerankGainTest, roughly 3:1. The headroom above that measured
        // figure is deliberately thin — this number moving is the signal that
        // a weight change made the prior pushier than the trade allowed for,
        // and it should be re-argued rather than absorbed.
        //
        // Worth keeping in view when weighing the two: a loss here means the
        // typed word fell to the strip's second slot, not that it was
        // replaced. `shouldAutocorrect` reads the raw walk ranking and never
        // this order, and it exits early on any in-dictionary word.
        //
        // 0.03 to 0.035 in 0.6.8, re-argued rather than absorbed: the cases
        // are drawn from en.txt's own neighbours, and taking 228 subtitle
        // fragments out of the list (`th`, `wa`, `bu`, `chffffff`) drew a
        // different 600. Against the old list the same code measures -2.83pt;
        // against the new one -3.17pt, with the base 0.718 -> 0.730 and
        // PredictionEvalTest's top-1 0.8195 -> 0.8315 on the same change.
        assertTrue(
            "seed prior buries correctly-typed words too often: ${pct(base - reranked)}",
            base - reranked < 0.035,
        )
    }

    /** Every real-word string one substitution, deletion or insertion away. */
    private fun editNeighbours(word: String): List<String> {
        val out = ArrayList<String>(word.length * 60)
        for (i in word.indices) {
            for (c in 'a'..'z') {
                if (c != word[i]) out.add(word.substring(0, i) + c + word.substring(i + 1))
            }
            out.add(word.substring(0, i) + word.substring(i + 1))
        }
        for (i in 0..word.length) {
            for (c in 'a'..'z') out.add(word.substring(0, i) + c + word.substring(i))
        }
        return out
    }
}
