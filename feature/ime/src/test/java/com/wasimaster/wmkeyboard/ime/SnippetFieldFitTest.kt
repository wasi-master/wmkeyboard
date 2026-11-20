package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a snippet may put into a field that is not prose (#815). */
class SnippetFieldFitTest {

    @Test
    fun `digits go into every numeric field`() {
        for (kind in listOf(FieldKind.NUMBER, FieldKind.PHONE, FieldKind.DATE, FieldKind.TIME, FieldKind.DATETIME)) {
            assertTrue(kind.name, snippetFitsField("0123", kind))
        }
    }

    @Test
    fun `a phone number with its marks goes into a phone field`() {
        assertTrue(snippetFitsField("+44 (20) 7946-0018", FieldKind.PHONE))
    }

    @Test
    fun `letters never go into a number field`() {
        assertFalse(snippetFitsField("call me", FieldKind.NUMBER))
        assertFalse(snippetFitsField("12 apples", FieldKind.NUMBER))
    }

    @Test
    fun `prose fields take anything`() {
        assertTrue(snippetFitsField("On my way!", FieldKind.TEXT))
        assertTrue(snippetFitsField("me@example.com", FieldKind.EMAIL))
    }

    @Test
    fun `prose without a variable is turned away before it is expanded`() {
        assertFalse(couldFitKeypad("word1"))
        assertTrue(couldFitKeypad("+44 20 7946"))
        assertTrue(couldFitKeypad("{date:yyyy-MM-dd}"))
    }
}
