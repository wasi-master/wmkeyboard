package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RankMarkTest {

    @Test
    fun `a rank reads with its sign, and down with a true minus`() {
        assertEquals("+2", rankMarkLabel(2))
        assertEquals("−1", rankMarkLabel(-1))
    }
}
