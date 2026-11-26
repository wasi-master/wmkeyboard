package com.wasimaster.wmkeyboard.core.localllm

import java.io.File

/**
 * File layout for on-device models. Catalog downloads live in
 * `filesDir/models/<id>/<fileName>` with a `<fileName>.part` while the
 * download is in flight; user-imported files live in `filesDir/models/custom/`.
 *
 * A model with NPU builds (#804) can have one of those beside its standard
 * file, `filesDir/models/<id>/<npu fileName>`: the same directory, so deleting
 * the model deletes both, and [orphanDirs] needs no second rule. A GGUF vision
 * model's projector sits beside it the same way. An imported model's
 * projector is imported into custom/ beside it, where [customModels] leaves
 * it out: it is no model to pick.
 *
 * The atomic-rename contract: a final (non-.part) file exists only if its
 * download completed with the expected byte count, so presence == valid.
 * The custom directory scan is the whole registry for imports — no index
 * file to fall out of sync (same approach as CustomDictionaries).
 */
object LocalLlmStore {

    /** Prefix marking an imported file in the aiLocalModelId setting. */
    const val CUSTOM_PREFIX = "custom:"

    private const val ENGINE_CACHE = "litertlm"

    private val MODEL_EXTENSIONS = ModelFormat.entries.map { it.extension }.toSet()

    fun modelsDir(filesDir: File): File = File(filesDir, "models")

    fun modelDir(filesDir: File, model: LocalLlmModel): File =
        File(modelsDir(filesDir), model.id)

    /** [model]'s standard file, or with [fileName] one of its [LocalLlmModel.npuBuilds]. */
    fun modelFile(filesDir: File, model: LocalLlmModel, fileName: String = model.fileName): File =
        File(modelDir(filesDir, model), fileName)

    fun partFile(filesDir: File, model: LocalLlmModel, fileName: String = model.fileName): File =
        File(modelDir(filesDir, model), "$fileName.part")

    /** [model]'s NPU build for [soc] on disk, or null. */
    fun npuFile(filesDir: File, model: LocalLlmModel, soc: String?): File? =
        LocalLlmCatalog.npuBuild(model, soc)
            ?.let { modelFile(filesDir, model, it.fileName) }
            ?.takeIf { it.isFile }

    /** [model]'s projector, where it goes; null for a model without one. */
    fun projectorFile(filesDir: File, model: LocalLlmModel): File? =
        model.projector?.let { modelFile(filesDir, model, it.fileName) }

    /** Whether the standard file, the one every backend runs, is on disk. */
    fun hasStandard(filesDir: File, model: LocalLlmModel): Boolean =
        modelFile(filesDir, model).isFile

    /** Some build of [model] is on disk: the standard file or an NPU build. */
    fun isDownloaded(filesDir: File, model: LocalLlmModel): Boolean =
        hasStandard(filesDir, model) ||
            model.npuBuilds.any { modelFile(filesDir, model, it.fileName).isFile }

    fun customDir(filesDir: File): File = File(modelsDir(filesDir), "custom")

    fun customModels(filesDir: File): List<File> =
        customDir(filesDir).listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.lowercase() in MODEL_EXTENSIONS && !isProjector(it) }
            .sortedBy { it.name.lowercase() }

    /** Imported projectors: GGUF files in custom/ that read pictures for a model there. */
    fun customProjectors(filesDir: File): List<File> =
        customDir(filesDir).listFiles()
            .orEmpty()
            .filter { it.isFile && isProjector(it) }

    private fun isProjector(file: File): Boolean =
        file.extension.equals(GgufFile.EXTENSION, ignoreCase = true) && GgufFile.isProjector(file)

    /**
     * Resolves the aiLocalModelId setting — a catalog id or a
     * [CUSTOM_PREFIX]ed file name — to an existing model file, or null when
     * nothing is selected or the file is gone (deleted, never finished).
     */
    fun selectedModelFile(filesDir: File, aiLocalModelId: String): File? {
        if (aiLocalModelId.isBlank()) return null
        val file = if (aiLocalModelId.startsWith(CUSTOM_PREFIX)) {
            val name = aiLocalModelId.removePrefix(CUSTOM_PREFIX)
            // Reject path separators so a malformed setting can't escape custom/.
            if (name.isBlank() || name != File(name).name) return null
            File(customDir(filesDir), name)
        } else {
            val model = LocalLlmCatalog.byId(aiLocalModelId) ?: return null
            // The standard file when it is there; otherwise the NPU build
            // that is. The engine looks for the other one itself (NpuRouting).
            modelFile(filesDir, model).takeIf { it.isFile }
                ?: model.npuBuilds.map { modelFile(filesDir, model, it.fileName) }.firstOrNull { it.isFile }
                ?: return null
        }
        return file.takeIf { it.isFile }
    }

    /**
     * When exactly one model exists on disk (catalog or custom), its
     * selectable id — the "only model" fallback that makes an explicit
     * selection unnecessary until there's an actual choice.
     */
    fun soleDownloadedId(filesDir: File): String? {
        val ids = LocalLlmCatalog.models
            .filter { isDownloaded(filesDir, it) }
            .map { it.id } +
            customModels(filesDir).map { CUSTOM_PREFIX + it.name }
        return ids.singleOrNull()
    }

    /**
     * Directories left behind by models that have since been dropped from the
     * catalog. Nothing can select or delete them through the normal rows — the
     * settings screen offers a one-tap cleanup instead of silently eating the
     * disk space (or silently deleting a multi-GB file the user paid to fetch).
     */
    fun orphanDirs(filesDir: File): List<File> {
        // builtInIds: a LiteRT-LM model hidden while Google's libraries are off is not an orphan.
        val known = LocalLlmCatalog.models.map { it.id }.toSet() + LocalLlmCatalog.builtInIds + "custom"
        return modelsDir(filesDir).listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name !in known }
    }

    fun orphanBytes(filesDir: File): Long =
        orphanDirs(filesDir).sumOf { dir ->
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }

    fun deleteOrphans(filesDir: File) {
        orphanDirs(filesDir).forEach { it.deleteRecursively() }
    }

    fun delete(filesDir: File, model: LocalLlmModel) {
        modelDir(filesDir, model).deleteRecursively()
    }

    /**
     * Deletes an imported model, and its projector when no other imported
     * model pairs with it: the projector has no row of its own to delete it by.
     */
    fun deleteCustom(filesDir: File, name: String) {
        if (name != File(name).name) return
        val file = File(customDir(filesDir), name)
        val projector = GgufFile.projectorFor(file)
        file.delete()
        if (projector != null && customModels(filesDir).none { GgufFile.projectorFor(it) == projector }) {
            projector.delete()
        }
    }

    /**
     * Where LiteRT-LM keeps what it builds from a model on its first load
     * (repacked weights, compiled GPU programs), about one model's size each.
     * It is a cache: the engine builds it again, slower once, when it is gone.
     */
    fun engineCacheDir(cacheDir: File): File = File(cacheDir, ENGINE_CACHE)

    /**
     * Deletes what the engine built for models no longer on disk. Its files
     * are named for the model file and carry its size
     * (`gemma-4-E2B-it.litertlm.xnnpack_cache_<mtime>_<bytes>`), so one that
     * matches no model's name and size is left over from a model that was
     * deleted, or replaced by a newer file of the same name.
     */
    fun pruneEngineCache(filesDir: File, cacheDir: File) {
        val models = modelsDir(filesDir).walkTopDown()
            .filter { it.isFile && it.extension != "part" }
            .map { it.name to "_${it.length()}" }
            .toList()
        engineCacheDir(cacheDir).listFiles().orEmpty()
            .filter { cached -> models.none { (name, size) -> cached.name.startsWith(name) && size in cached.name } }
            .forEach { it.deleteRecursively() }
    }

    /** Bytes on disk across all models, finished and partial. */
    fun totalBytesUsed(filesDir: File): Long =
        modelsDir(filesDir).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
