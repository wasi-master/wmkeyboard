package com.wasimaster.wmkeyboard.core.tools.pronounce

import android.content.Context
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Recorded pronunciations kept on the phone (#1146), so a word heard once
 * plays again with no connection: the audio files, and for each word the list
 * of voices it had, which is what the card shows when the sources cannot be
 * asked. Kept in the app's files when the user wants them for offline use and
 * in its cache otherwise, where Android may clear them. Either way the folder
 * is held to [MAX_BYTES], the files played longest ago going first.
 */
class PronunciationStore(private val dir: File) {

    /** The voices [word] in [language] had when it was last looked up, or empty. */
    fun remembered(word: String, language: String): List<Recording> {
        val file = indexFile(word, language)
        if (!file.isFile) return emptyList()
        return runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    /** Keeps [recordings] as the voices of [word] in [language]. */
    fun remember(word: String, language: String, recordings: List<Recording>) {
        runCatching {
            val file = indexFile(word, language)
            file.parentFile?.mkdirs()
            file.writeText(encode(recordings))
        }
    }

    /** The recording's file when it was fetched before, touched so the trim keeps it longest. */
    fun local(recording: Recording): File? =
        audioFile(recording).takeIf { it.isFile && it.length() > 0 }?.also { it.setLastModified(System.currentTimeMillis()) }

    /**
     * The recording's file, fetched now when it is not here yet. Blocking;
     * call on an IO dispatcher. Throws when the fetch fails.
     */
    fun fetch(recording: Recording): File {
        local(recording)?.let { return it }
        val target = audioFile(recording)
        ToolHttp.download(recording.url, target, maxBytes = MAX_FILE_BYTES, source = NetSource.DICTIONARY, route = "/pronunciation")
        trim()
        return target
    }

    /** Deletes every kept recording and list. */
    fun clear() {
        dir.deleteRecursively()
    }

    /** Bytes the kept recordings take up. */
    fun size(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun trim() {
        val files = dir.listFiles { file -> file.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private fun audioFile(recording: Recording): File {
        val extension = recording.url.substringAfterLast('.', "").substringBefore('?').lowercase(Locale.ROOT)
            .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "audio"
        return File(dir, hash(recording.url) + "." + extension)
    }

    private fun indexFile(word: String, language: String): File =
        File(File(dir, "words"), hash(language.lowercase(Locale.ROOT) + "\t" + word.trim().lowercase(Locale.ROOT)) + ".json")

    companion object {
        /** All kept recordings together; a few hundred words' worth. */
        const val MAX_BYTES = 24L * 1024 * 1024

        /** One recording: a word is seconds long, so anything bigger is not one. */
        const val MAX_FILE_BYTES = 4L * 1024 * 1024

        /** The store [keep] asks for: the app's files to keep, its cache otherwise. */
        fun open(context: Context, keep: Boolean): PronunciationStore =
            PronunciationStore(File(if (keep) context.filesDir else context.cacheDir, "pronunciations"))

        internal fun encode(recordings: List<Recording>): String = JsonArray(
            recordings.map { recording ->
                JsonObject(
                    mapOf(
                        "url" to JsonPrimitive(recording.url),
                        "label" to JsonPrimitive(recording.label),
                        "source" to JsonPrimitive(recording.source.name),
                    ),
                )
            },
        ).toString()

        internal fun decode(text: String): List<Recording> =
            (Json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = item["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val label = item["label"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val source = PronunciationSource.entries.firstOrNull { it.name == item["source"]?.jsonPrimitive?.content }
                    ?: return@mapNotNull null
                Recording(url, label, source)
            }

        private fun hash(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
