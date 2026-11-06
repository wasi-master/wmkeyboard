package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo

/**
 * The sizes a language's model comes in. [STANDARD] is what the automatic
 * download fetches; [LARGE] is offered on the language's screen for phones
 * with room and patience: it predicts the next word about a point better
 * and takes longer per committed word.
 */
enum class ContextModelVariant { STANDARD, LARGE }

/**
 * One downloadable context model: a `.wmlm` for a language (or a pair of
 * languages that are typed together), hosted in the data repo beside the
 * language's word lists as `data/<repoCode>/<stem>.wmlm`.
 */
class ContextModelEntry(
    /** The language id the model serves; a model for two languages is listed once per language. */
    val languageId: String,
    /** Directory under `data/` in the repo. */
    val repoCode: String,
    /** File stem, e.g. `en_context` -> `data/en/en_context.wmlm`. */
    val stem: String,
    /** Exact file size, checked after download. */
    val bytes: Long,
    /** SHA-256 of the file, checked after download. */
    val sha256: String,
    val variant: ContextModelVariant = ContextModelVariant.STANDARD,
) {
    init {
        require(bytes > 0) { "$stem: bytes must be positive" }
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "$stem: sha256 must be 64 lowercase hex characters"
        }
    }

    /** The file's size, for the prompt and the storage screen. */
    val approxBytes: Long get() = bytes

    val fileName: String get() = "$stem.wmlm"

    fun url(): String = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl("data/$repoCode/$stem.wmlm")
}

/**
 * Every language with a published context model. Models are trained by
 * `tools/wmlm` (see its README) and published to the data repo; a language
 * that is not here has none to offer and keeps its n-gram stores alone.
 */
object ContextModelCatalog {

    val entries: List<ContextModelEntry> = listOf(
        // d256, six layers, subword pieces, 10.3M parameters.
        ContextModelEntry(
            languageId = "en",
            repoCode = "en",
            stem = "en_context",
            bytes = 10_801_380,
            sha256 = "fef5aae9cbf11bd290ea5dd2817cc65d098072f4585f073f9c557d2ce674f040",
        ),
        // d320, eight layers, 16.1M parameters: next-word top-1 .214 against
        // the standard model's .203 on the typing harness, same typo ranking.
        ContextModelEntry(
            languageId = "en",
            repoCode = "en",
            stem = "en_context_large",
            bytes = 16_728_720,
            sha256 = "3baddb746c7add8f0fdcb43fa050a280cbeccdaaedfd533955489effcdf362a7",
            variant = ContextModelVariant.LARGE,
        ),
        // Banglish and English in one model (tools/wmlm, `--langs=en,bn_rom`):
        // the two are typed in one sentence, so one model reads both.
        ContextModelEntry(
            languageId = "bn_rom",
            repoCode = "bn",
            stem = "bn_rom_context",
            bytes = 10_801_084,
            sha256 = "2a43d0dcfb739546ad37ebdb9d0dfccf1684aabc8530122fd95dc42ed1e7b6f1",
        ),
        // German d256/6/4/768, ctx40, 24,576 vocab; the G6P 90k candidate.
        ContextModelEntry(
            languageId = "de",
            repoCode = "de",
            stem = "de_context",
            bytes = 10_856_360,
            sha256 = "4fe6d6b44af85eafcda1458f6e30f991033cd96177d9f77ddf5695d294845beb",
        ),
    )

    private val byLanguage = entries.groupBy { it.languageId }

    /** The standard model for [languageId], the one fetched by itself. */
    fun forLanguage(languageId: String): ContextModelEntry? =
        byLanguage[languageId]?.firstOrNull { it.variant == ContextModelVariant.STANDARD }

    /** Every size [languageId]'s model comes in, standard first. */
    fun variantsFor(languageId: String): List<ContextModelEntry> =
        byLanguage[languageId].orEmpty().sortedBy { it.variant.ordinal }

    fun entry(languageId: String, variant: ContextModelVariant): ContextModelEntry? =
        byLanguage[languageId]?.firstOrNull { it.variant == variant }

    /** The entry published as [fileName] (`en_context.wmlm`), for an import (#1000). */
    fun byFileName(fileName: String): ContextModelEntry? = entries.firstOrNull { it.fileName == fileName }
}
