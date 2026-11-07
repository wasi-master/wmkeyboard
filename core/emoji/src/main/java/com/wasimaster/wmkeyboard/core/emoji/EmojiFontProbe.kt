package com.wasimaster.wmkeyboard.core.emoji

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File

/**
 * Draws one emoji from a font file and says whether anything came out.
 *
 * A font's tables can map every emoji and still draw none of them on a given
 * phone: Android renders colour emoji from `CBDT` bitmaps and `COLR` layers,
 * and only from COLRv1 on Android 13 and later. A font whose pictures are
 * OpenType SVG, Apple's `sbix`, or COLRv1 on an older phone maps the code
 * point, so the font is chosen for it, and then paints an empty glyph. In the
 * emoji panel that is a grid of cells that insert when tapped and show nothing
 * (#1175). The only reliable test is the renderer itself.
 */
internal object EmojiFontProbe {

    /**
     * True when drawing a sample emoji the font maps leaves ink on a bitmap.
     * Anything that goes wrong while testing answers true: a probe that cannot
     * run must not take a working font away.
     */
    fun draws(file: File, coverage: EmojiFontCoverage): Boolean {
        val codePoint = coverage.sampleCodePoint() ?: return true
        return runCatching {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.createFromFile(file)
                textSize = PROBE_TEXT_PX
                color = Color.BLACK
            }
            val bitmap = Bitmap.createBitmap(PROBE_SIZE_PX, PROBE_SIZE_PX, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawText(String(Character.toChars(codePoint)), PROBE_INSET_PX, PROBE_BASELINE_PX, paint)
                val pixels = IntArray(PROBE_SIZE_PX * PROBE_SIZE_PX)
                bitmap.getPixels(pixels, 0, PROBE_SIZE_PX, 0, 0, PROBE_SIZE_PX, PROBE_SIZE_PX)
                hasInk(pixels)
            } finally {
                bitmap.recycle()
            }
        }.getOrDefault(true)
    }

    /** True when any pixel is not fully transparent. */
    internal fun hasInk(pixels: IntArray): Boolean = pixels.any { (it ushr ALPHA_SHIFT) != 0 }

    private const val PROBE_SIZE_PX = 64
    private const val PROBE_TEXT_PX = 48f
    private const val PROBE_INSET_PX = 4f
    private const val PROBE_BASELINE_PX = 52f
    private const val ALPHA_SHIFT = 24
}
