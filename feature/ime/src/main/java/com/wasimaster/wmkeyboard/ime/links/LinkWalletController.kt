package com.wasimaster.wmkeyboard.ime.links

import android.content.Context
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.links.LinkWalletStore
import com.wasimaster.wmkeyboard.core.links.SavedLink
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Which of a link's two boxes the keys type into (#1214). */
enum class LinkTextField {
    /** The address. Enter checks it and moves on to the name. */
    URL,

    /** The name on the button. Enter keeps the link. */
    TITLE,
}

/**
 * The box the Link wallet types into, in `KeyboardUiState` like the to-do
 * list's, because the keyboard's capture path works on that state. [ref] is
 * the link being changed, 0 for a new one; [url] and [title] carry the other
 * box's words while this one has the keys.
 */
data class LinkTextEntry(
    val field: LinkTextField,
    val text: String = "",
    val ref: Long = 0L,
    val url: String = "",
    val title: String = "",
)

/** What the Undo bar would put back. */
data class LinkUndo(val index: Int, val link: SavedLink)

/** Everything the Link wallet panel draws; the controller's, like `TodoUi`. */
data class LinkWalletUi(
    /** The store is attached: false before the first unlock. */
    val available: Boolean = false,
    val links: List<SavedLink> = emptyList(),
    /** The link whose actions are showing after a hold, or null. */
    val held: Long? = null,
    /** A line under the box: why an address was not taken. */
    val note: Int? = null,
    val undo: LinkUndo? = null,
)

sealed interface LinkAction {
    /** A new link, its address box opening on the copied link if there is one. */
    data object StartAdd : LinkAction
    data class Open(val id: Long) : LinkAction
    data class Hold(val id: Long) : LinkAction
    data object Release : LinkAction
    data class Insert(val id: Long) : LinkAction
    data class Copy(val id: Long) : LinkAction
    data class Share(val id: Long) : LinkAction
    data class EditTitle(val id: Long) : LinkAction
    data class EditUrl(val id: Long) : LinkAction
    data class Move(val id: Long, val by: Int) : LinkAction
    data class Delete(val id: Long) : LinkAction

    /** The box's Next or Save chip: what Enter does. */
    data object TextNext : LinkAction
    data object TextCancel : LinkAction

    /** Puts the copied text in the open box. */
    data object PasteClip : LinkAction
    data object Undo : LinkAction
    data object UndoDismiss : LinkAction
}

/** What the panel hands back; one bundle, see `ToolHoldCallbacks.links`. */
data class LinkWalletCallbacks(
    val state: StateFlow<LinkWalletUi> = MutableStateFlow(LinkWalletUi()),
    val onAction: (LinkAction) -> Unit = {},
)

/**
 * The keyboard half of the Link wallet (#1214): links kept as buttons, a tap
 * opening one in the browser, a hold offering to type, copy or share it, to
 * rename it, change its address, move it or delete it. Holds the panel's
 * [LinkWalletUi] and turns its [LinkAction]s into changes to the
 * [LinkWalletStore]. Every store change runs on one worker thread. Built on
 * first use.
 */
internal class LinkWalletController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    interface Host {
        fun haptic()
        fun insertText(text: String)
        fun copyText(text: String)
        fun shareText(text: String)

        /** Opens [url] outside the keyboard; false when nothing on the phone can. */
        fun openUrl(url: String): Boolean

        /** The text a paste would put in, or "" when there is none or the clipboard is off. */
        fun clipText(): String

        /** Hands the keys to the panel's box, with what it should start with. */
        fun beginText(entry: LinkTextEntry)

        /** Gives the keys back to the field. */
        fun endText()

        /** What is in the panel's box right now. */
        fun currentText(): LinkTextEntry?
        fun toast(message: Int)
    }

    private val app = context.applicationContext

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1, "wmkb-links")
    private val _state = MutableStateFlow(LinkWalletUi())
    val state: StateFlow<LinkWalletUi> = _state

    val callbacks = LinkWalletCallbacks(state = state, onAction = ::dispatch)

    private var store: LinkWalletStore? = null
    private var undoJob: Job? = null

    fun onPanelOpen() {
        onWorker {
            val current = store ?: attach()
            // A restore rewrites the file under the keyboard; the stamp says so.
            current?.reloadIfChanged()
            publish()
        }
    }

    fun onPanelClose() {
        if (host.currentText() != null) host.endText()
        undoJob?.cancel()
        _state.update { it.copy(held = null, note = null, undo = null) }
    }

    /** Enter in the box: the address moves on to the name, the name keeps the link. */
    fun onTextEnter(entry: LinkTextEntry) = next(entry)

    @Suppress("detekt.CyclomaticComplexMethod")
    private fun dispatch(action: LinkAction) {
        when (action) {
            LinkAction.StartAdd -> {
                host.haptic()
                _state.update { it.copy(held = null, note = null) }
                val copied = LinkWalletStore.firstLinkIn(host.clipText()).orEmpty()
                host.beginText(LinkTextEntry(LinkTextField.URL, text = copied))
            }
            is LinkAction.Open -> {
                val link = find(action.id) ?: return
                host.haptic()
                _state.update { it.copy(held = null) }
                if (!host.openUrl(link.url)) host.toast(R.string.ime_links_no_app)
            }
            is LinkAction.Hold -> {
                host.haptic()
                if (host.currentText() != null) host.endText()
                _state.update { it.copy(held = if (it.held == action.id) null else action.id, note = null) }
            }
            LinkAction.Release -> _state.update { it.copy(held = null) }
            is LinkAction.Insert -> {
                val link = find(action.id) ?: return
                _state.update { it.copy(held = null) }
                host.insertText(link.url)
            }
            is LinkAction.Copy -> {
                val link = find(action.id) ?: return
                host.haptic()
                _state.update { it.copy(held = null) }
                host.copyText(link.url)
            }
            is LinkAction.Share -> {
                val link = find(action.id) ?: return
                _state.update { it.copy(held = null) }
                host.shareText(link.url)
            }
            is LinkAction.EditTitle -> {
                val link = find(action.id) ?: return
                _state.update { it.copy(note = null) }
                host.beginText(LinkTextEntry(LinkTextField.TITLE, link.title, link.id, url = link.url, title = link.title))
            }
            is LinkAction.EditUrl -> {
                val link = find(action.id) ?: return
                _state.update { it.copy(note = null) }
                host.beginText(LinkTextEntry(LinkTextField.URL, link.url, link.id, url = link.url, title = link.title))
            }
            is LinkAction.Move -> {
                host.haptic()
                change { it.move(action.id, action.by) }
            }
            is LinkAction.Delete -> {
                host.haptic()
                if (host.currentText()?.ref == action.id) host.endText()
                _state.update { it.copy(held = null) }
                change { s -> s.delete(action.id)?.let { (index, link) -> offerUndo(LinkUndo(index, link)) } }
            }
            LinkAction.TextNext -> host.currentText()?.let(::next)
            LinkAction.TextCancel -> {
                host.endText()
                _state.update { it.copy(note = null) }
            }
            LinkAction.PasteClip -> {
                val entry = host.currentText() ?: return
                val clip = host.clipText()
                if (clip.isEmpty()) return
                val text = if (entry.field == LinkTextField.URL) LinkWalletStore.firstLinkIn(clip) ?: clip.trim() else clip.trim()
                host.beginText(entry.copy(text = text))
            }
            LinkAction.Undo -> {
                val undo = _state.value.undo ?: return
                undoJob?.cancel()
                _state.update { it.copy(undo = null) }
                change { it.restore(undo.index, undo.link) }
            }
            LinkAction.UndoDismiss -> {
                undoJob?.cancel()
                _state.update { it.copy(undo = null) }
            }
        }
    }

    /**
     * The address box checks its words and hands over to the name, which
     * starts on the name the link had or, for a new one, on its host; the name
     * box keeps the link and gives the keys back.
     */
    private fun next(entry: LinkTextEntry) {
        when (entry.field) {
            LinkTextField.URL -> {
                if (entry.text.isBlank() && entry.ref == 0L) {
                    host.endText()
                    return
                }
                val url = LinkWalletStore.normalise(entry.text)
                if (url == null) {
                    host.haptic()
                    _state.update { it.copy(note = R.string.ime_links_not_a_link) }
                    return
                }
                _state.update { it.copy(note = null) }
                val title = entry.title.ifEmpty { LinkWalletStore.hostOf(url) }
                host.beginText(LinkTextEntry(LinkTextField.TITLE, title, entry.ref, url = url, title = title))
            }
            LinkTextField.TITLE -> {
                val url = entry.url
                host.endText()
                if (url.isEmpty()) return
                if (entry.ref == 0L) {
                    change { it.add(entry.text, url) }
                } else {
                    change { it.update(entry.ref, entry.text, url) }
                }
            }
        }
    }

    // ---- the store, on the worker ----------------------------------------

    private fun attach(): LinkWalletStore? {
        if (!DirectBoot.isUserUnlocked(app)) return null
        return LinkWalletStore(File(app.filesDir, LinkWalletStore.FILE_PATH)).also { store = it }
    }

    /** Runs [block] on the store, saves and redraws. */
    private fun change(block: (LinkWalletStore) -> Unit) {
        onWorker {
            val s = store ?: attach() ?: return@onWorker
            block(s)
            s.save()
            publish()
        }
    }

    private fun offerUndo(undo: LinkUndo) {
        _state.update { it.copy(undo = undo) }
        undoJob?.cancel()
        undoJob = scope.launch {
            delay(UNDO_MS)
            _state.update { if (it.undo === undo) it.copy(undo = null) else it }
        }
    }

    /** Rebuilds the list the panel draws. Worker only. */
    private fun publish() {
        val s = store
        if (s == null) {
            _state.update { it.copy(available = false, links = emptyList()) }
            return
        }
        val links = s.all()
        _state.update { ui ->
            ui.copy(available = true, links = links, held = ui.held?.takeIf { id -> links.any { it.id == id } })
        }
    }

    private fun find(id: Long): SavedLink? = _state.value.links.firstOrNull { it.id == id }

    private fun onWorker(block: suspend () -> Unit) {
        scope.launch(worker) { block() }
    }

    companion object {
        private const val UNDO_MS = 6_000L

        /** Longest either box takes; the store trims a name further. */
        const val TEXT_MAX = LinkWalletStore.MAX_URL_LENGTH
    }
}
