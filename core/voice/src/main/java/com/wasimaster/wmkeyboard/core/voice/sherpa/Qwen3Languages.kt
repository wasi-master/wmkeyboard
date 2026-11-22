package com.wasimaster.wmkeyboard.core.voice.sherpa

/**
 * The 30 languages Qwen3-ASR transcribes (#1437), as its model card lists
 * them, by the keyboard's base language code, each with the English name the
 * model is told to write in ("language English" in its prompt, the names the
 * qwen-asr package uses). Filipino is `tl` on the keyboard. The 22 Chinese
 * dialects it also hears come under `zh`; Cantonese is a language of its own
 * here as on the keyboard.
 */
object Qwen3Languages {

    private val names: Map<String, String> = mapOf(
        "zh" to "Chinese",
        "en" to "English",
        "yue" to "Cantonese",
        "ar" to "Arabic",
        "de" to "German",
        "fr" to "French",
        "es" to "Spanish",
        "pt" to "Portuguese",
        "id" to "Indonesian",
        "it" to "Italian",
        "ko" to "Korean",
        "ru" to "Russian",
        "th" to "Thai",
        "vi" to "Vietnamese",
        "ja" to "Japanese",
        "tr" to "Turkish",
        "hi" to "Hindi",
        "ms" to "Malay",
        "nl" to "Dutch",
        "sv" to "Swedish",
        "da" to "Danish",
        "fi" to "Finnish",
        "pl" to "Polish",
        "cs" to "Czech",
        "tl" to "Filipino",
        "fa" to "Persian",
        "el" to "Greek",
        "hu" to "Hungarian",
        "mk" to "Macedonian",
        "ro" to "Romanian",
    )

    val codes: Set<String> = names.keys

    /**
     * The name to hold a dictation in [languageIds] to: the language's own
     * when there is one language, null for several (the model tells them
     * apart by itself) or for a language it does not list.
     */
    fun promptName(languageIds: List<String>): String? {
        val bases = languageIds.map(::base).distinct()
        return bases.singleOrNull()?.let(names::get)
    }

    private fun base(id: String): String = id.trim().lowercase().substringBefore('-').substringBefore('_')
}
