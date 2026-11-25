package com.wasimaster.wmkeyboard.core.upscale

import com.wasimaster.wmkeyboard.core.engine.NativeEngine
import kotlin.math.roundToInt

/**
 * The upscaler's pixel arithmetic, apart from Android and from MNN so it can
 * be tested on the computer: cutting a picture into tiles, feeding one to the
 * network, and putting what comes back where it belongs.
 *
 * Pixels are non-premultiplied ARGB ints, which is what `Bitmap.getPixels`
 * hands over. The network sees RGB from 0 to 1 in NCHW order.
 *
 * A tile is read with [UpscaleModels.CONTEXT] pixels of the picture around
 * it (the picture's own edge pixel repeated past its border), which the
 * network eats, so tile seams fall where the network could see across them.
 * Every tile has the same input size, the last row and column included: MNN
 * prepares a network for one input shape and redoes that for each new one.
 */
object UpscaleTiles {

    /** One tile: where it sits in the small picture, and how much of it is picture. */
    data class Tile(val x: Int, val y: Int, val width: Int, val height: Int)

    /** The tile side for a [width] by [height] picture: [UpscaleModels.TILE], or less for a small picture. */
    fun side(width: Int, height: Int): Int =
        minOf(UpscaleModels.TILE, roundUp(maxOf(width, height), ALIGN))

    /** Every tile of a [width] by [height] picture, row by row. */
    fun tiles(width: Int, height: Int, side: Int): List<Tile> {
        val out = ArrayList<Tile>()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                out += Tile(x, y, minOf(side, width - x), minOf(side, height - y))
                x += side
            }
            y += side
        }
        return out
    }

    /**
     * Runs [network] over every tile of a [width] by [height] picture and
     * hands each result to [put] at its place in the picture twice the size:
     * x, y, width, height, opaque RGB pixels. [network] takes one tile's input
     * (3 × inSide × inSide) with its side and answers 3 × 2·side × 2·side.
     * False as soon as [cancelled] says so, between two tiles.
     */
    fun pass(
        pixels: IntArray,
        width: Int,
        height: Int,
        network: (input: FloatArray, inSide: Int) -> FloatArray,
        cancelled: () -> Boolean,
        put: (x: Int, y: Int, w: Int, h: Int, rgb: IntArray) -> Unit,
    ): Boolean {
        val side = side(width, height)
        val context = UpscaleModels.CONTEXT
        val scale = UpscaleModels.SCALE
        val inSide = side + 2 * context
        val input = FloatArray(3 * inSide * inSide)
        for (tile in tiles(width, height, side)) {
            if (cancelled()) return false
            input(pixels, width, height, tile.x - context, tile.y - context, inSide, input)
            val rgb = output(network(input, inSide), side * scale, tile)
            put(tile.x * scale, tile.y * scale, tile.width * scale, tile.height * scale, rgb)
        }
        return true
    }

    /**
     * [pass] for the NPU's build of the network: a graph fixed at one input,
     * [UpscaleModels.NPU_INPUT] square, channels last (H × W × RGB) in and out.
     * Every tile is [UpscaleModels.TILE] with its context, whatever the
     * picture's size; a tile past the picture's edge reads the edge repeated,
     * and only the part of the answer that is picture is kept.
     */
    fun passChannelsLast(
        pixels: IntArray,
        width: Int,
        height: Int,
        network: (input: FloatArray) -> FloatArray,
        cancelled: () -> Boolean,
        put: (x: Int, y: Int, w: Int, h: Int, rgb: IntArray) -> Unit,
    ): Boolean {
        val side = UpscaleModels.TILE
        val context = UpscaleModels.CONTEXT
        val scale = UpscaleModels.SCALE
        val inSide = UpscaleModels.NPU_INPUT
        val input = FloatArray(3 * inSide * inSide)
        for (tile in tiles(width, height, side)) {
            if (cancelled()) return false
            inputChannelsLast(pixels, width, height, tile.x - context, tile.y - context, inSide, input)
            val rgb = outputChannelsLast(network(input), side * scale, tile)
            put(tile.x * scale, tile.y * scale, tile.width * scale, tile.height * scale, rgb)
        }
        return true
    }

    /** [input], channels last: (row × inSide + col) × 3 + channel. */
    fun inputChannelsLast(pixels: IntArray, width: Int, height: Int, x0: Int, y0: Int, inSide: Int, out: FloatArray) {
        var i = 0
        for (row in 0 until inSide) {
            val sy = (y0 + row).coerceIn(0, height - 1) * width
            for (col in 0 until inSide) {
                val p = pixels[sy + (x0 + col).coerceIn(0, width - 1)]
                out[i++] = ((p shr 16) and 0xFF) / 255f
                out[i++] = ((p shr 8) and 0xFF) / 255f
                out[i++] = (p and 0xFF) / 255f
            }
        }
    }

    /** [output] for a channels-last answer (outSide × outSide × RGB). */
    fun outputChannelsLast(output: FloatArray, outSide: Int, tile: Tile): IntArray {
        val w = tile.width * UpscaleModels.SCALE
        val h = tile.height * UpscaleModels.SCALE
        val out = IntArray(w * h)
        for (y in 0 until h) {
            var i = y * outSide * 3
            for (x in 0 until w) {
                out[y * w + x] = OPAQUE or (channel(output[i]) shl 16) or
                    (channel(output[i + 1]) shl 8) or channel(output[i + 2])
                i += 3
            }
        }
        return out
    }

    /**
     * The network's input for the square at ([x0], [y0]) of side [inSide] in
     * a [width] by [height] picture, into [out] (3 × inSide × inSide). Places
     * outside the picture take the nearest edge pixel.
     */
    fun input(pixels: IntArray, width: Int, height: Int, x0: Int, y0: Int, inSide: Int, out: FloatArray) {
        // The engine's NEON kernel computes exactly this loop, in one call.
        if (NativeEngine.upscaleInput(pixels, width, height, x0, y0, inSide, out)) return
        val plane = inSide * inSide
        for (row in 0 until inSide) {
            val sy = (y0 + row).coerceIn(0, height - 1) * width
            for (col in 0 until inSide) {
                val p = pixels[sy + (x0 + col).coerceIn(0, width - 1)]
                val i = row * inSide + col
                out[i] = ((p shr 16) and 0xFF) / 255f
                out[plane + i] = ((p shr 8) and 0xFF) / 255f
                out[2 * plane + i] = (p and 0xFF) / 255f
            }
        }
    }

    /**
     * The part of the network's [output] (3 × outSide × outSide, RGB from 0 to
     * 1, twice the tile's side) that belongs to [tile], as opaque RGB ints:
     * all of it, or its top left for a tile cut short by the picture's edge.
     */
    fun output(output: FloatArray, outSide: Int, tile: Tile): IntArray {
        val w = tile.width * UpscaleModels.SCALE
        val h = tile.height * UpscaleModels.SCALE
        val plane = outSide * outSide
        val out = IntArray(w * h)
        if (NativeEngine.upscaleOutput(output, outSide, w, h, out)) return out
        for (y in 0 until h) {
            val row = y * outSide
            for (x in 0 until w) {
                val i = row + x
                out[y * w + x] = OPAQUE or (channel(output[i]) shl 16) or
                    (channel(output[plane + i]) shl 8) or channel(output[2 * plane + i])
            }
        }
        return out
    }

    /**
     * [pixels]' alpha as an opaque grey picture, for the network to enlarge
     * like any other: an edge drawn by it stays as crisp as the colour beside it.
     */
    fun alphaAsGrey(pixels: IntArray): IntArray = IntArray(pixels.size) {
        val a = pixels[it] ushr 24
        OPAQUE or (a shl 16) or (a shl 8) or a
    }

    /** The alpha a grey pixel the network drew stands for: the mean of its three channels. */
    fun greyToAlpha(p: Int): Int = (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF) + 1) / 3

    private fun channel(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)

    /**
     * Gives every fully transparent pixel of [pixels] the colour of the
     * visible pixels nearest it, in place. What sits under a transparent pixel
     * is whatever the encoder left there, often black, and the network would
     * draw it into the edge of the sticker as a dark fringe. Spread out a ring
     * at a time for [rings] rings; anything further away takes the average
     * visible colour, since nothing that far from an edge is ever seen.
     */
    fun bleed(pixels: IntArray, width: Int, height: Int, rings: Int = BLEED_RINGS) {
        val known = BooleanArray(pixels.size) { (pixels[it] ushr 24) > TRANSPARENT }
        var anyKnown = false
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0L
        for (i in pixels.indices) {
            if (!known[i]) continue
            anyKnown = true
            val p = pixels[i]
            sr += (p shr 16) and 0xFF
            sg += (p shr 8) and 0xFF
            sb += p and 0xFF
            n++
        }
        if (!anyKnown || n == pixels.size.toLong()) return
        // A ring is every unknown pixel touching a known one, coloured from
        // the pixels known before the ring and then made known. Only the
        // first ring needs the whole picture searched: the next one can only
        // be beside what the last ring added, so it is gathered from there.
        var ring = IntArray(pixels.size)
        var next = IntArray(pixels.size)
        val queued = BooleanArray(pixels.size)
        val colours = IntArray(pixels.size)
        var count = 0
        for (i in pixels.indices) {
            if (!known[i] && touchesKnown(known, i, width, height)) {
                ring[count++] = i
                queued[i] = true
            }
        }
        repeat(rings) {
            if (count == 0) return
            for (k in 0 until count) colours[k] = meanOfKnownAround(pixels, known, ring[k], width, height)
            for (k in 0 until count) {
                val i = ring[k]
                pixels[i] = (pixels[i] and ALPHA_MASK) or colours[k]
                known[i] = true
            }
            var nextCount = 0
            for (k in 0 until count) {
                val i = ring[k]
                val x = i % width
                val y = i / width
                for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
                    for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                        val j = ny * width + nx
                        if (!known[j] && !queued[j]) {
                            queued[j] = true
                            next[nextCount++] = j
                        }
                    }
                }
            }
            val done = ring
            ring = next
            next = done
            count = nextCount
        }
        val mean = ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
        for (i in pixels.indices) if (!known[i]) pixels[i] = (pixels[i] and ALPHA_MASK) or mean
    }

    private fun touchesKnown(known: BooleanArray, i: Int, width: Int, height: Int): Boolean {
        val x = i % width
        val y = i / width
        for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
            for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                if (known[ny * width + nx]) return true
            }
        }
        return false
    }

    /** The mean colour of the known pixels around [i], whose own pixel is unknown. */
    private fun meanOfKnownAround(pixels: IntArray, known: BooleanArray, i: Int, width: Int, height: Int): Int {
        val x = i % width
        val y = i / width
        var r = 0
        var g = 0
        var b = 0
        var c = 0
        for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
            for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                val j = ny * width + nx
                if (!known[j]) continue
                val p = pixels[j]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                c++
            }
        }
        return (r / c shl 16) or (g / c shl 8) or (b / c)
    }

    /** Whether any pixel of [pixels] is less than opaque. */
    fun hasAlpha(pixels: IntArray): Boolean = pixels.any { (it ushr 24) != 0xFF }

    /**
     * The largest of [UpscaleModels.FACTORS] that keeps a [width] by [height]
     * picture within [maxEdge] on its long side, or 0 when even the smallest
     * would not.
     */
    fun largestFactor(width: Int, height: Int, maxEdge: Int): Int =
        UpscaleModels.FACTORS.filter { maxOf(width, height) * it <= maxEdge }.maxOrNull() ?: 0

    private fun roundUp(v: Int, to: Int): Int = (v + to - 1) / to * to

    /** Tile sides are multiples of this. */
    private const val ALIGN = 8
    private const val OPAQUE = 0xFF shl 24
    private const val ALPHA_MASK = 0xFF shl 24

    /** Alpha at or under this is "fully transparent" to [bleed]: invisible after rounding. */
    private const val TRANSPARENT = 0

    /** Past [UpscaleModels.CONTEXT], the furthest the network reads from an edge, with room to spare. */
    const val BLEED_RINGS = 16
}
