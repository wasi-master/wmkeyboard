package com.wasimaster.wmkeyboard.core.localllm

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/**
 * Recognises a GGUF file, the format llama.cpp runs, by its content: every
 * GGUF starts with the four bytes `GGUF`. The name is no guide on its own,
 * since a file saved from a browser or a chat app can lose its extension.
 *
 * A GGUF is not always a model: a vision model's projector (its `mmproj`
 * file, architecture `clip`) is one too, and only reads pictures for the
 * model beside it. [isProjector] tells the two apart and [projectorFor]
 * finds a model's.
 */
object GgufFile {

    const val EXTENSION = "gguf"

    /** `general.architecture` of a vision or audio projector. */
    const val PROJECTOR_ARCHITECTURE = "clip"

    private val MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    /** Whether [header] (a file's first bytes) opens a GGUF. */
    fun hasMagic(header: ByteArray): Boolean =
        header.size >= MAGIC.size && (MAGIC.indices).all { header[it] == MAGIC[it] }

    /** Whether [file] is a GGUF, read from its first four bytes. False when it cannot be read. */
    fun isGguf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(MAGIC.size)
            input.read(header) == MAGIC.size && hasMagic(header)
        }
    }.getOrDefault(false)

    /** Whether [file] is run by llama.cpp rather than LiteRT-LM: its extension, else its content. */
    fun runsOnLlamaCpp(file: File): Boolean =
        file.extension.equals(EXTENSION, ignoreCase = true) ||
            (ModelFormat.entries.none { it.extension.equals(file.extension, ignoreCase = true) } && isGguf(file))

    /**
     * `general.architecture` from [file]'s header ("llama", "qwen2", "clip"),
     * or null when it is no GGUF or the key is not among the first ones.
     * Reads a few kilobytes at most.
     */
    fun architecture(file: File): String? = runCatching {
        file.inputStream().buffered().use { architecture(it) }
    }.getOrNull()

    /** [architecture] from a stream positioned at the start of a GGUF. */
    fun architecture(input: InputStream): String? {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        if (!hasMagic(magic)) return null
        val version = data.readLittleInt()
        // Version 1 counted in 32 bits and is long gone from the hub.
        if (version < 2) return null
        data.readLittleLong() // tensors
        val pairs = data.readLittleLong()
        // general.architecture is the first key every writer puts down; a
        // file that buries it deeper is read as having none.
        for (i in 0 until minOf(pairs, MAX_KEYS_READ)) {
            val key = data.readGgufString(MAX_KEY_BYTES) ?: return null
            val type = data.readLittleInt()
            if (key == "general.architecture") {
                return if (type == TYPE_STRING) data.readGgufString(MAX_VALUE_BYTES) else null
            }
            if (!data.skipValue(type)) return null
        }
        return null
    }

    /**
     * How [file] stores its weights: `general.file_type` from its header, or,
     * in a file whose header has no such key, what its name says. Null when it
     * is no GGUF, or names a format this reader does not know (an old
     * repacked `Q4_0_4_4` is not a Q4_0). Reads only the header: the
     * tokenizer's lists are skipped, never loaded.
     */
    fun quantization(file: File): GgufQuant? {
        val read = runCatching { file.inputStream().buffered().use { fileType(it) } }.getOrNull()
        return when (read) {
            null -> null
            NO_FILE_TYPE -> GgufQuant.ofName(file.name)
            else -> GgufQuant.ofFileType(read)
        }
    }

    /**
     * `general.file_type` from a stream at the start of a GGUF, [NO_FILE_TYPE]
     * when the header has none, or null when it is no GGUF a reader can walk.
     */
    internal fun fileType(input: InputStream): Int? {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        if (!hasMagic(magic)) return null
        if (data.readLittleInt() < 2) return null
        data.readLittleLong() // tensors
        val pairs = data.readLittleLong()
        for (i in 0 until minOf(pairs, MAX_KEYS_SCANNED)) {
            val key = data.readGgufString(MAX_KEY_BYTES) ?: return null
            val type = data.readLittleInt()
            if (key == "general.file_type") {
                return when (type) {
                    TYPE_UINT32, TYPE_INT32 -> data.readLittleInt()
                    else -> null
                }
            }
            if (!data.skipValue(type)) return null
        }
        return NO_FILE_TYPE
    }

    /** [fileType]'s answer for a GGUF whose header has no `general.file_type`. */
    internal const val NO_FILE_TYPE = -1

    /** Whether [file] is a projector: its name says `mmproj`, or its header says `clip`. */
    fun isProjector(file: File): Boolean =
        file.name.contains("mmproj", ignoreCase = true) || architecture(file) == PROJECTOR_ARCHITECTURE

    /**
     * The projector beside [model] in its directory, if one is there for it.
     * Several models can share a directory (imported files all land in one),
     * so a projector is matched by name: without `mmproj`, the quantization
     * and punctuation, one name has to be the start of the other
     * (`mmproj-SmolVLM-500M-Instruct-f16` for `SmolVLM-500M-Instruct-Q8_0`),
     * and the longest such wins. A directory holding just one model and one
     * projector pairs them whatever their names.
     */
    fun projectorFor(model: File): File? {
        val dir = model.parentFile ?: return null
        val ggufs = dir.listFiles().orEmpty()
            .filter { it.isFile && it != model && it.extension.equals(EXTENSION, ignoreCase = true) }
        val projectors = ggufs.filter(::isProjector)
        if (projectors.isEmpty() || isProjector(model)) return null
        val wanted = nameKey(model.name)
        projectors
            .filter { val key = nameKey(it.name); key.isNotEmpty() && (wanted.startsWith(key) || key.startsWith(wanted)) }
            .maxByOrNull { nameKey(it.name).length }
            ?.let { return it }
        val models = ggufs.count { it !in projectors } + 1
        return projectors.singleOrNull()?.takeIf { models == 1 }
    }

    /**
     * A file name reduced to what identifies the model: lower case, no
     * extension, no `mmproj`, no quantization tag (Q4_K_M, f16, IQ3_XS...),
     * letters and digits only.
     */
    internal fun nameKey(name: String): String =
        name.lowercase()
            .removeSuffix(".$EXTENSION")
            .replace("mmproj", "")
            .replace(QUANT_TAG, "")
            .filter { it.isLetterOrDigit() }

    private val QUANT_TAG = Regex("""[-_.](i?q\d[a-z0-9_]*|bf16|f16|f32|fp16|model)(?=$|[-_.])""")

    private const val MAX_KEYS_READ = 64L

    /** Every key a real header has: [fileType] walks past the tokenizer's to find its own. */
    private const val MAX_KEYS_SCANNED = 1024L
    private const val MAX_KEY_BYTES = 256L
    private const val MAX_VALUE_BYTES = 4096L
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9

    /** Bytes of each scalar GGUF value type, by type id; 0 for string and array. */
    private val SCALAR_BYTES = intArrayOf(1, 1, 2, 2, 4, 4, 4, 1, 0, 0, 8, 8, 8)

    private fun DataInputStream.readLittleInt(): Int = Integer.reverseBytes(readInt())

    private fun DataInputStream.readLittleLong(): Long = java.lang.Long.reverseBytes(readLong())

    private fun DataInputStream.readGgufString(limit: Long): String? {
        val length = readLittleLong()
        if (length < 0 || length > limit) return null
        val bytes = ByteArray(length.toInt())
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataInputStream.skipFully(count: Long): Boolean {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
        return true
    }

    /** Skips one value of [type]; false for a type this reader does not know. */
    private fun DataInputStream.skipValue(type: Int): Boolean = when {
        type == TYPE_STRING -> skipFully(readLittleLong().takeIf { it >= 0 } ?: return false)
        type == TYPE_ARRAY -> {
            val elementType = readLittleInt()
            val count = readLittleLong()
            when {
                count < 0 -> false
                elementType == TYPE_STRING -> {
                    for (i in 0 until count) skipFully(readLittleLong().takeIf { it >= 0 } ?: return false)
                    true
                }
                elementType in SCALAR_BYTES.indices && SCALAR_BYTES[elementType] > 0 ->
                    skipFully(count * SCALAR_BYTES[elementType])
                else -> false
            }
        }
        type in SCALAR_BYTES.indices && SCALAR_BYTES[type] > 0 -> skipFully(SCALAR_BYTES[type].toLong())
        else -> false
    }
}
