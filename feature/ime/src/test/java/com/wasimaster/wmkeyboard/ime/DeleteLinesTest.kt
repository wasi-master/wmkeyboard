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
    fun `back goes a drawn line at a time through a wrapped paragraph`() {
        // Drawn as "the quick " / "brown fox " / "jumps".
        val before = "the quick brown fox jumps"
        val wraps = listOf(10, 20)
        assertEquals(5, DeleteLines.step(before, forward = false, wraps = wraps))
        assertEquals(listOf(5, 15, 25), DeleteLines.reach(before, 5, forward = false, wraps = wraps))
    }

    @Test
    fun `forward goes a drawn line at a time, then takes the break as before`() {
        val after = "the quick brown fox jumps\nnext"
        val wraps = listOf(10, 20)
        assertEquals(listOf(10, 20, 25, 30), DeleteLines.reach(after, 5, forward = true, wraps = wraps))
    }

    @Test
    fun `a wrap on a hard line start changes nothing`() {
        assertEquals(4, DeleteLines.step("\nabc", forward = true, wraps = listOf(1)))
        assertEquals(4, DeleteLines.step("one\ntwo\n", forward = false, wraps = listOf(4)))
    }

    @Test
    fun `nothing to take is no step`() {
        assertEquals(0, DeleteLines.step("", forward = true))
        assertEquals(emptyList<Int>(), DeleteLines.reach("", 2, forward = false))
    }
}
