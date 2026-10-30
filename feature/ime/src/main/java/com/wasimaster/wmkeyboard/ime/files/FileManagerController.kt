package com.wasimaster.wmkeyboard.ime.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.wasimaster.wmkeyboard.app.AiChatPickActivity
import com.wasimaster.wmkeyboard.app.FilesPermissionActivity
import com.wasimaster.wmkeyboard.app.GalleryPickActivity
import com.wasimaster.wmkeyboard.app.SendPickActivity
import com.wasimaster.wmkeyboard.app.SpecialAccess
import com.wasimaster.wmkeyboard.app.SpecialAccessActivity
import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.files.FileOpProgress
import com.wasimaster.wmkeyboard.core.files.FileOpResult
import com.wasimaster.wmkeyboard.core.files.FileOps
import com.wasimaster.wmkeyboard.core.files.FilePlaces
import com.wasimaster.wmkeyboard.core.files.FileShortcut
import com.wasimaster.wmkeyboard.core.files.FileSort
import com.wasimaster.wmkeyboard.core.files.FileStore
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The keyboard half of the file manager: a picker for the tools that want a
 * file, and rename, copy, move and delete while it is up, so picking does not
 * mean leaving the field.
 *
 * Opened for one [FilePickRequest] over the panel that asked, and always goes
 * back to that panel. What is picked goes through the same queues Android's
 * picker fills, so the tools take it in the way they always did. Nothing runs
 * until a pick opens it, and a build without the grant ([FilePicks]) never does.
 */
internal class FileManagerController(
    context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {

    /** What the controller needs from the service, which owns the panels and the keys. */
    interface Host {
        fun haptic()

        /** Puts up [panel], or nothing when it is [PanelMode.NONE]. */
        fun returnTo(panel: PanelMode)

        /** Has the tools take in whatever was just queued for them. */
        fun takePicks()
        fun beginText(entry: FilesTextEntry)
        fun endText()
        fun currentText(): FilesTextEntry?

        /** Turns the built-in manager off, for "Always use Android's picker". */
        fun turnOff()
        fun kdeSendFailed()
    }

    private val app = context.applicationContext
    private val _state = MutableStateFlow(FileManagerUi())
    val state: StateFlow<FileManagerUi> = _state

    val callbacks = FileManagerCallbacks(state = state, onAction = ::dispatch)

    private var returnPanel = PanelMode.NONE
    private var open = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var opJob: Job? = null
    private var noticeJob: Job? = null

    /** The last folder for each kind of pick, so picking again starts where the last one ended. */
    private val lastViews = HashMap<Class<*>, FileView>()

    // ---- Lifecycle --------------------------------------------------------

    /** Takes [request], asked for from [from]; the panel opening next shows it. */
    fun start(request: FilePickRequest, from: PanelMode) {
        returnPanel = from
        _state.update {
            it.copy(
                request = request,
                view = lastViews[request.javaClass] ?: FileView.Places,
                selection = emptySet(),
                selecting = false,
                confirmDelete = false,
                query = null,
                searching = false,
                notice = null,
            )
        }
    }

    fun onPanelOpen() {
        open = true
        refreshAccess()
        load()
    }

    fun onPanelClose() {
        open = false
        endTextIfAny()
        loadJob?.cancel()
        searchJob?.cancel()
        _state.value.request?.let { lastViews[it.javaClass] = _state.value.view }
        // A copy under way finishes by itself. The clip stays too: going back
        // for another file should not drop what Copy was carrying.
        _state.update {
            it.copy(
                request = null,
                entries = emptyList(),
                selection = emptySet(),
                selecting = false,
                confirmDelete = false,
                query = null,
                searching = false,
                notice = null,
            )
        }
    }

    /** Back from somewhere, most likely the screen that holds the grant. */
    fun onKeyboardShown() {
        if (!open) return
        val had = _state.value.access
        refreshAccess()
        if (_state.value.access != had) load()
    }

    private fun refreshAccess() {
        _state.update { it.copy(access = FilePlaces.hasAccess(app)) }
    }

    /**
     * Back, one layer at a time: the text box, the delete question, the
     * selection, the search, up a folder, and last the panel that asked.
     */
    fun onBack() {
        val s = _state.value
        when {
            host.currentText() != null -> host.endText()
            s.confirmDelete -> _state.update { it.copy(confirmDelete = false) }
            s.selection.isNotEmpty() -> clearSelection()
            s.query != null -> clearSearch()
            s.view is FileView.Folder -> up(s.view.path)
            s.view is FileView.Recent -> show(FileView.Places)
            else -> close()
        }
    }

    private fun close() = host.returnTo(returnPanel)

    private fun up(path: String) {
        val atVolume = _state.value.volumes.any { it.path == path }
        val parent = File(path).parent
        show(if (atVolume || parent == null) FileView.Places else FileView.Folder(parent))
    }

    // ---- Listing ----------------------------------------------------------

    private fun show(view: FileView) {
        searchJob?.cancel()
        _state.update { it.copy(view = view, query = null, searching = false) }
        load()
    }

    private fun load() {
        val s = _state.value
        val request = s.request ?: return
        if (!s.access) return
        loadJob?.cancel()
        _state.update { it.copy(loading = true) }
        loadJob = scope.launch {
            val volumes = withContext(Dispatchers.IO) { FilePlaces.volumes(app) }
            when (val view = s.view) {
                FileView.Places -> {
                    val shortcuts = withContext(Dispatchers.IO) {
                        FileShortcut.entries.filter { FilePlaces.folderOf(it).isDirectory }
                    }
                    _state.update {
                        it.copy(volumes = volumes, shortcuts = shortcuts, entries = emptyList(), unreadable = false, loading = false)
                    }
                }
                FileView.Recent -> {
                    val recent = withContext(Dispatchers.IO) { FilePlaces.recent(app, request.mimes, s.showHidden) }
                    _state.update { it.copy(volumes = volumes, entries = recent, unreadable = false, loading = false) }
                }
                is FileView.Folder -> {
                    val listed = withContext(Dispatchers.IO) {
                        FileStore.list(File(view.path), s.sort, s.descending, s.showHidden, request.mimes, FilePlaces::mimeOf)
                    }
                    _state.update {
                        it.copy(
                            volumes = volumes,
                            entries = listed.orEmpty(),
                            unreadable = listed == null,
                            loading = false,
                        )
                    }
                }
            }
        }
    }

    private fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(query = null, searching = false) }
        load()
    }

    /** A search over the open folder, or everywhere from the top; a moment after the last key. */
    private fun setQuery(text: String) {
        searchJob?.cancel()
        val query = text.trim()
        if (query.length < MIN_SEARCH) {
            if (_state.value.query != null) clearSearch()
            return
        }
        val request = _state.value.request ?: return
        _state.update { it.copy(query = query, searching = true) }
        searchJob = scope.launch {
            delay(SEARCH_DELAY_MS)
            val s = _state.value
            val roots = s.folder?.let { listOf(File(it)) } ?: s.volumes.map { File(it.path) }
            val found = withContext(Dispatchers.IO) {
                roots.flatMap { root ->
                    FileStore.search(root, query, s.showHidden, request.mimes, mimeOf = FilePlaces::mimeOf)
                }.take(FileStore.SEARCH_CAP)
            }
            _state.update { it.copy(entries = found, searching = false) }
        }
    }

    // ---- Picking ----------------------------------------------------------

    private fun tap(entry: FileEntry) {
        val s = _state.value
        when {
            s.selecting -> toggle(entry)
            entry.isDirectory -> show(FileView.Folder(entry.path))
            s.request?.limit == 1 -> deliver(listOf(entry.path))
            else -> toggle(entry)
        }
    }

    private fun toggle(entry: FileEntry) {
        host.haptic()
        val s = _state.value
        val picked = entry.path in s.selection
        if (!picked && !s.selecting && s.selection.size >= (s.request?.limit ?: 0)) {
            notice(FileNotice.Kind.LIMIT, s.request?.limit ?: 0)
            return
        }
        val next = if (picked) s.selection - entry.path else s.selection + entry.path
        _state.update { it.copy(selection = next, selecting = it.selecting && next.isNotEmpty()) }
    }

    private fun clearSelection() = _state.update { it.copy(selection = emptySet(), selecting = false) }

    /** The picks to the tool that asked, through the queue Android's picker would have filled, and back to it. */
    private fun deliver(paths: List<String>) {
        val request = _state.value.request ?: return
        val uris = paths.map { FilePlaces.uriOf(app, it) }
        when (request) {
            is FilePickRequest.GalleryFiles, is FilePickRequest.GalleryMedia ->
                GalleryPickActivity.offer(described(uris) { GalleryPickActivity.describe(app, it) })
            FilePickRequest.SendFiles ->
                SendPickActivity.offer(described(uris) { SendPickActivity.describe(app, it) })
            is FilePickRequest.AiImages -> importForChat(uris, asFiles = false, request.room, request.keep)
            is FilePickRequest.AiFiles -> importForChat(uris, asFiles = true, request.room, request.keep)
            is FilePickRequest.KdeFiles ->
                if (KdeConnectHub.send(app, request.deviceId, uris, null) == 0) host.kdeSendFailed()
            FilePickRequest.QrPicture -> GalleryPickActivity.offerQr(uris.first())
            FilePickRequest.OcrPicture -> GalleryPickActivity.offerOcr(uris.first())
            FilePickRequest.CameraPicture -> GalleryPickActivity.offerCamera(uris.first())
        }
        close()
        host.takePicks()
    }

    private fun <T : Any> described(uris: List<Uri>, describe: (Uri) -> T): List<T> =
        uris.mapNotNull { uri -> runCatching { describe(uri) }.getOrNull() }

    /** The chat keeps its own copies, as it does of a pick from Android's picker; those take a moment. */
    private fun importForChat(uris: List<Uri>, asFiles: Boolean, room: Int, keep: Boolean) {
        scope.launch {
            val picks = withContext(Dispatchers.IO) { AiChatPickActivity.importPicks(app, uris, asFiles, room, keep) }
            AiChatPickActivity.offer(picks)
            host.takePicks()
        }
    }

    private fun useSystemPicker() {
        val request = _state.value.request ?: return
        close()
        runCatching { app.startActivity(request.systemIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun askPermission() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SpecialAccessActivity.intent(app, SpecialAccess.ALL_FILES)
        } else {
            Intent(app, FilesPermissionActivity::class.java)
        }
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ---- Changing files ---------------------------------------------------

    private fun selectionFiles(): List<File> = _state.value.selection.map(::File)

    private fun takeClip(move: Boolean) {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        _state.update { it.copy(clip = FileClip(paths, move), selection = emptySet(), selecting = false) }
    }

    private fun paste() {
        val s = _state.value
        val clip = s.clip ?: return
        val folder = s.folder ?: return
        val files = clip.paths.map(::File)
        val target = File(folder)
        _state.update { it.copy(clip = null) }
        if (clip.move) {
            runOp(FileOpKind.MOVE, files.size, FileNotice.Kind.MOVED) { FileOps.move(files, target, it) }
        } else {
            runOp(FileOpKind.COPY, files.size, FileNotice.Kind.COPIED) { FileOps.copy(files, target, it) }
        }
    }

    private fun delete() {
        val files = selectionFiles()
        _state.update { it.copy(confirmDelete = false, selection = emptySet(), selecting = false) }
        if (files.isEmpty()) return
        runOp(FileOpKind.DELETE, files.size, FileNotice.Kind.DELETED) { FileOps.delete(files, it) }
    }

    /** [work] on a background thread, its progress on the panel, the media library told what changed. */
    private fun runOp(
        kind: FileOpKind,
        total: Int,
        done: FileNotice.Kind,
        work: suspend ((FileOpProgress) -> Unit) -> FileOpResult,
    ) {
        if (opJob?.isActive == true) return
        _state.update { it.copy(op = FileOpUi(kind, 0, total)) }
        opJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    work { p -> _state.update { it.copy(op = FileOpUi(kind, p.done, p.total)) } }
                }
                withContext(Dispatchers.IO) { FilePlaces.scan(app, result.touched) }
                if (result.failed > 0) notice(FileNotice.Kind.FAILED, result.failed) else notice(done, result.done)
            } finally {
                _state.update { it.copy(op = null) }
                if (open) load()
            }
        }
    }

    private fun applyRename(name: String) {
        val file = selectionFiles().singleOrNull() ?: return
        val renamed = FileOps.rename(file, name)
        if (renamed == null) {
            notice(FileNotice.Kind.BAD_NAME)
            return
        }
        FilePlaces.scan(app, listOf(file.path, renamed.path))
        clearSelection()
        load()
    }

    private fun applyNewFolder(name: String) {
        val folder = _state.value.folder ?: return
        if (FileOps.newFolder(File(folder), name) == null) {
            notice(FileNotice.Kind.BAD_NAME)
            return
        }
        load()
    }

    // ---- Text -------------------------------------------------------------

    fun onText(entry: FilesTextEntry) {
        if (entry.field == FilesTextField.SEARCH) setQuery(entry.text)
    }

    fun onTextEnter(entry: FilesTextEntry) {
        host.endText()
        when (entry.field) {
            FilesTextField.SEARCH -> setQuery(entry.text)
            FilesTextField.RENAME -> applyRename(entry.text)
            FilesTextField.NEW_FOLDER -> applyNewFolder(entry.text)
        }
    }

    private fun endTextIfAny() {
        if (host.currentText() != null) host.endText()
    }

    private fun notice(kind: FileNotice.Kind, count: Int = 0) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = FileNotice(kind, count)) }
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            _state.update { it.copy(notice = null) }
        }
    }

    // ---- Dispatch ---------------------------------------------------------

    @Suppress("detekt.CyclomaticComplexMethod")
    private fun dispatch(action: FileAction) {
        when (action) {
            is FileAction.Tap -> tap(action.entry)
            is FileAction.Hold -> {
                _state.update { it.copy(selecting = true) }
                toggle(action.entry)
            }
            is FileAction.OpenFolder -> show(FileView.Folder(action.path))
            FileAction.OpenPlaces -> show(FileView.Places)
            FileAction.OpenRecent -> show(FileView.Recent)
            is FileAction.OpenShortcut -> show(FileView.Folder(FilePlaces.folderOf(action.shortcut).path))
            FileAction.ToggleExpanded -> _state.update { it.copy(expanded = !it.expanded) }
            is FileAction.SetSort -> setSort(action.sort)
            FileAction.ToggleHidden -> {
                _state.update { it.copy(showHidden = !it.showHidden) }
                load()
            }
            FileAction.AskPermission -> askPermission()
            FileAction.UseSystemPicker -> useSystemPicker()
            FileAction.AlwaysSystemPicker -> {
                host.turnOff()
                useSystemPicker()
            }
            FileAction.Add -> if (_state.value.canPick) deliver(_state.value.selection.toList())
            FileAction.ClearSelection -> clearSelection()
            FileAction.Copy -> takeClip(move = false)
            FileAction.Move -> takeClip(move = true)
            FileAction.Paste -> paste()
            FileAction.ClearClip -> _state.update { it.copy(clip = null) }
            FileAction.AskDelete -> _state.update { it.copy(confirmDelete = true) }
            is FileAction.AnswerDelete ->
                if (action.yes) delete() else _state.update { it.copy(confirmDelete = false) }
            FileAction.CancelOp -> opJob?.cancel()
            FileAction.Rename -> selectionFiles().singleOrNull()?.let {
                host.beginText(FilesTextEntry(FilesTextField.RENAME, it.name))
            }
            FileAction.NewFolder -> if (_state.value.folder != null) {
                host.beginText(FilesTextEntry(FilesTextField.NEW_FOLDER))
            }
            FileAction.SearchFocus ->
                host.beginText(FilesTextEntry(FilesTextField.SEARCH, _state.value.query.orEmpty()))
            FileAction.ClearSearch -> clearSearch()
            FileAction.TextDone -> host.currentText()?.let(::onTextEnter)
            FileAction.NoticeDismiss -> _state.update { it.copy(notice = null) }
            FileAction.Close -> close()
        }
    }

    /** The same column again flips its direction; another starts ascending, except dates, newest first. */
    private fun setSort(sort: FileSort) {
        _state.update {
            it.copy(
                sort = sort,
                descending = if (it.sort == sort) !it.descending else sort == FileSort.DATE,
            )
        }
        load()
    }

    companion object {
        const val TEXT_MAX = 255
        private const val MIN_SEARCH = 2
        private const val SEARCH_DELAY_MS = 300L
        private const val NOTICE_MS = 3_000L
    }
}
