package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.CopiedCodeChip
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopiedCodeChipRulesTest {

    private val now = 1_000_000L
    private val maxAge = 5L * 60 * 1000

    private fun offers(
        mode: CopiedCodeChip = CopiedCodeChip.ANY_FIELD,
        fieldKind: FieldKind = FieldKind.TEXT,
        clipAgeMs: Long = 0,
        showingAgeMs: Long? = null,
    ) = offersCopiedCode(
        mode = mode,
        fieldKind = fieldKind,
        clipTimestamp = now - clipAgeMs,
        showingTimestamp = showingAgeMs?.let { now - it },
        now = now,
        maxAgeMs = maxAge,
    )

    @Test
    fun `off never offers`() {
        assertFalse(offers(mode = CopiedCodeChip.OFF))
        assertFalse(offers(mode = CopiedCodeChip.OFF, fieldKind = FieldKind.NUMBER))
    }

    @Test
    fun `code fields only wants a number field`() {
        assertTrue(offers(mode = CopiedCodeChip.CODE_FIELDS, fieldKind = FieldKind.NUMBER))
        assertFalse(offers(mode = CopiedCodeChip.CODE_FIELDS, fieldKind = FieldKind.TEXT))
        // A phone or date keypad asks for digits without being a code box.
        assertFalse(offers(mode = CopiedCodeChip.CODE_FIELDS, fieldKind = FieldKind.PHONE))
        assertFalse(offers(mode = CopiedCodeChip.CODE_FIELDS, fieldKind = FieldKind.DATE))
    }

    @Test
    fun `any field offers outside a code box`() {
        // Issue #66: an alphanumeric code box is a plain text field, and a code
        // copied by hand is offered there like any other copy.
        assertTrue(offers(fieldKind = FieldKind.TEXT))
        assertTrue(offers(fieldKind = FieldKind.NUMBER))
    }

    @Test
    fun `a stale copy is not offered`() {
        assertTrue(offers(clipAgeMs = maxAge))
        assertFalse(offers(clipAgeMs = maxAge + 1))
    }

    @Test
    fun `a newer code replaces the chip already on the strip`() {
        // Copy a message, then re-copy just the code out of it: the code is the
        // fresher clip and has to win, or the message's chip stands and the
        // code the user went back for never appears.
        assertTrue(offers(clipAgeMs = 0, showingAgeMs = 30_000))
    }

    @Test
    fun `an equally fresh or newer chip stands`() {
        assertFalse(offers(clipAgeMs = 0, showingAgeMs = 0))
        assertFalse(offers(clipAgeMs = 30_000, showingAgeMs = 0))
    }
}
