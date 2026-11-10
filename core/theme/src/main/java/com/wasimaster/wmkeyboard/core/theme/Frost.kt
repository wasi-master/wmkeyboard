package com.wasimaster.wmkeyboard.core.theme

import kotlinx.serialization.Serializable

/**
 * A theme's frosted glass: translucent surfaces that show what is behind them
 * blurred, dressed with a rim of light and a little grain so they read as
 * glass rather than as a see-through colour.
 *
 * Two independent mechanisms share the one vocabulary:
 *
 * - [windowBlur] asks the system to blur the app behind the keyboard's window
 *   (Android 12 and later, and only where the phone has not switched window
 *   blur off). The board's own colour is then the tint over that blur. Where
 *   the blur cannot run the board is drawn at least [fallbackOpacity] opaque
 *   instead, so the keys never sit on an unblurred app.
 * - Every surface named in [surfaces] draws, under its own colour, the slice
 *   of the theme's *background image* that lies beneath it — baked once into
 *   a blurred, re-saturated copy by [backdropBlur], [saturation] and
 *   [brightness]. The board stays sharp, so a key reads as frosted glass over
 *   the photo. A board with no image has nothing to sample, and the surface
 *   is its translucent colour alone.
 *
 * [rim] and [grain] apply to every glass surface whichever mechanism is at
 * work: the rim is a hairline that is light where the light would catch the
 * top-left edge and dark along the bottom-right, the grain a faint tiled
 * noise. Both are what separates glass from a plain transparent fill.
 *
 * A nested, nullable class rather than a dozen fields on [ThemeSpec]: a build
 * from before this existed drops the one unknown key and imports the theme
 * as a plain translucent one, which is the right degradation. [surfaces]
 * travels as names so a surface added later costs itself, not the theme.
 */
@Serializable
data class FrostSpec(
    val enabled: Boolean = true,
    /** Blur the app behind the keyboard window. Android 12+; see the class note. */
    val windowBlur: Boolean = false,
    /** Radius of that blur, in dp; see [WINDOW_BLUR_RADIUS_RANGE]. */
    val windowBlurRadiusDp: Int = 24,
    /**
     * The least opaque the board may be while [windowBlur] is wanted but
     * cannot run. Applied to the board colour and every stop of the board
     * gradient on the way to the screen, never written back.
     */
    val fallbackOpacity: Float = 0.92f,
    /** The surfaces that are glass, as [FrostSurface] names. */
    val surfaces: List<String> = FrostSurface.DEFAULT_NAMES,
    /** Blur baked into the backdrop the glass samples, on the image slider's 0..25 scale. */
    val backdropBlur: Float = 14f,
    /** Saturation of that backdrop, 1 leaving the photo's colours alone. */
    val saturation: Float = 1.25f,
    /** Lifts or darkens the backdrop, as a fraction of white. */
    val brightness: Float = 0f,
    /** Strength of the rim of light around each surface, 0 for none. */
    val rim: Float = 0.5f,
    /**
     * Colour of the rim's lit edge, alpha honoured; null picks white for a
     * dark theme and a brighter white for a light one. The shadowed edge is
     * always a translucent black.
     */
    val rimColor: Long? = null,
    /** Opacity of the grain over each surface, 0 for a clean pane. */
    val grain: Float = 0.06f,
    /**
     * What the glass stands on when the app behind is not being blurred, as
     * a [FrostBackdropSource] name: `AUTO` (the theme's image, else the
     * wallpaper), `IMAGE`, `WALLPAPER` or `NONE`. A string for the usual
     * forward-compat reason; read through [backdropSource].
     */
    val backdrop: String = "AUTO",
    /** Which part of the wallpaper, as a [WallpaperBand] name; read through [band]. */
    val wallpaperBand: String = "BOTTOM",
    /**
     * The look of the glass, as a [FrostStyle] name: `FROSTED`, a pane of
     * milky glass, or `LIQUID`, a thick lens that bends the backdrop at its
     * edge, splits its colours there and catches the light along it. A
     * string for the usual forward-compat reason; read through [glassStyle].
     */
    val style: String = "FROSTED",
    /** How strongly a liquid pane bends the backdrop at its edge; see [FROST_REFRACTION_RANGE]. */
    val refraction: Float = 0.6f,
    /** How far a liquid pane splits the backdrop's colours at that edge; see [FROST_DISPERSION_RANGE]. */
    val dispersion: Float = 0.25f,
    /**
     * Whether the board shows its background image frosted too — through the
     * same bake the panes sample — rather than sharp between them. On by
     * default: with the board sharp and the panes blurred, every pane and the
     * toolbar read as a different tint from the board, and the backdrop
     * sliders seemed to do nothing to the picture. The image's own blur
     * slider still applies when this is off.
     */
    val blurBoard: Boolean = true,
    /**
     * Whether a theme that follows the wallpaper by role draws its board in
     * the wallpaper's accent (FrostKeys' saturated tint) or keeps the board's
     * own lightness and takes only the wallpaper's hue (a quieter, near
     * neutral tint). Only matters to a theme with wallpaper roles, Frost among
     * them; see `followingWallpaper`.
     */
    val accentBoard: Boolean = true,
    /**
     * The picture shows only through the glass surfaces: the board hides its
     * image and paints its own colour between the keys, and each pane shows
     * its slice of the picture without the board's colour laid over it. One
     * photo cut up across the keys, the look people used to make by slicing
     * an image into per-key textures by hand. Set by [withPictureOnKeys].
     */
    val cutout: Boolean = false,
) {
    val glassStyle: FrostStyle
        get() = FrostStyle.entries.firstOrNull { it.name.equals(style, ignoreCase = true) } ?: FrostStyle.FROSTED

    val backdropSource: FrostBackdropSource
        get() = FrostBackdropSource.entries.firstOrNull { it.name.equals(backdrop, ignoreCase = true) }
            ?: FrostBackdropSource.AUTO

    val band: WallpaperBand
        get() = wallpaperBandOrDefault(wallpaperBand)

    /** The surfaces that are glass; a name this build does not know is dropped. */
    val surfaceSet: Set<FrostSurface>
        get() = surfaces.mapNotNullTo(HashSet()) { name ->
            FrostSurface.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }

    fun has(surface: FrostSurface): Boolean = enabled && surface in surfaceSet
}

/**
 * The parts of the keyboard that can be glass. Each is one toggle in the
 * editor; the four key classes are split the way the key textures and
 * colours already are.
 */
enum class FrostSurface {
    KEYS,
    MODIFIER_KEYS,
    ENTER_KEY,
    SPACE_BAR,
    /** The suggestion strip and every bar row stacked with it, the tools' row included. */
    BARS,
    /** The background behind each toolbar tool. */
    TOOLS,
    CHIPS,
    /** Panel cards and search bars. */
    CARDS,
    /** The long-press alternates and the key-preview bubble. */
    POPUPS,
    /** The list menus: the language picker, the clipboard and emoji menus. */
    MENUS,
    ;

    companion object {
        /** Everything but the enter key, whose solid accent is what makes it findable. */
        val DEFAULT: List<FrostSurface> = entries.filter { it != ENTER_KEY }
        val DEFAULT_NAMES: List<String> = DEFAULT.map { it.name }
        val ALL_NAMES: List<String> = entries.map { it.name }
    }
}

/**
 * What a glass surface shows blurred behind it when the app itself is not
 * blurred through the window. Never serialized — travels as a string.
 *
 * `AUTO` is the theme's background image where it has one, and otherwise the
 * wallpaper (see `WallpaperBackdrop`), but only while the window is not
 * already blurring the app: a backdrop painted over a live blur would hide it.
 * `WALLPAPER` paints the wallpaper regardless, a steady backdrop that never
 * changes with the app. `IMAGE` is the theme's image or nothing. `NONE` is
 * tint, rim and grain alone.
 */
enum class FrostBackdropSource { AUTO, IMAGE, WALLPAPER, NONE }

/**
 * How a glass surface draws what it samples. `FROSTED` is the blurred slice
 * under a tint. `LIQUID` is the look Apple calls liquid glass: the slice
 * refracted — pulled inward near the edge as a thick lens magnifies — with
 * its colours split a little where it bends and a band of light where the
 * edge faces the light. Liquid needs a per-pixel shader, which Android has
 * from 13 (`RuntimeShader`); below that, and over the live window blur
 * (whose pixels the keyboard cannot read), a liquid pane draws frosted.
 * Never serialized — travels as a string.
 */
enum class FrostStyle { FROSTED, LIQUID }

/** Bounds the editor's sliders and the renderer hold the glass fields to. */
val WINDOW_BLUR_RADIUS_RANGE = 4..64
val FROST_FALLBACK_OPACITY_RANGE = 0.5f..1f
val FROST_BACKDROP_BLUR_RANGE = 0f..25f
val FROST_SATURATION_RANGE = 0.5f..2f
val FROST_BRIGHTNESS_RANGE = -0.3f..0.3f
val FROST_RIM_RANGE = 0f..1f
val FROST_GRAIN_RANGE = 0f..0.25f
val FROST_REFRACTION_RANGE = 0f..1f
val FROST_DISPERSION_RANGE = 0f..1f

/**
 * Ready-made glass looks. Each is a [FrostSpec] plus the opacities it dresses
 * the theme's fills to, so one tap turns an opaque theme into a glass one. The
 * values are ordinary theme fields afterwards: nothing remembers which preset
 * was picked, and every slider still moves.
 */
enum class FrostPreset(
    val spec: FrostSpec,
    /** Alpha, 0..255, the key, modifier, chip, tool and popup fills are brought down to. */
    val keyAlpha: Int,
    val modifierAlpha: Int,
    val chipAlpha: Int,
    val toolAlpha: Int,
    val popupAlpha: Int,
    /** The sheen laid over the letter keys where the theme has none. */
    val sheen: GradientSpec,
) {
    /** Milky glass: a soft blur, colours lifted a little, a quiet rim. */
    FROSTED(
        spec = FrostSpec(backdropBlur = 14f, saturation = 1.25f, brightness = 0f, rim = 0.5f, grain = 0.06f),
        keyAlpha = 0x40, modifierAlpha = 0x2B, chipAlpha = 0x40, toolAlpha = 0x40, popupAlpha = 0xB3,
        sheen = GradientSpec(listOf(0x2EFFFFFF, 0x00FFFFFF), GradientType.LINEAR, 90f),
    ),

    /** Nearly transparent panes with a bright rim, the way polished glass catches light. */
    CLEAR(
        spec = FrostSpec(backdropBlur = 6f, saturation = 1.1f, brightness = 0.05f, rim = 0.85f, grain = 0.02f),
        keyAlpha = 0x26, modifierAlpha = 0x1A, chipAlpha = 0x26, toolAlpha = 0x26, popupAlpha = 0x99,
        sheen = GradientSpec(listOf(0x40FFFFFF, 0x00FFFFFF), GradientType.LINEAR, 90f),
    ),

    /** Heavy blur and muted colour, the look of a wall of frosted acrylic. */
    MICA(
        spec = FrostSpec(backdropBlur = 25f, saturation = 0.8f, brightness = -0.05f, rim = 0.25f, grain = 0.1f),
        keyAlpha = 0x66, modifierAlpha = 0x4D, chipAlpha = 0x66, toolAlpha = 0x66, popupAlpha = 0xCC,
        sheen = GradientSpec(listOf(0x1AFFFFFF, 0x00FFFFFF), GradientType.LINEAR, 90f),
    ),

    /** Tinted dark: the backdrop dimmed, a low rim, a touch more grain. */
    SMOKED(
        spec = FrostSpec(backdropBlur = 18f, saturation = 1f, brightness = -0.2f, rim = 0.3f, grain = 0.08f),
        keyAlpha = 0x59, modifierAlpha = 0x40, chipAlpha = 0x59, toolAlpha = 0x59, popupAlpha = 0xCC,
        sheen = GradientSpec(listOf(0x14FFFFFF, 0x26000000), GradientType.LINEAR, 90f),
    ),

    /**
     * Thick, clear lenses: barely blurred, the backdrop bent and colour-split
     * at the edge, a bright rim, no grain. Android 13 and later draw the lens;
     * older phones draw this as very clear frosted glass.
     */
    LIQUID(
        spec = FrostSpec(
            style = "LIQUID", backdropBlur = 5f, saturation = 1.15f, brightness = 0.03f,
            rim = 0.9f, grain = 0f, refraction = 0.7f, dispersion = 0.3f,
        ),
        keyAlpha = 0x2E, modifierAlpha = 0x1F, chipAlpha = 0x2E, toolAlpha = 0x2E, popupAlpha = 0x99,
        sheen = GradientSpec(listOf(0x33FFFFFF, 0x00FFFFFF), GradientType.LINEAR, 90f),
    ),
}

/** The surfaces [withPictureOnKeys] cuts the picture into: every key, nothing else. */
val PICTURE_ON_KEYS_SURFACES: List<String> = listOf(
    FrostSurface.KEYS,
    FrostSurface.MODIFIER_KEYS,
    FrostSurface.ENTER_KEY,
    FrostSurface.SPACE_BAR,
).map { it.name }

/** Whether the theme shows its background image only on the keys; see [FrostSpec.cutout]. */
val ThemeSpec.pictureOnKeys: Boolean
    get() = frost?.let { it.enabled && it.cutout } == true

/**
 * The theme with its background image spread across the keys ([on]) or back
 * on the board. On, the glass samples the image sharp — no blur, no colour
 * change, no rim or grain — on the four key surfaces only; the letter and
 * modifier fills go fully see-through so the picture shows as it is (the
 * Enter key keeps its colour, lower it to put the picture there too); and
 * the board colour goes opaque, since it is now the colour of the gaps.
 *
 * Off clears the flag and turns the glass off, and gives a fill this left
 * fully see-through its opacity back, so the keys do not vanish into the
 * picture now that it is on the board again.
 */
fun ThemeSpec.withPictureOnKeys(on: Boolean): ThemeSpec {
    fun Long.opaque(): Long = this or 0xFF000000L
    fun Long.clear(): Long = this and 0x00FFFFFFL
    fun Long.restored(): Long = if ((this ushr 24) == 0L) opaque() else this
    val current = frost ?: FrostSpec()
    return if (on) {
        copy(
            frost = current.copy(
                enabled = true,
                cutout = true,
                windowBlur = false,
                surfaces = PICTURE_ON_KEYS_SURFACES,
                backdrop = FrostBackdropSource.IMAGE.name,
                style = FrostStyle.FROSTED.name,
                backdropBlur = 0f,
                saturation = 1f,
                brightness = 0f,
                rim = 0f,
                grain = 0f,
                blurBoard = false,
            ),
            keyBackground = keyBackground.clear(),
            modifierKeyBackground = modifierKeyBackground.clear(),
            boardBackground = boardBackground.opaque(),
        )
    } else {
        copy(
            frost = current.copy(enabled = false, cutout = false),
            keyBackground = keyBackground.restored(),
            modifierKeyBackground = modifierKeyBackground.restored(),
        )
    }
}

/**
 * The theme dressed for [preset]: its glass fields set, and every fill that
 * is more opaque than the preset wants brought down to it. A fill the user
 * has already made more see-through than that is left alone — the preset is
 * a floor on transparency, not a replacement for a choice. Letter keys get
 * the preset's sheen only where the theme carries none.
 *
 * The window-blur fields are kept as they are: whether the app behind shows
 * through is a separate decision from how the glass looks.
 */
fun ThemeSpec.dressedForGlass(preset: FrostPreset): ThemeSpec {
    val current = frost ?: FrostSpec()
    fun Long.atMost(alpha: Int): Long =
        if (((this ushr 24) and 0xFFL) > alpha) (this and 0x00FFFFFFL) or (alpha.toLong() shl 24) else this
    return copy(
        frost = preset.spec.copy(
            enabled = true,
            windowBlur = current.windowBlur,
            windowBlurRadiusDp = current.windowBlurRadiusDp,
            fallbackOpacity = current.fallbackOpacity,
            surfaces = current.surfaces,
            rimColor = current.rimColor,
            backdrop = current.backdrop,
            wallpaperBand = current.wallpaperBand,
            blurBoard = current.blurBoard,
            accentBoard = current.accentBoard,
        ),
        keyBackground = keyBackground.atMost(preset.keyAlpha),
        modifierKeyBackground = modifierKeyBackground.atMost(preset.modifierAlpha),
        chipBackground = chipBackground?.atMost(preset.chipAlpha),
        toolCircleBackground = toolCircleBackground?.atMost(preset.toolAlpha),
        popupBackground = popupBackground?.atMost(preset.popupAlpha),
        keyGradient = keyGradient ?: preset.sheen,
    )
}

