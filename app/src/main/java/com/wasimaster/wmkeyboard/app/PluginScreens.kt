package com.wasimaster.wmkeyboard.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.plugins.PluginImportResult
import com.wasimaster.wmkeyboard.core.plugins.PluginLog
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestResult
import com.wasimaster.wmkeyboard.core.plugins.PluginStorage
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.resolve
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.R as ImeR

/**
 * Managing plugins: the master switch, what is installed, and what each one is
 * allowed to do.
 *
 * The whole subsystem is **off until asked for**. Nothing installs and nothing
 * runs before the user turns it on, and the explainer beside the switch says in
 * plain words what a plugin can and cannot reach — which is a short list,
 * because the sandbox has no API for reading text, the clipboard or the network.
 */
@Composable
internal fun PluginsScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { PluginStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val enabled = remember(revision) { store.subsystemEnabled() }
    val autoDisable = remember(revision) { store.autoDisableOnAbandon() }
    val plugins = remember(revision) { store.plugins() }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> pending = uri }

    // No scroller of its own: SettingsScreen already wraps this content in a
    // Column(verticalScroll), and a scrollable inside a scrollable is measured
    // with an infinite maximum height, which Compose refuses outright.
    SettingsGroup {
        item {
            // Highlightable by name: an addon page that refused to install a
            // plugin sends the user straight to this switch. The name is the
            // match key the addon screen and the search index hold, so it stays
            // an English literal; only the drawn title is a resource.
            HighlightableRow("Allow plugins") {
                val title = stringResource(R.string.plugins_allow_title)
                WmRow(
                    title = title,
                    subtitle = stringResource(R.string.plugins_allow_subtitle),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Hand-built rather than a ToggleSetting, because
                            // this row is matched by the English literal above
                            // and ToggleSetting would key the highlight on the
                            // drawn title instead. The reset control is the one
                            // thing it would otherwise have brought with it.
                            ResetSetting(title, enabled) { store.setSubsystemEnabled(false) }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { store.setSubsystemEnabled(it) },
                            )
                        }
                    },
                )
            }
        }
        if (enabled) {
            item {
                ToggleSetting(
                    R.string.plugins_auto_disable_title,
                    stringResource(R.string.plugins_auto_disable_subtitle),
                    autoDisable,
                    info = stringResource(R.string.plugins_auto_disable_info),
                    default = true,
                ) { store.setAutoDisableOnAbandon(it) }
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.plugins_facts_title),
        info = stringResource(R.string.plugins_facts_info),
    ) {
        item { PluginFact(stringResource(R.string.plugins_fact_no_typing), allowed = false) }
        item { PluginFact(stringResource(R.string.plugins_fact_no_field), allowed = false) }
        item { PluginFact(stringResource(R.string.plugins_fact_no_clipboard), allowed = false) }
        item { PluginFact(stringResource(R.string.plugins_fact_no_internet), allowed = false) }
        item { PluginFact(stringResource(R.string.plugins_fact_own_panel), allowed = true) }
        item { PluginFact(stringResource(R.string.plugins_fact_own_storage), allowed = true) }
    }

    if (enabled) {
        SettingsGroup(stringResource(R.string.plugins_installed_title)) {
            if (plugins.isEmpty()) {
                item { CaptionText(stringResource(R.string.plugins_installed_empty)) }
            } else {
                for (plugin in plugins) {
                    item {
                        val subtitle = when {
                            plugin.author.isNotBlank() && !plugin.enabled -> stringResource(
                                R.string.plugins_row_version_author_off,
                                plugin.version,
                                plugin.author,
                            )

                            plugin.author.isNotBlank() -> stringResource(
                                R.string.plugins_row_version_author,
                                plugin.version,
                                plugin.author,
                            )

                            !plugin.enabled ->
                                stringResource(R.string.plugins_row_version_off, plugin.version)

                            else -> stringResource(R.string.plugins_row_version, plugin.version)
                        }
                        HighlightableItem(plugin.id) {
                            WmRow(
                                title = plugin.name,
                                subtitle = subtitle,
                                trailing = {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ArrowForward,
                                        contentDescription = null,
                                    )
                                },
                                // The arrow promises a tap opens it. It used to
                                // sit above a separate "Manage" button and do
                                // nothing.
                                onClick = { onNavigate("plugin/${plugin.id}") },
                            )
                        }
                    }
                }
            }
        }

        SettingsGroup(
            stringResource(R.string.plugins_add_title),
            info = stringResource(R.string.plugins_install_repo_info),
        ) {
            item { AddonStoreRow(AddonType.Plugin, onNavigate) }
            item {
                WmRow(
                    title = stringResource(R.string.plugins_install_file_title),
                    subtitle = stringResource(R.string.plugins_install_file_subtitle),
                    onClick = { picker.launch(PluginFile.IMPORT_MIME_TYPES) },
                )
            }
        }
    }

    // The same disclosure the file-manager path shows: what the plugin says it
    // is and what it may do, before a line of its code is on the device.
    pending?.let { uri ->
        PluginInstallDialog(
            uri = uri,
            onDismiss = { pending = null },
            onDone = { message ->
                pending = null
                importMessage = message
            },
            scope = scope,
        )
    }

    importMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { importMessage = null },
            // The tool's own name, read from the keyboard module: one copy of
            // the word for translators, not two.
            title = { Text(stringResource(ImeR.string.ime_tool_plugins)) },
            text = { Text(message) },
            confirmButton = {
                TextButton({ importMessage = null }) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
    }
}

@Composable
private fun PluginFact(text: String, allowed: Boolean) {
    WmRow(
        title = text,
        leading = {
            Text(
                if (allowed) "✓" else "✕",
                color = if (allowed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        },
    )
}

/** One plugin: what it may do, what it has stored, its log, and how to remove it. */
@Composable
internal fun PluginDetailScreen(pluginId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PluginStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val plugin = remember(revision) { store.plugin(pluginId) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmUninstall by remember { mutableStateOf(false) }

    if (plugin == null) {
        Column { CaptionText(stringResource(R.string.plugins_detail_missing)) }
        return
    }

    val storage = remember(pluginId, refresh) { PluginStorage(store.storageFile(pluginId)) }
    val usedBytes = remember(pluginId, refresh) { storage.usedBytes() }
    val log = remember(pluginId, refresh) {
        PluginLog(store.logFile(pluginId)).also { it.restore() }.lines()
    }

    // No scroller of its own: SettingsScreen already wraps this content in a
    // Column(verticalScroll), and a scrollable inside a scrollable is measured
    // with an infinite maximum height, which Compose refuses outright.
    SettingsGroup {
        item {
            val noDescription = stringResource(R.string.plugins_detail_no_description)
            val versionLine = if (plugin.author.isNotBlank()) {
                stringResource(R.string.plugins_row_version_author, plugin.version, plugin.author)
            } else {
                stringResource(R.string.plugins_row_version, plugin.version)
            }
            WmRow(
                title = plugin.name,
                subtitle = plugin.description.ifBlank { noDescription } + "\n\n" + versionLine,
            )
        }
        item {
            WmRow(
                title = stringResource(R.string.plugins_detail_enabled_title),
                subtitle = if (plugin.abandonedCount >= PluginStore.MAX_ABANDONS) {
                    stringResource(R.string.plugins_detail_stopped_subtitle)
                } else {
                    stringResource(R.string.plugins_detail_enabled_subtitle)
                },
                trailing = {
                    Switch(
                        checked = plugin.enabled,
                        onCheckedChange = { store.setEnabled(pluginId, it) },
                    )
                },
            )
        }
    }

    SettingsGroup(
        stringResource(R.string.plugins_permissions_title),
        info = stringResource(R.string.plugins_permissions_info),
    ) {
        if (plugin.grantedPermissions.isEmpty()) {
            item {
                WmRow(title = stringResource(R.string.plugins_permissions_none))
            }
        } else {
            for (permission in plugin.grantedPermissions) {
                item {
                    WmRow(title = stringResource(permission.labelRes))
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.plugins_storage_title)) {
        item {
            WmRow(
                title = stringResource(
                    R.string.plugins_storage_used_label,
                    usedBytes / 1024,
                    PluginStorage.MAX_TOTAL_BYTES / 1024,
                ),
                subtitle = stringResource(R.string.plugins_storage_subtitle),
            )
        }
        item {
            TextButton(onClick = { storage.clear(); refresh++ }) {
                Text(stringResource(R.string.plugins_storage_clear_action))
            }
        }
    }

    if (log.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.plugins_log_title),
            info = stringResource(R.string.plugins_log_info),
        ) {
            for (line in log.takeLast(LOG_LINES)) {
                item {
                    WmRow(
                        title = line,
                        titleStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                TextButton(onClick = { PluginLog(store.logFile(pluginId)).clear(); refresh++ }) {
                    Text(stringResource(R.string.plugins_log_clear_action))
                }
            }
        }
    }

    SettingsGroup {
        item {
            TextButton(onClick = { confirmUninstall = true }) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
                Text("  " + stringResource(CommonR.string.common_uninstall))
            }
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(stringResource(R.string.plugins_uninstall_confirm_title, plugin.name)) },
            text = { Text(stringResource(R.string.plugins_uninstall_confirm_body)) },
            confirmButton = {
                TextButton({
                    store.delete(pluginId)
                    confirmUninstall = false
                    onBack()
                }) { Text(stringResource(CommonR.string.common_uninstall)) }
            },
            dismissButton = {
                TextButton({ confirmUninstall = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

/** Log lines shown before the list is more scrolling than reading. */
private const val LOG_LINES = 40

/**
 * Confirms installing a picked `.wmplugin`, showing what it claims to be and
 * what it would be allowed to do.
 *
 * Deliberately the same disclosure the file-manager path produces. There is no
 * route into the store that skips it — a plugin whose capabilities the user
 * never saw is one they never agreed to.
 */
@Composable
private fun PluginInstallDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    val read = remember(uri) {
        runCatching {
            context.contentResolver.requireInputStream(uri).use { PluginFile.readManifest(it) }
        }.getOrNull()
    }
    val ok = read as? PluginManifestResult.Ok

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (ok != null) {
                    stringResource(R.string.plugins_install_confirm_title, ok.manifest.name)
                } else {
                    stringResource(R.string.plugins_install_unreadable_title)
                },
            )
        },
        text = {
            val defaultDescription = stringResource(R.string.plugins_install_default_description)
            val noPermissions = stringResource(R.string.plugins_install_no_permissions)
            val permissionsIntro = stringResource(R.string.plugins_install_permissions_intro)
            val notAPlugin = stringResource(R.string.plugins_not_a_plugin_error)
            val body = if (ok != null) {
                val versionLine = if (ok.manifest.author.isNotBlank()) {
                    stringResource(
                        R.string.plugins_row_version_author,
                        ok.manifest.pluginVersion,
                        ok.manifest.author,
                    )
                } else {
                    stringResource(R.string.plugins_row_version, ok.manifest.pluginVersion)
                }
                // Resolved through the context rather than stringResource: the
                // labels are read in a loop over a list, and this keeps the
                // composable call out of a lambda.
                val permissionLabels = ok.permissions.map { context.getString(it.labelRes) }
                buildString {
                    append(ok.manifest.description.ifBlank { defaultDescription })
                    append("\n\n")
                    append(versionLine)
                    append("\n\n")
                    if (permissionLabels.isEmpty()) {
                        append(noPermissions)
                    } else {
                        append(permissionsIntro)
                        for (label in permissionLabels) append("\n• $label")
                    }
                }
            } else {
                (read as? PluginManifestResult.Rejected)?.reasonText?.resolve(context) ?: notAPlugin
            }
            Text(body)
        },
        confirmButton = {
            if (ok != null) {
                TextButton(onClick = {
                    scope.launch {
                        val store = PluginStore.get(context)
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.requireInputStream(uri)
                                    .use { PluginFile.import(it, store) }
                            }.getOrDefault(PluginImportResult.Failed)
                        }
                        onDone(
                            when (result) {
                                is PluginImportResult.Imported -> context.getString(
                                    R.string.plugins_install_done_message,
                                    result.plugin.name,
                                )

                                is PluginImportResult.Rejected ->
                                    result.reasonText.resolve(context)

                                PluginImportResult.NotAPlugin ->
                                    context.getString(R.string.plugins_not_a_plugin_error)

                                PluginImportResult.TooManyPlugins ->
                                    context.resources.getQuantityString(
                                        R.plurals.plugins_too_many_error,
                                        PluginStore.MAX_PLUGINS,
                                        PluginStore.MAX_PLUGINS,
                                    )

                                PluginImportResult.Failed ->
                                    context.getString(R.string.plugins_install_failed_error)
                            },
                        )
                    }
                }) { Text(stringResource(CommonR.string.common_install)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
