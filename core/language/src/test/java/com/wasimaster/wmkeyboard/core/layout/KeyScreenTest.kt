package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class KeyScreenTest {

    private fun grid(vararg rows: List<Key>, heights: List<Float>? = null) =
        KeyboardLayout(name = "t", rows = rows.toList(), rowHeights = heights)

    private val always = Key(label = "a")
    private val upright = Key(label = "u", showOn = KeyScreen.PORTRAIT)
    private val sideways = Key(label = "s", showOn = KeyScreen.LANDSCAPE)

    @Test
    fun `a grid no key asks about comes back as it is`() {
        val layout = grid(listOf(always))
        assertSame(layout, layout.forScreen(landscape = true))
    }

    @Test
    fun `each way up keeps its own keys`() {
        val layout = grid(listOf(always, upright, sideways))
        assertEquals(listOf("a", "s"), layout.forScreen(landscape = true).rows[0].map { it.label })
        assertEquals(listOf("a", "u"), layout.forScreen(landscape = false).rows[0].map { it.label })
    }

    @Test
    fun `a row left empty goes with its height`() {
        val layout = grid(listOf(always), listOf(sideways), listOf(always), heights = listOf(1f, 2f, 3f))
        val upright = layout.forScreen(landscape = false)
        assertEquals(2, upright.rows.size)
        assertEquals(listOf(1f, 3f), upright.rowHeights)
    }
}
