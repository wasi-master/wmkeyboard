package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The glass model: what travels, what an older file means, what a preset does. */
class FrostSpecTest {

    @Test
    fun `a theme from before glass existed decodes with no glass`() {
        val json = """{"id":"t","name":"T"}"""
        assertNull(ThemeCodec.decode(json)?.frost)
    }

    @Test
    fun `glass round-trips through the codec`() {
        val theme = ThemeSpec(id = "t", name = "T", frost = FrostSpec(windowBlur = true, rim = 0.7f))
        val back = ThemeCodec.decode(ThemeCodec.encode(theme))
        assertEquals(theme.frost, back?.frost)
    }

    @Test
    fun `a surface name this build does not know costs itself, not the theme`() {
        val spec = FrostSpec(surfaces = listOf("KEYS", "HOLOGRAM", "chips"))
        assertEquals(setOf(FrostSurface.KEYS, FrostSurface.CHIPS), spec.surfaceSet)
        assertTrue(spec.has(FrostSurface.KEYS))
        assertFalse(spec.has(FrostSurface.POPUPS))
    }

    @Test
    fun `a disabled frost field is glass on no surface`() {
        val spec = FrostSpec(enabled = false)
        for (surface in FrostSurface.entries) assertFalse(spec.has(surface))
    }

    @Test
    fun `the default surfaces leave the enter key solid`() {
        val spec = FrostSpec()
        assertFalse(spec.has(FrostSurface.ENTER_KEY))
        assertTrue(spec.has(FrostSurface.KEYS))
        assertTrue(spec.has(FrostSurface.POPUPS))
    }

    @Test
    fun `every preset sits inside the sliders' ranges`() {
        for (preset in FrostPreset.entries) {
            val spec = preset.spec
            assertTrue(preset.name, spec.backdropBlur in FROST_BACKDROP_BLUR_RANGE)
            assertTrue(preset.name, spec.saturation in FROST_SATURATION_RANGE)
            assertTrue(preset.name, spec.brightness in FROST_BRIGHTNESS_RANGE)
            assertTrue(preset.name, spec.rim in FROST_RIM_RANGE)
            assertTrue(preset.name, spec.grain in FROST_GRAIN_RANGE)
            assertTrue(preset.name, spec.windowBlurRadiusDp in WINDOW_BLUR_RADIUS_RANGE)
            assertTrue(preset.name, spec.fallbackOpacity in FROST_FALLBACK_OPACITY_RANGE)
            assertTrue(preset.name, spec.refraction in FROST_REFRACTION_RANGE)
            assertTrue(preset.name, spec.dispersion in FROST_DISPERSION_RANGE)
        }
    }

    @Test
    fun `the glass style travels as a name and only Liquid is a lens`() {
        assertEquals(FrostStyle.FROSTED, FrostSpec().glassStyle)
        assertEquals(FrostStyle.LIQUID, FrostSpec(style = "liquid").glassStyle)
        assertEquals(FrostStyle.FROSTED, FrostSpec(style = "PLASMA").glassStyle)
        assertEquals(FrostStyle.LIQUID, FrostPreset.LIQUID.spec.glassStyle)
        // Picking a frosted preset over a liquid theme turns the lens off.
        val back = ThemeSpec(id = "t", name = "T", frost = FrostSpec(style = "LIQUID")).dressedForGlass(FrostPreset.FROSTED)
        assertEquals(FrostStyle.FROSTED, back.frost?.glassStyle)
    }

    @Test
    fun `dressing lowers only the fills that are more opaque than the preset`() {
        val theme = ThemeSpec(
            id = "t",
            name = "T",
            keyBackground = 0xFF303338,
            modifierKeyBackground = 0x10222428,
            chipBackground = null,
            popupBackground = 0xFF111111,
        )
        val dressed = theme.dressedForGlass(FrostPreset.FROSTED)
        assertEquals(0x40303338L, dressed.keyBackground)
        // Already more see-through than the preset asks: left alone.
        assertEquals(0x10222428L, dressed.modifierKeyBackground)
        assertNull(dressed.chipBackground)
        assertEquals(0xB3111111L, dressed.popupBackground)
        assertEquals(FrostPreset.FROSTED.sheen, dressed.keyGradient)
        assertEquals(true, dressed.frost?.enabled)
    }

    @Test
    fun `dressing keeps the window-blur choice and the surface set`() {
        val theme = ThemeSpec(
            id = "t",
            name = "T",
            frost = FrostSpec(windowBlur = true, windowBlurRadiusDp = 40, surfaces = listOf("KEYS")),
        )
        val dressed = theme.dressedForGlass(FrostPreset.MICA)
        assertEquals(true, dressed.frost?.windowBlur)
        assertEquals(40, dressed.frost?.windowBlurRadiusDp)
        assertEquals(listOf("KEYS"), dressed.frost?.surfaces)
        assertEquals(FrostPreset.MICA.spec.backdropBlur, dressed.frost?.backdropBlur)
    }

    @Test
    fun `dressing keeps a sheen the theme already has`() {
        val own = GradientSpec(listOf(0x40FF0000, 0x00FF0000))
        val dressed = ThemeSpec(id = "t", name = "T", keyGradient = own).dressedForGlass(FrostPreset.CLEAR)
        assertEquals(own, dressed.keyGradient)
    }

    @Test
    fun `reseeding keeps the glass`() {
        val theme = ThemeSpec(id = "t", name = "T", frost = FrostSpec(rim = 0.9f))
        assertEquals(0.9f, theme.reseeded(0xFF3B82C4, dark = true).frost?.rim)
    }

    @Test
    fun `the backdrop and the wallpaper band travel as names with safe defaults`() {
        assertEquals(FrostBackdropSource.AUTO, FrostSpec().backdropSource)
        assertEquals(WallpaperBand.BOTTOM, FrostSpec().band)
        assertEquals(FrostBackdropSource.WALLPAPER, FrostSpec(backdrop = "wallpaper").backdropSource)
        assertEquals(WallpaperBand.TOP, FrostSpec(wallpaperBand = "top").band)
        // A name from a later build costs the field, not the theme.
        assertEquals(FrostBackdropSource.AUTO, FrostSpec(backdrop = "HOLODECK").backdropSource)
        assertEquals(WallpaperBand.BOTTOM, FrostSpec(wallpaperBand = "left").band)
    }

    @Test
    fun `the board is frosted by default and dressing keeps the choice`() {
        assertTrue(FrostSpec().blurBoard)
        val theme = ThemeSpec(id = "t", name = "T", frost = FrostSpec(blurBoard = false))
        assertFalse(theme.dressedForGlass(FrostPreset.FROSTED).frost?.blurBoard ?: true)
    }

    @Test
    fun `an accent-free board keeps its own lightness and the keys still follow their roles`() {
        val frost = BuiltInThemes.first { it.id == "builtin_frost" }
        val palette = WallpaperPalette(
            roles = SnyggPalette.Baseline,
            tones = WallpaperTones(
                primary = List(13) { 0xFF3050A0 }, secondary = List(13) { 0xFF506080 },
                tertiary = List(13) { 0xFF805070 }, neutral = List(13) { 0xFF303034 },
                neutralVariant = List(13) { 0xFF303040 },
            ),
        )
        val accented = frost.followingWallpaper(palette)
        val quiet = frost.copy(frost = frost.frost?.copy(accentBoard = false)).followingWallpaper(palette)
        assertTrue(accented.boardBackground != quiet.boardBackground)
        assertEquals(accented.keyBackground, quiet.keyBackground)
        // Alpha is the theme's own either way.
        assertEquals(frost.boardBackground ushr 24, quiet.boardBackground ushr 24)
    }

    @Test
    fun `dressing keeps the backdrop choice`() {
        val theme = ThemeSpec(id = "t", name = "T", frost = FrostSpec(backdrop = "WALLPAPER", wallpaperBand = "CENTER"))
        val dressed = theme.dressedForGlass(FrostPreset.SMOKED)
        assertEquals("WALLPAPER", dressed.frost?.backdrop)
        assertEquals("CENTER", dressed.frost?.wallpaperBand)
    }

    @Test
    fun `picture on keys cuts the image sharp across the keys and back`() {
        val theme = ThemeSpec(
            id = "t", name = "T",
            boardBackground = 0x0017181CL,
            keyBackground = 0xFF303338L,
            modifierKeyBackground = 0x80222428L,
            enterKeyBackground = 0xFF4C8DF6L,
        )
        val on = theme.withPictureOnKeys(true)
        assertTrue(on.pictureOnKeys)
        val frost = on.frost!!
        assertEquals(0f, frost.backdropBlur)
        assertEquals(1f, frost.saturation)
        assertEquals(0f, frost.rim)
        assertEquals(0f, frost.grain)
        assertFalse(frost.blurBoard)
        assertEquals(FrostBackdropSource.IMAGE, frost.backdropSource)
        assertEquals(PICTURE_ON_KEYS_SURFACES.toSet(), frost.surfaces.toSet())
        // Keys see-through, the gaps opaque, Enter left findable.
        assertEquals(0x00303338L, on.keyBackground)
        assertEquals(0x00222428L, on.modifierKeyBackground)
        assertEquals(0xFF17181CL, on.boardBackground)
        assertEquals(0xFF4C8DF6L, on.enterKeyBackground)
        // Round-trips, so a shared theme keeps the look.
        assertTrue(ThemeCodec.decode(ThemeCodec.encode(on))!!.pictureOnKeys)

        val off = on.withPictureOnKeys(false)
        assertFalse(off.pictureOnKeys)
        assertFalse(off.frost!!.enabled)
        assertEquals(0xFF303338L, off.keyBackground)
        assertEquals(0xFF222428L, off.modifierKeyBackground)
    }

    @Test
    fun `a glass preset drops the picture-on-keys cut`() {
        val on = ThemeSpec(id = "t", name = "T").withPictureOnKeys(true)
        assertFalse(on.dressedForGlass(FrostPreset.FROSTED).pictureOnKeys)
    }
}
