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
     * Whether the dedicated digit row is drawn on this shape.
     *
     * The one sizing choice that is not a number, and the one a landscape
     * phone most often wants to answer differently: it has the least room for
     * a sixth row and the most need for the keys below it. null follows
     * portrait, as everywhere else here.
     */
    val numberRow: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = keyHeightDp == null && numberRowHeightDp == null &&
            bottomPaddingDp == null && keyboardWidthPercent == null &&
            fontScale == null && keyboardAlignment == null && keyboardScale == null &&
            keyGapScale == null && sidePadLeftScale == null && sidePadRightScale == null &&
            bottomRowHeightDp == null && numberRow == null
}

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
    // Split is gated on the shape rather than overridden per shape: the user
    // set one switch, and this decides whether the current screen is wide
    // enough to honour it. Portrait on a folded phone is the one that is not.
    val splitHere = splitKeyboard &&
        (!layoutBehavior.splitOnlyOnLargeScreens || variant != ScreenVariant.PORTRAIT)
    val override = sizingOverrides[variant]
    if (override == null || override.isEmpty) {
        return if (splitHere == splitKeyboard) this else copy(splitKeyboard = splitHere)
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
        // These three live on the nested layout-behaviour object, so the
        // override has to rebuild it rather than name a top-level field.
        layoutBehavior = if (
            override.keyGapScale == null && override.sidePadLeftScale == null &&
            override.sidePadRightScale == null && override.bottomRowHeightDp == null
        ) {
            layoutBehavior
        } else {
            layoutBehavior.copy(
                sidePadLeftScale = override.sidePadLeftScale ?: layoutBehavior.sidePadLeftScale,
                sidePadRightScale = override.sidePadRightScale ?: layoutBehavior.sidePadRightScale,
                bottomRowHeightDp = override.bottomRowHeightDp ?: layoutBehavior.bottomRowHeightDp,
            )
        },
        keyGapScale = override.keyGapScale ?: keyGapScale,
        splitKeyboard = splitHere,
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
    val override = sizingOverrides[variant]
    return SizingOverride(
        keyHeightDp = override?.keyHeightDp ?: keyHeightDp,
        numberRowHeightDp = override?.numberRowHeightDp ?: numberRowHeightDp,
        bottomPaddingDp = override?.bottomPaddingDp ?: bottomPaddingDp,
        keyboardWidthPercent = override?.keyboardWidthPercent ?: keyboardWidthPercent,
        fontScale = override?.fontScale ?: fontScale,
        keyboardAlignment = override?.keyboardAlignment ?: keyboardAlignment,
        keyboardScale = override?.keyboardScale ?: 1f,
        keyGapScale = override?.keyGapScale ?: keyGapScale,
        sidePadLeftScale = override?.sidePadLeftScale ?: layoutBehavior.sidePadLeftScale,
        sidePadRightScale = override?.sidePadRightScale ?: layoutBehavior.sidePadRightScale,
        bottomRowHeightDp = override?.bottomRowHeightDp ?: layoutBehavior.bottomRowHeightDp,
        numberRow = override?.numberRow ?: numberRow,
    )
}
