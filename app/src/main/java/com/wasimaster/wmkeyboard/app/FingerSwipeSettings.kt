package com.wasimaster.wmkeyboard.app

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
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.layout.FingerSwipe
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.SwipeDirection
import com.wasimaster.wmkeyboard.core.layout.actionOf
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.LetterSwipeAction
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import kotlinx.coroutines.launch

/**
 * What a multi-finger swipe may be bound to (issue #647): any key action, bar
 * the ones that only mean something as a held key in a chord engine.
 */
private val FingerSwipeActionCatalog: List<KeyActionOption> =
    KeyActionCatalog.filterNot { option ->
        val action = option.build()
        action is KeyAction.BrailleDot || action == KeyAction.MorseDot || action == KeyAction.MorseDash
    }

/**
 * The multi-finger swipes group of the gestures page (issue #647): the switch,
 * then one row per finger count and direction, each opening the layout
 * editor's action picker and, for an action that names something, the same
 * follow-up picker a key's action gets.
 */
@Composable
internal fun FingerSwipeSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val on = settings.watch { it.gesture.multiFingerSwipes }
    // Two-finger swipes stand aside while the two-thumb loop runs.
    val thumbsGlide = settings.watch {
        it.gestureTyping && it.letterSwipeAction == LetterSwipeAction.TYPE_WORDS && it.gesture.twoThumbGlide
    }
    val bound = settings.watch { it.gesture.fingerSwipes }
    val layouts = secondaryLayouts(settings.watch { it.customLayouts })
    val modes = settings.watch { it.keyboardModes }
    // The slot whose action is being picked.
    var picking by remember { mutableStateOf<FingerSwipe?>(null) }
    fun bind(swipe: FingerSwipe, binding: KeyAlternate?) {
        scope.launch { repository.setFingerSwipe(swipe, binding) }
    }
    SettingsGroup(stringResource(R.string.typing_group_finger_swipes_title)) {
        item {
            ToggleSetting(
                R.string.typing_finger_swipes_title,
                stringResource(R.string.typing_finger_swipes_subtitle),
                on,
                info = stringResource(R.string.typing_finger_swipes_info),
                default = SettingsDefaults.gesture.multiFingerSwipes,
            ) { scope.launch { repository.setMultiFingerSwipes(it) } }
        }
        for (swipe in FingerSwipe.all) {
            item(visible = on, key = swipe.token) {
                val binding = bound.actionOf(swipe)
                NavRow(
                    title = stringResource(
                        R.string.typing_finger_swipe_row_title,
                        swipe.fingers,
                        stringResource(directionLabel(swipe.direction)),
                    ),
                    icon = SettingsRowIcons[R.string.typing_finger_swipe_row_title],
                    subtitle = if (swipe.fingers == 2 && thumbsGlide) {
                        stringResource(R.string.typing_finger_swipe_two_thumb_subtitle)
                    } else {
                        null
                    },
                    value = binding?.let { fingerSwipeBindingName(it, layouts, modes) }
                        ?: stringResource(R.string.typing_finger_swipe_nothing_value),
                ) { picking = swipe }
            }
        }
        item(visible = on && bound.isNotEmpty()) {
            NavRow(
                R.string.typing_finger_swipes_reset_title,
                stringResource(R.string.typing_finger_swipes_reset_subtitle),
            ) {
                scope.launch { FingerSwipe.all.forEach { repository.setFingerSwipe(it, null) } }
            }
        }
    }

    picking?.let { swipe ->
        KeyBindingPicker(
            current = bound.actionOf(swipe),
            layouts = layouts,
            modes = modes,
            onDismiss = { picking = null },
        ) { binding ->
            picking = null
            bind(swipe, binding)
        }
    }
}

/**
 * Picks what a gesture runs: the layout editor's action picker, then for an
 * action that names something (its text, tool, layout, keyboard or broadcast)
 * the same follow-up a key's action gets. Shared by the multi-finger swipes
 * (issue #647) and the `?123` chords (issue #754). [onBind] gets the finished
 * binding; dismissing either step gives none.
 */
@Composable
internal fun KeyBindingPicker(
    current: KeyAlternate?,
    layouts: List<LayoutSpec>,
    modes: List<KeyboardMode> = emptyList(),
    onDismiss: () -> Unit,
    onBind: (KeyAlternate) -> Unit,
) {
    // The action picked, while its follow-up is open.
    var followUp by remember { mutableStateOf<KeyAction?>(null) }
    when (val action = followUp) {
        null -> KeyActionPickerDialog(
            current = current?.action ?: KeyAction.None,
            options = FingerSwipeActionCatalog,
            onPick = { picked ->
                // Half an answer until it names its text, tool, layout, mode or keyboard.
                val needsMore = picked == KeyAction.Text || picked is KeyAction.Tool ||
                    picked is KeyAction.Edit || picked is KeyAction.Layout || picked is KeyAction.Mode ||
                    picked is KeyAction.SwitchInputMethod || picked is KeyAction.Broadcast
                if (needsMore) followUp = picked else onBind(KeyAlternate(picked))
            },
            onDismiss = onDismiss,
        )
        is KeyAction.Tool -> ToolPickerDialog(
            title = stringResource(R.string.layout_editor_tool_picker_title),
            current = (current?.action as? KeyAction.Tool)?.tool,
            options = ToolbarTool.entries.filter(::isSupportedTool),
            onDismiss = onDismiss,
            onPick = { picked -> if (picked != null) onBind(KeyAlternate(KeyAction.Tool(picked))) else onDismiss() },
        )
        is KeyAction.Edit -> TextEditActionPickerDialog(
            current = (current?.action as? KeyAction.Edit)?.op,
            onDismiss = onDismiss,
            // A swipe has no hold, so the arm needs an action of its own.
            offerNoSpaceNext = true,
            onPick = { op -> onBind(KeyAlternate(KeyAction.Edit(op))) },
        )
        is KeyAction.Layout -> SecondaryLayoutPickerDialog(
            current = (current?.action as? KeyAction.Layout)?.id,
            options = layouts,
            onDismiss = onDismiss,
            onPick = { picked -> onBind(KeyAlternate(KeyAction.Layout(picked.id), label = picked.name)) },
        )
        is KeyAction.Mode -> KeyboardModePickerDialog(
            current = (current?.action as? KeyAction.Mode)?.id,
            options = modes,
            onDismiss = onDismiss,
            onPick = { picked -> onBind(KeyAlternate(KeyAction.Mode(picked.id), label = picked.name)) },
        )
        is KeyAction.SwitchInputMethod -> InputMethodPickerDialog(
            current = (current?.action as? KeyAction.SwitchInputMethod)?.id,
            onDismiss = onDismiss,
            onPick = { id, name -> onBind(KeyAlternate(KeyAction.SwitchInputMethod(id), label = name)) },
        )
        is KeyAction.Broadcast -> FingerSwipeTextDialog(
            title = stringResource(R.string.typing_finger_swipe_broadcast_title),
            initial = (current?.action as? KeyAction.Broadcast)?.action.orEmpty(),
            onDismiss = onDismiss,
            onDone = { text -> onBind(KeyAlternate(KeyAction.Broadcast(text))) },
        )
        else -> FingerSwipeTextDialog(
            title = stringResource(R.string.typing_finger_swipe_text_title),
            initial = current?.takeIf { it.action == KeyAction.Text }?.label.orEmpty(),
            onDismiss = onDismiss,
            onDone = { text -> onBind(KeyAlternate(KeyAction.Text, label = text)) },
        )
    }
}

/** What a row says its swipe or chord does: the typed text in quotes, else the action's name. */
@Composable
internal fun fingerSwipeBindingName(
    binding: KeyAlternate,
    layouts: List<LayoutSpec>,
    modes: List<KeyboardMode> = emptyList(),
): String =
    when (binding.action) {
        KeyAction.Text -> stringResource(R.string.typing_finger_swipe_text_value, binding.label)
        is KeyAction.SwitchInputMethod -> binding.label.ifBlank { actionAlternateName(binding, layouts) }
        else -> actionAlternateName(binding, layouts, modes)
    }

private fun directionLabel(direction: SwipeDirection): Int = when (direction) {
    SwipeDirection.UP -> R.string.typing_finger_swipe_up
    SwipeDirection.DOWN -> R.string.typing_finger_swipe_down
    SwipeDirection.LEFT -> R.string.typing_finger_swipe_left
    SwipeDirection.RIGHT -> R.string.typing_finger_swipe_right
}

/** One line of text for a swipe that types it, or the action a broadcast sends. */
@Composable
internal fun FingerSwipeTextDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onDone(text) }, enabled = text.isNotEmpty()) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
