package com.wasimaster.wmkeyboard.core.theme

import kotlinx.serialization.Serializable

/**
 * Variable-font axis values, by OpenType tag (`wght` to 650, `wdth` to 85,
 * `GRAD` to 50…), for the four places text is drawn on the board.
 *
 * [keys] is the key labels. [hints] (the corner hints and flick arms),
 * [strip] (the suggestion strip) and [popup] (the preview bubble and the
 * long-press alternates) follow [keys] while null, so a font tuned once looks
 * the same everywhere until one of them is pulled apart.
 *
 * The same shape serves the user's own tuning (per font id, in the keyboard
 * settings) and a theme's (see [ThemeSpec.fontAxes]). Values are not checked
 * against any font here: the font is known only where it is drawn, and that
 * is where they are held to its axes and anything it has no axis for is
 * dropped. So a theme written for Roboto Flex keeps its numbers on a phone
 * where it falls back to a static face, and simply draws that face.
 */
@Serializable
data class FontAxesSpec(
    val keys: Map<String, Float> = emptyMap(),
    val hints: Map<String, Float>? = null,
    val strip: Map<String, Float>? = null,
    val popup: Map<String, Float>? = null,
) {
    val isEmpty: Boolean
        get() = keys.isEmpty() && hints.isNullOrEmpty() && strip.isNullOrEmpty() && popup.isNullOrEmpty()

    fun role(role: FontRole): Map<String, Float> = when (role) {
        FontRole.KEYS -> keys
        FontRole.HINTS -> hints ?: keys
        FontRole.STRIP -> strip ?: keys
        FontRole.POPUP -> popup ?: keys
    }

    /** Whether [role] has values of its own rather than following [keys]. */
    fun ownsRole(role: FontRole): Boolean = when (role) {
        FontRole.KEYS -> true
        FontRole.HINTS -> hints != null
        FontRole.STRIP -> strip != null
        FontRole.POPUP -> popup != null
    }

    /** [values] as [role]'s; null puts a role other than [FontRole.KEYS] back on following the keys. */
    fun withRole(role: FontRole, values: Map<String, Float>?): FontAxesSpec = when (role) {
        FontRole.KEYS -> copy(keys = values.orEmpty())
        FontRole.HINTS -> copy(hints = values)
        FontRole.STRIP -> copy(strip = values)
        FontRole.POPUP -> copy(popup = values)
    }
}

/** Where on the board a set of axis values is drawn; see [FontAxesSpec]. */
enum class FontRole { KEYS, HINTS, STRIP, POPUP }

/**
 * Parses `'wght' 700, "wdth" 80` (the rule sheet's `font-variation`, and
 * the form Android's `setFontVariationSettings` takes) back into a map. Anything that is not a quoted
 * four-character tag followed by a number is skipped.
 */
fun parseAxesCss(text: String): Map<String, Float> {
    val out = LinkedHashMap<String, Float>()
    for (part in text.split(',')) {
        val trimmed = part.trim()
        if (trimmed.length < 7) continue
        val quote = trimmed[0]
        if (quote != '\'' && quote != '"') continue
        val close = trimmed.indexOf(quote, 1)
        if (close != 5) continue
        val tag = trimmed.substring(1, 5)
        val value = trimmed.substring(6).trim().toFloatOrNull() ?: continue
        if (value.isFinite()) out[tag] = value
    }
    return out
}

// ---- font motion ------------------------------------------------------------

/**
 * What moves a theme's font. Each gives every key label a level from 0 to 1,
 * and the label draws its axes that far from rest toward the motion's peak.
 */
enum class FontMotionTrigger {
    /** One of the key-lighting patterns ([KeyLightingPattern]), idle or answering presses. */
    PATTERN,
    /** The letters near a pressing finger swell and settle back as it lifts. */
    TOUCH,
    /** The letters near a gliding finger swell and settle back as it passes. */
    GLIDE,
    /** Every label eases to the peak while Shift or Caps Lock is on. */
    SHIFT,
    /** The whole board leans toward the peak the faster the typing, settling when it stops. */
    ENERGY,
}

/** Which labels a motion moves. */
enum class FontMotionTarget {
    /** The keys that type a character. */
    LETTERS,
    /** Every key with a text label, the function keys' words included. */
    ALL,
}

/**
 * One way a theme's font moves; see [ThemeSpec.fontMotions].
 *
 * [axes] are the *peak* values, by tag, for the axes the theme author picked:
 * an axis left out does not move. Rest is whatever the label draws at
 * otherwise (the user's tuning, then the theme's [ThemeSpec.fontAxes], then
 * the font's own default). Strings for the enums for the usual forward-compat
 * reason: a trigger from a later build costs that one motion, not the theme.
 */
@Serializable
data class FontMotionSpec(
    /** A [FontMotionTrigger] name. */
    val trigger: String,
    /** A [KeyLightingPattern] name, for [FontMotionTrigger.PATTERN]. */
    val pattern: String? = null,
    val axes: Map<String, Float> = emptyMap(),
    /** A [FontMotionTarget] name; null is [FontMotionTarget.LETTERS]. */
    val target: String? = null,
    /** How fast an idle pattern runs; see [FONT_MOTION_SPEED_RANGE]. */
    val speed: Float = 1f,
    /** How far from the finger the swell reaches, for touch and glide. */
    val radiusDp: Float = 96f,
    /** How long a press, a passing finger or a burst of typing takes to settle. */
    val decayMs: Int = 500,
    /** How far past rest the label rings on the way back, 0..1; 0 settles with no overshoot. */
    val wobble: Float = 0.3f,
    /**
     * How big the label draws at the motion's peak, 1 for its own size; see
     * [FONT_MOTION_SCALE_RANGE]. Moves smoothly between the font's cuts, so a
     * swell reads as one growing letter rather than a run of weights.
     */
    val scale: Float = 1f,
)

const val MAX_FONT_MOTIONS = 4
val FONT_MOTION_SPEED_RANGE = 0.25f..4f
val FONT_MOTION_RADIUS_RANGE = 24f..240f
val FONT_MOTION_DECAY_RANGE = 100..2400
val FONT_MOTION_WOBBLE_RANGE = 0f..1f
val FONT_MOTION_SCALE_RANGE = 0.5f..2f

fun fontMotionTriggerOrNull(name: String?): FontMotionTrigger? =
    FontMotionTrigger.entries.firstOrNull { it.name == name }

fun fontMotionTargetOrDefault(name: String?): FontMotionTarget =
    FontMotionTarget.entries.firstOrNull { it.name == name } ?: FontMotionTarget.LETTERS
