package com.wasimaster.wmkeyboard.ime.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
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
    onManage: () -> Unit,
) {
    when (val panel = state.plugins) {
        is PluginPanelUi.List -> PluginList(state, panel, onOpenPlugin, onManage)
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
    onManage: () -> Unit,
) {
    val kb = LocalKbTheme.current
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
                PluginRow(plugin, focused = index == focused) { onOpenPlugin(plugin.id) }
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
private fun PluginRow(plugin: InstalledPlugin, focused: Boolean = false, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    // Read outside ifBlank: that lambda is not a composable one.
    val versionLabel = stringResource(R.string.ime_plugin_version_label, plugin.version)
    ListRow(
        title = plugin.displayName,
        subtitle = plugin.displayDescription.ifBlank { versionLabel },
        leading = {
            Text(
                pluginInitial(plugin.displayName),
                color = kb.chipActiveText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
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
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
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
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(20.dp))
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
        if (panel.needs.isNotEmpty()) {
            PermissionBanner(panel.needs, onAllow = { onEvent(PythonSession.hostEvent(ALLOW_EVENT)) })
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CompositionLocalProvider(LocalPluginPanelStyle provides style) {
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

/**
 * The event the banner's Allow sends. The service takes it before the plugin
 * could: it opens the plugin's page in settings, where each risky permission
 * is a switch behind its warning. Nothing is granted from the keyboard.
 */
internal const val ALLOW_EVENT = "wm.allow"

/**
 * A call needed a risky permission the user has not turned on. Names each
 * one, so the user knows what the plugin wanted before deciding.
 */
@Composable
private fun PermissionBanner(needs: List<PluginPermission>, onAllow: () -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val names = needs.joinToString { context.getString(it.labelRes) }
    val shape = kb.cardShape()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = kb.accent, modifier = Modifier.size(20.dp))
        Text(
            stringResource(R.string.ime_plugin_needs_permission, names),
            color = kb.chipText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ToolPanelChip(label = stringResource(R.string.ime_plugin_allow_action), selected = true, onClick = onAllow)
    }
}

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
