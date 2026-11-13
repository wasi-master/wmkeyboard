package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankScaleTest {

    @Test fun `the raw scale reads counts exactly as the walk always did`() {
        for (f in listOf(0, 1, 7, 2_962, 28_787_591)) {
            assertEquals(StrictMath.log(1.0 + f), RankScale.RAW.ln1p(f), 0.0)
        }
    }

    @Test fun `the anchored line passes through the top and the anchor`() {
        // The Banglish list: 31,465 at the top, 127 at rank 2,000.
        val line = RankScale.anchored(31_465, 127, 10_000.0)
        assertEquals(10_000.0, line.read(31_465), 1e-6)
        assertEquals(RankScale.REFERENCE_AT_ANCHOR, line.read(127), 1e-6)
        // Below the floor rarer still reads lower, not flattened onto 1.
        assertTrue(line.read(2) < line.read(5))
    }

    @Test fun `a list with nothing at the anchor keeps the assumed line`() {
        assertEquals(RankScale.logLine(31_465, 10_000.0), RankScale.anchored(31_465, 0, 10_000.0))
        assertEquals(RankScale.logLine(500, 10_000.0), RankScale.anchored(500, 500, 10_000.0))
    }

    @Test fun `a head that is one long tie cannot blow the stretch up`() {
        val line = RankScale.anchored(1_000_000, 999_999, 10_000.0)
        assertEquals(RankScale.logLine(1_000_000, 10_000.0).perNat * 2.0, line.perNat, 1e-9)
    }

    @Test fun `a packed list answers its count at the anchor without touching the cap's floor`() {
        val entries = (1..3_000).map { "w$it" to 100_000 / it }
        val trie = PackedTrie.of(entries)
        val capFloor = trie.frequencyAtRank(500)
        val atAnchor = trie.countAtRank(RankScale.ANCHOR_RANK)
        assertTrue("count at rank 2,000: $atAnchor", atAnchor in 40..60)
        assertEquals(capFloor, trie.frequencyAtRank(500))
    }
}
