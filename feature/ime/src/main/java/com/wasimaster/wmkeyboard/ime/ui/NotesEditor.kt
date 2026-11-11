package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Code
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatBold
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatItalic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListBulleted
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListNumbered
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatQuote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Label
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckBoxOutlineBlank
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Title
import com.wasimaster.wmkeyboard.core.notes.SuggestedNoteTag
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.NoteEdit
import com.wasimaster.wmkeyboard.ime.NoteEditField
import com.wasimaster.wmkeyboard.ime.NoteMarkdownAction
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/**
 * The note editor inside the keyboard (#560): the key rows come back beneath
 * it and type into the service's drafts, the way the clip editor works. The
 * title sits in the header as a pill; here are the tags, the body with its
 * caret, and the Markdown helper chips.
 *
 * Not a real text field — an input method has none of its own to raise — so
 * the body is [ClipEditText] drawing the draft with the shared capture caret
 * while it has the keys, and a plain text whose tap hands it the keys with
 * the caret under the finger otherwise.
 */
@Composable
internal fun NotesEditor(
    state: KeyboardUiState,
    edit: NoteEdit,
    callbacks: NotesCallbacks,
    onOpenRoute: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val short = shortLandscape()
    // In a short landscape the tag row and the helper chips fold behind one
    // header toggle, so the body keeps a line or two of room.
    var extrasShown by rememberSaveable(edit.id) { mutableStateOf(!short) }
    // Nothing else of the panel is on screen; the ring has only Done and Cancel.
    for (region in listOf(FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.RESULTS)) {
        PanelFocusTarget(panel = PanelMode.NOTES, region = region, count = 0, columns = 1) {}
    }
    PanelFocusTarget(panel = PanelMode.NOTES, region = FocusRegion.ACTIONS, count = 2, columns = 2) { index ->
        if (index == 0) callbacks.onEditDone() else callbacks.onEditCancel()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (extrasShown || !short) {
            NoteTagRow(state, edit, callbacks)
            Spacer(Modifier.height(4.dp))
        }
        val fieldShape = RoundedCornerShape(8.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(fieldShape)
                .frostGlass(kb, fieldShape, FrostSurface.CHIPS)
                .background(kb.chip)
                .chipBorder(kb, fieldShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (edit.focused == NoteEditField.BODY) {
                ClipEditText(
                    text = edit.bodyDraft,
                    placeholder = stringResource(R.string.ime_notes_body_hint),
                    textColor = MaterialTheme.colorScheme.onSurface,
                    placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // The body without the keys: a tap gives it them, the caret
                // where the finger landed, so the first tap already edits.
                var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                val text = edit.bodyDraft
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(text) {
                            detectTapGestures { position ->
                                val offset = layout?.takeIf { it.layoutInput.text.text == text }?.getOffsetForPosition(position)
                                callbacks.onEditFocus(NoteEditField.BODY, offset)
                            }
                        },
                ) {
                    if (text.isEmpty()) {
                        Text(
                            stringResource(R.string.ime_notes_body_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    } else {
                        Text(
                            text,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            onTextLayout = { layout = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        if (extrasShown || !short) {
            Spacer(Modifier.height(4.dp))
            MarkdownHelperRow(edit, callbacks)
            if (state.notes.quick) {
                Spacer(Modifier.height(4.dp))
                QuickNoteRow(state, edit, callbacks)
            }
        } else {
            Text(
                stringResource(R.string.ime_notes_tag_row_desc),
                color = kb.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { extrasShown = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * The note's tags as chips a tap takes off, then "+ Tag". Open, the picker
 * lists the user's other tags, the suggested starters not yet made, and a
 * "New…" pill that takes the keys for a name of the user's own.
 */
@Composable
private fun NoteTagRow(state: KeyboardUiState, edit: NoteEdit, callbacks: NotesCallbacks) {
    val kb = LocalKbTheme.current
    val tags = state.notes.tags
    val tagColours = remember(tags) { tags.associate { it.name.lowercase() to it.colour } }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tag in edit.tags) {
            NoteTagChip(tag, tagColours[tag.lowercase()] ?: 0, filled = true, focused = false, onClick = {
                callbacks.onTagToggle(edit.id, tag, 0)
            })
        }
        if (edit.focused == NoteEditField.TAG) {
            val shape = RoundedCornerShape(18.dp)
            Row(
                modifier = Modifier
                    .width(140.dp)
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CHIPS)
                    .background(kb.chipActive)
                    .chipBorder(kb, shape)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchQueryText(
                    query = edit.tagDraft,
                    placeholder = stringResource(R.string.ime_notes_tag_hint),
                    active = true,
                    textColor = kb.chipActiveText,
                    placeholderColor = kb.chipActiveText.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            ToolPanelChip(stringResource(com.wasimaster.wmkeyboard.common.R.string.common_cancel)) { callbacks.onTagTypeCancel() }
        } else {
            ToolPanelChip(
                stringResource(R.string.ime_notes_add_tag_action),
                selected = edit.tagPickerOpen,
                icon = if (edit.tagPickerOpen) null else Icons.Outlined.Label,
            ) { callbacks.onTagPickerToggle() }
        }
        if (edit.tagPickerOpen) {
            // The user's own tags first, then the starters nobody has made yet.
            for (tag in tags) {
                if (edit.tags.any { it.equals(tag.name, ignoreCase = true) }) continue
                NoteTagChip(tag.name, tag.colour, filled = false, focused = false, onClick = {
                    callbacks.onTagToggle(edit.id, tag.name, tag.colour)
                })
            }
            for (suggested in SuggestedNoteTag.entries) {
                val name = stringResource(suggested.nameRes)
                if (tags.hasTag(name) || edit.tags.any { it.equals(name, ignoreCase = true) }) continue
                NoteTagChip(name, suggested.colour, filled = false, focused = false, onClick = {
                    callbacks.onTagToggle(edit.id, name, suggested.colour)
                })
            }
            if (edit.focused != NoteEditField.TAG) {
                ToolPanelChip(stringResource(R.string.ime_notes_new_tag_action)) { callbacks.onTagTypeStart() }
            }
        }
    }
}

/**
 * Paste, then the Markdown chips, and the word and character count at the
 * row's end. Paste leads because the body is no real text field: there is no
 * long-press menu to paste from, and a paste key may not be on the layout.
 */
@Composable
private fun MarkdownHelperRow(edit: NoteEdit, callbacks: NotesCallbacks) {
    val kb = LocalKbTheme.current
    val actions = listOf(
        Triple(NoteMarkdownAction.BOLD, Icons.Outlined.FormatBold, R.string.ime_notes_md_bold_desc),
        Triple(NoteMarkdownAction.ITALIC, Icons.Outlined.FormatItalic, R.string.ime_notes_md_italic_desc),
        Triple(NoteMarkdownAction.BULLET, Icons.Outlined.FormatListBulleted, R.string.ime_notes_md_bullet_desc),
        Triple(NoteMarkdownAction.NUMBERED, Icons.Outlined.FormatListNumbered, R.string.ime_notes_md_numbered_desc),
        Triple(NoteMarkdownAction.TASK, Icons.Outlined.CheckBoxOutlineBlank, R.string.ime_notes_md_task_desc),
        Triple(NoteMarkdownAction.HEADING, Icons.Outlined.Title, R.string.ime_notes_md_heading_desc),
        Triple(NoteMarkdownAction.QUOTE, Icons.Outlined.FormatQuote, R.string.ime_notes_md_quote_desc),
        Triple(NoteMarkdownAction.CODE, Icons.Outlined.Code, R.string.ime_notes_md_code_desc),
    )
    val words = remember(edit.bodyDraft) { edit.bodyDraft.split(Regex("""\s+""")).count { it.isNotEmpty() } }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolPanelChip(stringResource(R.string.ime_notes_paste_desc), icon = Icons.Outlined.ContentPaste) { callbacks.onPaste() }
            for ((action, icon, descRes) in actions) {
                ToolPanelChip(stringResource(descRes), icon = icon) { callbacks.onMarkdown(action) }
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

/**
 * The Quick note row (#864), under the helper chips: New note, then the
 * latest notes by when each was last touched. A tap moves the editor to that
 * note with the caret at its end, to add to it; the note being left is saved,
 * or thrown away if it is a new one still blank.
 */
@Composable
private fun QuickNoteRow(state: KeyboardUiState, edit: NoteEdit, callbacks: NotesCallbacks) {
    val untitled = stringResource(R.string.ime_notes_untitled)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickNoteChip(stringResource(R.string.ime_notes_new_action), selected = edit.isNew, icon = Icons.Outlined.Add) {
            callbacks.onQuickPick(null)
        }
        for (note in state.notes.recent) {
            if (edit.isNew && note.id == edit.id) continue
            QuickNoteChip(note.displayTitle().ifEmpty { untitled }, selected = note.id == edit.id) {
                callbacks.onQuickPick(note.id)
            }
        }
    }
}

/** [ToolPanelChip]'s pill, with a leading icon beside the words and a width cap so a long title ends in an ellipsis. */
@Composable
private fun QuickNoteChip(label: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    val content = if (selected) kb.chipActiveText else kb.chipText
    Row(
        modifier = Modifier
            .widthIn(max = 160.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(if (selected) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .clickable {
                feedback()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            color = content,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The editor's height while the keys are back beneath it: the header, the
 * tag row, the helper row and their paddings, plus four lines of text at the
 * user's font scale; two lines and no rows in a short landscape. The Quick
 * note row adds its own line of chips. The frame takes the capture strip out
 * of this and never goes below the header.
 */
@Composable
internal fun notesEditCompactHeight(lineHeight: Dp, quickRow: Boolean = false): Dp = when {
    shortLandscape() -> FullBleedHeaderHeight + 14.dp + lineHeight * 2
    quickRow -> FullBleedHeaderHeight + 86.dp + QuickNoteRowHeight + lineHeight * 4
    else -> FullBleedHeaderHeight + 86.dp + lineHeight * 4
}

/** The Quick note row's chip line and the gap above it. */
private val QuickNoteRowHeight = 32.dp
