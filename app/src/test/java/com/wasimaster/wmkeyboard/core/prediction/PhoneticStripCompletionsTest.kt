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

    /** #1557: দারুণ typed again and again completes from "dar", list or no list. */
    @Test
    fun `a word the user keeps typing completes right after the head`() {
        val index = BengaliPhoneticIndex(listOf("দার" to 4000, "দারা" to 3000, "দারি" to 2000))
        val lexicon = UserLexicon(null).apply { learnWord("দারুণ", count = 3, langId = "bn") }
        val engine = SuggestionEngine(Trie(), index, lexicon).apply {
            primaryLanguageId = "bn"
            englishSources = false
        }
        val strip = engine.strip("dar")
        assertEquals(strip.toString(), 1, strip.indexOf("দারুণ"))
    }

    @Test
    fun `the user's own words complete on a flat list too`() {
        val flat = BengaliPhoneticIndex(listOf("বাংলা" to 1, "বাংলাদেশ" to 1))
        val lexicon = UserLexicon(null).apply { learnWord("বাংলাদেশ", count = 2, langId = "bn") }
        val engine = SuggestionEngine(Trie(), flat, lexicon).apply {
            primaryLanguageId = "bn"
            englishSources = false
        }
        assertTrue("বাংলাদেশ" in engine.strip("bangl"))
    }

    /** #1567: a word the user taught in a spelling the rules do not write is found while it is typed. */
    @Test
    fun `the user's own word the spelling folds to is offered while typing`() {
        val lexicon = UserLexicon(null).apply { learnWord("রহমাতুল্লাহি", count = 1, langId = "bn") }
        val engine = SuggestionEngine(Trie(), bangla, lexicon).apply {
            primaryLanguageId = "bn"
            englishSources = false
        }
        val strip = engine.strip("rohmatullahi")
        assertTrue(strip.toString(), "রহমাতুল্লাহি" in strip)
        // Typed once is not yet the user's spelling: the reading still commits.
        assertEquals("রহ্মাতুল্লাহি", strip.first())
    }

    @Test
    fun `an established own word leads a reading no list holds`() {
        val lexicon = UserLexicon(null).apply { learnWord("রহমাতুল্লাহি", count = 3, langId = "bn") }
        val engine = SuggestionEngine(Trie(), bangla, lexicon).apply {
            primaryLanguageId = "bn"
            englishSources = false
        }
        assertEquals("রহমাতুল্লাহি", engine.strip("rohmatullahi").first())
        assertEquals("রহমাতুল্লাহি", engine.phoneticCommit("bn", "rohmatullahi")!!.output)
    }

    @Test
    fun `an own word never takes the head from a listed reading`() {
        val lexicon = UserLexicon(null).apply { learnWord("আমী", count = 5, langId = "bn") }
        val engine = SuggestionEngine(Trie(), bangla, lexicon).apply {
            primaryLanguageId = "bn"
            englishSources = false
        }
        val strip = engine.strip("ami")
        assertEquals("আমি", strip.first())
        assertTrue(strip.toString(), "আমী" in strip)
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
