package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.IconButton
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Category
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FolderOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WorkOutline
import com.wasimaster.wmkeyboard.core.settings.LauncherFolder
import com.wasimaster.wmkeyboard.core.settings.launcherFolderById
import com.wasimaster.wmkeyboard.core.settings.launcherFolderOf
import com.wasimaster.wmkeyboard.ime.LauncherCell
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fullscreen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PictureInPictureAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerticalSplit
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.settings.LauncherOpenMode
import com.wasimaster.wmkeyboard.core.settings.LauncherSplitCombo
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Apps
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AppShortcut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.LauncherIconShape
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.AppCatalog
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LauncherActivity
import com.wasimaster.wmkeyboard.ime.LauncherApp
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.delay

/**
 * The app-launcher panel's callbacks, bundled into one object rather than
 * seven parameters on [KeyboardScreen]: that function's argument list already
 * flirts with the JVM's 64K method-size ceiling at its call site, and the
 * launcher was the straw that crossed it.
 */
@androidx.compose.runtime.Immutable
class LauncherPanelCallbacks(
    val onAppTap: (LauncherApp) -> Unit = {},
    val onOpenDetail: (LauncherApp) -> Unit = {},
    val onActivityTap: (LauncherActivity) -> Unit = {},
    /** Takes the app's [LauncherApp.key], as [onHideToggle] does. */
    val onPinToggle: (String) -> Unit = {},
    /** Puts the app on the toolbar or takes it off (#1159), by [LauncherApp.key]. */
    val onToolbarToggle: (String) -> Unit = {},
    val onAppInfo: (LauncherApp) -> Unit = {},
    val onDetailClose: () -> Unit = {},
    val onHideToggle: (String) -> Unit = {},
    /** Resolves an app's icon through the service's LRU cache, off-main. */
    val iconFor: suspend (LauncherApp) -> ImageBitmap? = { null },
    /** Opens one app in an explicit mode, from the hold menu. */
    val onAppOpen: (LauncherApp, LauncherOpenMode) -> Unit = { _, _ -> },
    val onComboTap: (LauncherSplitCombo) -> Unit = {},
    val onComboAdd: (LauncherSplitCombo) -> Unit = {},
    val onComboRemove: (LauncherSplitCombo) -> Unit = {},
    /** Whether split-screen launches can work here; see `AppLaunchModes`. */
    val splitAvailable: () -> Boolean = { false },
    /** Whether the device has a freeform window mode at all. */
    val freeformAvailable: () -> Boolean = { false },
    val folders: LauncherFolderCallbacks = LauncherFolderCallbacks(),
)

/** The app folders' half of [LauncherPanelCallbacks] (#919). */
@androidx.compose.runtime.Immutable
class LauncherFolderCallbacks(
    /** Opens a folder by id; null goes back to the whole grid. */
    val onOpen: (String?) -> Unit = {},
    /** A new folder holding the app, opened with its name box up. */
    val onNew: (LauncherApp) -> Unit = {},
    val onAddTo: (LauncherApp, String) -> Unit = { _, _ -> },
    val onTakeOut: (LauncherApp) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onRenameDone: () -> Unit = {},
    /** A folder one place earlier (-1) or later (+1). */
    val onMove: (String, Int) -> Unit = { _, _ -> },
    val onDelete: (String) -> Unit = {},
    val onGroupByCategory: () -> Unit = {},
)

/** What the hold menu is open on: an app, a saved split pair or a folder. */
private sealed interface LauncherMenuTarget {
    data class App(val app: LauncherApp) : LauncherMenuTarget
    data class Combo(val combo: LauncherSplitCombo) : LauncherMenuTarget
    data class Folder(val folder: LauncherFolder) : LauncherMenuTarget

    /** The folder list an app is being filed into. */
    data class FolderPick(val app: LauncherApp) : LauncherMenuTarget
}

/**
 * The app-launcher tool: a searchable grid of every launchable app, a
 * pinned/recents row, a row of saved split-screen pairs, and (long-press, from
 * any of them) a menu: open normally, in a floating window or in split screen,
 * start a pair, pin, file it in a folder (#919), or go to the app's page with
 * its hide and App-info actions and the activities inside it. Folders lead
 * the grid; a tap opens one, a hold renames, moves or removes it. Apps of a
 * work profile (#924) carry a briefcase and open in their own profile. The service owns the catalog and the
 * launches; this panel renders
 * [KeyboardUiState.launcherApps]/[KeyboardUiState.launcherDetail]
 * and reports taps.
 *
 * The menu and the pick-a-second-app step of a new pair are local state and
 * drawn inside the panel rather than in a popup window: both are over the
 * moment the panel closes, and a window of their own would bring the IME
 * popup focus and touch rules along for nothing.
 *
 * [iconFor] resolves one app's icon off the main thread through the service's
 * LRU cache — cells draw a neutral placeholder until theirs lands, so a cold
 * open never decodes a hundred adaptive icons on the composition clock.
 */
@Composable
internal fun AppLauncherPanel(
    state: KeyboardUiState,
    callbacks: LauncherPanelCallbacks,
    onQueryTap: () -> Unit,
) {
    var menu by remember { mutableStateOf<LauncherMenuTarget?>(null) }
    var pairing by remember { mutableStateOf<LauncherApp?>(null) }
    // Read once per panel open: both are device facts that a developer-option
    // flip is the only thing to change, and the next open picks that up.
    val splitAvailable = remember { callbacks.splitAvailable() }
    val freeformAvailable = remember { callbacks.freeformAvailable() }
    val detail = state.launcherDetail
    if (detail != null) {
        LauncherDetail(
            state, detail.app, callbacks.iconFor,
            callbacks.onActivityTap, callbacks.onPinToggle, callbacks.onAppInfo,
            callbacks.onHideToggle,
        )
        return
    }
    val pick: (LauncherApp) -> Unit = { app ->
        val first = pairing
        when {
            first == null -> callbacks.onAppTap(app)
            // A pair is two packages of the keyboard's own profile; a work
            // app cannot be started beside another with the split flag.
            app.otherProfile -> Unit
            // The first app again backs out rather than pairing an app with itself.
            first.packageName == app.packageName -> pairing = null
            else -> {
                callbacks.onComboAdd(LauncherSplitCombo(first.packageName, app.packageName))
                pairing = null
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LauncherGrid(
            state = state,
            iconFor = callbacks.iconFor,
            onAppTap = pick,
            onAppLongPress = { app -> if (pairing != null) pick(app) else menu = LauncherMenuTarget.App(app) },
            onQueryTap = onQueryTap,
            combos = if (splitAvailable && pairing == null) state.settings.launcher.combos else emptyList(),
            onComboTap = callbacks.onComboTap,
            onComboLongPress = { menu = LauncherMenuTarget.Combo(it) },
            pairing = pairing,
            onCancelPairing = { pairing = null },
            folders = callbacks.folders,
            onFolderLongPress = { menu = LauncherMenuTarget.Folder(it) },
        )
        when (val target = menu) {
            is LauncherMenuTarget.App -> AppMenu(
                state = state,
                app = target.app,
                iconFor = callbacks.iconFor,
                splitAvailable = splitAvailable,
                freeformAvailable = freeformAvailable,
                onDismiss = { menu = null },
                onOpen = { mode ->
                    menu = null
                    callbacks.onAppOpen(target.app, mode)
                },
                onPair = {
                    menu = null
                    pairing = target.app
                },
                onPinToggle = {
                    menu = null
                    callbacks.onPinToggle(target.app.key)
                },
                onToolbarToggle = {
                    menu = null
                    callbacks.onToolbarToggle(target.app.key)
                },
                onFolder = {
                    val home = state.settings.launcher.folders.launcherFolderOf(target.app.key)
                    if (home != null) {
                        menu = null
                        callbacks.folders.onTakeOut(target.app)
                    } else {
                        menu = LauncherMenuTarget.FolderPick(target.app)
                    }
                },
                onOpenPage = {
                    menu = null
                    callbacks.onOpenDetail(target.app)
                },
            )
            is LauncherMenuTarget.FolderPick -> FolderPickMenu(
                state = state,
                app = target.app,
                iconFor = callbacks.iconFor,
                onDismiss = { menu = null },
                onPick = { id ->
                    menu = null
                    callbacks.folders.onAddTo(target.app, id)
                },
                onNew = {
                    menu = null
                    callbacks.folders.onNew(target.app)
                },
                onGroup = {
                    menu = null
                    callbacks.folders.onGroupByCategory()
                },
            )
            is LauncherMenuTarget.Folder -> FolderMenu(
                state = state,
                folder = target.folder,
                onDismiss = { menu = null },
                onOpen = {
                    menu = null
                    callbacks.folders.onOpen(target.folder.id)
                },
                onRename = {
                    menu = null
                    callbacks.folders.onRename(target.folder.id)
                },
                onMove = { by ->
                    menu = null
                    callbacks.folders.onMove(target.folder.id, by)
                },
                onDelete = {
                    menu = null
                    callbacks.folders.onDelete(target.folder.id)
                },
            )
            is LauncherMenuTarget.Combo -> ComboMenu(
                state = state,
                combo = target.combo,
                iconFor = callbacks.iconFor,
                onDismiss = { menu = null },
                onOpen = {
                    menu = null
                    callbacks.onComboTap(target.combo)
                },
                onRemove = {
                    menu = null
                    callbacks.onComboRemove(target.combo)
                },
            )
            null -> Unit
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun LauncherGrid(
    state: KeyboardUiState,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onAppTap: (LauncherApp) -> Unit,
    onAppLongPress: (LauncherApp) -> Unit,
    onQueryTap: () -> Unit,
    combos: List<LauncherSplitCombo>,
    onComboTap: (LauncherSplitCombo) -> Unit,
    onComboLongPress: (LauncherSplitCombo) -> Unit,
    pairing: LauncherApp?,
    onCancelPairing: () -> Unit,
    folders: LauncherFolderCallbacks,
    onFolderLongPress: (LauncherFolder) -> Unit,
) {
    val launcher = state.settings.launcher
    val query = state.mediaQuery
    val hidden = launcher.hidden.toSet()
    val iconShape = launcherIconShape(launcher.iconShape)
    // Hidden apps drop out of the browsing grid only. A search still finds
    // them, dimmed, so hiding never makes an app unreachable from here.
    val apps = AppCatalog.filterApps(state.launcherApps, query)
        .let { found -> if (query.isEmpty()) found.filter { it.key !in hidden } else found }
    val sorted = AppCatalog.sortApps(
        apps,
        recentFirst = launcher.sortOrder ==
            com.wasimaster.wmkeyboard.core.settings.AppSortOrder.RECENT_FIRST,
        pinned = launcher.pinned,
        recents = launcher.recents,
    )
    // A search answers from every app, folders or not; browsing shows the
    // folders first, and inside an open one, its apps in the folder's order.
    val openFolder = launcher.folders.launcherFolderById(state.launcherView.folderOpen)
        .takeIf { query.isEmpty() }
    val cells = when {
        query.isNotEmpty() -> sorted.map { LauncherCell.App(it) }
        openFolder != null -> {
            val byKey = sorted.associateBy { it.key }
            openFolder.apps.mapNotNull { byKey[it] }.map { LauncherCell.App(it) }
        }
        else -> AppCatalog.arrangeGrid(sorted, launcher.folders)
    }
    val browsing = query.isEmpty() && !state.mediaSearchActive && openFolder == null
    // A search narrowed to one app opens it once the typing pauses (#1147).
    // Keyed on the query, so each key typed restarts the wait and a word still
    // being typed never launches half-way. Not while picking a pair's second
    // app: that tap means "pair with this", not "open it".
    val sole = if (launcher.openSoleMatch && query.isNotEmpty() && pairing == null) sorted.singleOrNull() else null
    if (sole != null) {
        val tap by rememberUpdatedState(onAppTap)
        LaunchedEffect(query, sole.key) {
            delay(SOLE_MATCH_SETTLE_MS)
            tap(sole)
        }
    }
    // Pinned first, then recent — deduplicated so a pinned app never shows
    // twice in the shortcut row. Hidden while searching: the grid is the answer.
    val shortcuts = if (browsing) {
        val byKey = state.launcherApps.associateBy { it.key }
        (launcher.pinned + if (launcher.recentsEnabled) launcher.recents else emptyList())
            .distinct()
            .filter { it !in hidden }
            .mapNotNull { byKey[it] }
    } else {
        emptyList()
    }
    // A pair whose app was uninstalled drops out of the row but stays saved,
    // so reinstalling the app brings the pair back. Pairs are the keyboard's
    // own profile's packages, never a work twin of the same name.
    val shownCombos = if (browsing && combos.isNotEmpty()) {
        val byPackage = state.launcherApps.filter { !it.otherProfile }.associateBy { it.packageName }
        combos.mapNotNull { combo ->
            val first = byPackage[combo.first] ?: return@mapNotNull null
            val second = byPackage[combo.second] ?: return@mapNotNull null
            Triple(combo, first, second)
        }
    } else {
        emptyList()
    }
    val onCellTap: (LauncherCell) -> Unit = { cell ->
        when (cell) {
            is LauncherCell.App -> onAppTap(cell.app)
            is LauncherCell.Folder -> folders.onOpen(cell.folder.id)
        }
    }

    PanelFocusTarget(PanelMode.APP_LAUNCHER, 1, 1, FocusRegion.SEARCH) { onQueryTap() }
    PanelFocusTarget(PanelMode.APP_LAUNCHER, shortcuts.size, shortcuts.size, FocusRegion.CHIPS) {
        shortcuts.getOrNull(it)?.let(onAppTap)
    }
    // An Adaptive grid's true column count is a layout fact; six is close
    // enough for arrow-key math on every phone width this ships on. A fixed
    // count is exact.
    val fixedColumns = launcher.gridColumns
        .takeIf { it != com.wasimaster.wmkeyboard.core.settings.LauncherToolSettings.AUTO_COLUMNS }
    PanelFocusTarget(PanelMode.APP_LAUNCHER, cells.size, fixedColumns ?: 6) {
        cells.getOrNull(it)?.let(onCellTap)
    }

    // Above every state, empty results included: a search that finds nothing
    // mid-pick must still show what the next tap is for and how to back out.
    Column(Modifier.fillMaxSize()) {
        if (pairing != null) PairingBanner(pairing.label, onCancelPairing)
        if (openFolder != null) {
            FolderHeader(
                folder = openFolder,
                index = launcher.folders.indexOf(openFolder),
                count = launcher.folders.size,
                renaming = state.launcherView.nameDraft != null,
                callbacks = folders,
            )
        }
        LauncherGridBody(
            state, iconFor, onCellTap, onAppLongPress, onFolderLongPress, cells, shortcuts, shownCombos,
            onComboTap, onComboLongPress, fixedColumns, iconShape, hidden,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun LauncherGridBody(
    state: KeyboardUiState,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onCellTap: (LauncherCell) -> Unit,
    onAppLongPress: (LauncherApp) -> Unit,
    onFolderLongPress: (LauncherFolder) -> Unit,
    cells: List<LauncherCell>,
    shortcuts: List<LauncherApp>,
    shownCombos: List<Triple<LauncherSplitCombo, LauncherApp, LauncherApp>>,
    onComboTap: (LauncherSplitCombo) -> Unit,
    onComboLongPress: (LauncherSplitCombo) -> Unit,
    fixedColumns: Int?,
    iconShape: Shape?,
    hidden: Set<String>,
) {
    val kb = LocalKbTheme.current
    val launcher = state.settings.launcher
    when {
        state.launcherLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Text(
                    stringResource(R.string.ime_launcher_loading),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        cells.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Apps,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .alpha(0.4f),
                    tint = kb.secondaryText,
                )
                Text(
                    stringResource(R.string.ime_launcher_empty),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            val motion = kb.motion.any
            // One clock for the grid's entrance, started in the composition
            // that first shows the grid so no cell draws a frame at full
            // strength first. Each cell reads it in its layer, offset by its
            // place in the stagger, so the entrance recomposes nothing.
            val entrance = remember { Animatable(if (motion) 0f else 1f) }
            LaunchedEffect(Unit) {
                entrance.animateTo(1f, tween(GRID_ENTRANCE_MS, easing = LinearEasing))
            }
            if (shownCombos.isNotEmpty()) {
                Text(
                    stringResource(R.string.ime_launcher_combos_label),
                    color = kb.secondaryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 14.dp, top = 2.dp),
                )
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowItemsIndexed(
                        shownCombos,
                        key = { _, (combo, _, _) -> "combo:${combo.first}/${combo.second}" },
                    ) { _, (combo, first, second) ->
                        ComboCell(
                            combo = combo,
                            first = first,
                            second = second,
                            shape = iconShape,
                            iconFor = iconFor,
                            onTap = { onComboTap(combo) },
                            onLongPress = { onComboLongPress(combo) },
                        )
                    }
                }
            }
            if (shortcuts.isNotEmpty()) {
                Text(
                    stringResource(
                        if (launcher.pinned.isEmpty()) R.string.ime_launcher_recents_label
                        else R.string.ime_launcher_pinned_label,
                    ),
                    color = kb.secondaryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 14.dp, top = 2.dp),
                )
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowItemsIndexed(shortcuts, key = { _, app -> app.key }) { _, app ->
                        ShortcutCell(
                            app = app,
                            pinned = app.key in launcher.pinned,
                            shape = iconShape,
                            iconFor = iconFor,
                            onTap = { onCellTap(LauncherCell.App(app)) },
                            onLongPress = { onAppLongPress(app) },
                        )
                    }
                }
            }
            LazyVerticalGrid(
                // Auto keeps the 68 dp cell of the default 42 dp icon and
                // widens it with a bigger icon, so labels keep their room.
                columns = if (fixedColumns != null) {
                    GridCells.Fixed(fixedColumns)
                } else {
                    GridCells.Adaptive(minSize = maxOf(68, launcher.iconSizeDp + 26).dp)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
            ) {
                itemsIndexed(cells, key = { _, cell -> cell.key }) { index, cell ->
                    // A search reflows the grid: the cells that stay glide
                    // to their new places, the rest fade.
                    val cellModifier = if (motion) {
                        Modifier
                            .animateItem(
                                fadeInSpec = tween(CELL_FADE_IN_MS),
                                placementSpec = spring(
                                    stiffness = Spring.StiffnessMediumLow,
                                    visibilityThreshold = IntOffset.VisibilityThreshold,
                                ),
                                fadeOutSpec = tween(CELL_FADE_OUT_MS),
                            )
                            .graphicsLayer {
                                val t = cellEntrance(entrance.value, index)
                                alpha = t
                                translationY = (1f - t) * CELL_RISE_DP.dp.toPx()
                            }
                    } else {
                        Modifier
                    }
                    when (cell) {
                        is LauncherCell.Folder -> FolderCell(
                            modifier = cellModifier,
                            folder = cell.folder,
                            apps = cell.apps,
                            showLabel = launcher.showLabels,
                            iconSizeDp = launcher.iconSizeDp,
                            shape = iconShape,
                            iconFor = iconFor,
                            onTap = { onCellTap(cell) },
                            onLongPress = { onFolderLongPress(cell.folder) },
                        )
                        is LauncherCell.App -> AppCell(
                            modifier = cellModifier,
                            app = cell.app,
                            showLabel = launcher.showLabels,
                            pinned = cell.app.key in launcher.pinned,
                            hidden = cell.app.key in hidden,
                            iconSizeDp = launcher.iconSizeDp,
                            shape = iconShape,
                            iconFor = iconFor,
                            onTap = { onCellTap(cell) },
                            // One meaning for a hold everywhere in this panel: the
                            // app's menu, whose last item is the app's page, so
                            // hiding keeps its door.
                            onLongPress = { onAppLongPress(cell.app) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * How far one cell is through the grid's entrance, 0 to 1, from the shared
 * clock's [progress]. Cells start [CELL_STAGGER_MS] apart, the spread capped
 * at [GRID_STAGGER_CAP_MS] so a long list does not keep the last cell waiting.
 */
private fun cellEntrance(progress: Float, index: Int): Float {
    val elapsed = progress * GRID_ENTRANCE_MS
    val delay = minOf(index * CELL_STAGGER_MS, GRID_STAGGER_CAP_MS)
    return ((elapsed - delay) / CELL_ENTER_MS).coerceIn(0f, 1f)
}

private const val CELL_STAGGER_MS = 15
private const val GRID_STAGGER_CAP_MS = 150
private const val CELL_ENTER_MS = 150f
private const val GRID_ENTRANCE_MS = GRID_STAGGER_CAP_MS + 150
private const val CELL_RISE_DP = 8
private const val CELL_FADE_IN_MS = 150
private const val CELL_FADE_OUT_MS = 120

private val RoundedIconShape = RoundedCornerShape(percent = 24)

/** The clip for [LauncherIconShape.SYSTEM] is none: the icon keeps its own mask. */
internal fun launcherIconShape(shape: LauncherIconShape): Shape? = when (shape) {
    LauncherIconShape.CIRCLE -> CircleShape
    LauncherIconShape.ROUNDED -> RoundedIconShape
    LauncherIconShape.SYSTEM -> null
}

/** One app's icon, resolved through the service cache, placeholder first. */
@Composable
private fun AppIcon(
    app: LauncherApp,
    sizeDp: Int,
    shape: Shape?,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
) {
    val kb = LocalKbTheme.current
    val icon by produceState<ImageBitmap?>(null, app.key, app.activityName) {
        value = iconFor(app)
    }
    // Capped at the size but square at any smaller width: eight columns of a
    // big icon on a narrow phone shrink the icon rather than stretching it
    // into an oval.
    val box = Modifier
        .sizeIn(maxWidth = sizeDp.dp, maxHeight = sizeDp.dp)
        .fillMaxWidth()
        .aspectRatio(1f)
    val bitmap = icon
    Box(box) {
        val fill = Modifier.fillMaxSize()
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = null,
                modifier = if (shape != null) fill.clip(shape) else fill,
            )
        } else {
            val placeholder = shape ?: RoundedIconShape
            Box(
                modifier = fill
                    .clip(placeholder)
                    .background(kb.chip, placeholder),
            )
        }
        if (app.otherProfile) WorkBadge(app.paused, Modifier.align(Alignment.BottomEnd))
    }
}

/**
 * The briefcase on a work app's icon (#924), drawn outside the icon's clip
 * so a circle cannot cut it off the way it would the system's corner badge.
 */
@Composable
private fun WorkBadge(paused: Boolean, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    Box(
        modifier = modifier
            .fillMaxSize(0.4f)
            .clip(CircleShape)
            .background(kb.popup, CircleShape)
            .padding(1.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.WorkOutline,
            contentDescription = stringResource(
                if (paused) R.string.ime_launcher_work_paused_desc else R.string.ime_launcher_work_desc,
            ),
            modifier = Modifier.fillMaxSize(),
            tint = kb.popupText,
        )
    }
}

/**
 * A folder in the grid (#919): up to four of its apps' icons, two by two, on
 * a tile the size of one app icon, and its name under it like an app's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("LongParameterList")
@Composable
private fun FolderCell(
    folder: LauncherFolder,
    apps: List<LauncherApp>,
    showLabel: Boolean,
    iconSizeDp: Int,
    shape: Shape?,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val tile = RoundedIconShape
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    feedback()
                    onLongPress()
                },
            )
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .sizeIn(maxWidth = iconSizeDp.dp, maxHeight = iconSizeDp.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(tile)
                .background(kb.chip, tile)
                .padding(4.dp),
        ) {
            val faces = apps.take(4)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (row in faces.chunked(2)) {
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (app in row) {
                            Box(Modifier.weight(1f)) {
                                AppIcon(app, sizeDp = iconSizeDp / 2, shape = shape, iconFor = iconFor)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (faces.size <= 2) Spacer(Modifier.weight(1f))
            }
        }
        if (showLabel) {
            Text(
                folder.name,
                color = kb.suggestionText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 3.dp, start = 2.dp, end = 2.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

/**
 * The bar over an open folder (#919): back to the whole grid, the name (a tap
 * types a new one), move the folder earlier or later, and remove it.
 */
@Composable
private fun FolderHeader(
    folder: LauncherFolder,
    index: Int,
    count: Int,
    renaming: Boolean,
    callbacks: LauncherFolderCallbacks,
) {
    val kb = LocalKbTheme.current
    val renameDesc = stringResource(R.string.ime_launcher_folder_rename_action)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderHeaderButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ime_launcher_folder_back_desc)) {
            callbacks.onOpen(null)
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !renaming, onClickLabel = renameDesc) { callbacks.onRename(folder.id) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = kb.toolbarIcon)
            Text(
                folder.name,
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .weight(1f, fill = false),
            )
            if (!renaming) {
                Icon(Icons.Outlined.Edit, contentDescription = renameDesc, modifier = Modifier.size(14.dp), tint = kb.toolbarIcon)
            }
        }
        if (index > 0) {
            FolderHeaderButton(Icons.Outlined.ChevronLeft, stringResource(R.string.ime_launcher_folder_move_earlier_action)) {
                callbacks.onMove(folder.id, -1)
            }
        }
        if (index in 0 until count - 1) {
            FolderHeaderButton(Icons.Outlined.ChevronRight, stringResource(R.string.ime_launcher_folder_move_later_action)) {
                callbacks.onMove(folder.id, 1)
            }
        }
        FolderHeaderButton(Icons.Outlined.Delete, stringResource(R.string.ime_launcher_folder_delete_action)) {
            callbacks.onDelete(folder.id)
        }
    }
}

@Composable
private fun FolderHeaderButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    IconHint(description) {
        IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp), tint = kb.toolbarIcon)
        }
    }
}

/**
 * The header's box while a folder's name is typed (#919), in place of the
 * search: the keys type into it, and the check (or Enter) keeps the name.
 */
@Composable
internal fun RowScope.LauncherFolderNameBar(draft: String, onDone: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val doneDesc = stringResource(R.string.ime_launcher_folder_rename_done_desc)
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(start = 6.dp, end = 4.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = kb.toolbarIcon)
        Spacer(Modifier.width(8.dp))
        SearchQueryText(
            query = draft,
            placeholder = stringResource(R.string.ime_launcher_folder_name_hint),
            active = true,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        IconHint(doneDesc) {
            IconButton(onClick = onDone, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Check, contentDescription = doneDesc, modifier = Modifier.size(16.dp), tint = kb.toolbarIcon)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(
    app: LauncherApp,
    showLabel: Boolean,
    pinned: Boolean,
    hidden: Boolean,
    iconSizeDp: Int,
    shape: Shape?,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Column(
        modifier = modifier
            .alpha(if (hidden || app.paused) 0.45f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    feedback()
                    onLongPress()
                },
            )
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(horizontal = 4.dp)) {
            AppIcon(app, sizeDp = iconSizeDp, shape = shape, iconFor = iconFor)
            if (pinned) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = stringResource(R.string.ime_launcher_pinned_label),
                    modifier = Modifier
                        .size(13.dp)
                        .align(Alignment.TopEnd),
                    tint = kb.toolCircleActiveIcon,
                )
            }
            if (hidden) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = stringResource(R.string.ime_launcher_hidden_label),
                    modifier = Modifier
                        .size(13.dp)
                        .align(Alignment.BottomEnd),
                    tint = kb.secondaryText,
                )
            }
        }
        if (showLabel) {
            Text(
                app.label,
                color = kb.suggestionText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 3.dp, start = 2.dp, end = 2.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutCell(
    app: LauncherApp,
    pinned: Boolean,
    shape: Shape?,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    feedback()
                    onLongPress()
                },
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            AppIcon(app, sizeDp = 36, shape = shape, iconFor = iconFor)
            if (pinned) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = null,
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.TopEnd),
                    tint = kb.toolCircleActiveIcon,
                )
            }
        }
        Text(
            app.label,
            color = kb.secondaryText,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(52.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** What the next tap does while a pair is being made, and the way out. */
@Composable
private fun PairingBanner(firstLabel: String, onCancel: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .frostGlass(kb, RoundedCornerShape(10.dp), FrostSurface.CHIPS)
            .background(kb.chip)
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.VerticalSplit,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = kb.toolbarIcon,
        )
        Text(
            stringResource(R.string.ime_launcher_pair_prompt, firstLabel),
            color = kb.suggestionText,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(
            stringResource(R.string.ime_launcher_pair_cancel),
            color = kb.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** A saved pair: the two icons overlapped, first in front, and its name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComboCell(
    combo: LauncherSplitCombo,
    first: LauncherApp,
    second: LauncherApp,
    shape: Shape?,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    feedback()
                    onLongPress()
                },
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(50.dp).height(36.dp)) {
            Box(
                Modifier
                    .size(30.dp)
                    .align(Alignment.BottomEnd),
            ) { AppIcon(second, sizeDp = 30, shape = shape, iconFor = iconFor) }
            Box(
                Modifier
                    .size(30.dp)
                    .align(Alignment.TopStart),
            ) { AppIcon(first, sizeDp = 30, shape = shape, iconFor = iconFor) }
        }
        Text(
            comboLabel(combo, first, second),
            color = kb.secondaryText,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(72.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun comboLabel(combo: LauncherSplitCombo, first: LauncherApp, second: LauncherApp): String =
    combo.name.ifBlank { stringResource(R.string.ime_launcher_combo_label, first.label, second.label) }

/**
 * The hold menu on one app. The three open modes lead; split screen and
 * making a pair are left out where split screen cannot work, and the
 * floating window says so when the device has no freeform mode to put it in
 * (it still opens, full screen). The app's page — screens, hide, App info —
 * closes the list, so everything a hold used to reach is still one step away.
 */
@Suppress("LongParameterList")
@Composable
private fun AppMenu(
    state: KeyboardUiState,
    app: LauncherApp,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    splitAvailable: Boolean,
    freeformAvailable: Boolean,
    onDismiss: () -> Unit,
    onOpen: (LauncherOpenMode) -> Unit,
    onPair: () -> Unit,
    onPinToggle: () -> Unit,
    onToolbarToggle: () -> Unit,
    onFolder: () -> Unit,
    onOpenPage: () -> Unit,
) {
    val launcher = state.settings.launcher
    val shape = launcherIconShape(launcher.iconShape)
    val pinned = app.key in launcher.pinned
    val onToolbar = app.key in launcher.toolbarApps
    val inFolder = launcher.folders.launcherFolderOf(app.key) != null
    LauncherMenuSheet(
        header = { AppIcon(app, sizeDp = 28, shape = shape, iconFor = iconFor) },
        title = app.label,
        onDismiss = onDismiss,
    ) {
        LauncherMenuItem(
            Icons.Outlined.Fullscreen,
            stringResource(R.string.ime_launcher_open_action),
        ) { onOpen(LauncherOpenMode.NORMAL) }
        LauncherMenuItem(
            Icons.Outlined.PictureInPictureAlt,
            stringResource(R.string.ime_launcher_open_floating_action),
            note = if (freeformAvailable) null else stringResource(R.string.ime_launcher_floating_unsupported),
        ) { onOpen(LauncherOpenMode.FLOATING) }
        // A work app opens through LauncherApps, which has no split-screen
        // flag to pass, so neither split item would do what it says.
        if (splitAvailable && !app.otherProfile) {
            LauncherMenuItem(
                Icons.Outlined.VerticalSplit,
                stringResource(R.string.ime_launcher_open_split_action),
            ) { onOpen(LauncherOpenMode.SPLIT) }
            LauncherMenuItem(
                Icons.Outlined.Link,
                stringResource(R.string.ime_launcher_pair_action),
                onClick = onPair,
            )
        }
        LauncherMenuItem(
            Icons.Outlined.PushPin,
            stringResource(if (pinned) R.string.ime_launcher_unpin_action else R.string.ime_launcher_pin_action),
            onClick = onPinToggle,
        )
        // Its own button on the toolbar (#1159).
        LauncherMenuItem(
            Icons.Outlined.AppShortcut,
            stringResource(
                if (onToolbar) R.string.ime_launcher_toolbar_remove_action else R.string.ime_launcher_toolbar_add_action,
            ),
            onClick = onToolbarToggle,
        )
        LauncherMenuItem(
            if (inFolder) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
            stringResource(
                if (inFolder) R.string.ime_launcher_folder_take_out_action else R.string.ime_launcher_folder_add_action,
            ),
            onClick = onFolder,
        )
        LauncherMenuItem(
            Icons.Outlined.MoreHoriz,
            stringResource(
                if (launcher.activityDrilldown) R.string.ime_launcher_page_action
                else R.string.ime_launcher_page_no_screens_action,
            ),
            onClick = onOpenPage,
        )
    }
}

/**
 * Add to folder (#919): the folders there are, a new one, and the one-tap
 * fallback for the home screen's folders, which no keyboard can read: a
 * folder per Play category for every app in no folder yet.
 */
@Suppress("LongParameterList")
@Composable
private fun FolderPickMenu(
    state: KeyboardUiState,
    app: LauncherApp,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onGroup: () -> Unit,
) {
    val launcher = state.settings.launcher
    LauncherMenuSheet(
        header = { AppIcon(app, sizeDp = 28, shape = launcherIconShape(launcher.iconShape), iconFor = iconFor) },
        title = stringResource(R.string.ime_launcher_folder_pick_title, app.label),
        onDismiss = onDismiss,
    ) {
        for (folder in launcher.folders) {
            LauncherMenuItem(Icons.Outlined.Folder, folder.name) { onPick(folder.id) }
        }
        LauncherMenuItem(Icons.Outlined.Add, stringResource(R.string.ime_launcher_folder_new_action), onClick = onNew)
        LauncherMenuItem(
            Icons.Outlined.Category,
            stringResource(R.string.ime_launcher_group_action),
            note = stringResource(R.string.ime_launcher_group_note),
            onClick = onGroup,
        )
    }
}

/** The hold menu on a folder (#919): open, rename, move, remove. */
@Suppress("LongParameterList")
@Composable
private fun FolderMenu(
    state: KeyboardUiState,
    folder: LauncherFolder,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val folders = state.settings.launcher.folders
    val index = folders.indexOfFirst { it.id == folder.id }
    LauncherMenuSheet(
        header = { Icon(Icons.Outlined.Folder, contentDescription = null, tint = LocalKbTheme.current.popupText) },
        title = folder.name,
        onDismiss = onDismiss,
    ) {
        LauncherMenuItem(Icons.Outlined.FolderOpen, stringResource(R.string.ime_launcher_folder_open_action), onClick = onOpen)
        LauncherMenuItem(Icons.Outlined.Edit, stringResource(R.string.ime_launcher_folder_rename_action), onClick = onRename)
        if (index > 0) {
            LauncherMenuItem(Icons.Outlined.ChevronLeft, stringResource(R.string.ime_launcher_folder_move_earlier_action)) {
                onMove(-1)
            }
        }
        if (index in 0 until folders.size - 1) {
            LauncherMenuItem(Icons.Outlined.ChevronRight, stringResource(R.string.ime_launcher_folder_move_later_action)) {
                onMove(1)
            }
        }
        LauncherMenuItem(
            Icons.Outlined.Delete,
            stringResource(R.string.ime_launcher_folder_delete_action),
            note = stringResource(R.string.ime_launcher_folder_delete_note),
            onClick = onDelete,
        )
    }
}

/** The hold menu on a saved pair: open it, or remove it. */
@Composable
private fun ComboMenu(
    state: KeyboardUiState,
    combo: LauncherSplitCombo,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val byPackage = state.launcherApps.filter { !it.otherProfile }.associateBy { it.packageName }
    val first = byPackage[combo.first]
    val second = byPackage[combo.second]
    val shape = launcherIconShape(state.settings.launcher.iconShape)
    LauncherMenuSheet(
        header = { if (first != null) AppIcon(first, sizeDp = 28, shape = shape, iconFor = iconFor) },
        title = if (first != null && second != null) {
            comboLabel(combo, first, second)
        } else {
            combo.name.ifBlank { "${combo.first} + ${combo.second}" }
        },
        onDismiss = onDismiss,
    ) {
        LauncherMenuItem(
            Icons.Outlined.VerticalSplit,
            stringResource(R.string.ime_launcher_combo_open_action),
            onClick = onOpen,
        )
        LauncherMenuItem(
            Icons.Outlined.Delete,
            stringResource(R.string.ime_launcher_combo_remove_action),
            onClick = onRemove,
        )
    }
}

/**
 * A menu drawn inside the panel over a scrim that closes it. Scrolls when the
 * keyboard is short: the app menu runs to six rows.
 */
@Composable
private fun LauncherMenuSheet(
    header: @Composable () -> Unit,
    title: String,
    onDismiss: () -> Unit,
    items: @Composable ColumnScope.() -> Unit,
) {
    val kb = LocalKbTheme.current
    val closeLabel = stringResource(R.string.ime_launcher_menu_close)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(kb.board.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = closeLabel,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(kb.popup)
                // Swallows taps between rows so they do not reach the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp)) { header() }
                Text(
                    title,
                    color = kb.popupText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            items()
        }
    }
}

@Composable
private fun LauncherMenuItem(
    icon: ImageVector,
    label: String,
    note: String? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = kb.popupText)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, color = kb.popupText, fontSize = 13.sp)
            if (note != null) {
                Text(
                    note,
                    color = kb.popupText.copy(alpha = 0.65f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/**
 * The app's page: its header card (icon, label, package) with the pin, hide
 * and App-info actions, and — behind **Open screens inside apps** — every
 * activity it declares, exported first, the rest dimmed with a lock and shown
 * only behind a second setting. With the screens switch off the page is the
 * actions alone; it stays reachable either way, because hiding an app has no
 * other door.
 */
@Composable
private fun LauncherDetail(
    state: KeyboardUiState,
    app: LauncherApp,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onActivityTap: (LauncherActivity) -> Unit,
    onPinToggle: (String) -> Unit,
    onAppInfo: (LauncherApp) -> Unit,
    onHideToggle: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val detail = state.launcherDetail ?: return
    val launcher = state.settings.launcher
    val shown = if (launcher.activityDrilldown) {
        detail.activities.filter { it.exported || launcher.showNonExported }
    } else {
        emptyList()
    }

    PanelFocusTarget(PanelMode.APP_LAUNCHER, shown.size, 1) {
        shown.getOrNull(it)?.let(onActivityTap)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                app, sizeDp = 40, shape = launcherIconShape(launcher.iconShape), iconFor = iconFor,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    app.label,
                    color = kb.suggestionText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (app.otherProfile) {
                        stringResource(R.string.ime_launcher_work_package, app.packageName)
                    } else {
                        app.packageName
                    },
                    color = kb.secondaryText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val pinned = app.key in launcher.pinned
            IconHint(
                stringResource(
                    if (pinned) R.string.ime_launcher_unpin_action
                    else R.string.ime_launcher_pin_action,
                ),
            ) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = stringResource(
                        if (pinned) R.string.ime_launcher_unpin_action
                        else R.string.ime_launcher_pin_action,
                    ),
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { onPinToggle(app.key) }
                        .padding(8.dp),
                    tint = if (pinned) kb.toolCircleActiveIcon else kb.toolbarIcon,
                )
            }
            val hidden = app.key in launcher.hidden
            IconHint(
                stringResource(
                    if (hidden) R.string.ime_launcher_unhide_action
                    else R.string.ime_launcher_hide_action,
                ),
            ) {
                Icon(
                    if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = stringResource(
                        if (hidden) R.string.ime_launcher_unhide_action
                        else R.string.ime_launcher_hide_action,
                    ),
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { onHideToggle(app.key) }
                        .padding(8.dp),
                    tint = kb.toolbarIcon,
                )
            }
            IconHint(stringResource(R.string.ime_launcher_app_info_action)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.ime_launcher_app_info_action),
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { onAppInfo(app) }
                        .padding(8.dp),
                    tint = kb.toolbarIcon,
                )
            }
        }
        if (!launcher.activityDrilldown) return@Column
        Text(
            stringResource(R.string.ime_launcher_activities_label),
            color = kb.secondaryText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 14.dp, bottom = 2.dp),
        )
        if (detail.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            return@Column
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
            rowItemsIndexed(shown, key = { _, a -> "${a.packageName}/${a.className}" }) { _, activity ->
                val launchable = activity.exported && activity.enabled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onActivityTap(activity) }
                        .alpha(if (launchable) 1f else 0.45f)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            activity.label,
                            color = kb.suggestionText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            activity.className,
                            color = kb.secondaryText,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!launchable) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription =
                                stringResource(R.string.ime_launcher_not_exported_label),
                            modifier = Modifier
                                .size(14.dp)
                                .padding(start = 2.dp),
                            tint = kb.secondaryText,
                        )
                    }
                    Spacer(Modifier.width(0.dp))
                }
            }
        }
    }
}

/** How long the typing has to pause before a lone match opens; see [AppLauncherPanel]. */
private const val SOLE_MATCH_SETTLE_MS = 700L
