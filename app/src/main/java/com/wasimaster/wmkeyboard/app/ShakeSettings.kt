package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * The shake group of the gestures page (#1429): one row naming what shaking
 * the phone runs while the keyboard is up, opening the action picker the
 * multi-finger swipes and pinches use. Nothing out of the box, and then the
 * keyboard does not listen to the accelerometer at all.
 */
@Composable
internal fun ShakeSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val bound = settings.watch { it.gesture.shakeAction }
    val layouts = secondaryLayouts(settings.watch { it.customLayouts })
    val modes = settings.watch { it.keyboardModes }
    var picking by remember { mutableStateOf(false) }
    SettingsGroup(stringResource(R.string.typing_group_shake_title)) {
        item {
            NavRow(
                R.string.typing_shake_action_title,
                subtitle = stringResource(R.string.typing_shake_action_subtitle),
                value = bound?.let { fingerSwipeBindingName(it, layouts, modes) }
                    ?: stringResource(R.string.typing_finger_swipe_nothing_value),
            ) { picking = true }
        }
    }

    if (picking) {
        KeyBindingPicker(
            current = bound,
            layouts = layouts,
            modes = modes,
            onDismiss = { picking = false },
        ) { binding ->
            picking = false
            // No action clears it; the repository stores nothing for it.
            scope.launch { repository.setShakeAction(binding) }
        }
    }
}
