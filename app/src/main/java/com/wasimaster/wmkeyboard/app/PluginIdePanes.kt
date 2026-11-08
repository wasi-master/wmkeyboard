package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.plugins.PyOutlineItem

/**
 * What the checks found, errors first and then in document order, each with
 * its sentence and its line. A tap selects the text the problem is about.
 */
@Composable
internal fun ProblemsPane(diagnostics: List<CodeDiagnostic>, lineStarts: List<Int>, onJump: (TextRange) -> Unit) {
    if (diagnostics.isEmpty()) {
        EmptyPane(R.string.plugin_ide_problems_empty)
        return
    }
    val context = LocalContext.current
    val colors = rememberCodeColors()
    val ordered = remember(diagnostics) { diagnostics.sortedWith(compareBy({ it.severity }, { it.range.min })) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        items(ordered) { diagnostic ->
            val line = lineOf(lineStarts, diagnostic.range.min.coerceIn(0, lineStarts.last())) + 1
            val severity = stringResource(
                when (diagnostic.severity) {
                    CodeSeverity.ERROR -> R.string.plugin_ide_severity_error
                    CodeSeverity.WARNING -> R.string.plugin_ide_severity_warning
                    CodeSeverity.INFO -> R.string.plugin_ide_severity_info
                },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onJump(diagnostic.range) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (diagnostic.severity) {
                                CodeSeverity.ERROR -> colors.problem
                                CodeSeverity.WARNING -> colors.warning
                                CodeSeverity.INFO -> colors.gutterText
                            },
                        )
                        .semantics { contentDescription = severity },
                )
                Text(
                    diagnostic.message(context),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Text(
                    stringResource(R.string.plugin_ide_console_line_label, line),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** The functions and top-level names, each indented under the function it is written in. A tap selects the name. */
@Composable
internal fun OutlinePane(outline: List<PyOutlineItem>, lineStarts: List<Int>, onJump: (TextRange) -> Unit) {
    if (outline.isEmpty()) {
        EmptyPane(R.string.plugin_ide_outline_empty)
        return
    }
    val colors = rememberCodeColors()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(outline) { item ->
            val line = lineOf(lineStarts, item.nameSpan.start.coerceIn(0, lineStarts.last())) + 1
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onJump(TextRange(item.nameSpan.start, item.nameSpan.end)) }
                    .padding(start = (12 + item.depth * 16).dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (item.kind == "function") "f" else "x",
                    fontFamily = CodeFontFamily,
                    fontSize = 13.sp,
                    color = if (item.kind == "function") colors.function else colors.key,
                    modifier = Modifier.width(18.dp).clearAndSetSemantics {},
                )
                Text(
                    item.name,
                    fontFamily = CodeFontFamily,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.plugin_ide_console_line_label, line),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyPane(@StringRes words: Int) {
    Text(
        stringResource(words),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
