package com.wasimaster.wmkeyboard.core.voice.punctuation

import com.wasimaster.wmkeyboard.core.voice.executorch.EtCustomModels
import java.io.File

/**
 * Punctuation models the user brings (#1005): any sherpa-onnx online punctuation
 * model, the CNN-BiLSTM kind Sherpa's English one is, for the languages they give
 * it. Each lives in a `custom-` folder under [DictationPunctuation.rootDir], and is
 * used before the listed models for its languages.
 */
object PunctuationCustomModels {

    private const val PREFIX = "custom-"
    private const val META = "model.txt"
    private const val VOCAB = "bpe.vocab"

    /**
     * What sherpa-onnx reads from such a model's metadata. It exits the process on
     * a model without them, and that process is the keyboard.
     */
    private val MARKS = listOf("COMMA", "PERIOD", "QUESTION", "UPPER", "CAP", "MIX_CASE").map { it.toByteArray() }

    /** What an import came to. */
    enum class Result { INSTALLED, MISSING_FILES, NOT_PUNCTUATION }

    class Model(val dir: File, val name: String, val languages: Set<String>, val graph: File, val vocab: File)

    @Volatile
    var models: List<Model> = emptyList()
        private set

    @Volatile
    private var loaded = false

    /** [models] as they are on the disk, read once unless [force]d. */
    fun load(filesDir: File, force: Boolean = false): List<Model> {
        if (loaded && !force) return models
        models = DictationPunctuation.rootDir(filesDir)
            .listFiles { f -> f.isDirectory && f.name.startsWith(PREFIX) }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull(::read)
        loaded = true
        return models
    }

    /** The newest model that knows all of [languages], which are base codes. */
    fun forLanguages(filesDir: File, languages: List<String>): Model? =
        load(filesDir).lastOrNull { model -> languages.all { it in model.languages } }

    private fun read(dir: File): Model? {
        val meta = File(dir, META).takeIf { it.isFile }?.readLines() ?: return null
        val graph = dir.listFiles().orEmpty().firstOrNull { it.name.endsWith(".onnx") } ?: return null
        val languages = meta.getOrNull(1).orEmpty().split(',').map(DictationPunctuation::base).filter { it.isNotEmpty() }.toSet()
        return Model(dir, meta.firstOrNull().orEmpty().ifBlank { dir.name }, languages, graph, File(dir, VOCAB))
    }

    /** The graph and the vocabulary among [names], int8 where there are two; null without both. */
    fun parts(names: List<String>): List<String>? {
        val graph = names.filter { it.endsWith(".onnx") }.minByOrNull { if (".int8." in it) 0 else 1 } ?: return null
        return if (VOCAB in names) listOf(graph, VOCAB) else null
    }

    /** Installs [files] as a model called [name] for [languages]. Blocking. */
    fun install(filesDir: File, files: List<File>, name: String, languages: Set<String>): Result {
        val keep = parts(files.map { it.name }) ?: return Result.MISSING_FILES
        val graph = files.first { it.name == keep[0] }
        if (!MARKS.all { EtCustomModels.holds(graph, it) }) return Result.NOT_PUNCTUATION
        val dir = File(DictationPunctuation.rootDir(filesDir), PREFIX + System.currentTimeMillis())
        check(dir.mkdirs() || dir.isDirectory) { "could not make $dir" }
        try {
            files.filter { it.name in keep }.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            // Last: a folder without it is not read as a model.
            File(dir, META).writeText(name.lines().first() + "\n" + languages.joinToString(","))
        } catch (e: java.io.IOException) {
            dir.deleteRecursively()
            throw e
        }
        load(filesDir, force = true)
        return Result.INSTALLED
    }

    fun delete(filesDir: File, model: Model) {
        model.dir.deleteRecursively()
        load(filesDir, force = true)
    }
}
