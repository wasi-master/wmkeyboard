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

/**
 * A diagnostic in words. A string with no arguments is fetched rather than
 * formatted; one with arguments gets both slots filled, since a missing one is
 * never asked for.
 */
internal fun CodeDiagnostic.message(context: Context): String = when {
    note != null -> note.format(context.resources)
    messageRes == 0 -> ""
    arg1 == null && arg2 == null -> context.getString(messageRes)
    else -> context.getString(messageRes, arg1.orEmpty(), arg2.orEmpty())
}
