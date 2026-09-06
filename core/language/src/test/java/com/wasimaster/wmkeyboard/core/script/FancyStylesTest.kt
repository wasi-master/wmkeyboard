package com.wasimaster.wmkeyboard.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Fancy Text style table. The glyphs were lifted from the retired
 * per-style layouts, so the spot checks below pin a few of their exact
 * values — including the awkward ones: astral pairs, combining marks, the
 * capital-less small caps, and superscript's plain-q fallback.
 */
class FancyStylesTest {

    @Test
    fun `normal plus the 22 styles are present, in the shipped order, with a default`() {
        assertEquals(23, FancyStyles.all.size)
        assertEquals(FancyStyles.NORMAL_ID, FancyStyles.all.first().id)
        assertEquals("bold", FancyStyles.all[1].id)
        assertEquals("underline", FancyStyles.all.last().id)
        assertNotNull(FancyStyles.byId(FancyStyles.DEFAULT_ID))
        assertEquals(FancyStyles.all.size, FancyStyles.all.distinctBy { it.id }.size)
    }

    @Test
    fun `normal leaves text exactly as typed`() {
        val normal = FancyStyles.byId(FancyStyles.NORMAL_ID)!!
        assertEquals("Hello World! 123", FancyStyles.transform("Hello World! 123", normal))
    }

    @Test
    fun `every style maps all 26 letters in both cases`() {
        for (style in FancyStyles.all) {
            assertEquals(style.id, 26, style.lower.size)
            assertEquals(style.id, 26, style.upper.size)
            for (c in 'a'..'z') assertTrue(style.id, style.lower.getValue(c).isNotEmpty())
            for (c in 'A'..'Z') assertTrue(style.id, style.upper.getValue(c).isNotEmpty())
        }
    }

    @Test
    fun `bold maps onto the mathematical bold block`() {
        val bold = FancyStyles.byId("bold")!!
        assertEquals("𝐚", bold.lower.getValue('a'))
        assertEquals("𝐀", bold.upper.getValue('A'))
        assertEquals("𝐇𝐞𝐥𝐥𝐨 𝐖𝐨𝐫𝐥𝐝!", FancyStyles.transform("Hello World!", bold))
    }

    @Test
    fun `small caps has no capitals of its own`() {
        val smallCaps = FancyStyles.byId("small_caps")!!
        for (c in 'a'..'z') {
            assertEquals(
                smallCaps.lower.getValue(c),
                smallCaps.upper.getValue(c.uppercaseChar()),
            )
        }
    }

    @Test
    fun `superscript falls back to the plain letter where no form exists`() {
        val superscript = FancyStyles.byId("superscript")!!
        assertEquals("q", superscript.lower.getValue('q'))
    }

    @Test
    fun `the combining styles append their mark to the plain letter`() {
        val underline = FancyStyles.byId("underline")!!
        val styled = underline.lower.getValue('a')
        assertEquals(2, styled.length)
        assertEquals('a', styled[0])
        assertEquals('̲', styled[1])
    }

    @Test
    fun `non-letters pass through the transform untouched`() {
        val fraktur = FancyStyles.byId("fraktur")!!
        assertEquals("123 ,.!? ৳", FancyStyles.transform("123 ,.!? ৳", fraktur))
        assertEquals("", FancyStyles.transform("", fraktur))
    }

    @Test
    fun `samples are written in their own style`() {
        for (style in FancyStyles.all) {
            assertEquals(
                style.id,
                FancyStyles.transform(style.name, style),
                style.sample,
            )
        }
    }
}
