package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.barcode.BarcodeEngine
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.app.media.MusicApps
import com.wasimaster.wmkeyboard.app.launcher.LauncherCombos
import com.wasimaster.wmkeyboard.core.settings.SearchResultTap
import com.wasimaster.wmkeyboard.core.settings.LauncherOpenMode
import com.wasimaster.wmkeyboard.core.settings.MAX_SEARCH_RESULTS
import com.wasimaster.wmkeyboard.ime.AppLaunchModes
import com.wasimaster.wmkeyboard.core.accessibility.SplitScreenHelperService
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.media.hasNotificationAccess
import com.wasimaster.wmkeyboard.core.notify.DownloadKeys
import com.wasimaster.wmkeyboard.core.prediction.PhoneticSchemes
import com.wasimaster.wmkeyboard.core.settings.AppSortOrder
import com.wasimaster.wmkeyboard.core.settings.LauncherIconShape
import com.wasimaster.wmkeyboard.core.settings.PhotoSearchEngine
import com.wasimaster.wmkeyboard.core.settings.PhotoSearchTarget
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.HandwritingFullScreenMode
import com.wasimaster.wmkeyboard.core.tools.PasswordGen
import com.wasimaster.wmkeyboard.core.tools.AiPrompts
import com.wasimaster.wmkeyboard.core.tools.ReplyEmoji
import com.wasimaster.wmkeyboard.core.tools.ReplyImageMode
import com.wasimaster.wmkeyboard.core.tools.ReplyLength
import com.wasimaster.wmkeyboard.core.tools.CryptoCatalog
import com.wasimaster.wmkeyboard.core.tools.TextRepeat
import com.wasimaster.wmkeyboard.core.tools.CurrencyLabel
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Switch
import androidx.compose.ui.text.style.TextOverflow
import com.wasimaster.wmkeyboard.core.tools.DictionarySource
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.ui.toolAccentColorArgb
import com.wasimaster.wmkeyboard.core.ui.toolAccentEndColorArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.deeplLive
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Build
import com.wasimaster.wmkeyboard.core.handwriting.HandwritingDownloadProgress
import com.wasimaster.wmkeyboard.core.handwriting.HandwritingModelSizes
import com.wasimaster.wmkeyboard.core.handwriting.HandwritingModels
import com.wasimaster.wmkeyboard.core.handwriting.InkPackImports
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.ime.KeyboardAutomation
import com.wasimaster.wmkeyboard.ime.WMKeyboardService
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import com.wasimaster.wmkeyboard.core.settings.GifContentFilter
import com.wasimaster.wmkeyboard.core.settings.GrammarCategory
import com.wasimaster.wmkeyboard.core.grammar.LanguageToolChecker
import com.wasimaster.wmkeyboard.core.settings.GrammarDialect
import com.wasimaster.wmkeyboard.core.settings.GrammarLintKind
import com.wasimaster.wmkeyboard.core.settings.MediaGridSpacingRange
import com.wasimaster.wmkeyboard.core.settings.MediaPanelExtraHeightRange
import com.wasimaster.wmkeyboard.core.settings.MediaPerRowRange
import com.wasimaster.wmkeyboard.core.settings.MediaSendMode
import com.wasimaster.wmkeyboard.core.settings.QrEccLevel
import com.wasimaster.wmkeyboard.core.settings.StickerSuggestStyle
import com.wasimaster.wmkeyboard.core.settings.StickerTriggerAction
import com.wasimaster.wmkeyboard.core.tools.AltCalendar
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendar
import com.wasimaster.wmkeyboard.core.settings.CalendarSaveMode
import com.wasimaster.wmkeyboard.core.tools.Weekend
import com.wasimaster.wmkeyboard.core.tools.isSouthernHemisphere
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
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslation
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import com.wasimaster.wmkeyboard.core.ocr.OcrLanguages
import com.wasimaster.wmkeyboard.core.ocr.OcrPacks
import com.wasimaster.wmkeyboard.core.ocr.PaddleOcrModel
import com.wasimaster.wmkeyboard.core.ocr.TesseractOcr
import androidx.compose.runtime.collectAsState
import com.wasimaster.wmkeyboard.core.settings.TranslateEngine
import com.wasimaster.wmkeyboard.core.settings.DeepLWriteStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.settings.HoldRepeatCursorTools
import com.wasimaster.wmkeyboard.core.settings.DefaultToolHoldActions
import com.wasimaster.wmkeyboard.core.settings.holdActionOf
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.settings.LauncherToolSettings
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.ToolBlocker
import com.wasimaster.wmkeyboard.core.settings.toolBlocker
import com.wasimaster.wmkeyboard.core.settings.PowerSavingTrigger
import com.wasimaster.wmkeyboard.core.settings.PowerSavingPreset
import com.wasimaster.wmkeyboard.core.settings.preset
import com.wasimaster.wmkeyboard.core.settings.CursorWordSteps
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.ToolHoldAction
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AltRoute
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Timer10
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Timer3
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TimerOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoSizeSelectActual
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoSizeSelectLarge
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoSizeSelectSmall
import com.wasimaster.wmkeyboard.core.ui.DisabledReason

/**
 * What a press and hold on this tool does while it is pinned to the toolbar.
 *
 * On the tool's own page rather than on a central list under Appearance: that
 * list showed only the pinned tools, so a fresh install found three rows and no
 * way to give any other tool a hold, and nobody looked under Appearance for it
 * anyway (#31). The bound tool runs whether or not it is itself on the toolbar.
 *
 * A tool with nothing bound does its default ([holdActionOf]): HeliBoard's
 * pairing where it has one (#639), its own settings page where it has none.
 * The picker's first row is that default, named; a tool with a pairing also
 * gets a "settings page" row, since there the page has become a choice.
 * The caret tools and Selection mode say their reason instead of hiding the
 * row: their hold is already spent, on a repeat or on arming the mode, and
 * someone looking for the row here should find out why.
 */
@Composable
private fun ToolHoldRow(
    repository: SettingsRepository,
    settings: LiveSettings,
    tool: ToolbarTool,
) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    val repeats = settings.watch { tool in HoldRepeatCursorTools && it.textEditing.cursorToolsRepeatOnHold }
    val selects = settings.watch { tool == ToolbarTool.SELECT_MODE && it.textEditing.selectionModeHold }
    val tracks = settings.watch { tool == ToolbarTool.TRACKPAD && it.trackpad.holdToOpen }
    val picksVoiceMode = settings.watch { tool == ToolbarTool.VOICE && it.voiceBar.holdPicksTypingMode }
    val bound = settings.watch { it.toolbarBehavior.holdActions[tool] }
    val resolved = settings.watch { it.toolbarBehavior.holdActionOf(tool) }
    val pairing = DefaultToolHoldActions[tool]
    if (repeats || selects || tracks || picksVoiceMode) {
        // Reads rather than opens: this tool's hold has none to give.
        WmRow(
            title = stringResource(R.string.tooldetail_hold_title),
            subtitle = stringResource(
                when {
                    repeats -> R.string.tooldetail_hold_repeats_subtitle
                    selects -> R.string.tooldetail_hold_selects_subtitle
                    tracks -> R.string.tooldetail_hold_trackpad_subtitle
                    else -> R.string.tooldetail_hold_voice_modes_subtitle
                },
            ),
            icon = SettingsRowIcons[R.string.tooldetail_hold_title],
        )
        return
    }
    NavRow(
        title = R.string.tooldetail_hold_title,
        subtitle = stringResource(R.string.tooldetail_hold_subtitle),
        value = when (resolved) {
            ToolHoldAction.OpenSettings -> stringResource(R.string.tooldetail_hold_settings_value)
            ToolHoldAction.None -> stringResource(R.string.tooldetail_hold_nothing_value)
            is ToolHoldAction.Run -> stringResource(toolTitle(resolved.tool))
        },
    ) { editing = true }
    if (editing) {
        ToolPickerDialog(
            title = stringResource(R.string.tooldetail_hold_pick_title, stringResource(toolTitle(tool))),
            current = (bound as? ToolHoldAction.Run)?.tool,
            // Every tool but this one: holding a tool to run itself is a slow tap.
            options = ToolbarTool.entries.filter { isSupportedTool(it) && it != tool },
            // The default row says what the default is: the pairing (#639),
            // or the settings page for a tool without one.
            noneTitle = stringResource(R.string.tooldetail_hold_default_title),
            noneSubtitle = pairing?.let {
                stringResource(R.string.tooldetail_hold_default_pairing_subtitle, stringResource(toolTitle(it)))
            } ?: stringResource(R.string.tooldetail_hold_settings_value),
            // The third answer (#136): a hold that does nothing at all.
            nothingSubtitle = stringResource(R.string.tooldetail_hold_nothing_subtitle),
            nothingSelected = bound == ToolHoldAction.None,
            // Only where the default is a pairing: elsewhere the page is
            // already what the default row gives.
            settingsSubtitle = pairing?.let { stringResource(R.string.tooldetail_hold_settings_subtitle) },
            settingsSelected = bound == ToolHoldAction.OpenSettings,
            onPickSettings = {
                editing = false
                scope.launch { repository.setToolHoldAction(tool, ToolHoldAction.OpenSettings) }
            },
            onDismiss = { editing = false },
            onPick = { picked ->
                editing = false
                scope.launch { repository.setToolHoldAction(tool, picked?.let(ToolHoldAction::Run)) }
            },
            onPickNothing = {
                editing = false
                scope.launch { repository.setToolHoldAction(tool, ToolHoldAction.None) }
            },
            memoryKey = "tool-hold",
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
 *
 * [nothingSubtitle] is the same shape for a "Does nothing" row (#136), which
 * only the hold picker has a use for: a key that does nothing is a blank, a
 * hold that does nothing is a choice. [nothingSelected] says whether it is the
 * standing one, since the row is not a tool and [current] cannot name it.
 * [settingsSubtitle] is a third such row, "Settings page", for the hold picker
 * of a tool whose default is a pairing (#639); [noneTitle] renames the "None"
 * row, which the hold picker calls "Default".
 */
@Composable
internal fun ToolPickerDialog(
    title: String,
    current: ToolbarTool?,
    options: List<ToolbarTool>,
    onDismiss: () -> Unit,
    onPick: (ToolbarTool?) -> Unit,
    noneSubtitle: String? = null,
    nothingSubtitle: String? = null,
    nothingSelected: Boolean = false,
    onPickNothing: () -> Unit = {},
    noneTitle: String? = null,
    settingsSubtitle: String? = null,
    settingsSelected: Boolean = false,
    onPickSettings: () -> Unit = {},
    /** Which remembered folds and scroll the page opens with; see [ActionPickerPage]. */
    memoryKey: String = "tools",
) {
    // A page of its own now (#665), grouped the way the Tools screen groups
    // them, with the answers that are not a tool above the groups.
    val special = buildList {
        if (noneSubtitle != null) {
            add(
                PickerRow(
                    key = "none",
                    title = noneTitle ?: stringResource(CommonR.string.common_none),
                    subtitle = noneSubtitle,
                    selected = current == null && !nothingSelected && !settingsSelected,
                    onPick = { onPick(null) },
                ),
            )
        }
        if (nothingSubtitle != null) {
            add(
                PickerRow(
                    key = "nothing",
                    title = stringResource(R.string.tooldetail_hold_nothing_value),
                    subtitle = nothingSubtitle,
                    selected = nothingSelected,
                    onPick = onPickNothing,
                ),
            )
        }
        if (settingsSubtitle != null) {
            add(
                PickerRow(
                    key = "settings",
                    title = stringResource(R.string.tooldetail_hold_settings_title),
                    subtitle = settingsSubtitle,
                    selected = settingsSelected,
                    onPick = onPickSettings,
                ),
            )
        }
    }
    val offered = options.toSet()
    val grouped = ToolGroups.withExtensions(LocalExtensions.current)
        .map { (titleRes, tools) -> titleRes to tools.filter { it in offered } }
    val placed = grouped.flatMapTo(HashSet()) { it.second }
    val leftovers = options.filter { it !in placed }
    val groups = buildList {
        if (special.isNotEmpty()) add(PickerGroup("special", null, special))
        for ((titleRes, tools) in grouped) {
            if (tools.isEmpty()) continue
            add(PickerGroup("group-$titleRes", stringResource(titleRes), tools.map { toolPickerRow(it, current, onPick) }))
        }
        if (leftovers.isNotEmpty()) {
            add(
                PickerGroup(
                    "group-other",
                    stringResource(R.string.tools_group_other_title),
                    leftovers.map { toolPickerRow(it, current, onPick) },
                ),
            )
        }
    }
    ActionPickerPage(memoryKey = memoryKey, title = title, groups = groups, onDismiss = onDismiss)
}

/** One tool as a picker row: its name, what it does, and its glyph. */
@Composable
private fun toolPickerRow(tool: ToolbarTool, current: ToolbarTool?, onPick: (ToolbarTool?) -> Unit): PickerRow =
    PickerRow(
        key = tool.name,
        title = stringResource(toolTitle(tool)),
        subtitle = stringResource(toolDescription(tool)),
        selected = current == tool,
        leading = { SlotIcon(IconSlots.forTool(tool), contentDescription = null) },
        onPick = { onPick(tool) },
    )

/** One tool's screen: the enable switch plus every setting the tool has. */
/**
 * Where Word left, Word right and Select word stop (#662): the app's own idea
 * of a word, or the keyboard's, measured at spaces or at punctuation too, and
 * optionally at a capital inside a word. Shared by the three tools' pages,
 * since the setting is one for all three.
 */
@Composable
private fun WordStepsGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val steps = settings.watch { it.textEditing.cursorWordSteps }
    SettingsGroup(stringResource(R.string.tooldetail_word_steps_group), level = SettingLevel.STANDARD) {
        item {
            ChoiceSetting(
                R.string.tooldetail_word_steps_title,
                subtitle = stringResource(R.string.tooldetail_word_steps_subtitle),
                options = listOf(
                    CursorWordSteps.APP to stringResource(R.string.tooldetail_word_steps_app),
                    CursorWordSteps.SPACES to stringResource(R.string.tooldetail_word_steps_spaces),
                    CursorWordSteps.PUNCTUATION to stringResource(R.string.tooldetail_word_steps_punctuation),
                ),
                selected = steps,
                info = stringResource(R.string.tooldetail_word_steps_info),
                default = SettingsDefaults.textEditing.cursorWordSteps,
                detail = { choice ->
                    ChoiceDetail(
                        stringResource(
                            when (choice) {
                                CursorWordSteps.APP -> R.string.tooldetail_word_steps_app_desc
                                CursorWordSteps.SPACES -> R.string.tooldetail_word_steps_spaces_desc
                                CursorWordSteps.PUNCTUATION -> R.string.tooldetail_word_steps_punctuation_desc
                            },
                        ),
                    )
                },
            ) { scope.launch { repository.setCursorWordSteps(it) } }
        }
        item(visible = steps != CursorWordSteps.APP, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.tooldetail_word_steps_capitals_title,
                stringResource(R.string.tooldetail_word_steps_capitals_subtitle),
                settings.watch { it.textEditing.cursorWordStopsAtCapitals },
                default = SettingsDefaults.textEditing.cursorWordStopsAtCapitals,
            ) { scope.launch { repository.setCursorWordStopsAtCapitals(it) } }
        }
        item(visible = steps != CursorWordSteps.APP, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.tooldetail_word_steps_digits_title,
                stringResource(R.string.tooldetail_word_steps_digits_subtitle),
                settings.watch { it.textEditing.cursorWordStopsAtDigits },
                default = SettingsDefaults.textEditing.cursorWordStopsAtDigits,
            ) { scope.launch { repository.setCursorWordStopsAtDigits(it) } }
        }
    }
}

/** The tools whose Add to home screen opens their screen in this app, not the keyboard. */
private val ScreenShortcutTools = setOf(ToolbarTool.CLOCK, ToolbarTool.NOTES, ToolbarTool.TODO)

@Composable
internal fun ToolDetailSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    tool: ToolbarTool,
    onNavigate: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // What decides which rows the first group holds; each row reads its own value.
    val toolOn = settings.watch { tool in it.enabledTools }
    val coloredIcons = settings.watch { it.coloredToolIcons }
    val gradient = settings.watch { it.toolIconGradients }
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
            // A blocked tool cannot be switched on; what unblocks it is further
            // down this same screen (the key field, the Key layouts link), and
            // the subtitle names it rather than guessing a key. A hold on the
            // greyed switch says the same, with nowhere else to send the user.
            val blocker = settings.watch { toolBlocker(tool, it) }
            val usable = blocker == null
            val why = when (blocker) {
                null -> stringResource(R.string.tooldetail_enabled_subtitle)
                ToolBlocker.NEEDS_SEARCH_KEY ->
                    stringResource(R.string.tooldetail_enabled_needs_key_subtitle)
                ToolBlocker.NEEDS_SEARCH_INSTANCE ->
                    stringResource(R.string.tooldetail_enabled_needs_instance_subtitle)
                ToolBlocker.NEEDS_SECONDARY_LAYOUT ->
                    stringResource(R.string.tooldetail_enabled_needs_layout_subtitle)
                ToolBlocker.VOICE_OFF ->
                    stringResource(R.string.tooldetail_enabled_voice_off_subtitle)
                ToolBlocker.NEEDS_MIX_SOURCE ->
                    stringResource(R.string.tooldetail_enabled_needs_mix_source_subtitle)
                ToolBlocker.NEEDS_MUSIC_PROVIDER ->
                    stringResource(R.string.tooldetail_enabled_needs_music_provider_subtitle)
            }
            ToggleSetting(
                CommonR.string.common_enable,
                why,
                usable && toolOn,
                switchKey = landingKey("switch"),
                enabled = usable,
                disabledReason = DisabledReason(why),
                default = usable && tool in SettingsDefaults.enabledTools,
            ) { scope.launch { repository.setToolEnabled(tool, it) } }
        }
        // Any tool with a panel can go on the home screen (#970). The clock,
        // notes and to-do list pin their own screens instead, further down.
        item(
            visible = KeyboardAutomation.panelTool(tool.name) != null && tool !in ScreenShortcutTools,
            level = SettingLevel.STANDARD,
        ) { ToolHomeScreenShortcutRow(tool) }
    }
    when (tool) {
        ToolbarTool.KDE_CONNECT ->
            com.wasimaster.wmkeyboard.app.kdeconnect.KdeConnectToolSettings(repository, settings, onNavigate)
        // The tool is a shortcut to a screen, and its page leads to it too (#390).
        ToolbarTool.STATISTICS -> SettingsGroup {
            item {
                NavRow(
                    R.string.statistics_title,
                    stringResource(R.string.statistics_subtitle),
                    route = "statistics",
                ) { onNavigate("statistics") }
            }
        }
        // The search history lives in its own file, not in the settings (see
        // FindHistoryPrefs), so this page reads and writes that file directly.
        ToolbarTool.FIND_REPLACE -> {
            val historyContext = LocalContext.current
            val history = remember { com.wasimaster.wmkeyboard.ime.FindHistoryPrefs(historyContext) }
            var remembering by remember { mutableStateOf(history.enabled) }
            var count by remember { mutableStateOf(history.entries().size) }
            SettingsGroup(stringResource(R.string.tooldetail_options_group), level = SettingLevel.STANDARD) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_find_history_title,
                        stringResource(R.string.tooldetail_find_history_subtitle),
                        remembering,
                        default = true,
                    ) {
                        history.enabled = it
                        remembering = it
                        count = history.entries().size
                    }
                }
                item(visible = remembering) {
                    ActionRow(
                        R.string.tooldetail_find_history_clear_title,
                        subtitle = pluralStringResource(R.plurals.tooldetail_find_history_count, count, count),
                        action = stringResource(R.string.tooldetail_find_history_clear_action),
                        enabled = count > 0,
                    ) {
                        history.clear()
                        count = 0
                    }
                }
            }
        }
        ToolbarTool.MEDIA_CONTROL -> {
            // Re-read whenever this screen comes back to the foreground: the
            // grant is made on a system screen, so the user leaves, ticks the
            // listener, and returns to a row that has to have noticed.
            val hasAccess = rememberGrantState(::hasNotificationAccess)
            val openAccess = rememberDisclosedSpecialAccess(SpecialAccess.NOTIFICATIONS)
            // Decides which rows the group holds; the rows read everything else.
            val pinWhilePlaying = settings.watch { it.mediaControl.pinWhilePlaying }
            SettingsGroup(
                stringResource(R.string.tooldetail_mediactl_group),
                info = stringResource(R.string.tooldetail_mediactl_info),
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_mediactl_pin_title,
                        stringResource(R.string.tooldetail_mediactl_pin_subtitle),
                        pinWhilePlaying,
                        info = stringResource(R.string.tooldetail_mediactl_pin_info),
                        default = SettingsDefaults.mediaControl.pinWhilePlaying,
                    ) { scope.launch { repository.setMediaPinWhilePlaying(it) } }
                }
                item(visible = pinWhilePlaying, level = SettingLevel.STANDARD) {
                    NavRow(
                        title = R.string.tooldetail_mediactl_apps_title,
                        subtitle = stringResource(
                            R.string.tooldetail_mediactl_apps_subtitle,
                            settings.watch { it.mediaControl.musicApps.size },
                        ),
                    ) { onNavigate(MusicApps.ROUTE) }
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
        ToolbarTool.APP_LAUNCHER -> {
            val launcherContext = LocalContext.current
            // Re-read on resume: turning the helper on in Settings ›
            // Accessibility is what brings split screen in below Android 12L.
            val splitHelperOn = rememberGrantState { SplitScreenHelperService.isEnabled(it) }
            val askSplitHelper = rememberDisclosedSpecialAccess(SpecialAccess.SPLIT_SCREEN)
            val splitSupported = remember(splitHelperOn) { AppLaunchModes.splitSupported(launcherContext) }
            // What decides which rows the group holds; each row reads its own value.
            val recentsOn = settings.watch { it.launcher.recentsEnabled }
            val drilldown = settings.watch { it.launcher.activityDrilldown }
            SettingsGroup(stringResource(R.string.tooldetail_launcher_group)) {
                item(level = SettingLevel.STANDARD) {
                    // Split screen is left out where the keyboard cannot start
                    // it (before Android 12L); a stored choice of it reads as
                    // Normally there, which is also what a tap then does.
                    val openModes = buildList {
                        add(
                            LauncherOpenMode.NORMAL to
                                stringResource(R.string.tooldetail_launcher_open_mode_normal_label),
                        )
                        add(
                            LauncherOpenMode.FLOATING to
                                stringResource(R.string.tooldetail_launcher_open_mode_floating_label),
                        )
                        if (splitSupported) {
                            add(
                                LauncherOpenMode.SPLIT to
                                    stringResource(R.string.tooldetail_launcher_open_mode_split_label),
                            )
                        }
                    }
                    val openMode = settings.watch { it.launcher.openMode }
                    ChoiceSetting(
                        R.string.tooldetail_launcher_open_mode_title,
                        subtitle = stringResource(R.string.tooldetail_launcher_open_mode_subtitle),
                        info = stringResource(
                            if (splitSupported) R.string.tooldetail_launcher_open_mode_info
                            else R.string.tooldetail_launcher_open_mode_info_no_split,
                        ),
                        options = openModes,
                        selected = if (openModes.any { it.first == openMode }) openMode else LauncherOpenMode.NORMAL,
                        default = SettingsDefaults.launcher.openMode,
                    ) { scope.launch { repository.setLauncherOpenMode(it) } }
                }
                item(
                    visible = ChannelFeatures.SPLIT_SCREEN_HELPER && AppLaunchModes.splitHelperApplies,
                    level = SettingLevel.ADVANCED,
                ) {
                    NavRow(
                        R.string.tooldetail_launcher_split_helper_title,
                        subtitle = stringResource(
                            if (splitHelperOn) {
                                R.string.tooldetail_launcher_split_helper_on_subtitle
                            } else {
                                R.string.tooldetail_launcher_split_helper_off_subtitle
                            },
                        ),
                    ) { askSplitHelper() }
                }
                item(visible = splitSupported, level = SettingLevel.ADVANCED) {
                    val count = settings.watch { it.launcher.combos.size }
                    NavRow(
                        title = R.string.tooldetail_launcher_combos_title,
                        subtitle = if (count == 0) {
                            stringResource(R.string.tooldetail_launcher_combos_none_subtitle)
                        } else {
                            pluralStringResource(R.plurals.tooldetail_launcher_combos_count, count, count)
                        },
                    ) { onNavigate(LauncherCombos.ROUTE) }
                }
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
                        selected = settings.watch { it.launcher.sortOrder },
                        default = SettingsDefaults.launcher.sortOrder,
                    ) { scope.launch { repository.setLauncherSortOrder(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_launcher_labels_title,
                        stringResource(R.string.tooldetail_launcher_labels_subtitle),
                        settings.watch { it.launcher.showLabels },
                        default = SettingsDefaults.launcher.showLabels,
                    ) { scope.launch { repository.setLauncherShowLabels(it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    val columnsFormat = stringResource(R.string.values_number)
                    ChoiceSetting(
                        R.string.tooldetail_launcher_columns_title,
                        subtitle = stringResource(R.string.tooldetail_launcher_columns_subtitle),
                        info = stringResource(R.string.tooldetail_launcher_columns_info),
                        options = listOf(
                            LauncherToolSettings.AUTO_COLUMNS to
                                stringResource(R.string.tooldetail_launcher_columns_auto_label),
                        ) + LauncherToolSettings.COLUMNS_RANGE.map { it to columnsFormat.format(it) },
                        selected = settings.watch { it.launcher.gridColumns },
                        default = SettingsDefaults.launcher.gridColumns,
                    ) { scope.launch { repository.setLauncherGridColumns(it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    val dpFormat = stringResource(R.string.typing_value_dp)
                    SliderSetting(
                        R.string.tooldetail_launcher_icon_size_title,
                        subtitle = stringResource(R.string.tooldetail_launcher_icon_size_subtitle),
                        value = settings.watch { it.launcher.iconSizeDp }.toFloat(),
                        range = LauncherToolSettings.ICON_SIZE_RANGE.first.toFloat()..
                            LauncherToolSettings.ICON_SIZE_RANGE.last.toFloat(),
                        display = { dpFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.tooldetail_launcher_icon_size_info),
                        default = SettingsDefaults.launcher.iconSizeDp.toFloat(),
                    ) { scope.launch { repository.setLauncherIconSize(it.roundToInt()) } }
                }
                item(level = SettingLevel.STANDARD) {
                    ChoiceSetting(
                        R.string.tooldetail_launcher_icon_shape_title,
                        subtitle = stringResource(R.string.tooldetail_launcher_icon_shape_subtitle),
                        info = stringResource(R.string.tooldetail_launcher_icon_shape_info),
                        options = listOf(
                            LauncherIconShape.CIRCLE to
                                stringResource(R.string.tooldetail_launcher_icon_shape_circle_label),
                            LauncherIconShape.ROUNDED to
                                stringResource(R.string.tooldetail_launcher_icon_shape_rounded_label),
                            LauncherIconShape.SYSTEM to
                                stringResource(R.string.tooldetail_launcher_icon_shape_system_label),
                        ),
                        selected = settings.watch { it.launcher.iconShape },
                        default = SettingsDefaults.launcher.iconShape,
                    ) { scope.launch { repository.setLauncherIconShape(it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        R.string.tooldetail_launcher_recents_title,
                        stringResource(R.string.tooldetail_launcher_recents_subtitle),
                        recentsOn,
                        info = stringResource(R.string.tooldetail_launcher_recents_info),
                        default = SettingsDefaults.launcher.recentsEnabled,
                    ) { scope.launch { repository.setLauncherRecentsEnabled(it) } }
                }
                item(visible = recentsOn, level = SettingLevel.ADVANCED) {
                    val appsFormat = stringResource(R.string.values_number)
                    SliderSetting(
                        R.string.tooldetail_launcher_recents_count_title,
                        subtitle = stringResource(
                            R.string.tooldetail_launcher_recents_count_subtitle,
                        ),
                        value = settings.watch { it.launcher.maxRecents }.toFloat(),
                        range = LauncherToolSettings.RECENTS_RANGE.first.toFloat()..
                            LauncherToolSettings.RECENTS_RANGE.last.toFloat(),
                        display = { appsFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.tooldetail_launcher_recents_count_info),
                        default = SettingsDefaults.launcher.maxRecents.toFloat(),
                    ) { scope.launch { repository.setLauncherMaxRecents(it.roundToInt()) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_launcher_drilldown_title,
                        stringResource(R.string.tooldetail_launcher_drilldown_subtitle),
                        drilldown,
                        info = stringResource(R.string.tooldetail_launcher_drilldown_info),
                        default = SettingsDefaults.launcher.activityDrilldown,
                    ) { scope.launch { repository.setLauncherActivityDrilldown(it) } }
                }
                // Private screens are listed inside the drill-down sheet and
                // nowhere else, so without the drill-down there is no list for
                // this to add to.
                if (drilldown) item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_launcher_non_exported_title,
                        stringResource(R.string.tooldetail_launcher_non_exported_subtitle),
                        settings.watch { it.launcher.showNonExported },
                        info = stringResource(R.string.tooldetail_launcher_non_exported_info),
                        default = SettingsDefaults.launcher.showNonExported,
                    ) { scope.launch { repository.setLauncherShowNonExported(it) } }
                }
                item {
                    // A reset rather than a list: each hidden app is one
                    // search away in the panel and unhides from its own page,
                    // so this row is only the way back for all of them at once.
                    val hiddenCount = settings.watch { it.launcher.hidden.size }
                    WmRow(
                        title = stringResource(R.string.tooldetail_launcher_hidden_title),
                        subtitle = if (hiddenCount == 0) {
                            stringResource(R.string.tooldetail_launcher_hidden_none_subtitle)
                        } else {
                            pluralStringResource(
                                R.plurals.tooldetail_launcher_hidden_count,
                                hiddenCount,
                                hiddenCount,
                            )
                        },
                        icon = SettingsRowIcons[R.string.tooldetail_launcher_hidden_title],
                        enabled = hiddenCount > 0,
                        highlightKey = R.string.tooldetail_launcher_hidden_title,
                        onClick = { scope.launch { repository.clearLauncherHidden() } },
                    )
                }
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        R.string.tooldetail_launcher_work_title,
                        stringResource(R.string.tooldetail_launcher_work_subtitle),
                        settings.watch { it.launcher.showWorkApps },
                        info = stringResource(R.string.tooldetail_launcher_work_info),
                        default = SettingsDefaults.launcher.showWorkApps,
                    ) { scope.launch { repository.setLauncherShowWorkApps(it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    // Folders are made, filled and renamed in the panel itself;
                    // this row is the way out of all of them at once, like the
                    // hidden apps row above.
                    val folderCount = settings.watch { it.launcher.folders.size }
                    WmRow(
                        title = stringResource(R.string.tooldetail_launcher_folders_title),
                        subtitle = if (folderCount == 0) {
                            stringResource(R.string.tooldetail_launcher_folders_none_subtitle)
                        } else {
                            pluralStringResource(
                                R.plurals.tooldetail_launcher_folders_count,
                                folderCount,
                                folderCount,
                            )
                        },
                        icon = SettingsRowIcons[R.string.tooldetail_launcher_folders_title],
                        enabled = folderCount > 0,
                        highlightKey = R.string.tooldetail_launcher_folders_title,
                        onClick = { scope.launch { repository.clearLauncherFolders() } },
                    )
                }
            }
        }
        ToolbarTool.PLUGINS -> SettingsGroup(stringResource(R.string.tooldetail_plugins_group)) {
            item {
                WmRow(
                    title = stringResource(R.string.tooldetail_plugins_manage_title),
                    icon = SettingsRowIcons[R.string.tooldetail_plugins_manage_title],
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
            // The panel's gear lands here, so the panel's own layout setting
            // sits here too rather than one screen deeper (#471). Same key as
            // the row on the Text expansion screen.
            item(level = SettingLevel.STANDARD) {
                SliderSetting(
                    R.string.expander_grid_columns_title,
                    subtitle = stringResource(R.string.expander_grid_columns_subtitle),
                    value = settings.watch { it.suggestionStrip.snippetGridColumns }.toFloat(),
                    range = 1f..4f,
                    display = { it.roundToInt().toString() },
                    info = stringResource(R.string.expander_grid_columns_info),
                    default = SettingsDefaults.suggestionStrip.snippetGridColumns.toFloat(),
                ) { scope.launch { repository.setSnippetGridColumns(it.roundToInt()) } }
            }
            item(level = SettingLevel.STANDARD) {
                ToggleSetting(
                    R.string.expander_titles_only_title,
                    stringResource(R.string.expander_titles_only_subtitle),
                    settings.watch { it.suggestionStrip.snippetTitlesOnly },
                    default = SettingsDefaults.suggestionStrip.snippetTitlesOnly,
                ) { scope.launch { repository.setSnippetTitlesOnly(it) } }
            }
            item(level = SettingLevel.STANDARD) {
                ToggleSetting(
                    R.string.expander_panel_stays_title,
                    stringResource(R.string.expander_panel_stays_subtitle),
                    settings.watch { it.suggestionStrip.snippetPanelStays },
                    default = SettingsDefaults.suggestionStrip.snippetPanelStays,
                ) { scope.launch { repository.setSnippetPanelStays(it) } }
            }
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
        ToolbarTool.PERSISTENT -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                NavRow(
                    R.string.tooldetail_layout_nav_title,
                    stringResource(R.string.tooldetail_layout_nav_persistent_subtitle),
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
                    settings.watch { it.sensorTools.flashlightAutoOff },
                    info = stringResource(R.string.tooldetail_flashlight_auto_off_info),
                    default = SettingsDefaults.sensorTools.flashlightAutoOff,
                ) { scope.launch { repository.setFlashlightAutoOff(it) } }
            }
        }
        ToolbarTool.COMPASS -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group), level = SettingLevel.STANDARD) {
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_compass_degrees_title,
                        stringResource(R.string.tooldetail_compass_degrees_subtitle),
                        settings.watch { it.sensorTools.compassDegrees },
                        default = SettingsDefaults.sensorTools.compassDegrees,
                    ) { scope.launch { repository.setCompassShowDegrees(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_compass_qibla_title,
                        stringResource(R.string.tooldetail_compass_qibla_subtitle),
                        settings.watch { it.sensorTools.compassQibla },
                        info = stringResource(R.string.tooldetail_compass_qibla_info),
                        default = SettingsDefaults.sensorTools.compassQibla,
                    ) { scope.launch { repository.setCompassShowQibla(it) } }
                }
            }
            if (settings.watch { it.sensorTools.compassQibla && it.weather.latitude == null }) {
                StateBanner(
                    stringResource(R.string.tooldetail_compass_no_location_error),
                    action = stringResource(toolTitle(ToolbarTool.WEATHER)),
                    tone = BannerTone.WARNING,
                ) { onNavigate(toolRoute(ToolbarTool.WEATHER)) }
            }
        }
        ToolbarTool.LEVEL -> SettingsGroup(stringResource(R.string.tooldetail_options_group), level = SettingLevel.ADVANCED) {
            item {
                ToggleSetting(
                    R.string.tooldetail_level_angles_title,
                    stringResource(R.string.tooldetail_level_angles_subtitle),
                    settings.watch { it.sensorTools.levelAngles },
                    default = SettingsDefaults.sensorTools.levelAngles,
                ) { scope.launch { repository.setLevelShowAngles(it) } }
            }
        }
        ToolbarTool.UNDO, ToolbarTool.REDO ->
            SettingsGroup(stringResource(R.string.tooldetail_options_group), level = SettingLevel.ADVANCED) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_redo_ctrl_y_title,
                        stringResource(R.string.tooldetail_redo_ctrl_y_subtitle),
                        settings.watch { it.redoUsesCtrlY },
                        info = stringResource(R.string.tooldetail_redo_ctrl_y_info),
                        default = SettingsDefaults.redoUsesCtrlY,
                    ) { scope.launch { repository.setRedoUsesCtrlY(it) } }
                }
            }
        ToolbarTool.MOON_PHASE -> SettingsGroup(stringResource(R.string.tooldetail_options_group), level = SettingLevel.ADVANCED) {
            item {
                ToggleSetting(
                    R.string.tooldetail_moon_southern_title,
                    stringResource(R.string.tooldetail_moon_southern_subtitle),
                    settings.watch { it.sensorTools.moonSouthern },
                    // Not SettingsDefaults: this one starts from the device's
                    // region, so reset has to land back on that and not on
                    // the northern hemisphere the data class declares.
                    default = isSouthernHemisphere(repository.deviceRegion),
                ) { scope.launch { repository.setMoonSouthernHemisphere(it) } }
            }
            item {
                ToggleSetting(
                    R.string.tooldetail_moon_arabic_title,
                    stringResource(R.string.tooldetail_moon_arabic_subtitle),
                    settings.watch { it.sensorTools.moonArabicNames },
                    default = SettingsDefaults.sensorTools.moonArabicNames,
                ) { scope.launch { repository.setMoonArabicNames(it) } }
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
                        settings.watch { it.weather.fahrenheit },
                        default = SettingsDefaults.weather.fahrenheit,
                    ) { scope.launch { repository.setWeatherFahrenheit(it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        R.string.tooldetail_weather_auto_fetch_title,
                        stringResource(R.string.tooldetail_weather_auto_fetch_subtitle),
                        settings.watch { it.weather.autoFetch },
                        info = stringResource(R.string.tooldetail_weather_auto_fetch_info),
                        default = SettingsDefaults.weather.autoFetch,
                    ) { scope.launch { repository.setWeatherAutoFetch(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    val weatherMinutesFormat = stringResource(R.string.values_minutes)
                    SliderSetting(
                        R.string.tooldetail_weather_refresh_title,
                        subtitle = stringResource(R.string.tooldetail_weather_refresh_subtitle),
                        value = settings.watch { it.toolLimits.weatherRefreshMinutes }.toFloat(),
                        range = 1f..180f,
                        display = { weatherMinutesFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.tooldetail_weather_refresh_info),
                        default = SettingsDefaults.toolLimits.weatherRefreshMinutes.toFloat(),
                    ) { picked ->
                        scope.launch { repository.setWeatherRefreshMinutes(picked.roundToInt()) }
                    }
                }
                serverItems(
                    repository,
                    settings,
                    endpoints = listOf(ServiceEndpoint.OPEN_METEO, ServiceEndpoint.OPEN_METEO_GEOCODING),
                    level = SettingLevel.ADVANCED,
                )
            }
        }
        ToolbarTool.CALENDAR -> {
            // Decides which rows the group holds; the rows read everything else.
            val showsHijri = settings.watch {
                it.calendarTool.altOne == AltCalendar.HIJRI || it.calendarTool.altTwo == AltCalendar.HIJRI
            }
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
                        selected = settings.watch { it.calendarTool.altOne },
                        onChange = { scope.launch { repository.setCalendarAltOne(it) } },
                    )
                }
                item(level = SettingLevel.ADVANCED) {
                    AltCalendarSetting(
                        title = stringResource(R.string.tooldetail_calendar_second_title),
                        subtitle = stringResource(R.string.tooldetail_calendar_second_subtitle),
                        selected = settings.watch { it.calendarTool.altTwo },
                        onChange = { scope.launch { repository.setCalendarAltTwo(it) } },
                    )
                }
                item(level = SettingLevel.STANDARD) {
                    WeekendSetting(settings.watch { it.calendarTool.weekend }) {
                        scope.launch { repository.setCalendarWeekend(it) }
                    }
                }
                item(visible = showsHijri, level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.tooldetail_calendar_hijri_title,
                        subtitle = stringResource(R.string.tooldetail_calendar_hijri_subtitle),
                        value = settings.watch { it.calendarTool.hijriAdjustDays }.toFloat(),
                        range = -2f..2f,
                        display = { days ->
                            val d = days.roundToInt()
                            if (d > 0) daysAheadFormat.format(d) else daysFormat.format(d)
                        },
                        info = stringResource(R.string.tooldetail_calendar_hijri_info),
                        default = SettingsDefaults.calendarTool.hijriAdjustDays.toFloat(),
                    ) { scope.launch { repository.setHijriAdjustDays(it.roundToInt()) } }
                }
            }
            CalendarNewEventRows(repository, settings)
        }
        ToolbarTool.CAMERA -> {
            // What decides which rows the search group holds; each row reads
            // its own value.
            val onWeb = settings.watch { it.camera.searchWith == PhotoSearchTarget.WEB }
            val customEngine = settings.watch { it.camera.searchEngine == PhotoSearchEngine.CUSTOM }
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_front_title,
                        stringResource(R.string.tooldetail_camera_front_subtitle),
                        settings.watch { it.camera.preferFront },
                        default = SettingsDefaults.camera.preferFront,
                    ) { scope.launch { repository.setCameraPreferFront(it) } }
                }
                item(level = SettingLevel.STANDARD) {
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
                        selected = settings.watch { it.camera.timerSeconds },
                        info = stringResource(R.string.tooldetail_camera_timer_info),
                        default = SettingsDefaults.camera.timerSeconds,
                        detail = { seconds ->
                            ChoiceDetail(
                                icon = when (seconds) {
                                    3 -> Icons.Outlined.Timer3
                                    10 -> Icons.Outlined.Timer10
                                    else -> Icons.Outlined.TimerOff
                                },
                            )
                        },
                    ) { scope.launch { repository.setCameraTimerSeconds(it) } }
                }
                item(level = SettingLevel.STANDARD) {
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
                        selected = settings.watch { it.camera.captureMaxPx },
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
                                when {
                                    px <= 1000 -> Icons.Outlined.PhotoSizeSelectSmall
                                    px <= 1600 -> Icons.Outlined.PhotoSizeSelectLarge
                                    else -> Icons.Outlined.PhotoSizeSelectActual
                                },
                            )
                        },
                    ) { scope.launch { repository.setCameraCaptureMaxPx(it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        R.string.tooldetail_camera_mirror_title,
                        stringResource(R.string.tooldetail_camera_mirror_subtitle),
                        settings.watch { it.camera.mirrorFront },
                        info = stringResource(R.string.tooldetail_camera_mirror_info),
                        default = SettingsDefaults.camera.mirrorFront,
                    ) { scope.launch { repository.setCameraMirrorFront(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_camera_fullframe_title,
                        stringResource(R.string.tooldetail_camera_fullframe_subtitle),
                        settings.watch { it.camera.fullFrame },
                        info = stringResource(R.string.tooldetail_camera_fullframe_info),
                        default = SettingsDefaults.camera.fullFrame,
                    ) { scope.launch { repository.setCameraFullFrame(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_gallery_title,
                        stringResource(R.string.tooldetail_camera_gallery_subtitle),
                        settings.watch { it.camera.saveToGallery },
                        info = stringResource(R.string.tooldetail_camera_gallery_info),
                        default = SettingsDefaults.camera.saveToGallery,
                    ) { scope.launch { repository.setCameraSaveToGallery(it) } }
                }
            }
            SettingsGroup(
                stringResource(R.string.location_camera_group),
                info = stringResource(R.string.location_camera_info),
                level = SettingLevel.STANDARD,
            ) {
                item {
                    ToggleSetting(
                        R.string.location_camera_stamp_title,
                        stringResource(R.string.location_camera_stamp_subtitle),
                        settings.watch { it.camera.locationStamp },
                        default = SettingsDefaults.camera.locationStamp,
                    ) { scope.launch { repository.setCameraLocationStamp(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.location_camera_caption_title,
                        stringResource(R.string.location_camera_caption_subtitle),
                        settings.watch { it.camera.locationCaption },
                        info = stringResource(R.string.location_camera_caption_info),
                        default = SettingsDefaults.camera.locationCaption,
                    ) { scope.launch { repository.setCameraLocationCaption(it) } }
                }
            }
            SettingsGroup(
                stringResource(R.string.tooldetail_camera_search_group),
                info = stringResource(R.string.tooldetail_camera_search_info),
                level = SettingLevel.STANDARD,
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_search_button_title,
                        stringResource(R.string.tooldetail_camera_search_button_subtitle),
                        settings.watch { it.camera.searchButton },
                        default = SettingsDefaults.camera.searchButton,
                    ) { scope.launch { repository.setCameraSearchButton(it) } }
                }
                item {
                    ChoiceSetting(
                        R.string.tooldetail_camera_search_with_title,
                        info = stringResource(R.string.tooldetail_camera_search_with_info),
                        options = listOf(
                            PhotoSearchTarget.LENS to stringResource(R.string.tooldetail_camera_search_with_lens),
                            PhotoSearchTarget.WEB to stringResource(R.string.tooldetail_camera_search_with_web),
                            PhotoSearchTarget.SHARE to stringResource(R.string.tooldetail_camera_search_with_share),
                        ),
                        selected = settings.watch { it.camera.searchWith },
                        default = SettingsDefaults.camera.searchWith,
                        detail = { target ->
                            ChoiceDetail(
                                stringResource(
                                    when (target) {
                                        PhotoSearchTarget.LENS -> R.string.tooldetail_camera_search_with_lens_desc
                                        PhotoSearchTarget.WEB -> R.string.tooldetail_camera_search_with_web_desc
                                        PhotoSearchTarget.SHARE -> R.string.tooldetail_camera_search_with_share_desc
                                    },
                                ),
                            )
                        },
                    ) { scope.launch { repository.setCameraSearchWith(it) } }
                }
                item(visible = onWeb) {
                    ChoiceSetting(
                        R.string.tooldetail_camera_search_engine_title,
                        info = stringResource(R.string.tooldetail_camera_search_engine_info),
                        options = listOf(
                            PhotoSearchEngine.GOOGLE_LENS to stringResource(R.string.tooldetail_camera_search_engine_lens),
                            PhotoSearchEngine.BING to stringResource(R.string.tooldetail_camera_search_engine_bing),
                            PhotoSearchEngine.YANDEX to stringResource(R.string.tooldetail_camera_search_engine_yandex),
                            PhotoSearchEngine.TINEYE to stringResource(R.string.tooldetail_camera_search_engine_tineye),
                            PhotoSearchEngine.CUSTOM to stringResource(R.string.tooldetail_camera_search_engine_custom),
                        ),
                        selected = settings.watch { it.camera.searchEngine },
                        default = SettingsDefaults.camera.searchEngine,
                    ) { scope.launch { repository.setCameraSearchEngine(it) } }
                }
                item(visible = onWeb && customEngine, level = SettingLevel.ADVANCED) {
                    TextFieldSetting(
                        label = stringResource(R.string.tooldetail_camera_search_custom_url_label),
                        value = settings.watch { it.camera.searchCustomUrl },
                        hint = stringResource(R.string.tooldetail_camera_search_custom_url_hint),
                        default = SettingsDefaults.camera.searchCustomUrl,
                    ) { repository.setCameraSearchCustomUrl(it) }
                }
                item(visible = onWeb && customEngine, level = SettingLevel.ADVANCED) {
                    TextFieldSetting(
                        label = stringResource(R.string.tooldetail_camera_search_custom_field_label),
                        value = settings.watch { it.camera.searchCustomField },
                        hint = stringResource(R.string.tooldetail_camera_search_custom_field_hint),
                        default = SettingsDefaults.camera.searchCustomField,
                    ) { repository.setCameraSearchCustomField(it) }
                }
            }
            // Issue #727: a Translate button on the confirm step, shown once
            // a provider here can take the photo.
            ProvidersGroup(
                repository,
                settings,
                ProviderTool.PHOTO_TRANSLATE,
                titleRes = R.string.tooldetail_camera_translate_group,
                info = stringResource(R.string.tooldetail_camera_translate_info),
                level = SettingLevel.STANDARD,
            )
            SettingsGroup(
                stringResource(R.string.tooldetail_camera_feedback_group),
                info = stringResource(R.string.tooldetail_camera_info),
                level = SettingLevel.STANDARD,
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_shutter_title,
                        stringResource(R.string.tooldetail_camera_shutter_subtitle),
                        settings.watch { it.camera.shutterSound },
                        default = SettingsDefaults.camera.shutterSound,
                    ) { scope.launch { repository.setCameraShutterSound(it) } }
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
                        settings.watch { it.dictionaryAutoLookup },
                        default = SettingsDefaults.dictionaryAutoLookup,
                    ) { scope.launch { repository.setDictionaryAutoLookup(it) } }
                }
            }
            // Where the tool looks, in the order it asks: a provider is only
            // asked when those above it did not know the word or were unreachable.
            // The packs' screen link, the offline dictionaries' downloads, the
            // imported dictionaries (#765) and the server addresses are on the
            // sources' own screens.
            ProvidersGroup(
                repository,
                settings,
                ProviderTool.DICTIONARY,
                titleRes = R.string.tooldetail_dictionary_sources_title,
                info = stringResource(R.string.tooldetail_dictionary_sources_info),
            )
            DictionaryTranslationsGroup(repository, settings)
            // The same look-up as a card over other apps (#765).
            LookupSettingsGroup(repository, settings, onNavigate)
        }
        ToolbarTool.TEXT_EDIT -> SettingsGroup(stringResource(R.string.tooldetail_options_group), level = SettingLevel.ADVANCED) {
            item {
                SliderSetting(
                    R.string.tooldetail_text_edit_repeat_title,
                    subtitle = stringResource(R.string.tooldetail_text_edit_repeat_subtitle),
                    value = settings.watch { it.textEditing.repeatMs }.toFloat(),
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
                ) { onNavigate(panelEditRoute(PanelKind.TEXT_EDIT)) }
            }
        }
        ToolbarTool.TRACKPAD -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_trackpad_info),
                level = SettingLevel.STANDARD,
            ) {
                item(level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.tooldetail_trackpad_step_x_title,
                        subtitle = stringResource(R.string.tooldetail_trackpad_step_x_subtitle),
                        value = settings.watch { it.trackpad.stepXDp }.toFloat(),
                        range = 4f..48f,
                        display = { dpFormat.format(it.toInt()) },
                        default = SettingsDefaults.trackpad.stepXDp.toFloat(),
                    ) { scope.launch { repository.setTrackpadStepXDp(it.toInt()) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.tooldetail_trackpad_step_y_title,
                        subtitle = stringResource(R.string.tooldetail_trackpad_step_y_subtitle),
                        value = settings.watch { it.trackpad.stepYDp }.toFloat(),
                        range = 8f..96f,
                        display = { dpFormat.format(it.toInt()) },
                        default = SettingsDefaults.trackpad.stepYDp.toFloat(),
                    ) { scope.launch { repository.setTrackpadStepYDp(it.toInt()) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_trackpad_hold_title,
                        stringResource(R.string.tooldetail_trackpad_hold_subtitle),
                        settings.watch { it.trackpad.holdToOpen },
                        info = stringResource(R.string.tooldetail_trackpad_hold_info),
                        default = SettingsDefaults.trackpad.holdToOpen,
                    ) { scope.launch { repository.setTrackpadHoldToOpen(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_trackpad_taps_title,
                        stringResource(R.string.tooldetail_trackpad_taps_subtitle),
                        settings.watch { it.trackpad.multiTap },
                        default = SettingsDefaults.trackpad.multiTap,
                    ) { scope.launch { repository.setTrackpadMultiTap(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_trackpad_haptics_title,
                        stringResource(R.string.tooldetail_trackpad_haptics_subtitle),
                        settings.watch { it.trackpad.haptics },
                        default = SettingsDefaults.trackpad.haptics,
                    ) { scope.launch { repository.setTrackpadHaptics(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_trackpad_trail_title,
                        stringResource(R.string.tooldetail_trackpad_trail_subtitle),
                        settings.watch { it.trackpad.trail },
                        default = SettingsDefaults.trackpad.trail,
                    ) { scope.launch { repository.setTrackpadTrail(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_trackpad_magnifier_title,
                        stringResource(R.string.tooldetail_trackpad_magnifier_subtitle),
                        settings.watch { it.trackpad.magnifier },
                        info = stringResource(R.string.tooldetail_trackpad_magnifier_info),
                        default = SettingsDefaults.trackpad.magnifier,
                    ) { scope.launch { repository.setTrackpadMagnifier(it) } }
                }
                // The surface and the keys beside it are a panel layout (issue
                // #63), edited where the text-editing pad is.
                item(level = SettingLevel.ADVANCED) {
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
                    ) { onNavigate(panelEditRoute(PanelKind.TRACKPAD)) }
                }
            }
        }
        ToolbarTool.SELECT_MODE -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_select_mode_info),
                level = SettingLevel.STANDARD,
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_select_mode_hold_title,
                        stringResource(R.string.tooldetail_select_mode_hold_subtitle),
                        settings.watch { it.textEditing.selectionModeHold },
                        info = stringResource(R.string.tooldetail_select_mode_hold_info),
                        default = SettingsDefaults.textEditing.selectionModeHold,
                    ) { scope.launch { repository.setSelectionModeHold(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_select_mode_taps_title,
                        stringResource(R.string.tooldetail_select_mode_taps_subtitle),
                        settings.watch { it.textEditing.selectionModeMultiTap },
                        info = stringResource(R.string.tooldetail_select_mode_taps_info),
                        default = SettingsDefaults.textEditing.selectionModeMultiTap,
                    ) { scope.launch { repository.setSelectionModeMultiTap(it) } }
                }
            }
        }
        // Select word grows over the words beside it, measured as the two word
        // moves measure (#662), so its page carries the same rows.
        ToolbarTool.SELECT_WORD -> WordStepsGroup(repository, settings)
        // The caret movers, which are the only tools a hold repeats. Home, End
        // and the two select tools are not in the set — a second press of those
        // lands exactly where the first one did — so their pages stay plain.
        in HoldRepeatCursorTools -> {
            // Decides which rows the group holds; the rows read everything else.
            val anyRepeat = settings.watch {
                it.textEditing.cursorToolsRepeatOnHold || tool in it.textEditing.toolboxRepeatTools
            }
            SettingsGroup(stringResource(R.string.tooldetail_options_group), level = SettingLevel.STANDARD) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_cursor_repeat_title,
                        stringResource(R.string.tooldetail_cursor_repeat_subtitle),
                        settings.watch { it.textEditing.cursorToolsRepeatOnHold },
                        info = stringResource(R.string.tooldetail_cursor_repeat_info),
                        default = SettingsDefaults.textEditing.cursorToolsRepeatOnHold,
                    ) { scope.launch { repository.setCursorToolsRepeatOnHold(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    // This one is per tool, unlike the switch above: turning it
                    // on costs *this* tool the toolbox hold that opens its
                    // settings page, so it is answered a tool at a time.
                    ToggleSetting(
                        R.string.tooldetail_cursor_repeat_toolbox_title,
                        stringResource(R.string.tooldetail_cursor_repeat_toolbox_subtitle),
                        settings.watch { tool in it.textEditing.toolboxRepeatTools },
                        info = stringResource(R.string.tooldetail_cursor_repeat_toolbox_info),
                        default = tool in SettingsDefaults.textEditing.toolboxRepeatTools,
                    ) { scope.launch { repository.setToolboxRepeat(tool, it) } }
                }
                // The speed is shared, so it shows while either surface repeats.
                if (anyRepeat) {
                    item(level = SettingLevel.ADVANCED) {
                        SliderSetting(
                            R.string.tooldetail_text_edit_repeat_title,
                            subtitle = stringResource(
                                R.string.tooldetail_cursor_repeat_speed_subtitle,
                            ),
                            value = settings.watch { it.textEditing.repeatMs }.toFloat(),
                            range = 30f..200f,
                            display = { msFormat.format(it.toInt()) },
                            default = SettingsDefaults.textEditing.repeatMs.toFloat(),
                        ) { scope.launch { repository.setTextEditRepeatMs(it.toInt()) } }
                    }
                }
            }
            if (tool == ToolbarTool.CURSOR_WORD_LEFT || tool == ToolbarTool.CURSOR_WORD_RIGHT) {
                WordStepsGroup(repository, settings)
            }
        }
        ToolbarTool.NUMPAD -> SettingsGroup(stringResource(R.string.tooldetail_options_group), level = SettingLevel.STANDARD) {
            item {
                ToggleSetting(
                    R.string.tooldetail_numpad_calc_title,
                    stringResource(R.string.tooldetail_numpad_calc_subtitle),
                    settings.watch { it.numpadCalculatorLayout },
                    default = SettingsDefaults.numpadCalculatorLayout,
                ) { scope.launch { repository.setNumpadCalculatorLayout(it) } }
            }
            item(level = SettingLevel.ADVANCED) {
                // The pad is a panel layout now (issue #63): the same editor
                // and the same row the text-editing pad has.
                val customPanels by repository.customPanelLayouts.collectAsStateWithLifecycle(emptyList())
                NavRow(
                    title = R.string.panel_layout_row_title,
                    subtitle = stringResource(R.string.panel_layout_row_subtitle),
                    value = stringResource(
                        if (customPanels.none { it.panel == PanelKind.NUMPAD }) {
                            R.string.panel_layout_value_default
                        } else {
                            R.string.panel_layout_value_custom
                        },
                    ),
                ) { onNavigate(panelEditRoute(PanelKind.NUMPAD)) }
            }
        }
        ToolbarTool.INCOGNITO -> {
            SettingsGroup(stringResource(R.string.tooldetail_incognito_group), level = SettingLevel.STANDARD) {
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_incognito_learning_title,
                        stringResource(R.string.tooldetail_incognito_learning_subtitle),
                        settings.watch { it.incognitoPausesLearning },
                        default = SettingsDefaults.incognitoPausesLearning,
                    ) { scope.launch { repository.setIncognitoPausesLearning(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_incognito_clipboard_title,
                        stringResource(R.string.tooldetail_incognito_clipboard_subtitle),
                        settings.watch { it.incognitoPausesClipboard },
                        default = SettingsDefaults.incognitoPausesClipboard,
                    ) { scope.launch { repository.setIncognitoPausesClipboard(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_incognito_private_clipboard_title,
                        stringResource(R.string.tooldetail_incognito_private_clipboard_subtitle),
                        settings.watch { it.incognitoPrivateClipboard },
                        info = stringResource(R.string.tooldetail_incognito_private_clipboard_info),
                        default = SettingsDefaults.incognitoPrivateClipboard,
                    ) { scope.launch { repository.setIncognitoPrivateClipboard(it) } }
                }
                item {
                    NavRow(
                        R.string.tooldetail_incognito_auto_nav_title,
                        stringResource(R.string.tooldetail_incognito_auto_nav_subtitle),
                        value = stringResource(if (settings.watch { it.autoIncognito }) CommonR.string.common_on else CommonR.string.common_off),
                        route = "privacy",
                        onClick = { onNavigate("privacy") },
                    )
                }
            }
        }
        ToolbarTool.POWER_SAVING -> {
            // Decides which rows the group holds; the rows read everything else.
            val powerTrigger = settings.watch { it.powerSaving.trigger }
            SettingsGroup(stringResource(R.string.tooldetail_power_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_now_title,
                        stringResource(R.string.tooldetail_power_now_subtitle),
                        settings.watch { it.powerSaving.manual },
                        info = stringResource(R.string.tooldetail_power_now_info),
                        default = SettingsDefaults.powerSaving.manual,
                    ) { scope.launch { repository.setPowerSavingManual(it) } }
                }
                // One bundle over the twelve switches below (the audit's
                // "collapse to presets with an advanced expander"): the
                // switches keep their rows, at Everything.
                item(level = SettingLevel.STANDARD) {
                    ChoiceSetting(
                        R.string.tooldetail_power_preset_title,
                        subtitle = stringResource(R.string.tooldetail_power_preset_subtitle),
                        info = stringResource(R.string.tooldetail_power_info),
                        options = PowerSavingPreset.entries.map { it to stringResource(powerPresetLabelRes(it)) },
                        selected = settings.watch { it.powerSaving.preset },
                        default = PowerSavingPreset.BALANCED,
                        detail = { preset -> ChoiceDetail(stringResource(powerPresetDescRes(preset))) },
                    ) { scope.launch { repository.setPowerSavingPreset(it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    ChoiceSetting(
                        R.string.tooldetail_power_trigger_title,
                        subtitle = stringResource(R.string.tooldetail_power_trigger_subtitle),
                        info = stringResource(R.string.tooldetail_power_trigger_info),
                        options = PowerSavingTrigger.entries.map { it to stringResource(it.labelRes) },
                        selected = powerTrigger,
                        default = SettingsDefaults.powerSaving.trigger,
                        detail = { trigger -> ChoiceDetail(stringResource(powerTriggerDescRes(trigger))) },
                    ) { scope.launch { repository.setPowerSavingTrigger(it) } }
                }
                if (powerTrigger == PowerSavingTrigger.LOW_BATTERY ||
                    powerTrigger == PowerSavingTrigger.EITHER
                ) {
                    item(level = SettingLevel.ADVANCED) {
                        SliderSetting(
                            R.string.tooldetail_power_battery_title,
                            subtitle = stringResource(R.string.tooldetail_power_battery_subtitle),
                            value = settings.watch { it.powerSaving.batteryPercent }.toFloat(),
                            range = 5f..50f,
                            display = { percentFormat.format(it.toInt()) },
                            default = SettingsDefaults.powerSaving.batteryPercent.toFloat(),
                        ) { scope.launch { repository.setPowerSavingBatteryPercent(it.toInt()) } }
                    }
                }
                item(visible = powerTrigger != PowerSavingTrigger.OFF, level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_power_charging_title,
                        stringResource(R.string.tooldetail_power_charging_subtitle),
                        settings.watch { it.powerSaving.offWhileCharging },
                        info = stringResource(R.string.tooldetail_power_charging_info),
                        default = SettingsDefaults.powerSaving.offWhileCharging,
                    ) { scope.launch { repository.setPowerSavingOffWhileCharging(it) } }
                }
            }
            SettingsGroup(
                stringResource(R.string.tooldetail_power_drop_group),
                info = stringResource(R.string.tooldetail_power_info),
                level = SettingLevel.ADVANCED,
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_haptics_title,
                        stringResource(R.string.tooldetail_power_drop_haptics_subtitle),
                        settings.watch { it.powerSaving.dropHaptics },
                        default = SettingsDefaults.powerSaving.dropHaptics,
                    ) { scope.launch { repository.setPowerSavingDropHaptics(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_sound_title,
                        stringResource(R.string.tooldetail_power_drop_sound_subtitle),
                        settings.watch { it.powerSaving.dropKeySound },
                        default = SettingsDefaults.powerSaving.dropKeySound,
                    ) { scope.launch { repository.setPowerSavingDropKeySound(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_anim_title,
                        stringResource(R.string.tooldetail_power_drop_anim_subtitle),
                        settings.watch { it.powerSaving.dropAnimations },
                        default = SettingsDefaults.powerSaving.dropAnimations,
                    ) { scope.launch { repository.setPowerSavingDropAnimations(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_trail_title,
                        stringResource(R.string.tooldetail_power_drop_trail_subtitle),
                        settings.watch { it.powerSaving.dropGlideTrail },
                        info = stringResource(R.string.tooldetail_power_drop_trail_info),
                        default = SettingsDefaults.powerSaving.dropGlideTrail,
                    ) { scope.launch { repository.setPowerSavingDropGlideTrail(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_glass_blur_title,
                        stringResource(R.string.tooldetail_power_drop_glass_blur_subtitle),
                        settings.watch { it.powerSaving.dropGlassBlur },
                        default = SettingsDefaults.powerSaving.dropGlassBlur,
                    ) { scope.launch { repository.setPowerSavingDropGlassBlur(it) } }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_power_drop_helpers_group), level = SettingLevel.ADVANCED) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_glide_title,
                        stringResource(R.string.tooldetail_power_drop_glide_subtitle),
                        settings.watch { it.powerSaving.dropGestureTyping },
                        info = stringResource(R.string.tooldetail_power_drop_glide_info),
                        default = SettingsDefaults.powerSaving.dropGestureTyping,
                    ) { scope.launch { repository.setPowerSavingDropGestureTyping(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_emoji_title,
                        stringResource(R.string.tooldetail_power_drop_emoji_subtitle),
                        settings.watch { it.powerSaving.dropEmojiPrediction },
                        default = SettingsDefaults.powerSaving.dropEmojiPrediction,
                    ) { scope.launch { repository.setPowerSavingDropEmojiPrediction(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_chips_title,
                        stringResource(R.string.tooldetail_power_drop_chips_subtitle),
                        settings.watch { it.powerSaving.dropSmartChips },
                        default = SettingsDefaults.powerSaving.dropSmartChips,
                    ) { scope.launch { repository.setPowerSavingDropSmartChips(it) } }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_power_drop_background_group), level = SettingLevel.ADVANCED) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_network_title,
                        stringResource(R.string.tooldetail_power_drop_network_subtitle),
                        settings.watch { it.powerSaving.dropBackgroundNetwork },
                        info = stringResource(R.string.tooldetail_power_drop_network_info),
                        default = SettingsDefaults.powerSaving.dropBackgroundNetwork,
                    ) { scope.launch { repository.setPowerSavingDropBackgroundNetwork(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_screenshot_title,
                        stringResource(R.string.tooldetail_power_drop_screenshot_subtitle),
                        settings.watch { it.powerSaving.dropScreenshotWatch },
                        default = SettingsDefaults.powerSaving.dropScreenshotWatch,
                    ) { scope.launch { repository.setPowerSavingDropScreenshotWatch(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_models_title,
                        stringResource(R.string.tooldetail_power_drop_models_subtitle),
                        settings.watch { it.powerSaving.dropOnDeviceModels },
                        info = stringResource(R.string.tooldetail_power_drop_models_info),
                        default = SettingsDefaults.powerSaving.dropOnDeviceModels,
                    ) { scope.launch { repository.setPowerSavingDropOnDeviceModels(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_media_pin_title,
                        stringResource(R.string.tooldetail_power_drop_media_pin_subtitle),
                        settings.watch { it.powerSaving.dropMediaPin },
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
                    settings.watch { it.correction.enabled },
                    default = SettingsDefaults.correction.enabled,
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
        // The setting is each language's own, on that language's screen, so
        // this page is the way there rather than a second copy of the switch.
        ToolbarTool.PHONETIC_ENGLISH -> {
            // The rows the group holds, one per language with a scheme.
            val phoneticLanguages = settings.watch { s ->
                s.enabledLanguages.filter { PhoneticSchemes.forLanguage(it.id) != null }
            }
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_phonetic_english_info),
            ) {
                for (language in phoneticLanguages) {
                    item {
                        NavRow(
                            language.displayName,
                            stringResource(R.string.tooldetail_phonetic_english_nav_subtitle),
                            route = "language/${language.id}",
                            onClick = { onNavigate("language/${language.id}") },
                        )
                    }
                }
            }
        }
        ToolbarTool.SELECTION_ACTIONS -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_selection_actions_title,
                    stringResource(R.string.tooldetail_selection_actions_subtitle),
                    settings.watch { it.selectionMacros.enabled },
                    default = SettingsDefaults.selectionMacros.enabled,
                ) { scope.launch { repository.setSelectionMacrosEnabled(it) } }
            }
            item {
                NavRow(
                    R.string.tooldetail_selection_actions_nav_title,
                    stringResource(R.string.tooldetail_selection_actions_nav_subtitle),
                    route = SelectionMacroRoute,
                    onClick = { onNavigate(SelectionMacroRoute) },
                )
            }
        }
        ToolbarTool.FANCY -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_fancy_info),
                level = SettingLevel.STANDARD,
            ) {
                item(level = SettingLevel.ADVANCED) {
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
                        selected = settings.watch { it.layoutBehavior.fancyToolStyleId }
                            ?.takeIf { FancyStyles.byId(it) != null },
                    ) { scope.launch { repository.setFancyToolStyle(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_fancy_keep_title,
                        stringResource(R.string.tooldetail_fancy_keep_subtitle),
                        settings.watch { it.layoutBehavior.fancyToolKeepsLanguage },
                        info = stringResource(R.string.tooldetail_fancy_keep_info),
                        default = SettingsDefaults.layoutBehavior.fancyToolKeepsLanguage,
                    ) { scope.launch { repository.setFancyToolKeepsLanguage(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_fancy_auto_off_title,
                        stringResource(R.string.tooldetail_fancy_auto_off_subtitle),
                        settings.watch { it.layoutBehavior.fancyToolAutoOff },
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
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_custom_layout_info),
            ) {
                item {
                    val secondaries = settings.watch {
                        com.wasimaster.wmkeyboard.core.layout.secondaryLayouts(it.customLayouts)
                    }
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
                        selected = settings.watch { it.layoutBehavior.customLayoutToolId }
                            ?.takeIf { id -> secondaries.any { it.id == id } },
                        detail = { id ->
                            ChoiceDetail(
                                icon = if (id == null) Icons.Outlined.AltRoute
                                else Icons.Outlined.Keyboard,
                            )
                        },
                    ) { scope.launch { repository.setCustomLayoutToolLayout(it) } }
                }
                item(level = SettingLevel.STANDARD) {
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
                    settings.watch { it.sound.enabled },
                    default = SettingsDefaults.sound.enabled,
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
            SettingsGroup(stringResource(R.string.tooldetail_handwriting_input_group), level = SettingLevel.STANDARD) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_handwriting_stylus_title,
                        stringResource(R.string.tooldetail_handwriting_stylus_subtitle),
                        settings.watch { it.handwritingStylusOnly },
                        info = stringResource(R.string.tooldetail_handwriting_stylus_info),
                        default = SettingsDefaults.handwritingStylusOnly,
                    ) { scope.launch { repository.setHandwritingStylusOnly(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_handwriting_auto_space_title,
                        stringResource(R.string.tooldetail_handwriting_auto_space_subtitle),
                        settings.watch { it.handwritingAutoSpace },
                        default = SettingsDefaults.handwritingAutoSpace,
                    ) { scope.launch { repository.setHandwritingAutoSpace(it) } }
                }
                // Only a choice where ML Kit is in the build: without it
                // zinnia is all Chinese and Japanese have.
                item(visible = HandwritingModels.mlKitInBuild, level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_handwriting_zinnia_title,
                        stringResource(R.string.tooldetail_handwriting_zinnia_subtitle),
                        settings.watch { it.handwritingZinnia },
                        info = stringResource(R.string.tooldetail_handwriting_zinnia_info),
                        default = SettingsDefaults.handwritingZinnia,
                    ) { scope.launch { repository.setHandwritingZinnia(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.tooldetail_handwriting_pause_title,
                        subtitle = stringResource(R.string.tooldetail_handwriting_pause_subtitle),
                        value = settings.watch { it.handwritingCommitDelayMs }.toFloat(),
                        range = 300f..2000f,
                        display = { msFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.tooldetail_handwriting_pause_info),
                        default = SettingsDefaults.handwritingCommitDelayMs.toFloat(),
                    ) { scope.launch { repository.setHandwritingCommitDelayMs(it.roundToInt()) } }
                }
                // The canvas and the keys around it are a panel layout (#568),
                // edited where the trackpad's are.
                item(level = SettingLevel.ADVANCED) {
                    val customPanels by repository.customPanelLayouts.collectAsStateWithLifecycle(emptyList())
                    NavRow(
                        title = R.string.panel_layout_row_title,
                        subtitle = stringResource(R.string.panel_layout_row_subtitle),
                        value = stringResource(
                            if (customPanels.none { it.panel == PanelKind.HANDWRITING }) {
                                R.string.panel_layout_value_default
                            } else {
                                R.string.panel_layout_value_custom
                            },
                        ),
                    ) { onNavigate(panelEditRoute(PanelKind.HANDWRITING)) }
                }
                // Issue #386. Automatic rides on Android's own stylus
                // handwriting, which only exists from Android 14; below that
                // the row would offer a choice of one.
                item(visible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ChoiceSetting(
                        title = R.string.tooldetail_handwriting_full_screen_title,
                        subtitle = stringResource(R.string.tooldetail_handwriting_full_screen_subtitle),
                        info = stringResource(R.string.tooldetail_handwriting_full_screen_info),
                        options = listOf(
                            HandwritingFullScreenMode.MANUAL to
                                stringResource(R.string.tooldetail_handwriting_full_screen_manual_label),
                            HandwritingFullScreenMode.AUTOMATIC to
                                stringResource(R.string.tooldetail_handwriting_full_screen_automatic_label),
                        ),
                        selected = settings.watch { it.handwritingFullScreenMode },
                        onChange = { scope.launch { repository.setHandwritingFullScreenMode(it) } },
                        default = SettingsDefaults.handwritingFullScreenMode,
                        detail = { mode ->
                            ChoiceDetail(
                                stringResource(
                                    if (mode == HandwritingFullScreenMode.AUTOMATIC) {
                                        R.string.tooldetail_handwriting_full_screen_automatic_desc
                                    } else {
                                        R.string.tooldetail_handwriting_full_screen_manual_desc
                                    },
                                ),
                            )
                        },
                    )
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
        // One row per grid the tool holds. The tool opened as a theme switcher
        // and grew the other three, so a page offering only "All theme
        // settings" would leave three of its four grids with nowhere to go.
        ToolbarTool.THEMES -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                NavRow(
                    R.string.tooldetail_themes_nav_title,
                    stringResource(R.string.tooldetail_themes_nav_subtitle),
                    onClick = { onNavigate("themes") },
                )
            }
            item(level = SettingLevel.STANDARD) {
                NavRow(
                    R.string.tooldetail_appearance_icons_nav_title,
                    stringResource(R.string.tooldetail_appearance_icons_nav_subtitle),
                    onClick = { onNavigate("icons") },
                )
            }
            item(level = SettingLevel.STANDARD) {
                NavRow(
                    R.string.tooldetail_appearance_emoji_nav_title,
                    stringResource(R.string.tooldetail_appearance_emoji_nav_subtitle),
                    onClick = { onNavigate("emoji") },
                )
            }
            item(level = SettingLevel.STANDARD) {
                NavRow(
                    R.string.tooldetail_appearance_fonts_nav_title,
                    stringResource(R.string.tooldetail_appearance_fonts_nav_subtitle),
                    onClick = { onNavigate("fonts") },
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
            // The engine row and the model lists exist only where an engine
            // does: ML Kit in builds that carry it, Bergamot on Android 9 and
            // later in every build that can fetch its models, NLLB on 64-bit
            // phones with the memory for it.
            val onDevice = OfflineTranslation.available
            if (!InternetPermission.granted) StateBanner(stringResource(R.string.tooldetail_translate_offline_build_info), tone = BannerTone.WARNING)
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item { TranslateLanguageSetting(repository, settings) }
                if (onDevice) {
                    item(level = SettingLevel.STANDARD) {
                        val context = LocalContext.current
                        ChoiceSetting(
                            R.string.tooldetail_translate_engine_title,
                            subtitle = stringResource(R.string.tooldetail_translate_engine_subtitle),
                            info = stringResource(R.string.tooldetail_translate_engine_info),
                            options = TranslateEngine.entries.map { it to stringResource(it.labelRes) },
                            selected = settings.watch { it.translate.engine },
                            default = SettingsDefaults.translate.engine,
                        ) { engine ->
                            scope.launch { repository.setTranslateEngine(engine) }
                            // Picking an engine that needs the on-demand
                            // module is asking for it (Play only; a no-op
                            // elsewhere). Data saver holding downloads leaves
                            // it to the banner below, which asks properly.
                            if (engine != TranslateEngine.ONLINE &&
                                downloadDecisionNow(context, settings.value) == MeteredDecision.ALLOWED
                            ) {
                                OnDeviceTranslator.requestModule()
                            }
                        }
                    }
                }
            }
            // Who translates, in order: on the device, the user's own server,
            // DeepL, LibreTranslate, Google. The engine switch above decides
            // which part of the list a translation asks. Each on-device
            // engine's models, DeepL Write and Google's server address are on
            // those providers' own screens.
            ProvidersGroup(
                repository,
                settings,
                ProviderTool.TRANSLATE,
                info = stringResource(R.string.tooldetail_translate_providers_info),
            )
            if (onDevice) {
                SettingsGroup(
                    stringResource(R.string.tooldetail_advanced_group),
                    foldKey = "translate_advanced",
                    level = SettingLevel.ADVANCED,
                ) {
                    // Both shape the panel's language menus, and only mean
                    // something where models do; the subtitles say which
                    // engine each one works with.
                    item {
                        ToggleSetting(
                            R.string.tooldetail_translate_downloaded_first_title,
                            stringResource(R.string.tooldetail_translate_downloaded_first_subtitle),
                            settings.watch { it.translate.downloadedFirst },
                            default = SettingsDefaults.translate.downloadedFirst,
                        ) { scope.launch { repository.setTranslateDownloadedFirst(it) } }
                    }
                    item {
                        ToggleSetting(
                            R.string.tooldetail_translate_only_downloaded_title,
                            stringResource(R.string.tooldetail_translate_only_downloaded_subtitle),
                            settings.watch { it.translate.onlyDownloaded },
                            default = SettingsDefaults.translate.onlyDownloaded,
                        ) { scope.launch { repository.setTranslateOnlyDownloaded(it) } }
                    }
                }
            }
        }
        ToolbarTool.GIF, ToolbarTool.STICKER -> {
            // Who the panel asks: KLIPY, GIPHY, Commons or another wiki,
            // GifCities, packs on the phone. Keys are shared between the two
            // tools, so a key set up here works on the other page too.
            ProvidersGroup(
                repository,
                settings,
                if (tool == ToolbarTool.STICKER) ProviderTool.STICKER else ProviderTool.GIF,
                info = stringResource(R.string.tooldetail_media_providers_info),
                // Categories were fetched with the old key; they are not the new key's answer.
                onChanged = { MediaCategoryCache.clear() },
            )
            if (tool == ToolbarTool.STICKER) {
                // Decides which rows the suggest group holds; the rows read
                // everything else.
                val stickerSuggest = settings.watch { it.gif.stickerSuggest }
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
                // Your own stickers offered while you type their title, a
                // keyword or an emoji (#329). Only the sticker page: the GIF
                // tool has no stickers of your own to offer.
                SettingsGroup(stringResource(R.string.tooldetail_sticker_suggest_group), level = SettingLevel.STANDARD) {
                    item {
                        ToggleSetting(
                            R.string.tooldetail_sticker_suggest_title,
                            stringResource(R.string.tooldetail_sticker_suggest_subtitle),
                            stickerSuggest,
                            info = stringResource(R.string.tooldetail_sticker_suggest_info),
                            default = SettingsDefaults.gif.stickerSuggest,
                        ) { scope.launch { repository.setStickerSuggest(it) } }
                    }
                    if (stickerSuggest) {
                        item {
                            ChoiceSetting(
                                title = R.string.tooldetail_sticker_suggest_style_title,
                                options = listOf(
                                    StickerSuggestStyle.TRAY to
                                        stringResource(R.string.tooldetail_sticker_suggest_style_tray),
                                    StickerSuggestStyle.STRIP to
                                        stringResource(R.string.tooldetail_sticker_suggest_style_strip),
                                    StickerSuggestStyle.CHIP to
                                        stringResource(R.string.tooldetail_sticker_suggest_style_chip),
                                ),
                                selected = settings.watch { it.gif.stickerSuggestStyle },
                                default = SettingsDefaults.gif.stickerSuggestStyle,
                                detail = { style ->
                                    ChoiceDetail(
                                        stringResource(
                                            when (style) {
                                                StickerSuggestStyle.TRAY ->
                                                    R.string.tooldetail_sticker_suggest_style_tray_desc
                                                StickerSuggestStyle.STRIP ->
                                                    R.string.tooldetail_sticker_suggest_style_strip_desc
                                                StickerSuggestStyle.CHIP ->
                                                    R.string.tooldetail_sticker_suggest_style_chip_desc
                                            },
                                        ),
                                    )
                                },
                            ) { scope.launch { repository.setStickerSuggestStyle(it) } }
                        }
                        item(level = SettingLevel.ADVANCED) {
                            ChoiceSetting(
                                title = R.string.tooldetail_sticker_suggest_trigger_title,
                                options = listOf(
                                    StickerTriggerAction.DELETE to
                                        stringResource(R.string.tooldetail_sticker_suggest_trigger_delete),
                                    StickerTriggerAction.KEEP to
                                        stringResource(R.string.tooldetail_sticker_suggest_trigger_keep),
                                ),
                                selected = settings.watch { it.gif.stickerSuggestTrigger },
                                default = SettingsDefaults.gif.stickerSuggestTrigger,
                                detail = { action ->
                                    ChoiceDetail(
                                        stringResource(
                                            if (action == StickerTriggerAction.DELETE) {
                                                R.string.tooldetail_sticker_suggest_trigger_delete_desc
                                            } else {
                                                R.string.tooldetail_sticker_suggest_trigger_keep_desc
                                            },
                                        ),
                                    )
                                },
                            ) { scope.launch { repository.setStickerSuggestTrigger(it) } }
                        }
                        item(level = SettingLevel.ADVANCED) {
                            ToggleSetting(
                                R.string.tooldetail_sticker_suggest_magnify_title,
                                stringResource(R.string.tooldetail_sticker_suggest_magnify_subtitle),
                                settings.watch { it.gif.stickerSuggestMagnify },
                                default = SettingsDefaults.gif.stickerSuggestMagnify,
                            ) { scope.launch { repository.setStickerSuggestMagnify(it) } }
                        }
                    }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_media_layout_group), level = SettingLevel.STANDARD) {
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_media_full_bleed_title,
                        stringResource(R.string.tooldetail_media_full_bleed_subtitle),
                        settings.watch { it.mediaFullBleed },
                        info = stringResource(R.string.tooldetail_media_full_bleed_info),
                        default = SettingsDefaults.mediaFullBleed,
                    ) { scope.launch { repository.setMediaFullBleed(it) } }
                }
                item {
                    // The grid's own size, the way the emoji panel has one:
                    // how many to a row, each tool its own.
                    val stickers = tool == ToolbarTool.STICKER
                    SliderSetting(
                        if (stickers) R.string.tooldetail_media_sticker_per_row_title else R.string.tooldetail_media_gif_per_row_title,
                        subtitle = stringResource(
                            if (stickers) {
                                R.string.tooldetail_media_sticker_per_row_subtitle
                            } else {
                                R.string.tooldetail_media_gif_per_row_subtitle
                            },
                        ),
                        value = settings.watch { if (stickers) it.gif.stickerPerRow else it.gif.gifPerRow }.toFloat(),
                        range = MediaPerRowRange.first.toFloat()..MediaPerRowRange.last.toFloat(),
                        display = { numberFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.tooldetail_media_per_row_info),
                        default = (if (stickers) SettingsDefaults.gif.stickerPerRow else SettingsDefaults.gif.gifPerRow).toFloat(),
                    ) {
                        scope.launch {
                            if (stickers) repository.setStickerPerRow(it.roundToInt()) else repository.setGifPerRow(it.roundToInt())
                        }
                    }
                }
                item(level = SettingLevel.ADVANCED) {
                    val spacing = stringResource(R.string.tooldetail_media_spacing_value)
                    SliderSetting(
                        R.string.tooldetail_media_spacing_title,
                        subtitle = stringResource(R.string.tooldetail_media_spacing_subtitle),
                        value = settings.watch { it.gif.gridSpacing }.toFloat(),
                        range = MediaGridSpacingRange.first.toFloat()..MediaGridSpacingRange.last.toFloat(),
                        display = { spacing.format(it.roundToInt()) },
                        default = SettingsDefaults.gif.gridSpacing.toFloat(),
                    ) { scope.launch { repository.setMediaGridSpacing(it.roundToInt()) } }
                }
                item {
                    // The emoji panel's own setting (#537), here too because
                    // the bar on top of these panels drags the same value.
                    val none = stringResource(R.string.langemoji_panel_height_none)
                    val taller = stringResource(R.string.langemoji_panel_height_value)
                    SliderSetting(
                        R.string.langemoji_panel_height_title,
                        subtitle = stringResource(R.string.langemoji_panel_height_subtitle),
                        value = settings.watch { it.emoji.panelExtraHeightDp }.toFloat(),
                        range = MediaPanelExtraHeightRange.first.toFloat()..MediaPanelExtraHeightRange.last.toFloat(),
                        display = {
                            val dp = it.roundToInt()
                            if (dp == 0) none else taller.format(dp)
                        },
                        info = stringResource(R.string.langemoji_panel_height_info),
                        default = SettingsDefaults.emoji.panelExtraHeightDp.toFloat(),
                    ) { scope.launch { repository.setMediaPanelExtraHeightDp(it.roundToInt()) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_media_hide_header_title,
                        stringResource(R.string.tooldetail_media_hide_header_subtitle),
                        settings.watch { it.gif.hideHeaderOnScroll },
                        info = stringResource(R.string.tooldetail_media_hide_header_info),
                        default = SettingsDefaults.gif.hideHeaderOnScroll,
                    ) { scope.launch { repository.setMediaHideHeaderOnScroll(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_media_remember_search_title,
                        stringResource(R.string.tooldetail_media_remember_search_subtitle),
                        settings.watch { it.gif.rememberSearch },
                        info = stringResource(R.string.tooldetail_media_remember_search_info),
                        default = SettingsDefaults.gif.rememberSearch,
                    ) { scope.launch { repository.setMediaRememberSearch(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    // A panel layout like the emoji panel's (#538): the keys
                    // under the results, and where the switch sits.
                    val panel = if (tool == ToolbarTool.STICKER) PanelKind.STICKER else PanelKind.GIF
                    val customPanels by repository.customPanelLayouts.collectAsStateWithLifecycle(emptyList())
                    NavRow(
                        title = R.string.panel_layout_row_title,
                        subtitle = stringResource(R.string.panel_layout_row_subtitle),
                        value = stringResource(
                            if (customPanels.none { it.panel == panel }) {
                                R.string.panel_layout_value_default
                            } else {
                                R.string.panel_layout_value_custom
                            },
                        ),
                    ) { onNavigate(panelEditRoute(panel)) }
                }
            }
            if (tool == ToolbarTool.GIF) {
                // The GIF panel's favourites and recent GIFs (#597).
                val context = LocalContext.current
                SettingsGroup(
                    stringResource(R.string.tooldetail_gif_collections_group),
                    info = stringResource(R.string.tooldetail_gif_collections_info),
                    level = SettingLevel.STANDARD,
                ) {
                    item {
                        ToggleSetting(
                            R.string.tooldetail_gif_recent_title,
                            stringResource(R.string.tooldetail_gif_recent_subtitle),
                            settings.watch { it.gif.recentGifs },
                            info = stringResource(R.string.tooldetail_gif_recent_info),
                            default = SettingsDefaults.gif.recentGifs,
                        ) { on ->
                            scope.launch {
                                repository.setRecentGifs(on)
                                if (!on) {
                                    withContext(Dispatchers.IO) {
                                        com.wasimaster.wmkeyboard.core.tools.GifCollections.get(context).clearRecents()
                                    }
                                }
                            }
                        }
                    }
                    item(level = SettingLevel.ADVANCED) {
                        ToggleSetting(
                            R.string.tooldetail_gif_favourites_offline_title,
                            stringResource(R.string.tooldetail_gif_favourites_offline_subtitle),
                            settings.watch { it.gif.favouritesOffline },
                            info = stringResource(R.string.tooldetail_gif_favourites_offline_info),
                            default = SettingsDefaults.gif.favouritesOffline,
                        ) { on ->
                            scope.launch {
                                repository.setGifFavouritesOffline(on)
                                if (!on) {
                                    withContext(Dispatchers.IO) {
                                        com.wasimaster.wmkeyboard.core.tools.GifCollections.get(context).dropCopies()
                                    }
                                }
                            }
                        }
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
                // Sticker or image matters to a sticker (WhatsApp takes one and
                // not the other); a GIF sent as a sticker is the rare case.
                item(level = if (tool == ToolbarTool.STICKER) SettingLevel.STANDARD else SettingLevel.ADVANCED) {
                    if (tool == ToolbarTool.STICKER) {
                        ChoiceSetting(
                            title = R.string.tooldetail_media_sticker_send_title,
                            subtitle = stringResource(R.string.tooldetail_media_sticker_send_subtitle),
                            info = stringResource(R.string.tooldetail_media_sticker_send_info),
                            options = listOf(
                                MediaSendMode.STICKER to stickerOption,
                                MediaSendMode.IMAGE to imageOption,
                            ),
                            selected = settings.watch { it.stickerSendMode },
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
                            selected = settings.watch { it.gif.sendMode },
                            default = SettingsDefaults.gif.sendMode,
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
            // A row in a group like every other setting, so it folds, resets
            // and lands from search; it used to be a bare control under a
            // heading.
            SettingsGroup(level = SettingLevel.STANDARD) {
                item {
                    ChoiceSetting(
                        R.string.tooldetail_media_filter_header,
                        info = stringResource(R.string.tooldetail_media_filter_info),
                        options = GifContentFilter.entries.map { filter ->
                            filter to when (filter) {
                                GifContentFilter.OFF -> stringResource(CommonR.string.common_off)
                                GifContentFilter.LOW -> stringResource(R.string.tooldetail_media_filter_low)
                                GifContentFilter.MEDIUM ->
                                    stringResource(R.string.tooldetail_media_filter_medium)
                                GifContentFilter.HIGH -> stringResource(R.string.tooldetail_media_filter_high)
                            }
                        },
                        selected = settings.watch { it.gif.contentFilter },
                        default = SettingsDefaults.gif.contentFilter,
                        detail = { filter -> ChoiceDetail(stringResource(gifFilterDescRes(filter))) },
                    ) { filter -> scope.launch { repository.setGifContentFilter(filter) } }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_media_limit_group), level = SettingLevel.ADVANCED) {
                item {
                    SliderSetting(
                        R.string.tooldetail_media_limit_title,
                        subtitle = stringResource(R.string.tooldetail_media_limit_subtitle),
                        value = settings.watch { it.gif.resultLimit }.toFloat(),
                        range = 6f..48f,
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.gif.resultLimit.toFloat(),
                    ) { scope.launch { repository.setGifResultLimit(it.roundToInt()) } }
                }
            }
        }
        // Image search is web search's Images tab (#879): its button stays,
        // and its settings are all on web search's page.
        ToolbarTool.IMAGE_SEARCH -> SettingsGroup {
            item {
                NavRow(
                    title = R.string.tooldetail_image_search_moved_title,
                    subtitle = stringResource(R.string.tooldetail_image_search_moved_subtitle),
                ) { onNavigate(toolRoute(ToolbarTool.WEB_SEARCH)) }
            }
        }
        ToolbarTool.WEB_SEARCH -> {
            // Who answers, in order: a SearXNG instance, Tavily, Brave. One
            // list for both tabs, as one service always answered both.
            ProvidersGroup(
                repository,
                settings,
                ProviderTool.SEARCH,
                info = stringResource(R.string.tooldetail_search_providers_info),
            )
            // Tavily's advanced search and the F-Droid server addresses are on
            // those providers' own screens.
            SettingsGroup(stringResource(R.string.tooldetail_search_results_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_search_safe_title,
                        stringResource(R.string.tooldetail_search_safe_subtitle),
                        settings.watch { it.webSearch.safe },
                        default = SettingsDefaults.webSearch.safe,
                    ) { scope.launch { repository.setSearchSafe(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.tooldetail_search_count_title,
                        subtitle = stringResource(R.string.tooldetail_search_count_subtitle),
                        value = settings.watch { it.webSearch.resultCount }.toFloat(),
                        range = 1f..MAX_SEARCH_RESULTS.toFloat(),
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.webSearch.resultCount.toFloat(),
                    ) { scope.launch { repository.setSearchResultCount(it.roundToInt()) } }
                }
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        R.string.tooldetail_search_answer_title,
                        stringResource(R.string.tooldetail_search_answer_subtitle),
                        settings.watch { it.webSearch.showAnswer },
                        info = stringResource(R.string.tooldetail_search_answer_info),
                        default = SettingsDefaults.webSearch.showAnswer,
                    ) { scope.launch { repository.setSearchShowAnswer(it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    ChoiceSetting(
                        R.string.tooldetail_search_open_browser_title,
                        subtitle = stringResource(R.string.tooldetail_search_open_browser_subtitle),
                        options = listOf(
                            SearchResultTap.MENU to stringResource(R.string.tooldetail_search_tap_menu),
                            SearchResultTap.INSERT to stringResource(R.string.tooldetail_search_tap_insert),
                            SearchResultTap.OPEN to stringResource(R.string.tooldetail_search_tap_open),
                        ),
                        selected = settings.watch { it.webSearch.resultTap },
                        default = SettingsDefaults.webSearch.resultTap,
                        detail = { tap ->
                            ChoiceDetail(
                                stringResource(
                                    when (tap) {
                                        SearchResultTap.MENU -> R.string.tooldetail_search_tap_menu_desc
                                        SearchResultTap.INSERT -> R.string.tooldetail_search_tap_insert_desc
                                        SearchResultTap.OPEN -> R.string.tooldetail_search_tap_open_desc
                                    },
                                ),
                            )
                        },
                    ) { scope.launch { repository.setSearchResultTap(it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    SliderSetting(
                        R.string.tooldetail_image_columns_title,
                        subtitle = stringResource(R.string.tooldetail_image_columns_subtitle),
                        value = settings.watch { it.emoji.mediaGridColumns }.toFloat(),
                        range = 2f..5f,
                        display = { numberFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.tooldetail_image_columns_info),
                        default = SettingsDefaults.emoji.mediaGridColumns.toFloat(),
                    ) { scope.launch { repository.setMediaGridColumns(it.roundToInt()) } }
                }
            }
        }
        ToolbarTool.OCR -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_ocr_info),
                level = SettingLevel.STANDARD,
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_ocr_select_all_title,
                        stringResource(R.string.tooldetail_ocr_select_all_subtitle),
                        settings.watch { it.scanner.ocrAutoSelectWords },
                        default = SettingsDefaults.scanner.ocrAutoSelectWords,
                    ) { scope.launch { repository.setOcrAutoSelectWords(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    ChoiceSetting(
                        R.string.tooldetail_ocr_engine_title,
                        info = stringResource(
                            // F-Droid's build: PaddleOCR or online, nothing else.
                            if (BuildConfig.ENABLE_ML_KIT_SCANNERS || TesseractOcr.available) R.string.tooldetail_ocr_engine_info
                            else R.string.tooldetail_ocr_engine_info_paddle,
                        ),
                        // Only what this build has: F-Droid's has neither
                        // ML Kit nor Tesseract, and a choice of either
                        // restored from a backup reads as Automatic.
                        options = OcrLanguages.engines(TesseractOcr.available).map { engine ->
                            engine to stringResource(
                                when (engine) {
                                    OcrEngine.AUTO -> R.string.tooldetail_ocr_engine_auto
                                    OcrEngine.ML_KIT -> R.string.tooldetail_ocr_engine_mlkit
                                    OcrEngine.TESSERACT -> R.string.tooldetail_ocr_engine_tesseract
                                    OcrEngine.ONLINE -> R.string.tooldetail_ocr_engine_online
                                },
                            )
                        },
                        selected = settings.watch { OcrLanguages.usable(it.scanner.ocrEngine, TesseractOcr.available) },
                        default = SettingsDefaults.scanner.ocrEngine,
                    ) { scope.launch { repository.setOcrEngine(it) } }
                }
            }
            // Issue #469: a model that reads images, behind an
            // OpenAI-compatible address of the user's choosing; #727 adds
            // Rens servers. Readers are a provider list, asked in order or
            // chosen in the panel.
            if (settings.watch { it.scanner.ocrEngine == OcrEngine.ONLINE }) {
                ProvidersGroup(
                    repository,
                    settings,
                    ProviderTool.OCR,
                    titleRes = R.string.tooldetail_ocr_online_group,
                    info = stringResource(R.string.tooldetail_ocr_online_info),
                )
            }
            val usableEngine = settings.watch { OcrLanguages.usable(it.scanner.ocrEngine, TesseractOcr.available) }
            if (usableEngine != OcrEngine.ML_KIT && usableEngine != OcrEngine.ONLINE) {
                SectionHeader(
                    stringResource(R.string.tooldetail_ocr_packs_header),
                    info = stringResource(
                        if (BuildConfig.ENABLE_ML_KIT_SCANNERS || TesseractOcr.available) R.string.tooldetail_ocr_packs_info
                        else R.string.tooldetail_ocr_packs_info_paddle,
                    ),
                )
                OcrPackManager(settings)
            }
        }
        ToolbarTool.QR_SCAN -> {
            val zxing = BarcodeEngine.useZxing(settings.watch { it.scanner.qrZxing })
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(if (zxing) R.string.tooldetail_qr_scan_zxing_info else R.string.tooldetail_qr_scan_info),
                level = SettingLevel.STANDARD,
            ) {
                // Only a choice where ML Kit's reader can run at all.
                if (BarcodeEngine.mlKitUsable) {
                    item(level = SettingLevel.ADVANCED) {
                        ToggleSetting(
                            R.string.tooldetail_qr_scan_zxing_title,
                            stringResource(R.string.tooldetail_qr_scan_zxing_subtitle),
                            settings.watch { it.scanner.qrZxing },
                            default = SettingsDefaults.scanner.qrZxing,
                        ) { scope.launch { repository.setQrScanZxing(it) } }
                    }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_qr_scan_auto_title,
                        stringResource(R.string.tooldetail_qr_scan_auto_subtitle),
                        settings.watch { it.scanner.qrScanAutoInsert },
                        default = SettingsDefaults.scanner.qrScanAutoInsert,
                    ) { scope.launch { repository.setQrScanAutoInsert(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_qr_scan_haptics_title,
                        stringResource(R.string.tooldetail_qr_scan_haptics_subtitle),
                        settings.watch { it.scanner.qrScanHaptics },
                        default = SettingsDefaults.scanner.qrScanHaptics,
                    ) { scope.launch { repository.setQrScanHaptics(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_qr_scan_preview_title,
                        stringResource(R.string.tooldetail_qr_scan_preview_subtitle),
                        settings.watch { it.scanner.qrScanLinkPreviews },
                        default = SettingsDefaults.scanner.qrScanLinkPreviews,
                    ) { scope.launch { repository.setQrScanLinkPreviews(it) } }
                }
            }
        }
        ToolbarTool.DOC_SCAN -> {
            val builtIn = settings.watch { it.scanner.docBuiltIn }
            DocScanModuleBanner(settings, builtIn)
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(
                    if (googleDocScanner) R.string.tooldetail_doc_scan_info else R.string.tooldetail_doc_scan_builtin_info,
                ),
            ) {
                // Only a choice where Google's scanner can run at all.
                if (googleDocScanner) {
                    item(level = SettingLevel.ADVANCED) {
                        ToggleSetting(
                            R.string.tooldetail_doc_scan_builtin_title,
                            stringResource(R.string.tooldetail_doc_scan_builtin_subtitle),
                            settings.watch { it.scanner.docBuiltIn },
                            default = SettingsDefaults.scanner.docBuiltIn,
                        ) { scope.launch { repository.setDocScanBuiltIn(it) } }
                    }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_doc_scan_gallery_title,
                        stringResource(R.string.tooldetail_doc_scan_gallery_subtitle),
                        settings.watch { it.scanner.docSaveToGallery },
                        default = SettingsDefaults.scanner.docSaveToGallery,
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
            // Harper on the phone, and LanguageTool servers, off until picked.
            ProvidersGroup(
                repository,
                settings,
                ProviderTool.GRAMMAR,
                titleRes = R.string.tooldetail_grammar_providers_group,
                info = stringResource(R.string.tooldetail_grammar_providers_info),
                // A server's answers were for its old setup.
                onChanged = { LanguageToolChecker.reset() },
            )
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item(level = SettingLevel.STANDARD) {
                    ChoiceSetting(
                        R.string.tooldetail_grammar_dialect_title,
                        subtitle = stringResource(R.string.tooldetail_grammar_dialect_subtitle),
                        options = GrammarDialect.entries.map { it to stringResource(it.labelRes) },
                        selected = settings.watch { it.grammarDialect },
                        default = SettingsDefaults.grammarDialect,
                    ) { scope.launch { repository.setGrammarDialect(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.tooldetail_grammar_debounce_title,
                        subtitle = stringResource(R.string.tooldetail_grammar_debounce_subtitle),
                        value = settings.watch { it.grammarDebounceMs }.toFloat(),
                        range = 100f..1500f,
                        display = { msFormat.format(it.toInt()) },
                        default = SettingsDefaults.grammarDebounceMs.toFloat(),
                    ) { scope.launch { repository.setGrammarDebounceMs(it.toInt()) } }
                }
            }
            // One fold per category, opening onto the kinds inside it. The same
            // filter the keyboard's funnel writes, so a kind switched off here
            // is gone from the panel's cards, its issue count and "Fix all".
            GrammarCategory.entries.forEach { category ->
                val kinds = GrammarLintKind.of(category)
                SettingsGroup(
                    stringResource(category.labelRes),
                    foldKey = "grammar_${category.name.lowercase()}",
                    info = stringResource(grammarCategoryInfo(category)),
                    foldSummary = {
                        val shownKinds = settings.watch { s -> kinds.filter { it !in s.grammarHiddenKinds } }
                        when (shownKinds.size) {
                            0 -> stringResource(R.string.tooldetail_grammar_category_none_summary)
                            kinds.size ->
                                stringResource(R.string.tooldetail_grammar_category_all_summary)
                            else -> shownKinds
                                .map { stringResource(it.labelRes) }
                                .joinToString(", ")
                        }
                    },
                    level = SettingLevel.STANDARD,
                ) {
                    item {
                        val shownCount = settings.watch { s -> kinds.count { it !in s.grammarHiddenKinds } }
                        ToggleSetting(
                            grammarCategoryTitle(category),
                            stringResource(
                                R.string.tooldetail_grammar_category_toggle_subtitle,
                                shownCount,
                                kinds.size,
                            ),
                            // Reads as on while any kind inside is on; switching
                            // it sets every kind in the category at once.
                            checked = shownCount > 0,
                        ) { on ->
                            scope.launch { repository.setGrammarCategoryShown(category, on) }
                        }
                    }
                    kinds.forEach { kind ->
                        item {
                            ToggleSetting(
                                title = stringResource(kind.labelRes),
                                subtitle = null,
                                icon = SettingsRowIcons[kind.labelRes],
                                checked = settings.watch { kind !in it.grammarHiddenKinds },
                                onChange = { on ->
                                    scope.launch { repository.setGrammarKindShown(kind, on) }
                                },
                            )
                        }
                    }
                }
            }
            if (BuildConfig.ENABLE_GRAMMAR) {
                val context = LocalContext.current
                SettingsGroup(
                    stringResource(R.string.tooldetail_grammar_system_group),
                    info = stringResource(R.string.tooldetail_grammar_info),
                    level = SettingLevel.STANDARD,
                ) {
                    item {
                        NavRow(
                            R.string.tooldetail_grammar_system_title,
                            stringResource(R.string.tooldetail_grammar_system_subtitle),
                            onClick = { openSpellCheckerSettings(context) },
                        )
                    }
                    item(visible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, level = SettingLevel.ADVANCED) {
                        ToggleSetting(
                            R.string.tooldetail_grammar_no_suggestions_title,
                            stringResource(
                                R.string.tooldetail_grammar_no_suggestions_subtitle,
                            ),
                            settings.watch { it.spellCheckerNoSuggestions },
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
        ToolbarTool.WIKIPEDIA -> {
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_wiki_info),
            ) {
                item {
                    TextFieldSetting(
                        label = stringResource(R.string.tooldetail_wiki_language_label),
                        value = settings.watch { it.webSearch.wikiLanguage },
                        hint = stringResource(R.string.tooldetail_wiki_language_hint),
                        default = SettingsDefaults.webSearch.wikiLanguage,
                    ) { repository.setWikiLanguage(it) }
                }
                serverItems(repository, settings, endpoints = listOf(ServiceEndpoint.WIKIPEDIA), level = SettingLevel.ADVANCED)
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_wiki_markdown_title,
                        stringResource(R.string.tooldetail_wiki_markdown_subtitle),
                        settings.watch { it.webSearch.wikiLinksMarkdown },
                        default = SettingsDefaults.webSearch.wikiLinksMarkdown,
                    ) { scope.launch { repository.setWikiLinksMarkdown(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    val linksFormat = stringResource(R.string.values_number)
                    SliderSetting(
                        R.string.tooldetail_wiki_link_limit_title,
                        subtitle = stringResource(R.string.tooldetail_wiki_link_limit_subtitle),
                        value = settings.watch { it.toolLimits.wikiLinkLimit }.toFloat(),
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
                level = SettingLevel.STANDARD,
            ) {
                item {
                    val remembered = settings.watch { it.symbolRecents.size }
                    WmRow(
                        title = stringResource(R.string.tooldetail_symbols_clear_title),
                        icon = SettingsRowIcons[R.string.tooldetail_symbols_clear_title],
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
                    value = stringResource(if (settings.watch { it.smartCalc }) CommonR.string.common_on else CommonR.string.common_off),
                    route = "typing/chips",
                    onClick = { onNavigate("typing/chips") },
                )
            }
            item(level = SettingLevel.STANDARD) {
                ToggleSetting(
                    R.string.tooldetail_calc_degrees_title,
                    stringResource(R.string.tooldetail_calc_degrees_subtitle),
                    settings.watch { it.calcDegrees },
                    default = SettingsDefaults.calcDegrees,
                ) { scope.launch { repository.setCalcDegrees(it) } }
            }
            item(level = SettingLevel.STANDARD) {
                ToggleSetting(
                    R.string.tooldetail_calc_phone_layout_title,
                    stringResource(R.string.tooldetail_calc_phone_layout_subtitle),
                    settings.watch { it.calcPhoneLayout },
                    default = SettingsDefaults.calcPhoneLayout,
                ) { scope.launch { repository.setCalcPhoneLayout(it) } }
            }
            item(level = SettingLevel.ADVANCED) {
                SliderSetting(
                    R.string.tooldetail_calc_precision_title,
                    subtitle = stringResource(R.string.tooldetail_calc_precision_subtitle),
                    value = settings.watch { it.calcPrecision }.toFloat(),
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
                        value = stringResource(if (settings.watch { it.smartUnits }) CommonR.string.common_on else CommonR.string.common_off),
                        route = "typing/chips",
                        onClick = { onNavigate("typing/chips") },
                    )
                }
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        R.string.tooldetail_units_compound_title,
                        stringResource(R.string.tooldetail_units_compound_subtitle),
                        settings.watch { it.compoundUnits },
                        info = stringResource(R.string.tooldetail_units_compound_info),
                        default = SettingsDefaults.compoundUnits,
                    ) { scope.launch { repository.setCompoundUnits(it) } }
                }
            }
        }
        ToolbarTool.CURRENCY -> {
            // Decides which rows the crypto group holds; the rows read
            // everything else.
            val cryptoOn = settings.watch { it.rateSources.cryptoEnabled }
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_currency_info),
            ) {
                item {
                    NavRow(
                        R.string.tooldetail_chips_nav_title,
                        stringResource(R.string.tooldetail_chips_nav_subtitle),
                        value = stringResource(if (settings.watch { it.smartCurrency }) CommonR.string.common_on else CommonR.string.common_off),
                        route = "typing/chips",
                        onClick = { onNavigate("typing/chips") },
                    )
                }
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        R.string.tooldetail_currency_auto_fetch_title,
                        stringResource(R.string.tooldetail_currency_auto_fetch_subtitle),
                        settings.watch { it.rateSources.autoFetch },
                        info = stringResource(R.string.tooldetail_currency_auto_fetch_info),
                        default = SettingsDefaults.rateSources.autoFetch,
                    ) { scope.launch { repository.setCurrencyAutoFetch(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.tooldetail_currency_decimals_title,
                        subtitle = stringResource(R.string.tooldetail_currency_decimals_subtitle),
                        value = settings.watch { it.currencyDecimals }.toFloat(),
                        range = 0f..6f,
                        display = { numberFormat.format(it.toInt()) },
                        default = SettingsDefaults.currencyDecimals.toFloat(),
                    ) { scope.launch { repository.setCurrencyDecimals(it.toInt()) } }
                }
                item(level = SettingLevel.STANDARD) {
                    ChoiceSetting(
                        R.string.tooldetail_currency_label_title,
                        subtitle = stringResource(R.string.tooldetail_currency_label_subtitle),
                        info = stringResource(R.string.tooldetail_currency_label_info),
                        options = listOf(
                            CurrencyLabel.NAME to stringResource(R.string.tooldetail_currency_label_name),
                            CurrencyLabel.SYMBOL to stringResource(R.string.tooldetail_currency_label_symbol),
                            CurrencyLabel.CODE to stringResource(R.string.tooldetail_currency_label_code),
                        ),
                        selected = settings.watch { it.currencyLabel },
                        default = SettingsDefaults.currencyLabel,
                    ) { scope.launch { repository.setCurrencyLabel(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.tooldetail_currency_refresh_title,
                        subtitle = stringResource(R.string.tooldetail_currency_refresh_subtitle),
                        value = settings.watch { it.currencyCacheHours }.toFloat(),
                        range = 1f..48f,
                        display = { hoursFormat.format(it.toInt()) },
                        info = stringResource(R.string.tooldetail_currency_refresh_info),
                        default = SettingsDefaults.currencyCacheHours.toFloat(),
                    ) { scope.launch { repository.setCurrencyCacheHours(it.toInt()) } }
                }
            }
            // Where rates come from, best first; the next is asked when one fails.
            ProvidersGroup(
                repository,
                settings,
                ProviderTool.CURRENCY_FIAT,
                titleRes = R.string.tooldetail_currency_source_title,
                info = stringResource(R.string.tooldetail_currency_source_subtitle),
                level = SettingLevel.ADVANCED,
            )
            SettingsGroup(
                stringResource(R.string.tooldetail_crypto_group_title),
                info = stringResource(R.string.tooldetail_crypto_info),
                level = SettingLevel.STANDARD,
            ) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_crypto_enable_title,
                        stringResource(R.string.tooldetail_crypto_enable_subtitle),
                        cryptoOn,
                        info = stringResource(R.string.tooldetail_crypto_enable_info),
                        default = SettingsDefaults.rateSources.cryptoEnabled,
                    ) { scope.launch { repository.setCryptoEnabled(it) } }
                }
                if (cryptoOn) {
                    item(level = SettingLevel.ADVANCED) {
                        val auto = stringResource(R.string.tooldetail_crypto_decimals_auto)
                        SliderSetting(
                            R.string.tooldetail_crypto_decimals_title,
                            subtitle = stringResource(R.string.tooldetail_crypto_decimals_subtitle),
                            value = settings.watch { it.rateSources.cryptoDecimals }.toFloat(),
                            range = 0f..12f,
                            display = {
                                if (it.toInt() == 0) auto else numberFormat.format(it.toInt())
                            },
                            default = SettingsDefaults.rateSources.cryptoDecimals.toFloat(),
                        ) { scope.launch { repository.setCryptoDecimals(it.toInt()) } }
                    }
                    item(level = SettingLevel.ADVANCED) {
                        SliderSetting(
                            R.string.tooldetail_crypto_refresh_title,
                            subtitle = stringResource(R.string.tooldetail_crypto_refresh_subtitle),
                            value = settings.watch { it.rateSources.cryptoCacheMinutes }.toFloat(),
                            range = 1f..60f,
                            display = { minutesFormat.format(it.toInt()) },
                            default = SettingsDefaults.rateSources.cryptoCacheMinutes.toFloat(),
                        ) { scope.launch { repository.setCryptoCacheMinutes(it.toInt()) } }
                    }
                    item(level = SettingLevel.STANDARD) { CryptoCoinPicker(repository, settings) }
                }
            }
            if (cryptoOn) {
                ProvidersGroup(
                    repository,
                    settings,
                    ProviderTool.CURRENCY_CRYPTO,
                    titleRes = R.string.tooldetail_crypto_source_title,
                    info = stringResource(R.string.tooldetail_crypto_source_subtitle),
                    level = SettingLevel.ADVANCED,
                )
            }
        }
        ToolbarTool.QR_GEN -> {
            val qrImageOption = stringResource(R.string.tooldetail_media_send_image_option)
            val qrStickerOption = stringResource(R.string.tooldetail_media_send_sticker_option)
            SettingsGroup(
                stringResource(R.string.tooldetail_options_group),
                info = stringResource(R.string.tooldetail_qr_gen_ecc_info),
                level = SettingLevel.STANDARD,
            ) {
                item(level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.tooldetail_qr_gen_size_title,
                        subtitle = stringResource(R.string.tooldetail_qr_gen_size_subtitle),
                        value = settings.watch { it.scanner.qrSizePx }.toFloat(),
                        range = 256f..2048f,
                        display = { pixelsFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.scanner.qrSizePx.toFloat(),
                    ) { scope.launch { repository.setQrSizePx(it.roundToInt()) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    ChoiceSetting(
                        title = R.string.tooldetail_qr_gen_send_title,
                        subtitle = stringResource(R.string.tooldetail_qr_gen_send_subtitle),
                        info = stringResource(R.string.tooldetail_qr_gen_send_info),
                        options = listOf(
                            MediaSendMode.IMAGE to qrImageOption,
                            MediaSendMode.STICKER to qrStickerOption,
                        ),
                        selected = settings.watch { it.scanner.qrSendMode },
                        default = SettingsDefaults.scanner.qrSendMode,
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
                        settings.watch { it.scanner.qrSaveToGallery },
                        default = SettingsDefaults.scanner.qrSaveToGallery,
                    ) { scope.launch { repository.setQrSaveToGallery(it) } }
                }
            }
            SettingsGroup(level = SettingLevel.ADVANCED) {
                item {
                    ChoiceSetting(
                        R.string.tooldetail_qr_gen_ecc_header,
                        // The names are the standard's own single letters (L/M/Q/H),
                        // not words, so they are not translated.
                        options = QrEccLevel.entries.map { it to it.name },
                        selected = settings.watch { it.scanner.qrEcc },
                        default = SettingsDefaults.scanner.qrEcc,
                        detail = { level -> ChoiceDetail(stringResource(qrEccDescRes(level))) },
                    ) { level -> scope.launch { repository.setQrEcc(level) } }
                }
                item {
                    val charsFormat = stringResource(R.string.values_number)
                    SliderSetting(
                        R.string.tooldetail_qr_max_chars_title,
                        subtitle = stringResource(R.string.tooldetail_qr_max_chars_subtitle),
                        value = settings.watch { it.toolLimits.qrMaxChars }.toFloat(),
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
            // Decides which rows the group holds; the rows read everything else.
            val pwSymbols = settings.watch { it.passwordGenerator.pwSymbols }
            SettingsGroup(
                stringResource(R.string.tooldetail_password_group),
                info = stringResource(R.string.tooldetail_password_pool_info),
            ) {
                item {
                    SliderSetting(
                        R.string.tooldetail_password_length_title,
                        value = settings.watch { it.passwordGenerator.pwLength }.toFloat(),
                        range = 4f..64f,
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.passwordGenerator.pwLength.toFloat(),
                    ) { scope.launch { repository.setPwLength(it.roundToInt()) } }
                }
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        R.string.tooldetail_password_uppercase_title,
                        stringResource(R.string.tooldetail_password_uppercase_subtitle),
                        settings.watch { it.passwordGenerator.pwUppercase },
                        default = SettingsDefaults.passwordGenerator.pwUppercase,
                    ) { scope.launch { repository.setPwUppercase(it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        R.string.tooldetail_password_digits_title,
                        stringResource(R.string.tooldetail_password_digits_subtitle),
                        settings.watch { it.passwordGenerator.pwDigits },
                        default = SettingsDefaults.passwordGenerator.pwDigits,
                    ) { scope.launch { repository.setPwDigits(it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        R.string.tooldetail_password_symbols_title,
                        stringResource(R.string.tooldetail_password_symbols_subtitle),
                        pwSymbols,
                        default = SettingsDefaults.passwordGenerator.pwSymbols,
                    ) { scope.launch { repository.setPwSymbols(it) } }
                }
                item(visible = pwSymbols, level = SettingLevel.ADVANCED) {
                    TextFieldSetting(
                        label = stringResource(R.string.tooldetail_password_pool_label),
                        value = settings.watch { it.toolLimits.passwordSymbols },
                        hint = stringResource(R.string.tooldetail_password_pool_hint),
                        default = SettingsDefaults.toolLimits.passwordSymbols,
                    ) { repository.setPasswordSymbols(it) }
                }
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        R.string.tooldetail_password_ambiguous_title,
                        stringResource(R.string.tooldetail_password_ambiguous_subtitle),
                        settings.watch { it.passwordGenerator.pwExcludeAmbiguous },
                        default = SettingsDefaults.passwordGenerator.pwExcludeAmbiguous,
                    ) { scope.launch { repository.setPwExcludeAmbiguous(it) } }
                }
            }
            SettingsGroup(
                stringResource(R.string.tooldetail_passphrase_group),
                info = stringResource(R.string.tooldetail_password_info),
                level = SettingLevel.STANDARD,
            ) {
                item {
                    SliderSetting(
                        R.string.tooldetail_passphrase_words_title,
                        value = settings.watch { it.passwordGenerator.ppWordCount }.toFloat(),
                        range = 2f..10f,
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.passwordGenerator.ppWordCount.toFloat(),
                    ) { scope.launch { repository.setPpWordCount(it.roundToInt()) } }
                }
                item {
                    TextFieldSetting(
                        label = stringResource(R.string.tooldetail_passphrase_separator_label),
                        value = settings.watch { it.passwordGenerator.ppSeparator },
                        hint = stringResource(R.string.tooldetail_passphrase_separator_hint),
                        default = SettingsDefaults.passwordGenerator.ppSeparator,
                    ) { repository.setPpSeparator(it) }
                }
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_passphrase_capitalize_title,
                        stringResource(R.string.tooldetail_passphrase_capitalize_subtitle),
                        settings.watch { it.passwordGenerator.ppCapitalize },
                        default = SettingsDefaults.passwordGenerator.ppCapitalize,
                    ) { scope.launch { repository.setPpCapitalize(it) } }
                }
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        R.string.tooldetail_passphrase_digit_title,
                        stringResource(R.string.tooldetail_passphrase_digit_subtitle),
                        settings.watch { it.passwordGenerator.ppIncludeDigit },
                        default = SettingsDefaults.passwordGenerator.ppIncludeDigit,
                    ) { scope.launch { repository.setPpIncludeDigit(it) } }
                }
            }
            // Digits only, for a phone, an app or a bank card (#899).
            SettingsGroup(stringResource(R.string.tooldetail_pin_group), level = SettingLevel.STANDARD) {
                item {
                    SliderSetting(
                        R.string.tooldetail_pin_length_title,
                        value = settings.watch { it.passwordGenerator.pinLength }.toFloat(),
                        range = PasswordGen.PIN_LENGTHS.first.toFloat()..PasswordGen.PIN_LENGTHS.last.toFloat(),
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.passwordGenerator.pinLength.toFloat(),
                    ) { scope.launch { repository.setPinLength(it.roundToInt()) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_pin_avoid_weak_title,
                        stringResource(R.string.tooldetail_pin_avoid_weak_subtitle),
                        settings.watch { it.passwordGenerator.pinAvoidWeak },
                        default = SettingsDefaults.passwordGenerator.pinAvoidWeak,
                    ) { scope.launch { repository.setPinAvoidWeak(it) } }
                }
            }
        }
        ToolbarTool.TEXT_REPEAT -> TextRepeatToolSettings(repository, settings)
        ToolbarTool.READ_BACK -> ReadBackToolSettings(settings)
        ToolbarTool.TYPING_TEST -> TypingTestToolSettings(repository, settings)
        ToolbarTool.SPEED_TEST -> SpeedTestToolSettings(repository, settings)
        ToolbarTool.AI -> AiToolSettings(repository, settings, onNavigate)
        ToolbarTool.AI_REPLY -> AiReplyToolSettings(repository, settings, onNavigate)
        ToolbarTool.VOCABULARY -> VocabularyToolSettings(repository, settings, onNavigate)
        ToolbarTool.NOTES -> NotesToolSettings(repository, settings, onNavigate)
        ToolbarTool.TODO -> TodoToolSettings(repository, settings, onNavigate)
        ToolbarTool.LOCATION -> LocationToolSettings(repository, settings)
        ToolbarTool.CLOCK -> ClockToolSettings(onNavigate)
        ToolbarTool.MUSIC_ID -> MusicToolSettings(repository, settings, onNavigate)
        ToolbarTool.HOME_ASSISTANT -> com.wasimaster.wmkeyboard.app.homeassistant.SmartHomeToolSettings(repository, settings, onNavigate)
        ToolbarTool.GALLERY -> GalleryToolSettings(repository, settings)
        ToolbarTool.SEND_FILES -> com.wasimaster.wmkeyboard.app.send.SendToolSettings(repository, settings, onNavigate)
        ToolbarTool.MODES -> SettingsGroup(stringResource(R.string.tooldetail_modes_group)) {
            item {
                NavRow(
                    R.string.tooldetail_modes_edit_title,
                    stringResource(R.string.tooldetail_modes_edit_subtitle),
                    value = "${settings.watch { it.keyboardModes.size }}",
                ) { onNavigate("modes") }
            }
        }
        else -> {}
    }
    // How the tool sits on the toolbar and in the toolbox: the same rows on
    // every tool's page, so after the tool's own settings rather than
    // before them, folded with what they hold named on the heading.
    SettingsGroup(
        stringResource(R.string.tooldetail_toolbar_group),
        foldKey = "tool_toolbar",
        foldSummary = {
            listOfNotNull(
                stringResource(R.string.tooldetail_show_in_toolbox_title).takeIf { toolOn },
                stringResource(R.string.tooldetail_icon_colour_title).takeIf { coloredIcons },
                stringResource(R.string.tooldetail_hold_title),
            ).joinToString(", ")
        },
        level = SettingLevel.STANDARD,
    ) {
        // Off the grid, on everywhere else: for a tool someone only reaches by
        // name, from a selection action or pinned on the bar. Meaningless while
        // the tool is off, since then it is in no grid to leave.
        item(visible = toolOn) {
            ToggleSetting(
                R.string.tooldetail_show_in_toolbox_title,
                stringResource(R.string.tooldetail_show_in_toolbox_subtitle),
                settings.watch { tool !in it.toolbox.hiddenTools },
                default = tool !in SettingsDefaults.toolbox.hiddenTools,
            ) { scope.launch { repository.setToolHiddenInToolbox(tool, !it) } }
        }
        // Recolour just this tool's icon. Only meaningful while the global
        // "Colorful tool icons" switch is on, since it's what paints them.
        if (coloredIcons) {
            item(level = SettingLevel.ADVANCED) {
                ColorSetting(
                    // With the gradients off there is one colour and it needs
                    // no qualifier; with them on the two rows have to say which
                    // end of the gradient each one is. Named by resource rather
                    // than resolved here, so the row picks its icon out of
                    // [SettingsRowIcons] like every other settings row.
                    title = if (gradient) R.string.tooldetail_icon_colour_start_title
                    else R.string.tooldetail_icon_colour_title,
                    color = settings.watch { it.toolColorOverrides[tool] },
                    fallback = toolAccentColorArgb(tool),
                    onChange = { scope.launch { repository.setToolColor(tool, it) } },
                )
            }
            item(visible = gradient, level = SettingLevel.ADVANCED) {
                ColorSetting(
                    title = R.string.tooldetail_icon_colour_end_title,
                    color = settings.watch { it.toolColorEndOverrides[tool] },
                    // Derived from whichever colour the near end currently
                    // is, so the pair moves together until it is pinned.
                    fallback = settings.watch { toolAccentEndColorArgb(tool, it.toolColorOverrides) },
                    onChange = { scope.launch { repository.setToolColorEnd(tool, it) } },
                )
            }
        }
        item { ToolHoldRow(repository, settings, tool) }
    }
    ToolKeywordSetting(repository, settings, tool)
}
/**
 * The coins the keyboard reads and offers. An empty stored set means the
 * catalogue's defaults, so the last coin cannot be switched off — turning
 * it off would silently bring all of them back.
 */
@Composable
private fun CryptoCoinPicker(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val tickers = settings.watch { it.rateSources.cryptoTickers }
    val enabled = remember(tickers) {
        CryptoCatalog.enabled(tickers)
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
/**
 * The Text repeat tool's options. The panel edits most of them live, so they
 * are also where it was last left. The recent texts live in their own file
 * (see FindHistoryPrefs), read and written here directly like Find's history.
 */
@Composable
private fun TextRepeatToolSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val numberFormat = stringResource(R.string.values_number)
    // What decides which rows the groups hold; each row reads its own value.
    val mode = settings.watch { it.textRepeat.mode }
    val separator = settings.watch { it.textRepeat.separator }
    val hasCopies = mode != TextRepeat.Mode.STRETCH
    SettingsGroup(
        stringResource(R.string.tooldetail_repeat_group),
        info = stringResource(R.string.tooldetail_repeat_group_info),
    ) {
        item {
            SliderSetting(
                R.string.tooldetail_repeat_count_title,
                subtitle = stringResource(R.string.tooldetail_repeat_count_subtitle),
                value = settings.watch { it.textRepeat.count }.toFloat(),
                range = TextRepeat.MIN_COUNT.toFloat()..TextRepeat.MAX_COUNT.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                default = SettingsDefaults.textRepeat.count.toFloat(),
            ) { scope.launch { repository.setTextRepeatCount(it.roundToInt()) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.tooldetail_repeat_mode_title,
                subtitle = stringResource(R.string.tooldetail_repeat_mode_subtitle),
                options = listOf(
                    TextRepeat.Mode.REPEAT to stringResource(R.string.tooldetail_repeat_mode_repeat),
                    TextRepeat.Mode.STRETCH to stringResource(R.string.tooldetail_repeat_mode_stretch),
                    TextRepeat.Mode.STAIRS to stringResource(R.string.tooldetail_repeat_mode_stairs),
                ),
                selected = mode,
                default = SettingsDefaults.textRepeat.mode,
                detail = { candidate ->
                    ChoiceDetail(
                        stringResource(
                            when (candidate) {
                                TextRepeat.Mode.REPEAT -> R.string.tooldetail_repeat_mode_repeat_desc
                                TextRepeat.Mode.STRETCH -> R.string.tooldetail_repeat_mode_stretch_desc
                                TextRepeat.Mode.STAIRS -> R.string.tooldetail_repeat_mode_stairs_desc
                            },
                        ),
                    )
                },
            ) { scope.launch { repository.setTextRepeatMode(it) } }
        }
        item(visible = hasCopies, level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.tooldetail_repeat_separator_title,
                subtitle = stringResource(R.string.tooldetail_repeat_separator_subtitle),
                options = listOf(
                    TextRepeat.Separator.NONE to stringResource(R.string.tooldetail_repeat_sep_none),
                    TextRepeat.Separator.SPACE to stringResource(R.string.tooldetail_repeat_sep_space),
                    TextRepeat.Separator.NEWLINE to stringResource(R.string.tooldetail_repeat_sep_newline),
                    TextRepeat.Separator.COMMA to stringResource(R.string.tooldetail_repeat_sep_comma),
                    TextRepeat.Separator.CUSTOM to stringResource(R.string.tooldetail_repeat_sep_custom),
                ),
                selected = separator,
                default = SettingsDefaults.textRepeat.separator,
            ) { scope.launch { repository.setTextRepeatSeparator(it) } }
        }
        item(visible = hasCopies && separator == TextRepeat.Separator.CUSTOM, level = SettingLevel.STANDARD) {
            TextFieldSetting(
                label = stringResource(R.string.tooldetail_repeat_custom_label),
                value = settings.watch { it.textRepeat.customSeparator },
                hint = stringResource(R.string.tooldetail_repeat_custom_hint),
                default = SettingsDefaults.textRepeat.customSeparator,
            ) { repository.setTextRepeatCustomSeparator(it) }
        }
        item(visible = hasCopies, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.tooldetail_repeat_numbered_title,
                stringResource(R.string.tooldetail_repeat_numbered_subtitle),
                settings.watch { it.textRepeat.numbered },
                default = SettingsDefaults.textRepeat.numbered,
            ) { scope.launch { repository.setTextRepeatNumbered(it) } }
        }
        item(visible = hasCopies, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.tooldetail_repeat_alternate_title,
                stringResource(R.string.tooldetail_repeat_alternate_subtitle),
                settings.watch { it.textRepeat.alternateCase },
                default = SettingsDefaults.textRepeat.alternateCase,
            ) { scope.launch { repository.setTextRepeatAlternateCase(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.tooldetail_repeat_limits_group), level = SettingLevel.ADVANCED) {
        item {
            SliderSetting(
                R.string.tooldetail_repeat_max_chars_title,
                subtitle = stringResource(R.string.tooldetail_repeat_max_chars_subtitle),
                value = settings.watch { it.toolLimits.textRepeatMaxChars }.toFloat(),
                range = TextRepeat.MIN_MAX_CHARS.toFloat()..TextRepeat.MAX_MAX_CHARS.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                default = SettingsDefaults.toolLimits.textRepeatMaxChars.toFloat(),
            ) { scope.launch { repository.setTextRepeatMaxChars(it.roundToInt()) } }
        }
        item {
            ChoiceSetting(
                R.string.tooldetail_repeat_chip_insert_title,
                subtitle = stringResource(R.string.tooldetail_repeat_chip_insert_subtitle),
                options = listOf(
                    TextRepeat.ChipInsert.REPLACE_RUN to stringResource(R.string.tooldetail_repeat_chip_insert_replace),
                    TextRepeat.ChipInsert.APPEND to stringResource(R.string.tooldetail_repeat_chip_insert_append),
                ),
                selected = settings.watch { it.textRepeat.chipInsert },
                default = SettingsDefaults.textRepeat.chipInsert,
                detail = { candidate ->
                    ChoiceDetail(
                        stringResource(
                            when (candidate) {
                                TextRepeat.ChipInsert.REPLACE_RUN -> R.string.tooldetail_repeat_chip_insert_replace_desc
                                TextRepeat.ChipInsert.APPEND -> R.string.tooldetail_repeat_chip_insert_append_desc
                            },
                        ),
                    )
                },
            ) { scope.launch { repository.setTextRepeatChipInsert(it) } }
        }
        // The same row as Typing → Smart chips; the chip is this tool's door.
        item {
            StepperSetting(
                R.string.tooldetail_repeat_chip_after_title,
                stringResource(R.string.tooldetail_repeat_chip_after_subtitle),
                value = settings.watch { it.smartChips.repeatAfter },
                range = (TextRepeat.MIN_REPEAT_AFTER..TextRepeat.MAX_REPEAT_AFTER).toList(),
                display = { numberFormat.format(it) },
                default = SettingsDefaults.smartChips.repeatAfter,
            ) { scope.launch { repository.setSmartChipRepeatAfter(it) } }
        }
    }
    val recentsContext = LocalContext.current
    val recents = remember {
        com.wasimaster.wmkeyboard.ime.FindHistoryPrefs(
            recentsContext,
            com.wasimaster.wmkeyboard.ime.FindHistoryPrefs.TEXT_REPEAT_FILE,
        )
    }
    var remembering by remember { mutableStateOf(recents.enabled) }
    var count by remember { mutableStateOf(recents.entries().size) }
    SettingsGroup(stringResource(R.string.tooldetail_repeat_recents_group), level = SettingLevel.STANDARD) {
        item {
            ToggleSetting(
                R.string.tooldetail_repeat_recents_title,
                stringResource(R.string.tooldetail_repeat_recents_subtitle),
                remembering,
                info = stringResource(R.string.tooldetail_repeat_recents_info),
                default = true,
            ) {
                recents.enabled = it
                remembering = it
                count = recents.entries().size
            }
        }
        item(visible = remembering) {
            ActionRow(
                R.string.tooldetail_repeat_recents_clear_title,
                subtitle = pluralStringResource(R.plurals.tooldetail_repeat_recents_count, count, count),
                action = stringResource(R.string.tooldetail_repeat_recents_clear_action),
                enabled = count > 0,
            ) {
                recents.clear()
                count = 0
            }
        }
    }
}

@Composable
private fun TypingTestToolSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val secondsFormat = stringResource(R.string.values_seconds)
    val numberFormat = stringResource(R.string.values_number)
    // What decides which rows the groups hold; each row reads its own value.
    val testMode = settings.watch { it.typingTest.mode }
    val anyCompleted = settings.watch { it.typingTest.completed > 0 }
    val encodedBests = settings.watch { it.typingTest.bests }
    val bests = remember(encodedBests) { TypingBests.decode(encodedBests) }
    val encodedHistory = settings.watch { it.typingTest.history }
    val history = remember(encodedHistory) {
        TypingHistory.decode(encodedHistory)
    }

    SettingsGroup {
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.toolai_typing_default_test_title,
                options = TypingTestMode.entries.map { mode ->
                    mode to stringResource(
                        when (mode) {
                            TypingTestMode.TIME -> R.string.toolai_typing_mode_time_label
                            TypingTestMode.WORDS -> R.string.toolai_typing_mode_words_label
                            TypingTestMode.QUOTE -> R.string.toolai_typing_mode_quote_label
                        },
                    )
                },
                selected = testMode,
                default = SettingsDefaults.typingTest.mode,
            ) { mode -> scope.launch { repository.setTypingTestMode(mode) } }
        }
    }

    SettingsGroup(
        stringResource(R.string.toolai_typing_length_title),
        info = stringResource(R.string.toolai_typing_quote_info).takeIf { testMode == TypingTestMode.QUOTE },
        level = SettingLevel.ADVANCED,
    ) {
        when (testMode) {
            TypingTestMode.TIME -> item {
                SliderSetting(
                    R.string.toolai_typing_seconds_label,
                    value = settings.watch { it.typingTest.duration }.toFloat(),
                    range = 15f..120f,
                    display = { secondsFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.typingTest.duration.toFloat(),
                ) { scope.launch { repository.setTypingTestDuration(it.roundToInt()) } }
            }
            TypingTestMode.WORDS -> item {
                SliderSetting(
                    R.string.toolai_typing_words_label,
                    value = settings.watch { it.typingTest.wordCount }.toFloat(),
                    range = 10f..100f,
                    display = { numberFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.typingTest.wordCount.toFloat(),
                ) { scope.launch { repository.setTypingTestWordCount(it.roundToInt()) } }
            }
            // Quotes come at whatever length they were written.
            TypingTestMode.QUOTE -> Unit
        }
    }

    if (testMode != TypingTestMode.QUOTE) {
        SettingsGroup(stringResource(R.string.toolai_typing_difficulty_title), level = SettingLevel.STANDARD) {
            item {
                ToggleSetting(
                    R.string.toolai_typing_punctuation_title,
                    stringResource(R.string.toolai_typing_punctuation_subtitle),
                    settings.watch { it.typingTest.punctuation },
                    default = SettingsDefaults.typingTest.punctuation,
                ) { scope.launch { repository.setTypingTestPunctuation(it) } }
            }
            item {
                ToggleSetting(
                    R.string.toolai_typing_numbers_title,
                    stringResource(R.string.toolai_typing_numbers_subtitle),
                    settings.watch { it.typingTest.numbers },
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
        level = SettingLevel.STANDARD,
    ) {
        item {
            ToggleSetting(
                R.string.toolai_typing_glide_title,
                stringResource(R.string.toolai_typing_glide_subtitle),
                settings.watch { it.typingTest.glide },
                default = SettingsDefaults.typingTest.glide,
            ) { scope.launch { repository.setTypingTestGlide(it) } }
        }
        item {
            ToggleSetting(
                R.string.toolai_typing_suggestions_title,
                stringResource(R.string.toolai_typing_suggestions_subtitle),
                settings.watch { it.typingTest.suggestions },
                default = SettingsDefaults.typingTest.suggestions,
            ) { scope.launch { repository.setTypingTestSuggestions(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.toolai_typing_records_title)) {
        item {
            val completed = settings.watch { it.typingTest.completed }
            WmRow(
                title = stringResource(R.string.toolai_typing_tests_completed_title),
                trailing = { Text("$completed") },
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
        if (bests.isNotEmpty() || anyCompleted) {
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
    SettingsGroup(
        stringResource(R.string.toolai_typing_achievements_title),
        info = stringResource(R.string.toolai_typing_info),
    ) {
        for (id in TypingAchievements.ALL) {
            item {
                val achievements = settings.watch { it.typingTest.achievements }
                val unlocked = remember(achievements) { id in TypingAchievements.decode(achievements) }
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
    settings: LiveSettings,
    tool: ToolbarTool,
) {
    val defaults = SmartSuggest.defaultKeywords[tool] ?: return
    val scope = rememberCoroutineScope()
    // Decides whether the reset row is in the group, and seeds the field.
    val saved = settings.watch { SmartSuggest.keywordsFor(tool, it.toolKeywords) }
    var text by remember(tool) { mutableStateOf(saved.joinToString(", ")) }
    SettingsGroup(stringResource(R.string.toolai_keyword_group_title), foldKey = "tool_keyword", level = SettingLevel.STANDARD) {
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
        item(level = SettingLevel.ADVANCED) {
            val caseSensitive = settings.watch { SmartSuggest.caseSensitiveKeyword(tool, it.toolKeywordCase) }
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
        item(visible = saved != defaults) {
            WmRow(
                title = stringResource(CommonR.string.common_reset_defaults),
                subtitle = defaults.joinToString(", "),
                icon = SettingsRowIcons[CommonR.string.common_reset_defaults],
                onClick = {
                    text = defaults.joinToString(", ")
                    scope.launch { repository.setToolKeywords(tool, defaults) }
                },
            )
        }
    }
    if (settings.watch { !it.smartSuggestions || !it.smartToolKeywords }) {
        StateBanner(stringResource(R.string.toolai_keyword_off_info))
    }
}

/**
 * DeepL Write (#331), for the grammar panel and the selection bar. Its key
 * and proxy are the DeepL provider of the translate list, so the group only
 * shows once that is set up; the rows here are what DeepL is used for
 * besides translating.
 */
@Composable
internal fun DeepLWriteSettingsGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val defaults = SettingsDefaults.translate.deepl
    // What decides which rows the group holds; each row reads its own value.
    val configured = settings.watch { it.deeplLive.configured }
    val writeActive = settings.watch { it.deeplLive.writeActive }
    SettingsGroup(
        stringResource(R.string.tooldetail_deepl_group),
        info = stringResource(R.string.tooldetail_deepl_write_group_info),
        level = SettingLevel.STANDARD,
    ) {
        item(visible = configured) {
            ToggleSetting(
                R.string.tooldetail_deepl_write_title,
                stringResource(R.string.tooldetail_deepl_write_subtitle),
                settings.watch { it.translate.deepl.write },
                info = stringResource(R.string.tooldetail_deepl_write_info),
                default = defaults.write,
            ) { scope.launch { repository.setDeepLWrite(it) } }
        }
        item(visible = writeActive) {
            ChoiceSetting(
                R.string.tooldetail_deepl_style_title,
                subtitle = stringResource(R.string.tooldetail_deepl_style_subtitle),
                options = DeepLWriteStyle.entries.map { it to stringResource(it.labelRes) },
                selected = settings.watch { it.translate.deepl.writeStyle },
                default = defaults.writeStyle,
            ) { scope.launch { repository.setDeepLWriteStyle(it) } }
        }
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
                ResetSetting(label, default != null && text != default, possible = default != null) {
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
private fun TranslateLanguageSetting(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    var dialogOpen by remember { mutableStateOf(false) }
    val targetLang = settings.watch { it.translateTargetLang }
    NavRow(
        R.string.toolai_translate_into_title,
        subtitle = stringResource(R.string.toolai_translate_into_subtitle),
        value = TranslateClient.languageName(targetLang),
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
                            trailingContent = if (code == targetLang) {
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
    val total = progress?.totalBytes ?: 0L
    return when {
        (progress?.stalledForMs ?: 0L) >= HANDWRITING_STALL_HINT_MS ->
            stringResource(R.string.privacy_handwriting_status_stalled, size)
        total > 0L -> stringResource(
            R.string.privacy_handwriting_status_downloading_of_total,
            size,
            Formatter.formatShortFileSize(context, total),
        )
        else -> stringResource(R.string.privacy_handwriting_status_downloading, size)
    }
}

/** "Not downloaded", with what downloading it would actually cost. */
@Composable
private fun missingSubtitle(installedBytes: Long): String {
    val context = LocalContext.current
    if (installedBytes <= 0L) return stringResource(R.string.privacy_handwriting_status_missing)
    return stringResource(
        R.string.privacy_handwriting_status_missing_sized,
        Formatter.formatShortFileSize(context, installedBytes),
    )
}

/** How long with nothing arriving before the row admits it is stuck. */
private const val HANDWRITING_STALL_HINT_MS = 20_000L

/**
 * Which control a handwriting row's [status] draws at its end: the spinner,
 * the bin, or the Download button. What its cross-fade is keyed on, so a
 * change of status that keeps the same control does not fade.
 */
private fun handwritingControlOf(status: String): Int = when (status) {
    "downloading", "checking" -> 0
    "downloaded" -> 1
    else -> 2
}

/**
 * Download/delete state for the handwriting model of every language the user
 * types in — drawn from ML Kit's full ink catalogue and zinnia's three
 * models, then narrowed to the enabled languages so the list is only ever as
 * long as it is useful. Status is re-read after every action.
 */
@Composable
private fun HandwritingModelManager(settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val enabledLanguages = settings.watch { it.enabledLanguages }
    val zinnia = settings.watch { it.handwritingZinnia }
    val languages = remember(enabledLanguages, zinnia) {
        HandwritingModels.modelsFor(enabledLanguages, zinnia)
    }
    val missing = remember(enabledLanguages, languages) {
        enabledLanguages.distinctBy { it.id }
            .filter { HandwritingModels.tagFor(it, zinnia) == null }
    }
    val context = LocalContext.current
    val startDownload = rememberDownloadStarter()
    // tag -> "checking" | "missing" | "downloaded" | "downloading" | "error"
    val statuses = remember { mutableStateMapOf<String, String>() }
    // Live byte counts while a download runs, measured off disk — ML Kit's
    // API reports no progress — and checked against the size it ships in its
    // own asset manifest.
    val progress = remember { mutableStateMapOf<String, HandwritingDownloadProgress>() }
    val sizes = remember { mutableStateMapOf<String, Long>() }
    val reduceMotion = LocalReduceMotion.current
    // Bumped by an offline import, so the rows re-read what ML Kit now has.
    var imports by remember { mutableIntStateOf(0) }
    LaunchedEffect(languages, imports) {
        for (language in languages) {
            statuses[language.tag] =
                if (HandwritingModels.isDownloaded(language.tag)) "downloaded" else "missing"
            sizes[language.tag] = HandwritingModelSizes.installedBytes(context, language.tag)
        }
    }
    // Each model's pack files, to fetch on another device (#666). Empty on
    // Play until the recogniser's module, which carries the list, is here.
    val importLinks by produceState(emptyList<OfflineLink>(), languages) {
        value = withContext(Dispatchers.IO) {
            languages.flatMap { language ->
                val urls = InkPackImports.packUrls(context, language.tag)
                urls.mapIndexed { index, url ->
                    OfflineLink(
                        context.getString(
                            R.string.offline_import_handwriting_link_label,
                            language.displayName, index + 1, urls.size,
                        ),
                        url,
                    )
                }
            }.distinctBy { it.url }
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
                    swipeActions = if (status == "downloaded") {
                        listOf(
                            deleteSwipeAction(
                                stringResource(R.string.privacy_handwriting_delete_desc, language.displayName),
                            ) {
                                scope.launch {
                                    HandwritingModels.delete(language.tag)
                                    statuses[language.tag] =
                                        if (HandwritingModels.isDownloaded(language.tag)) "downloaded" else "missing"
                                }
                            },
                        )
                    } else {
                        emptyList()
                    },
                    subtitle = when (status) {
                            "checking" -> stringResource(R.string.privacy_handwriting_status_checking)
                            "downloaded" -> stringResource(R.string.privacy_handwriting_status_downloaded)
                            "downloading" -> downloadSubtitle(progress[language.tag])
                            "error" -> stringResource(R.string.privacy_handwriting_status_failed)
                            else -> missingSubtitle(sizes[language.tag] ?: 0L)
                        },
                    trailing = {
                        // Checking, then Download or the bin; after a tap, the
                        // spinner, then the bin. Cross-faded from one to the
                        // next, keyed on which control it is so two spinners
                        // (checking, then downloading) do not fade into each
                        // other. Keyed on the language too, so a slot the group
                        // hands to another language starts still.
                        key(language.tag) {
                            AnimatedContent(
                                targetState = status,
                                contentKey = { handwritingControlOf(it) },
                                contentAlignment = Alignment.CenterEnd,
                                transitionSpec = { stateSwapTransform(reduceMotion) },
                                label = "handwritingModelAction",
                            ) { shown ->
                                // The control on its way out still takes taps
                                // while it fades. One that no longer matches the
                                // model's state does nothing, so a quick second
                                // tap on Download cannot start it twice.
                                val current = { (statuses[language.tag] ?: "checking") == shown }
                                when (shown) {
                                    "downloading", "checking" -> CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    "downloaded" -> IconButton(onClick = {
                                        if (!current()) return@IconButton
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
                                    else -> if (!offlineBuild) TextButton(onClick = {
                                        if (!current()) return@TextButton
                                        statuses[language.tag] = "downloading"
                                        progress[language.tag] = HandwritingDownloadProgress()
                                        // ML Kit fetches ink models through Mobile Data
                                        // Download, which can stall for a minute and a
                                        // half before this gives up on it — far longer
                                        // than anyone waits on the screen.
                                        val notify = startDownload(
                                            DownloadKeys.handwriting(language.tag),
                                            context.getString(
                                                CommonR.string.common_notify_download_handwriting,
                                                language.displayName,
                                            ),
                                        )
                                        scope.launch {
                                            val ok = runCancellable {
                                                HandwritingModels.download(context, language.tag) {
                                                    progress[language.tag] = it
                                                    notify.progress(it.bytes, it.totalBytes)
                                                }
                                            }.isSuccess
                                            progress.remove(language.tag)
                                            statuses[language.tag] = if (ok) "downloaded" else "error"
                                            if (ok) {
                                                notify.done()
                                            } else {
                                                notify.failed(
                                                    context.getString(
                                                        R.string.privacy_handwriting_status_failed,
                                                    ),
                                                )
                                            }
                                        }
                                    }) { Text(stringResource(CommonR.string.common_download)) }
                                }
                            }
                        }
                    },
                )
            }
        }
        item(level = offlineImportLevel) {
            OfflineImportRow(
                subtitle = stringResource(R.string.offline_import_handwriting_subtitle),
                links = importLinks,
                onImported = { imports++ },
            )
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
 * Download/delete for the Tesseract data of every language the user types in,
 * and under Automatic PaddleOCR's models for Chinese and Japanese instead.
 * Several languages can share one pack (both Norwegians, a language that
 * borrows its script's main pack, or Chinese and Japanese with Paddle), so a
 * row is a pack and names them all.
 * Progress comes from [OcrPacks], which the keyboard's own download button
 * feeds too.
 */
@Composable
private fun OcrPackManager(settings: LiveSettings) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val enabledLanguages = settings.watch { it.enabledLanguages }
    val engine = settings.watch { it.scanner.ocrEngine }
    val packs = remember(enabledLanguages, engine) {
        OcrLanguages.packsFor(enabledLanguages, engine, TesseractOcr.available)
    }
    val states by OcrPacks.states.collectAsState()
    LaunchedEffect(packs) { OcrPacks.refresh(filesDir, packs.map { it.first }) }
    val decide = rememberDownloadDecision(settings)
    var askFor by remember { mutableStateOf<String?>(null) }
    var blocked by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current

    if (packs.isEmpty()) {
        StateBanner(stringResource(R.string.tooldetail_ocr_packs_none_info))
        return
    }
    SettingsGroup {
        for ((pack, languages) in packs) {
            item {
                val status = states[pack] ?: OcrPacks.Status.NotDownloaded
                val names = languages.joinToString(", ") { it.displayName }
                WmRow(
                    swipeActions = if (status == OcrPacks.Status.Downloaded) {
                        listOf(
                            deleteSwipeAction(stringResource(R.string.privacy_handwriting_delete_desc, names)) {
                                OcrPacks.delete(filesDir, pack)
                            },
                        )
                    } else {
                        emptyList()
                    },
                    title = if (pack == PaddleOcrModel.PACK) {
                        stringResource(R.string.tooldetail_ocr_pack_paddle_title, names)
                    } else {
                        names
                    },
                    subtitle = when (status) {
                        OcrPacks.Status.Downloaded ->
                            stringResource(R.string.privacy_handwriting_status_downloaded)
                        is OcrPacks.Status.Downloading -> stringResource(
                            R.string.privacy_handwriting_status_downloading_of_total,
                            Formatter.formatShortFileSize(context, status.bytes),
                            Formatter.formatShortFileSize(context, status.total),
                        )
                        is OcrPacks.Status.Failed ->
                            if (status.messageArg.isEmpty()) stringResource(status.messageRes)
                            else stringResource(status.messageRes, status.messageArg)
                        OcrPacks.Status.NotDownloaded -> stringResource(
                            R.string.privacy_handwriting_status_missing_sized,
                            Formatter.formatShortFileSize(context, OcrLanguages.sizeOf(pack)),
                        )
                    },
                    trailing = {
                        key(pack) {
                            AnimatedContent(
                                targetState = status,
                                contentKey = {
                                    when (it) {
                                        is OcrPacks.Status.Downloading -> 0
                                        OcrPacks.Status.Downloaded -> 1
                                        else -> 2
                                    }
                                },
                                contentAlignment = Alignment.CenterEnd,
                                transitionSpec = { stateSwapTransform(reduceMotion) },
                                label = "ocrPackAction",
                            ) { shown ->
                                when (shown) {
                                    is OcrPacks.Status.Downloading -> IconButton(onClick = { OcrPacks.cancel(pack) }) {
                                        CircularProgressIndicator(
                                            progress = {
                                                if (shown.total > 0L) (shown.bytes.toFloat() / shown.total).coerceIn(0f, 1f)
                                                else 0f
                                            },
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                    OcrPacks.Status.Downloaded -> IconButton(onClick = { OcrPacks.delete(filesDir, pack) }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.privacy_handwriting_delete_desc, names),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    else -> if (!offlineBuild) TextButton(onClick = {
                                        when (decide()) {
                                            MeteredDecision.ALLOWED -> OcrPacks.start(filesDir, pack)
                                            MeteredDecision.ASK -> askFor = pack
                                            MeteredDecision.BLOCKED -> blocked = true
                                        }
                                    }) {
                                        Text(
                                            stringResource(
                                                if (shown is OcrPacks.Status.Failed) CommonR.string.common_retry
                                                else CommonR.string.common_download,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
        item(level = offlineImportLevel) {
            val links = packs.flatMap { (pack, languages) ->
                val names = languages.joinToString(", ") { it.displayName }
                if (pack == PaddleOcrModel.PACK) {
                    // Three files, imported one by one under their own names.
                    PaddleOcrModel.parts().mapNotNull { part ->
                        PaddleOcrModel.url(part)?.let { OfflineLink("$names (${part.fileName})", it) }
                    }
                } else {
                    listOf(OfflineLink(names, OcrLanguages.downloadUrl(pack)))
                }
            }
            OfflineImportRow(
                subtitle = stringResource(R.string.offline_import_ocr_subtitle),
                links = links,
                onImported = { OcrPacks.refresh(filesDir, packs.map { it.first }) },
            )
        }
    }
    askFor?.let { pack ->
        MeteredDownloadDialog(
            detail = stringResource(
                R.string.languages_metered_confirm_body,
                Formatter.formatShortFileSize(context, OcrLanguages.sizeOf(pack)),
            ),
            onConfirm = {
                askFor = null
                OcrPacks.start(filesDir, pack)
            },
            onDismiss = { askFor = null },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}
/**
 * Weather location: place label plus coordinates, edited in a dialog. Shared
 * with the onboarding tool-setup page, which asks the same question.
 */
@Composable
internal fun WeatherLocationSetting(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    val unnamedPlace = stringResource(R.string.privacy_weather_place_unnamed)
    val savedLatitude = settings.watch { it.weather.latitude }
    val savedLongitude = settings.watch { it.weather.longitude }
    val savedPlace = settings.watch { it.weather.placeName }
    val summary = if (savedLatitude != null && savedLongitude != null) {
        stringResource(
            R.string.privacy_weather_location_summary,
            savedPlace.ifBlank { unnamedPlace },
            savedLatitude,
            savedLongitude,
        )
    } else {
        stringResource(R.string.privacy_weather_location_empty)
    }
    NavRow(
        R.string.privacy_weather_location_title,
        subtitle = summary,
    ) { editing = true }
    if (!editing) return
    val lookupNames = settings.watch { it.location.lookupAddress }
    PlaceEditorDialog(
        title = stringResource(R.string.privacy_weather_dialog_title),
        initialName = savedPlace,
        initialPoint = if (savedLatitude != null && savedLongitude != null) {
            com.wasimaster.wmkeyboard.core.location.GeoPoint(savedLatitude.toDouble(), savedLongitude.toDouble())
        } else {
            null
        },
        lookupNames = lookupNames,
        onSave = { name, point ->
            scope.launch {
                repository.setWeatherLocation(point.latitude.toFloat(), point.longitude.toFloat(), name)
            }
            editing = false
        },
        onClear = if (savedLatitude != null) {
            {
                scope.launch { repository.setWeatherLocation(null, null, "") }
                editing = false
            }
        } else {
            null
        },
        onDismiss = { editing = false },
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
@StringRes
private fun powerPresetLabelRes(preset: PowerSavingPreset): Int = when (preset) {
    PowerSavingPreset.LIGHT -> R.string.tooldetail_power_preset_light
    PowerSavingPreset.BALANCED -> R.string.tooldetail_power_preset_balanced
    PowerSavingPreset.AGGRESSIVE -> R.string.tooldetail_power_preset_aggressive
    PowerSavingPreset.CUSTOM -> R.string.tooldetail_power_preset_custom
}

@StringRes
private fun powerPresetDescRes(preset: PowerSavingPreset): Int = when (preset) {
    PowerSavingPreset.LIGHT -> R.string.tooldetail_power_preset_light_desc
    PowerSavingPreset.BALANCED -> R.string.tooldetail_power_preset_balanced_desc
    PowerSavingPreset.AGGRESSIVE -> R.string.tooldetail_power_preset_aggressive_desc
    PowerSavingPreset.CUSTOM -> R.string.tooldetail_power_preset_custom_desc
}

private fun powerTriggerDescRes(trigger: PowerSavingTrigger): Int = when (trigger) {
    PowerSavingTrigger.OFF -> R.string.tooldetail_power_trigger_off_desc
    PowerSavingTrigger.SYSTEM_SAVER -> R.string.tooldetail_power_trigger_system_desc
    PowerSavingTrigger.LOW_BATTERY -> R.string.tooldetail_power_trigger_low_desc
    PowerSavingTrigger.EITHER -> R.string.tooldetail_power_trigger_either_desc
}

/** How the GIF panel arranges its sources, one line, for the picker sheet. */

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

/** The settings row that switches a whole grammar category on or off. */
private fun grammarCategoryTitle(category: GrammarCategory): Int = when (category) {
    GrammarCategory.CORRECTNESS -> R.string.tooldetail_grammar_correctness_title
    GrammarCategory.CLARITY -> R.string.tooldetail_grammar_clarity_title
    GrammarCategory.ENGAGEMENT -> R.string.tooldetail_grammar_engagement_title
    GrammarCategory.DELIVERY -> R.string.tooldetail_grammar_delivery_title
}

/**
 * What a grammar category covers, behind its fold's "?". The four names are
 * borrowed from the way writing tools group this, and none of them says on its
 * own which of the twenty kinds landed inside.
 */
private fun grammarCategoryInfo(category: GrammarCategory): Int = when (category) {
    GrammarCategory.CORRECTNESS -> R.string.tooldetail_grammar_correctness_info
    GrammarCategory.CLARITY -> R.string.tooldetail_grammar_clarity_info
    GrammarCategory.ENGAGEMENT -> R.string.tooldetail_grammar_engagement_info
    GrammarCategory.DELIVERY -> R.string.tooldetail_grammar_delivery_info
}


/**
 * The AI reply tool's page (#234).
 *
 * The provider, the key and the model are the AI tool's and stay there — this
 * tool borrows them, and two places to set the same key would be two places to
 * get it wrong. What is here is only what a drafted reply needs: how many, how
 * long, who the user is, and what happens to the copies it was built from.
 */
@Composable
private fun AiReplyToolSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val numberFormat = stringResource(R.string.values_number)
    val charsFormat = stringResource(R.string.values_characters)

    SettingsGroup(stringResource(R.string.toolai_reply_drafts_title)) {
        item(level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.toolai_reply_count_title,
                value = settings.watch { it.ai.reply.count }.toFloat(),
                range = 1f..AiPrompts.MAX_REPLIES.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                default = SettingsDefaults.ai.reply.count.toFloat(),
            ) { scope.launch { repository.setAiReplyCount(it.roundToInt()) } }
        }
        item {
            ChoiceSetting(
                title = R.string.toolai_reply_length_title,
                subtitle = stringResource(R.string.toolai_reply_length_subtitle),
                options = listOf(
                    ReplyLength.SHORT to stringResource(R.string.toolai_reply_length_short),
                    ReplyLength.MEDIUM to stringResource(R.string.toolai_reply_length_medium),
                    ReplyLength.LONG to stringResource(R.string.toolai_reply_length_long),
                ),
                selected = settings.watch { it.ai.reply.length },
                default = SettingsDefaults.ai.reply.length,
            ) { scope.launch { repository.setAiReplyLength(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.toolai_reply_emoji_title,
                subtitle = stringResource(R.string.toolai_reply_emoji_subtitle),
                options = listOf(
                    ReplyEmoji.MATCH to stringResource(R.string.toolai_reply_emoji_match),
                    ReplyEmoji.ALLOW to stringResource(R.string.toolai_reply_emoji_allow),
                    ReplyEmoji.AVOID to stringResource(R.string.toolai_reply_emoji_avoid),
                ),
                selected = settings.watch { it.ai.reply.emoji },
                default = SettingsDefaults.ai.reply.emoji,
            ) { scope.launch { repository.setAiReplyEmoji(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.toolai_reply_voice_title), level = SettingLevel.STANDARD) {
        item {
            TextFieldSetting(
                label = stringResource(R.string.toolai_reply_persona_label),
                value = settings.watch { it.ai.reply.persona },
                hint = stringResource(R.string.toolai_reply_persona_hint),
            ) { repository.setAiReplyPersona(it) }
        }
        item {
            TextFieldSetting(
                label = stringResource(R.string.toolai_reply_language_label),
                value = settings.watch { it.ai.reply.language },
                hint = stringResource(R.string.toolai_reply_language_hint),
            ) { repository.setAiReplyLanguage(it) }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.toolai_reply_context_title,
                value = settings.watch { it.ai.reply.contextChars }.toFloat(),
                range = 300f..8_000f,
                display = { charsFormat.format(it.roundToInt()) },
                default = SettingsDefaults.ai.reply.contextChars.toFloat(),
            ) { scope.launch { repository.setAiReplyContextChars(it.roundToInt()) } }
        }
    }

    SettingsGroup(stringResource(R.string.toolai_reply_shots_title), level = SettingLevel.STANDARD) {
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.toolai_reply_image_mode_title,
                subtitle = stringResource(R.string.toolai_reply_image_mode_subtitle),
                options = listOf(
                    ReplyImageMode.AUTO to stringResource(R.string.toolai_reply_image_mode_auto),
                    ReplyImageMode.IMAGE to stringResource(R.string.toolai_reply_image_mode_image),
                    ReplyImageMode.OCR to stringResource(R.string.toolai_reply_image_mode_ocr),
                ),
                selected = settings.watch { it.ai.reply.imageMode },
                default = SettingsDefaults.ai.reply.imageMode,
            ) { scope.launch { repository.setAiReplyImageMode(it) } }
        }
        item {
            ToggleSetting(
                R.string.toolai_reply_catch_shots_title,
                stringResource(R.string.toolai_reply_catch_shots_subtitle),
                settings.watch { it.ai.reply.catchScreenshots },
                default = SettingsDefaults.ai.reply.catchScreenshots,
            ) { scope.launch { repository.setAiReplyCatchScreenshots(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.toolai_reply_tones_title), level = SettingLevel.STANDARD) {
        item {
            NavRow(
                R.string.toolai_reply_tones_nav_title,
                stringResource(R.string.toolai_reply_tones_nav_subtitle),
            ) { onNavigate("ai_reply_tones") }
        }
    }

    SettingsGroup(stringResource(R.string.toolai_reply_after_title), level = SettingLevel.STANDARD) {
        item {
            ToggleSetting(
                R.string.toolai_reply_tidy_title,
                stringResource(R.string.toolai_reply_tidy_subtitle),
                settings.watch { it.ai.reply.tidyUp },
                default = SettingsDefaults.ai.reply.tidyUp,
            ) { scope.launch { repository.setAiReplyTidyUp(it) } }
        }
        item {
            ToggleSetting(
                R.string.toolai_reply_history_title,
                stringResource(R.string.toolai_reply_history_subtitle),
                settings.watch { it.ai.reply.keepHistory },
                default = SettingsDefaults.ai.reply.keepHistory,
            ) { scope.launch { repository.setAiReplyKeepHistory(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.toolai_reply_provider_title)) {
        item {
            NavRow(
                R.string.toolai_reply_provider_nav_title,
                stringResource(R.string.toolai_reply_provider_nav_subtitle),
            ) { onNavigate("tool/${ToolbarTool.AI.name}") }
        }
    }
}

/**
 * The calendar tool's "New events" group: where Save puts an event, and what
 * a new one starts with. The calendar list needs read access; without it the
 * row says so and asks, through the same disclosure the first Save shows.
 */
@Composable
private fun CalendarNewEventRows(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val canRead = rememberGrantState { DeviceCalendar.canRead(it) }
    var granted by remember { mutableStateOf(false) }
    val request = rememberDisclosedPermissionRequest(PermissionDisclosures.CALENDAR_WRITE) { granted = true }
    val readable = canRead || granted
    SettingsGroup(stringResource(R.string.tooldetail_calendar_new_group), level = SettingLevel.STANDARD) {
        item {
            ChoiceSetting(
                R.string.tooldetail_calendar_save_mode_title,
                subtitle = stringResource(R.string.tooldetail_calendar_save_mode_subtitle),
                info = stringResource(R.string.tooldetail_calendar_save_mode_info),
                options = listOf(
                    CalendarSaveMode.KEYBOARD to stringResource(R.string.tooldetail_calendar_save_keyboard_label),
                    CalendarSaveMode.CALENDAR_APP to stringResource(R.string.tooldetail_calendar_save_app_label),
                ),
                selected = settings.watch { it.calendarTool.saveMode },
                default = SettingsDefaults.calendarTool.saveMode,
            ) { scope.launch { repository.setCalendarSaveMode(it) } }
        }
        item {
            val calendars = remember(readable) { if (readable) DeviceCalendar.writableCalendars(context) else emptyList() }
            val chosen = settings.watch { it.calendarTool.defaultCalendarId }
            val options = listOf(-1L to stringResource(R.string.tooldetail_calendar_default_calendar_primary)) +
                calendars.map { c -> c.id to listOf(c.name, c.account).filter { it.isNotBlank() }.distinct().joinToString(" · ") }
            if (readable) {
                ChoiceSetting(
                    R.string.tooldetail_calendar_default_calendar_title,
                    subtitle = stringResource(R.string.tooldetail_calendar_default_calendar_subtitle),
                    options = options,
                    selected = if (options.any { it.first == chosen }) chosen else -1L,
                    default = SettingsDefaults.calendarTool.defaultCalendarId,
                ) { scope.launch { repository.setCalendarDefaultCalendar(it) } }
            } else {
                NavRow(
                    title = R.string.tooldetail_calendar_default_calendar_title,
                    subtitle = stringResource(R.string.tooldetail_calendar_default_calendar_no_access),
                ) { request() }
            }
        }
        item {
            ChoiceSetting(
                R.string.tooldetail_calendar_default_length_title,
                subtitle = stringResource(R.string.tooldetail_calendar_default_length_subtitle),
                options = CALENDAR_LENGTHS.map { it to calendarLengthLabel(it) },
                selected = settings.watch { it.calendarTool.defaultDurationMinutes }.let { m ->
                    if (m in CALENDAR_LENGTHS) m else SettingsDefaults.calendarTool.defaultDurationMinutes
                },
                default = SettingsDefaults.calendarTool.defaultDurationMinutes,
            ) { scope.launch { repository.setCalendarDefaultDuration(it) } }
        }
        item {
            ChoiceSetting(
                R.string.tooldetail_calendar_default_reminder_title,
                subtitle = stringResource(R.string.tooldetail_calendar_default_reminder_subtitle),
                options = CALENDAR_REMINDERS.map { it to calendarReminderLabel(it) },
                selected = settings.watch { it.calendarTool.defaultReminderMinutes }.let { m ->
                    if (m in CALENDAR_REMINDERS) m else SettingsDefaults.calendarTool.defaultReminderMinutes
                },
                default = SettingsDefaults.calendarTool.defaultReminderMinutes,
            ) { scope.launch { repository.setCalendarDefaultReminder(it) } }
        }
    }
}

private val CALENDAR_LENGTHS = listOf(15, 30, 45, 60, 90, 120, 180, 240)
private val CALENDAR_REMINDERS = listOf(-1, 0, 5, 10, 15, 30, 60, 120, 1440)

@Composable
private fun calendarLengthLabel(minutes: Int): String =
    if (minutes % 60 == 0) {
        pluralStringResource(R.plurals.tooldetail_calendar_length_hours, minutes / 60, minutes / 60)
    } else {
        pluralStringResource(R.plurals.tooldetail_calendar_length_minutes, minutes, minutes)
    }

@Composable
private fun calendarReminderLabel(minutes: Int): String = when {
    minutes < 0 -> stringResource(R.string.tooldetail_calendar_reminder_none)
    minutes == 0 -> stringResource(R.string.tooldetail_calendar_reminder_at_start)
    minutes % 1440 == 0 -> pluralStringResource(R.plurals.tooldetail_calendar_days_before, minutes / 1440, minutes / 1440)
    minutes % 60 == 0 -> pluralStringResource(R.plurals.tooldetail_calendar_hours_before, minutes / 60, minutes / 60)
    else -> pluralStringResource(R.plurals.tooldetail_calendar_minutes_before, minutes, minutes)
}
