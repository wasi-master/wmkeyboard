package com.wasimaster.wmkeyboard.core.transliteration

import java.io.InputStream

/**
 * Varnam's way of finding the word a romanized spelling means, written for
 * this app over Varnam's own symbol tables (github.com/varnamproject/schemes,
 * MPL-2.0; the engine itself, govarnam, is not used).
 *
 * The spelling is cut into the longest patterns the scheme knows (`ththa`,
 * `nn`, `zh`), and every pattern keeps all the letters it can stand for, the
 * likely ones first: `th` is ത്, ഥ്, ത്ത് or ത്ഥ്. The readings are then grown
 * a pattern at a time, and one survives only while some word of the language's
 * list starts with it, so the many combinations never multiply out. What is
 * left at the end and is a whole word is the answer, commonest first.
 *
 * Measured on 2,000 held-out words per language (Aksharantar's test split),
 * over the same word lists the keyboard downloads: on Malayalam this finds the
 * intended word first 27.6% of the time where the phonetic index and rules
 * together manage 23.1%, and the strip's first four hold it 35.8% of the time
 * rather than 25.9%. On Tamil, Telugu and Kannada it adds a few points after
 * the index's own words; elsewhere it adds nothing, so only those four ship.
 */
class VarnamScheme private constructor(
    private val byPattern: Map<String, Array<Symbol>>,
    private val longest: Int,
) {

    /**
     * One reading of one pattern. [value] stands at the start of a word,
     * [sign] after a consonant (a vowel's sign; empty when it has none).
     */
    class Symbol(
        val type: Int,
        val value: String,
        val sign: String,
        val exact: Boolean,
        val stands: Int,
        val weight: Int,
    ) {
        fun at(position: Int): String = if (type == VOWEL && position > 0 && sign.isNotEmpty()) sign else value
    }

    /**
     * The words of [words] that [roman] can be read as, best ranked first, at
     * most [limit]. Empty when the scheme cannot cut the spelling up or no word
     * of the list fits all of it.
     */
    fun words(roman: String, words: SortedWords, limit: Int): List<String> {
        if (roman.isEmpty() || words.size == 0 || limit <= 0) return emptyList()
        val tokens = tokenize(roman) ?: return emptyList()
        var partials: List<String> = tokens[0].map { it.at(0) }.distinct().filter { hasPrefix(words, it) }
        for (i in 1 until tokens.size) {
            if (partials.isEmpty()) return emptyList()
            val next = LinkedHashSet<String>()
            for (p in partials) {
                for (s in tokens[i]) {
                    val q = p + s.at(i)
                    if (q !in next && hasPrefix(words, q)) next += q
                }
                if (next.size >= MAX_PARTIALS) break
            }
            partials = next.toList()
        }
        val found = ArrayList<Pair<String, Long>>()
        for (p in partials) {
            val at = lowerBound(words, p)
            if (at < words.size && words.length(at) == p.length && startsWith(words, at, p)) found += p to words.rank(at)
        }
        found.sortBy { it.second }
        return found.take(limit).map { it.first }
    }

    /**
     * The spelling cut into patterns, each with every symbol it may be, as
     * Varnam cuts it: longest pattern first, a pattern's letters only where the
     * scheme lets them stand (some only start a word, some only end one), and
     * after the first possible reading, only the ones Varnam saw at least once.
     * Null when some letter belongs to no pattern.
     */
    internal fun tokenize(roman: String): List<Array<Symbol>>? {
        val out = ArrayList<Array<Symbol>>()
        var i = 0
        while (i < roman.length) {
            val stands = when {
                out.isEmpty() -> STARTS
                i == roman.length - 1 -> ENDS
                else -> MIDDLE
            }
            var matched: Array<Symbol>? = null
            var length = minOf(longest, roman.length - i)
            while (length > 0 && matched == null) {
                val all = byPattern[roman.substring(i, i + length)]
                if (all != null) {
                    val fits = all.filter { it.stands == ANYWHERE || it.stands == stands }
                    if (fits.isNotEmpty()) matched = keepLikely(fits)
                }
                if (matched == null) length--
            }
            if (matched == null) return null
            out += matched
            i += length
        }
        return out
    }

    private fun keepLikely(symbols: List<Symbol>): Array<Symbol> {
        val kept = ArrayList<Symbol>(symbols.size)
        for (s in symbols) {
            val weight = if (s.exact) EXACT_WEIGHT else s.weight
            if (weight == 0 && kept.isNotEmpty()) break
            kept += s
        }
        return kept.toTypedArray()
    }

    companion object {
        const val VOWEL = 1
        private const val ANYWHERE = 0
        private const val STARTS = 1
        private const val MIDDLE = 2
        private const val ENDS = 3

        /** What an exact symbol counts for against the possible ones, as in Varnam. */
        private const val EXACT_WEIGHT = 200

        /** How many readings one step keeps; a long word of short patterns stays bounded. */
        private const val MAX_PARTIALS = 3000

        /**
         * A scheme from the app's text form of a Varnam symbol table: `#`
         * comments, then one symbol a line, `pattern, type, value, sign,
         * match (1 exact, 2 possible), where it may stand, weight, priority`,
         * a pattern's symbols in Varnam's order (exact first, then by weight).
         */
        fun read(input: InputStream): VarnamScheme {
            val byPattern = HashMap<String, ArrayList<Symbol>>()
            input.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line.startsWith('#')) continue
                    val f = line.split('\t')
                    if (f.size < 7) continue
                    val symbol = Symbol(
                        type = f[1].toIntOrNull() ?: continue,
                        value = f[2],
                        sign = f[3],
                        exact = f[4] == "1",
                        stands = f[5].toIntOrNull() ?: 0,
                        weight = f[6].toIntOrNull() ?: 0,
                    )
                    byPattern.getOrPut(f[0]) { ArrayList() } += symbol
                }
            }
            val longest = byPattern.keys.maxOfOrNull { it.length } ?: 0
            return VarnamScheme(byPattern.mapValues { it.value.toTypedArray() }, longest)
        }

        /** Whether some word of [words] starts with [prefix]. */
        fun hasPrefix(words: SortedWords, prefix: String): Boolean {
            val at = lowerBound(words, prefix)
            return at < words.size && startsWith(words, at, prefix)
        }

        /** The first word of [words] not before [key], in [String.compareTo] order. */
        private fun lowerBound(words: SortedWords, key: String): Int {
            var lo = 0
            var hi = words.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (compare(words, mid, key) < 0) lo = mid + 1 else hi = mid
            }
            return lo
        }

        private fun compare(words: SortedWords, i: Int, key: String): Int {
            val n = words.length(i)
            val common = minOf(n, key.length)
            for (k in 0 until common) {
                val d = words.charAt(i, k) - key[k]
                if (d != 0) return d
            }
            return n - key.length
        }

        private fun startsWith(words: SortedWords, i: Int, prefix: String): Boolean {
            if (words.length(i) < prefix.length) return false
            for (k in prefix.indices) if (words.charAt(i, k) != prefix[k]) return false
            return true
        }
    }
}

/**
 * The Varnam schemes the keyboard ships, read from the app's assets the first
 * time a language asks. [assets] is set once, at app start.
 */
object VarnamSchemes {

    /** Malayalam, Tamil, Telugu and Kannada: where the walk adds to the strip (see [VarnamScheme]). */
    val languages: Set<String> = setOf("ml", "ta", "te", "kn")

    /** Opens an asset by path; null where there is none. */
    @Volatile
    var assets: ((String) -> InputStream?)? = null

    private val loaded = HashMap<String, VarnamScheme?>()

    fun forLanguage(languageId: String): VarnamScheme? {
        if (languageId !in languages) return null
        synchronized(loaded) {
            if (languageId in loaded) return loaded[languageId]
        }
        val open = assets ?: return null
        val scheme = runCatching { open("varnam/$languageId.tsv")?.use(VarnamScheme::read) }.getOrNull()
        synchronized(loaded) { loaded[languageId] = scheme }
        return scheme
    }
}
