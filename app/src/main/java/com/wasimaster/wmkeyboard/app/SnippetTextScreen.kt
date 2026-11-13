package com.wasimaster.wmkeyboard.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileUpload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Restore
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.snippets.SnippetText
import com.wasimaster.wmkeyboard.core.util.readTextCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The route of [SnippetTextScreen]. */
internal const val SNIPPETS_TEXT_ROUTE = "expander/text"

/**
 * The list a save replaced and the folders it made, kept for as long as the
 * settings process lives so "Undo last save" still works after leaving the
 * screen and coming back. One save deep: a second save replaces it.
 */
private object SnippetTextUndo {
    @Volatile
    var before: List<Snippet>? = null

    @Volatile
    var madeFolders: List<Long> = emptyList()
}

/**
 * Every snippet as one plain-text document (gap list item 33), in the editor
 * the layout JSON screens use. The format is [SnippetText]'s: one
 * `=== trigger ===` block per snippet.
 *
 * A draft with an explicit Save. Saving reads the whole document, refuses it
 * while any line has a mistake (each one marked in the gutter, the first named
 * under the editor), then shows what it would add, change and delete before
 * anything is written. The list it replaced is kept for a one-tap undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SnippetTextScreen(settings: LiveSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val title = stringResource(R.string.expander_text_title)
    val file = remember { java.io.File(context.filesDir, SnippetStore.FILE_PATH) }
    var store by remember { mutableStateOf<SnippetStore?>(null) }
    // The document as last loaded or saved; the draft is dirty against it.
    var savedText by remember { mutableStateOf<String?>(null) }
    // Bumped whenever the editor must start over on a new document.
    var generation by remember { mutableIntStateOf(0) }
    val header = listOf(
        stringResource(R.string.expander_text_header_1),
        stringResource(R.string.expander_text_header_2),
    )

    fun document(s: SnippetStore): String = SnippetText.encode(s.items(), s.folders(), header)

    LaunchedEffect(Unit) {
        val (s, text) = withContext(Dispatchers.IO) { SnippetStore(file).let { it to document(it) } }
        store = s
        savedText = text
        generation++
    }

    RegisterSettingsCrumb(title)
    val loaded = savedText
    if (store == null || loaded == null) {
        Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.padding(32.dp)) }
        return
    }
    val editor = rememberCodeEditorState("$SNIPPETS_TEXT_ROUTE#$generation", SnippetTextCode.smartRules, keepHistory = true) {
        loaded
    }
    val colors = rememberCodeColors()
    val text = editor.text
    val lineStarts = remember(text) { lineStartOffsets(text) }
    // Read a moment after typing stops, off the main thread: a long list is a
    // long document, and the field must not wait on it.
    val parsed by produceState(SnippetText.Parsed(emptyList(), emptyList()), text) {
        delay(PARSE_DELAY_MS)
        value = withContext(Dispatchers.Default) { SnippetText.parse(text) }
    }
    val decorations = remember(parsed, lineStarts) {
        if (parsed.problems.isEmpty()) {
            CodeDecorations.None
        } else {
            CodeDecorations(
                squiggles = parsed.problems.map { CodeDiagnostic(lineRange(text, lineStarts, it.line - 1), CodeSeverity.ERROR) },
                gutterMarks = parsed.problems.associate { it.line - 1 to CodeSeverity.ERROR },
            )
        }
    }
    val dirty = text != loaded
    var menuOpen by remember { mutableStateOf(false) }
    var pendingPlan by remember { mutableStateOf<SnippetText.Plan?>(null) }
    var refused by remember { mutableStateOf<List<SnippetText.Problem>>(emptyList()) }
    var leaving by remember { mutableStateOf(false) }
    var canRestore by remember { mutableStateOf(SnippetTextUndo.before != null) }

    val savedMessage = stringResource(R.string.expander_text_saved)
    val nothingMessage = stringResource(R.string.expander_text_nothing_changed)
    val restoredMessage = stringResource(R.string.expander_text_restored)
    val undoLabel = stringResource(CommonR.string.common_undo)
    val readMessage = stringResource(R.string.expander_text_read_in)
    val readFailedMessage = stringResource(R.string.expander_text_read_failed)
    val exportedMessage = stringResource(R.string.expander_text_exported)
    val exportFailedMessage = stringResource(R.string.expander_export_error)

    /** Puts back the list the last save replaced. */
    fun restore() {
        val s = store ?: return
        val before = SnippetTextUndo.before ?: return
        val made = SnippetTextUndo.madeFolders
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                s.replaceAll(before)
                for (id in made) s.removeFolderIfEmpty(id)
                s.save()
                document(s)
            }
            SnippetTextUndo.before = null
            SnippetTextUndo.madeFolders = emptyList()
            canRestore = false
            savedText = restored
            generation++
            snackbar.showSnackbar(restoredMessage)
        }
    }

    /** Writes [plan] over the stored list, keeping the old one for [restore]. */
    fun apply(plan: SnippetText.Plan) {
        val s = store ?: return
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                val before = s.items()
                val made = s.replaceFromText(plan)
                s.save()
                SnippetTextUndo.before = before
                SnippetTextUndo.madeFolders = made.map { it.id }
                document(s)
            }
            canRestore = true
            savedText = written
            generation++
            val result = snackbar.showSnackbar(savedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) restore()
        }
    }

    val save: () -> Unit = save@{
        val s = store ?: return@save
        val draft = editor.text
        scope.launch {
            val plan = withContext(Dispatchers.IO) {
                val items = s.items()
                val known = HashSet<String>()
                items.mapNotNullTo(known) { it.image }
                s.imagesDir()?.list()?.let { known.addAll(it) }
                SnippetText.plan(items, s.folders(), SnippetText.parse(draft), known)
            }
            when {
                plan.problems.isNotEmpty() -> refused = plan.problems
                plan.isEmpty -> snackbar.showSnackbar(nothingMessage)
                else -> pendingPlan = plan
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val body = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.readTextCapped(uri) }.getOrNull()
            }
            if (body == null || !SnippetText.looksLikeText(body)) {
                snackbar.showSnackbar(readFailedMessage)
            } else {
                // Into the draft, like a paste: nothing is stored until Save,
                // and Save shows what the file would change first.
                editor.replace(body)
                snackbar.showSnackbar(readMessage)
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SnippetText.MIME_TYPE)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val body = editor.text
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(body.toByteArray()) } != null
                }.getOrDefault(false)
            }
            snackbar.showSnackbar(if (written) exportedMessage else exportFailedMessage)
        }
    }

    BackHandler(enabled = dirty) { leaving = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) leaving = true else onBack() }) {
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
                    TextButton(onClick = save, enabled = dirty) {
                        Text(stringResource(CommonR.string.common_save))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.plugin_ide_more_desc))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (canRestore) {
                                TextMenuItem(stringResource(R.string.expander_text_restore), Icons.Outlined.Restore) {
                                    menuOpen = false
                                    restore()
                                }
                            }
                            TextMenuItem(stringResource(CommonR.string.common_copy), Icons.Outlined.ContentCopy) {
                                menuOpen = false
                                copyCode(context, editor.text)
                            }
                            TextMenuItem(stringResource(CommonR.string.common_paste), Icons.Outlined.ContentPaste) {
                                menuOpen = false
                                pasteCode(context)?.let(editor::replace)
                            }
                            TextMenuItem(stringResource(R.string.expander_text_import), Icons.Outlined.FileDownload) {
                                menuOpen = false
                                importLauncher.launch(arrayOf("text/plain", "*/*"))
                            }
                            TextMenuItem(stringResource(R.string.expander_text_export), Icons.Outlined.FileUpload) {
                                menuOpen = false
                                exportLauncher.launch(SnippetText.FILE_NAME)
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val reduceMotion = settings.watch { it.reduceMotion }
        ResizeForKeyboard()
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            CodeSurface(
                state = editor,
                language = SnippetTextCode,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                colors = colors,
                lineStarts = lineStarts,
                decorations = decorations,
                wrap = true,
                reduceMotion = reduceMotion,
            )
            HorizontalDivider()
            TextStatus(parsed) { line -> editor.select(lineRange(text, lineStarts, line - 1)) }
        }
    }

    pendingPlan?.let { plan ->
        SnippetTextDiffDialog(
            plan = plan,
            onDismiss = { pendingPlan = null },
            onSave = {
                pendingPlan = null
                apply(plan)
            },
        )
    }

    if (refused.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { refused = emptyList() },
            title = { Text(pluralStringResource(R.plurals.expander_text_refused_title, refused.size, refused.size)) },
            text = {
                Column(Modifier.heightIn(max = TextDialogMaxHeight).verticalScroll(rememberScrollState())) {
                    for (problem in refused.take(MAX_LISTED)) {
                        DialogNote(stringResource(R.string.expander_text_line, problem.line, problem.message.resolve(context)))
                    }
                    if (refused.size > MAX_LISTED) {
                        DialogNote(pluralStringResource(R.plurals.expander_text_more, refused.size - MAX_LISTED, refused.size - MAX_LISTED))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val first = refused.first().line
                    refused = emptyList()
                    editor.select(lineRange(editor.text, lineStartOffsets(editor.text), first - 1))
                }) { Text(stringResource(R.string.expander_text_go_to_first)) }
            },
            dismissButton = {
                TextButton(onClick = { refused = emptyList() }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.expander_text_discard_title)) },
            text = { Text(stringResource(R.string.expander_text_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    onBack()
                }) { Text(stringResource(R.string.expander_text_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.expander_text_keep_editing)) }
            },
        )
    }
}

private const val PARSE_DELAY_MS = 250L

private val TextDialogMaxHeight = 400.dp

/** Most snippets or mistakes a dialog lists by name before it only counts the rest. */
private const val MAX_LISTED = 30

/** What saving would do, by snippet, before it is done. */
@Composable
private fun SnippetTextDiffDialog(plan: SnippetText.Plan, onDismiss: () -> Unit, onSave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expander_text_diff_title)) },
        text = {
            Column(Modifier.heightIn(max = TextDialogMaxHeight).verticalScroll(rememberScrollState())) {
                DiffSection(R.plurals.expander_text_diff_added, plan.added, MaterialTheme.colorScheme.primary, "+")
                DiffSection(R.plurals.expander_text_diff_changed, plan.changed, MaterialTheme.colorScheme.tertiary, "~")
                DiffSection(R.plurals.expander_text_diff_removed, plan.removed, MaterialTheme.colorScheme.error, "−")
                if (plan.reordered) DialogNote(stringResource(R.string.expander_text_diff_reordered))
                if (plan.newFolders.isNotEmpty()) {
                    DialogNote(
                        pluralStringResource(
                            R.plurals.expander_text_diff_folders,
                            plan.newFolders.size,
                            plan.newFolders.joinToString(", "),
                        ),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(CommonR.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

@Composable
private fun DiffSection(heading: Int, snippets: List<Snippet>, color: androidx.compose.ui.graphics.Color, mark: String) {
    if (snippets.isEmpty()) return
    Text(
        pluralStringResource(heading, snippets.size, snippets.size),
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
    for (snippet in snippets.take(MAX_LISTED)) {
        val key = SnippetText.keyOf(snippet)
        val name = if (snippet.label.isNotBlank() && snippet.label != key) "$key · ${snippet.label}" else key
        Text(
            "$mark $name",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (snippets.size > MAX_LISTED) {
        Text(
            pluralStringResource(R.plurals.expander_text_more, snippets.size - MAX_LISTED, snippets.size - MAX_LISTED),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
}

/** The first mistake (a tap selects its line), else how many snippets the document holds. */
@Composable
private fun TextStatus(parsed: SnippetText.Parsed, onLine: (Int) -> Unit) {
    val context = LocalContext.current
    val first = parsed.problems.firstOrNull()
    val message = if (first != null) {
        pluralStringResource(
            R.plurals.expander_text_status_problems,
            parsed.problems.size,
            parsed.problems.size,
            stringResource(R.string.expander_text_line, first.line, first.message.resolve(context)),
        )
    } else {
        pluralStringResource(R.plurals.expander_text_status_count, parsed.entries.size, parsed.entries.size)
    }
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (first != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (first != null) Modifier.clickable { onLine(first.line) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun TextMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** Line [index] of [text], without its line break. */
private fun lineRange(text: String, lineStarts: List<Int>, index: Int): TextRange {
    val start = lineStarts.getOrElse(index) { text.length }
    val end = lineStarts.getOrNull(index + 1)?.minus(1) ?: text.length
    return TextRange(start, end.coerceAtLeast(start))
}

/**
 * The snippet document for the editor: headers as headings, `@name:` settings
 * as keys and values, separators as operators, and the comment lines above
 * the first block dimmed. A `#` inside a snippet is text, so it is not dimmed.
 */
private object SnippetTextCode : CodeLanguage {

    override val smartRules: CodeSmartRules = CodeSmartRules(
        pairs = emptyMap(),
        quotes = emptySet(),
        inert = { _, _ -> true },
    )

    override fun highlight(source: String, colors: CodeColors): AnnotatedString = buildAnnotatedString {
        var start = 0
        var seenHeader = false
        var inSettings = false
        while (start <= source.length) {
            val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
            val line = source.substring(start, end)
            val isHeader = line.startsWith("===") && line.trimEnd().endsWith("===") && line.trimEnd().length >= 6
            when {
                isHeader -> {
                    seenHeader = true
                    inSettings = true
                    withStyle(SpanStyle(color = colors.keyword)) { append(line) }
                }
                !seenHeader && line.startsWith("#") -> withStyle(SpanStyle(color = colors.comment)) { append(line) }
                inSettings && line.startsWith("@") -> {
                    val colon = line.indexOf(':').let { if (it < 0) line.length else it + 1 }
                    withStyle(SpanStyle(color = colors.key)) { append(line, 0, colon) }
                    withStyle(SpanStyle(color = colors.string)) { append(line, colon, line.length) }
                }
                line == SnippetText.SEPARATOR -> {
                    inSettings = false
                    withStyle(SpanStyle(color = colors.operator)) { append(line) }
                }
                else -> {
                    inSettings = false
                    append(line)
                }
            }
            if (end < source.length) append('\n')
            start = end + 1
        }
    }

    override fun format(source: String): String? = null

    override fun problem(source: String): CodeProblem? = null

    override fun brackets(source: String): List<Int> = emptyList()

    override fun matchingBracket(source: String, brackets: List<Int>, caret: Int): Pair<Int, Int>? = null
}
