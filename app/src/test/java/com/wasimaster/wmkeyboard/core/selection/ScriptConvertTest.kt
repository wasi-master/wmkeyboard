package com.wasimaster.wmkeyboard.core.selection

import com.wasimaster.wmkeyboard.core.transliteration.LipiScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Convert script's routing: when the chip is offered, what a selection is read as, and where it can go. */
class ScriptConvertTest {

    private val devanagari = "संस्कृतम्"
    private val iast = "saṃskṛtam"
    private val allowed = setOf(SelectionMacro.CONVERT_SCRIPT, SelectionMacro.COPY)

    private fun offered(text: String, ready: Boolean = true, languageId: String = "hi"): Boolean {
        val content = SelectionMacros.detectContent(text, DetectOptions(scripts = ready))
        val gates = MacroGates(scriptConvert = ready, languageId = languageId, content = content)
        return SelectionMacro.CONVERT_SCRIPT in SelectionMacros.offer(SelectionKind.TEXT, allowed, gates)
    }

    @Test
    fun detectContentReadsTheScriptOnlyWhenAsked() {
        assertEquals(LipiScheme.DEVANAGARI, SelectionMacros.detectContent(devanagari, DetectOptions(scripts = true)).lipiSource)
        assertEquals(LipiScheme.IAST, SelectionMacros.detectContent(iast, DetectOptions(scripts = true)).lipiSource)
        assertNull(SelectionMacros.detectContent(devanagari).lipiSource)
        assertNull(SelectionMacros.detectContent("saMskRtam", DetectOptions(scripts = true)).lipiSource)
    }

    @Test
    fun offeredOnIndicTextAndMarkedRomanisations() {
        assertTrue(offered(devanagari))
        assertTrue(offered(iast))
        assertTrue(offered("kR^iShNa"))
        assertTrue(offered("தமிழ்", languageId = "en"))
    }

    @Test
    fun notOfferedWithoutTheLibrary() {
        assertFalse(offered(devanagari, ready = false))
    }

    /** Plain letters could be English: only the Sanskrit keyboard, whose users type a romanisation, gets the chip for them. */
    @Test
    fun plainLettersOnlyOnTheSanskritKeyboard() {
        assertFalse(offered("saMskRtam", languageId = "hi"))
        assertFalse(offered("hello there", languageId = "en"))
        assertTrue(offered("saMskRtam", languageId = "sa"))
        assertFalse(offered("12 34", languageId = "sa"))
    }

    @Test
    fun onlyOnPlainText() {
        val content = SelectionMacros.detectContent(devanagari, DetectOptions(scripts = true))
        val gates = MacroGates(scriptConvert = true, content = content)
        assertFalse(SelectionMacro.CONVERT_SCRIPT in SelectionMacros.offer(SelectionKind.URL, allowed, gates))
    }

    @Test
    fun thePlainLetterSettingFallsBackToItrans() {
        assertEquals(LipiScheme.HARVARD_KYOTO, ScriptConvert.plainLetters("HarvardKyoto"))
        assertEquals(LipiScheme.ITRANS, ScriptConvert.plainLetters(""))
        // A script, or IAST (which no letter key types), is no answer for plain letters.
        assertEquals(LipiScheme.ITRANS, ScriptConvert.plainLetters("Devanagari"))
        assertEquals(LipiScheme.ITRANS, ScriptConvert.plainLetters("Iast"))
    }

    @Test
    fun choicesReadTheSourceAndLeaveItOut() {
        val (from, targets) = ScriptConvert.choices(devanagari, "Itrans", "ta")!!
        assertEquals(LipiScheme.DEVANAGARI, from)
        assertEquals(LipiScheme.TAMIL, targets.first())
        assertFalse(LipiScheme.DEVANAGARI in targets)
        val (plain, _) = ScriptConvert.choices("saMskRtam", "HarvardKyoto", "sa")!!
        assertEquals(LipiScheme.HARVARD_KYOTO, plain)
        assertNull(ScriptConvert.choices("!!", "Itrans", "sa"))
    }

    /** The JVM has no libwmlipi, so a pick converts nothing rather than pushing the text back unchanged. */
    @Test
    fun convertIsNullWithoutTheLibrary() {
        assertNull(ScriptConvert.convert(devanagari, "Itrans", "Iast"))
        assertNull(ScriptConvert.convert(devanagari, "Itrans", "NotAScheme"))
    }

    @Test
    fun aDoorThatNeedsTheLock() {
        assertTrue(SelectionMacro.CONVERT_SCRIPT.opensLadder)
        assertFalse(SelectionMacro.CONVERT_SCRIPT.directBootSafe)
        assertEquals(MacroCategory.CONVERT, SelectionMacro.CONVERT_SCRIPT.category)
        assertTrue(SelectionMacro.CONVERT_SCRIPT in SelectionMacros.defaultMacros)
    }
}
