package com.wasimaster.wmkeyboard.ime.wmtranslate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameLanguageTest {
    @Test fun wmTranslatesOwnSpellingsCountAsTheLanguageAsked() {
        assertTrue(sameLanguage("zh", "zh-CN"))
        assertTrue(sameLanguage("pt-BR", "pt"))
        assertTrue(sameLanguage("nb", "no"))
        assertTrue(sameLanguage("iw", "he"))
        assertTrue(sameLanguage("fil", "tl"))
        assertTrue(sameLanguage("en_GB", "en"))
    }

    @Test fun anotherLanguageIsNot() {
        assertFalse(sameLanguage("bn", "en"))
        assertFalse(sameLanguage("ja", "zh-CN"))
    }

    @Test fun anAutoOrBlankAskTakesAnyAnswer() {
        assertTrue(sameLanguage("", "en"))
        assertTrue(sameLanguage("auto", "fr"))
    }
}
