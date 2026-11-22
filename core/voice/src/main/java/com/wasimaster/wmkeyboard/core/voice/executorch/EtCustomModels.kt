package com.wasimaster.wmkeyboard.core.voice.executorch

import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import java.io.File

/**
 * Models the user brings, from files or from Hugging Face ([HuggingFaceModels]):
 * any sherpa-onnx streaming transducer or Paraformer for Sherpa (#1005), for a
 * language or a size the catalog lacks, and any Qwen3-ASR export for
 * sherpa-onnx (#1437), a fine-tune or the 1.7B model. Each lives in a
 * `custom-` folder under its engine's, and is an [EtModel] whose [EtFile]s are
 * read back off the disk, so the engine runs it as it does a catalog model.
 * It counts as the largest there is: bringing a model is choosing it, and
 * deleting it hands back to the catalog.
 */
object EtCustomModels {

    private const val PREFIX = "custom-"
    private const val META = "model.txt"
    private const val VAD = "silero_vad.onnx"

    /** The engines a model can be brought to. */
    private val ENGINES = listOf(EtEngine.SHERPA, EtEngine.QWEN3)

    /**
     * What sherpa-onnx's online transducers read from an encoder's metadata. It
     * exits the process on a model it cannot read, and that process is the
     * keyboard, so a whole-file model is turned away here.
     */
    private val STREAMING_MARK = "decode_chunk_len".toByteArray()

    /**
     * What its online Paraformer reads the same way, and exits on the same way.
     * A whole-file Paraformer has no `decoder_num_blocks`.
     */
    private val PARAFORMER_MARKS = listOf(
        "vocab_size", "lfr_window_size", "lfr_window_shift", "encoder_output_size",
        "decoder_num_blocks", "decoder_kernel_size", "neg_mean", "inv_stddev",
    ).map { it.toByteArray() }

    /** The Qwen tokenizer's files, which sherpa-onnx reads from one folder. */
    val QWEN3_TOKENIZER = listOf("vocab.json", "merges.txt", "tokenizer_config.json")

    /** What an import came to. */
    enum class Result {
        INSTALLED,
        NOT_TRANSDUCER,
        NOT_STREAMING,
        NEEDS_VAD,

        /** A Qwen3-ASR model short of a graph or a tokenizer file. */
        QWEN3_INCOMPLETE,

        /** Qwen3-ASR graphs ONNX Runtime cannot open, or of a shape sherpa-onnx does not run. */
        QWEN3_UNREADABLE,

        /** ONNX Runtime is not on the phone yet, so a Qwen3-ASR model cannot be checked. */
        NEEDS_RUNTIME,
    }

    @Volatile
    var models: List<EtModel> = emptyList()
        private set

    @Volatile
    private var loaded = false

    /** [models] as they are on the disk, read once unless [force]d. */
    fun load(filesDir: File, force: Boolean = false): List<EtModel> {
        if (loaded && !force) return models
        models = ENGINES.flatMap { engine ->
            EtStore.rootDir(filesDir, engine).listFiles { f -> f.isDirectory && f.name.startsWith(PREFIX) }.orEmpty()
                .sortedBy { it.name }
                .mapNotNull { read(it, engine) }
        }
        loaded = true
        return models
    }

    /** The user's models for [engine]. */
    fun forEngine(filesDir: File, engine: EtEngine): List<EtModel> = load(filesDir).filter { it.engine == engine }

    private fun read(dir: File, engine: EtEngine): EtModel? {
        val meta = File(dir, META).takeIf { it.isFile }?.readLines() ?: return null
        val qwen3 = engine == EtEngine.QWEN3
        val files = dir.listFiles().orEmpty().filter {
            it.name == VAD || (if (qwen3) qwen3PartOf(it.name) != null else partOf(it.name) != null)
        }
        return EtModel(
            dir.name, engine, dir.name, meta.firstOrNull().orEmpty().ifBlank { dir.name }, Int.MAX_VALUE,
            "", "",
            files.map { EtFile(it.name, it.length(), "") },
            languages = meta.getOrNull(1).orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
            // Qwen3-ASR writes its own marks; a transducer only when its vocabulary has a full stop.
            writesPunctuation = qwen3 ||
                File(dir, "tokens.txt").takeIf { it.isFile }?.useLines { lines -> lines.any { it.startsWith(". ") } } ?: false,
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
     * Which part of a Qwen3-ASR export [name] is: one of its three graphs, the
     * external weights a large graph keeps beside it (`decoder.int8.onnx.data`),
     * or a tokenizer file. Null for anything else.
     */
    internal fun qwen3PartOf(name: String): String? = when {
        name in QWEN3_TOKENIZER -> name
        name.endsWith(".onnx.data") || name.endsWith(".onnx_data") -> "data"
        !name.endsWith(".onnx") -> null
        name.startsWith("conv_frontend") -> "conv_frontend"
        name.startsWith("encoder") -> "encoder"
        name.startsWith("decoder") -> "decoder"
        else -> null
    }

    /** Whether [names] look like a Qwen3-ASR export rather than a Sherpa transducer. */
    fun isQwen3(names: List<String>): Boolean = names.any { qwen3PartOf(it) == "conv_frontend" }

    /**
     * The one file to keep of each part among [names], null without an encoder, a
     * decoder and tokens. A joiner comes along when there is one: that is a
     * transducer, and without one a Paraformer. A model's repository usually holds
     * a full and an int8 copy; int8 is the one a phone wants.
     */
    fun parts(names: List<String>): List<String>? {
        val byPart = names.groupBy { partOf(it) }
        val wanted = listOf("encoder", "decoder", "tokens") + listOfNotNull("joiner".takeIf { it in byPart })
        return wanted.map { part ->
            byPart[part]?.minByOrNull { if (".int8." in it) 0 else 1 } ?: return null
        }
    }

    /**
     * The files of a Qwen3-ASR export among [names] (base names): one of each
     * graph, int8 first, the external weights any of those three keeps beside
     * it, and the tokenizer's three files. Null when a graph or a tokenizer
     * file is missing.
     */
    fun qwen3Parts(names: List<String>): List<String>? {
        val byPart = names.groupBy { qwen3PartOf(it) }
        val graphs = listOf("conv_frontend", "encoder", "decoder").map { part ->
            byPart[part]?.minByOrNull { if (".int8." in it) 0 else 1 } ?: return null
        }
        val tokenizer = QWEN3_TOKENIZER.map { name -> name.takeIf { it in names } ?: return null }
        // ONNX Runtime finds a graph's external weights by the name the graph
        // gives them, which starts with the graph's own.
        val data = byPart["data"].orEmpty().filter { d -> graphs.any { d.startsWith(it.removeSuffix(".onnx")) } }
        return graphs + data + tokenizer
    }

    /**
     * Installs [files] as a model called [name] for [languages], for Qwen3-ASR
     * when they hold its front end ([isQwen3]) and for Sherpa otherwise.
     * [move] takes the files rather than copying them, for a download made
     * for this import. Blocking; a Qwen3-ASR model's graphs are opened to
     * check them, which takes seconds.
     */
    fun install(filesDir: File, files: List<File>, name: String, languages: Set<String>, move: Boolean = false): Result =
        if (isQwen3(files.map { it.name })) {
            installQwen3(filesDir, files, name, languages, move)
        } else {
            installSherpa(filesDir, files, name, languages, move)
        }

    private fun installSherpa(filesDir: File, files: List<File>, name: String, languages: Set<String>, move: Boolean): Result {
        val keep = parts(files.map { it.name }) ?: return Result.NOT_TRANSDUCER
        val chosen = files.filter { it.name in keep }
        val encoder = chosen.first { it.name.startsWith("encoder") }
        val marks = if (chosen.any { it.name.startsWith("joiner") }) listOf(STREAMING_MARK) else PARAFORMER_MARKS
        if (!marks.all { holds(encoder, it) }) return Result.NOT_STREAMING
        val vad = vadAmong(filesDir, files) ?: return Result.NEEDS_VAD
        place(filesDir, EtEngine.SHERPA, chosen, vad, name, languages, move)
        return Result.INSTALLED
    }

    private fun installQwen3(filesDir: File, files: List<File>, name: String, languages: Set<String>, move: Boolean): Result {
        val keep = qwen3Parts(files.map { it.name }) ?: return Result.QWEN3_INCOMPLETE
        val chosen = files.filter { it.name in keep }.distinctBy { it.name }
        val vad = vadAmong(filesDir, files) ?: return Result.NEEDS_VAD
        if (!SherpaNative.available) return Result.NEEDS_RUNTIME
        // Checked where the files are now: sherpa-onnx exits on graphs of the
        // wrong shape, and the keyboard is the process it would exit.
        fun graph(part: String) = chosen.first { qwen3PartOf(it.name) == part }.path
        val verdict = try {
            SherpaNative.nativeQwen3Probe(graph("conv_frontend"), graph("encoder"), graph("decoder"))
        } catch (_: UnsatisfiedLinkError) {
            return Result.NEEDS_RUNTIME
        }
        if (verdict != 0) return Result.QWEN3_UNREADABLE
        place(filesDir, EtEngine.QWEN3, chosen, vad, name, languages, move)
        return Result.INSTALLED
    }

    /**
     * Silero VAD among [files], or a copy from a model of any engine that
     * carries one: each model keeps its own copy of the phrase-end detector.
     */
    private fun vadAmong(filesDir: File, files: List<File>): File? =
        files.firstOrNull { it.name == VAD }
            ?: listOf(EtEngine.SHERPA, EtEngine.QWEN3, EtEngine.OMNILINGUAL).asSequence()
                .flatMap { EtStore.downloaded(filesDir, it).asSequence() }
                .firstNotNullOfOrNull { model -> File(EtStore.dir(filesDir, model), VAD).takeIf { it.isFile } }

    private fun place(
        filesDir: File,
        engine: EtEngine,
        chosen: List<File>,
        vad: File,
        name: String,
        languages: Set<String>,
        move: Boolean,
    ) {
        val dir = File(EtStore.rootDir(filesDir, engine), PREFIX + System.currentTimeMillis())
        check(dir.mkdirs() || dir.isDirectory) { "could not make $dir" }
        try {
            for (file in chosen + vad) {
                val target = File(dir, file.name)
                // The detector may be another model's copy, which stays where it is.
                if (!(move && file !== vad && file.renameTo(target))) file.copyTo(target, overwrite = true)
            }
            // Last: a folder without it is not read as a model.
            File(dir, META).writeText(name.lines().first() + "\n" + languages.joinToString(","))
        } catch (e: java.io.IOException) {
            dir.deleteRecursively()
            throw e
        }
        load(filesDir, force = true)
    }

    fun delete(filesDir: File, model: EtModel) {
        EtStore.delete(filesDir, model)
        load(filesDir, force = true)
    }

    /** Whether [mark] is anywhere in [file], which may be hundreds of megabytes. */
    internal fun holds(file: File, mark: ByteArray): Boolean {
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
