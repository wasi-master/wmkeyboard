package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.plugins.PyApiEntry

/**
 * The API name at the caret, how it is called, and the first line of what it
 * does, in a strip under the code. A phone has no hover, and a long press
 * belongs to selection, so this is the plugin editor's hover: it needs no
 * gesture at all. A tap opens the whole paragraph and a second tap folds it.
 */
@Composable
internal fun ApiDocStrip(entry: PyApiEntry, colors: CodeColors) {
    val call = entry.signature?.let { if (entry.parent.isEmpty()) it else "${entry.parent}.$it" } ?: entry.path
    CodeDocStrip(entry.path, call, entry.doc, colors)
}

/**
 * A strip under the code that explains the name at the caret: [signature] on
 * one line in code colours, and [body] under it, one line until pressed.
 * [key] names the entry, so a strip opened for one name closes for the next.
 */
@Composable
internal fun CodeDocStrip(key: String, signature: String, body: String, colors: CodeColors) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    val toggleLabel = stringResource(if (open) R.string.plugin_ide_doc_less_action else R.string.plugin_ide_doc_more_action)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.gutter)
            .clickable(onClickLabel = toggleLabel) { open = !open }
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            signature,
            fontFamily = CodeFontFamily,
            fontSize = 12.sp,
            color = colors.function,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            body,
            fontSize = 12.sp,
            color = colors.text,
            maxLines = if (open) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
