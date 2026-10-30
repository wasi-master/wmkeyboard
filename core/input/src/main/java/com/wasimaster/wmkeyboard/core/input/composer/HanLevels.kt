package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.engine.NativeHandles

/**
 * How common a Chinese character is by the PRC's 通用规范汉字表 (2013): 3,500
 * level-1 characters for everyday writing, 3,000 level-2 ones, and 1,605
 * level-3 ones for names, places and specialist terms. Everything else in
 * Unicode — 琌, 硂 and the rest of the debris #633 found ranked under `qi` —
 * is not in the table at all.
 *
 * The conversion pack ranks a single character by how often it was seen in a
 * corpus, and a corpus is noisy exactly where it is thin: a rare character's
 * count can come from one misencoded file. The table is the opposite kind of
 * evidence, a fixed list of what people write, so the decoder takes it as a
 * cost on top of the frequency ([penalty]) and the two settle it together.
 *
 * Bundled as `dictionaries/han_levels.txt` (one line per level, `level<TAB>chars`),
 * built from the `kTGH` field of the Unicode Han Database, whose position
 * numbers are the table's own order. Empty until the service loads it, and
 * empty costs nothing, so tests and the other languages are unaffected.
 */
object HanLevels {

    /** Code points, sorted, each with its level in the parallel [levels]. */
    class Table internal constructor(private val points: IntArray, private val levels: ByteArray) {

        val isEmpty: Boolean get() = points.isEmpty()

        /** The same table on the decoder's side of the JNI line; 0 when empty. */
        internal val handle: Long = if (points.isEmpty()) 0L else CjkNative.levelsOpen(points, levels)

        init {
            if (handle != 0L) NativeHandles.track(this, handle, CjkNative::levelsClose)
        }

        /** 1, 2 or 3; 0 for a character the table does not list. */
        fun levelOf(codePoint: Int): Int {
            val i = points.binarySearch(codePoint)
            return if (i >= 0) levels[i].toInt() else 0
        }
    }

    val EMPTY = Table(IntArray(0), ByteArray(0))

    @Volatile
    var table: Table = EMPTY
        set(value) { field = value; CjkDictionaries.invalidate() }

    /**
     * What a single character costs by level, in nats. Level 1 is free; level
     * 3 sits about three times behind, and a character outside the table about
     * twelve times, which still lets a genuinely frequent one through.
     */
    private const val LEVEL_2 = -0.4
    private const val LEVEL_3 = -1.2
    private const val OUTSIDE = -2.5

    /**
     * What [word] costs for the characters it is written with. A single
     * character pays its level in full. A longer word pays only for a
     * character outside the table altogether: 琵琶 is a word people write
     * whatever its characters' levels, and its own frequency already says how
     * often.
     */
    fun penalty(word: String): Double {
        val t = table
        if (t.isEmpty || word.isEmpty()) return 0.0
        val single = word.codePointCount(0, word.length) == 1
        var worst = 0.0
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isIdeographic(cp)) continue
            val cost = when (t.levelOf(cp)) {
                1 -> 0.0
                2 -> if (single) LEVEL_2 else 0.0
                3 -> if (single) LEVEL_3 else 0.0
                else -> OUTSIDE
            }
            if (cost < worst) worst = cost
        }
        return worst
    }

    /** Parses the asset: `level<TAB>characters` per line, `#` comments skipped. */
    fun parse(lines: Sequence<String>): Table {
        val pairs = ArrayList<Long>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val level = line.substring(0, tab).toIntOrNull()?.takeIf { it in 1..3 } ?: continue
            val chars = line.substring(tab + 1)
            var i = 0
            while (i < chars.length) {
                val cp = chars.codePointAt(i)
                i += Character.charCount(cp)
                pairs.add(cp.toLong() shl 8 or level.toLong())
            }
        }
        if (pairs.isEmpty()) return EMPTY
        pairs.sort()
        val points = IntArray(pairs.size)
        val levels = ByteArray(pairs.size)
        var n = 0
        for (p in pairs) {
            val cp = (p shr 8).toInt()
            // A character listed twice keeps its first (lower) level.
            if (n > 0 && points[n - 1] == cp) continue
            points[n] = cp
            levels[n] = (p and 0xFF).toByte()
            n++
        }
        return Table(points.copyOf(n), levels.copyOf(n))
    }
}
