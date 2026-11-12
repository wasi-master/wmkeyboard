package com.wasimaster.wmkeyboard.core.transliteration

import java.text.Normalizer

/**
 * Unicode Tamil to Bamini, the legacy font Sri Lankan and diaspora Tamil text
 * is still typed in (#1252): கொ is `nfh`, சூ is `#`.
 *
 * Ported from `src/bamini.ts` of legacy-fonts-to-unicode (osamamumtaz01, MIT),
 * whose glyph assignments are the Bamini table of aril (digital-tamil, MIT or
 * Apache-2.0); aril credits open-tamil's txt2unicode tables as its ground
 * truth. The first version of this output came from Android-TamilUtil, whose
 * table writes ஃ, சூ, தூ and a few more with glyphs both aril and open-tamil
 * read as other letters: on the 229 of 15,162 Tamil words where the two
 * disagreed, open-tamil read this table right 224 times and Android-TamilUtil's
 * 65.
 *
 * Bamini writes a syllable as its consonant glyph with the sign after it,
 * except ெ ே ை (`n`, `N`, `i`), which go in front, and ொ ோ ௌ, split around it.
 * Sixteen consonants carry ு in a glyph of their own (கு is `F`), and their ூ
 * is that glyph with a hook (தூ is `Jh`, பூ is `G+`) or a glyph of its own.
 */
internal object BaminiEncoding {

    private const val PULLI = '்'

    private val CONSONANT = mapOf(
        'க' to "f", 'ங' to "q", 'ச' to "r", 'ஜ' to "[", 'ஞ' to "Q", 'ட' to "l", 'ண' to "z", 'த' to "j",
        'ந' to "e", 'ன' to "d", 'ப' to "g", 'ம' to "k", 'ய' to "a", 'ர' to "u", 'ல' to "y", 'ள' to "s",
        'வ' to "t", 'ழ' to "o", 'ற' to "w", 'ஹ' to "`", 'ஷ' to "\\", 'ஸ' to "]",
    )

    /** Glyphs that already carry ு or ூ, or ி / ீ for ட. */
    private val LIGATURE = mapOf(
        "கு" to "F", "சு" to "R", "டு" to "L", "ணு" to "Z", "து" to "J", "நு" to "E", "னு" to "D", "பு" to "G",
        "மு" to "K", "யு" to "A", "ரு" to "U", "லு" to "Y", "ளு" to "S", "ழு" to "O", "று" to "W", "வு" to "T",
        "கூ" to "$", "சூ" to "#", "டூ" to "^", "மூ" to "%", "ரூ" to "&", "ழூ" to "*",
        "டி" to "b", "டீ" to "B",
    )

    private val OTHER = mapOf(
        'அ' to "m", 'ஆ' to "M", 'இ' to ",", 'ஈ' to "<", 'உ' to "c", 'ஊ' to "C", 'எ' to "v", 'ஏ' to "V",
        'ஐ' to "I", 'ஒ' to "x", 'ஓ' to "X", 'ஔ' to "xs", 'ஃ' to "/", ',' to ">", ';' to "@",
    )

    /** Consonants whose ூ is the ு glyph plus a hook. */
    private val UU_HOOK = mapOf(
        "ண" to "h", "த" to "h", "ந" to "h", "ன" to "h", "ல" to "h", "ற" to "h", "ள" to "h", "க" to "h",
        "ச" to "h", "ம" to "h", "ர" to "h", "ழ" to "h", "ப" to "+", "ய" to "+", "வ" to "+",
    )

    private val SIGN = mapOf('ா' to "h", 'ி' to "p", 'ீ' to "P", PULLI to ";")

    /** The marks the font keeps on other keys (`,` is `>`), so they move too. */
    val MARKS: Set<Char> = setOf(',', ';')

    fun convert(text: String): String {
        val a = Normalizer.normalize(text, Normalizer.Form.NFC).replace("ஸ்ரீ", "=")
        val out = StringBuilder(a.length + 8)
        var i = 0
        while (i < a.length) {
            val c = a[i]
            val consonant = CONSONANT[c]
            if (consonant == null) {
                out.append(OTHER[c] ?: c.toString())
                i++
                continue
            }
            // A consonant, or க்ஷ as one unit.
            var base = c.toString()
            var glyph = consonant
            var k = i + 1
            if (c == 'க' && a.getOrNull(k) == PULLI && a.getOrNull(k + 1) == 'ஷ') {
                base = "க்ஷ"
                glyph = "f;\\"
                k += 2
            }
            val v = a.getOrNull(k)
            when (v) {
                'ெ', 'ே', 'ை' -> {
                    out.append(if (v == 'ெ') "n" else if (v == 'ே') "N" else "i").append(glyph)
                    k++
                }
                'ொ', 'ோ', 'ௌ' -> {
                    out.append(if (v == 'ோ') "N" else "n").append(glyph).append(if (v == 'ௌ') "s" else "h")
                    k++
                }
                'ு', 'ூ' -> {
                    val ligature = LIGATURE[base + v]
                    val uHook = UU_HOOK[base]
                    when {
                        ligature != null -> out.append(ligature)
                        v == 'ூ' && LIGATURE[base + "ு"] != null && uHook != null ->
                            out.append(LIGATURE.getValue(base + "ு")).append(uHook)
                        base == "ஞ" -> out.append(if (v == 'ு') "Qc" else "Qc}")
                        // Grantha letters and ங
                        else -> out.append(glyph).append(if (v == 'ு') "{" else "_")
                    }
                    k++
                }
                'ி', 'ீ' -> {
                    out.append(LIGATURE[base + v] ?: (glyph + SIGN.getValue(v)))
                    k++
                }
                else -> {
                    val sign = v?.let { SIGN[it] }
                    out.append(glyph)
                    if (sign != null) {
                        out.append(sign)
                        k++
                    }
                }
            }
            i = k
        }
        return out.toString()
    }
}
