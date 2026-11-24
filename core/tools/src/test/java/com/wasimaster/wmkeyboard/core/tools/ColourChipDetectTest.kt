package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The colour chip recognises a code just typed; WM Chroma answers it later. */
class ColourChipDetectTest {
    private val on = SmartSuggest.Context(colourChips = true)

    private fun code(text: String) = SmartSuggest.detect(text, on)?.takeIf { it.kind == SmartSuggest.Kind.COLOUR }?.query

    @Test fun hexAtTheEndIsAColour() {
        assertEquals("#3b82f6", code("the button is #3b82f6"))
        assertEquals("#3B82F6CC", code("fill: #3B82F6CC"))
        assertEquals("#fff", code("#fff"))
        assertEquals("#aabbcc", code("try #aabbcc"))
    }

    @Test fun theHitReplacesTheCodeAndOpensChroma() {
        val hit = SmartSuggest.detect("color: #3b82f6", on)!!
        assertEquals(SmartSuggest.Kind.COLOUR, hit.kind)
        assertEquals(7, hit.replaceSpan)
        assertEquals(ToolbarTool.WM_CHROMA, hit.tool)
        assertNull(hit.result)
    }

    @Test fun functionsAreColours() {
        assertEquals("rgb(59, 130, 246)", code("set it to rgb(59, 130, 246)"))
        assertEquals("oklch(0.62 0.19 260)", code("oklch(0.62 0.19 260)"))
        assertEquals("HSL(217 91% 60%)", code("HSL(217 91% 60%)"))
    }

    @Test fun hashtagsAreNotColours() {
        assertNull(code("#bad"))
        assertNull(code("so #cafe"))
        assertNull(code("#facade"))
        assertNull(code("#deface"))
    }

    @Test fun onlyWhatWasJustTyped() {
        assertNull(code("#3b82f6 is blue"))
        assertNull(code("word#3b82f6"))
        assertNull(code("#3b82f"))
        assertNull(code("#3b82f6a"))
    }

    @Test fun offMeansNoChip() {
        assertNull(SmartSuggest.detect("#3b82f6", SmartSuggest.Context(colourChips = false))?.takeIf { it.kind == SmartSuggest.Kind.COLOUR })
    }
}
