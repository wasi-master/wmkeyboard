package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Korean on the 나랏글 (Naratgeul) pad, the 12-key layout LG phones shipped and
 * Samsung still offers (issue #599).
 *
 * The pad has keys for the basic letters only: ㄱ ㄴ ㄹ ㅁ ㅅ ㅇ, the vowels ㅏㅓ
 * and ㅗㅜ (picked by tapping again, `Key.multitap`), ㅣ and ㅡ. Everything
 * else is built from them. A consonant or y-vowel gets its extra stroke from the
 * 획추가 key and its tense twin from the 쌍자음 key, both of which rewrite the
 * last jamo in the buffer ([HangulStrokes]). And ㅐ ㅒ ㅔ ㅖ have no key: they
 * are their vowel followed by ㅣ, which is what this composer adds to the
 * two-set automaton — on a two-set grid ㅏ then ㅣ is two syllables, because
 * that grid has a key for ㅐ.
 *
 * The buffer keeps the keys as typed and [composeBuffer] folds the pairs on
 * every keystroke, so a backspace over ㅐ takes the ㅣ and leaves ㅏ, and ㅗ ㅏ ㅣ
 * reads as ㅗ ㅐ, which [HangulComposer] already joins into ㅙ.
 *
 * A pad with explicit ㅇ needs no guessing here: two vowels in a row are always
 * one compound vowel, never two syllables, because the second syllable would
 * start with the ㅇ key.
 */
object NaratgeulComposer : Composer {

    override val isTransliterating: Boolean get() = true

    override val offersHanja: Boolean get() = true

    override fun composeBuffer(buffer: String): String =
        HangulComposer.composeBuffer(foldVowelPairs(buffer))

    /** A vowel and a following ㅣ, read as the one vowel they make here. */
    private val PAIRS: Map<Char, Char> = mapOf('ㅏ' to 'ㅐ', 'ㅑ' to 'ㅒ', 'ㅓ' to 'ㅔ', 'ㅕ' to 'ㅖ')

    /**
     * [buffer] with every ㅏ ㅑ ㅓ ㅕ followed by ㅣ read as ㅐ ㅒ ㅔ ㅖ, and
     * everything else left alone. Public so the table can be tested without
     * going through syllable assembly.
     */
    fun foldVowelPairs(buffer: String): String {
        if ('ㅣ' !in buffer) return buffer
        val out = StringBuilder(buffer.length)
        var i = 0
        while (i < buffer.length) {
            val pair = PAIRS[buffer[i]]?.takeIf { i + 1 < buffer.length && buffer[i + 1] == 'ㅣ' }
            if (pair != null) {
                out.append(pair)
                i += 2
            } else {
                out.append(buffer[i++])
            }
        }
        return out.toString()
    }
}
