package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.LearnFromTextSort
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LearnFromTextUi
import com.wasimaster.wmkeyboard.ime.LearnRow
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/** What the Learn from text panel hands back (#174); one bundle, see [ToolHoldCallbacks]. */
data class LearnFromTextCallbacks(
    val onToggle: (String) -> Unit = {},
    val onToggleAll: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onPairs: (Boolean) -> Unit = {},
    val onSort: (LearnFromTextSort) -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onEditDone: () -> Unit = {},
    val onEditCancel: () -> Unit = {},
    val onIgnore: (String) -> Unit = {},
    val onNeverSuggest: (String) -> Unit = {},
    val onShow: (String) -> Unit = {},
)

/** The panel's height while its spelling editor shares the window with the key rows. */
private val LearnEditHeight = 132.dp

/**
 * Learn from text (#174): the words in the field, or the selection, that the
 * keyboard does not know yet.
 *
 * Every row starts checked; Add puts the checked words in the personal
 * dictionary and, with Word pairs on, the pairs the text holds between known
 * words. A row's ⋮ opens its own actions under it: Edit the spelling before
 * adding, Show the word in the field, Never suggest it, or Ignore it for this
 * scan. The service reads the text and filters it; nothing here touches the field.
 */
@Composable
internal fun LearnFromTextPanel(
    state: KeyboardUiState,
    callbacks: LearnFromTextCallbacks,
    onClose: () -> Unit,
) {
    val ui = state.learnFromText ?: return
    val sort = state.settings.suggestionStrip.learnFromTextSort
    val pairsOn = state.settings.suggestionStrip.learnFromTextPairs
    val editing = ui.editing != null
    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    val focusedRow = state.focusedIndex(FocusRegion.RESULTS)
    val hasRows = ui.rows.isNotEmpty() && !editing
    val canLearn = !ui.scanning && !ui.blocked && !editing

    PanelFocusTarget(
        panel = PanelMode.LEARN_FROM_TEXT,
        region = FocusRegion.CHIPS,
        count = if (canLearn) (if (hasRows) 4 else 2) else 0,
        columns = if (hasRows) 4 else 2,
    ) { index ->
        if (hasRows) {
            when (index) {
                0 -> callbacks.onToggleAll()
                1 -> callbacks.onSort(nextSort(sort))
                2 -> callbacks.onPairs(!pairsOn)
                else -> callbacks.onAdd()
            }
        } else {
            when (index) {
                0 -> callbacks.onPairs(!pairsOn)
                else -> callbacks.onAdd()
            }
        }
    }
    PanelFocusTarget(
        panel = PanelMode.LEARN_FROM_TEXT,
        region = FocusRegion.RESULTS,
        count = if (editing) 0 else ui.rows.size,
        columns = 1,
    ) { index -> ui.rows.getOrNull(index)?.let { callbacks.onToggle(it.key) } }

    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        compact = editing,
        compactHeight = LearnEditHeight,
        headerActions = {
            if (editing) {
                EditHeader(ui, callbacks)
            } else {
                ListHeader(ui, sort, pairsOn, focusedChip, callbacks)
            }
        },
    ) {
        if (editing) {
            EditBody(ui)
        } else {
            ListBody(ui, focusedRow, callbacks)
        }
    }
}

@Composable
private fun RowScope.ListHeader(
    ui: LearnFromTextUi,
    sort: LearnFromTextSort,
    pairsOn: Boolean,
    focusedChip: Int?,
    callbacks: LearnFromTextCallbacks,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(2.dp))
        Text(
            if (ui.scanning) {
                stringResource(R.string.ime_tool_learn_from_text)
            } else {
                pluralStringResource(R.plurals.ime_learn_found_count, ui.rows.size, ui.rows.size)
            },
            color = kb.secondaryText,
            fontSize = 12.sp,
            maxLines = 1,
        )
        if (!ui.scanning && !ui.blocked) {
            if (ui.rows.isNotEmpty()) {
                val allChecked = ui.rows.all { it.checked }
                ToolPanelChip(
                    stringResource(if (allChecked) R.string.ime_learn_select_none_action else R.string.ime_learn_select_all_action),
                    modifier = Modifier.focusRing(focusedChip == 0, kb.chipShape()),
                ) { callbacks.onToggleAll() }
                val sortDescription = stringResource(R.string.ime_learn_sort_desc)
                ToolPanelChip(
                    stringResource(sortLabel(sort)),
                    modifier = Modifier
                        .focusRing(focusedChip == 1, kb.chipShape())
                        .semantics { contentDescription = sortDescription },
                ) { callbacks.onSort(nextSort(sort)) }
            }
            val pairsDescription = stringResource(R.string.ime_learn_pairs_desc)
            ToolPanelChip(
                stringResource(R.string.ime_learn_pairs_label),
                selected = pairsOn,
                modifier = Modifier
                    .focusRing(if (ui.rows.isNotEmpty()) focusedChip == 2 else focusedChip == 0, kb.chipShape())
                    .semantics { contentDescription = pairsDescription },
            ) { callbacks.onPairs(!pairsOn) }
            val addLabel = if (ui.checkedCount > 0) {
                stringResource(R.string.ime_learn_add_action, ui.checkedCount)
            } else {
                stringResource(R.string.ime_learn_text_action)
            }
            ToolPanelChip(
                addLabel,
                selected = true,
                enabled = true,
                modifier = Modifier.focusRing(if (ui.rows.isNotEmpty()) focusedChip == 3 else focusedChip == 1, kb.chipShape()),
            ) { callbacks.onAdd() }
        }
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun RowScope.EditHeader(ui: LearnFromTextUi, callbacks: LearnFromTextCallbacks) {
    Spacer(Modifier.weight(1f))
    ToolPanelChip(stringResource(com.wasimaster.wmkeyboard.common.R.string.common_cancel)) { callbacks.onEditCancel() }
    Spacer(Modifier.width(6.dp))
    ToolPanelChip(
        stringResource(R.string.ime_learn_edit_done_action),
        selected = true,
        enabled = ui.editValid,
    ) { callbacks.onEditDone() }
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun EditBody(ui: LearnFromTextUi) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(shape)
            .background(kb.chip)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchQueryText(
            query = ui.editText,
            placeholder = stringResource(R.string.ime_learn_edit_hint),
            active = true,
            textColor = kb.chipText,
            placeholderColor = kb.secondaryText,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ListBody(ui: LearnFromTextUi, focusedRow: Int?, callbacks: LearnFromTextCallbacks) {
    val kb = LocalKbTheme.current
    val note = when {
        ui.blocked -> stringResource(R.string.ime_learn_blocked_info)
        ui.result != null -> resultLine(ui)
        ui.partial -> stringResource(R.string.ime_learn_partial_info)
        ui.capped -> stringResource(R.string.ime_learn_capped_info, ui.rows.size)
        else -> null
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        if (note != null) {
            Text(
                note,
                color = kb.secondaryText,
                fontSize = 12.sp,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        when {
            ui.scanning -> Centered {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = kb.accent)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ime_learn_scanning_progress), color = kb.secondaryText, fontSize = 13.sp)
            }
            ui.blocked -> {}
            ui.rows.isEmpty() -> Centered {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = kb.accent)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ime_learn_empty), color = kb.secondaryText, fontSize = 13.sp)
            }
            else -> {
                var expanded by remember { mutableStateOf<String?>(null) }
                val listState = rememberLazyListState()
                ScrollFocusIntoView(focusedRow) { listState.animateScrollToItem(it) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(ui.rows, key = { _, row -> row.key }) { index, row ->
                        LearnWordRow(
                            row = row,
                            focused = index == focusedRow,
                            open = expanded == row.key,
                            canShow = ui.origin != null,
                            callbacks = callbacks,
                            onMore = { expanded = if (expanded == row.key) null else row.key },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun resultLine(ui: LearnFromTextUi): String {
    val result = ui.result ?: return ""
    if (result.words == 0 && result.pairs == 0) {
        return stringResource(R.string.ime_learn_result_text_only)
    }
    val words = if (result.words > 0) pluralStringResource(R.plurals.ime_learn_result_words, result.words, result.words) else null
    val pairs = if (result.pairs > 0) pluralStringResource(R.plurals.ime_learn_result_pairs, result.pairs, result.pairs) else null
    return when {
        words != null && pairs != null -> stringResource(R.string.ime_learn_result_both, words, pairs)
        words != null -> words
        pairs != null -> pairs
        else -> stringResource(R.string.ime_learn_result_text_only)
    }
}

@Composable
private fun LearnWordRow(
    row: LearnRow,
    focused: Boolean,
    open: Boolean,
    canShow: Boolean,
    callbacks: LearnFromTextCallbacks,
    onMore: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (open) kb.chip else kb.chip.copy(alpha = kb.chip.alpha * 0.5f))
            .focusRing(focused, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { callbacks.onToggle(row.key) }
                .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckBox(row.checked)
            Spacer(Modifier.width(10.dp))
            Text(
                row.edited?.let { stringResource(R.string.ime_learn_edited_label, row.spelling, it) } ?: row.spelling,
                color = kb.suggestionText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ime_learn_count_label, row.count), color = kb.secondaryText, fontSize = 12.sp, maxLines = 1)
            if (row.seen > 0) {
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ime_learn_seen_label, row.seen), color = kb.secondaryText, fontSize = 11.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onMore)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.ime_learn_more_desc, row.finalSpelling),
                    modifier = Modifier.size(18.dp),
                    tint = kb.toolbarIcon,
                )
            }
        }
        if (open) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 32.dp, end = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolPanelChip(stringResource(R.string.ime_learn_edit_action)) { callbacks.onEdit(row.key) }
                ToolPanelChip(stringResource(R.string.ime_learn_show_action), enabled = canShow) { callbacks.onShow(row.key) }
                ToolPanelChip(stringResource(R.string.ime_learn_never_action)) { callbacks.onNeverSuggest(row.key) }
                ToolPanelChip(stringResource(R.string.ime_learn_ignore_action)) { callbacks.onIgnore(row.key) }
            }
        }
    }
}

/** A small themed checkbox; Material3's ignores the keyboard theme. */
@Composable
private fun CheckBox(checked: Boolean) {
    val kb = LocalKbTheme.current
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) kb.chipActive else kb.chip),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = kb.chipActiveText, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun Centered(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun nextSort(sort: LearnFromTextSort): LearnFromTextSort =
    LearnFromTextSort.entries[(sort.ordinal + 1) % LearnFromTextSort.entries.size]

private fun sortLabel(sort: LearnFromTextSort): Int = when (sort) {
    LearnFromTextSort.MOST_FREQUENT -> R.string.ime_learn_sort_frequent_label
    LearnFromTextSort.TEXT_ORDER -> R.string.ime_learn_sort_text_label
    LearnFromTextSort.ALPHABETICAL -> R.string.ime_learn_sort_alpha_label
}
