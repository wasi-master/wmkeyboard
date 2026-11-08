package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.plugins.PyRenameProblem
import com.wasimaster.wmkeyboard.core.plugins.PythonTooling

/** Asks for a line number and hands it over once it names a line of the document. */
@Composable
internal fun GoToLineDialog(lines: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val line = parseLineNumber(input, lines)
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.code_go_to_line_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                label = { Text(stringResource(R.string.code_go_to_line_label)) },
                isError = input.isNotBlank() && line == null,
                supportingText = {
                    if (input.isNotBlank() && line == null) Text(stringResource(R.string.code_go_to_line_error, lines))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { line?.let(onGo) }),
                modifier = Modifier.focusOncePlaced(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { line?.let(onGo) }, enabled = line != null) {
                Text(stringResource(R.string.code_go_to_line_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** A rename prepared before its dialog opens: the name, every place it is written, and the text they were found in. */
@Immutable
internal data class RenamePlan(val oldName: String, val spans: List<TextRange>, val snapshot: String, val caret: Int)

/** The rename of the name at [caret], or null where the tooling does not offer one. Run off the main thread. */
internal fun renamePlanAt(source: String, caret: Int): RenamePlan? {
    val spans = PythonTooling.rename(source, caret, "")?.spans ?: return null
    val first = spans.firstOrNull() ?: return null
    return RenamePlan(source.substring(first.start, first.end), spans.map { TextRange(it.start, it.end) }, source, caret)
}

/**
 * Asks for the new name, with the name selected so typing replaces it. Under
 * the field it says how many places change, or why the new name cannot be used.
 */
@Composable
internal fun RenameDialog(plan: RenamePlan, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val context = LocalContext.current
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(plan.oldName, TextRange(0, plan.oldName.length)))
    }
    val name = field.text
    val problem = remember(name) {
        if (name == plan.oldName) null else PythonTooling.rename(plan.snapshot, plan.caret, name)?.problem
    }
    val ready = name != plan.oldName && problem == null
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_ide_rename_title, plan.oldName)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                label = { Text(stringResource(R.string.plugin_ide_rename_label)) },
                isError = problem != null,
                supportingText = {
                    Text(
                        problem?.let { stringResource(it.messageRes) }
                            ?: context.resources.getQuantityString(R.plurals.plugin_ide_rename_count, plan.spans.size, plan.spans.size),
                    )
                },
                textStyle = TextStyle(fontFamily = CodeFontFamily),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (ready) onRename(name) }),
                modifier = Modifier.focusOncePlaced(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = ready) { Text(stringResource(R.string.plugin_ide_rename_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@get:StringRes
private val PyRenameProblem.messageRes: Int
    get() = when (this) {
        PyRenameProblem.NOT_A_NAME -> R.string.plugin_ide_rename_not_a_name
        PyRenameProblem.KEYWORD -> R.string.plugin_ide_rename_python_keyword
        PyRenameProblem.BUILTIN -> R.string.plugin_ide_rename_builtin
        PyRenameProblem.TAKEN -> R.string.plugin_ide_rename_taken
        PyRenameProblem.API -> R.string.plugin_ide_rename_api
        PyRenameProblem.HOOK -> R.string.plugin_ide_rename_hook
    }
