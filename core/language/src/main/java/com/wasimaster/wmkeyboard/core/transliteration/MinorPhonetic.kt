package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Phonetic typing for the languages outside the Indic engine that are only a
 * table: Arabic-script languages, Yiddish, Mongolian, Santali in Ol Chiki and
 * Manipuri in Meetei Mayek. The tables live in the Rust engine
 * (`native/wm-engine/src/minor_phonetic.rs`, which has to list the same ids);
 * this is the call into it.
 */
object MinorPhonetic {

    /** The languages written in Arabic script, which rank through [UrduPhoneticIndex]'s fold. */
    val arabic: Set<String> = setOf(
        "bcc", "bft", "bgp", "bgq", "brh", "dcc", "doi-Arab", "gju", "hnd", "hno", "ks", "mve", "phr", "ps", "scl",
        "sd", "skr",
    )

    /** Each language with the code points its letters fall in. */
    val languages: Map<String, IntRange> = buildMap {
        // Through Arabic Supplement, where Shina's and Saraiki's own letters are.
        val arabicLetters = 0x0620..0x077F
        for (id in arabic) put(id, arabicLetters)
        put("yi", 0x05D0..0x05F4)
        put("mn", 0x0400..0x052F)
        put("sat", 0x1C50..0x1C7F)
        put("mni", 0xABC0..0xABFF)
    }

    fun transliterate(languageId: String, input: String): String = EngineNative.minorPhonetic(languageId, input)

    /** Every reading the table finds plausible, the literal one first. */
    fun variants(languageId: String, input: String): List<String> =
        EngineNative.minorPhoneticVariants(languageId, input).toList()

    /** The letters of these languages that Urdu's fold has no key for, as the Urdu letters that sound like them. */
    private val URDU_LETTERS: Map<Char, String> = mapOf(
        // Pashto.
        'ټ' to "ٹ", 'ډ' to "ڈ", 'ړ' to "ڑ", 'ڼ' to "ن", 'ګ' to "گ", 'ښ' to "ش", 'ږ' to "ژ",
        'څ' to "تس", 'ځ' to "دز", 'ې' to "ے", 'ۍ' to "ی",
        // Sindhi. Its ڪ is the plain k, and its ک the aspirated one the fold ranks rather than keys.
        'ڀ' to "بھ", 'ٻ' to "ب", 'ٿ' to "تھ", 'ٺ' to "ٹھ", 'ٽ' to "ٹ", 'ڦ' to "پھ", 'ڄ' to "ج", 'ڃ' to "نی",
        'ڇ' to "چھ", 'ڌ' to "دھ", 'ڍ' to "ڈھ", 'ڏ' to "د", 'ڊ' to "ڈ", 'ڙ' to "ڑ", 'ڪ' to "ک", 'ڳ' to "گ",
        'ڱ' to "نگ", 'ڻ' to "ن",
        // Shina and Saraiki.
        'ݜ' to "ش", 'ݫ' to "ژ", 'ݘ' to "چ", 'ݨ' to "ن", 'ݙ' to "د",
    )

    /** [word] in the letters [UrduPhoneticIndex] reads. */
    fun urduSpelling(word: String): String {
        if (word.none { it in URDU_LETTERS }) return word
        return buildString(word.length + 2) {
            for (c in word) URDU_LETTERS[c]?.let(::append) ?: append(c)
        }
    }

    /** What else a typed spelling may stand for where the table reads it differently from the fold. */
    fun readings(languageId: String, input: String): List<String> = when {
        // Pashto spells ښ with an x, which the fold takes for ks.
        languageId == "ps" && 'x' in input -> listOf(input.replace("x", "sh"))
        // Kashmiri's ts is ژ, which the fold files under z.
        languageId == "ks" && "ts" in input -> listOf(input, input.replace("ts", "zh"))
        else -> listOf(input)
    }

    /** One word of [languageId] in Latin letters. */
    fun romanize(languageId: String, word: String): String =
        if (languageId in arabic) UrduRomanizer.romanizeWord(urduSpelling(word)) else AlphabetFold.key(languageId, word) ?: word
}
