package com.wasimaster.wmkeyboard.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowForward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Block
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Code
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Handyman
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PowerSettingsNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ToggleOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Widgets
import com.wasimaster.wmkeyboard.core.settings.PluginToolPlace
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.pluginToolPlaceIn
import com.wasimaster.wmkeyboard.ime.ui.PluginIcon
import com.wasimaster.wmkeyboard.ime.ui.toolColor
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.plugins.PluginImportResult
import com.wasimaster.wmkeyboard.core.plugins.PluginLog
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestResult
import com.wasimaster.wmkeyboard.core.plugins.ParkedPlugin
import com.wasimaster.wmkeyboard.core.plugins.InstalledPlugin
import com.wasimaster.wmkeyboard.core.plugins.displayDescription
import com.wasimaster.wmkeyboard.core.plugins.displayName
import com.wasimaster.wmkeyboard.core.plugins.label
import com.wasimaster.wmkeyboard.core.plugins.PluginPermission
import com.wasimaster.wmkeyboard.core.plugins.PluginSecrets
import com.wasimaster.wmkeyboard.core.plugins.PluginSettingSpec
import com.wasimaster.wmkeyboard.core.plugins.PluginReminderAlarms
import com.wasimaster.wmkeyboard.core.plugins.PluginReminders
import com.wasimaster.wmkeyboard.core.plugins.PluginStorage
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.PluginWorkspace
import com.wasimaster.wmkeyboard.core.plugins.PythonNative
import com.wasimaster.wmkeyboard.core.plugins.resolve
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.ime.ui.pluginActionIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.R as ImeR

/**
 * One plugin's page, as flights name it.
 *
 * The navigation route with its argument filled in, which is what a flight is
 * keyed on: the pattern (`plugin/{pluginId}`) is the same string for every
 * plugin and would hang one key on all of them.
 */
internal fun pluginRoute(pluginId: String): String = "plugin/$pluginId"

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
internal fun PluginsScreen(repository: SettingsRepository, settings: LiveSettings, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { PluginStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val enabled = remember(revision) { store.subsystemEnabled() }
    val autoDisable = remember(revision) { store.autoDisableOnAbandon() }
    val allowRisky = remember(revision) { store.allowRisky() }
    var confirmRisky by remember { mutableStateOf(false) }
    val plugins = remember(revision) { store.plugins() }
    // Where each plugin is a tool, from the global lists (a mode's own order
    // only decides where it sits).
    val toolLists = settings.watch { Triple(it.toolbarTools, it.toolboxOrder, it.toolbox.folders) }
    val toolPlaces = { id: String -> pluginToolPlaceIn(toolLists.first, toolLists.second, toolLists.third, id) }
    var uninstalling by remember { mutableStateOf<InstalledPlugin?>(null) }
    val retired = remember(revision) { store.retiredNames() }
    val parked = remember(revision) { store.parked() }
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
                    icon = SettingsRowIcons[R.string.plugins_allow_title],
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Hand-built rather than a ToggleSetting, because
                            // this row is matched by the English literal above
                            // and ToggleSetting would key the highlight on the
                            // drawn title instead. The reset control is the one
                            // thing it would otherwise have brought with it.
                            RowExtras { ResetSetting(title, enabled) { store.setSubsystemEnabled(false) } }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { on ->
                                    store.setSubsystemEnabled(on)
                                    // The interpreter is an on-demand download: fetch
                                    // it now rather than at the first plugin opened.
                                    if (on) PythonNative.requestModule()
                                },
                            )
                        }
                    },
                )
            }
        }
        item(visible = enabled, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.plugins_auto_disable_title,
                stringResource(R.string.plugins_auto_disable_subtitle),
                autoDisable,
                info = stringResource(R.string.plugins_auto_disable_info),
                default = true,
            ) { store.setAutoDisableOnAbandon(it) }
        }
        item(visible = enabled) {
            HighlightableRow("Allow risky permissions") {
                ToggleSetting(
                    R.string.plugins_risky_title,
                    stringResource(R.string.plugins_risky_subtitle),
                    allowRisky,
                    info = stringResource(R.string.plugins_risky_info),
                    default = false,
                ) { on -> if (on) confirmRisky = true else store.setAllowRisky(false) }
            }
        }
    }

    if (confirmRisky) {
        AlertDialog(
            onDismissRequest = { confirmRisky = false },
            title = { Text(stringResource(R.string.plugins_risky_confirm_title)) },
            text = { Text(stringResource(R.string.plugins_risky_confirm_body)) },
            confirmButton = {
                TextButton({
                    store.setAllowRisky(true)
                    confirmRisky = false
                }) { Text(stringResource(R.string.plugins_risky_title)) }
            },
            dismissButton = { TextButton({ confirmRisky = false }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }

    PluginFactsGroups()

    // Outside the master switch on purpose: writing and previewing a plugin runs
    // nothing in the keyboard, and a plugin can be written before plugins are on.
    SettingsGroup(stringResource(R.string.plugin_ide_group_title), level = SettingLevel.ADVANCED) {
        item {
            WmRow(
                title = stringResource(R.string.plugin_ide_entry_title),
                icon = SettingsRowIcons[R.string.plugin_ide_entry_title],
                subtitle = stringResource(R.string.plugin_ide_entry_subtitle_python),
                trailing = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                onClick = { onNavigate("plugin_ide") },
            )
        }
    }

    // Outside the master switch: the Lua plugins went whether or not plugins
    // are on now, and their data is the user's either way.
    if (retired.isNotEmpty() || parked.isNotEmpty()) RetiredPluginsGroup(store, retired, parked)

    if (enabled) {
        SettingsGroup(stringResource(R.string.plugins_installed_title)) {
            if (plugins.isEmpty()) {
                item { CaptionText(stringResource(R.string.plugins_installed_empty)) }
            } else {
                for (plugin in plugins) {
                    item(key = plugin.id) {
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
                            PluginListRow(
                                plugin = plugin,
                                subtitle = subtitle,
                                place = toolPlaces(plugin.id),
                                onPlace = { place -> scope.launch { repository.setPluginToolPlace(plugin.id, place) } },
                                onNavigate = onNavigate,
                                onUninstall = { uninstalling = plugin },
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
                    icon = SettingsRowIcons[R.string.plugins_install_file_title],
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

    uninstalling?.let { plugin ->
        UninstallPluginDialog(plugin, onDismiss = { uninstalling = null }) {
            // Out of every tool list, the modes' and the floating button's
            // included, so nothing is left pointing at it.
            scope.launch { repository.removePluginTool(plugin.id) }
            store.delete(plugin.id)
            uninstalling = null
        }
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

/**
 * What an update did to plugins written in Lua: a one-time notice naming them,
 * and the data each one kept, which waits [PluginStore.PARKED_DAYS] days for
 * the plugin's Python version to be installed.
 */
@Composable
private fun RetiredPluginsGroup(store: PluginStore, retired: List<String>, parked: List<ParkedPlugin>) {
    var confirm by remember { mutableStateOf<ParkedPlugin?>(null) }
    val now = remember(parked) { System.currentTimeMillis() }
    SettingsGroup(stringResource(R.string.plugins_retired_title)) {
        if (retired.isNotEmpty()) {
            item { CaptionText(stringResource(R.string.plugins_retired_notice, retired.joinToString(", "))) }
            item {
                TextButton(onClick = { store.dismissRetired() }) {
                    Text(stringResource(R.string.plugins_retired_dismiss))
                }
            }
        }
        for (entry in parked) {
            item(key = "parked:${entry.id}") {
                val daysLeft = (PluginStore.PARKED_DAYS - ((now - entry.parkedAt) / DAY_MILLIS).toInt())
                    .coerceIn(1, PluginStore.PARKED_DAYS)
                val kilobytes = ((entry.bytes + 1023) / 1024).toInt()
                WmRow(
                    title = entry.name,
                    subtitle = pluralStringResource(R.plurals.plugins_parked_subtitle, daysLeft, kilobytes, daysLeft),
                    trailing = {
                        IconButton(onClick = { confirm = entry }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.plugins_parked_delete, entry.name),
                            )
                        }
                    },
                )
            }
        }
    }
    confirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.plugins_parked_delete_confirm_title, entry.name)) },
            text = { Text(stringResource(R.string.plugins_parked_delete_confirm_body)) },
            confirmButton = {
                TextButton({
                    store.deleteParked(entry.id)
                    confirm = null
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton({ confirm = null }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * The plain-words warning for a plugin that can read something of the user's
 * and send data away, or null when it cannot do both.
 */
internal fun pluginCombinationWarning(
    context: android.content.Context,
    permissions: List<PluginPermission>,
    hosts: List<String>,
): String? {
    if (!PluginPermission.combinationRisk(permissions)) return null
    return if (PluginPermission.Network in permissions && hosts.isNotEmpty()) {
        context.getString(com.wasimaster.wmkeyboard.plugins.R.string.core_plugins_permission_combination_warning, hosts.joinToString(", "))
    } else {
        context.getString(com.wasimaster.wmkeyboard.plugins.R.string.core_plugins_permission_combination_ai_warning)
    }
}

/**
 * What a plugin can and cannot do, built from [PluginPermission.all] so the
 * list cannot drift from what the sandbox enforces: what every plugin may do,
 * what one may do if it lists it before install, what waits for the user's
 * switch, and what none may do whatever it lists.
 */
@Composable
private fun PluginFactsGroups() {
    val listed = PluginPermission.all.filter { !it.isRisky }
    val risky = PluginPermission.all.filter { it.isRisky }
    val yes = MaterialTheme.colorScheme.primary
    SettingsGroup(
        stringResource(R.string.plugins_facts_title),
        info = stringResource(R.string.plugins_facts_info_v5),
        level = SettingLevel.STANDARD,
    ) {
        item { PluginFact(stringResource(R.string.plugins_fact_always_panel), Icons.Outlined.CheckCircle, yes) }
        item { PluginFact(stringResource(R.string.plugins_fact_always_tile), Icons.Outlined.CheckCircle, yes) }
    }
    SettingsGroup(stringResource(R.string.plugins_facts_listed_title), level = SettingLevel.STANDARD) {
        for (permission in listed) {
            item(key = "fact:${permission.wire}") { PluginFact(stringResource(permission.labelRes), Icons.Outlined.CheckCircle, yes) }
        }
    }
    SettingsGroup(stringResource(R.string.plugins_facts_granted_title), level = SettingLevel.STANDARD) {
        for (permission in risky) {
            item(key = "fact:${permission.wire}") {
                PluginFact(stringResource(permission.labelRes), Icons.Outlined.ToggleOn, MaterialTheme.colorScheme.tertiary)
            }
        }
    }
    SettingsGroup(stringResource(R.string.plugins_facts_never_title), level = SettingLevel.STANDARD) {
        for (res in NeverFacts) {
            item(key = "never:$res") { PluginFact(stringResource(res), Icons.Outlined.Block, MaterialTheme.colorScheme.error) }
        }
    }
}

private val NeverFacts = listOf(
    R.string.plugins_fact_never_typing,
    R.string.plugins_fact_never_watch,
    R.string.plugins_fact_never_private,
    R.string.plugins_fact_never_background,
    R.string.plugins_fact_never_code,
)

@Composable
private fun PluginFact(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color) {
    WmRow(
        title = text,
        leading = { Icon(icon, contentDescription = null, tint = tint) },
    )
}

/**
 * An installed plugin in the list. A tap opens its page; a hold or the ⋮
 * button opens everything else (toolbar, toolbox, on or off, the code, its
 * data, uninstall), and a swipe toward the start shows the three most used.
 * Every swipe action is also in the menu, which is what TalkBack reaches.
 */
@Composable
private fun PluginListRow(
    plugin: InstalledPlugin,
    subtitle: String,
    place: PluginToolPlace,
    onPlace: (PluginToolPlace) -> Unit,
    onNavigate: (String) -> Unit,
    onUninstall: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { PluginStore.get(context) }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val onToolbar = place == PluginToolPlace.TOOLBAR
    val swipes = listOf(
        SwipeAction(
            Icons.Outlined.Widgets,
            stringResource(if (onToolbar) ImeR.string.ime_plugin_action_toolbar_remove else ImeR.string.ime_plugin_action_toolbar_add),
            SwipeTone.ACCENT,
        ) { onPlace(if (onToolbar) PluginToolPlace.TOOLBOX else PluginToolPlace.TOOLBAR) },
        SwipeAction(
            Icons.Outlined.PowerSettingsNew,
            stringResource(if (plugin.enabled) ImeR.string.ime_plugin_action_turn_off else ImeR.string.ime_plugin_action_turn_on),
        ) { store.setEnabled(plugin.id, !plugin.enabled) },
        SwipeAction(Icons.Outlined.Delete, stringResource(CommonR.string.common_uninstall), SwipeTone.DESTRUCTIVE, onUninstall),
    )
    androidx.compose.foundation.layout.Box {
        WmRow(
            title = plugin.displayName,
            subtitle = subtitle,
            // The icon its tool wears on the toolbar and in the toolbox.
            leading = { PluginIcon(plugin, tint = plugin.toolColor ?: MaterialTheme.colorScheme.onSurfaceVariant) },
            swipeActions = swipes,
            trailing = {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(ImeR.string.ime_plugin_more, plugin.displayName))
                }
            },
            onLongClick = { menu = true },
            onClick = { onNavigate(pluginRoute(plugin.id)) },
        )
        androidx.compose.material3.DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            offset = androidx.compose.ui.unit.DpOffset(x = 16.dp, y = 0.dp),
        ) {
            @Composable
            fun entry(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean = false, action: () -> Unit) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label, color = if (destructive) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) },
                    leadingIcon = { Icon(icon, contentDescription = null, tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {
                        menu = false
                        action()
                    },
                )
            }
            entry(stringResource(ImeR.string.ime_plugin_action_settings), Icons.Outlined.Settings) { onNavigate(pluginRoute(plugin.id)) }
            entry(
                stringResource(if (onToolbar) ImeR.string.ime_plugin_action_toolbar_remove else ImeR.string.ime_plugin_action_toolbar_add),
                Icons.Outlined.Widgets,
            ) { onPlace(if (onToolbar) PluginToolPlace.TOOLBOX else PluginToolPlace.TOOLBAR) }
            entry(
                stringResource(if (place == PluginToolPlace.TOOLBOX) ImeR.string.ime_plugin_action_toolbox_remove else ImeR.string.ime_plugin_action_toolbox_add),
                Icons.Outlined.Handyman,
            ) { onPlace(if (place == PluginToolPlace.TOOLBOX) PluginToolPlace.NONE else PluginToolPlace.TOOLBOX) }
            entry(
                stringResource(if (plugin.enabled) ImeR.string.ime_plugin_action_turn_off else ImeR.string.ime_plugin_action_turn_on),
                Icons.Outlined.PowerSettingsNew,
            ) { store.setEnabled(plugin.id, !plugin.enabled) }
            entry(stringResource(R.string.plugin_ide_edit_title), Icons.Outlined.Code) {
                scope.launch { openPluginCode(context, store, plugin.id, onNavigate) }
            }
            entry(stringResource(R.string.plugins_storage_clear_action), Icons.Outlined.DeleteSweep) {
                PluginStorage(store.storageFile(plugin.id), PluginStorage.Quota.forApi(plugin.apiVersion)).clear()
            }
            entry(stringResource(CommonR.string.common_uninstall), Icons.Outlined.Delete, destructive = true, action = onUninstall)
        }
    }
}

/** Opens [pluginId]'s code in the editor, as a draft made from the installed copy. */
private suspend fun openPluginCode(context: android.content.Context, store: PluginStore, pluginId: String, onNavigate: (String) -> Unit) {
    val draftId = withContext(Dispatchers.IO) { draftForInstalled(PluginWorkspace.get(context), store, pluginId) }
    if (draftId != null) {
        onNavigate("plugin_ide/$draftId")
    } else {
        android.widget.Toast.makeText(context, context.getString(R.string.plugin_ide_edit_error), android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun UninstallPluginDialog(plugin: InstalledPlugin, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugins_uninstall_confirm_title, plugin.displayName)) },
        text = { Text(stringResource(R.string.plugins_uninstall_confirm_body)) },
        confirmButton = { TextButton(onConfirm) { Text(stringResource(CommonR.string.common_uninstall)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

/** One plugin: what it may do, what it has stored, its log, and how to remove it. */
@Composable
internal fun PluginDetailScreen(
    pluginId: String,
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { PluginStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val plugin = remember(revision) { store.plugin(pluginId) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmUninstall by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val place = settings.watch { pluginToolPlaceIn(it.toolbarTools, it.toolboxOrder, it.toolbox.folders, pluginId) }

    if (plugin == null) {
        Column { CaptionText(stringResource(R.string.plugins_detail_missing)) }
        return
    }

    val quota = PluginStorage.Quota.forApi(plugin.apiVersion)
    val storage = remember(pluginId, refresh) { PluginStorage(store.storageFile(pluginId), quota) }
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
                title = plugin.displayName,
                subtitle = plugin.displayDescription.ifBlank { noDescription } + "\n\n" + versionLine,
                leading = { PluginIcon(plugin, tint = plugin.toolColor ?: MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
        item {
            // This screen is a deep-link route of its own, so it can be
            // reached while the "Allow plugins" master switch is off — and
            // with it off nothing here runs. Greyed rather than gone: it is
            // the page's only state control, and a page with no switch at all
            // would say less than a switch that says why it cannot be moved.
            val subsystemOn = remember(revision) { store.subsystemEnabled() }
            // No `default`: an installed plugin is not a shipped setting, so
            // there is no value to reset the switch to.
            ToggleSetting(
                R.string.plugins_detail_enabled_title,
                subtitle = when {
                    !subsystemOn -> stringResource(R.string.plugins_detail_subsystem_off_subtitle)
                    plugin.abandonedCount >= PluginStore.MAX_ABANDONS ->
                        stringResource(R.string.plugins_detail_stopped_subtitle)
                    else -> stringResource(R.string.plugins_detail_enabled_subtitle)
                },
                checked = plugin.enabled,
                enabled = subsystemOn,
                disabledReason = rememberDisabledReason(
                    stringResource(R.string.plugins_detail_subsystem_off_subtitle),
                    R.string.plugins_allow_title,
                ),
            ) { store.setEnabled(pluginId, it) }
        }
        item(level = SettingLevel.ADVANCED) {
            WmRow(
                title = stringResource(R.string.plugin_ide_edit_title),
                icon = SettingsRowIcons[R.string.plugin_ide_edit_title],
                subtitle = stringResource(R.string.plugin_ide_edit_subtitle_python),
                trailing = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                onClick = { scope.launch { openPluginCode(context, store, pluginId, onNavigate) } },
            )
        }
    }

    // A plugin with a panel can be a tool of its own: pinned to the toolbar or
    // in the toolbox grid, where it drags, files into folders and ranks by
    // use like any tool, and opens straight into the plugin.
    if (plugin.panel) {
        SettingsGroup(stringResource(R.string.plugins_tile_title), info = stringResource(R.string.plugins_tile_info)) {
            item {
                SingleChoiceSegmentedButtonRow(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                    val choices = listOf(
                        PluginToolPlace.NONE to R.string.plugins_tile_none,
                        PluginToolPlace.TOOLBAR to R.string.plugins_tile_toolbar,
                        PluginToolPlace.TOOLBOX to R.string.plugins_tile_toolbox,
                    )
                    choices.forEachIndexed { index, (choice, label) ->
                        SegmentedButton(
                            selected = place == choice,
                            onClick = { scope.launch { repository.setPluginToolPlace(pluginId, choice) } },
                            shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                        ) { Text(stringResource(label), maxLines = 1) }
                    }
                }
            }
        }
    }

    PluginPermissionsGroup(store, plugin, revision)
    if (plugin.settings.isNotEmpty()) PluginSettingsGroup(store, plugin)

    if (plugin.actions.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.plugins_actions_title),
            info = stringResource(R.string.plugins_actions_info),
        ) {
            for (action in plugin.actions) {
                item(key = "action:${action.id}") {
                    WmRow(
                        title = plugin.label("@action.${action.id}", action.name),
                        subtitle = plugin.label("@action.${action.id}.summary", action.summary).takeIf { it.isNotBlank() },
                        icon = pluginActionIcon(action.icon),
                    )
                }
            }
            item {
                NavRow(
                    R.string.plugins_actions_settings_link,
                    route = SelectionMacroRoute,
                ) { onNavigate(SelectionMacroRoute) }
            }
        }
    }

    SettingsGroup(stringResource(R.string.plugins_storage_title), level = SettingLevel.STANDARD) {
        item {
            WmRow(
                title = stringResource(
                    R.string.plugins_storage_used_label,
                    usedBytes / 1024,
                    quota.maxTotal / 1024,
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

    if (plugin.grants(PluginPermission.Reminders)) {
        PluginRemindersGroup(PluginReminders(store.remindersFile(pluginId)) { PluginReminderAlarms.requestRearm(context) })
    }

    if (log.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.plugins_log_title),
            info = stringResource(R.string.plugins_log_info),
            level = SettingLevel.ADVANCED,
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
            title = { Text(stringResource(R.string.plugins_uninstall_confirm_title, plugin.displayName)) },
            text = { Text(stringResource(R.string.plugins_uninstall_confirm_body)) },
            confirmButton = {
                TextButton({
                    scope.launch { repository.removePluginTool(pluginId) }
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

/**
 * What a plugin may do: the listed permissions as plain rows, and each risky
 * one as a switch that asks before it turns on, with the websites beside the
 * network. Greyed while the global switch is off, saying where it is.
 */
@Composable
private fun PluginPermissionsGroup(store: PluginStore, plugin: InstalledPlugin, revision: Int) {
    val riskyOn = remember(revision) { store.allowRisky() }
    var asking by remember { mutableStateOf<PluginPermission?>(null) }
    val declared = plugin.declaredPermissions
    SettingsGroup(
        stringResource(R.string.plugins_permissions_title),
        info = stringResource(R.string.plugins_permissions_info_v5),
    ) {
        if (plugin.newSinceUpdate.isNotEmpty()) {
            item {
                val context = LocalContext.current
                val names = plugin.newSinceUpdate.joinToString(", ") { wire ->
                    PluginPermission.parse(wire)?.let { context.getString(it.labelRes) } ?: wire
                }
                CaptionText(stringResource(R.string.plugins_new_since_update, names))
            }
        }
        if (declared.isEmpty()) {
            item { WmRow(title = stringResource(R.string.plugins_permissions_none)) }
        }
        for (permission in declared.filter { !it.isRisky }) {
            item(key = "perm:${permission.wire}") { WmRow(title = stringResource(permission.labelRes)) }
        }
        for (permission in declared.filter { it.isRisky }) {
            item(key = "risky:${permission.wire}") {
                val granted = permission.wire in plugin.granted
                ToggleSetting(
                    permission.labelRes,
                    subtitle = if (!riskyOn) {
                        stringResource(R.string.plugins_permission_risky_off_subtitle)
                    } else {
                        permission.warningRes?.let { stringResource(it) }
                    },
                    checked = granted && riskyOn,
                    enabled = riskyOn,
                    disabledReason = rememberDisabledReason(
                        stringResource(R.string.plugins_permission_risky_off_subtitle),
                        R.string.plugins_risky_title,
                    ),
                ) { on -> if (on) asking = permission else store.setGranted(plugin.id, permission, false) }
            }
        }
        if (plugin.declares(PluginPermission.Network) && (plugin.allowedHosts + plugin.localHosts).isNotEmpty()) {
            item {
                WmRow(
                    title = stringResource(R.string.plugins_hosts_title),
                    subtitle = (plugin.allowedHosts + plugin.localHosts).joinToString("\n"),
                )
            }
        }
    }
    asking?.let { permission ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.plugins_permission_grant_title, stringResource(permission.labelRes))) },
            text = {
                val warning = permission.warningRes?.let { stringResource(it) }.orEmpty()
                val hosts = when (permission) {
                    PluginPermission.Network -> "\n\n" + plugin.allowedHosts.joinToString("\n")
                    PluginPermission.NetworkLocal -> "\n\n" + plugin.localHosts.joinToString("\n")
                    else -> ""
                }
                Text(warning + hosts)
            },
            confirmButton = {
                TextButton({
                    store.setGranted(plugin.id, permission, true)
                    asking = null
                }) { Text(stringResource(com.wasimaster.wmkeyboard.plugins.R.string.core_plugins_ui_allow)) }
            },
            dismissButton = { TextButton({ asking = null }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
}

/** The plugin's own settings page, drawn from its manifest: switches, boxes, numbers, choices, websites and keys. */
@Composable
private fun PluginSettingsGroup(store: PluginStore, plugin: InstalledPlugin) {
    var editing by remember { mutableStateOf<PluginSettingSpec?>(null) }
    val revision by store.revision.collectAsStateWithLifecycle()
    // Which secret rows hold a value. Read from the plugin's secrets file, never shown.
    val savedSecrets = remember(revision, plugin.id) {
        val secrets = store.secrets(plugin.id)
        plugin.settings.filter { it.type == PluginSettingSpec.TYPE_SECRET && secrets?.get(it.key) != null }.map { it.key }.toSet()
    }
    SettingsGroup(stringResource(R.string.plugins_settings_title)) {
        for (setting in plugin.settings) {
            item(key = "setting:${setting.key}") {
                val value = plugin.settingValues[setting.key] ?: setting.default
                when (setting.type) {
                    PluginSettingSpec.TYPE_SWITCH -> WmRow(
                        title = plugin.label("@setting.${setting.key}", setting.label),
                        subtitle = plugin.label("@setting.${setting.key}.summary", setting.summary).takeIf { it.isNotBlank() },
                        trailing = {
                            Switch(
                                checked = value == "true",
                                onCheckedChange = { store.setSettingValue(plugin.id, setting.key, it.toString()) },
                            )
                        },
                    )

                    PluginSettingSpec.TYPE_CHOICE -> WmRow(
                        title = plugin.label("@setting.${setting.key}", setting.label),
                        subtitle = plugin.label("@option.${setting.key}.$value", value)
                            .ifBlank { plugin.label("@setting.${setting.key}.summary", setting.summary) },
                        onClick = {
                            val i = setting.options.indexOf(value)
                            val next = setting.options.getOrNull((i + 1) % setting.options.size.coerceAtLeast(1)) ?: return@WmRow
                            store.setSettingValue(plugin.id, setting.key, next)
                        },
                    )

                    PluginSettingSpec.TYPE_SECRET -> WmRow(
                        title = plugin.label("@setting.${setting.key}", setting.label),
                        subtitle = if (setting.key in savedSecrets) {
                            stringResource(R.string.plugins_setting_secret_saved)
                        } else {
                            plugin.label("@setting.${setting.key}.summary", setting.summary)
                                .ifBlank { stringResource(R.string.plugins_setting_secret_empty) }
                        },
                        onClick = { editing = setting },
                    )

                    else -> WmRow(
                        title = plugin.label("@setting.${setting.key}", setting.label),
                        subtitle = value.ifBlank { plugin.label("@setting.${setting.key}.summary", setting.summary) },
                        onClick = { editing = setting },
                    )
                }
            }
        }
    }
    editing?.let { setting ->
        val secret = setting.type == PluginSettingSpec.TYPE_SECRET
        // A secret box starts empty: the saved key is never put back on screen.
        var text by remember(setting.key) { mutableStateOf(if (secret) "" else plugin.settingValues[setting.key] ?: setting.default) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(plugin.label("@setting.${setting.key}", setting.label)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(if (secret) PluginSecrets.MAX_VALUE else PLUGIN_SETTING_MAX) },
                    singleLine = true,
                    placeholder = if (secret && setting.key in savedSecrets) {
                        { Text(stringResource(R.string.plugins_setting_secret_replace_hint)) }
                    } else {
                        null
                    },
                    visualTransformation = if (secret) {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    } else {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        autoCorrectEnabled = !secret,
                        keyboardType = when (setting.type) {
                            PluginSettingSpec.TYPE_NUMBER -> androidx.compose.ui.text.input.KeyboardType.Number
                            PluginSettingSpec.TYPE_HOST -> androidx.compose.ui.text.input.KeyboardType.Uri
                            PluginSettingSpec.TYPE_SECRET -> androidx.compose.ui.text.input.KeyboardType.Password
                            else -> androidx.compose.ui.text.input.KeyboardType.Text
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton({
                    // An empty secret box leaves the saved key alone; Remove forgets it.
                    if (!secret || text.isNotEmpty()) store.setSettingValue(plugin.id, setting.key, if (secret) text.trim() else text)
                    editing = null
                }) { Text(stringResource(CommonR.string.common_ok)) }
            },
            dismissButton = {
                if (!secret || setting.key in savedSecrets) {
                    TextButton({
                        store.setSettingValue(plugin.id, setting.key, null)
                        editing = null
                    }) {
                        Text(stringResource(if (secret) R.string.plugins_setting_secret_remove else R.string.plugins_setting_default_reset))
                    }
                }
            },
        )
    }
}

private const val PLUGIN_SETTING_MAX = 512

/** Log lines shown before the list is more scrolling than reading. */
private const val LOG_LINES = 40

/**
 * The reminders a plugin has set and not yet shown, each with its time, and a
 * way to cancel them all. A notification from a plugin opens this page, so it
 * is also where the user stops one that has become a nuisance.
 *
 * Says so when the app may not post notifications, since that is the one thing
 * here the keyboard cannot fix by itself: an input method has no screen to ask
 * for the permission from.
 */
@Composable
private fun PluginRemindersGroup(reminders: PluginReminders) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var canPost by remember { mutableStateOf(WmNotifications.canPost(context)) }
    val pending = remember(refresh) { reminders.list() }
    val askPermission = rememberNotificationPermissionRequest { canPost = WmNotifications.canPost(context) }
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }

    SettingsGroup(
        stringResource(R.string.plugins_reminders_title),
        info = stringResource(R.string.plugins_reminders_info),
    ) {
        if (!canPost) {
            item {
                WmRow(
                    title = stringResource(R.string.plugins_reminders_blocked_title),
                    subtitle = stringResource(R.string.plugins_reminders_blocked_subtitle),
                    onClick = askPermission,
                )
            }
        }
        if (pending.isEmpty()) {
            item { CaptionText(stringResource(R.string.plugins_reminders_empty)) }
        } else {
            for (reminder in pending) {
                item(key = "reminder:${reminder.id}") {
                    WmRow(title = reminder.text, subtitle = format.format(java.util.Date(reminder.at)))
                }
            }
            item {
                TextButton(onClick = { reminders.clear(); refresh++ }) {
                    Text(stringResource(R.string.plugins_reminders_cancel_all_action))
                }
            }
        }
    }
}

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
                val permissionLabels = ok.permissions.filter { !it.isRisky }.map { context.getString(it.labelRes) }
                val risky = ok.permissions.filter { it.isRisky }
                buildString {
                    append(ok.manifest.description.ifBlank { defaultDescription })
                    append("\n\n")
                    append(versionLine)
                    append("\n\n")
                    if (permissionLabels.isEmpty() && risky.isEmpty()) {
                        append(noPermissions)
                    } else if (permissionLabels.isNotEmpty()) {
                        append(permissionsIntro)
                        for (label in permissionLabels) append("\n• $label")
                    }
                    if (risky.isNotEmpty()) {
                        append("\n\n")
                        append(context.getString(R.string.plugins_install_risky_intro))
                        for (permission in risky) {
                            append("\n⚠ ").append(context.getString(permission.labelRes))
                            permission.warningRes?.let { append("\n   ").append(context.getString(it)) }
                        }
                        if (PluginPermission.Network in risky && ok.manifest.hosts.isNotEmpty()) {
                            append("\n   ").append(ok.manifest.hosts.joinToString(", "))
                        }
                        pluginCombinationWarning(context, ok.permissions, ok.manifest.hosts)?.let { append("\n\n").append(it) }
                    }
                    append(pluginActionsLine(context, ok.manifest.actions.map { it.name }))
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
                        if (result is PluginImportResult.Imported && !result.replaced && result.plugin.panel && result.plugin.tool.tile) {
                            SettingsRepository(context).offerPluginTool(result.plugin.id)
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
