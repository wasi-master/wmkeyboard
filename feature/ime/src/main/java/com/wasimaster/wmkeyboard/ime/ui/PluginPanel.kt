package com.wasimaster.wmkeyboard.ime.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Handyman
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PowerSettingsNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Widgets
import com.wasimaster.wmkeyboard.core.settings.PluginToolPlace
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.pluginToolPlaceIn
import com.wasimaster.wmkeyboard.core.ui.SwipeAction
import com.wasimaster.wmkeyboard.core.ui.SwipeTone
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.plugins.InstalledPlugin
import com.wasimaster.wmkeyboard.core.plugins.PluginEvent
import com.wasimaster.wmkeyboard.core.plugins.PluginPermission
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.PluginWidget
import com.wasimaster.wmkeyboard.core.plugins.PythonSession
import com.wasimaster.wmkeyboard.core.plugins.displayDescription
import com.wasimaster.wmkeyboard.core.plugins.displayName
import com.wasimaster.wmkeyboard.core.plugins.resolve
import com.wasimaster.wmkeyboard.core.plugins.ui.LocalPluginPanelStyle
import com.wasimaster.wmkeyboard.core.plugins.ui.PluginInputHost
import com.wasimaster.wmkeyboard.core.plugins.ui.PluginOverlayBox
import com.wasimaster.wmkeyboard.core.plugins.ui.PluginPanelStyle
import com.wasimaster.wmkeyboard.core.plugins.ui.PluginWidgetList
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.PluginPanelUi
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.send.QrImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Plugins panel: the installed list, and whichever plugin is running.
 *
 * Everything a plugin draws is drawn *here*, by the keyboard, from a plain data
 * description the script handed back. The script never gets a Compose handle, a
 * colour, or a pixel — which is why a plugin cannot make itself look like the
 * keyboard's own UI, cannot draw outside its panel, and cannot cover a system
 * prompt. The header above it always says which plugin is running.
 *
 * Text inputs are the delicate part. There are no `TextField`s anywhere in the
 * IME (they fight the `InputConnection`), so a plugin's input is a tap target
 * that, once focused, makes the keyboard route keystrokes into
 * [KeyboardUiState.pluginInputs] instead of the user's app. The service owns
 * that switch and clears it whenever the panel closes.
 */
@Composable
internal fun PluginPanel(
    state: KeyboardUiState,
    onOpenPlugin: (String) -> Unit,
    onEvent: (PluginEvent) -> Unit,
    onInputFocus: (String?) -> Unit,
    onToolInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    onPaste: (String) -> Unit,
    /** Opens a settings route: `plugins` for Manage, `plugin/<id>` for one plugin's page. */
    onOpenRoute: (String) -> Unit,
) {
    when (val panel = state.plugins) {
        is PluginPanelUi.List -> PluginList(state, panel, onOpenPlugin, onOpenRoute)
        is PluginPanelUi.Running -> RunningPlugin(
            state = state,
            panel = panel,
            onEvent = onEvent,
            onInputFocus = onInputFocus,
            onToolInsert = onToolInsert,
            onCopy = onCopy,
            onPaste = onPaste,
        )
    }
}

@Composable
private fun PluginList(
    state: KeyboardUiState,
    panel: PluginPanelUi.List,
    onOpenPlugin: (String) -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val onManage = { onOpenRoute("plugins") }
    val context = LocalContext.current
    val store = remember(context) { PluginStore.get(context) }
    val repository = remember(context) { SettingsRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // Turned off from here: gone at once, though the service built the list
    // before. The next time the panel opens, the store leaves it out itself.
    var hidden by remember(panel) { mutableStateOf(emptySet<String>()) }
    // The one row whose options are showing, or null.
    var expanded by remember(panel) { mutableStateOf<String?>(null) }
    // A plugin is a tool like Calculator: pinned, in the toolbox grid, or
    // neither (its panel is still here).
    fun place(pluginId: String, where: PluginToolPlace) {
        scope.launch { repository.setPluginToolPlace(pluginId, where) }
    }
    // The ring walks the rows and ends on Manage.
    PanelFocusTarget(
        panel = PanelMode.PLUGINS,
        region = FocusRegion.RESULTS,
        count = panel.plugins.size + 1,
        columns = 1,
    ) { index ->
        val plugin = panel.plugins.getOrNull(index)
        if (plugin != null) onOpenPlugin(plugin.id) else onManage()
    }
    val focused = state.focusedIndex(FocusRegion.RESULTS)
    // Material's line height for small text is 24 sp, which double-spaces a
    // two-line description; a line in proportion to the font instead.
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(lineHeight = 1.35.em))) {
        // Back from a plugin, the list is where it was left, and the next
        // plugin opened from it starts at its own top.
        androidx.compose.runtime.LaunchedEffect(Unit) { PluginPanelScroll.forgetRunning() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(PluginPanelScroll.list)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            panel.notice?.let { notice ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(20.dp))
                    Text(notice, color = kb.secondaryText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
            if (panel.plugins.isEmpty() && panel.notice == null) {
                // Only when nothing else has already explained the empty list. With
                // the subsystem off, or every plugin switched off, the notice above
                // says why — and "No plugins yet" beside it is simply untrue.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 16.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(kb.chip),
                    ) { Icon(Icons.Outlined.Extension, contentDescription = null, tint = kb.accent, modifier = Modifier.size(32.dp)) }
                    Text(
                        stringResource(R.string.ime_plugin_empty_title),
                        color = kb.keyText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.ime_plugin_empty_body),
                        color = kb.secondaryText,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            for ((index, plugin) in panel.plugins.withIndex()) {
                if (plugin.id in hidden) continue
                key(plugin.id) {
                    val where = pluginToolPlaceIn(
                        state.settings.toolbarTools,
                        state.settings.toolboxOrder,
                        state.settings.toolbox.folders,
                        plugin.id,
                    )
                    val onBar = where == PluginToolPlace.TOOLBAR
                    val toolbarLabel = stringResource(if (onBar) R.string.ime_plugin_action_toolbar_remove else R.string.ime_plugin_action_toolbar_add)
                    val settingsLabel = stringResource(R.string.ime_plugin_action_settings)
                    // The two a thumb wants most; the rest are behind a hold or ⋮,
                    // where every swipe action also is, for TalkBack.
                    KbSwipeActionsRow(
                        actions = listOf(
                            SwipeAction(Icons.Outlined.Widgets, toolbarLabel, SwipeTone.ACCENT) {
                                place(plugin.id, if (onBar) PluginToolPlace.TOOLBOX else PluginToolPlace.TOOLBAR)
                            },
                            SwipeAction(Icons.Outlined.Settings, settingsLabel) { onOpenRoute("plugin/${plugin.id}") },
                        ),
                        reduceMotion = state.settings.reduceMotion,
                    ) {
                        PluginRow(
                            plugin,
                            focused = index == focused,
                            showingOptions = expanded == plugin.id,
                            onOptions = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                expanded = if (expanded == plugin.id) null else plugin.id
                            },
                        ) { onOpenPlugin(plugin.id) }
                    }
                    if (expanded == plugin.id) {
                        PluginOptions(
                            place = where,
                            onSettings = { onOpenRoute("plugin/${plugin.id}") },
                            onPlace = { place(plugin.id, it) },
                            onTurnOff = {
                                store.setEnabled(plugin.id, false)
                                hidden = hidden + plugin.id
                                expanded = null
                            },
                        )
                    }
                }
            }
            ListRow(
                title = stringResource(R.string.ime_plugin_manage_action),
                subtitle = null,
                leading = { Icon(Icons.Outlined.Tune, contentDescription = null, tint = kb.chipText, modifier = Modifier.size(22.dp)) },
                focused = focused == panel.plugins.size,
                onClick = onManage,
            )
        }
    }
}

/**
 * One installed plugin, drawn as a row of a settings list: its initial in a
 * round badge, its name, what it does in a line or two, and a chevron that says
 * the row opens something.
 */
@Composable
private fun PluginRow(
    plugin: InstalledPlugin,
    focused: Boolean = false,
    showingOptions: Boolean = false,
    onOptions: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    // Read outside ifBlank: that lambda is not a composable one.
    val versionLabel = stringResource(R.string.ime_plugin_version_label, plugin.version)
    val moreLabel = stringResource(R.string.ime_plugin_more, plugin.displayName)
    ListRow(
        title = plugin.displayName,
        subtitle = plugin.displayDescription.ifBlank { versionLabel },
        onLongClick = onOptions,
        trailing = onOptions?.let { open ->
            {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (showingOptions) kb.chipActive else Color.Transparent)
                        .clickable(onClick = open)
                        .semantics { contentDescription = moreLabel },
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = null,
                        tint = if (showingOptions) kb.chipActiveText else kb.secondaryText,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        leading = {
            // Its own icon when its manifest names one (`tool.icon`), the
            // picture its tool wears; otherwise its initial.
            if (plugin.tool.icon.isNotBlank()) {
                PluginIcon(plugin, tint = kb.chipActiveText, modifier = Modifier.size(22.dp))
            } else {
                Text(
                    pluginInitial(plugin.displayName),
                    color = kb.chipActiveText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        leadingFill = kb.chipActive,
        focused = focused,
        onClick = onClick,
    )
}

/** The first letter of [name], a whole grapheme so an accent or an emoji is not cut in half. */
private fun pluginInitial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val breaks = java.text.BreakIterator.getCharacterInstance()
    breaks.setText(trimmed)
    val end = breaks.next().takeIf { it > 0 } ?: 1
    return trimmed.substring(0, end).uppercase()
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    focused: Boolean,
    leadingFill: Color? = null,
    /** Drawn in place of the chevron, such as a plugin's ⋮ options button. */
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(start = 12.dp, end = if (trailing != null) 4.dp else 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(leadingFill ?: kb.keyText.copy(alpha = 0.08f)),
        ) { leading() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = kb.chipText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = kb.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * A plugin's options, opened under its row by a hold or its ⋮ button: its
 * settings page, a tile on the toolbar or in the toolbox, and turning it off.
 * Inline rather than a popup: apps may refuse touches through a keyboard's
 * popup window. Uninstalling and its code are in settings, behind a page that
 * can ask first.
 */
@Composable
private fun PluginOptions(
    place: PluginToolPlace,
    onSettings: () -> Unit,
    onPlace: (PluginToolPlace) -> Unit,
    onTurnOff: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
    ) {
        OptionChip(Icons.Outlined.Settings, stringResource(R.string.ime_plugin_action_settings), onClick = onSettings)
        // Off the bar, a pinned tool goes back to its cell in the toolbox, as a
        // built-in tool dragged off the bar does.
        val onBar = place == PluginToolPlace.TOOLBAR
        OptionChip(
            Icons.Outlined.Widgets,
            stringResource(if (onBar) R.string.ime_plugin_action_toolbar_remove else R.string.ime_plugin_action_toolbar_add),
            selected = onBar,
        ) { onPlace(if (onBar) PluginToolPlace.TOOLBOX else PluginToolPlace.TOOLBAR) }
        val inBox = place == PluginToolPlace.TOOLBOX
        OptionChip(
            Icons.Outlined.Handyman,
            stringResource(if (inBox) R.string.ime_plugin_action_toolbox_remove else R.string.ime_plugin_action_toolbox_add),
            selected = inBox,
        ) { onPlace(if (inBox) PluginToolPlace.NONE else PluginToolPlace.TOOLBOX) }
        OptionChip(Icons.Outlined.PowerSettingsNew, stringResource(R.string.ime_plugin_action_turn_off), onClick = onTurnOff)
    }
}

/** An icon and a word, in the keyboard's chip colours; [selected] lit as a selected chip is. */
@Composable
private fun OptionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    val content = if (selected) kb.chipActiveText else kb.chipText
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(if (selected) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(label, color = content, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * The running view's interactive leaves in render order, for the focus ring.
 * Tabs are skipped on purpose: which page is showing lives in composable-local
 * saved state a pure walk cannot see, so ringing into a hidden page would
 * activate controls that are not on screen. Ring coverage inside tabbed
 * plugins is future work; everything else is reachable.
 */
private fun interactiveLeaves(widgets: List<PluginWidget>): List<PluginWidget> = buildList {
    fun walk(list: List<PluginWidget>) {
        for (widget in list) {
            when (widget) {
                is PluginWidget.Column -> walk(widget.children)
                is PluginWidget.Row -> walk(widget.children)
                is PluginWidget.Button -> if (widget.enabled) add(widget)
                is PluginWidget.Toggle, is PluginWidget.Input -> add(widget)
                else -> Unit
            }
        }
    }
    walk(widgets)
}

@Composable
private fun RunningPlugin(
    state: KeyboardUiState,
    panel: PluginPanelUi.Running,
    onEvent: (PluginEvent) -> Unit,
    onInputFocus: (String?) -> Unit,
    onToolInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    onPaste: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val leaves = interactiveLeaves(panel.ui.root)
    PanelFocusTarget(
        panel = PanelMode.PLUGINS,
        region = FocusRegion.RESULTS,
        count = leaves.size,
        columns = 1,
    ) { index ->
        when (val leaf = leaves.getOrNull(index)) {
            is PluginWidget.Button -> onEvent(PluginEvent.Click(leaf.id))
            is PluginWidget.Toggle -> onEvent(PluginEvent.ToggleChanged(leaf.id, !leaf.checked))
            // Focusing an input flips pluginTypingActive, so the next
            // keystrokes take the existing safe path into the box.
            is PluginWidget.Input -> onInputFocus(leaf.id)
            else -> Unit
        }
    }
    val ringed = state.focusedIndex(FocusRegion.RESULTS)?.let { leaves.getOrNull(it) }
    val context = LocalContext.current
    val style = remember(kb, panel.plugin.id) { keyboardPluginStyle(kb, panel.plugin.id, PluginStore.get(context)) }
    // A box whose keys the focus switch sent to the app's field (#665) draws
    // as unfocused, and pressing it takes them back.
    val caretHandle = LocalCaptureCaret.current
    val reclaim = caretHandle.onFocusSwitch?.takeIf { caretHandle.toApp }
    val host = remember(state, onInputFocus, onPaste, reclaim) { KeyboardInputHost(state, onInputFocus, onPaste, reclaim) }
    // The focus switch for the box taking the keys sits in the header
    // ([PluginHeaderActions]), not on a row of its own above the plugin.
    Box(Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (panel.busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = kb.accent,
                trackColor = kb.chip,
            )
        }
        panel.error?.let { message ->
            Text(
                message,
                color = kb.secondaryText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        for (line in panel.ui.repairs) {
            Text(
                line.resolve(context),
                color = kb.secondaryText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        // The overlay layer covers the visible panel, not the scrolled
        // content: a dropdown opens over the widgets, a dialog over all of it.
        CompositionLocalProvider(LocalPluginPanelStyle provides style) {
            PluginOverlayBox(panel.plugin.id, Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(PluginPanelScroll.running(panel.plugin.id))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PluginWidgetList(
                        widgets = panel.ui.root,
                        host = host,
                        onEvent = onEvent,
                        onInsert = onToolInsert,
                        onCopy = onCopy,
                        ringed = ringed,
                    )
                }
            }
        }
    }
        // Over the whole panel, so the plugin can't be used past it: the
        // question is answered before anything else.
        PermissionDialog(
            plugin = panel.plugin,
            needs = panel.needs,
            onAllow = { onEvent(PythonSession.hostEvent(ALLOW_EVENT)) },
            onDeny = { onEvent(PythonSession.hostEvent(DENY_EVENT)) },
        )
    }
}

/**
 * The permission dialog's two answers. The service takes them before the
 * plugin could: Allow grants what the plugin asked for, Don't allow puts the
 * dialog away. Neither reaches the script.
 */
internal const val ALLOW_EVENT = "wm.allow"
internal const val DENY_EVENT = "wm.deny"

/**
 * A call needed a risky permission the user has not turned on. Asked the way
 * Android asks for one: a dialog over the plugin with its icon and name, what
 * it wants and what that lets it do, and Allow or Don't allow. Drawn by the
 * keyboard, never by the plugin, and over the whole panel so it is answered
 * before the plugin can be used further.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PermissionDialog(
    plugin: InstalledPlugin,
    needs: List<PluginPermission>,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val riskyOff = remember(needs) { needs.isNotEmpty() && !PluginStore.get(context).allowRisky() }
    androidx.compose.animation.AnimatedVisibility(
        visible = needs.isNotEmpty(),
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = Modifier.matchParentSize(),
    ) {
        // The last list asked for, kept while the dialog fades out.
        val shown = remember { mutableStateOf(needs) }
        if (needs.isNotEmpty()) shown.value = needs
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
        ) {
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .widthIn(max = 380.dp)
                    .clip(shape)
                    .background(kb.panelSurfaceOpaque())
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(kb.chipActive),
                ) {
                    if (plugin.tool.icon.isNotBlank()) {
                        PluginIcon(plugin, tint = kb.chipActiveText, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Outlined.Extension, contentDescription = null, tint = kb.chipActiveText, modifier = Modifier.size(24.dp))
                    }
                }
                Text(
                    stringResource(R.string.ime_plugin_permission_title, plugin.displayName),
                    color = kb.keyText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                for (permission in shown.value) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(context.getString(permission.labelRes), color = kb.keyText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        permission.warningRes?.let { Text(context.getString(it), color = kb.secondaryText, fontSize = 13.sp) }
                    }
                }
                if (riskyOff) {
                    Text(stringResource(R.string.ime_plugin_permission_risky_note), color = kb.secondaryText, fontSize = 12.sp)
                }
                // Stacked full-width answers, as Android's own permission prompt has them.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    PermissionAnswer(stringResource(R.string.ime_plugin_permission_allow), primary = true, onClick = onAllow)
                    PermissionAnswer(stringResource(R.string.ime_plugin_permission_deny), primary = false, onClick = onDeny)
                }
            }
        }
    }
}

@Composable
private fun PermissionAnswer(label: String, primary: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (primary) kb.chipActive else kb.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, color = if (primary) kb.chipActiveText else kb.chipText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** The theme's chip colour made opaque, so the panel doesn't show through the dialog. */
private fun KbTheme.panelSurfaceOpaque(): Color =
    if (chip.alpha >= 1f) chip else lerp(if (keyText.luminance() > 0.5f) Color(0xFF202124) else Color(0xFFF8F9FA), chip.copy(alpha = 1f), chip.alpha)

/**
 * Whether the header should carry the access mark: the plugin may reach the
 * network or read what the user is writing, and the user turned that on.
 */
internal fun PluginPanelUi.Running.showsAccessMark(): Boolean =
    plugin.grants(PluginPermission.Network) && PluginPermission.Network in plugin.declaredPermissions ||
        plugin.grants(PluginPermission.FieldRead) && PluginPermission.FieldRead in plugin.declaredPermissions ||
        plugin.grants(PluginPermission.Ai) && PluginPermission.Ai in plugin.declaredPermissions

/**
 * The header's right end while a plugin runs: the access mark, then the
 * buttons the plugin asked for with `@plugin.command`. The keyboard draws
 * them, three at most, as it draws its own header buttons.
 */
@Composable
internal fun PluginHeaderActions(panel: PluginPanelUi.Running, onEvent: (PluginEvent) -> Unit) {
    val kb = LocalKbTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Draws itself only while one of the plugin's boxes has the keys.
        FieldFocusSwitch(LocalCaptureCaret.current)
        if (panel.showsAccessMark()) {
            Icon(
                Icons.Outlined.Public,
                contentDescription = stringResource(R.string.ime_plugin_access_mark),
                tint = kb.secondaryText,
                modifier = Modifier.size(16.dp),
            )
        }
        for (command in panel.host?.commands.orEmpty().take(MAX_COMMANDS)) {
            ToolPanelChip(label = command.label) {
                onEvent(PythonSession.hostEvent("command", mapOf("id" to kotlinx.serialization.json.JsonPrimitive(command.id))))
            }
        }
    }
}

private const val MAX_COMMANDS = 3

/** The style the running panel remembers, built without a composition so a test can check each colour's source. */
internal fun keyboardPluginStyle(kb: KbTheme, pluginId: String? = null, store: PluginStore? = null): PluginPanelStyle =
    PluginPanelStyle(
        text = kb.keyText,
        secondaryText = kb.secondaryText,
        surface = kb.chip,
        onSurface = kb.chipText,
        accent = kb.accent,
        cardShape = kb.cardShape(),
        cardBorder = { modifier, shape -> modifier.chipBorder(kb, shape) },
        ring = { modifier, active -> modifier.focusRing(active) },
        accentContainer = kb.chipActive,
        onAccentContainer = kb.chipActiveText,
        buttonShape = kb.chipShape(),
        glass = { modifier, shape -> modifier.frostGlass(kb, shape, FrostSurface.CHIPS) },
        chip = { label, selected, enabled, modifier, onClick ->
            ToolPanelChip(label = label, selected = selected, modifier = modifier, enabled = enabled, onClick = onClick)
        },
        icon = { name, tint, size -> Icon(pluginActionIcon(name), contentDescription = null, tint = tint, modifier = Modifier.size(size)) },
        image = { src, alt, modifier -> PluginAssetImage(src, alt, modifier, pluginId, store, kb.secondaryText) },
        qr = { value, size -> QrImage(value, Modifier.size(size)) },
    )

/**
 * A picture from the plugin's own `assets/`, read off the main thread. A web
 * address is not fetched: the panel would reach a server the user never
 * allowed, so its alt text stands in.
 */
@Composable
private fun PluginAssetImage(src: String, alt: String, modifier: Modifier, pluginId: String?, store: PluginStore?, muted: Color) {
    val name = src.removePrefix("assets/").takeIf { src.startsWith("assets/") }
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, pluginId, name) {
        value = if (pluginId == null || store == null || name == null) null else withContext(Dispatchers.IO) {
            store.asset(pluginId, name)?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    val picture = bitmap
    if (picture != null) {
        Image(picture, alt, modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        Text(alt, color = muted, fontSize = 12.sp, modifier = modifier)
    }
}

/**
 * A plugin's text boxes as the keyboard hosts them: the text is what the service
 * collected, pressing a box hands it the keys, and there is no text field, so
 * [onValueChange] is null.
 */
private class KeyboardInputHost(
    private val state: KeyboardUiState,
    private val focus: (String?) -> Unit,
    private val paste: (String) -> Unit,
    /** The focus switch, while it has the focused box's keys with the app's field (#665). */
    private val reclaim: (() -> Unit)?,
) : PluginInputHost {
    override fun value(id: String): String = state.pluginInputs[id].orEmpty()

    override fun isFocused(id: String): Boolean = state.pluginFocusedInput == id && !state.captureHandedToApp

    override fun onFocus(id: String) {
        if (reclaim != null && state.pluginFocusedInput == id) reclaim.invoke() else focus(id)
    }

    override fun onPaste(id: String) = paste(id)

    override val onValueChange: ((String, String) -> Unit)? = null
}

/**
 * Where the Plugins panel's list and the open plugin were scrolled to. Held
 * outside the composition: the panel leaves it for a scanner or a picker the
 * plugin asked for, and for its list a plugin opened from it, and both came
 * back at the top. Main thread.
 */
internal object PluginPanelScroll {
    val list = androidx.compose.foundation.ScrollState(0)

    private val open = HashMap<String, androidx.compose.foundation.ScrollState>()

    fun running(pluginId: String): androidx.compose.foundation.ScrollState =
        open.getOrPut(pluginId) { androidx.compose.foundation.ScrollState(0) }

    /** The list is showing: the next plugin opened from it starts at its top. */
    fun forgetRunning() = open.clear()
}
