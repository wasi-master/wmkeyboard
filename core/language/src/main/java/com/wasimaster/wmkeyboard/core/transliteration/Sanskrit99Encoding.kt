package com.wasimaster.wmkeyboard.core.transliteration

import java.text.Normalizer

/**
 * Unicode Devanagari to Sanskrit 99, the font of Ulrich Stiehl's Itranslator 99
 * that Sanskrit texts were set in (#1252): संस्कृतम् is `s<Sk«tm!`.
 *
 * Unlike Kruti Dev it has a glyph for most Sanskrit conjuncts and writes the
 * virama out, so [Sanskrit99Table] is the inverted table of fontconverter.in's
 * Sanskrit 99 converter with the ligature list of Stiehl's own manual laid over
 * it. The steps are the app's own: the reph (`R`, or `¡` with an anusvara) goes
 * after its syllable, ि goes in front of its cluster, and the longest sequence
 * the table knows is replaced at each place. A virama the font has no half
 * letter for is written as a mark, `œ` under a letter with no stem and `!`
 * after one with a stem, as the manual does.
 *
 * Checked by reading the output back with that converter, taught the manual's
 * ligatures: 14,815 of 14,816 words from Sanskrit Wikipedia and 5,290 of the
 * 5,293 Hindi words of the app's translation come back unchanged (the others
 * are a vowel sign the font has no glyph for and three nukta letters the
 * converter misreads), and all 346 ligatures in the manual come out as listed.
 */
internal object Sanskrit99Encoding {

    private const val CONSONANT = "[\\u0915-\\u0939\\u0958-\\u095F]"
    private const val C = "$CONSONANT\\u093C?"

    /** A virama with no consonant after it: written out, not as a half letter. */
    private val LONE_VIRAMA = Regex("($C)\\u094D(?![\\u0915-\\u0939\\u0958-\\u095F\\u200D])")

    /** र् starting a syllable (not the ra of a cluster such as त्र्य), with what it sits over. */
    private val REPH = Regex("(?<!\\u094D)\\u0930\\u094D((?:$C\\u094D)*$C)([\\u093E-\\u094C\\u0962\\u0963]*)([\\u0901\\u0902]?)")
    private val I_SIGN = Regex("((?:$C\\u094D)*$C)\\u093F")
    private val PART = Regex("$C\\u094D?")

    /** Letters without a stem on the right, which take the virama under them (`œ`). */
    private const val STEMLESS = "कङछटठडढदफरह"

    private const val VIRAMA_MARK = ''

    private val LONGEST = Sanskrit99Table.GLYPHS.keys.maxOf { it.length }

    fun convert(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFC)
        s = LONE_VIRAMA.replace(s) { it.groupValues[1] + VIRAMA_MARK }
        s = REPH.replace(s) { m ->
            m.groupValues[1] + m.groupValues[2] + if (m.groupValues[3] == "ं") "¡" else "R" + m.groupValues[3]
        }
        s = I_SIGN.replace(s) { m ->
            val parts = PART.findAll(m.groupValues[1]).map { it.value }.toList()
            var cut = 0
            for (k in 0 until parts.size - 1) if (parts[k] !in Sanskrit99Table.GLYPHS) cut = k + 1
            parts.subList(0, cut).joinToString("") + "ि" + parts.subList(cut, parts.size).joinToString("")
        }
        return encode(s)
    }

    private fun encode(s: String): String {
        val table = Sanskrit99Table.GLYPHS
        val out = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            if (s[i] == VIRAMA_MARK) {
                out.append(viramaAfter(s, i))
                i++
                continue
            }
            var matched = false
            for (length in minOf(LONGEST, s.length - i) downTo 1) {
                val key = s.substring(i, i + length)
                val glyphs = table[key] ?: continue
                val next = i + length
                // A half letter does not swallow the consonant that takes the ra under it.
                if (length > 2 && key.endsWith('्') && s.startsWith("र", next) &&
                    (key[length - 2].toString() + "्र") in table
                ) continue
                // Nor does a letter leave its nukta behind.
                if (s.startsWith("़", next) && key.last() != '़') continue
                out.append(if (key == "्") viramaAfter(s, i) else glyphs)
                i = next
                matched = true
                break
            }
            if (!matched) {
                out.append(s[i])
                i++
            }
        }
        return out.toString()
    }

    /** The virama mark for the letter before [at]: under a letter with no stem, after one with a stem. */
    private fun viramaAfter(s: String, at: Int): Char {
        var j = at - 1
        if (j >= 0 && s[j] == '़') j--
        return if (j >= 0 && s[j] in STEMLESS) 'œ' else '!'
    }
}
