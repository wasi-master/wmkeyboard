package com.wasimaster.wmkeyboard.core.prediction

import java.text.Normalizer

/**
 * The bare letter an accented one is written on: `ż` → `z`, `ł` → `l`,
 * `é` → `e`, `ά` → `α` (#200).
 *
 * The fuzzy walk uses it to read a plain letter as its accented form. Leaving
 * the accent off is not a slip of the finger: the accented letter sits on the
 * same key, one long-press away, and a Polish, Czech or Portuguese typist
 * skips that press constantly. So `juz` is `już` with nothing mistyped, and it
 * has to be priced that way rather than as a far substitution. A far
 * substitution also counts as an edit, and a three-letter word gets only one.
 *
 * Latin and Greek only. On those keyboards the accented letter is a variant of
 * the bare one. Elsewhere a decomposition names a different letter with its
 * own key: Cyrillic `й` is not `и` typed carelessly, and a Bengali nukta letter
 * is not its base (see `decomposedKeyChars` in the IME for the Indic side).
 */
object Accents {

    /** First code point of each folded range; [table] is indexed from here. */
    private const val LATIN_START = 0x00C0
    private const val LATIN_END = 0x024F
    private const val GREEK_START = 0x0370
    private const val GREEK_END = 0x03FF
    private const val LATIN_EXTENDED_START = 0x1E00
    private const val LATIN_EXTENDED_END = 0x1EFF

    /**
     * Letters with a stroke or slash rather than a combining mark, and
     * Turkish's dotless `ı`. Unicode gives them no decomposition, but they are
     * accents in the sense that matters here: `ł` sits on the `l` key, and a
     * Turkish typist who skips the long press writes `haklisin` for
     * `haklısın`.
     */
    private val STROKED = mapOf(
        'ł' to 'l', 'Ł' to 'L',
        'ı' to 'i',
        'ø' to 'o', 'Ø' to 'O',
        'đ' to 'd', 'Đ' to 'D',
        'ħ' to 'h', 'Ħ' to 'H',
        'ŀ' to 'l', 'Ŀ' to 'L',
    )

    private val latin = build(LATIN_START, LATIN_END)
    private val greek = build(GREEK_START, GREEK_END)
    private val latinExtended = build(LATIN_EXTENDED_START, LATIN_EXTENDED_END)

    /** [c] without its accent, or [c] itself when it carries none. */
    fun bare(c: Char): Char {
        val code = c.code
        return when {
            code < LATIN_START -> c
            code <= LATIN_END -> latin[code - LATIN_START]
            code in GREEK_START..GREEK_END -> greek[code - GREEK_START]
            code in LATIN_EXTENDED_START..LATIN_EXTENDED_END ->
                latinExtended[code - LATIN_EXTENDED_START]
            else -> c
        }
    }

    /** Whether [word] has a letter with an accent, a stroke or a tone mark on it. */
    fun hasMarks(word: String): Boolean = word.any { bare(it) != it }

    /** Whether [label] is [typed] with an accent the typist left off. */
    fun isAccentOf(label: Char, typed: Char): Boolean =
        label != typed && label.code >= LATIN_START && bare(label) == typed

    private fun build(start: Int, end: Int): CharArray = CharArray(end - start + 1) { i ->
        val c = (start + i).toChar()
        STROKED[c] ?: run {
            val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            // A base letter followed only by marks. Anything else, such as a
            // ligature or a compatibility form, is its own letter.
            val marksOnly = decomposed.length > 1 && decomposed.drop(1).all {
                Character.getType(it) == Character.NON_SPACING_MARK.toInt()
            }
            if (marksOnly && decomposed[0].isLetter()) decomposed[0] else c
        }
    }
}
