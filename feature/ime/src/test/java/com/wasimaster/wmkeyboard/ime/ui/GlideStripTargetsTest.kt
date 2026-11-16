package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A glide lifted on a strip chip takes that chip's word (#1386). */
class GlideStripTargetsTest {

    @Test
    fun aLiftOnAChipNamesItsWordAndOneBesideItNamesNothing() {
        val targets = GlideStripTargets()
        targets.put("their", Rect(0f, 0f, 100f, 40f))
        targets.put("there", Rect(100f, 0f, 200f, 40f))
        assertEquals("there", targets.wordAt(Offset(150f, 20f)))
        assertEquals("their", targets.wordAt(Offset(10f, 5f)))
        assertNull(targets.wordAt(Offset(150f, 80f)))
    }

    @Test
    fun aChipThatLeftTheStripIsNoTarget() {
        val targets = GlideStripTargets()
        targets.put("the", Rect(0f, 0f, 100f, 40f))
        targets.remove("the")
        assertNull(targets.wordAt(Offset(50f, 20f)))
    }
}
