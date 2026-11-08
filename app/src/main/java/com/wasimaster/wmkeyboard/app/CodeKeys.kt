package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState

/**
 * Suggestions handed from the code field to the key row, for an editor too short
 * to show a list at the caret: with the keyboard up and the panel open, three
 * lines of code can be all there is.
 */
@Stable
internal class CodeSuggestionBar {
    var shown: CodeCompletions? by mutableStateOf(null)
        private set

    private var chooser: (CodeCompletion) -> Unit = {}

    fun publish(completions: CodeCompletions?, choose: (CodeCompletion) -> Unit) {
        shown = completions
        chooser = choose
    }

    fun choose(item: CodeCompletion) = chooser(item)
}

/**
 * How the author arranged the code key row: whether it shows, the order of the
 * keys, and the keys hidden. Its own preferences file, like `EggPrefs`: nothing
 * here shapes typing, so none of it belongs in `KeyboardSettings`.
 */
internal class CodeKeyPrefs(context: Context, fileName: String = DEFAULT_FILE) {

    private val prefs = context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    var showRow: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ROW, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_ROW, value) }

    /** Key labels in the order the author put them, or empty for the order the row ships with. */
    var order: List<String>
        get() = prefs.getString(KEY_ORDER, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()
        set(value) = prefs.edit { putString(KEY_ORDER, value.joinToString(SEPARATOR)) }

    var hidden: Set<String>
        get() = prefs.getString(KEY_HIDDEN, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        set(value) = prefs.edit { putString(KEY_HIDDEN, value.joinToString(SEPARATOR)) }

    fun reset() = prefs.edit { clear() }

    companion object {
        /** The plugin editor's file. Another editor names its own, so arranging one row leaves the other alone. */
        const val DEFAULT_FILE = "code_keys"
        private const val KEY_SHOW_ROW = "show_row"
        private const val KEY_ORDER = "order"
        private const val KEY_HIDDEN = "hidden"

        /** A line break, which no key label contains, where a comma would. */
        private const val SEPARATOR = "\n"
    }
}

/**
 * [keys] in the saved [order], then any key the order does not name, in the
 * place the row ships it, so a key added in a later version still appears; the
 * [hidden] ones are left out.
 */
internal fun arrangeKeys(keys: List<AccessoryKey>, order: List<String>, hidden: Set<String>): List<AccessoryKey> {
    val byLabel = keys.associateBy { it.label }
    val placed = order.mapNotNull { byLabel[it] }.distinctBy { it.label }
    val named = placed.mapTo(HashSet()) { it.label }
    return (placed + keys.filter { it.label !in named }).filter { it.label !in hidden }
}

/** Where the code key row is arranged: shown or not, and each key moved by its handle or hidden by its box. */
@Composable
internal fun CodeKeysDialog(
    showRow: Boolean,
    order: List<String>,
    hidden: Set<String>,
    onShowRow: (Boolean) -> Unit,
    onOrder: (List<String>) -> Unit,
    onHidden: (Set<String>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    keys: List<AccessoryKey> = PythonAccessoryKeys,
) {
    val labels = arrangeKeys(keys, order, emptySet()).map { it.label }
    val rail = rememberScrollRailState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.code_keys_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.code_keys_show_label), modifier = Modifier.weight(1f))
                    Switch(checked = showRow, onCheckedChange = onShowRow)
                }
                Text(stringResource(R.string.code_keys_caption), style = MaterialTheme.typography.bodySmall)
                ScrollRail(state = rail, modifier = Modifier.heightIn(max = 380.dp)) {
                    ReorderableColumn(
                        items = labels,
                        label = { it },
                        onReorder = onOrder,
                    ) { label ->
                        val describe = stringResource(R.string.code_key_show_desc, label)
                        Checkbox(
                            checked = label !in hidden,
                            onCheckedChange = { visible -> onHidden(if (visible) hidden - label else hidden + label) },
                            modifier = Modifier.semantics { contentDescription = describe },
                        )
                        Text(label, fontFamily = CodeFontFamily, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_done)) } },
        dismissButton = { TextButton(onClick = onReset) { Text(stringResource(R.string.code_keys_reset_action)) } },
    )
}
