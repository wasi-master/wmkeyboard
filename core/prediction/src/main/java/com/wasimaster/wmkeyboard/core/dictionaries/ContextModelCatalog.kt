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
    /** The file's size, for the prompt and the storage screen. */
    val approxBytes: Long,
    val variant: ContextModelVariant = ContextModelVariant.STANDARD,
) {
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
        ContextModelEntry(languageId = "en", repoCode = "en", stem = "en_context", approxBytes = 10_800_000),
        // d320, eight layers, 16.1M parameters: next-word top-1 .214 against
        // the standard model's .203 on the typing harness, same typo ranking.
        ContextModelEntry(
            languageId = "en", repoCode = "en", stem = "en_context_large", approxBytes = 16_700_000,
            variant = ContextModelVariant.LARGE,
        ),
        // Banglish and English in one model (tools/wmlm, `--langs=en,bn_rom`):
        // the two are typed in one sentence, so one model reads both.
        ContextModelEntry(languageId = "bn_rom", repoCode = "bn", stem = "bn_rom_context", approxBytes = 10_800_000),
        // German d256/6/4/768, ctx40, 24,576 vocab; the G6P 90k candidate.
        ContextModelEntry(languageId = "de", repoCode = "de", stem = "de_context", approxBytes = 10_856_360),
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
