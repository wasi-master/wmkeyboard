package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.modules.GoogleLibraries

/**
 * Which Tesseract language data ("pack") reads text in each keyboard language,
 * for the scan text tool.
 *
 * Packs are the `tessdata_fast` models: LSTM only, which is all the slimmed
 * engine in `native/tesseract-jni` can run, and about a quarter of the size of
 * `tessdata_best`. A pack is named by Tesseract's own code (`ben`, `chi_sim`),
 * and several keyboard languages can share one: both Norwegians read with
 * `nor`, and a language without a pack of its own reads with the main pack for
 * its script, so Maithili gets Hindi's Devanagari model.
 *
 * Chinese and Japanese can also read with PaddleOCR, whose one pack
 * ([PaddleOcrModel.PACK]) covers both and reads them far better than
 * tessdata_fast; see [offlinePack]. Its recognizer reads Latin script too,
 * which F-Droid's build, with neither ML Kit nor Tesseract, falls back on.
 *
 * Never a catalogue on its own: the lists the user sees come from their
 * enabled languages through [packsFor].
 */
object OcrLanguages {

    /**
     * Whether ML Kit's four non-Latin recognizers can run: a build with ML Kit on a
     * phone with Play services, which downloads their models. Null asks the
     * build and the phone; tests set it.
     */
    internal var extraScriptsOverride: Boolean? = null

    private val extraScripts: Boolean
        get() = extraScriptsOverride ?: (mlKit && PlayServices.available)

    /**
     * Whether ML Kit's text recognizers can be used: the build has them (every
     * one but F-Droid's) and Google's libraries are not switched off
     * ([GoogleLibraries]). Null asks the build; tests set it.
     */
    internal var mlKitOverride: Boolean? = null

    private val mlKit: Boolean
        get() = mlKitOverride ?: (BuildConfig.ENABLE_ML_KIT_SCANNERS && GoogleLibraries.usable)

    /**
     * Whether this build can run PaddleOCR: every one, on the LiteRT
     * interpreter Whisper brings where the build has it and on the MNN
     * library the sticker generator brings in F-Droid's (see
     * [PaddleOcrModel.runtime]). Null asks the build; tests set it.
     */
    internal var paddleOverride: Boolean? = null

    private val paddle: Boolean
        get() = paddleOverride ?: true

    /** The tessdata_fast release the downloads are pinned to. */
    const val SOURCE_TAG = "4.1.0"

    /**
     * Notation layouts and placeholders. They type Latin letters or symbols
     * but are not a language anyone prints, so they get no pack of their own
     * and the tool falls back to another enabled language.
     */
    private val notText = setOf("ipa", "fancy", "music", "braille", "morse", "und")

    fun downloadUrl(pack: String): String =
        "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$SOURCE_TAG/$pack.traineddata"

    /** The pack that reads [language], or null when no pack fits it. */
    fun packFor(language: LanguageDef): String? {
        if (language.id in notText) return null
        return byLanguageId[language.id] ?: byScript[language.script]
    }

    /**
     * Whether PaddleOCR reads [language] in a build that can run it: Chinese
     * in either script and Japanese, its own; and Latin script, which its
     * recognizer also reads, only in a build with neither ML Kit nor
     * Tesseract ([tesseractAvailable]) to read it better.
     */
    fun paddleReads(language: LanguageDef, tesseractAvailable: Boolean = true): Boolean =
        paddle && language.id !in notText && when (language.script) {
            ScriptId.HAN, ScriptId.JAPANESE -> true
            ScriptId.LATIN -> !mlKit && !tesseractAvailable
            else -> false
        }

    /**
     * [engine] as this build can honour it: ML Kit in a build without it,
     * or Tesseract without its library, read as Auto, so a choice restored
     * from another build's backup still reads with what is here.
     */
    fun usable(engine: OcrEngine, tesseractAvailable: Boolean): OcrEngine = when {
        engine == OcrEngine.ML_KIT && !mlKit -> OcrEngine.AUTO
        engine == OcrEngine.TESSERACT && !tesseractAvailable -> OcrEngine.AUTO
        else -> engine
    }

    /** The engines the settings offer in this build, Auto first. */
    fun engines(tesseractAvailable: Boolean): List<OcrEngine> =
        OcrEngine.entries.filter { usable(it, tesseractAvailable) == it }

    /** The download size of [pack] in bytes, or 0 for a name this table does not know. */
    fun sizeOf(pack: String): Long =
        if (pack == PaddleOcrModel.PACK) PaddleOcrModel.totalBytes else sizes[pack] ?: 0L

    /**
     * A human name for [pack]: the English name of the first language that
     * reads with it as its own, or the pack name itself.
     */
    fun nameOf(pack: String): String {
        if (pack == PaddleOcrModel.PACK) return "PaddleOCR"
        return byLanguageId.entries.firstOrNull { it.value == pack }
            ?.let { LanguageRegistry.byId(it.key).englishName }
            ?: pack
    }

    /** Every pack name this table can hand out. */
    val allPacks: Set<String> get() = sizes.keys

    /**
     * The packs [languages] need under [engine], each with the languages it
     * serves, in the order the languages come in. Languages with no pack are
     * left out. Under Auto, Chinese and Japanese list Paddle's pack where
     * ML Kit does not read them, and so does Latin script where neither
     * ML Kit nor Tesseract ([tesseractAvailable]) is in the build.
     */
    fun packsFor(
        languages: List<LanguageDef>,
        engine: OcrEngine = OcrEngine.AUTO,
        tesseractAvailable: Boolean = true,
    ): List<Pair<String, List<LanguageDef>>> =
        languages.mapNotNull { lang ->
            val auto = usable(engine, tesseractAvailable) == OcrEngine.AUTO
            val pack = if (auto && paddleReads(lang, tesseractAvailable) && mlKitScript(lang) == null) {
                PaddleOcrModel.PACK
            } else {
                packFor(lang)?.takeIf { tesseractAvailable }
            }
            pack?.let { it to lang }
        }
            .groupBy({ it.first }, { it.second })
            .toList()

    /**
     * The pack that reads [language] on the phone under [engine], Tesseract's
     * or [PaddleOcrModel.PACK], or null when ML Kit reads it instead.
     * [tesseractAvailable] is false in a build without the native library.
     * [onDisk] says whether a pack is downloaded: under Auto, Chinese and
     * Japanese read with Paddle unless only their Tesseract pack is there, so
     * a pack fetched before Paddle existed keeps working and the download
     * offered is always Paddle's. Null too when nothing in this build reads
     * [language] offline (see [readable]).
     */
    fun offlinePack(
        engine: OcrEngine,
        language: LanguageDef,
        tesseractAvailable: Boolean,
        onDisk: (String) -> Boolean = { false },
    ): String? {
        val pack = packFor(language) ?: return null
        return when (usable(engine, tesseractAvailable)) {
            OcrEngine.ML_KIT, OcrEngine.ONLINE -> null
            OcrEngine.TESSERACT -> pack.takeIf { tesseractAvailable }
            OcrEngine.AUTO -> when {
                // ML Kit reads its five scripts better than tessdata_fast does,
                // CJK above all (#757), and needs no download.
                mlKitScript(language) != null -> null
                // So does Paddle, where ML Kit's Chinese and Japanese are
                // missing, and for Latin script where nothing else is.
                paddleReads(language, tesseractAvailable) ->
                    if (tesseractAvailable && !onDisk(PaddleOcrModel.PACK) && onDisk(pack)) pack
                    else PaddleOcrModel.PACK
                else -> pack.takeIf { tesseractAvailable }
            }
        }
    }

    /**
     * The ML Kit recognizer that reads [language], or null when ML Kit has
     * none for its script. Each non-Latin recognizer reads Latin as well, so
     * English words in a Japanese sign still come through.
     *
     * The other four's models come from Play services on first use, so
     * without Play services they count as missing and those scripts go to
     * Paddle or Tesseract.
     */
    fun mlKitScript(language: LanguageDef): MlKitScript? {
        if (language.id in notText || !mlKit) return null
        if (language.script != ScriptId.LATIN && !extraScripts) return null
        return when (language.script) {
            ScriptId.LATIN -> MlKitScript.LATIN
            ScriptId.HAN -> MlKitScript.CHINESE
            ScriptId.JAPANESE -> MlKitScript.JAPANESE
            ScriptId.HANGUL -> MlKitScript.KOREAN
            ScriptId.DEVANAGARI -> MlKitScript.DEVANAGARI
            else -> null
        }
    }

    /**
     * The recognizer ML Kit reads [language] with when it reads it at all:
     * a script ML Kit has no recognizer for falls back to the Latin one, as
     * it always did under [OcrEngine.ML_KIT]. Meaningless in a build without
     * ML Kit, where [readable] decides instead.
     */
    fun mlKitRecognizer(language: LanguageDef): MlKitScript = mlKitScript(language) ?: MlKitScript.LATIN

    /**
     * The languages the viewfinder's language chip steps through: one per
     * distinct way of reading, so two languages that read with the same pack,
     * or both with the same ML Kit recognizer, do not both appear. The panel
     * shows the chip only with two or more. Empty under [OcrEngine.ONLINE],
     * where the language makes no difference.
     */
    fun chipLanguages(
        engine: OcrEngine,
        enabled: List<LanguageDef>,
        tesseractAvailable: Boolean,
        onDisk: (String) -> Boolean = { false },
    ): List<LanguageDef> {
        // The online model reads any script, so the language changes nothing there.
        if (engine == OcrEngine.ONLINE) return emptyList()
        return enabled.filter { packFor(it) != null && readable(engine, it, tesseractAvailable, onDisk) }
            .distinctBy { route(engine, it, tesseractAvailable, onDisk) }
    }

    /**
     * Whether anything in this build reads [language] under [engine]: always
     * online and wherever ML Kit is; without ML Kit (F-Droid's build) only a
     * pack, Paddle's or Tesseract's.
     */
    fun readable(
        engine: OcrEngine,
        language: LanguageDef,
        tesseractAvailable: Boolean,
        onDisk: (String) -> Boolean = { false },
    ): Boolean = engine == OcrEngine.ONLINE || mlKit ||
        offlinePack(engine, language, tesseractAvailable, onDisk) != null

    /**
     * What reads [language] under [engine], as a key: two languages with the
     * same key read the same way.
     */
    fun route(
        engine: OcrEngine,
        language: LanguageDef,
        tesseractAvailable: Boolean,
        onDisk: (String) -> Boolean = { false },
    ): String =
        offlinePack(engine, language, tesseractAvailable, onDisk) ?: "mlkit:${mlKitRecognizer(language)}"

    // Keyboard language id -> pack, for every language with a pack of its own.
    // Sizes are the 4.1.0 files, for progress and the storage check.
    private val byLanguageId: Map<String, String> = mapOf(
        "en" to "eng", "bn" to "ben", "fr" to "fra", "de" to "deu", "es" to "spa", "ko" to "kor",
        "ru" to "rus", "ar" to "ara", "el" to "ell", "he" to "heb", "hi" to "hin", "pt" to "por",
        "uk" to "ukr", "it" to "ita", "nl" to "nld", "pl" to "pol", "sv" to "swe", "sr" to "srp",
        "sr-Latn" to "srp_latn", "bg" to "bul", "ka" to "kat", "cs" to "ces", "sk" to "slk",
        "ro" to "ron", "hu" to "hun", "fi" to "fin", "da" to "dan", "nb" to "nor", "nn" to "nor",
        "hr" to "hrv", "fa" to "fas", "be" to "bel", "et" to "est", "lt" to "lit", "lv" to "lav",
        "sl" to "slv", "ur" to "urd", "ps" to "pus", "sd" to "snd", "ug" to "uig", "hy" to "hye",
        "mk" to "mkd", "kk" to "kaz", "ky" to "kir", "tg" to "tgk", "mn" to "mon", "tt" to "tat",
        "mr" to "mar", "ne" to "nep", "sa" to "san", "ta" to "tam", "si" to "sin", "te" to "tel",
        "kn" to "kan", "ml" to "mal", "gu" to "guj", "pa" to "pan", "or" to "ori", "as" to "asm",
        "ca" to "cat", "gl" to "glg", "eu" to "eus", "oc" to "oci", "br" to "bre", "co" to "cos",
        "la" to "lat", "lb" to "ltz", "fy" to "fry", "fo" to "fao", "cy" to "cym", "ga" to "gle",
        "gd" to "gla", "is" to "isl", "sq" to "sqi", "mt" to "mlt", "eo" to "epo", "af" to "afr",
        "tr" to "tur", "az" to "aze", "uz" to "uzb", "ku" to "kmr", "id" to "ind", "ms" to "msa",
        "tl" to "fil", "ceb" to "ceb", "jv" to "jav", "su" to "sun", "mi" to "mri", "sw" to "swa",
        "yo" to "yor", "vi" to "vie", "ja" to "jpn", "zh" to "chi_sim", "yue" to "chi_tra",
        "ht" to "hat", "qu" to "que", "th" to "tha", "lo" to "lao", "km" to "khm", "my" to "mya",
        "am" to "amh", "ti" to "tir", "to" to "ton", "dv" to "div", "yi" to "yid", "syc" to "syr",
        "grc" to "grc", "chr" to "chr", "iu" to "iku", "bo" to "bod", "dz" to "dzo",
    )

    private val byScript: Map<ScriptId, String> = mapOf(
        ScriptId.LATIN to "eng",
        ScriptId.CYRILLIC to "rus",
        ScriptId.GREEK to "ell",
        ScriptId.ARMENIAN to "hye",
        ScriptId.GEORGIAN to "kat",
        ScriptId.ARABIC to "ara",
        ScriptId.HEBREW to "heb",
        ScriptId.SYRIAC to "syr",
        ScriptId.DEVANAGARI to "hin",
        ScriptId.BENGALI to "ben",
        ScriptId.GURMUKHI to "pan",
        ScriptId.GUJARATI to "guj",
        ScriptId.ORIYA to "ori",
        ScriptId.TAMIL to "tam",
        ScriptId.TELUGU to "tel",
        ScriptId.KANNADA to "kan",
        ScriptId.MALAYALAM to "mal",
        ScriptId.SINHALA to "sin",
        ScriptId.THAI to "tha",
        ScriptId.LAO to "lao",
        ScriptId.KHMER to "khm",
        ScriptId.MYANMAR to "mya",
        ScriptId.HANGUL to "kor",
        ScriptId.ETHIOPIC to "amh",
        ScriptId.THAANA to "div",
        ScriptId.JAPANESE to "jpn",
        ScriptId.HAN to "chi_sim",
        ScriptId.CHEROKEE to "chr",
        ScriptId.CANADIAN_ABORIGINAL_SYLLABICS to "iku",
        ScriptId.TIBETAN to "bod",
    )

    private val sizes: Map<String, Long> = mapOf(
        "afr" to 2652786L, "amh" to 5470094L, "ara" to 1432056L, "asm" to 2045427L, "aze" to 3524799L,
        "bel" to 3692098L, "ben" to 855841L, "bod" to 1966440L, "bre" to 6335007L, "bul" to 1675212L,
        "cat" to 1146012L, "ceb" to 716214L, "ces" to 3795684L, "chi_sim" to 2469156L,
        "chi_tra" to 2366642L, "chr" to 374944L, "cos" to 2299099L, "cym" to 2208919L, "dan" to 2580059L,
        "deu" to 1525436L, "div" to 1774535L, "dzo" to 449596L, "ell" to 1419514L, "eng" to 4113088L,
        "epo" to 4728393L, "est" to 4458101L, "eus" to 5175921L, "fao" to 3439772L, "fas" to 431500L,
        "fil" to 1841715L, "fin" to 7865732L, "fra" to 1130365L, "fry" to 1906018L, "gla" to 3068307L,
        "gle" to 1181824L, "glg" to 2554555L, "grc" to 2246328L, "guj" to 1418394L, "hat" to 1976902L,
        "heb" to 961404L, "hin" to 1122751L, "hrv" to 4103348L, "hun" to 5296273L, "hye" to 3463717L,
        "iku" to 2802266L, "ind" to 1122661L, "isl" to 2278973L, "ita" to 2701314L, "jav" to 2982741L,
        "jpn" to 2471260L, "kan" to 3608331L, "kat" to 2524769L, "kaz" to 4734644L, "khm" to 1446926L,
        "kir" to 9928497L, "kmr" to 3568615L, "kor" to 1677415L, "lao" to 6386744L, "lat" to 3187463L,
        "lav" to 2717247L, "lit" to 3154896L, "ltz" to 2606426L, "mal" to 5275996L, "mar" to 2118233L,
        "mkd" to 1600188L, "mlt" to 2308796L, "mon" to 2137042L, "mri" to 862973L, "msa" to 1747801L,
        "mya" to 4640561L, "nep" to 1002911L, "nld" to 6050296L, "nor" to 3610079L, "oci" to 6322087L,
        "ori" to 1480066L, "pan" to 497721L, "pol" to 4765518L, "por" to 1982756L, "pus" to 1772087L,
        "que" to 5026336L, "ron" to 2376323L, "rus" to 3861738L, "san" to 12404677L, "sin" to 1727413L,
        "slk" to 4427661L, "slv" to 3003829L, "snd" to 1694035L, "spa" to 2294433L, "sqi" to 1874705L,
        "srp" to 2149931L, "srp_latn" to 3281787L, "sun" to 1369500L, "swa" to 2167651L,
        "swe" to 4167034L, "syr" to 2207208L, "tam" to 3237963L, "tat" to 1072896L, "tel" to 2769654L,
        "tgk" to 2602685L, "tha" to 1072600L, "tir" to 378822L, "ton" to 947249L, "tur" to 4550554L,
        "uig" to 2794272L, "ukr" to 3825102L, "urd" to 1398718L, "uzb" to 6470703L, "vie" to 531275L,
        "yid" to 545606L, "yor" to 963400L,
    )
}

/** ML Kit's text recognizers, one per script it reads. */
enum class MlKitScript { LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI }
