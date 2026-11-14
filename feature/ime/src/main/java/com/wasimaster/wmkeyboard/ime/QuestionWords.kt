package com.wasimaster.wmkeyboard.ime

/**
 * The words that make a sentence a question, for the languages the smart
 * punctuation model was never trained on (#1316): romanized Hindi and Urdu
 * (`kya`, `kyun`, `kab`), Bengali, Punjabi, Marathi, Nepali, Tamil and
 * Telugu. A sentence holding one of them puts `?` on the key; anything else
 * is left to the model, which reads these languages as best it can.
 *
 * A word list, not a reading of the sentence: "pata nahi kya hua" (I don't
 * know what happened) holds `kya` and is no question. It is the fallback
 * these languages had none of, and a long press still has the full stop.
 */
internal object QuestionWords {

    private val HINDUSTANI = setOf(
        "kya", "kyaa", "kyun", "kyu", "kyon", "kyoon", "kab", "kaun", "kon", "kahan", "kaha", "kidhar",
        "kaise", "kese", "kaisa", "kaisi", "kitna", "kitne", "kitni", "kis", "kisne", "kisko", "kiska", "kiski",
        "kaunsa", "konsa", "kaunsi", "konsi",
    )

    private val BY_LANGUAGE: Map<String, Set<String>> = mapOf(
        "hi_rom" to HINDUSTANI,
        "ur_rom" to HINDUSTANI,
        "bn_rom" to setOf("keno", "kokhon", "kothay", "kemon", "kon", "koto", "kar", "kake", "kivabe", "naki"),
        "pa_rom" to setOf("kyon", "kiyon", "kado", "kithe", "kiven", "kaun", "kinna", "kinne", "kehda", "kehdi"),
        "mr_rom" to setOf("kay", "ka", "kadhi", "kuthe", "kasa", "kashi", "kase", "kon", "kiti", "kevha"),
        "ne_rom" to setOf("kina", "kahile", "kaha", "kasari", "kati", "kun", "kasko", "kasle"),
        "ta_rom" to setOf("enna", "yen", "eppo", "eppodhu", "enga", "eppadi", "yaar", "evlo", "edhu", "ethana"),
        "te_rom" to setOf("enti", "endhuku", "enduku", "eppudu", "ekkada", "ela", "evaru", "entha", "edi", "ye"),
    )

    /** Whether [words], one sentence's so far, holds a question word of [languageId]; false for a language with no list. */
    fun asks(languageId: String, words: List<String>): Boolean {
        val list = BY_LANGUAGE[languageId.lowercase()] ?: return false
        return words.any { it.lowercase() in list }
    }

    /** Whether [languageId] has a list here at all. */
    fun covers(languageId: String): Boolean = languageId.lowercase() in BY_LANGUAGE
}
