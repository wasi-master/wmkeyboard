package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.fonts.FontAxis
import com.wasimaster.wmkeyboard.core.fonts.FontVariations
import com.wasimaster.wmkeyboard.core.theme.FontMotionSpec
import com.wasimaster.wmkeyboard.core.theme.FontMotionTrigger
import com.wasimaster.wmkeyboard.core.theme.KeyLightingPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of the font motion: where a label's axes draw for a set of levels. */
class FontMotionLevelsTest {

    private val font = FontVariations(
        axes = listOf(
            FontAxis("wght", "Weight", 100f, 400f, 900f),
            FontAxis("wdth", "Width", 50f, 100f, 150f),
        ),
    )

    @Test
    fun `a level moves the axis that far toward its peak`() {
        // An untuned weight rests at the labels' Medium, 500.
        val out = morphedAxes(emptyMap(), font, listOf(mapOf("wght" to 800f) to 0.5f))
        assertEquals(650f, out.getValue("wght"), 0.001f)
        assertTrue("wdth" !in out)
    }

    @Test
    fun `rest is where it moves from`() {
        val out = morphedAxes(mapOf("wght" to 300f), font, listOf(mapOf("wght" to 700f) to 1f))
        assertEquals(700f, out.getValue("wght"), 0.001f)
    }

    @Test
    fun `two motions on one axis keep the furthest move`() {
        val out = morphedAxes(
            emptyMap(),
            font,
            listOf(mapOf("wght" to 900f) to 0.2f, mapOf("wght" to 100f) to 1f),
        )
        // From 500: +80 against -400, so the second wins.
        assertEquals(100f, out.getValue("wght"), 0.001f)
    }

    @Test
    fun `an overshoot is held to the font`() {
        val out = morphedAxes(emptyMap(), font, listOf(mapOf("wght" to 900f) to 1.4f))
        assertEquals(900f, out.getValue("wght"), 0.001f)
    }

    @Test
    fun `nothing moving returns rest itself`() {
        val rest = mapOf("wght" to 500f)
        assertTrue(morphedAxes(rest, font, listOf(mapOf("wght" to 900f) to 0f)) === rest)
    }

    @Test
    fun `quantizing snaps to steps across the range`() {
        val q = quantizeAxes(mapOf("wght" to 432f, "XXXX" to 3f), font, steps = 8)
        // 800 / 8 = 100 a step, from 100.
        assertEquals(400f, q.getValue("wght"), 0.001f)
        assertEquals(3f, q.getValue("XXXX"), 0.001f)
    }

    @Test
    fun `the swell rises under the finger and settles after the lift`() {
        val down = swellEnvelope(300, STILL_DOWN, 500, 0f)
        assertTrue(down > 0.95f)
        val justLifted = swellEnvelope(310, 300, 500, 0f)
        assertTrue(justLifted > 0.8f)
        assertEquals(0f, swellEnvelope(300 + 2000, 300, 500, 0f), 0.001f)
    }

    @Test
    fun `the shared lighting levels match what the lighting drew`() {
        assertEquals(1f, idleLightingLevel(KeyLightingPattern.STEADY, 3f, 0.4f, 7), 0f)
        val breathe = idleLightingLevel(KeyLightingPattern.BREATHE, 0f, 0f, 0)
        assertEquals(0.625f, breathe, 0.001f)
        assertEquals(0f, idleLightingLevel(KeyLightingPattern.KEY, 1f, 0.5f, 0), 0f)
        val cell = Rect(0f, 0f, 100f, 100f)
        assertEquals(1f, pressLightingLevel(KeyLightingPattern.KEY, 1f, cell, Offset(50f, 50f), 0f), 0.001f)
        assertEquals(0f, pressLightingLevel(KeyLightingPattern.KEY, 1f, cell, Offset(150f, 50f), 0f), 0f)
    }

    @Test
    fun `resolving drops motions that cannot move anything`() {
        assertNull(resolveFontMotion(listOf(FontMotionSpec("WIGGLE", axes = mapOf("wght" to 700f)))))
        assertNull(resolveFontMotion(listOf(FontMotionSpec(FontMotionTrigger.TOUCH.name))))
        val m = resolveFontMotion(
            listOf(
                FontMotionSpec(FontMotionTrigger.TOUCH.name, axes = mapOf("wght" to 700f), radiusDp = 9999f),
                FontMotionSpec(FontMotionTrigger.PATTERN.name, pattern = "RIPPLE", axes = mapOf("wdth" to 80f)),
            ),
        )!!
        assertEquals(240f, m.items[0].radiusDp, 0f)
        assertTrue(m.followsFingers)
        assertTrue(m.readsPresses)
    }

    @Test
    fun `a motion that only grows the labels is kept, one that moves nothing is dropped`() {
        val grows = resolveFontMotion(listOf(FontMotionSpec(trigger = FontMotionTrigger.TOUCH.name, scale = 1.3f)))!!
        assertEquals(1.3f, grows.items.single().scale, 0f)
        assertTrue(grows.scales)
        assertNull(resolveFontMotion(listOf(FontMotionSpec(trigger = FontMotionTrigger.TOUCH.name))))
        val wild = resolveFontMotion(listOf(FontMotionSpec(trigger = FontMotionTrigger.TOUCH.name, scale = 9f)))!!
        assertEquals(2f, wild.items.single().scale, 0f)
    }
}
