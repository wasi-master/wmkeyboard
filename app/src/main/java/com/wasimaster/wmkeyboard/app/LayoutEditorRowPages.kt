package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.MaxRowPages
import com.wasimaster.wmkeyboard.core.layout.RowPage
import com.wasimaster.wmkeyboard.core.layout.RowPageFill
import com.wasimaster.wmkeyboard.core.tools.BuiltInSymbolSets
import com.wasimaster.wmkeyboard.core.tools.SymbolSet

/**
 * The selected row's extra pages in the layout editor: what each one holds,
 * a button to take it away, and one to add another. A swipe across the row
 * on the keyboard brings them in, in this order (issue #1526's follow-up).
 *
 * A page is one of the fills the keyboard works out as it draws ([RowPageFill])
 * or keys typed here as characters separated by spaces. A page of keys that do
 * more than type is written in the JSON editor.
 */
@Composable
internal fun RowPagesSetting(
    rowIndex: Int,
    pages: List<RowPage>,
    symbolSets: List<SymbolSet>,
    onChange: (List<RowPage>) -> Unit,
) {
    var adding by remember(rowIndex) { mutableStateOf(false) }
    ControlSetting(
        R.string.layout_editor_row_pages_title,
        subtitle = stringResource(R.string.layout_editor_row_pages_subtitle),
    ) {
        pages.forEachIndexed { index, page ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = rowPageSummary(page, symbolSets),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { onChange(pages.filterIndexed { i, _ -> i != index }) }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.layout_editor_row_pages_remove_desc, index + 1),
                    )
                }
            }
        }
        if (pages.size < MaxRowPages) {
            OutlinedButton(onClick = { adding = true }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.layout_editor_row_pages_add))
            }
        }
    }
    if (adding) {
        RowPagePickerDialog(
            rowIndex = rowIndex,
            symbolSets = symbolSets,
            onDismiss = { adding = false },
        ) { page ->
            adding = false
            onChange(pages + page)
        }
    }
}

/** What a page holds, in one line. */
@Composable
private fun rowPageSummary(page: RowPage, symbolSets: List<SymbolSet>): String = when (page.fill) {
    RowPageFill.EMOJI -> stringResource(R.string.layout_editor_row_page_emoji)
    RowPageFill.DIGITS -> stringResource(R.string.layout_editor_row_page_digits)
    RowPageFill.SYMBOLS -> {
        val set = symbolSets.firstOrNull { it.id == (page.symbolSet ?: BuiltInSymbolSets.MATH_ID) }
        stringResource(R.string.layout_editor_row_page_symbols, set?.let { symbolSetName(it) } ?: page.symbolSet.orEmpty())
    }
    null -> page.keys.joinToString(" ") { it.label }
}

@Composable
private fun symbolSetName(set: SymbolSet): String =
    BuiltInSymbolSets.nameRes(set)?.let { stringResource(it) } ?: set.name

/** The kinds of page to add, then for typed keys a field to type them in. */
@Composable
private fun RowPagePickerDialog(
    rowIndex: Int,
    symbolSets: List<SymbolSet>,
    onDismiss: () -> Unit,
    onPick: (RowPage) -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_row_pages_picker_title, rowIndex + 1)) },
        text = {
            if (typing) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.layout_editor_row_page_keys_hint)) },
                    singleLine = true,
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PickerOption(stringResource(R.string.layout_editor_row_page_emoji)) {
                        onPick(RowPage(fill = RowPageFill.EMOJI))
                    }
                    PickerOption(stringResource(R.string.layout_editor_row_page_digits)) {
                        onPick(RowPage(fill = RowPageFill.DIGITS))
                    }
                    for (set in symbolSets) {
                        PickerOption(stringResource(R.string.layout_editor_row_page_symbols, symbolSetName(set))) {
                            onPick(RowPage(fill = RowPageFill.SYMBOLS, symbolSet = set.id))
                        }
                    }
                    PickerOption(stringResource(R.string.layout_editor_row_page_keys)) { typing = true }
                }
            }
        },
        confirmButton = {
            if (typing) {
                val keys = rowPageKeysFrom(typed)
                TextButton(enabled = keys.isNotEmpty(), onClick = { onPick(RowPage(keys = keys)) }) {
                    Text(stringResource(CommonR.string.common_add))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
private fun PickerOption(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** One key per character, or run of characters, between the spaces of [text]. */
internal fun rowPageKeysFrom(text: String): List<Key> =
    text.split(' ', '\t', '\n').filter { it.isNotEmpty() }.distinct().take(MaxRowPageTypedKeys).map { Key(label = it) }

private const val MaxRowPageTypedKeys = 14
