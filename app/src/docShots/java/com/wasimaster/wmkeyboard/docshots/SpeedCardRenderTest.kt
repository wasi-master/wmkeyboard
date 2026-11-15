package com.wasimaster.wmkeyboard.docshots

import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardLayout
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardPalette
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardRenderer
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardSize
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardSpec
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardStyle
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestResult
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedUnit
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Every speed test card the tool can share, drawn on the JVM with Android's
 * real graphics: each style at each size in each layout, with and without an
 * upload, comes out the size it promises, mostly its own background, and
 * with enough of something else on it to be a card rather than a blank. The
 * cards land in build/docshots/speed-cards/ for a look by eye.
 *
 * Here and not in the ordinary suite because only the docs-shots run has
 * Robolectric's native graphics (`-Pwmkb.docShots=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeedCardRenderTest {

    private val result = SpeedTestResult(
        downMbps = 187.4,
        upMbps = 42.9,
        pingMs = 13.0,
        jitterMs = 2.4,
        downSamples = List(40) { 150f + (it % 7) * 9f },
        upSamples = List(40) { 35f + (it % 5) * 3f },
        isp = "Example ISP",
        serverName = "Cloudflare",
        serverCity = "DAC",
        at = 1_763_300_000_000L,
    )

    private val theme = SpeedCardRenderer.themePalette(
        background = 0xFF20232A.toInt(),
        key = 0xFF2E333B.toInt(),
        accent = 0xFF7C4DFF.toInt(),
        text = 0xFFFFFFFF.toInt(),
        muted = 0xFFB0B6BE.toInt(),
    )

    private val out = File("build/docshots/speed-cards").apply { mkdirs() }

    @Test
    fun `every style size and layout draws a card of its size with something on it`() {
        for (style in SpeedCardStyle.entries) {
            for (size in SpeedCardSize.entries) {
                for (layout in SpeedCardLayout.entries) {
                    for (upload in listOf(true, false)) {
                        val spec = SpeedCardSpec(style, size, layout, SpeedUnit.MBPS)
                        val card = SpeedCardRenderer.render(
                            if (upload) result else result.copy(upMbps = null, upSamples = emptyList()),
                            spec,
                            theme = theme,
                            previous = result.copy(downMbps = 150.0),
                            locale = Locale.US,
                        )
                        val name = "${style.name}-${size.name}-${layout.name}${if (upload) "" else "-noup"}".lowercase()
                        assertEquals("$name width", size.width, card.width)
                        assertEquals("$name height", size.height, card.height)
                        val palette = SpeedCardPalette.fixed(style) ?: theme
                        val inked = inkedShare(card, palette.background)
                        assertTrue("$name is blank ($inked)", inked > MIN_INK)
                        assertTrue("$name is all ink ($inked)", inked < MAX_INK || style == SpeedCardStyle.GRADIENT)
                        if (upload) File(out, "$name.png").outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        }
    }

    @Test
    fun `a preview is the card scaled down`() {
        val spec = SpeedCardSpec(SpeedCardStyle.DARK, SpeedCardSize.STORY, SpeedCardLayout.FULL)
        val preview = SpeedCardRenderer.render(result, spec, scale = 0.25f)
        assertEquals((spec.size.width * 0.25f).toInt(), preview.width, 1)
        assertEquals((spec.size.height * 0.25f).toInt(), preview.height, 1)
    }

    /** The share of a grid of sample points that is not [background], so text, graphs and panels. */
    private fun inkedShare(bitmap: Bitmap, background: Int): Float {
        var inked = 0
        var total = 0
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 120)
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                total++
                if (distance(bitmap.getPixel(x, y), background) > COLOUR_TOLERANCE) inked++
            }
        }
        return inked.toFloat() / total
    }

    private fun distance(a: Int, b: Int): Int {
        fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
        return maxOf(
            kotlin.math.abs(ch(a, 16) - ch(b, 16)),
            kotlin.math.abs(ch(a, 8) - ch(b, 8)),
            kotlin.math.abs(ch(a, 0) - ch(b, 0)),
        )
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$actual is not within $tolerance of $expected", kotlin.math.abs(expected - actual) <= tolerance)
    }

    private companion object {
        const val COLOUR_TOLERANCE = 24
        const val MIN_INK = 0.02f
        const val MAX_INK = 0.85f
    }
}
