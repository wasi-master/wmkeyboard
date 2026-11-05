package com.wasimaster.wmkeyboard.core.settings

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.settings.R
import kotlin.math.roundToInt

/**
 * The screen shapes a keyboard has to fit, each of which can carry its own
 * sizing.
 *
 * A key height that feels right on a folded phone is cramped on a tablet
 * and far too tall in landscape, where vertical space is the scarce thing.
 * Rather than one compromise set of numbers, each shape gets its own.
 *
 * [PORTRAIT] is the base: its values are the plain fields on
 * [KeyboardSettings], which is what the keyboard used before this existed.
 * The other three are *overrides* — each value is optional, and anything
 * left unset falls back to portrait. That keeps existing installs behaving
 * exactly as they did and means a user who only cares about landscape
 * height sets one number, not a whole profile.
 *
 * "Unfolded" is decided by the smallest screen dimension rather than the
 * current width: a phone in landscape is wide but still a phone, while an
 * opened foldable or a tablet is wide in both orientations.
 */
enum class ScreenVariant(val suffix: String, @StringRes val labelRes: Int) {
    PORTRAIT("", R.string.core_settings_screen_variant_portrait_label),
    LANDSCAPE("landscape", R.string.core_settings_screen_variant_landscape_label),
    PORTRAIT_UNFOLDED("unfolded", R.string.core_settings_screen_variant_portrait_unfolded_label),
    LANDSCAPE_UNFOLDED("landscape_unfolded", R.string.core_settings_screen_variant_landscape_unfolded_label),
    ;

    /** Everything except [PORTRAIT], which is stored as the base values. */
    val isOverride: Boolean get() = this != PORTRAIT

    /**
     * The one shape [applyScreenDefaults] sizes: a *phone* turned sideways.
     *
     * Not [LANDSCAPE_UNFOLDED]. "Unfolded" is decided by the smallest screen
     * dimension, so a tablet sideways is still at least [UNFOLDED_MIN_DP] tall
     * — it has the room, and [applyDeviceForm] has already given it a shorter
     * key than a phone's.
     */
    val wantsLandscapePhoneSizing: Boolean get() = this == LANDSCAPE

    /** Held sideways, folded or not. */
    val isLandscape: Boolean get() = this == LANDSCAPE || this == LANDSCAPE_UNFOLDED

    companion object {
        /** Width in dp at or above which a device counts as unfolded. */
        const val UNFOLDED_MIN_DP = 600

        fun of(landscape: Boolean, unfolded: Boolean): ScreenVariant = when {
            landscape && unfolded -> LANDSCAPE_UNFOLDED
            landscape -> LANDSCAPE
            unfolded -> PORTRAIT_UNFOLDED
            else -> PORTRAIT
        }
    }
}

/**
 * Per-variant sizing. Every field is optional: null means "use the
 * portrait value", so a variant only stores what the user actually changed.
 */
data class SizingOverride(
    val keyHeightDp: Int? = null,
    val numberRowHeightDp: Int? = null,
    val bottomPaddingDp: Int? = null,
    val keyboardWidthPercent: Int? = null,
    val fontScale: Float? = null,
    val keyboardAlignment: KeyboardAlignment? = null,
    /**
     * Whole-keyboard size multiplier for this screen shape: it scales the key
     * and number-row heights together, so a foldable's roomy inner display can
     * run a smaller keyboard than its cramped cover screen (or the reverse)
     * without re-dialling each height by hand. null = 1× (portrait size).
     */
    val keyboardScale: Float? = null,
    /**
     * Gap between keys, as a multiple of the built-in gap. Landscape is the
     * shape that wants this most: the same spacing that reads as comfortable
     * in portrait is what pushes the bottom row off a short screen.
     */
    val keyGapScale: Float? = null,
    /** Space kept clear left of the keys, as a fraction of the width. */
    val sidePadLeftScale: Float? = null,
    /** The same on the right (issue #41). */
    val sidePadRightScale: Float? = null,
    /** Height of the bottom row, which carries the spacebar. */
    val bottomRowHeightDp: Int? = null,
    /**
     * Toolbar and suggestion strip at full width over keys narrowed by width
     * and side pads; see [LayoutBehaviorSettings.fullWidthBars]. A tablet in
     * landscape is the shape that usually wants it, and a phone upright the
     * one that does not.
     */
    val fullWidthBars: Boolean? = null,
    /**
     * Height of the toolbar row on this shape. Unset, the base height applies,
     * scaled to 70% on a landscape phone (see [applyScreenDefaults]); set, this
     * number wins outright, theme included.
     */
    val toolbarHeightDp: Int? = null,
    /**
     * Whether the dedicated digit row is drawn on this shape.
     *
     * The one sizing choice that is not a number, and the one a landscape
     * phone most often wants to answer differently: it has the least room for
     * a sixth row and the most need for the keys below it. null follows
     * portrait, as everywhere else here.
     */
    val numberRow: Boolean? = null,
    /**
     * Whether one-handed mode may show on this shape (#611), or null for the
     * shared rule: everywhere, except sideways while "Portrait only" is on. A
     * gate, not a second switch: the mode itself stays the one setting, so the
     * toolbar tool and the rail keep driving it on every shape that allows it.
     */
    val allowOneHanded: Boolean? = null,
    /**
     * The same for the split keyboard (#611): null follows "Split only on wide
     * screens". An unfolded portrait screen is the usual one to answer apart,
     * where one-handed and split together are one too many.
     */
    val allowSplit: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = keyHeightDp == null && numberRowHeightDp == null &&
            bottomPaddingDp == null && keyboardWidthPercent == null &&
            fontScale == null && keyboardAlignment == null && keyboardScale == null &&
            keyGapScale == null && sidePadLeftScale == null && sidePadRightScale == null &&
            bottomRowHeightDp == null && numberRow == null &&
            allowOneHanded == null && allowSplit == null &&
            fullWidthBars == null && toolbarHeightDp == null
}

/**
 * Whether split may show on [variant] (#611): the shape's own answer, else
 * the shared one, which holds it back only on a folded phone held upright
 * while "Split only on wide screens" is on.
 */
fun KeyboardSettings.splitAllowedOn(variant: ScreenVariant): Boolean =
    sizingOverrides[variant]?.allowSplit
        ?: (!layoutBehavior.splitOnlyOnLargeScreens || variant != ScreenVariant.PORTRAIT)

/**
 * Whether one-handed mode may show on [variant] (#611): the shape's own
 * answer, else the shared "Portrait only" rule (#503).
 */
fun KeyboardSettings.oneHandedAllowedOn(variant: ScreenVariant): Boolean =
    sizingOverrides[variant]?.allowOneHanded
        ?: !(variant.isLandscape && oneHanded.portraitOnly)

/**
 * The settings as a screen shape would have shipped them, before the user, the
 * theme or a per-shape override has said anything.
 *
 * Only landscape has anything to say, and it is the shape that needs it. A
 * phone turned sideways keeps its width and loses more than half its height: a
 * 362dp-tall window against the 750dp it had upright. The portrait board — six
 * rows of 48dp keys with 4dp of air above and below each — is about 465dp tall,
 * so it did not merely look cramped there, it did not fit at all. The bottom
 * rows were laid out past the window's edge and clipped away, which is issue
 * #251.
 *
 * ## The numbers
 *
 * Measured off Gboard on the same screen, which is the bar users judge this
 * against: a 32.5dp key face on a 41dp row pitch, four rows, no digit row, for
 * a board of roughly 202dp — a little over half the window. [KeyHeightDp] of 33
 * on this keyboard's own 4dp gap is the same 41dp pitch, and the digit row's
 * height is scaled by the same ratio for the users who keep it.
 *
 * ## Where it sits in the overlay chain
 *
 * First, beside [applyDeviceForm] and before everything else:
 * `applyDeviceForm` → `applyScreenDefaults` → `applyMode` →
 * [applyThemeOverrides] → [resolvedFor]. Being first is what makes these
 * defaults rather than overrides — a theme's authored key height beats them,
 * and a number the user set for this shape beats that. `ThemeOverridesTest`
 * pins the ordering.
 *
 * ## What is gated and what is not
 *
 * The heights are not gated on the user having left portrait alone, which is
 * where this parts company with [applyDeviceForm]. A tablet is a
 * normal-proportioned screen and a user who dialled in a key height meant it
 * there; a landscape phone is short whatever anyone prefers upright, and
 * carrying a 64dp portrait key across would put the board back through the
 * bottom of the window. The per-shape editor is where they say otherwise.
 *
 * The digit row is gated, because it is a row the user asked for rather than a
 * size the screen dictates. Someone who turned digits on means them sideways
 * too; someone who never touched the switch gets Gboard's landscape, which is
 * four rows.
 *
 * The toolbar is scaled rather than replaced, for a third reason again: unlike
 * the key heights it has no per-shape override to escape to, so a number the
 * user picked has to survive the rotation in proportion instead of being
 * overwritten by one of ours.
 */
fun KeyboardSettings.applyScreenDefaults(variant: ScreenVariant): KeyboardSettings {
    if (!variant.wantsLandscapePhoneSizing) return this
    val digits = if (layoutBehavior.numberRowUntouched) false else numberRow
    val barHeight = (toolbarHeightDp * LandscapeChromeScale).roundToInt()
    val toolWidth = (toolbarBehavior.toolWidthDp * LandscapeChromeScale).roundToInt()
    // Hand back the same instance when there is nothing to say: the service
    // caches this and the composable remembers on it by identity, so a
    // fresh-but-equal copy would re-resolve the theme and the screen variant on
    // every unrelated preference write. [applyDeviceForm]'s bargain exactly.
    val boardSized = keyHeightDp == LandscapeKeyHeightDp &&
        numberRowHeightDp == LandscapeNumberRowHeightDp
    val chromeSized = barHeight == toolbarHeightDp &&
        toolWidth == toolbarBehavior.toolWidthDp
    if (boardSized && chromeSized && digits == numberRow) return this
    return copy(
        keyHeightDp = LandscapeKeyHeightDp,
        numberRowHeightDp = LandscapeNumberRowHeightDp,
        numberRow = digits,
        toolbarHeightDp = barHeight,
        toolbarBehavior = toolbarBehavior.copy(toolWidthDp = toolWidth),
    )
}

/** Gboard's landscape key face, to the dp; see [applyScreenDefaults]. */
private const val LandscapeKeyHeightDp = 33

/** The digit row, kept in proportion for the users who switch it back on. */
private const val LandscapeNumberRowHeightDp = 29

/**
 * How much of its upright size the bar chrome keeps sideways: [LandscapeKeyHeightDp]
 * over the 48dp key it replaces, near enough.
 *
 * The toolbar is sized in dp of its own and so did not shrink with the board.
 * At the shipped numbers that left a 38dp tool circle sitting on a bar above a
 * 33dp key — chrome drawn larger than the keys it serves, which is the one
 * proportion a keyboard cannot get away with. Upright the same circle is 38dp
 * against a 48dp key and reads correctly, which is why this only shows sideways.
 */
private const val LandscapeChromeScale = 0.7f

/**
 * The settings as they apply on [variant]: the base values with that
 * variant's overrides layered on top.
 *
 * Returning a whole [KeyboardSettings] rather than a separate sizing object
 * is deliberate — the keyboard resolves once where it knows the screen
 * shape, and every `settings.keyHeightDp` downstream keeps working without
 * knowing variants exist at all.
 */
fun KeyboardSettings.resolvedFor(variant: ScreenVariant): KeyboardSettings {
    // Split and one-handed are gated on the shape rather than switched per
    // shape: the user set one switch each, and this decides whether the
    // current screen honours it (#611). The answer is folded into the plain
    // fields, so the frame never learns there are gates.
    val splitHere = splitKeyboard && splitAllowedOn(variant)
    val oneHandedHere = if (oneHandedAllowedOn(variant)) oneHandedMode else OneHandedMode.OFF
    // "Portrait only" is already answered above, and a shape that allows
    // one-handed sideways must not have it taken back by oneHandedModeFor.
    val oneHandedSettings = if (oneHanded.portraitOnly) oneHanded.copy(portraitOnly = false) else oneHanded
    // Floating is remembered per orientation (#584): the field downstream reads
    // is set to the answer for this one, so the frame never learns there are two.
    val floatingHere = floatingFor(variant.isLandscape)
    val override = sizingOverrides[variant]
    if (override == null || override.isEmpty) {
        return if (
            splitHere == splitKeyboard && floatingHere == floatingKeyboard &&
            oneHandedHere == oneHandedMode && oneHandedSettings === oneHanded
        ) {
            this
        } else {
            copy(
                splitKeyboard = splitHere,
                floatingKeyboard = floatingHere,
                oneHandedMode = oneHandedHere,
                oneHanded = oneHandedSettings,
            )
        }
    }
    // The scale rides on the resolved heights, so every `settings.keyHeightDp`
    // downstream is already scaled and no render code learns it exists.
    val scale = override.keyboardScale ?: 1f
    return copy(
        keyHeightDp = ((override.keyHeightDp ?: keyHeightDp) * scale).roundToInt(),
        numberRowHeightDp = ((override.numberRowHeightDp ?: numberRowHeightDp) * scale).roundToInt(),
        bottomPaddingDp = override.bottomPaddingDp ?: bottomPaddingDp,
        keyboardWidthPercent = override.keyboardWidthPercent ?: keyboardWidthPercent,
        fontScale = override.fontScale ?: fontScale,
        keyboardAlignment = override.keyboardAlignment ?: keyboardAlignment,
        numberRow = override.numberRow ?: numberRow,
        toolbarHeightDp = override.toolbarHeightDp ?: toolbarHeightDp,
        // These live on the nested layout-behaviour object, so the override
        // has to rebuild it rather than name a top-level field.
        layoutBehavior = if (
            override.sidePadLeftScale == null && override.sidePadRightScale == null &&
            override.bottomRowHeightDp == null && override.fullWidthBars == null
        ) {
            layoutBehavior
        } else {
            layoutBehavior.copy(
                sidePadLeftScale = override.sidePadLeftScale ?: layoutBehavior.sidePadLeftScale,
                sidePadRightScale = override.sidePadRightScale ?: layoutBehavior.sidePadRightScale,
                bottomRowHeightDp = override.bottomRowHeightDp ?: layoutBehavior.bottomRowHeightDp,
                fullWidthBars = override.fullWidthBars ?: layoutBehavior.fullWidthBars,
            )
        },
        keyGapScale = override.keyGapScale ?: keyGapScale,
        splitKeyboard = splitHere,
        floatingKeyboard = floatingHere,
        oneHandedMode = oneHandedHere,
        oneHanded = oneHandedSettings,
    )
}

/**
 * The values [variant] currently resolves to, for showing in its editor.
 *
 * Deliberately built from the raw base + override rather than [resolvedFor]:
 * the editor shows the stored key height and the scale as separate numbers, so
 * it must not fold the scale into the height the way the render path does.
 */
fun KeyboardSettings.sizingValuesFor(variant: ScreenVariant): SizingOverride {
    // Read off the shape-defaulted base, not the raw one, or the editor would
    // show a landscape phone the portrait numbers while the board is drawn at
    // the landscape ones (see [applyScreenDefaults]).
    val base = applyScreenDefaults(variant)
    val override = sizingOverrides[variant]
    return SizingOverride(
        keyHeightDp = override?.keyHeightDp ?: base.keyHeightDp,
        numberRowHeightDp = override?.numberRowHeightDp ?: base.numberRowHeightDp,
        bottomPaddingDp = override?.bottomPaddingDp ?: base.bottomPaddingDp,
        keyboardWidthPercent = override?.keyboardWidthPercent ?: base.keyboardWidthPercent,
        fontScale = override?.fontScale ?: base.fontScale,
        keyboardAlignment = override?.keyboardAlignment ?: base.keyboardAlignment,
        keyboardScale = override?.keyboardScale ?: 1f,
        keyGapScale = override?.keyGapScale ?: base.keyGapScale,
        sidePadLeftScale = override?.sidePadLeftScale ?: base.layoutBehavior.sidePadLeftScale,
        sidePadRightScale = override?.sidePadRightScale ?: base.layoutBehavior.sidePadRightScale,
        bottomRowHeightDp = override?.bottomRowHeightDp ?: base.layoutBehavior.bottomRowHeightDp,
        fullWidthBars = override?.fullWidthBars ?: base.layoutBehavior.fullWidthBars,
        toolbarHeightDp = override?.toolbarHeightDp ?: base.toolbarHeightDp,
        numberRow = override?.numberRow ?: base.numberRow,
        allowOneHanded = oneHandedAllowedOn(variant),
        allowSplit = splitAllowedOn(variant),
    )
}
