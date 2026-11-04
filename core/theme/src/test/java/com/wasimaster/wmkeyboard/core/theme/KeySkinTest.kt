package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading key art: SVG path data, the nine-patch fit and the colour references. */
class KeySkinTest {

    private fun last(commands: List<PathCommand>): Pair<Float, Float> = when (val c = commands.last { it != PathCommand.Close }) {
        is PathCommand.MoveTo -> c.x to c.y
        is PathCommand.LineTo -> c.x to c.y
        is PathCommand.CubicTo -> c.x to c.y
        PathCommand.Close -> error("unreachable")
    }

    @Test
    fun `absolute and relative lines come out absolute`() {
        val path = parseSvgPath("M10 10 L20 10 l0 5 H5 v-5 Z")
        assertEquals(
            listOf(
                PathCommand.MoveTo(10f, 10f),
                PathCommand.LineTo(20f, 10f),
                PathCommand.LineTo(20f, 15f),
                PathCommand.LineTo(5f, 15f),
                PathCommand.LineTo(5f, 10f),
                PathCommand.Close,
            ),
            path,
        )
    }

    @Test
    fun `numbers after a move are lines, and compact numbers split`() {
        // "1.5.5" is 1.5 then .5; "-2-3" is -2 then -3; "1e1" is ten.
        val path = parseSvgPath("M0 0 1.5.5-2-3 1e1,0")
        assertEquals(PathCommand.LineTo(1.5f, 0.5f), path[1])
        assertEquals(PathCommand.LineTo(-2f, -3f), path[2])
        assertEquals(PathCommand.LineTo(10f, 0f), path[3])
    }

    @Test
    fun `curves become cubics that end where the curve ends`() {
        val quad = parseSvgPath("M0 0 Q10 10 20 0 T40 0")
        assertTrue(quad.drop(1).all { it is PathCommand.CubicTo })
        assertEquals(40f to 0f, last(quad))
        val smooth = parseSvgPath("M0 0 C0 10 10 10 10 0 s10 -10 10 0")
        assertEquals(20f to 0f, last(smooth))
        val arc = parseSvgPath("M0 50 A50 50 0 0 1 100 50")
        val (x, y) = last(arc)
        assertEquals(100f, x, 0.01f)
        assertEquals(50f, y, 0.01f)
        assertTrue(arc.size in 2..5)
    }

    @Test
    fun `arc flags may run straight into the next number`() {
        val arc = parseSvgPath("M0 0a5 5 0 105 5")
        val (x, y) = last(arc)
        assertEquals(5f, x, 0.01f)
        assertEquals(5f, y, 0.01f)
    }

    @Test
    fun `broken data ends the path instead of throwing`() {
        assertEquals(listOf(PathCommand.MoveTo(1f, 2f)), parseSvgPath("M1 2 L3"))
        assertTrue(parseSvgPath("").isEmpty())
        assertTrue(parseSvgPath("hello").isEmpty())
        // Numbers after Z are not a repeat; reading stops.
        assertEquals(3, parseSvgPath("M0 0 L1 1 Z 5 5").size)
    }

    @Test
    fun `the stretch band takes up the width and the corners keep theirs`() {
        val slot = SkinSlot(size = listOf(100f, 100f), stretchX = listOf(20f, 80f), stretchY = listOf(20f, 80f))
        // A spacebar: four times as wide as it is tall.
        val fit = SkinFit(slot, width = 400f, height = 100f)
        assertEquals(1f, fit.unit)
        assertEquals(10f, fit.x(10f))
        assertEquals(395f, fit.x(95f))
        assertEquals(200f, fit.x(50f))
        assertEquals(50f, fit.y(50f))
    }

    @Test
    fun `without a stretch band the drawing is centred at its own proportions`() {
        val fit = SkinFit(SkinSlot(size = listOf(100f, 50f)), width = 300f, height = 100f)
        assertEquals(2f, fit.unit)
        assertEquals(50f, fit.x(0f))
        assertEquals(250f, fit.x(100f))
    }

    @Test
    fun `a key too small for the fixed parts squeezes the whole drawing`() {
        val slot = SkinSlot(size = listOf(100f, 100f), stretchX = listOf(45f, 55f))
        val fit = SkinFit(slot, width = 50f, height = 100f)
        assertEquals(50f, fit.x(100f))
        assertEquals(25f, fit.x(50f))
    }

    @Test
    fun `colour references resolve against roles with their steps`() {
        val roles = mapOf("face" to 0xFF808080, "accent" to 0xFFFF0000)
        fun r(ref: String) = SkinColorRef.parse(ref).resolve { roles[it] }
        assertEquals(0xFF808080, r("face"))
        assertEquals(0xFFFFFFFF, r("face:lighten:1"))
        assertEquals(0xFF000000, r("face:darken:1"))
        assertEquals(0x80FF0000, r("accent:alpha:0.5"))
        assertEquals(0x00FF0000L, r("accent:alpha:0"))
        assertEquals(0xFF112233, r("#112233"))
        assertEquals(0x80112233, r("#80112233"))
        assertEquals(0xFF000000, r("#FFFFFF:darken:1"))
        // Ink moves towards whichever end reads: black on a light face.
        assertEquals(0xFF000000, SkinColorRef.parse("face:ink:1").resolve { 0xFFF0F0F0 })
        assertNull(r("nonsense"))
    }

    @Test
    fun `a key with no slot of its own falls back to its class, then the letter`() {
        val letter = SkinSlot(size = listOf(1f, 1f))
        val special = SkinSlot(size = listOf(2f, 2f))
        val skin = KeySkinSpec(slots = mapOf(SkinSlotName.LETTER to letter, SkinSlotName.SPECIAL to special))
        assertEquals(special, skin.slotFor(SkinSlotName.SHIFT))
        assertEquals(letter, skin.slotFor(SkinSlotName.PUNCTUATION))
        assertEquals(letter, skin.slotFor(SkinSlotName.SPACE))
        assertNull(KeySkinSpec().slotFor(SkinSlotName.LETTER))
    }

    @Test
    fun `an oversized slot is cut down to the limits`() {
        val layers = List(MAX_SKIN_LAYERS + 10) { SkinLayer(d = "M0 0") } + SkinLayer(d = "M" + "0".repeat(MAX_SKIN_PATH_CHARS))
        val skin = KeySkinSpec(slots = mapOf(SkinSlotName.LETTER to SkinSlot(layers = layers))).bounded()
        assertEquals(MAX_SKIN_LAYERS, skin.slots.getValue(SkinSlotName.LETTER).layers.size)
    }
}
