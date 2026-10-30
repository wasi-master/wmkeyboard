package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Korean on the 천지인 (Cheonjiin) keypad, the 12-key pad most Korean phones
 * shipped with (discussion #372).
 *
 * Its vowels are not keys. Three strokes are — ㅣ (a person), ㆍ (the sky) and
 * ㅡ (the earth) — and a vowel is the strokes it is drawn with, in order:
 * ㅣ ㆍ is ㅏ, ㆍ ㅣ is ㅓ, ㆍ ㅡ is ㅗ, ㅡ ㆍ is ㅜ, a second dot makes the
 * y-vowel (ㅣ ㆍ ㆍ is ㅑ), and a trailing ㅣ makes the ㅐ/ㅔ family. So the
 * composing buffer holds the raw strokes, and [composeBuffer] reads each run of
 * them as the vowels it spells before handing the result to [HangulComposer],
 * which assembles syllables exactly as it does for the two-set grid.
 *
 * The consonants need nothing from here. They share keys (ㄱㅋ, ㅅㅎ …) and are
 * picked by tapping again, which `Key.multitap` does in the service, so by the
 * time a consonant reaches the buffer it is already the jamo the user chose.
 * That is also why the buffer never needs a "next letter" marker: ㄱ ㄱ in the
 * buffer is two ㄱ, because a single run of taps would have left ㅋ.
 *
 * Re-reading the whole buffer on every key is what makes the pad work the way
 * it looks: 가 plus ㆍ shows 가ㆍ until the ㅣ that turns it into 가 + ㅓ, and a
 * final waiting under 각 moves into the next syllable (가거) the moment its
 * vowel is complete, with no state carried between keystrokes. Backspace takes
 * one buffer character — a whole consonant, or one stroke (ㅏ back to ㅣ).
 */
object CheonjiinComposer : Composer {

    override val isTransliterating: Boolean get() = true

    override val offersHanja: Boolean get() = true

    override val spaceEndsMultitap: Boolean get() = true

    override fun composeBuffer(buffer: String): String =
        HangulComposer.composeBuffer(foldStrokes(buffer))

    /** ㅣ, the vertical stroke. Also the vowel ㅣ on its own. */
    const val I = 'ㅣ'

    /** ㆍ (U+318D, arae-a), the dot. Never a vowel on its own. */
    const val DOT = 'ㆍ'

    /** ㅡ, the horizontal stroke. Also the vowel ㅡ on its own. */
    const val EU = 'ㅡ'

    /** Two dots not yet part of a vowel, drawn the way Samsung's pad draws them. */
    private const val TWO_DOTS = "‥"

    /** Every vowel by the strokes that spell it — Samsung's and the original pad's table. */
    private val VOWELS: Map<String, Char> = mapOf(
        "ㅣ" to 'ㅣ', "ㅡ" to 'ㅡ',
        "ㅣㆍ" to 'ㅏ', "ㅣㆍㆍ" to 'ㅑ', "ㆍㅣ" to 'ㅓ', "ㆍㆍㅣ" to 'ㅕ',
        "ㆍㅡ" to 'ㅗ', "ㆍㆍㅡ" to 'ㅛ', "ㅡㆍ" to 'ㅜ', "ㅡㆍㆍ" to 'ㅠ',
        "ㅡㅣ" to 'ㅢ',
        "ㅣㆍㅣ" to 'ㅐ', "ㅣㆍㆍㅣ" to 'ㅒ', "ㆍㅣㅣ" to 'ㅔ', "ㆍㆍㅣㅣ" to 'ㅖ',
        "ㆍㅡㅣ" to 'ㅚ', "ㆍㅡㅣㆍ" to 'ㅘ', "ㆍㅡㅣㆍㅣ" to 'ㅙ',
        "ㅡㆍㅣ" to 'ㅟ', "ㅡㆍㆍㅣ" to 'ㅝ', "ㅡㆍㆍㅣㅣ" to 'ㅞ',
    )

    private val LONGEST = VOWELS.keys.maxOf { it.length }

    private fun isStroke(c: Char) = c == I || c == DOT || c == EU

    /**
     * [buffer] with every run of strokes read as the vowels it spells, and
     * everything else left alone. Public so the table can be tested without
     * going through syllable assembly.
     *
     * A run is read greedily, longest spelling first, which is unambiguous
     * because the pad itself is: every longer spelling extends a shorter one
     * that means the vowel it grows out of (ㅗ → ㅚ → ㅘ → ㅙ). A dot typed a
     * third time cycles back to one, as on the pad (ㅏ ㆍ is ㅑ, ㅑ ㆍ is ㅏ
     * again), and dots that spell nothing yet are shown as they are.
     */
    fun foldStrokes(buffer: String): String {
        if (buffer.none(::isStroke)) return buffer
        val out = StringBuilder(buffer.length)
        var i = 0
        while (i < buffer.length) {
            if (!isStroke(buffer[i])) {
                out.append(buffer[i++])
                continue
            }
            var end = i
            while (end < buffer.length && isStroke(buffer[end])) end++
            readRun(foldDots(buffer.substring(i, end)), out)
            i = end
        }
        return out.toString()
    }

    /** Every run of three or more dots cut back to its place in the ㆍ → ‥ → ㆍ cycle. */
    private fun foldDots(run: String): String {
        if (!run.contains("$DOT$DOT$DOT")) return run
        val out = StringBuilder(run.length)
        var i = 0
        while (i < run.length) {
            if (run[i] != DOT) {
                out.append(run[i++])
                continue
            }
            var end = i
            while (end < run.length && run[end] == DOT) end++
            repeat(if ((end - i) % 2 == 0) 2 else 1) { out.append(DOT) }
            i = end
        }
        return out.toString()
    }

    private fun readRun(run: String, out: StringBuilder) {
        var i = 0
        while (i < run.length) {
            val vowel = (minOf(LONGEST, run.length - i) downTo 1).firstNotNullOfOrNull { len ->
                VOWELS[run.substring(i, i + len)]?.let { it to len }
            }
            if (vowel != null) {
                out.append(vowel.first)
                i += vowel.second
            } else if (i + 1 < run.length && run[i + 1] == DOT) {
                // Only a dot reaches here: ㅣ and ㅡ are vowels on their own.
                out.append(TWO_DOTS)
                i += 2
            } else {
                out.append(DOT)
                i++
            }
        }
    }
}
