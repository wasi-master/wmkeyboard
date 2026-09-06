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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.wasimaster.wmkeyboard.core.media.hasNotificationAccess
import com.wasimaster.wmkeyboard.core.settings.BackspaceSwipeUnit
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SuggestionHotkeyMode
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
import com.wasimaster.wmkeyboard.core.tools.leaderLabel
import androidx.compose.ui.unit.dp
import android.os.Build
import com.wasimaster.wmkeyboard.core.settings.PickerTimeoutRange
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import com.wasimaster.wmkeyboard.core.settings.GlideApostropheKey
import com.wasimaster.wmkeyboard.core.settings.GlideVocabulary
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.settings.LanguageDetectionStrength
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.LetterSwipeAction
import com.wasimaster.wmkeyboard.core.settings.SpaceSwipeAction
import com.wasimaster.wmkeyboard.core.settings.SpacebarDisplay
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** One spacebar-swipe slot (quick or hold+swipe): nothing / language / cursor. */
@Composable
private fun SpaceSwipeSetting(
    title: String,
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
internal fun TypingSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
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





    SettingsGroup(stringResource(R.string.typing_group_backspace_title)) {
        item {
            ToggleSetting(
                R.string.typing_backspace_swipe_title,
                stringResource(R.string.typing_backspace_swipe_subtitle),
                settings.backspaceSwipeDelete,
                info = stringResource(R.string.typing_backspace_swipe_info),
                default = SettingsDefaults.backspaceSwipeDelete,
            ) { scope.launch { repository.setBackspaceSwipeDelete(it) } }
        }
        if (settings.backspaceSwipeDelete) {
            item {
                ChoiceSetting(
                    R.string.typing_backspace_unit_title,
                    subtitle = stringResource(R.string.typing_backspace_unit_subtitle),
                    info = stringResource(R.string.typing_backspace_unit_info),
                    options = listOf(
                        BackspaceSwipeUnit.WORD to
                            stringResource(R.string.typing_backspace_unit_word),
                        BackspaceSwipeUnit.CHARACTER to
                            stringResource(R.string.typing_backspace_unit_character),
                    ),
                    selected = settings.textEditing.backspaceSwipeUnit,
                    default = SettingsDefaults.textEditing.backspaceSwipeUnit,
                    detail = { unit ->
                        ChoiceDetail(
                            stringResource(
                                if (unit == BackspaceSwipeUnit.WORD) {
                                    R.string.typing_backspace_unit_word_desc
                                } else {
                                    R.string.typing_backspace_unit_character_desc
                                },
                            ),
                        )
                    },
                ) { scope.launch { repository.setBackspaceSwipeUnit(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_backspace_preview_title,
                    stringResource(R.string.typing_backspace_preview_subtitle),
                    settings.textEditing.backspaceSwipePreview,
                    info = stringResource(R.string.typing_backspace_preview_info),
                    default = SettingsDefaults.textEditing.backspaceSwipePreview,
                ) { scope.launch { repository.setBackspaceSwipePreview(it) } }
            }
            if (settings.textEditing.backspaceSwipeUnit == BackspaceSwipeUnit.WORD) {
                item {
                    SliderSetting(
                        R.string.typing_backspace_step_title,
                        subtitle = stringResource(R.string.typing_backspace_step_subtitle),
                        value = settings.textEditing.backspaceWordStepDp.toFloat(),
                        range = 32f..120f,
                        display = { context.getString(R.string.typing_value_dp, it.toInt()) },
                        info = stringResource(R.string.typing_backspace_step_info),
                        default = SettingsDefaults.textEditing.backspaceWordStepDp.toFloat(),
                    ) { scope.launch { repository.setBackspaceWordStepDp(it.toInt()) } }
                }
            } else {
                item {
                    SliderSetting(
                        R.string.typing_backspace_char_step_title,
                        subtitle = stringResource(R.string.typing_backspace_char_step_subtitle),
                        value = settings.textEditing.backspaceCharStepDp.toFloat(),
                        range = 8f..48f,
                        display = { context.getString(R.string.typing_value_dp, it.toInt()) },
                        info = stringResource(R.string.typing_backspace_char_step_info),
                        default = SettingsDefaults.textEditing.backspaceCharStepDp.toFloat(),
                    ) { scope.launch { repository.setBackspaceCharStepDp(it.toInt()) } }
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.typing_group_enter_title)) {
        item {
            ToggleSetting(
                R.string.typing_shift_enter_title,
                stringResource(R.string.typing_shift_enter_subtitle),
                settings.layoutBehavior.shiftEnterNewline,
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
                settings.volumeCursor,
                info = stringResource(R.string.typing_volume_cursor_info),
                default = SettingsDefaults.volumeCursor,
            ) { scope.launch { repository.setVolumeCursor(it) } }
        }
        if (settings.volumeCursor) {
            item {
                ToggleSetting(
                    R.string.typing_volume_cursor_media_title,
                    stringResource(R.string.typing_volume_cursor_media_subtitle),
                    settings.volumeCursorMediaAware,
                    info = stringResource(R.string.typing_volume_cursor_media_info),
                    default = SettingsDefaults.volumeCursorMediaAware,
                ) { scope.launch { repository.setVolumeCursorMediaAware(it) } }
            }
        }
    }

}

@Composable
internal fun TypingCorrectionsSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    SettingsGroup(stringResource(R.string.typing_group_corrections_title)) {
        item {
            ToggleSetting(
                R.string.typing_autocorrect_title,
                stringResource(R.string.typing_autocorrect_subtitle),
                settings.autocorrect,
                info = stringResource(R.string.typing_autocorrect_info),
                default = SettingsDefaults.autocorrect,
            ) { scope.launch { repository.setAutocorrect(it) } }
        }
        if (settings.autocorrect) {
            item {
                val valueFormat = stringResource(R.string.typing_value_multiplier_prefix)
                SliderSetting(
                    R.string.typing_autocorrect_confidence_title,
                    subtitle = stringResource(R.string.typing_autocorrect_confidence_subtitle),
                    value = settings.autocorrectConfidence,
                    range = 1.5f..10f,
                    display = { valueFormat.format("%.1f".format(it)) },
                    info = stringResource(R.string.typing_autocorrect_confidence_info),
                    default = SettingsDefaults.autocorrectConfidence,
                ) { scope.launch { repository.setAutocorrectConfidence(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_autocorrect_adaptive_title,
                    stringResource(R.string.typing_autocorrect_adaptive_subtitle),
                    settings.autocorrectAdaptive,
                    info = stringResource(R.string.typing_autocorrect_adaptive_info),
                    default = SettingsDefaults.autocorrectAdaptive,
                ) { scope.launch { repository.setAutocorrectAdaptive(it) } }
            }
            item {
                val percentFormat = stringResource(R.string.typing_value_percent)
                SliderSetting(
                    R.string.typing_timing_signal_title,
                    subtitle = stringResource(R.string.typing_timing_signal_subtitle),
                    value = settings.suggestionStrip.timingSignalStrength,
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
                    settings.revertAutocorrectOnBackspace,
                    info = stringResource(R.string.typing_undo_autocorrect_info),
                    default = SettingsDefaults.revertAutocorrectOnBackspace,
                ) { scope.launch { repository.setRevertAutocorrectOnBackspace(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_skip_all_caps_title,
                    stringResource(R.string.typing_skip_all_caps_subtitle),
                    settings.autocorrectSkipAllCaps,
                    info = stringResource(R.string.typing_skip_all_caps_info),
                    default = SettingsDefaults.autocorrectSkipAllCaps,
                ) { scope.launch { repository.setAutocorrectSkipAllCaps(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_block_offensive_title,
                    stringResource(R.string.typing_block_offensive_subtitle),
                    settings.suggestionStrip.blockOffensiveWords,
                    info = stringResource(R.string.typing_block_offensive_info),
                    default = SettingsDefaults.suggestionStrip.blockOffensiveWords,
                ) { scope.launch { repository.setBlockOffensiveWords(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_context_rerank_title,
                    stringResource(R.string.typing_context_rerank_subtitle),
                    settings.suggestionStrip.contextRerank,
                    info = stringResource(R.string.typing_context_rerank_info),
                    default = SettingsDefaults.suggestionStrip.contextRerank,
                ) { scope.launch { repository.setContextRerank(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_autocorrect_splits_title,
                    stringResource(R.string.typing_autocorrect_splits_subtitle),
                    settings.suggestionStrip.autocorrectSplits,
                    info = stringResource(R.string.typing_autocorrect_splits_info),
                    default = SettingsDefaults.suggestionStrip.autocorrectSplits,
                ) { scope.launch { repository.setAutocorrectSplits(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_language_detection_title,
                    stringResource(R.string.typing_language_detection_subtitle),
                    settings.suggestionStrip.languageDetection,
                    info = stringResource(R.string.typing_language_detection_info),
                    default = SettingsDefaults.suggestionStrip.languageDetection,
                ) { scope.launch { repository.setLanguageDetection(it) } }
            }
            if (settings.suggestionStrip.languageDetection) {
                item {
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
                        selected = settings.suggestionStrip.languageDetectionStrength,
                        default = SettingsDefaults.suggestionStrip.languageDetectionStrength,
                        detail = { strength ->
                            ChoiceDetail(stringResource(detectionStrengthDescRes(strength)))
                        },
                    ) { scope.launch { repository.setLanguageDetectionStrength(it) } }
                }
            }
            if (settings.numberRow) {
                item {
                    ToggleSetting(
                        R.string.typing_number_row_corrections_title,
                        stringResource(R.string.typing_number_row_corrections_subtitle),
                        settings.suggestionStrip.numberRowCorrections,
                        info = stringResource(R.string.typing_number_row_corrections_info),
                        default = SettingsDefaults.suggestionStrip.numberRowCorrections,
                    ) { scope.launch { repository.setNumberRowCorrections(it) } }
                }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_register_priors_title,
                stringResource(R.string.typing_register_priors_subtitle),
                settings.suggestionStrip.registerPriors,
                info = stringResource(R.string.typing_register_priors_info),
                default = SettingsDefaults.suggestionStrip.registerPriors,
            ) { scope.launch { repository.setRegisterPriors(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_auto_apostrophe_title,
                stringResource(R.string.typing_auto_apostrophe_subtitle),
                settings.autoApostrophe,
                info = stringResource(R.string.typing_auto_apostrophe_info),
                default = SettingsDefaults.autoApostrophe,
            ) { scope.launch { repository.setAutoApostrophe(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_auto_capitalize_title,
                stringResource(R.string.typing_auto_capitalize_subtitle),
                settings.autoCapitalize,
                info = stringResource(R.string.typing_auto_capitalize_info),
                default = SettingsDefaults.autoCapitalize,
            ) { scope.launch { repository.setAutoCapitalize(it) } }
        }
        item {
            val doubleSpace = when {
                settings.doubleSpaceTab -> DoubleSpaceAction.TAB
                settings.doubleSpacePeriod -> DoubleSpaceAction.PERIOD
                else -> DoubleSpaceAction.NONE
            }
            ChoiceSetting(
                R.string.typing_double_space_title,
                subtitle = stringResource(R.string.typing_double_space_subtitle),
                info = stringResource(R.string.typing_double_space_info),
                options = DoubleSpaceAction.entries.map { it to stringResource(it.labelRes) },
                selected = doubleSpace,
                default = DefaultDoubleSpace,
                detail = { action -> ChoiceDetail(stringResource(action.descRes)) },
            ) { action ->
                scope.launch {
                    repository.setDoubleSpacePeriod(action == DoubleSpaceAction.PERIOD)
                    repository.setDoubleSpaceTab(action == DoubleSpaceAction.TAB)
                }
            }
        }
        if (settings.doubleSpacePeriod || settings.doubleSpaceTab) {
            item {
                SliderSetting(
                    R.string.typing_double_space_window_title,
                    subtitle = stringResource(R.string.typing_double_space_window_subtitle),
                    value = settings.textEditing.doubleSpaceWindowMs.toFloat(),
                    range = 200f..800f,
                    display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                    info = stringResource(R.string.typing_double_space_window_info),
                    default = SettingsDefaults.textEditing.doubleSpaceWindowMs.toFloat(),
                ) { scope.launch { repository.setDoubleSpaceWindowMs(it.toInt()) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_auto_space_punctuation_title,
                stringResource(R.string.typing_auto_space_punctuation_subtitle),
                settings.autoSpaceAfterPunctuation,
                info = stringResource(R.string.typing_auto_space_punctuation_info),
                default = SettingsDefaults.autoSpaceAfterPunctuation,
            ) { scope.launch { repository.setAutoSpaceAfterPunctuation(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_space_after_suggestion_title,
                stringResource(R.string.typing_space_after_suggestion_subtitle),
                settings.suggestionStrip.autoSpaceAfterSuggestion,
                info = stringResource(R.string.typing_space_after_suggestion_info),
                default = SettingsDefaults.suggestionStrip.autoSpaceAfterSuggestion,
            ) { scope.launch { repository.setAutoSpaceAfterSuggestion(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_wrap_selection_title,
                stringResource(R.string.typing_wrap_selection_subtitle),
                settings.textEditing.wrapSelectionWithPair,
                info = stringResource(R.string.typing_wrap_selection_info),
                default = SettingsDefaults.textEditing.wrapSelectionWithPair,
            ) { scope.launch { repository.setWrapSelectionWithPair(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_shift_recase_title,
                stringResource(R.string.typing_shift_recase_subtitle),
                settings.textEditing.recapitalizeSelectionWithShift,
                info = stringResource(R.string.typing_shift_recase_info),
                default = SettingsDefaults.textEditing.recapitalizeSelectionWithShift,
            ) { scope.launch { repository.setRecapitalizeSelectionWithShift(it) } }
        }
    }
}

@Composable
internal fun TypingSuggestionsSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onOpenDictionary: () -> Unit,
    onOpenCustomDictionaries: () -> Unit,
    onOpenBlacklist: () -> Unit,
    onOpenAutopilot: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    SettingsGroup(stringResource(R.string.typing_group_suggestions_title)) {
        item {
            ToggleSetting(
                R.string.typing_suggestions_title,
                stringResource(R.string.typing_suggestions_subtitle),
                settings.suggestions,
                info = stringResource(R.string.typing_suggestions_info),
                default = SettingsDefaults.suggestions,
            ) { scope.launch { repository.setSuggestions(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_punctuation_suggestions_title,
                stringResource(R.string.typing_punctuation_suggestions_subtitle),
                settings.suggestionStrip.punctuation,
                info = stringResource(R.string.typing_punctuation_suggestions_info),
                default = SettingsDefaults.suggestionStrip.punctuation,
            ) { scope.launch { repository.setPunctuationSuggestions(it) } }
        }
        if (settings.suggestionStrip.punctuation) {
            item {
                TextFieldSetting(
                    label = stringResource(R.string.typing_punctuation_marks_title),
                    value = settings.suggestionStrip.punctuationChips,
                    hint = stringResource(R.string.typing_punctuation_marks_hint),
                    default = SettingsDefaults.suggestionStrip.punctuationChips,
                ) { repository.setPunctuationChips(it) }
            }
        }
        item {
            SliderSetting(
                R.string.typing_suggestion_slots_title,
                subtitle = stringResource(R.string.typing_suggestion_slots_subtitle),
                value = settings.suggestionStrip.slotCount.toFloat(),
                range = 2f..6f,
                display = { it.toInt().toString() },
                info = stringResource(R.string.typing_suggestion_slots_info),
                default = SettingsDefaults.suggestionStrip.slotCount.toFloat(),
            ) { scope.launch { repository.setSuggestionSlotCount(it.toInt()) } }
        }
        item {
            ToggleSetting(
                R.string.typing_suggestion_scroll_title,
                stringResource(R.string.typing_suggestion_scroll_subtitle),
                settings.suggestionStrip.scrollable,
                info = stringResource(R.string.typing_suggestion_scroll_info),
                default = SettingsDefaults.suggestionStrip.scrollable,
            ) { scope.launch { repository.setSuggestionScrollable(it) } }
        }
        item {
            val once = stringResource(R.string.typing_learn_threshold_once)
            SliderSetting(
                R.string.typing_learn_threshold_title,
                subtitle = stringResource(R.string.typing_learn_threshold_subtitle),
                value = settings.suggestionStrip.learnedWordMinCount.toFloat(),
                range = 1f..5f,
                display = { if (it.toInt() <= 1) once else it.toInt().toString() },
                info = stringResource(R.string.typing_learn_threshold_info),
                default = SettingsDefaults.suggestionStrip.learnedWordMinCount.toFloat(),
            ) { scope.launch { repository.setLearnedWordMinCount(it.toInt()) } }
        }
        item {
            ToggleSetting(
                R.string.typing_offer_near_miss_title,
                stringResource(R.string.typing_offer_near_miss_subtitle),
                settings.suggestionStrip.offerNearMissCorrections,
                info = stringResource(R.string.typing_offer_near_miss_info),
                default = SettingsDefaults.suggestionStrip.offerNearMissCorrections,
            ) { scope.launch { repository.setOfferNearMissCorrections(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_ask_before_learning_title,
                stringResource(R.string.typing_ask_before_learning_subtitle),
                settings.suggestionStrip.askBeforeLearning,
                info = stringResource(R.string.typing_ask_before_learning_info),
                default = SettingsDefaults.suggestionStrip.askBeforeLearning,
            ) { scope.launch { repository.setAskBeforeLearning(it) } }
        }
        if (!settings.suggestionStrip.askBeforeLearning) {
            item {
                val immediately = stringResource(R.string.typing_new_word_sightings_once)
                SliderSetting(
                    R.string.typing_new_word_sightings_title,
                    subtitle = stringResource(R.string.typing_new_word_sightings_subtitle),
                    value = settings.suggestionStrip.newWordSightings.toFloat(),
                    range = 1f..10f,
                    display = { if (it.toInt() <= 1) immediately else it.toInt().toString() },
                    info = stringResource(R.string.typing_new_word_sightings_info),
                    default = SettingsDefaults.suggestionStrip.newWordSightings.toFloat(),
                ) { scope.launch { repository.setNewWordSightings(it.toInt()) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_suggestions_all_fields_title,
                stringResource(R.string.typing_suggestions_all_fields_subtitle),
                settings.showSuggestionsInAllFields,
                info = stringResource(R.string.typing_suggestions_all_fields_info),
                default = SettingsDefaults.showSuggestionsInAllFields,
            ) { scope.launch { repository.setShowSuggestionsInAllFields(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_suggestions_first_title,
                stringResource(R.string.typing_suggestions_first_subtitle),
                settings.suggestionStrip.suggestionsFirst,
                info = stringResource(R.string.typing_suggestions_first_info),
                default = SettingsDefaults.suggestionStrip.suggestionsFirst,
            ) { scope.launch { repository.setSuggestionsFirst(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_primary_center_title,
                stringResource(R.string.typing_primary_center_subtitle),
                settings.suggestionStrip.suggestionPrimaryCenter,
                info = stringResource(R.string.typing_primary_center_info),
                default = SettingsDefaults.suggestionStrip.suggestionPrimaryCenter,
            ) { scope.launch { repository.setSuggestionPrimaryCenter(it) } }
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
                settings.contactSuggestions,
                info = stringResource(R.string.typing_contact_names_info),
                default = SettingsDefaults.contactSuggestions,
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
                settings.contactEmailSuggestions,
                info = stringResource(R.string.typing_contact_emails_info),
                default = SettingsDefaults.contactEmailSuggestions,
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
        if (settings.contactEmailSuggestions) {
            item {
                ToggleSetting(
                    R.string.typing_contact_emails_in_email_fields_title,
                    stringResource(R.string.typing_contact_emails_in_email_fields_subtitle),
                    settings.contactEmailSuggestionsInEmailFields,
                    info = stringResource(R.string.typing_contact_emails_in_email_fields_info),
                    default = SettingsDefaults.contactEmailSuggestionsInEmailFields,
                ) { scope.launch { repository.setContactEmailSuggestionsInEmailFields(it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_app_names_title,
                stringResource(R.string.typing_app_names_subtitle),
                settings.appNameSuggestions,
                info = stringResource(R.string.typing_app_names_info),
                default = SettingsDefaults.appNameSuggestions,
            ) { scope.launch { repository.setAppNameSuggestions(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_inline_emoji_search_title,
                stringResource(R.string.typing_inline_emoji_search_subtitle),
                settings.inlineEmojiSearch,
                info = stringResource(R.string.typing_inline_emoji_search_info),
                default = SettingsDefaults.inlineEmojiSearch,
            ) { scope.launch { repository.setInlineEmojiSearch(it) } }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            item {
                ToggleSetting(
                    R.string.typing_inline_autofill_title,
                    stringResource(R.string.typing_inline_autofill_subtitle),
                    settings.inlineAutofill,
                    info = stringResource(R.string.typing_inline_autofill_info),
                    default = SettingsDefaults.inlineAutofill,
                ) { scope.launch { repository.setInlineAutofill(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_replies_title,
                    stringResource(R.string.typing_smart_replies_subtitle),
                    settings.suggestionStrip.systemSmartReplies,
                    info = stringResource(R.string.typing_smart_replies_info),
                    default = SettingsDefaults.suggestionStrip.systemSmartReplies,
                ) { scope.launch { repository.setSystemSmartReplies(it) } }
            }
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
            val count = settings.suggestionBlacklist.size
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
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    SettingsGroup {
        item {
            ToggleSetting(
                R.string.typing_smart_hit_detection_title,
                stringResource(R.string.typing_smart_hit_detection_subtitle),
                settings.layoutBehavior.smartHitDetection,
                info = stringResource(R.string.typing_smart_hit_detection_info),
                default = SettingsDefaults.layoutBehavior.smartHitDetection,
            ) { scope.launch { repository.setSmartHitDetection(it) } }
        }
        if (settings.layoutBehavior.smartHitDetection) {
            item {
                SliderSetting(
                    R.string.typing_autopilot_strength_title,
                    subtitle = stringResource(R.string.typing_autopilot_strength_subtitle),
                    value = settings.layoutBehavior.autopilotStrength.toFloat(),
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
                    settings.layoutBehavior.autopilotShowEffect,
                    info = stringResource(R.string.typing_autopilot_show_info),
                    default = SettingsDefaults.layoutBehavior.autopilotShowEffect,
                ) { scope.launch { repository.setAutopilotShowEffect(it) } }
            }
            if (settings.layoutBehavior.autopilotShowEffect) {
                item {
                    val valueFormat = stringResource(R.string.typing_value_multiplier_prefix)
                    SliderSetting(
                        R.string.typing_autopilot_size_title,
                        subtitle = stringResource(R.string.typing_autopilot_size_subtitle),
                        value = settings.layoutBehavior.autopilotVisualScale,
                        range = 1f..3f,
                        display = { valueFormat.format("%.1f".format(it)) },
                        info = stringResource(R.string.typing_autopilot_size_info),
                        default = SettingsDefaults.layoutBehavior.autopilotVisualScale,
                    ) { scope.launch { repository.setAutopilotVisualScale(it) } }
                }
            }
            item {
                ToggleSetting(
                    R.string.typing_autopilot_outline_title,
                    stringResource(R.string.typing_autopilot_outline_subtitle),
                    settings.layoutBehavior.autopilotOutline,
                    info = stringResource(R.string.typing_autopilot_outline_info),
                    default = SettingsDefaults.layoutBehavior.autopilotOutline,
                ) { scope.launch { repository.setAutopilotOutline(it) } }
            }
        }
    }
}

@Composable
internal fun TypingSmartChipsSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    SettingsGroup(stringResource(R.string.typing_group_smart_chips_title)) {
        item {
            ToggleSetting(
                R.string.typing_smart_chips_title,
                stringResource(R.string.typing_smart_chips_subtitle),
                settings.smartSuggestions,
                info = stringResource(R.string.typing_smart_chips_info),
                default = SettingsDefaults.smartSuggestions,
            ) { scope.launch { repository.setSmartSuggestions(it) } }
        }
        if (settings.smartSuggestions) {
            item {
                ToggleSetting(
                    R.string.typing_smart_calc_title,
                    stringResource(R.string.typing_smart_calc_subtitle),
                    settings.smartCalc,
                    default = SettingsDefaults.smartCalc,
                ) { scope.launch { repository.setSmartCalc(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_currency_title,
                    stringResource(R.string.typing_smart_currency_subtitle, settings.currencyTo),
                    settings.smartCurrency,
                    default = SettingsDefaults.smartCurrency,
                ) { scope.launch { repository.setSmartCurrency(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_units_title,
                    stringResource(R.string.typing_smart_units_subtitle),
                    settings.smartUnits,
                    default = SettingsDefaults.smartUnits,
                ) { scope.launch { repository.setSmartUnits(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_tool_keywords_title,
                    stringResource(R.string.typing_smart_tool_keywords_subtitle),
                    settings.smartToolKeywords,
                    info = stringResource(R.string.typing_smart_tool_keywords_info),
                    default = SettingsDefaults.smartToolKeywords,
                ) { scope.launch { repository.setSmartToolKeywords(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_dates_title,
                    stringResource(R.string.typing_smart_dates_subtitle),
                    settings.smartChips.dates,
                    default = SettingsDefaults.smartChips.dates,
                ) { scope.launch { repository.setSmartChipDates(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_weather_title,
                    stringResource(R.string.typing_smart_weather_subtitle),
                    settings.smartChips.weather,
                    default = SettingsDefaults.smartChips.weather,
                ) { scope.launch { repository.setSmartChipWeather(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_lookups_title,
                    stringResource(R.string.typing_smart_lookups_subtitle),
                    settings.smartChips.lookups,
                    default = SettingsDefaults.smartChips.lookups,
                ) { scope.launch { repository.setSmartChipLookups(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_intents_title,
                    stringResource(R.string.typing_smart_intents_subtitle),
                    settings.smartChips.intents,
                    default = SettingsDefaults.smartChips.intents,
                ) { scope.launch { repository.setSmartChipIntents(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_gifs_title,
                    stringResource(R.string.typing_smart_gifs_subtitle),
                    settings.smartChips.gifs,
                    default = SettingsDefaults.smartChips.gifs,
                ) { scope.launch { repository.setSmartChipGifs(it) } }
            }
        }
    }
}

@Composable
internal fun TypingCodesSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val notificationCodesGranted = rememberGrantState(::hasNotificationAccess)
    val minutesFormat = stringResource(R.string.values_minutes)
    SettingsGroup(stringResource(R.string.typing_group_otp_title)) {
        item {
            val accessContext = LocalContext.current
            val codesAccess = rememberDisclosedSpecialAccess(SpecialAccess.NOTIFICATION_CODES)
            ToggleSetting(
                R.string.typing_otp_chip_title,
                stringResource(R.string.typing_otp_chip_subtitle),
                settings.otp.enabled,
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
        if (settings.otp.enabled) {
            if (!notificationCodesGranted) {
                item {
                    val codesAccessRow =
                        rememberDisclosedSpecialAccess(SpecialAccess.NOTIFICATION_CODES)
                    NavRow(
                        R.string.typing_otp_access_title,
                        stringResource(R.string.typing_otp_access_subtitle),
                    ) { codesAccessRow() }
                }
            }
            item {
                ToggleSetting(
                    R.string.typing_otp_number_fields_title,
                    stringResource(R.string.typing_otp_number_fields_subtitle),
                    settings.otp.numberFieldsOnly,
                    info = stringResource(R.string.typing_otp_number_fields_info),
                    default = SettingsDefaults.otp.numberFieldsOnly,
                ) { scope.launch { repository.setOtpNumberFieldsOnly(it) } }
            }
            item {
                SliderSetting(
                    R.string.typing_otp_expiry_title,
                    subtitle = stringResource(R.string.typing_otp_expiry_subtitle),
                    value = settings.otp.expiryMinutes.toFloat(),
                    range = 1f..10f,
                    display = { minutesFormat.format(it.toInt()) },
                    default = SettingsDefaults.otp.expiryMinutes.toFloat(),
                ) { scope.launch { repository.setOtpExpiryMinutes(it.toInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_otp_dismiss_title,
                    stringResource(R.string.typing_otp_dismiss_subtitle),
                    settings.otp.dismissNotification,
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
                settings.otp.perDigitEntry,
                info = stringResource(R.string.typing_otp_per_digit_info),
                default = SettingsDefaults.otp.perDigitEntry,
            ) { scope.launch { repository.setOtpPerDigitEntry(it) } }
        }
    }
}

@Composable
internal fun TypingGesturesSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    SettingsGroup(stringResource(R.string.typing_group_glide_title)) {
        item {
            ToggleSetting(
                R.string.typing_glide_typing_title,
                stringResource(R.string.typing_glide_typing_subtitle),
                settings.gestureTyping,
                info = stringResource(R.string.typing_glide_typing_info),
                default = SettingsDefaults.gestureTyping,
            ) { scope.launch { repository.setGestureTyping(it) } }
        }
        // What a letter swipe does — glide a word or handwrite it. Full builds
        // only (needs the ML Kit handwriting model), and only relevant once
        // letter swipes are switched on above.
        if (BuildConfig.ENABLE_ML_KIT_HANDWRITING && settings.gestureTyping) {
            item {
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
                    selected = settings.letterSwipeAction,
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
        }
        if (settings.gestureTyping) {
            // Glide-word only: crossing the spacebar to chain words has no
            // meaning when a swipe draws handwriting instead.
            if (settings.letterSwipeAction == LetterSwipeAction.TYPE_WORDS) {
                item {
                    ToggleSetting(
                        R.string.typing_space_glide_multiword_title,
                        stringResource(R.string.typing_space_glide_multiword_subtitle),
                        settings.gesture.spaceGlideMultiWord,
                        info = stringResource(R.string.typing_space_glide_multiword_info),
                        default = SettingsDefaults.gesture.spaceGlideMultiWord,
                    ) { scope.launch { repository.setGestureSpaceMultiWord(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.typing_glide_picker_title,
                        stringResource(R.string.typing_glide_picker_subtitle),
                        settings.gesture.ambiguityPicker,
                        info = stringResource(R.string.typing_glide_picker_info),
                        default = SettingsDefaults.gesture.ambiguityPicker,
                    ) { scope.launch { repository.setGestureAmbiguityPicker(it) } }
                }
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
                        selected = settings.gesture.vocabulary,
                        onChange = { scope.launch { repository.setGestureVocabulary(it) } },
                        default = SettingsDefaults.gesture.vocabulary,
                    )
                }
                item {
                    ToggleSetting(
                        R.string.typing_space_after_glide_title,
                        stringResource(R.string.typing_space_after_glide_subtitle),
                        settings.gesture.autoSpaceAfterGlide,
                        info = stringResource(R.string.typing_space_after_glide_info),
                        default = SettingsDefaults.gesture.autoSpaceAfterGlide,
                    ) { scope.launch { repository.setGestureAutoSpace(it) } }
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
                        selected = settings.gesture.apostropheKey,
                        onChange = { scope.launch { repository.setGestureApostropheKey(it) } },
                        default = SettingsDefaults.gesture.apostropheKey,
                        detail = { key -> ChoiceDetail(stringResource(glideApostropheDescRes(key))) },
                    )
                }
                // The possessive flick hangs off that key, and the spacebar
                // cannot be its starting point, so the row appears only for the
                // three choices it can actually work from.
                if (settings.gesture.apostropheKey != GlideApostropheKey.OFF &&
                    settings.gesture.apostropheKey != GlideApostropheKey.SPACE
                ) {
                    item {
                        ToggleSetting(
                            R.string.typing_glide_apostrophe_s_title,
                            stringResource(R.string.typing_glide_apostrophe_s_subtitle),
                            settings.gesture.apostropheS,
                            info = stringResource(R.string.typing_glide_apostrophe_s_info),
                            default = SettingsDefaults.gesture.apostropheS,
                        ) { scope.launch { repository.setGestureApostropheS(it) } }
                    }
                }
            }
            item {
                val valueFormat = stringResource(R.string.typing_value_multiplier_suffix)
                SliderSetting(
                    R.string.typing_swipe_start_distance_title,
                    subtitle = stringResource(R.string.typing_swipe_start_distance_subtitle),
                    value = settings.gesture.startThresholdSlop,
                    range = 0.5f..4f,
                    display = { valueFormat.format("%.1f".format(it)) },
                    info = stringResource(R.string.typing_swipe_start_distance_info),
                    default = SettingsDefaults.gesture.startThresholdSlop,
                ) { scope.launch { repository.setGestureStartThresholdSlop(it) } }
            }
            // Glide-word only: the guard raises the swipe-start bar, which never
            // runs in handwrite mode (there is no word glide to suppress).
            if (settings.letterSwipeAction == LetterSwipeAction.TYPE_WORDS) {
                item {
                    val offLabel = stringResource(CommonR.string.common_off)
                    val msFormat = stringResource(R.string.typing_value_milliseconds)
                    SliderSetting(
                        R.string.typing_gesture_cooldown_title,
                        subtitle = stringResource(R.string.typing_gesture_cooldown_subtitle),
                        value = settings.gesture.postTypeCooldownMs.toFloat(),
                        range = 0f..500f,
                        display = { if (it.roundToInt() == 0) offLabel else msFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.typing_gesture_cooldown_info),
                        default = SettingsDefaults.gesture.postTypeCooldownMs.toFloat(),
                    ) { scope.launch { repository.setGesturePostTypeCooldownMs(it.roundToInt()) } }
                }
            }
            // Handwrite-with-swipes only: window after a drawn stroke in which a
            // tap is grabbed as an ink dot rather than typing.
            if (BuildConfig.ENABLE_ML_KIT_HANDWRITING &&
                settings.letterSwipeAction == LetterSwipeAction.HANDWRITE
            ) {
                item {
                    val offLabel = stringResource(CommonR.string.common_off)
                    val msFormat = stringResource(R.string.typing_value_milliseconds)
                    SliderSetting(
                        R.string.typing_handwrite_dot_title,
                        subtitle = stringResource(R.string.typing_handwrite_dot_subtitle),
                        value = settings.gesture.handwriteDotCooldownMs.toFloat(),
                        range = 0f..1500f,
                        display = { if (it.roundToInt() == 0) offLabel else msFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.typing_handwrite_dot_info),
                        default = SettingsDefaults.gesture.handwriteDotCooldownMs.toFloat(),
                    ) { scope.launch { repository.setGestureHandwriteDotCooldownMs(it.roundToInt()) } }
                }
            }
        }
    }
    SettingsGroup(stringResource(R.string.typing_group_glide_trail_title)) {
        if (settings.gestureTyping) {
            item {
                val dpFormat = stringResource(R.string.typing_value_dp)
                SliderSetting(
                    R.string.typing_trail_width_title,
                    subtitle = stringResource(R.string.typing_trail_width_subtitle),
                    value = settings.gesture.trailWidthDp,
                    range = 2f..24f,
                    display = { dpFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.gesture.trailWidthDp,
                ) { scope.launch { repository.setGestureTrailWidthDp(it) } }
            }
            item {
                val msFormat = stringResource(R.string.typing_value_milliseconds)
                SliderSetting(
                    R.string.typing_trail_length_title,
                    subtitle = stringResource(R.string.typing_trail_length_subtitle),
                    value = settings.gesture.trailDurationMs.toFloat(),
                    range = 100f..1200f,
                    display = { msFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.gesture.trailDurationMs.toFloat(),
                ) { scope.launch { repository.setGestureTrailDurationMs(it.roundToInt()) } }
            }
            item {
                val percentFormat = stringResource(R.string.typing_value_percent)
                SliderSetting(
                    R.string.typing_trail_opacity_title,
                    value = settings.gesture.trailOpacity,
                    // Down to zero, which is the only way to glide with no
                    // trail at all. It used to floor at 0.1, so the one way to
                    // turn the trail off was power saving mode, which changes
                    // a dozen other things with it.
                    range = 0f..1f,
                    display = { percentFormat.format((it * 100).roundToInt()) },
                    default = SettingsDefaults.gesture.trailOpacity,
                ) { scope.launch { repository.setGestureTrailOpacity(it) } }
            }
            // The pill that rides above the finger with the word the stroke has
            // decoded to so far. Glide-word only: handwriting draws ink and
            // recognizes on lift, so there is no running word to float.
            if (settings.letterSwipeAction == LetterSwipeAction.TYPE_WORDS) {
                item {
                    ToggleSetting(
                        R.string.typing_glide_preview_title,
                        stringResource(R.string.typing_glide_preview_subtitle),
                        settings.gesture.wordPreview,
                        info = stringResource(R.string.typing_glide_preview_info),
                        default = SettingsDefaults.gesture.wordPreview,
                    ) { scope.launch { repository.setGestureWordPreview(it) } }
                }
                if (settings.gesture.wordPreview) {
                    item {
                        val dpFormat = stringResource(R.string.typing_value_dp)
                        SliderSetting(
                            R.string.typing_glide_preview_height_title,
                            subtitle = stringResource(R.string.typing_glide_preview_height_subtitle),
                            value = settings.gesture.wordPreviewOffsetYDp.toFloat(),
                            range = 0f..160f,
                            display = { dpFormat.format(it.roundToInt()) },
                            info = stringResource(R.string.typing_glide_preview_height_info),
                            default = SettingsDefaults.gesture.wordPreviewOffsetYDp.toFloat(),
                        ) { scope.launch { repository.setGestureWordPreviewOffsetYDp(it.roundToInt()) } }
                    }
                    item {
                        val dpFormat = stringResource(R.string.typing_value_dp)
                        SliderSetting(
                            R.string.typing_glide_preview_shift_title,
                            subtitle = stringResource(R.string.typing_glide_preview_shift_subtitle),
                            value = settings.gesture.wordPreviewOffsetXDp.toFloat(),
                            range = -80f..80f,
                            display = { dpFormat.format(it.roundToInt()) },
                            info = stringResource(R.string.typing_glide_preview_shift_info),
                            default = SettingsDefaults.gesture.wordPreviewOffsetXDp.toFloat(),
                        ) { scope.launch { repository.setGestureWordPreviewOffsetXDp(it.roundToInt()) } }
                    }
                    item {
                        val spFormat = stringResource(R.string.values_sp)
                        SliderSetting(
                            R.string.typing_glide_preview_size_title,
                            subtitle = stringResource(R.string.typing_glide_preview_size_subtitle),
                            value = settings.gesture.wordPreviewFontSp.toFloat(),
                            range = 12f..32f,
                            display = { spFormat.format(it.roundToInt()) },
                            info = stringResource(R.string.typing_glide_preview_size_info),
                            default = SettingsDefaults.gesture.wordPreviewFontSp.toFloat(),
                        ) { scope.launch { repository.setGestureWordPreviewFontSp(it.roundToInt()) } }
                    }
                    item {
                        ColorSetting(
                            R.string.typing_glide_preview_color_title,
                            subtitle = stringResource(R.string.typing_glide_preview_color_subtitle),
                            color = settings.gesture.wordPreviewBackground,
                            fallback = MaterialTheme.colorScheme.surfaceVariant.argbLong(),
                            info = stringResource(R.string.typing_glide_preview_color_info),
                        ) { scope.launch { repository.setGestureWordPreviewBackground(it) } }
                    }
                    item {
                        ColorSetting(
                            R.string.typing_glide_preview_text_color_title,
                            subtitle = stringResource(R.string.typing_glide_preview_text_color_subtitle),
                            color = settings.gesture.wordPreviewTextColor,
                            fallback = MaterialTheme.colorScheme.onSurfaceVariant.argbLong(),
                            info = stringResource(R.string.typing_glide_preview_text_color_info),
                        ) { scope.launch { repository.setGestureWordPreviewTextColor(it) } }
                    }
                }
            }
        }
    }
    SettingsGroup(stringResource(R.string.typing_group_spacebar_title)) {
        item {
            SpaceSwipeSetting(
                title = stringResource(R.string.typing_space_short_swipe_title),
                subtitle = stringResource(R.string.typing_space_short_swipe_subtitle),
                info = stringResource(R.string.typing_space_short_swipe_info),
                value = settings.spaceShortSwipe,
                default = SettingsDefaults.spaceShortSwipe,
            ) { scope.launch { repository.setSpaceShortSwipe(it) } }
        }
        item {
            SpaceSwipeSetting(
                title = stringResource(R.string.typing_space_long_swipe_title),
                subtitle = stringResource(R.string.typing_space_long_swipe_subtitle),
                info = stringResource(R.string.typing_space_long_swipe_info),
                value = settings.spaceLongSwipe,
                default = SettingsDefaults.spaceLongSwipe,
            ) { scope.launch { repository.setSpaceLongSwipe(it) } }
        }
        // 2-D cursor pad only makes sense once a slide is set to cursor control.
        if (settings.spaceShortSwipe == SpaceSwipeAction.CURSOR ||
            settings.spaceLongSwipe == SpaceSwipeAction.CURSOR
        ) {
            item {
                ToggleSetting(
                    R.string.typing_space_cursor_2d_title,
                    stringResource(R.string.typing_space_cursor_2d_subtitle),
                    settings.layoutBehavior.spaceCursor2d,
                    info = stringResource(R.string.typing_space_cursor_2d_info),
                    default = SettingsDefaults.layoutBehavior.spaceCursor2d,
                ) { scope.launch { repository.setSpaceCursor2d(it) } }
            }
            item {
                SliderSetting(
                    R.string.typing_space_cursor_step_title,
                    subtitle = stringResource(R.string.typing_space_cursor_step_subtitle),
                    value = settings.textEditing.spaceCursorStepDp.toFloat(),
                    range = 8f..32f,
                    display = { context.getString(R.string.typing_value_dp, it.toInt()) },
                    info = stringResource(R.string.typing_space_cursor_step_info),
                    default = SettingsDefaults.textEditing.spaceCursorStepDp.toFloat(),
                ) { scope.launch { repository.setSpaceCursorStepDp(it.toInt()) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_space_swipe_down_hide_title,
                stringResource(R.string.typing_space_swipe_down_hide_subtitle),
                settings.layoutBehavior.spaceSwipeDownHide,
                info = stringResource(R.string.typing_space_swipe_down_hide_info),
                default = SettingsDefaults.layoutBehavior.spaceSwipeDownHide,
            ) { scope.launch { repository.setSpaceSwipeDownHide(it) } }
        }
        item {
            // Issue #57: the characters the spacebar's long press offers, space
            // separated. Blank gives the hold back to the language picker.
            TextFieldSetting(
                label = stringResource(R.string.typing_space_hold_keys_label),
                value = settings.layoutBehavior.spaceHoldKeys.joinToString(" "),
                hint = stringResource(R.string.typing_space_hold_keys_hint),
                default = SettingsDefaults.layoutBehavior.spaceHoldKeys.joinToString(" "),
            ) { text ->
                repository.setSpaceHoldKeys(text.split(" ").filter { it.isNotBlank() })
            }
        }
        if (settings.spaceShortSwipe == SpaceSwipeAction.LANGUAGE ||
            settings.spaceLongSwipe == SpaceSwipeAction.LANGUAGE
        ) {
            item {
                ToggleSetting(
                    R.string.typing_spacebar_language_arrows_title,
                    stringResource(R.string.typing_spacebar_language_arrows_subtitle),
                    settings.spacebarLanguageArrows,
                    info = stringResource(R.string.typing_spacebar_language_arrows_info),
                    default = SettingsDefaults.spacebarLanguageArrows,
                ) { scope.launch { repository.setSpacebarLanguageArrows(it) } }
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
                selected = settings.layoutBehavior.spacebarDisplay,
                default = SettingsDefaults.layoutBehavior.spacebarDisplay,
            ) { scope.launch { repository.setSpacebarDisplay(it) } }
        }
        item {
            TextFieldSetting(
                label = stringResource(R.string.typing_spacebar_text_label),
                value = settings.spacebarLabel,
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
    settings: KeyboardSettings,
    onOpenHardwareShortcuts: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    SettingsGroup(stringResource(R.string.typing_group_hardware_title)) {
        item {
            ToggleSetting(
                R.string.typing_hardware_input_title,
                stringResource(R.string.typing_hardware_input_subtitle),
                settings.hardwareKeyboardInput,
                info = stringResource(R.string.typing_hardware_input_info),
                default = SettingsDefaults.hardwareKeyboardInput,
            ) { scope.launch { repository.setHardwareKeyboardInput(it) } }
        }
        val hw = settings.hardwareKeyboard
        item {
            ToggleSetting(
                R.string.typing_hw_shortcuts_title,
                stringResource(R.string.typing_hw_shortcuts_subtitle),
                hw.shortcutsEnabled,
                info = stringResource(R.string.typing_hw_shortcuts_info),
                default = SettingsDefaults.hardwareKeyboard.shortcutsEnabled,
            ) { scope.launch { repository.setHwShortcutsEnabled(it) } }
        }
        if (hw.shortcutsEnabled) {
            item {
                // A chord spells itself, so it arrives with no template around it.
                val leaderParts = leaderLabel(parseLeader(hw.leader) ?: DefaultLeader)
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
            item {
                ToggleSetting(
                    R.string.typing_hw_digit_chord_title,
                    stringResource(R.string.typing_hw_digit_chord_subtitle),
                    hw.toolbarDigitChord,
                    info = stringResource(R.string.typing_hw_digit_chord_info),
                    default = SettingsDefaults.hardwareKeyboard.toolbarDigitChord,
                ) { scope.launch { repository.setHwToolbarDigitChord(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_hw_modifier_words_title,
                    stringResource(R.string.typing_hw_modifier_words_subtitle),
                    hw.hintModifierWords,
                    info = stringResource(R.string.typing_hw_modifier_words_info),
                    default = SettingsDefaults.hardwareKeyboard.hintModifierWords,
                ) { scope.launch { repository.setHwHintModifierWords(it) } }
            }
            item {
                // The readout tracks the live thumb, so its format string is
                // resolved out here: the display lambda is not composable.
                val secondsFormat = stringResource(R.string.typing_hw_picker_timeout_value)
                SliderSetting(
                    R.string.typing_hw_picker_timeout_title,
                    subtitle = stringResource(R.string.typing_hw_picker_timeout_subtitle),
                    value = hw.pickerTimeoutMs.toFloat(),
                    range = PickerTimeoutRange.first.toFloat()..PickerTimeoutRange.last.toFloat(),
                    display = { secondsFormat.format("%.1f".format(it / 1000f)) },
                    info = stringResource(R.string.typing_hw_picker_timeout_info),
                    default = SettingsDefaults.hardwareKeyboard.pickerTimeoutMs.toFloat(),
                ) { scope.launch { repository.setHwPickerTimeoutMs(it.toInt()) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_panel_nav_title,
                stringResource(R.string.typing_hw_panel_nav_subtitle),
                hw.panelNavigation,
                info = stringResource(R.string.typing_hw_panel_nav_info),
                default = SettingsDefaults.hardwareKeyboard.panelNavigation,
            ) { scope.launch { repository.setHwPanelNavigation(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_esc_title,
                stringResource(R.string.typing_hw_esc_subtitle),
                hw.escClosesPanel,
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
                selected = hw.suggestionHotkeys,
                default = SettingsDefaults.hardwareKeyboard.suggestionHotkeys,
                detail = { mode -> ChoiceDetail(stringResource(suggestionHotkeyDescRes(mode))) },
            ) { scope.launch { repository.setHwSuggestionHotkeys(it) } }
        }
        if (hw.suggestionHotkeys == SuggestionHotkeyMode.ALT_DIGIT) {
            item {
                ToggleSetting(
                    R.string.typing_hw_suggestion_hints_title,
                    stringResource(R.string.typing_hw_suggestion_hints_subtitle),
                    hw.suggestionHintsAlways,
                    info = stringResource(R.string.typing_hw_suggestion_hints_info),
                    default = SettingsDefaults.hardwareKeyboard.suggestionHintsAlways,
                ) { scope.launch { repository.setHwSuggestionHintsAlways(it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_mac_title,
                stringResource(R.string.typing_hw_mac_subtitle),
                hw.macShortcuts,
                info = stringResource(R.string.typing_hw_mac_info),
                default = SettingsDefaults.hardwareKeyboard.macShortcuts,
            ) { scope.launch { repository.setHwMacShortcuts(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_lang_chord_title,
                stringResource(R.string.typing_hw_lang_chord_subtitle),
                hw.languageSwitchChord,
                info = stringResource(R.string.typing_hw_lang_chord_info),
                default = SettingsDefaults.hardwareKeyboard.languageSwitchChord,
            ) { scope.launch { repository.setHwLanguageSwitchChord(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_auto_show_title,
                stringResource(R.string.typing_hw_auto_show_subtitle),
                hw.autoShowUi,
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
internal fun HardwareShortcutsSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val hw = settings.hardwareKeyboard
    val leader = parseLeader(hw.leader) ?: DefaultLeader
    var editingLeader by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ToolbarTool?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    val tools = remember(hw.toolByLetter, settings.enabledTools) {
        val letterOf = hw.toolByLetter.entries.associate { (letter, tool) -> tool to letter }
        // Bound tools first, in letter order, so the table reads as "what my
        // keyboard does" before "what else could be bound".
        ToolbarTool.entries.filter(::isSupportedTool)
            .sortedWith(compareBy({ letterOf[it] == null }, { letterOf[it] ?: ' ' }, { it.name }))
    }
    val letterOf = hw.toolByLetter.entries.associate { (letter, tool) -> tool to letter }
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
                    leaderTitle,
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
            takenBy = { letter -> hw.toolByLetter[letter] },
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
private enum class DoubleSpaceAction(@StringRes val labelRes: Int, @StringRes val descRes: Int) {
    NONE(R.string.typing_double_space_none_label, R.string.typing_double_space_none_desc),
    PERIOD(R.string.typing_double_space_period_label, R.string.typing_double_space_period_desc),
    TAB(R.string.typing_double_space_tab_label, R.string.typing_double_space_tab_desc),
}

private val DefaultDoubleSpace = when {
    SettingsDefaults.doubleSpaceTab -> DoubleSpaceAction.TAB
    SettingsDefaults.doubleSpacePeriod -> DoubleSpaceAction.PERIOD
    else -> DoubleSpaceAction.NONE
}

/** One tool in the hardware-shortcut list: its icon, the letter it holds, and the way to clear it. */
@Composable
private fun HardwareShortcutRow(
    tool: ToolbarTool,
    letter: Char?,
    settings: KeyboardSettings,
    repository: SettingsRepository,
    onEdit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
            WmRow(
                title = stringResource(toolTitle(tool)),
                leading = {
                    SlotIcon(IconSlots.forTool(tool), contentDescription = null)
                },
                // A tool with no API key is off as far as the keyboard
                // is concerned, whatever the Tools screen last stored.
                subtitle = if (tool !in settings.enabledTools || !isUsableTool(tool, settings)) {
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

/** Which key carries the apostrophe in a glide, and what it costs, for the sheet. */
private fun glideApostropheDescRes(key: GlideApostropheKey): Int = when (key) {
    GlideApostropheKey.OFF -> R.string.typing_glide_apostrophe_off_desc
    GlideApostropheKey.COMMA -> R.string.typing_glide_apostrophe_comma_desc
    GlideApostropheKey.PERIOD -> R.string.typing_glide_apostrophe_period_desc
    GlideApostropheKey.SPACE -> R.string.typing_glide_apostrophe_space_desc
    GlideApostropheKey.APOSTROPHE -> R.string.typing_glide_apostrophe_key_desc
}

/** What a number key does on a physical keyboard, for the sheet. */
private fun suggestionHotkeyDescRes(mode: SuggestionHotkeyMode): Int = when (mode) {
    SuggestionHotkeyMode.OFF -> R.string.typing_hw_suggestion_hotkeys_off_desc
    SuggestionHotkeyMode.LEADER_DIGIT -> R.string.typing_hw_suggestion_hotkeys_leader_desc
    SuggestionHotkeyMode.ALT_DIGIT -> R.string.typing_hw_suggestion_hotkeys_alt_desc
}
