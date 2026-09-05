package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.settings.BottomRowHeightRange
import com.wasimaster.wmkeyboard.core.settings.SidePadScaleRange
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import com.wasimaster.wmkeyboard.core.settings.KeyboardAlignment
import com.wasimaster.wmkeyboard.core.script.NumeralCommitScope
import com.wasimaster.wmkeyboard.core.settings.DefaultToolbarTools
import com.wasimaster.wmkeyboard.core.settings.KeyFontScaleRange
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.OneHandedSide
import com.wasimaster.wmkeyboard.core.settings.ScreenVariant
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sizingValuesFor
import com.wasimaster.wmkeyboard.core.settings.ToolbarPlacement
import com.wasimaster.wmkeyboard.core.settings.ToolboxLayout
import com.wasimaster.wmkeyboard.core.settings.ToolboxPageSizeRange
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    settings: KeyboardSettings,
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
    // Turning the toolbar off is guarded — it hides suggestions and every tool.
    var confirmDisableToolbar by remember { mutableStateOf(false) }
    var toolShapePickerOpen by rememberSaveable { mutableStateOf(false) }
    if (toolShapePickerOpen) {
        KeyShapePickerDialog(
            selected = settings.toolShape,
            radiusDp = settings.toolCircleRadiusDp,
            onPick = { kind ->
                scope.launch { repository.setToolShape(kind) }
                toolShapePickerOpen = false
            },
            onDismiss = { toolShapePickerOpen = false },
            title = R.string.appearance_tool_shape_title,
        )
    }
    SettingsGroup(stringResource(R.string.appearance_style_section_title)) {
        item {
            val selected = com.wasimaster.wmkeyboard.core.theme.findThemeSpec(
                settings.keyboardThemeId,
                settings.customThemes,
            )
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
        item {
            NavRow(
                R.string.appearance_font_title,
                stringResource(R.string.appearance_font_subtitle),
                value = KeyboardFonts.genericDisplayName(
                    LocalContext.current,
                    settings.keyFontId,
                    settings.customFontName,
                ),
                route = "fonts",
                onClick = onOpenFonts,
            )
        }
        item {
            val active = settings.icons.activePackId
            val changed = settings.icons.overrides.size
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
        item {
            SliderSetting(
                R.string.appearance_key_corner_radius_title,
                subtitle = stringResource(R.string.appearance_key_corner_radius_subtitle),
                value = settings.keyCornerRadiusDp.toFloat(),
                range = 0f..28f,
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.appearance_key_corner_radius_info),
                default = SettingsDefaults.keyCornerRadiusDp.toFloat(),
            ) { scope.launch { repository.setKeyCornerRadiusDp(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.appearance_key_label_size_title,
                subtitle = stringResource(R.string.appearance_key_label_size_subtitle),
                value = settings.fontScale,
                range = KeyFontScaleRange,
                display = { multiplierFormat.format(it) },
                info = stringResource(R.string.appearance_key_label_size_info),
                default = SettingsDefaults.fontScale,
            ) { scope.launch { repository.setFontScale(it) } }
        }
        item {
            SliderSetting(
                R.string.appearance_key_hint_size_title,
                subtitle = stringResource(R.string.appearance_key_hint_size_subtitle),
                value = settings.layoutBehavior.hintFontScale,
                range = 0.5f..2.0f,
                display = { multiplierFormat.format(it) },
                info = stringResource(R.string.appearance_key_hint_size_info),
                default = SettingsDefaults.layoutBehavior.hintFontScale,
            ) { scope.launch { repository.setHintFontScale(it) } }
        }
    }

    SettingsGroup {
        item {
            NavRow(
                R.string.appearance_toolbar_section_title,
                stringResource(R.string.appearance_toolbar_section_subtitle),
                route = "appearance/toolbar",
            ) {
                onNavigate("appearance/toolbar")
            }
        }
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
}

@Composable
internal fun AppearanceToolbarSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val dpFormat = stringResource(R.string.typing_value_dp)
    val spFormat = stringResource(R.string.values_sp)
    val percentFormat = stringResource(R.string.typing_value_percent)
    var confirmDisableToolbar by remember { mutableStateOf(false) }
    var toolShapePickerOpen by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(stringResource(R.string.appearance_toolbar_section_title)) {
        item {
            ToggleSetting(
                R.string.appearance_toolbar_show_title,
                stringResource(R.string.appearance_toolbar_show_subtitle),
                settings.toolbarBehavior.enabled,
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
        if (settings.toolbarBehavior.enabled) {
            item {
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
                    selected = settings.toolbarBehavior.placement,
                    onChange = { scope.launch { repository.setToolbarPlacement(it) } },
                    default = SettingsDefaults.toolbarBehavior.placement,
                )
            }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_swipe_down_title,
                stringResource(R.string.appearance_toolbar_swipe_down_subtitle),
                settings.toolbarBehavior.swipeDownHide,
                info = stringResource(R.string.appearance_toolbar_swipe_down_info),
                default = SettingsDefaults.toolbarBehavior.swipeDownHide,
            ) { scope.launch { repository.setToolbarSwipeDownHide(it) } }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_hardware_only_title,
                stringResource(R.string.appearance_toolbar_hardware_only_subtitle),
                settings.toolbarBehavior.onlyWithHardwareKeyboard,
                info = stringResource(R.string.appearance_toolbar_hardware_only_info),
                default = SettingsDefaults.toolbarBehavior.onlyWithHardwareKeyboard,
            ) { scope.launch { repository.setToolbarOnlyWithHardwareKeyboard(it) } }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_rtl_title,
                stringResource(R.string.appearance_toolbar_rtl_subtitle),
                settings.toolbarBehavior.reverseForRtl,
                info = stringResource(R.string.appearance_toolbar_rtl_info),
                default = SettingsDefaults.toolbarBehavior.reverseForRtl,
            ) { scope.launch { repository.setReverseToolbarForRtl(it) } }
        }
        item {
            val fit = when {
                settings.toolbarBehavior.scrollable -> ToolbarFit.SCROLL
                settings.toolbarBehavior.greedy -> ToolbarFit.SPREAD
                else -> ToolbarFit.FIXED
            }
            ChoiceSetting(
                R.string.appearance_toolbar_fit_title,
                subtitle = stringResource(R.string.appearance_toolbar_fit_subtitle),
                info = stringResource(R.string.appearance_toolbar_fit_info),
                options = ToolbarFit.entries.map { it to stringResource(it.labelRes) },
                selected = fit,
                default = DefaultToolbarFit,
            ) { chosen ->
                scope.launch {
                    repository.setToolbarGreedy(chosen == ToolbarFit.SPREAD)
                    repository.setToolbarScrollable(chosen == ToolbarFit.SCROLL)
                }
            }
        }
        item {
            SliderSetting(
                R.string.appearance_toolbar_height_title,
                subtitle = stringResource(R.string.appearance_toolbar_height_subtitle),
                value = settings.toolbarHeightDp.toFloat(),
                range = 32f..80f,
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_toolbar_height_info),
                default = SettingsDefaults.toolbarHeightDp.toFloat(),
            ) { scope.launch { repository.setToolbarHeightDp(it.roundToInt()) } }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_lock_title,
                stringResource(R.string.appearance_toolbar_lock_subtitle),
                settings.toolbarBehavior.hideWhenLocked,
                info = stringResource(R.string.appearance_toolbar_lock_info),
                default = SettingsDefaults.toolbarBehavior.hideWhenLocked,
            ) { scope.launch { repository.setToolbarHideWhenLocked(it) } }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_labels_title,
                stringResource(R.string.appearance_toolbar_labels_subtitle),
                settings.toolbarLabels,
                info = stringResource(R.string.appearance_toolbar_labels_info),
                default = SettingsDefaults.toolbarLabels,
            ) { scope.launch { repository.setToolbarLabels(it) } }
        }
        if (settings.toolbarLabels) {
            item {
                SliderSetting(
                    R.string.appearance_toolbar_label_size_title,
                    subtitle = stringResource(R.string.appearance_toolbar_label_size_subtitle),
                    value = settings.toolbarLabelSize.toFloat(),
                    range = 7f..14f,
                    display = { spFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.toolbarLabelSize.toFloat(),
                ) { scope.launch { repository.setToolbarLabelSize(it.roundToInt()) } }
            }
        }
        item {
            SliderSetting(
                R.string.appearance_suggestion_text_size_title,
                subtitle = stringResource(R.string.appearance_suggestion_text_size_subtitle),
                value = settings.suggestionStrip.textScale,
                range = 0.8f..1.6f,
                display = { percentFormat.format((it * 100).roundToInt()) },
                info = stringResource(R.string.appearance_suggestion_text_size_info),
                default = SettingsDefaults.suggestionStrip.textScale,
            ) { scope.launch { repository.setSuggestionTextScale(it) } }
        }
        item {
            SliderSetting(
                R.string.appearance_suggestion_spacing_title,
                subtitle = stringResource(R.string.appearance_suggestion_spacing_subtitle),
                value = settings.suggestionStrip.chipPadding.toFloat(),
                range = 0f..24f,
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_suggestion_spacing_info),
                default = SettingsDefaults.suggestionStrip.chipPadding.toFloat(),
            ) { scope.launch { repository.setSuggestionChipPadding(it.roundToInt()) } }
        }
        item {
            ResetPinnedToolsSetting(repository, scope)
        }
        // The grid's own order. "Reset pinned tools" restored the bar and
        // nothing restored the grid, so a bad drag session there had no way
        // back. Drawn only once the order has actually been changed.
        if (settings.toolboxOrder != SettingsDefaults.toolboxOrder) {
            item {
                ActionRow(
                    title = R.string.appearance_reset_toolbox_order_title,
                    subtitle = stringResource(R.string.appearance_reset_toolbox_order_subtitle),
                    action = stringResource(CommonR.string.common_reset),
                    confirm = stringResource(R.string.appearance_reset_toolbox_order_confirm),
                ) { scope.launch { repository.resetToolboxOrder() } }
            }
        }
        item {
            val offLabel = stringResource(CommonR.string.common_off)
            SliderSetting(
                R.string.appearance_tool_circle_title,
                subtitle = stringResource(R.string.appearance_tool_circle_subtitle),
                value = settings.toolCircleRadiusDp.toFloat(),
                range = 0f..20f,
                display = { if (it.toInt() == 0) offLabel else dpFormat.format(it.toInt()) },
                info = stringResource(R.string.appearance_tool_circle_info),
                default = SettingsDefaults.toolCircleRadiusDp.toFloat(),
            ) { scope.launch { repository.setToolCircleRadiusDp(it.toInt()) } }
        }
        // The same shapes the keys and the popups use. It draws nothing while
        // the radius above is at 0, which is the setting for "no background".
        if (settings.toolCircleRadiusDp > 0) {
            item {
                NavRow(
                    R.string.appearance_tool_shape_title,
                    subtitle = stringResource(R.string.appearance_tool_shape_subtitle),
                    value = keyShapeName(settings.toolShape),
                    onClick = { toolShapePickerOpen = true },
                )
            }
        }
        item {
            SliderSetting(
                R.string.appearance_tool_width_title,
                subtitle = stringResource(R.string.appearance_tool_width_subtitle),
                value = settings.toolbarBehavior.toolWidthDp.toFloat(),
                range = 38f..64f,
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_tool_width_info),
                default = SettingsDefaults.toolbarBehavior.toolWidthDp.toFloat(),
            ) { scope.launch { repository.setToolbarToolWidthDp(it.roundToInt()) } }
        }
        item {
            ChoiceSetting(
                R.string.appearance_toolbox_layout_title,
                subtitle = stringResource(R.string.appearance_toolbox_layout_subtitle),
                options = listOf(
                    ToolboxLayout.ICONS to
                        stringResource(R.string.appearance_toolbox_layout_icons_label),
                    ToolboxLayout.PILLS to
                        stringResource(R.string.appearance_toolbox_layout_pills_label),
                ),
                selected = settings.toolbox.layout,
                info = stringResource(R.string.appearance_toolbox_layout_info),
                default = SettingsDefaults.toolbox.layout,
            ) { scope.launch { repository.setToolboxLayout(it) } }
        }
        if (settings.toolbox.layout == ToolboxLayout.ICONS) {
            item {
                val perRow = stringResource(R.string.appearance_slider_per_row_value)
                SliderSetting(
                    R.string.appearance_toolbox_columns_title,
                    subtitle = stringResource(R.string.appearance_toolbox_columns_subtitle),
                    value = settings.toolboxColumns.toFloat(),
                    range = 3f..6f,
                    display = { perRow.format(it.roundToInt()) },
                    info = stringResource(R.string.appearance_toolbox_columns_info),
                    default = SettingsDefaults.toolboxColumns.toFloat(),
                ) { scope.launch { repository.setToolboxColumns(it.roundToInt()) } }
            }
        } else {
            item {
                val perRow = stringResource(R.string.appearance_slider_per_row_value)
                SliderSetting(
                    R.string.appearance_toolbox_pill_columns_title,
                    subtitle = stringResource(R.string.appearance_toolbox_pill_columns_subtitle),
                    value = settings.toolbox.pillColumns.toFloat(),
                    range = 1f..3f,
                    display = { perRow.format(it.roundToInt()) },
                    info = stringResource(R.string.appearance_toolbox_pill_columns_info),
                    default = SettingsDefaults.toolbox.pillColumns.toFloat(),
                ) { scope.launch { repository.setToolboxPillColumns(it.roundToInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.appearance_toolbox_pill_filled_title,
                    stringResource(R.string.appearance_toolbox_pill_filled_subtitle),
                    settings.toolbox.pillFilled,
                    info = stringResource(R.string.appearance_toolbox_pill_filled_info),
                    default = SettingsDefaults.toolbox.pillFilled,
                ) { scope.launch { repository.setToolboxPillFilled(it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbox_paginate_title,
                stringResource(R.string.appearance_toolbox_paginate_subtitle),
                settings.toolbox.paginate,
                info = stringResource(R.string.appearance_toolbox_paginate_info),
                default = SettingsDefaults.toolbox.paginate,
            ) { scope.launch { repository.setToolboxPaginate(it) } }
        }
        if (settings.toolbox.paginate) {
            item {
                val perPage = stringResource(R.string.appearance_slider_per_page_value)
                SliderSetting(
                    R.string.appearance_toolbox_page_size_title,
                    subtitle = stringResource(R.string.appearance_toolbox_page_size_subtitle),
                    value = settings.toolbox.pageSize.toFloat(),
                    range = ToolboxPageSizeRange.first.toFloat()..ToolboxPageSizeRange.last.toFloat(),
                    display = { perPage.format(it.roundToInt()) },
                    info = stringResource(R.string.appearance_toolbox_page_size_info),
                    default = SettingsDefaults.toolbox.pageSize.toFloat(),
                ) { scope.launch { repository.setToolboxPageSize(it.roundToInt()) } }
            }
        }
        item {
            val followToolbar = stringResource(R.string.appearance_toolbox_label_size_follow)
            SliderSetting(
                R.string.appearance_toolbox_label_size_title,
                subtitle = stringResource(R.string.appearance_toolbox_label_size_subtitle),
                value = settings.toolbox.labelSizeSp.toFloat(),
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
        // Drawn only once something has actually moved, like the group reset on
        // Layout & size. Theme, font and icons are excluded on both sides of
        // this: they lead to their own screens and are not what "reset the
        // sliders" means.
        val d = SettingsDefaults
        val appearanceMoved = settings.keyCornerRadiusDp != d.keyCornerRadiusDp ||
            settings.fontScale != d.fontScale ||
            settings.layoutBehavior.hintFontScale != d.layoutBehavior.hintFontScale ||
            settings.toolbarBehavior != d.toolbarBehavior ||
            settings.toolbarHeightDp != d.toolbarHeightDp ||
            settings.toolbarLabels != d.toolbarLabels ||
            settings.toolbarLabelSize != d.toolbarLabelSize ||
            settings.suggestionStrip.textScale != d.suggestionStrip.textScale ||
            settings.suggestionStrip.chipPadding != d.suggestionStrip.chipPadding ||
            settings.toolCircleRadiusDp != d.toolCircleRadiusDp ||
            settings.toolShape != d.toolShape ||
            settings.toolbox != d.toolbox ||
            settings.toolboxColumns != d.toolboxColumns
        if (appearanceMoved) {
            item {
                ActionRow(
                    title = R.string.appearance_reset_title,
                    subtitle = stringResource(R.string.appearance_reset_subtitle),
                    action = stringResource(CommonR.string.common_reset),
                    confirm = stringResource(R.string.appearance_reset_confirm),
                ) { scope.launch { repository.resetAppearance() } }
            }
        }
    }
}
// ---- layout & size ----

@Composable
internal fun LayoutSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val dpFormat = stringResource(R.string.typing_value_dp)
    // The keyboard's shape as the rows below set it, pinned so it stays in
    // view while they change (#43).
    RegisterPinned {
        MiniKeyboardPreview(
            numberRow = settings.numberRow,
            globeAsEmoji = settings.globeAsEmoji,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    SettingsGroup(stringResource(R.string.layout_number_row_title)) {
        item {
            ToggleSetting(
                R.string.layout_number_row_title,
                stringResource(R.string.layout_number_row_subtitle),
                settings.numberRow,
                info = stringResource(R.string.layout_number_row_info),
                default = SettingsDefaults.numberRow,
            ) { scope.launch { repository.setNumberRow(it) } }
        }
        if (settings.numberRow) {
            item {
                SliderSetting(
                    R.string.layout_number_row_height_title,
                    subtitle = stringResource(R.string.layout_number_row_height_subtitle),
                    value = settings.numberRowHeightDp.toFloat(),
                    range = 32f..100f,
                    display = { dpFormat.format(it.toInt()) },
                    info = stringResource(R.string.layout_number_row_height_info),
                    default = SettingsDefaults.numberRowHeightDp.toFloat(),
                ) { scope.launch { repository.setNumberRowHeightDp(it.toInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.layout_number_row_shift_symbols_title,
                    stringResource(R.string.layout_number_row_shift_symbols_subtitle),
                    settings.layoutBehavior.numberRowShiftSymbols,
                    info = stringResource(R.string.layout_number_row_shift_symbols_info),
                    default = SettingsDefaults.layoutBehavior.numberRowShiftSymbols,
                ) { scope.launch { repository.setNumberRowShiftSymbols(it) } }
            }
            item {
                ToggleSetting(
                    R.string.layout_number_row_in_symbols_title,
                    stringResource(R.string.layout_number_row_in_symbols_subtitle),
                    settings.layoutBehavior.numberRowInSymbols,
                    info = stringResource(R.string.layout_number_row_in_symbols_info),
                    default = SettingsDefaults.layoutBehavior.numberRowInSymbols,
                ) { scope.launch { repository.setNumberRowInSymbols(it) } }
            }
        }
    }

    SettingsGroup(stringResource(R.string.layout_symbols_title)) {
        item {
            ToggleSetting(
                R.string.layout_symbols_return_title,
                stringResource(R.string.layout_symbols_return_subtitle),
                settings.layoutBehavior.symbolsReturnToLetters,
                info = stringResource(R.string.layout_symbols_return_info),
                default = SettingsDefaults.layoutBehavior.symbolsReturnToLetters,
            ) { scope.launch { repository.setSymbolsReturnToLetters(it) } }
        }
        if (settings.layoutBehavior.symbolsReturnToLetters) {
            item {
                // Saves as it is typed, like the currency keys field; blank
                // restores the default set. Seeded once rather than re-read on
                // every keystroke: the repository drops spaces and duplicates,
                // and feeding that back would move the caret while typing.
                var returnChars by remember {
                    mutableStateOf(settings.layoutBehavior.symbolsReturnCharSet())
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.layout_symbols_return_chars_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        InfoButton(
                            stringResource(R.string.layout_symbols_return_chars_title),
                            stringResource(R.string.layout_symbols_return_chars_info),
                        )
                    }
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
        }
    }

    SettingsGroup(
        stringResource(R.string.layout_numerals_title),
        info = stringResource(R.string.layout_numerals_caption),
    ) {
        item {
            ChoiceSetting(
                R.string.layout_numeral_scope_title,
                subtitle = stringResource(R.string.layout_numeral_scope_subtitle),
                info = stringResource(R.string.layout_numeral_scope_info),
                options = NumeralCommitScope.entries.map { it to stringResource(it.labelRes) },
                selected = settings.layoutBehavior.numeralCommitScope,
                default = SettingsDefaults.layoutBehavior.numeralCommitScope,
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



    SettingsGroup(stringResource(R.string.layout_bottom_row_keys_title)) {
        item {
            ToggleSetting(
                R.string.layout_comma_emoji_title,
                stringResource(R.string.layout_comma_emoji_subtitle),
                settings.commaAsEmoji,
                info = stringResource(R.string.layout_comma_emoji_info),
                default = SettingsDefaults.commaAsEmoji,
            ) { scope.launch { repository.setCommaAsEmoji(it) } }
        }
        item {
            ToggleSetting(
                R.string.layout_globe_emoji_title,
                stringResource(R.string.layout_globe_emoji_subtitle),
                settings.globeAsEmoji,
                info = stringResource(R.string.layout_globe_emoji_info),
                default = SettingsDefaults.globeAsEmoji,
            ) { scope.launch { repository.setGlobeAsEmoji(it) } }
        }
        item {
            ToggleSetting(
                R.string.layout_swap_comma_globe_title,
                stringResource(R.string.layout_swap_comma_globe_subtitle),
                settings.swapCommaAndGlobe,
                info = stringResource(R.string.layout_swap_comma_globe_info),
                default = SettingsDefaults.swapCommaAndGlobe,
            ) { scope.launch { repository.setSwapCommaAndGlobe(it) } }
        }
    }
}

@Composable
internal fun LayoutSizeSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val dpFormat = stringResource(R.string.typing_value_dp)
    val percentFormat = stringResource(R.string.typing_value_percent)
    val multiplierFormat = stringResource(R.string.keypress_value_multiplier)
    var expandedVariant by remember { mutableStateOf<ScreenVariant?>(null) }
    SettingsGroup(stringResource(R.string.layout_size_position_title)) {
        item {
            SliderSetting(
                R.string.layout_key_height_title,
                subtitle = stringResource(R.string.layout_key_height_subtitle),
                value = settings.keyHeightDp.toFloat(),
                range = 32f..100f,
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_key_height_info),
                default = SettingsDefaults.keyHeightDp.toFloat(),
            ) { scope.launch { repository.setKeyHeightDp(it.toInt()) } }
        }
        item {
            val followKeys = stringResource(R.string.layout_bottom_row_follow_keys_label)
            SliderSetting(
                R.string.layout_bottom_row_height_title,
                subtitle = stringResource(R.string.layout_bottom_row_height_subtitle),
                value = settings.layoutBehavior.bottomRowHeightDp.toFloat(),
                range = 0f..BottomRowHeightRange.last.toFloat(),
                display = { if (it < 1f) followKeys else dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_bottom_row_height_info),
                default = SettingsDefaults.layoutBehavior.bottomRowHeightDp.toFloat(),
            ) { scope.launch { repository.setBottomRowHeightDp(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.layout_side_padding_left_title,
                subtitle = stringResource(R.string.layout_side_padding_left_subtitle),
                value = settings.layoutBehavior.sidePadLeftScale,
                range = SidePadScaleRange.start..SidePadScaleRange.endInclusive,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_side_padding_left_info),
                default = SettingsDefaults.layoutBehavior.sidePadLeftScale,
            ) { scope.launch { repository.setSidePadLeftScale(it) } }
        }
        item {
            SliderSetting(
                R.string.layout_side_padding_right_title,
                subtitle = stringResource(R.string.layout_side_padding_right_subtitle),
                value = settings.layoutBehavior.sidePadRightScale,
                range = SidePadScaleRange.start..SidePadScaleRange.endInclusive,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_side_padding_right_info),
                default = SettingsDefaults.layoutBehavior.sidePadRightScale,
            ) { scope.launch { repository.setSidePadRightScale(it) } }
        }
        item {
            SliderSetting(
                R.string.layout_key_spacing_title,
                subtitle = stringResource(R.string.layout_key_spacing_subtitle),
                value = settings.keyGapScale,
                range = 0f..2f,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_key_spacing_info),
                default = SettingsDefaults.keyGapScale,
            ) { scope.launch { repository.setKeyGapScale(it) } }
        }
        item {
            SliderSetting(
                R.string.layout_bottom_padding_title,
                subtitle = stringResource(R.string.layout_bottom_padding_subtitle),
                value = settings.bottomPaddingDp.toFloat(),
                range = 0f..SettingsRepository.MAX_BOTTOM_PADDING_DP.toFloat(),
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_bottom_padding_info),
                default = SettingsDefaults.bottomPaddingDp.toFloat(),
            ) { scope.launch { repository.setBottomPaddingDp(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.layout_keyboard_width_title,
                subtitle = stringResource(R.string.layout_keyboard_width_subtitle),
                value = settings.keyboardWidthPercent.toFloat(),
                range = 50f..100f,
                display = { percentFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_keyboard_width_info),
                default = SettingsDefaults.keyboardWidthPercent.toFloat(),
            ) { scope.launch { repository.setKeyboardWidthPercent(it.toInt()) } }
        }
        if (settings.keyboardWidthPercent < 100) {
            item {
                ChoiceSetting(
                    title = R.string.layout_keyboard_position_title,
                    info = stringResource(R.string.layout_keyboard_position_info),
                    options = KeyboardAlignment.entries.map { alignment ->
                        alignment to stringResource(layoutAlignmentLabelRes(alignment))
                    },
                    selected = settings.keyboardAlignment,
                    default = SettingsDefaults.keyboardAlignment,
                ) { scope.launch { repository.setKeyboardAlignment(it) } }
            }
        }
        // Drawn only once something in the group has actually moved, like the
        // per-row reset controls: on an untouched screen it would be a button
        // that does nothing.
        val sizingMoved = settings.keyHeightDp != SettingsDefaults.keyHeightDp ||
            settings.numberRowHeightDp != SettingsDefaults.numberRowHeightDp ||
            settings.bottomPaddingDp != SettingsDefaults.bottomPaddingDp ||
            settings.keyboardWidthPercent != SettingsDefaults.keyboardWidthPercent ||
            settings.keyboardAlignment != SettingsDefaults.keyboardAlignment ||
            settings.keyGapScale != SettingsDefaults.keyGapScale ||
            settings.keyCornerRadiusDp != SettingsDefaults.keyCornerRadiusDp ||
            settings.layoutBehavior.sidePadLeftScale !=
            SettingsDefaults.layoutBehavior.sidePadLeftScale ||
            settings.layoutBehavior.sidePadRightScale !=
            SettingsDefaults.layoutBehavior.sidePadRightScale ||
            settings.layoutBehavior.bottomRowHeightDp !=
            SettingsDefaults.layoutBehavior.bottomRowHeightDp
        if (sizingMoved) {
            item {
                ActionRow(
                    title = R.string.layout_reset_sizing_title,
                    subtitle = stringResource(R.string.layout_reset_sizing_subtitle),
                    action = stringResource(CommonR.string.common_reset),
                    confirm = stringResource(R.string.layout_reset_sizing_confirm),
                ) { scope.launch { repository.resetSizeAndPosition() } }
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.layout_per_screen_title),
        info = stringResource(R.string.layout_per_screen_caption),
    ) {
        for (variant in ScreenVariant.entries.filter { it.isOverride }) {
            val override = settings.sizingOverrides[variant]
            val values = settings.sizingValuesFor(variant)
            item {
                NavRow(
                    stringResource(variant.labelRes),
                    if (override == null || override.isEmpty) {
                        stringResource(R.string.layout_variant_follows_portrait_label)
                    } else {
                        stringResource(
                            R.string.layout_variant_summary,
                            values.keyHeightDp ?: settings.keyHeightDp,
                            values.keyboardWidthPercent ?: settings.keyboardWidthPercent,
                        )
                    },
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
                        value = values.keyboardScale ?: 1f,
                        range = 0.5f..1.5f,
                        display = { percentFormat.format((it * 100).toInt()) },
                    ) { scope.launch { repository.setVariantKeyboardScale(variant, it) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_key_height_title,
                        value = (values.keyHeightDp ?: settings.keyHeightDp).toFloat(),
                        range = 32f..100f,
                        display = { dpFormat.format(it.toInt()) },
                    ) { scope.launch { repository.setVariantKeyHeightDp(variant, it.toInt()) } }
                }
                if (settings.numberRow) {
                    item {
                        SliderSetting(
                            R.string.layout_number_row_height_title,
                            value = (values.numberRowHeightDp ?: settings.numberRowHeightDp).toFloat(),
                            range = 32f..100f,
                            display = { dpFormat.format(it.toInt()) },
                        ) {
                            scope.launch {
                                repository.setVariantNumberRowHeightDp(variant, it.toInt())
                            }
                        }
                    }
                }
                item {
                    SliderSetting(
                        R.string.layout_bottom_padding_title,
                        value = (values.bottomPaddingDp ?: settings.bottomPaddingDp).toFloat(),
                        range = 0f..SettingsRepository.MAX_BOTTOM_PADDING_DP.toFloat(),
                        display = { dpFormat.format(it.toInt()) },
                    ) { scope.launch { repository.setVariantBottomPaddingDp(variant, it.toInt()) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_keyboard_width_title,
                        value = (values.keyboardWidthPercent ?: settings.keyboardWidthPercent).toFloat(),
                        range = 50f..100f,
                        display = { percentFormat.format(it.toInt()) },
                    ) { scope.launch { repository.setVariantWidthPercent(variant, it.toInt()) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_font_size_title,
                        value = values.fontScale ?: settings.fontScale,
                        range = KeyFontScaleRange,
                        display = { multiplierFormat.format(it) },
                    ) { scope.launch { repository.setVariantFontScale(variant, it) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_key_spacing_title,
                        value = values.keyGapScale ?: settings.keyGapScale,
                        range = 0f..2f,
                        display = { percentFormat.format((it * 100).toInt()) },
                    ) { scope.launch { repository.setVariantKeyGapScale(variant, it) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_side_padding_left_title,
                        value = values.sidePadLeftScale ?: settings.layoutBehavior.sidePadLeftScale,
                        range = SidePadScaleRange,
                        display = { percentFormat.format((it * 100).toInt()) },
                    ) { scope.launch { repository.setVariantSidePadLeftScale(variant, it) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_side_padding_right_title,
                        value = values.sidePadRightScale
                            ?: settings.layoutBehavior.sidePadRightScale,
                        range = SidePadScaleRange,
                        display = { percentFormat.format((it * 100).toInt()) },
                    ) { scope.launch { repository.setVariantSidePadRightScale(variant, it) } }
                }
                item {
                    val followKeys = stringResource(R.string.layout_bottom_row_follow_keys_label)
                    SliderSetting(
                        R.string.layout_bottom_row_height_title,
                        value = (
                            values.bottomRowHeightDp
                                ?: settings.layoutBehavior.bottomRowHeightDp
                            ).toFloat(),
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
                        values.numberRow ?: settings.numberRow,
                    ) { scope.launch { repository.setVariantNumberRow(variant, it) } }
                }
                if ((values.keyboardWidthPercent ?: settings.keyboardWidthPercent) < 100) {
                    item {
                        ChoiceSetting(
                            title = R.string.layout_keyboard_position_title,
                            options = KeyboardAlignment.entries.map { alignment ->
                                alignment to stringResource(layoutAlignmentLabelRes(alignment))
                            },
                            selected = values.keyboardAlignment ?: settings.keyboardAlignment,
                        ) { scope.launch { repository.setVariantAlignment(variant, it) } }
                    }
                }
                if (override != null && !override.isEmpty) {
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
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val dpFormat = stringResource(R.string.typing_value_dp)
    val percentFormat = stringResource(R.string.typing_value_percent)
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
                selected = settings.oneHandedMode,
                default = SettingsDefaults.oneHandedMode,
            ) { scope.launch { repository.setOneHandedMode(it) } }
        }
        val orientations = listOf(
            false to R.string.layout_orientation_portrait_label,
            true to R.string.layout_orientation_landscape_label,
        )
        for ((landscape, orientationRes) in orientations) {
            val profile = settings.oneHanded.forLandscape(landscape)
            item {
                val orientationLabel = stringResource(orientationRes)
                SliderSetting(
                    stringResource(R.string.layout_one_handed_width_title, orientationLabel),
                    subtitle = stringResource(
                        R.string.layout_one_handed_width_subtitle,
                        orientationLabel,
                    ),
                    value = profile.widthPercent.toFloat(),
                    range = SettingsRepository.ONE_HANDED_WIDTH_MIN.toFloat()..
                        SettingsRepository.ONE_HANDED_WIDTH_MAX.toFloat(),
                    display = { percentFormat.format(it.toInt()) },
                    info = stringResource(R.string.layout_one_handed_width_info),
                    default = SettingsDefaults.oneHanded.forLandscape(landscape)
                        .widthPercent.toFloat(),
                ) { scope.launch { repository.setOneHandedWidthPercent(landscape, it.toInt()) } }
            }
            item {
                SliderSetting(
                    stringResource(
                        R.string.layout_one_handed_height_title,
                        stringResource(orientationRes),
                    ),
                    subtitle = stringResource(R.string.layout_one_handed_height_subtitle),
                    value = profile.heightScale.toFloat(),
                    range = SettingsRepository.ONE_HANDED_HEIGHT_SCALE_MIN.toFloat()..
                        SettingsRepository.ONE_HANDED_HEIGHT_SCALE_MAX.toFloat(),
                    display = { percentFormat.format(it.toInt()) },
                    info = stringResource(R.string.layout_one_handed_height_info),
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
                    options = OneHandedSide.entries.map { side ->
                        side to stringResource(layoutOneHandedSideLabelRes(side))
                    },
                    selected = profile.side,
                    default = SettingsDefaults.oneHanded.forLandscape(landscape).side,
                ) { scope.launch { repository.setOneHandedSide(landscape, it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.layout_split_title,
                stringResource(R.string.layout_split_subtitle),
                settings.splitKeyboard,
                info = stringResource(R.string.layout_split_info),
                default = SettingsDefaults.splitKeyboard,
            ) { scope.launch { repository.setSplitKeyboard(it) } }
        }
        if (settings.splitKeyboard) {
            item {
                ToggleSetting(
                    R.string.layout_split_large_only_title,
                    stringResource(R.string.layout_split_large_only_subtitle),
                    settings.layoutBehavior.splitOnlyOnLargeScreens,
                    info = stringResource(R.string.layout_split_large_only_info),
                    default = SettingsDefaults.layoutBehavior.splitOnlyOnLargeScreens,
                ) { scope.launch { repository.setSplitOnlyOnLargeScreens(it) } }
            }
            item {
                SliderSetting(
                    R.string.layout_split_gap_title,
                    subtitle = stringResource(R.string.layout_split_gap_subtitle),
                    value = settings.splitGapPercent.toFloat(),
                    range = 5f..40f,
                    display = { percentFormat.format(it.toInt()) },
                    info = stringResource(R.string.layout_split_gap_info),
                    default = SettingsDefaults.splitGapPercent.toFloat(),
                ) { scope.launch { repository.setSplitGapPercent(it.toInt()) } }
            }
        }
        item {
            ToggleSetting(
                R.string.layout_floating_title,
                stringResource(R.string.layout_floating_subtitle),
                settings.floatingKeyboard,
                info = stringResource(R.string.layout_floating_info),
                default = SettingsDefaults.floatingKeyboard,
            ) { scope.launch { repository.setFloatingKeyboard(it) } }
        }
        if (settings.floatingKeyboard) {
            item {
                SliderSetting(
                    R.string.layout_floating_width_title,
                    subtitle = stringResource(R.string.layout_floating_width_subtitle),
                    value = settings.floatingWidthDp.toFloat(),
                    range = 240f..500f,
                    display = { dpFormat.format(it.toInt()) },
                    info = stringResource(R.string.layout_floating_width_info),
                    default = SettingsDefaults.floatingWidthDp.toFloat(),
                ) { scope.launch { repository.setFloatingWidthDp(it.toInt()) } }
            }
            item {
                SliderSetting(
                    R.string.layout_floating_height_title,
                    subtitle = stringResource(R.string.layout_floating_height_subtitle),
                    value = settings.floatingHeightScale,
                    range = 0.6f..1.6f,
                    display = { percentFormat.format((it * 100).toInt()) },
                    info = stringResource(R.string.layout_floating_height_info),
                    default = SettingsDefaults.floatingHeightScale,
                ) { scope.launch { repository.setFloatingHeightScale(it) } }
            }
            item {
                val movedFloating = settings.floatingWidthDp != SettingsDefaults.floatingWidthDp ||
                    settings.floatingHeightScale != SettingsDefaults.floatingHeightScale ||
                    settings.floatingXFraction != SettingsDefaults.floatingXFraction ||
                    settings.floatingYFraction != SettingsDefaults.floatingYFraction
                if (movedFloating) {
                    ActionRow(
                        title = R.string.layout_floating_reset_title,
                        subtitle = stringResource(R.string.layout_floating_reset_subtitle),
                        action = stringResource(CommonR.string.common_reset),
                    ) { scope.launch { repository.resetFloatingGeometry() } }
                }
            }
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
private enum class ToolbarFit(@StringRes val labelRes: Int) {
    FIXED(R.string.appearance_toolbar_fit_fixed_label),
    SPREAD(R.string.appearance_toolbar_fit_spread_label),
    SCROLL(R.string.appearance_toolbar_fit_scroll_label),
}

private val DefaultToolbarFit = when {
    SettingsDefaults.toolbarBehavior.scrollable -> ToolbarFit.SCROLL
    SettingsDefaults.toolbarBehavior.greedy -> ToolbarFit.SPREAD
    else -> ToolbarFit.FIXED
}
