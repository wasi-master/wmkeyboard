package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.ui.ScrollRailBox
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState

/** One choice on an [ActionPickerPage]. [key] is stable across openings; [onPick] answers it. */
@Immutable
internal class PickerRow(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val leading: (@Composable () -> Unit)? = null,
    val onPick: () -> Unit,
)

/**
 * A run of [rows] under one heading. A null [title] is a group with no heading
 * that cannot be folded: the answers that are not a tool or an action, such as
 * "Default" or "Does nothing", which belong at the top whatever else is shut.
 *
 * A [searchOnly] group is never listed, only searched: the second answer an
 * entry leads to, such as each tool behind "Open a tool", so a search for
 * "settings shortcut" finds it and picks both at once (#1298).
 */
@Immutable
internal class PickerGroup(
    val key: String,
    val title: String?,
    val rows: List<PickerRow>,
    val searchOnly: Boolean = false,
)

/**
 * What each picker looked like when it was last closed, by the caller's
 * memory key: which groups were folded and where the list was scrolled to.
 * The process's memory only, like the screens' own scroll: a picker opened a
 * second time comes back the way it was left, which a dialog rebuilt from
 * nothing on every opening never did (#665).
 */
private object PickerMemory {
    val folded = HashMap<String, Set<String>>()
    val scroll = HashMap<String, Pair<Int, Int>>()
}

/**
 * A full-screen picker for the long lists of actions: what a tool's press and
 * hold runs, what a key, a flick or a swipe does (#665).
 *
 * These were dialogs a few hundred dp tall, so the one row someone came for
 * sat under minutes of scrolling past groups they had no use for. Here the
 * list has the whole screen, a field searches every row's name and
 * description, each heading folds its group away (and says how many it holds
 * while folded), and the folds and the scroll are kept for the next opening
 * under [memoryKey]. With nothing kept yet, it opens on the current choice.
 */
@Composable
internal fun ActionPickerPage(
    memoryKey: String,
    title: String,
    groups: List<PickerGroup>,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            ActionPickerContent(memoryKey, title, groups, onDismiss)
        }
    }
}

@Composable
private fun ActionPickerContent(
    memoryKey: String,
    title: String,
    groups: List<PickerGroup>,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable(memoryKey) { mutableStateOf("") }
    var folded by remember(memoryKey) { mutableStateOf(PickerMemory.folded[memoryKey].orEmpty()) }
    val list = remember(memoryKey) {
        val saved = PickerMemory.scroll[memoryKey]
        if (saved != null) {
            LazyListState(saved.first, saved.second)
        } else {
            LazyListState(initialRowIndex(groups, folded))
        }
    }
    DisposableEffect(memoryKey, list) {
        onDispose {
            PickerMemory.scroll[memoryKey] = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        }
    }
    val tokens = remember(query) { normalizeForSearch(query).split(' ').filter { it.isNotEmpty() } }
    val searching = tokens.isNotEmpty()
    val matches = remember(tokens, groups) {
        if (!searching) {
            emptyList()
        } else {
            groups.flatMap { it.rows }.filter { row ->
                val haystack = normalizeForSearch(row.title + ' ' + row.subtitle.orEmpty())
                tokens.all { haystack.contains(it) }
            }
        }
    }
    val rail = rememberScrollRailState(list)

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_close))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.action_picker_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_clear))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ScrollRailBox(state = rail, modifier = Modifier.weight(1f)) { rows ->
            LazyColumn(state = list, modifier = rows) {
                if (searching) {
                    if (matches.isEmpty()) {
                        item(key = "empty") {
                            CaptionText(stringResource(R.string.action_picker_search_empty, query))
                        }
                    }
                    items(matches, key = { "match-" + it.key }) { PickerRowView(it) }
                    return@LazyColumn
                }
                for (group in groups) {
                    if (group.searchOnly) continue
                    val title = group.title
                    if (title != null) {
                        val shut = group.key in folded
                        item(key = "head-" + group.key) {
                            PickerGroupHeading(title, group.rows.size, shut) {
                                folded = if (shut) folded - group.key else folded + group.key
                                PickerMemory.folded[memoryKey] = folded
                            }
                        }
                        if (shut) continue
                    }
                    items(group.rows, key = { group.key + "/" + it.key }) { PickerRowView(it) }
                }
            }
        }
    }
}

/** Where a picker with nothing remembered opens: on the current choice, if a group it is in is open. */
private fun initialRowIndex(groups: List<PickerGroup>, folded: Set<String>): Int {
    var index = 0
    for (group in groups) {
        if (group.searchOnly) continue
        if (group.title != null) index++
        if (group.title != null && group.key in folded) continue
        val at = group.rows.indexOfFirst { it.selected }
        // A row above the selection keeps the heading over it in sight.
        if (at >= 0) return (index + at - 1).coerceAtLeast(0)
        index += group.rows.size
    }
    return 0
}

@Composable
private fun PickerGroupHeading(title: String, count: Int, shut: Boolean, onToggle: () -> Unit) {
    val stateDesc = stringResource(if (shut) R.string.action_picker_group_show else R.string.action_picker_group_hide)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stateDesc, onClick = onToggle)
            .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(if (shut) 0f else 180f),
        )
    }
}

@Composable
private fun PickerRowView(row: PickerRow) {
    WmRow(
        title = row.title,
        subtitle = row.subtitle,
        leading = row.leading,
        trailing = { RadioButton(selected = row.selected, onClick = row.onPick) },
        onClick = row.onPick,
    )
}
