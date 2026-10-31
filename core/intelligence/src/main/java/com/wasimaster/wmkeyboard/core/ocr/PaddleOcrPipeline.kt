package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.core.engine.NativeEngine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * PaddleOCR's two-stage read in plain Kotlin, around the two graphs a
 * [PaddleRuntime] runs: DB text detection, then a CTC line recognizer.
 *
 * 1. The photo is shrunk (or grown) to fit the detector's fixed square, top
 *    left, and normalised the way PaddleOCR trains: BGR, ImageNet mean and
 *    deviation.
 * 2. The detector answers how likely each pixel is to be text. Pixels above
 *    [BINARY_THRESHOLD] are joined into regions; a region whose mean
 *    probability clears [BOX_THRESHOLD] becomes the smallest rotated
 *    rectangle around it, grown by [UNCLIP_RATIO] the way DB's training
 *    shrank it (offset = area × ratio / perimeter).
 * 3. Each rectangle is sampled straight out of the full photo, rotated level
 *    and scaled to the recognizer's 48 pixel height. A rectangle at least
 *    [VERTICAL_RATIO] times taller than wide is vertical writing and is
 *    turned a quarter anticlockwise first, as PaddleOCR does, so the column's
 *    top comes first.
 * 4. The recognizer's input is only 320 pixels wide, about seven CJK
 *    characters, so a longer line is cut into pieces at its emptiest column
 *    between [CHUNK_MIN_WIDTH] and 320 (the gap between two characters), and
 *    each piece read on its own, right-padded.
 * 5. Greedy CTC: the best class per step, repeats merged, blanks dropped.
 *
 * Pixels are ARGB ints as `Bitmap.getPixels` gives them, so all of this runs
 * on the JVM in tests.
 */
internal object PaddleOcrPipeline {

    const val DET_SIDE = 640
    const val REC_HEIGHT = 48
    const val REC_WIDTH = 320

    // PaddleOCR's own defaults for PP-OCRv5 (det_db_thresh, det_db_box_thresh,
    // det_db_unclip_ratio, and the recogniser's drop_score).
    private const val BINARY_THRESHOLD = 0.3f
    private const val BOX_THRESHOLD = 0.6f
    private const val UNCLIP_RATIO = 1.5f
    private const val DROP_SCORE = 0.5f

    private const val MIN_REGION_PIXELS = 10
    private const val MIN_BOX_SIDE = 3f
    private const val VERTICAL_RATIO = 1.5f
    private const val CHUNK_MIN_WIDTH = 240

    // A line longer than this many recogniser widths is cut short: nothing
    // printed is, and it bounds the work one stray region can cause.
    private const val MAX_LINE_CHUNKS = 24

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    private val NORM = MEAN + STD

    /**
     * A text region in photo pixels: its centre, its size along and across
     * [angle] (radians, folded into ±45°), so [width] runs with a level line.
     */
    data class TextBox(val cx: Float, val cy: Float, val width: Float, val height: Float, val angle: Float) {
        val vertical: Boolean get() = height >= VERTICAL_RATIO * width
    }

    /** How the photo was fitted into the detector's square: its scale and the size it took. */
    class Letterbox(val scale: Float, val width: Int, val height: Int)

    /** One read line, with the recognizer's mean confidence in its characters. */
    class Line(val text: String, val confidence: Float)

    /**
     * The recognizer alone, over [boxes] the caller already has, for lone letters the
     * detector skips. Keeps what reads as one character at [floor] or better: one
     * glyph on its own scores lower than a line does, though it is usually right.
     */
    fun readAt(
        runtime: PaddleRuntime,
        pixels: IntArray,
        width: Int,
        height: Int,
        charset: List<String>,
        boxes: List<TextBox>,
        floor: Float,
    ): List<Pair<String, TextBox>> {
        val recIn = FloatArray(REC_HEIGHT * REC_WIDTH * 3)
        val recOut = FloatArray(runtime.recognizerSteps * runtime.recognizerClasses)
        val found = ArrayList<Pair<String, TextBox>>()
        for (box in boxes) {
            val strip = strip(pixels, width, height, box) ?: continue
            val pieces = cuts(strip).zipWithNext().map { (from, to) ->
                recognizerInput(strip, from, to, recIn)
                runtime.recognize(recIn, recOut)
                ctcDecode(recOut, runtime.recognizerSteps, runtime.recognizerClasses, charset)
            }
            val text = pieces.joinToString("") { it.text }.trim()
            if (text.length == 1 && pieces.map { it.confidence }.average().toFloat() >= floor) found += text to box
        }
        return found
    }

    /**
     * The whole read: lines of text in reading order, each the recognized
     * regions on one row joined by spaces. [isActive] is asked between
     * steps, so a cancelled read stops early (and returns what it has).
     */
    fun read(
        runtime: PaddleRuntime,
        pixels: IntArray,
        width: Int,
        height: Int,
        charset: List<String>,
        isActive: () -> Boolean = { true },
    ): List<String> {
        val detIn = FloatArray(DET_SIDE * DET_SIDE * 3)
        val letterbox = detectorInput(pixels, width, height, detIn)
        val prob = FloatArray(DET_SIDE * DET_SIDE)
        runtime.detect(detIn, prob)
        val rows = readingOrder(boxes(prob, letterbox))

        val recIn = FloatArray(REC_HEIGHT * REC_WIDTH * 3)
        val recOut = FloatArray(runtime.recognizerSteps * runtime.recognizerClasses)
        val lines = ArrayList<String>()
        for (row in rows) {
            val parts = ArrayList<String>()
            for (box in row) {
                if (!isActive()) return lines
                val strip = strip(pixels, width, height, box) ?: continue
                val pieces = cuts(strip).zipWithNext().map { (from, to) ->
                    recognizerInput(strip, from, to, recIn)
                    runtime.recognize(recIn, recOut)
                    ctcDecode(recOut, runtime.recognizerSteps, runtime.recognizerClasses, charset)
                }
                val text = pieces.joinToString("") { it.text }.trim()
                val confidence = pieces.map { it.confidence }.average().toFloat()
                if (text.isNotEmpty() && confidence >= DROP_SCORE) parts += text
            }
            if (parts.isNotEmpty()) lines += parts.joinToString(" ")
        }
        return lines
    }

    /**
     * Fits the photo into [input], a [DET_SIDE] square, top left, at one
     * scale, normalised BGR. The rest of the square stays 0, which is the
     * mean colour after normalising. Shrinks by averaging each output
     * pixel's whole footprint, so fine print does not alias away; that
     * shrink runs in the native engine's NEON kernel when it can
     * (`kernels/box_average.rs`, bit for bit the loop below).
     */
    fun detectorInput(pixels: IntArray, width: Int, height: Int, input: FloatArray): Letterbox {
        val scale = DET_SIDE.toFloat() / max(width, height)
        val outW = (width * scale).roundToInt().coerceIn(1, DET_SIDE)
        val outH = (height * scale).roundToInt().coerceIn(1, DET_SIDE)
        if (scale < 1f && NativeEngine.boxAverageBgr(pixels, width, height, scale, outW, outH, DET_SIDE, NORM, input)) {
            return Letterbox(scale, outW, outH)
        }
        input.fill(0f)
        val rgb = FloatArray(3)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                if (scale < 1f) average(pixels, width, height, x / scale, y / scale, (x + 1) / scale, (y + 1) / scale, rgb)
                else bilinear(pixels, width, height, (x + 0.5f) / scale - 0.5f, (y + 0.5f) / scale - 0.5f, rgb)
                val at = (y * DET_SIDE + x) * 3
                // BGR, as PaddleOCR reads photos with OpenCV.
                input[at] = (rgb[2] / 255f - MEAN[0]) / STD[0]
                input[at + 1] = (rgb[1] / 255f - MEAN[1]) / STD[1]
                input[at + 2] = (rgb[0] / 255f - MEAN[2]) / STD[2]
            }
        }
        return Letterbox(scale, outW, outH)
    }

    /** The text regions in the detector's [prob] map, in photo pixels. */
    fun boxes(prob: FloatArray, letterbox: Letterbox): List<TextBox> {
        val w = letterbox.width
        val h = letterbox.height
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<TextBox>()
        // Per region: the leftmost and rightmost pixel of each row it covers,
        // which is all its convex hull needs.
        val rowMin = IntArray(h)
        val rowMax = IntArray(h)
        for (start in 0 until w * h) {
            if (seen[start] || prob[(start / w) * DET_SIDE + start % w] <= BINARY_THRESHOLD) continue
            var top = h
            var bottom = -1
            var count = 0
            var sum = 0.0
            var size = 0
            stack[size++] = start
            seen[start] = true
            while (size > 0) {
                val at = stack[--size]
                val x = at % w
                val y = at / w
                count++
                sum += prob[y * DET_SIDE + x]
                if (y < top) {
                    for (r in y until min(top, h)) { rowMin[r] = Int.MAX_VALUE; rowMax[r] = -1 }
                    top = y
                }
                if (y > bottom) {
                    for (r in max(bottom + 1, top) until y + 1) { rowMin[r] = Int.MAX_VALUE; rowMax[r] = -1 }
                    bottom = y
                }
                if (x < rowMin[y]) rowMin[y] = x
                if (x > rowMax[y]) rowMax[y] = x
                if (x > 0) size = visit(at - 1, prob, w, seen, stack, size)
                if (x < w - 1) size = visit(at + 1, prob, w, seen, stack, size)
                if (y > 0) size = visit(at - w, prob, w, seen, stack, size)
                if (y < h - 1) size = visit(at + w, prob, w, seen, stack, size)
            }
            if (count < MIN_REGION_PIXELS || sum / count < BOX_THRESHOLD) continue
            val points = ArrayList<Long>()
            for (r in top..bottom) {
                if (rowMax[r] < 0) continue
                // Pixel corners, not centres, so a one-pixel-tall row has height.
                points += pack(rowMin[r], r); points += pack(rowMax[r] + 1, r)
                points += pack(rowMin[r], r + 1); points += pack(rowMax[r] + 1, r + 1)
            }
            val box = minAreaRect(hull(points)) ?: continue
            if (min(box.width, box.height) < MIN_BOX_SIDE) continue
            val grow = box.width * box.height * UNCLIP_RATIO / (2 * (box.width + box.height))
            val s = letterbox.scale
            out += TextBox(box.cx / s, box.cy / s, (box.width + 2 * grow) / s, (box.height + 2 * grow) / s, box.angle)
        }
        return out
    }

    private fun visit(at: Int, prob: FloatArray, w: Int, seen: BooleanArray, stack: IntArray, size: Int): Int {
        if (seen[at] || prob[(at / w) * DET_SIDE + at % w] <= BINARY_THRESHOLD) return size
        seen[at] = true
        stack[size] = at
        return size + 1
    }

    private fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    private fun px(p: Long): Int = (p shr 32).toInt()
    private fun py(p: Long): Int = p.toInt()

    /** Andrew's monotone chain, anticlockwise in image coordinates' terms. */
    fun hull(points: List<Long>): List<Long> {
        val sorted = points.distinct().sortedWith(compareBy({ px(it) }, { py(it) }))
        if (sorted.size < 3) return sorted
        fun cross(o: Long, a: Long, b: Long): Long =
            (px(a) - px(o)).toLong() * (py(b) - py(o)) - (py(a) - py(o)).toLong() * (px(b) - px(o))
        val out = ArrayList<Long>(sorted.size * 2)
        for (p in sorted) {
            while (out.size >= 2 && cross(out[out.size - 2], out[out.size - 1], p) <= 0) out.removeAt(out.size - 1)
            out += p
        }
        val lower = out.size + 1
        for (i in sorted.size - 2 downTo 0) {
            val p = sorted[i]
            while (out.size >= lower && cross(out[out.size - 2], out[out.size - 1], p) <= 0) out.removeAt(out.size - 1)
            out += p
        }
        out.removeAt(out.size - 1)
        return out
    }

    /**
     * The smallest rectangle around [hull], by trying each hull edge as a
     * side (rotating calipers). Null for a degenerate hull.
     */
    fun minAreaRect(hull: List<Long>): TextBox? {
        if (hull.size < 3) return null
        var bestArea = Float.MAX_VALUE
        var best: FloatArray? = null
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val angle = atan2((py(b) - py(a)).toFloat(), (px(b) - px(a)).toFloat())
            val c = cos(angle)
            val s = sin(angle)
            var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE
            var v0 = Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
            for (p in hull) {
                val u = px(p) * c + py(p) * s
                val v = -px(p) * s + py(p) * c
                if (u < u0) u0 = u
                if (u > u1) u1 = u
                if (v < v0) v0 = v
                if (v > v1) v1 = v
            }
            val area = (u1 - u0) * (v1 - v0)
            if (area < bestArea) {
                bestArea = area
                best = floatArrayOf(angle, u0, u1, v0, v1)
            }
        }
        val (angle0, u0, u1, v0, v1) = best ?: return null
        val c = cos(angle0)
        val s = sin(angle0)
        val uc = (u0 + u1) / 2
        val vc = (v0 + v1) / 2
        var angle = angle0
        var w = u1 - u0
        var h = v1 - v0
        val quarter = (Math.PI / 2).toFloat()
        while (angle > quarter / 2) { angle -= quarter; val t = w; w = h; h = t }
        while (angle <= -quarter / 2) { angle += quarter; val t = w; w = h; h = t }
        return TextBox(uc * c - vc * s, uc * s + vc * c, w, h, angle)
    }

    /**
     * [boxes] as rows to read: level boxes top to bottom, those sharing a
     * row left to right; then vertical columns, right to left, as vertical
     * Japanese and Chinese are read.
     */
    fun readingOrder(boxes: List<TextBox>): List<List<TextBox>> {
        val rows = ArrayList<MutableList<TextBox>>()
        var rowCy = 0f
        var rowHeight = 0f
        for (box in boxes.filter { !it.vertical }.sortedBy { it.cy }) {
            val row = rows.lastOrNull()
            if (row != null && abs(box.cy - rowCy) < min(box.height, rowHeight) / 2) {
                row += box
            } else {
                rows += mutableListOf(box)
                rowCy = box.cy
                rowHeight = box.height
            }
        }
        rows.forEach { row -> row.sortBy { it.cx } }
        boxes.filter { it.vertical }.sortedByDescending { it.cx }.forEach { rows += mutableListOf(it) }
        return rows
    }

    /** A level line [width] pixels long, [REC_HEIGHT] tall: RGB per pixel, 0 to 255. */
    class Strip(val width: Int, val rgb: FloatArray)

    /**
     * [box] cut out of the photo, levelled and scaled to [REC_HEIGHT], by
     * sampling the photo directly. A vertical box is turned a quarter
     * anticlockwise, so its top comes first. Null for an empty box.
     */
    fun strip(pixels: IntArray, width: Int, height: Int, box: TextBox): Strip? {
        val along = if (box.vertical) box.height else box.width
        val across = if (box.vertical) box.width else box.height
        if (along < 1f || across < 1f) return null
        val outW = (along * REC_HEIGHT / across).roundToInt()
            .coerceIn(1, REC_WIDTH * MAX_LINE_CHUNKS)
        val rgb = FloatArray(outW * REC_HEIGHT * 3)
        val c = cos(box.angle)
        val s = sin(box.angle)
        val sample = FloatArray(3)
        for (y in 0 until REC_HEIGHT) {
            for (x in 0 until outW) {
                val fx = (x + 0.5f) / outW
                val fy = (y + 0.5f) / REC_HEIGHT
                // (u, v): offsets from the centre along and across the box's own axes.
                val u: Float
                val v: Float
                if (box.vertical) {
                    u = box.width / 2 - fy * box.width
                    v = fx * box.height - box.height / 2
                } else {
                    u = fx * box.width - box.width / 2
                    v = fy * box.height - box.height / 2
                }
                val sx = box.cx + u * c - v * s
                val sy = box.cy + u * s + v * c
                bilinear(pixels, width, height, sx - 0.5f, sy - 0.5f, sample)
                val at = (y * outW + x) * 3
                rgb[at] = sample[0]; rgb[at + 1] = sample[1]; rgb[at + 2] = sample[2]
            }
        }
        return Strip(outW, rgb)
    }

    /**
     * Where to cut [strip] into recognizer-sized pieces: 0, then for each
     * piece the column with the least ink between [CHUNK_MIN_WIDTH] and
     * [REC_WIDTH] past the last cut, then the end.
     */
    fun cuts(strip: Strip): List<Int> {
        val cuts = arrayListOf(0)
        if (strip.width <= REC_WIDTH) return cuts.apply { add(strip.width) }
        val gray = FloatArray(strip.width * REC_HEIGHT)
        val histogram = IntArray(256)
        for (i in gray.indices) {
            val g = (strip.rgb[i * 3] + strip.rgb[i * 3 + 1] + strip.rgb[i * 3 + 2]) / 3
            gray[i] = g
            histogram[g.toInt().coerceIn(0, 255)]++
        }
        // The background is the photo's most common shade: the median.
        var background = 0
        var seen = 0
        while (seen + histogram[background] <= gray.size / 2) seen += histogram[background++]
        val ink = FloatArray(strip.width)
        for (y in 0 until REC_HEIGHT) for (x in 0 until strip.width) ink[x] += abs(gray[y * strip.width + x] - background)
        while (strip.width - cuts.last() > REC_WIDTH) {
            val from = cuts.last() + CHUNK_MIN_WIDTH
            val to = cuts.last() + REC_WIDTH
            var best = from
            for (x in from until to) if (ink[x] < ink[best]) best = x
            cuts += best
        }
        cuts += strip.width
        return cuts
    }

    /** Columns [from] to [to] of [strip] into [input], normalised to ±1, the rest padded with 0. */
    fun recognizerInput(strip: Strip, from: Int, to: Int, input: FloatArray) {
        input.fill(0f)
        val span = min(to - from, REC_WIDTH)
        for (y in 0 until REC_HEIGHT) {
            for (x in 0 until span) {
                val src = (y * strip.width + from + x) * 3
                val dst = (y * REC_WIDTH + x) * 3
                // BGR again.
                input[dst] = (strip.rgb[src + 2] - 127.5f) / 127.5f
                input[dst + 1] = (strip.rgb[src + 1] - 127.5f) / 127.5f
                input[dst + 2] = (strip.rgb[src] - 127.5f) / 127.5f
            }
        }
    }

    /**
     * Greedy CTC over [output] ([steps] × [classes] probabilities): the best
     * class at each step, a run of the same class read once, class 0 (the
     * blank) dropped. [charset] maps class to text, see [charset].
     */
    fun ctcDecode(output: FloatArray, steps: Int, classes: Int, charset: List<String>): Line {
        val bests = bestClasses(output, steps, classes)
        val text = StringBuilder()
        var previous = 0
        var sum = 0f
        var count = 0
        for (t in 0 until steps) {
            val best = bests[t]
            if (best != 0 && best != previous) {
                text.append(charset.getOrElse(best) { "" })
                sum += output[t * classes + best]
                count++
            }
            previous = best
        }
        return Line(text.toString(), if (count == 0) 0f else sum / count)
    }

    /**
     * The first best class at each step: in the native engine's NEON
     * kernel when it can (`kernels/argmax.rs`, the same strict `>` scan),
     * else here.
     */
    private fun bestClasses(output: FloatArray, steps: Int, classes: Int): IntArray {
        val bests = IntArray(steps)
        if (output.size == steps * classes && NativeEngine.argmaxRows(output, classes, bests)) return bests
        for (t in 0 until steps) {
            var best = 0
            var bestValue = output[t * classes]
            for (k in 1 until classes) {
                val value = output[t * classes + k]
                if (value > bestValue) { best = k; bestValue = value }
            }
            bests[t] = best
        }
        return bests
    }

    /**
     * The recognizer's classes from its character list (one per line): the
     * CTC blank first, then the list, then a space, which PP-OCRv5 adds last.
     */
    fun charset(keys: String): List<String> {
        val lines = keys.split('\n').map { it.removeSuffix("\r") }
        val list = if (lines.lastOrNull()?.isEmpty() == true) lines.dropLast(1) else lines
        return listOf("") + list + " "
    }

    // RGB of the photo at (x, y), pixel centres at integers, clamped at the edges.
    private fun bilinear(pixels: IntArray, width: Int, height: Int, x: Float, y: Float, out: FloatArray) {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val xa = x0.coerceIn(0, width - 1)
        val xb = (x0 + 1).coerceIn(0, width - 1)
        val ya = y0.coerceIn(0, height - 1)
        val yb = (y0 + 1).coerceIn(0, height - 1)
        val p00 = pixels[ya * width + xa]
        val p01 = pixels[ya * width + xb]
        val p10 = pixels[yb * width + xa]
        val p11 = pixels[yb * width + xb]
        for (ch in 0 until 3) {
            val shift = 16 - ch * 8
            val top = ((p00 shr shift) and 0xff) * (1 - fx) + ((p01 shr shift) and 0xff) * fx
            val bottom = ((p10 shr shift) and 0xff) * (1 - fx) + ((p11 shr shift) and 0xff) * fx
            out[ch] = top * (1 - fy) + bottom * fy
        }
    }

    // Mean RGB over the photo rectangle [x0, x1) × [y0, y1), whole pixels.
    private fun average(
        pixels: IntArray, width: Int, height: Int,
        x0: Float, y0: Float, x1: Float, y1: Float, out: FloatArray,
    ) {
        val xa = floor(x0).toInt().coerceIn(0, width - 1)
        val xb = ceil(x1).toInt().coerceIn(xa + 1, width)
        val ya = floor(y0).toInt().coerceIn(0, height - 1)
        val yb = ceil(y1).toInt().coerceIn(ya + 1, height)
        var r = 0L; var g = 0L; var b = 0L
        for (y in ya until yb) {
            for (x in xa until xb) {
                val p = pixels[y * width + x]
                r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff
            }
        }
        val n = ((xb - xa) * (yb - ya)).toFloat()
        out[0] = r / n; out[1] = g / n; out[2] = b / n
    }
}
