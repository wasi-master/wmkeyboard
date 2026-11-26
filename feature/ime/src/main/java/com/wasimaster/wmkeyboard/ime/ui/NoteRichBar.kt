package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckBox
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatBold
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatColorText
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatItalic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListBulleted
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListNumbered
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatQuote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatStrikethrough
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatUnderlined
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Highlight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFormat
import com.wasimaster.wmkeyboard.core.notes.rich.NoteBlockStyle
import com.wasimaster.wmkeyboard.core.notes.rich.NoteFont
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMark
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkKind
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkup
import com.wasimaster.wmkeyboard.core.notes.rich.NoteRichEdits
import com.wasimaster.wmkeyboard.core.notes.rich.NoteRichPalette
import com.wasimaster.wmkeyboard.core.notes.rich.NoteTableOp
import com.wasimaster.wmkeyboard.core.notes.rich.NoteUnderline
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.NoteEdit
import com.wasimaster.wmkeyboard.ime.NoteEditField
import com.wasimaster.wmkeyboard.ime.NoteRichAction
import com.wasimaster.wmkeyboard.ime.NoteRichInput
import com.wasimaster.wmkeyboard.ime.NotesLogic
import com.wasimaster.wmkeyboard.ime.R

/** Which row the rich editor's bar shows. */
private enum class RichRow { MAIN, FORMAT, COLOR, HIGHLIGHT, ADD }

/**
 * The keyboard's rich-text bar (#1586), under the note in place of the
 * Markdown chips: one row of chips the keys stay beneath, as the panel's
 * other rows are. The main row has undo and redo, the inline formats and
 * the list styles; Format, Text colour, Highlight and Add swap the row for
 * their own choices, with a back chip to return. Typing `/` at the start of
 * an empty line turns the row into the slash list, narrowed by the letters
 * after it.
 *
 * A chip shows as on when the letter before the caret (or every selected
 * letter) wears that format, or when it is waiting for the next letters.
 */
@Composable
internal fun NoteRichBar(state: KeyboardUiState, edit: NoteEdit, callbacks: NotesCallbacks) {
    val kb = LocalKbTheme.current
    var row by rememberSaveable(edit.id) { mutableStateOf(RichRow.MAIN) }
    val caretText = state.captureCaretText()?.takeIf { edit.focused == NoteEditField.BODY }
    val draft = caretText?.let(NoteRichInput::draft)
    val marks = remember(draft) { draft?.let(NoteRichEdits::marksAt).orEmpty() } + edit.pendingMarks
    val block = remember(draft) { draft?.let(NoteRichEdits::blockAt) }
    val inTable = remember(draft) {
        draft?.let { d -> NoteMarkup.read(d.text).let { r -> r.lines[r.lineAt(d.start)].kind == NoteMarkup.LineKind.TABLE } } == true
    }
    val slash = caretText?.let(NotesLogic::slashQuery)
    val words = remember(edit.bodyDraft) { NoteMarkup.strip(edit.bodyDraft).split(Regex("""\s+""")).count { it.isNotEmpty() } }
    val rich: (NoteRichAction) -> Unit = callbacks.onRich
    fun on(mark: NoteMark) = mark in marks
    fun onKind(kind: NoteMarkKind) = marks.any { it.kind == kind }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val slashItems = slash?.let { query -> slashCommands().filter { (label, _) -> label.split(' ').any { it.startsWith(query, ignoreCase = true) } } }
            if (!slashItems.isNullOrEmpty()) {
                for ((label, action) in slashItems) {
                    ToolPanelChip(label, selected = true) {
                        when (action) {
                            is SlashPick.Rich -> rich(NoteRichAction.Slash(action.action))
                            is SlashPick.InApp -> callbacks.onAddInApp(action.kind)
                        }
                    }
                }
                return@Row
            }
            when (row) {
                RichRow.MAIN -> {
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_undo_desc), icon = Icons.AutoMirrored.Outlined.Undo) { callbacks.onRichUndo(false) }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_redo_desc), icon = Icons.AutoMirrored.Outlined.Redo) { callbacks.onRichUndo(true) }
                    if (inTable) {
                        ToolPanelChip(stringResource(R.string.ime_notes_rich_table_add_row)) { rich(NoteRichAction.TableEdit(NoteTableOp.ADD_ROW)) }
                        ToolPanelChip(stringResource(R.string.ime_notes_rich_table_add_column)) { rich(NoteRichAction.TableEdit(NoteTableOp.ADD_COLUMN)) }
                        ToolPanelChip(stringResource(R.string.ime_notes_rich_table_remove_row)) { rich(NoteRichAction.TableEdit(NoteTableOp.REMOVE_ROW)) }
                        ToolPanelChip(stringResource(R.string.ime_notes_rich_table_remove_column)) { rich(NoteRichAction.TableEdit(NoteTableOp.REMOVE_COLUMN)) }
                    }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_format_desc), icon = Icons.Outlined.TextFormat) { row = RichRow.FORMAT }
                    ToolPanelChip(stringResource(R.string.ime_notes_md_task_desc), selected = block == NoteBlockStyle.TODO, icon = Icons.Outlined.CheckBox) {
                        rich(NoteRichAction.Block(NoteBlockStyle.TODO))
                    }
                    ToolPanelChip(stringResource(R.string.ime_notes_md_bold_desc), selected = on(NoteMark.BOLD), icon = Icons.Outlined.FormatBold) { rich(NoteRichAction.Mark(NoteMark.BOLD)) }
                    ToolPanelChip(stringResource(R.string.ime_notes_md_italic_desc), selected = on(NoteMark.ITALIC), icon = Icons.Outlined.FormatItalic) { rich(NoteRichAction.Mark(NoteMark.ITALIC)) }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_underline_desc), selected = onKind(NoteMarkKind.UNDERLINE), icon = Icons.Outlined.FormatUnderlined) {
                        rich(NoteRichAction.Mark(marks.firstOrNull { it.kind == NoteMarkKind.UNDERLINE } ?: NoteMark.UNDERLINE))
                    }
                    ToolPanelChip(stringResource(R.string.ime_notes_md_strike_desc), selected = on(NoteMark.STRIKE), icon = Icons.Outlined.FormatStrikethrough) { rich(NoteRichAction.Mark(NoteMark.STRIKE)) }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_color_desc), selected = onKind(NoteMarkKind.COLOR), icon = Icons.Outlined.FormatColorText) { row = RichRow.COLOR }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_highlight_desc), selected = onKind(NoteMarkKind.HIGHLIGHT), icon = Icons.Outlined.Highlight) { row = RichRow.HIGHLIGHT }
                    ToolPanelChip(stringResource(R.string.ime_notes_md_bullet_desc), selected = block == NoteBlockStyle.BULLET, icon = Icons.Outlined.FormatListBulleted) {
                        rich(NoteRichAction.Block(NoteBlockStyle.BULLET))
                    }
                    ToolPanelChip(stringResource(R.string.ime_notes_md_numbered_desc), selected = block == NoteBlockStyle.NUMBERED, icon = Icons.Outlined.FormatListNumbered) {
                        rich(NoteRichAction.Block(NoteBlockStyle.NUMBERED))
                    }
                    ToolPanelChip(stringResource(R.string.ime_notes_paste_desc), icon = Icons.Outlined.ContentPaste) { callbacks.onPaste() }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_add_desc), icon = Icons.Outlined.Add) { row = RichRow.ADD }
                }
                RichRow.FORMAT -> {
                    BackChip { row = RichRow.MAIN }
                    for ((style, label) in blockStyles()) {
                        ToolPanelChip(label, selected = block == style) { rich(NoteRichAction.Block(style)) }
                    }
                    ToolPanelChip(stringResource(R.string.ime_notes_md_quote_desc), selected = block == NoteBlockStyle.QUOTE, icon = Icons.Outlined.FormatQuote) {
                        rich(NoteRichAction.Block(NoteBlockStyle.QUOTE))
                    }
                    for ((style, label) in underlineStyles()) {
                        val mark = NoteMark.underline(style)
                        ToolPanelChip(label, selected = on(mark)) { rich(NoteRichAction.Mark(mark)) }
                    }
                    for ((font, label) in fonts()) {
                        val mark = NoteMark.font(font)
                        ToolPanelChip(label, selected = on(mark)) { rich(NoteRichAction.Mark(mark)) }
                    }
                    for ((size, label) in sizes()) {
                        val mark = NoteMark.size(size)
                        ToolPanelChip(label, selected = on(mark)) { rich(NoteRichAction.Mark(mark)) }
                    }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_clear_desc)) { rich(NoteRichAction.ClearMarks) }
                }
                RichRow.COLOR -> {
                    BackChip { row = RichRow.MAIN }
                    val names = colourNames()
                    NoteRichPalette.TEXT.forEachIndexed { i, hex ->
                        val mark = NoteMark.color(hex)
                        SwatchChip(NoteRichStyle.color(hex) ?: kb.chipText, names.getOrElse(i) { hex }, on(mark)) { rich(NoteRichAction.Mark(mark)) }
                    }
                    marks.firstOrNull { it.kind == NoteMarkKind.COLOR }?.let { current ->
                        ToolPanelChip(stringResource(R.string.ime_notes_rich_no_colour)) { rich(NoteRichAction.Mark(current)) }
                    }
                }
                RichRow.HIGHLIGHT -> {
                    BackChip { row = RichRow.MAIN }
                    val names = highlightNames()
                    NoteRichPalette.HIGHLIGHT.forEachIndexed { i, hex ->
                        val mark = NoteMark.highlight(hex)
                        SwatchChip(NoteRichStyle.highlight(hex).copy(alpha = 1f), names.getOrElse(i) { hex.orEmpty() }, on(mark)) { rich(NoteRichAction.Mark(mark)) }
                    }
                    marks.firstOrNull { it.kind == NoteMarkKind.HIGHLIGHT }?.let { current ->
                        ToolPanelChip(stringResource(R.string.ime_notes_rich_no_highlight)) { rich(NoteRichAction.Mark(current)) }
                    }
                }
                RichRow.ADD -> {
                    BackChip { row = RichRow.MAIN }
                    ToolPanelChip(stringResource(R.string.ime_notes_md_divider_desc)) { rich(NoteRichAction.Divider) }
                    ToolPanelChip(stringResource(R.string.ime_notes_md_table_desc)) { rich(NoteRichAction.Table) }
                    // Pictures, drawings and scans need the camera, the gallery or a
                    // canvas the size of the screen: the note opens in the app for them.
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_photo)) { callbacks.onAddInApp(ADD_PHOTO) }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_camera)) { callbacks.onAddInApp(ADD_CAMERA) }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_drawing)) { callbacks.onAddInApp(ADD_DRAWING) }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_scan)) { callbacks.onAddInApp(ADD_SCAN) }
                    if (inTable) ToolPanelChip(stringResource(R.string.ime_notes_rich_table_delete)) { rich(NoteRichAction.TableEdit(NoteTableOp.DELETE)) }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_move_up)) { rich(NoteRichAction.Move(up = true)) }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_move_down)) { rich(NoteRichAction.Move(up = false)) }
                    ToolPanelChip(stringResource(R.string.ime_notes_rich_edit_markdown)) { callbacks.onEditorFlip() }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.ime_notes_word_count, words, edit.bodyDraft.length),
            color = kb.secondaryText,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

/** What a slash-list entry does: an edit here, or the note handed to the app for a picture. */
private sealed interface SlashPick {
    data class Rich(val action: NoteRichAction) : SlashPick
    data class InApp(val kind: String) : SlashPick
}

/** The slash list, by name, in the order the menu lists it. */
@Composable
private fun slashCommands(): List<Pair<String, SlashPick>> =
    blockStyles().map { (style, label) -> label to SlashPick.Rich(NoteRichAction.Block(style)) } + listOf(
        stringResource(R.string.ime_notes_md_quote_desc) to SlashPick.Rich(NoteRichAction.Block(NoteBlockStyle.QUOTE)),
        stringResource(R.string.ime_notes_md_bullet_desc) to SlashPick.Rich(NoteRichAction.Block(NoteBlockStyle.BULLET)),
        stringResource(R.string.ime_notes_md_numbered_desc) to SlashPick.Rich(NoteRichAction.Block(NoteBlockStyle.NUMBERED)),
        stringResource(R.string.ime_notes_rich_todos) to SlashPick.Rich(NoteRichAction.Block(NoteBlockStyle.TODO)),
        stringResource(R.string.ime_notes_md_divider_desc) to SlashPick.Rich(NoteRichAction.Divider),
        stringResource(R.string.ime_notes_md_table_desc) to SlashPick.Rich(NoteRichAction.Table),
        stringResource(R.string.ime_notes_rich_photo) to SlashPick.InApp(ADD_PHOTO),
        stringResource(R.string.ime_notes_rich_camera) to SlashPick.InApp(ADD_CAMERA),
        stringResource(R.string.ime_notes_rich_drawing) to SlashPick.InApp(ADD_DRAWING),
        stringResource(R.string.ime_notes_rich_scan) to SlashPick.InApp(ADD_SCAN),
    )

@Composable
private fun blockStyles(): List<Pair<NoteBlockStyle, String>> = listOf(
    NoteBlockStyle.TITLE to stringResource(R.string.ime_notes_rich_title),
    NoteBlockStyle.SUBTITLE to stringResource(R.string.ime_notes_rich_subtitle),
    NoteBlockStyle.HEADING to stringResource(R.string.ime_notes_md_heading_desc),
    NoteBlockStyle.BODY to stringResource(R.string.ime_notes_rich_body),
)

@Composable
private fun underlineStyles(): List<Pair<NoteUnderline, String>> = listOf(
    NoteUnderline.WAVY to stringResource(R.string.ime_notes_rich_wavy),
    NoteUnderline.DOTTED to stringResource(R.string.ime_notes_rich_dotted),
    NoteUnderline.DASHED to stringResource(R.string.ime_notes_rich_dashed),
    NoteUnderline.DOUBLE to stringResource(R.string.ime_notes_rich_double),
)

@Composable
private fun fonts(): List<Pair<NoteFont, String>> = listOf(
    NoteFont.SERIF to stringResource(R.string.ime_notes_rich_font_serif),
    NoteFont.MONO to stringResource(R.string.ime_notes_rich_font_mono),
    NoteFont.HAND to stringResource(R.string.ime_notes_rich_font_hand),
)

@Composable
private fun sizes(): List<Pair<String, String>> {
    val labels = listOf(
        stringResource(R.string.ime_notes_rich_size_small),
        stringResource(R.string.ime_notes_rich_size_large),
        stringResource(R.string.ime_notes_rich_size_larger),
        stringResource(R.string.ime_notes_rich_size_huge),
    )
    return NoteRichPalette.SIZES.zip(labels)
}

@Composable
private fun colourNames(): List<String> = listOf(
    stringResource(R.string.ime_notes_rich_colour_red),
    stringResource(R.string.ime_notes_rich_colour_orange),
    stringResource(R.string.ime_notes_rich_colour_amber),
    stringResource(R.string.ime_notes_rich_colour_green),
    stringResource(R.string.ime_notes_rich_colour_teal),
    stringResource(R.string.ime_notes_rich_colour_blue),
    stringResource(R.string.ime_notes_rich_colour_purple),
    stringResource(R.string.ime_notes_rich_colour_grey),
)

@Composable
private fun highlightNames(): List<String> = listOf(
    stringResource(R.string.ime_notes_rich_colour_yellow),
    stringResource(R.string.ime_notes_rich_colour_green),
    stringResource(R.string.ime_notes_rich_colour_blue),
    stringResource(R.string.ime_notes_rich_colour_pink),
    stringResource(R.string.ime_notes_rich_colour_orange),
    stringResource(R.string.ime_notes_rich_colour_purple),
)

@Composable
private fun BackChip(onClick: () -> Unit) {
    ToolPanelChip(stringResource(R.string.ime_notes_rich_back_desc), icon = Icons.AutoMirrored.Outlined.ArrowBack, onClick = onClick)
}

/** A colour to pick: a filled dot in a chip's height, ringed when it is the one on. */
@Composable
private fun SwatchChip(colour: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable {
                feedback()
                onClick()
            }
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (selected) 18.dp else 20.dp)
                .clip(CircleShape)
                .background(colour)
                .then(if (selected) Modifier.border(2.dp, kb.accent, CircleShape) else Modifier.border(1.dp, kb.chipText.copy(alpha = 0.25f), CircleShape)),
        )
    }
}

/** What [NotesCallbacks.onAddInApp] asks the app's editor to open with. */
const val ADD_PHOTO = "photo"
const val ADD_CAMERA = "camera"
const val ADD_DRAWING = "drawing"
const val ADD_SCAN = "scan"
