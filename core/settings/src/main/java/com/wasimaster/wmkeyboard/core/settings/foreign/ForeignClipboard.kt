package com.wasimaster.wmkeyboard.core.settings.foreign

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** One text clip from another keyboard's history (#1542). */
data class ForeignClip(val text: String, val timestamp: Long?, val pinned: Boolean)

/**
 * The clipboard history in another keyboard's backup (#1542), for Import into
 * a new clipboard. The whole-backup import leaves it behind on purpose
 * ([LeftBehind.Kind.CLIPBOARD]); this is the place the user asks for it.
 *
 * Where each keyboard keeps it:
 *
 * - FUTO Keyboard: `clipboard.json` at the root of its backup, a list of
 *   `{timestamp, pinned, text, uri, mimeTypes}`.
 * - FlorisBoard: `clipboard/clipboard_text_items.json`, a list of
 *   `{type, text, creationTimestampMs, isPinned, isSensitive}`.
 * - CleverKeys: `clipboard_history.json` in a full backup, or the same JSON
 *   exported on its own; its history, pinned and to-do lists are all read.
 * - HeliBoard and LeanType: the `CLIPBOARD` table of `heliboard.db` /
 *   `leantype.db` (`TIMESTAMP`, `PINNED`, `TEXT`), read through [sqlite],
 *   which only Android has.
 *
 * The JSON is walked rather than decoded into one shape each, since every
 * keyboard has moved its fields around between versions: any list of objects
 * with a text field counts, with a time and a pin read from whichever of the
 * usual names is there. Pictures, videos and files are left out, since their
 * bytes are not in a form this app's history can keep, and so are clips the
 * other keyboard marked sensitive.
 */
object ForeignClipboard {

    /** Past this, one entry is not a clipboard any keyboard writes. */
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024

    /** Most clips one import takes. */
    const val MAX_CLIPS = 20_000

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private val TEXT_KEYS = listOf("text", "content", "clipText", "value", "data")
    private val TIME_KEYS = listOf("timestamp", "timeStamp", "creationTimestampMs", "createdAt", "created", "time", "date")
    private val PIN_KEYS = listOf("pinned", "isPinned", "is_pinned")
    private val SENSITIVE_KEYS = listOf("isSensitive", "sensitive", "is_sensitive")
    private val MEDIA_TYPES = setOf("IMAGE", "VIDEO", "FILE", "2", "3")

    /**
     * The clips in a backup ZIP, or null when it holds none this can read.
     * Streams the archive: a FUTO backup with its models is hundreds of
     * megabytes, and only the clipboard entries are kept. [sqlite] reads a
     * HeliBoard database file; null skips those.
     */
    fun readZip(input: InputStream, sqlite: ((File) -> List<ForeignClip>)? = null, scratch: File? = null): List<ForeignClip>? {
        val clips = ArrayList<ForeignClip>()
        var any = false
        runCatching {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.trimStart('/')
                    if (name.split('/').any { it == ".." }) continue
                    val leaf = name.substringAfterLast('/')
                    when {
                        isClipboardJson(name) -> {
                            val bytes = readCapped(zip) ?: continue
                            fromJson(bytes.decodeToString())?.let { any = true; clips += it }
                        }
                        sqlite != null && scratch != null && (leaf == "heliboard.db" || leaf == "leantype.db") -> {
                            val file = File(scratch, "foreign-clipboard-${System.nanoTime()}.db")
                            try {
                                file.outputStream().use { out -> zip.copyTo(out) }
                                val read = runCatching { sqlite(file) }.getOrDefault(emptyList())
                                if (read.isNotEmpty()) any = true
                                clips += read
                            } finally {
                                file.delete()
                                File(file.path + "-journal").delete()
                            }
                        }
                    }
                }
            }
        }
        return clips.takeIf { any }?.let(::tidy)
    }

    /** Whether a ZIP entry is one of the clipboard files the keyboards above write. */
    internal fun isClipboardJson(name: String): Boolean {
        val leaf = name.substringAfterLast('/')
        return leaf == "clipboard.json" || leaf == "clipboard_text_items.json" || leaf == "clipboard_history.json"
    }

    /**
     * The clips in a JSON file another keyboard wrote, or null when it is
     * not JSON or holds no list of clips.
     */
    fun fromJson(text: String): List<ForeignClip>? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val out = ArrayList<ForeignClip>()
        walk(root, pinnedList = false, out = out, depth = 0)
        return out.takeIf { it.isNotEmpty() }?.let(::tidy)
    }

    private fun walk(element: JsonElement, pinnedList: Boolean, out: MutableList<ForeignClip>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_CLIPS) return
        when (element) {
            is JsonArray -> for (item in element) {
                val clip = (item as? JsonObject)?.let { clipOf(it, pinnedList) }
                if (clip != null) out += clip else walk(item, pinnedList, out, depth + 1)
            }
            is JsonObject -> for ((key, value) in element) {
                // CleverKeys keeps its pinned clips in a list of their own.
                val pinned = pinnedList || key.contains("pinned", ignoreCase = true)
                if (value is JsonArray || value is JsonObject) walk(value, pinned, out, depth + 1)
            }
            else -> Unit
        }
    }

    private fun clipOf(entry: JsonObject, pinnedList: Boolean): ForeignClip? {
        val text = TEXT_KEYS.firstNotNullOfOrNull { key ->
            (entry[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val type = (entry["type"] as? JsonPrimitive)?.contentOrNull?.uppercase()
        if (type != null && type in MEDIA_TYPES) return null
        if (SENSITIVE_KEYS.any { (entry[it] as? JsonPrimitive)?.booleanOrNull == true }) return null
        val time = TIME_KEYS.firstNotNullOfOrNull { key -> (entry[key] as? JsonPrimitive)?.longOrNull }
            ?.let { if (it in 1..SECONDS_BEFORE_2100) it * 1000 else it }
        val pinned = pinnedList || PIN_KEYS.any { (entry[it] as? JsonPrimitive)?.booleanOrNull == true }
        return ForeignClip(text, time, pinned)
    }

    /** Each text once, the pinned copy winning, newest first, at most [MAX_CLIPS]. */
    private fun tidy(clips: List<ForeignClip>): List<ForeignClip> {
        val byText = LinkedHashMap<String, ForeignClip>()
        for (clip in clips) {
            val seen = byText[clip.text]
            byText[clip.text] = when {
                seen == null -> clip
                else -> seen.copy(
                    pinned = seen.pinned || clip.pinned,
                    timestamp = listOfNotNull(seen.timestamp, clip.timestamp).maxOrNull(),
                )
            }
        }
        return byText.values.sortedByDescending { it.timestamp ?: Long.MIN_VALUE }.take(MAX_CLIPS)
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_ENTRY_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * HeliBoard's or LeanType's clipboard table in [file], a copy of the
     * database from its backup. Android only; a file that is not that
     * database gives nothing.
     */
    fun readHeliDatabase(file: File): List<ForeignClip> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        return db.use {
            it.rawQuery("SELECT TIMESTAMP, PINNED, TEXT FROM CLIPBOARD WHERE TEXT IS NOT NULL", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < MAX_CLIPS) {
                        val text = cursor.getString(2)?.trim().orEmpty()
                        if (text.isEmpty()) continue
                        add(ForeignClip(text, cursor.getLong(0).takeIf { t -> t > 0 }, cursor.getInt(1) != 0))
                    }
                }
            }
        }
    }

    private const val MAX_DEPTH = 8

    /** A time below this is in seconds, not milliseconds (it is 2100 in seconds). */
    private const val SECONDS_BEFORE_2100 = 4_102_444_800L
}
