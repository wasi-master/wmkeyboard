package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.WrapText
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoFixHigh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FindReplace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListNumbered
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SaveAs
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextDecrease
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextIncrease
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldMore
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.util.readTextCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plain text, as far as the editor is concerned: nothing to colour, nothing to
 * tidy up, nothing that pairs.
 *
 * The editor still gives it line numbers, an active-line band, find and
 * replace, folding chevrons where there are none, undo and the code key row —
 * everything that does not depend on knowing the language. A file that is not
 * JSON is usually a config someone wants to read rather than write, and this is
 * enough for that.
 */
internal object PlainCode : CodeLanguage {

    override fun highlight(source: String, colors: CodeColors): AnnotatedString = AnnotatedString(source)

    override fun format(source: String): String? = null

    override fun problem(source: String): CodeProblem? = null

    override fun brackets(source: String): List<Int> = emptyList()

    override fun matchingBracket(source: String, brackets: List<Int>, caret: Int): Pair<Int, Int>? = null
}

/**
 * The screen a file opens in when it is readable text but none of the
 * keyboard's own formats.
 *
 * The manifest claims `application/json` and `application/octet-stream` so that
 * a theme downloaded in a browser — whose content URI carries no file name —
 * still reaches this app. The cost is being offered for files that are nobody's
 * business here, and this screen is what makes that honest: someone who picked
 * this app for their `docker-compose.json` gets to read it and close it rather
 * than an error and a wasted tap.
 *
 * Not exported. [ImportFileActivity] starts it, and re-grants the URI on the
 * way: a grant made to this process by the opening app can be passed on while
 * the activity holding it is still alive, which it is at that moment.
 */
class FileEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        val repository = SettingsRepository(applicationContext)
        setContent {
            val stored = repository.settings
                .collectAsStateWithLifecycle(null as KeyboardSettings?)
            val deviceForm = DeviceForm.of(LocalConfiguration.current.smallestScreenWidthDp)
            val settings = rememberLiveSettings(stored, deviceForm) ?: return@setContent
            AppTheme(settings) {
                FileEditorScreen(uri, settings) { finish() }
            }
        }
    }

    companion object {
        /**
         * Opens [uri] in the editor, handing on the access this process was
         * actually given — and only that.
         *
         * A grant cannot be passed on unless it is held. Carrying the write
         * flag over a read-only grant is not a flag quietly ignored: the
         * activity manager throws `SecurityException` out of `startActivity`,
         * which killed the app on the way to the editor for every file opened
         * from a file manager that shares read-only. Google's Files does.
         *
         * Each flag is therefore asked about first. Whether Save can really
         * write is still found out by trying — a provider may answer this check
         * and refuse the write anyway.
         */
        fun start(context: Context, uri: Uri) {
            var flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (context.holdsUriAccess(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
                flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            if (context.holdsUriAccess(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            val intent = Intent(context, FileEditorActivity::class.java).setData(uri)
            runCatching { context.startActivity(Intent(intent).addFlags(flags)) }
                .onFailure {
                    // The check above is the same one the activity manager
                    // makes, so this is the provider changing its mind between
                    // the two calls. The editor opens without the grant and
                    // says it cannot read the file, which beats a crash.
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
        }

        /** Whether this process may exercise [flag] on [uri]. */
        private fun Context.holdsUriAccess(uri: Uri, flag: Int): Boolean =
            checkUriPermission(uri, Process.myPid(), Process.myUid(), flag) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/** What reading the file came to. */
private sealed interface FileText {
    data class Loaded(val text: String) : FileText
    data object Failed : FileText
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditorScreen(uri: Uri, settings: LiveSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val name = remember(uri) { WMFileTypes.displayName(context, uri) }
    // Read here rather than carried from the import dialog: a file may be two
    // million characters, and an intent extra that size is a dead process.
    val loaded by produceState<FileText?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.readTextCapped(uri)
            }.getOrNull()?.let(FileText::Loaded) ?: FileText.Failed
        }
    }

    when (val state = loaded) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        FileText.Failed -> AlertDialog(
            onDismissRequest = onBack,
            text = { Text(stringResource(R.string.file_editor_read_error)) },
            confirmButton = {
                TextButton(onClick = onBack) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )

        is FileText.Loaded -> LoadedFileEditor(uri, name, state.text, settings, onBack)
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadedFileEditor(
    uri: Uri,
    name: String,
    initialText: String,
    settings: LiveSettings,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // JSON by the file's own contents, not by its name: an `application/json`
    // that holds a shopping list is text, and a `.txt` holding an object is
    // JSON. The name only breaks a tie on an empty file.
    val isJson = remember(initialText, name) {
        looksLikeJson(initialText) || name.endsWith(".json", ignoreCase = true)
    }
    val language: CodeLanguage = if (isJson) JsonCode else PlainCode
    val editor = rememberCodeEditorState(uri.toString(), keepHistory = true) { initialText }
    val colors = rememberCodeColors()

    var savedText by rememberSaveable(uri.toString()) { mutableStateOf(initialText) }
    val dirty = editor.text != savedText
    var menuOpen by remember { mutableStateOf(false) }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var lineOpen by rememberSaveable { mutableStateOf(false) }
    var keysOpen by rememberSaveable { mutableStateOf(false) }
    var leaveOpen by remember { mutableStateOf(false) }
    var wrap by rememberCodeWrap(CodeFontIsFallback)
    var textSize by rememberCodeTextSize(TEXT_SIZE)
    var suggestRequests by remember { mutableIntStateOf(0) }
    val find = remember { CodeFindState() }
    val keyPrefs = remember { CodeKeyPrefs(context, JSON_KEYS_FILE) }
    var showKeys by remember { mutableStateOf(keyPrefs.showRow) }
    var keyOrder by remember { mutableStateOf(keyPrefs.order) }
    var hiddenKeys by remember { mutableStateOf(keyPrefs.hidden) }
    val suggestionBar = remember { CodeSuggestionBar() }
    val reduceMotion = settings.watch { it.reduceMotion }

    val text = editor.text
    val lineStarts = remember(text) { lineStartOffsets(text) }
    val caret = editor.value.selection.end.coerceIn(0, text.length)
    val caretLine = lineOf(lineStarts, caret)
    val problem = remember(text, language) { language.problem(text) }
    val matches by produceState(emptyList<TextRange>(), text, find.query, find.options, findOpen) {
        if (!findOpen || find.query.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        delay(FIND_PAUSE_MS)
        value = withContext(Dispatchers.Default) { findMatches(text, find.query, find.options) }
    }
    val decorations = remember(matches, find.active) {
        if (matches.isEmpty()) {
            CodeDecorations.None
        } else {
            CodeDecorations(matches = matches, activeMatch = find.active.coerceIn(0, matches.lastIndex))
        }
    }

    val savedMessage = stringResource(R.string.file_editor_saved)
    val saveFailedMessage = stringResource(R.string.file_editor_save_failed)
    val copySavedMessage = stringResource(R.string.file_editor_copy_saved)
    val invalidJsonMessage = stringResource(R.string.file_editor_invalid_json)

    // Save a copy, for a file the app was only allowed to read. The type is the
    // one the file arrived as, so the picker suggests the same kind of name.
    val copyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(if (isJson) "application/json" else "text/plain"),
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        val body = editor.text
        scope.launch {
            val written = withContext(Dispatchers.IO) { writeText(context, target, body) }
            if (written) {
                savedText = body
                snackbar.showSnackbar(copySavedMessage)
            } else {
                snackbar.showSnackbar(saveFailedMessage)
            }
        }
    }

    val save: () -> Unit = {
        val body = editor.text
        scope.launch {
            val written = withContext(Dispatchers.IO) { writeText(context, uri, body) }
            if (written) {
                savedText = body
                snackbar.showSnackbar(savedMessage)
            } else {
                // An app that shared the file for reading only lands here, and
                // there is nothing to do about it but write somewhere else.
                snackbar.showSnackbar(saveFailedMessage)
                copyLauncher.launch(name)
            }
        }
    }

    val format: () -> Unit = {
        val formatted = language.format(editor.text)
        if (formatted != null) {
            editor.replace(formatted)
        } else {
            scope.launch { snackbar.showSnackbar(invalidJsonMessage) }
        }
    }

    val leave: () -> Unit = { if (dirty) leaveOpen = true else onBack() }

    val keyboard = hardwareKeyboardAttached()
    val editorFocus = remember { FocusRequester() }
    var findFocusRequests by remember { mutableIntStateOf(0) }
    val openFind: (Boolean) -> Unit = { replace ->
        val selection = editor.value.selection
        val selected = editor.text.substring(selection.min, selection.max)
        if (selected.isNotEmpty() && '\n' !in selected) find.query = selected
        if (replace) find.replacing = true
        find.active = firstMatchFrom(findMatches(editor.text, find.query, find.options), selection.min)
        findOpen = true
        findFocusRequests++
    }
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
            CodeCommand.FORMAT -> format()
            CodeCommand.TOGGLE_WRAP -> wrap = !wrap
            CodeCommand.ZOOM_IN -> textSize = (textSize + 1).coerceAtMost(MAX_TEXT)
            CodeCommand.ZOOM_OUT -> textSize = (textSize - 1).coerceAtLeast(MIN_TEXT)
            CodeCommand.ZOOM_RESET -> textSize = TEXT_SIZE
            CodeCommand.SAVE -> save()
            CodeCommand.SHOW_COMMANDS -> menuOpen = true
            else -> return@command false
        }
        true
    }

    BackHandler(enabled = findOpen || dirty) { if (findOpen) findOpen = false else leaveOpen = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(CommonR.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { editor.undo() }, enabled = editor.canUndo) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Undo,
                            contentDescription = stringResource(CommonR.string.common_undo),
                        )
                    }
                    IconButton(onClick = { editor.redo() }, enabled = editor.canRedo) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Redo,
                            contentDescription = stringResource(R.string.code_redo_desc),
                        )
                    }
                    TextButton(onClick = save, enabled = dirty) {
                        Text(stringResource(R.string.file_editor_save_action))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.plugin_ide_more_desc),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            EditorMenuItem(
                                R.string.file_editor_save_copy_action,
                                Icons.Outlined.SaveAs,
                            ) {
                                menuOpen = false
                                copyLauncher.launch(name)
                            }
                            EditorMenuItem(
                                R.string.plugin_ide_find_action,
                                Icons.Outlined.FindReplace,
                                shortcut = shortcutSlot(keyboard, CodeCommand.FIND),
                            ) {
                                menuOpen = false
                                openFind(false)
                            }
                            EditorMenuItem(
                                R.string.plugin_ide_go_to_line_action,
                                Icons.Outlined.FormatListNumbered,
                                shortcut = shortcutSlot(keyboard, CodeCommand.GO_TO_LINE),
                            ) {
                                menuOpen = false
                                lineOpen = true
                            }
                            if (isJson) {
                                EditorMenuItem(
                                    R.string.plugin_ide_format_action,
                                    Icons.Outlined.AutoFixHigh,
                                    shortcut = shortcutSlot(keyboard, CodeCommand.FORMAT),
                                ) {
                                    menuOpen = false
                                    format()
                                }
                            }
                            val wrapLabel = if (wrap) R.string.code_wrap_off_desc else R.string.code_wrap_on_desc
                            EditorMenuItem(
                                wrapLabel,
                                Icons.AutoMirrored.Outlined.WrapText,
                                shortcut = shortcutSlot(keyboard, CodeCommand.TOGGLE_WRAP),
                            ) {
                                menuOpen = false
                                wrap = !wrap
                            }
                            if (isJson) {
                                EditorMenuItem(
                                    R.string.plugin_ide_fold_all_action,
                                    Icons.Outlined.UnfoldLess,
                                    shortcut = shortcutSlot(keyboard, CodeCommand.FOLD_ALL),
                                ) {
                                    menuOpen = false
                                    editor.foldAll(language.foldRegions(editor.text).map { it.min })
                                }
                                EditorMenuItem(
                                    R.string.plugin_ide_unfold_all_action,
                                    Icons.Outlined.UnfoldMore,
                                    enabled = editor.foldStarts.isNotEmpty(),
                                    shortcut = shortcutSlot(keyboard, CodeCommand.UNFOLD_ALL),
                                ) {
                                    menuOpen = false
                                    editor.unfoldAll()
                                }
                            }
                            EditorMenuItem(
                                R.string.plugin_ide_text_larger_action,
                                Icons.Outlined.TextIncrease,
                                enabled = textSize < MAX_TEXT,
                                shortcut = shortcutSlot(keyboard, CodeCommand.ZOOM_IN),
                            ) {
                                textSize = (textSize + 1).coerceAtMost(MAX_TEXT)
                            }
                            EditorMenuItem(
                                R.string.plugin_ide_text_smaller_action,
                                Icons.Outlined.TextDecrease,
                                enabled = textSize > MIN_TEXT,
                                shortcut = shortcutSlot(keyboard, CodeCommand.ZOOM_OUT),
                            ) {
                                textSize = (textSize - 1).coerceAtLeast(MIN_TEXT)
                            }
                            EditorMenuItem(R.string.plugin_ide_code_keys_action, Icons.Outlined.Keyboard) {
                                menuOpen = false
                                keysOpen = true
                            }
                            EditorMenuItem(CommonR.string.common_copy, Icons.Outlined.ContentCopy) {
                                menuOpen = false
                                copyCode(context, editor.text)
                            }
                            EditorMenuItem(CommonR.string.common_paste, Icons.Outlined.ContentPaste) {
                                menuOpen = false
                                pasteCode(context)?.let(editor::replace)
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
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
                        val current = findMatches(editor.text, find.query, find.options)
                        current.getOrNull(find.active.coerceIn(0, maxOf(0, current.lastIndex)))?.let { match ->
                            editor.applyEdit(replaceMatch(editor.text, match, find.replacement, find.query, find.options))
                        }
                    },
                    onReplaceAll = {
                        val current = findMatches(editor.text, find.query, find.options)
                        replaceAllMatches(editor.text, current, find.replacement, find.query, find.options)
                            ?.let(editor::applyEdit)
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
                language = language,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                colors = colors,
                lineStarts = lineStarts,
                decorations = decorations,
                wrap = wrap,
                fontSize = textSize.sp,
                lineHeight = (textSize * LINE_RATIO).sp,
                folding = isJson,
                suggestionBar = suggestionBar,
                reduceMotion = reduceMotion,
                onCommand = onCommand,
                focusRequester = editorFocus,
            )
            HorizontalDivider()
            FileStatusRow(
                line = caretLine + 1,
                column = caret - lineStarts[caretLine] + 1,
                invalidJson = isJson && problem != null,
                unsaved = dirty,
            )
            if (!keyboard && showKeys && isJson) {
                CodeAccessoryRow(
                    state = editor,
                    colors = colors,
                    lineComment = null,
                    onFind = { findOpen = true },
                    onFormat = format,
                    onSuggest = { suggestRequests++ },
                    blocks = language::foldRegions,
                    keys = remember(keyOrder, hiddenKeys) { arrangeKeys(JsonAccessoryKeys, keyOrder, hiddenKeys) },
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
            keys = JsonAccessoryKeys,
        )
    }
    if (leaveOpen) {
        AlertDialog(
            onDismissRequest = { leaveOpen = false },
            title = { Text(stringResource(R.string.file_editor_discard_title)) },
            text = { Text(stringResource(R.string.file_editor_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    leaveOpen = false
                    onBack()
                }) { Text(stringResource(R.string.file_editor_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { leaveOpen = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

/** Where the caret is, whether the JSON parses, and whether anything is unsaved. */
@Composable
private fun FileStatusRow(line: Int, column: Int, invalidJson: Boolean, unsaved: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.file_editor_position, line, column),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (invalidJson) {
            Text(
                stringResource(R.string.file_editor_json_error),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (unsaved) {
            Text(
                stringResource(R.string.file_editor_unsaved),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditorMenuItem(
    label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    shortcut: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = shortcut,
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * Whether [text] is worth colouring as JSON: the first character that is not
 * whitespace opens an object or an array.
 *
 * A guess from one character rather than a parse, because a file being edited
 * is invalid for most of the time it is open, and the colouring must not flick
 * off the moment a brace is deleted.
 */
private fun looksLikeJson(text: String): Boolean {
    val first = text.firstOrNull { !it.isWhitespace() } ?: return false
    return first == '{' || first == '['
}

/**
 * Writes [body] to [target], truncating what was there.
 *
 * `"wt"` is the mode that truncates, and a provider is free not to support it;
 * the plain `"w"` fallback is the same write for those, and the explicit
 * truncate after it is what stops a shorter document leaving the tail of a
 * longer one behind.
 */
private fun writeText(context: Context, target: Uri, body: String): Boolean = runCatching {
    val bytes = body.toByteArray()
    val resolver = context.contentResolver
    val stream = runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
        ?: resolver.openOutputStream(target, "w")
        ?: return false
    stream.use { out ->
        (out as? java.io.FileOutputStream)?.channel?.truncate(0)
        out.write(bytes)
        out.flush()
    }
    true
}.getOrDefault(false)

private const val TEXT_SIZE = 14
private const val MIN_TEXT = 11
private const val MAX_TEXT = 22
private const val LINE_RATIO = 22f / 14f
private const val FIND_PAUSE_MS = 120L
private const val JSON_KEYS_FILE = "code_keys_json"
