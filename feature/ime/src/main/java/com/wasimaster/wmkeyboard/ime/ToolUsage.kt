package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.ToolKey
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * How often and how lately each tool has run, for the toolbox's "Most used"
 * and "Recently used" orders ([com.wasimaster.wmkeyboard.core.settings.ToolboxSort]).
 * Persisted as JSON beside the emoji history, and kept the same way
 * ([com.wasimaster.wmkeyboard.core.emoji.EmojiUsage]): a dirty flag, and a save
 * on every dismissal that writes only when something ran.
 *
 * Tools are stored by [ToolKey.token]: a built-in tool's name, or
 * `plugin:<id>` for a plugin shown as a tool, so its uses rank it beside the
 * built-in ones. One a later build drops or renames is skipped on load rather
 * than failing the whole file; an uninstalled plugin's count is kept, and
 * counts again if it comes back.
 */
class ToolUsage(storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val recents: List<String> = emptyList(),
        val counts: Map<String, Int> = emptyMap(),
    )

    /**
     * Every tool ever run, latest first. Bounded by the enum and the plugins
     * installed, so trimmed only at [MAX_ENTRIES].
     */
    private val recents = ArrayDeque<ToolKey>()
    private val counts = HashMap<ToolKey, Int>()
    private val json = Json { ignoreUnknownKeys = true }
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    init {
        load()
    }

    private fun load() {
        snapshotFile?.file?.takeIf { it.exists() }?.let { file ->
            runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText())
                snapshot.recents.mapNotNullTo(recents, ::toolOf)
                for ((name, count) in snapshot.counts) {
                    toolOf(name)?.let { counts[it] = count }
                }
            }
        }
    }

    private fun toolOf(name: String): ToolKey? = ToolKey.of(name)

    @Synchronized
    fun record(tool: ToolKey) {
        recents.remove(tool)
        recents.addFirst(tool)
        counts.merge(tool, 1, Int::plus)
        // Plugins come and go, so the list is capped where the enum never
        // needed it to be: the oldest drops off, count and all.
        while (recents.size > MAX_ENTRIES) counts.remove(recents.removeLast())
        dirty = true
    }

    /** The tools ever run, latest first. */
    @Synchronized
    fun recent(): List<ToolKey> = recents.toList()

    /** The tools ever run, most runs first; a tie goes to the one run last. */
    @Synchronized
    fun mostUsed(): List<ToolKey> {
        val lately = recents.withIndex().associate { it.value to it.index }
        return counts.keys.sortedWith(
            compareByDescending<ToolKey> { counts[it] ?: 0 }
                .thenBy { lately[it] ?: Int.MAX_VALUE },
        )
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(
                recents.map { it.token },
                counts.entries.associate { it.key.token to it.value },
            )
        }
        if (!file.write(ticket) { json.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    companion object {
        const val FILE_PATH = "learning/tool_usage.json"

        /** The most tools remembered: every built-in one, and room for a good many plugins. */
        const val MAX_ENTRIES = 256
    }
}
