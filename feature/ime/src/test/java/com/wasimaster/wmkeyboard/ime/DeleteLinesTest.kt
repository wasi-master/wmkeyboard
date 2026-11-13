package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteLinesTest {

    @Test
    fun `back takes the line up to its start, then the line above with its break`() {
        val before = "one\ntwo\nthr"
        assertEquals(3, DeleteLines.step(before, forward = false))
        assertEquals(listOf(3, 7, 11), DeleteLines.reach(before, 5, forward = false))
    }

    @Test
    fun `back at a line's start takes the line above and its break`() {
        assertEquals(4, DeleteLines.step("one\ntwo\n", forward = false))
    }

    @Test
    fun `forward takes the rest of the line, then the break and the line below`() {
        val after = "abc\ndef\ngh"
        assertEquals(listOf(3, 7, 10), DeleteLines.reach(after, 3, forward = true))
    }

    @Test
    fun `nothing to take is no step`() {
        assertEquals(0, DeleteLines.step("", forward = true))
        assertEquals(emptyList<Int>(), DeleteLines.reach("", 2, forward = false))
    }
}
