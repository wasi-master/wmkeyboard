package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionMacroCodecTest {

    private val version = SelectionMacros.LIST_VERSION

    @Test
    fun `a missing or pre-merge version reads as the shipped lists`() {
        val stored = setOf(SelectionMacro.COPY.name)
        val before = SelectionMacroCodec.MERGE_FROM - 1
        assertEquals(SelectionMacros.defaultMacros, SelectionMacroCodec.decodeMacros(null, stored))
        assertEquals(SelectionMacros.defaultMacros, SelectionMacroCodec.decodeMacros(before, stored, "COPY"))
        assertEquals(SelectionMacros.defaultMacros, SelectionMacroCodec.decodeMacros(version, null))
        assertEquals(SelectionMacros.defaultOrder, SelectionMacroCodec.decodeOrder(null, "COPY\tSHARE"))
        assertEquals(SelectionMacros.defaultOrder, SelectionMacroCodec.decodeOrder(before, "COPY\tSHARE"))
        assertEquals(SelectionMacros.defaultOrder, SelectionMacroCodec.decodeOrder(version, ""))
    }

    @Test
    fun `an older version keeps the user's choices and gains only the new shipped actions`() {
        // Written by a build that did not have furigana yet: the user switched
        // Share off, Search on, and moved Copy to the front.
        val oldOrder = listOf(SelectionMacro.COPY) +
            SelectionMacros.defaultOrder.filter { it != SelectionMacro.COPY && it != SelectionMacro.ADD_FURIGANA }
        val oldOn = SelectionMacros.defaultMacros - SelectionMacro.SHARE - SelectionMacro.ADD_FURIGANA + SelectionMacro.SEARCH
        val encodedOrder = SelectionMacroCodec.encodeOrder(oldOrder)
        val macros = SelectionMacroCodec.decodeMacros(version - 1, SelectionMacroCodec.encodeMacros(oldOn), encodedOrder)
        assertTrue(SelectionMacro.SHARE !in macros)
        assertTrue(SelectionMacro.SEARCH in macros)
        assertTrue(SelectionMacro.ADD_FURIGANA in macros)
        assertEquals(oldOn + SelectionMacro.ADD_FURIGANA, macros)

        val order = SelectionMacroCodec.decodeOrder(version - 1, encodedOrder)
        assertEquals(oldOrder, order.take(oldOrder.size))
        assertEquals(SelectionMacros.defaultOrder.toSet(), order.toSet())
    }

    @Test
    fun `an older list without its order keeps its choices rather than guessing`() {
        val oldOn = setOf(SelectionMacro.COPY, SelectionMacro.CUT)
        assertEquals(oldOn, SelectionMacroCodec.decodeMacros(version - 1, SelectionMacroCodec.encodeMacros(oldOn), null))
    }

    @Test
    fun `the current version round-trips, dropping what this build does not know`() {
        val macros = setOf(SelectionMacro.COPY, SelectionMacro.CUT)
        assertEquals(macros, SelectionMacroCodec.decodeMacros(version, SelectionMacroCodec.encodeMacros(macros) + "FROM_THE_FUTURE"))
        // A fixed case is never configurable, so it is dropped even when named.
        assertEquals(macros, SelectionMacroCodec.decodeMacros(version, setOf("COPY", "CUT", "CASE_LOWER")))
    }

    @Test
    fun `a stored order leads and the rest follow in shipped order`() {
        val encoded = SelectionMacroCodec.encodeOrder(listOf(SelectionMacro.SHARE, SelectionMacro.CASE_CAMEL, SelectionMacro.COPY)) + "\tNOPE\tCOPY"
        val order = SelectionMacroCodec.decodeOrder(version, encoded)
        assertEquals(listOf(SelectionMacro.SHARE, SelectionMacro.COPY), order.take(2))
        assertTrue(SelectionMacro.CASE_CAMEL !in order)
        assertEquals(SelectionMacros.defaultOrder.size, order.size)
        assertEquals(SelectionMacros.defaultOrder.toSet(), order.toSet())
    }
}
