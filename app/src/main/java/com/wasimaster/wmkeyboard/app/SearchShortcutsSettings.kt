package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.tools.SearchTarget
import com.wasimaster.wmkeyboard.core.tools.SearchTrigger
import com.wasimaster.wmkeyboard.core.tools.SearchTriggers
import kotlinx.coroutines.launch

/**
 * Typing › Smart chips › Search shortcuts (#1242): "@gif cats" offers a GIF
 * search for cats on the strip. The switch, then one row per shortcut (tap
 * to change it, swipe to delete), then adding one and going back to the
 * shipped set. See [SearchTriggers] for how a shortcut is read.
 */
@Composable
internal fun SearchShortcutsGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val on = settings.watch { it.smartChips.searchTriggers }
    val list = settings.watch { it.smartChips.searchTriggerList }
    // The row being edited: an index into the list, or -1 for a new one.
    var editing by remember { mutableStateOf<Int?>(null) }

    fun save(next: List<SearchTrigger>) {
        scope.launch { repository.setSearchTriggerList(next) }
    }

    SettingsGroup(
        stringResource(R.string.search_shortcuts_group),
        info = stringResource(R.string.search_shortcuts_info),
        level = SettingLevel.STANDARD,
    ) {
        item {
            ToggleSetting(
                R.string.search_shortcuts_title,
                stringResource(R.string.search_shortcuts_subtitle),
                on,
                icon = Icons.Outlined.Search,
                default = SettingsDefaults.smartChips.searchTriggers,
            ) { scope.launch { repository.setSearchTriggers(it) } }
        }
        if (on) {
            list.forEachIndexed { index, trigger ->
                item(key = "trigger-$index-${trigger.prefix}") {
                    val deleteLabel = stringResource(CommonR.string.common_delete)
                    val editLabel = stringResource(CommonR.string.common_edit)
                    WmRow(
                        title = trigger.prefix,
                        subtitle = stringResource(R.string.search_shortcuts_row_subtitle, targetLabel(trigger.target)),
                        swipeActions = listOf(
                            SwipeAction(Icons.Outlined.Edit, editLabel) { editing = index },
                            deleteSwipeAction(deleteLabel) { save(list.filterIndexed { i, _ -> i != index }) },
                        ),
                        onClick = { editing = index },
                    )
                }
            }
            item {
                WmRow(
                    title = stringResource(R.string.search_shortcuts_add_title),
                    icon = Icons.Outlined.Add,
                    highlightKey = R.string.search_shortcuts_add_title,
                    onClick = { editing = -1 },
                )
            }
            if (list != SearchTriggers.DEFAULTS) {
                item {
                    ActionRow(
                        title = R.string.search_shortcuts_reset_title,
                        subtitle = null,
                        action = stringResource(R.string.search_shortcuts_reset_action),
                    ) { scope.launch { repository.resetSearchTriggerList() } }
                }
            }
        }
    }

    editing?.let { index ->
        val existing = list.getOrNull(index)
        TriggerDialog(
            initial = existing,
            taken = list.filterIndexed { i, _ -> i != index }.map { it.prefix.lowercase() }.toSet(),
            onDismiss = { editing = null },
        ) { trigger ->
            editing = null
            save(if (existing == null) list + trigger else list.mapIndexed { i, t -> if (i == index) trigger else t })
        }
    }
}

@Composable
private fun targetLabel(target: SearchTarget): String {
    val tool = target.tool ?: return stringResource(R.string.search_shortcuts_target_contacts)
    return stringResource(toolTitle(tool))
}

@Composable
private fun TriggerDialog(
    initial: SearchTrigger?,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onSave: (SearchTrigger) -> Unit,
) {
    var prefix by remember { mutableStateOf(initial?.prefix.orEmpty()) }
    var target by remember { mutableStateOf(initial?.target ?: SearchTarget.GIF) }
    val cleaned = SearchTriggers.clean(prefix)
    val clash = cleaned != null && cleaned.lowercase() in taken
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.search_shortcuts_add_title else R.string.search_shortcuts_edit_title,
                ),
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it.take(SearchTriggers.MAX_PREFIX) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.search_shortcuts_prefix_label)) },
                    placeholder = { Text(stringResource(R.string.search_shortcuts_prefix_hint)) },
                    isError = clash,
                    supportingText = {
                        Text(
                            stringResource(
                                if (clash) R.string.search_shortcuts_prefix_taken else R.string.search_shortcuts_prefix_help,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceControl(
                    label = stringResource(R.string.search_shortcuts_target_label),
                    options = SearchTarget.entries.map { it to targetLabel(it) },
                    selected = target,
                    modifier = Modifier.padding(top = 8.dp),
                ) { target = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = cleaned != null && !clash,
                onClick = { cleaned?.let { onSave(SearchTrigger(it, target)) } },
            ) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}
