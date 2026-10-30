package com.wasimaster.wmkeyboard.ime.files

import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.files.FileShortcut
import com.wasimaster.wmkeyboard.core.files.FileSort
import com.wasimaster.wmkeyboard.core.files.FileVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Everything the file manager panel draws, owned by [FileManagerController]
 * rather than `KeyboardUiState`, as the gallery's is. The one piece in
 * `KeyboardUiState` is the text being typed ([FilesTextEntry]), because the
 * keyboard's shared capture path types into that state.
 */
data class FileManagerUi(
    /** Whether the keyboard may browse shared storage at all. */
    val access: Boolean = false,
    val request: FilePickRequest? = null,
    val view: FileView = FileView.Places,
    val volumes: List<FileVolume> = emptyList(),
    val shortcuts: List<FileShortcut> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    /** The open folder could not be read, which is not the same as empty. */
    val unreadable: Boolean = false,
    val sort: FileSort = FileSort.NAME,
    val descending: Boolean = false,
    val showHidden: Boolean = false,
    val expanded: Boolean = false,
    /** Paths picked, in the order they were; they stay picked from folder to folder. */
    val selection: Set<String> = emptySet(),
    /** A long press started it: a tap now picks anything, rather than opening a folder or taking a file. */
    val selecting: Boolean = false,
    /** What Copy or Move is carrying, until it is put down in a folder. */
    val clip: FileClip? = null,
    val op: FileOpUi? = null,
    val confirmDelete: Boolean = false,
    /** The search showing as the list, or null for the folder. */
    val query: String? = null,
    val searching: Boolean = false,
    val notice: FileNotice? = null,
) {
    val folder: String? get() = (view as? FileView.Folder)?.path

    /** Whether Add can hand the selection over: files only, and no more than the tool takes. */
    val canPick: Boolean
        get() = selection.isNotEmpty() && selection.size <= (request?.limit ?: 0) &&
            selection.none { File(it).isDirectory }
}

/** What the list is showing. */
sealed interface FileView {
    data object Places : FileView
    data object Recent : FileView
    data class Folder(val path: String) : FileView
}

data class FileClip(val paths: List<String>, val move: Boolean)

/** A copy, move or delete under way. */
data class FileOpUi(val kind: FileOpKind, val done: Int, val total: Int)

enum class FileOpKind { COPY, MOVE, DELETE }

data class FileNotice(val kind: Kind, val count: Int = 0) {
    enum class Kind { COPIED, MOVED, DELETED, FAILED, LIMIT, BAD_NAME }
}

/** One step of the breadcrumb: its name, or null for the volume to be named by its label. */
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
