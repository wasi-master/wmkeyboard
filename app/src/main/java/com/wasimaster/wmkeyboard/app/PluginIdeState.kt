package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wasimaster.wmkeyboard.core.plugins.PluginAdoptResult
import com.wasimaster.wmkeyboard.core.plugins.PluginDraft
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.plugins.PluginManifest
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestCodec
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestResult
import com.wasimaster.wmkeyboard.core.plugins.PluginSnapshot
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.PluginText
import com.wasimaster.wmkeyboard.core.plugins.PluginWorkspace
import com.wasimaster.wmkeyboard.core.plugins.SnapshotReason
import com.wasimaster.wmkeyboard.plugins.R as PluginsR

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
}

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

    /** The script as it was last written to the draft. */
    var savedText: String by mutableStateOf(ports.script().orEmpty())
        private set

    /** What the importer would refuse the manifest for, in field order. Empty when Install can go ahead. */
    val problems: List<PluginManifestCodec.Problem>
        get() = PluginManifestCodec.problems(manifest)

    fun isDirty(text: String): Boolean = text != savedText

    /** Writes [text] to the draft when it differs from what is there. False only when the write failed. */
    fun save(text: String): Boolean {
        if (text == savedText) return true
        if (!ports.writeScript(text)) return false
        savedText = text
        return true
    }

    fun updateManifest(updated: PluginManifest): Boolean {
        val withFormat = updated.copy(format = PluginManifestCodec.FORMAT)
        if (!ports.writeManifest(withFormat)) return false
        manifest = withFormat
        return true
    }

    /** Saves [text] and keeps it as a version. Null when it is the same as the newest version. */
    fun saveVersion(text: String): PluginSnapshot? {
        save(text)
        return ports.snapshot(SnapshotReason.MANUAL)
    }

    /** Saves [text] and keeps it as a version taken while working. Null when nothing changed since the newest. */
    fun keepWhileWorking(text: String): PluginSnapshot? {
        if (!save(text)) return null
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
        if (!save(current)) return null
        if (!ports.restore(snapshotId)) return null
        val body = ports.script() ?: return null
        savedText = body
        return body
    }

    /** Saves [text], then installs it, refusing first on anything the importer would refuse. */
    fun publish(text: String): PublishOutcome {
        if (!save(text)) return PublishOutcome.Failed
        problems.firstOrNull()?.let { return PublishOutcome.Invalid(it.text) }
        if (text.toByteArray(Charsets.UTF_8).size > PluginFile.MAX_SCRIPT_BYTES) {
            return PublishOutcome.Invalid(
                PluginText.of(
                    PluginsR.string.core_plugins_reject_script_too_large,
                    manifest.name,
                    PluginFile.MAX_SCRIPT_BYTES / 1024,
                ),
            )
        }
        return ports.publish(manifest, text)
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
        return when (val adopted = store.adopt(accepted, script, keepState = true)) {
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
    workspace.draftFor(pluginId)?.let { return it.draftId }
    val script = store.script(pluginId) ?: return null
    val manifestText = store.manifestFile(pluginId)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        ?: return null
    val manifest = (PluginManifestCodec.read(manifestText) as? PluginManifestResult.Ok)?.manifest ?: return null
    return workspace.create(manifest, script, "installed:$pluginId")?.draftId
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
# and on_event(e) hears what the user pressed.

count = 0


def on_event(e):
    global count
    if e.type == "click" and e.id == "add":
        count += 1


def render():
    return ui.column(
        ui.label("Pressed " + str(count) + " times", style="title"),
        ui.button("add", "Press me", style="primary"),
    )
"""
