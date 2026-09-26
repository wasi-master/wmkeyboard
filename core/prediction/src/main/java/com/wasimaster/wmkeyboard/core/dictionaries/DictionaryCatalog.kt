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
     * keyboard shipped one for. Curated rather than counted: a couple of
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
) {
    /** Where the list is fetched from: the data repository, wherever [ServiceRepo.DATA] points. */
    val url: String
        get() = ServiceEndpoints.repo(ServiceRepo.DATA)
            .rawUrl("data/$repoCode/${fileStem ?: "${repoCode}_$suffix"}.txt.gz")
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
    ) = DictionaryEntry(id, languageId, repoCode, totalWordCount, approxGzBytes, variantRes, suffix, fileStem)

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
        entry("ady", "ady", "ady", 20_195, 94_516L),
        entry("af", "af", "af", 288_488, 1_390_628L),
        entry("ak", "ak", "ak", 3_893, 15_910L),
        entry("am", "am", "am", 91_579, 412_108L),
        entry("ar", "ar", "ar", 2_487_447, 9_706_359L),
        entry("av", "av", "av", 53_036, 241_190L),
        entry("ay", "ay", "ay", 34_729, 132_886L),
        entry("az", "az", "az", 814_123, 3_616_285L),
        entry("ba", "ba", "ba", 159_186, 790_316L),
        entry("be", "be", "be", 349_962, 1_737_920L),
        entry("bg", "bg", "bg", 1_055_844, 4_924_294L),
        entry("bho", "bho", "bho", 7_304, 49_129L),
        entry("bi", "bi", "bi", 6_558, 25_592L),
        entry("bm", "bm", "bm", 13_745, 56_151L),
        entry("bn", "bn", "bn", 451_348, 1_675_761L),
        entry("bo", "bo", "bo", 19_157, 309_528L),
        entry("br", "br", "br", 128_028, 558_000L),
        entry("brx", "brx", "brx", 10_900, 74_842L),
        entry("bua", "bua", "bxr", 60_442, 263_262L),
        entry("ca", "ca", "ca", 184_216, 669_028L),
        entry("ce", "ce", "ce", 73_434, 332_281L),
        entry("ceb", "ceb", "ceb", 383_771, 1_344_763L),
        entry("chr", "chr", "chr", 6_500, 28_870L),
        entry("ckb", "ckb", "ckb", 201_528, 863_815L),
        entry("co", "co", "co", 30_522, 119_761L),
        entry("crh", "crh", "crh", 1_486, 7_819L),
        entry("cs", "cs", "cs", 1_719_446, 6_998_615L),
        entry("csb", "csb", "csb", 63_525, 255_227L),
        entry("cv", "cv", "cv", 35_945, 172_438L),
        entry("cy", "cy", "cy", 123_099, 545_010L),
        entry("da", "da", "da", 685_713, 3_082_306L),
        entry("de", "de", "de", 1_153_001, 5_474_930L),
        entry("doi", "doi", "doi", 2_539, 17_410L),
        entry("dsb", "dsb", "dsb", 46_802, 187_995L),
        entry("dv", "dv", "dv", 100_368, 493_127L),
        entry("ee", "ee", "ee", 14_882, 48_645L),
        entry("el", "el", "el", 1_168_400, 5_726_781L),
        entry("en", "en", "en", 1_636_178, 6_753_423L),
        entry("eo", "eo", "eo", 386_958, 1_630_975L),
        entry("es", "es", "es", 1_190_537, 4_833_505L),
        entry("et", "et", "et", 1_000_180, 4_084_505L),
        entry("eu", "eu", "eu", 389_587, 1_584_930L),
        entry("fa", "fa", "fa", 363_989, 1_426_839L),
        entry("fi", "fi", "fi", 2_488_573, 10_584_625L),
        entry("fj", "fj", "fj", 10_583, 44_024L),
        entry("fo", "fo", "fo", 231_427, 1_049_407L),
        entry("fr", "fr", "fr", 825_643, 3_464_732L),
        entry("fur", "fur", "fur", 53_308, 200_165L),
        entry("fy", "fy", "fy", 138_753, 633_511L),
        entry("ga", "ga", "ga", 188_674, 810_882L),
        entry("gd", "gd", "gd", 71_344, 291_138L),
        entry("gl", "gl", "gl", 268_205, 1_164_344L),
        entry("gn", "gn", "gn", 30_178, 127_355L),
        entry("gu", "gu", "gu", 172_335, 834_724L),
        entry("gv", "gv", "gv", 20_491, 87_175L),
        entry("ha", "ha", "ha", 38_571, 200_967L),
        entry("haw", "haw", "haw", 19_109, 79_128L),
        entry("he", "he", "he", 1_053_149, 4_291_295L),
        entry("hi", "hi", "hi", 242_911, 1_313_058L),
        entry("hr", "hr", "hr", 1_497_358, 5_787_914L),
        entry("hsb", "hsb", "hsb", 110_141, 451_823L),
        entry("ht", "ht", "ht", 36_690, 159_068L),
        entry("hu", "hu", "hu", 3_138_382, 13_176_302L),
        entry("hy", "hy", "hy", 394_817, 1_782_177L),
        entry("ia", "ia", "ia", 50_502, 208_771L),
        entry("id", "id", "id", 354_661, 1_444_041L),
        entry("ig", "ig", "ig", 13_929, 78_042L),
        entry("is", "is", "is", 256_264, 1_094_639L),
        entry("it", "it", "it", 791_470, 3_240_903L),
        entry("iu", "iu", "iu", 9_978, 53_532L),
        entry("ja", "ja", "ja", 213_393, 922_434L),
        entry("jbo", "jbo", "jbo", 2_412, 6_319L),
        entry("jv", "jv", "jv", 144_058, 591_696L),
        entry("ka", "ka", "ka", 335_324, 1_639_061L),
        entry("kbd", "kbd", "kbd", 43_188, 193_415L),
        entry("ki", "ki", "ki", 86_175, 329_664L),
        entry("kk", "kk", "kk", 361_083, 1_788_094L),
        entry("km", "km", "km", 9_427, 155_670L),
        entry("kn", "kn", "kn", 549_589, 2_884_310L),
        entry("ko", "ko", "ko", 675_331, 2_838_560L),
        // Devanagari, from gom.wikipedia; data/kok holds the Romi list.
        entry("kok", "kok", "gom", 165_192, 774_191L),
        entry("ku", "ku", "ku", 145_280, 577_616L),
        entry("kv", "kv", "kv", 49_462, 228_954L),
        entry("kw", "kw", "kw", 49_355, 194_052L),
        entry("ky", "ky", "ky", 336_397, 1_691_959L),
        entry("la", "la", "la", 184_648, 716_296L),
        entry("lb", "lb", "lb", 152_991, 734_198L),
        entry("lez", "lez", "lez", 59_601, 267_993L),
        entry("lg", "lg", "lg", 187_969, 735_891L),
        entry("lij", "lij", "lij", 147_785, 567_240L),
        entry("lld", "lld", "lld", 163_062, 632_585L),
        entry("ln", "ln", "ln", 4_865, 17_803L),
        entry("lo", "lo", "lo", 33_334, 333_101L),
        entry("lt", "lt", "lt", 321_660, 1_204_167L),
        entry("lv", "lv", "lv", 330_323, 1_403_969L),
        entry("mai", "mai", "mai", 49_795, 354_711L),
        entry("mg", "mg", "mg", 184_155, 728_748L),
        entry("chm", "chm", "mhr", 62_439, 285_836L),
        entry("mi", "mi", "mi", 16_654, 67_366L),
        entry("mk", "mk", "mk", 293_121, 1_298_185L),
        entry("ml", "ml", "ml", 250_542, 1_275_671L),
        entry("mn", "mn", "mn", 137_799, 641_531L),
        entry("mr", "mr", "mr", 331_817, 1_721_372L),
        entry("ms", "ms", "ms", 242_279, 1_118_799L),
        entry("mt", "mt", "mt", 79_257, 341_653L),
        entry("my", "my", "my", 49_585, 340_725L),
        entry("myv", "myv", "myv", 71_985, 320_352L),
        entry("nb", "nb", "nb", 387_768, 1_734_759L),
        entry("nd", "nd", "nd", 36_656, 126_306L),
        entry("ne", "ne", "ne", 163_948, 818_119L),
        entry("nl", "nl", "nl", 1_099_506, 5_009_575L),
        entry("nqo", "nqo", "nqo", 51_467, 230_261L),
        entry("nrf", "nrf", "nrm", 33_765, 128_026L),
        entry("nso", "nso", "nso", 26_865, 103_682L),
        entry("ny", "ny", "ny", 4_020, 16_541L),
        entry("oc", "oc", "oc", 154_753, 623_673L),
        entry("om", "om", "om", 11_941, 46_241L),
        entry("or", "or", "or", 72_978, 401_802L),
        entry("os", "os", "os", 12_863, 62_079L),
        entry("pa", "pa", "pa", 226_706, 1_148_074L),
        entry("pl", "pl", "pl", 1_481_466, 6_171_710L),
        entry("pms", "pms", "pms", 47_916, 200_183L),
        entry("ps", "ps", "ps", 146_694, 626_376L),
        entry(
            "pt", "pt", "pt", 763_183, 3_138_081L,
            variantRes = R.string.core_pred_wordlist_variant_europe_label,
        ),
        entry(
            "pt_br", "pt", "pt_br", 847_162, 3_384_245L,
            variantRes = R.string.core_pred_wordlist_variant_brazil_label,
        ),
        entry("qu", "qu", "qu", 20_435, 83_225L),
        entry("rm", "rm", "rm", 49_238, 204_904L),
        entry("ro", "ro", "ro", 1_218_883, 5_040_931L),
        entry("rup", "rup", "roa_rup", 25_337, 100_417L),
        entry("ru", "ru", "ru", 1_236_328, 5_940_540L),
        entry("rw", "rw", "rw", 143_158, 552_327L),
        entry("sa", "sa", "sa", 219_660, 1_356_999L),
        entry("sah", "sah", "sah", 197_782, 912_422L),
        entry("sc", "sc", "sc", 38_576, 151_283L),
        entry("scn", "scn", "scn", 142_482, 539_216L),
        entry("sd", "sd", "sd", 92_822, 369_930L),
        entry("se", "se", "se", 27_671, 126_224L),
        entry("si", "si", "si", 163_154, 811_027L),
        entry("sk", "sk", "sk", 746_834, 2_988_084L),
        entry("sl", "sl", "sl", 842_678, 3_236_603L),
        entry("sm", "sm", "sm", 12_149, 48_536L),
        entry("sn", "sn", "sn", 81_621, 318_116L),
        entry("so", "so", "so", 34_258, 133_508L),
        entry("sq", "sq", "sq", 240_759, 901_232L),
        entry("sr", "sr", "sr", 225_229, 950_408L),
        entry("ss", "ss", "ss", 43_023, 172_299L),
        entry("st", "st", "st", 16_704, 53_082L),
        entry("su", "su", "su", 173_735, 689_687L),
        entry("sv", "sv", "sv", 900_975, 4_079_684L),
        entry("sw", "sw", "sw", 128_718, 527_814L),
        entry("ta", "ta", "ta", 1_874_722, 7_756_983L),
        entry("te", "te", "te", 393_342, 2_061_715L),
        entry("tet", "tet", "tet", 22_227, 87_799L),
        entry("tg", "tg", "tg", 144_177, 692_345L),
        entry("th", "th", "th", 4_024_497, 41_176_795L),
        entry("ti", "ti", "ti", 5_411, 23_047L),
        entry("tk", "tk", "tk", 79_436, 320_667L),
        entry("tl", "tl", "tl", 144_149, 631_911L),
        entry("tlh", "tlh", "tlh", 2_428, 6_843L),
        // Toki Pona's entire vocabulary is ~140 words — the list is complete,
        // not truncated, so it ships despite being far below the usual floor.
        entry("tok", "tok", "tok", 178, 695L),
        entry("tn", "tn", "tn", 33_977, 108_600L),
        entry("to", "to", "to", 10_181, 41_437L),
        entry("tr", "tr", "tr", 2_027_282, 8_051_955L),
        entry("ts", "ts", "ts", 16_508, 54_430L),
        entry("tt", "tt", "tt", 142_022, 702_178L),
        entry("ty", "ty", "ty", 5_164, 21_116L),
        entry("tyv", "tyv", "tyv", 98_879, 451_744L),
        entry("udm", "udm", "udm", 43_500, 202_058L),
        entry("ug", "ug", "ug", 83_651, 374_285L),
        entry("uk", "uk", "uk", 409_369, 1_835_322L),
        entry("ur", "ur", "ur", 154_781, 541_600L),
        entry("uz", "uz", "uz", 176_272, 741_660L),
        entry("ve", "ve", "ve", 13_743, 45_724L),
        entry("vec", "vec", "vec", 205_090, 766_850L),
        entry("vi", "vi", "vi", 605_499, 3_056_250L),
        entry("wa", "wa", "wa", 43_783, 178_143L),
        entry("wo", "wo", "wo", 20_916, 68_267L),
        entry("xal", "xal", "xal", 10_333, 46_484L),
        entry("xh", "xh", "xh", 87_838, 325_602L),
        entry("yi", "yi", "yi", 46_500, 212_440L),
        entry("yo", "yo", "yo", 27_710, 124_495L),
        entry("zgh", "zgh", "zgh", 58_474, 243_558L),
        entry("zu", "zu", "zu", 367_932, 1_490_853L),
        entry("as", "as", "as", 142_052, 708_124L),
        entry("bpy", "bpy", "bpy", 36_838, 181_959L),
        entry("syl", "syl", "syl", 17_135, 73_917L),
        // Romanized/Latin-script transliteration lists — each its own selectable
        // language (e.g. "Bengali (Romanized)"), not a variant of the native one.
        entry("ar_rom", "ar_rom", "ar", 211_291, 867_223L, suffix = "rom"),
        entry("bn_rom", "bn_rom", "bn", 187_030, 708_388L, suffix = "rom"),
        entry("gu_rom", "gu_rom", "gu", 994_681, 2_988_542L, suffix = "rom"),
        entry("hi_rom", "hi_rom", "hi", 1_052_827, 3_357_284L, suffix = "rom"),
        entry("kn_rom", "kn_rom", "kn", 2_717_878, 8_658_966L, suffix = "rom"),
        entry("ml_rom", "ml_rom", "ml", 237_194, 954_305L, suffix = "rom"),
        entry("mr_rom", "mr_rom", "mr", 1_317_776, 4_118_825L, suffix = "rom"),
        entry("ne_rom", "ne_rom", "ne", 170_679, 566_063L, suffix = "rom"),
        entry("pa_rom", "pa_rom", "pa", 426_465, 1_293_538L, suffix = "rom"),
        entry("ru_rom", "ru_rom", "ru", 1_313_808, 5_393_212L, suffix = "rom", fileStem = "russian_rom"),
        entry("si_rom", "si_rom", "si", 989_853, 5_401_632L, suffix = "rom"),
        entry("ta_rom", "ta_rom", "ta", 3_134_887, 10_185_068L, suffix = "rom"),
        entry("te_rom", "te_rom", "te", 2_270_502, 7_234_058L, suffix = "rom"),
        entry("ur_rom", "ur_rom", "ur", 110_489, 507_828L, suffix = "rom"),
        entry("kok_rom", "kok_rom", "kok", 58_449, 220_459L, suffix = "rom"),
        // --- Language expansion: 140 new languages. ---
        entry("ace", "ace", "ace", 27984, 109003L),
        entry("ami", "ami", "ami", 58883, 219840L),
        entry("an", "an", "an", 65018, 263253L),
        entry("ann", "ann", "ann", 8692, 34130L),
        entry("ast", "ast", "ast", 292283, 1268893L),
        entry("atj", "atj", "atj", 18322, 72257L),
        entry("ban", "ban", "ban", 2674, 11436L),
        entry("bar", "bar", "bar", 339781, 1429264L),
        entry("sgs", "sgs", "bat_smg", 96808, 372118L),
        entry("bbc", "bbc", "bbc", 17392, 67610L),
        entry("bcl", "bcl", "bcl", 35209, 126306L),
        entry("bew", "bew", "bew", 31897, 123046L),
        entry("bjn", "bjn", "bjn", 66129, 252069L),
        entry("btm", "btm", "btm", 22089, 84664L),
        entry("bug", "bug", "bug", 1694, 7487L),
        entry("cbk", "cbk", "cbk", 25598, 102880L),
        entry("ch", "ch", "ch", 4149, 17307L),
        entry("chy", "chy", "chy", 4519, 20370L),
        entry("dag", "dag", "dag", 91863, 353385L),
        entry("dga", "dga", "dga", 55297, 212817L),
        entry("diq", "diq", "diq", 108534, 412484L),
        entry("din", "din", "din", 15231, 59924L),
        entry("dtp", "dtp", "dtp", 52898, 204225L),
        entry("eml", "eml", "eml", 24148, 93431L),
        entry("ext", "ext", "ext", 110945, 421806L),
        entry("fat", "fat", "fat", 33116, 128544L),
        entry("ff", "ff", "ff", 5110, 18023L),
        entry("vro", "vro", "fiu_vro", 76541, 307812L),
        entry("fon", "fon", "fon", 23847, 99570L),
        entry("frp", "frp", "frp", 36673, 141948L),
        entry("frr", "frr", "frr", 101843, 460004L),
        entry("gag", "gag", "gag", 29359, 114575L),
        entry("gcr", "gcr", "gcr", 18341, 75496L),
        entry("gor", "gor", "gor", 39484, 151130L),
        entry("gpe", "gpe", "gpe", 76922, 313884L),
        entry("gsw", "gsw", "gsw", 229834, 1039744L),
        entry("guc", "guc", "guc", 33436, 123431L),
        entry("guw", "guw", "guw", 26981, 113162L),
        entry("gur", "gur", "gur", 32867, 127842L),
        entry("hif", "hif", "hif", 62360, 242071L),
        entry("iba", "iba", "iba", 29575, 115578L),
        entry("ik", "ik", "ik", 6243, 28270L),
        entry("ilo", "ilo", "ilo", 26907, 117561L),
        entry("jam", "jam", "jam", 18737, 73165L),
        entry("kab", "kab", "kab", 19417, 70675L),
        entry("kaj", "kaj", "kaj", 16359, 62910L),
        entry("kbp", "kbp", "kbp", 40305, 157881L),
        entry("kcg", "kcg", "kcg", 23260, 92008L),
        entry("kg", "kg", "kg", 1205, 4591L),
        entry("kl", "kl", "kl", 110455, 492788L),
        entry("knc", "knc", "knc", 59207, 228592L),
        entry("ksh", "ksh", "ksh", 58956, 240412L),
        entry("kus", "kus", "kus", 39693, 150720L),
        entry("lad", "lad", "lad", 61092, 239763L),
        entry("li", "li", "li", 156869, 695607L),
        entry("ltg", "ltg", "ltg", 21574, 86061L),
        entry("mad", "mad", "mad", 39176, 151027L),
        entry("min", "min", "min", 145708, 723717L),
        entry("mos", "mos", "mos", 44193, 168731L),
        entry("mwl", "mwl", "mwl", 63640, 259934L),
        entry("nah", "nah", "nah", 19578, 85816L),
        entry("nds", "nds", "nds", 377047, 1639474L),
        entry("nia", "nia", "nia", 22160, 85502L),
        entry("nn", "nn", "nn", 338726, 1573832L),
        entry("nr", "nr", "nr", 2596, 10264L),
        entry("nup", "nup", "nup", 13289, 51124L),
        entry("nv", "nv", "nv", 9653, 50254L),
        entry("pag", "pag", "pag", 13489, 53247L),
        entry("pap", "pap", "pap", 53695, 222161L),
        entry("pcd", "pcd", "pcd", 53163, 201164L),
        entry("pcm", "pcm", "pcm", 34014, 130317L),
        entry("pdc", "pdc", "pdc", 19805, 79005L),
        entry("pfl", "pfl", "pfl", 70928, 294017L),
        entry("ppl", "ppl", "ppl", 20450, 84413L),
        entry("pwn", "pwn", "pwn", 14079, 55742L),
        entry("rmy", "rmy", "rmy", 14039, 56735L),
        entry("sco", "sco", "sco", 123333, 526632L),
        entry("sg", "sg", "sg", 3064, 12643L),
        entry("srn", "srn", "srn", 6716, 27344L),
        entry("stq", "stq", "stq", 53855, 225866L),
        entry("szl", "szl", "szl", 146204, 617678L),
        entry("szy", "szy", "szy", 92948, 332214L),
        entry("tay", "tay", "tay", 26311, 95342L),
        entry("tdd", "tdd", "tdd", 4795, 21062L),
        entry("tly", "tly", "tly", 13902, 56250L),
        entry("trv", "trv", "trv", 53084, 193302L),
        entry("tum", "tum", "tum", 77785, 313644L),
        entry("vep", "vep", "vep", 113585, 469155L),
        entry("vls", "vls", "vls", 114628, 476895L),
        entry("war", "war", "war", 99224, 433779L),
        entry("za", "za", "za", 4549, 19311L),
        entry("zea", "zea", "zea", 54082, 219023L),
        entry("alt", "alt", "alt", 41282, 193700L),
        entry("inh", "inh", "inh", 41800, 184176L),
        entry("kaa", "kaa", "kaa", 2057, 10803L),
        entry("koi", "koi", "koi", 24466, 116125L),
        entry("krc", "krc", "krc", 57441, 258680L),
        entry("lbe", "lbe", "lbe", 7013, 32751L),
        entry("mdf", "mdf", "mdf", 26421, 120445L),
        entry("mrj", "mrj", "mrj", 39362, 182978L),
        entry("rsk", "rsk", "rsk", 70061, 305125L),
        entry("rue", "rue", "rue", 165745, 723521L),
        entry("anp", "anp", "anp", 42532, 206500L),
        entry("awa", "awa", "awa", 37606, 180271L),
        entry("dty", "dty", "dty", 68505, 321580L),
        entry("new", "new", "new", 199375, 1085868L),
        entry("ary", "ary", "ary", 158198, 643894L),
        entry("azb", "azb", "azb", 340066, 1386976L),
        entry("glk", "glk", "glk", 69089, 275870L),
        entry("mzn", "mzn", "mzn", 88550, 351940L),
        entry("pnb", "pnb", "pnb", 491982, 2050264L),
        entry("skr", "skr", "skr", 223837, 913730L),
        entry("blk", "blk", "blk", 393462, 2821064L),
        entry("mnw", "mnw", "mnw", 538957, 4152310L),
        entry("rki", "rki", "rki", 254879, 1717041L),
        entry("shn", "shn", "shn", 239802, 1856358L),
        entry("hyw", "hyw", "hyw", 275125, 1221053L),
        entry("pnt", "pnt", "pnt", 8208, 39993L),
        entry("shi", "shi", "shi", 23090, 103083L),
        entry("shi_latn", "shi_latn", "shi", 23094, 87492L, suffix = "latn"),
        entry("tig", "tig", "tig", 68240, 278849L),
        entry("tcy", "tcy", "tcy", 33567, 160325L),
        entry("xmf", "xmf", "xmf", 175039, 795383L),
        entry("dz", "dz", "dz", 9167, 36081L),
        entry("avk", "avk", "avk", 84354, 336013L),
        entry("ie", "ie", "ie", 18253, 78009L),
        entry("io", "io", "io", 84056, 339090L),
        entry("lfn", "lfn", "lfn", 57197, 223971L),
        entry("nov", "nov", "nov", 17163, 65549L),
        entry("qya", "qya", "qya", 7443, 21303L),
        entry("vo", "vo", "vo", 55046, 254064L),
        entry("sat", "sat", "sat", 200195, 874570L),
        entry("mni", "mni", "mni", 61810, 275608L),
        // --- AOSP LatinIME's lists, preferred over the counted ones above. ---
        // Where a language has two regions, the first listed is its default.
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
        aosp("ro_aosp", "ro", "ro", 1_125_204, 3_229_839L),
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
