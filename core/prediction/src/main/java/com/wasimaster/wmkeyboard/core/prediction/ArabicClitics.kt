package com.wasimaster.wmkeyboard.core.prediction

/**
 * Arabic writes its conjunctions, prepositions and article onto the word
 * (#1311): وبمقتنياتهم is و + ب + مقتنيات + هم. A word list holds the stems,
 * not every stem with every prefix, so a word typed with its prefix had
 * nothing to complete from. [split] takes the prefix off, the engine completes
 * the stem from the lists, and the prefix goes back on.
 *
 * Only the prefixes ("proclitics") are handled here. The endings (pronoun
 * suffixes such as هم and ها) come after the stem, so a stem typed in full is
 * already a word the lists complete, and Hunspell's rules check the rest.
 */
object ArabicClitics {

    /** The language whose words take these. */
    const val LANGUAGE = "ar"

    /**
     * The prefixes, longest first so وبال is read before و: a conjunction (و,
     * ف), then a preposition (ب, ل, ك) or the future marker (س), then the
     * article (ال). لل is ل before ال, which drops its alif.
     */
    private val PREFIXES: List<String> = listOf(
        "وبال", "فبال", "وكال", "فكال", "ولل", "فلل",
        "وال", "فال", "بال", "كال", "لل",
        "وب", "فب", "ول", "فل", "وك", "فك", "وس", "فس",
        "ال", "و", "ف", "ب", "ل", "ك",
    ).distinct().sortedByDescending { it.length }

    /** The fewest letters of stem worth completing after a prefix: one letter would list half the dictionary. */
    const val MIN_STEM = 2

    /**
     * [typed] as its prefix and the stem being typed after it, or null when
     * it starts with none or leaves fewer than [MIN_STEM] letters after it.
     * The longest prefix that leaves a stem wins.
     */
    fun split(typed: String): Pair<String, String>? {
        for (prefix in PREFIXES) {
            if (typed.length - prefix.length < MIN_STEM) continue
            if (typed.startsWith(prefix)) return prefix to typed.substring(prefix.length)
        }
        return null
    }
}
