package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** The language a `.wmlm` file's name seeds the import dialog with. */
class LanguageModelImportTest {

    @Test
    fun `the language a model is named after is the guess`() {
        assertEquals("de", WMFileTypes.languageGuessFromName("de_context.wmlm"))
        assertEquals("de", WMFileTypes.languageGuessFromName("DE-context.wmlm"))
        assertEquals("bn_rom", WMFileTypes.languageGuessFromName("bn_rom_context.wmlm"))
        assertEquals("bn_rom", WMFileTypes.languageGuessFromName("bn-rom.wmlm"))
    }

    @Test
    fun `a name that names no language seeds English`() {
        assertEquals("en", WMFileTypes.languageGuessFromName("context.wmlm"))
        assertEquals("en", WMFileTypes.languageGuessFromName("trained model.wmlm"))
        assertEquals("en", WMFileTypes.languageGuessFromName(".wmlm"))
    }
}
