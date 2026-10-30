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
 * concatenates onto the stem's with no glide at the seam — the vowel-initial
 * endings Avro joins with য় (মা + ের → মায়ের) are left to the candidate list.
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
}
