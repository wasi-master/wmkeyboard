package com.wasimaster.wmkeyboard.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.lock.AppLockStatus
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.app.lock.LocalAppLock
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteForever
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Inventory2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Label
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Restore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapVert
import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.notes.NoteFiles
import com.wasimaster.wmkeyboard.core.notes.NoteFilter
import com.wasimaster.wmkeyboard.core.notes.NoteShelf
import com.wasimaster.wmkeyboard.core.notes.NoteSort
import com.wasimaster.wmkeyboard.core.notes.NoteStore
import com.wasimaster.wmkeyboard.core.notes.NoteTag
import com.wasimaster.wmkeyboard.core.notes.NoteTagPalette
import com.wasimaster.wmkeyboard.core.settings.NotesPanelLayout
import com.wasimaster.wmkeyboard.core.settings.NotesPanelStart
import com.wasimaster.wmkeyboard.core.settings.NotesSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.util.readBytesCapped
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The Notes tool's screens in the settings app (#560): the list with its
 * search, tags and bulk actions; the tag manager; the trash; and the tool's
 * own settings page. The editor is in NotesEditorScreen.kt.
 *
 * All of them open the same file the keyboard writes, `notes/notes.json`,
 * through [NoteStore], and re-read it when the screen comes back to the
 * foreground: a note written on the keyboard is here when the user returns.
 */

internal const val NOTES_ROUTE = "notes"
internal const val NOTES_TAGS_ROUTE = "notes/tags"
internal const val NOTES_TRASH_ROUTE = "notes/trash"

/** The editor's route; 0 is a note that does not exist yet, which the Add button opens. */
internal fun noteEditRoute(noteId: Long): String = "notes/edit/$noteId"

/**
 * The notes file as a screen sees it: the store, and snapshots of its lists
 * that recompose the screen when a mutation goes through.
 */
@Stable
internal class NotesScreenState(val store: NoteStore) {
    var active by mutableStateOf<List<Note>>(emptyList())
    var archived by mutableStateOf<List<Note>>(emptyList())
    var trash by mutableStateOf<List<Note>>(emptyList())
    var tags by mutableStateOf<List<NoteTag>>(emptyList())

    fun refresh() {
        active = store.items(NoteFilter(NoteShelf.ACTIVE))
        archived = store.items(NoteFilter(NoteShelf.ARCHIVED))
        trash = store.items(NoteFilter(NoteShelf.TRASH))
        tags = store.tags()
    }

    /** Runs [block] against the store off the main thread, saves, and republishes. */
    suspend fun mutate(block: (NoteStore) -> Unit) {
        withContext(Dispatchers.IO) {
            block(store)
            store.save()
        }
        refresh()
    }
}

/**
 * Opens the notes file off the main thread, once per screen, and re-reads it
 * whenever the screen resumes, since the keyboard writes the same file.
 */
@Composable
internal fun rememberNotesState(settings: LiveSettings): NotesScreenState? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<NotesScreenState?>(null) }
    val trashDays = settings.watch { it.notes.trashDays }
    LaunchedEffect(Unit) {
        val store = withContext(Dispatchers.IO) { NoteStore(File(context.filesDir, NoteStore.FILE_PATH)) }
        state = NotesScreenState(store).also { it.refresh() }
    }
    LaunchedEffect(state, trashDays) {
        val s = state ?: return@LaunchedEffect
        s.store.trashRetentionMillis = trashDays * DAY_MS
        s.refresh()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val s = state ?: return@LifecycleEventEffect
        scope.launch {
            val changed = withContext(Dispatchers.IO) { s.store.reloadIfChanged() }
            if (changed) s.refresh()
        }
    }
    return state
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Which notes the list screen shows, besides the tag filter. */
private enum class NotesView { ALL, PINNED, ARCHIVED, UNTAGGED }

/**
 * Opens [note], asking for the fingerprint first when it is locked and the
 * Notes screen itself did not already ask on the way in. The bare prompt, not
 * a target's: there is no [AppLockTargets] entry per note, and passing one
 * note's lock must not open every locked screen.
 */
@Composable
internal fun rememberLockedNoteOpener(onOpen: (Note) -> Unit, onError: (Int) -> Unit): (Note) -> Unit {
    val appLock = LocalAppLock.current
    val config by appLock.config.collectAsStateWithLifecycle()
    val status by appLock.status.collectAsStateWithLifecycle()
    val screenGuarded = config?.let { it.enabled && "screen_notes" in it.lockedTargets } == true &&
        status == AppLockStatus.READY
    return { note ->
        when {
            !note.locked -> onOpen(note)
            screenGuarded -> { NoteUnlockPass.grant(note.id); onOpen(note) }
            else -> appLock.authenticate { result ->
                if (result.succeeded) {
                    NoteUnlockPass.grant(note.id)
                    onOpen(note)
                } else if (result.explains) {
                    onError(result.message)
                }
            }
        }
    }
}

/**
 * Hands a fingerprint already asked for by [rememberLockedNoteOpener] to the
 * editor route it opens, so the editor's own gate does not ask a second time.
 * One use: the editor takes the pass on arrival, and the next visit asks again.
 */
internal object NoteUnlockPass {
    private val granted = mutableSetOf<Long>()

    fun grant(id: Long) = synchronized(granted) { granted += id }

    fun take(id: Long): Boolean = synchronized(granted) { granted.remove(id) }
}

// ---- the list ----

/** Tools › Notes: every note, searched, filtered by tag, and acted on in bulk. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun NotesListScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val state = rememberNotesState(settings) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSettingsSnackbar.current
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf(NotesView.ALL) }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    val sort = settings.watch { it.notes.sort }
    val tagColours = remember(state.tags) { state.tags.associate { it.name.lowercase(Locale.ROOT) to it.colour } }

    val shown = remember(state.active, state.archived, view, tagFilter, query, sort) {
        val base = when (view) {
            NotesView.ALL -> state.active
            NotesView.PINNED -> state.active.filter { it.pinned }
            NotesView.ARCHIVED -> state.archived
            NotesView.UNTAGGED -> state.active.filter { it.tags.isEmpty() }
        }
        val tagged = tagFilter?.let { tag -> base.filter { it.hasTag(tag) } } ?: base
        val needle = query.trim()
        val searched = if (needle.isEmpty()) {
            tagged
        } else {
            tagged.filter { note ->
                note.displayTitle().contains(needle, ignoreCase = true) ||
                    note.tags.any { it.contains(needle, ignoreCase = true) } ||
                    (!note.locked && note.body.contains(needle, ignoreCase = true))
            }
        }
        searched.sortedWith(noteComparator(sort))
    }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }

    val openNote = rememberLockedNoteOpener(
        onOpen = { onNavigate(noteEditRoute(it.id)) },
        onError = { message = context.getString(it) },
    )

    // ---- export and import ----
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(NoteFiles.ZIP_MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val notes = (state.active + state.archived).filterNot { it.locked }
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                runCancellable {
                    context.contentResolver.requireOutputStream(uri).use { NoteFiles.writeZip(notes, NoteFiles.Format.MARKDOWN, it) }
                    notes.size
                }.getOrNull()
            }
            message = if (count == null) {
                context.getString(R.string.notes_export_error)
            } else {
                context.resources.getQuantityString(R.plurals.notes_exported_count, count, count)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch { message = importNotes(context, state, uris) }
    }

    RegisterPinned {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.notes_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_clear))
                        }
                    }
                } else {
                    null
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (v in NotesView.entries) {
                        FilterChip(
                            selected = view == v,
                            onClick = { view = v },
                            label = { Text(stringResource(v.labelRes())) },
                        )
                    }
                    for (tag in state.tags) {
                        FilterChip(
                            selected = tagFilter.equals(tag.name, ignoreCase = true),
                            onClick = { tagFilter = if (tagFilter.equals(tag.name, ignoreCase = true)) null else tag.name },
                            label = { Text(tag.name) },
                            leadingIcon = { TagSwatch(tag.colour, 10.dp) },
                        )
                    }
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Outlined.SwapVert, contentDescription = stringResource(R.string.notes_sort_desc))
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        for (option in NoteSort.entries) {
                            DropdownMenuItem(
                                text = { Text(stringResource(option.labelRes())) },
                                trailingIcon = if (option == sort) ({ Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
                                onClick = {
                                    sortMenu = false
                                    scope.launch { repository.setNotesSort(option) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (!selecting) {
        RegisterAddFab(stringResource(R.string.notes_add_action)) { onNavigate(noteEditRoute(0)) }
    } else {
        RegisterDock {
            BulkActionBar(
                state = state,
                selected = selected,
                onClear = { selected = emptySet() },
                onSelectAll = { selected = shown.map { it.id }.toSet() },
                onError = { message = context.getString(it) },
                onTrashed = { count ->
                    val ids = selected.toList()
                    selected = emptySet()
                    snackbar?.undo(
                        context.resources.getQuantityString(R.plurals.notes_trashed_count_snackbar, count, count),
                        context.getString(CommonR.string.common_undo),
                    ) { scope.launch { state.mutate { s -> ids.forEach { s.restore(it) } } } }
                },
            )
        }
    }

    if (shown.isEmpty()) {
        CaptionText(
            when {
                query.isNotBlank() -> stringResource(R.string.notes_empty_search, query.trim())
                view == NotesView.ARCHIVED -> stringResource(R.string.notes_empty_archived)
                tagFilter != null || view != NotesView.ALL -> stringResource(R.string.notes_empty_filter)
                else -> stringResource(R.string.notes_empty)
            },
        )
    } else {
        SettingsGroup {
            for (note in shown) {
                item(key = note.id) {
                    NoteListRow(
                        note = note,
                        tagColours = tagColours,
                        selecting = selecting,
                        checked = note.id in selected,
                        onClick = {
                            if (selecting) selected = selected.toggle(note.id) else openNote(note)
                        },
                        onLongClick = { selected = selected.toggle(note.id) },
                        onPin = { scope.launch { state.mutate { s -> s.setPinned(note.id, !note.pinned) } } },
                        onArchive = { scope.launch { state.mutate { s -> s.setArchived(note.id, !note.archived) } } },
                        onTrash = {
                            scope.launch { state.mutate { s -> s.trash(note.id) } }
                            snackbar?.undo(
                                context.resources.getQuantityString(R.plurals.notes_trashed_count_snackbar, 1, 1),
                                context.getString(CommonR.string.common_undo),
                            ) { scope.launch { state.mutate { s -> s.restore(note.id) } } }
                        },
                    )
                }
            }
        }
    }

    SettingsGroup {
        item {
            NavRow(
                R.string.notes_tags_title,
                subtitle = pluralStringResource(R.plurals.notes_tags_count, state.tags.size, state.tags.size),
                route = NOTES_TAGS_ROUTE,
            ) { onNavigate(NOTES_TAGS_ROUTE) }
        }
        item {
            NavRow(
                R.string.notes_trash_title,
                subtitle = pluralStringResource(R.plurals.notes_count, state.trash.size, state.trash.size),
                route = NOTES_TRASH_ROUTE,
            ) { onNavigate(NOTES_TRASH_ROUTE) }
        }
    }
    SettingsGroup(stringResource(R.string.notes_files_group)) {
        item {
            ActionRow(
                R.string.notes_export_all_action,
                subtitle = stringResource(R.string.notes_export_all_subtitle),
                action = stringResource(R.string.notes_export_button),
                enabled = state.active.isNotEmpty() || state.archived.isNotEmpty(),
            ) {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                exportLauncher.launch("wmkeyboard-notes-$stamp.zip")
            }
        }
        item {
            ActionRow(
                R.string.notes_import_action,
                subtitle = stringResource(R.string.notes_import_subtitle),
                action = stringResource(R.string.notes_import_button),
            ) {
                importLauncher.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream"))
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) } },
            text = { Text(text) },
        )
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

/** Pinned first, then the chosen order; the store's own rule, applied to a list the screen filtered. */
private fun noteComparator(sort: NoteSort): Comparator<Note> {
    val pinnedFirst = compareByDescending<Note> { it.pinned }
    return when (sort) {
        NoteSort.UPDATED -> pinnedFirst.thenByDescending { it.updatedAt }
        NoteSort.CREATED -> pinnedFirst.thenByDescending { it.createdAt }
        NoteSort.TITLE -> pinnedFirst.thenBy { it.displayTitle().lowercase(Locale.ROOT) }.thenByDescending { it.updatedAt }
    }
}

private fun NotesView.labelRes(): Int = when (this) {
    NotesView.ALL -> R.string.notes_filter_all
    NotesView.PINNED -> R.string.notes_filter_pinned
    NotesView.ARCHIVED -> R.string.notes_filter_archived
    NotesView.UNTAGGED -> R.string.notes_filter_untagged
}

internal fun NoteSort.labelRes(): Int = when (this) {
    NoteSort.UPDATED -> R.string.tooldetail_notes_sort_updated_label
    NoteSort.CREATED -> R.string.tooldetail_notes_sort_created_label
    NoteSort.TITLE -> R.string.tooldetail_notes_sort_title_label
}

/** Reads the picked files as notes; returns the sentence the dialog says about it. */
private suspend fun importNotes(context: android.content.Context, state: NotesScreenState, uris: List<Uri>): String {
    var imported = 0
    var duplicates = 0
    var skipped = 0
    val existing = (state.active + state.archived).map { it.title.trim() to it.body.trim() }.toHashSet()
    val notes = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            val name = WMFileTypes.displayName(context, uri)
            if (!NoteFiles.isNoteFile(name)) {
                skipped++
                return@mapNotNull null
            }
            val bytes = runCatching { context.contentResolver.readBytesCapped(uri) }.getOrNull()
            if (bytes == null) {
                skipped++
                return@mapNotNull null
            }
            NoteFiles.fromText(name, String(bytes))
        }
    }
    state.mutate { store ->
        for (note in notes) {
            if ((note.title.trim() to note.body.trim()) in existing) {
                duplicates++
                continue
            }
            store.add(note)
            imported++
        }
    }
    val lines = buildList {
        add(context.resources.getQuantityString(R.plurals.notes_imported_count, imported, imported))
        if (duplicates > 0) add(context.resources.getQuantityString(R.plurals.notes_import_duplicates, duplicates, duplicates))
        if (skipped > 0) add(context.resources.getQuantityString(R.plurals.notes_import_skipped, skipped, skipped))
    }
    return lines.joinToString("\n")
}

/** One note in the list: title, a preview (or "Locked note"), its tags and when it was edited. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun NoteListRow(
    note: Note,
    tagColours: Map<String, Int>,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
) {
    val title = note.displayTitle().ifEmpty { stringResource(R.string.notes_untitled) }
    // The same three things the selection bar does to many notes, for one.
    // A long swipe trashes outright: the trash keeps the note, and the undo
    // bar puts it back where it was.
    val trash = deleteSwipeAction(stringResource(R.string.notes_trash_action), onTrash)
    SwipeActionsRow(
        actions = listOf(
            SwipeAction(
                Icons.Outlined.PushPin,
                stringResource(if (note.pinned) R.string.notes_unpin_action else R.string.notes_pin_action),
                SwipeTone.ACCENT,
                onPin,
            ),
            SwipeAction(
                Icons.Outlined.Inventory2,
                stringResource(if (note.archived) R.string.notes_unarchive_action else R.string.notes_archive_action),
                onClick = onArchive,
            ),
            trash,
        ),
        fullSwipe = trash,
        // While picking notes the row is a checkbox, and a swipe is one more
        // thing a finger meant for the next tick could set off.
        enabled = !selecting,
    ) {
        Box(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
            WmRow(
                title = title,
                leading = if (selecting) ({ Checkbox(checked = checked, onCheckedChange = { onClick() }) }) else null,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (note.pinned) Icon(Icons.Outlined.PushPin, contentDescription = stringResource(R.string.notes_pinned_desc), modifier = Modifier.size(16.dp))
                        if (note.locked) Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.notes_locked_desc), modifier = Modifier.size(16.dp))
                    }
                },
                trailingOnTitleLine = true,
                supporting = {
                    Column {
                        val preview = if (note.locked) stringResource(R.string.notes_locked_preview) else note.previewBody()
                        if (preview.isNotBlank()) {
                            Text(
                                preview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Text(
                                relativeTime(note.updatedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (note.tags.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (tag in note.tags) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TagSwatch(tagColours[tag.lowercase(Locale.ROOT)] ?: 0, 8.dp)
                                            Spacer(Modifier.width(3.dp))
                                            Text(tag, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                onClick = null,
            )
        }
    }
}

internal fun relativeTime(at: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()

/** A tag's colour as a dot; palette index 0 is the theme's own accent. */
@Composable
internal fun TagSwatch(colour: Int, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tagColour(colour)))
}

@Composable
internal fun tagColour(index: Int): Color {
    val safe = NoteTagPalette.clamp(index)
    return if (safe == 0) MaterialTheme.colorScheme.primary else Color(NoteTagPalette.ARGB[safe])
}

/** The bar under a selection: how many, and what to do with them all. */
@Composable
private fun BulkActionBar(
    state: NotesScreenState,
    selected: Set<Long>,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onError: (Int) -> Unit,
    onTrashed: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appLock = LocalAppLock.current
    var more by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    val notes = remember(selected, state.active, state.archived) { (state.active + state.archived).filter { it.id in selected } }
    val allPinned = notes.isNotEmpty() && notes.all { it.pinned }
    val allArchived = notes.isNotEmpty() && notes.all { it.archived }
    val allLocked = notes.isNotEmpty() && notes.all { it.locked }
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_cancel)) }
            Text(
                pluralStringResource(R.plurals.notes_selected_count, selected.size, selected.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { scope.launch { state.mutate { s -> selected.forEach { s.setPinned(it, !allPinned) } } } }) {
                Icon(Icons.Outlined.PushPin, contentDescription = stringResource(if (allPinned) R.string.notes_unpin_action else R.string.notes_pin_action))
            }
            IconButton(onClick = {
                scope.launch { state.mutate { s -> selected.forEach { s.setArchived(it, !allArchived) } } }
                onClear()
            }) {
                Icon(Icons.Outlined.Inventory2, contentDescription = stringResource(if (allArchived) R.string.notes_unarchive_action else R.string.notes_archive_action))
            }
            IconButton(onClick = { tagDialog = true }) { Icon(Icons.Outlined.Label, contentDescription = stringResource(R.string.notes_bulk_tag)) }
            IconButton(onClick = {
                val count = selected.size
                scope.launch { state.mutate { s -> selected.forEach { s.trash(it) } } }
                onTrashed(count)
            }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.notes_trash_action))
            }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = null) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(stringResource(CommonR.string.common_select_all)) }, onClick = { more = false; onSelectAll() })
                    DropdownMenuItem(
                        text = { Text(stringResource(if (allLocked) R.string.notes_unlock_action else R.string.notes_lock_action)) },
                        onClick = {
                            more = false
                            val ids = selected.toList()
                            if (allLocked) {
                                // Taking the lock off is the one bulk action that needs the fingerprint.
                                appLock.authenticate { result ->
                                    if (result.succeeded) {
                                        scope.launch { state.mutate { s -> ids.forEach { s.setLocked(it, false) } } }
                                    } else if (result.explains) {
                                        onError(result.message)
                                    }
                                }
                            } else {
                                scope.launch { state.mutate { s -> ids.forEach { s.setLocked(it, true) } } }
                            }
                        },
                    )
                }
            }
        }
    }
    if (tagDialog) {
        TagPickDialog(
            tags = state.tags,
            title = pluralStringResource(R.plurals.notes_bulk_tag_title, selected.size, selected.size),
            onDismiss = { tagDialog = false },
            onPick = { name ->
                tagDialog = false
                scope.launch {
                    state.mutate { s ->
                        for (id in selected) {
                            val note = s.item(id) ?: continue
                            if (!note.hasTag(name)) s.setTags(id, note.tags + name)
                        }
                    }
                }
            },
        )
    }
}

/** Pick one tag, or type a new one, for the bulk Tag button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPickDialog(tags: List<NoteTag>, title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (tag in tags) {
                        AssistChip(onClick = { onPick(tag.name) }, label = { Text(tag.name) }, leadingIcon = { TagSwatch(tag.colour, 10.dp) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(NoteStore.MAX_TAG_LENGTH) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.notes_tag_add_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = draft.isNotBlank(), onClick = { onPick(draft.trim()) }) { Text(stringResource(CommonR.string.common_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

// ---- tags ----

/** Tools › Notes › Tags: rename (merging on a collision), recolour, remove. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteTagsScreen(settings: LiveSettings) {
    val state = rememberNotesState(settings) ?: return
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<NoteTag?>(null) }
    var colouring by remember { mutableStateOf<NoteTag?>(null) }
    var removing by remember { mutableStateOf<NoteTag?>(null) }
    val counts = remember(state.active, state.archived, state.tags) {
        val all = state.active + state.archived
        state.tags.associate { tag -> tag.name to all.count { it.hasTag(tag.name) } }
    }
    RegisterAddFab(stringResource(R.string.notes_tag_add_action)) { adding = true }
    if (state.tags.isEmpty()) {
        CaptionText(stringResource(R.string.notes_tags_empty))
    } else {
        SettingsGroup {
            for (tag in state.tags) {
                // Keyed, so the open menu belongs to the tag rather than to
                // its place, and a removed tag closes out of the list.
                item(key = tag.name) {
                    var menu by remember { mutableStateOf(false) }
                    val count = counts[tag.name] ?: 0
                    WmRow(
                        title = tag.name,
                        subtitle = pluralStringResource(R.plurals.notes_tag_count, count, count),
                        leading = { TagSwatch(tag.colour, 14.dp) },
                        swipeActions = listOf(
                            SwipeAction(
                                Icons.Outlined.Edit,
                                stringResource(R.string.notes_tag_rename_action),
                            ) { renaming = tag },
                            deleteSwipeAction(stringResource(R.string.notes_tag_remove_action)) { removing = tag },
                        ),
                        trailing = {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = null) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.notes_tag_rename_action)) }, onClick = { menu = false; renaming = tag })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.notes_tag_colour_title)) }, onClick = { menu = false; colouring = tag })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.notes_tag_remove_action)) }, onClick = { menu = false; removing = tag })
                                }
                            }
                        },
                        onClick = { menu = true },
                    )
                }
            }
        }
    }
    if (adding || renaming != null) {
        val target = renaming
        TagNameDialog(
            initial = target?.name.orEmpty(),
            existing = state.tags,
            onDismiss = { adding = false; renaming = null },
        ) { name ->
            adding = false
            renaming = null
            scope.launch {
                state.mutate { s -> if (target == null) s.addTag(name) else s.renameTag(target.name, name) }
            }
        }
    }
    colouring?.let { tag ->
        AlertDialog(
            onDismissRequest = { colouring = null },
            title = { Text(stringResource(R.string.notes_tag_colour_title)) },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (index in 0 until NoteTagPalette.size) {
                        val chosen = index == tag.colour
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(tagColour(index))
                                .combinedClickable(onClick = {
                                    colouring = null
                                    scope.launch { state.mutate { s -> s.setTagColour(tag.name, index) } }
                                }),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (chosen) Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { colouring = null }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
    removing?.let { tag ->
        val count = counts[tag.name] ?: 0
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.notes_tag_remove_action)) },
            text = { Text(pluralStringResource(R.plurals.notes_tag_remove_body, count, tag.name, count)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch { state.mutate { s -> s.removeTag(tag.name) } }
                }) { Text(stringResource(R.string.notes_tag_remove_action)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
}

/** Name a tag. Renaming to another tag's name is a merge, and the dialog says so. */
@Composable
private fun TagNameDialog(initial: String, existing: List<NoteTag>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val clean = name.trim()
    val collision = existing.firstOrNull { it.name.equals(clean, ignoreCase = true) && !it.name.equals(initial, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.isEmpty()) R.string.notes_tag_add_action else R.string.notes_tag_rename_action)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(NoteStore.MAX_TAG_LENGTH) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.notes_tag_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (collision != null && initial.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    DialogNote(stringResource(R.string.notes_tag_rename_merge_body, collision.name))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = clean.isNotEmpty(), onClick = { onConfirm(clean) }) {
                Text(stringResource(if (collision != null && initial.isNotEmpty()) R.string.notes_tag_merge_action else CommonR.string.common_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

// ---- trash ----

/** Tools › Notes › Trash: restore, delete for good, or empty the lot. */
@Composable
internal fun NotesTrashScreen(settings: LiveSettings) {
    val state = rememberNotesState(settings) ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = LocalSettingsSnackbar.current
    val trashDays = settings.watch { it.notes.trashDays }
    var deleting by remember { mutableStateOf<Note?>(null) }
    CaptionText(
        if (trashDays > 0) stringResource(R.string.notes_trash_info_days, trashDays) else stringResource(R.string.notes_trash_info_never),
    )
    if (state.trash.isEmpty()) {
        CaptionText(stringResource(R.string.notes_trash_empty))
    } else {
        SettingsGroup {
            for (note in state.trash) {
                item(key = note.id) {
                    WmRow(
                        title = note.displayTitle().ifEmpty { stringResource(R.string.notes_untitled) },
                        subtitle = stringResource(R.string.notes_trashed_ago, relativeTime(note.deletedAt ?: note.updatedAt)),
                        swipeActions = listOf(
                            SwipeAction(
                                Icons.Outlined.Restore,
                                stringResource(R.string.notes_restore_action),
                                SwipeTone.ACCENT,
                            ) {
                                scope.launch { state.mutate { s -> s.restore(note.id) } }
                                snackbar?.undo(
                                    context.getString(R.string.notes_restored_snackbar, note.displayTitle()),
                                    context.getString(CommonR.string.common_undo),
                                ) { scope.launch { state.mutate { s -> s.trash(note.id) } } }
                            },
                            SwipeAction(
                                Icons.Outlined.DeleteForever,
                                stringResource(R.string.notes_delete_forever_action),
                                SwipeTone.DESTRUCTIVE,
                            ) { deleting = note },
                        ),
                        trailing = {
                            Row {
                                IconButton(onClick = {
                                    scope.launch { state.mutate { s -> s.restore(note.id) } }
                                    snackbar?.undo(
                                        context.getString(R.string.notes_restored_snackbar, note.displayTitle()),
                                        context.getString(CommonR.string.common_undo),
                                    ) { scope.launch { state.mutate { s -> s.trash(note.id) } } }
                                }) { Icon(Icons.Outlined.Restore, contentDescription = stringResource(R.string.notes_restore_action)) }
                                IconButton(onClick = { deleting = note }) {
                                    Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(R.string.notes_delete_forever_action))
                                }
                            }
                        },
                    )
                }
            }
        }
        SettingsGroup {
            item {
                ActionRow(
                    R.string.notes_empty_trash_action,
                    subtitle = pluralStringResource(R.plurals.notes_count, state.trash.size, state.trash.size),
                    action = stringResource(CommonR.string.common_delete),
                    confirm = stringResource(R.string.notes_empty_trash_confirm),
                    lock = AppLockTargets["action_empty_notes_trash"],
                ) { scope.launch { state.mutate { s -> s.emptyTrash() } } }
            }
        }
    }
    deleting?.let { note ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.notes_delete_forever_action)) },
            text = { Text(stringResource(R.string.notes_delete_forever_confirm, note.displayTitle().ifEmpty { stringResource(R.string.notes_untitled) })) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { state.mutate { s -> s.deleteForever(note.id) } }
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
}

// ---- the tool's own page ----

/** The Notes tool's settings page body, called from [ToolDetailSettings]. */
@Composable
internal fun NotesToolSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val d = SettingsDefaults.notes
    val summary by produceState<Pair<Int, Int>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val store = NoteStore(File(context.filesDir, NoteStore.FILE_PATH))
            store.count(NoteFilter(NoteShelf.ACTIVE)) + store.count(NoteFilter(NoteShelf.ARCHIVED)) to store.count(NoteFilter(NoteShelf.TRASH))
        }
    }
    SettingsGroup(stringResource(R.string.tooldetail_notes_group)) {
        item {
            val count = summary?.first
            NavRow(
                R.string.tooldetail_notes_open_title,
                subtitle = if (count == null || count == 0) {
                    stringResource(R.string.tooldetail_notes_open_none_subtitle)
                } else {
                    pluralStringResource(R.plurals.tooldetail_notes_open_subtitle, count, count)
                },
                route = NOTES_ROUTE,
            ) { onNavigate(NOTES_ROUTE) }
        }
        item(level = SettingLevel.STANDARD) { HomeScreenShortcutRow(R.string.home_shortcut_notes_label, NOTES_ROUTE) }
        // #997: other apps' text and links, straight into the notes.
        item(level = SettingLevel.STANDARD) {
            var shareOn by remember { mutableStateOf(NoteShareActivity.inShareSheet(context)) }
            ToggleSetting(
                R.string.tooldetail_notes_share_title,
                stringResource(R.string.tooldetail_notes_share_subtitle),
                shareOn,
                info = stringResource(R.string.tooldetail_notes_share_info),
                default = true,
            ) { on ->
                NoteShareActivity.setInShareSheet(context, on)
                shareOn = on
            }
        }
        item(level = SettingLevel.STANDARD) {
            NavRow(R.string.notes_tags_title, stringResource(R.string.tooldetail_notes_tags_subtitle), route = NOTES_TAGS_ROUTE) {
                onNavigate(NOTES_TAGS_ROUTE)
            }
        }
        item(level = SettingLevel.STANDARD) {
            val trashed = summary?.second
            NavRow(
                R.string.notes_trash_title,
                stringResource(R.string.tooldetail_notes_trash_subtitle),
                value = trashed?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.notes_count, it, it) },
                route = NOTES_TRASH_ROUTE,
            ) { onNavigate(NOTES_TRASH_ROUTE) }
        }
    }
    SettingsGroup(stringResource(R.string.tooldetail_options_group), level = SettingLevel.STANDARD) {
        item {
            ChoiceSetting(
                R.string.tooldetail_notes_sort_title,
                options = NoteSort.entries.map { it to stringResource(it.labelRes()) },
                selected = settings.watch { it.notes.sort },
                default = d.sort,
            ) { scope.launch { repository.setNotesSort(it) } }
        }
        item {
            ToggleSetting(
                R.string.tooldetail_notes_markdown_title,
                stringResource(R.string.tooldetail_notes_markdown_subtitle),
                settings.watch { it.notes.renderMarkdown },
                default = d.renderMarkdown,
            ) { scope.launch { repository.setNotesRenderMarkdown(it) } }
        }
        item {
            ChoiceSetting(
                R.string.tooldetail_notes_panel_start_title,
                subtitle = stringResource(R.string.tooldetail_notes_panel_start_subtitle),
                options = listOf(
                    NotesPanelStart.LIST to stringResource(R.string.tooldetail_notes_panel_start_list_label),
                    NotesPanelStart.LAST_NOTE to stringResource(R.string.tooldetail_notes_panel_start_last_label),
                ),
                selected = settings.watch { it.notes.panelOpensOn },
                default = d.panelOpensOn,
            ) { scope.launch { repository.setNotesPanelOpensOn(it) } }
        }
        item {
            ChoiceSetting(
                R.string.tooldetail_notes_panel_layout_title,
                subtitle = stringResource(R.string.tooldetail_notes_panel_layout_subtitle),
                options = listOf(
                    NotesPanelLayout.SHELF to stringResource(R.string.tooldetail_notes_panel_layout_shelf_label),
                    NotesPanelLayout.TIMELINE to stringResource(R.string.tooldetail_notes_panel_layout_timeline_label),
                ),
                selected = settings.watch { it.notes.panelLayout },
                default = d.panelLayout,
            ) { scope.launch { repository.setNotesPanelLayout(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                R.string.tooldetail_notes_trash_days_title,
                options = NotesSettings.TRASH_DAY_CHOICES.map { days ->
                    days to when (days) {
                        7 -> stringResource(R.string.tooldetail_notes_trash_7_label)
                        30 -> stringResource(R.string.tooldetail_notes_trash_30_label)
                        90 -> stringResource(R.string.tooldetail_notes_trash_90_label)
                        else -> stringResource(R.string.tooldetail_notes_trash_never_label)
                    }
                },
                selected = settings.watch { it.notes.trashDays }.let { days -> if (days in NotesSettings.TRASH_DAY_CHOICES) days else 30 },
                default = d.trashDays,
            ) { scope.launch { repository.setNotesTrashDays(it) } }
        }
        item {
            ChoiceSetting(
                R.string.tooldetail_notes_save_selection_title,
                subtitle = stringResource(R.string.tooldetail_notes_save_selection_subtitle),
                options = listOf(
                    false to stringResource(R.string.tooldetail_notes_save_new_label),
                    true to stringResource(R.string.tooldetail_notes_save_append_label),
                ),
                selected = settings.watch { it.notes.saveSelectionAppends },
                default = d.saveSelectionAppends,
            ) { scope.launch { repository.setNotesSaveSelectionAppends(it) } }
        }
    }
    SettingsGroup {
        item(level = SettingLevel.STANDARD) {
            ActionRow(
                R.string.tooldetail_notes_clear_title,
                subtitle = stringResource(R.string.tooldetail_notes_clear_subtitle),
                action = stringResource(CommonR.string.common_delete),
                confirm = stringResource(R.string.tooldetail_notes_clear_confirm),
                lock = AppLockTargets["action_delete_notes"],
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val store = NoteStore(File(context.filesDir, NoteStore.FILE_PATH))
                        val all = store.items(NoteFilter(NoteShelf.ACTIVE)) + store.items(NoteFilter(NoteShelf.ARCHIVED)) + store.items(NoteFilter(NoteShelf.TRASH))
                        all.forEach { store.deleteForever(it.id) }
                        store.tags().forEach { store.removeTag(it.name) }
                        store.save()
                    }
                    // The keyboard holds its own copy; tell it the file changed.
                    repository.bumpNotesVersion()
                }
            }
        }
    }
}
