package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.PinchGesture
import com.wasimaster.wmkeyboard.core.layout.actionOf
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.settings.LetterSwipeAction
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlinx.coroutines.launch

/**
 * The pinch and stretch group of the gestures page (gap list item 18): the
 * switch, then one row each for a pinch and a stretch, opening the same
 * action picker the multi-finger swipes use.
 */
@Composable
internal fun PinchSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val on = settings.watch { it.gesture.pinchGestures }
    // The pair stands aside while the two-thumb loop runs, where two moving
    // thumbs are two words.
    val thumbsGlide = settings.watch {
        it.gestureTyping && it.letterSwipeAction == LetterSwipeAction.TYPE_WORDS && it.gesture.twoThumbGlide
    }
    val bound = settings.watch { it.gesture.pinchBindings }
    val layouts = secondaryLayouts(settings.watch { it.customLayouts })
    val modes = settings.watch { it.keyboardModes }
    var picking by remember { mutableStateOf<PinchGesture?>(null) }
    SettingsGroup(stringResource(R.string.typing_group_pinch_title)) {
        item {
            ToggleSetting(
                R.string.typing_pinch_gestures_title,
                stringResource(
                    if (thumbsGlide) R.string.typing_pinch_two_thumb_subtitle else R.string.typing_pinch_gestures_subtitle,
                ),
                on,
                info = stringResource(R.string.typing_pinch_gestures_info),
                default = SettingsDefaults.gesture.pinchGestures,
            ) { scope.launch { repository.setPinchGestures(it) } }
        }
        for (gesture in PinchGesture.entries) {
            item(visible = on, key = gesture.token) {
                val binding = bound.actionOf(gesture)
                val value = when {
                    binding == null -> stringResource(R.string.typing_finger_swipe_nothing_value)
                    // The split toggle runs one way per gesture, so the row says which.
                    (binding.action as? KeyAction.Tool)?.tool == ToolbarTool.SPLIT -> stringResource(
                        if (gesture == PinchGesture.STRETCH) R.string.typing_pinch_split_value else R.string.typing_pinch_merge_value,
                    )
                    else -> fingerSwipeBindingName(binding, layouts, modes)
                }
                NavRow(
                    if (gesture == PinchGesture.STRETCH) R.string.typing_stretch_row_title else R.string.typing_pinch_row_title,
                    value = value,
                ) { picking = gesture }
            }
        }
        item(visible = on && bound.isNotEmpty()) {
            NavRow(
                R.string.typing_pinch_reset_title,
                stringResource(R.string.typing_pinch_reset_subtitle),
            ) {
                scope.launch { PinchGesture.entries.forEach { repository.setPinchGesture(it, null) } }
            }
        }
    }

    picking?.let { gesture ->
        KeyBindingPicker(
            current = bound.actionOf(gesture),
            layouts = layouts,
            modes = modes,
            onDismiss = { picking = null },
        ) { binding ->
            picking = null
            scope.launch { repository.setPinchGesture(gesture, binding) }
        }
    }
}
