package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.ui.text.TextRange
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.layout.json.JsonIssue
import com.wasimaster.wmkeyboard.core.layout.json.JsonIssueCode
import com.wasimaster.wmkeyboard.core.layout.json.JsonSeverity

/** The sentence for each problem the layout JSON checks can find, from strings_json_editor.xml. */
@get:StringRes
internal val JsonIssueCode.messageRes: Int
    get() = when (this) {
        JsonIssueCode.UNCLOSED_STRING -> R.string.json_diag_unclosed_string
        JsonIssueCode.BAD_ESCAPE -> R.string.json_diag_bad_escape
        JsonIssueCode.UNKNOWN_CHARACTER -> R.string.json_diag_unknown_character
        JsonIssueCode.UNQUOTED_WORD -> R.string.json_diag_unquoted_word
        JsonIssueCode.EXPECTED_VALUE -> R.string.json_diag_expected_value
        JsonIssueCode.EXPECTED_KEY -> R.string.json_diag_expected_key
        JsonIssueCode.EXPECTED_COLON -> R.string.json_diag_expected_colon
        JsonIssueCode.EXPECTED_COMMA -> R.string.json_diag_expected_comma
        JsonIssueCode.TRAILING_COMMA -> R.string.json_diag_trailing_comma
        JsonIssueCode.UNCLOSED_OBJECT -> R.string.json_diag_unclosed_object
        JsonIssueCode.UNCLOSED_ARRAY -> R.string.json_diag_unclosed_array
        JsonIssueCode.STRAY_CLOSER -> R.string.json_diag_stray_closer
        JsonIssueCode.TRAILING_CONTENT -> R.string.json_diag_trailing_content
        JsonIssueCode.UNKNOWN_PROPERTY -> R.string.json_diag_unknown_property
        JsonIssueCode.PROPERTY_TYPO -> R.string.json_diag_property_typo
        JsonIssueCode.DUPLICATE_PROPERTY -> R.string.json_diag_duplicate_property
        JsonIssueCode.MISSING_PROPERTY -> R.string.json_diag_missing_property
        JsonIssueCode.EXPECTED_TEXT -> R.string.json_diag_expected_text
        JsonIssueCode.EXPECTED_NUMBER -> R.string.json_diag_expected_number
        JsonIssueCode.EXPECTED_WHOLE_NUMBER -> R.string.json_diag_expected_whole_number
        JsonIssueCode.EXPECTED_BOOLEAN -> R.string.json_diag_expected_boolean
        JsonIssueCode.EXPECTED_LIST -> R.string.json_diag_expected_list
        JsonIssueCode.EXPECTED_OBJECT -> R.string.json_diag_expected_object
        JsonIssueCode.NULL_NOT_ALLOWED -> R.string.json_diag_null_not_allowed
        JsonIssueCode.NULL_BECOMES_DEFAULT -> R.string.json_diag_null_becomes_default
        JsonIssueCode.UNKNOWN_VALUE -> R.string.json_diag_unknown_value
        JsonIssueCode.VALUE_TYPO -> R.string.json_diag_value_typo
        JsonIssueCode.UNKNOWN_VALUE_REQUIRED -> R.string.json_diag_unknown_value_required
        JsonIssueCode.UNKNOWN_ACTION -> R.string.json_diag_unknown_action
        JsonIssueCode.ACTION_TYPO -> R.string.json_diag_action_typo
        JsonIssueCode.UNKNOWN_MAP_KEY -> R.string.json_diag_unknown_map_key
        JsonIssueCode.UNKNOWN_LAYER -> R.string.json_diag_unknown_layer
        JsonIssueCode.LAYER_TYPO -> R.string.json_diag_layer_typo
        JsonIssueCode.OUT_OF_RANGE -> R.string.json_diag_out_of_range
        JsonIssueCode.UNKNOWN_LANGUAGE -> R.string.json_diag_unknown_language
        JsonIssueCode.UNKNOWN_ICON -> R.string.json_diag_unknown_icon
        JsonIssueCode.UNKNOWN_THEME -> R.string.json_diag_unknown_theme
        JsonIssueCode.UNKNOWN_SECONDARY_LAYOUT -> R.string.json_diag_unknown_secondary_layout
        JsonIssueCode.UNKNOWN_RULE_SET -> R.string.json_diag_unknown_rule_set
        JsonIssueCode.FIELD_OUTSIDE_PANEL -> R.string.json_diag_field_outside_panel
        JsonIssueCode.SET_BY_KEYBOARD -> R.string.json_diag_set_by_keyboard
    }

internal fun JsonSeverity.toCodeSeverity(): CodeSeverity = when (this) {
    JsonSeverity.ERROR -> CodeSeverity.ERROR
    JsonSeverity.WARNING -> CodeSeverity.WARNING
    JsonSeverity.INFO -> CodeSeverity.INFO
}

/** A layout JSON problem as the code field draws it. */
internal fun JsonIssue.toCodeDiagnostic(): CodeDiagnostic =
    CodeDiagnostic(TextRange(start, end), severity.toCodeSeverity(), code.messageRes, arg1, arg2)
