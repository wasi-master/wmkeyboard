package com.wasimaster.wmkeyboard.core.dictionaries

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.prediction.R

/**
 * Where a downloadable word list comes from. A language can have one of each,
 * and the one listed first here is the one it downloads unless the user picks
 * the other ([DictionaryCatalog.preferred]).
 */
enum class WordlistSource(@StringRes val labelRes: Int, @StringRes val detailRes: Int) {
    /**
     * AOSP LatinIME's own lists (Apache-2.0), for the 23 languages Android's
     * keyboard shipped one for, plus Canadian and Australian English respelled
     * from them. Curated rather than counted: a couple of
     * hundred thousand words with the names, typos and subtitle noise of a
     * counted list mostly gone. Their frequencies are AOSP's 0..255 log scale,
     * which the downloader turns back into counts
     * ([com.wasimaster.wmkeyboard.core.prediction.AospScores.listCount]).
     */
    AOSP(R.string.core_pred_wordlist_source_aosp_label, R.string.core_pred_wordlist_source_aosp_detail),

    /** Counted from subtitles and web text: every language the repo covers, and far more words each. */
    FREQUENCY(R.string.core_pred_wordlist_source_frequency_label, R.string.core_pred_wordlist_source_frequency_detail),
}

/**
 * One downloadable wordlist from the wmkeyboard-data repo
 * (https://github.com/wasi-master/wmkeyboard-data). Each is a gzipped
 * `word<space>count` list sorted by descending frequency, which is what lets
 * the downloader stop reading after the user's chosen word cap instead of
 * transferring the whole file (Thai is 41 MB compressed; the first 150k words
 * are a fraction of that).
 */
data class DictionaryEntry(
    /** Stable key for status maps and UI (== [languageId] except `pt_br`). */
    val id: String,
    /** [com.wasimaster.wmkeyboard.core.script.LanguageRegistry] id it serves. */
    val languageId: String,
    /** Directory/file stem in the data repo (`de` -> `data/de/de_full.txt.gz`). */
    val repoCode: String,
    /** Total lines in the full repo list (the cap trims this). */
    val totalWordCount: Int,
    /** Compressed size of the full file — progress denominator upper bound. */
    val approxGzBytes: Long,
    /** Label distinguishing two entries for one language ("Europe"/"Brazil"). */
    @StringRes val variantRes: Int? = null,
    /**
     * Which file under `data/$repoCode/` this entry downloads: `"full"` (the
     * ordinary native-script list), `"rom"` (a romanized/Latin-script
     * transliteration list — its own selectable language, e.g. "Bengali
     * (Romanized)", not a variant of the native-script entry) or `"latn"`
     * (a language's other script, written as such: Tachelhit's Latin list).
     */
    val suffix: String = "full",
    /**
     * Overrides the filename stem (`${repoCode}_$suffix` by default) for the
     * rare file whose name does not match its own folder — the repo's Russian
     * romanized list lives at `data/ru/russian_rom.txt.gz`, not `ru_rom.txt.gz`.
     */
    val fileStem: String? = null,
    /** Which kind of list this is, and so how its frequencies read. */
    val source: WordlistSource = WordlistSource.FREQUENCY,
    /**
     * Names this list was published under before [fileName], which the data
     * repo keeps for the app versions that still fetch them (`bn_full.txt.gz`
     * before `bn_full_v2.txt.gz`). A language pack carries both; see
     * [DictionaryCatalog.byLegacyFileName].
     */
    val legacyFileNames: List<String> = emptyList(),
) {
    /** The list's file name in the data repo, and in a language pack. */
    val fileName: String get() = "${fileStem ?: "${repoCode}_$suffix"}.txt.gz"

    /** Where the list is fetched from: the data repository, wherever [ServiceRepo.DATA] points. */
    val url: String
        get() = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl("data/$repoCode/$fileName")
}

/**
 * The downloadable wordlists for every language the registry knows and the data
 * repo covers (over 300 of them; identity mapping except Wikipedia-style codes:
 * `roa_rup`->`rup`, `mhr`->`chm`, `bxr`->`bua`, `nrm`->`nrf`, and `pt`/`pt_br`
 * both feeding `pt`). Regenerate the table against a fresh repo checkout with
 * a directory listing — sizes and counts are display/progress hints, not
 * checksums, so drift is harmless.
 */
object DictionaryCatalog {

    /**
     * User-selectable download size: how many of the most frequent words to
     * keep. [ALL] takes the list whole, however long it is — its cap is the
     * entry's own [DictionaryEntry.totalWordCount], not a number chosen here.
     */
    enum class DictionarySize(@StringRes val labelRes: Int, val wordCap: Int) {
        SMALL(R.string.core_pred_wordlist_size_small_label, 50_000),
        MEDIUM(R.string.core_pred_wordlist_size_medium_label, 150_000),
        LARGE(R.string.core_pred_wordlist_size_large_label, 300_000),
        ALL(R.string.core_pred_wordlist_size_all_label, Int.MAX_VALUE),
    }

    /** Words [size] actually keeps of [entry] — never more than the list holds. */
    fun wordCap(entry: DictionaryEntry, size: DictionarySize): Int =
        minOf(size.wordCap, entry.totalWordCount)

    private fun entry(
        id: String,
        languageId: String,
        repoCode: String,
        totalWordCount: Int,
        approxGzBytes: Long,
        @StringRes variantRes: Int? = null,
        suffix: String = "full",
        fileStem: String? = null,
        legacyFileNames: List<String> = emptyList(),
    ) = DictionaryEntry(
        id, languageId, repoCode, totalWordCount, approxGzBytes, variantRes, suffix, fileStem,
        legacyFileNames = legacyFileNames,
    )

    /**
     * Bangla's list with corpus counts. `bn_full.txt.gz` said 1 for every word;
     * it stays published, unchanged, for the versions that fetch it.
     */
    private const val BN_STEM = "bn_full_v2"
    private const val BN_GZ_BYTES = 2_308_065L
    private val BN_LEGACY = listOf("bn_full.txt.gz")

    /**
     * An AOSP list, `data/<repoCode>/<repoCode>_aosp.txt.gz` beside the
     * language's counted one. Its id is never the language id, so a list
     * downloaded before AOSP lists existed, which has no source marker, still
     * reads as the counted list it is.
     */
    private fun aosp(
        id: String,
        languageId: String,
        repoCode: String,
        totalWordCount: Int,
        approxGzBytes: Long,
        @StringRes variantRes: Int? = null,
        fileStem: String? = null,
    ) = DictionaryEntry(
        id, languageId, repoCode, totalWordCount, approxGzBytes, variantRes,
        suffix = "aosp", fileStem = fileStem, source = WordlistSource.AOSP,
    )

    val entries: List<DictionaryEntry> = listOf(
        entry("ab", "ab", "ab", 81_433, 380_406L),
        entry("ady", "ady", "ady", 61_998, 327_422L, fileStem = "ady_full_v2", legacyFileNames = listOf("ady_full.txt.gz")),
        entry("af", "af", "af", 286_020, 1_608_123L, fileStem = "af_full_v2", legacyFileNames = listOf("af_full.txt.gz")),
        entry("ak", "ak", "ak", 77_497, 336_249L, fileStem = "ak_full_v2", legacyFileNames = listOf("ak_full.txt.gz")),
        entry("am", "am", "am", 252_980, 1_348_373L, fileStem = "am_full_v2", legacyFileNames = listOf("am_full.txt.gz")),
        entry("ar", "ar", "ar", 2_487_447, 9_706_359L),
        entry("av", "av", "av", 129_813, 665_977L, fileStem = "av_full_v2", legacyFileNames = listOf("av_full.txt.gz")),
        entry("ay", "ay", "ay", 107_743, 466_049L, fileStem = "ay_full_v2", legacyFileNames = listOf("ay_full.txt.gz")),
        entry("az", "az", "az", 814_123, 3_616_285L),
        entry("ba", "ba", "ba", 222_331, 1_378_964L, fileStem = "ba_full_v2", legacyFileNames = listOf("ba_full.txt.gz")),
        entry("be", "be", "be", 349_962, 1_737_920L),
        entry("bg", "bg", "bg", 1_055_844, 4_924_294L),
        entry("bho", "bho", "bho", 73_823, 368_808L, fileStem = "bho_full_v2", legacyFileNames = listOf("bho_full.txt.gz")),
        entry("bi", "bi", "bi", 6_558, 25_592L),
        entry("bm", "bm", "bm", 45_558, 203_813L, fileStem = "bm_full_v2", legacyFileNames = listOf("bm_full.txt.gz")),
        entry("bn", "bn", "bn", 451_348, BN_GZ_BYTES, fileStem = BN_STEM, legacyFileNames = BN_LEGACY),
        entry("bo", "bo", "bo", 27_565, 352_881L, fileStem = "bo_full_v2", legacyFileNames = listOf("bo_full.txt.gz")),
        entry("br", "br", "br", 165_034, 856_994L, fileStem = "br_full_v2", legacyFileNames = listOf("br_full.txt.gz")),
        entry("brx", "brx", "brx", 24_136, 127_775L, fileStem = "brx_full_v2", legacyFileNames = listOf("brx_full.txt.gz")),
        entry("bua", "bua", "bxr", 60_442, 263_262L),
        entry("ca", "ca", "ca", 184_216, 669_028L),
        entry("ce", "ce", "ce", 180_948, 888_960L, fileStem = "ce_full_v2", legacyFileNames = listOf("ce_full.txt.gz")),
        entry("ceb", "ceb", "ceb", 383_771, 1_344_763L),
        entry("chr", "chr", "chr", 14_789, 69_497L, fileStem = "chr_full_v2", legacyFileNames = listOf("chr_full.txt.gz")),
        entry("ckb", "ckb", "ckb", 362_995, 1_816_031L, fileStem = "ckb_full_v2", legacyFileNames = listOf("ckb_full.txt.gz")),
        entry("co", "co", "co", 102_426, 501_455L, fileStem = "co_full_v2", legacyFileNames = listOf("co_full.txt.gz")),
        entry("crh", "crh", "crh", 306_570, 1_563_549L, fileStem = "crh_full_v2", legacyFileNames = listOf("crh_full.txt.gz")),
        entry("cs", "cs", "cs", 1_719_446, 6_998_615L),
        entry("csb", "csb", "csb", 63_525, 255_227L),
        entry("cv", "cv", "cv", 163_539, 915_572L, fileStem = "cv_full_v2", legacyFileNames = listOf("cv_full.txt.gz")),
        entry("cy", "cy", "cy", 142_957, 755_838L, fileStem = "cy_full_v2", legacyFileNames = listOf("cy_full.txt.gz")),
        entry("da", "da", "da", 685_713, 3_082_306L),
        entry("de", "de", "de", 1_153_001, 5_474_930L),
        entry("doi", "doi", "doi", 4_521, 22_511L, fileStem = "doi_full_v2", legacyFileNames = listOf("doi_full.txt.gz")),
        entry("dsb", "dsb", "dsb", 46_802, 187_995L),
        entry("dv", "dv", "dv", 223_051, 1_326_779L, fileStem = "dv_full_v2", legacyFileNames = listOf("dv_full.txt.gz")),
        entry("ee", "ee", "ee", 41_155, 192_418L, fileStem = "ee_full_v2", legacyFileNames = listOf("ee_full.txt.gz")),
        entry("el", "el", "el", 1_168_400, 5_726_781L),
        entry("en", "en", "en", 1_636_178, 6_753_423L),
        entry("eo", "eo", "eo", 386_958, 1_630_975L),
        entry("es", "es", "es", 1_190_537, 4_833_505L),
        entry("et", "et", "et", 1_000_180, 4_084_505L),
        entry("eu", "eu", "eu", 389_587, 1_584_930L),
        entry("fa", "fa", "fa", 363_989, 1_426_839L),
        entry("fi", "fi", "fi", 2_488_573, 10_584_625L),
        entry("fj", "fj", "fj", 31_742, 146_875L, fileStem = "fj_full_v2", legacyFileNames = listOf("fj_full.txt.gz")),
        entry("fo", "fo", "fo", 245_885, 1_338_601L, fileStem = "fo_full_v2", legacyFileNames = listOf("fo_full.txt.gz")),
        entry("fr", "fr", "fr", 825_643, 3_464_732L),
        entry("fur", "fur", "fur", 104_874, 476_785L, fileStem = "fur_full_v2", legacyFileNames = listOf("fur_full.txt.gz")),
        entry("fy", "fy", "fy", 166_709, 928_127L, fileStem = "fy_full_v2", legacyFileNames = listOf("fy_full.txt.gz")),
        entry("ga", "ga", "ga", 192_715, 973_362L, fileStem = "ga_full_v2", legacyFileNames = listOf("ga_full.txt.gz")),
        entry("gd", "gd", "gd", 115_476, 549_059L, fileStem = "gd_full_v2", legacyFileNames = listOf("gd_full.txt.gz")),
        entry("gl", "gl", "gl", 241_385, 1_273_264L, fileStem = "gl_full_v2", legacyFileNames = listOf("gl_full.txt.gz")),
        entry("gn", "gn", "gn", 155_612, 764_988L, fileStem = "gn_full_v2", legacyFileNames = listOf("gn_full.txt.gz")),
        entry("gu", "gu", "gu", 172_335, 834_724L),
        entry("gv", "gv", "gv", 31_202, 151_000L, fileStem = "gv_full_v2", legacyFileNames = listOf("gv_full.txt.gz")),
        entry("ha", "ha", "ha", 87_486, 429_961L, fileStem = "ha_full_v2", legacyFileNames = listOf("ha_full.txt.gz")),
        entry("haw", "haw", "haw", 46_886, 231_785L, fileStem = "haw_full_v2", legacyFileNames = listOf("haw_full.txt.gz")),
        entry("he", "he", "he", 1_053_149, 4_291_295L),
        entry("hi", "hi", "hi", 242_911, 1_313_058L),
        entry("hr", "hr", "hr", 1_497_358, 5_787_914L),
        entry("hsb", "hsb", "hsb", 110_141, 451_823L),
        entry("ht", "ht", "ht", 77_437, 390_094L, fileStem = "ht_full_v2", legacyFileNames = listOf("ht_full.txt.gz")),
        entry("hu", "hu", "hu", 3_138_382, 13_176_302L),
        entry("hy", "hy", "hy", 394_817, 1_782_177L),
        entry("ia", "ia", "ia", 50_502, 208_771L),
        entry("id", "id", "id", 354_661, 1_444_041L),
        entry("ig", "ig", "ig", 67_813, 340_990L, fileStem = "ig_full_v2", legacyFileNames = listOf("ig_full.txt.gz")),
        entry("is", "is", "is", 348_756, 1_713_712L, fileStem = "is_full_v2", legacyFileNames = listOf("is_full.txt.gz")),
        entry("it", "it", "it", 791_470, 3_240_903L),
        entry("iu", "iu", "iu", 45_591, 255_492L, fileStem = "iu_full_v2", legacyFileNames = listOf("iu_full.txt.gz")),
        entry("ja", "ja", "ja", 213_393, 922_434L),
        entry("jbo", "jbo", "jbo", 2_412, 6_319L),
        entry("jv", "jv", "jv", 224_820, 1_090_063L, fileStem = "jv_full_v2", legacyFileNames = listOf("jv_full.txt.gz")),
        entry("ka", "ka", "ka", 335_324, 1_639_061L),
        entry("kbd", "kbd", "kbd", 194_675, 948_612L, fileStem = "kbd_full_v2", legacyFileNames = listOf("kbd_full.txt.gz")),
        entry("ki", "ki", "ki", 108_925, 442_381L, fileStem = "ki_full_v2", legacyFileNames = listOf("ki_full.txt.gz")),
        entry("kk", "kk", "kk", 361_083, 1_788_094L),
        entry("km", "km", "km", 67_430, 471_153L, fileStem = "km_full_v2", legacyFileNames = listOf("km_full.txt.gz")),
        entry("kn", "kn", "kn", 549_589, 2_884_310L),
        entry("ko", "ko", "ko", 675_331, 2_838_560L),
        // Devanagari, from gom.wikipedia; data/kok holds the Romi list.
        entry("kok", "kok", "gom", 215_000, 1_106_180L, fileStem = "gom_full_v2", legacyFileNames = listOf("gom_full.txt.gz")),
        entry("ku", "ku", "ku", 205_744, 925_647L, fileStem = "ku_full_v2", legacyFileNames = listOf("ku_full.txt.gz")),
        entry("kv", "kv", "kv", 67_805, 347_193L, fileStem = "kv_full_v2", legacyFileNames = listOf("kv_full.txt.gz")),
        entry("kw", "kw", "kw", 53_918, 232_997L, fileStem = "kw_full_v2", legacyFileNames = listOf("kw_full.txt.gz")),
        entry("ky", "ky", "ky", 336_397, 1_691_959L),
        entry("la", "la", "la", 184_648, 716_296L),
        entry("lb", "lb", "lb", 203_871, 1_184_625L, fileStem = "lb_full_v2", legacyFileNames = listOf("lb_full.txt.gz")),
        entry("lez", "lez", "lez", 59_601, 267_993L),
        entry("lg", "lg", "lg", 275_388, 1_233_144L, fileStem = "lg_full_v2", legacyFileNames = listOf("lg_full.txt.gz")),
        entry("lij", "lij", "lij", 201_943, 876_877L, fileStem = "lij_full_v2", legacyFileNames = listOf("lij_full.txt.gz")),
        entry("lld", "lld", "lld", 153_591, 606_785L, fileStem = "lld_full_v2", legacyFileNames = listOf("lld_full.txt.gz")),
        entry("ln", "ln", "ln", 96_734, 416_881L, fileStem = "ln_full_v2", legacyFileNames = listOf("ln_full.txt.gz")),
        entry("lo", "lo", "lo", 49_018, 419_423L, fileStem = "lo_full_v2", legacyFileNames = listOf("lo_full.txt.gz")),
        entry("lt", "lt", "lt", 321_660, 1_204_167L),
        entry("lv", "lv", "lv", 330_323, 1_403_969L),
        entry("mai", "mai", "mai", 136_272, 737_980L, fileStem = "mai_full_v2", legacyFileNames = listOf("mai_full.txt.gz")),
        entry("mg", "mg", "mg", 216_116, 968_436L, fileStem = "mg_full_v2", legacyFileNames = listOf("mg_full.txt.gz")),
        entry("chm", "chm", "mhr", 62_439, 285_836L),
        entry("mi", "mi", "mi", 59_342, 281_266L, fileStem = "mi_full_v2", legacyFileNames = listOf("mi_full.txt.gz")),
        entry("mk", "mk", "mk", 329_084, 1_601_592L, fileStem = "mk_full_v2", legacyFileNames = listOf("mk_full.txt.gz")),
        entry("ml", "ml", "ml", 250_542, 1_275_671L),
        entry("mn", "mn", "mn", 187_074, 1_074_182L, fileStem = "mn_full_v2", legacyFileNames = listOf("mn_full.txt.gz")),
        entry("mr", "mr", "mr", 331_817, 1_721_372L),
        entry("ms", "ms", "ms", 242_279, 1_118_799L),
        entry("mt", "mt", "mt", 127_212, 652_939L, fileStem = "mt_full_v2", legacyFileNames = listOf("mt_full.txt.gz")),
        entry("my", "my", "my", 70_163, 451_730L, fileStem = "my_full_v2", legacyFileNames = listOf("my_full.txt.gz")),
        entry("myv", "myv", "myv", 122_039, 639_648L, fileStem = "myv_full_v2", legacyFileNames = listOf("myv_full.txt.gz")),
        entry("nb", "nb", "nb", 387_768, 1_734_759L),
        entry("nd", "nd", "nd", 36_656, 126_306L),
        entry("ne", "ne", "ne", 163_948, 818_119L),
        entry("nl", "nl", "nl", 1_099_506, 5_009_575L),
        entry("nqo", "nqo", "nqo", 51_467, 230_261L),
        entry("nrf", "nrf", "nrm", 33_765, 128_026L),
        entry("nso", "nso", "nso", 47_459, 210_722L, fileStem = "nso_full_v2", legacyFileNames = listOf("nso_full.txt.gz")),
        entry("ny", "ny", "ny", 108_176, 512_152L, fileStem = "ny_full_v2", legacyFileNames = listOf("ny_full.txt.gz")),
        entry("oc", "oc", "oc", 219_831, 1_096_595L, fileStem = "oc_full_v2", legacyFileNames = listOf("oc_full.txt.gz")),
        entry("om", "om", "om", 119_703, 495_083L, fileStem = "om_full_v2", legacyFileNames = listOf("om_full.txt.gz")),
        entry("or", "or", "or", 72_978, 401_802L),
        entry("os", "os", "os", 108_037, 560_647L, fileStem = "os_full_v2", legacyFileNames = listOf("os_full.txt.gz")),
        entry("pa", "pa", "pa", 226_706, 1_148_074L),
        entry("pl", "pl", "pl", 1_481_466, 6_171_710L),
        entry("pms", "pms", "pms", 66_204, 313_472L, fileStem = "pms_full_v2", legacyFileNames = listOf("pms_full.txt.gz")),
        entry("ps", "ps", "ps", 161_407, 806_118L, fileStem = "ps_full_v2", legacyFileNames = listOf("ps_full.txt.gz")),
        entry(
            "pt", "pt", "pt", 763_183, 3_138_081L,
            variantRes = R.string.core_pred_wordlist_variant_europe_label,
        ),
        entry(
            "pt_br", "pt", "pt_br", 847_162, 3_384_245L,
            variantRes = R.string.core_pred_wordlist_variant_brazil_label,
        ),
        entry("qu", "qu", "qu", 265_657, 1_195_633L, fileStem = "qu_full_v2", legacyFileNames = listOf("qu_full.txt.gz")),
        entry("rm", "rm", "rm", 83_269, 415_308L, fileStem = "rm_full_v2", legacyFileNames = listOf("rm_full.txt.gz")),
        entry("ro", "ro", "ro", 1_199_239, 4_962_791L),
        entry("rup", "rup", "roa_rup", 25_337, 100_417L),
        entry("ru", "ru", "ru", 1_236_328, 5_940_540L),
        entry("rw", "rw", "rw", 225_033, 1_003_953L, fileStem = "rw_full_v2", legacyFileNames = listOf("rw_full.txt.gz")),
        entry("sa", "sa", "sa", 450_508, 2_749_564L, fileStem = "sa_full_v2", legacyFileNames = listOf("sa_full.txt.gz")),
        entry("sah", "sah", "sah", 256_403, 1_404_995L, fileStem = "sah_full_v2", legacyFileNames = listOf("sah_full.txt.gz")),
        entry("sc", "sc", "sc", 186_762, 852_700L, fileStem = "sc_full_v2", legacyFileNames = listOf("sc_full.txt.gz")),
        entry("scn", "scn", "scn", 225_369, 1_038_214L, fileStem = "scn_full_v2", legacyFileNames = listOf("scn_full.txt.gz")),
        entry("sd", "sd", "sd", 119_469, 558_768L, fileStem = "sd_full_v2", legacyFileNames = listOf("sd_full.txt.gz")),
        entry("se", "se", "se", 178_325, 937_019L, fileStem = "se_full_v2", legacyFileNames = listOf("se_full.txt.gz")),
        entry("si", "si", "si", 163_154, 811_027L),
        entry("sk", "sk", "sk", 746_834, 2_988_084L),
        entry("sl", "sl", "sl", 842_678, 3_236_603L),
        entry("sm", "sm", "sm", 49_836, 237_579L, fileStem = "sm_full_v2", legacyFileNames = listOf("sm_full.txt.gz")),
        entry("sn", "sn", "sn", 194_300, 914_557L, fileStem = "sn_full_v2", legacyFileNames = listOf("sn_full.txt.gz")),
        entry("so", "so", "so", 122_032, 564_115L, fileStem = "so_full_v2", legacyFileNames = listOf("so_full.txt.gz")),
        entry("sq", "sq", "sq", 280_604, 1_205_982L, fileStem = "sq_full_v2", legacyFileNames = listOf("sq_full.txt.gz")),
        entry("sr", "sr", "sr", 225_229, 950_408L),
        entry("ss", "ss", "ss", 74_104, 324_142L, fileStem = "ss_full_v2", legacyFileNames = listOf("ss_full.txt.gz")),
        entry("st", "st", "st", 52_723, 257_714L, fileStem = "st_full_v2", legacyFileNames = listOf("st_full.txt.gz")),
        entry("su", "su", "su", 192_197, 890_931L, fileStem = "su_full_v2", legacyFileNames = listOf("su_full.txt.gz")),
        entry("sv", "sv", "sv", 900_975, 4_079_684L),
        entry("sw", "sw", "sw", 128_718, 527_814L),
        entry("ta", "ta", "ta", 1_874_722, 7_756_983L),
        entry("te", "te", "te", 393_342, 2_061_715L),
        entry("tet", "tet", "tet", 45_229, 222_955L, fileStem = "tet_full_v2", legacyFileNames = listOf("tet_full.txt.gz")),
        entry("tg", "tg", "tg", 228_572, 1_321_402L, fileStem = "tg_full_v2", legacyFileNames = listOf("tg_full.txt.gz")),
        entry("th", "th", "th", 4_024_497, 41_176_795L),
        entry("ti", "ti", "ti", 185_786, 955_232L, fileStem = "ti_full_v2", legacyFileNames = listOf("ti_full.txt.gz")),
        entry("tk", "tk", "tk", 192_115, 998_283L, fileStem = "tk_full_v2", legacyFileNames = listOf("tk_full.txt.gz")),
        entry("tl", "tl", "tl", 144_149, 631_911L),
        entry("tlh", "tlh", "tlh", 6_277, 22_014L),
        // Toki Pona's entire vocabulary is ~140 words — the list is complete,
        // not truncated, so it ships despite being far below the usual floor.
        entry("tok", "tok", "tok", 178, 695L),
        entry("tn", "tn", "tn", 58_646, 274_712L, fileStem = "tn_full_v2", legacyFileNames = listOf("tn_full.txt.gz")),
        entry("to", "to", "to", 37_598, 173_385L, fileStem = "to_full_v2", legacyFileNames = listOf("to_full.txt.gz")),
        entry("tr", "tr", "tr", 2_027_282, 8_051_955L),
        entry("ts", "ts", "ts", 62_986, 283_025L, fileStem = "ts_full_v2", legacyFileNames = listOf("ts_full.txt.gz")),
        entry("tt", "tt", "tt", 214_559, 1_292_173L, fileStem = "tt_full_v2", legacyFileNames = listOf("tt_full.txt.gz")),
        entry("ty", "ty", "ty", 5_164, 21_116L),
        entry("tyv", "tyv", "tyv", 124_073, 655_937L, fileStem = "tyv_full_v2", legacyFileNames = listOf("tyv_full.txt.gz")),
        entry("udm", "udm", "udm", 112_672, 604_063L, fileStem = "udm_full_v2", legacyFileNames = listOf("udm_full.txt.gz")),
        entry("ug", "ug", "ug", 230_502, 1_314_345L, fileStem = "ug_full_v2", legacyFileNames = listOf("ug_full.txt.gz")),
        entry("uk", "uk", "uk", 409_369, 1_835_322L),
        entry("ur", "ur", "ur", 154_781, 541_600L),
        entry("uz", "uz", "uz", 176_272, 741_660L),
        entry("ve", "ve", "ve", 20_441, 95_473L, fileStem = "ve_full_v2", legacyFileNames = listOf("ve_full.txt.gz")),
        entry("vec", "vec", "vec", 298_035, 1_308_025L, fileStem = "vec_full_v2", legacyFileNames = listOf("vec_full.txt.gz")),
        entry("vi", "vi", "vi", 605_499, 3_056_250L),
        entry("wa", "wa", "wa", 96_815, 426_751L, fileStem = "wa_full_v2", legacyFileNames = listOf("wa_full.txt.gz")),
        entry("wo", "wo", "wo", 46_206, 204_553L, fileStem = "wo_full_v2", legacyFileNames = listOf("wo_full.txt.gz")),
        entry("xal", "xal", "xal", 25_536, 121_672L, fileStem = "xal_full_v2", legacyFileNames = listOf("xal_full.txt.gz")),
        entry("xh", "xh", "xh", 245_217, 1_187_214L, fileStem = "xh_full_v2", legacyFileNames = listOf("xh_full.txt.gz")),
        entry("yi", "yi", "yi", 112_632, 618_000L, fileStem = "yi_full_v2", legacyFileNames = listOf("yi_full.txt.gz")),
        entry("yo", "yo", "yo", 86_638, 459_185L, fileStem = "yo_full_v2", legacyFileNames = listOf("yo_full.txt.gz")),
        entry("zgh", "zgh", "zgh", 58_474, 243_558L),
        entry("zu", "zu", "zu", 367_932, 1_490_853L),
        entry("as", "as", "as", 142_052, 708_124L),
        entry("bpy", "bpy", "bpy", 36_838, 181_959L),
        entry("syl", "syl", "syl", 17_135, 73_917L),
        // Romanized/Latin-script transliteration lists — each its own selectable
        // language (e.g. "Bengali (Romanized)"), not a variant of the native one.
        entry("ar_rom", "ar_rom", "ar", 211_291, 867_223L, suffix = "rom"),
        entry("bn_rom", "bn_rom", "bn", 130_934, 439_995L, suffix = "rom", fileStem = "bn_rom_v2", legacyFileNames = listOf("bn_rom.txt.gz")),
        entry("gu_rom", "gu_rom", "gu", 1_034_629, 3_179_852L, suffix = "rom", fileStem = "gu_rom_v2", legacyFileNames = listOf("gu_rom.txt.gz")),
        entry("hi_rom", "hi_rom", "hi", 1_080_362, 3_484_829L, suffix = "rom", fileStem = "hi_rom_v2", legacyFileNames = listOf("hi_rom.txt.gz")),
        entry("kn_rom", "kn_rom", "kn", 2_736_573, 8_786_632L, suffix = "rom", fileStem = "kn_rom_v2", legacyFileNames = listOf("kn_rom.txt.gz")),
        entry("ml_rom", "ml_rom", "ml", 291_116, 1_167_850L, suffix = "rom", fileStem = "ml_rom_v2", legacyFileNames = listOf("ml_rom.txt.gz")),
        entry("mr_rom", "mr_rom", "mr", 1_346_682, 4_260_495L, suffix = "rom", fileStem = "mr_rom_v2", legacyFileNames = listOf("mr_rom.txt.gz")),
        entry("ne_rom", "ne_rom", "ne", 170_679, 566_063L, suffix = "rom"),
        entry("pa_rom", "pa_rom", "pa", 458_700, 1_431_167L, suffix = "rom", fileStem = "pa_rom_v2", legacyFileNames = listOf("pa_rom.txt.gz")),
        entry("ru_rom", "ru_rom", "ru", 1_313_808, 5_393_212L, suffix = "rom", fileStem = "russian_rom"),
        entry("si_rom", "si_rom", "si", 1_015_807, 4_021_053L, suffix = "rom", fileStem = "si_rom_v2", legacyFileNames = listOf("si_rom.txt.gz")),
        entry("ta_rom", "ta_rom", "ta", 3_157_911, 10_303_093L, suffix = "rom", fileStem = "ta_rom_v2", legacyFileNames = listOf("ta_rom.txt.gz")),
        entry("te_rom", "te_rom", "te", 2_291_197, 7_361_707L, suffix = "rom", fileStem = "te_rom_v2", legacyFileNames = listOf("te_rom.txt.gz")),
        entry("ur_rom", "ur_rom", "ur", 202_206, 680_199L, suffix = "rom", fileStem = "ur_rom_v2", legacyFileNames = listOf("ur_rom.txt.gz")),
        entry("kok_rom", "kok_rom", "kok", 58_449, 220_459L, suffix = "rom"),
        // --- Language expansion: 140 new languages. ---
        entry("ace", "ace", "ace", 98_394, 419_586L, fileStem = "ace_full_v2", legacyFileNames = listOf("ace_full.txt.gz")),
        entry("ami", "ami", "ami", 58883, 219840L),
        entry("an", "an", "an", 147_083, 658_655L, fileStem = "an_full_v2", legacyFileNames = listOf("an_full.txt.gz")),
        entry("ann", "ann", "ann", 8692, 34130L),
        entry("ast", "ast", "ast", 172_392, 843_345L, fileStem = "ast_full_v2", legacyFileNames = listOf("ast_full.txt.gz")),
        entry("atj", "atj", "atj", 18322, 72257L),
        entry("ban", "ban", "ban", 131_290, 565_778L, fileStem = "ban_full_v2", legacyFileNames = listOf("ban_full.txt.gz")),
        entry("bar", "bar", "bar", 140_062, 651_754L, fileStem = "bar_full_v2", legacyFileNames = listOf("bar_full.txt.gz")),
        entry("sgs", "sgs", "bat_smg", 96808, 372118L),
        entry("bbc", "bbc", "bbc", 30_563, 133_134L, fileStem = "bbc_full_v2", legacyFileNames = listOf("bbc_full.txt.gz")),
        entry("bcl", "bcl", "bcl", 94_424, 430_929L, fileStem = "bcl_full_v2", legacyFileNames = listOf("bcl_full.txt.gz")),
        entry("bew", "bew", "bew", 39_886, 167_406L, fileStem = "bew_full_v2", legacyFileNames = listOf("bew_full.txt.gz")),
        entry("bjn", "bjn", "bjn", 132_196, 572_974L, fileStem = "bjn_full_v2", legacyFileNames = listOf("bjn_full.txt.gz")),
        entry("btm", "btm", "btm", 22089, 84664L),
        entry("bug", "bug", "bug", 45_965, 196_394L, fileStem = "bug_full_v2", legacyFileNames = listOf("bug_full.txt.gz")),
        entry("cbk", "cbk", "cbk", 25598, 102880L),
        entry("ch", "ch", "ch", 11_774, 52_944L, fileStem = "ch_full_v2", legacyFileNames = listOf("ch_full.txt.gz")),
        entry("chy", "chy", "chy", 4519, 20370L),
        entry("dag", "dag", "dag", 85_479, 344_440L, fileStem = "dag_full_v2", legacyFileNames = listOf("dag_full.txt.gz")),
        entry("dga", "dga", "dga", 55297, 212817L),
        entry("diq", "diq", "diq", 108534, 412484L),
        entry("din", "din", "din", 37_754, 165_612L, fileStem = "din_full_v2", legacyFileNames = listOf("din_full.txt.gz")),
        entry("dtp", "dtp", "dtp", 52_314, 217_756L, fileStem = "dtp_full_v2", legacyFileNames = listOf("dtp_full.txt.gz")),
        entry("eml", "eml", "eml", 24148, 93431L),
        entry("ext", "ext", "ext", 105_163, 418_998L, fileStem = "ext_full_v2", legacyFileNames = listOf("ext_full.txt.gz")),
        entry("fat", "fat", "fat", 33116, 128544L),
        entry("ff", "ff", "ff", 118_882, 495_778L, fileStem = "ff_full_v2", legacyFileNames = listOf("ff_full.txt.gz")),
        entry("vro", "vro", "fiu_vro", 76541, 307812L),
        entry("fon", "fon", "fon", 32_495, 150_279L, fileStem = "fon_full_v2", legacyFileNames = listOf("fon_full.txt.gz")),
        entry("frp", "frp", "frp", 36673, 141948L),
        entry("frr", "frr", "frr", 101843, 460004L),
        entry("gag", "gag", "gag", 42_797, 190_975L, fileStem = "gag_full_v2", legacyFileNames = listOf("gag_full.txt.gz")),
        entry("gcr", "gcr", "gcr", 18341, 75496L),
        entry("gor", "gor", "gor", 39484, 151130L),
        entry("gpe", "gpe", "gpe", 71_603, 292_091L, fileStem = "gpe_full_v2", legacyFileNames = listOf("gpe_full.txt.gz")),
        entry("gsw", "gsw", "gsw", 342_218, 1_810_818L, fileStem = "gsw_full_v2", legacyFileNames = listOf("gsw_full.txt.gz")),
        entry("guc", "guc", "guc", 33436, 123431L),
        entry("guw", "guw", "guw", 26981, 113162L),
        entry("gur", "gur", "gur", 32867, 127842L),
        entry("hif", "hif", "hif", 102_890, 443_837L, fileStem = "hif_full_v2", legacyFileNames = listOf("hif_full.txt.gz")),
        entry("iba", "iba", "iba", 45_541, 191_687L, fileStem = "iba_full_v2", legacyFileNames = listOf("iba_full.txt.gz")),
        entry("ik", "ik", "ik", 6243, 28270L),
        entry("ilo", "ilo", "ilo", 201_622, 951_215L, fileStem = "ilo_full_v2", legacyFileNames = listOf("ilo_full.txt.gz")),
        entry("jam", "jam", "jam", 18737, 73165L),
        entry("kab", "kab", "kab", 19417, 70675L),
        entry("kaj", "kaj", "kaj", 16359, 62910L),
        entry("kbp", "kbp", "kbp", 77_098, 344_694L, fileStem = "kbp_full_v2", legacyFileNames = listOf("kbp_full.txt.gz")),
        entry("kcg", "kcg", "kcg", 23260, 92008L),
        entry("kg", "kg", "kg", 31_423, 133_187L, fileStem = "kg_full_v2", legacyFileNames = listOf("kg_full.txt.gz")),
        entry("kl", "kl", "kl", 411_084, 2_099_221L, fileStem = "kl_full_v2", legacyFileNames = listOf("kl_full.txt.gz")),
        entry("knc", "knc", "knc", 59207, 228592L),
        entry("ksh", "ksh", "ksh", 58956, 240412L),
        entry("kus", "kus", "kus", 39693, 150720L),
        entry("lad", "lad", "lad", 61092, 239763L),
        entry("li", "li", "li", 274_012, 1_433_093L, fileStem = "li_full_v2", legacyFileNames = listOf("li_full.txt.gz")),
        entry("ltg", "ltg", "ltg", 140_779, 611_369L, fileStem = "ltg_full_v2", legacyFileNames = listOf("ltg_full.txt.gz")),
        entry("mad", "mad", "mad", 67_952, 287_578L, fileStem = "mad_full_v2", legacyFileNames = listOf("mad_full.txt.gz")),
        entry("min", "min", "min", 198_734, 847_846L, fileStem = "min_full_v2", legacyFileNames = listOf("min_full.txt.gz")),
        entry("mos", "mos", "mos", 44_819, 183_398L, fileStem = "mos_full_v2", legacyFileNames = listOf("mos_full.txt.gz")),
        entry("mwl", "mwl", "mwl", 61_568, 298_153L, fileStem = "mwl_full_v2", legacyFileNames = listOf("mwl_full.txt.gz")),
        entry("nah", "nah", "nah", 19578, 85816L),
        entry("nds", "nds", "nds", 377047, 1639474L),
        entry("nia", "nia", "nia", 22160, 85502L),
        entry("nn", "nn", "nn", 338726, 1573832L),
        entry("nr", "nr", "nr", 2596, 10264L),
        entry("nup", "nup", "nup", 13289, 51124L),
        entry("nv", "nv", "nv", 22_859, 129_116L, fileStem = "nv_full_v2", legacyFileNames = listOf("nv_full.txt.gz")),
        entry("pag", "pag", "pag", 89_369, 388_764L, fileStem = "pag_full_v2", legacyFileNames = listOf("pag_full.txt.gz")),
        entry("pap", "pap", "pap", 75_866, 383_470L, fileStem = "pap_full_v2", legacyFileNames = listOf("pap_full.txt.gz")),
        entry("pcd", "pcd", "pcd", 53163, 201164L),
        entry("pcm", "pcm", "pcm", 34014, 130317L),
        entry("pdc", "pdc", "pdc", 19805, 79005L),
        entry("pfl", "pfl", "pfl", 70928, 294017L),
        entry("ppl", "ppl", "ppl", 20450, 84413L),
        entry("pwn", "pwn", "pwn", 14079, 55742L),
        entry("rmy", "rmy", "rmy", 14039, 56735L),
        entry("sco", "sco", "sco", 124_327, 604_418L, fileStem = "sco_full_v2", legacyFileNames = listOf("sco_full.txt.gz")),
        entry("sg", "sg", "sg", 17_770, 82_058L, fileStem = "sg_full_v2", legacyFileNames = listOf("sg_full.txt.gz")),
        entry("srn", "srn", "srn", 13_158, 62_285L, fileStem = "srn_full_v2", legacyFileNames = listOf("srn_full.txt.gz")),
        entry("stq", "stq", "stq", 53855, 225866L),
        entry("szl", "szl", "szl", 391_119, 1_781_957L, fileStem = "szl_full_v2", legacyFileNames = listOf("szl_full.txt.gz")),
        entry("szy", "szy", "szy", 89_981, 328_766L, fileStem = "szy_full_v2", legacyFileNames = listOf("szy_full.txt.gz")),
        entry("tay", "tay", "tay", 26311, 95342L),
        entry("tdd", "tdd", "tdd", 4795, 21062L),
        entry("tly", "tly", "tly", 13902, 56250L),
        entry("trv", "trv", "trv", 53084, 193302L),
        entry("tum", "tum", "tum", 119_634, 510_998L, fileStem = "tum_full_v2", legacyFileNames = listOf("tum_full.txt.gz")),
        entry("vep", "vep", "vep", 112_641, 474_668L, fileStem = "vep_full_v2", legacyFileNames = listOf("vep_full.txt.gz")),
        entry("vls", "vls", "vls", 110_574, 473_232L, fileStem = "vls_full_v2", legacyFileNames = listOf("vls_full.txt.gz")),
        entry("war", "war", "war", 176_378, 805_287L, fileStem = "war_full_v2", legacyFileNames = listOf("war_full.txt.gz")),
        entry("za", "za", "za", 8_406, 40_721L, fileStem = "za_full_v2", legacyFileNames = listOf("za_full.txt.gz")),
        entry("zea", "zea", "zea", 54082, 219023L),
        entry("alt", "alt", "alt", 58_426, 303_408L, fileStem = "alt_full_v2", legacyFileNames = listOf("alt_full.txt.gz")),
        entry("inh", "inh", "inh", 41800, 184176L),
        entry("kaa", "kaa", "kaa", 2057, 10803L),
        entry("koi", "koi", "koi", 24466, 116125L),
        entry("krc", "krc", "krc", 96_191, 491_269L, fileStem = "krc_full_v2", legacyFileNames = listOf("krc_full.txt.gz")),
        entry("lbe", "lbe", "lbe", 7013, 32751L),
        entry("mdf", "mdf", "mdf", 42_584, 218_453L, fileStem = "mdf_full_v2", legacyFileNames = listOf("mdf_full.txt.gz")),
        entry("mrj", "mrj", "mrj", 49_473, 258_948L, fileStem = "mrj_full_v2", legacyFileNames = listOf("mrj_full.txt.gz")),
        entry("rsk", "rsk", "rsk", 70061, 305125L),
        entry("rue", "rue", "rue", 165745, 723521L),
        entry("anp", "anp", "anp", 42532, 206500L),
        entry("awa", "awa", "awa", 37606, 180271L),
        entry("dty", "dty", "dty", 68505, 321580L),
        entry("new", "new", "new", 211_833, 1_165_118L, fileStem = "new_full_v2", legacyFileNames = listOf("new_full.txt.gz")),
        entry("ary", "ary", "ary", 150_824, 624_904L, fileStem = "ary_full_v2", legacyFileNames = listOf("ary_full.txt.gz")),
        entry("azb", "azb", "azb", 340066, 1386976L),
        entry("glk", "glk", "glk", 69089, 275870L),
        entry("mzn", "mzn", "mzn", 84_601, 344_984L, fileStem = "mzn_full_v2", legacyFileNames = listOf("mzn_full.txt.gz")),
        entry("pnb", "pnb", "pnb", 491982, 2050264L),
        entry("skr", "skr", "skr", 211_737, 892_366L, fileStem = "skr_full_v2", legacyFileNames = listOf("skr_full.txt.gz")),
        entry("blk", "blk", "blk", 393462, 2821064L),
        entry("mnw", "mnw", "mnw", 538957, 4152310L),
        entry("rki", "rki", "rki", 270_008, 1_802_756L, fileStem = "rki_full_v2", legacyFileNames = listOf("rki_full.txt.gz")),
        entry("shn", "shn", "shn", 239802, 1856358L),
        entry("hyw", "hyw", "hyw", 277_499, 1_266_263L, fileStem = "hyw_full_v2", legacyFileNames = listOf("hyw_full.txt.gz")),
        entry("pnt", "pnt", "pnt", 8208, 39993L),
        entry("shi", "shi", "shi", 23090, 103083L),
        entry("shi_latn", "shi_latn", "shi", 23094, 87492L, suffix = "latn"),
        entry("tig", "tig", "tig", 93_339, 394_064L, fileStem = "tig_full_v2", legacyFileNames = listOf("tig_full.txt.gz")),
        entry("tcy", "tcy", "tcy", 73_859, 399_697L, fileStem = "tcy_full_v2", legacyFileNames = listOf("tcy_full.txt.gz")),
        entry("xmf", "xmf", "xmf", 175_328, 809_958L, fileStem = "xmf_full_v2", legacyFileNames = listOf("xmf_full.txt.gz")),
        entry("dz", "dz", "dz", 11_899, 53_114L, fileStem = "dz_full_v2", legacyFileNames = listOf("dz_full.txt.gz")),
        entry("avk", "avk", "avk", 84354, 336013L),
        entry("ie", "ie", "ie", 18253, 78009L),
        entry("io", "io", "io", 84056, 339090L),
        entry("lfn", "lfn", "lfn", 57197, 223971L),
        entry("nov", "nov", "nov", 17163, 65549L),
        entry("qya", "qya", "qya", 7443, 21303L),
        entry("vo", "vo", "vo", 55046, 254064L),
        entry("sat", "sat", "sat", 206_428, 926_116L, fileStem = "sat_full_v2", legacyFileNames = listOf("sat_full.txt.gz")),
        entry("mni", "mni", "mni", 61810, 275608L),
        // --- Lists for the corpus languages (CorpusLanguages) and for languages
        // the registry or Keyman already had but no list was offered for. ---
        entry("ber", "ber", "ber", 81160, 312491L),
        entry("swc", "swc", "swc", 26579, 96340L),
        entry("nnb", "nnb", "nnb", 67_663, 287_450L, fileStem = "nnb_full_v2", legacyFileNames = listOf("nnb_full.txt.gz")),
        entry("gos", "gos", "gos", 8016, 30828L),
        entry("kzj", "kzj", "kzj", 5726, 21770L),
        entry("prg", "prg", "prg", 5972, 24087L),
        entry("rif", "rif", "rif", 4072, 15333L),
        entry("ota", "ota", "ota", 3296, 13555L),
        entry("ota_latn", "ota_latn", "ota", 7378, 28697L, suffix = "latn"),
        entry("arq", "arq", "arq", 3499, 13719L),
        entry("hrx", "hrx", "hrx", 1967, 7622L),
        entry("kha", "kha", "kha", 21_765, 96_281L, fileStem = "kha_full_v2", legacyFileNames = listOf("kha_full.txt.gz")),
        entry("gcf", "gcf", "gcf", 1166, 4308L),
        entry("swg", "swg", "swg", 4862, 18226L),
        entry("orv", "orv", "orv", 1987, 8491L),
        entry("bzt", "bzt", "bzt", 1271, 4539L),
        entry("sdh", "sdh", "sdh", 1863, 7292L),
        entry("rom", "rom", "rom", 49_723, 212_047L, fileStem = "rom_full_v2", legacyFileNames = listOf("rom_full.txt.gz")),
        entry("enm", "enm", "enm", 1457, 5144L),
        entry("mus", "mus", "mus", 1358, 5351L),
        entry("fkv", "fkv", "fkv", 1341, 5726L),
        entry("pau", "pau", "pau", 5_871, 25_981L, fileStem = "pau_full_v2", legacyFileNames = listOf("pau_full.txt.gz")),
        entry("ckt", "ckt", "ckt", 1853, 10620L),
        entry("thv", "thv", "thv", 1026, 3745L),
        entry("isv_latn", "isv_latn", "isv", 1997, 7634L, suffix = "latn"),
        entry("bs", "bs", "bs", 385260, 1337778L),
        entry("rn", "rn", "rn", 149_210, 662_017L, fileStem = "rn_full_v2", legacyFileNames = listOf("rn_full.txt.gz")),
        entry("sh", "sh", "sh", 403950, 1604417L),
        entry("tw", "tw", "tw", 91_402, 383_456L, fileStem = "tw_full_v2", legacyFileNames = listOf("tw_full.txt.gz")),
        entry("lmo", "lmo", "lmo", 313_502, 1_420_600L, fileStem = "lmo_full_v2", legacyFileNames = listOf("lmo_full.txt.gz")),
        entry("quc", "quc", "quc", 23_628, 100_117L, fileStem = "quc_full_v2", legacyFileNames = listOf("quc_full.txt.gz")),
        entry("tpi", "tpi", "tpi", 23_500, 105_776L, fileStem = "tpi_full_v2", legacyFileNames = listOf("tpi_full.txt.gz")),
        entry("igl", "igl", "igl", 42191, 163792L),
        entry("kai", "kai", "kai", 8192, 33132L),
        entry("kge", "kge", "kge", 15993, 67412L),
        entry("bdr", "bdr", "bdr", 5535, 20026L),
        entry("nds_nl", "nds_nl", "nds_nl", 160_049, 688_283L, fileStem = "nds_nl_full_v2", legacyFileNames = listOf("nds_nl_full.txt.gz")),
        entry("roa_tara", "roa_tara", "roa_tara", 73547, 273713L),
        entry("cu", "cu", "cu", 14707, 72472L),
        entry("pi", "pi", "pi", 1446, 8267L),
        entry("arz", "arz", "arz", 160_343, 753_962L, fileStem = "arz_full_v2", legacyFileNames = listOf("arz_full.txt.gz")),
        entry("nap", "nap", "nap", 124836, 494568L),
        entry("pam", "pam", "pam", 150000, 642283L),
        entry("grc", "grc", "grc", 3774, 18504L),
        entry("dar", "dar", "dar", 2842, 13274L),
        entry("nus_latn", "nus-Latn", "nus", 2802, 9948L),
        entry("hoc_latn", "hoc-Latn", "hoc", 2347, 9229L, suffix = "latn"),
        entry("ttq_latn", "ttq-Latn", "ttq", 1492, 5844L),
        entry("hbo", "hbo", "hbo", 1101, 3810L),
        entry("ccp", "ccp", "ccp", 6627, 26306L),
        entry("syr", "syr", "syr", 43785, 146536L),
        entry("got_goth", "got-Goth", "got", 10877, 53556L),
        entry("blo_latn", "blo-Latn", "blo", 2022, 8902L),
        entry("ang", "ang", "ang", 69735, 280819L),
        entry("cdo_latn", "cdo-Latn-030", "cdo", 35459, 303116L),
        entry("hak_latn", "hak-Latn-030", "hak", 25789, 188687L),
        entry("nan_latn", "nan-Latn", "nan", 310331, 1285434L),
        entry("be_tarask", "be_tarask", "be_x_old", 653151, 3005635L),
        // Another code for a language that already has a list: same file.
        entry("azj_latn", "azj-Latn", "az", 814123, 3616285L),
        entry("azj_latn_az", "azj-Latn-AZ", "az", 814123, 3616285L),
        entry("pes_arab", "pes-Arab", "fa", 363989, 1434152L),
        entry("prs", "prs", "fa", 363989, 1434152L),
        entry("khk_cyrl", "khk-Cyrl", "mn", 187_074, 1_074_182L, fileStem = "mn_full_v2", legacyFileNames = listOf("mn_full.txt.gz")),
        entry("khk_cyrl_mn", "khk-Cyrl-MN", "mn", 187_074, 1_074_182L, fileStem = "mn_full_v2", legacyFileNames = listOf("mn_full.txt.gz")),
        entry("nan_latn_035", "nan-Latn-035", "nan", 310331, 1285434L),
        entry("nan_latn_tw", "nan-Latn-TW", "nan", 310331, 1285434L),
        entry("bn_in", "bn-IN", "bn", 451348, BN_GZ_BYTES, fileStem = BN_STEM, legacyFileNames = BN_LEGACY),
        entry("tzm", "tzm", "ber", 81160, 312491L),
        entry("tzm_tfng", "tzm-Tfng", "zgh", 58474, 243558L),
        entry("dz_bt", "dz-BT", "dz", 11_899, 53_114L, fileStem = "dz_full_v2", legacyFileNames = listOf("dz_full.txt.gz")),
        entry("dgr_latn", "dgr-Latn", "dgr", 9916, 43423L),
        // A language's list carried into its other script (transliterate_wordlist.py).
        entry("sr_latn", "sr-Latn", "sr", 224790, 824744L, suffix = "latn"),
        entry("bs_cyrl", "bs-Cyrl", "bs", 383957, 1539125L, suffix = "cyrl"),
        entry("kk_latn", "kk-Latn", "kk", 270978, 1050392L, suffix = "latn"),
        entry("be_latn", "be-Latn", "be", 278764, 1119638L, suffix = "latn"),
        entry("zgh_latn", "zgh-Latn", "zgh", 58455, 205783L, suffix = "latn"),
        // Counted from eBible and Glot500 text (import_corpus_words.py).
        entry("uz_cyrl", "uzn-Cyrl", "uz", 271798, 1172868L, suffix = "cyrl"),
        entry("kaa_latn", "kaa-Latn", "kaa", 52652, 206299L, suffix = "latn"),
        entry("acm", "acm", "acm", 86141, 313282L),
        entry("afb", "afb", "afb", 218035, 793127L),
        entry("ajp", "ajp", "ajp", 142979, 519298L),
        entry("aln", "aln", "aln", 29357, 107618L),
        entry("apc", "apc", "apc", 135119, 490648L),
        entry("arn", "arn", "arn", 21_479, 89_658L, fileStem = "arn_full_v2", legacyFileNames = listOf("arn_full.txt.gz")),
        entry("bba", "bba", "bba", 13687, 51365L),
        entry("bem", "bem", "bem", 130_434, 532_209L, fileStem = "bem_full_v2", legacyFileNames = listOf("bem_full.txt.gz")),
        entry("dgr", "dgr", "dgr", 9916, 43423L),
        entry("emp", "emp", "emp", 38_409, 172_900L, fileStem = "emp_full_v2", legacyFileNames = listOf("emp_full.txt.gz")),
        entry("kac", "kac", "kac", 21_957, 89_468L, fileStem = "kac_full_v2", legacyFileNames = listOf("kac_full.txt.gz")),
        entry("kam", "kam", "kam", 25_616, 105_536L, fileStem = "kam_full_v2", legacyFileNames = listOf("kam_full.txt.gz")),
        entry("kek", "kek", "kek", 28_236, 116_284L, fileStem = "kek_full_v2", legacyFileNames = listOf("kek_full.txt.gz")),
        entry("kjh", "kjh", "kjh", 48143, 200934L),
        entry("knf", "knf", "knf", 13488, 47279L),
        entry("nch", "nch", "nch", 32628, 132096L),
        entry("ngu", "ngu", "ngu", 33_708, 149_478L, fileStem = "ngu_full_v2", legacyFileNames = listOf("ngu_full.txt.gz")),
        entry("nop", "nop", "nop", 17302, 65675L),
        entry("tgo", "tgo", "tgo", 10900, 43110L),
        entry("umb", "umb", "umb", 37_075, 155_994L, fileStem = "umb_full_v2", legacyFileNames = listOf("umb_full.txt.gz")),
        // --- AOSP LatinIME's lists, preferred over the counted ones above. ---
        // Where a language has two regions, the first listed is its default.
        // Languages that had no list until the web corpora: built, like the rebuilt
        // lists above, by the data repo's build_corpus_packs.py.
        entry("acf", "acf", "acf", 5_372, 23_458L),
        entry("ach", "ach", "ach", 9_805, 36_914L),
        entry("ada", "ada", "ada", 25_043, 103_075L),
        entry("ahk", "ahk", "ahk", 1_029, 4_573L),
        entry("alz", "alz", "alz", 6_540, 25_297L),
        entry("bas", "bas", "bas", 4_256, 17_275L),
        entry("bci", "bci", "bci", 7_441, 32_747L),
        entry("bim", "bim", "bim", 3_151, 12_053L),
        entry("bqc", "bqc", "bqc", 5_842, 23_508L),
        entry("btx", "btx", "btx", 11_333, 47_834L),
        entry("bum", "bum", "bum", 5_448, 23_514L),
        entry("bus", "bus", "bus", 6_833, 27_610L),
        entry("cab", "cab", "cab", 10_710, 49_293L),
        entry("cce", "cce", "cce", 4_602, 19_603L),
        entry("cjk", "cjk", "cjk", 23_983, 98_734L),
        entry("cnh", "cnh", "cnh", 31_942, 135_715L),
        entry("crs", "crs", "crs", 8_597, 38_550L),
        entry("dik", "dik", "dik", 24_197, 101_702L),
        entry("djk", "djk", "djk", 1_971, 9_612L),
        entry("dyu", "dyu", "dyu", 6_474, 28_681L),
        entry("fuv", "fuv", "fuv", 85_065, 329_487L),
        entry("gbm", "gbm", "gbm", 5_155, 23_229L),
        entry("gui", "gui", "gui", 8_604, 37_552L),
        entry("gvl", "gvl", "gvl", 3_055, 12_166L),
        entry("hil", "hil", "hil", 40_639, 184_171L),
        entry("hmn", "hmn", "hmn", 25_315, 120_679L),
        entry("hne", "hne", "hne", 25_536, 119_986L),
        entry("ho", "ho", "ho", 2_882, 12_773L),
        entry("hvn", "hvn", "hvn", 3_262, 12_809L),
        entry("ibb", "ibb", "ibb", 8_674, 37_553L),
        entry("iso", "iso", "iso", 10_519, 48_186L),
        entry("kea", "kea", "kea", 16_758, 69_689L),
        entry("kj", "kj", "kj", 22_157, 94_289L),
        entry("kmb", "kmb", "kmb", 6_496, 28_352L),
        entry("kri", "kri", "kri", 4_680, 21_469L),
        entry("ks", "ks", "ks_arab", 15_266, 65_766L),
        entry("ksw", "ksw", "ksw", 6_968, 30_620L),
        entry("ktu", "ktu", "ktu", 2_879, 11_738L),
        entry("kum", "kum", "kum", 32_987, 166_391L),
        entry("laj", "laj", "laj", 4_113, 14_904L),
        entry("lhu", "lhu", "lhu", 2_609, 11_780L),
        entry("lrc", "lrc", "lrc", 12_713, 57_414L),
        entry("lu", "lu", "lu", 26_766, 107_236L),
        entry("lua", "lua", "lua", 19_978, 80_680L),
        entry("luo", "luo", "luo", 33_884, 125_316L),
        entry("lus", "lus", "lus", 90_283, 388_307L),
        entry("mag", "mag", "mag", 12_833, 65_969L),
        entry("mak", "mak", "mak", 11_733, 48_846L),
        entry("mam", "mam", "mam", 19_637, 80_287L),
        entry("mas", "mas", "mas", 9_785, 38_624L),
        entry("meo", "meo", "meo", 17_911, 69_814L),
        entry("mfe", "mfe", "mfe", 5_037, 22_230L),
        entry("mgh", "mgh", "mgh", 23_890, 101_877L),
        entry("msi", "msi", "msi", 8_759, 34_863L),
        entry("nij", "nij", "nij", 4_199, 17_600L),
        entry("nog", "nog", "nog", 15_021, 70_626L),
        entry("nyu", "nyu", "nyu", 8_325, 35_394L),
        entry("nzi", "nzi", "nzi", 9_841, 42_744L),
        entry("pck", "pck", "pck", 27_467, 109_552L),
        entry("plt", "plt", "plt", 121_318, 602_067L),
        entry("quh", "quh", "quh", 14_840, 62_571L),
        entry("quy", "quy", "quy", 235_769, 1_043_859L),
        entry("raj", "raj", "raj", 3_448, 15_261L),
        entry("rcf", "rcf", "rcf", 12_441, 51_497L),
        entry("sda", "sda", "sda", 10_156, 40_050L),
        entry("seh", "seh", "seh", 11_074, 47_510L),
        entry("spp", "spp", "spp", 2_276, 10_519L),
        entry("srm", "srm", "srm", 1_803, 8_889L),
        entry("tab", "tab", "tab", 18_716, 93_915L),
        entry("taj", "taj", "taj", 5_874, 28_662L),
        entry("taq", "taq", "taq", 20_138, 87_858L),
        entry("tbz", "tbz", "tbz", 18_280, 82_197L),
        entry("tdx", "tdx", "tdx", 6_412, 26_818L),
        entry("teo", "teo", "teo", 10_663, 42_212L),
        entry("tiv", "tiv", "tiv", 5_760, 26_399L),
        entry("tvl", "tvl", "tvl", 4_570, 21_335L),
        entry("tyz", "tyz", "tyz", 27_963, 125_364L),
        entry("wal", "wal", "wal", 11_886, 47_208L),
        entry("yua", "yua", "yua", 26_168, 120_844L),
        entry("zne", "zne", "zne", 5_166, 21_673L),
        aosp("cs_aosp", "cs", "cs", 171_544, 813_469L),
        aosp("da_aosp", "da", "da", 178_449, 869_312L),
        aosp("de_aosp", "de", "de", 205_888, 1_101_134L),
        aosp("el_aosp", "el", "el", 184_303, 981_672L),
        aosp(
            "en_aosp", "en", "en", 160_668, 748_505L,
            variantRes = R.string.core_pred_wordlist_variant_us_label,
        ),
        aosp(
            "en_gb_aosp", "en", "en", 157_423, 734_107L,
            variantRes = R.string.core_pred_wordlist_variant_uk_label, fileStem = "en_gb_aosp",
        ),
        // AOSP has no Canadian or Australian list. These are its en_US and
        // LineageOS's en_GB-based en_AU, respelled with the English Speller
        // Database's regional spellings and AOSP's frequencies kept
        // (wmkeyboard-data scripts/import_aosp_regional_en.py).
        aosp(
            "en_ca_aosp", "en", "en", 160_541, 748_592L,
            variantRes = R.string.core_pred_wordlist_variant_canada_label, fileStem = "en_ca_aosp",
        ),
        aosp(
            "en_au_aosp", "en", "en", 157_418, 732_940L,
            variantRes = R.string.core_pred_wordlist_variant_australia_label, fileStem = "en_au_aosp",
        ),
        aosp("es_aosp", "es", "es", 236_193, 977_769L),
        aosp("fi_aosp", "fi", "fi", 223_363, 1_093_376L),
        aosp("fr_aosp", "fr", "fr", 190_113, 950_440L),
        aosp("he_aosp", "he", "he", 94_799, 389_909L),
        aosp("hr_aosp", "hr", "hr", 210_081, 858_496L),
        aosp("it_aosp", "it", "it", 172_831, 802_881L),
        aosp("lt_aosp", "lt", "lt", 198_160, 837_749L),
        aosp("lv_aosp", "lv", "lv", 200_570, 813_274L),
        aosp("nb_aosp", "nb", "nb", 171_008, 817_188L),
        aosp("nl_aosp", "nl", "nl", 178_444, 900_291L),
        aosp("pl_aosp", "pl", "pl", 195_099, 934_477L),
        aosp(
            "pt_aosp", "pt", "pt", 218_456, 952_642L,
            variantRes = R.string.core_pred_wordlist_variant_europe_label,
        ),
        aosp(
            "pt_br_aosp", "pt", "pt_br", 170_043, 756_696L,
            variantRes = R.string.core_pred_wordlist_variant_brazil_label,
        ),
        aosp("ro_aosp", "ro", "ro", 1_125_199, 3_229_694L),
        aosp("ru_aosp", "ru", "ru", 220_485, 1_195_780L),
        aosp("sl_aosp", "sl", "sl", 59_998, 260_336L),
        aosp("sr_aosp", "sr", "sr", 191_608, 895_311L),
        aosp("sv_aosp", "sv", "sv", 196_739, 969_689L),
        aosp("tr_aosp", "tr", "tr", 180_841, 792_381L),
    )

    init {
        check(entries.map { it.id }.toSet().size == entries.size) { "entry ids must be unique" }
    }

    fun byId(id: String): DictionaryEntry? = entries.firstOrNull { it.id == id }

    /**
     * The entry whose list is called [name], gzip or not (`de_full.txt.gz`,
     * `de_full.txt`). Several entries can share a file only by sharing a
     * language, so the first is as good as any.
     */
    fun byFileName(name: String): DictionaryEntry? {
        val gz = if (name.endsWith(".txt")) "$name.gz" else name
        return entries.firstOrNull { it.fileName == gz }
    }

    /**
     * The entry that once published its list as [name] (see
     * [DictionaryEntry.legacyFileNames]), gzip or not. An offline import takes
     * such a file only when it was not given the current one beside it.
     */
    fun byLegacyFileName(name: String): DictionaryEntry? {
        val gz = if (name.endsWith(".txt")) "$name.gz" else name
        return entries.firstOrNull { gz in it.legacyFileNames }
    }

    fun forLanguage(langId: String): List<DictionaryEntry> =
        entries.filter { it.languageId == langId }

    /**
     * The list [langId] downloads unless the user picks another: its AOSP list
     * where it has one, else its counted list, and of either the first region
     * listed (Europe for Portuguese, the US for English).
     */
    fun preferred(langId: String): DictionaryEntry? =
        forLanguage(langId).minByOrNull { it.source.ordinal }
}
