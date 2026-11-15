package com.wasimaster.wmkeyboard.core.prediction

/**
 * Case tests that count letters by code point.
 *
 * `Char.isLetter()` and `Char.isUpperCase()` are false for either half of a
 * surrogate pair, so a word in Osage, Adlam or Warang Citi — cased scripts, all
 * of them outside the BMP — reads as "no letters at all" to a per-`Char` test.
 * The all-caps test then passes vacuously and every suggestion for such a word
 * came back in capitals.
 */

/** Whether [word] holds at least one letter. */
internal fun hasLetter(word: String): Boolean {
    var at = 0
    while (at < word.length) {
        val cp = word.codePointAt(at)
        if (Character.isLetter(cp)) return true
        at += Character.charCount(cp)
    }
    return false
}

/** Whether every letter of [word] is upper case; true when it has no letters. */
internal fun lettersAllUpper(word: String): Boolean {
    var at = 0
    while (at < word.length) {
        val cp = word.codePointAt(at)
        if (Character.isLetter(cp) && !Character.isUpperCase(cp)) return false
        at += Character.charCount(cp)
    }
    return true
}

/** Whether [word] is more than one letter long and written entirely in capitals. */
internal fun isAllCapsWord(word: String): Boolean =
    word.codePointCount(0, word.length) > 1 && hasLetter(word) && lettersAllUpper(word)

/** Whether [word] starts with an upper-case letter. */
internal fun startsUpperCase(word: String): Boolean =
    word.isNotEmpty() && Character.isUpperCase(word.codePointAt(0))

/** [word] with its first character — the whole first code point — upper-cased. */
internal fun capitalizeFirst(word: String): String {
    if (word.isEmpty()) return word
    val first = word.codePointAt(0)
    val upper = Character.toUpperCase(first)
    if (upper == first) return word
    return String(Character.toChars(upper)) + word.substring(Character.charCount(first))
}

/**
 * The casing of the languages that write a dotted and a dotless i as two
 * letters: Turkish, Azerbaijani, Crimean Tatar and Gagauz. There `i` has the
 * capital `İ` and `ı` the capital `I`, where every other Latin alphabet pairs
 * `i` with `I`.
 *
 * Folded the common way, `İyi` became `i` and a combining dot and matched no
 * word in any list, so the commonest word a Turkish sentence opens with was
 * unknown where it opened one; a suggestion for a word in `i` came back with
 * the dotless capital (`Iyi`, `Ile`); and a list's `İstanbul` was filed under
 * a key that typing `istanbul` never reached.
 */
object TurkicCase {
    private val LANGUAGES = setOf("tr", "az", "crh", "gag")
    private val LOCALE = java.util.Locale.forLanguageTag("tr")

    /** Whether [languageId] (`tr`, `tr_TR`, `az-Latn`) cases its letters this way. */
    fun applies(languageId: String): Boolean =
        languageId.substringBefore('-').substringBefore('_').lowercase() in LANGUAGES

    fun lower(text: String): String = text.lowercase(LOCALE)

    fun upper(text: String): String = text.uppercase(LOCALE)

    /** [c]'s capital: `İ` for `i`, `I` for `ı`, the usual one otherwise. */
    fun upperChar(c: Char): Char = when (c) {
        'i' -> '\u0130'
        '\u0131' -> 'I'
        else -> c.uppercaseChar()
    }

    /** [word] with its first letter as a capital. */
    fun capitalized(word: String): String = when (word.firstOrNull()) {
        'i' -> "\u0130" + word.substring(1)
        '\u0131' -> "I" + word.substring(1)
        // The file's own, for every other letter.
        else -> capitalizeFirst(word)
    }
}
