package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.WrapText
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowForward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoFixHigh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BookmarkAdd
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DataObject
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DriveFileRenameOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FindReplace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListNumbered
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Publish
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SaveAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Stop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextDecrease
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextIncrease
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.plugins.PluginDraft
import com.wasimaster.wmkeyboard.core.plugins.PluginEvent
import com.wasimaster.wmkeyboard.core.plugins.PluginManifest
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestCodec
import com.wasimaster.wmkeyboard.core.plugins.PluginPermission
import com.wasimaster.wmkeyboard.core.plugins.PluginPreviewSession
import com.wasimaster.wmkeyboard.core.plugins.PluginRuntime
import com.wasimaster.wmkeyboard.core.plugins.PluginSnapshot
import com.wasimaster.wmkeyboard.core.plugins.PluginStorage
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.PluginWorkspace
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaApiEntry
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaDocuments
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaHostShape
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaNavigation
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaOutlineItem
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaSyntax
import com.wasimaster.wmkeyboard.core.plugins.resolve
import com.wasimaster.wmkeyboard.core.plugins.ui.LocalPluginPanelStyle
import com.wasimaster.wmkeyboard.core.plugins.ui.PluginInputHost
import com.wasimaster.wmkeyboard.core.plugins.ui.PluginPanelStyle
import com.wasimaster.wmkeyboard.core.plugins.ui.PluginWidgetList
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long typing has to pause before the draft is written. */
private const val AUTOSAVE_MS = 400L

private enum class IdePanel { CLOSED, PREVIEW, CONSOLE, PROBLEMS, OUTLINE, EVENTS, STORAGE }

/** What one pass of the checks found: the problems, and the outline of the last text that parsed. */
private data class IdeInspection(val diagnostics: List<CodeDiagnostic>, val outline: List<LuaOutlineItem>) {
    companion object {
        val None = IdeInspection(emptyList(), emptyList())
    }
}

// ---------------------------------------------------------------------------
// The drafts list
// ---------------------------------------------------------------------------

/** The plugin editor's front page: a new plugin, and every draft in the workspace. */
@Composable
internal fun PluginIdeProjectsScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val workspace = remember { PluginWorkspace.get(context) }
    val store = remember { PluginStore.get(context) }
    val revision by workspace.revision.collectAsStateWithLifecycle()
    val drafts = remember(revision) { workspace.drafts().map { it to workspace.manifest(it.draftId) } }
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf<PluginTemplate?>(null) }
    var deleting by remember { mutableStateOf<PluginDraft?>(null) }
    val untitled = stringResource(R.string.plugin_ide_draft_untitled)
    val newError = stringResource(R.string.plugin_ide_new_error)
    val notAPlugin = stringResource(R.string.plugin_ide_import_not_a_plugin_error)
    val importFailed = stringResource(R.string.plugin_ide_import_failed_error)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.requireInputStream(uri).use { importDraft(workspace, it) } }
                    .getOrElse { DraftImport.Failed }
            }
            when (result) {
                is DraftImport.Created -> onNavigate("plugin_ide/${result.draft.draftId}")
                is DraftImport.Refused -> Toast.makeText(context, result.reason.resolve(context), Toast.LENGTH_LONG).show()
                is DraftImport.NotAPlugin -> Toast.makeText(context, notAPlugin, Toast.LENGTH_LONG).show()
                is DraftImport.Failed -> Toast.makeText(context, importFailed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // No scroller of its own: SettingsScreen already wraps this content in a
    // Column(verticalScroll).
    SettingsGroup {
        item {
            WmRow(
                title = stringResource(R.string.plugin_ide_new_title),
                icon = SettingsRowIcons[R.string.plugin_ide_new_title],
                subtitle = stringResource(R.string.plugin_ide_new_subtitle),
                trailing = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                onClick = { creating = PluginTemplate.BLANK },
            )
        }
    }

    SettingsGroup(stringResource(R.string.plugin_ide_templates_title)) {
        for (template in PluginTemplate.entries) {
            if (template == PluginTemplate.BLANK) continue
            item { TemplateCard(template) { creating = template } }
        }
        item {
            WmRow(
                title = stringResource(R.string.plugin_ide_import_title),
                icon = SettingsRowIcons[R.string.plugin_ide_import_title],
                subtitle = stringResource(R.string.plugin_ide_import_subtitle),
                trailing = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                onClick = { importLauncher.launch(PluginFile.IMPORT_MIME_TYPES) },
            )
        }
    }

    SettingsGroup(stringResource(R.string.plugin_ide_drafts_title)) {
        if (drafts.isEmpty()) {
            item { CaptionText(stringResource(R.string.plugin_ide_drafts_empty)) }
        } else {
            for ((draft, manifest) in drafts) {
                item(key = draft.draftId) {
                    val edited = DateUtils.getRelativeTimeSpanString(
                        draft.updatedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                    val subtitle = if (draft.publishedVersion.isNotEmpty()) {
                        stringResource(R.string.plugin_ide_draft_published_label, draft.publishedVersion, edited)
                    } else {
                        stringResource(R.string.plugin_ide_draft_edited_label, edited)
                    }
                    WmRow(
                        title = manifest?.name?.ifBlank { null } ?: untitled,
                        subtitle = subtitle,
                        swipeActions = listOf(
                            SwipeAction(
                                Icons.Outlined.ContentCopy,
                                stringResource(R.string.plugin_ide_duplicate_draft_desc),
                            ) {
                                scope.launch {
                                    val copy = withContext(Dispatchers.IO) { workspace.duplicate(draft.draftId) }
                                    if (copy == null) Toast.makeText(context, newError, Toast.LENGTH_LONG).show()
                                }
                            },
                            deleteSwipeAction(stringResource(R.string.plugin_ide_delete_draft_desc)) { deleting = draft },
                        ),
                        trailing = {
                            Row {
                                IconButton(onClick = {
                                    scope.launch {
                                        val copy = withContext(Dispatchers.IO) { workspace.duplicate(draft.draftId) }
                                        if (copy == null) Toast.makeText(context, newError, Toast.LENGTH_LONG).show()
                                    }
                                }) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.plugin_ide_duplicate_draft_desc))
                                }
                                IconButton(onClick = { deleting = draft }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.plugin_ide_delete_draft_desc))
                                }
                            }
                        },
                        onClick = { onNavigate("plugin_ide/${draft.draftId}") },
                    )
                }
            }
        }
    }

    creating?.let { template ->
        NewPluginDialog(
            initialName = if (template == PluginTemplate.BLANK) null else stringResource(template.nameRes),
            onDismiss = { creating = null },
            onCreate = { name ->
                creating = null
                scope.launch {
                    val draft = withContext(Dispatchers.IO) { newDraftFromTemplate(context, workspace, store, template, name) }
                    if (draft != null) {
                        onNavigate("plugin_ide/${draft.draftId}")
                    } else {
                        Toast.makeText(context, newError, Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    deleting?.let { draft ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.plugin_ide_delete_draft_title)) },
            text = { Text(stringResource(R.string.plugin_ide_delete_draft_body)) },
            confirmButton = {
                TextButton({
                    workspace.delete(draft.draftId)
                    deleting = null
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton({ deleting = null }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun NewPluginDialog(initialName: String? = null, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val fallback = stringResource(R.string.plugin_ide_new_default_name)
    var name by rememberSaveable { mutableStateOf(initialName ?: fallback) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_ide_new_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(PluginManifestCodec.MAX_NAME) },
                label = { Text(stringResource(R.string.plugin_ide_new_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton({ onCreate(name.trim().ifBlank { fallback }) }) {
                Text(stringResource(R.string.plugin_ide_new_action))
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

// ---------------------------------------------------------------------------
// The code screen
// ---------------------------------------------------------------------------

/**
 * One draft, open for editing: the code, full height, and under it a panel that
 * shows the plugin running and what it printed.
 *
 * Builds its own Scaffold rather than going through SettingsScreen, as
 * AiChatScreen does: the house frame's collapsing heading and scrolling column
 * would take a third of a phone screen from a page whose whole point is showing
 * as much code as fits, and the code field scrolls inside itself.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PluginIdeScreen(draftId: String, onBack: () -> Unit, reduceMotion: Boolean = false) {
    val context = LocalContext.current
    val workspace = remember { PluginWorkspace.get(context) }
    val store = remember { PluginStore.get(context) }
    if (remember(draftId) { workspace.draft(draftId) } == null) {
        MissingDraft(onBack)
        return
    }
    val ide = remember(draftId) { PluginIdeState(draftId, WorkspaceIdePorts(workspace, store, draftId)) }
    val editor = rememberCodeEditorState(draftId, rules = LuaSmartRules, keepHistory = true) { ide.savedText }
    val scope = rememberCoroutineScope()
    val preview = remember(draftId) { PluginPreviewSession(workspace, store, post = { body -> scope.launch { body() } }) }
    val previewState by preview.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var panel by rememberSaveable { mutableStateOf(IdePanel.CLOSED) }
    var menuOpen by remember { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var suggestRequests by remember { mutableStateOf(0) }
    val keyPrefs = remember { CodeKeyPrefs(context) }
    var showKeys by remember { mutableStateOf(keyPrefs.showRow) }
    var keyOrder by remember { mutableStateOf(keyPrefs.order) }
    var hiddenKeys by remember { mutableStateOf(keyPrefs.hidden) }
    var keysOpen by rememberSaveable { mutableStateOf(false) }
    val suggestionBar = remember { CodeSuggestionBar() }
    var preludeLine by remember { mutableStateOf<Int?>(null) }
    var textSize by rememberCodeTextSize(DEFAULT_TEXT_SIZE)
    // Wrapped at first: a phone is narrow, and a Lua line reads better whole than scrolled sideways.
    var wrap by rememberCodeWrap(true)
    var autoRun by rememberSaveable { mutableStateOf(true) }
    // The text the plugin last ran, typed or pressed, so a pause does not run it again.
    var lastRun by remember { mutableStateOf<String?>(null) }
    val renderHistory = remember { mutableStateListOf<Long>() }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    val find = remember { CodeFindState() }
    var lineOpen by rememberSaveable { mutableStateOf(false) }
    var renamePlan by remember { mutableStateOf<RenamePlan?>(null) }
    var versions by remember { mutableStateOf<List<PluginSnapshot>?>(null) }
    var comparing by remember { mutableStateOf<PluginSnapshot?>(null) }
    var comparison by remember { mutableStateOf<List<DiffRow>?>(null) }
    val historyContext = LocalContext.current
    val files = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PluginFile.MIME_TYPE)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val manifest = ide.manifest
        val script = editor.text
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { files.contentResolver.requireOutputStream(uri).use { PluginFile.write(it, manifest, script) } }.isSuccess
            }
            snackbar.showSnackbar(files.getString(if (saved) R.string.plugin_ide_export_saved_message else R.string.plugin_ide_export_failed_error))
        }
    }

    val text = editor.text
    // The draft on disk is the save point: written after a pause in typing, and
    // again as the screen goes, so leaving never loses a character.
    LaunchedEffect(text) {
        delay(AUTOSAVE_MS)
        withContext(Dispatchers.IO) { ide.save(text) }
    }
    // A version every few minutes while the screen is open. The workspace refuses
    // to keep the same text twice, so an editor left idle keeps nothing.
    LaunchedEffect(draftId) {
        while (true) {
            delay(PERIODIC_VERSION_MS)
            val current = editor.text
            withContext(Dispatchers.IO) { ide.keepWhileWorking(current) }
        }
    }
    LaunchedEffect(text, autoRun) {
        if (!autoRun) return@LaunchedEffect
        delay(AUTO_RUN_MS)
        val parses = withContext(Dispatchers.Default) { LuaDocuments.of(text).syntax == LuaSyntax.Valid }
        if (shouldRunAsTyped(autoRun, text, lastRun, parses)) {
            lastRun = text
            preview.run(draftId, text, ide.manifest)
        }
    }
    val renderUsage = previewState.usage[PluginRuntime.Phase.RENDER]
    LaunchedEffect(renderUsage) {
        if (renderUsage == null || renderUsage.running) return@LaunchedEffect
        renderHistory += renderUsage.instructions
        while (renderHistory.size > MAX_BUDGET_HISTORY) renderHistory.removeAt(0)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        ide.save(editor.text)
        preview.stop()
    }
    DisposableEffect(preview) {
        onDispose {
            ide.save(editor.text)
            preview.shutdown()
        }
    }

    val lineStarts = remember(text) { lineStartOffsets(text) }
    val caretNow = editor.value.selection.end
    val positionLine = lineOf(lineStarts, caretNow.coerceIn(0, text.length))
    val positionColumn = caretNow.coerceIn(0, text.length) - lineStarts[positionLine]
    val apiHere by produceState<LuaApiEntry?>(null, text, caretNow) {
        value = withContext(Dispatchers.Default) { LuaCode.apiAt(text, caretNow) }
    }
    // The events from runs before this one, taken as it starts, for the Events tab to send again.
    val replayable = remember(previewState.runs) { preview.recordedEvents() }
    // What the installed copy of this plugin keeps, read afresh whenever the panel changes.
    val installedStorage by produceState<PluginStorage?>(null, ide.manifest.id, panel) {
        val id = ide.manifest.id
        value = withContext(Dispatchers.IO) {
            if (id.isNotBlank() && store.plugins().any { it.id == id }) store.storageFile(id)?.let { PluginStorage(it) } else null
        }
    }
    // The permission checks follow the manifest as it is edited, not as it was saved.
    val storage = PluginPermission.Storage.wire in ide.manifest.permissions
    val reminders = PluginPermission.Reminders.wire in ide.manifest.permissions
    val inspection by produceState(IdeInspection.None, text, storage, reminders) {
        delay(250)
        val previous = value
        value = withContext(Dispatchers.Default) {
            val found = LuaCode.diagnostics(text, LuaHostShape(storage, reminders))
            // The same document the checks just built, so this is not a second parse.
            val outline = LuaDocuments.of(text).analysis?.let(LuaNavigation::outline) ?: previous.outline
            IdeInspection(found, outline)
        }
    }
    val diagnostics = inspection.diagnostics
    val failureLine = previewState.failure?.takeIf { it.chunk == MAIN_CHUNK }?.line?.minus(1)
    val matches by produceState(emptyList<TextRange>(), text, find.query, find.options, findOpen) {
        if (!findOpen || find.query.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        delay(FIND_DELAY_MS)
        value = withContext(Dispatchers.Default) { findMatches(text, find.query, find.options) }
    }
    val decorations = remember(diagnostics, lineStarts, failureLine, matches, find.active) {
        val marks = HashMap<Int, CodeSeverity>()
        for (diagnostic in diagnostics) {
            val line = lineOf(lineStarts, diagnostic.range.min.coerceIn(0, lineStarts.last()))
            val held = marks[line]
            if (held == null || diagnostic.severity < held) marks[line] = diagnostic.severity
        }
        failureLine?.takeIf { it in lineStarts.indices }?.let { marks[it] = CodeSeverity.ERROR }
        if (marks.isEmpty() && matches.isEmpty()) {
            CodeDecorations.None
        } else {
            CodeDecorations(
                matches = matches,
                activeMatch = if (matches.isEmpty()) -1 else find.active.coerceIn(0, matches.lastIndex),
                squiggles = diagnostics,
                gutterMarks = marks,
            )
        }
    }
    val keyboard = hardwareKeyboardAttached()
    val editorFocus = remember { FocusRequester() }
    var findFocusRequests by remember { mutableIntStateOf(0) }
    val openFind: (Boolean) -> Unit = { replace ->
        val selection = editor.value.selection
        val selected = editor.text.substring(selection.min, selection.max)
        // A selection on one line becomes the query, the way VS Code fills its find widget.
        if (selected.isNotEmpty() && '\n' !in selected) find.query = selected
        if (replace) find.replacing = true
        find.active = firstMatchFrom(findMatches(editor.text, find.query, find.options), selection.min)
        findOpen = true
        findFocusRequests++
    }
    val runPlugin: () -> Unit = {
        lastRun = editor.text
        preview.run(draftId, editor.text, ide.manifest)
        if (panel == IdePanel.CLOSED) panel = IdePanel.PREVIEW
    }
    val goToDefinition: () -> Unit = {
        val source = editor.text
        val caret = editor.value.selection.min
        scope.launch {
            val span = withContext(Dispatchers.Default) {
                LuaNavigation.definitionAt(LuaDocuments.of(source), caret)
            }
            if (span != null && editor.text == source) {
                editor.select(TextRange(span.start, span.end))
            } else {
                snackbar.showSnackbar(context.getString(R.string.plugin_ide_no_definition_message))
            }
        }
    }
    val startRename: () -> Unit = {
        val source = editor.text
        val caret = editor.value.selection.min
        scope.launch {
            val plan = withContext(Dispatchers.Default) { renamePlanAt(source, caret) }
            if (plan != null && editor.text == source) {
                renamePlan = plan
            } else {
                snackbar.showSnackbar(context.getString(R.string.plugin_ide_no_rename_message))
            }
        }
    }
    // What a key asks of the screen rather than of the text. The keys are in CodeShortcuts.kt.
    val onCommand: (CodeCommand) -> Boolean = command@{ command ->
        when (command) {
            CodeCommand.FIND -> openFind(false)
            CodeCommand.REPLACE -> openFind(true)
            CodeCommand.FIND_NEXT, CodeCommand.FIND_PREVIOUS -> when {
                !findOpen -> openFind(false)
                matches.isNotEmpty() -> {
                    find.active = (find.active + if (command == CodeCommand.FIND_NEXT) 1 else -1).mod(matches.size)
                    editor.select(matches[find.active])
                }
            }
            CodeCommand.ESCAPE -> findOpen = false
            CodeCommand.GO_TO_LINE -> lineOpen = true
            CodeCommand.GO_TO_DEFINITION -> goToDefinition()
            CodeCommand.RENAME -> startRename()
            CodeCommand.GO_TO_SYMBOL -> panel = IdePanel.OUTLINE
            CodeCommand.FORMAT -> editor.replace(LuaCode.format(editor.text))
            CodeCommand.TOGGLE_WRAP -> wrap = !wrap
            CodeCommand.ZOOM_IN -> textSize = (textSize + 1).coerceAtMost(MAX_TEXT_SIZE)
            CodeCommand.ZOOM_OUT -> textSize = (textSize - 1).coerceAtLeast(MIN_TEXT_SIZE)
            CodeCommand.ZOOM_RESET -> textSize = DEFAULT_TEXT_SIZE
            // The draft saves itself after a pause; Ctrl+S saves it now and says so.
            CodeCommand.SAVE -> {
                val current = editor.text
                scope.launch {
                    withContext(Dispatchers.IO) { ide.save(current) }
                    snackbar.showSnackbar(context.getString(R.string.plugin_ide_saved_message))
                }
            }
            CodeCommand.RUN -> runPlugin()
            CodeCommand.STOP -> preview.stop()
            CodeCommand.NEXT_PROBLEM, CodeCommand.PREVIOUS_PROBLEM ->
                nextProblem(diagnostics.map { it.range }, editor.value.selection.min, command == CodeCommand.NEXT_PROBLEM)?.let(editor::select)
            CodeCommand.SHOW_PROBLEMS -> panel = if (panel == IdePanel.PROBLEMS) IdePanel.CLOSED else IdePanel.PROBLEMS
            CodeCommand.SHOW_CONSOLE -> panel = if (panel == IdePanel.CONSOLE) IdePanel.CLOSED else IdePanel.CONSOLE
            CodeCommand.SHOW_COMMANDS -> menuOpen = true
            else -> return@command false
        }
        true
    }
    // Back closes the find bar before it leaves the screen. Nothing else waits
    // for Back: the draft is saved, so leaving never loses a character.
    BackHandler(enabled = findOpen) { findOpen = false }

    val title = ide.manifest.name.ifBlank { stringResource(R.string.plugin_ide_draft_untitled) }
    RegisterSettingsCrumb(title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(CommonR.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { editor.undo() }, enabled = editor.canUndo) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(CommonR.string.common_undo))
                    }
                    IconButton(onClick = { editor.redo() }, enabled = editor.canRedo) {
                        Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = stringResource(R.string.code_redo_desc))
                    }
                    val running = previewState.status == PluginPreviewSession.Status.RUNNING
                    IconButton(onClick = {
                        if (running) preview.stop() else runPlugin()
                    }) {
                        Icon(
                            if (running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(if (running) R.string.plugin_ide_stop_desc else R.string.plugin_ide_run_desc),
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) { ide.publish(editor.text) }
                            snackbar.showSnackbar(publishMessage(context, outcome))
                        }
                    }) {
                        Icon(Icons.Outlined.Publish, contentDescription = stringResource(R.string.plugin_ide_publish_desc))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.plugin_ide_more_desc))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_find_action)) },
                                leadingIcon = { Icon(Icons.Outlined.FindReplace, contentDescription = null) },
                                trailingIcon = shortcutSlot(keyboard, CodeCommand.FIND),
                                onClick = {
                                    menuOpen = false
                                    openFind(false)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_go_to_line_action)) },
                                leadingIcon = { Icon(Icons.Outlined.FormatListNumbered, contentDescription = null) },
                                trailingIcon = shortcutSlot(keyboard, CodeCommand.GO_TO_LINE),
                                onClick = {
                                    menuOpen = false
                                    lineOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_definition_action)) },
                                leadingIcon = { Icon(Icons.Outlined.DataObject, contentDescription = null) },
                                trailingIcon = shortcutSlot(keyboard, CodeCommand.GO_TO_DEFINITION),
                                onClick = {
                                    menuOpen = false
                                    goToDefinition()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_rename_action)) },
                                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                                trailingIcon = shortcutSlot(keyboard, CodeCommand.RENAME),
                                onClick = {
                                    menuOpen = false
                                    startRename()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_export_action)) },
                                leadingIcon = { Icon(Icons.Outlined.SaveAlt, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    if (PluginManifestCodec.problems(ide.manifest).isNotEmpty()) {
                                        scope.launch { snackbar.showSnackbar(files.getString(R.string.plugin_ide_export_problems_error)) }
                                    } else {
                                        exportLauncher.launch(PluginFile.fileName(ide.manifest))
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_versions_action)) },
                                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    val current = editor.text
                                    scope.launch {
                                        versions = withContext(Dispatchers.IO) {
                                            ide.save(current)
                                            ide.versions()
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_text_larger_action)) },
                                leadingIcon = { Icon(Icons.Outlined.TextIncrease, contentDescription = null) },
                                trailingIcon = shortcutSlot(keyboard, CodeCommand.ZOOM_IN),
                                enabled = textSize < MAX_TEXT_SIZE,
                                onClick = { textSize = (textSize + 1).coerceAtMost(MAX_TEXT_SIZE) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_text_smaller_action)) },
                                leadingIcon = { Icon(Icons.Outlined.TextDecrease, contentDescription = null) },
                                trailingIcon = shortcutSlot(keyboard, CodeCommand.ZOOM_OUT),
                                enabled = textSize > MIN_TEXT_SIZE,
                                onClick = { textSize = (textSize - 1).coerceAtLeast(MIN_TEXT_SIZE) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (wrap) R.string.code_wrap_off_desc else R.string.code_wrap_on_desc)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.WrapText, contentDescription = null) },
                                trailingIcon = shortcutSlot(keyboard, CodeCommand.TOGGLE_WRAP),
                                onClick = {
                                    menuOpen = false
                                    wrap = !wrap
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_fold_all_action)) },
                                leadingIcon = { Icon(Icons.Outlined.UnfoldLess, contentDescription = null) },
                                trailingIcon = shortcutSlot(keyboard, CodeCommand.FOLD_ALL),
                                onClick = {
                                    menuOpen = false
                                    editor.foldAll(LuaCode.foldRegions(editor.text).map { it.min })
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_unfold_all_action)) },
                                leadingIcon = { Icon(Icons.Outlined.UnfoldMore, contentDescription = null) },
                                trailingIcon = shortcutSlot(keyboard, CodeCommand.UNFOLD_ALL),
                                enabled = editor.foldStarts.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    editor.unfoldAll()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_code_keys_action)) },
                                leadingIcon = { Icon(Icons.Outlined.Keyboard, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    keysOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_format_action)) },
                                leadingIcon = { Icon(Icons.Outlined.AutoFixHigh, contentDescription = null) },
                                trailingIcon = shortcutSlot(keyboard, CodeCommand.FORMAT),
                                onClick = {
                                    menuOpen = false
                                    editor.replace(LuaCode.format(editor.text))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_details_action)) },
                                leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    detailsOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.plugin_ide_version_action)) },
                                leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        val kept = withContext(Dispatchers.IO) { ide.saveVersion(editor.text) }
                                        snackbar.showSnackbar(
                                            context.getString(
                                                if (kept == null) R.string.plugin_ide_version_same_message else R.string.plugin_ide_version_saved_message,
                                            ),
                                        )
                                    }
                                },
                            )
                            // The whole draft, as the JSON editor's Copy and Paste take the whole document.
                            DropdownMenuItem(
                                text = { Text(stringResource(CommonR.string.common_copy)) },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    copyCode(context, editor.text)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(CommonR.string.common_paste)) },
                                leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    pasteCode(context)?.let(editor::replace)
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The app targets SDK 36, so from Android 15 the window is drawn edge to edge
        // and no longer resizes for the keyboard. The content stops at the top of the
        // keyboard instead: the code key row sits on it, and the code ends where the
        // keyboard begins, which is also how the suggestion list knows there is no
        // room below the caret. The Scaffold's own bottom inset is consumed first so
        // the navigation bar is not counted twice.
        ResizeForKeyboard()
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            AnimatedVisibility(
                visible = findOpen,
                enter = if (reduceMotion) fadeIn(snap()) else expandVertically() + fadeIn(),
                exit = if (reduceMotion) fadeOut(snap()) else shrinkVertically() + fadeOut(),
            ) {
                CodeFindBar(
                    find = find,
                    matches = matches,
                    onStep = { delta ->
                        if (matches.isNotEmpty()) {
                            find.active = (find.active + delta).mod(matches.size)
                            editor.select(matches[find.active])
                        }
                    },
                    onReplace = {
                        // Found again in the text as it is now, not as it was when the list was drawn.
                        val current = findMatches(editor.text, find.query, find.options)
                        current.getOrNull(find.active.coerceIn(0, maxOf(0, current.lastIndex)))?.let { match ->
                            editor.applyEdit(replaceMatch(editor.text, match, find.replacement, find.query, find.options))
                        }
                    },
                    onReplaceAll = {
                        val current = findMatches(editor.text, find.query, find.options)
                        replaceAllMatches(editor.text, current, find.replacement, find.query, find.options)?.let(editor::applyEdit)
                    },
                    onClose = { findOpen = false },
                    onEscape = {
                        findOpen = false
                        editorFocus.requestFocus()
                    },
                    onCommand = onCommand,
                    focusRequests = findFocusRequests,
                )
            }
            CodeSurface(
                state = editor,
                language = LuaCode,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                lineStarts = lineStarts,
                decorations = decorations,
                wrap = wrap,
                fontSize = textSize.sp,
                lineHeight = (textSize * LINE_HEIGHT_RATIO).sp,
                completions = true,
                suggestRequests = suggestRequests,
                onGutterPress = { line ->
                    panel = IdePanel.PROBLEMS
                    editor.moveTo(offsetOfLine(editor.text, line))
                },
                folding = true,
                suggestionBar = suggestionBar,
                reduceMotion = reduceMotion,
                onCommand = onCommand,
                focusRequester = editorFocus,
            )
            apiHere?.let { ApiDocStrip(it, rememberCodeColors()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    IdePanelBar(panel, problems = diagnostics.size) { chosen -> panel = if (panel == chosen) IdePanel.CLOSED else chosen }
                }
                // Where the caret is, at the end of the tabs, as the JSON editor shows it.
                Text(
                    stringResource(R.string.code_position_label, positionLine + 1, positionColumn + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            if (panel != IdePanel.CLOSED) {
                HorizontalDivider()
                Box(Modifier.fillMaxWidth().fillMaxHeight(PANEL_FRACTION)) {
                    when (panel) {
                        IdePanel.PREVIEW -> PreviewPane(preview, previewState, autoRun, { autoRun = it }, renderHistory)
                        IdePanel.CONSOLE -> ConsolePane(preview, previewState.consoleRevision, { preludeLine = it }) { line ->
                            editor.moveTo(offsetOfLine(editor.text, line - 1))
                        }
                        IdePanel.PROBLEMS -> ProblemsPane(diagnostics, lineStarts) { range -> editor.select(range) }
                        IdePanel.OUTLINE -> OutlinePane(inspection.outline, lineStarts) { range -> editor.select(range) }
                        IdePanel.EVENTS -> EventsPane(previewState.targets, replayable, preview::send)
                        IdePanel.STORAGE -> StoragePane(preview.storageOf(draftId), declared = storage, busy = previewState.busy, installed = installedStorage)
                        IdePanel.CLOSED -> Unit
                    }
                }
            }
            // At the bottom, so it sits against the soft keyboard whether or not the
            // panel is open. A hardware keyboard has its own keys for all of it.
            if (!keyboard && showKeys) {
                CodeAccessoryRow(
                    state = editor,
                    colors = rememberCodeColors(),
                    lineComment = LuaCode.lineComment,
                    onFind = { findOpen = true },
                    onFormat = { editor.replace(LuaCode.format(editor.text)) },
                    onSuggest = { suggestRequests++ },
                    blocks = LuaCode::foldRegions,
                    keys = remember(keyOrder, hiddenKeys) { arrangeKeys(LuaAccessoryKeys, keyOrder, hiddenKeys) },
                    suggestionBar = suggestionBar,
                )
            }
        }
    }

    if (lineOpen) {
        GoToLineDialog(
            lines = lineStarts.size,
            onDismiss = { lineOpen = false },
            onGo = { line ->
                lineOpen = false
                editor.moveTo(offsetOfLine(editor.text, line - 1))
            },
        )
    }
    renamePlan?.let { plan ->
        RenameDialog(
            plan = plan,
            onDismiss = { renamePlan = null },
            onRename = { name ->
                renamePlan = null
                if (editor.text == plan.snapshot) renameEdit(plan.snapshot, plan.spans, name, plan.caret)?.let(editor::applyEdit)
            },
        )
    }

    versions?.let { list ->
        VersionsDialog(
            versions = list,
            onOpen = { version ->
                comparing = version
                comparison = null
                val current = editor.text
                scope.launch {
                    val rows = withContext(Dispatchers.IO) {
                        ide.versionText(version.snapshotId)?.let { collapseDiff(lineDiff(current, it)) }
                    }
                    if (rows != null) {
                        comparison = rows
                    } else {
                        comparing = null
                        snackbar.showSnackbar(historyContext.getString(R.string.plugin_ide_version_restore_failed_error))
                    }
                }
            },
            onDismiss = { versions = null },
        )
    }
    comparing?.let { version ->
        VersionDiffDialog(
            version = version,
            rows = comparison,
            onRestore = {
                comparing = null
                versions = null
                val current = editor.text
                scope.launch {
                    val body = withContext(Dispatchers.IO) { ide.restore(version.snapshotId, current) }
                    if (body != null) {
                        editor.replace(body)
                        snackbar.showSnackbar(historyContext.getString(R.string.plugin_ide_version_restored_message))
                    } else {
                        snackbar.showSnackbar(historyContext.getString(R.string.plugin_ide_version_restore_failed_error))
                    }
                }
            },
            onDismiss = { comparing = null },
        )
    }

    preludeLine?.let { line -> PreludeDialog(line) { preludeLine = null } }

    if (keysOpen) {
        CodeKeysDialog(
            showRow = showKeys,
            order = keyOrder,
            hidden = hiddenKeys,
            onShowRow = {
                showKeys = it
                keyPrefs.showRow = it
            },
            onOrder = {
                keyOrder = it
                keyPrefs.order = it
            },
            onHidden = {
                hiddenKeys = it
                keyPrefs.hidden = it
            },
            onReset = {
                keyPrefs.reset()
                showKeys = true
                keyOrder = emptyList()
                hiddenKeys = emptySet()
            },
            onDismiss = { keysOpen = false },
        )
    }

    if (detailsOpen) {
        PluginDetailsDialog(
            manifest = ide.manifest,
            onDismiss = { detailsOpen = false },
            onSave = { updated ->
                detailsOpen = false
                scope.launch { withContext(Dispatchers.IO) { ide.updateManifest(updated) } }
            },
        )
    }
}

/** The chunk name the runtime gives the plugin's own script. */
private const val MAIN_CHUNK = "main.lua"

/** The chunk name the runtime gives the prelude that runs before every plugin. */
private const val PRELUDE_CHUNK = "prelude.lua"

/** How much of the screen the panel under the code takes when it is open. */
private const val PANEL_FRACTION = 0.42f

/** How long the find bar waits after a change before it searches again. */
private const val FIND_DELAY_MS = 120L

/** How often the editor keeps a version while it is open. */
private const val PERIODIC_VERSION_MS = 5 * 60 * 1000L

/** How long typing must pause before the preview runs the draft by itself. */
private const val AUTO_RUN_MS = 600L

/** The code text size in points, and the steps Larger text and Smaller text move between. */
private const val DEFAULT_TEXT_SIZE = 14
private const val MIN_TEXT_SIZE = 11
private const val MAX_TEXT_SIZE = 22

/** Line height over text size, as the editor's own 14 on 22 has it. */
private const val LINE_HEIGHT_RATIO = 22f / 14f

private fun publishMessage(context: Context, outcome: PublishOutcome): String = when (outcome) {
    is PublishOutcome.Published -> context.getString(
        if (outcome.replaced) R.string.plugin_ide_updated_message else R.string.plugin_ide_published_message,
        outcome.version,
    )
    is PublishOutcome.Invalid -> outcome.reason.resolve(context)
    PublishOutcome.TooManyPlugins -> context.getString(R.string.plugin_ide_too_many_error)
    PublishOutcome.Failed -> context.getString(R.string.plugin_ide_publish_failed_error)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissingDraft(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(CommonR.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            CaptionText(stringResource(R.string.plugin_ide_missing))
        }
    }
}

@Composable
private fun IdePanelBar(panel: IdePanel, problems: Int, onSelect: (IdePanel) -> Unit) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = panel == IdePanel.PREVIEW,
            onClick = { onSelect(IdePanel.PREVIEW) },
            label = { Text(stringResource(R.string.plugin_ide_preview_label)) },
        )
        FilterChip(
            selected = panel == IdePanel.CONSOLE,
            onClick = { onSelect(IdePanel.CONSOLE) },
            label = { Text(stringResource(R.string.plugin_ide_console_label)) },
        )
        FilterChip(
            selected = panel == IdePanel.PROBLEMS,
            onClick = { onSelect(IdePanel.PROBLEMS) },
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.plugin_ide_problems_label))
                    if (problems > 0) {
                        Spacer(Modifier.width(6.dp))
                        Badge { Text(numbers.format(problems)) }
                    }
                }
            },
        )
        FilterChip(
            selected = panel == IdePanel.OUTLINE,
            onClick = { onSelect(IdePanel.OUTLINE) },
            label = { Text(stringResource(R.string.plugin_ide_outline_label)) },
        )
        FilterChip(
            selected = panel == IdePanel.EVENTS,
            onClick = { onSelect(IdePanel.EVENTS) },
            label = { Text(stringResource(R.string.plugin_ide_events_label)) },
        )
        FilterChip(
            selected = panel == IdePanel.STORAGE,
            onClick = { onSelect(IdePanel.STORAGE) },
            label = { Text(stringResource(R.string.plugin_ide_storage_label)) },
        )
    }
}

/** The plugin's panel as the keyboard would draw it, in this app's colours, with real text fields. */
@Composable
private fun PreviewPane(
    preview: PluginPreviewSession,
    state: PluginPreviewSession.State,
    autoRun: Boolean,
    onAutoRun: (Boolean) -> Unit,
    history: List<Long>,
) {
    val context = LocalContext.current
    val style = rememberMaterialPluginStyle()
    val host = remember(preview, state.inputs) { PreviewInputHost(preview, state.inputs, context) }
    val copied = stringResource(R.string.plugin_ide_copied_message)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.plugin_ide_auto_run_label),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = autoRun, onCheckedChange = onAutoRun)
        }
        state.usage[PluginRuntime.Phase.RENDER]?.let { usage -> BudgetMeter(usage, history) }
        state.failure?.let { failure ->
            Text(
                failure.text.substringBefore('\n'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.ui.root.isEmpty()) {
            Text(
                stringResource(R.string.plugin_ide_preview_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompositionLocalProvider(LocalPluginPanelStyle provides style) {
                PluginWidgetList(
                    widgets = state.ui.root,
                    host = host,
                    onEvent = preview::send,
                    onInsert = { copyText(context, it, copied) },
                    onCopy = { copyText(context, it, copied) },
                )
            }
        }
    }
}

/** What the plugin printed, where it failed and when it stopped, oldest first. */
@Composable
private fun ConsolePane(preview: PluginPreviewSession, revision: Int, onPrelude: (Int) -> Unit, onJump: (Int) -> Unit) {
    val entries = remember(revision) { preview.console() }
    val list = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) list.scrollToItem(entries.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.plugin_ide_console_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { preview.clearConsole() }) { Text(stringResource(R.string.plugin_ide_console_clear_action)) }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            items(entries) { entry -> ConsoleLine(entry, onPrelude, onJump) }
        }
    }
}

@Composable
private fun ConsoleLine(entry: PluginPreviewSession.ConsoleEntry, onPrelude: (Int) -> Unit, onJump: (Int) -> Unit) {
    val context = LocalContext.current
    val words = when (entry.kind) {
        PluginPreviewSession.ConsoleEntry.Kind.STARTED -> stringResource(R.string.plugin_ide_console_started)
        PluginPreviewSession.ConsoleEntry.Kind.CANCELLED -> stringResource(R.string.plugin_ide_console_cancelled)
        else -> entry.text.ifEmpty { entry.message?.resolve(context).orEmpty() }
    }
    val colour = when (entry.kind) {
        PluginPreviewSession.ConsoleEntry.Kind.ERROR, PluginPreviewSession.ConsoleEntry.Kind.STOPPED -> MaterialTheme.colorScheme.error
        PluginPreviewSession.ConsoleEntry.Kind.PRINT -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val line = entry.line?.takeIf { entry.chunk == MAIN_CHUNK }
    val preludeLine = entry.line?.takeIf { entry.chunk == PRELUDE_CHUNK }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    line != null -> Modifier.clickable { onJump(line) }
                    preludeLine != null -> Modifier.clickable { onPrelude(preludeLine) }
                    else -> Modifier
                },
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            words,
            fontFamily = CodeFontFamily,
            fontSize = 12.sp,
            color = colour,
            modifier = Modifier.weight(1f),
        )
        if (preludeLine != null) {
            Text(
                stringResource(R.string.plugin_ide_console_prelude_line_label, preludeLine),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (line != null) {
            Text(
                stringResource(R.string.plugin_ide_console_line_label, line),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** The manifest, with what the importer would refuse listed under it as it is typed. */
@Composable
private fun PluginDetailsDialog(manifest: PluginManifest, onDismiss: () -> Unit, onSave: (PluginManifest) -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(manifest.name) }
    var id by rememberSaveable { mutableStateOf(manifest.id) }
    var version by rememberSaveable { mutableStateOf(manifest.pluginVersion) }
    var author by rememberSaveable { mutableStateOf(manifest.author) }
    var description by rememberSaveable { mutableStateOf(manifest.description) }
    // The declared permissions as their wire strings, comma-joined so the state
    // saves without a custom saver. Every permission this build knows gets a
    // box, so saving the form never drops one the draft already declared.
    var declared by rememberSaveable { mutableStateOf(manifest.permissions.joinToString(",")) }
    val granted = declared.split(',').filter { it.isNotEmpty() }.toSet()
    val edited = manifest.copy(
        format = PluginManifestCodec.FORMAT,
        name = name,
        id = id,
        pluginVersion = version,
        author = author,
        description = description,
        permissions = PluginPermission.all.map { it.wire }.filter { it in granted },
    )
    val problems = remember(edited) { PluginManifestCodec.problems(edited) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_ide_details_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.plugin_ide_details_name_label)) }, singleLine = true)
                OutlinedTextField(id, { id = it }, label = { Text(stringResource(R.string.plugin_ide_details_id_label)) }, singleLine = true)
                OutlinedTextField(version, { version = it }, label = { Text(stringResource(R.string.plugin_ide_details_version_label)) }, singleLine = true)
                OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.plugin_ide_details_author_label)) }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.plugin_ide_details_description_label)) })
                for (permission in PluginPermission.all) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = permission.wire in granted,
                            onCheckedChange = { on ->
                                declared = (if (on) granted + permission.wire else granted - permission.wire).joinToString(",")
                            },
                        )
                        Text(stringResource(permission.labelRes))
                    }
                }
                for (problem in problems) {
                    Text(
                        problem.text.resolve(context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton({ onSave(edited) }) { Text(stringResource(CommonR.string.common_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

/** The shared plugin renderer, dressed in this app's Material colours. No focus ring, no chip outline. */
@Composable
internal fun rememberMaterialPluginStyle(): PluginPanelStyle {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    return remember(colors, shape) {
        PluginPanelStyle(
            text = colors.onSurface,
            secondaryText = colors.onSurfaceVariant,
            surface = colors.surfaceContainerHigh,
            onSurface = colors.onSurface,
            accent = colors.primary,
            cardShape = shape,
            cardBorder = { modifier, _ -> modifier },
            ring = { modifier, _ -> modifier },
            chip = { label, selected, enabled, modifier, onClick ->
                FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, enabled = enabled, modifier = modifier)
            },
        )
    }
}

/** A preview's inputs are real fields: typing is an input event, and Paste reads the clipboard. */
private class PreviewInputHost(
    private val preview: PluginPreviewSession,
    private val inputs: Map<String, String>,
    private val context: Context,
) : PluginInputHost {
    override fun value(id: String): String = inputs[id].orEmpty()

    override fun isFocused(id: String): Boolean = false

    override fun onFocus(id: String) = Unit

    override fun onPaste(id: String) {
        pasteText(context)?.let { preview.send(PluginEvent.InputChanged(id, it)) }
    }

    override val onValueChange: (String, String) -> Unit = { id, text ->
        preview.send(PluginEvent.InputChanged(id, text))
    }
}

private fun copyText(context: Context, text: String, confirmation: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("plugin", text))
        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
    }
}

private fun pasteText(context: Context): String? = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = manager.primaryClip ?: return null
    (0 until clip.itemCount).asSequence().mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() }.firstOrNull()
}.getOrNull()
