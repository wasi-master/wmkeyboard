package com.wasimaster.wmkeyboard.ime.board

import android.content.Context
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.board.BoardEntry
import com.wasimaster.wmkeyboard.core.clipboard.board.BoardLocal
import com.wasimaster.wmkeyboard.core.clipboard.board.ClipBoard
import com.wasimaster.wmkeyboard.core.settings.BackupInstall
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.sync.ClipBoardSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The keyboard half of the shared clipboard board (#1201): the Board view in
 * the clipboard panel, and when it talks to the board's folder.
 *
 * Every change lands in this device's copy at once ([ClipBoardSync.change]),
 * so it shows and survives with no network, and a pass a moment later writes
 * it out. Passes also run when the Board view opens, and when the keyboard
 * comes up with a change still waiting or the board not read for a while,
 * which is how a phone that was offline sends what waited and reads what
 * others sent. Nothing runs while no place is picked for the board.
 */
internal class ClipBoardController(
    context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {

    interface Host {
        fun settings(): KeyboardSettings
        fun haptic()

        /** Unlocked and off the keyguard: the clipboard is behind both. */
        fun available(): Boolean

        /** Types [text] into the field, as a tap on a clip does. */
        fun paste(text: String)

        /** Puts [text] on the system clipboard. */
        fun copy(text: String)

        /** Opens the clipboard's editor on [text]; saving there comes back through [entryEdited]. */
        fun edit(text: String, entryId: String)
    }

    private val app = context.applicationContext
    private val _state = MutableStateFlow(ClipBoardUi())
    val state: StateFlow<ClipBoardUi> = _state

    val callbacks = ClipBoardCallbacks(state = state, onAction = ::dispatch)

    private var syncJob: Job? = null
    private var soonJob: Job? = null
    private var noticeJob: Job? = null

    /** A change arrived while a pass was running: one more pass once it ends. */
    private var again = false
    private var lastAttemptMs = 0L

    private fun location(): BackupLocation? = ClipBoardSync.location(host.settings())

    // ---- Lifecycle --------------------------------------------------------

    /** The clipboard panel opened: whether the board is set up, and what this device last saw of it. */
    fun onClipboardOpen() {
        val location = location()?.takeIf { host.available() }
        _state.update { it.copy(configured = location != null, open = it.open && location != null) }
        location ?: return
        scope.launch { publish(ClipBoardSync.load(app, location)) }
    }

    /** The clipboard panel closed: the Board view goes with it, and opens on the history next time. */
    fun onClipboardClose() {
        _state.update { it.copy(open = false, sending = false, sent = emptySet(), confirmDelete = null, notice = null) }
    }

    /**
     * The keyboard came up. A change still waiting goes out, and a board not
     * read for [STALE_MS] is read, both no more often than [RETRY_MS] apart
     * so a phone with no network does not try at every field.
     */
    fun onKeyboardShown() {
        val location = location() ?: return
        if (!host.available()) return
        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < RETRY_MS) return
        scope.launch {
            val local = ClipBoardSync.load(app, location)
            publish(local)
            if (local.pending || now - local.syncedAtMs > STALE_MS) syncNow()
        }
    }

    /** The clipboard's editor saved [text] over a clip opened from entry [entryId]. */
    fun entryEdited(entryId: String, text: String) {
        val location = location() ?: return
        scope.launch {
            val me = withContext(Dispatchers.IO) { BackupInstall.id(app) }
            val now = System.currentTimeMillis()
            publish(ClipBoardSync.change(app, location) { ClipBoard.edit(it, entryId, text, me, now) })
            syncSoon()
        }
    }

    private fun publish(local: BoardLocal) {
        _state.update {
            it.copy(
                entries = ClipBoard.live(local.entries),
                pending = local.pending,
                syncedAtMs = local.syncedAtMs,
                error = local.error,
            )
        }
    }

    // ---- Passes -----------------------------------------------------------

    private fun syncNow() {
        if (syncJob?.isActive == true) {
            again = true
            return
        }
        val location = location() ?: return
        lastAttemptMs = System.currentTimeMillis()
        _state.update { it.copy(syncing = true) }
        syncJob = scope.launch {
            try {
                publish(ClipBoardSync.sync(app, location, host.settings().autoBackup))
            } finally {
                _state.update { it.copy(syncing = false) }
            }
            if (again) {
                again = false
                syncNow()
            }
        }
    }

    /** A pass a moment after the last change, so a run of sends goes out as one. */
    private fun syncSoon() {
        soonJob?.cancel()
        soonJob = scope.launch {
            delay(SOON_MS)
            syncNow()
        }
    }

    // ---- Changes ----------------------------------------------------------

    private fun send(clip: ClipItem) {
        val location = location() ?: return
        host.haptic()
        val portable = ClipBoard.portableText(clip)
        if (portable == null) {
            notice(ClipBoardNotice.REFUSED)
            return
        }
        scope.launch {
            val (me, device) = withContext(Dispatchers.IO) { BackupInstall.id(app) to BackupInstall.deviceLabel(app) }
            val now = System.currentTimeMillis()
            var added = false
            val local = ClipBoardSync.change(app, location) { entries ->
                ClipBoard.add(entries, portable.first, portable.second, device, me, now)
                    ?.also { added = true }?.first ?: entries
            }
            publish(local)
            if (added) {
                _state.update { it.copy(sent = it.sent + clip.id) }
                notice(ClipBoardNotice.SENT)
                syncSoon()
            } else {
                notice(ClipBoardNotice.REFUSED)
            }
        }
    }

    private fun delete(entry: BoardEntry) {
        val location = location() ?: return
        _state.update { it.copy(confirmDelete = null) }
        scope.launch {
            val me = withContext(Dispatchers.IO) { BackupInstall.id(app) }
            val now = System.currentTimeMillis()
            publish(ClipBoardSync.change(app, location) { ClipBoard.delete(it, entry.id, me, now) })
            notice(ClipBoardNotice.DELETED)
            syncSoon()
        }
    }

    private fun notice(kind: ClipBoardNotice) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = kind) }
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            _state.update { it.copy(notice = null) }
        }
    }

    // ---- Dispatch ---------------------------------------------------------

    private fun dispatch(action: ClipBoardAction) {
        when (action) {
            ClipBoardAction.Open -> {
                val location = location() ?: return
                if (!host.available()) return
                host.haptic()
                _state.update { it.copy(open = true, sending = false, sent = emptySet()) }
                scope.launch {
                    publish(ClipBoardSync.load(app, location))
                    syncNow()
                }
            }
            ClipBoardAction.Close -> {
                host.haptic()
                _state.update { it.copy(open = false, sending = false, sent = emptySet(), confirmDelete = null) }
            }
            ClipBoardAction.Refresh -> {
                host.haptic()
                syncNow()
            }
            ClipBoardAction.ShowSend -> _state.update { it.copy(sending = true, sent = emptySet()) }
            ClipBoardAction.HideSend -> _state.update { it.copy(sending = false) }
            is ClipBoardAction.Send -> send(action.clip)
            is ClipBoardAction.Paste -> {
                host.haptic()
                host.paste(action.entry.text)
            }
            is ClipBoardAction.Copy -> {
                host.haptic()
                host.copy(action.entry.text)
                notice(ClipBoardNotice.COPIED)
            }
            is ClipBoardAction.Edit -> {
                _state.update { it.copy(open = false, sending = false) }
                host.edit(action.entry.text, action.entry.id)
            }
            is ClipBoardAction.AskDelete -> {
                host.haptic()
                _state.update { it.copy(confirmDelete = action.entry) }
            }
            is ClipBoardAction.AnswerDelete -> {
                val entry = _state.value.confirmDelete
                if (action.yes && entry != null) delete(entry) else _state.update { it.copy(confirmDelete = null) }
            }
            ClipBoardAction.NoticeDismiss -> _state.update { it.copy(notice = null) }
        }
    }

    companion object {
        private const val SOON_MS = 1_500L
        private const val NOTICE_MS = 2_500L
        private const val RETRY_MS = 2 * 60_000L
        private const val STALE_MS = 15 * 60_000L
    }
}
