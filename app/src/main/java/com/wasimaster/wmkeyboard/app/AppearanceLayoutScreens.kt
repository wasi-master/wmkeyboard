package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.settings.DefaultToolbarKeys
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.layout.SymbolsPage
import com.wasimaster.wmkeyboard.core.layout.BottomRowPreset
import com.wasimaster.wmkeyboard.core.layout.HoldActionsKey
import com.wasimaster.wmkeyboard.core.layout.isShippedLayoutId
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.layout.resolveLayoutLanguage
import com.wasimaster.wmkeyboard.core.layout.resolveLayoutName
import com.wasimaster.wmkeyboard.core.settings.HintCorner
import com.wasimaster.wmkeyboard.core.settings.KeyLabelCase
import com.wasimaster.wmkeyboard.core.settings.HintOffsetRange
import com.wasimaster.wmkeyboard.core.settings.LabelOffsetRange
import com.wasimaster.wmkeyboard.core.settings.ArrowKey
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import com.wasimaster.wmkeyboard.core.settings.KeyGapAxisScaleRange
import com.wasimaster.wmkeyboard.core.settings.SidePadScaleRange
import com.wasimaster.wmkeyboard.core.settings.FieldButtonRange
import com.wasimaster.wmkeyboard.core.settings.ToolHeightRange
import com.wasimaster.wmkeyboard.core.settings.ToolWidthRange
import com.wasimaster.wmkeyboard.core.settings.ToolbarHeightRange
import com.wasimaster.wmkeyboard.core.settings.KeepToolsMinutesRange
import com.wasimaster.wmkeyboard.core.settings.StatusIconSettings
import com.wasimaster.wmkeyboard.core.settings.ToolbarPaddingRange
import com.wasimaster.wmkeyboard.core.settings.stripToolboxSwipes
import com.wasimaster.wmkeyboard.core.addons.AddonType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.core.addons.AddonStore
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.ui.FitText
import com.wasimaster.wmkeyboard.core.ui.LongWordFit
import com.wasimaster.wmkeyboard.core.ui.TextFitting
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import com.wasimaster.wmkeyboard.ime.ui.arrowRowKey
import com.wasimaster.wmkeyboard.ime.ui.gestureBarAtBottom
import com.wasimaster.wmkeyboard.ime.ui.keyboardCanHideSystemKeyboardBar
import com.wasimaster.wmkeyboard.core.settings.autoBottomPaddingDp
import com.wasimaster.wmkeyboard.core.settings.KeyboardAlignment
import com.wasimaster.wmkeyboard.core.settings.FloatingHandlePosition
import com.wasimaster.wmkeyboard.core.settings.floatingFor
import com.wasimaster.wmkeyboard.core.settings.oneHandedLayoutChoices
import com.wasimaster.wmkeyboard.core.script.NumeralCommitScope
import com.wasimaster.wmkeyboard.core.settings.DefaultToolbarTools
import com.wasimaster.wmkeyboard.core.settings.ToolKey
import com.wasimaster.wmkeyboard.core.settings.ToolHoldAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.key
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
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
import com.wasimaster.wmkeyboard.core.settings.ActionOnlyTools
import com.wasimaster.wmkeyboard.core.settings.ToolboxLayout
import com.wasimaster.wmkeyboard.core.settings.ToolboxSort
import com.wasimaster.wmkeyboard.core.settings.ToolboxPageSizeRange
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
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
                    scope.launch { repository.setToolbarTools(DefaultToolbarKeys) }
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
@Suppress("UnusedParameter")
internal fun AppearanceSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onOpenThemes: () -> Unit,
    onOpenFonts: () -> Unit,
    onOpenIcons: () -> Unit,
    onNavigate: (String) -> Unit,
) {
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

    SettingsGroup {
        // The key rows have a page of their own (#1318), beside the toolbar's
        // and the toolbox's: nine sliders laid out here pushed those two doors
        // to the bottom of a long scroll.
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.appearance_keys_section_title,
                stringResource(R.string.appearance_keys_page_subtitle),
                route = "appearance/keys",
            ) {
                onNavigate("appearance/keys")
            }
        }
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

/** Appearance › Keys: the corners of the keys, and where and how big their labels are. */
@Composable
internal fun AppearanceKeysSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
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
            // Gap list item 31: the case the letters are drawn in, whatever
            // shift does to what they type.
            ChoiceSetting(
                R.string.appearance_key_label_case_title,
                subtitle = stringResource(R.string.appearance_key_label_case_subtitle),
                info = stringResource(R.string.appearance_key_label_case_info),
                options = listOf(
                    KeyLabelCase.FOLLOW_SHIFT to stringResource(R.string.appearance_key_label_case_follow),
                    KeyLabelCase.LOWER to stringResource(R.string.appearance_key_label_case_lower),
                    KeyLabelCase.UPPER to stringResource(R.string.appearance_key_label_case_upper),
                ),
                selected = settings.watch { it.layoutBehavior.labelCase },
                default = SettingsDefaults.layoutBehavior.labelCase,
            ) { scope.launch { repository.setKeyLabelCase(it) } }
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
            // Gap list item 21: the corner the hint sits in. Six choices, so
            // the row falls back to a sheet where segments would not fit.
            ChoiceSetting(
                R.string.appearance_key_hint_corner_title,
                subtitle = stringResource(R.string.appearance_key_hint_corner_subtitle),
                options = listOf(
                    HintCorner.TOP_START to stringResource(R.string.appearance_key_hint_corner_top_start),
                    HintCorner.TOP_CENTER to stringResource(R.string.appearance_key_hint_corner_top_center),
                    HintCorner.TOP_END to stringResource(R.string.appearance_key_hint_corner_top_end),
                    HintCorner.BOTTOM_START to stringResource(R.string.appearance_key_hint_corner_bottom_start),
                    HintCorner.BOTTOM_CENTER to stringResource(R.string.appearance_key_hint_corner_bottom_center),
                    HintCorner.BOTTOM_END to stringResource(R.string.appearance_key_hint_corner_bottom_end),
                ),
                selected = settings.watch { it.layoutBehavior.hintCorner },
                default = SettingsDefaults.layoutBehavior.hintCorner,
                info = stringResource(R.string.appearance_key_hint_corner_info),
            ) { scope.launch { repository.setHintCorner(it) } }
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
                info = stringResource(R.string.appearance_key_hint_offset_x_info),
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
                info = stringResource(R.string.appearance_key_label_offset_x_info),
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
                info = stringResource(R.string.appearance_key_label_offset_y_info),
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
    val stripSwipes = settings.watch { it.stripToolboxSwipes() }
    val labelsOn = settings.watch { it.toolbarLabels }
    val keepTools = settings.watch { it.toolbarBehavior.keepToolsAcrossApps }
    val alarmIconOn = settings.watch { it.toolbarBehavior.statusIcons.alarm }
    val toolCircleOn = settings.watch { it.toolCircleRadiusDp > 0 }
    val stripShown = settings.watch { it.toolbarBehavior.showStrip }
    val overTheme = settings.watch { it.toolbarBehavior.sizeOverTheme }
    val widthPinned = themePin(settings) { it.toolWidthDp }
    val longWords = settings.watch { it.suggestionStrip.longWords }
    val narrowest = settings.watch { it.suggestionStrip.narrowestLetters }
    val smallest = settings.watch { it.suggestionStrip.smallestText }
    // While a slider is held, the preview follows the thumb; a new saved value clears it.
    var narrowestDraft by remember(narrowest) { mutableStateOf<Float?>(null) }
    var smallestDraft by remember(smallest) { mutableStateOf<Float?>(null) }
    val toolbarMoved = settings.watch { s ->
        val d = SettingsDefaults
        // Without the second toolbar (#1217), which the reset leaves alone.
        s.toolbarBehavior.copy(
            secondRow = d.toolbarBehavior.secondRow,
            secondRowTools = d.toolbarBehavior.secondRowTools,
        ) != d.toolbarBehavior ||
            s.toolbarHeightDp != d.toolbarHeightDp ||
            s.toolbarLabels != d.toolbarLabels ||
            s.toolbarLabelSize != d.toolbarLabelSize ||
            s.suggestionStrip.textScale != d.suggestionStrip.textScale ||
            s.suggestionStrip.longWords != d.suggestionStrip.longWords ||
            s.suggestionStrip.narrowestLetters != d.suggestionStrip.narrowestLetters ||
            s.suggestionStrip.smallestText != d.suggestionStrip.smallestText ||
            s.suggestionStrip.chipPadding != d.suggestionStrip.chipPadding ||
            s.suggestionStrip.primaryColor != d.suggestionStrip.primaryColor ||
            s.suggestionStrip.newWordColor != d.suggestionStrip.newWordColor ||
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
            offerNone = true,
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
            // The user's own buttons (#1213, #1236), which sit on this bar.
            item(level = SettingLevel.STANDARD) {
                val customCount = settings.watch { it.toolbox.customButtons.size }
                NavRow(
                    title = R.string.custombuttons_title,
                    subtitle = if (customCount == 0) {
                        stringResource(R.string.custombuttons_nav_subtitle)
                    } else {
                        pluralStringResource(R.plurals.custombuttons_count, customCount, customCount)
                    },
                    route = CustomButtons.ROUTE,
                ) { onNavigate(CustomButtons.ROUTE) }
            }
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
                        ToolbarPlacement.ON_SWIPE to
                            stringResource(R.string.appearance_toolbar_placement_swipe_label),
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
            // The other way round from the strip placement (#1272): the tools
            // stay, and the words come and go.
            if (placement == ToolbarPlacement.ALWAYS_ROW && stripShown) item {
                ToggleSetting(
                    R.string.appearance_toolbar_strip_only_words_title,
                    stringResource(R.string.appearance_toolbar_strip_only_words_subtitle),
                    settings.watch { it.toolbarBehavior.stripOnlyWithWords },
                    info = stringResource(R.string.appearance_toolbar_strip_only_words_info),
                    default = SettingsDefaults.toolbarBehavior.stripOnlyWithWords,
                ) { scope.launch { repository.setToolbarStripOnlyWithWords(it) } }
            }
            // The toolbox button before the pinned tools (#970) can go once the
            // strip has a toolbox button of its own. The strip's arrow can go
            // any time: a swipe up anywhere on the strip opens the tools.
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.appearance_toolbar_bar_toolbox_title,
                    stringResource(R.string.appearance_toolbar_bar_toolbox_subtitle),
                    settings.watch { it.toolbarBehavior.barToolbox || !it.stripToolboxSwipes() },
                    enabled = stripSwipes,
                    disabledReason = DisabledReason(stringResource(R.string.appearance_toolbar_needs_strip_toolbox)),
                    default = SettingsDefaults.toolbarBehavior.barToolbox,
                    info = stringResource(R.string.appearance_toolbar_bar_toolbox_info),
                ) { scope.launch { repository.setToolbarBarToolbox(it) } }
            }
            if (placement == ToolbarPlacement.STRIP || placement == ToolbarPlacement.ON_DEMAND_ROW) item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.appearance_toolbar_tools_arrow_title,
                    stringResource(R.string.appearance_toolbar_tools_arrow_subtitle),
                    settings.watch { it.toolbarBehavior.toolsArrow },
                    default = SettingsDefaults.toolbarBehavior.toolsArrow,
                    info = stringResource(R.string.appearance_toolbar_tools_arrow_info),
                ) { scope.launch { repository.setToolbarToolsArrow(it) } }
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
                range = ToolbarPaddingRange.first.toFloat()..ToolbarPaddingRange.last.toFloat(),
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
                range = ToolbarPaddingRange.first.toFloat()..ToolbarPaddingRange.last.toFloat(),
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
        // The status icons at the toolbar's far end (#1453).
        item {
            ToggleSetting(
                R.string.appearance_status_alarm_title,
                stringResource(R.string.appearance_status_alarm_subtitle),
                settings.watch { it.toolbarBehavior.statusIcons.alarm },
                info = stringResource(R.string.appearance_status_alarm_info),
                default = SettingsDefaults.toolbarBehavior.statusIcons.alarm,
            ) { on -> scope.launch { repository.updateStatusIcons { it.copy(alarm = on) } } }
        }
        item(visible = alarmIconOn, level = SettingLevel.ADVANCED) {
            val minutesFormat = stringResource(R.string.values_minutes)
            val range = StatusIconSettings.AlarmMinutesRange
            SliderSetting(
                R.string.appearance_status_alarm_minutes_title,
                subtitle = stringResource(R.string.appearance_status_alarm_minutes_subtitle),
                value = settings.watch { it.toolbarBehavior.statusIcons.alarmMinutes }.toFloat(),
                range = range.first.toFloat()..range.last.toFloat(),
                display = { minutesFormat.format(it.roundToInt()) },
                default = SettingsDefaults.toolbarBehavior.statusIcons.alarmMinutes.toFloat(),
                info = stringResource(R.string.appearance_status_alarm_minutes_info),
            ) { v -> scope.launch { repository.updateStatusIcons { it.copy(alarmMinutes = v.roundToInt()) } } }
        }
        item {
            ToggleSetting(
                R.string.appearance_status_special_days_title,
                stringResource(R.string.appearance_status_special_days_subtitle),
                settings.watch { it.toolbarBehavior.statusIcons.specialDays },
                info = stringResource(R.string.appearance_status_special_days_info),
                default = SettingsDefaults.toolbarBehavior.statusIcons.specialDays,
            ) { on -> scope.launch { repository.updateStatusIcons { it.copy(specialDays = on) } } }
        }
        item {
            ToggleSetting(
                R.string.appearance_status_clock_title,
                stringResource(R.string.appearance_status_clock_subtitle),
                settings.watch { it.toolbarBehavior.statusIcons.clock },
                info = stringResource(R.string.appearance_status_clock_info),
                default = SettingsDefaults.toolbarBehavior.statusIcons.clock,
            ) { on -> scope.launch { repository.updateStatusIcons { it.copy(clock = on) } } }
        }
        item {
            ToggleSetting(
                R.string.appearance_status_todo_title,
                stringResource(R.string.appearance_status_todo_subtitle),
                settings.watch { it.toolbarBehavior.statusIcons.todo },
                info = stringResource(R.string.appearance_status_todo_info),
                default = SettingsDefaults.toolbarBehavior.statusIcons.todo,
            ) { on -> scope.launch { repository.updateStatusIcons { it.copy(todo = on) } } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.appearance_status_chips_title,
                stringResource(R.string.appearance_status_chips_subtitle),
                settings.watch { it.toolbarBehavior.statusIcons.chips },
                info = stringResource(R.string.appearance_status_chips_info),
                default = SettingsDefaults.toolbarBehavior.statusIcons.chips,
            ) { on -> scope.launch { repository.updateStatusIcons { it.copy(chips = on) } } }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_keep_tools_title,
                stringResource(R.string.appearance_toolbar_keep_tools_subtitle),
                keepTools,
                info = stringResource(R.string.appearance_toolbar_keep_tools_info),
                default = SettingsDefaults.toolbarBehavior.keepToolsAcrossApps,
            ) { scope.launch { repository.setKeepToolsAcrossApps(it) } }
        }
        item(visible = keepTools) {
            val minutesFormat = stringResource(R.string.values_minutes)
            SliderSetting(
                R.string.appearance_toolbar_keep_tools_minutes_title,
                subtitle = stringResource(R.string.appearance_toolbar_keep_tools_minutes_subtitle),
                value = settings.watch { it.toolbarBehavior.keepToolsMinutes }.toFloat(),
                range = KeepToolsMinutesRange.first.toFloat()..KeepToolsMinutesRange.last.toFloat(),
                display = { minutesFormat.format(it.roundToInt()) },
                default = SettingsDefaults.toolbarBehavior.keepToolsMinutes.toFloat(),
                info = stringResource(R.string.appearance_toolbar_keep_tools_minutes_info),
            ) { scope.launch { repository.setKeepToolsMinutes(it.roundToInt()) } }
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
                info = stringResource(R.string.appearance_toolbar_label_size_info),
            ) { scope.launch { repository.setToolbarLabelSize(it.roundToInt()) } }
        }
        item(visible = toolbarOn && labelsOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.appearance_toolbar_labels_fixed_title,
                stringResource(R.string.appearance_toolbar_labels_fixed_subtitle),
                settings.watch { it.toolbarBehavior.labelsFixedWidth },
                default = SettingsDefaults.toolbarBehavior.labelsFixedWidth,
                info = stringResource(R.string.appearance_toolbar_labels_fixed_info),
            ) { scope.launch { repository.setToolbarLabelsFixedWidth(it) } }
        }
        item(visible = toolbarOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.appearance_toolbar_pinned_colors_title,
                stringResource(R.string.appearance_toolbar_pinned_colors_subtitle),
                settings.watch { it.toolbarBehavior.pinnedToolColors },
                default = SettingsDefaults.toolbarBehavior.pinnedToolColors,
                info = stringResource(R.string.appearance_toolbar_pinned_colors_info),
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
            ChoiceSetting(
                R.string.appearance_long_words_title,
                subtitle = stringResource(R.string.appearance_long_words_subtitle),
                info = stringResource(R.string.appearance_long_words_info),
                options = listOf(
                    LongWordFit.NARROW_THEN_SHRINK to stringResource(R.string.appearance_long_words_narrow_then_shrink),
                    LongWordFit.SHRINK_THEN_NARROW to stringResource(R.string.appearance_long_words_shrink_then_narrow),
                    LongWordFit.NARROW_ONLY to stringResource(R.string.appearance_long_words_narrow_only),
                    LongWordFit.SHRINK_ONLY to stringResource(R.string.appearance_long_words_shrink_only),
                    LongWordFit.CUT_OFF to stringResource(R.string.appearance_long_words_cut_off),
                ),
                selected = longWords,
                default = SettingsDefaults.suggestionStrip.longWords,
            ) { scope.launch { repository.setSuggestionLongWords(it) } }
        }
        item(visible = longWords.narrows, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_narrowest_letters_title,
                subtitle = stringResource(R.string.appearance_narrowest_letters_subtitle),
                value = narrowest,
                range = 0.5f..1f,
                display = { percentFormat.format((it * 100).roundToInt()) },
                info = stringResource(R.string.appearance_narrowest_letters_info),
                default = SettingsDefaults.suggestionStrip.narrowestLetters,
                preview = { narrowestDraft = it },
            ) { scope.launch { repository.setSuggestionNarrowestLetters(it) } }
        }
        item(visible = longWords.shrinks, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_smallest_text_title,
                subtitle = stringResource(R.string.appearance_smallest_text_subtitle),
                value = smallest,
                range = 0.5f..1f,
                display = { percentFormat.format((it * 100).roundToInt()) },
                info = stringResource(R.string.appearance_smallest_text_info),
                default = SettingsDefaults.suggestionStrip.smallestText,
                preview = { smallestDraft = it },
            ) { scope.launch { repository.setSuggestionSmallestText(it) } }
        }
        // What the three rows above do, on words that need it, redrawn as they
        // change. Part of the page, so it scrolls away with it.
        item(level = SettingLevel.ADVANCED) {
            LongWordsPreview(
                fitting = TextFitting(longWords, narrowestDraft ?: narrowest, smallestDraft ?: smallest),
                textScale = settings.watch { it.suggestionStrip.textScale },
            )
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
        // Its twin for the other end of the strip's promises (#1098): not
        // the word a space will put in, but the word no list knows yet.
        item(level = SettingLevel.ADVANCED) {
            ColorSetting(
                R.string.appearance_suggestion_new_word_color_title,
                subtitle = stringResource(R.string.appearance_suggestion_new_word_color_subtitle),
                color = settings.watch { it.suggestionStrip.newWordColor },
                fallback = MaterialTheme.colorScheme.onSurface.argbLong(),
                info = stringResource(R.string.appearance_suggestion_new_word_color_info),
            ) { scope.launch { repository.setSuggestionNewWordColor(it) } }
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
        // The theme's own tool width shadows the width row until this switch
        // says the user's sizes win (#1264): the theme's Layout group is far
        // from here, and a greyed-out slider was all anyone found.
        if (toolbarOn && (widthPinned != null || overTheme)) item {
            ToggleSetting(
                R.string.appearance_tool_size_over_theme_title,
                stringResource(R.string.appearance_tool_size_over_theme_subtitle),
                overTheme,
                info = stringResource(R.string.appearance_tool_size_over_theme_info),
                default = SettingsDefaults.toolbarBehavior.sizeOverTheme,
            ) { scope.launch { repository.setToolbarSizeOverTheme(it) } }
        }
        if (toolbarOn) item {
            val pin = widthPinned.takeUnless { overTheme }
            SliderSetting(
                R.string.appearance_tool_width_title,
                subtitle = pin?.text ?: stringResource(R.string.appearance_tool_width_subtitle),
                value = settings.watch { it.toolbarBehavior.toolWidthDp }.toFloat(),
                range = ToolWidthRange.first.toFloat()..ToolWidthRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_tool_width_info),
                enabled = pin == null,
                disabledReason = pin,
                default = SettingsDefaults.toolbarBehavior.toolWidthDp.toFloat(),
            ) { scope.launch { repository.setToolbarToolWidthDp(it.roundToInt()) } }
        }
        // Taller buttons (#1264). The bar's height caps them, so the row says
        // so while the setting asks for more than the bar holds.
        if (toolbarOn) item {
            val barHeight = settings.watch { it.toolbarHeightDp }
            val height = settings.watch { it.toolbarBehavior.toolHeightDp }
            SliderSetting(
                R.string.appearance_tool_height_title,
                subtitle = if (height > barHeight) {
                    stringResource(R.string.appearance_tool_height_capped, barHeight)
                } else {
                    stringResource(R.string.appearance_tool_height_subtitle)
                },
                value = height.toFloat(),
                range = ToolHeightRange.first.toFloat()..ToolHeightRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_tool_height_info),
                default = SettingsDefaults.toolbarBehavior.toolHeightDp.toFloat(),
            ) { scope.launch { repository.setToolbarToolHeightDp(it.roundToInt()) } }
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
        // The buttons on the keyboard's own fields (#1153): not the toolbar's,
        // but sized alongside them, and there with the toolbar off too.
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.appearance_field_button_size_title,
                subtitle = stringResource(R.string.appearance_field_button_size_subtitle),
                value = settings.watch { it.toolbarBehavior.fieldButtonDp }.toFloat(),
                range = FieldButtonRange.first.toFloat()..FieldButtonRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_field_button_size_info),
                default = SettingsDefaults.toolbarBehavior.fieldButtonDp.toFloat(),
            ) { scope.launch { repository.setFieldButtonDp(it.roundToInt()) } }
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
    StripButtonsSettings(repository, settings)
    // A row of tools of its own (#1217), picked apart from the pinned ones.
    // Not gated on the toolbar: the row stays with the toolbar switched off.
    val secondOn = settings.watch { it.toolbarBehavior.secondRow }
    SettingsGroup(stringResource(R.string.appearance_second_toolbar_section_title)) {
        item {
            ToggleSetting(
                R.string.appearance_second_toolbar_title,
                stringResource(R.string.appearance_second_toolbar_subtitle),
                secondOn,
                info = stringResource(R.string.appearance_second_toolbar_info),
                default = SettingsDefaults.toolbarBehavior.secondRow,
            ) { scope.launch { repository.setSecondToolbar(it) } }
        }
        // Where it sits is its place in the row order, which Rows & bars
        // drags like every other row: anywhere among the bars, above or
        // below the keys, not just at either end of the toolbar.
        item(visible = secondOn) {
            NavRow(
                title = R.string.appearance_second_toolbar_place_title,
                subtitle = stringResource(R.string.appearance_second_toolbar_place_subtitle),
                route = "rows",
            ) { onNavigate("rows") }
        }
        // Its tools: dragged on, along and off on the keyboard itself, as the
        // toolbar's are, or here: add from the tool list, drag to reorder, the
        // bin to take one off.
        item(visible = secondOn) {
            val tools = settings.watch { it.toolbarBehavior.secondRowTools }
            var picking by remember { mutableStateOf(false) }
            NavRow(
                title = R.string.appearance_second_toolbar_tools_title,
                subtitle = stringResource(R.string.appearance_second_toolbar_tools_subtitle),
                value = stringResource(R.string.langemoji_strip_tools_add_value),
            ) { picking = true }
            if (picking) {
                ToolPickerDialog(
                    title = stringResource(R.string.appearance_second_toolbar_tools_title),
                    current = null,
                    // Not the action-only tools: they are never drawn as a
                    // button, so one picked here would leave a gap in the row.
                    options = ToolbarTool.entries.filter {
                        isSupportedTool(it) && it !in ActionOnlyTools && it.key !in tools
                    },
                    onDismiss = { picking = false },
                    onPick = { picked ->
                        picking = false
                        if (picked != null) scope.launch { repository.setSecondToolbarTools(tools + picked.key) }
                    },
                )
            }
        }
        item(visible = secondOn) {
            val tools = settings.watch { it.toolbarBehavior.secondRowTools }
            // Every tool's name, not only the chosen ones': the list adopts a
            // change a frame after it lands, so a tool just taken off is
            // still drawn once more by name.
            val names = ToolbarTool.entries.associateWith { stringResource(toolTitle(it)) }
            val pluginNames = rememberPluginToolNames()
            ReorderableColumn(
                items = tools,
                label = { tool ->
                    when (tool) {
                        is ToolKey.Builtin -> names[tool.tool] ?: tool.token
                        is ToolKey.Plugin -> pluginNames[tool.pluginId] ?: tool.pluginId
                        is ToolKey.Custom -> tool.token
                        // Never on this row: it holds tools only.
                        is ToolKey.Folder -> tool.token
                    }
                },
                onReorder = { order -> scope.launch { repository.setSecondToolbarTools(order) } },
                onDelete = { tool -> scope.launch { repository.setSecondToolbarTools(tools - tool) } },
                keepLast = false,
            )
        }
    }
}

/** Where the strip's toolbox button sits, as the one choice the screen offers. */
private enum class StripToolboxSide { OFF, LEFT, RIGHT }

/**
 * The tools kept on the suggestion strip while the words show (#462). They
 * lived on the Emoji screen while the emoji button was the only one, and
 * stayed there after any tool could join it; they are toolbar buttons, so
 * they are here. Each picks its own end of the strip. Not gated on the
 * toolbar: the strip draws them with the toolbar switched off.
 */
@Composable
private fun StripButtonsSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val dpFormat = stringResource(R.string.typing_value_dp)
    val buttonsOn = settings.watch { it.emojiToolbar }
    val stripOwnSize = settings.watch { it.toolbarBehavior.stripOwnSize }
    val hasCustoms = settings.watch { it.toolbarBehavior.stripCustomButtons.isNotEmpty() }
    SettingsGroup(stringResource(R.string.appearance_strip_buttons_section_title)) {
        item {
            ToggleSetting(
                R.string.appearance_strip_buttons_show_title,
                stringResource(R.string.appearance_strip_buttons_show_subtitle),
                buttonsOn,
                info = stringResource(R.string.appearance_strip_buttons_show_info),
                default = SettingsDefaults.emojiToolbar,
            ) { scope.launch { repository.setEmojiToolbar(it) } }
        }
        item(visible = buttonsOn) {
            val shortcuts = settings.watch { it.toolbarBehavior.stripShortcuts }
            val customs = settings.watch { it.toolbarBehavior.stripCustomButtons }
            var picking by remember { mutableStateOf(false) }
            NavRow(
                title = R.string.appearance_strip_buttons_add_title,
                subtitle = stringResource(R.string.appearance_strip_buttons_add_subtitle),
                value = stringResource(R.string.langemoji_strip_tools_add_value),
            ) { picking = true }
            if (picking) {
                ToolPickerDialog(
                    title = stringResource(R.string.appearance_strip_buttons_add_title),
                    current = null,
                    options = ToolbarTool.entries.filter { isSupportedTool(it) && it !in shortcuts },
                    onDismiss = { picking = false },
                    onPick = { picked ->
                        picking = false
                        if (picked != null) scope.launch { repository.setStripShortcuts(shortcuts + picked) }
                    },
                    // The user's custom buttons beside the words too (#1300).
                    onPickCustom = { id ->
                        picking = false
                        if (id !in customs) scope.launch { repository.setStripCustomButtons(customs + id) }
                    },
                )
            }
        }
        // The chosen buttons: drag to change their order, the bin to take one
        // off, and the side button to send one to the other end of the strip.
        item(visible = buttonsOn) {
            val shortcuts = settings.watch { it.toolbarBehavior.stripShortcuts }
            val endTools = settings.watch { it.toolbarBehavior.stripEndTools }
            // Every tool's name, not only the chosen ones': the list adopts a
            // change a frame after it lands, so a tool just taken off is still
            // drawn once more by name.
            val names = ToolbarTool.entries.associateWith { stringResource(toolTitle(it)) }
            val left = stringResource(R.string.appearance_strip_buttons_side_left)
            val right = stringResource(R.string.appearance_strip_buttons_side_right)
            val sideDesc = stringResource(R.string.appearance_strip_buttons_side_desc)
            ReorderableColumn(
                items = shortcuts,
                label = { names[it] ?: it.name },
                onReorder = { order -> scope.launch { repository.setStripShortcuts(order) } },
                onDelete = { tool -> scope.launch { repository.setStripShortcuts(shortcuts - tool) } },
                keepLast = false,
            ) { tool ->
                val name = names[tool] ?: tool.name
                val atEnd = tool in endTools
                val sideName = if (atEnd) right else left
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { scope.launch { repository.setStripToolAtEnd(tool, !atEnd) } },
                    modifier = Modifier.semantics { contentDescription = sideDesc.format(name, sideName) },
                ) { Text(sideName) }
            }
        }
        // The custom buttons on the strip (#1300): a side each and a bin, like the tools above.
        item(visible = buttonsOn && hasCustoms) {
            val customs = settings.watch { it.toolbarBehavior.stripCustomButtons }
            val endCustoms = settings.watch { it.toolbarBehavior.stripEndCustomButtons }
            val left = stringResource(R.string.appearance_strip_buttons_side_left)
            val right = stringResource(R.string.appearance_strip_buttons_side_right)
            val sideDesc = stringResource(R.string.appearance_strip_buttons_side_desc)
            val removeDesc = stringResource(CommonR.string.common_delete)
            Column {
                for (id in customs) {
                    val name = customButtonName(id)
                    val atEnd = id in endCustoms
                    val sideName = if (atEnd) right else left
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { scope.launch { repository.setStripCustomAtEnd(id, !atEnd) } },
                            modifier = Modifier.semantics { contentDescription = sideDesc.format(name, sideName) },
                        ) { Text(sideName) }
                        IconButton(onClick = { scope.launch { repository.setStripCustomButtons(customs - id) } }) {
                            Icon(Icons.Outlined.Delete, contentDescription = removeDesc)
                        }
                    }
                }
            }
        }
        item(visible = buttonsOn, level = SettingLevel.STANDARD) {
            val side = settings.watch {
                when {
                    !it.toolbarBehavior.stripToolbox -> StripToolboxSide.OFF
                    it.toolbarBehavior.stripToolboxAtEnd -> StripToolboxSide.RIGHT
                    else -> StripToolboxSide.LEFT
                }
            }
            ChoiceSetting(
                title = R.string.langemoji_strip_toolbox_title,
                subtitle = stringResource(R.string.appearance_strip_buttons_toolbox_subtitle),
                options = listOf(
                    StripToolboxSide.OFF to stringResource(CommonR.string.common_off),
                    StripToolboxSide.LEFT to stringResource(R.string.appearance_strip_buttons_side_left),
                    StripToolboxSide.RIGHT to stringResource(R.string.appearance_strip_buttons_side_right),
                ),
                selected = side,
                default = StripToolboxSide.OFF,
                info = stringResource(R.string.langemoji_strip_toolbox_info),
            ) { chosen ->
                scope.launch {
                    repository.setStripToolbox(chosen != StripToolboxSide.OFF)
                    if (chosen != StripToolboxSide.OFF) repository.setStripToolboxAtEnd(chosen == StripToolboxSide.RIGHT)
                }
            }
        }
        // Their size, apart from the toolbar's tools (which the sliders above
        // size). Off, a tool on both looks the same in either spot.
        item(visible = buttonsOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.langemoji_strip_own_size_title,
                stringResource(R.string.langemoji_strip_own_size_subtitle),
                stripOwnSize,
                default = SettingsDefaults.toolbarBehavior.stripOwnSize,
                info = stringResource(R.string.langemoji_strip_own_size_info),
            ) { scope.launch { repository.setStripOwnSize(it) } }
        }
        item(visible = buttonsOn && stripOwnSize, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.langemoji_strip_tool_width_title,
                subtitle = stringResource(R.string.langemoji_strip_tool_width_subtitle),
                value = settings.watch { it.toolbarBehavior.stripToolWidthDp }.toFloat(),
                // Down to 20 dp, a button about as wide as its icon (#970).
                range = 20f..64f,
                display = { dpFormat.format(it.roundToInt()) },
                default = SettingsDefaults.toolbarBehavior.stripToolWidthDp.toFloat(),
                info = stringResource(R.string.langemoji_strip_tool_width_info),
            ) { scope.launch { repository.setStripToolWidthDp(it.roundToInt()) } }
        }
        item(visible = buttonsOn && stripOwnSize, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.langemoji_strip_icon_size_title,
                subtitle = stringResource(R.string.langemoji_strip_icon_size_subtitle),
                value = settings.watch { it.toolbarBehavior.stripIconSizeDp }.toFloat(),
                range = 14f..30f,
                display = { dpFormat.format(it.roundToInt()) },
                default = SettingsDefaults.toolbarBehavior.stripIconSizeDp.toFloat(),
                info = stringResource(R.string.langemoji_strip_icon_size_info),
            ) { scope.launch { repository.setStripIconSizeDp(it.roundToInt()) } }
        }
        // The gap around them (#1478), with or without a size of their own.
        item(visible = buttonsOn, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.langemoji_strip_tool_gap_title,
                subtitle = stringResource(R.string.langemoji_strip_tool_gap_subtitle),
                value = settings.watch { it.toolbarBehavior.stripToolGapDp }.toFloat(),
                range = 0f..12f,
                display = { dpFormat.format(it.roundToInt()) },
                default = SettingsDefaults.toolbarBehavior.stripToolGapDp.toFloat(),
                info = stringResource(R.string.langemoji_strip_tool_gap_info),
            ) { scope.launch { repository.setStripToolGapDp(it.roundToInt()) } }
        }
    }
}

@Composable
internal fun AppearanceToolboxSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // The grid's tools as a list the screen high (#1475): drag one to move
    // it, switch one off to leave it out of the grid but on everywhere else.
    SettingsGroup {
        item {
            val pluginNames = rememberPluginToolNames()
            val counts = settings.watch { s ->
                val all = arrangeableToolboxTools(s, pluginNames.keys)
                all.count { it !in s.toolbox.hiddenTools } to all.size
            }
            NavRow(
                R.string.toolbox_arrange_title,
                stringResource(R.string.toolbox_arrange_subtitle),
                value = stringResource(R.string.toolbox_arrange_count, counts.first, counts.second),
                route = TOOLBOX_ARRANGE_ROUTE,
            ) { onNavigate(TOOLBOX_ARRANGE_ROUTE) }
        }
    }
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
                    steps = wholeSteps(3f..6f),
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
                    steps = wholeSteps(1f..3f),
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
                info = stringResource(R.string.appearance_toolbox_cell_spacing_info),
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
        item(level = SettingLevel.STANDARD) { ToolboxHoldRow(repository, settings) }
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
/**
 * What a press and hold on the toolbox button does (#1516): the same picker a
 * tool's "Press and hold on the toolbar" opens, with this page as the default
 * and "Does nothing" for a hold that should only buzz.
 */
@Composable
private fun ToolboxHoldRow(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    val bound = settings.watch { it.toolbox.buttonHold }
    NavRow(
        title = R.string.appearance_toolbox_hold_title,
        subtitle = stringResource(R.string.appearance_toolbox_hold_subtitle),
        value = when (bound) {
            null, ToolHoldAction.OpenSettings, ToolHoldAction.Floating ->
                stringResource(R.string.appearance_toolbox_hold_settings_value)
            ToolHoldAction.None -> stringResource(R.string.tooldetail_hold_nothing_value)
            is ToolHoldAction.Run -> stringResource(toolTitle(bound.tool))
            is ToolHoldAction.RunCustom -> customButtonName(bound.id)
        },
    ) { editing = true }
    if (!editing) return
    ToolPickerDialog(
        title = stringResource(R.string.appearance_toolbox_hold_title),
        current = (bound as? ToolHoldAction.Run)?.tool,
        options = ToolbarTool.entries.filter(::isSupportedTool),
        noneTitle = stringResource(R.string.tooldetail_hold_default_title),
        noneSubtitle = stringResource(R.string.appearance_toolbox_hold_settings_value),
        nothingSubtitle = stringResource(R.string.tooldetail_hold_nothing_subtitle),
        nothingSelected = bound == ToolHoldAction.None,
        onPickNothing = {
            editing = false
            scope.launch { repository.setToolboxButtonHold(ToolHoldAction.None) }
        },
        onDismiss = { editing = false },
        onPick = { picked ->
            editing = false
            scope.launch { repository.setToolboxButtonHold(picked?.let(ToolHoldAction::Run)) }
        },
        customCurrent = (bound as? ToolHoldAction.RunCustom)?.id,
        onPickCustom = { id ->
            editing = false
            scope.launch { repository.setToolboxButtonHold(ToolHoldAction.RunCustom(id)) }
        },
        memoryKey = "toolbox-hold",
    )
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
    // Layouts that came from an add-on repository: switched on here, but not
    // edited, since the next update of the add-on would throw the edit away.
    // Their rows stay a plain switch; every other row opens its editor.
    val context = LocalContext.current
    val addonStore = remember { AddonStore.get(context) }
    val addonRevision by addonStore.revision.collectAsStateWithLifecycle()
    val addonLayoutIds = remember(addonRevision) {
        addonStore.installed().values
            .filter { it.type == AddonType.Layout }
            .mapTo(HashSet()) { it.localRef }
    }
    // Names more than one row carries (#1389): an add-on install beside the
    // user's own copy of the same layout, imported from a file before it was
    // an add-on or an edit of a layout that has since left the app (English
    // Gboard+, 0.6.7). Those rows say which is which.
    val sharedNames = remember(customs) {
        customs.groupingBy { it.name.trim().lowercase() }.eachCount().filterValues { it > 1 }.keys
    }
    SettingsGroup(stringResource(R.string.langemoji_lang_your_layouts_title)) {
        for (layout in customs) {
            item {
                // An installed layout arrives switched off and this switch is
                // what finishes the install, so its addon's Use button lands
                // here — on the layout's own row, not on the group.
                HighlightableItem(layout.id) {
                    val base = stringResource(
                        R.string.langemoji_lang_custom_layout_subtitle,
                        baseModeTitle(layout),
                    )
                    val subtitle = when {
                        layout.name.trim().lowercase() !in sharedNames -> base
                        layout.id in addonLayoutIds -> stringResource(R.string.layout_row_origin_addon, base)
                        else -> stringResource(R.string.layout_row_origin_own, base)
                    }
                    val checked = settings.watch { layout.id in it.enabledLayoutIds }
                    val default = layout.id in SettingsDefaults.enabledLayoutIds
                    val change: (Boolean) -> Unit = { enable ->
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
                    if (layout.id in addonLayoutIds) {
                        ToggleSetting(layout.name, subtitle, checked, default = default, onChange = change)
                    } else {
                        // The row opens the layout's editor and the switch
                        // stays a switch (#1318): going through Key layouts to
                        // edit a layout listed right here was a tap for nothing.
                        val route = "keymap_edit/${layout.id}"
                        ToggleNavRow(
                            title = layout.name,
                            subtitle = subtitle,
                            checked = checked,
                            route = route,
                            default = default,
                            onChange = change,
                            onOpen = { onNavigate(route) },
                        )
                    }
                }
            }
        }
    }
    // The pages and the store in a group of their own (#1318): in the same
    // card stack as the layouts, under the Your layouts heading, their icons
    // read as parts of the layout above them rather than rows of the page.
    SettingsGroup {
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
        // The emoji, clipboard and other panels are layouts too (#63), but no
        // language's: they have a page of their own beside Key layouts
        // (#1318) rather than a group at the foot of it.
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.panel_layouts_title,
                stringResource(R.string.panel_layouts_nav_subtitle),
                route = PANEL_LAYOUTS_ROUTE,
            ) {
                onNavigate(PANEL_LAYOUTS_ROUTE)
            }
        }
        // "Make one" and "get one" are the same question answered two ways.
        // One row for getting one (#1318): the community repository is one of
        // the stores the add-on screen lists, so a second row for it only
        // split the same catalogue in two.
        item(visible = !OfflineBuild.isThisBuild, waitsOnSetting = false) {
            AddonStoreRow(
                AddonType.Layout,
                onNavigate,
                subtitle = stringResource(R.string.layout_get_more_subtitle),
            )
        }
    }
    // Bringing a layout in from a file, or sending the ones in use out to one
    // (#1318): beside the layouts they add to, not a screen further in.
    LayoutImportsGroup(repository, settings)
    // The rest of the screen is doors (#1432): Number row, Arrow keys row,
    // Symbols layer and Bottom row keys were groups laid out here, a long
    // scroll of rows under headings beside the arrow rows Size and position
    // and One-handed already were. Each is now a page of its own, the way
    // #1318 split Key press and Appearance.
    val onLabel = stringResource(CommonR.string.common_on)
    val offLabel = stringResource(CommonR.string.common_off)
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
        item {
            NavRow(
                R.string.layout_number_row_title,
                stringResource(R.string.layout_number_row_page_subtitle),
                value = if (settings.watch { it.numberRow }) onLabel else offLabel,
                route = LAYOUT_NUMBER_ROW_ROUTE,
            ) {
                onNavigate(LAYOUT_NUMBER_ROW_ROUTE)
            }
        }
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.layout_arrow_row_title,
                stringResource(R.string.layout_arrow_row_page_subtitle),
                value = if (settings.watch { it.layoutBehavior.arrowRow }) onLabel else offLabel,
                route = LAYOUT_ARROW_ROW_ROUTE,
            ) {
                onNavigate(LAYOUT_ARROW_ROW_ROUTE)
            }
        }
        item {
            NavRow(
                R.string.layout_symbols_title,
                stringResource(R.string.layout_symbols_page_nav_subtitle),
                route = LAYOUT_SYMBOLS_ROUTE,
            ) {
                onNavigate(LAYOUT_SYMBOLS_ROUTE)
            }
        }
        item {
            NavRow(
                R.string.layout_bottom_row_keys_title,
                stringResource(R.string.layout_bottom_row_page_subtitle),
                value = stringResource(bottomRowPresetLabel(settings.watch { it.bottomRow.preset })),
                route = LAYOUT_BOTTOM_ROW_ROUTE,
            ) {
                onNavigate(LAYOUT_BOTTOM_ROW_ROUTE)
            }
        }
    }
}

/** Layout & size › Number row (#1432). */
internal const val LAYOUT_NUMBER_ROW_ROUTE = "layout/numberrow"

/** Layout & size › Arrow keys row (#1432). */
internal const val LAYOUT_ARROW_ROW_ROUTE = "layout/arrowrow"

/** Layout & size › Symbols layer, with Numerals (#1432). */
internal const val LAYOUT_SYMBOLS_ROUTE = "layout/symbols"

/** Layout & size › Bottom row keys (#1432). */
internal const val LAYOUT_BOTTOM_ROW_ROUTE = "layout/bottomrow"

/** Layout & size › Number row: the row of digits over the letters (#1432). */
@Composable
internal fun LayoutNumberRowSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val numberRow = settings.watch { it.numberRow }
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
            NumberRowHeightSlider(settings, repository, scope)
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
        item(visible = numberRow, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.layout_number_row_hints_title,
                stringResource(R.string.layout_number_row_hints_subtitle),
                settings.watch { it.layoutBehavior.numberRowHints },
                default = SettingsDefaults.layoutBehavior.numberRowHints,
                info = stringResource(R.string.layout_number_row_hints_info),
            ) { scope.launch { repository.setNumberRowHints(it) } }
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
        // The row's keys, once for every layout that has no row of its own.
        item(visible = numberRow, level = SettingLevel.ADVANCED) {
            val custom = settings.watch { it.layoutBehavior.customNumberRow != null }
            NavRow(
                R.string.layout_custom_number_row_title,
                subtitle = stringResource(
                    if (custom) R.string.layout_custom_row_custom_subtitle else R.string.layout_custom_row_builtin_subtitle,
                ),
                route = globalRowRoute(GlobalRow.NUMBER),
            ) { onNavigate(globalRowRoute(GlobalRow.NUMBER)) }
        }
    }
}

/** Layout & size › Arrow keys row (#1432). */
@Composable
internal fun LayoutArrowRowSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Issue #369: the caret keys as a row of their own under the spacebar.
    val arrowRow = settings.watch { it.layoutBehavior.arrowRow }
    val customArrowRow = settings.watch { it.layoutBehavior.customArrowRow != null }
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
        item(visible = arrowRow, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.layout_arrow_row_on_top_title,
                stringResource(R.string.layout_arrow_row_on_top_subtitle),
                settings.watch { it.layoutBehavior.arrowRowOnTop },
                info = stringResource(R.string.layout_arrow_row_on_top_info),
                default = SettingsDefaults.layoutBehavior.arrowRowOnTop,
            ) { scope.launch { repository.setArrowRowOnTop(it) } }
        }
        item(visible = arrowRow, level = SettingLevel.STANDARD) {
            NavRow(
                R.string.layout_custom_arrow_row_title,
                subtitle = stringResource(
                    if (customArrowRow) R.string.layout_custom_row_custom_subtitle else R.string.layout_custom_row_builtin_subtitle,
                ),
                route = globalRowRoute(GlobalRow.ARROW),
            ) { onNavigate(globalRowRoute(GlobalRow.ARROW)) }
        }
        // The order is of the four built-in arrows: a row of the user's own
        // keys has its order in the row.
        item(visible = arrowRow && !customArrowRow, level = SettingLevel.ADVANCED) {
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

}

/**
 * Layout & size › Symbols layer (#1432), with Numerals under it: which digits
 * the keyboard types is a question about the digits the symbols page offers.
 */
@Composable
internal fun LayoutSymbolsSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val symbolsReturn = settings.watch { it.layoutBehavior.symbolsReturnToLetters }
    // The group was advanced-only until the Symbols page choice joined it;
    // its other rows still are.
    SettingsGroup(stringResource(R.string.layout_symbols_title)) {
        item {
            // SwiftKey's symbols page, the digits as a keypad: asked for again
            // and again in the Telegram group. Only for layouts that borrow
            // the shipped page; a layout's own symbols page wins.
            ChoiceSetting(
                R.string.layout_symbols_page_title,
                subtitle = stringResource(R.string.layout_symbols_page_subtitle),
                info = stringResource(R.string.layout_symbols_page_info),
                options = listOf(
                    SymbolsPage.STANDARD to stringResource(R.string.layout_symbols_page_standard_label),
                    SymbolsPage.NUMBER_PAD to stringResource(R.string.layout_symbols_page_number_pad_label),
                ),
                selected = settings.watch { it.layoutBehavior.symbolsPage },
                default = SettingsDefaults.layoutBehavior.symbolsPage,
            ) { scope.launch { repository.setSymbolsPage(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_symbols_return_title,
                stringResource(R.string.layout_symbols_return_subtitle),
                symbolsReturn,
                info = stringResource(R.string.layout_symbols_return_info),
                default = SettingsDefaults.layoutBehavior.symbolsReturnToLetters,
            ) { scope.launch { repository.setSymbolsReturnToLetters(it) } }
        }
        item(visible = symbolsReturn, level = SettingLevel.ADVANCED) {
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
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_symbols_return_space_title,
                stringResource(R.string.layout_symbols_return_space_subtitle),
                settings.watch { it.layoutBehavior.symbolsReturnOnSpace },
                info = stringResource(R.string.layout_symbols_return_space_info),
                default = SettingsDefaults.layoutBehavior.symbolsReturnOnSpace,
            ) { scope.launch { repository.setSymbolsReturnOnSpace(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_back_leaves_symbols_title,
                stringResource(R.string.layout_back_leaves_symbols_subtitle),
                settings.watch { it.layoutBehavior.backLeavesSymbols },
                info = stringResource(R.string.layout_back_leaves_symbols_info),
                default = SettingsDefaults.layoutBehavior.backLeavesSymbols,
            ) { scope.launch { repository.setBackLeavesSymbols(it) } }
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

}

/** Layout & size › Bottom row keys (#1432). */
@Composable
internal fun LayoutBottomRowSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    // The rows that act on the 🌐 key go with it: hidden, it has nothing for
    // them to change.
    val globeShown = settings.watch { it.showGlobeKey }
    val globeAsEmoji = settings.watch { it.globeAsEmoji }
    val holdActions = settings.watch { it.bottomRow.holdActions }
    SettingsGroup(stringResource(R.string.layout_bottom_row_keys_title)) {
        // Competitor gap list item 15: one row for every letters layout. First,
        // since every row below acts on the row it draws.
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.layout_bottom_row_preset_title,
                subtitle = stringResource(R.string.layout_bottom_row_preset_subtitle),
                info = stringResource(R.string.layout_bottom_row_preset_info),
                options = BottomRowPreset.entries.map { it to stringResource(bottomRowPresetLabel(it)) },
                selected = settings.watch { it.bottomRow.preset },
                default = SettingsDefaults.bottomRow.preset,
                detail = { ChoiceDetail(stringResource(bottomRowPresetDesc(it))) },
            ) { scope.launch { repository.setBottomRowPreset(it) } }
        }
        // Competitor gap list item 14: FlorisBoard's Fn key and FUTO's enter
        // hold menu, as a list on a key the user already has.
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.layout_hold_actions_key_title,
                subtitle = stringResource(R.string.layout_hold_actions_key_subtitle),
                info = stringResource(R.string.layout_hold_actions_key_info),
                options = HoldActionsKey.entries.map {
                    it to stringResource(
                        when (it) {
                            HoldActionsKey.ENTER -> R.string.layout_hold_actions_key_enter
                            HoldActionsKey.COMMA -> R.string.layout_hold_actions_key_comma
                            HoldActionsKey.GLOBE -> R.string.layout_hold_actions_key_globe
                        },
                    )
                },
                selected = settings.watch { it.bottomRow.holdKey },
                default = SettingsDefaults.bottomRow.holdKey,
            ) { scope.launch { repository.setHoldActionsKey(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            val layouts = secondaryLayouts(settings.watch { it.customLayouts })
            val modes = settings.watch { it.keyboardModes }
            var editing by remember { mutableStateOf(false) }
            NavRow(
                R.string.layout_hold_actions_title,
                stringResource(R.string.layout_hold_actions_subtitle),
                value = holdActionsSummary(holdActions, layouts, modes),
            ) { editing = true }
            if (editing) {
                HoldActionsDialog(
                    actions = holdActions,
                    layouts = layouts,
                    modes = modes,
                    onChange = { scope.launch { repository.setHoldActions(it) } },
                    onDismiss = { editing = false },
                )
            }
        }
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
        item(level = SettingLevel.ADVANCED) {
            // Competitor gap list item 3: HeliBoard's D-pad key, opt-in.
            ToggleSetting(
                R.string.layout_symbols_text_edit_key_title,
                stringResource(R.string.layout_symbols_text_edit_key_subtitle),
                settings.watch { it.layoutBehavior.symbolsTextEditKey },
                info = stringResource(R.string.layout_symbols_text_edit_key_info),
                default = SettingsDefaults.layoutBehavior.symbolsTextEditKey,
            ) { scope.launch { repository.setSymbolsTextEditKey(it) } }
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
        // What that key opens (#1126): the emoji panel, or any tool.
        item(visible = globeShown && globeAsEmoji) {
            val tool = settings.watch { it.layoutBehavior.globeTool }
            var picking by remember { mutableStateOf(false) }
            NavRow(
                title = R.string.layout_globe_tool_title,
                subtitle = stringResource(R.string.layout_globe_tool_subtitle),
                value = stringResource(tool?.let(::toolTitle) ?: R.string.layout_globe_tool_emoji_value),
            ) { picking = true }
            if (picking) {
                ToolPickerDialog(
                    title = stringResource(R.string.layout_globe_tool_title),
                    current = tool,
                    options = ToolbarTool.entries.filter { isSupportedTool(it) && it != ToolbarTool.EMOJI },
                    onDismiss = { picking = false },
                    onPick = { picked ->
                        picking = false
                        scope.launch { repository.setGlobeTool(picked) }
                    },
                    noneTitle = stringResource(R.string.layout_globe_tool_emoji_value),
                    noneSubtitle = stringResource(R.string.layout_globe_tool_emoji_subtitle),
                )
            }
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
    onNavigate: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val dpFormat = stringResource(R.string.typing_value_dp)
    val percentFormat = stringResource(R.string.typing_value_percent)
    val multiplierFormat = stringResource(R.string.keypress_value_multiplier)
    // What the keyboard uses while bottom padding is unset (#343). Read off
    // this screen's own insets, which carry the same kind of navigation bar.
    val autoBottomPadding = autoBottomPaddingDp(gestureBarAtBottom())
    // What decides which rows the groups hold; each row reads its own value.
    val cornersRound = settings.watch {
        it.layoutBehavior.boardCornerTopDp > 0 || it.layoutBehavior.boardCornerBottomDp > 0 ||
            it.layoutBehavior.boardInverseCornerDp > 0
    }
    val narrowed = settings.watch { it.keyboardWidthPercent < 100 }
    // The keys narrower than the window, by width or by either edge pad: the
    // only time the toolbar can be wider than them.
    val keysNarrowed = settings.watch {
        it.keyboardWidthPercent < 100 || it.layoutBehavior.sidePadLeftScale > 0f ||
            it.layoutBehavior.sidePadRightScale > 0f
    }
    val sizingMoved = settings.watch { s ->
        s.keyHeightDp != SettingsDefaults.keyHeightDp ||
            s.numberRowHeightDp != SettingsDefaults.numberRowHeightDp ||
            s.bottomPaddingDp != null ||
            s.keyboardWidthPercent != SettingsDefaults.keyboardWidthPercent ||
            s.keyboardAlignment != SettingsDefaults.keyboardAlignment ||
            s.keyGapScale != SettingsDefaults.keyGapScale ||
            s.layoutBehavior.keyGapColumnScale != SettingsDefaults.layoutBehavior.keyGapColumnScale ||
            s.layoutBehavior.keyGapRowScale != SettingsDefaults.layoutBehavior.keyGapRowScale ||
            s.keyCornerRadiusDp != SettingsDefaults.keyCornerRadiusDp ||
            s.layoutBehavior.sidePadLeftScale !=
            SettingsDefaults.layoutBehavior.sidePadLeftScale ||
            s.layoutBehavior.sidePadRightScale !=
            SettingsDefaults.layoutBehavior.sidePadRightScale ||
            s.layoutBehavior.bottomRowHeightDp !=
            SettingsDefaults.layoutBehavior.bottomRowHeightDp ||
            s.layoutBehavior.fullWidthBars != SettingsDefaults.layoutBehavior.fullWidthBars ||
            s.toolbarHeightDp != SettingsDefaults.toolbarHeightDp
    }
    val preview = LocalKeyboardPreview.current
    val sizeNumberRow = settings.watch { it.numberRow }
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
                range = SettingsRepository.KEY_HEIGHT_MIN_DP.toFloat()..SettingsRepository.KEY_HEIGHT_MAX_DP.toFloat(),
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_key_height_info),
                enabled = pin == null,
                disabledReason = pin,
                default = SettingsDefaults.keyHeightDp.toFloat(),
            ) { scope.launch { repository.setKeyHeightDp(it.toInt()) } }
        }
        // Here too, beside the key height it can follow (#1376), and not only
        // under the number row's own switch in Layout.
        item(visible = sizeNumberRow, level = SettingLevel.ADVANCED) {
            NumberRowHeightSlider(settings, repository, scope)
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
            // The row above the keys, sized like the rows of keys (Telegram
            // group, 2026-11-05). A theme may pin it, as it may the key height.
            val pin = themePin(settings) { it.toolbarHeightDp }
            SliderSetting(
                R.string.layout_toolbar_height_title,
                subtitle = pin?.text ?: stringResource(R.string.layout_toolbar_height_subtitle),
                value = settings.watch { it.toolbarHeightDp }.toFloat(),
                range = ToolbarHeightRange.first.toFloat()..ToolbarHeightRange.last.toFloat(),
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_toolbar_height_info),
                enabled = pin == null,
                disabledReason = pin,
                default = SettingsDefaults.toolbarHeightDp.toFloat(),
            ) { scope.launch { repository.setToolbarHeightDp(it.toInt()) } }
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
        // Each axis on its own, over Key spacing (Telegram 10316): keys less
        // square, with wider columns and tight rows or the other way round.
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.layout_key_spacing_columns_title,
                subtitle = stringResource(R.string.layout_key_spacing_columns_subtitle),
                value = settings.watch { it.layoutBehavior.keyGapColumnScale },
                range = KeyGapAxisScaleRange,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_key_spacing_columns_info),
                default = SettingsDefaults.layoutBehavior.keyGapColumnScale,
            ) { scope.launch { repository.setKeyGapColumnScale(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.layout_key_spacing_rows_title,
                subtitle = stringResource(R.string.layout_key_spacing_rows_subtitle),
                value = settings.watch { it.layoutBehavior.keyGapRowScale },
                range = KeyGapAxisScaleRange,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_key_spacing_rows_info),
                default = SettingsDefaults.layoutBehavior.keyGapRowScale,
            ) { scope.launch { repository.setKeyGapRowScale(it) } }
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
            ToggleSetting(
                R.string.layout_full_height_window_title,
                stringResource(R.string.layout_full_height_window_subtitle),
                settings.watch { it.layoutBehavior.fullHeightWindow },
                info = stringResource(R.string.layout_full_height_window_info),
                default = SettingsDefaults.layoutBehavior.fullHeightWindow,
            ) { scope.launch { repository.setFullHeightWindow(it) } }
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
        item(level = SettingLevel.ADVANCED) {
            val off = stringResource(R.string.layout_board_inverse_corner_off)
            SliderSetting(
                R.string.layout_board_inverse_corner_title,
                subtitle = stringResource(R.string.layout_board_inverse_corner_subtitle),
                value = settings.watch { it.layoutBehavior.boardInverseCornerDp }.toFloat(),
                range = BoardCornerRadiusRange.first.toFloat()..BoardCornerRadiusRange.last.toFloat(),
                display = { if (it.roundToInt() == 0) off else dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.layout_board_inverse_corner_info),
                default = SettingsDefaults.layoutBehavior.boardInverseCornerDp.toFloat(),
            ) { scope.launch { repository.setBoardInverseCornerDp(it.roundToInt()) } }
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
                    info = stringResource(R.string.layout_board_corners_info),
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
        item(visible = keysNarrowed, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.layout_full_width_bars_title,
                stringResource(R.string.layout_full_width_bars_subtitle),
                settings.watch { it.layoutBehavior.fullWidthBars },
                info = stringResource(R.string.layout_full_width_bars_info),
                default = SettingsDefaults.layoutBehavior.fullWidthBars,
            ) { scope.launch { repository.setFullWidthBars(it) } }
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
                        onNavigate("$VARIANT_SIZE_ROUTE_PREFIX${variant.name.lowercase()}")
                    },
                )
            }
        }
    }
}

/** Where one screen shape's sizes live: `layout/size/landscape` and so on. */
internal const val VARIANT_SIZE_ROUTE_PREFIX = "layout/size/"

/** The screen shape a `layout/size/{variant}` route names; null for portrait or an unknown name. */
internal fun sizeVariantOf(segment: String?): ScreenVariant? =
    ScreenVariant.entries.firstOrNull { it.isOverride && it.name.equals(segment, ignoreCase = true) }

/**
 * One screen shape's own sizes (#43): a page of its own, opened from
 * Per-screen sizing, instead of rows that unfolded inside the size page and
 * pushed everything under them down. Each value left alone follows portrait.
 */
@Composable
internal fun LayoutVariantSizeSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    variant: ScreenVariant,
) {
    val scope = rememberCoroutineScope()
    val dpFormat = stringResource(R.string.typing_value_dp)
    val percentFormat = stringResource(R.string.typing_value_percent)
    val multiplierFormat = stringResource(R.string.keypress_value_multiplier)
    val autoBottomPadding = autoBottomPaddingDp(gestureBarAtBottom())
    // This shape's own values decide which rows are there.
    val shapeNumberRow = settings.watch { s -> s.sizingValuesFor(variant).numberRow ?: s.numberRow }
    val shapeNarrowed = settings.watch { s ->
        (s.sizingValuesFor(variant).keyboardWidthPercent ?: s.keyboardWidthPercent) < 100
    }
    val shapeKeysNarrowed = settings.watch { s ->
        val v = s.sizingValuesFor(variant)
        ((v.keyboardWidthPercent ?: s.keyboardWidthPercent) < 100) ||
            (v.sidePadLeftScale ?: s.layoutBehavior.sidePadLeftScale) > 0f ||
            (v.sidePadRightScale ?: s.layoutBehavior.sidePadRightScale) > 0f
    }
    val shapeOverridden = settings.watch { s -> s.sizingOverrides[variant]?.let { !it.isEmpty } == true }
    SettingsGroup(
        stringResource(R.string.layout_size_position_title),
        info = stringResource(R.string.layout_per_screen_caption),
    ) {
        item {
            SliderSetting(
                R.string.layout_keyboard_scale_title,
                subtitle = stringResource(R.string.layout_keyboard_scale_subtitle),
                value = settings.watch { it.sizingValuesFor(variant).keyboardScale } ?: 1f,
                range = 0.5f..1.5f,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_keyboard_scale_info),
            ) { scope.launch { repository.setVariantKeyboardScale(variant, it) } }
        }
        item {
            SliderSetting(
                R.string.layout_key_height_title,
                value = settings.watch { it.sizingValuesFor(variant).keyHeightDp ?: it.keyHeightDp }
                    .toFloat(),
                range = SettingsRepository.KEY_HEIGHT_MIN_DP.toFloat()..SettingsRepository.KEY_HEIGHT_MAX_DP.toFloat(),
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_key_height_info),
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
                range = SettingsRepository.KEY_HEIGHT_MIN_DP.toFloat()..SettingsRepository.KEY_HEIGHT_MAX_DP.toFloat(),
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_number_row_height_info),
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
                info = stringResource(R.string.layout_bottom_padding_info),
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
                info = stringResource(R.string.layout_keyboard_width_info),
            ) { scope.launch { repository.setVariantWidthPercent(variant, it.toInt()) } }
        }
        item(visible = shapeKeysNarrowed, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.layout_full_width_bars_title,
                stringResource(R.string.layout_full_width_bars_subtitle),
                settings.watch {
                    it.sizingValuesFor(variant).fullWidthBars ?: it.layoutBehavior.fullWidthBars
                },
                info = stringResource(R.string.layout_full_width_bars_info),
            ) { scope.launch { repository.setVariantFullWidthBars(variant, it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.layout_toolbar_height_title,
                value = settings.watch {
                    it.sizingValuesFor(variant).toolbarHeightDp ?: it.toolbarHeightDp
                }.toFloat(),
                range = ToolbarHeightRange.first.toFloat()..ToolbarHeightRange.last.toFloat(),
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_toolbar_height_info),
            ) { scope.launch { repository.setVariantToolbarHeightDp(variant, it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.layout_font_size_title,
                value = settings.watch { it.sizingValuesFor(variant).fontScale ?: it.fontScale },
                range = KeyFontScaleRange,
                display = { multiplierFormat.format(it) },
                info = stringResource(R.string.layout_font_size_info),
            ) { scope.launch { repository.setVariantFontScale(variant, it) } }
        }
        item(level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.layout_key_spacing_title,
                value = settings.watch { it.sizingValuesFor(variant).keyGapScale ?: it.keyGapScale },
                range = 0f..2f,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_key_spacing_info),
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
                info = stringResource(R.string.layout_side_padding_left_info),
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
                info = stringResource(R.string.layout_side_padding_right_info),
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
                info = stringResource(R.string.layout_bottom_row_height_info),
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
                info = stringResource(R.string.layout_number_row_info),
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
                info = stringResource(R.string.layout_variant_allow_one_handed_info),
            ) { scope.launch { repository.setVariantAllowOneHanded(variant, it) } }
        }
        item {
            ToggleSetting(
                R.string.layout_variant_allow_split_title,
                stringResource(R.string.layout_variant_allow_split_subtitle),
                settings.watch { it.splitAllowedOn(variant) },
                info = stringResource(R.string.layout_variant_allow_split_info),
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
                info = stringResource(R.string.layout_keyboard_position_info),
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
                info = stringResource(R.string.layout_one_handed_info),
            ) { scope.launch { repository.setOneHandedMode(it) } }
        }
        // Shown with one-handed mode off too, so the board can be chosen
        // before the mode is first used (#1230). Only layouts that can apply:
        // an enabled one sharing its language with another enabled one.
        item {
            val choices = settings.watch { it.oneHandedLayoutChoices() }
            val customLayouts = settings.watch { it.customLayouts }
            val same = stringResource(R.string.layout_one_handed_layout_same)
            ChoiceSetting(
                R.string.layout_one_handed_layout_title,
                subtitle = stringResource(
                    if (choices.isEmpty()) {
                        R.string.layout_one_handed_layout_none_subtitle
                    } else {
                        R.string.layout_one_handed_layout_subtitle
                    },
                ),
                info = stringResource(R.string.layout_one_handed_layout_info),
                options = listOf<Pair<String?, String>>(null to same) + choices.map { id ->
                    id to "${resolveLayoutName(customLayouts, id)} (${resolveLayoutLanguage(customLayouts, id).displayName})"
                },
                // A pick since turned off, or left alone in its language, reads
                // as what the keyboard does with it: the normal layout.
                selected = settings.watch { it.oneHanded.layoutId }?.takeIf { it in choices },
                default = SettingsDefaults.oneHanded.layoutId,
            ) { scope.launch { repository.setOneHandedLayout(it) } }
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
        item(visible = split, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_split_see_through_title,
                stringResource(R.string.layout_split_see_through_subtitle),
                settings.watch { it.layoutBehavior.splitGapSeeThrough },
                info = stringResource(R.string.layout_split_see_through_info),
                default = SettingsDefaults.layoutBehavior.splitGapSeeThrough,
            ) { scope.launch { repository.setSplitGapSeeThrough(it) } }
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
        // The same text box with the phone upright (gap list item 50).
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_portrait_fullscreen_title,
                stringResource(R.string.layout_portrait_fullscreen_subtitle),
                settings.watch { it.layoutBehavior.portraitFullscreen },
                info = stringResource(R.string.layout_portrait_fullscreen_info),
                default = SettingsDefaults.layoutBehavior.portraitFullscreen,
            ) { scope.launch { repository.setPortraitFullscreen(it) } }
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
    ToolbarPlacement.ON_SWIPE -> R.string.appearance_toolbar_placement_swipe_desc
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

/** The name of a [BottomRowPreset] in the Bottom row choice. */
@StringRes
private fun bottomRowPresetLabel(preset: BottomRowPreset): Int = when (preset) {
    BottomRowPreset.AS_LAID_OUT -> R.string.layout_bottom_row_preset_as_laid_out
    BottomRowPreset.CLASSIC -> R.string.layout_bottom_row_preset_classic
    BottomRowPreset.IPHONE -> R.string.layout_bottom_row_preset_iphone
    BottomRowPreset.MINIMAL -> R.string.layout_bottom_row_preset_minimal
    BottomRowPreset.WITH_EMOJI -> R.string.layout_bottom_row_preset_with_emoji
}

/** The row a [BottomRowPreset] draws, spelled out under its name. */
@StringRes
private fun bottomRowPresetDesc(preset: BottomRowPreset): Int = when (preset) {
    BottomRowPreset.AS_LAID_OUT -> R.string.layout_bottom_row_preset_as_laid_out_desc
    BottomRowPreset.CLASSIC -> R.string.layout_bottom_row_preset_classic_desc
    BottomRowPreset.IPHONE -> R.string.layout_bottom_row_preset_iphone_desc
    BottomRowPreset.MINIMAL -> R.string.layout_bottom_row_preset_minimal_desc
    BottomRowPreset.WITH_EMOJI -> R.string.layout_bottom_row_preset_with_emoji_desc
}

/** Whether [this] way of fitting narrows the letters, so the Narrowest letters row matters. */
private val LongWordFit.narrows: Boolean
    get() = this == LongWordFit.NARROW_THEN_SHRINK || this == LongWordFit.SHRINK_THEN_NARROW || this == LongWordFit.NARROW_ONLY

/** Whether [this] way of fitting shrinks the text, so the Smallest text row matters. */
private val LongWordFit.shrinks: Boolean
    get() = this == LongWordFit.NARROW_THEN_SHRINK || this == LongWordFit.SHRINK_THEN_NARROW || this == LongWordFit.SHRINK_ONLY

/**
 * A sample suggestion strip under the Long words rows: a short word, a medium
 * one and one too long for its slot, fitted with [fitting] at the strip's own
 * text size, in three equal slots the way the strip lays them out.
 */
@Composable
private fun LongWordsPreview(fitting: TextFitting, textScale: Float) {
    val words = listOf(
        stringResource(R.string.appearance_long_words_preview_short),
        stringResource(R.string.appearance_long_words_preview_medium),
        stringResource(R.string.appearance_long_words_preview_long),
    )
    val description = stringResource(R.string.appearance_long_words_preview_description)
    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp * textScale, letterSpacing = 0.5.sp)
    val divider = MaterialTheme.colorScheme.outlineVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = description },
    ) {
        words.forEachIndexed { index, word ->
            if (index > 0) {
                Box(
                    Modifier.width(1.dp).height(20.dp).background(divider),
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    FitText(word, style, fitting = fitting)
                }
            }
        }
    }
}

/**
 * The number row's height, with "Same as keys" at its leftmost stop (#1376),
 * the way the bottom row's slider has one: the row then follows the key
 * height however that is set. In Layout under the number row's switch and in
 * Size and position beside the key height. A theme's own height pins it.
 */
@Composable
private fun NumberRowHeightSlider(
    settings: LiveSettings,
    repository: SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val pin = themePin(settings) { it.numberRowHeightDp }
    val follows = settings.watch { it.layoutBehavior.numberRowFollowsKeys }
    val height = settings.watch { it.numberRowHeightDp }
    val followStop = (SettingsRepository.KEY_HEIGHT_MIN_DP - 1).toFloat()
    val sameAsKeys = stringResource(R.string.layout_number_row_follow_keys_label)
    val dpFormat = stringResource(R.string.typing_value_dp)
    SliderSetting(
        R.string.layout_number_row_height_title,
        subtitle = pin?.text ?: stringResource(R.string.layout_number_row_height_subtitle),
        value = if (follows) followStop else height.toFloat(),
        range = followStop..SettingsRepository.KEY_HEIGHT_MAX_DP.toFloat(),
        display = { if (it < SettingsRepository.KEY_HEIGHT_MIN_DP) sameAsKeys else dpFormat.format(it.toInt()) },
        info = stringResource(R.string.layout_number_row_height_info),
        enabled = pin == null,
        disabledReason = pin,
        default = SettingsDefaults.numberRowHeightDp.toFloat(),
    ) { scope.launch { repository.setNumberRowHeightDp(it.toInt()) } }
}
