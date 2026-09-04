package com.wasimaster.wmkeyboard.ime.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.wasimaster.wmkeyboard.core.emoji.EmojiFontShaping
import com.wasimaster.wmkeyboard.core.settings.withRotation
import com.wasimaster.wmkeyboard.core.settings.rotates
import com.wasimaster.wmkeyboard.core.settings.RotationState
import com.wasimaster.wmkeyboard.core.settings.AutoThemeTrigger
import com.wasimaster.wmkeyboard.core.settings.ColorVisionFilter
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ThemeMode
import com.wasimaster.wmkeyboard.core.settings.activeThemeSpec
import com.wasimaster.wmkeyboard.core.settings.effectiveThemeId
import com.wasimaster.wmkeyboard.core.tools.SolarCalculator
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.theme.ColorVision
import com.wasimaster.wmkeyboard.core.theme.GradientSpec
import com.wasimaster.wmkeyboard.core.theme.DecalSpec
import com.wasimaster.wmkeyboard.core.theme.KeyEffectKind
import com.wasimaster.wmkeyboard.core.theme.KeyOverride
import com.wasimaster.wmkeyboard.core.theme.keyEffectKindOrNull
import com.wasimaster.wmkeyboard.core.theme.KeyShapeKind
import com.wasimaster.wmkeyboard.core.theme.MAX_DECALS
import com.wasimaster.wmkeyboard.core.theme.MAX_EFFECT_IMAGES
import com.wasimaster.wmkeyboard.core.theme.KeyTextureScale
import com.wasimaster.wmkeyboard.core.theme.ThemeAnimation
import com.wasimaster.wmkeyboard.core.theme.ThemeBackgroundImage
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.brush
import com.wasimaster.wmkeyboard.core.theme.castsElevationShadow
import com.wasimaster.wmkeyboard.core.theme.hueShift
import com.wasimaster.wmkeyboard.core.theme.keyShapeFor
import com.wasimaster.wmkeyboard.core.theme.keyShapeKindOrNull
import com.wasimaster.wmkeyboard.core.theme.keyTextureScaleOrDefault
import com.wasimaster.wmkeyboard.core.theme.popupOnKeyOrNull
import com.wasimaster.wmkeyboard.core.theme.safeContainerKind
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.delay

/**
 * Fully resolved keyboard theme: every color the keyboard UI draws with,
 * plus the effective radii. Nullable [ThemeSpec] fields have been collapsed
 * into derived values by [resolveKbTheme].
 */
data class KbTheme(
    val dark: Boolean,
    val board: Color,
    val boardGradient: GradientSpec?,
    val backgroundImage: String?,
    /** Landscape override for [backgroundImage]; null falls back to it. */
    val backgroundImageLandscape: String?,
    val backgroundImageOpacity: Float,
    val backgroundImageBlur: Float,
    /** Play the background as an animated image; see [ThemeSpec.backgroundAnimated]. */
    val backgroundAnimated: Boolean,
    val keyShapeKind: KeyShapeKind,
    val keyGradient: GradientSpec?,
    /** Image drawn over the letter-key colour, clipped to the key shape. */
    val keyTexture: String?,
    /** Modifier-key texture; null falls back to [keyTexture]. */
    val keyTextureModifier: String?,
    /** Enter-key texture; null falls back to [keyTextureModifier]'s chain. */
    val keyTextureEnter: String?,
    /** Space-bar texture; null falls back to [keyTexture]. */
    val keyTextureSpace: String?,
    /** Texture drawn while a key is held; null keeps the pressed colour. */
    val keyTexturePressed: String?,
    /** How a texture fits its key. */
    val keyTextureScale: KeyTextureScale,
    /** Alpha the textures draw with. */
    val keyTextureOpacity: Float,
    /** Single-key style overrides, keyed as [ThemeSpec.keyOverrides] is. */
    val keyOverrides: Map<String, KeyOverride>,
    /** Decorative stickers over the key grid. */
    val decals: List<DecalSpec>,
    /** Particle burst on key press; null for none. Gated by [reduceMotion]. */
    val keyEffect: KeyEffectKind?,
    /** Glyphs the EMOJI effect throws. */
    val keyEffectParam: String,
    /** Scales the burst's particle count. */
    val keyEffectIntensity: Float,
    /** Image paths the CUSTOM_IMAGE effect throws. */
    val keyEffectImages: List<String>,
    val key: Color,
    val keyText: Color,
    val modifierKey: Color,
    val modifierKeyText: Color,
    /** Corner-hint colour; null draws the key's label colour at 55% alpha. */
    val hintText: Color?,
    val enterKey: Color,
    val enterKeyText: Color,
    val pressedKey: Color,
    val keyBorder: Color?,
    val keyBorderWidthDp: Float,
    val accent: Color,
    /** Colour of the glide-typing trail; defaults to [accent] when a theme leaves it unset. */
    val gestureTrail: Color,
    val popup: Color,
    val popupText: Color,
    /** Preview-bubble placement override: true on-key, false floating, null follows the setting. */
    val popupOnKey: Boolean?,
    /** Outline around the preview bubble; null draws none. */
    val popupBorder: Color?,
    val popupBorderWidthDp: Float,
    /** Image painted inside the preview bubble, clipped to its shape. */
    val popupTexture: String?,
    val toolbarIcon: Color,
    val toolCircle: Color,
    val toolCircleActive: Color,
    val toolCircleActiveIcon: Color,
    /** Outline around a tool's background; null draws none. */
    val toolBorder: Color?,
    val toolBorderWidthDp: Float,
    val chip: Color,
    /** Text on an unselected chip. */
    val chipText: Color,
    /** A selected chip's fill and its text. */
    val chipActive: Color,
    val chipActiveText: Color,
    /** Outline around every chip; null draws none. */
    val chipBorder: Color?,
    val chipBorderWidthDp: Float,
    val suggestionText: Color,
    val secondaryText: Color,
    val divider: Color,
    val keyRadiusDp: Int,
    val popupRadiusDp: Int,
    val popupShapeKind: KeyShapeKind,
    /** Shape of the list-menu popups; a safe derivative of the popup shape. */
    val menuShapeKind: KeyShapeKind,
    /** Height of the key-preview bubble; a theme may override the setting. */
    val popupHeightDp: Int,
    val toolRadiusDp: Int,
    val toolShapeKind: KeyShapeKind,
    val chipRadiusDp: Int,
    val chipShapeKind: KeyShapeKind,
    /** Shape of the panel cards and search bars; a safe derivative of the chip shape. */
    val cardShapeKind: KeyShapeKind,
    val toolWidthDp: Int,
    val animation: ThemeAnimation,
    val animationSpeed: Float,
    /**
     * The accessibility setting, carried on the theme rather than threaded
     * through every composable that animates. It is not a colour, but neither
     * are the radii or [animation] beside it — this object is already the
     * "how the keyboard presents itself" bundle, and it is the one thing
     * every drawing composable can reach through [LocalKbTheme]. The blinking
     * carets in particular sit four call sites deep in panels that never
     * otherwise see settings.
     */
    val reduceMotion: Boolean,
)

/**
 * The resolved outline every key draws with.
 *
 * [bleedDp] is how far a leaning shape may spill into the gap around the key —
 * pass the horizontal gap where there is one, so a slanted key keeps its full
 * width; leave it at zero where the key has no gap of its own to lean into.
 */
fun KbTheme.keyShape(bleedDp: Float = 0f) = keyShapeFor(keyShapeKind, keyRadiusDp, bleedDp)

/**
 * The resolved outline every popup surface draws with — the preview bubble, the
 * long-press alternates, the language picker and the panel menus.
 */
fun KbTheme.popupShape() = keyShapeFor(popupShapeKind, popupRadiusDp)

/**
 * The resolved outline behind a toolbar tool — the toolbox launcher, every
 * pinned tool, and the two buttons on the floating panel's handle. A radius of
 * 0 still means no background at all, so the shape only shows above it.
 */
fun KbTheme.toolShape() = keyShapeFor(toolShapeKind, toolRadiusDp)

/**
 * The resolved outline behind a chip — the tool-panel buttons, the style
 * strips, the plugin buttons. Defaults to the 12dp soft rectangle every chip
 * drew before a theme could say otherwise.
 */
fun KbTheme.chipShape() = keyShapeFor(chipShapeKind, chipRadiusDp)

/**
 * The resolved outline of the list-menu popups — the language picker, the
 * clipboard and emoji menus. Unless the theme names one, it is the popup
 * shape passed through [safeContainerKind], so a slanted or circular bubble
 * never turns a menu unreadable.
 */
fun KbTheme.menuShape() = keyShapeFor(menuShapeKind, popupRadiusDp)

/**
 * The resolved outline of the panel cards and search bars, derived from the
 * chip shape under the same safety rule as [menuShape].
 */
fun KbTheme.cardShape() = keyShapeFor(cardShapeKind, chipRadiusDp)

/**
 * [elevation], or none when [kind] is an outline that cannot cast a shadow
 * without hanging the RenderThread — see [castsElevationShadow], which is
 * where the reasoning lives. Pass the shape kind the surface is drawing with,
 * not the resolved [Shape]: the decision is about the outline's geometry and a
 * Shape cannot be asked what it is.
 */
fun elevationFor(kind: KeyShapeKind, elevation: Dp): Dp =
    if (castsElevationShadow(kind)) elevation else 0.dp

/**
 * The popup outline a theme asked for, as a Surface border; null draws none.
 * Every popup surface — the preview bubble, the long-press alternates, the
 * language picker, the clip and emoji menus — wears the same one.
 */
fun KbTheme.popupSurfaceBorder(): BorderStroke? =
    popupBorder?.takeIf { popupBorderWidthDp > 0f }
        ?.let { BorderStroke(popupBorderWidthDp.dp, it) }

/** [popupSurfaceBorder] as a modifier, for popup containers that are not Surfaces. */
fun Modifier.popupBorder(kb: KbTheme, shape: Shape): Modifier =
    if (kb.popupBorder != null && kb.popupBorderWidthDp > 0f) {
        border(kb.popupBorderWidthDp.dp, kb.popupBorder, shape)
    } else {
        this
    }

/** The chip outline a theme asked for, or nothing — chips default to none. */
fun Modifier.chipBorder(kb: KbTheme, shape: Shape): Modifier =
    if (kb.chipBorder != null && kb.chipBorderWidthDp > 0f) {
        border(kb.chipBorderWidthDp.dp, kb.chipBorder, shape)
    } else {
        this
    }

/**
 * The key outline a theme asked for, or nothing — so the panel keypads
 * (calculator, numpad, the handwriting and voice rails) match the key grid.
 */
fun Modifier.panelKeyBorder(kb: KbTheme, shape: Shape): Modifier =
    if (kb.keyBorder != null && kb.keyBorderWidthDp > 0f) {
        border(kb.keyBorderWidthDp.dp, kb.keyBorder, shape)
    } else {
        this
    }

/** Added text and deleted text, for the AI tool's comparison view. */
internal data class DiffColors(val added: Color, val deleted: Color)

/**
 * Colours for the AI comparison, legible on this theme.
 *
 * [KbTheme] carries no add/remove pair, so these start from fixed light and
 * dark values — the same approach the grammar panel already takes for its lint
 * categories — and are then pushed until they stand out from the box they are
 * drawn on. That box can be translucent over a photo, so the background is the
 * chip composited onto the board rather than the chip alone.
 *
 * Over an arbitrary photo no colour can be guaranteed, which is exactly why the
 * spans also carry a strikethrough and an underline: the decoration is what
 * says which is which, and the colour is a help rather than the whole signal.
 */
internal fun KbTheme.diffColors(): DiffColors {
    val background = chip.compositeOver(board)
    val added = if (dark) Color(0xFF43C593) else Color(0xFF15845D)
    val deleted = if (dark) Color(0xFFF08A8A) else Color(0xFFC62828)
    return DiffColors(
        added = added.readableOn(background),
        deleted = deleted.readableOn(background),
    )
}

/** Minimum contrast ratio to aim for, the WCAG bar for ordinary text. */
private const val DIFF_MIN_CONTRAST = 4.5f

/**
 * Moves a colour towards white or black, whichever the background is further
 * from, until it is readable on it. Gives up after a bounded number of steps
 * rather than looping: on a mid-grey background nothing reaches the bar, and a
 * colour that is as far as it gets is better than a hang.
 */
private fun Color.readableOn(background: Color): Color {
    val target = if (background.luminance() > 0.5f) Color.Black else Color.White
    var candidate = this
    var step = 0
    while (contrastWith(candidate, background) < DIFF_MIN_CONTRAST && step < 10) {
        candidate = lerp(candidate, target, 0.1f)
        step++
    }
    return candidate
}

private fun contrastWith(a: Color, b: Color): Float {
    val first = a.luminance() + 0.05f
    val second = b.luminance() + 0.05f
    return if (first > second) first / second else second / first
}

val LocalKbTheme = staticCompositionLocalOf<KbTheme> {
    error("LocalKbTheme not provided")
}

/**
 * Emoji font for the keyboard's own emoji rendering (panel, row, strip);
 * null uses the system emoji font. Kept separate from the text font — a
 * display face has no emoji glyphs and vice versa.
 */
val LocalEmojiFontFamily = staticCompositionLocalOf<FontFamily?> { null }

/**
 * Respells an emoji for whatever [LocalEmojiFontFamily] resolves to — see
 * [EmojiShaper]. Only ever applied to text being *drawn*; what is committed to
 * the field is the catalog's own standard spelling.
 */
val LocalEmojiShaper = staticCompositionLocalOf { EmojiFontShaping.Identity }

/**
 * The family to draw [emoji] with: the chosen emoji font, or null — the system
 * font — for the ones that font cannot draw.
 *
 * Letting a font it half-covers draw an emoji is worse than not using it at
 * all: Android keeps the run in the chosen font and, finding no ligature to
 * collapse it with, draws 😶‍🌫️ as a face followed by a separate cloud. One
 * emoji in the system font's style is the better of the two.
 *
 * Pair with `LocalEmojiShaper.current.shape(emoji)` for the text itself; the
 * shaper computes both answers together and caches them.
 */
@Composable
fun emojiFamilyFor(emoji: String): FontFamily? =
    if (LocalEmojiShaper.current.drawsWithSystemFont(emoji)) null else LocalEmojiFontFamily.current

/** Every Material text style re-based onto [family]; null keeps the defaults. */
private fun typographyWith(family: FontFamily?): Typography {
    val base = Typography()
    if (family == null) return base
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}

private fun colorOf(argb: Long): Color = Color(argb.toInt())

/** Blend of [top] at [alpha] flattened over [base] — guaranteed-contrast trick. */
private fun blendOver(top: Color, base: Color, alpha: Float): Color =
    top.copy(alpha = alpha).compositeOver(base)

/** WCAG-style contrast ratio between two (assumed opaque) colors. */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return max(la, lb) / min(la, lb)
}

/**
 * First of [candidates] that actually reads on [background] (≥ 3:1, the
 * large-text/UI-component threshold), else plain black/white. Lets active
 * chips keep the theme's accent personality when it's legible and fall
 * back to guaranteed contrast when it isn't — accent text on an
 * accent-tinted chip was unreadable in most light themes.
 */
private fun legibleOn(background: Color, candidates: List<Color>): Color =
    candidates.firstOrNull { contrastRatio(it, background) >= 3f }
        ?: if (background.luminance() > 0.5f) Color.Black else Color.White

/**
 * The default (system) theme, derived from the Material scheme. In dark
 * mode the key/circle colors are blends of onSurface over a darkened board
 * instead of the scheme's surfaceContainer roles: dynamic dark palettes
 * often flatten those to near-identical tones, which is exactly the washed
 * out look this replaces.
 */
private fun defaultKbTheme(
    scheme: ColorScheme,
    dark: Boolean,
    amoled: Boolean,
    settings: KeyboardSettings,
): KbTheme {
    val board = when {
        amoled -> Color.Black
        dark -> lerp(scheme.surfaceContainerLowest, Color.Black, 0.35f)
        else -> scheme.surfaceContainerLow
    }
    val key = if (dark) blendOver(scheme.onSurface, board, 0.15f) else scheme.surfaceContainerHighest
    val modifier = if (dark) blendOver(scheme.onSurface, board, 0.08f) else scheme.surfaceContainerHigh
    val toolCircle = if (dark) blendOver(scheme.onSurface, board, 0.14f) else scheme.surfaceContainerHighest
    val popup = if (dark) blendOver(scheme.onSurface, board, 0.20f) else scheme.surfaceContainerHighest
    val chip = if (dark) blendOver(scheme.onSurface, board, 0.10f) else scheme.surfaceContainer
    // Pressed keys darken/lighten neutrally instead of flashing the accent
    // (primaryContainer) — a themed highlight on every keystroke reads as
    // noise, a grayscale shift reads as depression.
    val pressed = if (dark) blendOver(scheme.onSurface, board, 0.32f)
        else blendOver(scheme.onSurface, key, 0.14f)
    return KbTheme(
        dark = dark,
        board = board,
        boardGradient = null,
        backgroundImage = null,
        backgroundImageLandscape = null,
        backgroundImageOpacity = 1f,
        backgroundImageBlur = 0f,
        backgroundAnimated = false,
        keyShapeKind = KeyShapeKind.ROUNDED,
        keyGradient = null,
        keyTexture = null,
        keyTextureModifier = null,
        keyTextureEnter = null,
        keyTextureSpace = null,
        keyTexturePressed = null,
        keyTextureScale = KeyTextureScale.CROP,
        keyTextureOpacity = 1f,
        keyOverrides = emptyMap(),
        decals = emptyList(),
        keyEffect = null,
        keyEffectParam = "",
        keyEffectIntensity = 1f,
        keyEffectImages = emptyList(),
        key = key,
        keyText = scheme.onSurface,
        modifierKey = modifier,
        modifierKeyText = scheme.onSurface,
        hintText = null,
        enterKey = scheme.primary,
        enterKeyText = scheme.onPrimary,
        pressedKey = pressed,
        keyBorder = null,
        keyBorderWidthDp = 0f,
        accent = scheme.primary,
        gestureTrail = scheme.primary,
        popup = popup,
        popupText = scheme.onSurface,
        popupOnKey = null,
        popupBorder = null,
        popupBorderWidthDp = 0f,
        popupTexture = null,
        toolbarIcon = scheme.onSurfaceVariant,
        toolCircle = toolCircle,
        toolCircleActive = scheme.primaryContainer,
        // onPrimaryContainer, not primary: primary-on-primaryContainer is
        // tone-on-tone (blue text on light blue) and fails contrast in
        // most light palettes.
        toolCircleActiveIcon = legibleOn(
            scheme.primaryContainer,
            listOf(scheme.onPrimaryContainer, scheme.primary),
        ),
        toolBorder = null,
        toolBorderWidthDp = 0f,
        chip = chip,
        chipText = scheme.onSurface,
        chipActive = scheme.primaryContainer,
        chipActiveText = legibleOn(
            scheme.primaryContainer,
            listOf(scheme.onPrimaryContainer, scheme.primary),
        ),
        chipBorder = null,
        chipBorderWidthDp = 0f,
        suggestionText = scheme.onSurface,
        secondaryText = scheme.onSurfaceVariant,
        divider = scheme.outlineVariant,
        keyRadiusDp = settings.keyCornerRadiusDp,
        popupRadiusDp = settings.popup.cornerRadiusDp,
        popupShapeKind = settings.popup.shape,
        menuShapeKind = safeContainerKind(settings.popup.shape),
        popupHeightDp = settings.popup.heightDp,
        toolRadiusDp = settings.toolCircleRadiusDp,
        toolShapeKind = settings.toolShape,
        chipRadiusDp = DEFAULT_CHIP_RADIUS_DP,
        chipShapeKind = KeyShapeKind.ROUNDED,
        cardShapeKind = KeyShapeKind.ROUNDED,
        toolWidthDp = settings.toolbarBehavior.toolWidthDp,
        animation = ThemeAnimation.NONE,
        animationSpeed = 1f,
        reduceMotion = settings.reduceMotion,
    )
}

/** What the rounded and cut chip shapes use when a theme sets no chip radius. */
private const val DEFAULT_CHIP_RADIUS_DP = 12

/** Resolves a stored [ThemeSpec] into a [KbTheme], deriving nullable fields. */
private fun specKbTheme(spec: ThemeSpec, settings: KeyboardSettings): KbTheme {
    val board = colorOf(spec.boardBackground)
    val key = colorOf(spec.keyBackground)
    val keyText = colorOf(spec.keyText)
    val accent = colorOf(spec.accent)
    // Derived pressed state shifts toward the text color (a neutral
    // darken/lighten of the key), not the accent — same reasoning as the
    // default theme. Themes that want a colored press set it explicitly.
    val pressed = spec.pressedKeyBackground?.let(::colorOf) ?: lerp(key, keyText, 0.25f)
    // Board-level text: what the suggestion strip, the dividers and the panel
    // chrome draw with. A theme may invert its keys against its board (dark
    // ink on cream keys over a dark board), so the keyText fallback is
    // contrast-guarded against the board; an explicit suggestionText is
    // honoured as written.
    val stripText = spec.suggestionText?.let(::colorOf) ?: legibleOn(board, listOf(keyText))
    val secondary = stripText.copy(alpha = 0.65f)
    val toolActive = spec.toolCircleActiveBackground?.let(::colorOf) ?: pressed
    val chipActive = spec.chipActiveBackground?.let(::colorOf) ?: toolActive
    return KbTheme(
        dark = spec.dark,
        board = board,
        boardGradient = spec.boardGradient,
        backgroundImage = spec.backgroundImage,
        backgroundImageLandscape = spec.backgroundImageLandscape,
        backgroundImageOpacity = spec.backgroundImageOpacity,
        backgroundImageBlur = spec.backgroundImageBlur,
        // Blur wins when a theme file claims both: playing a blurred animation
        // re-renders the effect every frame, which the editor already forbids.
        backgroundAnimated = spec.backgroundAnimated && spec.backgroundImageBlur == 0f,
        keyShapeKind = spec.keyShape,
        keyGradient = spec.keyGradient,
        keyTexture = spec.keyTexture,
        keyTextureModifier = spec.keyTextureModifier,
        keyTextureEnter = spec.keyTextureEnter,
        keyTextureSpace = spec.keyTextureSpace,
        keyTexturePressed = spec.keyTexturePressed,
        keyTextureScale = keyTextureScaleOrDefault(spec.keyTextureScale),
        keyTextureOpacity = spec.keyTextureOpacity,
        keyOverrides = spec.keyOverrides,
        decals = spec.decals.take(MAX_DECALS),
        keyEffect = keyEffectKindOrNull(spec.keyEffect),
        keyEffectParam = spec.keyEffectParam.orEmpty(),
        keyEffectIntensity = spec.keyEffectIntensity,
        keyEffectImages = spec.keyEffectImages.take(MAX_EFFECT_IMAGES),
        key = key,
        keyText = keyText,
        modifierKey = colorOf(spec.modifierKeyBackground),
        modifierKeyText = spec.modifierKeyText?.let(::colorOf) ?: keyText,
        hintText = spec.hintText?.let(::colorOf),
        enterKey = colorOf(spec.enterKeyBackground),
        enterKeyText = colorOf(spec.enterKeyText),
        pressedKey = pressed,
        keyBorder = spec.keyBorderColor?.let(::colorOf),
        keyBorderWidthDp = spec.keyBorderWidthDp,
        accent = accent,
        gestureTrail = spec.gestureTrailColor?.let(::colorOf) ?: accent,
        // A light theme's keyText is dark, so a heavy blend produced a dark
        // popup — and popupText also falls back to keyText (dark), giving an
        // unreadable dark-on-dark key preview. A light theme needs a subtle
        // lift (like the dark branch) so the dark popupText stays legible.
        popup = spec.popupBackground?.let(::colorOf)
            ?: blendOver(keyText, board, if (spec.dark) 0.20f else 0.06f),
        popupText = spec.popupText?.let(::colorOf) ?: keyText,
        popupOnKey = popupOnKeyOrNull(spec.popupPlacement),
        popupBorder = spec.popupBorderColor?.let(::colorOf),
        popupBorderWidthDp = spec.popupBorderWidthDp,
        popupTexture = spec.popupTexture,
        toolbarIcon = spec.toolbarIcon?.let(::colorOf) ?: secondary,
        toolCircle = spec.toolCircleBackground?.let(::colorOf)
            ?: blendOver(keyText, board, 0.14f),
        toolCircleActive = toolActive,
        toolCircleActiveIcon = legibleOn(toolActive, listOf(accent, keyText)),
        toolBorder = spec.toolBorderColor?.let(::colorOf),
        toolBorderWidthDp = spec.toolBorderWidthDp,
        chip = spec.chipBackground?.let(::colorOf) ?: colorOf(spec.modifierKeyBackground),
        chipText = spec.chipText?.let(::colorOf)
            ?: spec.modifierKeyText?.let(::colorOf) ?: keyText,
        chipActive = chipActive,
        chipActiveText = spec.chipActiveText?.let(::colorOf)
            ?: legibleOn(chipActive, listOf(accent, keyText)),
        chipBorder = spec.chipBorderColor?.let(::colorOf),
        chipBorderWidthDp = spec.chipBorderWidthDp,
        suggestionText = stripText,
        secondaryText = secondary,
        divider = stripText.copy(alpha = 0.25f),
        keyRadiusDp = spec.keyCornerRadiusDp ?: settings.keyCornerRadiusDp,
        popupRadiusDp = spec.popupCornerRadiusDp ?: settings.popup.cornerRadiusDp,
        popupShapeKind = keyShapeKindOrNull(spec.popupShape) ?: settings.popup.shape,
        menuShapeKind = keyShapeKindOrNull(spec.menuShape)
            ?: safeContainerKind(keyShapeKindOrNull(spec.popupShape) ?: settings.popup.shape),
        popupHeightDp = spec.popupHeightDp ?: settings.popup.heightDp,
        toolRadiusDp = spec.toolCircleRadiusDp ?: settings.toolCircleRadiusDp,
        toolShapeKind = keyShapeKindOrNull(spec.toolShape) ?: settings.toolShape,
        chipRadiusDp = spec.chipCornerRadiusDp ?: DEFAULT_CHIP_RADIUS_DP,
        chipShapeKind = keyShapeKindOrNull(spec.chipShape) ?: KeyShapeKind.ROUNDED,
        cardShapeKind = keyShapeKindOrNull(spec.cardShape)
            ?: safeContainerKind(keyShapeKindOrNull(spec.chipShape) ?: KeyShapeKind.ROUNDED),
        toolWidthDp = spec.toolWidthDp ?: settings.toolbarBehavior.toolWidthDp,
        animation = spec.animation,
        animationSpeed = spec.animationSpeed,
        reduceMotion = settings.reduceMotion,
    )
}

/**
 * Material scheme for M3 components inside the keyboard (tabs, buttons,
 * list bits in the panels), mapped from the resolved theme so everything
 * follows the theme's colors. Surfaces are flattened to opaque so text on
 * them stays readable even when the board itself is translucent.
 */
private fun schemeFor(kb: KbTheme): ColorScheme {
    val base = if (kb.dark) darkColorScheme() else lightColorScheme()
    val opaqueBoard = kb.board.copy(alpha = 1f)
    return base.copy(
        primary = kb.accent,
        onPrimary = kb.enterKeyText,
        primaryContainer = kb.pressedKey.copy(alpha = 1f),
        onPrimaryContainer = kb.keyText,
        background = opaqueBoard,
        // suggestionText, not keyText: this is the "text on the board" role
        // (the strip, panel bodies), and a theme may invert its keys against
        // its board — dark ink on cream keys over a near-black board. keyText
        // belongs on keys, which have their own colour.
        onBackground = kb.suggestionText,
        surface = opaqueBoard,
        onSurface = kb.suggestionText,
        surfaceContainerLowest = opaqueBoard,
        surfaceContainerLow = opaqueBoard,
        surfaceContainer = kb.chip.copy(alpha = 1f),
        surfaceContainerHigh = kb.modifierKey.copy(alpha = 1f),
        surfaceContainerHighest = kb.key.copy(alpha = 1f),
        onSurfaceVariant = kb.secondaryText,
        outlineVariant = kb.divider,
    )
}

private fun Color.argbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

private fun GradientSpec.mapColors(f: (Color) -> Color): GradientSpec =
    copy(colors = colors.map { f(Color(it.toInt())).argbLong() })

/** Every colour in the theme put through [f]; non-colour fields untouched. */
private fun KbTheme.mapColors(f: (Color) -> Color): KbTheme = copy(
    board = f(board),
    boardGradient = boardGradient?.mapColors(f),
    keyGradient = keyGradient?.mapColors(f),
    key = f(key),
    keyText = f(keyText),
    modifierKey = f(modifierKey),
    modifierKeyText = f(modifierKeyText),
    hintText = hintText?.let(f),
    enterKey = f(enterKey),
    enterKeyText = f(enterKeyText),
    pressedKey = f(pressedKey),
    keyBorder = keyBorder?.let(f),
    accent = f(accent),
    gestureTrail = f(gestureTrail),
    popup = f(popup),
    popupText = f(popupText),
    popupBorder = popupBorder?.let(f),
    toolbarIcon = f(toolbarIcon),
    toolCircle = f(toolCircle),
    toolCircleActive = f(toolCircleActive),
    toolCircleActiveIcon = f(toolCircleActiveIcon),
    chip = f(chip),
    chipText = f(chipText),
    chipActive = f(chipActive),
    chipActiveText = f(chipActiveText),
    chipBorder = chipBorder?.let(f),
    suggestionText = f(suggestionText),
    secondaryText = f(secondaryText),
    divider = f(divider),
)

/** Near-black or near-white, whichever reads on [background]. */
internal fun maxContrastOn(background: Color): Color =
    if (background.luminance() > 0.45f) Color(0xFF000000) else Color(0xFFFFFFFF)

/**
 * Applies the accessibility settings that are palette-level: colour-vision
 * correction, forced contrast and key outlines. Doing it here — after the
 * theme (default or stored) has fully resolved — means every drawing site
 * downstream reads the adjusted values through [LocalKbTheme] without
 * knowing accessibility exists, and it works identically for built-in,
 * custom and dynamic-colour themes.
 */
private fun KbTheme.accessibilityAdjusted(settings: KeyboardSettings): KbTheme {
    // Correction runs first so the contrast pass gets the last word: after
    // daltonization shifts hues, the text/background relationship is what
    // matters and it must not be re-broken.
    var kb = if (settings.colorVisionFilter == ColorVisionFilter.NONE) {
        this
    } else {
        mapColors { ColorVision.correct(it, settings.colorVisionFilter) }
    }

    if (settings.highContrastKeys) {
        // Push the board away from the keys as well as fixing the text: a
        // high-contrast palette that only touches labels still leaves the
        // key *shapes* indistinct, which is the harder problem for low vision.
        val board = if (kb.dark) Color(0xFF000000) else Color(0xFFFFFFFF)
        val key = if (kb.dark) Color(0xFF2A2A2A) else Color(0xFFEDEDED)
        val modifier = if (kb.dark) Color(0xFF141414) else Color(0xFFD2D2D2)
        kb = kb.copy(
            board = board,
            // Gradients, images and motion all fight legibility — drop them.
            boardGradient = null,
            keyGradient = null,
            backgroundImage = null,
            backgroundImageLandscape = null,
            backgroundAnimated = false,
            keyTexture = null,
            keyTextureModifier = null,
            keyTextureEnter = null,
            keyTextureSpace = null,
            keyTexturePressed = null,
            popupTexture = null,
            keyOverrides = emptyMap(),
            decals = emptyList(),
            keyEffect = null,
            animation = ThemeAnimation.NONE,
            key = key,
            modifierKey = modifier,
            keyText = maxContrastOn(key),
            modifierKeyText = maxContrastOn(modifier),
            // Back to the label colour faded: a theme's own hint hue was
            // picked against faces this mode has just repainted.
            hintText = null,
            enterKeyText = maxContrastOn(kb.enterKey),
            popupText = maxContrastOn(kb.popup),
            chipText = maxContrastOn(kb.chip),
            chipActiveText = maxContrastOn(kb.chipActive),
            suggestionText = maxContrastOn(board),
            toolbarIcon = maxContrastOn(board),
            secondaryText = maxContrastOn(board).copy(alpha = 0.75f),
            divider = maxContrastOn(board).copy(alpha = 0.4f),
        )
    }

    if (settings.keyOutlines) {
        val alpha = if (settings.highContrastKeys) 0.9f else 0.45f
        // Only where the theme has drawn no outline of its own: a theme that
        // carries a border has already answered this question, for the keys and
        // for the tools separately.
        if (kb.keyBorderWidthDp <= 0f) {
            kb = kb.copy(
                keyBorder = maxContrastOn(kb.key).copy(alpha = alpha),
                keyBorderWidthDp = 1.5f,
            )
        }
        if (kb.toolBorderWidthDp <= 0f && kb.toolRadiusDp > 0) {
            kb = kb.copy(
                toolBorder = maxContrastOn(kb.toolCircle).copy(alpha = alpha),
                toolBorderWidthDp = 1.5f,
            )
        }
    }
    return kb
}

/**
 * Which half of the auto-theme pair is due right now, kept current while the
 * keyboard is on screen.
 *
 * The system trigger needs no clock — `isSystemInDarkTheme()` recomposes on its
 * own when the setting flips. The other two are answers about *the time*, so
 * they are re-asked once a minute: a keyboard is on screen in bursts, and a
 * board that changed theme only on the next open would sit in yesterday's
 * colours through the whole message being typed at sunset.
 *
 * The sun times are computed once per day per location rather than per tick —
 * they only change when the date does.
 */
@Composable
internal fun rememberAutoThemeDarkSlot(settings: KeyboardSettings, systemDark: Boolean): Boolean {
    val auto = settings.autoTheme
    if (!auto.enabled || auto.trigger == AutoThemeTrigger.SYSTEM) return systemDark

    val minutesOfDay by produceState(currentMinutesOfDay(), auto.trigger) {
        while (true) {
            value = currentMinutesOfDay()
            // Land just after the next whole minute rather than every 60s from
            // whenever this started, so a switchover happens on the minute.
            delay(60_000L - System.currentTimeMillis() % 60_000L + 500L)
        }
    }
    val sun = if (auto.trigger == AutoThemeTrigger.SUN) {
        val latitude = settings.weatherLatitude
        val longitude = settings.weatherLongitude
        // Recomputed when the day rolls over, which minutesOfDay wrapping to a
        // small number is the visible sign of.
        remember(latitude, longitude, minutesOfDay / 60) {
            if (latitude == null || longitude == null) {
                null
            } else {
                SolarCalculator.forDate(
                    latitude.toDouble(),
                    longitude.toDouble(),
                    System.currentTimeMillis(),
                )
            }
        }
    } else {
        null
    }
    return auto.usesDarkSlot(systemDark, minutesOfDay, sun)
}

private fun currentMinutesOfDay(): Int {
    val calendar = java.util.Calendar.getInstance()
    return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
}

/**
 * Resolves the selected theme and provides [LocalKbTheme] plus a matching
 * MaterialTheme to [content]. The default theme follows the theme mode
 * (system/light/dark/AMOLED) and dynamic color like before; stored themes
 * are fixed palettes.
 */
@Composable
fun KeyboardThemeProvider(
    settings: KeyboardSettings,
    rotationStates: Map<String, RotationState> = emptyMap(),
    /**
     * The face the layout on screen asked for (`LayoutAppearance.fontId`), which
     * sits between the user's per-script pick and the theme's — see
     * [KeyboardFonts.scriptFamily] for the order and why. Null, the default, is
     * every shipped layout, and is also what the callers that have no layout in
     * hand (the layout editor's own preview picks its own) pass.
     */
    layoutFontId: String? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    // Auto-theme, when on, picks the id from whichever half of its pair is
    // currently due and ignores the manually-selected keyboardThemeId (the
    // theme tool is read-only while it's active). Off, the selected id wins.
    // Resolution lives in effectiveThemeId/activeThemeSpec so the settings
    // overlay (applyThemeOverrides) can never pick a different spec.
    val auto = settings.autoTheme
    val darkSlot = rememberAutoThemeDarkSlot(settings, systemDark)
    val effectiveId = settings.effectiveThemeId(darkSlot)
    // Everything below is a pure function of the keys listed here, and all of
    // it is expensive enough to matter: dynamicDark/LightColorScheme reads
    // forty system colours out of resources, and the two builders derive a
    // full palette (contrast blends included) from them. This composable
    // recomposes on every keystroke — the content lambda closes over the ui
    // state — so without the memo the whole palette was rebuilt per letter
    // typed. `configuration` is a key so a wallpaper or ui-mode change, which
    // is what moves the dynamic palette underneath us, still re-resolves it.
    val configuration = LocalConfiguration.current
    val resolved = remember(
        settings, systemDark, darkSlot, rotationStates, context, configuration,
    ) {
        val spec = settings.activeThemeSpec(darkSlot)
        if (spec == null) {
            // Under auto-theme the chosen slot decides light vs dark directly;
            // otherwise the theme mode does.
            val dark = if (auto.enabled) darkSlot else when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.AMOLED -> true
            }
            // AMOLED is a dark-only variant; gating on `dark` keeps a light slot
            // (or Light mode) from turning the board black.
            val amoled = dark && settings.themeMode == ThemeMode.AMOLED
            val supportsDynamic =
                settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val scheme = when {
                supportsDynamic && dark -> dynamicDarkColorScheme(context)
                supportsDynamic -> dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            defaultKbTheme(scheme, dark, amoled = amoled, settings)
        } else {
            // The rotating photo is laid over the theme here, on the way to the
            // screen, rather than written into the stored theme. That is what
            // keeps a rotation from rewriting something the user made -- and it
            // is also what lets a built-in theme rotate, since a built-in has
            // nowhere of its own to keep an image.
            val shown =
                if (settings.photoBackground.rotates(effectiveId, settings.keyboardThemeId)) {
                    spec.withRotation(rotationStates[effectiveId], settings.photoBackground)
                } else {
                    spec
                }
            specKbTheme(shown, settings)
        }.accessibilityAdjusted(settings)
    }
    val kb = animatedKbTheme(resolved)
    // The chosen font rides in through the Material typography, so every
    // Text on the keyboard — key labels, suggestions, panels — follows it
    // without per-call plumbing. Emojis get their own family via
    // LocalEmojiFontFamily at the few places emojis are drawn.
    // Per-script font: every non-Latin script draws with the face the user
    // picked for it, or its dedicated Noto face, so its glyphs stay correct even
    // when the key font is a Latin-only display face. Latin (and Cyrillic/Greek,
    // which ride the Latin faces) use the user's key font. Bengali is no
    // different from the rest — its faces simply also carry Latin glyphs, which
    // is what keeps Avro's romanized keys and mixed strips in one face.
    val scriptId = settings.script.id
    // A theme may carry its own key font, by id. It sits between the
    // per-script face and the global pick: script correctness still beats the
    // theme's display face, and an id the device has no font for (the font is
    // its own addon, which the user may not have installed) resolves to null
    // in KeyboardFonts.family and falls through to the global setting.
    // A theme may also name a face per script, which does beat the automatic
    // Noto one — a pixel theme with a pixel Bengali font asked for those glyphs,
    // it is not a Latin-only display face about to blank the board. The user's
    // own per-script pick still wins over both.
    // A *layout* may carry a face too (layoutFontId), and it goes above the
    // theme's: a theme is a skin over the whole keyboard, a layout is one grid
    // whose author picked type for the labels on it.
    val themeFontId = remember(settings, darkSlot) {
        settings.activeThemeSpec(darkSlot)?.fontId
    }
    val themeScriptFontId = remember(settings, darkSlot, scriptId) {
        settings.activeThemeSpec(darkSlot)?.scriptFontIds?.get(scriptId.name)
    }
    val keyFontFamily = remember(
        scriptId,
        layoutFontId,
        themeFontId,
        themeScriptFontId,
        settings.keyFontId,
        settings.scriptFontIds,
        settings.customFontName,
        settings.customScriptFontNames,
    ) {
        KeyboardFonts.scriptFamily(
            context,
            scriptId,
            settings.scriptFontIds[scriptId.name] ?: KeyboardFonts.DEFAULT_ID,
            layoutFontId,
            themeScriptFontId,
        ) // The Latin/Cyrillic/Greek side of the same order: the layout's own
            // face, then the theme's, then the user's global pick.
            ?: layoutFontId?.let { KeyboardFonts.family(context, it) }
            ?: themeFontId?.let { KeyboardFonts.family(context, it) }
            ?: KeyboardFonts.family(context, settings.keyFontId)
    }
    val emojiFontFamily = remember(settings.emojiFont, settings.emojiFontInstalled.installedId) {
        KeyboardFonts.emojiFamily(
            context,
            settings.emojiFont,
            settings.emojiFontInstalled.installedId,
        )
    }
    // Built from the font file, not the FontFamily: the decision is made by
    // reading the font's own cmap and GSUB tables. Identity for the system
    // font, so the default keyboard pays nothing for this, and the tables of a
    // chosen font are only read when an emoji is first drawn.
    val emojiShaper = remember(settings.emojiFont, settings.emojiFontInstalled.installedId) {
        EmojiFontShaping.forFontFile(
            KeyboardFonts.emojiFontFile(
                context,
                settings.emojiFont,
                settings.emojiFontInstalled.installedId,
            ),
        )
    }
    MaterialTheme(
        // Two whole ColorSchemes per call (the Material base, then the copy
        // with this theme's colours over it) — remembered for the same reason
        // the palette above is, since `kb` only moves on a theme change.
        colorScheme = remember(kb) { schemeFor(kb) },
        typography = remember(keyFontFamily) { typographyWith(keyFontFamily) },
    ) {
        CompositionLocalProvider(
            LocalKbTheme provides kb,
            LocalKeyTextures provides rememberKeyTextures(kb),
            LocalEmojiFontFamily provides emojiFontFamily,
            LocalEmojiShaper provides emojiShaper,
            content = content,
        )
    }
}

/** How long a theme switch eases from the old palette to the new one. */
private const val THEME_TRANSITION_MS = 320

/**
 * Crossfades whole themes: whenever the resolved [target] changes while the
 * keyboard is composed — the user tapping a theme, or the AI theme tool
 * swapping one in — every colour, gradient and radius eases from what was on
 * screen to the new values instead of snapping. The first composition and
 * reduce-motion return [target] outright, so opening the keyboard and
 * accessibility users see no fade.
 *
 * An interrupted switch (tapping a second theme mid-fade) restarts from the
 * currently blended colours, so rapid taps stay smooth instead of jumping
 * back to a stale endpoint.
 */
@Composable
fun animatedKbTheme(target: KbTheme): KbTheme {
    val progress = remember { Animatable(1f) }
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        if (target.reduceMotion) {
            // Accessibility: jump straight to the new theme, no crossfade.
            from = target
            to = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        // Snapshot what is on screen now so an interrupted fade continues from
        // the blend rather than from the previous target.
        from = lerpKbTheme(from, to, progress.value)
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(THEME_TRANSITION_MS, easing = FastOutSlowInEasing))
    }
    val p = progress.value
    return if (p >= 1f || from == to) to else lerpKbTheme(from, to, p)
}

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun lerpI(a: Int, b: Int, t: Float): Int = Math.round(a + (b - a) * t)

/** Blend two ARGB longs (theme's storage form) through [Color] space. */
private fun lerpLong(a: Long, b: Long, t: Float): Long =
    lerp(Color(a.toInt()), Color(b.toInt()), t).toArgb().toLong() and 0xFFFFFFFFL

private fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

private fun lerpColorOrNull(a: Color?, b: Color?, t: Float): Color? {
    // A null border means "no outline"; fade it in/out via a transparent stand-in.
    // Either elvis falling all the way through means both sides were absent.
    val from = a ?: b?.copy(alpha = 0f) ?: return null
    val to = b ?: a?.copy(alpha = 0f) ?: return null
    return lerp(from, to, t)
}

/**
 * Interpolate two gradients. Same type and stop count blend stop-for-stop; a
 * missing gradient is promoted to a flat gradient of its side's solid colour
 * ([aSolid]/[bSolid]) so a solid⇆gradient switch still eases. Mismatched
 * shapes (different type or stop count) can't blend, so they flip at the
 * midpoint.
 */
private fun lerpGradient(
    a: GradientSpec?,
    b: GradientSpec?,
    t: Float,
    aSolid: Color,
    bSolid: Color,
): GradientSpec? {
    if (a == null && b == null) return null
    if (a != null && b != null && (a.type != b.type || a.colors.size != b.colors.size)) {
        return if (t >= 0.5f) b else a
    }
    val shape = a ?: b ?: return null
    val flatSolid = (if (a == null) aSolid else bSolid).toArgbLong()
    val flat = List(shape.colors.size) { flatSolid }
    val fromColors = a?.colors ?: flat
    val toColors = b?.colors ?: flat
    return GradientSpec(
        colors = fromColors.indices.map { lerpLong(fromColors[it], toColors[it], t) },
        type = shape.type,
        angleDeg = lerpF(a?.angleDeg ?: shape.angleDeg, b?.angleDeg ?: shape.angleDeg, t),
    )
}

/**
 * Every drawable field of two themes blended at [t] (0 = [a], 1 = [b]).
 * Colours, gradients, radii and widths interpolate; discrete fields (shape
 * kind, animation, background image, dark flag) flip at the midpoint since
 * they can't be tweened.
 */
private fun lerpKbTheme(a: KbTheme, b: KbTheme, t: Float): KbTheme {
    if (t <= 0f) return a
    if (t >= 1f) return b
    val past = t >= 0.5f
    return KbTheme(
        dark = if (past) b.dark else a.dark,
        board = lerp(a.board, b.board, t),
        boardGradient = lerpGradient(a.boardGradient, b.boardGradient, t, a.board, b.board),
        backgroundImage = if (past) b.backgroundImage else a.backgroundImage,
        backgroundImageLandscape = if (past) b.backgroundImageLandscape else a.backgroundImageLandscape,
        backgroundImageOpacity = lerpF(a.backgroundImageOpacity, b.backgroundImageOpacity, t),
        // Discrete, like the image path it belongs to. Interpolating it meant
        // every frame of the crossfade asked for a blur radius no cached decode
        // had, so switching between two themes whose blurs differed re-decoded
        // the photo about twenty times in a third of a second. Blending image
        // A's pixels with image B's blur radius was never meaningful anyway —
        // the image itself snaps at the midpoint. Opacity still interpolates:
        // it is a draw-time alpha, it costs nothing, and it is what makes the
        // swap look smooth.
        backgroundImageBlur = if (past) b.backgroundImageBlur else a.backgroundImageBlur,
        backgroundAnimated = if (past) b.backgroundAnimated else a.backgroundAnimated,
        keyShapeKind = if (past) b.keyShapeKind else a.keyShapeKind,
        keyGradient = lerpGradient(a.keyGradient, b.keyGradient, t, a.key, b.key),
        // Discrete like the background image: a texture is a decoded file.
        keyTexture = if (past) b.keyTexture else a.keyTexture,
        keyTextureModifier = if (past) b.keyTextureModifier else a.keyTextureModifier,
        keyTextureEnter = if (past) b.keyTextureEnter else a.keyTextureEnter,
        keyTextureSpace = if (past) b.keyTextureSpace else a.keyTextureSpace,
        keyTexturePressed = if (past) b.keyTexturePressed else a.keyTexturePressed,
        keyTextureScale = if (past) b.keyTextureScale else a.keyTextureScale,
        keyTextureOpacity = lerpF(a.keyTextureOpacity, b.keyTextureOpacity, t),
        keyOverrides = if (past) b.keyOverrides else a.keyOverrides,
        decals = if (past) b.decals else a.decals,
        keyEffect = if (past) b.keyEffect else a.keyEffect,
        keyEffectParam = if (past) b.keyEffectParam else a.keyEffectParam,
        keyEffectIntensity = lerpF(a.keyEffectIntensity, b.keyEffectIntensity, t),
        keyEffectImages = if (past) b.keyEffectImages else a.keyEffectImages,
        key = lerp(a.key, b.key, t),
        keyText = lerp(a.keyText, b.keyText, t),
        modifierKey = lerp(a.modifierKey, b.modifierKey, t),
        modifierKeyText = lerp(a.modifierKeyText, b.modifierKeyText, t),
        hintText = lerpColorOrNull(a.hintText, b.hintText, t),
        enterKey = lerp(a.enterKey, b.enterKey, t),
        enterKeyText = lerp(a.enterKeyText, b.enterKeyText, t),
        pressedKey = lerp(a.pressedKey, b.pressedKey, t),
        keyBorder = lerpColorOrNull(a.keyBorder, b.keyBorder, t),
        keyBorderWidthDp = lerpF(a.keyBorderWidthDp, b.keyBorderWidthDp, t),
        accent = lerp(a.accent, b.accent, t),
        gestureTrail = lerp(a.gestureTrail, b.gestureTrail, t),
        popup = lerp(a.popup, b.popup, t),
        popupText = lerp(a.popupText, b.popupText, t),
        // Discrete, like the shape kind beside it: a placement is either.
        popupOnKey = if (past) b.popupOnKey else a.popupOnKey,
        popupBorder = lerpColorOrNull(a.popupBorder, b.popupBorder, t),
        popupBorderWidthDp = lerpF(a.popupBorderWidthDp, b.popupBorderWidthDp, t),
        popupTexture = if (past) b.popupTexture else a.popupTexture,
        toolbarIcon = lerp(a.toolbarIcon, b.toolbarIcon, t),
        toolCircle = lerp(a.toolCircle, b.toolCircle, t),
        toolCircleActive = lerp(a.toolCircleActive, b.toolCircleActive, t),
        toolCircleActiveIcon = lerp(a.toolCircleActiveIcon, b.toolCircleActiveIcon, t),
        toolBorder = lerpColorOrNull(a.toolBorder, b.toolBorder, t),
        toolBorderWidthDp = lerpF(a.toolBorderWidthDp, b.toolBorderWidthDp, t),
        chip = lerp(a.chip, b.chip, t),
        chipText = lerp(a.chipText, b.chipText, t),
        chipActive = lerp(a.chipActive, b.chipActive, t),
        chipActiveText = lerp(a.chipActiveText, b.chipActiveText, t),
        chipBorder = lerpColorOrNull(a.chipBorder, b.chipBorder, t),
        chipBorderWidthDp = lerpF(a.chipBorderWidthDp, b.chipBorderWidthDp, t),
        suggestionText = lerp(a.suggestionText, b.suggestionText, t),
        secondaryText = lerp(a.secondaryText, b.secondaryText, t),
        divider = lerp(a.divider, b.divider, t),
        keyRadiusDp = lerpI(a.keyRadiusDp, b.keyRadiusDp, t),
        popupRadiusDp = lerpI(a.popupRadiusDp, b.popupRadiusDp, t),
        popupShapeKind = if (past) b.popupShapeKind else a.popupShapeKind,
        menuShapeKind = if (past) b.menuShapeKind else a.menuShapeKind,
        popupHeightDp = lerpI(a.popupHeightDp, b.popupHeightDp, t),
        toolRadiusDp = lerpI(a.toolRadiusDp, b.toolRadiusDp, t),
        toolShapeKind = if (past) b.toolShapeKind else a.toolShapeKind,
        chipRadiusDp = lerpI(a.chipRadiusDp, b.chipRadiusDp, t),
        chipShapeKind = if (past) b.chipShapeKind else a.chipShapeKind,
        cardShapeKind = if (past) b.cardShapeKind else a.cardShapeKind,
        // Width is measured, not painted — tweening it would re-measure the
        // toolbar every crossfade frame, which the top bar forbids (a width
        // animation restarts every icon's placement spring). Snap at midpoint.
        toolWidthDp = if (past) b.toolWidthDp else a.toolWidthDp,
        animation = if (past) b.animation else a.animation,
        animationSpeed = lerpF(a.animationSpeed, b.animationSpeed, t),
        reduceMotion = b.reduceMotion,
    )
}

/**
 * The theme the default ("Auto") id resolves to right now — dynamic device
 * colors in the current light/dark mode. The themes panel uses this for the
 * Auto swatch, so it previews the device palette instead of parroting
 * whatever theme happens to be active.
 */
@Composable
fun autoKbTheme(settings: KeyboardSettings): KbTheme {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val supportsDynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        supportsDynamic && dark -> dynamicDarkColorScheme(context)
        supportsDynamic -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    return defaultKbTheme(scheme, dark, amoled = settings.themeMode == ThemeMode.AMOLED, settings)
}

/**
 * The animation clock: 0..1 phase looping while the keyboard is composed.
 * Returns a static 0 for NONE, and for reduce-motion, so no infinite
 * transition runs at all — this one loops for as long as the keyboard is on
 * screen, so leaving it running was the single largest thing the setting
 * failed to stop.
 */
@Composable
fun themeAnimationPhase(animation: ThemeAnimation, speed: Float, reduceMotion: Boolean): Float {
    if (animation == ThemeAnimation.NONE || reduceMotion) return 0f
    val transition = rememberInfiniteTransition(label = "themeAnim")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = (16000f / speed.coerceIn(0.25f, 3f)).toInt(),
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    return phase
}

/**
 * The most of the screen the board is ever assumed to take, used to size the
 * background decode. A full-bleed tool panel is the tallest the board gets.
 */
private const val BOARD_HEIGHT_SHARE = 0.6f

/**
 * Board background: optional image (center-cropped, with its own opacity and
 * blur) under the board layer. The board layer is the gradient when one is
 * set, else the solid board color; either can be translucent to act as a
 * scrim over the image (or, with no image, to let the app behind shine
 * through). FLOW/HUE_CYCLE animate the board layer.
 */
@Composable
fun BoxScope.BoardBackground(kb: KbTheme) {
    // In landscape prefer the theme's landscape image, falling back to the
    // portrait one when it has none — so a theme with a single image still
    // shows it in both orientations, exactly as before this split existed.
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val imagePath = if (landscape) kb.backgroundImageLandscape ?: kb.backgroundImage else kb.backgroundImage
    val density = LocalDensity.current
    // Estimated rather than measured: a measured size would stall on a layout
    // pass, and BackgroundBitmapCache buckets what it is given anyway, so a
    // font-scale nudge or a floating-resize drag does not throw the decode
    // away. Over-estimating costs a little memory; under-estimating costs
    // sharpness, so the board is assumed to be a generous share of the screen.
    val targetW = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val targetH = with(density) { (configuration.screenHeightDp * BOARD_HEIGHT_SHARE).dp.roundToPx() }
    // Animated backgrounds play through Coil (its loader already carries the
    // GIF/animated-WebP decoders for the media tools) and stop by themselves
    // when the keyboard leaves the screen. Reduce motion — which power saving
    // also sets — falls back to the still path below, whose BitmapFactory
    // decode of the same file is its first frame. Blur is structurally
    // excluded (editor + specKbTheme), so the two paths never fight.
    if (kb.backgroundAnimated && !kb.reduceMotion && imagePath != null) {
        AsyncImage(
            model = remember(imagePath) { File(imagePath) },
            contentDescription = null,
            imageLoader = rememberMediaImageLoader(),
            contentScale = ContentScale.Crop,
            alpha = kb.backgroundImageOpacity,
            modifier = Modifier.matchParentSize(),
        )
    } else {
        ThemeBackgroundImage(
            path = imagePath,
            blur = kb.backgroundImageBlur,
            opacity = kb.backgroundImageOpacity,
            targetW = targetW,
            targetH = targetH,
        )
    }
    val phase = themeAnimationPhase(kb.animation, kb.animationSpeed, kb.reduceMotion)
    val gradient = kb.boardGradient
    if (gradient != null) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(gradient.brush(kb.animation, phase)),
        )
    } else {
        val board = if (kb.animation == ThemeAnimation.HUE_CYCLE) {
            hueShift(kb.board, phase * 360f)
        } else {
            kb.board
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(board),
        )
    }
}
