package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.prediction.PunctuationModel
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import com.wasimaster.wmkeyboard.ime.ui.withSmartPunctuation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SmartPunctuationTest {

    @Test
    fun `the three end marks are judged with the comma folded away`() {
        // The comma leads, but the key is not allowed to show it: the
        // likeliest of the three end marks is what goes on the key.
        assertEquals(PunctuationModel.QUESTION, PunctuationModel.markFor(floatArrayOf(0.2f, 0.3f, 0.1f, 0.4f), allowComma = false))
        assertEquals(PunctuationModel.COMMA, PunctuationModel.markFor(floatArrayOf(0.2f, 0.3f, 0.1f, 0.4f), allowComma = true))
        assertEquals(PunctuationModel.PERIOD, PunctuationModel.markFor(floatArrayOf(0.5f, 0.3f, 0.2f, 0.0f), allowComma = true))
        assertNull(PunctuationModel.markFor(FloatArray(0), allowComma = false))
    }

    @Test
    fun `glyphs follow the script`() {
        val latin = ScriptRegistry[ScriptId.LATIN]
        assertEquals(".", SmartPunctuation.glyph(PunctuationModel.PERIOD, latin))
        assertEquals("?", SmartPunctuation.glyph(PunctuationModel.QUESTION, latin))
        assertEquals("!", SmartPunctuation.glyph(PunctuationModel.EXCLAIM, latin))
        assertEquals(",", SmartPunctuation.glyph(PunctuationModel.COMMA, latin))
        val bengali = ScriptRegistry[ScriptId.BENGALI]
        assertEquals("।", SmartPunctuation.glyph(PunctuationModel.PERIOD, bengali))
        assertEquals("?", SmartPunctuation.glyph(PunctuationModel.QUESTION, bengali))
        val arabic = ScriptRegistry[ScriptId.ARABIC]
        assertEquals("؟", SmartPunctuation.glyph(PunctuationModel.QUESTION, arabic))
        assertEquals("،", SmartPunctuation.glyph(PunctuationModel.COMMA, arabic))
    }

    @Test
    fun `the period key shows the mark and keeps its full stop a hold away`() {
        val period = Key(".", longPress = listOf(",", "?", "!", ":"))
        val asked = period.withSmartPunctuation("?")
        assertEquals("?", asked.label)
        assertNull(asked.output)
        assertEquals("?", asked.output ?: asked.label)
        assertEquals(listOf(".", ",", "!", ":"), asked.longPress)
        // A key already typing the mark, or typing a letter, is left alone.
        assertSame(period, period.withSmartPunctuation("."))
        val letter = Key("m", longPress = listOf("?"))
        assertSame(letter, letter.withSmartPunctuation("?"))
        // Not a text key either.
        val enter = Key("enter", action = KeyAction.Enter)
        assertSame(enter, enter.withSmartPunctuation("?"))
    }

    @Test
    fun `a danda key takes the mark the same way`() {
        val danda = Key("।", longPress = listOf(".", "?"))
        val asked = danda.withSmartPunctuation("?")
        assertEquals("?", asked.label)
        assertEquals(listOf("।", "."), asked.longPress)
    }
}
