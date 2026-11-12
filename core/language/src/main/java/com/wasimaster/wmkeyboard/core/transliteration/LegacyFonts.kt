package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Unicode to the pre-Unicode font encodings Indian print and design work still
 * runs on (#1252), where a glyph of the script sits on a Latin code point and
 * only looks right in its own font: Hindi in Kruti Dev is `fgUnh`. Bengali's
 * Bijoy encoding is [BijoyAnsi]; Burmese's Zawgyi is [Zawgyi].
 *
 * The tables in [LegacyFontTables] are generated from MIT-licensed converters:
 *
 * - **Kruti Dev 010** (also DevLys 010, which shares its layout): reversed from
 *   the Kruti Dev → Unicode table of `npm-hindi-converter` (Vineet Verma). The
 *   reverse was checked by converting every Hindi word in the app's own
 *   translation and back through that package: all 5,293 came back unchanged.
 * - **Bamini** (Tamil): the ordered replacements of Android-TamilUtil (Jay
 *   Mayu), which converts this direction already.
 * - **AnmolLipi / Gurbani Akhar** (Punjabi, Gurmukhi): the Unicode → ASCII
 *   routine of anvaad-js (Khalis Foundation); the 4,967 Punjabi words of the
 *   app's translation round-trip through it unchanged.
 */
object LegacyFonts {

    /** A legacy encoding, by the id the settings store. */
    enum class Font(val id: String) {
        KRUTI_DEV("krutidev"),
        BAMINI("bamini"),
        ANMOL_LIPI("anmollipi"),
        ;

        companion object {
            fun of(id: String?): Font? = entries.firstOrNull { it.id == id }
        }
    }

    /** The languages written in Devanagari, which Kruti Dev covers. */
    private val DEVANAGARI_LANGUAGES = setOf("hi", "mr", "ne", "sa", "mai", "bho", "awa", "mag", "kok", "doi")

    /** The fonts offered for [languageId], in the order the settings list them. */
    fun fontsFor(languageId: String): List<Font> = when (languageId) {
        in DEVANAGARI_LANGUAGES -> listOf(Font.KRUTI_DEV)
        "ta" -> listOf(Font.BAMINI)
        "pa" -> listOf(Font.ANMOL_LIPI)
        else -> emptyList()
    }

    /** Whether [text] holds anything [font] writes differently. */
    fun needsConversion(font: Font, text: CharSequence): Boolean = text.any { converts(font, it) }

    /** Whether [c] is a character [font] changes. */
    fun converts(font: Font, c: Char): Boolean = when (font) {
        Font.KRUTI_DEV -> c in '\u0900'..'\u097F' || c in KRUTI_DEV_MARKS
        Font.BAMINI -> c in '\u0B80'..'\u0BFF' || c in BAMINI_MARKS
        Font.ANMOL_LIPI -> c in '\u0A00'..'\u0A7F' || c == '\u0964' || c == '\u0965'
    }

    /** A letter or sign of [font]'s script, or a joiner: what a word is made of. */
    fun isScriptChar(font: Font, c: Char): Boolean = c == '\u200C' || c == '\u200D' || when (font) {
        Font.KRUTI_DEV -> c in '\u0900'..'\u0963' || c in '\u0966'..'\u097F'
        Font.BAMINI -> c in '\u0B80'..'\u0BFF'
        Font.ANMOL_LIPI -> c in '\u0A00'..'\u0A7F'
    }

    /** Unicode [text] in [font]. Text with nothing for the font to change comes back as it was. */
    fun convert(font: Font, text: String): String {
        if (!needsConversion(font, text)) return text
        return when (font) {
            Font.KRUTI_DEV -> toKrutiDev(text)
            Font.BAMINI -> toBamini(text)
            Font.ANMOL_LIPI -> toAnmolLipi(text)
        }
    }

    // ---- Kruti Dev ----

    private val KRUTI_DEV_MAP: Map<String, String> = LegacyFontTables.KRUTI_DEV.toMap()
    private val KRUTI_DEV_LONGEST = LegacyFontTables.KRUTI_DEV.maxOf { it.first.length }

    /** The ASCII marks Kruti Dev keeps glyphs of its own on (`?` is घ् there), so they move too. */
    private val KRUTI_DEV_MARKS: Set<Char> = LegacyFontTables.KRUTI_DEV
        .map { it.first }
        .filter { it.length == 1 && it[0] !in '\u0900'..'\u097F' }
        .map { it[0] }
        .toSet()

    /** A consonant, with its nukta, and the stand-in for a round letter's ्र (see [toKrutiDev]). */
    private const val CONSONANT = "[\\u0915-\\u0939\\u0958-\\u095F]\\u093C?\\uE000?"

    /** र् in front of a consonant cluster: the reph, which the font draws after the syllable. */
    private val REPH = Regex("\\u0930\\u094D((?:$CONSONANT\\u094D)*$CONSONANT)([\\u093E-\\u094C\\u0901\\u0902\\u0903]*)")

    /** ि after its cluster, which the font types before it. */
    private val I_SIGN = Regex("((?:$CONSONANT\\u094D)*$CONSONANT)\\u093F")

    /** ्र under a round-bottomed letter, which takes the font's other ra stroke (`ª`). */
    private val ROUND_RA = Regex("([\\u091B\\u091F\\u0920\\u0921\\u0922])\\u094D\\u0930")

    /** The precomposed nukta letters, as the font's table spells them: letter and nukta. */
    private val NUKTA_LETTERS = mapOf(
        '\u0958' to "\u0915\u093C", '\u0959' to "\u0916\u093C", '\u095A' to "\u0917\u093C", '\u095B' to "\u091C\u093C",
        '\u095C' to "\u0921\u093C", '\u095D' to "\u0922\u093C", '\u095E' to "\u092B\u093C", '\u095F' to "\u092F\u093C",
    )

    private const val ROUND_RA_MARK = '\uE000'

    /**
     * The font types in visual order: ि before the cluster it follows, and
     * the reph (`Z`) after the syllable it sits on. Both are moved in the
     * Unicode first; then the longest Unicode sequence the table knows is
     * replaced at each place.
     */
    private fun toKrutiDev(text: String): String {
        var s = buildString(text.length) { for (c in text) append(NUKTA_LETTERS[c] ?: c) }
        s = ROUND_RA.replace(s) { it.groupValues[1] + ROUND_RA_MARK }
        s = REPH.replace(s) { it.groupValues[1] + it.groupValues[2] + "\uE001" }
        s = I_SIGN.replace(s) { "\u093F" + it.groupValues[1] }
        val out = StringBuilder(s.length + 4)
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                ROUND_RA_MARK -> { out.append('ª'); i++; continue }
                '\uE001' -> { out.append('Z'); i++; continue }
            }
            var matched = false
            for (length in minOf(KRUTI_DEV_LONGEST, s.length - i) downTo 1) {
                val glyphs = KRUTI_DEV_MAP[s.substring(i, i + length)] ?: continue
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

    // ---- Bamini ----

    private val BAMINI_MARKS: Set<Char> = LegacyFontTables.BAMINI
        .map { it.first }
        .filter { it.length == 1 && it[0] !in '\u0B80'..'\u0BFF' }
        .map { it[0] }
        .toSet()

    /** Each replacement in order, every occurrence: whole syllables first, so கொ is `nfh`. */
    private fun toBamini(text: String): String =
        LegacyFontTables.BAMINI.fold(text) { s, (from, to) -> s.replace(from, to) }

    // ---- AnmolLipi / Gurbani Akhar ----

    private const val SIHARI = '\u0A3F'
    private const val HALANT = '\u0A4D'

    /** The nukta letters the layout has keys of their own for, as letter and nukta. */
    private val GURMUKHI_NUKTA = mapOf(
        "\u0A38\u0A3C" to "S", "\u0A16\u0A3C" to "^", "\u0A17\u0A3C" to "Z",
        "\u0A1C\u0A3C" to "z", "\u0A2B\u0A3C" to "&", "\u0A32\u0A3C" to "L",
    )

    private val ABOVE_SIGNS = setOf('\u0A47', '\u0A48', '\u0A4B', '\u0A4C')

    /** The precomposed nukta letters Unicode keeps apart, spelled as letter and nukta. */
    private val GURMUKHI_PRECOMPOSED = mapOf(
        '\u0A33' to "\u0A32\u0A3C", '\u0A36' to "\u0A38\u0A3C", '\u0A59' to "\u0A16\u0A3C",
        '\u0A5A' to "\u0A17\u0A3C", '\u0A5B' to "\u0A1C\u0A3C", '\u0A5E' to "\u0A2B\u0A3C",
    )

    /**
     * anvaad-js's `ascii`, with its simplified nukta letters. Sihari (ਿ)
     * goes in front of the letter it follows, as the font draws it, and a
     * subjoined letter takes its own half form.
     */
    private fun toAnmolLipi(text: String): String {
        val s = buildString(text.length) { for (c in text) append(GURMUKHI_PRECOMPOSED[c] ?: c) }
        val map = LegacyFontTables.GURMUKHI
        val out = ArrayList<String>(s.length)
        fun before(lead: String) {
            val last = out.removeLastOrNull() ?: ""
            out += lead
            out += last
        }
        var j = 0
        while (j < s.length) {
            val current = s[j]
            val next = s.getOrNull(j + 1)
            val afterNext = s.getOrNull(j + 2)
            val pair = if (next != null) "$current$next" else null
            when {
                pair != null && pair in GURMUKHI_NUKTA -> {
                    out += GURMUKHI_NUKTA.getValue(pair)
                    j++
                }
                current == SIHARI -> before("i")
                current == HALANT -> {
                    if (afterNext == SIHARI) {
                        before("i")
                        j++
                    }
                    out += pair?.let { map[it] } ?: ""
                    if (afterNext == '\u0A41') {
                        out += "ü"
                        j++
                    } else if (afterNext == '\u0A42') {
                        out += "¨"
                        j++
                    }
                    j++
                }
                current == '\u0A51' || current == '\u0A75' -> {
                    out += map[current.toString()] ?: current.toString()
                    if (next == '\u0A41') {
                        out += "ü"
                        j++
                    } else if (next == '\u0A42') {
                        out += "¨"
                        j++
                    }
                }
                current == '\u0A28' && next == '\u0A42' && afterNext == '\u0A70' -> {
                    out += "ƒ"
                    j += 2
                }
                current == '\u0A4B' && next == '\u0A41' -> {
                    out += map.getValue("\u0A4B\u0A41")
                    j++
                }
                current == '\u0A71' && afterNext != null && afterNext in ABOVE_SIGNS -> out += "¤"
                current == '\u0A3E' && next == '\u0A02' -> {
                    out += "W"
                    j++
                }
                current == '\u0A06' && next == '\u0A02' -> {
                    out += "AW"
                    j++
                }
                current == '\u0A02' && next == '\u0A40' -> {
                    out += "ˆØI"
                    j++
                }
                current == '\u0A70' && next == '\u0A40' -> {
                    out += "µØI"
                    j++
                }
                else -> out += map[current.toString()] ?: current.toString()
            }
            j++
        }
        return out.joinToString("")
    }
}
