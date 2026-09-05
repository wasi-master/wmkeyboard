package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.theme.DEFAULT_THEME_ID
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.findThemeSpec

/**
 * The theme id the keyboard is actually showing right now. Auto-theme, when
 * on, picks from its light/dark pair and ignores the manually selected id;
 * [darkSlot] is which half of that pair is currently due (the IME computes it
 * from the trigger — system dark mode, a schedule, or the sun).
 *
 * A half that selects at random resolves through [slotThemeId] to the theme it
 * selected last, which the keyboard rewrites between sessions rather than here:
 * this stays a pure read that three non-composable callers can share.
 *
 * This is the single definition of "which theme is active"; the theme
 * provider and the settings overlay both resolve through here so the spec
 * that paints the board and the spec whose overrides reshape it can never
 * disagree.
 */
fun KeyboardSettings.effectiveThemeId(darkSlot: Boolean): String =
    if (autoTheme.enabled) autoTheme.slotThemeId(darkSlot) else keyboardThemeId

/**
 * The stored [ThemeSpec] behind [effectiveThemeId], or null when the default
 * ("Auto") theme is active — that one is derived from the Material scheme and
 * has no spec to carry overrides.
 */
fun KeyboardSettings.activeThemeSpec(darkSlot: Boolean): ThemeSpec? {
    val id = effectiveThemeId(darkSlot)
    if (id == DEFAULT_THEME_ID) return null
    return findThemeSpec(id, customThemes)
}

/**
 * The settings as they apply while a grid that names its own theme is on
 * screen (issue #61): that theme selected, and the auto pair switched off for
 * the same reason a mode's theme switches it off — "this layout looks like
 * this" must not quietly mean "unless auto-theme is on". A view, never a
 * write back; the user's own choice is untouched and returns with the next
 * layer.
 *
 * [themeId] is `KeyboardLayout.themeId`, the layer-beats-layout answer. An id
 * this device has no theme for falls through to the settings: a layout shared
 * with its theme still remembers the pairing for when the theme arrives, and
 * meanwhile draws the way everything else does rather than in some default.
 *
 * Instance-stable when there is nothing to do, for the same reason
 * [applyThemeOverrides] is: the caller remembers on the result.
 */
fun KeyboardSettings.applyLayoutTheme(themeId: String?): KeyboardSettings {
    if (themeId == null) return this
    if (themeId != DEFAULT_THEME_ID && findThemeSpec(themeId, customThemes) == null) return this
    if (themeId == keyboardThemeId && !autoTheme.enabled) return this
    return copy(keyboardThemeId = themeId, autoTheme = autoTheme.copy(enabled = false))
}

/**
 * Lays the theme's layout/type overrides over the global appearance settings,
 * the way [resolvedFor] lays screen-variant sizing over them: the result is a
 * whole [KeyboardSettings], so every `settings.fontScale` downstream keeps
 * working without knowing themes can reshape the board. Null fields fall
 * through to the user's globals.
 *
 * Ordering contract (see the call sites in the service and KeyboardScreen):
 * [applyDeviceForm] runs first, then mode overlays, then this, and [resolvedFor]
 * runs last. Each step is more specific than the one before — a per-screen sizing
 * override is the most explicit user intent and beats the theme, the theme beats
 * the plain globals, and the device-form defaults are only what this screen size
 * would have shipped with, so everything beats them.
 *
 * Never touches [KeyboardSettings.keyboardThemeId], [KeyboardSettings.autoTheme]
 * or [KeyboardSettings.customThemes]: theme selection feeds this overlay, so
 * writing any of them back would loop.
 */
fun KeyboardSettings.applyThemeOverrides(spec: ThemeSpec?): KeyboardSettings {
    if (spec == null) return this
    val untouched = spec.toolbarHeightDp == null && spec.keyHeightDp == null &&
        spec.keyGapScale == null && spec.sidePadScale == null &&
        spec.sidePadLeftScale == null && spec.sidePadRightScale == null &&
        spec.fontScale == null && spec.boldKeyLabels == null &&
        spec.hintFontScale == null && spec.gestureTrailWidthDp == null &&
        spec.gestureTrailOpacity == null && spec.toolWidthDp == null
    // Instance-stable in the no-override case: the caller remembers on the
    // result, and a fresh-but-equal copy per frame would defeat that.
    if (untouched) return this
    return copy(
        toolbarHeightDp = spec.toolbarHeightDp ?: toolbarHeightDp,
        keyHeightDp = spec.keyHeightDp ?: keyHeightDp,
        keyGapScale = spec.keyGapScale ?: keyGapScale,
        fontScale = spec.fontScale ?: fontScale,
        boldKeyLabels = spec.boldKeyLabels ?: boldKeyLabels,
        toolbarBehavior = spec.toolWidthDp
            ?.let { toolbarBehavior.copy(toolWidthDp = it) } ?: toolbarBehavior,
        layoutBehavior = if (
            spec.sidePadScale != null || spec.sidePadLeftScale != null ||
            spec.sidePadRightScale != null || spec.hintFontScale != null
        ) {
            layoutBehavior.copy(
                // The per-edge overrides beat the legacy symmetric one, which
                // stands in for whichever edge they leave unset.
                sidePadLeftScale = spec.sidePadLeftScale ?: spec.sidePadScale
                    ?: layoutBehavior.sidePadLeftScale,
                sidePadRightScale = spec.sidePadRightScale ?: spec.sidePadScale
                    ?: layoutBehavior.sidePadRightScale,
                hintFontScale = spec.hintFontScale ?: layoutBehavior.hintFontScale,
            )
        } else {
            layoutBehavior
        },
        gesture = if (spec.gestureTrailWidthDp != null || spec.gestureTrailOpacity != null) {
            gesture.copy(
                trailWidthDp = spec.gestureTrailWidthDp ?: gesture.trailWidthDp,
                trailOpacity = spec.gestureTrailOpacity ?: gesture.trailOpacity,
            )
        } else {
            gesture
        },
    )
}
