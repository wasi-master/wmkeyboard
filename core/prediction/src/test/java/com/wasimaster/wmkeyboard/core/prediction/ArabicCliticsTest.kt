package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Arabic prefixes come off before the stem completes (#1311). */
class ArabicCliticsTest {

    @Test
    fun `the longest prefix that leaves a stem wins`() {
        assertEquals("وب" to "مقتني", ArabicClitics.split("وبمقتني"))
        assertEquals("وال" to "كتا", ArabicClitics.split("والكتا"))
        assertEquals("لل" to "طلا", ArabicClitics.split("للطلا"))
    }

    @Test
    fun `too little left after the prefix is no split`() {
        assertNull(ArabicClitics.split("وب"))
        assertNull(ArabicClitics.split("كتاب".take(1)))
        assertNull(ArabicClitics.split("مكتبة"))
    }

    @Test
    fun `a finished stem takes every ending`() {
        val words = ArabicClitics.endings("كتاب").map { it.whole to it.stem }
        assertTrue("كتابها" to "كتاب" in words)
        assertTrue("كتابهم" to "كتاب" in words)
        assertEquals(ArabicClitics.ENCLITICS.size, words.count { it.second == "كتاب" })
    }

    @Test
    fun `a begun ending narrows to the endings it starts`() {
        val words = ArabicClitics.endings("كتابه").filter { it.stem == "كتاب" }.map { it.whole }.toSet()
        // The word as typed is among them; the engine leaves that one out.
        assertEquals(setOf("كتابه", "كتابها", "كتابهم", "كتابهما", "كتابهن"), words)
        assertTrue(ArabicClitics.endings("كتابه").filter { it.stem == "كتاب" }.all { it.typedEnding == 1 })
    }

    @Test
    fun `ta marbuta and alif maqsura take the ending on their own letters`() {
        val school = ArabicClitics.endings("مدرسة").map { it.whole }
        assertTrue("مدرستها" in school)
        val typed = ArabicClitics.endings("مدرستك").filter { it.stem == "مدرسة" }.map { it.whole }
        assertTrue("مدرستكم" in typed)
        assertTrue("مستشفاه" in ArabicClitics.endings("مستشفى").map { it.whole })
    }

    @Test
    fun `a prefix comes off the stem the lists are asked for`() {
        val read = ArabicClitics.endings("وكتابه").filter { it.whole == "وكتابهم" }
        assertTrue(read.any { it.stem == "كتاب" })
        assertFalse(ArabicClitics.endings("و").isNotEmpty())
    }
}
