package com.wasimaster.wmkeyboard.app

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.settings.SettingsHistory
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.undoable
import kotlinx.coroutines.launch

/** The settings store, for the furniture every screen's frame draws. Null outside the nav host. */
internal val LocalSettingsRepository = staticCompositionLocalOf<SettingsRepository?> { null }

/**
 * A page's own undo (#1242): puts the page back the way it was when it was
 * opened, by taking back each change made while it was in front.
 *
 * While the page is resumed it is [SettingsHistory.currentPage], so every
 * change recorded meanwhile carries its route, whichever row or dialog made
 * it. The button shows only once there is something of this visit's to take
 * back, and the bar's undo puts it all back again. The visit's start is kept
 * across a trip to a page below and back, so what was changed before going
 * deeper still counts.
 */
@Composable
internal fun PageUndoButton() {
    val repository = LocalSettingsRepository.current ?: return
    val page = currentLinkRoute() ?: return
    if (page == HomeRoute || page == "search") return
    val context = LocalContext.current
    val history = repository.history
    val since = rememberSaveable(page) { System.currentTimeMillis() }
    LifecycleResumeEffect(page) {
        SettingsHistory.currentPage = page
        onPauseOrDispose { if (SettingsHistory.currentPage == page) SettingsHistory.currentPage = null }
    }
    LaunchedEffect(history) { history.load() }
    val batches by history.batches.collectAsStateWithLifecycle()
    // Above the early return: the bar's redo runs after the button has gone.
    val scope = rememberCoroutineScope()
    val count = batches.count { it.undoable && it.page == page && it.at >= since }
    if (count == 0) return
    val snackbar = LocalSettingsSnackbar.current
    val redo = stringResource(R.string.restore_redo_action)
    IconButton(
        onClick = {
            scope.launch {
                val result = repository.undoSettingsOnPage(page, since)
                if (result !is SettingsRepository.HistoryResult.Applied) return@launch
                val text = context.resources.getQuantityString(R.plurals.restore_undo_done, result.count, result.count)
                val undo = history.batches.value.firstOrNull()
                if (snackbar != null && undo != null && undo.kind == SettingsHistory.Kind.UNDO) {
                    snackbar.undo(text, redo) { scope.launch { repository.undoSettingsBatch(undo.id) } }
                }
            }
        },
    ) {
        Icon(
            Icons.Outlined.History,
            contentDescription = context.resources.getQuantityString(R.plurals.restore_page_undo_desc, count, count),
        )
    }
}
