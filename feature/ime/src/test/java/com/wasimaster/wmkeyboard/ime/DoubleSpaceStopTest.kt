package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1559: a double space after a sentence that already has its mark adds no second stop. */
class DoubleSpaceStopTest {

    @Test
    fun `a word with its full stop already ends the sentence`() {
        assertTrue(endsWithSentenceMark("test."))
        assertTrue(endsWithSentenceMark("Why?"))
        assertTrue(endsWithSentenceMark("wait…"))
        assertTrue(endsWithSentenceMark("আমি।"))
        assertTrue(endsWithSentenceMark("کیا؟"))
    }

    @Test
    fun `closing quotes and brackets after the mark are looked past`() {
        assertTrue(endsWithSentenceMark("he said “Go!”"))
        assertTrue(endsWithSentenceMark("(see above.)"))
    }

    @Test
    fun `a bare word or a comma still gets its stop`() {
        assertFalse(endsWithSentenceMark("test"))
        assertFalse(endsWithSentenceMark("test,"))
        assertFalse(endsWithSentenceMark("“quoted”"))
        assertFalse(endsWithSentenceMark(""))
    }
}
