package com.wasimaster.wmkeyboard.core.voice.speech

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import java.io.File
import java.util.Locale

/**
 * Kokoro, hexgrad's 82M-parameter speech model (Apache-2.0), in sherpa-onnx's
 * int8 conversion of v1.0: one 132 MB download that reads English (American
 * and British voices) and Mandarin, each voice a speaker id in the same
 * model. sherpa-onnx's release archive is a `.tar.bz2`, pinned by size and
 * SHA-256; [KokoroDownloads] keeps only [KEEP] out of it (not the
 * espeak-ng data sherpa-onnx 2.0 no longer reads).
 */
object KokoroVoices {

    const val PACKAGE = "kokoro-int8-multi-lang-v1_0"
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$PACKAGE.tar.bz2"
    const val ARCHIVE_BYTES = 132_353_410L
    const val ARCHIVE_SHA256 = "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653"

    const val MODEL = "model.int8.onnx"
    const val VOICES = "voices.bin"
    const val TOKENS = "tokens.txt"
    private const val LEXICON_US = "lexicon-us-en.txt"
    private const val LEXICON_GB = "lexicon-gb-en.txt"
    private const val LEXICON_ZH = "lexicon-zh.txt"
    private val RULES_ZH = listOf("date-zh.fst", "phone-zh.fst", "number-zh.fst")

    /** The archive's files the phone keeps; the rest (espeak-ng data, a Chinese word list) is skipped. */
    val KEEP: Set<String> = setOf(MODEL, VOICES, TOKENS, LEXICON_US, LEXICON_GB, LEXICON_ZH, "LICENSE") + RULES_ZH

    /** The files that must be there for the model to open. */
    private val REQUIRED = listOf(MODEL, VOICES, TOKENS, LEXICON_US, LEXICON_GB, LEXICON_ZH)

    /** Folder under `filesDir`, beside MMS's. */
    private const val DIR = "kokoro"

    /** One speaker in the model: its id, Kokoro's name for it, and the language it reads. */
    data class Speaker(val id: Int, val name: String, val language: Language) {
        /** "Heart", "Michael": the name without its accent-and-gender prefix. */
        val label: String get() = name.substringAfter('_').replaceFirstChar { it.titlecase(Locale.ROOT) }

        /** Kokoro's names start `af_`/`am_` (American), `bf_`/`bm_` (British), `zf_`/`zm_` (Mandarin); f or m for the voice. */
        val female: Boolean get() = name.getOrNull(1) == 'f'
    }

    enum class Language(val lang: String) { EN_US("en-us"), EN_GB("en-gb"), ZH("zh") }

    /**
     * The model's speakers in voices.bin's order, from its `speaker_names`
     * metadata. Only the English and Mandarin ones: the others need espeak-ng,
     * which sherpa-onnx 2.0 no longer links.
     */
    val SPEAKERS: List<Speaker> = SPEAKER_NAMES.split(',').mapIndexedNotNull { id, name ->
        val language = when (name.take(1)) {
            "a" -> Language.EN_US
            "b" -> Language.EN_GB
            "z" -> Language.ZH
            else -> null
        }
        language?.let { Speaker(id, name, it) }
    }

    /** voices.bin's order, the model's `speaker_names` metadata (v1.0, 54 speakers). */
    private const val SPEAKER_NAMES =
        "af_alloy,af_aoede,af_bella,af_heart,af_jessica,af_kore,af_nicole,af_nova,af_river,af_sarah,af_sky," +
            "am_adam,am_echo,am_eric,am_fenrir,am_liam,am_michael,am_onyx,am_puck,am_santa," +
            "bf_alice,bf_emma,bf_isabella,bf_lily,bm_daniel,bm_fable,bm_george,bm_lewis," +
            "ef_dora,em_alex,ff_siwis,hf_alpha,hf_beta,hm_omega,hm_psi,if_sara,im_nicola," +
            "jf_alpha,jf_gongitsune,jf_nezumi,jf_tebukuro,jm_kumo,pf_dora,pm_alex,pm_santa," +
            "zf_xiaobei,zf_xiaoni,zf_xiaoxiao,zf_xiaoyi,zm_yunjian,zm_yunxi,zm_yunxia,zm_yunyang,em_santa"

    /** The speaker a language reads with until the user picks one. */
    val DEFAULTS: Map<Language, String> = mapOf(
        Language.EN_US to "af_heart",
        Language.EN_GB to "bf_emma",
        Language.ZH to "zf_xiaoxiao",
    )

    /** Which of Kokoro's languages reads [locale]: British English for en-GB and its neighbours, else American; Chinese as Mandarin. */
    fun languageFor(locale: Locale): Language? = when (locale.language) {
        "en" -> if (locale.country in BRITISH) Language.EN_GB else Language.EN_US
        "zh" -> Language.ZH
        else -> null
    }

    private val BRITISH = setOf("GB", "IE", "AU", "NZ", "ZA", "IN")

    fun dir(filesDir: File): File = File(File(filesDir, DIR), PACKAGE)

    fun file(filesDir: File, name: String): File = File(dir(filesDir), name)

    fun isDownloaded(filesDir: File): Boolean = REQUIRED.all { file(filesDir, it).isFile }

    /** The lexicons, comma-separated, English first for [language]'s spelling. */
    fun lexicons(filesDir: File, language: Language): String {
        val english = if (language == Language.EN_GB) LEXICON_GB else LEXICON_US
        return listOf(english, LEXICON_ZH).joinToString(",") { file(filesDir, it).path }
    }

    fun ruleFsts(filesDir: File): String =
        RULES_ZH.map { file(filesDir, it) }.filter { it.isFile }.joinToString(",") { it.path }

    fun bytesOnDisk(filesDir: File): Long = FileDownloader.bytesUnder(File(filesDir, DIR))

    /** Every Kokoro file, for the storage screen. */
    fun rootDir(filesDir: File): File = File(filesDir, DIR)

    /** The speaker the user picked for [language], kept beside the model; the default until then. */
    fun chosen(filesDir: File, language: Language): Speaker {
        val picked = runCatching { File(dir(filesDir), "voice-${language.lang}.txt").readText().trim() }.getOrNull()
        val name = picked?.takeIf { n -> SPEAKERS.any { it.name == n && it.language == language } }
            ?: DEFAULTS.getValue(language)
        return SPEAKERS.first { it.name == name }
    }

    fun choose(filesDir: File, speaker: Speaker) {
        val d = dir(filesDir)
        if (!d.isDirectory) return
        File(d, "voice-${speaker.language.lang}.txt").writeText(speaker.name)
    }
}
