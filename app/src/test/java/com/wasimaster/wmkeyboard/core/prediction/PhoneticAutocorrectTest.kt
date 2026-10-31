package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.gesture.RomanizedIndex
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mistyped romanized spellings on Avro, corrected the way a Latin typo is. */
class PhoneticAutocorrectTest {

    private val bengali = BengaliPhoneticIndex(
        listOf(
            "ভালো" to 70388,
            "তোমার" to 60000,
            "ভাব" to 3616,
            "ভাবো" to 73,
            "বাক" to 742,
            "ভাক" to 3,
            "গল্প" to 40000,
            "গল" to 300,
        ),
    )

    private val spellings = SpellingMap.load(
        "".byteInputStream(Charsets.UTF_8),
        "valo\tভালো\ntomar\tতোমার\nvabo\tভাবো\n".byteInputStream(Charsets.UTF_8),
        loanwordStreams = 1,
    )

    private val english = Trie().apply { insert("vakt", 500) }

    private fun engine(lexicon: UserLexicon = UserLexicon(null)) =
        SuggestionEngine(english, bengali, lexicon, spellings).apply {
            primaryLanguageId = "bn"
            englishSources = false
            phoneticRomanized = mapOf(
                "bn" to RomanizedIndex.of(spellings, bengali, nativeFrequency = bengali::frequencyOf),
            )
        }

    private fun SuggestionEngine.strip(buffer: String) =
        suggest(buffer, previousWord = null, phoneticLanguage = "bn")

    @Test
    fun `a key next door reaches the spelling and the word behind it`() {
        val engine = engine()
        assertEquals("ভালো", engine.strip("vako").first())
        val commit = engine.phoneticCommit("bn", "vako")!!
        assertEquals("ভালো", commit.output)
        assertEquals("what the rules and the fold make of it", "বাক", commit.uncorrected)
    }

    @Test
    fun `a spelling the list holds is never a typo`() {
        val commit = engine().phoneticCommit("bn", "vabo")!!
        assertNull(commit.uncorrected)
        assertTrue(commit.output in listOf("ভাব", "ভাবো"))
    }

    @Test
    fun `a far key only offers`() {
        val engine = engine()
        val commit = engine.phoneticCommit("bn", "vaso")!!
        assertNull(commit.uncorrected)
        assertFalse(commit.output == "ভালো")
    }

    @Test
    fun `off for the language it stays on the strip behind the head`() {
        val engine = engine().apply { phoneticAutocorrect = false }
        val commit = engine.phoneticCommit("bn", "vako")!!
        assertNull(commit.uncorrected)
        assertEquals("বাক", commit.output)
        assertTrue("ভালো" in engine.strip("vako"))
    }

    @Test
    fun `dictionary words switched off leaves the literal in charge`() {
        val engine = engine().apply { phoneticSiblingsOff = setOf("bn") }
        assertNull(engine.phoneticCommit("bn", "vako")!!.uncorrected)
    }

    @Test
    fun `a word the user keeps typing stands`() {
        val lexicon = UserLexicon(null).apply { learnWord("vako", count = 3) }
        assertNull(engine(lexicon).phoneticCommit("bn", "vako")!!.uncorrected)
    }

    @Test
    fun `an English word stands`() {
        assertNull(engine().phoneticCommit("bn", "vakt")!!.uncorrected)
    }

    @Test
    fun `too short to say`() {
        assertNull(engine().phoneticCommit("bn", "vak")!!.uncorrected)
    }

    @Test
    fun `the verdict needs a clear winner and context can make one`() {
        val near = { word: String, context: Double ->
            PhoneticCorrection.Candidate(word, PhoneticCorrection.rank(1000, 0.105, context), 0.105, 5000)
        }
        val rival = PhoneticCorrection.rivalRank(0, 0.0)
        val tied = PhoneticCorrection.merged(listOf(near("ক", 0.0), near("খ", 0.0)))
        assertNull(PhoneticCorrection.leader(tied, rival))
        val told = PhoneticCorrection.merged(listOf(near("ক", 0.0), near("খ", 2.5)))
        assertEquals("খ", PhoneticCorrection.leader(told, rival)?.word)
    }
}
