package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.input.composer.JapaneseComposer
import com.wasimaster.wmkeyboard.core.input.composer.PinyinComposer
import com.wasimaster.wmkeyboard.core.input.composer.composerFor
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.composerType
import com.wasimaster.wmkeyboard.core.layout.script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which layouts may have the word a caret landed on re-armed as the composing
 * region, and what that word is. Built from the shipped layout specs the way the
 * service builds the live composer, so a layout that changes script or composer
 * is checked here as it is actually configured rather than against a hand-made
 * stand-in.
 */
class ComposingResumeTest {

    private fun composerOf(spec: LayoutSpec) = composerFor(spec.script(), spec.composerType())

    private fun resumable(spec: LayoutSpec, hasWordSources: Boolean = true) =
        composingResumable(composerOf(spec), hasWordSources)

    @Test
    fun `english resumes`() {
        assertTrue(resumable(BuiltInLayouts.QWERTY))
    }

    @Test
    fun `other latin languages resume too`() {
        // The gate used to read `language.isEnglish`, inherited from a glide
        // flag; these complete from the same sources English does.
        for (spec in listOf(BuiltInLayouts.FRENCH, BuiltInLayouts.GERMAN, BuiltInLayouts.SPANISH)) {
            assertTrue(spec.id, resumable(spec))
        }
    }

    @Test
    fun `non-latin scripts that type themselves resume`() {
        // Cyrillic, Greek, Arabic and Hebrew all compose their own script, so
        // the word read back out of the field is exactly what the buffer holds.
        for (spec in listOf(
            BuiltInLayouts.RUSSIAN,
            BuiltInLayouts.GREEK,
            BuiltInLayouts.ARABIC,
            BuiltInLayouts.HEBREW,
        )) {
            assertTrue(spec.id, resumable(spec))
        }
    }

    @Test
    fun `a language with nothing to complete from does not resume`() {
        // No bundled list, no imported one, nothing learned: the underline
        // would sit there offering nothing.
        assertFalse(resumable(BuiltInLayouts.FRENCH, hasWordSources = false))
        assertFalse(resumable(BuiltInLayouts.QWERTY, hasWordSources = false))
    }

    @Test
    fun `avro does not resume`() {
        // Its buffer is the roman source of the Bengali in the field, and the
        // Bengali cannot be reversed back into it.
        val avro = composerOf(BuiltInLayouts.AVRO)
        assertTrue(avro.isTransliterating)
        assertFalse(composingResumable(avro, hasWordSources = true))
    }

    @Test
    fun `fixed bengali resumes`() {
        // Probhat and Jatiya type Bengali into the field and hold Bengali in the
        // buffer, which is the whole test. They still commit ordinary keystrokes
        // straight through — the resumed word is the only thing they compose.
        for (spec in listOf(BuiltInLayouts.PROBHAT, BuiltInLayouts.JATIYA)) {
            val composer = composerOf(spec)
            assertTrue(spec.id, composer.isClusterShaping)
            assertTrue(spec.id, composingResumable(composer, hasWordSources = true))
        }
    }

    @Test
    fun `other fixed indic layouts resume too`() {
        assertTrue(resumable(BuiltInLayouts.HINDI))
    }

    @Test
    fun `a fixed indic layout with no word list still does not resume`() {
        assertFalse(resumable(BuiltInLayouts.PROBHAT, hasWordSources = false))
    }

    @Test
    fun `hangul does not resume`() {
        assertFalse(resumable(BuiltInLayouts.KOREAN))
    }

    @Test
    fun `a conversion ime does not resume`() {
        // A reading with a whole choice of outputs behind it can never be read
        // back out of the field. These set isTransliterating as well as
        // isConversion, so the one term in the gate covers them — pinned here
        // rather than discovered if one of them ever stops.
        for (composer in listOf(PinyinComposer, JapaneseComposer)) {
            assertTrue(composer.isConversion)
            assertFalse(composingResumable(composer, hasWordSources = true))
        }
    }

    // --- what counts as part of a word ------------------------------------

    @Test
    fun `a combining mark is part of the composing word`() {
        // বাংলা is ব া ং ল া: two of its five characters are combining marks and
        // it *ends* in one, so Char.isLetter() says the caret parked after it is
        // not at a word end at all.
        assertTrue(isComposingWordChar('া'))     // Bengali vowel sign AA
        assertTrue(isComposingWordChar('্'))     // Bengali hasant (virama)
        assertTrue(isComposingWordChar('ং'))     // Bengali anusvara
        assertTrue(isComposingWordChar('ि'))     // Devanagari matra I
        assertTrue(isComposingWordChar('่'))     // Thai tone mark
        assertTrue(isComposingWordChar('\'')) // English contractions
        assertTrue(isComposingWordChar('a'))
    }

    @Test
    fun `separators and digits are not part of the composing word`() {
        // A digit can *enter* the buffer as a number-row slip, but a span read
        // back out of the field must not swallow the "2" of "level 2".
        assertFalse(isComposingWordChar('2'))
        assertFalse(isComposingWordChar(' '))
        assertFalse(isComposingWordChar(','))
        assertFalse(isComposingWordChar('।')) // Devanagari danda
    }

    @Test
    fun `an apostrophe ahead of the caret does not block a resume`() {
        // Deliberately asymmetric with isComposingWordChar: "don|'t" keeps
        // resuming "don" exactly as it did before combining marks were counted.
        assertFalse(continuesWordAhead('\''))
        assertTrue(continuesWordAhead('া'))
        assertTrue(continuesWordAhead('2'))
        assertTrue(continuesWordAhead('a'))
        assertFalse(continuesWordAhead(' '))
    }

    // --- the word a caret is parked at the end of --------------------------

    @Test
    fun `a bengali word resumes whole, not from its last consonant`() {
        // The prize: Probhat/Jatiya users tapping into a word get its
        // completions. Under a letters-only span this returned "ল" — four
        // fifths of the word left outside the region, and a lookup for a bare
        // consonant behind it.
        val word = resumableWordAt("আমি বাংলা", "")
        assertEquals("বাংলা", word)
        assertEquals(5, word?.length)
    }

    @Test
    fun `a word ending in a matra or a tone mark resumes whole`() {
        assertEquals("किया", resumableWordAt("उसने किया", null))
        assertEquals("ก่อน", resumableWordAt("ก่อน", null))
        // A conjunct inside the word is no boundary either.
        assertEquals("কিন্তু", resumableWordAt("কিন্তু", null))
    }

    @Test
    fun `a caret in front of a vowel sign is mid-word and resumes nothing`() {
        // বাংল|া — a word char on both sides of the caret. Arming বাংল here
        // would put the region over four fifths of a word and strand its vowel
        // sign outside it.
        assertNull(resumableWordAt("বাংল", "া"))
        assertNull(resumableWordAt("कि", "या"))
    }

    @Test
    fun `latin resumes exactly as it did`() {
        assertEquals("hello", resumableWordAt("say hello", " world"))
        assertEquals("hello", resumableWordAt("say hello", null))
        assertEquals("hello", resumableWordAt("say hello", ""))
        // Mid-word, and after a separator: nothing to resume either way.
        assertNull(resumableWordAt("hel", "lo"))
        assertNull(resumableWordAt("hello ", ""))
        assertNull(resumableWordAt("hello,", ""))
        // A trailing digit is not a word to complete.
        assertNull(resumableWordAt("level 2", ""))
        assertNull(resumableWordAt("", ""))
        assertNull(resumableWordAt(null, null))
    }

    @Test
    fun `an apostrophe belongs to the word it sits in`() {
        assertEquals("don't", resumableWordAt("i don't", ""))
        assertEquals("don", resumableWordAt("i don", "'t"))
    }

    // --- the word a caret is sitting inside --------------------------------

    @Test
    fun `a caret in the middle of a word takes the whole word, split`() {
        assertEquals("hel" to "lo", caretWordAt("say hel", "lo world"))
        assertEquals("h" to "ello", caretWordAt("say h", "ello"))
    }

    @Test
    fun `a caret right in front of a word takes it with an empty head`() {
        // "say |hello" — the caret touches the word without a letter behind it.
        assertEquals("" to "hello", caretWordAt("say ", "hello"))
        assertEquals("" to "hello", caretWordAt(null, "hello"))
    }

    @Test
    fun `a caret at a word end is left to the resume path`() {
        assertNull(caretWordAt("say hello", ""))
        assertNull(caretWordAt("say hello", null))
        assertNull(caretWordAt("say hello", " world"))
    }

    @Test
    fun `a caret touching nothing takes nothing`() {
        assertNull(caretWordAt("say ", " hello"))
        assertNull(caretWordAt("", ""))
        assertNull(caretWordAt(null, null))
        // A digit is not a word to correct, on either side of the caret.
        assertNull(caretWordAt("level ", "2"))
    }

    @Test
    fun `the tail stops where the word does`() {
        assertEquals("lev" to "el", caretWordAt("lev", "el 2"))
        assertEquals("hel" to "lo", caretWordAt("hel", "lo, there"))
    }

    @Test
    fun `a caret in front of a vowel sign takes the word the resume refused`() {
        // The mirror of the resume test above: বাংল|া resumes nothing, but the
        // strip still answers about বাংলা.
        assertEquals("বাংল" to "া", caretWordAt("আমি বাংল", "া"))
        assertEquals("कि" to "या", caretWordAt("उसने कि", "या"))
    }

    // --- backspacing the resumed buffer ------------------------------------

    @Test
    fun `backspacing a resumed bengali buffer takes a whole conjunct`() {
        // The composing-buffer branch of deleteFromField used to shed one
        // UTF-16 unit unconditionally. On কিন্ত that leaves কিন্ — a base gone
        // and its hasant dangling — where the field's own backspace would have
        // taken ন্ত off as one unit. The buffer is the field's text, so it has
        // to answer the same way; the branch asks the composer for this length.
        val probhat = composerOf(BuiltInLayouts.PROBHAT)
        assertEquals(3, probhat.deleteLength("কিন্ত"))
        // A consonant and the kar hanging off it are one visual unit too.
        assertEquals(2, probhat.deleteLength("বাংলা"))
    }
}
