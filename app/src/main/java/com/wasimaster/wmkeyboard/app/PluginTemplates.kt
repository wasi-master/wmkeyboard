package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.plugins.PluginActionSpec
import com.wasimaster.wmkeyboard.core.plugins.PluginDraft
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.plugins.PluginManifest
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestCodec
import com.wasimaster.wmkeyboard.core.plugins.PluginPermission
import com.wasimaster.wmkeyboard.core.plugins.PluginReadResult
import com.wasimaster.wmkeyboard.core.plugins.PluginSettingSpec
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.PluginText
import com.wasimaster.wmkeyboard.core.plugins.PluginWorkspace
import com.wasimaster.wmkeyboard.core.plugins.SnapshotReason
import java.io.InputStream

/**
 * A plugin to start from. Apart from the blank one, each is a demo plugin the
 * documentation teaches from, copied into assets from
 * app/src/test/resources/plugins, where DemoPluginsTest runs them in the real
 * sandbox. PluginTemplatesTest fails when a copy drifts from its demo.
 */
internal enum class PluginTemplate(
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    /** The script's file under assets/plugin-templates, or null for the blank plugin. */
    val asset: String?,
    /** What the script uses beyond its panel, so the new manifest declares it. */
    val permissions: List<PluginPermission>,
    /** The selection actions the script's `transform` answers, so the new manifest lists them. */
    val actions: List<PluginActionSpec> = emptyList(),
    /** The API level the script is written for: 4 for the `wm` and `ui` globals, 5 for `from wm import …`. */
    val apiVersion: Int = PluginManifestCodec.PYTHON_ONLY_API,
    /** The websites a network template reaches. */
    val hosts: List<String> = emptyList(),
    /** The settings page the manifest declares. */
    val settings: List<PluginSettingSpec> = emptyList(),
) {
    BLANK(R.string.plugin_ide_template_blank_name, R.string.plugin_ide_template_blank_description, null, emptyList()),
    TEXT_TOOLS(
        R.string.plugin_ide_template_text_tools_name,
        R.string.plugin_ide_template_text_tools_description,
        "text-tools.py",
        emptyList(),
    ),
    CIPHER(R.string.plugin_ide_template_cipher_name, R.string.plugin_ide_template_cipher_description, "cipher-tool.py", emptyList()),
    TODO(
        R.string.plugin_ide_template_todo_name,
        R.string.plugin_ide_template_todo_description,
        "todo-list.py",
        listOf(PluginPermission.Storage, PluginPermission.Reminders),
    ),
    KITCHEN_SINK(
        R.string.plugin_ide_template_kitchen_sink_name,
        R.string.plugin_ide_template_kitchen_sink_description,
        "ui-kitchen-sink.py",
        emptyList(),
    ),
    TEXT_ACTIONS(
        R.string.plugin_ide_template_text_actions_name,
        R.string.plugin_ide_template_text_actions_description,
        "text-actions.py",
        listOf(PluginPermission.Storage, PluginPermission.SelectedText),
        listOf(
            PluginActionSpec(id = "rot13", name = "ROT13", summary = "Shift each letter 13 places", icon = "lock"),
            PluginActionSpec(id = "quote", name = "Wrap in quotes", summary = "Put quotes around the selection"),
        ),
    ),
    TEXT_GENERATOR(
        R.string.plugin_ide_template_text_generator_name,
        R.string.plugin_ide_template_text_generator_description,
        "text-generator.py",
        emptyList(),
        apiVersion = PluginManifestCodec.PACKAGE_API,
    ),
    WEATHER(
        R.string.plugin_ide_template_weather_name,
        R.string.plugin_ide_template_weather_description,
        "weather.py",
        listOf(PluginPermission.Network, PluginPermission.Storage),
        apiVersion = PluginManifestCodec.PACKAGE_API,
        hosts = listOf("api.open-meteo.com", "geocoding-api.open-meteo.com"),
        settings = listOf(
            PluginSettingSpec(
                key = "units",
                type = PluginSettingSpec.TYPE_CHOICE,
                label = "Units",
                default = "metric",
                options = listOf("metric", "imperial"),
            ),
        ),
    ),
    ;

    /** The script a new draft starts with. Reads an asset, so it runs off the main thread. */
    fun script(context: Context): String =
        asset?.let { file -> context.assets.open("$ASSET_DIR/$file").bufferedReader().use { it.readText() } } ?: BLANK_PLUGIN_SCRIPT

    companion object {
        const val ASSET_DIR = "plugin-templates"
    }
}

/** A new draft from [template] named [name], with an identifier nothing else has. Runs off the main thread. */
internal fun newDraftFromTemplate(
    context: Context,
    workspace: PluginWorkspace,
    store: PluginStore,
    template: PluginTemplate,
    name: String,
): PluginDraft? {
    val taken = store.plugins().map { it.id }.toSet() +
        workspace.drafts().mapNotNull { workspace.manifest(it.draftId)?.id }
    val manifest = PluginManifest(
        format = PluginManifestCodec.FORMAT,
        id = uniquePluginId(name, taken),
        name = PluginManifestCodec.sanitise(name, PluginManifestCodec.Field.NAME),
        pluginVersion = "0.1.0",
        apiVersion = template.apiVersion,
        permissions = template.permissions.map { it.wire },
        actions = template.actions,
        hosts = template.hosts,
        settings = template.settings,
    )
    return workspace.create(manifest, template.script(context), "template:${template.name.lowercase()}")
}

/** What opening a plugin file as a draft came to. */
internal sealed interface DraftImport {
    data class Created(val draft: PluginDraft) : DraftImport

    /** The file is a plugin an install would refuse, for [reason]. */
    data class Refused(val reason: PluginText) : DraftImport

    data object NotAPlugin : DraftImport

    data object Failed : DraftImport
}

/**
 * A new draft from a `.wmplugin` file, read by the same reader an install uses,
 * so the editor never holds a plugin an install would refuse. The file as it
 * came is kept as the draft's first snapshot. Nothing is installed. Runs off
 * the main thread.
 */
internal fun importDraft(workspace: PluginWorkspace, input: InputStream): DraftImport =
    when (val read = PluginFile.read(input)) {
        is PluginReadResult.Ok -> workspace.create(read.manifest, read.script, "import")?.let { draft ->
            workspace.snapshot(draft.draftId, SnapshotReason.IMPORT)
            DraftImport.Created(draft)
        } ?: DraftImport.Failed
        is PluginReadResult.Rejected -> DraftImport.Refused(read.reasonText)
        is PluginReadResult.NotAPlugin -> DraftImport.NotAPlugin
        is PluginReadResult.Failed -> DraftImport.Failed
    }
