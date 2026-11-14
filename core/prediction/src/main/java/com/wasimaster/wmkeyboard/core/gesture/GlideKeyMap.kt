package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
import com.wasimaster.wmkeyboard.core.prediction.PredictionNative

/**
 * The letter grid a glide is drawn on: every character the layout can produce,
 * resolved to the key that produces it.
 *
 * Two things separate this from the raw [KeyCenter] list it is built from.
 *
 * **Several characters share one key.** On Latin that is only case — `a` and
 * `A` are one key — but it is the general rule, not a Latin quirk. Probhat puts
 * ক and খ on one key, ঞ behind a long press on another; QWERTZ hides ü behind
 * u. A finger crossing a key cannot say which of its characters was meant, so
 * all of them resolve to the same centre and the language model separates them
 * afterwards. Refusing the shifted characters instead would make every aspirated
 * Bengali consonant unglidable, which is most of the language.
 *
 * **Lookup is boxing-free.** The decoder resolves an edge label to a key for
 * every trie edge it considers — hundreds of thousands of times per decode — so
 * the map is a sorted [IntArray] searched by bisection rather than a
 * `HashMap<Int, Int>`, whose keys would box on every probe for any character
 * outside the cached ASCII range (which is to say, every non-Latin script).
 *
 * Keyed by code point, not `Char`, so a letter outside the BMP — every Osage,
 * Adlam or Warang Citi key — is one entry and not two halves of one. The `Char`
 * overloads answer for a single UTF-16 unit and say no to a lone surrogate,
 * which is never a character the grid could produce.
 *
 * Coordinates are in key widths, the convention shared with `KeyTouchModel`, so
 * nothing downstream depends on density or keyboard size.
 */
class GlideKeyMap private constructor(
    /** Distinct key centres, x then y, in key widths. */
    val keyX: FloatArray,
    val keyY: FloatArray,
    private val codePoints: IntArray,
    private val keyOf: IntArray,
    private val primary: IntArray,
) {

    /** How many distinct keys the grid has — the width of the decoder's masks. */
    val keyCount: Int get() = keyX.size

    /**
     * Centre-to-centre distances, `keyCount²` of them. The decoder asks for one
     * on every trie edge it considers, so the square root is paid once per grid
     * rather than a hundred thousand times per stroke.
     */
    private val distances: FloatArray = FloatArray(keyX.size * keyX.size).also { table ->
        for (a in keyX.indices) {
            for (b in keyX.indices) {
                val dx = keyX[a] - keyX[b]
                val dy = keyY[a] - keyY[b]
                table[a * keyX.size + b] = kotlin.math.sqrt(dx * dx + dy * dy)
            }
        }
    }

    /** Distance between two keys in key widths. */
    fun distance(a: Int, b: Int): Float = distances[a * keyX.size + b]

    /** The Rust copy of this map, for the glide decoder; made on first use and closed with the map. */
    internal val nativeHandle: Long by lazy {
        PredictionNative.glideKeyMapOpen(keyX, keyY, codePoints, keyOf).also {
            NativeHandles.track(this, it, PredictionNative::glideKeyMapClose)
        }
    }

    /**
     * Where the grid's top row ends upward, in key widths: half a row pitch
     * above the highest key a character resolves to, the row pitch being the
     * gap down to the next row. What [GlideOverhang] folds a stroke back
     * under. NaN on a grid of one row, which has no pitch to measure.
     *
     * Keys no character resolves to are left out: a carrier whose letters
     * every other key already claimed collapses to a centre of its own in
     * [of], and a key that can spell nothing is not part of the grid's top.
     */
    val topEdge: Float by lazy {
        var top = Float.POSITIVE_INFINITY
        for (key in keyOf.toHashSet()) top = minOf(top, keyY[key])
        top - rowPitch / 2f
    }

    /**
     * The gap from the grid's top row down to the next, in key widths: the
     * row pitch [topEdge] and [GlideOverhang.escaped] measure in. NaN on a
     * grid of one row.
     */
    val rowPitch: Float by lazy {
        val owned = keyOf.toHashSet()
        var top = Float.POSITIVE_INFINITY
        for (key in owned) top = minOf(top, keyY[key])
        var next = Float.POSITIVE_INFINITY
        for (key in owned) {
            val y = keyY[key]
            if (y > top + ROW_TOLERANCE && y < next) next = y
        }
        if (next.isInfinite()) Float.NaN else next - top
    }

    /** True when [codePoint] is somewhere on the grid. */
    fun knows(codePoint: Int): Boolean = keyIndex(codePoint) >= 0

    /** True when [ch] is somewhere on the grid. */
    fun knows(ch: Char): Boolean = knows(ch.code)

    /**
     * Index of the key that produces [codePoint], or -1 when the grid cannot.
     * Case is folded, so a dictionary's lowercase spelling finds an uppercase key.
     */
    fun keyIndex(codePoint: Int): Int {
        val folded = Character.toLowerCase(codePoint)
        var lo = 0
        var hi = codePoints.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val here = codePoints[mid]
            when {
                here < folded -> lo = mid + 1
                here > folded -> hi = mid - 1
                else -> return keyOf[mid]
            }
        }
        return -1
    }

    /** Index of the key that produces [ch], or -1 — always -1 for a surrogate. */
    fun keyIndex(ch: Char): Int = keyIndex(ch.code)

    /**
     * The character [key] was first reported with, lowercased: its base label
     * in the caller's priority order. What a decoder that wants one code per
     * key (AOSP's gesture library) labels the key with.
     */
    fun primaryCodePoint(key: Int): Int = primary[key]

    companion object {

        /**
         * Builds the grid from [keys], whose coordinates are in the pixel space
         * of a keyboard with keys [keyWidth] wide.
         *
         * Entries sharing a position collapse to one key: the caller emits one
         * [KeyCenter] per character a key can produce, and several characters
         * landing on the same centre is the normal case, not a mistake. Where
         * two keys claim the same character the first wins, which makes the
         * caller's order the priority order (base label, then shifted, then
         * long-press).
         */
        fun of(keys: List<KeyCenter>, keyWidth: Float): GlideKeyMap {
            require(keyWidth > 0f) { "keyWidth must be positive" }
            val xs = ArrayList<Float>(keys.size)
            val ys = ArrayList<Float>(keys.size)
            val byCodePoint = LinkedHashMap<Int, Int>(keys.size * 2)
            val primary = ArrayList<Int>(keys.size)
            for (key in keys) {
                val x = key.x / keyWidth
                val y = key.y / keyWidth
                var index = -1
                for (i in xs.indices) {
                    if (near(xs[i], x) && near(ys[i], y)) {
                        index = i
                        break
                    }
                }
                if (index < 0) {
                    index = xs.size
                    xs.add(x)
                    ys.add(y)
                    primary.add(Character.toLowerCase(key.codePoint))
                }
                byCodePoint.putIfAbsent(Character.toLowerCase(key.codePoint), index)
            }
            val sorted = byCodePoint.keys.sorted()
            val codePoints = IntArray(sorted.size) { sorted[it] }
            val keyOf = IntArray(sorted.size) { byCodePoint.getValue(sorted[it]) }
            return GlideKeyMap(xs.toFloatArray(), ys.toFloatArray(), codePoints, keyOf, primary.toIntArray())
        }

        /**
         * Two centres are the same key when they agree to well under a pixel.
         * Measured centres arrive from `onGloballyPositioned` as floats and the
         * characters of one key are reported from one measurement, so exact
         * equality would very nearly work; the epsilon covers the arithmetic.
         */
        private fun near(a: Float, b: Float): Boolean =
            kotlin.math.abs(a - b) < POSITION_EPSILON

        private const val POSITION_EPSILON = 1e-3f

        /**
         * How far below the top row's centre, in key widths, a key may sit and
         * still be on that row for [topEdge]: a staggered or hand-moved key is
         * never a whole row out, and a quarter key is well under any row pitch.
         */
        private const val ROW_TOLERANCE = 0.25f

        /**
         * A stable identity for the grid [keys] describe: which character sits
         * where, to a quarter of a key. Two layouts with the same geometry
         * share one; portrait and landscape do not, since their row pitch
         * differs in key widths. Taken from the keys as drawn, so it does not
         * move when the hand model moves them — it is what the learned shapes
         * are filed under.
         */
        fun fingerprint(keys: List<KeyCenter>, keyWidth: Float): Long {
            val sorted = keys.sortedWith(compareBy({ it.codePoint }, { it.x }, { it.y }))
            var hash = FNV_OFFSET
            for (key in sorted) {
                hash = mix(hash, key.codePoint)
                hash = mix(hash, kotlin.math.round(key.x / keyWidth * FINGERPRINT_GRID).toInt())
                hash = mix(hash, kotlin.math.round(key.y / keyWidth * FINGERPRINT_GRID).toInt())
            }
            return hash
        }

        private fun mix(hash: Long, value: Int): Long = (hash xor value.toLong()) * FNV_PRIME

        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L

        /** Cells per key width the fingerprint resolves a position to. */
        private const val FINGERPRINT_GRID = 4f
    }
}
