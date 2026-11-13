package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FUTO's per-key pictures and wallpaper colours, carried onto key textures,
 * single-key styles, icon slots and wallpaper roles.
 *
 * The fixture is synthetic, because the themes in FUTO's gallery carry no
 * licence: it is written in the shape the 14 gallery themes of 2026-11-13
 * share (rules ordered pressed-first, a positional rule, a `stickyon` rule,
 * nine-slice assets at `target_density = 640`), with made-up pictures.
 */
class FutoMatchRulesTest {

    private val pictures = listOf(
        "normal.png", "normal-pressed.png", "action.png", "action-pressed.png", "spacebar.png",
        "functional.png", "corner.png", "sticky.png", "popup.png", "q.png",
        "shift.png", "shift-on.png", "enter.png",
    ).associateWith { png(128, 140, it.hashCode()) }

    private fun read(text: String = GALLERY, palette: WallpaperPalette? = null): FutoThemeResult.Converted =
        FutoTheme.read(mapOf(FutoTheme.TOML_CONFIG to text.toByteArray()) + pictures, palette)
            as FutoThemeResult.Converted

    @Test
    fun classPicturesLandOnTheClassSlots() {
        val images = read().converted.images
        assertArrayEquals(pictures["normal.png"], images[ASSET_KEY_TEXTURE])
        assertArrayEquals(pictures["functional.png"], images[ASSET_KEY_TEXTURE_MODIFIER])
        assertArrayEquals(pictures["action.png"], images[ASSET_KEY_TEXTURE_ENTER])
        assertArrayEquals(pictures["spacebar.png"], images[ASSET_KEY_TEXTURE_SPACE])
        assertArrayEquals(pictures["normal-pressed.png"], images[ASSET_KEY_TEXTURE_PRESSED])
        assertArrayEquals(pictures["popup.png"], images[ASSET_POPUP_TEXTURE])
    }

    @Test
    fun aKeyThatDiffersFromItsClassGetsItsOwnStyle() {
        val result = read()
        val theme = result.converted.theme
        // `label q` comes before `normal`, so q wears its own picture.
        assertArrayEquals(pictures["q.png"], result.converted.images[ASSET_KEY_OVERRIDE_TEXTURE_PREFIX + "q"])
        assertTrue("q" in theme.keyOverrides)
        // The other letters follow the class and get no entry.
        assertFalse("w" in theme.keyOverrides)
        // Enter's held picture is not the board's.
        assertArrayEquals(
            pictures["action-pressed.png"],
            result.converted.images[ASSET_KEY_OVERRIDE_PRESSED_PREFIX + "ENTER"],
        )
        // The space bar has no held picture of its own in FUTO, so it keeps its
        // resting one rather than the letters' held one.
        assertArrayEquals(
            pictures["spacebar.png"],
            result.converted.images[ASSET_KEY_OVERRIDE_PRESSED_PREFIX + "SPACE"],
        )
        assertNull(result.converted.images[ASSET_KEY_OVERRIDE_PRESSED_PREFIX + "q"])
    }

    @Test
    fun picturedFacesGoClearAndTakeTheLabelColour() {
        val theme = read().converted.theme
        assertEquals(0L, theme.keyBackground)
        assertEquals(0L, theme.modifierKeyBackground)
        assertEquals(0L, theme.enterKeyBackground)
        assertEquals(0L, theme.pressedKeyBackground)
        // The preview bubble keeps the colour the keys had.
        assertEquals(0xFFDB4740L, theme.popupBackground)
        // foreground_tint is the label drawn on the picture.
        assertEquals(0xFFFBF1C7L, theme.keyText)
        assertEquals(0xFF282828L, theme.enterKeyText)
        assertEquals("stretch", theme.keyTextureScale)
    }

    @Test
    fun nineSliceCutsCarryTheirNaturalSize() {
        val slices = read().converted.theme.textureSlices
        val normal = slices.getValue(ASSET_KEY_TEXTURE)
        assertEquals(0.25f, normal.left)
        assertEquals(0.2f, normal.top)
        assertEquals(0.75f, normal.right)
        assertEquals(0.8f, normal.bottom)
        // 128 × 140 px drawn for 640 dpi: a quarter of a dp each.
        assertEquals(32f, normal.widthDp!!, 0.001f)
        assertEquals(35f, normal.heightDp!!, 0.001f)
        assertNull(normal.tint)
        val action = slices.getValue(ASSET_KEY_TEXTURE_ENTER)
        assertEquals(64f, action.widthDp!!, 0.001f)
        // `#ff000080` is RGBA: a translucent red multiplied in.
        assertEquals(0x80FF0000L, action.tint)
        // The cut travels with each slot the picture lands in.
        assertNotNull(slices[ASSET_KEY_TEXTURE_SPACE])
        assertNotNull(slices[ASSET_KEY_OVERRIDE_PRESSED_PREFIX + "SPACE"])
    }

    @Test
    fun iconRulesFillTheIconSlots() {
        val images = read().converted.images
        assertArrayEquals(pictures["shift.png"], images[ASSET_KEY_ICON_PREFIX + "key.shift"])
        assertArrayEquals(pictures["shift-on.png"], images[ASSET_KEY_ICON_PREFIX + "key.shift_on"])
        assertArrayEquals(pictures["shift-on.png"], images[ASSET_KEY_ICON_PREFIX + "key.shift_lock"])
        assertArrayEquals(pictures["enter.png"], images[ASSET_KEY_ICON_PREFIX + "key.enter"])
        assertNull(images[ASSET_KEY_ICON_PREFIX + "key.backspace"])
    }

    @Test
    fun whatCannotComeAcrossIsNamed() {
        val dropped = read().dropped
        assertTrue(FutoThemeUnsupported.KEY_POSITIONS in dropped)
        assertTrue(FutoThemeUnsupported.KEY_STATES in dropped)
        assertTrue(FutoThemeUnsupported.WALLPAPER_STAND_IN in dropped)
        assertFalse(FutoThemeUnsupported.KEY_PICTURES in dropped)
        assertFalse(FutoThemeUnsupported.SHADER in dropped)
    }

    @Test
    fun withBordersOffThePicturesAreDroppedAndNamed() {
        val result = read(GALLERY.replace("auto_borders = true", "auto_borders = false"))
        assertEquals(KeyShapeKind.NONE, result.converted.theme.keyShape)
        assertNull(result.converted.images[ASSET_KEY_TEXTURE])
        assertTrue(FutoThemeUnsupported.KEY_PICTURES in result.dropped)
    }

    @Test
    fun wallpaperNamesResolveAgainstThePhonesPalette() {
        val device = WallpaperPalette(
            roles = SnyggPalette.Baseline.copy(
                dark = SnyggPalette.Baseline.dark + ("primary" to 0xFF00AA00),
                fromDevice = true,
            ),
            tones = WallpaperTones.Baseline,
        )
        val result = read(palette = device)
        val theme = result.converted.theme
        assertEquals(0xFF00AA00L, theme.accent)
        assertEquals(0xFF00AA00L, theme.wallpaperRoles[wallpaperRoleKey(dark = true, role = "primary")])
        // FUTO's own shade spelling: neutralVariant at tone 60.
        assertEquals(0xFF938F99L, theme.wallpaperRoles[wallpaperToneKey("neutralvariant", 60)])
        assertFalse(FutoThemeUnsupported.WALLPAPER_STAND_IN in result.dropped)
        assertTrue(theme.followWallpaper)
    }

    @Test
    fun everySpellingOfAWallpaperColourIsRead() {
        val colours = FutoColours(alphaLast = true, palette = null)
        assertEquals(0xFFD0BCFFL, colours.colour("system_accent1_200"))
        assertEquals(0xFF000000L, colours.colour("system_neutral0"))
        assertEquals(0xFFD0BCFFL, colours.colour("system_primary80"))
        assertEquals(0xFF1D1B20L, colours.colour("system_on_surface_light"))
        assertEquals(0xFF2B2930L, colours.colour("system_surface_container_high_dark"))
        assertNull(colours.colour("system_nonsense_dark"))
        assertTrue(FutoThemeUnsupported.WALLPAPER_COLOURS in colours.dropped)
        assertEquals(0x80102030L, colours.colour("#10203080"))
    }

    @Test
    fun selectorsSplitIntoWhatTheyAsk() {
        val s = FutoSelector.parse("functional icon action_emoji pressed")
        assertEquals(setOf("functional"), s.classes)
        assertEquals(setOf("pressed"), s.states)
        assertEquals("action_emoji", s.icon)
        assertFalse(s.positional)
        assertTrue(FutoSelector.parse("normal rowmod 1 2 colmod 3 9").positional)
        assertTrue(FutoSelector.parse("normal row 1 col -2").positional)
        assertTrue(FutoSelector.parse("sparkly").unknown)
        val shift = FutoMatchRules.PROBES.first { it.id == "SHIFT" }
        // A held key still fits a rule that names no state; a resting key does
        // not fit a pressed rule.
        assertTrue(FutoSelector.parse("functional").fits(shift, setOf("pressed")))
        assertFalse(FutoSelector.parse("functional pressed").fits(shift, emptySet()))
        // Labels match either case, since a style here covers both.
        val q = FutoMatchRules.PROBES.first { it.id == "q" }
        assertTrue(FutoSelector.parse("label Q").fits(q, emptySet()))
    }

    @Test
    fun aCutOnlyAppliesToTheFileItWasMadeFor() {
        val slices = mapOf(ASSET_KEY_TEXTURE to TextureSlice(0.8f, 0.2f, 0.2f, 0.8f, path = "/t/a_tex.img"))
        val cut = slices.sliceFor(ASSET_KEY_TEXTURE, "/t/a_tex.img")
        // Fractions put back in order.
        assertEquals(0.2f, cut!!.left)
        assertEquals(0.8f, cut.right)
        // A picture swapped in the editor is drawn the ordinary way.
        assertNull(slices.sliceFor(ASSET_KEY_TEXTURE, "/t/picked_123.img"))
        assertNull(slices.sliceFor(ASSET_KEY_TEXTURE_ENTER, "/t/a_tex.img"))
        val theme = ThemeSpec(
            id = "a",
            name = "A",
            keyTexture = "/t/a_tex.img",
            keyOverrides = mapOf("ENTER" to KeyOverride(pressedTexture = "/t/a_keypress_ENTER.img")),
        )
        assertEquals("/t/a_tex.img", theme.texturePathFor(ASSET_KEY_TEXTURE))
        assertEquals("/t/a_keypress_ENTER.img", theme.texturePathFor(ASSET_KEY_OVERRIDE_PRESSED_PREFIX + "ENTER"))
        assertNull(theme.texturePathFor(ASSET_KEY_TEXTURE_SPACE))
    }

    @Test
    fun pngHeadersGiveTheirSize() {
        assertEquals(128 to 140, FutoMatchRules.pngSize(png(128, 140, 0)))
        assertNull(FutoMatchRules.pngSize(byteArrayOf(1, 2, 3)))
    }

    /** The first 24 bytes of a PNG, which is all the size check reads, plus a tag to tell files apart. */
    private fun png(width: Int, height: Int, tag: Int): ByteArray {
        val out = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10,
            0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
        )
        fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        return out + int(width) + int(height) + int(tag)
    }

    private companion object {
        val GALLERY = """
            # Format version: 1.0
            name = "Pictured"
            author = "Someone"

            [options]
            auto_borders = true
            roundedness = 0.5

            [colors]
            primary = "system_primary_dark"
            on_primary = "#1d2021"
            background = "#3c3836"
            on_background = "#d5c4a1"
            keyboard_surface = "#282828"
            keyboard_container = "#db4740"
            keyboard_container_variant = "#7c6f64"
            on_keyboard_container = "#fbf1c7"
            keyboard_container_pressed = "system_neutralVariant60"

            [[matchrules.border]]
            selector = "normal pressed"
            asset = "normal-pressed.png"

            [[matchrules.border]]
            selector = "action pressed"
            asset = "action-pressed.png"

            [[matchrules.border]]
            selector = "functional row -1 col 0"
            asset = "corner.png"

            [[matchrules.border]]
            selector = "stickyon"
            asset = "sticky.png"

            [[matchrules.border]]
            selector = "popup"
            asset = "popup.png"

            [[matchrules.border]]
            selector = "label q"
            asset = "q.png"

            [[matchrules.border]]
            selector = "action"
            asset = "action.png"

            [[matchrules.border]]
            selector = "spacebar"
            asset = "spacebar.png"

            [[matchrules.border]]
            selector = "normal"
            asset = "normal.png"

            [[matchrules.border]]
            selector = "functional"
            asset = "functional.png"

            [[matchrules.icon]]
            selector = "icon shift_key_shifted"
            asset = "shift-on.png"

            [[matchrules.icon]]
            selector = "icon shift_key"
            asset = "shift.png"

            [[matchrules.icon]]
            selector = "action"
            asset = "enter.png"

            [[asset.border]]
            name = "normal.png"
            background_tint = "#ffffff"
            foreground_tint = "#fbf1c7"
            padding = [0, 0, 0, 0]
            slicing = [0.25, 0.2, 0.75, 0.8]
            gap = [1, 1, 1, 1]
            target_density = 640

            [[asset.border]]
            name = "action.png"
            background_tint = "#ff000080"
            foreground_tint = "#282828"
            slicing = [0.2, 0.2, 0.8, 0.8]
            target_density = 320

            [[asset.border]]
            name = "spacebar.png"
            background_tint = "#ffffff"
            foreground_tint = "#fbf1c7"
            slicing = [0.266, 0.193, 0.738, 0.552]
            target_density = 640

            [[asset.icon]]
            name = "shift.png"
            target_density = 640
        """.trimIndent()
    }
}
