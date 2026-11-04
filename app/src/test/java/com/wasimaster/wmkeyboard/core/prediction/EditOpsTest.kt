package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditOpsTest {

    @Test
    fun aTranspositionIsOneSwap() {
        assertEquals(1, EditOps.distance("teh", "the"))
        val ops = EditOps.align("teh", "the")
        assertEquals(
            listOf<EditOps.Op>(EditOps.Op.Match(0, 't'), EditOps.Op.Swap(1, 'h', 'e')),
            ops,
        )
    }

    @Test
    fun aLetterWhereASpaceWasMeantIsASubstitutionOfSpace() {
        assertEquals(1, EditOps.distance("thisbis", "this is"))
        val ops = EditOps.align("thisbis", "this is")
        assertTrue(ops.contains(EditOps.Op.Sub(4, 'b', ' ')))
    }

    @Test
    fun aMissedDoubleIsAnInsertion() {
        val ops = EditOps.align("aple", "apple")
        assertEquals(1, ops.count { it is EditOps.Op.Ins })
        assertTrue(ops.any { it is EditOps.Op.Ins && it.intended == 'p' })
    }

    @Test
    fun aStrayLetterIsADeletion() {
        val ops = EditOps.align("helllo", "hello")
        assertEquals(1, EditOps.distance("helllo", "hello"))
        assertEquals(1, ops.count { it is EditOps.Op.Del })
        assertTrue(ops.any { it is EditOps.Op.Del && it.typed == 'l' })
    }

    @Test
    fun aNumberRowSlipIsASubstitution() {
        val ops = EditOps.align("as3", "ase")
        assertEquals(listOf<EditOps.Op>(
            EditOps.Op.Match(0, 'a'), EditOps.Op.Match(1, 's'), EditOps.Op.Sub(2, '3', 'e'),
        ), ops)
    }

    @Test
    fun swapBeatsTwoSubstitutions() {
        // "form" against "from" is one swap, never two substitutions.
        assertEquals(1, EditOps.distance("form", "from"))
        assertEquals(1, EditOps.align("form", "from").count { it !is EditOps.Op.Match })
    }

    @Test
    fun emptyStringsMeasureTheirLength() {
        assertEquals(3, EditOps.distance("", "the"))
        assertEquals(3, EditOps.distance("the", ""))
        assertEquals(0, EditOps.distance("", ""))
        assertEquals(3, EditOps.align("", "the").count { it is EditOps.Op.Ins })
    }

    @Test
    fun aRewriteIsFarAway() {
        assertTrue(EditOps.distance("abcdefg", "zzzzzzz") >= 7)
    }

    @Test
    fun typedIndicesWalkTheTypedString() {
        val ops = EditOps.align("recieve", "receive")
        // Every op that consumes typed text names its index in order.
        val consumed = ops.filter { it !is EditOps.Op.Ins }.map { it.at }
        assertEquals(consumed.sorted(), consumed)
        assertEquals(1, EditOps.distance("recieve", "receive"))
    }

    @Test
    fun oneSwapIsANeighbouringPairTurnedRoundAndNothingElse() {
        assertTrue(EditOps.isOneSwap("wnat", "want"))
        assertTrue(EditOps.isOneSwap("soemthing", "something"))
        assertTrue(EditOps.isOneSwap("adn", "dan"))
        assertFalse(EditOps.isOneSwap("wnat", "what"))
        assertFalse(EditOps.isOneSwap("want", "want"))
        assertFalse(EditOps.isOneSwap("abcd", "badc"))
        assertFalse(EditOps.isOneSwap("ab", "abc"))
    }
}
