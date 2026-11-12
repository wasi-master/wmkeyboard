package com.wasimaster.wmkeyboard.core.transliteration

import java.text.Normalizer

/**
 * Unicode Nepali to Preeti, the legacy font most Nepali print and office text
 * is still typed in (#1252): नेपाल is `g]kfn`.
 *
 * Ported from `src/preeti.ts` of legacy-fonts-to-unicode (osamamumtaz01, MIT),
 * whose Unicode → Preeti glyph table is in turn based on unicode-to-preeti by
 * Milkesh Shrestha (ISC). Checked against preeti2unicode by Roj Tamang and
 * Navraj Bhattarai (MIT), an independent converter the other way: see
 * [LegacyFonts] for the figures.
 *
 * The font types in visual order: ि before its cluster, the reph (`{`) after
 * the syllable, and a nasal after the vowel signs. Each syllable is put in
 * that order first, then the longest Unicode sequence the table knows is
 * replaced at each place.
 */
internal object PreetiEncoding {

    private const val P_I = ''
    private const val P_REPH = ''
    private const val P_EYELASH_RA = ''
    private const val VIRAMA = "्"

    private const val C = "[\\u0915-\\u0939\\u0958-\\u095F\\u0978-\\u097F]"
    private const val CN = "$C\\u093C?"
    private const val CLUSTER = "(?:$CN\\u094D)*$CN"
    private const val SIGN_MARKS = "[\\u0900-\\u0903\\u093A\\u093B\\u093E-\\u094C\\u094E\\u094F\\u0955-\\u0957\\u0962\\u0963]"

    /** Full consonants. */
    private val CONSONANTS = mapOf(
        "क" to "s", "ख" to "v", "ग" to "u", "घ" to "3", "ङ" to "ª",
        "च" to "r", "छ" to "5", "ज" to "h", "झ" to "´", "ञ" to "`",
        "ट" to "6", "ठ" to "7", "ड" to "8", "ढ" to "9", "ण" to "0f",
        "त" to "t", "थ" to "y", "द" to "b", "ध" to "w", "न" to "g",
        "प" to "k", "फ" to "km", "ब" to "a", "भ" to "e", "म" to "d",
        "य" to "o", "र" to "/", "ल" to "n", "व" to "j", "श" to "z", "ष" to "if", "स" to ";", "ह" to "x",
    )

    /** Consonants with no vertical bar take the « ra-phala instead of |. */
    private val ROUND = setOf("ट", "ठ", "ड", "ढ", "छ")

    private val TABLE: Map<String, String> = buildMap {
        put(P_I.toString(), "l")
        put(P_REPH.toString(), "{")
        put(P_EYELASH_RA.toString(), "¥")
        // Independent vowels
        putAll(
            listOf(
                "अ" to "c", "आ" to "cf", "इ" to "O", "ई" to "O{", "उ" to "p", "ऊ" to "pm", "ऋ" to "C",
                "ए" to "P", "ऐ" to "P]", "ओ" to "cf]", "औ" to "cf}", "ऑ" to "cf‘", "ॐ" to "ç",
            ),
        )
        putAll(CONSONANTS)
        // Half consonants with a glyph of their own
        putAll(
            listOf(
                "क्" to "S", "ख्" to "V", "ग्" to "U", "घ्" to "£", "च्" to "R", "ज्" to "H", "झ्" to "‰",
                "ञ्" to "~", "ण्" to "0", "त्" to "T", "थ्" to "Y", "ध्" to "W", "न्" to "G", "प्" to "K",
                "फ्" to "ˆ", "ब्" to "A", "भ्" to "E", "म्" to "D", "ल्" to "N", "व्" to "J", "श्" to "Z",
                "ष्" to "i", "स्" to ":", "ह्" to "X",
            ),
        )
        // Conjunct glyphs
        putAll(
            listOf(
                "क्ष" to "If", "क्ष्" to "I", "त्र" to "q", "ज्ञ" to "1", "ज्ञ्" to "¡", "श्र" to ">",
                "द्द" to "2", "द्ध" to "4", "द्य" to "B", "द्व" to "å", "द्म" to "ß", "द्घ" to "¢",
                "द्र" to "›", "ध्र" to "„", "ट्ट" to "§", "ट्ठ" to "Ý", "ड्ड" to "•", "ठ्ठ" to "¶",
                "त्त" to "Q", "क्त" to "Qm", "क्र" to "qm", "फ्र" to "k|m", "न्न" to "Ì",
                "ङ्क" to "Í", "ङ्ख" to "Î", "ङ्ग" to "Ë", "ङ्घ" to "‹", "हृ" to "Å",
                "रु" to "?", "रू" to "¿",
                "ट्र" to "6«", "ठ्र" to "7«", "ड्र" to "8«", "ढ्र" to "9«",
                "्र" to "|", "्य" to "Ø",
            ),
        )
        // Vowel signs and marks
        putAll(
            listOf(
                "ा" to "f", "ी" to "L", "ु" to "'", "ू" to "\"", "ृ" to "[", "े" to "]", "ै" to "}",
                "ो" to "f]", "ौ" to "f}", "ॉ" to "f‘", "ॅ" to "‘", "ं" to "+", "ँ" to "F", "ः" to "M",
                "्" to "\\", "़" to "Þ", "ऽ" to "˜",
            ),
        )
        // Digits: Preeti has the Devanagari digits on the shifted number keys.
        val digits = ")!@#$%^&*("
        for (d in 0..9) {
            put(('०' + d).toString(), digits[d].toString())
            put(('0' + d).toString(), digits[d].toString())
        }
        // Punctuation
        putAll(
            listOf(
                "।" to ".", "॥" to "..", "." to "=", "," to ",", "?" to "<", "/" to "÷", "(" to "-", ")" to "_",
                ";" to "Ù", ":" to "M", "!" to "Û", "%" to "Ü", "=" to "Ö", "+" to "±",
                "‘" to "…", "’" to "Ú", "“" to "æ", "”" to "Æ", "'" to "Ú", "\"" to "Æ", "-" to "–",
            ),
        )
        // A full consonant with ्र: प्र is k|, a round one takes «.
        for ((cons, keys) in CONSONANTS) {
            val key = cons + VIRAMA + "र"
            if (key !in this) put(key, keys + if (cons in ROUND) "«" else "|")
        }
    }

    private val LONGEST = TABLE.keys.maxOf { it.length }

    /** The marks outside Devanagari the font keeps glyphs on (`.` is `=`, `5` is `%`), so they move too. */
    val MARKS: Set<Char> = TABLE.keys
        .filter { it.length == 1 && it[0] !in '\u0900'..'\u097F' && it[0] !in '\uE000'..'\uE002' }
        .map { it[0] }
        .toSet()

    private val SYLLABLE = Regex("(र्(?=$CN))?($CLUSTER)($SIGN_MARKS*)")
    private val NASALS = Regex("[ंँ]")
    private val NOT_NASALS = Regex("[^ंँ]")

    fun convert(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFC).replace("\r\n", "\n").replace('\r', '\n')
        s = s.replace(Regex("[\\u200B\\u200C\\uFEFF]"), "")
        // The eyelash ra keeps a glyph of its own.
        s = s.replace("र्‍", P_EYELASH_RA.toString()).replace("‍", "")
        s = SYLLABLE.replace(s) { m ->
            val reph = m.groupValues[1].isNotEmpty()
            val cluster = m.groupValues[2]
            val marks = m.groupValues[3]
            val shortI = 'ि' in marks
            var signs = if (shortI) marks.replaceFirst("ि", "") else marks
            val nasal = signs.replace(NOT_NASALS, "")
            signs = signs.replace(NASALS, "")
            (if (shortI) P_I.toString() else "") + cluster + signs + (if (reph) P_REPH.toString() else "") + nasal
        }
        return LegacyMatch.longest(s, TABLE, LONGEST)
    }
}

/** The longest-match replacement the ported converters share. */
internal object LegacyMatch {
    fun longest(s: String, table: Map<String, String>, longest: Int): String {
        val out = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            var matched = false
            for (length in minOf(longest, s.length - i) downTo 1) {
                val glyphs = table[s.substring(i, i + length)] ?: continue
                out.append(glyphs)
                i += length
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
}
