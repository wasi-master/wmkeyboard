package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The stored order of a held clip's buttons (#1094). */
class ClipHoldActionOrderTest {

    @Test
    fun `nothing stored is the default order`() {
        assertEquals(ClipHoldAction.entries, ClipHoldAction.orderOf(null))
        assertEquals(ClipHoldAction.entries, ClipHoldAction.orderOf(""))
    }

    @Test
    fun `a stored order comes back as it was`() {
        val order = ClipHoldAction.entries.reversed()
        assertEquals(order, ClipHoldAction.orderOf(order.joinToString(",") { it.name }))
    }

    @Test
    fun `unknown names go and missing actions follow in their own order`() {
        val order = ClipHoldAction.orderOf("DELETE,GONE,PIN,DELETE")
        assertEquals(listOf(ClipHoldAction.DELETE, ClipHoldAction.PIN), order.take(2))
        assertEquals(ClipHoldAction.entries - setOf(ClipHoldAction.DELETE, ClipHoldAction.PIN), order.drop(2))
    }
}
