package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.layout.SwipeDirection
import com.wasimaster.wmkeyboard.core.layout.oneFingerActionOf
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * The one-finger swipe group of the gestures page (gap list item 44): the
 * switch, then one row per direction, opening the same action picker the
 * multi-finger swipes use. The swipes only work while glide typing is off,
 * so the switch says so instead of pretending.
 */
@Composable
internal fun OneFingerSwipeSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val on = settings.watch { it.gesture.oneFingerSwipes }
    val glideOn = settings.watch { it.gestureTyping }
    val bound = settings.watch { it.gesture.oneFingerBindings }
    val layouts = secondaryLayouts(settings.watch { it.customLayouts })
    val modes = settings.watch { it.keyboardModes }
    var picking by remember { mutableStateOf<SwipeDirection?>(null) }
    SettingsGroup(stringResource(R.string.typing_group_one_finger_title)) {
        item {
            ToggleSetting(
                R.string.typing_one_finger_swipes_title,
                stringResource(
                    if (glideOn) R.string.typing_one_finger_glide_subtitle else R.string.typing_one_finger_swipes_subtitle,
                ),
                on,
                info = stringResource(R.string.typing_one_finger_swipes_info),
                default = SettingsDefaults.gesture.oneFingerSwipes,
            ) { scope.launch { repository.setOneFingerSwipes(it) } }
        }
        for (direction in SwipeDirection.entries) {
            item(visible = on, key = "one-finger-${direction.token}") {
                val binding = bound.oneFingerActionOf(direction)
                NavRow(
                    when (direction) {
                        SwipeDirection.UP -> R.string.typing_one_finger_up_title
                        SwipeDirection.DOWN -> R.string.typing_one_finger_down_title
                        SwipeDirection.LEFT -> R.string.typing_one_finger_left_title
                        SwipeDirection.RIGHT -> R.string.typing_one_finger_right_title
                    },
                    value = if (binding == null) {
                        stringResource(R.string.typing_finger_swipe_nothing_value)
                    } else {
                        fingerSwipeBindingName(binding, layouts, modes)
                    },
                ) { picking = direction }
            }
        }
        item(visible = on && bound.isNotEmpty()) {
            NavRow(
                R.string.typing_one_finger_reset_title,
                stringResource(R.string.typing_one_finger_reset_subtitle),
            ) {
                scope.launch { SwipeDirection.entries.forEach { repository.setOneFingerSwipe(it, null) } }
            }
        }
    }

    picking?.let { direction ->
        KeyBindingPicker(
            current = bound.oneFingerActionOf(direction),
            layouts = layouts,
            modes = modes,
            onDismiss = { picking = null },
        ) { binding ->
            picking = null
            scope.launch { repository.setOneFingerSwipe(direction, binding) }
        }
    }
}
