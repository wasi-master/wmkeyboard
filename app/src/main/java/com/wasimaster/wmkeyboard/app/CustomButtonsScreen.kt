package com.wasimaster.wmkeyboard.app

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AppShortcut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDownward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowUpward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bookmarks
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ToggleOn
import com.wasimaster.wmkeyboard.core.settings.CUSTOM_BUTTON_LABEL_MAX
import com.wasimaster.wmkeyboard.core.settings.CustomButton
import com.wasimaster.wmkeyboard.core.settings.CustomButtonKind
import com.wasimaster.wmkeyboard.core.settings.CustomPage
import com.wasimaster.wmkeyboard.core.settings.CustomToggle
import com.wasimaster.wmkeyboard.core.settings.MAX_CUSTOM_BUTTONS
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.moved
import com.wasimaster.wmkeyboard.core.settings.nextCustomButtonId
import com.wasimaster.wmkeyboard.ime.ui.CustomButtonFaces
import com.wasimaster.wmkeyboard.ime.ui.customPageIcon
import com.wasimaster.wmkeyboard.ime.ui.customPageLabelRes
import com.wasimaster.wmkeyboard.ime.ui.customToggleIcon
import com.wasimaster.wmkeyboard.ime.ui.customToggleLabelRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Route of the custom buttons page (#1213, #1236). */
object CustomButtons {
    const val ROUTE = "custom_buttons"
}

private enum class CustomPick { SHORTCUT, SETTING, PAGE, PLUGIN }

/**
 * The user's own buttons (#1213, #1236): another app's shortcut, a switch
 * over one of the keyboard's settings, a jump to a settings page, or a
 * plugin's panel. Each
 * sits on the toolbar or in the toolbox, and the floating button can run any
 * of them. A press on a button renames it and moves it between the two.
 */
@Composable
internal fun CustomButtonsScreen(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val buttons = settings.watch { it.toolbox.customButtons }
    SideEffect { CustomButtonFaces.buttons = buttons }
    var picking by rememberSaveable { mutableStateOf<CustomPick?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val full = buttons.size >= MAX_CUSTOM_BUTTONS

    fun add(kind: CustomButtonKind, target: String, label: String = "", pkg: String = "", icon: android.graphics.drawable.Drawable? = null) {
        scope.launch {
            var made: String? = null
            repository.updateCustomButtons { current ->
                if (current.size >= MAX_CUSTOM_BUTTONS) return@updateCustomButtons current
                val id = current.nextCustomButtonId()
                made = id
                current + CustomButton(id = id, kind = kind, target = target, label = label, packageName = pkg)
            }
            val id = made
            if (id != null && kind == CustomButtonKind.SHORTCUT) {
                withContext(Dispatchers.IO) { CustomShortcutSources.saveIcon(context, id, icon) }
            }
        }
    }

    // An app's "create shortcut" screen hands its shortcut back here.
    var pendingMaker by remember { mutableStateOf<ShortcutMaker?>(null) }
    val makerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val maker = pendingMaker ?: return@rememberLauncherForActivityResult
        pendingMaker = null
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val made = CustomShortcutSources.fromMakerResult(context, result.data, maker)
        if (made == null) {
            Toast.makeText(context, R.string.custombuttons_maker_unsupported, Toast.LENGTH_LONG).show()
        } else {
            add(CustomButtonKind.SHORTCUT, made.target, made.label, made.packageName, made.icon)
        }
    }

    Spacer(Modifier.height(12.dp))
    CaptionText(stringResource(R.string.custombuttons_caption))
    if (buttons.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.custombuttons_yours_title)) {
            buttons.forEachIndexed { index, button ->
                item(key = button.id) {
                    CustomButtonRow(
                        button = button,
                        canMoveUp = index > 0,
                        canMoveDown = index < buttons.lastIndex,
                        onEdit = { editing = button.id },
                        onMove = { by -> scope.launch { repository.updateCustomButtons { it.moved(button.id, by) } } },
                        onDelete = {
                            scope.launch {
                                repository.updateCustomButtons { list -> list.filterNot { it.id == button.id } }
                                withContext(Dispatchers.IO) { CustomShortcutSources.deleteIcon(context, button.id) }
                            }
                        },
                    )
                }
            }
        }
    }
    SettingsGroup(stringResource(R.string.custombuttons_add_title)) {
        item {
            NavRow(
                title = R.string.custombuttons_add_shortcut_title,
                subtitle = stringResource(
                    if (full) R.string.custombuttons_full else R.string.custombuttons_add_shortcut_subtitle,
                    MAX_CUSTOM_BUTTONS,
                ),
                icon = Icons.Outlined.AppShortcut,
            ) { if (!full) picking = CustomPick.SHORTCUT }
        }
        item {
            NavRow(
                title = R.string.custombuttons_add_setting_title,
                subtitle = stringResource(
                    if (full) R.string.custombuttons_full else R.string.custombuttons_add_setting_subtitle,
                    MAX_CUSTOM_BUTTONS,
                ),
                icon = Icons.Outlined.ToggleOn,
            ) { if (!full) picking = CustomPick.SETTING }
        }
        item {
            NavRow(
                title = R.string.custombuttons_add_page_title,
                subtitle = stringResource(
                    if (full) R.string.custombuttons_full else R.string.custombuttons_add_page_subtitle,
                    MAX_CUSTOM_BUTTONS,
                ),
                icon = Icons.Outlined.Bookmarks,
            ) { if (!full) picking = CustomPick.PAGE }
        }
        item {
            NavRow(
                title = R.string.custombuttons_add_plugin_title,
                subtitle = stringResource(
                    if (full) R.string.custombuttons_full else R.string.custombuttons_add_plugin_subtitle,
                    MAX_CUSTOM_BUTTONS,
                ),
                icon = Icons.Outlined.Extension,
            ) { if (!full) picking = CustomPick.PLUGIN }
        }
    }

    when (picking) {
        CustomPick.SHORTCUT -> ShortcutPicker(
            onDismiss = { picking = null },
            onPick = { offer ->
                picking = null
                scope.launch {
                    val icon = withContext(Dispatchers.IO) { runCatching { offer.icon() }.getOrNull() }
                    add(CustomButtonKind.SHORTCUT, offer.target, offer.label, offer.packageName, icon)
                }
            },
            onMaker = { maker ->
                picking = null
                pendingMaker = maker
                runCatching { makerLauncher.launch(CustomShortcutSources.makerIntent(maker)) }
                    .onFailure {
                        pendingMaker = null
                        Toast.makeText(context, R.string.custombuttons_maker_unsupported, Toast.LENGTH_LONG).show()
                    }
            },
        )
        CustomPick.SETTING -> TogglePicker(
            taken = buttons.mapNotNullTo(HashSet()) { it.toggle },
            onDismiss = { picking = null },
        ) { toggle ->
            picking = null
            add(CustomButtonKind.SETTING, toggle.name)
        }
        CustomPick.PAGE -> PagePicker(
            taken = buttons.mapNotNullTo(HashSet()) { it.page },
            onDismiss = { picking = null },
        ) { page ->
            picking = null
            add(CustomButtonKind.PAGE, page.name)
        }
        CustomPick.PLUGIN -> PluginTilePicker(
            taken = buttons.filter { it.kind == CustomButtonKind.PLUGIN }.mapTo(HashSet()) { it.target },
            onDismiss = { picking = null },
        ) { plugin ->
            picking = null
            add(CustomButtonKind.PLUGIN, plugin)
        }
        null -> Unit
    }

    val edited = buttons.firstOrNull { it.id == editing }
    if (edited != null) {
        EditCustomButtonDialog(
            button = edited,
            onDismiss = { editing = null },
            onSave = { label, onToolbar ->
                editing = null
                scope.launch {
                    repository.updateCustomButtons { list ->
                        list.map { if (it.id == edited.id) it.copy(label = label, onToolbar = onToolbar) else it }
                    }
                }
            },
        )
    }
}

@Composable
private fun CustomButtonRow(
    button: CustomButton,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val name = remember(button) { CustomButtonFaces.name(context, button) }
    val kind = stringResource(
        when (button.kind) {
            CustomButtonKind.SHORTCUT -> R.string.custombuttons_kind_shortcut
            CustomButtonKind.SETTING -> R.string.custombuttons_kind_setting
            CustomButtonKind.PAGE -> R.string.custombuttons_kind_page
            CustomButtonKind.PLUGIN -> R.string.custombuttons_kind_plugin
        },
    )
    val place = stringResource(
        if (button.onToolbar) R.string.custombuttons_on_toolbar else R.string.custombuttons_in_toolbox,
    )
    WmRow(
        title = name,
        subtitle = stringResource(R.string.custombuttons_row_subtitle, kind, place) +
            if (!button.usable) "\n" + stringResource(R.string.custombuttons_unusable) else "",
        swipeActions = listOf(deleteSwipeAction(stringResource(CommonR.string.common_delete), onDelete)),
        leading = { CustomButtonGlyph(button) },
        trailing = {
            Row {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.custombuttons_move_up))
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = stringResource(R.string.custombuttons_move_down))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(CommonR.string.common_delete))
                }
            }
        },
        onClick = onEdit,
    )
}

/** A button's picture in a list: a shortcut's own, else the glyph the keyboard draws. */
@Composable
private fun CustomButtonGlyph(button: CustomButton) {
    val context = LocalContext.current
    val px = (32 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    val picture by produceState<ImageBitmap?>(null, button, px) {
        value = withContext(Dispatchers.IO) { CustomButtonFaces.icon(context, button, px) }
    }
    val shown = picture
    if (shown != null) {
        Image(shown, contentDescription = null, modifier = Modifier.size(32.dp))
        return
    }
    Icon(
        when (button.kind) {
            CustomButtonKind.SETTING -> button.toggle?.let(::customToggleIcon) ?: Icons.Outlined.ToggleOn
            CustomButtonKind.PAGE -> button.page?.let(::customPageIcon) ?: Icons.Outlined.Bookmarks
            CustomButtonKind.PLUGIN -> Icons.Outlined.Extension
            CustomButtonKind.SHORTCUT -> Icons.Outlined.AppShortcut
        },
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(28.dp),
    )
}

/**
 * Every app's shortcuts the keyboard can offer, grouped by app, then the apps
 * that make a shortcut on request. Where Android does not let the keyboard
 * read shortcuts, the first list holds only what apps declare in their
 * manifests, and a row at the top says so.
 */
@Composable
private fun ShortcutPicker(
    onDismiss: () -> Unit,
    onPick: (ShortcutOffer) -> Unit,
    onMaker: (ShortcutMaker) -> Unit,
) {
    val context = LocalContext.current
    val loaded by produceState<Triple<Boolean, List<ShortcutOffer>, List<ShortcutMaker>>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            Triple(
                CustomShortcutSources.canReadShortcuts(context),
                CustomShortcutSources.offers(context),
                CustomShortcutSources.makers(context),
            )
        }
    }
    val (canRead, offers, makers) = loaded ?: Triple(true, emptyList(), emptyList())
    val groups = buildList {
        if (loaded == null) {
            add(PickerGroup("loading", null, listOf(PickerRow("loading", stringResource(R.string.custombuttons_loading), onPick = {}))))
            return@buildList
        }
        if (!canRead) {
            add(
                PickerGroup(
                    "note",
                    null,
                    listOf(
                        PickerRow(
                            "note",
                            stringResource(R.string.custombuttons_shortcut_limited_title),
                            subtitle = stringResource(R.string.custombuttons_shortcut_limited_body),
                            onPick = {},
                        ),
                    ),
                ),
            )
        }
        for ((app, list) in offers.groupBy { it.appLabel }) {
            add(
                PickerGroup(
                    "app-${list.first().packageName}",
                    app,
                    list.map { offer ->
                        PickerRow(
                            key = offer.key,
                            title = offer.label,
                            leading = { ShortcutOfferIcon(offer) },
                            onPick = { onPick(offer) },
                        )
                    },
                ),
            )
        }
        if (makers.isNotEmpty()) {
            add(
                PickerGroup(
                    "makers",
                    stringResource(R.string.custombuttons_makers_group),
                    makers.map { maker ->
                        PickerRow(
                            key = "maker-${maker.packageName}/${maker.activity}",
                            title = maker.label,
                            subtitle = maker.appLabel.takeIf { it != maker.label },
                            onPick = { onMaker(maker) },
                        )
                    },
                ),
            )
        }
        if (offers.isEmpty() && makers.isEmpty()) {
            add(PickerGroup("empty", null, listOf(PickerRow("empty", stringResource(R.string.custombuttons_shortcut_empty), onPick = onDismiss))))
        }
    }
    ActionPickerPage(
        memoryKey = "custom-shortcut",
        title = stringResource(R.string.custombuttons_add_shortcut_title),
        groups = groups,
        onDismiss = onDismiss,
    )
}

@Composable
private fun ShortcutOfferIcon(offer: ShortcutOffer) {
    val context = LocalContext.current
    val px = (32 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    val icon by produceState<ImageBitmap?>(null, offer.key) {
        value = withContext(Dispatchers.IO) {
            runCatching { offer.icon()?.toBitmap(px, px)?.asImageBitmap() }.getOrNull()
                ?: com.wasimaster.wmkeyboard.ime.floating.FloatingAppIcons.icon(context, offer.packageName, px)
        }
    }
    val shown = icon
    if (shown != null) {
        Image(shown, contentDescription = null, modifier = Modifier.size(32.dp))
    } else {
        Icon(Icons.Outlined.AppShortcut, contentDescription = null)
    }
}

@Composable
private fun TogglePicker(taken: Set<CustomToggle>, onDismiss: () -> Unit, onPick: (CustomToggle) -> Unit) {
    val rows = CustomToggle.entries.filter { it !in taken }.map { toggle ->
        PickerRow(
            key = toggle.name,
            title = stringResource(customToggleLabelRes(toggle)),
            subtitle = stringResource(R.string.custombuttons_setting_row_subtitle),
            leading = { Icon(customToggleIcon(toggle), contentDescription = null) },
            onPick = { onPick(toggle) },
        )
    }
    ActionPickerPage(
        memoryKey = "custom-setting",
        title = stringResource(R.string.custombuttons_add_setting_title),
        groups = listOf(
            PickerGroup(
                "settings",
                null,
                rows.ifEmpty { listOf(PickerRow("none", stringResource(R.string.custombuttons_all_added), onPick = onDismiss)) },
            ),
        ),
        onDismiss = onDismiss,
    )
}

@Composable
private fun PagePicker(taken: Set<CustomPage>, onDismiss: () -> Unit, onPick: (CustomPage) -> Unit) {
    val rows = CustomPage.entries.filter { it !in taken }.map { page ->
        PickerRow(
            key = page.name,
            title = stringResource(customPageLabelRes(page)),
            subtitle = if (page == CustomPage.FAVOURITES) stringResource(R.string.custombuttons_page_favourites_subtitle) else null,
            leading = { Icon(customPageIcon(page), contentDescription = null) },
            onPick = { onPick(page) },
        )
    }
    ActionPickerPage(
        memoryKey = "custom-page",
        title = stringResource(R.string.custombuttons_add_page_title),
        groups = listOf(
            PickerGroup(
                "pages",
                null,
                rows.ifEmpty { listOf(PickerRow("none", stringResource(R.string.custombuttons_all_added), onPick = onDismiss)) },
            ),
        ),
        onDismiss = onDismiss,
    )
}

/**
 * Plugins that can open a panel. Those whose manifest asks for a tile come
 * first; any other panel plugin can have one too, since the tile only opens
 * what the Plugins panel already lists.
 */
@Composable
private fun PluginTilePicker(taken: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { PluginStore.get(context) }
    val plugins = remember(taken) {
        (if (store.subsystemEnabled()) store.panelPlugins() else emptyList())
            .filter { it.id !in taken }.sortedByDescending { it.tool.tile }
    }
    val rows = plugins.map { plugin ->
        PickerRow(
            key = plugin.id,
            title = plugin.name,
            subtitle = plugin.description.takeIf { it.isNotBlank() },
            leading = { Icon(Icons.Outlined.Extension, contentDescription = null) },
            onPick = { onPick(plugin.id) },
        )
    }
    ActionPickerPage(
        memoryKey = "custom-plugin",
        title = stringResource(R.string.custombuttons_add_plugin_title),
        groups = listOf(
            PickerGroup(
                "plugins",
                null,
                rows.ifEmpty { listOf(PickerRow("none", stringResource(R.string.custombuttons_no_plugins), onPick = onDismiss)) },
            ),
        ),
        onDismiss = onDismiss,
    )
}

@Composable
private fun EditCustomButtonDialog(
    button: CustomButton,
    onDismiss: () -> Unit,
    onSave: (label: String, onToolbar: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var label by rememberSaveable(button.id) { mutableStateOf(button.label) }
    var onToolbar by rememberSaveable(button.id) { mutableStateOf(button.onToolbar) }
    val fallback = remember(button) { CustomButtonFaces.name(context, button.copy(label = "")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custombuttons_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(CUSTOM_BUTTON_LABEL_MAX) },
                    label = { Text(stringResource(R.string.custombuttons_edit_name)) },
                    placeholder = { Text(fallback) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.custombuttons_edit_toolbar))
                        Text(
                            stringResource(
                                if (onToolbar) R.string.custombuttons_edit_toolbar_on else R.string.custombuttons_edit_toolbar_off,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = onToolbar, onCheckedChange = { onToolbar = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(label.trim(), onToolbar) }) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

