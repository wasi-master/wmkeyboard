package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Korean Hangul input: composes a run of jamo into syllable blocks (한, 글).
 * Like Avro it is a transliterating composer — the keys go into a buffer and
 * [composeBuffer] renders the buffer to text, which the service shows as the
 * composing region and commits as a unit.
 *
 * A syllable is `0xAC00 + (L*21 + V)*28 + T`, L an initial consonant (19), V a
 * medial vowel (21), T a final consonant (28, 0 = none). The automaton adds jamo
 * left to right, handling compound medials (ㅗ+ㅏ→ㅘ) and compound finals
 * (ㄱ+ㅅ→ㄳ).
 *
 * Two kinds of jamo arrive here, and they mean different things:
 *
 *  - **Compatibility jamo** (U+3131…), what a two-set (두벌식) keyboard emits.
 *    One key is ㄱ whether it starts a syllable or ends one, so the automaton
 *    decides by position: a consonant after a vowel is a final, and a final
 *    followed by a vowel re-splits into the next syllable's initial
 *    (간+ㅏ → 가나, 갉+ㅣ → 갈기).
 *  - **Conjoining jamo** (U+1100…), what a three-set (세벌식) keyboard emits.
 *    The key itself says whether it is an initial ᄀ or a final ᆨ — they are
 *    different code points — so nothing is guessed: an initial after a vowel
 *    starts a new syllable rather than closing the current one, a final only
 *    ever closes one, and a vowel after a final starts a fresh syllable
 *    instead of pulling the final across. Three-set layouts carry no tense
 *    initials of their own, so an initial typed twice doubles
 *    (ᄀᄀ → ᄁ, giving 까 따 빠 싸 짜).
 *
 * Whatever comes in, isolated jamo go out as compatibility jamo — a lone
 * conjoining ᆫ draws as a dotted circle with a final hanging off it, and the
 * two-set layout has always left ㄴ behind for the same keystroke.
 */
object HangulComposer : Composer {

    override val isTransliterating: Boolean get() = true

    override fun composeBuffer(buffer: String): String = compose(buffer)

    // Compatibility jamo (U+3131…), in Unicode syllable order.
    private const val CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ" // 19 initials
    private const val JUNGSEONG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ" // 21 medials
    // 28 finals; index 0 is "no final", written as a space placeholder.
    private const val JONGSEONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    // Conjoining jamo (U+1100 block): each run sits in Unicode syllable order,
    // so a code point's offset from its run's start is its L, V or T index.
    private const val CHOSEONG_FIRST = 0x1100 // ᄀ
    private const val CHOSEONG_LAST = 0x1112 // ᄒ
    private const val JUNGSEONG_FIRST = 0x1161 // ᅡ
    private const val JUNGSEONG_LAST = 0x1175 // ᅵ
    private const val JONGSEONG_FIRST = 0x11A8 // ᆨ, T index 1
    private const val JONGSEONG_LAST = 0x11C2 // ᇂ, T index 27

    private val COMPOUND_MEDIAL = mapOf(
        ('ㅗ' to 'ㅏ') to 'ㅘ', ('ㅗ' to 'ㅐ') to 'ㅙ', ('ㅗ' to 'ㅣ') to 'ㅚ',
        ('ㅜ' to 'ㅓ') to 'ㅝ', ('ㅜ' to 'ㅔ') to 'ㅞ', ('ㅜ' to 'ㅣ') to 'ㅟ',
        ('ㅡ' to 'ㅣ') to 'ㅢ',
    )

    private val COMPOUND_FINAL = mapOf(
        ('ㄱ' to 'ㅅ') to 'ㄳ', ('ㄴ' to 'ㅈ') to 'ㄵ', ('ㄴ' to 'ㅎ') to 'ㄶ',
        ('ㄹ' to 'ㄱ') to 'ㄺ', ('ㄹ' to 'ㅁ') to 'ㄻ', ('ㄹ' to 'ㅂ') to 'ㄼ',
        ('ㄹ' to 'ㅅ') to 'ㄽ', ('ㄹ' to 'ㅌ') to 'ㄾ', ('ㄹ' to 'ㅍ') to 'ㄿ',
        ('ㄹ' to 'ㅎ') to 'ㅀ', ('ㅂ' to 'ㅅ') to 'ㅄ',
    )

    /** A compound final splits into (kept final, consonant moved to next initial). */
    private val FINAL_SPLIT = mapOf(
        'ㄳ' to ('ㄱ' to 'ㅅ'), 'ㄵ' to ('ㄴ' to 'ㅈ'), 'ㄶ' to ('ㄴ' to 'ㅎ'),
        'ㄺ' to ('ㄹ' to 'ㄱ'), 'ㄻ' to ('ㄹ' to 'ㅁ'), 'ㄼ' to ('ㄹ' to 'ㅂ'),
        'ㄽ' to ('ㄹ' to 'ㅅ'), 'ㄾ' to ('ㄹ' to 'ㅌ'), 'ㄿ' to ('ㄹ' to 'ㅍ'),
        'ㅀ' to ('ㄹ' to 'ㅎ'), 'ㅄ' to ('ㅂ' to 'ㅅ'),
    )

    /** The tense initial a plain initial typed twice becomes, on a three-set layout. */
    private val DOUBLED_INITIAL = mapOf(
        'ㄱ' to 'ㄲ', 'ㄷ' to 'ㄸ', 'ㅂ' to 'ㅃ', 'ㅅ' to 'ㅆ', 'ㅈ' to 'ㅉ',
    )

    private fun lIndex(c: Char) = CHOSEONG.indexOf(c)
    private fun vIndex(c: Char) = JUNGSEONG.indexOf(c)
    private fun tIndex(c: Char) = JONGSEONG.indexOf(c) // 0 = none, >0 = a real final

    private fun syllable(l: Int, v: Int, t: Int): Char =
        (0xAC00 + (l * 21 + v) * 28 + t).toChar()

    private fun compose(jamos: String): String {
        val out = StringBuilder()
        var l = -1 // choseong index, -1 none
        var v = -1 // jungseong index, -1 none
        var t = 0 // jongseong index, 0 none

        fun flush() {
            when {
                l >= 0 && v >= 0 -> out.append(syllable(l, v, t))
                l >= 0 -> out.append(CHOSEONG[l])
                v >= 0 -> out.append(JUNGSEONG[v])
            }
            l = -1; v = -1; t = 0
        }

        /** A vowel, from either block: only the re-split differs. */
        fun jungseong(vi: Int, positional: Boolean) {
            when {
                v < 0 -> v = vi // starts or follows an initial
                t == 0 -> {
                    val cm = COMPOUND_MEDIAL[JUNGSEONG[v] to JUNGSEONG[vi]]
                    if (cm != null) v = vIndex(cm) else { flush(); v = vi }
                }
                positional -> { flush(); v = vi } // the final was typed as one; it stays
                else -> { // two-set: the final becomes the next syllable's initial
                    val split = FINAL_SPLIT[JONGSEONG[t]]
                    val moved: Char
                    if (split != null) { t = tIndex(split.first); moved = split.second }
                    else { moved = JONGSEONG[t]; t = 0 }
                    flush(); l = lIndex(moved); v = vi
                }
            }
        }

        /** A conjoining initial: never a final, and doubles when typed twice. */
        fun choseong(li: Int) {
            when {
                l < 0 && v < 0 -> l = li
                v < 0 -> {
                    val doubled = DOUBLED_INITIAL[CHOSEONG[l]]
                    if (doubled != null && li == l) l = lIndex(doubled) else { flush(); l = li }
                }
                else -> { flush(); l = li }
            }
        }

        /** A conjoining final: closes the open syllable or stands alone. */
        fun jongseong(ti: Int) {
            when {
                l >= 0 && v >= 0 && t == 0 -> t = ti
                l >= 0 && v >= 0 -> {
                    val cf = COMPOUND_FINAL[JONGSEONG[t] to JONGSEONG[ti]]
                    if (cf != null) t = tIndex(cf) else { flush(); out.append(JONGSEONG[ti]) }
                }
                else -> { flush(); out.append(JONGSEONG[ti]) }
            }
        }

        /** A compatibility consonant: initial or final by where it lands. */
        fun compatConsonant(c: Char, ci: Int, ti: Int) {
            when {
                l < 0 && v < 0 -> if (ci >= 0) l = ci else out.append(c)
                v < 0 -> { flush(); if (ci >= 0) l = ci else out.append(c) }
                t == 0 -> if (l >= 0 && ti > 0) t = ti else { flush(); if (ci >= 0) l = ci else out.append(c) }
                else -> {
                    val cf = COMPOUND_FINAL[JONGSEONG[t] to c]
                    if (cf != null) t = tIndex(cf)
                    else { flush(); if (ci >= 0) l = ci else out.append(c) }
                }
            }
        }

        for (c in jamos) {
            val code = c.code
            when {
                code in CHOSEONG_FIRST..CHOSEONG_LAST -> choseong(code - CHOSEONG_FIRST)
                code in JUNGSEONG_FIRST..JUNGSEONG_LAST ->
                    jungseong(code - JUNGSEONG_FIRST, positional = true)
                code in JONGSEONG_FIRST..JONGSEONG_LAST -> jongseong(code - JONGSEONG_FIRST + 1)
                else -> {
                    val ci = lIndex(c)
                    val vi = vIndex(c)
                    val ti = tIndex(c)
                    when {
                        vi >= 0 -> jungseong(vi, positional = false)
                        ci >= 0 || ti > 0 -> compatConsonant(c, ci, ti)
                        else -> { flush(); out.append(c) } // not a jamo
                    }
                }
            }
        }
        flush()
        return out.toString()
    }
}
