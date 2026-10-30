package com.wasimaster.wmkeyboard.core.transliteration

import java.text.Normalizer

/**
 * Greeklish in, Greek out ("kalimera" → καλημερα). The rules alone can't choose
 * between the five spellings of the sound i or the two of o, and don't know where the
 * accent goes, so [GreekPhoneticIndex] over the word list is what finds καλημέρα. These
 * rules are for a word the list doesn't have.
 */
object GreekPhonetic {

    private val DIGRAPHS = mapOf(
        "th" to "θ", "ch" to "χ", "ps" to "ψ", "ks" to "ξ", "ou" to "ου", "ai" to "αι", "ei" to "ει", "oi" to "οι",
        "au" to "αυ", "eu" to "ευ", "ay" to "αυ", "ey" to "ευ", "mp" to "μπ", "nt" to "ντ", "gk" to "γκ", "gg" to "γγ",
    )

    private val LETTERS = mapOf(
        'a' to "α", 'b' to "μπ", 'c' to "σ", 'd' to "δ", 'e' to "ε", 'f' to "φ", 'g' to "γ", 'h' to "η", 'i' to "ι",
        'j' to "ξ", 'k' to "κ", 'l' to "λ", 'm' to "μ", 'n' to "ν", 'o' to "ο", 'p' to "π", 'q' to ";", 'r' to "ρ",
        's' to "σ", 't' to "τ", 'u' to "υ", 'v' to "β", 'w' to "ω", 'x' to "χ", 'y' to "υ", 'z' to "ζ",
        // The digits that look like the letter, as chat spells θ and ξ.
        '8' to "θ", '3' to "ξ",
    )

    /** Transliterates Greeklish, one word or free text. A number typed alone stays a number. */
    fun transliterate(input: String): String {
        val out = StringBuilder(input.length + 4)
        var i = 0
        while (i < input.length) {
            val run = wordEnd(input, i)
            if (run == i) {
                out.append(input[i])
                i++
                continue
            }
            val word = input.substring(i, run)
            out.append(if (word.all { it.isDigit() }) word else transliterateWord(word))
            i = run
        }
        return out.toString()
    }

    private fun wordEnd(text: String, from: Int): Int {
        var at = from
        while (at < text.length && (text[at].isLetter() || text[at].isDigit())) at++
        return at
    }

    private fun transliterateWord(word: String): String {
        val lower = word.lowercase()
        val out = StringBuilder(word.length + 2)
        var i = 0
        while (i < lower.length) {
            // "nth" is ν then θ, not ντ then η.
            val pair = if (i + 2 <= lower.length && !lower.startsWith("nth", i)) DIGRAPHS[lower.substring(i, i + 2)] else null
            val piece = pair ?: LETTERS[lower[i]] ?: lower[i].toString()
            val capital = word[i].isUpperCase()
            out.append(if (capital) piece.replaceFirstChar { it.uppercaseChar() } else piece)
            i += if (pair != null) 2 else 1
        }
        // A word ends in the other sigma.
        if (out.isNotEmpty() && out.last() == 'σ') out.setCharAt(out.lastIndex, 'ς')
        return out.toString()
    }

    /** One Greek word as it would be spelt in Latin letters: what a swipe or a search reads it as. */
    fun romanize(word: String): String = GreekFold.greeklish(word)

    /** A letter or sign of a Greek word. */
    fun isGreek(c: Char): Boolean = c in 'Ͱ'..'Ͽ' || c in 'ἀ'..'῿'

    /** Accents off and σ for ς, the form the rules and the fold compare. */
    internal fun plain(word: String): String =
        Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .replace('ς', 'σ')
}

/**
 * The key a Greek word and a Greeklish spelling of it share: how the word sounds, with
 * one letter per sound, so ι η υ ει οι are all `i` and καλημέρα, kalhmera and kalimera
 * are all the same key.
 */
internal object GreekFold {

    private val GREEK_PAIRS = mapOf(
        "ου" to "u", "αι" to "e", "ει" to "i", "οι" to "i", "υι" to "i", "αυ" to "av", "ευ" to "ev", "ηυ" to "iv",
        "μπ" to "b", "ντ" to "d", "γκ" to "g", "γγ" to "g",
    )

    private val GREEK_LETTERS = mapOf(
        'α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z", 'η' to "i", 'θ' to "8", 'ι' to "i",
        'κ' to "k", 'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "3", 'ο' to "o", 'π' to "p", 'ρ' to "r", 'σ' to "s",
        'τ' to "t", 'υ' to "i", 'φ' to "f", 'χ' to "x", 'ψ' to "ps", 'ω' to "o",
    )

    private val LATIN_PAIRS = mapOf(
        "th" to "8", "ch" to "x", "ks" to "3", "ou" to "u", "ai" to "e", "ei" to "i", "oi" to "i", "ui" to "i",
        "yi" to "i", "au" to "av", "eu" to "ev", "ay" to "av", "ey" to "ev", "mp" to "b", "nt" to "d", "gk" to "g", "gg" to "g", "ng" to "g",
    )

    private val LATIN_LETTERS = mapOf(
        'h' to "i", 'y' to "i", 'u' to "i", 'w' to "o", 'c' to "s", 'j' to "3", '9' to "8", 'q' to "k",
    )

    /** The key of a Greek word. */
    fun ofGreek(word: String): String = settle(read(GreekPhonetic.plain(word), GREEK_PAIRS) { GREEK_LETTERS[it] })

    /** The keys of a Greeklish spelling, the likeliest first: `h` is χ as well as η, `x` is ξ as well as χ. */
    fun ofLatin(input: String): List<String> {
        val lower = input.lowercase()
        val first = settle(read(lower, LATIN_PAIRS) { LATIN_LETTERS[it] })
        val keys = LinkedHashSet<String>()
        keys += first
        if ('h' in lower) keys += settle(read(lower, LATIN_PAIRS) { c -> if (c == 'h') "x" else LATIN_LETTERS[c] })
        if ('x' in lower) keys += settle(read(lower, LATIN_PAIRS) { c -> if (c == 'x') "3" else LATIN_LETTERS[c] })
        return keys.toList()
    }

    /** A Greek word as plain Greeklish: readable, not the key. */
    fun greeklish(word: String): String = ofGreek(word).replace('8', 'θ').replace("θ", "th").replace("3", "ks")

    private inline fun read(text: String, pairs: Map<String, String>, letter: (Char) -> String?): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val pair = if (i + 2 <= text.length && !text.startsWith("nth", i)) pairs[text.substring(i, i + 2)] else null
            if (pair != null) {
                out.append(pair)
                i += 2
            } else {
                out.append(letter(text[i]) ?: text[i].toString())
                i++
            }
        }
        return out.toString()
    }

    /** What is the same to the ear either way: a doubled letter, and ev against ef before anything. */
    private fun settle(key: String): String {
        val out = StringBuilder(key.length)
        for (c in key) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString().replace("ev", "ef").replace("av", "af")
    }
}

/** Dictionary words a Greeklish spelling can stand for, commonest first. */
class GreekPhoneticIndex(private val table: PhoneticTable) : PhoneticIndex {

    /** An index built on the device over [entries], `word to frequency`. */
    constructor(entries: List<Pair<String, Int>>) : this(table(entries))

    override val isEmpty: Boolean get() = table.isEmpty

    override fun lookup(input: String): List<String> {
        val rows = GreekFold.ofLatin(input).filter { key -> key.all { it.code < 128 } }.map { table.rows(it) }
        if (rows.isEmpty()) return emptyList()
        if (rows.size == 1) return rows[0].map { it.word }
        val out = LinkedHashSet<String>()
        val longest = rows.maxOf { it.size }
        for (rank in 0 until longest) for (list in rows) list.getOrNull(rank)?.let { out.add(it.word) }
        return out.toList()
    }

    override fun frequencyOf(word: String): Int = table.frequencyOf(word)

    override val maxFrequency: Int get() = table.maxFrequency

    companion object {
        private const val FOLD = 4
        private const val REVISION = 1

        /** Files [entries], at most the [boundedEntries] budget of them, by sound. */
        fun table(entries: List<Pair<String, Int>>): PhoneticTable {
            val builder = PhoneticTableBuilder(FOLD, REVISION)
            for ((word, frequency) in boundedEntries(entries)) {
                val key = GreekFold.ofGreek(word)
                // The table's keys are ASCII. A word that leaves anything else in its key isn't plain Greek.
                if (key.isNotEmpty() && key.all { it.code < 128 }) builder.add(key, word, frequency, "", 0)
            }
            return builder.build()
        }
    }
}
