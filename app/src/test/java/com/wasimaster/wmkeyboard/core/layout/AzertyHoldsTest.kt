package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AzertyHoldsTest {
    private fun holds(spec: LayoutSpec, layer: LayoutLayer, label: String): List<String> =
        spec.layer(layer)!!.rows.flatten().first { it.label == label }.longPress

    @Test
    fun `french leads c with its cedilla and d with the euro`() {
        assertEquals("ç", holds(BuiltInLayouts.FRENCH, LayoutLayer.LETTERS, "c").first())
        assertEquals(listOf("€", "$"), holds(BuiltInLayouts.FRENCH, LayoutLayer.LETTERS, "d"))
    }

    @Test
    fun `the question mark lives on one letter hold`() {
        val carriers = BuiltInLayouts.FRENCH.layer(LayoutLayer.LETTERS)!!.rows.dropLast(1).flatten()
            .filter { "?" in it.longPress }
            .map { it.label }
        assertEquals(listOf("'"), carriers)
    }

    @Test
    fun `only french's symbols page leads with the euro`() {
        assertTrue("$" in holds(BuiltInLayouts.FRENCH, LayoutLayer.SYMBOLS, "€"))
        assertEquals(null, BuiltInLayouts.AZERTY.layer(LayoutLayer.SYMBOLS))
    }
}
