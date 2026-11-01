package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrbanDictionaryClientTest {

    private fun item(word: String, definition: String, example: String = "") =
        """{"word":"$word","definition":"$definition","example":"$example","thumbs_up":0,"thumbs_down":0}"""

    @Test
    fun `a word it does not know is no entry`() {
        assertTrue(UrbanDictionaryClient.parse("""{"list":[]}""", "zzq").isEmpty())
    }

    @Test
    fun `link brackets and CRLF come out of the text`() {
        val body = """{"list":[${item("Rizz", "[charm]\\r\\nor [style]", "he has [rizz]")}]}"""
        val entry = UrbanDictionaryClient.parse(body, "rizz").single()
        assertEquals("Rizz", entry.word)
        val definition = entry.meanings.single().definitions.single()
        assertEquals("charm\nor style", definition.text)
        assertEquals("he has rizz", definition.example)
    }

    @Test
    fun `entries for another word are left out`() {
        val body = """{"list":[${item("rizzler", "other")},${item("RIZZ", "this one")}]}"""
        val definitions = UrbanDictionaryClient.parse(body, "rizz").single().meanings.single().definitions
        assertEquals(listOf("this one"), definitions.map { it.text })
    }

    @Test
    fun `a blank example is none and the list stops at five`() {
        val items = (1..7).joinToString(",") { item("w", "d$it") }
        val definitions = UrbanDictionaryClient.parse("""{"list":[$items]}""", "w").single().meanings.single().definitions
        assertEquals(5, definitions.size)
        assertNull(definitions.first().example)
    }
}
