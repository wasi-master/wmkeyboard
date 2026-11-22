package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val ROW_HEIGHT = 36.dp
private const val VISIBLE_ROWS = 5

/** What kind of name a suggestion is, for the mark beside it. */
internal enum class CodeCompletionKind { VARIABLE, FUNCTION, FIELD, MODULE, CONSTANT, KEYWORD, SNIPPET, VALUE }

/** One suggestion: what the list shows, and what choosing it writes. */
@Immutable
internal data class CodeCompletion(
    val label: String,
    val kind: CodeCompletionKind,
    val insert: String,
    /** Where the caret lands inside [insert]. */
    val caret: Int = insert.length,
    /** How it is called, or what it holds. */
    val detail: String? = null,
    /**
     * Whether choosing it leaves a blank that has suggestions of its own, such as
     * the empty value of a JSON property with a fixed set of values, so the list
     * opens again at the new caret.
     */
    val reopen: Boolean = false,
    /** What it is, one paragraph, shown under the list while it is the lit row. */
    val doc: String? = null,
)

/** Suggestions for one caret, and the text a chosen one replaces. */
@Immutable
internal data class CodeCompletions(val replace: TextRange, val items: List<CodeCompletion>)

/** What the editor does with its suggestion list after a change to the text or the caret. */
internal enum class CompletionStep { ASK, KEEP, CLOSE }

/**
 * Typing asks for suggestions; deleting asks again only while a list is open,
 * so it narrows and widens as the word does; moving the caret keeps a list only
 * while the caret is still in the word it was made for.
 */
internal fun completionStep(before: String, after: String, caret: Int, shown: CodeCompletions?): CompletionStep = when {
    after.length > before.length -> CompletionStep.ASK
    after != before -> if (shown != null) CompletionStep.ASK else CompletionStep.CLOSE
    shown != null && caret >= shown.replace.min && caret <= shown.replace.max -> CompletionStep.KEEP
    else -> CompletionStep.CLOSE
}

/** The edit that choosing [item] makes: the word replaced, and the caret where the item puts it. */
internal fun CodeCompletions.editFor(item: CodeCompletion): CodeTextEdit {
    val start = replace.min
    return CodeTextEdit(replace, item.insert, TextRange(start + item.caret.coerceIn(0, item.insert.length)))
}

/**
 * The suggestion list, drawn inside the code field's frame rather than in a
 * popup window, so it clips to the editor and moves with it. [chosen] is lit,
 * which is the row Enter or Tab takes on a hardware keyboard.
 */
@Composable
internal fun CodeCompletionList(
    completions: CodeCompletions,
    chosen: Int,
    colors: CodeColors,
    style: TextStyle,
    onChoose: (CodeCompletion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val list = rememberLazyListState()
    LaunchedEffect(chosen) {
        if (chosen in completions.items.indices) list.scrollToItem(maxOf(0, chosen - VISIBLE_ROWS + 1))
    }
    // The lit row's doc, or the first row's while nothing is lit: what a tap would write.
    val doc = (completions.items.getOrNull(chosen) ?: completions.items.firstOrNull())?.doc
    Column(
        modifier = modifier
            .widthIn(min = 180.dp, max = 320.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(colors.gutter)
            .border(1.dp, colors.border, shape),
    ) {
    LazyColumn(
        state = list,
        modifier = Modifier.heightIn(max = ROW_HEIGHT * VISIBLE_ROWS),
    ) {
        itemsIndexed(completions.items) { index, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .background(if (index == chosen) colors.selection else Color.Transparent)
                    .clickable { onChoose(item) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    kindMark(item.kind),
                    color = kindColour(item.kind, colors),
                    style = style.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = Modifier.width(24.dp).clearAndSetSemantics {},
                )
                Text(
                    item.label,
                    color = colors.text,
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                item.detail?.let { detail ->
                    Spacer(Modifier.width(10.dp))
                    Text(
                        detail,
                        color = colors.gutterText,
                        style = style.copy(fontSize = style.fontSize * DETAIL_SCALE),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (!doc.isNullOrBlank()) {
        Text(
            doc,
            color = colors.gutterText,
            style = style.copy(fontSize = style.fontSize * DETAIL_SCALE, fontFamily = null),
            maxLines = DOC_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .border(Dp.Hairline, colors.border)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
    }
}

private const val DETAIL_SCALE = 0.85f

/** How much of a suggestion's doc shows under the list; the strip under the code has the rest. */
private const val DOC_LINES = 3

/** A mark in code characters rather than words, so nothing here needs translating. */
private fun kindMark(kind: CodeCompletionKind): String = when (kind) {
    CodeCompletionKind.VARIABLE -> "x"
    CodeCompletionKind.FUNCTION -> "f"
    CodeCompletionKind.FIELD -> "."
    CodeCompletionKind.MODULE -> "{}"
    CodeCompletionKind.CONSTANT -> "="
    CodeCompletionKind.KEYWORD -> "k"
    CodeCompletionKind.SNIPPET -> "<>"
    CodeCompletionKind.VALUE -> "\"\""
}

private fun kindColour(kind: CodeCompletionKind, colors: CodeColors): Color = when (kind) {
    CodeCompletionKind.VARIABLE -> colors.text
    CodeCompletionKind.FUNCTION -> colors.function
    CodeCompletionKind.FIELD -> colors.key
    CodeCompletionKind.MODULE -> colors.operator
    CodeCompletionKind.CONSTANT -> colors.number
    CodeCompletionKind.KEYWORD -> colors.keyword
    CodeCompletionKind.SNIPPET -> colors.comment
    CodeCompletionKind.VALUE -> colors.string
}
