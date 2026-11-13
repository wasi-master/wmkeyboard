package com.wasimaster.wmkeyboard.core.transliteration

import java.text.Normalizer

/**
 * Unicode Kannada to the Nudi / Baraha ASCII encoding, what Karnataka's
 * government offices and most Kannada print still type in (#1252): ಮತ್ತು is
 * `ªÀÄvÀÄÛ`, ಪ್ರತಿ is `¥Àæw`.
 *
 * The glyph table is the data of sanchaya's kannada-font-converter
 * ([NudiTable]); the code is this app's own. The order follows real Nudi and
 * Baraha text (aravindavk/ascii2unicode's test samples): a syllable is the
 * glyph of its first consonant with its vowel sign, then each further
 * consonant's subjoined form (ಕ್ಕ is `PÀÌ`), then the long mark `Ã` or the
 * ai mark `Ê` a split vowel leaves (ತ್ತೇ is `vÉÛÃ`). The reph `ð` goes right
 * after the syllable glyph (ಕೀರ್ತಿ is `QÃwð`). ಪ keeps the ordinary u and uu
 * marks; `Å` and `Ç` are ವ's.
 *
 * Checked by reading the output back with aravindavk's knconverter: all 13,313
 * Kannada words of the app's translation came back, but for two with a typo
 * of a doubled virama and two with Kannada digits, which Nudi writes as ASCII.
 */
internal object KannadaNudiEncoding {

    private const val VIRAMA = '್'

    private const val CONSONANTS = "ಕಖಗಘಙಚಛಜಝಞಟಠಡಢಣತಥದಧನಪಫಬಭಮಯರಲವಶಷಸಹಳೞ"

    /** A consonant's subjoined form, the first under its base. */
    private val SUBJOINED: Map<Char, String> =
        "ಕಖಗಘಙಚಛಜಝಞಟಠಡಢಣತಥದಧನಪಫಬಭಮಯರಲವಶಷಸಹಳ".zip("ÌÍÎÏÐÑÒÓÔÕÖ×ØÙÚÛÜÝÞßàáâãäåæèéêëìíî")
            .associate { (c, g) -> c to g.toString() }

    private const val SIGNS = "ಾಿೀುೂೃೄೆೇೈೊೋೌ"

    /** Split vowels: the sign the glyph table carries, and the mark that follows the subjoined letters. */
    private val SPLIT = mapOf('ೀ' to ('ಿ' to "Ã"), 'ೇ' to ('ೆ' to "Ã"), 'ೈ' to ('ೆ' to "Ê"), 'ೋ' to ('ೊ' to "Ã"))

    /** The marks the encoding writes differently: Kannada digits go in as ASCII ones. */
    val MARKS: Set<Char> = emptySet()

    private fun isConsonant(c: Char?) = c != null && c in CONSONANTS

    private fun glyph(key: String): String = NudiTable.GLYPHS[key] ?: NudiTable.GLYPHS[key.substring(0, 1)] ?: key

    /** The [index]th subjoined letter: ra and ya under another subjoined letter take their second form. */
    private fun subjoined(c: Char, index: Int): String = when (c) {
        'ರ' -> if (index == 0) "æ" else "ç"
        'ಯ' -> if (index == 0) "å" else "öå"
        else -> SUBJOINED[c] ?: "$VIRAMA$c"
    }

    fun convert(text: String): String {
        val t = Normalizer.normalize(text, Normalizer.Form.NFC)
        val out = StringBuilder(t.length * 2)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (isConsonant(c)) {
                var first = c
                // ರ್ before a consonant is the reph, except before ಯ, where ರ stays whole.
                var reph = false
                if (c == 'ರ' && t.getOrNull(i + 1) == VIRAMA && isConsonant(t.getOrNull(i + 2)) && t[i + 2] != 'ಯ') {
                    reph = true
                    i += 2
                    first = t[i]
                }
                val cluster = mutableListOf(first)
                var j = i + 1
                while (t.getOrNull(j) == VIRAMA && isConsonant(t.getOrNull(j + 1))) {
                    cluster += t[j + 1]
                    j += 2
                }
                var sign: Char? = null
                var dead = false
                when {
                    t.getOrNull(j)?.let { it in SIGNS } == true -> sign = t[j++]
                    t.getOrNull(j) == VIRAMA -> {
                        dead = true
                        j++
                    }
                }
                if (dead) {
                    // A cluster ending in a virama is written letter by letter, each with its own.
                    if (reph) out.append("gï")
                    for (letter in cluster) out.append(glyph("$letter$VIRAMA"))
                } else {
                    var tail = ""
                    val split = sign?.let { SPLIT[it] }
                    if (split != null) {
                        sign = split.first
                        tail = split.second
                    }
                    out.append(glyph(if (sign != null) "$first$sign" else first.toString()))
                    if (reph) out.append('ð')
                    for (k in 1 until cluster.size) out.append(subjoined(cluster[k], k - 1))
                    out.append(tail)
                }
                i = j
                continue
            }
            when {
                c == '‌' || c == '‍' -> Unit
                // An independent vowel with a subjoined letter: ಆ್ಯ, the vowel of "app".
                NudiTable.GLYPHS.containsKey(c.toString()) && t.getOrNull(i + 1) == VIRAMA && isConsonant(t.getOrNull(i + 2)) -> {
                    out.append(NudiTable.GLYPHS.getValue(c.toString())).append(subjoined(t[i + 2], 0))
                    i += 3
                    continue
                }
                c == 'ಂ' -> out.append('A')
                c == 'ಃ' -> out.append('B')
                c == VIRAMA -> out.append('ï')
                c in '೦'..'೯' -> out.append('0' + (c - '೦'))
                else -> out.append(NudiTable.GLYPHS[c.toString()] ?: c.toString())
            }
            i++
        }
        return out.toString()
    }
}
