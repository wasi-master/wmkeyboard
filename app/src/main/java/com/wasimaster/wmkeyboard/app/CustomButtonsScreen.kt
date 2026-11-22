package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.PluginToolPlace
import com.wasimaster.wmkeyboard.core.settings.pluginIds
import com.wasimaster.wmkeyboard.ime.ui.PluginIcon
import com.wasimaster.wmkeyboard.ime.ui.toolColor
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
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
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bookmarks
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.displayDescription
import com.wasimaster.wmkeyboard.core.plugins.displayName
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ToggleOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Window
import com.wasimaster.wmkeyboard.core.settings.CUSTOM_SHELL_ACTIVITY_PREFIX
import com.wasimaster.wmkeyboard.core.shell.ShellAccess
import com.wasimaster.wmkeyboard.core.shell.ShellFeatures
import com.wasimaster.wmkeyboard.core.settings.CUSTOM_BUTTON_LABEL_MAX
import com.wasimaster.wmkeyboard.core.settings.CustomButton
import com.wasimaster.wmkeyboard.core.settings.CustomButtonKind
import com.wasimaster.wmkeyboard.core.settings.CustomPage
import com.wasimaster.wmkeyboard.core.settings.CustomToggle
import com.wasimaster.wmkeyboard.core.settings.MAX_CUSTOM_BUTTONS
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.customToolKey
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

private enum class CustomPick { SHORTCUT, SCREEN, SETTING, PAGE, PLUGIN }

/**
 * The user's own buttons (#1213, #1236): another app's shortcut, a switch
 * over one of the keyboard's settings, a jump to a settings page, or a
 * plugin's panel. Each is a tool (#1345): on the toolbar or in the toolbox,
 * dragged between the two and around them like any other, and the floating
 * button can run any of them. A press on a button renames it and pins it to
 * the toolbar or puts it in the toolbox.
 */
@Composable
internal fun CustomButtonsScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val buttons = settings.watch { it.toolbox.customButtons }
    // Where each button is lives in the tool lists, not on the button (#1345).
    val pinned = settings.watch { s -> s.toolbarTools.toSet() }
    SideEffect { CustomButtonFaces.buttons = buttons }
    var picking by rememberSaveable { mutableStateOf<CustomPick?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val full = buttons.size >= MAX_CUSTOM_BUTTONS
    // The plugins already tools, pinned or in the toolbox, which the picker leaves out.
    val toolPlugins = settings.watch { (it.toolbarTools + it.toolboxOrder).pluginIds().toSet() }

    fun add(kind: CustomButtonKind, target: String, label: String = "", pkg: String = "", icon: android.graphics.drawable.Drawable? = null) {
        scope.launch {
            // The picture is on disk before the button is in the list (#1489).
            // Written after, the keyboard and this page could read the new
            // button first, find no file, fall back to the app's own icon, and
            // keep that one: a picture is loaded once per button, not again
            // when its file turns up. So it is drawn to a file of its own
            // first and moved under the button's id in the same write that
            // adds the button, once the id is known.
            val staged = if (kind == CustomButtonKind.SHORTCUT) {
                withContext(Dispatchers.IO) { CustomShortcutSources.stageIcon(context, icon) }
            } else {
                null
            }
            var added = false
            repository.updateCustomButtons { current ->
                if (current.size >= MAX_CUSTOM_BUTTONS) return@updateCustomButtons current
                val id = current.nextCustomButtonId()
                // Moved, or for a shortcut with no picture cleared: an id is
                // reused, and a deleted button's picture must not come back.
                if (kind == CustomButtonKind.SHORTCUT) CustomShortcutSources.placeIcon(context, staged, id)
                added = true
                current + CustomButton(id = id, kind = kind, target = target, label = label, packageName = pkg)
            }
            // Not added (the list filled up meanwhile): no picture left behind.
            if (!added && staged != null) withContext(Dispatchers.IO) { runCatching { staged.delete() } }
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
            for (button in buttons) {
                item(key = button.id) {
                    CustomButtonRow(
                        button = button,
                        onToolbar = customToolKey(button.id) in pinned,
                        onEdit = { editing = button.id },
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
        // Any screen of an app, as an activity launcher opens it (#1405).
        item {
            NavRow(
                title = R.string.custombuttons_add_screen_title,
                subtitle = stringResource(
                    if (full) R.string.custombuttons_full else R.string.custombuttons_add_screen_subtitle,
                    MAX_CUSTOM_BUTTONS,
                ),
                icon = Icons.Outlined.Window,
            ) { if (!full) picking = CustomPick.SCREEN }
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
        // A plugin is a tool of its own now, not a button: picked here, it is
        // pinned to the toolbar, and counts against nothing.
        item {
            NavRow(
                title = R.string.custombuttons_add_plugin_title,
                subtitle = stringResource(R.string.custombuttons_add_plugin_subtitle),
                icon = Icons.Outlined.Extension,
            ) { picking = CustomPick.PLUGIN }
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
            onMore = { picking = CustomPick.SCREEN },
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
        CustomPick.SCREEN -> AppScreenPicker(
            useRoot = settings.watch { it.launcher.useRoot },
            onDismiss = { picking = null },
            onSetUp = {
                picking = null
                onNavigate("permissions")
            },
            onPick = { offer ->
                picking = null
                scope.launch {
                    val icon = withContext(Dispatchers.IO) { runCatching { offer.icon() }.getOrNull() }
                    add(CustomButtonKind.SHORTCUT, offer.target, offer.label, offer.packageName, icon)
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
            taken = toolPlugins,
            onDismiss = { picking = null },
        ) { plugin ->
            picking = null
            scope.launch { repository.setPluginToolPlace(plugin, PluginToolPlace.TOOLBAR) }
        }
        null -> Unit
    }

    val edited = buttons.firstOrNull { it.id == editing }
    if (edited != null) {
        val wasOnToolbar = customToolKey(edited.id) in pinned
        EditCustomButtonDialog(
            button = edited,
            onToolbar = wasOnToolbar,
            onDismiss = { editing = null },
            onSave = { label, onToolbar, icon ->
                editing = null
                scope.launch {
                    repository.updateCustomButtons { list ->
                        list.map { if (it.id == edited.id) it.copy(label = label, icon = icon) else it }
                    }
                    if (onToolbar != wasOnToolbar) repository.setCustomButtonOnToolbar(edited.id, onToolbar)
                }
            },
        )
    }
}

@Composable
private fun CustomButtonRow(
    button: CustomButton,
    onToolbar: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val name = remember(button) { CustomButtonFaces.name(context, button) }
    val kind = stringResource(
        when (button.kind) {
            CustomButtonKind.SHORTCUT -> if (button.target.startsWith(CUSTOM_SHELL_ACTIVITY_PREFIX)) {
                R.string.custombuttons_kind_screen
            } else {
                R.string.custombuttons_kind_shortcut
            }
            CustomButtonKind.SETTING -> R.string.custombuttons_kind_setting
            CustomButtonKind.PAGE -> R.string.custombuttons_kind_page
            CustomButtonKind.PLUGIN -> R.string.custombuttons_kind_plugin
        },
    )
    val place = stringResource(
        if (onToolbar) R.string.custombuttons_on_toolbar else R.string.custombuttons_in_toolbox,
    )
    WmRow(
        title = name,
        subtitle = stringResource(R.string.custombuttons_row_subtitle, kind, place) +
            if (!button.usable) "\n" + stringResource(R.string.custombuttons_unusable) else "",
        swipeActions = listOf(deleteSwipeAction(stringResource(CommonR.string.common_delete), onDelete)),
        leading = { CustomButtonGlyph(button) },
        trailing = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(CommonR.string.common_delete))
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
 * manifests, and a banner at the top says so, with a way on to any app's
 * screens ([onMore], #1405). The rows add a button: no radio buttons (#1407).
 */
@Composable
private fun ShortcutPicker(
    onDismiss: () -> Unit,
    onPick: (ShortcutOffer) -> Unit,
    onMore: () -> Unit,
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
                            leading = { AppIconOf(maker.packageName) },
                            onPick = { onMaker(maker) },
                        )
                    },
                ),
            )
        }
    }
    val limitedTitle = stringResource(R.string.custombuttons_shortcut_limited_title)
    val limitedBody = stringResource(R.string.custombuttons_shortcut_limited_body)
    val more = stringResource(R.string.custombuttons_shortcut_more_action)
    ActionPickerPage(
        memoryKey = "custom-shortcut",
        title = stringResource(R.string.custombuttons_add_shortcut_title),
        groups = groups,
        onDismiss = onDismiss,
        searchHint = stringResource(R.string.custombuttons_shortcut_search_hint),
        radios = false,
        emptyText = stringResource(
            if (loaded == null) R.string.custombuttons_loading else R.string.custombuttons_shortcut_empty,
        ),
        banner = if (loaded != null && !canRead) {
            { StateBanner("$limitedTitle\n$limitedBody", action = more, onAction = onMore) }
        } else {
            null
        },
    )
}

/** An installed app's own icon, the shortcut glyph while it loads or when it has none. */
@Composable
private fun AppIconOf(packageName: String) {
    val context = LocalContext.current
    val px = (32 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            com.wasimaster.wmkeyboard.ime.floating.FloatingAppIcons.icon(context, packageName, px)
        }
    }
    val shown = icon
    if (shown != null) {
        Image(shown, contentDescription = null, modifier = Modifier.size(32.dp))
    } else {
        Icon(Icons.Outlined.AppShortcut, contentDescription = null)
    }
}

/**
 * Every screen of every app the keyboard can see, grouped by app and folded
 * until opened, the way an activity launcher lists them (#1405). Listed are
 * the screens any app may open; with Settings › Permissions › Shizuku or root
 * on and a door open, also those that ask for a permission (Shizuku's shell
 * holds most), and with root, those an app keeps to itself. Those open through
 * the shell's `am start`, so the button stores an `am:` target.
 */
@Composable
private fun AppScreenPicker(
    useRoot: Boolean,
    onDismiss: () -> Unit,
    onSetUp: () -> Unit,
    onPick: (ShortcutOffer) -> Unit,
) {
    val context = LocalContext.current
    val shellOn = remember { ShellFeatures.isEnabled(context) }
    LaunchedEffect(Unit) { ShellAccess.init(context) }
    SideEffect { ShellAccess.rootAllowed = useRoot }
    val shizuku by ShellAccess.shizukuGranted.collectAsState()
    val root = shellOn && useRoot && ShellAccess.suPresent
    val shell = shellOn && (shizuku || root)
    val loaded by produceState<List<AppScreens>?>(null, shell, root) {
        value = withContext(Dispatchers.IO) { CustomShortcutSources.appScreens(context, shell = shell, root = root) }
    }
    val needsShell = stringResource(R.string.custombuttons_screen_needs_shell)
    val groups = loaded.orEmpty().map { app ->
        PickerGroup(
            "screens-${app.packageName}",
            app.appLabel,
            app.screens.map { screen ->
                PickerRow(
                    key = screen.className,
                    title = screen.label,
                    subtitle = screen.className + if (screen.viaShell) "\n" + needsShell else "",
                    leading = { AppIconOf(app.packageName) },
                    onPick = { onPick(CustomShortcutSources.screenOffer(context, app, screen)) },
                )
            },
        )
    }
    ActionPickerPage(
        memoryKey = "custom-screen",
        title = stringResource(R.string.custombuttons_add_screen_title),
        groups = groups,
        onDismiss = onDismiss,
        searchHint = stringResource(R.string.custombuttons_screen_search_hint),
        radios = false,
        foldedAtFirst = true,
        emptyText = stringResource(
            if (loaded == null) R.string.custombuttons_screen_loading else R.string.custombuttons_screen_empty,
        ),
        banner = when {
            !ShellFeatures.offered || root -> null
            !shell -> {
                {
                    StateBanner(
                        stringResource(R.string.custombuttons_screen_shell_banner),
                        action = stringResource(R.string.custombuttons_screen_shell_action),
                        onAction = onSetUp,
                    )
                }
            }
            ShellAccess.suPresent -> {
                {
                    StateBanner(
                        stringResource(R.string.custombuttons_screen_root_banner),
                        action = stringResource(R.string.custombuttons_screen_shell_action),
                        onAction = onSetUp,
                    )
                }
            }
            else -> null
        },
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
                rows,
            ),
        ),
        onDismiss = onDismiss,
        radios = false,
        emptyText = stringResource(R.string.custombuttons_all_added),
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
                rows,
            ),
        ),
        onDismiss = onDismiss,
        radios = false,
        emptyText = stringResource(R.string.custombuttons_all_added),
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
            title = plugin.displayName,
            subtitle = plugin.displayDescription.takeIf { it.isNotBlank() },
            leading = { PluginIcon(plugin, tint = plugin.toolColor ?: androidx.compose.material3.LocalContentColor.current) },
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
                rows,
            ),
        ),
        onDismiss = onDismiss,
        radios = false,
        emptyText = stringResource(R.string.custombuttons_no_plugins),
    )
}

@Composable
private fun EditCustomButtonDialog(
    button: CustomButton,
    /** Whether the button is pinned to the toolbar now, by the tool lists. */
    onToolbar: Boolean,
    onDismiss: () -> Unit,
    onSave: (label: String, onToolbar: Boolean, icon: String) -> Unit,
) {
    val context = LocalContext.current
    var label by rememberSaveable(button.id) { mutableStateOf(button.label) }
    var pinned by rememberSaveable(button.id) { mutableStateOf(onToolbar) }
    // An icon out of an installed icon pack (#1375), or blank for its own.
    var icon by rememberSaveable(button.id) { mutableStateOf(button.icon) }
    var choosingIcon by remember { mutableStateOf(false) }
    val packs = rememberSystemIconPacks()
    if (choosingIcon) {
        AlertDialog(
            onDismissRequest = { choosingIcon = false },
            title = { Text(stringResource(R.string.custombuttons_icon_picker_title)) },
            text = {
                if (packs.isEmpty()) {
                    Text(stringResource(R.string.custombuttons_icon_no_packs))
                } else {
                    SystemPackIconGrid(
                        packs = packs,
                        selected = icon.ifEmpty { null },
                        onPick = { source ->
                            icon = source
                            choosingIcon = false
                        },
                        // A shortcut's own app first, where the pack themes it.
                        leadFor = button.packageName.ifEmpty { null },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { choosingIcon = false }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
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
                                if (pinned) R.string.custombuttons_edit_toolbar_on else R.string.custombuttons_edit_toolbar_off,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = pinned, onCheckedChange = { pinned = it })
                }
                // Its icon, on the toolbar, in the toolbox and on the floating button (#1375).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.custombuttons_edit_icon))
                        Text(
                            if (icon.isEmpty()) {
                                stringResource(R.string.custombuttons_edit_icon_own)
                            } else {
                                systemPackLabel(packs, icon)
                                    ?.let { stringResource(R.string.custombuttons_edit_icon_from_pack, it) }
                                    ?: stringResource(R.string.custombuttons_edit_icon_own)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (icon.isNotEmpty()) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            ResolvedGlyph(rememberSystemPackIcon(icon))
                        }
                        TextButton(onClick = { icon = "" }) { Text(stringResource(R.string.custombuttons_edit_icon_reset)) }
                    }
                    TextButton(onClick = { choosingIcon = true }) {
                        Text(stringResource(R.string.custombuttons_edit_icon_choose))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(label.trim(), pinned, icon) }) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

