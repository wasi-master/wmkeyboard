package com.wasimaster.wmkeyboard.core.transliteration

import java.text.Normalizer

/**
 * Unicode to the pre-Unicode font encodings South Asian print and design work
 * still runs on (#1252), where a glyph of the script sits on a Latin code
 * point and only looks right in its own font: Hindi in Kruti Dev is `fgUnh`.
 * Bengali's Bijoy encoding is [BijoyAnsi]; Burmese's Zawgyi is [Zawgyi].
 *
 * Each was checked over thousands of real words of its language, against the
 * converter it came from and through one written separately back to Unicode.
 * Where the only converter is GPL (Nudi, Walkman Chanakya, TSCII), its glyph
 * table is used as data and the code here is the app's own; that converter
 * only reads the output back in the checks.
 *
 * Left out on the evidence: Gujarati's Terafont (the only table gives one
 * glyph to two letters, with nothing to check it against), Odia's Akruti, ORTT,
 * Aprant and Shreelipi (at best 94% of words read back), Tamil's TAB (no ூ for
 * several letters) and TAM (leaves Tamil letters unconverted), Kannada's
 * Shree-Kan and Nandi (they need Nudi's letter order), the Anandabazar Bengali
 * fonts (no anusvara in their table) and Padma's fonts under 99%.
 *
 * The tables in [LegacyFontTables] are generated:
 *
 * - **Kruti Dev 010** (also DevLys 010, which shares its layout): reversed from
 *   the Kruti Dev → Unicode table of `npm-hindi-converter` (Vineet Verma). The
 *   reverse was checked by converting every Hindi word in the app's own
 *   translation and back through that package: all 5,293 came back unchanged.
 * - **AnmolLipi / Gurbani Akhar** (Punjabi, Gurmukhi): the Unicode → ASCII
 *   routine of anvaad-js (Khalis Foundation); the 4,967 Punjabi words of the
 *   app's translation round-trip through it unchanged.
 *
 * The rest are code, since they reorder whole syllables:
 *
 * - **Walkman Chanakya** (Hindi): [ChanakyaEncoding].
 * - **Sanskrit 99** (Sanskrit and the other Devanagari languages): [Sanskrit99Encoding].
 * - **Nudi / Baraha** (Kannada): [KannadaNudiEncoding].
 * - **TSCII 1.7** (Tamil): a longest match over open-tamil's syllable table
 *   ([TsciiTable]), plus the Grantha u and uu signs (0xA4, 0xA5) that table
 *   writes only after ஜ and க்ஷ; 15,144 of 15,162 Tamil words read back through
 *   open-tamil, the other 18 being the ஸு and ஷூ its table has no entry for.
 * - **Bamini** (Tamil): [BaminiEncoding].
 * - **Preeti** (Nepali): [PreetiEncoding].
 * - **FM Abhaya** and the rest of the FM family (Sinhala): [FmAbhayaEncoding].
 * - **ML-TT** and **FML** (Malayalam): [MalayalamAsciiEncoding].
 * - The newspaper and DTP fonts with a [Font.block] (DV-TT, Shree Lipi's, Shusha,
 *   Vakil, Eenadu, Manorama, DrChatrik and the rest): [GlyphTableEncoding] over
 *   Padma's glyph tables ([GlyphFontTables]), each reading back through Padma
 *   unchanged for at least 99% of the app's own words in its language.
 */
object LegacyFonts {

    /**
     * A legacy encoding, by the id the settings store, with the [badge] its
     * strip button shows and the [title] its toast and the settings name. A
     * font with a [block] is one of [GlyphFontTables]', written by
     * [GlyphTableEncoding] for that Unicode block.
     */
    enum class Font(val id: String, val badge: String, val title: String, val block: Int = 0) {
        KRUTI_DEV("krutidev", "KD", "Kruti Dev"),
        CHANAKYA("chanakya", "WC", "Walkman Chanakya"),
        SANSKRIT_99("sanskrit99", "S9", "Sanskrit 99"),
        PREETI("preeti", "PR", "Preeti"),
        BAMINI("bamini", "BM", "Bamini"),
        TSCII("tscii", "TS", "TSCII"),
        NUDI("nudi", "NU", "Nudi"),
        ANMOL_LIPI("anmollipi", "AL", "AnmolLipi"),
        ML_TT("mltt", "ML", "ML-TT"),
        FML("fml", "FM", "FML"),
        FM_ABHAYA("fmabhaya", "FA", "FM Abhaya"),

        // The glyph-table fonts (GlyphFontTables): newspaper and DTP fonts, mostly Shree Lipi's and the dailies'.
        DV_TT("dvtt", "DV", "DV-TT Surekh / Ganesh / Yogesh", 0x900),
        DVW_TT("dvwtt", "DW", "DVW-TT Surekh / Ganesh / Yogesh", 0x900),
        SHREE_DEV_714("shreedev714", "SD", "Shree-Dev-0714", 0x900),
        SHUSHA("shusha", "SU", "Shusha", 0x900),
        SHIVAJI("shivaji", "SV", "Shivaji", 0x900),
        SHREE_PUDHARI("shreepudhari", "PU", "Shree Pudhari", 0x900),
        SHREE_PUD_77("shreepud77", "P7", "Shree-Pud-77", 0x900),
        HT_CHANAKYA("htchanakya", "HT", "HT Chanakya", 0x900),
        VAKIL("vakil", "VK", "Vakil", 0xA80),
        SHREE_TEL_900("shreetel900", "ST", "Shree-Tel-0900", 0xC00),
        TELUGU_LIPI("telugulipi", "TL", "Telugu Lipi", 0xC00),
        EENADU("eenadu", "EN", "Eenadu", 0xC00),
        VAARTHA("vaartha", "VR", "Vaartha", 0xC00),
        VIKAAS("vikaas", "VI", "Vikaas", 0xC00),
        SHREE_TAM_802("shreetam802", "SM", "Shree-Tam-0802", 0xB80),
        ELANGO("elango", "EP", "Elango Panchali", 0xB80),
        AMUDHAM("amudham", "AM", "Amudham", 0xB80),
        MANORAMA("manorama", "MN", "Manorama", 0xD00),
        MATWEB("matweb", "MW", "Matweb", 0xD00),
        KAIRALI("kairali", "KR", "Kairali", 0xD00),
        THOOLIKA("thoolika", "TH", "Thoolika", 0xD00),
        PANCHAMI("panchami", "PC", "Panchami", 0xD00),
        KALAKAUMUDI("kalakaumudi", "KK", "Kalakaumudi", 0xD00),
        DR_CHATRIK("drchatrik", "DC", "DrChatrik", 0xA00),
        ;

        companion object {
            fun of(id: String?): Font? = entries.firstOrNull { it.id == id }
        }
    }

    /** The languages written in Devanagari, which Kruti Dev covers. */
    private val DEVANAGARI_LANGUAGES = setOf("hi", "mr", "ne", "sa", "mai", "bho", "awa", "mag", "kok", "doi")

    /** The Devanagari glyph-table fonts, Marathi's DTP fonts first. */
    private val DEVANAGARI_GLYPH_FONTS = listOf(
        Font.DV_TT, Font.DVW_TT, Font.SHREE_DEV_714, Font.SHUSHA, Font.SHIVAJI,
        Font.SHREE_PUDHARI, Font.SHREE_PUD_77, Font.HT_CHANAKYA,
    )

    /** The fonts offered for [languageId], in the order the settings list them. */
    fun fontsFor(languageId: String): List<Font> = when (languageId) {
        // Preeti is what Nepali is typed in; Kruti Dev reads Nepali too.
        "ne" -> listOf(Font.PREETI, Font.KRUTI_DEV, Font.CHANAKYA, Font.SANSKRIT_99) + DEVANAGARI_GLYPH_FONTS
        // Sanskrit 99 has the conjuncts Sanskrit needs, so Sanskrit lists it first.
        "sa" -> listOf(Font.SANSKRIT_99, Font.KRUTI_DEV, Font.CHANAKYA) + DEVANAGARI_GLYPH_FONTS
        // Marathi print runs on DV-TT and Shree Lipi more than on Kruti Dev.
        "mr" -> listOf(Font.KRUTI_DEV) + DEVANAGARI_GLYPH_FONTS + listOf(Font.CHANAKYA, Font.SANSKRIT_99)
        in DEVANAGARI_LANGUAGES -> listOf(Font.KRUTI_DEV, Font.CHANAKYA, Font.SANSKRIT_99) + DEVANAGARI_GLYPH_FONTS
        "gu" -> listOf(Font.VAKIL)
        "te" -> listOf(Font.SHREE_TEL_900, Font.TELUGU_LIPI, Font.EENADU, Font.VAARTHA, Font.VIKAAS)
        "ta" -> listOf(Font.BAMINI, Font.TSCII, Font.SHREE_TAM_802, Font.ELANGO, Font.AMUDHAM)
        "kn" -> listOf(Font.NUDI)
        "pa" -> listOf(Font.ANMOL_LIPI, Font.DR_CHATRIK)
        "ml" -> listOf(
            Font.ML_TT, Font.FML, Font.MANORAMA, Font.MATWEB, Font.KAIRALI,
            Font.THOOLIKA, Font.PANCHAMI, Font.KALAKAUMUDI,
        )
        "si" -> listOf(Font.FM_ABHAYA)
        else -> emptyList()
    }

    /** Whether [text] holds anything [font] writes differently. */
    fun needsConversion(font: Font, text: CharSequence): Boolean = text.any { converts(font, it) }

    /** Whether [c] is a character [font] changes. */
    fun converts(font: Font, c: Char): Boolean = if (font.block != 0) c.code - font.block in 0..0x7F || c == '\u0964' || c == '\u0965' else when (font) {
        Font.KRUTI_DEV -> c in '\u0900'..'\u097F' || c in KRUTI_DEV_MARKS
        Font.CHANAKYA -> c in '\u0900'..'\u097F' || c in ChanakyaEncoding.MARKS
        Font.SANSKRIT_99 -> c in '\u0900'..'\u097F'
        Font.TSCII -> c in '\u0B80'..'\u0BFF' || c in TSCII_MARKS
        Font.NUDI -> c in '\u0C80'..'\u0CFF'
        Font.PREETI -> c in '\u0900'..'\u097F' || c in PreetiEncoding.MARKS
        Font.BAMINI -> c in '\u0B80'..'\u0BFF' || c in BaminiEncoding.MARKS
        Font.ANMOL_LIPI -> c in '\u0A00'..'\u0A7F' || c == '\u0964' || c == '\u0965'
        Font.ML_TT, Font.FML -> c in '\u0D00'..'\u0D7F' || c in MalayalamAsciiEncoding.MARKS
        Font.FM_ABHAYA -> c in '\u0D80'..'\u0DFF' || c in FmAbhayaEncoding.MARKS
        else -> false
    }

    /** A letter or sign of [font]'s script, or a joiner: what a word is made of. */
    fun isScriptChar(font: Font, c: Char): Boolean = c == '\u200C' || c == '\u200D' || if (font.block != 0) {
        c.code - font.block in 0..0x7F && (font.block != 0x900 || c != '\u0964' && c != '\u0965')
    } else when (font) {
        Font.KRUTI_DEV, Font.PREETI, Font.CHANAKYA, Font.SANSKRIT_99 -> c in '\u0900'..'\u0963' || c in '\u0966'..'\u097F'
        Font.BAMINI, Font.TSCII -> c in '\u0B80'..'\u0BFF'
        Font.NUDI -> c in '\u0C80'..'\u0CFF'
        Font.ANMOL_LIPI -> c in '\u0A00'..'\u0A7F'
        Font.ML_TT, Font.FML -> c in '\u0D00'..'\u0D7F'
        Font.FM_ABHAYA -> c in '\u0D80'..'\u0DFF'
        else -> false
    }

    /** Unicode [text] in [font]. Text with nothing for the font to change comes back as it was. */
    fun convert(font: Font, text: String): String {
        if (!needsConversion(font, text)) return text
        if (font.block != 0) return GlyphFontTables.encoding(font.id)?.convert(text) ?: text
        return when (font) {
            Font.KRUTI_DEV -> toKrutiDev(text)
            Font.PREETI -> PreetiEncoding.convert(text)
            Font.CHANAKYA -> ChanakyaEncoding.convert(text)
            Font.SANSKRIT_99 -> Sanskrit99Encoding.convert(text)
            Font.BAMINI -> BaminiEncoding.convert(text)
            Font.TSCII -> LegacyMatch.longest(Normalizer.normalize(text, Normalizer.Form.NFC), TsciiTable.GLYPHS, TSCII_LONGEST)
            Font.NUDI -> KannadaNudiEncoding.convert(text)
            Font.ANMOL_LIPI -> toAnmolLipi(text)
            Font.ML_TT -> MalayalamAsciiEncoding.convert(text, fml = false)
            Font.FML -> MalayalamAsciiEncoding.convert(text, fml = true)
            Font.FM_ABHAYA -> FmAbhayaEncoding.convert(text)
            else -> text
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

    // ---- TSCII ----

    private val TSCII_LONGEST by lazy { TsciiTable.GLYPHS.keys.maxOf { it.length } }

    /** The marks outside the Tamil block TSCII writes differently. */
    private val TSCII_MARKS: Set<Char> by lazy {
        TsciiTable.GLYPHS.keys.filter { it.length == 1 && it[0] !in '\u0B80'..'\u0BFF' }.map { it[0] }.toSet()
    }

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
