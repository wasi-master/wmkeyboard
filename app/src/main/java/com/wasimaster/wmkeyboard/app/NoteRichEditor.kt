package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.notes.NoteAttachments
import com.wasimaster.wmkeyboard.core.notes.NoteChecklist
import com.wasimaster.wmkeyboard.core.notes.rich.NoteDraft
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMark
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkup
import com.wasimaster.wmkeyboard.core.notes.rich.NoteRichEdits
import com.wasimaster.wmkeyboard.core.notes.rich.NoteTable
import com.wasimaster.wmkeyboard.ime.ui.NoteRichStyle

/**
 * The note as rich text in the app (#1586), the default editor: what is
 * written shows as it will read, bold as bold, lists with bullets and boxes,
 * pictures as pictures, tables as tables.
 *
 * The note is still its Markdown. The text between pictures and tables is
 * one real text field per run of lines, drawn by [NoteRichStyle] with the
 * markers hidden (the same drawing as the keyboard's editor), so the system
 * keyboard, selection and copy and paste all work as in any field; a row of
 * pictures and a table are blocks of their own between those fields. Every
 * change is a change to the Markdown, so the note saves, syncs and searches
 * as it always has.
 */

/** One stretch of a note: text lines, a row of pictures, or a table, with where it sits in the body. */
internal data class NoteSegment(val kind: Kind, val start: Int, val end: Int, val text: String, val firstLine: Int) {
    enum class Kind { TEXT, IMAGES, TABLE }
}

/** [body] cut into [NoteSegment]s, in order; joined with line breaks they are the body again. */
internal fun noteSegments(body: String): List<NoteSegment> {
    val read = NoteMarkup.read(body)
    val out = ArrayList<NoteSegment>()
    var i = 0
    while (i < read.lines.size) {
        val line = read.lines[i]
        val kind = when (line.kind) {
            NoteMarkup.LineKind.IMAGES -> NoteSegment.Kind.IMAGES
            NoteMarkup.LineKind.TABLE -> NoteSegment.Kind.TABLE
            else -> NoteSegment.Kind.TEXT
        }
        var j = i
        if (kind != NoteSegment.Kind.IMAGES) {
            while (j + 1 < read.lines.size && segmentKind(read.lines[j + 1].kind) == kind) j++
        }
        val start = line.start
        val end = read.lines[j].end
        out += NoteSegment(kind, start, end, body.substring(start, end), i)
        i = j + 1
    }
    return out
}

private fun segmentKind(kind: NoteMarkup.LineKind): NoteSegment.Kind = when (kind) {
    NoteMarkup.LineKind.IMAGES -> NoteSegment.Kind.IMAGES
    NoteMarkup.LineKind.TABLE -> NoteSegment.Kind.TABLE
    else -> NoteSegment.Kind.TEXT
}

/**
 * The rich editor's state between its fields and its toolbar: which field
 * has the caret and where, the formats waiting for the next letters, and
 * the undo history of the whole body.
 */
@Stable
internal class NoteRichController {
    /** The text segment that last had the caret, by index. */
    var focused by mutableStateOf<Int?>(null)

    /** Each text segment's field value, so its caret and selection survive a recomposition. */
    val values = mutableStateMapOf<Int, TextFieldValue>()

    /** Formats picked with nothing selected, for the next letters typed. */
    var pending by mutableStateOf<List<NoteMark>>(emptyList())

    private val undoSteps = ArrayList<String>()
    private val redoSteps = ArrayList<String>()
    private var lastStepAt = 0L
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** A focus request per segment, for moving the caret into a field from the toolbar. */
    val requesters = HashMap<Int, FocusRequester>()

    fun requester(index: Int): FocusRequester = requesters.getOrPut(index) { FocusRequester() }

    /**
     * [before] remembered for Undo. Typing a few seconds apart is one step,
     * as in any editor; [force] (a format, an insert, a move) is always its own.
     */
    fun record(before: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || undoSteps.isEmpty() || now - lastStepAt > STEP_MS) {
            undoSteps += before
            if (undoSteps.size > MAX_STEPS) undoSteps.removeAt(0)
        }
        lastStepAt = if (force) 0L else now
        redoSteps.clear()
        canUndo = true
        canRedo = false
    }

    fun undo(current: String): String? {
        val previous = undoSteps.removeLastOrNull() ?: return null
        redoSteps += current
        lastStepAt = 0L
        values.clear()
        canUndo = undoSteps.isNotEmpty()
        canRedo = true
        return previous
    }

    fun redo(current: String): String? {
        val next = redoSteps.removeLastOrNull() ?: return null
        undoSteps += current
        lastStepAt = 0L
        values.clear()
        canUndo = true
        canRedo = redoSteps.isNotEmpty()
        return next
    }

    /** A field's change, tidied the way the keyboard's editor tidies one ([NoteRichEdits.settle]). */
    fun settle(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        if (old.text == new.text) {
            // The caret moved by itself: a waiting format was for where it was.
            if (old.selection != new.selection && pending.isNotEmpty()) pending = emptyList()
            return new
        }
        val settled = NoteRichEdits.settle(
            NoteDraft(old.text, old.selection.min, old.selection.max),
            NoteDraft(new.text, new.selection.min, new.selection.max),
            pending,
        )
        if (settled.usedPending) pending = emptyList()
        val d = settled.draft
        return if (d.text == new.text && d.start == new.selection.min && d.end == new.selection.max) {
            new
        } else {
            TextFieldValue(d.text, TextRange(d.start, d.end))
        }
    }

    /**
     * [op] on the focused text segment's text and selection, as a new body;
     * with no text segment, on a new one at the end. Null when nothing changed.
     */
    fun apply(body: String, op: (NoteDraft) -> NoteDraft): String? {
        val segments = noteSegments(body)
        val index = focused?.takeIf { segments.getOrNull(it)?.kind == NoteSegment.Kind.TEXT }
            ?: segments.indexOfLast { it.kind == NoteSegment.Kind.TEXT }.takeIf { it >= 0 }
        if (index == null) {
            val out = op(NoteDraft("", 0)).clamped()
            if (out.text.isEmpty()) return null
            return if (body.isEmpty()) out.text else body + "\n" + out.text
        }
        val seg = segments[index]
        val stored = values[index]?.takeIf { it.text == seg.text }
        val draft = stored?.let { NoteDraft(it.text, it.selection.min, it.selection.max) } ?: NoteDraft(seg.text, seg.text.length)
        val out = op(draft).clamped()
        if (out.text == draft.text && out.start == draft.start && out.end == draft.end) return null
        values[index] = TextFieldValue(out.text, TextRange(out.start, out.end))
        return body.substring(0, seg.start) + out.text + body.substring(seg.end)
    }

    /** The focused text segment's text and selection, for the toolbar's on and off states. */
    fun focusedDraft(body: String): NoteDraft? {
        val index = focused ?: return null
        val seg = noteSegments(body).getOrNull(index)?.takeIf { it.kind == NoteSegment.Kind.TEXT } ?: return null
        val v = values[index]?.takeIf { it.text == seg.text } ?: return NoteDraft(seg.text, seg.text.length)
        return NoteDraft(v.text, v.selection.min, v.selection.max)
    }

    private companion object {
        const val STEP_MS = 1_500L
        const val MAX_STEPS = 200
    }
}

/** The styled text in a field: the source itself, so offsets need no mapping. */
private class RichTransformation(private val styled: NoteRichStyle.Styled) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(if (styled.text.text == text.text) styled.text else text, OffsetMapping.Identity)

    override fun equals(other: Any?): Boolean = other is RichTransformation && other.styled === styled

    override fun hashCode(): Int = System.identityHashCode(styled)
}

/**
 * The note's body as rich text. [onBody] hears every change as the whole new
 * Markdown; [onSegmentFocus] says a text field took the caret.
 */
@Composable
internal fun NoteRichBody(
    body: String,
    controller: NoteRichController,
    onBody: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(body) { noteSegments(body) }
    val latestBody by rememberUpdatedState(body)
    val change: (Int, String) -> Unit = { index, text ->
        val current = latestBody
        val seg = noteSegments(current).getOrNull(index)
        if (seg != null && seg.text != text) {
            controller.record(current)
            onBody(current.substring(0, seg.start) + text + current.substring(seg.end))
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (segments.isEmpty()) {
            RichTextSegment(0, NoteSegment(NoteSegment.Kind.TEXT, 0, 0, "", 0), controller, change)
        }
        for ((index, seg) in segments.withIndex()) {
            when (seg.kind) {
                NoteSegment.Kind.TEXT -> RichTextSegment(index, seg, controller, change)
                NoteSegment.Kind.IMAGES -> PicturesSegment(seg, body, controller, onBody)
                NoteSegment.Kind.TABLE -> TableSegment(index, seg, change)
            }
        }
        // After a picture or a table there is always somewhere to write.
        if (segments.isNotEmpty() && segments.last().kind != NoteSegment.Kind.TEXT) {
            Text(
                stringResource(R.string.notes_rich_write_below),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        controller.record(body, force = true)
                        controller.focused = segments.size
                        onBody(body + "\n")
                    }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** One run of text lines: a real text field, drawn rich. */
@Composable
private fun RichTextSegment(
    index: Int,
    seg: NoteSegment,
    controller: NoteRichController,
    onChange: (Int, String) -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val code = ink.copy(alpha = 0.10f)
    val stored = controller.values[index]
    val value = if (stored != null && stored.text == seg.text) {
        stored
    } else {
        val at = stored?.selection?.end?.coerceIn(0, seg.text.length) ?: seg.text.length
        TextFieldValue(seg.text, TextRange(at))
    }
    val fontSize = 17.sp
    val styled = remember(value.text, ink, dim, accent) { NoteRichStyle.annotate(value.text, fontSize, ink, dim, accent, code) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val latestValue by rememberUpdatedState(value)
    BasicTextField(
        value = value,
        onValueChange = { new ->
            val next = controller.settle(latestValue, new)
            controller.values[index] = next
            if (next.text != latestValue.text) onChange(index, next.text)
        },
        textStyle = TextStyle(color = ink, fontSize = fontSize, lineHeight = 25.sp),
        cursorBrush = SolidColor(accent),
        visualTransformation = RichTransformation(styled),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .focusRequester(controller.requester(index))
            .onFocusChanged { if (it.isFocused) controller.focused = index }
            .drawBehind {
                val l = layout?.takeIf { it.layoutInput.text.text == styled.text.text } ?: return@drawBehind
                with(NoteRichStyle) { drawDecorations(styled, l, ink, dim, accent) }
            }
            // A task's box: taken before the field sees the tap, so it ticks
            // rather than putting the caret there.
            .pointerInput(styled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val l = layout?.takeIf { it.layoutInput.text.text == styled.text.text } ?: return@awaitEachGesture
                    val line = NoteRichStyle.taskAt(styled, l, down.position) ?: return@awaitEachGesture
                    down.consume()
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial) ?: return@awaitEachGesture
                    up.consume()
                    val ticked = NoteChecklist.toggleTask(latestValue.text, line) ?: return@awaitEachGesture
                    controller.values[index] = latestValue.copy(text = ticked)
                    onChange(index, ticked)
                }
            },
    )
}

/** A row of pictures: one wide, or several side by side; a tap offers the layout, a move and removal. */
@Composable
private fun PicturesSegment(seg: NoteSegment, body: String, controller: NoteRichController, onBody: (String) -> Unit) {
    val context = LocalContext.current
    val urls = remember(seg.text) { NoteMarkup.images(seg.text).map { it.second } }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val read = remember(body) { NoteMarkup.read(body) }
    val line = seg.firstLine
    val neighbourHasPictures = read.lines.getOrNull(line + 1)?.kind == NoteMarkup.LineKind.IMAGES ||
        read.lines.getOrNull(line - 1)?.kind == NoteMarkup.LineKind.IMAGES
    fun change(next: String) {
        if (next == body) return
        controller.record(body, force = true)
        controller.values.clear()
        onBody(next)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        urls.forEachIndexed { k, url ->
            Box(Modifier.weight(1f)) {
                AsyncImage(
                    model = NoteAttachments.file(context.filesDir, url) ?: url,
                    contentDescription = null,
                    contentScale = if (urls.size > 1) ContentScale.Crop else ContentScale.Fit,
                    alignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (urls.size > 1) Modifier.aspectRatio(1f) else Modifier.heightIn(min = 80.dp, max = 420.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { menuFor = k },
                )
                DropdownMenu(expanded = menuFor == k, onDismissRequest = { menuFor = null }) {
                    if (urls.size > 1) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notes_rich_pictures_stack)) },
                            onClick = { menuFor = null; change(NoteRichEdits.imagesLayout(body, line, sideBySide = false)) },
                        )
                    } else if (neighbourHasPictures) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notes_rich_pictures_side_by_side)) },
                            onClick = { menuFor = null; change(NoteRichEdits.imagesLayout(body, line, sideBySide = true)) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notes_rich_move_up)) },
                        onClick = { menuFor = null; change(NoteRichEdits.moveBlock(NoteDraft(body, seg.start), up = true).text) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notes_rich_move_down)) },
                        onClick = { menuFor = null; change(NoteRichEdits.moveBlock(NoteDraft(body, seg.start), up = false).text) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notes_rich_picture_remove)) },
                        onClick = { menuFor = null; change(NoteRichEdits.removeImage(body, line, k)) },
                    )
                }
            }
        }
    }
}

/** A table as a grid of small fields, the header row bold, with a menu for rows and columns. */
@Composable
private fun TableSegment(index: Int, seg: NoteSegment, onChange: (Int, String) -> Unit) {
    val table = remember(seg.text) { NoteRichEdits.table(seg.text) } ?: return
    val ink = MaterialTheme.colorScheme.onSurface
    val line = MaterialTheme.colorScheme.outlineVariant
    var focusedCell by remember { mutableStateOf(0 to 0) }
    var menu by remember { mutableStateOf(false) }
    fun write(rows: List<List<String>>, align: List<String> = table.align) = onChange(index, NoteRichEdits.tableText(NoteTable(rows, align)))
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.notes_rich_table_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box {
                Text(
                    stringResource(R.string.notes_rich_table_edit),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { menu = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val (r, c) = focusedCell
                    DropdownMenuItem(text = { Text(stringResource(R.string.notes_rich_table_add_row)) }, onClick = {
                        menu = false
                        write(table.rows.toMutableList().apply { add((r + 1).coerceAtMost(size), List(table.columns) { "" }) })
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.notes_rich_table_add_column)) }, onClick = {
                        menu = false
                        val at = (c + 1).coerceAtMost(table.columns)
                        write(table.rows.map { it.toMutableList().apply { add(at, "") } }, table.align.toMutableList().apply { add(at, "---") })
                    })
                    if (table.rows.size > 2 && r > 0) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.notes_rich_table_remove_row)) }, onClick = {
                            menu = false
                            write(table.rows.toMutableList().apply { removeAt(r) })
                            focusedCell = (r - 1).coerceAtLeast(0) to c
                        })
                    }
                    if (table.columns > 1) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.notes_rich_table_remove_column)) }, onClick = {
                            menu = false
                            write(table.rows.map { it.toMutableList().apply { removeAt(c.coerceAtMost(size - 1)) } }, table.align.toMutableList().apply { removeAt(c.coerceAtMost(size - 1)) })
                            focusedCell = r to (c - 1).coerceAtLeast(0)
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.notes_rich_table_delete)) }, onClick = {
                        menu = false
                        onChange(index, "")
                    })
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .drawBehind { drawRect(line, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())) },
        ) {
            table.rows.forEachIndexed { r, cells ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            if (r > 0) drawLine(line, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx())
                        },
                ) {
                    cells.forEachIndexed { c, cell ->
                        var draft by remember(seg.text, r, c) { mutableStateOf(cell) }
                        BasicTextField(
                            value = draft,
                            onValueChange = { typed ->
                                val one = typed.replace('\n', ' ')
                                draft = one
                                write(table.rows.mapIndexed { rr, row -> if (rr == r) row.mapIndexed { cc, v -> if (cc == c) one else v } else row })
                            },
                            textStyle = TextStyle(
                                color = ink,
                                fontSize = 15.sp,
                                fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .weight(1f)
                                .drawBehind {
                                    if (c > 0) drawLine(line, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), 1.dp.toPx())
                                }
                                .onFocusChanged { if (it.isFocused) focusedCell = r to c }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
