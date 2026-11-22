package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Theme font axes and motions: roles, transport, and the rule sheet's `font-variation`. */
class FontAxesSpecTest {

    @Test
    fun `roles follow the keys until given their own`() {
        val spec = FontAxesSpec(keys = mapOf("wght" to 650f))
        assertEquals(mapOf("wght" to 650f), spec.role(FontRole.HINTS))
        assertFalse(spec.ownsRole(FontRole.HINTS))
        val light = spec.withRole(FontRole.HINTS, mapOf("wght" to 300f))
        assertEquals(mapOf("wght" to 300f), light.role(FontRole.HINTS))
        assertEquals(mapOf("wght" to 650f), light.role(FontRole.STRIP))
        assertTrue(light.withRole(FontRole.HINTS, null).role(FontRole.HINTS) == spec.keys)
        assertTrue(FontAxesSpec().isEmpty)
    }

    @Test
    fun `axes and motions survive the theme codec`() {
        val theme = ThemeSpec(
            id = "t",
            name = "T",
            fontAxes = FontAxesSpec(keys = mapOf("wght" to 500f), strip = mapOf("wdth" to 85f)),
            scriptFontAxes = mapOf("BENGALI" to FontAxesSpec(keys = mapOf("wght" to 600f))),
            fontMotions = listOf(FontMotionSpec(trigger = "TOUCH", axes = mapOf("wght" to 800f))),
            keyOverrides = mapOf("ENTER" to KeyOverride(fontAxes = mapOf("wght" to 900f))),
        )
        val back = ThemeCodec.decodeList(ThemeCodec.encodeList(listOf(theme))).single()
        assertEquals(theme.fontAxes, back.fontAxes)
        assertEquals(theme.scriptFontAxes, back.scriptFontAxes)
        assertEquals(theme.fontMotions, back.fontMotions)
        assertEquals(mapOf("wght" to 900f), back.keyOverrides.getValue("ENTER").fontAxes)
    }

    @Test
    fun `a key style with only axes is not empty`() {
        assertFalse(KeyOverride(fontAxes = mapOf("wght" to 700f)).isEmpty)
        assertTrue(KeyOverride(fontAxes = emptyMap()).isEmpty)
    }

    @Test
    fun `an unknown trigger costs the motion, not the theme`() {
        assertNull(fontMotionTriggerOrNull("WIGGLE"))
        assertEquals(FontMotionTrigger.GLIDE, fontMotionTriggerOrNull("GLIDE"))
        assertEquals(FontMotionTarget.LETTERS, fontMotionTargetOrDefault(null))
        val theme = ThemeSpec(id = "t", name = "T", fontMotions = listOf(FontMotionSpec("WIGGLE")))
        val back = ThemeCodec.decodeList(ThemeCodec.encodeList(listOf(theme))).single()
        assertEquals("t", back.id)
        assertEquals("WIGGLE", back.fontMotions.single().trigger)
    }

    @Test
    fun `the css form parses quoted tags and skips the rest`() {
        assertEquals(mapOf("wght" to 700f, "wdth" to 80f), parseAxesCss("'wght' 700, \"wdth\" 80"))
        assertEquals(mapOf("GRAD" to -50f), parseAxesCss("'GRAD' -50, wght 700, 'toolong' 1, 'opsz' x"))
        assertTrue(parseAxesCss("").isEmpty())
    }

    @Test
    fun `font-variation lands on a key and on the board`() {
        val base = ThemeSpec(id = "t", name = "T")
        val result = KeyRuleSheet.compile(
            base.copy(
                ruleSheet = """
                {
                  "key[code=10]": { "font-variation": "'wght' 800" },
                  "key[class=`letter`]": { "font-variation": "'wdth' 90" },
                  "key-hint[class=`letter`]": { "font-variation": "'wght' 300" }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(mapOf("wght" to 800f), result.theme.keyOverrides.getValue("ENTER").fontAxes)
        assertEquals(mapOf("wdth" to 90f), result.theme.fontAxes?.keys)
        assertEquals(300f, result.theme.fontAxes?.hints?.get("wght"))
        assertTrue(result.issues.isEmpty())
    }
}
