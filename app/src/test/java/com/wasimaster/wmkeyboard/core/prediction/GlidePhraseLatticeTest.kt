package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.prediction.GlidePhraseLattice.Lift
import com.wasimaster.wmkeyboard.core.prediction.GlidePhraseLattice.Phrase
import com.wasimaster.wmkeyboard.core.prediction.GlidePhraseLattice.Slot
import org.junit.Assert.assertEquals
import org.junit.Test

class GlidePhraseLatticeTest {

    private val silent = Lift { _, _, _ -> 0.0 }

    private fun slot(vararg readings: Pair<String, Double>) =
        Slot(readings.map { it.first }, readings.map { it.second })

    private fun best(slots: List<Slot>, lift: Lift, back2: String? = null, back1: String? = null) =
        GlidePhraseLattice.read(slots, back2, back1, lift = lift).first().words

    @Test
    fun withNoEvidenceEverySlotKeepsItsLeader() {
        val slots = listOf(slot("by" to 5.0, "buy" to 4.8), slot("a" to 3.0, "as" to 2.0), slot("car" to 6.0, "far" to 5.9))
        assertEquals(listOf("by", "a", "car"), best(slots, silent))
    }

    @Test
    fun aWordIsJudgedByTheWordsAfterIt() {
        val slots = listOf(slot("by" to 5.0, "buy" to 4.8), slot("a" to 3.0), slot("car" to 6.0))
        val lift = Lift { back2, back1, word ->
            when {
                back2 == "buy" && back1 == "a" && word == "car" -> 1.0
                else -> 0.0
            }
        }
        assertEquals(listOf("buy", "a", "car"), best(slots, lift))
    }

    @Test
    fun theFieldsWordsCount() {
        val slots = listOf(slot("by" to 5.0, "buy" to 4.8))
        val lift = Lift { back2, back1, word -> if (back2 == "want" && back1 == "to" && word == "buy") 1.0 else 0.0 }
        assertEquals(listOf("buy"), best(slots, lift, back2 = "want", back1 = "to"))
    }

    @Test
    fun anEmptySlotHoldsItsPlaceAndPassesTheContextOver() {
        val slots = listOf(slot("the" to 1.0), Slot(emptyList(), emptyList()), slot("car" to 1.0, "far" to 1.5))
        val lift = Lift { _, back1, word -> if (back1 == "the" && word == "car") 1.0 else 0.0 }
        assertEquals(listOf("the", null, "car"), best(slots, lift))
    }

    @Test
    fun anUnscoredWordIsNeverTyped() {
        val slots = listOf(slot("is" to 5.0, "us" to Double.NEGATIVE_INFINITY))
        val lift = Lift { _, _, word -> if (word == "us") 100.0 else 0.0 }
        assertEquals(listOf("is"), best(slots, lift))
    }

    @Test
    fun aLeaderTheSlotPutFirstLeadsAtTheSlotsBestScore() {
        // An offered contraction carries a lower score than the reading it leads.
        val slots = listOf(slot("it's" to 4.0, "its" to 5.0))
        assertEquals(listOf("it's"), best(slots, silent))
    }

    @Test
    fun theLongestContextThatHasSeenTheWordWins() {
        assertEquals(GlidePhraseLattice.TRIGRAM_WEIGHT * kotlin.math.ln1p(9.0), GlidePhraseLattice.lift(9, 500, true), 1e-9)
        assertEquals(GlidePhraseLattice.BIGRAM_WEIGHT * kotlin.math.ln1p(9.0), GlidePhraseLattice.lift(0, 9, true), 1e-9)
        assertEquals(-GlidePhraseLattice.UNSEEN_PAIR_PENALTY, GlidePhraseLattice.lift(0, 0, true), 1e-9)
        assertEquals(0.0, GlidePhraseLattice.lift(0, 0, false), 1e-9)
    }

    @Test
    fun theModelMovesAPhraseOnlyAsFarAsItsCap() {
        val phrases = listOf(Phrase(listOf("by", "a"), 10.0), Phrase(listOf("buy", "a"), 9.0), Phrase(listOf("bye", "a"), 7.0))
        val out = GlidePhraseLattice.blendModel(phrases, listOf(-20f, -10f, 0f))
        // +2 and +2 at two words' cap: the second passes the leader, the third cannot.
        assertEquals(listOf("buy", "a"), out[0].words)
        assertEquals(11.0, out[0].score, 1e-6)
        assertEquals(listOf("by", "a"), out[1].words)
    }

    @Test
    fun aPhraseTheModelCannotReadKeepsItsScore() {
        val phrases = listOf(Phrase(listOf("a"), 3.0), Phrase(listOf("b"), 2.0))
        assertEquals(2.0, GlidePhraseLattice.blendModel(phrases, listOf(-5f, Float.NaN))[1].score, 1e-9)
        assertEquals(phrases, GlidePhraseLattice.blendModel(phrases, listOf(Float.NaN, -1f)))
    }

    @Test
    fun aSlotsOtherReadingsStandGivenTheWordsChosenBeforeThem() {
        val slots = listOf(slot("a" to 3.0), slot("far" to 5.0, "car" to 4.9, "us" to Double.NEGATIVE_INFINITY))
        val lift = Lift { _, back1, word -> if (back1 == "a" && word == "car") 1.0 else 0.0 }
        val phrase = Phrase(listOf("a", "far"), 0.0)
        val orders = GlidePhraseLattice.slotOrders(slots, phrase, null, null, lift)
        assertEquals(listOf("a"), orders[0])
        assertEquals(listOf("far", "car", "us"), orders[1])
    }
}
