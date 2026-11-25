package com.wasimaster.wmkeyboard.ime.files

import android.content.ClipData
import android.content.ClipboardManager
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
import com.wasimaster.wmkeyboard.core.files.Clash
import com.wasimaster.wmkeyboard.core.files.DocFiles
import com.wasimaster.wmkeyboard.core.files.FileCategories
import com.wasimaster.wmkeyboard.core.files.FileCategory
import com.wasimaster.wmkeyboard.core.files.FileDetails
import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.files.FileKind
import com.wasimaster.wmkeyboard.core.files.FileKinds
import com.wasimaster.wmkeyboard.core.files.FileOpProgress
import com.wasimaster.wmkeyboard.core.files.FileOpResult
import com.wasimaster.wmkeyboard.core.files.FileOps
import com.wasimaster.wmkeyboard.core.files.FilePin
import com.wasimaster.wmkeyboard.core.files.FilePins
import com.wasimaster.wmkeyboard.core.files.FilePlaces
import com.wasimaster.wmkeyboard.core.files.FileQuery
import com.wasimaster.wmkeyboard.core.files.FileShortcut
import com.wasimaster.wmkeyboard.core.files.FileSort
import com.wasimaster.wmkeyboard.core.files.FileStore
import com.wasimaster.wmkeyboard.core.files.FileTree
import com.wasimaster.wmkeyboard.core.files.FileTrees
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
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
 * file, and the Files tool of its own (#1267), so neither means leaving the
 * field. It browses, picks, previews and changes files, and sends them into
 * the field the keyboard is typing in.
 *
 * Opened for one [FilePickRequest] over the panel that asked, it always goes
 * back to that panel, and what is picked goes through the same queues
 * Android's picker fills. Opened as the tool, it closes to the keys. It lists
 * the phone's own storage by path when it has All files access, and the
 * folders added through Android's folder picker ([FileTrees]) in every build.
 * Nothing runs until it opens.
 *
 * Changes run one at a time, in a queue; each can be taken back with Undo
 * while its chip shows, except a delete, which is permanent by the owner's
 * choice (no bin).
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

        /** The Files tool's settings: grid, tap, thumbnails, hidden files, folders first. */
        fun prefs(): FilePrefs
        fun setGrid(on: Boolean)
        fun setShowHidden(on: Boolean)

        /** [file] into the field as an attachment, when the field takes its type: true when it went. */
        fun commitFile(file: File, mime: String): Boolean
        fun insertText(text: String)

        /** A password field: nothing from here is typed into it. */
        fun secureField(): Boolean

        /** What plugins add to previews, thumbnails and colouring; null while plugins are off. */
        fun filePlugins(): FilePlugins? = null
    }

    private val app = context.applicationContext
    private val _state = MutableStateFlow(FileManagerUi())
    val state: StateFlow<FileManagerUi> = _state

    val callbacks = FileManagerCallbacks(state = state, onAction = ::dispatch, plugins = { host.filePlugins() })

    private var returnPanel = PanelMode.NONE
    private var open = false
    private val loadJobs = arrayOfNulls<Job>(2)
    private var searchJob: Job? = null
    private var noticeJob: Job? = null
    private var undoJob: Job? = null
    private var placesJob: Job? = null
    private var previewJob: Job? = null
    private var infoJob: Job? = null

    /** The last folder for each kind of pick, so picking again starts where the last one ended. */
    private val lastViews = HashMap<Class<*>, FileView>()

    /** Where the Files tool's lists were when it closed. */
    private var browseViews: List<FileView> = emptyList()

    /** Where a drag-select started, and what was picked before it, so a drag back shrinks the range. */
    private var rangeAnchor: Int = -1
    private var rangeBase: Set<String> = emptySet()

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
                selectionBytes = 0,
                selecting = false,
                confirmDelete = false,
                confirmRemoveTree = null,
                clash = null,
                query = null,
                searching = false,
                preview = null,
                info = null,
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
        previewJob?.cancel()
        infoJob?.cancel()
        placesJob?.cancel()
        val s = _state.value
        s.request?.let { lastViews[it.javaClass] = s.view }
        if (s.browsing) browseViews = s.panes.map { it.view }
        // A copy under way finishes by itself. The clip stays too: going back
        // for another file should not drop what Copy was carrying.
        _state.update {
            it.copy(
                request = null,
                browsing = false,
                panes = it.panes.map { pane -> pane.copy(entries = emptyList(), loading = false, reading = 0) },
                selection = emptySet(),
                selectedFolders = emptySet(),
                selectionBytes = 0,
                selecting = false,
                confirmDelete = false,
                confirmRemoveTree = null,
                clash = null,
                query = null,
                searching = false,
                preview = null,
                info = null,
                notice = null,
            )
        }
        pendingClash = null
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
                prefs = host.prefs(),
                secureField = host.secureField(),
            )
        }
    }

    /**
     * Back, one layer at a time: the text box, a card, the preview, a
     * question, the selection, the search, the place before this one (or up
     * a folder when there was none), and last the panel that asked (or the keys).
     */
    fun onBack() {
        val s = _state.value
        val view = s.view
        when {
            host.currentText() != null -> host.endText()
            s.filters.open != null -> setFilters { it.copy(open = null) }
            s.preview?.typeMenu == true -> _state.update { it.copy(preview = it.preview?.copy(typeMenu = false)) }
            s.info != null -> closeInfo()
            s.preview != null -> closePreview()
            s.clash != null -> cancelClash()
            s.confirmRemoveTree != null -> _state.update { it.copy(confirmRemoveTree = null) }
            s.confirmDelete -> _state.update { it.copy(confirmDelete = false) }
            s.selection.isNotEmpty() -> clearSelection()
            s.query != null -> clearSearch()
            s.pane.history.isNotEmpty() -> goBack()
            view is FileView.Folder -> up(view)
            view != FileView.Places -> show(FileView.Places, remember = false)
            else -> close()
        }
    }

    private fun close() = host.returnTo(returnPanel)

    private fun goBack() {
        val index = _state.value.active
        val history = _state.value.pane.history
        val previous = history.lastOrNull() ?: return
        updatePane(index) { it.copy(history = history.dropLast(1)) }
        show(previous, remember = false)
    }

    private fun up(view: FileView.Folder) {
        if (view.trail.isNotEmpty()) {
            show(
                if (view.trail.size > 1) {
                    FileView.Folder(view.trail[view.trail.size - 2].path, view.trail.dropLast(1))
                } else {
                    FileView.Places
                },
                remember = false,
            )
            return
        }
        val atVolume = _state.value.volumes.any { it.path == view.path }
        val parent = File(view.path).parent
        show(if (atVolume || parent == null) FileView.Places else FileView.Folder(parent), remember = false)
    }

    // ---- Listing ----------------------------------------------------------

    private fun updatePane(index: Int, change: (FilePane) -> FilePane) = _state.update { s ->
        if (index !in s.panes.indices) {
            s
        } else {
            s.copy(panes = s.panes.toMutableList().also { it[index] = change(it[index]) })
        }
    }

    /** [view] in the active list; [remember] puts the place it leaves on the list's history, for Back. */
    private fun show(view: FileView, remember: Boolean = true) {
        searchJob?.cancel()
        val index = _state.value.active
        val from = _state.value.pane.view
        _state.update { it.copy(query = null, searching = false, searchingIn = null) }
        updatePane(index) { pane ->
            val history = if (remember && from != view) (pane.history + from).takeLast(HISTORY_MAX) else pane.history
            pane.copy(view = view, history = history)
        }
        if (view is FileView.Folder) noteVisit(view)
        load(index)
    }

    private fun noteVisit(view: FileView.Folder) {
        val label = folderCrumbs(view, _state.value.volumes).lastOrNull()?.label ?: return
        scope.launch(Dispatchers.IO) {
            FilePins.visited(app, FilePin(view.path, label))
            val recent = FilePins.recentFolders(app)
            _state.update { it.copy(recentFolders = recent) }
        }
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

    /** A pinned or recent folder: a document one starts a trail of its own, as an added folder does. */
    private fun openPin(pin: FilePin) {
        if (DocFiles.isDoc(pin.path)) {
            show(FileView.Folder(pin.path, listOf(FileCrumb(pin.path, pin.label))))
        } else {
            show(FileView.Folder(pin.path))
        }
    }

    private fun loadAll() {
        _state.value.panes.indices.forEach(::load)
    }

    /** A listing already read, kept so going back into a folder shows it at once while it is read again. */
    private data class Listed(val key: String, val stamp: Long, val entries: List<FileEntry>)

    private val listed = object : LinkedHashMap<String, Listed>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Listed>?): Boolean = size > CACHE_MAX
    }

    private fun cacheKey(path: String, s: FileManagerUi): String =
        "$path|${s.sort}|${s.descending}|${s.prefs.showHidden}|${s.prefs.foldersFirst}|${s.request?.mimes.orEmpty().joinToString(",")}"

    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    private fun load(index: Int) {
        val s = _state.value
        if (s.request == null && !s.browsing) return
        if (s.locked) return
        val pane = s.panes.getOrNull(index) ?: return
        val mimes = s.request?.mimes.orEmpty()
        val prefs = s.prefs
        loadJobs[index]?.cancel()
        val view = pane.view
        // A folder read before shows straight away; the read below brings it up to date.
        val cached = (view as? FileView.Folder)?.let { synchronized(listed) { listed[cacheKey(it.path, s)] } }
        updatePane(index) { it.copy(loading = cached == null, entries = cached?.entries ?: emptyList(), reading = 0) }
        loadJobs[index] = scope.launch {
            val access = s.access
            if (view == FileView.Places) loadPlaces(access)
            when (view) {
                FileView.Places -> updatePane(index) { it.copy(entries = emptyList(), unreadable = false, loading = false) }
                FileView.Recent -> {
                    val recent = if (access) withContext(Dispatchers.IO) { FilePlaces.recent(app, mimes, prefs.showHidden) } else emptyList()
                    updatePane(index) { it.copy(entries = recent, unreadable = false, loading = false) }
                }
                is FileView.Category -> {
                    val found = if (access) {
                        withContext(Dispatchers.IO) {
                            FileCategories.list(app, view.category, s.sort, s.descending, prefs.showHidden, mimes)
                        }
                    } else {
                        emptyList()
                    }
                    updatePane(index) { it.copy(entries = found, unreadable = false, loading = false) }
                }
                is FileView.Folder -> {
                    val stamp = if (DocFiles.isDoc(view.path)) -1L else withContext(Dispatchers.IO) { File(view.path).lastModified() }
                    if (cached != null && stamp > 0 && cached.stamp == stamp) {
                        updatePane(index) { it.copy(entries = cached.entries, unreadable = false, loading = false) }
                        return@launch
                    }
                    val result = withContext(Dispatchers.IO) {
                        when {
                            DocFiles.isDoc(view.path) ->
                                DocFiles.list(app, view.path, s.sort, s.descending, prefs.showHidden, mimes)
                                    ?.let { FileStore.sorted(it, s.sort, s.descending, prefs.foldersFirst) }
                            access -> FileStore.list(
                                File(view.path),
                                s.sort,
                                s.descending,
                                prefs.showHidden,
                                mimes,
                                FilePlaces::mimeOf,
                                foldersFirst = prefs.foldersFirst,
                                fast = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                                onCount = { n -> updatePane(index) { it.copy(reading = n) } },
                            )
                            else -> null
                        }
                    }
                    if (result != null) synchronized(listed) { listed[cacheKey(view.path, s)] = Listed(view.path, stamp, result) }
                    updatePane(index) { it.copy(entries = result.orEmpty(), unreadable = result == null, loading = false, reading = 0) }
                }
            }
            refreshSelectionBytes()
        }
    }

    /** What Places shows besides the lists: storage, category counts, pins, recent folders and searches. */
    private fun loadPlaces(access: Boolean) {
        placesJob?.cancel()
        placesJob = scope.launch {
            val volumes = if (access) withContext(Dispatchers.IO) { FilePlaces.volumes(app) } else emptyList()
            val trees = withContext(Dispatchers.IO) { FileTrees.list(app) }
            val space = withContext(Dispatchers.IO) { volumes.mapNotNull { v -> FileDetails.space(v.path)?.let { v.path to it } }.toMap() }
            val shortcuts = if (access) {
                withContext(Dispatchers.IO) { FileShortcut.entries.filter { FilePlaces.folderOf(it).isDirectory } }
            } else {
                emptyList()
            }
            val (pins, recent, searches) = withContext(Dispatchers.IO) {
                Triple(FilePins.pins(app), FilePins.recentFolders(app), FilePins.searches(app))
            }
            _state.update {
                it.copy(
                    volumes = volumes,
                    trees = trees,
                    space = space,
                    shortcuts = shortcuts,
                    pins = pins,
                    recentFolders = recent,
                    searches = searches,
                )
            }
            // The counts take a query each; the tiles show without them first.
            if (access && _state.value.categoryCounts.isEmpty()) {
                val counts = withContext(Dispatchers.IO) { FileCategory.entries.associateWith { FileCategories.count(app, it) } }
                _state.update { it.copy(categoryCounts = counts) }
            }
        }
    }

    /** A change on disk: what was cached may be stale, and every list reads again. */
    private fun changed() {
        synchronized(listed) { listed.clear() }
        _state.update { it.copy(categoryCounts = emptyMap()) }
        if (open) loadAll()
    }

    // ---- Search -----------------------------------------------------------

    private fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(query = null, searching = false, searchingIn = null, filters = SearchFilters()) }
        load(_state.value.active)
    }

    private fun setFilters(change: (SearchFilters) -> SearchFilters) {
        _state.update { it.copy(filters = change(it.filters)) }
    }

    private fun queryOf(text: String, filters: SearchFilters): FileQuery {
        val after = if (filters.date.days > 0) System.currentTimeMillis() - filters.date.days * DAY_MS else 0L
        return FileQuery(
            text = text,
            kinds = filters.category?.kinds.orEmpty(),
            minSize = filters.size.min,
            maxSize = filters.size.max,
            after = after,
            inContents = filters.inContents,
        )
    }

    /**
     * A search over the open folder, or everywhere from the top, a moment
     * after the last key; results stream in as the walk finds them. The
     * filters alone, with no words, search too: every PDF from this month.
     */
    private fun runSearch(text: String, now: Boolean = false) {
        searchJob?.cancel()
        val trimmed = text.trim()
        val first = _state.value
        val query = queryOf(trimmed, first.filters)
        if ((trimmed.length < MIN_SEARCH && !first.filters.any) || query.isEmpty) {
            if (first.query != null) {
                _state.update { it.copy(query = if (first.filters.any) trimmed else null, searching = false) }
                if (!first.filters.any) load(first.active)
            }
            return
        }
        if (first.request == null && !first.browsing) return
        _state.update { it.copy(query = trimmed, searching = true) }
        searchJob = scope.launch {
            if (!now) delay(SEARCH_DELAY_MS)
            val s = _state.value
            val mimes = s.request?.mimes.orEmpty()
            val folder = s.folder
            val active = s.active
            val show: (List<FileEntry>) -> Unit = { found -> updatePane(active) { it.copy(entries = found, loading = false) } }
            updatePane(active) { it.copy(entries = emptyList()) }
            val roots: List<Pair<String, String>> = when {
                folder != null -> listOf(folder to (folderCrumbs(s.view as FileView.Folder, s.volumes).lastOrNull()?.label ?: folder))
                else -> s.volumes.map { it.path to it.label } + s.trees.map { it.root to it.label }
            }
            val found = ArrayList<FileEntry>()
            withContext(Dispatchers.IO) {
                for ((root, label) in roots) {
                    if (found.size >= FileStore.SEARCH_CAP) break
                    _state.update { it.copy(searchingIn = label) }
                    val base = found.toList()
                    val more = if (DocFiles.isDoc(root)) {
                        DocFiles.search(app, root, query, s.prefs.showHidden, mimes, FileStore.SEARCH_CAP - found.size) { show(base + it) }
                    } else {
                        FileStore.search(File(root), query, s.prefs.showHidden, mimes, FileStore.SEARCH_CAP - found.size, FilePlaces::mimeOf, { show(base + it) })
                    }
                    found += more
                    show(found.toList())
                }
            }
            _state.update { it.copy(searching = false, searchingIn = null) }
            if (trimmed.isNotEmpty()) {
                withContext(Dispatchers.IO) { FilePins.searched(app, trimmed) }
                _state.update { it.copy(searches = FilePins.searches(app)) }
            }
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

    private fun swapPanes() {
        if (!_state.value.twoPanes) return
        host.haptic()
        _state.update { it.copy(panes = it.panes.reversed(), active = 1 - it.active) }
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
            // The tool on its own: a tap opens the file in the app for it (or
            // in the keyboard's preview, by the setting); a hold picks it to act on.
            s.browsing && s.prefs.tapPreviews -> openPreview(entry)
            s.browsing -> open(entry.path, entry.mime, choose = false)
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
        refreshSelectionBytes()
    }

    private fun hold(entry: FileEntry) {
        val s = _state.value
        rangeAnchor = s.entries.indexOfFirst { it.path == entry.path }
        rangeBase = s.selection - entry.path
        _state.update { it.copy(selecting = true) }
        if (entry.path !in s.selection) toggle(entry) else host.haptic()
    }

    /** From the held row to [to], on top of what was picked before the hold. */
    private fun selectRange(from: Int, to: Int) {
        val s = _state.value
        val anchor = if (rangeAnchor >= 0) rangeAnchor else from
        if (anchor !in s.entries.indices) return
        val end = to.coerceIn(s.entries.indices)
        val range = s.entries.subList(minOf(anchor, end), maxOf(anchor, end) + 1)
        val limit = s.request?.limit
        val next = (rangeBase + range.map { it.path }).let { all -> if (limit != null && all.size > limit) all.take(limit).toSet() else all }
        if (next == s.selection) return
        host.haptic()
        val folders = s.entries.filter { it.isDirectory && it.path in next }.map { it.path }.toSet() +
            s.selectedFolders.filter { it in rangeBase }
        _state.update { it.copy(selection = next, selectedFolders = folders, selecting = true) }
        refreshSelectionBytes()
    }

    private fun selectAll() {
        val s = _state.value
        val entries = s.entries.filter { s.request == null || !it.isDirectory }
        val limited = s.request?.limit?.let { entries.take(it) } ?: entries
        host.haptic()
        _state.update {
            it.copy(
                selection = it.selection + limited.map { e -> e.path },
                selectedFolders = it.selectedFolders + limited.filter { e -> e.isDirectory }.map { e -> e.path },
                selecting = true,
            )
        }
        refreshSelectionBytes()
    }

    private fun invertSelection() {
        val s = _state.value
        val here = s.entries.filter { s.request == null || !it.isDirectory }
        val herePaths = here.map { it.path }.toSet()
        val next = (s.selection - herePaths) + herePaths.filterNot { it in s.selection }
        host.haptic()
        _state.update {
            it.copy(
                selection = next,
                selectedFolders = here.filter { e -> e.isDirectory && e.path in next }.map { e -> e.path }.toSet() +
                    it.selectedFolders.filter { p -> p !in herePaths },
                selecting = next.isNotEmpty(),
            )
        }
        refreshSelectionBytes()
    }

    /** The picked files' sizes, from the rows that show them; a pick from another folder counts as its last known size. */
    private val knownSizes = HashMap<String, Long>()

    private fun refreshSelectionBytes() {
        val s = _state.value
        s.panes.forEach { pane -> pane.entries.forEach { if (!it.isDirectory) knownSizes[it.path] = it.size } }
        val bytes = s.selection.sumOf { knownSizes[it] ?: 0L }
        if (bytes != s.selectionBytes) _state.update { it.copy(selectionBytes = bytes) }
    }

    /**
     * Hands the file at [path] to the app that shows it, through Android's
     * View intent with a read grant for that one file: [choose] asks which
     * app every time, else the default one opens it (Android asks when there
     * is none). A document folder's file goes as its own document URI, one
     * by path through [com.wasimaster.wmkeyboard.core.files.LocalFilesProvider].
     */
    private fun open(path: String, mime: String, choose: Boolean) {
        host.haptic()
        val uri = DocFiles.uriOf(app, path)
        val type = mime.ifEmpty { null }
            ?: runCatching { app.contentResolver.getType(uri) }.getOrNull()
            ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser carries the grant over to the app picked from it.
        val intent = (if (choose) Intent.createChooser(view, null) else view).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { app.startActivity(intent) }.isFailure) notice(FileNotice.Kind.NO_APP)
    }

    private fun clearSelection() {
        rangeAnchor = -1
        rangeBase = emptySet()
        _state.update { it.copy(selection = emptySet(), selectedFolders = emptySet(), selecting = false, selectionBytes = 0) }
    }

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
            FilePickRequest.DocPicture -> GalleryPickActivity.offerDoc(uris.first())
            FilePickRequest.CameraPicture -> GalleryPickActivity.offerCamera(uris.first())
            is FilePickRequest.PluginFile -> com.wasimaster.wmkeyboard.ime.PluginFilePicks.deliver(uris.first())
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

    // ---- Pins -------------------------------------------------------------

    /** The picked folder, or the open one, on or off Places. */
    private fun togglePin() {
        val s = _state.value
        val target = s.selection.singleOrNull()?.takeIf { it in s.selectedFolders } ?: s.folder ?: s.info?.entry?.takeIf { it.isDirectory }?.path ?: return
        val label = s.entries.firstOrNull { it.path == target }?.name
            ?: (s.view as? FileView.Folder)?.let { folderCrumbs(it, s.volumes).lastOrNull()?.label }
            ?: s.info?.entry?.name
            ?: File(target).name
        scope.launch {
            val pinned = withContext(Dispatchers.IO) { FilePins.toggle(app, FilePin(target, label)) }
            _state.update { it.copy(pins = FilePins.pins(app), info = it.info?.copy(pinned = pinned)) }
            notice(if (pinned) FileNotice.Kind.PINNED else FileNotice.Kind.UNPINNED)
        }
    }

    private fun unpin(pin: FilePin) {
        host.haptic()
        scope.launch {
            withContext(Dispatchers.IO) { FilePins.toggle(app, pin) }
            _state.update { it.copy(pins = FilePins.pins(app)) }
            notice(FileNotice.Kind.UNPINNED)
        }
    }

    // ---- Changing files ---------------------------------------------------

    /** One change waiting its turn: what it is, how many things, and the work. */
    private class PendingOp(
        val kind: FileOpKind,
        val total: Int,
        val work: suspend ((FileOpProgress) -> Unit) -> Unit,
    )

    private val queue = ArrayDeque<PendingOp>()
    private var opJob: Job? = null

    /**
     * [work] in turn, after whatever is under way: a second copy started
     * while the first runs waits rather than being dropped. Its progress is
     * the bar; the speed and the time left come from the bytes.
     */
    private fun enqueue(kind: FileOpKind, total: Int, work: suspend ((FileOpProgress) -> Unit) -> Unit) {
        queue.addLast(PendingOp(kind, total, work))
        if (opJob?.isActive == true) {
            _state.update { it.copy(queued = queue.map { op -> op.kind }) }
            notice(FileNotice.Kind.QUEUED)
            return
        }
        runNext()
    }

    private fun runNext() {
        val next = queue.removeFirstOrNull()
        _state.update { it.copy(queued = queue.map { op -> op.kind }) }
        if (next == null) {
            _state.update { it.copy(op = null) }
            return
        }
        _state.update { it.copy(op = FileOpUi(next.kind, 0, next.total)) }
        opJob = scope.launch {
            val started = System.currentTimeMillis()
            var lastDrawn = 0L
            try {
                withContext(Dispatchers.IO) {
                    next.work progress@{ p ->
                        val now = System.currentTimeMillis()
                        val finished = p.done >= p.total && p.total > 0
                        if (now - lastDrawn < PROGRESS_MS && !finished) return@progress
                        lastDrawn = now
                        val seconds = ((now - started) / 1000.0).coerceAtLeast(0.25)
                        val speed = (p.bytesDone / seconds).toLong()
                        val left = if (speed > 0 && p.bytesTotal > p.bytesDone) (p.bytesTotal - p.bytesDone) / speed else -1
                        _state.update { it.copy(op = FileOpUi(next.kind, p.done, p.total, p.name, p.bytesDone, p.bytesTotal, speed, left)) }
                    }
                }
            } finally {
                changed()
                runNext()
            }
        }
    }

    private fun cancelOp() {
        opJob?.cancel()
    }

    /** What a finished change says, and the media library told what moved. */
    private fun report(result: FileOpResult, done: FileNotice.Kind) {
        FilePlaces.scan(app, result.touched.filterNot(DocFiles::isDoc))
        when {
            result.failed > 0 -> notice(FileNotice.Kind.FAILED, result.failed)
            result.done == 0 && result.skipped > 0 -> notice(FileNotice.Kind.SKIPPED, result.skipped)
            else -> notice(done, result.done)
        }
    }

    private fun takeClip(move: Boolean) {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        clearSelection()
        _state.update { it.copy(clip = FileClip(paths, move)) }
    }

    /** Copy or Move: with two lists open and the other one in a folder, straight there; otherwise carried. */
    private fun copyOrMove(move: Boolean) {
        val target = _state.value.otherFolder ?: return takeClip(move)
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        val origin = _state.value.folder
        clearSelection()
        transfer(paths, target.path, move, origin, folderCrumbs(target, _state.value.volumes).lastOrNull()?.label.orEmpty())
    }

    private fun paste() {
        val s = _state.value
        val clip = s.clip ?: return
        val folder = s.folder ?: return
        _state.update { it.copy(clip = null) }
        val label = (s.view as? FileView.Folder)?.let { folderCrumbs(it, s.volumes).lastOrNull()?.label }.orEmpty()
        transfer(clip.paths, folder, clip.move, null, label)
    }

    /** A copy or move held while the clash card asks about names already taken. */
    private class Transfer(
        val paths: List<String>,
        val dir: String,
        val move: Boolean,
        val origin: String?,
        val clashing: List<String>,
        val answers: MutableMap<String, Clash> = HashMap(),
    )

    private var pendingClash: Transfer? = null

    /**
     * Copies or moves [paths] into [dir], asking first when names there are
     * taken. [origin] is the folder they came from, when they all came from
     * one, so a document move can be taken back.
     */
    private fun transfer(paths: List<String>, dir: String, move: Boolean, origin: String?, label: String) {
        scope.launch {
            val clashing = withContext(Dispatchers.IO) { DocFiles.clashes(app, paths, dir) }
            if (clashing.isEmpty()) {
                runTransfer(Transfer(paths, dir, move, origin, emptyList()))
                return@launch
            }
            pendingClash = Transfer(paths, dir, move, origin, clashing)
            val names = withContext(Dispatchers.IO) { clashing.map { DocFiles.nameOf(app, it) } }
            host.haptic()
            _state.update { it.copy(clash = ClashAsk(names, label)) }
        }
    }

    private fun answerClash(policy: Clash) {
        val pending = pendingClash ?: return
        val ask = _state.value.clash ?: return
        if (ask.sameForAll) {
            pending.clashing.forEach { pending.answers[it] = policy }
        } else {
            pending.answers[pending.clashing[ask.index]] = policy
            if (ask.index + 1 < pending.clashing.size) {
                _state.update { it.copy(clash = ask.copy(index = ask.index + 1)) }
                return
            }
        }
        pendingClash = null
        _state.update { it.copy(clash = null) }
        runTransfer(pending)
    }

    private fun cancelClash() {
        pendingClash = null
        _state.update { it.copy(clash = null) }
    }

    /** The transfer in groups by the answer each clashing name got; one op, one Undo. */
    private fun runTransfer(t: Transfer) {
        val groups = t.paths.groupBy { t.answers[it] ?: Clash.KEEP_BOTH }
        val kind = if (t.move) FileOpKind.MOVE else FileOpKind.COPY
        enqueue(kind, t.paths.size) { progress ->
            var offset = 0
            val results = ArrayList<FileOpResult>()
            for ((policy, group) in groups) {
                val base = offset
                results += DocFiles.transfer(app, group, t.dir, t.move, { p -> progress(p.copy(done = p.done + base, total = t.paths.size)) }, policy)
                offset += group.size
            }
            val result = FileOpResult(
                done = results.sumOf { it.done },
                failed = results.sumOf { it.failed },
                touched = results.flatMap { it.touched },
                moves = results.flatMap { it.moves },
                made = results.flatMap { it.made },
                skipped = results.sumOf { it.skipped },
            )
            withContext(Dispatchers.Main) {
                report(result, if (t.move) FileNotice.Kind.MOVED else FileNotice.Kind.COPIED)
                if (t.move && result.moves.isNotEmpty()) {
                    offerUndo(UndoKind.MOVE, result.moves.size) { undoMoves(result.moves, t.origin) }
                } else if (!t.move && result.made.isNotEmpty()) {
                    offerUndo(UndoKind.COPY, result.made.size) { DocFiles.delete(app, result.made).failed == 0 }
                }
            }
        }
    }

    /** Each moved thing back to the folder it came from: by its path's parent, or the folder a document move started in. */
    private suspend fun undoMoves(moves: List<Pair<String, String>>, origin: String?): Boolean {
        var ok = true
        for ((from, to) in moves) {
            val back = if (DocFiles.isDoc(from)) origin else File(from).parent
            if (back == null) {
                ok = false
                continue
            }
            val result = DocFiles.transfer(app, listOf(to), back, move = true)
            if (result.failed > 0) ok = false
        }
        return ok
    }

    private fun delete() {
        val paths = _state.value.selection.toList()
        _state.update { it.copy(confirmDelete = false) }
        clearSelection()
        if (paths.isEmpty()) return
        enqueue(FileOpKind.DELETE, paths.size) { progress ->
            val result = DocFiles.delete(app, paths, progress)
            withContext(Dispatchers.Main) { report(result, FileNotice.Kind.DELETED) }
        }
    }

    private fun duplicate() {
        val s = _state.value
        val paths = s.selection.toList()
        val folder = s.folder ?: paths.firstOrNull()?.takeUnless(DocFiles::isDoc)?.let { File(it).parent } ?: return
        if (paths.isEmpty()) return
        clearSelection()
        enqueue(FileOpKind.COPY, paths.size) { progress ->
            val result = DocFiles.transfer(app, paths, folder, move = false, onProgress = progress, clash = Clash.KEEP_BOTH)
            withContext(Dispatchers.Main) {
                report(result, FileNotice.Kind.COPIED)
                if (result.made.isNotEmpty()) {
                    offerUndo(UndoKind.DUPLICATE, result.made.size) { DocFiles.delete(app, result.made).failed == 0 }
                }
            }
        }
    }

    /** A zip of the selection, named for its one thing or for the folder, in the open folder. */
    private fun askZipName() {
        val s = _state.value
        if (s.selection.isEmpty() || s.folder == null) return
        val single = s.selection.singleOrNull()
        val name = single?.let { p -> s.entries.firstOrNull { it.path == p }?.name?.substringBeforeLast('.') }
            ?: (s.view as? FileView.Folder)?.let { folderCrumbs(it, s.volumes).lastOrNull()?.label }
            ?: "Archive"
        host.beginText(FilesTextEntry(FilesTextField.ZIP_NAME, name))
    }

    private fun zip(name: String) {
        val s = _state.value
        val folder = s.folder ?: return
        val paths = s.selection.toList()
        if (paths.isEmpty() || !FileOps.validName(name)) {
            notice(FileNotice.Kind.BAD_NAME)
            return
        }
        clearSelection()
        enqueue(FileOpKind.ZIP, paths.size) { progress ->
            val made = DocFiles.zip(app, paths, folder, name.trim(), progress)
            withContext(Dispatchers.Main) {
                if (made == null) {
                    notice(FileNotice.Kind.ZIP_FAILED)
                } else {
                    FilePlaces.scan(app, listOf(made).filterNot(DocFiles::isDoc))
                    notice(FileNotice.Kind.ZIPPED, paths.size)
                    offerUndo(UndoKind.ZIP, 1) { DocFiles.delete(app, listOf(made)).failed == 0 }
                }
            }
        }
    }

    /** The one picked zip (or the previewed one) unpacked into a folder of its own beside it. */
    private fun unzip() {
        val s = _state.value
        val archive = s.preview?.entry?.path ?: s.selection.singleOrNull() ?: return
        val folder = s.folder ?: archive.takeUnless(DocFiles::isDoc)?.let { File(it).parent } ?: return
        clearSelection()
        closePreview()
        enqueue(FileOpKind.UNZIP, 1) { progress ->
            val made = DocFiles.unzip(app, archive, folder, progress)
            withContext(Dispatchers.Main) {
                if (made == null) {
                    notice(FileNotice.Kind.ZIP_FAILED)
                } else {
                    if (!DocFiles.isDoc(made)) FilePlaces.scan(app, File(made).walkTopDown().filter { it.isFile }.map { it.path }.toList())
                    notice(FileNotice.Kind.UNZIPPED)
                    offerUndo(UndoKind.UNZIP, 1) { DocFiles.delete(app, listOf(made)).failed == 0 }
                }
            }
        }
    }

    private fun applyRename(name: String) {
        val path = _state.value.selection.singleOrNull() ?: return
        val oldName = _state.value.panes.flatMap { it.entries }.firstOrNull { it.path == path }?.name
            ?: if (DocFiles.isDoc(path)) "" else File(path).name
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
            changed()
            if (oldName.isNotEmpty()) {
                offerUndo(UndoKind.RENAME, 1) {
                    if (DocFiles.isDoc(renamed)) {
                        DocFiles.rename(app, renamed, oldName) != null
                    } else {
                        FileOps.rename(File(renamed), oldName)?.also { FilePlaces.scan(app, listOf(renamed, it.path)) } != null
                    }
                }
            }
        }
    }

    private fun applyNewFolder(name: String) {
        val folder = _state.value.folder ?: return
        scope.launch {
            val made = withContext(Dispatchers.IO) {
                if (DocFiles.isDoc(folder)) DocFiles.newFolder(app, folder, name) else FileOps.newFolder(File(folder), name)?.path
            }
            if (made == null) {
                notice(FileNotice.Kind.BAD_NAME)
            } else {
                changed()
                offerUndo(UndoKind.NEW_FOLDER, 1) { DocFiles.delete(app, listOf(made)).failed == 0 }
            }
        }
    }

    // ---- Undo -------------------------------------------------------------

    private var undoWork: (suspend () -> Boolean)? = null

    /** The Undo chip for the change just made, for a few seconds; a newer change takes its place. */
    private fun offerUndo(kind: UndoKind, count: Int, work: suspend () -> Boolean) {
        undoWork = work
        undoJob?.cancel()
        _state.update { it.copy(undo = FileUndoUi(kind, count)) }
        undoJob = scope.launch {
            delay(UNDO_MS)
            undoWork = null
            _state.update { it.copy(undo = null) }
        }
    }

    private fun undo() {
        val work = undoWork ?: return
        undoWork = null
        undoJob?.cancel()
        _state.update { it.copy(undo = null) }
        host.haptic()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { work() }.getOrDefault(false) }
            notice(if (ok) FileNotice.Kind.UNDONE else FileNotice.Kind.UNDO_FAILED)
            changed()
        }
    }

    // ---- Into the field and out of the keyboard ----------------------------

    /** The previewed file, or the picked files, as entries; folders left out. */
    private fun targets(): List<FileEntry> {
        val s = _state.value
        s.preview?.let { return listOf(it.entry) }
        val rows = s.panes.flatMap { it.entries }.associateBy { it.path }
        return s.selection.filterNot { it in s.selectedFolders }.mapNotNull { path ->
            rows[path] ?: if (DocFiles.isDoc(path)) null else File(path).takeIf { it.isFile }?.let { FileStore.entryOf(it, FilePlaces::mimeOf) }
        }
    }

    /**
     * Each file into the field as an attachment, the way a sticker or a photo
     * from the gallery goes: a copy under `cache/media/files` that the field's
     * app is given a grant to, swept a day later. A field that takes no files
     * says so, and nothing else happens.
     */
    private fun send() {
        val files = targets()
        if (files.isEmpty()) return
        host.haptic()
        enqueue(FileOpKind.SEND, files.size) { progress ->
            val dir = File(MediaPrep.outputDir(app).parentFile, "files").apply { mkdirs() }
            var sent = 0
            var refused = 0
            files.forEachIndexed { i, entry ->
                progress(FileOpProgress(i, files.size, entry.name))
                val copy = File(dir, entry.name.ifEmpty { "file" }).let { FileOps.uniqueTarget(dir, it.name) }
                val ok = runCatching { DocFiles.input(app, entry.path)?.use { input -> copy.outputStream().use { input.copyTo(it) } } != null }
                    .getOrDefault(false)
                if (!ok) {
                    copy.delete()
                    refused++
                    return@forEachIndexed
                }
                val mime = entry.mime.ifEmpty { FilePlaces.mimeOf(entry.name) ?: "application/octet-stream" }
                val went = withContext(Dispatchers.Main) { host.commitFile(copy, mime) }
                if (went) sent++ else {
                    copy.delete()
                    refused++
                }
            }
            withContext(Dispatchers.Main) {
                if (sent > 0) {
                    clearSelection()
                    notice(FileNotice.Kind.SENT, sent)
                } else if (refused > 0) {
                    notice(FileNotice.Kind.FIELD_REFUSED)
                }
            }
        }
    }

    /** The files to another app through Android's share sheet, each with a read grant. */
    private fun share() {
        val files = targets()
        if (files.isEmpty()) return
        host.haptic()
        val uris = files.map { DocFiles.uriOf(app, it.path) }
        val mime = files.map { it.mime.ifEmpty { "*/*" } }.distinct().singleOrNull()
            ?: files.map { it.mime.substringBefore('/', "*") }.distinct().singleOrNull()?.let { "$it/*" }
            ?: "*/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = mime
        send.clipData = ClipData.newRawUri(files.first().name, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (runCatching { app.startActivity(chooser) }.isFailure) notice(FileNotice.Kind.NO_APP)
    }

    private fun readable(path: String): String {
        val s = _state.value
        if (!DocFiles.isDoc(path)) return path
        val trail = (s.view as? FileView.Folder)?.trail.orEmpty()
        val name = s.entries.firstOrNull { it.path == path }?.name ?: s.preview?.entry?.name.orEmpty()
        return (trail.map { it.label } + name).filter { it.isNotEmpty() }.joinToString("/")
    }

    private fun copyPath() {
        val s = _state.value
        val path = s.info?.entry?.path ?: s.preview?.entry?.path ?: s.selection.singleOrNull() ?: s.folder ?: return
        copyToClipboard(readable(path))
        notice(FileNotice.Kind.PATH_COPIED)
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(null, text))
        }
    }

    /** The name, the path or a text file's words into the field. Never into a password field. */
    private fun type(what: TypeWhat) {
        if (host.secureField()) return
        val entry = targets().singleOrNull() ?: _state.value.selection.singleOrNull()?.let { p ->
            _state.value.entries.firstOrNull { it.path == p }
        } ?: return
        _state.update { it.copy(preview = it.preview?.copy(typeMenu = false)) }
        when (what) {
            TypeWhat.NAME -> host.insertText(entry.name)
            TypeWhat.PATH -> host.insertText(readable(entry.path))
            TypeWhat.CONTENTS -> scope.launch {
                if (!FileKinds.isTextual(FileKinds.of(entry)) || entry.size > TYPE_TEXT_MAX) {
                    notice(FileNotice.Kind.TOO_BIG_TO_TYPE)
                    return@launch
                }
                val text = withContext(Dispatchers.IO) { DocFiles.readText(app, entry.path, TYPE_TEXT_MAX.toInt()) } ?: return@launch
                host.insertText(text)
            }
        }
        host.haptic()
    }

    private fun copyText() {
        val text = _state.value.preview?.text ?: return
        copyToClipboard(text)
        notice(FileNotice.Kind.TEXT_COPIED)
    }

    // ---- Preview ------------------------------------------------------------

    private fun openPreview(entry: FileEntry) {
        val s = _state.value
        val files = s.entries.filterNot { it.isDirectory }
        val index = files.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        host.haptic()
        showPreview(entry, index, files.size)
    }

    /**
     * Whether the keyboard draws [entry] itself: pictures, video, audio,
     * text and code, PDFs, app packages and zips. Anything else is a plugin's
     * to draw, or the "no preview" card.
     */
    private fun builtIn(entry: FileEntry, kind: FileKind): Boolean =
        kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.AUDIO || kind == FileKind.PDF ||
            kind == FileKind.APK || FileKinds.isTextual(kind) || FileKinds.isZip(entry.name)

    private fun extensionOf(entry: FileEntry): String = entry.name.substringAfterLast('.', "").lowercase()

    private fun showPreview(entry: FileEntry, index: Int, count: Int) {
        val kind = FileKinds.of(entry)
        previewJob?.cancel()
        host.filePlugins()?.runner?.cancel()
        val plugins = host.filePlugins()?.previewsFor(entry.name, entry.mime).orEmpty()
        val own = builtIn(entry, kind)
        val choices = buildList {
            if (own) add(PreviewChoice(PreviewChoice.BUILT_IN, null))
            for (ref in plugins) add(PreviewChoice(ref.key, ref.name))
        }
        // The choice made last for this type, while it is still on offer; else
        // the keyboard's own, else the closest plugin.
        val remembered = FilePins.previewChoice(app, extensionOf(entry))?.takeIf { key -> choices.any { it.key == key } }
        val choice = remembered ?: choices.firstOrNull()?.key ?: PreviewChoice.BUILT_IN
        _state.update { it.copy(preview = PreviewUi(entry, kind, index, count, choices = choices, choice = choice), expanded = true) }
        if (choice != PreviewChoice.BUILT_IN) {
            runPlugin(entry, choice)
            return
        }
        loadBuiltIn(entry, kind, index, count)
    }

    /** "View with": [key] draws the file now, and next time for its type. */
    private fun previewWith(key: String) {
        val preview = _state.value.preview ?: return
        if (preview.choice == key || preview.choices.none { it.key == key }) return
        host.haptic()
        FilePins.setPreviewChoice(app, extensionOf(preview.entry), key)
        previewJob?.cancel()
        host.filePlugins()?.runner?.cancel()
        val reset = preview.copy(choice = key, loading = true, pluginUi = null, pluginError = null)
        _state.update { it.copy(preview = reset) }
        if (key == PreviewChoice.BUILT_IN) {
            loadBuiltIn(preview.entry, preview.kind, preview.index, preview.count)
        } else {
            runPlugin(preview.entry, key)
        }
    }

    private fun runPlugin(entry: FileEntry, key: String) {
        val plugins = host.filePlugins()
        val ref = plugins?.preview(key)
        if (plugins == null || ref == null) {
            _state.update { s ->
                s.copy(preview = s.preview?.takeIf { it.entry.path == entry.path }?.copy(loading = false, pluginError = com.wasimaster.wmkeyboard.core.plugins.PluginText.of(com.wasimaster.wmkeyboard.plugins.R.string.core_plugins_error_turned_off)) ?: s.preview)
            }
            return
        }
        plugins.runner.preview(ref, plugins.source(entry)) { result ->
            _state.update { s ->
                val current = s.preview?.takeIf { it.entry.path == entry.path && it.choice == key } ?: return@update s
                val next = when (result) {
                    is com.wasimaster.wmkeyboard.core.plugins.PluginPreviewResult.Shown -> current.copy(loading = false, pluginUi = result.ui, pluginError = null)
                    is com.wasimaster.wmkeyboard.core.plugins.PluginPreviewResult.Failed -> current.copy(loading = false, pluginError = result.message)
                    is com.wasimaster.wmkeyboard.core.plugins.PluginPreviewResult.Stopped -> current.copy(loading = false, pluginError = result.message)
                }
                s.copy(preview = next)
            }
        }
    }

    private fun loadBuiltIn(entry: FileEntry, kind: FileKind, index: Int, count: Int) {
        previewJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                when {
                    FileKinds.isTextual(kind) -> {
                        val text = DocFiles.readText(app, entry.path, PREVIEW_TEXT_MAX)
                        val grammar = host.filePlugins()?.grammarFor(entry.name)
                        val syntax = if (text != null && grammar != null) com.wasimaster.wmkeyboard.core.plugins.PluginSyntax.tokens(text, grammar) else null
                        PreviewUi(entry, kind, index, count, loading = false, text = text, textCut = entry.size > PREVIEW_TEXT_MAX, syntax = syntax)
                    }
                    kind == FileKind.PDF -> {
                        val page = FileDetails.pdfPage(app, entry.path, 0, PDF_PX)
                        PreviewUi(entry, kind, index, count, loading = false, pdfPage = page?.first, pdfCount = page?.second ?: 0)
                    }
                    kind == FileKind.APK -> {
                        val info = FileDetails.app(app, entry.path)
                        val zip = DocFiles.zipEntries(app, entry.path, ZIP_PREVIEW_ROWS)
                        PreviewUi(entry, kind, index, count, loading = false, facts = info?.first ?: FileDetails.Facts(), appIcon = info?.second, zipItems = zip)
                    }
                    kind == FileKind.ARCHIVE || FileKinds.isZip(entry.name) ->
                        PreviewUi(entry, kind, index, count, loading = false, zipItems = DocFiles.zipEntries(app, entry.path))
                    else -> PreviewUi(entry, kind, index, count, loading = false, facts = FileDetails.facts(app, entry.path, kind))
                }
            }
            _state.update { s ->
                val current = s.preview?.takeIf { it.entry.path == entry.path && !it.byPlugin } ?: return@update s
                s.copy(preview = loaded.copy(choices = current.choices, choice = current.choice))
            }
        }
    }

    private fun previewStep(delta: Int) {
        val s = _state.value
        val preview = s.preview ?: return
        val files = s.entries.filterNot { it.isDirectory }
        val next = (preview.index + delta)
        if (next !in files.indices) return
        host.haptic()
        showPreview(files[next], next, files.size)
    }

    private fun pdfStep(delta: Int) {
        val preview = _state.value.preview ?: return
        val next = preview.pdfIndex + delta
        if (next !in 0 until preview.pdfCount) return
        previewJob?.cancel()
        previewJob = scope.launch {
            val page = withContext(Dispatchers.IO) { FileDetails.pdfPage(app, preview.entry.path, next, PDF_PX) } ?: return@launch
            _state.update { s -> s.preview?.takeIf { it.entry.path == preview.entry.path }?.let { s.copy(preview = it.copy(pdfPage = page.first, pdfIndex = next)) } ?: s }
        }
    }

    private fun closePreview() {
        previewJob?.cancel()
        host.filePlugins()?.runner?.cancel()
        _state.update { it.copy(preview = null, expanded = if (it.info == null) false else it.expanded) }
    }

    // ---- Info ---------------------------------------------------------------

    /** The Properties card for the previewed file, the one picked, or else the open folder. */
    private fun openInfo() {
        val s = _state.value
        val entry = s.preview?.entry
            ?: s.selection.singleOrNull()?.let { p -> s.panes.flatMap { it.entries }.firstOrNull { it.path == p } }
            ?: s.folder?.let { folder ->
                val name = (s.view as? FileView.Folder)?.let { folderCrumbs(it, s.volumes).lastOrNull()?.label } ?: File(folder).name
                FileEntry(folder, name, true, 0, if (DocFiles.isDoc(folder)) 0 else File(folder).lastModified(), "")
            }
            ?: return
        val kind = FileKinds.of(entry)
        val trail = (s.view as? FileView.Folder)?.trail.orEmpty()
        val location = if (DocFiles.isDoc(entry.path)) {
            trail.joinToString(" / ") { it.label }
        } else {
            readablePath(File(entry.path).parent ?: entry.path, s.volumes)
        }
        host.haptic()
        infoJob?.cancel()
        _state.update { it.copy(info = InfoUi(entry, kind, location, counting = entry.isDirectory, pinned = it.pins.any { p -> p.path == entry.path })) }
        infoJob = scope.launch {
            if (entry.isDirectory) {
                withContext(Dispatchers.IO) {
                    DocFiles.tally(app, entry.path) { tally ->
                        _state.update { st -> st.info?.takeIf { it.entry.path == entry.path }?.let { st.copy(info = it.copy(tally = tally)) } ?: st }
                    }
                }
                _state.update { st -> st.info?.takeIf { it.entry.path == entry.path }?.let { st.copy(info = it.copy(counting = false)) } ?: st }
            } else {
                val facts = withContext(Dispatchers.IO) { FileDetails.facts(app, entry.path, kind) }
                _state.update { st -> st.info?.takeIf { it.entry.path == entry.path }?.let { st.copy(info = it.copy(facts = facts)) } ?: st }
            }
        }
    }

    private fun closeInfo() {
        infoJob?.cancel()
        _state.update { it.copy(info = null, expanded = if (it.preview == null) false else it.expanded) }
    }

    private fun computeHash() {
        val info = _state.value.info ?: return
        if (info.entry.isDirectory || info.hashing || info.hash != null) return
        _state.update { it.copy(info = info.copy(hashing = true)) }
        infoJob = scope.launch {
            val hash = withContext(Dispatchers.IO) { DocFiles.sha256(app, info.entry.path) }
            _state.update { st -> st.info?.takeIf { it.entry.path == info.entry.path }?.let { st.copy(info = it.copy(hash = hash ?: "", hashing = false)) } ?: st }
        }
    }

    // ---- Text -------------------------------------------------------------

    fun onText(entry: FilesTextEntry) {
        if (entry.field == FilesTextField.SEARCH) runSearch(entry.text)
    }

    fun onTextEnter(entry: FilesTextEntry) {
        host.endText()
        when (entry.field) {
            FilesTextField.SEARCH -> runSearch(entry.text, now = true)
            FilesTextField.RENAME -> applyRename(entry.text)
            FilesTextField.NEW_FOLDER -> applyNewFolder(entry.text)
            FilesTextField.ZIP_NAME -> zip(entry.text)
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

    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    private fun dispatch(action: FileAction) {
        when (action) {
            is FileAction.Tap -> tap(action.entry)
            is FileAction.Hold -> hold(action.entry)
            is FileAction.SelectRange -> selectRange(action.from, action.to)
            FileAction.SelectAll -> selectAll()
            FileAction.InvertSelection -> invertSelection()
            is FileAction.OpenFolder -> openPath(action.path)
            FileAction.OpenPlaces -> show(FileView.Places)
            FileAction.OpenRecent -> show(FileView.Recent)
            is FileAction.OpenCategory -> show(FileView.Category(action.category))
            is FileAction.OpenShortcut -> show(FileView.Folder(FilePlaces.folderOf(action.shortcut).path))
            is FileAction.OpenTree -> openTree(action.tree)
            is FileAction.OpenPin -> openPin(action.pin)
            is FileAction.Unpin -> unpin(action.pin)
            FileAction.TogglePin -> togglePin()
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
            FileAction.SwapPanes -> swapPanes()
            is FileAction.FocusPane -> focusPane(action.index)
            FileAction.ToggleExpanded -> _state.update { it.copy(expanded = !it.expanded) }
            FileAction.ToggleLayout -> {
                val grid = !_state.value.prefs.grid
                host.setGrid(grid)
                _state.update { it.copy(prefs = it.prefs.copy(grid = grid)) }
            }
            is FileAction.SetSort -> setSort(action.sort)
            FileAction.ToggleHidden -> {
                val on = !_state.value.prefs.showHidden
                host.setShowHidden(on)
                _state.update { it.copy(prefs = it.prefs.copy(showHidden = on)) }
                changed()
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
            FileAction.CancelOp -> cancelOp()
            FileAction.Rename -> _state.value.selection.singleOrNull()?.let { path ->
                val name = _state.value.panes.flatMap { it.entries }.firstOrNull { it.path == path }?.name
                    ?: if (DocFiles.isDoc(path)) "" else File(path).name
                host.beginText(FilesTextEntry(FilesTextField.RENAME, name))
            }
            FileAction.Duplicate -> duplicate()
            FileAction.Zip -> askZipName()
            FileAction.Unzip -> unzip()
            FileAction.Share -> share()
            FileAction.CopyPath -> copyPath()
            FileAction.Send -> send()
            is FileAction.Type -> type(action.what)
            FileAction.CopyText -> copyText()
            is FileAction.AnswerClash -> answerClash(action.policy)
            FileAction.ToggleClashForAll -> _state.update { s -> s.copy(clash = s.clash?.let { it.copy(sameForAll = !it.sameForAll) }) }
            FileAction.CancelClash -> cancelClash()
            FileAction.Undo -> undo()
            FileAction.OpenWith -> (_state.value.preview?.entry?.path ?: _state.value.selection.singleOrNull())?.let { path ->
                val mime = _state.value.preview?.entry?.mime
                    ?: _state.value.panes.flatMap { it.entries }.firstOrNull { it.path == path }?.mime.orEmpty()
                open(path, mime, choose = true)
            }
            FileAction.NewFolder -> if (_state.value.folder != null) {
                host.beginText(FilesTextEntry(FilesTextField.NEW_FOLDER))
            }
            is FileAction.Preview -> openPreview(action.entry)
            is FileAction.PreviewWith -> previewWith(action.key)
            FileAction.PreviewPicked -> _state.value.selection.singleOrNull()?.let { path ->
                _state.value.panes.flatMap { it.entries }.firstOrNull { it.path == path && !it.isDirectory }?.let(::openPreview)
            }
            is FileAction.InsertPluginText -> if (host.secureField()) {
                notice(FileNotice.Kind.FIELD_REFUSED)
            } else {
                host.insertText(action.text)
                host.haptic()
            }
            is FileAction.CopyPluginText -> {
                copyToClipboard(action.text)
                notice(FileNotice.Kind.TEXT_COPIED)
            }
            is FileAction.PreviewStep -> previewStep(action.delta)
            is FileAction.PdfStep -> pdfStep(action.delta)
            FileAction.ToggleTypeMenu -> _state.update { s -> s.copy(preview = s.preview?.let { it.copy(typeMenu = !it.typeMenu) }) }
            FileAction.ClosePreview -> closePreview()
            FileAction.Info -> openInfo()
            FileAction.CloseInfo -> closeInfo()
            FileAction.InfoDetails -> _state.update { s -> s.copy(info = s.info?.let { it.copy(details = !it.details) }) }
            FileAction.ComputeHash -> computeHash()
            FileAction.SearchFocus -> {
                _state.update { it.copy(searches = FilePins.searches(app)) }
                host.beginText(FilesTextEntry(FilesTextField.SEARCH, _state.value.query.orEmpty()))
            }
            FileAction.ClearSearch -> clearSearch()
            is FileAction.UseSearch -> {
                host.currentText()?.let { host.beginText(it.copy(text = action.text)) }
                runSearch(action.text, now = true)
            }
            is FileAction.ForgetSearch -> {
                FilePins.forgetSearch(app, action.text)
                _state.update { it.copy(searches = FilePins.searches(app)) }
            }
            is FileAction.OpenFilter -> setFilters { it.copy(open = if (it.open == action.chip) null else action.chip) }
            is FileAction.SetCategoryFilter -> refilter { it.copy(category = action.category, open = null) }
            is FileAction.SetSizeFilter -> refilter { it.copy(size = action.size, open = null) }
            is FileAction.SetDateFilter -> refilter { it.copy(date = action.date, open = null) }
            FileAction.ToggleInContents -> refilter { it.copy(inContents = !it.inContents) }
            FileAction.TextDone -> host.currentText()?.let(::onTextEnter)
            FileAction.NoticeDismiss -> _state.update { it.copy(notice = null) }
            FileAction.Close -> close()
        }
    }

    /** A changed filter searches again straight away with whatever words are there. */
    private fun refilter(change: (SearchFilters) -> SearchFilters) {
        setFilters(change)
        val text = host.currentText()?.takeIf { it.field == FilesTextField.SEARCH }?.text ?: _state.value.query.orEmpty()
        runSearch(text, now = true)
    }

    /** The same column again flips its direction; another starts ascending, except dates and sizes, biggest and newest first. */
    private fun setSort(sort: FileSort) {
        _state.update {
            it.copy(
                sort = sort,
                descending = if (it.sort == sort) !it.descending else sort == FileSort.DATE || sort == FileSort.SIZE,
            )
        }
        changed()
    }

    companion object {
        const val TEXT_MAX = 255

        /** How much of a text file the preview reads: enough for any note, not a whole log. */
        const val PREVIEW_TEXT_MAX = 256 * 1024

        /** The largest text file Type puts into a field: a chat box is not the place for more. */
        const val TYPE_TEXT_MAX = 64L * 1024
        private const val PDF_PX = 1080
        private const val ZIP_PREVIEW_ROWS = 400
        private const val MIN_SEARCH = 2
        private const val SEARCH_DELAY_MS = 300L
        private const val NOTICE_MS = 3_000L
        private const val UNDO_MS = 8_000L
        private const val PROGRESS_MS = 120L
        private const val HISTORY_MAX = 30
        private const val CACHE_MAX = 32
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
