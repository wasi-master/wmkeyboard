package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.plugins.PluginWorkspace

/**
 * The draft's files, one chip each: the entry script first, then its other
 * modules, assets and translations, as a `.wmplugin` holds them. Shown only
 * when the draft has files besides the entry, so a one-file plugin keeps every
 * line of the screen for its code.
 */
@Composable
internal fun DraftFileStrip(
    entry: String,
    files: List<String>,
    open: String,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = open == PluginIdeState.ENTRY,
            onClick = { onOpen(PluginIdeState.ENTRY) },
            label = { Text(entry) },
        )
        for (path in files) {
            val picture = !PluginWorkspace.isTextFile(path)
            FilterChip(
                selected = open == path,
                onClick = { onOpen(path) },
                label = { Text(path) },
                leadingIcon = if (picture) {
                    { Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
            )
        }
        AssistChip(
            onClick = onAdd,
            label = { Text(stringResource(R.string.plugin_ide_files_new_action)) },
            leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    }
}

/** Asks for a file name, for a new file or a rename. [error] is shown under the field and keeps the dialog open. */
@Composable
internal fun DraftFileNameDialog(
    title: String,
    initial: String,
    confirm: String,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_FILE_NAME) },
                    label = { Text(stringResource(R.string.plugin_ide_files_name_hint)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: stringResource(R.string.plugin_ide_files_name_help)) },
                )
            }
        },
        confirmButton = { TextButton({ onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

/** Four path parts of 64 characters, which is as long as [PluginWorkspace.isFilePath] lets a name be. */
private const val MAX_FILE_NAME = 4 * 65

/**
 * Text with nothing to colour, check or tidy: a `.txt` or `.svg` asset. The
 * editor still numbers its lines, finds and replaces in it, and saves it.
 */
internal object PlainTextCode : CodeLanguage {
    override fun highlight(source: String, colors: CodeColors): AnnotatedString = AnnotatedString(source)

    override fun format(source: String): String? = null

    override fun problem(source: String): CodeProblem? = null

    override fun brackets(source: String): List<Int> = emptyList()

    override fun matchingBracket(source: String, brackets: List<Int>, caret: Int): Pair<Int, Int>? = null
}

/** The language the editor reads [path] in: Python for the entry and modules, JSON for translations and JSON assets. */
internal fun draftFileLanguage(path: String): CodeLanguage = when {
    path == PluginIdeState.ENTRY || path.endsWith(".py") -> PythonCode
    path.endsWith(".json") -> JsonCode
    else -> PlainTextCode
}
