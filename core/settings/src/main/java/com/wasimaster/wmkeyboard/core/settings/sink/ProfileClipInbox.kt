package com.wasimaster.wmkeyboard.core.settings.sink

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Clips handed to this copy of the keyboard while the keyboard itself was
 * not running (#1556): one the copy in the other profile sent over the link,
 * or a text shared to "Add to WM Keyboard clipboard" from the share sheet,
 * which is how a clip reaches a work profile whose keyboard has not started.
 * Kept in a small file, so they survive the process going, until the keyboard
 * starts and takes them into its history; see [ProfileLink.attachClipReceiver].
 */
object ProfileClipInbox {

    /** One waiting clip; [shared] when it came through the share sheet rather than the link. */
    data class Entry(val text: String, val shared: Boolean)

    private const val FILE = "profile-clips.json"

    /** Most clips kept for a keyboard that has not started; the oldest go first. */
    private const val MAX = 50

    /** Longer than any text worth a clip; a larger share is cut here. */
    private const val MAX_CHARS = 200_000

    private val lock = Any()

    private fun file(context: Context): File = File(context.noBackupFilesDir, FILE)

    fun add(context: Context, text: String, shared: Boolean) {
        if (text.isBlank()) return
        synchronized(lock) {
            val held = read(context).toMutableList()
            held += Entry(text.take(MAX_CHARS), shared)
            while (held.size > MAX) held.removeAt(0)
            write(context, held)
        }
    }

    /**
     * Every waiting clip, oldest first, and the file emptied. Nothing while
     * the phone is still locked after a restart, when the file cannot be read.
     */
    fun take(context: Context): List<Entry> = synchronized(lock) {
        val held = read(context)
        if (held.isNotEmpty()) runCatching { file(context).delete() }
        held
    }

    private fun read(context: Context): List<Entry> =
        runCatching {
            val file = file(context)
            if (!file.isFile) return@runCatching emptyList<Entry>()
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("t")
                if (text.isBlank()) null else Entry(text, o.optBoolean("s"))
            }
        }.getOrDefault(emptyList())

    private fun write(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("t", it.text).put("s", it.shared)) }
        runCatching {
            val file = file(context)
            val part = File(file.parentFile, "$FILE.part")
            part.writeText(array.toString())
            if (!part.renameTo(file)) {
                file.writeText(array.toString())
                part.delete()
            }
        }
    }
}
