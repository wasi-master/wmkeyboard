package com.wasimaster.wmkeyboard.core.prediction

/**
 * Arabic writes its conjunctions, prepositions and article onto the word
 * (#1311): وبمقتنياتهم is و + ب + مقتنيات + هم. A word list holds the stems,
 * not every stem with every prefix, so a word typed with its prefix had
 * nothing to complete from. [split] takes the prefix off, the engine completes
 * the stem from the lists, and the prefix goes back on.
 *
 * The endings (pronoun suffixes such as هم and ها, "enclitics") come after
 * the stem: [endings] reads a typed word as a listed stem and the start of an
 * ending, and spells the stem with each ending that could follow. Those are
 * guesses only Hunspell's rules can vouch for, so the engine offers them only
 * with the Ayaspell dictionary installed.
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

    /**
     * The pronoun endings: his, her, their (two, men, women), your (one, two,
     * men, women), my, our.
     */
    val ENCLITICS: List<String> = listOf("ه", "ها", "هم", "هما", "هن", "ك", "كم", "كما", "كن", "ي", "نا")

    private val LONGEST_ENCLITIC = ENCLITICS.maxOf { it.length }

    /**
     * One reading of a typed word as stem plus ending: [whole] is the word to
     * offer, [stem] the stem as a word list holds it (without any prefix, and
     * with its own final letter, ة or ى, where the ending changed it), and
     * [typedEnding] how much of the ending was already typed.
     */
    data class Ending(val whole: String, val stem: String, val typedEnding: Int)

    /**
     * Every way [typed] reads as a stem followed by the start of a pronoun
     * ending, each ending that start could become spelled out. A stem ending
     * in ة takes the ending on ت (مدرسة, مدرستها) and one ending in ى on ا
     * (مستشفى, مستشفاه), so a typed ت or ا before the ending is tried as the
     * stem's own ة or ى as well. [stem] is tried whole and with each prefix
     * it could start with taken off, since a word may start with a prefix's
     * letter (كتاب) and the lists hold stems without their prefixes. Nothing for a stem shorter than
     * [MIN_STEM], and the ending is never the whole word.
     */
    fun endings(typed: String): List<Ending> {
        val out = ArrayList<Ending>()
        for (k in 0..minOf(LONGEST_ENCLITIC, typed.length)) {
            val head = typed.substring(0, typed.length - k)
            val tail = typed.substring(typed.length - k)
            if (head.length < MIN_STEM) continue
            val enclitics = ENCLITICS.filter { it.startsWith(tail) && it.length > tail.length || k > 0 && it == tail }
            if (enclitics.isEmpty()) continue
            val attached = attachedForm(head)
            for (stemAsTyped in listOfNotNull(head, restored(head))) {
                // كتاب is a word and also ك before تاب, وكتاب is و before
                // كتاب and وك before تاب: every reading is asked.
                val stems = listOf(stemAsTyped) + PREFIXES.filter { stemAsTyped.startsWith(it) }
                    .map { stemAsTyped.substring(it.length) }
                for (stem in stems.distinct()) {
                    if (stem.length < MIN_STEM) continue
                    for (e in enclitics) out += Ending(attached + e, stem, k)
                }
            }
        }
        return out.distinctBy { it.whole to it.stem }
    }

    /** [head] with a final ت or ا read back as the stem's own ة or ى, or null. */
    private fun restored(head: String): String? = when (head.lastOrNull()) {
        'ت' -> head.dropLast(1) + 'ة'
        'ا' -> head.dropLast(1) + 'ى'
        else -> null
    }

    /** [stem] as it is spelled with an ending after it: a final ة on ت, a final ى on ا. */
    private fun attachedForm(stem: String): String = when (stem.lastOrNull()) {
        'ة' -> stem.dropLast(1) + 'ت'
        'ى' -> stem.dropLast(1) + 'ا'
        else -> stem
    }
}
