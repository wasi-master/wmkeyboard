package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a Kinetica theme out of its backup.
 *
 * The fixtures are hand-written in the line format Kinetica 2.2 writes
 * (`Backup.encode`), not anybody's real backup. The expected colours are
 * what Kinetica's own `KeyboardTheme` gives for the same settings.
 */
class KineticaThemeTest {

    private fun backup(vararg lines: String) =
        (listOf("kinetica-backup\t1") + lines + "word\ten\thello\t3").joinToString("\n")

    private fun pref(type: String, key: String, value: String) = "pref\t$type\t$key\t$value"

    private fun converted(text: String): KineticaThemeResult.Converted {
        val result = KineticaTheme.read(text)
        assertTrue("expected a theme, got $result", result is KineticaThemeResult.Converted)
        return result as KineticaThemeResult.Converted
    }

    @Test
    fun `a backup with no theme settings is the bundled dark theme`() {
        val theme = converted(backup(pref("bool", "pref_zen_mode", "false"))).theme
        assertTrue(theme.dark)
        assertEquals("Kinetica", theme.name)
        assertEquals(0xFF1B1B1F, theme.boardBackground)
        assertEquals(0xFF303034, theme.keyBackground)
        assertEquals(0xFF25252A, theme.modifierKeyBackground)
        assertEquals(0xFF25252A, theme.enterKeyBackground)
        assertEquals(0xFFEDEDF2, theme.keyText)
        assertEquals(0xFF4A4A52, theme.pressedKeyBackground)
        assertEquals(0xFF232327, theme.suggestionBarBackground)
        assertEquals(0xFFFFFFFF, theme.suggestionText)
        assertEquals(0xFFD8D8E0, theme.secondarySuggestionText)
        assertEquals(0xFF5468FF, theme.accent)
        // Hint colour at Kinetica's 40%.
        assertEquals(0x669A9AA5L, theme.hintText)
        assertEquals(7, theme.keyCornerRadiusDp)
        assertFalse(theme.followWallpaper)
        assertEquals(0f, theme.keyBorderWidthDp)
        assertNull(theme.keyBorderColor)
    }

    @Test
    fun `light brightness takes the light half of the bundled palette`() {
        val theme = converted(backup(pref("string", "pref_theme_brightness", "light"))).theme
        assertFalse(theme.dark)
        assertEquals("Kinetica light", theme.name)
        assertEquals(0xFFE6E6EA, theme.boardBackground)
        assertEquals(0xFFFFFFFF, theme.keyBackground)
        assertEquals(0xFF1B1B1F, theme.keyText)
    }

    @Test
    fun `a typed colour spreads over the roles as Kinetica spreads it`() {
        val theme = converted(
            backup(
                pref("string", "pref_theme_mode", "custom"),
                pref("string", "pref_theme_color_hex", "#475DFF"),
            ),
        ).theme
        assertEquals("Kinetica #475DFF", theme.name)
        // Kinetica 2.2.1's KeyboardTheme.fromPrimary(#475DFF, light = false), run.
        assertEquals(0xFF12131A, theme.boardBackground)
        assertEquals(0xFF272936, theme.keyBackground)
        assertEquals(0xFF1E1F29, theme.modifierKeyBackground)
        assertEquals(0xFF1F2659, theme.pressedKeyBackground)
        assertEquals(0xFF171821, theme.suggestionBarBackground)
        assertEquals(0xFF475DFF, theme.accent)
        assertEquals(0xFFEDEDF2, theme.keyText)
    }

    @Test
    fun `a typed colour on the light table`() {
        val theme = converted(
            backup(
                pref("string", "pref_theme_mode", "custom"),
                pref("string", "pref_theme_color_hex", "#475DFF"),
                pref("string", "pref_theme_brightness", "light"),
            ),
        ).theme
        assertEquals("Kinetica #475DFF light", theme.name)
        // fromPrimary(#475DFF, light = true), run.
        assertEquals(0xFFCACDE6, theme.boardBackground)
        assertEquals(0xFFF1F3FF, theme.keyBackground)
        assertEquals(0xFF1D1E29, theme.keyText)
        assertEquals(0xFF3241B3, theme.accent)
    }

    @Test
    fun `a dark typed colour keeps the accent visible`() {
        val theme = converted(
            backup(
                pref("string", "pref_theme_mode", "custom"),
                pref("string", "pref_theme_color_hex", "#101830"),
            ),
        ).theme
        // Too dark to show on the keys, so its hue and saturation at value 0.6.
        assertEquals(0xFF334D99, theme.accent)
        assertEquals(0xFF0C0D11, theme.boardBackground)
    }

    @Test
    fun `the retired hue slider wins over a stored colour`() {
        val theme = converted(
            backup(
                pref("string", "pref_theme_mode", "custom"),
                pref("int", "pref_theme_hue", "0"),
                pref("string", "pref_theme_color_hex", "#00FF00"),
            ),
        ).theme
        // Hue 0 at s 0.72, v 1 is #FF4747; dark accent is the colour itself.
        assertEquals(0xFFFF4747, theme.accent)
    }

    @Test
    fun `system brightness is a family of the dark and light halves`() {
        val theme = converted(backup(pref("string", "pref_theme_brightness", "system"))).theme
        assertEquals("Kinetica", theme.familyName)
        assertTrue(theme.dark)
        assertEquals(1, theme.variants.size)
        assertFalse(theme.variants.single().dark)
        val stored = theme.withFreshIds("custom_1")
        assertEquals("custom_1_v0", stored.variants.single().id)
    }

    @Test
    fun `Material You follows the wallpaper from the bundled colours`() {
        val theme = converted(backup(pref("string", "pref_theme_mode", "dynamic"))).theme
        assertTrue(theme.followWallpaper)
        assertEquals("Kinetica Material You", theme.name)
        assertEquals(0xFF1B1B1F, theme.boardBackground)
    }

    @Test
    fun `key borders take the hint colour and their width`() {
        val theme = converted(
            backup(pref("bool", "pref_key_borders", "true"), pref("int", "pref_key_border_width", "9")),
        ).theme
        assertEquals(4f, theme.keyBorderWidthDp)
        assertEquals(0xFF9A9AA5, theme.keyBorderColor)
    }

    @Test
    fun `trail presets, a typed trail and the theme trail`() {
        fun trail(vararg lines: String) = converted(backup(*lines)).theme.gestureTrailColor
        // Cyan at s 0.85, v 1.
        assertEquals(0xFF26FFFF, trail(pref("string", "pref_trail_color", "cyan")))
        assertEquals(
            0xFF123456,
            trail(pref("string", "pref_trail_color", "custom"), pref("string", "pref_trail_color_hex", "#123456")),
        )
        assertNull(trail(pref("string", "pref_trail_color", "theme")))
    }

    @Test
    fun `what does not come across is said`() {
        val rainbow = converted(backup(pref("string", "pref_swipe_look", "both")))
        assertTrue(KineticaUnsupported.RAINBOW_TRAIL in rainbow.dropped)
        assertTrue(KineticaUnsupported.LIT_KEYS in rainbow.dropped)
        val plain = converted(backup(pref("string", "pref_trail_color", "theme"), pref("string", "pref_swipe_look", "trail")))
        assertTrue(plain.dropped.isEmpty())
    }

    @Test
    fun `pasted pref lines without the header still read`() {
        val text = pref("string", "pref_theme_brightness", "light")
        assertFalse(KineticaTheme.looksLikeBackup(text))
        assertFalse(converted(text).theme.dark)
    }

    @Test
    fun `text that is not a Kinetica backup is not a theme`() {
        assertEquals(KineticaThemeResult.NotATheme, KineticaTheme.read("hello"))
        assertEquals(KineticaThemeResult.NotATheme, KineticaTheme.read(pref("bool", "pref_zen_mode", "true")))
        assertTrue(KineticaTheme.looksLikeBackup("﻿kinetica-backup\t1\n"))
    }
}
