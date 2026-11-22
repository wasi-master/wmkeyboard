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
 *
 * **Old Hangul.** Obsolete jamo (ㆍ ㅿ ㆁ ㆆ ㅸ and the rest, as compatibility
 * letters U+3165–318E or as conjoining jamo from the old-Hangul ranges) have
 * no precomposed syllables, so a syllable holding one goes out as a run of
 * conjoining jamo, initial + medial (+ final), which a font with old-Hangul
 * support draws as one block: ㅎ ㆍ ㄴ → ᄒᆞᆫ. Compatibility letters
 * still find their place by position the two-set way, ㆍ ㅣ makes ᆡ and
 * ㆍ ㆍ makes ᆢ.
 */
object HangulComposer : Composer {

    override val isTransliterating: Boolean get() = true

    override val offersHanja: Boolean get() = true

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

    /** A jamo's conjoining initial, medial and final forms; 0 where it has none. */
    private class Forms(val l: Int, val v: Int, val t: Int)

    /** The obsolete compatibility letters, from their Unicode names. */
    private val OLD_COMPAT: Map<Char, Forms> = mapOf(
        'ㅥ' to Forms(0x1114, 0, 0x11FF), // ssangnieun
        'ㅦ' to Forms(0x1115, 0, 0x11C6), // nieun-tikeut
        'ㅧ' to Forms(0x115B, 0, 0x11C7), // nieun-sios
        'ㅨ' to Forms(0, 0, 0x11C8), // nieun-pansios
        'ㅩ' to Forms(0, 0, 0x11CC), // rieul-kiyeok-sios
        'ㅪ' to Forms(0xA966, 0, 0x11CE), // rieul-tikeut
        'ㅫ' to Forms(0, 0, 0x11D3), // rieul-pieup-sios
        'ㅬ' to Forms(0, 0, 0x11D7), // rieul-pansios
        'ㅭ' to Forms(0, 0, 0x11D9), // rieul-yeorinhieuh
        'ㅮ' to Forms(0x111C, 0, 0x11DC), // mieum-pieup
        'ㅯ' to Forms(0xA971, 0, 0x11DD), // mieum-sios
        'ㅰ' to Forms(0, 0, 0x11DF), // mieum-pansios
        'ㅱ' to Forms(0x111D, 0, 0x11E2), // kapyeounmieum
        'ㅲ' to Forms(0x111E, 0, 0), // pieup-kiyeok
        'ㅳ' to Forms(0x1120, 0, 0xD7E3), // pieup-tikeut
        'ㅴ' to Forms(0x1122, 0, 0), // pieup-sios-kiyeok
        'ㅵ' to Forms(0x1123, 0, 0xD7E7), // pieup-sios-tikeut
        'ㅶ' to Forms(0x1127, 0, 0xD7E8), // pieup-cieuc
        'ㅷ' to Forms(0x1129, 0, 0), // pieup-thieuth
        'ㅸ' to Forms(0x112B, 0, 0x11E6), // kapyeounpieup
        'ㅹ' to Forms(0x112C, 0, 0), // kapyeounssangpieup
        'ㅺ' to Forms(0x112D, 0, 0x11E7), // sios-kiyeok
        'ㅻ' to Forms(0x112E, 0, 0), // sios-nieun
        'ㅼ' to Forms(0x112F, 0, 0x11E8), // sios-tikeut
        'ㅽ' to Forms(0x1132, 0, 0x11EA), // sios-pieup
        'ㅾ' to Forms(0x1136, 0, 0xD7EF), // sios-cieuc
        'ㅿ' to Forms(0x1140, 0, 0x11EB), // pansios
        'ㆀ' to Forms(0x1147, 0, 0x11EE), // ssangieung
        'ㆁ' to Forms(0x114C, 0, 0x11F0), // yesieung
        'ㆂ' to Forms(0, 0, 0x11F1), // yesieung-sios
        'ㆃ' to Forms(0, 0, 0x11F2), // yesieung-pansios
        'ㆄ' to Forms(0x1157, 0, 0x11F4), // kapyeounphieuph
        'ㆅ' to Forms(0x1158, 0, 0), // ssanghieuh
        'ㆆ' to Forms(0x1159, 0, 0x11F9), // yeorinhieuh
        'ㆇ' to Forms(0, 0x1184, 0), // yo-ya
        'ㆈ' to Forms(0, 0x1185, 0), // yo-yae
        'ㆉ' to Forms(0, 0x1188, 0), // yo-i
        'ㆊ' to Forms(0, 0x1191, 0), // yu-yeo
        'ㆋ' to Forms(0, 0x1192, 0), // yu-ye
        'ㆌ' to Forms(0, 0x1194, 0), // yu-i
        'ㆍ' to Forms(0, 0x119E, 0), // araea
        'ㆎ' to Forms(0, 0x11A1, 0), // araeae
    )

    /** Conjoining initials, medials and finals outside the modern 19/21/27. */
    private fun isOldInitial(code: Int) = code in 0x1113..0x115E || code in 0xA960..0xA97C
    private fun isOldMedial(code: Int) = code in 0x1176..0x11A7 || code in 0xD7B0..0xD7C6
    private fun isOldFinal(code: Int) = code in 0x11C3..0x11FF || code in 0xD7CB..0xD7FB

    private fun isOld(c: Char): Boolean =
        c in OLD_COMPAT || isOldInitial(c.code) || isOldMedial(c.code) || isOldFinal(c.code)

    private const val CHOSEONG_FILLER = 0x115F
    private const val JUNGSEONG_FILLER = 0x1160
    private const val ARAEA = 0x119E

    /** Medial pairs in conjoining form: the modern compounds plus ㆍㅣ → ᆡ and ㆍㆍ → ᆢ. */
    private val OLD_COMPOUND_MEDIAL: Map<Pair<Int, Int>, Int> by lazy {
        COMPOUND_MEDIAL.entries.associate { (pair, out) ->
            (JUNGSEONG_FIRST + vIndex(pair.first) to JUNGSEONG_FIRST + vIndex(pair.second)) to JUNGSEONG_FIRST + vIndex(out)
        } + mapOf((ARAEA to JUNGSEONG_FIRST + vIndex('ㅣ')) to 0x11A1, (ARAEA to ARAEA) to 0x11A2)
    }

    /** What a compatibility jamo [c] can be, in conjoining form; null when it is not one. */
    private fun compatForms(c: Char): Forms? {
        OLD_COMPAT[c]?.let { return it }
        val ci = lIndex(c)
        val vi = vIndex(c)
        val ti = tIndex(c)
        if (ci < 0 && vi < 0 && ti <= 0) return null
        return Forms(
            if (ci >= 0) CHOSEONG_FIRST + ci else 0,
            if (vi >= 0) JUNGSEONG_FIRST + vi else 0,
            if (ti > 0) JONGSEONG_FIRST + ti - 1 else 0,
        )
    }

    /** The compatibility letter a lone conjoining jamo shows as, or itself. */
    private fun lone(code: Int): String {
        when (code) {
            in CHOSEONG_FIRST..CHOSEONG_LAST -> return CHOSEONG[code - CHOSEONG_FIRST].toString()
            in JUNGSEONG_FIRST..JUNGSEONG_LAST -> return JUNGSEONG[code - JUNGSEONG_FIRST].toString()
            in JONGSEONG_FIRST..JONGSEONG_LAST -> return JONGSEONG[code - JONGSEONG_FIRST + 1].toString()
        }
        OLD_COMPAT.entries.firstOrNull { it.value.l == code || it.value.v == code || it.value.t == code }
            ?.let { return it.key.toString() }
        return String(Character.toChars(code))
    }

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

        // An old-Hangul syllable, in conjoining code points (0 = empty slot).
        // While [old] is set these hold the syllable and l, v, t stay empty.
        var old = false
        var xl = 0
        var xv = 0
        var xt = 0
        var xtCompat: Char? = null // the compatibility letter the final was typed as

        fun flush() {
            if (old) {
                when {
                    xv == 0 && xt == 0 && xl != 0 -> out.append(lone(xl))
                    xl == 0 && xt == 0 && xv != 0 -> out.append(lone(xv))
                    // A syllable that ended up all modern (the one after an old
                    // one, typed while the run was still in conjoining form)
                    // goes out precomposed, like any other.
                    xl in CHOSEONG_FIRST..CHOSEONG_LAST && xv in JUNGSEONG_FIRST..JUNGSEONG_LAST &&
                        (xt == 0 || xt in JONGSEONG_FIRST..JONGSEONG_LAST) ->
                        out.append(
                            syllable(xl - CHOSEONG_FIRST, xv - JUNGSEONG_FIRST, if (xt == 0) 0 else xt - JONGSEONG_FIRST + 1),
                        )
                    xl != 0 || xv != 0 -> {
                        out.appendCodePoint(if (xl != 0) xl else CHOSEONG_FILLER)
                        out.appendCodePoint(if (xv != 0) xv else JUNGSEONG_FILLER)
                        if (xt != 0) out.appendCodePoint(xt)
                    }
                    xt != 0 -> out.append(lone(xt))
                }
                old = false; xl = 0; xv = 0; xt = 0; xtCompat = null
                return
            }
            when {
                l >= 0 && v >= 0 -> out.append(syllable(l, v, t))
                l >= 0 -> out.append(CHOSEONG[l])
                v >= 0 -> out.append(JUNGSEONG[v])
            }
            l = -1; v = -1; t = 0
        }

        /** Carries the modern syllable open so far over into conjoining form. */
        fun goOld() {
            if (old) return
            xl = if (l >= 0) CHOSEONG_FIRST + l else 0
            xv = if (v >= 0) JUNGSEONG_FIRST + v else 0
            xt = if (t > 0) JONGSEONG_FIRST + t - 1 else 0
            xtCompat = if (t > 0) JONGSEONG[t] else null
            l = -1; v = -1; t = 0
            old = true
        }

        fun oldVowel(vc: Int, positional: Boolean) {
            when {
                xv == 0 && xt == 0 -> xv = vc
                xt == 0 -> {
                    val cm = OLD_COMPOUND_MEDIAL[xv to vc]
                    if (cm != null) xv = cm else { flush(); old = true; xv = vc }
                }
                positional || xtCompat == null -> { flush(); old = true; xv = vc }
                else -> {
                    // Two-set: the final moves on to be the next syllable's initial.
                    val typed = xtCompat!!
                    val split = FINAL_SPLIT[typed]
                    val kept = split?.first
                    val moved = compatForms(split?.second ?: typed)?.l ?: 0
                    if (moved == 0) { flush(); old = true; xv = vc; return }
                    xt = kept?.let { JONGSEONG_FIRST + tIndex(it) - 1 } ?: 0
                    flush(); old = true; xl = moved; xv = vc
                }
            }
        }

        fun oldConsonant(c: Char, forms: Forms, positional: Boolean) {
            fun startInitial() {
                flush()
                if (forms.l != 0) { old = true; xl = forms.l } else out.append(c)
            }
            when {
                positional && forms.l != 0 -> if (xl == 0 && xv == 0 && xt == 0) xl = forms.l else startInitial()
                positional -> if (xv != 0 && xt == 0) { xt = forms.t; xtCompat = null } else { flush(); out.append(lone(forms.t)) }
                xv == 0 -> if (xl == 0 && forms.l != 0) xl = forms.l else startInitial()
                xt == 0 -> if (forms.t != 0) { xt = forms.t; xtCompat = c } else startInitial()
                else -> {
                    val cf = xtCompat?.let { COMPOUND_FINAL[it to c] }
                    if (cf != null) { xt = JONGSEONG_FIRST + tIndex(cf) - 1; xtCompat = cf } else startInitial()
                }
            }
        }

        /** Any jamo, once the syllable is an old-Hangul one. */
        fun oldJamo(c: Char) {
            val code = c.code
            when {
                code in CHOSEONG_FIRST..CHOSEONG_LAST || isOldInitial(code) -> oldConsonant(c, Forms(code, 0, 0), true)
                code in JUNGSEONG_FIRST..JUNGSEONG_LAST || isOldMedial(code) -> oldVowel(code, true)
                code in JONGSEONG_FIRST..JONGSEONG_LAST || isOldFinal(code) -> oldConsonant(c, Forms(0, 0, code), true)
                else -> {
                    val forms = compatForms(c)
                    when {
                        forms == null -> { flush(); out.append(c) }
                        forms.v != 0 -> oldVowel(forms.v, false)
                        else -> oldConsonant(c, forms, false)
                    }
                }
            }
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
            if (old || isOld(c)) {
                goOld()
                oldJamo(c)
                continue
            }
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
