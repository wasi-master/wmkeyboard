package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDownward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowUpward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode

/**
 * The hold actions row's value: the actions' names in order, or "None".
 */
@Composable
internal fun holdActionsSummary(
    actions: List<KeyAlternate>,
    layouts: List<LayoutSpec>,
    modes: List<KeyboardMode>,
): String =
    if (actions.isEmpty()) {
        stringResource(R.string.layout_hold_actions_none)
    } else {
        actions.map { fingerSwipeBindingName(it, layouts, modes) }.joinToString(", ")
    }

/**
 * The hold actions list (competitor gap list item 14): one row per action in
 * the order the popup shows them, each movable and removable, and a button
 * that adds one through the multi-finger swipes' own picker, so the list can
 * hold anything a key can do.
 *
 * Every change goes out through [onChange] as the whole new list; the dialog
 * holds no copy of its own, so it always shows what is stored.
 */
@Composable
internal fun HoldActionsDialog(
    actions: List<KeyAlternate>,
    layouts: List<LayoutSpec>,
    modes: List<KeyboardMode>,
    onChange: (List<KeyAlternate>) -> Unit,
    onDismiss: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        // The list steps aside while an action is picked and comes back after,
        // so several can be added one after another.
        KeyBindingPicker(
            current = null,
            layouts = layouts,
            modes = modes,
            onDismiss = { adding = false },
        ) { binding ->
            adding = false
            if (binding.action != KeyAction.None && binding !in actions) onChange(actions + binding)
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_hold_actions_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (actions.isEmpty()) {
                    Text(
                        stringResource(R.string.layout_hold_actions_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                actions.forEachIndexed { index, action ->
                    ListItem(
                        headlineContent = {
                            Text(
                                fingerSwipeBindingName(action, layouts, modes),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    enabled = index > 0,
                                    onClick = { onChange(actions.moved(index, index - 1)) },
                                ) {
                                    Icon(
                                        Icons.Outlined.ArrowUpward,
                                        contentDescription = stringResource(R.string.layout_hold_actions_move_up),
                                    )
                                }
                                IconButton(
                                    enabled = index < actions.lastIndex,
                                    onClick = { onChange(actions.moved(index, index + 1)) },
                                ) {
                                    Icon(
                                        Icons.Outlined.ArrowDownward,
                                        contentDescription = stringResource(R.string.layout_hold_actions_move_down),
                                    )
                                }
                                IconButton(onClick = { onChange(actions.filterIndexed { i, _ -> i != index }) }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(CommonR.string.common_remove),
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.layout_hold_actions_add))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_done)) }
        },
    )
}

/** This list with the entry at [from] moved to [to]. */
private fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    toMutableList().also { it.add(to, it.removeAt(from)) }
