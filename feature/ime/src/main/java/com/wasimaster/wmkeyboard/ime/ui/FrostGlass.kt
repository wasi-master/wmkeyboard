package com.wasimaster.wmkeyboard.ime.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.theme.BackgroundBitmapCache
import com.wasimaster.wmkeyboard.core.theme.FrostNoise
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.theme.brush
import com.wasimaster.wmkeyboard.core.theme.KeyShapeKind
import com.wasimaster.wmkeyboard.core.theme.WallpaperBackdrop
import com.wasimaster.wmkeyboard.core.theme.WallpaperBand
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Frosted glass on the keyboard (see `FrostSpec` for the model).
 *
 * Two mechanisms live here. The backdrop is the theme's background image,
 * baked once into a blurred copy that every glass surface samples at its own
 * place on the board: a key draws the slice of the blurred picture that lies
 * under it, then its own translucent colour, then a rim of light and a little
 * grain. Nothing is blurred per frame — the bake is one bitmap in the
 * background cache, and a surface's draw is an image shader with a matrix.
 *
 * The window is the other: on Android 12 and later the keyboard's window asks
 * the system to blur the app behind it, and that is the system compositor's
 * work rather than the keyboard's. [WindowFrostHost] is the seam to the
 * window, implemented by the service and faked by the settings app's preview.
 */

/**
 * The baked backdrop and where the board it belongs to is, in the compose
 * root's coordinates. One per [KeyboardThemeProvider]; the surfaces read it
 * inside their draw cache, so a bake landing or the board moving re-prepares
 * their paint and nothing recomposes.
 */
class FrostBackdrop {
    var bitmap by mutableStateOf<ImageBitmap?>(null)
    var boardBounds by mutableStateOf(Rect.Zero)

    /**
     * The board's top-left on the screen, which is what lets a surface in a
     * window of its own — a popup, a menu — find where it sits on the board:
     * its own screen position minus this, plus [boardBounds]' origin.
     */
    var boardScreenOrigin by mutableStateOf(Offset.Zero)

    /**
     * How much a layer above the board scales it: 1 on the keyboard itself,
     * the zoom on the theme editor's miniature, which shrinks the keyboard
     * with a placement layer (#1299). [boardBounds] and every pane's origin
     * are measured through that layer, while a pane draws in its own
     * unscaled space, so [GlassPaint.of] divides them by this first.
     */
    var boardScale by mutableStateOf(1f)

    /** [screen] (a point on the screen) in the board root's coordinates. */
    fun toBoardRoot(screen: Offset): Offset = screen - boardScreenOrigin + boardBounds.topLeft
}

/**
 * What the board paints over its picture, which every pane lays over its
 * slice so a pane shows the backdrop as the board shows it. The gradient,
 * where the theme has one, is drawn at phase zero: a Flow or Hue-cycle board
 * moves under still panes.
 */
@androidx.compose.runtime.Immutable
data class BoardFill(val color: Color, val gradient: com.wasimaster.wmkeyboard.core.theme.GradientSpec?) {
    companion object {
        val None = BoardFill(Color.Transparent, null)
    }
}

/** The board fill of [this] theme. */
val KbTheme.boardFill: BoardFill get() = BoardFill(board, boardGradient)

/** Provided beside [LocalKbTheme]; a host with no board hands out an empty one. */
val LocalFrostBackdrop = staticCompositionLocalOf { FrostBackdrop() }

/**
 * Keeps [LocalFrostBackdrop]'s bitmap current for what the glass stands on —
 * the board image at [imagePath] or the wallpaper's band, as [kind] says — at
 * the size the board is drawn. Called by the board, which is the one place
 * that knows which picture is up and how big it is.
 *
 * An animated image that is playing has no single frame to bake, so the glass
 * over it is its tint alone; reduce motion stills the image, and then its
 * first frame is baked like any other.
 */
@Composable
internal fun FrostBackdropLoader(
    kb: KbTheme,
    imagePath: String?,
    kind: BackdropKind?,
    targetW: Int,
    targetH: Int,
) {
    val backdrop = LocalFrostBackdrop.current
    val frost = kb.frost
    val playing = kb.backgroundAnimated && !kb.reduceMotion
    val context = LocalContext.current
    // A wallpaper change on Android 12+ arrives as a configuration change
    // (the dynamic colours move with it), which is what re-bakes the band.
    val configuration = LocalConfiguration.current
    // The bake's inputs are snapped at the crossfade midpoint (see lerpFrost),
    // so a theme switch asks for one new bake, not one per frame.
    val blur = frost?.backdropBlur ?: 0f
    val saturation = frost?.saturation ?: 1f
    val brightness = frost?.brightness ?: 0f
    val band = frost?.wallpaperBand
    val dark = kb.dark
    // The theme's own colours, for a phone that publishes no wallpaper colours.
    val fallback = listOf(kb.board, kb.accent, kb.key).map { it.toArgb().toLong() and 0xFFFFFFFFL }
    LaunchedEffect(
        backdrop, kind, imagePath, playing, blur, saturation, brightness, band, dark, targetW, targetH,
        configuration,
    ) {
        backdrop.bitmap = when {
            frost == null || kind == null -> null
            kind == BackdropKind.IMAGE -> if (imagePath == null || playing) {
                null
            } else {
                BackgroundBitmapCache
                    .loadFrosted(imagePath, blur, saturation, brightness, targetW, targetH)
                    ?.asImageBitmap()
            }
            else -> WallpaperBackdrop
                .loadFrosted(context, band ?: WallpaperBand.BOTTOM, blur, saturation, brightness, targetW, targetH, dark, fallback)
                ?.asImageBitmap()
        }
    }
}

/** Records where the board is, for the surfaces to sample the backdrop against. */
internal fun Modifier.frostBoardBounds(backdrop: FrostBackdrop): Modifier =
    onGloballyPositioned {
        val bounds = it.boundsInRoot()
        backdrop.boardBounds = bounds
        backdrop.boardScreenOrigin = it.positionOnScreen()
        // Measured unclipped, unlike the bounds: a miniature half scrolled
        // out of view is still the same size.
        val across = it.localToRoot(Offset(it.size.width.toFloat(), 0f)).x - it.localToRoot(Offset.Zero).x
        backdrop.boardScale = if (it.size.width > 0 && across > 0f) across / it.size.width else 1f
    }

/**
 * [frostBoardBounds] for a board that moves with what it sits in: a theme
 * preview in a scrolling list. The bounds are the board's own frame rather
 * than the window's, so a pane's place on the board (its screen position less
 * the board's) is the same on every frame of a scroll, and the paints built
 * for it are kept instead of rebuilt for every key of every card each frame.
 */
internal fun Modifier.frostLocalBoardBounds(backdrop: FrostBackdrop): Modifier =
    onGloballyPositioned {
        backdrop.boardBounds = Rect(Offset.Zero, it.size.toSize())
        backdrop.boardScreenOrigin = it.positionOnScreen()
    }

/**
 * The slice of the backdrop under one surface, ready to draw: an image
 * shader whose matrix lays the board-sized bitmap over the board and moves
 * it back by the surface's own origin. Built in a draw cache's block, so the
 * draw pass is one `drawOutline`.
 */
class GlassPaint private constructor(
    private val outline: Outline,
    private val brush: Brush,
    private val fill: Brush?,
    /** The lens pressed, for a liquid pane; null draws [brush] either way. */
    private val pressedBrush: Brush?,
    /** True when [LiquidLens] draws this pane, rim of light included, so no [RimPaint] goes over it. */
    val lensed: Boolean,
) {

    /**
     * The slice, then the board's own fill over it — its colour, or its
     * gradient laid out as the board lays it out: a pane lies on the board,
     * so what shows through it is the backdrop *as the board tints it*.
     * Without it a dark board scrimming a bright wallpaper had keys in the
     * wallpaper's full colour, and a gradient board stopped dead at every key
     * and at the toolbar.
     */
    fun draw(scope: DrawScope, pressed: Boolean = false) = with(scope) {
        drawOutline(outline, if (pressed) pressedBrush ?: brush else brush)
        fill?.let { drawOutline(outline, it) }
    }

    companion object {
        /**
         * Null until the backdrop has been baked and the board placed — the
         * surface then draws its tint alone, which is what it drew before.
         *
         * A liquid pane wraps the slice in [LiquidLens] where the phone can
         * run one; [size] and [lensRadiusPx] describe the pane the lens bends
         * around, as a rounded rectangle whatever the outline really is.
         * [withPress] also prepares the pressed lens, for a key.
         */
        fun of(
            backdrop: FrostBackdrop,
            outline: Outline,
            originInRoot: Offset,
            frost: KbFrost? = null,
            size: Size = Size.Zero,
            lensRadiusPx: Float = 0f,
            /** What the board paints over its picture; see [draw]. */
            boardFill: BoardFill = BoardFill.None,
            /** The screen's density, which sizes the lens's bevel. */
            pxPerDp: Float = 1f,
            withPress: Boolean = false,
        ): GlassPaint? {
            val bitmap = backdrop.bitmap ?: return null
            // Back into the unscaled space the pane draws in (#1299).
            val layerScale = backdrop.boardScale.takeIf { it > 0f } ?: 1f
            val board = backdrop.boardBounds.let { measured ->
                if (layerScale == 1f) {
                    measured
                } else {
                    Rect(
                        measured.left / layerScale,
                        measured.top / layerScale,
                        measured.right / layerScale,
                        measured.bottom / layerScale,
                    )
                }
            }
            val origin = if (layerScale == 1f) originInRoot else originInRoot / layerScale
            if (board.isEmpty || bitmap.width == 0 || bitmap.height == 0) return null
            // Centre-crop, the way the board itself draws the image.
            val scale = max(board.width / bitmap.width, board.height / bitmap.height)
            val dx = board.left + (board.width - bitmap.width * scale) / 2f - origin.x
            val dy = board.top + (board.height - bitmap.height * scale) / 2f - origin.y
            val slice = ImageShader(bitmap, TileMode.Clamp, TileMode.Clamp)
            // Bilinear, not nearest: drawn straight a Paint filters the
            // bitmap, but sampled as a runtime shader's child it is read
            // unfiltered, and the upscaled bake came out in visible blocks.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && slice is android.graphics.BitmapShader) {
                slice.filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR
            }
            slice.setLocalMatrix(
                android.graphics.Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy)
                },
            )
            val lensed = frost != null && frost.liquid && LiquidLens.available && size.minDimension > 0f
            // A picture-on-keys theme's board colour is the colour of the
            // gaps, not a scrim over the picture: laid over the slice it
            // would hide the very picture the keys are there to show.
            val fill = if (frost?.cutout == true) null else fillBrush(boardFill, board, origin)
            if (!lensed || frost == null) {
                return GlassPaint(outline, ShaderBrush(slice), fill, null, false)
            }
            val lens = LiquidLens.shader(slice, size, lensRadiusPx, frost, pxPerDp)
            val pressedLens = if (withPress) LiquidLens.shader(slice, size, lensRadiusPx, frost, pxPerDp, press = 1f) else null
            return GlassPaint(
                outline,
                ShaderBrush(lens),
                fill,
                pressedLens?.let { ShaderBrush(it) },
                lensed = lens !== slice,
            )
        }
    }
}

/**
 * The board's fill as a brush in a pane's own coordinates: its flat colour,
 * or its gradient built at the board's size and moved back by the pane's
 * place on it, so the gradient runs on across every pane unbroken.
 */
private fun fillBrush(fill: BoardFill, board: Rect, originInRoot: Offset): Brush? {
    val gradient = fill.gradient
    if (gradient == null) return if (fill.color.alpha > 0f) SolidColor(fill.color) else null
    val boardBrush = gradient.brush() as? ShaderBrush ?: return null
    val shader = boardBrush.createShader(board.size)
    val matrix = android.graphics.Matrix()
    shader.getLocalMatrix(matrix)
    matrix.postTranslate(board.left - originInRoot.x, board.top - originInRoot.y)
    shader.setLocalMatrix(matrix)
    return ShaderBrush(shader)
}

/**
 * The per-pixel half of liquid glass: a runtime shader that samples the
 * backdrop slice through a thick lens, after the SDF lens of
 * QWEA0/Liquid-Glass-Android (`GlassLensRenderer`), whose shapes were
 * measured off iOS 26's Control Center.
 *
 * - The pane is flat glass in the middle and a bevel at the rim. Across the
 *   bevel the sample is pulled inward along the edge's normal by an
 *   inverse-power profile, `(1 + 4t)^-2`: almost all of the bending is in the
 *   last few pixels, where what lies just inside the edge is folded back into
 *   a thin compressed mirror ring, and the middle reads straight through.
 * - Red, green and blue bend by different amounts along that normal, so the
 *   ring is fringed with colour like the edge of a prism.
 * - The light is two equal lobes of `dot(n, -light)`: a two-pixel hairline on
 *   the edges facing the light (upper left, 30 degrees off the horizontal)
 *   and on the edges facing away (the inner wall reflecting it), nothing on
 *   the sides, plus a short glow inward on the lit side only. There is no
 *   shadowed edge — glass has none — which is why a lensed pane draws no
 *   [RimPaint].
 * - Pressed, the rim bends 60% harder, the hairline dims and the middle of
 *   the pane bulges toward its centre, the reference's liquid press.
 *
 * The pane is treated as a rounded rectangle of the surface's size and
 * [lensRadiusPx]; the real outline still clips the draw, so a pill or a
 * squircle gets a lens that follows its box, which at key sizes is close
 * enough. Android 13 and later only: `RuntimeShader` is the first way to run
 * a pixel program from a Paint.
 */
object LiquidLens {
    /**
     * Android 13+ and the program compiles on this phone. Compiled once and
     * remembered, so a phone whose shader compiler rejects it draws every
     * liquid pane frosted instead of throwing out of a draw pass — which
     * kills the keyboard, and on 2026-10-12 did: AGSL reserves `half` as a
     * type, and a parameter named so failed to compile on a CPH2481.
     */
    val available: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { android.graphics.RuntimeShader(SOURCE) }.isSuccess
    }

    /** Width of the bevel, the same on every pane so a space bar and a letter bend alike. */
    private const val BEVEL_DP = LENS_EDGE_DP

    /** The most of a pane's short side the bevel may take, so a small chip is not all rim. */
    private const val BEVEL_MAX_SHARE = 0.3f

    /**
     * How far the very edge reaches inward at full refraction, as a share of
     * the short side, and the short side below which that share shrinks
     * again by (side / reference)²: a 48 dp key folds about 38 px, a large
     * card no more than its own bevel can carry. The reference's numbers.
     */
    private const val REFRACT_SHARE = 0.7f
    private const val REFRACT_REFERENCE_DP = 110f

    /** Inverse-power exponent of the bevel profile; 2 keeps the bending at the very edge. */
    private const val FALLOFF = 2f

    /** Colour split at full dispersion; the slider's 0.3 lands on the reference's 0.12. */
    private const val DISPERSION_SCALE = 0.4f
    private const val DISPERSION_MAX = 0.9f

    /** Inward glow on the lit side: at most this many px, and 5% of the short side. */
    private const val GLOW_MAX_PX = 6f
    private const val GLOW_SHARE = 0.05f

    /** Direction the light travels, from the upper left 30 degrees off the horizontal. */
    private const val LIGHT_X = 0.866f
    private const val LIGHT_Y = 0.5f

    // language=AGSL
    private const val SOURCE = """
        uniform shader backdrop;
        uniform float2 size;
        uniform float radius;
        uniform float bevel;
        uniform float refractPx;
        uniform float falloff;
        uniform float dispersion;
        uniform float2 lightDir;
        uniform float specStrength;
        uniform float glowMax;
        uniform float press;

        float roundRect(float2 p, float2 extent, float r) {
            float2 q = abs(p) - extent + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        half4 main(float2 p) {
            float2 c = size * 0.5;
            float2 local = p - c;
            float d = roundRect(local, c, radius);

            float2 n = float2(
                roundRect(local + float2(1.0, 0.0), c, radius) - roundRect(local - float2(1.0, 0.0), c, radius),
                roundRect(local + float2(0.0, 1.0), c, radius) - roundRect(local - float2(0.0, 1.0), c, radius)
            );
            float nLen = length(n);
            if (nLen > 0.0001) {
                n = n / nLen;
            } else {
                n = float2(0.0, -1.0);
            }

            // 1 at the edge, falling as (1 + 4t)^-p and reaching 0 where the bevel ends.
            float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
            float tail = pow(5.0, -falloff);
            float slope = (pow(1.0 + 4.0 * t, -falloff) - tail) / (1.0 - tail);

            float refr = refractPx * (1.0 + 0.6 * press);
            float2 offset = -n * (slope * refr);

            // Pressed: the middle bulges, samples drawn toward the centre.
            if (press > 0.001) {
                float tr = length(local);
                float sigma = max(max(c.x, c.y), 1.0);
                float bump = press * exp(-(tr * tr) / (sigma * sigma * 0.30));
                if (tr > 1.0) {
                    offset -= (local / tr) * (bump * refr * 0.5);
                }
            }

            float2 lo = float2(1.0);
            float2 hi = size - 1.0;
            float2 cR = clamp(p + offset * (1.0 - dispersion * slope), lo, hi);
            float2 cG = clamp(p + offset, lo, hi);
            float2 cB = clamp(p + offset * (1.0 + dispersion * slope), lo, hi);
            float3 col = float3(
                float4(backdrop.eval(cR)).r,
                float4(backdrop.eval(cG)).g,
                float4(backdrop.eval(cB)).b
            );

            float facing = dot(n, -lightDir);
            float lobeF = pow(max(facing, 0.0), 4.5);
            float lobeB = pow(max(-facing, 0.0), 4.5);
            float bandW = clamp(bevel * 0.3, 2.0, glowMax);
            float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);
            float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5);
            float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0);
            float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.10 * lobeF)
                * specStrength * (1.0 - 0.35 * press);
            col = clamp(col + float3(spec), 0.0, 1.0);
            return half4(half3(col), 1.0);
        }
    """

    /**
     * The lens over [slice] for a pane of [size]. [pxPerDp] sizes the bevel
     * and the fold; [press] 0..1 is the liquid press.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun shader(
        slice: android.graphics.Shader,
        size: Size,
        lensRadiusPx: Float,
        frost: KbFrost,
        pxPerDp: Float,
        press: Float = 0f,
    ): android.graphics.Shader =
        runCatching { lensShader(slice, size, lensRadiusPx, frost, pxPerDp, press) }.getOrElse { slice }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun lensShader(
        slice: android.graphics.Shader,
        size: Size,
        lensRadiusPx: Float,
        frost: KbFrost,
        pxPerDp: Float,
        press: Float,
    ): android.graphics.Shader {
        val short = size.minDimension
        val bevel = (BEVEL_DP * pxPerDp).coerceAtMost(short * BEVEL_MAX_SHARE).coerceAtLeast(2f)
        val reference = REFRACT_REFERENCE_DP * pxPerDp
        val refract = frost.refraction * REFRACT_SHARE * short * min(1f, short / reference)
        return android.graphics.RuntimeShader(SOURCE).apply {
            setInputShader("backdrop", slice)
            setFloatUniform("size", size.width, size.height)
            setFloatUniform("radius", lensRadiusPx.coerceIn(0f, short / 2f))
            setFloatUniform("bevel", bevel)
            setFloatUniform("refractPx", refract)
            setFloatUniform("falloff", FALLOFF)
            setFloatUniform("dispersion", (frost.dispersion * DISPERSION_SCALE).coerceIn(0f, DISPERSION_MAX))
            setFloatUniform("lightDir", LIGHT_X, LIGHT_Y)
            setFloatUniform("specStrength", frost.rim)
            setFloatUniform("glowMax", min(GLOW_MAX_PX, short * GLOW_SHARE).coerceAtLeast(2f))
            setFloatUniform("press", press)
        }
    }
}

/**
 * The corner radius the lens bends around, for a pane of [kind] drawn at
 * [size]: the shapes that round themselves off their box (pill, circle, arch)
 * are half the short side, a square is none, and the rest read the radius
 * slider as the outline does.
 */
fun lensRadiusPx(kind: KeyShapeKind, radiusDp: Int, size: Size, density: Density): Float = when (kind) {
    KeyShapeKind.SHARP -> 0f
    KeyShapeKind.PILL, KeyShapeKind.CIRCLE, KeyShapeKind.ARCH -> size.minDimension / 2f
    else -> with(density) { radiusDp.dp.toPx() }.coerceAtMost(size.minDimension / 2f)
}

/** Width of a liquid lens's bevel, the same on every pane. */
internal const val LENS_EDGE_DP = 12f

/** Width of the rim of light around a glass surface. */
private const val RIM_WIDTH_DP = 1f

/**
 * The rim: a hairline stroke just inside the outline, lit along the top-left
 * edge and shadowed along the bottom-right, fading through nothing across the
 * middle so a long bar is not ringed in a flat line. Stroked on an outline
 * one stroke-width smaller and drawn offset by half of it, so the whole line
 * lands inside the surface rather than straddling its edge.
 */
class RimPaint private constructor(
    private val outline: Outline,
    private val brush: Brush,
    private val stroke: Stroke,
    private val inset: Float,
) {
    fun draw(scope: DrawScope) = with(scope) {
        translate(inset, inset) { drawOutline(outline, brush, style = stroke) }
    }

    companion object {
        fun of(
            frost: KbFrost,
            shape: Shape,
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): RimPaint? {
            if (frost.rim <= 0f) return null
            val width = with(density) { RIM_WIDTH_DP.dp.toPx() }
            val inner = Size(size.width - width, size.height - width)
            if (inner.width <= 0f || inner.height <= 0f) return null
            val light = frost.rimLight.copy(alpha = frost.rimLight.alpha * frost.rim)
            val shadow = frost.rimShadow.copy(alpha = frost.rimShadow.alpha * frost.rim)
            val brush = if (frost.liquid) liquidRim(light, inner) else Brush.linearGradient(
                0f to light,
                0.45f to light.copy(alpha = light.alpha * MID_RIM_ALPHA),
                0.55f to shadow.copy(alpha = shadow.alpha * MID_RIM_ALPHA),
                1f to shadow,
                start = Offset.Zero,
                end = Offset(inner.width, inner.height),
            )
            return RimPaint(shape.createOutline(inner, layoutDirection, density), brush, Stroke(width), width / 2f)
        }

        /** How much of the rim survives across the middle of the diagonal. */
        private const val MID_RIM_ALPHA = 0.15f

        /**
         * A liquid pane's rim where no lens draws it (over the live window
         * blur, or before Android 13): the lens's two equal lobes of light, on
         * the edges facing the light and on those facing away, along the same
         * axis 30 degrees off the horizontal, gone on the sides. No shadowed
         * edge; liquid glass has none.
         */
        private fun liquidRim(light: Color, size: Size): Brush {
            val ux = 0.866f
            val uy = 0.5f
            val reach = size.width / 2f * ux + size.height / 2f * uy
            val centre = Offset(size.width / 2f, size.height / 2f)
            val clear = light.copy(alpha = 0f)
            return Brush.linearGradient(
                0f to light,
                LIQUID_LOBE to clear,
                1f - LIQUID_LOBE to clear,
                1f to light,
                start = centre - Offset(ux * reach, uy * reach),
                end = centre + Offset(ux * reach, uy * reach),
            )
        }

        /** Where along the light axis each lobe has faded out. */
        private const val LIQUID_LOBE = 0.3f
    }
}

/**
 * The grain: the shared noise tile repeated over the surface at the theme's
 * opacity. One shader for every surface on the board.
 */
class GrainPaint private constructor(private val outline: Outline, private val alpha: Float) {

    fun draw(scope: DrawScope) = with(scope) { drawOutline(outline, brush, alpha = alpha) }

    companion object {
        private val brush: Brush by lazy {
            ShaderBrush(ImageShader(FrostNoise.bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
        }

        fun of(frost: KbFrost, outline: Outline): GrainPaint? =
            if (frost.grain <= 0f) null else GrainPaint(outline, frost.grain)
    }
}

/**
 * Dresses a surface as glass: the backdrop slice under whatever the surface
 * paints itself, and the rim and grain over it. Goes *before* the surface's
 * own `background` in the chain, so the tint composites over the glass and
 * the rim over the tint. [frost] null is the surface not being glass, and
 * costs the receiver returned as it is.
 *
 * A modifier node rather than `drawWithCache` so it can be used from any
 * modifier chain, composable or not. The node keeps its paints and rebuilds
 * them only when its size, its place on the board, or the backdrop changes;
 * the draw itself is three `drawOutline`s around the content.
 *
 * A pane finds its place on the board through the screen, so a popup or a
 * menu in a window of its own samples the backdrop exactly as a key does.
 * [sampleBackdrop] false draws only the rim and grain.
 */
fun Modifier.frostGlass(
    frost: KbFrost?,
    backdrop: FrostBackdrop,
    shape: Shape,
    sampleBackdrop: Boolean = true,
    /** What a liquid lens bends around; see [lensRadiusPx]. */
    lensKind: KeyShapeKind = KeyShapeKind.ROUNDED,
    lensRadiusDp: Int = 0,
    /** What the board paints over its picture; see [GlassPaint.draw]. */
    boardFill: BoardFill = BoardFill.None,
): Modifier = if (frost == null) {
    this
} else {
    this.then(FrostGlassElement(frost, backdrop, shape, sampleBackdrop, lensKind, lensRadiusDp, boardFill))
}

/** [frostGlass] for [surface], when the theme makes it glass; the backdrop comes from the composition. */
@Composable
fun Modifier.frostGlass(
    kb: KbTheme,
    shape: Shape,
    surface: FrostSurface,
    sampleBackdrop: Boolean = true,
): Modifier {
    val frost = kb.frost?.takeIf { it.has(surface) } ?: return this
    // The outline the lens bends around: each surface's own shape and radius.
    val (kind, radiusDp) = when (surface) {
        FrostSurface.KEYS, FrostSurface.MODIFIER_KEYS, FrostSurface.ENTER_KEY, FrostSurface.SPACE_BAR ->
            kb.keyShapeKind to kb.keyRadiusDp
        FrostSurface.CHIPS -> kb.chipShapeKind to kb.chipRadiusDp
        FrostSurface.CARDS -> kb.cardShapeKind to kb.chipRadiusDp
        FrostSurface.TOOLS -> kb.toolShapeKind to kb.toolRadiusDp
        FrostSurface.POPUPS -> kb.popupShapeKind to kb.popupRadiusDp
        FrostSurface.MENUS -> kb.menuShapeKind to kb.popupRadiusDp
        FrostSurface.BARS -> KeyShapeKind.SHARP to 0
    }
    // A bar runs the full width with nothing beside it, so a rim drew a frame
    // around the whole strip and a lens bent a wedge into its ends: bars keep
    // the glass and the grain, frosted, without either.
    val dressed = if (surface == FrostSurface.BARS) frost.copy(rim = 0f, liquid = false) else frost
    return frostGlass(dressed, LocalFrostBackdrop.current, shape, sampleBackdrop, kind, radiusDp, kb.boardFill)
}

private data class FrostGlassElement(
    val frost: KbFrost,
    val backdrop: FrostBackdrop,
    val shape: Shape,
    val sample: Boolean,
    val lensKind: KeyShapeKind,
    val lensRadiusDp: Int,
    val boardFill: BoardFill,
) : ModifierNodeElement<FrostGlassNode>() {
    override fun create() = FrostGlassNode(frost, backdrop, shape, sample, lensKind, lensRadiusDp, boardFill)

    override fun update(node: FrostGlassNode) =
        node.update(frost, backdrop, shape, sample, lensKind, lensRadiusDp, boardFill)

    override fun InspectorInfo.inspectableProperties() {
        name = "frostGlass"
    }
}

/** What the paints were last built for; any change rebuilds them. */
private data class GlassPaintKey(
    val size: Size,
    val origin: Offset,
    val bitmap: ImageBitmap?,
    val bounds: Rect,
    val layoutDirection: LayoutDirection,
)

private class FrostGlassNode(
    private var frost: KbFrost,
    private var backdrop: FrostBackdrop,
    private var shape: Shape,
    private var sample: Boolean,
    private var lensKind: KeyShapeKind,
    private var lensRadiusDp: Int,
    private var boardFill: BoardFill,
) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode, ObserverModifierNode {

    private var origin = Offset.Zero
    private var builtFor: GlassPaintKey? = null
    private var glass: GlassPaint? = null
    private var rim: RimPaint? = null
    private var grain: GrainPaint? = null

    fun update(
        frost: KbFrost,
        backdrop: FrostBackdrop,
        shape: Shape,
        sample: Boolean,
        lensKind: KeyShapeKind,
        lensRadiusDp: Int,
        boardFill: BoardFill,
    ) {
        val rewatch = backdrop !== this.backdrop
        this.frost = frost
        this.backdrop = backdrop
        this.shape = shape
        this.sample = sample
        this.lensKind = lensKind
        this.lensRadiusDp = lensRadiusDp
        this.boardFill = boardFill
        builtFor = null
        if (rewatch && isAttached) watchBackdrop()
        invalidateDraw()
    }

    override fun onAttach() {
        watchBackdrop()
    }

    /**
     * The backdrop's bitmap and bounds are snapshot state. Watched here, so a
     * bake landing redraws every pane without anything recomposing; the draw
     * below reads them again, which is what keeps the paints current.
     */
    private fun watchBackdrop() {
        observeReads {
            backdrop.bitmap
            backdrop.boardBounds
            backdrop.boardScreenOrigin
            backdrop.boardScale
        }
    }

    override fun onObservedReadsChanged() {
        // Not a rebuild: the draw compares what the paints were built for
        // (bitmap, bounds, this pane's place on the board) and rebuilds only
        // when one of them really moved. The board's screen origin changes on
        // every frame of a scroll or a floating-keyboard drag while every
        // pane's place on the board stays put.
        invalidateDraw()
        watchBackdrop()
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val bitmap = if (sample) backdrop.bitmap else null
        val bounds = if (sample) backdrop.boardBounds else Rect.Zero
        // Where this pane is, in the board root's coordinates, found through
        // the screen so a pane in a window of its own (a popup, a menu) lands
        // on the board as truly as a key does. Read at draw, not at placement:
        // a popup window moves to its anchor without its content relaying out.
        if (sample && bitmap != null && isAttached) {
            origin = backdrop.toBoardRoot(requireLayoutCoordinates().positionOnScreen())
        }
        val key = GlassPaintKey(size, origin, bitmap, bounds, layoutDirection)
        if (key != builtFor) {
            val outline = shape.createOutline(size, layoutDirection, this)
            glass = if (bitmap != null) {
                GlassPaint.of(
                    backdrop, outline, origin, frost, size,
                    lensRadiusPx(lensKind, lensRadiusDp, size, this), boardFill,
                    pxPerDp = density,
                )
            } else {
                null
            }
            rim = if (glass?.lensed == true) null else RimPaint.of(frost, shape, size, layoutDirection, this)
            grain = GrainPaint.of(frost, outline)
            builtFor = key
        }
        glass?.draw(this)
        drawContent()
        rim?.draw(this)
        grain?.draw(this)
    }
}

/** The glass surface a key of this action belongs to; the same split the textures and colours use. */
internal fun frostSurfaceFor(action: com.wasimaster.wmkeyboard.core.layout.KeyAction): FrostSurface = when (action) {
    com.wasimaster.wmkeyboard.core.layout.KeyAction.Enter -> FrostSurface.ENTER_KEY
    com.wasimaster.wmkeyboard.core.layout.KeyAction.Space -> FrostSurface.SPACE_BAR
    com.wasimaster.wmkeyboard.core.layout.KeyAction.Text -> FrostSurface.KEYS
    else -> FrostSurface.MODIFIER_KEYS
}

// ---- the window ----

/**
 * How the keyboard asks the window it lives in to blur the app behind it.
 *
 * [blurAvailable] is whether that blur can run right now: Android 12 or later,
 * the phone's cross-window blur switched on (battery saver and some OEM
 * builds turn it off), and the keyboard's own power saving not in force. The
 * board watches it to draw itself at the theme's fallback opacity whenever
 * the answer is no, so the keys never sit on an unblurred app.
 *
 * The default does nothing and reports no blur, which is what a host with no
 * window of its own wants.
 */
interface WindowFrostHost {
    val blurAvailable: StateFlow<Boolean>

    /**
     * Blur radius for the window's background, in pixels; 0 turns it off.
     * [cornerPx] rounds the blurred region to match the board's own corners.
     */
    fun setBackgroundBlur(radiusPx: Int, cornerPx: Float = 0f)
}

/** A host with no window to blur. */
object NoWindowFrost : WindowFrostHost {
    override val blurAvailable: StateFlow<Boolean> = MutableStateFlow(false)
    override fun setBackgroundBlur(radiusPx: Int, cornerPx: Float) = Unit
}

val LocalWindowFrost = staticCompositionLocalOf<WindowFrostHost> { NoWindowFrost }

/** True while the app behind the board is being blurred for [kb]. */
@Composable
fun windowFrostLive(kb: KbTheme): Boolean {
    val frost = kb.frost ?: return false
    if (!frost.windowBlur) return false
    val available by LocalWindowFrost.current.blurAvailable.collectAsState()
    return available
}

/**
 * Keeps the window's blur at what [kb] asks for while the docked board is on
 * screen, and turns it off on the way out. Only the docked frame calls this:
 * floating mode's window spans the whole screen, and a blur there would blur
 * the whole app.
 */
@Composable
fun WindowFrostEffect(kb: KbTheme, boardCornerDp: Int = 0) {
    val host = LocalWindowFrost.current
    val density = LocalDensity.current
    val frost = kb.frost
    // Only while the blur may run: WM Keyboard's own power saving says no
    // through `blurAvailable`, and the system does not know to ignore the
    // request then, so asking anyway blurred the band above the board while
    // the board itself had already gone to its fallback.
    val live = windowFrostLive(kb)
    val radiusPx = if (frost != null && live) {
        with(density) { frost.windowBlurRadiusDp.dp.roundToPx() }
    } else {
        0
    }
    val cornerPx = with(density) { boardCornerDp.dp.toPx() }
    LaunchedEffect(host, radiusPx, cornerPx) { host.setBackgroundBlur(radiusPx, cornerPx) }
    DisposableEffect(host) {
        onDispose { host.setBackgroundBlur(0) }
    }
}

/**
 * How many glass popups are open over the board right now. While any is, the
 * board blurs *itself* (see [popupBackdropBlur]), so the popup reads as a pane
 * over softened keys.
 *
 * Why the board and not what is under the popup: a popup is a window of its
 * own, and nothing public blurs what lies under just that window.
 * `Window.setBackgroundBlurRadius` needs a real `Window`, which a Compose
 * popup is not, and `FLAG_BLUR_BEHIND` blurs the *whole screen* behind it —
 * measured on a CPH2481 (Android 15), one long-press turned the app and every
 * key soft. FrostKeys reached the same answer: it blurs its own keyboard view
 * with a `RenderEffect` while its popup-keys panel is up.
 */
class PopupBackdrop {
    var open by mutableIntStateOf(0)
}

val LocalPopupBackdrop = staticCompositionLocalOf { PopupBackdrop() }

/** Blur under an open glass popup, the same 8 dp FrostKeys uses. */
private const val POPUP_BACKDROP_BLUR_DP = 8f

/** Whether a glass popup can blur the board under it: Android 12+ (`RenderEffect`), motion allowed. */
private fun KbTheme.popupBackdropBlurs(surface: FrostSurface): Boolean =
    frost?.has(surface) == true && !reduceMotion && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Counts this popup into [LocalPopupBackdrop] while [visible] and the theme makes [surface] glass. */
@Composable
fun GlassPopupBackdrop(kb: KbTheme, surface: FrostSurface, visible: Boolean = true) {
    val wanted = visible && kb.popupBackdropBlurs(surface)
    val backdrop = LocalPopupBackdrop.current
    DisposableEffect(backdrop, wanted) {
        if (wanted) backdrop.open++
        onDispose { if (wanted) backdrop.open-- }
    }
}

/**
 * The board's half of [PopupBackdrop]: a layer whose render effect is a blur
 * while a glass popup is open, read in the layer block so opening and closing
 * one changes a layer property and recomposes nothing.
 */
fun Modifier.popupBackdropBlur(backdrop: PopupBackdrop): Modifier = graphicsLayer {
    renderEffect = if (backdrop.open > 0) {
        val radius = POPUP_BACKDROP_BLUR_DP * density
        BlurEffect(radius, radius, TileMode.Clamp)
    } else {
        null
    }
}

/**
 * The popup colour for [surface].
 *
 * Over a backdrop the popup is a pane like any key — it samples the same
 * glass ([frostGlass] finds its place through the screen) — so it takes the
 * letter-key fill and reads as one of them. With no backdrop, it is the
 * theme's popup colour over a board that softens under it
 * ([GlassPopupBackdrop]), or at least the fallback opacity where nothing
 * softens, so a translucent sheet never sits on sharp keys.
 */
@Composable
fun KbTheme.popupFill(surface: FrostSurface): Color {
    val frost = frost?.takeIf { it.has(surface) } ?: return popup
    if (LocalFrostBackdrop.current.bitmap != null) return key
    if (popupBackdropBlurs(surface)) return popup
    return popup.copy(alpha = max(popup.alpha, frost.fallbackOpacity))
}
