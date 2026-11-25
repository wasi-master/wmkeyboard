package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wasimaster.wmkeyboard.core.plugins.PluginAdoptResult
import com.wasimaster.wmkeyboard.core.plugins.PluginDraft
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.plugins.PluginLocales
import com.wasimaster.wmkeyboard.core.plugins.PluginManifest
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestCodec
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestResult
import com.wasimaster.wmkeyboard.core.plugins.PluginSnapshot
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.PluginText
import com.wasimaster.wmkeyboard.core.plugins.PluginWorkspace
import com.wasimaster.wmkeyboard.core.plugins.SnapshotReason
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.plugins.R as PluginsR
import kotlinx.serialization.json.JsonObject

/** What pressing Install came to. */
internal sealed interface PublishOutcome {
    data class Published(val version: String, val replaced: Boolean) : PublishOutcome

    /** The manifest or the script is one the importer would refuse; [reason] says why, in its words. */
    data class Invalid(val reason: PluginText) : PublishOutcome

    data object TooManyPlugins : PublishOutcome

    data object Failed : PublishOutcome
}

/** What the plugin editor needs from the world, so its state is testable without it. */
internal interface PluginIdePorts {
    fun script(): String?

    fun manifest(): PluginManifest?

    fun writeScript(text: String): Boolean

    fun writeManifest(manifest: PluginManifest): Boolean

    fun snapshot(reason: SnapshotReason): PluginSnapshot?

    fun publish(manifest: PluginManifest, script: String): PublishOutcome

    /** The draft's kept versions, newest first. */
    fun snapshots(): List<PluginSnapshot> = emptyList()

    fun snapshotBody(snapshotId: String): String? = null

    /** Puts a kept version back as the draft's script, keeping the script it replaces first. */
    fun restore(snapshotId: String): Boolean = false

    /** The draft's files besides plugin.json and the entry script, by their names in a `.wmplugin`. */
    fun files(): List<String> = emptyList()

    /** A file of [files] as text; null for a picture or a file that has gone. */
    fun fileText(path: String): String? = null

    fun writeFile(path: String, text: String): Boolean = false

    fun deleteFile(path: String): Boolean = false

    fun renameFile(from: String, to: String): Boolean = false

    fun modules(): Map<String, String> = emptyMap()

    fun assets(): Map<String, ByteArray> = emptyMap()

    fun locales(): Map<String, String> = emptyMap()
}

/** What adding, renaming or deleting one of the draft's files came to. */
internal sealed interface FileChange {
    data object Done : FileChange

    /** Not a name a plugin file can have. */
    data object BadName : FileChange

    data class Taken(val path: String) : FileChange

    /** The draft already holds [PluginWorkspace.MAX_FILES]. */
    data object Full : FileChange

    data object Failed : FileChange
}

/** Everything a run of the draft needs: the entry script, the other modules and the translation it reads. */
internal data class DraftRunInputs(val script: String, val modules: Map<String, String>, val strings: JsonObject)

/** Everything Save as a file writes. */
internal data class DraftArchive(
    val script: String,
    val modules: Map<String, String>,
    val assets: Map<String, ByteArray>,
    val locales: Map<String, String>,
)

/**
 * Everything the plugin editor screen decides, with no Compose UI and no Android
 * in it, so a plain JVM test drives it.
 *
 * The draft on disk is the save point. Leaving the screen never loses work, and
 * [publish] is the only way anything reaches the keyboard.
 */
@Stable
internal class PluginIdeState(val draftId: String, private val ports: PluginIdePorts) {

    var manifest: PluginManifest by mutableStateOf(ports.manifest() ?: PluginManifest(format = PluginManifestCodec.FORMAT))
        private set

    /** The file open in the editor: [ENTRY] for the entry script, or a path from [files]. */
    var file: String by mutableStateOf(ENTRY)
        private set

    /** The draft's other files: modules, assets and translations, by their names in a `.wmplugin`. */
    var files: List<String> by mutableStateOf(ports.files())
        private set

    val onEntry: Boolean
        get() = file == ENTRY

    /** Whether the open file is Python, so the Python checks, suggestions and tools apply to it. */
    val onPython: Boolean
        get() = onEntry || file.endsWith(".py")

    /** The open file as it was last written to the draft. */
    var savedText: String by mutableStateOf(ports.script().orEmpty())
        private set

    /** The other modules by dotted name, `pkg/util.py` as `pkg.util`, for the checks to know `import util` is the plugin's own. */
    val moduleNames: List<String>
        get() = files.filter { it.endsWith(".py") }.map { it.removeSuffix(".py").replace('/', '.') }

    /** What the importer would refuse the manifest for, in field order. Empty when Install can go ahead. */
    val problems: List<PluginManifestCodec.Problem>
        get() = PluginManifestCodec.problems(manifest)

    fun isDirty(text: String): Boolean = text != savedText

    /**
     * Writes [text] as the open file when it differs from what is there. False
     * only when the write failed. [path] is the file [text] was typed in, read
     * with it on the main thread: a save that reaches here after the editor
     * moved to another file writes nothing, since [open] saved that file as it
     * left it.
     */
    @Synchronized
    fun save(text: String, path: String = file): Boolean {
        if (path != file) return true
        if (text == savedText) return true
        val written = if (onEntry) ports.writeScript(text) else ports.writeFile(file, text)
        if (!written) return false
        savedText = text
        return true
    }

    /**
     * Saves [current] as the open file, then opens [path]. False when [path] is
     * a picture, has gone, or [current] could not be saved; then nothing moved.
     */
    @Synchronized
    fun open(path: String, current: String, from: String = file): Boolean {
        if (path == file) return true
        if (!save(current, from)) return false
        val body = (if (path == ENTRY) ports.script() else ports.fileText(path)) ?: return false
        file = path
        savedText = body
        return true
    }

    /**
     * A new file named [name], opened. A bare name gets `.py`, so `helpers`
     * makes the module `import helpers` reaches. A translation starts as an
     * empty object, which is a valid one.
     */
    @Synchronized
    fun addFile(name: String, current: String, from: String = file): FileChange {
        val path = fileName(name)
        if (path == entryName || path in files) return FileChange.Taken(path)
        if (!canName(path)) return FileChange.BadName
        if (files.size >= PluginWorkspace.MAX_FILES) return FileChange.Full
        if (!save(current, from)) return FileChange.Failed
        val body = if (PluginLocales.tagOf(path) != null) "{\n}\n" else ""
        if (!ports.writeFile(path, body)) return FileChange.Failed
        files = ports.files()
        file = path
        savedText = body
        return FileChange.Done
    }

    /** Renames the open file, which is never the entry: that one is named in Details. */
    @Synchronized
    fun renameFile(name: String, current: String): FileChange {
        if (onEntry) return FileChange.Failed
        val path = fileName(name)
        if (path == file) return FileChange.Done
        if (path == entryName || path in files) return FileChange.Taken(path)
        if (!canName(path)) return FileChange.BadName
        if (!save(current)) return FileChange.Failed
        if (!ports.renameFile(file, path)) return FileChange.Failed
        files = ports.files()
        file = path
        return FileChange.Done
    }

    /** Deletes the open file, which is never the entry, and opens the entry. */
    @Synchronized
    fun deleteFile(): FileChange {
        if (onEntry) return FileChange.Failed
        if (!ports.deleteFile(file)) return FileChange.Failed
        files = ports.files()
        file = ENTRY
        savedText = ports.script().orEmpty()
        return FileChange.Done
    }

    /** What a run uses, with [current] standing in for the open file as typed, saved or not. */
    fun runInputs(current: String, path: String = file): DraftRunInputs {
        val script = if (path == ENTRY) current else ports.script().orEmpty()
        val modules = ports.modules().let { if (path != ENTRY && path.endsWith(".py")) it + (path to current) else it }
        val tag = PluginLocales.tagOf(path)
        val locales = ports.locales().let { if (tag != null) it + (tag to current) else it }
        return DraftRunInputs(script, modules, PluginLocales.strings(locales))
    }

    /** Saves [current], then reads everything Save as a file writes. */
    fun archive(current: String, path: String = file): DraftArchive {
        save(current, path)
        return DraftArchive(ports.script().orEmpty(), ports.modules(), ports.assets(), ports.locales())
    }

    private val entryName: String
        get() = manifest.entry.ifBlank { PluginManifestCodec.DEFAULT_ENTRY }

    /** [name] as a plugin file path: trimmed, with `.py` added to a bare name. */
    private fun fileName(name: String): String {
        val trimmed = name.trim().trim('/')
        return if ('.' in trimmed.substringAfterLast('/')) trimmed else "$trimmed.py"
    }

    /** Whether a text file the editor makes can be called [path]. Pictures come with a plugin; they are not typed. */
    private fun canName(path: String): Boolean = PluginWorkspace.isFilePath(path) && PluginWorkspace.isTextFile(path)

    fun updateManifest(updated: PluginManifest): Boolean {
        val withFormat = updated.copy(format = PluginManifestCodec.FORMAT)
        if (!ports.writeManifest(withFormat)) return false
        manifest = withFormat
        return true
    }

    /** Saves [text] and keeps it as a version. Null when it is the same as the newest version. */
    fun saveVersion(text: String, path: String = file): PluginSnapshot? {
        save(text, path)
        return ports.snapshot(SnapshotReason.MANUAL)
    }

    /** Saves [text] and keeps it as a version taken while working. Null when nothing changed since the newest. */
    fun keepWhileWorking(text: String, path: String = file): PluginSnapshot? {
        if (!save(text, path)) return null
        return ports.snapshot(SnapshotReason.PERIODIC)
    }

    fun versions(): List<PluginSnapshot> = ports.snapshots()

    fun versionText(snapshotId: String): String? = ports.snapshotBody(snapshotId)

    /**
     * Saves [current], then puts version [snapshotId] back and returns its text.
     * Null when the version is gone or cannot be written, and then the draft is
     * [current], saved.
     */
    fun restore(snapshotId: String, current: String): String? {
        // Versions are of the entry script only, and the editor offers them only there.
        if (!onEntry) return null
        if (!save(current)) return null
        if (!ports.restore(snapshotId)) return null
        val body = ports.script() ?: return null
        savedText = body
        return body
    }

    /**
     * Saves [text] as the open file, then installs the draft with all its
     * files, refusing first on anything the importer would refuse.
     */
    fun publish(text: String, path: String = file): PublishOutcome {
        if (!save(text, path)) return PublishOutcome.Failed
        problems.firstOrNull()?.let { return PublishOutcome.Invalid(it.text) }
        val script = if (path == ENTRY) text else ports.script() ?: return PublishOutcome.Failed
        val modules = ports.modules()
        if ((listOf(script) + modules.values).any { it.toByteArray(Charsets.UTF_8).size > PluginFile.MAX_SCRIPT_BYTES }) {
            return PublishOutcome.Invalid(
                PluginText.of(PluginsR.string.core_plugins_reject_script_too_large, manifest.name, PluginFile.MAX_SCRIPT_BYTES / 1024),
            )
        }
        // A .wmplugin below API 5 is read as one file, so its other files would not survive a share.
        if (files.isNotEmpty() && manifest.apiVersion < PluginManifestCodec.PACKAGE_API) {
            return PublishOutcome.Invalid(PluginText.of(R.string.plugin_ide_files_needs_api, entryName, PluginManifestCodec.PACKAGE_API))
        }
        for ((tag, body) in ports.locales()) {
            if (PluginLocales.parse(body) == null) {
                val path = PluginLocales.PREFIX + tag + PluginLocales.SUFFIX
                return PublishOutcome.Invalid(PluginText.of(PluginsR.string.core_plugins_reject_bad_locale, manifest.name, path))
            }
        }
        return ports.publish(manifest, script)
    }

    companion object {
        /** [file] when the entry script is open. Not a path a plugin file can have. */
        const val ENTRY = ""
    }
}

/** The editor's ports over the real workspace and store. */
internal class WorkspaceIdePorts(
    private val workspace: PluginWorkspace,
    private val store: PluginStore,
    private val draftId: String,
) : PluginIdePorts {
    override fun script(): String? = workspace.script(draftId)

    override fun manifest(): PluginManifest? = workspace.manifest(draftId)

    override fun writeScript(text: String): Boolean = workspace.writeScript(draftId, text)

    override fun writeManifest(manifest: PluginManifest): Boolean = workspace.writeManifest(draftId, manifest)

    override fun snapshot(reason: SnapshotReason): PluginSnapshot? = workspace.snapshot(draftId, reason)

    override fun snapshots(): List<PluginSnapshot> = workspace.snapshots(draftId)

    override fun snapshotBody(snapshotId: String): String? = workspace.snapshotBody(draftId, snapshotId)

    override fun restore(snapshotId: String): Boolean = workspace.restore(draftId, snapshotId)

    override fun files(): List<String> = workspace.files(draftId)

    override fun fileText(path: String): String? = workspace.fileText(draftId, path)

    override fun writeFile(path: String, text: String): Boolean = workspace.writeFile(draftId, path, text)

    override fun deleteFile(path: String): Boolean = workspace.deleteFile(draftId, path)

    override fun renameFile(from: String, to: String): Boolean = workspace.renameFile(draftId, from, to)

    override fun modules(): Map<String, String> = workspace.modules(draftId)

    override fun assets(): Map<String, ByteArray> = workspace.assets(draftId)

    override fun locales(): Map<String, String> = workspace.locales(draftId)

    /**
     * Runs the manifest through the importer's own reader, so what is installed is
     * exactly what a file with this manifest would install, then adopts it keeping
     * the installed plugin's switch and strikes as they were.
     */
    override fun publish(manifest: PluginManifest, script: String): PublishOutcome {
        val accepted = when (val read = PluginManifestCodec.read(PluginManifestCodec.encode(manifest))) {
            is PluginManifestResult.Ok -> read.manifest
            is PluginManifestResult.Rejected -> return PublishOutcome.Invalid(read.reasonText)
            PluginManifestResult.NotAPlugin -> return PublishOutcome.Failed
        }
        workspace.snapshot(draftId, SnapshotReason.PUBLISH)
        // Every file goes, so an install from here holds what the draft holds:
        // adopt replaces the modules, assets and translations it is given.
        val adopted = store.adopt(
            accepted,
            script,
            keepState = true,
            modules = workspace.modules(draftId),
            assets = workspace.assets(draftId),
            locales = workspace.locales(draftId),
        )
        return when (adopted) {
            is PluginAdoptResult.Adopted -> {
                workspace.markPublished(draftId, accepted.pluginVersion)
                PublishOutcome.Published(accepted.pluginVersion, adopted.replaced)
            }
            PluginAdoptResult.TooManyPlugins -> PublishOutcome.TooManyPlugins
            PluginAdoptResult.Failed -> PublishOutcome.Failed
        }
    }
}

/**
 * The draft for an installed plugin: the one already open for it if there is one,
 * or a new one made from what is installed. Null when the plugin is not installed.
 */
internal fun draftForInstalled(workspace: PluginWorkspace, store: PluginStore, pluginId: String): String? {
    workspace.draftFor(pluginId)?.let { draft ->
        adoptInstalledFiles(workspace, store, draft.draftId)
        return draft.draftId
    }
    val script = store.script(pluginId) ?: return null
    val manifestText = store.manifestFile(pluginId)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        ?: return null
    val manifest = (PluginManifestCodec.read(manifestText) as? PluginManifestResult.Ok)?.manifest ?: return null
    return workspace.create(manifest, script, "installed:$pluginId", files = store.files(pluginId))?.draftId
}

/**
 * A draft of an installed plugin made while drafts held only the entry script
 * gets the plugin's other files, once. Without them its Install replaced the
 * plugin with a copy missing every module, asset and translation.
 */
internal fun adoptInstalledFiles(workspace: PluginWorkspace, store: PluginStore, draftId: String) {
    val draft = workspace.draft(draftId) ?: return
    if (draft.layout >= PluginWorkspace.FILES_LAYOUT) return
    val pluginId = draft.origin.removePrefix("installed:").takeIf { draft.origin.startsWith("installed:") }
    val files = pluginId?.takeIf { id -> workspace.manifest(draftId)?.id == id }?.let(store::files).orEmpty()
    workspace.adoptFiles(draftId, files)
}

/** A new draft named [name], with an identifier no installed plugin or other draft has, holding [BLANK_PLUGIN_SCRIPT]. */
internal fun newBlankDraft(workspace: PluginWorkspace, store: PluginStore, name: String): PluginDraft? {
    val taken = store.plugins().map { it.id }.toSet() +
        workspace.drafts().mapNotNull { workspace.manifest(it.draftId)?.id }
    val manifest = PluginManifest(
        format = PluginManifestCodec.FORMAT,
        id = uniquePluginId(name, taken),
        name = PluginManifestCodec.sanitise(name, PluginManifestCodec.Field.NAME),
        pluginVersion = "0.1.0",
        // The blank script imports what it uses from the wm package.
        apiVersion = PluginManifestCodec.PACKAGE_API,
    )
    return workspace.create(manifest, BLANK_PLUGIN_SCRIPT, "blank")
}

/**
 * `my.` and the name in small letters, digits and dashes, numbered until nothing
 * in [taken] has it. A name with no Latin letters in it becomes `my.plugin`.
 */
internal fun uniquePluginId(name: String, taken: Set<String>): String {
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifBlank { "plugin" }
    val base = "my.$slug"
    if (base !in taken) return base
    var number = 2
    while ("$base-$number" in taken) number++
    return "$base-$number"
}

/** The plugin a new draft starts as: small, working, and in the house style. */
internal const val BLANK_PLUGIN_SCRIPT = """# A plugin draws a panel in the keyboard. render() says what the panel shows,
# and a button names the function that runs when it is pressed.

from wm import plugin, ui

state = plugin.state(count=0)


def add():
    state.count += 1


def render():
    with ui.column():
        ui.label(f"Pressed {state.count} times", style="title")
        ui.button("Press me", on_click=add, primary=True, id="add")
"""
