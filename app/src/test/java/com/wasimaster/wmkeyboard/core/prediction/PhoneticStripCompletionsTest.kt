package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.HindiPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phonetic strip offers the words a romanized buffer is the start of, and
 * orders a fold's siblings by how close each is to what was typed.
 */
class PhoneticStripCompletionsTest {

    private fun engine(index: BengaliPhoneticIndex) =
        SuggestionEngine(Trie(), index, UserLexicon(null)).apply {
            primaryLanguageId = "bn"
            englishSources = false
        }

    private fun SuggestionEngine.strip(buffer: String, previous: String? = null) =
        suggest(buffer, previousWord = previous, phoneticLanguage = "bn", limit = 5)

    private val bangla = BengaliPhoneticIndex(
        listOf("বাংলা" to 5000, "বাংলাদেশ" to 3000, "বাংলাদেশের" to 900, "আমি" to 9000, "আমার" to 8000),
    )

    @Test
    fun `a word typed partway offers the words it begins`() {
        val engine = engine(bangla)
        val strip = engine.strip("bangl")
        assertTrue(strip.toString(), "বাংলা" in strip)
        assertTrue(strip.toString(), "বাংলাদেশ" in strip)
    }

    @Test
    fun `the head and what a space commits do not change for them`() {
        val engine = engine(bangla)
        val commit = engine.phoneticCommit("bn", "bangl")!!.output
        assertEquals(commit, engine.strip("bangl").first())
        assertFalse(commit == "বাংলাদেশ")
    }

    @Test
    fun `off with no count to rank by`() {
        val flat = BengaliPhoneticIndex(listOf("বাংলা" to 1, "বাংলাদেশ" to 1))
        assertFalse("বাংলাদেশ" in engine(flat).strip("bangl"))
    }

    @Test
    fun `off when asked for none`() {
        val engine = engine(bangla).apply { phoneticCompletionCount = 0 }
        assertFalse("বাংলাদেশ" in engine.strip("bangl"))
    }

    @Test
    fun `a sibling the typing spells beats a commoner one it argues against`() {
        // ভ and ব share a key; bh asks for ভ.
        val index = BengaliPhoneticIndex(listOf("বাসা" to 2000, "ভাষা" to 1000))
        assertEquals("ভাষা", engine(index).phoneticCommit("bn", "bhasha")!!.output)
        assertEquals("বাসা", engine(index).phoneticCommit("bn", "basa")!!.output)
        assertEquals("বাসা", engine(index).apply { phoneticLiteralPull = 0.0 }.phoneticCommit("bn", "bhasha")!!.output)
    }

    @Test
    fun `letters nobody types apart are not held against a word`() {
        // ণ is typed n like ন: the list's misspelling must not win on that.
        val index = BengaliPhoneticIndex(listOf("আক্রমণ" to 1000, "আক্রমন" to 400))
        assertEquals("আক্রমণ", engine(index).phoneticCommit("bn", "akromon")!!.output)
    }

    @Test
    fun `every phonetic list that keeps its words in order completes`() {
        val hindi = HindiPhoneticIndex(listOf("नमस्ते" to 50, "नमक" to 20, "घर" to 90))
        assertEquals(listOf("नमस्ते", "नमक"), hindi.completions("नम", 5))
    }
}
