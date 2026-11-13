package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Themes following the wallpaper (issue #357): the role remap an imported
 * FlorisBoard theme uses, and the tone remap every other theme uses.
 */
class WallpaperFollowTest {

    /** A tonal palette that is one hue, from white to black, easy to read back. */
    private fun ramp(r: Int, g: Int, b: Int): List<Long> = WallpaperTones.TONES.map { tone ->
        val f = tone / 100f
        fun c(v: Int) = (v * f).toLong().coerceIn(0, 255)
        0xFF000000L or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun palette(roles: SnyggPalette = SnyggPalette.Baseline) = WallpaperPalette(
        roles = roles,
        tones = WallpaperTones(
            primary = ramp(0, 255, 0),
            secondary = ramp(0, 200, 100),
            tertiary = ramp(255, 0, 255),
            neutral = ramp(255, 255, 255),
            neutralVariant = ramp(240, 255, 240),
        ),
    )

    @Test
    fun `a theme that does not follow is returned untouched`() {
        val spec = ThemeSpec(id = "t", name = "T")
        assertSame(spec, spec.followingWallpaper(palette()))
    }

    @Test
    fun `no wallpaper palette leaves the stored colours`() {
        val spec = ThemeSpec(id = "t", name = "T", followWallpaper = true)
        assertSame(spec, spec.followingWallpaper(null))
    }

    @Test
    fun `a recorded tonal shade takes the wallpaper's shade now`() {
        // FUTO names shades as often as roles: `system_neutralVariant60`.
        val spec = ThemeSpec(
            id = "t",
            name = "T",
            followWallpaper = true,
            boardBackground = 0xFF111111,
            keyBackground = 0xFF939094,
            wallpaperRoles = mapOf(
                wallpaperToneKey("neutralVariant", 60) to 0xFF939094,
                wallpaperRoleKey(dark = true, role = "surface") to 0xFF111111,
            ),
        )
        val live = palette(SnyggPalette(light = emptyMap(), dark = mapOf("surface" to 0xFFAA0000)))
        val shown = spec.followingWallpaper(live)
        assertEquals(WallpaperTones.shade(live.tones.neutralVariant, 60), shown.keyBackground)
        assertEquals(0xFFAA0000, shown.boardBackground)
    }

    @Test
    fun `tone keys read back only for a palette and tone that exist`() {
        val tones = palette().tones
        assertEquals("tone:neutralvariant:60", wallpaperToneKey("neutral_variant", 60))
        assertEquals(tones.primary.first(), toneKeyValue(wallpaperToneKey("primary", 100), tones))
        assertEquals(null, toneKeyValue("tone:magenta:50", tones))
        assertEquals(null, toneKeyValue("dark:primary", tones))
    }

    @Test
    fun `recorded roles take the wallpaper's value now`() {
        val spec = ThemeSpec(
            id = "t",
            name = "T",
            followWallpaper = true,
            boardBackground = 0xFF111111,
            keyBackground = 0x80222222,
            // The user changed this one after import; it matches no role.
            enterKeyBackground = 0xFF123456,
            accent = 0xFF333333,
            keyOverrides = mapOf("SPACE" to KeyOverride(background = 0xFF333333)),
            wallpaperRoles = mapOf(
                wallpaperRoleKey(dark = true, role = "surface") to 0xFF111111,
                wallpaperRoleKey(dark = true, role = "surfaceContainer") to 0xFF222222,
                wallpaperRoleKey(dark = true, role = "primary") to 0xFF333333,
            ),
        )
        val live = SnyggPalette(
            light = emptyMap(),
            dark = mapOf("surface" to 0xFFAA0000, "surfacecontainer" to 0xFF00AA00, "primary" to 0xFF0000AA),
        )
        val shown = spec.followingWallpaper(palette(live))
        assertEquals(0xFFAA0000, shown.boardBackground)
        // Alpha is the theme's own and survives.
        assertEquals(0x8000AA00, shown.keyBackground)
        assertEquals(0xFF123456, shown.enterKeyBackground)
        assertEquals(0xFF0000AA, shown.accent)
        assertEquals(0xFF0000AA, shown.keyOverrides.getValue("SPACE").background)
        // The record itself is never rewritten, so the next wallpaper matches too.
        assertEquals(spec.wallpaperRoles, shown.wallpaperRoles)
    }

    @Test
    fun `a theme with no roles keeps each colour's lightness`() {
        val spec = ThemeSpec(
            id = "t",
            name = "T",
            followWallpaper = true,
            boardBackground = 0xFF000000,
            keyBackground = 0xFF808080,
            keyText = 0xFFFFFFFF,
            accent = 0xFF3F51B5,
            enterKeyBackground = 0xFF3F51B5,
            boardGradient = GradientSpec(colors = listOf(0xFF3F51B5, 0x00000000)),
        )
        val shown = spec.followingWallpaper(palette())
        // Black and white are tone 0 and tone 100 of any palette.
        assertEquals(0xFF000000, shown.boardBackground)
        assertEquals(0xFFFFFFFF, shown.keyText)
        // Grey goes to the neutral palette, which here is grey too.
        val key = shown.keyBackground
        assertEquals((key ushr 16) and 0xFF, (key ushr 8) and 0xFF)
        // The indigo accent takes the green primary palette.
        val g = (shown.accent ushr 8) and 0xFF
        val r = (shown.accent ushr 16) and 0xFF
        assertTrue("accent ${shown.accent.toString(16)}", g > 0 && r == 0L)
        // Gradient stops move too; a transparent stop is left alone.
        assertEquals(shown.accent, shown.boardGradient!!.colors[0])
        assertEquals(0x00000000L, shown.boardGradient!!.colors[1])
    }

    @Test
    fun `a colour far round the wheel from the accent takes the tertiary palette`() {
        val spec = ThemeSpec(
            id = "t",
            name = "T",
            followWallpaper = true,
            accent = 0xFF3F51B5,
            enterKeyBackground = 0xFF3F51B5,
            // Orange, opposite indigo.
            keyText = 0xFFFF9800,
        )
        val shown = spec.followingWallpaper(palette())
        val r = (shown.keyText ushr 16) and 0xFF
        val g = (shown.keyText ushr 8) and 0xFF
        assertTrue(r > 0 && g == 0L)
        assertNotEquals(spec.keyText, shown.keyText)
    }

    @Test
    fun `a shade picked off the wallpaper's own palettes stays exactly as picked`() {
        val tones = palette().tones
        // Tertiary at tone 40 on a theme whose accent would otherwise send a
        // colour this saturated to the primary palette.
        val picked = WallpaperTones.shade(tones.tertiary, 40)
        val spec = ThemeSpec(
            id = "t",
            name = "T",
            followWallpaper = true,
            accent = 0xFFFF00FF,
            enterKeyBackground = 0xFFFF00FF,
            keyBackground = (picked and 0x00FFFFFF) or 0xCC000000,
        )
        val shown = spec.followingWallpaper(palette())
        assertEquals((picked and 0x00FFFFFF) or 0xCC000000, shown.keyBackground)
    }

    @Test
    fun `reseeding drops the roles so the switch keeps working`() {
        val spec = ThemeSpec(
            id = "t",
            name = "T",
            followWallpaper = true,
            wallpaperRoles = mapOf("dark:primary" to 0xFF333333),
        )
        val reseeded = spec.reseeded(0xFF3F51B5, dark = true)
        assertTrue(reseeded.wallpaperRoles.isEmpty())
        assertTrue(reseeded.followWallpaper)
    }
}
