package com.wasimaster.wmkeyboard.core.voice.whisper

import com.wasimaster.wmkeyboard.voice.R
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * whisper.cpp models the user brought themselves (#1224): a fine-tune such as
 * a Hinglish model, converted to whisper.cpp's `ggml-*.bin`, that is in no
 * catalog and has no hash to check against. Told by the file's own header
 * rather than its name, since whisper.cpp reads exactly that header first.
 *
 * Each one lives in `filesDir/whisper/custom-<name>/` beside the catalog's
 * models, so [WhisperStore] finds, routes and deletes it as it does those.
 * The list is read from disk, once per process and again after an import or
 * a delete; [WhisperCatalog.models] carries it after the catalog's own.
 */
object WhisperCustomModels {

    /** Prefix of a custom model's id and directory; no catalog id starts with it. */
    const val ID_PREFIX = "custom-"

    /** What a whisper.cpp model's header says about it. */
    data class Header(
        val vocab: Int,
        val audioLayers: Int,
        val textLayers: Int,
        val mels: Int,
    ) {
        /** 51864 tokens is the English-only tokenizer; every multilingual model has more (whisper.cpp's own test). */
        val englishOnly: Boolean get() = vocab == ENGLISH_VOCAB

        /** The size class, from the encoder's depth: Turbo is Large's encoder with a 4-layer decoder. */
        val size: WhisperSize
            get() = when {
                audioLayers <= 4 -> WhisperSize.TINY
                audioLayers <= 6 -> WhisperSize.BASE
                audioLayers <= 12 -> WhisperSize.SMALL
                audioLayers <= 24 -> WhisperSize.MEDIUM
                textLayers <= 4 -> WhisperSize.TURBO
                else -> WhisperSize.LARGE
            }
    }

    @Volatile private var loaded: List<WhisperModel>? = null

    /** The custom models found on disk so far; empty before [ensure] or [scan] ran. */
    val models: List<WhisperModel> get() = loaded.orEmpty()

    /** Whether [id] names a custom model, found yet or not. */
    fun isCustom(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** Reads the custom models from disk unless they have been read in this process. */
    fun ensure(filesDir: File) {
        if (loaded == null) scan(filesDir)
    }

    /** Reads the custom models from disk again. */
    fun scan(filesDir: File) {
        if (!WhisperCatalog.cppAvailable) {
            loaded = emptyList()
            return
        }
        loaded = WhisperStore.rootDir(filesDir).listFiles().orEmpty()
            .filter { it.isDirectory && isCustom(it.name) }
            .sortedBy { it.name }
            .mapNotNull { dir ->
                val file = dir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.endsWith(".bin") }
                    ?: return@mapNotNull null
                val header = header(file) ?: return@mapNotNull null
                modelOf(dir.name, file, header)
            }
    }

    /**
     * The header of [file] when it is a whisper.cpp model: the `ggml` magic,
     * then hyperparameters a Whisper model has (a 1500-frame encoder, 80 or
     * 128 mel bands, Whisper's vocabulary give or take a fine-tune's own tokens). Null for anything else,
     * an LLM in the old GGML format included.
     */
    fun header(file: File): Header? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val bytes = ByteArray(HEADER_BYTES)
            input.readFully(bytes)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.int != GGML_MAGIC) return null
            val vocab = buf.int
            val audioCtx = buf.int
            buf.int // n_audio_state
            buf.int // n_audio_head
            val audioLayers = buf.int
            buf.int // n_text_ctx
            buf.int // n_text_state
            buf.int // n_text_head
            val textLayers = buf.int
            val mels = buf.int
            val whisper = vocab in MIN_VOCAB..MAX_VOCAB && audioCtx == AUDIO_CTX &&
                (mels == 80 || mels == 128) && audioLayers in 1..64 && textLayers in 1..64
            if (whisper) Header(vocab, audioLayers, textLayers, mels) else null
        }
    }.getOrNull()

    /**
     * Copies [file], whose header is [header], into a directory of its own
     * named after [name], replacing one imported earlier under the same
     * name, and returns the model it now is. Runs on the caller's thread.
     */
    fun install(filesDir: File, file: File, name: String, header: Header): WhisperModel {
        val id = idFor(name)
        val dir = File(WhisperStore.rootDir(filesDir), id)
        dir.deleteRecursively()
        dir.mkdirs()
        val leaf = if (name.endsWith(".bin", ignoreCase = true)) name else "$name.bin"
        val target = File(dir, leaf)
        val part = File(dir, "$leaf.part")
        try {
            file.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            check(part.renameTo(target)) { "could not move $target into place" }
        } finally {
            part.delete()
        }
        scan(filesDir)
        return models.firstOrNull { it.id == id } ?: modelOf(id, target, header)
    }

    /** Forgets [id] after its directory went, so it is not offered as a download. */
    fun forget(id: String) {
        loaded = loaded?.filterNot { it.id == id }
    }

    /** The id a file named [name] is kept under: its name without `ggml-` and `.bin`, in safe characters. */
    internal fun idFor(name: String): String {
        val stem = name.substringAfterLast('/').removeSuffix(".bin").removeSuffix(".BIN")
            .removePrefix("ggml-").lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.')
        return ID_PREFIX + stem.ifEmpty { "model" }.take(MAX_ID_CHARS)
    }

    private fun modelOf(id: String, file: File, header: Header): WhisperModel = WhisperModel(
        id = id,
        displayName = file.name.removeSuffix(".bin"),
        size = header.size,
        repo = "",
        modelFile = file.name,
        vocabFile = "",
        modelBytes = file.length(),
        vocabBytes = 0,
        langCodes = if (header.englishOnly) setOf("en") else emptySet(),
        // whisper.cpp takes a language by name for any multilingual model; a
        // fine-tune keeps its base model's language tokens.
        selectableLang = !header.englishOnly,
        // Nothing says a fine-tune kept the translate task, so it is not offered.
        supportsTranslate = false,
        tier = WhisperTier.STANDARD,
        descriptionRes = R.string.core_voice_model_custom_body,
        format = WhisperFormat.GGML,
    )

    /** "ggml" read as a little-endian uint32, whisper.cpp's GGML_FILE_MAGIC. */
    private const val GGML_MAGIC = 0x67676d6c
    private const val HEADER_BYTES = 4 * 12
    private const val AUDIO_CTX = 1500
    private const val ENGLISH_VOCAB = 51864
    private const val MIN_VOCAB = 51864
    /** Room for a fine-tune that added a few tokens of its own; whisper.cpp reads them all. */
    private const val MAX_VOCAB = 52_864
    private const val MAX_ID_CHARS = 64
}
