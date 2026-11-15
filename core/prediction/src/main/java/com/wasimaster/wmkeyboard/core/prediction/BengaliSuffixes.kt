package com.wasimaster.wmkeyboard.core.prediction

/**
 * Bengali inflectional endings the phonetic strip joins onto a known stem.
 *
 * Bengali piles classifiers and case endings onto a noun — ছেলে "boy",
 * ছেলেগুলো "the boys", ছেলেদের "of the boys" — and a word list holds only the
 * stems and whichever inflected forms somebody happened to write. So when a
 * romanized spelling is a known stem followed by one of these endings, the
 * stem's best reading is taken and the ending's own reading put back on, so
 * `chelegulo` reaches ছেলেগুলো even when that exact form is in no list, and so
 * the whole counts as a word (for ranking, context and autocorrect) rather
 * than a bare letter-for-letter transliteration.
 *
 * The same stem + ending join desktop Avro's candidate list does
 * ([PhoneticCandidates]) and Borno Phonetic does, here on the main strip for
 * Avro and Borno alike. Every ending begins with a consonant, so its reading
 * concatenates onto the stem's with no glide at the seam. Desktop Avro's
 * whole table of endings, vowel-initial ones included, is joined by
 * [joinTable], which offers rather than settles.
 */
object BengaliSuffixes {

    /**
     * The romanized endings recognised, longest first so a word ending in
     * গুলোর is not taken as a গুলো stem. Spelled with the letters Avro and
     * Borno romanize the same way, so one list serves both schemes.
     */
    private val ENDINGS: List<String> = listOf(
        "guloke", "gulor", "gulo", "gula", "guli", "derke", "der", "khana", "khani",
    )

    /** Shorter than this before an ending is a particle or a typo, not a stem. */
    private const val MIN_STEM = 3

    /**
     * The suffix-joined readings of [roman], best first: for each ending it
     * carries, [stemReading] of the part before it with [transliterate] of the
     * ending appended. [stemReading] returns a stem's best native reading when
     * the stem is a known word, or null to decline the join — which is what
     * keeps an ending off a stem that is nonsense.
     */
    fun join(
        roman: String,
        stemReading: (String) -> String?,
        transliterate: (String) -> String,
    ): List<String> {
        if (roman.length <= MIN_STEM) return emptyList()
        val out = LinkedHashSet<String>()
        for (ending in ENDINGS) {
            if (ending.length >= roman.length || !roman.endsWith(ending)) continue
            val stem = roman.substring(0, roman.length - ending.length)
            if (stem.length < MIN_STEM) continue
            val reading = stemReading(stem) ?: continue
            if (reading.isNotEmpty()) out.add(reading + transliterate(ending))
        }
        return out.toList()
    }

    /** How many of desktop Avro's endings one spelling may add to the strip. */
    private const val TABLE_JOINS = 2

    /**
     * The readings of [roman] as a known stem followed by one of desktop
     * Avro's endings ([table], romanized ending to its Bengali, the 737 of
     * ibus-avro's `suffix.json`), longest ending first and at most
     * [TABLE_JOINS] of them. Unlike [join] the ending's own Bengali is put on
     * as Avro spells it, joined the way Avro joins it ([glue]), so a
     * vowel-initial ending works too: `rajshahite` is রাজশাহী + তে where the
     * letter-for-letter reading has রাজশাহিতে, and `babaer` is বাবা + ের, বাবায়ের. [stemReading] declines a stem that is not a word, as in
     * [join]. Readings equal to [literal] are left out: they add nothing.
     */
    fun joinTable(
        roman: String,
        table: Map<String, String>,
        stemReading: (String) -> String?,
        literal: String,
    ): List<String> {
        if (table.isEmpty() || roman.length <= MIN_STEM) return emptyList()
        val lower = roman.lowercase()
        val out = LinkedHashSet<String>()
        for (split in MIN_STEM until roman.length) {
            val ending = table[lower.substring(split)] ?: continue
            val reading = stemReading(roman.substring(0, split))?.takeIf { it.isNotEmpty() } ?: continue
            val joined = glue(reading, ending)
            if (joined != literal) out.add(joined)
            if (out.size >= TABLE_JOINS) break
        }
        return out.toList()
    }

    /**
     * [stem] with [ending] after it, joined as desktop Avro joins them: a য়
     * between a vowel and a vowel sign (মা + ের → মায়ের), ৎ opened back up to ত
     * before an ending (হঠাৎ + ই → হঠাতই), and ং to ঙ (রং + এর → রঙের).
     */
    fun glue(stem: String, ending: String): String {
        val last = stem.lastOrNull() ?: return ending
        val first = ending.firstOrNull() ?: return stem
        return when {
            isVowel(last) && isVowelSign(first) -> stem + YA + ending
            last == KHANDA_TA -> stem.dropLast(1) + TA + ending
            last == ANUSVARA -> stem.dropLast(1) + NGA + ending
            else -> stem + ending
        }
    }

    private fun isVowelSign(c: Char): Boolean = c in "ািীুূৃৄেৈোৌ"

    private fun isVowel(c: Char): Boolean = isVowelSign(c) || c in "অআইঈউঊঋঌএঐওঔৡ"

    private const val YA = 'য়'
    private const val KHANDA_TA = 'ৎ'
    private const val TA = 'ত'
    private const val ANUSVARA = 'ং'
    private const val NGA = 'ঙ'
}
