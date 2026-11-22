package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two halves of the guess seam, and the gap between them that was #317. */
class GlideGuessGateTest {

    private fun read(word: String, score: Double, cost: Double) =
        GlideBeam.Candidate(word, score, cost, FuzzyBeamSearch.Tier.DICTIONARY, 0)

    private fun guess(word: String, score: Double, cost: Double, ahead: Int) =
        GlideBeam.Candidate(word, score, cost, FuzzyBeamSearch.Tier.USER, ahead)

    @Test
    fun `a learned guess cannot throw away the decode that read the stroke`() {
        // "exam" drawn in full and read by the dictionary; "example" learned,
        // and guessed off the same four letters — so its cost is the prefix's,
        // which ties the reading it is competing with.
        val learned = listOf(guess("example", 9.0, 4.0, 3))
        val full = listOf(read("exam", 5.0, 4.0), read("exams", 3.0, 6.0))

        val decoded = GlideGuessGate.preferLearned(learned, full)

        assertTrue("the drawn word must survive the sandbox", decoded.any { it.word == "exam" })
        assertEquals("the guess leads on score until the gate says otherwise", "example", decoded[0].word)
        // Four nats clear of the drawn word is the lead "When sure" asks for,
        // so this guess does lead — but the word the finger drew is still on
        // the strip to tap, which is what the sandbox used to throw away.
        assertTrue(GlideGuessGate.admit(decoded, 4.0).any { it.word == "exam" })
    }

    @Test
    fun `the confidence tier bites on a stroke the sandbox could not read`() {
        val learned = listOf(guess("example", 7.0, 4.0, 3))
        val full = listOf(read("exam", 5.0, 4.0))

        val decoded = GlideGuessGate.preferLearned(learned, full)

        // 2 nats ahead of the drawn word: past Eager, short of When sure.
        assertEquals("example", GlideGuessGate.admit(decoded, 1.0).first().word)
        assertEquals("exam", GlideGuessGate.admit(decoded, 4.0).first().word)
        assertEquals("exam", GlideGuessGate.admit(decoded, null).single().word)
    }

    @Test
    fun `a learned reading still wins the sandbox when it explains the stroke`() {
        val learned = listOf(read("safeway", 11.0, 3.0))
        val full = listOf(read("safe", 5.0, 4.0))

        val decoded = GlideGuessGate.preferLearned(learned, full)

        assertEquals(listOf("safeway", "safe"), decoded.map { it.word })
        // A lone learned reading asked nothing before, and still asks nothing.
        assertTrue(!SuggestionEngine.glideIsAmbiguous(decoded, 2.5))
    }

    @Test
    fun `a winning learned reading leads the full decode, not just the learned words`() {
        // #1250: "notes" written before and fitting as well as anything, so it
        // wins; "nodes", never written, has to stay on the strip to pick.
        val learned = listOf(read("notes", 9.0, 3.0), read("knots", 8.5, 5.0))
        val full = listOf(read("notes", 8.0, 3.0), read("nodes", 7.6, 3.2), read("knots", 6.0, 5.0))

        val decoded = GlideGuessGate.preferLearned(learned, full)

        assertEquals(listOf("notes", "nodes", "knots"), decoded.map { it.word })
        // The picker's gap is the one the learned list had: notes against knots.
        assertEquals(0.5, decoded[0].score - decoded[1].score, 1e-9)
        assertEquals(
            SuggestionEngine.glideIsAmbiguous(learned),
            SuggestionEngine.glideIsAmbiguous(decoded),
        )
    }

    @Test
    fun `the margin is measured against the best reading, not the reranked first one`() {
        // The context rerank has put a weak reading in front. Measuring the
        // guess against that one would clear a bar the stroke never set.
        val decoded = listOf(read("rat", 1.0, 5.0), guess("rather", 4.0, 5.0, 3), read("rats", 3.5, 5.0))

        assertEquals("rat", GlideGuessGate.admit(decoded, 4.0).first().word)
        assertTrue(GlideGuessGate.admit(decoded, 4.0).none { it.ahead > 0 })
    }

    @Test
    fun `a guess for a word the stroke already reads is dropped`() {
        val learned = listOf(guess("customer", 9.0, 4.0, 1))
        val full = listOf(read("customer", 5.0, 4.5))

        val decoded = GlideGuessGate.preferLearned(learned, full)

        assertEquals(listOf("customer"), decoded.map { it.word })
        assertEquals(0, decoded.single().ahead)
    }

    @Test
    fun `a list with no reading at all is left alone`() {
        // LEARNED_ONLY answering for a word its lexicon does not hold: the
        // guess is the only answer there is.
        val decoded = listOf(guess("getting", 6.0, 5.0, 4))

        assertEquals(decoded, GlideGuessGate.admit(decoded, 4.0))
    }
}
