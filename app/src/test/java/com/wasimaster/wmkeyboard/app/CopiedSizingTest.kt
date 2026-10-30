package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopiedSizingTest {
    @Test
    fun readsTheBlockTheCopierWrites() {
        val sizing = CopiedSizing.parse(
            """{"id":"copied","keyBackground":4281612095,"_copy":{"sizing":{"numberRow":true,"landscape":false,
            "keyHeightDp":44.000,"numberRowHeightDp":43.000,"bottomRowHeightDp":44.000,"keyGapScale":1.033,
            "sidePadLeftScale":0.004,"sidePadRightScale":0.006,"toolbarHeightDp":45.000,"bottomGapDp":50.000,
            "fullScreenshot":true},"report":{"grid":"outlines","confidence":0.900}}}""",
        )!!
        assertTrue(sizing.numberRow)
        assertFalse(sizing.landscape)
        assertTrue(sizing.fullScreenshot)
        assertEquals(44f, sizing.keyHeightDp, 0.001f)
        assertEquals(43f, sizing.numberRowHeightDp, 0.001f)
        assertEquals(1.033f, sizing.keyGapScale, 0.001f)
        assertEquals(0.006f, sizing.sidePadRightScale, 0.001f)
        assertEquals(50f, sizing.bottomGapDp, 0.001f)
        assertEquals(0.9f, sizing.confidence, 0.001f)
    }

    @Test
    fun noBlockIsNoSizing() {
        assertNull(CopiedSizing.parse("""{"id":"copied"}"""))
        assertNull(CopiedSizing.parse("not json"))
    }
}
