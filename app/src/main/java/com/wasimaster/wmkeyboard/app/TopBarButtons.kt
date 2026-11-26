package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListBulleted
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Home
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Replay
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Restore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RestartAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Schedule
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsHistory
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository.HistoryResult
import com.wasimaster.wmkeyboard.core.settings.TopBarButton
import com.wasimaster.wmkeyboard.core.settings.undoable
import com.wasimaster.wmkeyboard.ime.KeyboardControls
import kotlinx.coroutines.launch

/*
 * Quick buttons in the settings top bar (#1579), and the undo and redo that
 * live there (#1568).
 *
 * Undo used to be three screens away (Backup & restore › Undo and restore
 * points), so fixing a wrong turn meant leaving the setting, undoing, and
 * finding the setting again. These buttons sit on every page instead, and
 * each one is the user's to turn on: the bar with search alone is the bar the
 * app always had, which is the default.
 *
 * Undo and redo do not just write the old value back. A change made on
 * another page is no use undone out of sight, so they go to where it was made
 * and flash the row, the way a search result or the new settings walk does.
 * The history learns which row a change came from as the row is pressed
 * ([SettingsHistory.markRow]); a change without one still lands on its page.
 */

/** The buttons the user keeps in the bar; see [com.wasimaster.wmkeyboard.core.settings.AppUiSettings.topBarButtons]. */
internal val LocalTopBarButtons = compositionLocalOf { setOf(TopBarButton.SEARCH) }

/** About › Settings app › Top bar buttons. */
internal const val TOP_BAR_ROUTE = "about/top_bar"

/** One button's room in the bar: Material's 48 dp touch target. */
private val TopBarButtonSize = 48.dp

/** How far back the bar's windowed undo reaches. */
private const val TEN_MINUTES_MS = 10 * 60 * 1000L

/**
 * How much more of the collapsed bar the buttons take than the furniture width
 * already allows for, which counts search: one button each for the rest, and
 * one fewer when search is off. Never below nothing.
 */
@Composable
internal fun topBarExtraWidth(route: String?): Dp {
    val shown = LocalTopBarButtons.current
    if (LocalSettingsCrumbTrail.current == null || route == SearchScreenRoute) return 0.dp
    val extra = shown.count { it != TopBarButton.SEARCH && !(it == TopBarButton.HOME && route == HomeRoute) } -
        (if (TopBarButton.SEARCH in shown) 0 else 1)
    return TopBarButtonSize * extra.coerceAtLeast(0)
}

/** The settings search screen's route, which carries no bar buttons. */
private const val SearchScreenRoute = "search"

/**
 * The buttons the user turned on, in [TopBarButton] order, all but search,
 * which the frame draws itself. Nothing outside the settings graph, and
 * nothing on the search screen.
 */
@Composable
internal fun TopBarQuickButtons(route: String?) {
    val shown = LocalTopBarButtons.current
    if (shown.all { it == TopBarButton.SEARCH }) return
    if (route == SearchScreenRoute) return
    val trail = LocalSettingsCrumbTrail.current ?: return
    val repository = LocalSettingsRepository.current ?: return
    val history = repository.history
    val context = LocalContext.current
    LaunchedEffect(history) { history.load() }
    val batches by history.batches.collectAsStateWithLifecycle()
    val actions = rememberHistoryActions(repository, redoShown = TopBarButton.REDO in shown)
    for (button in TopBarButton.entries) {
        if (button !in shown) continue
        when (button) {
            TopBarButton.UNDO -> UndoButton(actions, batches)
            TopBarButton.REDO -> {
                val canRedo = remember(batches) { history.lastRedoable() != null }
                BarButton(
                    Icons.AutoMirrored.Outlined.Redo,
                    stringResource(R.string.top_bar_redo_title),
                    dimmed = !canRedo,
                    onClick = actions::redo,
                )
            }
            TopBarButton.UNDO_TEN_MINUTES -> {
                val now = System.currentTimeMillis()
                val count = batches.count { it.undoable && it.at >= now - TEN_MINUTES_MS }
                BarButton(
                    Icons.Outlined.Replay,
                    stringResource(R.string.top_bar_undo_ten_minutes_title),
                    dimmed = count == 0,
                    onClick = actions::undoTenMinutes,
                )
            }
            TopBarButton.RESTORE_POINT -> RestorePointButton(repository)
            TopBarButton.HISTORY -> BarButton(
                Icons.Outlined.FormatListBulleted,
                stringResource(R.string.top_bar_history_title),
                onClick = actions::openHistory,
            )
            TopBarButton.RECENT_PAGES -> RecentPagesButton()
            TopBarButton.WHATS_NEW -> {
                val up = NewSettings.entries.isNotEmpty()
                BarButton(
                    Icons.Outlined.AutoAwesome,
                    stringResource(if (up) R.string.top_bar_whats_new_hide_desc else R.string.top_bar_whats_new_show_desc),
                    tint = if (up) NewSettingGold else LocalContentColor.current,
                ) { toggleWhatsNew(context) }
            }
            TopBarButton.HOME -> if (route != HomeRoute) {
                BarButton(Icons.Outlined.Home, stringResource(R.string.top_bar_home_title)) {
                    trail.path.firstOrNull()?.let(trail::goTo)
                }
            }
            TopBarButton.FORCE_RESTART -> ForceRestartButton()
            TopBarButton.SEARCH -> Unit
        }
    }
}

/**
 * Restarts the keyboard, after asking (#1579). The running keyboard does it
 * itself ([KeyboardControls.Host.restart]): it saves what it has learnt and
 * the clipboard first, then ends the process, which is also this app's, so
 * the settings close with it. With no keyboard running there is nothing to
 * restart, and the bar says so.
 */
@Composable
private fun ForceRestartButton() {
    val context = LocalContext.current
    val snackbar = LocalSettingsSnackbar.current
    var asking by remember { mutableStateOf(false) }
    BarButton(Icons.Outlined.RestartAlt, stringResource(R.string.top_bar_force_restart_title)) {
        val host = KeyboardControls.host
        if (host == null) {
            val text = context.getString(R.string.top_bar_force_restart_not_running)
            snackbar?.say(text) ?: Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        } else {
            asking = true
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.top_bar_force_restart_confirm_title)) },
            text = { Text(stringResource(R.string.top_bar_force_restart_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    KeyboardControls.host?.restart()
                }) { Text(stringResource(R.string.top_bar_force_restart_action)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
}

/** The new settings bar up, or put away; About's own row does the same (#970). */
private fun toggleWhatsNew(context: Context) {
    when {
        NewSettings.entries.isNotEmpty() -> NewSettings.dismiss(context)
        NewSettings.lastAdded.isEmpty() ->
            Toast.makeText(context, R.string.about_new_settings_none, Toast.LENGTH_SHORT).show()
        else -> NewSettings.replay(context)
    }
}

/**
 * A bar button that can also be held, which [androidx.compose.material3.IconButton]
 * cannot. [dimmed] draws it greyed while it has nothing to do; it still takes
 * the press and says so, rather than a tap that does nothing at all.
 */
@Composable
private fun BarButton(
    icon: ImageVector,
    description: String,
    dimmed: Boolean = false,
    tint: Color = LocalContentColor.current,
    longDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(TopBarButtonSize)
            .clip(CircleShape)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = description,
                onLongClickLabel = longDescription,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (dimmed) tint.copy(alpha = 0.38f) else tint)
    }
}

/**
 * Undo, with its menu on a hold: the last 10 minutes, a restore point, and the
 * change history, which is what the Backup screen's Undo group offers.
 */
@Composable
private fun UndoButton(actions: HistoryActions, batches: List<SettingsHistory.Batch>) {
    var menu by remember { mutableStateOf(false) }
    var points by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val tenMinutes = batches.count { it.undoable && it.at >= now - TEN_MINUTES_MS }
    Box {
        BarButton(
            Icons.AutoMirrored.Outlined.Undo,
            stringResource(R.string.restore_undo_last_title),
            dimmed = batches.none { it.undoable },
            longDescription = stringResource(R.string.top_bar_undo_more_desc),
            onLongClick = { menu = true },
            onClick = actions::undo,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.restore_undo_ten_minutes_title)) },
                leadingIcon = { Icon(Icons.Outlined.Replay, contentDescription = null) },
                trailingIcon = {
                    Text(
                        pluralStringResource(R.plurals.restore_undo_changes_count, tenMinutes, tenMinutes),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                enabled = tenMinutes > 0,
                onClick = {
                    menu = false
                    actions.undoTenMinutes()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.top_bar_restore_point_title)) },
                leadingIcon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                trailingIcon = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                onClick = {
                    menu = false
                    points = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.top_bar_history_title)) },
                leadingIcon = { Icon(Icons.Outlined.FormatListBulleted, contentDescription = null) },
                onClick = {
                    menu = false
                    actions.openHistory()
                },
            )
        }
        RestorePointMenu(actions.repository, expanded = points) { points = false }
    }
}

/** The restore points as a button of their own. */
@Composable
private fun RestorePointButton(repository: SettingsRepository) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarButton(Icons.Outlined.Restore, stringResource(R.string.top_bar_restore_point_title)) { open = true }
        RestorePointMenu(repository, expanded = open) { open = false }
    }
}

/**
 * The saved restore points, newest first, each going back through the same
 * question the Backup screen asks. With none saved, the way to save one.
 */
@Composable
private fun RestorePointMenu(repository: SettingsRepository, expanded: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val snackbar = LocalSettingsSnackbar.current
    val open = LocalOpenSettingLink.current
    val points by repository.history.points.collectAsStateWithLifecycle()
    var restoring by remember { mutableStateOf<SettingsHistory.RestorePoint?>(null) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (points.isEmpty()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.restore_points_empty)) },
                enabled = false,
                onClick = {},
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.restore_point_save_title)) },
                onClick = {
                    onDismiss()
                    open?.invoke(rowLink(context, "backup/history", R.string.restore_point_save_title))
                },
            )
        }
        for (point in points) {
            DropdownMenuItem(
                text = { Text(pointName(context, point), maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                onClick = {
                    onDismiss()
                    restoring = point
                },
            )
        }
    }
    restoring?.let { point ->
        RestoreDialog(repository, point, onDismiss = { restoring = null }) { result ->
            restoring = null
            val text = when (result) {
                is HistoryResult.Applied ->
                    context.resources.getQuantityString(R.plurals.restore_point_restored, result.count, result.count)
                HistoryResult.NothingToDo -> context.getString(R.string.restore_point_same)
                HistoryResult.Missing -> context.getString(R.string.restore_point_missing)
                HistoryResult.RolledBack -> context.getString(R.string.restore_rolled_back)
            }
            snackbar?.say(text) ?: Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
}

/** The pages visited lately (#1317), as a menu, without the one the bar is on. */
@Composable
private fun RecentPagesButton() {
    val context = LocalContext.current
    val open = LocalOpenSettingLink.current
    val links = LocalSettingLinks.current
    val here = currentLinkRoute()
    var menu by remember { mutableStateOf(false) }
    val visited = rememberRecentPages()
    val pages = remember(visited, here) {
        val hereLink = here?.let { SettingsFavourites.screenLink(it) }
        visited.filter { it.link != hereLink }.take(RecentPages.SHOWN)
    }
    Box {
        BarButton(Icons.Outlined.Schedule, stringResource(R.string.top_bar_recent_pages_title)) { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (pages.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.top_bar_recent_pages_empty)) },
                    enabled = false,
                    onClick = {},
                )
            }
            for (page in pages) {
                key(page.link) {
                    val title = remember(page, links) {
                        SettingsFavourites.title(page, links, context.resources, context.packageName)
                    }
                    DropdownMenuItem(
                        text = { Text(title, maxLines = 1) },
                        onClick = {
                            menu = false
                            open?.invoke(page.link)
                        },
                    )
                }
            }
        }
    }
}

/** The `wmkeyboard://` link to the row [titleRes] on the screen at [route]. */
private fun rowLink(context: Context, route: String, @StringRes titleRes: Int): String =
    SettingsFavourites.screenLink(route) + "?setting=" + context.resources.getResourceEntryName(titleRes)

/**
 * What the bar's undo buttons do, and the trip to the setting afterwards.
 * Built per screen: "here" is the page the bar is drawn on.
 */
internal class HistoryActions(
    val repository: SettingsRepository,
    private val context: Context,
    private val here: String?,
    private val open: ((String) -> Unit)?,
    private val snackbar: SettingsSnackbar?,
    private val redoShown: Boolean,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    private val history get() = repository.history

    /** Takes back the newest change, then goes to it. */
    fun undo() = launch work@{
        history.load()
        val target = history.lastUndoable()
        if (target == null) {
            say(context.getString(R.string.restore_nothing_to_undo))
            return@work
        }
        report(repository.undoSettingsBatch(target.id), target, offerRedo = true)
    }

    /** Puts back what the newest undo took back, then goes to it. */
    fun redo() = launch work@{
        val (result, redone) = repository.redoLastSettingsChange()
        if (result == HistoryResult.NothingToDo) {
            say(context.getString(R.string.top_bar_nothing_to_redo))
            return@work
        }
        report(result, redone.firstOrNull(), offerRedo = false)
    }

    /** Takes back the last 10 minutes, then goes to the newest change among them. */
    fun undoTenMinutes() = launch {
        history.load()
        val newest = history.undoableWithin(TEN_MINUTES_MS).firstOrNull()
        report(repository.undoSettingsWithin(TEN_MINUTES_MS), newest, offerRedo = true)
    }

    /** The change history on the Undo and restore points screen. */
    fun openHistory() {
        open?.invoke(rowLink(context, "backup/history", R.string.restore_history_group))
    }

    private fun report(result: HistoryResult, batch: SettingsHistory.Batch?, offerRedo: Boolean) {
        val text = when (result) {
            is HistoryResult.Applied ->
                context.resources.getQuantityString(R.plurals.restore_undo_done, result.count, result.count)
            HistoryResult.NothingToDo -> context.getString(R.string.restore_nothing_to_undo)
            HistoryResult.Missing -> context.getString(R.string.restore_point_missing)
            HistoryResult.RolledBack -> context.getString(R.string.restore_rolled_back)
        }
        if (result !is HistoryResult.Applied) {
            say(text)
            return
        }
        if (batch != null && goTo(batch)) {
            // This page's bar goes with it; the toast outlives the trip.
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            return
        }
        // Redo on the bar already: the snackbar need not offer it too.
        val undo = history.batches.value.firstOrNull()
        if (offerRedo && !redoShown && snackbar != null && undo != null && undo.kind == SettingsHistory.Kind.UNDO) {
            snackbar.undo(text, context.getString(R.string.restore_redo_action)) {
                launch { repository.undoSettingsBatch(undo.id) }
            }
        } else {
            say(text)
        }
    }

    /**
     * Shows where [batch] was made: its row flashed on this page, or its page
     * opened with the row flashed there. True when it left this page. A change
     * made outside the settings app has no page and goes nowhere.
     */
    private fun goTo(batch: SettingsHistory.Batch): Boolean {
        val page = batch.page ?: return false
        if (page == here) {
            val row = batch.row ?: return false
            // A name the history wrote from a resource of this app.
            @Suppress("DiscouragedApi")
            val id = context.resources.getIdentifier(row, "string", context.packageName)
            if (id != 0) SettingsHighlight.request(id, centre = true)
            return false
        }
        val link = open ?: return false
        link(SettingsFavourites.screenLink(page) + (batch.row?.let { "?setting=$it" } ?: ""))
        return true
    }

    private fun say(text: String) {
        snackbar?.say(text) ?: Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun rememberHistoryActions(repository: SettingsRepository, redoShown: Boolean): HistoryActions {
    val context = LocalContext.current
    val here = currentLinkRoute()
    val open = LocalOpenSettingLink.current
    val snackbar = LocalSettingsSnackbar.current
    val scope = rememberCoroutineScope()
    return remember(repository, context, here, open, snackbar, redoShown, scope) {
        HistoryActions(repository, context, here, open, snackbar, redoShown) { block -> scope.launch { block() } }
    }
}

/**
 * About › Settings app › Top bar buttons (#1579): one switch per button, in
 * the order the bar draws them.
 */
@Composable
internal fun TopBarButtonsSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val shown = settings.watch { it.appUi.topBarButtons }
    val defaults = SettingsDefaults.appUi.topBarButtons
    SettingsGroup(
        stringResource(R.string.top_bar_group),
        info = stringResource(R.string.top_bar_group_info),
    ) {
        for (button in TopBarButton.entries) {
            val (title, subtitle) = topBarButtonText(button)
            item(key = button.name) {
                ToggleSetting(
                    title,
                    stringResource(subtitle),
                    button in shown,
                    default = button in defaults,
                ) { on -> scope.launch { repository.setTopBarButton(button, on) } }
            }
        }
    }
}

/** A button's switch title and subtitle. */
internal fun topBarButtonText(button: TopBarButton): Pair<Int, Int> = when (button) {
    TopBarButton.UNDO -> R.string.top_bar_undo_title to R.string.top_bar_undo_subtitle
    TopBarButton.REDO -> R.string.top_bar_redo_title to R.string.top_bar_redo_subtitle
    TopBarButton.UNDO_TEN_MINUTES -> R.string.top_bar_undo_ten_minutes_title to R.string.top_bar_undo_ten_minutes_subtitle
    TopBarButton.RESTORE_POINT -> R.string.top_bar_restore_point_title to R.string.top_bar_restore_point_subtitle
    TopBarButton.HISTORY -> R.string.top_bar_history_title to R.string.top_bar_history_subtitle
    TopBarButton.RECENT_PAGES -> R.string.top_bar_recent_pages_title to R.string.top_bar_recent_pages_subtitle
    TopBarButton.WHATS_NEW -> R.string.top_bar_whats_new_title to R.string.top_bar_whats_new_subtitle
    TopBarButton.HOME -> R.string.top_bar_home_title to R.string.top_bar_home_subtitle
    TopBarButton.FORCE_RESTART -> R.string.top_bar_force_restart_title to R.string.top_bar_force_restart_subtitle
    TopBarButton.SEARCH -> R.string.top_bar_search_title to R.string.top_bar_search_subtitle
}
