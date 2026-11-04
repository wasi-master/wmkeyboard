package com.wasimaster.wmkeyboard.core.feedback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Reads a Clink sound pack (`.clinkpack`), the format of the iOS keyboard
 * Clink's sound repositories (`anti-ltd/clink-sounds` and its forks).
 *
 * A pack is one JSON file: a `pack` object (`id`, `name`, `blurb`,
 * `sampleNames`, `fileExtension`, `gain`) and a `samples` array holding each
 * recording as base64, in the order `sampleNames` lists them. Clink plays one
 * of them, picked at random, on every key, which is this app's board-wide
 * `press` set.
 *
 * Rather than a second installer, this writes the pack out as a `.wmsoundpack`
 * (a ZIP of `pack.json` and the recordings) and leaves the rest to
 * [SoundPackFile.import]: the caps, the playable-header sniff and the storage
 * are all that importer's, and a Clink pack is refused by exactly the rules a
 * native one would be.
 */
object ClinkSoundPack {

    const val FILE_EXTENSION = "clinkpack"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Pack(
        val id: String = "",
        val name: String = "",
        val blurb: String = "",
        val sampleNames: List<String> = emptyList(),
        val fileExtension: String = "wav",
        val gain: Float = 1f,
    )

    @Serializable
    private data class PackFile(
        val pack: Pack = Pack(),
        val samples: List<String> = emptyList(),
    )

    /** Whether [text], a whole file, reads as a Clink pack. */
    fun looksLike(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") && trimmed.contains("\"pack\"") && trimmed.contains("\"samples\"")
    }

    /**
     * [text] as the bytes of a `.wmsoundpack`, or null when it is not a Clink
     * pack or holds no recording. Sample bytes are copied as they decode; which
     * of them are playable is for [SoundPackFile.import] to say.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun toWmSoundPack(text: String): ByteArray? {
        if (!looksLike(text)) return null
        val source = runCatching { json.decodeFromString<PackFile>(text) }.getOrNull() ?: return null
        val extension = source.pack.fileExtension.filter { it.isLetterOrDigit() }.ifEmpty { "wav" }.take(MAX_EXTENSION)
        val samples = source.samples.take(SoundPackFile.MAX_VARIANTS).mapIndexedNotNull { index, encoded ->
            val bytes = runCatching { Base64.Default.decode(encoded.filterNot { it.isWhitespace() }) }.getOrNull()
                ?: return@mapIndexedNotNull null
            if (bytes.isEmpty()) return@mapIndexedNotNull null
            "sample_$index.$extension" to bytes
        }
        if (samples.isEmpty()) return null

        val manifest = SoundPackManifest(
            format = SoundPackFile.FORMAT,
            version = SoundPackFile.VERSION,
            id = source.pack.id,
            name = source.pack.name,
            description = source.pack.blurb,
            gain = source.pack.gain.coerceIn(0f, 1f),
            press = samples.map { it.first },
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(SoundPackFile.MANIFEST))
            zip.write(json.encodeToString(manifest).toByteArray())
            zip.closeEntry()
            for ((name, bytes) in samples) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private const val MAX_EXTENSION = 8
}
