package com.wasimaster.wmkeyboard.core.transliteration

import java.text.Normalizer

/**
 * Unicode Malayalam to the two ASCII layouts Kerala's print and DTP work runs
 * on (#1252): ML-TT (Revathi, Karthika and the rest of the ML series, also
 * the ML-KV display faces) and FML. ഇന്നത്തെ is `C¶s¯` in both; they differ only
 * in ണ്ട, a soft hyphen in ML and `ï` in FML.
 *
 * Ported from `js/malayalam.js` of Kalkandam (Dexters-Hub, MIT), which matches
 * the output of kuttipencil.in, the long-time home of ML/FML conversion.
 * Checked through Kalkandam's own converter back to Unicode: see [LegacyFonts].
 *
 * A syllable is written in the font's visual order: the e/ee/ai signs (`s`,
 * `t`, `ss`) and the ra sign (`{`) in front of the cluster, the ya and va
 * signs (`y`, `z`) after it, and a conjunct the font has a glyph for as that
 * glyph; any other cluster is its letters joined by the visible virama (`v`).
 */
internal object MalayalamAsciiEncoding {

    private const val VIRAMA = '്'
    private const val RAKAR = "{"
    private const val MAX_CONJUNCT = 3

    private val CONSONANTS = mapOf(
        'ക' to "I", 'ഖ' to "J", 'ഗ' to "K", 'ഘ' to "L", 'ങ' to "M",
        'ച' to "N", 'ഛ' to "O", 'ജ' to "P", 'ഝ' to "Q", 'ഞ' to "R",
        'ട' to "S", 'ഠ' to "T", 'ഡ' to "U", 'ഢ' to "V", 'ണ' to "W",
        'ത' to "X", 'ഥ' to "Y", 'ദ' to "Z", 'ധ' to "[", 'ന' to "\\",
        'പ' to "]", 'ഫ' to "^", 'ബ' to "_", 'ഭ' to "`", 'മ' to "a",
        'യ' to "b", 'ര' to "c", 'റ' to "d", 'ല' to "e", 'ള' to "f",
        'ഴ' to "g", 'വ' to "h", 'ശ' to "i", 'ഷ' to "j", 'സ' to "k", 'ഹ' to "l",
    )

    private val VOWELS = mapOf(
        'അ' to "A", 'ആ' to "B", 'ഇ' to "C", 'ഈ' to "Cu", 'ഉ' to "D", 'ഊ' to "Du", 'ഋ' to "E",
        'എ' to "F", 'ഏ' to "G", 'ഐ' to "sF", 'ഒ' to "H", 'ഓ' to "Hm", 'ഔ' to "Hu",
    )

    /** A vowel sign as what goes before the cluster and what goes after it. */
    private val SIGNS = mapOf(
        'ാ' to ("" to "m"), 'ി' to ("" to "n"), 'ീ' to ("" to "o"), 'ു' to ("" to "p"), 'ൂ' to ("" to "q"),
        'ൃ' to ("" to "r"), 'ൄ' to ("" to "r"), 'െ' to ("s" to ""), 'േ' to ("t" to ""), 'ൈ' to ("ss" to ""),
        'ൊ' to ("s" to "m"), 'ോ' to ("t" to "m"), 'ൌ' to ("s" to "u"), 'ൗ' to ("" to "u"),
    )

    private val MODIFIERS = mapOf('ം' to "w", 'ഃ' to "x", VIRAMA to "v")

    private val CHILLUS = mapOf('ൺ' to "¬", 'ൻ' to "³", 'ർ' to "À", 'ൽ' to "Â", 'ൾ' to "Ä", 'ൿ' to "Iv")

    /** ണ്ട is left out: its glyph is the one place the two layouts differ. */
    private val CONJUNCTS = mapOf(
        "ക്ക" to "¡", "ക്ല" to "¢", "ക്ഷ" to "£", "ഗ്ഗ" to "¤", "ഗ്ല" to "¥", "ങ്ക" to "¦", "ങ്ങ" to "§",
        "ച്ച" to "¨", "ഞ്ച" to "©", "ഞ്ഞ" to "ª", "ട്ട" to "«", "ണ്ണ" to "®", "ത്ത" to "¯",
        "ത്ഥ" to "°", "ദ്ദ" to "±", "ദ്ധ" to "²", "ന്ത" to "´", "ന്ദ" to "µ", "ന്ന" to "¶", "ന്മ" to "·",
        "പ്പ" to "¸", "പ്ല" to "¹", "ബ്ബ" to "º", "ബ്ല" to "»", "മ്പ" to "¼", "മ്മ" to "½", "മ്ല" to "¾",
        "യ്യ" to "¿", "ല്ല" to "Ã", "ള്ള" to "Å", "വ്വ" to "Æ", "ശ്ല" to "Ç", "ശ്ശ" to "È", "സ്ല" to "É",
        "സ്സ" to "Ê", "ഹ്ല" to "Ë", "സ്റ്റ" to "Ì", "ഡ്ഡ" to "Í", "ക്ട" to "Î", "ബ്ധ" to "Ï", "ബ്ദ" to "Ð",
        "ച്ഛ" to "Ñ", "ഹ്മ" to "Ò", "ഹ്ന" to "Ó", "ന്ധ" to "Ô", "ത്സ" to "Õ", "ജ്ജ" to "Ö", "ണ്മ" to "×",
        "സ്ഥ" to "Ø", "ന്ഥ" to "Ù", "ജ്ഞ" to "Ú", "ത്ഭ" to "Û", "ഗ്മ" to "Ü", "ശ്ച" to "Ý", "ണ്ഡ" to "Þ",
        "ത്മ" to "ß", "ക്ത" to "à", "ഗ്ന" to "á", "ന്റ" to "â", "ഷ്ട" to "ã", "റ്റ" to "ä", "ല്പ" to "å",
    )

    private const val NDA = "ണ്ട"

    /** The marks the layouts draw differently: `-` is `þ`, the curly single quotes are straight. */
    val MARKS: Set<Char> = setOf('-', '‘', '’')

    private val POST_BASE = mapOf('യ' to "y", 'വ' to "z")

    /** Old-style chillus (letter, virama, ZWJ), ന്റ written with the chillu, and the digits. */
    private fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace("ണ്‍", "ൺ").replace("ന്‍", "ൻ").replace("ര്‍", "ർ")
            .replace("ല്‍", "ൽ").replace("ള്‍", "ൾ").replace("ക്‍", "ൿ")
            .replace("ൻ്റ", "ന്റ").replace("ൻറ്", "ന്റ്")
        if (t.any { it in '൦'..'൯' }) {
            t = buildString(t.length) { for (c in t) append(if (c in '൦'..'൯') ('0' + (c - '൦')) else c) }
        }
        return t
    }

    private fun conjunct(cluster: String, fml: Boolean): String? =
        if (cluster == NDA) (if (fml) "ï" else "­") else CONJUNCTS[cluster]

    private fun segment(consonants: List<Char>, fml: Boolean): List<String> {
        val units = ArrayList<String>(consonants.size)
        var i = 0
        while (i < consonants.size) {
            var taken = false
            var length = minOf(MAX_CONJUNCT, consonants.size - i)
            while (length > 1) {
                val glyph = conjunct(consonants.subList(i, i + length).joinToString(VIRAMA.toString()), fml)
                if (glyph != null) {
                    units += glyph
                    i += length
                    taken = true
                    break
                }
                length--
            }
            if (!taken) units += CONSONANTS.getValue(consonants[i++])
        }
        return units
    }

    private fun hasConjunct(a: Char, b: Char): Boolean = "$a$VIRAMA$b" in CONJUNCTS

    /** [text] in ML-TT, or in FML when [fml]. */
    fun convert(text: String, fml: Boolean): String {
        val t = normalize(text)
        val out = StringBuilder(t.length + 8)
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (ch in CONSONANTS) {
                val cons = mutableListOf(ch)
                var j = i + 1
                while (t.getOrNull(j) == VIRAMA && t.getOrNull(j + 1)?.let { it in CONSONANTS } == true) {
                    cons += t[j + 1]
                    j += 2
                }
                var pre = ""
                var post = ""
                t.getOrNull(j)?.let { SIGNS[it] }?.let { (before, after) ->
                    pre = before
                    post = after
                    j++
                }
                var postBase = ""
                if (cons.size > 1 && cons.last() in POST_BASE && !hasConjunct(cons[cons.size - 2], cons.last())) {
                    postBase = POST_BASE.getValue(cons.removeAt(cons.size - 1))
                }
                var rakar = false
                if (cons.size > 1 && cons.last() == 'ര') {
                    cons.removeAt(cons.size - 1)
                    rakar = true
                }
                val units = segment(cons, fml)
                for (u in 0 until units.size - 1) out.append(units[u]).append('v')
                out.append(pre)
                if (rakar) out.append(RAKAR)
                out.append(units.last()).append(postBase).append(post)
                i = j
                continue
            }
            when {
                ch in VOWELS -> out.append(VOWELS.getValue(ch))
                ch in SIGNS -> SIGNS.getValue(ch).let { out.append(it.first).append(it.second) }
                ch in MODIFIERS -> out.append(MODIFIERS.getValue(ch))
                ch in CHILLUS -> out.append(CHILLUS.getValue(ch))
                ch == '‍' || ch == '‌' -> Unit
                ch == '-' -> out.append('þ')
                ch == '‘' -> out.append('"')
                ch == '’' -> out.append('\'')
                else -> out.append(ch)
            }
            i++
        }
        return out.toString()
    }
}
