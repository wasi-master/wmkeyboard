package com.wasimaster.wmkeyboard.ime.files

import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.files.FileShortcut
import com.wasimaster.wmkeyboard.core.files.FileSort
import com.wasimaster.wmkeyboard.core.files.FileTree
import com.wasimaster.wmkeyboard.core.files.FileVolume
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
    val shortcuts: List<FileShortcut> = emptyList(),
    /** Folders added through Android's folder picker: a USB stick, a share, a cloud drive's folder. */
    val trees: List<FileTree> = emptyList(),
    val sort: FileSort = FileSort.NAME,
    val descending: Boolean = false,
    val showHidden: Boolean = false,
    val expanded: Boolean = false,
    /** Paths picked, in the order they were; they stay picked from folder to folder. */
    val selection: Set<String> = emptySet(),
    /** Which of [selection] are folders, which a tool's pick never takes. */
    val selectedFolders: Set<String> = emptySet(),
    /** A long press started it: a tap now picks anything, rather than opening a folder or taking a file. */
    val selecting: Boolean = false,
    /** What Copy or Move is carrying, until it is put down in a folder. */
    val clip: FileClip? = null,
    val op: FileOpUi? = null,
    val confirmDelete: Boolean = false,
    /** A folder added through the picker, asked about before it comes off the list. */
    val confirmRemoveTree: FileTree? = null,
    /** The search showing as the active list, or null for the folder. */
    val query: String? = null,
    val searching: Boolean = false,
    val notice: FileNotice? = null,
) {
    val pane: FilePane get() = panes[active.coerceIn(panes.indices)]
    val view: FileView get() = pane.view
    val entries: List<FileEntry> get() = pane.entries
    val loading: Boolean get() = pane.loading
    val unreadable: Boolean get() = pane.unreadable

    val twoPanes: Boolean get() = panes.size > 1
    val folder: String? get() = (view as? FileView.Folder)?.path

    /** With two lists, the folder the other one shows: where Copy and Move go. */
    val otherFolder: FileView.Folder?
        get() = if (twoPanes) panes[1 - active.coerceIn(0, 1)].view as? FileView.Folder else null

    /** Whether Add can hand the selection over: files only, and no more than the tool takes. */
    val canPick: Boolean
        get() = request != null && selection.isNotEmpty() && selection.size <= request.limit &&
            selection.none { it in selectedFolders || File(it).isDirectory }
}

/** One list of the manager: what it shows and what that holds. */
data class FilePane(
    val view: FileView = FileView.Places,
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    /** The open folder could not be read, which is not the same as empty. */
    val unreadable: Boolean = false,
)

/** What a list is showing. */
sealed interface FileView {
    data object Places : FileView
    data object Recent : FileView

    /**
     * A folder, by path or by document. A document has no path to read its
     * parents from, so [trail] is the way down to it from the folder that was
     * added, ending with itself; a folder on the phone leaves it empty and the
     * breadcrumb comes from the path.
     */
    data class Folder(val path: String, val trail: List<FileCrumb> = emptyList()) : FileView
}

data class FileClip(val paths: List<String>, val move: Boolean)

/** A copy, move or delete under way. */
data class FileOpUi(val kind: FileOpKind, val done: Int, val total: Int)

enum class FileOpKind { COPY, MOVE, DELETE }

data class FileNotice(val kind: Kind, val count: Int = 0) {
    enum class Kind { COPIED, MOVED, DELETED, FAILED, LIMIT, BAD_NAME, TREE_REMOVED }
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

enum class FilesTextField { SEARCH, RENAME, NEW_FOLDER }

/** The panel's text box while the keys type into it. */
data class FilesTextEntry(val field: FilesTextField, val text: String = "")

sealed interface FileAction {
    data class Tap(val entry: FileEntry) : FileAction
    data class Hold(val entry: FileEntry) : FileAction
    data class OpenFolder(val path: String) : FileAction
    data object OpenPlaces : FileAction
    data object OpenRecent : FileAction
    data class OpenShortcut(val shortcut: FileShortcut) : FileAction
    data class OpenTree(val tree: FileTree) : FileAction

    /** Android's folder picker, for a folder to add to Places. */
    data object AddTree : FileAction
    data class AskRemoveTree(val tree: FileTree) : FileAction
    data class AnswerRemoveTree(val yes: Boolean) : FileAction

    /** One list or two side by side; the choice is kept for next time. */
    data object TogglePanes : FileAction

    /** Makes list [index] the one the bars and the search act on. */
    data class FocusPane(val index: Int) : FileAction
    data object ToggleExpanded : FileAction
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
    data object NewFolder : FileAction
    data object SearchFocus : FileAction
    data object ClearSearch : FileAction
    data object TextDone : FileAction
    data object NoticeDismiss : FileAction
    data object Close : FileAction
}

/** What the file manager panel hands back; one bundle, see `ToolHoldCallbacks.files`. */
data class FileManagerCallbacks(
    val state: StateFlow<FileManagerUi> = MutableStateFlow(FileManagerUi()),
    val onAction: (FileAction) -> Unit = {},
)
