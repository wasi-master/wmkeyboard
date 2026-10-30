package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.input.BrailleContracted
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.input.mozc.MozcData
import com.wasimaster.wmkeyboard.core.input.rime.RimeSchemaCatalog
import com.wasimaster.wmkeyboard.core.input.zinnia.ZinniaData
import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.input.R
import java.io.File

/**
 * One downloadable CJK conversion-dictionary pack. These tables are large and
 * useful only to a minority, so they are NOT bundled — the pack is the only
 * source. Until it is downloaded the composer types the raw reading with no
 * candidates. Checksum-verified after download (a corrupt table is worse than
 * none); see [ConversionDictionary].
 */
data class CjkDictPack(
    /** Stable key — storage subdir and the bundled asset base name (`pinyin`). */
    val id: String,
    /** Owning language id (`zh` / `ja`) — gates which detail screen shows it. */
    val langId: String,
    /** Row title for the pack; the UI layer resolves it. */
    @StringRes val displayNameRes: Int,
    /** One-line description for the pack row; the UI layer resolves it. */
    @StringRes val descriptionRes: Int,
    /**
     * The pack's path inside the data repository. Blank until the maintainer
     * fills it in. A mirror has to serve the same bytes: [sha256] is checked.
     */
    val path: String,
    /** Approximate size — preflight space check and progress fallback. */
    val sizeBytes: Long,
    /** Lowercase-hex SHA-256 of the downloaded file; verified before it goes live. */
    val sha256: String,
    /** On-disk file name for the downloaded pack. */
    val fileName: String,
    /**
     * Other languages whose detail screen also lists the pack. Still one pack,
     * downloaded once: the row on either screen shows the same file.
     */
    val alsoLangIds: Set<String> = emptySet(),
    /**
     * Names earlier versions of this pack were published and stored under.
     *
     * A published pack is never rebuilt in place: every installed app version
     * checks it against the checksum it shipped with, so new bytes under the
     * old name break the download for all of them. A rebuild goes out under a
     * new [path] and [fileName] instead, and the old name lands here so a copy
     * already on the device keeps converting until the new one replaces it.
     */
    val legacyFileNames: List<String> = emptyList(),
    /**
     * The pack this one improves and is fetched with: wherever that one is
     * offered — switching a layout on, a language's download-all — this one
     * comes too, without a prompt of its own. Null for a pack that stands
     * alone.
     */
    val alongside: String? = null,
    /**
     * A pinned address outside the data repository, for a file over the 100 MB its host
     * allows. [path] then only names the file. [sha256] is checked the same.
     */
    val directUrl: String? = null,
) {
    /** Where the pack is fetched from: the data repository, wherever [ServiceRepo.DATA] points. */
    val url: String get() = directUrl ?: ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl(path)

    /** A pack can only be offered once it has a path and a checksum. */
    val available: Boolean get() = path.isNotBlank() && sha256.isNotBlank()
}

/**
 * The curated CJK dictionary packs, one per input scheme: CC-CEDICT-derived
 * Hanzi for Pinyin, a Mozc-derived kana→kanji table for Japanese, and the
 * jyutping/stroke/cangjie tables from their sources in the data repo. A pack
 * whose URL or checksum is blank is [CjkDictPack.available] == false and the
 * settings row says so.
 */
object CjkDictCatalog {

    /** The list Restore ё reads. */
    const val RUSSIAN_YO_PACK = "ru_yo"


    val packs: List<CjkDictPack> = listOf(
        CjkDictPack(
            id = "pinyin",
            langId = "zh",
            displayNameRes = R.string.core_input_cjk_pack_pinyin_title,
            descriptionRes = R.string.core_input_cjk_pack_pinyin_subtitle,
            // v2 is the rescore from the zh_cn corpus (#633). pinyin.tsv stays
            // published, unchanged, for the versions that pinned it.
            path = "cjk/pinyin_v2.tsv",
            sizeBytes = 2_586_937L,
            sha256 = "0c572b5f5699378beb0eab5ec81cf79bb277e0f100d3c944d8969b7445b1a0c9",
            fileName = "pinyin_v2.tsv",
            legacyFileNames = listOf("pinyin.tsv"),
        ),
        // Not a reading table: which words follow which (#580), so `wbxh`
        // reads 我不喜欢 rather than 亡八喜欢. Pinyin converts without it, one
        // word's frequency at a time; it comes with the pinyin pack.
        CjkDictPack(
            id = "pinyin_ngrams",
            langId = "zh",
            displayNameRes = R.string.core_input_cjk_pack_pinyin_ngrams_title,
            descriptionRes = R.string.core_input_cjk_pack_pinyin_ngrams_subtitle,
            path = "cjk/pinyin_ngrams.tsv",
            sizeBytes = 11_036_820L,
            sha256 = "30c0c614ac0576379407b0e519615653a4be5939590a795dae7074ce960e30bf",
            fileName = "pinyin_ngrams.tsv",
            alongside = "pinyin",
        ),
        CjkDictPack(
            id = "ja_kana",
            langId = "ja",
            displayNameRes = R.string.core_input_cjk_pack_ja_kana_title,
            descriptionRes = R.string.core_input_cjk_pack_ja_kana_subtitle,
            path = "cjk/ja_kana.tsv",
            sizeBytes = 41_531_397L,
            sha256 = "189214b81968c857d7cb020c52fc087ee44918ab28534194f79ea66f45c17a70",
            fileName = "ja_kana.tsv",
        ),
        CjkDictPack(
            id = "stroke",
            langId = "zh",
            displayNameRes = R.string.core_input_cjk_pack_stroke_title,
            descriptionRes = R.string.core_input_cjk_pack_stroke_subtitle,
            path = "cjk/stroke.tsv",
            sizeBytes = 445_686L,
            sha256 = "b3bb4669cf8e411fd63b3a15e07e32db84ed1f2cfe09fb67066866ebe9b2278c",
            fileName = "stroke.tsv",
        ),
        CjkDictPack(
            id = "cangjie",
            langId = "zh",
            displayNameRes = R.string.core_input_cjk_pack_cangjie_title,
            descriptionRes = R.string.core_input_cjk_pack_cangjie_subtitle,
            path = "cjk/cangjie.tsv",
            sizeBytes = 351_337L,
            sha256 = "77a5a4c054019e3a3ea875e86a37cb08b71ce29392b6680208bb0c5749feb25d",
            fileName = "cangjie.tsv",
        ),
        // Not a reading table: the OpenCC phrases that let Traditional output
        // pick 頭髮 over 頭發 (#609). Optional — without it the bundled character
        // table still converts, one character at a time. See [HanVariant].
        // Cantonese lists it too: Jyutping candidates go through the same
        // conversion when Traditional output is on.
        CjkDictPack(
            id = "s2t_phrases",
            langId = "zh",
            displayNameRes = R.string.core_input_cjk_pack_s2t_phrases_title,
            descriptionRes = R.string.core_input_cjk_pack_s2t_phrases_subtitle,
            path = "cjk/s2t_phrases.txt",
            sizeBytes = 1_021_797L,
            sha256 = "a8c1ff12191edfc8dc435d23736e2bf097357650ea195f51767d397c5b70908c",
            fileName = "s2t_phrases.txt",
            alsoLangIds = setOf("yue"),
        ),
        CjkDictPack(
            id = "jyutping",
            langId = "yue",
            displayNameRes = R.string.core_input_cjk_pack_jyutping_title,
            descriptionRes = R.string.core_input_cjk_pack_jyutping_subtitle,
            path = "cjk/jyutping.tsv",
            sizeBytes = 3_416_241L,
            sha256 = "cc0666179df615846328ed1e86b621919553d8ff30905f30d0b6ff45315bcfba",
            fileName = "jyutping.tsv",
        ),
        CjkDictPack(
            id = "hanja",
            langId = "ko",
            displayNameRes = R.string.core_input_cjk_pack_hanja_title,
            descriptionRes = R.string.core_input_cjk_pack_hanja_subtitle,
            path = "cjk/hanja.tsv",
            sizeBytes = 7_380_500L,
            sha256 = "008dfcbbfd9ea873c3c77f9aa1152b6b4d05ad8734146fb3202fd9ec75202da6",
            fileName = "hanja.tsv",
        ),
        // Not a conversion table: Kiwi's model, for [KoreanSpacing].
        CjkDictPack(
            id = KoreanSpacing.PACK_ID,
            langId = "ko",
            displayNameRes = R.string.core_input_cjk_pack_kiwi_title,
            descriptionRes = R.string.core_input_cjk_pack_kiwi_subtitle,
            path = "cjk/kiwi/kiwi-model-0.24.1.zip",
            sizeBytes = 87_773_842L,
            sha256 = "e39cc9777da5ec0b24c772ac95c0f2cb2c937e666761dc0b50ba7e7e5c9e6c09",
            fileName = "kiwi-model-0.24.1.zip",
        ),
        // Phonikud's model, for [HebrewNikud]. Pinned to its upstream revision: it is
        // too big for the data repository.
        CjkDictPack(
            id = HebrewNikud.PACK_ID,
            langId = "he",
            displayNameRes = R.string.core_input_cjk_pack_nikud_title,
            descriptionRes = R.string.core_input_cjk_pack_nikud_subtitle,
            path = "phonikud/phonikud-1.0.int8.onnx",
            sizeBytes = 307_844_158L,
            sha256 = "113afb58d3140502aa1e7691cdc6b240b56cf97e5852fc870e1a7fb5a400dd62",
            fileName = "phonikud-1.0.int8.onnx",
            directUrl = "https://huggingface.co/Phonikud/phonikud-onnx/resolve/b806189fe1fc0085b1012b7560ffb5e8ecfd72a2/phonikud-1.0.int8.onnx",
        ),
        // CATT's encoder-only model as one int8 graph, for [ArabicTashkeel].
        CjkDictPack(
            id = ArabicTashkeel.PACK_ID,
            langId = "ar",
            displayNameRes = R.string.core_input_cjk_pack_tashkeel_title,
            descriptionRes = R.string.core_input_cjk_pack_tashkeel_subtitle,
            path = "cjk/tashkeel/catt-eo-v2.int8.onnx",
            sizeBytes = 21_341_532L,
            sha256 = "5e5e06c6d1244da0858da409f2e2ae6e5ae26568158719a8184c4362ac50435e",
            fileName = "catt-eo-v2.int8.onnx",
        ),
        // Word to reading, cut from the Mozc table above, for [JapaneseFurigana]. Not for conversion.
        CjkDictPack(
            id = JapaneseFurigana.PACK_ID,
            langId = "ja",
            displayNameRes = R.string.core_input_cjk_pack_furigana_title,
            descriptionRes = R.string.core_input_cjk_pack_furigana_subtitle,
            path = "cjk/furigana/ja_furigana.tsv",
            sizeBytes = 24_096_330L,
            sha256 = "436b57d4eb028456e356f520507b44eb32ac12f9763b9bb2e737b051a1a213f6",
            fileName = "ja_furigana.tsv",
        ),
        // eyo-kernel's safe list, for Restore ё.
        CjkDictPack(
            id = RUSSIAN_YO_PACK,
            langId = "ru",
            displayNameRes = R.string.core_input_cjk_pack_ru_yo_title,
            descriptionRes = R.string.core_input_cjk_pack_ru_yo_subtitle,
            path = "cjk/yo/ru_yo_safe.txt",
            sizeBytes = 864903L,
            sha256 = "11fc6d9c3cc6d0fff6a21cd642fa6aecbf3f9eb784f84ccd78141f32e51e0693",
            fileName = "ru_yo_safe.txt",
        ),
        // A Hunspell pair, for [TurkishSpelling].
        CjkDictPack(
            id = TurkishSpelling.PACK_ID,
            langId = "tr",
            displayNameRes = R.string.core_input_cjk_pack_hunspell_tr_title,
            descriptionRes = R.string.core_input_cjk_pack_hunspell_tr_subtitle,
            path = "cjk/hunspell/hunspell-tr-1.1.1.zip",
            sizeBytes = 14529614L,
            sha256 = "78ededf6f375d3dd98aa89f9797b91b7d292ab25dbf9b731789fc214a65ad399",
            fileName = "hunspell-tr-1.1.1.zip",
        ),
        // Not a conversion table either: liblouis's, for [BrailleContracted].
        CjkDictPack(
            id = BrailleContracted.PACK_ID,
            langId = "braille",
            displayNameRes = R.string.core_input_cjk_pack_louis_title,
            descriptionRes = R.string.core_input_cjk_pack_louis_subtitle,
            path = "cjk/louis/louis-tables-3.39.0.zip",
            sizeBytes = 3_609_626L,
            sha256 = "d5e9976911a9cbb10f086f0ac84029f0333eb5cee65e0e7a6d0c9de71c136bc7",
            fileName = "louis-tables-3.39.0.zip",
        ),
    )

    /**
     * [packs], the RIME engine's schema packs ([RimeSchemaCatalog]) and the
     * Mozc engine's dictionary ([MozcData]) and zinnia's handwriting models
     * ([ZinniaData]), which download, verify, import and sit on disk the same
     * way but are offered in their engine's own group and never converted
     * with by the built-in composers.
     */
    val allPacks: List<CjkDictPack> get() = packs + RimeSchemaCatalog.packs + MozcData.packs + ZinniaData.packs

    init {
        check(allPacks.map { it.id }.toSet().size == allPacks.size) { "pack ids must be unique" }
    }

    fun byId(id: String): CjkDictPack? = allPacks.firstOrNull { it.id == id }

    /** [pack] and every available pack fetched [CjkDictPack.alongside] it. */
    fun withCompanions(pack: CjkDictPack): List<CjkDictPack> =
        listOf(pack) + packs.filter { it.alongside == pack.id && it.available }

    /** The pack published as [name] (`pinyin_v2.tsv`), by the file name in the data repo. */
    fun byFileName(name: String): CjkDictPack? =
        allPacks.firstOrNull { it.available && it.path.substringAfterLast('/') == name }

    /**
     * True for a name an older version of a pack was published under. Offline
     * language packs carry those too, for app versions that still pin them, and
     * this one passes over them: their bytes would fail its checksum.
     */
    fun isLegacyFileName(name: String): Boolean = allPacks.any { name in it.legacyFileNames }

    fun forLang(langId: String): List<CjkDictPack> =
        packs.filter { it.langId == langId || langId in it.alsoLangIds }

    /**
     * The pack a layout typing through [type] cannot convert without, or null
     * for a composer that needs none. Spelled to match [CjkDictionaries]'
     * `missing*`, which answers the same question once the tables are loaded;
     * this one is for Settings, which has no tables, deciding what to offer
     * when such a layout is switched on.
     */
    fun forComposer(type: ComposerType): CjkDictPack? {
        val id = when (type) {
            ComposerType.PINYIN, ComposerType.T9_PINYIN, ComposerType.ZHUYIN -> "pinyin"
            ComposerType.ROMAJI -> "ja_kana"
            ComposerType.STROKE -> "stroke"
            ComposerType.CANGJIE, ComposerType.CANGJIE_QUICK -> "cangjie"
            ComposerType.JYUTPING -> "jyutping"
            else -> return null
        }
        return byId(id)
    }
}

/**
 * File layout for downloaded packs: `filesDir/cjk_dicts/<id>/<fileName>` with a
 * `<fileName>.part` while in flight. The atomic-rename + checksum contract means
 * a final (non-.part) file exists only if its download completed and verified,
 * so presence == valid, exactly as [com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore].
 */
object CjkDictStore {

    private fun dictsDir(filesDir: File) = File(filesDir, "cjk_dicts")

    fun packDir(filesDir: File, pack: CjkDictPack): File = File(dictsDir(filesDir), pack.id)

    fun packFile(filesDir: File, pack: CjkDictPack): File =
        File(packDir(filesDir, pack), pack.fileName)

    fun partFile(filesDir: File, pack: CjkDictPack): File =
        File(packDir(filesDir, pack), "${pack.fileName}.part")

    fun isDownloaded(filesDir: File, pack: CjkDictPack): Boolean = packFile(filesDir, pack).isFile

    fun delete(filesDir: File, pack: CjkDictPack) {
        packDir(filesDir, pack).deleteRecursively()
    }

    /** Removes copies of [pack]'s earlier versions, once the current one is in place. */
    fun dropLegacy(filesDir: File, pack: CjkDictPack) {
        pack.legacyFileNames.forEach { name ->
            File(packDir(filesDir, pack), name).delete()
            // The mapped table the decoder built from it; see [ConversionDictionary.open].
            File(packDir(filesDir, pack), "$name.wmcd").delete()
        }
    }

    /**
     * The pack file for [id] to convert with: the current version if it is on
     * disk, else a copy of an earlier one, else null (no candidates until
     * downloaded).
     *
     * The earlier copy is what keeps typing working across an update that moves
     * a pack to a new version. [isDownloaded] still says no, so the pack's row
     * offers the download, but nobody loses Chinese between updating the app
     * and fetching the new table.
     */
    fun downloadedFileFor(filesDir: File, id: String): File? {
        val pack = CjkDictCatalog.byId(id) ?: return null
        packFile(filesDir, pack).takeIf(File::isFile)?.let { return it }
        return pack.legacyFileNames.map { File(packDir(filesDir, pack), it) }.firstOrNull(File::isFile)
    }

    /**
     * A monotonically-changing token summarising which packs are on disk. The
     * service compares it against the token it last loaded from and re-parses the
     * conversion tables only when it differs — so a pack finished in Settings goes
     * live the next time a field is focused, without re-parsing on every keypress.
     */
    fun stateToken(filesDir: File): Int =
        CjkDictCatalog.packs.fold(0) { acc, pack ->
            acc * 31 + if (isDownloaded(filesDir, pack)) 1 else 0
        }
}
