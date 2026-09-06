package com.wasimaster.wmkeyboard.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import com.wasimaster.wmkeyboard.app.media.MusicApps
import com.wasimaster.wmkeyboard.core.media.hasNotificationAccess
import com.wasimaster.wmkeyboard.core.settings.AppSortOrder
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.tools.CryptoCatalog
import com.wasimaster.wmkeyboard.core.tools.CurrencyClient
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.ui.toolAccentColorArgb
import com.wasimaster.wmkeyboard.core.ui.toolAccentEndColorArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Build
import com.wasimaster.wmkeyboard.core.handwriting.HandwritingDownloadProgress
import com.wasimaster.wmkeyboard.core.handwriting.HandwritingModels
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.ime.WMKeyboardService
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import com.wasimaster.wmkeyboard.core.settings.GifContentFilter
import com.wasimaster.wmkeyboard.core.settings.GifSourceMode
import com.wasimaster.wmkeyboard.core.settings.GrammarDialect
import com.wasimaster.wmkeyboard.core.settings.MediaSendMode
import com.wasimaster.wmkeyboard.core.settings.QrEccLevel
import com.wasimaster.wmkeyboard.core.tools.AltCalendar
import com.wasimaster.wmkeyboard.core.tools.Weekend
import com.wasimaster.wmkeyboard.core.tools.isSouthernHemisphere
import com.wasimaster.wmkeyboard.core.tools.GeoPlace
import com.wasimaster.wmkeyboard.core.tools.SmartSuggest
import com.wasimaster.wmkeyboard.core.tools.MediaCategoryCache
import com.wasimaster.wmkeyboard.core.tools.ToolApiKeys
import com.wasimaster.wmkeyboard.core.tools.TypingAchievements
import com.wasimaster.wmkeyboard.core.tools.TypingBests
import com.wasimaster.wmkeyboard.core.tools.typingConfigBase
import com.wasimaster.wmkeyboard.core.tools.typingConfigLanguage
import com.wasimaster.wmkeyboard.core.tools.TypingHistory
import com.wasimaster.wmkeyboard.core.tools.TypingTestMode
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.tools.WeatherClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.settings.HoldRepeatCursorTools
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.settings.LauncherToolSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.core.settings.PowerSavingTrigger
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * What a press and hold on this tool does while it is pinned to the toolbar.
 *
 * On the tool's own page rather than on a central list under Appearance: that
 * list showed only the pinned tools, so a fresh install found three rows and no
 * way to give any other tool a hold, and nobody looked under Appearance for it
 * anyway (#31). The bound tool runs whether or not it is itself on the toolbar.
 *
 * A tool with nothing bound keeps the original behaviour and opens its own
 * settings page, which the row says in as many words — an explicit "settings"
 * entry in the picker made the default look like a choice the user had made.
 * The caret tools and Selection mode say their reason instead of hiding the
 * row: their hold is already spent, on a repeat or on arming the mode, and
 * someone looking for the row here should find out why.
 */
@Composable
private fun ToolHoldRow(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    tool: ToolbarTool,
) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    val repeats = tool in HoldRepeatCursorTools && settings.textEditing.cursorToolsRepeatOnHold
    val selects = tool == ToolbarTool.SELECT_MODE && settings.textEditing.selectionModeHold
    val tracks = tool == ToolbarTool.TRACKPAD && settings.trackpad.holdToOpen
    val bound = settings.toolbarBehavior.holdActions[tool]
    if (repeats || selects || tracks) {
        // Reads rather than opens: this tool's hold has none to give.
        WmRow(
            title = stringResource(R.string.tooldetail_hold_title),
            subtitle = stringResource(
                when {
                    repeats -> R.string.tooldetail_hold_repeats_subtitle
                    selects -> R.string.tooldetail_hold_selects_subtitle
                    else -> R.string.tooldetail_hold_trackpad_subtitle
                },
            ),
            icon = SettingsRowIcons[R.string.tooldetail_hold_title],
        )
        return
    }
    NavRow(
        title = R.string.tooldetail_hold_title,
        subtitle = stringResource(R.string.tooldetail_hold_subtitle),
        value = if (bound == null) {
            stringResource(R.string.tooldetail_hold_settings_value)
        } else {
            stringResource(toolTitle(bound))
        },
    ) { editing = true }
    if (editing) {
        ToolPickerDialog(
            title = stringResource(R.string.tooldetail_hold_pick_title, stringResource(toolTitle(tool))),
            current = bound,
            // Every tool but this one: holding a tool to run itself is a slow tap.
            options = ToolbarTool.entries.filter { isSupportedTool(it) && it != tool },
            noneSubtitle = stringResource(R.string.tooldetail_hold_settings_value),
            onDismiss = { editing = false },
            onPick = { picked ->
                editing = false
                scope.launch { repository.setToolHoldAction(tool, picked) }
            },
        )
    }
}
/**
 * Picks one tool out of every tool, or none. Its own dialog rather than a
 * [ChoiceSetting] because the list is forty entries long and has to scroll.
 *
 * [noneSubtitle] both words the "None" row and decides whether there is one: the
 * layout editor picks the tool a key opens, where "no tool" is not a key anyone
 * would want, so it passes null and the row goes.
 */
@Composable
internal fun ToolPickerDialog(
    title: String,
    current: ToolbarTool?,
    options: List<ToolbarTool>,
    onDismiss: () -> Unit,
    onPick: (ToolbarTool?) -> Unit,
    noneSubtitle: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                if (noneSubtitle != null) item {
                    WmRow(
                        title = stringResource(CommonR.string.common_none),
                        supporting = { CaptionText(noneSubtitle) },
                        trailing = { RadioButton(selected = current == null, onClick = { onPick(null) }) },
                        onClick = { onPick(null) },
                    )
                }
                items(options, key = { it.name }) { tool ->
                    WmRow(
                        title = stringResource(toolTitle(tool)),
                        leading = { SlotIcon(IconSlots.forTool(tool), contentDescription = null) },
                        trailing = { RadioButton(selected = current == tool, onClick = { onPick(tool) }) },
                        onClick = { onPick(tool) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
/** One tool's screen: the enable switch plus every setting the tool has. */
@Composable
internal fun ToolDetailSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    tool: ToolbarTool,
    onNavigate: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val numberFormat = stringResource(R.string.values_number)
    val percentFormat = stringResource(R.string.typing_value_percent)
    val dpFormat = stringResource(R.string.typing_value_dp)
    val msFormat = stringResource(R.string.typing_value_milliseconds)
    val minutesFormat = stringResource(R.string.values_minutes)
    val hoursFormat = stringResource(R.string.values_hours)
    val pixelsFormat = stringResource(R.string.values_pixels)
    val daysFormat = stringResource(R.string.values_days)
    val daysAheadFormat = stringResource(R.string.values_days_ahead)
    SettingsGroup {
        item {
            // The search tools need a key before they can be switched on; the
            // field that takes one is further down this same screen.
            val usable = isUsableTool(tool, settings)
            ToggleSetting(
                CommonR.string.common_enable,
                if (usable) {
                    stringResource(R.string.tooldetail_enabled_subtitle)
                } else {
                    stringResource(R.string.tooldetail_enabled_needs_key_subtitle)
                },
                usable && tool in settings.enabledTools,
                switchKey = landingKey("switch"),
                enabled = usable,
                default = usable && tool in SettingsDefaults.enabledTools,
            ) { scope.launch { repository.setToolEnabled(tool, it) } }
        }
        // Recolour just this tool's icon. Only meaningful while the global
        // "Colorful tool icons" switch is on, since it's what paints them.
        if (settings.coloredToolIcons) {
            val gradient = settings.toolIconGradients
            item {
                ColorSetting(
                    // With the gradients off there is one colour and it needs
                    // no qualifier; with them on the two rows have to say which
                    // end of the gradient each one is. Named by resource rather
                    // than resolved here, so the row picks its icon out of
                    // [SettingsRowIcons] like every other settings row.
                    title = if (gradient) R.string.tooldetail_icon_colour_start_title
                    else R.string.tooldetail_icon_colour_title,
                    color = settings.toolColorOverrides[tool],
                    fallback = toolAccentColorArgb(tool),
                    onChange = { scope.launch { repository.setToolColor(tool, it) } },
                )
            }
            if (gradient) {
                item {
                    ColorSetting(
                        title = R.string.tooldetail_icon_colour_end_title,
                        color = settings.toolColorEndOverrides[tool],
                        // Derived from whichever colour the near end currently
                        // is, so the pair moves together until it is pinned.
                        fallback = toolAccentEndColorArgb(tool, settings.toolColorOverrides),
                        onChange = { scope.launch { repository.setToolColorEnd(tool, it) } },
                    )
                }
            }
        }
        item { ToolHoldRow(repository, settings, tool) }
    }
    ToolKeywordSetting(repository, settings, tool)
    when (tool) {
        ToolbarTool.MEDIA_CONTROL -> {
            // Re-read whenever this screen comes back to the foreground: the
            // grant is made on a system screen, so the user leaves, ticks the
            // listener, and returns to a row that has to have noticed.
            val hasAccess = rememberGrantState(::hasNotificationAccess)
            val openAccess = rememberDisclosedSpecialAccess(SpecialAccess.NOTIFICATIONS)
            SettingsGroup(
                stringResource(R.string.tooldetail_mediactl_group),
                info = stringResource(R.string.tooldetail_mediactl_info),
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_mediactl_pin_title,
                        stringResource(R.string.tooldetail_mediactl_pin_subtitle),
                        settings.mediaControl.pinWhilePlaying,
                        info = stringResource(R.string.tooldetail_mediactl_pin_info),
                        default = SettingsDefaults.mediaControl.pinWhilePlaying,
                    ) { scope.launch { repository.setMediaPinWhilePlaying(it) } }
                }
                if (settings.mediaControl.pinWhilePlaying) {
                    item {
                        NavRow(
                            title = R.string.tooldetail_mediactl_apps_title,
                            subtitle = stringResource(
                                R.string.tooldetail_mediactl_apps_subtitle,
                                settings.mediaControl.musicApps.size,
                            ),
                        ) { onNavigate(MusicApps.ROUTE) }
                    }
                }
                item {
                    // Not a toggle: notification access is granted on a system
                    // screen and can only be revoked there too, so the row
                    // reports the state and opens that screen.
                    NavRow(
                        title = R.string.tooldetail_mediactl_access_title,
                        subtitle = stringResource(
                            if (hasAccess) {
                                R.string.tooldetail_mediactl_access_granted
                            } else {
                                R.string.tooldetail_mediactl_access_missing
                            },
                        ),
                    ) { openAccess() }
                }
            }
        }
        ToolbarTool.APP_LAUNCHER ->
            SettingsGroup(stringResource(R.string.tooldetail_launcher_group)) {
                item {
                    ChoiceSetting(
                        R.string.tooldetail_launcher_sort_title,
                        subtitle = stringResource(R.string.tooldetail_launcher_sort_subtitle),
                        options = listOf(
                            AppSortOrder.ALPHABETICAL to
                                stringResource(R.string.tooldetail_launcher_sort_alpha_label),
                            AppSortOrder.RECENT_FIRST to
                                stringResource(R.string.tooldetail_launcher_sort_recent_label),
                        ),
                        selected = settings.launcher.sortOrder,
                        default = SettingsDefaults.launcher.sortOrder,
                    ) { scope.launch { repository.setLauncherSortOrder(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_launcher_labels_title,
                        stringResource(R.string.tooldetail_launcher_labels_subtitle),
                        settings.launcher.showLabels,
                        default = SettingsDefaults.launcher.showLabels,
                    ) { scope.launch { repository.setLauncherShowLabels(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_launcher_recents_title,
                        stringResource(R.string.tooldetail_launcher_recents_subtitle),
                        settings.launcher.recentsEnabled,
                        info = stringResource(R.string.tooldetail_launcher_recents_info),
                        default = SettingsDefaults.launcher.recentsEnabled,
                    ) { scope.launch { repository.setLauncherRecentsEnabled(it) } }
                }
                if (settings.launcher.recentsEnabled) {
                    item {
                        val appsFormat = stringResource(R.string.values_number)
                        SliderSetting(
                            R.string.tooldetail_launcher_recents_count_title,
                            subtitle = stringResource(
                                R.string.tooldetail_launcher_recents_count_subtitle,
                            ),
                            value = settings.launcher.maxRecents.toFloat(),
                            range = LauncherToolSettings.RECENTS_RANGE.first.toFloat()..
                                LauncherToolSettings.RECENTS_RANGE.last.toFloat(),
                            display = { appsFormat.format(it.roundToInt()) },
                            info = stringResource(R.string.tooldetail_launcher_recents_count_info),
                            default = SettingsDefaults.launcher.maxRecents.toFloat(),
                        ) { scope.launch { repository.setLauncherMaxRecents(it.roundToInt()) } }
                    }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_launcher_drilldown_title,
                        stringResource(R.string.tooldetail_launcher_drilldown_subtitle),
                        settings.launcher.activityDrilldown,
                        info = stringResource(R.string.tooldetail_launcher_drilldown_info),
                        default = SettingsDefaults.launcher.activityDrilldown,
                    ) { scope.launch { repository.setLauncherActivityDrilldown(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_launcher_non_exported_title,
                        stringResource(R.string.tooldetail_launcher_non_exported_subtitle),
                        settings.launcher.showNonExported,
                        info = stringResource(R.string.tooldetail_launcher_non_exported_info),
                        default = SettingsDefaults.launcher.showNonExported,
                    ) { scope.launch { repository.setLauncherShowNonExported(it) } }
                }
            }
        ToolbarTool.PLUGINS -> SettingsGroup(stringResource(R.string.tooldetail_plugins_group)) {
            item {
                WmRow(
                    title = stringResource(R.string.tooldetail_plugins_manage_title),
                    subtitle = stringResource(R.string.tooldetail_plugins_manage_subtitle),
                    onClick = { onNavigate("plugins") },
                )
            }
        }
        ToolbarTool.EMOJI -> SettingsGroup(stringResource(R.string.tooldetail_emoji_group)) {
            item {
                NavRow(
                    R.string.tooldetail_emoji_all_title,
                    stringResource(R.string.tooldetail_emoji_all_subtitle),
                    onClick = { onNavigate("emoji") },
                )
            }
        }
        ToolbarTool.SNIPPETS -> SettingsGroup(stringResource(R.string.tooldetail_snippets_group)) {
            item {
                NavRow(
                    R.string.tooldetail_snippets_all_title,
                    stringResource(R.string.tooldetail_snippets_all_subtitle),
                    onClick = { onNavigate("expander") },
                )
            }
        }
        ToolbarTool.CLIPBOARD -> SettingsGroup(stringResource(R.string.tooldetail_clipboard_group)) {
            item {
                NavRow(
                    R.string.tooldetail_clipboard_all_title,
                    stringResource(R.string.tooldetail_clipboard_all_subtitle),
                    onClick = { onNavigate("clipboard") },
                )
            }
        }
        ToolbarTool.SPLIT -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                NavRow(
                    R.string.tooldetail_layout_nav_title,
                    stringResource(R.string.tooldetail_layout_nav_split_subtitle),
                    route = "layout/onehanded",
                    onClick = { onNavigate("layout/onehanded") },
                )
            }
        }
        ToolbarTool.FLOATING -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                NavRow(
                    R.string.tooldetail_layout_nav_title,
                    stringResource(R.string.tooldetail_layout_nav_floating_subtitle),
                    route = "layout/onehanded",
                    onClick = { onNavigate("layout/onehanded") },
                )
            }
        }
        ToolbarTool.FLASHLIGHT -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_flashlight_auto_off_title,
                    stringResource(R.string.tooldetail_flashlight_auto_off_subtitle),
                    settings.flashlightAutoOff,
                    info = stringResource(R.string.tooldetail_flashlight_auto_off_info),
                    default = SettingsDefaults.flashlightAutoOff,
                ) { scope.launch { repository.setFlashlightAutoOff(it) } }
            }
        }
        ToolbarTool.COMPASS -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_compass_degrees_title,
                        stringResource(R.string.tooldetail_compass_degrees_subtitle),
                        settings.compassShowDegrees,
                        default = SettingsDefaults.compassShowDegrees,
                    ) { scope.launch { repository.setCompassShowDegrees(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_compass_qibla_title,
                        stringResource(R.string.tooldetail_compass_qibla_subtitle),
                        settings.compassShowQibla,
                        info = stringResource(R.string.tooldetail_compass_qibla_info),
                        default = SettingsDefaults.compassShowQibla,
                    ) { scope.launch { repository.setCompassShowQibla(it) } }
                }
            }
            if (settings.compassShowQibla && settings.weatherLatitude == null) {
                StateBanner(
                    stringResource(R.string.tooldetail_compass_no_location_error),
                    action = stringResource(toolTitle(ToolbarTool.WEATHER)),
                    tone = BannerTone.WARNING,
                ) { onNavigate(toolRoute(ToolbarTool.WEATHER)) }
            }
        }
        ToolbarTool.LEVEL -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_level_angles_title,
                    stringResource(R.string.tooldetail_level_angles_subtitle),
                    settings.levelShowAngles,
                    default = SettingsDefaults.levelShowAngles,
                ) { scope.launch { repository.setLevelShowAngles(it) } }
            }
        }
        ToolbarTool.UNDO, ToolbarTool.REDO ->
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_redo_ctrl_y_title,
                        stringResource(R.string.tooldetail_redo_ctrl_y_subtitle),
                        settings.redoUsesCtrlY,
                        info = stringResource(R.string.tooldetail_redo_ctrl_y_info),
                        default = SettingsDefaults.redoUsesCtrlY,
                    ) { scope.launch { repository.setRedoUsesCtrlY(it) } }
                }
            }
        ToolbarTool.MOON_PHASE -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_moon_southern_title,
                    stringResource(R.string.tooldetail_moon_southern_subtitle),
                    settings.moonSouthernHemisphere,
                    // Not SettingsDefaults: this one starts from the device's
                    // region, so reset has to land back on that and not on
                    // the northern hemisphere the data class declares.
                    default = isSouthernHemisphere(repository.deviceRegion),
                ) { scope.launch { repository.setMoonSouthernHemisphere(it) } }
            }
        }
        ToolbarTool.WEATHER -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_weather_info),
            ) {
                item { WeatherLocationSetting(repository, settings) }
                item {
                    ToggleSetting(
                        R.string.tooldetail_weather_fahrenheit_title,
                        stringResource(R.string.tooldetail_weather_fahrenheit_subtitle),
                        settings.weatherFahrenheit,
                        default = SettingsDefaults.weatherFahrenheit,
                    ) { scope.launch { repository.setWeatherFahrenheit(it) } }
                }
                item {
                    val weatherMinutesFormat = stringResource(R.string.values_minutes)
                    SliderSetting(
                        R.string.tooldetail_weather_refresh_title,
                        subtitle = stringResource(R.string.tooldetail_weather_refresh_subtitle),
                        value = settings.toolLimits.weatherRefreshMinutes.toFloat(),
                        range = 1f..180f,
                        display = { weatherMinutesFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.tooldetail_weather_refresh_info),
                        default = SettingsDefaults.toolLimits.weatherRefreshMinutes.toFloat(),
                    ) { picked ->
                        scope.launch { repository.setWeatherRefreshMinutes(picked.roundToInt()) }
                    }
                }
            }
        }
        ToolbarTool.CALENDAR -> {
            val showsHijri = settings.calendarAltOne == AltCalendar.HIJRI ||
                settings.calendarAltTwo == AltCalendar.HIJRI
            SettingsGroup(
                stringResource(R.string.tooldetail_calendar_group),
                info = listOf(
                    stringResource(R.string.tooldetail_calendar_info),
                    stringResource(R.string.tooldetail_calendar_events_info),
                ).joinToString("\n\n"),
            ) {
                item {
                    AltCalendarSetting(
                        title = stringResource(R.string.tooldetail_calendar_first_title),
                        subtitle = stringResource(R.string.tooldetail_calendar_first_subtitle),
                        selected = settings.calendarAltOne,
                        onChange = { scope.launch { repository.setCalendarAltOne(it) } },
                    )
                }
                item {
                    AltCalendarSetting(
                        title = stringResource(R.string.tooldetail_calendar_second_title),
                        subtitle = stringResource(R.string.tooldetail_calendar_second_subtitle),
                        selected = settings.calendarAltTwo,
                        onChange = { scope.launch { repository.setCalendarAltTwo(it) } },
                    )
                }
                item {
                    WeekendSetting(settings.calendarWeekend) {
                        scope.launch { repository.setCalendarWeekend(it) }
                    }
                }
                if (showsHijri) {
                    item {
                        SliderSetting(
                            R.string.tooldetail_calendar_hijri_title,
                            subtitle = stringResource(R.string.tooldetail_calendar_hijri_subtitle),
                            value = settings.hijriAdjustDays.toFloat(),
                            range = -2f..2f,
                            display = { days ->
                                val d = days.roundToInt()
                                if (d > 0) daysAheadFormat.format(d) else daysFormat.format(d)
                            },
                            info = stringResource(R.string.tooldetail_calendar_hijri_info),
                            default = SettingsDefaults.hijriAdjustDays.toFloat(),
                        ) { scope.launch { repository.setHijriAdjustDays(it.roundToInt()) } }
                    }
                }
            }
        }
        ToolbarTool.CAMERA -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_front_title,
                        stringResource(R.string.tooldetail_camera_front_subtitle),
                        settings.camera.preferFront,
                        default = SettingsDefaults.camera.preferFront,
                    ) { scope.launch { repository.setCameraPreferFront(it) } }
                }
                item {
                    val offLabel = stringResource(CommonR.string.common_off)
                    val secondsFormat = stringResource(R.string.values_seconds)
                    ChoiceSetting(
                        R.string.tooldetail_camera_timer_title,
                        subtitle = stringResource(R.string.tooldetail_camera_timer_subtitle),
                        options = listOf(
                            0 to offLabel,
                            3 to secondsFormat.format(3),
                            10 to secondsFormat.format(10),
                        ),
                        selected = settings.camera.timerSeconds,
                        info = stringResource(R.string.tooldetail_camera_timer_info),
                        default = SettingsDefaults.camera.timerSeconds,
                    ) { scope.launch { repository.setCameraTimerSeconds(it) } }
                }
                item {
                    val pxFormat = stringResource(R.string.values_pixels)
                    ChoiceSetting(
                        R.string.tooldetail_camera_resolution_title,
                        subtitle = stringResource(R.string.tooldetail_camera_resolution_subtitle),
                        options = listOf(
                            1000 to pxFormat.format(1000),
                            1600 to pxFormat.format(1600),
                            2400 to pxFormat.format(2400),
                            3200 to pxFormat.format(3200),
                        ),
                        selected = settings.camera.captureMaxPx,
                        info = stringResource(R.string.tooldetail_camera_resolution_info),
                        default = SettingsDefaults.camera.captureMaxPx,
                        detail = { px ->
                            ChoiceDetail(
                                stringResource(
                                    when {
                                        px <= 1000 -> R.string.tooldetail_camera_resolution_small_desc
                                        px <= 1600 -> R.string.tooldetail_camera_resolution_medium_desc
                                        px <= 2400 -> R.string.tooldetail_camera_resolution_large_desc
                                        else -> R.string.tooldetail_camera_resolution_max_desc
                                    },
                                ),
                            )
                        },
                    ) { scope.launch { repository.setCameraCaptureMaxPx(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_mirror_title,
                        stringResource(R.string.tooldetail_camera_mirror_subtitle),
                        settings.camera.mirrorFront,
                        info = stringResource(R.string.tooldetail_camera_mirror_info),
                        default = SettingsDefaults.camera.mirrorFront,
                    ) { scope.launch { repository.setCameraMirrorFront(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_fullframe_title,
                        stringResource(R.string.tooldetail_camera_fullframe_subtitle),
                        settings.camera.fullFrame,
                        info = stringResource(R.string.tooldetail_camera_fullframe_info),
                        default = SettingsDefaults.camera.fullFrame,
                    ) { scope.launch { repository.setCameraFullFrame(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_gallery_title,
                        stringResource(R.string.tooldetail_camera_gallery_subtitle),
                        settings.camera.saveToGallery,
                        info = stringResource(R.string.tooldetail_camera_gallery_info),
                        default = SettingsDefaults.camera.saveToGallery,
                    ) { scope.launch { repository.setCameraSaveToGallery(it) } }
                }
            }
            SettingsGroup(
                stringResource(R.string.tooldetail_camera_feedback_group),
                info = stringResource(R.string.tooldetail_camera_info),
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_shutter_title,
                        stringResource(R.string.tooldetail_camera_shutter_subtitle),
                        settings.camera.shutterSound,
                        default = SettingsDefaults.camera.shutterSound,
                    ) { scope.launch { repository.setCameraShutterSound(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_haptics_title,
                        stringResource(R.string.tooldetail_camera_haptics_subtitle),
                        settings.camera.haptics,
                        info = stringResource(R.string.tooldetail_camera_haptics_info),
                        default = SettingsDefaults.camera.haptics,
                    ) { scope.launch { repository.setCameraHaptics(it) } }
                }
            }
        }
        ToolbarTool.DICTIONARY -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_dictionary_info),
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_dictionary_auto_title,
                        stringResource(R.string.tooldetail_dictionary_auto_subtitle),
                        settings.dictionaryAutoLookup,
                        default = SettingsDefaults.dictionaryAutoLookup,
                    ) { scope.launch { repository.setDictionaryAutoLookup(it) } }
                }
            }
        }
        ToolbarTool.TEXT_EDIT -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                SliderSetting(
                    R.string.tooldetail_text_edit_repeat_title,
                    subtitle = stringResource(R.string.tooldetail_text_edit_repeat_subtitle),
                    value = settings.textEditing.repeatMs.toFloat(),
                    range = 30f..200f,
                    display = { msFormat.format(it.toInt()) },
                    default = SettingsDefaults.textEditing.repeatMs.toFloat(),
                ) { scope.launch { repository.setTextEditRepeatMs(it.toInt()) } }
            }
            // The panel's grid is a panel layout (issue #63), edited in the
            // layout editor's own controls: rows, widths, spans, alternates.
            item {
                val customPanels by repository.customPanelLayouts.collectAsStateWithLifecycle(emptyList())
                NavRow(
                    title = R.string.panel_layout_row_title,
                    subtitle = stringResource(R.string.panel_layout_row_subtitle),
                    value = stringResource(
                        if (customPanels.none { it.panel == PanelKind.TEXT_EDIT }) {
                            R.string.panel_layout_value_default
                        } else {
                            R.string.panel_layout_value_custom
                        },
                    ),
                ) { onNavigate("panel_edit/${PanelKind.TEXT_EDIT.name}") }
            }
        }
        ToolbarTool.TRACKPAD -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_trackpad_info),
            ) {
                item {
                    SliderSetting(
                        R.string.tooldetail_trackpad_step_x_title,
                        subtitle = stringResource(R.string.tooldetail_trackpad_step_x_subtitle),
                        value = settings.trackpad.stepXDp.toFloat(),
                        range = 4f..48f,
                        display = { dpFormat.format(it.toInt()) },
                        default = SettingsDefaults.trackpad.stepXDp.toFloat(),
                    ) { scope.launch { repository.setTrackpadStepXDp(it.toInt()) } }
                }
                item {
                    SliderSetting(
                        R.string.tooldetail_trackpad_step_y_title,
                        subtitle = stringResource(R.string.tooldetail_trackpad_step_y_subtitle),
                        value = settings.trackpad.stepYDp.toFloat(),
                        range = 8f..96f,
                        display = { dpFormat.format(it.toInt()) },
                        default = SettingsDefaults.trackpad.stepYDp.toFloat(),
                    ) { scope.launch { repository.setTrackpadStepYDp(it.toInt()) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_trackpad_hold_title,
                        stringResource(R.string.tooldetail_trackpad_hold_subtitle),
                        settings.trackpad.holdToOpen,
                        info = stringResource(R.string.tooldetail_trackpad_hold_info),
                        default = SettingsDefaults.trackpad.holdToOpen,
                    ) { scope.launch { repository.setTrackpadHoldToOpen(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_trackpad_taps_title,
                        stringResource(R.string.tooldetail_trackpad_taps_subtitle),
                        settings.trackpad.multiTap,
                        default = SettingsDefaults.trackpad.multiTap,
                    ) { scope.launch { repository.setTrackpadMultiTap(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_trackpad_haptics_title,
                        stringResource(R.string.tooldetail_trackpad_haptics_subtitle),
                        settings.trackpad.haptics,
                        default = SettingsDefaults.trackpad.haptics,
                    ) { scope.launch { repository.setTrackpadHaptics(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_trackpad_trail_title,
                        stringResource(R.string.tooldetail_trackpad_trail_subtitle),
                        settings.trackpad.trail,
                        default = SettingsDefaults.trackpad.trail,
                    ) { scope.launch { repository.setTrackpadTrail(it) } }
                }
                // The surface and the keys beside it are a panel layout (issue
                // #63), edited where the text-editing pad is.
                item {
                    val customPanels by repository.customPanelLayouts.collectAsStateWithLifecycle(emptyList())
                    NavRow(
                        title = R.string.panel_layout_row_title,
                        subtitle = stringResource(R.string.panel_layout_row_subtitle),
                        value = stringResource(
                            if (customPanels.none { it.panel == PanelKind.TRACKPAD }) {
                                R.string.panel_layout_value_default
                            } else {
                                R.string.panel_layout_value_custom
                            },
                        ),
                    ) { onNavigate("panel_edit/${PanelKind.TRACKPAD.name}") }
                }
            }
        }
        ToolbarTool.SELECT_MODE -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_select_mode_info),
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_select_mode_hold_title,
                        stringResource(R.string.tooldetail_select_mode_hold_subtitle),
                        settings.textEditing.selectionModeHold,
                        info = stringResource(R.string.tooldetail_select_mode_hold_info),
                        default = SettingsDefaults.textEditing.selectionModeHold,
                    ) { scope.launch { repository.setSelectionModeHold(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_select_mode_taps_title,
                        stringResource(R.string.tooldetail_select_mode_taps_subtitle),
                        settings.textEditing.selectionModeMultiTap,
                        info = stringResource(R.string.tooldetail_select_mode_taps_info),
                        default = SettingsDefaults.textEditing.selectionModeMultiTap,
                    ) { scope.launch { repository.setSelectionModeMultiTap(it) } }
                }
            }
        }
        // The caret movers, which are the only tools a hold repeats. Home, End
        // and the two select tools are not in the set — a second press of those
        // lands exactly where the first one did — so their pages stay plain.
        in HoldRepeatCursorTools -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_cursor_repeat_title,
                        stringResource(R.string.tooldetail_cursor_repeat_subtitle),
                        settings.textEditing.cursorToolsRepeatOnHold,
                        info = stringResource(R.string.tooldetail_cursor_repeat_info),
                        default = SettingsDefaults.textEditing.cursorToolsRepeatOnHold,
                    ) { scope.launch { repository.setCursorToolsRepeatOnHold(it) } }
                }
                item {
                    // This one is per tool, unlike the switch above: turning it
                    // on costs *this* tool the toolbox hold that opens its
                    // settings page, so it is answered a tool at a time.
                    ToggleSetting(
                        R.string.tooldetail_cursor_repeat_toolbox_title,
                        stringResource(R.string.tooldetail_cursor_repeat_toolbox_subtitle),
                        tool in settings.textEditing.toolboxRepeatTools,
                        info = stringResource(R.string.tooldetail_cursor_repeat_toolbox_info),
                        default = tool in SettingsDefaults.textEditing.toolboxRepeatTools,
                    ) { scope.launch { repository.setToolboxRepeat(tool, it) } }
                }
                // The speed is shared, so it shows while either surface repeats.
                if (settings.textEditing.cursorToolsRepeatOnHold ||
                    tool in settings.textEditing.toolboxRepeatTools
                ) {
                    item {
                        SliderSetting(
                            R.string.tooldetail_text_edit_repeat_title,
                            subtitle = stringResource(
                                R.string.tooldetail_cursor_repeat_speed_subtitle,
                            ),
                            value = settings.textEditing.repeatMs.toFloat(),
                            range = 30f..200f,
                            display = { msFormat.format(it.toInt()) },
                            default = SettingsDefaults.textEditing.repeatMs.toFloat(),
                        ) { scope.launch { repository.setTextEditRepeatMs(it.toInt()) } }
                    }
                }
            }
        }
        ToolbarTool.NUMPAD -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_numpad_calc_title,
                    stringResource(R.string.tooldetail_numpad_calc_subtitle),
                    settings.numpadCalculatorLayout,
                    default = SettingsDefaults.numpadCalculatorLayout,
                ) { scope.launch { repository.setNumpadCalculatorLayout(it) } }
            }
        }
        ToolbarTool.INCOGNITO -> {
            SettingsGroup(stringResource(R.string.tooldetail_incognito_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_incognito_learning_title,
                        stringResource(R.string.tooldetail_incognito_learning_subtitle),
                        settings.incognitoPausesLearning,
                        default = SettingsDefaults.incognitoPausesLearning,
                    ) { scope.launch { repository.setIncognitoPausesLearning(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_incognito_clipboard_title,
                        stringResource(R.string.tooldetail_incognito_clipboard_subtitle),
                        settings.incognitoPausesClipboard,
                        default = SettingsDefaults.incognitoPausesClipboard,
                    ) { scope.launch { repository.setIncognitoPausesClipboard(it) } }
                }
                item {
                    NavRow(
                        R.string.tooldetail_incognito_auto_nav_title,
                        stringResource(R.string.tooldetail_incognito_auto_nav_subtitle),
                        value = stringResource(if (settings.autoIncognito) CommonR.string.common_on else CommonR.string.common_off),
                        route = "privacy",
                        onClick = { onNavigate("privacy") },
                    )
                }
            }
        }
        ToolbarTool.POWER_SAVING -> {
            val ps = settings.powerSaving
            SettingsGroup(stringResource(R.string.tooldetail_power_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_now_title,
                        stringResource(R.string.tooldetail_power_now_subtitle),
                        ps.manual,
                        info = stringResource(R.string.tooldetail_power_now_info),
                        default = SettingsDefaults.powerSaving.manual,
                    ) { scope.launch { repository.setPowerSavingManual(it) } }
                }
                item {
                    ChoiceSetting(
                        R.string.tooldetail_power_trigger_title,
                        subtitle = stringResource(R.string.tooldetail_power_trigger_subtitle),
                        info = stringResource(R.string.tooldetail_power_trigger_info),
                        options = PowerSavingTrigger.entries.map { it to stringResource(it.labelRes) },
                        selected = ps.trigger,
                        default = SettingsDefaults.powerSaving.trigger,
                        detail = { trigger -> ChoiceDetail(stringResource(powerTriggerDescRes(trigger))) },
                    ) { scope.launch { repository.setPowerSavingTrigger(it) } }
                }
                if (ps.trigger == PowerSavingTrigger.LOW_BATTERY ||
                    ps.trigger == PowerSavingTrigger.EITHER
                ) {
                    item {
                        SliderSetting(
                            R.string.tooldetail_power_battery_title,
                            subtitle = stringResource(R.string.tooldetail_power_battery_subtitle),
                            value = ps.batteryPercent.toFloat(),
                            range = 5f..50f,
                            display = { percentFormat.format(it.toInt()) },
                            default = SettingsDefaults.powerSaving.batteryPercent.toFloat(),
                        ) { scope.launch { repository.setPowerSavingBatteryPercent(it.toInt()) } }
                    }
                }
                if (ps.trigger != PowerSavingTrigger.OFF) {
                    item {
                        ToggleSetting(
                            R.string.tooldetail_power_charging_title,
                            stringResource(R.string.tooldetail_power_charging_subtitle),
                            ps.offWhileCharging,
                            info = stringResource(R.string.tooldetail_power_charging_info),
                            default = SettingsDefaults.powerSaving.offWhileCharging,
                        ) { scope.launch { repository.setPowerSavingOffWhileCharging(it) } }
                    }
                }
            }
            SettingsGroup(
                stringResource(R.string.tooldetail_power_drop_group),
                info = stringResource(R.string.tooldetail_power_info),
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_haptics_title,
                        stringResource(R.string.tooldetail_power_drop_haptics_subtitle),
                        ps.dropHaptics,
                        default = SettingsDefaults.powerSaving.dropHaptics,
                    ) { scope.launch { repository.setPowerSavingDropHaptics(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_sound_title,
                        stringResource(R.string.tooldetail_power_drop_sound_subtitle),
                        ps.dropKeySound,
                        default = SettingsDefaults.powerSaving.dropKeySound,
                    ) { scope.launch { repository.setPowerSavingDropKeySound(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_anim_title,
                        stringResource(R.string.tooldetail_power_drop_anim_subtitle),
                        ps.dropAnimations,
                        default = SettingsDefaults.powerSaving.dropAnimations,
                    ) { scope.launch { repository.setPowerSavingDropAnimations(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_trail_title,
                        stringResource(R.string.tooldetail_power_drop_trail_subtitle),
                        ps.dropGlideTrail,
                        info = stringResource(R.string.tooldetail_power_drop_trail_info),
                        default = SettingsDefaults.powerSaving.dropGlideTrail,
                    ) { scope.launch { repository.setPowerSavingDropGlideTrail(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_popup_title,
                        stringResource(R.string.tooldetail_power_drop_popup_subtitle),
                        ps.dropKeyPopup,
                        default = SettingsDefaults.powerSaving.dropKeyPopup,
                    ) { scope.launch { repository.setPowerSavingDropKeyPopup(it) } }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_power_drop_helpers_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_glide_title,
                        stringResource(R.string.tooldetail_power_drop_glide_subtitle),
                        ps.dropGestureTyping,
                        info = stringResource(R.string.tooldetail_power_drop_glide_info),
                        default = SettingsDefaults.powerSaving.dropGestureTyping,
                    ) { scope.launch { repository.setPowerSavingDropGestureTyping(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_emoji_title,
                        stringResource(R.string.tooldetail_power_drop_emoji_subtitle),
                        ps.dropEmojiPrediction,
                        default = SettingsDefaults.powerSaving.dropEmojiPrediction,
                    ) { scope.launch { repository.setPowerSavingDropEmojiPrediction(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_chips_title,
                        stringResource(R.string.tooldetail_power_drop_chips_subtitle),
                        ps.dropSmartChips,
                        default = SettingsDefaults.powerSaving.dropSmartChips,
                    ) { scope.launch { repository.setPowerSavingDropSmartChips(it) } }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_power_drop_background_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_network_title,
                        stringResource(R.string.tooldetail_power_drop_network_subtitle),
                        ps.dropBackgroundNetwork,
                        info = stringResource(R.string.tooldetail_power_drop_network_info),
                        default = SettingsDefaults.powerSaving.dropBackgroundNetwork,
                    ) { scope.launch { repository.setPowerSavingDropBackgroundNetwork(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_screenshot_title,
                        stringResource(R.string.tooldetail_power_drop_screenshot_subtitle),
                        ps.dropScreenshotWatch,
                        default = SettingsDefaults.powerSaving.dropScreenshotWatch,
                    ) { scope.launch { repository.setPowerSavingDropScreenshotWatch(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_models_title,
                        stringResource(R.string.tooldetail_power_drop_models_subtitle),
                        ps.dropOnDeviceModels,
                        info = stringResource(R.string.tooldetail_power_drop_models_info),
                        default = SettingsDefaults.powerSaving.dropOnDeviceModels,
                    ) { scope.launch { repository.setPowerSavingDropOnDeviceModels(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_stats_title,
                        stringResource(R.string.tooldetail_power_drop_stats_subtitle),
                        ps.dropTypingStats,
                        default = SettingsDefaults.powerSaving.dropTypingStats,
                    ) { scope.launch { repository.setPowerSavingDropTypingStats(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_media_pin_title,
                        stringResource(R.string.tooldetail_power_drop_media_pin_subtitle),
                        ps.dropMediaPin,
                        default = SettingsDefaults.powerSaving.dropMediaPin,
                    ) { scope.launch { repository.setPowerSavingDropMediaPin(it) } }
                }
            }
        }
        ToolbarTool.AUTOCORRECT -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_autocorrect_title,
                    stringResource(R.string.tooldetail_autocorrect_subtitle),
                    settings.autocorrect,
                    default = SettingsDefaults.autocorrect,
                ) { scope.launch { repository.setAutocorrect(it) } }
            }
            item {
                NavRow(
                    R.string.tooldetail_typing_nav_title,
                    stringResource(R.string.tooldetail_typing_nav_subtitle),
                    onClick = { onNavigate("typing") },
                )
            }
        }
        ToolbarTool.FANCY -> {
            val behavior = settings.layoutBehavior
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_fancy_info),
            ) {
                item {
                    // "Follow the strip" is the empty pick, so the tool can go
                    // back to starting from whatever style was last used.
                    val follow = stringResource(R.string.tooldetail_fancy_style_follow)
                    ChoiceSetting(
                        R.string.tooldetail_fancy_style_title,
                        subtitle = stringResource(R.string.tooldetail_fancy_style_subtitle),
                        info = stringResource(R.string.tooldetail_fancy_style_info),
                        options = listOf<Pair<String?, String>>(null to follow) +
                            FancyStyles.all.map { it.id to it.sample },
                        // No reset control: this row's default *is* the "follow
                        // the strip" option, which is already one press away in
                        // the list, and a null default is how [ChoiceSetting]
                        // spells "no one right answer".
                        selected = behavior.fancyToolStyleId
                            ?.takeIf { FancyStyles.byId(it) != null },
                    ) { scope.launch { repository.setFancyToolStyle(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_fancy_keep_title,
                        stringResource(R.string.tooldetail_fancy_keep_subtitle),
                        behavior.fancyToolKeepsLanguage,
                        info = stringResource(R.string.tooldetail_fancy_keep_info),
                        default = SettingsDefaults.layoutBehavior.fancyToolKeepsLanguage,
                    ) { scope.launch { repository.setFancyToolKeepsLanguage(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_fancy_auto_off_title,
                        stringResource(R.string.tooldetail_fancy_auto_off_subtitle),
                        behavior.fancyToolAutoOff,
                        info = stringResource(R.string.tooldetail_fancy_auto_off_info),
                        default = SettingsDefaults.layoutBehavior.fancyToolAutoOff,
                    ) { scope.launch { repository.setFancyToolAutoOff(it) } }
                }
                item {
                    NavRow(
                        R.string.tooldetail_fancy_language_nav_title,
                        stringResource(R.string.tooldetail_fancy_language_nav_subtitle),
                        onClick = { onNavigate("language/${FancyStyles.LANG_ID}") },
                    )
                }
            }
        }
        ToolbarTool.CUSTOM_LAYOUT -> {
            val behavior = settings.layoutBehavior
            val secondaries = com.wasimaster.wmkeyboard.core.layout.secondaryLayouts(settings.customLayouts)
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_custom_layout_info),
            ) {
                item {
                    // "The first one" is the empty pick, so the tool works before
                    // this page has ever been visited and keeps working when the
                    // picked layout is deleted.
                    val first = stringResource(R.string.tooldetail_custom_layout_first)
                    ChoiceSetting(
                        R.string.tooldetail_custom_layout_layout_title,
                        subtitle = stringResource(R.string.tooldetail_custom_layout_layout_subtitle),
                        info = stringResource(R.string.tooldetail_custom_layout_layout_info),
                        options = listOf<Pair<String?, String>>(null to first) +
                            secondaries.map { it.id to it.name },
                        selected = behavior.customLayoutToolId
                            ?.takeIf { id -> secondaries.any { it.id == id } },
                    ) { scope.launch { repository.setCustomLayoutToolLayout(it) } }
                }
                item {
                    NavRow(
                        R.string.tooldetail_custom_layout_keymaps_nav_title,
                        stringResource(R.string.tooldetail_custom_layout_keymaps_nav_subtitle),
                        onClick = { onNavigate("keymaps") },
                    )
                }
            }
        }
        ToolbarTool.SOUND_HAPTICS -> SettingsGroup(stringResource(R.string.hardware_sound_group_title)) {
            // The one master switch the tool toggles, then the page that owns
            // the rest of the sound and haptics settings.
            item {
                ToggleSetting(
                    R.string.hardware_sound_key_title,
                    stringResource(R.string.hardware_sound_key_subtitle),
                    settings.keySound,
                    default = SettingsDefaults.keySound,
                ) { scope.launch { repository.setKeySound(it) } }
            }
            item {
                NavRow(
                    R.string.tooldetail_keypress_nav_title,
                    stringResource(R.string.tooldetail_keypress_nav_subtitle),
                    route = "keypress/haptics",
                    onClick = { onNavigate("keypress/haptics") },
                )
            }
        }
        ToolbarTool.HANDWRITING -> {
            SettingsGroup(stringResource(R.string.tooldetail_handwriting_input_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_handwriting_stylus_title,
                        stringResource(R.string.tooldetail_handwriting_stylus_subtitle),
                        settings.handwritingStylusOnly,
                        info = stringResource(R.string.tooldetail_handwriting_stylus_info),
                        default = SettingsDefaults.handwritingStylusOnly,
                    ) { scope.launch { repository.setHandwritingStylusOnly(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_handwriting_auto_space_title,
                        stringResource(R.string.tooldetail_handwriting_auto_space_subtitle),
                        settings.handwritingAutoSpace,
                        default = SettingsDefaults.handwritingAutoSpace,
                    ) { scope.launch { repository.setHandwritingAutoSpace(it) } }
                }
                item {
                    SliderSetting(
                        R.string.tooldetail_handwriting_pause_title,
                        subtitle = stringResource(R.string.tooldetail_handwriting_pause_subtitle),
                        value = settings.handwritingCommitDelayMs.toFloat(),
                        range = 300f..2000f,
                        display = { msFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.tooldetail_handwriting_pause_info),
                        default = SettingsDefaults.handwritingCommitDelayMs.toFloat(),
                    ) { scope.launch { repository.setHandwritingCommitDelayMs(it.roundToInt()) } }
                }
            }
            SectionHeader(
                stringResource(R.string.tooldetail_handwriting_models_header),
                info = stringResource(R.string.tooldetail_handwriting_models_info),
            )
            HandwritingModelManager(settings)
            SettingsGroup {
                item {
                    NavRow(
                        R.string.tooldetail_handwriting_languages_title,
                        stringResource(R.string.tooldetail_handwriting_languages_subtitle),
                        onClick = { onNavigate("languages") },
                    )
                }
            }
        }
        ToolbarTool.THEMES -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                NavRow(
                    R.string.tooldetail_themes_nav_title,
                    stringResource(R.string.tooldetail_themes_nav_subtitle),
                    onClick = { onNavigate("themes") },
                )
            }
        }
        ToolbarTool.ONE_HANDED -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                NavRow(
                    R.string.tooldetail_layout_nav_title,
                    stringResource(R.string.tooldetail_layout_nav_one_handed_subtitle),
                    route = "layout/onehanded",
                    onClick = { onNavigate("layout/onehanded") },
                )
            }
        }
        ToolbarTool.TRANSLATE -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item { TranslateLanguageSetting(repository, settings) }
            }
            SettingsGroup(
                stringResource(R.string.tooldetail_translate_key_group),
                info = stringResource(R.string.tooldetail_translate_info),
            ) {
                item {
                    ApiKeyField(
                        label = stringResource(R.string.tooldetail_translate_key_label),
                        value = settings.translateApiKey,
                        builtInAvailable = ToolApiKeys.builtInTranslate,
                        emptyHint = stringResource(R.string.tooldetail_translate_key_hint),
                    ) { repository.setTranslateApiKey(it) }
                }
            }
        }
        ToolbarTool.GIF, ToolbarTool.STICKER -> {
            if (tool == ToolbarTool.STICKER) {
                SettingsGroup(stringResource(R.string.tooldetail_sticker_packs_group)) {
                    item {
                        NavRow(
                            R.string.tooldetail_sticker_packs_title,
                            stringResource(R.string.tooldetail_sticker_packs_subtitle),
                            route = "sticker_packs",
                            onClick = { onNavigate("sticker_packs") },
                        )
                    }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_media_layout_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_media_full_bleed_title,
                        stringResource(R.string.tooldetail_media_full_bleed_subtitle),
                        settings.mediaFullBleed,
                        info = stringResource(R.string.tooldetail_media_full_bleed_info),
                        default = SettingsDefaults.mediaFullBleed,
                    ) { scope.launch { repository.setMediaFullBleed(it) } }
                }
            }
            SettingsGroup(
                stringResource(R.string.tooldetail_media_keys_group),
                info = stringResource(R.string.tooldetail_media_info),
            ) {
                item {
                    ApiKeyField(
                        label = stringResource(R.string.tooldetail_media_klipy_label),
                        value = settings.klipyApiKey,
                        builtInAvailable = ToolApiKeys.builtInKlipy,
                        emptyHint = stringResource(R.string.tooldetail_media_klipy_hint),
                    ) {
                        repository.setKlipyApiKey(it)
                        // Categories were fetched with the old key; they are
                        // not this key's answer.
                        MediaCategoryCache.clear()
                    }
                }
                item {
                    ApiKeyField(
                        label = stringResource(R.string.tooldetail_media_giphy_label),
                        value = settings.giphyApiKey,
                        builtInAvailable = ToolApiKeys.builtInGiphy,
                        emptyHint = stringResource(R.string.tooldetail_media_giphy_hint),
                    ) {
                        repository.setGiphyApiKey(it)
                        MediaCategoryCache.clear()
                    }
                }
            }
            // Resolved out here: the group builder lambda is not composable.
            val stickerOption = stringResource(R.string.tooldetail_media_send_sticker_option)
            val imageOption = stringResource(R.string.tooldetail_media_send_image_option)
            // One send mode per tool: a sticker tapped in the sticker panel is
            // sent with the sticker setting and a GIF tapped in the GIF panel
            // with the GIF one (see WMKeyboardService.onGifSelect), so showing
            // both on both pages only invited the user to change the one that
            // could not affect the panel they came from.
            SettingsGroup(stringResource(R.string.tooldetail_media_sending_group)) {
                item {
                    if (tool == ToolbarTool.STICKER) {
                        ChoiceSetting(
                            title = R.string.tooldetail_media_sticker_send_title,
                            subtitle = stringResource(R.string.tooldetail_media_sticker_send_subtitle),
                            info = stringResource(R.string.tooldetail_media_sticker_send_info),
                            options = listOf(
                                MediaSendMode.STICKER to stickerOption,
                                MediaSendMode.IMAGE to imageOption,
                            ),
                            selected = settings.stickerSendMode,
                            default = SettingsDefaults.stickerSendMode,
                            detail = { mode ->
                                ChoiceDetail(
                                    stringResource(
                                        if (mode == MediaSendMode.STICKER) {
                                            R.string.tooldetail_media_sticker_send_sticker_desc
                                        } else {
                                            R.string.tooldetail_media_sticker_send_image_desc
                                        },
                                    ),
                                )
                            },
                        ) { scope.launch { repository.setStickerSendMode(it) } }
                    } else {
                        ChoiceSetting(
                            title = R.string.tooldetail_media_gif_send_title,
                            subtitle = stringResource(R.string.tooldetail_media_gif_send_subtitle),
                            info = stringResource(R.string.tooldetail_media_gif_send_info),
                            options = listOf(
                                MediaSendMode.IMAGE to imageOption,
                                MediaSendMode.STICKER to stickerOption,
                            ),
                            selected = settings.gifSendMode,
                            default = SettingsDefaults.gifSendMode,
                            detail = { mode ->
                                ChoiceDetail(
                                    stringResource(
                                        if (mode == MediaSendMode.STICKER) {
                                            R.string.tooldetail_media_gif_send_sticker_desc
                                        } else {
                                            R.string.tooldetail_media_gif_send_image_desc
                                        },
                                    ),
                                )
                            },
                        ) { scope.launch { repository.setGifSendMode(it) } }
                    }
                }
            }
            SectionHeader(stringResource(R.string.tooldetail_media_sources_header))
            ChoiceControl(
                options = GifSourceMode.entries.map { mode ->
                    mode to when (mode) {
                        GifSourceMode.TABS -> stringResource(R.string.tooldetail_media_source_tabs)
                        GifSourceMode.MIX -> stringResource(R.string.tooldetail_media_source_mixed)
                    }
                },
                selected = settings.gifSourceMode,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                detail = { mode -> ChoiceDetail(stringResource(gifSourceDescRes(mode))) },
            ) { mode -> scope.launch { repository.setGifSourceMode(mode) } }
            SectionHeader(
                stringResource(R.string.tooldetail_media_filter_header),
                info = stringResource(R.string.tooldetail_media_filter_info),
            )
            ChoiceControl(
                options = GifContentFilter.entries.map { filter ->
                    filter to when (filter) {
                        GifContentFilter.OFF -> stringResource(CommonR.string.common_off)
                        GifContentFilter.LOW -> stringResource(R.string.tooldetail_media_filter_low)
                        GifContentFilter.MEDIUM ->
                            stringResource(R.string.tooldetail_media_filter_medium)
                        GifContentFilter.HIGH -> stringResource(R.string.tooldetail_media_filter_high)
                    }
                },
                selected = settings.gifContentFilter,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                detail = { filter -> ChoiceDetail(stringResource(gifFilterDescRes(filter))) },
            ) { filter -> scope.launch { repository.setGifContentFilter(filter) } }
            SettingsGroup(stringResource(R.string.tooldetail_media_limit_group)) {
                item {
                    SliderSetting(
                        R.string.tooldetail_media_limit_title,
                        subtitle = stringResource(R.string.tooldetail_media_limit_subtitle),
                        value = settings.gifResultLimit.toFloat(),
                        range = 6f..48f,
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.gifResultLimit.toFloat(),
                    ) { scope.launch { repository.setGifResultLimit(it.roundToInt()) } }
                }
            }
        }
        ToolbarTool.WEB_SEARCH, ToolbarTool.IMAGE_SEARCH -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_search_group),
                info = stringResource(R.string.tooldetail_search_info),
            ) {
                item {
                    ApiKeyField(
                        label = stringResource(R.string.tooldetail_search_key_label),
                        value = settings.braveApiKey,
                        builtInAvailable = ToolApiKeys.builtInBrave,
                        emptyHint = stringResource(R.string.tooldetail_search_key_hint),
                    ) { repository.setBraveApiKey(it) }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_search_results_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_search_safe_title,
                        stringResource(R.string.tooldetail_search_safe_subtitle),
                        settings.searchSafe,
                        default = SettingsDefaults.searchSafe,
                    ) { scope.launch { repository.setSearchSafe(it) } }
                }
                item {
                    SliderSetting(
                        R.string.tooldetail_search_count_title,
                        subtitle = stringResource(R.string.tooldetail_search_count_subtitle),
                        value = settings.searchResultCount.toFloat(),
                        range = 1f..10f,
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.searchResultCount.toFloat(),
                    ) { scope.launch { repository.setSearchResultCount(it.roundToInt()) } }
                }
                if (tool == ToolbarTool.IMAGE_SEARCH) {
                    item {
                        SliderSetting(
                            R.string.tooldetail_image_columns_title,
                            subtitle = stringResource(R.string.tooldetail_image_columns_subtitle),
                            value = settings.emoji.mediaGridColumns.toFloat(),
                            range = 2f..5f,
                            display = { numberFormat.format(it.roundToInt()) },
                            info = stringResource(R.string.tooldetail_image_columns_info),
                            default = SettingsDefaults.emoji.mediaGridColumns.toFloat(),
                        ) { scope.launch { repository.setMediaGridColumns(it.roundToInt()) } }
                    }
                }
            }
        }
        ToolbarTool.OCR -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_ocr_info),
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_ocr_select_all_title,
                        stringResource(R.string.tooldetail_ocr_select_all_subtitle),
                        settings.ocrAutoSelectWords,
                        default = SettingsDefaults.ocrAutoSelectWords,
                    ) { scope.launch { repository.setOcrAutoSelectWords(it) } }
                }
            }
        }
        ToolbarTool.QR_SCAN -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_qr_scan_info),
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_qr_scan_auto_title,
                        stringResource(R.string.tooldetail_qr_scan_auto_subtitle),
                        settings.qrScanAutoInsert,
                        default = SettingsDefaults.qrScanAutoInsert,
                    ) { scope.launch { repository.setQrScanAutoInsert(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_qr_scan_haptics_title,
                        stringResource(R.string.tooldetail_qr_scan_haptics_subtitle),
                        settings.qrScanHaptics,
                        default = SettingsDefaults.qrScanHaptics,
                    ) { scope.launch { repository.setQrScanHaptics(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_qr_scan_preview_title,
                        stringResource(R.string.tooldetail_qr_scan_preview_subtitle),
                        settings.qrScanLinkPreviews,
                        default = SettingsDefaults.qrScanLinkPreviews,
                    ) { scope.launch { repository.setQrScanLinkPreviews(it) } }
                }
            }
        }
        ToolbarTool.DOC_SCAN -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_doc_scan_info),
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_doc_scan_gallery_title,
                        stringResource(R.string.tooldetail_doc_scan_gallery_subtitle),
                        settings.docScanSaveToGallery,
                        default = SettingsDefaults.docScanSaveToGallery,
                    ) { scope.launch { repository.setDocScanSaveToGallery(it) } }
                }
            }
        }
        ToolbarTool.VOICE -> SettingsGroup(stringResource(R.string.tooldetail_voice_group)) {
            item {
                NavRow(
                    R.string.tooldetail_voice_all_title,
                    stringResource(R.string.tooldetail_voice_all_subtitle),
                    onClick = { onNavigate("voice") },
                )
            }
        }
        ToolbarTool.GRAMMAR -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ChoiceSetting(
                        R.string.tooldetail_grammar_dialect_title,
                        subtitle = stringResource(R.string.tooldetail_grammar_dialect_subtitle),
                        options = GrammarDialect.entries.map { it to stringResource(it.labelRes) },
                        selected = settings.grammarDialect,
                        default = SettingsDefaults.grammarDialect,
                    ) { scope.launch { repository.setGrammarDialect(it) } }
                }
                item {
                    SliderSetting(
                        R.string.tooldetail_grammar_debounce_title,
                        subtitle = stringResource(R.string.tooldetail_grammar_debounce_subtitle),
                        value = settings.grammarDebounceMs.toFloat(),
                        range = 100f..1500f,
                        display = { msFormat.format(it.toInt()) },
                        default = SettingsDefaults.grammarDebounceMs.toFloat(),
                    ) { scope.launch { repository.setGrammarDebounceMs(it.toInt()) } }
                }
            }
            if (BuildConfig.ENABLE_GRAMMAR) {
                val context = LocalContext.current
                SettingsGroup(
                    stringResource(R.string.tooldetail_grammar_system_group),
                    info = stringResource(R.string.tooldetail_grammar_info),
                ) {
                    item {
                        NavRow(
                            R.string.tooldetail_grammar_system_title,
                            stringResource(R.string.tooldetail_grammar_system_subtitle),
                            onClick = { openSpellCheckerSettings(context) },
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        item {
                            ToggleSetting(
                                stringResource(
                                    R.string.tooldetail_grammar_no_suggestions_title,
                                ),
                                stringResource(
                                    R.string.tooldetail_grammar_no_suggestions_subtitle,
                                ),
                                settings.spellCheckerNoSuggestions,
                                default = SettingsDefaults.spellCheckerNoSuggestions,
                            ) {
                                scope.launch {
                                    repository.setSpellCheckerNoSuggestions(it)
                                }
                            }
                        }
                    }
                }
            }
        }
        ToolbarTool.WIKIPEDIA -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_wiki_info),
            ) {
                item {
                    TextFieldSetting(
                        label = stringResource(R.string.tooldetail_wiki_language_label),
                        value = settings.wikiLanguage,
                        hint = stringResource(R.string.tooldetail_wiki_language_hint),
                        default = SettingsDefaults.wikiLanguage,
                    ) { repository.setWikiLanguage(it) }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_wiki_markdown_title,
                        stringResource(R.string.tooldetail_wiki_markdown_subtitle),
                        settings.wikiLinksMarkdown,
                        default = SettingsDefaults.wikiLinksMarkdown,
                    ) { scope.launch { repository.setWikiLinksMarkdown(it) } }
                }
                item {
                    val linksFormat = stringResource(R.string.values_number)
                    SliderSetting(
                        R.string.tooldetail_wiki_link_limit_title,
                        subtitle = stringResource(R.string.tooldetail_wiki_link_limit_subtitle),
                        value = settings.toolLimits.wikiLinkLimit.toFloat(),
                        range = 50f..500f,
                        display = { linksFormat.format((it / 10f).roundToInt() * 10) },
                        info = stringResource(R.string.tooldetail_wiki_link_limit_info),
                        default = SettingsDefaults.toolLimits.wikiLinkLimit.toFloat(),
                    ) { picked ->
                        scope.launch { repository.setWikiLinkLimit((picked / 10f).roundToInt() * 10) }
                    }
                }
            }
        }
        ToolbarTool.SYMBOLS -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_symbols_recents_group),
                info = stringResource(R.string.tooldetail_symbols_info),
            ) {
                item {
                    val remembered = settings.symbolRecents.size
                    WmRow(
                        title = stringResource(R.string.tooldetail_symbols_clear_title),
                        subtitle = if (remembered == 0) {
                            stringResource(R.string.tooldetail_symbols_clear_empty)
                        } else {
                            pluralStringResource(
                                R.plurals.tooldetail_symbols_remembered_count,
                                remembered,
                                remembered,
                            )
                        },
                        onClick = { scope.launch { repository.clearSymbolRecents() } },
                    )
                }
            }
        }
        ToolbarTool.CALCULATOR -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                NavRow(
                    R.string.tooldetail_chips_nav_title,
                    stringResource(R.string.tooldetail_chips_nav_subtitle),
                    value = stringResource(if (settings.smartCalc) CommonR.string.common_on else CommonR.string.common_off),
                    route = "typing/chips",
                    onClick = { onNavigate("typing/chips") },
                )
            }
            item {
                ToggleSetting(
                    R.string.tooldetail_calc_degrees_title,
                    stringResource(R.string.tooldetail_calc_degrees_subtitle),
                    settings.calcDegrees,
                    default = SettingsDefaults.calcDegrees,
                ) { scope.launch { repository.setCalcDegrees(it) } }
            }
            item {
                SliderSetting(
                    R.string.tooldetail_calc_precision_title,
                    subtitle = stringResource(R.string.tooldetail_calc_precision_subtitle),
                    value = settings.calcPrecision.toFloat(),
                    range = 0f..12f,
                    display = { numberFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.calcPrecision.toFloat(),
                ) { scope.launch { repository.setCalcPrecision(it.roundToInt()) } }
            }
        }
        ToolbarTool.UNIT_CONVERT -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_units_info),
            ) {
                item {
                    NavRow(
                        R.string.tooldetail_chips_nav_title,
                        stringResource(R.string.tooldetail_chips_nav_subtitle),
                        value = stringResource(if (settings.smartUnits) CommonR.string.common_on else CommonR.string.common_off),
                        route = "typing/chips",
                        onClick = { onNavigate("typing/chips") },
                    )
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_units_compound_title,
                        stringResource(R.string.tooldetail_units_compound_subtitle),
                        settings.compoundUnits,
                        info = stringResource(R.string.tooldetail_units_compound_info),
                        default = SettingsDefaults.compoundUnits,
                    ) { scope.launch { repository.setCompoundUnits(it) } }
                }
            }
        }
        ToolbarTool.CURRENCY -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_currency_info),
            ) {
                item {
                    NavRow(
                        R.string.tooldetail_chips_nav_title,
                        stringResource(R.string.tooldetail_chips_nav_subtitle),
                        value = stringResource(if (settings.smartCurrency) CommonR.string.common_on else CommonR.string.common_off),
                        route = "typing/chips",
                        onClick = { onNavigate("typing/chips") },
                    )
                }
                item {
                    SliderSetting(
                        R.string.tooldetail_currency_decimals_title,
                        subtitle = stringResource(R.string.tooldetail_currency_decimals_subtitle),
                        value = settings.currencyDecimals.toFloat(),
                        range = 0f..6f,
                        display = { numberFormat.format(it.toInt()) },
                        default = SettingsDefaults.currencyDecimals.toFloat(),
                    ) { scope.launch { repository.setCurrencyDecimals(it.toInt()) } }
                }
                item {
                    SliderSetting(
                        R.string.tooldetail_currency_refresh_title,
                        subtitle = stringResource(R.string.tooldetail_currency_refresh_subtitle),
                        value = settings.currencyCacheHours.toFloat(),
                        range = 1f..48f,
                        display = { hoursFormat.format(it.toInt()) },
                        info = stringResource(R.string.tooldetail_currency_refresh_info),
                        default = SettingsDefaults.currencyCacheHours.toFloat(),
                    ) { scope.launch { repository.setCurrencyCacheHours(it.toInt()) } }
                }
                item {
                    RateSourceSetting(
                        title = R.string.tooldetail_currency_source_title,
                        subtitle = stringResource(R.string.tooldetail_currency_source_subtitle),
                        providers = settings.rateSources.fiatProviders,
                        defaultProviders = SettingsDefaults.rateSources.fiatProviders,
                        candidates = CurrencyClient.Provider.entries.filter { it.fiat },
                    ) { scope.launch { repository.setFiatProviders(it) } }
                }
            }
            SettingsGroup(
                stringResource(R.string.tooldetail_crypto_group_title),
                info = stringResource(R.string.tooldetail_crypto_info),
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_crypto_enable_title,
                        stringResource(R.string.tooldetail_crypto_enable_subtitle),
                        settings.rateSources.cryptoEnabled,
                        info = stringResource(R.string.tooldetail_crypto_enable_info),
                        default = SettingsDefaults.rateSources.cryptoEnabled,
                    ) { scope.launch { repository.setCryptoEnabled(it) } }
                }
                if (settings.rateSources.cryptoEnabled) {
                    item {
                        val auto = stringResource(R.string.tooldetail_crypto_decimals_auto)
                        SliderSetting(
                            R.string.tooldetail_crypto_decimals_title,
                            subtitle = stringResource(R.string.tooldetail_crypto_decimals_subtitle),
                            value = settings.rateSources.cryptoDecimals.toFloat(),
                            range = 0f..12f,
                            display = {
                                if (it.toInt() == 0) auto else numberFormat.format(it.toInt())
                            },
                            default = SettingsDefaults.rateSources.cryptoDecimals.toFloat(),
                        ) { scope.launch { repository.setCryptoDecimals(it.toInt()) } }
                    }
                    item {
                        SliderSetting(
                            R.string.tooldetail_crypto_refresh_title,
                            subtitle = stringResource(R.string.tooldetail_crypto_refresh_subtitle),
                            value = settings.rateSources.cryptoCacheMinutes.toFloat(),
                            range = 1f..60f,
                            display = { minutesFormat.format(it.toInt()) },
                            default = SettingsDefaults.rateSources.cryptoCacheMinutes.toFloat(),
                        ) { scope.launch { repository.setCryptoCacheMinutes(it.toInt()) } }
                    }
                    item {
                        RateSourceSetting(
                            title = R.string.tooldetail_crypto_source_title,
                            subtitle = stringResource(R.string.tooldetail_crypto_source_subtitle),
                            providers = settings.rateSources.cryptoProviders,
                            defaultProviders = SettingsDefaults.rateSources.cryptoProviders,
                            candidates = CurrencyClient.Provider.entries.filter { it.crypto },
                        ) { scope.launch { repository.setCryptoProviders(it) } }
                    }
                    item { CryptoCoinPicker(repository, settings) }
                }
            }
        }
        ToolbarTool.QR_GEN -> {
            val qrImageOption = stringResource(R.string.tooldetail_media_send_image_option)
            val qrStickerOption = stringResource(R.string.tooldetail_media_send_sticker_option)
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_qr_gen_ecc_info),
            ) {
                item {
                    SliderSetting(
                        R.string.tooldetail_qr_gen_size_title,
                        subtitle = stringResource(R.string.tooldetail_qr_gen_size_subtitle),
                        value = settings.qrSizePx.toFloat(),
                        range = 256f..2048f,
                        display = { pixelsFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.qrSizePx.toFloat(),
                    ) { scope.launch { repository.setQrSizePx(it.roundToInt()) } }
                }
                item {
                    ChoiceSetting(
                        title = R.string.tooldetail_qr_gen_send_title,
                        subtitle = stringResource(R.string.tooldetail_qr_gen_send_subtitle),
                        info = stringResource(R.string.tooldetail_qr_gen_send_info),
                        options = listOf(
                            MediaSendMode.IMAGE to qrImageOption,
                            MediaSendMode.STICKER to qrStickerOption,
                        ),
                        selected = settings.qrSendMode,
                        default = SettingsDefaults.qrSendMode,
                        detail = { mode ->
                            ChoiceDetail(
                                stringResource(
                                    if (mode == MediaSendMode.STICKER) {
                                        R.string.tooldetail_qr_gen_send_sticker_desc
                                    } else {
                                        R.string.tooldetail_qr_gen_send_image_desc
                                    },
                                ),
                            )
                        },
                    ) { scope.launch { repository.setQrSendMode(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_qr_gen_gallery_title,
                        stringResource(R.string.tooldetail_qr_gen_gallery_subtitle),
                        settings.qrSaveToGallery,
                        default = SettingsDefaults.qrSaveToGallery,
                    ) { scope.launch { repository.setQrSaveToGallery(it) } }
                }
            }
            SectionHeader(stringResource(R.string.tooldetail_qr_gen_ecc_header))
            ChoiceControl(
                // The names are the standard's own single letters (L/M/Q/H),
                // not words, so they are not translated.
                options = QrEccLevel.entries.map { it to it.name },
                selected = settings.qrEcc,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                detail = { level -> ChoiceDetail(stringResource(qrEccDescRes(level))) },
            ) { level -> scope.launch { repository.setQrEcc(level) } }
            SettingsGroup {
                item {
                    val charsFormat = stringResource(R.string.values_number)
                    SliderSetting(
                        R.string.tooldetail_qr_max_chars_title,
                        subtitle = stringResource(R.string.tooldetail_qr_max_chars_subtitle),
                        value = settings.toolLimits.qrMaxChars.toFloat(),
                        range = 500f..4000f,
                        display = { charsFormat.format((it / 100f).roundToInt() * 100) },
                        info = stringResource(R.string.tooldetail_qr_max_chars_info),
                        default = SettingsDefaults.toolLimits.qrMaxChars.toFloat(),
                    ) { picked ->
                        scope.launch {
                            repository.setQrMaxChars((picked / 100f).roundToInt() * 100)
                        }
                    }
                }
            }
        }
        ToolbarTool.PASSWORD_GEN -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_password_group),
                info = stringResource(R.string.tooldetail_password_pool_info),
            ) {
                item {
                    SliderSetting(
                        R.string.tooldetail_password_length_title,
                        value = settings.passwordGenerator.pwLength.toFloat(),
                        range = 4f..64f,
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.passwordGenerator.pwLength.toFloat(),
                    ) { scope.launch { repository.setPwLength(it.roundToInt()) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_password_uppercase_title,
                        stringResource(R.string.tooldetail_password_uppercase_subtitle),
                        settings.passwordGenerator.pwUppercase,
                        default = SettingsDefaults.passwordGenerator.pwUppercase,
                    ) { scope.launch { repository.setPwUppercase(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_password_digits_title,
                        stringResource(R.string.tooldetail_password_digits_subtitle),
                        settings.passwordGenerator.pwDigits,
                        default = SettingsDefaults.passwordGenerator.pwDigits,
                    ) { scope.launch { repository.setPwDigits(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_password_symbols_title,
                        stringResource(R.string.tooldetail_password_symbols_subtitle),
                        settings.passwordGenerator.pwSymbols,
                        default = SettingsDefaults.passwordGenerator.pwSymbols,
                    ) { scope.launch { repository.setPwSymbols(it) } }
                }
                if (settings.passwordGenerator.pwSymbols) {
                    item {
                        TextFieldSetting(
                            label = stringResource(R.string.tooldetail_password_pool_label),
                            value = settings.toolLimits.passwordSymbols,
                            hint = stringResource(R.string.tooldetail_password_pool_hint),
                            default = SettingsDefaults.toolLimits.passwordSymbols,
                        ) { repository.setPasswordSymbols(it) }
                    }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_password_ambiguous_title,
                        stringResource(R.string.tooldetail_password_ambiguous_subtitle),
                        settings.passwordGenerator.pwExcludeAmbiguous,
                        default = SettingsDefaults.passwordGenerator.pwExcludeAmbiguous,
                    ) { scope.launch { repository.setPwExcludeAmbiguous(it) } }
                }
            }
            SettingsGroup(
                stringResource(R.string.tooldetail_passphrase_group),
                info = stringResource(R.string.tooldetail_password_info),
            ) {
                item {
                    SliderSetting(
                        R.string.tooldetail_passphrase_words_title,
                        value = settings.passwordGenerator.ppWordCount.toFloat(),
                        range = 2f..10f,
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.passwordGenerator.ppWordCount.toFloat(),
                    ) { scope.launch { repository.setPpWordCount(it.roundToInt()) } }
                }
                item {
                    TextFieldSetting(
                        label = stringResource(R.string.tooldetail_passphrase_separator_label),
                        value = settings.passwordGenerator.ppSeparator,
                        hint = stringResource(R.string.tooldetail_passphrase_separator_hint),
                        default = SettingsDefaults.passwordGenerator.ppSeparator,
                    ) { repository.setPpSeparator(it) }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_passphrase_capitalize_title,
                        stringResource(R.string.tooldetail_passphrase_capitalize_subtitle),
                        settings.passwordGenerator.ppCapitalize,
                        default = SettingsDefaults.passwordGenerator.ppCapitalize,
                    ) { scope.launch { repository.setPpCapitalize(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_passphrase_digit_title,
                        stringResource(R.string.tooldetail_passphrase_digit_subtitle),
                        settings.passwordGenerator.ppIncludeDigit,
                        default = SettingsDefaults.passwordGenerator.ppIncludeDigit,
                    ) { scope.launch { repository.setPpIncludeDigit(it) } }
                }
            }
        }
        ToolbarTool.TYPING_TEST -> TypingTestToolSettings(repository, settings)
        ToolbarTool.AI -> AiToolSettings(repository, settings, onNavigate)
        ToolbarTool.MODES -> SettingsGroup(stringResource(R.string.tooldetail_modes_group)) {
            item {
                NavRow(
                    R.string.tooldetail_modes_edit_title,
                    stringResource(R.string.tooldetail_modes_edit_subtitle),
                    value = "${settings.keyboardModes.size}",
                ) { onNavigate("modes") }
            }
        }
        else -> {}
    }
}
/** The display name of a rate provider. Ids are stored, names are shown. */
@Composable
private fun providerLabel(provider: CurrencyClient.Provider): String = stringResource(
    when (provider) {
        CurrencyClient.Provider.ER_API -> R.string.tooldetail_rate_source_er_api
        CurrencyClient.Provider.FRANKFURTER -> R.string.tooldetail_rate_source_frankfurter
        CurrencyClient.Provider.COINBASE -> R.string.tooldetail_rate_source_coinbase
        CurrencyClient.Provider.CURRENCY_API -> R.string.tooldetail_rate_source_currency_api
        CurrencyClient.Provider.COINGECKO -> R.string.tooldetail_rate_source_coingecko
    },
)
/**
 * Which source to fetch from, and whether the rest stand behind it. Both
 * answers are one stored list: the head is the source that is tried and the
 * tail is the fallback chain, so switching fallbacks off simply drops the
 * tail.
 */
@Composable
private fun RateSourceSetting(
    @StringRes title: Int,
    subtitle: String,
    providers: List<String>,
    defaultProviders: List<String>,
    candidates: List<CurrencyClient.Provider>,
    onChange: (List<String>) -> Unit,
) {
    val primary = providers.firstNotNullOfOrNull { CurrencyClient.Provider.of(it) }
        ?: candidates.first()
    val fallback = providers.size > 1
    // Both rows are views onto the one stored list, so both read their default
    // off the list the app shipped with rather than off a constant of their own.
    val defaultPrimary = defaultProviders.firstNotNullOfOrNull { CurrencyClient.Provider.of(it) }
        ?: candidates.first()
    fun write(head: CurrencyClient.Provider, withFallback: Boolean) {
        val rest = if (withFallback) candidates.filter { it != head }.map { it.name } else emptyList()
        onChange(listOf(head.name) + rest)
    }
    ChoiceSetting(
        title = title,
        subtitle = subtitle,
        options = candidates.map { it to providerLabel(it) },
        selected = primary,
        default = defaultPrimary,
    ) { write(it, fallback) }
    ToggleSetting(
        R.string.tooldetail_rate_fallback_title,
        stringResource(R.string.tooldetail_rate_fallback_subtitle),
        fallback,
        default = defaultProviders.size > 1,
    ) { write(primary, it) }
}
/**
 * The coins the keyboard reads and offers. An empty stored set means the
 * catalogue's defaults, so the last coin cannot be switched off — turning
 * it off would silently bring all of them back.
 */
@Composable
private fun CryptoCoinPicker(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val enabled = remember(settings.rateSources.cryptoTickers) {
        CryptoCatalog.enabled(settings.rateSources.cryptoTickers)
    }
    val extra = remember(enabled) { enabled.filterNot { CryptoCatalog.isKnown(it) }.sorted() }
    var showAdd by remember { mutableStateOf(false) }
    fun save(next: Set<String>) {
        if (next.isNotEmpty()) scope.launch { repository.setCryptoTickers(next) }
    }

    WmRow(
        title = stringResource(R.string.tooldetail_crypto_coins_title),
        subtitle = stringResource(R.string.tooldetail_crypto_coins_subtitle, enabled.size),
        icon = SettingsRowIcons[R.string.tooldetail_crypto_coins_title],
    )
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (coin in CryptoCatalog.coins) {
            FilterChip(
                selected = coin.code in enabled,
                onClick = {
                    save(if (coin.code in enabled) enabled - coin.code else enabled + coin.code)
                },
                label = { Text(coin.code, maxLines = 1) },
            )
        }
        for (ticker in extra) {
            FilterChip(
                selected = true,
                onClick = { save(enabled - ticker) },
                label = { Text(ticker, maxLines = 1) },
            )
        }
    }
    Button(
        onClick = { showAdd = true },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) { Text(stringResource(R.string.tooldetail_crypto_add_action)) }

    if (showAdd) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.tooldetail_crypto_add_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.tooldetail_crypto_add_hint)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.tooldetail_crypto_add_info),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        save(enabled + input.trim().uppercase().filter { it.isLetterOrDigit() })
                        showAdd = false
                    },
                ) { Text(stringResource(CommonR.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
/**
 * The typing test's settings. These are the same values the panel's own
 * chip row edits — this screen is the slower way round to them, plus the
 * records the panel only shows one config of at a time.
 */
@Composable
private fun TypingTestToolSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val secondsFormat = stringResource(R.string.values_seconds)
    val numberFormat = stringResource(R.string.values_number)
    val bests = remember(settings.typingTest.bests) { TypingBests.decode(settings.typingTest.bests) }
    val history = remember(settings.typingTest.history) {
        TypingHistory.decode(settings.typingTest.history)
    }

    SectionHeader(stringResource(R.string.toolai_typing_default_test_title))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        for (mode in TypingTestMode.entries) {
            FilterChip(
                selected = settings.typingTest.mode == mode,
                onClick = { scope.launch { repository.setTypingTestMode(mode) } },
                label = {
                    Text(
                        stringResource(
                            when (mode) {
                                TypingTestMode.TIME -> R.string.toolai_typing_mode_time_label
                                TypingTestMode.WORDS -> R.string.toolai_typing_mode_words_label
                                TypingTestMode.QUOTE -> R.string.toolai_typing_mode_quote_label
                            },
                        ),
                    )
                },
            )
        }
    }

    SettingsGroup(
        stringResource(R.string.toolai_typing_length_title),
        info = stringResource(R.string.toolai_typing_quote_info).takeIf { settings.typingTest.mode == TypingTestMode.QUOTE },
    ) {
        when (settings.typingTest.mode) {
            TypingTestMode.TIME -> item {
                SliderSetting(
                    R.string.toolai_typing_seconds_label,
                    value = settings.typingTest.duration.toFloat(),
                    range = 15f..120f,
                    display = { secondsFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.typingTest.duration.toFloat(),
                ) { scope.launch { repository.setTypingTestDuration(it.roundToInt()) } }
            }
            TypingTestMode.WORDS -> item {
                SliderSetting(
                    R.string.toolai_typing_words_label,
                    value = settings.typingTest.wordCount.toFloat(),
                    range = 10f..100f,
                    display = { numberFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.typingTest.wordCount.toFloat(),
                ) { scope.launch { repository.setTypingTestWordCount(it.roundToInt()) } }
            }
            // Quotes come at whatever length they were written.
            TypingTestMode.QUOTE -> Unit
        }
    }

    if (settings.typingTest.mode != TypingTestMode.QUOTE) {
        SettingsGroup(stringResource(R.string.toolai_typing_difficulty_title)) {
            item {
                ToggleSetting(
                    R.string.toolai_typing_punctuation_title,
                    stringResource(R.string.toolai_typing_punctuation_subtitle),
                    settings.typingTest.punctuation,
                    default = SettingsDefaults.typingTest.punctuation,
                ) { scope.launch { repository.setTypingTestPunctuation(it) } }
            }
            item {
                ToggleSetting(
                    R.string.toolai_typing_numbers_title,
                    stringResource(R.string.toolai_typing_numbers_subtitle),
                    settings.typingTest.numbers,
                    default = SettingsDefaults.typingTest.numbers,
                ) { scope.launch { repository.setTypingTestNumbers(it) } }
            }
        }
    }

    // The keyboard's own helpers, let into a run one at a time so the
    // difference each one makes can be read off two scores.
    SettingsGroup(
        stringResource(R.string.toolai_typing_assist_title),
        info = stringResource(R.string.toolai_typing_language_info),
    ) {
        item {
            ToggleSetting(
                R.string.toolai_typing_glide_title,
                stringResource(R.string.toolai_typing_glide_subtitle),
                settings.typingTest.glide,
                default = SettingsDefaults.typingTest.glide,
            ) { scope.launch { repository.setTypingTestGlide(it) } }
        }
        item {
            ToggleSetting(
                R.string.toolai_typing_suggestions_title,
                stringResource(R.string.toolai_typing_suggestions_subtitle),
                settings.typingTest.suggestions,
                default = SettingsDefaults.typingTest.suggestions,
            ) { scope.launch { repository.setTypingTestSuggestions(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.toolai_typing_records_title)) {
        item {
            WmRow(
                title = stringResource(R.string.toolai_typing_tests_completed_title),
                trailing = { Text("${settings.typingTest.completed}") },
            )
        }
        if (history.isNotEmpty()) {
            item {
                WmRow(
                    title = stringResource(R.string.toolai_typing_recent_average_title),
                    subtitle = pluralStringResource(
                        R.plurals.toolai_typing_recent_average_subtitle,
                        history.size,
                        history.size,
                    ),
                    trailing = {
                        Text(
                            stringResource(
                                R.string.toolai_typing_wpm_value,
                                history.average().roundToInt(),
                            ),
                        )
                    },
                )
            }
        }
        // One row per config the user has actually run, best first.
        for ((key, wpm) in bests.entries.sortedByDescending { it.value }) {
            item {
                WmRow(
                    title = typingBestLabel(key),
                    trailing = {
                        Text(stringResource(R.string.toolai_typing_wpm_value, wpm.roundToInt()))
                    },
                )
            }
        }
        if (bests.isNotEmpty() || settings.typingTest.completed > 0) {
            item {
                NavRow(
                    R.string.toolai_typing_clear_records_title,
                    stringResource(R.string.toolai_typing_clear_records_subtitle),
                ) {
                    scope.launch { repository.clearTypingStats() }
                }
            }
        }
    }

    // The full badge list, locked ones greyed — the keyboard's results screen
    // only shows what is already earned; this is where the goals are visible.
    val unlockedBadges = remember(settings.typingTest.achievements) {
        TypingAchievements.decode(settings.typingTest.achievements)
    }
    SettingsGroup(
        stringResource(R.string.toolai_typing_achievements_title),
        info = stringResource(R.string.toolai_typing_info),
    ) {
        for (id in TypingAchievements.ALL) {
            item {
                val unlocked = id in unlockedBadges
                val (title, subtitle) = when (id) {
                    TypingAchievements.WPM_100 ->
                        R.string.toolai_typing_achievement_wpm100_title to
                            R.string.toolai_typing_achievement_wpm100_subtitle
                    TypingAchievements.PERFECT ->
                        R.string.toolai_typing_achievement_perfect_title to
                            R.string.toolai_typing_achievement_perfect_subtitle
                    TypingAchievements.PANGRAM ->
                        R.string.toolai_typing_achievement_pangram_title to
                            R.string.toolai_typing_achievement_pangram_subtitle
                    else ->
                        R.string.toolai_typing_achievement_tests50_title to
                            R.string.toolai_typing_achievement_tests50_subtitle
                }
                WmRow(
                    title = stringResource(title),
                    subtitle = stringResource(subtitle),
                    trailing = {
                        if (unlocked) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = stringResource(
                                    R.string.toolai_typing_achievement_unlocked_desc,
                                ),
                                tint = ActiveGreen,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = stringResource(
                                    R.string.toolai_typing_achievement_locked_desc,
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    },
                )
            }
        }
    }

}
/** Turns a stored best's key ("time30", "quote") back into a heading. */
@Composable
private fun typingBestLabel(key: String): String {
    val base = typingConfigBase(key)
    val label = when {
        base == "quote" -> stringResource(R.string.toolai_typing_mode_quote_label)
        base.startsWith("time") ->
            stringResource(R.string.toolai_typing_best_seconds_label, base.removePrefix("time"))
        base.startsWith("words") ->
            stringResource(R.string.toolai_typing_best_words_label, base.removePrefix("words"))
        else -> base
    }
    // English records carry no language; every other language's are named.
    val languageId = typingConfigLanguage(key)
    if (languageId.isEmpty()) return label
    return stringResource(
        R.string.toolai_typing_best_language_label,
        label,
        LanguageRegistry.byId(languageId).displayName,
    )
}
/**
 * The words that make this tool offer itself on the suggestion strip.
 * Only tools that ship a default get the row — a keyword for "Undo" would
 * fire on prose and there is nothing to open anyway.
 */
@Composable
private fun ToolKeywordSetting(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    tool: ToolbarTool,
) {
    val defaults = SmartSuggest.defaultKeywords[tool] ?: return
    val scope = rememberCoroutineScope()
    val saved = SmartSuggest.keywordsFor(tool, settings.toolKeywords)
    val caseSensitive = SmartSuggest.caseSensitiveKeyword(tool, settings.toolKeywordCase)
    var text by remember(tool) { mutableStateOf(saved.joinToString(", ")) }
    SettingsGroup(stringResource(R.string.toolai_keyword_group_title), foldKey = "tool_keyword") {
        item {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    scope.launch { repository.setToolKeywords(tool, it.split(',')) }
                },
                label = { Text(stringResource(R.string.toolai_keyword_field_label)) },
                singleLine = true,
                supportingText = {
                    Text(
                        if (saved.isEmpty()) {
                            stringResource(R.string.toolai_keyword_empty_hint)
                        } else {
                            stringResource(
                                R.string.toolai_keyword_hint,
                                stringResource(toolTitle(tool)),
                            )
                        }
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            ToggleSetting(
                R.string.toolai_keyword_case_title,
                stringResource(
                    if (caseSensitive) R.string.toolai_keyword_case_on_subtitle
                    else R.string.toolai_keyword_case_off_subtitle,
                ),
                caseSensitive,
                info = stringResource(R.string.toolai_keyword_case_info),
                default = SmartSuggest.caseSensitiveKeyword(
                    tool,
                    SettingsDefaults.toolKeywordCase,
                ),
            ) { scope.launch { repository.setToolKeywordCaseSensitive(tool, it) } }
        }
        if (saved != defaults) {
            item {
                WmRow(
                    title = stringResource(CommonR.string.common_reset_defaults),
                    subtitle = defaults.joinToString(", "),
                    onClick = {
                        text = defaults.joinToString(", ")
                        scope.launch { repository.setToolKeywords(tool, defaults) }
                    },
                )
            }
        }
    }
    if (!settings.smartSuggestions || !settings.smartToolKeywords) {
        StateBanner(stringResource(R.string.toolai_keyword_off_info))
    }
}
/** A plain saved-as-you-type text setting (same mechanics as ApiKeyField). */
@Composable
internal fun TextFieldSetting(
    label: String,
    value: String,
    hint: String,
    default: String? = null,
    onSave: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember(label) { mutableStateOf(value) }
    HighlightableRow(label) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                scope.launch { onSave(it) }
            },
            label = { Text(label) },
            singleLine = true,
            supportingText = { Text(hint) },
            trailingIcon = {
                ResetSetting(label, default != null && text != default) {
                    text = default.orEmpty()
                    scope.launch { onSave(default.orEmpty()) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
/**
 * One API-key input. Saves as you type (it's a paste, in practice). The
 * user's key always beats any key baked into the build via
 * local.properties — leaving the field blank falls back to the built-in
 * key when the build has one.
 */
@Composable
internal fun ApiKeyField(
    label: String,
    value: String,
    builtInAvailable: Boolean,
    emptyHint: String,
    onSave: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember(label) { mutableStateOf(value) }
    HighlightableRow(label) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                scope.launch { onSave(it) }
            },
            label = { Text(label) },
            singleLine = true,
            supportingText = {
                Text(
                    when {
                        text.isNotBlank() -> stringResource(R.string.toolai_api_key_yours_hint)
                        builtInAvailable -> stringResource(R.string.toolai_api_key_builtin_hint)
                        else -> emptyHint
                    },
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
/**
 * One of the calendar tool's two alternate-calendar slots. A dialog rather
 * than a segmented row: nine choices never fit side by side, and the tool's
 * settings and the onboarding page both ask the same question.
 */
@Composable
internal fun AltCalendarSetting(
    title: String,
    subtitle: String,
    selected: AltCalendar,
    icon: ImageVector? = null,
    onChange: (AltCalendar) -> Unit,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    // The label of every calendar but NONE reads "English name · own name";
    // the row has room for the first half only. NONE's label is already short.
    NavRow(
        title,
        subtitle = subtitle,
        value = stringResource(selected.labelRes).substringBefore(" ·"),
        icon = icon,
        onClick = { dialogOpen = true },
    )
    if (dialogOpen) {
        val selectedDesc = stringResource(R.string.toolai_selected_desc)
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(AltCalendar.entries) { calendar ->
                        ListItem(
                            headlineContent = { Text(stringResource(calendar.labelRes)) },
                            trailingContent = if (calendar == selected) {
                                { Icon(Icons.Outlined.Check, contentDescription = selectedDesc) }
                            } else null,
                            modifier = Modifier.clickable {
                                dialogOpen = false
                                onChange(calendar)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(CommonR.string.common_close))
                }
            },
        )
    }
}
/**
 * The weekend picker, as a row plus dialog so it works both on the tool's
 * settings screen and in the onboarding wizard, which has no [SettingsGroup].
 */
@Composable
internal fun WeekendSetting(selected: Weekend, onChange: (Weekend) -> Unit) {
    var dialogOpen by remember { mutableStateOf(false) }
    NavRow(
        R.string.toolai_weekend_title,
        subtitle = stringResource(R.string.toolai_weekend_subtitle),
        value = stringResource(selected.labelRes),
        onClick = { dialogOpen = true },
    )
    if (dialogOpen) {
        val selectedDesc = stringResource(R.string.toolai_selected_desc)
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(stringResource(R.string.toolai_weekend_title)) },
            text = {
                LazyColumn {
                    items(Weekend.entries) { weekend ->
                        ListItem(
                            headlineContent = { Text(stringResource(weekend.labelRes)) },
                            trailingContent = if (weekend == selected) {
                                { Icon(Icons.Outlined.Check, contentDescription = selectedDesc) }
                            } else null,
                            modifier = Modifier.clickable {
                                dialogOpen = false
                                onChange(weekend)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(CommonR.string.common_close))
                }
            },
        )
    }
}
/** "Translate into" row with a full-language-list dialog. */
@Composable
private fun TranslateLanguageSetting(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    var dialogOpen by remember { mutableStateOf(false) }
    NavRow(
        R.string.toolai_translate_into_title,
        subtitle = stringResource(R.string.toolai_translate_into_subtitle),
        value = TranslateClient.languageName(settings.translateTargetLang),
        onClick = { dialogOpen = true },
    )
    if (dialogOpen) {
        val selectedDesc = stringResource(R.string.toolai_selected_desc)
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(stringResource(R.string.toolai_translate_into_title)) },
            text = {
                LazyColumn {
                    items(TranslateClient.languages) { (code, name) ->
                        ListItem(
                            headlineContent = { Text(name) },
                            trailingContent = if (code == settings.translateTargetLang) {
                                { Icon(Icons.Outlined.Check, contentDescription = selectedDesc) }
                            } else null,
                            modifier = Modifier.clickable {
                                dialogOpen = false
                                scope.launch { repository.setTranslateTargetLang(code) }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(CommonR.string.common_close))
                }
            },
        )
    }
}
/**
 * Open Android's Spell checker settings screen — where WM Keyboard's Harper
 * service can be picked as the system checker.
 *
 * There is no public [Settings] action for this screen, so we aim the direct
 * AOSP Settings component first and only fall back to the input-method
 * settings page (its parent) when that component is missing or hidden, as it
 * is on some OEM builds. Resolving before launching keeps a stock ROM that
 * renamed the activity from throwing an [android.content.ActivityNotFoundException].
 */
private fun openSpellCheckerSettings(context: Context) {
    val direct = Intent(Intent.ACTION_MAIN).setComponent(
        ComponentName(
            "com.android.settings",
            "com.android.settings.Settings\$SpellCheckersSettingsActivity",
        )
    )
    val resolves = context.packageManager.resolveActivity(direct, 0) != null
    val launched = resolves && runCatching { context.startActivity(direct) }.isSuccess
    if (!launched) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }
}
/**
 * Opens Android's subtype enabler for this keyboard — the screen where the user
 * ticks which of our registered languages the system switcher may list. Needed
 * on Android 13 and older, where an IME cannot enable its own subtypes and the
 * framework otherwise picks one from the phone's language list.
 *
 * The extra is what scopes the screen to us; without it (or on an OEM build
 * that dropped the activity) we fall back to the input-method settings page,
 * which is one tap away from the same place.
 */
internal fun openSubtypeEnabler(context: Context) {
    val imeId = ComponentName(context, WMKeyboardService::class.java).flattenToShortString()
    val direct = Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS)
        .putExtra(Settings.EXTRA_INPUT_METHOD_ID, imeId)
    if (runCatching { context.startActivity(direct) }.isFailure) {
        runCatching { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
    }
}

/**
 * What a running download has to say for itself. Before the first byte there
 * is only the wait, and calling that "downloading" is what makes a slow start
 * look like a hang.
 */
@Composable
private fun downloadSubtitle(progress: HandwritingDownloadProgress?): String {
    val context = LocalContext.current
    val bytes = progress?.bytes ?: 0L
    if (bytes <= 0L) return stringResource(R.string.privacy_handwriting_status_preparing)
    val size = Formatter.formatShortFileSize(context, bytes)
    return if ((progress?.stalledForMs ?: 0L) >= HANDWRITING_STALL_HINT_MS) {
        stringResource(R.string.privacy_handwriting_status_stalled, size)
    } else {
        stringResource(R.string.privacy_handwriting_status_downloading, size)
    }
}

/** How long with nothing arriving before the row admits it is stuck. */
private const val HANDWRITING_STALL_HINT_MS = 20_000L

/**
 * Download/delete state for the handwriting model of every language the user
 * types in — drawn from ML Kit's full ink catalogue, then narrowed to the
 * enabled languages so the list is only ever as long as it is useful. Status
 * is re-read from ML Kit's model manager after every action.
 */
@Composable
private fun HandwritingModelManager(settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val languages = remember(settings.enabledLanguages) {
        HandwritingModels.modelsFor(settings.enabledLanguages)
    }
    val missing = remember(settings.enabledLanguages, languages) {
        settings.enabledLanguages.distinctBy { it.id }
            .filter { HandwritingModels.tagFor(it) == null }
    }
    val context = LocalContext.current
    // tag -> "checking" | "missing" | "downloaded" | "downloading" | "error"
    val statuses = remember { mutableStateMapOf<String, String>() }
    // Live byte counts while a download runs. ML Kit publishes no percentage,
    // so the row reports what it can actually see arriving.
    val progress = remember { mutableStateMapOf<String, HandwritingDownloadProgress>() }
    LaunchedEffect(languages) {
        for (language in languages) {
            statuses[language.tag] =
                if (HandwritingModels.isDownloaded(language.tag)) "downloaded" else "missing"
        }
    }
    if (languages.isEmpty()) {
        StateBanner(stringResource(R.string.privacy_handwriting_none_info))
        return
    }
    SettingsGroup {
        for (language in languages) {
            item {
                val status = statuses[language.tag] ?: "checking"
                WmRow(
                    title = language.displayName,
                    subtitle = when (status) {
                            "checking" -> stringResource(R.string.privacy_handwriting_status_checking)
                            "downloaded" -> stringResource(R.string.privacy_handwriting_status_downloaded)
                            "downloading" -> downloadSubtitle(progress[language.tag])
                            "error" -> stringResource(R.string.privacy_handwriting_status_failed)
                            else -> stringResource(R.string.privacy_handwriting_status_missing)
                        },
                    trailing = {
                        when (status) {
                            "downloading", "checking" -> CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                            "downloaded" -> IconButton(onClick = {
                                scope.launch {
                                    HandwritingModels.delete(language.tag)
                                    statuses[language.tag] =
                                        if (HandwritingModels.isDownloaded(language.tag)) "downloaded" else "missing"
                                }
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(
                                        R.string.privacy_handwriting_delete_desc,
                                        language.displayName,
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> TextButton(onClick = {
                                statuses[language.tag] = "downloading"
                                progress[language.tag] = HandwritingDownloadProgress()
                                scope.launch {
                                    val ok = runCancellable {
                                        HandwritingModels.download(context, language.tag) {
                                            progress[language.tag] = it
                                        }
                                    }.isSuccess
                                    progress.remove(language.tag)
                                    statuses[language.tag] = if (ok) "downloaded" else "error"
                                }
                            }) { Text(stringResource(CommonR.string.common_download)) }
                        }
                    },
                )
            }
        }
    }
    if (missing.isNotEmpty()) {
        StateBanner(
            stringResource(
                R.string.privacy_handwriting_missing_info,
                missing.joinToString(", ") { it.englishName },
            ),
        )
    }
}
/**
 * Weather location: place label plus coordinates, edited in a dialog. Shared
 * with the onboarding tool-setup page, which asks the same question.
 */
@Composable
internal fun WeatherLocationSetting(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    val unnamedPlace = stringResource(R.string.privacy_weather_place_unnamed)
    val savedLatitude = settings.weatherLatitude
    val savedLongitude = settings.weatherLongitude
    val summary = if (savedLatitude != null && savedLongitude != null) {
        stringResource(
            R.string.privacy_weather_location_summary,
            settings.weatherPlaceName.ifBlank { unnamedPlace },
            savedLatitude,
            savedLongitude,
        )
    } else {
        stringResource(R.string.privacy_weather_location_empty)
    }
    WmRow(
        title = stringResource(R.string.privacy_weather_location_title),
        subtitle = summary,
        trailing = {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.privacy_weather_edit_desc),
            )
        },
        onClick = { editing = true },
    )
    if (!editing) return

    var place by remember { mutableStateOf(settings.weatherPlaceName) }
    var lat by remember { mutableStateOf(settings.weatherLatitude?.toString().orEmpty()) }
    var lon by remember { mutableStateOf(settings.weatherLongitude?.toString().orEmpty()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    val parsedLat = lat.trim().toFloatOrNull()?.takeIf { it in -90f..90f }
    val parsedLon = lon.trim().toFloatOrNull()?.takeIf { it in -180f..180f }

    fun search() {
        if (query.isBlank() || searching) return
        searching = true
        searchFailed = false
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { WeatherClient.geocode(query) }.getOrNull()
            }
            searching = false
            if (found == null) {
                searchFailed = true
            } else {
                results = found
                searchFailed = found.isEmpty()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { editing = false },
        // Typing in the search/coordinate fields moves the dialog around the
        // keyboard, so a tap can land on the scrim where the dialog just was
        // and silently swallow the half-entered location. Explicit
        // Cancel/Save only; back still dismisses.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.privacy_weather_dialog_title)) },
        text = {
            val unknownRegion = stringResource(R.string.privacy_weather_region_unknown)
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.privacy_weather_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { search() }, enabled = query.isNotBlank() && !searching) {
                        Text(if (searching) "…" else stringResource(CommonR.string.common_search))
                    }
                }
                if (searchFailed) {
                    Text(
                        if (results.isEmpty() && !searching) {
                            stringResource(R.string.privacy_weather_no_matches_error)
                        } else {
                            stringResource(R.string.privacy_weather_search_error)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                for (result in results) {
                    ListItem(
                        headlineContent = { Text(result.name) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.privacy_weather_result_summary,
                                    result.region.ifBlank { unknownRegion },
                                    result.latitude,
                                    result.longitude,
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                place = result.name
                                lat = result.latitude.toString()
                                lon = result.longitude.toString()
                                results = emptyList()
                            },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.privacy_weather_manual_info),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it },
                    label = { Text(stringResource(R.string.privacy_weather_name_hint)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text(stringResource(R.string.privacy_weather_latitude_hint)) },
                    singleLine = true,
                    isError = lat.isNotBlank() && parsedLat == null,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it },
                    label = { Text(stringResource(R.string.privacy_weather_longitude_hint)) },
                    singleLine = true,
                    isError = lon.isNotBlank() && parsedLon == null,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedLat != null && parsedLon != null,
                onClick = {
                    scope.launch {
                        repository.setWeatherLocation(parsedLat, parsedLon, place.trim())
                    }
                    editing = false
                },
            ) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            Row {
                if (settings.weatherLatitude != null) {
                    TextButton(onClick = {
                        scope.launch { repository.setWeatherLocation(null, null, "") }
                        editing = false
                    }) { Text(stringResource(CommonR.string.common_clear)) }
                }
                TextButton(onClick = { editing = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            }
        },
    )
}
// ---- privacy ----

/**
 * The explanation of "Follow private browsing". Two screens show it: the
 * Privacy screen below, and the incognito tool's own settings. It is a
 * resource id, not the text, so it is read where it is drawn.
 */
@StringRes
internal val AUTO_INCOGNITO_INFO = R.string.privacy_auto_incognito_info

/** What arms power saving under each answer, one line, for the picker sheet. */
private fun powerTriggerDescRes(trigger: PowerSavingTrigger): Int = when (trigger) {
    PowerSavingTrigger.OFF -> R.string.tooldetail_power_trigger_off_desc
    PowerSavingTrigger.SYSTEM_SAVER -> R.string.tooldetail_power_trigger_system_desc
    PowerSavingTrigger.LOW_BATTERY -> R.string.tooldetail_power_trigger_low_desc
    PowerSavingTrigger.EITHER -> R.string.tooldetail_power_trigger_either_desc
}

/** How the GIF panel arranges its sources, one line, for the picker sheet. */
private fun gifSourceDescRes(mode: GifSourceMode): Int = when (mode) {
    GifSourceMode.TABS -> R.string.tooldetail_media_source_tabs_desc
    GifSourceMode.MIX -> R.string.tooldetail_media_source_mixed_desc
}

/**
 * What each filter level hides, with the rating it maps to. The level names
 * are a scale with nothing on it: "Medium" says it is between two others and
 * says nothing about what comes back.
 */
private fun gifFilterDescRes(filter: GifContentFilter): Int = when (filter) {
    GifContentFilter.OFF -> R.string.tooldetail_media_filter_off_desc
    GifContentFilter.LOW -> R.string.tooldetail_media_filter_low_desc
    GifContentFilter.MEDIUM -> R.string.tooldetail_media_filter_medium_desc
    GifContentFilter.HIGH -> R.string.tooldetail_media_filter_high_desc
}

/**
 * What each error correction level buys and costs. The option names are the
 * standard's own letters, so the sheet is the only place this can be said.
 */
private fun qrEccDescRes(level: QrEccLevel): Int = when (level) {
    QrEccLevel.L -> R.string.tooldetail_qr_gen_ecc_l_desc
    QrEccLevel.M -> R.string.tooldetail_qr_gen_ecc_m_desc
    QrEccLevel.Q -> R.string.tooldetail_qr_gen_ecc_q_desc
    QrEccLevel.H -> R.string.tooldetail_qr_gen_ecc_h_desc
}
