package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #970: a glide begun on the number row, above the top letter row. */
class GlideStartAboveTopRowTest {

    // Keys 60 px wide and 84 px tall: q at (30, 42), a row below at y 126.
    private val keyWidth = 60f
    private val centers: Map<Int, Offset> = buildMap {
        "qwertyuiop".forEachIndexed { i, c -> put(c.code, Offset(30f + 60f * i, 42f)) }
        "asdfghjkl".forEachIndexed { i, c -> put(c.code, Offset(60f + 60f * i, 126f)) }
        put(','.code, Offset(150f, 294f))
    }

    @Test fun `the digit over q starts a glide`() {
        // The number row's centre, one row pitch above q.
        assertTrue(glideStartsAboveTopRow(Offset(30f, -42f), centers, keyWidth))
        // The top edge of that digit key.
        assertTrue(glideStartsAboveTopRow(Offset(40f, -80f), centers, keyWidth))
    }

    @Test fun `too far above, or below the top row, it does not`() {
        assertFalse(glideStartsAboveTopRow(Offset(30f, -100f), centers, keyWidth))
        // On a letter row the ordinary radius decides, not this.
        assertFalse(glideStartsAboveTopRow(Offset(30f, 60f), centers, keyWidth))
        // Under the bottom row (the comma's place) nothing changes.
        assertFalse(glideStartsAboveTopRow(Offset(150f, 330f), centers, keyWidth))
    }

    @Test fun `past the end of the top row it does not`() {
        assertFalse(glideStartsAboveTopRow(Offset(30f + 60f * 11, -42f), centers, keyWidth))
    }
}
