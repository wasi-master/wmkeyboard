package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.app.share.AppShareSheet
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.lock.LocalAppLock
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fingerprint
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.notes.NoteChecklist
import com.wasimaster.wmkeyboard.core.notes.NoteFiles
import com.wasimaster.wmkeyboard.core.notes.NoteStore
import com.wasimaster.wmkeyboard.core.notes.NoteTag
import com.wasimaster.wmkeyboard.core.notes.SuggestedNoteTag
import com.wasimaster.wmkeyboard.ime.ui.ChatMarkdown
import com.wasimaster.wmkeyboard.ime.ui.ChatMarkdownColors
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One note in the settings app (#560): a real text field for long writing,
 * where the keyboard's own editor is for a line or two.
 *
 * Autosaves. There is no Save button and no "discard changes?" dialog: every
 * pause in typing writes the draft to the store, and leaving the screen
 * flushes whatever is left. A new note that is still blank on leaving is not
 * kept, so a tap on Add followed by Back leaves nothing behind.
 */
@Composable
internal fun NoteEditorScreen(settings: LiveSettings, noteId: Long, onDone: () -> Unit) {
    RegisterHidingHeader()
    val state = rememberNotesState(settings) ?: return
    val initial = remember(state.active, state.archived, state.trash, noteId) {
        (state.active + state.archived + state.trash).firstOrNull { it.id == noteId }
    }
    if (noteId != 0L && initial == null) {
        // Deleted for good from the keyboard while this screen was on the way in.
        CaptionText(stringResource(R.string.notes_gone_caption))
        return
    }
    // A note opened from the list already passed the fingerprint there; the gate
    // is for the route reached any other way (a link, a shortcut, process restore).
    var unlocked by rememberSaveable(noteId) { mutableStateOf(initial?.locked != true || NoteUnlockPass.take(noteId)) }
    if (!unlocked) {
        LockedNoteGate(onUnlocked = { unlocked = true }, onCancel = onDone)
        return
    }
    NoteEditorForm(settings, state, initial, onDone)
}

/** The fingerprint ask in front of a locked note opened by its route. */
@Composable
private fun LockedNoteGate(onUnlocked: () -> Unit, onCancel: () -> Unit) {
    val appLock = LocalAppLock.current
    val status by appLock.status.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<Int?>(null) }
    val ask = {
        appLock.authenticate { result ->
            when {
                result.succeeded -> onUnlocked()
                result.explains -> error = result.message
                else -> onCancel()
            }
        }
    }
    LaunchedEffect(Unit) { ask() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.notes_locked_preview), style = MaterialTheme.typography.titleMedium)
        if (status.failsOpen) {
            Text(stringResource(R.string.notes_lock_unavailable_caption), style = MaterialTheme.typography.bodyMedium)
        }
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(onClick = ask) { Text(stringResource(R.string.notes_unlock_action)) }
    }
}

private enum class NoteEditorMode { EDIT, PREVIEW }

/**
 * The fields, once the store behind them has loaded. Split from
 * [NoteEditorScreen] so every `remember` here is keyed on a note that already
 * exists: a draft seeded from a value that arrives one frame late starts
 * empty and stays empty.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod")
private fun NoteEditorForm(settings: LiveSettings, state: NotesScreenState, initial: Note?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appLock = LocalAppLock.current
    val lockStatus by appLock.status.collectAsStateWithLifecycle()
    val renderMarkdown = settings.watch { it.notes.renderMarkdown }
    var id by remember { mutableLongStateOf(initial?.id ?: 0L) }
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var body by rememberSaveable { mutableStateOf(initial?.body.orEmpty()) }
    var tags by rememberSaveable { mutableStateOf(initial?.tags.orEmpty()) }
    var tagDraft by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(NoteEditorMode.EDIT) }
    var menu by remember { mutableStateOf(false) }
    var lockInfo by remember { mutableStateOf(false) }
    var savedAt by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    val stored = remember(state.active, state.archived, state.trash, id) {
        (state.active + state.archived + state.trash).firstOrNull { it.id == id }
    }

    // The flush outlives the composition: the last write happens as the
    // screen is already going away.
    val flushScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val latest by rememberUpdatedState(Triple(title, body, tags))
    val latestId by rememberUpdatedState(id)
    /** Writes the drafts; a blank new note is never created. */
    val save: suspend () -> Unit = {
        val (t, b, g) = latest
        val current = latestId
        if (current == 0L) {
            if (t.isNotBlank() || b.isNotBlank()) {
                var created = 0L
                state.mutate { s -> created = s.add(b, title = t, tags = g).id }
                id = created
                savedAt = System.currentTimeMillis()
            }
        } else {
            val now = state.store.item(current)
            if (now != null && (now.title != t || now.body != b || now.tags != g)) {
                state.mutate { s -> s.update(now.copy(title = t, body = b, tags = g)) }
                savedAt = System.currentTimeMillis()
            }
        }
    }
    LaunchedEffect(title, body, tags) {
        delay(AUTOSAVE_MS)
        save()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { flushScope.launch { save() } }
    DisposableEffect(Unit) {
        onDispose {
            flushScope.launch {
                save()
                flushScope.cancel()
            }
        }
    }

    ResizeForKeyboard()
    Column(modifier = Modifier.imePadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (renderMarkdown) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    SegmentedButton(
                        selected = mode == NoteEditorMode.EDIT,
                        onClick = { mode = NoteEditorMode.EDIT },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.notes_mode_edit)) }
                    SegmentedButton(
                        selected = mode == NoteEditorMode.PREVIEW,
                        onClick = { mode = NoteEditorMode.PREVIEW },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.notes_mode_preview)) }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val pinned = stored?.pinned == true
                    val archived = stored?.archived == true
                    val locked = stored?.locked == true
                    DropdownMenuItem(
                        text = { Text(stringResource(if (pinned) R.string.notes_unpin_action else R.string.notes_pin_action)) },
                        onClick = { menu = false; scope.launch { save(); state.mutate { s -> s.setPinned(id, !pinned) } } },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (archived) R.string.notes_unarchive_action else R.string.notes_archive_action)) },
                        onClick = { menu = false; scope.launch { save(); state.mutate { s -> s.setArchived(id, !archived) } } },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (locked) R.string.notes_unlock_action else R.string.notes_lock_action)) },
                        onClick = {
                            menu = false
                            if (locked) {
                                appLock.authenticate { result ->
                                    if (result.succeeded) {
                                        scope.launch { state.mutate { s -> s.setLocked(id, false) } }
                                    } else if (result.explains) {
                                        message = context.getString(result.message)
                                    }
                                }
                            } else {
                                lockInfo = true
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notes_share_md_action)) },
                        onClick = { menu = false; scope.launch { save(); shareNote(context, title, body, tags, NoteFiles.Format.MARKDOWN)?.let { message = it } } },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notes_share_txt_action)) },
                        onClick = { menu = false; scope.launch { save(); shareNote(context, title, body, tags, NoteFiles.Format.TEXT)?.let { message = it } } },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notes_trash_action)) },
                        onClick = {
                            menu = false
                            scope.launch {
                                save()
                                if (id != 0L) state.mutate { s -> s.trash(id) }
                                onDone()
                            }
                        },
                    )
                }
            }
        }
        if (stored?.locked == true && lockStatus.failsOpen) {
            CaptionText(stringResource(R.string.notes_lock_unavailable_caption), modifier = Modifier.padding(vertical = 4.dp))
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.replace("\n", " ").take(NoteStore.MAX_TITLE_LENGTH) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.notes_field_title_label)) },
            textStyle = MaterialTheme.typography.titleLarge,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Spacer(Modifier.height(8.dp))
        if (mode == NoteEditorMode.PREVIEW && renderMarkdown) {
            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.padding(12.dp)) {
                    if (body.isBlank()) {
                        Text(stringResource(R.string.notes_preview_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val ink = MaterialTheme.colorScheme.onSurface
                        // The app has an activity, so the platform's own selection and Copy bar work here.
                        SelectionContainer {
                            ChatMarkdown(
                                body,
                                ChatMarkdownColors(text = ink, dim = ink.copy(alpha = 0.65f), codeBackground = ink.copy(alpha = 0.10f), link = MaterialTheme.colorScheme.primary),
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                                onTaskToggle = { task -> NoteChecklist.toggleTask(body, task.line)?.let { body = it } },
                            )
                        }
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = body,
                onValueChange = { body = it.take(NoteStore.MAX_BODY_CHARS) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8,
                label = { Text(stringResource(R.string.notes_field_body_label)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.notes_tags_label), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (tag in tags) {
                val colour = state.tags.firstOrNull { it.name.equals(tag, ignoreCase = true) }?.colour ?: 0
                InputChip(
                    selected = true,
                    onClick = { tags = tags.filterNot { it.equals(tag, ignoreCase = true) } },
                    label = { Text(tag) },
                    leadingIcon = { TagSwatch(colour, 10.dp) },
                    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_delete), modifier = Modifier.size(16.dp)) },
                )
            }
        }
        OutlinedTextField(
            value = tagDraft,
            onValueChange = { tagDraft = it.take(NoteStore.MAX_TAG_LENGTH) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.notes_tag_add_hint)) },
            trailingIcon = if (tagDraft.isNotBlank()) {
                {
                    TextButton(onClick = {
                        val clean = tagDraft.trim()
                        if (tags.none { it.equals(clean, ignoreCase = true) }) tags = tags + clean
                        tagDraft = ""
                    }) { Text(stringResource(CommonR.string.common_add)) }
                }
            } else {
                null
            },
        )
        TagSuggestions(state.tags, tags, tagDraft) { name ->
            if (tags.none { it.equals(name, ignoreCase = true) }) tags = tags + name
            tagDraft = ""
        }
        Spacer(Modifier.height(24.dp))
    }

    RegisterDock {
        Surface(tonalElevation = 2.dp) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val words = remember(body) { body.split(Regex("""\s+""")).count { it.isNotEmpty() } }
                Text(
                    stringResource(
                        R.string.notes_counter,
                        pluralStringResource(R.plurals.notes_words, words, words),
                        pluralStringResource(R.plurals.notes_characters, body.length, body.length),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (savedAt > 0) stringResource(R.string.notes_saved_caption) else stored?.let { stringResource(R.string.notes_edited_time, relativeTime(it.updatedAt)) }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (lockInfo) {
        AlertDialog(
            onDismissRequest = { lockInfo = false },
            title = { Text(stringResource(R.string.notes_lock_action)) },
            text = {
                Column {
                    Text(stringResource(R.string.notes_lock_info))
                    if (lockStatus.failsOpen) {
                        Spacer(Modifier.height(8.dp))
                        DialogNote(stringResource(R.string.notes_lock_unavailable_caption))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    lockInfo = false
                    scope.launch {
                        save()
                        if (id != 0L) state.mutate { s -> s.setLocked(id, true) }
                    }
                }) { Text(stringResource(R.string.notes_lock_action)) }
            },
            dismissButton = { TextButton(onClick = { lockInfo = false }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) } },
            text = { Text(text) },
        )
    }
}

/** The user's other tags, then the starters nobody has made yet, narrowed by what is being typed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSuggestions(all: List<NoteTag>, chosen: List<String>, draft: String, onPick: (String) -> Unit) {
    val needle = draft.trim()
    val existing = all.filter { tag -> chosen.none { it.equals(tag.name, ignoreCase = true) } && tag.name.contains(needle, ignoreCase = true) }
    val starters = SuggestedNoteTag.entries.map { stringResource(it.nameRes) to it.colour }
        .filter { (name, _) -> all.none { it.name.equals(name, ignoreCase = true) } && chosen.none { it.equals(name, ignoreCase = true) } }
        .filter { (name, _) -> name.contains(needle, ignoreCase = true) }
    if (existing.isEmpty() && starters.isEmpty()) return
    FlowRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (tag in existing.take(12)) {
            AssistChip(onClick = { onPick(tag.name) }, label = { Text(tag.name) }, leadingIcon = { TagSwatch(tag.colour, 10.dp) })
        }
        for ((name, colour) in starters) {
            AssistChip(onClick = { onPick(name) }, label = { Text(name) }, leadingIcon = { TagSwatch(colour, 10.dp) })
        }
    }
}

/**
 * Shares the note as a file through the app's FileProvider (a share intent
 * the size of a long note is dropped by half the apps that would take it as
 * text). Returns an error sentence, or null when the chooser opened.
 */
private fun shareNote(context: Context, title: String, body: String, tags: List<String>, format: NoteFiles.Format): String? {
    val note = Note(id = 0, body = body, title = title, tags = tags)
    val uri = runCatching {
        val dir = File(context.cacheDir, "notes").apply { mkdirs() }
        val file = File(dir, NoteFiles.fileName(note, format, HashSet()))
        file.writeText(if (format == NoteFiles.Format.MARKDOWN) NoteFiles.toText(note) else body)
        FileProvider.getUriForFile(context, "${context.packageName}.clipboard", file)
    }.getOrNull() ?: return context.getString(R.string.notes_share_error)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = format.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, note.displayTitle().ifEmpty { context.getString(R.string.notes_untitled) })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return if (AppShareSheet.start(context, intent, context.getString(R.string.notes_share_title))) null else context.getString(R.string.notes_share_error)
}

private const val AUTOSAVE_MS = 600L
