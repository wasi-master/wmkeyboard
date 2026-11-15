package com.wasimaster.wmkeyboard.core.input.imported

/**
 * A Rime code table's `encoder:`: how a phrase that the
 * dictionary lists without a code, or that comes from the vocabulary, gets
 * one from the codes of its characters. Wubi, Cangjie and Zhengma tables all
 * rely on it, since a phrase's code is a fixed pick of letters from its
 * characters' codes: Wubi 86 types a two-character word with the first two
 * letters of each (`AaAbBaBb`).
 *
 * Read from Rime's documentation of the format and the shipped schemas:
 *
 * - `rules` is a list; the first whose length test fits the phrase is used.
 *   `length_equal: N` or `length_in_range: [min, max]`, in characters.
 * - `formula` is pairs of a capital (which character) and a small letter
 *   (which letter of its code). `A` is the first character and `Z` the last;
 *   `A`..`U` count from the front, `V`..`Z` from the back (`Y` is the second
 *   to last). The same for the code letters, `a`..`u` and `v`..`z`.
 * - A pair that points past the phrase or past a code is skipped, as is a
 *   from-the-back pair that would land on a letter already taken.
 * - `exclude_patterns`: regular expressions over a character's code; a code
 *   that matches one is never used to build a phrase's (Wubi's `z` codes).
 */
class RimeTableEncoder private constructor(private val rules: List<Rule>, private val exclude: List<Regex>) {

    private class Rule(val min: Int, val max: Int, val coords: List<Pair<Int, Int>>)

    /**
     * The codes [phrase] can be typed with, given each character's codes from
     * [codesOf] (the dictionary's single-character rows). Empty when a
     * character has no usable code or no rule fits. Several codes come back
     * only when a character has several, at most [MAX_CODES].
     */
    fun encode(phrase: String, codesOf: (String) -> List<String>): List<String> {
        val chars = ArrayList<String>()
        var i = 0
        while (i < phrase.length) {
            val end = i + Character.charCount(phrase.codePointAt(i))
            chars += phrase.substring(i, end)
            i = end
        }
        if (chars.size < 2) return emptyList()
        val options = chars.map { c -> codesOf(c).filter { code -> code.isNotEmpty() && exclude.none { it.matches(code) } } }
        if (options.any { it.isEmpty() }) return emptyList()
        val out = LinkedHashSet<String>()
        val at = IntArray(options.size)
        var tried = 0
        while (tried < MAX_COMBINATIONS && out.size < MAX_CODES) {
            encodeOne(options.indices.map { options[it][at[it]] })?.let(out::add)
            tried++
            var k = options.size - 1
            while (k >= 0) {
                at[k]++
                if (at[k] < options[k].size) break
                at[k] = 0
                k--
            }
            if (k < 0) break
        }
        return out.toList()
    }

    /** One code for one choice of the characters' codes, by the first rule that fits; null when none does. */
    @Suppress("CyclomaticComplexMethod")
    private fun encodeOne(codes: List<String>): String? {
        val n = codes.size
        for (rule in rules) {
            if (n < rule.min || n > rule.max) continue
            val out = StringBuilder()
            var prevChar = 0
            var prevCode = -1
            var prev: Pair<Int, Int>? = null
            for (coord in rule.coords) {
                var ci = coord.first
                if (ci < 0) ci += n
                if (ci !in 0 until n) continue
                if (coord.first < 0 && ci < prevChar) continue
                val code = codes[ci]
                var k = coord.second
                if (k < 0) k += code.length
                if (k !in code.indices) continue
                val fromBack = coord.first < 0 || coord.second < 0
                if (fromBack && ci == prevChar && k <= prevCode && coord != prev) continue
                out.append(code[k])
                prevChar = ci
                prevCode = k
                prev = coord
            }
            if (out.isNotEmpty()) return out.toString()
        }
        return null
    }

    companion object {
        /** At most this many codes for one phrase, however many codes its characters have. */
        const val MAX_CODES = 4

        private const val MAX_COMBINATIONS = 64

        /** Letters past this one in the alphabet count from the end: `V` is -5, `Z` is -1. */
        private const val FROM_FRONT = 21

        private const val ALPHABET = 26

        /**
         * The encoder of a dictionary header's `encoder:` node, or null when
         * it has none or no rule in it reads.
         */
        fun of(node: Any?): RimeTableEncoder? {
            val map = node as? Map<*, *> ?: return null
            val rules = (map["rules"] as? List<*>).orEmpty().mapNotNull { rule(it as? Map<*, *> ?: return@mapNotNull null) }
            if (rules.isEmpty()) return null
            val exclude = (map["exclude_patterns"] as? List<*>).orEmpty().mapNotNull { p ->
                p?.toString()?.let { runCatching { Regex(it) }.getOrNull() }
            }
            return RimeTableEncoder(rules, exclude)
        }

        private fun rule(map: Map<*, *>): Rule? {
            val formula = map["formula"]?.toString() ?: return null
            val coords = formula(formula) ?: return null
            val equal = (map["length_equal"] as? Number)?.toInt()
            val range = (map["length_in_range"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
            return when {
                equal != null -> Rule(equal, equal, coords)
                range != null && range.size == 2 -> Rule(range[0], range[1], coords)
                else -> null
            }
        }

        /** `AaAbBaBb` as (character, letter) pairs, from-the-back ones negative; null when it is malformed. */
        internal fun formula(text: String): List<Pair<Int, Int>>? {
            if (text.isEmpty() || text.length % 2 != 0) return null
            val out = ArrayList<Pair<Int, Int>>(text.length / 2)
            for (i in text.indices step 2) {
                val c = text[i]
                val k = text[i + 1]
                if (c !in 'A'..'Z' || k !in 'a'..'z') return null
                out += index(c - 'A') to index(k - 'a')
            }
            return out
        }

        private fun index(i: Int): Int = if (i >= FROM_FRONT) i - ALPHABET else i
    }
}
