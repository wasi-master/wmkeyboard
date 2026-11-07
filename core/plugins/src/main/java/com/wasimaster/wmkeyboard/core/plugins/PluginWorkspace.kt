package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ThreadLocalRandom

/** One plugin being written in the plugin editor, as the workspace index remembers it. */
@Serializable
data class PluginDraft(
    val draftId: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Where it came from: `blank`, `template:<id>`, `installed:<pluginId>`, `file` or `duplicate:<draftId>`. */
    val origin: String = "",
    /** When it was last published into the store, or 0 if it never was. */
    val publishedAt: Long = 0L,
    val publishedVersion: String = "",
)

/** A saved copy of a draft's script, kept so a phone author can step back across sessions. */
@Serializable
data class PluginSnapshot(
    val snapshotId: String = "",
    val at: Long = 0L,
    /** A [SnapshotReason.wire] value. */
    val reason: String = "",
    val characters: Int = 0,
    val lines: Int = 0,
    val note: String = "",
)

/** Why a snapshot was taken. [wire] is what the history index stores. */
enum class SnapshotReason(val wire: String) {
    MANUAL("manual"),
    PERIODIC("periodic"),
    BEFORE_RESTORE("before_restore"),
    PUBLISH("publish"),
    IMPORT("import"),
    ;

    companion object {
        fun parse(wire: String): SnapshotReason? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Drafts the plugin editor is working on, under `filesDir/plugin-ide/`:
 *
 * ```
 * plugin-ide/
 * ├── projects.json            the index
 * └── <draftId>/
 *     ├── plugin.json          the draft manifest, which may be invalid mid-edit
 *     ├── main.py              the draft script, autosaved
 *     ├── preview/             wm.storage and the log for preview runs only
 *     └── history/             index.json and one <snapshotId>.py per snapshot
 * ```
 *
 * Kept apart from [PluginStore] on purpose. The keyboard may load an enabled
 * plugin's `main.py` at any moment, so half-written code must never be there; it
 * lives here, and the installed copy changes only when the author publishes.
 * Preview storage lives here too, so a preview run can never read or overwrite
 * the data of the installed plugin with the same id.
 *
 * A draft id is generated, not the plugin id. Renaming the plugin moves no
 * directory, two drafts may aim at one plugin, and a draft whose manifest id is
 * not valid yet still has somewhere to live.
 *
 * Same shape and reasons as [PluginStore]: one per process, every mutator
 * writes immediately and atomically, [revision] is how Compose notices, and a
 * null directory during direct boot means empty rather than broken.
 *
 * A draft from when plugins were Lua is not thrown away with the language: on
 * read its script becomes a Python file holding the old code as comments, so
 * the author still has it to port, and its history stays readable.
 */
class PluginWorkspace(private var baseDir: File?) {

    @Serializable
    private data class Index(val version: Int = FORMAT_VERSION, val drafts: List<PluginDraft> = emptyList())

    @Serializable
    private data class History(val version: Int = FORMAT_VERSION, val snapshots: List<PluginSnapshot> = emptyList())

    private val draftList = ArrayList<PluginDraft>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _revision = MutableStateFlow(0)

    /** Bumped on every change, so Compose re-reads the workspace. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        reload()
    }

    companion object {
        @Volatile
        private var shared: PluginWorkspace? = null

        /** The one workspace for this process. */
        fun get(context: android.content.Context): PluginWorkspace =
            shared ?: synchronized(this) {
                shared ?: PluginWorkspace(workspaceDir(context)).also { shared = it }
            }

        /** Re-points the shared workspace after a direct-boot unlock. */
        fun attach(context: android.content.Context) {
            get(context).attach(workspaceDir(context))
        }

        private fun workspaceDir(context: android.content.Context): File? =
            if (DirectBoot.isUserUnlocked(context)) File(context.applicationContext.filesDir, DIR_NAME) else null

        /** 2: drafts are Python; a Lua draft is converted on read. */
        private const val FORMAT_VERSION = 2

        const val DIR_NAME = "plugin-ide"
        const val MAX_DRAFTS = 20
        const val MAX_SNAPSHOTS = 30

        /** Characters of snapshot bodies kept per draft: at least two copies of the largest script there can be. */
        const val MAX_SNAPSHOT_CHARACTERS = 512 * 1024

        const val MANIFEST_FILE = "plugin.json"
        const val SCRIPT_FILE = "main.py"

        private const val LUA_SCRIPT_FILE = "main.lua"
        private const val SNAPSHOT_EXT = ".py"

        /** What snapshots were saved as while drafts were Lua; still read. */
        private const val LUA_SNAPSHOT_EXT = ".lua"

        private const val MAX_NOTE = 80
        private const val INDEX_FILE = "projects.json"
        private const val PREVIEW_DIR = "preview"
        private const val HISTORY_DIR = "history"
        private const val HISTORY_INDEX = "index.json"

        /** A single safe path segment, the same rule [PluginStore] applies to plugin ids. */
        private val SAFE_ID = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")
    }

    // ---- reading -------------------------------------------------------

    /** Every draft, most recently edited first. */
    @Synchronized
    fun drafts(): List<PluginDraft> = draftList.sortedByDescending { it.updatedAt }

    @Synchronized
    fun draft(draftId: String): PluginDraft? = draftList.firstOrNull { it.draftId == draftId }

    /** The most recently edited draft whose manifest names [pluginId], so "Edit the code" reopens work in progress. */
    @Synchronized
    fun draftFor(pluginId: String): PluginDraft? = drafts().firstOrNull { manifest(it.draftId)?.id == pluginId }

    /** The draft's own directory, or null when the id is not a safe path segment or there is nowhere to write. */
    fun dirFor(draftId: String): File? {
        val dir = baseDir ?: return null
        if (!SAFE_ID.matches(draftId)) return null
        return File(dir, draftId)
    }

    fun previewStorageFile(draftId: String): File? = dirFor(draftId)?.let { File(File(it, PREVIEW_DIR), PluginStore.STORAGE_FILE) }

    fun previewLogFile(draftId: String): File? = dirFor(draftId)?.let { File(File(it, PREVIEW_DIR), PluginStore.LOG_FILE) }

    fun previewRemindersFile(draftId: String): File? =
        dirFor(draftId)?.let { File(File(it, PREVIEW_DIR), PluginStore.REMINDERS_FILE) }

    fun script(draftId: String): String? {
        val file = dirFor(draftId)?.let { File(it, SCRIPT_FILE) } ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /**
     * The draft manifest as it was last written, valid or not. Decoded directly
     * rather than through [PluginManifestCodec.read], which would refuse the very
     * manifests the editor exists to fix.
     */
    fun manifest(draftId: String): PluginManifest? {
        val file = dirFor(draftId)?.let { File(it, MANIFEST_FILE) } ?: return null
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<PluginManifest>(file.readText()) }.getOrNull()
    }

    // ---- writing -------------------------------------------------------

    /** Starts a draft from [manifest] and [script]. Null when the workspace is full or cannot be written. */
    @Synchronized
    fun create(manifest: PluginManifest, script: String, origin: String, now: Long = System.currentTimeMillis()): PluginDraft? {
        if (baseDir == null || draftList.size >= MAX_DRAFTS) return null
        val draftId = newId("d", now) { id -> draftList.any { it.draftId == id } || dirFor(id)?.exists() == true }
        val dir = dirFor(draftId) ?: return null
        val written = writeAtomically(File(dir, MANIFEST_FILE), PluginManifestCodec.encode(manifest)) &&
            writeAtomically(File(dir, SCRIPT_FILE), script)
        if (!written) {
            runCatching { dir.deleteRecursively() }
            return null
        }
        val draft = PluginDraft(draftId = draftId, createdAt = now, updatedAt = now, origin = origin)
        draftList += draft
        save()
        return draft
    }

    /** A new draft with the same manifest and script. History and preview data are not copied. */
    @Synchronized
    fun duplicate(draftId: String, now: Long = System.currentTimeMillis()): PluginDraft? {
        val manifest = manifest(draftId) ?: return null
        val script = script(draftId) ?: return null
        return create(manifest, script, "duplicate:$draftId", now)
    }

    /** Removes the draft and everything it owns: its history and its preview data. */
    @Synchronized
    fun delete(draftId: String) {
        val removed = draftList.removeAll { it.draftId == draftId }
        dirFor(draftId)?.let { dir -> runCatching { dir.deleteRecursively() } }
        if (removed) save()
    }

    @Synchronized
    fun writeScript(draftId: String, text: String, now: Long = System.currentTimeMillis()): Boolean {
        if (draft(draftId) == null) return false
        val file = dirFor(draftId)?.let { File(it, SCRIPT_FILE) } ?: return false
        if (!writeAtomically(file, text)) return false
        touch(draftId, now)
        return true
    }

    @Synchronized
    fun writeManifest(draftId: String, manifest: PluginManifest, now: Long = System.currentTimeMillis()): Boolean {
        if (draft(draftId) == null) return false
        val file = dirFor(draftId)?.let { File(it, MANIFEST_FILE) } ?: return false
        if (!writeAtomically(file, PluginManifestCodec.encode(manifest))) return false
        touch(draftId, now)
        return true
    }

    /** Records that the draft was published as [version]. */
    @Synchronized
    fun markPublished(draftId: String, version: String, now: Long = System.currentTimeMillis()) {
        val index = draftList.indexOfFirst { it.draftId == draftId }
        if (index < 0) return
        draftList[index] = draftList[index].copy(publishedAt = now, publishedVersion = version, updatedAt = now)
        save()
    }

    /**
     * Drops drafts whose script has gone, a directory deleted by hand or a create
     * interrupted part-way. Returns true if anything changed.
     */
    @Synchronized
    fun reconcile(): Boolean {
        if (baseDir == null) return false
        val gone = draftList.filter { script(it.draftId) == null }
        if (gone.isEmpty()) return false
        draftList.removeAll(gone.toSet())
        save()
        return true
    }

    // ---- history -------------------------------------------------------

    /** The draft's snapshots, newest first. */
    @Synchronized
    fun snapshots(draftId: String): List<PluginSnapshot> = readHistory(draftId).asReversed()

    fun snapshotBody(draftId: String, snapshotId: String): String? {
        if (!SAFE_ID.matches(snapshotId)) return null
        val dir = historyDir(draftId) ?: return null
        val file = listOf(SNAPSHOT_EXT, LUA_SNAPSHOT_EXT).map { File(dir, snapshotId + it) }.firstOrNull { it.isFile }
            ?: return null
        return runCatching { file.readText() }.getOrNull()
    }

    /**
     * Saves the draft's current script. Null when there is no script, or when it
     * is exactly the newest snapshot already: the same text is never kept twice.
     */
    @Synchronized
    fun snapshot(draftId: String, reason: SnapshotReason, note: String = "", now: Long = System.currentTimeMillis()): PluginSnapshot? {
        val text = script(draftId) ?: return null
        return keep(draftId, text, reason, note, now)
    }

    /**
     * Puts snapshot [snapshotId] back as the draft's script. The script it replaces
     * is snapshotted first, so a restore is itself a step that can be undone.
     */
    @Synchronized
    fun restore(draftId: String, snapshotId: String, now: Long = System.currentTimeMillis()): Boolean {
        val body = snapshotBody(draftId, snapshotId) ?: return false
        val current = script(draftId)
        if (current != null && current != body) keep(draftId, current, SnapshotReason.BEFORE_RESTORE, "", now)
        return writeScript(draftId, body, now)
    }

    private fun keep(draftId: String, text: String, reason: SnapshotReason, note: String, now: Long): PluginSnapshot? {
        val dir = historyDir(draftId) ?: return null
        val history = readHistory(draftId).toMutableList()
        val newest = history.lastOrNull()
        if (newest != null && snapshotBody(draftId, newest.snapshotId) == text) return null
        val snapshotId = newId("s", now) { id -> history.any { it.snapshotId == id } }
        if (!writeAtomically(File(dir, snapshotId + SNAPSHOT_EXT), text)) return null
        val entry = PluginSnapshot(
            snapshotId = snapshotId,
            at = now,
            reason = reason.wire,
            characters = text.length,
            lines = text.count { it == '\n' } + 1,
            note = note.take(MAX_NOTE),
        )
        history += entry
        prune(dir, history)
        writeAtomically(File(dir, HISTORY_INDEX), json.encodeToString(History(snapshots = history)))
        _revision.value++
        return entry
    }

    /**
     * Holds history to [MAX_SNAPSHOTS] and [MAX_SNAPSHOT_CHARACTERS]. Periodic
     * snapshots go first, then the oldest of any kind. The newest, the one just
     * taken, always stays.
     */
    private fun prune(dir: File, history: MutableList<PluginSnapshot>) {
        while (history.size > 1 && (history.size > MAX_SNAPSHOTS || history.sumOf { it.characters } > MAX_SNAPSHOT_CHARACTERS)) {
            val older = history.subList(0, history.size - 1)
            val victim = older.firstOrNull { it.reason == SnapshotReason.PERIODIC.wire } ?: older.first()
            history.remove(victim)
            runCatching {
                File(dir, victim.snapshotId + SNAPSHOT_EXT).delete()
                File(dir, victim.snapshotId + LUA_SNAPSHOT_EXT).delete()
            }
        }
    }

    private fun historyDir(draftId: String): File? = dirFor(draftId)?.let { File(it, HISTORY_DIR) }

    private fun readHistory(draftId: String): List<PluginSnapshot> {
        val file = historyDir(draftId)?.let { File(it, HISTORY_INDEX) } ?: return emptyList()
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString<History>(file.readText()).snapshots }
            .getOrDefault(emptyList())
            .filter { SAFE_ID.matches(it.snapshotId) }
    }

    // ---- persistence ---------------------------------------------------

    private fun touch(draftId: String, now: Long) {
        val index = draftList.indexOfFirst { it.draftId == draftId }
        if (index < 0) return
        draftList[index] = draftList[index].copy(updatedAt = now)
        save()
    }

    @Synchronized
    fun save() {
        val dir = baseDir
        if (dir != null) writeAtomically(File(dir, INDEX_FILE), json.encodeToString(Index(drafts = draftList.toList())))
        _revision.value++
    }

    /** Points the workspace at [dir] and re-reads it. The direct-boot unlock path. */
    @Synchronized
    fun attach(dir: File?) {
        if (dir?.absolutePath == baseDir?.absolutePath) return
        baseDir = dir
        reload()
    }

    @Synchronized
    fun reload() {
        draftList.clear()
        val dir = baseDir ?: run { _revision.value++; return }
        val index = File(dir, INDEX_FILE)
        if (index.isFile) {
            runCatching {
                draftList.addAll(
                    json.decodeFromString<Index>(index.readText()).drafts
                        .filter { SAFE_ID.matches(it.draftId) }
                        .distinctBy { it.draftId }
                        .take(MAX_DRAFTS),
                )
            }
        }
        draftList.forEach { convertLuaDraft(it.draftId) }
        _revision.value++
    }

    /**
     * Turns a Lua draft into a Python one the editor can open: the old code
     * kept as comments under a note, and the manifest pointed at `main.py`
     * and API 4. Does nothing for a draft that already has `main.py`.
     */
    private fun convertLuaDraft(draftId: String) {
        val dir = dirFor(draftId) ?: return
        val lua = File(dir, LUA_SCRIPT_FILE)
        val py = File(dir, SCRIPT_FILE)
        if (!lua.isFile || py.exists()) return
        val old = runCatching { lua.readText() }.getOrNull() ?: return
        if (!writeAtomically(py, luaAsComments(old))) return
        runCatching { lua.delete() }
        val manifest = manifest(draftId) ?: return
        val entry = manifest.entry.takeUnless { it.isBlank() || it.endsWith(".lua") } ?: PluginManifestCodec.DEFAULT_ENTRY
        writeAtomically(
            File(dir, MANIFEST_FILE),
            PluginManifestCodec.encode(
                manifest.copy(
                    apiVersion = PluginManifestCodec.API_VERSION,
                    language = PluginManifestCodec.LANGUAGE_PYTHON,
                    entry = entry,
                ),
            ),
        )
    }

    private fun luaAsComments(source: String): String = buildString {
        append("# This draft was written in Lua, which plugins no longer use.\n")
        append("# The Lua code is kept below as comments, to port to Python.\n\n")
        append("def render():\n    return ui.label(\"Not ported yet\")\n\n")
        source.lines().forEach { line -> append(if (line.isEmpty()) "#" else "# $line").append('\n') }
    }

    /** `d` or `s`, the time in base 36, and four random hex digits. Locale-free, and a safe path segment. */
    private fun newId(prefix: String, now: Long, taken: (String) -> Boolean): String {
        while (true) {
            val suffix = ThreadLocalRandom.current().nextInt(0x10000).toString(16).padStart(4, '0')
            val id = prefix + now.coerceAtLeast(0L).toString(36) + "-" + suffix
            if (!taken(id)) return id
        }
    }

    private fun writeAtomically(file: File, text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(text)
        part.renameTo(file) || run {
            file.delete()
            part.renameTo(file)
        }
    }.getOrDefault(false)
}
