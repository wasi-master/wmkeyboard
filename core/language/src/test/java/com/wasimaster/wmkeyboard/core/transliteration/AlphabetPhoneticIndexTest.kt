package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphabetPhoneticIndexTest {

    private fun index(languageId: String, rules: Map<String, String>, vararg words: Pair<String, Int>) =
        AlphabetPhoneticIndex(
            languageId,
            PhoneticTables.build(languageId, words.toList()),
            rules = { rules.getValue(it) },
        )

    @Test
    fun `Mongolian finds the vowel the rules could not`() {
        // Chat writes "odor" for өдөр: the rules write о, the list has ө.
        val index = index("mn", mapOf("odor" to "одор"), "өдөр" to 900, "одор" to 3)
        assertEquals(listOf("өдөр", "одор"), index.lookup("odor"))
    }

    @Test
    fun `a flat list leads with what the rules wrote`() {
        val index = index("mn", mapOf("odor" to "одор"), "өдөр" to 1, "одор" to 1)
        assertEquals("одор", index.lookup("odor").first())
    }

    @Test
    fun `Yiddish reads a list with or without points`() {
        val index = index("yi", mapOf("man" to "מאַן"), "מאן" to 5, "מאָן" to 9)
        assertEquals(listOf("מאָן", "מאן"), index.lookup("man"))
    }

    @Test
    fun `Yiddish drops the silent alef and reads final forms`() {
        assertEquals(AlphabetFold.key("yi", "אױג"), AlphabetFold.key("yi", "ױג"))
        assertEquals(AlphabetFold.key("yi", "מאַן"), AlphabetFold.key("yi", "מאנ"))
    }

    @Test
    fun `Ol Chiki folds retroflex against dental`() {
        assertEquals(AlphabetFold.key("sat", "ᱴᱟ"), AlphabetFold.key("sat", "ᱛᱟ"))
        assertEquals("santali", AlphabetFold.key("sat", "ᱥᱟᱱᱛᱟᱞᱤ"))
    }

    @Test
    fun `Meetei reads its closing letters and a long a`() {
        assertEquals(AlphabetFold.key("mni", "ꯀꯟ"), AlphabetFold.key("mni", "ꯀꯅ"))
        assertEquals(AlphabetFold.key("mni", "ꯃꯥ"), AlphabetFold.key("mni", "ꯃ"))
    }

    @Test
    fun `a word with letters outside the alphabet has no key`() {
        assertNull(AlphabetFold.key("mn", "hello"))
        val index = index("mn", mapOf("sain" to "сайн"), "hello" to 9, "сайн" to 1)
        assertEquals(listOf("сайн"), index.lookup("sain"))
    }

    @Test
    fun `every alphabet language is filed under its own fold`() {
        for (id in MinorPhonetic.languages.keys) assertTrue(id, PhoneticTables.supports(id))
    }
}
