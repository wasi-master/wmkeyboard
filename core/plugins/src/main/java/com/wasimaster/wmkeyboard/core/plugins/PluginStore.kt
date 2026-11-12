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
    /**
     * [PluginManifestCodec.LANGUAGE_PYTHON] for every plugin the store keeps.
     * The default is Lua because an index written before this field existed
     * held only Lua plugins, and [PluginStore.reload] retires those.
     */
    val language: String = PluginManifestCodec.LANGUAGE_LUA,
    /** False for a plugin of actions alone, which the keyboard's plugin list leaves out. */
    val panel: Boolean = true,
    /**
     * The selection actions it declared, copied here so the keyboard can draw
     * their chips on every selection change without reading `plugin.json`.
     */
    val actions: List<PluginActionSpec> = emptyList(),
    /**
     * The [PluginPermission.Tier.RISKY] permissions the user turned on, a
     * subset of [permissions]. Empty on install: each one is granted on the
     * plugin's page, after its warning.
     */
    val granted: List<String> = emptyList(),
    /** The hosts the manifest listed, normalised. */
    val hosts: List<String> = emptyList(),
    val tool: PluginToolSpec = PluginToolSpec(),
    val settings: List<PluginSettingSpec> = emptyList(),
    /** What the user set on the plugin's settings page, by key, as text. */
    val settingValues: Map<String, String> = emptyMap(),
    /**
     * Risky permissions or hosts the last update added, which the update left
     * ungranted. The plugin's page says so until the user looks.
     */
    val newSinceUpdate: List<String> = emptyList(),
) {
    val isPython: Boolean
        get() = language == PluginManifestCodec.LANGUAGE_PYTHON

    /** Whether it runs against the `wm` package (API 5). */
    val isPackage: Boolean
        get() = apiVersion >= PluginManifestCodec.PACKAGE_API

    /** The declared permissions this build understands; unknown ones never survive install. */
    val declaredPermissions: List<PluginPermission>
        get() = permissions.mapNotNull { PluginPermission.parse(it) }

    /** The declared permissions in force: every listed one, and the risky ones the user granted. */
    val grantedPermissions: List<PluginPermission>
        get() = declaredPermissions.filter { grants(it) }

    fun declares(permission: PluginPermission): Boolean = permission.wire in permissions

    /**
     * Whether [permission] applies: declared, and granted when it is risky.
     * [PluginStore.allowRisky] is checked on top of this where calls are made.
     */
    fun grants(permission: PluginPermission): Boolean =
        declares(permission) && (!permission.isRisky || permission.wire in granted)

    /** The hosts `wm.http` may reach: the manifest's, and any the user typed into a host setting. */
    val allowedHosts: List<String>
        get() = (
            hosts + settings.filter { it.type == PluginSettingSpec.TYPE_HOST }
                .mapNotNull { setting -> settingValues[setting.key]?.let(PluginManifestCodec::normaliseHost) }
                .filter(PluginManifestCodec::isValidHost)
            ).distinct()

    /** The value of every setting, the user's or the default, typed as JSON for the session. */
    fun settingsJson(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        for (setting in settings) {
            val raw = settingValues[setting.key] ?: setting.default
            val value: kotlinx.serialization.json.JsonElement = when (setting.type) {
                PluginSettingSpec.TYPE_SWITCH -> kotlinx.serialization.json.JsonPrimitive(raw.trim() == "true")
                PluginSettingSpec.TYPE_NUMBER -> raw.trim().toDoubleOrNull()?.let { n ->
                    if (n == Math.floor(n) && kotlin.math.abs(n) < 1e15) kotlinx.serialization.json.JsonPrimitive(n.toLong()) else kotlinx.serialization.json.JsonPrimitive(n)
                } ?: kotlinx.serialization.json.JsonNull
                else -> kotlinx.serialization.json.JsonPrimitive(raw)
            }
            put(setting.key, value)
        }
    }
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

/**
 * The saved data of a plugin that was removed without the user asking: a Lua
 * plugin, retired when plugins became Python only. Installing a plugin with the
 * same id puts the data back, so the Python version of a demo finds the list
 * its Lua version kept.
 */
@Serializable
data class ParkedPlugin(
    val id: String = "",
    val name: String = "",
    val parkedAt: Long = 0L,
    /** Size of the parked `storage.json`, for the Plugins screen. */
    val bytes: Long = 0L,
)

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
 * ├── <pluginId>/
 * │   ├── plugin.json             the manifest as accepted
 * │   ├── main.py                 the script as imported
 * │   ├── modules.json            its other modules, API 5
 * │   ├── assets/                 its pictures and data, API 5
 * │   ├── storage.json            wm.storage, if the plugin declared it
 * │   ├── reminders.json          wm.reminders still to show, if it declared that
 * │   └── log.txt                 print() output and script errors
 * └── .parked/<pluginId>/
 *     └── storage.json            a retired plugin's data, kept for its reinstall
 * ```
 *
 * `.parked` can never be a plugin's directory: an id starts with a letter or
 * a digit.
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
class PluginStore(
    private var baseDir: File?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Serializable
    private data class Snapshot(
        val version: Int = FORMAT_VERSION,
        val plugins: List<InstalledPlugin> = emptyList(),
        val subsystemEnabled: Boolean = false,
        // Defaults true so an index written before this existed keeps the
        // behaviour it was written under.
        val autoDisableOnAbandon: Boolean = true,
        val parked: List<ParkedPlugin> = emptyList(),
        /** Names of plugins retired since the user last saw the notice. */
        val retired: List<String> = emptyList(),
        /** The global switch every risky grant also needs. Off until the user turns it on. */
        val allowRisky: Boolean = false,
    )

    private val pluginList = ArrayList<InstalledPlugin>()
    private val parkedList = ArrayList<ParkedPlugin>()
    private val retiredNames = ArrayList<String>()
    private var subsystem = false
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _revision = MutableStateFlow(0)

    /** Bumped on every change, so Compose and the IME re-read the store. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private var autoDisable = true
    private var risky = false

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

        /**
         * 2: plugins are Python only; Lua ones are retired on read.
         * 3: API 5 fields (grants, hosts, tool, settings) and [allowRisky];
         * every field defaults, so a version 2 index reads as is.
         */
        private const val FORMAT_VERSION = 3

        const val DIR_NAME = "plugins"

        /** Well past any real collection; a list this long is a mistake, not a use case. */
        const val MAX_PLUGINS = 50

        private const val MAX_SETTING_VALUE = 1024

        /** Runaway abandonments before a plugin is disabled for the user. */
        const val MAX_ABANDONS = 2

        const val MANIFEST_FILE = "plugin.json"
        const val SCRIPT_FILE = "main.py"
        const val STORAGE_FILE = "storage.json"

        /** The plugin's other modules, `{"helpers.py": source}`: one file, so no entry name becomes a path. */
        const val MODULES_FILE = "modules.json"

        /** Bundled pictures and data, under names [PluginFile] already checked. */
        const val ASSETS_DIR = "assets"

        /** What a plugin's script was called while plugins were Lua. */
        private const val LUA_SCRIPT_FILE = "main.lua"
        private const val PARKED_DIR = ".parked"

        /** How long a retired plugin's data waits for its reinstall. */
        const val PARKED_DAYS = 90
        private const val PARKED_MILLIS = PARKED_DAYS * 24L * 60 * 60 * 1000
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

    /**
     * Whether any plugin may use a [PluginPermission.Tier.RISKY] permission at
     * all. **Off until the user turns it on**: a grant on one plugin's page
     * does nothing while this is off, and turning it off revokes nothing but
     * stops every risky call at once.
     */
    @Synchronized
    fun allowRisky(): Boolean = risky

    @Synchronized
    fun setAllowRisky(enabled: Boolean) {
        if (risky == enabled) return
        risky = enabled
        save()
    }

    /** Turns [permission] on or off for [id]. Only a declared, risky permission can be granted. */
    @Synchronized
    fun setGranted(id: String, permission: PluginPermission, granted: Boolean) {
        val index = pluginList.indexOfFirst { it.id == id }
        if (index < 0) return
        val plugin = pluginList[index]
        if (!permission.isRisky || !plugin.declares(permission)) return
        val now = if (granted) (plugin.granted + permission.wire).distinct() else plugin.granted - permission.wire
        if (now == plugin.granted) return
        pluginList[index] = plugin.copy(granted = now, newSinceUpdate = plugin.newSinceUpdate - permission.wire)
        save()
    }

    /** Stores the user's value for setting [key] of [id]; null puts the default back. */
    @Synchronized
    fun setSettingValue(id: String, key: String, value: String?) {
        val index = pluginList.indexOfFirst { it.id == id }
        if (index < 0) return
        val plugin = pluginList[index]
        if (plugin.settings.none { it.key == key }) return
        val values = if (value == null) plugin.settingValues - key else plugin.settingValues + (key to value.take(MAX_SETTING_VALUE))
        if (values == plugin.settingValues) return
        pluginList[index] = plugin.copy(settingValues = values)
        save()
    }

    /** The plugin's page has shown what the last update added. */
    @Synchronized
    fun dismissNewSinceUpdate(id: String) {
        val index = pluginList.indexOfFirst { it.id == id }
        if (index < 0 || pluginList[index].newSinceUpdate.isEmpty()) return
        pluginList[index] = pluginList[index].copy(newSinceUpdate = emptyList())
        save()
    }

    /** Runnable plugins that offer a tile for the toolbar, toolbox and floating button. */
    @Synchronized
    fun toolPlugins(): List<InstalledPlugin> =
        if (!subsystem) emptyList() else pluginList.filter { it.enabled && it.panel && it.tool.tile }

    /** Runnable plugins with a keyword, by keyword. The first install wins a clash. */
    @Synchronized
    fun keywordPlugins(): Map<String, InstalledPlugin> {
        if (!subsystem) return emptyMap()
        val out = LinkedHashMap<String, InstalledPlugin>()
        for (plugin in pluginList) {
            val word = plugin.tool.keyword
            if (plugin.enabled && word.isNotEmpty() && word !in out) out[word] = plugin
        }
        return out
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

    /** The script, or null if it has gone missing — which the reconciler treats as uninstalled. */
    fun script(id: String): String? {
        val file = scriptFile(id) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /** The plugin's other modules, `{"helpers.py": source}`; empty for a one-file plugin. */
    fun modules(id: String): Map<String, String> {
        val file = dirFor(id)?.let { File(it, MODULES_FILE) } ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(file.readText()) }.getOrDefault(emptyMap())
    }

    /** One bundled asset's bytes, or null. [name] is the part after `assets/`. */
    fun asset(id: String, name: String): ByteArray? {
        if (!PluginFile.isAssetName(name)) return null
        val file = dirFor(id)?.let { File(File(it, ASSETS_DIR), name) } ?: return null
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    // ---- retired Lua plugins -------------------------------------------

    /** Data kept for retired plugins, oldest first. */
    @Synchronized
    fun parked(): List<ParkedPlugin> = parkedList.toList()

    /**
     * The parked `storage.json` of a retired plugin, or null when none is kept.
     * The Todo tool's import reads it, so a list survives the Todo plugin being
     * retired even if its Python version is never installed.
     */
    fun parkedStorageFile(id: String): File? {
        val dir = baseDir ?: return null
        if (!SAFE_ID.matches(id)) return null
        return File(File(File(dir, PARKED_DIR), id), STORAGE_FILE).takeIf { it.isFile }
    }

    /** Throws a retired plugin's data away. */
    @Synchronized
    fun deleteParked(id: String) {
        if (!parkedList.removeAll { it.id == id }) return
        parkedDir(id)?.let { runCatching { it.deleteRecursively() } }
        save()
    }

    /**
     * Names of plugins retired since [dismissRetired]: shown once, on the
     * Plugins screen and in the keyboard's plugin list.
     */
    @Synchronized
    fun retiredNames(): List<String> = retiredNames.toList()

    @Synchronized
    fun dismissRetired() {
        if (retiredNames.isEmpty()) return
        retiredNames.clear()
        save()
    }

    private fun parkedDir(id: String): File? {
        val dir = baseDir ?: return null
        if (!SAFE_ID.matches(id)) return null
        return File(File(dir, PARKED_DIR), id)
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
     * Both files go through [writeAtomically]. The keyboard can load `main.py`
     * at any moment, and a write cut short must leave the previous script whole
     * rather than a truncated one that fails to parse.
     *
     * A plugin with the id of a retired one gets the retired one's parked data
     * back, unless it already has data of its own.
     */
    @Synchronized
    fun adopt(
        manifest: PluginManifest,
        script: String,
        now: Long = System.currentTimeMillis(),
        keepState: Boolean = false,
        modules: Map<String, String> = emptyMap(),
        assets: Map<String, ByteArray> = emptyMap(),
    ): PluginAdoptResult {
        if (baseDir == null) return PluginAdoptResult.Failed
        val dir = dirFor(manifest.id) ?: return PluginAdoptResult.Failed
        val existing = pluginList.firstOrNull { it.id == manifest.id }
        if (existing == null && pluginList.size >= MAX_PLUGINS) return PluginAdoptResult.TooManyPlugins

        val written = writeAtomically(File(dir, MANIFEST_FILE), PluginManifestCodec.encode(manifest)) &&
            writeAtomically(File(dir, SCRIPT_FILE), script) &&
            writeModules(dir, modules) &&
            writeAssets(dir, assets)
        if (!written) return PluginAdoptResult.Failed
        val carried = existing?.takeIf { keepState }
        unpark(manifest.id, dir)
        val (granted, added) = carryGrants(existing, manifest)

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
            granted = granted,
            hosts = manifest.hosts,
            tool = manifest.tool,
            settings = manifest.settings,
            settingValues = existing?.settingValues.orEmpty().filterKeys { key -> manifest.settings.any { it.key == key } },
            newSinceUpdate = added,
        )
        // A permission the new version dropped must stop applying, which happens
        // for free: the record is rebuilt from the new manifest rather than
        // merged into the old one.
        if (existing != null) pluginList.remove(existing)
        pluginList.add(record)
        save()
        return PluginAdoptResult.Adopted(record, replaced = existing != null)
    }

    /**
     * Which risky grants an update keeps, and what it newly asks for. A grant
     * survives only for a permission the new version still declares, and the
     * network grant only when the new version lists no host the old one did
     * not: a grant was given for those websites, not for whatever the update
     * names next.
     */
    private fun carryGrants(existing: InstalledPlugin?, manifest: PluginManifest): Pair<List<String>, List<String>> {
        val risky = manifest.permissions.mapNotNull { PluginPermission.parse(it) }.filter { it.isRisky }.map { it.wire }
        if (existing == null) return emptyList<String>() to emptyList()
        val newHosts = manifest.hosts.filter { it !in existing.hosts }
        val kept = existing.granted.filter { wire ->
            wire in risky && !(wire == PluginPermission.Network.wire && newHosts.isNotEmpty())
        }
        val added = risky.filter { it !in existing.permissions || (it == PluginPermission.Network.wire && newHosts.isNotEmpty()) } +
            newHosts
        return kept to added.distinct()
    }

    /** `modules.json`, or none for a one-file plugin. */
    private fun writeModules(dir: File, modules: Map<String, String>): Boolean {
        val file = File(dir, MODULES_FILE)
        if (modules.isEmpty()) {
            runCatching { file.delete() }
            return true
        }
        return writeAtomically(file, json.encodeToString(modules))
    }

    /** Replaces the assets folder with [assets], whose names [PluginFile] checked. */
    private fun writeAssets(dir: File, assets: Map<String, ByteArray>): Boolean = runCatching {
        val folder = File(dir, ASSETS_DIR)
        folder.deleteRecursively()
        if (assets.isEmpty()) return@runCatching true
        folder.mkdirs()
        for ((name, bytes) in assets) {
            if (!PluginFile.isAssetName(name)) continue
            File(folder, name).writeBytes(bytes)
        }
        true
    }.getOrDefault(false)

    /**
     * Moves parked data for [id] into [dir]. A plugin that already keeps data
     * of its own leaves the parked copy where it is, listed and deletable, so
     * neither is thrown away without the user seeing it.
     */
    private fun unpark(id: String, dir: File) {
        if (parkedList.none { it.id == id }) return
        val from = parkedDir(id) ?: return
        val parkedFile = File(from, STORAGE_FILE)
        val target = File(dir, STORAGE_FILE)
        if (target.exists()) return
        if (parkedFile.isFile) {
            val moved = runCatching { parkedFile.renameTo(target) || parkedFile.copyTo(target).exists() }
                .getOrDefault(false)
            if (!moved) return
        }
        runCatching { from.deleteRecursively() }
        parkedList.removeAll { it.id == id }
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
                        parked = parkedList.toList(),
                        retired = retiredNames.toList(),
                        allowRisky = risky,
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
        parkedList.clear()
        retiredNames.clear()
        subsystem = false
        autoDisable = true
        risky = false
        val dir = baseDir ?: run { _revision.value++; return }
        val index = File(dir, INDEX_FILE)
        var version = FORMAT_VERSION
        if (index.exists()) {
            runCatching {
                val snapshot = json.decodeFromString<Snapshot>(index.readText())
                version = snapshot.version
                subsystem = snapshot.subsystemEnabled
                autoDisable = snapshot.autoDisableOnAbandon
                risky = snapshot.allowRisky
                pluginList.addAll(
                    snapshot.plugins
                        .filter { SAFE_ID.matches(it.id) }
                        .distinctBy { it.id }
                        .take(MAX_PLUGINS),
                )
                parkedList.addAll(snapshot.parked.filter { SAFE_ID.matches(it.id) }.distinctBy { it.id })
                retiredNames.addAll(snapshot.retired)
            }
        }
        val retiredAny = retireLua()
        val purged = purgeParked()
        if (retiredAny || purged || (index.exists() && version < FORMAT_VERSION)) save() else _revision.value++
    }

    /**
     * Retires every Lua plugin: its directory goes, its `storage.json` is parked
     * and its name is queued for the notice. A Python plugin installed while the
     * script was still called `main.lua` gets it renamed. True if anything changed.
     */
    private fun retireLua(): Boolean {
        var changed = false
        val lua = pluginList.filter { it.language != PluginManifestCodec.LANGUAGE_PYTHON }
        for (record in lua) {
            park(record)
            dirFor(record.id)?.let { runCatching { it.deleteRecursively() } }
            pluginList.remove(record)
            retiredNames += record.name.ifBlank { record.id }
            changed = true
        }
        for (record in pluginList) {
            val dir = dirFor(record.id) ?: continue
            val old = File(dir, LUA_SCRIPT_FILE)
            val new = File(dir, SCRIPT_FILE)
            if (old.isFile && !new.exists()) {
                runCatching { old.renameTo(new) }
                changed = true
            }
        }
        return changed
    }

    private fun park(record: InstalledPlugin) {
        val source = storageFile(record.id)?.takeIf { it.isFile && it.length() > 0 } ?: return
        val to = parkedDir(record.id) ?: return
        val moved = runCatching {
            to.deleteRecursively()
            to.mkdirs()
            val target = File(to, STORAGE_FILE)
            source.renameTo(target) || source.copyTo(target, overwrite = true).exists()
        }.getOrDefault(false)
        if (!moved) return
        parkedList.removeAll { it.id == record.id }
        parkedList += ParkedPlugin(
            id = record.id,
            name = record.name.ifBlank { record.id },
            parkedAt = clock(),
            bytes = File(to, STORAGE_FILE).length(),
        )
    }

    /** Drops parked data older than [PARKED_DAYS], and entries whose files have gone. */
    private fun purgeParked(): Boolean {
        val now = clock()
        val gone = parkedList.filter { now - it.parkedAt > PARKED_MILLIS || parkedStorageFile(it.id) == null }
        if (gone.isEmpty()) return false
        for (entry in gone) parkedDir(entry.id)?.let { runCatching { it.deleteRecursively() } }
        parkedList.removeAll(gone.toSet())
        return true
    }
}
