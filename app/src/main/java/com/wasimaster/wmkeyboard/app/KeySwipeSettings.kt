package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.KeySwipeArms
import com.wasimaster.wmkeyboard.core.layout.KeySwipePreset
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.SymbolChord
import com.wasimaster.wmkeyboard.core.layout.plusPreset
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * The swipes-on-keys group of the gestures page: flick arms put on keys from
 * here rather than in the layout editor, so they follow the letter onto every
 * layout. The switch, one row per key with arms, a row to add a key, the
 * presets and a reset. A key's row opens its eight directions as a 3×3 pad,
 * and each direction the same action picker a multi-finger swipe gets.
 */
@Composable
internal fun KeySwipeSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val on = settings.watch { it.gesture.keySwipesOn }
    val swipes = settings.watch { it.gesture.keySwipes }
    val layouts = secondaryLayouts(settings.watch { it.customLayouts })
    val modes = settings.watch { it.keyboardModes }
    // The key whose pad is open, the direction on it being picked, and the two
    // dialogs that come before a key has a pad at all.
    var editing by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<FlickDirection?>(null) }
    var naming by remember { mutableStateOf(false) }
    var choosingPreset by remember { mutableStateOf(false) }
    SettingsGroup(stringResource(R.string.typing_group_key_swipes_title)) {
        item {
            ToggleSetting(
                R.string.typing_key_swipes_title,
                stringResource(R.string.typing_key_swipes_subtitle),
                on,
                info = stringResource(R.string.typing_key_swipes_info),
                default = SettingsDefaults.gesture.keySwipesOn,
            ) { scope.launch { repository.setKeySwipesOn(it) } }
        }
        for ((token, arms) in swipes.toSortedMap()) {
            item(visible = on, key = "key-swipes-$token") {
                NavRow(
                    title = stringResource(R.string.typing_key_swipe_row_title, token.uppercase()),
                    icon = SettingsRowIcons[R.string.typing_key_swipe_row_title],
                    value = keySwipeSummary(arms, layouts, modes),
                ) { editing = token }
            }
        }
        item(visible = on) {
            NavRow(
                R.string.typing_key_swipe_add_title,
                stringResource(R.string.typing_key_swipe_add_subtitle),
            ) { naming = true }
        }
        item(visible = on) {
            NavRow(
                R.string.typing_key_swipe_preset_title,
                stringResource(R.string.typing_key_swipe_preset_subtitle),
            ) { choosingPreset = true }
        }
        item(visible = on && swipes.isNotEmpty()) {
            NavRow(
                R.string.typing_key_swipes_reset_title,
                stringResource(R.string.typing_key_swipes_reset_subtitle),
            ) { scope.launch { repository.editKeySwipes { emptyMap() } } }
        }
    }

    if (naming) {
        KeySwipeKeyDialog(onDismiss = { naming = false }) { token ->
            naming = false
            editing = token
        }
    }

    if (choosingPreset) {
        KeySwipePresetDialog(onDismiss = { choosingPreset = false }) { preset ->
            choosingPreset = false
            scope.launch { repository.editKeySwipes { it.plusPreset(preset) } }
        }
    }

    editing?.let { token ->
        val direction = picking
        if (direction == null) {
            KeySwipeArmsDialog(
                token = token,
                arms = swipes[token].orEmpty(),
                layouts = layouts,
                modes = modes,
                onPick = { picking = it },
                onDismiss = { editing = null },
            )
        } else {
            // The pad steps aside while a direction is picked and comes back
            // after, so a key's arms can be set one after another.
            KeyBindingPicker(
                current = swipes[token]?.get(direction),
                layouts = layouts,
                modes = modes,
                onDismiss = { picking = null },
            ) { binding ->
                picking = null
                scope.launch { repository.setKeySwipe(token, direction, binding) }
            }
        }
    }
}

/** The arrow a direction is drawn with on the pad and in a row's summary. */
private fun arrowOf(direction: FlickDirection): String = when (direction) {
    FlickDirection.UP_LEFT -> "↖"
    FlickDirection.UP -> "↑"
    FlickDirection.UP_RIGHT -> "↗"
    FlickDirection.LEFT -> "←"
    FlickDirection.RIGHT -> "→"
    FlickDirection.DOWN_LEFT -> "↙"
    FlickDirection.DOWN -> "↓"
    FlickDirection.DOWN_RIGHT -> "↘"
}

/** What one arm does, short enough for a pad cell: the text itself, else the action's name. */
@Composable
private fun armName(binding: KeyAlternate, layouts: List<LayoutSpec>, modes: List<KeyboardMode>): String =
    if (binding.action == KeyAction.Text) binding.label else fingerSwipeBindingName(binding, layouts, modes)

/** A key's arms on one line, in the pad's reading order: `↖ ! ↗ 1`. */
@Composable
private fun keySwipeSummary(arms: KeySwipeArms, layouts: List<LayoutSpec>, modes: List<KeyboardMode>): String =
    FlickDirection.gridOrder.filterNotNull()
        .mapNotNull { direction -> arms[direction]?.let { "${arrowOf(direction)} ${armName(it, layouts, modes)}" } }
        .joinToString("  ")

/** A key's eight directions laid out around it, each opening its picker. */
@Composable
private fun KeySwipeArmsDialog(
    token: String,
    arms: KeySwipeArms,
    layouts: List<LayoutSpec>,
    modes: List<KeyboardMode>,
    onPick: (FlickDirection) -> Unit,
    onDismiss: () -> Unit,
) {
    val nothing = stringResource(R.string.typing_finger_swipe_nothing_value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.typing_key_swipe_row_title, token.uppercase())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.typing_key_swipe_pad_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (row in FlickDirection.gridOrder.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (direction in row) {
                            val name = direction?.let { arms[it] }?.let { armName(it, layouts, modes) }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (direction == null) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                    )
                                    .then(
                                        if (direction == null) {
                                            Modifier
                                        } else {
                                            Modifier
                                                .clickable { onPick(direction) }
                                                .semantics { contentDescription = "${arrowOf(direction)} ${name ?: nothing}" }
                                        },
                                    )
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (direction == null) {
                                    Text(token, style = MaterialTheme.typography.titleLarge)
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            arrowOf(direction),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            name ?: "",
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_done)) }
        },
    )
}

/** Names the key to put swipes on: the one character it types. */
@Composable
private fun KeySwipeKeyDialog(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val token = text.trim().lowercase()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.typing_key_swipe_key_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.typing_symbol_chord_key_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(token) }, enabled = SymbolChord.isToken(token)) {
                Text(stringResource(CommonR.string.common_next))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** The ready-made sets, each merged into the user's arms when picked. */
@Composable
private fun KeySwipePresetDialog(onDismiss: () -> Unit, onPick: (KeySwipePreset) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.typing_key_swipe_preset_title)) },
        text = {
            Column {
                for (preset in KeySwipePreset.entries) {
                    val (title, description) = when (preset) {
                        KeySwipePreset.NUMBERS ->
                            R.string.typing_key_swipe_preset_numbers to R.string.typing_key_swipe_preset_numbers_desc
                        KeySwipePreset.SYMBOLS ->
                            R.string.typing_key_swipe_preset_symbols to R.string.typing_key_swipe_preset_symbols_desc
                        KeySwipePreset.EDITING ->
                            R.string.typing_key_swipe_preset_editing to R.string.typing_key_swipe_preset_editing_desc
                        KeySwipePreset.PUNCTUATION ->
                            R.string.typing_key_swipe_preset_punctuation to
                                R.string.typing_key_swipe_preset_punctuation_desc
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(title)) },
                        supportingContent = { Text(stringResource(description)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(preset) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
