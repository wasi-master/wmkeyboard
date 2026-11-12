package com.wasimaster.wmkeyboard.ime.board

import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.board.BoardEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the clipboard panel's Board view draws (#1201), owned by
 * [ClipBoardController] rather than `KeyboardUiState`, the way the file
 * manager's is: one bundle reaches the panel through
 * `ClipboardPanelActions.board`, so `KeyboardScreen` gains no parameter.
 */
data class ClipBoardUi(
    /** A WebDAV place is picked for the board; nothing about it shows until one is. */
    val configured: Boolean = false,
    /** The Board view is up in place of the history. */
    val open: Boolean = false,
    /** Live entries, newest first. */
    val entries: List<BoardEntry> = emptyList(),
    val syncing: Boolean = false,
    /** A change made here is still waiting to reach the board's folder. */
    val pending: Boolean = false,
    val syncedAtMs: Long = 0L,
    /** Why the last pass failed, a `SinkError` name, or empty. */
    val error: String = "",
    /** The list of this phone's clips to send from, in place of the board. */
    val sending: Boolean = false,
    /** Clips sent while that list was up, so each shows it went. */
    val sent: Set<Long> = emptySet(),
    val confirmDelete: BoardEntry? = null,
    val notice: ClipBoardNotice? = null,
)

enum class ClipBoardNotice { SENT, COPIED, REFUSED, DELETED }

sealed interface ClipBoardAction {
    data object Open : ClipBoardAction
    data object Close : ClipBoardAction
    data object Refresh : ClipBoardAction
    data object ShowSend : ClipBoardAction
    data object HideSend : ClipBoardAction
    data class Send(val clip: ClipItem) : ClipBoardAction

    /** Typed into the field, as a tap on a clip does. */
    data class Paste(val entry: BoardEntry) : ClipBoardAction

    /** Onto this phone's clipboard, where the history takes it in like any copy. */
    data class Copy(val entry: BoardEntry) : ClipBoardAction

    /** Into the clipboard's editor; saving there changes the entry on the board too. */
    data class Edit(val entry: BoardEntry) : ClipBoardAction
    data class AskDelete(val entry: BoardEntry) : ClipBoardAction
    data class AnswerDelete(val yes: Boolean) : ClipBoardAction
    data object NoticeDismiss : ClipBoardAction
}

/** What the Board view hands back; see `ClipboardPanelActions.board`. */
data class ClipBoardCallbacks(
    val state: StateFlow<ClipBoardUi> = MutableStateFlow(ClipBoardUi()),
    val onAction: (ClipBoardAction) -> Unit = {},
)
