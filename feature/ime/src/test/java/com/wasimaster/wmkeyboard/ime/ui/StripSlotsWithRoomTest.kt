package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1291: chips beside the words leave fewer, readable slots instead of three slivers. */
class StripSlotsWithRoomTest {

    @Test fun aFullStripKeepsEverySlot() {
        assertEquals(3, slotsWithRoom(3, 356.dp))
        assertEquals(6, slotsWithRoom(6, 356.dp))
    }

    @Test fun aCrowdedStripDropsToWhatFits() {
        assertEquals(2, slotsWithRoom(3, 110.dp))
        assertEquals(1, slotsWithRoom(3, 80.dp))
    }

    @Test fun oneSlotIsAlwaysDrawn() {
        assertEquals(1, slotsWithRoom(3, 20.dp))
        assertEquals(1, slotsWithRoom(3, 0.dp))
    }
}
