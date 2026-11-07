package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.FormatIndentIncrease
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import kotlin.math.abs
import kotlin.math.sign

/** Which keys the row under the code shows. */
internal enum class AccessoryPage { SYMBOLS, COMMANDS }

/** One key: its label, what a tap types, and what holding it types. */
@Immutable
internal data class AccessoryKey(val label: String, val insert: String, val held: String? = null)

/** [value] with its selection replaced by [text] and the caret after it, exactly as if typed. */
internal fun typed(value: TextFieldValue, text: String): TextFieldValue {
    val start = value.selection.min
    val next = value.text.substring(0, start) + text + value.text.substring(value.selection.max)
    return TextFieldValue(next, TextRange(start + text.length))
}

/** The offset [lines] lines below [offset], or above for a negative count, at the same column or the end of a shorter line. */
internal fun caretLines(text: String, offset: Int, lines: Int): Int {
    val here = lineRangeAt(text, offset)
    val column = offset.coerceIn(here.start, here.end) - here.start
    var start = here.start
    repeat(abs(lines)) {
        if (lines < 0) {
            if (start > 0) start = lineRangeAt(text, start - 1).start
        } else {
            val end = lineRangeAt(text, start).end
            if (end < text.length) start = end + 1
        }
    }
    val target = lineRangeAt(text, start)
    return (target.start + column).coerceAtMost(target.end)
}

/**
 * The row of keys above the soft keyboard, for the characters code needs and a
 * phone keyboard hides: an indent key, the symbols or the editing commands, a
 * page key, and two caret keys that turn into a trackpad under a sliding finger.
 * Symbols go through [CodeEditorState.edit], so brackets and quotes pair the
 * way they do when typed.
 */
@Composable
internal fun CodeAccessoryRow(
    state: CodeEditorState,
    colors: CodeColors,
    lineComment: String?,
    onFind: () -> Unit,
    onFormat: () -> Unit,
    onSuggest: () -> Unit,
    modifier: Modifier = Modifier,
    /** The blocks of the text, for Block start and Block end. */
    blocks: (String) -> List<TextRange> = { emptyList() },
    /** The symbol keys, in the order and with the keys the author chose. */
    keys: List<AccessoryKey> = PythonAccessoryKeys,
    /** Suggestions the field hands over when it is too short to list them at the caret. */
    suggestionBar: CodeSuggestionBar? = null,
) {
    var page by rememberSaveable { mutableStateOf(AccessoryPage.SYMBOLS) }
    val offered = suggestionBar?.shown?.items.orEmpty()
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(colors.gutter)
            .drawBehind { drawLine(colors.border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccessoryIcon(
            Icons.AutoMirrored.Outlined.FormatIndentIncrease,
            R.string.code_key_indent_desc,
            colors,
            onLongClick = { state.shiftLines(-1) },
        ) { state.shiftLines(1) }
        LazyRow(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (offered.isNotEmpty()) {
                items(offered) { item -> TextKey(label = item.label, colors = colors) { suggestionBar?.choose(item) } }
            } else when (page) {
                AccessoryPage.SYMBOLS -> items(keys) { key ->
                    TextKey(
                        label = key.label,
                        colors = colors,
                        onLongClick = key.held?.let { held -> { state.edit(typed(state.value, held)) } },
                    ) { state.edit(typed(state.value, key.insert)) }
                }
                AccessoryPage.COMMANDS -> items(commands(state, lineComment, onFind, onFormat, onSuggest, blocks)) { command ->
                    TextKey(label = stringResource(command.label), colors = colors, onClick = command.run)
                }
            }
        }
        AccessoryIcon(Icons.Outlined.MoreHoriz, R.string.code_keys_page_desc, colors) {
            page = if (page == AccessoryPage.SYMBOLS) AccessoryPage.COMMANDS else AccessoryPage.SYMBOLS
        }
        CaretKey(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, R.string.code_key_left_desc, colors, state, direction = -1)
        CaretKey(Icons.AutoMirrored.Outlined.KeyboardArrowRight, R.string.code_key_right_desc, colors, state, direction = 1)
    }
}

private class Command(@StringRes val label: Int, val run: () -> Unit)

private fun commands(
    state: CodeEditorState,
    lineComment: String?,
    onFind: () -> Unit,
    onFormat: () -> Unit,
    onSuggest: () -> Unit,
    blocks: (String) -> List<TextRange>,
): List<Command> = listOfNotNull(
    Command(R.string.code_command_suggest, onSuggest),
    Command(R.string.code_command_undo) { state.undo() },
    Command(R.string.code_command_redo) { state.redo() },
    lineComment?.let { marker ->
        Command(R.string.code_command_comment) { state.applyEdit(toggleLineComment(state.text, state.value.selection, marker)) }
    },
    Command(R.string.code_command_duplicate) { state.applyEdit(duplicateLines(state.text, state.value.selection)) },
    Command(R.string.code_command_line_up) { moveLines(state.text, state.value.selection, -1)?.let(state::applyEdit) },
    Command(R.string.code_command_line_down) { moveLines(state.text, state.value.selection, 1)?.let(state::applyEdit) },
    Command(R.string.code_command_select_word) { state.select(wordRangeAt(state.text, state.value.selection.end)) },
    Command(R.string.code_command_select_line) { state.select(lineRangeAt(state.text, state.value.selection.end)) },
    Command(R.string.code_command_block_start) {
        blockAround(blocks(state.text), state.value.selection.end)?.let { state.moveTo(it.min) }
    },
    Command(R.string.code_command_block_end) {
        blockAround(blocks(state.text), state.value.selection.end)?.let { state.moveTo(it.max) }
    },
    Command(R.string.code_command_find, onFind),
    Command(R.string.code_command_format, onFormat),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextKey(label: String, colors: CodeColors, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .widthIn(min = 40.dp)
            .fillMaxHeight()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = CodeFontFamily, fontSize = 15.sp, color = colors.text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccessoryIcon(
    icon: ImageVector,
    @StringRes description: Int,
    colors: CodeColors,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(44.dp)
            .fillMaxHeight()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = stringResource(description), tint = colors.gutterActiveText)
    }
}

/**
 * A caret key. A tap moves the caret one character. A finger that slides turns
 * the key into a trackpad: across moves by characters, up and down by lines,
 * for as long as the finger stays down.
 */
@Composable
private fun CaretKey(icon: ImageVector, @StringRes description: Int, colors: CodeColors, state: CodeEditorState, direction: Int) {
    val words = stringResource(description)
    val step = { state.moveTo(caretStep(state.text, state.value.selection.end, direction, false)) }
    Box(
        Modifier
            .width(44.dp)
            .fillMaxHeight()
            .semantics {
                contentDescription = words
                role = Role.Button
                onClick {
                    step()
                    true
                }
            }
            .pointerInput(state, direction) {
                val across = CHARACTER_TRAVEL.toPx()
                val down = LINE_TRAVEL.toPx()
                awaitEachGesture {
                    val first = awaitFirstDown()
                    var dx = 0f
                    var dy = 0f
                    var slid = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                        if (!change.pressed) break
                        val moved = change.positionChange()
                        dx += moved.x
                        dy += moved.y
                        while (abs(dx) >= across) {
                            val sideways = sign(dx).toInt()
                            state.moveTo(caretStep(state.text, state.value.selection.end, sideways, false))
                            dx -= sideways * across
                            slid = true
                        }
                        while (abs(dy) >= down) {
                            val vertical = sign(dy).toInt()
                            state.moveTo(caretLines(state.text, state.value.selection.end, vertical))
                            dy -= vertical * down
                            slid = true
                        }
                        change.consume()
                    }
                    if (!slid) step()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.gutterActiveText)
    }
}

/** How far a finger slides for the caret to move one character, and one line. */
private val CHARACTER_TRAVEL = 12.dp
private val LINE_TRAVEL = 24.dp
