package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** An installed plugin, as the index remembers it. */
@Serializable
data class InstalledPlugin(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val apiVersion: Int = 1,
    /** Declared permission wire strings, as accepted at install time. */
    val permissions: List<String> = emptyList(),
    val enabled: Boolean = true,
    val installedAt: Long = 0L,
    /**
     * How many times this plugin's thread had to be abandoned for running away.
     * At [PluginStore.MAX_ABANDONS] the plugin is disabled and stays that way
     * until the user turns it back on — a script that hangs the keyboard twice
     * has earned the benefit of the doubt being withdrawn.
     */
    val abandonedCount: Int = 0,
    /** [PluginManifestCodec.LANGUAGE_LUA] or [PluginManifestCodec.LANGUAGE_PYTHON]. */
    val language: String = PluginManifestCodec.LANGUAGE_LUA,
    /** False for a plugin of actions alone, which the keyboard's plugin list leaves out. */
    val panel: Boolean = true,
    /**
     * The selection actions it declared, copied here so the keyboard can draw
     * their chips on every selection change without reading `plugin.json`.
     */
    val actions: List<PluginActionSpec> = emptyList(),
) {
    val isPython: Boolean
        get() = language == PluginManifestCodec.LANGUAGE_PYTHON

    /** The declared permissions this build understands; unknown ones never survive install. */
    val grantedPermissions: List<PluginPermission>
        get() = permissions.mapNotNull { PluginPermission.parse(it) }

    fun grants(permission: PluginPermission): Boolean = permission.wire in permissions
}

/** One selection action, with the plugin that declared it. */
data class PluginActionRef(val plugin: InstalledPlugin, val action: PluginActionSpec) {
    /** Stable across launches and unique across plugins: what settings and the bar key on. */
    val key: String
        get() = keyOf(plugin.id, action.id)

    companion object {
        const val PREFIX = "plugin:"

        fun keyOf(pluginId: String, actionId: String): String = "$PREFIX$pluginId/$actionId"

        /** The plugin and action ids in [key], or null when it is not a plugin action key. */
        fun parse(key: String): Pair<String, String>? {
            if (!key.startsWith(PREFIX)) return null
            val rest = key.removePrefix(PREFIX)
            val slash = rest.lastIndexOf('/')
            if (slash <= 0 || slash == rest.length - 1) return null
            return rest.substring(0, slash) to rest.substring(slash + 1)
        }
    }
}

/** The outcome of writing a plugin into the store. */
sealed interface PluginAdoptResult {
    /** [replaced] is true when this overwrote an install of the same id. */
    data class Adopted(val plugin: InstalledPlugin, val replaced: Boolean) : PluginAdoptResult

    data object TooManyPlugins : PluginAdoptResult

    /** Nowhere to write — locked storage, or the disk said no. */
    data object Failed : PluginAdoptResult
}

/**
 * Installed plugins and their files, under `filesDir/plugins/`:
 *
 * ```
 * plugins/
 * ├── plugins.json                the index
 * └── <pluginId>/
 *     ├── plugin.json             the manifest as accepted
 *     ├── main.lua                the script as imported (a Python plugin's too)
 *     ├── storage.json            wm.storage, if the plugin declared it
 *     ├── reminders.json          wm.reminders still to show, if it declared that
 *     └── log.txt                 print() output and script errors
 * ```
 *
 * Same shape and the same reasons as
 * [com.wasimaster.wmkeyboard.core.addons.AddonStore]: one store per process,
 * every mutator writes immediately, [revision] is how Compose notices, and a
 * null directory during direct boot means the store is simply empty rather than
 * broken. Plugins are wholly unavailable before first unlock — scripts and their
 * data live in credential-encrypted storage, and a lock-screen keyboard has no
 * business running third-party code anyway.
 *
 * The id is the directory name, which is why [PluginManifestCodec] proves it is
 * a single safe lowercase path segment before anything reaches this class, and
 * why [dirFor] re-checks it rather than trusting its caller.
 */
class PluginStore(private var baseDir: File?) {

    @Serializable
    private data class Snapshot(
        val version: Int = FORMAT_VERSION,
        val plugins: List<InstalledPlugin> = emptyList(),
        val subsystemEnabled: Boolean = false,
        // Defaults true so an index written before this existed keeps the
        // behaviour it was written under.
        val autoDisableOnAbandon: Boolean = true,
    )

    private val pluginList = ArrayList<InstalledPlugin>()
    private var subsystem = false
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _revision = MutableStateFlow(0)

    /** Bumped on every change, so Compose and the IME re-read the store. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private var autoDisable = true

    init {
        reload()
    }

    companion object {
        @Volatile
        private var shared: PluginStore? = null

        /** The one store for this process. */
        fun get(context: android.content.Context): PluginStore =
            shared ?: synchronized(this) {
                shared ?: PluginStore(pluginsDir(context)).also { shared = it }
            }

        /** Re-points the shared store after a direct-boot unlock. */
        fun attach(context: android.content.Context) {
            get(context).attach(pluginsDir(context))
        }

        private fun pluginsDir(context: android.content.Context): File? =
            if (DirectBoot.isUserUnlocked(context)) {
                File(context.applicationContext.filesDir, DIR_NAME)
            } else {
                null
            }

        private const val FORMAT_VERSION = 1

        const val DIR_NAME = "plugins"

        /** Well past any real collection; a list this long is a mistake, not a use case. */
        const val MAX_PLUGINS = 50

        /** Runaway abandonments before a plugin is disabled for the user. */
        const val MAX_ABANDONS = 2

        const val MANIFEST_FILE = "plugin.json"
        const val SCRIPT_FILE = "main.lua"
        const val STORAGE_FILE = "storage.json"
        const val LOG_FILE = "log.txt"
        const val REMINDERS_FILE = "reminders.json"

        private const val INDEX_FILE = "plugins.json"

        /**
         * The same shape [PluginManifestCodec] enforces, re-stated here because
         * this class turns an id into a filesystem path and must not depend on
         * having been called correctly.
         */
        private val SAFE_ID = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")
    }

    // ---- the master switch ---------------------------------------------

    /**
     * Whether the plugin subsystem is on at all. **Off until the user turns it
     * on**, and while it is off nothing installs and nothing runs.
     *
     * Kept here rather than in `KeyboardSettings` for two reasons. That class is
     * within a handful of fields of the JVM limit on `copy$default` parameters,
     * and it is re-emitted to the IME on every settings change — putting a flag
     * there that only two screens and one panel ever read would push it through
     * the keyboard's hot path for nothing.
     */
    @Synchronized
    fun subsystemEnabled(): Boolean = subsystem

    @Synchronized
    fun setSubsystemEnabled(enabled: Boolean) {
        if (subsystem == enabled) return
        subsystem = enabled
        save()
    }

    /**
     * Whether [recordAbandon] may switch a plugin off once it reaches
     * [MAX_ABANDONS]. On, which is what the store always did.
     *
     * Off is for the plugin's author, and for anyone on a phone slow enough to
     * be the real cause. Two timeouts permanently disable a plugin that works,
     * and from in here there is no telling "this script hangs" from "this
     * device was busy". The strikes are counted either way, so turning this off
     * gives up the intervention and keeps the evidence.
     *
     * Stored beside [subsystemEnabled] and for the same reasons: it belongs to
     * the plugin subsystem, only its own screen reads it, and `KeyboardSettings`
     * is close enough to the JVM `copy$default` limit that a flag no hot path
     * touches has no business being pushed through it.
     */
    @Synchronized
    fun autoDisableOnAbandon(): Boolean = autoDisable

    @Synchronized
    fun setAutoDisableOnAbandon(enabled: Boolean) {
        if (autoDisable == enabled) return
        autoDisable = enabled
        save()
    }

    // ---- reading -------------------------------------------------------

    @Synchronized
    fun plugins(): List<InstalledPlugin> = pluginList.toList()

    @Synchronized
    fun plugin(id: String): InstalledPlugin? = pluginList.firstOrNull { it.id == id }

    /** Plugins that may actually run right now. */
    @Synchronized
    fun runnablePlugins(): List<InstalledPlugin> = pluginList.filter { it.enabled }

    /** Runnable plugins that draw a panel: what the keyboard's plugin list offers. */
    @Synchronized
    fun panelPlugins(): List<InstalledPlugin> = pluginList.filter { it.enabled && it.panel }

    /**
     * Every selection action that may run right now, in install order: the
     * subsystem is on, the plugin is on and granted [PluginPermission.SelectedText].
     */
    @Synchronized
    fun selectionActions(): List<PluginActionRef> {
        if (!subsystem) return emptyList()
        return pluginList
            .filter { it.enabled && it.grants(PluginPermission.SelectedText) }
            .flatMap { plugin -> plugin.actions.map { PluginActionRef(plugin, it) } }
    }

    /**
     * The plugin's own directory, or null when the id is not a safe path segment
     * or there is nowhere to write. Never creates it.
     */
    fun dirFor(id: String): File? {
        val dir = baseDir ?: return null
        if (!SAFE_ID.matches(id)) return null
        return File(dir, id)
    }

    fun manifestFile(id: String): File? = dirFor(id)?.let { File(it, MANIFEST_FILE) }

    fun scriptFile(id: String): File? = dirFor(id)?.let { File(it, SCRIPT_FILE) }

    fun storageFile(id: String): File? = dirFor(id)?.let { File(it, STORAGE_FILE) }

    fun logFile(id: String): File? = dirFor(id)?.let { File(it, LOG_FILE) }

    fun remindersFile(id: String): File? = dirFor(id)?.let { File(it, REMINDERS_FILE) }

    /** The Lua source, or null if it has gone missing — which the reconciler treats as uninstalled. */
    fun script(id: String): String? {
        val file = scriptFile(id) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    // ---- writing -------------------------------------------------------

    /**
     * Writes [manifest] and [script] into the store, replacing any install with
     * the same id.
     *
     * An update keeps `storage.json` — the user's todo list should survive the
     * plugin that owns it being upgraded — and by default resets
     * [InstalledPlugin.enabled] and the abandonment strikes, since a new script
     * from somewhere else deserves to be judged on its own behaviour rather than
     * the old one's.
     *
     * [keepState] is the plugin editor's publish, where the new script is the
     * user's own next edit of the same plugin. Switching back on a plugin they
     * turned off, or forgiving strikes the runaway guard counted, is not what
     * saving an edit means, so the existing record's flag and count carry over.
     *
     * Both files go through [writeAtomically]. The keyboard can load `main.lua`
     * at any moment, and a write cut short must leave the previous script whole
     * rather than a truncated one that fails to parse.
     */
    @Synchronized
    fun adopt(
        manifest: PluginManifest,
        script: String,
        now: Long = System.currentTimeMillis(),
        keepState: Boolean = false,
    ): PluginAdoptResult {
        if (baseDir == null) return PluginAdoptResult.Failed
        val dir = dirFor(manifest.id) ?: return PluginAdoptResult.Failed
        val existing = pluginList.firstOrNull { it.id == manifest.id }
        if (existing == null && pluginList.size >= MAX_PLUGINS) return PluginAdoptResult.TooManyPlugins

        val written = writeAtomically(File(dir, MANIFEST_FILE), PluginManifestCodec.encode(manifest)) &&
            writeAtomically(File(dir, SCRIPT_FILE), script)
        if (!written) return PluginAdoptResult.Failed
        val carried = existing?.takeIf { keepState }

        val record = InstalledPlugin(
            id = manifest.id,
            name = manifest.name,
            version = manifest.pluginVersion,
            author = manifest.author,
            description = manifest.description,
            apiVersion = manifest.apiVersion,
            permissions = manifest.permissions,
            enabled = carried?.enabled ?: true,
            installedAt = existing?.installedAt ?: now,
            abandonedCount = carried?.abandonedCount ?: 0,
            language = manifest.language,
            panel = manifest.panel,
            actions = manifest.actions,
        )
        // A permission the new version dropped must stop applying, which happens
        // for free: the record is rebuilt from the new manifest rather than
        // merged into the old one.
        if (existing != null) pluginList.remove(existing)
        pluginList.add(record)
        save()
        return PluginAdoptResult.Adopted(record, replaced = existing != null)
    }

    /** Removes the plugin and everything it owns, including its storage and log. */
    @Synchronized
    fun delete(id: String) {
        val removed = pluginList.removeAll { it.id == id }
        dirFor(id)?.let { dir -> runCatching { dir.deleteRecursively() } }
        if (removed) save()
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        val index = pluginList.indexOfFirst { it.id == id }
        if (index < 0) return
        if (pluginList[index].enabled == enabled) return
        // Turning a plugin back on forgives its strikes, or a plugin disabled for
        // running away could never be given another chance.
        pluginList[index] = pluginList[index].copy(
            enabled = enabled,
            abandonedCount = if (enabled) 0 else pluginList[index].abandonedCount,
        )
        save()
    }

    /**
     * Records that this plugin's thread had to be abandoned, disabling it once
     * it reaches [MAX_ABANDONS]. Returns the plugin as it now stands, or null if
     * it is not installed.
     *
     * The strike is counted even with [autoDisableOnAbandon] off, so the count
     * on the plugin's own screen still says what has been happening.
     */
    @Synchronized
    fun recordAbandon(id: String): InstalledPlugin? {
        val index = pluginList.indexOfFirst { it.id == id }
        if (index < 0) return null
        val count = pluginList[index].abandonedCount + 1
        pluginList[index] = pluginList[index].copy(
            abandonedCount = count,
            enabled = pluginList[index].enabled &&
                (!autoDisable || count < MAX_ABANDONS),
        )
        save()
        return pluginList[index]
    }

    /**
     * Drops index entries whose files have gone — a directory deleted by hand, or
     * an install interrupted between writing the index and writing the script.
     * Returns true if anything changed.
     */
    @Synchronized
    fun reconcile(): Boolean {
        if (baseDir == null) return false
        val gone = pluginList.filter { record ->
            val file = scriptFile(record.id)
            file == null || !file.isFile
        }
        if (gone.isEmpty()) return false
        pluginList.removeAll(gone)
        save()
        return true
    }

    // ---- persistence ---------------------------------------------------

    @Synchronized
    fun save() {
        val dir = baseDir
        if (dir != null) {
            writeAtomically(
                File(dir, INDEX_FILE),
                json.encodeToString(
                    Snapshot(
                        plugins = pluginList.toList(),
                        subsystemEnabled = subsystem,
                        autoDisableOnAbandon = autoDisable,
                    ),
                ),
            )
        }
        _revision.value++
    }

    /** Writes through a `.part` file and a rename. False when the disk refused. */
    private fun writeAtomically(file: File, text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(text)
        part.renameTo(file) || run {
            file.delete()
            part.renameTo(file)
        }
    }.getOrDefault(false)

    /** Points the store at [dir] and re-reads it. The direct-boot unlock path. */
    @Synchronized
    fun attach(dir: File?) {
        if (dir?.absolutePath == baseDir?.absolutePath) return
        baseDir = dir
        reload()
    }

    @Synchronized
    fun reload() {
        pluginList.clear()
        subsystem = false
        autoDisable = true
        val dir = baseDir ?: run { _revision.value++; return }
        val index = File(dir, INDEX_FILE)
        if (index.exists()) {
            runCatching {
                val snapshot = json.decodeFromString<Snapshot>(index.readText())
                subsystem = snapshot.subsystemEnabled
                autoDisable = snapshot.autoDisableOnAbandon
                pluginList.addAll(
                    snapshot.plugins
                        .filter { SAFE_ID.matches(it.id) }
                        .distinctBy { it.id }
                        .take(MAX_PLUGINS),
                )
            }
        }
        _revision.value++
    }
}
