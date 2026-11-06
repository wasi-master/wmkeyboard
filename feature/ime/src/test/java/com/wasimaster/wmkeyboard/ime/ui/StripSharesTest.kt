package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1151: a long word borrows the room short words leave on the strip. */
class StripSharesTest {

    @Test fun wordsThatFitKeepEqualSlots() {
        assertNull(stripShares(listOf(40f, 60f, 80f), 300f))
    }

    @Test fun aLongWordTakesWhatTheShortOnesLeave() {
        val shares = stripShares(listOf(30f, 30f, 200f), 300f)!!
        assertEquals(3, shares.size)
        // Everything fits: 260 needed, 40 spare spread evenly.
        assertEquals(30f + 40f / 3, shares[0], 0.01f)
        assertEquals(200f + 40f / 3, shares[2], 0.01f)
        assertEquals(300f, shares.sum(), 0.01f)
    }

    @Test fun twoLongWordsSplitWhatIsLeft() {
        val shares = stripShares(listOf(20f, 200f, 200f), 300f)!!
        assertEquals(20f, shares[0], 0.01f)
        assertEquals(140f, shares[1], 0.01f)
        assertEquals(140f, shares[2], 0.01f)
    }
}
