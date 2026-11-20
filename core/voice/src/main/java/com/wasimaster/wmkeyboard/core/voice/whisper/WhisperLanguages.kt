package com.wasimaster.wmkeyboard.core.voice.whisper

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry

/**
 * Whisper's own language table: the 100 codes its multilingual tokenizer knows,
 * in tokenizer order, plus the bridge to this keyboard's [LanguageRegistry] ids.
 * The last, Cantonese, came with large-v3's vocabulary; models before it have
 * 99, so [CANTONESE] is told apart where a model is asked for it.
 *
 * The order matters — a language's `<|xx|>` token id is [FIRST_TOKEN] plus its
 * index here, and that id is what the `serving_transcribe_lang` graphs take as
 * their `lang_token` input to force one language instead of auto-detecting.
 * Verified against the `.tokens` files DocWolle ships next to the grouped
 * models (Maltese 50343, Luxembourgish 50345).
 *
 * Codes are BCP-47-ish and mostly identical to our [LanguageRegistry] ids, so
 * only the handful that disagree are listed in [OVERRIDES].
 */
object WhisperLanguages {

    /** Token id of `<|en|>`, the first language token in the multilingual vocab. */
    const val FIRST_TOKEN = 50259

    /** Whisper code of Cantonese, the one language only the large-v3 vocabulary has (#1412). */
    const val CANTONESE = "yue"

    /** Whisper's 100 languages in tokenizer order, `code to English name`. */
    private val table: List<Pair<String, String>> = listOf(
        "en" to "English", "zh" to "Chinese", "de" to "German", "es" to "Spanish",
        "ru" to "Russian", "ko" to "Korean", "fr" to "French", "ja" to "Japanese",
        "pt" to "Portuguese", "tr" to "Turkish", "pl" to "Polish", "ca" to "Catalan",
        "nl" to "Dutch", "ar" to "Arabic", "sv" to "Swedish", "it" to "Italian",
        "id" to "Indonesian", "hi" to "Hindi", "fi" to "Finnish", "vi" to "Vietnamese",
        "he" to "Hebrew", "uk" to "Ukrainian", "el" to "Greek", "ms" to "Malay",
        "cs" to "Czech", "ro" to "Romanian", "da" to "Danish", "hu" to "Hungarian",
        "ta" to "Tamil", "no" to "Norwegian", "th" to "Thai", "ur" to "Urdu",
        "hr" to "Croatian", "bg" to "Bulgarian", "lt" to "Lithuanian", "la" to "Latin",
        "mi" to "Maori", "ml" to "Malayalam", "cy" to "Welsh", "sk" to "Slovak",
        "te" to "Telugu", "fa" to "Persian", "lv" to "Latvian", "bn" to "Bangla",
        "sr" to "Serbian", "az" to "Azerbaijani", "sl" to "Slovenian", "kn" to "Kannada",
        "et" to "Estonian", "mk" to "Macedonian", "br" to "Breton", "eu" to "Basque",
        "is" to "Icelandic", "hy" to "Armenian", "ne" to "Nepali", "mn" to "Mongolian",
        "bs" to "Bosnian", "kk" to "Kazakh", "sq" to "Albanian", "sw" to "Swahili",
        "gl" to "Galician", "mr" to "Marathi", "pa" to "Punjabi", "si" to "Sinhala",
        "km" to "Khmer", "sn" to "Shona", "yo" to "Yoruba", "so" to "Somali",
        "af" to "Afrikaans", "oc" to "Occitan", "ka" to "Georgian", "be" to "Belarusian",
        "tg" to "Tajik", "sd" to "Sindhi", "gu" to "Gujarati", "am" to "Amharic",
        "yi" to "Yiddish", "lo" to "Lao", "uz" to "Uzbek", "fo" to "Faroese",
        "ht" to "Haitian Creole", "ps" to "Pashto", "tk" to "Turkmen",
        "nn" to "Norwegian Nynorsk", "mt" to "Maltese", "sa" to "Sanskrit",
        "lb" to "Luxembourgish", "my" to "Burmese", "bo" to "Tibetan",
        "tl" to "Filipino", "mg" to "Malagasy", "as" to "Assamese", "tt" to "Tatar",
        "haw" to "Hawaiian", "ln" to "Lingala", "ha" to "Hausa", "ba" to "Bashkir",
        "jw" to "Javanese", "su" to "Sundanese",
        // Added by large-v3 as `<|yue|>` (50358); earlier vocabularies end at su.
        CANTONESE to "Cantonese",
    )

    /** Keyboard language id → Whisper code, for the ids the two tables spell differently. */
    private val OVERRIDES = mapOf(
        // Our Norwegian is Bokmål ("nb"); Whisper's plain "no" is the same thing.
        "nb" to "no",
        // Whisper uses the older ISO 639-1 code for Javanese.
        "jv" to "jw",
    )

    /** Every Whisper language code, in tokenizer order. */
    val codes: List<String> = table.map { it.first }

    private val tokens: Map<String, Int> =
        codes.withIndex().associate { (index, code) -> code to FIRST_TOKEN + index }

    private val names: Map<String, String> = table.toMap()

    /** Whisper code → keyboard language id, built from the registry so it stays in sync. */
    private val toLanguageId: Map<String, String> = buildMap {
        for (language in LanguageRegistry.all) {
            val code = OVERRIDES[language.id] ?: language.id
            if (code in tokens) putIfAbsent(code, language.id)
        }
    }

    /** The `<|xx|>` token id for a Whisper code, or null when Whisper has no such language. */
    fun tokenFor(code: String): Int? = tokens[code]

    /** The Whisper code for a keyboard language, or null when Whisper cannot do it at all. */
    fun codeForLanguage(languageId: String): String? =
        (OVERRIDES[languageId] ?: languageId).takeIf { it in tokens }

    /** The keyboard language id a Whisper code corresponds to, when this build has one. */
    fun languageIdFor(code: String): String? = toLanguageId[code]

    /**
     * A human label for a Whisper code — the keyboard's own English name where the
     * language exists here (so "Bangla" matches the Languages screen rather than
     * reading "Bengali"), else Whisper's name.
     */
    fun label(code: String): String {
        val id = toLanguageId[code]
        if (id != null) {
            LanguageRegistry.all.firstOrNull { it.id == id }?.let { return it.englishName }
        }
        return names[code] ?: code
    }

    /** [label]s for a set of codes, sorted alphabetically — for coverage lists. */
    fun labels(codes: Collection<String>): List<String> = codes.map { label(it) }.sorted()
}
