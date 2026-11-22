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
     * [coverage] as this phone will actually draw it: marked undrawable when a
     * sample emoji leaves no ink, and marked unjoined when a sample sequence
     * comes out as more than one glyph.
     */
    fun check(file: File, coverage: EmojiFontCoverage): EmojiFontCoverage {
        if (!draws(file, coverage)) return coverage.undrawable()
        return if (joins(file, coverage)) coverage else coverage.unjoined()
    }

    /**
     * True when a sequence the font maps every part of lays out as one glyph in
     * it (#1492).
     *
     * [Paint.hasGlyph] is the right question here, though not for a single
     * emoji (see [EmojiRenderCheck]): every code point of the sample is in the
     * font, which comes first in its collection, so the run is the font's own
     * and the answer is about its shaping, whatever the rules are written in.
     * For more than one character the call answers true only when the run
     * shaped to a single glyph. As with [draws], a probe that cannot run, or a
     * font with no sample to try, answers true.
     */
    fun joins(file: File, coverage: EmojiFontCoverage): Boolean {
        val sample = coverage.sampleSequence() ?: return true
        return runCatching {
            val text = buildString { for (cp in sample) appendCodePoint(cp) }
            Paint().apply { typeface = Typeface.createFromFile(file) }.hasGlyph(text)
        }.getOrDefault(true)
    }

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
