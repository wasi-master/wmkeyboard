package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePoolTest {

    private data class Press(val token: String, val label: String = token)

    private fun BubblePool<Press>.seat(vararg presses: Press) = assign(presses.toList()) { it.token }

    @Test
    fun `a new press reuses an idle slot instead of growing the pool`() {
        val pool = BubblePool<Press>(size = 4)
        pool.seat(Press("a"))
        pool.seat()
        pool.seat(Press("b"))
        assertEquals(1, pool.used)
        assertEquals("b", pool.item(0)?.token)
        assertTrue(pool.live[0])
    }

    @Test
    fun `a press already up keeps its slot while others come and go`() {
        val pool = BubblePool<Press>(size = 4)
        pool.seat(Press("a"), Press("b"))
        pool.seat(Press("b"), Press("c"))
        // b stays in slot 1; c takes slot 0, which a left.
        assertEquals("c", pool.item(0)?.token)
        assertEquals("b", pool.item(1)?.token)
        assertTrue(pool.live[0] && pool.live[1])
    }

    @Test
    fun `the slot idle longest is taken first`() {
        val pool = BubblePool<Press>(size = 2)
        pool.seat(Press("a"))
        pool.seat(Press("a"), Press("b"))
        pool.seat(Press("b"))
        pool.seat()
        // a went idle before b did.
        pool.seat(Press("c"))
        assertEquals("c", pool.item(0)?.token)
        assertEquals("b", pool.item(1)?.token)
        assertFalse(pool.live[1])
    }

    @Test
    fun `presses past the pool spill over`() {
        val pool = BubblePool<Press>(size = 2)
        val spill = pool.seat(Press("a"), Press("b"), Press("c"))
        assertEquals(listOf(Press("c")), spill)
        assertEquals(2, pool.used)
    }

    @Test
    fun `an idle slot keeps its last preview for the next press`() {
        val pool = BubblePool<Press>(size = 2)
        pool.seat(Press("a", "A"))
        pool.seat()
        assertFalse(pool.live[0])
        assertEquals("A", pool.item(0)?.label)
    }
}
