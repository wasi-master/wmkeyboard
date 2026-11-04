package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a Clink theme. The fixtures are hand-written in the shape of the
 * files in `anti-ltd/clink-themes`, cut down to the fields under test.
 */
class ClinkThemeTest {

    private fun c(r: Double, g: Double, b: Double, a: Double = 1.0) =
        """{"r": $r, "g": $g, "b": $b, "a": $a}"""

    private fun look(
        dark: Boolean,
        extra: String = "",
        material: String = "solid",
        keyFill: String = c(0.2, 0.2, 0.2),
    ) = """
        "material": "$material", "isDark": $dark,
        "background": ${if (dark) c(0.0, 0.0, 0.0) else c(1.0, 1.0, 1.0)},
        "keyFill": $keyFill, "keyText": ${c(1.0, 1.0, 1.0)},
        "specialKeyFill": ${c(0.4, 0.4, 0.4)}, "specialKeyText": ${c(0.0, 0.0, 1.0)},
        "accent": ${c(1.0, 0.0, 0.0)} $extra
    """.trimIndent()

    private fun converted(text: String): ClinkThemeResult.Converted {
        val result = ClinkTheme.read(text)
        assertTrue("expected a theme, got $result", result is ClinkThemeResult.Converted)
        return result as ClinkThemeResult.Converted
    }

    @Test
    fun `the colours land on the matching surfaces`() {
        val theme = converted("""{"id": "t", "name": "Test", ${look(dark = true)}}""").theme
        assertEquals("Test", theme.name)
        assertTrue(theme.dark)
        assertEquals(0xFF000000, theme.boardBackground)
        assertEquals(0xFF333333, theme.keyBackground)
        assertEquals(0xFFFFFFFF, theme.keyText)
        assertEquals(0xFF666666, theme.modifierKeyBackground)
        assertEquals(0xFF0000FF, theme.modifierKeyText)
        // Enter is a special key until a group paints it.
        assertEquals(0xFF666666, theme.enterKeyBackground)
        assertEquals(0xFFFF0000, theme.accent)
    }

    @Test
    fun `alpha is kept`() {
        val text = """{"name": "Glass", ${look(true, keyFill = c(1.0, 1.0, 1.0, 0.12))}}"""
        assertEquals(0x1FFFFFFFL, converted(text).theme.keyBackground)
    }

    @Test
    fun `paired variants become a dark and a light look`() {
        val text = """{"id": "pair", "name": "Pair", ${look(false)},
            "pairedVariants": [{"name": "Pair", ${look(false)}}, {"name": "Pair", ${look(true)}}]}"""
        val theme = converted(text).theme
        assertEquals("Pair", theme.familyName)
        assertTrue(theme.dark)
        assertEquals("Pair dark", theme.name)
        assertEquals(1, theme.variants.size)
        assertFalse(theme.variants.single().dark)
        assertEquals("Pair light", theme.variants.single().name)
    }

    @Test
    fun `group paint sets enter, letters and single keys`() {
        val groups = """, "groupPaint": {
            "return": {"fill": ${c(0.0, 1.0, 0.0)}, "text": ${c(0.0, 0.0, 0.0)}},
            "letters": {"pressed": ${c(0.5, 0.5, 0.5)}, "cornerRadius": 4},
            "space": {"fill": ${c(1.0, 1.0, 0.0)}},
            "numbers": {"activeText": ${c(1.0, 1.0, 1.0)}}
        }, "keyPaint": {"c:Q": {"fill": ${c(1.0, 0.0, 1.0)}}, "f:planeToggle": {"berryOppositeEdge": "oval"}}"""
        val theme = converted("""{"name": "G", ${look(true, groups)}}""").theme
        assertEquals(0xFF00FF00, theme.enterKeyBackground)
        assertEquals(0xFF000000, theme.enterKeyText)
        assertEquals(0xFF808080, theme.pressedKeyBackground)
        assertEquals(4, theme.keyCornerRadiusDp)
        assertEquals(0xFFFFFF00, theme.keyOverrides["SPACE"]?.background)
        assertEquals(0xFFFF00FF, theme.keyOverrides["q"]?.background)
        // A key paint with no colour in it is no override at all.
        assertNull(theme.keyOverrides["SYMBOLS"])
    }

    @Test
    fun `an empty group paint array is no paint`() {
        val empty = """, "groupPaint": [], "keyPaint": {}"""
        val theme = converted("""{"name": "E", ${look(true, empty)}}""").theme
        assertTrue(theme.keyOverrides.isEmpty())
    }

    @Test
    fun `a gradient keeps its stops in position order`() {
        val gradient = """, "backgroundGradient": {"type": "angular", "rotation": 140,
            "stops": [{"color": ${c(0.0, 0.0, 1.0)}, "position": 1}, {"color": ${c(1.0, 0.0, 0.0)}, "position": 0}]}"""
        val spec = converted("""{"name": "Grad", ${look(true, gradient)}}""").theme.boardGradient!!
        assertEquals(listOf(0xFFFF0000, 0xFF0000FF), spec.colors)
        assertEquals(GradientType.SWEEP, spec.type)
        assertEquals(140f, spec.angleDeg)
    }

    @Test
    fun `strokes, shadow, shape and type come across`() {
        val extra = """, "keyCapShape": "capsule", "keyFontWeight": "semibold", "keyFontDesign": "monospaced",
            "keyOuterStroke": {"style": "dashed", "color": ${c(1.0, 1.0, 1.0)}, "width": 2},
            "keyInnerStroke": {"style": "none", "color": ${c(0.0, 0.0, 0.0)}, "width": 1},
            "keyShadow": {"isOn": true, "color": ${c(0.0, 0.0, 0.0, 0.5)}, "radius": 4, "x": 0, "y": 2}"""
        val result = converted("""{"name": "S", ${look(true, extra)}}""")
        val theme = result.theme
        assertEquals(KeyShapeKind.PILL, theme.keyShape)
        assertEquals(true, theme.boldKeyLabels)
        assertEquals("google:Roboto Mono", theme.fontId)
        assertEquals(0xFFFFFFFF, theme.keyBorderColor)
        assertEquals(2f, theme.keyBorderWidthDp)
        assertEquals(2f, theme.keyElevationDp)
        assertEquals(0x80000000, theme.keyShadowColor)
        assertEquals(listOf(ClinkUnsupported.STROKE_STYLE), result.dropped)
    }

    @Test
    fun `materials and effects are reported, glass becomes frost`() {
        val effect = """, "backgroundEffect": {"style": "bokeh"}"""
        val metal = """{"name": "M", ${look(true, effect, material = "metal")}}"""
        val dropped = converted(metal).dropped
        assertTrue(ClinkUnsupported.MATERIAL in dropped)
        assertTrue(ClinkUnsupported.BACKGROUND_EFFECT in dropped)

        val glass = converted("""{"name": "L", ${look(true, material = "liquidGlass")}}""")
        assertTrue(glass.theme.frost != null)
        assertTrue(glass.dropped.isEmpty())
    }

    @Test
    fun `other JSON is not a Clink theme`() {
        assertEquals(ClinkThemeResult.NotATheme, ClinkTheme.read("""{"id": "x", "name": "Y", "boardBackground": 1}"""))
        assertEquals(ClinkThemeResult.NotATheme, ClinkTheme.read("not json"))
        assertFalse(ClinkTheme.looksLikeClinkTheme("""{"keyBackground": 1}"""))
    }
}
