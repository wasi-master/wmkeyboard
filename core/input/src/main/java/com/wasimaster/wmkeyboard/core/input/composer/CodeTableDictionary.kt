package com.wasimaster.wmkeyboard.core.input.composer

/**
 * A code → Hanzi table for the shape-based Chinese input methods: 笔画 (stroke
 * order as the digits 1–5) and 倉頡 Cangjie (radical decomposition as the letters
 * a–y). Both encode a character's *appearance* rather than its sound, and both
 * spell exactly one character per code run, so they share this structure and
 * differ only in their alphabet and their on-screen key glyphs.
 *
 * Entries are kept sorted by code so a prefix query is a binary-searched
 * contiguous range rather than a full scan — a table can hold tens of thousands
 * of characters. A `.` in the query is a wildcard (any single code character); a
 * query containing one falls back to a linear pattern scan.
 *
 * Loaded from a downloadable pack; absent, the composer simply offers no
 * candidates. Line format: `code<TAB>hanzi<TAB>freq`.
 */
class CodeTableDictionary private constructor(
    private val codes: Array<String>,
    private val words: Array<String>,
    private val freqs: IntArray,
) {

    val isEmpty: Boolean get() = codes.isEmpty()

    /**
     * Character → its shortest code, built the first time [codeOf] is asked.
     * Pinyin's stroke filter reads the stroke table this way round: it has
     * the characters and wants to know how each one is written.
     */
    private val byWord: Map<String, String> by lazy {
        val out = HashMap<String, String>(words.size * 2)
        for (i in words.indices) {
            val current = out[words[i]]
            if (current == null || codes[i].length < current.length) out[words[i]] = codes[i]
        }
        out
    }

    /** The code [word] is written with, or null when the table has no entry for it. */
    fun codeOf(word: String): String? = if (codes.isEmpty()) null else byWord[word]

    /**
     * Characters whose code starts with [pattern], best first: a character whose
     * code is *exactly* what was typed leads, then the rest by frequency.
     *
     * Exactness has to outrank frequency here, because a shape code is a complete
     * spelling of one character rather than a prefix of a word. Ranking on
     * frequency alone buried 二 (code `11`, and rare) 271 places down its own
     * prefix behind common characters that merely start with those two strokes —
     * so typing a character's full code failed to offer it.
     */
    fun candidates(pattern: String, limit: Int = 24): List<String> {
        if (pattern.isEmpty() || codes.isEmpty()) return emptyList()
        val hits = if ('.' in pattern) wildcardHits(pattern) else prefixHits(pattern)
        return rank(hits, limit) { codes[it].length == pattern.length }
    }

    /**
     * Characters whose code starts with [first] and ends with [last] — Cangjie
     * Quick (速成), which types only the first and last radical of a character
     * instead of all of them. Served from the same table as full Cangjie: no
     * second pack, because Quick is a different *query* over one code set, not a
     * different code set.
     *
     * Costs two binary searches to reach the [first] bucket, then a scan bounded
     * to that one bucket — never the whole table, unlike the wildcard path. A
     * length-1 code matches when its single char is both first and last.
     */
    fun quickCandidates(first: Char, last: Char, limit: Int = 24): List<String> {
        if (codes.isEmpty()) return emptyList()
        val lo = lowerBound(first.toString())
        val hi = lowerBound(first.toString() + '￿')
        val hits = (lo until hi).filter { codes[it].last() == last }
        return rank(hits, limit)
    }

    /** Indices whose code starts with [prefix], via the sorted range [lo, hi). */
    private fun prefixHits(prefix: String): List<Int> {
        val lo = lowerBound(prefix)
        val hi = lowerBound(prefix + '￿')
        return (lo until hi).toList()
    }

    /** Indices matching [pattern] where `.` is any code char; linear (wildcards are rare). */
    private fun wildcardHits(pattern: String): List<Int> {
        val out = ArrayList<Int>()
        for (i in codes.indices) if (matches(codes[i], pattern)) out.add(i)
        return out
    }

    private fun matches(code: String, pattern: String): Boolean {
        if (code.length < pattern.length) return false
        for (j in pattern.indices) {
            val p = pattern[j]
            if (p != '.' && p != code[j]) return false
        }
        return true
    }

    /**
     * De-duplicated words for a set of row indices: rows [preferred] (an exact
     * code match) first, each group most-frequent first.
     */
    private fun rank(
        hits: List<Int>,
        limit: Int,
        preferred: (Int) -> Boolean = { false },
    ): List<String> = hits
        .sortedWith(compareByDescending<Int> { preferred(it) }.thenByDescending { freqs[it] })
        .map { words[it] }
        .distinct()
        .take(limit)

    /** First index whose code is >= [key] (binary search over the sorted codes). */
    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = codes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (codes[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val EMPTY = CodeTableDictionary(emptyArray(), emptyArray(), IntArray(0))

        /** 笔画 stroke codes: the five stroke classes, as the digits 1–5. */
        val STROKE_CODE: (String) -> Boolean = { code -> code.all { it in '1'..'5' } }

        /** Cangjie codes: at most five of the 24 radical letters a–y. */
        val CANGJIE_CODE: (String) -> Boolean =
            { code -> code.length <= 5 && code.all { it in 'a'..'y' } }

        /** Wubi 86 codes: at most four of the 25 letters a–y; `z` is a wildcard, typed only. */
        val WUBI_CODE: (String) -> Boolean =
            { code -> code.length <= 4 && code.all { it in 'a'..'y' } }

        /**
         * Parses `code<TAB>hanzi<TAB>freq` lines into a sorted table, dropping any
         * row [isValidCode] rejects. Each table passes its own alphabet so a
         * malformed row is discarded at parse time rather than sitting in the
         * index under a code no keystroke can produce — and so a wildcard `.`,
         * which is query-only, can never leak into stored data.
         */
        fun parse(
            lines: Sequence<String>,
            isValidCode: (String) -> Boolean = { true },
        ): CodeTableDictionary {
            data class Row(val code: String, val word: String, val freq: Int)
            val rows = ArrayList<Row>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split('\t')
                if (parts.size < 2) continue
                val code = parts[0].trim()
                val word = parts[1].trim()
                if (code.isEmpty() || word.isEmpty() || !isValidCode(code)) continue
                rows.add(Row(code, word, parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0))
            }
            rows.sortBy { it.code }
            return CodeTableDictionary(
                Array(rows.size) { rows[it].code },
                Array(rows.size) { rows[it].word },
                IntArray(rows.size) { rows[it].freq },
            )
        }
    }
}
