package com.wasimaster.wmkeyboard.ime.files

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.wasimaster.wmkeyboard.core.files.FileCategory
import com.wasimaster.wmkeyboard.core.files.FileDetails
import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.files.FileInfo
import com.wasimaster.wmkeyboard.core.files.FileKind
import com.wasimaster.wmkeyboard.core.files.FilePin
import com.wasimaster.wmkeyboard.core.files.FileShortcut
import com.wasimaster.wmkeyboard.core.files.FileSort
import com.wasimaster.wmkeyboard.core.files.FileTree
import com.wasimaster.wmkeyboard.core.files.FileVolume
import com.wasimaster.wmkeyboard.core.files.FileZip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Everything the file manager panel draws, owned by [FileManagerController]
 * rather than `KeyboardUiState`, as the gallery's is. The one piece in
 * `KeyboardUiState` is the text being typed ([FilesTextEntry]), because the
 * keyboard's shared capture path types into that state.
 *
 * Opened two ways: for a tool's pick ([request] set), or as the Files tool
 * on its own ([browsing], #1267), where nothing is handed anywhere and the
 * point is the changing of files. Either can show one list or two side by
 * side ([panes]); the selection, the search and the bars act on the [active]
 * one, and Copy and Move with two lists go straight to the other one.
 *
 * Over the lists sit, one at a time: a file's [preview], its [info] card, a
 * question about names already taken ([clash]) or about deleting.
 */
data class FileManagerUi(
    /** Whether the keyboard may browse shared storage by path: All files access. */
    val access: Boolean = false,
    /** Whether this build can ask for that access at all; a Play build cannot. */
    val canAskAccess: Boolean = true,
    val request: FilePickRequest? = null,
    /** Open as the Files tool, with no pick to make. */
    val browsing: Boolean = false,
    /** The keyguard is up: a phone's files are not for whoever is holding it. */
    val locked: Boolean = false,
    val panes: List<FilePane> = listOf(FilePane()),
    val active: Int = 0,
    val volumes: List<FileVolume> = emptyList(),
    /** Free and total bytes per volume path, for the storage cards. */
    val space: Map<String, Pair<Long, Long>> = emptyMap(),
    /** How many files each category holds, filled in a moment after Places opens. */
    val categoryCounts: Map<FileCategory, Int> = emptyMap(),
    val shortcuts: List<FileShortcut> = emptyList(),
    /** Folders added through Android's folder picker: a USB stick, a share, a cloud drive's folder. */
    val trees: List<FileTree> = emptyList(),
    /** The backup locations that hold files (a WebDAV or SFTP server, Dropbox, an S3 bucket), browsed like folders. */
    val remotes: List<com.wasimaster.wmkeyboard.core.files.RemotePlace> = emptyList(),
    val pins: List<FilePin> = emptyList(),
    val recentFolders: List<FilePin> = emptyList(),
    val searches: List<String> = emptyList(),
    val prefs: FilePrefs = FilePrefs(),
    val sort: FileSort = FileSort.NAME,
    val descending: Boolean = false,
    val expanded: Boolean = false,
    /** Paths picked, in the order they were; they stay picked from folder to folder. */
    val selection: Set<String> = emptySet(),
    /** Which of [selection] are folders, which a tool's pick never takes. */
    val selectedFolders: Set<String> = emptySet(),
    /** Bytes in the picked files (folders not counted), for the selection header. */
    val selectionBytes: Long = 0,
    /** A long press started it: a tap now picks anything, rather than opening a folder or taking a file. */
    val selecting: Boolean = false,
    /** What Copy or Move is carrying, until it is put down in a folder. */
    val clip: FileClip? = null,
    val op: FileOpUi? = null,
    /** What waits behind [op], in order. */
    val queued: List<FileOpKind> = emptyList(),
    val confirmDelete: Boolean = false,
    /** A folder added through the picker, asked about before it comes off the list. */
    val confirmRemoveTree: FileTree? = null,
    val clash: ClashAsk? = null,
    /** The last change, which the Undo chip takes back while it shows. */
    val undo: FileUndoUi? = null,
    /** The search showing as the active list, or null for the folder. */
    val query: String? = null,
    val searching: Boolean = false,
    /** Where the search is looking right now, for its status line. */
    val searchingIn: String? = null,
    val filters: SearchFilters = SearchFilters(),
    val preview: PreviewUi? = null,
    val info: InfoUi? = null,
    val notice: FileNotice? = null,
    /** The field being typed in takes no text from here: a password field. */
    val secureField: Boolean = false,
) {
    val pane: FilePane get() = panes[active.coerceIn(panes.indices)]
    val view: FileView get() = pane.view
    val entries: List<FileEntry> get() = pane.entries
    val loading: Boolean get() = pane.loading
    val unreadable: Boolean get() = pane.unreadable

    val twoPanes: Boolean get() = panes.size > 1
    val folder: String? get() = (view as? FileView.Folder)?.path
    val grid: Boolean get() = prefs.grid

    /** With two lists, the folder the other one shows: where Copy and Move go. */
    val otherFolder: FileView.Folder?
        get() = if (twoPanes) panes[1 - active.coerceIn(0, 1)].view as? FileView.Folder else null

    /** Whether Add can hand the selection over: files only, and no more than the tool takes. */
    val canPick: Boolean
        get() = request != null && selection.isNotEmpty() && selection.size <= request.limit &&
            selection.none { it in selectedFolders || File(it).isDirectory }

    /** Files only, so Send and Share have something they can hand over. */
    val selectionIsFiles: Boolean get() = selection.isNotEmpty() && selectedFolders.isEmpty()
}

/** The Files tool's settings, as the controller last read them. */
data class FilePrefs(
    /** Thumbnails in a grid rather than rows (the default since the revamp). */
    val grid: Boolean = true,
    /** A tap on a file previews it in the keyboard rather than opening its app (the default). */
    val tapPreviews: Boolean = true,
    val thumbnails: Boolean = true,
    val showHidden: Boolean = false,
    val foldersFirst: Boolean = true,
)

/** One list of the manager: what it shows, what that holds, and where it has been. */
data class FilePane(
    val view: FileView = FileView.Places,
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    /** How many entries a big folder has read so far, while it reads; 0 otherwise. */
    val reading: Int = 0,
    /** The open folder could not be read, which is not the same as empty. */
    val unreadable: Boolean = false,
    /** The places this list came through, newest last: what Back returns to. */
    val history: List<FileView> = emptyList(),
)

/** What a list is showing. */
sealed interface FileView {
    data object Places : FileView
    data object Recent : FileView

    /** Every file of one kind on the phone, from the media index. */
    data class Category(val category: FileCategory) : FileView

    /**
     * A folder, by path or by document. A document has no path to read its
     * parents from, so [trail] is the way down to it from the folder that was
     * added, ending with itself; a folder on the phone leaves it empty and the
     * breadcrumb comes from the path.
     */
    data class Folder(val path: String, val trail: List<FileCrumb> = emptyList()) : FileView
}

data class FileClip(val paths: List<String>, val move: Boolean)

/**
 * A copy, move, delete, zip or unzip under way: [done] of [total] things,
 * the one called [name] now, and bytes where they are known, with the speed
 * and the time left worked out from them.
 */
data class FileOpUi(
    val kind: FileOpKind,
    val done: Int,
    val total: Int,
    val name: String = "",
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val bytesPerSecond: Long = 0,
    val secondsLeft: Long = -1,
) {
    val fraction: Float?
        get() = when {
            bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
            total > 0 && kind == FileOpKind.DELETE -> (done.toFloat() / total).coerceIn(0f, 1f)
            else -> null
        }
}

enum class FileOpKind { COPY, MOVE, DELETE, ZIP, UNZIP, SEND }

/** Names already taken where a copy or move is going: which ones, which is being asked about, and whether one answer does for all. */
data class ClashAsk(
    val names: List<String>,
    val folderLabel: String,
    val index: Int = 0,
    val sameForAll: Boolean = true,
)

enum class UndoKind { COPY, MOVE, RENAME, NEW_FOLDER, ZIP, UNZIP, DUPLICATE }

data class FileUndoUi(val kind: UndoKind, val count: Int)

/** Type filters for a search: a category's kinds, or any. */
enum class SizeFilter(val min: Long, val max: Long) {
    ANY(0, 0),
    SMALL(0, 1024L * 1024),
    MEDIUM(1024L * 1024, 100L * 1024 * 1024),
    LARGE(100L * 1024 * 1024, 0),
}

enum class DateFilter(val days: Int) { ANY(0), TODAY(1), WEEK(7), MONTH(31), YEAR(366) }

data class SearchFilters(
    /** A category's kinds, or null for every kind. [FileCategory.LARGE] is not offered here; size has its own chip. */
    val category: FileCategory? = null,
    val size: SizeFilter = SizeFilter.ANY,
    val date: DateFilter = DateFilter.ANY,
    val inContents: Boolean = false,
    /** Which chip has its choices open, if any. */
    val open: FilterChip? = null,
) {
    val any: Boolean get() = category != null || size != SizeFilter.ANY || date != DateFilter.ANY || inContents
}

enum class FilterChip { TYPE, SIZE, DATE }

/**
 * A file shown in the keyboard: [entry] as the [index]th of the list it came
 * from, so a swipe goes to the next one. What fills it depends on [kind]; each
 * part is null until it has loaded, or when the file has none.
 */
data class PreviewUi(
    val entry: FileEntry,
    val kind: FileKind,
    val index: Int,
    val count: Int,
    val loading: Boolean = true,
    /** The words of a text file, up to [FileManagerController.PREVIEW_TEXT_MAX]. */
    val text: String? = null,
    /** The text stopped at the cap; the rest is in the file. */
    val textCut: Boolean = false,
    val pdfPage: Bitmap? = null,
    val pdfIndex: Int = 0,
    val pdfCount: Int = 0,
    val zipItems: List<FileZip.Item>? = null,
    val facts: FileDetails.Facts = FileDetails.Facts(),
    val appIcon: Drawable? = null,
    /** The Type choices are showing. */
    val typeMenu: Boolean = false,
    /** What can draw this file: the built-in view, where there is one, then the plugins that read it. */
    val choices: List<PreviewChoice> = emptyList(),
    /** The one drawing it: [PreviewChoice.BUILT_IN] or a plugin preview's key. */
    val choice: String = PreviewChoice.BUILT_IN,
    /** What the chosen plugin drew. */
    val pluginUi: com.wasimaster.wmkeyboard.core.plugins.RenderedUi? = null,
    /** Why the chosen plugin drew nothing. */
    val pluginError: com.wasimaster.wmkeyboard.core.plugins.PluginText? = null,
    /** A plugin grammar's colours for [text], when one reads this file. */
    val syntax: List<com.wasimaster.wmkeyboard.core.plugins.PluginSyntax.Token>? = null,
    /** A plugin from the add-on repositories that would draw this file, when none installed does. */
    val offer: PluginOffer? = null,
    /** What the app would import this file as, when it is one of the files the app imports. */
    val importable: ImportableFiles.Found? = null,
    /** The file is still being read for an import. */
    val importChecking: Boolean = false,
) {
    val byImport: Boolean get() = choice == PreviewChoice.IMPORT && importable != null
    val byPlugin: Boolean get() = choice != PreviewChoice.BUILT_IN && choice != PreviewChoice.IMPORT
}

/** One way to draw a previewed file: the keyboard's own, or a plugin's ([label] its name). */
data class PreviewChoice(val key: String, val label: String?) {
    companion object {
        const val BUILT_IN = "builtin"

        /** What the app would import the file as, with Import. */
        const val IMPORT = "import"
    }
}

/** The Properties card: a file's facts, or a folder's tally as it is counted. */
data class InfoUi(
    val entry: FileEntry,
    val kind: FileKind,
    /** The path a person reads: the breadcrumb for a document, the real path for a file. */
    val location: String,
    val facts: FileDetails.Facts = FileDetails.Facts(),
    val tally: FileInfo.Tally? = null,
    val counting: Boolean = false,
    val hash: String? = null,
    val hashing: Boolean = false,
    val pinned: Boolean = false,
    /** The details below the tiles of a folder's card are showing. */
    val details: Boolean = false,
)

data class FileNotice(val kind: Kind, val count: Int = 0, val text: String = "") {
    enum class Kind {
        COPIED, MOVED, DELETED, FAILED, LIMIT, BAD_NAME, TREE_REMOVED, NO_APP,
        ZIPPED, UNZIPPED, ZIP_FAILED, SKIPPED, UNDONE, UNDO_FAILED, PATH_COPIED, TEXT_COPIED, PINNED, UNPINNED,
        FIELD_REFUSED, SENT, TOO_BIG_TO_TYPE, QUEUED,
    }
}

/** One step of the breadcrumb: where it goes, and what it is called. */
data class FileCrumb(val path: String, val label: String)

/**
 * The steps from the volume holding [path] down to [path]. A path on no known
 * volume gets one step, itself, so the row still says where it is.
 */
fun fileCrumbs(path: String, volumes: List<FileVolume>): List<FileCrumb> {
    val volume = volumes.firstOrNull { path == it.path || path.startsWith(it.path + "/") }
        ?: return listOf(FileCrumb(path, File(path).name.ifEmpty { path }))
    val out = arrayListOf(FileCrumb(volume.path, volume.label))
    var at = volume.path
    path.removePrefix(volume.path).split('/').filter { it.isNotEmpty() }.forEach { part ->
        at = "$at/$part"
        out += FileCrumb(at, part)
    }
    return out
}

/** The breadcrumb for [view]: the document trail where it has one, else the steps down its path. */
fun folderCrumbs(view: FileView.Folder, volumes: List<FileVolume>): List<FileCrumb> =
    view.trail.ifEmpty { fileCrumbs(view.path, volumes) }

/** Where [path] is, as a person reads it: `Phone / DCIM / Camera`, with the volume's own name. */
fun readablePath(path: String, volumes: List<FileVolume>, trail: List<FileCrumb> = emptyList()): String =
    (trail.ifEmpty { fileCrumbs(path, volumes) }).joinToString(" / ") { it.label }

enum class FilesTextField { SEARCH, RENAME, NEW_FOLDER, ZIP_NAME }

/** The panel's text box while the keys type into it. */
data class FilesTextEntry(val field: FilesTextField, val text: String = "")

/** What the Type button in a preview or the selection puts in the field. */
enum class TypeWhat { NAME, PATH, CONTENTS }

sealed interface FileAction {
    data class Tap(val entry: FileEntry) : FileAction
    data class Hold(val entry: FileEntry) : FileAction

    /** A drag after a hold, over the list: everything from the held row to [to] is picked. */
    data class SelectRange(val from: Int, val to: Int) : FileAction
    data object SelectAll : FileAction
    data object InvertSelection : FileAction
    data class OpenFolder(val path: String) : FileAction
    data object OpenPlaces : FileAction
    data object OpenRecent : FileAction
    data class OpenCategory(val category: FileCategory) : FileAction
    data class OpenShortcut(val shortcut: FileShortcut) : FileAction
    data class OpenTree(val tree: FileTree) : FileAction

    /** A server or a cloud, at its top folder. */
    data class OpenRemote(val place: com.wasimaster.wmkeyboard.core.files.RemotePlace) : FileAction

    /** The app's backup settings, where a server or a cloud is added. */
    data object AddRemote : FileAction
    data class OpenPin(val pin: FilePin) : FileAction
    data class Unpin(val pin: FilePin) : FileAction

    /** Pins the open folder (or the one picked) to Places, or takes it off. */
    data object TogglePin : FileAction

    /** Android's folder picker, for a folder to add to Places. */
    data object AddTree : FileAction
    data class AskRemoveTree(val tree: FileTree) : FileAction
    data class AnswerRemoveTree(val yes: Boolean) : FileAction

    /** One list or two side by side; the choice is kept for next time. */
    data object TogglePanes : FileAction

    /** The two lists trade places. */
    data object SwapPanes : FileAction

    /** Makes list [index] the one the bars and the search act on. */
    data class FocusPane(val index: Int) : FileAction
    data object ToggleExpanded : FileAction
    data object ToggleLayout : FileAction
    data class SetSort(val sort: FileSort) : FileAction
    data object ToggleHidden : FileAction
    data object AskPermission : FileAction
    data object UseSystemPicker : FileAction

    /** Android's picker from now on: the setting goes off, and this pick goes there too. */
    data object AlwaysSystemPicker : FileAction

    data object Add : FileAction
    data object ClearSelection : FileAction
    data object Copy : FileAction
    data object Move : FileAction
    data object Paste : FileAction
    data object ClearClip : FileAction
    data object AskDelete : FileAction
    data class AnswerDelete(val yes: Boolean) : FileAction
    data object CancelOp : FileAction
    data object Rename : FileAction
    data object Duplicate : FileAction
    data object Zip : FileAction
    data object Unzip : FileAction
    data object Share : FileAction
    data object CopyPath : FileAction

    /** The picked files (or the one previewed) into the field as attachments. */
    data object Send : FileAction
    data class Type(val what: TypeWhat) : FileAction
    data object CopyText : FileAction
    data class AnswerClash(val policy: com.wasimaster.wmkeyboard.core.files.Clash) : FileAction
    data object ToggleClashForAll : FileAction
    data object CancelClash : FileAction
    data object Undo : FileAction

    /** The one picked file to another app, asking which. */
    data object OpenWith : FileAction
    data object NewFolder : FileAction

    data class Preview(val entry: FileEntry) : FileAction

    /** The one picked file, previewed: the selection menu's Preview. */
    data object PreviewPicked : FileAction

    /** A plugin preview's text into the field, from its output card's Type button. */
    data class InsertPluginText(val text: String) : FileAction

    /** A plugin preview's text to the clipboard. */
    data class CopyPluginText(val text: String) : FileAction

    /** The offered plugin's page in the add-on store, to install it there. */
    data object GetPlugin : FileAction

    /** "Not now" to offered plugins for files of the previewed one's type. */
    data object DismissOffer : FileAction

    /** The previewed file into the app's import dialog. */
    data object Import : FileAction

    /** "View with": draw the previewed file with [key] instead, and keep that choice for its type. */
    data class PreviewWith(val key: String) : FileAction
    data class PreviewStep(val delta: Int) : FileAction
    data class PdfStep(val delta: Int) : FileAction
    data object ToggleTypeMenu : FileAction
    data object ClosePreview : FileAction

    /** The Properties card, for the previewed file, the one picked, or the open folder. */
    data object Info : FileAction
    data object CloseInfo : FileAction
    data object InfoDetails : FileAction
    data object ComputeHash : FileAction

    data object SearchFocus : FileAction
    data object ClearSearch : FileAction
    data class UseSearch(val text: String) : FileAction
    data class ForgetSearch(val text: String) : FileAction
    data class OpenFilter(val chip: FilterChip?) : FileAction
    data class SetCategoryFilter(val category: FileCategory?) : FileAction
    data class SetSizeFilter(val size: SizeFilter) : FileAction
    data class SetDateFilter(val date: DateFilter) : FileAction
    data object ToggleInContents : FileAction
    data object TextDone : FileAction
    data object NoticeDismiss : FileAction
    data object Close : FileAction
}

/** What the file manager panel hands back; one bundle, see `ToolHoldCallbacks.files`. */
data class FileManagerCallbacks(
    val state: StateFlow<FileManagerUi> = MutableStateFlow(FileManagerUi()),
    val onAction: (FileAction) -> Unit = {},
    /** What plugins add, while plugins are on: thumbnails for the grid, grammars for code. */
    val plugins: () -> FilePlugins? = { null },
)
