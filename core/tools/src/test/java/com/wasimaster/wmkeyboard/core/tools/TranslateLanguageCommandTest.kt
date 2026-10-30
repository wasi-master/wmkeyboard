package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslateLanguageCommandTest {

    @Test
    fun `a code alone is the target`() {
        assertEquals(null to "ja", TranslateClient.languageCommand("/ja"))
    }

    @Test
    fun `two codes are the source and the target`() {
        assertEquals("bn" to "ja", TranslateClient.languageCommand("/bn:ja"))
    }

    @Test
    fun `a bare chinese code becomes the one the picker holds`() {
        assertEquals(null to "zh-CN", TranslateClient.languageCommand("/zh"))
    }

    @Test
    fun `anything else is text`() {
        for (typed in listOf("ja", "/", "/ja:", "/:ja", "/usr", "/a:b:c", "hello")) {
            assertNull(typed, TranslateClient.languageCommand(typed))
        }
    }
}
