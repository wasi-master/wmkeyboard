package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.TurkicCase
import com.wasimaster.wmkeyboard.core.prediction.WordKey
import com.wasimaster.wmkeyboard.core.prediction.WordSource
import java.util.BitSet

/**
 * The capitals a word list spells its words with, kept apart from the list
 * (#481).
 *
 * Every lookup here is by lower-case key, and a curated list is not lower
 * case: AOSP's German list writes `Zeit`, `Haus`, `Berlin`, 86,000 of its
 * 205,000 words. Packed as written, those words sat under keys that nothing
 * typed in lower case ever reached, so "haus" was not a word at all and
 * neither a completion nor a swipe could find it.
 *
 * [fold] puts every word under its lower-case key and returns what was lost
 * as a second trie: the same keys, with the *frequency* slot holding the
 * shape instead of a count. It is written beside the list as `caps.wmdict`
 * and mapped the same way, so a language's capitals cost no heap.
 *
 * A word the list also spells in lower case keeps no shape. German has both
 * `essen` and `Essen`, English both `may` and `May`, and what was typed
 * cannot say which was meant.
 */
object DictionaryCapitals {

    /**
     * Words a list spells only in capitals that are ordinary lower-case words
     * of the language, by language: AOSP's Polish list has `PAN` and no
     * `pan`, the forty-fifth commonest Polish word, so every "pan" typed came
     * out as an acronym. Found by matching each AOSP list's capitals-only
     * words against the first 3,000 of the language's counted list and
     * keeping the ones that are words or interjections there, not
     * abbreviations (`OK`, `FBI`, `USA` and `TV` stay capitals).
     */
    private val LOWER_CASE_WORDS: Map<String, Set<String>> = mapOf(
        "pl" to setOf("pan", "oh", "ej", "um"),
        "hr" to setOf("vi", "sad"),
        "sr" to setOf("сад"),
        "cs" to setOf("oh", "ah", "hm", "au"),
        "de" to setOf("tu", "hm"),
        "es" to setOf("eh", "os"),
        "it" to setOf("esco"),
        "fi" to setOf("isi"),
        "nl" to setOf("ah"),
        "sl" to setOf("oh", "aha"),
        "pt" to setOf("ias", "uh"),
        "tr" to setOf("ee"),
        "en" to setOf("erm"),
    )

    /** Whether [key] is a lower-case word of [languageId] whatever its list's capitals say. */
    fun writtenLowerCase(languageId: String, key: String): Boolean =
        LOWER_CASE_WORDS[languageId.substringBefore('_')]?.contains(key) == true

    /**
     * A shape is a bit per character, set where that character is a capital.
     * Eleven bits is what a dictionary frequency stores exactly; a capital
     * further in than that is only kept for a word that is capitals throughout.
     */
    private const val MASK_BITS = 11

    /** Every character a capital, whatever the length: `USA`, `UNESCO`. */
    private const val ALL_CAPS = (1 shl MASK_BITS) - 1

    /** [capitals] is null when the list spells nothing with a capital. */
    class Folded(val capitals: PackedTrie?)

    /**
     * Rewrites the first [count] of [words] to their lower-case keys, in
     * place, and returns the capitals that took away.
     */
    fun fold(words: Array<String?>, count: Int, turkic: Boolean = false): Folded {
        val shapes = HashMap<String, Int>()
        val folded = BitSet(count)
        for (i in 0 until count) {
            val word = words[i] ?: continue
            if (!hasCapital(word)) continue
            // [turkic]: `İ` folds to `i` and `I` to `ı` ([TurkicCase]), so
            // `İstanbul` is filed where typing `istanbul` finds it.
            val key = WordKey.of(if (turkic) TurkicCase.lower(word) else word)
            // Sorted by frequency, so the first spelling met is the common one.
            if (key !in shapes) shapes[key] = shapeOf(word, key, turkic)
            words[i] = key
            folded.set(i)
        }
        if (shapes.isEmpty()) return Folded(null)
        // The twins: a key the list also wrote in lower case, itself.
        var i = folded.nextClearBit(0)
        while (i < count) {
            words[i]?.let(shapes::remove)
            i = folded.nextClearBit(i + 1)
        }
        return Folded(pack(shapes))
    }

    /**
     * The capitals of a plain list of spellings, one a line, `#` for a
     * comment: what the app ships for a bundled list that has none (#517).
     */
    fun ofSpellings(lines: Sequence<String>): PackedTrie? {
        val words = lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList<String?>().toTypedArray()
        return fold(words, words.size).capitals
    }

    private fun pack(shapes: Map<String, Int>): PackedTrie? {
        val kept = shapes.filterValues { it > 0 }
        if (kept.isEmpty()) return null
        return PackedTrie.of(kept.map { it.key to it.value })
    }

    /** [key] as [capitals] spells it, or null when it has no capital there. */
    fun spelling(capitals: WordSource, key: String, turkic: Boolean = false): String? {
        val shape = capitals.frequencyOf(key)
        if (shape <= 0) return null
        if (shape == ALL_CAPS) return if (turkic) TurkicCase.upper(key) else key.uppercase()
        val out = StringBuilder(key)
        for (i in 0 until minOf(key.length, MASK_BITS)) {
            if (shape and (1 shl i) != 0) out.setCharAt(i, upperOf(key[i], turkic))
        }
        return out.toString()
    }

    private fun upperOf(c: Char, turkic: Boolean): Char = if (turkic) TurkicCase.upperChar(c) else c.uppercaseChar()

    /**
     * The shape that turns [key] back into [word], or 0 when no shape does:
     * a capital past the eleventh character, or a letter whose two cases are
     * not the same length.
     */
    private fun shapeOf(word: String, key: String, turkic: Boolean = false): Int {
        val surface = WordKey.surface(word)
        if (surface.length != key.length) return 0
        if (key.length > 1 && surface == (if (turkic) TurkicCase.upper(key) else key.uppercase())) return ALL_CAPS
        var shape = 0
        for (i in surface.indices) {
            if (surface[i] == key[i]) continue
            if (i >= MASK_BITS || upperOf(key[i], turkic) != surface[i]) return 0
            shape = shape or (1 shl i)
        }
        return if (shape == ALL_CAPS) 0 else shape
    }

    /**
     * Every spelling of the first [count] of [words] that a fold would lose,
     * kept as written, for a language whose capitals are letters of their own
     * (Klingon: `qaH` "sir" and `QaH` "help" are two words under one key).
     *
     * [fold] keeps one shape per key, which is right for German, where `Essen`
     * and `essen` are one word in two positions, and wrong here, where they are
     * two words. So such a list also gets this: a trie keyed by the spellings
     * themselves, holding each word with a capital and, beside it, the
     * lower-case twin of any such key, with its list frequency. Call it before
     * [fold], which rewrites [words] in place. Null when nothing has a capital.
     */
    fun spellingsOf(words: Array<String?>, frequencies: IntArray, count: Int): PackedTrie? {
        val kept = HashMap<String, Int>()
        val cased = HashSet<String>()
        for (i in 0 until count) {
            val word = words[i] ?: continue
            if (!hasCapital(word)) continue
            kept.merge(WordKey.surface(word), maxOf(frequencies[i], 1), ::maxOf)
            cased.add(WordKey.of(word))
        }
        if (kept.isEmpty()) return null
        for (i in 0 until count) {
            val word = words[i] ?: continue
            if (hasCapital(word) || WordKey.of(word) !in cased) continue
            kept.merge(WordKey.surface(word), maxOf(frequencies[i], 1), ::maxOf)
        }
        return PackedTrie.of(kept.map { it.key to it.value })
    }

    /**
     * Every spelling [spellings] (from [spellingsOf]) holds for [key],
     * commonest first. Empty when it holds none, which means the word is
     * written in lower case, as [key] already is.
     *
     * A walk down the trie trying each character of [key] as it is and as its
     * capital, so a key costs at most two child lookups per character and a
     * branch only where the list really has both.
     */
    fun spellings(spellings: WordSource, key: String): List<String> {
        if (key.isEmpty()) return emptyList()
        val found = ArrayList<Pair<String, Int>>(2)
        val out = StringBuilder(key.length)
        for (walker in spellings.walkers()) {
            fun descend(node: Int, at: Int) {
                if (at == key.length) {
                    if (walker.isWord(node)) found.add(out.toString() to walker.frequency(node))
                    return
                }
                val lower = key[at]
                val upper = lower.uppercaseChar()
                for (c in if (upper == lower) charArrayOf(lower) else charArrayOf(lower, upper)) {
                    val next = walker.child(node, c)
                    if (next < 0) continue
                    out.append(c)
                    descend(next, at + 1)
                    out.setLength(out.length - 1)
                }
            }
            descend(walker.root, 0)
        }
        if (found.size < 2) return found.map { it.first }
        return found.sortedByDescending { it.second }.map { it.first }.distinct()
    }

    private fun hasCapital(word: String): Boolean {
        for (c in word) if (c.isUpperCase() || c.isTitleCase()) return true
        return false
    }
}
