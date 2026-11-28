package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel

/**
 * The keyboard languages Offline Sherpa's models transcribe, by family, as
 * the base codes [com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine]
 * compares, and the tags a model told the language per stream is given.
 */
object SherpaLanguages {

    /** The streaming Zipformers and Paraformers Sherpa started with. */
    val ZIPFORMER: Set<String> = setOf("en", "bn", "ru", "ko", "fr", "de", "es", "zh", "ar", "id", "ja", "th", "vi")

    /**
     * NVIDIA's Nemotron 3.5 ASR, each language by the locale its prompt is
     * keyed by: the 32 its model card calls transcription-ready or
     * broad-coverage. The eight it lists as needing fine-tuning first
     * (Greek, Lithuanian, Latvian, Maltese, Slovenian, Hebrew, Thai, Nynorsk)
     * are left out. Norwegian Bokmål is `nb` on the keyboard.
     */
    val NEMOTRON: Map<String, String> = mapOf(
        "en" to "en-US",
        "es" to "es-ES",
        "fr" to "fr-FR",
        "it" to "it-IT",
        "pt" to "pt-BR",
        "nl" to "nl-NL",
        "de" to "de-DE",
        "tr" to "tr-TR",
        "ru" to "ru-RU",
        "ar" to "ar-AR",
        "hi" to "hi-IN",
        "ja" to "ja-JP",
        "ko" to "ko-KR",
        "vi" to "vi-VN",
        "uk" to "uk-UA",
        "pl" to "pl-PL",
        "sv" to "sv-SE",
        "cs" to "cs-CZ",
        "nb" to "nb-NO",
        "da" to "da-DK",
        "bg" to "bg-BG",
        "fi" to "fi-FI",
        "hr" to "hr-HR",
        "sk" to "sk-SK",
        "zh" to "zh-CN",
        "hu" to "hu-HU",
        "ro" to "ro-RO",
        "et" to "et-EE",
    )

    /** The regional locales Nemotron 3.5 has besides the ones [NEMOTRON] picks. */
    private val NEMOTRON_REGIONS: Set<String> = setOf("en-GB", "es-US", "fr-CA", "pt-PT")

    /**
     * DataoceanAI's Dolphin: its 40 languages (Yue Chinese is `yue` on the
     * keyboard, Filipino `tl`). The 22 Chinese dialects come under `zh`.
     */
    val DOLPHIN: Set<String> = setOf(
        "zh", "ja", "th", "ru", "ko", "id", "vi", "yue", "hi", "ur", "ms", "uz", "ar", "fa", "bn", "ta",
        "te", "ug", "gu", "my", "tl", "kk", "or", "ne", "mn", "km", "jv", "lo", "si", "ps", "pa", "kab",
        "ba", "ks", "tg", "su", "mr", "ky", "az",
    )

    /** SenseVoice, each language by the name the model is told it by. */
    val SENSE_VOICE: Map<String, String> = mapOf("zh" to "zh", "en" to "en", "ja" to "ja", "ko" to "ko", "yue" to "yue")

    /**
     * Moonshine's languages besides English, which Offline Moonshine has
     * already: one model each, under the Moonshine Community License.
     */
    val MOONSHINE: Set<String> = setOf("ar", "es", "ja", "ko", "uk", "vi", "zh")

    /**
     * Single-language models from the community: Farsi, Thai, Uzbek,
     * Cantonese, and Addis AI's Amharic. Addis AI's Tigrinya, Afaan Oromo and
     * Swahili models join once tools/speech-exports has converted them.
     */
    val SINGLE: Set<String> = setOf("fa", "th", "uz", "yue", "am")

    /** Every language some Sherpa model transcribes. */
    val codes: Set<String> = ZIPFORMER + NEMOTRON.keys + DOLPHIN + SENSE_VOICE.keys + MOONSHINE + SINGLE

    /**
     * The tag [model] is told a dictation in [languageIds] is in, or null
     * when it takes none. Several languages at once, or one it has no tag
     * for, leave it to tell them apart (`auto`). A keyboard language with a
     * region the model has a locale of its own for (`en_GB`, `pt_PT`) gets
     * that locale.
     */
    fun tagFor(model: EtModel, languageIds: List<String>): String? {
        val tags = model.languageTags ?: return null
        val id = languageIds.map { it.trim().replace('_', '-') }.distinct().singleOrNull() ?: return AUTO
        val base = id.substringBefore('-').lowercase()
        val region = id.substringAfter('-', "").uppercase()
        if (region.isNotEmpty() && tags == NEMOTRON) {
            val locale = "$base-$region"
            if (locale in NEMOTRON_REGIONS) return locale
        }
        return tags[base] ?: AUTO
    }

    /** What a model with language tags is told when it should tell the language itself. */
    const val AUTO = "auto"
}
