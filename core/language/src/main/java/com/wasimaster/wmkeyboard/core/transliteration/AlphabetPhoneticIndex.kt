package com.wasimaster.wmkeyboard.core.transliteration

import java.text.Normalizer

/**
 * The key a word of Yiddish, Mongolian, Santali in Ol Chiki or Manipuri in
 * Meetei Mayek is filed under: its sounds in plain Latin letters, without what
 * a romanized spelling doesn't tell apart. ө and о are both `o`, ᱴ and ᱛ both
 * `t`, אַ and אָ both `a`, and a long vowel or doubled letter counts once.
 */
internal object AlphabetFold {

    private class Alphabet(val pieces: Map<String, String>, val isDropped: (Char) -> Boolean) {
        val longest = pieces.keys.maxOf { it.length }
    }

    private val YIDDISH = Alphabet(
        mapOf(
            // What a pointed list writes as one sound, before the points go.
            "ײַ" to "ey", "ײ" to "ey", "יי" to "ey", "ױ" to "oy", "וי" to "oy", "װ" to "v", "וו" to "v",
            "פֿ" to "f", "ף" to "f", "בֿ" to "v", "כּ" to "k", "תּ" to "t", "שׂ" to "s",
            "א" to "a", "ב" to "b", "ג" to "g", "ד" to "d", "ה" to "h", "ו" to "u", "ז" to "z", "ח" to "kh",
            "ט" to "t", "י" to "i", "כ" to "kh", "ך" to "kh", "ל" to "l", "מ" to "m", "ם" to "m", "נ" to "n",
            "ן" to "n", "ס" to "s", "ע" to "e", "פ" to "p", "צ" to "ts", "ץ" to "ts", "ק" to "k", "ר" to "r",
            "ש" to "sh", "ת" to "t",
        ),
    ) { it.code in 0x0591..0x05C7 || it == '׳' || it == '״' }

    private val MONGOLIAN = Alphabet(
        mapOf(
            "а" to "a", "б" to "b", "в" to "v", "г" to "g", "д" to "d", "е" to "e", "ё" to "yo", "ж" to "j",
            "з" to "z", "и" to "i", "й" to "i", "к" to "k", "л" to "l", "м" to "m", "н" to "n", "о" to "o",
            "ө" to "o", "п" to "p", "р" to "r", "с" to "s", "т" to "t", "у" to "u", "ү" to "u", "ф" to "f",
            "х" to "kh", "ц" to "ts", "ч" to "ch", "ш" to "sh", "щ" to "sh", "ы" to "i", "э" to "e", "ю" to "yu",
            "я" to "ya",
        ),
    ) { it == 'ъ' || it == 'ь' }

    private val OL_CHIKI = Alphabet(
        mapOf(
            "ᱚ" to "o", "ᱛ" to "t", "ᱜ" to "g", "ᱝ" to "ng", "ᱞ" to "l", "ᱟ" to "a", "ᱠ" to "k", "ᱡ" to "j",
            "ᱢ" to "m", "ᱣ" to "w", "ᱤ" to "i", "ᱥ" to "s", "ᱦ" to "h", "ᱧ" to "ny", "ᱨ" to "r", "ᱩ" to "u",
            "ᱪ" to "c", "ᱫ" to "d", "ᱬ" to "n", "ᱭ" to "y", "ᱮ" to "e", "ᱯ" to "p", "ᱰ" to "d", "ᱱ" to "n",
            "ᱲ" to "r", "ᱳ" to "o", "ᱴ" to "t", "ᱵ" to "b", "ᱶ" to "v", "ᱷ" to "h",
        ),
    ) { it.code in 0x1C78..0x1C7F }

    private val MEETEI = Alphabet(
        mapOf(
            // A vowel opening a syllable is ꯑ with the vowel's sign on it.
            "ꯑꯣ" to "o", "ꯑꯥ" to "a", "ꯑꯦ" to "e", "ꯑꯧ" to "ou", "ꯑꯩ" to "ei",
            "ꯀ" to "k", "ꯁ" to "s", "ꯂ" to "l", "ꯃ" to "m", "ꯄ" to "p", "ꯅ" to "n", "ꯆ" to "c", "ꯇ" to "t",
            "ꯈ" to "k", "ꯉ" to "ng", "ꯊ" to "t", "ꯋ" to "w", "ꯌ" to "y", "ꯍ" to "h", "ꯎ" to "u", "ꯏ" to "i",
            "ꯐ" to "p", "ꯑ" to "a", "ꯒ" to "g", "ꯓ" to "j", "ꯔ" to "r", "ꯕ" to "b", "ꯖ" to "j", "ꯗ" to "d",
            "ꯘ" to "g", "ꯙ" to "d", "ꯚ" to "b",
            // The letters that close a syllable.
            "ꯛ" to "k", "ꯜ" to "l", "ꯝ" to "m", "ꯞ" to "p", "ꯟ" to "n", "ꯠ" to "t", "ꯡ" to "ng", "ꯢ" to "i",
            "ꯣ" to "o", "ꯤ" to "i", "ꯦ" to "e", "ꯧ" to "ou", "ꯨ" to "u", "ꯩ" to "ei", "ꯪ" to "ng",
        ),
        // ꯥ is only the a a consonant already says, longer, and nobody's spelling agrees on that.
    ) { it == 'ꯥ' || it == '꯭' || it == '꯫' || it == '꯬' }

    private fun alphabetOf(languageId: String): Alphabet? = when (languageId) {
        "yi" -> YIDDISH
        "mn" -> MONGOLIAN
        "sat" -> OL_CHIKI
        "mni" -> MEETEI
        else -> null
    }

    /** [word]'s key, or null where something in it isn't a letter of [languageId]'s alphabet. */
    fun key(languageId: String, word: String): String? {
        val alphabet = alphabetOf(languageId) ?: return null
        val text = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFC).let {
            // A silent alef carries a word that opens on ו or י.
            if (languageId == "yi" && it.length > 1 && it[0] == 'א' && it[1] in "ויױײ") it.substring(1) else it
        }
        val out = StringBuilder(text.length + 2)
        var i = 0
        while (i < text.length) {
            var piece: String? = null
            var length = minOf(alphabet.longest, text.length - i)
            while (length > 0 && piece == null) {
                piece = alphabet.pieces[text.substring(i, i + length)]
                if (piece == null) length--
            }
            if (piece != null) {
                if (out.isEmpty() || !out.endsWith(piece)) out.append(piece)
                i += length
            } else if (alphabet.isDropped(text[i])) {
                i++
            } else {
                return null
            }
        }
        return out.toString().ifEmpty { null }
    }
}

/**
 * Dictionary words a romanized spelling can stand for in an alphabet language.
 * The typed spelling goes through the language's own rules first, so the key is
 * always read off that language's letters and the table is the one place that
 * says what a roman letter means here. Words sharing a key come commonest first.
 */
class AlphabetPhoneticIndex(
    private val languageId: String,
    private val table: PhoneticTable,
    private val rules: (String) -> String = { MinorPhonetic.transliterate(languageId, it) },
) : PhoneticIndex {

    override val isEmpty: Boolean get() = table.isEmpty

    override fun lookup(input: String): List<String> {
        val literal = rules(input)
        val key = AlphabetFold.key(languageId, literal) ?: return emptyList()
        return table.rows(key)
            .sortedWith(
                // A flat list ties on frequency, and then the rules' own spelling leads.
                compareByDescending<PhoneticRow> { it.frequency }
                    .thenBy { !it.word.equals(literal, ignoreCase = true) }
                    .thenBy { it.word.length }
                    .thenBy { it.word },
            )
            .map { it.word }
            .distinct()
    }

    override fun frequencyOf(word: String): Int = table.frequencyOf(word)

    override val maxFrequency: Int get() = table.maxFrequency

    companion object {
        const val FOLD = 5
        const val REVISION = 1

        /** Files [entries], at most the [boundedEntries] budget of them, by [languageId]'s alphabet. */
        internal fun file(languageId: String, builder: PhoneticTableBuilder, entries: List<Pair<String, Int>>) {
            for ((word, frequency) in boundedEntries(entries)) {
                val key = AlphabetFold.key(languageId, word) ?: continue
                builder.add(key, word, frequency, "", 0)
            }
        }
    }
}
