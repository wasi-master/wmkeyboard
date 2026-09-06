package com.wasimaster.wmkeyboard.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool

/**
 * A distinct accent colour per tool, used to tint the tool icons in the
 * Settings app and the keyboard's toolbox when
 * [com.wasimaster.wmkeyboard.core.settings.KeyboardSettings.coloredToolIcons]
 * is on. Related tools share a hue on purpose (all cursor keys are the same
 * neutral, all media tools are warm) so the grid reads as grouped rather than
 * a random confetti of colour, while still being scannable at a glance.
 *
 * Mid-tone values (~Material 400/500) that stay legible on both the light and
 * dark toolbar/circle backgrounds. Exhaustive so a new tool must pick a
 * colour, matching the icon/label maps.
 */
fun toolAccentColor(tool: ToolbarTool): Color = when (tool) {
    // Expressive / media — warm
    ToolbarTool.EMOJI -> Color(0xFFFFB300)
    ToolbarTool.GIF -> Color(0xFFFF7043)
    ToolbarTool.STICKER -> Color(0xFFFFA726)
    ToolbarTool.CAMERA -> Color(0xFFF06292)
    ToolbarTool.THEMES -> Color(0xFFEC407A)

    // Text & clipboard — blues/teals
    ToolbarTool.CLIPBOARD -> Color(0xFF42A5F5)
    // The one-tap trio reads as the clipboard panel's own family (issue #41).
    ToolbarTool.COPY, ToolbarTool.CUT, ToolbarTool.PASTE -> Color(0xFF42A5F5)
    ToolbarTool.SNIPPETS -> Color(0xFF26A69A)
    ToolbarTool.TEXT_EDIT -> Color(0xFF5C6BC0)
    ToolbarTool.TRACKPAD -> Color(0xFF7986CB)
    ToolbarTool.DICTIONARY -> Color(0xFF26A69A)
    ToolbarTool.VOCABULARY -> Color(0xFF8E24AA)
    ToolbarTool.HANDWRITING -> Color(0xFF7E57C2)
    ToolbarTool.NUMPAD -> Color(0xFF42A5F5)
    ToolbarTool.SYMBOLS -> Color(0xFF7E57C2)
    ToolbarTool.FANCY -> Color(0xFF9575CD)
    ToolbarTool.CUSTOM_LAYOUT -> Color(0xFF5C6BC0)

    // Language help — greens
    ToolbarTool.AUTOCORRECT -> Color(0xFF66BB6A)
    ToolbarTool.GRAMMAR -> Color(0xFF66BB6A)
    ToolbarTool.TRANSLATE -> Color(0xFF29B6F6)

    // Voice / sound — reds/purples
    ToolbarTool.VOICE -> Color(0xFFEF5350)
    ToolbarTool.SOUND_HAPTICS -> Color(0xFFAB47BC)

    // Online / search — blues/cyans
    ToolbarTool.WEB_SEARCH -> Color(0xFF42A5F5)
    ToolbarTool.IMAGE_SEARCH -> Color(0xFF26C6DA)
    ToolbarTool.WIKIPEDIA -> Color(0xFF78909C)
    ToolbarTool.AI -> Color(0xFFAB47BC)

    // Scanners — teals/greens
    ToolbarTool.OCR -> Color(0xFF66BB6A)
    ToolbarTool.QR_SCAN -> Color(0xFF26A69A)
    ToolbarTool.DOC_SCAN -> Color(0xFF29B6F6)

    // Create & convert
    ToolbarTool.CALCULATOR -> Color(0xFFFFA726)
    ToolbarTool.UNIT_CONVERT -> Color(0xFF26C6DA)
    ToolbarTool.CURRENCY -> Color(0xFF66BB6A)
    ToolbarTool.QR_GEN -> Color(0xFF26A69A)
    ToolbarTool.PASSWORD_GEN -> Color(0xFFEF5350)
    ToolbarTool.TYPING_TEST -> Color(0xFFFF7043)
    ToolbarTool.MEDIA_CONTROL -> Color(0xFFAB47BC)
    ToolbarTool.PLUGINS -> Color(0xFF06B6D4)
    ToolbarTool.APP_LAUNCHER -> Color(0xFF7E57C2)

    // Keyboard modes / layout — indigos & neutrals
    ToolbarTool.MODES -> Color(0xFF5C6BC0)
    ToolbarTool.ONE_HANDED -> Color(0xFF78909C)
    ToolbarTool.SPLIT -> Color(0xFF78909C)
    ToolbarTool.FLOATING -> Color(0xFF78909C)
    ToolbarTool.RESIZE -> Color(0xFF78909C)
    ToolbarTool.INCOGNITO -> Color(0xFF607D8B)
    ToolbarTool.POWER_SAVING -> Color(0xFF66BB6A)
    ToolbarTool.SETTINGS -> Color(0xFF90A4AE)
    ToolbarTool.UNDO -> Color(0xFF78909C)
    ToolbarTool.REDO -> Color(0xFF78909C)

    // Sensors / utilities
    ToolbarTool.FLASHLIGHT -> Color(0xFFFFCA28)
    ToolbarTool.COMPASS -> Color(0xFFEF5350)
    ToolbarTool.LEVEL -> Color(0xFF26C6DA)
    ToolbarTool.CALENDAR -> Color(0xFFE57373)
    ToolbarTool.WEATHER -> Color(0xFFFFA726)
    ToolbarTool.MOON_PHASE -> Color(0xFF7986CB)

    // Cursor keys — one neutral so they read as a set
    ToolbarTool.CURSOR_LEFT,
    ToolbarTool.CURSOR_RIGHT,
    ToolbarTool.CURSOR_WORD_LEFT,
    ToolbarTool.CURSOR_WORD_RIGHT,
    ToolbarTool.CURSOR_UP,
    ToolbarTool.CURSOR_DOWN,
    ToolbarTool.CURSOR_HOME,
    ToolbarTool.CURSOR_END,
    ToolbarTool.PAGE_UP,
    ToolbarTool.PAGE_DOWN,
    ToolbarTool.SELECT_WORD,
    ToolbarTool.SELECT_LINE,
    ToolbarTool.SELECT_MODE -> Color(0xFF90A4AE)

    ToolbarTool.HIDE_KEYBOARD -> Color(0xFF607D8B)
}

/**
 * The accent colour to actually paint a tool with: the user's per-tool
 * override from [com.wasimaster.wmkeyboard.core.settings.KeyboardSettings.toolColorOverrides]
 * when they've set one, otherwise the built-in [toolAccentColor] default.
 */
fun toolAccentColor(tool: ToolbarTool, overrides: Map<ToolbarTool, Long>): Color =
    overrides[tool]?.let { Color(it.toInt()) } ?: toolAccentColor(tool)

/** The default accent colour as a stored ARGB long (for seeding the picker). */
fun toolAccentColorArgb(tool: ToolbarTool): Long =
    toolAccentColor(tool).toArgb().toLong() and 0xFFFFFFFFL

// ---- gradients ----

/*
 * With "Gradient tool colours" on, a tool is painted with two colours rather
 * than one: [toolAccentColor] at the top left, this at the bottom right. The
 * second colour has its own per-tool override, and its default is derived from
 * the first rather than listed, so a tool that only ever gets one colour picked
 * for it — including any tool added later — still gets a gradient that belongs
 * to it instead of one hand-chosen pair the two colours have to be kept in
 * sync with.
 */

/** How far round the wheel the derived end colour sits from the start, in degrees. */
private const val GRADIENT_HUE_SHIFT = 34f

/** How much brighter and more saturated the far end is. */
private const val GRADIENT_VALUE_LIFT = 0.10f
private const val GRADIENT_SATURATION_LIFT = 0.06f

/**
 * The far end of a tool's gradient when the user has not picked one.
 *
 * A rotation rather than a lightening, because two tints of one hue read as a
 * lighting artifact and not as a gradient — but a small one, so the tool is
 * still recognisably the colour it was, and always forwards round the wheel so
 * that neighbouring tools drift the same way rather than towards each other.
 */
fun toolAccentEndColor(tool: ToolbarTool): Color = shiftForGradient(toolAccentColor(tool))

/**
 * The far end of a tool's gradient: the user's own override where there is
 * one, otherwise [toolAccentEndColor] derived from whatever the near end
 * currently is — so recolouring a tool moves both ends of its gradient
 * together until the user pins the far one down.
 */
fun toolAccentEndColor(
    tool: ToolbarTool,
    starts: Map<ToolbarTool, Long>,
    ends: Map<ToolbarTool, Long>,
): Color = ends[tool]?.let { Color(it.toInt()) } ?: shiftForGradient(toolAccentColor(tool, starts))

/** [toolAccentEndColor] as a stored ARGB long (for seeding the picker). */
fun toolAccentEndColorArgb(tool: ToolbarTool, starts: Map<ToolbarTool, Long>): Long =
    shiftForGradient(toolAccentColor(tool, starts)).toArgb().toLong() and 0xFFFFFFFFL

private fun shiftForGradient(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + GRADIENT_HUE_SHIFT) % 360f
    hsv[1] = (hsv[1] + GRADIENT_SATURATION_LIFT).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] + GRADIENT_VALUE_LIFT).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * The brush a gradient tool icon is painted with: top left to bottom right of
 * whatever it is drawn into. [Offset.Infinite] is Compose's "the far corner of
 * the drawing area", so one brush works at every icon size without being told
 * how big the icon is.
 */
fun toolAccentBrush(start: Color, end: Color): Brush =
    Brush.linearGradient(listOf(start, end), start = Offset.Zero, end = Offset.Infinite)

/**
 * How a tool is painted: [color] always, and [endColor] as well when the
 * gradients are on.
 *
 * Both ends are kept rather than only the finished [brush], because a surface
 * that wants the gradient in another form — darkened for a light background,
 * say — cannot take a [Brush] apart again. Not everything that wears a tool's
 * colour can take a brush at all (a text label, a contrast calculation, the
 * tint on a full-colour icon from a pack); those use [color], the near end.
 */
@Immutable
data class ToolPaint(val color: Color, val endColor: Color?) {
    /** The gradient to paint with, or null when this tool is painted flat. */
    val brush: Brush? = endColor?.let { toolAccentBrush(color, it) }

    /** The same paint with both ends put through [transform]. */
    inline fun map(transform: (Color) -> Color): ToolPaint =
        ToolPaint(transform(color), endColor?.let(transform))
}

/*
 * Reading the paint straight off the settings needs `KeyboardSettings`, which
 * lives a layer above this one — so `toolAccentPaint` is in `:core:settings`,
 * in this same package.
 */
