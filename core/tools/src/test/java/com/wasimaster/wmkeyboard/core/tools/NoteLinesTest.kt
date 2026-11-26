package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteLinesTest {
    private val lines = NoteLines.build(
        listOf(
            "Hamlet" to "To be, or not to be, that is the question:\nWhether 'tis nobler in the mind to suffer\n\nThe slings and arrows of outrageous fortune,",
            "List" to "- Buy milk and eggs today\n- Call the plumber about the sink",
            "Short" to "Hi\nok then",
        ),
    )

    @Test fun firstWordsOfALineOfferTheWholeLine() {
        val hit = lines.match("Quote: \nto be, or not", truncated = false)!!
        assertEquals("To be, or not to be, that is the question:", hit.insert)
        assertEquals("to be, or not".length, hit.replaceSpan)
        assertEquals("Hamlet", hit.source)
    }

    @Test fun aWholeLineOffersTheNextOnALineOfItsOwn() {
        val hit = lines.match("To be, or not to be, that is the question:", truncated = false)!!
        assertEquals("\nWhether 'tis nobler in the mind to suffer", hit.insert)
        assertEquals(0, hit.replaceSpan)
        assertEquals("", hit.typed)
    }

    @Test fun aBlankLineSkipsInTheNote() {
        val hit = lines.match("x\nWhether 'tis nobler in the mind to suffer", truncated = false)!!
        assertEquals("\nThe slings and arrows of outrageous fortune,", hit.insert)
    }

    @Test fun afterEnterTheNextLineComesWithoutABreak() {
        val hit = lines.match("To be, or not to be, that is the question:\n", truncated = false)!!
        assertEquals("Whether 'tis nobler in the mind to suffer", hit.insert)
    }

    @Test fun listMarksAndCaseAndSpacesDoNotCount() {
        val hit = lines.match("buy  MILK and", truncated = false)!!
        assertEquals("Buy milk and eggs today", hit.insert)
        assertEquals("\nCall the plumber about the sink", lines.match("Buy milk and eggs today ", truncated = false)!!.insert)
    }

    @Test fun shortTextNeverRaisesIt() {
        assertNull(lines.match("Hi", truncated = false))
        assertNull(lines.match("to be,", truncated = false))
        assertNull(lines.match("tobeornottobe", truncated = false))
    }

    @Test fun theLastLineHasNothingAfterIt() {
        assertNull(lines.match("The slings and arrows of outrageous fortune,", truncated = false))
    }

    @Test fun aLineWhoseStartWasNotReadIsLeftAlone() {
        assertNull(lines.match("be, or not to be, that is the question:", truncated = true))
    }

    @Test fun keyFolds() {
        assertEquals("buy milk", NoteLines.keyOf("  - [ ]  Buy   Milk "))
        assertEquals("quote", NoteLines.keyOf(">> Quote"))
    }
}
