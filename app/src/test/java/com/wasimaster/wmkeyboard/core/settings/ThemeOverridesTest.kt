package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.theme.DEFAULT_THEME_ID
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The theme layout overlay: which spec is active, how its overrides land on
 * [KeyboardSettings], and the ordering contract with [resolvedFor] — the theme
 * reshapes the base look, an explicit per-screen sizing override still wins.
 */
class ThemeOverridesTest {

    private val wide = ThemeSpec(
        id = "wide",
        name = "Wide",
        toolWidthDp = 56,
        toolbarHeightDp = 52,
        keyHeightDp = 60,
        fontScale = 1.2f,
        boldKeyLabels = true,
        sidePadScale = 0.1f,
        gestureTrailWidthDp = 4f,
    )

    // ---- a layout's own theme (issue #61) --------------------------------

    @Test
    fun `a grid naming a theme selects it and switches the auto pair off`() {
        val settings = KeyboardSettings(
            keyboardThemeId = DEFAULT_THEME_ID,
            customThemes = listOf(wide),
            autoTheme = AutoThemeSettings(enabled = true),
        )
        val themed = settings.applyLayoutTheme("wide")
        assertEquals("wide", themed.effectiveThemeId(darkSlot = true))
        assertSame(wide, themed.activeThemeSpec(darkSlot = true))
        assertEquals(false, themed.autoTheme.enabled)
        // A view, not a write: the settings it was laid over are untouched.
        assertEquals(DEFAULT_THEME_ID, settings.keyboardThemeId)
        assertEquals(true, settings.autoTheme.enabled)
    }

    @Test
    fun `a grid naming a theme this device lacks changes nothing`() {
        val settings = KeyboardSettings(keyboardThemeId = "wide", customThemes = listOf(wide))
        assertSame(settings, settings.applyLayoutTheme("gone"))
        assertSame(settings, settings.applyLayoutTheme(null))
        // Already showing: the same instance, so the remembers downstream hold.
        assertSame(settings, settings.applyLayoutTheme("wide"))
    }

    // ---- spec selection ----------------------------------------------

    @Test
    fun `the selected custom theme is the active spec`() {
        val settings = KeyboardSettings(keyboardThemeId = "wide", customThemes = listOf(wide))
        assertEquals("wide", settings.effectiveThemeId(darkSlot = false))
        assertSame(wide, settings.activeThemeSpec(darkSlot = false))
    }

    @Test
    fun `the default theme has no spec to override with`() {
        val settings = KeyboardSettings(keyboardThemeId = DEFAULT_THEME_ID)
        assertNull(settings.activeThemeSpec(darkSlot = false))
    }

    @Test
    fun `a selected variant of a custom family is the active spec`() {
        val night = wide.copy(id = "wide_v0", name = "Wide night")
        val family = wide.copy(variants = listOf(night))
        val settings = KeyboardSettings(keyboardThemeId = "wide_v0", customThemes = listOf(family))
        assertEquals("Wide night", settings.activeThemeSpec(darkSlot = false)?.name)
    }

    @Test
    fun `a selected built-in variant is the active spec`() {
        // Glacier moved inside the Snow family; a selection from before the
        // grouping must keep resolving.
        val settings = KeyboardSettings(keyboardThemeId = "builtin_glacier")
        assertEquals("builtin_glacier", settings.activeThemeSpec(darkSlot = false)?.id)
    }

    @Test
    fun `the default panel shortlist names themes that exist`() {
        val flattened = com.wasimaster.wmkeyboard.core.theme.BuiltInThemes
            .flatMap { listOf(it) + it.variants }
            .map { it.id }
        for (id in DefaultThemesPanelBuiltIns) {
            assertEquals("$id is not a built-in", true, id in flattened)
        }
    }

    @Test
    fun `auto-theme picks the slot's id, not the selected one`() {
        val settings = KeyboardSettings(
            keyboardThemeId = "wide",
            customThemes = listOf(wide),
            autoTheme = AutoThemeSettings(
                enabled = true,
                lightThemeId = DEFAULT_THEME_ID,
                darkThemeId = "wide",
            ),
        )
        assertEquals(DEFAULT_THEME_ID, settings.effectiveThemeId(darkSlot = false))
        assertNull(settings.activeThemeSpec(darkSlot = false))
        assertSame(wide, settings.activeThemeSpec(darkSlot = true))
    }

    @Test
    fun `a random slot resolves to the theme it selected, and the other half does not`() {
        val settings = KeyboardSettings(
            keyboardThemeId = DEFAULT_THEME_ID,
            customThemes = listOf(wide),
            autoTheme = AutoThemeSettings(
                enabled = true,
                lightThemeId = DEFAULT_THEME_ID,
                darkThemeId = "builtin_ocean",
                lightRandom = true,
                lightPoolIds = setOf("wide", "builtin_snow"),
                shuffleLightId = "wide",
            ),
        )
        assertEquals("wide", settings.effectiveThemeId(darkSlot = false))
        assertSame(wide, settings.activeThemeSpec(darkSlot = false))
        // The dark half is not random, so it still shows the one theme it names.
        assertEquals("builtin_ocean", settings.effectiveThemeId(darkSlot = true))
    }

    // ---- the overlay --------------------------------------------------

    @Test
    fun `a set override beats the global, an unset one falls through`() {
        val base = KeyboardSettings(keyHeightDp = 48, keyGapScale = 1.5f, fontScale = 1f)
        val overlaid = base.applyThemeOverrides(wide)
        assertEquals(60, overlaid.keyHeightDp)
        assertEquals(1.2f, overlaid.fontScale, 0f)
        assertEquals(56, overlaid.toolbarBehavior.toolWidthDp)
        // The legacy symmetric override still reaches both edges (issue #41).
        assertEquals(0.1f, overlaid.layoutBehavior.sidePadLeftScale, 0f)
        assertEquals(0.1f, overlaid.layoutBehavior.sidePadRightScale, 0f)
        assertEquals(4f, overlaid.gesture.trailWidthDp, 0f)
        // keyGapScale is null on the spec: the user's global stands.
        assertEquals(1.5f, overlaid.keyGapScale, 0f)
        // Trail opacity unset: untouched.
        assertEquals(base.gesture.trailOpacity, overlaid.gesture.trailOpacity, 0f)
    }

    @Test
    fun `a per-edge pad beats the legacy symmetric one on the edge it names`() {
        val lopsided = wide.copy(sidePadRightScale = 0.25f)
        val overlaid = KeyboardSettings().applyThemeOverrides(lopsided)
        assertEquals(0.1f, overlaid.layoutBehavior.sidePadLeftScale, 0f)
        assertEquals(0.25f, overlaid.layoutBehavior.sidePadRightScale, 0f)
    }

    @Test
    fun `a theme with no overrides returns the same settings instance`() {
        // The caller remembers on the result; a fresh-but-equal copy per frame
        // would defeat the per-keystroke skip.
        val base = KeyboardSettings()
        assertSame(base, base.applyThemeOverrides(ThemeSpec(id = "plain", name = "Plain")))
        assertSame(base, base.applyThemeOverrides(null))
    }

    @Test
    fun `theme selection fields are never touched`() {
        val base = KeyboardSettings(keyboardThemeId = "wide", customThemes = listOf(wide))
        val overlaid = base.applyThemeOverrides(wide)
        assertEquals("wide", overlaid.keyboardThemeId)
        assertSame(base.customThemes, overlaid.customThemes)
        assertSame(base.autoTheme, overlaid.autoTheme)
    }

    // ---- ordering with resolvedFor ------------------------------------

    @Test
    fun `a per-screen sizing override beats the theme`() {
        val base = KeyboardSettings(
            keyHeightDp = 48,
            sizingOverrides = mapOf(
                ScreenVariant.LANDSCAPE to SizingOverride(keyHeightDp = 40, fontScale = 0.9f),
            ),
        )
        val resolved = base.applyThemeOverrides(wide).resolvedFor(ScreenVariant.LANDSCAPE)
        assertEquals(40, resolved.keyHeightDp)
        assertEquals(0.9f, resolved.fontScale, 0f)
        // Fields the variant does not size still carry the theme's values.
        assertEquals(52, resolved.toolbarHeightDp)
        assertEquals(56, resolved.toolbarBehavior.toolWidthDp)
    }

    @Test
    fun `on a screen with no sizing override the theme's sizes stand`() {
        val base = KeyboardSettings(keyHeightDp = 48)
        val resolved = base.applyThemeOverrides(wide).resolvedFor(ScreenVariant.PORTRAIT)
        assertEquals(60, resolved.keyHeightDp)
        assertEquals(1.2f, resolved.fontScale, 0f)
    }

    @Test
    fun `the variant's keyboard scale multiplies the theme's key height`() {
        val base = KeyboardSettings(
            keyHeightDp = 48,
            sizingOverrides = mapOf(
                ScreenVariant.LANDSCAPE to SizingOverride(keyboardScale = 0.5f),
            ),
        )
        val resolved = base.applyThemeOverrides(wide).resolvedFor(ScreenVariant.LANDSCAPE)
        assertEquals(30, resolved.keyHeightDp)
    }
}
