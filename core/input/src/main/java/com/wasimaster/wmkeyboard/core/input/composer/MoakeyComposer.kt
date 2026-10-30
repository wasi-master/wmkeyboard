package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Korean on Samsung's 모아키 (Moakey): each consonant key is dragged towards a
 * vowel, so a consonant and its vowel are one stroke and arrive here as the
 * finished syllable (`바`). A tap types the bare consonant, which becomes the
 * final of the syllable before it.
 *
 * The buffer keeps what each stroke typed, so one backspace takes back one
 * stroke, and [composeBuffer] opens the syllables back into jamo for
 * [HangulComposer], which then treats a tapped consonant as it always does:
 * `바` then `ㄱ` is 박, `바` then `가` is 바가, `박` then `아` stays 박아 because
 * that stroke brought its own ㅇ.
 *
 * The vowels past the six a first drag reaches come from bending the drag,
 * which `MoakeyDrag` reads on the key. On the two-hand board the same vowels can
 * be built on the side keys instead, and those are what [foldVowelKeys] is for.
 */
object MoakeyComposer : Composer {

    override val isTransliterating: Boolean get() = true

    override val offersHanja: Boolean get() = true

    override fun composeBuffer(buffer: String): String =
        HangulComposer.composeBuffer(foldVowelKeys(openSyllables(buffer)))

    private const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"

    private const val SYLLABLE_FIRST = 0xAC00
    private const val SYLLABLES_PER_VOWEL = 28
    private const val DOT = 'ㆍ'
    private const val I = 'ㅣ'

    /** The syllable a drag on [initial] towards [vowel] types, or null if either is not one. */
    fun syllable(initial: Char, vowel: Char): Char? {
        val l = INITIALS.indexOf(initial)
        val v = VOWELS.indexOf(vowel)
        if (l < 0 || v < 0) return null
        return (SYLLABLE_FIRST + (l * VOWELS.length + v) * SYLLABLES_PER_VOWEL).toChar()
    }

    /** ㅏ then ㅣ on the side keys: the vowel they make together. */
    private val WITH_I: Map<Char, Char> = mapOf('ㅏ' to 'ㅐ', 'ㅑ' to 'ㅒ', 'ㅓ' to 'ㅔ', 'ㅕ' to 'ㅖ')

    /**
     * What ㆍ does to the vowel before it: its y-form and back, in both
     * directions, as Samsung's KoreanMoa engine has it. ㅜ ㅣ then ㆍ is ㅝ, which
     * [foldVowelKeys] handles apart because it is two jamo in the buffer.
     */
    private val WITH_DOT: Map<Char, Char> = buildMap {
        for ((plain, y) in listOf('ㅏ' to 'ㅑ', 'ㅓ' to 'ㅕ', 'ㅗ' to 'ㅛ', 'ㅜ' to 'ㅠ', 'ㅐ' to 'ㅒ', 'ㅔ' to 'ㅖ')) {
            put(plain, y)
            put(y, plain)
        }
        put(I, 'ㅏ')
        put('ㅡ', 'ㅜ')
    }

    /** [buffer] with every open syllable written as its initial and vowel. */
    fun openSyllables(buffer: String): String {
        val first = buffer.indexOfFirst { it.code >= SYLLABLE_FIRST }
        if (first < 0) return buffer
        val out = StringBuilder(buffer.length + 4)
        for (c in buffer) {
            val index = c.code - SYLLABLE_FIRST
            if (index < 0 || index % SYLLABLES_PER_VOWEL != 0 || c.code > 0xD7A3) {
                out.append(c)
                continue
            }
            val vowel = index / SYLLABLES_PER_VOWEL
            out.append(INITIALS[vowel / VOWELS.length]).append(VOWELS[vowel % VOWELS.length])
        }
        return out.toString()
    }

    /**
     * Jamo with the side keys folded into the vowel before them: ㅏ ㅣ is ㅐ, ㅏ ㆍ
     * is ㅑ, ㅗ ㅣ ㆍ is ㅘ. A key that follows no vowel is left for
     * [HangulComposer], where a ㅣ after a consonant opens a syllable as usual.
     */
    fun foldVowelKeys(jamo: String): String {
        if (I !in jamo && DOT !in jamo) return jamo
        val out = StringBuilder(jamo.length)
        for (c in jamo) {
            val last = out.lastOrNull()
            val folded = when {
                c == I -> WITH_I[last]
                c != DOT -> null
                out.endsWith("ㅜ$I") -> 'ㅓ'
                out.endsWith("ㅜㅓ") -> I
                else -> WITH_DOT[last]
            }
            if (folded != null) out.setCharAt(out.length - 1, folded) else out.append(c)
        }
        return out.toString()
    }
}
