package com.wasimaster.wmkeyboard.core.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.wasimaster.wmkeyboard.core.engine.NativeEngine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bakes the backdrop the glass surfaces sample: the board image blurred,
 * re-saturated and lifted or dimmed, once per image and parameter set.
 *
 * Done on pixels rather than with a draw-time blur effect because the
 * surfaces need the blurred *pixels* at their own position — a key draws the
 * slice of the backdrop under it through an image shader — and because that
 * has to look the same on Android 8 as on 15. The blur is three box passes on
 * a quarter-size copy, which is within a hair of a Gaussian. The engine's
 * NEON kernel runs them (about 1 ms for a phone's board); [boxBlur] is what
 * it computes, bit for bit, and runs when the engine cannot.
 */
object FrostBake {

    /** How much smaller than the target the blur runs at; the upscale softens further. */
    private const val DOWNSCALE = 4

    /** Box passes; three is the classic Gaussian approximation. */
    private const val PASSES = 3

    /**
     * [source] blurred by [blur] (the editor's 0..25 scale), then saturated by
     * [saturation] and shifted by [brightness] (a fraction of white, negative
     * to darken), at [source]'s own size. Never recycles [source]: the cache
     * may still be handing it out as the sharp board.
     */
    fun bake(source: Bitmap, blur: Float, saturation: Float, brightness: Float): Bitmap {
        val width = source.width
        val height = source.height
        // No blur: nothing gains from the quarter-size pass, and the upscale
        // back would soften a picture the keys are meant to show sharp (the
        // picture-on-keys look). Only the colour change runs, at full size.
        if (blur < 0.5f) return recoloured(source, width, height, saturation, brightness)
        val smallW = max(8, width / DOWNSCALE)
        val smallH = max(8, height / DOWNSCALE)
        val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        val pixels = IntArray(smallW * smallH)
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
        if (small !== source) small.recycle()
        val radius = radiusFor(blur, smallW, smallH)
        if (radius > 0 && !NativeEngine.boxBlur(pixels, smallW, smallH, radius, PASSES)) {
            repeat(PASSES) { boxBlur(pixels, smallW, smallH, radius) }
        }
        val blurred = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        blurred.setPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
        val out = recoloured(blurred, width, height, saturation, brightness)
        blurred.recycle()
        return out
    }

    /**
     * [from] drawn at [width] x [height] into a new bitmap through the
     * saturation and brightness matrix. Always a new bitmap, even with
     * nothing to change, so the bake never hands out the sharp decode
     * itself: the cache would then hold one bitmap under two keys.
     */
    private fun recoloured(
        from: Bitmap,
        width: Int,
        height: Int,
        saturation: Float,
        brightness: Float,
    ): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix(saturation, brightness))
        }
        Canvas(out).drawBitmap(from, null, Rect(0, 0, width, height), paint)
        return out
    }

    /**
     * The box radius, in quarter-size pixels, for a slider value: a third of
     * it, held under a quarter of the short side so a tiny decode is not
     * blurred into one colour.
     */
    fun radiusFor(blur: Float, width: Int, height: Int): Int =
        (blur / 3f).roundToInt().coerceIn(0, max(1, minOf(width, height) / 4))

    /**
     * Saturation first, then the brightness offset on every channel — the
     * order a photo editor applies them, and the one that keeps a lifted
     * image from washing out before it is re-saturated.
     */
    fun colorMatrix(saturation: Float, brightness: Float): ColorMatrix {
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        if (brightness != 0f) {
            val shift = brightness * 255f
            matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, shift,
                        0f, 1f, 0f, 0f, shift,
                        0f, 0f, 1f, 0f, shift,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        return matrix
    }

    /**
     * One horizontal and one vertical box blur of [radius] over ARGB [pixels],
     * in place. Edges clamp to their own pixel, so a bright border never
     * darkens toward transparent. Pure integer arithmetic on arrays, so a JVM
     * test can pin it.
     */
    fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0 || pixels.isEmpty()) return
        val scratch = IntArray(pixels.size)
        blurLines(pixels, scratch, width, height, radius, horizontal = true)
        blurLines(scratch, pixels, width, height, radius, horizontal = false)
    }

    private fun blurLines(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            fun index(i: Int) = if (horizontal) line * width + i else i * width + line
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            // Prime the window with the first pixel repeated radius+1 times
            // and the next radius pixels, clamped at the far end.
            for (k in -radius..radius) {
                val p = src[index(k.coerceIn(0, length - 1))]
                a += p ushr 24 and 0xFF
                r += p ushr 16 and 0xFF
                g += p ushr 8 and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until length) {
                dst[index(i)] = (a / window shl 24) or (r / window shl 16) or (g / window shl 8) or (b / window)
                val leaving = src[index((i - radius).coerceIn(0, length - 1))]
                val entering = src[index((i + radius + 1).coerceIn(0, length - 1))]
                a += (entering ushr 24 and 0xFF) - (leaving ushr 24 and 0xFF)
                r += (entering ushr 16 and 0xFF) - (leaving ushr 16 and 0xFF)
                g += (entering ushr 8 and 0xFF) - (leaving ushr 8 and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }
}

/**
 * The grain laid over glass: one small tile of grey noise, generated once
 * and repeated by a shader. Deterministic, so every surface and every launch
 * draws the same grain and a theme's look does not twinkle between opens.
 */
object FrostNoise {
    const val SIZE = 96

    /** Pixels of the tile, opaque greys spread either side of mid-grey. */
    fun pixels(): IntArray {
        // A small linear congruential generator: no dependency, no allocation
        // beyond the array, and the same tile every time.
        var seed = 0x2F6E2B1
        return IntArray(SIZE * SIZE) {
            seed = seed * 1103515245 + 12345
            val grey = 64 + ((seed ushr 16) and 0x7F)
            (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
        }
    }

    val bitmap: Bitmap by lazy {
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels(), 0, SIZE, 0, 0, SIZE, SIZE)
        }
    }
}
