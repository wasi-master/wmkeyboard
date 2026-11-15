package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
