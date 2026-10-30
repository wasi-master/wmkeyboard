package com.wasimaster.wmkeyboard.core.voice.executorch

import java.io.File

/**
 * Streaming transducers the user brings to Sherpa (#1005): any sherpa-onnx model
 * of that shape, for a language or a size the catalog lacks. Each lives in a
 * `custom-` folder under the engine's, and is an [EtModel] whose [EtFile]s are read
 * back off the disk, so the engine runs it as it does a catalog model. It counts
 * as the largest there is: bringing a model is choosing it, and deleting it hands
 * back to the catalog.
 */
object EtCustomModels {

    private const val PREFIX = "custom-"
    private const val META = "model.txt"
    private const val VAD = "silero_vad.onnx"

    /**
     * What sherpa-onnx's online transducers read from an encoder's metadata. It
     * exits the process on a model it cannot read, and that process is the
     * keyboard, so a whole-file model is turned away here.
     */
    private val STREAMING_MARK = "decode_chunk_len".toByteArray()

    /** What an import came to. */
    enum class Result { INSTALLED, NOT_TRANSDUCER, NOT_STREAMING, NEEDS_VAD }

    @Volatile
    var models: List<EtModel> = emptyList()
        private set

    @Volatile
    private var loaded = false

    /** [models] as they are on the disk, read once unless [force]d. */
    fun load(filesDir: File, force: Boolean = false): List<EtModel> {
        if (loaded && !force) return models
        val root = EtStore.rootDir(filesDir, EtEngine.SHERPA)
        models = root.listFiles { f -> f.isDirectory && f.name.startsWith(PREFIX) }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull(::read)
        loaded = true
        return models
    }

    private fun read(dir: File): EtModel? {
        val meta = File(dir, META).takeIf { it.isFile }?.readLines() ?: return null
        val files = dir.listFiles().orEmpty().filter { it.name == VAD || partOf(it.name) != null }
        return EtModel(
            dir.name, EtEngine.SHERPA, dir.name, meta.firstOrNull().orEmpty().ifBlank { dir.name }, Int.MAX_VALUE,
            "", "",
            files.map { EtFile(it.name, it.length(), "") },
            languages = meta.getOrNull(1).orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
            writesPunctuation = File(dir, "tokens.txt").takeIf { it.isFile }?.useLines { lines -> lines.any { it.startsWith(". ") } } ?: false,
        )
    }

    private fun partOf(name: String): String? = when {
        !name.endsWith(".onnx") && name != "tokens.txt" -> null
        name.startsWith("encoder") -> "encoder"
        name.startsWith("decoder") -> "decoder"
        name.startsWith("joiner") -> "joiner"
        name == "tokens.txt" -> "tokens"
        else -> null
    }

    /**
     * The one file to keep of each part among [names], null without all four. A
     * model's repository usually holds a full and an int8 copy; int8 is the one a
     * phone wants.
     */
    fun parts(names: List<String>): List<String>? {
        val byPart = names.groupBy { partOf(it) }
        return listOf("encoder", "decoder", "joiner", "tokens").map { part ->
            byPart[part]?.minByOrNull { if (".int8." in it) 0 else 1 } ?: return null
        }
    }

    /** Installs [files] as a model called [name] for [languages]. Blocking. */
    fun install(filesDir: File, files: List<File>, name: String, languages: Set<String>): Result {
        val keep = parts(files.map { it.name }) ?: return Result.NOT_TRANSDUCER
        val chosen = files.filter { it.name in keep }
        if (!holds(chosen.first { it.name.startsWith("encoder") }, STREAMING_MARK)) return Result.NOT_STREAMING
        // Each model keeps its own copy of the phrase-end detector.
        val vad = files.firstOrNull { it.name == VAD }
            ?: EtStore.downloaded(filesDir, EtEngine.SHERPA).firstNotNullOfOrNull { model ->
                File(EtStore.dir(filesDir, model), VAD).takeIf { it.isFile }
            }
            ?: return Result.NEEDS_VAD
        val dir = File(EtStore.rootDir(filesDir, EtEngine.SHERPA), PREFIX + System.currentTimeMillis())
        check(dir.mkdirs() || dir.isDirectory) { "could not make $dir" }
        try {
            (chosen + vad).forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            // Last: a folder without it is not read as a model.
            File(dir, META).writeText(name.lines().first() + "\n" + languages.joinToString(","))
        } catch (e: java.io.IOException) {
            dir.deleteRecursively()
            throw e
        }
        load(filesDir, force = true)
        return Result.INSTALLED
    }

    fun delete(filesDir: File, model: EtModel) {
        EtStore.delete(filesDir, model)
        load(filesDir, force = true)
    }

    /** Whether [mark] is anywhere in [file], which may be hundreds of megabytes. */
    private fun holds(file: File, mark: ByteArray): Boolean {
        val buffer = ByteArray((1 shl 20) + mark.size)
        file.inputStream().use { input ->
            var kept = 0
            while (true) {
                val read = input.read(buffer, kept, buffer.size - kept)
                if (read < 0) return false
                val end = kept + read
                for (i in 0..end - mark.size) {
                    if (mark.indices.all { buffer[i + it] == mark[it] }) return true
                }
                kept = minOf(mark.size - 1, end)
                System.arraycopy(buffer, end - kept, buffer, 0, kept)
            }
        }
    }
}
