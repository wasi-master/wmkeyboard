package com.wasimaster.wmkeyboard.core.vocab

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class VocabEnvelope(
    val format: String = "",
    val version: Int = 0,
    val appVersion: Int = 0,
    val appVersionName: String = "",
    val pack: VocabPackMeta = VocabPackMeta(),
    val words: List<VocabWord> = emptyList(),
)

/**
 * The `.wmvocab.json` file: a vocabulary pack, as the data repository hosts
 * it (gzipped) and as the app exports a list the user made.
 *
 * ```json
 * {
 *   "format": "wmkeyboard-vocab",
 *   "version": 1,
 *   "appVersion": 41,
 *   "appVersionName": "1.4.0",
 *   "pack": { "id": "ws1", "name": "Word Smart 1", "langId": "en", … },
 *   "words": [ { "word": "abhor", "pos": ["verb"], "senses": [ … ] } ]
 * }
 * ```
 *
 * Same versioned envelope as `SnippetFile`: `format` is the one strict check,
 * unknown keys are ignored so a newer pack still reads, and bad rows are
 * repaired rather than refused. Lemmas are lowercased and trimmed on the way
 * in because the trigger index keys on them exactly.
 */
object VocabPackFile {

    const val FORMAT = "wmkeyboard-vocab"

    /** Documentary; nothing reads it. Additive fields never bump it. */
    const val VERSION = 1

    const val FILE_EXTENSION = "wmvocab.json"

    /** Plain JSON so file managers and chat apps offer the file at all. */
    const val MIME_TYPE = "application/json"

    /** Permissive on purpose; the format tag inside is the real check. */
    val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream", "application/gzip")

    /** Inflated size past which a file is a dump, not a pack. */
    const val MAX_BYTES = 8L * 1024 * 1024

    /** A study list past this is a dictionary; the index would drown. */
    const val MAX_WORDS = 5_000

    private const val GZIP_MAGIC_1 = 0x1f
    private const val GZIP_MAGIC_2 = 0x8b

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val prettyJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
        prettyPrint = true
    }

    fun fileName(meta: VocabPackMeta): String {
        val base = meta.name.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().take(48).ifEmpty { "vocabulary" }
        return "$base.$FILE_EXTENSION"
    }

    /** Writes a pack in the app's own hand, pretty-printed for a human to read. */
    fun encode(pack: VocabPack, appVersion: Int, appVersionName: String): String =
        prettyJson.encodeToString(
            VocabEnvelope(
                format = FORMAT,
                version = VERSION,
                appVersion = appVersion,
                appVersionName = appVersionName,
                pack = pack.meta,
                words = pack.words,
            ),
        )

    /** Parses [text], or returns null when it is not a vocabulary pack at all. */
    fun decode(text: String): VocabPack? {
        val envelope = runCatching { json.decodeFromString<VocabEnvelope>(text) }.getOrNull()
            ?: return null
        if (envelope.format != FORMAT) return null
        val seen = HashSet<String>()
        val words = ArrayList<VocabWord>(envelope.words.size)
        for (word in envelope.words) {
            if (words.size >= MAX_WORDS) break
            val lemma = normalizeLemma(word.word) ?: continue
            if (!seen.add(lemma)) continue
            words += if (lemma == word.word) word else word.copy(word = lemma)
        }
        return VocabPack(meta = envelope.pack, words = words)
    }

    /**
     * Parses a pack from [stream], inflating it first when the bytes are
     * gzip — the hosted packs are, a file the app exported is not, and the
     * reader should not care which it was handed. Reads at most [MAX_BYTES].
     */
    fun decode(stream: InputStream): VocabPack? {
        val pushback = PushbackInputStream(stream, 2)
        val first = pushback.read()
        val second = pushback.read()
        if (first < 0) return null
        if (second >= 0) pushback.unread(second)
        pushback.unread(first)
        val source: InputStream = if (first == GZIP_MAGIC_1 && second == GZIP_MAGIC_2) {
            GZIPInputStream(pushback, 32 * 1024)
        } else {
            pushback
        }
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        source.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) return null
                bytes.write(buffer, 0, read)
            }
        }
        return decode(bytes.toString(Charsets.UTF_8.name()))
    }

    /** The lemma as the index keys it: trimmed, lowercased, single-spaced. */
    fun normalizeLemma(raw: String): String? {
        val lemma = raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return lemma.takeIf { it.isNotEmpty() && it.length <= MAX_LEMMA_LENGTH }
    }

    private const val MAX_LEMMA_LENGTH = 64
}
