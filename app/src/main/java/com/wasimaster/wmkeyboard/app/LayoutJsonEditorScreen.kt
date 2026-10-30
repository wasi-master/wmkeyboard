package com.wasimaster.wmkeyboard.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextDecrease
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextIncrease
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.layout.LayoutMessage
import com.wasimaster.wmkeyboard.core.layout.json.JsonDocEntry
import com.wasimaster.wmkeyboard.core.layout.json.LayoutJsonRoot
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The keys a JSON author reaches for that a phone keyboard keeps behind a layer
 * switch: the quote, the colon and comma, both kinds of bracket, and the three
 * JSON words. The colon types a space after itself, the way the printer writes
 * one, so the list of values can open straight away.
 */
internal val JsonAccessoryKeys: List<AccessoryKey> = listOf(
    AccessoryKey("\"", "\""),
    AccessoryKey(":", ": "),
    AccessoryKey(",", ","),
    AccessoryKey("{", "{"),
    AccessoryKey("}", "}"),
    AccessoryKey("[", "["),
    AccessoryKey("]", "]"),
    AccessoryKey("true", "true"),
    AccessoryKey("false", "false"),
    AccessoryKey("null", "null"),
    AccessoryKey("-", "-"),
    AccessoryKey(".", "."),
    AccessoryKey("_", "_"),
    AccessoryKey("\\", "\\"),
)

/** What pressing Apply did. */
internal sealed interface JsonApplyOutcome {
    /** The text is not a layout the decoder can read; nothing was saved. */
    data object Invalid : JsonApplyOutcome

    /** Saved, after the repair pass made the changes [notes] lists. */
    data class Applied(val notes: List<LayoutMessage>) : JsonApplyOutcome
}

private const val JSON_KEYS_FILE = "code_keys_json"
private const val TEXT_SIZE = 14
private const val MIN_TEXT = 11
private const val MAX_TEXT = 22
private const val LINE_RATIO = 22f / 14f
private const val PROBLEMS_FRACTION = 0.42f
private const val FIND_PAUSE_MS = 120L
private const val CHECK_PAUSE_MS = 250L

/**
 * A layout or a panel layout as JSON, in an editor with the whole window: the
 * suggestions, checks, doc strip, find, folds and code keys of the plugin
 * editor, over the schema of the layout format instead of a Lua API.
 *
 * Its own Scaffold rather than a settings page, like the plugin editor, because
 * the code needs the height. The draft lives only on this screen, as it always
 * has: Apply saves it, through the repair pass, and Back leaves it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayoutJsonEditorScreen(
    title: String,
    documentKey: String,
    root: LayoutJsonRoot,
    settings: LiveSettings,
    initialText: () -> String,
    onApply: suspend (String) -> JsonApplyOutcome,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // The value hints read these three fields and nothing else. Selected on
    // their own, a write to any other setting neither rebuilds the hints nor
    // recomposes the editor.
    val hintSource = settings.watch {
        SettingsDefaults.copy(
            customLayouts = it.customLayouts,
            customThemes = it.customThemes,
            customFontName = it.customFontName,
        )
    }
    val values = remember(hintSource) { AppJsonValues(context, hintSource) }
    val language = remember(root, values) { LayoutJsonLanguage(root, values) }
    val editor = rememberCodeEditorState(documentKey, keepHistory = true) { initialText() }
    val colors = rememberCodeColors()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var problemsOpen by rememberSaveable { mutableStateOf(false) }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    val find = remember { CodeFindState() }
    var lineOpen by rememberSaveable { mutableStateOf(false) }
    var wrap by rememberCodeWrap(CodeFontIsFallback)
    var textSize by rememberCodeTextSize(TEXT_SIZE)
    var suggestRequests by remember { mutableIntStateOf(0) }
    val keyPrefs = remember { CodeKeyPrefs(context, JSON_KEYS_FILE) }
    var showKeys by remember { mutableStateOf(keyPrefs.showRow) }
    var keyOrder by remember { mutableStateOf(keyPrefs.order) }
    var hiddenKeys by remember { mutableStateOf(keyPrefs.hidden) }
    var keysOpen by rememberSaveable { mutableStateOf(false) }
    val suggestionBar = remember { CodeSuggestionBar() }
    var notes by remember { mutableStateOf<List<LayoutMessage>?>(null) }
    var applying by remember { mutableStateOf(false) }
    val invalidMessage = stringResource(R.string.layout_editor_json_invalid_error)

    val text = editor.text
    val lineStarts = remember(text) { lineStartOffsets(text) }
    val caret = editor.value.selection.end.coerceIn(0, text.length)
    val caretLine = lineOf(lineStarts, caret)
    val doc by produceState<JsonDocEntry?>(null, text, caret, language) {
        value = withContext(Dispatchers.Default) { language.docAt(text, caret) }
    }
    val diagnostics by produceState(emptyList<CodeDiagnostic>(), text, language) {
        delay(CHECK_PAUSE_MS)
        value = withContext(Dispatchers.Default) { language.diagnostics(text) }
    }
    val matches by produceState(emptyList<TextRange>(), text, find.query, find.options, findOpen) {
        if (!findOpen || find.query.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        delay(FIND_PAUSE_MS)
        value = withContext(Dispatchers.Default) { findMatches(text, find.query, find.options) }
    }
    val decorations = remember(diagnostics, lineStarts, matches, find.active) {
        val marks = HashMap<Int, CodeSeverity>()
        for (diagnostic in diagnostics) {
            val line = lineOf(lineStarts, diagnostic.range.min.coerceIn(0, lineStarts.last()))
            val held = marks[line]
            if (held == null || diagnostic.severity < held) marks[line] = diagnostic.severity
        }
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
    val format: () -> Unit = {
        val formatted = language.format(editor.text)
        if (formatted != null) {
            editor.replace(formatted)
        } else {
            scope.launch { snackbar.showSnackbar(invalidMessage) }
        }
    }
    val apply: () -> Unit = {
        if (!applying) {
            applying = true
            scope.launch {
                val outcome = onApply(editor.text)
                applying = false
                when (outcome) {
                    JsonApplyOutcome.Invalid -> {
                        // Straight to the first thing that stops it, with the list open beside it.
                        diagnostics.firstOrNull { it.severity == CodeSeverity.ERROR }?.let { editor.select(it.range) }
                        problemsOpen = true
                        snackbar.showSnackbar(invalidMessage)
                    }
                    is JsonApplyOutcome.Applied -> if (outcome.notes.isEmpty()) onBack() else notes = outcome.notes
                }
            }
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
            CodeCommand.FORMAT -> format()
            CodeCommand.TOGGLE_WRAP -> wrap = !wrap
            CodeCommand.ZOOM_IN -> textSize = (textSize + 1).coerceAtMost(MAX_TEXT)
            CodeCommand.ZOOM_OUT -> textSize = (textSize - 1).coerceAtLeast(MIN_TEXT)
            CodeCommand.ZOOM_RESET -> textSize = TEXT_SIZE
            // Apply is how this screen saves.
            CodeCommand.SAVE -> if (editor.text.isNotBlank()) apply()
            CodeCommand.NEXT_PROBLEM, CodeCommand.PREVIOUS_PROBLEM ->
                nextProblem(diagnostics.map { it.range }, editor.value.selection.min, command == CodeCommand.NEXT_PROBLEM)?.let(editor::select)
            CodeCommand.SHOW_PROBLEMS -> problemsOpen = !problemsOpen
            CodeCommand.SHOW_COMMANDS -> menuOpen = true
            else -> return@command false
        }
        true
    }
    // Back closes the find bar before it leaves the screen.
    BackHandler(enabled = findOpen) { findOpen = false }
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
                    TextButton(onClick = apply, enabled = text.isNotBlank() && !applying) {
                        Text(stringResource(R.string.layout_editor_apply_action))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.plugin_ide_more_desc))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            MenuItem(R.string.plugin_ide_find_action, Icons.Outlined.FindReplace, shortcut = shortcutSlot(keyboard, CodeCommand.FIND)) {
                                menuOpen = false
                                openFind(false)
                            }
                            MenuItem(R.string.plugin_ide_go_to_line_action, Icons.Outlined.FormatListNumbered, shortcut = shortcutSlot(keyboard, CodeCommand.GO_TO_LINE)) {
                                menuOpen = false
                                lineOpen = true
                            }
                            MenuItem(R.string.plugin_ide_format_action, Icons.Outlined.AutoFixHigh, shortcut = shortcutSlot(keyboard, CodeCommand.FORMAT)) {
                                menuOpen = false
                                format()
                            }
                            val wrapLabel = if (wrap) R.string.code_wrap_off_desc else R.string.code_wrap_on_desc
                            MenuItem(wrapLabel, Icons.AutoMirrored.Outlined.WrapText, shortcut = shortcutSlot(keyboard, CodeCommand.TOGGLE_WRAP)) {
                                menuOpen = false
                                wrap = !wrap
                            }
                            MenuItem(R.string.plugin_ide_fold_all_action, Icons.Outlined.UnfoldLess, shortcut = shortcutSlot(keyboard, CodeCommand.FOLD_ALL)) {
                                menuOpen = false
                                editor.foldAll(language.foldRegions(editor.text).map { it.min })
                            }
                            MenuItem(
                                R.string.plugin_ide_unfold_all_action,
                                Icons.Outlined.UnfoldMore,
                                enabled = editor.foldStarts.isNotEmpty(),
                                shortcut = shortcutSlot(keyboard, CodeCommand.UNFOLD_ALL),
                            ) {
                                menuOpen = false
                                editor.unfoldAll()
                            }
                            MenuItem(R.string.plugin_ide_text_larger_action, Icons.Outlined.TextIncrease, enabled = textSize < MAX_TEXT, shortcut = shortcutSlot(keyboard, CodeCommand.ZOOM_IN)) {
                                textSize = (textSize + 1).coerceAtMost(MAX_TEXT)
                            }
                            MenuItem(R.string.plugin_ide_text_smaller_action, Icons.Outlined.TextDecrease, enabled = textSize > MIN_TEXT, shortcut = shortcutSlot(keyboard, CodeCommand.ZOOM_OUT)) {
                                textSize = (textSize - 1).coerceAtLeast(MIN_TEXT)
                            }
                            MenuItem(R.string.plugin_ide_code_keys_action, Icons.Outlined.Keyboard) {
                                menuOpen = false
                                keysOpen = true
                            }
                            MenuItem(CommonR.string.common_copy, Icons.Outlined.ContentCopy) {
                                menuOpen = false
                                copyCode(context, editor.text)
                            }
                            MenuItem(CommonR.string.common_paste, Icons.Outlined.ContentPaste) {
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
        val reduceMotion = settings.watch { it.reduceMotion }
        // The window no longer resizes for the keyboard on Android 15, so the content
        // stops at the top of the keyboard itself; the plugin editor explains why.
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
                language = language,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                colors = colors,
                lineStarts = lineStarts,
                decorations = decorations,
                wrap = wrap,
                fontSize = textSize.sp,
                lineHeight = (textSize * LINE_RATIO).sp,
                completions = true,
                suggestRequests = suggestRequests,
                onGutterPress = { line ->
                    problemsOpen = true
                    editor.moveTo(offsetOfLine(editor.text, line))
                },
                folding = true,
                suggestionBar = suggestionBar,
                reduceMotion = reduceMotion,
                onCommand = onCommand,
                focusRequester = editorFocus,
            )
            doc?.let { CodeDocStrip(it.path, it.signature, it.body, colors) }
            JsonStatusRow(
                problems = diagnostics.size,
                open = problemsOpen,
                line = caretLine,
                column = caret - lineStarts[caretLine],
                onToggle = { problemsOpen = !problemsOpen },
            )
            if (problemsOpen) {
                HorizontalDivider()
                Box(Modifier.fillMaxWidth().fillMaxHeight(PROBLEMS_FRACTION)) {
                    ProblemsPane(diagnostics, lineStarts) { range -> editor.select(range) }
                }
            }
            // At the bottom, so it sits on the soft keyboard. A hardware keyboard has its own keys.
            if (!keyboard && showKeys) {
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
    notes?.let { list ->
        val done = {
            notes = null
            onBack()
        }
        AlertDialog(
            onDismissRequest = done,
            title = { Text(stringResource(R.string.layout_editor_json_applied_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (note in list) {
                        Text(
                            stringResource(R.string.layout_editor_repair_note, note.format(context.resources)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = done) { Text(stringResource(CommonR.string.common_ok)) } },
        )
    }
}

@Composable
private fun MenuItem(
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

/** The Problems toggle with its count, and where the caret is. */
@Composable
private fun JsonStatusRow(problems: Int, open: Boolean, line: Int, column: Int, onToggle: () -> Unit) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = open,
            onClick = onToggle,
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
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.code_position_label, line + 1, column + 1),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The editor's frame around a message, for a layout that no longer exists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MissingJsonDocument(title: String, message: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(CommonR.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Text(
            message,
            modifier = Modifier.padding(padding).padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
