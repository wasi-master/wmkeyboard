package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R

/**
 * The sentence for each problem the Python tooling can find, by its code, from
 * strings_python_editor.xml. A code this build does not know (a library newer
 * than the app) falls back to a general sentence rather than to nothing.
 */
@StringRes
internal fun pyDiagnosticMessage(code: String): Int = PY_DIAGNOSTICS[code] ?: R.string.py_diag_unknown

/** Every code of native/wm-python-tooling's table; PythonDiagnosticCodesTest keeps the two in step. */
internal val PY_DIAGNOSTICS: Map<String, Int> = mapOf(
    "unknown_character" to R.string.py_diag_unknown_character,
    "unclosed_string" to R.string.py_diag_unclosed_string,
    "unclosed_triple_string" to R.string.py_diag_unclosed_triple_string,
    "bad_number" to R.string.py_diag_bad_number,
    "unclosed_bracket" to R.string.py_diag_unclosed_bracket,
    "stray_closer" to R.string.py_diag_stray_closer,
    "wrong_closer" to R.string.py_diag_wrong_closer,
    "unexpected_indent" to R.string.py_diag_unexpected_indent,
    "inconsistent_dedent" to R.string.py_diag_inconsistent_dedent,
    "empty_block" to R.string.py_diag_empty_block,
    "mixed_indentation" to R.string.py_diag_mixed_indentation,
    "syntax_expected" to R.string.py_diag_syntax_expected,
    "syntax_at_end" to R.string.py_diag_syntax_at_end,
    "unsupported" to R.string.py_diag_unsupported,
    "will_not_run" to R.string.py_diag_will_not_run,
    "undefined_name" to R.string.py_diag_undefined_name,
    "name_typo" to R.string.py_diag_name_typo,
    "module_not_imported" to R.string.py_diag_module_not_imported,
    "removed_builtin" to R.string.py_diag_removed_builtin,
    "missing_builtin" to R.string.py_diag_missing_builtin,
    "import_not_available" to R.string.py_diag_import_not_available,
    "unused_local" to R.string.py_diag_unused_local,
    "unused_parameter" to R.string.py_diag_unused_parameter,
    "accidental_global" to R.string.py_diag_accidental_global,
    "replaces_api" to R.string.py_diag_replaces_api,
    "shadows_builtin" to R.string.py_diag_shadows_builtin,
    "unknown_member" to R.string.py_diag_unknown_member,
    "member_typo" to R.string.py_diag_member_typo,
    "use_instead" to R.string.py_diag_use_instead,
    "never_in_api" to R.string.py_diag_never_in_api,
    "storage_not_declared" to R.string.py_diag_storage_not_declared,
    "reminders_not_declared" to R.string.py_diag_reminders_not_declared,
    "selection_not_declared" to R.string.py_diag_selection_not_declared,
    "panel_only" to R.string.py_diag_panel_only,
    "missing_render" to R.string.py_diag_missing_render,
    "missing_transform" to R.string.py_diag_missing_transform,
    "render_parameters" to R.string.py_diag_render_parameters,
    "render_returns_nothing" to R.string.py_diag_render_returns_nothing,
    "on_event_signature" to R.string.py_diag_on_event_signature,
    "transform_signature" to R.string.py_diag_transform_signature,
    "unknown_event_type" to R.string.py_diag_unknown_event_type,
    "event_type_typo" to R.string.py_diag_event_type_typo,
    "unknown_event_id" to R.string.py_diag_unknown_event_id,
    "unknown_action" to R.string.py_diag_unknown_action,
    "action_typo" to R.string.py_diag_action_typo,
    "unknown_ui_argument" to R.string.py_diag_unknown_ui_argument,
    "ui_argument_typo" to R.string.py_diag_ui_argument_typo,
    "ui_missing_argument" to R.string.py_diag_ui_missing_argument,
    "too_many_ui_arguments" to R.string.py_diag_too_many_ui_arguments,
    "unknown_style" to R.string.py_diag_unknown_style,
    "style_typo" to R.string.py_diag_style_typo,
    "duplicate_id" to R.string.py_diag_duplicate_id,
    "too_many_tabs" to R.string.py_diag_too_many_tabs,
    "loop_never_ends" to R.string.py_diag_loop_never_ends,
    "unreachable" to R.string.py_diag_unreachable,
)

/** The parser's placeholders for what it expected, in the reader's words. */
private val PY_PLACEHOLDERS: Map<String, Int> = mapOf(
    "<expression>" to R.string.py_expected_expression,
    "<name>" to R.string.py_expected_name,
    "<statement>" to R.string.py_expected_statement,
    "<end of line>" to R.string.py_expected_end_of_line,
    "<less nesting>" to R.string.py_expected_less_nesting,
    "<shorter expression>" to R.string.py_expected_shorter_expression,
    "<shorter comparison>" to R.string.py_expected_shorter_comparison,
)

/** [arg] with a parser placeholder put into words. Anything else is code, kept as written. */
private fun wordsFor(context: Context, arg: String?): String =
    arg?.let { PY_PLACEHOLDERS[it]?.let(context::getString) ?: it }.orEmpty()

/**
 * A diagnostic in words. A string with no arguments is fetched rather than
 * formatted; one with arguments gets both slots filled, since a missing one is
 * never asked for.
 */
internal fun CodeDiagnostic.message(context: Context): String = when {
    note != null -> note.format(context.resources)
    messageRes == 0 -> ""
    arg1 == null && arg2 == null -> context.getString(messageRes)
    else -> context.getString(messageRes, wordsFor(context, arg1), wordsFor(context, arg2))
}
