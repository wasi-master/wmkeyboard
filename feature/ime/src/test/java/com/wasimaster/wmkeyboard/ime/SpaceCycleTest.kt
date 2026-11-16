package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceCycleTest {

    private val cycle = listOf(", ", ". ", "? ")

    @Test
    fun `taps walk the cycle from a plain space`() {
        assertEquals(0 to ", ", spaceCycleNext(cycle, -1))
        assertEquals(1 to ". ", spaceCycleNext(cycle, 0))
        assertEquals(2 to "? ", spaceCycleNext(cycle, 1))
    }

    @Test
    fun `the tap after the last entry is a plain space again`() {
        assertEquals(-1 to " ", spaceCycleNext(cycle, 2))
        assertEquals(0 to ", ", spaceCycleNext(cycle, -1))
    }

    @Test
    fun `an empty cycle only ever types a space`() {
        assertEquals(-1 to " ", spaceCycleNext(emptyList(), -1))
    }
}
