package com.wasimaster.wmkeyboard.app

import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.accessibility.ScreenContextService
import com.wasimaster.wmkeyboard.core.accessibility.SystemButtonsService
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerticalSplit
import com.wasimaster.wmkeyboard.core.settings.AppletTools
import com.wasimaster.wmkeyboard.core.settings.DefaultFloatingMenu
import com.wasimaster.wmkeyboard.core.settings.FLOATING_CATEGORY_NAME_MAX
import com.wasimaster.wmkeyboard.core.settings.FloatingAction
import com.wasimaster.wmkeyboard.core.settings.FloatingButtonIdleRange
import com.wasimaster.wmkeyboard.core.settings.FloatingButtonShape
import com.wasimaster.wmkeyboard.core.settings.FloatingButtonSizeRange
import com.wasimaster.wmkeyboard.core.settings.FloatingButtonStyle
import com.wasimaster.wmkeyboard.core.settings.FloatingCategory
import com.wasimaster.wmkeyboard.core.settings.FlickDirection
import com.wasimaster.wmkeyboard.core.settings.FloatingMenuAnimation
import com.wasimaster.wmkeyboard.core.settings.LauncherSplitCombo
import com.wasimaster.wmkeyboard.core.settings.FloatingAnimationSpeedRange
import com.wasimaster.wmkeyboard.core.settings.FloatingMenuGapRange
import com.wasimaster.wmkeyboard.core.settings.FloatingPillLengthRange
import com.wasimaster.wmkeyboard.core.settings.FloatingPillThicknessRange
import com.wasimaster.wmkeyboard.core.settings.FloatingPillTouchRange
import com.wasimaster.wmkeyboard.core.settings.MAX_FLOATING_MENU_ITEMS
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.SummonButtonSettings
import com.wasimaster.wmkeyboard.core.settings.SystemButton
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.categoryById
import com.wasimaster.wmkeyboard.core.settings.categoryItems
import com.wasimaster.wmkeyboard.core.settings.fitsCategory
import com.wasimaster.wmkeyboard.core.settings.fitsMenu
import com.wasimaster.wmkeyboard.core.settings.gestureActions
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.pillTouchWidthDp
import com.wasimaster.wmkeyboard.core.settings.readsScreen
import com.wasimaster.wmkeyboard.core.settings.pressesSystemButtons
import com.wasimaster.wmkeyboard.ime.AppCatalog
import com.wasimaster.wmkeyboard.ime.KeyboardControls
import com.wasimaster.wmkeyboard.ime.floating.FLOATING_MENU_ITEM_DP
import com.wasimaster.wmkeyboard.ime.floating.FLOATING_MENU_LABEL_DP
import com.wasimaster.wmkeyboard.ime.floating.FloatingButtonFace
import com.wasimaster.wmkeyboard.ime.floating.FloatingEdgePill
import com.wasimaster.wmkeyboard.ime.floating.FloatingMenuItem
import com.wasimaster.wmkeyboard.ime.floating.floatingActionIcon
import com.wasimaster.wmkeyboard.ime.floating.floatingActionLabel
import com.wasimaster.wmkeyboard.ime.floating.FLOATING_RING_SIZE
import com.wasimaster.wmkeyboard.ime.floating.floatingRings
import com.wasimaster.wmkeyboard.ime.floating.rememberFloatingAppIcon
import com.wasimaster.wmkeyboard.ime.ui.ModeIcons
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The route of Advanced › Floating button (#755). */
internal const val FloatingButtonRoute = "floating_button"

/** Which gesture a picker is choosing for, or the quick menu's add. */
private enum class FloatingPick { TAP, DOUBLE_TAP, HOLD, TAP_TYPING, DOUBLE_TAP_TYPING, MENU_ADD, FLICK }

/** A swipe direction's row on the page (#1171). */
private fun flickTitle(direction: FlickDirection): Int = when (direction) {
    FlickDirection.UP -> R.string.floating_button_flick_up_title
    FlickDirection.UP_RIGHT -> R.string.floating_button_flick_up_right_title
    FlickDirection.RIGHT -> R.string.floating_button_flick_right_title
    FlickDirection.DOWN_RIGHT -> R.string.floating_button_flick_down_right_title
    FlickDirection.DOWN -> R.string.floating_button_flick_down_title
    FlickDirection.DOWN_LEFT -> R.string.floating_button_flick_down_left_title
    FlickDirection.LEFT -> R.string.floating_button_flick_left_title
    FlickDirection.UP_LEFT -> R.string.floating_button_flick_up_left_title
}

/**
 * Advanced › Floating button (#755): the one button over other apps that the
 * keyboard button (#374) and the AI button (#697) became, and everything about
 * it. A live preview heads the page, drawn from the very pieces the button is
 * drawn with, so each change below shows at once.
 */
@Composable
internal fun FloatingButtonSettingsScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val button = settings.watch { it.summonButton }
    // The app launcher's split pairs, which an action can open (#1140).
    val combos = settings.watch { it.launcher.combos }
    // The user's own buttons, which an action can run (#1213, #1236): the
    // pickers and the preview name and draw them from here.
    val customButtons = settings.watch { it.toolbox.customButtons }
    androidx.compose.runtime.SideEffect {
        com.wasimaster.wmkeyboard.ime.ui.CustomButtonFaces.buttons = customButtons
    }
    val overlayGranted = rememberGrantState { Settings.canDrawOverlays(it) }
    val askOverlay = rememberDisclosedSpecialAccess(SpecialAccess.OVERLAY)
    val screenReading = rememberGrantState { ScreenContextService.isEnabled(it) }
    val askScreenReading = rememberDisclosedSpecialAccess(SpecialAccess.SCREEN_CONTENT)
    val systemButtons = rememberGrantState { SystemButtonsService.isEnabled(it) }
    val askSystemButtons = rememberDisclosedSpecialAccess(SpecialAccess.SYSTEM_BUTTONS)
    // The keyboard only re-checks the grant when it next closes; coming back
    // from the system screen with it is worth showing the button at once.
    LaunchedEffect(button.enabled, overlayGranted) {
        KeyboardControls.host?.syncSummonButton()
    }
    var picking by remember { mutableStateOf<FloatingPick?>(null) }
    // The swipe direction a FLICK pick answers for (#1171).
    var flickPicking by remember { mutableStateOf<FlickDirection?>(null) }
    // The gesture or the menu an app picker answers for (#922), once "Open an app" is chosen.
    var pickingApp by remember { mutableStateOf<FloatingPick?>(null) }
    // The same for a pair of apps (#1140), once "Open an app pair" is chosen.
    var pickingPair by remember { mutableStateOf<FloatingPick?>(null) }
    // The category whose page is open (#923), by id.
    var editingCategory by remember { mutableStateOf<String?>(null) }
    val newCategoryName = stringResource(R.string.floating_button_category_default_name)
    val defaults = SettingsDefaults.summonButton
    val percentFormat = stringResource(R.string.typing_value_percent)
    val dpFormat = stringResource(R.string.typing_value_dp)
    val menuOpens = FloatingAction.Menu in button.gestureActions()

    SettingsGroup {
        item { FloatingButtonPreview(button) }
        item {
            ToggleSetting(
                R.string.floating_button_enable_title,
                stringResource(R.string.floating_button_enable_subtitle),
                button.enabled,
                info = stringResource(R.string.floating_button_enable_info),
                default = defaults.enabled,
            ) { on ->
                scope.launch { repository.setSummonButton(on) }
                // The disclosure, then Android's own switch. The setting is
                // kept either way, so granting later is all it takes.
                if (on && !overlayGranted) askOverlay()
            }
        }
        item(visible = button.enabled && !overlayGranted) {
            NavRow(
                R.string.layout_summon_button_permission_title,
                subtitle = stringResource(R.string.layout_summon_button_permission_subtitle),
            ) { askOverlay() }
        }
        item(
            visible = button.enabled && overlayGranted && button.readsScreen() &&
                !screenReading && ChannelFeatures.SCREEN_CONTEXT,
        ) {
            NavRow(
                R.string.floating_button_screen_title,
                subtitle = stringResource(R.string.floating_button_screen_subtitle),
            ) { askScreenReading() }
        }
        // Screenshot bound to a gesture or the menu (#1148), its service off.
        item(
            visible = button.enabled && overlayGranted && button.pressesSystemButtons() &&
                !systemButtons && ChannelFeatures.SYSTEM_BUTTONS,
        ) {
            NavRow(
                R.string.floating_button_system_buttons_title,
                subtitle = stringResource(R.string.floating_button_system_buttons_subtitle),
            ) { askSystemButtons() }
        }
    }

    SettingsGroup(
        stringResource(R.string.floating_button_gestures_group),
        info = stringResource(R.string.floating_button_gestures_info),
    ) {
        item {
            NavRow(R.string.floating_button_tap_title, value = floatingActionLabel(button.tap)) {
                picking = FloatingPick.TAP
            }
        }
        item(level = SettingLevel.STANDARD) {
            NavRow(R.string.floating_button_double_tap_title, value = floatingActionLabel(button.doubleTap)) {
                picking = FloatingPick.DOUBLE_TAP
            }
        }
        item(level = SettingLevel.STANDARD) {
            NavRow(R.string.floating_button_hold_title, value = floatingActionLabel(button.hold)) {
                picking = FloatingPick.HOLD
            }
        }
        // Pinned where it is, its swipes running actions by direction (#1171).
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.floating_button_docked_title,
                stringResource(R.string.floating_button_docked_subtitle),
                button.docked,
                default = defaults.docked,
            ) { scope.launch { repository.setFloatingButtonDocked(it) } }
        }
        for (direction in FlickDirection.entries) {
            item(visible = button.docked, level = SettingLevel.STANDARD) {
                NavRow(
                    flickTitle(direction),
                    value = button.flicks[direction]?.let { floatingActionLabel(it, categories = button.categories) }
                        ?: stringResource(R.string.floating_button_flick_unbound),
                ) {
                    flickPicking = direction
                    picking = FloatingPick.FLICK
                }
            }
        }
    }

    // The same two gestures with the keyboard up (#626): one tap opens the
    // keyboard, the next does something else, such as leaving the field.
    // Only while the button stays on screen over the keyboard.
    SettingsGroup(
        stringResource(R.string.floating_button_typing_group),
        info = stringResource(R.string.floating_button_typing_info),
        level = SettingLevel.ADVANCED,
    ) {
        item(visible = button.whileTyping) {
            NavRow(
                R.string.floating_button_tap_typing_title,
                value = button.tapTyping?.let { floatingActionLabel(it, keyboardUp = true) }
                    ?: stringResource(R.string.floating_button_same_as, stringResource(R.string.floating_button_tap_title)),
            ) { picking = FloatingPick.TAP_TYPING }
        }
        item(visible = button.whileTyping) {
            NavRow(
                R.string.floating_button_double_tap_typing_title,
                value = button.doubleTapTyping?.let { floatingActionLabel(it, keyboardUp = true) }
                    ?: stringResource(
                        R.string.floating_button_same_as,
                        stringResource(R.string.floating_button_double_tap_title),
                    ),
            ) { picking = FloatingPick.DOUBLE_TAP_TYPING }
        }
    }

    SettingsGroup(
        stringResource(R.string.floating_button_menu_group),
        info = stringResource(R.string.floating_button_menu_info),
    ) {
        item(visible = !menuOpens) {
            NavRow(
                R.string.floating_button_menu_unreached_title,
                subtitle = stringResource(R.string.floating_button_menu_unreached_subtitle),
            ) { scope.launch { repository.setFloatingButtonHold(FloatingAction.Menu) } }
        }
        item {
            FloatingMenuEditor(
                items = button.menu,
                categories = button.categories,
                onChange = { scope.launch { repository.setFloatingButtonMenu(it) } },
                onEditCategory = { editingCategory = it },
            )
        }
        item {
            val full = button.menu.size >= MAX_FLOATING_MENU_ITEMS
            NavRow(
                R.string.floating_button_menu_add_title,
                subtitle = if (full) {
                    stringResource(R.string.floating_button_menu_full_subtitle)
                } else {
                    stringResource(R.string.floating_button_menu_add_subtitle, MAX_FLOATING_MENU_ITEMS)
                },
            ) { if (!full) picking = FloatingPick.MENU_ADD }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.floating_button_menu_labels_title,
                stringResource(R.string.floating_button_menu_labels_subtitle),
                button.menuLabels,
                default = defaults.menuLabels,
            ) { scope.launch { repository.setFloatingButtonMenuLabels(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.floating_button_menu_gap_title,
                subtitle = stringResource(R.string.floating_button_menu_gap_subtitle),
                value = button.menuGapDp.toFloat(),
                range = FloatingMenuGapRange.first.toFloat()..FloatingMenuGapRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                default = defaults.menuGapDp.toFloat(),
            ) { scope.launch { repository.setFloatingButtonMenuGap(it.roundToInt()) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.floating_button_menu_animation_title,
                options = FloatingMenuAnimation.entries.map { it to stringResource(animationLabel(it)) },
                selected = button.menuAnimation,
                default = defaults.menuAnimation,
            ) { scope.launch { repository.setFloatingButtonMenuAnimation(it) } }
        }
        // Faster for those who find the motion slow (#1138); the button's own
        // appearing and settling follow it too, so it shows even with None.
        item(level = SettingLevel.ADVANCED) {
            val speedFormat = stringResource(R.string.floating_button_animation_speed_value)
            SliderSetting(
                R.string.floating_button_animation_speed_title,
                subtitle = stringResource(R.string.floating_button_animation_speed_subtitle),
                value = button.animationSpeed,
                range = FloatingAnimationSpeedRange,
                display = { speedFormat.format(it) },
                default = defaults.animationSpeed,
            ) { scope.launch { repository.setFloatingButtonAnimationSpeed((it * 20).roundToInt() / 20f) } }
        }
        item(visible = button.menu != DefaultFloatingMenu) {
            NavRow(
                R.string.floating_button_menu_reset_title,
                subtitle = stringResource(R.string.floating_button_menu_reset_subtitle),
            ) { scope.launch { repository.resetFloatingButtonMenu() } }
        }
    }

    SettingsGroup(stringResource(R.string.floating_button_look_group)) {
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.floating_button_style_title,
                options = FloatingButtonStyle.entries.map { it to stringResource(styleLabel(it)) },
                selected = button.style,
                default = defaults.style,
            ) { scope.launch { repository.setFloatingButtonStyle(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.floating_button_shape_title,
                subtitle = stringResource(R.string.floating_button_shape_subtitle),
                options = FloatingButtonShape.entries.map { it to stringResource(shapeLabel(it)) },
                selected = button.shape,
                default = defaults.shape,
            ) { scope.launch { repository.setFloatingButtonShape(it) } }
        }
        // A slim line against the edge for a small screen (#1137); the rest of
        // the page, the quick menu above all, works the same on it.
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.floating_button_pill_title,
                stringResource(R.string.floating_button_pill_subtitle),
                button.edgePill,
                default = defaults.edgePill,
            ) { scope.launch { repository.setFloatingButtonPill(it) } }
        }
        item(visible = button.edgePill, level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.floating_button_pill_thickness_title,
                subtitle = stringResource(R.string.floating_button_pill_thickness_subtitle),
                value = button.pillThicknessDp.toFloat(),
                range = FloatingPillThicknessRange.first.toFloat()..FloatingPillThicknessRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                default = defaults.pillThicknessDp.toFloat(),
            ) { scope.launch { repository.setFloatingButtonPillThickness(it.roundToInt()) } }
        }
        item(visible = button.edgePill, level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.floating_button_pill_length_title,
                subtitle = stringResource(R.string.floating_button_pill_length_subtitle),
                value = button.pillLengthDp.toFloat(),
                range = FloatingPillLengthRange.first.toFloat()..FloatingPillLengthRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                default = defaults.pillLengthDp.toFloat(),
            ) { scope.launch { repository.setFloatingButtonPillLength(it.roundToInt()) } }
        }
        item(visible = button.edgePill, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.floating_button_pill_touch_title,
                subtitle = stringResource(R.string.floating_button_pill_touch_subtitle),
                // Never thinner than the pill, which is how the button reads it.
                value = button.pillTouchWidthDp.toFloat(),
                range = FloatingPillTouchRange.first.toFloat()..FloatingPillTouchRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                default = defaults.pillTouchDp.toFloat(),
            ) { scope.launch { repository.setFloatingButtonPillTouch(it.roundToInt()) } }
        }
        item {
            SliderSetting(
                R.string.floating_button_size_title,
                subtitle = stringResource(R.string.floating_button_size_subtitle),
                value = button.sizeDp.toFloat(),
                range = FloatingButtonSizeRange.first.toFloat()..FloatingButtonSizeRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                default = defaults.sizeDp.toFloat(),
            ) { scope.launch { repository.setFloatingButtonSize(it.roundToInt()) } }
        }
        item {
            SliderSetting(
                R.string.floating_button_opacity_title,
                subtitle = stringResource(R.string.floating_button_opacity_subtitle),
                value = button.opacity,
                range = 0.3f..1f,
                display = { percentFormat.format((it * 100).roundToInt()) },
                default = defaults.opacity,
            ) { scope.launch { repository.setFloatingButtonOpacity(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            val never = stringResource(R.string.floating_button_idle_never)
            val seconds = stringResource(R.string.floating_button_idle_seconds)
            SliderSetting(
                R.string.floating_button_idle_title,
                subtitle = stringResource(R.string.floating_button_idle_subtitle),
                value = button.idleFadeSeconds.toFloat(),
                range = FloatingButtonIdleRange.first.toFloat()..FloatingButtonIdleRange.last.toFloat(),
                display = { it.roundToInt().let { s -> if (s == 0) never else seconds.format(s) } },
                default = defaults.idleFadeSeconds.toFloat(),
                typed = false,
            ) { scope.launch { repository.setFloatingButtonIdleFade(it.roundToInt()) } }
        }
        item(visible = button.idleFadeSeconds > 0, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.floating_button_idle_opacity_title,
                subtitle = stringResource(R.string.floating_button_idle_opacity_subtitle),
                value = button.idleOpacity,
                range = 0.15f..1f,
                display = { percentFormat.format((it * 100).roundToInt()) },
                default = defaults.idleOpacity,
            ) { scope.launch { repository.setFloatingButtonIdleOpacity(it) } }
        }
        // A pill already sits against the edge, so it has nothing to tuck.
        item(visible = button.idleFadeSeconds > 0 && !button.edgePill, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.floating_button_tuck_title,
                stringResource(R.string.floating_button_tuck_subtitle),
                button.tuck,
                default = defaults.tuck,
            ) { scope.launch { repository.setFloatingButtonTuck(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.floating_button_behaviour_group)) {
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.floating_button_while_typing_title,
                stringResource(R.string.floating_button_while_typing_subtitle),
                button.whileTyping,
                default = defaults.whileTyping,
            ) { scope.launch { repository.setFloatingButtonWhileTyping(it) } }
        }
        item(visible = button.whileTyping, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.floating_button_over_keyboard_title,
                stringResource(R.string.floating_button_over_keyboard_subtitle),
                button.overKeyboard,
                default = defaults.overKeyboard,
            ) { scope.launch { repository.setFloatingButtonOverKeyboard(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.floating_button_landscape_title,
                stringResource(R.string.floating_button_landscape_subtitle),
                button.hideInLandscape,
                default = defaults.hideInLandscape,
            ) { scope.launch { repository.setFloatingButtonHideInLandscape(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.floating_button_haptics_title,
                stringResource(R.string.floating_button_haptics_subtitle),
                button.haptics,
                default = defaults.haptics,
            ) { scope.launch { repository.setFloatingButtonHaptics(it) } }
        }
        item(visible = button.xFraction != defaults.xFraction || button.yFraction != defaults.yFraction) {
            NavRow(
                R.string.floating_button_reset_position_title,
                subtitle = stringResource(R.string.floating_button_reset_position_subtitle),
            ) { scope.launch { repository.resetSummonButtonPosition() } }
        }
    }

    /** What the gesture a pick answers for is bound to now; null for the menu's add. */
    fun currentFor(pick: FloatingPick): FloatingAction? = when (pick) {
        FloatingPick.TAP -> button.tap
        FloatingPick.DOUBLE_TAP -> button.doubleTap
        FloatingPick.HOLD -> button.hold
        FloatingPick.TAP_TYPING -> button.tapTyping
        FloatingPick.DOUBLE_TAP_TYPING -> button.doubleTapTyping
        FloatingPick.FLICK -> flickPicking?.let { button.flicks[it] }
        FloatingPick.MENU_ADD -> null
    }

    /** What a pick answers: the gesture's binding, or a slot added to the menu. */
    fun answer(pick: FloatingPick, action: FloatingAction?) {
        scope.launch {
            // Only the keyboard-up gestures offer "Same as", the one null.
            when {
                pick == FloatingPick.TAP_TYPING -> repository.setFloatingButtonTapTyping(action)
                pick == FloatingPick.DOUBLE_TAP_TYPING -> repository.setFloatingButtonDoubleTapTyping(action)
                pick == FloatingPick.FLICK -> flickPicking?.let { repository.setFloatingButtonFlick(it, action) }
                action == null -> Unit
                pick == FloatingPick.TAP -> repository.setFloatingButtonTap(action)
                pick == FloatingPick.DOUBLE_TAP -> repository.setFloatingButtonDoubleTap(action)
                pick == FloatingPick.HOLD -> repository.setFloatingButtonHold(action)
                else -> repository.setFloatingButtonMenu(button.menu + action)
            }
        }
    }

    picking?.let { pick ->
        val done = { picking = null }
        val gestureTitle = when (pick) {
            FloatingPick.TAP -> R.string.floating_button_tap_title
            FloatingPick.DOUBLE_TAP -> R.string.floating_button_double_tap_title
            FloatingPick.HOLD -> R.string.floating_button_hold_title
            FloatingPick.TAP_TYPING -> R.string.floating_button_tap_typing_title
            FloatingPick.DOUBLE_TAP_TYPING -> R.string.floating_button_double_tap_typing_title
            FloatingPick.FLICK -> flickPicking?.let(::flickTitle) ?: R.string.floating_button_docked_title
            FloatingPick.MENU_ADD -> 0
        }
        FloatingActionPicker(
            title = if (pick == FloatingPick.MENU_ADD) {
                stringResource(R.string.floating_button_menu_add_pick_title)
            } else {
                stringResource(R.string.floating_button_pick_title, stringResource(gestureTitle))
            },
            current = currentFor(pick),
            sameAs = when (pick) {
                FloatingPick.TAP_TYPING -> R.string.floating_button_tap_title
                FloatingPick.DOUBLE_TAP_TYPING -> R.string.floating_button_double_tap_title
                else -> null
            },
            keyboardUp = pick == FloatingPick.TAP_TYPING || pick == FloatingPick.DOUBLE_TAP_TYPING,
            forMenu = pick == FloatingPick.MENU_ADD,
            already = if (pick == FloatingPick.MENU_ADD) button.menu.toSet() else emptySet(),
            onDismiss = done,
            onPickApp = {
                done()
                pickingApp = pick
            },
            onPickPair = {
                done()
                pickingPair = pick
            },
            onNewCategory = if (pick == FloatingPick.MENU_ADD) {
                {
                    done()
                    scope.launch { editingCategory = repository.addFloatingButtonCategory(newCategoryName) }
                }
            } else {
                null
            },
            onPick = { action ->
                done()
                answer(pick, action)
            },
        )
    }

    pickingApp?.let { pick ->
        FloatingAppPicker(
            current = currentFor(pick),
            already = if (pick == FloatingPick.MENU_ADD) button.menu.toSet() else emptySet(),
            onDismiss = { pickingApp = null },
            onPick = { app ->
                pickingApp = null
                answer(pick, app)
            },
        )
    }

    pickingPair?.let { pick ->
        FloatingPairPicker(
            combos = combos,
            current = currentFor(pick),
            already = if (pick == FloatingPick.MENU_ADD) button.menu.toSet() else emptySet(),
            onDismiss = { pickingPair = null },
            onPick = { pair ->
                pickingPair = null
                answer(pick, pair)
            },
        )
    }

    editingCategory?.let { id ->
        // Null for the moment between a new category's write and its reading back.
        val category = button.categories.categoryById(id) ?: return@let
        FloatingCategoryPage(
            category = category,
            combos = combos,
            onChange = { scope.launch { repository.setFloatingButtonCategory(it) } },
            onRemove = {
                editingCategory = null
                scope.launch { repository.setFloatingButtonMenu(button.menu - FloatingAction.Category(id)) }
            },
            onDismiss = { editingCategory = null },
        )
    }
}

private fun styleLabel(style: FloatingButtonStyle): Int = when (style) {
    FloatingButtonStyle.AUTO -> R.string.floating_button_style_auto
    FloatingButtonStyle.DARK -> R.string.floating_button_style_dark
    FloatingButtonStyle.LIGHT -> R.string.floating_button_style_light
    FloatingButtonStyle.ACCENT -> R.string.floating_button_style_accent
}

private fun shapeLabel(shape: FloatingButtonShape): Int = when (shape) {
    FloatingButtonShape.CIRCLE -> R.string.floating_button_shape_circle
    FloatingButtonShape.SQUIRCLE -> R.string.floating_button_shape_squircle
    FloatingButtonShape.ROUNDED_SQUARE -> R.string.floating_button_shape_rounded_square
    FloatingButtonShape.OVAL -> R.string.floating_button_shape_oval
    FloatingButtonShape.DIAMOND -> R.string.floating_button_shape_diamond
    FloatingButtonShape.PENTAGON -> R.string.floating_button_shape_pentagon
    FloatingButtonShape.HEXAGON -> R.string.floating_button_shape_hexagon
    FloatingButtonShape.OCTAGON -> R.string.floating_button_shape_octagon
    FloatingButtonShape.STAR -> R.string.floating_button_shape_star
    FloatingButtonShape.HEART -> R.string.floating_button_shape_heart
}

private fun animationLabel(animation: FloatingMenuAnimation): Int = when (animation) {
    FloatingMenuAnimation.SPRING -> R.string.floating_button_menu_animation_spring
    FloatingMenuAnimation.POP -> R.string.floating_button_menu_animation_pop
    FloatingMenuAnimation.SWEEP -> R.string.floating_button_menu_animation_sweep
    FloatingMenuAnimation.FADE -> R.string.floating_button_menu_animation_fade
    FloatingMenuAnimation.NONE -> R.string.floating_button_menu_animation_none
}

/**
 * The page's preview: a sketch of a phone screen with the button on the side
 * it rests on and its quick menu fanned out, at the size, colours and opacity
 * set below, so a change is seen without leaving the page.
 */
@Composable
private fun FloatingButtonPreview(button: SummonButtonSettings) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val slots = button.menu.takeIf { FloatingAction.Menu in button.gestureActions() }
        .orEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                // Taller once a second ring is out (#1172), so the preview still holds it.
                .height(
                    when {
                        slots.size > FLOATING_RING_SIZE -> if (button.menuLabels) 460.dp else 400.dp
                        button.menuLabels -> 300.dp
                        else -> 260.dp
                    },
                )
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceContainerHighest)
                .alpha(if (button.enabled) 1f else 0.55f),
        ) {
            // Lines standing in for an app's content.
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0.7f, 0.9f, 0.5f, 0.8f, 0.6f, 0.85f).forEach { width ->
                    Box(
                        Modifier
                            .fillMaxWidth(width)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.onSurface.copy(alpha = 0.08f)),
                    )
                }
            }
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val disc = button.sizeDp
            val onLeft = button.xFraction < 0.5f
            val edge = with(density) { 10.dp.toPx() }
            val half = with(density) { (disc.dp / 2).toPx() }
            val anchor = Offset(
                if (onLeft) edge + half else widthPx - edge - half,
                heightPx * 0.55f,
            )
            val centers = remember(slots.size, widthPx, heightPx, anchor, button.menuLabels, disc, button.menuGapDp) {
                with(density) {
                    floatingRings(
                        count = slots.size,
                        anchor = anchor,
                        width = widthPx,
                        height = heightPx,
                        radius = (disc.dp / 2 + button.menuGapDp.dp + (FLOATING_MENU_ITEM_DP.dp / 2)).toPx(),
                        spacing = (if (button.menuLabels) 86.dp else 64.dp).toPx(),
                        margin = (FLOATING_MENU_ITEM_DP.dp / 2 + 8.dp + if (button.menuLabels) 18.dp else 0.dp).toPx(),
                    )
                }
            }
            val labelHalf = with(density) { (FLOATING_MENU_LABEL_DP.dp / 2).toPx() }
            val itemHalf = with(density) { (FLOATING_MENU_ITEM_DP.dp / 2).toPx() }
            slots.forEachIndexed { i, action ->
                val center = centers.getOrNull(i) ?: return@forEachIndexed
                FloatingMenuItem(
                    icon = floatingActionIcon(action, categories = button.categories),
                    image = rememberFloatingAppIcon(action as? FloatingAction.App, FLOATING_MENU_ITEM_DP),
                    label = floatingActionLabel(action, categories = button.categories),
                    style = button.style,
                    shape = button.shape,
                    showLabel = button.menuLabels,
                    hovered = i == 0,
                    modifier = Modifier.offset {
                        IntOffset((center.x - labelHalf).roundToInt(), (center.y - itemHalf).roundToInt())
                    },
                )
            }
            if (button.edgePill) {
                // Flat against the preview's edge, where the real one sits (#1137).
                val thick = with(density) { button.pillThicknessDp.dp.toPx() }
                val length = with(density) { button.pillLengthDp.dp.toPx() }
                FloatingEdgePill(
                    style = button.style,
                    thicknessDp = button.pillThicknessDp,
                    lengthDp = button.pillLengthDp,
                    onLeft = onLeft,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (if (onLeft) 0f else widthPx - thick).roundToInt(),
                                (anchor.y - length / 2).roundToInt(),
                            )
                        }
                        .alpha(button.opacity),
                )
            } else {
                FloatingButtonFace(
                    icon = floatingActionIcon(button.tap, categories = button.categories),
                    image = rememberFloatingAppIcon(button.tap as? FloatingAction.App, disc),
                    style = button.style,
                    shape = button.shape,
                    sizeDp = disc,
                    modifier = Modifier
                        .offset { IntOffset((anchor.x - half).roundToInt(), (anchor.y - half).roundToInt()) }
                        .alpha(button.opacity),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (button.enabled) {
                stringResource(
                    R.string.floating_button_preview_caption,
                    floatingActionLabel(button.tap, categories = button.categories),
                    floatingActionLabel(button.hold, categories = button.categories),
                )
            } else {
                stringResource(R.string.floating_button_preview_off)
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The quick menu's slots, or a category's, to reorder by their handles and
 * remove by their bins. A category's slot has a pencil that opens its page
 * (#923); an app's shows the app's own icon (#922).
 */
@Composable
private fun FloatingMenuEditor(
    items: List<FloatingAction>,
    categories: List<FloatingCategory>,
    onChange: (List<FloatingAction>) -> Unit,
    onEditCategory: ((String) -> Unit)? = null,
    @StringRes empty: Int = R.string.floating_button_menu_empty,
) {
    if (items.isEmpty()) {
        Text(
            stringResource(empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        return
    }
    val names = items.associateWith { floatingActionLabel(it, categories = categories) }
    ReorderableColumn(
        items = items,
        label = { names[it].orEmpty() },
        onReorder = onChange,
        onDelete = { gone -> onChange(items - gone) },
        keepLast = false,
    ) { action ->
        FloatingActionGlyph(action, Modifier.size(22.dp), categories)
        Spacer(Modifier.width(14.dp))
        Text(names[action].orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        val category = (action as? FloatingAction.Category)?.let { categories.categoryById(it.id) }
        if (category != null && onEditCategory != null) {
            IconButton(onClick = { onEditCategory(category.id) }) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.floating_button_category_edit, names[action].orEmpty()),
                )
            }
        }
    }
}

/**
 * An action's glyph in a list: an app's own icon (#922), a work app's badged
 * (#1138), else the glyph the button draws, a category's own among them.
 */
@Composable
private fun FloatingActionGlyph(
    action: FloatingAction,
    modifier: Modifier = Modifier,
    categories: List<FloatingCategory> = emptyList(),
) {
    val app = rememberFloatingAppIcon(action as? FloatingAction.App, 24) ?: rememberCustomButtonIcon(action, 24)
    if (app != null) {
        androidx.compose.foundation.Image(app, contentDescription = null, modifier = modifier)
    } else {
        Icon(
            floatingActionIcon(action, categories = categories),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

/**
 * A category's page (#923): its name, its actions in the order its ring
 * fans them out, and a way to add more, up to [MAX_FLOATING_MENU_ITEMS].
 * Every change is written as it is made.
 */
@Composable
private fun FloatingCategoryPage(
    category: FloatingCategory,
    combos: List<LauncherSplitCombo>,
    onChange: (FloatingCategory) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    var adding by remember { mutableStateOf(false) }
    var addingApp by remember { mutableStateOf(false) }
    var pickingIcon by remember { mutableStateOf(false) }
    var pickingPackIcon by remember { mutableStateOf(false) }
    var addingPair by remember { mutableStateOf(false) }
    val fallback = stringResource(R.string.floating_button_category_default_name)
    fun commitName() {
        val kept = name.trim().ifEmpty { fallback }
        if (kept != category.name) onChange(category.copy(name = kept))
    }
    fun add(action: FloatingAction) = onChange(category.copy(items = categoryItems(category.items + action)))
    val close = {
        commitName()
        onDismiss()
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = close) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_close))
                    }
                    Text(
                        name.ifBlank { fallback },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = close) { Text(stringResource(R.string.floating_button_category_done)) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.replace('\n', ' ').take(FLOATING_CATEGORY_NAME_MAX) },
                        label = { Text(stringResource(R.string.floating_button_category_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    // So categories can be told apart on the ring (#1138).
                    NavRow(
                        R.string.floating_button_category_icon_title,
                        subtitle = stringResource(R.string.floating_button_category_icon_subtitle),
                        icon = ModeIcons.iconOrNull(category.icon) ?: Icons.Outlined.Folder,
                    ) { pickingIcon = true }
                    // Or one out of an icon pack installed on the phone (#1275).
                    NavRow(
                        R.string.floating_button_category_pack_icon_title,
                        subtitle = stringResource(R.string.floating_button_category_pack_icon_subtitle),
                    ) { pickingPackIcon = true }
                    Text(
                        stringResource(R.string.floating_button_category_count, category.items.size, MAX_FLOATING_MENU_ITEMS),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    FloatingMenuEditor(
                        items = category.items,
                        categories = emptyList(),
                        onChange = { onChange(category.copy(items = categoryItems(it))) },
                        empty = R.string.floating_button_category_empty,
                    )
                    val full = category.items.size >= MAX_FLOATING_MENU_ITEMS
                    NavRow(
                        R.string.floating_button_category_add_title,
                        subtitle = if (full) {
                            stringResource(R.string.floating_button_menu_full_subtitle)
                        } else {
                            stringResource(R.string.floating_button_menu_add_subtitle, MAX_FLOATING_MENU_ITEMS)
                        },
                    ) { if (!full) adding = true }
                    NavRow(
                        R.string.floating_button_category_remove_title,
                        subtitle = stringResource(R.string.floating_button_category_remove_subtitle),
                        icon = Icons.Outlined.Delete,
                    ) { onRemove() }
                }
            }
        }
    }
    if (adding) {
        FloatingActionPicker(
            title = stringResource(R.string.floating_button_category_add_pick_title, name.ifBlank { fallback }),
            current = null,
            forMenu = true,
            forCategory = true,
            already = category.items.toSet(),
            onDismiss = { adding = false },
            onPickApp = {
                adding = false
                addingApp = true
            },
            onPickPair = {
                adding = false
                addingPair = true
            },
            onPick = { action ->
                adding = false
                if (action != null) add(action)
            },
        )
    }
    if (pickingIcon) {
        ModeIconPickerDialog(
            selected = category.icon,
            onPick = { id ->
                pickingIcon = false
                onChange(category.copy(icon = id))
            },
            onDismiss = { pickingIcon = false },
            title = stringResource(R.string.floating_button_category_icon_picker_title),
            clearLabel = stringResource(R.string.floating_button_category_icon_none),
        )
    }
    if (pickingPackIcon) {
        val packs = rememberSystemIconPacks()
        AlertDialog(
            onDismissRequest = { pickingPackIcon = false },
            title = { Text(stringResource(R.string.floating_button_category_icon_picker_title)) },
            text = {
                SystemPackIconGrid(
                    packs = packs,
                    selected = category.icon,
                    onPick = { source ->
                        pickingPackIcon = false
                        onChange(category.copy(icon = source))
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pickingPackIcon = false
                    onChange(category.copy(icon = null))
                }) { Text(stringResource(R.string.floating_button_category_icon_none)) }
            },
        )
    }
    if (addingPair) {
        FloatingPairPicker(
            combos = combos,
            current = null,
            already = category.items.toSet(),
            onDismiss = { addingPair = false },
            onPick = { pair ->
                addingPair = false
                add(pair)
            },
        )
    }
    if (addingApp) {
        FloatingAppPicker(
            current = null,
            already = category.items.toSet(),
            onDismiss = { addingApp = false },
            onPick = { app ->
                addingApp = false
                add(app)
            },
        )
    }
}

/**
 * Picks the app an action opens (#922): every app with a launcher entry,
 * the list the app launcher tool shows, searchable by name or package. The
 * work profile's apps follow in a group of their own (#1138), each opened
 * in that profile.
 */
@Composable
private fun FloatingAppPicker(
    current: FloatingAction?,
    already: Set<FloatingAction>,
    onDismiss: () -> Unit,
    onPick: (FloatingAction.App) -> Unit,
) {
    val context = LocalContext.current
    val apps by produceState<List<com.wasimaster.wmkeyboard.ime.LauncherApp>?>(null, context) {
        value = AppCatalog.loadAllApps(context, otherProfiles = true)
            .filter { it.otherProfile || it.packageName != context.packageName }
    }
    val loaded = apps ?: return
    fun rowsOf(list: List<com.wasimaster.wmkeyboard.ime.LauncherApp>) =
        list.map { FloatingAction.App(it.packageName, it.profile) to it }
            .filter { (action, _) -> action !in already }
            .map { (action, app) ->
                PickerRow(
                    key = app.key,
                    title = app.label,
                    subtitle = app.packageName,
                    selected = current == action,
                    leading = { FloatingActionGlyph(action, Modifier.size(32.dp)) },
                    onPick = { onPick(action) },
                )
            }
    val (work, own) = loaded.partition { it.otherProfile }
    val rows = rowsOf(own)
    val workRows = rowsOf(work)
    ActionPickerPage(
        memoryKey = "floating-app",
        title = stringResource(R.string.floating_button_app_picker_title),
        groups = if (rows.isEmpty() && workRows.isEmpty()) {
            listOf(
                PickerGroup(
                    "empty",
                    null,
                    listOf(PickerRow("empty", stringResource(R.string.floating_button_app_picker_empty), onPick = onDismiss)),
                ),
            )
        } else {
            listOfNotNull(
                PickerGroup("apps", stringResource(R.string.floating_button_app_picker_group), rows)
                    .takeIf { rows.isNotEmpty() },
                PickerGroup("work", stringResource(R.string.floating_button_app_picker_work_group), workRows)
                    .takeIf { workRows.isNotEmpty() },
            )
        },
        onDismiss = onDismiss,
    )
}

/**
 * Picks the app pair an action opens (#1140): the app launcher's split
 * screen pairs, by the name each was given or else by both apps' names.
 * Pairs are made in the launcher, where the apps are laid out to pick from,
 * so with none yet the one row says where.
 */
@Composable
private fun FloatingPairPicker(
    combos: List<LauncherSplitCombo>,
    current: FloatingAction?,
    already: Set<FloatingAction>,
    onDismiss: () -> Unit,
    onPick: (FloatingAction.AppPair) -> Unit,
) {
    val rows = combos.map { FloatingAction.AppPair(it.first, it.second) to it }
        .distinctBy { it.first }
        .filter { (action, _) -> action !in already }
        .map { (action, combo) ->
            val both = floatingActionLabel(action)
            PickerRow(
                key = action.token,
                title = combo.name.ifBlank { both },
                subtitle = both.takeIf { combo.name.isNotBlank() },
                selected = current == action,
                leading = { Icon(Icons.Outlined.VerticalSplit, contentDescription = null) },
                onPick = { onPick(action) },
            )
        }
    ActionPickerPage(
        memoryKey = "floating-pair",
        title = stringResource(R.string.floating_button_pair_picker_title),
        groups = if (rows.isEmpty()) {
            listOf(
                PickerGroup(
                    "empty",
                    null,
                    listOf(
                        PickerRow(
                            "empty",
                            stringResource(R.string.floating_button_pair_picker_empty),
                            subtitle = stringResource(R.string.floating_button_pair_picker_empty_subtitle),
                            onPick = onDismiss,
                        ),
                    ),
                ),
            )
        } else {
            listOf(PickerGroup("pairs", stringResource(R.string.floating_button_pair_picker_group), rows))
        },
        onDismiss = onDismiss,
    )
}

/**
 * Picks what a gesture does, or what the quick menu gains: the button's own
 * actions first, then every tool, grouped as the Tools screen groups them.
 * [forMenu] leaves out what a menu slot cannot be ([fitsMenu]) and what the
 * menu [already] holds; [forCategory], what a category's cannot ([fitsCategory]).
 * With [sameAs], a keyboard-up gesture's picker (#626), the first row hands
 * the gesture back to the one it names, as null.
 *
 * Three rows open something else instead of answering: "Open an app" hands
 * over to [onPickApp] (#922), "Open an app pair" to [onPickPair] (#1140), and
 * with [onNewCategory] "New category" makes one (#923).
 */
@Composable
private fun FloatingActionPicker(
    title: String,
    current: FloatingAction?,
    forMenu: Boolean,
    already: Set<FloatingAction>,
    onDismiss: () -> Unit,
    onPick: (FloatingAction?) -> Unit,
    onPickApp: () -> Unit,
    onPickPair: () -> Unit,
    @StringRes sameAs: Int? = null,
    keyboardUp: Boolean = false,
    forCategory: Boolean = false,
    onNewCategory: (() -> Unit)? = null,
) {
    fun offered(action: FloatingAction) =
        (!forMenu || action.fitsMenu()) && (!forCategory || action.fitsCategory()) && action !in already
    val own = listOf(
        FloatingAction.Keyboard to R.string.floating_button_pick_keyboard_subtitle,
        FloatingAction.AskAi to R.string.floating_button_pick_ai_subtitle,
        FloatingAction.LookUp to R.string.floating_button_pick_lookup_subtitle,
        FloatingAction.Menu to R.string.floating_button_pick_menu_subtitle,
        FloatingAction.Hide to R.string.floating_button_pick_hide_subtitle,
        FloatingAction.None to R.string.floating_button_pick_none_subtitle,
    ).filter { (action, _) -> offered(action) }
        // Look up reads the screen, which the Play build cannot (#765).
        .filter { (action, _) -> action != FloatingAction.LookUp || ChannelFeatures.SCREEN_CONTEXT }
    val sameRow = sameAs?.let { gesture ->
        PickerRow(
            key = "same",
            title = stringResource(R.string.floating_button_same_as, stringResource(gesture)),
            subtitle = stringResource(R.string.floating_button_pick_same_subtitle),
            selected = current == null,
            onPick = { onPick(null) },
        )
    }
    val appRow = PickerRow(
        key = "app",
        title = stringResource(R.string.floating_button_pick_app),
        // Bound to one already, the row says which.
        subtitle = (current as? FloatingAction.App)?.let { floatingActionLabel(it) }
            ?: stringResource(R.string.floating_button_pick_app_subtitle),
        selected = current is FloatingAction.App,
        leading = { Icon(Icons.Outlined.OpenInNew, contentDescription = null) },
        onPick = onPickApp,
    )
    val pairRow = PickerRow(
        key = "pair",
        title = stringResource(R.string.floating_button_pick_pair),
        subtitle = (current as? FloatingAction.AppPair)?.let { floatingActionLabel(it) }
            ?: stringResource(R.string.floating_button_pick_pair_subtitle),
        selected = current is FloatingAction.AppPair,
        leading = { Icon(Icons.Outlined.VerticalSplit, contentDescription = null) },
        onPick = onPickPair,
    )
    val categoryRow = onNewCategory?.let { make ->
        PickerRow(
            key = "category",
            title = stringResource(R.string.floating_button_pick_category_new),
            subtitle = stringResource(R.string.floating_button_pick_category_new_subtitle),
            leading = { Icon(Icons.Outlined.Folder, contentDescription = null) },
            onPick = make,
        )
    }
    val ownRows = listOfNotNull(sameRow) + own.map { (action, subtitle) ->
        PickerRow(
            key = action.token,
            title = floatingActionLabel(action, keyboardUp),
            subtitle = stringResource(subtitle),
            selected = current == action,
            leading = { Icon(floatingActionIcon(action), contentDescription = null) },
            onPick = { onPick(action) },
        )
    } + listOfNotNull(appRow, pairRow, categoryRow)
    val supported = ToolbarTool.entries.filter { isSupportedTool(it) && offered(FloatingAction.Tool(it)) }.toSet()
    val grouped = ToolGroups.withExtensions(LocalExtensions.current)
        .map { (titleRes, tools) -> titleRes to tools.filter { it in supported } }
    val placed = grouped.flatMapTo(HashSet()) { it.second }
    val leftovers = supported.filter { it !in placed }
    @Composable
    fun toolRow(tool: ToolbarTool) = PickerRow(
        key = tool.name,
        title = stringResource(toolTitle(tool)),
        subtitle = stringResource(toolDescription(tool)),
        selected = current == FloatingAction.Tool(tool),
        leading = { SlotIcon(IconSlots.forTool(tool), contentDescription = null) },
        onPick = { onPick(FloatingAction.Tool(tool)) },
    )
    // Android's own system buttons (#1170), once their service is on; before
    // that, one row that says what the service is and opens its switch.
    val systemOn = rememberGrantState { SystemButtonsService.isEnabled(it) }
    val askSystem = rememberDisclosedSpecialAccess(SpecialAccess.SYSTEM_BUTTONS)
    val systemRows = when {
        !ChannelFeatures.SYSTEM_BUTTONS -> emptyList()
        !systemOn -> listOf(
            PickerRow(
                key = "system-off",
                title = stringResource(R.string.floating_button_pick_system_off_title),
                subtitle = stringResource(R.string.floating_button_pick_system_off_subtitle),
                onPick = askSystem,
            ),
        )
        else -> SystemButton.entries
            .filter { it.available && offered(FloatingAction.System(it)) }
            .map { button ->
                val action = FloatingAction.System(button)
                PickerRow(
                    key = action.token,
                    title = floatingActionLabel(action),
                    subtitle = stringResource(R.string.floating_button_pick_system_subtitle),
                    selected = current == action,
                    leading = { Icon(floatingActionIcon(action), contentDescription = null) },
                    onPick = { onPick(action) },
                )
            }
    }
    // The user's own buttons (#1213, #1236), made on Tools › Custom buttons.
    val customRows = com.wasimaster.wmkeyboard.ime.ui.CustomButtonFaces.buttons
        .filter { it.usable }
        .map { FloatingAction.Custom(it.id) }
        .filter { offered(it) }
        .map { action ->
            PickerRow(
                key = action.token,
                title = floatingActionLabel(action),
                subtitle = stringResource(R.string.floating_button_pick_custom_subtitle),
                selected = current == action,
                leading = { FloatingActionGlyph(action, Modifier.size(24.dp)) },
                onPick = { onPick(action) },
            )
        }
    // A tool straight into a floating box over the app (#1155).
    val appletRows = AppletTools
        .filter { isSupportedTool(it) && offered(FloatingAction.Applet(it)) }
        .map { tool ->
            val action = FloatingAction.Applet(tool)
            PickerRow(
                key = action.token,
                title = floatingActionLabel(action),
                subtitle = stringResource(R.string.floating_button_pick_applet_subtitle),
                selected = current == action,
                leading = { SlotIcon(IconSlots.forTool(tool), contentDescription = null) },
                onPick = { onPick(action) },
            )
        }
    val groups = buildList {
        if (ownRows.isNotEmpty()) {
            add(PickerGroup("own", stringResource(R.string.floating_button_pick_group_button), ownRows))
        }
        if (systemRows.isNotEmpty()) {
            add(PickerGroup("system", stringResource(R.string.floating_button_pick_group_system), systemRows))
        }
        if (customRows.isNotEmpty()) {
            add(PickerGroup("custom", stringResource(R.string.floating_button_pick_group_custom), customRows))
        }
        if (appletRows.isNotEmpty()) {
            add(PickerGroup("applets", stringResource(R.string.floating_button_pick_group_applets), appletRows))
        }
        for ((titleRes, tools) in grouped) {
            if (tools.isEmpty()) continue
            add(PickerGroup("group-$titleRes", stringResource(titleRes), tools.map { toolRow(it) }))
        }
        if (leftovers.isNotEmpty()) {
            add(PickerGroup("group-other", stringResource(R.string.tools_group_other_title), leftovers.map { toolRow(it) }))
        }
    }
    ActionPickerPage(
        memoryKey = when {
            forCategory -> "floating-category"
            forMenu -> "floating-menu"
            else -> "floating-gesture"
        },
        title = title,
        groups = groups,
        onDismiss = onDismiss,
    )
}

/** A shortcut button's own picture for a list row (#1213), or null for anything else. */
@Composable
private fun rememberCustomButtonIcon(action: FloatingAction, sizeDp: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val id = (action as? FloatingAction.Custom)?.id ?: return null
    val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, id, px) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.wasimaster.wmkeyboard.ime.ui.CustomButtonFaces.byId(id)
                ?.let { com.wasimaster.wmkeyboard.ime.ui.CustomButtonFaces.icon(context, it, px) }
        }
    }
    return icon
}
