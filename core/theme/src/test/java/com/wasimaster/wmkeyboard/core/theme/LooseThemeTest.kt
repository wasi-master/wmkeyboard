package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Themes guessed from JSON with no schema: what a chat assistant writes when asked for one. */
class LooseThemeTest {

    /** Cut down from a theme a user's chat assistant wrote for "another keyboard". */
    private val assistantSkin = """
        {
          "name": "Liquid Glass Ruby",
          "theme": { "mode": "DARK", "shape": "PILL", "keyOpacity": 0.88,
            "colors": { "background": "#16070B", "key": "#351019", "keyText": "#FFF1F4",
              "func": "#48121E", "funcText": "#FFDCE4", "accent": "#FF4568" } },
          "skin": {
            "keyboard": {
              "background": { "colors": ["#16070B", "#390D1A", "#19080F"], "angle": 135 },
              "orbs": [ { "color": "#66FF315A", "x": 0.18, "y": 0.25, "r": 0.36 },
                        { "color": "#55D71945", "x": 0.78, "y": 0.68, "r": 0.34 } ],
              "trail": "#CCFF7892",
              "blurTint": "#33150810"
            },
            "colors": { "background": "#1A070D", "key": "#3A101C", "func": "#4A1422", "accent": "#FF496B" },
            "keys": {
              "*": { "material": "glass", "press": "jelly", "text": "#FFF4F7", "shape": "rounded", "radius": 17,
                     "shadowY": 3, "shadowColor": "#99000000" },
              "enter": { "background": { "colors": ["#FF3F65", "#B91D43"], "angle": 135 },
                         "text": "#FFFFFF", "shape": "pill", "glow": "#66FF4568" },
              "space": { "background": "#33101A", "shape": "pill" }
            },
            "popup": { "background": "#6635101C", "border": "#88FF8AA5", "radius": 18 },
            "panel": { "background": "#CC1A070D" }
          },
          "dark": { "colors": { "background": "#120509", "key": "#2B0B14" } }
        }
    """.trimIndent()

    @Test
    fun `an assistant's skin comes across part by part`() {
        val theme = checkNotNull(LooseTheme.read(assistantSkin))
        assertEquals("Liquid Glass Ruby", theme.name)
        assertTrue(theme.dark)
        // The skin's colours win over the general ones above them, and the
        // dark copy and the unknown `panel` never reach the board.
        assertEquals(0xFF1A070D, theme.boardBackground)
        assertEquals(listOf(0xFF16070BL, 0xFF390D1AL, 0xFF19080FL), theme.boardGradient?.colors)
        assertEquals(135f, theme.boardGradient?.angleDeg)
        // 0.88 of opaque, rounded.
        assertEquals(0xE03A101C, theme.keyBackground)
        assertEquals(0xFFFFF4F7, theme.keyText)
        assertEquals(0xE04A1422, theme.modifierKeyBackground)
        assertEquals(KeyShapeKind.ROUNDED, theme.keyShape)
        assertEquals(17, theme.keyCornerRadiusDp)
        assertEquals(3f, theme.keyElevationDp)
        assertEquals(0xFFFF496B, theme.accent)
        assertEquals(0xCCFF7892, theme.gestureTrailColor)
        // A popup is never left mostly see-through over the keys.
        assertEquals(0xCC35101C, theme.popupBackground)
        assertEquals(18, theme.popupCornerRadiusDp)
        assertEquals(KeyMaterialKind.GLASS.name, theme.keyMaterial?.kind)
        assertEquals(KeyPressMotion.JELLY.name, theme.touchBloom?.motion)
    }

    @Test
    fun `single keys keep their gradient, glow and shape`() {
        val theme = checkNotNull(LooseTheme.read(assistantSkin))
        assertEquals(0xFFFF3F65, theme.enterKeyBackground)
        val enter = checkNotNull(theme.keyOverrides["ENTER"])
        assertEquals(listOf(0xFFFF3F65L, 0xFFB91D43L), enter.gradient?.colors)
        assertEquals(0x66FF4568L, enter.glow)
        assertEquals(KeyShapeKind.PILL.name, enter.shape)
        val space = checkNotNull(theme.keyOverrides["SPACE"])
        assertEquals(0xE033101AL, space.background)
    }

    @Test
    fun `placed colour pools keep their places`() {
        val effect = checkNotNull(LooseTheme.read(assistantSkin)?.boardEffect)
        assertEquals(BoardEffectKind.ORBS.name, effect.kind)
        assertEquals(listOf(0x66FF315AL, 0x55D71945L), effect.colors)
        assertEquals(OrbSpot(0.18f, 0.25f, 0.36f), effect.orbSpots.first())
    }

    @Test
    fun `flat names split into part and slot`() {
        val theme = checkNotNull(
            LooseTheme.read(
                """{"themeName":"Ocean","mode":"light","colors":{"primary":"#0077be","background":"#e6f2ff",
                   "keyBackground":"#fff","keyTextColor":"#0a1a2a","specialKeyBackground":"#cfe3f7",
                   "popupBackground":"rgba(255,255,255,0.9)"},"keyCornerRadius":10}""",
            ),
        )
        assertEquals("Ocean", theme.name)
        assertEquals(false, theme.dark)
        assertEquals(0xFFFFFFFF, theme.keyBackground)
        assertEquals(0xFF0A1A2A, theme.keyText)
        assertEquals(0xFFCFE3F7, theme.modifierKeyBackground)
        assertEquals(0xFF0077BE, theme.accent)
        assertEquals(0xE6FFFFFF, theme.popupBackground)
        assertEquals(10, theme.keyCornerRadiusDp)
    }

    @Test
    fun `JSON that is not a theme stays unread`() {
        assertNull(LooseTheme.read("""{"name":"my-app","version":"1.0.0","color":"#ff0000"}"""))
        assertNull(LooseTheme.read("""[1, 2, 3]"""))
        assertNull(LooseTheme.read("not json"))
    }

    @Test
    fun `colour spellings`() {
        assertEquals(0xFFFF0000, LooseTheme.parseColor("#f00"))
        assertEquals(0x80FF0000, LooseTheme.parseColor("#80FF0000"))
        assertEquals(0xFF112233, LooseTheme.parseColor("0x112233"))
        assertEquals(0x80FF0000, LooseTheme.parseColor("rgba(255, 0, 0, 0.5)"))
        assertNull(LooseTheme.parseColor("#12345"))
        assertNull(LooseTheme.parseColor("red"))
    }
}
