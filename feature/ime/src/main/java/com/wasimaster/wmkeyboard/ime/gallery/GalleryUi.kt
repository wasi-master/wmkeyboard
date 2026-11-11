package com.wasimaster.wmkeyboard.ime.gallery

import com.wasimaster.wmkeyboard.core.media.gallery.GalleryAlbum
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryFilter
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryItem
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryKind
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryQuality
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryStore
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.media.gallery.edit.AdjustKnob
import com.wasimaster.wmkeyboard.core.media.gallery.edit.BrushKind
import com.wasimaster.wmkeyboard.core.media.gallery.edit.CropAspect
import com.wasimaster.wmkeyboard.core.media.gallery.edit.EditHistory
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEdit
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoUpscale
import com.wasimaster.wmkeyboard.core.media.gallery.edit.TextAlign
import com.wasimaster.wmkeyboard.core.media.gallery.edit.TextStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the gallery panel draws (#708), owned by [GalleryController]
 * rather than `KeyboardUiState`: the panel is the only reader, and a grid
 * paging in should not be a reason for the whole keyboard state to change.
 *
 * The one piece that lives in `KeyboardUiState` is the text being typed into
 * the panel ([GalleryTextEntry]), because the keyboard's shared capture path
 * types into that state.
 */
data class GalleryUi(
    val access: GalleryStore.Access = GalleryStore.Access.NONE,
    /** Photos are allowed and videos are not, while videos are meant to show: a banner asks for them. */
    val videosWithheld: Boolean = false,
    val albums: List<GalleryAlbum> = emptyList(),
    val album: GalleryAlbum = GalleryAlbum.Recents,
    val filter: GalleryFilter = GalleryFilter.ALL,
    /** The open album's rows loaded so far, newest first. */
    val items: List<GalleryItem> = emptyList(),
    val loading: Boolean = false,
    /** No more rows behind [items]. */
    val endReached: Boolean = false,
    /** Picked, in the order they were picked: the order they go in. */
    val selection: List<GalleryItem> = emptyList(),
    /** Edits by item id; an item with none is sent as it is. */
    val edits: Map<Long, EditHistory> = emptyMap(),
    val quality: GalleryQuality = GalleryQuality.HD,
    /** The caption kept while the keys are elsewhere. */
    val caption: String = "",
    val expanded: Boolean = false,
    val albumsOpen: Boolean = false,
    val columns: Int = 4,
    /** Held under a finger: drawn big until the finger lifts. */
    val peek: GalleryItem? = null,
    val viewer: ViewerUi? = null,
    val editor: EditorUi? = null,
    val sendMenuOpen: Boolean = false,
    val sending: SendProgress? = null,
    /** Files that went nowhere, waiting for the user to choose share or copy (the Ask fallback). */
    val askFallback: Int = 0,
    val notice: GalleryNotice? = null,
    /** What this keyboard sent before: a small mark in the grid. */
    val sentIds: Set<Long> = emptySet(),
    /** Whether the open field takes pictures, and videos, through the keyboard at all. */
    val fieldTakesImages: Boolean = true,
    val fieldTakesVideos: Boolean = false,
    /** Opened by the Send files tool to pick for a link (#729): Send hands the picks to it instead of the field. */
    val forLink: Boolean = false,
    /**
     * Whether the header offers a search: search by meaning is on with its
     * pictures model on the phone. Without it the panel is the plain grid.
     */
    val searchAvailable: Boolean = false,
    /** A search typed into the header; null while the grid is the album's. */
    val search: GallerySearch? = null,
    /** The upscaler's copies of edited photos, by [upscaleKey]: what the editor and the send read instead of the photo. */
    val upscaled: Map<String, java.io.File> = emptyMap(),
    /**
     * An Immich server is set up (#1205): its timeline and albums join the
     * album list, the grid shows even without the phone's photo permission,
     * and the send menu can upload phone pictures to it.
     */
    val immich: Boolean = false,
) {
    val selectedIds: Set<Long> get() = selection.mapTo(HashSet()) { it.id }

    /** 1-based place of [id] in the selection, or 0 when not picked. */
    fun orderOf(id: Long): Int = selection.indexOfFirst { it.id == id } + 1

    fun editOf(id: Long): PhotoEdit = edits[id]?.current ?: PhotoEdit.NONE

    /** The upscaled copy [edit] draws on for item [id], or null for the photo itself (or a copy that has gone). */
    fun upscaledFile(id: Long, edit: PhotoEdit): java.io.File? =
        edit.upscale?.let { upscaled[upscaleKey(id, it)] }?.takeIf { it.isFile }

    /** The list the viewer pages through. */
    val viewerItems: List<GalleryItem>
        get() = if (viewer?.fromSelection == true) selection else items
}

/**
 * The photos search: [query] as typed, [pending] while the answer for it is
 * still being worked out, and [showing] once [GalleryUi.items] holds what it
 * found, best match first, instead of the album's rows.
 */
data class GallerySearch(val query: String, val pending: Boolean = false, val showing: Boolean = false)

/** The viewer: which list it pages and where it is. */
data class ViewerUi(
    val fromSelection: Boolean,
    val index: Int,
    val muted: Boolean = false,
    /** The bars over the picture; a tap hides them. */
    val chrome: Boolean = true,
    val infoOpen: Boolean = false,
)

/** Where [GalleryUi.upscaled] keeps item [id]'s copy enlarged as [upscale] says. */
fun upscaleKey(id: Long, upscale: PhotoUpscale): String = "$id:${upscale.factor}:${upscale.art}"

/** What the editor's tool rail can switch to. */
enum class EditorTool { CROP, DRAW, TEXT, STICKER, FILTER, ADJUST, UPSCALE }

/** An upscale under way: [fraction] done, or below 0 while that is unknown; [downloading] the network first. */
data class UpscaleProgress(val fraction: Float = -1f, val downloading: Boolean = false)

/** The editor's own state; the edit itself is in [GalleryUi.edits]. */
data class EditorUi(
    val itemId: Long,
    val tool: EditorTool = EditorTool.CROP,
    val brush: BrushKind = BrushKind.PEN,
    val color: Int = DEFAULT_COLOR,
    /** Brush width as a fraction of the picture's shorter side. */
    val brushSize: Float = 0.012f,
    val aspect: CropAspect = CropAspect.FREE,
    val knob: AdjustKnob = AdjustKnob.BRIGHTNESS,
    /** The text or sticker layer picked for moving, restyling or deleting. */
    val selectedLayer: Long? = null,
    /** The text layer whose words the keys are typing. */
    val typingLayer: Long? = null,
    val textStyle: TextStyle = TextStyle.PLAIN,
    val textAlign: TextAlign = TextAlign.CENTER,
    /** The original under a held finger, for before-and-after. */
    val comparing: Boolean = false,
    /** The save card under the top bar: a copy, or over the original. */
    val saveMenuOpen: Boolean = false,
    /** A save to the gallery under way, the consent dialog included. */
    val saving: Boolean = false,
    /** The upscaler at work on this photo; null when it is not. */
    val upscaling: UpscaleProgress? = null,
    /** The last upscale failed: said under the Upscale tool until the next try. */
    val upscaleFailed: Boolean = false,
    /** The network the Upscale tool would use: the one for drawings, or for photos. */
    val upscaleArt: Boolean = false,
) {
    companion object {
        const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()

        /** The colour row: white, black, then a hue round. */
        val PALETTE: List<Int> = listOf(
            0xFFFFFFFF, 0xFF000000, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759,
            0xFF00C7BE, 0xFF007AFF, 0xFF5856D6, 0xFFAF52DE, 0xFFFF2D55, 0xFF8E8E93,
        ).map { it.toInt() }
    }
}

/** A send under way: how many of how many have gone. */
data class SendProgress(val done: Int, val total: Int, val preparingId: Long? = null)

/** A short line over the grid, gone after a moment. */
data class GalleryNotice(val kind: Kind, val count: Int = 0) {
    enum class Kind {
        /** The selection is full. */
        LIMIT,

        /** Nothing reached the app; what to do next is up to the user. */
        NOT_ACCEPTED,
        SHARED,
        COPIED,
        SAVED,
        FAILED,
        SENT,

        /** A video was picked for a field that only takes pictures. */
        NO_VIDEOS,

        /** The editor wrote the edited photo over its original. */
        REPLACED,

        /** The user said no to Android's "modify this photo?". */
        NOT_REPLACED,
        SAVE_FAILED,

        /** The Immich server could not be reached, or would not answer (#1205). */
        IMMICH_FAILED,

        /** The Immich server turned the API key down, or the key lacks the permission asked for. */
        IMMICH_REFUSED,

        /** Phone pictures uploaded to the Immich server. */
        UPLOADED,

        /** Every picture sent up was on the server already. */
        ALREADY_UPLOADED,
        UPLOAD_FAILED,
    }
}

/** What the panel's text box is for. */
enum class GalleryTextField { CAPTION, LAYER, SEARCH }

/**
 * The panel's text box while the keys type into it. [ref] is the text layer's
 * id for [GalleryTextField.LAYER].
 */
data class GalleryTextEntry(val field: GalleryTextField, val text: String = "", val ref: Long = 0)

/** The ways out of the send bar; [NORMAL] is a tap on Send, the rest are its hold menu. */
enum class SendMode {
    NORMAL,

    /** The original bytes, as a file, whatever the quality chip says. */
    AS_FILE,
    WITHOUT_CAPTION,

    /** Onto the clipboard, to paste. */
    COPY,

    /** Through Android's share screen, to the app the field belongs to. */
    SHARE_TO_APP,

    /** Through Android's share screen, to any app. */
    SHARE,

    /** Into the phone's gallery: an edited copy kept. */
    SAVE,

    /** To the Send files tool (#729), to go out as an encrypted link or to a device nearby. */
    AS_LINK,

    /** Up to the user's Immich server (#1205): phone pictures it does not have yet. */
    TO_IMMICH,
}

/** Everything the panel can ask for, as one type, so the callback bundle stays one field. */
sealed interface GalleryAction {
    // Access and elsewhere
    data object AskPermission : GalleryAction
    data object PickWithSystem : GalleryAction
    data object PickFiles : GalleryAction
    data object OpenCamera : GalleryAction
    data object OpenSettings : GalleryAction

    // Browsing
    data class OpenAlbum(val album: GalleryAlbum) : GalleryAction
    data object ToggleAlbums : GalleryAction
    data class SetFilter(val filter: GalleryFilter) : GalleryAction
    data object LoadMore : GalleryAction
    data class SetColumns(val columns: Int) : GalleryAction
    data object ToggleExpanded : GalleryAction
    data class SetExpanded(val on: Boolean) : GalleryAction
    data class Peek(val item: GalleryItem?) : GalleryAction

    /** The header's search box takes the keys. */
    data object SearchFocus : GalleryAction

    /** Back to the album's own grid. */
    data object ClearSearch : GalleryAction

    // Selection
    data class Toggle(val item: GalleryItem) : GalleryAction

    /** A drag across the grid: every item it covered is picked, or unpicked when it started on a picked one. */
    data class SelectRange(val items: List<GalleryItem>, val select: Boolean) : GalleryAction
    data class Remove(val id: Long) : GalleryAction
    data class Move(val from: Int, val to: Int) : GalleryAction
    data object ClearSelection : GalleryAction

    // Viewer
    data class OpenViewer(val index: Int, val fromSelection: Boolean = false) : GalleryAction
    data class ViewerPage(val index: Int) : GalleryAction
    data object ViewerChrome : GalleryAction
    data object ViewerMute : GalleryAction
    data object ViewerInfo : GalleryAction
    data object CloseViewer : GalleryAction

    // Sending
    data class SetQuality(val quality: GalleryQuality) : GalleryAction
    data object CaptionFocus : GalleryAction
    data object TextDone : GalleryAction
    data class Send(val mode: SendMode = SendMode.NORMAL) : GalleryAction
    data class SendMenu(val open: Boolean) : GalleryAction

    /** The Ask fallback's answer: share (true) or copy (false), or neither (null). */
    data class FallbackChoice(val share: Boolean?) : GalleryAction
    data object NoticeDismiss : GalleryAction

    // Editor
    data class OpenEditor(val id: Long) : GalleryAction
    data object CloseEditor : GalleryAction
    data class Tool(val tool: EditorTool) : GalleryAction

    /** A change still under the finger: drawn, not yet an undo step. */
    data class EditPreview(val edit: PhotoEdit) : GalleryAction

    /** A finished change: one undo step. */
    data class EditCommit(val edit: PhotoEdit) : GalleryAction
    data object Undo : GalleryAction
    data object Redo : GalleryAction
    data object ResetEdit : GalleryAction
    data class Brush(val brush: BrushKind) : GalleryAction
    data class BrushColor(val color: Int) : GalleryAction
    data class BrushSize(val size: Float) : GalleryAction
    data class Aspect(val aspect: CropAspect) : GalleryAction
    data class Knob(val knob: AdjustKnob) : GalleryAction
    /** A new text box at ([x], [y]), fractions of the turned picture. */
    data class AddText(val x: Float = 0.5f, val y: Float = 0.5f) : GalleryAction
    data class TypeLayer(val id: Long) : GalleryAction
    data class SelectLayer(val id: Long?) : GalleryAction
    data class DeleteLayer(val id: Long) : GalleryAction
    data class LayerStyle(val style: TextStyle) : GalleryAction
    data class LayerAlign(val align: TextAlign) : GalleryAction
    data class AddSticker(val path: String?, val emoji: String?) : GalleryAction
    data class Compare(val on: Boolean) : GalleryAction
    data class SaveMenu(val open: Boolean) : GalleryAction

    /** The edited photo into the gallery: a new picture beside it, or ([replace]) over it. */
    data class SaveEdit(val replace: Boolean) : GalleryAction

    /** Which network the Upscale tool uses: for drawings and text ([art]), or for photos. */
    data class UpscaleArt(val art: Boolean) : GalleryAction

    /** Enlarges the photo by [factor] with the upscaler, an undoable step once it is done. */
    data class Upscale(val factor: Int) : GalleryAction
    data object CancelUpscale : GalleryAction
}

/**
 * Whether the editor may write this photo back over itself: a library row
 * (a picker's grant reads, it does not write) in a format Android encodes.
 */
val GalleryItem.replaceable: Boolean
    get() = !isPicked && !isRemote && bucketId != com.wasimaster.wmkeyboard.ime.gallery.immich.ImmichLibrary.REMOTE_BUCKET &&
        kind == GalleryKind.IMAGE && MediaPrep.canRewriteExif(mime)

/** A sticker the editor can place: one of the user's own. */
data class StickerChoice(val id: String, val path: String)

/** What the gallery panel hands back; one bundle, see `ToolHoldCallbacks.gallery`. */
data class GalleryCallbacks(
    val state: StateFlow<GalleryUi> = MutableStateFlow(GalleryUi()),
    val onAction: (GalleryAction) -> Unit = {},
    /** The user's stickers, for the editor's sticker tab; read when the tab opens. */
    val stickers: () -> List<StickerChoice> = { emptyList() },
)
