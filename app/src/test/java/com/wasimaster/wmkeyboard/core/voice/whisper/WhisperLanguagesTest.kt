package com.wasimaster.wmkeyboard.core.voice.whisper

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperLanguagesTest {

    @Test
    fun `table holds whisper's 100 languages with no duplicates`() {
        assertEquals(100, WhisperLanguages.codes.size)
        assertEquals(100, WhisperLanguages.codes.toSet().size)
    }

    @Test
    fun `cantonese takes large-v3's token and has a model row`() {
        assertEquals(50358, WhisperLanguages.tokenFor("yue"))
        assertEquals("yue", WhisperLanguages.codeForLanguage("yue"))
    }

    /**
     * The token ids are the whole point of the table — these four are the ones
     * the DocWolle `.tokens` files and the generation notebook pin down.
     */
    @Test
    fun `token ids match the ids baked into the published models`() {
        assertEquals(50259, WhisperLanguages.tokenFor("en"))
        assertEquals(50261, WhisperLanguages.tokenFor("de"))
        assertEquals(50343, WhisperLanguages.tokenFor("mt"))
        assertEquals(50345, WhisperLanguages.tokenFor("lb"))
        assertEquals(WhisperLanguages.FIRST_TOKEN + 98, WhisperLanguages.tokenFor("su"))
    }

    @Test
    fun `unknown codes have no token`() {
        assertNull(WhisperLanguages.tokenFor("tlh"))
        assertNull(WhisperLanguages.tokenFor(""))
    }

    @Test
    fun `keyboard ids that spell a language differently still map`() {
        assertEquals("no", WhisperLanguages.codeForLanguage("nb"))
        assertEquals("jw", WhisperLanguages.codeForLanguage("jv"))
        assertEquals("bn", WhisperLanguages.codeForLanguage("bn"))
        // Kurdish is not in Whisper's set at all.
        assertNull(WhisperLanguages.codeForLanguage("ckb"))
    }

    @Test
    fun `mapping back from a code lands on a real keyboard language`() {
        assertEquals("nb", WhisperLanguages.languageIdFor("no"))
        assertEquals("jv", WhisperLanguages.languageIdFor("jw"))
        for (code in WhisperLanguages.codes) {
            val id = WhisperLanguages.languageIdFor(code) ?: continue
            assertTrue("$code -> $id", LanguageRegistry.all.any { it.id == id })
        }
    }

    @Test
    fun `labels prefer the keyboard's own english name`() {
        // The registry calls it Bangla; Whisper's own table says Bengali.
        assertEquals("Bangla", WhisperLanguages.label("bn"))
        assertEquals("German", WhisperLanguages.label("de"))
        // Nothing in the registry maps to Bosnian, so Whisper's name is used.
        assertEquals("Bosnian", WhisperLanguages.label("bs"))
        assertEquals(listOf("English", "German"), WhisperLanguages.labels(listOf("de", "en")))
    }
}
