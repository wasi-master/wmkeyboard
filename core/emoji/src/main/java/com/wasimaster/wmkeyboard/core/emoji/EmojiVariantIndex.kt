package com.wasimaster.wmkeyboard.core.emoji

import java.io.InputStream

/**
 * Exact RGI skin-toned sequences, keyed by their tone-free base emoji.
 *
 * Data comes from the bundled `emoji/variants.tsv` (generated straight out
 * of Unicode's emoji-test.txt by tools/emoji/generate_catalog.py), so every
 * sequence here is a real, fully-qualified emoji — no runtime composition
 * that could produce an unsupported combination.
 *
 * Tone indices are Fitzpatrick 1..5 (🏻..🏿); 0 means "no tone" (the
 * neutral yellow base). Bases like the handshake carry two independent
 * tone slots — [tonedPair] resolves any left/right combination.
 */
class EmojiVariantIndex private constructor(
    /** Every base with tones, sorted. */
    private val bases: Array<String>,
    /** Base `b`'s rows are `rowStart[b] until rowStart[b + 1]`, in file order. */
    private val rowStart: IntArray,
    /** Each row's tones as octal digits, first tone first: 3 is 🏽, 1,4 is `0o14`. */
    private val tones: IntArray,
    private val sequences: Array<String>,
) {

    /** True when [base] has any skin-tone variants at all. */
    fun hasTones(base: String): Boolean = slot(base) >= 0

    /** True when [base] supports two independent tones (handshake, couples…). */
    fun hasDualTones(base: String): Boolean {
        val b = slot(base)
        if (b < 0) return false
        for (r in rowStart[b] until rowStart[b + 1]) if (tones[r] in DUAL_CODES) return true
        return false
    }

    /**
     * The five uniformly-toned variants of [base], in tone order 1..5.
     * Empty when the base takes no tones.
     */
    fun uniformVariants(base: String): List<String> {
        val b = slot(base)
        if (b < 0) return emptyList()
        return (1..5).mapNotNull { tone -> row(b, tone)?.let { sequences[it] } }
    }

    /**
     * The RGI sequence for [base] with the given tones, where 0 = neutral.
     * Equal (or single-slot) tones resolve to the uniform variant; mixed
     * pairs to the per-person sequence. Null when that combination has no
     * RGI form (mixing one toned and one neutral person, for instance).
     */
    fun tonedPair(base: String, first: Int, second: Int = first): String? {
        if (first == 0 && second == 0) return base
        val b = slot(base)
        if (b < 0) return null
        // No row holds a neutral or unknown tone, so neither can match.
        if (first !in 1..5 || second !in 1..5) return null
        val want = if (first == second) first else first * TONE_RADIX + second
        return row(b, want)?.let { sequences[it] }
    }

    /**
     * The face to show for [base] in suggestions and search, given a global
     * default [tone] (0 = neutral) and, optionally, the tone last picked for
     * this base. When [overrideWithPreferred] is on and [preferred] is set it
     * wins; otherwise the default tone is applied. Falls back to [base] when a
     * tone has no RGI form (an emoji that takes no tones).
     */
    fun tonedDisplay(
        base: String,
        tone: Int,
        preferred: String? = null,
        overrideWithPreferred: Boolean = false,
    ): String {
        if (overrideWithPreferred && preferred != null) return preferred
        if (tone == 0) return base
        return tonedPair(base, tone) ?: base
    }

    /**
     * All variants a long-press popup should offer for [base]: the neutral
     * base plus the uniform tone row. (Dual-tone combinations are picked
     * through the two-slot selector, not listed exhaustively.)
     */
    fun popupVariants(base: String): List<String> {
        val uniform = uniformVariants(base)
        return if (uniform.isEmpty()) listOf(base) else listOf(base) + uniform
    }

    private fun slot(base: String): Int = bases.binarySearch(base).let { if (it < 0) -1 else it }

    /** The first of base [b]'s rows whose tones are [code], or null. */
    private fun row(b: Int, code: Int): Int? {
        for (r in rowStart[b] until rowStart[b + 1]) if (tones[r] == code) return r
        return null
    }

    companion object {

        /** Tones are 1..5, so each fits an octal digit and no code has a zero digit. */
        private const val TONE_RADIX = 8

        /** Codes of exactly two tones: 0o11..0o55. */
        private val DUAL_CODES = TONE_RADIX until TONE_RADIX * TONE_RADIX

        /** Rows with more tones than this are skipped; RGI has none past two. */
        private const val MAX_TONES = 4

        fun empty() = EmojiVariantIndex(emptyArray(), IntArray(1), IntArray(0), emptyArray())

        /**
         * Parses `base<TAB>tones<TAB>sequence` rows; tones is "3" or "1,4".
         *
         * Packed into sorted arrays: held by the keyboard all along, the
         * 2,040 rows over 332 bases were 375 KB as a map of lists of objects
         * that each held a list of boxed tones, against 150 KB here (measured
         * on a desktop JVM, 2026-11-26).
         */
        fun load(stream: InputStream): EmojiVariantIndex {
            val map = HashMap<String, MutableList<Pair<Int, String>>>()
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val parts = line.split('\t')
                    if (parts.size < 3) continue
                    val tones = parts[1].split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (tones.isEmpty() || tones.size > MAX_TONES || tones.any { it !in 1..5 }) continue
                    val code = tones.fold(0) { acc, t -> acc * TONE_RADIX + t }
                    map.getOrPut(parts[0]) { mutableListOf() }.add(code to parts[2])
                }
            }
            val bases = map.keys.toTypedArray().also { it.sort() }
            val rowStart = IntArray(bases.size + 1)
            val rows = bases.sumOf { map.getValue(it).size }
            val tones = IntArray(rows)
            val sequences = arrayOfNulls<String>(rows)
            var at = 0
            for ((b, base) in bases.withIndex()) {
                rowStart[b] = at
                for ((code, sequence) in map.getValue(base)) {
                    tones[at] = code
                    sequences[at++] = sequence
                }
            }
            rowStart[bases.size] = at
            return EmojiVariantIndex(bases, rowStart, tones, Array(rows) { sequences[it]!! })
        }
    }
}
