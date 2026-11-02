package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.gesture.eval.GlideGrid
import com.wasimaster.wmkeyboard.core.gesture.eval.SwipeCorpus
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import kotlin.math.ln1p
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Early prediction: reading a word off a stroke that has not finished spelling
 * it.
 *
 * The decoder's whole model is that a word explains a stroke by putting its
 * first letter on the first sample and its last on the last, which is what
 * makes it exact and what makes "dictionary" structurally unreachable from a
 * stroke that has only reached the `c`. A look-ahead candidate keeps the same
 * alignment and stops requiring the word to end there: the prefix explains the
 * stroke, and everything after it is the language model's guess.
 */
class GlideLookAheadTest {

    private val grid = GlideGrid.of(BuiltInLayouts.QWERTY)
    private val keys = GlideKeyMap.of(grid.keyCenters(SwipeCorpus.KEY_WIDTH), SwipeCorpus.KEY_WIDTH)
    private val corpus = SwipeCorpus(seed = 7L, grid = grid)

    private fun sourcesFor(vararg entries: Pair<String, Int>): List<FuzzyBeamSearch.WalkSource> {
        val trie = Trie().apply { entries.forEach { (w, f) -> insert(w, f) } }
        return trie.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }
    }

    /** A clean stroke through [word]'s keys, as if the finger stopped there. */
    private fun strokeFor(word: String): List<GesturePoint> =
        corpus.swipe(word, SwipeCorpus.Noise.CLEAN.profile)
            ?: error("the corpus cannot draw $word")

    private fun decode(
        word: String,
        sources: List<FuzzyBeamSearch.WalkSource>,
        lookAhead: Int,
        tuning: GlideBeam.Tuning = GlideBeam.Tuning(),
    ): List<GlideBeam.Candidate> = GlideBeam(tuning).decode(
        path = strokeFor(word),
        keys = keys,
        keyWidth = SwipeCorpus.KEY_WIDTH,
        sources = sources,
        ws = GlideWorkspace(),
        limit = 8,
        lookAhead = lookAhead,
    )

    @Test
    fun aStrokeThatStopsPartWayOffersTheWordItIsStarting() {
        val sources = sourcesFor("dictionary" to 5_000, "dict" to 3)
        // Drawn only as far as `dict`, which is a word here so the exact
        // reading has something to find — and "dictionary" is not reachable at
        // all without a look-ahead.
        val plain = decode("dict", sources, lookAhead = 0)
        assertTrue(
            "a plain decode should never invent letters, got ${plain.map { it.word }}",
            plain.none { it.word == "dictionary" },
        )
        val ahead = decode("dict", sources, lookAhead = 4)
        val found = ahead.firstOrNull { it.word == "dictionary" }
        assertNotNull("no look-ahead candidate for dictionary in ${ahead.map { it.word }}", found)
        assertEquals("dictionary is 6 characters past dict", 6, found!!.ahead)
    }

    @Test
    fun anOrdinaryReadingIsNeverMarkedAsAGuess() {
        val sources = sourcesFor("dictionary" to 5_000, "dict" to 3)
        val ahead = decode("dict", sources, lookAhead = 4)
        assertEquals(0, ahead.first { it.word == "dict" }.ahead)
    }

    @Test
    fun aStrokeTooShortToBeEvidenceIsNotGuessedFrom() {
        // Two letters: every long word starting `di` fits about as well, so a
        // guess there is a guess from frequency alone, and it must not lead,
        // however much commoner the word it guesses.
        val sources = sourcesFor("dictionary" to 5_000, "di" to 3)
        val ahead = decode("di", sources, lookAhead = 4)
        assertEquals("di", ahead.first().word)
    }

    @Test
    fun theGuessIsTheCommonestWordUnderThePrefixAndNotMerelyAnyOfThem() {
        val sources = sourcesFor(
            "dictionary" to 5_000,
            "dictation" to 900,
            "dictate" to 400,
            "dict" to 3,
        )
        val ahead = decode("dict", sources, lookAhead = 1).filter { it.ahead > 0 }
        assertEquals(listOf("dictionary"), ahead.map { it.word })
    }

    @Test
    fun aPrefixThatIsAlreadyItsCommonestWordOffersNothing() {
        // `dict` is the most frequent thing under `dict`, so there is nothing
        // to look ahead to that the ordinary reading has not already found.
        val sources = sourcesFor("dict" to 9_000, "dictionary" to 10)
        val ahead = decode("dict", sources, lookAhead = 4)
        assertNull(ahead.firstOrNull { it.word == "dictionary" && it.ahead > 0 })
    }

    @Test
    fun aLongerGuessIsChargedMoreThanAShortOne() {
        val short = sourcesFor("dictate" to 5_000, "dict" to 3)
        val long = sourcesFor("dictionary" to 5_000, "dict" to 3)
        val shortAhead = decode("dict", short, lookAhead = 4).first { it.ahead > 0 }
        val longAhead = decode("dict", long, lookAhead = 4).first { it.ahead > 0 }
        // Same prefix, same alignment, same frequency: the only difference is
        // how much of the word is still a guess.
        assertTrue(
            "the longer guess did not cost more (${shortAhead.score} vs ${longAhead.score})",
            shortAhead.score > longAhead.score,
        )
    }

    @Test
    fun aLookAheadOfZeroIsTheDecoderExactlyAsItWas() {
        val sources = sourcesFor("dictionary" to 5_000, "dict" to 3, "did" to 900)
        val off = decode("dict", sources, lookAhead = 0)
        assertTrue(off.all { it.ahead == 0 })
    }

    /**
     * Issue #121: the two decodes of one stroke have to be asked the same
     * question.
     *
     * A guess exists only in a decode that was asked for one, so a preview
     * given a look-ahead and a lift given none are not two readings of one
     * stroke: they are answers to different questions, and the word the user
     * was shown is not on the list the lift ranks at all. No gate downstream
     * can repair that, which is why the flag now follows where the stroke ends
     * rather than which of the two is asking.
     */
    @Test
    fun aLiftGivenNoLookAheadCannotTypeWhatThePreviewShowed() {
        val sources = sourcesFor("dictionary" to 5_000, "dict" to 300)
        val free = GlideBeam.Tuning(lookAheadCost = 0.0)
        // The preview, with the guess leading: this is the word on screen, and
        // the word the lift is being promised.
        val previewed = decode("dict", sources, lookAhead = 4, free).first()
        assertEquals("dictionary", previewed.word)
        // The lift, decoded the way it used to be. Not a lower ranking for the
        // promised word: it is absent, so the stroke types something else.
        val lifted = decode("dict", sources, lookAhead = 0, free)
        assertTrue(
            "a plain decode reached a guess: ${lifted.map { it.word }}",
            lifted.none { it.word == "dictionary" },
        )
        assertEquals("dict", lifted.first().word)
    }

    /**
     * The charge has to be able to hold a guess back, or the decoder would
     * answer every stroke with the commonest word that starts that way.
     */
    @Test
    fun theChargeCanHoldAGuessBehindAnOrdinaryReading() {
        val sources = sourcesFor("dictionary" to 5_000, "dict" to 300)
        val cheap = decode(
            "dict", sources, lookAhead = 4, GlideBeam.Tuning(lookAheadCost = 0.0),
        )
        val dear = decode(
            "dict", sources, lookAhead = 4, GlideBeam.Tuning(lookAheadCost = 6.0),
        )
        assertEquals("free guesses should lead", "dictionary", cheap.first().word)
        assertEquals("expensive guesses should not", "dict", dear.first().word)
    }

    /**
     * Issue #167: a word the stroke has spelled out and the longer word it
     * starts are read off the same letters, so they have to pay the same
     * shape charge.
     *
     * They did not. The shape channel rescored the readings and skipped the
     * guesses, on the reasoning that a guess's whole path runs through
     * letters not yet drawn — true, but its *prefix* is exactly what was
     * drawn, and it is the prefix that competes. On a real finger the charge
     * is worth several nats, all of which the guess kept, so "thing" glided
     * to its last letter lost to "things" on a gap that had nothing to do
     * with either word.
     */
    @Test
    fun aGuessPaysTheShapeChargeTheWordItExtendsPays() {
        // The plural a shade commoner, or there is nothing to guess: a
        // prefix that is already the commonest word under itself is never
        // extended.
        val sources = sourcesFor("thing" to 2_208, "things" to 2_480)
        val tuning = GlideBeam.Tuning()
        val path = corpus.swipe("thing", SwipeCorpus.Noise.TYPICAL.profile)
            ?: error("the corpus cannot draw thing")
        val decoded = GlideBeam(tuning).decode(
            path = path,
            keys = keys,
            keyWidth = SwipeCorpus.KEY_WIDTH,
            sources = sources,
            ws = GlideWorkspace(),
            limit = 8,
            lookAhead = 4,
        )
        val read = decoded.first { it.word == "thing" && it.ahead == 0 }
        val guess = decoded.first { it.word == "things" && it.ahead == 1 }
        // Same prefix, same alignment, same shape: all that stands between
        // the two is one guessed letter and the frequency gap.
        val expected = ln1p(2_480.0) - ln1p(2_208.0) - tuning.lookAheadCost
        assertEquals(expected, guess.score - read.score, 1e-6)
        assertEquals("the word drawn in full leads", "thing", decoded.first().word)
    }
}
