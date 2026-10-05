package com.wasimaster.wmkeyboard.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import android.view.KeyEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import com.wasimaster.wmkeyboard.core.media.hasNotificationAccess
import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.core.prediction.UndoMemory
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SuggestionHotkeyMode
import com.wasimaster.wmkeyboard.core.thesaurus.SynonymSource
import com.wasimaster.wmkeyboard.core.thesaurus.SynonymSourceChoice
import com.wasimaster.wmkeyboard.core.tools.CheatSheetLetter
import com.wasimaster.wmkeyboard.core.tools.DefaultLeader
import com.wasimaster.wmkeyboard.core.tools.DefaultToolLetters
import com.wasimaster.wmkeyboard.core.tools.KeyChord
import com.wasimaster.wmkeyboard.core.tools.LeaderTrigger
import com.wasimaster.wmkeyboard.core.tools.ReservedChords
import com.wasimaster.wmkeyboard.core.tools.ReservedLetters
import com.wasimaster.wmkeyboard.core.tools.TapModifier
import com.wasimaster.wmkeyboard.core.tools.ToolboxLetter
import com.wasimaster.wmkeyboard.core.tools.describeChord
import com.wasimaster.wmkeyboard.core.tools.formatChord
import com.wasimaster.wmkeyboard.core.tools.formatLeader
import com.wasimaster.wmkeyboard.core.tools.parseLeader
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.usableTools
import com.wasimaster.wmkeyboard.core.tools.SmartSuggest
import com.wasimaster.wmkeyboard.core.tools.leaderLabel
import androidx.compose.ui.unit.dp
import android.os.Build
import com.wasimaster.wmkeyboard.core.settings.PickerTimeoutRange
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import com.wasimaster.wmkeyboard.core.settings.GlideApostropheKey
import com.wasimaster.wmkeyboard.core.settings.ShiftGlideMode
import com.wasimaster.wmkeyboard.core.settings.GlidePickerChoicesRange
import com.wasimaster.wmkeyboard.core.settings.GlideRadiusRange
import com.wasimaster.wmkeyboard.core.settings.LanguageEchoMsRange
import com.wasimaster.wmkeyboard.core.settings.GlideDwellFullRange
import com.wasimaster.wmkeyboard.core.settings.GlideLoopMinArcRange
import com.wasimaster.wmkeyboard.core.settings.GlideLoopExtentRange
import com.wasimaster.wmkeyboard.core.settings.GlideLoopRadiusRange
import com.wasimaster.wmkeyboard.core.settings.GlideWiggleExtentRange
import com.wasimaster.wmkeyboard.core.settings.GlideShapesPerWordRange
import com.wasimaster.wmkeyboard.core.settings.GlideWiggleWeightRange
import com.wasimaster.wmkeyboard.core.settings.GlidePickerDwellMsRange
import com.wasimaster.wmkeyboard.core.settings.GlidePickerSensitivity
import com.wasimaster.wmkeyboard.core.settings.GlideCommitColor
import com.wasimaster.wmkeyboard.core.settings.GlideCommitColorScope
import com.wasimaster.wmkeyboard.core.settings.GlideLookAhead
import com.wasimaster.wmkeyboard.core.settings.GlidePreviewSteadiness
import com.wasimaster.wmkeyboard.core.settings.GlideSandbox
import com.wasimaster.wmkeyboard.core.settings.GlideVocabulary
import com.wasimaster.wmkeyboard.core.settings.OctopusDuringGlide
import com.wasimaster.wmkeyboard.core.settings.OctopusFlickSensitivity
import com.wasimaster.wmkeyboard.core.settings.OctopusPlacement
import com.wasimaster.wmkeyboard.core.settings.OctopusSettings
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.settings.LanguageDetectionStrength
import kotlinx.coroutines.CoroutineScope
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.LetterSwipeAction
import com.wasimaster.wmkeyboard.core.settings.NumberGrouping
import com.wasimaster.wmkeyboard.core.settings.RankControl
import com.wasimaster.wmkeyboard.core.settings.SuggestionOverflow
import com.wasimaster.wmkeyboard.core.settings.SpaceSwipeAction
import com.wasimaster.wmkeyboard.core.settings.WordMenuItem
import com.wasimaster.wmkeyboard.core.settings.LanguagePickerStyle
import com.wasimaster.wmkeyboard.core.settings.SpacebarDisplay
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.KeyboardTab
import androidx.compose.ui.graphics.vector.ImageVector

/** One spacebar-swipe slot (quick or hold+swipe): nothing / language / cursor. */
@Composable
private fun SpaceSwipeSetting(
    @StringRes title: Int,
    subtitle: String,
    info: String,
    value: SpaceSwipeAction,
    default: SpaceSwipeAction,
    onChange: (SpaceSwipeAction) -> Unit,
) {
    val nothing = stringResource(R.string.home_space_swipe_none_label)
    val language = stringResource(R.string.home_space_swipe_language_label)
    val cursor = stringResource(R.string.home_space_swipe_cursor_label)
    val numpad = stringResource(R.string.home_space_swipe_numpad_label)
    val keyboards = stringResource(R.string.home_space_swipe_keyboards_label)
    ChoiceSetting(
        title = title,
        subtitle = subtitle,
        info = info,
        options = SpaceSwipeAction.entries.map { action ->
            action to when (action) {
                SpaceSwipeAction.NONE -> nothing
                SpaceSwipeAction.LANGUAGE -> language
                SpaceSwipeAction.CURSOR -> cursor
                SpaceSwipeAction.NUMPAD -> numpad
                SpaceSwipeAction.KEYBOARDS -> keyboards
            }
        },
        selected = value,
        default = default,
        detail = { action -> ChoiceDetail(stringResource(spaceSwipeDescRes(action))) },
        onChange = onChange,
    )
}
// ---- typing ----

@Composable
@Suppress("UnusedParameter")
internal fun TypingSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onOpenDictionary: () -> Unit,
    onOpenCustomDictionaries: () -> Unit,
    onOpenBlacklist: () -> Unit,
    onOpenHardwareShortcuts: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    SettingsGroup {
        item {
            NavRow(
                R.string.typing_group_corrections_title,
                stringResource(R.string.typing_group_corrections_subtitle),
                route = "typing/corrections",
            ) {
                onNavigate("typing/corrections")
            }
        }
        item {
            NavRow(
                R.string.typing_group_suggestions_title,
                stringResource(R.string.typing_group_suggestions_subtitle),
                route = "typing/suggestions",
            ) {
                onNavigate("typing/suggestions")
            }
        }
        item {
            NavRow(
                R.string.typing_group_smart_chips_title,
                stringResource(R.string.typing_group_smart_chips_subtitle),
                route = "typing/chips",
            ) {
                onNavigate("typing/chips")
            }
        }
        item {
            NavRow(R.string.typing_group_otp_title, stringResource(R.string.typing_group_otp_subtitle), route = "typing/codes") {
                onNavigate("typing/codes")
            }
        }
        item {
            NavRow(
                R.string.typing_group_gestures_title,
                stringResource(R.string.typing_group_gestures_subtitle),
                route = "typing/gestures",
            ) {
                onNavigate("typing/gestures")
            }
        }
        item {
            NavRow(
                R.string.typing_group_hardware_title,
                stringResource(R.string.typing_group_hardware_subtitle),
                route = "typing/hardware",
            ) {
                onNavigate("typing/hardware")
            }
        }
    }





    // The Backspace group lives on Key press since #136: what a key does under
    // a swipe is a key-press matter, and this screen is the typing engine's.

    // Decides which rows the volume group holds; the rows read everything else.
    val volumeCursor = settings.watch { it.volumeCursor }
    SettingsGroup(stringResource(R.string.typing_group_enter_title)) {
        item {
            ToggleSetting(
                R.string.typing_shift_enter_title,
                stringResource(R.string.typing_shift_enter_subtitle),
                settings.watch { it.layoutBehavior.shiftEnterNewline },
                info = stringResource(R.string.typing_shift_enter_info),
                default = SettingsDefaults.layoutBehavior.shiftEnterNewline,
            ) { scope.launch { repository.setShiftEnterNewline(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.typing_group_volume_title)) {
        item {
            ToggleSetting(
                R.string.typing_volume_cursor_title,
                stringResource(R.string.typing_volume_cursor_subtitle),
                volumeCursor,
                info = stringResource(R.string.typing_volume_cursor_info),
                default = SettingsDefaults.volumeCursor,
            ) { scope.launch { repository.setVolumeCursor(it) } }
        }
        item(visible = volumeCursor) {
            ToggleSetting(
                R.string.typing_volume_cursor_media_title,
                stringResource(R.string.typing_volume_cursor_media_subtitle),
                settings.watch { it.volumeCursorMediaAware },
                info = stringResource(R.string.typing_volume_cursor_media_info),
                default = SettingsDefaults.volumeCursorMediaAware,
            ) { scope.launch { repository.setVolumeCursorMediaAware(it) } }
        }
    }

}

@Composable
internal fun TypingCorrectionsSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onOpenLearnedCorrections: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // What decides which rows the group holds; each row reads its own value.
    val autocorrectOn = settings.watch { it.correction.enabled }
    val undoChipOn = settings.watch { it.suggestionStrip.undoCorrectionChip }
    val detectionOn = settings.watch { it.suggestionStrip.languageDetection }
    val numberRowOn = settings.watch { it.numberRow }
    val doubleSpaceOn = settings.watch { it.autoText.doubleSpacePeriod || it.autoText.doubleSpaceTab }
    val hugOn = settings.watch { it.autoText.hugPunctuation }
    SettingsGroup(stringResource(R.string.typing_group_corrections_title)) {
        item {
            ToggleSetting(
                R.string.typing_autocorrect_title,
                stringResource(R.string.typing_autocorrect_subtitle),
                autocorrectOn,
                info = stringResource(R.string.typing_autocorrect_info),
                default = SettingsDefaults.correction.enabled,
            ) { scope.launch { repository.setAutocorrect(it) } }
        }
        if (autocorrectOn) {
            item {
                val valueFormat = stringResource(R.string.typing_value_multiplier_prefix)
                SliderSetting(
                    R.string.typing_autocorrect_confidence_title,
                    subtitle = stringResource(R.string.typing_autocorrect_confidence_subtitle),
                    value = settings.watch { it.correction.confidence },
                    range = 1.5f..10f,
                    display = { valueFormat.format("%.1f".format(it)) },
                    info = stringResource(R.string.typing_autocorrect_confidence_info),
                    default = SettingsDefaults.correction.confidence,
                ) { scope.launch { repository.setAutocorrectConfidence(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_autocorrect_adaptive_title,
                    stringResource(R.string.typing_autocorrect_adaptive_subtitle),
                    settings.watch { it.correction.adaptive },
                    info = stringResource(R.string.typing_autocorrect_adaptive_info),
                    default = SettingsDefaults.correction.adaptive,
                ) { scope.launch { repository.setAutocorrectAdaptive(it) } }
            }
            item {
                val percentFormat = stringResource(R.string.typing_value_percent)
                SliderSetting(
                    R.string.typing_timing_signal_title,
                    subtitle = stringResource(R.string.typing_timing_signal_subtitle),
                    value = settings.watch { it.suggestionStrip.timingSignalStrength },
                    range = 0f..1f,
                    display = { percentFormat.format((it * 100).toInt()) },
                    info = stringResource(R.string.typing_timing_signal_info),
                    default = SettingsDefaults.suggestionStrip.timingSignalStrength,
                ) { scope.launch { repository.setTimingSignalStrength(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_undo_autocorrect_title,
                    stringResource(R.string.typing_undo_autocorrect_subtitle),
                    settings.watch { it.correction.revertOnBackspace },
                    info = stringResource(R.string.typing_undo_autocorrect_info),
                    default = SettingsDefaults.correction.revertOnBackspace,
                ) { scope.launch { repository.setRevertAutocorrectOnBackspace(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_undo_chip_title,
                    stringResource(R.string.typing_undo_chip_subtitle),
                    undoChipOn,
                    info = stringResource(R.string.typing_undo_chip_info),
                    default = SettingsDefaults.suggestionStrip.undoCorrectionChip,
                ) { scope.launch { repository.setUndoCorrectionChip(it) } }
            }
            item(visible = undoChipOn) {
                val percentFormat = stringResource(R.string.typing_value_percent)
                SliderSetting(
                    R.string.typing_undo_chip_obviousness_title,
                    subtitle = stringResource(R.string.typing_undo_chip_obviousness_subtitle),
                    value = settings.watch { it.suggestionStrip.undoChipObviousness },
                    range = 0f..1f,
                    display = { percentFormat.format((it * 100).toInt()) },
                    info = stringResource(R.string.typing_undo_chip_obviousness_info),
                    default = SettingsDefaults.suggestionStrip.undoChipObviousness,
                ) { scope.launch { repository.setUndoChipObviousness(it) } }
            }
            item {
                ChoiceSetting(
                    R.string.typing_undo_memory_title,
                    subtitle = stringResource(R.string.typing_undo_memory_subtitle),
                    info = stringResource(R.string.typing_undo_memory_info),
                    options = listOf(
                        UndoMemory.OFF to stringResource(R.string.typing_undo_memory_off),
                        UndoMemory.LIGHT to stringResource(R.string.typing_undo_memory_light),
                        UndoMemory.NORMAL to stringResource(R.string.typing_undo_memory_normal),
                        UndoMemory.STRICT to stringResource(R.string.typing_undo_memory_strict),
                    ),
                    selected = settings.watch { it.correction.undoMemory },
                    default = SettingsDefaults.correction.undoMemory,
                    detail = { level -> ChoiceDetail(stringResource(undoMemoryDescRes(level))) },
                ) { scope.launch { repository.setAutocorrectUndoMemory(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_learn_corrections_title,
                    stringResource(R.string.typing_learn_corrections_subtitle),
                    settings.watch { it.suggestionStrip.learnFromCorrections },
                    info = stringResource(R.string.typing_learn_corrections_info),
                    default = SettingsDefaults.suggestionStrip.learnFromCorrections,
                ) { scope.launch { repository.setLearnFromCorrections(it) } }
            }
            item {
                NavRow(
                    R.string.typing_learned_corrections_title,
                    stringResource(R.string.typing_learned_corrections_subtitle),
                    route = "learnedcorrections",
                    onClick = onOpenLearnedCorrections,
                )
            }
            item {
                ToggleSetting(
                    R.string.typing_adapt_taps_title,
                    stringResource(R.string.typing_adapt_taps_subtitle),
                    settings.watch { it.suggestionStrip.adaptToTaps },
                    info = stringResource(R.string.typing_adapt_taps_info),
                    default = SettingsDefaults.suggestionStrip.adaptToTaps,
                ) { scope.launch { repository.setAdaptToTaps(it) } }
            }
            // Issue #385: how far a tap may stray into a neighbour.
            item {
                SliderSetting(
                    R.string.typing_mistype_tolerance_title,
                    subtitle = stringResource(R.string.typing_mistype_tolerance_subtitle),
                    value = settings.watch { it.suggestionStrip.mistypeTolerance }.toFloat(),
                    range = 50f..200f,
                    // Steps of ten: finer than that is not a difference anyone feels.
                    display = { "${(it.toInt() + 5) / 10 * 10}%" },
                    info = stringResource(R.string.typing_mistype_tolerance_info),
                    default = SettingsDefaults.suggestionStrip.mistypeTolerance.toFloat(),
                ) { scope.launch { repository.setMistypeTolerance((it.toInt() + 5) / 10 * 10) } }
            }
            item { ForgetTapModelRow(repository) }
            item {
                ToggleSetting(
                    R.string.typing_skip_all_caps_title,
                    stringResource(R.string.typing_skip_all_caps_subtitle),
                    settings.watch { it.correction.skipAllCaps },
                    info = stringResource(R.string.typing_skip_all_caps_info),
                    default = SettingsDefaults.correction.skipAllCaps,
                ) { scope.launch { repository.setAutocorrectSkipAllCaps(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_autocorrect_on_enter_title,
                    stringResource(R.string.typing_autocorrect_on_enter_subtitle),
                    settings.watch { it.correction.onEnter },
                    info = stringResource(R.string.typing_autocorrect_on_enter_info),
                    default = SettingsDefaults.correction.onEnter,
                ) { scope.launch { repository.setAutocorrectOnEnter(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_dictionary_capitals_title,
                    stringResource(R.string.typing_dictionary_capitals_subtitle),
                    settings.watch { it.correction.dictionaryCapitals },
                    info = stringResource(R.string.typing_dictionary_capitals_info),
                    default = SettingsDefaults.correction.dictionaryCapitals,
                ) { scope.launch { repository.setDictionaryCapitals(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_block_offensive_title,
                    stringResource(R.string.typing_block_offensive_subtitle),
                    settings.watch { it.suggestionStrip.blockOffensiveWords },
                    info = stringResource(R.string.typing_block_offensive_info),
                    default = SettingsDefaults.suggestionStrip.blockOffensiveWords,
                ) { scope.launch { repository.setBlockOffensiveWords(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_context_rerank_title,
                    stringResource(R.string.typing_context_rerank_subtitle),
                    settings.watch { it.suggestionStrip.contextRerank },
                    info = stringResource(R.string.typing_context_rerank_info),
                    default = SettingsDefaults.suggestionStrip.contextRerank,
                ) { scope.launch { repository.setContextRerank(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_autocorrect_splits_title,
                    stringResource(R.string.typing_autocorrect_splits_subtitle),
                    settings.watch { it.suggestionStrip.autocorrectSplits },
                    info = stringResource(R.string.typing_autocorrect_splits_info),
                    default = SettingsDefaults.suggestionStrip.autocorrectSplits,
                ) { scope.launch { repository.setAutocorrectSplits(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_language_detection_title,
                    stringResource(R.string.typing_language_detection_subtitle),
                    detectionOn,
                    info = stringResource(R.string.typing_language_detection_info),
                    default = SettingsDefaults.suggestionStrip.languageDetection,
                ) { scope.launch { repository.setLanguageDetection(it) } }
            }
            item(visible = detectionOn) {
                ChoiceSetting(
                    R.string.typing_language_detection_strength_title,
                    info = stringResource(R.string.typing_language_detection_strength_info),
                    options = listOf(
                        LanguageDetectionStrength.GENTLE to
                            stringResource(R.string.typing_language_detection_gentle),
                        LanguageDetectionStrength.BALANCED to
                            stringResource(R.string.typing_language_detection_balanced),
                        LanguageDetectionStrength.AGGRESSIVE to
                            stringResource(R.string.typing_language_detection_aggressive),
                    ),
                    selected = settings.watch { it.suggestionStrip.languageDetectionStrength },
                    default = SettingsDefaults.suggestionStrip.languageDetectionStrength,
                    detail = { strength ->
                        ChoiceDetail(stringResource(detectionStrengthDescRes(strength)))
                    },
                ) { scope.launch { repository.setLanguageDetectionStrength(it) } }
            }
            item(visible = detectionOn) {
                ToggleSetting(
                    R.string.typing_language_detection_by_app_title,
                    stringResource(R.string.typing_language_detection_by_app_subtitle),
                    settings.watch { it.suggestionStrip.languageDetectionByApp },
                    info = stringResource(R.string.typing_language_detection_by_app_info),
                    default = SettingsDefaults.suggestionStrip.languageDetectionByApp,
                ) { scope.launch { repository.setLanguageDetectionByApp(it) } }
            }
            item(visible = numberRowOn) {
                ToggleSetting(
                    R.string.typing_number_row_corrections_title,
                    stringResource(R.string.typing_number_row_corrections_subtitle),
                    settings.watch { it.suggestionStrip.numberRowCorrections },
                    info = stringResource(R.string.typing_number_row_corrections_info),
                    default = SettingsDefaults.suggestionStrip.numberRowCorrections,
                ) { scope.launch { repository.setNumberRowCorrections(it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_register_priors_title,
                stringResource(R.string.typing_register_priors_subtitle),
                settings.watch { it.suggestionStrip.registerPriors },
                info = stringResource(R.string.typing_register_priors_info),
                default = SettingsDefaults.suggestionStrip.registerPriors,
            ) { scope.launch { repository.setRegisterPriors(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_auto_apostrophe_title,
                stringResource(R.string.typing_auto_apostrophe_subtitle),
                settings.watch { it.autoText.apostrophe },
                info = stringResource(R.string.typing_auto_apostrophe_info),
                default = SettingsDefaults.autoText.apostrophe,
            ) { scope.launch { repository.setAutoApostrophe(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_auto_capitalize_title,
                stringResource(R.string.typing_auto_capitalize_subtitle),
                settings.watch { it.autoText.capitalize },
                info = stringResource(R.string.typing_auto_capitalize_info),
                default = SettingsDefaults.autoText.capitalize,
            ) { scope.launch { repository.setAutoCapitalize(it) } }
        }
        item {
            val doubleSpace = settings.watch {
                when {
                    it.autoText.doubleSpaceTab -> DoubleSpaceAction.TAB
                    it.autoText.doubleSpacePeriod -> DoubleSpaceAction.PERIOD
                    else -> DoubleSpaceAction.NONE
                }
            }
            ChoiceSetting(
                R.string.typing_double_space_title,
                subtitle = stringResource(R.string.typing_double_space_subtitle),
                info = stringResource(R.string.typing_double_space_info),
                options = DoubleSpaceAction.entries.map { it to stringResource(it.labelRes) },
                selected = doubleSpace,
                default = DefaultDoubleSpace,
                detail = { action -> ChoiceDetail(stringResource(action.descRes), action.icon) },
            ) { action ->
                scope.launch {
                    repository.setDoubleSpacePeriod(action == DoubleSpaceAction.PERIOD)
                    repository.setDoubleSpaceTab(action == DoubleSpaceAction.TAB)
                }
            }
        }
        item(visible = doubleSpaceOn) {
            SliderSetting(
                R.string.typing_double_space_window_title,
                subtitle = stringResource(R.string.typing_double_space_window_subtitle),
                value = settings.watch { it.textEditing.doubleSpaceWindowMs }.toFloat(),
                range = 200f..800f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.typing_double_space_window_info),
                default = SettingsDefaults.textEditing.doubleSpaceWindowMs.toFloat(),
            ) { scope.launch { repository.setDoubleSpaceWindowMs(it.toInt()) } }
        }
        item {
            ToggleSetting(
                R.string.typing_auto_space_punctuation_title,
                stringResource(R.string.typing_auto_space_punctuation_subtitle),
                settings.watch { it.autoText.spaceAfterPunctuation },
                info = stringResource(R.string.typing_auto_space_punctuation_info),
                default = SettingsDefaults.autoText.spaceAfterPunctuation,
            ) { scope.launch { repository.setAutoSpaceAfterPunctuation(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hug_punctuation_title,
                stringResource(R.string.typing_hug_punctuation_subtitle),
                hugOn,
                info = stringResource(R.string.typing_hug_punctuation_info),
                default = SettingsDefaults.autoText.hugPunctuation,
            ) { scope.launch { repository.setHugPunctuation(it) } }
        }
        item(visible = hugOn) {
            TextFieldSetting(
                label = stringResource(R.string.typing_hug_punctuation_marks_title),
                value = settings.watch { it.autoText.hugPunctuationMarks },
                hint = stringResource(R.string.typing_hug_punctuation_marks_hint),
                default = SettingsDefaults.autoText.hugPunctuationMarks,
            ) { repository.setHugPunctuationMarks(it) }
        }
        item {
            ToggleSetting(
                R.string.typing_language_punctuation_spacing_title,
                stringResource(R.string.typing_language_punctuation_spacing_subtitle),
                settings.watch { it.autoText.languagePunctuationSpacing },
                info = stringResource(R.string.typing_language_punctuation_spacing_info),
                default = SettingsDefaults.autoText.languagePunctuationSpacing,
            ) { scope.launch { repository.setLanguagePunctuationSpacing(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_space_after_suggestion_title,
                stringResource(R.string.typing_space_after_suggestion_subtitle),
                settings.watch { it.suggestionStrip.autoSpaceAfterSuggestion },
                info = stringResource(R.string.typing_space_after_suggestion_info),
                default = SettingsDefaults.suggestionStrip.autoSpaceAfterSuggestion,
            ) { scope.launch { repository.setAutoSpaceAfterSuggestion(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_wrap_selection_title,
                stringResource(R.string.typing_wrap_selection_subtitle),
                settings.watch { it.textEditing.wrapSelectionWithPair },
                info = stringResource(R.string.typing_wrap_selection_info),
                default = SettingsDefaults.textEditing.wrapSelectionWithPair,
            ) { scope.launch { repository.setWrapSelectionWithPair(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_auto_close_brackets_title,
                stringResource(R.string.typing_auto_close_brackets_subtitle),
                settings.watch { it.textEditing.autoCloseBrackets },
                info = stringResource(R.string.typing_auto_close_brackets_info),
                default = SettingsDefaults.textEditing.autoCloseBrackets,
            ) { scope.launch { repository.setAutoCloseBrackets(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_shift_recase_title,
                stringResource(R.string.typing_shift_recase_subtitle),
                settings.watch { it.textEditing.recapitalizeSelectionWithShift },
                info = stringResource(R.string.typing_shift_recase_info),
                default = SettingsDefaults.textEditing.recapitalizeSelectionWithShift,
            ) { scope.launch { repository.setRecapitalizeSelectionWithShift(it) } }
        }
    }
}

@Composable
internal fun TypingSuggestionsSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onOpenDictionary: () -> Unit,
    onOpenCustomDictionaries: () -> Unit,
    onOpenBlacklist: () -> Unit,
    onOpenAutopilot: () -> Unit,
    onOpenOctopus: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // What decides which rows the group holds; each row reads its own value.
    val suggestionsOn = settings.watch { it.suggestions }
    val punctuationOn = settings.watch { it.suggestionStrip.punctuation }
    val autocorrectOn = settings.watch { it.correction.enabled }
    val askBeforeLearning = settings.watch { it.suggestionStrip.askBeforeLearning }
    val scrollable = settings.watch { it.suggestionStrip.scrollable }
    val contactEmailsOn = settings.watch { it.suggestionSources.contactEmails }
    SettingsGroup(stringResource(R.string.typing_group_suggestions_title)) {
        item {
            ToggleSetting(
                R.string.typing_suggestions_title,
                stringResource(R.string.typing_suggestions_subtitle),
                suggestionsOn,
                info = stringResource(R.string.typing_suggestions_info),
                default = SettingsDefaults.suggestions,
            ) { scope.launch { repository.setSuggestions(it) } }
        }
        // The strip refresh returns before it reads any of this while
        // suggestions are off, so the chips never get built.
        if (suggestionsOn) item {
            ToggleSetting(
                R.string.typing_punctuation_suggestions_title,
                stringResource(R.string.typing_punctuation_suggestions_subtitle),
                punctuationOn,
                info = stringResource(R.string.typing_punctuation_suggestions_info),
                default = SettingsDefaults.suggestionStrip.punctuation,
            ) { scope.launch { repository.setPunctuationSuggestions(it) } }
        }
        item(visible = suggestionsOn && punctuationOn) {
            TextFieldSetting(
                label = stringResource(R.string.typing_punctuation_marks_title),
                value = settings.watch { it.suggestionStrip.punctuationChips },
                hint = stringResource(R.string.typing_punctuation_marks_hint),
                default = SettingsDefaults.suggestionStrip.punctuationChips,
            ) { repository.setPunctuationChips(it) }
        }
        item {
            SliderSetting(
                R.string.typing_suggestion_slots_title,
                subtitle = stringResource(R.string.typing_suggestion_slots_subtitle),
                value = settings.watch { it.suggestionStrip.slotCount }.toFloat(),
                range = 2f..6f,
                display = { it.toInt().toString() },
                info = stringResource(R.string.typing_suggestion_slots_info),
                default = SettingsDefaults.suggestionStrip.slotCount.toFloat(),
            ) { scope.launch { repository.setSuggestionSlotCount(it.toInt()) } }
        }
        item {
            // Issue #385: the words the strip has no room for.
            ToggleSetting(
                R.string.typing_suggestion_pages_title,
                stringResource(R.string.typing_suggestion_pages_subtitle),
                settings.watch { it.suggestionStrip.swipeForMore },
                info = stringResource(R.string.typing_suggestion_pages_info),
                default = SettingsDefaults.suggestionStrip.swipeForMore,
            ) { scope.launch { repository.setSuggestionsSwipeForMore(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_suggestion_emoji_slot_title,
                stringResource(R.string.typing_suggestion_emoji_slot_subtitle),
                settings.watch { it.suggestionStrip.emojiTakesSlot },
                info = stringResource(R.string.typing_suggestion_emoji_slot_info),
                default = SettingsDefaults.suggestionStrip.emojiTakesSlot,
            ) { scope.launch { repository.setSuggestionEmojiTakesSlot(it) } }
        }
        item {
            // Issue #509: the tail of emoji after the words.
            SliderSetting(
                R.string.typing_suggestion_emoji_count_title,
                subtitle = stringResource(R.string.typing_suggestion_emoji_count_subtitle),
                value = settings.watch { it.suggestionStrip.emojiCount }.toFloat(),
                range = 1f..4f,
                display = { it.toInt().toString() },
                info = stringResource(R.string.typing_suggestion_emoji_count_info),
                default = SettingsDefaults.suggestionStrip.emojiCount.toFloat(),
            ) { scope.launch { repository.setSuggestionEmojiCount(it.toInt()) } }
        }
        item {
            // Issue #513: words that stay where the eye expects them.
            ToggleSetting(
                R.string.typing_suggestion_fixed_slots_title,
                stringResource(R.string.typing_suggestion_fixed_slots_subtitle),
                settings.watch { it.suggestionStrip.fixedSlots },
                info = stringResource(R.string.typing_suggestion_fixed_slots_info),
                default = SettingsDefaults.suggestionStrip.fixedSlots,
            ) { scope.launch { repository.setSuggestionFixedSlots(it) } }
        }
        item {
            // Issue #510: slots told apart by colour.
            ToggleSetting(
                R.string.typing_suggestion_tinted_title,
                stringResource(R.string.typing_suggestion_tinted_subtitle),
                settings.watch { it.suggestionStrip.tintedSlots },
                info = stringResource(R.string.typing_suggestion_tinted_info),
                default = SettingsDefaults.suggestionStrip.tintedSlots,
            ) { scope.launch { repository.setSuggestionTintedSlots(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_suggestion_scroll_title,
                stringResource(R.string.typing_suggestion_scroll_subtitle),
                scrollable,
                info = stringResource(R.string.typing_suggestion_scroll_info),
                default = SettingsDefaults.suggestionStrip.scrollable,
            ) { scope.launch { repository.setSuggestionScrollable(it) } }
        }
        item {
            val once = stringResource(R.string.typing_learn_threshold_once)
            SliderSetting(
                R.string.typing_learn_threshold_title,
                subtitle = stringResource(R.string.typing_learn_threshold_subtitle),
                value = settings.watch { it.suggestionStrip.learnedWordMinCount }.toFloat(),
                range = 1f..5f,
                display = { if (it.toInt() <= 1) once else it.toInt().toString() },
                info = stringResource(R.string.typing_learn_threshold_info),
                default = SettingsDefaults.suggestionStrip.learnedWordMinCount.toFloat(),
            ) { scope.launch { repository.setLearnedWordMinCount(it.toInt()) } }
        }
        // A near miss is only ever offered from the autocorrect branch, so
        // with autocorrect off there is nothing to widen.
        if (autocorrectOn) item {
            ToggleSetting(
                R.string.typing_offer_near_miss_title,
                stringResource(R.string.typing_offer_near_miss_subtitle),
                settings.watch { it.suggestionStrip.offerNearMissCorrections },
                info = stringResource(R.string.typing_offer_near_miss_info),
                default = SettingsDefaults.suggestionStrip.offerNearMissCorrections,
            ) { scope.launch { repository.setOfferNearMissCorrections(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_ask_before_learning_title,
                stringResource(R.string.typing_ask_before_learning_subtitle),
                askBeforeLearning,
                info = stringResource(R.string.typing_ask_before_learning_info),
                default = SettingsDefaults.suggestionStrip.askBeforeLearning,
            ) { scope.launch { repository.setAskBeforeLearning(it) } }
        }
        item(visible = !askBeforeLearning) {
            val immediately = stringResource(R.string.typing_new_word_sightings_once)
            SliderSetting(
                R.string.typing_new_word_sightings_title,
                subtitle = stringResource(R.string.typing_new_word_sightings_subtitle),
                value = settings.watch { it.suggestionStrip.newWordSightings }.toFloat(),
                range = 1f..10f,
                display = { if (it.toInt() <= 1) immediately else it.toInt().toString() },
                info = stringResource(R.string.typing_new_word_sightings_info),
                default = SettingsDefaults.suggestionStrip.newWordSightings.toFloat(),
            ) { scope.launch { repository.setNewWordSightings(it.toInt()) } }
        }
        item {
            ToggleSetting(
                R.string.typing_suggestions_all_fields_title,
                stringResource(R.string.typing_suggestions_all_fields_subtitle),
                settings.watch { it.suggestionSources.inAllFields },
                info = stringResource(R.string.typing_suggestions_all_fields_info),
                default = SettingsDefaults.suggestionSources.inAllFields,
            ) { scope.launch { repository.setShowSuggestionsInAllFields(it) } }
        }
        if (suggestionsOn) item {
            ToggleSetting(
                R.string.typing_suggestions_first_title,
                stringResource(R.string.typing_suggestions_first_subtitle),
                settings.watch { it.suggestionStrip.suggestionsFirst },
                info = stringResource(R.string.typing_suggestions_first_info),
                default = SettingsDefaults.suggestionStrip.suggestionsFirst,
            ) { scope.launch { repository.setSuggestionsFirst(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_primary_center_title,
                stringResource(R.string.typing_primary_center_subtitle),
                settings.watch { it.suggestionStrip.suggestionPrimaryCenter },
                info = stringResource(R.string.typing_primary_center_info),
                default = SettingsDefaults.suggestionStrip.suggestionPrimaryCenter,
            ) { scope.launch { repository.setSuggestionPrimaryCenter(it) } }
        }
        // A scrolling strip draws every word whole, so there is nothing to cut.
        if (!scrollable) item {
            ChoiceSetting(
                R.string.typing_suggestion_overflow_title,
                subtitle = stringResource(R.string.typing_suggestion_overflow_subtitle),
                info = stringResource(R.string.typing_suggestion_overflow_info),
                options = listOf(
                    SuggestionOverflow.MIDDLE to stringResource(R.string.typing_suggestion_overflow_middle),
                    SuggestionOverflow.END to stringResource(R.string.typing_suggestion_overflow_end),
                ),
                selected = settings.watch { it.suggestionStrip.overflow },
                default = SettingsDefaults.suggestionStrip.overflow,
            ) { scope.launch { repository.setSuggestionOverflow(it) } }
        }
        item {
            val permissionContext = LocalContext.current
            // Prominent disclosure before the system prompt, never the prompt on
            // its own: see PermissionDisclosure.
            val contactsPermission =
                rememberDisclosedPermissionRequest(PermissionDisclosures.CONTACT_NAMES) {
                    scope.launch { repository.setContactSuggestions(true) }
                }
            ToggleSetting(
                R.string.typing_contact_names_title,
                stringResource(R.string.typing_contact_names_subtitle),
                settings.watch { it.suggestionSources.contacts },
                info = stringResource(R.string.typing_contact_names_info),
                default = SettingsDefaults.suggestionSources.contacts,
            ) { enabled ->
                when {
                    !enabled -> scope.launch { repository.setContactSuggestions(false) }
                    permissionContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                        PackageManager.PERMISSION_GRANTED ->
                        scope.launch { repository.setContactSuggestions(true) }
                    else -> contactsPermission()
                }
            }
        }
        item {
            val permissionContext = LocalContext.current
            val emailPermission =
                rememberDisclosedPermissionRequest(PermissionDisclosures.CONTACT_EMAILS) {
                    scope.launch { repository.setContactEmailSuggestions(true) }
                }
            ToggleSetting(
                R.string.typing_contact_emails_title,
                stringResource(R.string.typing_contact_emails_subtitle),
                contactEmailsOn,
                info = stringResource(R.string.typing_contact_emails_info),
                default = SettingsDefaults.suggestionSources.contactEmails,
            ) { enabled ->
                when {
                    !enabled -> scope.launch { repository.setContactEmailSuggestions(false) }
                    permissionContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                        PackageManager.PERMISSION_GRANTED ->
                        scope.launch { repository.setContactEmailSuggestions(true) }
                    else -> emailPermission()
                }
            }
        }
        item(visible = contactEmailsOn) {
            ToggleSetting(
                R.string.typing_contact_emails_in_email_fields_title,
                stringResource(R.string.typing_contact_emails_in_email_fields_subtitle),
                settings.watch { it.suggestionSources.contactEmailsInEmailFields },
                info = stringResource(R.string.typing_contact_emails_in_email_fields_info),
                default = SettingsDefaults.suggestionSources.contactEmailsInEmailFields,
            ) { scope.launch { repository.setContactEmailSuggestionsInEmailFields(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_typed_emails_title,
                stringResource(R.string.typing_typed_emails_subtitle),
                settings.watch { it.suggestionSources.typedEmails },
                info = stringResource(R.string.typing_typed_emails_info),
                default = SettingsDefaults.suggestionSources.typedEmails,
            ) { scope.launch { repository.setTypedEmailSuggestions(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_app_names_title,
                stringResource(R.string.typing_app_names_subtitle),
                settings.watch { it.suggestionSources.appNames },
                info = stringResource(R.string.typing_app_names_info),
                default = SettingsDefaults.suggestionSources.appNames,
            ) { scope.launch { repository.setAppNameSuggestions(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_inline_emoji_search_title,
                stringResource(R.string.typing_inline_emoji_search_subtitle),
                settings.watch { it.suggestionSources.inlineEmojiSearch },
                info = stringResource(R.string.typing_inline_emoji_search_info),
                default = SettingsDefaults.suggestionSources.inlineEmojiSearch,
            ) { scope.launch { repository.setInlineEmojiSearch(it) } }
        }
        item(visible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ToggleSetting(
                R.string.typing_inline_autofill_title,
                stringResource(R.string.typing_inline_autofill_subtitle),
                settings.watch { it.suggestionSources.inlineAutofill },
                info = stringResource(R.string.typing_inline_autofill_info),
                default = SettingsDefaults.suggestionSources.inlineAutofill,
            ) { scope.launch { repository.setInlineAutofill(it) } }
        }
        item(visible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ToggleSetting(
                R.string.typing_smart_replies_title,
                stringResource(R.string.typing_smart_replies_subtitle),
                settings.watch { it.suggestionStrip.systemSmartReplies },
                info = stringResource(R.string.typing_smart_replies_info),
                default = SettingsDefaults.suggestionStrip.systemSmartReplies,
            ) { scope.launch { repository.setSystemSmartReplies(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_skip_typed_word_title,
                stringResource(R.string.typing_skip_typed_word_subtitle),
                settings.watch { it.suggestionStrip.skipTypedWord },
                info = stringResource(R.string.typing_skip_typed_word_info),
                default = SettingsDefaults.suggestionStrip.skipTypedWord,
            ) { scope.launch { repository.setSkipTypedWord(it) } }
        }
        item {
            // Issue #181: a word typed on digit-hinted keys also offers the number.
            ToggleSetting(
                R.string.typing_number_prediction_title,
                stringResource(R.string.typing_number_prediction_subtitle),
                settings.watch { it.suggestionStrip.numberPrediction },
                info = stringResource(R.string.typing_number_prediction_info),
                default = SettingsDefaults.suggestionStrip.numberPrediction,
            ) { scope.launch { repository.setNumberPrediction(it) } }
        }
        item {
            NavRow(
                R.string.typing_group_autopilot_title,
                stringResource(R.string.typing_group_autopilot_subtitle),
                route = "typing/autopilot",
                onClick = onOpenAutopilot,
            )
        }
        item {
            NavRow(
                R.string.typing_group_octopus_title,
                stringResource(R.string.typing_group_octopus_subtitle),
                route = "typing/octopus",
                onClick = onOpenOctopus,
            )
        }
        item {
            NavRow(
                R.string.typing_personal_dictionary_title,
                stringResource(R.string.typing_personal_dictionary_subtitle),
                route = "dictionary",
                onClick = onOpenDictionary,
            )
        }
        item {
            NavRow(
                R.string.typing_custom_dictionaries_title,
                stringResource(R.string.typing_custom_dictionaries_subtitle),
                route = "customdictionaries",
                onClick = onOpenCustomDictionaries,
            )
        }
        item {
            val count = settings.watch { it.suggestionSources.blacklistCount }
            NavRow(
                R.string.typing_blacklist_title,
                if (count == 0) {
                    stringResource(R.string.typing_blacklist_subtitle)
                } else {
                    pluralStringResource(R.plurals.typing_blacklist_count_subtitle, count, count)
                },
                route = "blacklist",
                onClick = onOpenBlacklist,
            )
        }
        item {
            val neverSuggest = stringResource(R.string.typing_word_menu_item_never_suggest)
            val add = stringResource(R.string.typing_word_menu_item_add)
            val delete = stringResource(R.string.typing_word_menu_item_delete)
            val synonyms = stringResource(R.string.typing_word_menu_item_synonyms)
            MultiChoiceSetting(
                R.string.typing_word_menu_title,
                subtitle = stringResource(R.string.typing_word_menu_subtitle),
                info = stringResource(R.string.typing_word_menu_info),
                options = WordMenuItem.entries.map { item ->
                    item to when (item) {
                        WordMenuItem.NEVER_SUGGEST -> neverSuggest
                        WordMenuItem.ADD -> add
                        WordMenuItem.DELETE -> delete
                        WordMenuItem.SYNONYMS -> synonyms
                    }
                },
                selected = settings.watch { it.suggestionStrip.wordMenuItems },
                default = SettingsDefaults.suggestionStrip.wordMenuItems,
            ) { scope.launch { repository.setWordMenuItems(it) } }
        }
        item {
            // Where Synonyms looks (#321), in the order it asks: a source is
            // only asked when those above it had nothing or were unreachable.
            val sources = settings.watch { it.suggestionStrip.synonymSources }
            val save: (List<SynonymSourceChoice>) -> Unit = { scope.launch { repository.setSynonymSources(it) } }
            ControlSetting(
                R.string.typing_synonym_sources_title,
                subtitle = stringResource(R.string.typing_synonym_sources_subtitle),
                info = stringResource(R.string.typing_synonym_sources_info),
            ) {
                val names = SynonymSource.entries.associateWith { stringResource(it.labelRes) }
                ReorderableColumn(
                    items = sources,
                    label = { names[it.source].orEmpty() },
                    onReorder = save,
                    modifier = Modifier.padding(top = 8.dp),
                    rowHeight = SynonymSourceRowHeight,
                ) { choice ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            names[choice.source].orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(choice.source.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = choice.enabled,
                        onCheckedChange = { on ->
                            save(sources.map { if (it.source == choice.source) it.copy(enabled = on) else it })
                        },
                    )
                }
            }
        }
        item {
            val weight = stringResource(R.string.typing_rank_control_weight_label)
            val offset = stringResource(R.string.typing_rank_control_offset_label)
            val both = stringResource(R.string.typing_rank_control_both_label)
            ChoiceSetting(
                R.string.typing_rank_control_title,
                subtitle = stringResource(R.string.typing_rank_control_subtitle),
                info = stringResource(R.string.typing_rank_control_info),
                options = RankControl.entries.map { control ->
                    control to when (control) {
                        RankControl.LEARNED_WEIGHT -> weight
                        RankControl.RANK_OFFSET -> offset
                        RankControl.BOTH -> both
                    }
                },
                selected = settings.watch { it.suggestionStrip.rankControl },
                default = SettingsDefaults.suggestionStrip.rankControl,
                detail = { control ->
                    ChoiceDetail(
                        stringResource(
                            when (control) {
                                RankControl.LEARNED_WEIGHT -> R.string.typing_rank_control_weight_desc
                                RankControl.RANK_OFFSET -> R.string.typing_rank_control_offset_desc
                                RankControl.BOTH -> R.string.typing_rank_control_both_desc
                            },
                        ),
                    )
                },
            ) { scope.launch { repository.setRankControl(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_delete_edits_lists_title,
                stringResource(R.string.typing_delete_edits_lists_subtitle),
                settings.watch { it.suggestionStrip.deleteEditsImportedLists },
                info = stringResource(R.string.typing_delete_edits_lists_info),
                default = SettingsDefaults.suggestionStrip.deleteEditsImportedLists,
            ) { scope.launch { repository.setDeleteEditsImportedLists(it) } }
        }
    }
}

/**
 * Autopilot: the touch areas of the letters the word list expects next, and the
 * two ways of seeing what that is doing.
 *
 * Its own page because the three rows below it are meaningless with the feature
 * off, and the suggestions page already carries twenty rows.
 */
@Composable
internal fun TypingAutopilotSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // What decides which rows the group holds; each row reads its own value.
    val autopilotOn = settings.watch { it.layoutBehavior.smartHitDetection }
    val showEffect = settings.watch { it.layoutBehavior.autopilotShowEffect }
    SettingsGroup {
        item {
            ToggleSetting(
                R.string.typing_smart_hit_detection_title,
                stringResource(R.string.typing_smart_hit_detection_subtitle),
                autopilotOn,
                info = stringResource(R.string.typing_smart_hit_detection_info),
                default = SettingsDefaults.layoutBehavior.smartHitDetection,
            ) { scope.launch { repository.setSmartHitDetection(it) } }
        }
        if (autopilotOn) {
            item {
                SliderSetting(
                    R.string.typing_autopilot_strength_title,
                    subtitle = stringResource(R.string.typing_autopilot_strength_subtitle),
                    value = settings.watch { it.layoutBehavior.autopilotStrength }.toFloat(),
                    range = 1f..10f,
                    display = { context.getString(R.string.values_number, it.toInt()) },
                    info = stringResource(R.string.typing_autopilot_strength_info),
                    default = SettingsDefaults.layoutBehavior.autopilotStrength.toFloat(),
                ) { scope.launch { repository.setAutopilotStrength(it.toInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_autopilot_show_title,
                    stringResource(R.string.typing_autopilot_show_subtitle),
                    showEffect,
                    info = stringResource(R.string.typing_autopilot_show_info),
                    default = SettingsDefaults.layoutBehavior.autopilotShowEffect,
                ) { scope.launch { repository.setAutopilotShowEffect(it) } }
            }
            item(visible = showEffect) {
                val valueFormat = stringResource(R.string.typing_value_multiplier_prefix)
                SliderSetting(
                    R.string.typing_autopilot_size_title,
                    subtitle = stringResource(R.string.typing_autopilot_size_subtitle),
                    value = settings.watch { it.layoutBehavior.autopilotVisualScale },
                    range = 1f..3f,
                    display = { valueFormat.format("%.1f".format(it)) },
                    info = stringResource(R.string.typing_autopilot_size_info),
                    default = SettingsDefaults.layoutBehavior.autopilotVisualScale,
                ) { scope.launch { repository.setAutopilotVisualScale(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_autopilot_outline_title,
                    stringResource(R.string.typing_autopilot_outline_subtitle),
                    settings.watch { it.layoutBehavior.autopilotOutline },
                    info = stringResource(R.string.typing_autopilot_outline_info),
                    default = SettingsDefaults.layoutBehavior.autopilotOutline,
                ) { scope.launch { repository.setAutopilotOutline(it) } }
            }
        }
    }
}

/**
 * The octopus (discussion #102): a predicted word drawn over the key that would
 * reach it, and the two gestures that take it.
 *
 * Its own page for the same reason autopilot has one — every row below the
 * first is meaningless with the feature off, and the suggestions page is full.
 */
@Composable
internal fun TypingOctopusSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // What decides which rows the group holds; each row reads its own value.
    val octopusOn = settings.watch { it.octopus.enabled }
    val glideOn = settings.watch { it.gestureTyping }
    val flickCommits = settings.watch { it.octopus.flickCommits }
    SettingsGroup {
        item {
            ToggleSetting(
                R.string.typing_octopus_enabled_title,
                stringResource(R.string.typing_octopus_enabled_subtitle),
                octopusOn,
                info = stringResource(R.string.typing_octopus_enabled_info),
                default = SettingsDefaults.octopus.enabled,
            ) { scope.launch { repository.setOctopusEnabled(it) } }
        }
        if (octopusOn) {
            item {
                ChoiceSetting(
                    R.string.typing_octopus_placement_title,
                    subtitle = stringResource(R.string.typing_octopus_placement_subtitle),
                    info = stringResource(R.string.typing_octopus_placement_info),
                    options = OctopusPlacement.entries.map { it to stringResource(it.labelRes) },
                    selected = settings.watch { it.octopus.placement },
                    default = SettingsDefaults.octopus.placement,
                ) { scope.launch { repository.setOctopusPlacement(it) } }
            }
            item {
                SliderSetting(
                    R.string.typing_octopus_density_title,
                    subtitle = stringResource(R.string.typing_octopus_density_subtitle),
                    value = settings.watch { it.octopus.density }.toFloat(),
                    range = OctopusSettings.MIN_DENSITY.toFloat()..
                        OctopusSettings.MAX_DENSITY.toFloat(),
                    display = { context.getString(R.string.values_number, it.toInt()) },
                    info = stringResource(R.string.typing_octopus_density_info),
                    default = SettingsDefaults.octopus.density.toFloat(),
                ) { scope.launch { repository.setOctopusDensity(it.toInt()) } }
            }
            item {
                MultiChoiceSetting(
                    R.string.typing_octopus_kinds_title,
                    subtitle = stringResource(R.string.typing_octopus_kinds_subtitle),
                    info = stringResource(R.string.typing_octopus_kinds_info),
                    options = listOf(
                        OctopusKind.COMPLETION to
                            stringResource(R.string.typing_octopus_kind_completion),
                        OctopusKind.CORRECTION to
                            stringResource(R.string.typing_octopus_kind_correction),
                        OctopusKind.NEXT_WORD to
                            stringResource(R.string.typing_octopus_kind_next_word),
                    ),
                    selected = settings.watch { it.octopus.kinds },
                    default = SettingsDefaults.octopus.kinds,
                ) { scope.launch { repository.setOctopusKinds(it) } }
            }
            // Hidden on a board that does not glide: it is a question about a
            // surface that never appears there.
            item(visible = glideOn) {
                ChoiceSetting(
                    R.string.typing_octopus_glide_title,
                    subtitle = stringResource(R.string.typing_octopus_glide_subtitle),
                    info = stringResource(R.string.typing_octopus_glide_info),
                    options = OctopusDuringGlide.entries.map {
                        it to stringResource(it.labelRes)
                    },
                    selected = settings.watch { it.octopus.duringGlide },
                    default = SettingsDefaults.octopus.duringGlide,
                ) { scope.launch { repository.setOctopusDuringGlide(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_octopus_flick_title,
                    stringResource(R.string.typing_octopus_flick_subtitle),
                    flickCommits,
                    info = stringResource(R.string.typing_octopus_flick_info),
                    default = SettingsDefaults.octopus.flickCommits,
                ) { scope.launch { repository.setOctopusFlickCommits(it) } }
            }
            item(visible = flickCommits) {
                ChoiceSetting(
                    R.string.typing_octopus_sensitivity_title,
                    subtitle = stringResource(R.string.typing_octopus_sensitivity_subtitle),
                    info = stringResource(R.string.typing_octopus_sensitivity_info),
                    options = OctopusFlickSensitivity.entries.map {
                        it to stringResource(it.labelRes)
                    },
                    selected = settings.watch { it.octopus.flickSensitivity },
                    default = SettingsDefaults.octopus.flickSensitivity,
                ) { scope.launch { repository.setOctopusFlickSensitivity(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_octopus_tap_title,
                    stringResource(R.string.typing_octopus_tap_subtitle),
                    settings.watch { it.octopus.tapCommits },
                    info = stringResource(R.string.typing_octopus_tap_info),
                    default = SettingsDefaults.octopus.tapCommits,
                ) { scope.launch { repository.setOctopusTapCommits(it) } }
            }
            item {
                val valueFormat = stringResource(R.string.typing_value_multiplier_prefix)
                SliderSetting(
                    R.string.typing_octopus_size_title,
                    subtitle = stringResource(R.string.typing_octopus_size_subtitle),
                    value = settings.watch { it.octopus.fontScale },
                    range = OctopusSettings.FONT_SCALE_RANGE,
                    display = { valueFormat.format("%.1f".format(it)) },
                    info = stringResource(R.string.typing_octopus_size_info),
                    default = SettingsDefaults.octopus.fontScale,
                ) { scope.launch { repository.setOctopusFontScale(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_octopus_hints_title,
                    stringResource(R.string.typing_octopus_hints_subtitle),
                    settings.watch { it.octopus.suppressHints },
                    info = stringResource(R.string.typing_octopus_hints_info),
                    default = SettingsDefaults.octopus.suppressHints,
                ) { scope.launch { repository.setOctopusSuppressHints(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_octopus_long_press_title,
                    stringResource(R.string.typing_octopus_long_press_subtitle),
                    settings.watch { it.octopus.longPressKeys },
                    info = stringResource(R.string.typing_octopus_long_press_info),
                    default = SettingsDefaults.octopus.longPressKeys,
                ) { scope.launch { repository.setOctopusLongPressKeys(it) } }
            }
            item {
                StepperSetting(
                    R.string.typing_octopus_stack_title,
                    subtitle = stringResource(R.string.typing_octopus_stack_subtitle),
                    value = settings.watch { it.octopus.wordsPerKey },
                    range = OctopusSettings.WORDS_PER_KEY_RANGE.toList(),
                    display = { context.getString(R.string.values_number, it) },
                    info = stringResource(R.string.typing_octopus_stack_info),
                    default = SettingsDefaults.octopus.wordsPerKey,
                ) { scope.launch { repository.setOctopusWordsPerKey(it) } }
            }
        }
    }
}

@Composable
internal fun TypingSmartChipsSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    // What decides which rows the group holds — each switch, and the banner
    // under it when its tools are off; each row reads its own value.
    val smartOn = settings.watch { it.smartSuggestions }
    val calcOn = settings.watch { it.smartCalc }
    val currencyOn = settings.watch { it.smartCurrency }
    val unitsOn = settings.watch { it.smartUnits }
    val datesOn = settings.watch { it.smartChips.dates }
    val weatherOn = settings.watch { it.smartChips.weather }
    val lookupsOn = settings.watch { it.smartChips.lookups }
    val intentsOn = settings.watch { it.smartChips.intents }
    val gifsOn = settings.watch { it.smartChips.gifs }
    val numbersOn = settings.watch { it.smartChips.numbers }
    // The keyboard gates on the usable set, so the banners ask the same one.
    val usable = settings.watch { usableTools(it) }
    SettingsGroup(stringResource(R.string.typing_group_smart_chips_title)) {
        item {
            ToggleSetting(
                R.string.typing_smart_chips_title,
                stringResource(R.string.typing_smart_chips_subtitle),
                smartOn,
                info = stringResource(R.string.typing_smart_chips_info),
                default = SettingsDefaults.smartSuggestions,
            ) { scope.launch { repository.setSmartSuggestions(it) } }
        }
        if (smartOn) {
            item {
                ToggleSetting(
                    R.string.typing_smart_calc_title,
                    stringResource(R.string.typing_smart_calc_subtitle),
                    calcOn,
                    default = SettingsDefaults.smartCalc,
                ) { scope.launch { repository.setSmartCalc(it) } }
            }
            chipToolsOffItem(SmartSuggest.Family.CALC, calcOn, usable, repository)
            item {
                ToggleSetting(
                    R.string.typing_smart_currency_title,
                    stringResource(R.string.typing_smart_currency_subtitle, settings.watch { it.currencyTo }),
                    currencyOn,
                    default = SettingsDefaults.smartCurrency,
                ) { scope.launch { repository.setSmartCurrency(it) } }
            }
            chipToolsOffItem(SmartSuggest.Family.CURRENCY, currencyOn, usable, repository)
            item {
                ToggleSetting(
                    R.string.typing_smart_units_title,
                    stringResource(R.string.typing_smart_units_subtitle),
                    unitsOn,
                    default = SettingsDefaults.smartUnits,
                ) { scope.launch { repository.setSmartUnits(it) } }
            }
            chipToolsOffItem(SmartSuggest.Family.UNITS, unitsOn, usable, repository)
            item {
                ToggleSetting(
                    R.string.typing_smart_tool_keywords_title,
                    stringResource(R.string.typing_smart_tool_keywords_subtitle),
                    settings.watch { it.smartToolKeywords },
                    info = stringResource(R.string.typing_smart_tool_keywords_info),
                    default = SettingsDefaults.smartToolKeywords,
                ) { scope.launch { repository.setSmartToolKeywords(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_dates_title,
                    stringResource(R.string.typing_smart_dates_subtitle),
                    datesOn,
                    default = SettingsDefaults.smartChips.dates,
                ) { scope.launch { repository.setSmartChipDates(it) } }
            }
            chipToolsOffItem(SmartSuggest.Family.DATES, datesOn, usable, repository)
            item {
                ToggleSetting(
                    R.string.typing_smart_weather_title,
                    stringResource(R.string.typing_smart_weather_subtitle),
                    weatherOn,
                    default = SettingsDefaults.smartChips.weather,
                ) { scope.launch { repository.setSmartChipWeather(it) } }
            }
            chipToolsOffItem(SmartSuggest.Family.WEATHER, weatherOn, usable, repository)
            item {
                ToggleSetting(
                    R.string.typing_smart_lookups_title,
                    stringResource(R.string.typing_smart_lookups_subtitle),
                    lookupsOn,
                    default = SettingsDefaults.smartChips.lookups,
                ) { scope.launch { repository.setSmartChipLookups(it) } }
            }
            chipToolsOffItem(SmartSuggest.Family.LOOKUPS, lookupsOn, usable, repository)
            item {
                ToggleSetting(
                    R.string.typing_smart_intents_title,
                    stringResource(R.string.typing_smart_intents_subtitle),
                    intentsOn,
                    default = SettingsDefaults.smartChips.intents,
                ) { scope.launch { repository.setSmartChipIntents(it) } }
            }
            chipToolsOffItem(SmartSuggest.Family.TRANSLATE, intentsOn, usable, repository)
            item {
                ToggleSetting(
                    R.string.typing_smart_gifs_title,
                    stringResource(R.string.typing_smart_gifs_subtitle),
                    gifsOn,
                    default = SettingsDefaults.smartChips.gifs,
                ) { scope.launch { repository.setSmartChipGifs(it) } }
            }
            // Celebrations are read inside the translate hints (see chips.mdx),
            // so with those off the GIF switch has no chips to warn about.
            chipToolsOffItem(
                SmartSuggest.Family.GIFS,
                gifsOn && intentsOn,
                usable,
                repository,
            )
            item {
                ToggleSetting(
                    R.string.typing_smart_numbers_title,
                    stringResource(R.string.typing_smart_numbers_subtitle),
                    numbersOn,
                    info = stringResource(R.string.typing_smart_numbers_info),
                    default = SettingsDefaults.smartChips.numbers,
                ) { scope.launch { repository.setSmartChipNumbers(it) } }
            }
            chipToolsOffItem(SmartSuggest.Family.NUMBERS, numbersOn, usable, repository)
            item(visible = numbersOn) {
                ChoiceSetting(
                    R.string.typing_smart_number_grouping_title,
                    subtitle = stringResource(
                        R.string.typing_smart_number_grouping_subtitle,
                    ),
                    options = listOf(
                        NumberGrouping.AUTO to
                            stringResource(R.string.typing_smart_number_grouping_auto),
                        NumberGrouping.WESTERN to
                            stringResource(R.string.typing_smart_number_grouping_western),
                        NumberGrouping.SOUTH_ASIAN to
                            stringResource(
                                R.string.typing_smart_number_grouping_south_asian,
                            ),
                    ),
                    selected = settings.watch { it.smartChips.numberGrouping },
                    default = SettingsDefaults.smartChips.numberGrouping,
                    detail = { style ->
                        ChoiceDetail(stringResource(numberGroupingDescRes(style)))
                    },
                ) { scope.launch { repository.setSmartChipNumberGrouping(it) } }
            }
        }
    }
}

/**
 * Under a chip switch that is on: a banner when the tools its chips hand off
 * to are switched off, naming them, with the one press that turns them on
 * (#176). Without it the switch reads on while its chips never show, or show
 * a gear that is not there, and nothing on either screen says why.
 */
private fun SettingsGroupScope.chipToolsOffItem(
    family: SmartSuggest.Family,
    switchOn: Boolean,
    usable: Set<ToolbarTool>,
    repository: SettingsRepository,
) {
    if (!switchOn) return
    // The keyboard gates on the usable set, so the banner asks the same one.
    // A tool this build does not ship is no use to offer.
    val missing = SmartSuggest.missingTools(family, usable, canTurnOn = ::isSupportedTool)
    if (missing.isEmpty()) return
    item {
        val scope = rememberCoroutineScope()
        val names = missing.map { stringResource(toolTitle(it)) }
        StateBanner(
            text = when {
                names.size > 1 -> stringResource(R.string.typing_smart_tools_off_hidden, names[0], names[1])
                SmartSuggest.answersWithoutTool(family) -> stringResource(R.string.typing_smart_tool_off_gear, names[0])
                else -> stringResource(R.string.typing_smart_tool_off_hidden, names[0])
            },
            action = stringResource(CommonR.string.common_enable),
        ) { scope.launch { missing.forEach { repository.setToolEnabled(it, true) } } }
    }
}

@Composable
internal fun TypingCodesSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val notificationCodesGranted = rememberGrantState(::hasNotificationAccess)
    val minutesFormat = stringResource(R.string.values_minutes)
    // Decides which rows the group holds; the rows read everything else.
    val otpOn = settings.watch { it.otp.enabled }
    SettingsGroup(stringResource(R.string.typing_group_otp_title)) {
        item {
            val accessContext = LocalContext.current
            val codesAccess = rememberDisclosedSpecialAccess(SpecialAccess.NOTIFICATION_CODES)
            ToggleSetting(
                R.string.typing_otp_chip_title,
                stringResource(R.string.typing_otp_chip_subtitle),
                otpOn,
                info = stringResource(R.string.typing_otp_chip_info),
                default = SettingsDefaults.otp.enabled,
            ) { on ->
                scope.launch { repository.setOtpChipEnabled(on) }
                // Disclosure then the grant screen, the first time it goes on —
                // but not when access is already there, which is the common
                // case for a toggle flipped off and on again.
                if (on && !hasNotificationAccess(accessContext)) codesAccess()
            }
        }
        if (otpOn) {
            item(visible = !notificationCodesGranted) {
                val codesAccessRow =
                    rememberDisclosedSpecialAccess(SpecialAccess.NOTIFICATION_CODES)
                NavRow(
                    R.string.typing_otp_access_title,
                    stringResource(R.string.typing_otp_access_subtitle),
                ) { codesAccessRow() }
            }
            item {
                ToggleSetting(
                    R.string.typing_otp_code_fields_title,
                    stringResource(R.string.typing_otp_code_fields_subtitle),
                    settings.watch { it.otp.codeFieldsOnly },
                    info = stringResource(R.string.typing_otp_code_fields_info),
                    default = SettingsDefaults.otp.codeFieldsOnly,
                ) { scope.launch { repository.setOtpCodeFieldsOnly(it) } }
            }
            item {
                SliderSetting(
                    R.string.typing_otp_expiry_title,
                    subtitle = stringResource(R.string.typing_otp_expiry_subtitle),
                    value = settings.watch { it.otp.expiryMinutes }.toFloat(),
                    range = 1f..10f,
                    display = { minutesFormat.format(it.toInt()) },
                    default = SettingsDefaults.otp.expiryMinutes.toFloat(),
                ) { scope.launch { repository.setOtpExpiryMinutes(it.toInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_otp_dismiss_title,
                    stringResource(R.string.typing_otp_dismiss_subtitle),
                    settings.watch { it.otp.dismissNotification },
                    info = stringResource(R.string.typing_otp_dismiss_info),
                    default = SettingsDefaults.otp.dismissNotification,
                ) { scope.launch { repository.setOtpDismissNotification(it) } }
            }
        }
        // Outside the enabled block on purpose: this governs how *every* code
        // is typed, including one pasted from the clipboard, which needs no
        // notification access at all.
        item {
            ToggleSetting(
                R.string.typing_otp_per_digit_title,
                stringResource(R.string.typing_otp_per_digit_subtitle),
                settings.watch { it.otp.perDigitEntry },
                info = stringResource(R.string.typing_otp_per_digit_info),
                default = SettingsDefaults.otp.perDigitEntry,
            ) { scope.launch { repository.setOtpPerDigitEntry(it) } }
        }
    }
}

/**
 * "Forget" for the learned swipe style (issue #52): a confirm, then the
 * files go and a running keyboard is told to drop its copies. Its own row
 * rather than a corner of "Delete learned words", which takes the personal
 * dictionary with it.
 */
/**
 * The tap model's "forget", the twin of [ForgetSwipeStyleRow] for taps: a
 * confirmed reset that deletes the learned tap positions and tells a running
 * keyboard through its own version signal.
 */
@Composable
private fun ForgetTapModelRow(repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    TextButton(
        onClick = { confirm = true },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    ) { Text(stringResource(R.string.typing_adapt_taps_forget)) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.typing_adapt_taps_forget_title)) },
            text = { Text(stringResource(R.string.typing_adapt_taps_forget_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { repository.forgetTapModel() }
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

@Composable
private fun ForgetSwipeStyleRow(repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    TextButton(
        onClick = { confirm = true },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    ) { Text(stringResource(R.string.typing_glide_swipe_style_forget)) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.typing_glide_swipe_style_forget_title)) },
            text = { Text(stringResource(R.string.typing_glide_swipe_style_forget_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { repository.forgetSwipeStyle() }
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

@Composable
internal fun TypingGesturesSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // What decides which rows the groups hold; each row reads its own value.
    val glideOn = settings.watch { it.gestureTyping }
    val swipeAction = settings.watch { it.letterSwipeAction }
    val shiftCapitals = settings.watch { it.gesture.shiftGlideCapitals }
    val pickerOn = settings.watch { it.gesture.ambiguityPicker }
    val pickerAsksAtEveryPause =
        settings.watch { it.gesture.pickerSensitivity == GlidePickerSensitivity.EVERY_PAUSE }
    val learnSwipeStyle = settings.watch { it.gesture.learnSwipeStyle }
    val loopDouble = settings.watch { it.gesture.loopDouble }
    val wiggleDouble = settings.watch { it.gesture.wiggleDouble }
    val wordPreview = settings.watch { it.gesture.wordPreview }
    val spaceCursor = settings.watch {
        it.spaceShortSwipe == SpaceSwipeAction.CURSOR || it.spaceLongSwipe == SpaceSwipeAction.CURSOR
    }
    val spaceLanguage = settings.watch {
        it.spaceShortSwipe == SpaceSwipeAction.LANGUAGE || it.spaceLongSwipe == SpaceSwipeAction.LANGUAGE
    }
    SettingsGroup(stringResource(R.string.typing_group_glide_title)) {
        item {
            ToggleSetting(
                R.string.typing_glide_typing_title,
                stringResource(R.string.typing_glide_typing_subtitle),
                glideOn,
                info = stringResource(R.string.typing_glide_typing_info),
                default = SettingsDefaults.gestureTyping,
            ) { scope.launch { repository.setGestureTyping(it) } }
        }
        // What a letter swipe does — glide a word or handwrite it. Full builds
        // only (needs the ML Kit handwriting model), and only relevant once
        // letter swipes are switched on above.
        item(visible = BuildConfig.ENABLE_ML_KIT_HANDWRITING && glideOn) {
            ChoiceSetting(
                title = R.string.typing_letter_swipe_action_title,
                subtitle = stringResource(R.string.typing_letter_swipe_action_subtitle),
                info = stringResource(R.string.typing_letter_swipe_action_info),
                options = listOf(
                    LetterSwipeAction.TYPE_WORDS to
                        stringResource(R.string.typing_letter_swipe_type_words_label),
                    LetterSwipeAction.HANDWRITE to
                        stringResource(R.string.typing_letter_swipe_handwrite_label),
                ),
                selected = swipeAction,
                onChange = { scope.launch { repository.setLetterSwipeAction(it) } },
                default = SettingsDefaults.letterSwipeAction,
                detail = { action ->
                    ChoiceDetail(
                        stringResource(
                            if (action == LetterSwipeAction.HANDWRITE) {
                                R.string.typing_letter_swipe_handwrite_desc
                            } else {
                                R.string.typing_letter_swipe_type_words_desc
                            },
                        ),
                    )
                },
            )
        }
        if (glideOn) {
            // Glide-word only: crossing the spacebar to chain words has no
            // meaning when a swipe draws handwriting instead.
            if (swipeAction == LetterSwipeAction.TYPE_WORDS) {
                item {
                    ToggleSetting(
                        R.string.typing_space_glide_multiword_title,
                        stringResource(R.string.typing_space_glide_multiword_subtitle),
                        settings.watch { it.gesture.spaceGlideMultiWord },
                        info = stringResource(R.string.typing_space_glide_multiword_info),
                        default = SettingsDefaults.gesture.spaceGlideMultiWord,
                    ) { scope.launch { repository.setGestureSpaceMultiWord(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.typing_shift_glide_capitals_title,
                        stringResource(R.string.typing_shift_glide_capitals_subtitle),
                        shiftCapitals,
                        info = stringResource(R.string.typing_shift_glide_capitals_info),
                        default = SettingsDefaults.gesture.shiftGlideCapitals,
                    ) { scope.launch { repository.setGestureShiftCapitals(it) } }
                }
                // How a crossing reads, only while crossings mean anything.
                item(visible = shiftCapitals) {
                    ChoiceSetting(
                        title = R.string.typing_shift_glide_mode_title,
                        subtitle = stringResource(R.string.typing_shift_glide_mode_subtitle),
                        info = stringResource(R.string.typing_shift_glide_mode_info),
                        options = listOf(
                            ShiftGlideMode.WORD to
                                stringResource(R.string.typing_shift_glide_mode_word_label),
                            ShiftGlideMode.LETTER to
                                stringResource(R.string.typing_shift_glide_mode_letter_label),
                        ),
                        selected = settings.watch { it.gesture.shiftGlideMode },
                        onChange = { scope.launch { repository.setGestureShiftGlideMode(it) } },
                        default = SettingsDefaults.gesture.shiftGlideMode,
                    )
                }
                item {
                    ToggleSetting(
                        R.string.typing_glide_picker_title,
                        stringResource(R.string.typing_glide_picker_subtitle),
                        pickerOn,
                        info = stringResource(R.string.typing_glide_picker_info),
                        default = SettingsDefaults.gesture.ambiguityPicker,
                    ) { scope.launch { repository.setGestureAmbiguityPicker(it) } }
                }
                // The picker's own knobs, only while it is on: how sure the
                // keyboard has to be, how long to hold, whether a sure stroke
                // can still be second-guessed, and how many words to offer.
                if (pickerOn) {
                    item {
                        ChoiceSetting(
                            title = R.string.typing_glide_picker_sensitivity_title,
                            subtitle = stringResource(R.string.typing_glide_picker_sensitivity_subtitle),
                            info = stringResource(R.string.typing_glide_picker_sensitivity_info),
                            options = GlidePickerSensitivity.entries.map { it to stringResource(it.labelRes) },
                            selected = settings.watch { it.gesture.pickerSensitivity },
                            onChange = { scope.launch { repository.setGesturePickerSensitivity(it) } },
                            default = SettingsDefaults.gesture.pickerSensitivity,
                            detail = { tier -> ChoiceDetail(stringResource(glidePickerSensitivityDescRes(tier))) },
                        )
                    }
                    item {
                        val msFormat = stringResource(R.string.typing_value_milliseconds)
                        SliderSetting(
                            R.string.typing_glide_picker_dwell_title,
                            subtitle = stringResource(R.string.typing_glide_picker_dwell_subtitle),
                            value = settings.watch { it.gesture.pickerDwellMs }.toFloat(),
                            range = GlidePickerDwellMsRange.first.toFloat()..GlidePickerDwellMsRange.last.toFloat(),
                            display = { msFormat.format(it.roundToInt()) },
                            info = stringResource(R.string.typing_glide_picker_dwell_info),
                            default = SettingsDefaults.gesture.pickerDwellMs.toFloat(),
                        ) { scope.launch { repository.setGesturePickerDwellMs(it.roundToInt()) } }
                    }
                    // Redundant under "Every pause", which already asks at
                    // the first hold.
                    item(visible = !pickerAsksAtEveryPause) {
                        ToggleSetting(
                            R.string.typing_glide_picker_hold_title,
                            stringResource(R.string.typing_glide_picker_hold_subtitle),
                            settings.watch { it.gesture.pickerHoldToAsk },
                            info = stringResource(R.string.typing_glide_picker_hold_info),
                            default = SettingsDefaults.gesture.pickerHoldToAsk,
                        ) { scope.launch { repository.setGesturePickerHoldToAsk(it) } }
                    }
                    item {
                        val numberFormat = stringResource(R.string.values_number)
                        SliderSetting(
                            R.string.typing_glide_picker_choices_title,
                            subtitle = stringResource(R.string.typing_glide_picker_choices_subtitle),
                            value = settings.watch { it.gesture.pickerChoices }.toFloat(),
                            range = GlidePickerChoicesRange.first.toFloat()..GlidePickerChoicesRange.last.toFloat(),
                            display = { numberFormat.format(it.roundToInt()) },
                            info = stringResource(R.string.typing_glide_picker_choices_info),
                            default = SettingsDefaults.gesture.pickerChoices.toFloat(),
                        ) { scope.launch { repository.setGesturePickerChoices(it.roundToInt()) } }
                    }
                }
                item {
                    ToggleSetting(
                        R.string.typing_glide_swipe_style_title,
                        stringResource(R.string.typing_glide_swipe_style_subtitle),
                        learnSwipeStyle,
                        info = stringResource(R.string.typing_glide_swipe_style_info),
                        default = SettingsDefaults.gesture.learnSwipeStyle,
                    ) { scope.launch { repository.setGestureLearnSwipeStyle(it) } }
                }
                item { ForgetSwipeStyleRow(repository) }
                // How much of the dictionary a swipe may answer with. The
                // shipped lists are smaller than every limit, so this does
                // nothing until a large list is downloaded or imported (#28).
                item {
                    ChoiceSetting(
                        title = R.string.typing_glide_vocabulary_title,
                        subtitle = stringResource(R.string.typing_glide_vocabulary_subtitle),
                        info = stringResource(R.string.typing_glide_vocabulary_info),
                        options = GlideVocabulary.entries.map { option ->
                            val label = stringResource(option.labelRes)
                            option to if (option.rank == 0) {
                                label
                            } else {
                                pluralStringResource(
                                    R.plurals.languages_wordlist_size_option,
                                    option.rank,
                                    label,
                                    option.rank,
                                )
                            }
                        },
                        selected = settings.watch { it.gesture.vocabulary },
                        onChange = { scope.launch { repository.setGestureVocabulary(it) } },
                        default = SettingsDefaults.gesture.vocabulary,
                    )
                }
                // The three questions about *what a swipe may answer with*,
                // kept together and out of line: this screen's body is already
                // past its length budget and these belong to one another.
                glideVocabularyRows(settings, repository, scope)
                item {
                    ToggleSetting(
                        R.string.typing_space_after_glide_title,
                        stringResource(R.string.typing_space_after_glide_subtitle),
                        settings.watch { it.gesture.autoSpaceAfterGlide },
                        info = stringResource(R.string.typing_space_after_glide_info),
                        default = SettingsDefaults.gesture.autoSpaceAfterGlide,
                    ) { scope.launch { repository.setGestureAutoSpace(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.typing_glide_backspace_undo_title,
                        stringResource(R.string.typing_glide_backspace_undo_subtitle),
                        settings.watch { it.gesture.backspaceUndoesGlide },
                        info = stringResource(R.string.typing_glide_backspace_undo_info),
                        default = SettingsDefaults.gesture.backspaceUndoesGlide,
                    ) { scope.launch { repository.setGestureBackspaceUndoesGlide(it) } }
                }
                // Which key a glide reads as an apostrophe, so "it's" can be
                // drawn rather than guessed at. One key, never several.
                item {
                    ChoiceSetting(
                        title = R.string.typing_glide_apostrophe_title,
                        subtitle = stringResource(R.string.typing_glide_apostrophe_subtitle),
                        info = stringResource(R.string.typing_glide_apostrophe_info),
                        options = listOf(
                            GlideApostropheKey.OFF to
                                stringResource(R.string.typing_glide_apostrophe_off_label),
                            GlideApostropheKey.COMMA to
                                stringResource(R.string.typing_glide_apostrophe_comma_label),
                            GlideApostropheKey.PERIOD to
                                stringResource(R.string.typing_glide_apostrophe_period_label),
                            GlideApostropheKey.SPACE to
                                stringResource(R.string.typing_glide_apostrophe_space_label),
                            GlideApostropheKey.APOSTROPHE to
                                stringResource(R.string.typing_glide_apostrophe_key_label),
                        ),
                        selected = settings.watch { it.gesture.apostropheKey },
                        onChange = { scope.launch { repository.setGestureApostropheKey(it) } },
                        default = SettingsDefaults.gesture.apostropheKey,
                        detail = { key -> ChoiceDetail(stringResource(glideApostropheDescRes(key))) },
                    )
                }
            }
            item {
                val valueFormat = stringResource(R.string.typing_value_multiplier_suffix)
                SliderSetting(
                    R.string.typing_swipe_start_distance_title,
                    subtitle = stringResource(R.string.typing_swipe_start_distance_subtitle),
                    value = settings.watch { it.gesture.startThresholdSlop },
                    range = 0.5f..4f,
                    display = { valueFormat.format("%.1f".format(it)) },
                    info = stringResource(R.string.typing_swipe_start_distance_info),
                    default = SettingsDefaults.gesture.startThresholdSlop,
                ) { scope.launch { repository.setGestureStartThresholdSlop(it) } }
            }
            // Glide-word only: the guard raises the swipe-start bar, which never
            // runs in handwrite mode (there is no word glide to suppress).
            item(visible = swipeAction == LetterSwipeAction.TYPE_WORDS) {
                val offLabel = stringResource(CommonR.string.common_off)
                val msFormat = stringResource(R.string.typing_value_milliseconds)
                SliderSetting(
                    R.string.typing_gesture_cooldown_title,
                    subtitle = stringResource(R.string.typing_gesture_cooldown_subtitle),
                    value = settings.watch { it.gesture.postTypeCooldownMs }.toFloat(),
                    range = 0f..500f,
                    display = { if (it.roundToInt() == 0) offLabel else msFormat.format(it.roundToInt()) },
                    info = stringResource(R.string.typing_gesture_cooldown_info),
                    default = SettingsDefaults.gesture.postTypeCooldownMs.toFloat(),
                ) { scope.launch { repository.setGesturePostTypeCooldownMs(it.roundToInt()) } }
            }
            // Handwrite-with-swipes only: window after a drawn stroke in which a
            // tap is grabbed as an ink dot rather than typing.
            if (BuildConfig.ENABLE_ML_KIT_HANDWRITING &&
                swipeAction == LetterSwipeAction.HANDWRITE
            ) {
                item {
                    val offLabel = stringResource(CommonR.string.common_off)
                    val msFormat = stringResource(R.string.typing_value_milliseconds)
                    SliderSetting(
                        R.string.typing_handwrite_dot_title,
                        subtitle = stringResource(R.string.typing_handwrite_dot_subtitle),
                        value = settings.watch { it.gesture.handwriteDotCooldownMs }.toFloat(),
                        range = 0f..1500f,
                        display = { if (it.roundToInt() == 0) offLabel else msFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.typing_handwrite_dot_info),
                        default = SettingsDefaults.gesture.handwriteDotCooldownMs.toFloat(),
                    ) { scope.launch { repository.setGestureHandwriteDotCooldownMs(it.roundToInt()) } }
                }
            }
        }
    }
    // How the decoder reads a stroke: the three tolerances (#222) and the
    // three marks for a doubled letter (#270). A fold, because they are for
    // the people who want to tune and measure, and a screen that opens on
    // eleven sliders buries the switches everyone else came for.
    SettingsGroup(
        stringResource(R.string.typing_group_glide_tuning_title),
        foldKey = "typing/glide_tuning",
        info = stringResource(R.string.typing_group_glide_tuning_info),
        foldSummary = { stringResource(R.string.typing_group_glide_tuning_summary) },
    ) {
        if (glideOn && swipeAction == LetterSwipeAction.TYPE_WORDS) {
            glideRadiusRows(settings, repository, scope)
            glideIntentRows(settings, loopDouble, wiggleDouble, repository, scope)
            // How many ways of drawing one word the swipe style keeps apart
            // (#326). Here rather than beside the switch because it is a number
            // to measure with, not a choice most people need to make.
            item(visible = learnSwipeStyle) {
                val numberFormat = stringResource(R.string.values_number)
                SliderSetting(
                    R.string.typing_glide_shapes_per_word_title,
                    subtitle = stringResource(R.string.typing_glide_shapes_per_word_subtitle),
                    value = settings.watch { it.gesture.shapesPerWord }.toFloat(),
                    range = GlideShapesPerWordRange.first.toFloat()..GlideShapesPerWordRange.last.toFloat(),
                    display = { numberFormat.format(it.roundToInt()) },
                    info = stringResource(R.string.typing_glide_shapes_per_word_info),
                    default = SettingsDefaults.gesture.shapesPerWord.toFloat(),
                ) { scope.launch { repository.setGestureShapesPerWord(it.roundToInt()) } }
            }
            item(visible = learnSwipeStyle) {
                ToggleSetting(
                    title = stringResource(R.string.typing_glide_shape_seeding_title),
                    subtitle = stringResource(R.string.typing_glide_shape_seeding_subtitle),
                    checked = settings.watch { it.gesture.shapeSeeding },
                    info = stringResource(R.string.typing_glide_shape_seeding_info),
                    default = SettingsDefaults.gesture.shapeSeeding,
                ) { scope.launch { repository.setGestureShapeSeeding(it) } }
            }
        }
    }
    SettingsGroup(stringResource(R.string.typing_group_glide_trail_title)) {
        if (glideOn) {
            item {
                val dpFormat = stringResource(R.string.typing_value_dp)
                // Width and opacity are among the sizes a theme's "Layout for
                // this theme" group can pin; the row says so when one does (#88).
                val pinned = themePinSubtitle(settings) { it.gestureTrailWidthDp }
                SliderSetting(
                    R.string.typing_trail_width_title,
                    subtitle = pinned ?: stringResource(R.string.typing_trail_width_subtitle),
                    value = settings.watch { it.gesture.trailWidthDp },
                    range = 2f..24f,
                    display = { dpFormat.format(it.roundToInt()) },
                    enabled = pinned == null,
                    default = SettingsDefaults.gesture.trailWidthDp,
                ) { scope.launch { repository.setGestureTrailWidthDp(it) } }
            }
            item {
                val msFormat = stringResource(R.string.typing_value_milliseconds)
                SliderSetting(
                    R.string.typing_trail_length_title,
                    subtitle = stringResource(R.string.typing_trail_length_subtitle),
                    value = settings.watch { it.gesture.trailDurationMs }.toFloat(),
                    range = 100f..1200f,
                    display = { msFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.gesture.trailDurationMs.toFloat(),
                ) { scope.launch { repository.setGestureTrailDurationMs(it.roundToInt()) } }
            }
            item {
                val percentFormat = stringResource(R.string.typing_value_percent)
                val pinned = themePinSubtitle(settings) { it.gestureTrailOpacity }
                SliderSetting(
                    R.string.typing_trail_opacity_title,
                    subtitle = pinned,
                    value = settings.watch { it.gesture.trailOpacity },
                    // Down to zero, which is the only way to glide with no
                    // trail at all. It used to floor at 0.1, so the one way to
                    // turn the trail off was power saving mode, which changes
                    // a dozen other things with it.
                    range = 0f..1f,
                    display = { percentFormat.format((it * 100).roundToInt()) },
                    enabled = pinned == null,
                    default = SettingsDefaults.gesture.trailOpacity,
                ) { scope.launch { repository.setGestureTrailOpacity(it) } }
            }
            // The pill that rides above the finger with the word the stroke has
            // decoded to so far. Glide-word only: handwriting draws ink and
            // recognizes on lift, so there is no running word to float.
            if (swipeAction == LetterSwipeAction.TYPE_WORDS) {
                item {
                    ToggleSetting(
                        R.string.typing_glide_preview_title,
                        stringResource(R.string.typing_glide_preview_subtitle),
                        wordPreview,
                        info = stringResource(R.string.typing_glide_preview_info),
                        default = SettingsDefaults.gesture.wordPreview,
                    ) { scope.launch { repository.setGestureWordPreview(it) } }
                }
                item(visible = wordPreview) {
                    val dpFormat = stringResource(R.string.typing_value_dp)
                    SliderSetting(
                        R.string.typing_glide_preview_height_title,
                        subtitle = stringResource(R.string.typing_glide_preview_height_subtitle),
                        value = settings.watch { it.gesture.wordPreviewOffsetYDp }.toFloat(),
                        range = 0f..160f,
                        display = { dpFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.typing_glide_preview_height_info),
                        default = SettingsDefaults.gesture.wordPreviewOffsetYDp.toFloat(),
                    ) { scope.launch { repository.setGestureWordPreviewOffsetYDp(it.roundToInt()) } }
                }
                item(visible = wordPreview) {
                    val dpFormat = stringResource(R.string.typing_value_dp)
                    SliderSetting(
                        R.string.typing_glide_preview_shift_title,
                        subtitle = stringResource(R.string.typing_glide_preview_shift_subtitle),
                        value = settings.watch { it.gesture.wordPreviewOffsetXDp }.toFloat(),
                        range = -80f..80f,
                        display = { dpFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.typing_glide_preview_shift_info),
                        default = SettingsDefaults.gesture.wordPreviewOffsetXDp.toFloat(),
                    ) { scope.launch { repository.setGestureWordPreviewOffsetXDp(it.roundToInt()) } }
                }
                item(visible = wordPreview) {
                    val spFormat = stringResource(R.string.values_sp)
                    SliderSetting(
                        R.string.typing_glide_preview_size_title,
                        subtitle = stringResource(R.string.typing_glide_preview_size_subtitle),
                        value = settings.watch { it.gesture.wordPreviewFontSp }.toFloat(),
                        range = 12f..32f,
                        display = { spFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.typing_glide_preview_size_info),
                        default = SettingsDefaults.gesture.wordPreviewFontSp.toFloat(),
                    ) { scope.launch { repository.setGestureWordPreviewFontSp(it.roundToInt()) } }
                }
                item(visible = wordPreview) {
                    ColorSetting(
                        R.string.typing_glide_preview_color_title,
                        subtitle = stringResource(R.string.typing_glide_preview_color_subtitle),
                        color = settings.watch { it.gesture.wordPreviewBackground },
                        fallback = MaterialTheme.colorScheme.surfaceVariant.argbLong(),
                        info = stringResource(R.string.typing_glide_preview_color_info),
                    ) { scope.launch { repository.setGestureWordPreviewBackground(it) } }
                }
                item(visible = wordPreview) {
                    ColorSetting(
                        R.string.typing_glide_preview_text_color_title,
                        subtitle = stringResource(R.string.typing_glide_preview_text_color_subtitle),
                        color = settings.watch { it.gesture.wordPreviewTextColor },
                        fallback = MaterialTheme.colorScheme.onSurfaceVariant.argbLong(),
                        info = stringResource(R.string.typing_glide_preview_text_color_info),
                    ) { scope.launch { repository.setGestureWordPreviewTextColor(it) } }
                }
                // Issue #84. Outside the pill's own block on purpose: this is
                // about the suggestion strip, so it stands whether the pill is
                // on or off.
                item {
                    ToggleSetting(
                        R.string.typing_glide_strip_preview_title,
                        stringResource(R.string.typing_glide_strip_preview_subtitle),
                        settings.watch { it.gesture.stripPreviewOnly },
                        info = stringResource(R.string.typing_glide_strip_preview_info),
                        default = SettingsDefaults.gesture.stripPreviewOnly,
                    ) { scope.launch { repository.setGestureStripPreviewOnly(it) } }
                }
            }
        }
    }
    SettingsGroup(stringResource(R.string.typing_group_spacebar_title)) {
        item {
            SpaceSwipeSetting(
                title = R.string.typing_space_short_swipe_title,
                subtitle = stringResource(R.string.typing_space_short_swipe_subtitle),
                info = stringResource(R.string.typing_space_short_swipe_info),
                value = settings.watch { it.spaceShortSwipe },
                default = SettingsDefaults.spaceShortSwipe,
            ) { scope.launch { repository.setSpaceShortSwipe(it) } }
        }
        item {
            SpaceSwipeSetting(
                title = R.string.typing_space_long_swipe_title,
                subtitle = stringResource(R.string.typing_space_long_swipe_subtitle),
                info = stringResource(R.string.typing_space_long_swipe_info),
                value = settings.watch { it.spaceLongSwipe },
                default = SettingsDefaults.spaceLongSwipe,
            ) { scope.launch { repository.setSpaceLongSwipe(it) } }
        }
        // 2-D cursor pad only makes sense once a slide is set to cursor control.
        if (spaceCursor) {
            item {
                ToggleSetting(
                    R.string.typing_space_cursor_2d_title,
                    stringResource(R.string.typing_space_cursor_2d_subtitle),
                    settings.watch { it.layoutBehavior.spaceCursor2d },
                    info = stringResource(R.string.typing_space_cursor_2d_info),
                    default = SettingsDefaults.layoutBehavior.spaceCursor2d,
                ) { scope.launch { repository.setSpaceCursor2d(it) } }
            }
            item {
                SliderSetting(
                    R.string.typing_space_cursor_step_title,
                    subtitle = stringResource(R.string.typing_space_cursor_step_subtitle),
                    value = settings.watch { it.textEditing.spaceCursorStepDp }.toFloat(),
                    range = 8f..32f,
                    display = { context.getString(R.string.typing_value_dp, it.toInt()) },
                    info = stringResource(R.string.typing_space_cursor_step_info),
                    default = SettingsDefaults.textEditing.spaceCursorStepDp.toFloat(),
                ) { scope.launch { repository.setSpaceCursorStepDp(it.toInt()) } }
            }
            // Issue #385: the drag speeds up the further it goes.
            item {
                ToggleSetting(
                    R.string.typing_space_cursor_accelerate_title,
                    stringResource(R.string.typing_space_cursor_accelerate_subtitle),
                    settings.watch { it.textEditing.spaceCursorAccelerate },
                    info = stringResource(R.string.typing_space_cursor_accelerate_info),
                    default = SettingsDefaults.textEditing.spaceCursorAccelerate,
                ) { scope.launch { repository.setSpaceCursorAccelerate(it) } }
            }
            // Issue #505: a caret move that no search box mistakes for Tab.
            item {
                ToggleSetting(
                    R.string.typing_space_cursor_direct_title,
                    stringResource(R.string.typing_space_cursor_direct_subtitle),
                    settings.watch { it.textEditing.spaceCursorDirect },
                    info = stringResource(R.string.typing_space_cursor_direct_info),
                    default = SettingsDefaults.textEditing.spaceCursorDirect,
                ) { scope.launch { repository.setSpaceCursorDirect(it) } }
            }
            // Issue #505: the drag goes on past the end of the spacebar.
            item {
                ToggleSetting(
                    R.string.typing_space_cursor_edge_repeat_title,
                    stringResource(R.string.typing_space_cursor_edge_repeat_subtitle),
                    settings.watch { it.textEditing.spaceCursorEdgeRepeat },
                    info = stringResource(R.string.typing_space_cursor_edge_repeat_info),
                    default = SettingsDefaults.textEditing.spaceCursorEdgeRepeat,
                ) { scope.launch { repository.setSpaceCursorEdgeRepeat(it) } }
            }
            // Issue #505: the whole key area is the drag's touchpad.
            item {
                ToggleSetting(
                    R.string.typing_space_cursor_whole_keyboard_title,
                    stringResource(R.string.typing_space_cursor_whole_keyboard_subtitle),
                    settings.watch { it.textEditing.spaceCursorWholeKeyboard },
                    info = stringResource(R.string.typing_space_cursor_whole_keyboard_info),
                    default = SettingsDefaults.textEditing.spaceCursorWholeKeyboard,
                ) { scope.launch { repository.setSpaceCursorWholeKeyboard(it) } }
            }
            item {
                val valueFormat = stringResource(R.string.typing_value_multiplier_suffix)
                SliderSetting(
                    R.string.typing_space_cursor_top_speed_title,
                    subtitle = stringResource(R.string.typing_space_cursor_top_speed_subtitle),
                    value = settings.watch { it.textEditing.spaceCursorTopSpeed }.toFloat(),
                    range = 2f..8f,
                    display = { valueFormat.format(it.roundToInt().toString()) },
                    info = stringResource(R.string.typing_space_cursor_top_speed_info),
                    enabled = settings.watch { it.textEditing.spaceCursorAccelerate },
                    default = SettingsDefaults.textEditing.spaceCursorTopSpeed.toFloat(),
                ) { scope.launch { repository.setSpaceCursorTopSpeed(it.roundToInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_space_cursor_magnifier_title,
                    stringResource(R.string.typing_space_cursor_magnifier_subtitle),
                    settings.watch { it.textEditing.spaceCursorMagnifier },
                    info = stringResource(R.string.typing_space_cursor_magnifier_info),
                    default = SettingsDefaults.textEditing.spaceCursorMagnifier,
                ) { scope.launch { repository.setSpaceCursorMagnifier(it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_space_swipe_down_hide_title,
                stringResource(R.string.typing_space_swipe_down_hide_subtitle),
                settings.watch { it.layoutBehavior.spaceSwipeDownHide },
                info = stringResource(R.string.typing_space_swipe_down_hide_info),
                default = SettingsDefaults.layoutBehavior.spaceSwipeDownHide,
            ) { scope.launch { repository.setSpaceSwipeDownHide(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_edge_swipe_back_title,
                stringResource(R.string.typing_edge_swipe_back_subtitle),
                settings.watch { it.layoutBehavior.edgeSwipeBack },
                info = stringResource(R.string.typing_edge_swipe_back_info),
                default = SettingsDefaults.layoutBehavior.edgeSwipeBack,
            ) { scope.launch { repository.setEdgeSwipeBack(it) } }
        }
        item {
            // Issue #178: a quick flick down on a key types its corner hint.
            ToggleSetting(
                R.string.typing_hint_flick_title,
                stringResource(R.string.typing_hint_flick_subtitle),
                settings.watch { it.layoutBehavior.hintFlick },
                info = stringResource(R.string.typing_hint_flick_info),
                default = SettingsDefaults.layoutBehavior.hintFlick,
            ) { scope.launch { repository.setHintFlick(it) } }
        }
        item {
            // The hint flick's upward twin: a quick flick up types the capital.
            ToggleSetting(
                R.string.typing_capital_flick_title,
                stringResource(R.string.typing_capital_flick_subtitle),
                settings.watch { it.layoutBehavior.capitalFlick },
                info = stringResource(R.string.typing_capital_flick_info),
                default = SettingsDefaults.layoutBehavior.capitalFlick,
            ) { scope.launch { repository.setCapitalFlick(it) } }
        }
        item {
            // Issue #169: a short straight swipe from a punctuation key to s
            // appends 's to the last word. Its own key, its own row, outside
            // the glide block: it works on tapped words with glide typing off.
            ChoiceSetting(
                title = R.string.typing_possessive_swipe_title,
                subtitle = stringResource(R.string.typing_possessive_swipe_subtitle),
                info = stringResource(R.string.typing_possessive_swipe_info),
                options = listOf(
                    GlideApostropheKey.OFF to
                        stringResource(R.string.typing_glide_apostrophe_off_label),
                    GlideApostropheKey.COMMA to
                        stringResource(R.string.typing_glide_apostrophe_comma_label),
                    GlideApostropheKey.PERIOD to
                        stringResource(R.string.typing_glide_apostrophe_period_label),
                    GlideApostropheKey.APOSTROPHE to
                        stringResource(R.string.typing_glide_apostrophe_key_label),
                ),
                selected = settings.watch { it.gesture.possessiveKey },
                onChange = { scope.launch { repository.setGesturePossessiveKey(it) } },
                default = SettingsDefaults.gesture.possessiveKey,
                detail = { key -> ChoiceDetail(stringResource(possessiveSwipeDescRes(key))) },
            )
        }
        item {
            // Issue #57: the characters the spacebar's long press offers, space
            // separated. Blank gives the hold back to the language picker.
            TextFieldSetting(
                label = stringResource(R.string.typing_space_hold_keys_label),
                value = settings.watch { it.layoutBehavior.spaceHoldKeys }.joinToString(" "),
                hint = stringResource(R.string.typing_space_hold_keys_hint),
                default = SettingsDefaults.layoutBehavior.spaceHoldKeys.joinToString(" "),
            ) { text ->
                repository.setSpaceHoldKeys(text.split(" ").filter { it.isNotBlank() })
            }
        }
        if (spaceLanguage) {
            item {
                ToggleSetting(
                    R.string.typing_spacebar_language_arrows_title,
                    stringResource(R.string.typing_spacebar_language_arrows_subtitle),
                    settings.watch { it.spacebarLanguageArrows },
                    info = stringResource(R.string.typing_spacebar_language_arrows_info),
                    default = SettingsDefaults.spacebarLanguageArrows,
                ) { scope.launch { repository.setSpacebarLanguageArrows(it) } }
            }
            item {
                // Issue #376: how long a switch keeps its language up after the
                // lift. Steps of 50 ms, like the globe typing guard.
                val offLabel = stringResource(CommonR.string.common_off)
                val msFormat = stringResource(R.string.typing_value_milliseconds)
                SliderSetting(
                    R.string.typing_language_echo_title,
                    subtitle = stringResource(R.string.typing_language_echo_subtitle),
                    value = settings.watch { it.layoutBehavior.languageEchoMs }.toFloat(),
                    range = LanguageEchoMsRange.first.toFloat()..LanguageEchoMsRange.last.toFloat(),
                    display = {
                        val ms = (it / 50f).roundToInt() * 50
                        if (ms == 0) offLabel else msFormat.format(ms)
                    },
                    info = stringResource(R.string.typing_language_echo_info),
                    default = SettingsDefaults.layoutBehavior.languageEchoMs.toFloat(),
                ) { scope.launch { repository.setLanguageEchoMs((it / 50f).roundToInt() * 50) } }
            }
        }
        item {
            ChoiceSetting(
                R.string.typing_spacebar_display_title,
                subtitle = stringResource(R.string.typing_spacebar_display_subtitle),
                info = stringResource(R.string.typing_spacebar_display_info),
                options = listOf(
                    SpacebarDisplay.LANGUAGE to
                        stringResource(R.string.typing_spacebar_display_language_label),
                    SpacebarDisplay.LAYOUT to
                        stringResource(R.string.typing_spacebar_display_layout_label),
                    SpacebarDisplay.BOTH to
                        stringResource(R.string.typing_spacebar_display_both_label),
                ),
                selected = settings.watch { it.layoutBehavior.spacebarDisplay },
                default = SettingsDefaults.layoutBehavior.spacebarDisplay,
            ) { scope.launch { repository.setSpacebarDisplay(it) } }
        }
        item {
            // Issue #150: the picker a long ring opens, as a list or a carousel.
            ChoiceSetting(
                R.string.typing_language_picker_style_title,
                subtitle = stringResource(R.string.typing_language_picker_style_subtitle),
                info = stringResource(R.string.typing_language_picker_style_info),
                options = listOf(
                    LanguagePickerStyle.LIST to
                        stringResource(R.string.typing_language_picker_style_list_label),
                    LanguagePickerStyle.CAROUSEL to
                        stringResource(R.string.typing_language_picker_style_carousel_label),
                ),
                selected = settings.watch { it.layoutBehavior.languagePickerStyle },
                default = SettingsDefaults.layoutBehavior.languagePickerStyle,
            ) { scope.launch { repository.setLanguagePickerStyle(it) } }
        }
        item {
            // Off: the spacebar hold keeps the sideways preview at any ring
            // length instead of switching to the picker past four layouts.
            ToggleSetting(
                R.string.typing_space_hold_picker_long_ring_title,
                stringResource(R.string.typing_space_hold_picker_long_ring_subtitle),
                settings.watch { it.layoutBehavior.spaceHoldPickerForLongRing },
                info = stringResource(R.string.typing_space_hold_picker_long_ring_info),
                default = SettingsDefaults.layoutBehavior.spaceHoldPickerForLongRing,
            ) { scope.launch { repository.setSpaceHoldPickerForLongRing(it) } }
        }
        item {
            TextFieldSetting(
                label = stringResource(R.string.typing_spacebar_text_label),
                value = settings.watch { it.spacebarLabel },
                // The %s token is text the user types, so it travels as an argument.
                hint = stringResource(R.string.typing_spacebar_text_hint, "%s"),
                default = SettingsDefaults.spacebarLabel,
            ) { repository.setSpacebarLabel(it) }
        }
    }
}

@Composable
internal fun TypingHardwareSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onOpenHardwareShortcuts: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // What decides which rows the group holds; each row reads its own value.
    val shortcutsOn = settings.watch { it.hardwareKeyboard.shortcutsEnabled }
    val altDigitHotkeys =
        settings.watch { it.hardwareKeyboard.suggestionHotkeys == SuggestionHotkeyMode.ALT_DIGIT }
    SettingsGroup(stringResource(R.string.typing_group_hardware_title)) {
        item {
            ToggleSetting(
                R.string.typing_hardware_input_title,
                stringResource(R.string.typing_hardware_input_subtitle),
                settings.watch { it.hardwareKeyboardInput },
                info = stringResource(R.string.typing_hardware_input_info),
                default = SettingsDefaults.hardwareKeyboardInput,
            ) { scope.launch { repository.setHardwareKeyboardInput(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_shortcuts_title,
                stringResource(R.string.typing_hw_shortcuts_subtitle),
                shortcutsOn,
                info = stringResource(R.string.typing_hw_shortcuts_info),
                default = SettingsDefaults.hardwareKeyboard.shortcutsEnabled,
            ) { scope.launch { repository.setHwShortcutsEnabled(it) } }
        }
        item(visible = shortcutsOn) {
            // A chord spells itself, so it arrives with no template around it.
            val leaderParts = leaderLabel(parseLeader(settings.watch { it.hardwareKeyboard.leader }) ?: DefaultLeader)
            val leaderText = if (leaderParts.templateRes == 0) {
                leaderParts.text
            } else {
                stringResource(leaderParts.templateRes, leaderParts.text)
            }
            NavRow(
                R.string.typing_hw_shortcuts_list_title,
                stringResource(R.string.typing_hw_shortcuts_list_subtitle),
                value = leaderText,
                route = "hwshortcuts",
                onClick = onOpenHardwareShortcuts,
            )
        }
        item(visible = shortcutsOn) {
            ToggleSetting(
                R.string.typing_hw_digit_chord_title,
                stringResource(R.string.typing_hw_digit_chord_subtitle),
                settings.watch { it.hardwareKeyboard.toolbarDigitChord },
                info = stringResource(R.string.typing_hw_digit_chord_info),
                default = SettingsDefaults.hardwareKeyboard.toolbarDigitChord,
            ) { scope.launch { repository.setHwToolbarDigitChord(it) } }
        }
        item(visible = shortcutsOn) {
            ToggleSetting(
                R.string.typing_hw_modifier_words_title,
                stringResource(R.string.typing_hw_modifier_words_subtitle),
                settings.watch { it.hardwareKeyboard.hintModifierWords },
                info = stringResource(R.string.typing_hw_modifier_words_info),
                default = SettingsDefaults.hardwareKeyboard.hintModifierWords,
            ) { scope.launch { repository.setHwHintModifierWords(it) } }
        }
        item(visible = shortcutsOn) {
            // The readout tracks the live thumb, so its format string is
            // resolved out here: the display lambda is not composable.
            val secondsFormat = stringResource(R.string.typing_hw_picker_timeout_value)
            SliderSetting(
                R.string.typing_hw_picker_timeout_title,
                subtitle = stringResource(R.string.typing_hw_picker_timeout_subtitle),
                value = settings.watch { it.hardwareKeyboard.pickerTimeoutMs }.toFloat(),
                range = PickerTimeoutRange.first.toFloat()..PickerTimeoutRange.last.toFloat(),
                display = { secondsFormat.format("%.1f".format(it / 1000f)) },
                info = stringResource(R.string.typing_hw_picker_timeout_info),
                default = SettingsDefaults.hardwareKeyboard.pickerTimeoutMs.toFloat(),
            ) { scope.launch { repository.setHwPickerTimeoutMs(it.toInt()) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_panel_nav_title,
                stringResource(R.string.typing_hw_panel_nav_subtitle),
                settings.watch { it.hardwareKeyboard.panelNavigation },
                info = stringResource(R.string.typing_hw_panel_nav_info),
                default = SettingsDefaults.hardwareKeyboard.panelNavigation,
            ) { scope.launch { repository.setHwPanelNavigation(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_dpad_keys_title,
                stringResource(R.string.typing_hw_dpad_keys_subtitle),
                settings.watch { it.hardwareKeyboard.dpadKeyNavigation },
                info = stringResource(R.string.typing_hw_dpad_keys_info),
                // The stored default, not the effective one: a television turns
                // this on for itself (see TelevisionDefaults), and a "reset" that
                // claimed to put it back to off there would be a lie.
                default = SettingsDefaults.hardwareKeyboard.dpadKeyNavigation,
            ) { scope.launch { repository.setHwDpadKeyNavigation(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_esc_title,
                stringResource(R.string.typing_hw_esc_subtitle),
                settings.watch { it.hardwareKeyboard.escClosesPanel },
                info = stringResource(R.string.typing_hw_esc_info),
                default = SettingsDefaults.hardwareKeyboard.escClosesPanel,
            ) { scope.launch { repository.setHwEscClosesPanel(it) } }
        }
        item {
            ChoiceSetting(
                R.string.typing_hw_suggestion_hotkeys_title,
                subtitle = stringResource(R.string.typing_hw_suggestion_hotkeys_subtitle),
                info = stringResource(R.string.typing_hw_suggestion_hotkeys_info),
                options = SuggestionHotkeyMode.entries.map { it to stringResource(it.labelRes) },
                selected = settings.watch { it.hardwareKeyboard.suggestionHotkeys },
                default = SettingsDefaults.hardwareKeyboard.suggestionHotkeys,
                detail = { mode -> ChoiceDetail(stringResource(suggestionHotkeyDescRes(mode))) },
            ) { scope.launch { repository.setHwSuggestionHotkeys(it) } }
        }
        item(visible = altDigitHotkeys) {
            ToggleSetting(
                R.string.typing_hw_suggestion_hints_title,
                stringResource(R.string.typing_hw_suggestion_hints_subtitle),
                settings.watch { it.hardwareKeyboard.suggestionHintsAlways },
                info = stringResource(R.string.typing_hw_suggestion_hints_info),
                default = SettingsDefaults.hardwareKeyboard.suggestionHintsAlways,
            ) { scope.launch { repository.setHwSuggestionHintsAlways(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_mac_title,
                stringResource(R.string.typing_hw_mac_subtitle),
                settings.watch { it.hardwareKeyboard.macShortcuts },
                info = stringResource(R.string.typing_hw_mac_info),
                default = SettingsDefaults.hardwareKeyboard.macShortcuts,
            ) { scope.launch { repository.setHwMacShortcuts(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_lang_chord_title,
                stringResource(R.string.typing_hw_lang_chord_subtitle),
                settings.watch { it.hardwareKeyboard.languageSwitchChord },
                info = stringResource(R.string.typing_hw_lang_chord_info),
                default = SettingsDefaults.hardwareKeyboard.languageSwitchChord,
            ) { scope.launch { repository.setHwLanguageSwitchChord(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_auto_show_title,
                stringResource(R.string.typing_hw_auto_show_subtitle),
                settings.watch { it.hardwareKeyboard.autoShowUi },
                info = stringResource(R.string.typing_hw_auto_show_info),
                default = SettingsDefaults.hardwareKeyboard.autoShowUi,
            ) { scope.launch { repository.setHwAutoShowUi(it) } }
        }
    }
}
/**
 * The letter that opens each tool from a physical keyboard, plus the shortcut key
 * that arms them.
 *
 * The rows are every supported tool rather than a list the user builds, so there
 * is no "add" — a tool either has a letter or it does not, and the unbound ones
 * are still reachable through the toolbox.
 */
@Composable
internal fun HardwareShortcutsSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    // The whole screen is laid out from the bindings and the shortcut key, so
    // they are read here; each row reads whether its own tool is on.
    val toolByLetter = settings.watch { it.hardwareKeyboard.toolByLetter }
    val leader = parseLeader(settings.watch { it.hardwareKeyboard.leader }) ?: DefaultLeader
    var editingLeader by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ToolbarTool?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    val tools = remember(toolByLetter) {
        val letterOf = toolByLetter.entries.associate { (letter, tool) -> tool to letter }
        // Bound tools first, in letter order, so the table reads as "what my
        // keyboard does" before "what else could be bound".
        ToolbarTool.entries.filter(::isSupportedTool)
            .sortedWith(compareBy({ letterOf[it] == null }, { letterOf[it] ?: ' ' }, { it.name }))
    }
    val letterOf = toolByLetter.entries.associate { (letter, tool) -> tool to letter }
    // A chord spells itself, so it arrives as plain text; a double tap needs the
    // wording around the modifier name, which only this layer can resolve.
    val leaderSpec = leaderLabel(leader)
    val leaderName = if (leaderSpec.templateRes == 0) {
        leaderSpec.text
    } else {
        stringResource(leaderSpec.templateRes, leaderSpec.text)
    }
    val leaderTitle = stringResource(R.string.hardware_shortcuts_leader_title)

    Column {
        SettingsGroup(
            leaderTitle,
            info = stringResource(R.string.hardware_shortcuts_intro_body, ToolboxLetter, CheatSheetLetter),
        ) {
            item {
                NavRow(
                    R.string.hardware_shortcuts_leader_title,
                    stringResource(R.string.hardware_shortcuts_leader_subtitle),
                    value = leaderName,
                    onClick = { editingLeader = true },
                )
            }
        }
        // What is already bound first, then the free tools by the same groups
        // the Tools screen uses, so a list of every tool reads as a few short
        // ones instead of one long one.
        val assigned = tools.filter { letterOf[it] != null }
        if (assigned.isNotEmpty()) {
            SettingsGroup(stringResource(R.string.hardware_shortcuts_assigned_group_title)) {
                for (tool in assigned) {
                    item { HardwareShortcutRow(tool, letterOf[tool], settings, repository) { editing = tool } }
                }
            }
        }
        for ((groupTitle, groupTools) in ToolGroups) {
            val free = groupTools.filter { it in tools && letterOf[it] == null }
            if (free.isEmpty()) continue
            SettingsGroup(stringResource(groupTitle)) {
                for (tool in free) {
                    item { HardwareShortcutRow(tool, null, settings, repository) { editing = tool } }
                }
            }
        }
        TextButton(
            onClick = { confirmReset = true },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) { Text(stringResource(CommonR.string.common_reset_defaults)) }
    }

    if (editingLeader) {
        LeaderCaptureDialog(
            current = leader,
            onDismiss = { editingLeader = false },
            onPick = { picked ->
                editingLeader = false
                scope.launch { repository.setHwLeader(formatLeader(picked)) }
            },
        )
    }
    editing?.let { tool ->
        LetterCaptureDialog(
            tool = tool,
            current = letterOf[tool],
            takenBy = { letter -> toolByLetter[letter] },
            onDismiss = { editing = null },
            onPick = { letter ->
                editing = null
                scope.launch { repository.setHwToolLetter(letter, tool) }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.hardware_shortcuts_reset_title)) },
            text = { Text(stringResource(R.string.hardware_shortcuts_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        repository.setHwToolLetters(DefaultToolLetters)
                        repository.setHwLeader(formatLeader(DefaultLeader))
                    }
                }) { Text(stringResource(CommonR.string.common_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
/**
 * Picks the shortcut key: a double-tapped modifier, or a chord pressed on an
 * attached keyboard.
 *
 * The double-tap choices matter more than the capture field — most people
 * editing this screen are holding a phone with no keyboard plugged in, and a
 * "press a key" prompt would leave them stuck.
 */
@Composable
private fun LeaderCaptureDialog(
    current: LeaderTrigger,
    onDismiss: () -> Unit,
    onPick: (LeaderTrigger) -> Unit,
) {
    var captured by remember { mutableStateOf<KeyChord?>(null) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hardware_shortcuts_leader_title)) },
        text = {
            Column {
                CaptionText(stringResource(R.string.hardware_shortcuts_double_tap_body))
                for (modifier in TapModifier.entries) {
                    val trigger = LeaderTrigger.DoubleTap(modifier)
                    // The same wording the row on the settings screen shows, and
                    // a double tap always carries a template to fill.
                    val spec = leaderLabel(trigger)
                    WmRow(
                        title = stringResource(spec.templateRes, spec.text),
                        trailing = {
                            if (current == trigger && captured == null) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = stringResource(
                                        R.string.hardware_shortcuts_current_desc,
                                    ),
                                )
                            }
                        },
                        onClick = { onPick(trigger) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                CaptionText(stringResource(R.string.hardware_shortcuts_capture_body))
                // A real focusable window, unlike the keyboard's own, so Compose
                // focus is the right tool here.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .focusRequester(requester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val native = event.nativeKeyEvent
                            // Wait for the key the modifiers are qualifying.
                            if (KeyEvent.isModifierKey(native.keyCode)) return@onPreviewKeyEvent true
                            val chord = KeyChord(
                                keyCode = native.keyCode,
                                ctrl = native.isCtrlPressed,
                                alt = native.isAltPressed,
                                shift = native.isShiftPressed,
                                meta = native.isMetaPressed,
                            )
                            // A bare key would swallow ordinary typing, and a
                            // chord this app cannot name cannot be stored.
                            captured = chord.takeIf { it.hasModifier && formatChord(it) != null }
                            true
                        },
                ) {
                    Text(
                        captured?.let(::describeChord)
                            ?: stringResource(R.string.hardware_shortcuts_waiting_progress),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                captured?.let { chord ->
                    if (chord in ReservedChords) {
                        CaptionText(
                            stringResource(
                                R.string.hardware_shortcuts_reserved_error,
                                describeChord(chord),
                            ),
                            error = true,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = captured != null,
                onClick = { captured?.let { onPick(LeaderTrigger.Chord(it)) } },
            ) { Text(stringResource(R.string.hardware_shortcuts_use_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
/**
 * Picks the letter for one tool. Typed rather than captured: this is a single
 * character, and a text field works with or without a keyboard attached.
 */
@Composable
private fun LetterCaptureDialog(
    tool: ToolbarTool,
    current: Char?,
    takenBy: (Char) -> ToolbarTool?,
    onDismiss: () -> Unit,
    onPick: (Char) -> Unit,
) {
    var text by remember { mutableStateOf(current?.toString().orEmpty()) }
    val letter = text.trim().uppercase().firstOrNull()
    val valid = letter != null && (letter in 'A'..'Z' || letter in '0'..'9') &&
        letter !in ReservedLetters
    val clash = letter?.let(takenBy)?.takeIf { it != tool }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(toolTitle(tool))) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.takeLast(1) },
                    label = { Text(stringResource(R.string.hardware_shortcuts_letter_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    letter in ReservedLetters -> CaptionText(
                        stringResource(
                            R.string.hardware_shortcuts_letter_reserved_error,
                            letter?.toString().orEmpty(),
                            ToolboxLetter,
                            CheatSheetLetter,
                        ),
                        error = true,
                    )
                    clash != null -> CaptionText(
                        stringResource(
                            R.string.hardware_shortcuts_letter_clash_body,
                            letter.toString(),
                            toolTitle(clash),
                        ),
                    )
                    else -> CaptionText(stringResource(R.string.hardware_shortcuts_letter_hint))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { letter?.let(onPick) }) {
                Text(
                    if (clash != null) {
                        stringResource(R.string.hardware_shortcuts_letter_move_action)
                    } else {
                        stringResource(CommonR.string.common_save)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * What two quick presses of space type. Two booleans in the repository, one
 * decision on screen: the tab had priority over the full stop whenever both
 * were on, so the pair only ever meant one of these three.
 */
// See the note on ToolbarFit: a file-private enum carries its own glyph,
// because [ChoiceOptionIcons] cannot name a type it cannot see.
private enum class DoubleSpaceAction(
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
    val icon: ImageVector,
) {
    NONE(
        R.string.typing_double_space_none_label,
        R.string.typing_double_space_none_desc,
        Icons.Outlined.Block,
    ),
    PERIOD(
        R.string.typing_double_space_period_label,
        R.string.typing_double_space_period_desc,
        Icons.Outlined.FiberManualRecord,
    ),
    TAB(
        R.string.typing_double_space_tab_label,
        R.string.typing_double_space_tab_desc,
        Icons.Outlined.KeyboardTab,
    ),
}

private val DefaultDoubleSpace = when {
    SettingsDefaults.autoText.doubleSpaceTab -> DoubleSpaceAction.TAB
    SettingsDefaults.autoText.doubleSpacePeriod -> DoubleSpaceAction.PERIOD
    else -> DoubleSpaceAction.NONE
}

/** One tool in the hardware-shortcut list: its icon, the letter it holds, and the way to clear it. */
@Composable
private fun HardwareShortcutRow(
    tool: ToolbarTool,
    letter: Char?,
    settings: LiveSettings,
    repository: SettingsRepository,
    onEdit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toolOff = settings.watch { tool !in it.enabledTools || !isUsableTool(tool, it) }
            WmRow(
                title = stringResource(toolTitle(tool)),
                leading = {
                    SlotIcon(IconSlots.forTool(tool), contentDescription = null)
                },
                // A tool with no API key is off as far as the keyboard
                // is concerned, whatever the Tools screen last stored.
                subtitle = if (toolOff) {
                    stringResource(R.string.hardware_shortcuts_tool_off_subtitle)
                } else {
                    null
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            letter?.toString() ?: stringResource(CommonR.string.common_none),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (letter == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        if (letter != null) {
                            IconButton(onClick = {
                                scope.launch { repository.setHwToolLetter(letter, null) }
                            }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(
                                        R.string.hardware_shortcuts_unbind_desc,
                                        toolTitle(tool),
                                    ),
                                )
                            }
                        }
                    }
                },
                onClick = onEdit,
            )
}

/** What one spacebar swipe slot does under each answer, for the sheet. */
private fun spaceSwipeDescRes(action: SpaceSwipeAction): Int = when (action) {
    SpaceSwipeAction.NONE -> R.string.typing_space_swipe_none_desc
    SpaceSwipeAction.LANGUAGE -> R.string.typing_space_swipe_language_desc
    SpaceSwipeAction.CURSOR -> R.string.typing_space_swipe_cursor_desc
    SpaceSwipeAction.NUMPAD -> R.string.typing_space_swipe_numpad_desc
    SpaceSwipeAction.KEYBOARDS -> R.string.typing_space_swipe_keyboards_desc
}

/**
 * How far a detected language is allowed to go, for the sheet. The names are
 * three points on a dial and say nothing about what moves.
 */
private fun detectionStrengthDescRes(strength: LanguageDetectionStrength): Int = when (strength) {
    LanguageDetectionStrength.GENTLE -> R.string.typing_language_detection_gentle_desc
    LanguageDetectionStrength.BALANCED -> R.string.typing_language_detection_balanced_desc
    LanguageDetectionStrength.AGGRESSIVE -> R.string.typing_language_detection_aggressive_desc
}

/**
 * How long an undone correction stays undone, for the sheet. Each line says
 * what the level costs the user, not how the store spells it.
 */
private fun undoMemoryDescRes(level: UndoMemory): Int = when (level) {
    UndoMemory.OFF -> R.string.typing_undo_memory_off_desc
    UndoMemory.LIGHT -> R.string.typing_undo_memory_light_desc
    UndoMemory.NORMAL -> R.string.typing_undo_memory_normal_desc
    UndoMemory.STRICT -> R.string.typing_undo_memory_strict_desc
}

/** What each grouping does to a long number, for the sheet. */
private fun numberGroupingDescRes(style: NumberGrouping): Int = when (style) {
    NumberGrouping.AUTO -> R.string.typing_smart_number_grouping_auto_desc
    NumberGrouping.WESTERN -> R.string.typing_smart_number_grouping_western_desc
    NumberGrouping.SOUTH_ASIAN -> R.string.typing_smart_number_grouping_south_asian_desc
}

/** Where the possessive swipe starts, for its sheet (#169). */
private fun possessiveSwipeDescRes(key: GlideApostropheKey): Int = when (key) {
    GlideApostropheKey.COMMA -> R.string.typing_possessive_swipe_comma_desc
    GlideApostropheKey.PERIOD -> R.string.typing_possessive_swipe_period_desc
    GlideApostropheKey.APOSTROPHE -> R.string.typing_possessive_swipe_key_desc
    GlideApostropheKey.OFF, GlideApostropheKey.SPACE -> R.string.typing_possessive_swipe_off_desc
}

/** Which key carries the apostrophe in a glide, and what it costs, for the sheet. */
private fun glideApostropheDescRes(key: GlideApostropheKey): Int = when (key) {
    GlideApostropheKey.OFF -> R.string.typing_glide_apostrophe_off_desc
    GlideApostropheKey.COMMA -> R.string.typing_glide_apostrophe_comma_desc
    GlideApostropheKey.PERIOD -> R.string.typing_glide_apostrophe_period_desc
    GlideApostropheKey.SPACE -> R.string.typing_glide_apostrophe_space_desc
    GlideApostropheKey.APOSTROPHE -> R.string.typing_glide_apostrophe_key_desc
}

/** One line under each tier of the glide picker's "When to ask" choice. */
@StringRes
private fun glidePickerSensitivityDescRes(tier: GlidePickerSensitivity): Int = when (tier) {
    GlidePickerSensitivity.NEAR_TIES -> R.string.typing_glide_picker_near_ties_desc
    GlidePickerSensitivity.CLOSE_CALLS -> R.string.typing_glide_picker_close_calls_desc
    GlidePickerSensitivity.ANY_DOUBT -> R.string.typing_glide_picker_any_doubt_desc
    GlidePickerSensitivity.EVERY_PAUSE -> R.string.typing_glide_picker_every_pause_desc
}

/** What a number key does on a physical keyboard, for the sheet. */
private fun suggestionHotkeyDescRes(mode: SuggestionHotkeyMode): Int = when (mode) {
    SuggestionHotkeyMode.OFF -> R.string.typing_hw_suggestion_hotkeys_off_desc
    SuggestionHotkeyMode.LEADER_DIGIT -> R.string.typing_hw_suggestion_hotkeys_leader_desc
    SuggestionHotkeyMode.ALT_DIGIT -> R.string.typing_hw_suggestion_hotkeys_alt_desc
}

/**
 * Whose words a swipe may answer with, how steady the word it shows is, and
 * whether it may finish a word early — three settings that only make sense
 * beside one another, so they are written and read together.
 */
@Suppress("LongMethod")
private fun SettingsGroupScope.glideVocabularyRows(
    settings: LiveSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
) {
    // Whose words a swipe may answer with — the same question as
    // the row above, asked on the other axis.
    item {
        // Automatic behaves like whichever rung the user has accepted on the
        // strip, which the options alone cannot show (#316).
        val sandbox = settings.watch { it.gesture.sandbox }
        val rung by produceState<GlideSandbox?>(null, sandbox) {
            value = if (sandbox == GlideSandbox.AUTOMATIC) repository.glideSandboxRung() else null
        }
        ChoiceSetting(
            title = R.string.typing_glide_sandbox_title,
            subtitle = rung?.let {
                stringResource(R.string.typing_glide_sandbox_automatic_subtitle, stringResource(it.labelRes))
            } ?: stringResource(R.string.typing_glide_sandbox_subtitle),
            info = stringResource(R.string.typing_glide_sandbox_info),
            options = GlideSandbox.entries.map { it to stringResource(it.labelRes) },
            selected = sandbox,
            onChange = { scope.launch { repository.setGestureSandbox(it) } },
            default = SettingsDefaults.gesture.sandbox,
        )
    }
    // The way back out of the sandbox, one word at a time: the stroke that
    // wrote a word, handed to every list there is (#135). Always reachable
    // from a held word on the strip; this is only whether it also offers
    // itself, which costs a strip slot each time a swiped word is read back.
    item {
        ToggleSetting(
            R.string.typing_glide_search_all_chip_title,
            stringResource(R.string.typing_glide_search_all_chip_subtitle),
            settings.watch { it.gesture.searchAllChip },
            info = stringResource(R.string.typing_glide_search_all_chip_info),
            default = SettingsDefaults.gesture.searchAllChip,
        ) { scope.launch { repository.setGestureSearchAllChip(it) } }
    }
    // How hard the word shown mid-stroke resists being replaced.
    item {
        ChoiceSetting(
            title = R.string.typing_glide_steadiness_title,
            subtitle = stringResource(R.string.typing_glide_steadiness_subtitle),
            info = stringResource(R.string.typing_glide_steadiness_info),
            options = GlidePreviewSteadiness.entries.map {
                it to stringResource(it.labelRes)
            },
            selected = settings.watch { it.gesture.previewSteadiness },
            onChange = { scope.launch { repository.setGesturePreviewSteadiness(it) } },
            default = SettingsDefaults.gesture.previewSteadiness,
        )
    }
    // Whether a glide may offer a word the stroke has not finished
    // spelling — the tap typist's head start, for a swipe.
    item {
        ChoiceSetting(
            title = R.string.typing_glide_lookahead_title,
            subtitle = stringResource(R.string.typing_glide_lookahead_subtitle),
            info = stringResource(R.string.typing_glide_lookahead_info),
            options = GlideLookAhead.entries.map { it to stringResource(it.labelRes) },
            selected = settings.watch { it.gesture.lookAhead },
            onChange = { scope.launch { repository.setGestureLookAhead(it) } },
            default = SettingsDefaults.gesture.lookAhead,
        )
    }
    // Which word the stroke colours rather than only bolds, and how far that
    // colour reaches. Here rather than in Appearance because what it says is
    // about the decode, not about the strip: bold means "this leads", colour
    // means "this is what a lift types".
    item {
        ChoiceSetting(
            title = R.string.typing_glide_commit_color_title,
            subtitle = stringResource(R.string.typing_glide_commit_color_subtitle),
            info = stringResource(R.string.typing_glide_commit_color_info),
            options = GlideCommitColor.entries.map { it to stringResource(it.labelRes) },
            selected = settings.watch { it.gesture.commitColor },
            onChange = { scope.launch { repository.setGestureCommitColor(it) } },
            default = SettingsDefaults.gesture.commitColor,
        )
    }
    item {
        ChoiceSetting(
            title = R.string.typing_glide_commit_scope_title,
            subtitle = stringResource(R.string.typing_glide_commit_scope_subtitle),
            info = stringResource(R.string.typing_glide_commit_scope_info),
            options = GlideCommitColorScope.entries.map { it to stringResource(it.labelRes) },
            selected = settings.watch { it.gesture.commitColorScope },
            onChange = { scope.launch { repository.setGestureCommitColorScope(it) } },
            default = SettingsDefaults.gesture.commitColorScope,
        )
    }
}

/**
 * The decoder's three tolerances: how far the start, the end and the middle of
 * a stroke may sit from the keys of a word for that word to be an answer at all
 * (#222). Out of line for the same reason as [glideVocabularyRows] — the screen
 * body is past its length budget and these three belong together.
 *
 * All three are in key widths and share [GlideRadiusRange] with the setters
 * that store them, so a value the slider reaches is always one that is kept.
 */
private fun SettingsGroupScope.glideRadiusRows(
    settings: LiveSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
) {
    // Where the finger went down, which is a deliberate placement.
    item {
        val valueFormat = stringResource(R.string.typing_value_multiplier_suffix)
        SliderSetting(
            R.string.typing_glide_start_radius_title,
            subtitle = stringResource(R.string.typing_glide_start_radius_subtitle),
            value = settings.watch { it.gesture.startRadius },
            range = GlideRadiusRange,
            display = { valueFormat.format("%.1f".format(it)) },
            info = stringResource(R.string.typing_glide_start_radius_info),
            default = SettingsDefaults.gesture.startRadius,
        ) { scope.launch { repository.setGestureStartRadius(it) } }
    }
    // Where it came up, which is only where a movement stopped.
    item {
        val valueFormat = stringResource(R.string.typing_value_multiplier_suffix)
        SliderSetting(
            R.string.typing_glide_end_radius_title,
            subtitle = stringResource(R.string.typing_glide_end_radius_subtitle),
            value = settings.watch { it.gesture.endRadius },
            range = GlideRadiusRange,
            display = { valueFormat.format("%.1f".format(it)) },
            info = stringResource(R.string.typing_glide_end_radius_info),
            default = SettingsDefaults.gesture.endRadius,
        ) { scope.launch { repository.setGestureEndRadius(it) } }
    }
    // And how much corner cutting the letters in between survive.
    item {
        val valueFormat = stringResource(R.string.typing_value_multiplier_suffix)
        SliderSetting(
            R.string.typing_glide_near_radius_title,
            subtitle = stringResource(R.string.typing_glide_near_radius_subtitle),
            value = settings.watch { it.gesture.nearRadius },
            range = GlideRadiusRange,
            display = { valueFormat.format("%.1f".format(it)) },
            info = stringResource(R.string.typing_glide_near_radius_info),
            default = SettingsDefaults.gesture.nearRadius,
        ) { scope.launch { repository.setGestureNearRadius(it) } }
    }
}

/**
 * The three marks a stroke can carry for a doubled letter, and what each one
 * has to look like before the decoder reads it (#270).
 *
 * A glide draws "good" and "god" identically, so shape has nothing to say and
 * only an intent mark can: a pause on the key, a circle on it, or a rub back
 * and forth over it. How readily each registers is a property of the hand, not
 * of the layout, which is why they are worth moving at all. The pause is
 * always on and has one number; the other two are readings that can be
 * switched off, and their sliders are hidden while they are.
 *
 * Out of line beside [glideRadiusRows] for the same reason: the screen body is
 * past its length budget. Every range is shared with the setter that stores it,
 * so a value the slider reaches is always one that is kept (#241).
 */
private fun SettingsGroupScope.glideIntentRows(
    settings: LiveSettings,
    loopDouble: Boolean,
    wiggleDouble: Boolean,
    repository: SettingsRepository,
    scope: CoroutineScope,
) {
    // The pause. No switch: a pause is also read as evidence against the words
    // that have no letter where the finger stopped, so it is never off.
    item {
        val valueFormat = stringResource(R.string.typing_value_multiplier_suffix)
        SliderSetting(
            R.string.typing_glide_dwell_title,
            subtitle = stringResource(R.string.typing_glide_dwell_subtitle),
            value = settings.watch { it.gesture.dwellFull },
            range = GlideDwellFullRange,
            display = { valueFormat.format("%.1f".format(it)) },
            info = stringResource(R.string.typing_glide_dwell_info),
            default = SettingsDefaults.gesture.dwellFull,
        ) { scope.launch { repository.setGestureDwellFull(it) } }
    }
    // The circle, and the three things that decide whether a curl is one.
    item {
        ToggleSetting(
            R.string.typing_glide_loop_title,
            stringResource(R.string.typing_glide_loop_subtitle),
            loopDouble,
            info = stringResource(R.string.typing_glide_loop_info),
            default = SettingsDefaults.gesture.loopDouble,
        ) { scope.launch { repository.setGestureLoopDouble(it) } }
    }
    item(visible = loopDouble) {
        val valueFormat = stringResource(R.string.typing_value_multiplier_suffix)
        SliderSetting(
            R.string.typing_glide_loop_arc_title,
            subtitle = stringResource(R.string.typing_glide_loop_arc_subtitle),
            value = settings.watch { it.gesture.loopMinArc },
            range = GlideLoopMinArcRange,
            display = { valueFormat.format("%.1f".format(it)) },
            info = stringResource(R.string.typing_glide_loop_arc_info),
            default = SettingsDefaults.gesture.loopMinArc,
        ) { scope.launch { repository.setGestureLoopMinArc(it) } }
    }
    item(visible = loopDouble) {
        val valueFormat = stringResource(R.string.typing_value_multiplier_suffix)
        SliderSetting(
            R.string.typing_glide_loop_extent_title,
            subtitle = stringResource(R.string.typing_glide_loop_extent_subtitle),
            value = settings.watch { it.gesture.loopExtent },
            range = GlideLoopExtentRange,
            display = { valueFormat.format("%.1f".format(it)) },
            info = stringResource(R.string.typing_glide_loop_extent_info),
            default = SettingsDefaults.gesture.loopExtent,
        ) { scope.launch { repository.setGestureLoopExtent(it) } }
    }
    item(visible = loopDouble) {
        val valueFormat = stringResource(R.string.typing_value_multiplier_suffix)
        SliderSetting(
            R.string.typing_glide_loop_radius_title,
            subtitle = stringResource(R.string.typing_glide_loop_radius_subtitle),
            value = settings.watch { it.gesture.loopRadius },
            range = GlideLoopRadiusRange,
            display = { valueFormat.format("%.2f".format(it)) },
            info = stringResource(R.string.typing_glide_loop_radius_info),
            default = SettingsDefaults.gesture.loopRadius,
        ) { scope.launch { repository.setGestureLoopRadius(it) } }
    }
    // The rub, which ships off: at the sloppy end of a stroke a slow pivot
    // with tremor on it looks the same.
    item {
        ToggleSetting(
            R.string.typing_glide_wiggle_title,
            stringResource(R.string.typing_glide_wiggle_subtitle),
            wiggleDouble,
            info = stringResource(R.string.typing_glide_wiggle_info),
            default = SettingsDefaults.gesture.wiggleDouble,
        ) { scope.launch { repository.setGestureWiggleDouble(it) } }
    }
    item(visible = wiggleDouble) {
        val percentFormat = stringResource(R.string.typing_value_percent)
        SliderSetting(
            R.string.typing_glide_wiggle_strength_title,
            subtitle = stringResource(R.string.typing_glide_wiggle_strength_subtitle),
            value = settings.watch { it.gesture.wiggleWeight },
            range = GlideWiggleWeightRange,
            display = { percentFormat.format((it * 100).roundToInt()) },
            info = stringResource(R.string.typing_glide_wiggle_strength_info),
            default = SettingsDefaults.gesture.wiggleWeight,
        ) { scope.launch { repository.setGestureWiggleWeight(it) } }
    }
    item(visible = wiggleDouble) {
        val valueFormat = stringResource(R.string.typing_value_multiplier_suffix)
        SliderSetting(
            R.string.typing_glide_wiggle_extent_title,
            subtitle = stringResource(R.string.typing_glide_wiggle_extent_subtitle),
            value = settings.watch { it.gesture.wiggleExtent },
            range = GlideWiggleExtentRange,
            display = { valueFormat.format("%.2f".format(it)) },
            info = stringResource(R.string.typing_glide_wiggle_extent_info),
            default = SettingsDefaults.gesture.wiggleExtent,
        ) { scope.launch { repository.setGestureWiggleExtent(it) } }
    }
}

/** Two lines per source: its name and what it knows. */
private val SynonymSourceRowHeight = 64.dp
