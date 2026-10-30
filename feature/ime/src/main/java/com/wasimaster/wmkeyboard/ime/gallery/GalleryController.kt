package com.wasimaster.wmkeyboard.ime.gallery

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.wasimaster.wmkeyboard.app.GalleryPickActivity
import com.wasimaster.wmkeyboard.app.GalleryPermissionActivity
import com.wasimaster.wmkeyboard.app.GalleryWriteActivity
import com.wasimaster.wmkeyboard.app.StoragePermissionActivity
import com.wasimaster.wmkeyboard.core.media.GallerySaver
import com.wasimaster.wmkeyboard.core.media.gallery.AlbumKind
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryAlbum
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryFilter
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryItem
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryKind
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryQuality
import com.wasimaster.wmkeyboard.core.media.gallery.GallerySentHistory
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryStore
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.media.gallery.edit.EditHistory
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEdit
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEditRenderer
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoUpscale
import com.wasimaster.wmkeyboard.core.media.gallery.edit.StickerLayer
import com.wasimaster.wmkeyboard.core.media.gallery.edit.TextLayer
import com.wasimaster.wmkeyboard.core.settings.GalleryCaption
import com.wasimaster.wmkeyboard.core.settings.GalleryFallback
import com.wasimaster.wmkeyboard.core.settings.GallerySettings
import com.wasimaster.wmkeyboard.core.upscale.UpscaleKind
import com.wasimaster.wmkeyboard.core.upscale.Upscaler
import com.wasimaster.wmkeyboard.ime.files.FilePickRequest
import com.wasimaster.wmkeyboard.ime.files.FilePicks
import com.wasimaster.wmkeyboard.ime.ui.gallery.trimGalleryImageMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The keyboard half of the gallery tool (#708).
 *
 * Holds the panel's [GalleryUi]: the albums, the open album's rows paged in
 * from [GalleryStore], the selection, each picked photo's edit, and a send in
 * progress. Turns the panel's [GalleryAction]s into changes to that state,
 * into copies made by [MediaPrep], and into commits through the [Host]. Owned
 * by the service and built on first use; nothing here runs until the panel is
 * opened or a picker hands something back.
 */
internal class GalleryController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {

    /** What the controller needs from the service, which owns the field and the keyboard's state. */
    interface Host {
        val settings: GallerySettings
        fun updateSettings(change: (GallerySettings) -> GallerySettings)
        fun haptic()

        /** Whether the field is incognito: nothing is remembered from it. */
        fun incognito(): Boolean

        /** Which field is open now, to stop a send that outlives it. */
        fun field(): FieldKey?

        /** The MIME types the open field takes through the keyboard. */
        fun acceptedMimes(): List<String>

        /** Puts [file] into the field. False when the field would not take it. */
        fun commit(file: File, mime: String): Boolean
        fun insertText(text: String)
        fun openCamera()
        fun closePanel()
        fun openSettings()
        fun beginText(entry: GalleryTextEntry)
        fun endText()
        fun currentText(): GalleryTextEntry?
        fun saveToGallery(file: File, mime: String)

        /** The picks to the Send files tool (#729), as prepared copies with their types; it opens its panel. */
        fun sendAsLink(files: List<Pair<File, String>>)
        fun stickers(): List<StickerChoice>

        /** A FileProvider URI for a copy in the media cache, for share and copy. */
        fun fileUri(file: File): Uri?

        /** [send] on the keyboard's share sheet, or Android's when that is off (#970). True when one opened. */
        fun share(send: Intent): Boolean

        /** Whether photos can be searched by what they show: search by meaning on, its pictures model here. */
        fun photoSearchReady(): Boolean

        /**
         * Brings the photos' index up to [photos] in the background, a few at
         * a time; an empty list drops it. Never on the typing path.
         */
        fun indexPhotos(photos: List<GalleryItem>)

        /** The ids of the indexed photos that show what [query] says, best first, on the main thread. */
        fun searchPhotos(query: String, onHits: (List<Long>) -> Unit)
    }

    /** One field: the app it is in and its id, which together outlive a keyboard restart. */
    data class FieldKey(val packageName: String?, val fieldId: Int?)

    private val app = context.applicationContext
    private val _state = MutableStateFlow(GalleryUi())
    val state: StateFlow<GalleryUi> = _state

    val callbacks = GalleryCallbacks(state = state, onAction = ::dispatch, stickers = { host.stickers() })

    private val history by lazy { GallerySentHistory.get(app) }
    private var observer: ContentObserver? = null
    private var loadJob: Job? = null
    private var albumsJob: Job? = null
    private var refreshJob: Job? = null
    private var sendJob: Job? = null
    private var noticeJob: Job? = null
    private var indexJob: Job? = null
    private var searchJob: Job? = null
    private var upscaleJob: Job? = null
    private var open = false

    /** The field the picks were made for; a different one drops them. */
    private var lastField: FieldKey? = null

    /** Kept across one close: the camera tile closes the panel and its photo comes back to the same picks. */
    private var keepPicksOnClose = false

    /** Files the Ask fallback is holding while the user chooses. */
    private var refused: List<MediaPrep.Prepared> = emptyList()
    private var refusedCaption: String = ""

    /** Ids for picker results and new layers; negative for picks so they never meet a library row's id. */
    private var nextPickId = -1L
    private var nextLayerId = 1L

    // ---- Lifecycle --------------------------------------------------------

    fun onPanelOpen() {
        open = true
        noteField()
        val s = host.settings
        _state.update {
            it.copy(
                columns = s.columns,
                quality = if (it.selection.isEmpty()) s.quality else it.quality,
                expanded = s.startExpanded,
                albumsOpen = false,
                viewer = null,
                editor = null,
                peek = null,
                sendMenuOpen = false,
                searchAvailable = host.photoSearchReady(),
                search = null,
            )
        }
        refreshField()
        refreshAccess(reload = true)
        if (observer == null) {
            observer = GalleryStore.watch(app) { scheduleRefresh() }
        }
        GalleryPickActivity.consume().takeIf { it.isNotEmpty() }?.let(::addPicked)
    }

    fun onPanelClose() {
        open = false
        endTextIfAny()
        observer?.let { GalleryStore.stopWatching(app, it) }
        observer = null
        loadJob?.cancel()
        refreshJob?.cancel()
        indexJob?.cancel()
        searchJob?.cancel()
        val keep = keepPicksOnClose
        keepPicksOnClose = false
        _state.update {
            it.copy(
                viewer = null,
                editor = null,
                peek = null,
                albumsOpen = false,
                sendMenuOpen = false,
                askFallback = 0,
                notice = null,
                forLink = keep && it.forLink,
                search = null,
                selection = if (keep || it.sending != null) it.selection else emptyList(),
                edits = if (keep || it.sending != null) it.edits else emptyMap(),
                caption = if (keep) it.caption else "",
            )
        }
    }

    /**
     * Back from somewhere: a grant answered, or a system picker. Picks made
     * with the panel up join the selection; with it down (a Play build, where
     * the tool is the picker) they go straight out.
     */
    fun onKeyboardShown() {
        noteField()
        if (open) {
            refreshField()
            refreshAccess(reload = _state.value.access == GalleryStore.Access.NONE)
        }
        val picks = GalleryPickActivity.consume()
        if (picks.isEmpty()) return
        if (open) addPicked(picks) else sendPicked(picks)
    }

    /**
     * Picks made for one field do not carry over to another. Coming back from
     * a picker or a grant is the same field again, so those keep them.
     */
    private fun noteField() {
        val now = host.field()
        if (lastField != null && now != lastField) onFieldChanged()
        lastField = now
    }

    private fun onFieldChanged() {
        if (_state.value.sending != null) return
        _state.update { it.copy(selection = emptyList(), edits = emptyMap(), caption = "", askFallback = 0) }
        refused = emptyList()
    }

    fun onFinish() {
        scope.launch(Dispatchers.IO) { MediaPrep.sweep(app) }
    }

    private fun refreshField() {
        val accepted = host.acceptedMimes()
        fun takes(mime: String) = accepted.any { ClipDescription.compareMimeTypes(mime, it) }
        _state.update {
            it.copy(fieldTakesImages = takes("image/jpeg") || takes("image/png"), fieldTakesVideos = takes("video/mp4"))
        }
    }

    private fun refreshAccess(reload: Boolean) {
        val access = GalleryStore.access(app)
        val withheld = host.settings.showVideos && GalleryStore.videosWithheld(app)
        // Videos granted on their own leave the access as it was, and still reload.
        val changed = access != _state.value.access || withheld != _state.value.videosWithheld
        _state.update { it.copy(access = access, videosWithheld = withheld) }
        if (access != GalleryStore.Access.NONE && (reload || changed)) {
            loadAlbums(restore = true)
            load(reset = true)
        }
        if (reload || changed) indexPhotos(access)
    }

    // ---- Search by meaning -------------------------------------------------

    /**
     * Hands the newest photos to the index, which embeds the ones it has not
     * seen. Without the permission the index is dropped: what it holds was
     * read under a grant that is gone.
     */
    private fun indexPhotos(access: GalleryStore.Access) {
        if (!host.photoSearchReady()) return
        indexJob?.cancel()
        if (access == GalleryStore.Access.NONE) {
            host.indexPhotos(emptyList())
            return
        }
        indexJob = scope.launch {
            val photos = withContext(Dispatchers.IO) {
                GalleryStore.page(
                    app, GalleryAlbum.Recents, GalleryFilter.PHOTOS, 0, INDEX_LIMIT, false, emptyList(),
                    host.settings.hiddenFolders,
                )
            }
            host.indexPhotos(photos)
        }
    }

    private fun searchFocus() {
        if (!_state.value.searchAvailable) return
        host.beginText(GalleryTextEntry(GalleryTextField.SEARCH, _state.value.search?.query.orEmpty()))
    }

    /**
     * A query typed: asked of the index once the typing pauses (the index
     * does the waiting). Too short to mean anything, the album's grid stays.
     */
    private fun setQuery(text: String) {
        val query = text.replace('\n', ' ').take(SEARCH_MAX)
        if (query.isBlank()) {
            clearSearch()
            return
        }
        val searchable = query.trim().length >= MIN_SEARCH
        _state.update { s -> s.copy(search = GallerySearch(query, pending = searchable, showing = s.search?.showing == true)) }
        if (!searchable) return
        host.searchPhotos(query) { ids -> showResults(query, ids) }
    }

    private fun showResults(query: String, ids: List<Long>) {
        if (_state.value.search?.query != query) return
        searchJob?.cancel()
        loadJob?.cancel()
        searchJob = scope.launch {
            // In the order the index ranked them; a photo deleted since it
            // was indexed is left out.
            val found = withContext(Dispatchers.IO) { GalleryStore.byIds(app, ids) }
            _state.update { s ->
                val current = s.search
                if (current == null || current.query != query) {
                    s
                } else {
                    s.copy(
                        items = found,
                        loading = false,
                        endReached = true,
                        search = current.copy(pending = false, showing = true),
                    )
                }
            }
        }
    }

    /** Back to the album as it was. */
    private fun clearSearch() {
        val was = _state.value.search ?: return
        searchJob?.cancel()
        _state.update {
            it.copy(
                search = null,
                items = if (was.showing) emptyList() else it.items,
                endReached = if (was.showing) false else it.endReached,
            )
        }
        if (was.showing) load(reset = true)
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            // A burst of changes (a sync, a burst shot) is one refresh.
            delay(REFRESH_DEBOUNCE_MS)
            loadAlbums(restore = false)
            load(reset = true, keepCount = true)
        }
    }

    // ---- Loading -----------------------------------------------------------

    private fun sentIds(): List<Long> = if (host.settings.keepHistory) history.ids() else emptyList()

    private fun loadAlbums(restore: Boolean) {
        albumsJob?.cancel()
        albumsJob = scope.launch {
            val videos = host.settings.showVideos
            val sent = withContext(Dispatchers.IO) { sentIds() }
            val hidden = host.settings.hiddenFolders
            val albums = withContext(Dispatchers.IO) { GalleryStore.albums(app, videos, sent, hidden) }
            val remembered = host.settings.lastAlbum
            _state.update { s ->
                val current = if (restore && host.settings.rememberAlbum && remembered.isNotEmpty()) {
                    albums.firstOrNull { it.key == remembered } ?: s.album
                } else {
                    albums.firstOrNull { it.key == s.album.key } ?: s.album
                }
                s.copy(albums = albums, album = current, sentIds = sent.toHashSet())
            }
            if (restore && _state.value.items.isEmpty()) load(reset = true)
        }
    }

    private fun load(reset: Boolean, keepCount: Boolean = false) {
        val s = _state.value
        if (s.access == GalleryStore.Access.NONE) return
        // Search results hold the grid; the album comes back when they go.
        if (s.search?.showing == true) return
        if (!reset && (s.loading || s.endReached)) return
        loadJob?.cancel()
        val offset = if (reset) 0 else s.items.size
        val limit = if (reset && keepCount) maxOf(PAGE, s.items.size) else PAGE
        val album = s.album
        val filter = s.filter
        _state.update { it.copy(loading = true) }
        loadJob = scope.launch {
            val videos = host.settings.showVideos
            val sent = if (album.kind == AlbumKind.SENT) withContext(Dispatchers.IO) { sentIds() } else emptyList()
            val page = withContext(Dispatchers.IO) {
                GalleryStore.page(app, album, filter, offset, limit, videos, sent, host.settings.hiddenFolders)
            }
            _state.update {
                if (it.album.key != album.key || it.filter != filter || it.search?.showing == true) return@update it
                // A pick that is in the page takes its fresh row, so a photo
                // changed on disk (replaced from the editor) shows as it is now.
                val fresh = page.associateBy { row -> row.id }
                it.copy(
                    items = if (reset) page else it.items + page,
                    loading = false,
                    endReached = page.size < limit,
                    selection = it.selection.map { sel -> fresh[sel.id] ?: sel },
                )
            }
        }
    }

    // ---- Actions -----------------------------------------------------------

    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    private fun dispatch(action: GalleryAction) {
        when (action) {
            GalleryAction.AskPermission -> startScreen(Intent(app, GalleryPermissionActivity::class.java))
            // With no library grant this button promised the picker that needs
            // none, so that is what it opens, not a second grant to ask for.
            GalleryAction.PickWithSystem -> if (_state.value.access == GalleryStore.Access.NONE) {
                startScreen(GalleryPickActivity.intent(app, files = false, limit = room()))
            } else {
                pick(FilePickRequest.GalleryMedia(room()))
            }
            GalleryAction.PickFiles -> pick(FilePickRequest.GalleryFiles(room()))
            GalleryAction.OpenCamera -> {
                keepPicksOnClose = true
                host.openCamera()
            }
            GalleryAction.OpenSettings -> host.openSettings()

            is GalleryAction.OpenAlbum -> {
                dropSearch()
                _state.update {
                    it.copy(album = action.album, albumsOpen = false, items = emptyList(), endReached = false)
                }
                if (host.settings.rememberAlbum) host.updateSettings { it.copy(lastAlbum = action.album.key) }
                load(reset = true)
            }
            GalleryAction.ToggleAlbums -> _state.update { it.copy(albumsOpen = !it.albumsOpen) }
            is GalleryAction.SetFilter -> {
                if (action.filter == _state.value.filter) return
                dropSearch()
                _state.update { it.copy(filter = action.filter, items = emptyList(), endReached = false) }
                load(reset = true)
            }
            GalleryAction.LoadMore -> load(reset = false)
            is GalleryAction.SetColumns -> {
                val c = action.columns.coerceIn(GallerySettings.MIN_COLUMNS, GallerySettings.MAX_COLUMNS)
                if (c == _state.value.columns) return
                _state.update { it.copy(columns = c) }
                host.updateSettings { it.copy(columns = c) }
            }
            GalleryAction.ToggleExpanded -> _state.update { it.copy(expanded = !it.expanded) }
            is GalleryAction.SetExpanded -> _state.update { it.copy(expanded = action.on) }
            is GalleryAction.Peek -> _state.update { it.copy(peek = action.item) }
            GalleryAction.SearchFocus -> searchFocus()
            GalleryAction.ClearSearch -> {
                if (host.currentText()?.field == GalleryTextField.SEARCH) host.endText()
                clearSearch()
            }

            is GalleryAction.Toggle -> toggle(action.item)
            is GalleryAction.SelectRange -> selectRange(action.items, action.select)
            is GalleryAction.Remove -> remove(action.id)
            is GalleryAction.Move -> _state.update { s ->
                if (action.from !in s.selection.indices || action.to !in s.selection.indices) {
                    s
                } else {
                    s.copy(selection = s.selection.toMutableList().apply { add(action.to, removeAt(action.from)) })
                }
            }
            GalleryAction.ClearSelection -> {
                host.haptic()
                _state.update { it.copy(selection = emptyList(), edits = emptyMap(), sendMenuOpen = false) }
            }

            is GalleryAction.OpenViewer -> _state.update {
                it.copy(viewer = ViewerUi(fromSelection = action.fromSelection, index = action.index), peek = null)
            }
            is GalleryAction.ViewerPage -> _state.update { it.copy(viewer = it.viewer?.copy(index = action.index)) }
            GalleryAction.ViewerChrome -> _state.update { s -> s.copy(viewer = s.viewer?.let { v -> v.copy(chrome = !v.chrome) }) }
            GalleryAction.ViewerMute -> _state.update { s -> s.copy(viewer = s.viewer?.let { v -> v.copy(muted = !v.muted) }) }
            GalleryAction.ViewerInfo -> _state.update { s -> s.copy(viewer = s.viewer?.let { v -> v.copy(infoOpen = !v.infoOpen) }) }
            GalleryAction.CloseViewer -> {
                endTextIfAny()
                _state.update { it.copy(viewer = null) }
            }

            is GalleryAction.SetQuality -> _state.update { it.copy(quality = action.quality) }
            GalleryAction.CaptionFocus -> {
                if (host.settings.caption == GalleryCaption.OFF) return
                host.beginText(GalleryTextEntry(GalleryTextField.CAPTION, _state.value.caption))
            }
            GalleryAction.TextDone -> host.currentText()?.let(::onTextEnter) ?: endTextIfAny()
            is GalleryAction.Send -> send(action.mode)
            is GalleryAction.SendMenu -> _state.update { it.copy(sendMenuOpen = action.open) }
            is GalleryAction.FallbackChoice -> fallbackChoice(action.share)
            GalleryAction.NoticeDismiss -> _state.update { it.copy(notice = null) }

            is GalleryAction.OpenEditor -> openEditor(action.id)
            GalleryAction.CloseEditor -> {
                endTextIfAny()
                upscaleJob?.cancel()
                _state.update { it.copy(editor = null) }
            }
            is GalleryAction.Tool -> {
                endTextIfAny()
                editor { it.copy(tool = action.tool, selectedLayer = null) }
            }
            is GalleryAction.EditPreview -> history { it.preview(action.edit) }
            is GalleryAction.EditCommit -> history { it.push(action.edit) }
            GalleryAction.Undo -> history { it.undone() }
            GalleryAction.Redo -> history { it.redone() }
            GalleryAction.ResetEdit -> {
                host.haptic()
                history { it.push(PhotoEdit.NONE) }
            }
            is GalleryAction.Brush -> editor { it.copy(brush = action.brush) }
            is GalleryAction.BrushColor -> {
                editor { it.copy(color = action.color) }
                recolourSelectedLayer(action.color)
            }
            is GalleryAction.BrushSize -> editor { it.copy(brushSize = action.size) }
            is GalleryAction.Aspect -> editor { it.copy(aspect = action.aspect) }
            is GalleryAction.Knob -> editor { it.copy(knob = action.knob) }
            is GalleryAction.AddText -> addText(action.x, action.y)
            is GalleryAction.TypeLayer -> typeLayer(action.id)
            is GalleryAction.SelectLayer -> editor { it.copy(selectedLayer = action.id) }
            is GalleryAction.DeleteLayer -> deleteLayer(action.id)
            is GalleryAction.LayerStyle -> {
                editor { it.copy(textStyle = action.style) }
                editLayer { it.copy(style = action.style) }
            }
            is GalleryAction.LayerAlign -> {
                editor { it.copy(textAlign = action.align) }
                editLayer { it.copy(align = action.align) }
            }
            is GalleryAction.AddSticker -> addSticker(action.path, action.emoji)
            is GalleryAction.Compare -> editor { it.copy(comparing = action.on) }
            is GalleryAction.SaveMenu -> editor { it.copy(saveMenuOpen = action.open) }
            is GalleryAction.SaveEdit -> saveEdit(action.replace)
            is GalleryAction.UpscaleArt -> editor { it.copy(upscaleArt = action.art, upscaleFailed = false) }
            is GalleryAction.Upscale -> upscale(action.factor)
            GalleryAction.CancelUpscale -> upscaleJob?.cancel()
        }
    }

    /** An album or a filter picked mid-search: the search goes, and the pick loads as usual. */
    private fun dropSearch() {
        if (_state.value.search == null) return
        searchJob?.cancel()
        if (host.currentText()?.field == GalleryTextField.SEARCH) host.endText()
        _state.update { it.copy(search = null) }
    }

    private fun startScreen(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The file manager in place of this panel, which comes back with its picks when it does; or Android's picker. */
    private fun pick(request: FilePickRequest) {
        keepPicksOnClose = FilePicks.panelReady()
        runCatching { FilePicks.open(app, request) }
    }

    /** How many more fit in the selection; at least one, so a picker always opens. */
    private fun room(): Int = (host.settings.maxSelection - _state.value.selection.size).coerceAtLeast(1)

    // ---- Selection ----------------------------------------------------------

    private fun toggle(item: GalleryItem) {
        val s = _state.value
        if (s.selection.any { it.id == item.id }) {
            remove(item.id)
            return
        }
        if (s.selection.size >= host.settings.maxSelection) {
            notice(GalleryNotice.Kind.LIMIT, host.settings.maxSelection)
            return
        }
        if (host.settings.haptics) host.haptic()
        _state.update { it.copy(selection = it.selection + item) }
        if (item.isVideo && !s.fieldTakesVideos && s.fieldTakesImages) notice(GalleryNotice.Kind.NO_VIDEOS)
    }

    private fun selectRange(items: List<GalleryItem>, select: Boolean) {
        val max = host.settings.maxSelection
        var hitLimit = false
        _state.update { s ->
            val ids = s.selectedIds
            if (select) {
                val adding = items.filter { it.id !in ids }
                val fits = (max - s.selection.size).coerceAtLeast(0)
                if (adding.size > fits) hitLimit = true
                s.copy(selection = s.selection + adding.take(fits))
            } else {
                val gone = items.mapTo(HashSet()) { it.id }
                s.copy(selection = s.selection.filterNot { it.id in gone }, edits = s.edits - gone)
            }
        }
        if (hitLimit) notice(GalleryNotice.Kind.LIMIT, max)
    }

    private fun remove(id: Long) {
        if (host.settings.haptics) host.haptic()
        _state.update { s ->
            val selection = s.selection.filterNot { it.id == id }
            s.copy(
                selection = selection,
                edits = s.edits - id,
                // Paging the selection, the viewer stays on a real page.
                viewer = s.viewer?.let { v ->
                    if (!v.fromSelection) v else if (selection.isEmpty()) null else v.copy(index = v.index.coerceAtMost(selection.lastIndex))
                },
                editor = s.editor?.takeIf { it.itemId != id },
                sendMenuOpen = s.sendMenuOpen && selection.isNotEmpty(),
            )
        }
    }

    /** Picks from a system picker, joining the selection as items of their own. */
    private fun addPicked(picks: List<GalleryPickActivity.Picked>) {
        val items = picks.map(::itemOf)
        val max = host.settings.maxSelection
        val fits = (max - _state.value.selection.size).coerceAtLeast(0)
        _state.update { it.copy(selection = it.selection + items.take(fits)) }
        if (items.size > fits) notice(GalleryNotice.Kind.LIMIT, max)
    }

    private fun itemOf(p: GalleryPickActivity.Picked): GalleryItem {
        val kind = when {
            p.mime == "image/gif" -> GalleryKind.GIF
            p.mime.startsWith("video/") -> GalleryKind.VIDEO
            else -> GalleryKind.IMAGE
        }
        return GalleryItem(
            id = nextPickId--, mime = p.mime, kind = kind, name = p.name, width = 0, height = 0, orientation = 0,
            size = p.size, durationMs = 0, dateMs = System.currentTimeMillis(), bucketId = "", bucketName = "",
            favorite = false, sourceUri = p.uri,
        )
    }

    // ---- Text ---------------------------------------------------------------

    private fun endTextIfAny() {
        if (host.currentText() != null) host.endText()
        _state.update { it.copy(editor = it.editor?.copy(typingLayer = null)) }
    }

    /** Called by the service as the box is typed into. */
    fun onText(entry: GalleryTextEntry) {
        when (entry.field) {
            GalleryTextField.CAPTION -> _state.update { it.copy(caption = entry.text.take(CAPTION_MAX)) }
            GalleryTextField.SEARCH -> setQuery(entry.text)
            GalleryTextField.LAYER -> {
                val id = _state.value.editor?.itemId ?: return
                val edit = _state.value.editOf(id)
                history {
                    it.preview(edit.copy(texts = edit.texts.map { t -> if (t.id == entry.ref) t.copy(text = entry.text) else t }))
                }
            }
        }
    }

    /** Enter in the box, or its Done: the caption is kept, a text layer is finished. */
    fun onTextEnter(entry: GalleryTextEntry) {
        onText(entry)
        if (entry.field == GalleryTextField.LAYER) {
            val id = _state.value.editor?.itemId
            if (id != null) {
                val edit = _state.value.editOf(id)
                val texts = edit.texts.filterNot { it.id == entry.ref && it.text.isBlank() }
                history { it.push(edit.copy(texts = texts)) }
            }
        }
        host.endText()
        _state.update { it.copy(editor = it.editor?.copy(typingLayer = null)) }
    }

    // ---- Editor -------------------------------------------------------------

    private fun openEditor(id: Long) {
        val item = _state.value.selection.firstOrNull { it.id == id }
            ?: _state.value.items.firstOrNull { it.id == id } ?: return
        if (item.kind != GalleryKind.IMAGE) return
        // Editing a photo is choosing it.
        if (_state.value.selection.none { it.id == id }) toggle(item)
        if (_state.value.selection.none { it.id == id }) return
        // A PNG is most often a screenshot or a drawing, which the network
        // for drawings and text enlarges better; anything else is a photo.
        val art = item.mime == "image/png" || item.mime == "image/gif"
        _state.update { it.copy(editor = EditorUi(itemId = id, upscaleArt = art), sendMenuOpen = false) }
    }

    private fun editor(change: (EditorUi) -> EditorUi) = _state.update { it.copy(editor = it.editor?.let(change)) }

    private fun history(change: (EditHistory) -> EditHistory) {
        val id = _state.value.editor?.itemId ?: return
        _state.update { s -> s.copy(edits = s.edits + (id to change(s.edits[id] ?: EditHistory()))) }
    }

    private fun editLayer(change: (TextLayer) -> TextLayer) {
        val e = _state.value.editor ?: return
        val layerId = e.selectedLayer ?: e.typingLayer ?: return
        val edit = _state.value.editOf(e.itemId)
        if (edit.texts.none { it.id == layerId }) return
        history { it.push(edit.copy(texts = edit.texts.map { t -> if (t.id == layerId) change(t) else t })) }
    }

    private fun recolourSelectedLayer(color: Int) {
        val e = _state.value.editor ?: return
        if (e.tool != EditorTool.TEXT) return
        editLayer { it.copy(color = color) }
    }

    private fun addText(x: Float, y: Float) {
        val e = _state.value.editor ?: return
        val edit = _state.value.editOf(e.itemId)
        val layer = TextLayer(
            id = nextLayerId++, text = "", color = e.color, style = e.textStyle, align = e.textAlign,
            center = com.wasimaster.wmkeyboard.core.media.gallery.edit.NPoint(x.coerceIn(0.05f, 0.95f), y.coerceIn(0.05f, 0.95f)),
        )
        history { it.push(edit.copy(texts = edit.texts + layer)) }
        editor { it.copy(tool = EditorTool.TEXT, selectedLayer = layer.id, typingLayer = layer.id) }
        host.beginText(GalleryTextEntry(GalleryTextField.LAYER, "", layer.id))
    }

    private fun typeLayer(id: Long) {
        val e = _state.value.editor ?: return
        val layer = _state.value.editOf(e.itemId).texts.firstOrNull { it.id == id } ?: return
        editor { it.copy(selectedLayer = id, typingLayer = id, textStyle = layer.style, textAlign = layer.align) }
        host.beginText(GalleryTextEntry(GalleryTextField.LAYER, layer.text, id))
    }

    private fun deleteLayer(id: Long) {
        val e = _state.value.editor ?: return
        val edit = _state.value.editOf(e.itemId)
        if (e.typingLayer == id) endTextIfAny()
        host.haptic()
        history { it.push(edit.copy(texts = edit.texts.filterNot { it.id == id }, stickers = edit.stickers.filterNot { it.id == id })) }
        editor { it.copy(selectedLayer = null) }
    }

    private fun addSticker(path: String?, emoji: String?) {
        val e = _state.value.editor ?: return
        val edit = _state.value.editOf(e.itemId)
        // Each new one a little off the last, so a second does not hide under the first.
        val nudge = (edit.stickers.size % STICKER_STAGGER) * 0.06f
        val layer = StickerLayer(
            id = nextLayerId++, path = path, emoji = emoji,
            center = com.wasimaster.wmkeyboard.core.media.gallery.edit.NPoint(0.5f + nudge, 0.5f + nudge),
        )
        host.haptic()
        history { it.push(edit.copy(stickers = edit.stickers + layer)) }
        editor { it.copy(selectedLayer = layer.id) }
    }

    // ---- Upscaling ----------------------------------------------------------------

    /**
     * Enlarges the photo open in the editor by [factor] with the upscaler, in
     * the sticker generator's process, and makes that an undoable step: the
     * edit says which copy it draws on ([PhotoEdit.upscale]), and the copy
     * waits in the media cache under [GalleryUi.upscaled]. The photo is read
     * at the size the result can take ([MediaPrep.MAX_DECODE_EDGE] over the
     * factor); the tool only offers a factor a photo is small enough for.
     */
    private fun upscale(factor: Int) {
        val e = _state.value.editor ?: return
        if (upscaleJob?.isActive == true) return
        val item = _state.value.selection.firstOrNull { it.id == e.itemId } ?: return
        val want = PhotoUpscale(factor, e.upscaleArt)
        val key = upscaleKey(item.id, want)
        endTextIfAny()
        host.haptic()
        if (_state.value.upscaled[key]?.isFile == true) {
            history { it.push(it.current.copy(upscale = want)) }
            return
        }
        editor { it.copy(upscaling = UpscaleProgress(), upscaleFailed = false) }
        upscaleJob = scope.launch {
            var input: File? = null
            var ok = false
            try {
                input = withContext(Dispatchers.IO) {
                    val photo = MediaPrep.decode(app, item.uri, MediaPrep.MAX_DECODE_EDGE / factor) ?: return@withContext null
                    val alpha = photo.hasAlpha()
                    val file = File(Upscaler.workDir(app), "photo_${System.nanoTime()}.${if (alpha) "png" else "jpg"}")
                    val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    file.outputStream().use { photo.compress(format, UPSCALE_INPUT_QUALITY, it) }
                    photo.recycle()
                    file
                }
                val source = input ?: return@launch
                val out = File(MediaPrep.outputDir(app), "UPSCALED_${System.nanoTime()}.${source.extension}")
                val kind = if (want.art) UpscaleKind.ART else UpscaleKind.PHOTO
                Upscaler.upscale(app, source, out, kind, factor).collect { event ->
                    when (event) {
                        is Upscaler.Event.Downloading -> editor {
                            it.copy(upscaling = UpscaleProgress(if (event.total > 0) event.bytes.toFloat() / event.total else -1f, downloading = true))
                        }
                        is Upscaler.Event.Working -> editor {
                            it.copy(upscaling = UpscaleProgress(event.done.toFloat() / event.total.coerceAtLeast(1)))
                        }
                        is Upscaler.Event.Done -> {
                            ok = true
                            _state.update { s -> s.copy(upscaled = s.upscaled + (key to event.file)) }
                            // The step goes on this photo only while its editor is still the open one.
                            if (_state.value.editor?.itemId == item.id) history { it.push(it.current.copy(upscale = want)) }
                        }
                        is Upscaler.Event.Failed -> Unit
                    }
                }
            } finally {
                input?.delete()
                val failed = !ok && isActive
                editor { if (it.itemId == item.id) it.copy(upscaling = null, upscaleFailed = failed) else it }
            }
        }
    }

    /** Where the photo's pixels are read for its edit: the upscaled copy, or the photo. */
    private fun sourceOf(item: GalleryItem, edit: PhotoEdit): Uri =
        _state.value.upscaledFile(item.id, edit)?.let(Uri::fromFile) ?: item.uri

    // ---- Saving to the gallery ---------------------------------------------------

    /**
     * Keeps the edited photo in the phone's gallery: a new picture beside the
     * original, or written over it. Drawn at the editor's largest size in the
     * photo's own format, whatever the send bar's quality says.
     */
    private fun saveEdit(replace: Boolean) {
        val e = _state.value.editor ?: return
        if (e.saving) return
        val item = _state.value.selection.firstOrNull { it.id == e.itemId } ?: return
        val edit = _state.value.editOf(item.id)
        if (edit.isEmpty || (replace && !item.replaceable)) return
        endTextIfAny()
        host.haptic()
        if (!GallerySaver.canSave(app)) {
            // Below Android 10 the gallery is the storage permission's.
            editor { it.copy(saveMenuOpen = false) }
            startScreen(Intent(app, StoragePermissionActivity::class.java))
            return
        }
        editor { it.copy(saving = true, saveMenuOpen = false) }
        scope.launch {
            var asking = false
            try {
                val kept = withContext(Dispatchers.IO) {
                    MediaPrep.renderToKeep(app, item, sourceOf(item, edit)) { bmp -> PhotoEditRenderer.render(bmp, edit, StickerCache()) }
                }
                if (kept == null) {
                    notice(GalleryNotice.Kind.SAVE_FAILED)
                } else if (replace) {
                    asking = replaceOriginal(item, kept)
                } else {
                    saveCopy(item, kept)
                }
            } finally {
                if (!asking) savingDone(item.id)
            }
        }
    }

    private fun savingDone(id: Long) = _state.update { s ->
        s.copy(editor = s.editor?.let { if (it.itemId == id) it.copy(saving = false) else it })
    }

    private suspend fun saveCopy(item: GalleryItem, kept: MediaPrep.Prepared) {
        val saved = withContext(Dispatchers.IO) {
            GallerySaver.save(app, kept.file, kept.mime, copyName(item, kept.mime), GalleryStore.pictureFolderOf(app, item))
                .also { kept.file.delete() }
        }
        notice(if (saved != null) GalleryNotice.Kind.SAVED else GalleryNotice.Kind.SAVE_FAILED, 1)
    }

    /** "PXL_20261018_101500_edited.jpg": the photo's own name, marked, for the copy beside it. */
    private fun copyName(item: GalleryItem, mime: String): String {
        val base = item.name.substringBeforeLast('.').ifBlank {
            "IMG_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        }
        return "${base}_edited.${MediaPrep.extensionFor(mime)}"
    }

    /**
     * Writes [kept] over [item]'s file. True while Android's consent dialog
     * is up: the save is still under way until the user answers it.
     */
    private suspend fun replaceOriginal(item: GalleryItem, kept: MediaPrep.Prepared): Boolean {
        when (val r = withContext(Dispatchers.IO) { GallerySaver.overwrite(app, item.uri, kept.file) }) {
            GallerySaver.Overwrite.Done -> replaced(item, kept)
            is GallerySaver.Overwrite.NeedsConsent -> {
                startScreen(
                    GalleryWriteActivity.intent(app, r.sender) { yes ->
                        if (!yes) {
                            kept.file.delete()
                            notice(GalleryNotice.Kind.NOT_REPLACED)
                            savingDone(item.id)
                        } else {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { GallerySaver.write(app, item.uri, kept.file) }.isSuccess
                                }
                                if (ok) {
                                    replaced(item, kept)
                                } else {
                                    kept.file.delete()
                                    notice(GalleryNotice.Kind.SAVE_FAILED)
                                }
                                savingDone(item.id)
                            }
                        }
                    },
                )
                return true
            }
            GallerySaver.Overwrite.NeedsPermission -> {
                kept.file.delete()
                startScreen(Intent(app, StoragePermissionActivity::class.java))
            }
            GallerySaver.Overwrite.Failed -> {
                kept.file.delete()
                notice(GalleryNotice.Kind.SAVE_FAILED)
            }
        }
        return false
    }

    /**
     * The photo now holds the edit, so the edit goes: kept on top of the
     * new file it would be drawn twice. The editor closes on the result.
     */
    private fun replaced(item: GalleryItem, kept: MediaPrep.Prepared) {
        kept.file.delete()
        trimGalleryImageMemory()
        _state.update { s -> s.copy(edits = s.edits - item.id, editor = s.editor?.takeIf { it.itemId != item.id }) }
        notice(GalleryNotice.Kind.REPLACED)
        // The store rescans the file once it is written; the watcher reloads
        // the grid then, and this catches a store that does not say so.
        scheduleRefresh()
    }

    // ---- Sending --------------------------------------------------------------

    private fun notice(kind: GalleryNotice.Kind, count: Int = 0) {
        _state.update { it.copy(notice = GalleryNotice(kind, count)) }
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            _state.update { if (it.notice?.kind == kind) it.copy(notice = null) else it }
        }
    }

    private fun options(quality: GalleryQuality): MediaPrep.Options {
        val s = host.settings
        return MediaPrep.Options(
            quality = quality,
            stripLocation = s.stripLocation,
            stripAll = s.stripAllMetadata,
            randomName = s.randomFileNames,
        )
    }

    /** The sticker pictures an edit places, decoded once per send. */
    private class StickerCache : PhotoEditRenderer.StickerSource {
        private val cache = HashMap<String, Bitmap?>()
        override fun bitmap(layer: StickerLayer): Bitmap? {
            val path = layer.path ?: return null
            return cache.getOrPut(path) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        }
    }

    /** The copy of [item] that is sent, made on the calling (background) thread. */
    private fun prepare(item: GalleryItem, edit: PhotoEdit, quality: GalleryQuality, stickers: StickerCache): MediaPrep.Prepared? {
        val opts = options(quality)
        val mime = item.mime
        return when {
            item.kind == GalleryKind.VIDEO -> MediaPrep.prepareVideo(app, item, opts)
            !mime.startsWith("image/") ->
                MediaPrep.copyAsIs(app, item, mime, item.name.substringAfterLast('.', MediaPrep.extensionFor(mime)), opts)
            edit.isEmpty -> MediaPrep.prepareImage(app, item, opts)
            else -> MediaPrep.prepareImage(app, item, opts, sourceOf(item, edit)) { bmp -> PhotoEditRenderer.render(bmp, edit, stickers) }
        }
    }

    /** The Send files tool opened this panel: the Send button hands the picks to it (#729). */
    fun openForLink() {
        _state.update { it.copy(forLink = true) }
    }

    private fun send(requested: SendMode) {
        val s = _state.value
        if (s.selection.isEmpty() || sendJob?.isActive == true) return
        val mode = if (requested == SendMode.NORMAL && s.forLink) SendMode.AS_LINK else requested
        endTextIfAny()
        host.haptic()
        val captionSetting = host.settings.caption
        val caption = if (mode == SendMode.WITHOUT_CAPTION || captionSetting == GalleryCaption.OFF) "" else s.caption.trim()
        val quality = if (mode == SendMode.AS_FILE) GalleryQuality.ORIGINAL else s.quality
        val items = s.selection
        val edits = items.associate { it.id to s.editOf(it.id) }
        val field = host.field()
        upscaleJob?.cancel()
        _state.update { it.copy(sending = SendProgress(0, items.size), sendMenuOpen = false, editor = null, viewer = null) }
        sendJob = scope.launch {
            try {
                val stickers = StickerCache()
                val prepared = ArrayList<Pair<GalleryItem, MediaPrep.Prepared>>()
                var failed = 0
                for (item in items) {
                    _state.update { it.copy(sending = it.sending?.copy(preparingId = item.id)) }
                    val p = withContext(Dispatchers.IO) { prepare(item, edits[item.id] ?: PhotoEdit.NONE, quality, stickers) }
                    if (p == null) failed++ else prepared += item to p
                }
                if (failed > 0) notice(GalleryNotice.Kind.FAILED, failed)
                if (prepared.isEmpty()) return@launch
                val delivered = when (mode) {
                    SendMode.COPY -> copy(prepared.map { it.second })
                    SendMode.SHARE_TO_APP -> share(prepared.map { it.second }, caption, toApp = field?.packageName)
                    SendMode.SHARE -> share(prepared.map { it.second }, caption, toApp = null)
                    SendMode.SAVE -> {
                        prepared.forEach { (_, p) -> host.saveToGallery(p.file, p.mime) }
                        notice(GalleryNotice.Kind.SAVED, prepared.size)
                        false
                    }
                    SendMode.AS_LINK -> {
                        host.sendAsLink(prepared.map { (_, p) -> p.file to p.mime })
                        false
                    }
                    else -> commitAll(prepared, caption, captionSetting, field, items.size)
                }
                val savedEdited = host.settings.saveEdited && mode != SendMode.SAVE
                if (savedEdited) {
                    prepared.filter { (item, _) -> edits[item.id]?.isEmpty == false }
                        .forEach { (_, p) -> host.saveToGallery(p.file, p.mime) }
                }
                if (delivered) finishSend(prepared.map { it.first })
            } finally {
                _state.update { it.copy(sending = null) }
            }
        }
    }

    /**
     * Commits each copy in turn, a breath apart, while the field stays the one
     * they were sent to. True when something reached the app, by commit or by
     * the fallback.
     *
     * Some apps answer a committed picture with a send screen of their own
     * (WhatsApp, Signal): the field goes, and the pictures after the first
     * used to be dropped without a word (#956). So the first commit is given
     * time to show whether the app moves on, and once it has, the rest go to
     * that app together through its share screen rather than one commit each.
     * The first goes with them: the share screen takes the place of the one
     * it opened, and left out it was lost there (#1022).
     */
    private suspend fun commitAll(
        prepared: List<Pair<GalleryItem, MediaPrep.Prepared>>,
        caption: String,
        placement: GalleryCaption,
        field: FieldKey?,
        total: Int,
    ): Boolean {
        val captionFirst = caption.isNotEmpty() && placement == GalleryCaption.BEFORE
        if (captionFirst) host.insertText(caption)
        val notTaken = ArrayList<MediaPrep.Prepared>()
        var movedOn = emptyList<MediaPrep.Prepared>()
        var committed = 0
        for ((i, pair) in prepared.withIndex()) {
            if (host.field() != field) {
                movedOn = (if (i <= 1) prepared else prepared.drop(i)).map { it.second }
                break
            }
            val p = pair.second
            if (host.commit(p.file, p.mime)) committed++ else notTaken += p
            _state.update { it.copy(sending = SendProgress(i + 1, total)) }
            if (i < prepared.lastIndex) {
                if (i == 0 && committed == 1) awaitFieldChange(field) else delay(SEND_GAP_MS)
            }
        }
        if (movedOn.isNotEmpty()) {
            val shared = share(movedOn, if (captionFirst) "" else caption, toApp = field?.packageName)
            return shared || committed > 0
        }
        if (caption.isNotEmpty() && !captionFirst && committed > 0) host.insertText(caption)
        if (notTaken.isEmpty()) return committed > 0
        // What the field would not take: the setting says where it goes.
        val leftCaption = if (committed == 0 && !captionFirst) caption else ""
        return when (host.settings.fallback) {
            GalleryFallback.SHARE_TO_APP -> share(notTaken, leftCaption, toApp = field?.packageName) || committed > 0
            GalleryFallback.CLIPBOARD -> copy(notTaken) || committed > 0
            GalleryFallback.ASK -> {
                refused = notTaken
                refusedCaption = leftCaption
                _state.update { it.copy(askFallback = notTaken.size) }
                committed > 0
            }
        }
    }

    /**
     * Waits after the first picture of several went in, up to
     * [FIRST_COMMIT_SETTLE_MS], for the app to leave [field] for a send screen
     * of its own; returns as soon as it has. A field that stays costs the
     * whole wait once per send. See [commitAll].
     */
    private suspend fun awaitFieldChange(field: FieldKey?) {
        var waited = 0L
        while (waited < FIRST_COMMIT_SETTLE_MS) {
            delay(FIELD_POLL_MS)
            waited += FIELD_POLL_MS
            if (host.field() != field) return
        }
    }

    private fun fallbackChoice(share: Boolean?) {
        val files = refused
        val caption = refusedCaption
        refused = emptyList()
        _state.update { it.copy(askFallback = 0) }
        when (share) {
            true -> if (share(files, caption, toApp = host.field()?.packageName)) finishSend(emptyList())
            false -> if (copy(files)) finishSend(emptyList())
            null -> Unit
        }
    }

    private fun finishSend(sent: List<GalleryItem>) {
        val ids = sent.filter { !it.isPicked }.map { it.id }
        if (ids.isNotEmpty() && host.settings.keepHistory && !host.incognito()) {
            scope.launch(Dispatchers.IO) { history.record(ids) }
            _state.update { it.copy(sentIds = it.sentIds + ids) }
        }
        _state.update { it.copy(selection = emptyList(), edits = emptyMap(), caption = "", viewer = null, editor = null) }
        if (host.settings.closeAfterSend && open) host.closePanel()
    }

    private fun copy(files: List<MediaPrep.Prepared>): Boolean {
        val uris = files.mapNotNull { host.fileUri(it.file) }
        if (uris.isEmpty()) return false
        val mimes = files.map { it.mime }.distinct().toTypedArray()
        val clip = ClipData(ClipDescription("media", mimes), ClipData.Item(uris.first()))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val ok = runCatching {
            (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        }.isSuccess
        if (ok) notice(GalleryNotice.Kind.COPIED, uris.size)
        return ok
    }

    /** The share sheet with [files], aimed at [toApp] when it can take them there. */
    private fun share(files: List<MediaPrep.Prepared>, caption: String, toApp: String?): Boolean {
        val uris = files.mapNotNull { host.fileUri(it.file) }
        if (uris.isEmpty()) return false
        val mime = commonMime(files.map { it.mime })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mime
        // The grant travels on the clip data; the extra alone carries none.
        intent.clipData = ClipData(ClipDescription("media", arrayOf(mime)), ClipData.Item(uris.first())).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        if (caption.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, caption)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Aimed at the app first, with no lookup beforehand: package visibility
        // hides other apps' share screens from a query but not from a launch,
        // so the launch itself is the test, and the share sheet is the way out.
        val ok = (toApp != null && runCatching { context.startActivity(Intent(intent).setPackage(toApp)) }.isSuccess) ||
            host.share(intent)
        if (ok) notice(GalleryNotice.Kind.SHARED, uris.size)
        return ok
    }

    /**
     * Picks from a Play build's photo picker, with no panel to show them in:
     * prepared and sent at once, as a tap on Send would.
     */
    private fun sendPicked(picks: List<GalleryPickActivity.Picked>) {
        val items = picks.map(::itemOf)
        _state.update { it.copy(selection = items, edits = emptyMap(), quality = host.settings.quality, caption = "") }
        send(SendMode.NORMAL)
    }

    companion object {
        const val PAGE = 120
        const val CAPTION_MAX = 4096

        /** The longest search query kept. */
        const val SEARCH_MAX = 200
        private const val MIN_SEARCH = 2

        /** The newest photos the index keeps: older ones are dropped from it. */
        private const val INDEX_LIMIT = 3000
        private const val SEND_GAP_MS = 350L

        /** How long the first of several pictures is given to show whether the app opens a send screen for it (#956). */
        private const val FIRST_COMMIT_SETTLE_MS = 1_200L
        private const val FIELD_POLL_MS = 100L
        private const val NOTICE_MS = 2600L
        private const val REFRESH_DEBOUNCE_MS = 450L
        private const val STICKER_STAGGER = 5

        /** The photo handed to the upscaler, when it has no transparency: a JPEG close enough to lossless. */
        private const val UPSCALE_INPUT_QUALITY = 98

        /** The narrowest type that covers every one of [mimes]: `image/jpeg`, `image/*`, or `*/*`. */
        fun commonMime(mimes: List<String>): String {
            val distinct = mimes.distinct()
            if (distinct.size == 1) return distinct.single()
            val tops = distinct.map { it.substringBefore('/') }.distinct()
            return if (tops.size == 1) "${tops.single()}/*" else "*/*"
        }
    }
}

/** An album's stable name for remembering it: the folder's id, or the collection's name. */
val GalleryAlbum.key: String get() = if (kind == AlbumKind.BUCKET) "bucket:$id" else kind.name
