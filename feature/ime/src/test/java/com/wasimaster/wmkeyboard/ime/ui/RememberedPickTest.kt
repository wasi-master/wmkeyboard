package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The comma key typing what was last picked from its hold (#1096). */
class RememberedPickTest {

    private val comma = Key(",", longPress = listOf("?", "!", ";"))

    @Test
    fun `the pick types and the comma leads the hold`() {
        val key = comma.withRememberedPick(mapOf("," to "!"))
        assertEquals("!", key.label)
        assertEquals(listOf(",", "?", ";"), key.longPress)
    }

    @Test
    fun `a pick the popup no longer offers is ignored`() {
        assertSame(comma, comma.withRememberedPick(mapOf("," to "…")))
    }

    @Test
    fun `a key with nothing remembered is left alone`() {
        assertSame(comma, comma.withRememberedPick(mapOf("." to "?")))
    }
}
