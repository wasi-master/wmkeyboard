package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Several keys styled from one sheet (Telegram 6201): only what was edited is carried over. */
class KeyOverrideMergeTest {

    @Test
    fun `the edited field is carried and the key's own others stay`() {
        val before = KeyOverride(background = 0xFF111111)
        val after = KeyOverride(background = 0xFF222222)
        val other = KeyOverride(background = 0xFF999999, border = 0xFF00FF00)
        assertEquals(
            KeyOverride(background = 0xFF222222, border = 0xFF00FF00),
            other.withChangesOf(before, after),
        )
    }

    @Test
    fun `a field cleared back to automatic is cleared on the others too`() {
        val before = KeyOverride(text = 0xFFFFFFFF)
        val after = KeyOverride()
        val other = KeyOverride(text = 0xFF000000, background = 0xFF123456)
        assertEquals(KeyOverride(background = 0xFF123456), other.withChangesOf(before, after))
    }

    @Test
    fun `the key being edited ends exactly as edited`() {
        val before = KeyOverride(background = 0xFF111111, bold = true)
        val after = before.copy(background = 0xFF333333)
        assertEquals(after, before.withChangesOf(before, after))
    }

    @Test
    fun `no edit leaves the key as it was`() {
        val other = KeyOverride(background = 0xFF999999)
        assertSame(other, other.withChangesOf(KeyOverride(), KeyOverride()))
    }
}
