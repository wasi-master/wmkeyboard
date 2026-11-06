package com.wasimaster.wmkeyboard.core.voice.executorch

import java.io.File

/**
 * The [EtCatalog] models' files fetched on another device and imported here
 * (#954): Asr4All, Parakeet, Moonshine, Sherpa, Omnilingual and Vosk, for a phone
 * that cannot download them.
 *
 * A file is told by its size and then its SHA-256, never its name: the names
 * Hugging Face serves repeat from model to model (`tokens.txt`,
 * `metadata.json`, `model.pte`), and a browser renames a second download
 * anyway. A file whose bytes more than one model shares (Silero VAD, which
 * every Sherpa model keeps a copy of, or a tokens file two models have in
 * common) goes only to the models this import or an earlier one has already
 * started, so importing one model never leaves a stray file in the folders
 * of the others.
 */
object EtImport {

    /** What one imported file came to. */
    sealed interface Result {
        val fileName: String

        /** Placed into [models]; each of [ready] now has all its files. */
        data class Placed(override val fileName: String, val models: List<EtModel>, val ready: List<EtModel>) : Result

        /** A file several models share, and none of them started: import it with a model's own files. */
        data class Shared(override val fileName: String) : Result
    }

    /** Whether some model has a file exactly [size] bytes long: worth hashing. */
    fun sizeMatches(size: Long): Boolean = size > 0 && EtCatalog.models.any { m -> m.files.any { it.bytes == size } }

    /**
     * Places every file among [files] that is one of the catalog's, and
     * returns what became of each; the files that are none of them come back
     * in [Outcome.unclaimed] for another installer to try. Blocking.
     */
    fun install(filesDir: File, files: List<File>): Outcome {
        val matched = LinkedHashMap<File, List<Pair<EtModel, EtFile>>>()
        val unclaimed = ArrayList<File>()
        for (file in files) {
            val size = file.length()
            val bySize = EtCatalog.models.flatMap { m -> m.files.filter { it.bytes == size }.map { m to it } }
            if (bySize.isEmpty()) {
                unclaimed += file
                continue
            }
            val sha = EtStore.sha256(file)
            val hits = bySize.filter { it.second.sha256.equals(sha, ignoreCase = true) }
            if (hits.isEmpty()) unclaimed += file else matched[file] = hits
        }
        // A model is started when this import brings a file only it has, or
        // when some of its files are on the device already.
        val started = HashSet<String>()
        matched.values.filter { it.size == 1 }.forEach { started += it.single().first.id }
        EtCatalog.models.filter { EtStore.bytesOnDisk(filesDir, it) > 0 }.forEach { started += it.id }
        val results = ArrayList<Result>()
        val touched = LinkedHashSet<EtModel>()
        for ((file, hits) in matched) {
            val targets = if (hits.size == 1) hits else hits.filter { it.first.id in started }
            if (targets.isEmpty()) {
                results += Result.Shared(file.name)
                continue
            }
            for ((model, entry) in targets) {
                place(file, EtStore.file(filesDir, model, entry))
                touched += model
            }
            results += Result.Placed(file.name, targets.map { it.first }, emptyList())
        }
        // A model that comes as one zip (Vosk's) is laid out once it is here whole.
        touched.forEach { runCatching { EtStore.unpackArchives(filesDir, it) } }
        val ready = touched.filter { EtStore.isDownloaded(filesDir, it) }.toSet()
        return Outcome(
            results.map { r ->
                if (r is Result.Placed) r.copy(ready = r.models.filter { it in ready }) else r
            },
            unclaimed,
        )
    }

    /** [install]'s answer: a [Result] per catalog file, and the files that were none. */
    class Outcome(val results: List<Result>, val unclaimed: List<File>)

    /** Copies [source] to [target] through a `.part`, so a half-copied file is never taken for the model. */
    private fun place(source: File, target: File) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.import")
        try {
            source.copyTo(part, overwrite = true)
            target.delete()
            check(part.renameTo(target)) { "could not move $target into place" }
        } finally {
            part.delete()
        }
    }
}
