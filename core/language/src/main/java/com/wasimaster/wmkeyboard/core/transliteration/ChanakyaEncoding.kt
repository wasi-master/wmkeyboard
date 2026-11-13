package com.wasimaster.wmkeyboard.core.transliteration

import java.text.Normalizer

/**
 * Unicode Hindi to Walkman Chanakya 905, the font Hindi newspapers and
 * publishers set type in (#1252): हिन्दी is `fgUnh`, as in Kruti Dev, whose
 * layout it largely shares.
 *
 * The glyph table ([ChanakyaTable]) is the data of IIT Delhi Assistech's
 * Walkman Chanakya converter, inverted; the code is this app's own, the same
 * steps as [LegacyFonts]' Kruti Dev: ्र under a round letter takes `ª`, the
 * reph (`Z`) goes after its syllable, ि goes in front of its cluster, then
 * the longest sequence the table knows is replaced at each place. A letter
 * with no half glyph (फ़) is written with a visible virama, and ि then goes
 * after it rather than in front of the whole cluster.
 *
 * Checked by reading the output back with that converter: all 5,293 Hindi
 * words of the app's translation came back unchanged.
 */
internal object ChanakyaEncoding {

    private const val C = "[\\u0915-\\u0939\\u0958-\\u095F]\\u093C?\\uE000?"
    private val REPH = Regex("\\u0930\\u094D((?:$C\\u094D)*$C)([\\u093E-\\u094C\\u0901\\u0902\\u0903]*)")
    private val I_SIGN = Regex("((?:$C\\u094D)*$C)\\u093F")
    private val PART = Regex("$C\\u094D?")
    private val ROUND_RA = Regex("([\\u091B\\u091F\\u0920\\u0921\\u0922])\\u094D\\u0930")
    private const val ROUND_RA_MARK = ''

    private val LONGEST = ChanakyaTable.GLYPHS.keys.maxOf { it.length }

    /** The marks outside Devanagari the font keeps glyphs on, so they move too. */
    val MARKS: Set<Char> = ChanakyaTable.GLYPHS.keys
        .filter { it.length == 1 && it[0] !in 'ऀ'..'ॿ' }
        .map { it[0] }
        .toSet()

    fun convert(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFC)
        s = ROUND_RA.replace(s) { it.groupValues[1] + ROUND_RA_MARK }
        // The reph goes in as the font's own Z, so the table's र्क glyph (`कZ` is `oZQ`) can match it.
        s = REPH.replace(s) { it.groupValues[1] + it.groupValues[2] + "Z" }
        s = I_SIGN.replace(s) { m ->
            val parts = PART.findAll(m.groupValues[1]).map { it.value }.toList()
            var cut = 0
            for (k in 0 until parts.size - 1) if (parts[k] !in ChanakyaTable.GLYPHS) cut = k + 1
            parts.subList(0, cut).joinToString("") + "ि" + parts.subList(cut, parts.size).joinToString("")
        }
        return LegacyMatch.longest(s, ChanakyaTable.GLYPHS, LONGEST).replace(ROUND_RA_MARK, 'ª')
    }
}
