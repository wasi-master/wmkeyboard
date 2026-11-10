package com.wasimaster.wmkeyboard.core.voice.whisper

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.voice.R
import java.io.IOException

/**
 * A Whisper failure carrying text the UI can show. [messageRes] is a string
 * resource id, so the message follows the app language instead of being frozen
 * in English inside the exception. [messageArg] is the one format argument the
 * message takes, or "" when it takes none.
 *
 * The exception's own `message` stays null on purpose: a caller that still
 * reads `message` falls back to its own generic text rather than printing a
 * resource id at the user.
 *
 * Declared here beside the catalog rather than with the engine, which every
 * caller reaches through [WhisperEngine.AVAILABLE] first.
 */
class WhisperException(
    @StringRes val messageRes: Int,
    val messageArg: String = "",
    cause: Throwable? = null,
) : IOException(null, cause)

/**
 * Whether a model is the one we point users at. [STANDARD] is everything else
 * and gets no badge. The catalog is all the same published conversions, so
 * there is nothing useful to say about the rest beyond their size and languages.
 */
enum class WhisperTier(@StringRes val badgeRes: Int?) {
    RECOMMENDED(R.string.core_voice_tier_recommended_label),
    STANDARD(null),
}

/**
 * Size class of a Whisper graph, ordered smallest to largest. Drives grouping and filters.
 *
 * [catalogName] is the size the way the model publishers spell it. It builds
 * [WhisperModel.displayName] and is never translated. [labelRes] is the same
 * word for the UI, which is translated.
 *
 * Turbo is a size class of its own rather than a flavour of Medium or Large:
 * it downloads at roughly Medium's size but transcribes at Large accuracy, so
 * filtering by size would put it in the wrong place either way.
 */
enum class WhisperSize(@StringRes val labelRes: Int, val catalogName: String) {
    TINY(R.string.core_voice_size_tiny_label, "Tiny"),
    BASE(R.string.core_voice_size_base_label, "Base"),
    SMALL(R.string.core_voice_size_small_label, "Small"),
    MEDIUM(R.string.core_voice_size_medium_label, "Medium"),
    TURBO(R.string.core_voice_size_turbo_label, "Turbo"),
    LARGE(R.string.core_voice_size_large_label, "Large"),
}

/**
 * Which runtime a model is for, which is also what its files look like.
 *
 * [TFLITE] graphs run on LiteRT with the whole generation loop traced inside
 * them, and need a companion `filters_vocab_*.bin`. [GGML] models run on
 * whisper.cpp ([WhisperCpp]) from one self-contained `.bin`, and take
 * everything Whisper itself takes: a language by name, a prompt, beam search.
 */
enum class WhisperFormat { TFLITE, GGML }

/**
 * One downloadable Whisper model. A [WhisperFormat.TFLITE] graph needs a
 * companion `filters_vocab_*.bin` (mel filterbank + BPE vocab in one binary),
 * so its entry maps to **two** files that both must land before the model
 * counts as downloaded. A [WhisperFormat.GGML] model is one file, and its
 * [vocabFile] is empty.
 */
data class WhisperModel(
    /** Stable key — storage directory name and the whisperModelId setting value. */
    val id: String,
    val displayName: String,
    val size: WhisperSize,
    val repo: String,
    val modelFile: String,
    val vocabFile: String,
    /**
     * Where [vocabFile] comes from, when that is not the repo holding the model.
     * Only the 128-band filterbank needs this: no repo publishes both it and a
     * large-v3 graph.
     */
    val vocabRepo: String = repo,
    val modelBytes: Long,
    val vocabBytes: Long,
    /**
     * The Whisper language codes this graph can transcribe. Empty means all 99 —
     * the `-transcribe-translate` models detect the language themselves.
     */
    val langCodes: Set<String> = emptySet(),
    /**
     * True for the grouped graphs that expose `serving_transcribe_lang` and take
     * a `lang_token` scalar, so the keyboard forces whichever of [langCodes] is
     * being typed in instead of letting the model guess.
     */
    val selectableLang: Boolean = false,
    /** True where a `serving_translate` signature exists (speech → English). */
    val supportsTranslate: Boolean = false,
    val tier: WhisperTier,
    /** What this model is good for, as a string resource the UI resolves. */
    @StringRes val descriptionRes: Int,
    /**
     * The one format argument [descriptionRes] takes, or "" when it takes none.
     * The UI resolves the pair together:
     * `if (descriptionArg.isEmpty()) stringResource(descriptionRes)`
     * `else stringResource(descriptionRes, descriptionArg)`.
     */
    val descriptionArg: String = "",
    /**
     * Byte sizes of model files this entry used to download and must not run.
     * A file of one of these exact sizes is an earlier, broken graph left on
     * disk from before the entry changed source: [WhisperStore] reports the
     * model as not downloaded and deletes the file, so it comes down again
     * from the current [repo] instead of silently transcribing nothing.
     */
    val retiredModelBytes: Set<Long> = emptySet(),
    val format: WhisperFormat = WhisperFormat.TFLITE,
    /** SHA-256 of [modelFile], checked once it has downloaded, when known. */
    val sha256: String? = null,
    /** The repo revision files are fetched from: a commit, so a file cannot change under its hash. */
    val revision: String = "main",
    /**
     * The language whisper.cpp is told to decode in, whatever is dictated, or
     * null to name the dictation's own. A fine-tune for one language is held
     * to it; the Hinglish models are routed for Hindi but decode as English,
     * which is how they were trained to write Hindi in Latin letters (forcing
     * `hi` brings the Devanagari back).
     */
    val decodeAs: String? = null,
    /**
     * Whether the user's own words go in as a prompt. False for a model whose
     * output is not the language the words are in: an English glossary pulls
     * the Hinglish models back to English spellings ("office mein" came out
     * as "officemen" in their converter's test).
     */
    val takesPrompt: Boolean = true,
) {
    /** Runs on whisper.cpp rather than LiteRT. */
    val ggml: Boolean get() = format == WhisperFormat.GGML

    /** Total download/footprint — used for the space preflight and progress fallback. */
    val sizeBytes: Long get() = modelBytes + vocabBytes

    @get:StringRes
    val sizeLabelRes: Int get() = size.labelRes

    /** False only for the graphs locked to one language (distilled and `.en`). */
    val multilingual: Boolean get() = langCodes.size != 1

    /** Whisper language code a single-language model is locked to; null otherwise. */
    val fixedLang: String? get() = langCodes.singleOrNull()

    /** Whether this model can transcribe [code] at all. */
    fun covers(code: String): Boolean = langCodes.isEmpty() || code in langCodes

    /** How many of [codes] this model handles — ranks catalog picks against enabled languages. */
    fun coverageOf(codes: Collection<String>): Int = codes.count { covers(it) }

    /**
     * The `lang_token` to force for a keyboard language, or null when this model
     * takes no such input or does not cover the language (then it auto-detects).
     */
    fun langTokenFor(languageId: String): Int? {
        if (!selectableLang || ggml) return null
        val code = WhisperLanguages.codeForLanguage(languageId) ?: return null
        if (code !in langCodes) return null
        return WhisperLanguages.tokenFor(code)
    }

    /**
     * The language name when this model handles exactly one language, else null.
     * The UI shows this in place of the [languageCount] plural. Language names
     * are proper nouns, so this one is not translated.
     */
    val singleLanguageName: String?
        get() = langCodes.singleOrNull()?.let { WhisperLanguages.label(it) }

    /**
     * How many languages this model transcribes. Feeds the
     * `core_voice_model_language_count` plural, which the UI shows whenever
     * [singleLanguageName] is null.
     */
    val languageCount: Int get() = if (langCodes.isEmpty()) ALL_LANGUAGES else langCodes.size

    companion object {
        /** Every language Whisper knows. An auto-detecting graph covers them all. */
        const val ALL_LANGUAGES = 99
    }
}

/**
 * Every offline Whisper graph we can actually run, from the two public Hugging
 * Face collections that publish them (no token or licence gate):
 *
 *  - `DocWolle/whisper_tflite_models` — the F-Droid Whisper app's set.
 *  - `nyadla-sys/whisper-tiny.en.tflite` — the original conversion repo, which
 *    carries the extra per-language and medium/large graphs.
 *  - `cik009/whisper` — for one file only, the 128-band `filters_vocab` the
 *    large-v3 generation needs. Neither repo above ships one.
 *
 * Three shapes exist, and the settings UI leans on the distinction:
 *  - **auto** (`-transcribe-translate`): all 99 languages, detected per clip,
 *    plus a translate-to-English task.
 *  - **grouped** (`TOP_WORLD`, `EUROPEAN_UNION`): a language *subset* baked into
 *    one graph with a `lang_token` input, so the keyboard forces the language
 *    you are typing in — more accurate than auto-detect on short clips, and far
 *    less disk than one single-language model per language.
 *  - **single-language** (`base.de`, `small.en`, …): one language, no choice.
 *
 * The large-v3 generation (`large-v3` and `turbo`) needs a 128-band mel
 * spectrogram where every earlier model needs 80. Those two pair with the
 * 128-band `filters_vocab_multilingual-v3.bin`, and [WhisperMel] takes the band
 * count from whichever filterbank came down with the model.
 *
 * Not included on purpose: `whisper-small.tflite` forces no task and duplicates
 * `small-multi`; `whisper-tiny-en.tflite` takes decoder token ids, not a
 * spectrogram; DocWolle's `whisper-tiny.en.tflite` forces the multilingual
 * prompt (`<|en|>` `<|transcribe|>` as ids 50259/50359) onto the English-only
 * tokenizer, where those ids mean something else, so it answers most clips with
 * an immediate end-of-text and dictation comes back empty (#207) — `tiny-en`
 * takes nyadla's conversion of the same name, which gets position 1 right but
 * still forces those two multilingual ids at positions 2 and 3 (seen in tiny.en
 * and base.en; small.en and medium.en come from the same script), so
 * [WhisperGraphFix] rewrites that table on first use (#440);
 * and the `-with-timestamp-` graphs emit timestamp tokens this decoder has no
 * use for.
 *
 * Order is a suggested-pick ranking, not a size ranking — the settings list
 * renders in catalog order, so the balanced default sits first.
 *
 * Only the multi-language graphs are shown unconditionally; the single-language
 * ones surface once their language is enabled. See [visibleFor].
 */
object WhisperCatalog {

    /**
     * Whether this device runs [WhisperFormat.GGML] models: a build with a
     * whisper.cpp library for its ABI. Read once, before [models], which is
     * built from it. Where it is false the catalog is exactly the LiteRT one.
     */
    val cppAvailable: Boolean = WhisperCpp.shipped

    /** The id of the model pointed at first: the whisper.cpp Small where it runs. */
    val defaultId: String get() = if (cppAvailable) "cpp-small" else "small-multi"

    /** whisper.cpp's own model repo (MIT), at the commit the hashes below were read from. */
    private const val REPO_GGML = "ggerganov/whisper.cpp"
    private const val REV_GGML = "5359861c739e955e79d9a303bcbc70fb988958b1"

    private const val REPO = "DocWolle/whisper_tflite_models"
    private const val REPO_NYADLA = "nyadla-sys/whisper-tiny.en.tflite"
    private const val REPO_CIK = "cik009/whisper"
    private const val VOCAB_MULTI = "filters_vocab_multilingual.bin"
    private const val VOCAB_EN = "filters_vocab_en.bin"

    /** The 128-band filterbank, for large-v3 and turbo only. */
    private const val VOCAB_V3 = "filters_vocab_multilingual-v3.bin"
    private const val VOCAB_MULTI_BYTES = 572_421L
    private const val VOCAB_EN_BYTES = 586_174L
    private const val VOCAB_V3_BYTES = 611_013L

    /**
     * The 40 languages baked into the `TOP_WORLD` graphs, decoded from the
     * `whisper-base.TOP_WORLD.tokens` file that ships beside them.
     */
    private val TOP_WORLD = setOf(
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl",
        "ar", "sv", "it", "id", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da",
        "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "sk", "lv", "sl", "et", "mt", "lb",
    )

    /** The 26 languages in the `EUROPEAN_UNION` graph, from its own `.tokens` file. */
    private val EUROPEAN_UNION = setOf(
        "en", "de", "es", "fr", "pt", "tr", "pl", "nl", "sv", "it", "fi", "el", "cs",
        "ro", "da", "hu", "no", "hr", "bg", "lt", "sk", "lv", "sl", "et", "mt", "lb",
    )

    /**
     * Every model there is: the catalog's, then the whisper.cpp models the
     * user imported themselves ([WhisperCustomModels]), once they are read.
     */
    val models: List<WhisperModel>
        get() = WhisperCustomModels.models.let { custom -> if (custom.isEmpty()) builtIn else builtIn + custom }

    private val builtIn: List<WhisperModel> = buildList {
        // ---- whisper.cpp first where it runs: every language by name, prompts,
        // beam search, and faster than the LiteRT graphs of the same size.
        if (cppAvailable) addAll(ggmlModels())
        if (cppAvailable) addAll(fineTunedModels())

        // ---- Grouped, language-selectable: the best fit for a multilingual keyboard.
        add(
            WhisperModel(
                id = "base-world",
                displayName = "Whisper Base (40 languages)",
                size = WhisperSize.BASE,
                repo = REPO,
                modelFile = "whisper-base.TOP_WORLD.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 107_564_368L,
                vocabBytes = VOCAB_MULTI_BYTES,
                langCodes = TOP_WORLD,
                selectableLang = true,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_base_world_body,
            ),
        )
        add(
            WhisperModel(
                id = "base-eu",
                displayName = "Whisper Base (European)",
                size = WhisperSize.BASE,
                repo = REPO,
                modelFile = "whisper-base.EUROPEAN_UNION.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 94_819_264L,
                vocabBytes = VOCAB_MULTI_BYTES,
                langCodes = EUROPEAN_UNION,
                selectableLang = true,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_base_eu_body,
            ),
        )
        add(
            WhisperModel(
                id = "small-world",
                displayName = "Whisper Small (40 languages)",
                size = WhisperSize.SMALL,
                repo = REPO,
                modelFile = "whisper-small.TOP_WORLD.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 307_408_944L,
                vocabBytes = VOCAB_MULTI_BYTES,
                langCodes = TOP_WORLD,
                selectableLang = true,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_small_world_body,
            ),
        )

        // ---- Auto-detecting, all 99 languages, translate-capable.
        add(
            WhisperModel(
                id = "base-multi",
                displayName = "Whisper Base",
                size = WhisperSize.BASE,
                repo = REPO,
                modelFile = "whisper-base-transcribe-translate.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 78_508_512L,
                vocabBytes = VOCAB_MULTI_BYTES,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_base_multi_body,
            ),
        )
        add(
            WhisperModel(
                id = "tiny-multi",
                displayName = "Whisper Tiny",
                size = WhisperSize.TINY,
                repo = REPO,
                modelFile = "whisper-tiny-transcribe-translate.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 42_103_936L,
                vocabBytes = VOCAB_MULTI_BYTES,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_tiny_multi_body,
            ),
        )
        add(
            WhisperModel(
                id = "small-multi",
                displayName = "Whisper Small",
                size = WhisperSize.SMALL,
                repo = REPO,
                modelFile = "whisper-small-transcribe-translate.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 248_684_440L,
                vocabBytes = VOCAB_MULTI_BYTES,
                supportsTranslate = true,
                tier = WhisperTier.RECOMMENDED,
                descriptionRes = R.string.core_voice_model_small_multi_body,
            ),
        )
        add(
            WhisperModel(
                id = "medium-multi",
                displayName = "Whisper Medium",
                size = WhisperSize.MEDIUM,
                repo = REPO_NYADLA,
                modelFile = "whisper-medium-transcribe-translate.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 775_637_984L,
                vocabBytes = VOCAB_MULTI_BYTES,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_medium_multi_body,
            ),
        )
        add(
            WhisperModel(
                id = "large-multi",
                displayName = "Whisper Large",
                size = WhisperSize.LARGE,
                repo = REPO_NYADLA,
                modelFile = "whisper-large-transcribe-translate.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 1_559_228_864L,
                vocabBytes = VOCAB_MULTI_BYTES,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_large_multi_body,
            ),
        )

        // ---- The large-v3 generation: 128 mel bands, so a different filterbank.
        add(
            WhisperModel(
                id = "turbo-multi",
                displayName = "Whisper Turbo",
                size = WhisperSize.TURBO,
                repo = REPO_NYADLA,
                modelFile = "whisper-turbo-transcribe-translate.tflite",
                vocabFile = VOCAB_V3,
                vocabRepo = REPO_CIK,
                modelBytes = 818_545_328L,
                vocabBytes = VOCAB_V3_BYTES,
                // large-v3-turbo dropped the translate task in training, so the
                // signature is not offered even where the graph still carries it.
                supportsTranslate = false,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_turbo_multi_body,
            ),
        )
        add(
            WhisperModel(
                id = "large-v3-multi",
                displayName = "Whisper Large v3",
                size = WhisperSize.LARGE,
                repo = REPO_NYADLA,
                modelFile = "whisper-large-v3-transcribe-translate.tflite",
                vocabFile = VOCAB_V3,
                vocabRepo = REPO_CIK,
                modelBytes = 1_559_408_000L,
                vocabBytes = VOCAB_V3_BYTES,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_large_v3_multi_body,
            ),
        )

        // ---- Single-language graphs: one language each, no detection step.
        addAll(
            singleLanguage(
                Single(
                    "tiny-en", WhisperSize.TINY, "whisper-tiny.en.tflite", "en", REPO_NYADLA, 41_507_968L,
                    // DocWolle's graph of the same name, which tiny-en downloaded
                    // before #207 and which transcribes almost nothing.
                    retired = setOf(41_486_616L),
                ),
                Single("base-en", WhisperSize.BASE, "whisper-base.en.tflite", "en", REPO_NYADLA, 77_642_600L),
                Single("small-en", WhisperSize.SMALL, "whisper-small.en.tflite", "en", REPO_NYADLA, 247_048_280L),
                Single("medium-en", WhisperSize.MEDIUM, "whisper-medium.en.tflite", "en", REPO_NYADLA, 772_442_720L),
                Single("base-de", WhisperSize.BASE, "whisper-base.de.tflite", "de", REPO, 78_448_496L),
                Single("base-es", WhisperSize.BASE, "whisper-base.es.tflite", "es", REPO, 78_448_496L),
                Single("small-es", WhisperSize.SMALL, "whisper-small.es.tflite", "es", REPO_NYADLA, 247_025_152L),
                Single("base-fr", WhisperSize.BASE, "whisper-base.fr.tflite", "fr", REPO, 78_448_496L),
                Single("base-hi", WhisperSize.BASE, "whisper-base.hi.tflite", "hi", REPO, 78_448_496L),
                Single("small-hi", WhisperSize.SMALL, "whisper-small.hi.tflite", "hi", REPO_NYADLA, 247_025_144L),
                Single("base-it", WhisperSize.BASE, "whisper-base.it.tflite", "it", REPO, 78_448_496L),
                Single("base-ja", WhisperSize.BASE, "whisper-base.ja.tflite", "ja", REPO_NYADLA, 77_619_400L),
                Single("base-pt", WhisperSize.BASE, "whisper-base.pt.tflite", "pt", REPO, 78_448_496L),
                Single("base-ru", WhisperSize.BASE, "whisper-base.ru.tflite", "ru", REPO, 78_448_496L),
                Single("small-ta", WhisperSize.SMALL, "whisper-small.ta.tflite", "ta", REPO_NYADLA, 247_025_192L),
                Single("small-te", WhisperSize.SMALL, "whisper-small.te.tflite", "te", REPO_NYADLA, 247_025_192L),
                Single("base-ur", WhisperSize.BASE, "whisper-base.ur.tflite", "ur", REPO, 78_437_408L),
                Single("small-ur", WhisperSize.SMALL, "whisper-small.ur.tflite", "ur", REPO, 248_612_928L),
                Single("base-zh", WhisperSize.BASE, "whisper-base.zh.tflite", "zh", REPO, 78_448_496L),
            ),
        )
    }.filter { model ->
        // F-Droid's build has no LiteRT, so only whisper.cpp's models can run there.
        model.ggml || WhisperEngine.AVAILABLE
    }.map { model ->
        // Beside whisper.cpp's models of the same sizes, the LiteRT graphs need
        // telling apart, and the one pointed at first is no longer theirs.
        if (!cppAvailable || model.ggml) {
            model
        } else {
            model.copy(displayName = "${model.displayName} (LiteRT)", tier = WhisperTier.STANDARD)
        }
    }

    /**
     * whisper.cpp's quantized models: 8-bit up to Small, which loses nothing
     * measurable, and 5-bit from Medium up, where the download size starts to
     * matter more. Each `.en` model is the English-only one, which is a little
     * more accurate for English than the multilingual model of its size.
     */
    private fun ggmlModels(): List<WhisperModel> {
        fun entry(
            id: String,
            size: WhisperSize,
            file: String,
            bytes: Long,
            sha256: String,
            @StringRes descriptionRes: Int,
            english: Boolean = false,
            name: String = size.catalogName,
            tier: WhisperTier = WhisperTier.STANDARD,
            translate: Boolean = !english,
        ) = WhisperModel(
            id = id,
            displayName = if (english) "Whisper $name (${WhisperLanguages.label("en")})" else "Whisper $name",
            size = size,
            repo = REPO_GGML,
            modelFile = file,
            vocabFile = "",
            modelBytes = bytes,
            vocabBytes = 0,
            langCodes = if (english) setOf("en") else emptySet(),
            // Any language can be named to a multilingual whisper.cpp model.
            selectableLang = !english,
            supportsTranslate = translate,
            tier = tier,
            descriptionRes = descriptionRes,
            descriptionArg = if (english) WhisperLanguages.label("en") else "",
            format = WhisperFormat.GGML,
            sha256 = sha256,
            revision = REV_GGML,
        )
        return listOf(
            entry(
                "cpp-small", WhisperSize.SMALL, "ggml-small-q8_0.bin", 264_464_607L,
                "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f",
                R.string.core_voice_model_cpp_small_body, tier = WhisperTier.RECOMMENDED,
            ),
            entry(
                "cpp-base", WhisperSize.BASE, "ggml-base-q8_0.bin", 81_768_585L,
                "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9",
                R.string.core_voice_model_cpp_base_body,
            ),
            entry(
                "cpp-tiny", WhisperSize.TINY, "ggml-tiny-q8_0.bin", 43_537_433L,
                "c2085835d3f50733e2ff6e4b41ae8a2b8d8110461e18821b09a15c40c42d1cca",
                R.string.core_voice_model_cpp_tiny_body,
            ),
            entry(
                "cpp-turbo", WhisperSize.TURBO, "ggml-large-v3-turbo-q5_0.bin", 574_041_195L,
                "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
                R.string.core_voice_model_cpp_turbo_body,
                // large-v3-turbo was trained for transcription alone.
                translate = false,
            ),
            entry(
                "cpp-medium", WhisperSize.MEDIUM, "ggml-medium-q5_0.bin", 539_212_467L,
                "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f",
                R.string.core_voice_model_cpp_medium_body,
            ),
            entry(
                "cpp-large-v3", WhisperSize.LARGE, "ggml-large-v3-q5_0.bin", 1_081_140_203L,
                "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1",
                R.string.core_voice_model_cpp_large_body, name = "Large v3",
            ),
            entry(
                "cpp-tiny-en", WhisperSize.TINY, "ggml-tiny.en-q8_0.bin", 43_550_795L,
                "5bc2b3860aa151a4c6e7bb095e1fcce7cf12c7b020ca08dcec0c6d018bb7dd94",
                R.string.core_voice_model_single_language_body, english = true,
            ),
            entry(
                "cpp-base-en", WhisperSize.BASE, "ggml-base.en-q8_0.bin", 81_781_811L,
                "a4d4a0768075e13cfd7e19df3ae2dbc4a68d37d36a7dad45e8410c9a34f8c87e",
                R.string.core_voice_model_single_language_body, english = true,
            ),
            entry(
                "cpp-small-en", WhisperSize.SMALL, "ggml-small.en-q8_0.bin", 264_477_561L,
                "67a179f608ea6114bd3fdb9060e762b588a3fb3bd00c4387971be4d177958067",
                R.string.core_voice_model_single_language_body, english = true,
            ),
            entry(
                "cpp-medium-en", WhisperSize.MEDIUM, "ggml-medium.en-q5_0.bin", 539_225_533L,
                "76733e26ad8fe1c7a5bf7531a9d41917b2adc0f20f2e4f5531688a8c6cd88eb0",
                R.string.core_voice_model_single_language_body, english = true,
            ),
        )
    }

    /**
     * whisper.cpp conversions of models trained further on one language
     * (#1224), each by the people who trained it or converted it, pinned to a
     * commit and checked against its hash like whisper.cpp's own. Only the
     * quantized files a phone can hold; the f16 ones of 1.6 GB (ivrit.ai's
     * Hebrew, BELLE's Chinese, the Korean and Swiss German turbos) are left
     * to the import of the user's own models. Single-language, so each shows
     * once its language is enabled and wins that language's routing once it
     * is on the phone.
     */
    private fun fineTunedModels(): List<WhisperModel> {
        fun entry(
            id: String,
            displayName: String,
            size: WhisperSize,
            repo: String,
            revision: String,
            file: String,
            bytes: Long,
            sha256: String,
            language: String,
            @StringRes descriptionRes: Int,
            descriptionArg: String = "",
            decodeAs: String = language,
            takesPrompt: Boolean = true,
        ) = WhisperModel(
            id = id,
            displayName = displayName,
            size = size,
            repo = repo,
            modelFile = file,
            vocabFile = "",
            modelBytes = bytes,
            vocabBytes = 0,
            langCodes = setOf(language),
            tier = WhisperTier.STANDARD,
            descriptionRes = descriptionRes,
            descriptionArg = descriptionArg,
            format = WhisperFormat.GGML,
            sha256 = sha256,
            revision = revision,
            decodeAs = decodeAs,
            takesPrompt = takesPrompt,
        )
        return listOf(
            // Oriserve's Hindi2Hinglish Swift (Apache 2.0), whisper-base, in
            // rohitag13's q5_1 conversion. Its larger Apex sibling is a turbo
            // that took 25.6 s a clip on a Snapdragon 8 Elite for the same text.
            entry(
                "cpp-hinglish-base", "Whisper Base (Hinglish)", WhisperSize.BASE,
                "rohitag13/whisper-hindi2hinglish-swift-GGUF", "c630ccae346b7d84a809c9f758b72b2825d14aae",
                "ggml-hinglish-swift-q5_1.bin", 59_707_625L,
                "30ba7b988bb0f7b3e3abc4b8aca1a5028cf56060d2142233d240c10f3d5b070a",
                "hi", R.string.core_voice_model_cpp_hinglish_body, decodeAs = "en", takesPrompt = false,
            ),
            // Kotoba Technologies' distilled large-v3 for Japanese (Apache 2.0):
            // a two-layer decoder, so it runs at Turbo's pace.
            entry(
                "cpp-kotoba-ja", "Kotoba-Whisper v2.0 (${WhisperLanguages.label("ja")})", WhisperSize.TURBO,
                "kotoba-tech/kotoba-whisper-v2.0-ggml", "e3a0cf6a62b95911703cfb97d819292e058f12c3",
                "ggml-kotoba-whisper-v2.0-q5_0.bin", 537_819_875L,
                "4a3b92192b5d3578ff854a5876213e2e27af0c2d357492c2d14271e82c303658",
                "ja", R.string.core_voice_model_cpp_kotoba_body,
            ),
            // The National Library of Sweden's KB-Whisper (Apache 2.0), from its own repos.
            entry(
                "cpp-kb-base-sv", "KB-Whisper Base (${WhisperLanguages.label("sv")})", WhisperSize.BASE,
                "KBLab/kb-whisper-base", "1499d2d2f0c7ed545bd6f2eec85287cf8d8c8b38",
                "ggml-model-q5_0.bin", 55_295_450L,
                "aead29b356bca8840e72a8dc2286e2d69e6702639751a1e60cb3c8eacefec546",
                "sv", R.string.core_voice_model_cpp_kb_body,
            ),
            entry(
                "cpp-kb-small-sv", "KB-Whisper Small (${WhisperLanguages.label("sv")})", WhisperSize.SMALL,
                "KBLab/kb-whisper-small", "3564d61a42fc210ceaa55a22a96dd64478959c78",
                "ggml-model-q5_0.bin", 175_209_680L,
                "6768836a51abc902e420c613153e6d418c90ea2774e913274d02ab23170225b7",
                "sv", R.string.core_voice_model_cpp_kb_body,
            ),
            entry(
                "cpp-kb-medium-sv", "KB-Whisper Medium (${WhisperLanguages.label("sv")})", WhisperSize.MEDIUM,
                "KBLab/kb-whisper-medium", "0abe10b9d7f75d0902656e5c06c5c4d549604dc5",
                "ggml-model-q5_0.bin", 539_212_484L,
                "7f8762e0ade9e0073674c0d5acae942a0b1ea98add9baa008ee89c94eaba43d0",
                "sv", R.string.core_voice_model_cpp_kb_body,
            ),
            // primeline's German turbo (Apache 2.0) in cstr's q5_0 conversion.
            entry(
                "cpp-turbo-de", "Whisper Turbo (${WhisperLanguages.label("de")})", WhisperSize.TURBO,
                "cstr/whisper-large-v3-turbo-german-ggml", "d9431a78952938533dc46e75395a9b0b7237fa69",
                "ggml-model-q5_0.bin", 574_041_195L,
                "15e92e3db0993c52fffa781513eec9253475331c1be808f8fb409285c9d9d030",
                "de", R.string.core_voice_model_cpp_fine_tune_body, "primeline",
            ),
            // LocalAI's Italian turbo (MIT). The same name and size as the German
            // one above: the hash tells an imported file apart.
            entry(
                "cpp-turbo-it", "Whisper Turbo (${WhisperLanguages.label("it")})", WhisperSize.TURBO,
                "LocalAI-io/whisper-large-v3-turbo-it-ggml", "c9aff1689a2edd95743e2ba2df87ece57c97ff17",
                "ggml-model-q5_0.bin", 574_041_195L,
                "55b0dcac9cd64d51b83a30ee5297bdbef11c379da5e13c0f17807c1522dafcc8",
                "it", R.string.core_voice_model_cpp_fine_tune_body, "LocalAI",
            ),
            // givia's Persian turbo (CC BY 4.0, credited in its description) in
            // hasanghaffari93's q5_0 conversion.
            entry(
                "cpp-turbo-fa", "Whisper Turbo (${WhisperLanguages.label("fa")})", WhisperSize.TURBO,
                "hasanghaffari93/whisper-large-v3-turbo-persian-ggml-q5_0", "d1ee8515dc87215becad31f80c21a1cca6430906",
                "ggml-persian-v3-turbo-q5_0.bin", 574_041_195L,
                "d832fe5cd81c756bc1962765cff4a33cacde00a7701809837875245870b6d5a8",
                "fa", R.string.core_voice_model_cpp_fine_tune_body, "givia",
            ),
        )
    }

    /** Every language at least one single-language graph exists for. */
    val singleLanguageCodes: Set<String> =
        builtIn.mapNotNullTo(mutableSetOf()) { it.fixedLang }

    private class Single(
        val id: String,
        val size: WhisperSize,
        val file: String,
        val code: String,
        val repo: String,
        val bytes: Long,
        val retired: Set<Long> = emptySet(),
    )

    /** Single-language graphs share one shape — spell out only what differs. */
    private fun singleLanguage(vararg entries: Single): List<WhisperModel> =
        entries.map { entry ->
            // The English graphs are built on the English-only tokenizer, so they
            // need the other vocab binary; every other one is a distilled
            // multilingual graph with its language forced.
            val english = entry.code == "en"
            val name = WhisperLanguages.label(entry.code)
            WhisperModel(
                id = entry.id,
                displayName = "Whisper ${entry.size.catalogName} ($name)",
                size = entry.size,
                repo = entry.repo,
                modelFile = entry.file,
                vocabFile = if (english) VOCAB_EN else VOCAB_MULTI,
                modelBytes = entry.bytes,
                vocabBytes = if (english) VOCAB_EN_BYTES else VOCAB_MULTI_BYTES,
                langCodes = setOf(entry.code),
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_single_language_body,
                descriptionArg = name,
                retiredModelBytes = entry.retired,
            )
        }

    init {
        check(builtIn.map { it.id }.toSet().size == builtIn.size) { "catalog ids must be unique" }
    }

    fun byId(id: String): WhisperModel? = models.firstOrNull { it.id == id }

    /**
     * The catalog as it should be offered to someone whose enabled languages are
     * [codes]: every multi-language graph, plus the single-language graphs for
     * languages they actually type in. Showing a German-only model to someone who
     * has never enabled German is noise — and downloading one would be a wasted
     * 78 MB, since nothing would ever route dictation to it.
     */
    fun visibleFor(codes: Set<String>): List<WhisperModel> =
        models.filter { model -> model.fixedLang?.let { it in codes } ?: true }

    /** The single-language graphs for one language, smallest first. */
    fun singleLanguageFor(code: String): List<WhisperModel> =
        models.filter { it.fixedLang == code }.sortedBy { it.sizeBytes }

    /**
     * True when no model in the catalog can be *told* to transcribe [code]: there
     * is no graph built for it alone, and no grouped graph lists it. Every option
     * left has to work out the language from the clip itself.
     *
     * This matters because auto-detection is the one Whisper step that fails
     * silently and completely. A language it confuses with a better-represented
     * neighbour — Bangla read as Hindi is the standard example — comes back as
     * fluent text in the wrong language, with no error anywhere. The graphs are
     * published conversions and the language list is compiled into each one (a
     * branch per language token), so this is not something the keyboard can fix
     * by asking differently; all it can do is say so, point at the models whose
     * detection is best, and repair the script afterwards where the two
     * languages happen to be script-convertible ([WhisperScript]).
     */
    fun autoDetectOnly(code: String): Boolean =
        models.none { it.fixedLang == code || (it.selectableLang && it.covers(code)) }

    /**
     * Ranks [candidates] by how well each transcribes [code], best first. Used
     * both to suggest downloads and to pick which downloaded model handles a
     * language when the user has not said explicitly.
     *
     * A graph built for exactly this language wins, then one that can be *told*
     * the language, then a plain auto-detecting one; within a rank, more
     * parameters means better transcription, so bigger wins. Models that cannot
     * do the language at all are dropped.
     */
    fun rankedFor(code: String, candidates: List<WhisperModel> = models): List<WhisperModel> =
        candidates.filter { it.covers(code) }.sortedWith(
            compareBy<WhisperModel> {
                when {
                    it.fixedLang == code -> 0
                    it.selectableLang -> 1
                    else -> 2
                }
            }.thenBy { !it.ggml }.thenByDescending { it.sizeBytes },
        )

    /**
     * The models worth suggesting for an enabled-language set, best first: the
     * grouped or single-language graphs that actually cover those languages,
     * falling back to the all-languages default. Capped at [limit] so the
     * settings screen stays a shortlist rather than a second full catalog.
     */
    fun recommendedFor(codes: Set<String>, limit: Int = 4): List<WhisperModel> {
        val default = byId(defaultId)
        if (codes.isEmpty()) return listOfNotNull(default)
        // whisper.cpp takes any language by name, so the shortlist is about
        // size alone: the default, the quicker one, English's own where
        // English is typed, and the most accurate one a phone can hold.
        if (cppAvailable) {
            val picks = LinkedHashSet<WhisperModel>()
            default?.let { picks += it }
            // A language with a model trained for it alone gets the smallest
            // of those. Not the Hinglish one: Hindi's own speakers mostly
            // write Devanagari, which it never does.
            for (code in codes) {
                models.filter { it.ggml && it.fixedLang == code && it.decodeAs == code }
                    .minByOrNull { it.sizeBytes }
                    ?.let { picks += it }
            }
            byId("cpp-base")?.let { picks += it }
            if ("en" in codes) byId("cpp-base-en")?.let { picks += it }
            byId("cpp-turbo")?.let { picks += it }
            return picks.take(limit)
        }
        val picks = LinkedHashSet<WhisperModel>()
        // The one we point at first, then the smallest download, so a shortlist
        // spans the accuracy/size trade-off instead of listing near-clones.
        val bestFirst = compareBy<WhisperModel>({ it.tier != WhisperTier.RECOMMENDED }, { it.sizeBytes })

        // A grouped graph that covers everything the user types is the standout
        // pick: one download, and the language forced per phrase. Among those that
        // fit, the widest one goes first — it is the one that still fits after the
        // user enables another language — and only then the smallest download.
        models.filter { it.selectableLang && it.coverageOf(codes) == codes.size }
            .sortedWith(compareByDescending<WhisperModel> { it.langCodes.size }.thenBy { it.sizeBytes })
            .firstOrNull()
            ?.let { picks += it }

        // Then the single-language graphs, one per language, so a one-language
        // user is pointed straight at their own model.
        for (code in codes) {
            models.filter { it.fixedLang == code }.minWithOrNull(bestFirst)?.let { picks += it }
        }

        // Always leave an all-languages escape hatch — the only thing that
        // covers a language with no dedicated graph.
        default?.let { picks += it }

        // A language nothing can be told about rides entirely on how well the
        // model detects it, so the best detector in the catalog belongs on the
        // shortlist even at its size. Nothing else on this screen would lead
        // someone there, and for those languages it is the difference between
        // usable dictation and fluent text in the wrong language.
        if (codes.any { autoDetectOnly(it) }) {
            byId("turbo-multi")?.let { picks += it }
        }

        // Partial grouped coverage is still useful when nothing above fit well.
        models.filter { it.selectableLang && it.coverageOf(codes) > 0 }
            .sortedByDescending { it.coverageOf(codes) }
            .forEach { picks += it }

        return picks.take(limit)
    }

    fun downloadUrl(repo: String, fileName: String, revision: String = "main"): String =
        "https://huggingface.co/$repo/resolve/$revision/$fileName"
}
