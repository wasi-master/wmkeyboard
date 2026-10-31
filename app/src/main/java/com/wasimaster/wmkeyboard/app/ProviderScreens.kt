package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CurrencyBitcoin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CurrencyExchange
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dns
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DragIndicator
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Key
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HomeIotDevice
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhoneAndroid
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import androidx.compose.ui.text.style.TextOverflow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import com.wasimaster.wmkeyboard.app.homeassistant.HomeProviderExtras
import com.wasimaster.wmkeyboard.app.homeassistant.homeReady
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.providers.ChainMode
import com.wasimaster.wmkeyboard.core.providers.CredentialSpec
import com.wasimaster.wmkeyboard.core.providers.Need
import com.wasimaster.wmkeyboard.core.providers.ProviderCatalog
import com.wasimaster.wmkeyboard.core.providers.ProviderChain
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderEnv
import com.wasimaster.wmkeyboard.core.providers.ProviderEnvironment
import com.wasimaster.wmkeyboard.core.providers.ProviderLogos
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.providers.SameKind
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiProviders
import com.wasimaster.wmkeyboard.core.settings.ProviderEdits
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.imagegen.OnlineImageGen
import com.wasimaster.wmkeyboard.core.settings.availableVoiceEngines
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.providerChain
import com.wasimaster.wmkeyboard.core.settings.providerCredentials
import com.wasimaster.wmkeyboard.core.settings.providerMode
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import kotlinx.coroutines.launch

/*
 * One screen part for every tool that can ask more than one service: the GIF
 * and sticker search, translate, the dictionary, synonyms, vocabulary, web
 * search, background photos, currency. It works the way backup locations do.
 * A provider is added from a sheet of kinds, set up once in an editor, and
 * then moved, switched off or removed in the tool's list. What a provider was
 * set up with (a key, a server) is shared with every other tool that lists
 * it, so one KLIPY key serves GIFs and stickers both.
 */

/**
 * A tool's provider list as settings groups. The providers that are on come
 * first, in the order they are asked, each row saying what it is set up with
 * and that it opens: a press opens the provider's own screen
 * ([PROVIDER_PAGE_ROUTE]), where its setup and everything else it has live.
 * The ones that are off follow, folded to a name, a description and a
 * switch, under a heading for where they run when they run in more than one
 * kind of place. How the list is used, and Reset, sit in a fold at the end.
 */
@Composable
internal fun ProvidersGroup(
    repository: SettingsRepository,
    settings: LiveSettings,
    tool: ProviderTool,
    /** The group's heading, which is also what a settings search lands on. */
    @StringRes titleRes: Int = R.string.providers_group_title,
    info: String? = null,
    /**
     * How far down the settings depth the list sits, for a page where it is
     * not what the page is for (a fallback source, a second engine). The
     * fold of how the list is used is advanced wherever the list is.
     */
    level: SettingLevel = SettingLevel.BASIC,
    /** After every saved change: for a cache that answered for the old setup. */
    onChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val navigate = LocalSettingsNavigate.current
    val env = rememberProviderEnv(tool)
    val rows = settings.watch { it.resolveProviders(tool, env, all = true) }
    val mode = settings.watch { it.providerMode(tool, env) }
    val stored = settings.watch { tool in it.providers.chains }
    // Only a list that folds two of a kind, with some kind on it twice, has anything to decide here.
    val sameKind = settings.watch { s ->
        s.providerChain(tool, env).takeIf { tool.foldsSameKind(it.mode) && it.repeatsKind }?.sameKindInForce
    }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProviderEditTarget?>(null) }
    var removing by remember { mutableStateOf<ResolvedProvider?>(null) }

    fun save(change: ProviderEdits.Change) {
        scope.launch {
            repository.saveProviders(change)
            onChanged()
        }
    }
    fun chain(): ProviderChain = settings.value.providerChain(tool, env)
    fun openRow(row: ResolvedProvider) {
        // A kind a newer build added: nothing to open, only to remove.
        if (row.type == null) removing = row else navigate(providerRoute(tool, row.key))
    }
    fun toggle(row: ResolvedProvider, on: Boolean) = save(ProviderEdits.setChain(tool, chain().toggled(row.key, on)))

    val on = rows.filter { it.entry.enabled }
    val off = rows.filterNot { it.entry.enabled }
    // What each row that is on is set up with, from its kind's own settings
    // and what is on the phone; read live, so a model downloaded or chosen
    // on the provider's screen shows here on the way back.
    val facts = rememberProviderFacts(on.mapTo(HashSet()) { it.typeId }, settings)
    val summaries = settings.watch { s -> on.associate { it.key to providerSummary(it, s, facts[it.typeId]) } }
    SettingsGroup(
        stringResource(titleRes),
        // What a settings search lands on; one list per page.
        highlightKey = titleRes,
        info = info ?: stringResource(R.string.providers_group_info),
        level = level,
    ) {
        item {
            if (on.isEmpty()) {
                Text(
                    stringResource(if (rows.isEmpty()) R.string.providers_empty else R.string.providers_none_on),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                ProviderList(
                    rows = on,
                    summaries = summaries,
                    // Only the rows that are on move; the ones that are off keep
                    // their places, which nothing reads while they are off.
                    onReorder = { ordered ->
                        save(ProviderEdits.setChain(tool, chain().withOnOrder(ordered.map { it.key })))
                    },
                    onToggle = ::toggle,
                    onOpen = ::openRow,
                    onRemove = { row -> removing = row },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.providers_add),
                subtitle = stringResource(R.string.providers_add_subtitle),
                icon = Icons.Outlined.Add,
                onClick = { adding = true },
            )
        }
    }
    // Sorted by where they run only when that tells them apart: three
    // online services under "Online services" is a heading saying nothing.
    val kinds = off.groupBy { ProviderPlace.of(it.type) }.toSortedMap()
    for ((place, placed) in kinds) {
        SettingsGroup(stringResource(if (kinds.size > 1) place.titleRes else R.string.providers_section_off), level = level) {
            for (row in placed) {
                item(key = row.key) {
                    OffProviderRow(
                        row = row,
                        tool = tool,
                        onToggle = { toggle(row, it) },
                        onOpen = { openRow(row) },
                        onRemove = { removing = row },
                    )
                }
            }
        }
    }
    val modeSummary: (@Composable () -> String)? = if (tool.modes.size > 1) {
        { stringResource(mode.labelRes) }
    } else {
        null
    }
    SettingsGroup(
        stringResource(R.string.providers_options_title),
        foldKey = "providers_${tool.id}",
        foldSummary = modeSummary,
        level = SettingLevel.ADVANCED,
    ) {
        if (tool.modes.size > 1) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ChoiceControl(
                        options = tool.modes.map { it to stringResource(it.labelRes) },
                        selected = mode,
                        label = stringResource(R.string.providers_mode_label),
                        onChange = { picked: ChainMode -> save(ProviderEdits.setChain(tool, chain().copy(mode = picked))) },
                    )
                    Text(
                        stringResource(mode.descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        item(visible = sameKind != null) {
            val shown = sameKind ?: SameKind.FALLBACK
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ChoiceControl(
                    options = SameKind.entries.map { it to stringResource(it.labelRes) },
                    selected = shown,
                    label = stringResource(R.string.providers_same_kind_label),
                    onChange = { picked: SameKind -> save(ProviderEdits.setChain(tool, chain().copy(sameKind = picked))) },
                )
                Text(
                    stringResource(shown.descRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item(visible = stored) {
            WmRow(
                title = stringResource(R.string.providers_reset),
                subtitle = stringResource(R.string.providers_reset_subtitle),
                icon = Icons.Outlined.History,
                onClick = {
                    scope.launch {
                        repository.resetProviderChain(tool)
                        onChanged()
                    }
                },
            )
        }
    }

    if (adding) {
        val current = chain()
        ProviderTypeSheet(
            tool = tool,
            env = env,
            chain = current,
            credentials = settings.value.providerCredentials(),
            onPick = { type, existing ->
                adding = false
                val pick = pickProvider(type, existing, current)
                when (pick) {
                    is ProviderPick.Add -> save(ProviderEdits.addEntry(settings.value, tool, pick.entry, env))
                    is ProviderPick.Edit -> editing = pick.target
                }
            },
            onDismiss = { adding = false },
        )
    }
    editing?.let { target ->
        ProviderEditorSheet(
            repository = repository,
            tool = tool,
            target = target,
            usedBy = ProviderEdits.usedBy(settings.value, target.credential.id, tool, env),
            onSave = { credential ->
                editing = null
                save(ProviderEdits.saveCredential(settings.value, credential, if (target.isNew) tool else null, env))
            },
            onRemove = target.row?.let { row ->
                {
                    editing = null
                    removing = row
                }
            },
            onDismiss = {
                editing = null
                onChanged()
            },
        )
    }
    removing?.let { row ->
        ProviderRemoveDialog(
            settings = settings,
            tool = tool,
            env = env,
            row = row,
            onConfirm = { change ->
                removing = null
                save(change)
            },
            onDismiss = { removing = null },
        )
    }
}

/** Asks before a row goes; says whether its key and address go with it. */
@Composable
internal fun ProviderRemoveDialog(
    settings: LiveSettings,
    tool: ProviderTool,
    env: ProviderEnv,
    row: ResolvedProvider,
    onConfirm: (ProviderEdits.Change) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val title = providerTitle(context, row)
    val change = ProviderEdits.removeEntry(settings.value, tool, row.key, env)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.providers_remove_title, title)) },
        text = {
            Text(
                stringResource(
                    if (change.credentials != null) R.string.providers_remove_body_credential else R.string.providers_remove_body,
                    stringResource(tool.labelRes),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(change) }) { Text(stringResource(R.string.providers_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * What a provider list knows of the phone, read once per list: whether
 * offline GIF packs exist is a look at the disk, and which voice engines can
 * run is a load of their libraries.
 */
@Composable
internal fun rememberProviderEnv(tool: ProviderTool): ProviderEnv = remember(tool) {
    ProviderEnvironment.current().let {
        if (tool == ProviderTool.VOICE) it.copy(voiceEngines = availableVoiceEngines()) else it
    }
}

/**
 * [entries] with the rows named in [onOrder] in that order, each put into a
 * place one of those rows held: every other row keeps its own. This is how
 * the turned-on rows are dragged without the turned-off ones between them
 * moving.
 */
internal fun ProviderChain.withOnOrder(onOrder: List<String>): ProviderChain {
    val byKey = entries.associateBy { it.key }
    val queue = ArrayDeque(onOrder.distinct().mapNotNull { byKey[it] })
    val moving = queue.mapTo(HashSet()) { it.key }
    return copy(entries = entries.map { if (it.key in moving) queue.removeFirst() else it })
}

/** Where a provider runs, which is what the turned-off rows are sorted under. In heading order. */
internal enum class ProviderPlace(@StringRes val titleRes: Int) {
    ON_DEVICE(R.string.providers_section_on_device),
    ONLINE(R.string.providers_section_online),
    SERVER(R.string.providers_section_servers),
    ;

    companion object {
        fun of(type: ProviderType?): ProviderPlace = when {
            type?.onDevice == true -> ON_DEVICE
            type?.selfHosted == true -> SERVER
            else -> ONLINE
        }
    }
}

/** The pattern of a provider's own screen; see [providerRoute]. */
internal const val PROVIDER_PAGE_ROUTE = "provider/{tool}/{key}"

/**
 * A provider's own screen. [key] is a row's key, or a kind's id: a search
 * result or a link names a kind ("the Whisper engine"), and the screen opens
 * on that kind's first row, or offers to add it.
 */
// URLEncoder rather than Uri: the search index builds these routes on the JVM in tests too.
internal fun providerRoute(tool: ProviderTool, key: String): String =
    "provider/${tool.id}/${java.net.URLEncoder.encode(key, "UTF-8")}"

/** [providerRoute] for a kind, for the search index and links. */
internal fun providerRoute(tool: ProviderTool, type: ProviderType): String = providerRoute(tool, type.id)

/** The list and the row key (or kind id) a [providerRoute] names; null for any other route. */
internal fun providerOfRoute(route: String): Pair<ProviderTool, String>? {
    val parts = route.split('/')
    if (parts.size != 3 || parts[0] != PROVIDER_PAGE_ROUTE.substringBefore('/')) return null
    val tool = ProviderTool.of(parts[1]) ?: return null
    return tool to java.net.URLDecoder.decode(parts[2], "UTF-8")
}

/** What the editor sheet is editing: one credential of one type, new or saved, and the list row it was opened from. */
internal data class ProviderEditTarget(
    val type: ProviderType,
    val credential: ProviderCredential,
    val isNew: Boolean,
    val row: ResolvedProvider? = null,
)

/** What tapping a kind in the add sheet does: add a row straight away, or set one up first. */
internal sealed interface ProviderPick {
    data class Add(val entry: ProviderEntry) : ProviderPick
    data class Edit(val target: ProviderEditTarget) : ProviderPick
}

/**
 * A kind that needs nothing typed in (no key, a key the build carries, a
 * credential another tool already set up) goes straight into the list; the
 * rest open the editor. A kind whose own row is already listed sets up
 * another one of it: a new server, or a second key or account.
 */
internal fun pickProvider(type: ProviderType, existing: ProviderCredential?, chain: ProviderChain): ProviderPick {
    if (existing != null) {
        return ProviderPick.Add(ProviderEntry(type.id, existing.id.takeIf { it != type.id }))
    }
    val defaultListed = chain.entries.any { it.typeId == type.id && it.credentialId == null }
    if (type.readyOutOfTheBox && !defaultListed) return ProviderPick.Add(ProviderEntry(type.id))
    val id = if (type.multiInstance || defaultListed) ProviderCredential.newId(type.id) else type.id
    return ProviderPick.Edit(ProviderEditTarget(type, ProviderCredential(id, type.id), isNew = true))
}

/** Set up beside the type's own credential: a second key or account, which brings its own key. */
private fun ProviderCredential.isAnother(type: ProviderType): Boolean = !type.multiInstance && id != type.id

/** What tells a saved setup apart when it has no nickname: its server, else the end of its key. */
@Composable
private fun credentialLabel(type: ProviderType, credential: ProviderCredential): String = when {
    credential.name.isNotBlank() -> credential.name
    credential.url.isNotBlank() -> hostOf(credential.url)
    credential.apiKey.isNotBlank() ->
        stringResource(R.string.providers_type_existing_key, stringResource(type.labelRes), credential.apiKey.takeLast(KEY_TAIL))
    else -> stringResource(type.labelRes)
}

/** The name a row goes by: the one the user gave a server, else its kind's. */
internal fun providerTitle(context: Context, row: ResolvedProvider): String =
    row.customName.ifEmpty { row.type?.let { context.getString(it.labelRes) } ?: row.typeId }

/**
 * What a row is set up with, and whether that stops it working: the Setup
 * row on its screen, and the banner over it. The list row says more
 * ([providerSummary]).
 */
@Composable
internal fun providerStatus(row: ResolvedProvider): Pair<String, Boolean> {
    val summary = providerSetupSummary(row)
    return summary.text() to summary.problem
}

/**
 * The list itself. Each row is the order, the provider, and its switch;
 * tapping a row opens its setup, where it can also be removed. The order
 * column (the position over a grip) is the drag handle, and TalkBack gets
 * Move up and Move down instead.
 *
 * Not [ReorderableColumn]: that one needs every row the same height, and a
 * provider's name and state wrap onto as many lines as they need here. Each
 * row's height is measured, and a dragged row swaps with a neighbour once it
 * is past half of that neighbour's height.
 */
@Composable
private fun ProviderList(
    rows: List<ResolvedProvider>,
    summaries: Map<String, ProviderSummary>,
    onReorder: (List<ResolvedProvider>) -> Unit,
    onToggle: (ResolvedProvider, Boolean) -> Unit,
    onOpen: (ResolvedProvider) -> Unit,
    onRemove: (ResolvedProvider) -> Unit,
) {
    var working by remember { mutableStateOf(rows) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    // Mid-drag the finger's order wins, and reaches the caller on the drop.
    LaunchedEffect(rows) { if (dragKey == null) working = rows }
    val currentRows by rememberUpdatedState(rows)
    val currentOnReorder by rememberUpdatedState(onReorder)
    fun finish() {
        if (working.map { it.key } != currentRows.map { it.key }) currentOnReorder(working)
        dragKey = null
        dragOffset = 0f
    }
    fun moved(from: Int, to: Int): List<ResolvedProvider> =
        working.toMutableList().apply { add(to, removeAt(from)) }

    val shape = MaterialTheme.shapes.medium
    val lift = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        working.forEachIndexed { index, row ->
            key(row.key) {
                val dragging = dragKey == row.key
                ProviderRow(
                    row = row,
                    summary = summaries[row.key] ?: ProviderSummary(emptyList()),
                    position = index + 1,
                    modifier = Modifier
                        .onSizeChanged { heights[row.key] = it.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            shadowElevation = if (dragging) 6.dp.toPx() else 0f
                            this.shape = shape
                        }
                        .background(if (dragging) lift else Color.Transparent, shape),
                    handle = Modifier.pointerInput(row.key) {
                        detectDragGestures(
                            onDragStart = {
                                dragKey = row.key
                                dragOffset = 0f
                            },
                            onDragEnd = { finish() },
                            onDragCancel = { finish() },
                        ) { change, drag ->
                            change.consume()
                            val from = working.indexOfFirst { it.key == dragKey }
                            if (from < 0) return@detectDragGestures
                            // Held at the ends: past the first or last row it stops.
                            dragOffset = (dragOffset + drag.y).let {
                                when {
                                    from == 0 && it < 0f -> 0f
                                    from == working.lastIndex && it > 0f -> 0f
                                    else -> it
                                }
                            }
                            if (dragOffset > 0f && from < working.lastIndex) {
                                val next = heights[working[from + 1].key] ?: return@detectDragGestures
                                if (dragOffset > next / 2f) {
                                    working = moved(from, from + 1)
                                    dragOffset -= next
                                }
                            } else if (dragOffset < 0f && from > 0) {
                                val previous = heights[working[from - 1].key] ?: return@detectDragGestures
                                if (-dragOffset > previous / 2f) {
                                    working = moved(from, from - 1)
                                    dragOffset += previous
                                }
                            }
                        }
                    },
                    onMove = if (dragKey == null) {
                        { by ->
                            val to = (index + by).coerceIn(working.indices)
                            if (to != index) {
                                working = moved(index, to)
                                currentOnReorder(working)
                            }
                        }
                    } else {
                        null
                    },
                    canMoveUp = index > 0,
                    canMoveDown = index < working.lastIndex,
                    onToggle = { on -> onToggle(row, on) },
                    onOpen = { onOpen(row) },
                    onRemove = { onRemove(row) },
                )
            }
        }
    }
}

/** One turned-on provider: the order and its grip, the tile, the name and setup, the way in, the switch. */
@Composable
private fun ProviderRow(
    row: ResolvedProvider,
    summary: ProviderSummary,
    position: Int,
    modifier: Modifier,
    handle: Modifier,
    onMove: ((Int) -> Unit)?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val title = providerTitle(context, row)
    val status = summary.text()
    val problem = summary.problem
    val upLabel = stringResource(R.string.providers_move_up)
    val downLabel = stringResource(R.string.providers_move_down)
    val shape = MaterialTheme.shapes.medium
    // Remove asks first, as it does from the provider's screen: the dialog
    // says whether the key goes with it.
    SwipeActionsRow(
        listOf(
            SwipeAction(Icons.Outlined.Settings, stringResource(R.string.providers_open), onClick = onOpen),
            deleteSwipeAction(stringResource(R.string.providers_remove), onRemove),
        ),
        modifier = modifier,
    ) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        // The order, over the grip that changes it.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .width(28.dp)
                .heightIn(min = 48.dp)
                .then(handle),
        ) {
            Text(
                position.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Outlined.DragIndicator,
                contentDescription = stringResource(R.string.rows_reorder_handle_desc, title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.size(4.dp))
        // Everything but the grip and the switch is one press target, and
        // the chevron at its end says that it opens.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .clickable(enabled = row.type != null, onClickLabel = stringResource(R.string.providers_open)) { onOpen() }
                .semantics {
                    customActions = buildList {
                        if (onMove != null && canMoveUp) add(CustomAccessibilityAction(upLabel) { onMove(-1); true })
                        if (onMove != null && canMoveDown) add(CustomAccessibilityAction(downLabel) { onMove(1); true })
                    }
                }
                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            ProviderTile(row.type, active = row.usable)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.Top) {
                    if (problem) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp, end = 4.dp).size(14.dp),
                        )
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (row.type != null) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        ProviderSwitch(row.entry.enabled, onToggle)
    }
    }
}

/**
 * One turned-off provider, folded: what it is and the switch that brings it
 * into the list. Its setup is not shown, since nothing asks it; a press
 * still opens its screen, to set it up before it is turned on.
 */
@Composable
private fun OffProviderRow(
    row: ResolvedProvider,
    tool: ProviderTool,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val title = providerTitle(context, row)
    val description = row.type?.let { stringResource(it.descriptionRes(tool)) } ?: stringResource(R.string.providers_status_unknown)
    SwipeActionsRow(
        listOf(deleteSwipeAction(stringResource(R.string.providers_remove), onRemove)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClickLabel = stringResource(R.string.providers_open)) { onOpen() }
                    .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                ProviderTile(row.type, active = false)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ProviderSwitch(false, onToggle, enabled = row.type != null)
        }
    }
}

/**
 * A row's switch, behind a thin rule that sets it apart from the part of the
 * row that opens: the two do different things, and read as two targets.
 * At four fifths of its size, so a long name keeps its width.
 */
@Composable
private fun ProviderSwitch(checked: Boolean, onToggle: (Boolean) -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .size(width = 1.dp, height = 32.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
    Box(Modifier.size(width = 52.dp, height = 40.dp), contentAlignment = Alignment.Center) {
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = enabled,
            modifier = Modifier.requiredSize(width = 52.dp, height = 32.dp).scale(0.8f),
        )
    }
}

/**
 * A kind's tile: its logo where the app carries one, else the glyph for what
 * it is. A one-colour mark is made readable on the theme ([readableMark]); a
 * picture that vanishes on one theme sits on its own plate.
 */
@Composable
internal fun ProviderTile(type: ProviderType?, active: Boolean = true, modifier: Modifier = Modifier) {
    val logo = type?.let { ProviderLogos.of(it.id) }
    val dark = isSystemInDarkTheme()
    val accent = when {
        !active -> MaterialTheme.colorScheme.outline
        logo != null && logo.monochrome -> readableMark(Color(logo.color), dark)
        logo != null -> Color(0xFF9E9E9E)
        else -> readableMark(providerAccent(type), dark)
    }
    if (logo == null) {
        WmIconTile(icon = providerIcon(type), accent = accent, modifier = modifier)
        return
    }
    WmIconTile(accent, modifier) {
        val plate = logo.backdrop?.takeIf { active }
        Box(
            contentAlignment = Alignment.Center,
            modifier = if (plate != null) {
                Modifier.size(30.dp).background(Color(plate), RoundedCornerShape(8.dp))
            } else {
                Modifier
            },
        ) {
            Icon(
                painterResource(logo.res),
                contentDescription = null,
                // A brand's own colours stay as they are; a one-colour mark takes the tile's.
                tint = if (logo.monochrome || !active) LocalContentColor.current else Color.Unspecified,
                modifier = Modifier.size(20.dp).graphicsLayer { alpha = if (active) 1f else 0.6f },
            )
        }
    }
}

/**
 * [color] moved toward white on a dark theme, or toward black on a light
 * one, until it stands out from the tile. The tile draws its glyph in the
 * accent itself on a dark theme, so a near-black brand (Unsplash, Wiktionary,
 * DeepL's navy) disappeared there; a pale one did the same on a light theme.
 */
internal fun readableMark(color: Color, dark: Boolean): Color {
    var mark = color
    repeat(MARK_STEPS) {
        val luminance = mark.luminance()
        if (if (dark) luminance >= DARK_MIN_LUMINANCE else luminance <= LIGHT_MAX_LUMINANCE) return mark
        mark = lerp(mark, if (dark) Color.White else Color.Black, MARK_STEP)
    }
    return mark
}

private const val MARK_STEPS = 10
private const val MARK_STEP = 0.2f
private const val DARK_MIN_LUMINANCE = 0.22f
private const val LIGHT_MAX_LUMINANCE = 0.45f

/** The glyph a kind of provider is drawn with: by what it is first, then by what it does. */
internal fun providerIcon(type: ProviderType?): ImageVector = when {
    type == null -> Icons.Outlined.Public
    HomeKind.of(type.id) != null -> Icons.Outlined.HomeIotDevice
    ProviderTool.VOICE in type.serves && !type.selfHosted -> Icons.Outlined.Mic
    type.onDevice -> Icons.Outlined.PhoneAndroid
    type.selfHosted -> Icons.Outlined.Dns
    ProviderTool.TRANSLATE in type.serves -> Icons.Outlined.Translate
    ProviderTool.SEARCH in type.serves -> Icons.Outlined.Search
    ProviderTool.CURRENCY_CRYPTO in type.serves && ProviderTool.CURRENCY_FIAT !in type.serves -> Icons.Outlined.CurrencyBitcoin
    ProviderTool.CURRENCY_FIAT in type.serves || ProviderTool.CURRENCY_CRYPTO in type.serves -> Icons.Outlined.CurrencyExchange
    ProviderTool.DICTIONARY in type.serves || ProviderTool.SYNONYMS in type.serves -> Icons.Outlined.Spellcheck
    type === ProviderTypes.COMMONS -> Icons.Outlined.Public
    ProviderTool.PHOTOS in type.serves -> Icons.Outlined.PhotoLibrary
    ProviderTool.AI in type.serves -> Icons.Outlined.AutoAwesome
    ProviderTool.GRAMMAR in type.serves -> Icons.Outlined.Spellcheck
    ProviderTool.IMAGE_GEN in type.serves -> Icons.Outlined.AutoAwesome
    else -> Icons.Outlined.Image
}

/** A kind's tile colour: the brand's where there is one, muted where there is none. */
internal fun providerAccent(type: ProviderType?): Color = when (type?.id) {
    ProviderTypes.KLIPY.id -> Color(0xFF6C47FF)
    ProviderTypes.GIPHY.id -> Color(0xFF00B4E6)
    ProviderTypes.COMMONS.id -> Color(0xFF006699)
    ProviderTypes.GIFCITIES.id -> Color(0xFFB0306B)
    ProviderTypes.DEEPL.id -> Color(0xFF0F2B46)
    ProviderTypes.GOOGLE_TRANSLATE.id -> Color(0xFF1A73E8)
    ProviderTypes.LIBRETRANSLATE.id -> Color(0xFF1565C0)
    ProviderTypes.MOZHI.id -> Color(0xFF7B5CBF)
    ProviderTypes.SIMPLYTRANSLATE.id -> Color(0xFF2B6CB0)
    ProviderTypes.LINGVA.id -> Color(0xFF38A169)
    ProviderTypes.TRANSLITE.id -> Color(0xFFC05621)
    ProviderTypes.BRAVE.id -> Color(0xFFFB542B)
    ProviderTypes.TAVILY.id -> Color(0xFF3B5BDB)
    ProviderTypes.SEARXNG.id -> Color(0xFF3050FF)
    ProviderTypes.UNSPLASH.id -> Color(0xFF111111)
    ProviderTypes.PEXELS.id -> Color(0xFF05A081)
    ProviderTypes.HOME_ASSISTANT.id -> Color(0xFF18BCF2)
    ProviderTypes.GOOGLE_HOME.id -> Color(0xFF4285F4)
    ProviderTypes.SMARTTHINGS.id -> Color(0xFF1F8EFA)
    ProviderTypes.HUE.id -> Color(0xFF0065D3)
    ProviderTypes.OPENHAB.id -> Color(0xFFE64A19)
    ProviderTypes.TUYA.id -> Color(0xFFFF4800)
    ProviderTypes.SWITCHBOT.id -> Color(0xFFE60012)
    ProviderTypes.ANTHROPIC.id -> Color(0xFFD97757)
    ProviderTypes.OPENAI.id -> Color(0xFF10A37F)
    ProviderTypes.GEMINI.id -> Color(0xFF4285F4)
    ProviderTypes.XAI.id -> Color(0xFF111111)
    ProviderTypes.DEEPSEEK.id -> Color(0xFF4D6BFE)
    ProviderTypes.BRAVE_ANSWERS.id -> Color(0xFFFB542B)
    ProviderTypes.OPENAI_IMAGE.id -> Color(0xFF10A37F)
    ProviderTypes.GEMINI_IMAGE.id -> Color(0xFF4285F4)
    ProviderTypes.XAI_IMAGE.id -> Color(0xFF111111)
    ProviderTypes.POLLINATIONS.id -> Color(0xFF1B1B1B)
    ProviderTypes.AI_HORDE.id -> Color(0xFF7E57C2)
    ProviderTypes.STABILITY.id -> Color(0xFF8B5CF6)
    ProviderTypes.BFL.id -> Color(0xFF222222)
    ProviderTypes.RECRAFT.id -> Color(0xFFE5482F)
    ProviderTypes.REPLICATE.id -> Color(0xFF202020)
    ProviderTypes.FAL.id -> Color(0xFF6C2BD9)
    ProviderTypes.TOGETHER.id -> Color(0xFF0F6FFF)
    ProviderTypes.HUGGING_FACE.id -> Color(0xFFFFB000)
    ProviderTypes.CLOUDFLARE_AI.id -> Color(0xFFF38020)
    else -> when {
        type == null -> Color(0xFF757575)
        type.onDevice -> Color(0xFF2E7D32)
        type.selfHosted -> Color(0xFF455A64)
        type.freeSoftware -> Color(0xFF00897B)
        else -> Color(0xFF5C6BC0)
    }
}

/** The short facts under a kind in the add sheet: free software, on the phone, a key it needs. */
@Composable
internal fun providerTags(type: ProviderType): String {
    val tags = buildList {
        if (type.onDevice) add(stringResource(R.string.providers_tag_on_device))
        if (type.freeSoftware) add(stringResource(R.string.providers_tag_free))
        if (type.selfHosted) add(stringResource(R.string.providers_tag_self_hosted))
        when {
            type.credential.key == Need.REQUIRED && type.hasBuiltInKey -> add(stringResource(R.string.providers_tag_builtin))
            type.credential.key == Need.REQUIRED -> add(stringResource(R.string.providers_tag_needs_key))
            type.credential.key == Need.NONE && !type.onDevice && !type.selfHosted ->
                add(stringResource(R.string.providers_tag_no_key))
        }
    }
    return tags.joinToString(stringResource(R.string.providers_tag_separator))
}

/**
 * "Add a provider" as a sheet of kinds, the build's suggestions first. A
 * server kind also lists the servers set up for another tool, so a
 * LibreTranslate server added once can be used again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderTypeSheet(
    tool: ProviderTool,
    env: ProviderEnv,
    chain: ProviderChain,
    credentials: List<ProviderCredential>,
    onPick: (ProviderType, ProviderCredential?) -> Unit,
    onDismiss: () -> Unit,
) {
    val types = remember(tool, env, chain) { ProviderCatalog.addable(tool, chain, env) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ScrollRail(state = rememberScrollRailState(), fadeColor = BottomSheetDefaults.ContainerColor) {
            Text(
                stringResource(R.string.providers_type_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.providers_type_subtitle, stringResource(tool.labelRes)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            for (type in types) {
                val tags = providerTags(type)
                val desc = stringResource(type.descriptionRes(tool))
                WmRow(
                    title = stringResource(type.labelRes),
                    subtitle = if (tags.isEmpty()) desc else desc + "\n" + tags,
                    leading = { ProviderTile(type) },
                    onClick = {
                        // The type's own setup, made on another tool's page, when this list does not have it yet.
                        onPick(type, credentials.firstOrNull { it.id == type.id && !type.multiInstance && !chain.has(type.id) })
                    },
                )
                // The other setups of this kind that this list does not use yet: servers, second keys.
                if (tool.allowsAnother(type)) {
                    val unused = credentials.filter { c ->
                        c.typeId == type.id && !chain.has(c.id) && (type.multiInstance || c.id != type.id) &&
                            (c.url.isNotBlank() || c.apiKey.isNotBlank())
                    }
                    for (credential in unused) {
                        WmRow(
                            title = stringResource(R.string.providers_type_use_existing, credentialLabel(type, credential)),
                            icon = if (credential.url.isNotBlank()) Icons.Outlined.Dns else Icons.Outlined.Key,
                            onClick = { onPick(type, credential) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * Setting up or changing one provider. Typed fields edit a draft that Save
 * keeps, so a half-typed address is never asked by a search meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
internal fun ProviderEditorSheet(
    repository: SettingsRepository,
    tool: ProviderTool,
    target: ProviderEditTarget,
    usedBy: List<ProviderTool>,
    onSave: (ProviderCredential) -> Unit,
    /** Takes the row out of the list (after asking); null while the provider is being added. */
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val type = target.type
    val spec = type.credential
    var draft by remember { mutableStateOf(target.credential) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val home = HomeKind.of(type.id) != null
    // A second key or account of a single kind: the build's key is the first one's, so this one needs its own.
    val another = target.credential.isAnother(type)
    val ready = if (home) {
        homeReady(type, draft)
    } else {
        (spec.url != Need.REQUIRED || draft.url.isNotBlank()) &&
            (spec.key != Need.REQUIRED || (type.hasBuiltInKey && !another) || draft.apiKey.isNotBlank()) &&
            (!another || spec.key == Need.NONE || draft.apiKey.isNotBlank() || (type === ProviderTypes.DEEPL && draft.url.isNotBlank())) &&
            (!spec.modelRequired || draft.model.isNotBlank()) &&
            (type !== ProviderTypes.DEEPL || draft.apiKey.isNotBlank() || draft.url.isNotBlank()) &&
            // A Premium account signs with both; one without the other is refused.
            (!spec.username || draft.apiKey.isBlank() == draft.extra[CredentialSpec.USERNAME].isNullOrBlank()) &&
            // Cloudflare runs a model under the account's address.
            (!spec.account || !draft.extra[CredentialSpec.ACCOUNT].isNullOrBlank()) &&
            // ACRCloud signs with a key and a secret; one without the other is refused.
            (!spec.secret || !draft.extra[CredentialSpec.SECRET].isNullOrBlank())
    }
    fun edit(next: ProviderCredential) {
        draft = next
        testResult = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ScrollRail(state = rememberScrollRailState(), fadeColor = BottomSheetDefaults.ContainerColor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                ProviderTile(type)
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(
                        target.credential.name.ifBlank { stringResource(type.labelRes) },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(type.descriptionRes(tool)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // A nickname wherever there can be two of the kind, so the list and the panel can tell them apart.
            if (type.multiInstance || tool.allowsAnother(type)) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_name),
                    value = draft.name,
                    supporting = stringResource(R.string.providers_field_name_hint),
                ) { edit(draft.copy(name = it)) }
            }
            if (spec.url != Need.NONE) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_url),
                    value = draft.url,
                    supporting = stringResource(
                        when {
                            type === ProviderTypes.LANGUAGETOOL -> R.string.providers_field_url_languagetool_hint
                            spec.url == Need.REQUIRED -> R.string.providers_field_url_hint
                            else -> R.string.providers_field_url_optional_hint
                        },
                    ),
                    keyboardType = KeyboardType.Uri,
                ) { edit(draft.copy(url = it)) }
            }
            if (spec.path) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_path),
                    value = draft.extra[CredentialSpec.PATH].orEmpty(),
                    supporting = stringResource(R.string.providers_field_path_hint),
                    keyboardType = KeyboardType.Uri,
                ) { path ->
                    edit(draft.copy(extra = if (path.isBlank()) draft.extra - CredentialSpec.PATH else draft.extra + (CredentialSpec.PATH to path)))
                }
            }
            if (spec.engines.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ChoiceControl(
                        options = spec.engines.map { it.id to it.label },
                        selected = spec.engine(draft.model)?.id ?: spec.engines.first().id,
                        label = stringResource(R.string.providers_field_engine),
                        onChange = { picked: String -> edit(draft.copy(model = picked)) },
                    )
                    Text(
                        stringResource(R.string.providers_field_engine_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (spec.account) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_account),
                    value = draft.extra[CredentialSpec.ACCOUNT].orEmpty(),
                    supporting = stringResource(R.string.providers_field_account_hint),
                ) { id ->
                    edit(draft.copy(extra = if (id.isBlank()) draft.extra - CredentialSpec.ACCOUNT else draft.extra + (CredentialSpec.ACCOUNT to id)))
                }
            }
            if (spec.username) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_username),
                    value = draft.extra[CredentialSpec.USERNAME].orEmpty(),
                    supporting = stringResource(R.string.providers_field_username_hint),
                ) { name ->
                    edit(
                        draft.copy(
                            extra = if (name.isBlank()) draft.extra - CredentialSpec.USERNAME else draft.extra + (CredentialSpec.USERNAME to name),
                        ),
                    )
                }
            }
            // A home's key is a token pasted or a pairing made in its own section below, not a plain field.
            if (spec.key != Need.NONE && !home) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_key),
                    value = draft.apiKey,
                    supporting = stringResource(
                        when {
                            type === ProviderTypes.LANGUAGETOOL -> R.string.providers_field_key_languagetool_hint
                            type === ProviderTypes.AUTOMATIC1111 -> R.string.providers_field_key_automatic1111_hint
                            type.keyFrom != null && !another -> R.string.providers_field_key_borrowed_hint
                            another -> R.string.providers_field_key_another_hint
                            type.hasBuiltInKey -> R.string.providers_field_key_builtin_hint
                            spec.key == Need.OPTIONAL -> R.string.providers_field_key_optional_hint
                            else -> R.string.providers_field_key_required_hint
                        },
                    ),
                    password = true,
                ) { edit(draft.copy(apiKey = it)) }
            }
            if (spec.secret) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_secret),
                    value = draft.extra[CredentialSpec.SECRET].orEmpty(),
                    supporting = stringResource(R.string.providers_field_secret_hint),
                    password = true,
                ) { secret ->
                    edit(
                        draft.copy(
                            extra = if (secret.isBlank()) draft.extra - CredentialSpec.SECRET else draft.extra + (CredentialSpec.SECRET to secret),
                        ),
                    )
                }
            }
            if (spec.model) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_model),
                    value = draft.model,
                    supporting = aiModelHint(type) ?: imageModelHint(type) ?: stringResource(
                        if (spec.modelRequired) R.string.providers_field_model_required_hint else R.string.providers_field_model_hint,
                    ),
                ) { edit(draft.copy(model = it)) }
                AiProviders.of(type.id)?.let { provider ->
                    ModelListButton(provider, draft) { model -> edit(draft.copy(model = model)) }
                }
            }
            if (type === ProviderTypes.LANGUAGETOOL) LanguageToolExtras(draft, ::edit)
            if (home) {
                HomeProviderExtras(
                    repository = repository,
                    type = type,
                    draft = draft,
                    isNew = target.isNew,
                    onDraft = ::edit,
                    onSave = { onSave(it.trimmed()) },
                    onDismiss = onDismiss,
                )
            }
            if (usedBy.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.providers_used_by,
                        usedBy.joinToString(", ") { context.getString(it.labelRes) },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            // The cheapest request the provider answers, with what is on
            // screen now: a typo in a host name shows here, not in the panel.
            val probe = ProviderProbes.probe(context, tool, type, draft.trimmed())
            if (probe != null && ready) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    OutlinedButton(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                val failure = runCatching { probe() }.exceptionOrNull()
                                testing = false
                                testResult = if (failure == null) {
                                    true to context.getString(R.string.providers_test_ok)
                                } else {
                                    false to context.getString(
                                        R.string.providers_test_failed,
                                        ToolHttp.friendlyMessage(context, failure),
                                    )
                                }
                            }
                        },
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.providers_testing))
                        } else {
                            Text(stringResource(R.string.providers_test))
                        }
                    }
                }
                testResult?.let { (ok, text) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
                        Icon(
                            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (onRemove != null) {
                    TextButton(onClick = onRemove) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.providers_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                // A kind with nothing to set up only has its description and Remove here.
                if (spec.none) {
                    TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
                    Button(enabled = ready, onClick = { onSave(draft.trimmed()) }) {
                        Text(stringResource(if (target.isNew) CommonR.string.common_add else CommonR.string.common_save))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * What a blank model means for an AI service: the default the client falls
 * back to, named from the client's own list so the two cannot drift apart.
 * Null for a kind that is not an AI service.
 */
@Composable
/** Asks [provider] for its models with what the form holds, and offers them in a sheet (#1038). */
@Composable
private fun ModelListButton(provider: AiProvider, draft: ProviderCredential, onPick: (String) -> Unit) {
    if (provider == AiProvider.ON_DEVICE || provider == AiProvider.BRAVE) return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var models by remember { mutableStateOf<List<String>?>(null) }
    TextButton(
        onClick = {
            val config = AiClient.Config(provider, draft.apiKey.trim(), draft.model.trim(), draft.url.trim())
            scope.launch {
                val found = withContext(Dispatchers.IO) { runCatching { AiClient.listModels(config) }.getOrNull() }
                if (found.isNullOrEmpty()) {
                    Toast.makeText(context, R.string.toolai_ai_model_list_failed, Toast.LENGTH_LONG).show()
                } else {
                    models = found
                }
            }
        },
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Text(stringResource(R.string.toolai_ai_model_list_action)) }
    models?.let { list ->
        ChoiceSheet(
            title = stringResource(R.string.providers_field_model),
            subtitle = null,
            options = list.map { it to it },
            selected = draft.model,
            detail = null,
            onPick = {
                models = null
                onPick(it)
            },
            onDismiss = { models = null },
        )
    }
}

private fun aiModelHint(type: ProviderType): String? {
    val default = when (AiProviders.of(type.id)) {
        AiProvider.ANTHROPIC -> AiClient.DefaultModels.ANTHROPIC
        AiProvider.OPENAI -> AiClient.DefaultModels.OPENAI
        AiProvider.GEMINI -> AiClient.DefaultModels.GEMINI
        AiProvider.XAI -> AiClient.DefaultModels.XAI
        AiProvider.DEEPSEEK -> AiClient.DefaultModels.DEEPSEEK
        AiProvider.OLLAMA -> AiClient.DefaultModels.OLLAMA
        AiProvider.LM_STUDIO -> return stringResource(R.string.toolai_ai_lm_studio_model_hint)
        AiProvider.OPENAI_COMPATIBLE -> return stringResource(R.string.toolai_ai_compatible_model_hint)
        else -> return null
    }
    return stringResource(R.string.toolai_ai_model_hint, default)
}

/**
 * What a blank model means for a sticker generator engine: the model the
 * client asks for then, named from the client's own defaults, or that the
 * service (or server) picks. Null for a kind that is not an image engine.
 */
@Composable
private fun imageModelHint(type: ProviderType): String? {
    if (ProviderTool.IMAGE_GEN !in type.serves || type.onDevice) return null
    val default = OnlineImageGen.defaultModel(type.id)
    return when {
        default != null -> stringResource(R.string.providers_field_model_image_hint, default)
        type === ProviderTypes.COMFYUI || type === ProviderTypes.AUTOMATIC1111 -> stringResource(R.string.providers_field_model_checkpoint_hint)
        type.credential.modelRequired -> null
        else -> stringResource(R.string.providers_field_model_image_service_hint)
    }
}

/** Stored trimmed: a key or address pasted from a web page often arrives with a space on it. */
private fun ProviderCredential.trimmed() =
    copy(
        name = name.trim(),
        apiKey = apiKey.trim(),
        url = url.trim(),
        model = model.trim(),
        extra = extra.mapValues { (key, value) ->
            if (key == CredentialSpec.PATH || key == CredentialSpec.USERNAME || key == CredentialSpec.ACCOUNT || key == CredentialSpec.SECRET) {
                value.trim()
            } else {
                value
            }
        },
    )
