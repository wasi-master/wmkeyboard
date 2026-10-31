package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.wasimaster.wmkeyboard.core.layout.isShippedLayoutId
import com.wasimaster.wmkeyboard.core.settings.HintOffsetRange
import com.wasimaster.wmkeyboard.core.settings.LabelOffsetRange
import com.wasimaster.wmkeyboard.core.settings.ArrowKey
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.settings.BottomRowHeightRange
import com.wasimaster.wmkeyboard.core.settings.GlobeTypingGuardMsRange
import com.wasimaster.wmkeyboard.core.settings.BoardCorner
import com.wasimaster.wmkeyboard.core.settings.BoardCornerRadiusRange
import com.wasimaster.wmkeyboard.core.settings.SidePadScaleRange
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import com.wasimaster.wmkeyboard.ime.ui.arrowRowKey
import com.wasimaster.wmkeyboard.ime.ui.gestureBarAtBottom
import com.wasimaster.wmkeyboard.ime.ui.keyboardCanHideSystemKeyboardBar
import com.wasimaster.wmkeyboard.core.settings.autoBottomPaddingDp
import com.wasimaster.wmkeyboard.core.settings.KeyboardAlignment
import com.wasimaster.wmkeyboard.core.settings.FloatingHandlePosition
import com.wasimaster.wmkeyboard.core.settings.floatingFor
import com.wasimaster.wmkeyboard.core.script.NumeralCommitScope
import com.wasimaster.wmkeyboard.core.settings.DefaultToolbarTools
import com.wasimaster.wmkeyboard.core.settings.KeyFontScaleRange
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.OneHandedSide
import com.wasimaster.wmkeyboard.core.settings.ScreenVariant
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sizingValuesFor
import com.wasimaster.wmkeyboard.core.settings.oneHandedAllowedOn
import com.wasimaster.wmkeyboard.core.settings.splitAllowedOn
import com.wasimaster.wmkeyboard.core.settings.ToolbarColumn
import com.wasimaster.wmkeyboard.core.settings.ToolbarPlacement
import com.wasimaster.wmkeyboard.core.settings.ToolboxLayout
import com.wasimaster.wmkeyboard.core.settings.ToolboxSort
import com.wasimaster.wmkeyboard.core.settings.ToolboxPageSizeRange
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewColumn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewWeek
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.ui.DisabledReason

/**
 * Restores the toolbar's default pins ([DefaultToolbarTools]) from Settings —
 * the global set. A mode's own pinned toolbar is reset from that mode's
 * editor (Keyboard modes → the mode → turn off "Custom pinned tools").
 * Confirms first, since it discards whatever the user dragged onto the bar.
 */
@Composable
private fun ResetPinnedToolsSetting(repository: SettingsRepository, scope: CoroutineScope) {
    var confirm by remember { mutableStateOf(false) }
    val title = stringResource(R.string.home_reset_pinned_tools_title)
    HighlightableRow(title) {
        WmRow(
            title = title,
            subtitle = stringResource(R.string.home_reset_pinned_tools_subtitle),
            icon = SettingsRowIcons[R.string.home_reset_pinned_tools_title],
            trailing = {
                OutlinedButton(onClick = { confirm = true }) {
                    Text(stringResource(CommonR.string.common_reset))
                }
            },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.home_reset_pinned_tools_confirm_title)) },
            text = { Text(stringResource(R.string.home_reset_pinned_tools_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { repository.setToolbarTools(DefaultToolbarTools) }
                }) { Text(stringResource(CommonR.string.common_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
// ---- appearance ----

@Composable
internal fun AppearanceSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onOpenThemes: () -> Unit,
    onOpenFonts: () -> Unit,
    onOpenIcons: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val dpFormat = stringResource(R.string.typing_value_dp)
    val multiplierFormat = stringResource(R.string.keypress_value_multiplier)
    // What decides which rows the groups hold; each row reads its own value.
    val appearanceMoved = settings.watch { s ->
        val d = SettingsDefaults
        s.keyCornerRadiusDp != d.keyCornerRadiusDp ||
            s.fontScale != d.fontScale ||
            s.layoutBehavior.hintFontScale != d.layoutBehavior.hintFontScale ||
            s.layoutBehavior.hintOffsetDp != d.layoutBehavior.hintOffsetDp ||
            s.layoutBehavior.hintOffsetXDp != d.layoutBehavior.hintOffsetXDp ||
            s.layoutBehavior.labelOffsetXDp != d.layoutBehavior.labelOffsetXDp ||
            s.layoutBehavior.labelOffsetYDp != d.layoutBehavior.labelOffsetYDp
    }
    // Turning the toolbar off is guarded — it hides suggestions and every tool.
    SettingsGroup(stringResource(R.string.appearance_style_section_title)) {
        item {
            val selected = settings.watch {
                com.wasimaster.wmkeyboard.core.theme.findThemeSpec(
                    it.keyboardThemeId,
                    it.customThemes,
                )
            }
            NavRow(
                R.string.appearance_themes_title,
                stringResource(R.string.appearance_themes_subtitle),
                value = if (selected == null) {
                    stringResource(CommonR.string.common_default)
                } else {
                    com.wasimaster.wmkeyboard.core.theme.themeName(selected)
                },
                route = "themes",
                onClick = onOpenThemes,
            )
        }
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.appearance_font_title,
                stringResource(R.string.appearance_font_subtitle),
                value = KeyboardFonts.genericDisplayName(
                    LocalContext.current,
                    settings.watch { it.keyFontId },
                    settings.watch { it.customFontName },
                ),
                route = "fonts",
                onClick = onOpenFonts,
            )
        }
        item(level = SettingLevel.STANDARD) {
            val active = settings.watch { it.icons.activePackId }
            val changed = settings.watch { it.icons.overrides.size }
            val defaultLabel = stringResource(CommonR.string.common_default)
            NavRow(
                R.string.appearance_icons_title,
                stringResource(R.string.appearance_icons_subtitle),
                value = when {
                    active.isNotEmpty() ->
                        IconPackStore.get(LocalContext.current).pack(active)?.name ?: defaultLabel
                    changed > 0 -> pluralStringResource(
                        R.plurals.appearance_icons_changed_count,
                        changed,
                        changed,
                    )
                    else -> defaultLabel
                },
                route = "icons",
                onClick = onOpenIcons,
            )
        }
    }

    SettingsGroup(stringResource(R.string.appearance_keys_section_title)) {
        item(level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.appearance_key_corner_radius_title,
                subtitle = stringResource(R.string.appearance_key_corner_radius_subtitle),
                value = settings.watch { it.keyCornerRadiusDp }.toFloat(),
                range = 0f..28f,
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.appearance_key_corner_radius_info),
                default = SettingsDefaults.keyCornerRadiusDp.toFloat(),
            ) { scope.launch { repository.setKeyCornerRadiusDp(it.toInt()) } }
        }
        // A row the active theme's "Layout for this theme" group replaces says
        // so and stands down, here and on every sizing row below (#88); see
        // [themePin].
        item(level = SettingLevel.STANDARD) {
            val pin = themePin(settings) { it.fontScale }
            SliderSetting(
                R.string.appearance_key_label_size_title,
                subtitle = pin?.text ?: stringResource(R.string.appearance_key_label_size_subtitle),
                value = settings.watch { it.fontScale },
                range = KeyFontScaleRange,
                display = { multiplierFormat.format(it) },
                info = stringResource(R.string.appearance_key_label_size_info),
                enabled = pin == null,
                disabledReason = pin,
                default = SettingsDefaults.fontScale,
            ) { scope.launch { repository.setFontScale(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            val pin = themePin(settings) { it.hintFontScale }
            SliderSetting(
                R.string.appearance_key_hint_size_title,
                subtitle = pin?.text ?: stringResource(R.string.appearance_key_hint_size_subtitle),
                value = settings.watch { it.layoutBehavior.hintFontScale },
                // To ×3 (#970): an Arabic haraka at ×2 was still a speck.
                range = 0.5f..3.0f,
                display = { multiplierFormat.format(it) },
                info = stringResource(R.string.appearance_key_hint_size_info),
                enabled = pin == null,
                disabledReason = pin,
                default = SettingsDefaults.layoutBehavior.hintFontScale,
            ) { scope.launch { repository.setHintFontScale(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_key_hint_offset_title,
                subtitle = stringResource(R.string.appearance_key_hint_offset_subtitle),
                value = settings.watch { it.layoutBehavior.hintOffsetDp }.toFloat(),
                // Below 0 too (#970): a large hint can sit right up in the corner.
                range = HintOffsetRange.first.toFloat()..HintOffsetRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_key_hint_offset_info),
                default = SettingsDefaults.layoutBehavior.hintOffsetDp.toFloat(),
            ) { scope.launch { repository.setHintOffsetDp(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_key_hint_offset_x_title,
                subtitle = stringResource(R.string.appearance_key_hint_offset_x_subtitle),
                value = settings.watch { it.layoutBehavior.hintOffsetXDp }.toFloat(),
                range = HintOffsetRange.first.toFloat()..HintOffsetRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                default = SettingsDefaults.layoutBehavior.hintOffsetXDp.toFloat(),
            ) { scope.launch { repository.setHintOffsetXDp(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_key_label_offset_x_title,
                subtitle = stringResource(R.string.appearance_key_label_offset_x_subtitle),
                value = settings.watch { it.layoutBehavior.labelOffsetXDp }.toFloat(),
                range = LabelOffsetRange.first.toFloat()..LabelOffsetRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                default = SettingsDefaults.layoutBehavior.labelOffsetXDp.toFloat(),
            ) { scope.launch { repository.setLabelOffsetXDp(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_key_label_offset_y_title,
                subtitle = stringResource(R.string.appearance_key_label_offset_y_subtitle),
                value = settings.watch { it.layoutBehavior.labelOffsetYDp }.toFloat(),
                range = LabelOffsetRange.first.toFloat()..LabelOffsetRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                default = SettingsDefaults.layoutBehavior.labelOffsetYDp.toFloat(),
            ) { scope.launch { repository.setLabelOffsetYDp(it.roundToInt()) } }
        }
        // Drawn only once something has actually moved. Theme, font and icons
        // are excluded: they lead to their own screens and are not what "reset
        // the sliders" means. The toolbar and toolbox pages reset themselves.
        item(visible = appearanceMoved, level = SettingLevel.STANDARD) {
            ActionRow(
                title = R.string.appearance_reset_title,
                subtitle = stringResource(R.string.appearance_reset_subtitle),
                action = stringResource(CommonR.string.common_reset),
                confirm = stringResource(R.string.appearance_reset_confirm),
            ) { scope.launch { repository.resetAppearance() } }
        }
    }

    SettingsGroup {
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.appearance_toolbar_section_title,
                stringResource(R.string.appearance_toolbar_section_subtitle),
                route = "appearance/toolbar",
            ) {
                onNavigate("appearance/toolbar")
            }
        }
        item(level = SettingLevel.ADVANCED) {
            NavRow(
                R.string.appearance_toolbox_section_title,
                stringResource(R.string.appearance_toolbox_section_subtitle),
                route = "appearance/toolbox",
            ) {
                onNavigate("appearance/toolbox")
            }
        }
    }

}

@Composable
@Suppress("UnusedParameter")
internal fun AppearanceToolbarSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dpFormat = stringResource(R.string.typing_value_dp)
    val spFormat = stringResource(R.string.values_sp)
    val percentFormat = stringResource(R.string.typing_value_percent)
    var confirmDisableToolbar by remember { mutableStateOf(false) }
    var toolShapePickerOpen by rememberSaveable { mutableStateOf(false) }
    // What decides which rows the group holds; each row reads its own value.
    val toolbarOn = settings.watch { it.toolbarBehavior.enabled }
    val toolbarOnlyHw = settings.watch { it.toolbarBehavior.onlyWithHardwareKeyboard }
    val placement = settings.watch { it.toolbarBehavior.placement }
    val labelsOn = settings.watch { it.toolbarLabels }
    val toolCircleOn = settings.watch { it.toolCircleRadiusDp > 0 }
    val toolbarMoved = settings.watch { s ->
        val d = SettingsDefaults
        s.toolbarBehavior != d.toolbarBehavior ||
            s.toolbarHeightDp != d.toolbarHeightDp ||
            s.toolbarLabels != d.toolbarLabels ||
            s.toolbarLabelSize != d.toolbarLabelSize ||
            s.suggestionStrip.textScale != d.suggestionStrip.textScale ||
            s.suggestionStrip.chipPadding != d.suggestionStrip.chipPadding ||
            s.suggestionStrip.primaryColor != d.suggestionStrip.primaryColor ||
            s.toolCircleRadiusDp != d.toolCircleRadiusDp ||
            s.toolShape != d.toolShape
    }
    if (toolShapePickerOpen) {
        KeyShapePickerDialog(
            selected = settings.watch { it.toolShape },
            radiusDp = settings.watch { it.toolCircleRadiusDp },
            onPick = { kind ->
                scope.launch { repository.setToolShape(kind) }
                toolShapePickerOpen = false
            },
            onDismiss = { toolShapePickerOpen = false },
            title = R.string.appearance_tool_shape_title,
        )
    }
    if (confirmDisableToolbar) {
        AlertDialog(
            onDismissRequest = { confirmDisableToolbar = false },
            title = { Text(stringResource(R.string.appearance_toolbar_disable_dialog_title)) },
            text = { Text(stringResource(R.string.appearance_toolbar_disable_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisableToolbar = false
                    scope.launch { repository.setToolbarEnabled(false) }
                }) { Text(stringResource(CommonR.string.common_disable)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisableToolbar = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
    SettingsGroup(stringResource(R.string.appearance_toolbar_section_title)) {
        item {
            ToggleSetting(
                R.string.appearance_toolbar_show_title,
                stringResource(R.string.appearance_toolbar_show_subtitle),
                toolbarOn,
                info = stringResource(R.string.appearance_toolbar_show_info),
                default = SettingsDefaults.toolbarBehavior.enabled,
            ) { on ->
                // Enabling is harmless; disabling loses real features, so confirm.
                if (on) scope.launch { repository.setToolbarEnabled(true) }
                else confirmDisableToolbar = true
            }
        }
        // Where the tools live: sharing the suggestion strip, or on a row of
        // their own so they are in reach whatever the strip is showing.
        if (toolbarOn) {
            item(level = SettingLevel.STANDARD) {
                ChoiceSetting(
                    title = R.string.appearance_toolbar_placement_title,
                    subtitle = stringResource(R.string.appearance_toolbar_placement_subtitle),
                    info = stringResource(R.string.appearance_toolbar_placement_info),
                    options = listOf(
                        ToolbarPlacement.STRIP to
                            stringResource(R.string.appearance_toolbar_placement_strip_label),
                        ToolbarPlacement.ON_DEMAND_ROW to
                            stringResource(R.string.appearance_toolbar_placement_button_label),
                        ToolbarPlacement.ALWAYS_ROW to
                            stringResource(R.string.appearance_toolbar_placement_always_label),
                    ),
                    selected = placement,
                    detail = { placement -> ChoiceDetail(stringResource(toolbarPlacementDescRes(placement))) },
                    onChange = { scope.launch { repository.setToolbarPlacement(it) } },
                    default = SettingsDefaults.toolbarBehavior.placement,
                )
            }
            // Only under Always: with A button the strip's arrow is the way
            // into the tools row, so the strip cannot go (#302).
            if (placement == ToolbarPlacement.ALWAYS_ROW) item {
                ToggleSetting(
                    R.string.appearance_toolbar_show_strip_title,
                    stringResource(R.string.appearance_toolbar_show_strip_subtitle),
                    settings.watch { it.toolbarBehavior.showStrip },
                    info = stringResource(R.string.appearance_toolbar_show_strip_info),
                    default = SettingsDefaults.toolbarBehavior.showStrip,
                ) { scope.launch { repository.setToolbarShowStrip(it) } }
            }
            // Sideways only (#970): a column down one side of the keys in
            // place of anything along the top, for a screen with width to
            // spare and little height.
            item(level = SettingLevel.STANDARD) {
                ChoiceSetting(
                    title = R.string.appearance_toolbar_landscape_column_title,
                    subtitle = stringResource(R.string.appearance_toolbar_landscape_column_subtitle),
                    info = stringResource(R.string.appearance_toolbar_landscape_column_info),
                    options = listOf(
                        ToolbarColumn.OFF to
                            stringResource(R.string.appearance_toolbar_landscape_column_off_label),
                        ToolbarColumn.LEFT to
                            stringResource(R.string.appearance_toolbar_landscape_column_left_label),
                        ToolbarColumn.RIGHT to
                            stringResource(R.string.appearance_toolbar_landscape_column_right_label),
                    ),
                    selected = settings.watch { it.toolbarBehavior.landscapeColumn },
                    onChange = { scope.launch { repository.setToolbarLandscapeColumn(it) } },
                    default = SettingsDefaults.toolbarBehavior.landscapeColumn,
                )
            }
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.appearance_toolbar_swipe_down_title,
                    stringResource(R.string.appearance_toolbar_swipe_down_subtitle),
                    settings.watch { it.toolbarBehavior.swipeDownHide },
                    info = stringResource(R.string.appearance_toolbar_swipe_down_info),
                    default = SettingsDefaults.toolbarBehavior.swipeDownHide,
                ) { scope.launch { repository.setToolbarSwipeDownHide(it) } }
            }
            // A hold that drifts into a drag is the one gesture the bar cannot
            // tell from a hold meant to stay put, so the drag can be given up
            // altogether (#136); the toolbox still rearranges the bar.
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.appearance_toolbar_drag_title,
                    stringResource(R.string.appearance_toolbar_drag_subtitle),
                    settings.watch { it.toolbarBehavior.dragToRearrange },
                    info = stringResource(R.string.appearance_toolbar_drag_info),
                    default = SettingsDefaults.toolbarBehavior.dragToRearrange,
                ) { scope.launch { repository.setToolbarDragToRearrange(it) } }
            }
            // One wait for every hold on a tool (#665): the bound action, the
            // settings page and the pick-up all start from it. The bottom of
            // the track is the system's own delay, which is the default.
            item(level = SettingLevel.ADVANCED) {
                val context = LocalContext.current
                SliderSetting(
                    R.string.appearance_toolbar_hold_delay_title,
                    subtitle = stringResource(R.string.appearance_toolbar_hold_delay_subtitle),
                    value = settings.watch { it.toolbarBehavior.holdDelayMs }.toFloat(),
                    range = 0f..1500f,
                    display = {
                        val ms = toolbarHoldDelayStep(it)
                        if (ms == 0) {
                            context.getString(R.string.appearance_toolbar_hold_delay_system)
                        } else {
                            context.getString(R.string.keypress_value_ms, ms)
                        }
                    },
                    info = stringResource(R.string.appearance_toolbar_hold_delay_info),
                    default = SettingsDefaults.toolbarBehavior.holdDelayMs.toFloat(),
                ) { scope.launch { repository.setToolbarHoldDelayMs(toolbarHoldDelayStep(it)) } }
            }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.appearance_toolbar_hardware_only_title,
                stringResource(R.string.appearance_toolbar_hardware_only_subtitle),
                settings.watch { it.toolbarBehavior.onlyWithHardwareKeyboard },
                info = stringResource(R.string.appearance_toolbar_hardware_only_info),
                default = SettingsDefaults.toolbarBehavior.onlyWithHardwareKeyboard,
            ) { scope.launch { repository.setToolbarOnlyWithHardwareKeyboard(it) } }
        }
        // The toolbar's show-keys button only exists in the toolbar-only view,
        // and so does the question of when its keys go away again (#642).
        if (toolbarOn && toolbarOnlyHw) item {
            ToggleSetting(
                R.string.appearance_toolbar_hardware_auto_hide_title,
                stringResource(R.string.appearance_toolbar_hardware_auto_hide_subtitle),
                settings.watch { it.toolbarBehavior.hardwareKeysAutoHide },
                info = stringResource(R.string.appearance_toolbar_hardware_auto_hide_info),
                default = SettingsDefaults.toolbarBehavior.hardwareKeysAutoHide,
            ) { scope.launch { repository.setHardwareKeysAutoHide(it) } }
        }
        // Nothing here reaches the board while the toolbar is switched off:
        // the row and the top bar are both gated on it before these are read.
        if (toolbarOn) item {
            ToggleSetting(
                R.string.appearance_toolbar_rtl_title,
                stringResource(R.string.appearance_toolbar_rtl_subtitle),
                settings.watch { it.toolbarBehavior.reverseForRtl },
                info = stringResource(R.string.appearance_toolbar_rtl_info),
                default = SettingsDefaults.toolbarBehavior.reverseForRtl,
            ) { scope.launch { repository.setReverseToolbarForRtl(it) } }
        }
        if (toolbarOn) item {
            val fit = settings.watch {
                when {
                    it.toolbarBehavior.scrollable -> ToolbarFit.SCROLL
                    it.toolbarBehavior.greedy -> ToolbarFit.SPREAD
                    else -> ToolbarFit.FIXED
                }
            }
            ChoiceSetting(
                R.string.appearance_toolbar_fit_title,
                subtitle = stringResource(R.string.appearance_toolbar_fit_subtitle),
                info = stringResource(R.string.appearance_toolbar_fit_info),
                options = ToolbarFit.entries.map { it to stringResource(it.labelRes) },
                selected = fit,
                default = DefaultToolbarFit,
                detail = { option -> ChoiceDetail(stringResource(option.descRes), option.icon) },
            ) { chosen ->
                scope.launch {
                    repository.setToolbarGreedy(chosen == ToolbarFit.SPREAD)
                    repository.setToolbarScrollable(chosen == ToolbarFit.SCROLL)
                }
            }
        }
        // The two paddings replaced the height slider (#208): the height made the
        // tools float in the middle of a taller strip, and the complaint was
        // about the gap on one side of it. A height set before then still
        // counts, and the toolbar reset below clears it.
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_toolbar_padding_top_title,
                subtitle = stringResource(R.string.appearance_toolbar_padding_top_subtitle),
                value = settings.watch { it.toolbarBehavior.paddingTopDp }.toFloat(),
                range = 0f..24f,
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_toolbar_padding_top_info),
                default = SettingsDefaults.toolbarBehavior.paddingTopDp.toFloat(),
            ) { scope.launch { repository.setToolbarPaddingTopDp(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_toolbar_padding_bottom_title,
                subtitle = stringResource(R.string.appearance_toolbar_padding_bottom_subtitle),
                value = settings.watch { it.toolbarBehavior.paddingBottomDp }.toFloat(),
                range = 0f..24f,
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_toolbar_padding_bottom_info),
                default = SettingsDefaults.toolbarBehavior.paddingBottomDp.toFloat(),
            ) { scope.launch { repository.setToolbarPaddingBottomDp(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.appearance_toolbar_lock_title,
                stringResource(R.string.appearance_toolbar_lock_subtitle),
                settings.watch { it.toolbarBehavior.hideWhenLocked },
                info = stringResource(R.string.appearance_toolbar_lock_info),
                default = SettingsDefaults.toolbarBehavior.hideWhenLocked,
            ) { scope.launch { repository.setToolbarHideWhenLocked(it) } }
        }
        if (toolbarOn) item {
            ToggleSetting(
                R.string.appearance_toolbar_labels_title,
                stringResource(R.string.appearance_toolbar_labels_subtitle),
                labelsOn,
                info = stringResource(R.string.appearance_toolbar_labels_info),
                default = SettingsDefaults.toolbarLabels,
            ) { scope.launch { repository.setToolbarLabels(it) } }
        }
        // The toolbox reads this as its own fallback, but it has a slider of
        // its own further down, so nothing is stranded by hiding the pair.
        item(visible = toolbarOn && labelsOn, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_toolbar_label_size_title,
                subtitle = stringResource(R.string.appearance_toolbar_label_size_subtitle),
                value = settings.watch { it.toolbarLabelSize }.toFloat(),
                range = 7f..14f,
                display = { spFormat.format(it.roundToInt()) },
                default = SettingsDefaults.toolbarLabelSize.toFloat(),
            ) { scope.launch { repository.setToolbarLabelSize(it.roundToInt()) } }
        }
        item(visible = toolbarOn && labelsOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.appearance_toolbar_labels_fixed_title,
                stringResource(R.string.appearance_toolbar_labels_fixed_subtitle),
                settings.watch { it.toolbarBehavior.labelsFixedWidth },
                default = SettingsDefaults.toolbarBehavior.labelsFixedWidth,
            ) { scope.launch { repository.setToolbarLabelsFixedWidth(it) } }
        }
        item(visible = toolbarOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.appearance_toolbar_pinned_colors_title,
                stringResource(R.string.appearance_toolbar_pinned_colors_subtitle),
                settings.watch { it.toolbarBehavior.pinnedToolColors },
                default = SettingsDefaults.toolbarBehavior.pinnedToolColors,
            ) { scope.launch { repository.setToolbarPinnedToolColors(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.appearance_suggestion_text_size_title,
                subtitle = stringResource(R.string.appearance_suggestion_text_size_subtitle),
                value = settings.watch { it.suggestionStrip.textScale },
                range = 0.8f..1.6f,
                display = { percentFormat.format((it * 100).roundToInt()) },
                info = stringResource(R.string.appearance_suggestion_text_size_info),
                default = SettingsDefaults.suggestionStrip.textScale,
            ) { scope.launch { repository.setSuggestionTextScale(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_suggestion_spacing_title,
                subtitle = stringResource(R.string.appearance_suggestion_spacing_subtitle),
                value = settings.watch { it.suggestionStrip.chipPadding }.toFloat(),
                range = 0f..24f,
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_suggestion_spacing_info),
                default = SettingsDefaults.suggestionStrip.chipPadding.toFloat(),
            ) { scope.launch { repository.setSuggestionChipPadding(it.roundToInt()) } }
        }
        // Beside the strip's other two appearance rows rather than on the
        // suggestions page it used to sit on (#114): it says what the strip
        // looks like, not what it offers.
        item(level = SettingLevel.ADVANCED) {
            ColorSetting(
                R.string.appearance_suggestion_primary_color_title,
                subtitle = stringResource(R.string.appearance_suggestion_primary_color_subtitle),
                color = settings.watch { it.suggestionStrip.primaryColor },
                fallback = MaterialTheme.colorScheme.onSurface.argbLong(),
                info = stringResource(R.string.appearance_suggestion_primary_color_info),
            ) { scope.launch { repository.setSuggestionPrimaryColor(it) } }
        }
        item {
            ResetPinnedToolsSetting(repository, scope)
        }
        item(level = SettingLevel.ADVANCED) {
            val offLabel = stringResource(CommonR.string.common_off)
            SliderSetting(
                R.string.appearance_tool_circle_title,
                subtitle = stringResource(R.string.appearance_tool_circle_subtitle),
                value = settings.watch { it.toolCircleRadiusDp }.toFloat(),
                range = 0f..20f,
                display = { if (it.toInt() == 0) offLabel else dpFormat.format(it.toInt()) },
                info = stringResource(R.string.appearance_tool_circle_info),
                default = SettingsDefaults.toolCircleRadiusDp.toFloat(),
            ) { scope.launch { repository.setToolCircleRadiusDp(it.toInt()) } }
        }
        // The same shapes the keys and the popups use. It draws nothing while
        // the radius above is at 0, which is the setting for "no background".
        item(visible = toolCircleOn, level = SettingLevel.ADVANCED) {
            NavRow(
                R.string.appearance_tool_shape_title,
                subtitle = stringResource(R.string.appearance_tool_shape_subtitle),
                value = keyShapeName(settings.watch { it.toolShape }),
                onClick = { toolShapePickerOpen = true },
            )
        }
        // Only the wide tools honour it — panel headers and grids keep the
        // fixed circle — and every wide one is drawn by the toolbar.
        if (toolbarOn) item {
            val pin = themePin(settings) { it.toolWidthDp }
            SliderSetting(
                R.string.appearance_tool_width_title,
                subtitle = pin?.text ?: stringResource(R.string.appearance_tool_width_subtitle),
                value = settings.watch { it.toolbarBehavior.toolWidthDp }.toFloat(),
                range = 38f..64f,
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_tool_width_info),
                enabled = pin == null,
                disabledReason = pin,
                default = SettingsDefaults.toolbarBehavior.toolWidthDp.toFloat(),
            ) { scope.launch { repository.setToolbarToolWidthDp(it.roundToInt()) } }
        }
        // Not gated on the toolbar: the toolbox grid draws the same buttons,
        // and it is still one tap away from the keyboard with the bar off.
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_tool_icon_size_title,
                subtitle = stringResource(R.string.appearance_tool_icon_size_subtitle),
                value = settings.watch { it.toolbarBehavior.iconSizeDp }.toFloat(),
                range = 14f..30f,
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_tool_icon_size_info),
                default = SettingsDefaults.toolbarBehavior.iconSizeDp.toFloat(),
            ) { scope.launch { repository.setToolbarIconSizeDp(it.roundToInt()) } }
        }
        // Drawn only once something has actually moved, like the group reset on
        // Layout & size. Which tools are pinned is not a slider and stays.
        item(visible = toolbarMoved, level = SettingLevel.STANDARD) {
            ActionRow(
                title = R.string.appearance_toolbar_reset_title,
                subtitle = stringResource(R.string.appearance_toolbar_reset_subtitle),
                action = stringResource(CommonR.string.common_reset),
                confirm = stringResource(R.string.appearance_toolbar_reset_confirm),
            ) { scope.launch { repository.resetToolbar() } }
        }
    }
}

@Composable
internal fun AppearanceToolboxSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val spFormat = stringResource(R.string.values_sp)
    val dpFormat = stringResource(R.string.typing_value_dp)
    // What decides which rows the group holds; each row reads its own value.
    val orderMoved = settings.watch { it.toolboxOrder != SettingsDefaults.toolboxOrder }
    val toolboxLayout = settings.watch { it.toolbox.layout }
    val paginate = settings.watch { it.toolbox.paginate }
    val searchButtonOn = settings.watch { it.toolbox.searchButton }
    val toolboxMoved = settings.watch { s ->
        val d = SettingsDefaults
        s.toolbox != d.toolbox || s.toolboxColumns != d.toolboxColumns
    }
    SettingsGroup(stringResource(R.string.appearance_toolbox_section_title)) {
        // The grid's own order. "Reset pinned tools" restored the bar and
        // nothing restored the grid, so a bad drag session there had no way
        // back. Drawn only once the order has actually been changed.
        item(visible = orderMoved, level = SettingLevel.STANDARD) {
            ActionRow(
                title = R.string.appearance_reset_toolbox_order_title,
                subtitle = stringResource(R.string.appearance_reset_toolbox_order_subtitle),
                action = stringResource(CommonR.string.common_reset),
                confirm = stringResource(R.string.appearance_reset_toolbox_order_confirm),
            ) { scope.launch { repository.resetToolboxOrder() } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.appearance_toolbox_sort_title,
                subtitle = stringResource(R.string.appearance_toolbox_sort_subtitle),
                options = toolboxSortOptions(),
                selected = settings.watch { it.toolbox.sort },
                info = stringResource(R.string.appearance_toolbox_sort_info),
                default = SettingsDefaults.toolbox.sort,
            ) { scope.launch { repository.setToolboxSort(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.appearance_toolbox_layout_title,
                subtitle = stringResource(R.string.appearance_toolbox_layout_subtitle),
                options = listOf(
                    ToolboxLayout.ICONS to
                        stringResource(R.string.appearance_toolbox_layout_icons_label),
                    ToolboxLayout.PILLS to
                        stringResource(R.string.appearance_toolbox_layout_pills_label),
                ),
                selected = toolboxLayout,
                info = stringResource(R.string.appearance_toolbox_layout_info),
                default = SettingsDefaults.toolbox.layout,
            ) { scope.launch { repository.setToolboxLayout(it) } }
        }
        if (toolboxLayout == ToolboxLayout.ICONS) {
            item(level = SettingLevel.ADVANCED) {
                val perRow = stringResource(R.string.appearance_slider_per_row_value)
                SliderSetting(
                    R.string.appearance_toolbox_columns_title,
                    subtitle = stringResource(R.string.appearance_toolbox_columns_subtitle),
                    value = settings.watch { it.toolboxColumns }.toFloat(),
                    range = 3f..6f,
                    display = { perRow.format(it.roundToInt()) },
                    info = stringResource(R.string.appearance_toolbox_columns_info),
                    default = SettingsDefaults.toolboxColumns.toFloat(),
                ) { scope.launch { repository.setToolboxColumns(it.roundToInt()) } }
            }
        } else {
            item(level = SettingLevel.ADVANCED) {
                val perRow = stringResource(R.string.appearance_slider_per_row_value)
                SliderSetting(
                    R.string.appearance_toolbox_pill_columns_title,
                    subtitle = stringResource(R.string.appearance_toolbox_pill_columns_subtitle),
                    value = settings.watch { it.toolbox.pillColumns }.toFloat(),
                    range = 1f..3f,
                    display = { perRow.format(it.roundToInt()) },
                    info = stringResource(R.string.appearance_toolbox_pill_columns_info),
                    default = SettingsDefaults.toolbox.pillColumns.toFloat(),
                ) { scope.launch { repository.setToolboxPillColumns(it.roundToInt()) } }
            }
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.appearance_toolbox_pill_filled_title,
                    stringResource(R.string.appearance_toolbox_pill_filled_subtitle),
                    settings.watch { it.toolbox.pillFilled },
                    info = stringResource(R.string.appearance_toolbox_pill_filled_info),
                    default = SettingsDefaults.toolbox.pillFilled,
                ) { scope.launch { repository.setToolboxPillFilled(it) } }
            }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.appearance_toolbox_paginate_title,
                stringResource(R.string.appearance_toolbox_paginate_subtitle),
                paginate,
                info = stringResource(R.string.appearance_toolbox_paginate_info),
                default = SettingsDefaults.toolbox.paginate,
            ) { scope.launch { repository.setToolboxPaginate(it) } }
        }
        item(visible = paginate, level = SettingLevel.ADVANCED) {
            val perPage = stringResource(R.string.appearance_slider_per_page_value)
            SliderSetting(
                R.string.appearance_toolbox_page_size_title,
                subtitle = stringResource(R.string.appearance_toolbox_page_size_subtitle),
                value = settings.watch { it.toolbox.pageSize }.toFloat(),
                range = ToolboxPageSizeRange.first.toFloat()..ToolboxPageSizeRange.last.toFloat(),
                display = { perPage.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_toolbox_page_size_info),
                default = SettingsDefaults.toolbox.pageSize.toFloat(),
            ) { scope.launch { repository.setToolboxPageSize(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            val followToolbar = stringResource(R.string.appearance_toolbox_label_size_follow)
            SliderSetting(
                R.string.appearance_toolbox_label_size_title,
                subtitle = stringResource(R.string.appearance_toolbox_label_size_subtitle),
                value = settings.watch { it.toolbox.labelSizeSp }.toFloat(),
                // 0 is the "follow the toolbar" end of the slider rather than a
                // size, which is why the readout reads as a word there.
                range = 0f..16f,
                display = {
                    if (it.roundToInt() == 0) followToolbar else spFormat.format(it.roundToInt())
                },
                info = stringResource(R.string.appearance_toolbox_label_size_info),
                default = SettingsDefaults.toolbox.labelSizeSp.toFloat(),
            ) { scope.launch { repository.setToolboxLabelSize(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_toolbox_cell_spacing_title,
                subtitle = stringResource(R.string.appearance_toolbox_cell_spacing_subtitle),
                value = settings.watch { it.toolbox.cellSpacingDp }.toFloat(),
                range = 0f..16f,
                display = { dpFormat.format(it.roundToInt()) },
                default = SettingsDefaults.toolbox.cellSpacingDp.toFloat(),
            ) { scope.launch { repository.setToolboxCellSpacing(it.roundToInt()) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.appearance_toolbox_back_lowers_title,
                stringResource(R.string.appearance_toolbox_back_lowers_subtitle),
                settings.watch { it.toolbox.backLowersKeys },
                info = stringResource(R.string.appearance_toolbox_back_lowers_info),
                default = SettingsDefaults.toolbox.backLowersKeys,
            ) { scope.launch { repository.setToolboxBackLowersKeys(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.appearance_toolbox_search_button_title,
                stringResource(R.string.appearance_toolbox_search_button_subtitle),
                settings.watch { it.toolbox.searchButton },
                info = stringResource(R.string.appearance_toolbox_search_button_info),
                default = SettingsDefaults.toolbox.searchButton,
            ) { scope.launch { repository.setToolboxSearchButton(it) } }
        }
        item(visible = searchButtonOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.appearance_toolbox_search_at_end_title,
                stringResource(R.string.appearance_toolbox_search_at_end_subtitle),
                settings.watch { it.toolbox.searchAtEnd },
                info = stringResource(R.string.appearance_toolbox_search_at_end_info),
                default = SettingsDefaults.toolbox.searchAtEnd,
            ) { scope.launch { repository.setToolboxSearchAtEnd(it) } }
        }
        item(visible = searchButtonOn, level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                R.string.appearance_toolbox_search_sort_title,
                subtitle = stringResource(R.string.appearance_toolbox_search_sort_subtitle),
                options = toolboxSortOptions(),
                selected = settings.watch { it.toolbox.searchSort },
                info = stringResource(R.string.appearance_toolbox_search_sort_info),
                default = SettingsDefaults.toolbox.searchSort,
            ) { scope.launch { repository.setToolboxSearchSort(it) } }
        }
        // Drawn only once something has actually moved, like the group reset on
        // Layout & size. The order of the tools has its own reset above.
        item(visible = toolboxMoved, level = SettingLevel.STANDARD) {
            ActionRow(
                title = R.string.appearance_toolbox_reset_title,
                subtitle = stringResource(R.string.appearance_toolbox_reset_subtitle),
                action = stringResource(CommonR.string.common_reset),
                confirm = stringResource(R.string.appearance_toolbox_reset_confirm),
            ) { scope.launch { repository.resetToolbox() } }
        }
    }
}
/** The orders the toolbox and its search can take. */
@Composable
private fun toolboxSortOptions(): List<Pair<ToolboxSort, String>> = listOf(
    ToolboxSort.CUSTOM to stringResource(R.string.appearance_toolbox_sort_custom_label),
    ToolboxSort.MOST_USED to stringResource(R.string.appearance_toolbox_sort_most_used_label),
    ToolboxSort.RECENT to stringResource(R.string.appearance_toolbox_sort_recent_label),
    ToolboxSort.ALPHABETICAL to stringResource(R.string.appearance_toolbox_sort_alphabetical_label),
)

// ---- layout & size ----

/** The name the arrow order list gives [this] (issue #369). */
@StringRes
private fun ArrowKey.nameRes(): Int = when (this) {
    ArrowKey.LEFT -> R.string.layout_arrow_left
    ArrowKey.UP -> R.string.layout_arrow_up
    ArrowKey.DOWN -> R.string.layout_arrow_down
    ArrowKey.RIGHT -> R.string.layout_arrow_right
}

@Composable
internal fun LayoutSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val dpFormat = stringResource(R.string.typing_value_dp)
    // What decides which rows the groups hold; each row reads its own value.
    val numberRow = settings.watch { it.numberRow }
    val symbolsReturn = settings.watch { it.layoutBehavior.symbolsReturnToLetters }
    // No pinned sketch of the keyboard here any more (issue #769): it took a
    // band of the screen and drew a board that was not the user's, and the
    // keyboard test button on every screen (#412) shows the real one.
    // The grids themselves, first: which layout the keyboard draws is a bigger
    // question than how tall its keys are, and every row below this one is an
    // adjustment to whatever is chosen here. The whole group came off the
    // Languages screen, where it read as a language thing only because it sat
    // next to the switches that turn a language on.
    //
    // Only the user's own grids. An override of a shipped layout — built-in or
    // JSON asset — is an edit of that layout, not a layout of their own, and
    // listing it here would show the same name twice. Secondary layouts are
    // left out too: they cannot be switched on, and are reached from a key or
    // the Secondary layout tool.
    val customs = settings.watch { s ->
        s.customLayouts
            .filter {
                !it.secondary &&
                    !isShippedLayoutId(it.id)
            }
            .sortedBy { it.name.lowercase() }
    }
    // Turning a layout on is gated on it validating; switching one off never is,
    // or a layout broken while enabled would be impossible to put away.
    val enableGate = rememberLayoutEnableGate(settings)
    SettingsGroup(stringResource(R.string.langemoji_lang_your_layouts_title)) {
        for (layout in customs) {
            item {
                // An installed layout arrives switched off and this switch is
                // what finishes the install, so its addon's Use button lands
                // here — on the layout's own row, not on the group.
                HighlightableItem(layout.id) {
                    ToggleSetting(
                        layout.name,
                        stringResource(
                            R.string.langemoji_lang_custom_layout_subtitle,
                            baseModeTitle(layout),
                        ),
                        settings.watch { layout.id in it.enabledLayoutIds },
                        default = layout.id in SettingsDefaults.enabledLayoutIds,
                    ) { enable ->
                        fun write() {
                            scope.launch {
                                val enabledIds = settings.value.enabledLayoutIds
                                val next =
                                    if (enable) enabledIds + layout.id
                                    else enabledIds - layout.id
                                if (next.isNotEmpty()) {
                                    repository.setEnabledLayoutIds(next.distinct())
                                }
                            }
                        }
                        if (enable) enableGate(layout.id) { write() } else write()
                    }
                }
            }
        }
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.langemoji_lang_keymaps_title,
                subtitle = if (customs.isEmpty()) {
                    stringResource(R.string.langemoji_lang_keymaps_empty_subtitle)
                } else {
                    stringResource(R.string.langemoji_lang_keymaps_subtitle)
                },
                route = "keymaps",
            ) {
                onNavigate("keymaps")
            }
        }
        // "Make one" and "get one" are the same question answered two ways.
        item { AddonStoreRow(AddonType.Layout, onNavigate) }
        item { CommunityAddonsRow(AddonType.Layout, onNavigate) }
    }
    SettingsGroup(stringResource(R.string.layout_number_row_title)) {
        item {
            ToggleSetting(
                R.string.layout_number_row_title,
                stringResource(R.string.layout_number_row_subtitle),
                numberRow,
                info = stringResource(R.string.layout_number_row_info),
                default = SettingsDefaults.numberRow,
            ) { scope.launch { repository.setNumberRow(it) } }
        }
        item(visible = numberRow, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.layout_number_row_height_title,
                subtitle = stringResource(R.string.layout_number_row_height_subtitle),
                value = settings.watch { it.numberRowHeightDp }.toFloat(),
                range = 32f..100f,
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_number_row_height_info),
                default = SettingsDefaults.numberRowHeightDp.toFloat(),
            ) { scope.launch { repository.setNumberRowHeightDp(it.toInt()) } }
        }
        item(visible = numberRow, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_number_row_shift_symbols_title,
                stringResource(R.string.layout_number_row_shift_symbols_subtitle),
                settings.watch { it.layoutBehavior.numberRowShiftSymbols },
                info = stringResource(R.string.layout_number_row_shift_symbols_info),
                default = SettingsDefaults.layoutBehavior.numberRowShiftSymbols,
            ) { scope.launch { repository.setNumberRowShiftSymbols(it) } }
        }
        item(visible = numberRow, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_number_row_in_symbols_title,
                stringResource(R.string.layout_number_row_in_symbols_subtitle),
                settings.watch { it.layoutBehavior.numberRowInSymbols },
                info = stringResource(R.string.layout_number_row_in_symbols_info),
                default = SettingsDefaults.layoutBehavior.numberRowInSymbols,
            ) { scope.launch { repository.setNumberRowInSymbols(it) } }
        }
        item(visible = numberRow, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_number_row_on_keypad_title,
                stringResource(R.string.layout_number_row_on_keypad_subtitle),
                settings.watch { it.layoutBehavior.numberRowOnKeypad },
                info = stringResource(R.string.layout_number_row_on_keypad_info),
                default = SettingsDefaults.layoutBehavior.numberRowOnKeypad,
            ) { scope.launch { repository.setNumberRowOnKeypad(it) } }
        }
    }
    // Issue #369: the caret keys as a row of their own under the spacebar.
    val arrowRow = settings.watch { it.layoutBehavior.arrowRow }
    SettingsGroup(stringResource(R.string.layout_arrow_row_title)) {
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.layout_arrow_row_title,
                stringResource(R.string.layout_arrow_row_subtitle),
                arrowRow,
                info = stringResource(R.string.layout_arrow_row_info),
                default = SettingsDefaults.layoutBehavior.arrowRow,
            ) { scope.launch { repository.setArrowRow(it) } }
        }
        item(visible = arrowRow, level = SettingLevel.ADVANCED) {
            ControlSetting(
                R.string.layout_arrow_row_order_title,
                subtitle = stringResource(R.string.layout_arrow_row_order_subtitle),
                info = stringResource(R.string.layout_arrow_row_order_info),
            ) {
                val names = ArrowKey.entries.associateWith { stringResource(it.nameRes()) }
                ReorderableColumn(
                    items = settings.watch { it.layoutBehavior.arrowRowOrder },
                    label = { names[it].orEmpty() },
                    onReorder = { scope.launch { repository.setArrowRowOrder(it) } },
                    modifier = Modifier.padding(top = 8.dp),
                ) { arrow ->
                    Text(
                        arrowRowKey(arrow).label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                    Text(
                        names[arrow].orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.layout_symbols_title), level = SettingLevel.ADVANCED) {
        item {
            ToggleSetting(
                R.string.layout_symbols_return_title,
                stringResource(R.string.layout_symbols_return_subtitle),
                symbolsReturn,
                info = stringResource(R.string.layout_symbols_return_info),
                default = SettingsDefaults.layoutBehavior.symbolsReturnToLetters,
            ) { scope.launch { repository.setSymbolsReturnToLetters(it) } }
        }
        item(visible = symbolsReturn) {
            // Saves as it is typed, like the currency keys field; blank
            // restores the default set. Seeded once rather than re-read on
            // every keystroke: the repository drops spaces and duplicates,
            // and feeding that back would move the caret while typing.
            val storedReturnChars = settings.watch { it.layoutBehavior.symbolsReturnCharSet() }
            var returnChars by remember {
                mutableStateOf(storedReturnChars)
            }
            ControlSetting(
                R.string.layout_symbols_return_chars_title,
                info = stringResource(R.string.layout_symbols_return_chars_info),
            ) {
                OutlinedTextField(
                    value = returnChars,
                    onValueChange = {
                        returnChars = it
                        scope.launch { repository.setSymbolsReturnChars(it) }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            ToggleSetting(
                R.string.layout_symbols_return_space_title,
                stringResource(R.string.layout_symbols_return_space_subtitle),
                settings.watch { it.layoutBehavior.symbolsReturnOnSpace },
                info = stringResource(R.string.layout_symbols_return_space_info),
                default = SettingsDefaults.layoutBehavior.symbolsReturnOnSpace,
            ) { scope.launch { repository.setSymbolsReturnOnSpace(it) } }
        }
    }

    SettingsGroup(
        stringResource(R.string.layout_numerals_title),
        info = stringResource(R.string.layout_numerals_caption),
    ) {
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.layout_numeral_scope_title,
                subtitle = stringResource(R.string.layout_numeral_scope_subtitle),
                info = stringResource(R.string.layout_numeral_scope_info),
                options = NumeralCommitScope.entries.map { it to stringResource(it.labelRes) },
                selected = settings.watch { it.layoutBehavior.numeralCommitScope },
                default = SettingsDefaults.layoutBehavior.numeralCommitScope,
                detail = { numeralScope -> ChoiceDetail(stringResource(numeralScopeDescRes(numeralScope))) },
            ) { scope.launch { repository.setNumeralCommitScope(it) } }
        }
    }

    SettingsGroup {
        item {
            NavRow(
                R.string.layout_size_position_title,
                stringResource(R.string.layout_size_subtitle),
                route = "layout/size",
            ) {
                onNavigate("layout/size")
            }
        }
        item {
            NavRow(
                R.string.layout_one_handed_group_title,
                stringResource(R.string.layout_one_handed_page_subtitle),
                route = "layout/onehanded",
            ) {
                onNavigate("layout/onehanded")
            }
        }
    }



    // The rows that act on the 🌐 key go with it: hidden, it has nothing for
    // them to change.
    val globeShown = settings.watch { it.showGlobeKey }
    SettingsGroup(stringResource(R.string.layout_bottom_row_keys_title)) {
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.layout_comma_emoji_title,
                stringResource(R.string.layout_comma_emoji_subtitle),
                settings.watch { it.commaAsEmoji },
                info = stringResource(R.string.layout_comma_emoji_info),
                default = SettingsDefaults.commaAsEmoji,
            ) { scope.launch { repository.setCommaAsEmoji(it) } }
        }
        // The same switch as the one under Key press → Press and hold
        // shortcuts, here too because this is where the ways onto the emoji
        // panel from the bottom row are.
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.keypress_enter_emoji_title,
                stringResource(R.string.keypress_enter_emoji_subtitle),
                settings.watch { it.layoutBehavior.enterLongPressEmoji },
                info = stringResource(R.string.keypress_enter_emoji_info),
                default = SettingsDefaults.layoutBehavior.enterLongPressEmoji,
            ) { scope.launch { repository.setEnterLongPressEmoji(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            // Issue #423: Gboard's 1234 key on the symbols pages.
            ToggleSetting(
                R.string.layout_symbols_numpad_key_title,
                stringResource(R.string.layout_symbols_numpad_key_subtitle),
                settings.watch { it.layoutBehavior.symbolsNumpadKey },
                info = stringResource(R.string.layout_symbols_numpad_key_info),
                default = SettingsDefaults.layoutBehavior.symbolsNumpadKey,
            ) { scope.launch { repository.setSymbolsNumpadKey(it) } }
        }
        item {
            ToggleSetting(
                R.string.layout_show_globe_title,
                stringResource(R.string.layout_show_globe_subtitle),
                globeShown,
                info = stringResource(R.string.layout_show_globe_info),
                default = SettingsDefaults.showGlobeKey,
            ) { scope.launch { repository.setShowGlobeKey(it) } }
        }
        // Not hidden with the key: a physical keyboard's language key follows
        // it too.
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_globe_recent_title,
                stringResource(R.string.layout_globe_recent_subtitle),
                settings.watch { it.globeRecentOrder },
                info = stringResource(R.string.layout_globe_recent_info),
                default = SettingsDefaults.globeRecentOrder,
            ) { scope.launch { repository.setGlobeRecentOrder(it) } }
        }
        item(visible = globeShown, level = SettingLevel.ADVANCED) {
            val offLabel = stringResource(CommonR.string.common_off)
            val msFormat = stringResource(R.string.typing_value_milliseconds)
            SliderSetting(
                R.string.layout_globe_guard_title,
                subtitle = stringResource(R.string.layout_globe_guard_subtitle),
                value = settings.watch { it.layoutBehavior.globeTypingGuardMs }.toFloat(),
                range = GlobeTypingGuardMsRange.first.toFloat()..GlobeTypingGuardMsRange.last.toFloat(),
                // Steps of 50 ms: finer than that is not a difference a thumb
                // can tell, and a readout that only moves in round numbers
                // is one the user can set again.
                display = {
                    val ms = (it / 50f).roundToInt() * 50
                    if (ms == 0) offLabel else msFormat.format(ms)
                },
                info = stringResource(R.string.layout_globe_guard_info),
                default = SettingsDefaults.layoutBehavior.globeTypingGuardMs.toFloat(),
            ) { scope.launch { repository.setGlobeTypingGuardMs((it / 50f).roundToInt() * 50) } }
        }
        item(visible = globeShown) {
            ToggleSetting(
                R.string.layout_globe_emoji_title,
                stringResource(R.string.layout_globe_emoji_subtitle),
                settings.watch { it.globeAsEmoji },
                info = stringResource(R.string.layout_globe_emoji_info),
                default = SettingsDefaults.globeAsEmoji,
            ) { scope.launch { repository.setGlobeAsEmoji(it) } }
        }
        item(visible = globeShown, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_swap_comma_globe_title,
                stringResource(R.string.layout_swap_comma_globe_subtitle),
                settings.watch { it.swapCommaAndGlobe },
                info = stringResource(R.string.layout_swap_comma_globe_info),
                default = SettingsDefaults.swapCommaAndGlobe,
            ) { scope.launch { repository.setSwapCommaAndGlobe(it) } }
        }
        item(visible = globeShown, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_globe_in_one_place_title,
                stringResource(R.string.layout_globe_in_one_place_subtitle),
                settings.watch { it.layoutBehavior.globeInOnePlace },
                info = stringResource(R.string.layout_globe_in_one_place_info),
                default = SettingsDefaults.layoutBehavior.globeInOnePlace,
            ) { scope.launch { repository.setGlobeInOnePlace(it) } }
        }
    }
}

@Composable
internal fun LayoutSizeSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val dpFormat = stringResource(R.string.typing_value_dp)
    val percentFormat = stringResource(R.string.typing_value_percent)
    val multiplierFormat = stringResource(R.string.keypress_value_multiplier)
    var expandedVariant by remember { mutableStateOf<ScreenVariant?>(null) }
    // What the keyboard uses while bottom padding is unset (#343). Read off
    // this screen's own insets, which carry the same kind of navigation bar.
    val autoBottomPadding = autoBottomPaddingDp(gestureBarAtBottom())
    // What decides which rows the groups hold; each row reads its own value.
    val cornersRound = settings.watch {
        it.layoutBehavior.boardCornerTopDp > 0 || it.layoutBehavior.boardCornerBottomDp > 0
    }
    val narrowed = settings.watch { it.keyboardWidthPercent < 100 }
    val sizingMoved = settings.watch { s ->
        s.keyHeightDp != SettingsDefaults.keyHeightDp ||
            s.numberRowHeightDp != SettingsDefaults.numberRowHeightDp ||
            s.bottomPaddingDp != null ||
            s.keyboardWidthPercent != SettingsDefaults.keyboardWidthPercent ||
            s.keyboardAlignment != SettingsDefaults.keyboardAlignment ||
            s.keyGapScale != SettingsDefaults.keyGapScale ||
            s.keyCornerRadiusDp != SettingsDefaults.keyCornerRadiusDp ||
            s.layoutBehavior.sidePadLeftScale !=
            SettingsDefaults.layoutBehavior.sidePadLeftScale ||
            s.layoutBehavior.sidePadRightScale !=
            SettingsDefaults.layoutBehavior.sidePadRightScale ||
            s.layoutBehavior.bottomRowHeightDp !=
            SettingsDefaults.layoutBehavior.bottomRowHeightDp
    }
    // Only the expanded screen shape draws its rows, so only its values
    // decide which of them are there.
    val shapeNumberRow = settings.watch { s ->
        expandedVariant?.let { s.sizingValuesFor(it).numberRow } ?: s.numberRow
    }
    val shapeNarrowed = settings.watch { s ->
        (expandedVariant?.let { s.sizingValuesFor(it).keyboardWidthPercent } ?: s.keyboardWidthPercent) < 100
    }
    val shapeOverridden = settings.watch { s ->
        expandedVariant?.let { s.sizingOverrides[it] }?.let { !it.isEmpty } == true
    }
    val preview = LocalKeyboardPreview.current
    SettingsGroup(stringResource(R.string.layout_size_position_title)) {
        // The sliders below, dragged on the keyboard itself (#912): opens the
        // test field and puts the keyboard that comes up into resize mode.
        // Needs the test field, so not while its button is switched off.
        item(visible = preview != null) {
            ActionRow(
                title = R.string.layout_resize_drag_title,
                subtitle = stringResource(R.string.layout_resize_drag_subtitle),
                action = stringResource(R.string.layout_resize_drag_action),
            ) {
                preview?.let {
                    it.resizeOnShow = true
                    it.open = true
                }
            }
        }
        item {
            val pin = themePin(settings) { it.keyHeightDp }
            SliderSetting(
                R.string.layout_key_height_title,
                subtitle = pin?.text ?: stringResource(R.string.layout_key_height_subtitle),
                value = settings.watch { it.keyHeightDp }.toFloat(),
                range = 32f..100f,
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_key_height_info),
                enabled = pin == null,
                disabledReason = pin,
                default = SettingsDefaults.keyHeightDp.toFloat(),
            ) { scope.launch { repository.setKeyHeightDp(it.toInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            val followKeys = stringResource(R.string.layout_bottom_row_follow_keys_label)
            SliderSetting(
                R.string.layout_bottom_row_height_title,
                subtitle = stringResource(R.string.layout_bottom_row_height_subtitle),
                value = settings.watch { it.layoutBehavior.bottomRowHeightDp }.toFloat(),
                range = 0f..BottomRowHeightRange.last.toFloat(),
                display = { if (it < 1f) followKeys else dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_bottom_row_height_info),
                default = SettingsDefaults.layoutBehavior.bottomRowHeightDp.toFloat(),
            ) { scope.launch { repository.setBottomRowHeightDp(it.toInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            // The legacy symmetric field pins both edges where a per-edge one
            // is unset, the way applyThemeOverrides reads them.
            val pin = themePin(settings) { it.sidePadLeftScale ?: it.sidePadScale }
            SliderSetting(
                R.string.layout_side_padding_left_title,
                subtitle = pin?.text ?: stringResource(R.string.layout_side_padding_left_subtitle),
                value = settings.watch { it.layoutBehavior.sidePadLeftScale },
                range = SidePadScaleRange.start..SidePadScaleRange.endInclusive,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_side_padding_left_info),
                enabled = pin == null,
                disabledReason = pin,
                default = SettingsDefaults.layoutBehavior.sidePadLeftScale,
            ) { scope.launch { repository.setSidePadLeftScale(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            val pin = themePin(settings) { it.sidePadRightScale ?: it.sidePadScale }
            SliderSetting(
                R.string.layout_side_padding_right_title,
                subtitle = pin?.text ?: stringResource(R.string.layout_side_padding_right_subtitle),
                value = settings.watch { it.layoutBehavior.sidePadRightScale },
                range = SidePadScaleRange.start..SidePadScaleRange.endInclusive,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_side_padding_right_info),
                enabled = pin == null,
                disabledReason = pin,
                default = SettingsDefaults.layoutBehavior.sidePadRightScale,
            ) { scope.launch { repository.setSidePadRightScale(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            val pin = themePin(settings) { it.keyGapScale }
            SliderSetting(
                R.string.layout_key_spacing_title,
                subtitle = pin?.text ?: stringResource(R.string.layout_key_spacing_subtitle),
                value = settings.watch { it.keyGapScale },
                range = 0f..2f,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_key_spacing_info),
                enabled = pin == null,
                disabledReason = pin,
                default = SettingsDefaults.keyGapScale,
            ) { scope.launch { repository.setKeyGapScale(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            // Issue #530: a tap in a short row's side gap types the key at that end.
            ToggleSetting(
                R.string.layout_extend_edge_keys_title,
                stringResource(R.string.layout_extend_edge_keys_subtitle),
                settings.watch { it.layoutBehavior.extendEdgeKeys },
                info = stringResource(R.string.layout_extend_edge_keys_info),
                default = SettingsDefaults.layoutBehavior.extendEdgeKeys,
            ) { scope.launch { repository.setExtendEdgeKeys(it) } }
        }
        item {
            // Basic: an empty band under the keys is the first thing anyone
            // resizing the keyboard comes here to fix (#1030).
            val bottomPadding = settings.watch { it.bottomPaddingDp }
            SliderSetting(
                R.string.layout_bottom_padding_title,
                subtitle = stringResource(R.string.layout_bottom_padding_subtitle),
                value = (bottomPadding ?: autoBottomPadding).toFloat(),
                range = 0f..SettingsRepository.MAX_BOTTOM_PADDING_DP.toFloat(),
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_bottom_padding_info),
                default = autoBottomPadding.toFloat(),
                // Back to automatic, not to the number it shows here.
                onReset = if (bottomPadding != null) {
                    { scope.launch { repository.resetBottomPaddingDp() } }
                } else {
                    null
                },
            ) { scope.launch { repository.setBottomPaddingDp(it.toInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            // Android has no way for a keyboard to ask for this before 16
            // (#582), and none at all where the bar is part of the system's
            // own navigation bar: three buttons, or a build that draws it
            // there (#681).
            val newEnough = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.BAKLAVA
            val resources = LocalContext.current.resources
            val supported = remember(resources, LocalConfiguration.current) {
                keyboardCanHideSystemKeyboardBar(resources)
            }
            ToggleSetting(
                R.string.layout_hide_system_keyboard_bar_title,
                stringResource(
                    when {
                        supported -> R.string.layout_hide_system_keyboard_bar_subtitle
                        newEnough -> R.string.layout_hide_system_keyboard_bar_system_drawn
                        else -> R.string.layout_hide_system_keyboard_bar_unsupported
                    },
                ),
                settings.watch { it.layoutBehavior.hideSystemKeyboardBar },
                info = stringResource(R.string.layout_hide_system_keyboard_bar_info),
                enabled = supported,
                // Nothing in settings can lift this one: the reason is the
                // subtitle's, and the hold only has it to say.
                disabledReason = DisabledReason(
                    stringResource(
                        if (newEnough) {
                            R.string.layout_hide_system_keyboard_bar_system_drawn
                        } else {
                            R.string.layout_hide_system_keyboard_bar_unsupported
                        },
                    ),
                ),
                default = SettingsDefaults.layoutBehavior.hideSystemKeyboardBar,
            ) { scope.launch { repository.setHideSystemKeyboardBar(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            val square = stringResource(R.string.layout_board_corner_square)
            SliderSetting(
                R.string.layout_board_corner_top_title,
                subtitle = stringResource(R.string.layout_board_corner_top_subtitle),
                value = settings.watch { it.layoutBehavior.boardCornerTopDp }.toFloat(),
                range = BoardCornerRadiusRange.first.toFloat()..BoardCornerRadiusRange.last.toFloat(),
                display = { if (it.roundToInt() == 0) square else dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.layout_board_corner_info),
                default = SettingsDefaults.layoutBehavior.boardCornerTopDp.toFloat(),
            ) { scope.launch { repository.setBoardCornerTopDp(it.roundToInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            val square = stringResource(R.string.layout_board_corner_square)
            SliderSetting(
                R.string.layout_board_corner_bottom_title,
                subtitle = stringResource(R.string.layout_board_corner_bottom_subtitle),
                value = settings.watch { it.layoutBehavior.boardCornerBottomDp }.toFloat(),
                range = BoardCornerRadiusRange.first.toFloat()..BoardCornerRadiusRange.last.toFloat(),
                display = { if (it.roundToInt() == 0) square else dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.layout_board_corner_info),
                default = SettingsDefaults.layoutBehavior.boardCornerBottomDp.toFloat(),
            ) { scope.launch { repository.setBoardCornerBottomDp(it.roundToInt()) } }
        }
        // Which corners only matters once one of the two is round.
        if (cornersRound) {
            item(level = SettingLevel.ADVANCED) {
                MultiChoiceSetting(
                    R.string.layout_board_corners_title,
                    subtitle = stringResource(R.string.layout_board_corners_subtitle),
                    options = BoardCorner.entries.map { it to stringResource(it.labelRes) },
                    selected = settings.watch { it.layoutBehavior.boardCorners },
                    default = SettingsDefaults.layoutBehavior.boardCorners,
                ) { scope.launch { repository.setBoardCorners(it) } }
            }
        }
        item(level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.layout_keyboard_width_title,
                subtitle = stringResource(R.string.layout_keyboard_width_subtitle),
                value = settings.watch { it.keyboardWidthPercent }.toFloat(),
                range = 50f..100f,
                display = { percentFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_keyboard_width_info),
                default = SettingsDefaults.keyboardWidthPercent.toFloat(),
            ) { scope.launch { repository.setKeyboardWidthPercent(it.toInt()) } }
        }
        item(visible = narrowed, level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.layout_keyboard_position_title,
                info = stringResource(R.string.layout_keyboard_position_info),
                options = KeyboardAlignment.entries.map { alignment ->
                    alignment to stringResource(layoutAlignmentLabelRes(alignment))
                },
                selected = settings.watch { it.keyboardAlignment },
                default = SettingsDefaults.keyboardAlignment,
            ) { scope.launch { repository.setKeyboardAlignment(it) } }
        }
        // Drawn only once something in the group has actually moved, like the
        // per-row reset controls: on an untouched screen it would be a button
        // that does nothing.
        item(visible = sizingMoved, level = SettingLevel.STANDARD) {
            ActionRow(
                title = R.string.layout_reset_sizing_title,
                subtitle = stringResource(R.string.layout_reset_sizing_subtitle),
                action = stringResource(CommonR.string.common_reset),
                confirm = stringResource(R.string.layout_reset_sizing_confirm),
            ) { scope.launch { repository.resetSizeAndPosition() } }
        }
    }
    SettingsGroup(
        stringResource(R.string.layout_per_screen_title),
        level = SettingLevel.ADVANCED,
        info = stringResource(R.string.layout_per_screen_caption),
    ) {
        for (variant in ScreenVariant.entries.filter { it.isOverride }) {
            item {
                val followsPortrait = settings.watch { s ->
                    s.sizingOverrides[variant].let { it == null || it.isEmpty }
                }
                NavRow(
                    stringResource(variant.labelRes),
                    if (followsPortrait) {
                        stringResource(R.string.layout_variant_follows_portrait_label)
                    } else {
                        stringResource(
                            R.string.layout_variant_summary,
                            settings.watch { it.sizingValuesFor(variant).keyHeightDp ?: it.keyHeightDp },
                            settings.watch {
                                it.sizingValuesFor(variant).keyboardWidthPercent ?: it.keyboardWidthPercent
                            },
                        )
                    },
                    icon = SettingsRowIcons[variant.labelRes],
                    onClick = {
                        expandedVariant = if (expandedVariant == variant) null else variant
                    },
                )
            }
            if (expandedVariant == variant) {
                item {
                    SliderSetting(
                        R.string.layout_keyboard_scale_title,
                        subtitle = stringResource(R.string.layout_keyboard_scale_subtitle),
                        value = settings.watch { it.sizingValuesFor(variant).keyboardScale } ?: 1f,
                        range = 0.5f..1.5f,
                        display = { percentFormat.format((it * 100).toInt()) },
                    ) { scope.launch { repository.setVariantKeyboardScale(variant, it) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_key_height_title,
                        value = settings.watch { it.sizingValuesFor(variant).keyHeightDp ?: it.keyHeightDp }
                            .toFloat(),
                        range = 32f..100f,
                        display = { dpFormat.format(it.toInt()) },
                    ) { scope.launch { repository.setVariantKeyHeightDp(variant, it.toInt()) } }
                }
                // The variant may turn the number row on or off for itself,
                // and that override is what decides whether the row is drawn
                // on this screen shape — so it, not the global switch, is what
                // makes the height mean something here.
                item(visible = shapeNumberRow, level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.layout_number_row_height_title,
                        value = settings.watch {
                            it.sizingValuesFor(variant).numberRowHeightDp ?: it.numberRowHeightDp
                        }.toFloat(),
                        range = 32f..100f,
                        display = { dpFormat.format(it.toInt()) },
                    ) {
                        scope.launch {
                            repository.setVariantNumberRowHeightDp(variant, it.toInt())
                        }
                    }
                }
                item(level = SettingLevel.STANDARD) {
                    SliderSetting(
                        R.string.layout_bottom_padding_title,
                        value = (
                            settings.watch { it.sizingValuesFor(variant).bottomPaddingDp ?: it.bottomPaddingDp }
                                ?: autoBottomPadding
                            ).toFloat(),
                        range = 0f..SettingsRepository.MAX_BOTTOM_PADDING_DP.toFloat(),
                        display = { dpFormat.format(it.toInt()) },
                    ) { scope.launch { repository.setVariantBottomPaddingDp(variant, it.toInt()) } }
                }
                item(level = SettingLevel.STANDARD) {
                    SliderSetting(
                        R.string.layout_keyboard_width_title,
                        value = settings.watch {
                            it.sizingValuesFor(variant).keyboardWidthPercent ?: it.keyboardWidthPercent
                        }.toFloat(),
                        range = 50f..100f,
                        display = { percentFormat.format(it.toInt()) },
                    ) { scope.launch { repository.setVariantWidthPercent(variant, it.toInt()) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_font_size_title,
                        value = settings.watch { it.sizingValuesFor(variant).fontScale ?: it.fontScale },
                        range = KeyFontScaleRange,
                        display = { multiplierFormat.format(it) },
                    ) { scope.launch { repository.setVariantFontScale(variant, it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    SliderSetting(
                        R.string.layout_key_spacing_title,
                        value = settings.watch { it.sizingValuesFor(variant).keyGapScale ?: it.keyGapScale },
                        range = 0f..2f,
                        display = { percentFormat.format((it * 100).toInt()) },
                    ) { scope.launch { repository.setVariantKeyGapScale(variant, it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.layout_side_padding_left_title,
                        value = settings.watch {
                            it.sizingValuesFor(variant).sidePadLeftScale ?: it.layoutBehavior.sidePadLeftScale
                        },
                        range = SidePadScaleRange,
                        display = { percentFormat.format((it * 100).toInt()) },
                    ) { scope.launch { repository.setVariantSidePadLeftScale(variant, it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.layout_side_padding_right_title,
                        value = settings.watch {
                            it.sizingValuesFor(variant).sidePadRightScale ?: it.layoutBehavior.sidePadRightScale
                        },
                        range = SidePadScaleRange,
                        display = { percentFormat.format((it * 100).toInt()) },
                    ) { scope.launch { repository.setVariantSidePadRightScale(variant, it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    val followKeys = stringResource(R.string.layout_bottom_row_follow_keys_label)
                    SliderSetting(
                        R.string.layout_bottom_row_height_title,
                        value = settings.watch {
                            it.sizingValuesFor(variant).bottomRowHeightDp
                                ?: it.layoutBehavior.bottomRowHeightDp
                        }.toFloat(),
                        range = 0f..BottomRowHeightRange.last.toFloat(),
                        display = {
                            if (it.toInt() == 0) followKeys else dpFormat.format(it.toInt())
                        },
                    ) {
                        scope.launch { repository.setVariantBottomRowHeightDp(variant, it.toInt()) }
                    }
                }
                item {
                    // The one per-shape choice that is not a number. Landscape
                    // has the least room for a sixth row and the most need for
                    // the keys under it.
                    ToggleSetting(
                        R.string.layout_number_row_title,
                        null,
                        shapeNumberRow,
                    ) { scope.launch { repository.setVariantNumberRow(variant, it) } }
                }
                // Gates on the two shared switches (#611), not switches of
                // their own: the mode stays one setting, and this says whether
                // the shape honours it. Unset, they show the shared rules
                // ("Portrait only", "Split only on wide screens").
                item {
                    ToggleSetting(
                        R.string.layout_variant_allow_one_handed_title,
                        stringResource(R.string.layout_variant_allow_one_handed_subtitle),
                        settings.watch { it.oneHandedAllowedOn(variant) },
                    ) { scope.launch { repository.setVariantAllowOneHanded(variant, it) } }
                }
                item {
                    ToggleSetting(
                        R.string.layout_variant_allow_split_title,
                        stringResource(R.string.layout_variant_allow_split_subtitle),
                        settings.watch { it.splitAllowedOn(variant) },
                    ) { scope.launch { repository.setVariantAllowSplit(variant, it) } }
                }
                item(visible = shapeNarrowed, level = SettingLevel.ADVANCED) {
                    ChoiceSetting(
                        title = R.string.layout_keyboard_position_title,
                        options = KeyboardAlignment.entries.map { alignment ->
                            alignment to stringResource(layoutAlignmentLabelRes(alignment))
                        },
                        selected = settings.watch {
                            it.sizingValuesFor(variant).keyboardAlignment ?: it.keyboardAlignment
                        },
                    ) { scope.launch { repository.setVariantAlignment(variant, it) } }
                }
                if (shapeOverridden) {
                    item {
                        NavRow(
                            R.string.layout_follow_portrait_title,
                            stringResource(
                                R.string.layout_follow_portrait_subtitle,
                                stringResource(variant.labelRes),
                            ),
                            onClick = { scope.launch { repository.clearVariantSizing(variant) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LayoutOneHandedSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val dpFormat = stringResource(R.string.typing_value_dp)
    val percentFormat = stringResource(R.string.typing_value_percent)
    // What decides which rows the group holds; each row reads its own value.
    val oneHanded = settings.watch { it.oneHandedMode != OneHandedMode.OFF }
    val split = settings.watch { it.splitKeyboard }
    // Either orientation floating (#584) shows the panel's own rows.
    val floating = settings.watch { it.floatingFor(false) || it.floatingFor(true) }
    SettingsGroup(
        stringResource(R.string.layout_one_handed_group_title),
        info = stringResource(R.string.layout_one_handed_caption),
    ) {
        item {
            ChoiceSetting(
                title = R.string.layout_one_handed_title,
                subtitle = stringResource(R.string.layout_one_handed_subtitle),
                options = OneHandedMode.entries.map { mode ->
                    mode to stringResource(layoutOneHandedModeLabelRes(mode))
                },
                selected = settings.watch { it.oneHandedMode },
                default = SettingsDefaults.oneHandedMode,
            ) { scope.launch { repository.setOneHandedMode(it) } }
        }
        if (oneHanded) item {
            ToggleSetting(
                R.string.layout_one_handed_portrait_only_title,
                stringResource(R.string.layout_one_handed_portrait_only_subtitle),
                settings.watch { it.oneHanded.portraitOnly },
                info = stringResource(R.string.layout_one_handed_portrait_only_info),
                default = SettingsDefaults.oneHanded.portraitOnly,
            ) { scope.launch { repository.setOneHandedPortraitOnly(it) } }
        }
        val orientations = listOf(
            false to R.string.layout_orientation_portrait_label,
            true to R.string.layout_orientation_landscape_label,
        )
        for ((landscape, orientationRes) in orientations) {
            if (oneHanded) item {
                val orientationLabel = stringResource(orientationRes)
                SliderSetting(
                    stringResource(R.string.layout_one_handed_width_title, orientationLabel),
                    subtitle = stringResource(
                        R.string.layout_one_handed_width_subtitle,
                        orientationLabel,
                    ),
                    value = settings.watch { it.oneHanded.forLandscape(landscape).widthPercent }.toFloat(),
                    range = SettingsRepository.ONE_HANDED_WIDTH_MIN.toFloat()..
                        SettingsRepository.ONE_HANDED_WIDTH_MAX.toFloat(),
                    display = { percentFormat.format(it.toInt()) },
                    info = stringResource(R.string.layout_one_handed_width_info),
                    icon = SettingsRowIcons[R.string.layout_one_handed_width_title],
                    default = SettingsDefaults.oneHanded.forLandscape(landscape)
                        .widthPercent.toFloat(),
                ) { scope.launch { repository.setOneHandedWidthPercent(landscape, it.toInt()) } }
            }
            if (oneHanded) item {
                SliderSetting(
                    stringResource(
                        R.string.layout_one_handed_height_title,
                        stringResource(orientationRes),
                    ),
                    subtitle = stringResource(R.string.layout_one_handed_height_subtitle),
                    value = settings.watch { it.oneHanded.forLandscape(landscape).heightScale }.toFloat(),
                    range = SettingsRepository.ONE_HANDED_HEIGHT_SCALE_MIN.toFloat()..
                        SettingsRepository.ONE_HANDED_HEIGHT_SCALE_MAX.toFloat(),
                    display = { percentFormat.format(it.toInt()) },
                    info = stringResource(R.string.layout_one_handed_height_info),
                    icon = SettingsRowIcons[R.string.layout_one_handed_height_title],
                    default = SettingsDefaults.oneHanded.forLandscape(landscape)
                        .heightScale.toFloat(),
                ) { scope.launch { repository.setOneHandedHeightScale(landscape, it.toInt()) } }
            }
            item {
                val orientationLabel = stringResource(orientationRes)
                ChoiceSetting(
                    title = stringResource(
                        R.string.layout_one_handed_side_title,
                        orientationLabel,
                    ),
                    subtitle = stringResource(
                        R.string.layout_one_handed_side_subtitle,
                        orientationLabel,
                    ),
                    icon = SettingsRowIcons[R.string.layout_one_handed_side_title],
                    options = OneHandedSide.entries.map { side ->
                        side to stringResource(layoutOneHandedSideLabelRes(side))
                    },
                    selected = settings.watch { it.oneHanded.forLandscape(landscape).side },
                    default = SettingsDefaults.oneHanded.forLandscape(landscape).side,
                ) { scope.launch { repository.setOneHandedSide(landscape, it) } }
            }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.layout_split_title,
                stringResource(R.string.layout_split_subtitle),
                split,
                info = stringResource(R.string.layout_split_info),
                default = SettingsDefaults.splitKeyboard,
            ) { scope.launch { repository.setSplitKeyboard(it) } }
        }
        item(visible = split, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_split_large_only_title,
                stringResource(R.string.layout_split_large_only_subtitle),
                settings.watch { it.layoutBehavior.splitOnlyOnLargeScreens },
                info = stringResource(R.string.layout_split_large_only_info),
                default = SettingsDefaults.layoutBehavior.splitOnlyOnLargeScreens,
            ) { scope.launch { repository.setSplitOnlyOnLargeScreens(it) } }
        }
        item(visible = split, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.layout_split_gap_title,
                subtitle = stringResource(R.string.layout_split_gap_subtitle),
                value = settings.watch { it.splitGapPercent }.toFloat(),
                // Up to 60% (#952): a big tablet in landscape wants the halves
                // right out at the edges, under the thumbs.
                range = 5f..60f,
                display = { percentFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_split_gap_info),
                default = SettingsDefaults.splitGapPercent.toFloat(),
            ) { scope.launch { repository.setSplitGapPercent(it.toInt()) } }
        }
        item(visible = split, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_split_spacebar_title,
                stringResource(R.string.layout_split_spacebar_subtitle),
                settings.watch { it.layoutBehavior.splitSpacebar },
                info = stringResource(R.string.layout_split_spacebar_info),
                default = SettingsDefaults.layoutBehavior.splitSpacebar,
            ) { scope.launch { repository.setSplitSpacebar(it) } }
        }
        // One switch per orientation (#584): each remembers its own, as the
        // toolbar tool and the dock button do for the one the phone is in.
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.layout_floating_title,
                stringResource(R.string.layout_floating_subtitle),
                settings.watch { it.floatingFor(false) },
                info = stringResource(R.string.layout_floating_info),
                default = SettingsDefaults.floatingKeyboard,
            ) { scope.launch { repository.setFloatingKeyboard(false, it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_floating_landscape_title,
                stringResource(R.string.layout_floating_landscape_subtitle),
                settings.watch { it.floatingFor(true) },
                info = stringResource(R.string.layout_floating_landscape_info),
                default = SettingsDefaults.floatingFor(true),
            ) { scope.launch { repository.setFloatingKeyboard(true, it) } }
        }
        // The platform's sideways text box over the app (#883).
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_landscape_fullscreen_title,
                stringResource(R.string.layout_landscape_fullscreen_subtitle),
                settings.watch { it.layoutBehavior.landscapeFullscreen },
                info = stringResource(R.string.layout_landscape_fullscreen_info),
                default = SettingsDefaults.layoutBehavior.landscapeFullscreen,
            ) { scope.launch { repository.setLandscapeFullscreen(it) } }
        }
        item(visible = floating, level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.layout_floating_handle_title,
                subtitle = stringResource(R.string.layout_floating_handle_subtitle),
                options = listOf(
                    FloatingHandlePosition.BOTTOM to stringResource(R.string.layout_floating_handle_bottom_label),
                    FloatingHandlePosition.TOP to stringResource(R.string.layout_floating_handle_top_label),
                ),
                selected = settings.watch { it.floatingHandle },
                default = SettingsDefaults.floatingHandle,
                info = stringResource(R.string.layout_floating_handle_info),
            ) { scope.launch { repository.setFloatingHandle(it) } }
        }
        item(visible = floating, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.layout_floating_width_title,
                subtitle = stringResource(R.string.layout_floating_width_subtitle),
                value = settings.watch { it.floatingWidthDp }.toFloat(),
                range = 240f..500f,
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_floating_width_info),
                default = SettingsDefaults.floatingWidthDp.toFloat(),
            ) { scope.launch { repository.setFloatingWidthDp(it.toInt()) } }
        }
        item(visible = floating, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.layout_floating_height_title,
                subtitle = stringResource(R.string.layout_floating_height_subtitle),
                value = settings.watch { it.floatingHeightScale },
                range = 0.6f..1.6f,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_floating_height_info),
                default = SettingsDefaults.floatingHeightScale,
            ) { scope.launch { repository.setFloatingHeightScale(it) } }
        }
        item(visible = floating, level = SettingLevel.ADVANCED) {
            val movedFloating = settings.watch { s ->
                s.floatingWidthDp != SettingsDefaults.floatingWidthDp ||
                    s.floatingHeightScale != SettingsDefaults.floatingHeightScale ||
                    s.floatingXFraction != SettingsDefaults.floatingXFraction ||
                    s.floatingYFraction != SettingsDefaults.floatingYFraction
            }
            if (movedFloating) {
                ActionRow(
                    title = R.string.layout_floating_reset_title,
                    subtitle = stringResource(R.string.layout_floating_reset_subtitle),
                    action = stringResource(CommonR.string.common_reset),
                ) { scope.launch { repository.resetFloatingGeometry() } }
            }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_persistent_title,
                stringResource(R.string.layout_persistent_subtitle),
                settings.watch { it.persistentKeyboard },
                info = stringResource(R.string.layout_persistent_info),
                default = SettingsDefaults.persistentKeyboard,
            ) { scope.launch { repository.setPersistentKeyboard(it) } }
        }
    }
}
/** The name drawn on the segmented button for each [KeyboardAlignment]. */
@StringRes
private fun layoutAlignmentLabelRes(alignment: KeyboardAlignment): Int = when (alignment) {
    KeyboardAlignment.LEFT -> R.string.layout_edge_left_label
    KeyboardAlignment.CENTER -> R.string.layout_edge_centre_label
    KeyboardAlignment.RIGHT -> R.string.layout_edge_right_label
}
/** The name drawn on the segmented button for each [OneHandedMode]. */
@StringRes
private fun layoutOneHandedModeLabelRes(mode: OneHandedMode): Int = when (mode) {
    OneHandedMode.OFF -> CommonR.string.common_off
    OneHandedMode.LEFT -> R.string.layout_edge_left_label
    OneHandedMode.RIGHT -> R.string.layout_edge_right_label
}
/** The name drawn on the segmented button for each [OneHandedSide]. */
@StringRes
private fun layoutOneHandedSideLabelRes(side: OneHandedSide): Int = when (side) {
    OneHandedSide.LEFT -> R.string.layout_edge_left_label
    OneHandedSide.RIGHT -> R.string.layout_edge_right_label
}

/**
 * How the pinned tools share the toolbar. Two booleans in the repository
 * (greedy, scrollable), one decision on screen: scrolling overrode spreading
 * whenever both were on, so the pair only ever meant one of these three.
 */
// Private to this file, so [ChoiceOptionIcons] cannot name it: the glyph rides
// on the constant instead, beside the labelRes and descRes already there.
private enum class ToolbarFit(
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
    val icon: ImageVector,
) {
    FIXED(
        R.string.appearance_toolbar_fit_fixed_label,
        R.string.appearance_toolbar_fit_fixed_desc,
        Icons.Outlined.ViewColumn,
    ),
    SPREAD(
        R.string.appearance_toolbar_fit_spread_label,
        R.string.appearance_toolbar_fit_spread_desc,
        Icons.Outlined.ViewWeek,
    ),
    SCROLL(
        R.string.appearance_toolbar_fit_scroll_label,
        R.string.appearance_toolbar_fit_scroll_desc,
        Icons.Outlined.SwapHoriz,
    ),
}

/** Where the native digits are typed under each answer, for the picker sheet. */
@StringRes
private fun numeralScopeDescRes(scope: NumeralCommitScope): Int = when (scope) {
    NumeralCommitScope.TEXT_ONLY -> R.string.layout_numeral_scope_text_desc
    NumeralCommitScope.EVERYWHERE -> R.string.layout_numeral_scope_everywhere_desc
    NumeralCommitScope.DISPLAY_ONLY -> R.string.layout_numeral_scope_display_desc
}

private fun toolbarPlacementDescRes(placement: ToolbarPlacement): Int = when (placement) {
    ToolbarPlacement.STRIP -> R.string.appearance_toolbar_placement_strip_desc
    ToolbarPlacement.ON_DEMAND_ROW -> R.string.appearance_toolbar_placement_button_desc
    ToolbarPlacement.ALWAYS_ROW -> R.string.appearance_toolbar_placement_always_desc
}

private val DefaultToolbarFit = when {
    SettingsDefaults.toolbarBehavior.scrollable -> ToolbarFit.SCROLL
    SettingsDefaults.toolbarBehavior.greedy -> ToolbarFit.SPREAD
    else -> ToolbarFit.FIXED
}

/**
 * The toolbar hold delay a slider position stands for: 50 ms steps, with the
 * first stretch of the track kept for the system's own delay (0), since a
 * hold shorter than a tenth of a second is a tap.
 */
private fun toolbarHoldDelayStep(value: Float): Int =
    if (value < 100f) 0 else ((value / 50f).roundToInt() * 50).coerceAtMost(1500)
