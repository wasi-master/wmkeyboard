package com.wasimaster.wmkeyboard.ime.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.wasimaster.wmkeyboard.app.AiChatPickActivity
import com.wasimaster.wmkeyboard.app.ChannelFeatures
import com.wasimaster.wmkeyboard.app.FilesPermissionActivity
import com.wasimaster.wmkeyboard.app.FileTreePickActivity
import com.wasimaster.wmkeyboard.app.GalleryPickActivity
import com.wasimaster.wmkeyboard.app.SendPickActivity
import com.wasimaster.wmkeyboard.app.SpecialAccess
import com.wasimaster.wmkeyboard.app.SpecialAccessActivity
import com.wasimaster.wmkeyboard.core.files.DocFiles
import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.files.FileOpProgress
import com.wasimaster.wmkeyboard.core.files.FileOpResult
import com.wasimaster.wmkeyboard.core.files.FileOps
import com.wasimaster.wmkeyboard.core.files.FilePlaces
import com.wasimaster.wmkeyboard.core.files.FileShortcut
import com.wasimaster.wmkeyboard.core.files.FileSort
import com.wasimaster.wmkeyboard.core.files.FileStore
import com.wasimaster.wmkeyboard.core.files.FileTree
import com.wasimaster.wmkeyboard.core.files.FileTrees
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
 * file, and the Files tool of its own (#1267), with rename, copy, move and
 * delete either way, so neither means leaving the field.
 *
 * Opened for one [FilePickRequest] over the panel that asked, it always goes
 * back to that panel, and what is picked goes through the same queues
 * Android's picker fills. Opened as the tool, it closes to the keys. It lists
 * the phone's own storage by path when it has All files access, and the
 * folders added through Android's folder picker ([FileTrees]) in every build.
 * Nothing runs until it opens.
 */
internal class FileManagerController(
    context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {

    /** What the controller needs from the service, which owns the panels and the keys. */
    interface Host {
        fun haptic()

        /** Puts up [panel], or closes this one when it is [PanelMode.NONE]. */
        fun returnTo(panel: PanelMode)

        /** Has the tools take in whatever was just queued for them. */
        fun takePicks()
        fun beginText(entry: FilesTextEntry)
        fun endText()
        fun currentText(): FilesTextEntry?

        /** Turns the built-in manager off, for "Always use Android's picker". */
        fun turnOff()
        fun kdeSendFailed()

        /** Whether the manager shows two lists side by side; a setting, see `KeyboardSettings.filesTwoPanes`. */
        fun twoPanes(): Boolean
        fun setTwoPanes(on: Boolean)
    }

    private val app = context.applicationContext
    private val _state = MutableStateFlow(FileManagerUi())
    val state: StateFlow<FileManagerUi> = _state

    val callbacks = FileManagerCallbacks(state = state, onAction = ::dispatch)

    private var returnPanel = PanelMode.NONE
    private var open = false
    private val loadJobs = arrayOfNulls<Job>(2)
    private var searchJob: Job? = null
    private var opJob: Job? = null
    private var noticeJob: Job? = null

    /** The last folder for each kind of pick, so picking again starts where the last one ended. */
    private val lastViews = HashMap<Class<*>, FileView>()

    /** Where the Files tool's lists were when it closed. */
    private var browseViews: List<FileView> = emptyList()

    // ---- Lifecycle --------------------------------------------------------

    /** Takes [request], asked for from [from]; the panel opening next shows it. */
    fun start(request: FilePickRequest, from: PanelMode) {
        returnPanel = from
        val first = lastViews[request.javaClass] ?: FileView.Places
        reset(request = request, browsing = false, locked = false, views = listOf(first))
    }

    /**
     * Opens as the Files tool, with no pick to make. [locked] while the
     * keyguard is up, when the panel says to unlock and lists nothing.
     */
    fun startBrowse(locked: Boolean) {
        returnPanel = PanelMode.NONE
        reset(request = null, browsing = true, locked = locked, views = browseViews)
    }

    private fun reset(request: FilePickRequest?, browsing: Boolean, locked: Boolean, views: List<FileView>) {
        val count = if (host.twoPanes()) 2 else 1
        val panes = List(count) { FilePane(view = views.getOrNull(it) ?: FileView.Places) }
        _state.update {
            it.copy(
                request = request,
                browsing = browsing,
                locked = locked,
                panes = panes,
                active = 0,
                selection = emptySet(),
                selectedFolders = emptySet(),
                selecting = false,
                confirmDelete = false,
                confirmRemoveTree = null,
                query = null,
                searching = false,
                notice = null,
            )
        }
    }

    fun onPanelOpen() {
        open = true
        refreshAccess()
        loadAll()
    }

    fun onPanelClose() {
        open = false
        endTextIfAny()
        loadJobs.forEachIndexed { i, job -> job?.cancel(); loadJobs[i] = null }
        searchJob?.cancel()
        val s = _state.value
        s.request?.let { lastViews[it.javaClass] = s.view }
        if (s.browsing) browseViews = s.panes.map { it.view }
        // A copy under way finishes by itself. The clip stays too: going back
        // for another file should not drop what Copy was carrying.
        _state.update {
            it.copy(
                request = null,
                browsing = false,
                panes = it.panes.map { pane -> pane.copy(entries = emptyList(), loading = false) },
                selection = emptySet(),
                selectedFolders = emptySet(),
                selecting = false,
                confirmDelete = false,
                confirmRemoveTree = null,
                query = null,
                searching = false,
                notice = null,
            )
        }
    }

    /** Back from somewhere: the screen that holds the grant, or Android's folder picker. */
    fun onKeyboardShown() {
        if (!open) return
        refreshAccess()
        loadAll()
    }

    private fun refreshAccess() {
        _state.update {
            it.copy(
                canAskAccess = ChannelFeatures.FILE_MANAGER,
                // A Play build has no provider for paths, so it lists only added folders.
                access = ChannelFeatures.FILE_MANAGER && FilePlaces.hasAccess(app),
            )
        }
    }

    /**
     * Back, one layer at a time: the text box, a question, the selection, the
     * search, up a folder, and last the panel that asked (or the keys).
     */
    fun onBack() {
        val s = _state.value
        val view = s.view
        when {
            host.currentText() != null -> host.endText()
            s.confirmRemoveTree != null -> _state.update { it.copy(confirmRemoveTree = null) }
            s.confirmDelete -> _state.update { it.copy(confirmDelete = false) }
            s.selection.isNotEmpty() -> clearSelection()
            s.query != null -> clearSearch()
            view is FileView.Folder -> up(view)
            view is FileView.Recent -> show(FileView.Places)
            else -> close()
        }
    }

    private fun close() = host.returnTo(returnPanel)

    private fun up(view: FileView.Folder) {
        if (view.trail.isNotEmpty()) {
            show(
                if (view.trail.size > 1) {
                    FileView.Folder(view.trail[view.trail.size - 2].path, view.trail.dropLast(1))
                } else {
                    FileView.Places
                },
            )
            return
        }
        val atVolume = _state.value.volumes.any { it.path == view.path }
        val parent = File(view.path).parent
        show(if (atVolume || parent == null) FileView.Places else FileView.Folder(parent))
    }

    // ---- Listing ----------------------------------------------------------

    private fun updatePane(index: Int, change: (FilePane) -> FilePane) = _state.update { s ->
        if (index !in s.panes.indices) {
            s
        } else {
            s.copy(panes = s.panes.toMutableList().also { it[index] = change(it[index]) })
        }
    }

    /** [view] in the active list. */
    private fun show(view: FileView) {
        searchJob?.cancel()
        val index = _state.value.active
        _state.update { it.copy(query = null, searching = false) }
        updatePane(index) { it.copy(view = view) }
        load(index)
    }

    /** A folder opened from the active list: a document one carries its way down. */
    private fun openEntry(entry: FileEntry) {
        if (!DocFiles.isDoc(entry.path)) {
            show(FileView.Folder(entry.path))
            return
        }
        val trail = (_state.value.view as? FileView.Folder)?.trail.orEmpty()
        show(FileView.Folder(entry.path, trail + FileCrumb(entry.path, entry.name)))
    }

    /** A breadcrumb step or a volume: a document one is found on the trail it came down. */
    private fun openPath(path: String) {
        if (!DocFiles.isDoc(path)) {
            show(FileView.Folder(path))
            return
        }
        val s = _state.value
        val trail = (s.view as? FileView.Folder)?.trail.orEmpty()
        val at = trail.indexOfFirst { it.path == path }
        if (at >= 0) {
            show(FileView.Folder(path, trail.take(at + 1)))
            return
        }
        val name = s.entries.firstOrNull { it.path == path }?.name ?: Uri.parse(path).lastPathSegment.orEmpty()
        show(FileView.Folder(path, trail + FileCrumb(path, name)))
    }

    private fun openTree(tree: FileTree) = show(FileView.Folder(tree.root, listOf(FileCrumb(tree.root, tree.label))))

    private fun loadAll() {
        _state.value.panes.indices.forEach(::load)
    }

    private fun load(index: Int) {
        val s = _state.value
        if (s.request == null && !s.browsing) return
        if (s.locked) return
        val pane = s.panes.getOrNull(index) ?: return
        val mimes = s.request?.mimes.orEmpty()
        loadJobs[index]?.cancel()
        updatePane(index) { it.copy(loading = true) }
        loadJobs[index] = scope.launch {
            val access = s.access
            val volumes = if (access) withContext(Dispatchers.IO) { FilePlaces.volumes(app) } else emptyList()
            val trees = withContext(Dispatchers.IO) { FileTrees.list(app) }
            _state.update { it.copy(volumes = volumes, trees = trees) }
            when (val view = pane.view) {
                FileView.Places -> {
                    val shortcuts = if (access) {
                        withContext(Dispatchers.IO) { FileShortcut.entries.filter { FilePlaces.folderOf(it).isDirectory } }
                    } else {
                        emptyList()
                    }
                    _state.update { it.copy(shortcuts = shortcuts) }
                    updatePane(index) { it.copy(entries = emptyList(), unreadable = false, loading = false) }
                }
                FileView.Recent -> {
                    val recent = if (access) {
                        withContext(Dispatchers.IO) { FilePlaces.recent(app, mimes, s.showHidden) }
                    } else {
                        emptyList()
                    }
                    updatePane(index) { it.copy(entries = recent, unreadable = false, loading = false) }
                }
                is FileView.Folder -> {
                    val listed = withContext(Dispatchers.IO) {
                        when {
                            DocFiles.isDoc(view.path) ->
                                DocFiles.list(app, view.path, s.sort, s.descending, s.showHidden, mimes)
                            access ->
                                FileStore.list(File(view.path), s.sort, s.descending, s.showHidden, mimes, FilePlaces::mimeOf)
                            else -> null
                        }
                    }
                    updatePane(index) { it.copy(entries = listed.orEmpty(), unreadable = listed == null, loading = false) }
                }
            }
        }
    }

    private fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(query = null, searching = false) }
        load(_state.value.active)
    }

    /** A search over the open folder, or everywhere on the phone from the top; a moment after the last key. */
    private fun setQuery(text: String) {
        searchJob?.cancel()
        val query = text.trim()
        if (query.length < MIN_SEARCH) {
            if (_state.value.query != null) clearSearch()
            return
        }
        val first = _state.value
        if (first.request == null && !first.browsing) return
        _state.update { it.copy(query = query, searching = true) }
        searchJob = scope.launch {
            delay(SEARCH_DELAY_MS)
            val s = _state.value
            val mimes = s.request?.mimes.orEmpty()
            val folder = s.folder
            val found = withContext(Dispatchers.IO) {
                when {
                    folder != null && DocFiles.isDoc(folder) ->
                        DocFiles.search(app, folder, query, s.showHidden, mimes)
                    folder != null ->
                        FileStore.search(File(folder), query, s.showHidden, mimes, mimeOf = FilePlaces::mimeOf)
                    else -> s.volumes.flatMap { volume ->
                        FileStore.search(File(volume.path), query, s.showHidden, mimes, mimeOf = FilePlaces::mimeOf)
                    }.take(FileStore.SEARCH_CAP)
                }
            }
            updatePane(s.active) { it.copy(entries = found) }
            _state.update { it.copy(searching = false) }
        }
    }

    // ---- Panes ------------------------------------------------------------

    private fun togglePanes() {
        val two = !_state.value.twoPanes
        host.setTwoPanes(two)
        if (two) {
            _state.update { it.copy(panes = listOf(it.pane, FilePane())) }
            load(1)
        } else {
            loadJobs[1]?.cancel()
            _state.update { it.copy(panes = listOf(it.pane), active = 0) }
        }
    }

    private fun focusPane(index: Int) {
        val s = _state.value
        if (index == s.active || index !in s.panes.indices) return
        if (s.query != null) clearSearch()
        _state.update { it.copy(active = index) }
    }

    // ---- Picking ----------------------------------------------------------

    private fun tap(entry: FileEntry) {
        val s = _state.value
        when {
            s.selecting -> toggle(entry)
            entry.isDirectory -> openEntry(entry)
            // The tool on its own: a file is something to act on, so a tap selects it.
            s.browsing -> toggle(entry)
            s.request?.limit == 1 -> deliver(listOf(entry.path))
            else -> toggle(entry)
        }
    }

    private fun toggle(entry: FileEntry) {
        host.haptic()
        val s = _state.value
        val picked = entry.path in s.selection
        val limit = s.request?.limit
        if (!picked && !s.selecting && limit != null && s.selection.size >= limit) {
            notice(FileNotice.Kind.LIMIT, limit)
            return
        }
        val next = if (picked) s.selection - entry.path else s.selection + entry.path
        val folders = when {
            !entry.isDirectory -> s.selectedFolders
            picked -> s.selectedFolders - entry.path
            else -> s.selectedFolders + entry.path
        }
        _state.update {
            it.copy(selection = next, selectedFolders = folders, selecting = it.selecting && next.isNotEmpty())
        }
    }

    private fun clearSelection() =
        _state.update { it.copy(selection = emptySet(), selectedFolders = emptySet(), selecting = false) }

    /** The picks to the tool that asked, through the queue Android's picker would have filled, and back to it. */
    private fun deliver(paths: List<String>) {
        val request = _state.value.request ?: return
        val uris = paths.map { DocFiles.uriOf(app, it) }
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
        if (!ChannelFeatures.FILE_MANAGER) return
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SpecialAccessActivity.intent(app, SpecialAccess.ALL_FILES)
        } else {
            Intent(app, FilesPermissionActivity::class.java)
        }
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Android's folder picker; the folder is on the list by the time the keyboard is back. */
    private fun addTree() {
        runCatching { app.startActivity(FileTreePickActivity.intent(app)) }
    }

    private fun removeTree(tree: FileTree) {
        _state.update { s ->
            // A list inside that folder goes back to Places: its grant may be gone in a moment.
            val panes = s.panes.map { pane ->
                val folder = pane.view as? FileView.Folder
                if (folder?.trail?.firstOrNull()?.path == tree.root) FilePane() else pane
            }
            s.copy(confirmRemoveTree = null, panes = panes)
        }
        scope.launch {
            withContext(Dispatchers.IO) { FileTrees.remove(app, tree.uri) }
            notice(FileNotice.Kind.TREE_REMOVED)
            if (open) loadAll()
        }
    }

    // ---- Changing files ---------------------------------------------------

    private fun takeClip(move: Boolean) {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        _state.update {
            it.copy(clip = FileClip(paths, move), selection = emptySet(), selectedFolders = emptySet(), selecting = false)
        }
    }

    /** Copy or Move: with two lists open and the other one in a folder, straight there; otherwise carried. */
    private fun copyOrMove(move: Boolean) {
        val target = _state.value.otherFolder ?: return takeClip(move)
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        clearSelection()
        transfer(paths, target.path, move)
    }

    private fun paste() {
        val s = _state.value
        val clip = s.clip ?: return
        val folder = s.folder ?: return
        _state.update { it.copy(clip = null) }
        transfer(clip.paths, folder, clip.move)
    }

    private fun transfer(paths: List<String>, dir: String, move: Boolean) {
        if (move) {
            runOp(FileOpKind.MOVE, paths.size, FileNotice.Kind.MOVED) { DocFiles.transfer(app, paths, dir, move = true, it) }
        } else {
            runOp(FileOpKind.COPY, paths.size, FileNotice.Kind.COPIED) { DocFiles.transfer(app, paths, dir, move = false, it) }
        }
    }

    private fun delete() {
        val paths = _state.value.selection.toList()
        _state.update {
            it.copy(confirmDelete = false, selection = emptySet(), selectedFolders = emptySet(), selecting = false)
        }
        if (paths.isEmpty()) return
        runOp(FileOpKind.DELETE, paths.size, FileNotice.Kind.DELETED) { DocFiles.delete(app, paths, it) }
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
                withContext(Dispatchers.IO) { FilePlaces.scan(app, result.touched.filterNot(DocFiles::isDoc)) }
                if (result.failed > 0) notice(FileNotice.Kind.FAILED, result.failed) else notice(done, result.done)
            } finally {
                _state.update { it.copy(op = null) }
                if (open) loadAll()
            }
        }
    }

    private fun applyRename(name: String) {
        val path = _state.value.selection.singleOrNull() ?: return
        scope.launch {
            val renamed = withContext(Dispatchers.IO) {
                if (DocFiles.isDoc(path)) {
                    DocFiles.rename(app, path, name)
                } else {
                    FileOps.rename(File(path), name)?.path?.also { FilePlaces.scan(app, listOf(path, it)) }
                }
            }
            if (renamed == null) {
                notice(FileNotice.Kind.BAD_NAME)
                return@launch
            }
            clearSelection()
            loadAll()
        }
    }

    private fun applyNewFolder(name: String) {
        val folder = _state.value.folder ?: return
        scope.launch {
            val made = withContext(Dispatchers.IO) {
                if (DocFiles.isDoc(folder)) DocFiles.newFolder(app, folder, name) else FileOps.newFolder(File(folder), name)?.path
            }
            if (made == null) notice(FileNotice.Kind.BAD_NAME) else loadAll()
        }
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
            is FileAction.OpenFolder -> openPath(action.path)
            FileAction.OpenPlaces -> show(FileView.Places)
            FileAction.OpenRecent -> show(FileView.Recent)
            is FileAction.OpenShortcut -> show(FileView.Folder(FilePlaces.folderOf(action.shortcut).path))
            is FileAction.OpenTree -> openTree(action.tree)
            FileAction.AddTree -> addTree()
            is FileAction.AskRemoveTree -> {
                host.haptic()
                _state.update { it.copy(confirmRemoveTree = action.tree) }
            }
            is FileAction.AnswerRemoveTree -> {
                val tree = _state.value.confirmRemoveTree
                if (action.yes && tree != null) removeTree(tree) else _state.update { it.copy(confirmRemoveTree = null) }
            }
            FileAction.TogglePanes -> togglePanes()
            is FileAction.FocusPane -> focusPane(action.index)
            FileAction.ToggleExpanded -> _state.update { it.copy(expanded = !it.expanded) }
            is FileAction.SetSort -> setSort(action.sort)
            FileAction.ToggleHidden -> {
                _state.update { it.copy(showHidden = !it.showHidden) }
                loadAll()
            }
            FileAction.AskPermission -> askPermission()
            FileAction.UseSystemPicker -> useSystemPicker()
            FileAction.AlwaysSystemPicker -> {
                host.turnOff()
                useSystemPicker()
            }
            FileAction.Add -> if (_state.value.canPick) deliver(_state.value.selection.toList())
            FileAction.ClearSelection -> clearSelection()
            FileAction.Copy -> copyOrMove(move = false)
            FileAction.Move -> copyOrMove(move = true)
            FileAction.Paste -> paste()
            FileAction.ClearClip -> _state.update { it.copy(clip = null) }
            FileAction.AskDelete -> _state.update { it.copy(confirmDelete = true) }
            is FileAction.AnswerDelete ->
                if (action.yes) delete() else _state.update { it.copy(confirmDelete = false) }
            FileAction.CancelOp -> opJob?.cancel()
            FileAction.Rename -> _state.value.selection.singleOrNull()?.let { path ->
                val name = _state.value.panes.flatMap { it.entries }.firstOrNull { it.path == path }?.name
                    ?: if (DocFiles.isDoc(path)) "" else File(path).name
                host.beginText(FilesTextEntry(FilesTextField.RENAME, name))
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
        loadAll()
    }

    companion object {
        const val TEXT_MAX = 255
        private const val MIN_SEARCH = 2
        private const val SEARCH_DELAY_MS = 300L
        private const val NOTICE_MS = 3_000L
    }
}
