package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.theme.ASSET_KEY_OVERRIDE_PRESSED_PREFIX
import com.wasimaster.wmkeyboard.core.theme.ASSET_KEY_OVERRIDE_TEXTURE_PREFIX
import com.wasimaster.wmkeyboard.core.theme.ASSET_KEY_TEXTURE
import com.wasimaster.wmkeyboard.core.theme.ASSET_KEY_TEXTURE_ENTER
import com.wasimaster.wmkeyboard.core.theme.ASSET_KEY_TEXTURE_MODIFIER
import com.wasimaster.wmkeyboard.core.theme.ASSET_KEY_TEXTURE_PRESSED
import com.wasimaster.wmkeyboard.core.theme.ASSET_KEY_TEXTURE_SPACE
import com.wasimaster.wmkeyboard.core.theme.ASSET_POPUP_TEXTURE
import com.wasimaster.wmkeyboard.core.theme.BackgroundBitmapCache
import com.wasimaster.wmkeyboard.core.theme.KeyTextureScale
import com.wasimaster.wmkeyboard.core.theme.TextureSlice
import com.wasimaster.wmkeyboard.core.theme.sliceFor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The active theme's key textures, decoded once for the whole board.
 *
 * Keys read this through [LocalKeyTextures] and pick their class's bitmap by
 * [KeyAction] inside the draw cache — never through [KeyVisual] or the key
 * grid's memo, so a texture costs zero per-keystroke work and the
 * exhaustive-or-stale contract on `rememberKeyGrid` is untouched.
 *
 * The bitmaps come from [BackgroundBitmapCache], which buckets sizes and holds
 * an LRU under the IME's memory posture; the decode targets below keep the
 * whole set around a megabyte.
 */
@Immutable
class KeyTextures(
    val normal: ImageBitmap?,
    val modifier: ImageBitmap?,
    val enter: ImageBitmap?,
    val space: ImageBitmap?,
    val pressed: ImageBitmap?,
    /** Painted inside the key-preview bubble; no fallback from the key slots. */
    val popup: ImageBitmap?,
    val scale: KeyTextureScale,
    val opacity: Float,
    /**
     * Single-key textures, keyed as `ThemeSpec.keyOverrides` is (issue #107).
     * Empty for every theme that gives no one key a texture of its own, which
     * is what keeps [forKey] a map miss away from the class slots below.
     */
    val perKey: Map<String, ImageBitmap> = emptyMap(),
    /** Single-key held-down pictures, keyed the same way; see [pressedForKey]. */
    val perKeyPressed: Map<String, ImageBitmap> = emptyMap(),
    /**
     * The nine-slice cut of each bitmap that has one, by the bitmap itself.
     * An `ImageBitmap` compares by identity, so two slots decoded from one
     * file are still two entries; empty for every theme but a FUTO import.
     */
    val slices: Map<ImageBitmap, TextureSlice> = emptyMap(),
    /** Pixels per dp, for drawing a cut's corners at their natural size. */
    val density: Float = 1f,
) {
    val isEmpty: Boolean
        get() = normal == null && modifier == null && enter == null &&
            space == null && pressed == null && popup == null && perKey.isEmpty() &&
            perKeyPressed.isEmpty()

    /**
     * The picture a key draws while held: its own, when the theme gave this
     * one key one, and otherwise the board's. Null keeps the pressed colour
     * over the resting picture.
     */
    fun pressedForKey(overrideId: String?): ImageBitmap? {
        if (overrideId != null && perKeyPressed.isNotEmpty()) {
            perKeyPressed[overrideId]?.let { return it }
        }
        return pressed
    }

    /**
     * The texture a key draws: its own, when the theme gave this one key one,
     * and otherwise its class's, with the same fallbacks the colour picks in
     * `keyVisual` use — enter falls to modifier falls to normal, space falls
     * to normal. Null means "colour only".
     *
     * [overrideId] is the key's `keyOverrideId`; null asks for the class
     * answer outright, which is what a key with no override has.
     */
    fun forKey(action: KeyAction, overrideId: String? = null): ImageBitmap? {
        if (overrideId != null && perKey.isNotEmpty()) {
            perKey[overrideId]?.let { return it }
        }
        return when {
            action == KeyAction.Enter -> enter ?: modifier ?: normal
            action == KeyAction.Space -> space ?: normal
            action != KeyAction.Text -> modifier ?: normal
            else -> normal
        }
    }

    companion object {
        val EMPTY = KeyTextures(null, null, null, null, null, null, KeyTextureScale.CROP, 1f)
    }
}

/** Provided beside [LocalKbTheme]; [KeyTextures.EMPTY] when the theme has none. */
val LocalKeyTextures = staticCompositionLocalOf { KeyTextures.EMPTY }

/**
 * One key's texture, prepared for its size and shape inside `drawWithCache`'s
 * cache block: the clip path, the source-crop window, or the tile brush are
 * all built there, so the press-path draw only issues the draw call.
 */
sealed class KeyTexturePaint {

    abstract fun draw(scope: DrawScope, alpha: Float)

    /** TILE: the bitmap repeats at its natural pixel size, as a shader. */
    private class Tiled(private val outline: Outline, private val brush: Brush) :
        KeyTexturePaint() {
        override fun draw(scope: DrawScope, alpha: Float) = with(scope) {
            drawOutline(outline, brush, alpha = alpha)
        }
    }

    /** CROP and STRETCH: the bitmap maps onto the key, clipped to its shape. */
    private class Fitted(
        private val clip: Path,
        private val bitmap: ImageBitmap,
        private val srcOffset: IntOffset,
        private val srcSize: IntSize,
        private val dstSize: IntSize,
    ) : KeyTexturePaint() {
        override fun draw(scope: DrawScope, alpha: Float) = with(scope) {
            clipPath(clip) {
                drawImage(
                    image = bitmap,
                    srcOffset = srcOffset,
                    srcSize = srcSize,
                    dstOffset = IntOffset.Zero,
                    dstSize = dstSize,
                    alpha = alpha,
                )
            }
        }
    }

    /**
     * Nine-slice: the corners at their natural size, the edges stretched one
     * way and the middle both, all nine pieces worked out once. Not clipped to
     * the key's outline, because the picture draws its own: a FUTO key border
     * is a frame with its own rounding, which a clip would shave.
     */
    private class Sliced(
        private val bitmap: ImageBitmap,
        private val pieces: List<Piece>,
        private val filter: ColorFilter?,
    ) : KeyTexturePaint() {
        class Piece(val srcOffset: IntOffset, val srcSize: IntSize, val dstOffset: IntOffset, val dstSize: IntSize)

        override fun draw(scope: DrawScope, alpha: Float) = with(scope) {
            for (piece in pieces) {
                drawImage(
                    image = bitmap,
                    srcOffset = piece.srcOffset,
                    srcSize = piece.srcSize,
                    dstOffset = piece.dstOffset,
                    dstSize = piece.dstSize,
                    alpha = alpha,
                    colorFilter = filter,
                )
            }
        }
    }

    companion object {
        fun of(
            bitmap: ImageBitmap,
            textures: KeyTextures,
            outline: Outline,
            size: Size,
        ): KeyTexturePaint {
            textures.slices[bitmap]?.let { return sliced(bitmap, it, size, textures.density) }
            return fitted(bitmap, textures, outline, size)
        }

        private fun sliced(bitmap: ImageBitmap, slice: TextureSlice, size: Size, density: Float): KeyTexturePaint {
            val (xs, dxs) = cuts(bitmap.width, slice.left, slice.right, slice.widthDp, size.width, size.height, bitmap.height, density)
            val (ys, dys) = cuts(bitmap.height, slice.top, slice.bottom, slice.heightDp, size.height, size.height, bitmap.height, density)
            val pieces = buildList {
                for (row in 0 until 3) for (col in 0 until 3) {
                    val sw = xs[col + 1] - xs[col]
                    val sh = ys[row + 1] - ys[row]
                    val dw = dxs[col + 1] - dxs[col]
                    val dh = dys[row + 1] - dys[row]
                    if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) continue
                    add(
                        Sliced.Piece(
                            IntOffset(xs[col], ys[row]),
                            IntSize(sw, sh),
                            IntOffset(dxs[col], dys[row]),
                            IntSize(dw, dh),
                        ),
                    )
                }
            }
            val filter = slice.tint?.let { ColorFilter.tint(Color(it.toInt()), BlendMode.Modulate) }
            return Sliced(bitmap, pieces, filter)
        }

        /**
         * The four source and four destination edges along one axis. The
         * corners keep [naturalDp]'s scale, or the key height's when the
         * picture states no size; on a key too small for both corners they
         * shrink together so the middle is never negative.
         */
        @Suppress("LongParameterList")
        private fun cuts(
            length: Int,
            from: Float,
            to: Float,
            naturalDp: Float?,
            dst: Float,
            keyHeight: Float,
            bitmapHeight: Int,
            density: Float,
        ): Pair<IntArray, IntArray> {
            val a = (from * length).roundToInt().coerceIn(0, length)
            val b = (to * length).roundToInt().coerceIn(a, length)
            val scale = if (naturalDp != null && naturalDp > 0f) {
                naturalDp * density / length
            } else {
                keyHeight / max(1, bitmapHeight)
            }
            var head = a * scale
            var tail = (length - b) * scale
            if (head + tail > dst && head + tail > 0f) {
                val fit = dst / (head + tail)
                head *= fit
                tail *= fit
            }
            val end = max(1, dst.roundToInt())
            val dHead = head.roundToInt().coerceIn(0, end)
            val dTail = (end - tail.roundToInt()).coerceIn(dHead, end)
            return intArrayOf(0, a, b, length) to intArrayOf(0, dHead, dTail, end)
        }

        private fun fitted(
            bitmap: ImageBitmap,
            textures: KeyTextures,
            outline: Outline,
            size: Size,
        ): KeyTexturePaint = when (textures.scale) {
            KeyTextureScale.TILE -> Tiled(
                outline,
                ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)),
            )
            KeyTextureScale.STRETCH, KeyTextureScale.CROP -> {
                val clip = Path().apply { addOutline(outline) }
                val bw = bitmap.width
                val bh = bitmap.height
                val dst = IntSize(
                    max(1, size.width.roundToInt()),
                    max(1, size.height.roundToInt()),
                )
                val (srcOffset, srcSize) = if (textures.scale == KeyTextureScale.STRETCH) {
                    IntOffset.Zero to IntSize(bw, bh)
                } else {
                    // Centre-crop: the largest window of the bitmap with the
                    // key's aspect ratio, so nothing distorts.
                    val scale = max(size.width / bw, size.height / bh)
                    val w = (size.width / scale).roundToInt().coerceIn(1, bw)
                    val h = (size.height / scale).roundToInt().coerceIn(1, bh)
                    IntOffset((bw - w) / 2, (bh - h) / 2) to IntSize(w, h)
                }
                Fitted(clip, bitmap, srcOffset, srcSize, dst)
            }
        }
    }
}

/** Longest edge a per-key texture decodes to. A key is a few dozen dp. */
private const val KEY_TEXTURE_PX = 256

/** The space bar is the one wide key, so its texture keeps more width. */
private const val SPACE_TEXTURE_W = 1024
private const val SPACE_TEXTURE_H = 320

/** The preview bubble is wider than a key but far smaller than the board. */
private const val POPUP_TEXTURE_W = 512
private const val POPUP_TEXTURE_H = 256

/**
 * Decodes the theme's key textures off the main thread, re-running only when
 * one of the paths (which snap at the crossfade midpoint) or the fit
 * parameters change. The first frames of a texture theme draw colour-only
 * while the decode lands — the same progressive appearance the background
 * image has always had.
 */
@Composable
fun rememberKeyTextures(kb: KbTheme): KeyTextures {
    // The single-key textures, pulled out first so the "nothing to decode"
    // shortcut below sees them: a theme may dress one key and no class at all.
    val perKeyPaths = kb.keyOverrideTexturePaths()
    val perKeyPressedPaths = kb.keyOverridePressedPaths()
    val hasAny = kb.keyTexture != null || kb.keyTextureModifier != null ||
        kb.keyTextureEnter != null || kb.keyTextureSpace != null ||
        kb.keyTexturePressed != null || kb.popupTexture != null ||
        perKeyPaths.isNotEmpty() || perKeyPressedPaths.isNotEmpty()
    if (!hasAny) return KeyTextures.EMPTY
    val density = LocalDensity.current.density
    val textures by produceState(
        initialValue = KeyTextures.EMPTY,
        kb.keyTexture,
        kb.keyTextureModifier,
        kb.keyTextureEnter,
        kb.keyTextureSpace,
        kb.keyTexturePressed,
        kb.popupTexture,
        kb.keyTextureScale,
        kb.keyTextureOpacity,
        perKeyPaths,
        perKeyPressedPaths,
        kb.textureSlices,
        density,
    ) {
        val slices = HashMap<ImageBitmap, TextureSlice>()
        suspend fun load(
            path: String?,
            slot: String,
            w: Int = KEY_TEXTURE_PX,
            h: Int = KEY_TEXTURE_PX,
        ): ImageBitmap? {
            val bitmap = path?.let { BackgroundBitmapCache.load(it, 0f, w, h)?.asImageBitmap() } ?: return null
            kb.textureSlices.sliceFor(slot, path)?.let { slices[bitmap] = it }
            return bitmap
        }
        suspend fun loadPerKey(paths: Map<String, String>, prefix: String) = buildMap {
            for ((id, path) in paths) {
                // The space bar is the one wide key, here as in the class
                // slots: a square decode on it reads as a smear.
                val bitmap = if (id == SPACE_OVERRIDE_ID) {
                    load(path, prefix + id, SPACE_TEXTURE_W, SPACE_TEXTURE_H)
                } else {
                    load(path, prefix + id)
                }
                bitmap?.let { put(id, it) }
            }
        }
        value = KeyTextures(
            normal = load(kb.keyTexture, ASSET_KEY_TEXTURE),
            modifier = load(kb.keyTextureModifier, ASSET_KEY_TEXTURE_MODIFIER),
            enter = load(kb.keyTextureEnter, ASSET_KEY_TEXTURE_ENTER),
            space = load(kb.keyTextureSpace, ASSET_KEY_TEXTURE_SPACE, SPACE_TEXTURE_W, SPACE_TEXTURE_H),
            pressed = load(kb.keyTexturePressed, ASSET_KEY_TEXTURE_PRESSED),
            popup = load(kb.popupTexture, ASSET_POPUP_TEXTURE, POPUP_TEXTURE_W, POPUP_TEXTURE_H),
            scale = kb.keyTextureScale,
            opacity = kb.keyTextureOpacity,
            perKey = loadPerKey(perKeyPaths, ASSET_KEY_OVERRIDE_TEXTURE_PREFIX),
            perKeyPressed = loadPerKey(perKeyPressedPaths, ASSET_KEY_OVERRIDE_PRESSED_PREFIX),
            slices = slices,
            density = density,
        )
    }
    return textures
}

/**
 * The theme's single-key texture paths, keyed by override id — a plain map so
 * `produceState` re-decodes on a value change rather than on every recomposed
 * theme instance.
 */
private fun KbTheme.keyOverrideTexturePaths(): Map<String, String> =
    if (keyOverrides.isEmpty()) {
        emptyMap()
    } else {
        buildMap {
            for ((id, override) in keyOverrides) override.texture?.let { put(id, it) }
        }
    }

/** The theme's single-key held-down picture paths, keyed by override id. */
private fun KbTheme.keyOverridePressedPaths(): Map<String, String> =
    if (keyOverrides.isEmpty()) {
        emptyMap()
    } else {
        buildMap {
            for ((id, override) in keyOverrides) override.pressedTexture?.let { put(id, it) }
        }
    }

/** The override id of the space bar; `keyOverrideId` spells it this way. */
private const val SPACE_OVERRIDE_ID = "SPACE"
