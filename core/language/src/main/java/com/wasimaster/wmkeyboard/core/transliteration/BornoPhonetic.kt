package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Borno Phonetic: the phonetic keymap Codepotro publishes for its Borno
 * keyboard, read by rules of our own. The key table follows the published map;
 * nothing here comes from Borno's parser, whose source is GPL. Which consonants
 * join is ordinary Bangla spelling, listed in [JOINS].
 *
 * It differs from Avro in a few keys people notice: `c` is চ and `ch` is ছ, `z`
 * is য, `w` after a consonant is ব-ফলা, `kkh` is ক্ষ, `gg` is জ্ঞ, and `A` or `xx`
 * is চন্দ্রবিন্দু.
 *
 * Letters not named in either case read as their lowercase key, the way the map
 * marks most keys "case insensitive": `K` is ক, but `T` and `t` differ.
 */
object BornoPhonetic {

    private const val HASANT = "্"
    private const val ZWNJ = "‌"
    private const val ZWJ = "‍"

    private class Vowel(val letter: String, val sign: String)

    private val VOWELS = mapOf(
        "o" to Vowel("অ", ""),
        "a" to Vowel("আ", "া"),
        "i" to Vowel("ই", "ি"),
        "I" to Vowel("ঈ", "ী"),
        "ee" to Vowel("ঈ", "ী"),
        "u" to Vowel("উ", "ু"),
        "oo" to Vowel("উ", "ু"),
        "U" to Vowel("ঊ", "ূ"),
        "rri" to Vowel("ঋ", "ৃ"),
        "e" to Vowel("এ", "ে"),
        "OI" to Vowel("ঐ", "ৈ"),
        "O" to Vowel("ও", "ো"),
        "OU" to Vowel("ঔ", "ৌ"),
    )

    private val CONSONANTS = mapOf(
        "k" to "ক", "kh" to "খ", "g" to "গ", "gh" to "ঘ", "Ng" to "ঙ",
        "c" to "চ", "ch" to "ছ", "j" to "জ", "jh" to "ঝ", "NG" to "ঞ",
        "T" to "ট", "Th" to "ঠ", "D" to "ড", "Dh" to "ঢ", "N" to "ণ",
        "t" to "ত", "th" to "থ", "d" to "দ", "dh" to "ধ", "n" to "ন",
        "p" to "প", "ph" to "ফ", "f" to "ফ", "b" to "ব", "bh" to "ভ", "v" to "ভ",
        "m" to "ম", "z" to "য", "r" to "র", "l" to "ল",
        "sh" to "শ", "S" to "শ", "Sh" to "ষ", "SH" to "ষ", "s" to "স", "h" to "হ",
        "R" to "ড়", "Rh" to "ঢ়", "y" to "য়", "Y" to "য়",
        "kkh" to "ক্ষ", "gg" to "জ্ঞ",
    )

    /** Written as they are, with no consonant joining on after them. */
    private val MARKS = mapOf(
        "ng" to "ং",
        "X" to "ৎ", "TH" to "ৎ", "t``" to "ৎ",
        "::" to "ঃ", "ff" to "ঃ", "HH" to "ঃ",
        "^" to "ঁ", "xx" to "ঁ", "A" to "ঁ",
    )

    /** The hasant, and the joiners that shape the conjunct it makes. */
    private val BREAKS = mapOf(",," to HASANT, ",." to HASANT + ZWNJ, ",.." to HASANT + ZWJ)

    /** The longest key any table holds. */
    private const val LONGEST_KEY = 3

    /**
     * Which consonants take which after them with no hasant typed, so `nd` is
     * ন্দ but `lp` next to a pair Bangla doesn't write stays two letters. The
     * three phalas and the reph are rules of their own in [transliterate].
     */
    private val JOINS: Map<String, String> = mapOf(
        "ক" to "কটতনমলষস",
        "গ" to "গণধনবমল",
        "ঘ" to "ন",
        "ঙ" to "কখগঘ",
        "চ" to "চছঞ",
        "জ" to "জঝঞ",
        "ঞ" to "চছজঝ",
        "ট" to "ট",
        "ড" to "ড",
        "ণ" to "টঠডঢণম",
        "ত" to "তথনম",
        "দ" to "গঘদধবভম",
        "ধ" to "নম",
        "ন" to "টঠডতথদধনমস",
        "প" to "টতনপলস",
        "ফ" to "ল",
        "ব" to "জদধবল",
        "ভ" to "ল",
        "ম" to "নপফবভমল",
        "ল" to "কগটডপফবমল",
        "শ" to "চছনমল",
        "ষ" to "কটঠণপফম",
        "স" to "কখটতথনপফমল",
        "হ" to "ণনমল",
    )

    /** Consonants that never carry a phala: they are written after a vowel sound. */
    private const val NO_PHALA = "ড়ঢ়য়"

    /** One romanized word (or free text) in Bangla. Anything no table knows stays as it is. */
    fun transliterate(input: String): String {
        val out = StringBuilder(input.length * 2)
        // The consonant the next key could join or sign, with no vowel after it yet.
        var open: String? = null
        var i = 0
        while (i < input.length) {
            val token = tokenAt(input, i)
            if (token == null) {
                out.append(input[i])
                open = null
                i++
                continue
            }
            i += token.length
            val key = token.key
            val vowel = VOWELS[key]
            val consonant = CONSONANTS[key]
            when {
                vowel != null -> {
                    out.append(if (open != null) vowel.sign else vowel.letter)
                    open = null
                }
                key == "w" -> {
                    if (open != null) out.append(HASANT).append("ব") else out.append("ও")
                    open = null
                }
                consonant != null -> {
                    val last = open
                    if (last != null && joins(last, key, consonant)) out.append(HASANT)
                    out.append(consonant)
                    open = consonant.takeLast(1)
                }
                else -> {
                    out.append(BREAKS[key] ?: MARKS.getValue(key))
                    open = null
                }
            }
        }
        return out.toString()
    }

    private fun joins(last: String, key: String, consonant: String): Boolean = when {
        // র in front of a consonant is the reph, and any consonant takes it.
        last == "র" -> consonant.first() != 'র' && consonant.takeLast(1) !in NO_PHALA
        key == "r" || key == "z" -> last !in NO_PHALA
        else -> JOINS[last]?.contains(consonant.first()) == true
    }

    private class Token(val key: String, val length: Int)

    /**
     * The longest key at [at]: as typed, and only then with its capitals folded,
     * so `Sh` stays ষ while `KH` still reads as `kh`.
     */
    private fun tokenAt(input: String, at: Int): Token? {
        for (length in minOf(LONGEST_KEY, input.length - at) downTo 1) {
            val typed = input.substring(at, at + length)
            if (known(typed)) return Token(typed, length)
            val folded = typed.lowercase()
            if (folded != typed && known(folded)) return Token(folded, length)
        }
        return null
    }

    private fun known(key: String): Boolean =
        key == "w" || key in VOWELS || key in CONSONANTS || key in MARKS || key in BREAKS
}
