package com.wasimaster.wmkeyboard.core.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabCardFieldsTest {

    @Test
    fun `defaults need no encoding`() {
        assertEquals("", VocabCardFields.encode(VocabCardField.entries.associateWith { it.defaultVisibility }))
        val resolved = VocabCardFields.resolve("")
        assertTrue(VocabCardFields.inKeyboard(resolved, VocabCardField.EXAMPLES))
        assertFalse(VocabCardFields.inKeyboard(resolved, VocabCardField.QUOTATIONS))
        assertTrue(VocabCardFields.inApp(resolved, VocabCardField.QUOTATIONS))
    }

    @Test
    fun `overrides round trip and unknown keys are ignored`() {
        val encoded = VocabCardFields.with("", VocabCardField.RHYMES, FieldVisibility.KEYBOARD)
        assertEquals("rhymes=kb", encoded)
        val more = VocabCardFields.with(encoded, VocabCardField.EXAMPLES, FieldVisibility.OFF)
        assertEquals("examples=off,rhymes=kb", more)
        assertEquals(FieldVisibility.OFF, VocabCardFields.visibility(more, VocabCardField.EXAMPLES))
        val resolved = VocabCardFields.resolve("$more,nonsense=kb,ipa=weird")
        assertFalse(VocabCardFields.inApp(resolved, VocabCardField.EXAMPLES))
        assertEquals(FieldVisibility.KEYBOARD, resolved[VocabCardField.IPA])
        // Setting a field back to its default drops it from the string.
        assertEquals("rhymes=kb", VocabCardFields.with(more, VocabCardField.EXAMPLES, FieldVisibility.KEYBOARD))
    }
}
