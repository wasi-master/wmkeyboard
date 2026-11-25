package com.wasimaster.wmkeyboard.core.upscale

import com.wasimaster.wmkeyboard.core.engine.NativeEngine
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscaleTilesTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * A stand-in for the network that does what UpConv7's shape does: eats
     * CONTEXT pixels on every side and doubles the rest, by repeating each
     * pixel into a 2x2 square.
     */
    private fun nearest(input: FloatArray, inSide: Int): FloatArray {
        val c = UpscaleModels.CONTEXT
        val side = inSide - 2 * c
        val outSide = side * 2
        val out = FloatArray(3 * outSide * outSide)
        for (ch in 0 until 3) {
            for (y in 0 until outSide) {
                for (x in 0 until outSide) {
                    out[ch * outSide * outSide + y * outSide + x] =
                        input[ch * inSide * inSide + (c + y / 2) * inSide + (c + x / 2)]
                }
            }
        }
        return out
    }

    @Test
    fun tilesCoverThePictureOnce() {
        val w = 300
        val h = 130
        val side = UpscaleTiles.side(w, h)
        assertEquals(UpscaleModels.TILE, side)
        val seen = IntArray(w * h)
        for (t in UpscaleTiles.tiles(w, h, side)) {
            for (y in t.y until t.y + t.height) for (x in t.x until t.x + t.width) seen[y * w + x]++
        }
        assertTrue(seen.all { it == 1 })
    }

    @Test
    fun smallPictureTakesOneSmallTile() {
        assertEquals(64, UpscaleTiles.side(60, 33))
        assertEquals(1, UpscaleTiles.tiles(60, 33, 64).size)
    }

    @Test
    fun passWithANearestNetworkDoublesThePictureExactly() {
        val w = 290
        val h = 150
        val pixels = IntArray(w * h) { i -> argb(255, i % 251, (i / 7) % 253, (i * 13) % 255) }
        val out = IntArray(4 * w * h)
        val done = UpscaleTiles.pass(pixels, w, h, ::nearest, { false }) { x, y, tw, th, rgb ->
            for (row in 0 until th) System.arraycopy(rgb, row * tw, out, (y + row) * 2 * w + x, tw)
        }
        assertTrue(done)
        for (y in 0 until 2 * h) {
            for (x in 0 until 2 * w) {
                assertEquals("($x, $y)", pixels[(y / 2) * w + x / 2], out[y * 2 * w + x])
            }
        }
    }

    /** [nearest] for the NPU's graph: fixed NPU_INPUT square, channels last in and out. */
    private fun nearestChannelsLast(input: FloatArray): FloatArray {
        val c = UpscaleModels.CONTEXT
        val inSide = UpscaleModels.NPU_INPUT
        val outSide = UpscaleModels.TILE * 2
        val out = FloatArray(3 * outSide * outSide)
        for (y in 0 until outSide) {
            for (x in 0 until outSide) {
                for (ch in 0 until 3) {
                    out[(y * outSide + x) * 3 + ch] = input[((c + y / 2) * inSide + (c + x / 2)) * 3 + ch]
                }
            }
        }
        return out
    }

    @Test
    fun channelsLastPassDoublesThePictureExactly() {
        // Smaller than one tile on one side, more than two on the other.
        val w = 300
        val h = 45
        val pixels = IntArray(w * h) { i -> argb(255, i % 251, (i / 7) % 253, (i * 13) % 255) }
        val out = IntArray(4 * w * h)
        var tiles = 0
        val done = UpscaleTiles.passChannelsLast(pixels, w, h, ::nearestChannelsLast, { false }) { x, y, tw, th, rgb ->
            tiles++
            for (row in 0 until th) System.arraycopy(rgb, row * tw, out, (y + row) * 2 * w + x, tw)
        }
        assertTrue(done)
        assertEquals(3, tiles)
        for (y in 0 until 2 * h) {
            for (x in 0 until 2 * w) {
                assertEquals("($x, $y)", pixels[(y / 2) * w + x / 2], out[y * 2 * w + x])
            }
        }
    }

    @Test
    fun channelsLastInputMatchesTheChannelsFirstOne() {
        val w = 20
        val h = 9
        val pixels = IntArray(w * h) { i -> argb(255, i % 251, (i * 3) % 253, (i * 7) % 255) }
        val side = 12
        val first = FloatArray(3 * side * side)
        val last = FloatArray(3 * side * side)
        UpscaleTiles.input(pixels, w, h, -3, -2, side, first)
        UpscaleTiles.inputChannelsLast(pixels, w, h, -3, -2, side, last)
        val plane = side * side
        for (i in 0 until plane) for (ch in 0 until 3) assertEquals(first[ch * plane + i], last[i * 3 + ch], 0f)
    }

    @Test
    fun inputRepeatsTheEdgePastTheBorder() {
        val w = 3
        val h = 2
        val pixels = intArrayOf(
            argb(255, 10, 0, 0), argb(255, 20, 0, 0), argb(255, 30, 0, 0),
            argb(255, 40, 0, 0), argb(255, 50, 0, 0), argb(255, 60, 0, 0),
        )
        val side = 5
        val out = FloatArray(3 * side * side)
        UpscaleTiles.input(pixels, w, h, -1, -1, side, out)
        // Row 0 is above the picture: the top row again, its left pixel repeated.
        assertEquals(10 / 255f, out[0], 1e-6f)
        assertEquals(10 / 255f, out[1], 1e-6f)
        assertEquals(20 / 255f, out[2], 1e-6f)
        assertEquals(30 / 255f, out[4], 1e-6f)
        // Row 4 is below it: the bottom row.
        assertEquals(60 / 255f, out[4 * side + 4], 1e-6f)
    }

    @Test
    fun cancelStopsBetweenTiles() {
        val pixels = IntArray(300 * 300) { argb(255, 1, 2, 3) }
        var tiles = 0
        val done = UpscaleTiles.pass(pixels, 300, 300, ::nearest, { tiles >= 2 }) { _, _, _, _, _ -> tiles++ }
        assertFalse(done)
        assertEquals(2, tiles)
    }

    @Test
    fun bleedFillsHiddenColourAndKeepsAlpha() {
        val w = 4
        val h = 1
        val pixels = intArrayOf(argb(255, 200, 100, 50), argb(0, 0, 0, 0), argb(0, 0, 0, 0), argb(0, 0, 0, 0))
        UpscaleTiles.bleed(pixels, w, h)
        for (i in 1 until 4) {
            assertEquals(0, pixels[i] ushr 24)
            assertEquals(argb(0, 200, 100, 50), pixels[i])
        }
        assertEquals(argb(255, 200, 100, 50), pixels[0])
    }

    @Test
    fun bleedPastItsRingsTakesTheMeanColour() {
        val w = 40
        val pixels = IntArray(w) { if (it == 0) argb(255, 90, 60, 30) else 0 }
        UpscaleTiles.bleed(pixels, w, 1, rings = 3)
        assertEquals(argb(0, 90, 60, 30), pixels[3])
        assertEquals(argb(0, 90, 60, 30), pixels[39])
    }

    @Test
    fun opaquePicturesNeedNoAlphaPass() {
        assertFalse(UpscaleTiles.hasAlpha(intArrayOf(argb(255, 1, 2, 3))))
        assertTrue(UpscaleTiles.hasAlpha(intArrayOf(argb(255, 1, 2, 3), argb(254, 1, 2, 3))))
    }

    @Test
    fun alphaSurvivesTheGreyRoundTrip() {
        val pixels = IntArray(256) { argb(it, 9, 9, 9) }
        val grey = UpscaleTiles.alphaAsGrey(pixels)
        assertArrayEquals(IntArray(256) { it }, IntArray(256) { UpscaleTiles.greyToAlpha(grey[it]) })
        assertTrue(grey.all { (it ushr 24) == 255 })
    }

    @Test
    fun largestFactorStaysUnderTheEdge() {
        assertEquals(4, UpscaleTiles.largestFactor(200, 100, 1024))
        assertEquals(2, UpscaleTiles.largestFactor(400, 100, 1024))
        assertEquals(0, UpscaleTiles.largestFactor(600, 100, 1024))
    }

    /** [UpscaleTiles.bleed] as it was before it gathered each ring from the last: the reference. */
    private fun bleedByScanning(pixels: IntArray, width: Int, height: Int, rings: Int) {
        val known = BooleanArray(pixels.size) { (pixels[it] ushr 24) > 0 }
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0L
        for (i in pixels.indices) {
            if (!known[i]) continue
            val p = pixels[i]
            sr += (p shr 16) and 0xFF
            sg += (p shr 8) and 0xFF
            sb += p and 0xFF
            n++
        }
        if (n == 0L || n == pixels.size.toLong()) return
        repeat(rings) {
            val newly = ArrayList<Int>()
            val colours = ArrayList<Int>()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    if (known[i]) continue
                    var r = 0
                    var g = 0
                    var b = 0
                    var c = 0
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until height) continue
                        for (dx in -1..1) {
                            val nx = x + dx
                            if ((dx == 0 && dy == 0) || nx !in 0 until width) continue
                            val j = ny * width + nx
                            if (!known[j]) continue
                            val p = pixels[j]
                            r += (p shr 16) and 0xFF
                            g += (p shr 8) and 0xFF
                            b += p and 0xFF
                            c++
                        }
                    }
                    if (c == 0) continue
                    newly += i
                    colours += (r / c shl 16) or (g / c shl 8) or (b / c)
                }
            }
            if (newly.isEmpty()) return
            for (k in newly.indices) {
                pixels[newly[k]] = (pixels[newly[k]] and (0xFF shl 24)) or colours[k]
                known[newly[k]] = true
            }
        }
        val mean = ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
        for (i in pixels.indices) if (!known[i]) pixels[i] = (pixels[i] and (0xFF shl 24)) or mean
    }

    @Test
    fun bleedGathersEachRingAsTheWholeScanFindsIt() {
        val random = Random(11)
        for ((w, h) in listOf(1 to 1, 1 to 9, 9 to 1, 5 to 4, 40 to 31, 120 to 77)) {
            for (shape in 0 until 4) {
                val src = IntArray(w * h) { i ->
                    val x = i % w
                    val y = i / w
                    val visible = when (shape) {
                        0 -> (x - w / 2) * (x - w / 2) + (y - h / 2) * (y - h / 2) < (w * h) / 6
                        1 -> random.nextInt(9) == 0
                        2 -> x == 0 && y == 0
                        else -> random.nextInt(3) != 0
                    }
                    (if (visible) (1 + random.nextInt(255)) shl 24 else 0) or random.nextInt(0x1000000)
                }
                for (rings in listOf(0, 1, 3, 16, 200)) {
                    val want = src.copyOf()
                    bleedByScanning(want, w, h, rings)
                    val got = src.copyOf()
                    UpscaleTiles.bleed(got, w, h, rings)
                    assertArrayEquals("${w}x$h shape $shape rings $rings", want, got)
                }
            }
        }
    }

    @Test
    fun theEnginesTileArithmeticIsTheKotlinLoopsBitForBit() {
        assertTrue("libwm_engine_jni for this machine", NativeEngine.loaded)
        val random = Random(13)
        val before = NativeEngine.forceKotlin
        fun <T> both(run: () -> T): Pair<T, T> {
            NativeEngine.forceKotlin = true
            val kotlin = run()
            NativeEngine.forceKotlin = false
            return kotlin to run()
        }
        try {
            for ((w, h) in listOf(1 to 1, 7 to 3, 130 to 61)) {
                val pixels = IntArray(w * h) { random.nextInt() }
                for ((x0, y0, side) in listOf(Triple(-7, -7, 21), Triple(0, 0, 8), Triple(w - 3, h - 2, 20))) {
                    val (k, n) = both { FloatArray(3 * side * side).also { UpscaleTiles.input(pixels, w, h, x0, y0, side, it) } }
                    assertArrayEquals("input ${w}x$h at $x0,$y0", k, n, 0f)
                }
            }
            val outSide = 36
            val output = FloatArray(3 * outSide * outSide) { random.nextFloat() * 1.2f - 0.1f }
            // Exact halves, where the rounding rule shows.
            for (i in 0 until 40) output[i] = (i + 0.5f) / 255f
            for (tile in listOf(UpscaleTiles.Tile(0, 0, 18, 18), UpscaleTiles.Tile(0, 0, 5, 11))) {
                val (k, n) = both { UpscaleTiles.output(output, outSide, tile) }
                assertArrayEquals("output $tile", k, n)
            }
            NativeEngine.forceKotlin = false
            val rgb = IntArray(9 * 4) { random.nextInt() }
            val alpha = IntArray(13 * 6) { random.nextInt() }
            val merged = rgb.copyOf()
            assertTrue(NativeEngine.mergeAlpha(merged, 9, 4, alpha, 13, 2 * 13 + 3))
            for (row in 0 until 4) for (col in 0 until 9) {
                val i = row * 9 + col
                val a = UpscaleTiles.greyToAlpha(alpha[(2 + row) * 13 + 3 + col])
                assertEquals((a shl 24) or (rgb[i] and 0xFFFFFF), merged[i])
            }
        } finally {
            NativeEngine.forceKotlin = before
        }
    }
}
