package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The personal dictionary's `.txt` export (gap list item 32) is Gboard's `dictionary.txt`. */
class GboardDictionaryWriteTest {

    @Test fun whatIsWrittenReadsBack() {
        val entries = listOf(
            GboardDictionary.Entry(shortcut = "", word = "Wasimaster", locale = "en-US"),
            GboardDictionary.Entry(shortcut = "", word = "ভালোবাসা", locale = "bn-BD"),
            GboardDictionary.Entry(shortcut = "", word = "okay", locale = ""),
        )
        val text = GboardDictionary.write(entries)
        assertTrue(text.startsWith(GboardDictionary.HEADER))
        assertTrue(GboardDictionary.looksLikeGboard(text))
        val read = GboardDictionary.read(text)
        assertNotNull(read)
        assertEquals(entries, read!!.entries)
    }

    @Test fun oneWordPerLine() {
        val text = GboardDictionary.write(listOf(GboardDictionary.Entry("", "café", "fr-FR")))
        assertEquals(listOf("# Gboard Dictionary version:1", "\tcafé\tfr-FR", ""), text.split('\n'))
    }

    @Test fun tabsAndLineBreaksCannotSplitAField() {
        val text = GboardDictionary.write(listOf(GboardDictionary.Entry("", "two\twords\nhere", "")))
        assertEquals("two words here", GboardDictionary.read(text)!!.entries.single().word)
    }
}
