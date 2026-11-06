package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypadSumTest {

    private fun KeypadSum.insert(decimals: Boolean = true) = answer(true, 8, decimals)?.insert
    private fun KeypadSum.shown(decimals: Boolean = true) = answer(true, 8, decimals)?.shown

    @Test
    fun `the number in front of the caret is what a sum starts from`() {
        assertEquals("120", KeypadSum.operandBefore("120"))
        assertEquals("12.5", KeypadSum.operandBefore("total 12.5"))
        assertEquals("-5", KeypadSum.operandBefore("-5"))
        assertEquals("3", KeypadSum.operandBefore("5-3"))
        assertEquals("5", KeypadSum.operandBefore("x,5"))
        assertNull(KeypadSum.operandBefore(""))
        assertNull(KeypadSum.operandBefore("abc"))
        assertNull(KeypadSum.operandBefore("12 "))
    }

    @Test
    fun `an operator starts a sum and a digit or percent does not`() {
        assertTrue(KeypadSum.startsSum("*"))
        assertTrue(KeypadSum.startsSum("-"))
        assertTrue(KeypadSum.startsSum("÷"))
        assertFalse(KeypadSum.startsSum("%"))
        assertFalse(KeypadSum.startsSum("5"))
        assertFalse(KeypadSum.startsSum("."))
    }

    @Test
    fun `a finished sum answers`() {
        assertEquals("360", KeypadSum("120", "*").append("3")!!.insert())
        assertEquals("2", KeypadSum("-5", "+").append("7")!!.insert())
        assertEquals("220", KeypadSum("200", "+10%").insert())
    }

    @Test
    fun `a sum waiting on its next number answers with what it has`() {
        assertEquals("120", KeypadSum("120", "*").insert())
        assertEquals("15", KeypadSum("12", "+3*").insert())
    }

    @Test
    fun `a second operator replaces the first, except a minus after times`() {
        assertEquals("*", KeypadSum("5", "+").append("*")!!.tail)
        assertEquals("-", KeypadSum("5", "+").append("-")!!.tail)
        assertEquals("*-", KeypadSum("5", "*").append("-")!!.tail)
        assertEquals("-15", KeypadSum("5", "*-").append("3")!!.insert())
    }

    @Test
    fun `a field with no fractions gets the answer rounded and marked`() {
        val sum = KeypadSum("10", "/4")
        assertEquals("2.5", sum.insert(decimals = true))
        assertEquals("3", sum.insert(decimals = false))
        assertEquals("~3", sum.shown(decimals = false))
        assertEquals("4", KeypadSum("8", "/2").shown(decimals = false))
    }

    @Test
    fun `a decimal comma is read and written back`() {
        assertEquals("3", KeypadSum("1,5", "*2").insert())
        assertEquals("2,5", KeypadSum("1,5", "+1").insert())
    }

    @Test
    fun `native digits are read as numbers`() {
        assertEquals("24", KeypadSum("১২", "*২").insert())
    }

    @Test
    fun `backspace edits the sum and ends it past the operator`() {
        assertEquals("+", KeypadSum("5", "+3").backspace()!!.tail)
        assertNull(KeypadSum("5", "+").backspace())
    }

    @Test
    fun `only what belongs in a sum is taken`() {
        assertNull(KeypadSum("5", "+").append("a"))
        assertNull(KeypadSum("5", "+").append(" "))
        assertEquals("+3.5", KeypadSum("5", "+").append("3")!!.append(".")!!.append("5")!!.tail)
    }

    @Test
    fun `the strip shows the sum with real signs`() {
        assertEquals("120 × 3", KeypadSum("120", "*3").display)
        assertEquals("−5 − 2", KeypadSum("-5", "-2").display)
        assertEquals("8 ÷", KeypadSum("8", "/").display)
        assertEquals("5 × −3", KeypadSum("5", "*-3").display)
    }

    @Test
    fun `the chip replaces exactly the number in the field`() {
        val hit = KeypadSum("120", "*3").hit(degrees = true, precision = 8, decimals = true)
        assertEquals(3, hit.replaceSpan)
        assertEquals("360", hit.insert)
        assertEquals("360", hit.result)
        assertEquals("120 × 3", hit.query)
    }
}
