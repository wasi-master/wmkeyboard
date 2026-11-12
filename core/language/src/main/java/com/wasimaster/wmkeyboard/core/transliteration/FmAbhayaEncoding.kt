package com.wasimaster.wmkeyboard.core.transliteration

import java.text.Normalizer

/**
 * Unicode Sinhala to FM Abhaya, the encoding of the FM font family (FM Abhaya,
 * FM Malithi, FM Bindumathi, FM Emanee …) that Sri Lankan print, government
 * forms and design work still run on (#1252).
 *
 * Ported from `src/fmabhaya.ts` of legacy-fonts-to-unicode (osamamumtaz01,
 * MIT). Its glyph table is Pandukabhaya's `fm_abhaya.json` (akuruAI, MIT),
 * itself a cleaned-up version of the UCSC Language Technology Research
 * Laboratory converter. Checked against Pandukabhaya, which converts the
 * other way: see [LegacyFonts].
 *
 * Works a syllable at a time, `[repaya] cluster [signs]`: the kombuva (`f`)
 * goes in front of its consonant, the repaya (`_`) after the syllable, and a
 * consonant with one sign takes the joined glyph the font has for it.
 */
internal object FmAbhayaEncoding {

    private const val ZWJ = "‍"
    private const val AL = "්"
    private const val JOIN = AL + ZWJ

    private val CONS_KEY: Map<String, String> = buildMap {
        // The first key the font has for each consonant.
        val consonants = listOf(
            "l" to "ක", "L" to "ඛ", "." to "ග", ">" to "ඝ", "X" to "ඞ", "Õ" to "ඟ", "p" to "ච", "P" to "ඡ",
            "c" to "ජ", "®" to "ඣ", "[" to "ඤ", "Ø" to "ඤ", "{" to "ඥ", "×" to "ඥ", "g" to "ට", "G" to "ඨ",
            "v" to "ඩ", "V" to "ඪ", "K" to "ණ", "~" to "ඬ", ";" to "ත", ":" to "ථ", "o" to "ද", "O" to "ධ",
            "k" to "න", "|" to "ඳ", "m" to "ප", "M" to "ඵ", "n" to "බ", "N" to "භ", "u" to "ම", "U" to "ඹ",
            "h" to "ය", "r" to "ර", "," to "ල", "j" to "ව", "Y" to "ශ", "I" to "ෂ", "i" to "ස", "y" to "හ",
            "<" to "ළ", "*" to "ෆ",
        )
        for ((key, letter) in consonants) if (letter !in this) put(letter, key)
        put("ඟ", "`.")
        put("ඦ", "`P")
        put("ඣ", "®")
    }

    private val VOWEL_KEY = mapOf(
        "අ" to "w", "ආ" to "wd", "ඇ" to "we", "ඈ" to "wE", "ඉ" to "b", "ඊ" to "B", "උ" to "W", "ඌ" to "W!",
        "ඍ" to "R", "ඎ" to "RD", "ඏ" to "Ì", "ඐ" to "Ï", "එ" to "t", "ඒ" to "ta", "ඓ" to "ft", "ඔ" to "T",
        "ඕ" to "´", "ඖ" to "T!",
    )

    private val SIGN_KEY = mapOf(
        "්" to "a", "ා" to "d", "ැ" to "e", "ෑ" to "E", "ි" to "s", "ී" to "S", "ු" to "q", "ූ" to "Q", "ෘ" to "D",
        "ෲ" to "DD", "ෟ" to "!", "ං" to "x", "ඃ" to "#",
    )

    /** A consonant with one sign, drawn as one glyph or with a sign of its own shape. */
    private val LIGATURE = mapOf(
        "ඳි" to "¢", "ඳී" to "£", "දී" to "§", "ලු" to "¨", "ඳූ" to "ª", "ඣි" to "¯", "ඣී" to "°",
        "ඨි" to "À", "ඨී" to "Á", "ඡී" to "Â", "ඛ්" to "Ä", "ඛි" to "Å", "ලූ" to "Æ", "ඛී" to "Ç",
        "දි" to "È", "ච්" to "É", "ජ්" to "Ê", "රී" to "Í", "ඪි" to "Î", "ඪී" to "Ð", "චි" to "Ñ", "ථී" to "Ò",
        "ථි" to "Ó", "ජී" to "Ô", "චී" to "Ö", "ඞ්" to "Ù", "ඵී" to "Ú", "ට්" to "Ü", "ඵි" to "Ý",
        "රි" to "ß", "ටී" to "à", "ටි" to "á", "ඩ්" to "â", "ඞී" to "ã", "ඩි" to "ä", "ඬ්" to "å", "ඬි" to "ç",
        "ධ්" to "è", "ඬී" to "é", "ධි" to "ê", "ධී" to "ë", "බි" to "ì", "බ්" to "í", "බී" to "î", "ම්" to "ï",
        "ජි" to "ð", "මි" to "ñ", "ඹ්" to "ò", "මී" to "ó", "ඹි" to "ô", "ව්" to "õ", "ඹී" to "ö", "ඳු" to "÷",
        "වී" to "ù", "වි" to "ú", "ඤු" to "û", "ඤූ" to "ü", "ඡි" to "ý", "ඡ්" to "þ", "දු" to "ÿ",
        "ණී" to "Œ", "ණි" to "‚", "ළු" to "¿", "ර්" to "¾",
        "රු" to "re", "රූ" to "rE", "රැ" to "/", "රෑ" to "?",
        "කු" to "l=", "කූ" to "l+", "ගු" to ".=", "ගූ" to ".+", "තු" to ";=", "තූ" to ";+",
    )

    /** Touching pairs with a glyph of their own (consonant ්‍ consonant). */
    private val CONJUNCT = mapOf(
        "ක${JOIN}ෂ" to "CI", "ක${JOIN}ව" to "Cj", "ත${JOIN}ථ" to "†", "ත${JOIN}ව" to "…",
        "න${JOIN}ද" to "‡", "න${JOIN}ධ" to "JO", "ද${JOIN}ව" to "„", "ද${JOIN}ධ" to "Š",
        "ට${JOIN}ඨ" to "`G", "ද${JOIN}ර" to "ø",
    )
    private val HALF = mapOf("ක" to "C", "ත" to "F", "න" to "J")

    /** FM keeps . and , on ' and ", since the period and comma keys are letters. */
    private val PUNCT_KEY = mapOf(
        "." to "'", "," to "\"", "/" to "$", ")" to "&", ":" to "(", "*" to ")", "?" to "@", "%" to "]", "(" to "^",
        "=" to "}", ";" to "¦", "!" to "æ", "+" to "¬", "×" to "«", "÷" to "­", "•" to "¡", "–" to "¤",
        "⋆" to "³", "\"" to "˜", "“" to "˜", "”" to "˜", "{" to "™", "}" to "š", "‘" to "z", "’" to "Z",
    )

    /** The marks the font keeps on other keys (`.` is `'`), so they move too. */
    val MARKS: Set<Char> = PUNCT_KEY.keys.map { it[0] }.toSet()

    private fun isCons(c: Char?): Boolean = c != null && c in 'ක'..'ෆ'

    private fun isSignOrMark(c: Char): Boolean = c == '්' || c in 'ා'..'ෟ' || c == 'ෲ' ||
        c == 'ෳ' || c == 'ං' || c == 'ඃ'

    private fun clusterToFm(cons: List<String>): String {
        var out = CONS_KEY[cons[0]] ?: cons[0]
        for (i in 1 until cons.size) {
            val prev = cons[i - 1]
            val c = cons[i]
            val prevKey = CONS_KEY[prev] ?: prev
            val pair = CONJUNCT[prev + JOIN + c]
            out = when {
                pair != null && out.endsWith(prevKey) -> out.dropLast(prevKey.length) + pair
                c == "ය" -> out + "H"
                c == "ර" -> out + "%"
                HALF[prev] != null && out.endsWith(prevKey) -> out.dropLast(prevKey.length) + HALF.getValue(prev) + CONS_KEY[c]
                else -> out + "a" + ZWJ + CONS_KEY[c]
            }
        }
        return out
    }

    private fun signsToFm(signs: String): String = buildString { for (c in signs) append(SIGN_KEY[c.toString()] ?: c) }

    fun convert(text: String): String {
        // NFD splits ේ ො ෝ ෞ into the kombuva and its other sign.
        val t = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(t.length + 8)
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (t.startsWith("ශ${JOIN}රී", i)) {
                out.append('›')
                i += 5
                continue
            }
            if (!isCons(ch)) {
                val s = ch.toString()
                out.append(VOWEL_KEY[s] ?: SIGN_KEY[s] ?: PUNCT_KEY[s] ?: s)
                i++
                continue
            }
            // The repaya: ර්‍ before a consonant (ර්‍ය is ra with the yansaya instead).
            var repaya = false
            if (ch == 'ර' && t.startsWith(JOIN, i + 1) && isCons(t.getOrNull(i + 3)) && t[i + 3] != 'ය') {
                repaya = true
                i += 3
            }
            val cons = mutableListOf(t[i++].toString())
            while (t.startsWith(JOIN, i) && isCons(t.getOrNull(i + 2))) {
                cons += t[i + 2].toString()
                i += 3
            }
            val signStart = i
            while (i < t.length && isSignOrMark(t[i])) i++
            val signs = t.substring(signStart, i)

            val base = clusterToFm(cons)
            val kombu = when (signs.firstOrNull()) {
                'ෙ' -> 1
                'ෛ' -> 2
                else -> 0
            }
            val syllable = if (kombu > 0) {
                var rest = signs.substring(1)
                var tail = ""
                when {
                    rest.startsWith("ා්") -> { tail = "da"; rest = rest.substring(2) }
                    rest.startsWith("ා") -> { tail = "d"; rest = rest.substring(1) }
                    rest.startsWith("ෟ") -> { tail = "!"; rest = rest.substring(1) }
                }
                var body = base
                if (rest.startsWith("්")) {
                    rest = rest.substring(1)
                    val ligature = if (cons.size == 1) LIGATURE[cons[0] + "්"] else null
                    body = ligature ?: (base + "a")
                }
                "f".repeat(kombu) + body + tail + signsToFm(rest)
            } else {
                val ligature = if (cons.size == 1 && signs.isNotEmpty()) LIGATURE[cons[0] + signs[0]] else null
                if (ligature != null) ligature + signsToFm(signs.substring(1)) else base + signsToFm(signs)
            }
            out.append(syllable)
            if (repaya) out.append('_')
        }
        return out.toString()
    }
}
