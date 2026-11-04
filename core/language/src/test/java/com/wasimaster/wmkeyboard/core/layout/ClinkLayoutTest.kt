package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Clink `.clinklayout`: a name and three rows of letters. */
class ClinkLayoutTest {

    private val flow = """{"id": "custom-clink-flow", "name": "Clink Flow", "rows": [
        ["w", "c", "s", "l", "b", "x", "g", "y", "j", "z"],
        ["h", "t", "n", "d", "v", "i", "e", "a", "q"],
        ["f", "r", "m", "k", "u", "o", "p"]]}"""

    @Test
    fun `the rows come across in order under the file's name`() {
        assertTrue(ClinkLayouts.looksLikeClinkLayout(flow))
        val converted = ClinkLayouts.read(flow)!!
        assertEquals("Clink Flow", converted.layout.name)
        val labels = converted.layout.layers.getValue(LayoutLayer.LETTERS.key).rows
            .flatten().map { it.label }
        // The letters keep their order; this app's frame adds keys round them.
        val letters = labels.filter { it.length == 1 && it[0].isLetter() }
        assertEquals("wcslbxgyjzhtndvieaqfrmkuop", letters.joinToString(""))
    }

    @Test
    fun `other JSON is not a Clink layout`() {
        assertFalse(ClinkLayouts.looksLikeClinkLayout("""{"rows": "abc"}"""))
        assertFalse(ClinkLayouts.looksLikeClinkLayout("""[["a", "b"]]"""))
        assertNull(ClinkLayouts.read("""{"rows": [[]]}"""))
    }
}
