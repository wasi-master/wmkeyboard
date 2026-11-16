package com.wasimaster.wmkeyboard.ime.vault

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.files.DocFiles
import com.wasimaster.wmkeyboard.core.notes.vault.VaultMarkdown
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the vault's box is for (#1211). */
enum class VaultTextField {
    /** A new note's name; Enter makes it in the folder on show and opens it to write. */
    NAME,

    /** The open note's text below its front matter, edited in place. */
    BODY,

    /** A quick capture, added to the capture spot when it is sent. */
    CAPTURE,
}

/** The vault's box, in `KeyboardUiState` like the other tools' boxes. */
data class VaultTextEntry(val field: VaultTextField, val text: String = "")

/** A folder on the way down from the vault's top, for the trail of crumbs. */
data class VaultDir(val path: String, val name: String)

/** One row of a folder: a folder or a note. */
data class VaultEntry(val path: String, val name: String, val folder: Boolean, val modifiedMs: Long)

/** The note being read or edited. */
data class VaultNote(
    val path: String,
    val fileName: String,
    val title: String,
    val frontMatter: String,
    val body: String,
    val tags: List<String>,
    /** The file's stamp when it was read, to notice a sync changing it underneath. */
    val modifiedMs: Long,
    /** Too long to edit in the keyboard's box; it can still be read and typed. */
    val tooLong: Boolean = false,
)

/** Everything the vault view draws; the controller's, like `TodoUi`. */
data class VaultUi(
    /** A folder is chosen and its grant still holds. */
    val configured: Boolean = false,
    /** The folder's grant allows writing. */
    val writable: Boolean = true,
    val trail: List<VaultDir> = emptyList(),
    val entries: List<VaultEntry> = emptyList(),
    val loading: Boolean = false,
    val open: VaultNote? = null,
    /** The row whose actions are showing after a hold, by path. */
    val held: String? = null,
    val capture: VaultCaptureSpot? = null,
    /** A line about what went wrong or what just happened. */
    val message: Int? = null,
    val messageArg: String = "",
    /** The open note changed on disk since it was read, and the save waits on a choice. */
    val conflict: Boolean = false,
) {
    val folder: VaultDir? get() = trail.lastOrNull()
}

sealed interface VaultAction {
    data object PickFolder : VaultAction
    data object Forget : VaultAction
    data class Open(val entry: VaultEntry) : VaultAction
    data object Up : VaultAction
    data class Crumb(val index: Int) : VaultAction
    data object Refresh : VaultAction
    data class Hold(val path: String?) : VaultAction
    data object NewNote : VaultAction
    data object Edit : VaultAction
    data object CloseNote : VaultAction

    /** The open note's text typed into the field behind the keyboard. */
    data object Insert : VaultAction

    /** A wiki-link tapped in the open note. */
    data class Follow(val target: String) : VaultAction
    data class CaptureHere(val entry: VaultEntry) : VaultAction
    data object CaptureHereFolder : VaultAction
    data object StartCapture : VaultAction
    data object TextDone : VaultAction
    data object TextCancel : VaultAction

    /** The note changed elsewhere: write ours over it, or drop ours and read theirs. */
    data object Overwrite : VaultAction
    data object Reload : VaultAction
    data object DismissMessage : VaultAction
}

/** What the vault view hands back; one bundle, see `ToolHoldCallbacks.vault`. */
data class VaultCallbacks(
    val state: StateFlow<VaultUi> = MutableStateFlow(VaultUi()),
    val onAction: (VaultAction) -> Unit = {},
)

/**
 * The keyboard's way into an Obsidian or Zettel Notes vault (#1211): a folder
 * of Markdown files picked with Android's folder picker, browsed folder by
 * folder, a note read with its front matter kept out of sight and its
 * wiki-links followed, edited in place, and new notes and quick captures
 * written into it. The built-in notes are untouched; this is another place
 * notes come from.
 *
 * Every read and write is on one worker thread through the folder's grant
 * ([DocFiles] and the content resolver). Built on first use.
 */
internal class VaultController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    interface Host {
        fun haptic()
        fun insertText(text: String)

        /** Opens Android's folder picker through [com.wasimaster.wmkeyboard.app.VaultPickActivity]. */
        fun pickFolder()
        fun beginText(entry: VaultTextEntry)
        fun endText()
        fun currentText(): VaultTextEntry?
    }

    private val app = context.applicationContext

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1, "wmkb-vault")
    private val _state = MutableStateFlow(VaultUi())
    val state: StateFlow<VaultUi> = _state

    val callbacks = VaultCallbacks(state = state, onAction = ::dispatch)

    private var listJob: Job? = null

    /** A folder picked while the view was open: the preferences say so, and the view lists it. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == VaultPrefs.KEY_TREE || key == null) onWorker { attach(reset = true) }
    }
    private var listening = false

    fun onPanelOpen() {
        // The preferences that say where the vault is are credential-encrypted,
        // and Android throws on opening them before the first unlock.
        if (!DirectBoot.isUserUnlocked(app)) {
            _state.value = VaultUi(message = R.string.ime_notes_unlock_info)
            return
        }
        if (!listening) {
            VaultPrefs.prefs(app).registerOnSharedPreferenceChangeListener(prefsListener)
            listening = true
        }
        onWorker { attach(reset = _state.value.trail.isEmpty()) }
    }

    fun onPanelClose() {
        if (host.currentText() != null) host.endText()
        _state.update { it.copy(held = null, message = null, conflict = false) }
    }

    /**
     * Back inside the view: the box, then the note, then up a folder. False
     * at the vault's top, so the panel itself goes back.
     */
    fun onBack(): Boolean {
        val ui = _state.value
        when {
            host.currentText() != null -> dispatch(VaultAction.TextCancel)
            ui.open != null -> dispatch(VaultAction.CloseNote)
            ui.trail.size > 1 -> dispatch(VaultAction.Up)
            else -> return false
        }
        return true
    }

    /** Enter in the box: a name makes the note. A note's text and a capture take Enter as a new line. */
    fun onTextEnter(entry: VaultTextEntry): Boolean {
        if (entry.field != VaultTextField.NAME) return false
        finishText(entry)
        return true
    }

    @Suppress("detekt.CyclomaticComplexMethod")
    private fun dispatch(action: VaultAction) {
        when (action) {
            VaultAction.PickFolder -> {
                host.haptic()
                host.pickFolder()
            }
            VaultAction.Forget -> {
                host.haptic()
                if (host.currentText() != null) host.endText()
                VaultPrefs.forget(app)
                _state.value = VaultUi()
            }
            is VaultAction.Open -> {
                _state.update { it.copy(held = null, message = null) }
                if (action.entry.folder) {
                    val trail = _state.value.trail + VaultDir(action.entry.path, action.entry.name)
                    list(trail)
                } else {
                    onWorker { openNote(action.entry.path, action.entry.name) }
                }
            }
            VaultAction.Up -> {
                val trail = _state.value.trail
                if (trail.size > 1) list(trail.dropLast(1))
            }
            is VaultAction.Crumb -> {
                val trail = _state.value.trail
                if (action.index in trail.indices && action.index < trail.lastIndex) list(trail.take(action.index + 1))
            }
            VaultAction.Refresh -> list(_state.value.trail)
            is VaultAction.Hold -> {
                if (action.path != null) host.haptic()
                _state.update { it.copy(held = if (it.held == action.path) null else action.path) }
            }
            VaultAction.NewNote -> {
                if (!_state.value.writable) return readOnly()
                _state.update { it.copy(held = null, message = null) }
                host.beginText(VaultTextEntry(VaultTextField.NAME))
            }
            VaultAction.Edit -> {
                val note = _state.value.open ?: return
                if (!_state.value.writable) return readOnly()
                if (note.tooLong) {
                    _state.update { it.copy(message = R.string.ime_vault_too_long) }
                    return
                }
                host.beginText(VaultTextEntry(VaultTextField.BODY, note.body))
            }
            VaultAction.CloseNote -> {
                if (host.currentText()?.field == VaultTextField.BODY) host.endText()
                _state.update { it.copy(open = null, conflict = false, message = null) }
            }
            VaultAction.Insert -> {
                val note = _state.value.open ?: return
                if (host.currentText() != null) host.endText()
                host.insertText(note.body.trim())
            }
            is VaultAction.Follow -> onWorker { follow(action.target) }
            is VaultAction.CaptureHere -> setCapture(VaultCaptureSpot(action.entry.path, action.entry.name, action.entry.folder))
            VaultAction.CaptureHereFolder -> _state.value.folder?.let { setCapture(VaultCaptureSpot(it.path, it.name, folder = true)) }
            VaultAction.StartCapture -> {
                if (!_state.value.writable) return readOnly()
                if (_state.value.capture == null) {
                    _state.update { it.copy(message = R.string.ime_vault_capture_unset) }
                    return
                }
                _state.update { it.copy(held = null, message = null) }
                host.beginText(VaultTextEntry(VaultTextField.CAPTURE))
            }
            VaultAction.TextDone -> host.currentText()?.let(::finishText)
            VaultAction.TextCancel -> {
                host.endText()
                _state.update { it.copy(conflict = false) }
            }
            VaultAction.Overwrite -> {
                val entry = host.currentText() ?: return
                onWorker { saveBody(entry.text, force = true) }
            }
            VaultAction.Reload -> {
                val note = _state.value.open ?: return
                host.endText()
                _state.update { it.copy(conflict = false) }
                onWorker { openNote(note.path, note.fileName) }
            }
            VaultAction.DismissMessage -> _state.update { it.copy(message = null) }
        }
    }

    private fun finishText(entry: VaultTextEntry) {
        when (entry.field) {
            VaultTextField.NAME -> {
                val name = VaultMarkdown.fileNameFor(entry.text)
                host.endText()
                if (name == null) return
                val dir = _state.value.folder ?: return
                onWorker { create(dir, name) }
            }
            VaultTextField.BODY -> onWorker { saveBody(entry.text, force = false) }
            VaultTextField.CAPTURE -> {
                val text = entry.text
                host.endText()
                if (text.isBlank()) return
                val spot = _state.value.capture ?: return
                onWorker { capture(spot, text) }
            }
        }
    }

    private fun readOnly() {
        _state.update { it.copy(message = R.string.ime_vault_read_only) }
    }

    private fun setCapture(spot: VaultCaptureSpot) {
        host.haptic()
        VaultPrefs.setCapture(app, spot)
        _state.update { it.copy(capture = spot, held = null, message = R.string.ime_vault_capture_set, messageArg = spot.name) }
    }

    // ---- on the worker ---------------------------------------------------

    /** Reads the preferences: the vault's top, or nothing chosen, or a grant Android took back. */
    private fun attach(reset: Boolean) {
        val tree = VaultPrefs.tree(app)
        if (tree == null) {
            _state.value = VaultUi()
            return
        }
        val granted = runCatching {
            app.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isReadPermission }
        }.getOrDefault(false)
        if (!granted) {
            _state.value = VaultUi(message = R.string.ime_vault_grant_lost)
            return
        }
        val treeUri = Uri.parse(tree)
        val root = runCatching { DocFiles.rootOf(treeUri) }.getOrNull()?.toString() ?: run {
            _state.value = VaultUi(message = R.string.ime_vault_grant_lost)
            return
        }
        val rootName = DocFiles.entry(app, root)?.name ?: treeUri.lastPathSegment?.substringAfterLast(':').orEmpty()
        _state.update {
            it.copy(configured = true, writable = VaultPrefs.writable(app), capture = VaultPrefs.capture(app))
        }
        val trail = if (reset || _state.value.trail.firstOrNull()?.path != root) listOf(VaultDir(root, rootName)) else _state.value.trail
        readFolder(trail)
    }

    private fun list(trail: List<VaultDir>) {
        if (trail.isEmpty()) return
        listJob?.cancel()
        listJob = scope.launch(worker) { readFolder(trail) }
    }

    private fun readFolder(trail: List<VaultDir>) {
        val dir = trail.last()
        _state.update { it.copy(trail = trail, loading = true) }
        val listed = DocFiles.list(app, dir.path)
        if (listed == null) {
            _state.update { it.copy(loading = false, entries = emptyList(), message = R.string.ime_vault_unreadable) }
            return
        }
        val entries = listed.filter { it.isDirectory || VaultMarkdown.isNote(it.name) }
            .map { VaultEntry(it.path, it.name, it.isDirectory, it.modifiedMs) }
        _state.update { it.copy(loading = false, entries = entries) }
    }

    private fun openNote(path: String, fileName: String) {
        val text = read(path)
        if (text == null) {
            _state.update { it.copy(message = R.string.ime_vault_unreadable) }
            return
        }
        val split = VaultMarkdown.split(text)
        val modified = DocFiles.entry(app, path)?.modifiedMs ?: 0L
        _state.update {
            it.copy(
                open = VaultNote(
                    path = path,
                    fileName = fileName,
                    title = VaultMarkdown.title(split.frontMatter, fileName),
                    frontMatter = split.frontMatter,
                    body = split.body,
                    tags = VaultMarkdown.tags(split.frontMatter),
                    modifiedMs = modified,
                    tooLong = split.body.length > EDIT_MAX,
                ),
                conflict = false,
            )
        }
    }

    private fun saveBody(body: String, force: Boolean) {
        val note = _state.value.open ?: return
        val now = DocFiles.entry(app, note.path)?.modifiedMs ?: 0L
        // A sync wrote the file while it was open: ask before writing over it.
        if (!force && note.modifiedMs != 0L && now != 0L && now != note.modifiedMs) {
            _state.update { it.copy(conflict = true) }
            return
        }
        val whole = VaultMarkdown.Split(note.frontMatter, body).joined()
        if (!write(note.path, whole)) {
            _state.update { it.copy(message = R.string.ime_vault_write_failed) }
            return
        }
        host.endText()
        val stamp = DocFiles.entry(app, note.path)?.modifiedMs ?: System.currentTimeMillis()
        _state.update { it.copy(open = note.copy(body = body, modifiedMs = stamp), conflict = false, message = R.string.ime_vault_saved) }
    }

    private fun create(dir: VaultDir, fileName: String) {
        val made = runCatching {
            DocumentsContract.createDocument(app.contentResolver, Uri.parse(dir.path), MIME_MARKDOWN, fileName)
        }.getOrNull()
        if (made == null) {
            _state.update { it.copy(message = R.string.ime_vault_write_failed) }
            return
        }
        readFolder(_state.value.trail)
        val name = DocFiles.entry(app, made.toString())?.name ?: fileName
        openNote(made.toString(), name)
        // Straight to writing it.
        host.beginText(VaultTextEntry(VaultTextField.BODY, ""))
    }

    private fun capture(spot: VaultCaptureSpot, text: String) {
        val ok = if (spot.folder) {
            val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(Date())
            val fileName = VaultMarkdown.fileNameFor(VaultMarkdown.captureName(text, stamp)) ?: "$stamp.md"
            val made = runCatching {
                DocumentsContract.createDocument(app.contentResolver, Uri.parse(spot.path), MIME_MARKDOWN, fileName)
            }.getOrNull()
            made != null && write(made.toString(), VaultMarkdown.appended("", text))
        } else {
            val existing = read(spot.path)
            existing != null && write(spot.path, VaultMarkdown.appended(existing, text))
        }
        _state.update {
            it.copy(message = if (ok) R.string.ime_vault_captured else R.string.ime_vault_write_failed, messageArg = spot.name)
        }
        if (ok) {
            if (_state.value.folder?.path == spot.path) readFolder(_state.value.trail)
            val open = _state.value.open
            if (open != null && open.path == spot.path) openNote(open.path, open.fileName)
        }
    }

    /**
     * A wiki-link's note: first in the folder on show, then anywhere in the
     * vault by its file name, the way Obsidian resolves a link with no path.
     */
    private suspend fun follow(target: String) {
        val wanted = target.substringAfterLast('/')
        val names = setOf("$wanted.md".lowercase(), wanted.lowercase(), "$wanted.markdown".lowercase(), "$wanted.txt".lowercase())
        val here = _state.value.entries.firstOrNull { !it.folder && it.name.lowercase() in names }
        if (here != null) {
            openNote(here.path, here.name)
            return
        }
        val root = _state.value.trail.firstOrNull()?.path ?: return
        _state.update { it.copy(loading = true) }
        val hit = runCatching { DocFiles.search(app, root, wanted) }.getOrDefault(emptyList())
            .firstOrNull { !it.isDirectory && it.name.lowercase() in names }
        _state.update { it.copy(loading = false) }
        if (hit == null) {
            _state.update { it.copy(message = R.string.ime_vault_link_missing, messageArg = target) }
            return
        }
        openNote(hit.path, hit.name)
    }

    private fun read(path: String): String? = runCatching {
        app.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > READ_MAX_BYTES) null else String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    /** Writes [text] over the file at [path]; "wt" truncates, and a provider that knows only "w" gets that. */
    private fun write(path: String, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val uri = Uri.parse(path)
        for (mode in arrayOf("wt", "w")) {
            val done = runCatching {
                app.contentResolver.openOutputStream(uri, mode)?.use { it.write(bytes) } != null
            }.getOrDefault(false)
            if (done) return true
        }
        return false
    }

    private fun onWorker(block: suspend () -> Unit) {
        scope.launch(worker) { block() }
    }

    companion object {
        private const val MIME_MARKDOWN = "text/markdown"

        /** A note longer than this is read, not edited: the box retypes it with every key. */
        const val EDIT_MAX = 20_000

        /** Longest file read at all. */
        private const val READ_MAX_BYTES = 2 * 1024 * 1024

        /** Longest a name or a capture gets in the box. */
        const val TEXT_MAX = EDIT_MAX
    }
}
